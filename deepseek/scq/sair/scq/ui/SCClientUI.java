package sair.scq.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import sair.FCM;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;

/**
 * 客户端主布局管理器 —— 参照 musicplayer 的 ConsUI 模式。
 * 
 * <h3>布局</h3>
 * <pre>
 *   listPane (BorderLayout, 透明)
 *   ├── NORTH:  titleBar (用户名 + 在线状态)
 *   ├── CENTER: chatPanel (聊天消息显示区 JList)
 *   ├── WEST:   contactPanel (通讯录 - 好友+群组)
 *   └── SOUTH:  inputPanel (消息输入区)
 * </pre>
 * 
 * <h3>设计要点</h3>
 * <ul>
 *   <li>所有组件 setOpaque(false)</li>
 *   <li>使用 FCM 主题色</li>
 *   <li>通过 ConsFrame.printComponent() 输出</li>
 *   <li>懒加载子面板</li>
 * </ul>
 */
public class SCClientUI {

    /** 主面板 */
    private JPanel listPane;
    /** 是否已初始化 */
    private boolean isInit = false;

    /** 标题栏 */
    private JPanel titleBar;
    private JLabel titleLabel;
    private JLabel statusLabel;

    /** 当前用户名 */
    private String currentUsername = "";
    /** 通讯录面板容器（内部包含好友列表和群组列表上下排列） */
    private JPanel contactContainer;

    // 子面板引用
    private ChatPanel chatPanel;
    private ContactPanel contactPanel;
    private InputPanel inputPanel;
    private LoginPanel loginPanel;
    private UserInfoPanel userInfoPanel;

    public SCClientUI() {
        this.listPane = new JPanel();
        this.titleBar = new JPanel();
        this.titleLabel = new JLabel("SCQ 即时聊天");
        this.statusLabel = new JLabel("未连接");
    }

    public JPanel getListPane() { return listPane; }

    public void setChatPanel(ChatPanel cp) { this.chatPanel = cp; }
    public void setContactPanel(ContactPanel cp) { this.contactPanel = cp; }
    public void setInputPanel(InputPanel ip) { this.inputPanel = ip; }
    public void setLoginPanel(LoginPanel lp) { this.loginPanel = lp; }
    public void setUserInfoPanel(UserInfoPanel uip) { this.userInfoPanel = uip; }

    public void setCurrentUsername(String name) {
        this.currentUsername = name;
        titleLabel.setText("SCQ - " + name);
    }

    public void setStatusText(String status) {
        statusLabel.setText(status);
    }

    // ==================== 显示方法 ====================

    /**
     * 显示主聊天界面。
     */
    public void showMainUI() {
        try { reinit(); } catch (Exception e) {}
        ConsFrame.printComponent(listPane);
    }

    /**
     * 显示登录面板（懒加载）。
     */
    public void showLoginPanel() {
        if (loginPanel != null) {
            loginPanel.show();
        }
    }

    /**
     * 显示用户信息编辑面板（懒加载）。
     */
    public void showUserInfoPanel() {
        if (userInfoPanel != null) {
            userInfoPanel.show();
        }
    }

    // ==================== 内部方法 ====================

    private void reinit() {
        // --- 颜色 ---
        titleLabel.setForeground(FCM.EXECTION_pathInfo_Color);
        titleLabel.setFont(ConsFrame.getTextPane().getFont());
        statusLabel.setForeground(FCM.EXECTION_help_Color);
        statusLabel.setFont(ConsFrame.font);

        // --- 子面板样式刷新 ---
        if (chatPanel != null) chatPanel.initStyle();
        if (contactPanel != null) contactPanel.initStyle();
        if (inputPanel != null) inputPanel.initStyle();

        // --- 尺寸 ---
        float hi = ((float) ConsFrame.cf.getHeight()) / 10f * 4f;
        float wi = ConsFrame.cf.getWidth();
        Dimension chatSize = new Dimension((int)(wi / 2f), (int) hi);
        Dimension contactSize = new Dimension((int)(wi / 4f), (int) hi);

        if (chatPanel != null && chatPanel.getScrollPane() != null) {
            chatPanel.getScrollPane().setPreferredSize(chatSize);
        }

        if (contactPanel != null) {
            if (contactPanel.getContactScrollPane() != null) {
                contactPanel.getContactScrollPane().setPreferredSize(contactSize);
            }
            if (contactPanel.getGroupScrollPane() != null) {
                contactPanel.getGroupScrollPane().setPreferredSize(
                    new Dimension((int)(wi / 4f), (int)(hi / 2f)));
            }
        }

        chkInit();
    }

    private void chkInit() {
        if (isInit) return;

        listPane.setLayout(new BorderLayout());
        listPane.setOpaque(false);

        // --- 标题栏 ---
        titleBar.setLayout(new FlowLayout(FlowLayout.LEFT, 10, 2));
        titleBar.setOpaque(false);
        titleBar.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
        titleBar.add(titleLabel);
        titleBar.add(new JLabel(" | "));
        titleBar.add(statusLabel);

        // --- 通讯录容器（好友在上，群组在下） ---
        contactContainer = new JPanel(new BorderLayout());
        contactContainer.setOpaque(false);
        if (contactPanel != null) {
            JLabel contactLabel = new JLabel("好友");
            contactLabel.setForeground(FCM.EXECTION_pathInfo_Color);
            contactLabel.setFont(ConsFrame.font);

            JLabel groupLabel = new JLabel("群组");
            groupLabel.setForeground(FCM.EXECTION_pathInfo_Color);
            groupLabel.setFont(ConsFrame.font);

            JPanel contactPart = new JPanel(new BorderLayout());
            contactPart.setOpaque(false);
            contactPart.add(contactLabel, BorderLayout.NORTH);
            contactPart.add(contactPanel.getContactScrollPane(), BorderLayout.CENTER);

            JPanel groupPart = new JPanel(new BorderLayout());
            groupPart.setOpaque(false);
            groupPart.add(groupLabel, BorderLayout.NORTH);
            groupPart.add(contactPanel.getGroupScrollPane(), BorderLayout.CENTER);

            contactContainer.add(contactPart, BorderLayout.CENTER);
            contactContainer.add(groupPart, BorderLayout.SOUTH);
        }

        // --- 布局组合 ---
        listPane.add(titleBar, BorderLayout.NORTH);

        if (chatPanel != null && chatPanel.getScrollPane() != null) {
            listPane.add(chatPanel.getScrollPane(), BorderLayout.CENTER);
        }

        listPane.add(contactContainer, BorderLayout.WEST);

        if (inputPanel != null && inputPanel.getPanel() != null) {
            listPane.add(inputPanel.getPanel(), BorderLayout.SOUTH);
        }

        isInit = true;
    }
}
