package sair.scq.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import sair.FCM;
import sair.scq.acts.ServerActions;
import sair.scq.model.GroupInfo;
import sair.scq.model.UserInfo;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SairScrollBarUI;

/**
 * 服务端管理面板UI —— 显示在线用户和群组信息。
 * 
 * <h3>布局（参照ConsUI模式）</h3>
 * <pre>
 *   listPane (BorderLayout, 透明)
 *   ├── CENTER: 在线用户列表
 *   └── EAST:   群组列表
 * </pre>
 */
public class ServerUI {

    private final ServerActions serverActions;

    /** 根面板 */
    private JPanel listPane;
    /** 是否已初始化 */
    private boolean isInit = false;

    /** 在线用户列表 */
    private JList<String> userList;
    private DefaultListModel<String> userListModel;
    /** 群组列表 */
    private JList<String> groupList;
    private DefaultListModel<String> groupListModel;
    /** 用户列表滚动面板 */
    private JScrollPane userScrollPane;
    /** 群组列表滚动面板 */
    private JScrollPane groupScrollPane;
    /** 标题标签 */
    private JLabel titleLabel;

    public ServerUI(ServerActions serverActions) {
        this.serverActions = serverActions;
        this.listPane = new JPanel();
        this.userListModel = new DefaultListModel<String>();
        this.groupListModel = new DefaultListModel<String>();
        this.userList = new JList<String>(userListModel);
        this.groupList = new JList<String>(groupListModel);
        this.userScrollPane = new JScrollPane(userList);
        this.groupScrollPane = new JScrollPane(groupList);
        this.titleLabel = new JLabel("SCQ 服务端管理面板");
    }

    /**
     * 显示管理面板。
     */
    public void show() {
        refreshData();
        try {
            reinit();
        } catch (Exception e) {}
        ConsFrame.printComponent(listPane);
    }

    /**
     * 刷新数据。
     */
    public void refreshData() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                userListModel.clear();
                groupListModel.clear();

                // 在线用户
                for (UserInfo u : serverActions.getServerCore().getUserManager().getOnlineUsers(serverActions.getServerCore().getOnlineUIDs())) {
                    userListModel.addElement("UID:" + u.getUid() + " " + u.getUsername()
                        + " [" + u.getRole().name() + "] " + u.getIp());
                }

                // 群组
                for (GroupInfo g : serverActions.getServerCore().getGroupManager().getAllGroups()) {
                    groupListModel.addElement("GID:" + g.getGid() + " " + g.getGroupName()
                        + " (" + g.getMemberUIDs().size() + "人)");
                }
            }
        });
    }

    /** 重新初始化颜色和尺寸 */
    private void reinit() {
        // --- 颜色设置 ---
        userList.setForeground(FCM.EXECTION_help_Color);
        userList.setSelectionForeground(FCM.EXECTION_pathInfo_Color);
        groupList.setForeground(FCM.EXECTION_help_Color);
        groupList.setSelectionForeground(FCM.EXECTION_pathInfo_Color);
        titleLabel.setForeground(FCM.EXECTION_pathInfo_Color);
        titleLabel.setFont(ConsFrame.getTextPane().getFont());

        // --- 滚动条样式 ---
        JScrollBar vsb1 = userScrollPane.getVerticalScrollBar();
        JScrollBar hsb1 = userScrollPane.getHorizontalScrollBar();
        vsb1.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
        hsb1.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
        vsb1.setOpaque(false); hsb1.setOpaque(false);

        JScrollBar vsb2 = groupScrollPane.getVerticalScrollBar();
        JScrollBar hsb2 = groupScrollPane.getHorizontalScrollBar();
        vsb2.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
        hsb2.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
        vsb2.setOpaque(false); hsb2.setOpaque(false);

        // --- 尺寸 ---
        float hi = ((float) ConsFrame.cf.getHeight()) / 10f * 8f;
        float wi = ConsFrame.cf.getWidth() / 2f;
        Dimension size = new Dimension((int) wi, (int) hi);
        userScrollPane.setPreferredSize(size);
        groupScrollPane.setPreferredSize(size);

        userScrollPane.setBorder(new SBorder(FCM.EXECTION_help_Color));
        groupScrollPane.setBorder(new SBorder(FCM.EXECTION_help_Color));

        // 一次性初始化
        chkInit();
    }

    private void chkInit() {
        if (!isInit) {
            listPane.setLayout(new BorderLayout());
            listPane.setOpaque(false);

            // --- 用户列表样式 ---
            userList.setOpaque(false);
            userList.setFont(ConsFrame.font);
            userList.setFixedCellHeight((int) ConsFrame.font.getSize2D() + 10);
            userList.setSelectionBackground(new Color(0, 0, 0, 0));
            ((JComponent) userList.getCellRenderer()).setOpaque(false);

            // --- 群组列表样式 ---
            groupList.setOpaque(false);
            groupList.setFont(ConsFrame.font);
            groupList.setFixedCellHeight((int) ConsFrame.font.getSize2D() + 10);
            groupList.setSelectionBackground(new Color(0, 0, 0, 0));
            ((JComponent) groupList.getCellRenderer()).setOpaque(false);

            // --- 标题 ---
            titleLabel.setBorder(new SBorder(FCM.EXECTION_help_Color));

            // --- 滚动面板透明 ---
            userScrollPane.setOpaque(false);
            userScrollPane.getViewport().setOpaque(false);
            userScrollPane.setColumnHeaderView(titleLabel);
            userScrollPane.getColumnHeader().setOpaque(false);
            groupScrollPane.setOpaque(false);
            groupScrollPane.getViewport().setOpaque(false);

            // --- 布局 ---
            listPane.add(userScrollPane, BorderLayout.CENTER);
            listPane.add(groupScrollPane, BorderLayout.EAST);

            isInit = true;
        }
    }
}
