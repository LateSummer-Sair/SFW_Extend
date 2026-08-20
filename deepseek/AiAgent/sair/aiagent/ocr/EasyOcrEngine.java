package sair.aiagent.ocr;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * EasyOCR 在线 OCR 引擎实现。
 *
 * <p>调用 EasyOCR 官方 REST API：{@code POST https://console.easyocr.org/api/ocr}，
 * 通过 multipart/form-data 上传图片，Header 携带 {@code X-Access-Key} 认证。</p>
 *
 * <p>响应格式（节选）：<pre>
 * {
 *   "words": [
 *     { "text": "识别出的文字块", "rate": 0.998, ... },
 *     ...
 *   ]
 * }</pre>
 * 本实现按顺序拼接所有 {@code words[].text}，用换行分隔。</p>
 *
 * <p>识别失败（网络异常 / 认证失败 / 额度不足 / 服务不可用等）时静默返回 {@code null}，
 * 不向调用方抛出异常，也不打印任何日志，由上层统一决定降级策略。</p>
 */
public final class EasyOcrEngine implements OcrEngine {

    /** EasyOCR 统一接口地址 */
    public static final String API_URL = "https://console.easyocr.org/api/ocr";

    /** 单次上传图片大小上限（字节），与 EasyOCR 文档一致 */
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    private final String accessKey;
    private final int connectTimeout;
    private final int readTimeout;

    public EasyOcrEngine(String accessKey) {
        this(accessKey, 8000, 20000);
    }

    public EasyOcrEngine(String accessKey, int connectTimeout, int readTimeout) {
        this.accessKey = accessKey;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    @Override
    public String getName() {
        return "EasyOCR";
    }

    @Override
    public String recognize(byte[] imageBytes) {
        if (accessKey == null || accessKey.isEmpty()) return null;
        if (imageBytes == null || imageBytes.length == 0) return null;
        if (imageBytes.length > MAX_IMAGE_BYTES) return null;

        HttpURLConnection conn = null;
        try {
            String boundary = "----AiAgentOcr" + System.currentTimeMillis();
            String mime = detectMime(imageBytes);
            String fileName = fileNameOf(mime);

            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("X-Access-Key", accessKey);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AiAgent-SFW/3.0)");
            conn.setDoOutput(true);
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);

            byte[] body = buildMultipartBody(boundary, fileName, mime, imageBytes);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(is);
            if (code < 200 || code >= 300) {
                return null; // 认证失败 / 额度不足 / 服务不可用等，静默降级
            }
            return parseWords(resp);
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 构建 multipart/form-data 请求体。 */
    private static byte[] buildMultipartBody(String boundary, String fileName, String mime,
                                             byte[] imageBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String preamble = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + mime + "\r\n\r\n";
        out.write(preamble.getBytes(StandardCharsets.UTF_8));
        out.write(imageBytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** 读取输入流全部内容为 UTF-8 字符串。 */
    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 解析响应 JSON，拼接所有 words[].text。 */
    private static String parseWords(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj == null) return null;
            JsonArray words = (obj.has("words") && obj.get("words").isJsonArray())
                    ? obj.getAsJsonArray("words") : null;
            if (words == null || words.size() == 0) return null;

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < words.size(); i++) {
                JsonObject w = words.get(i).getAsJsonObject();
                if (w == null || !w.has("text") || w.get("text").isJsonNull()) continue;
                String text = w.get("text").getAsString();
                if (text != null && !text.trim().isEmpty()) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(text);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 探测图片 MIME 类型（依据文件魔数）。 */
    private static String detectMime(byte[] b) {
        if (b.length >= 8) {
            if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
            if (b[0] == 'B' && b[1] == 'M') return "image/bmp";
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                    && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
            if ((b[0] == 'I' && b[1] == 'I' && b[2] == 0x2A && b[3] == 0x00)
                    || (b[0] == 'M' && b[1] == 'M' && b[2] == 0x00 && b[3] == 0x2A)) return "image/tiff";
        }
        return "image/jpeg"; // 默认
    }

    /** 由 MIME 类型推导文件扩展名。 */
    private static String fileNameOf(String mime) {
        if (mime == null) return "image.jpg";
        if (mime.contains("png")) return "image.png";
        if (mime.contains("gif")) return "image.gif";
        if (mime.contains("bmp")) return "image.bmp";
        if (mime.contains("webp")) return "image.webp";
        if (mime.contains("tiff")) return "image.tiff";
        return "image.jpg";
    }
}
