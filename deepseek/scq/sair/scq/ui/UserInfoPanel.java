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
 * 用户信息编辑面板 —— 修改签名、头像、密码。
 * 
 * <h3>布局</h3>
 * 竖向排列：签名输入、密码输入、保存按钮。
 */
public class UserInfoPanel {

    /** 回调接口 */
    public interface UserInfoCallback {
        void onSave(String signature, String avatarPath, String newPassword);
    }

    private UserInfoCallback callback;
    private JPanel panel;
    private JTextField signatureField;
    private JTextField avatarField;
    private JTextField passwordField;
    private JLabel statusLabel;
    private boolean isInit = false;

    public UserInfoPanel(UserInfoCallback callback) {
        this.callback = callback;
        this.panel = new JPanel();
        this.signatureField = new JTextField(20);
        this.avatarField = new JTextField(20);
        this.passwordField = new JTextField(20);
        this.statusLabel = new JLabel("编辑个人信息");
    }

    public JPanel getPanel() {
        return panel;
    }

    public void setSignature(String sig) {
        signatureField.setText(sig != null ? sig : "");
    }

    public void clearPassword() {
        passwordField.setText("");
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public void show() {
        try {
            reinit();
        } catch (Exception e) {}
        ConsFrame.printComponent(panel);
    }

    private void reinit() {
        signatureField.setForeground(FCM.EXECTION_pathInfo_Color);
        signatureField.setCaretColor(FCM.EXECTION_pathInfo_Color);
        signatureField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        avatarField.setForeground(FCM.EXECTION_pathInfo_Color);
        avatarField.setCaretColor(FCM.EXECTION_pathInfo_Color);
        avatarField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        passwordField.setForeground(FCM.EXECTION_pathInfo_Color);
        passwordField.setCaretColor(FCM.EXECTION_pathInfo_Color);
        passwordField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        statusLabel.setForeground(FCM.EXECTION_help_Color);
        statusLabel.setFont(ConsFrame.getTextPane().getFont());

        int wi = ConsFrame.cf.getWidth();
        panel.setPreferredSize(new Dimension(wi / 3, 200));

        chkInit();
    }

    private void chkInit() {
        if (isInit) return;

        panel.setLayout(new BorderLayout(5, 5));
        panel.setOpaque(false);
        panel.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));

        JPanel centerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        centerPanel.setOpaque(false);

        // 签名行
        JLabel sigLabel = new JLabel("签名:");
        sigLabel.setForeground(FCM.EXECTION_pathInfo_Color);
        sigLabel.setFont(ConsFrame.font);
        signatureField.setOpaque(false);
        signatureField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        centerPanel.add(sigLabel);
        centerPanel.add(signatureField);

        // 头像行
        JLabel avatarLabel = new JLabel("头像路径:");
        avatarLabel.setForeground(FCM.EXECTION_pathInfo_Color);
        avatarLabel.setFont(ConsFrame.font);
        avatarField.setOpaque(false);
        avatarField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        centerPanel.add(avatarLabel);
        centerPanel.add(avatarField);

        // 密码行
        JLabel passLabel = new JLabel("新密码:");
        passLabel.setForeground(FCM.EXECTION_pathInfo_Color);
        passLabel.setFont(ConsFrame.font);
        passwordField.setOpaque(false);
        passwordField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        centerPanel.add(passLabel);
        centerPanel.add(passwordField);

        // 保存按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        btnPanel.setOpaque(false);
        JButton saveBtn = new JButton("保存");
        saveBtn.setOpaque(false);
        saveBtn.setForeground(FCM.EXECTION_pathInfo_Color);
        saveBtn.setFont(ConsFrame.font);
        saveBtn.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        saveBtn.setContentAreaFilled(false);
        saveBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                if (callback != null) {
                    callback.onSave(
                        signatureField.getText().trim(),
                        avatarField.getText().trim(),
                        passwordField.getText().trim()
                    );
                }
            }
        });
        btnPanel.add(saveBtn);

        statusLabel.setHorizontalAlignment(JLabel.CENTER);

        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(centerPanel, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);

        isInit = true;
    }
}
