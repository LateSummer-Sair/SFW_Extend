package sair.gomoku.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionListener;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import sair.gomoku.ai.GomokuAI;
import sair.gomoku.game.Board;

/**
 * 游戏对战面板 — 棋盘 + 状态标签 + 按钮(准备/重来) + 聊天，游戏进行时使用
 */
public class GameBoardPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private GamePanel gamePanel;
    private ChatPanel chatPanel;
    private JLabel statusLabel;
    private JButton btnReady, btnRestart, btnBack;
    private boolean onlineMode = false;

    public GameBoardPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBackground(new Color(50, 50, 50));

        // === 状态栏 ===
        JPanel statusPanel = new JPanel();
        statusPanel.setOpaque(false);
        statusPanel.setMaximumSize(new Dimension(600, 30));
        statusLabel = new JLabel(" ");
        statusLabel.setForeground(Color.YELLOW);
        statusLabel.setFont(new Font("微软雅黑", Font.BOLD, 14));
        statusPanel.add(statusLabel);

        // === 棋盘 ===
        gamePanel = new GamePanel();
        gamePanel.setAlignmentX(Component.CENTER_ALIGNMENT);

        // === 按钮栏 ===
        JPanel btnPanel = new JPanel();
        btnPanel.setOpaque(false);
        btnPanel.setLayout(new BoxLayout(btnPanel, BoxLayout.X_AXIS));
        btnPanel.setAlignmentX(CENTER_ALIGNMENT);
        btnPanel.setMaximumSize(new Dimension(600, 35));

        btnReady = makeBtn("准备");
        btnReady.setVisible(false);

        btnRestart = makeBtn("重来");
        btnBack = new JButton("返回主菜单");
        btnBack.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btnBack.setMaximumSize(new Dimension(110, 30));
        btnBack.setBackground(new Color(100, 50, 50));
        btnBack.setForeground(Color.WHITE);
        btnBack.setFocusPainted(false);

        btnPanel.add(btnReady);
        btnPanel.add(Box.createHorizontalStrut(5));
        btnPanel.add(btnRestart);
        btnPanel.add(Box.createHorizontalStrut(15));
        btnPanel.add(btnBack);

        // === 聊天面板 ===
        chatPanel = new ChatPanel();
        chatPanel.setAlignmentX(Component.CENTER_ALIGNMENT);
        chatPanel.setMaximumSize(new Dimension(600, 120));

        add(statusPanel);
        add(Box.createVerticalStrut(3));
        add(gamePanel);
        add(Box.createVerticalStrut(3));
        add(btnPanel);
        add(Box.createVerticalStrut(3));
        add(chatPanel);
    }

    private JButton makeBtn(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        btn.setMaximumSize(new Dimension(80, 30));
        btn.setBackground(new Color(70, 70, 70));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        return btn;
    }

    public GamePanel getGamePanel() { return gamePanel; }
    public ChatPanel getChatPanel() { return chatPanel; }

    public void setStatus(String text) { statusLabel.setText(text); }

    public void setOnlineMode(boolean online) {
        this.onlineMode = online;
        btnReady.setVisible(online);
    }

    public JButton getReadyButton() { return btnReady; }
    public JButton getRestartButton() { return btnRestart; }

    public void setOnReady(ActionListener l) { btnReady.addActionListener(l); }
    public void setOnRestart(ActionListener l) { btnRestart.addActionListener(l); }
    public void setOnBack(ActionListener l) { btnBack.addActionListener(l); }

    public void setChatListener(ChatPanel.ChatListener l) { chatPanel.setChatListener(l); }
}
