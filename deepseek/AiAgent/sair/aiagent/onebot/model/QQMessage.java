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
    /** 表情包图片URL列表（仅 sub_type=1/3/7 的 image 段 + mface/sticker 段，用于自动收藏） */
    private final List<String> stickerUrls = new ArrayList<>();
    /**
     * 引用消息中的图片：每项 {@code [url, file(md5)]}，<b>成对存储</b>。
     * <p>
     * 旧实现用两个独立 List（{@code quotedImageUrls} / {@code quotedImageFiles}）并各自
     * 跳过空值，一旦某张图只有 file 没有 url（或反之），两个列表就会错位一格：
     * NapCat 兜底下载会取到<b>另一张图</b>的 md5，图注绑定与按 md5 去重全部张冠李戴。
     * 改成成对存储后，索引永远对齐。</p>
     */
    private final List<String[]> quotedImages = new ArrayList<>();
    /** 是否包含折叠/转发消息 */
    private boolean hasForward;
    /** 折叠消息中提取的文本内容 */
    private String forwardContent;
    /** 折叠消息的原始 JSON（get_forward_msg 响应 / 内嵌 content 数组），供结构化递归展开与图片 URL 提取 */
    private String forwardRaw;
    /** 折叠消息（含各嵌套层）中收集到的图片 URL */
    private final List<String> forwardImageUrls = new ArrayList<>();
    /** 引用消息的原始内容(通过get_msg API获取) */
    private String quotedMessageContent;
    /** 被引用消息的发送者昵称/群名片(通过get_msg API获取) */
    private String quotedSenderName;
    /** 被引用消息的发送者QQ号 */
    private long quotedSenderQQ;
    /** 折叠消息的转发ID(用于get_forward_msg API获取内容) */
    private String forwardId;
    /** 是否包含语音消息段 */
    private boolean hasRecord;
    /** 语音转文字结果（程序自动通过 fetch_ptt_text 获取，非 AI 调用） */
    /**
     * 语音转文字结果。由 {@code OneBot-VoiceTranscribe} 异步线程写入、由消息处理线程读取，
     * 因此必须是 volatile：否则处理线程可能永远看不到转写结果（旧的 null 被缓存在寄存器里），
     * 表现为「语音消息时好时坏、转写已成功却没进上下文」。
     */
    private volatile String voiceText;
    /** 卡片消息（json/xml/markdown等）提取出的人可读内容，供 AI 识别，避免看到空消息 */
    private String cardSummary;
    /** 系统拦截结果说明（群邀请/好友申请等被系统处理后注入，告知 AI 处理结果与原因） */
    private String interceptNote;

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
        public String forwardId; // forward类型的转发ID（字符串）
        public String subType; // image子类型(0=普通,1=表情)
        public String fileId;  // image的文件ID
        public String fileName; // file类型的文件名
        public String data;    // json/xml/markdown 等卡片消息段的内容

        /** 是否是图片消息段 */
        public boolean isImage() { return "image".equals(type); }
        /** 是否是折叠消息段 */
        public boolean isForward() { return "forward".equals(type); }
        /** 是否是文件消息段 */
        public boolean isFile() { return "file".equals(type); }
        /** 是否是超级表情/商城表情消息段 */
        public boolean isMface() { return "mface".equals(type); }
        /** 是否是贴纸消息段 */
        public boolean isSticker() { return "sticker".equals(type); }
        /** 是否是 JSON 卡片消息段（群邀请/分享/小程序等） */
        public boolean isJson() { return "json".equals(type); }
        /** 是否是 XML 卡片消息段 */
        public boolean isXml() { return "xml".equals(type); }
        /** 是否是 Markdown 消息段 */
        public boolean isMarkdown() { return "markdown".equals(type); }
        /** 是否是任意类型的卡片消息段（json/xml/markdown/rich/ark/card） */
        public boolean isCard() {
            return isJson() || isXml() || isMarkdown()
                    || "rich".equals(type) || "ark".equals(type) || "card".equals(type);
        }
        /** 是否是图片段且为表情包子类型(sub_type=1表情/2热图/3斗图/4智图/7贴图) */
        public boolean isStickerImage() {
            if (!"image".equals(type) || subType == null) return false;
            String s = subType.trim();
            return "1".equals(s) || "2".equals(s) || "3".equals(s) || "4".equals(s) || "7".equals(s);
        }
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
    
    /** 获取表情包图片URL列表（仅 sub_type=1/3/7 的 image 段 + mface/sticker 段） */
    public List<String> getStickerUrls() { return stickerUrls; }
    public void addStickerUrl(String url) { if (url != null && !url.isEmpty()) this.stickerUrls.add(url); }
    /** 是否存在可自动收藏的表情包图片 */
    public boolean hasStickerImages() { return !stickerUrls.isEmpty(); }
    
    /** 获取引用消息中的图片URL列表（用于OCR识别；已过滤空项，仅用于计数/提示） */
    public List<String> getQuotedImageUrls() {
        List<String> urls = new ArrayList<>(quotedImages.size());
        for (String[] pair : quotedImages) {
            if (pair[0] != null && !pair[0].isEmpty()) urls.add(pair[0]);
        }
        return urls;
    }

    /** 获取引用消息图片的成对数据 {@code [url, file(md5)]}（索引永远对齐，供视觉处理使用） */
    public List<String[]> getQuotedImages() { return quotedImages; }

    /**
     * 追加一张引用消息图片（成对写入，保持索引对齐）。
     * url 与 file 都为空时不记录。
     */
    public void addQuotedImage(String url, String file) {
        String u = (url == null) ? "" : url;
        String f = (file == null) ? "" : file;
        if (u.isEmpty() && f.isEmpty()) return;
        quotedImages.add(new String[]{u, f});
    }

    /** 是否存在引用图片 */
    public boolean hasQuotedImages() { return !quotedImages.isEmpty(); }
    
    public boolean hasForward() { return hasForward; }
    public void setHasForward(boolean v) { this.hasForward = v; }
    
    public String getForwardContent() { return forwardContent; }
    public void setForwardContent(String v) { this.forwardContent = v; }

    /** 折叠消息原始 JSON（可为空；为空时上层退回按 forwardContent 文本处理） */
    public String getForwardRaw() { return forwardRaw; }
    public void setForwardRaw(String v) { this.forwardRaw = v; }

    /** 折叠消息（含嵌套层）中的图片 URL 列表 */
    public List<String> getForwardImageUrls() { return forwardImageUrls; }
    public void addForwardImageUrl(String url) {
        if (url != null && !url.isEmpty() && !forwardImageUrls.contains(url)) forwardImageUrls.add(url);
    }
    
    public String getQuotedMessageContent() { return quotedMessageContent; }
    public void setQuotedMessageContent(String v) { this.quotedMessageContent = v; }

    /** 被引用消息的发送者昵称/群名片 */
    public String getQuotedSenderName() { return quotedSenderName; }
    public void setQuotedSenderName(String v) { this.quotedSenderName = v; }
    /** 被引用消息的发送者QQ号 */
    public long getQuotedSenderQQ() { return quotedSenderQQ; }
    public void setQuotedSenderQQ(long v) { this.quotedSenderQQ = v; }
    
    public String getForwardId() { return forwardId; }
    public void setForwardId(String v) { this.forwardId = v; }

    /** 是否包含语音消息段 */
    public boolean hasRecord() { return hasRecord; }
    public void setHasRecord(boolean v) { this.hasRecord = v; }

    /** 获取语音转文字结果（程序自动转换） */
    public String getVoiceText() { return voiceText; }
    public void setVoiceText(String v) { this.voiceText = v; }

    /** 卡片消息提取出的人可读内容（可能为空） */
    public String getCardSummary() { return cardSummary; }
    public void setCardSummary(String v) { this.cardSummary = v; }
    /** 系统拦截结果说明（可能为空） */
    public String getInterceptNote() { return interceptNote; }
    public void setInterceptNote(String v) { this.interceptNote = v; }

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

    /**
     * 剥离 CQ 码，但<b>为语义保留占位符</b>。
     * <p>
     * 旧实现直接 {@code replaceAll("\\[CQ:[^\\]]+\\]", "")}，把所有 CQ 码无声删除：
     * 用户发的 {@code [CQ:face,id=178]}（微笑）、{@code [CQ:dice]}、{@code [CQ:location]}、
     * {@code [CQ:file]}、{@code [CQ:reply]} 全部消失，AI 看到的是一个意思完全不同的句子
     * （例如「哈哈哈哈[微笑]」变成「哈哈哈哈」）。而这段文本同时是<b>记忆入库</b>的内容，
     * 丢失即永久丢失。
     * </p>
     * <p>
     * 图片/语音/折叠/卡片四类已有各自的专用注入通道（imageUrls / voiceText /
     * forwardContent / cardSummary），这里保持剥离以免重复；其余类型统一降级为
     * 人类可读占位符，保证「没有任何内容被静默丢弃」。
     * </p>
     */
    static String stripCqCodes(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        java.util.regex.Matcher m = CQ_PATTERN.matcher(raw);
        if (!m.find()) return raw;
        StringBuilder out = new StringBuilder(raw.length());
        int last = 0;
        do {
            out.append(raw, last, m.start());
            out.append(cqPlaceholder(m.group()));
            last = m.end();
        } while (m.find());
        out.append(raw, last, raw.length());
        return out.toString();
    }

    private static final java.util.regex.Pattern CQ_PATTERN =
            java.util.regex.Pattern.compile("\\[CQ:([a-zA-Z_]+)((?:,[^\\]]*)?)\\]");

    /** CQ 码 → 占位符（返回空串表示该类型已有专用注入通道）。 */
    private static String cqPlaceholder(String cqCode) {
        java.util.regex.Matcher m = CQ_PATTERN.matcher(cqCode);
        if (!m.matches()) return "";
        String type = m.group(1).toLowerCase();
        switch (type) {
            // 已有专用注入通道，避免重复
            case "image":
            case "record":
            case "forward":
            case "json":
            case "xml":
                return "";
            case "face":
            case "mface":
            case "sface":
                return "[表情]";
            case "at":
                return "[提及]";
            case "reply":
                return "[引用]";
            case "video":
                return "[视频]";
            case "file":
                return "[文件]";
            case "poke":
                return "[戳一戳]";
            case "dice":
                return "[骰子]";
            case "rps":
                return "[猜拳]";
            case "location":
                return "[位置]";
            case "music":
                return "[音乐]";
            case "contact":
                return "[名片]";
            case "share":
                return "[分享]";
            case "markdown":
                return "[卡片]";
            case "tts":
                return "[语音]";
            default:
                // 未知类型：保留类型名，去掉参数，避免完全静默丢失
                return "[CQ:" + type + "]";
        }
    }

    /** 提取纯文本内容（去除CQ码，含图片/折叠/语音转文字占位） */
    public String getPlainText() {
        if (rawMessage == null) return "";
        StringBuilder sb = new StringBuilder(stripCqCodes(rawMessage).trim());
        // 卡片消息：rawMessage 剥离 CQ 码后为空，这里注入提取出的人可读内容，避免 AI 看到空消息
        if (cardSummary != null && !cardSummary.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(cardSummary);
        }
        // 语音转文字：作为消息正文注入，并备注来源（仅在已转文字成功后追加，避免未转时污染）
        if (hasRecord && voiceText != null && !voiceText.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[语音转文字] ").append(voiceText);
        }
        // 图片信息
        if (hasImage && !imageUrls.isEmpty()) {
            sb.append("\n[包含图片: ").append(imageUrls.size()).append("张]");
            for (int i = 0; i < imageUrls.size(); i++) {
                sb.append("\n  图片").append(i + 1).append(": ").append(imageUrls.get(i));
            }
        }
        // 折叠消息内容
        if (hasForward && forwardContent != null && !forwardContent.isEmpty()) {
            sb.append("\n[转发/折叠消息内容: ").append(forwardContent).append("]");
        }
        return sb.toString().trim();
    }
    
    /** 提取纯文本内容（仅文本，不含图片/折叠信息，含语音转文字） */
    public String getPlainTextOnly() {
        if (rawMessage == null) return "";
        StringBuilder sb = new StringBuilder(stripCqCodes(rawMessage).trim());
        if (cardSummary != null && !cardSummary.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(cardSummary);
        }
        if (hasRecord && voiceText != null && !voiceText.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("[语音转文字] ").append(voiceText);
        }
        return sb.toString().trim();
    }

    @Override
    public String toString() {
        return "[QQMsg type=" + messageType + " user=" + userId
                + (groupId > 0 ? " group=" + groupId : "")
                + " msg=" + (rawMessage != null && rawMessage.length() > 50
                        ? rawMessage.substring(0, 50) + "..." : rawMessage) + "]";
    }
}
