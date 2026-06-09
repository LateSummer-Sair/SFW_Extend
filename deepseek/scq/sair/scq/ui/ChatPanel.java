package sair.scq.ui;

import java.awt.Color;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

import sair.FCM;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SairScrollBarUI;

/**
 * 聊天消息显示面板 —— JList显示聊天消息，自定义CellRenderer区分自己/他人。
 * 
 * <h3>消息格式</h3>
 * <pre>{@code
 * [时间] 用户名: 消息内容
 * }</pre>
 * 
 * <h3>渲染规则</h3>
 * <ul>
 *   <li>自己发的消息：右对齐，绿色文字</li>
 *   <li>他人发的消息：左对齐，白色文字</li>
 *   <li>命令消息：红色标识</li>
 * </ul>
 */
public class ChatPanel {

    /** 当前用户UID（用于判断自己/他人） */
    private long currentUID;

    /** 消息列表 */
    private JList<String> messageList;
    private DefaultListModel<String> messageListModel;
    /** 滚动面板 */
    private JScrollPane scrollPane;
    /** 自定义渲染器 */
    private SCListCellRenderer renderer;

    public ChatPanel() {
        this.messageListModel = new DefaultListModel<String>();
        this.messageList = new JList<String>(messageListModel);
        this.scrollPane = new JScrollPane(messageList);
        this.renderer = new SCListCellRenderer(SwingConstants.LEFT);
        this.messageList.setCellRenderer(renderer);
    }

    public JScrollPane getScrollPane() {
        return scrollPane;
    }

    public JList<String> getMessageList() {
        return messageList;
    }

    public void setCurrentUID(long uid) {
        this.currentUID = uid;
    }

    /**
     * 添加一条消息。
     * @param fromUID 发送者UID
     * @param username 发送者用户名
     * @param content 消息内容
     * @param isCommand 是否命令消息
     * @param isSelf 是否是自己发的
     */
    public void addMessage(long fromUID, String username, String content, boolean isCommand, boolean isSelf) {
        String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
        String prefix = isCommand ? "[命令] " : "";
        String display = "[" + time + "] " + username + ": " + prefix + content;

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                messageListModel.addElement(display);
                // 滚动到底部
                int lastIndex = messageListModel.getSize() - 1;
                if (lastIndex >= 0) {
                    messageList.ensureIndexIsVisible(lastIndex);
                }
            }
        });
    }

    /**
     * 添加系统消息。
     */
    public void addSystemMessage(String msg) {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                messageListModel.addElement("[系统] " + msg);
                int lastIndex = messageListModel.getSize() - 1;
                if (lastIndex >= 0) {
                    messageList.ensureIndexIsVisible(lastIndex);
                }
            }
        });
    }

    /**
     * 清空消息列表。
     */
    public void clear() {
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                messageListModel.clear();
            }
        });
    }

    /**
     * 初始化样式（每次show时调用）。
     */
    public void initStyle() {
        messageList.setForeground(FCM.EXECTION_help_Color);
        messageList.setSelectionForeground(FCM.EXECTION_pathInfo_Color);
        messageList.setOpaque(false);
        messageList.setFont(ConsFrame.font);
        messageList.setFixedCellHeight((int) ConsFrame.font.getSize2D() + 14);
        messageList.setSelectionBackground(new Color(0, 0, 0, 0));

        JScrollBar vsb = scrollPane.getVerticalScrollBar();
        JScrollBar hsb = scrollPane.getHorizontalScrollBar();
        vsb.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
        hsb.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
        vsb.setOpaque(false); hsb.setOpaque(false);

        scrollPane.setOpaque(false);
        scrollPane.getViewport().setOpaque(false);
        scrollPane.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
    }
}
