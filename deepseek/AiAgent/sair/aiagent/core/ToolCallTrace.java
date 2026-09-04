package sair.aiagent.core;

/**
 * ToolCallTrace —— 单次工具调用的轻量遥测模型。
 *
 * <p>与 {@link HarnessTrace} 分离：HarnessTrace 记录一次 Agent/编排执行的整体轨迹，
 * 本类只记录 ToolDispatcher 中每次工具调用的通道、工具名、参数/结果摘要、耗时与结果状态。
 * 由 {@link PersistenceManager} 持久化到 {@code tool_call_traces} 表，供 ai/status 与后续诊断使用。</p>
 */
public class ToolCallTrace {

    /** 唯一调用 ID（时间戳 + 随机后缀）。 */
    public final String traceId;
    /** 工具名。 */
    public final String toolName;
    /** 通道标识：console / execq / execs。 */
    public final String channel;
    /** 参数摘要（已截断）。 */
    public final String arguments;
    /** 结果摘要（已截断）。 */
    public final String result;
    /** 是否成功（未被权限/安全阻断，且未抛异常）。 */
    public final boolean success;
    /** 执行耗时（毫秒）。 */
    public final long durationMs;
    /** 结果状态：ok / blocked / error。 */
    public final String outcome;
    /** 记录时间戳（epoch 毫秒）。 */
    public final long timestamp;

    public ToolCallTrace(String traceId, String toolName, String channel, String arguments,
                         String result, boolean success, long durationMs, String outcome,
                         long timestamp) {
        this.traceId = traceId;
        this.toolName = toolName != null ? toolName : "";
        this.channel = (channel != null && !channel.isEmpty()) ? channel : "console";
        this.arguments = arguments != null ? arguments : "";
        this.result = result != null ? result : "";
        this.success = success;
        this.durationMs = durationMs;
        this.outcome = (outcome != null && !outcome.isEmpty()) ? outcome : (success ? "ok" : "error");
        this.timestamp = timestamp;
    }

    /** 生成唯一调用 ID。 */
    public static String newTraceId() {
        long ts = System.currentTimeMillis();
        int rnd = (int) (Math.random() * 100000);
        return "tool-" + ts + "-" + rnd;
    }

    /** 文本截断（用于落库前压缩存储）。 */
    public static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) : text;
    }

    @Override
    public String toString() {
        return "ToolCallTrace{" + traceId + ", tool=" + toolName + ", channel=" + channel
                + ", outcome=" + outcome + ", success=" + success + ", durationMs=" + durationMs + "}";
    }
}
