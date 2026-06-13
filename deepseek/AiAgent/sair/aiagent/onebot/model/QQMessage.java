package sair.aiagent.onebot.model;

import java.util.ArrayList;
import java.util.List;

/**
 * OneBot v11 消息事件模型。
 * 解析来自 OneBot 实现的 JSON 消息事件。
 */
public class QQMessage {

    /** 事件类型：message */
    private String postType;
    /** 消息类型：private / group */
    private String messageType;
    /** 子类型：friend / group / normal */
    private String subType;
    /** 消息ID */
    private long messageId;
    /** 发送者QQ号 */
    private long userId;
    /** 群号（群消息时有效） */
    private long groupId;
    /** 原始消息文本（CQ码已转义） */
    private String rawMessage;
    /** 发送者信息 */
    private Sender sender;
    /** 消息段列表 */
    private List<MessageSegment> segments = new ArrayList<>();
    /** 是否@了机器人 */
    private boolean atBot;
    /** 是否为引用回复 */
    private boolean isReply;
    /** 引用的消息ID */
    private long replyMessageId;

    // === 内部类 ===

    /** 发送者信息 */
    public static class Sender {
        public long userId;
        public String nickname;
        public String card;    // 群名片（群内）
        public String sex;
        public int age;
        public String area;
        public String level;
        public String role;    // owner/admin/member
        public String title;   // 专属头衔
    }

    /** 消息段 */
    public static class MessageSegment {
        public String type;  // text / at / image / reply / face / record / video / json等
        public String text;  // text类型的内容
        public long qq;      // at类型的QQ号
        public long id;      // reply类型的消息ID
        public String url;   // image/record类型的URL
        public String file;  // image/record类型的文件路径
    }

    // === Getters/Setters ===

    public String getPostType() { return postType; }
    public void setPostType(String v) { this.postType = v; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String v) { this.messageType = v; }

    public String getSubType() { return subType; }
    public void setSubType(String v) { this.subType = v; }

    public long getMessageId() { return messageId; }
    public void setMessageId(long v) { this.messageId = v; }

    public long getUserId() { return userId; }
    public void setUserId(long v) { this.userId = v; }

    public long getGroupId() { return groupId; }
    public void setGroupId(long v) { this.groupId = v; }

    public String getRawMessage() { return rawMessage; }
    public void setRawMessage(String v) { this.rawMessage = v; }

    public Sender getSender() { return sender; }
    public void setSender(Sender v) { this.sender = v; }

    public List<MessageSegment> getSegments() { return segments; }
    public void setSegments(List<MessageSegment> v) { this.segments = v; }

    public boolean isAtBot() { return atBot; }
    public void setAtBot(boolean v) { this.atBot = v; }

    public boolean isReply() { return isReply; }
    public void setReply(boolean v) { this.isReply = v; }

    public long getReplyMessageId() { return replyMessageId; }
    public void setReplyMessageId(long v) { this.replyMessageId = v; }

    /** 是否为群消息 */
    public boolean isGroupMessage() {
        return "group".equals(messageType);
    }

    /** 是否为私聊消息 */
    public boolean isPrivateMessage() {
        return "private".equals(messageType);
    }

    /** 获取发送者显示名称（群名片优先，无则用昵称） */
    public String getDisplayName() {
        if (sender == null) return String.valueOf(userId);
        if (sender.card != null && !sender.card.isEmpty()) return sender.card;
        if (sender.nickname != null && !sender.nickname.isEmpty()) return sender.nickname;
        return String.valueOf(userId);
    }

    /** 提取纯文本内容（去除CQ码） */
    public String getPlainText() {
        if (rawMessage == null) return "";
        // 去除CQ码
        return rawMessage.replaceAll("\\[CQ:[^\\]]+\\]", "").trim();
    }

    @Override
    public String toString() {
        return "[QQMsg type=" + messageType + " user=" + userId
                + (groupId > 0 ? " group=" + groupId : "")
                + " msg=" + (rawMessage != null && rawMessage.length() > 50
                        ? rawMessage.substring(0, 50) + "..." : rawMessage) + "]";
    }
}
