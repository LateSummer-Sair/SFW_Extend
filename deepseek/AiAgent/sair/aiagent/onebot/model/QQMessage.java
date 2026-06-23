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
    /** 当前消息中@提及的所有用户QQ号（不含机器人自身） */
    private final List<Long> mentionedUsers = new ArrayList<>();
    /** 当前消息中@提及的所有用户QQ号对应的原始at段文本 */
    private final List<String> mentionedAtStrings = new ArrayList<>();
    
    /** 消息是否包含图片 */
    private boolean hasImage;
    /** 图片URL列表 */
    private final List<String> imageUrls = new ArrayList<>();
    /** 图片本地文件路径列表 */
    private final List<String> imageFilePaths = new ArrayList<>();
    /** 是否包含折叠/转发消息 */
    private boolean hasForward;
    /** 折叠消息中提取的文本内容 */
    private String forwardContent;
    /** 引用消息的原始内容(通过get_msg API获取) */
    private String quotedMessageContent;

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
        public String type;  // text / at / image / reply / face / record / video / json / forward等
        public String text;  // text类型的内容
        public long qq;      // at类型的QQ号
        public long id;      // reply类型的消息ID
        public String url;   // image/record类型的URL
        public String file;  // image/record类型的文件路径
        public String content; // forward类型的嵌套消息内容(JSON数组)
        public String subType; // image子类型(0=普通,1=表情)
        public String fileId;  // image的文件ID
        public String fileName; // file类型的文件名
        
        /** 是否是图片消息段 */
        public boolean isImage() { return "image".equals(type); }
        /** 是否是折叠消息段 */
        public boolean isForward() { return "forward".equals(type); }
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

    /** 获取当前消息中@提及的用户QQ号列表 */
    public List<Long> getMentionedUsers() { return mentionedUsers; }
    /** 获取当前消息中@提及的原始at段文本 */
    public List<String> getMentionedAtStrings() { return mentionedAtStrings; }
    /** 添加一个被@提及的用户 */
    public void addMentionedUser(long qq, String atString) {
        this.mentionedUsers.add(qq);
        this.mentionedAtStrings.add(atString != null ? atString : String.valueOf(qq));
    }
    
    // === 图片/折叠相关 ===
    
    public boolean hasImage() { return hasImage; }
    public void setHasImage(boolean v) { this.hasImage = v; }
    
    public List<String> getImageUrls() { return imageUrls; }
    public void addImageUrl(String url) { if (url != null && !url.isEmpty()) this.imageUrls.add(url); }
    
    public List<String> getImageFilePaths() { return imageFilePaths; }
    public void addImageFilePath(String path) { if (path != null && !path.isEmpty()) this.imageFilePaths.add(path); }
    
    public boolean hasForward() { return hasForward; }
    public void setHasForward(boolean v) { this.hasForward = v; }
    
    public String getForwardContent() { return forwardContent; }
    public void setForwardContent(String v) { this.forwardContent = v; }
    
    public String getQuotedMessageContent() { return quotedMessageContent; }
    public void setQuotedMessageContent(String v) { this.quotedMessageContent = v; }

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

    /** 提取纯文本内容（去除CQ码，含图片/折叠占位） */
    public String getPlainText() {
        if (rawMessage == null) return "";
        String text = rawMessage;
        // 如果解析了消息段且包含图片，追加图片信息
        if (hasImage && !imageUrls.isEmpty()) {
            StringBuilder sb = new StringBuilder(text.replaceAll("\\[CQ:[^\\]]+\\]", "").trim());
            sb.append("\n[包含图片: ").append(imageUrls.size()).append("张]");
            for (int i = 0; i < imageUrls.size(); i++) {
                sb.append("\n  图片").append(i + 1).append(": ").append(imageUrls.get(i));
            }
            return sb.toString().trim();
        }
        // 如果包含折叠消息，追加折叠内容
        if (hasForward && forwardContent != null && !forwardContent.isEmpty()) {
            StringBuilder sb = new StringBuilder(text.replaceAll("\\[CQ:[^\\]]+\\]", "").trim());
            sb.append("\n[转发/折叠消息内容: ").append(forwardContent).append("]");
            return sb.toString().trim();
        }
        return text.replaceAll("\\[CQ:[^\\]]+\\]", "").trim();
    }
    
    /** 提取纯文本内容（仅文本，不含图片/折叠信息） */
    public String getPlainTextOnly() {
        if (rawMessage == null) return "";
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
