package sair.aiagent.onebot;

import sair.aiagent.onebot.model.QQMessage;

/**
 * 触发任务 —— 拟人化监听队列的元素，记录一次触发的位置与上下文。
 * <p>
 * 一次「触发」来源可能是：@机器人、提到名字、私聊直接触发、
 * 监听续期（话题讨论中无需触发词自动续接）、好感度主动监听（挚友/恋人概率触发）。
 * </p>
 */
public class TriggerTask {

    /** 触发位置类型：私聊 */
    public static final int TYPE_PRIVATE = 1;
    /** 触发位置类型：群聊 */
    public static final int TYPE_GROUP = 2;

    /** 触发方式：被@ */
    public static final int TRIGGER_AT = 1;
    /** 触发方式：提到名字 */
    public static final int TRIGGER_NAME = 2;
    /** 触发方式：私聊直接触发 */
    public static final int TRIGGER_PRIVATE = 3;
    /** 触发方式：监听续期（无需触发词，受同群自动入队次数限制；含挚友/恋人主动关注） */
    public static final int TRIGGER_LISTEN = 4;

    /** 触发位置类型（TYPE_PRIVATE / TYPE_GROUP） */
    private final int type;
    /** 群号（群聊任务有效，私聊为 0） */
    private final long groupId;
    /** 触发者 QQ 号 */
    private final long userId;
    /** 触发时间戳（毫秒） */
    private final long triggerTime;
    /** 触发方式 */
    private final int triggerType;
    /** 触发消息（保留原始上下文，供引用/折叠内容解析与相关性判定使用） */
    private final QQMessage message;

    /** 触发后的 AI 回复文本（处理完成后回填，用于后续相关性判定的话题上下文） */
    private volatile String lastReply;

    public TriggerTask(int type, long groupId, long userId, int triggerType, QQMessage message) {
        this.type = type;
        this.groupId = groupId;
        this.userId = userId;
        this.triggerTime = System.currentTimeMillis();
        this.triggerType = triggerType;
        this.message = message;
    }

    public int getType() { return type; }
    public long getGroupId() { return groupId; }
    public long getUserId() { return userId; }
    public long getTriggerTime() { return triggerTime; }
    public int getTriggerType() { return triggerType; }
    public QQMessage getMessage() { return message; }
    public String getLastReply() { return lastReply; }
    public void setLastReply(String v) { this.lastReply = v; }

    /** 是否为群聊任务 */
    public boolean isGroup() { return type == TYPE_GROUP; }

    /** 是否为监听续期（无需触发词，受同群自动入队 8 次边界限制） */
    public boolean isListenRenewal() { return triggerType == TRIGGER_LISTEN; }

    /** 距离触发已过去多少毫秒 */
    public long elapsedMs() { return System.currentTimeMillis() - triggerTime; }

    @Override
    public String toString() {
        return "[TriggerTask " + (isGroup() ? "群" + groupId : "私聊")
                + " user=" + userId + " trigger=" + triggerType + "]";
    }
}
