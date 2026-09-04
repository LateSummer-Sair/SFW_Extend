package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import sair.aiagent.AiAgentActivity;

/**
 * 定时任务调度器 —— 基于 cron 表达式的任务调度。
 * <p>支持 add/list/remove/enable/disable 命令，任务持久化到 PersistenceManager SQLite。</p>
 * <p>使用简单的周期计算（分钟/小时/天），不依赖第三方 cron 库。</p>
 */
public class CronScheduler {

    /** 任务条目 */
    public static class CronTask {
        public final int id;
        public final String cronExpr;
        public final String command;
        public final String description;
        public volatile boolean enabled;
        public final long createdAt;

        public CronTask(int id, String cronExpr, String command, String description, boolean enabled, long createdAt) {
            this.id = id;
            this.cronExpr = cronExpr;
            this.command = command;
            this.description = description;
            this.enabled = enabled;
            this.createdAt = createdAt;
        }

        @Override
        public String toString() {
            String status = enabled ? "[启用]" : "[禁用]";
            return "#" + id + " " + status + " " + cronExpr + " | " + command
                    + (description != null && !description.isEmpty() ? " (" + description + ")" : "");
        }
    }

    private final ScheduledExecutorService scheduler =
            ThreadManager.getInstance().newNamedScheduled("AiAgent-Cron", 1);

    private final PersistenceManager persistence;
    private final Map<Integer, ScheduledFuture<?>> futures = new ConcurrentHashMap<>();
    private volatile Runnable taskCallback;

    public CronScheduler(PersistenceManager persistence) {
        this.persistence = persistence;
    }

    /** 设置任务触发回调 */
    public void setTaskCallback(Runnable callback) {
        this.taskCallback = callback;
    }

    /** 启动时加载所有已启用任务 */
    public void start() {
        List<CronTask> tasks = persistence.listCronTasks();
        for (CronTask task : tasks) {
            if (task.enabled) {
                scheduleTask(task);
            }
        }
        AiAgentActivity.debugLog("[CronScheduler] 已加载 " + tasks.size() + " 个任务，启用 " + futures.size() + " 个");
    }

    /** 停止所有定时任务（不关闭共享调度线程池）。 */
    public void stop() {
        for (ScheduledFuture<?> f : futures.values()) {
            f.cancel(false);
        }
        futures.clear();
    }

    /** 添加任务 */
    public String addTask(String cronExpr, String command, String description) {
        long[] period = parseCronToPeriod(cronExpr);
        if (period == null) {
            return "[schedule] 无法解析 cron 表达式: " + cronExpr + "\n"
                    + "支持格式: */N (每N分钟) | *:N (N分钟) | H:N,M (每天N点M分) | H:N (每天N点)";
        }

        int id = persistence.addCronTask(cronExpr, command, description);
        if (id < 0) return "[schedule] 添加任务失败";

        CronTask task = new CronTask(id, cronExpr, command, description, true, System.currentTimeMillis());
        scheduleTask(task);

        long periodMs = period[1];
        String periodDesc = periodMs < 3600000 ? (periodMs / 60000) + "分钟" :
                (periodMs / 3600000) + "小时";
        return "[schedule] 任务 #" + id + " 已添加，每 " + periodDesc + " 执行: " + command;
    }

    /** 列出所有任务 */
    public String listTasks() {
        List<CronTask> tasks = persistence.listCronTasks();
        if (tasks.isEmpty()) return "[schedule] 暂无定时任务";
        StringBuilder sb = new StringBuilder("[schedule] 定时任务列表:\n");
        for (CronTask t : tasks) {
            sb.append(t.toString()).append("\n");
        }
        return sb.toString().trim();
    }

    /** 移除任务 */
    public String removeTask(int id) {
        if (persistence.removeCronTask(id)) {
            ScheduledFuture<?> f = futures.remove(id);
            if (f != null) f.cancel(false);
            return "[schedule] 任务 #" + id + " 已移除";
        }
        return "[schedule] 任务 #" + id + " 不存在";
    }

    /** 启用任务 */
    public String enableTask(int id) {
        CronTask task = persistence.getCronTask(id);
        if (task == null) return "[schedule] 任务 #" + id + " 不存在";
        if (task.enabled) return "[schedule] 任务 #" + id + " 已处于启用状态";

        persistence.setCronTaskEnabled(id, true);
        task.enabled = true;
        scheduleTask(task);
        return "[schedule] 任务 #" + id + " 已启用: " + task.cronExpr;
    }

    /** 禁用任务 */
    public String disableTask(int id) {
        CronTask task = persistence.getCronTask(id);
        if (task == null) return "[schedule] 任务 #" + id + " 不存在";
        if (!task.enabled) return "[schedule] 任务 #" + id + " 已处于禁用状态";

        persistence.setCronTaskEnabled(id, false);
        task.enabled = false;
        ScheduledFuture<?> f = futures.remove(id);
        if (f != null) f.cancel(false);
        return "[schedule] 任务 #" + id + " 已禁用: " + task.cronExpr;
    }

    private void scheduleTask(CronTask task) {
        long[] period = parseCronToPeriod(task.cronExpr);
        if (period == null) return;

        // 取消旧调度，避免重复执行
        ScheduledFuture<?> old = futures.remove(task.id);
        if (old != null) old.cancel(false);

        long initialDelay = period[0];
        long repeatPeriod = period[1];

        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                AiAgentActivity.debugLog("[CronScheduler] 执行任务 #" + task.id + ": " + task.command);
                if (taskCallback != null) {
                    taskCallback.run();
                }
                // 执行 SFW 命令
                sair.sys.SairCons.runner(false, task.command);
                // 更新最后执行时间
                persistence.updateCronTaskLastRun(task.id);
            } catch (Exception e) {
                AiAgentActivity.debugLog("[CronScheduler] 任务 #" + task.id + " 失败: " + e.toString());
            }
        }, initialDelay, repeatPeriod, TimeUnit.MILLISECONDS);

        futures.put(task.id, future);
    }

    /**
     * 解析简单 cron 表达式为 [初始延迟毫秒, 重复周期毫秒]。
     * 支持格式:
     *   - "star/N" 或 "*:N": 每 N 分钟
     *   - "H:N": 每天 N 点
     *   - "H:N,M": 每天 N 点 M 分
     * @return long[2] {initialDelayMs, periodMs}，解析失败返回 null
     */
    static long[] parseCronToPeriod(String expr) {
        if (expr == null) return null;
        String e = expr.trim();

        // */N 或 *:N: 每 N 分钟
        if (e.startsWith("*/") || e.startsWith("*:")) {
            try {
                int min = Integer.parseInt(e.substring(2));
                if (min <= 0 || min > 1440) return null;
                return new long[]{min * 60_000L, min * 60_000L};
            } catch (NumberFormatException ignored) { return null; }
        }

        // H:N 或 H:N,M: 每天 N 点 M 分
        if (e.startsWith("H:") || e.startsWith("h:")) {
            try {
                String time = e.substring(2);
                int colon = time.indexOf(',');
                int hour, minute = 0;
                if (colon > 0) {
                    hour = Integer.parseInt(time.substring(0, colon));
                    minute = Integer.parseInt(time.substring(colon + 1));
                } else {
                    hour = Integer.parseInt(time);
                }
                if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return null;

                long now = System.currentTimeMillis();
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.set(java.util.Calendar.HOUR_OF_DAY, hour);
                cal.set(java.util.Calendar.MINUTE, minute);
                cal.set(java.util.Calendar.SECOND, 0);
                cal.set(java.util.Calendar.MILLISECOND, 0);

                long target = cal.getTimeInMillis();
                if (target <= now) target += 24 * 3600_000L;

                return new long[]{target - now, 24 * 3600_000L};
            } catch (NumberFormatException ignored) { return null; }
        }

        // 纯数字: 解释为分钟
        try {
            int min = Integer.parseInt(e);
            if (min > 0 && min <= 1440) return new long[]{min * 60_000L, min * 60_000L};
        } catch (NumberFormatException ignored) {}

        return null;
    }
}
