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
import javax.swing.JPasswordField;
import javax.swing.JTextField;

import sair.FCM;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;

/**
 * 登录/注册面板 —— 用户输入用户名和密码进行登录或注册。
 * 
 * <h3>回调</h3>
 * 通过 LoginCallback 接口将登录/注册操作通知给上层。
 */
public class LoginPanel {

    /** 登录回调接口 */
    public interface LoginCallback {
        void onLogin(String username, String password);
        void onRegister(String username, String password);
    }

    private LoginCallback callback;
    private JPanel panel;
    private JTextField usernameField;
    private JPasswordField passwordField;
    private JLabel statusLabel;
    private boolean isInit = false;

    public LoginPanel(LoginCallback callback) {
        this.callback = callback;
        this.panel = new JPanel();
        this.usernameField = new JTextField(15);
        this.passwordField = new JPasswordField(15);
        this.statusLabel = new JLabel("请输入用户名和密码");
    }

    public JPanel getPanel() {
        return panel;
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public void setStatus(String status, Color color) {
        statusLabel.setText(status);
        statusLabel.setForeground(color);
    }

    /**
     * 显示登录面板。
     */
    public void show() {
        try {
            reinit();
        } catch (Exception e) {}
        ConsFrame.printComponent(panel);
    }

    private void reinit() {
        // --- 主题色 ---
        usernameField.setForeground(FCM.EXECTION_pathInfo_Color);
        usernameField.setCaretColor(FCM.EXECTION_pathInfo_Color);
        passwordField.setForeground(FCM.EXECTION_pathInfo_Color);
        passwordField.setCaretColor(FCM.EXECTION_pathInfo_Color);
        statusLabel.setForeground(FCM.EXECTION_help_Color);
        statusLabel.setFont(ConsFrame.getTextPane().getFont());

        // --- 边框 ---
        usernameField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        passwordField.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        panel.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));

        // --- 尺寸 ---
        int wi = ConsFrame.cf.getWidth();
        int hi = ConsFrame.cf.getHeight();
        panel.setPreferredSize(new Dimension(wi / 3, hi / 3));

        chkInit();
    }

    private void chkInit() {
        if (isInit) return;

        panel.setLayout(new BorderLayout(10, 10));
        panel.setOpaque(false);
        panel.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));

        // --- 输入区域 ---
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        inputPanel.setOpaque(false);

        usernameField.setOpaque(false);
        passwordField.setOpaque(false);

        JLabel userLabel = new JLabel("用户名:");
        userLabel.setForeground(FCM.EXECTION_pathInfo_Color);
        userLabel.setFont(ConsFrame.font);
        JLabel passLabel = new JLabel("密码:");
        passLabel.setForeground(FCM.EXECTION_pathInfo_Color);
        passLabel.setFont(ConsFrame.font);

        inputPanel.add(userLabel);
        inputPanel.add(usernameField);
        inputPanel.add(passLabel);
        inputPanel.add(passwordField);

        // --- 按钮区域 ---
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        btnPanel.setOpaque(false);

        JButton loginBtn = new JButton("登录");
        styleButton(loginBtn);
        loginBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String username = usernameField.getText().trim();
                String password = new String(passwordField.getPassword());
                if (username.isEmpty() || password.isEmpty()) {
                    setStatus("用户名和密码不能为空", FCM.Error_Color);
                    return;
                }
                if (callback != null) {
                    callback.onLogin(username, password);
                }
            }
        });

        JButton registerBtn = new JButton("注册");
        styleButton(registerBtn);
        registerBtn.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                String username = usernameField.getText().trim();
                String password = new String(passwordField.getPassword());
                if (username.isEmpty() || password.isEmpty()) {
                    setStatus("用户名和密码不能为空", FCM.Error_Color);
                    return;
                }
                if (callback != null) {
                    callback.onRegister(username, password);
                }
            }
        });

        btnPanel.add(loginBtn);
        btnPanel.add(registerBtn);

        // --- 状态标签 ---
        statusLabel.setHorizontalAlignment(JLabel.CENTER);

        // --- 布局 ---
        panel.add(statusLabel, BorderLayout.NORTH);
        panel.add(inputPanel, BorderLayout.CENTER);
        panel.add(btnPanel, BorderLayout.SOUTH);

        isInit = true;
    }

    private void styleButton(JButton btn) {
        btn.setOpaque(false);
        btn.setForeground(FCM.EXECTION_pathInfo_Color);
        btn.setFont(ConsFrame.font);
        btn.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        btn.setContentAreaFilled(false);
    }
}
