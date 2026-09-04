package sair.aiagent.util;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/**
 * 健壮的网页抓取器 —— 统一解决编码识别、gzip/deflate 解压、重定向、正文提取等复杂场景。
 *
 * <p>定位：{@code AgentActionHandler.executeWeb} 与 {@code executeDownload} 的底层抓取能力，
 * 避免散落重复、脆弱的 HTTP 代码。SSRF 防护、确认门控仍由调用方负责。</p>
 *
 * <h3>强化的短板</h3>
 * <ul>
 *   <li>编码识别：Content-Type charset → HTML meta charset → UTF-8 回退（乱码自动回退 GBK）。</li>
 *   <li>解压：自动处理 gzip / deflate 响应。</li>
 *   <li>重定向：HTTP 3xx 自动跟随 + 手动识别 meta refresh 跳转（最多 5 次）。</li>
 *   <li>正文提取：剔除 script/style/head/注释，转义 HTML 实体，压缩空白，提高信息密度。</li>
 *   <li>重试：网络异常自动重试 2 次（指数退避）。</li>
 * </ul>
 */
public final class WebFetcher {

    private static final int CONNECT_TIMEOUT = 10_000;
    private static final int READ_TIMEOUT = 20_000;
    private static final int MAX_BODY_LEN = 12_000;          // 提取后正文最大字符数
    private static final int MAX_JSON_LEN = 50_000;          // JSON 响应最大字符数（比 HTML 正文宽，容纳完整结构化字段）
    private static final int MAX_META_REDIRECTS = 5;         // meta refresh 重定向上限
    private static final int MAX_RETRIES = 2;                // 网络错误重试次数
    private static final int MAX_RAW_BYTES = 5 * 1024 * 1024; // 原始响应字节上限

    private WebFetcher() {}

    /** 抓取结果。{@code redirectUrl} 非 null 表示还需重定向到该地址。 */
    public static final class FetchResult {
        public final int status;
        public final String finalUrl;
        public final String contentType;
        public final String charset;
        public final String text;
        public final boolean success;
        public final String error;
        public final String redirectUrl;

        FetchResult(int status, String finalUrl, String contentType, String charset,
                    String text, boolean success, String error, String redirectUrl) {
            this.status = status;
            this.finalUrl = finalUrl;
            this.contentType = contentType;
            this.charset = charset;
            this.text = text;
            this.success = success;
            this.error = error;
            this.redirectUrl = redirectUrl;
        }
    }

    /** 抓取网页并提取正文。返回的 {@link FetchResult#text} 为纯文本正文。 */
    public static FetchResult fetch(String rawUrl) {
        String url = normalizeUrl(rawUrl);
        if (url == null) {
            return new FetchResult(-1, rawUrl, null, null, null, false, "URL 格式无效", null);
        }
        String current = url;
        for (int hop = 0; hop <= MAX_META_REDIRECTS; hop++) {
            String host = hostOf(current);
            if (isInternalHost(host)) {
                return new FetchResult(-1, current, null, null, null, false,
                        "禁止访问内网地址 (" + host + ")", null);
            }
            FetchResult r = fetchOnceWithRetry(current);
            if (r.redirectUrl != null) {
                current = r.redirectUrl;
                continue;
            }
            return r;
        }
        return new FetchResult(-1, current, null, null, null, false, "重定向次数超限（" + MAX_META_REDIRECTS + "）", null);
    }

    /** 单次抓取（含网络重试）。 */
    private static FetchResult fetchOnceWithRetry(String url) {
        Exception last = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return fetchOnce(url);
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_RETRIES) {
                    try { Thread.sleep(400L * (attempt + 1)); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                }
            }
        }
        return new FetchResult(-1, url, null, null, null, false,
                last != null ? last.toString() : "网络请求失败", null);
    }

    /** 单次 HTTP 请求，返回结果或重定向目标。 */
    private static FetchResult fetchOnce(String url) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            configure(conn);
            int status = conn.getResponseCode();
            String finalUrl = conn.getURL().toString();
            String finalHost = hostOf(finalUrl);
            if (isInternalHost(finalHost)) {
                return new FetchResult(status, finalUrl, null, null, null, false,
                        "禁止访问内网地址 (" + finalHost + ")", null);
            }

            if (status >= 300 && status < 400) {
                String loc = conn.getHeaderField("Location");
                if (loc == null || loc.trim().isEmpty()) {
                    return new FetchResult(status, finalUrl, null, null, null, false,
                            "HTTP " + status + " 但缺少 Location", null);
                }
                return new FetchResult(status, finalUrl, null, null, null, false, null,
                        resolve(url, loc.trim()));
            }
            if (status != 200) {
                if (status == 429 || status == 500 || status == 502 || status == 503 || status == 504) {
                    throw new java.io.IOException("HTTP " + status);
                }
                return new FetchResult(status, finalUrl, null, null, null, false, "HTTP " + status, null);
            }

            byte[] bytes = readBody(conn);
            String contentType = conn.getContentType();
            String charset = detectCharset(contentType, bytes);

            // JSON 响应：原样返回结构化文本，不走 jsoup 正文提取（避免 JSON 被当作 HTML 解析导致字段丢失/截断）
            if (isJsonResponse(contentType, bytes)) {
                String json = new String(bytes, charset);
                if ("UTF-8".equalsIgnoreCase(charset) && hasReplacementChar(json)) {
                    json = new String(bytes, "GBK");
                    charset = "GBK";
                }
                if (json.length() > MAX_JSON_LEN) {
                    json = json.substring(0, MAX_JSON_LEN) + "\n…(JSON 过长已截断)";
                }
                return new FetchResult(status, finalUrl, contentType, charset, json, true, null, null);
            }

            String html = new String(bytes, charset);
            // UTF-8 解码产生大量替换字符 → 大概率编码错误，回退 GBK
            if ("UTF-8".equalsIgnoreCase(charset) && hasReplacementChar(html)) {
                html = new String(bytes, "GBK");
                charset = "GBK";
            }

            // meta refresh 重定向
            String meta = detectMetaRefresh(html);
            if (meta != null) {
                return new FetchResult(status, finalUrl, contentType, charset, null, false, null,
                        resolve(url, meta));
            }

            String text = extractText(html);
            return new FetchResult(status, finalUrl, contentType, charset, text, true, null, null);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ==================== HTTP ====================

    private static void configure(HttpURLConnection conn) {
        conn.setConnectTimeout(CONNECT_TIMEOUT);
        conn.setReadTimeout(READ_TIMEOUT);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
    }

    /** 读取响应字节，自动处理 gzip / deflate 解压，限制最大字节数。 */
    private static byte[] readBody(HttpURLConnection conn) throws Exception {
        String encoding = conn.getContentEncoding();
        InputStream raw = conn.getInputStream();
        InputStream is = raw;
        if ("gzip".equalsIgnoreCase(encoding)) {
            is = new GZIPInputStream(raw);
        } else if ("deflate".equalsIgnoreCase(encoding)) {
            is = new InflaterInputStream(raw);
        }
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int total = 0;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
                total += n;
                if (total > MAX_RAW_BYTES) break; // 超大响应截断
            }
            return baos.toByteArray();
        } finally {
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    // ==================== 编码识别 ====================

    private static String detectCharset(String contentType, byte[] bytes) {
        String cs = charsetFromContentType(contentType);
        if (cs != null) return cs;
        // 用 ISO-8859-1 安全读取头部（前 4KB）找 meta charset
        String head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
        cs = charsetFromMeta(head);
        if (cs != null) return cs;
        return "UTF-8";
    }

    private static String charsetFromContentType(String contentType) {
        if (contentType == null) return null;
        Matcher m = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([a-zA-Z0-9._-]+)").matcher(contentType);
        return m.find() ? m.group(1) : null;
    }

    private static String charsetFromMeta(String head) {
        Matcher m = Pattern.compile("(?i)<meta[^>]+charset\\s*=\\s*[\"']?([a-zA-Z0-9._-]+)").matcher(head);
        return m.find() ? m.group(1) : null;
    }

    /** 判断字符串是否含较多 Unicode 替换字符（\uFFFD），用于检测解码失败。 */
    private static boolean hasReplacementChar(String s) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\uFFFD') count++;
        }
        return count > 0 && count * 20 > s.length(); // 超过 5% 视为解码失败
    }

    // ==================== 重定向 ====================

    /** 识别 meta refresh 跳转，如 <meta http-equiv="refresh" content="0; url=xxx">。 */
    private static String detectMetaRefresh(String html) {
        Matcher m = Pattern.compile(
                "(?is)<meta[^>]+http-equiv\\s*=\\s*[\"']?refresh[\"']?[^>]+content\\s*=\\s*[\"']([^\"']+)[\"']"
        ).matcher(html);
        if (!m.find()) return null;
        String content = m.group(1);
        Matcher um = Pattern.compile("(?i)url\\s*=\\s*([^;]+)").matcher(content);
        if (!um.find()) return null;
        String u = um.group(1).trim();
        if (u.startsWith("\"") || u.startsWith("'")) u = u.substring(1);
        if (u.endsWith("\"") || u.endsWith("'")) u = u.substring(0, u.length() - 1);
        return u.isEmpty() ? null : u;
    }

    private static String resolve(String base, String target) {
        try {
            return new URI(base).resolve(target).toString();
        } catch (Exception e) {
            return target;
        }
    }

    private static String normalizeUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        return u;
    }

    private static String hostOf(String url) {
        try {
            return new URI(url).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isInternalHost(String host) {
        if (host == null || host.isEmpty()) return true;
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("0.0.0.0")) return true;
        if (lower.startsWith("10.") || lower.startsWith("192.168.")) return true;
        if (lower.startsWith("172.")) {
            try {
                int second = Integer.parseInt(lower.substring(4, lower.indexOf('.', 4)));
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {}
        }
        try {
            InetAddress addr = InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (ip == null) return false;
            if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0") || ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
            if (ip.startsWith("172.")) {
                int di = ip.indexOf('.', 4);
                if (di > 0) {
                    int s = Integer.parseInt(ip.substring(4, di));
                    if (s >= 16 && s <= 31) return true;
                }
            }
            if (ip.startsWith("169.254.")) return true;
            if (ip.equals("::1") || ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    // ==================== 正文提取 ====================

    /** 判断响应是否为 JSON：优先看 Content-Type 含 json，其次看正文是否以 { 或 [ 开头（跳过 BOM/空白）。 */
    private static boolean isJsonResponse(String contentType, byte[] bytes) {
        if (contentType != null && contentType.toLowerCase().contains("json")) return true;
        if (bytes == null || bytes.length == 0) return false;
        int i = 0;
        // 跳过 UTF-8 BOM
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b == ' ' || b == '\t' || b == '\n' || b == '\r') { i++; continue; }
            return b == '{' || b == '[';
        }
        return false;
    }

    /** 从 HTML 提取纯文本正文：用 jsoup 剔除脚本/样式/注释，自动解码实体、压缩空白，截断。 */
    private static String extractText(String html) {
        if (html == null || html.isEmpty()) return "";
        Document doc = Jsoup.parse(html);
        doc.select("script, style, noscript, iframe, head").remove();
        String s = doc.text();
        if (s.length() > MAX_BODY_LEN) {
            s = s.substring(0, MAX_BODY_LEN) + "\n…(正文过长已截断)";
        }
        return s;
    }
}
