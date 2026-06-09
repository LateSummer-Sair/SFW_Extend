package sair.gomoku.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionListener;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 模式选择面板 — 第一步：选择单人/双人/联机
 */
public class ModeSelectPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private JButton btnSingle, btnDual, btnOnline;
    private JLabel titleLabel;

    public ModeSelectPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(30, 40, 60));
        setPreferredSize(new Dimension(420, 300));

        // 标题
        titleLabel = new JLabel("五子棋");
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 28));
        titleLabel.setForeground(new Color(255, 200, 80));
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);

        JLabel subLabel = new JLabel("—— 选择游戏模式 ——");
        subLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        subLabel.setForeground(new Color(180, 200, 220));
        subLabel.setAlignmentX(CENTER_ALIGNMENT);

        // 按钮
        btnSingle = makeButton("🎮  单人模式（与电脑对弈）", new Color(60, 100, 50));
        btnDual   = makeButton("👥  双人本地对弈", new Color(80, 60, 120));
        btnOnline = makeButton("🌐  多人联机", new Color(50, 80, 130));

        add(Box.createVerticalStrut(25));
        add(titleLabel);
        add(Box.createVerticalStrut(5));
        add(subLabel);
        add(Box.createVerticalStrut(25));
        add(btnSingle);
        add(Box.createVerticalStrut(10));
        add(btnDual);
        add(Box.createVerticalStrut(10));
        add(btnOnline);
    }

    private JButton makeButton(String text, Color bgColor) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.BOLD, 15));
        btn.setAlignmentX(CENTER_ALIGNMENT);
        btn.setMaximumSize(new Dimension(320, 50));
        btn.setBackground(bgColor);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        return btn;
    }

    public void setOnSingle(ActionListener l) { btnSingle.addActionListener(l); }
    public void setOnDual(ActionListener l) { btnDual.addActionListener(l); }
    public void setOnOnline(ActionListener l) { btnOnline.addActionListener(l); }
}
