package sair.aiagent.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上下文与截断统计（只读观测）。
 * <p>
 * 定位：把「提示词各段多大」「这一轮有多少内容被截断/丢弃」变成可查的数字。
 * 之前这些信息完全不存在 —— 内容被截断时既不计数也不上报，只能靠翻日志猜，
 * 于是「AI 说没看到图片」「文件明明有 20 万字却只读到一半」这类问题无法定位。
 * </p>
 * <p>全部为计数/采样，热路径只做一次 {@code AtomicLong.incrementAndGet()}，可忽略开销。</p>
 */
public final class ContextStats {

    private ContextStats() {}

    /** 累计计数器（key → 次数）。 */
    private static final ConcurrentHashMap<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();

    /** 最近一次提示词各段字符数（key → 字符数），供 ai/ctx 展示。 */
    private static final ConcurrentHashMap<String, Integer> LAST_SIZES = new ConcurrentHashMap<>();

    /** 最近一次提示词总字符数与估算 token。 */
    private static volatile int lastPromptChars = 0;
    private static volatile long lastPromptAt = 0L;

    // 计数器键（统一常量，避免各处手写字符串打错）
    /** 工具结果被 harness 结果上限截断。 */
    public static final String C_RESULT_TRUNCATED = "结果截断(harness上限)";
    /** 单次请求累计工具结果超限后的压缩。 */
    public static final String C_TOOL_RESULT_TRIMMED = "工具结果压缩(累计超限)";
    /** 群上下文超字符预算，丢弃了更旧的消息。 */
    public static final String C_HISTORY_TRIMMED = "群上下文裁剪(超预算)";
    /** 折叠消息正文超长截断。 */
    public static final String C_FOLD_TRUNCATED = "折叠正文截断";
    /** 附加上限导致图片未送入视觉。 */
    public static final String C_IMAGE_DROPPED = "图片丢弃(超上限)";
    /** 图片超 3MB 走 File API 上传。 */
    public static final String C_IMAGE_OVERSIZE = "图片超3M走FileAPI";
    /** 子 Agent 结果被截断。 */
    public static final String C_SUBAGENT_TRUNCATED = "子Agent结果截断";
    /** 已到文件末尾（分段读取边界）。 */
    public static final String C_READ_EOF = "文件读到末尾";
    /** 到达连续失败上限而终止任务。 */
    public static final String C_FAILURE_ABORT = "连续失败终止任务";
    /** 上下文过长导致进入压缩模式。 */
    public static final String C_CONTEXT_OVERFLOW = "上下文溢出进入压缩模式";

    /** 计数 +1。 */
    public static void count(String key) {
        if (key == null) return;
        COUNTERS.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    /** 计数 +n（n<=0 时忽略）。 */
    public static void count(String key, long n) {
        if (key == null || n <= 0) return;
        COUNTERS.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(n);
    }

    /** 记录某一段的字符数（覆盖式，只保留最近一次）。 */
    public static void size(String block, int chars) {
        if (block == null) return;
        LAST_SIZES.put(block, chars);
    }

    /** 记录本轮提示词总字符数。 */
    public static void prompt(int totalChars) {
        lastPromptChars = totalChars;
        lastPromptAt = System.currentTimeMillis();
    }

    /** 生成可读报告。 */
    public static String report() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== 上下文分段字符数（最近一次请求） ===\n");
        if (LAST_SIZES.isEmpty()) {
            sb.append("  (尚无数据)\n");
        } else {
            java.util.List<String> keys = new java.util.ArrayList<>(LAST_SIZES.keySet());
            java.util.Collections.sort(keys);
            int sum = 0;
            for (String k : keys) {
                int v = LAST_SIZES.get(k);
                if (!k.startsWith("总")) sum += v;
                sb.append(String.format("  %-22s %8d 字符  (~%d token)%n", k, v, v / 2));
            }
            sb.append(String.format("  %-22s %8d 字符  (~%d token)%n", "合计", sum, sum / 2));
        }
        if (lastPromptChars > 0) {
            sb.append("  最近一次提示词总量: ").append(lastPromptChars).append(" 字符 (~")
              .append(lastPromptChars / 2).append(" token)，于 ")
              .append(new java.text.SimpleDateFormat("MM-dd HH:mm:ss").format(new java.util.Date(lastPromptAt)))
              .append("\n");
        }
        sb.append("\n=== 累计截断/丢弃计数 ===\n");
        if (COUNTERS.isEmpty()) {
            sb.append("  (尚无数据)\n");
        } else {
            java.util.List<String> keys = new java.util.ArrayList<>(COUNTERS.keySet());
            java.util.Collections.sort(keys);
            for (String k : keys) {
                sb.append(String.format("  %-24s %d%n", k, COUNTERS.get(k).get()));
            }
        }
        return sb.toString();
    }

    /** 清零（供调试）。 */
    public static void reset() {
        COUNTERS.clear();
        LAST_SIZES.clear();
        lastPromptChars = 0;
        lastPromptAt = 0L;
    }
}
