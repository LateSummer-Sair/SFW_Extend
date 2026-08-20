package sair.aiagent.model;

import java.io.Serializable;

/**
 * 系统闹钟（AI Alarm）条目模型 —— 到点后唤醒 AI 走 Agent 链路执行任务。
 * <p>
 * 与 {@code CronScheduler} 的 SFW 定时任务不同，alarm 的触发对象是 AI 本身：
 * 到点后按 {@code scope} 重建上下文并唤醒 Agent（Function Calling 工具循环）干活。
 * </p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>id</b>         —— 自增编号</li>
 *   <li><b>scope</b>      —— 执行权限级别：REMIND / EXECQ / EXECS</li>
 *   <li><b>schedule</b>   —— 时间表达：once:yyyy-MM-dd HH:mm / daily:HH:mm / weekly:D,HH:mm</li>
 *   <li><b>task</b>       —— 任务描述（用户原始请求）</li>
 *   <li><b>snapshot</b>   —— 上下文快照 JSON（stableSystem/dynamicContext/model）</li>
 *   <li><b>notes</b>      —— 追加备注 JSON 数组 [{ts, content}]</li>
 *   <li><b>channel</b>    —— 触发通道：console / execq</li>
 *   <li><b>senderQq</b>   —— 触发者 QQ（execq）</li>
 *   <li><b>isGroup</b>    —— 群聊/私聊</li>
 *   <li><b>groupId</b>    —— 群号</li>
 *   <li><b>isMaster</b>   —— 触发者是否主人</li>
 *   <li><b>repeat</b>     —— 是否重复任务</li>
 *   <li><b>enabled</b>    —— 是否启用</li>
 *   <li><b>lastRun</b>    —— 最后执行时间戳</li>
 *   <li><b>createdAt</b>  —— 创建时间戳</li>
 * </ul>
 */
public class AlarmEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String scope;
    private String schedule;
    private String task;
    private String snapshot;
    private String notes;
    private String channel;
    private long senderQq;
    private boolean isGroup;
    private long groupId;
    private boolean isMaster;
    private boolean repeat;
    private boolean enabled;
    private long lastRun;
    private long createdAt;

    /** 无参构造（Gson 反序列化） */
    public AlarmEntry() {}

    /** 完整构造 */
    public AlarmEntry(int id, String scope, String schedule, String task,
                      String snapshot, String notes, String channel,
                      long senderQq, boolean isGroup, long groupId,
                      boolean isMaster, boolean repeat, boolean enabled,
                      long lastRun, long createdAt) {
        this.id = id;
        this.scope = (scope != null) ? scope : "REMIND";
        this.schedule = (schedule != null) ? schedule : "";
        this.task = (task != null) ? task : "";
        this.snapshot = snapshot;
        this.notes = notes;
        this.channel = (channel != null) ? channel : "console";
        this.senderQq = senderQq;
        this.isGroup = isGroup;
        this.groupId = groupId;
        this.isMaster = isMaster;
        this.repeat = repeat;
        this.enabled = enabled;
        this.lastRun = lastRun;
        this.createdAt = createdAt;
    }

    // ==================== Getters & Setters ====================

    public int getId()         { return id; }
    public String getScope()   { return scope; }
    public String getSchedule(){ return schedule; }
    public String getTask()    { return task; }
    public String getSnapshot(){ return snapshot; }
    public String getNotes()   { return notes; }
    public String getChannel() { return channel; }
    public long getSenderQq()  { return senderQq; }
    public boolean isGroup()   { return isGroup; }
    public long getGroupId()   { return groupId; }
    public boolean isMaster()  { return isMaster; }
    public boolean isRepeat()  { return repeat; }
    public boolean isEnabled() { return enabled; }
    public long getLastRun()   { return lastRun; }
    public long getCreatedAt() { return createdAt; }

    public void setId(int id)             { this.id = id; }
    public void setScope(String s)        { this.scope = s; }
    public void setSchedule(String s)     { this.schedule = s; }
    public void setTask(String t)         { this.task = t; }
    public void setSnapshot(String s)     { this.snapshot = s; }
    public void setNotes(String n)        { this.notes = n; }
    public void setChannel(String c)      { this.channel = c; }
    public void setSenderQq(long q)       { this.senderQq = q; }
    public void setGroup(boolean g)       { this.isGroup = g; }
    public void setGroupId(long g)        { this.groupId = g; }
    public void setMaster(boolean m)      { this.isMaster = m; }
    public void setRepeat(boolean r)      { this.repeat = r; }
    public void setEnabled(boolean e)     { this.enabled = e; }
    public void setLastRun(long l)        { this.lastRun = l; }
    public void setCreatedAt(long c)      { this.createdAt = c; }

    @Override
    public String toString() {
        String status = enabled ? "[启用]" : "[禁用]";
        return "#" + id + " " + status + " [" + scope + "] " + schedule + " | " + task;
    }
}
