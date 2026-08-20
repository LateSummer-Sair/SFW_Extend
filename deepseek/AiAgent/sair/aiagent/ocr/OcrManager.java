package sair.aiagent.ocr;

/**
 * OCR 模块门面 —— 对外暴露的唯一入口。
 *
 * <p>内部持有 {@link OcrEngine} 实例与 Access Key，负责 OCR 能力的开关控制。
 * 程序内部通过 {@link #recognize(byte[])} 使用 OCR，无需关心具体引擎实现。</p>
 *
 * <p>未设置 Access Key 时 {@link #isEnabled()} 返回 {@code false}，
 * {@link #recognize(byte[])} 静默返回 {@code null}，实现「全盘忽略 OCR 能力」。
 * 「未启用」的提示仅由初始化方在 SFW 控制台输出一次，本类不打印任何日志。</p>
 */
public final class OcrManager {

    private static final OcrManager INSTANCE = new OcrManager();

    private volatile OcrEngine engine;
    private volatile String accessKey = "";

    private OcrManager() {}

    public static OcrManager getInstance() {
        return INSTANCE;
    }

    /**
     * 设置 Access Key 并据此启用 / 禁用 OCR。
     * <p>Key 为空时禁用（引擎置空），否则使用默认的 {@link EasyOcrEngine}。</p>
     *
     * @param key OCR 服务的 Access Key，可为 {@code null} 或空串
     */
    public void setAccessKey(String key) {
        String k = (key == null) ? "" : key.trim();
        this.accessKey = k;
        this.engine = k.isEmpty() ? null : new EasyOcrEngine(k);
    }

    /**
     * 更换 OCR 引擎实现（可持续发展：方便以后切换其他在线 OCR 服务）。
     *
     * @param e 新的 OCR 引擎实例；为 {@code null} 表示禁用
     */
    public void setEngine(OcrEngine e) {
        this.engine = e;
    }

    /** OCR 是否启用（已设置 Access Key 且引擎可用）。 */
    public boolean isEnabled() {
        return engine != null && accessKey != null && !accessKey.isEmpty();
    }

    /**
     * 识别图片文字。
     *
     * @param imageBytes 图片字节
     * @return 识别出的文字；未启用或识别失败返回 {@code null}（静默降级）
     */
    public String recognize(byte[] imageBytes) {
        if (!isEnabled()) return null;
        try {
            return engine.recognize(imageBytes);
        } catch (Exception e) {
            return null;
        }
    }

    /** 当前引擎名称（用于日志 / 诊断）。 */
    public String getEngineName() {
        return engine == null ? "未启用" : engine.getName();
    }
}
