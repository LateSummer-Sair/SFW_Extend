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
    /** 模型名校验只报一次（同一个不在官方列表里的名字不重复刷 warn）。 */
    private volatile String warnedModel;
    /** stop 配置异常只报一次。 */
    private volatile String warnedStop;

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
     * 没装配闸门时的哨兵：{@link #gateEnter()} 返回它表示"放行、但无需释放"。
     * 用哨兵而不是 null，是为了把"没装闸门"和"等不到许可"区分开（后者返回 null ⇒ {@link #busy()}）。
     */
    private static final java.util.concurrent.Semaphore NO_GATE = new java.util.concurrent.Semaphore(0);

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

    /**
     * 进闸门：返回<b>这次真正拿到的那一个</b> {@link java.util.concurrent.Semaphore}；
     * 没装配闸门时返回哨兵 {@link #NO_GATE}；返回 null = 等不到（本次调用放弃）。
     * <p>释放时必须把返回值还给 {@link #gateExit(java.util.concurrent.Semaphore)} ——
     * 不能再去读 {@code gate} 字段：调用途中若换了闸门，释放的就不是当初拿到的那个。</p>
     */
    private java.util.concurrent.Semaphore gateEnter() {
        java.util.concurrent.Semaphore g = gate;
        if (g == null) return NO_GATE;
        long t0 = System.currentTimeMillis();
        try {
            if (g.tryAcquire()) {
                gateInFlight.incrementAndGet();
                return g;
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
                return null;
            }
            gateInFlight.incrementAndGet();
            return g;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 释放 {@link #gateEnter()} 返回的那一个许可（null / 哨兵 ⇒ 什么都不做）。 */
    private void gateExit(java.util.concurrent.Semaphore g) {
        if (g == null || g == NO_GATE) return;
        gateInFlight.decrementAndGet();
        g.release();
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
        java.util.concurrent.Semaphore g = gateEnter();
        if (g == null) return busy();
        try {
            return chat0(messages, tools, opts);
        } finally {
            gateExit(g);
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
        java.util.concurrent.Semaphore g = gateEnter();
        if (g == null) return busy();
        try {
            // 瞬时失败静默重试一次（真机 2026-09-19：connect timed out 让整轮泡汤，会话里冒出一句兜底话）。
            // **只有这一轮一个字都还没吐出去时才重试** —— 已经吐过字的流重试会把同一段话说两遍。
            // 另外 onDelta == null（纯取结果、不要增量）时不重试：没有增量回调就无从判断"是否已经吐过字"，
            // 宁可把错误原样返回，也不做一次看不见的重复请求。
            final boolean[] got = new boolean[1];
            final Stream once = onDelta == null ? null : new Stream() {
                @Override
                public void on(String delta) {
                    got[0] = true;
                    try {
                        onDelta.on(delta);
                    } catch (Throwable ignored) {
                    }
                }
            };
            Res r = stream0(messages, tools, opts, once);
            if (onDelta != null && !r.ok() && !got[0] && retryable(r.error)) {
                logWarn("stream 瞬时失败，重试一次：" + r.error);
                r = stream0(messages, tools, opts, once);
            }
            return r;
        } finally {
            gateExit(g);
        }
    }

    /** 这个错误值不值得重试一次：连接/读取超时、连接被掐断、HTTP 5xx 与 429。 */
    private static boolean retryable(String err) {
        if (Str.blank(err)) return false;
        String s = err.toLowerCase();
        if (s.contains("timeout") || s.contains("timed out") || s.contains("connect")
                || s.contains("connection reset") || s.contains("broken pipe")
                || s.contains("unexpected end") || s.contains("eof")) return true;
        return s.contains("http 500") || s.contains("http 502") || s.contains("http 503")
                || s.contains("http 504") || s.contains("http 429");
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

    /**
     * 组装请求体：Conf 打底 → opts 覆盖 → 再按"最终生效"的字段做收尾。
     *
     * <p>收尾两件事必须在 opts 合并之后做，否则看的都不是真正发出去的值：</p>
     * <ul>
     *   <li>{@code stream_options}：只有最终 {@code stream=true} 才加（见 {@link #streamOn}）；</li>
     *   <li>{@code tool_choice} 冲突降级：见 {@link #fixToolChoice(JsonObject)}。</li>
     * </ul>
     *
     * <p>2026-11-20 升级新增的字段（thinking / reasoning_effort / response_format / stop /
     * safety_check / title / 官方联网工具）默认不发，只有配置显式打开才写进请求体；
     * 例外是 {@code reasoning_effort}：思考模式 auto/enabled（服务端开启思考）时按配置发，
     * 只有显式 {@code thinking=disabled} 才不发。</p>
     */
    private String body(JsonArray messages, JsonArray tools, JsonObject opts, boolean stream) {
        JsonObject b = new JsonObject();
        String modelName = model();
        b.addProperty("model", modelName);
        verifyModelName(modelName);                                 // 只 warn，不改发出去的模型名
        b.addProperty("temperature", conf.temperature());
        double topP = conf.topP();
        if (topP >= 0) b.addProperty("top_p", topP);
        int maxTokens = conf.maxTokens();
        if (maxTokens > 0) b.addProperty("max_tokens", maxTokens);
        b.add("messages", messages == null ? new JsonArray() : messages);
        JsonArray useTools = toolsWithWeb(tools);                   // aiWebTools=true 时追加官方联网工具（复制，不改调用方的数组）
        if (useTools != null && useTools.size() > 0) b.add("tools", useTools);
        addExtraFields(b);                                          // 升级新增字段：默认不发
        if (stream) b.addProperty("stream", true);
        if (opts != null) {
            for (Map.Entry<String, JsonElement> e : opts.entrySet()) {
                if (e.getKey() != null) b.add(e.getKey(), e.getValue());   // 直接覆盖同名键
            }
        }
        // stream_options 依赖 opts 覆盖后的最终 stream；调用方自己给了 stream_options 就不覆盖。
        if (streamOn(b) && !b.has("stream_options") && conf.streamUsage()) {
            JsonObject so = new JsonObject();
            so.addProperty("include_usage", true);
            b.add("stream_options", so);
        }
        fixToolChoice(b);                                           // 只看请求体里最终生效的 thinking / tools
        return J.json(b);
    }

    /**
     * 请求体里最终生效的 stream：opts 可能用布尔或字符串改写它，只有真值才算开。
     * （不是 {@code stream} 形参：那个只代表调用的是 chat 还是 stream 接口。）
     */
    private static boolean streamOn(JsonObject b) {
        JsonElement e = b.get("stream");
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return false;
        try {
            if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            String s = e.getAsString().trim();
            return "true".equalsIgnoreCase(s) || "1".equals(s)
                    || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s);
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------- 2026-11-20 API 升级：请求体新增字段 ----------------
    // 统一原则：默认值 = 干脆不发这个字段，配置显式打开才加。
    // 例外：reasoning_effort 跟随思考模式（auto 视为服务端开启思考 ⇒ 照发；disabled 才不发）。

    /**
     * 思考模式：null = auto（不发该字段，服务端默认开启）；否则 "enabled" / "disabled"。
     * <p>读配置一律走 {@link Conf#thinking()} 这些新 getter（Conf 里注明它们是这 10 个键的
     * <b>唯一读口</b>，{@code knownKeys()} 自省面读的也是它们），不再自己抄一遍键名和缺省值。</p>
     */
    private String thinkingType() {
        String v = conf.thinking().trim();
        if ("enabled".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v) || "true".equalsIgnoreCase(v)) return "enabled";
        if ("disabled".equalsIgnoreCase(v) || "off".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return "disabled";
        return null;                                    // auto / 空 / 认不出的值 ⇒ 一律不发
    }

    /** 推理强度：none|low|high|max，缺省 high（与官方默认一致）；认不出的值回落 high。 */
    private String reasoningEffort() {
        String v = conf.reasoningEffort().trim();
        if ("none".equalsIgnoreCase(v)) return "none";
        if ("low".equalsIgnoreCase(v)) return "low";
        if ("max".equalsIgnoreCase(v)) return "max";
        return "high";
    }

    /** 内容风控等级：只有 high 才发 safety_check；默认 low = 不额外审，保持老请求不变。 */
    private boolean safetyHigh() {
        return "high".equalsIgnoreCase(conf.safetyLevel().trim());
    }

    /** stop 序列：中英文逗号都认；最多 16 条、每条 ≤512 字节，超出的丢弃并 warn 一次。 */
    private JsonArray stopSeqs() {
        String raw = conf.stopSeqs();
        if (Str.blank(raw)) return null;
        JsonArray a = new JsonArray();
        int dropped = 0;
        String[] parts = raw.split("[,，]");
        for (int i = 0; i < parts.length; i++) {
            String s = (parts[i] == null) ? "" : parts[i].trim();
            if (s.isEmpty()) continue;
            if (a.size() >= 16) { dropped++; continue; }                     // 官方上限 16 条
            if (s.getBytes(Fs.UTF8).length > 512) { dropped++; continue; }   // 官方上限每条 512 字节
            a.add(s);
        }
        if (dropped > 0 && !raw.equals(warnedStop)) {
            warnedStop = raw;                                                // 同一个配置只报一次，别每个请求刷一行
            logWarn("aiStopSeqs 有 " + dropped + " 条未发送（上限 16 条、每条 ≤512 字节）");
        }
        return a.size() > 0 ? a : null;
    }

    /**
     * {@code aiWebTools=true} 时，在调用方 tools 之后追加官方联网工具：
     * {@code {"type":"deepseek_web_search"}} 与 {@code {"type":"deepseek_read_url"}}。
     * <p>tools 为 null / 空也要追加 —— "不挂本地工具、只用官方联网"是正常用法，
     * 旧写法在空数组上直接返回，等于把联网能力吞掉了。</p>
     * <p>要追加就<b>先复制</b> —— 不能把调用方传进来的 JsonArray 改掉。</p>
     */
    private JsonArray toolsWithWeb(JsonArray tools) {
        if (!conf.webTools()) return tools;
        JsonArray a = new JsonArray();
        boolean hasSearch = false;
        boolean hasFetch = false;
        if (tools != null) {
            for (int i = 0; i < tools.size(); i++) {
                JsonElement e = tools.get(i);
                a.add(e);
                String t = toolType(e);
                if ("deepseek_web_search".equals(t)) hasSearch = true;
                if ("deepseek_read_url".equals(t)) hasFetch = true;
            }
        }
        if (!hasSearch) a.add(webTool("deepseek_web_search"));       // 调用方已经显式带了就不重复加
        if (!hasFetch) a.add(webTool("deepseek_read_url"));
        return a;
    }

    /** 这组 tools 里是否带了官方联网工具（带了才允许 safety_check 配 tool_choice=required）。 */
    private static boolean hasWebTool(JsonArray tools) {
        if (tools == null) return false;
        for (int i = 0; i < tools.size(); i++) {
            String t = toolType(tools.get(i));
            if ("deepseek_web_search".equals(t) || "deepseek_read_url".equals(t)) return true;
        }
        return false;
    }

    private static String toolType(JsonElement e) {
        if (e == null || !e.isJsonObject()) return "";
        JsonElement t = e.getAsJsonObject().get("type");
        if (t == null || !t.isJsonPrimitive()) return "";
        try {
            return t.getAsString();
        } catch (Throwable x) {
            return "";
        }
    }

    private static JsonObject webTool(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    /**
     * 升级新增字段的落点：配置不开就一个都不发（reasoning_effort 例外，见方法内注释）。
     * <p>stream_options <b>不在这里</b>：它依赖 opts 覆盖之后最终生效的 stream，见 {@link #body}。</p>
     */
    private void addExtraFields(JsonObject b) {
        String thinking = thinkingType();
        if (thinking != null) {                                      // auto ⇒ 不发（服务端默认开启）
            JsonObject t = new JsonObject();
            t.addProperty("type", thinking);
            b.add("thinking", t);
        }
        // 只在显式 enabled 时发（auto 保持不发，见下）。
        // 显式 disabled 时官方会忽略它，发出去只会让请求体含义含混（审查 A8 第 28 条）。
        // 2026-11-20 主人定：默认（auto）不发 reasoning_effort，保持"升级前后请求体逐字一致"；
        // 只有显式 aiThinking=enabled 才发。fixToolChoice 内部的 thinkingOn(JsonObject) 仍按
        // "auto=服务端开启" 判断（那是为了 tool_choice 冲突降级，与服务端行为一致）。
        if ("enabled".equals(thinkingType())) b.addProperty("reasoning_effort", reasoningEffort());
        if (conf.jsonMode()) {                                       // 强制 JSON 输出，默认关
            JsonObject f = new JsonObject();
            f.addProperty("type", "json_object");
            b.add("response_format", f);
        }
        JsonArray stop = stopSeqs();
        if (stop != null) b.add("stop", stop);
        if (safetyHigh()) {                                          // 只有 high 才发；low 不额外审
            JsonObject sc = new JsonObject();
            sc.addProperty("level", "high");
            b.add("safety_check", sc);
        }
        if (conf.titleEnabled()) {                                   // 让服务端顺带生成会话标题，默认关
            JsonObject ti = new JsonObject();
            ti.addProperty("enabled", true);
            b.add("title", ti);
        }
    }

    /** 按配置判断本次请求是否开着思考：auto 不发字段，但服务端默认开启；只有显式 disabled 才算关。 */
    private boolean thinkingOn() {
        return !"disabled".equals(thinkingType());
    }

    /**
     * 请求体里<b>最终生效</b>的思考模式（opts 合并之后再判断）：
     * 字段缺失 / null / 写法认不出都按 auto 处理 = 服务端默认开启；只有 {@code type=disabled} 才算关。
     * <p>不能只看配置：调用方可以在 opts 里把 {@code thinking} 改成别的值。</p>
     */
    private static boolean thinkingOn(JsonObject b) {
        JsonElement e = b.get("thinking");
        if (e == null || e.isJsonNull()) return true;                // 没发字段 ⇒ 服务端默认开启
        if (!e.isJsonObject()) return true;                          // 不认识的写法 ⇒ 交给服务端，按开启算
        JsonElement t = e.getAsJsonObject().get("type");
        if (t == null || !t.isJsonPrimitive()) return true;
        try {
            return !"disabled".equalsIgnoreCase(t.getAsString().trim());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 请求体里最终生效的 tools（opts 可以整个覆盖 tools，所以不能拿原来的入参判断）。 */
    private static JsonArray toolsIn(JsonObject b) {
        JsonElement e = b.get("tools");
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    /**
     * tool_choice 的冲突降级（只改本次请求体，不动调用方传进来的 opts）：
     * <ul>
     *   <li><b>思考模式</b>：官方对「思考模式 + tool_choice=required（或指定具体工具）」直接 400；</li>
     *   <li><b>safety_check</b>：{@code aiSafetyLevel=high} 且本次没带官方联网工具时，
     *       单独用 safety_check 只允许 tool_choice=auto/none，required / 具体工具同样 400。</li>
     * </ul>
     * <p>两种冲突都只降级成 auto 并 warn；看的是 opts 合并之后请求体里最终生效的 thinking / tools。</p>
     */
    private void fixToolChoice(JsonObject b) {
        if (b == null) return;
        JsonElement tc = b.get("tool_choice");
        if (tc == null || tc.isJsonNull()) return;
        String s = "";
        if (tc.isJsonPrimitive()) {
            try {
                s = tc.getAsString();
            } catch (Throwable ignored) {
                // 取不到就当"非 auto"处理，降级最安全
            }
        }
        s = (s == null) ? "" : s.trim();
        if ("auto".equalsIgnoreCase(s) || "none".equalsIgnoreCase(s)) return;   // 官方允许的两种写法
        boolean think = thinkingOn(b);
        boolean safety = safetyHigh() && !hasWebTool(toolsIn(b));
        if (!think && !safety) return;                                          // 没有冲突就不碰
        b.addProperty("tool_choice", "auto");
        String why = think && safety ? "思考模式、safety_check 单独使用"
                : (think ? "思考模式" : "safety_check 单独使用");
        logWarn("tool_choice=" + Str.cut(tc.toString(), 80) + " 与" + why
                + "冲突（官方会 400），本次自动降级为 auto");
    }

    /**
     * 模型名校验：官方只有 deepseek-flash / deepseek-v4-pro。
     * <p>不在列表里（旧名、自建反代映射名等）<b>只 warn 一次</b>；不抛异常、不改模型名、不改行为。</p>
     */
    private void verifyModelName(String m) {
        String v = (m == null) ? "" : m.trim();
        if ("deepseek-flash".equalsIgnoreCase(v) || "deepseek-v4-pro".equalsIgnoreCase(v)) return;
        String last = warnedModel;
        if (v.equals(last)) return;                                  // 同一个名字只报一次
        warnedModel = v;
        logWarn("模型名 \"" + v + "\" 不在官方列表（deepseek-flash / deepseek-v4-pro）内，仍按原样发送；"
                + "若报 404 或提示模型已下线，请改配置 model / autoModel");
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

    /**
     * 流式响应是否"完全没有内容"：只认 choices 里的 content / tool_calls / finish_reason。
     * <p>单独一帧 {@code usage}（include_usage 的收尾帧）不算内容，否则空回答会被当成成功；
     * reasoning_content 也不算最终回答（它通常会随后续出 content 或 finish_reason）。</p>
     */
    private static boolean emptyStream(Res r) {
        return Str.blank(r.content)
                && !r.hasTools()
                && Str.blank(r.finishReason);
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
