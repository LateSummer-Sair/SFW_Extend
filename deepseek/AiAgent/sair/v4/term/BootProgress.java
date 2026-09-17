package sair.v4.term;

import sair.v4.Boot;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 装配进度播报（基板⑨）：把 {@link Boot.InitListener} 的回调打成控制台看得见的一行行。
 *
 * <h3>为什么要有它</h3>
 * <p>基板装配（19 步，最慢的是运行期编译技能）以前是"哑的"：SFW 控制台只看见窗口没反应。
 * 现在装配跑在<b>自己的守护线程</b>上（{@code V4Activity} 的 {@code v4-boot}），
 * 这里负责把每一步的事实打出来，让"正在装什么、装到第几步、哪一步最慢"一眼可见。</p>
 *
 * <h3>线程与输出的契约</h3>
 * <ul>
 *   <li>所有回调都在装配线程上发生，因此打印是<b>从我们自己的线程直接走 {@link Out}</b>
 *       （生产环境 = {@code SfwOut} → {@code SairCons}：框架的打印是"调用线程直接执行 + printLock
 *       串行化"，不经过 EDT，也不阻塞 EDT）；</li>
 *   <li>本类<b>不 import 任何 Swing 类型</b>，也绝不调用 {@code SwingUtilities.invokeLater/invokeAndWait}。</li>
 * </ul>
 *
 * <h3>行格式（固定，便于 grep）</h3>
 * <pre>
 * [v4] 3/19 提示词 … 12ms
 * [v4]   编译技能 12/26 记忆 … 313ms
 * [v4] 基板就绪：技能 26 个（工具 23），耗时 3875 ms（最慢：技能编译 2635 ms）
 * </pre>
 */
public final class BootProgress implements Boot.InitListener {

    private final Out out;
    /** 装配观看起点（第一行播报时定，近似 init 的起点）。 */
    private final long t0 = System.currentTimeMillis();

    /** 最慢的命名步骤。 */
    private String slowStep = "";
    private long slowStepMs = -1L;
    /** 技能编译阶段（子进度）的累计耗时与技能数。 */
    private long compileMs = 0L;
    private int compileCount = 0;
    private int compileTotal = 0;
    /** 最后一步跑完的墙钟时间（汇总行用它算"耗时"）。 */
    private volatile long lastStepAt = 0L;

    public BootProgress(Out out) {
        this.out = out;
    }

    @Override
    public void step(int n, int total, String name, long ms) {
        lastStepAt = System.currentTimeMillis();
        if (ms >= slowStepMs) {
            slowStepMs = ms;
            slowStep = Str.nz(name);
        }
        print("[v4] " + n + "/" + total + " " + name + " … " + ms + "ms\n", Out.Tone.DIM);
    }

    @Override
    public void compile(int i, int total, String name, long ms) {
        compileCount = i;
        compileTotal = total;
        compileMs += Math.max(0L, ms);
        print("[v4]   编译技能 " + i + "/" + total + " " + name + " … " + ms + "ms\n", Out.Tone.DIM);
    }

    /**
     * 装配结束的汇总行（在装配线程上、{@code init} 返回之后打）。
     * <p>成功：{@code [v4] 基板就绪：技能 N 个（工具 M），耗时 X ms（最慢：<步骤> Y ms）}；
     * 有失败步骤时改为指出失败于哪一步（控制台其余命令照常可用）。</p>
     */
    public void finished(Boot b) {
        long total = totalMs();
        int skills = b == null || b.skills() == null ? 0 : b.skills().size();
        int tools = b == null || b.skills() == null ? 0 : b.skills().toolCount();
        String slow = slowestName(b);
        long slowMs = slowestMs(b);
        boolean degraded = b != null && b.degraded();
        if (degraded) {
            print("[v4] 装配结束（有失败步骤「" + b.failedStep() + "」）：技能 " + skills + " 个（工具 "
                    + tools + "），耗时 " + total + " ms（最慢：" + slow + " " + slowMs + " ms）"
                    + " —— help/status/tools/config 照常可用\n", Out.Tone.WARN);
            return;
        }
        if (b != null && b.skillsCompiling()) {
            print("[v4] 基板就绪：技能后台编译中，耗时 " + total + " ms（最慢：" + slow + " " + slowMs
                    + " ms）—— 技能工具会陆续出现（status 看 skills.compiling）\n", Out.Tone.OK);
            return;
        }
        print("[v4] 基板就绪：技能 " + skills + " 个（工具 " + tools + "），耗时 " + total
                + " ms（最慢：" + slow + " " + slowMs + " ms）\n", Out.Tone.OK);
    }

    /** 技能后台编译完成的那一行（只在上面的汇总行说过"后台编译中"时才有意义）。 */
    public void skillsFinished(Boot b) {
        int skills = b == null || b.skills() == null ? 0 : b.skills().size();
        int tools = b == null || b.skills() == null ? 0 : b.skills().toolCount();
        long ms = b == null ? compileMs : b.skillsCompileMs();
        print("[v4] 技能就绪：技能 " + skills + " 个（工具 " + tools + "），编译耗时 " + ms
                + " ms（后台线程，未阻塞基板）\n", Out.Tone.OK);
    }

    /** 装配炸到调用方（正常不会发生：init 自己收敛异常）时的最后一行。 */
    public void failed(Boot b, Throwable t) {
        print("[v4] 装配异常（已兜住，控制台继续可用）：" + t
                + (b == null || !b.degraded() ? "" : " —— 失败于「" + b.failedStep() + "」")
                + "\n", Out.Tone.ERR);
    }

    /** 装配总耗时（毫秒）：优先用 Boot 自己的起点，退化到本对象的起点。 */
    public long totalMs() {
        long base = lastStepAt > 0 ? lastStepAt : System.currentTimeMillis();
        return base - t0;
    }

    /** 最慢的那一段叫什么（技能编译单独计时，比任何步骤都慢时优先报它）。 */
    public String slowestName(Boot b) {
        long c = compileMsOf(b);
        // 同步模式（skillsAsync=false）：技能编译就发生在「技能扫描」这一步里面 ——
        // 这时报"技能编译"更准，也避免"内层测量比外层少几毫秒"导致同一件事一会儿叫这个一会儿叫那个
        // （探针实测过：2905ms 的步骤 vs 2902ms 的编译，判据一抖名字就变了）。
        if (SYNC_COMPILE_STEP.equals(slowStep)) return c >= 0 ? "技能编译" : slowStep;
        if (c >= 0 && c >= slowStepMs) return "技能编译";
        return Str.blank(slowStep) ? "（无）" : slowStep;
    }

    /** 最慢那一段的耗时（毫秒）。 */
    public long slowestMs(Boot b) {
        long c = compileMsOf(b);
        if (SYNC_COMPILE_STEP.equals(slowStep)) return c >= 0 ? c : Math.max(0L, slowStepMs);
        if (c >= 0 && c >= slowStepMs) return c;
        return Math.max(0L, slowStepMs);
    }

    /** 同步模式下"技能编译"所在的那一步。 */
    private static final String SYNC_COMPILE_STEP = "技能扫描";

    /** 技能编译耗时：优先取 Boot 记的（同步=步骤⑯耗时 / 后台=后台线程耗时），没有就用子进度累加。 */
    private long compileMsOf(Boot b) {
        long c = b == null ? -1L : b.skillsCompileMs();
        if (c >= 0) return c;
        return compileCount > 0 ? compileMs : -1L;
    }

    /** 本次扫描过的技能数与总数（诊断/探针用）。 */
    public int compileCount() { return compileCount; }

    /** 本次扫描的技能目录总数。 */
    public int compileTotal() { return compileTotal; }

    private void print(String text, Out.Tone tone) {
        if (out == null) return;
        try {
            out.print(text, tone);
        } catch (Throwable ignored) {
            // 打印失败不影响装配（观测面不是关键路径）
        }
    }
}
