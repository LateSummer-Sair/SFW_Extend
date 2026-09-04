package sair.aiagent.onebot;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.core.AiConfig;

/**
 * QQ 图片下载与多模态 API 格式转换工具类。
 * 从 QQMessageHandler 中提取，负责下载 QQ 图片并将其转为 DeepSeek Vision API 可用格式。
 */
public final class ImageDownloader {

    private ImageDownloader() {} // 纯静态工具类，禁止实例化

    /** 视觉模型图片大小上限：3MB（超过走 File API 上传，用 file_id 引用） */
    public static final int MAX_VISION_IMAGE_BYTES = 3 * 1024 * 1024;
    /** 图片超过大小限制且无法通过 File API 处理时的特殊返回标记 */
    public static final String VISION_OVERSIZE = "__VISION_OVERSIZE_3MB__";
    /** file_id 返回前缀：resolveImageForVision 返回 "file:file-api-xxx" 表示已上传，需用 file 内容块引用 */
    public static final String FILE_ID_PREFIX = "file:";

    /** 判断视觉解析结果是否为「超过3M且无法处理」标记 */
    public static boolean isOversizeResult(String result) {
        return VISION_OVERSIZE.equals(result);
    }

    /** 判断视觉解析结果是否为「已上传 File API 的 file_id」标记 */
    public static boolean isFileIdResult(String result) {
        return result != null && result.startsWith(FILE_ID_PREFIX);
    }

    /**
     * 下载图片并转换为 base64 data URI。
     * 用于 DeepSeek Vision API：当 QQ 图片 URL 对 API 不可访问时，
     * 通过本地下载转为 base64 后传入。
     *
     * @param imageUrl 图片 URL
     * @return base64 data URI 字符串（如 "data:image/jpeg;base64,..."），失败返回 null
     */
    public static String downloadImageAsBase64(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return null;

        HttpURLConnection conn = null;
        ByteArrayOutputStream baos = null;
        InputStream is = null;
        try {
            conn = openWithSafeRedirect(imageUrl);
            if (conn == null) {
                AiAgentActivity.debugLog("[QQMsg] 图片下载被拒绝（内网/重定向超限）: " + imageUrl);
                return null;
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                AiAgentActivity.debugLog("[QQMsg] 图片下载失败: HTTP " + code + " for " + imageUrl);
                return null;
            }

            // 检测 Content-Type 以确定图片格式
            String contentType = conn.getContentType();
            String mimeType = "image/jpeg"; // 默认
            if (contentType != null) {
                contentType = contentType.toLowerCase();
                if (contentType.contains("png")) mimeType = "image/png";
                else if (contentType.contains("gif")) mimeType = "image/gif";
                else if (contentType.contains("webp")) mimeType = "image/webp";
                else if (contentType.contains("bmp")) mimeType = "image/bmp";
            }

            is = conn.getInputStream();
            baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int totalBytes = 0;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
                totalBytes += n;
                if (totalBytes > 5 * 1024 * 1024) { // 限制 5MB
                    AiAgentActivity.debugLog("[QQMsg] 图片过大(>5MB)，放弃下载: " + imageUrl);
                    return null;
                }
            }

            byte[] imageBytes = baos.toByteArray();
            // 压缩大图：Vision API 建议 &lt;2MB，长边 2000px
            imageBytes = compressImageIfNeeded(imageBytes, mimeType, totalBytes);
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:" + mimeType + ";base64," + base64;
            AiAgentActivity.debugLog("[QQMsg] 图片下载成功: " + totalBytes + " bytes -> 压缩后 " + imageBytes.length + " bytes, base64 length " + base64.length());
            return dataUri;

        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] 图片下载异常: " + e.toString());
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (baos != null) baos.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 下载图片并返回原始字节（不压缩），供本地二维码识别 / OCR 使用。
     * <p>压缩会降低二维码识别精度，因此这里保留原始字节。</p>
     *
     * @param imageUrl 图片 URL（http/https 或 base64 data URI）
     * @return 原始图片字节，失败返回 null
     */
    public static byte[] downloadImage(String imageUrl) {
        if (imageUrl == null || imageUrl.isEmpty()) return null;

        // base64 data URI 直接解码
        if (imageUrl.startsWith("data:")) {
            try {
                int comma = imageUrl.indexOf(',');
                if (comma > 0) return Base64.getDecoder().decode(imageUrl.substring(comma + 1));
            } catch (Exception e) {
                AiAgentActivity.debugLog("[QQMsg] base64 图片解码失败: " + e.toString());
            }
            return null;
        }

        HttpURLConnection conn = null;
        ByteArrayOutputStream baos = null;
        InputStream is = null;
        try {
            conn = openWithSafeRedirect(imageUrl);
            if (conn == null) {
                AiAgentActivity.debugLog("[QQMsg] 图片下载被拒绝（内网/重定向超限）: " + imageUrl);
                return null;
            }

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                AiAgentActivity.debugLog("[QQMsg] 图片下载失败: HTTP " + code + " for " + imageUrl);
                return null;
            }

            is = conn.getInputStream();
            baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            int totalBytes = 0;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
                totalBytes += n;
                if (totalBytes > 20 * 1024 * 1024) { // 限制 20MB（File API 官方上限 64MiB，留足余量）
                    AiAgentActivity.debugLog("[QQMsg] 图片过大(>20MB)，放弃下载: " + imageUrl);
                    return null;
                }
            }
            return baos.toByteArray();
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] 图片下载异常: " + e.toString());
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            try { if (baos != null) baos.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 解析图片 URL 为 Vision API 可用格式。
     *
     * @param imageUrl 原始图片 URL
     * @return Vision API 可用的图片 URL 或 base64 data URI，失败返回 null
     */
    public static String resolveImageForVision(String imageUrl) {
        return resolveImageForVision(imageUrl, null);
    }

    /**
     * 解析图片 URL 为 Vision API 可用格式（支持 NapCat API 兜底）。
     * 策略：优先使用原始 URL（DeepSeek 可直接访问公网 URL），
     * 若 URL 看起来像腾讯内网地址则下载转 base64；
     * HTTP 下载失败时通过 NapCat get_image API 兜底。
     *
     * @param imageUrl 原始图片 URL
     * @param napcatApi NapCat API 实例（可为 null）
     * @return Vision API 可用的图片 URL 或 base64 data URI，失败返回 null
     */
    public static String resolveImageForVision(String imageUrl, NapCatApi napcatApi) {
        return resolveImageForVision(imageUrl, null, napcatApi);
    }

    /**
     * 解析图片 URL 为 Vision API 可用格式（携带 file/md5，供 NapCat get_image 兜底）。
     * 策略：优先使用原始 URL（DeepSeek 可直接访问公网 URL），
     * 若 URL 看起来像腾讯内网地址则下载转 base64；
     * HTTP 下载失败时通过 NapCat get_image API 兜底（用 file/md5 而非 URL 文件名）。
     *
     * @param imageUrl 原始图片 URL
     * @param file 图片的 file 字段（md5 值），用于 NapCat get_image 兜底；可为 null
     * @param napcatApi NapCat API 实例（可为 null）
     * @return Vision API 可用的图片 URL 或 base64 data URI，失败返回 null
     */
    public static String resolveImageForVision(String imageUrl, String file, NapCatApi napcatApi) {
        if (imageUrl == null || imageUrl.isEmpty()) return null;

        // 已经是 base64 data URI：解码检查大小，超过 3M 走 File API
        if (imageUrl.startsWith("data:")) {
            byte[] decoded = decodeDataUri(imageUrl);
            if (decoded != null && decoded.length > MAX_VISION_IMAGE_BYTES) {
                return uploadAsFile(decoded, guessMimeType(imageUrl));
            }
            return imageUrl;
        }

        // 检查是否是腾讯内网域名（qpic.cn, gchat.qpic.cn 等）
        // 这些 URL DeepSeek API 可能无法访问，需要下载转 base64
        boolean isInternal = imageUrl.contains("qpic.cn") ||
                             imageUrl.contains("gchat.qpic") ||
                             imageUrl.contains("c2cpicdw.qpic") ||
                             imageUrl.contains("multimedia.nt.qq");

        // 统一先下载原始字节检查大小（超过 3M 走 File API 上传，用 file_id 引用）
        byte[] bytes = downloadImage(imageUrl);
        if (bytes != null) {
            if (bytes.length > MAX_VISION_IMAGE_BYTES) {
                AiAgentActivity.debugLog("[QQMsg] 图片超过3M限制，走 File API 上传: " + imageUrl + " (" + bytes.length + " bytes)");
                return uploadAsFile(bytes, guessMimeType(imageUrl));
            }
            if (isInternal) {
                // 内网图：复用已下载字节转 base64（不重复下载）
                String mime = guessMimeType(imageUrl);
                return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(bytes);
            }
            // 公网图：未超限，返回原始 URL（DeepSeek 直接访问）
            return imageUrl;
        }

        // 下载失败（网络异常或被下载上限截断）
        if (isInternal) {
            // 内网下载失败，尝试 NapCat get_image API 兜底（用 file/md5 而非 URL 文件名）
            if (napcatApi != null) {
                AiAgentActivity.debugLog("[QQMsg] 内网图HTTP下载失败，尝试 NapCat get_image 兜底 (file=" + file + ")");
                String viaNapCat = downloadImageViaNapCat(imageUrl, file, napcatApi);
                if (viaNapCat != null) {
                    byte[] decoded = decodeDataUri(viaNapCat);
                    if (decoded != null && decoded.length > MAX_VISION_IMAGE_BYTES) {
                        return uploadAsFile(decoded, guessMimeType(imageUrl));
                    }
                    AiAgentActivity.debugLog("[QQMsg] NapCat get_image 兜底成功: " + imageUrl);
                    return viaNapCat;
                }
            } else {
                AiAgentActivity.debugLog("[QQMsg] 内网图HTTP下载失败且 napcatApi 为 null，无法 NapCat 兜底: " + imageUrl);
            }
            // 内网图下载与 get_image 均失败：返回 null，避免 DeepSeek 长时间尝试访问无法访问的内网 URL（卡死）
            AiAgentActivity.debugLog("[QQMsg] 腾讯内网图片转换失败，跳过该图（避免视觉 API 长时间等待）");
            return null;
        }

        // 公网 URL 本地下载失败：无法判断大小，返回原始 URL 让 DeepSeek 直接访问
        return imageUrl;
    }

    /** 手动跟随重定向并复检目标 host，防止重定向 SSRF。 */
    private static HttpURLConnection openWithSafeRedirect(String imageUrl) throws IOException {
        String current = imageUrl;
        for (int hop = 0; hop <= 5; hop++) {
            String host = new java.net.URL(current).getHost();
            if (isBlockedHost(host)) return null;
            HttpURLConnection conn = (HttpURLConnection) new java.net.URL(current).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AiAgent-SFW/1.5)");
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.trim().isEmpty()) return null;
                current = new java.net.URL(new java.net.URL(current), loc.trim()).toString();
                continue;
            }
            // 最终地址复检，缩小 DNS Rebinding 窗口
            String finalHost = conn.getURL().getHost();
            if (isBlockedHost(finalHost)) {
                conn.disconnect();
                return null;
            }
            return conn;
        }
        return null;
    }

    private static boolean isBlockedHost(String host) {
        if (host == null || host.isEmpty()) return true;
        String lower = host.toLowerCase();
        if (lower.equals("localhost")) return true;
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (ip == null) return true;
            if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0") || ip.equals("::1")) return true;
            if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) return true;
            if (ip.startsWith("172.")) {
                int di = ip.indexOf('.', 4);
                if (di > 0) {
                    try { int s = Integer.parseInt(ip.substring(4, di)); if (s >= 16 && s <= 31) return true; }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) return true;
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    /** 从 URL 后缀猜测图片 MIME 类型（默认 jpeg）。 */
    private static String guessMimeType(String imageUrl) {
        String lower = imageUrl.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "image/jpeg";
    }

    /** 解码 base64 data URI 为原始字节，失败返回 null。 */
    private static byte[] decodeDataUri(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0) return null;
            return Base64.getDecoder().decode(dataUri.substring(comma + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 将超限图片上传到 DeepSeek Files API，返回 "file:file-api-xxx"。
     * <p>上传失败时返回 {@link #VISION_OVERSIZE}，由调用方降级跳过该图。</p>
     */
    private static String uploadAsFile(byte[] bytes, String mimeType) {
        String fileId = uploadToFileApi(bytes, mimeType);
        if (fileId != null && !fileId.isEmpty()) {
            return FILE_ID_PREFIX + fileId;
        }
        return VISION_OVERSIZE;
    }

    /**
     * 上传文件到 DeepSeek Files API（multipart/form-data）。
     * <p>官方文档：{@code POST {base_url}/files}，字段 {@code file} + {@code purpose=user_data}，
     * 响应 {@code {"id":"file-api-xxx",...}}。</p>
     * <p>注意：File API 端点使用原始 apiUrl，不能带 strict 模式的 /beta 后缀。</p>
     *
     * @return file_id（如 "file-api-xxx"），失败返回 null
     */
    private static String uploadToFileApi(byte[] bytes, String mimeType) {
        AiConfig cfg = AiConfig.getInstance();
        String apiKey = cfg.getApiKey();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            AiAgentActivity.debugLog("[QQMsg] File API 上传失败：未配置 API Key");
            return null;
        }
        String baseUrl = cfg.getApiUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) baseUrl = AiConfig.DEFAULT_API_URL;
        if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        String urlStr = baseUrl + "/files";

        String boundary = "----AiAgentFile" + Long.toHexString(System.nanoTime());
        String ext = mimeToExt(mimeType);
        String fileName = "vision_" + System.currentTimeMillis() + "." + ext;

        HttpURLConnection conn = null;
        OutputStream os = null;
        InputStream respStream = null;
        try {
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AiAgent-SFW/1.5)");

            os = conn.getOutputStream();
            // purpose 字段
            writePart(os, boundary, "purpose", "user_data");
            // file 字段（二进制）
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Type: " + mimeType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(bytes);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            // 结束 boundary
            os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            os.flush();

            int code = conn.getResponseCode();
            respStream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readStream(respStream);
            if (code < 200 || code >= 300) {
                AiAgentActivity.debugLog("[QQMsg] File API 上传失败: HTTP " + code + " -> " + resp);
                return null;
            }
            String fileId = sair.aiagent.onebot.util.JsonUtil.extractString(resp, "id");
            if (fileId == null || fileId.isEmpty()) {
                AiAgentActivity.debugLog("[QQMsg] File API 上传响应缺少 id: " + resp);
                return null;
            }
            AiAgentActivity.debugLog("[QQMsg] File API 上传成功: " + fileId + " (" + bytes.length + " bytes)");
            return fileId;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] File API 上传异常: " + e.toString());
            return null;
        } finally {
            try { if (respStream != null) respStream.close(); } catch (Exception ignored) {}
            try { if (os != null) os.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /** 根据 MIME 类型返回文件扩展名（默认 jpg）。 */
    private static String mimeToExt(String mimeType) {
        if (mimeType == null) return "jpg";
        String m = mimeType.toLowerCase();
        if (m.contains("png")) return "png";
        if (m.contains("gif")) return "gif";
        if (m.contains("webp")) return "webp";
        if (m.contains("bmp")) return "bmp";
        return "jpg";
    }

    /** 读取输入流为 UTF-8 字符串（流为空返回空串）。 */
    private static String readStream(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 写入一个 multipart 文本字段。 */
    private static void writePart(OutputStream os, String boundary, String name, String value) throws Exception {
        os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(value.getBytes(StandardCharsets.UTF_8));
        os.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 压缩图片：如果超过大小或尺寸限制，等比缩放。
     *
     * @param imageBytes 原始图片字节
     * @param mimeType   图片 MIME 类型
     * @param originalSize 原始字节数
     * @return 压缩后的图片字节
     */
    private static byte[] compressImageIfNeeded(byte[] imageBytes, String mimeType, int originalSize) {
        // 如果原始小于 1MB 且 base64 后 < 1.5MB，不压缩
        if (originalSize < 1024 * 1024 && imageBytes.length < 1024 * 1024) {
            return imageBytes;
        }

        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) return imageBytes; // 非图片格式，不处理

            int width = img.getWidth();
            int height = img.getHeight();
            int maxDim = Math.max(width, height);

            // 长边 > 2000px 或 原始大小 > 2MB → 缩放
            int targetMax = 2000;
            if (maxDim <= targetMax && originalSize < 2 * 1024 * 1024) {
                return imageBytes; // 无需缩放
            }

            // 等比缩放
            double scale = (double) targetMax / maxDim;
            int newW = (int) (width * scale);
            int newH = (int) (height * scale);

            // 创建缩放后的图像
            BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, newW, newH, null);
            g.dispose();

            // 编码为 JPEG（通用性好，压缩率高）
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            float quality = 0.85f;
            // 如果缩放后仍可能很大，再降质量
            if (originalSize > 3 * 1024 * 1024) quality = 0.75f;
            javax.imageio.plugins.jpeg.JPEGImageWriteParam jpegParams = null;
            try {
                javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                javax.imageio.ImageWriteParam iwp = writer.getDefaultWriteParam();
                if (iwp.canWriteCompressed()) {
                    iwp.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
                    iwp.setCompressionQuality(quality);
                    jpegParams = (javax.imageio.plugins.jpeg.JPEGImageWriteParam) iwp;
                }
            } catch (Exception ignored) {}

            if (jpegParams != null) {
                javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                try {
                    javax.imageio.stream.ImageOutputStream ios = ImageIO.createImageOutputStream(out);
                    writer.setOutput(ios);
                    writer.write(null, new javax.imageio.IIOImage(scaled, null, null), jpegParams);
                    ios.close();
                    writer.dispose();
                } catch (Exception e) {
                    ImageIO.write(scaled, "JPEG", out);
                }
            } else {
                ImageIO.write(scaled, "JPEG", out);
            }
            out.close();

            byte[] compressed = out.toByteArray();
            AiAgentActivity.debugLog("[QQMsg] 图片压缩: " + originalSize + " bytes -> " + compressed.length + " bytes (" + width + "x" + height + " -> " + newW + "x" + newH + ")");

            // 如果压缩后仍 > 2MB，进一步缩小到 1500px
            if (compressed.length > 2 * 1024 * 1024 && maxDim > 1500) {
                double scale2 = 1500.0 / maxDim;
                int w2 = (int) (width * scale2);
                int h2 = (int) (height * scale2);
                BufferedImage s2 = new BufferedImage(w2, h2, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = s2.createGraphics();
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, 0, 0, w2, h2, null);
                g2.dispose();
                ByteArrayOutputStream out2 = new ByteArrayOutputStream();
                ImageIO.write(s2, "JPEG", out2);
                out2.close();
                compressed = out2.toByteArray();
                AiAgentActivity.debugLog("[QQMsg] 图片二次压缩: " + width + "x" + height + " -> " + w2 + "x" + h2 + " (" + compressed.length + " bytes)");
            }

            return compressed;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] 图片压缩失败，使用原始图片: " + e.toString());
            return imageBytes;
        }
    }

    /**
     * 通过 NapCat get_image API 下载图片并转为 base64（HTTP 直连失败的兜底方案）。
     * <p>优先使用传入的 file（消息段 file 字段，旧版为 md5，新版为 fileid）；
     * file 为空时从 URL 提取 {@code fileid} 参数（新版 multimedia.nt.qq.com.cn 格式）；
     * 再为空才回退到从 URL 路径提取文件名（旧版 qpic.cn 格式）。</p>
     *
     * @param imageUrl 原始图片 URL
     * @param file 图片的 file 字段（md5/fileid），可为 null
     * @param napcatApi NapCat API 实例
     * @return base64 data URI，失败返回 null
     */
    private static String downloadImageViaNapCat(String imageUrl, String file, NapCatApi napcatApi) {
        if (napcatApi == null || imageUrl == null) return null;
        try {
            // 1. 优先用 file（消息段 file 字段：旧版 md5 / 新版 fileid）
            String fileId = (file != null && !file.isEmpty()) ? file : null;
            // 2. 从 URL 提取 fileid 参数（新版 multimedia.nt.qq.com.cn/download?fileid=...）
            if (fileId == null || fileId.isEmpty()) {
                fileId = extractUrlParam(imageUrl, "fileid");
            }
            // 3. 最后回退：从 URL 路径提取文件名（旧版 qpic.cn 格式）
            if (fileId == null || fileId.isEmpty()) {
                fileId = extractFileNameFromUrl(imageUrl);
            }
            if (fileId == null || fileId.isEmpty()) {
                AiAgentActivity.debugLog("[QQMsg] NapCat get_image 无法确定 file 参数: " + imageUrl);
                return null;
            }

            AiAgentActivity.debugLog("[QQMsg] NapCat get_image 兜底下载: file=" + fileId);
            String resp = napcatApi.getImage(fileId);
            if (resp == null || resp.isEmpty()) {
                AiAgentActivity.debugLog("[QQMsg] NapCat get_image 返回空: file=" + fileId);
                return null;
            }

            // 解析响应，提取 base64 数据
            String base64 = sair.aiagent.onebot.util.JsonUtil.extractString(resp, "data");
            if (base64 != null && !base64.isEmpty()) {
                String mimeType = "image/jpeg"; // 默认
                if (base64.startsWith("data:")) {
                    // 可能返回的是完整的 data URI
                    int colon = base64.indexOf(';');
                    if (colon > 5) mimeType = base64.substring(5, colon);
                    return base64;
                }
                // 纯 base64，加上 data URI 前缀
                if (fileId.toLowerCase().endsWith(".png")) mimeType = "image/png";
                else if (fileId.toLowerCase().endsWith(".webp")) mimeType = "image/webp";
                else if (fileId.toLowerCase().endsWith(".gif")) mimeType = "image/gif";
                return "data:" + mimeType + ";base64," + base64;
            }
            // 尝试提取 file 字段
            String fileField = sair.aiagent.onebot.util.JsonUtil.extractString(resp, "file");
            if (fileField != null && !fileField.isEmpty()) {
                return "data:image/jpeg;base64," + fileField;
            }
            AiAgentActivity.debugLog("[QQMsg] NapCat get_image 响应无 data/file 字段: " + resp);
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] NapCat get_image 失败: " + e.toString());
        }
        return null;
    }

    /** 从 URL 查询参数中提取指定 key 的值（URL 解码），失败返回 null。 */
    private static String extractUrlParam(String url, String key) {
        if (url == null || key == null) return null;
        try {
            java.net.URL u = new java.net.URL(url);
            String query = u.getQuery();
            if (query == null || query.isEmpty()) return null;
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                String k = pair.substring(0, eq);
                if (key.equals(k)) {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 URL 路径提取文件名（去掉查询参数），失败返回 null。 */
    private static String extractFileNameFromUrl(String url) {
        if (url == null) return null;
        try {
            int qm = url.indexOf('?');
            String path = qm > 0 ? url.substring(0, qm) : url;
            int lastSlash = path.lastIndexOf('/');
            if (lastSlash >= 0 && lastSlash < path.length() - 1) {
                return path.substring(lastSlash + 1);
            }
        } catch (Exception ignored) {}
        return null;
    }
}
