package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;

/**
 * ContextPipeline —— 上下文状态管线（五级压缩）。
 *
 * <p>把 Agent / QQ 通道动态上下文（记忆、笔记、印象、技能、对话历史、情绪等）的
 * 组装与压缩收敛为统一的五级管线：</p>
 * <ol>
 *   <li><b>L1 Token 预算分配</b> —— 按总预算与各层权重，动态分配每层可占用的字符数
 *       （{@link #allocateBudget(int, int[])}）。</li>
 *   <li><b>L2 单项截断/裁剪</b> —— 超长单项截断（{@link #truncate(String, int)}），
 *       总上下文超预算时按头部优先裁剪尾部（{@link #enforceBudget(String, int)}）。</li>
 *   <li><b>L3 LLM 摘要压缩</b> —— 委托 {@link HistoryCompressor}（保留最近轮次 + 摘要旧历史）。</li>
 *   <li><b>L4 重要性加权/剪枝</b> —— 委托 {@link MemoryLifecycleManager} 的分级清理与数量阈值压缩。</li>
 *   <li><b>L5 FTS5 相关性检索</b> —— 只注入命中的记忆/笔记/技能/印象（委托 PersistenceManager 的
 *       {@code buildNotesContext/buildMemoryContext/buildCorrectionsContext} 等）。</li>
 * </ol>
 *
 * <p>L3/L4/L5 已在 {@link AgentExecutor} / {@link sair.aiagent.onebot.QQPromptBuilder} 中
 * 分散实现；本类统一提供 L1/L2 的确定性预算与截断能力，作为两个 {@code buildDynamicContext}
 * 的统一收口。</p>
 */
public class ContextPipeline {

    /** 动态上下文默认总预算（字符数，约 4000 token）。 */
    public static final int DEFAULT_BUDGET = 6000;
    /** 单个上下文块默认上限（字符数）。 */
    public static final int DEFAULT_BLOCK_MAX = 2000;
    /** 预算超限时的裁剪标记。 */
    private static final String TRIM_MARK = "\n...[上下文超预算已裁剪]";

    private ContextPipeline() {}

    /**
     * L1：按权重比例分配各层预算。
     *
     * @param totalChars 总字符预算
     * @param weights    各层权重（与层一一对应，权重越大分配越多）
     * @return 与 weights 等长的各层字符预算（不足 1 的层至少分得 1）
     */
    public static int[] allocateBudget(int totalChars, int[] weights) {
        if (weights == null || weights.length == 0) return new int[0];
        int totalWeight = 0;
        for (int w : weights) totalWeight += Math.max(0, w);
        int[] budgets = new int[weights.length];
        if (totalWeight <= 0) {
            int base = totalChars / weights.length;
            for (int i = 0; i < budgets.length; i++) budgets[i] = base;
            return budgets;
        }
        int allocated = 0;
        for (int i = 0; i < budgets.length; i++) {
            budgets[i] = (int) ((long) totalChars * Math.max(0, weights[i]) / totalWeight);
            allocated += budgets[i];
        }
        // 余数补给第一层
        budgets[0] += (totalChars - allocated);
        return budgets;
    }

    /**
     * L2：截断单个上下文块到指定最大字符数（超出末尾追加省略标记）。
     */
    public static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (maxChars <= 0) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars) + "...";
    }

    /**
     * L2：对已组装的总上下文执行预算强制（头部优先，裁剪尾部）。
     * <p>关键上下文（当前消息、身份、群上下文）通常位于头部，故保留头部、裁剪尾部。</p>
     */
    public static String enforceBudget(String context, int maxChars) {
        if (context == null) return "";
        if (maxChars <= 0) return "";
        if (context.length() <= maxChars) return context;
        int markLen = TRIM_MARK.length();
        if (maxChars <= markLen) {
            // 预算过小（甚至小于裁剪标记），直接硬截断，不加标记
            return context.substring(0, maxChars);
        }
        return context.substring(0, maxChars - markLen) + TRIM_MARK;
    }

    /** 粗略 token 估算（CJK 约 0.6 token/字，其它 0.25 token/字）。 */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cjk = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (isCjk(c)) cjk++; else other++;
        }
        return (int) (cjk * 0.6 + other * 0.25);
    }

    /** 把字符预算换算为大致 token 预算（用于日志/调试）。 */
    public static int charsToTokens(int chars) {
        return (int) (chars * 0.4);
    }

    /** L3 摘要压缩的委托入口（保留最近轮次 + LLM 摘要旧历史）。 */
    public static String compressHistory(List<sair.aiagent.model.ChatMessage> messages, HistoryCompressor compressor) {
        if (compressor == null || messages == null || messages.isEmpty()) return null;
        try {
            return compressor.compress(messages);
        } catch (Exception e) {
            return null;
        }
    }

    /** L4 重要性加权排序辅助：按 importance 降序，importance 相同按时间戳新→旧。 */
    public static List<long[]> rankByImportance(List<long[]> rows) {
        // rows: [id, importance, timestamp, ...]，按 importance desc、timestamp desc 排序
        if (rows == null) return new ArrayList<>();
        List<long[]> sorted = new ArrayList<>(rows);
        sorted.sort((a, b) -> {
            if (b[1] != a[1]) return Long.compare(b[1], a[1]);
            return Long.compare(b[2], a[2]);
        });
        return sorted;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x2E80 && c <= 0x2EFF) || (c >= 0x3000 && c <= 0x303F)
            || (c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)
            || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)
            || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xFF00 && c <= 0xFFEF);
    }
}
