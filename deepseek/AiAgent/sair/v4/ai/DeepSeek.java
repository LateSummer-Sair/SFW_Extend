package sair.v4.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

import sair.v4.Conf;
import sair.v4.kit.Fs;
import sair.v4.kit.Http;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * DeepSeek 完整客户端（基板能力⑦）。
 *
 * <p>端点：{@code {apiUrl}/chat/completions}、{@code {apiUrl}/user/balance}、{@code {apiUrl}/models}；
 * 头：{@code Authorization: Bearer <key>} + {@code Content-Type: application/json; charset=utf-8}。</p>
 *
 * <p>请求体由 {@link Conf} 驱动（model=resolveModel、temperature、top_p&lt;0 不发、max_tokens=0 不发），
 * 再被 {@code opts} 的同名键<b>直接覆盖</b>；{@code stream=true} 只在 {@link #stream} 里加。</p>
 *
 * <p>契约：<b>任何失败都收敛成 {@link Res#error}，绝不向调用方抛异常</b>；
 * 输出只走构造时传入的 {@link Out}，不用 System.out。</p>
 */
public final class DeepSeek {

    /** 流式增量回调。 */
    public interface Stream {
        void on(String deltaText);
    }

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_MS = 15000;
    /** 普通请求读超时（毫秒）。 */
    public static final int READ_MS = 180000;
    /** 流式读超时（毫秒）。 */
    public static final int STREAM_READ_MS = 300000;
    public static final String UA = "AiAgentV4/1.0";

    private static final Out SILENT = new Out() {
        @Override
        public void print(String text, Out.Tone tone) { /* 无输出 */ }
    };

    private final Conf conf;
    private final Out out;
    private volatile String keyOverride;

    public DeepSeek(Conf conf, Out out) {
        this.conf = (conf != null) ? conf : new Conf(new java.io.File("."));
        this.out = (out != null) ? out : SILENT;
    }

    // ---------------- 配置面 ----------------

    /** 临时指定 key（优先于 config.json；传空串则回落到 config.json）。 */
    public void setApiKey(String key) {
        this.keyOverride = (key == null) ? null : key.trim();
    }

    /** 当前生效的 key。 */
    public String apiKey() {
        String k = keyOverride;
        if (k != null && !k.isEmpty()) return k;
        String c = conf.apiKey();
        return (c == null) ? "" : c.trim();
    }

    /** 实际调用用的模型名（auto 已在 {@link Conf#resolveModel()} 里解析成默认模型）。 */
    public String model() {
        return conf.resolveModel();
    }

    /** 供调用方读取配置（数据根、超时等）。 */
    public Conf conf() {
        return conf;
    }

    // ---------------- 对话 ----------------

    /**
     * 模型调用闸门（由基板装配注入）：同一时刻只允许一次模型调用。
     * <p>这样"模型调用不并发"不只是一句注释 —— 技能在定时线程里直连模型、异步子 Agent、
     * 主回合都会排队（不设闸门时行为不变，便于单测）。</p>
     */
    private volatile java.util.concurrent.Semaphore gate;

    /**
     * 等闸门的最长时间（毫秒）的<b>默认值</b>；真正生效的值取配置 {@code modelGateWaitMs}
     * （见 {@link #gateWaitMs()}）。超过就放弃本次调用，避免定时技能把 tick 卡死。
     */
    public static final long GATE_WAIT_MS = 180_000L;

    /** 闸门观测面：等待路数、累计等待、等不到（放弃）次数 —— 进 {@code status}。 */
    private final java.util.concurrent.atomic.AtomicInteger gateWaiting =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong gateWaits =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong gateWaitMs =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong gateTimeouts =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger gateInFlight =
            new java.util.concurrent.atomic.AtomicInteger();

    public void setGate(java.util.concurrent.Semaphore g) { this.gate = g; }

    /** 等闸门的上限（毫秒）：配置 {@code modelGateWaitMs}，缺省 180000。 */
    public long gateWaitMs() {
        long v = conf.getLong("modelGateWaitMs", GATE_WAIT_MS);
        return v > 0 ? v : GATE_WAIT_MS;
    }

    /** 闸门许可数（配置 {@code modelConcurrency}，默认 1 = 模型调用不并发）。 */
    public int gatePermits() {
        java.util.concurrent.Semaphore g = gate;
        return g == null ? 0 : g.availablePermits() + gateInFlight.get();
    }

    /** 进闸门；返回 false = 等不到（本次调用放弃）。没有装闸门时直接放行。 */
    private boolean gateEnter() {
        java.util.concurrent.Semaphore g = gate;
        if (g == null) return true;
        long t0 = System.currentTimeMillis();
        try {
            if (g.tryAcquire()) {
                gateInFlight.incrementAndGet();
                return true;
            }
            gateWaiting.incrementAndGet();
            gateWaits.incrementAndGet();
            boolean got;
            try {
                got = g.tryAcquire(gateWaitMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } finally {
                gateWaiting.decrementAndGet();
                gateWaitMs.addAndGet(System.currentTimeMillis() - t0);
            }
            if (!got) {
                gateTimeouts.incrementAndGet();
                return false;
            }
            gateInFlight.incrementAndGet();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void gateExit() {
        java.util.concurrent.Semaphore g = gate;
        if (g != null) {
            gateInFlight.decrementAndGet();
            g.release();
        }
    }

    /** 闸门观测面（进 status）：许可数、当前在跑、正在等的路数、累计等待次数与时长、等不到次数。 */
    public JsonObject gateStat() {
        JsonObject o = new JsonObject();
        java.util.concurrent.Semaphore g = gate;
        o.addProperty("enabled", g != null);
        o.addProperty("permits", g == null ? 0 : g.availablePermits() + gateInFlight.get());
        o.addProperty("available", g == null ? 0 : g.availablePermits());
        o.addProperty("in_flight", gateInFlight.get());
        o.addProperty("waiting", gateWaiting.get());
        o.addProperty("waits", gateWaits.get());
        o.addProperty("wait_ms", gateWaitMs.get());
        long w = gateWaits.get();
        o.addProperty("avg_wait_ms", w <= 0 ? 0 : gateWaitMs.get() / w);
        o.addProperty("timeouts", gateTimeouts.get());
        o.addProperty("wait_limit_ms", gateWaitMs());
        return o;
    }

    /** 非流式对话。{@code tools} 可为 null；{@code opts} 覆盖请求体同名键。 */
    public Res chat(JsonArray messages, JsonArray tools, JsonObject opts) {
        if (!gateEnter()) return busy();
        try {
            return chat0(messages, tools, opts);
        } finally {
            gateExit();
        }
    }

    private Res busy() {
        Res r = new Res();
        r.error = "模型忙：等不到调用闸门（另一路模型调用超过 " + (gateWaitMs() / 1000) + " 秒）"
                + "；闸门许可数见配置 modelConcurrency（默认 1）";
        logWarn(r.error);
        return r;
    }

    private Res chat0(JsonArray messages, JsonArray tools, JsonObject opts) {
        Res r = new Res();
        long t0 = System.currentTimeMillis();
        try {
            if (Str.blank(apiKey())) {
                r.error = "apiKey 未配置";
                logWarn("chat 中止：apiKey 未配置");
                return r;
            }
            String url = endpoint("/chat/completions");
            String body = body(messages, tools, opts, false);
            if (verbose()) log("chat → " + model() + " msgs=" + size(messages) + " tools=" + size(tools));
            Http.Res h = Http.postJson(url, body, headers(false), READ_MS);
            if (!h.ok()) {
                r.error = "HTTP " + h.code + (Str.blank(h.body) ? "" : " " + Str.cut(Str.oneLine(h.body), 300));
                logWarn("chat HTTP " + h.code + " " + Str.cut(Str.oneLine(h.body), 200));
                return r;
            }
            JsonObject o = J.obj(h.body);
            if (o == null) {
                r.error = "响应不是 JSON: " + Str.cut(Str.oneLine(h.body), 200);
                logWarn(r.error);
                return r;
            }
            Sse.merge(o, r, null);                     // 非流式响应体与分片结构兼容，复用同一套合并
            JsonArray choices = J.list(o, "choices");
            if (r.ok() && (choices == null || choices.size() == 0)) {
                r.error = "响应缺少 choices: " + Str.cut(Str.oneLine(h.body), 200);
                logWarn(r.error);
                return r;
            }
            normalizeCalls(r);
            r.ms = System.currentTimeMillis() - t0;     // 事件行要报"用了多久"，必须在打日志之前落好
            if (logOn("model")) log("chat " + r.callEvent());
            if (verbose()) log("chat ← " + r.summary());
        } catch (Throwable t) {
            r.error = err(t);
            logWarn("chat 异常: " + r.error);
        } finally {
            r.ms = System.currentTimeMillis() - t0;
        }
        return r;
    }

    /**
     * 流式对话：SSE 增量一定回调 {@link Stream#on}，reasoning_content 累积进 {@code reasoning}，
     * tool_calls 分片按 index 合并。返回时 {@link Res} 与 {@link #chat} 同形。
     */
    public Res stream(JsonArray messages, JsonArray tools, JsonObject opts, final Stream onDelta) {
        if (!gateEnter()) return busy();
        try {
            return stream0(messages, tools, opts, onDelta);
        } finally {
            gateExit();
        }
    }

    private Res stream0(JsonArray messages, JsonArray tools, JsonObject opts, final Stream onDelta) {
        final Res r = new Res();
        long t0 = System.currentTimeMillis();
        HttpURLConnection c = null;
        BufferedReader br = null;
        try {
            if (Str.blank(apiKey())) {
                r.error = "apiKey 未配置";
                logWarn("stream 中止：apiKey 未配置");
                return r;
            }
            String url = endpoint("/chat/completions");
            String body = body(messages, tools, opts, true);
            if (verbose()) log("stream → " + model() + " msgs=" + size(messages) + " tools=" + size(tools));

            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(CONNECT_MS);        // 流式自己读，才能 connect/read 分开设
            c.setReadTimeout(STREAM_READ_MS);
            c.setDoOutput(true);
            c.setDoInput(true);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", UA);
            Map<String, String> hs = headers(true);
            for (Map.Entry<String, String> e : hs.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());

            OutputStream os = c.getOutputStream();
            try {
                os.write(body.getBytes(Fs.UTF8));
                os.flush();
            } finally {
                close(os);
            }

            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                String txt = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
                r.error = "HTTP " + code + (Str.blank(txt) ? "" : " " + Str.cut(Str.oneLine(txt), 300));
                logWarn("stream HTTP " + code + " " + Str.cut(Str.oneLine(txt), 200));
                return r;
            }

            Sse.Sink sink = null;
            if (onDelta != null) {
                sink = new Sse.Sink() {
                    @Override
                    public void on(String deltaText) {
                        try {
                            onDelta.on(deltaText);
                        } catch (Throwable ignored) {
                            // 回调方异常不打断流
                        }
                    }
                };
            }

            br = new BufferedReader(new InputStreamReader(c.getInputStream(), Fs.UTF8), 8192);
            String line;
            while ((line = br.readLine()) != null) {
                if (Sse.done(line)) break;
                Sse.feed(line, r, sink);
                if (!r.ok()) break;                     // 流中 error 分片
            }
            if (r.ok() && emptyStream(r)) {
                r.error = "流式响应为空（未收到有效分片）";
                logWarn(r.error);
            } else {
                normalizeCalls(r);
                r.ms = System.currentTimeMillis() - t0;   // 事件行要报"用了多久"，必须在打日志之前落好
                if (logOn("model")) log("stream " + r.callEvent());
                if (verbose()) log("stream ← " + r.summary());
            }
        } catch (Throwable t) {
            r.error = err(t);
            logWarn("stream 异常: " + r.error);
        } finally {
            close(br);
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
            r.ms = System.currentTimeMillis() - t0;
        }
        return r;
    }

    /**
     * 视觉对话：messages 里放 {@link Msg#userWithImages} 造的多模态消息。
     * 与 {@link #chat} 同一链路（基板不做任何模型路由，模型名仍来自 {@link Conf#resolveModel()}）。
     */
    public Res vision(JsonArray messages, JsonObject opts) {
        log("vision → " + model());
        return chat(messages, null, opts);
    }

    // ---------------- 账户与目录 ----------------

    /** 账户余额；失败返回 null。 */
    public JsonObject balance() {
        try {
            if (Str.blank(apiKey())) {
                logWarn("balance 跳过：apiKey 未配置");
                return null;
            }
            Http.Res h = Http.get(endpoint("/user/balance"), headers(false), READ_MS);
            if (!h.ok()) {
                logWarn("balance HTTP " + h.code + " " + Str.cut(Str.oneLine(h.body), 200));
                return null;
            }
            JsonObject o = J.obj(h.body);
            if (o == null) {
                logWarn("balance 响应不是 JSON");
                return null;
            }
            log("balance ← " + Str.cut(Str.oneLine(h.body), 200));
            return o;
        } catch (Throwable t) {
            logWarn("balance 异常: " + err(t));
            return null;
        }
    }

    /** 模型目录；失败返回空数组（不返回 null，不抛异常）。 */
    public JsonArray models() {
        JsonArray none = new JsonArray();
        try {
            if (Str.blank(apiKey())) {
                logWarn("models 跳过：apiKey 未配置");
                return none;
            }
            Http.Res h = Http.get(endpoint("/models"), headers(false), READ_MS);
            if (!h.ok()) {
                logWarn("models HTTP " + h.code + " " + Str.cut(Str.oneLine(h.body), 200));
                return none;
            }
            JsonElement e = J.el(h.body);
            if (e == null) {
                logWarn("models 响应不是 JSON");
                return none;
            }
            if (e.isJsonArray()) return e.getAsJsonArray();
            if (e.isJsonObject()) {
                JsonArray data = J.list(e.getAsJsonObject(), "data");
                if (data != null) return data;
            }
            return none;
        } catch (Throwable t) {
            logWarn("models 异常: " + err(t));
            return none;
        }
    }

    // ---------------- 请求构造 ----------------

    /** 组装请求体：Conf 打底 → opts 覆盖 → stream 只在流式加。 */
    private String body(JsonArray messages, JsonArray tools, JsonObject opts, boolean stream) {
        JsonObject b = new JsonObject();
        b.addProperty("model", model());
        b.addProperty("temperature", conf.temperature());
        double topP = conf.topP();
        if (topP >= 0) b.addProperty("top_p", topP);
        int maxTokens = conf.maxTokens();
        if (maxTokens > 0) b.addProperty("max_tokens", maxTokens);
        b.add("messages", messages == null ? new JsonArray() : messages);
        if (tools != null && tools.size() > 0) b.add("tools", tools);
        if (stream) b.addProperty("stream", true);
        if (opts != null) {
            for (Map.Entry<String, JsonElement> e : opts.entrySet()) {
                if (e.getKey() != null) b.add(e.getKey(), e.getValue());   // 直接覆盖同名键
            }
        }
        return J.json(b);
    }

    private Map<String, String> headers(boolean streaming) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        String k = apiKey();
        if (!Str.blank(k)) h.put("Authorization", "Bearer " + k);
        h.put("Content-Type", "application/json; charset=utf-8");
        h.put("Accept", streaming ? "text/event-stream" : "application/json");
        return h;
    }

    private String endpoint(String path) {
        String u = conf.apiUrl();
        if (Str.blank(u)) u = Conf.DEF_API_URL;
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u + path;
    }

    // ---------------- 内部 ----------------

    /** 非流式响应里的 tool_calls 也要保证 arguments 是字符串、字段齐全。 */
    private static void normalizeCalls(Res r) {
        if (r == null || r.toolCalls == null || r.toolCalls.size() == 0) return;
        JsonArray fixed = new JsonArray();
        for (int i = 0; i < r.toolCalls.size(); i++) {
            JsonElement e = r.toolCalls.get(i);
            if (e != null && e.isJsonObject()) fixed.add(Sse.norm(e.getAsJsonObject()));
        }
        r.toolCalls = fixed;
    }

    private static boolean emptyStream(Res r) {
        return Str.blank(r.content)
                && Str.blank(r.reasoning)
                && !r.hasTools()
                && Str.blank(r.finishReason)
                && r.usage == null;
    }

    private static int size(JsonArray a) {
        return a == null ? 0 : a.size();
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            BufferedReader b = new BufferedReader(new InputStreamReader(in, Fs.UTF8), 4096);
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = b.readLine()) != null) {
                sb.append(line).append('\n');
                if (sb.length() > 8192) break;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static void close(Closeable c) {
        if (c != null) try { c.close(); } catch (Throwable ignored) {}
    }

    private static String err(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (Str.blank(m) ? "" : ": " + m);
    }

    private void log(String s) {
        try {
            out.dim("[ai] " + s);
        } catch (Throwable ignored) {}
    }

    /** 这一类别要不要进控制台（见 {@code Conf.logOn}；默认只留 tool/model 两类）。 */
    private boolean logOn(String cat) {
        try {
            return conf != null && conf.logOn(cat);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 排障总开关。 */
    private boolean verbose() {
        try {
            return conf != null && conf.logVerbose();
        } catch (Throwable t) {
            return false;
        }
    }

    private void logWarn(String s) {
        try {
            out.warn("[ai] " + s);
        } catch (Throwable ignored) {}
    }
}
