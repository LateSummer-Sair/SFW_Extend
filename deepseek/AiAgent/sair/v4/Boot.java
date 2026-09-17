package sair.v4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import sair.v4.agent.Agent;
import sair.v4.ai.DeepSeek;
import sair.v4.auth.Acl;
import sair.v4.auth.Auth;
import sair.v4.auth.Caller;
import sair.v4.auth.Favor;
import sair.v4.auth.FavorStore;
import sair.v4.auth.Res;
import sair.v4.ctx.CtxBuild;
import sair.v4.ctx.Turn;
import sair.v4.hot.DynCode;
import sair.v4.hot.Skills;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.prompt.Inject;
import sair.v4.prompt.Prompts;
import sair.v4.qq.Api;
import sair.v4.qq.Link;
import sair.v4.skill.Host;
import sair.v4.store.Store;
import sair.v4.tool.Registry;

/**
 * 基板装配（唯一的组装点）：把九项能力接成一个可运行的 AI。
 * <p>数据根由外部注入（生产环境是 SFW 的 {@code data/<组件名>}，探针里是临时目录），
 * 因此基板可以脱离 SFW GUI 单独跑测试。</p>
 *
 * <h3>装配的线程口径（谁跑、谁等）</h3>
 * <p>{@link #init()} 是<b>同步</b>的：它在<b>调用线程</b>上把 19 个命名步骤跑完。
 * 生产入口 {@code sair.v4.V4Activity} 因此把 init 放进自己的守护线程
 * （{@code sair.v4.kit.Th.daemon("v4-boot", …)}），让 SFW 的调用线程（含 EDT）立即返回。</p>
 * <p>为了让"加载中"期间的 {@code status/help/tools/config} 打得开，本类遵守一条纪律：
 * <b>装配线程之外的人永远不等待装配</b> —— {@link #init()} 不再霸占实例监视器
 * （改用私有 {@code initLock}），台账/骨架/进度的读取各自走短锁，读者最多看到"写了一半"的快照，
 * 绝不阻塞。</p>
 */
public final class Boot {

    /** 命名装配步骤总数（①…⑲）。进度行与 {@code status} 都用它做分母。 */
    public static final int STEP_TOTAL = 19;

    /**
     * 装配进度回调（可为空）。
     * <p>回调一律在<b>装配线程</b>上被调用，回调里不要做重活（打完一行就走）。</p>
     */
    public interface InitListener {
        /**
         * 一个命名步骤跑完。
         *
         * @param n     第几步（1 起）
         * @param total 总步数（{@link #STEP_TOTAL}）
         * @param name  步骤名（与台账同名）
         * @param ms    这一步的耗时（毫秒）
         */
        void step(int n, int total, String name, long ms);

        /**
         * 技能编译的子进度（默认忽略）。技能扫描是最慢的一步，所以要能看见"编到第几个、编的是谁"。
         *
         * @param i     第几个技能（1 起）
         * @param total 本次扫描的技能目录总数
         * @param name  技能名（文件夹名）
         * @param ms    这个技能目录的加载/编译耗时（毫秒）
         */
        default void compile(int i, int total, String name, long ms) { }
    }

    private final File root;
    private final Out out;

    /**
     * init/stop 的互斥锁。<b>只给装配线程与 stop 用</b>：{@code status/help/tools/config}
     * 这类读者一个都不碰它 —— 否则"加载中敲 status"会退化成"等装配跑完"。
     */
    private final Object initLock = new Object();
    /** 只允许一次装配（重复命令/重复调用不得触发第二次 init）。 */
    private final AtomicBoolean initOnce = new AtomicBoolean(false);
    /** 真正跑起来的装配次数（诊断/探针断言用；正常恒为 0 或 1）。 */
    private final AtomicInteger initRuns = new AtomicInteger();

    /** 装配进度（装配线程写、任意线程读）。 */
    private volatile boolean initRunning = false;
    private volatile boolean initDone = false;
    private volatile long initStartedAt = 0L;
    private volatile int stepNo = 0;
    private volatile String stepName = "";
    private volatile InitListener listener;

    /**
     * <b>已经有人要它跑了吗</b>（= {@code ai/start}/{@code ai/restart} 受理过，或 {@link #init()} 已经进来）。
     *
     * <p>为什么需要它：新口径下"壳基板"（{@code new Boot(...)} 之后、没人敲 {@code ai/start} 之前）
     * 是一个<b>正常状态</b>，不是"正在加载"。旧口径只有一个 {@code initDone=false} 可用，
     * 于是空壳基板会永远自报"加载中：第 0/19 步" —— 那看起来就像挂了。</p>
     *
     * <p>{@link #loading()} = {@code requested && !initDone}：没人要它跑 → {@code false}（未启动），
     * 受理之后到装完之前 → {@code true}（加载中）。</p>
     */
    private volatile boolean requested = false;

    /** 技能编译阶段的耗时（毫秒；-1 = 还没编过）。同步模式 = 步骤⑯的耗时；后台模式 = 后台线程的耗时。 */
    private volatile long skillsCompileMs = -1L;
    /** 后台编译还在跑。 */
    private volatile boolean skillsCompiling = false;
    /** 后台编译失败的缘由（空串 = 没失败）。 */
    private volatile String skillsError = "";
    /** 编译过程中"是否已经 stop 过"（stop 之后编完就不再播报/注册）。 */
    private volatile boolean stopped = false;
    /** 本次装配是"重启"（{@code V4Activity} 在起装配线程前置位）：status 的措辞据此说"重启中"。 */
    private volatile boolean restarting = false;

    /**
     * 技能编译进度的转发器：把 {@code Skills} 的子进度转给 {@link #listener}。
     * 只在装配线程上装/卸（{@code skills.setCompileListener}），因此不需要额外同步。
     */
    private final sair.v4.hot.Skills.CompileListener compileRelay = new sair.v4.hot.Skills.CompileListener() {
        @Override
        public void done(int i, int total, String name, long ms) {
            InitListener l = listener;
            if (l == null) return;
            try {
                l.compile(i, total, name, ms);
            } catch (Throwable ignored) {
            }
        }
    };

    private Conf conf;
    private Store store;
    private Favor favor;
    private Auth auth;
    private Prompts prompts;
    private Registry registry;
    private Skills skills;
    private DeepSeek ai;
    private Link link;
    private Api api;
    /**
     * 技能/模型发起的 NapCat 动作用的<b>带闸门实例</b>。
     * <p><b>不变量</b>：这个字段一旦非 {@code null}，它<b>必然已经装好 Guard</b>（装配第⑧步把
     * "建实例 + 配 relay/out/auth + 装 Guard"全部做完，<b>最后一步</b>才赋值给字段）。
     * 反过来说：装配中途抛异常时它保持 {@code null}，而不是"实例在、闸门不在"——
     * 后者会让 {@code Api.call} 跳过复核（{@code guard == null} 即放行）而整层失效。</p>
     */
    private Api guardedApi;
    /**
     * <b>M4</b>：引用（{@code reply}）按需取回器（本地库优先 → NapCat；网络只在它自己的单线程 worker 上）。
     * <p>第⑭步装配；第⑧步之后才可能建（它要 {@code api}），{@code store}/{@code conf} 那时都已经在了。
     * 构造它<b>不起线程</b>（worker 在第一次真入队时懒启动），所以"没装引用解析"的那几条路
     * （{@code napcatEnabled=false} 的探针夹具等）也不会多出任何后台线程。</p>
     */
    private sair.v4.qq.QuoteCache quote;
    /** 闸门没装配好时的兜底实例（惰性建；见 {@link #guardedNapcat()}）。 */
    private volatile Api failClosed;
    private sair.v4.qq.Relay relay;
    private DynCode dyn;
    private CtxBuild ctx;
    private Agent agent;
    private Tick tick;
    private QqGateway gateway;
    private CtxBuild.Env env;
    /** 插件扩展点注册表（基板⑦）：上下文 provider / 出站 stage / 触发投票 / 生命周期钩子。 */
    private sair.v4.ext.ExtRegistry ext;
    /**
     * 库注册表（基板⑧）：<b>"世界上有哪些表"的唯一答案</b>。
     * <p>基板在装配第③步把<b>自己的环境库</b>（{@code dialog}/{@code grouplog}，见 {@link sair.v4.store.EnvLibs}）
     * 注册进来；插件在装载（第⑯步）时声明自己的业务表。{@code Store}/{@code Db}/{@code Skills} 拿到的
     * 是<b>同一个对象</b> —— 白名单、清单、建表都以它为准。</p>
     */
    private sair.v4.store.LibRegistry libs;
    /**
     * 给技能用的输出落点（构造器里建）：<b>过程行（dim/normal）受 {@code logConsole} 的 {@code skill}
     * 类别控制</b>，警告与错误永远放行。技能是外部代码，它们爱打自己的过程细节；主人要的是
     * "控制台只留 Agent 调用事件"，所以在这一道统一拦（不是让每个技能自己读配置）。
     */
    private final Out skillOut;
    /**
     * 本地交流面板（{@link sair.v4.ui.TalkPanel}）：挂上 SFW 控制台右侧的选项卡隔离区，
     * 承载"本地控制台 = 主人对话"的正文（流式）。
     * <p>没挂上（无界面环境 / 框架不可用 / 标题缺键时不挂）就保持 null，
     * 对话自动回落控制台主文本流（{@link sair.v4.term.Sinks.ConsoleSink}）——两条路都能不丢话。</p>
     */
    private volatile sair.v4.ui.TalkPanel talkPanel;
    /**
     * 只保护"建面板/取面板实例"这一瞬间（见 {@link #panelInstance()}）。
     * <b>绝不在持锁期间做挂载</b>：挂载会等 EDT，而控制台命令就跑在 EDT 上 —— 那样必假死。
     */
    private final Object panelLock = new Object();
    /** 这个进程里成功挂载过几次（0 = 还没挂过，用来区分"首次挂载"与"主人关掉后重挂"的日志）。 */
    private volatile int mountCount;

    /**
     * 回合队列：单线程 + <b>有界</b>队列。只用于<b>本地控制台</b>那条路径
     * （{@code Cmd.chat} 要一个 Future 才能等回合结束）。
     *
     * <p>QQ 入站 / 闹钟 / 异步子 Agent <b>不再走这条</b>：它们走
     * {@link #lanes}（按会话分道，见 {@link sair.v4.schedule.Lanes}）——
     * 旧口径把所有人排在这一条 64 槽队列上，一个慢群堵住全部会话，
     * 队列满时只打一行告警就静默丢弃。</p>
     */
    private final java.util.concurrent.ThreadPoolExecutor turns = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
            new java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            new ThreadFactory() {
                private final AtomicInteger n = new AtomicInteger();
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "v4-turn-" + n.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            },
            new java.util.concurrent.RejectedExecutionHandler() {
                @Override
                public void rejectedExecution(Runnable r, java.util.concurrent.ThreadPoolExecutor e) {
                    if (out != null) out.warn("[v4] 控制台回合队列已满（" + e.getQueue().size()
                            + " 条在等），本条被丢弃 —— 消息太密了");
                }
            });

    /** 按会话分道的回合调度器（QQ 入站 / 闹钟 / 异步子 Agent 都排在这里）。 */
    private sair.v4.schedule.Lanes lanes;
    /** 钩子技能执行池（{@code hookWorkers<=0} 时 enabled=false = 旧行为：调用线程直接跑）。 */
    private sair.v4.schedule.Pool hookPool;
    /** NapCat 事件执行池（{@code eventWorkers<=0} 时不起池 = 旧行为：每事件一个裸线程）。 */
    private java.util.concurrent.ThreadPoolExecutor eventPool;

    private volatile boolean started = false;
    /** 可注入的模型客户端（探针换替身用；不注入就按配置新建）。 */
    private volatile DeepSeek injectedAi;

    /**
     * 装配步骤台账：{@code 步骤名 → "ok" / "failed: 原因"}，顺序 = 执行顺序。
     * <p>这是"控制台永不失联"的可诊断面：任何一步炸了都不会中断装配，
     * 但必须能在 {@code status} 里指出<b>是哪一步、因为什么</b>。</p>
     * <p>写入方是装配线程，读取方是控制台命令（可能跑在 EDT 上）—— 因此用一把<b>只保护台账本身</b>
     * 的短锁：读者绝不会被装配的其它部分挡住。</p>
     */
    private final Map<String, String> steps = new LinkedHashMap<String, String>();
    /** 台账/骨架/已声明步数的短锁（装配线程与读者共用，临界区里只有 map/list 操作）。 */
    private final Object ledgerLock = new Object();
    /** 第一个失败的步骤与原因（未失败时为空串）。 */
    private volatile String failedStep = "";
    private volatile String failedReason = "";
    /** 本次 init 由 {@link #ensureSkeleton} 真正新建的骨架项。 */
    private final List<String> skeletonCreated = new java.util.ArrayList<String>();

    public Boot(File root, Out out) {
        this.root = root;
        this.out = out;
        this.skillOut = new sair.v4.kit.GatedOut(out, new sair.v4.kit.GatedOut.Gate() {
            @Override
            public boolean on() {
                return conf != null && conf.logOn("skill");
            }
        });
    }

    /** 注入模型客户端（必须在 {@link #init()} 之前调；给离线探针换替身用）。 */
    public void setAi(DeepSeek d) { this.injectedAi = d; }

    /**
     * 定时任务投递用的"发送器"替身（探针换"只记录、不联网"的实现用；不注入 = 走 NapCat）。
     *
     * <p>与 {@link #setAi} 同一个口径：它<b>只替换"怎么发出去"这一层</b>，落点解析
     * （{@link #sinkForDeliver}）仍然是被测的那段代码，所以探针能直接断言
     * "这条到点提醒投给了 group=888 / qq=30003，而不是控制台"。</p>
     */
    private volatile sair.v4.qq.Sender deliverSender;

    /** 注入定时任务投递的发送器替身（给离线探针用；不注入就是 NapCat）。 */
    public void setDeliverSender(sair.v4.qq.Sender s) { this.deliverSender = s; }

    /** 定时任务投递的分段口径（与回复同一条：照 config 的 replyMaxChars 等；懒建）。 */
    private volatile sair.v4.term.Segmenter deliverSeg;

    // ==================== 生命周期 ====================

    /**
     * 装配基板（无进度回调；等价于 {@code init(null)}）。
     *
     * <p><b>契约：这个方法不向调用方抛异常</b>。装配被拆成一串命名步骤，每步单独兜住异常并把
     * "哪一步失败了、因为什么"记进 {@link #steps}；失败只让该步骤的能力缺失，装配继续往下走 ——
     * 因为它是 SFW 控制台命令路径上的第一环，它一炸就意味着"SFW 不再响应"。</p>
     *
     * <p>它在<b>调用线程</b>上同步跑完（生产入口 {@code V4Activity} 负责把它放到自己的守护线程上）。
     * 同一实例只会真跑一次：重复调用直接返回（{@link #initRuns()} 可验证）。</p>
     */
    public void init() {
        init(null, null);
    }

    /**
     * 带进度回调的装配：每一步跑完回调一次 {@link InitListener#step}，
     * 技能扫描那一步内部还会回调 {@link InitListener#compile}。
     *
     * @param l 进度回调，可为 null
     */
    public void init(final InitListener l) {
        init(l, null);
    }

    /**
     * 带进度回调 + <b>就绪钩子</b>的装配。
     *
     * <p><b>约定（既有竞态 restartfix，2026-09-16）</b>：{@code onReady} 在 19 步<b>全部跑完</b>、
     * 且任何"就绪"信号（{@link #started()} / {@link #initDone()} / {@code loading()=false} /
     * 两个调试口的 {@code ready=1} / 控制台那一行「基板就绪」）<b>对外可见之前</b>，
     * 在同一把 {@link #initLock} 里被调用一次。钩子抛的异常被吞掉（装配结果不受它影响），
     * 因此它只该做"无阻塞的小事"。</p>
     *
     * <p>它存在的唯一理由：{@code V4Activity} 要在这里交还"同时只允许一条装配/重启线程"的名额。
     * 名额若等到 {@code init} 返回之后（或等到装配线程的 {@code finally}）才交还，
     * 就会留下一个"看到 {@code ready=1}、{@code ai/restart} 却被 {@code BUSY} 拒"的窗口
     * —— 门禁实测：{@code ProbeBootAsync} ⑩(c) 首跑 8/268 条红，改到"就绪播报之前"仍 2/5 红
     * （那段尾巴里有 {@code store.stat()} 与整条控制台打印链路）。</p>
     *
     * @param l       进度回调，可为 null
     * @param onReady 就绪钩子；传 null 时行为与 {@link #init(InitListener)} <b>完全一致</b>
     */
    public void init(final InitListener l, final Runnable onReady) {
        if (!initOnce.compareAndSet(false, true)) return;         // 只装一次
        requested = true;                                        // 已经有人要它跑了（见字段注释）
        synchronized (initLock) {
            if (started) return;
            listener = l;
            initRuns.incrementAndGet();
            initRunning = true;
            initDone = false;
            initStartedAt = System.currentTimeMillis();
            stepNo = 0;
            stepName = "";
            skillsCompileMs = -1L;
            skillsError = "";
            synchronized (ledgerLock) {
                steps.clear();
                skeletonCreated.clear();
            }
            failedStep = "";
            failedReason = "";
            try {
                runSteps();
                // ★ 就绪与"名额交还"的次序（既有竞态 restartfix）：钩子必须在 started / initDone
                //   **之前**跑完。同线程的写是有序的（钩子里写的是原子量/volatile），于是
                //   "看到就绪"的读者必然"看到名额已空" —— 误报 BUSY 的那个窗口才算真正关掉。
                if (onReady != null) {
                    try { onReady.run(); } catch (Throwable ignored) { }
                }
                started = true;
            } finally {
                // 先落"装配结束"再放锁：等待方（V4Activity 的 stop 收口）不会看到半拉状态
                initRunning = false;
                initDone = true;
                listener = null;
            }
            // 就绪播报也不能成为"init 抛异常"的来源（它要读 status/库统计）
            try {
                log("基板就绪：技能 " + (skills == null ? 0 : skills.size()) + " 个（工具 "
                        + (skills == null ? 0 : skills.toolCount()) + "），工具面 "
                        + (registry == null ? 0 : registry.size()) + " 个，六库 "
                        + (store == null ? "?" : J.s(store.stat(), "size", "?")) + " 字节");
                if (degraded() && out != null) {
                    out.warn("[v4] 装配有失败步骤（基板降级运行，控制台照常可用）：" + failureReport());
                }
            } catch (Throwable t) {
                if (out != null) out.err("[v4] 就绪播报失败（不影响可用性）：" + t);
            }
        }
    }

    /** 19 个命名步骤本体（只在 {@link #init(InitListener)} 的 initLock 内跑）。 */
    private void runSteps() {

        // ① 数据根 + 配置（config.json 缺失/为空时写默认值；已存在的一律不动）
        step("数据目录与配置", new Runnable() {
            @Override
            public void run() {
                conf = new Conf(root);
                // 建数据根前判定（防御性）：数据根在 SFW 之内 = B 类；她自己（SYSTEM）写 B 类放行。
                // 路径被改到 SFW 之外（A 类）才拦 —— 那时宁可装配失败也不在 A 类上建目录。
                String mkDeny = Conf.needWrite(null, Caller.systemActor(conf.masterQQ()), conf.root());
                if (mkDeny != null) {
                    if (out != null) out.err("[v4] 数据目录被权限拦：" + mkDeny);
                    throw new IllegalStateException(mkDeny);
                }
                Fs.mkdirs(conf.root());
                // 资源类别接线（主人加强定义 D22）：数据根的 files 目录 = C 类（她自己的运行时脚手架）。
                // 只接这一处：之后所有 Res.path(...) 判定都会把 <dataDir>/files/** 判成 C（其余 dataDir 不变）。
                Res.dataDir(conf.root());
                // 读不出来时 load() 返回 false，且面**保持空对象**；而紧接着的 ensureDefaults() 只在
                // "文件不存在 / 0 字节"时才补默认 ⇒ 一份**非空但解析不出来**的 config.json 会让整台基板
                // **静默**按全出厂默认跑（表象极像"配置没生效 / 被缓存了"）。实测 load()==false 的三种面：
                // 文件缺失 / 0 字节 / 坏 JSON（tmp\cfgfix\out\cfgload_edge.txt）。
                // 这里只把那次失败读出来、打一行 warn：不改任何取值路径与装配顺序；只说文件在哪，
                // **不打印文件内容、不打印任何键值**。
                if (!conf.load() && out != null) {
                    out.warn("[v4] config.json 读不出来（缺失/空/坏 JSON？）——本次装配按出厂默认值运行："
                            + conf.file().getAbsolutePath());
                }
                conf.ensureDefaults();
                for (String w : conf.warnings()) if (out != null) out.warn("[v4] " + w);
                log("数据目录 " + conf.root().getAbsolutePath());
                log("模型 " + conf.model() + (conf.isAutoModel()
                        ? " → " + conf.resolveModel() + "（auto=默认模型，无路由）" : ""));
            }
        });

        // ② 空数据骨架（空目录冷启动：必需路径全部落盘；提示词只建空文件，不写任何兜底文案）
        step("空数据骨架", new Runnable() {
            @Override
            public void run() {
                skeletonCreated.addAll(ensureSkeleton(root, conf, out));
                if (skeletonCreated.isEmpty()) log("空数据骨架：已齐备（本次没有新建任何项）");
            }
        });

        // ③ 六库（v4.db + 环境表/FTS）。打不开也要继续：其余能力照常
        step("六库", new Runnable() {
            @Override
            public void run() {
                if (conf == null) throw new IllegalStateException("配置未加载，数据根不可用");
                // 库注册表（基板⑧）：先登记基板自己的环境库，再开库。
                // 建表<b>不在这里</b> —— 业务表是插件声明的，要等插件装载之后（第⑯步末尾）。
                libs = new sair.v4.store.LibRegistry(out);
                int n = libs.registerAll(sair.v4.store.EnvLibs.all());
                store = Store.open(conf, out, libs);
                favor = new Favor(new StoreFavor(store));
                if (store.db() == null || !store.db().isOpen()) {
                    throw new IllegalStateException("v4.db 打不开（" + conf.dbFile().getAbsolutePath()
                            + "）——六库能力降级为不可用，其余能力照常");
                }
                // "连接打开"不等于"库可用"：损坏的库（非 SQLite 文件）照样连得上、却读不了任何表。
                // 这里用一次真实读（sqlite_master）把六库验出来，好让 status 能指出"是六库这一步失败"。
                String why = store.db().unusableReason();
                if (why != null) {
                    throw new IllegalStateException("v4.db 不可用（" + conf.dbFile().getAbsolutePath()
                            + "）：" + why + " —— 六库能力降级为不可用，其余能力照常");
                }
                log("库注册表：环境库 " + n + " 张（" + sair.v4.kit.Str.join(libs.tables(), "/")
                        + "）；建表在插件装载之后");
            }
        });

        // ④ 权限门禁（ACL 账本：数据根的 perms.json；装载只读，任何旧档位表路径都已删除 —— D11/D13）
        step("权限门禁", new Runnable() {
            @Override
            public void run() {
                auth = new Auth(conf, favor, out);
                auth.init();
                warnRetiredPermKeys();
            }
        });

        // ⑤ 提示词（identity.md 缺失 = 空提示词，按设计不兜底文案）
        step("提示词", new Runnable() {
            @Override
            public void run() { prompts = new Prompts(conf, out); }
        });

        // ⑥ 工具注册表（auth 为空时 Registry 不做权限筛，工具面反而更完整）
        step("工具注册表", new Runnable() {
            @Override
            public void run() { registry = new Registry(auth, conf, out); }
        });

        // ⑦ 模型客户端 + 动态执行
        step("模型客户端", new Runnable() {
            @Override
            public void run() {
                ai = injectedAi != null ? injectedAi : new DeepSeek(conf, out);
                // 模型调用闸门：主回合 / 定时技能 / 异步子 Agent 都要过它（"模型调用并发度"由它保证）。
                // 许可数可配（modelConcurrency，默认 1 = 不并发）；等不到闸门的上限也可配
                // （modelGateWaitMs，默认 180s），等不到就放弃本次调用并收敛成一条失败结果。
                ai.setGate(new java.util.concurrent.Semaphore(conf.modelConcurrency(), true));
                dyn = new DynCode(conf, out);
            }
        });

        // ⑧ NapCat 连接（link/api/guardedApi/relay）
        step("NapCat 连接", new Runnable() {
            @Override
            public void run() {
                link = new Link(conf, out);
                // 事件执行池：不装 = 每事件一个裸线程（旧行为）。装了就把"每条消息一个线程"
                // 收敛成 eventWorkers 个固定线程 + 有界队列（读循环照旧不被阻塞）。
                java.util.concurrent.ThreadPoolExecutor ep = newEventPool();
                if (ep != null) link.setEventExecutor(ep);
                api = new Api(link, conf);
                // 文件外链中转（⑧ 的一部分）：NapCat 不在同一台机器时，本地文件经它变成 URL 交出去。
                // 两个实例都要装：Api.call 是唯一出口，所有本地文件都在那里被统一改写。
                relay = new sair.v4.qq.Relay(conf, out);
                api.setRelay(relay);
                api.setOut(out);
                api.setAuth(auth);                       // 改写层的"读文件"判定也要装在无闸门实例上（洞 2）
                api.setSentTap(sentTap());               // 出站消息台账（F5a）：她发的每一条都留 message_id
                relay.setAuth(auth);                     // Relay 自身对外判定的兜底
                // ★ 带闸门那份：先在<b>局部变量</b>上配全（relay/out/auth/sentTap/guard），**最后一行**才交给字段。
                //   这样"guardedApi != null" ⇔ "闸门已装好"是一个**不可分割**的事实：
                //   中间任何一步抛异常，字段都还是 null（而不是"实例在、闸门不在"——
                //   那种状态下 Api.call 会因为 guard == null 直接放行，判定整层失效）。
                Api g = new Api(link, conf);
                g.setRelay(relay);
                g.setOut(out);
                g.setAuth(auth);
                g.setSentTap(sentTap());
                g.setGuard(new Api.Guard() {
                    @Override
                    public String deny(String action, JsonObject params) {
                        Caller c = sair.v4.ctx.Ctx.caller();
                        // 没有绑定主体 = 她自己的自主行为（基板回复、定时推送、钩子/扩展点回调）→ SYSTEM。
                        // 旧口径是"没有绑定调用者 = 基板内部动作 → 放行"，那是钩子绕闸门的那条洞
                        // （notes/acl-impl-plan.md §2.1(a)）：新模型里一律先有主体，再按主体判。
                        if (c == null) c = Caller.systemActor(conf == null ? 0L : conf.masterQQ());
                        if (c.master()) return null;        // 主人 = MASTER，一律全放行
                        if (auth == null) {
                            // 装配失败：不许静默放权（旧行为是 return null = 闸门不存在）
                            if (out != null) {
                                out.err("[auth] NapCat 闸门没有权限面（auth == null）—— 按拒绝处理：" + action);
                            }
                            return Acl.DENY_PREFIX + "需要 " + action + " 的 X 位（权限面没有装配，按拒绝处理）";
                        }
                        // 对外动作 = E 类资源的 X 位（E 类上限：MASTER RWX / SYSTEM X / OTHER NONE）
                        return auth.allowRes(c, Res.platform(action), 'X');
                    }
                });
                guardedApi = g;                              // ← 不可分割：装好了才交出去
            }
        });

        // ⑨ 上下文装配（+ 插件扩展点注册表：它是"基板⑦"，装配顺序上必须早于 CtxBuild/技能/Agent）
        step("上下文装配", new Runnable() {
            @Override
            public void run() {
                env = new CtxBuild.Env() {
                    @Override
                    public boolean napcatConnected() { return link != null && link.connected(); }

                    // 权限面接线口：事实块里的 caller.kind / caller.bits 与人工 op 的授权操作
                    // **用同一份内存账本**（主人刚写完例外，下一轮事实块立刻就是对的），
                    // 不再靠"文件 mtime 变了才自己重读"。
                    @Override
                    public sair.v4.auth.Auth auth() { return auth; }

                    @Override
                    public int tasksRunning() { return agent == null ? 0 : agent.running(); }

                    @Override
                    public String extraFacts() { return null; }

                    @Override
                    public String toolIndex(Turn t) {
                        if (registry == null) return null;
                        // 主 Agent 给全量（名字 + 一句话），子 Agent 只给名字（B10：省 token）
                        return registry.capabilityIndex(t == null ? null : t.caller(),
                                t == null || !t.isSubagent());
                    }

                    /**
                     * 事实块那一行 {@code rounds:}（B10）。
                     * <p>上限与"是不是子 Agent"的判据一律交给 {@code agent.Loop} 的同一个静态函数 ——
                     * 事实块里的数与她被掐停的那一轮用的是<b>同一个数</b>。</p>
                     */
                    @Override
                    public String roundsFact(Turn t) {
                        if (t == null || conf == null) return null;
                        return sair.v4.agent.Loop.roundsFact(conf, t, t.rounds(),
                                sair.v4.agent.Loop.maxRounds(conf, t));
                    }
                };
                // 扩展点注册表：不新开装配步骤（"19 步"是外挂文案里的既有口径），
                // 并进这一步 —— 它在能力上属于"装配骨架"，在时序上必须早于技能扫描（⑯）与 Agent（⑫）。
                ext = new sair.v4.ext.ExtRegistry(conf, out);
                ext.setHosts(new sair.v4.ext.ExtRegistry.Hosts() {
                    @Override
                    public Host hostFor(String owner, Caller c, Turn t) {
                        // 扩展点宿主 = 它<b>自己那个插件</b>的宿主（skillDir / toolName 才有意义）。
                        // 插件已卸载 / 探针直接注册的合成实现拿不到 Sk —— host(null,…) 依然是可用的只读面。
                        sair.v4.hot.Sk sk = (skills == null || owner == null || owner.isEmpty())
                                ? null : skills.get(owner);
                        return host(sk, c, t);
                    }
                });
                sair.v4.term.Sinks.setExt(ext);          // 出站 stage 管线（④ 的"发送"半边）
                ctx = new CtxBuild(conf, prompts, store, env, out);
                ctx.setExt(ext);                         // 上下文 provider 块（⑤ 的"装配"半边）
                // 识图组件（约定名 look）的在面状态（T12-R3）：装配期注入**同一份**状态载体 ——
                // Loop 调工具前记的那一笔，就是事实块 models.recognizer_uses 读的那一笔。
                // 判定口用既有只读口 Registry.get（不新增接口、不新增配置键）：名字在注册表里 = 在工具面。
                // 注意这里在⑩技能库之前（技能还没扫），但探测是**每次现问**的 ⇒ 技能热重载后立刻变对；
                // 注册表真为 null（装配降级）时 registryProbe 恒答"不在面" ⇒ off，不抛。
                sair.v4.ctx.RecognizerState.shared().probe(
                        sair.v4.ctx.RecognizerState.registryProbe(registry));
                ctx.setRecognizer(sair.v4.ctx.RecognizerState.shared());
            }
        });

        // ⑩ 技能库（0 技能也能跑）
        // ⑩ 技能库（技能扫描是⑯；这里只建壳）
        //     （P9b-1：原来这里挂了一个"扫完同步旧档位表"的收口 —— 已删除，旧表不再被写）
        step("技能库", new Runnable() {
            @Override
            public void run() {
                skills = new Skills(conf, out, registry, prompts == null ? null : prompts.inject(),
                        new Skills.Hosts() {
                            @Override
                            public Host hostFor(sair.v4.hot.Sk sk, Caller c, Turn t) { return host(sk, c, t); }
                        });
                skills.setSystemSink(new sair.v4.term.Sinks.ConsoleSink(out));
                skills.setExt(ext);          // 插件的扩展点（按接口自动识别；见 Skills.registerExt）
                // 库注册表（基板⑧）：插件在装载期声明自己的表（Skill.declare），基板只给注册表。
                // 与 Store/Db 用的是同一个对象 —— 声明出来的表立刻进白名单与清单。
                skills.setLibs(libs());
                // 重扫（skill reload / 现场写出来的新插件）声明了新表时立刻落盘，不用等重启
                skills.setLibSync(new Runnable() {
                    @Override
                    public void run() { createLibTables(); }
                });
                // 扫完（含 skill reload / 现场新增技能）原来要同步一次旧档位表 —— P9b-1 已删除
                // （旧表不再被写；这就是 D11 要求的"启动/加载/任何路径都写不到 perms.json"）
                // 钩子执行池（有界）：钩子仍然"同步"跑（要等 _handled），但并发线程数不再随消息数涨
                hookPool = sair.v4.schedule.Pool.hooks(conf, out);
                skills.setHookPool(hookPool);
            }
        });

        // ⑪ 回合调度器（按会话分道 + 有界的池；QQ 入站 / 闹钟 / 异步子 Agent 都排这里）
        step("回合调度器", new Runnable() {
            @Override
            public void run() {
                final sair.v4.tool.ToolIndex ix = sair.v4.tool.ToolIndex.of(conf);
                lanes = new sair.v4.schedule.Lanes(conf, out, new sair.v4.schedule.Texts() {
                    @Override
                    public String overflow() { return ix.queueOverflowText(); }

                    @Override
                    public String queued() { return ix.queueQueuedText(); }

                    @Override
                    public void missing(String key) {
                        if (out != null) {
                            out.err("[v4] prompts/tools-index.md 缺键「" + key + "」—— 这一路提示不发"
                                    + "（基板不编兜底文案）");
                        }
                    }
                });
                log("回合调度器：" + conf.turnWorkers() + " 个工作线程，单会话待办上限 " + conf.turnLaneMax()
                        + "、全体上限 " + conf.turnQueueMax() + "，溢出=" + conf.turnOverflow()
                        + "（丢弃时回话=" + conf.turnDropNotice() + "），优先级=" + conf.turnPriority());
                String miss = ix.missingQueueKeys();
                if (Str.has(miss) && out != null) {
                    out.warn("[v4] prompts/tools-index.md 缺键：" + miss
                            + "（队列溢出/排队提示不生效；不影响启动）");
                }
                String miss2 = ix.missingBootKeys();
                if (Str.has(miss2) && out != null) {
                    out.warn("[v4] prompts/tools-index.md 缺键：" + miss2
                            + "（加载期间 chat 的那句提示不发 —— 基板不编兜底话；不影响启动）");
                }
            }
        });

        // ⑫ Agent + 清单表
        step("Agent", new Runnable() {
            @Override
            public void run() {
                agent = new Agent(conf, out, ai, registry, store, ctx, prompts);
                agent.setExt(ext);           // 回合开始/结束的生命周期钩子（基板⑦）
                // 后台回合（闹钟/异步子 Agent）与主回合共用同一套<b>按会话分道</b>的队列：
                // 同会话保序、不同会话并行、模型闸门负责"模型不并发"
                agent.setExecutor(lanes);
            }
        });

        // ⑬ 心跳（tick）
        step("心跳", new Runnable() {
            @Override
            public void run() { tick = new Tick(conf, out, store, skills, agent, guardedApi); }
        });

        // ⑭ QQ 入站网关
        step("QQ 网关", new Runnable() {
            @Override
            public void run() {
                // M4：引用解析器（本地库优先 → NapCat；网络只在它自己的单线程 worker 上）。
                // 用**无闸门**的 api：这是基板自己的读动作（同 Inbound.remember / 落库这一类的自主行为），
                // 而带闸门那份是给技能与模型发起动作用的（见 guardedNapcat() 的注释）。
                // 构造它不起任何线程；worker 在第一次真入队时懒启动，stop() 里收。
                quote = new sair.v4.qq.QuoteCache(store, conf, api, out);
                gateway = new QqGateway(Boot.this);
            }
        });

        // ⑮ 基板工具面（15 个）
        step("基板工具面", new Runnable() {
            @Override
            public void run() { Builtins.register(Boot.this); }
        });

        // ⑯ 技能扫描（它要注册工具、挂钩子、声明自己的库）。默认同步跑完 —— 台账里这一步的耗时就是"技能编译"的耗时；
        //    配置 skillsAsync=true 时改成起后台线程：基板先用 15 个基板工具可用，技能编完逐个注册工具。
        step("技能扫描", new Runnable() {
            @Override
            public void run() {
                if (skills == null) return;
                if (skillsAsync()) {
                    startSkillsAsync();
                    log("技能扫描已放到后台线程（skillsAsync=true）：基板先用 "
                            + (registry == null ? 0 : registry.size()) + " 个工具可用，技能编完逐个注册"
                            + "（库落盘在后台扫完之后跑）");
                    return;
                }
                long t0 = System.currentTimeMillis();
                try {
                    skills.setCompileListener(compileRelay);
                    skills.scan();
                } finally {
                    skills.setCompileListener(null);
                    skillsCompileMs = System.currentTimeMillis() - t0;
                }
                // 库落盘：插件装载完成之后跑一次（环境表 + 插件声明的全部表，缺表即建、幂等）。
                // 不新开装配步骤："19 步"是外挂文案与各处台账的既有口径（见第⑨/⑲步同样的处理）。
                createLibTables();
            }
        });

        // 注：这里以前有一个「技能目录监听」步骤（2 秒轮询 mtime → 自动重扫）。
        // 已整条删除：加载时机只剩「启动这一次 scan」与「显式重载」（skill_write op=reload /
        // 控制台 skill reload / promote 之后自动 scan）。改了技能文件要显式重载，否则等重启。

        // ⑰ NapCat 服务
        step("NapCat 监听", new Runnable() {
            @Override
            public void run() {
                if (link == null) throw new IllegalStateException("NapCat 连接未装配");
                link.setHandler(new Link.Handler() {
                    @Override
                    public void onEvent(JsonObject event) {
                        if (gateway != null) gateway.onEvent(event);
                    }
                });
                if (conf != null && conf.napcatEnabled()) {
                    if (link.start()) log("NapCat 反向 WS 已监听端口 " + conf.napcatPort());
                    else log("NapCat 监听失败（端口 " + conf.napcatPort() + " 被占用？）——本地交互照常可用");
                } else {
                    log("NapCat 未启用（config.json 里 napcatEnabled=false）——本地交互照常可用");
                }
            }
        });

        // ⑱ 文件外链中转
        step("文件外链中转", new Runnable() {
            @Override
            public void run() {
                if (relay == null || conf == null) throw new IllegalStateException("中转未装配");
                if (conf.relayEnabled()) {
                    if (relay.start()) {
                        log("文件外链中转已就绪：本地文件将经它变成 URL 交给 NapCat（" + relay.urlShape() + "）");
                    } else {
                        log("文件外链中转没起来（端口 " + conf.relayPort() + " 被占用？）——本地文件仍按 file:/// 交给同机 NapCat");
                    }
                } else {
                    log("文件外链中转未启用（config.json 里 relayEnabled=false）——本地文件按 file:/// 交给同机 NapCat");
                }
            }
        });

        // ⑲ 心跳起跳（+ 本地交流面板挂载 + 本机调试口）
        step("心跳启动", new Runnable() {
            @Override
            public void run() {
                if (tick != null) tick.start();
                // 面板挂载放在最后一步：它不属于任何"能力步"，而"19 步"是外挂文案里的既有口径
                // （再加一步就要同步改 tools-index.md 的 {total} 与各处台账），所以并进最后一步。
                mountTalkPanel();
                // 本机调试口（dev.DebugPort / dev.DebugSimPort）：**装配不再自动起它**。
                // 新口径（主人 2026-09-16）：那两个口只由控制台命令 `ai/debug on|off|status` 起停，
                // 端口/token 走命令参数、不进 config.json —— 所以这里连"读配置试着起一次"都没有。
                log("本机调试口未自动启动：要看调试面就敲 ai/debug on（只有这条命令能起停 2660/2661）。");
            }
        });
    }

    /**
     * 跑一个装配步骤：异常收敛成"这一步失败"，装配继续；跑完把"第几步、叫什么、花了多久"
     * 回调给 {@link InitListener}。
     * <p>只在装配线程上调用（{@link #runSteps()} 内部），台账写入走 {@link #ledgerLock} 短锁，
     * 让 {@code status} 在加载期间也能读到"进行到哪一步、哪几步已经失败"。</p>
     */
    private boolean step(String name, Runnable body) {
        final int n = ++stepNo;
        stepName = name;
        final long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            body.run();
            synchronized (ledgerLock) { steps.put(name, "ok"); }
            ok = true;
        } catch (Throwable t) {
            String why = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
            synchronized (ledgerLock) { steps.put(name, "failed: " + why); }
            if (failedStep.isEmpty()) {
                failedStep = name;
                failedReason = why;
            }
            if (out != null) out.err("[v4] 步骤「" + name + "」失败：" + why + " → 跳过该步，控制台照常可用");
            ok = false;
        }
        long ms = System.currentTimeMillis() - t0;
        InitListener l = listener;
        if (l != null) {
            try {
                l.step(n, STEP_TOTAL, name, ms);
            } catch (Throwable ignored) {
                // 进度回调炸了不影响装配（它是观测面，不是关键路径）
            }
        }
        return ok;
    }

    // ==================== 空数据骨架 ====================

    /**
     * 空数据冷启动骨架：把"必需但允许为空"的目录与文件先落盘。
     *
     * <p><b>只创建、不覆盖、不写正文</b>：已存在的一律不动（二次启动幂等，config.json 与用户数据
     * 永不被清）；新建的 {@code prompts/identity.md} 与 {@code prompts/subagent.md} 是
     * <b>0 字节空文件</b> —— 基板不带任何提示词文案，空文件与"文件不存在"在
     * {@link Prompts} 里语义完全一致。</p>
     *
     * <p>{@code config.json} 由 {@code Conf.ensureDefaults()} 落盘、{@code v4.db} 由 {@code Store.open()}
     * 建出（只连库）；<b>表</b>在那个库文件里由第⑯步末尾的 {@link #createLibTables()} 落盘
     * —— 环境表 + 插件在装载期声明的表，见 {@link #init()} 的①②③步。</p>
     *
     * @return 本次真正新建的项（相对数据根的路径；已存在的不列入）
     */
    public static List<String> ensureSkeleton(File root, Conf conf, Out out) {
        List<String> created = new java.util.ArrayList<String>();
        if (root == null) return created;
        File promptsDir = conf != null ? conf.promptsDir() : new File(root, "prompts");
        File identity = conf != null ? conf.identityFile() : new File(promptsDir, "identity.md");
        File subagent = conf != null ? conf.subagentFile() : new File(promptsDir, "subagent.md");
        File skillsDir = conf != null ? conf.skillsDir() : new File(root, "skills");
        File filesDir = conf != null ? conf.filesDir() : new File(root, "files");
        File tmpDir = conf != null ? conf.tmpDir() : new File(root, "tmp");

        dir(root, root, created);
        dir(root, promptsDir, created);
        dir(root, skillsDir, created);
        dir(root, filesDir, created);
        dir(root, tmpDir, created);
        // 提示词空壳：父目录跟着身份文件的实际配置走（配置可把提示词放到别的相对路径）
        if (identity.getParentFile() != null) dir(root, identity.getParentFile(), created);
        if (subagent.getParentFile() != null) dir(root, subagent.getParentFile(), created);
        emptyFile(root, identity, created);
        emptyFile(root, subagent, created);

        if (out != null && !created.isEmpty()) {
            out.dim("[v4] 空数据骨架已建立 " + created.size() + " 项：" + Str.join(created, ", "));
        }
        return created;
    }

    /**
     * 库注册表（基板⑧）：<b>"有哪些表"的唯一答案</b> —— 基板环境库 + 插件声明的业务表。
     * <p>装配第③步建好；这之前的调用（探针、诊断）拿到的是"只有环境库"的注册表。</p>
     */
    public sair.v4.store.Libs libs() {
        sair.v4.store.LibRegistry l = libs;
        if (l != null) return l;
        synchronized (initLock) {
            if (libs == null) {
                sair.v4.store.LibRegistry r = new sair.v4.store.LibRegistry(out);
                r.registerAll(sair.v4.store.EnvLibs.all());
                libs = r;
            }
            return libs;
        }
    }

    /**
     * <b>库落盘</b>：基板 5 张环境表 + 每个已注册库的表/索引（缺表即建、缺列即补，幂等）。
     *
     * <p>时机：插件装载<b>之后</b>（装配第⑯步末尾）跑一次；{@code skill reload} 之后由
     * {@link sair.v4.hot.Skills#setLibSync} 再跑一次 —— 新插件的表立刻落盘，不用等重启。</p>
     *
     * @return 建表成功的库数；库不可用（{@code store} 未装配）返回 -1
     */
    public int createLibTables() {
        try {
            if (store == null || store.db() == null) return -1;
            // 防御性：建表落盘 = 她自己的自主行为（SYSTEM C:RWX）。先按 ACL 判 C 类 W 位再落盘 ——
            // 用 memory 做代表（SYSTEM 对 C 类恒 RWX，与具体库名无关），证明基板自身建表不会被默认 OTHER 位误伤。
            if (auth != null) {
                String deny = auth.allowRes(Caller.systemActor(conf.masterQQ()), Res.db("memory"), 'W');
                if (deny != null) {
                    if (out != null) out.warn("[auth] 自主建表被拦（未落盘）：" + deny);
                    return -1;
                }
            }
            int ok = store.db().createTables();
            if (out != null) {
                out.dim("[store] 库落盘：" + libs().size() + " 个已注册库（含环境库 "
                        + sair.v4.store.EnvLibs.tables().size() + " 张），建表成功 " + ok + " 个");
            }
            return ok;
        } catch (Throwable t) {
            if (out != null) out.warn("[store] 库落盘失败（不影响其余能力）：" + t);
            return -1;
        }
    }

    private static void dir(File root, File d, List<String> created) {        if (d == null) return;
        if (d.isDirectory()) return;
        if (d.isFile()) return;                     // 同名文件挡路：不覆盖，交给上层报错
        if (d.mkdirs() && d.isDirectory()) created.add(rel(root, d));
    }

    /** 只建 0 字节空壳：已存在（含非空）一律不动。 */
    private static void emptyFile(File root, File f, List<String> created) {
        if (f == null || f.exists()) return;
        File p = f.getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        if (Fs.write(f, "") && f.isFile()) created.add(rel(root, f));
    }

    private static String rel(File root, File f) {
        try {
            String r = root.getAbsolutePath();
            String a = f.getAbsolutePath();
            return a.startsWith(r + File.separator) ? a.substring(r.length() + 1) : a;
        } catch (Throwable t) {
            return String.valueOf(f);
        }
    }

    // ==================== 技能编译：同步 / 后台 ====================

    /**
     * 技能编译是否放到后台线程（配置键 {@code skillsAsync}，默认 false）。
     * <p>true = 步骤⑯只起线程立即返回：基板先用 15 个基板工具可用，技能在后台编完<b>逐个</b>注册工具
     * （{@code Skills.loadOne} 每编完一个就 {@code registry.add}），期间 {@code status} 能看到
     * {@code skills.compiling=true}。失败/重试口径：编失败只让该技能停在 ERROR 态，
     * 后台线程自己收敛异常，重试用控制台 {@code skill reload}（会重新全量扫）。</p>
     */
    private boolean skillsAsync() {
        try {
            return conf != null && conf.skillsAsync();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 起技能编译后台线程（不等待）。线程名 {@code v4-skills}，守护线程。 */
    private void startSkillsAsync() {
        skillsCompiling = true;
        sair.v4.kit.Th.start("v4-skills", new Runnable() {
            @Override
            public void run() {
                long t0 = System.currentTimeMillis();
                try {
                    skills.setCompileListener(compileRelay);
                    skills.scan();
                } catch (Throwable t) {
                    skillsError = String.valueOf(t);
                    if (out != null) out.err("[v4] 技能后台编译失败：" + t + "（用控制台 skill reload 重试）");
                } finally {
                    skills.setCompileListener(null);
                    skillsCompileMs = System.currentTimeMillis() - t0;
                    skillsCompiling = false;
                    createLibTables();      // 库落盘：后台扫完（插件声明齐了）才建表
                    if (out != null && !stopped) {
                        log("技能就绪：技能 " + (skills == null ? 0 : skills.size()) + " 个（工具 "
                                + (skills == null ? 0 : skills.toolCount()) + "），编译耗时 "
                                + skillsCompileMs + " ms（后台线程，未阻塞基板）");
                    }
                }
            }
        });
    }

    /** 技能后台编译是否还在跑。 */
    public boolean skillsCompiling() { return skillsCompiling; }

    // ==================== 权限注册表（旧档位体系）：已删除（P9b-1 停用 / P9b-2 删净） ====================
    //
    // 这里原来是启动期的"补齐 + 迁移"块：permSync（把内置/NapCat/技能键按出厂档位补进 perms.json）、
    // migrateLegacyMatrix（把 config.json 的旧 permissionMatrix 迁进表里）、以及 sectionOf/tableSectionOf/
    // legacyKey 三个辅助。整块已删除 —— 理由（D11）：
    //   perms.json 现在是 **ACL 账本**（{"version":3,"entries":[…]}}）与旧档位表共用的同一个文件；
    //   旧路径只要在启动时补一次"缺行"并 saveTable()，账本就会被覆盖成旧格式、条目全丢。
    // P9b-1 先把这条路断掉（不再有任何写表入口：原 permTableText/permLevelText/setPerm 三个控制台口一并删除）；
    // P9b-2 再把尸体搬走 —— PermTable 整类、PermTable.save()/exportView()、旧 perm 控制台命令的后端
    // 全部从 src 删除，启动期只剩 Auth.init() 一条**只读**装载路径。

    /**
     * {@code config.json} 里若还留着旧档位键（{@code permDefaultBuiltin} / {@code permDefaultSkill}），
     * 只提示一行"它们已退休"，<b>不报错、不改配置、不参与任何判定</b>。
     *
     * <p>理由（P9b-2）：这两个键只服务旧档位表（{@code Conf.permDefaultBuiltin()} 的读者就是已删的
     * {@code PermTable} / {@code Auth.defaultFor}）。留在配置文件里的老键不该让启动看起来像出错，
     * 但也不该继续被当成"生效中的默认权限" —— 现在的默认分配按资源类型来
     * （{@code Acl.defaultText()} / {@code Acl.systemText()}），主人写例外才改得了。</p>
     */
    private void warnRetiredPermKeys() {
        if (out == null || conf == null) return;
        boolean has = false;
        try {
            has = conf.get("permDefaultBuiltin", null) != null || conf.get("permDefaultSkill", null) != null;
        } catch (Throwable ignored) {
            return;
        }
        if (has) {
            out.dim("[auth] config.json 里的 permDefaultBuiltin / permDefaultSkill 已随旧档位体系退休"
                    + "（P9b-2 已删除）：忽略即可，它们不再参与任何判定 —— "
                    + "权限只看数据根的 perms.json 账本，默认分配是 " + sair.v4.auth.Acl.defaultText() + "。");
        }
    }

    /** 技能编译耗时（毫秒；-1 = 还没编过）。 */
    public long skillsCompileMs() { return skillsCompileMs; }

    /** 技能后台编译的失败缘由（空串 = 没失败）。 */
    public String skillsError() { return skillsError; }

    /**
     * 等技能后台编译结束（探针/退出前收口用）。
     *
     * @return true = 已经不在编译中（含本来就没开后台编译）
     */
    public boolean awaitSkills(long timeoutMs) {
        long t0 = System.currentTimeMillis();
        while (skillsCompiling && System.currentTimeMillis() - t0 < Math.max(0, timeoutMs)) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return !skillsCompiling;
    }

    /**
     * 收敛基板资源。
     * <p><b>不霸占实例监视器</b>（改用 {@link #initLock}）：装配线程还在跑时它不会"抢锁"，
     * 但调用方仍应避免在装配中途调它（{@code V4Activity.exit()} 的做法是先标记，让装配线程收口）。</p>
     */
    public void stop() {
        synchronized (initLock) {
            stopped = true;
            started = false;
            // ★ 调试面（两个口 + 落盘 + 控制台捕获器）**不在这里收**（主人 2026-09-16 裁定）。
            //   它们是壳层设施，不是基板组件：停基板不等于停观测面。
            //   旧实现第一句就是 sair.v4.dev.DebugPort.stop()，于是 ai/restart 一停旧基板，
            //   两个口 + 落盘（logs\console-<日期>.log）当场全没，新装配过程一个字都不落盘 ——
            //   真机上表现为"ai/restart 之后产品像死了、日志停在那一毫秒、2660/2661 也没了"。
            //   现在只有 ai/debug off（两个口）与壳卸载（DebugShell.detach）能收它们。
            try { if (tick != null) tick.stop(); } catch (Exception ignored) {}
            try { if (agent != null) agent.stop(); } catch (Exception ignored) {}
            try { if (relay != null) relay.stop(); } catch (Exception ignored) {}
            // M4：引用解析的 worker 先收（在 link 之前 —— 在飞的取数最多被中断一次，
            // Link.call 的 InterruptedException 分支已经处理）
            try { if (quote != null) quote.shutdown(); } catch (Exception ignored) {}
            try { if (link != null) { link.setHandler(null); link.stop(); } } catch (Exception ignored) {}
            try { if (store != null) store.db().close(); } catch (Exception ignored) {}
            try { turns.shutdownNow(); } catch (Exception ignored) {}
            try { if (lanes != null) lanes.shutdown(); } catch (Exception ignored) {}
            try { if (hookPool != null) hookPool.shutdown(); } catch (Exception ignored) {}
            try { if (eventPool != null) eventPool.shutdownNow(); } catch (Exception ignored) {}
            // 扩展点：先把出站管线摘掉（停完还在改正文是没意义的），再关掉它自己的工作线程池
            try { sair.v4.term.Sinks.setExt(null); } catch (Exception ignored) {}
            try { if (ext != null) ext.close(); } catch (Exception ignored) {}
            sair.v4.kit.Th.shutdown();
        }
    }

    public boolean started() { return started; }

    /**
     * 装配是否还没出结果（"壳已建、装配线程刚起、还没进 init"那一瞬也算 —— 否则
     * {@code status} 会在那个窗口里退化成"读半拉对象"的完整状态面，甚至 NPE）。
     *
     * <p><b>新口径（{@code ai/start} 才装配）下多一条前提</b>：只有<b>有人要它跑</b>
     * （{@link #markRequested()}：{@code ai/start}/{@code ai/restart} 受理时置位，
     * {@link #init(InitListener)} 进来时也置位）才谈得上"加载中"。
     * 刚 {@code new Boot(...)} 出来的壳基板没人要它跑 → {@code loading()=false}，
     * 于是 {@code ai/status} 报的是<b>未启动</b>，而不是永远"第 0/19 步"（那看起来就像挂了）。</p>
     */
    public boolean loading() { return requested && !initDone; }

    /**
     * <b>受理"要它跑"</b>：{@code ai/start}/{@code ai/restart} 在起装配线程之前调它。
     *
     * <p>为什么必须由受理方置位而不是等装配线程进 {@link #init(InitListener)}：受理到线程真正
     * 跑起来之间有毫秒级窗口，那个窗口里 {@code ai/status} 必须已经说"加载中：第 0/19 步"，
     * 不能说"未启动"（命令说已受理、状态说没开始，读起来就是自相矛盾）。</p>
     */
    public void markRequested() { requested = true; }

    /** 有没有人受理过"让它跑"（{@code false} = 还是只搭了壳的基板，一个键都没加载）。 */
    public boolean requested() { return requested; }

    /** 装配是否已经有结果（成功或失败都算跑完了）。 */
    public boolean initDone() { return initDone; }

    /**
     * 标记本次装配是"重启"（在 {@link #init(InitListener)} 之前调）。
     * <p>只影响措辞：{@code status} 会说"重启中：第 x/19 步"而不是"加载中"。
     * 重启是"停旧 + 新建一个 {@code Boot} 实例"，所以这个标记在新实例上置位，不存在竞态。</p>
     */
    public void setRestarting(boolean b) { this.restarting = b; }

    /** 本次装配是不是重启（由 {@link #setRestarting(boolean)} 置位）。 */
    public boolean restarting() { return restarting; }

    /** 真正跑起来的装配次数（正常 0 或 1；重复命令不得让它变成 2）。 */
    public int initRuns() { return initRuns.get(); }

    /** 当前（或最后一个）正在跑的步骤序号，1 起；没开始是 0。 */
    public int stepNo() { return stepNo; }

    /** 当前（或最后一个）正在跑的步骤名；没开始是空串。 */
    public String stepName() { return stepName; }

    /** 装配已耗时（毫秒；没开始是 0）。 */
    public long bootElapsedMs() {
        long t0 = initStartedAt;
        return t0 <= 0L ? 0L : System.currentTimeMillis() - t0;
    }

    /**
     * 加载中的一眼可读面：{@code 第 3/19 步 六库，已耗时 1234ms}。
     * <p>调用方一般写成 {@code "加载中：" + b.progressLine()}。装完/没开始都不该用（
     * 没开始时给的是"第 0/19 步（装配线程已起，正在准备）"）。</p>
     */
    public String progressLine() {
        long ms = bootElapsedMs();
        if (stepNo <= 0) {
            return "第 0/" + STEP_TOTAL + " 步（装配线程已起，正在准备），已耗时 " + ms + "ms";
        }
        return "第 " + stepNo + "/" + STEP_TOTAL + " 步 " + (Str.blank(stepName) ? "（准备中）" : stepName)
                + "，已耗时 " + ms + "ms";
    }

    // ==================== 会话入口 ====================

    /** 本地控制台 = 主人说话（≡ QQ 主人交互）。 */
    public Agent askConsole(String text) {
        Caller c = Caller.console(conf.masterQQ());
        // 先取落点（它会在面板被关掉时把面板挂回来），再写主人这句话 —— 顺序反了主人那句会落空
        sair.v4.ctx.Sink sink = localSink();
        echoConsoleUser(text);
        agent.ask(c, sink, text);
        return agent;
    }

    /**
     * 本地对话的落点：挂了交流面板就进面板（正文整行 + 流式增量），否则回落控制台主文本流。
     *
     * <p><b>每个回合都要新取一个</b>：{@link sair.v4.ui.PanelSink} 带"本轮是否流过式"的状态，
     * 跨回合复用会把一次整行回话误判成"流式收尾"而少打一行（面板/控制台两条路都不会丢话，
     * 只是少一行）。</p>
     */
    public sair.v4.ctx.Sink localSink() {
        sair.v4.ui.TalkPanel p = talkPanel;
        // 面板被主人右键关掉（框架只给了"关闭此面板"/"清除全部"，没有"重挂这一页"的公开入口）时
        // getParent() 会变 null —— 这时**按需把它挂回来**：下一次 ai/chat 照样进面板，而不是悄悄掉到主输出框。
        // 挂不回来（无界面 / 框架不可用）才回落控制台主文本流。
        if (p == null || !attached(p)) {
            p = ensureTalkPanel();
        }
        if (p == null || !attached(p)) return mirror(new sair.v4.term.Sinks.ConsoleSink(out));
        return mirror(new sair.v4.ui.PanelSink(p, out));
    }

    /**
     * 给本地对话落点套上调试镜像（{@code dev.DebugPort.mirror}）。
     *
     * <p>为什么必须在这里：{@code ai/chat} 的正文<b>不走主输出框</b>，它由 {@code PanelSink} 写进
     * 独立的交流面板控件（{@code TalkMount} 把它挂进选项卡隔离区，不碰 {@code infoPane}）；
     * 换言之控制台打印代理（{@code PrintRunnable}）拿不到"她说了什么"。
     * 而本地对话的落点只有本方法一个出处，所以在这一层套镜像 = 两路落点都覆盖、且只镜像一次。</p>
     *
     * <p>调试口没开时 {@code mirror} 原样返回 —— 不缓冲、行为逐字不变。</p>
     */
    private static sair.v4.ctx.Sink mirror(sair.v4.ctx.Sink s) {
        try {
            return sair.v4.dev.DebugPort.mirror(s);
        } catch (Throwable t) {
            return s;                       // 镜像只是观测面，绝不能影响回话
        }
    }

    /** 面板还挂在控制台选项卡隔离区里吗（没挂上/被关掉 = false）。 */
    private static boolean attached(sair.v4.ui.TalkPanel p) {
        try {
            return p.getParent() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 本地交流面板（没挂上时为 null —— 那时本地对话走控制台主文本流）。 */
    public sair.v4.ui.TalkPanel talkPanel() { return talkPanel; }

    /**
     * 把主人这句话也写进交流面板（面板是"对话"视图：主人与 AI 两侧都在）。
     * <p>没挂面板 / 面板已被主人关掉 = 什么都不做（主人的话本来就在控制台自己的输入回显里）。
     * 任何线程可调（面板自己串行化文档写入），异常一律吞掉 —— 它只是观感，不是关键路径。</p>
     */
    public void echoConsoleUser(String text) {
        sair.v4.ui.TalkPanel p = talkPanel;
        if (p == null || Str.blank(text) || !attached(p)) return;
        try {
            p.line(text, sair.v4.ui.TalkPanel.Style.USER);
            // 调试口镜像：面板是"对话"视图，主人这句也记一份，读起来才是完整的一来一回
            sair.v4.dev.DebugPort.acceptTalk("user", text + "\n");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把本地交流面板挂进 SFW 控制台的选项卡隔离区（装配最后一步调用一次；重复调用直接返回）。
     *
     * <p><b>标题是外挂文案</b>：取 {@code prompts/tools-index.md} 的「面板标题」；缺键 = 不设名字
     * （框架自己叫「面板N」）并在日志里报缺键 —— 与其余文案同一契约，Java 里不编名字。
     * 上限用 {@code talkPanelMaxEntries}/{@code talkPanelMaxChars}（读
     * {@link sair.v4.Conf#talkPanelMaxEntries()}/{@link sair.v4.Conf#talkPanelMaxChars()}；
     * 缺省值只活在 {@link sair.v4.ui.TalkPanel#DEF_MAX_ENTRIES}/{@link sair.v4.ui.TalkPanel#DEF_MAX_CHARS}
     * 那一处，这里不重抄）。</p>
     *
     * <p>无界面环境（探针）里 {@code SfwOut.available()=false} 或挂载失败：面板保持 null，
     * 本地对话照旧走控制台 —— <b>挂不上面板绝不影响基板</b>。</p>
     */
    private void mountTalkPanel() {
        ensureTalkPanel();
    }

    /**
     * 保证"有一页交流面板挂着"，返回它（挂不上返回 null）。<b>单例 + 按需自愈</b>：
     *
     * <ul>
     *   <li>已经挂着（{@code getParent() != null}）→ 直接复用，什么都不做；</li>
     *   <li>主人把它右键关掉 / 清了全部选项卡（框架只提供"关这一页"，没有公开的"重挂"入口）→
     *       <b>把同一个面板对象再挂回去</b>：历史还在，且不会多开一页（框架的 {@code printComponent} 不查重，
     *       所以我们永远只保留这一个实例）；</li>
     *   <li>还没建过 → 建一个（标题与上限按外挂文案/配置），挂上；</li>
     *   <li>无界面 / 框架不可用 → 返回 null，调用方回落控制台文本流。</li>
     * </ul>
     *
     * <p><b>规矩：挂载动作绝不放在锁里做。</b>控制台命令跑在 EDT 上，而
     * {@link sair.v4.ui.TalkMount#mount} 在非 EDT 线程里是"投递到 EDT + 等结果"——
     * 只要有人"占着锁等 EDT"，EDT 再跑来要这把锁，就是 EDT 等锁、挂载线程等 EDT 的假死
     * （{@code ai/chat} 卡死那种故障）。所以这里拆成三步，彼此不重叠：
     * ① 无锁快路径（已挂着就直接返回，EDT 绝大多数时候走这条）；
     * ② 只把"建实例"这一瞬间放进 {@code panelLock}（构造面板不会等任何线程，EDT 抢一下也只是一瞬间）；
     * ③ 挂载在锁<b>外面</b>做 —— 谁在 EDT 上谁就地挂（{@code TalkMount} 自己判线程），
     * 谁不在就投递，重复挂由 {@code TalkMount} 的"已有父容器就跳过"兜住，永远只有一页。</p>
     */
    public sair.v4.ui.TalkPanel ensureTalkPanel() {
        sair.v4.ui.TalkPanel p = talkPanel;
        if (p != null && attached(p)) return p;                   // ① 无锁快路径
        if (!sair.v4.term.SfwOut.available()) return null;        // 无界面环境：没有控制台可挂
        if (p == null) p = panelInstance();                       // ② 极短锁：只兜"同时建出两个实例"
        if (p != null) mountPanel(p);                             // ③ 无锁挂载
        return p;
    }

    /** 建或取唯一实例（只在"建"这一瞬间持 {@code panelLock}，绝不跨挂载持有）。 */
    private sair.v4.ui.TalkPanel panelInstance() {
        synchronized (panelLock) {
            if (talkPanel != null) return talkPanel;
            // 优先复用 TalkMount 记着的那一个（同一进程里换 Boot 实例时不会多开页）
            sair.v4.ui.TalkPanel p = sair.v4.ui.TalkMount.panel();
            if (p == null) {
                p = new sair.v4.ui.TalkPanel(
                        conf == null ? sair.v4.ui.TalkPanel.DEF_MAX_ENTRIES : conf.talkPanelMaxEntries(),
                        conf == null ? sair.v4.ui.TalkPanel.DEF_MAX_CHARS : conf.talkPanelMaxChars());
            }
            talkPanel = p;
            return p;
        }
    }

    /** 把面板挂回控制台（幂等；<b>不在任何锁里做</b>，所以 EDT 永远不会被挂载线程挡住）。 */
    private void mountPanel(sair.v4.ui.TalkPanel p) {
        if (attached(p)) return;                                  // 已经挂着了：什么都不做
        try {
            String title = panelTitle();
            boolean first = (mountCount == 0);
            if (sair.v4.ui.TalkMount.mount(p, title)) {
                mountCount++;
                log(first ? ("本地交流面板已挂载：选项卡「" + (Str.blank(title) ? "(框架默认名)" : title)
                        + "」，上限 " + p.maxEntries() + " 条 / " + p.maxChars() + " 字符")
                        : "本地交流面板已重新挂上（主人关掉过它；历史保留，未多开页）");
            } else {
                log("本地交流面板没挂上（" + sair.v4.ui.TalkMount.lastError()
                        + "）——本地对话走控制台主文本流");
            }
        } catch (Throwable t) {
            // UI 挂载绝不能让装配步骤/回合失败（out 也可能为 null：log() 一直是这么防的）
            if (out != null) out.err("[v4] 本地交流面板挂载异常：" + t + "（本地对话走控制台主文本流）");
        }
    }

    /** 面板标题（外挂文案；缺键 = null，由框架叫「面板N」，并在日志里报缺键）。 */
    private String panelTitle() {
        String title = null;
        try {
            sair.v4.tool.ToolIndex ix = sair.v4.tool.ToolIndex.of(conf);
            if (ix != null) title = ix.panelTitle();
        } catch (Throwable ignored) {
        }
        if (Str.blank(title)) {
            if (out != null) {
                out.warn("[v4] prompts/tools-index.md 缺键「面板标题」——面板不设名字"
                        + "（框架自己叫「面板N」），基板不编名字");
            }
            return null;
        }
        return title;
    }

    /** attached 的容错版（面板为 null 时为 false）。 */
    private static boolean attached0(sair.v4.ui.TalkPanel p) {
        return p != null && attached(p);
    }

    /**
     * 提交到<b>按会话分道</b>的回合队列（无名道、低优先级）。
     * <p>QQ 入站请用 {@link #turnsQq()} + {@link sair.v4.schedule.Job}：那条路才会带上会话键，
     * 从而拿到"同会话保序 + 同会话合并 + 不互相堵"。</p>
     */
    public void submit(Runnable r) {
        if (lanes != null) lanes.submit("", false, new sair.v4.schedule.SimpleJob("", false, r, null));
        else turns.execute(r);
    }

    /** 控制台回合队列（{@code Cmd.chat} 用；只有本地控制台走这条）。 */
    public java.util.concurrent.ThreadPoolExecutor turns() { return turns; }

    /** QQ/后台回合的调度器（按会话分道）。 */
    public sair.v4.schedule.Lanes turnsQq() { return lanes; }

    /** 排队中的回合数（含控制台队列；状态输出用）。 */
    public int queueSize() {
        int n = turns.getQueue().size();
        if (lanes != null) n += lanes.depth();
        return n;
    }

    /** NapCat 事件执行池（可能有界，也可能为 null = 每事件一个裸线程）。 */
    private java.util.concurrent.ThreadPoolExecutor newEventPool() {
        int w = conf == null ? Conf.DEF_EVENT_WORKERS : conf.eventWorkers();
        int q = conf == null ? Conf.DEF_EVENT_QUEUE_MAX : conf.eventQueueMax();
        if (w <= 0 || q <= 0) return null;
        final AtomicInteger n = new AtomicInteger();
        eventPool = new java.util.concurrent.ThreadPoolExecutor(w, w, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<Runnable>(q),
                new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "v4-evt-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        return eventPool;
    }

    /**
     * 把一个<b>会话键</b>解析成真正的投递落点（{@link Host#spawnAs} 的 {@code session} 那条口）。
     *
     * <p>口径与基板闹钟（{@code Tick.fireDue}）逐条对齐 —— 那里是
     * {@code alarmTarget > 0 ? new Sinks.TaskSink(api, out, alarmGroup, alarmTarget, false, seg)
     * : new Sinks.ConsoleSink(out)}。这里同样：</p>
     * <ul>
     *   <li>{@code group:<群号>} → {@link sair.v4.term.Sinks.TaskSink}（群）；</li>
     *   <li>{@code qq:<QQ>} → 同上（私聊）；</li>
     *   <li>{@code console} / 空 / <b>认不出来</b> / 号 ≤ 0 → 参数 {@code fallback}
     *       （= 当轮那个落点，定时钩子下就是控制台）—— <b>绝不抛异常、绝不丢任务</b>：老数据行
     *       （任务里没记过目标会话）走的正是这条路，行为与修这条之前逐字一致。</li>
     * </ul>
     *
     * <p><b>为什么用 {@code guardedApi}（带闸门那个）</b>：基板替机器人发一句话属于"她自己说话"，
     * 与"回复子 Agent 的结论"（{@link Host#spawnAs} 的 {@code reply=true}）是同一条口径 ——
     * 收件人不需要有权限，闸门按<b>没有绑定调用者 = SYSTEM</b>（E:{@code X}）判。基板闹钟
     * （{@code Tick}）与消息钩子的回复（{@code QqGateway}）传的也正是 {@code guardedApi}，
     * 这里与它们同源（不另开一条"绕过闸门"的口）。</p>
     *
     * <p><b>为什么不建 {@code QqSink}</b>：那条路要一个 {@link Caller} 当落点载体，
     * 而这里的落点是任务行里<b>记下来的会话</b>（创建者此刻不一定在场，主体另有其人 ——
     * 见 {@code CronSkill.on} 的 {@code ownerActor}）。{@code TaskSink} 要的正是"群/私聊 + 号"，
     * 而且它的结构事实行（{@code [task→] sent scope=… target=…}）把这件事故意打在明面上。</p>
     *
     * @param session  任务行里记的目标会话键；{@code null}/空 = 没有目标
     * @param fallback 认不出目标时用的落点（当轮落点；{@code null} 也接受）
     */
    private sair.v4.ctx.Sink sinkForDeliver(String session, sair.v4.ctx.Sink fallback) {
        try {
            String s = Str.trim(session);
            if (Str.blank(s) || "console".equalsIgnoreCase(s)) return fallback;
            boolean group;
            String rest;
            if (s.regionMatches(true, 0, "group:", 0, 6)) {
                group = true;
                rest = s.substring(6);
            } else if (s.regionMatches(true, 0, "qq:", 0, 3)) {
                group = false;
                rest = s.substring(3);
            } else {
                return fallback;                         // 认不出来的会话键：回落当轮落点
            }
            long target;
            try {
                target = Long.parseLong(Str.trim(rest));
            } catch (Exception bad) {
                target = 0L;
            }
            if (target <= 0L) return fallback;           // 号不合法（group:0 之类）：回落
            sair.v4.term.Segmenter seg = deliverSeg;
            if (seg == null && conf != null) {
                seg = new sair.v4.term.Segmenter(conf);
                deliverSeg = seg;
            }
            sair.v4.qq.Sender sd = deliverSender;
            if (sd != null) {
                // 探针替身：只记录、不联网，落点解析本身仍是被测代码
                return new sair.v4.term.Sinks.TaskSink(sd, out, group, target, false, seg);
            }
            return new sair.v4.term.Sinks.TaskSink(guardedApi != null ? guardedApi : api, out, group, target, false, seg);
        } catch (Throwable t) {
            // 兜底：解析这条路自己炸了也绝不外抛（任务照跑，只是回落成今天那个落点）
            if (out != null) out.warn("[cron] 目标会话解析失败，回落当轮落点：" + session + "（" + t + "）");
            return fallback;
        }
    }

    /** 技能宿主实现（绑定到具体技能与具体会话）。 */
    public Host host(final sair.v4.hot.Sk sk, final Caller c, final Turn t) {
        return new Host() {
            @Override
            public Caller caller() { return c; }

            @Override
            public String skillName() { return sk == null ? "" : sk.name; }

            @Override
            public String toolName() { return sk == null ? "" : Str.nz(sk.tool); }

            @Override
            public java.io.File skillDir() {
                return sk == null || sk.dir != null ? (sk == null ? null : sk.dir)
                        : new java.io.File(conf.skillsDir(), sk.name);
            }

            @Override
            public boolean selfIsGroupAdmin(long groupId) {
                return Boot.this.selfIsGroupAdmin(groupId);
            }

            @Override
            public String session() { return t != null ? t.session() : (c == null ? "console" : c.session()); }

            @Override
            public String scopeKey() { return c == null ? "" : c.scopeKey(); }

            @Override
            public boolean subagent() { return t != null && Str.has(t.get("subagent", "")); }

            @Override
            public String agentLabel() { return t == null ? "" : Str.nz(t.get("subagent", "")); }

            @Override
            public sair.v4.skill.ConfView conf() { return conf; }

            @Override
            public Out out() { return skillOut; }

            @Override
            public Store store() { return store; }

            @Override
            public DeepSeek ai() { return ai; }

            /**
             * 技能面拿到的 NapCat 接口：<b>只给带闸门且真的装上 Guard 的那一份</b>；
             * 闸门缺失时给"任何动作都被拒"的兜底实例（fail-closed），
             * <b>绝不回落到不带闸门的 {@code api}</b>（旧实现就是那么回落的 —— 那时"平台动作 = E 类"
             * 整层失效）。见 {@link Boot#guardedNapcat()}。
             */
            @Override
            public Api napcat() { return guardedNapcat(); }

            @Override
            public boolean napcatConnected() { return link != null && link.connected(); }

            /**
             * 私聊"正在输入"：机器人自己的状态动作，走<b>带闸门</b>的 {@code guardedApi}，
             * 并按 SYSTEM（E:X）判（见 {@link sair.v4.skill.Host#typing} —— 不是用户发起的对外动作）。
             * 只做 {@code set_input_status} 一个动作、只对私聊、异常全吞。
             */
            @Override
            public void typing(boolean on) {
                Api g = guardedNapcat();                          // 闸门缺失时给的是"任何调用都被拒"的兜底实例
                if (g == null || link == null || !link.connected()) return;
                long qq = c == null ? 0L : c.qq();
                if (qq <= 0) return;
                if (c.isGroup()) return;                          // 群聊没有这个动作
                try {
                    // 显式绑 SYSTEM：避免被触发者的位（OTHER E:NONE）误拦机器人自己的"正在输入"。
                    sair.v4.ctx.Ctx.Scope sc = sair.v4.ctx.Ctx.of(
                            Caller.systemActor(conf == null ? 0L : conf.masterQQ()));
                    try {
                        g.call("set_input_status",
                                sair.v4.kit.J.obj("user_id", qq, "status", on ? 1 : 0));
                    } finally {
                        sc.close();
                    }
                } catch (Throwable ignored) {
                }
            }

            @Override
            public String inboundImageUrl(String file) {
                return sair.v4.qq.Inbound.urlOfFile(file);
            }

            @Override
            public List<String> recentInboundImageUrls(int limit) {
                return sair.v4.qq.Inbound.recentImageUrls(limit);
            }

            @Override
            public Prompts prompts() { return prompts; }

            @Override
            public Inject inject() { return prompts.inject(); }

            /**
             * 权限账本（{@link Host#acl()}）。
             * <p>P9b-2：这里原来是 {@code @Override public AuthView auth()} —— 旧档位视图的出口，
             * 已随 {@code AuthView} / {@code PermTable} 一起删除。宿主面只需要账本本身，
             * 所以改成覆写 {@code acl()}（{@code Host.need*}/{@code bits} 走的正是它）。</p>
             */
            @Override
            public sair.v4.auth.Auth acl() { return auth; }

            @Override
            public sair.v4.skill.FavorView favor() { return favor; }

            @Override
            public sair.v4.skill.ToolView tools() { return registry; }

            @Override
            public String call(String tool, JsonObject args) {
                return registry.call(tool, args, t);
            }

            @Override
            public void say(String text) {
                if (t != null) t.say(text);
                else if (out != null) out.print(Str.nz(text) + "\n", Out.Tone.NORMAL);
            }

            @Override
            public JsonObject spawn(String task, List<String> tools, boolean async) {
                return agent.spawn(c, t == null ? null : t.sink(), task, tools, async, null);
            }

            @Override
            public JsonObject spawn(String task, List<String> tools, boolean async, String brief) {
                return agent.spawn(c, t == null ? null : t.sink(), task, tools, async, brief, null);
            }

            @Override
            public JsonObject spawn(String task, List<String> tools, boolean async, String brief, String model) {
                return agent.spawn(c, t == null ? null : t.sink(), task, tools, async, brief, model);
            }

            @Override
            public JsonObject spawn(String task, List<String> tools, boolean async, String brief, String model,
                                    boolean reply) {
                return agent.spawn(c, t == null ? null : t.sink(), task, tools, async, brief, model, reply);
            }

            /**
             * 显式指定主体的派发入口（{@link Host#spawnAs}）：主体字段换成调用方给的那一个。
             * 定时任务到点派子 Agent 走的正是这里 —— 它把主体从"钩子主体（SYSTEM）"换成
             * <b>任务创建者</b>（能力继承，见 {@code CronSkill.on}）。
             * {@code who == null} 时与上面那一族逐字一致（= 当轮调用者）。
             */
            @Override
            public JsonObject spawnAs(Caller who, String task, List<String> tools, boolean async,
                                      String brief, String model, boolean reply) {
                Caller subject = who == null ? c : who;
                return agent.spawn(subject, t == null ? null : t.sink(), task, tools, async, brief, model, reply);
            }

            /**
             * 指定主体 + <b>指定投递目标会话</b>（{@link Host#spawnAs} 的那条新口）。
             *
             * <p>定时任务到点要说的那句话，落点<b>不是</b>当轮落点（钩子那轮的落点是控制台），
             * 而是任务行里记下的那个会话（创建任务时所在群 / 私聊）。所以这里先把会话键解析成
             * 真落点（{@link Boot#sinkForDeliver}），再交给 {@code agent.spawn(...)} 的 sink ——
             * {@code reply=true} 时宿主就是往这个 sink 投递的。</p>
             *
             * <p>{@code session} 为空 / {@code console} / 认不出来时，{@code sinkForDeliver}
             * 原样返回 {@code t.sink()} —— 与上面那个重载逐字一致（老数据行 = 今天的控制台行为）。</p>
             */
            @Override
            public JsonObject spawnAs(Caller who, String task, List<String> tools, boolean async,
                                      String brief, String model, boolean reply, String session) {
                Caller subject = who == null ? c : who;
                sair.v4.ctx.Sink fallback = t == null ? null : t.sink();
                return agent.spawn(subject, sinkForDeliver(session, fallback), task, tools, async, brief, model, reply);
            }

            @Override
            public void inject(String slot, String key, String text) {
                prompts.inject().put(session(), slot, key, text, "skill");
            }
        };
    }

    // ==================== Getters ====================

    public File root() { return root; }

    public Conf conf() { return conf; }

    public Out out() { return out; }

    public Store store() { return store; }

    public Favor favor() { return favor; }

    public Auth auth() { return auth; }

    // ==================== 旧 perm 控制台命令：已删除（P9b-1 停用 / P9b-2 删净） ====================
    //
    // 这里原来是三个"旧 perm 控制台命令"的后端：permTableText()（perm levels 的全表清单）、
    // permLevelText()（perm levels <键>）、setPerm()（perm setlevel 写表 + 热重载），以及排版用的 pad()。
    // 整组已删除：它们是旧档位体系的用户可见面，而且 setPerm() 会落到 saveTable()（D11 的写账本口）。
    // 控制台侧的调用点（Cmd.perm 的 levels/setlevel 分支）与帮助文案一并撤掉。
    // P9b-2 连带删除：诊断用的 levelOfTool(...)（它读 Auth.keyOf/effectiveLevel —— 旧档位 facade 的方法，
    // 旧表已随 PermTable 一起消失，留着只会算出一堆无意义的字符串）；探针侧的调用点随 P9b-2b 处理。

    public Prompts prompts() { return prompts; }

    public Registry registry() { return registry; }

    public Skills skills() { return skills; }

    public DeepSeek ai() { return ai; }

    public Link link() { return link; }

    public Api api() { return api; }

    /** 带权限闸门的 NapCat 接口（技能与模型发起动作用它）。 */
    public Api guardedApi() { return guardedApi; }

    /**
     * <b>技能面发平台动作的唯一出口</b>：只给"带闸门且<b>确实装上了 Guard</b>"的那一份。
     *
     * <p>闸门没装配好（第⑧步中途抛异常 ⇒ {@code guardedApi == null}；或有人把 Guard 摘了
     * ⇒ {@code guard() == null}）时返回 {@link #failClosed()} —— 一个"任何动作都被拒"的实例，
     * <b>绝不回落到不带闸门的 {@link #api()}</b>：那份是基板自己说话用的，谁拿到它都能免判定发消息，
     * 回落过去等于把"平台动作 = E 类"整层删掉（fail-open）。</p>
     *
     * <p>顺带说明：调用方拿到的实例在兜底状态下 {@code link == null}，所以它连"发出去"的能力都没有，
     * 拒绝不是靠"恰好没连接"，而是靠这道闸门。</p>
     */
    public Api guardedNapcat() {
        Api g = guardedApi;
        if (g != null && g.guard() != null) return g;
        return failClosed();
    }

    /**
     * 闸门是不是真的装好了（诊断/工具面预检）：{@code true} = {@link #guardedApi()} 非空<b>且</b>
     * 它带着 Guard。装配第⑧步是"装好才赋值"，所以 {@code false} 只可能出现在"那一步抛异常"或
     * "有人把 Guard 摘了"这两种异常状态下 —— 那时一切平台动作按拒绝处理。
     */
    public boolean napcatGuardReady() {
        Api g = guardedApi;
        return g != null && g.guard() != null;
    }

    /** 闸门缺失时的兜底实例（惰性建、进程内复用）：任何动作都返回一句明确的拒绝理由。 */
    private Api failClosed() {
        Api f = failClosed;
        if (f != null) return f;
        synchronized (this) {
            if (failClosed == null) {
                Api a = new Api(null, conf);             // link=null：永远发不出去
                a.setOut(out);
                a.setGuard(new Api.Guard() {
                    @Override
                    public String deny(String action, JsonObject params) {
                        String why = Acl.DENY_PREFIX + "需要 " + action + " 的 X 位（NapCat 闸门没有装配："
                                + "guardedApi 缺失或没装上 Guard —— 按拒绝处理）";
                        if (out != null) {
                            out.err("[auth] NapCat 闸门没有装配，技能面的平台动作一律拒绝：" + action);
                        }
                        return why;
                    }
                });
                failClosed = a;
            }
        }
        return failClosed;
    }

    /**
     * 出站消息台账注入口（F5a）：把 {@code Api.call} 真发出去的 {@code message_id} 记进
     * {@code sent} 表（{@link Store#sentAdd}），供"按范围批量撤回"定位。
     *
     * <p><b>两个 Api 实例都装</b>（无闸门的 {@code api} 与带闸门的 {@code guardedApi}）：
     * 一次"意外刷屏"可能来自技能/模型的显式发送、也可能来自定时任务与闹钟的投递
     * （{@code Tick} 那条路走的是不带闸门的那份）—— 台账漏记哪一条，批量撤回就有洞。
     * 记录本身是 fire-and-forget（{@code SentTap} 返回 void、{@code Api} 侧整段 try/catch），
     * 所以它不会改变、吞掉或拖住任何一次发送的返回值。</p>
     *
     * <p><b>P0-1（A 路）</b>：这里多了一步 —— 把这次发送也变成一行"自我标记行"落进她自己的记账
     * （{@link QqGateway#selfEcho}，<b>唯一写库口</b>）。顺序<b>刻意</b>是"先台账、后标记行"：
     * ① 标记行的 {@code extra.sent_id} 要反查台账行（{@code Store.sentIdOf} 走 {@code idx_sent_msgid}），
     * 所以台账必须先落；② 万一标记行那一步抛异常，台账<b>已经</b>记上 —— 撤回能力不受影响。
     * 而且整段还在 {@code Api.recordSent} 的 try/catch 里（异常只打一行 dim），
     * 所以这一步<b>不可能</b>让任何一次发送失败或变慢成阻塞。</p>
     *
     * <p>六库没装配（{@code store == null}）时什么都不记 —— 发送照旧。</p>
     */
    private Api.SentTap sentTap() {
        return new Api.SentTap() {
            @Override
            public void sent(String session, String action, long messageId, String preview, JsonObject params) {
                Store s = store;
                if (s == null) return;
                long sid = s.sentAdd(session, action, messageId, preview);   // ① 出站台账（一字不变）
                QqGateway g = gateway;                                       // ② 自我标记行（P0-1 新增）
                // sid 直接带过去 ⇒ 标记行的 extra.sent_id 一定是这一行的真主键（不现查、不可能错配）
                if (g != null) g.selfEcho(action, messageId, params, sid, preview);
            }
        };
    }

    /** 文件外链中转（⑧ 的一部分；没装配时返回 null）。 */
    public sair.v4.qq.Relay relay() { return relay; }

    public DynCode dyn() { return dyn; }

    public CtxBuild ctx() { return ctx; }

    public Agent agent() { return agent; }

    public Tick tick() { return tick; }

    public QqGateway gateway() { return gateway; }

    /**
     * <b>M4</b>：引用解析器（唯一实例）。装配第⑭步建；{@code null} = 还没装配到那一步
     * （调用方据此跳过 —— 引用解析是"锦上添花"，绝不能让它的缺席变成消息处理失败）。
     */
    public sair.v4.qq.QuoteCache quote() { return quote; }

    /** 插件扩展点注册表（基板⑦）：装不出来时返回 null，调用方一律当"零外挂"处理。 */
    public sair.v4.ext.ExtRegistry ext() { return ext; }

    /**
     * 标记（{@code mark:} 前缀）的可观测面：总条数、legacy 条数、按会话条数。
     * <p>技「消息标记」自己的 {@code list} 只看本会话；这里给全局视角，便于判断"标了多少、有没有旧键"。</p>
     *
     * <p><b>游标机制删除后这里去掉了一格</b>：原来还报 {@code cursor_prefix}（快照游标在 kv 里的前缀
     * {@code cursor:}）。主人 2026-09-16 取消自动快照、改成每回合现查"本会话最近 N 条聊天记录"
     * （{@code ctx.ChatWindow}），基板里不再有那种 kv 进度键，所以这一格连同它的常量一起撤了 ——
     * 标记本身（{@code mark:<safeName(session)>/<msg_id>}）一个字都没动。</p>
     */
    public JsonObject markStat() {
        JsonObject o = new JsonObject();
        o.addProperty("prefix", "mark:");
        int total = 0, legacy = 0;
        JsonObject bySession = new JsonObject();
        if (store != null) {
            try {
                List<JsonObject> rows = store.kvList("mark:", 2000);
                if (rows != null) {
                    for (JsonObject r : rows) {
                        String k = J.s(r, "k", "");
                        total++;
                        JsonObject v = J.obj(J.s(r, "v", ""));
                        if (v != null && J.b(v, "legacy", false)) legacy++;
                        int slash = k.lastIndexOf('/');
                        String sess = slash > 5 ? k.substring(5, slash) : "?";
                        bySession.addProperty(sess, J.i(bySession, sess, 0) + 1);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        o.addProperty("total", total);
        o.addProperty("legacy", legacy);
        o.add("by_session", bySession);
        return o;
    }

    /**
     * 群会话的"最近群聊"事实行（诊断口：控制台/排查时按需看一段最近的群聊）。
     *
     * <p><b>注意</b>：回合的上下文<b>不走这个方法</b>。它在事实块里看到的那一段是
     * {@code ctx.ChatWindow} 现查的「本会话最近 N 条聊天记录」（{@code chatWindowSize}，
     * 群读 {@code grouplog}、私聊/控制台读 {@code dialog}，一行一条带时间和说话人）。
     * 本方法只是把同一批数据压成一行 JSON 的旧形态，<b>今天 src 里没有任何消费者</b>
     * （保留为诊断口，删不删由主人定）。</p>
     */
    public String groupRecentFacts(long groupId, int n, int cut) {
        Conf c = conf;
        if (c == null || store == null || groupId <= 0 || n <= 0) return null;
        try {
            List<JsonObject> rows = store.recentGrouplog(groupId, n);
            if (rows.isEmpty()) return null;
            JsonArray arr = new JsonArray();
            for (JsonObject r : rows) {
                JsonObject o = new JsonObject();
                o.addProperty("ts", J.l(r, "ts", 0L));
                o.addProperty("user", J.l(r, "user_id", 0L));
                String name = J.s(r, "nickname", "");
                if (Str.has(name)) o.addProperty("name", Str.cut(name, 32));
                o.addProperty("text", Str.cut(Str.oneLine(Str.nz(J.s(r, "content", ""))), Math.max(16, cut)));
                arr.add(o);
            }
            return "grouplog_recent: " + J.json(arr);
        } catch (Throwable t) {
            if (out != null) out.warn("[v4] 最近群聊事实读取失败: " + t);
            return null;
        }
    }

    public sair.v4.term.SfwOut console() {
        return out instanceof sair.v4.term.SfwOut ? (sair.v4.term.SfwOut) out : null;
    }

    // ==================== 状态 ====================

    /** 有任一装配步骤失败（基板降级运行，但控制台照常可用）。 */
    public boolean degraded() { return !failedStep.isEmpty(); }

    /** 第一个失败的装配步骤名（全部成功时为空串）。 */
    public String failedStep() { return failedStep; }

    /** 第一个失败的装配步骤的失败原因（全部成功时为空串）。 */
    public String failedReason() { return failedReason; }

    /** 装配步骤台账副本（顺序 = 执行顺序）。<b>短锁</b>：加载期间从 EDT 读也不会被装配挡住。 */
    public Map<String, String> bootSteps() {
        synchronized (ledgerLock) {
            return new LinkedHashMap<String, String>(steps);
        }
    }

    /** 本次 init 真正新建的空数据骨架项（相对数据根路径）。 */
    public List<String> skeletonItems() {
        synchronized (ledgerLock) {
            return new java.util.ArrayList<String>(skeletonCreated);
        }
    }

    /**
     * 模型这次调用能不能真的发出去：①装配了客户端 ②要么注入了替身、要么配置了 apiKey。
     * <p>控制台 {@code chat} 用它做前置判断，避免"没配 key 也去排队"。</p>
     */
    public boolean aiReady() {
        if (ai == null) return false;
        if (injectedAi != null) return true;
        return conf != null && Str.has(conf.apiKey());
    }

    /**
     * 人读的装配报告：成功就说成功，失败就指出<b>是哪一步、因为什么</b>，随后是完整步骤台账。
     * <p>控制台 {@code status} 在降级时打它；它本身不做任何可能失败的 IO，也不等装配
     * （只在 {@link #ledgerLock} 里拷一份台账快照）。</p>
     */
    public String failureReport() {
        Map<String, String> snap;
        List<String> skel;
        synchronized (ledgerLock) {
            snap = new LinkedHashMap<String, String>(steps);
            skel = new java.util.ArrayList<String>(skeletonCreated);
        }
        StringBuilder sb = new StringBuilder();
        if (failedStep.isEmpty()) {
            sb.append("装配：全部 ").append(snap.size()).append(" 步成功\n");
        } else {
            sb.append("装配失败于「").append(failedStep).append("」：").append(failedReason).append("\n");
            sb.append("基板降级运行 —— 控制台命令（help/status/tools/config…）照常可用。\n");
        }
        if (!snap.isEmpty()) {
            sb.append("步骤台账：\n");
            for (Map.Entry<String, String> e : snap.entrySet()) {
                sb.append("  ").append(e.getValue().startsWith("ok") ? "[ok]     " : "[FAILED] ")
                  .append(e.getKey()).append(": ").append(e.getValue()).append("\n");
            }
        }
        if (!skel.isEmpty()) {
            sb.append("本次新建的空数据骨架 ").append(skel.size()).append(" 项：")
              .append(Str.join(skel, ", ")).append("\n");
        }
        return sb.toString();
    }

    /**
     * 运行状态（控制台 status 用）。<b>不抛异常</b>：任一步骤失败时给出的字段一律降级，不 NPE。
     * <p>加载中（{@link #loading()}）走<b>轻量面</b>：只报"第几步、已耗时、台账"，<b>不碰</b>
     * 六库/NapCat/中转 —— 那些对象正在被装配线程初始化，去读它们既可能争库锁、
     * 又可能读到半拉状态。这是"加载期间 status 立即回话"的实现要点。</p>
     */
    public JsonObject status() {
        if (loading()) return loadingStatus();
        if (!started) return notStartedStatus();
        try {
            return statusRich();
        } catch (Throwable t) {
            // 兜底：状态本身也不能成为"控制台不响应"的原因
            JsonObject o = new JsonObject();
            o.addProperty("started", started);
            o.addProperty("degraded", true);
            o.addProperty("failed_step", failedStep.isEmpty() ? "status" : failedStep);
            o.addProperty("failed_reason", failedReason.isEmpty() ? String.valueOf(t) : failedReason);
            o.addProperty("status_error", String.valueOf(t));
            o.addProperty("data_dir", root == null ? "" : root.getAbsolutePath());
            return o;
        }
    }

    /** 加载中的状态面（轻量、零阻塞）：步骤进度 + 台账 + 已失败步骤。 */
    private JsonObject loadingStatus() {
        JsonObject o = new JsonObject();
        o.addProperty("loading", true);
        o.addProperty("started", false);
        o.addProperty("degraded", degraded());
        o.addProperty("step_no", stepNo);
        o.addProperty("step_total", STEP_TOTAL);
        o.addProperty("step", stepName);
        o.addProperty("elapsed_ms", bootElapsedMs());
        o.addProperty("failed_step", failedStep);
        o.addProperty("failed_reason", failedReason);
        o.addProperty("data_dir", root == null ? "" : root.getAbsolutePath());
        o.add("boot", bootJson(true));
        return o;
    }

    /**
     * <b>没启动</b>的状态面（新口径专用）：{@code ai/start} 之前基板是不工作的，
     * 这是一个正常状态，不是"加载中"，也不许看起来像崩溃或挂住。
     *
     * <p>要一眼说清的只有两件事：①{@code ready=0 reason=not_started}；
     * ②{@code config.json} 里有几个键与已生效的值不一样（{@code pending_keys} = 待生效的个数，
     * {@code -1} = 配置还<b>一次都没加载过</b>，也就是整个文件都还没生效）。
     * 详细清单由 {@code term.Cmd} 打成可读的几行（它手上有 conf 或壳配置）。</p>
     */
    private JsonObject notStartedStatus() {
        JsonObject o = new JsonObject();
        o.addProperty("loading", false);
        o.addProperty("started", false);
        o.addProperty("ready", 0);
        o.addProperty("reason", "not_started");
        o.addProperty("degraded", false);
        o.addProperty("data_dir", root == null ? "" : root.getAbsolutePath());
        Conf c = conf;
        o.addProperty("config_loaded", c != null);
        int pending = -1;
        JsonArray keys = new JsonArray();
        if (c != null) {
            try {
                List<String> p = c.pendingKeys();
                pending = p.size();
                for (String k : p) keys.add(k);
            } catch (Throwable ignored) {
                pending = -1;
            }
        }
        o.addProperty("pending_keys", pending);
        // 主人 2026-09-16 定的字段名：pending=<N>（与 reason / ready 同一行口径）
        o.addProperty("pending", pending);
        if (pending > 0) o.add("pending_names", keys);
        o.addProperty("hint", "基板未启动：输入 ai/start 让它加载 config.json 并运行"
                + "（config.json 的改动只有 ai/start / ai/restart 才会被读进生效面）");
        o.add("boot", bootJson(false));
        return o;
    }

    /** 装配台账的 JSON 面（{@code status.boot}）；{@code loading} 时多带进度字段。 */
    private JsonObject bootJson(boolean loading) {
        JsonObject bootJson = new JsonObject();
        bootJson.addProperty("loading", loading);
        bootJson.addProperty("started", started);
        bootJson.addProperty("degraded", degraded());
        bootJson.addProperty("failed_step", failedStep);
        bootJson.addProperty("failed_reason", failedReason);
        if (loading) {
            bootJson.addProperty("step_no", stepNo);
            bootJson.addProperty("step_total", STEP_TOTAL);
            bootJson.addProperty("step", stepName);
            bootJson.addProperty("elapsed_ms", bootElapsedMs());
        }
        JsonObject st = new JsonObject();
        for (Map.Entry<String, String> e : bootSteps().entrySet()) st.addProperty(e.getKey(), e.getValue());
        bootJson.add("steps", st);
        JsonArray skel = new JsonArray();
        for (String s : skeletonItems()) skel.add(s);
        bootJson.add("skeleton_created", skel);
        return bootJson;
    }


    private JsonObject statusRich() {
        Conf c = conf;
        JsonObject o = new JsonObject();
        o.addProperty("loading", loading());
        o.addProperty("started", started);
        o.addProperty("ready", started ? 1 : 0);
        o.addProperty("degraded", degraded());
        o.addProperty("failed_step", failedStep);
        o.addProperty("failed_reason", failedReason);
        // 待生效的键数（文件里的值与已生效的值不一样的个数）；-1 = 配置还没加载
        int pending = -1;
        try {
            if (c != null) pending = c.pendingKeys().size();
        } catch (Throwable ignored) { }
        o.addProperty("pending", pending);
        o.addProperty("data_dir", c != null ? c.root().getAbsolutePath()
                : (root == null ? "" : root.getAbsolutePath()));
        o.addProperty("model", c == null ? "" : c.resolveModel());
        o.addProperty("model_configured", c == null ? "" : c.model());
        o.addProperty("auto_model", c != null && c.isAutoModel());
        o.addProperty("api_key_set", c != null && Str.has(c.apiKey()));

        JsonObject qq = new JsonObject();
        qq.addProperty("enabled", c != null && c.napcatEnabled());
        qq.addProperty("listening", link != null && link.running());
        qq.addProperty("connected", link != null && link.connected());
        qq.addProperty("clients", link == null ? 0 : link.clients());
        qq.addProperty("port", c == null ? 0 : c.napcatPort());
        qq.addProperty("note", link != null && link.connected() ? "" : "Napcat 没有连接");
        o.add("napcat", qq);

        o.add("relay", relay == null ? new JsonObject() : relay.status());

        // ⑨ 控制台原生交互的可见面：捕获缓冲游标/条数/字符数 + 开关/转发档（机制见 ConsoleTap）
        o.add("console", sair.v4.term.SfwOut.tapStatus());
        // 转发队列（EDT 单写者）：待落盘条数 / 丢过的条数 / 已落盘段数 —— 控制台"看起来有没有输出"看这里
        o.add("console_print", sair.v4.term.ConsoleTap.printStat());

        JsonObject ui = new JsonObject();
        ui.addProperty("panel", talkPanel != null);
        ui.addProperty("title", talkPanel == null ? "" : Str.nz(talkPanel.getName()));
        ui.addProperty("entries", talkPanel == null ? 0 : talkPanel.entryCount());
        ui.addProperty("chars", talkPanel == null ? 0 : talkPanel.charCount());
        ui.addProperty("max_entries", talkPanel == null ? 0 : talkPanel.maxEntries());
        ui.addProperty("max_chars", talkPanel == null ? 0 : talkPanel.maxChars());
        ui.addProperty("mounted", sair.v4.ui.TalkMount.mounted());
        o.add("talk_panel", ui);

        JsonObject sk = new JsonObject();
        sk.addProperty("skills", skills == null ? 0 : skills.size());        sk.addProperty("tools_from_skills", skills == null ? 0 : skills.toolCount());
        sk.addProperty("hooked", skills == null ? 0 : skills.hookCount());
        sk.addProperty("registry", registry == null ? 0 : registry.size());
        // 后台编译（skillsAsync=true）时的可见面：还在编 / 编了多久 / 编挂了没有
        sk.addProperty("compiling", skillsCompiling);
        sk.addProperty("compile_ms", skillsCompileMs);
        sk.addProperty("compile_error", skillsError);
        o.add("skills", sk);

        JsonObject pc = new JsonObject();
        pc.addProperty("identity_chars", prompts == null ? 0 : prompts.identity().raw().length());
        pc.addProperty("identity_sections", prompts == null ? 0 : prompts.identity().sectionNames().size());
        pc.addProperty("prompt_files", prompts == null ? 0 : prompts.files().size());
        pc.addProperty("injects", prompts == null ? 0 : prompts.inject().list(Inject.GLOBAL).size());
        o.add("prompts", pc);

        o.add("store", store == null ? new JsonObject() : store.stat());

        // 基板⑦ 的可见面：谁挂了哪些扩展点、贡献了多少字符、花了多少毫秒、错了/超时了几次。
        // "外挂越多，这一面越重要" —— 出体感问题时先看这里。
        o.add("ext", ext == null ? new JsonObject() : ext.stat());

        JsonObject rt = new JsonObject();
        rt.addProperty("tick", tick == null ? 0 : tick.count());
        rt.addProperty("tick_ms", c == null ? 0 : c.tickMs());
        rt.addProperty("tasks_running", agent == null ? 0 : agent.running());
        rt.addProperty("alarms", tick == null ? 0 : tick.alarms().size());
        // 队列/回合/闸门/钩子/事件的可观测面（旧口径只有一个 queue 数字）
        rt.addProperty("queue_size", queueSize());
        JsonObject q = lanes == null ? new JsonObject() : lanes.stat();
        q.addProperty("console_depth", turns.getQueue().size());
        q.addProperty("console_max", 64);
        rt.add("queue", q);
        rt.add("model_gate", ai == null ? new JsonObject() : ai.gateStat());
        rt.add("marks", markStat());
        rt.add("hooks", hookPool == null ? new JsonObject() : hookPool.stat());
        rt.add("events", link == null ? new JsonObject() : link.eventStat());
        o.add("runtime", rt);

        // 装配台账（哪一步失败了在这里一目了然）
        o.add("boot", bootJson(false));
        return o;
    }

    /** 权限矩阵 + 可见工具（诊断）。 */
    public String permissions(Caller c) {
        StringBuilder sb = new StringBuilder();
        sb.append("调用者: ").append(c == null ? "(无)" : c.label()).append("\n");
        if (registry == null) {
            sb.append("工具面未装配（装配步骤「工具注册表」失败）\n");
            if (auth == null) sb.append("权限门禁未装配（装配步骤「权限门禁」失败）\n");
            return sb.toString();
        }
        sb.append("可见工具 ").append(registry.visible(c).size()).append(" / 全部 ").append(registry.size()).append("\n");
        sb.append(registry.describe(c));
        // P9b-2：这里原来还打印一块"配置里的权限覆盖"（auth.matrix() = 旧档位表的全部行）——
        // 整块已删：旧表随 PermTable 一起消失，权限的唯一账本是数据根的 perms.json。
        // 看账本请用控制台 ai/acl（只读），授权/撤销走 perm 工具的 grant/revoke。
        return sb.toString();
    }

    /** 六库统计（简洁文本）；库不可用时给出原因而不是抛异常。 */
    public String storeStat() {
        if (store == null) return "六库未装配（装配步骤「六库」失败：" + failedReason + "）";
        JsonObject s = store.stat();
        StringBuilder sb = new StringBuilder();
        sb.append("库文件 ").append(J.s(s, "db", "")).append("（").append(J.l(s, "size", 0)).append(" 字节）\n");
        JsonObject libs = J.sub(s, "libs");
        if (libs != null) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : libs.entrySet()) {
                sb.append("  ").append(e.getKey()).append(": ").append(e.getValue().getAsString()).append("\n");
            }
        }
        sb.append("FTS5 ").append(J.b(s, "fts", false) ? "可用" : "不可用（检索回退 LIKE）");
        if (store.db() != null && !store.db().isOpen()) sb.append("\n注意：数据库连接未打开，以上计数不可信");
        return sb.toString();
    }

    private void log(String msg) {
        if (out != null) out.dim("[v4] " + msg);
    }

    // ==================== 机器人自身在群里的身份 ====================

    private final java.util.Map<Long, long[]> selfRoleCache = new java.util.concurrent.ConcurrentHashMap<Long, long[]>();

    /** 机器人在该群是不是管理员/群主（60 秒缓存；未连接返回 false）。 */
    public boolean selfIsGroupAdmin(long groupId) {
        if (groupId <= 0 || guardedApi == null || !guardedApi.available()) return false;
        long[] cached = selfRoleCache.get(groupId);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached[0] < 60_000L) return cached[1] == 1L;
        boolean admin = false;
        try {
            JsonObject params = new JsonObject();
            params.addProperty("group_id", groupId);
            params.addProperty("user_id", conf.selfId());
            params.addProperty("no_cache", true);
            // 查"机器人自己在群里的角色"是她的自主行为：按 SYSTEM（E:X）判，不是按触发者判。
            sair.v4.ctx.Ctx.Scope sc = sair.v4.ctx.Ctx.of(
                    Caller.systemActor(conf == null ? 0L : conf.masterQQ()));
            JsonObject r;
            try {
                r = guardedApi.call("get_group_member_info", params);
            } finally {
                sc.close();
            }
            String role = J.s(J.sub(r, "data"), "role", "");
            admin = "owner".equalsIgnoreCase(role) || "admin".equalsIgnoreCase(role);
        } catch (Throwable ignored) {
        }
        selfRoleCache.put(groupId, new long[] {now, admin ? 1L : 0L});
        return admin;
    }

    /** 好感度落库适配（把 store 的 favor 表接成 auth 的窄接口）。 */
    private static final class StoreFavor implements FavorStore {
        private final Store store;

        StoreFavor(Store store) { this.store = store; }

        @Override
        public double favor(long qq) { return store.favor(qq); }

        @Override
        public void setFavor(long qq, double value, String level, String note) {
            store.setFavor(qq, value, level, note);
        }

        @Override
        public List<JsonObject> top(int limit) { return store.favorTop(limit); }

        @Override
        public int clear() { return store.clearFavor(); }
    }

    /** 给控制台/工具用的 JSON 数组包装。 */
    public static JsonArray arr(List<?> list) {
        JsonArray a = new JsonArray();
        for (Object o : list) {
            if (o instanceof JsonObject) a.add((JsonObject) o);
            else a.add(String.valueOf(o));
        }
        return a;
    }
}
