package sair.aiagent.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

/**
 * 系统命令执行面板 —— 嵌入SFW控制台的JPanel控件。
 * <p>
 * 通过 {@code ConsFrame.printComponent()} 渲染到控制台，
 * 包含一个只读文本框，实时显示系统命令及其输出。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * SysConsolePanel panel = new SysConsolePanel("dir C:\\");
 * ConsFrame.printComponent(panel);
 * panel.appendLine(" 驱动器 C 中的卷是 Windows");
 * panel.appendLine(" 卷的序列号是 XXXX-XXXX");
 * panel.setFinished();
 * }</pre>
 */
public class SysConsolePanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 默认面板宽度 */
    private static final int PANEL_WIDTH = 680;

    /** 默认面板高度 */
    private static final int PANEL_HEIGHT = 260;

    /** 输出文本区 */
    private final JTextArea outputArea;

    /** 标题标签 */
    private final JLabel headerLabel;

    /** 状态标签 */
    private final JLabel statusLabel;

    /**
     * 构造系统命令面板。
     *
     * @param command 要执行的系统命令（显示在面板标题）
     */
    public SysConsolePanel(String command) {
        setLayout(new BorderLayout(0, 4));
        setBackground(Color.BLACK);
        setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80), 1));
        setPreferredSize(new Dimension(PANEL_WIDTH, PANEL_HEIGHT));

        // ---- 顶部标题栏 ----
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(30, 30, 30));
        headerPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        headerLabel = new JLabel("系统命令: " + command);
        headerLabel.setForeground(new Color(100, 255, 180));
        headerLabel.setFont(new Font("Consolas", Font.BOLD, 13));
        headerPanel.add(headerLabel, BorderLayout.WEST);

        statusLabel = new JLabel("\u25B6 执行中...");
        statusLabel.setForeground(new Color(255, 200, 100));
        statusLabel.setFont(new Font("Consolas", Font.PLAIN, 12));
        headerPanel.add(statusLabel, BorderLayout.EAST);

        add(headerPanel, BorderLayout.NORTH);

        // ---- 中间输出区域 ----
        outputArea = new JTextArea();
        outputArea.setEditable(false);
        outputArea.setBackground(Color.BLACK);
        outputArea.setForeground(new Color(200, 200, 200));
        outputArea.setCaretColor(Color.WHITE);
        outputArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        outputArea.setLineWrap(false);
        outputArea.setWrapStyleWord(false);

        JScrollPane scrollPane = new JScrollPane(outputArea);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getVerticalScrollBar().setBackground(Color.BLACK);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);

        add(scrollPane, BorderLayout.CENTER);
    }

    /**
     * 追加一行文本到输出区（自动添加换行）。
     *
     * @param line 要追加的文本行
     */
    public void appendLine(final String line) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                outputArea.append(line);
                outputArea.append("\n");
                // 自动滚动到底部
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
            }
        });
    }

    /**
     * 追加原始文本（不添加换行）。
     *
     * @param text 要追加的文本
     */
    public void appendRaw(final String text) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                outputArea.append(text);
                outputArea.setCaretPosition(outputArea.getDocument().getLength());
            }
        });
    }

    /**
     * 标记命令执行完成。
     */
    public void setFinished() {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText("\u2714 完成");
                statusLabel.setForeground(new Color(100, 255, 100));
            }
        });
    }

    /**
     * 标记命令执行失败/超时。
     *
     * @param reason 失败原因
     */
    public void setError(final String reason) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                statusLabel.setText("\u2718 " + reason);
                statusLabel.setForeground(new Color(255, 100, 100));
            }
        });
    }
}
