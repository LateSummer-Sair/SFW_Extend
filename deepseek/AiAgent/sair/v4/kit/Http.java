package sair.v4.kit;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * HTTP 客户端（基板自用：DeepSeek、NapCat 中转）+ 技能抓取外网 URL 的<b>带闸门</b>入口。
 *
 * <h3>两套入口，别混用</h3>
 * <ul>
 *   <li>{@link #get} / {@link #postJson} / {@link #request} —— <b>低层传输</b>，不做 SSRF 判定、
 *       跟随重定向。只给"地址来自本机配置"的调用方用（模型 API 端点、NapCat…）：
 *       这些端点本来就可能是 {@code http://127.0.0.1:...}，套闸门等于把自家腿打断。</li>
 *   <li>{@link #getGuarded} / {@link #download} —— <b>技能唯一该用的入口</b>：地址来自模型或用户，
 *       每一跳都过 {@link NetGuard}，且关掉 JDK 的自动跟随自己走跳转（否则 302 到
 *       {@code 169.254.169.254} 会在判定之前就被跟随，闸门形同虚设）。</li>
 * </ul>
 *
 * <p>被闸门拒绝时，{@link Res#blocked} / {@link Dl#blocked} 给出<b>机器码</b>
 * （{@code internal} / {@code scheme} / {@code bad_url} / {@code redirect_no_location} /
 * {@code too_many_redirects}），拒绝话术由各技能的 {@code prompt.md} 负责 —— 基板不产文案。</p>
 *
 * <p>响应体解码统一走 {@link Text}（BOM → Content-Type charset → meta charset → 试探解码 → UTF-8 兜底），
 * 并按 {@code Content-Encoding} 解 gzip / deflate。</p>
 */
public final class Http {

    private Http() {}

    /** 默认响应体上限（8MB）。 */
    private static final long DEF_MAX_BYTES = 8L * 1024 * 1024;

    /** 带闸门入口允许的最大跳数（每一跳都重新判定）。 */
    public static final int MAX_HOPS = 5;

    /** 响应（错误串只给结构性信息；面向模型的文案由技能自己出）。 */
    public static final class Res {
        public final int code;
        public final String body;
        public final String error;
        /** 非空 = 被 {@link NetGuard} 拦下，值是机器码（{@code internal} / {@code scheme} / …）。 */
        public final String blocked;
        /** 被拦时命中的主机（给技能的 prompt.md 文案填空）。 */
        public final String blockedHost;
        /** 被拦时的细分类别（{@code loopback} / {@code private} / {@code metadata} / …）。 */
        public final String blockedReason;
        /** 非空 = 跳转链本身没走通（{@code redirect_no_location} / {@code too_many_redirects}）。 */
        public final String redirect;
        /** 实际解码用的字符集（{@code UTF-8} / {@code GBK} / …）。 */
        public final String charset;
        /** 逐跳之后的最终 URL。 */
        public final String finalUrl;

        Res(int code, String body, String error) {
            this(code, body, error, "", "", "", "", "", "");
        }

        Res(int code, String body, String error, String blocked, String blockedHost,
            String blockedReason, String redirect, String charset, String finalUrl) {
            this.code = code;
            this.body = body == null ? "" : body;
            this.error = error == null ? "" : error;
            this.blocked = blocked == null ? "" : blocked;
            this.blockedHost = blockedHost == null ? "" : blockedHost;
            this.blockedReason = blockedReason == null ? "" : blockedReason;
            this.redirect = redirect == null ? "" : redirect;
            this.charset = charset == null ? "" : charset;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
        }

        public boolean ok() { return code >= 200 && code < 300; }

        /** 是否被安全闸门拒绝（不是网络错）。 */
        public boolean denied() { return !blocked.isEmpty(); }

        /** 是否跳转链没走通（不是网络错、也不是被拒）。 */
        public boolean redirectBroken() { return !redirect.isEmpty(); }
    }

    /** 下载结果。 */
    public static final class Dl {
        public final String error;
        public final String blocked;
        public final String blockedHost;
        public final String blockedReason;
        /** 非空 = 跳转链本身没走通（{@code redirect_no_location} / {@code too_many_redirects}）。 */
        public final String redirect;
        public final String finalUrl;
        public final int code;
        public final long size;

        Dl(String error, String blocked, String blockedHost, String blockedReason,
           String redirect, String finalUrl, int code, long size) {
            this.error = error == null ? "" : error;
            this.blocked = blocked == null ? "" : blocked;
            this.blockedHost = blockedHost == null ? "" : blockedHost;
            this.blockedReason = blockedReason == null ? "" : blockedReason;
            this.redirect = redirect == null ? "" : redirect;
            this.finalUrl = finalUrl == null ? "" : finalUrl;
            this.code = code;
            this.size = size;
        }

        public boolean ok() { return error.isEmpty() && blocked.isEmpty() && redirect.isEmpty(); }

        public boolean denied() { return !blocked.isEmpty(); }

        public boolean redirectBroken() { return !redirect.isEmpty(); }
    }

    public static Map<String, String> headers() { return new LinkedHashMap<String, String>(); }

    // ================================================================ 低层入口

    public static Res get(String url, int timeoutMs) { return get(url, null, timeoutMs); }

    public static Res get(String url, Map<String, String> hdrs, int timeoutMs) {
        return request("GET", url, null, hdrs, timeoutMs, null);
    }

    public static Res postJson(String url, String json, Map<String, String> hdrs, int timeoutMs) {
        Map<String, String> h = hdrs == null ? headers() : new LinkedHashMap<String, String>(hdrs);
        if (!h.containsKey("Content-Type")) h.put("Content-Type", "application/json; charset=utf-8");
        return request("POST", url, json, h, timeoutMs, null);
    }

    /**
     * 带请求体的任意方法（body 为字符串，UTF-8）。<b>低层</b>：不判内网、跟随重定向。
     * 抓取"模型/用户给的 URL"请用 {@link #getGuarded}。
     */
    public static Res request(String method, String url, String body,
                              Map<String, String> hdrs, int timeoutMs, long[] maxBytesOut) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs > 0 ? timeoutMs : 15000);
            c.setReadTimeout(timeoutMs > 0 ? timeoutMs : 60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "AiAgentV4/1.0");
            if (hdrs != null) for (Map.Entry<String, String> e : hdrs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
            }
            if (body != null) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body.getBytes(Fs.UTF8));
                    os.flush();
                } finally {
                    os.close();
                }
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 400) ? c.getErrorStream() : c.getInputStream();
            String text = in == null ? "" : decodeFull(readAll(c, in, maxBytesOut), c.getContentType()).text;
            return new Res(code, text, "");
        } catch (Exception e) {
            return new Res(0, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    // ================================================================ 带闸门入口

    /**
     * 带 SSRF 闸门的 GET：<b>每一跳</b>都过 {@link NetGuard}，跳转自己走（最多 {@link #MAX_HOPS} 跳）。
     * 被拒时返回 {@code code=0} 且 {@link Res#blocked} 非空。
     */
    public static Res getGuarded(String url, Map<String, String> hdrs, int timeoutMs) {
        return getGuarded(url, hdrs, timeoutMs, null);
    }

    /** 同上，可指定响应体上限（字节）。 */
    public static Res getGuarded(String url, Map<String, String> hdrs, int timeoutMs, long[] maxBytesOut) {
        Hop hop;
        try {
            hop = open(url, hdrs, timeoutMs, "GET", null);
        } catch (Exception e) {
            return new Res(0, "", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (hop.blocked != null) return blockedRes(hop);
        if (hop.error != null) {
            return new Res(0, "", "", "", "", "", hop.error, "", hop.url);
        }
        try {
            InputStream in = hop.code >= 400 ? hop.c.getErrorStream() : hop.c.getInputStream();
            byte[] bytes = in == null ? new byte[0] : readAll(hop.c, in, maxBytesOut);
            Text.Decoded d = decodeFull(bytes, hop.c.getContentType());
            return new Res(hop.code, d.text, "", "", "", "", "", d.charset, hop.url);
        } catch (Exception e) {
            return new Res(0, "", e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "", "", "", "", "", hop.url);
        } finally {
            try { hop.c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /**
     * 下载到文件（技能的 {@code file op=download} 唯一入口）：每一跳过闸门，失败/被拒不留半截文件。
     *
     * @return {@link Dl}；{@link Dl#ok()} 为 false 时看 {@link Dl#error} 或 {@link Dl#blocked}
     */
    public static Dl download(String url, File dst, int timeoutMs) {
        Hop hop;
        try {
            hop = open(url, null, timeoutMs, "GET", null);
        } catch (Exception e) {
            return new Dl(e.getClass().getSimpleName() + ": " + e.getMessage(), "", "", "", "", "", 0, 0L);
        }
        if (hop.blocked != null) {
            return new Dl("", hop.blocked, hop.blockedHost, hop.blockedReason, "", hop.url, 0, 0L);
        }
        if (hop.error != null) return new Dl("", "", "", "", hop.error, hop.url, 0, 0L);
        if (hop.code < 200 || hop.code >= 300) {
            try { hop.c.disconnect(); } catch (Exception ignored) {}
            return new Dl("HTTP " + hop.code, "", "", "", "", hop.url, hop.code, 0L);
        }
        OutputStream os = null;
        InputStream in = null;
        try {
            File p = dst.getParentFile();
            if (p != null && !p.exists()) p.mkdirs();
            in = hop.c.getInputStream();
            os = new FileOutputStream(dst);
            byte[] buf = new byte[16384];
            int n;
            long total = 0L;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                total += n;
            }
            os.flush();
            return new Dl("", "", "", "", "", hop.url, hop.code, total);
        } catch (Exception e) {
            try { if (dst != null && dst.isFile()) dst.delete(); } catch (Exception ignored) {}
            return new Dl(e.getClass().getSimpleName() + ": " + e.getMessage(),
                    "", "", "", "", hop.url, hop.code, 0L);
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            try { hop.c.disconnect(); } catch (Exception ignored) {}
        }
    }

    // ================================================================ 解码

    /** 按 {@link Text} 的口径解码响应体（字符集名见 {@link Res#charset}）。 */
    public static String decode(byte[] body, String contentType) {
        return decodeFull(body, contentType).text;
    }

    /** 同上，带字符集信息。 */
    public static Text.Decoded decodeFull(byte[] body, String contentType) {
        return Text.decode(body, Text.contentTypeCharset(contentType));
    }

    // ================================================================ 跳转 + 读体

    /** 一跳的结果（活的连接 / 被闸门拒 / 结构错误）。 */
    private static final class Hop {
        HttpURLConnection c;
        String url;
        int code;
        /** 非 null = 被闸门拒绝（机器码）。 */
        String blocked;
        String blockedHost = "";
        String blockedReason = "";
        /** 非 null = 结构性错误码（redirect_no_location / too_many_redirects）。 */
        String error;
    }

    /**
     * 逐跳打开：关掉 JDK 的自动跟随，自己解析 {@code Location}；
     * <b>每一跳在发起连接之前</b>先过 {@link NetGuard}。
     */
    private static Hop open(String url, Map<String, String> hdrs, int timeoutMs,
                            String method, String body) throws IOException {
        String cur = url;
        for (int hop = 0; hop <= MAX_HOPS; hop++) {
            NetGuard.Verdict v = NetGuard.check(cur);
            if (v.blocked) {
                Hop h = new Hop();
                h.url = cur;
                h.blocked = v.code;
                h.blockedHost = v.host;
                h.blockedReason = v.reason;
                return h;
            }
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            c.setRequestMethod(method == null ? "GET" : method);
            c.setConnectTimeout(timeoutMs > 0 ? timeoutMs : 15000);
            c.setReadTimeout(timeoutMs > 0 ? timeoutMs : 120000);
            c.setInstanceFollowRedirects(false);         // 关键：跳转必须由我们逐跳判定
            c.setRequestProperty("User-Agent", "AiAgentV4/1.0");
            if (hdrs != null) for (Map.Entry<String, String> e : hdrs.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) c.setRequestProperty(e.getKey(), e.getValue());
            }
            if (body != null) {
                c.setDoOutput(true);
                OutputStream os = c.getOutputStream();
                try {
                    os.write(body.getBytes(Fs.UTF8));
                    os.flush();
                } finally {
                    os.close();
                }
            }
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                try { c.disconnect(); } catch (Exception ignored) {}
                if (Str.blank(loc)) {
                    Hop h = new Hop();
                    h.url = cur;
                    h.code = code;
                    h.error = "redirect_no_location";
                    return h;
                }
                cur = resolve(cur, Str.trim(loc));
                continue;                                // 下一跳重新判定
            }
            Hop h = new Hop();
            h.c = c;
            h.url = cur;
            h.code = code;
            return h;
        }
        Hop h = new Hop();
        h.url = cur;
        h.error = "too_many_redirects";
        return h;
    }

    private static Res blockedRes(Hop hop) {
        return new Res(0, "", "", hop.blocked, hop.blockedHost, hop.blockedReason, "", "", hop.url);
    }

    /** 相对 Location → 绝对 URL。 */
    static String resolve(String base, String target) {
        try {
            return new URI(base).resolve(target).toString();
        } catch (Exception e) {
            return target;
        }
    }

    /** 读响应体（按 Content-Encoding 解 gzip/deflate，按上限截断）。 */
    private static byte[] readAll(HttpURLConnection c, InputStream raw, long[] maxBytesOut) throws Exception {
        String enc = c == null ? "" : Str.lower(Str.nz(c.getContentEncoding()));
        InputStream in = raw;
        if (enc.contains("gzip")) in = new GZIPInputStream(raw);
        else if (enc.contains("deflate")) in = new InflaterInputStream(raw);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            long total = 0L;
            long max = (maxBytesOut != null && maxBytesOut.length > 0 && maxBytesOut[0] > 0)
                    ? maxBytesOut[0] : DEF_MAX_BYTES;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            try { if (in != raw) in.close(); } catch (Exception ignored) {}
        }
    }
}
