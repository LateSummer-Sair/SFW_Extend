package sair.aiagent.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.ToolCall;
import sair.aiagent.model.ToolDefinition;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * DeepSeek API 客户端 —— 策略模式（流式/非流式）。
 * <p>
 * 封装与 DeepSeek API 的全部HTTP通信逻辑，支持：
 * <ul>
 *   <li>OpenAI 兼容的 Chat Completions 接口</li>
 *   <li>SSE 流式响应解析（stream=true）</li>
 *   <li>JSON 非流式响应解析（Agent模式使用）</li>
 * </ul>
 * </p>
 *
 * <h3>API规范</h3>
 * <pre>
 * POST {baseUrl}/v1/chat/completions
 * Authorization: Bearer {apiKey}
 * Content-Type: application/json
 * </pre>
 */
public class DeepSeekClient {

    /** SSE数据行前缀 */
    private static final String SSE_PREFIX = "data: ";

    /** SSE结束标记 */
    private static final String SSE_DONE = "[DONE]";

    /** HTTP超时：连接 */
    private static final int CONNECT_TIMEOUT = 30_000;

    /** HTTP超时：读取（5分钟，足够长响应） */
    private static final int READ_TIMEOUT = 300_000;

    /** 非流式请求默认 max_tokens */
    private static final int DEFAULT_MAX_TOKENS = 4096;
    /** Agent/execs 模式 max_tokens（支持长输出） */
    private static final int AGENT_MAX_TOKENS = 32768;
    /** 重试最大次数（429/503） */
    private static final int MAX_RETRIES = 3;
    /** 重试退避基础等待 ms */
    private static final int RETRY_BASE_MS = 2000;

    /** 累计 token 用量 */
    private volatile long totalPromptTokens = 0;
    private volatile long totalCompletionTokens = 0;
    private volatile long totalCacheHitTokens = 0;
    private volatile long totalCacheMissTokens = 0;

    // ==================== 配置引用 ====================

    private final AiConfig config;

    /**
     * 构造客户端。
     *
     * @param config 配置管理器单例
     */
    public DeepSeekClient(AiConfig config) {
        this.config = config;
    }

    // ==================== 公共API ====================

    /**
     * 流式聊天 —— 通过单例 StreamPrinter 逐字输出。
     * <p>
     * 调用者应先调用 {@code StreamPrinter.getInstance().start()}，再调用此方法。
     * API返回的每个文本增量会通过 {@code offer()} 送入打印队列。
     * </p>
     *
     * @param messages 对话消息列表
     * @return 完整的AI回复文本
     * @throws IOException 网络或API错误
     */
    /** chatSync 返回结果（content + reasoning） */
    public static class SyncResult {
        public final String content;
        public final String reasoningContent;
        public SyncResult(String c, String r) { this.content = c; this.reasoningContent = r != null ? r : ""; }
    }

    /** Function Calling 返回结果（content + reasoning + tool_calls） */
    public static class ToolCallResult {
        public final String content;
        public final String reasoningContent;
        public final List<ToolCall> toolCalls;
        public ToolCallResult(String c, String r, List<ToolCall> tc) {
            this.content = c;
            this.reasoningContent = r != null ? r : "";
            this.toolCalls = (tc != null && !tc.isEmpty())
                    ? java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tc)) : null;
        }
        public boolean hasToolCalls() { return toolCalls != null && !toolCalls.isEmpty(); }
    }

    /** 流式聊天返回结果 */
    public static class StreamResult {
        public final String content;
        public final String reasoningContent;
        public StreamResult(String c, String r) { this.content = c; this.reasoningContent = r != null ? r : ""; }
    }

    /**
     * 流式聊天 — 指定模型版本，兼容旧接口。
     */
    public String chatStream(List<ChatMessage> messages, String modelOverride) throws IOException {
        StreamResult r = chatStreamFull(messages, modelOverride);
        return r != null ? r.content : "";
    }
    
    public String chatStream(List<ChatMessage> messages) throws IOException {
        return chatStream(messages, AiConfig.getInstance().getExecqModel());
    }

    /** 流式聊天 — 返回 content + reasoning_content */
    public StreamResult chatStreamFull(List<ChatMessage> messages, String modelOverride) throws IOException {
        String jsonBody = buildRequestBody(messages, true, modelOverride);
        HttpURLConnection conn = createConnection(true);
        try {
            sendRequest(conn, jsonBody);
            checkResponse(conn);
            return readStreamResponse(conn);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 非流式聊天 — 指定模型版本，兼容旧接口。
     */
    public String chatSync(List<ChatMessage> messages, String modelOverride) throws IOException {
        SyncResult r = chatSyncFull(messages, modelOverride);
        return r != null ? r.content : "";
    }
    
    public String chatSync(List<ChatMessage> messages) throws IOException {
        return chatSync(messages, AiConfig.getInstance().getExecqModel());
    }

    /** 非流式聊天 — 返回 content + reasoning_content */
    public SyncResult chatSyncFull(List<ChatMessage> messages, String modelOverride) throws IOException {
        String jsonBody = buildRequestBody(messages, false, modelOverride);
        HttpURLConnection conn = createConnection(false);
        try {
            sendRequest(conn, jsonBody);
            checkResponse(conn);
            String json = DeepSeekResponseParser.readAll(conn.getInputStream());
            String content = DeepSeekResponseParser.extractFirstContent(json);
            String reasoning = DeepSeekResponseParser.extractReasoningContent(json);
            trackUsage(json);
            return new SyncResult(content, reasoning);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 非流式聊天 + Function Calling —— 返回 content + reasoning + tool_calls。
     * <p>当 AI 决定调用工具时，返回的 toolCalls 非空；否则返回普通文本。</p>
     *
     * @param messages    对话消息列表
     * @param tools       工具定义列表
     * @param toolChoice  tool_choice 值（"auto"/"none"/"required"/工具名，null=默认 auto）
     * @param modelOverride 模型覆盖（null=使用默认 execq 模型）
     * @return ToolCallResult
     */
    public ToolCallResult chatSyncWithTools(List<ChatMessage> messages, List<ToolDefinition> tools,
                                            String toolChoice, String modelOverride) throws IOException {
        String jsonBody = buildRequestBody(messages, false, modelOverride, tools, toolChoice);
        HttpURLConnection conn = createConnection(false);
        try {
            sendRequest(conn, jsonBody);
            checkResponse(conn);
            String json = DeepSeekResponseParser.readAll(conn.getInputStream());
            String content = DeepSeekResponseParser.extractFirstContent(json);
            String reasoning = DeepSeekResponseParser.extractReasoningContent(json);
            List<ToolCall> toolCalls = DeepSeekResponseParser.extractToolCalls(json);
            trackUsage(json);
            return new ToolCallResult(content, reasoning, toolCalls);
        } finally {
            conn.disconnect();
        }
    }

    // ==================== HTTP通信 ====================

    /** 构建完整API URL（strict 模式下自动切 /beta） */
    private String buildApiUrl() {
        String url = config.getEffectiveApiUrl();
        if (!url.endsWith("/")) url += "/";
        return url + "v1/chat/completions";
    }

    /** 创建HTTP连接 */
    private HttpURLConnection createConnection(boolean isStream) throws IOException {
        URL url;
        try {
            url = new URI(buildApiUrl()).toURL();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid API URL: " + buildApiUrl(), e);
        }
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
        conn.setRequestProperty("Accept",
                isStream ? "text/event-stream" : "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        return conn;
    }

    /** 发送请求体 */
    private void sendRequest(HttpURLConnection conn, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // 显式设置 Content-Length，避免 Java HttpURLConnection 使用 chunked 分块编码
        // chunked 编码在发送大体积请求体（如含 base64 图片的 Vision API 请求）时可能导致
        // 某些 API 网关/代理连接超时
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }
    }

    /** 检查HTTP响应码，区分错误类型 */
    private void checkResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code == 200) return;
        String error = DeepSeekResponseParser.readAll(conn.getErrorStream());
        String detail = "API Error [" + code + "]";
        switch (code) {
            case 401: detail += " 认证失败——请检查 API Key 是否正确"; break;
            case 402: detail += " 余额不足——请充值后重试"; break;
            case 422: detail += " 参数错误——请检查请求体"; break;
            case 429: detail += " 请求频率超限——请降低请求速率"; break;
            case 500: detail += " 服务器内部故障——请稍后重试"; break;
            case 503: detail += " 服务器繁忙——请稍后重试"; break;
            default:  break;
        }
        detail += ": " + error;
        AiAgentActivity.debugLog("[DeepSeek] " + detail);
        throw new IOException(detail);
    }

    // ==================== 请求体构建 (Gson) ====================

    /**
     * Build JSON request body using Gson JsonObject (standard library serialization).
     * <p>Replaces manual StringBuilder concatenation with type-safe Gson builder API.</p>
     */
    private String buildRequestBody(List<ChatMessage> messages, boolean stream) {
        return buildRequestBody(messages, stream, null);
    }

    /** Build request body, optionally overriding the model */
    private String buildRequestBody(List<ChatMessage> messages, boolean stream, String modelOverride) {
        return buildRequestBody(messages, stream, modelOverride, null, null);
    }

    /**
     * Build request body with Function Calling tools support.
     * @param tools     工具定义列表（null/空 = 不使用 Function Calling）
     * @param toolChoice tool_choice 值（"auto"/"none"/"required"/工具名，null=不设置）
     */
    private String buildRequestBody(List<ChatMessage> messages, boolean stream, String modelOverride,
                                     List<ToolDefinition> tools, String toolChoice) {
        AiConfig cfg = AiConfig.getInstance();
        String effectiveModel = (modelOverride != null && !modelOverride.isEmpty())
                ? modelOverride : cfg.getExecqModel();

        JsonObject root = new JsonObject();
        root.addProperty("model", effectiveModel);
        root.addProperty("stream", stream);

        // --- messages array ---
        JsonArray msgs = new JsonArray();
        for (ChatMessage msg : messages) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.getRole());

            if (msg.hasImages() && msg.getContentParts() != null) {
                // Multimodal: content as Vision API array format
                JsonArray contentArr = new JsonArray();
                for (java.util.Map<String, Object> part : msg.getContentParts()) {
                    JsonObject p = new JsonObject();
                    String type = (String) part.get("type");
                    p.addProperty("type", type);
                    if ("text".equals(type)) {
                        p.addProperty("text", (String) part.get("text"));
                    } else if ("image_url".equals(type)) {
                        @SuppressWarnings("unchecked")
                        java.util.Map<String, Object> imgObj = (java.util.Map<String, Object>) part.get("image_url");
                        if (imgObj != null) {
                            JsonObject img = new JsonObject();
                            img.addProperty("url", (String) imgObj.get("url"));
                            p.add("image_url", img);
                        }
                    }
                    contentArr.add(p);
                }
                m.add("content", contentArr);
            } else if ("tool".equals(msg.getRole())) {
                // tool 角色消息：content + tool_call_id + name（Function Calling 回传）
                m.addProperty("content", msg.getContent());
                if (msg.getToolCallId() != null) {
                    m.addProperty("tool_call_id", msg.getToolCallId());
                }
                if (msg.getToolName() != null) {
                    m.addProperty("name", msg.getToolName());
                }
            } else {
                m.addProperty("content", msg.getContent());
            }

            // reasoning_content for assistant messages (multi-turn thinking mode)
            if ("assistant".equals(msg.getRole()) && msg.hasReasoning()) {
                m.addProperty("reasoning_content", msg.getReasoningContent());
            }

            // tool_calls for assistant messages (Function Calling 多轮回传)
            if (msg.hasToolCalls()) {
                JsonArray tcArr = new JsonArray();
                for (ToolCall tc : msg.getToolCalls()) {
                    JsonObject tco = new JsonObject();
                    tco.addProperty("id", tc.getId());
                    tco.addProperty("type", "function");
                    JsonObject fn = new JsonObject();
                    fn.addProperty("name", tc.getName());
                    fn.addProperty("arguments", tc.getArguments());
                    tco.add("function", fn);
                    tcArr.add(tco);
                }
                m.add("tool_calls", tcArr);
            }
            msgs.add(m);
        }
        root.add("messages", msgs);

        // --- max_tokens ---
        int maxTok = cfg.getMaxOutputTokens();
        if (maxTok <= 0) maxTok = DEFAULT_MAX_TOKENS;
        root.addProperty("max_tokens", maxTok);

        // --- thinking mode (思考模式开关) ---
        // DeepSeek thinking 默认 enabled、effort 默认 high。为让「空/none=关闭思考模式」语义成立，
        // 显式下发 thinking 开关。思考模式下 temperature/top_p/frequency_penalty/presence_penalty
        // 不生效（下发也会被忽略），故仅在关闭思考时下发这些采样参数，避免误导。
        String re = cfg.getReasoningEffort();
        boolean thinkingEnabled = (re != null && !re.isEmpty() && !"none".equalsIgnoreCase(re));
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", thinkingEnabled ? "enabled" : "disabled");
        root.add("thinking", thinking);
        if (thinkingEnabled) {
            root.addProperty("reasoning_effort", re);
        }

        if (!thinkingEnabled) {
            // --- temperature ---
            double temp = cfg.getTemperature();
            if (temp < 0 && hasMultimodalContent(messages)) {
                temp = 0.1; // Vision API recommended temp
            }
            if (temp >= 0 && temp <= 2.0) {
                root.addProperty("temperature", temp);
            }

            // --- top_p ---
            double tp = cfg.getTopP();
            if (tp > 0 && tp <= 1.0) {
                root.addProperty("top_p", tp);
            }

            // --- frequency_penalty ---
            double fp = cfg.getFrequencyPenalty();
            if (fp >= -2.0 && fp <= 2.0) {
                root.addProperty("frequency_penalty", fp);
            }

            // --- presence_penalty ---
            double pp = cfg.getPresencePenalty();
            if (pp >= -2.0 && pp <= 2.0) {
                root.addProperty("presence_penalty", pp);
            }
        }

        // --- stop sequences ---
        String stop = cfg.getStopSequences();
        if (stop != null && !stop.isEmpty()) {
            JsonArray stopArr = new JsonArray();
            for (String s : stop.split(",")) {
                stopArr.add(s.trim());
            }
            root.add("stop", stopArr);
        }

        // --- response_format (JSON mode) ---
        String respFmt = cfg.getResponseFormat();
        if (respFmt != null && !respFmt.isEmpty()) {
            JsonObject rf = new JsonObject();
            rf.addProperty("type", respFmt);
            root.add("response_format", rf);
        }

        // --- user_id (cache isolation / content safety) ---
        String uid = cfg.getDeepSeekUserId();
        if (uid != null && !uid.isEmpty()) {
            root.addProperty("user_id", uid);
        }

        // --- tools (Function Calling) ---
        if (tools != null && !tools.isEmpty()) {
            com.google.gson.Gson gson = new com.google.gson.Gson();
            JsonArray toolsArr = new JsonArray();
            for (ToolDefinition td : tools) {
                toolsArr.add(gson.toJsonTree(td.toRequestObject()));
            }
            root.add("tools", toolsArr);
            if (toolChoice != null && !toolChoice.isEmpty()) {
                root.addProperty("tool_choice", toolChoice);
            }
        }

        return root.toString();
    }


    // ==================== 响应解析 ====================

    /**
     * 读取SSE流式响应。
     * <p>
     * 逐行读取，解析 "data: " 前缀的行，提取 delta.content，
     * 将字符送入 StreamPrinter 队列。
     * </p>
     */
    private StreamResult readStreamResponse(HttpURLConnection conn)
            throws IOException {
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();
        StreamPrinter printer = StreamPrinter.getInstance();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SSE_PREFIX)) continue;
                String data = line.substring(SSE_PREFIX.length()).trim();
                if (SSE_DONE.equals(data)) break;

                // Extract reasoning_content first (may also contain content delta)
                String reasoningDelta = DeepSeekResponseParser.extractReasoningDelta(data);
                if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                    fullReasoning.append(reasoningDelta);
                }

                String delta = DeepSeekResponseParser.extractDelta(data);
                if (delta != null && !delta.isEmpty()) {
                    fullContent.append(delta);
                    printer.offer(delta);
                }

                // Track usage from last chunk (has finish_reason + usage)
                trackUsage(data);
            }
        }
        return new StreamResult(fullContent.toString(), fullReasoning.toString());
    }

    /**
     * 从SSE的data JSON中提取 delta.reasoning_content。
     */
    /** @deprecated delegated to DeepSeekResponseParser */
    static String extractReasoningDelta(String json) {
        return DeepSeekResponseParser.extractReasoningDelta(json);
    }

    /**
     * 从SSE的data JSON中提取 delta.content。
     * <p>先定位 delta 对象边界（括号计数绕过嵌套JSON），再提取 content。</p>
     */
    /** @deprecated delegated to DeepSeekResponseParser */
    static String extractDelta(String json) {
        return DeepSeekResponseParser.extractDelta(json);
    }

    /**
     * 从非流式响应的JSON中提取 message.reasoning_content 字段。
     */
    static String extractReasoningContent(String json) {
        return DeepSeekResponseParser.extractReasoningContent(json);
    }

    /**
     * 从非流式响应的JSON中提取 message.content 字段。
     * <p>先定位 message 块边界，再排除 tool_calls/function_call 中的 content。</p>
     */
    static String extractFirstContent(String json) {
        return DeepSeekResponseParser.extractFirstContent(json);
    }

    // ==================== Token 用量追踪 ====================

    /** Track usage from API response JSON (works for both stream chunks and sync) */
    private void trackUsage(String json) {
        if (json == null || !json.contains("\"usage\"")) return;
        try {
            // Only count final chunks (has finish_reason or is non-stream)
            if (!json.contains("\"finish_reason\"") && !json.contains("\"object\":\"chat.completion\"")) return;
            Pattern pp = Pattern.compile("\"prompt_tokens\"\\s*:\\s*(\\d+)");
            Pattern cp = Pattern.compile("\"completion_tokens\"\\s*:\\s*(\\d+)");
            Pattern hp = Pattern.compile("\"prompt_cache_hit_tokens\"\\s*:\\s*(\\d+)");
            Pattern mp = Pattern.compile("\"prompt_cache_miss_tokens\"\\s*:\\s*(\\d+)");
            Matcher pm = pp.matcher(json);
            Matcher cm = cp.matcher(json);
            Matcher hm = hp.matcher(json);
            Matcher mm = mp.matcher(json);
            if (pm.find()) totalPromptTokens += Long.parseLong(pm.group(1));
            if (cm.find()) totalCompletionTokens += Long.parseLong(cm.group(1));
            if (hm.find()) totalCacheHitTokens += Long.parseLong(hm.group(1));
            if (mm.find()) totalCacheMissTokens += Long.parseLong(mm.group(1));
        } catch (Exception ignored) {}
    }

    /** Get cumulative token usage stats */
    public long[] getUsageStats() {
        return new long[] { totalPromptTokens, totalCompletionTokens,
                totalCacheHitTokens, totalCacheMissTokens };
    }

    /** Get formatted usage summary */
    public String getUsageSummary() {
        long total = totalPromptTokens + totalCompletionTokens;
        long cacheTotal = totalCacheHitTokens + totalCacheMissTokens;
        double hitRate = cacheTotal > 0 ? (double) totalCacheHitTokens / cacheTotal * 100 : 0;
        return String.format("Tokens: prompt=%,d comp=%,d total=%,d | Cache: hit=%,d miss=%,d rate=%.0f%%",
                totalPromptTokens, totalCompletionTokens, total,
                totalCacheHitTokens, totalCacheMissTokens, hitRate);
    }

    /** 读取整个InputStream为字符串 */
    /** @deprecated delegated to DeepSeekResponseParser */
    private static String readAll(InputStream is) throws IOException {
        return DeepSeekResponseParser.readAll(is);
    }

    // ==================== 余额查询 ====================

    /** 查询 DeepSeek 账户余额。返回格式化的余额字符串，失败返回 null。 */
    public String queryBalance() throws IOException {
        String apiUrl = config.getApiUrl();
        if (!apiUrl.endsWith("/")) apiUrl += "/";
        URL url = new URL(apiUrl + "user/balance");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            int code = conn.getResponseCode();
            if (code != 200) {
                return "Balance query failed: HTTP " + code;
            }
            String json = "";
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String l; StringBuilder jsb = new StringBuilder();
                while ((l = br.readLine()) != null) jsb.append(l);
                json = jsb.toString();
            }
            StringBuilder sb = new StringBuilder();
            Pattern cp = Pattern.compile("\"currency\"\\s*:\\s*\"([^\"]+)\"");
            Pattern tp = Pattern.compile("\"total_balance\"\\s*:\\s*\"([^\"]+)\"");
            Pattern gp = Pattern.compile("\"granted_balance\"\\s*:\\s*\"([^\"]+)\"");
            Pattern up = Pattern.compile("\"topped_up_balance\"\\s*:\\s*\"([^\"]+)\"");
            Matcher tm = tp.matcher(json);
            Matcher gm = gp.matcher(json);
            Matcher um = up.matcher(json);
            if (tm.find()) {
                Matcher cm = cp.matcher(json);
                String currency = cm.find() ? cm.group(1) : "CNY";
                sb.append("Balance(").append(currency).append("): ").append(tm.group(1));
                if (gm.find()) sb.append(" (granted:").append(gm.group(1)).append(")");
                if (um.find()) sb.append(" (toppedUp:").append(um.group(1)).append(")");
            }
            return sb.length() > 0 ? sb.toString() : json;
        } finally {
            conn.disconnect();
        }
    }

    // ==================== JSON工具方法 ====================

    /**
     * 转义字符串用于JSON值。
     * <p>处理双引号、反斜杠、换行等特殊字符。</p>
     */
    static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /**
     * 反转义JSON字符串 —— 逐字符状态机解析，正确处理转义顺序。
     * <p>支持 \\ \" \/ \b \f \n \r \t \\uXXXX。</p>
     */
    /** @deprecated delegated to DeepSeekResponseParser */
    static String jsonUnescape(String s) {
        return DeepSeekResponseParser.jsonUnescape(s);
    }

    /** Check if any message in the list contains multimodal (image) content */
    private static boolean hasMultimodalContent(java.util.List<sair.aiagent.model.ChatMessage> messages) {
        if (messages == null) return false;
        for (sair.aiagent.model.ChatMessage msg : messages) {
            if (msg.hasImages()) return true;
        }
        return false;
    }
}
