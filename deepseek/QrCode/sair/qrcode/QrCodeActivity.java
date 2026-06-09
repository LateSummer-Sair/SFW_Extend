package sair.qrcode;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import sair.FCM;
import sair.Pathes;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SButton;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

/**
 * QrCode 插件 —— 二维码/条码生成与识别。
 *
 * <h3>命令列表</h3>
 * <ul>
 *   <li>{@code qr/gen [文本]} —— 生成二维码并输出到控制台（含保存按钮）</li>
 *   <li>{@code qr/gen-bar [文本]} —— 生成条形码并输出到控制台（含保存按钮）</li>
 *   <li>{@code qr/read [图片路径]} —— 识别图片中的二维码/条码</li>
 *   <li>{@code qr/save [文本] [保存路径]} —— 生成二维码并直接保存到文件</li>
 *   <li>{@code qr/save-bar [文本] [保存路径]} —— 生成条形码并直接保存到文件</li>
 * </ul>
 */
public class QrCodeActivity extends Activity {

    /** 默认二维码尺寸（像素） */
    private static final int QR_SIZE = 300;
    /** 默认条形码宽度 */
    private static final int BAR_WIDTH = 400;
    /** 默认条形码高度 */
    private static final int BAR_HEIGHT = 150;

    @Override
    public Object main(String funcName, String args) {
        switch (funcName) {
        case "gen":
            return genQrCode(args, false);
        case "gen-bar":
            return genBarCode(args, false);
        case "read":
            return readCode(args);
        case "save":
            return genQrCode(args, true);
        case "save-bar":
            return genBarCode(args, true);
        }
        return false;
    }

    /**
     * 生成二维码
     * @param args 文本内容 或 "文本 保存路径"（save模式）
     */
    private Object genQrCode(String args, boolean directSave) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.Error_Color, "请输入要生成的文本内容！用法: qr/gen [文本]");
            return false;
        }

        if (directSave) {
            return saveToFile(args, BarcodeFormat.QR_CODE);
        }

        try {
            String text = args;
            BufferedImage image = createQrImage(text, QR_SIZE, QR_SIZE);
            printToConsole(image, BarcodeFormat.QR_CODE, "QR-" + sanitizeFileName(text), text);
            return true;
        } catch (Exception e) {
            SairCons.println(FCM.Error_Color, "生成二维码失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 生成条形码（CODE_128格式）
     */
    private Object genBarCode(String args, boolean directSave) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.Error_Color, "请输入要生成的文本内容！用法: qr/gen-bar [文本]");
            return false;
        }

        if (directSave) {
            return saveToFile(args, BarcodeFormat.CODE_128);
        }

        try {
            String text = args;
            BufferedImage image = createBarcodeImage(text, BAR_WIDTH, BAR_HEIGHT);
            printToConsole(image, BarcodeFormat.CODE_128, "BAR-" + sanitizeFileName(text), text);
            return true;
        } catch (Exception e) {
            SairCons.println(FCM.Error_Color, "生成条形码失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 保存到文件：解析 "文本 路径" 格式
     */
    private Object saveToFile(String args, BarcodeFormat format) {
        int lastSpace = args.lastIndexOf(' ');
        if (lastSpace <= 0) {
            SairCons.println(FCM.Error_Color, "用法: qr/save(-bar) [文本] [保存路径]");
            return false;
        }

        String text = args.substring(0, lastSpace).trim();
        String filePath = ToolPack.pathRepack(args.substring(lastSpace).trim())[0];

        if (text.isEmpty()) {
            SairCons.println(FCM.Error_Color, "文本内容不能为空！");
            return false;
        }

        try {
            BufferedImage image;
            if (format == BarcodeFormat.QR_CODE) {
                image = createQrImageSave(text, QR_SIZE, QR_SIZE);
            } else {
                image = createBarcodeImageSave(text, BAR_WIDTH, BAR_HEIGHT);
            }

            if (!filePath.toLowerCase().endsWith(".png")) {
                filePath = filePath + ".png";
            }

            File outFile = new File(filePath);
            ImageIO.write(image, "png", outFile);
            SairCons.println(FCM.EXECTION_pathInfo_Color, "已保存到: " + outFile.getAbsolutePath());
            return true;
        } catch (Exception e) {
            SairCons.println(FCM.Error_Color, "保存失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 识别二维码/条码
     */
    private Object readCode(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.Error_Color, "请输入图片路径！用法: qr/read [图片路径]");
            return false;
        }

        String path = ToolPack.pathRepack(args)[0];
        File file = new File(path);
        if (!file.exists()) {
            SairCons.println(FCM.Error_Color, "文件不存在: " + path);
            return false;
        }

        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                SairCons.println(FCM.Error_Color, "无法读取图片文件: " + path);
                return false;
            }

            String result = decodeImage(image);
            if (result == null) {
                SairCons.println(FCM.Error_Color, "未识别到二维码或条码内容！");
                return false;
            }

            SairCons.println(FCM.EXECTION_pathInfo_Color, "识别结果: " + result);

            // 内容以 "SFW:" 开头则去除前缀后执行命令
            if (result.startsWith("SFW:")) {
                String cmd = result.substring(4).trim();
                if (cmd.length() > 0) {
                    SairCons.println(FCM.EXECTION_help_Color, "检测到 SFW: 前缀，执行命令: " + cmd);
                    SairCons.runner(false, cmd);
                }
            }

            return result;
        } catch (Exception e) {
            SairCons.println(FCM.Error_Color, "识别失败: " + e.getMessage());
            return false;
        }
    }

    // ==================== 图像生成 ====================

    /**
     * 创建透明背景的二维码图像
     */
    private BufferedImage createQrImage(String text, int width, int height) throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints);
        return toDisplayImage(bitMatrix);
    }

    /**
     * 生成用于保存到文件的二维码（透明底）
     */
    private BufferedImage createQrImageSave(String text, int width, int height) throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height, hints);
        return toSaveImage(bitMatrix);
    }

    /**
     * 创建透明背景的条形码图像
     */
    private BufferedImage createBarcodeImage(String text, int width, int height) throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.CODE_128, width, height, hints);
        return toDisplayImage(bitMatrix);
    }

    /**
     * 生成用于保存到文件的条形码（透明底）
     */
    private BufferedImage createBarcodeImageSave(String text, int width, int height) throws WriterException {
        Map<EncodeHintType, Object> hints = new HashMap<EncodeHintType, Object>();
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.CODE_128, width, height, hints);
        return toSaveImage(bitMatrix);
    }

    /**
     * 将 BitMatrix 转换为白底 BufferedImage（TYPE_INT_RGB），用于控制台显示。
     * JTextPane 的 ComponentView 对 ARGB 透明通道合成支持不足，需用不透明图像。
     */
    private BufferedImage toDisplayImage(BitMatrix matrix) {
        return MatrixToImageWriter.toBufferedImage(matrix);
    }

    /**
     * 将 BitMatrix 转换为透明底 BufferedImage（TYPE_INT_ARGB），用于 PNG 文件保存。
     */
    private BufferedImage toSaveImage(BitMatrix matrix) {
        int width = matrix.getWidth();
        int height = matrix.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (matrix.get(x, y)) {
                    image.setRGB(x, y, 0xFF000000);
                } else {
                    image.setRGB(x, y, 0x00000000);
                }
            }
        }
        return image;
    }

    // ==================== 图像识别 ====================

    /**
     * 解码图片中的二维码/条码
     */
    private String decodeImage(BufferedImage image) {
        try {
            BinaryBitmap binaryBitmap = new BinaryBitmap(
                    new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            Result result = new MultiFormatReader().decode(binaryBitmap);
            return result.getText();
        } catch (NotFoundException e) {
            return null;
        }
    }

    // ==================== 控制台输出 ====================

    /**
     * 将二维码/条码图片输出到控制台，包含图片预览和保存按钮
     * 参照 MusicPlayer 的 ConsUI.toPrint() — 使用 ConsFrame.printComponent
     */
    private void printToConsole(BufferedImage image, BarcodeFormat format, String defaultFileName, String content) {
        JPanel rootPanel = new JPanel(new BorderLayout(5, 5));
        rootPanel.setOpaque(false);

        // 用 JLabel 承载图片并设置明确尺寸，确保在 JTextPane 中可见
        ImageIcon icon = new ImageIcon(image);
        JLabel imageLabel = new JLabel(icon);
        imageLabel.setOpaque(false);
        imageLabel.setHorizontalAlignment(JLabel.CENTER);
        int imgW = image.getWidth();
        int imgH = image.getHeight();
        imageLabel.setPreferredSize(new Dimension(imgW, imgH));
        imageLabel.setMinimumSize(new Dimension(imgW, imgH));
        imageLabel.setMaximumSize(new Dimension(imgW, imgH));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        btnPanel.setOpaque(false);

        SButton saveBtn = new SButton("保存图片");
        saveBtn.setForeground(FCM.EXECTION_pathInfo_Color);
        saveBtn.setFont(ConsFrame.font);

        saveBtn.addActionListener(e -> {
            // 保存到 SFW data 目录下的默认路径，生成透明底 PNG
            String saveDir = this.getDataDir();
            File saveFile = new File(saveDir, defaultFileName + ".png");
            try {
                BufferedImage saveImage;
                if (format == BarcodeFormat.QR_CODE) {
                    saveImage = createQrImageSave(content, image.getWidth(), image.getHeight());
                } else {
                    saveImage = createBarcodeImageSave(content, image.getWidth(), image.getHeight());
                }
                ImageIO.write(saveImage, "png", saveFile);
                SairCons.println(FCM.EXECTION_pathInfo_Color, "已保存到: " + saveFile.getAbsolutePath());
            } catch (Exception ex) {
                SairCons.println(FCM.Error_Color, "保存失败: " + ex.getMessage());
            }
        });

        btnPanel.add(saveBtn);
        rootPanel.add(imageLabel, BorderLayout.CENTER);
        rootPanel.add(btnPanel, BorderLayout.SOUTH);

        // 设置根面板总体尺寸：图片高度 + 按钮区域
        int btnH = (int) (saveBtn.getPreferredSize().getHeight()) + 10;
        rootPanel.setPreferredSize(new Dimension(imgW + 20, imgH + btnH));

        SairCons.println(FCM.EXECTION_help_Color, "已生成: " + content);
        ConsFrame.printComponent(rootPanel);
    }

    /**
     * 生成安全的文件名（去除特殊字符，限制长度）
     */
    private String sanitizeFileName(String text) {
        if (text == null || text.isEmpty()) {
            return "qrcode";
        }
        String safe = text.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
        if (safe.length() > 30) {
            safe = safe.substring(0, 30);
        }
        return safe;
    }

    @Override
    public String[] help() {
        String name = this.getName();
        return new String[] {
            Pathes.printSplit,
            "QrCode 二维码/条码工具 v1.0",
            Pathes.printSplit,
            name + "/gen [文本] —— 生成二维码并输出到控制台",
            name + "/gen-bar [文本] —— 生成条形码并输出到控制台",
            "\t生成的图片下方有保存按钮，可保存为 PNG 文件",
            name + "/read [图片路径] —— 识别图片中的二维码/条码",
            "\t如果识别内容以 SFW: 开头，将自动执行后续命令",
            name + "/save [文本] [保存路径] —— 生成二维码并保存到文件",
            name + "/save-bar [文本] [保存路径] —— 生成条形码并保存到文件",
            Pathes.printSplit,
        };
    }

    @Override
    public void exit() {
    }
}
