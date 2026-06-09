package sair.scq.ui;

import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;

import sair.FCM;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SairScrollBarUI;

/**
 * 通讯录面板 —— 显示好友列表和群组列表。
 * 
 * <h3>交互</h3>
 * <ul>
 *   <li>双击好友 → 打开私聊</li>
 *   <li>双击群组 → 打开群聊</li>
 *   <li>右键 → 移除好友/退群</li>
 * </ul>
 */
public class ContactPanel {

    /** 通讯录回调接口 */
    public interface ContactCallback {
        void onContactDoubleClick(long uid, String name, boolean isGroup);
        void onContactRightClick(long uid, String name, boolean isGroup);
    }

    private ContactCallback callback;

    /** 好友列表 */
    private JList<String> contactList;
    private DefaultListModel<String> contactListModel;
    /** 群组列表 */
    private JList<String> groupList;
    private DefaultListModel<String> groupListModel;
    /** 滚动面板 */
    private JScrollPane contactScrollPane;
    private JScrollPane groupScrollPane;

    /** 好友UID存储（与列表索引对应） */
    private java.util.List<Long> contactUIDs = new java.util.ArrayList<Long>();
    /** 群组GID存储 */
    private java.util.List<Long> groupGIDs = new java.util.ArrayList<Long>();

    public ContactPanel(ContactCallback callback) {
        this.callback = callback;
        this.contactListModel = new DefaultListModel<String>();
        this.groupListModel = new DefaultListModel<String>();
        this.contactList = new JList<String>(contactListModel);
        this.groupList = new JList<String>(groupListModel);
        this.contactScrollPane = new JScrollPane(contactList);
        this.groupScrollPane = new JScrollPane(groupList);

        setupListeners();
    }

    public JScrollPane getContactScrollPane() { return contactScrollPane; }
    public JScrollPane getGroupScrollPane() { return groupScrollPane; }

    /**
     * 添加好友到列表。
     */
    public void addContact(long uid, String name, String signature, boolean online) {
        for (int i = 0; i < contactUIDs.size(); i++) {
            if (contactUIDs.get(i) == uid) return; // 已存在
        }
        contactUIDs.add(uid);
        String status = online ? "[在线] " : "[离线] ";
        contactListModel.addElement(status + "UID:" + uid + " - " + name + " - " + (signature != null ? signature : ""));
    }

    /**
     * 添加群组到列表。
     */
    public void addGroup(long gid, String name, int memberCount) {
        for (int i = 0; i < groupGIDs.size(); i++) {
            if (groupGIDs.get(i) == gid) return;
        }
        groupGIDs.add(gid);
        groupListModel.addElement(name + " (" + memberCount + "人)");
    }

    /**
     * 清空通讯录。
     */
    public void clear() {
        contactUIDs.clear();
        groupGIDs.clear();
        contactListModel.clear();
        groupListModel.clear();
    }

    /**
     * 更新好友在线状态。
     */
    public void updateContactStatus(long uid, boolean online) {
        int index = contactUIDs.indexOf(uid);
        if (index >= 0 && index < contactListModel.size()) {
            String text = contactListModel.get(index);
            if (online) {
                text = text.replace("[离线]", "[在线]");
            } else {
                text = text.replace("[在线]", "[离线]");
            }
            contactListModel.set(index, text);
        }
    }

    /** 初始化样式 */
    public void initStyle() {
        // 好友列表
        contactList.setForeground(FCM.EXECTION_help_Color);
        contactList.setSelectionForeground(FCM.EXECTION_pathInfo_Color);
        contactList.setOpaque(false);
        contactList.setFont(ConsFrame.font);
        contactList.setFixedCellHeight((int) ConsFrame.font.getSize2D() + 10);
        contactList.setSelectionBackground(new Color(0, 0, 0, 0));
        ((JComponent) contactList.getCellRenderer()).setOpaque(false);

        // 群组列表
        groupList.setForeground(FCM.EXECTION_help_Color);
        groupList.setSelectionForeground(FCM.EXECTION_pathInfo_Color);
        groupList.setOpaque(false);
        groupList.setFont(ConsFrame.font);
        groupList.setFixedCellHeight((int) ConsFrame.font.getSize2D() + 10);
        groupList.setSelectionBackground(new Color(0, 0, 0, 0));
        ((JComponent) groupList.getCellRenderer()).setOpaque(false);

        // 滚动条
        styleScrollPane(contactScrollPane);
        styleScrollPane(groupScrollPane);
    }

    private void styleScrollPane(JScrollPane sp) {
        JScrollBar vsb = sp.getVerticalScrollBar();
        JScrollBar hsb = sp.getHorizontalScrollBar();
        vsb.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
        hsb.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
        vsb.setOpaque(false); hsb.setOpaque(false);
        sp.setOpaque(false);
        sp.getViewport().setOpaque(false);
        sp.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
    }

    private void setupListeners() {
        // 好友列表双击
        contactList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int index = contactList.locationToIndex(e.getPoint());
                if (index < 0 || index >= contactUIDs.size()) return;

                long uid = contactUIDs.get(index);
                String name = contactListModel.get(index);

                if (e.getClickCount() == 2) {
                    if (callback != null) callback.onContactDoubleClick(uid, name, false);
                } else if (e.getButton() == MouseEvent.BUTTON3) {
                    if (callback != null) callback.onContactRightClick(uid, name, false);
                }
            }
        });

        // 群组列表双击
        groupList.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                int index = groupList.locationToIndex(e.getPoint());
                if (index < 0 || index >= groupGIDs.size()) return;

                long gid = groupGIDs.get(index);
                String name = groupListModel.get(index);

                if (e.getClickCount() == 2) {
                    if (callback != null) callback.onContactDoubleClick(gid, name, true);
                } else if (e.getButton() == MouseEvent.BUTTON3) {
                    if (callback != null) callback.onContactRightClick(gid, name, true);
                }
            }
        });
    }
}
