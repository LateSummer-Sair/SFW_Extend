package sair.aiagent.onebot;

import java.security.MessageDigest;

/**
 * 图片工具类 —— 计算图片字节的 MD5，作为图片注释持久化的强绑定 key。
 * <p>原 OCR 识别能力已彻底移除，图片理解统一改由 DeepSeek Vision 多模态模型负责。</p>
 */
public final class ImageRecognizer {

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
}
