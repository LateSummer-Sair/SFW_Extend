package sair.v4;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import sair.user.Activity;
import sair.v4.kit.Out;
import sair.v4.kit.Th;
import sair.v4.term.BootProgress;
import sair.v4.term.Cmd;
import sair.v4.term.ConsoleTap;
import sair.v4.term.SfwOut;

/**
 * AiAgent V4 的 SFW 插件入口。
 *
 * <h3>与框架的契约</h3>
 * <ul>
 *   <li>jar 的 MANIFEST 里 {@code ACT: sair.v4.V4Activity}；</li>
 *   <li>控制台命令行 {@code <jar名>/<函数> <参数>} 落到 {@link #main(String, String)}；</li>
 *   <li>数据目录 = {@code SFW data/<dataDir() 返回值>}；</li>
 *   <li>{@code exit()} 由框架卸载时调用，这里收敛基板资源。</li>
 * </ul>
 *
 * <h3>装配绝不在调用线程上跑（真实卡界面的那一条）</h3>
 * <p>框架的控制台回车路径是 {@code ClicksAct.clicks_enter} → {@code SairCons.runner(true, cmd)}
 * （<b>EDT 上直接执行</b>），启动路径是 {@code Main.autoRun} 里的 {@code /ir "<SFW-BOT.ir>"}。
 * 基板装配（{@code Boot.init()}：开 120MB 库 + 建索引/FTS + 运行期编译 20+ 个技能 + 起 NapCat/中转/tick）
 * 要好几秒 —— 以前它同步跑在调用线程上，于是"开机像没有界面、敲命令窗口卡死"。</p>
 * <p>现在：{@link #main} / {@link #help} / {@link #ensureReady()} <b>一律立即返回</b>，
 * 装配交给自己的守护线程 {@code v4-boot}（{@link Th#daemon(String, Runnable)}）。
 * <b>但装配只由 {@code ai/start}（已在跑则 {@code ai/restart}）触发</b> —— 第一条命令不再顺手把
 * 基板踢起来（主人 2026-09-16 的口径：{@code ai/start} 之前基板不工作，其余命令只改配置文件）。
 * <b>本类（以及整个 {@code sair.v4} 基板）不 import 任何 Swing 类型，也不调用
 * {@code SwingUtilities.invokeLater/invokeAndWait}</b>；装配进度是从 {@code v4-boot} 线程
 * 直接走 {@code SairCons} 打出去的（框架的打印是"调用线程直接执行 + printLock 串行化"，
 * 不经 EDT、也不占用 EDT）。</p>
 *
 * <h3>加载期间的命令面</h3>
 * <ul>
 *   <li>{@code help/status/tools/config}：<b>立刻回话</b>。{@code status} 第一行是
 *       {@code 加载中：第 x/19 步 <步骤名>，已耗时 …ms}，装配失败时照旧打出失败步骤台账；</li>
 *   <li>{@code chat}：未就绪时回一句 {@code prompts/tools-index.md} 的「初始化中文案」
 *       （缺键只报缺键，基板不编兜底话）；</li>
 *   <li>装配失败：{@code help/status/tools/config} 照常可用并指明失败步骤（永不失联，不回归）。</li>
 * </ul>
 */
public class V4Activity extends Activity implements Cmd.Life, sair.v4.dev.DebugShell.Host {

    /** 产品版本号（发布口径的唯一一处；启动横幅与文档里的版本以它为准）。 */
    public static final String VERSION = "V4.2";

    private volatile Boot boot;
    private volatile Cmd cmd;
    /** 装配阶段捕获到的异常（诊断用；正常时为空串）。 */
    private volatile String bootError = "";
    /**
     * "同时只允许一条装配/重启线程"的名额。
     * <p>装配中为 true：重复 {@code start}/{@code restart} 会被明确拒绝（不并发）；
     * <b>装配一就绪就变回 false</b> —— 由 {@link Boot#init(Boot.InitListener, Runnable)} 的
     * 就绪钩子在"就绪对外可见之前"交还（见 {@link #releaseBootSlot()}），
     * 之后的 {@code start} 回"已在运行"、{@code restart} 才进得来。</p>
     * <p>装配线程的 {@code finally} 里保留同样的交还：那是异常/早退路径的兜底
     * （{@code set(false)} 幂等，正常路径上是重复交还一次）。</p>
     */
    private final AtomicBoolean bootKicked = new AtomicBoolean(false);
    /** 装配线程的名字与 id（探针断言"加载不在 EDT/不在调用线程上"用）。 */
    private volatile String bootThreadName = "";
    private volatile long bootThreadId = -1L;
    /** 卸载标记：装配还在跑时 {@link #exit()} 不当场抢锁，交给装配线程收口。 */
    private volatile boolean unloading = false;
    /** exit() 之后不再自动重建装配（框架卸载后可能还会问一次 help/status）。 */
    private volatile boolean unloaded = false;
    /** 当前这次装配是不是"重启"（status 措辞用）。 */
    private volatile boolean restarting = false;
    /** 已经跑过的装配次数（start/restart 各算一次；诊断用）。 */
    private final AtomicLong bootCount = new AtomicLong();

    /**
     * 插件在"加载插件"阶段被框架实例化（<b>早于</b> {@code autorun.ir} 与我们的外置 {@code .ir} 脚本）。
     *
     * <p><b>构造期什么也不做（甲方令，T12-R3d 定稿）</b>：<b>不注册打印代理、不扣住输出、不碰框架控制台</b>。
     * 这里原先有一句 {@code ConsoleTap.install(null, true)}（注册代理 + 先扣住输出），已<b>整段删除</b> ——
     * 构造期劫持框架的控制台是错的：那时框架自己的 {@code autorun.ir} 正在跑，而"扣住"意味着
     * <b>框架打印全部进我们的队列、一个字节都不落盘，直到有人放行</b>；构造期又<b>无法保证随后一定有我们的命令</b>
     * （不带外置 {@code .ir} 启动时 {@code main()} 根本不会被调用）⇒ 控制台会永久空框。</p>
     *
     * <p>代理改在"<b>真的开始跑</b>"的路径上装（见 {@link sair.v4.term.SfwOut} 的构造与装配第⑮步），
     * 那两条都不在加载期/构造期。构造期唯一的责任人就是"别碰框架的东西"。</p>
     *
     * <p>装不上（无界面环境 / 框架不可用）就静默降级：绝不影响插件加载。</p>
     */
    public V4Activity() {
        // ★构造期**一个字节都不碰框架的控制台**（甲方令）：
        // 不 install（不注册打印代理）、不进"扣住"状态、不动那份 JTextPane。
        // 历史教训见下（那是另一件事，别再合并进来）。
        // ★ 这里**绝不**碰 getDataDir()（试过，是错的）：构造期框架还没把这个组件登记好，
        //   过早问一次数据根会把"按组件名拼出来的路径"缓存下来 —— 实测后果是整场会话的数据根
        //   变成 `<SFW data>\<组件类名>`（连探针重定向都被绕过，直接往框架 data\ 下写）。
        //   落盘是壳层设施但不是"构造期设施"：它由 ensureReady()（第一条命令的第一件事）与
        //   kick()（每次 ai/start / ai/restart）装 —— 那时数据根已经问得准了。见 DebugShell.attach。
    }

    @Override
    public Object main(String funcName, String args) {
        try {
            ensureReady();          // 只搭"壳"（Boot/Cmd）+ 起一次装配线程；立即返回
            // 第一条命令 = 框架那段启动脚本（autorun.ir：/clear、/print-ti、/help、/hide、/show）已经过去。
            // 甲方令（T12-R3d）之后<b>构造期不再注册代理、也不再扣住</b>，所以这一句现在是<b>空操作</b>
            // （没人扣住 ⇒ releaseHold 立即 return）；保留它是因为放行是幂等的：多调一次无害，
            // 少调一次就是控制台永久空框 —— 宁可多调（甲方令：保留无害）。
            ConsoleTap.releaseHold();
            Cmd c = cmd;
            if (c == null) {
                // ensureReady 保证 cmd 非空；真发生了也绝不让框架层收到异常
                SfwOut out = new SfwOut();
                out.err("[AiAgentV4] 控制台命令表没装配起来（" + bootError + "）——本次命令 " + funcName + " 未执行。");
                out.log("控制台前缀：" + safeName() + "/", Out.Tone.TITLE);
                printHelpQuiet(c);
                return null;
            }
            return c.route(funcName, args);
        } catch (Throwable t) {
            report("main", t);
            printHelpQuiet(cmd);
            return null;
        }
    }

    /**
     * 命令表。<b>永不抛异常、永不返回空数组</b>；装配没跑完也立刻返回（技能/工具面后面才出现）。
     */
    @Override
    public String[] help() {
        try {
            ensureReady();
            Cmd c = cmd;
            if (c != null) {
                String[] h = c.help();
                if (h != null && h.length > 0) return h;
            }
        } catch (Throwable t) {
            report("help", t);
        }
        return Cmd.fallbackHelp();
    }

    /**
     * 搭壳（幂等、可重入、不抛异常、<b>立即返回</b>）。
     *
     * <p>这里只做两件便宜事：①{@code new Boot(数据根)}（构造器不碰磁盘）；②{@code new Cmd(boot)}。
     * 命令表因此<b>在装配开始前就可用</b>（{@code help/status/tools/config} 立即回话，
     * 其余命令给"未启动，先 ai/start"的可读回话）。</p>
     *
     * <p>顺带装<b>壳层</b>设施（{@code DebugShell.attach}：调试落盘 + 宿主引用）—— 这是"进程一起来
     * 就会写日志"的落点：第一条命令进来时它就把 {@code logs\console-<日期>.log} 接上了，
     * 与 {@code ai/debug}/ {@code ai/start} 都无关。</p>
     *
     * <p><b>这里绝不自动装配</b>（主人 2026-09-16 定稿的口径）：装配只由显式的
     * {@code ai/start}（或已经在跑时的 {@code ai/restart}）触发 —— 见 {@link #start()}/{@link #kick(boolean)}。
     * 旧口径是"第一条命令（哪怕只是 {@code help}）顺手把装配踢起来"，那等于把
     * "基板什么时候开始工作"交给了"谁先来问了一句话"，与主人要的模型正相反。</p>
     *
     * <p>以 {@code cmd != null} 而不是 {@code boot != null} 作为"壳已搭好"的判据：装配中途失败时
     * boot 可能已存在，但命令表还没建好 —— 那种状态下必须继续把命令表建出来。</p>
     */
    private void ensureReady() {
        if (cmd != null) return;
        synchronized (this) {
            if (cmd != null) return;
            SfwOut out = new SfwOut();
            File root = null;
            try {
                root = new File(getDataDir());
            } catch (Throwable ignored) {
            }
            // 壳层设施：绑定"当前基板在哪"的稳定宿主引用（两个口靠它现取活基板，绝不缓存）
            // + 确保调试落盘在写（幂等；与 ai/debug / ai/start 无关）。
            sair.v4.dev.DebugShell.attach(this, root, null);

            // 卸载之后（exit 已跑过）：只留一张命令表 —— help 仍非空、其余命令给"未装配"的可读回话。
            // 绝不在这里重新起装配线程（那会让"卸载后又被问一次"变成第二次 init）；
            // 但 start/restart 是"显式要它跑"，由 Cmd.Life 走到 kick() 重新装配。
            if (unloaded) {
                boot = null;
                Cmd c0 = new Cmd(safeName(), out, this);
                try {
                    c0.setDataRoot(root != null ? root : new File(getDataDir()));   // 回话文案在 prompts/tools-index.md
                } catch (Throwable ignored) {
                }
                cmd = c0;
                return;
            }

            Boot b = null;
            try {
                b = new Boot(root != null ? root : new java.io.File(getDataDir()), out);
            } catch (Throwable t) {
                bootError = String.valueOf(t);
                safeLog(out, "数据目录解析失败：" + t + " —— 控制台仍可用（help/status/config）。");
            }

            Cmd c;
            try {
                c = b != null ? new Cmd(b, getName(), this) : new Cmd(getName(), out, this);
            } catch (Throwable t) {
                bootError = String.valueOf(t);
                c = new Cmd(safeName(), out, this);
            }
            try {
                c.setDataRoot(b != null ? b.root() : new File(getDataDir()));
            } catch (Throwable ignored) {
            }
            cmd = c;
            boot = b;

            // 这里<b>一句都不打</b>：调用线程（真实环境里可能是 EDT）只做"搭壳"，
            // 所有面向控制台的播报都从 v4-boot 线程走（见 startBoot）。
            // 也不装配 —— 装配只由 ai/start / ai/restart 触发（见类注释与 start()）。
        }
    }

    /**
     * 起唯一一条装配线程（守护线程 {@code v4-boot}）：跑 {@link Boot#init(Boot.InitListener)}，
     * 进度由 {@link BootProgress} 一行行打到控制台，末尾一行汇总。
     * <p>线程里的任何异常都被兜住：基板装不上也绝不让 SFW 层收到异常（永不失联）。</p>
     *
     * @param b          本次装配的基板实例
     * @param out        输出落点
     * @param restart    true = 重启口径（先停 {@link #pendingStop} 里那台旧基板）
     */
    private void startBoot(final Boot b, final SfwOut out, final boolean restart) {
        final BootProgress prog = new BootProgress(out);
        final Boot old = pendingStop;
        pendingStop = null;
        // "有人要它跑"必须在起线程**之前**置位：受理到线程真跑起来之间有毫秒级窗口，
        // 那个窗口里 ai/status 必须已经说"加载中：第 0/19 步"，不能说"未启动"（命令说受理了、
        // 状态说没开始 = 自相矛盾）。见 Boot.loading()。
        try { b.markRequested(); } catch (Throwable ignored) { }
        bootCount.incrementAndGet();          // 每次真的起装配线程都算一次（start / restart / 首次加载）
        Thread t = Th.daemon("v4-boot", new Runnable() {
            @Override
            public void run() {
                bootThreadName = Thread.currentThread().getName();
                bootThreadId = Thread.currentThread().getId();
                try {
                    out.log("控制台命令前缀：" + safeName() + "/（输入 " + safeName()
                            + "/help 看命令表；基板在后台线程装配，加载过程会打在这里）", Out.Tone.TITLE);
                    safeLog(out, "版本 " + VERSION);
                    if (restart) {
                        // 先把旧基板收干净（库/监听/线程池），再按新配置装配 —— 端口不会自己跟自己抢
                        safeLog(out, "重启：正在停旧基板（收库 / 收监听 / 收线程池）……");
                        if (old != null) safeStop(old);
                        safeLog(out, "重启：旧基板已停，开始按 config.json 重新装配（线程 " + bootThreadName + "）");
                    } else {
                        safeLog(out, "基板装配开始（AiAgent V4.2，线程 " + bootThreadName + "，19 步，控制台不受影响）");
                    }
                    // ★ 就绪与"装配名额"必须在同一个临界区里、且名额**先**交还（既有竞态；门禁实测
                    //   首跑 ⑩(c) 8/268 红，全落在这一条根因上）：
                    //   "就绪"是 Boot 内部置的（started / initDone / loading=false / 两个口读到的
                    //   ready=1），一旦可见，调用者就可能当场敲 ai/restart。名额若等到本 Runnable
                    //   收尾才放（更早的版本是等到 finally），就留下一个"看到就绪、restart 却被
                    //   BUSY 拒"的窗口 —— 而且这个窗口的主要开销（就绪播报里的 store.stat() 与整条
                    //   控制台打印链路）**在 init 内部、init 返回之前**，所以把释放点从 finally
                    //   前移到 prog.finished() 之前**不足以**关掉它（实测 5 次里仍红 2 次）。
                    //   现在由 Boot 在"就绪对外可见之前"回调 releaseBootSlot()：任何看到就绪的
                    //   调用者，必然看到名额已空。
                    b.init(prog, new Runnable() {
                        @Override
                        public void run() { releaseBootSlot(); }
                    });
                    prog.finished(b);
                    if (b.skillsCompiling()) {
                        // 后台编译模式：编完再补一行"技能就绪"（技能什么时候可用，看这一行）
                        Th.start("v4-skills-report", new Runnable() {
                            @Override
                            public void run() {
                                if (b.awaitSkills(600000L)) prog.skillsFinished(b);
                            }
                        });
                    }
                } catch (Throwable x) {
                    bootError = String.valueOf(x);
                    prog.failed(b, x);
                } finally {
                    // exit() 在装配中途被调用过：现在装配结束了，由本线程收口（当时抢锁会一直等）
                    if (unloading) safeStop(b);
                    // 兜底再交还一次名额（正常路径上 Boot 的就绪钩子已经交还过；只有 init 半路
                    // 抛异常/早退时才靠这里）。幂等，重复交还不会出错。
                    releaseBootSlot();
                }
            }
        });
        t.start();
    }

    /**
     * 交还"同时只允许一条装配/重启线程"的名额（{@link #bootKicked}）。
     * <p><b>调用时机是这条竞态的全部要害</b>：由 {@link Boot#init(Boot.InitListener, Runnable)}
     * 的就绪钩子在"就绪对外可见之前"调用（见 {@link #startBoot}），装配线程的 {@code finally}
     * 只做兜底。这样"看到就绪"与"名额已空"之间不存在窗口 —— {@code ai/restart} 不会再在
     * 基板其实已经就绪的时候回 {@code BUSY}。</p>
     * <p>幂等：重复调用等价于一次调用。</p>
     */
    private void releaseBootSlot() {
        restarting = false;
        bootKicked.set(false);
    }

    // ==================== 生命周期（Cmd.Life）：start / restart ====================

    /** 重启时要先收掉的旧基板（{@code restart} 在调用线程上填，装配线程消费）。 */
    private volatile Boot pendingStop = null;

    @Override
    public boolean restarting() { return restarting; }

    /**
     * {@code start}：<b>唯一的"加载并运行"命令</b>（主人 2026-09-16 的口径）。<b>幂等</b>。
     * <ul>
     *   <li>已就绪 → {@link Cmd.Life.R#RUNNING}（只回一句"已在运行"，<b>绝不重启</b>；
     *       要按新配置重装只有 {@code ai/restart}）；</li>
     *   <li>已有一条装配/重启在跑 → {@link Cmd.Life.R#BUSY}（明确拒绝，不并发）；</li>
     *   <li>还没起过（只搭了壳，配置一个键都没加载）→ 装配 → {@link Cmd.Life.R#ACCEPTED}；</li>
     *   <li>{@code exit()} 之后（基板已停）→ 重新搭壳并装配 → {@link Cmd.Life.R#ACCEPTED}。</li>
     * </ul>
     * <p><b>注意</b>：命令表本身（{@code ensureReady}）不在这里，也不在装配路径上 ——
     * 没人敲 {@code start} 之前基板就是不工作的。</p>
     */
    @Override
    public Cmd.Life.R start() {
        Boot b = boot;
        if (!unloaded && b != null && b.started() && !b.loading()) return Cmd.Life.R.RUNNING;
        if (bootKicked.get()) return Cmd.Life.R.BUSY;
        return kick(false);
    }

    /**
     * {@code restart}：停旧基板 → 重读 {@code config.json} → 重新装配（19 步进度照旧可见）。
     * <p>与 {@link #start()} 共用同一套状态机：{@code bootKicked} 为 true（已有一条在跑）时
     * 直接 {@link Cmd.Life.R#BUSY}，<b>绝不并发</b>。</p>
     * <p>调用线程只做便宜事：新建空壳 {@link Boot}（构造器不碰磁盘）+ 换掉命令表 + 起装配线程；
     * 真正的"停旧 + 装配"全在 {@code v4-boot} 线程上。这样重启期间 {@code status} 立刻能报
     * "重启中：第 x/19 步"，而 {@code help/tools/config} 也照常可用。</p>
     */
    @Override
    public Cmd.Life.R restart() {
        if (bootKicked.get()) return Cmd.Life.R.BUSY;      // 已有一条装配/重启在跑：拒绝，不并发
        return kick(true);
    }

    /** 起一次装配（restart=true 时先停旧的）。只在调用线程上做"换壳 + 起线程"这两件便宜事。 */
    private Cmd.Life.R kick(boolean restart) {
        if (!bootKicked.compareAndSet(false, true)) return Cmd.Life.R.BUSY;
        SfwOut out = new SfwOut();
        try {
            File root;
            try {
                root = new File(getDataDir());
            } catch (Throwable t) {
                bootError = String.valueOf(t);
                root = new File(".");
            }
            Boot old = boot;
            Boot nb = new Boot(root, out);
            nb.setRestarting(restart);
            Cmd nc = new Cmd(nb, safeName(), this);
            nc.setDataRoot(root);
            pendingStop = old;
            unloaded = false;          // start/restart 是"显式要它跑"，exit 的封印就此解除
            unloading = false;
            boot = nb;                 // ★ 两个口现取的就是这个字段（DebugShell.Host）—— 换基板即换它们看到的基板
            cmd = nc;
            restarting = restart;
            // 壳层设施：落盘（幂等，数据根没变就什么都不做）+ 宿主引用。
            // 放在起装配线程之前：新基板的每一行装配输出都要落得进日志。
            sair.v4.dev.DebugShell.attach(this, root, out);
            startBoot(nb, out, restart);
            return Cmd.Life.R.ACCEPTED;
        } catch (Throwable t) {
            bootError = String.valueOf(t);
            bootKicked.set(false);
            restarting = false;
            try {
                out.err("[AiAgentV4] 启动/重启没起来（已兜住，控制台仍可用）：" + t);
            } catch (Throwable ignored) {
            }
            return Cmd.Life.R.UNAVAILABLE;
        }
    }

    /** 已经跑过的装配次数（诊断/探针：{@code config set} 不该让它变）。 */
    public long bootCount() { return bootCount.get(); }

    @Override
    public void exit() {
        Boot b = boot;
        boot = null;
        cmd = null;
        unloading = true;
        unloaded = true;
        // ★ 壳卸载 = 调试面的终点（主人 2026-09-16 裁定）：停两个口 + 关落盘句柄 + 注销控制台捕获器。
        //   只有这里（与进程退出）能收它们；ai/start / ai/restart 收的只是基板，观测面照旧
        //   （旧实现在 Boot.stop() 里收，于是 restart 一停旧基板就把日志与两个口一起带走了）。
        try {
            sair.v4.dev.DebugShell.detach(new SfwOut());
        } catch (Throwable ignored) {
        }
        if (b == null) return;
        // 装配还在跑：不当场抢 initLock（那会把调用线程一起拖住），让 v4-boot 线程跑完自己收口
        if (b.loading()) {
            safeLog(new SfwOut(), "卸载时基板还在装配：已标记，装配跑完自动收敛资源。");
            return;
        }
        safeStop(b);
    }

    @Override
    protected String dataDir() {
        return "AiAgent-V4";
    }

    /**
     * 供其它插件/调试读取（可能为 null）。
     * <p><b>同时是 {@link sair.v4.dev.DebugShell.Host} 的实现</b>：两个调试口（2660/2661）
     * 每次要用基板都走这里现取，所以 {@code ai/debug on}（在 {@code ai/start} 之前敲）
     * 之后它们看到的是<b>后来装配出来的那台活基板</b>，而不是 {@code ai/debug on} 那一刻的空壳。
     * {@link #kick(boolean)} 换基板（{@code ai/start} / {@code ai/restart}）时这里立刻跟着换。</p>
     */
    public Boot boot() { return boot; }

    /** 装配线程名（未起 / 已退出时是空串）。探针用它断言"装配不在 EDT / 不在调用线程上"。 */
    public String bootThreadName() { return bootThreadName; }

    /** 装配线程 id（未起时 -1）。 */
    public long bootThreadId() { return bootThreadId; }

    /** 装配线程是否已经起过（幂等判据：重复命令不得再起一条）。 */
    public boolean bootKicked() { return bootKicked.get(); }

    // ==================== 内部：兜底输出 ====================

    /** 名字取不到也不能让入口炸：退回组件默认前缀。 */
    private String safeName() {
        try {
            String n = getName();
            return n == null || n.trim().isEmpty() ? "AiAgentV4" : n;
        } catch (Throwable t) {
            return "AiAgentV4";
        }
    }

    /** 出事了：把原因打到控制台 + 打一遍命令表；自身不再抛。 */
    private void report(String where, Throwable t) {
        try {
            SfwOut out = new SfwOut();
            out.err("[AiAgentV4] " + where + " 异常（已兜住，控制台继续可用）：" + t);
            Boot b = boot;
            if (b != null && b.degraded()) out.print(b.failureReport(), Out.Tone.ERR);
        } catch (Throwable ignored) {
        }
    }

    private void printHelpQuiet(Cmd c) {
        try {
            if (c != null) c.printHelp();
            else for (String line : Cmd.fallbackHelp()) System.out.println(line);
        } catch (Throwable ignored) {
        }
    }

    private static void safeStop(Boot b) {
        try {
            b.stop();
        } catch (Throwable ignored) {
        }
    }

    private static void safeLog(SfwOut out, String msg) {
        try {
            out.err("[AiAgentV4] " + msg);
        } catch (Throwable ignored) {
        }
    }
}
