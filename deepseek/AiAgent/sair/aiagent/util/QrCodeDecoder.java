package sair.aiagent.util;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import javax.imageio.ImageIO;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;

/**
 * 二维码解码工具 —— 基于 ZXing（纯 Java，零 native 依赖）。
 * <p>用于在 QQ 图片传给 AI / 存表情库之前，识别图片中是否包含二维码及二维码内容。</p>
 */
public final class QrCodeDecoder {

    private QrCodeDecoder() {} // 纯静态工具类

    /**
     * 从 BufferedImage 解码二维码。
     *
     * @param image 待识别的图片
     * @return 二维码内容文本；未识别到二维码返回 null
     */
    public static String decode(BufferedImage image) {
        if (image == null) return null;
        try {
            BinaryBitmap bitmap = new BinaryBitmap(
                    new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            Result result = new MultiFormatReader().decode(bitmap);
            return result != null ? result.getText() : null;
        } catch (NotFoundException e) {
            return null; // 图片中没有二维码
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从图片字节解码二维码。
     *
     * @param imageBytes 图片字节（jpg/png/webp 等）
     * @return 二维码内容文本；未识别到二维码返回 null
     */
    public static String decode(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            return decode(img);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 判断图片中是否包含二维码。
     *
     * @param imageBytes 图片字节
     * @return true 表示包含二维码
     */
    public static boolean hasQrCode(byte[] imageBytes) {
        return decode(imageBytes) != null;
    }
}
