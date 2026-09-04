package sair.aiagent.onebot;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片工具类 —— 计算图片字节的 MD5，作为图片注释持久化的强绑定 key。
 * <p>原 OCR 识别能力已彻底移除，图片理解统一改由 DeepSeek Vision 多模态模型负责。</p>
 */
public final class ImageRecognizer {

    /** 视觉识别结果缓存，有界 LRU（access-order），防止长期运行无限增长。 */
    private static final int VISION_CACHE_MAX = 1000;
    private static final Map<String, String> VISION_RESULT_CACHE = Collections.synchronizedMap(
        new LinkedHashMap<String, String>(256, 0.75f, true) {
            private static final long serialVersionUID = 1L;
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > VISION_CACHE_MAX;
            }
        });

    private ImageRecognizer() {} // 纯静态工具类

    /** 计算图片字节的 MD5，作为缓存 key（公开供图片注释工具复用）。 */
    public static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 降级：用长度 + 哈希码作为近似 key
            return data.length + "_" + java.util.Arrays.hashCode(data);
        }
    }

    /** 为视觉识别结果生成缓存 key：base64 按内容 MD5，file_id 和 URL 按稳定字符串。 */
    public static String visionCacheKey(String resolvedImage) {
        if (resolvedImage == null || resolvedImage.isEmpty()) return null;
        if (resolvedImage.startsWith("data:")) {
            try {
                int comma = resolvedImage.indexOf(',');
                if (comma >= 0) {
                    byte[] bytes = Base64.getDecoder().decode(resolvedImage.substring(comma + 1));
                    return "md5:" + md5(bytes);
                }
            } catch (Exception ignored) {}
        }
        return "raw:" + resolvedImage;
    }

    /** 读取视觉识别结果缓存。 */
    public static String getCachedVisionResult(String key) {
        return key == null ? null : VISION_RESULT_CACHE.get(key);
    }

    /** 写入视觉识别结果缓存。 */
    public static void putCachedVisionResult(String key, String result) {
        if (key == null || result == null || result.trim().isEmpty()) return;
        VISION_RESULT_CACHE.put(key, result.trim());
    }
}
