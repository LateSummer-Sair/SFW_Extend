package sair.aiagent.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HarnessEval —— Harness 执行轨迹评测器（可观测性）。
 *
 * <p>从 {@link PersistenceManager#listTraces(int)} 拉取最近 N 条 {@link HarnessTrace}，
 * 计算成功率、平均/最长耗时、平均工具调用次数、失败模式分布等指标，输出人类可读的评测报告。
 * 供主人通过命令（如 {@code /harnesseval}）或 debug 排查 Agent 执行质量。</p>
 */
public class HarnessEval {

    private final PersistenceManager pm;

    public HarnessEval(PersistenceManager pm) {
        this.pm = pm;
    }

    /** 拉取最近 N 条轨迹并计算评测统计。 */
    public String evaluate(int limit) {
        List<HarnessTrace> traces = pm != null ? pm.listTraces(limit) : new ArrayList<HarnessTrace>();
        return buildReport(traces);
    }

    /** 从给定轨迹列表计算评测统计（便于测试与复用）。 */
    public static String buildReport(List<HarnessTrace> traces) {
        if (traces == null || traces.isEmpty()) {
            return "[HarnessEval] 暂无执行轨迹，无法评测（请先执行若干次 Agent 任务）。";
        }

        int total = traces.size();
        int success = 0;
        int totalToolCalls = 0;
        long totalDuration = 0;
        long maxDuration = 0;
        Map<String, Integer> modeCount = new LinkedHashMap<>();
        int blocked = 0;

        for (HarnessTrace t : traces) {
            if (t.success) success++;
            totalToolCalls += t.toolCalls;
            totalDuration += t.durationMs;
            if (t.durationMs > maxDuration) maxDuration = t.durationMs;
            if (t.criticVerdict.startsWith("BLOCK")) blocked++;
            String m = t.mode;
            modeCount.put(m, modeCount.getOrDefault(m, 0) + 1);
        }

        double successRate = (double) success / total * 100.0;
        double avgToolCalls = (double) totalToolCalls / total;
        long avgDuration = totalDuration / total;

        StringBuilder sb = new StringBuilder();
        sb.append("===== Harness 执行评测（最近 ").append(total).append(" 次） =====\n");
        sb.append("成功率: ").append(String.format("%.1f", successRate)).append("% (")
          .append(success).append("/").append(total).append(")\n");
        sb.append("平均工具调用次数: ").append(String.format("%.2f", avgToolCalls)).append("\n");
        sb.append("平均耗时: ").append(avgDuration).append(" ms\n");
        sb.append("最长耗时: ").append(maxDuration).append(" ms\n");
        if (blocked > 0) {
            sb.append("被审查阻断: ").append(blocked).append(" 次\n");
        }
        sb.append("通道分布: ");
        int idx = 0;
        for (Map.Entry<String, Integer> e : modeCount.entrySet()) {
            if (idx++ > 0) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append("\n");
        return sb.toString();
    }
}
