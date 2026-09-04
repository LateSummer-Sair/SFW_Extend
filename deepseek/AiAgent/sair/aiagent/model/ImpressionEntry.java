package sair.aiagent.model;

import java.io.Serializable;

/**
 * 人格印象模型 —— 以 QQ 号为唯一键存储对一个人的多维度印象。
 * <p>
 * 类似 SkillEntry 的技能蒸馏机制，ImpressionEntry 从每次对话中
 * 蒸馏出对这个人的系统性认知，在后续对话中注入上下文帮助 AI 更好地回应。
 * </p>
 *
 * <h3>5个印象维度</h3>
 * <ul>
 *   <li><b>emotionStability</b> — 情绪稳定性：喜怒无常还是沉稳冷静</li>
 *   <li><b>interests</b>       — 兴趣爱好：喜欢什么事物/游戏/话题/词汇</li>
 *   <li><b>speakingStyle</b>   — 说话风格：话痨/简洁、喜欢聊什么内容</li>
 *   <li><b>honesty</b>         — 务实 vs 吹牛：求真务实者认真对待，爱吹牛者调侃回应</li>
 *   <li><b>imageHabit</b>      — 图片偏好：是否喜欢发图片，频率如何</li>
 * </ul>
 */
public class ImpressionEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private long qq;               // QQ号（唯一键）
    private String nickname;       // 昵称
    private String emotionStability;  // 情绪稳定性描述
    private String interests;         // 兴趣爱好描述
    private String speakingStyle;     // 说话风格描述
    private String honesty;           // 务实/吹牛程度描述
    private String imageHabit;        // 图片偏好描述
    private int impressionLevel;      // 印象好坏 -100~100（正=好，负=差）
    private int messageCount;         // 累计消息条数
    private long firstSeen;           // 首次遇见时间戳
    private long lastSeen;            // 最后遇见时间戳
    private long updatedAt;           // 最后一次蒸馏更新时间戳

    public ImpressionEntry() {}

    /** 新建印象 */
    public ImpressionEntry(long qq) {
        this.qq = qq;
        this.emotionStability = "";
        this.interests = "";
        this.speakingStyle = "";
        this.honesty = "";
        this.imageHabit = "";
        this.impressionLevel = 0;
        this.messageCount = 0;
        long now = System.currentTimeMillis();
        this.firstSeen = now;
        this.lastSeen = now;
        this.updatedAt = now;
    }

    /** DB 加载构造 */
    public ImpressionEntry(long qq, String nickname, String emotionStability,
            String interests, String speakingStyle, String honesty,
            String imageHabit, int impressionLevel, int messageCount, long firstSeen,
            long lastSeen, long updatedAt) {
        this.qq = qq;
        this.nickname = nickname;
        this.emotionStability = toStr(emotionStability);
        this.interests = toStr(interests);
        this.speakingStyle = toStr(speakingStyle);
        this.honesty = toStr(honesty);
        this.imageHabit = toStr(imageHabit);
        this.impressionLevel = impressionLevel;
        this.messageCount = messageCount;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.updatedAt = updatedAt;
    }

    private static String toStr(String s) { return s != null ? s : ""; }

    // ==================== Getters ====================
    public long getQq()              { return qq; }
    public String getNickname()      { return nickname; }
    public String getEmotionStability() { return emotionStability; }
    public String getInterests()      { return interests; }
    public String getSpeakingStyle()  { return speakingStyle; }
    public String getHonesty()        { return honesty; }
    public String getImageHabit()     { return imageHabit; }
    public int getImpressionLevel()   { return impressionLevel; }
    public int getMessageCount()      { return messageCount; }
    public long getFirstSeen()       { return firstSeen; }
    public long getLastSeen()        { return lastSeen; }
    public long getUpdatedAt()       { return updatedAt; }

    // ==================== Setters ====================
    public void setQq(long qq)                   { this.qq = qq; }
    public void setNickname(String n)            { this.nickname = n; }
    public void setEmotionStability(String v)    { this.emotionStability = toStr(v); }
    public void setInterests(String v)           { this.interests = toStr(v); }
    public void setSpeakingStyle(String v)       { this.speakingStyle = toStr(v); }
    public void setHonesty(String v)             { this.honesty = toStr(v); }
    public void setImageHabit(String v)          { this.imageHabit = toStr(v); }
    public void setImpressionLevel(int v)        { this.impressionLevel = Math.max(-100, Math.min(100, v)); }
    public void setMessageCount(int c)           { this.messageCount = c; }
    public void setFirstSeen(long t)             { this.firstSeen = t; }
    public void setLastSeen(long t)              { this.lastSeen = t; }
    public void setUpdatedAt(long t)             { this.updatedAt = t; }

    /** 记录一条消息，更新 lastSeen 和 messageCount */
    public void recordMessage() {
        this.messageCount++;
        this.lastSeen = System.currentTimeMillis();
    }

    /** 是否有足够印象值得注入上下文（至少有一条维度描述） */
    public boolean hasContent() {
        return !emotionStability.isEmpty() || !interests.isEmpty()
            || !speakingStyle.isEmpty() || !honesty.isEmpty()
            || !imageHabit.isEmpty() || impressionLevel != 0;
    }

    /** 印象是否差（用于偏好设定门禁）。 */
    public boolean isBad() {
        return impressionLevel < 0;
    }

    /** 构建注入系统提示词的印象上下文 */
    public String toPromptContext() {
        if (!hasContent()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("[Person印象] QQ:").append(qq);
        if (nickname != null && !nickname.isEmpty())
            sb.append("(").append(nickname).append(")");
        sb.append(" 累计").append(messageCount).append("条消息\n");
        if (!emotionStability.isEmpty())
            sb.append("- 情绪状态: ").append(emotionStability).append("\n");
        if (!interests.isEmpty())
            sb.append("- 兴趣爱好: ").append(interests).append("\n");
        if (!speakingStyle.isEmpty())
            sb.append("- 说话风格: ").append(speakingStyle).append("\n");
        if (!honesty.isEmpty())
            sb.append("- 虚实态度: ").append(honesty).append("\n");
        if (!imageHabit.isEmpty())
            sb.append("- 图片习惯: ").append(imageHabit).append("\n");
        if (impressionLevel != 0)
            sb.append("- 印象好坏: ").append(impressionLevel > 0 ? "好(" + impressionLevel + ")" : "差(" + impressionLevel + ")").append("\n");
        return sb.toString().trim();
    }

    @Override
    public String toString() {
        return "[Impression qq=" + qq + " msgs=" + messageCount
                + " hasContent=" + hasContent() + "]";
    }
}
