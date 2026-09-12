package sair.aiagent.model;

import java.io.Serializable;

/**
 * 群印象模型 —— 以群号为唯一键存储对一个群的氛围评价。
 * <p>
 * 与 {@link ImpressionEntry}（个人印象）并列，群印象关注「这个群整体是什么风格」：
 * 友好度、氛围、活跃度等。群内多个成员印象变差时，群印象随之变差，
 * 群友好度过低时可触发概率拒答。
 * </p>
 */
public class GroupImpression implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 群友好度下限（低于此值视为「印象差」） */
    public static final int FRIENDLINESS_LOW = -30;

    private long groupId;          // 群号（唯一键）
    private String groupName;      // 群名
    private int friendliness;      // 友好度 -100 ~ 100
    private String atmosphere;     // 群氛围描述（自由文本）
    private int messageCount;      // 累计消息条数
    private long firstSeen;        // 首次遇见时间戳
    private long lastSeen;         // 最后遇见时间戳
    private long updatedAt;        // 最后更新时间戳

    public GroupImpression() {}

    public GroupImpression(long groupId) {
        this.groupId = groupId;
        this.groupName = "";
        this.friendliness = 0;
        this.atmosphere = "";
        this.messageCount = 0;
        long now = System.currentTimeMillis();
        this.firstSeen = now;
        this.lastSeen = now;
        this.updatedAt = now;
    }

    public GroupImpression(long groupId, String groupName, int friendliness,
            String atmosphere, int messageCount, long firstSeen,
            long lastSeen, long updatedAt) {
        this.groupId = groupId;
        this.groupName = groupName == null ? "" : groupName;
        this.friendliness = friendliness;
        this.atmosphere = atmosphere == null ? "" : atmosphere;
        this.messageCount = messageCount;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.updatedAt = updatedAt;
    }

    // ==================== Getters ====================
    public long getGroupId()       { return groupId; }
    public String getGroupName()   { return groupName; }
    public int getFriendliness()   { return friendliness; }
    public String getAtmosphere()  { return atmosphere; }
    public int getMessageCount()   { return messageCount; }
    public long getFirstSeen()     { return firstSeen; }
    public long getLastSeen()      { return lastSeen; }
    public long getUpdatedAt()     { return updatedAt; }

    // ==================== Setters ====================
    public void setGroupId(long v)       { this.groupId = v; }
    public void setGroupName(String v)   { this.groupName = v == null ? "" : v; }
    public void setFriendliness(int v)   { this.friendliness = Math.max(-100, Math.min(100, v)); }
    public void setAtmosphere(String v)  { this.atmosphere = v == null ? "" : v; }
    public void setMessageCount(int v)   { this.messageCount = v; }
    public void setFirstSeen(long v)     { this.firstSeen = v; }
    public void setLastSeen(long v)      { this.lastSeen = v; }
    public void setUpdatedAt(long v)     { this.updatedAt = v; }

    /** 记录一条群消息 */
    public void recordMessage() {
        this.messageCount++;
        this.lastSeen = System.currentTimeMillis();
        this.updatedAt = this.lastSeen;
    }

    /** 调整群友好度（-100~100 区间内） */
    public void adjustFriendliness(int delta) {
        this.friendliness = Math.max(-100, Math.min(100, this.friendliness + delta));
        this.updatedAt = System.currentTimeMillis();
    }

    /** 群印象是否差（用于概率拒答判断） */
    public boolean isUnfriendly() {
        return friendliness <= FRIENDLINESS_LOW;
    }

    /** 是否有足够内容注入上下文 */
    public boolean hasContent() {
        return !atmosphere.isEmpty() || friendliness != 0;
    }

    /** 构建注入系统提示词的群印象上下文 */
    public String toPromptContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("[群印象] 群:").append(groupId);
        if (!groupName.isEmpty()) sb.append("(").append(groupName).append(")");
        sb.append(" 友好度:").append(friendliness);
        // 与 ImpressionEntry 同理：精确的 messageCount 每来一条群消息就变，
        // 而这段位于提示词稳定前缀区，一变就打断 KV 前缀缓存，因此改用活跃度分档。
        sb.append(" 活跃度:").append(activityLabel(messageCount));
        if (!atmosphere.isEmpty()) sb.append("\n- 群氛围: ").append(atmosphere);
        return sb.toString();
    }

    /** 粗粒度活跃度标签（分档而非精确条数，见 {@link #toPromptContext()}）。 */
    private static String activityLabel(int msgs) {
        if (msgs >= 5000) return "极高";
        if (msgs >= 500) return "高";
        if (msgs >= 50) return "中";
        return "低";
    }

    @Override
    public String toString() {
        return "[GroupImpression gid=" + groupId + " friendliness=" + friendliness + "]";
    }
}
