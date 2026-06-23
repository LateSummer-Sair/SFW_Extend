package sair.aiagent.util;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * 图片渲染工具类 —— 使用Java AWT/Graphics2D进行图片渲染。
 * <p>
 * 支持文字渲染为图片、简单图表创建等功能。
 * 输出为PNG字节流/文件，供NapCatApi发送。
 * </p>
 */
public class ImageRenderer {

    private static final int DEFAULT_WIDTH = 600;
    private static final int DEFAULT_PADDING = 20;
    private static final Color BG_COLOR = new Color(255, 255, 255);
    private static final Color TEXT_COLOR = new Color(40, 40, 40);
    private static final Color ACCENT_COLOR = new Color(70, 130, 230);
    
    // 检查是否有图形环境
    private static boolean headless = GraphicsEnvironment.isHeadless();

    /**
     * 将文字渲染为PNG图片
     * @param text 要渲染的文字
     * @param outputFile 输出文件路径
     * @return 输出文件
     * @throws IOException IO异常
     */
    public static File renderTextToImage(String text, File outputFile) throws IOException {
        return renderTextToImage(text, outputFile, DEFAULT_WIDTH, 0);
    }

    /**
     * 将文字渲染为PNG图片
     * @param text 要渲染的文字
     * @param outputFile 输出文件路径
     * @param width 图片宽度
     * @param height 图片高度（0=自动计算）
     * @return 输出文件
     * @throws IOException IO异常
     */
    public static File renderTextToImage(String text, File outputFile, int width, int height) throws IOException {
        if (headless) {
            throw new IOException("无图形环境，无法渲染图片");
        }
        
        if (text == null || text.isEmpty()) {
            throw new IOException("文字内容为空");
        }
        
        width = Math.max(width, 200);
        
        // 准备字体
        Font titleFont = new Font("Microsoft YaHei", Font.BOLD, 22);
        Font textFont = new Font("Microsoft YaHei", Font.PLAIN, 16);
        Font fallbackFont = new Font("SimHei", Font.PLAIN, 16);
        
        // 先用回退字体测量
        FontRenderContext frc = new FontRenderContext(null, true, true);
        
        // 计算自动高度
        int padding = DEFAULT_PADDING;
        int textWidth = width - padding * 2;
        
        List<String> lines = wrapText(text, textFont, textWidth, frc);
        if (height <= 0) {
            height = padding * 2 + 30 + lines.size() * 24 + padding;
        }
        height = Math.max(height, 100);
        
        // 创建图片
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        try {
            // 抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            
            // 背景
            g2d.setColor(BG_COLOR);
            g2d.fillRect(0, 0, width, height);
            
            // 顶部装饰线
            g2d.setColor(ACCENT_COLOR);
            g2d.fillRect(0, 0, width, 4);
            
            // 标题
            g2d.setColor(ACCENT_COLOR);
            g2d.setFont(titleFont);
            g2d.drawString("AI 助手", padding, padding + 22);
            
            // 分隔线
            int y = padding + 30;
            g2d.setColor(new Color(220, 220, 220));
            g2d.drawLine(padding, y, width - padding, y);
            y += 8;
            
            // 文字内容
            g2d.setColor(TEXT_COLOR);
            g2d.setFont(textFont);
            
            for (String line : lines) {
                y += 24;
                g2d.drawString(line, padding, y);
            }
            
            // 底部装饰
            g2d.setColor(ACCENT_COLOR);
            g2d.fillRect(0, height - 4, width, 4);
            
        } finally {
            g2d.dispose();
        }
        
        // 写入文件
        outputFile.getParentFile().mkdirs();
        ImageIO.write(image, "png", outputFile);
        return outputFile;
    }

    /**
     * 创建条形图
     * @param title 图表标题
     * @param labels 标签列表
     * @param values 数值列表
     * @param outputFile 输出文件
     * @return 输出文件
     * @throws IOException IO异常
     */
    public static File createBarChart(String title, List<String> labels, List<Double> values, File outputFile) throws IOException {
        if (headless) {
            throw new IOException("无图形环境，无法渲染图片");
        }
        
        int width = Math.max(500, labels.size() * 80 + 120);
        int height = 400;
        int padding = 60;
        
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();
        
        try {
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(BG_COLOR);
            g2d.fillRect(0, 0, width, height);
            
            // 标题
            g2d.setColor(ACCENT_COLOR);
            g2d.setFont(new Font("Microsoft YaHei", Font.BOLD, 18));
            g2d.drawString(title, padding, 30);
            
            // 坐标轴
            int chartLeft = padding;
            int chartRight = width - padding;
            int chartTop = 50;
            int chartBottom = height - padding;
            int chartWidth = chartRight - chartLeft;
            int chartHeight = chartBottom - chartTop;
            
            g2d.setColor(Color.BLACK);
            g2d.drawLine(chartLeft, chartTop, chartLeft, chartBottom);
            g2d.drawLine(chartLeft, chartBottom, chartRight, chartBottom);
            
            // 找最大值
            double maxVal = 1;
            for (double v : values) maxVal = Math.max(maxVal, v);
            maxVal *= 1.1;
            
            // 柱状图
            int barWidth = Math.min(60, chartWidth / labels.size() - 10);
            Font labelFont = new Font("Microsoft YaHei", Font.PLAIN, 11);
            g2d.setFont(labelFont);
            
            for (int i = 0; i < labels.size(); i++) {
                int barX = chartLeft + (i + 1) * chartWidth / (labels.size() + 1) - barWidth / 2;
                int barH = (int) (values.get(i) / maxVal * chartHeight);
                int barY = chartBottom - barH;
                
                // 渐变色柱
                g2d.setColor(new Color(70, 130, 230, 180));
                g2d.fillRect(barX, barY, barWidth, barH);
                g2d.setColor(ACCENT_COLOR);
                g2d.drawRect(barX, barY, barWidth, barH);
                
                // 数值
                String valStr = String.format("%.1f", values.get(i));
                g2d.setColor(TEXT_COLOR);
                FontMetrics fm = g2d.getFontMetrics();
                int valWidth = fm.stringWidth(valStr);
                g2d.drawString(valStr, barX + (barWidth - valWidth) / 2, barY - 5);
                
                // 标签
                String label = labels.get(i);
                if (label.length() > 6) label = label.substring(0, 5) + "..";
                int labelWidth = fm.stringWidth(label);
                // 旋转文字
                g2d.drawString(label, barX + (barWidth - labelWidth) / 2, chartBottom + 15);
            }
            
        } finally {
            g2d.dispose();
        }
        
        outputFile.getParentFile().mkdirs();
        ImageIO.write(image, "png", outputFile);
        return outputFile;
    }

    /**
     * 检查是否有图形环境
     */
    public static boolean isHeadless() {
        return headless;
    }
    
    /**
     * 文字换行计算
     */
    private static List<String> wrapText(String text, Font font, int maxWidth, FontRenderContext frc) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        
        // 中文支持：按字符换行
        String[] paragraphs = text.split("\n");
        for (String para : paragraphs) {
            if (para.isEmpty()) {
                lines.add("");
                continue;
            }
            
            java.awt.font.LineBreakMeasurer measurer = null;
            try {
                AttributedString as = new AttributedString(para);
                as.addAttribute(TextAttribute.FONT, font);
                measurer = new LineBreakMeasurer(as.getIterator(), frc);
                while (measurer.getPosition() < para.length()) {
                    TextLayout layout = measurer.nextLayout(maxWidth);
                    int start = measurer.getPosition() - layout.getCharacterCount();
                    lines.add(para.substring(start, measurer.getPosition()));
                }
            } catch (Exception e) {
                // 回退：按字符数粗略换行
                int charsPerLine = Math.max(1, maxWidth / 14); // 中文字符约14px宽
                for (int i = 0; i < para.length(); i += charsPerLine) {
                    int end = Math.min(i + charsPerLine, para.length());
                    lines.add(para.substring(i, end));
                }
            }
        }
        return lines;
    }
}
