package sair.v4.dev;

import java.io.File;

import sair.v4.Boot;
import sair.v4.kit.Out;

/**
 * 调试面的<b>壳层</b>入口（主人 2026-09-16 裁定 + 同日后追加裁定）。
 *
 * <h3>为什么要单独一层（这是本轮修掉的真实缺陷）</h3>
 * <p>旧口径把两样东西当成"基板组件"：①两个调试口（2660 输出 / 2661 输入）②调试落盘
 * （{@code logs\console-<日期>.log} / {@code talk-<日期>.log}）。于是 {@code Boot.stop()}
 * 顺手把它们一起收了（{@code DebugPort.stop()} → 连落盘一起关）。真机上的后果是三条：</p>
 * <ul>
 *   <li><b>{@code ai/restart} 之后产品像死了一样</b>：停旧基板那一步就把落盘关了，之后
 *       "旧基板已停、开始重新装配"以及整个新装配过程<b>一个字都不落盘</b>（控制台日志停在
 *       "调试输出口已停止"那一毫秒，mtime 再也不前进），外面看起来就是"卡死了、什么也没发生"；</li>
 *   <li><b>调试口拿着过期的基板</b>：口是在 {@code ai/debug on} 时起的（那时只有壳），
 *       {@code DebugPort} 把当时那台"壳基板"存了下来，之后 {@code ai/start} 装出来的是
 *       <b>另一台</b> {@code Boot} —— 口永远看不到活基板（{@code ready=0 substrate=not_started
 *       skills=-1}），输入口的 {@code msg} 永远回 {@code -ERR link_not_ready}；</li>
 *   <li><b>正常启动的产品一行日志都不写</b>：落盘只在 {@code ai/debug on} 时装，而口现在是
 *       命令才开的 → 不开调试口就没有控制台日志（违背"日志只增不删"的项目规矩）。</li>
 * </ul>
 *
 * <h3>现在的分层（写死在这里，别再混）</h3>
 * <ul>
 *   <li><b>壳层（{@link #attach}/{@link #detach}，由 {@code V4Activity} 调）</b>：
 *       绑定"当前基板在哪"这个稳定宿主引用 + 装调试落盘。<b>与 {@code ai/debug on|off} 无关</b>，
 *       也与 {@code ai/start}/{@code ai/restart} 无关 —— 落盘随壳存在而存在
 *       （壳的第一条命令就装上了，之后每次 {@code ai/start}/{@code ai/restart} 幂等重装）；</li>
 *   <li><b>命令层（{@code ai/debug on|off}）</b>：只管两个口。{@code off} 只停端口，
 *       <b>不停落盘、不删任何日志文件</b>；</li>
 *   <li><b>基板层（{@code Boot.stop()}）</b>：什么都不碰调试面 —— 停基板不等于停观测面。</li>
 * </ul>
 *
 * <p><b>为什么不在 {@code V4Activity} 的构造器里 attach</b>（试过，是错的，写下来免得再踩）：
 * 构造期框架还没把这个组件登记好，那时问一次 {@code getDataDir()} 会把"按组件类名拼出来的路径"
 * <b>缓存</b>下来 —— 实测后果是整场会话的数据根变成 {@code <SFW data>\<组件类名>}，
 * 连探针的数据根重定向都被绕过、直接往框架 {@code data\} 下写。
 * 所以落盘的安装点是 {@code ensureReady()}（第一条命令的第一件事）与 {@code kick()}，
 * 那时数据根已经问得准。</p>
 *
 * <p><b>稳定宿主引用</b>：{@link Host} 的唯一实现者是壳 {@code V4Activity}（它 {@code kick()}
 * 时会把字段 {@code boot} 换成新基板）。两个口每次用基板都<b>现问现取</b>
 * （{@link DebugPort#liveBoot()}），所以 {@code status}/{@code substrate=}/{@code ready=} 与
 * 输入口的 {@code msg}/{@code cmd} 永远看到的是<b>活的那一台</b>；{@code ai/start} 之前它
 * 如实报 {@code not_started}（正确，而且不再"冻住"）。</p>
 */
public final class DebugShell {

    private DebugShell() { }

    /**
     * 稳定宿主：谁手里有"当前基板"这个事实，谁实现它。
     * <p>壳 {@code V4Activity} 实现它（{@code boot()} 就是它那个 volatile 字段）；
     * 探针/无壳环境不绑定，此时两个口退回"{@code start()} 时传进来的那台"（见 {@link DebugPort#liveBoot()}）。</p>
     */
    public interface Host {
        /** 当前基板；没有（还没搭壳 / 已卸载）返回 null。 */
        Boot boot();
    }

    private static volatile Host host;
    private static volatile File root;
    /** 落盘是不是已经由壳装上了（幂等判据；{@code ai/debug off} 不会把它翻回去）。 */
    private static volatile boolean spoolInstalled;
    /** 上一次 attach 时落盘是不是已经在写（只影响那一行播报，不参与任何判定）。 */
    private static volatile boolean spoolWasOn = false;

    /**
     * 壳起来 / 换了壳 / 数据根刚算出来：绑定宿主 + 装落盘（<b>幂等</b>，可重复调）。
     *
     * <p>调用点（都在壳里）：{@link sair.v4.V4Activity#ensureReady()}（第一条命令的第一件事）、
     * 每次 {@code kick()}（{@code ai/start}/{@code ai/restart}，换基板时顺手确认落盘还在写）。
     * <b>不在构造器里调</b> —— 原因见类注释（构造期问数据根会把它缓存成按类名拼的路径）。</p>
     *
     * @param h        宿主（可为 null = 只装落盘）
     * @param dataRoot 数据根（可为 null：这一拍还没算出来，下次再装）
     * @param out      输出落点（可为 null = 静默；只在"从没写过 → 现在在写"那一拍播报一行）
     * @return 落盘现在是否在写
     */
    public static boolean attach(Host h, File dataRoot, Out out) {
        if (h != null) host = h;
        if (dataRoot == null) return spoolInstalled;
        File r = dataRoot;
        if (!r.equals(root) || !spoolInstalled) {
            boolean on = false;
            try {
                on = DebugLog.ensure(r);
            } catch (Throwable t) {
                on = false;
            }
            root = r;
            boolean first = !spoolWasOn;
            spoolInstalled = on;
            spoolWasOn = on;
            // 只在"从没写过 → 现在在写"这一拍播报一行（重复 attach 是常态：每次 ai/start 都会来这里）
            if (on && first && out != null) {
                try {
                    out.dim("[debug] 调试落盘已随壳就绪（与 ai/debug 无关，口开着关着都写）："
                            + (DebugLog.dir() == null ? "?" : DebugLog.dir().getAbsolutePath()));
                } catch (Throwable ignored) { }
            }
        }
        return spoolInstalled;
    }

    /**
     * 壳卸载（{@code V4Activity.exit()} = 插件被卸载 / 进程退出）：<b>这里才是调试面的终点</b> ——
     * 停两个口 + 关落盘句柄 + 解绑宿主。
     *
     * <p><b>绝不删日志</b>（{@link DebugLog#stop()} 只关句柄）。停基板不走这里
     * （{@code Boot.stop()} 一个字节都不碰调试面）。</p>
     */
    public static void detach(Out out) {
        try {
            DebugPort.stop();          // 两个口
        } catch (Throwable ignored) { }
        try {
            consoletapDown();
        } catch (Throwable ignored) { }
        try {
            DebugLog.stop();           // 落盘句柄（文件一个都不删）
        } catch (Throwable ignored) { }
        spoolInstalled = false;
        spoolWasOn = false;
        root = null;
        host = null;
        if (out != null) {
            try {
                out.dim("[debug] 调试面已随壳卸载（基板停摆不等于调试面停摆 —— 只有壳卸载才收）。");
            } catch (Throwable ignored) { }
        }
    }

    /**
     * 控制台捕获器（打印代理）也属于壳层：它是"控制台"的一部分，不是基板组件。
     * <p>旧口径在 {@code Th.shutdown()}（{@code Boot.stop()} 的收敛点）里注销它，于是
     * {@code ai/restart} 停旧基板那一瞬间控制台捕获被摘掉，而新装配<b>不会</b>再把它装回来
     * （装配路径上没有任何一处 install）→ 重启之后控制台捕获与落盘一起永久失联。
     * 现在只有壳卸载（{@link #detach}）才注销它。</p>
     */
    private static void consoletapDown() {
        sair.v4.term.SfwOut.uninstallTap();
    }

    /** 当前绑定的宿主（没绑 = null）。两个口用它现取活基板。 */
    public static Host host() { return host; }

    /** 壳登记的数据根（没登记 = null）。 */
    public static File root() { return root; }

    /** 落盘现在是否在写（{@code ai/debug status} 报的就是这个事实）。 */
    public static boolean spoolOn() { return DebugLog.enabled(); }
}
