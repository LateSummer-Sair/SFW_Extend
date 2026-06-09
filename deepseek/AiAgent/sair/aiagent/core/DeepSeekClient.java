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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.aiagent.model.ChatMessage;

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

    /** 非流式请求的max_tokens */
    private static final int MAX_OUTPUT_TOKENS = 4096;

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
    public String chatStream(List<ChatMessage> messages) throws IOException {
        String jsonBody = buildRequestBody(messages, true);
        HttpURLConnection conn = createConnection(true);
        sendRequest(conn, jsonBody);
        checkResponse(conn);
        return readStreamResponse(conn);
    }

    /**
     * 非流式聊天 —— Agent模式使用，直接返回完整响应。
     * <p>
     * 不走 StreamPrinter，直接返回完整JSON解析结果。
     * </p>
     *
     * @param messages 对话消息列表
     * @return AI回复文本
     * @throws IOException 网络或API错误
     */
    public String chatSync(List<ChatMessage> messages) throws IOException {
        String jsonBody = buildRequestBody(messages, false);
        HttpURLConnection conn = createConnection(false);
        sendRequest(conn, jsonBody);
        checkResponse(conn);
        String json = readAll(conn.getInputStream());
        return extractFirstContent(json);
    }

    // ==================== HTTP通信 ====================

    /** 构建完整API URL */
    private String buildApiUrl() {
        String url = config.getApiUrl();
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
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
            os.flush();
        }
    }

    /** 检查HTTP响应码 */
    private void checkResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code != 200) {
            String error = readAll(conn.getErrorStream());
            throw new IOException("API Error [" + code + "]: " + error);
        }
    }

    // ==================== 请求体构建 ====================

    /**
     * 手动构建JSON请求体。
     * <p>不依赖第三方JSON库，使用字符串拼接。
     * 所有字段值均经过转义处理。</p>
     */
    private String buildRequestBody(List<ChatMessage> messages, boolean stream) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"model\":\"").append(jsonEscape(config.getModel())).append("\",");
        sb.append("\"messages\":[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) sb.append(",");
            ChatMessage msg = messages.get(i);
            sb.append("{\"role\":\"").append(jsonEscape(msg.getRole())).append("\",");
            sb.append("\"content\":\"").append(jsonEscape(msg.getContent())).append("\"}");
        }
        sb.append("],\"stream\":").append(stream);
        if (!stream) {
            sb.append(",\"max_tokens\":").append(MAX_OUTPUT_TOKENS);
        }
        sb.append("}");
        return sb.toString();
    }

    // ==================== 响应解析 ====================

    /**
     * 读取SSE流式响应。
     * <p>
     * 逐行读取，解析 "data: " 前缀的行，提取 delta.content，
     * 将字符送入 StreamPrinter 队列。
     * </p>
     */
    private String readStreamResponse(HttpURLConnection conn)
            throws IOException {
        StringBuilder fullContent = new StringBuilder();
        StreamPrinter printer = StreamPrinter.getInstance();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(SSE_PREFIX)) continue;
                String data = line.substring(SSE_PREFIX.length()).trim();
                if (SSE_DONE.equals(data)) break;

                String delta = extractDelta(data);
                if (delta != null && !delta.isEmpty()) {
                    fullContent.append(delta);
                    // 送入单例流式打印机
                    printer.offer(delta);
                }
            }
        }
        return fullContent.toString();
    }

    /**
     * 从SSE的data JSON中提取 delta.content。
     * <p>先定位 delta 对象边界（括号计数绕过嵌套JSON），再提取 content。</p>
     */
    static String extractDelta(String json) {
        int deltaIdx = json.indexOf("\"delta\"");
        if (deltaIdx < 0) return "";
        int braceStart = json.indexOf('{', deltaIdx + 8);
        if (braceStart < 0) return "";
        int depth = 1;
        int pos = braceStart + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') { pos += 2; continue; }
                    if (sc == '"') break;
                    pos++;
                }
            }
            pos++;
        }
        String deltaObj = json.substring(braceStart, pos);
        Pattern p = Pattern.compile(
            "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(deltaObj);
        return m.find() ? jsonUnescape(m.group(1)) : "";
    }

    /**
     * 从非流式响应的JSON中提取 message.content 字段。
     * <p>先定位 message 块边界，再排除 tool_calls/function_call 中的 content。</p>
     */
    static String extractFirstContent(String json) {
        int msgIdx = json.indexOf("\"message\"");
        if (msgIdx < 0) {
            Pattern p = Pattern.compile(
                "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(json);
            return m.find() ? jsonUnescape(m.group(1)) : "";
        }
        int msgBrace = json.indexOf('{', msgIdx + 9);
        if (msgBrace < 0) return "";
        int depth = 1;
        int pos = msgBrace + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') { pos += 2; continue; }
                    if (sc == '"') break;
                    pos++;
                }
            }
            pos++;
        }
        String msgBlock = json.substring(msgBrace, pos);
        String result = "";
        Pattern p = Pattern.compile(
            "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(msgBlock);
        while (m.find()) {
            int matchStart = m.start();
            String before = msgBlock.substring(0, matchStart);
            int toolCallsIdx = before.lastIndexOf("\"tool_calls\"");
            int funcCallIdx = before.lastIndexOf("\"function_call\"");
            int roleIdx = before.lastIndexOf("\"role\"");
            boolean isTool = false;
            if (toolCallsIdx >= 0 && toolCallsIdx > roleIdx) isTool = true;
            if (funcCallIdx >= 0 && funcCallIdx > roleIdx) isTool = true;
            if (roleIdx >= 0 && roleIdx < matchStart && !isTool) {
                result = m.group(1);
            }
        }
        return result.isEmpty() ? "" : jsonUnescape(result);
    }

    /** 读取整个InputStream为字符串 */
    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
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
    static String jsonUnescape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"':  sb.append('"');  i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/':  sb.append('/');  i++; break;
                    case 'b':  sb.append('\b'); i++; break;
                    case 'f':  sb.append('\f'); i++; break;
                    case 'n':  sb.append('\n'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                String hex = s.substring(i + 2, i + 6);
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append("\\u");
                                i++;
                            }
                        } else {
                            sb.append("\\u");
                            i++;
                        }
                        break;
                    default:
                        sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
