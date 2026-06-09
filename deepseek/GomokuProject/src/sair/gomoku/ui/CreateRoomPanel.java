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
 * 创建房间面板 — 显示IP/端口/连接码，等待对手连接
 */
public class CreateRoomPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private JLabel titleLabel, ipLabel, portLabel, codeLabel, statusLabel;
    private JButton btnBack;

    public CreateRoomPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(20, 55, 55));
        setPreferredSize(new Dimension(420, 280));

        titleLabel = new JLabel("创建房间");
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 24));
        titleLabel.setForeground(new Color(100, 220, 200));
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);

        JLabel infoTitleLabel = new JLabel("—— 房间信息 ——");
        infoTitleLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        infoTitleLabel.setForeground(new Color(120, 180, 170));
        infoTitleLabel.setAlignmentX(CENTER_ALIGNMENT);

        ipLabel = new JLabel("本机IP: --");
        ipLabel.setFont(new Font("微软雅黑", Font.PLAIN, 15));
        ipLabel.setForeground(new Color(150, 255, 200));
        ipLabel.setAlignmentX(CENTER_ALIGNMENT);

        portLabel = new JLabel("端口号: --");
        portLabel.setFont(new Font("微软雅黑", Font.PLAIN, 15));
        portLabel.setForeground(new Color(150, 255, 200));
        portLabel.setAlignmentX(CENTER_ALIGNMENT);

        codeLabel = new JLabel("连接码: --");
        codeLabel.setFont(new Font("微软雅黑", Font.BOLD, 20));
        codeLabel.setForeground(new Color(255, 220, 80));
        codeLabel.setAlignmentX(CENTER_ALIGNMENT);

        statusLabel = new JLabel("等待玩家加入...（120秒超时）");
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        statusLabel.setForeground(new Color(255, 180, 100));
        statusLabel.setAlignmentX(CENTER_ALIGNMENT);

        btnBack = new JButton("关闭房间");
        btnBack.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        btnBack.setAlignmentX(CENTER_ALIGNMENT);
        btnBack.setMaximumSize(new Dimension(150, 35));
        btnBack.setBackground(new Color(100, 50, 50));
        btnBack.setForeground(Color.WHITE);
        btnBack.setFocusPainted(false);

        add(Box.createVerticalStrut(18));
        add(titleLabel);
        add(Box.createVerticalStrut(5));
        add(infoTitleLabel);
        add(Box.createVerticalStrut(15));
        add(ipLabel);
        add(Box.createVerticalStrut(8));
        add(portLabel);
        add(Box.createVerticalStrut(8));
        add(codeLabel);
        add(Box.createVerticalStrut(15));
        add(statusLabel);
        add(Box.createVerticalStrut(15));
        add(btnBack);
    }

    public void setRoomInfo(String ip, int port, String code) {
        ipLabel.setText("本机IP: " + ip);
        portLabel.setText("端口号: " + port);
        codeLabel.setText("连接码: " + code);
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public void setOnBack(ActionListener l) { btnBack.addActionListener(l); }
}
