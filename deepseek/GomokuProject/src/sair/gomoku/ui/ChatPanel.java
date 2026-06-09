package sair.gomoku.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/**
 * 聊天面板（棋盘下方的聊天窗口）
 */
public class ChatPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private JTextArea chatArea;
    private JTextField inputField;
    private ChatListener listener;

    public interface ChatListener {
        void onSendMessage(String message);
    }

    public ChatPanel() {
        setLayout(new BorderLayout(5, 5));
        setPreferredSize(new Dimension(560, 120));
        setOpaque(true);
        setBackground(new Color(240, 240, 240));

        // 聊天消息显示区
        chatArea = new JTextArea();
        chatArea.setEditable(false);
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);
        chatArea.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        chatArea.setBackground(new Color(250, 250, 250));
        JScrollPane scrollPane = new JScrollPane(chatArea);
        scrollPane.setPreferredSize(new Dimension(560, 90));

        // 输入框
        inputField = new JTextField();
        inputField.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        inputField.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                sendMessage();
            }
        });

        add(scrollPane, BorderLayout.CENTER);
        add(inputField, BorderLayout.SOUTH);
    }

    public void setChatListener(ChatListener listener) {
        this.listener = listener;
    }

    private void sendMessage() {
        String msg = inputField.getText().trim();
        if (!msg.isEmpty()) {
            appendMessage("我: " + msg);
            inputField.setText("");
            if (listener != null) {
                listener.onSendMessage(msg);
            }
        }
    }

    /**
     * 追加消息到聊天区
     */
    public void appendMessage(String message) {
        SwingUtilities.invokeLater(() -> {
            chatArea.append(message + "\n");
            chatArea.setCaretPosition(chatArea.getDocument().getLength());
        });
    }

    /**
     * 清空聊天记录
     */
    public void clear() {
        chatArea.setText("");
    }

    /**
     * 获取输入框焦点
     */
    public void focusInput() {
        inputField.requestFocusInWindow();
    }

    public JTextField getInputField() {
        return inputField;
    }
}
