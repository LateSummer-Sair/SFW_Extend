package sair.gomoku.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionListener;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 难度选择面板 — 单人模式：选择难度和棋子颜色
 */
public class DifficultySelectPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private JButton btnEasy, btnMedium, btnHard, btnBlack, btnWhite, btnStart, btnBack;
    private JLabel titleLabel, diffLabel, colorLabel, infoLabel;
    private int selectedDifficulty = 1; // 默认中级
    private int selectedColor = 1; // 默认黑棋

    public DifficultySelectPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(35, 55, 40));
        setPreferredSize(new Dimension(420, 320));

        titleLabel = new JLabel("单人模式 — 选择难度");
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 22));
        titleLabel.setForeground(new Color(144, 238, 144));
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);

        JLabel hintLabel = new JLabel("与电脑AI五子棋对弈");
        hintLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        hintLabel.setForeground(new Color(150, 200, 150));
        hintLabel.setAlignmentX(CENTER_ALIGNMENT);

        // ===== 难度选择 =====
        diffLabel = new JLabel("AI难度：中级");
        diffLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        diffLabel.setForeground(new Color(255, 255, 150));
        diffLabel.setAlignmentX(CENTER_ALIGNMENT);

        JPanel diffPanel = new JPanel();
        diffPanel.setOpaque(false);
        diffPanel.setLayout(new BoxLayout(diffPanel, BoxLayout.X_AXIS));
        diffPanel.setAlignmentX(CENTER_ALIGNMENT);

        btnEasy = makeSmallButton("初级");
        btnMedium = makeSmallButton("中级");
        btnHard = makeSmallButton("高级");

        btnEasy.addActionListener(e -> { selectedDifficulty = 0; diffLabel.setText("AI难度：初级"); });
        btnMedium.addActionListener(e -> { selectedDifficulty = 1; diffLabel.setText("AI难度：中级"); });
        btnHard.addActionListener(e -> { selectedDifficulty = 2; diffLabel.setText("AI难度：高级"); });

        diffPanel.add(btnEasy);
        diffPanel.add(Box.createHorizontalStrut(10));
        diffPanel.add(btnMedium);
        diffPanel.add(Box.createHorizontalStrut(10));
        diffPanel.add(btnHard);

        // ===== 棋子颜色选择 =====
        colorLabel = new JLabel("执子：黑棋（先手）");
        colorLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        colorLabel.setForeground(new Color(255, 255, 150));
        colorLabel.setAlignmentX(CENTER_ALIGNMENT);

        JPanel colorPanel = new JPanel();
        colorPanel.setOpaque(false);
        colorPanel.setLayout(new BoxLayout(colorPanel, BoxLayout.X_AXIS));
        colorPanel.setAlignmentX(CENTER_ALIGNMENT);

        btnBlack = makeSmallButton("执黑先手");
        btnWhite = makeSmallButton("执白后手");

        btnBlack.addActionListener(e -> { selectedColor = 1; colorLabel.setText("执子：黑棋（先手）"); });
        btnWhite.addActionListener(e -> { selectedColor = 2; colorLabel.setText("执子：白棋（后手）"); });

        colorPanel.add(btnBlack);
        colorPanel.add(Box.createHorizontalStrut(10));
        colorPanel.add(btnWhite);

        // ===== 按钮 =====
        JPanel btnPanel = new JPanel();
        btnPanel.setOpaque(false);
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.X_AXIS));
        btnPanel.setAlignmentX(CENTER_ALIGNMENT);

        btnBack = makeSmallButton("返回");
        btnBack.setBackground(new Color(100, 50, 50));
        btnStart = makeSmallButton("开始游戏");
        btnStart.setBackground(new Color(50, 100, 50));

        btnPanel.add(btnBack);
        btnPanel.add(Box.createHorizontalStrut(20));
        btnPanel.add(btnStart);

        add(Box.createVerticalStrut(18));
        add(titleLabel);
        add(Box.createVerticalStrut(5));
        add(hintLabel);
        add(Box.createVerticalStrut(20));
        add(diffLabel);
        add(Box.createVerticalStrut(10));
        add(diffPanel);
        add(Box.createVerticalStrut(15));
        add(colorLabel);
        add(Box.createVerticalStrut(10));
        add(colorPanel);
        add(Box.createVerticalStrut(20));
        add(btnPanel);
    }

    private JButton makeSmallButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        btn.setMaximumSize(new Dimension(120, 35));
        btn.setBackground(new Color(70, 70, 70));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        return btn;
    }

    public int getSelectedDifficulty() { return selectedDifficulty; }
    public int getSelectedColor() { return selectedColor; }

    public void setOnStart(ActionListener l) { btnStart.addActionListener(l); }
    public void setOnBack(ActionListener l) { btnBack.addActionListener(l); }
}
