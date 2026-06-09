package sair.scq.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;

import sair.FCM;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;

/**
 * 消息输入面板 —— 文本输入区 + 模式切换按钮 + 发送按钮。
 * 
 * <h3>模式</h3>
 * <ul>
 *   <li>消息模式（默认）：普通聊天消息</li>
 *   <li>命令模式：发送SFW命令，输入框变色为红色边框提示</li>
 * </ul>
 */
public class InputPanel {

    /** 输入回调接口 */
    public interface InputCallback {
        void onSendMessage(String text);
        void onSendCommand(String command);
    }

    private InputCallback callback;
    private JPanel panel;
    private JTextField inputField;
    private JButton sendBtn;
    private JButton modeBtn;
    private JLabel modeLabel;
    private boolean isInit = false;

    /** 是否为命令模式 */
    private boolean commandMode = false;

    public InputPanel(InputCallback callback) {
        this.callback = callback;
        this.panel = new JPanel();
        this.inputField = new JTextField(30);
        this.sendBtn = new JButton("发送");
        this.modeBtn = new JButton("切换命令");
        this.modeLabel = new JLabel("[消息模式]");

        setupListeners();
    }

    public JPanel getPanel() {
        return panel;
    }

    public boolean isCommandMode() { return commandMode; }

    public void clear() {
        inputField.setText("");
    }

    /**
     * 初始化样式。
     */
    public void initStyle() {
        inputField.setForeground(FCM.EXECTION_pathInfo_Color);
        inputField.setCaretColor(FCM.EXECTION_pathInfo_Color);
        inputField.setOpaque(false);
        modeLabel.setForeground(FCM.EXECTION_pathInfo_Color);
        modeLabel.setFont(ConsFrame.font);

        styleButton(sendBtn);
        styleButton(modeBtn);

        // 模式对应边框颜色
        if (commandMode) {
            inputField.setBorder(new RoundedBorder(10, FCM.Error_Color));
        } else {
            inputField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        }

        int wi = ConsFrame.cf.getWidth();
        panel.setPreferredSize(new Dimension(wi / 2, 60));

        chkInit();
    }

    private void chkInit() {
        if (isInit) return;

        panel.setLayout(new BorderLayout(5, 0));
        panel.setOpaque(false);

        // 按钮区域
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        btnPanel.setOpaque(false);
        btnPanel.add(modeLabel);
        btnPanel.add(modeBtn);
        btnPanel.add(sendBtn);

        panel.add(inputField, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.EAST);

        isInit = true;
    }

    private void styleButton(JButton btn) {
        btn.setOpaque(false);
        btn.setForeground(FCM.EXECTION_pathInfo_Color);
        btn.setFont(ConsFrame.font);
        btn.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        btn.setContentAreaFilled(false);
    }

    private void setupListeners() {
        // 发送按钮
        sendBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String text = inputField.getText().trim();
                if (text.isEmpty()) return;

                if (commandMode) {
                    if (callback != null) callback.onSendCommand(text);
                } else {
                    if (callback != null) callback.onSendMessage(text);
                }
                inputField.setText("");
            }
        });

        // 模式切换按钮
        modeBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                commandMode = !commandMode;
                if (commandMode) {
                    modeLabel.setText("[命令模式]");
                    inputField.setBorder(new RoundedBorder(10, FCM.Error_Color));
                } else {
                    modeLabel.setText("[消息模式]");
                    inputField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
                }
            }
        });

        // 回车发送
        inputField.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                sendBtn.doClick();
            }
        });
    }
}
