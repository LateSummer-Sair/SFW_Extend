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
 * 联机选择面板 — 创建房间 或 加入房间
 */
public class OnlineSelectPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private JButton btnCreate, btnJoin, btnBack;
    private JLabel titleLabel;

    public OnlineSelectPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(25, 40, 65));
        setPreferredSize(new Dimension(420, 270));

        titleLabel = new JLabel("多人联机");
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 24));
        titleLabel.setForeground(new Color(135, 206, 250));
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);

        JLabel hintLabel = new JLabel("创建房间等待对手，或连接到已有房间");
        hintLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        hintLabel.setForeground(new Color(160, 190, 220));
        hintLabel.setAlignmentX(CENTER_ALIGNMENT);

        btnCreate = makeButton("🏠  创建新房间", new Color(40, 100, 130));
        btnJoin   = makeButton("🔗  连接到已有房间", new Color(30, 80, 110));
        btnBack   = makeSmallButton("返回");

        add(Box.createVerticalStrut(22));
        add(titleLabel);
        add(Box.createVerticalStrut(5));
        add(hintLabel);
        add(Box.createVerticalStrut(22));
        add(btnCreate);
        add(Box.createVerticalStrut(12));
        add(btnJoin);
        add(Box.createVerticalStrut(20));
        add(btnBack);
    }

    private JButton makeButton(String text, Color bgColor) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.BOLD, 15));
        btn.setAlignmentX(CENTER_ALIGNMENT);
        btn.setMaximumSize(new Dimension(320, 48));
        btn.setBackground(bgColor);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        return btn;
    }

    private JButton makeSmallButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        btn.setAlignmentX(CENTER_ALIGNMENT);
        btn.setMaximumSize(new Dimension(120, 35));
        btn.setBackground(new Color(100, 50, 50));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        return btn;
    }

    public void setOnCreate(ActionListener l) { btnCreate.addActionListener(l); }
    public void setOnJoin(ActionListener l) { btnJoin.addActionListener(l); }
    public void setOnBack(ActionListener l) { btnBack.addActionListener(l); }
}
