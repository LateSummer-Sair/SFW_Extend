package sair.aiagent.onebot;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Base64;

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
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String dataUri = "data:" + mimeType + ";base64," + base64;
            AiAgentActivity.debugLog("[QQMsg] 图片下载成功: " + totalBytes + " bytes -> base64 length " + base64.length());
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
     * 解析图片 URL 为 Vision API 可用格式。
     * 策略：优先使用原始 URL（DeepSeek 可直接访问公网 URL），
     * 若 URL 看起来像腾讯内网地址则下载转 base64。
     *
     * @param imageUrl 原始图片 URL
     * @return Vision API 可用的图片 URL 或 base64 data URI，失败返回 null
     */
    public static String resolveImageForVision(String imageUrl) {
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
            AiAgentActivity.debugLog("[QQMsg] 图片base64转换失败，尝试直接传URL");
        }

        // 公网 URL 或 base64 失败兜底：直接传原始 URL
        return imageUrl;
    }
}
