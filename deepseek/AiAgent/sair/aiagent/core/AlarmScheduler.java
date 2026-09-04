package sair.aiagent.core;

import java.util.Calendar;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.AlarmEntry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 系统闹钟（AI Alarm）调度器 —— 到点后唤醒 AI 走 Agent 链路执行任务。
 * <p>
 * 与 {@link CronScheduler}（SFW 命令定时任务）不同，本调度器触发的是 AI 本身：
 * 到点按 {@code scope} 重建上下文，调用 {@link AgentExecutor} 的 Function Calling
 * 链路（executeFcExecq / executeFcExecs）实实在在唤醒 Agent 干活。
 * </p>
 *
 * <h3>时间表达（schedule 字段，由 AI 翻译语义后填入）</h3>
 * <ul>
 *   <li>一次性：{@code once:yyyy-MM-dd HH:mm}</li>
 *   <li>每天：{@code daily:HH:mm}</li>
 *   <li>每周几：{@code weekly:D,HH:mm}（D=1~7，1=周一）</li>
 * </ul>
 *
 * <h3>移除规则</h3>
 * <ul>
 *   <li>一次性任务（repeat=false）：触发执行一次即视为完成，自动删除。</li>
 *   <li>重复任务（repeat=true）：保留，直到用户"取消 / 不提醒了"。</li>
 * </ul>
 */
public class AlarmScheduler {

    /**
     * 闹钟与 QQ 通道 / 结果回传之间的桥接。
     * <p>由上层（AiAgentActivity）实现，注入 NapCatApi / 记忆 / 情绪等组件，
     * 避免调度器直接依赖 onebot 包，保持 core 层纯净。</p>
     */
    public interface AlarmBridge {
        /** 构建 QQ 工具执行上下文（execq/execs 触发时使用）。 */
        ToolContext buildToolContext(AlarmEntry alarm);
        /** 回传结果：群聊触发回原群 @触发者，私聊触发回私聊。 */
        void sendReply(AlarmEntry alarm, String text);
    }

    private static final long CHECK_INTERVAL_SECONDS = 20;

    private final PersistenceManager persistence;
    private final AgentExecutor agent;
    private final AlarmBridge bridge;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public AlarmScheduler(PersistenceManager persistence, AgentExecutor agent, AlarmBridge bridge) {
        this.persistence = persistence;
        this.agent = agent;
        this.bridge = bridge;
        this.scheduler = ThreadManager.getInstance().newNamedScheduled("AiAgent-Alarm", 1);
    }

    /** 启动调度循环（每 20 秒检查一次到点任务）。 */
    public synchronized void start() {
        if (running) return;
        running = true;
        scheduler.scheduleWithFixedDelay(this::checkDue, 5, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        AiAgentActivity.debugLog("[Alarm] 调度器已启动（检查间隔 " + CHECK_INTERVAL_SECONDS + "s）");
    }

    /** 停止调度（不关闭共享调度线程池）。 */
    public synchronized void stop() {
        running = false;
        AiAgentActivity.debugLog("[Alarm] 调度器已停止");
    }

    /** 列出所有闹钟（供工具层使用）。 */
    public List<AlarmEntry> listAlarms() {
        return persistence.listAlarms();
    }

    // ==================== 调度核心 ====================

    private void checkDue() {
        if (!running) return;
        try {
            long now = System.currentTimeMillis();
            for (AlarmEntry alarm : persistence.listAlarms(true)) {
                long trigger = computeNextTrigger(alarm, now);
                if (trigger < 0) continue;                 // 无法解析
                if (now >= trigger && alarm.getLastRun() < trigger) {
                    fire(alarm, now);
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Alarm] 检查失败: " + e.toString());
        }
    }

    /**
     * 计算下一次应触发的时间戳。
     * <ul>
     *   <li>{@code once:...}：返回绝对时间戳。</li>
     *   <li>{@code daily:...}：返回「今天 HH:mm」的时间戳（可能已过，由 lastRun 兜底防重复）。</li>
     *   <li>{@code weekly:...}：返回「本周 D HH:mm」的时间戳（可能已过，由 lastRun 兜底防重复）。</li>
     * </ul>
     * @return 时间戳；解析失败返回 -1。
     */
    static long computeNextTrigger(AlarmEntry alarm, long now) {
        String s = alarm.getSchedule();
        if (s == null || s.isEmpty()) return -1;
        String schedule = s.trim();
        if (schedule.startsWith("once:")) {
            return parseDateTime(schedule.substring(5).trim());
        }
        if (schedule.startsWith("daily:")) {
            return atToday(schedule.substring(6).trim(), now);
        }
        if (schedule.startsWith("weekly:")) {
            return atThisWeek(schedule.substring(7).trim(), now);
        }
        return -1;
    }

    private void fire(AlarmEntry alarm, long now) {
        String scope = alarm.getScope();
        AiAgentActivity.debugLog("[Alarm] 触发 #" + alarm.getId() + " scope=" + scope + " task=" + alarm.getTask());
        try {
            if ("REMIND".equalsIgnoreCase(scope)) {
                bridge.sendReply(alarm, "⏰ 提醒：" + alarm.getTask());
            } else if ("EXECQ".equalsIgnoreCase(scope)) {
                final AlarmEntry a = alarm;
                ThreadManager.getInstance().newNamedFixed("Alarm-Exec", 2).submit(() -> {
                    String result = runAgent(a, false);
                    bridge.sendReply(a, result);
                });
            } else if ("EXECS".equalsIgnoreCase(scope)) {
                final AlarmEntry a = alarm;
                ThreadManager.getInstance().newNamedFixed("Alarm-Exec", 2).submit(() -> {
                    String result = runAgent(a, true);
                    bridge.sendReply(a, result);
                });
            } else {
                bridge.sendReply(alarm, "⏰ 提醒：" + alarm.getTask());
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Alarm] 执行失败 #" + alarm.getId() + ": " + e.toString());
            try {
                bridge.sendReply(alarm, "⏰ 闹钟任务执行失败：" + e.getMessage());
            } catch (Exception ignored) {}
        }

        // 更新最后执行时间
        try { persistence.updateAlarmLastRun(alarm.getId(), now); } catch (Exception ignored) {}

        // 一次性任务：触发即完成，自动移除
        if (!alarm.isRepeat()) {
            persistence.removeAlarm(alarm.getId());
            AiAgentActivity.debugLog("[Alarm] 一次性任务 #" + alarm.getId() + " 已完成并移除");
        }
    }

    /** 唤醒 Agent 执行任务（从快照重建上下文）。 */
    private String runAgent(AlarmEntry alarm, boolean execs) {
        String stableSystem = null;
        String dynamicContext = null;
        String model = null;

        // 从快照 JSON 恢复 {stableSystem, dynamicContext, model}
        String snapshot = alarm.getSnapshot();
        if (snapshot != null && !snapshot.trim().isEmpty()) {
            try {
                JsonObject obj = JsonParser.parseString(snapshot).getAsJsonObject();
                if (obj.has("stableSystem") && !obj.get("stableSystem").isJsonNull()) {
                    stableSystem = obj.get("stableSystem").getAsString();
                }
                if (obj.has("dynamicContext") && !obj.get("dynamicContext").isJsonNull()) {
                    dynamicContext = obj.get("dynamicContext").getAsString();
                }
                if (obj.has("model") && !obj.get("model").isJsonNull()) {
                    model = obj.get("model").getAsString();
                }
            } catch (Exception ignored) {
                // 快照损坏时回退默认上下文
            }
        }

        if (model == null || model.isEmpty()) {
            model = execs
                    ? AiConfig.getInstance().getAgentModel()
                    : AiConfig.getInstance().getExecqModel();
        }

        // 任务描述 = 原始任务 + 追加备注
        String task = buildTaskWithNotes(alarm);

        ToolContext ctx = bridge.buildToolContext(alarm);

        String result;
        if (execs) {
            result = agent.executeFcExecs(task, stableSystem, dynamicContext, ctx, model);
        } else {
            result = agent.executeFcExecq(task, stableSystem, dynamicContext, ctx, model);
        }

        if (result == null || result.trim().isEmpty()) {
            return "[闹钟] 任务已执行（无文本输出）";
        }
        return result;
    }

    /** 组装任务描述：原始任务 + 历史追加备注。 */
    private String buildTaskWithNotes(AlarmEntry alarm) {
        StringBuilder sb = new StringBuilder(alarm.getTask() != null ? alarm.getTask() : "");
        String notes = alarm.getNotes();
        if (notes != null && !notes.trim().isEmpty()) {
            try {
                JsonArray arr = JsonParser.parseString(notes).getAsJsonArray();
                if (arr.size() > 0) {
                    sb.append("\n\n【任务历史追加备注】");
                    for (int i = 0; i < arr.size(); i++) {
                        JsonObject note = arr.get(i).getAsJsonObject();
                        long ts = note.has("ts") ? note.get("ts").getAsLong() : 0;
                        String content = note.has("content") ? note.get("content").getAsString() : "";
                        String time = ts > 0
                                ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(ts))
                                : "未知时间";
                        sb.append("\n- [").append(time).append("] ").append(content);
                    }
                }
            } catch (Exception ignored) {
                // notes 损坏时忽略
            }
        }
        return sb.toString();
    }

    // ==================== 时间解析 ====================

    /** 解析 {@code yyyy-MM-dd HH:mm} 为时间戳。 */
    static long parseDateTime(String s) {
        if (s == null) return -1;
        String v = s.trim();
        try {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
            fmt.setLenient(false);
            return fmt.parse(v).getTime();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 解析 {@code HH:mm}，返回「今天该时刻」的时间戳。 */
    private static long atToday(String hm, long now) {
        int[] t = parseHm(hm);
        if (t == null) return -1;
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        cal.set(Calendar.HOUR_OF_DAY, t[0]);
        cal.set(Calendar.MINUTE, t[1]);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 解析 {@code D,HH:mm}（D=1~7，1=周一），返回「本周该时刻」的时间戳。 */
    private static long atThisWeek(String spec, long now) {
        if (spec == null) return -1;
        int comma = spec.indexOf(',');
        if (comma <= 0) return -1;
        int d;
        try {
            d = Integer.parseInt(spec.substring(0, comma).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
        if (d < 1 || d > 7) return -1;
        int[] t = parseHm(spec.substring(comma + 1).trim());
        if (t == null) return -1;

        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        // 目标星期几：d=1(周一)~7(周日) → Calendar.DAY_OF_WEEK 1=周日,2=周一,...,7=周六
        int targetDow = (d % 7) + 1;
        int todayDow = cal.get(Calendar.DAY_OF_WEEK);
        int diff = targetDow - todayDow;
        cal.add(Calendar.DAY_OF_MONTH, diff);
        cal.set(Calendar.HOUR_OF_DAY, t[0]);
        cal.set(Calendar.MINUTE, t[1]);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 解析 {@code HH:mm} 为 [hour, minute]，非法返回 null。 */
    private static int[] parseHm(String hm) {
        if (hm == null) return null;
        String v = hm.trim();
        int colon = v.indexOf(':');
        if (colon <= 0) return null;
        try {
            int h = Integer.parseInt(v.substring(0, colon).trim());
            int m = Integer.parseInt(v.substring(colon + 1).trim());
            if (h < 0 || h > 23 || m < 0 || m > 59) return null;
            return new int[]{h, m};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
