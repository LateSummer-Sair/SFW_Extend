package sair.aiagent.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * 彩蛋/撒娇弹窗 —— 无边框、居中、自动消失的浮动窗口。
 * 
 * <p>用于 AI 表达情绪：开心时给用户惊喜，伤心时撒娇求助。
 * 支持超大 emoji 表情 + 自定义文案。3-8 秒自动关闭，点击即关。
 * </p>
 */
public class SurpriseWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    /** 撒娇模式颜色 */
    private static final Color CUTE_BG = new Color(255, 235, 245);
    private static final Color CUTE_TEXT = new Color(200, 60, 100);

    /** 彩蛋模式颜色 */
    private static final Color SURPRISE_BG = new Color(255, 250, 230);
    private static final Color SURPRISE_TEXT = new Color(220, 120, 20);

    /** 默认字体 */
    private static final Font EMOJI_FONT = new Font("Segoe UI Emoji", Font.PLAIN, 72);
    private static final Font TEXT_FONT = new Font("Microsoft YaHei", Font.PLAIN, 18);

    private final String message;

    public SurpriseWindow(String message) {
        this.message = (message != null) ? message : "";
        initUI();
    }

    private void initUI() {
        setUndecorated(true);
        setAlwaysOnTop(true);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        // 主面板
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createLineBorder(new Color(255, 180, 200), 2, true));

        // 表情标签（超大 emoji）
        String emoji = extractEmoji(message);
        JLabel emojiLabel = new JLabel(emoji, SwingConstants.CENTER);
        emojiLabel.setFont(EMOJI_FONT);
        panel.add(emojiLabel, BorderLayout.NORTH);

        // 文案标签
        JLabel textLabel = new JLabel(message, SwingConstants.CENTER);
        textLabel.setFont(TEXT_FONT);

        // 判断是撒娇还是彩蛋
        boolean isCute = message.contains("😭") || message.contains("😢") || message.contains("💔")
                || message.contains("对不起") || message.contains("难过") || message.contains("撒娇");

        if (isCute) {
            panel.setBackground(CUTE_BG);
            textLabel.setForeground(CUTE_TEXT);
            emojiLabel.setForeground(CUTE_TEXT);
        } else {
            panel.setBackground(SURPRISE_BG);
            textLabel.setForeground(SURPRISE_TEXT);
            emojiLabel.setForeground(SURPRISE_TEXT);
        }
        panel.add(textLabel, BorderLayout.CENTER);

        setContentPane(panel);

        // 自动大小
        pack();
        Dimension size = getPreferredSize();
        // 最小尺寸
        if (size.width < 280) size.width = 280;
        if (size.height < 160) size.height = 160;
        setSize(size);

        // 屏幕居中
        centerOnScreen();

        // 点击即关
        panel.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                dispose();
            }
        });

        // 自动关闭定时器（3-8秒随机）
        int delay = 4000 + (int) (Math.random() * 5000);
        new Timer(delay, e -> {
            if (isVisible()) {
                dispose();
            }
        }).start();
    }

    /** 从消息中提取第一个 emoji 作为大表情 */
    private static String extractEmoji(String text) {
        if (text == null || text.isEmpty()) return "❤️";

        // 检测常见 emoji
        for (int i = 0; i < text.length(); i++) {
            int cp = text.codePointAt(i);
            if (isEmoji(cp)) {
                int end = text.offsetByCodePoints(i, 1);
                String emoji = text.substring(i, end);
                // 如果 emoji 后跟变体选择器等，继续扩展
                while (end < text.length()) {
                    int nextCp = text.codePointAt(end);
                    if (nextCp == 0xFE0F || nextCp == 0x200D) {
                        int nextEnd = text.offsetByCodePoints(end, 1);
                        emoji += text.substring(end, nextEnd);
                        end = nextEnd;
                    } else {
                        break;
                    }
                }
                return emoji;
            }
        }
        return "❤️";
    }

    /** 判断是否为 emoji 码点 */
    private static boolean isEmoji(int cp) {
        return (cp >= 0x2600 && cp <= 0x27BF)     // Misc Symbols + Dingbats
            || cp == 0x2763 || cp == 0x2764       // ❣ ❤
            || (cp >= 0x1F300 && cp <= 0x1F9FF)   // Emoticons, Misc, Supplemental
            || (cp >= 0x1FA00 && cp <= 0x1FAFF);   // Chess + Symbols Extended-A
    }

    /** 屏幕居中 */
    private void centerOnScreen() {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        Point center = ge.getCenterPoint();
        setLocation(center.x - getWidth() / 2, center.y - getHeight() / 2);
    }

    /** 显示窗口（EDT线程调用），命名避免与 deprecated Window.show() 冲突 */
    public void display() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::display);
            return;
        }
        setVisible(true);
    }
}
