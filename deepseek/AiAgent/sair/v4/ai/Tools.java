package sair.v4.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * DeepSeek 官方工具类接口的纯 HTTP 封装（基板能力⑦的补齐件）。
 *
 * <p>端点（全部同域，Base URL 由调用方显式给）：</p>
 * <ul>
 *   <li>{@code POST /v1/tools/search} —— 网页搜索（结构化结果：rerank_score / summary / authority）</li>
 *   <li>{@code POST /v1/tools/fetch} —— 网页抓取（返回 {@code {title, content(标 Markdown)}}）</li>
 *   <li>{@code POST /v1/tools/ocr} —— OCR（multipart；响应先持续发换行保活、最后一次性给 JSON）</li>
 *   <li>{@code POST /v1/tools/asr} —— 语音识别（multipart；Ogg Opus 或 16bit PCM WAV）</li>
 *   <li>{@code POST /v1/tools/tts} —— 语音合成（返回音频字节，边合成边返回）</li>
 *   <li>{@code POST /files}、{@code GET /files}、{@code GET/DELETE /files/{id}} —— Files API</li>
 * </ul>
 *
 * <p>{@code baseUrl} 传 API 根（如 {@code https://api.deepseek.com}，<b>不要带 {@code /v1}</b>），
 * 本类自己拼 {@code /v1/tools/...} 与 {@code /files}。</p>
 *
 * <p><b>与 {@link DeepSeek} 的分工</b>：这个类<b>不碰 {@code Conf}</b>，也不带闸门、重试、<em>模型</em>路由，
 * baseUrl / apiKey 一律由调用方显式传 —— 这样技能侧可以先试官方接口，失败再退回本地实现（CDP、必应 HTML 等），
 * 而基板不必为新接口增加配置耦合。</p>
 *
 * <p><b>契约</b>：成功返回 {@link JsonObject} / 音频字节；<b>任何失败都抛 {@link RuntimeException}</b>，
 * 消息里带 HTTP 状态码与响应体前 200 字（不静默）。TTS 的“音频边合成边返回”是特例：
 * 响应声明了长度却没收全、或 WAV 缺头，一律当失败抛异常，绝不把半截音频当成功交出去。</p>
 *
 * <p><b>总时限</b>：每次请求都带一个<b>墙钟总期限</b>（search 30 秒 / fetch 60 秒 / ocr 200 秒 /
 * asr 120 秒 / tts 120 秒 / files 300 秒，见下面各 {@code *_TOTAL_MS}）。到点由看门狗主动
 * {@code disconnect} 并抛“超过总时限”的异常 —— 光靠读超时挡不住服务端细水长流地吐数据。</p>
 *
 * <p><b>排障</b>：{@code -Dsair.debug.tools=true}（或同名环境变量）打开 {@link #verbose}，
 * 也可以运行时直接改这个静态字段；装了 {@link #out} 就复用基板的输出通道。</p>
 */
public final class Tools {

    private Tools() {}

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_MS = 15000;
    /** Search 读超时（毫秒）。 */
    public static final int SEARCH_MS = 12000;
    /** Fetch 读超时（毫秒）。 */
    public static final int FETCH_MS = 30000;
    /**
     * OCR 读超时（毫秒）。官方口径：上传 120 秒内完成、从开始上传算起总时长 180 秒；
     * 这里给 <b>200 秒</b> 留余量 —— 识别期间服务端持续发换行保活，按“每次读”计时，不会提前掐断。
     */
    public static final int OCR_MS = 200000;
    /** ASR 读超时（毫秒）。 */
    public static final int ASR_MS = 120000;
    /** TTS 读超时（毫秒）。 */
    public static final int TTS_MS = 120000;
    /** Files API 读超时（毫秒）：上传文档官方给 120 秒、其他文件 10 分钟，这里统一放宽到 300 秒。 */
    public static final int FILES_MS = 300000;

    /**
     * 每次请求的<b>墙钟总时限</b>（毫秒）：从发起请求算起，到点由看门狗主动 {@code disconnect} 并抛超时异常。
     *
     * <p>读超时（上面的 {@code *_MS}）只约束“单次 read”，服务端只要在每次读超时之内吐一点数据就永远不超时；
     * 这一组总时限才是真正的上限。OCR 的 200 秒同时容纳官方“上传 120 秒 + 识别 180 秒”的口径。</p>
     */
    public static final int SEARCH_TOTAL_MS = 30000;
    /** Fetch 总时限（毫秒）。 */
    public static final int FETCH_TOTAL_MS = 60000;
    /** OCR 总时限（毫秒）：官方口径 180 秒，留余量到 200 秒。 */
    public static final int OCR_TOTAL_MS = 200000;
    /** ASR 总时限（毫秒）。 */
    public static final int ASR_TOTAL_MS = 120000;
    /** TTS 总时限（毫秒）。 */
    public static final int TTS_TOTAL_MS = 120000;
    /** Files API 总时限（毫秒）。 */
    public static final int FILES_TOTAL_MS = 300000;

    /** 单文件上限（字节）：OCR 与文档上传都是 100 MiB。 */
    public static final int MAX_OCR_BYTES = 100 * 1024 * 1024;
    /** Files API 文档类上限（字节）。 */
    public static final int MAX_UPLOAD_BYTES = 100 * 1024 * 1024;
    /** ASR 音频上限（字节）：官方 8 MiB。 */
    public static final int MAX_ASR_BYTES = 8 * 1024 * 1024;
    /** TTS 计费字符上限（官方：一万个计费字符，汉字算 2）。 */
    public static final int MAX_TTS_CHARS = 10000;

    /** JSON 响应体上限（字节）：OCR 一千页的文本可能不小。 */
    private static final long MAX_JSON_BYTES = 64L * 1024 * 1024;
    /** 音频响应体上限（字节）：TTS 单次最多 30 分钟，24kHz/16bit 单声道约 86 MB。 */
    private static final long MAX_AUDIO_BYTES = 128L * 1024 * 1024;
    /** Content-Length 预分配上限（字节）：更大的按需增长，防止对端谎报一个巨大长度直接吃掉堆。 */
    private static final long PREALLOC_MAX = 16L * 1024 * 1024;

    public static final String UA = "AiAgentV4/1.0";

    private static final String ACCEPT_JSON = "application/json, */*;q=0.8";
    private static final String ACCEPT_AUDIO = "audio/*, application/octet-stream;q=0.9, */*;q=0.8";

    /**
     * 排障总开关：默认取 {@code -Dsair.debug.tools=true} 或同名环境变量；运行时也可直接改。
     *
     * <p><b>注意：基板装配会覆盖它</b> —— {@code Boot} 在“基板工具面”一步按 {@code conf.logVerbose()}
     * 直接赋值，所以系统属性/环境变量只是<b>装配前</b>的初始值。线上要开排障请改配置里的
     * {@code logVerbose}，只设 {@code -Dsair.debug.tools} 会被装配覆盖掉。</p>
     */
    public static volatile boolean verbose = debugFlag("sair.debug.tools");

    /** 可选输出口（装配方注入基板 {@link Out}）；为 null 时调试日志走 stdout、告警走 stderr。 */
    public static volatile Out out;

    // ---------------- Search / Fetch ----------------

    /**
     * 网页搜索（{@code POST /v1/tools/search}）。
     *
     * <p><b>超时 12 秒</b>（{@link #SEARCH_MS}）：网页搜索本该快，慢搜索没价值，
     * 早失败好让调用方及时回退（例如退到必应 HTML 搜索）。<b>失败要由调用方回退</b> ——
     * 本方法只抛异常，不做任何重试或降级。</p>
     *
     * @param query     搜索词（1～128 字符；支持 {@code site:} / {@code -site:} / {@code "精确短语"} 等语法）
     * @param extraJson 可选：额外请求字段（如 {@code {"count":5,"need_content":true,"ori_question":"…"}}）；
     *                  为 null/空白则不传；{@code query} 始终以入参为准
     * @return 响应 JSON；{@code results[]} 每项含 title/url/summary/snippet/date/site_name/authority/rerank_score/content
     * @throws RuntimeException 参数不全、网络失败或 HTTP 非 2xx
     */
    public static JsonObject search(String baseUrl, String apiKey, String query, String extraJson) {
        need("search", "baseUrl", baseUrl);
        need("search", "apiKey", apiKey);
        need("search", "query", query);
        JsonObject b = new JsonObject();
        JsonObject more = extra("search", extraJson);
        if (more != null) {
            for (Map.Entry<String, JsonElement> e : more.entrySet()) {
                if (e.getKey() != null) b.add(e.getKey(), e.getValue());   // 未知字段官方会忽略，不用筛
            }
        }
        b.addProperty("query", Str.trim(query));                           // 入参优先，extra 里同名的盖不掉
        return postJson("search", endpoint(baseUrl, "/v1/tools/search"), apiKey, b, SEARCH_MS, SEARCH_TOTAL_MS);
    }

    /**
     * 网页抓取（{@code POST /v1/tools/fetch}）：正文是结构化 Markdown（标题层级、列表、表格、代码块都保留）。
     *
     * @return 响应 JSON，含 {@code title} 与 {@code content}（Markdown）
     * @throws RuntimeException 参数不全、网络失败或 HTTP 非 2xx
     */
    public static JsonObject fetch(String baseUrl, String apiKey, String url) {
        need("fetch", "baseUrl", baseUrl);
        need("fetch", "apiKey", apiKey);
        need("fetch", "url", url);
        JsonObject b = new JsonObject();
        b.addProperty("url", Str.trim(url));
        return postJson("fetch", endpoint(baseUrl, "/v1/tools/fetch"), apiKey, b, FETCH_MS, FETCH_TOTAL_MS);
    }

    // ---------------- OCR / ASR / TTS ----------------

    /**
     * OCR（{@code POST /v1/tools/ocr}）：multipart，{@code purpose} 必须在 {@code file} 之前（否则 400）。
     *
     * <p>响应不是按行流式的：服务端先<b>持续发换行保活</b>，识别完成后才一次性给一个 JSON 对象；
     * 所以这里整段读到 EOF 再解析（换行属于 JSON 的前导空白），<b>绝不能按行解析</b>。</p>
     *
     * @param purpose {@code text_recognition}（只取文字）或 {@code rich_layout_recognition}（图文混排）
     * @return 响应 JSON：{@code page_count} / {@code pages[]}（index、text、finish_reason）/ {@code usage}
     * @throws RuntimeException 参数不全、文件超限、网络失败、HTTP 非 2xx，
     *                          或识别失败（HTTP 200 但响应体是 {@code error} 对象）
     */
    public static JsonObject ocr(String baseUrl, String apiKey, byte[] fileBytes, String filename, String purpose) {
        need("ocr", "baseUrl", baseUrl);
        need("ocr", "apiKey", apiKey);
        need("ocr", "filename", filename);
        needBytes("ocr", "fileBytes", fileBytes);
        maxBytes("ocr", "fileBytes", fileBytes, MAX_OCR_BYTES);
        String p = purposeOf("ocr", purpose, false);
        Multipart mp = new Multipart();
        mp.field("purpose", p);                                            // ★ 顺序是协议要求，别调换
        mp.file("file", filename, contentTypeOf(filename), fileBytes);
        JsonObject o = post("ocr", "POST", endpoint(baseUrl, "/v1/tools/ocr"), apiKey,
                ACCEPT_JSON, mp, OCR_MS, OCR_TOTAL_MS, MAX_JSON_BYTES);
        // 识别开始后状态码就已经是 200：失败会以 error 对象回来，必须显式判掉，别当成功
        JsonObject err = J.sub(o, "error");
        if (err != null) {
            String msg = J.s(err, "message", "");
            String code = J.s(err, "code", "");
            String s = "[ocr] 识别失败: " + (Str.blank(msg) ? "（无 message）" : msg)
                    + (Str.blank(code) ? "" : " (code=" + code + ")");
            warn(s);
            throw new RuntimeException(s);
        }
        return o;
    }

    /**
     * 语音识别（{@code POST /v1/tools/asr}）：multipart，<b>只吃</b> 16 位 PCM WAV 或 Ogg Opus。
     *
     * <p>QQ 语音是 AMR/Silk，调用方必须先转码。这里上传前先做<b>魔数预检</b>：文件头既不是
     * {@code OggS} 也不是 {@code RIFF....WAVE} 就直接报错（不白传 8 MiB）；魔数对但内部编码不对的
     * 仍由服务端判。</p>
     *
     * @return 响应 JSON：{@code request_id} / {@code text} / {@code duration_ms}
     * @throws RuntimeException 参数不全、音频超过 8 MiB、网络失败或 HTTP 非 2xx
     */
    public static JsonObject asr(String baseUrl, String apiKey, byte[] audioBytes, String filename) {
        need("asr", "baseUrl", baseUrl);
        need("asr", "apiKey", apiKey);
        need("asr", "filename", filename);
        needBytes("asr", "audioBytes", audioBytes);
        maxBytes("asr", "audioBytes", audioBytes, MAX_ASR_BYTES);
        asrMagic("asr", audioBytes);                                       // 上传前先看文件头，别把 8 MiB 传上去才报错
        Multipart mp = new Multipart();
        mp.file("file", filename, contentTypeOf(filename), audioBytes);
        return post("asr", "POST", endpoint(baseUrl, "/v1/tools/asr"), apiKey,
                ACCEPT_JSON, mp, ASR_MS, ASR_TOTAL_MS, MAX_JSON_BYTES);
    }

    /**
     * 语音合成（{@code POST /v1/tools/tts}），返回音频字节。
     *
     * <p><b>音频是边合成边返回的</b>：HTTP 200 不代表成功。响应声明了 Content-Length 却没收全、
     * 或 format=wav（含不传 format 的默认口径）却没有 RIFF/WAVE 头、或 Content-Type 不是
     * {@code audio/*} ⇒ 一律抛异常（官方文档明确要求把提前结束的响应当失败）。</p>
     *
     * @param voiceId {@code stella}（默认，女声）或 {@code tide}（男声）；为空则用官方默认
     * @param format  {@code wav}（默认）或 {@code pcm}（24 kHz / 16bit / 单声道）；为空则用官方默认
     * @return 音频字节（wav 带容器，pcm 是裸数据）
     * @throws RuntimeException 参数不全、文本超过计费字符上限、网络/中途断流、HTTP 非 2xx
     */
    public static byte[] tts(String baseUrl, String apiKey, String text, String voiceId, String format) {
        need("tts", "baseUrl", baseUrl);
        need("tts", "apiKey", apiKey);
        need("tts", "text", text);
        int chars = billingChars(text);
        if (chars > MAX_TTS_CHARS) {
            String s = "[tts] 文本 " + chars + " 个计费字符，超过官方上限 " + MAX_TTS_CHARS + "（汉字算 2）";
            warn(s);
            throw new RuntimeException(s);
        }
        JsonObject b = new JsonObject();
        b.addProperty("text", text);
        String voice = Str.trim(voiceId);
        if (!voice.isEmpty()) b.addProperty("voice_id", voice);
        String fmt = Str.lower(Str.trim(format));
        boolean fmtGiven = !fmt.isEmpty();
        if (fmtGiven) b.addProperty("format", fmt);
        else fmt = "wav";                                                 // 不传 = 官方默认 wav：下面按 wav 校验
        Body body = new BytesBody("application/json; charset=utf-8", J.json(b));
        Raw r = exchange("tts", "POST", endpoint(baseUrl, "/v1/tools/tts"), apiKey, ACCEPT_AUDIO,
                body, TTS_MS, TTS_TOTAL_MS, MAX_AUDIO_BYTES);
        if (r.code < 200 || r.code >= 300) throw failure("tts", r.code, r.data);
        if (r.data.length == 0) {
            String s = "[tts] 返回了空音频（HTTP " + r.code + "）";
            warn(s);
            throw new RuntimeException(s);
        }
        if (r.declared > 0 && r.data.length < r.declared) {                 // 中途断流的硬证据
            String s = "[tts] 音频中途断流：声明 " + r.declared + " 字节，实际只收到 " + r.data.length + " 字节（按失败处理）";
            warn(s);
            throw new RuntimeException(s);
        }
        // 官方口径：音频接口只能回 audio/*；回 text/html 之类的是网关/错误页冒充成功，必须当失败
        String ct = Str.lower(r.type);
        if (!ct.startsWith("audio/")) {
            String s = "[tts] 响应的 Content-Type 不是音频（" + (ct.isEmpty() ? "未声明" : ct) + "），按失败处理";
            warn(s);
            throw new RuntimeException(s);
        }
        boolean wav = "wav".equals(fmt);
        if (wav && !riff(r.data)) {
            String s = "[tts] 返回的不是完整 WAV（缺 RIFF/WAVE 头，共 " + r.data.length + " 字节），按失败处理";
            warn(s);
            throw new RuntimeException(s);
        }
        if (wav && !wavFits(r.data)) {                                     // 分块传输“干净地”提前结束时只剩这一道
            String s = "[tts] 返回的 WAV 不完整（头里声明的长度大于实际收到的 " + r.data.length + " 字节），按失败处理";
            warn(s);
            throw new RuntimeException(s);
        }
        if (verbose) log("tts ← " + r.data.length + " 字节" + (fmtGiven ? " format=" + fmt : ""));
        return r.data;
    }

    // ---------------- Files API ----------------

    /**
     * 上传文件（{@code POST /files}）：multipart，{@code purpose} 必须在 {@code file} 之前。
     *
     * <p>purpose 决定能传什么：{@code user_data}（图片 / 纯文本 / 表格 / 电子书）、
     * {@code text_recognition}、{@code rich_layout_recognition}（后两者是 PDF/Office/图片，走解析）。</p>
     *
     * @return FileObject：{@code id} / {@code object} / {@code bytes} / {@code created_at} / {@code filename} / {@code purpose}
     * @throws RuntimeException 参数不全、文件超限、网络失败或 HTTP 非 2xx
     */
    public static JsonObject fileUpload(String baseUrl, String apiKey, byte[] bytes, String filename, String purpose) {
        need("files.upload", "baseUrl", baseUrl);
        need("files.upload", "apiKey", apiKey);
        need("files.upload", "filename", filename);
        needBytes("files.upload", "bytes", bytes);
        maxBytes("files.upload", "bytes", bytes, MAX_UPLOAD_BYTES);
        String p = purposeOf("files.upload", purpose, true);
        Multipart mp = new Multipart();
        mp.field("purpose", p);                                            // ★ 顺序是协议要求，别调换
        mp.file("file", filename, contentTypeOf(filename), bytes);
        return post("files.upload", "POST", endpoint(baseUrl, "/files"), apiKey,
                ACCEPT_JSON, mp, FILES_MS, FILES_TOTAL_MS, MAX_JSON_BYTES);
    }

    /**
     * 列出文件（{@code GET /files}，游标分页）。
     *
     * @param after  上一页的 {@code last_id}；空白则不传（从头开始）
     * @param limit  1～1000；{@code <=0} 不传（用官方默认），超过 1000 按 1000 处理并记日志
     * @param purpose 可选过滤：user_data / text_recognition / rich_layout_recognition；空白则不传
     * @return 响应 JSON：{@code object="list"} / {@code data[]} / {@code first_id} / {@code last_id} / {@code has_more}
     * @throws RuntimeException 参数不全、purpose 非法、网络失败或 HTTP 非 2xx
     */
    public static JsonObject fileList(String baseUrl, String apiKey, String after, int limit, String purpose) {
        need("files.list", "baseUrl", baseUrl);
        need("files.list", "apiKey", apiKey);
        StringBuilder q = new StringBuilder();
        String a = Str.trim(after);
        if (!a.isEmpty()) q.append("after=").append(enc(a));
        if (limit > 0) {
            int n = limit > 1000 ? 1000 : limit;
            if (n != limit) log("files.list ← limit " + limit + " 超出 1～1000，按 " + n + " 处理");
            if (q.length() > 0) q.append('&');
            q.append("limit=").append(n);
        }
        String p = Str.trim(purpose);
        if (!p.isEmpty()) {
            p = purposeOf("files.list", p, true);
            if (q.length() > 0) q.append('&');
            q.append("purpose=").append(enc(p));
        }
        String path = "/files" + (q.length() > 0 ? "?" + q.toString() : "");
        return post("files.list", "GET", endpoint(baseUrl, path), apiKey,
                ACCEPT_JSON, null, FILES_MS, FILES_TOTAL_MS, MAX_JSON_BYTES);
    }

    /**
     * 查询单个文件（{@code GET /files/{file_id}}）。
     *
     * @return FileObject
     * @throws RuntimeException 参数不全、网络失败或 HTTP 非 2xx（404 = 文件不存在或已过期）
     */
    public static JsonObject fileGet(String baseUrl, String apiKey, String fileId) {
        need("files.get", "baseUrl", baseUrl);
        need("files.get", "apiKey", apiKey);
        need("files.get", "fileId", fileId);
        return post("files.get", "GET", endpoint(baseUrl, "/files/" + encPath(Str.trim(fileId))), apiKey,
                ACCEPT_JSON, null, FILES_MS, FILES_TOTAL_MS, MAX_JSON_BYTES);
    }

    /**
     * 删除文件（{@code DELETE /files/{file_id}}）。
     *
     * <p>官方删除成功通常回 <b>204 No Content</b>（也可能 200 + 空体）—— 这两种都是删掉了，
     * 绝不能因为“不是 JSON”当失败。204 / 空体统一返回 {@code {deleted:true}}。</p>
     *
     * @return 响应 JSON：{@code id} / {@code object="file"} / {@code deleted}；204 / 空体 → {@code {deleted:true}}
     * @throws RuntimeException 参数不全、网络失败或 HTTP 非 2xx
     */
    public static JsonObject fileDelete(String baseUrl, String apiKey, String fileId) {
        need("files.delete", "baseUrl", baseUrl);
        need("files.delete", "apiKey", apiKey);
        need("files.delete", "fileId", fileId);
        String id = Str.trim(fileId);
        Raw r = exchange("files.delete", "DELETE", endpoint(baseUrl, "/files/" + encPath(id)), apiKey,
                ACCEPT_JSON, null, FILES_MS, FILES_TOTAL_MS, MAX_JSON_BYTES);
        if (r.code == 204 || (r.code >= 200 && r.code < 300 && r.data.length == 0)) {
            if (verbose) log("files.delete ← HTTP " + r.code + " 已删除 " + id);
            JsonObject o = new JsonObject();
            o.addProperty("deleted", true);
            return o;
        }
        if (r.code < 200 || r.code >= 300) throw failure("files.delete", r.code, r.data);
        String raw = text(r.data);
        JsonObject o = J.obj(raw);
        if (o == null) {
            String s = "[files.delete] 响应不是 JSON: " + Str.cut(Str.oneLine(raw), 200);
            warn(s);
            throw new RuntimeException(s);
        }
        return o;
    }

    // ---------------- 内部：请求 ----------------

    /** JSON 请求（响应必须是 JSON 对象）。{@code totalMs} = 这次请求的墙钟总时限。 */
    private static JsonObject postJson(String tag, String url, String apiKey, JsonObject body,
                                       int readMs, int totalMs) {
        Body b = new BytesBody("application/json; charset=utf-8", J.json(body));
        return post(tag, "POST", url, apiKey, ACCEPT_JSON, b, readMs, totalMs, MAX_JSON_BYTES);
    }

    /** 发一次请求并把响应按 JSON 对象解析；非 2xx 与不可解析都抛。 */
    private static JsonObject post(String tag, String method, String url, String apiKey, String accept,
                                   Body body, int readMs, int totalMs, long maxBytes) {
        Raw r = exchange(tag, method, url, apiKey, accept, body, readMs, totalMs, maxBytes);
        if (r.code < 200 || r.code >= 300) throw failure(tag, r.code, r.data);
        String raw = text(r.data);
        JsonObject o = J.obj(raw);                                         // J.obj 会先 trim：OCR 的前导换行正好被吃掉
        if (o == null) {
            String s = "[" + tag + "] 响应不是 JSON: " + Str.cut(Str.oneLine(raw), 200);
            warn(s);
            throw new RuntimeException(s);
        }
        if (verbose) log(tag + " ← " + Str.cut(Str.oneLine(raw), 200));
        return o;
    }

    /**
     * 底层一次交换：写请求体（可空）→ 读完整响应体。<b>只管传输，不管状态码</b>，
     * 由 {@link #post} / {@link #tts} 各自判定（TTS 要把“没收全”单独当失败）。
     *
     * <p><b>两层超时</b>：{@code readMs} 是单次读超时；{@code totalMs} 是这次请求的墙钟总时限 ——
     * 连接超时、每次读超时都不许超过“剩余总时限”，并且有一只守护线程到点主动 {@code disconnect}。</p>
     */
    private static Raw exchange(String tag, String method, String url, String apiKey, String accept,
                                Body body, int readMs, int totalMs, long maxBytes) {
        HttpURLConnection c = null;
        TimeoutGuard guard = null;
        long t0 = System.currentTimeMillis();
        long deadline = t0 + (totalMs > 0 ? totalMs : readMs);
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(left(deadline, CONNECT_MS));
            c.setReadTimeout(left(deadline, readMs));
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(true);
            c.setDoInput(true);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", accept == null ? "*/*" : accept);
            String k = Str.trim(apiKey);
            if (!k.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + k);
            guard = TimeoutGuard.arm(c, deadline, tag);
            if (body != null) {
                c.setDoOutput(true);
                // 定长流式写：OCR/上传的文件最大 100 MiB，不能先拼出一份完整 multipart 到内存
                c.setFixedLengthStreamingMode(body.length());
                c.setRequestProperty("Content-Type", body.contentType());
                if (verbose) log(tag + " → " + method + " " + url + " body=" + body.length() + "B");
                OutputStream os = c.getOutputStream();
                try {
                    body.writeTo(os);
                    os.flush();
                } finally {
                    close(os);
                }
            } else if (verbose) {
                log(tag + " → " + method + " " + url);
            }
            int code = c.getResponseCode();
            String type = c.getContentType();
            long declared = c.getContentLengthLong();
            InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            byte[] data;
            try {
                data = readAll(in, declared, maxBytes, deadline, tag);
            } finally {
                close(in);                                                 // ★ 响应流必须关：不关连接会被一直占着
            }
            if (verbose) {
                log(tag + " ← HTTP " + code + " " + data.length + "B "
                        + (System.currentTimeMillis() - t0) + "ms");
            }
            return new Raw(code, type, declared, data);
        } catch (RuntimeException e) {
            if ((guard != null && guard.fired()) || System.currentTimeMillis() >= deadline) {
                throw timeout(tag, method, url, totalMs, e);               // 看门狗断出来的未检查异常也按总时限报
            }
            throw e;
        } catch (Exception e) {
            // 到点是看门狗主动断的：必须先认出来，别当成普通网络故障（TTS 的中途断流仍在下面这一支）
            if ((guard != null && guard.fired()) || System.currentTimeMillis() >= deadline) {
                throw timeout(tag, method, url, totalMs, e);
            }
            String msg = "[" + tag + "] " + method + " " + url + " 失败: " + e.getClass().getSimpleName()
                    + (Str.blank(e.getMessage()) ? "" : ": " + e.getMessage());
            warn(msg);
            throw new RuntimeException(msg, e);
        } finally {
            if (guard != null) guard.cancel();
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    // ---------------- 内部：请求体 ----------------

    /** 请求体：长度已知 + 可写一遍（配合定长流式上传，避免大文件再复制一份）。 */
    private interface Body {
        String contentType();
        long length();
        void writeTo(OutputStream os) throws IOException;
    }

    /** 一段 JSON / 文本请求体。 */
    private static final class BytesBody implements Body {
        private final String type;
        private final byte[] data;

        BytesBody(String type, String text) {
            this.type = type;
            this.data = bytes(text);
        }

        @Override
        public String contentType() { return type; }

        @Override
        public long length() { return data.length; }

        @Override
        public void writeTo(OutputStream os) throws IOException { os.write(data); }
    }

    /**
     * 自己拼的 multipart/form-data 请求体（项目里没有现成的 multipart 实现）。
     *
     * <p>边界串带纳秒，不会撞上正文；文件内容只存引用不复制，写出时按定长流式发。</p>
     */
    private static final class Multipart implements Body {
        private final String boundary = "----SairV4" + Long.toHexString(System.currentTimeMillis())
                + Long.toHexString(System.nanoTime());
        private final List<byte[]> chunks = new ArrayList<byte[]>();
        private long len = 0L;

        /** 普通文本字段（如 purpose）。 */
        Multipart field(String name, String value) {
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(esc(name)).append("\"\r\n");
            sb.append("\r\n");
            sb.append(value == null ? "" : value).append("\r\n");
            add(bytes(sb.toString()));
            return this;
        }

        /** 文件字段：filename 去掉路径与裸换行，Content-Type 按扩展名给。 */
        Multipart file(String name, String filename, String contentType, byte[] data) {
            StringBuilder sb = new StringBuilder();
            sb.append("--").append(boundary).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"").append(esc(name))
                    .append("\"; filename=\"").append(esc(safeFilename(filename))).append("\"\r\n");
            sb.append("Content-Type: ").append(contentType == null ? "application/octet-stream" : contentType)
                    .append("\r\n");
            sb.append("\r\n");
            add(bytes(sb.toString()));
            add(data);                                                     // 直接引用，不复制
            add(bytes("\r\n"));
            return this;
        }

        @Override
        public String contentType() { return "multipart/form-data; boundary=" + boundary; }

        @Override
        public long length() { return len + tail().length; }

        @Override
        public void writeTo(OutputStream os) throws IOException {
            for (int i = 0; i < chunks.size(); i++) os.write(chunks.get(i));
            os.write(tail());
        }

        private byte[] tail() { return bytes("--" + boundary + "--\r\n"); }

        private void add(byte[] b) {
            if (b != null && b.length > 0) {
                chunks.add(b);
                len += b.length;
            }
        }

        private static String esc(String s) {
            return Str.nz(s).replace("\r", "").replace("\n", "").replace("\"", "'");
        }
    }

    /** 一次交换的结果。 */
    private static final class Raw {
        final int code;
        final String type;
        /** Content-Length；-1 = 未知（分块传输）。 */
        final long declared;
        final byte[] data;

        Raw(int code, String type, long declared, byte[] data) {
            this.code = code;
            this.type = type == null ? "" : type;
            this.declared = declared;
            this.data = data == null ? new byte[0] : data;
        }
    }

    /**
     * 总时限看门狗：到点主动 {@link HttpURLConnection#disconnect()}。
     *
     * <p>为什么非要它：{@code setReadTimeout} 只约束“单次 read”，服务端只要在每次读超时之内吐一点数据
     * 就能永远不超时；而且一次 read 一旦阻塞，主线程自己没机会看表。看门狗是唯一能在墙钟到点时
     * <b>主动</b>断开连接、把主线程从 read 里踢出来的东西。守护线程 + 收工即取消，不拖住 JVM。</p>
     */
    private static final class TimeoutGuard extends Thread {
        private final HttpURLConnection conn;
        private final long deadline;
        private volatile boolean fired;
        private volatile boolean done;

        private TimeoutGuard(HttpURLConnection conn, long deadline, String tag) {
            super("sair-tools-timeout-" + tag);
            setDaemon(true);
            this.conn = conn;
            this.deadline = deadline;
        }

        /** 起一只看门狗；线程起不来（安全管理器等）返回 null，退化成只靠读循环里的截止检查。 */
        static TimeoutGuard arm(HttpURLConnection conn, long deadline, String tag) {
            TimeoutGuard g = new TimeoutGuard(conn, deadline, tag);
            try {
                g.start();
                return g;
            } catch (Throwable e) {
                return null;
            }
        }

        @Override
        public void run() {
            try {
                long wait;
                while (!done && (wait = deadline - System.currentTimeMillis()) > 0) {
                    Thread.sleep(Math.min(wait, 1000L));
                }
                if (done) return;
                fired = true;                                              // 先置标记再断：主线程据此区分“总时限到”与普通 IO 故障
                try { conn.disconnect(); } catch (Throwable ignored) {}
            } catch (InterruptedException ignored) {
                // cancel() 叫醒的：正常收工
            }
        }

        boolean fired() { return fired; }

        void cancel() {
            done = true;
            try { interrupt(); } catch (Throwable ignored) {}
        }
    }

    // ---------------- 内部：小工具 ----------------

    private static void need(String tag, String name, String v) {
        if (Str.blank(v)) throw new RuntimeException("[" + tag + "] " + name + " 不能为空");
    }

    private static void needBytes(String tag, String name, byte[] b) {
        if (b == null || b.length == 0) throw new RuntimeException("[" + tag + "] " + name + " 不能为空");
    }

    private static void maxBytes(String tag, String name, byte[] b, int max) {
        if (b != null && b.length > max) {
            String s = "[" + tag + "] " + name + " " + mib(b.length) + " 超过上限 " + mib(max);
            warn(s);
            throw new RuntimeException(s);
        }
    }

    /** 解析 extraJson：空白返回 null；不是 JSON 对象就明确报错（不静默忽略）。 */
    private static JsonObject extra(String tag, String extraJson) {
        if (Str.blank(extraJson)) return null;
        JsonObject o = J.obj(extraJson);
        if (o == null) {
            throw new RuntimeException("[" + tag + "] extraJson 不是 JSON 对象: "
                    + Str.cut(Str.oneLine(extraJson), 200));
        }
        return o;
    }

    /** purpose 白名单；{@code allowUserData=false} 时（OCR）连 user_data 也拒，免得白跑一趟。 */
    private static String purposeOf(String tag, String purpose, boolean allowUserData) {
        String p = Str.lower(Str.trim(purpose));
        if (p.isEmpty()) {
            throw new RuntimeException("[" + tag + "] purpose 不能为空"
                    + "（user_data / text_recognition / rich_layout_recognition）");
        }
        if (!"user_data".equals(p) && !"text_recognition".equals(p) && !"rich_layout_recognition".equals(p)) {
            throw new RuntimeException("[" + tag + "] purpose 不支持: " + purpose
                    + "（只能是 user_data / text_recognition / rich_layout_recognition）");
        }
        if (!allowUserData && "user_data".equals(p)) {
            throw new RuntimeException("[" + tag + "] OCR 的 purpose 只能是 text_recognition 或 rich_layout_recognition");
        }
        return p;
    }

    /** 文件名只留本体：去路径、引号、换行（multipart 头里不能出现裸换行）。 */
    private static String safeFilename(String name) {
        String t = Str.nz(name);
        int i = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (i >= 0) t = t.substring(i + 1);
        t = t.replace("\r", "").replace("\n", "").replace("\"", "'").trim();
        return t.isEmpty() ? "file.bin" : t;
    }

    /** 按扩展名给 Content-Type（服务端按文件内容判格式，这里只是别发错得太离谱）。 */
    private static String contentTypeOf(String filename) {
        String f = Str.lower(safeFilename(filename));
        if (f.endsWith(".pdf")) return "application/pdf";
        if (f.endsWith(".doc")) return "application/msword";
        if (f.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (f.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (f.endsWith(".pptx"))
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (f.endsWith(".xls")) return "application/vnd.ms-excel";
        if (f.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".jpg") || f.endsWith(".jpeg")) return "image/jpeg";
        if (f.endsWith(".webp")) return "image/webp";
        if (f.endsWith(".gif")) return "image/gif";
        if (f.endsWith(".bmp")) return "image/bmp";
        if (f.endsWith(".tif") || f.endsWith(".tiff")) return "image/tiff";
        if (f.endsWith(".wav")) return "audio/wav";
        if (f.endsWith(".ogg") || f.endsWith(".opus")) return "audio/ogg";
        if (f.endsWith(".mp3")) return "audio/mpeg";
        if (f.endsWith(".m4a")) return "audio/mp4";
        if (f.endsWith(".epub")) return "application/epub+zip";
        if (f.endsWith(".txt") || f.endsWith(".md") || f.endsWith(".csv") || f.endsWith(".json")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }

    /** 计费字符：汉字/全角算 2，其余算 1（官方口径，用于上传前拦明显超限的文本）。 */
    private static int billingChars(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            n += (cp >= 0x2E80) ? 2 : 1;
            i += Character.charCount(cp);
        }
        return n;
    }

    /**
     * ASR 上传前的魔数预检：官方只吃 Ogg Opus（{@code OggS}）与 16bit PCM WAV（{@code RIFF....WAVE}）。
     * <p>传 8 MiB 上去才被拒既费流量也费时间，这里先按文件头拦掉明显不对的；魔数对但内部编码不对的
     * 仍由服务端判。</p>
     */
    private static void asrMagic(String tag, byte[] b) {
        boolean ogg = b != null && b.length >= 4
                && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S';
        if (ogg || riff(b)) return;
        String s = "[" + tag + "] 音频不是 Ogg Opus 或 16bit PCM WAV"
                + "（文件头既不是 OggS 也不是 RIFF/WAVE），请先转码再上传";
        warn(s);
        throw new RuntimeException(s);
    }

    /** 是不是带头的 WAV（RIFF....WAVE）。 */
    private static boolean riff(byte[] b) {
        return b != null && b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E';
    }

    /**
     * WAV 头里声明的长度（小端 RIFF size 字段 + 8）有没有超过实收字节数。
     * <p>分块传输如果“干净地”提前结束，读流不会报 IOException，只能靠这个字段兜底；
     * size 为 0 / 0xFFFFFFFF 是流式占位，无法判断时按“通过”处理。</p>
     */
    private static boolean wavFits(byte[] b) {
        if (b == null || b.length < 12) return false;
        long size = ((b[4] & 0xFFL)) | ((b[5] & 0xFFL) << 8) | ((b[6] & 0xFFL) << 16) | ((b[7] & 0xFFL) << 24);
        if (size == 0L || size == 0xFFFFFFFFL) return true;                // 流式占位：不判
        return size + 8L <= b.length;
    }

    /** baseUrl + path（去掉 baseUrl 末尾的斜杠；path 自己带前导 /）。 */
    private static String endpoint(String baseUrl, String path) {
        String b = Str.trim(baseUrl);
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b + path;
    }

    /** 查询串里的值（URLEncoder 对 query 是对的：空格编成 + 合法）。 */
    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            warn("URL 编码失败（原样拼）: " + e.getClass().getSimpleName());
            return Str.nz(s);
        }
    }

    /** 路径段编码：保留 RFC 3986 unreserved，其余按 UTF-8 百分号编码（不用 URLEncoder：它把空格编成 +）。 */
    private static String encPath(String s) {
        String v = Str.nz(s);
        try {
            byte[] b = v.getBytes(Fs.UTF8);
            StringBuilder sb = new StringBuilder(b.length + 8);
            for (int i = 0; i < b.length; i++) {
                int x = b[i] & 0xFF;
                boolean ok = (x >= 'a' && x <= 'z') || (x >= 'A' && x <= 'Z')
                        || (x >= '0' && x <= '9')
                        || x == '-' || x == '_' || x == '.' || x == '~';
                if (ok) sb.append((char) x);
                else sb.append('%').append(HEX[x >> 4]).append(HEX[x & 0xF]);
            }
            return sb.toString();
        } catch (Exception e) {
            warn("路径编码失败（原样拼）: " + e.getClass().getSimpleName());
            return v;
        }
    }

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * 读完整响应体（按上限保护内存）。OCR 的“前导换行 + 一个 JSON”就是在这里一次读完的。
     *
     * <p><b>Content-Length 已知时预分配</b>：收满就原样返回，<b>不再复制一次</b>（TTS/大响应的主路径）；
     * 实际短了（对端提前结束）才截到实收长度。预分配封顶 {@link #PREALLOC_MAX}，免得对端谎报一个
     * 巨大的长度直接吃掉堆；更大的走按需增长。</p>
     *
     * <p>本方法<b>不关</b>输入流 —— 关闭由调用方在 finally 里做（见 {@link #exchange}），
     * 这样“读抛异常”与“正常读完”两条路都一定关得掉。每次读之前都看一眼总时限，到点立刻停。</p>
     */
    private static byte[] readAll(InputStream in, long declared, long maxBytes, long deadline, String tag)
            throws IOException {
        if (in == null) return new byte[0];
        if (declared > maxBytes) {
            throw new IOException("响应声明 " + mib(declared) + "，超过 " + mib(maxBytes) + " 上限，已中止");
        }
        if (declared > 0 && declared <= PREALLOC_MAX) {
            byte[] data = new byte[(int) declared];
            int off = 0;
            while (off < data.length) {
                checkDeadline(deadline, tag);
                int n = in.read(data, off, data.length - off);
                if (n == -1) break;
                off += n;
            }
            if (off == data.length) return data;                           // 收满：零复制
            return off == 0 ? new byte[0] : java.util.Arrays.copyOf(data, off);
        }
        int cap = declared > 0 ? (int) Math.min(declared, PREALLOC_MAX) : 8192;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(8192, cap));
        byte[] buf = new byte[16384];
        long total = 0L;
        int n;
        while (true) {
            checkDeadline(deadline, tag);
            n = in.read(buf);
            if (n == -1) break;
            if (n == 0) continue;
            total += n;
            if (total > maxBytes) {
                throw new IOException("响应超过 " + mib(total) + " 上限（" + mib(maxBytes) + "），已中止");
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 单次 read 前看表：到总时限就停（服务端细水长流时靠它，不等看门狗）。 */
    private static void checkDeadline(long deadline, String tag) throws IOException {
        if (System.currentTimeMillis() >= deadline) {
            throw new IOException("[" + tag + "] 请求总时限已到，主动中止读取");
        }
    }

    /** 还剩下的总时限（夹到 1 毫秒以上，并封顶在单次超时上）；{@code 0} 在 HttpURLConnection 里是“无限等”，绝不能传。 */
    private static int left(long deadline, int perReadMs) {
        long remain = deadline - System.currentTimeMillis();
        long ms = remain > 0 ? Math.min(remain, perReadMs) : 1L;
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, ms));
    }

    private static byte[] bytes(String s) {
        return s == null ? new byte[0] : s.getBytes(Fs.UTF8);
    }

    private static String text(byte[] b) {
        return b == null ? "" : new String(b, Fs.UTF8);
    }

    private static String mib(long bytes) {
        if (bytes >= 1024L * 1024L) return (bytes / (1024L * 1024L)) + " MiB";
        if (bytes >= 1024L) return (bytes / 1024L) + " KiB";
        return bytes + " B";
    }

    private static void close(Closeable c) {
        if (c != null) try { c.close(); } catch (Throwable ignored) {}
    }

    /** 状态码附一句人话（只加提示，不改变失败语义）。 */
    private static String hint(int code) {
        if (code == 400) return "（请求参数/文件不合法）";
        if (code == 401) return "（key 无效或未授权）";
        if (code == 402) return "（余额不足）";
        if (code == 408) return "（上传/识别超时）";
        if (code == 413) return "（文件过大）";
        if (code == 429) return "（限流，稍等再试）";
        if (code == 503 || code == 504) return "（服务繁忙，稍等再试）";
        if (code >= 500) return "（服务端错误）";
        return "";
    }

    /** 失败：消息里必须带状态码 + 响应体前 200 字（契约要求，不许静默）。 */
    private static RuntimeException failure(String tag, int code, byte[] data) {
        String body = Str.cut(Str.oneLine(text(data)), 200);
        String s = "[" + tag + "] HTTP " + code + hint(code) + (Str.blank(body) ? "" : " " + body);
        warn(s);
        return new RuntimeException(s);
    }

    /** 墙钟总时限到点的失败（消息里点明“总时限”，别和单次读超时混在一起）。 */
    private static RuntimeException timeout(String tag, String method, String url, int totalMs, Exception cause) {
        String s = "[" + tag + "] " + method + " " + url + " 超过总时限 " + seconds(totalMs)
                + "，已主动断开（不是单次读超时）";
        warn(s);
        return new RuntimeException(s, cause);
    }

    /** 毫秒 → 人话（整秒报“N 秒”，否则报毫秒）。 */
    private static String seconds(int ms) {
        return (ms > 0 && ms % 1000 == 0) ? (ms / 1000) + " 秒" : ms + " 毫秒";
    }

    /** 排障开关的默认值：系统属性优先，其次同名环境变量。 */
    private static boolean debugFlag(String key) {
        try {
            String v = System.getProperty(key);
            if (v == null) v = System.getenv(key);
            if (v == null) return false;
            String s = v.trim().toLowerCase();
            return "1".equals(s) || "true".equals(s) || "on".equals(s) || "yes".equals(s);
        } catch (Throwable ignored) {
            return false;                       // 属性/环境读不到就当关，不影响功能
        }
    }

    private static void log(String s) {
        if (!verbose || s == null) return;
        Out o = out;
        try {
            if (o != null) o.dim("[tools] " + s);
            else System.out.println("[tools] " + s);
        } catch (Throwable ignored) {
            // 日志出口自己坏了不能再拖垮请求；这里也没有可信的出口可以再记一笔
        }
    }

    private static void warn(String s) {
        if (s == null) return;
        Out o = out;
        try {
            if (o != null) o.warn("[tools] " + s);
            else System.err.println("[tools] " + s);
        } catch (Throwable ignored) {
            // 同上：告警出口失败只能咽掉，绝不掩盖原始异常
        }
    }
}
