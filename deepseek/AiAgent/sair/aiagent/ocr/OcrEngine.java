package sair.aiagent.ocr;

/**
 * OCR 引擎接口 —— 图片文字识别能力的统一抽象。
 *
 * <p>实现方可以是任意 OCR 服务（在线 API 或本地引擎）。程序内部只面向本接口编程，
 * 通过 {@link OcrManager} 注入具体实现，从而在更换 OCR 服务商时无需改动调用方代码。</p>
 */
public interface OcrEngine {

    /**
     * 识别图片中的文字。
     *
     * @param imageBytes 图片字节（jpg/png/gif/bmp/tiff/webp 等）
     * @return 识别出的文字；无文字或识别失败返回 {@code null}
     */
    String recognize(byte[] imageBytes);

    /** 引擎名称（用于日志标识与诊断）。 */
    String getName();
}
