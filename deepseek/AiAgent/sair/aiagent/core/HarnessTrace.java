package sair.aiagent.core;

/**
 * HarnessTrace —— 单次 Agent/编排执行的轨迹记录模型（可观测性）。
 *
 * <p>由 {@link FunctionCallingBridge} 在一次 Function Calling 循环结束后埋点生成，
 * 由 {@link PersistenceManager} 持久化到 {@code harness_traces} 表，供
 * {@link HarnessEval} 做成功率 / 平均轮次 / 失败分布等评测统计。</p>
 *
 * <p>字段均为 public final，便于跨包直接读取；模型本身无副作用（不触碰数据库）。</p>
 */
public class HarnessTrace {

    /** 唯一任务 ID（时间戳 + 随机后缀）。 */
    public final String taskId;
    /** 通道/编排模式标识：console / execq / pipeline / fanout / expert。 */
    public final String mode;
    /** 任务描述（已截断，避免占用过多存储）。 */
    public final String task;
    /** 工具调用总次数。 */
    public final int toolCalls;
    /** 是否成功（未触发连续失败终止 / 未被停止）。 */
    public final boolean success;
    /** 执行耗时（毫秒）。 */
    public final long durationMs;
    /** 审查者最终判定（PASS / BLOCK: 原因 / 空字符串=未启用审查）。 */
    public final String criticVerdict;
    /** 记录时间戳（epoch 毫秒）。 */
    public final long timestamp;

    public HarnessTrace(String taskId, String mode, String task, int toolCalls,
                        boolean success, long durationMs, String criticVerdict, long timestamp) {
        this.taskId = taskId;
        this.mode = (mode != null && !mode.isEmpty()) ? mode : "console";
        this.task = task != null ? task : "";
        this.toolCalls = toolCalls;
        this.success = success;
        this.durationMs = durationMs;
        this.criticVerdict = (criticVerdict != null) ? criticVerdict : "";
        this.timestamp = timestamp;
    }

    /** 生成唯一任务 ID（时间戳 + 随机后缀）。 */
    public static String newTaskId() {
        long ts = System.currentTimeMillis();
        int rnd = (int) (Math.random() * 100000);
        return "trace-" + ts + "-" + rnd;
    }

    /** 任务描述截断（用于落库前压缩存储）。 */
    public static String truncateTask(String task, int maxLen) {
        if (task == null) return "";
        return task.length() > maxLen ? task.substring(0, maxLen) : task;
    }

    @Override
    public String toString() {
        return "HarnessTrace{" + taskId + ", mode=" + mode + ", toolCalls=" + toolCalls
                + ", success=" + success + ", durationMs=" + durationMs
                + ", verdict=" + criticVerdict + "}";
    }
}
