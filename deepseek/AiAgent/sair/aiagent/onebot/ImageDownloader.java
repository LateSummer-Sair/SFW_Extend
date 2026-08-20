package sair.aiagent.onebot;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

import javax.imageio.ImageIO;

import sair.aiagent.AiAgentActivity;

/**
 * QQ 图片下载与多模态 API 格式转换工具类。
 * 从 QQMessageHandler 中提取，负责下载 QQ 图片并将其转为 DeepSeek Vision API 可用格式。
 */
public final class ImageDownloader {

    private ImageDownloader() {} // 纯静态工具类，禁止实例化

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
            conn = (HttpURLConnection) new URL(imageUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; AiAgent-SFW/1.5)");
            conn.setInstanceFollowRedirects(true);

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
            conn = (HttpURLConnection) new URL(imageUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AiAgent-SFW/1.5)");
            conn.setInstanceFollowRedirects(true);

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
                if (totalBytes > 5 * 1024 * 1024) { // 限制 5MB
                    AiAgentActivity.debugLog("[QQMsg] 图片过大(>5MB)，放弃下载: " + imageUrl);
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
        if (imageUrl == null || imageUrl.isEmpty()) return null;

        // 已经是 base64 data URI，直接返回
        if (imageUrl.startsWith("data:")) return imageUrl;

        // 检查是否是腾讯内网域名（qpic.cn, gchat.qpic.cn 等）
        // 这些 URL DeepSeek API 可能无法访问，需要下载转 base64
        boolean isInternal = imageUrl.contains("qpic.cn") ||
                             imageUrl.contains("gchat.qpic") ||
                             imageUrl.contains("c2cpicdw.qpic") ||
                             imageUrl.contains("multimedia.nt.qq");

        if (isInternal) {
            AiAgentActivity.debugLog("[QQMsg] 检测到腾讯内网图片URL，下载转base64: " + imageUrl);
            String base64 = downloadImageAsBase64(imageUrl);
            if (base64 != null) return base64;
            // HTTP 下载失败，尝试通过 NapCat get_image API 获取
            if (napcatApi != null) {
                AiAgentActivity.debugLog("[QQMsg] HTTP下载失败，尝试 NapCat get_image API 兜底");
                base64 = downloadImageViaNapCat(imageUrl, napcatApi);
                if (base64 != null) return base64;
            }
            AiAgentActivity.debugLog("[QQMsg] 图片base64转换失败，尝试直接传URL");
        }

        // 公网 URL 或 base64 失败兜底：直接传原始 URL
        return imageUrl;
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
     *
     * @param imageUrl 原始图片 URL（用于提取 file_id）
     * @param napcatApi NapCat API 实例
     * @return base64 data URI，失败返回 null
     */
    private static String downloadImageViaNapCat(String imageUrl, NapCatApi napcatApi) {
        if (napcatApi == null || imageUrl == null) return null;
        try {
            // 从 URL 中提取 file_id（OneBot image 消息段中的 file 字段）
            // NapCat URL 格式通常是: https://gchat.qpic.cn/...?term=...
            // 或者 file 字段直接是 file_id
            String fileId = imageUrl;
            // 尝试提取文件名部分作为 file 参数
            int lastSlash = imageUrl.lastIndexOf('/');
            if (lastSlash >= 0) {
                String fileName = imageUrl.substring(lastSlash + 1);
                int qm = fileName.indexOf('?');
                if (qm > 0) fileName = fileName.substring(0, qm);
                if (!fileName.isEmpty()) fileId = fileName;
            }

            String resp = napcatApi.getImage(fileId);
            if (resp == null || resp.isEmpty()) return null;

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
            String file = sair.aiagent.onebot.util.JsonUtil.extractString(resp, "file");
            if (file != null && !file.isEmpty()) {
                return "data:image/jpeg;base64," + file;
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] NapCat get_image 失败: " + e.toString());
        }
        return null;
    }
}
