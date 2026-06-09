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
import javax.swing.JTextField;

/**
 * 加入房间面板 — 输入对方IP/端口/连接码
 */
public class JoinRoomPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private JLabel titleLabel, statusLabel;
    private JTextField ipField, portField, codeField;
    private JButton btnConnect, btnBack;
    private JPanel inputLine1, inputLine2, inputLine3;

    public JoinRoomPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(55, 40, 20));
        setPreferredSize(new Dimension(420, 280));

        titleLabel = new JLabel("加入房间");
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 24));
        titleLabel.setForeground(new Color(255, 180, 100));
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);

        JLabel hintLabel = new JLabel("输入房主提供的房间信息");
        hintLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        hintLabel.setForeground(new Color(200, 160, 120));
        hintLabel.setAlignmentX(CENTER_ALIGNMENT);

        // IP 输入行
        inputLine1 = makeInputLine("对方IP:", ipField = new JTextField("127.0.0.1"));
        // 端口输入行
        inputLine2 = makeInputLine("端口号:", portField = new JTextField("8063"));
        // 连接码输入行
        inputLine3 = makeInputLine("连接码:", codeField = new JTextField(""));

        // 状态
        statusLabel = new JLabel(" ");
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        statusLabel.setForeground(new Color(255, 180, 100));
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);

        // 按钮行
        JPanel btnPanel = new JPanel();
        btnPanel.setOpaque(false);
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.X_AXIS));
        btnPanel.setAlignmentX(CENTER_ALIGNMENT);

        btnBack = new JButton("返回");
        btnBack.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        btnBack.setMaximumSize(new Dimension(100, 35));
        btnBack.setBackground(new Color(100, 50, 50));
        btnBack.setForeground(Color.WHITE);
        btnBack.setFocusPainted(false);

        btnConnect = new JButton("连接");
        btnConnect.setFont(new Font("微软雅黑", Font.BOLD, 14));
        btnConnect.setMaximumSize(new Dimension(120, 35));
        btnConnect.setBackground(new Color(50, 100, 50));
        btnConnect.setForeground(Color.WHITE);
        btnConnect.setFocusPainted(false);

        btnPanel.add(btnBack);
        btnPanel.add(Box.createHorizontalStrut(30));
        btnPanel.add(btnConnect);

        add(Box.createVerticalStrut(18));
        add(titleLabel);
        add(Box.createVerticalStrut(3));
        add(hintLabel);
        add(Box.createVerticalStrut(18));
        add(inputLine1);
        add(Box.createVerticalStrut(8));
        add(inputLine2);
        add(Box.createVerticalStrut(8));
        add(inputLine3);
        add(Box.createVerticalStrut(10));
        add(statusLabel);
        add(Box.createVerticalStrut(10));
        add(btnPanel);
    }

    private JPanel makeInputLine(String labelText, JTextField field) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        panel.setAlignmentX(CENTER_ALIGNMENT);
        panel.setMaximumSize(new Dimension(300, 30));

        JLabel label = new JLabel(labelText);
        label.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        label.setForeground(Color.WHITE);
        label.setPreferredSize(new Dimension(70, 25));

        field.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        field.setMaximumSize(new Dimension(200, 28));
        field.setBackground(new Color(80, 80, 80));
        field.setForeground(Color.WHITE);
        field.setCaretColor(Color.WHITE);

        panel.add(label);
        panel.add(Box.createHorizontalStrut(5));
        panel.add(field);

        return panel;
    }

    public String getIP() { return ipField.getText().trim(); }
    public int getPort() {
        try { return Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { return -1; }
    }
    public String getCode() { return codeField.getText().trim(); }

    public void setStatus(String status) { statusLabel.setText(status); }

    public void setOnConnect(ActionListener l) { btnConnect.addActionListener(l); }
    public void setOnBack(ActionListener l) { btnBack.addActionListener(l); }
}
