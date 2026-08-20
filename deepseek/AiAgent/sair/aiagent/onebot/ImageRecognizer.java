package sair.aiagent.onebot;

import java.security.MessageDigest;

import sair.aiagent.core.PersistenceManager;
import sair.aiagent.core.RedisClient;
import sair.aiagent.util.QrCodeDecoder;

/**
 * 图片本地识别汇总 —— 二维码识别 + OCR 文字识别。
 * <p>在 QQ 图片传给 AI 之前，对图片做本地识别，把二维码内容和 OCR 文字
 * 格式化为一段文本，供注入到任务描述中，增强 AI 对图片的理解。</p>
 */
public final class ImageRecognizer {

    private ImageRecognizer() {} // 纯静态工具类

    /** 持久化管理器（由 AiAgentActivity.init 注入），用于图片注释的持久化与 AI 修改。 */
    private static volatile PersistenceManager persistenceManager;

    /** 注入持久化管理器（可重复设置）。 */
    public static void setPersistenceManager(PersistenceManager pm) {
        persistenceManager = pm;
    }

    /**
     * 识别图片（二维码 + OCR），返回格式化文本。
     *
     * @param imageBytes 图片字节
     * @return 识别结果文本（含二维码内容 / OCR 文字），无任何识别结果时返回 null
     */
    public static String recognize(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;

        StringBuilder sb = new StringBuilder();

        // 二维码
        String qr = QrCodeDecoder.decode(imageBytes);
        if (qr != null && !qr.trim().isEmpty()) {
            sb.append("二维码内容: ").append(qr.trim()).append("\n");
        }

        // OCR
        String ocr = sair.aiagent.ocr.OcrManager.getInstance().recognize(imageBytes);
        if (ocr != null && !ocr.trim().isEmpty()) {
            sb.append("图片文字(OCR): ").append(ocr.trim()).append("\n");
        }

        if (sb.length() == 0) return null;
        return sb.toString();
    }

    /**
     * 为单张图片生成结构化备注（二维码 + OCR 文字）。
     * <p>无论是否识别到内容，都会返回带标签的备注文本，识别不到的部分用「无」占位，
     * 保证每张图片在任务描述中都有可供 AI 参考的备注信息。</p>
     *
     * @param imageBytes 图片字节；为 null 或空表示图片不可用
     * @return 备注文本，如 "二维码: 无 | OCR文字: 你好"
     */
    public static String buildRemark(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return "二维码: 无 | OCR文字: 无（图片下载失败）";
        }

        String imageMd5 = md5(imageBytes);
        PersistenceManager pm = persistenceManager;

        // 1. 持久化注释优先：AI 可修改，强绑定 MD5，随图进入长期存储
        if (pm != null) {
            String persisted = pm.getImageRemark(imageMd5);
            if (persisted != null && !persisted.isEmpty()) {
                return persisted;
            }
        }

        // 2. Redis 旁路缓存：同一张图识别结果不变，命中直接返回（Redis 未运行则静默降级）
        String cacheKey = "imgremark:" + imageMd5;
        RedisClient redis = RedisClient.getInstance();
        String cached = redis != null ? redis.get(cacheKey) : null;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        // 3. OCR/二维码识别生成
        String qr = QrCodeDecoder.decode(imageBytes);
        String ocr = sair.aiagent.ocr.OcrManager.getInstance().recognize(imageBytes);
        String qrText = (qr != null && !qr.trim().isEmpty()) ? qr.trim() : "无";
        String ocrText = (ocr != null && !ocr.trim().isEmpty()) ? ocr.trim() : "无";
        String result = "二维码: " + qrText + " | OCR文字: " + ocrText;

        // 4. 写入持久化（source=ocr）+ Redis 缓存（TTL 7 天）
        if (pm != null) {
            pm.setImageRemark(imageMd5, result, "ocr");
        }
        if (redis != null) {
            redis.setex(cacheKey, 604800, result);
        }
        return result;
    }

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
