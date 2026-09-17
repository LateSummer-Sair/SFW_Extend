package sair.v4.term;

import com.google.gson.JsonObject;

import java.io.File;
import java.util.List;
import java.util.concurrent.Future;

import sair.v4.Boot;
import sair.v4.Conf;
import sair.v4.Tick;
import sair.v4.auth.Acl;
import sair.v4.auth.Caller;
import sair.v4.hot.Sk;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 控制台命令表（基板⑨）：<b>只保留"对基板操作"的命令</b>。
 * <p>业务能力一律走 AI 工具面（技能提供），控制台不再堆一堆业务命令。
 * 控制台输入 ≡ QQ 中的主人交互：{@code chat} 就是主人说话。</p>
 *
 * <h3>永不失联（硬要求）</h3>
 * <ul>
 *   <li>{@link #route(String, String)} 与 {@link #help()} <b>不向 SFW 框架层抛异常</b>；
 *       任何命令炸掉都收敛成一行可读的失败 + 允许继续输入下一条；</li>
 *   <li><b>不依赖模型/DB/NapCat 的命令永远可用</b>：{@code help}/{@code status}/{@code config}/{@code tools}
 *       （{@code status} 在装配部分失败时会指出是哪一步失败、因为什么）；</li>
 *   <li>{@code chat} 三层前置判断（装配齐备 / apiKey / 队列未满）+ <b>等待上限</b>，
 *       绝不在队列堵死时把控制台永久卡住。</li>
 * </ul>
 */
public final class Cmd {

    /**
     * {@code chat} 等待回合结束的默认上限（毫秒）；配置键 {@code chatTimeoutMs} 可调。
     * <p>值取自 {@link Conf#DEF_CHAT_TIMEOUT_MS}（B10：出厂默认只此一处，
     * {@code config} 自省面读的是 {@link Conf#chatTimeoutMs()}，两边不可能漂开）。</p>
     */
    public static final int DEF_CHAT_TIMEOUT_MS = (int) Conf.DEF_CHAT_TIMEOUT_MS;

    /**
     * 基板生命周期面（由 {@code sair.v4.V4Activity} 实现；探针给 {@code Cmd} 直接传 boot 时为 null）。
     * <p>控制台 {@code start}/{@code restart} 只负责"说"，动作与状态机在宿主里：
     * 幂等启动、停旧重装、<b>同时只允许一条装配/重启线程</b>。</p>
     */
    public interface Life {

        /** 生命周期动作的结果。 */
        enum R {
            /** 已受理（装配线程起来了，进度会打在控制台）。 */
            ACCEPTED,
            /** 已经在运行（幂等，不做任何动作）。 */
            RUNNING,
            /** 已经有一条装配/重启在跑：本次拒绝（不并发）。 */
            BUSY,
            /** 当前环境起不来（组件已卸载等）。 */
            UNAVAILABLE
        }

        /** 现在是不是"重启中"（{@code status} 的措辞据此说"重启中"）。 */
        boolean restarting();

        /** 幂等启动：未加载就加载；已加载不重启。 */
        R start();

        /** 停旧基板 → 重读 config.json → 重新装配（不并发）。 */
        R restart();
    }

    private final Boot boot;
    private final Out out;
    private final String name;
    /** 生命周期宿主（V4Activity）；null = 没有组件入口（探针/裸 Cmd）。 */
    private final Life life;
    /**
     * 数据根提示：{@code boot} 还没建 / 已经 exit 掉时，用它定位
     * {@code prompts/tools-index.md}（start/restart 的回话文案在那里）。
     */
    private volatile java.io.File dataRootHint;

    /** 设数据根提示（可选）：没有 boot 时也能读到外挂文案。 */
    public void setDataRoot(java.io.File root) { this.dataRootHint = root; }

    public Cmd(Boot boot, String name) {
        this(boot, name, null);
    }

    public Cmd(Boot boot, String name, Life life) {
        this.boot = boot;
        this.life = life;
        this.out = boot == null || boot.out() == null ? sair.v4.kit.PlainOut.echo() : boot.out();
        this.name = Str.blank(name) ? "ai4" : name;
    }

    /** 装配失败时的降级构造：只有名字与输出落点，所有命令都给可读的失败而不是异常。 */
    public Cmd(String name, Out out) {
        this(name, out, null);
    }

    public Cmd(String name, Out out, Life life) {
        this.boot = null;
        this.life = life;
        this.out = out == null ? sair.v4.kit.PlainOut.echo() : out;
        this.name = Str.blank(name) ? "ai4" : name;
    }

    /** 路由一条命令；返回 null 表示已处理。<b>本方法不抛异常</b>。 */
    public Object route(String func, String args) {
        String f = Str.nz(func).trim().toLowerCase();
        String a = Str.nz(args).trim();
        try {
            if (f.isEmpty() || "help".equals(f)) {
                printHelp();
                return null;
            }
            if ("chat".equals(f) || "say".equals(f)) {
                if (Str.blank(a)) {
                    out.warn("用法：" + f + " <要说的话>");
                    return null;
                }
                chat(a);
                return null;
            }
            if ("stop".equals(f)) {
                if (boot == null || boot.agent() == null) {
                    out.warn("stop 不可用：Agent 未装配（" + why() + "）");
                    return null;
                }
                // 控制台 = 主人：停全部（QQ 侧同一个命令只停自己那个会话的回合，
                // 见 sair.v4.tool.Builtins 里的 agent 工具与 Agent.stop(session, master)）
                int n = boot.agent().stop(null, true);
                out.ok("已请求中断当前输出（停了 " + n + " 个在跑的回合）");
                return null;
            }
            if ("reset".equals(f)) {
                if (boot == null || boot.store() == null) {
                    out.warn("reset 不可用：六库未装配（" + why() + "）");
                    return null;
                }
                int n = boot.store().clearDialog("console");
                out.ok("已重置控制台会话上下文（清掉 " + n + " 条历史）");
                return null;
            }
            if ("status".equals(f)) {
                status();
                return null;
            }
            if ("start".equals(f)) {
                startCmd();
                return null;
            }
            if ("restart".equals(f)) {
                restartCmd();
                return null;
            }
            if ("tools".equals(f)) {
                tools(a);
                return null;
            }
            if ("perm".equals(f)) {
                perm(a, caller());
                return null;
            }
            if ("acl".equals(f)) {
                aclCmd();
                return null;
            }
            if ("config".equals(f)) {
                config(a);
                return null;
            }
            if ("debug".equals(f)) {
                debug(a);
                return null;
            }
            if ("skill".equals(f)) {
                skill(a);
                return null;
            }
            if ("prompt".equals(f)) {
                promptCmd(a);
                return null;
            }
            if ("napcat".equals(f)) {
                napcat(a);
                return null;
            }
            if ("store".equals(f)) {
                storeCmd(a);
                return null;
            }
            if ("tick".equals(f)) {
                if (boot == null || boot.tick() == null) {
                    out.warn("tick 不可用：心跳未装配（" + why() + "）");
                    return null;
                }
                boot.tick().beat();
                out.ok("手动心跳完成（第 " + boot.tick().count() + " 次）");
                return null;
            }
            if ("alarm".equals(f)) {
                if (boot == null || boot.tick() == null) {
                    out.warn("alarm 不可用：心跳未装配（" + why() + "）");
                    return null;
                }
                out.print(J.pretty(boot.tick().alarmsJson()) + "\n", Out.Tone.NORMAL);
                return null;
            }
            if ("execs".equals(f) || "execq".equals(f) || "execfc".equals(f) || "orchestrate".equals(f)) {
                out.warn("命令 " + f + " 属于已删除的「通道」概念：V4 只有一条入口，直接用 "
                        + name() + "/chat <内容>。");
                out.dim("  工具能不能用完全由权限决定（主人 = MASTER 一律全放行），不再有按通道裁剪的工具表。");
                if (!Str.blank(a)) chat(a);
                return null;
            }
            out.warn("未知命令：" + f + "（输入 " + name() + "/help 看命令表）");
            return null;
        } catch (Throwable t) {
            // 命令级兜底：一条命令炸掉绝不影响下一条（SFW 控制台必须一直有响应）
            out.err("命令 " + f + " 执行异常：" + t);
            out.dim("  控制台未受影响：可用 " + name() + "/help、/status、/tools、/config 继续操作。");
            if (boot != null && boot.degraded()) out.dim("  " + oneLine(boot.failureReport()));
            return null;
        }
    }

    // ==================== 各命令 ====================

    /**
     * {@code start}：<b>唯一的"加载并运行"命令</b>（新口径：其他命令都只是改配置文件）。
     * 未加载就加载（读 {@code config.json} + 跑 19 步装配）；已在运行只回一句"已在运行"
     * （<b>绝不重启</b>，并说清"要应用文件里的改动得 restart"）；已经有一条装配/重启在跑就明确拒绝
     * （不并发）。
     * <p>没有生命周期宿主（探针直接给 {@code Cmd} 一个 boot）时退化成"同步装配一次"，便于单测。</p>
     */
    private void startCmd() {
        Life l = life;
        if (l == null) {
            if (boot == null) {
                out.warn("start 不可用：基板未创建（" + why() + "）");
                return;
            }
            if (boot.started()) {
                lifeLine(Out.Tone.OK, "已在运行回话", name(), "", "", "",
                        boot.skills() == null ? 0 : boot.skills().size(),
                        boot.registry() == null ? 0 : boot.registry().size());
                out.dim("  本次 start 什么都没做（不重启）。要应用 config.json 里的改动："
                        + name() + "/restart（本环境没有生命周期宿主 —— 探针里就等于重新 "
                        + "new Boot(数据根, out).init()）。");
                pendingHint();
                return;
            }
            if (boot.loading()) {
                lifeLine(Out.Tone.WARN, "装配中回话", name() + "/start", "", "", boot.progressLine(), 0, 0);
                return;
            }
            boot.init();                       // 无宿主：同步装一次（探针口径）
            out.ok("已同步装配（本环境没有基板生命周期宿主，start 直接在调用线程上跑了一次 init）");
            return;
        }
        Life.R r = l.start();
        switch (r) {
            case RUNNING:
                lifeLine(Out.Tone.OK, "已在运行回话", name(), "", "", "",
                        boot == null || boot.skills() == null ? 0 : boot.skills().size(),
                        boot == null || boot.registry() == null ? 0 : boot.registry().size());
                // 新口径的硬要求：已在运行时**只回话、绝不重启**，但必须把"要怎么才能生效"说清
                out.dim("  本次 start 什么都没做（不重启）。要应用 config.json 里的改动："
                        + name() + "/restart（先停旧基板，再读文件重新装配）。");
                pendingHint();
                return;
            case BUSY:
                lifeLine(Out.Tone.WARN, "装配中回话", name() + "/start", "", "", progressNow(), 0, 0);
                return;
            case ACCEPTED:
                lifeLine(Out.Tone.OK, "启动已受理回话", name(), "", "", "", 0, 0);
                return;
            default:
                out.warn("start 不可用：组件已卸载（要重新加载请重装/重载插件，或重启 SFW）。");
        }
    }

    /**
     * {@code restart}：停旧基板（收库 / 收监听 / 收线程池）→ 重读 {@code config.json} →
     * 重新跑 19 步装配（进度照旧打在控制台）。同一时刻只允许一条装配/重启线程。
     */
    private void restartCmd() {
        Life l = life;
        if (l == null) {
            out.warn("restart 需要 SFW 组件入口（" + name() + "）：本环境没有基板生命周期宿主，"
                    + "探针里请直接 new Boot(数据根, out).init() 或走 V4Activity。");
            return;
        }
        Life.R r = l.restart();
        switch (r) {
            case BUSY:
                lifeLine(Out.Tone.WARN, "装配中回话", name() + "/restart", "", "", progressNow(), 0, 0);
                return;
            case ACCEPTED:
                lifeLine(Out.Tone.OK, "重启已受理回话", name(), "", "", "", 0, 0);
                return;
            case RUNNING:
                // 正常不会走到（restart 一定重装）：兜底也别静默
                out.warn("restart 未执行：宿主报告已有一条装配在跑（" + progressNow() + "）");
                return;
            default:
                out.warn("restart 不可用：组件已卸载（要重新加载请重装/重载插件，或重启 SFW）。");
        }
    }

    /** 当前进度串（没有 boot / 已就绪时给一句人读的）。 */
    private String progressNow() {
        Boot b = boot;
        if (b == null) return "（无基板）";
        if (b.loading()) return b.progressLine();
        return "已就绪";
    }

    /** 生命周期文案：从 {@code prompts/tools-index.md} 取；缺键就报缺键，绝不编兜底话。 */
    private void lifeLine(Out.Tone tone, String key, String cmdPrefix, String cfgKey, String value,
                          String progress, int skills, int tools) {
        String t;
        try {
            sair.v4.tool.ToolIndex ix = index();
            t = ix == null ? null : ix.lifeText(key, name(), cfgKey, value, progress, skills, tools);
        } catch (Throwable e) {
            t = null;
        }
        if (t == null) {
            out.err("[v4] prompts/tools-index.md 缺键「" + key + "」—— 这句话不发（基板不编兜底文案）");
            return;
        }
        out.print(t + "\n", tone);
    }

    /** 定位 {@code prompts/tools-index.md}：conf 还没建好/已停时按数据根直接找。 */
    private sair.v4.tool.ToolIndex index() {
        Boot b = boot;
        if (b == null) {
            java.io.File r = dataRootHint;
            return r == null ? null : sair.v4.tool.ToolIndex.ofRoot(r);
        }
        if (b.conf() != null) return sair.v4.tool.ToolIndex.of(b.conf());
        return sair.v4.tool.ToolIndex.ofRoot(b.root());
    }

    /**
     * 状态：<b>没启动</b>就说清"没启动 + 有几个键待生效"；加载中给进度；
     * 装配部分失败时明确指出"哪一步失败、因为什么"。
     */
    private void status() {
        if (boot == null) {
            out.warn("status 不可用：基板没装配起来（" + why() + "）");
            out.dim("  " + name() + "/start 可以让它加载并运行；其余命令在装配成功后可用。");
            return;
        }
        // —— 没启动（新口径下最常见的那一态）：不许看起来像加载中/崩溃/挂住 ——
        if (!boot.started() && !boot.loading()) {
            Conf conf = boot.conf();
            boolean shell = conf == null;
            if (conf == null) conf = shellConf();
            out.print("未启动（ready=0 reason=not_started）：基板还没跑起来。\n", Out.Tone.WARN);
            out.dim("  输入 " + name() + "/start 让它加载 config.json 并运行（19 步装配；"
                    + "装配跑在自己的线程上，控制台不受影响）。");
            out.print(J.pretty(boot.status()) + "\n", Out.Tone.NORMAL);
            if (conf == null) {
                out.dim("  配置文件：读不到（数据根还没解析出来）。");
                return;
            }
            out.dim("  配置文件：" + conf.file().getAbsolutePath());
            if (shell) {
                out.dim("  pending=-1（还没加载过配置：整个文件都待生效 —— 它一个键都还没被读进生效面）。");
                return;
            }
            java.util.List<String> pend = conf.pendingKeys();
            out.print(pendingText(pend) + "\n", pend.isEmpty() ? Out.Tone.DIM : Out.Tone.WARN);
            if (pend.isEmpty()) {
                out.dim("  文件里的值与已生效的值一致（没有待生效的键）。");
            }
            return;
        }
        boolean loading = boot.loading();
        if (loading) {
            // 加载中：第一行是"到哪一步了"，随后是状态 JSON（轻量面，不碰六库/NapCat）
            out.print((boot.restarting() ? "重启中：" : "加载中：") + boot.progressLine()
                    + "（装配在自己的线程上跑，控制台不受影响）\n", Out.Tone.WARN);
        }
        out.print(J.pretty(boot.status()) + "\n", Out.Tone.NORMAL);
        if (boot.degraded()) {
            out.warn("基板降级运行 —— 失败步骤报告：");
            out.print(boot.failureReport(), Out.Tone.WARN);
        } else if (loading) {
            out.dim("  装配还在跑：help/status/tools/config 现在就能用；chat 等装完再试（"
                    + boot.progressLine() + "）。");
        } else {
            // 已就绪也要**总是**打这一行：主人要能看见"待生效清单变空了"
            pendingLine();
        }
    }

    /**
     * 已就绪时的待生效面（{@code pending=<N>} + 点名，最多 8 个；空清单也照打，好让人看见"清空了"）。
     */
    private void pendingLine() {
        try {
            Conf conf = boot == null ? null : boot.conf();
            if (conf == null) {
                out.dim("pending=-1（配置还没加载：基板未启动）");
                return;
            }
            java.util.List<String> pend = conf.pendingKeys();
            out.print(pendingText(pend) + "\n", pend.isEmpty() ? Out.Tone.DIM : Out.Tone.WARN);
            if (!pend.isEmpty()) {
                out.dim("  （文件里是新值、跑着的基板还是旧值 —— 要 " + name() + "/restart 才生效）");
            }
        } catch (Throwable t) {
            out.dim("pending=-1（算不出来：" + t + "）");
        }
    }

    /** 已就绪时的一句"文件里有哪几个键待生效"（没有就什么都不打）。 */
    private void pendingHint() {
        try {
            Conf conf = boot == null ? null : boot.conf();
            if (conf == null) return;
            java.util.List<String> pend = conf.pendingKeys();
            if (pend.isEmpty()) return;
            out.warn("＊ " + pendingText(pend));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 可见工具；工具面没装配起来时给一句可读的失败，而不是空输出或异常。
     * <p>加载中也不等装配：注册表还没建就报"到第几步了"，建好了就把当前工具面打出来，
     * 并说明技能工具会在「技能扫描」之后陆续出现。</p>
     */
    private void tools(String a) {
        if (boot == null) {
            out.warn("工具面不可用：注册表未装配（" + why() + "）");
            out.dim("  " + name() + "/status 可看到是哪一步失败；装配修好后本命令自动恢复。");
            return;
        }
        if (boot.registry() == null) {
            // 新口径下最常见的原因就是"还没 ai/start"—— 这条命令必须照常回话（不是空输出、不是异常）
            out.warn("工具面还没装配：" + why());
            out.dim("  基板工具在装配的「工具面」那一步注册（17 个），技能工具在「技能扫描」之后陆续出现；"
                    + "输入 " + name() + "/start 开始装配。");
            return;
        }
        out.print(boot.registry().describe("all".equalsIgnoreCase(a) ? null : caller()), Out.Tone.NORMAL);
        if (boot.loading()) {
            out.dim("  （装配还在跑：" + boot.progressLine() + " —— 现在看到的是当前工具面，"
                    + "技能工具会陆续注册）");
        }
    }

    /** 控制台调用者 = 主人（MASTER）。配置没加载时也要能构造出来。 */
    private Caller caller() {
        long master = 0L;
        try {
            if (boot != null && boot.conf() != null) master = boot.conf().masterQQ();
        } catch (Throwable ignored) {
        }
        return Caller.console(master);
    }

    /** 一句可读的"缺什么"：未启动 / 加载中 / 失败步骤。 */
    private String why() {
        if (boot == null) return "基板未创建";
        if (boot.loading()) return "加载中：" + boot.progressLine();
        if (boot.degraded()) return "装配失败于「" + boot.failedStep() + "」：" + boot.failedReason();
        if (!boot.started()) return "基板未启动（输入 " + name() + "/start 让它加载 config.json 并运行）";
        return "基板未就绪（init 未完成）";
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace("\n", " ").replace("\r", " ").trim();
    }

    /**
     * 主人说话：走完整 Agent 链路（与 QQ 主人消息完全同一条路）。
     *
     * <p>四道前置判断 + 等待上限，任何一道都不抛异常：装配未齐备 / 模型没配 apiKey /
     * 回合队列已满 / 等待超时 —— 控制台永远能在有限时间内拿回提示符。</p>
     */
    private void chat(String text) {
        if (boot == null) {
            out.warn("chat 不可用：基板没装配起来（" + why() + "）");
            return;
        }
        // 还在装配（一条命令都没失败过）→ 回一句"还在初始化"，文案在 prompts/tools-index.md，缺键报错不兜底。
        // 装配已经失败（degraded）时走下面的老路径：那种情况要说清"失败于哪一步"，不能说"稍等一下"。
        if (!boot.started() && boot.loading() && !boot.degraded()) {
            String line = initializingText();
            if (line == null) {
                out.err("[v4] prompts/tools-index.md 缺键「初始化中文案」—— 这一路提示不发"
                        + "（基板不编兜底文案）");
            } else {
                out.print(line + "\n", Out.Tone.NORMAL);
            }
            out.dim("  加载中：" + boot.progressLine() + "；现在就能用 help/status/tools/config，"
                    + "装完这条消息再发一次即可。");
            return;
        }
        if (boot.agent() == null || boot.ctx() == null || boot.store() == null || boot.turns() == null) {
            out.warn("chat 不可用：基板未完成装配（" + why() + "）");
            out.dim("  先 " + name() + "/status 看是哪一步失败（其余命令不受影响）。");
            return;
        }
        if (boot.turns().isShutdown()) {
            out.warn("chat 不可用：回合队列已关闭（基板已 stop）——重启组件后再试。");
            return;
        }
        if (boot.ai() == null) {
            out.warn("chat 不可用：模型客户端未装配（" + why() + "）");
            return;
        }
        if (!boot.aiReady()) {
            out.warn("chat 不可用：apiKey 未配置 —— 先 " + name() + "/config set apiKey <你的 key>，"
                    + "再 " + name() + "/config reload（或重启组件）。");
            out.dim("  api 地址：" + (boot.conf() == null ? "?" : boot.conf().apiUrl())
                    + "；模型：" + (boot.conf() == null ? "?" : boot.conf().resolveModel()));
            // 基板本身还有失败步骤时一并说清（否则"apiKey 未配置"会盖住真正的原因）。
            // 这里只报"失败于哪一步"，不搬异常原文 —— 面向用户的回话里不出现 Exception 栈字样。
            if (boot.degraded()) out.dim("  另外：基板有装配失败步骤 —— 失败于「" + boot.failedStep()
                    + "」（" + name() + "/status 看台账）");
            return;
        }
        if (boot.turns().getQueue().remainingCapacity() <= 0) {
            out.warn("chat 未提交：回合队列已满（" + boot.queueSize() + " 条在等）——"
                    + "稍后再试，或先 " + name() + "/stop 中断当前回合。");
            return;
        }

        Caller c = caller();
        // 本地控制台 = 主人对话：挂了交流面板就把正文/流式打进面板，否则回落控制台主文本流。
        // 每回合新取一个（PanelSink 带"本轮是否流过式"的状态，不能跨回合复用）。
        final sair.v4.ctx.Sink sink = boot.localSink();
        // 面板是"对话"视图：主人这句话也写一份进去（否则面板里只有 AI 一侧，读起来像独角戏）。
        // 没挂面板时它什么都不做 —— 主人的话本来就在控制台自己的输入回显里。
        boot.echoConsoleUser(text);
        final sair.v4.ctx.Turn t = new sair.v4.ctx.Turn(c, sink);
        try {
            boot.ctx().assemble(t, text);
            if (boot.store() != null) boot.store().appendDialog(t.session(), "user", text, 0);
        } catch (Throwable e) {
            out.err("chat 装配上下文失败：" + e + "（控制台未受影响）");
            return;
        }

        // 这一轮在"回合线程池"里跑；本方法要不要等它说完，取决于<b>调用线程是谁</b>：
        //   · 控制台里手敲回车 → 命令在 EDT 上（ClicksAct.clicks_enter → SairCons.runner 是 EDT 直接执行），
        //     而控制台的输入框、面板重绘、控制台输出全都在 EDT 上 —— 一旦在这里等，界面就整轮卡死
        //     （实测：敲 ai/chat 后输入框卡住、面板也不流式）。所以 EDT 上**只提交、不等**：
        //     正文由 sink 流式打进面板，摘要行由回合线程在结束时补一行；
        //   · IR 脚本（外置 .ir）/ 其它后台线程 → 照旧阻塞等待，脚本的顺序语义不变。
        final boolean waitHere = !javax.swing.SwingUtilities.isEventDispatchThread();
        final boolean wantSummary = boot != null && boot.conf() != null && boot.conf().logOn("turn");
        Future<?> fut;
        try {
            fut = boot.turns().submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        sair.v4.agent.Loop.Outcome oc = boot.agent().run(t);
                        if (oc.ok() && Str.has(oc.text)) {
                            // 记忆落库用"剥过控制标记"的文本（<split> 换成空行）：聊天记录不该带标记
                            if (boot.store() != null) boot.store().appendDialog(t.session(), "assistant",
                                    sair.v4.qq.Seg.forMemory(sair.v4.qq.MarkerTags.strip(oc.text)), 0);
                            sink.say(oc.text);
                        } else if (!oc.ok()) {
                            out.err("本轮失败：" + oc.error);
                        }
                    } catch (Throwable e) {
                        out.err("本轮执行异常：" + e);
                    } finally {
                        // 不等的那条路（EDT）由回合线程收尾：摘要行照旧有人打
                        if (!waitHere && wantSummary) {
                            out.dim("[本轮 " + t.rounds() + " 轮 · " + t.elapsedMs() + "ms]");
                        }
                    }
                }
            });
        } catch (Throwable e) {
            out.warn("chat 未提交：回合队列拒绝了本条（" + e + "）——稍后再试。");
            return;
        }
        if (!waitHere) return;                     // 控制台：立刻把输入框还给主人（流式在面板里）

        long timeoutMs = timeoutMs();
        try {
            fut.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);   // 脚本/后台线程：等它跑完
        } catch (java.util.concurrent.TimeoutException te) {
            out.warn("等待回合超时（" + (timeoutMs / 1000) + " 秒）：本轮仍在后台跑，控制台不受影响。");
            out.dim("  可用 " + name() + "/stop 中断它，或 " + name() + "/status 看队列（"
                    + boot.queueSize() + " 条在等）；上限键：chatTimeoutMs。");
        } catch (java.util.concurrent.CancellationException ce) {
            out.warn("回合被取消（" + ce + "）");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            out.warn("等待回合被中断（控制台未受影响）");
        } catch (java.util.concurrent.ExecutionException ee) {
            out.err("回合执行失败：" + (ee.getCause() == null ? ee : ee.getCause()));
        } catch (Throwable e) {
            out.err("等待回合结束失败：" + e);
        }
        // 回合摘要行：默认不打（控制台只留 Agent 调用事件）；要看加 logConsole 里的 turn
        if (wantSummary) {
            out.dim("[本轮 " + t.rounds() + " 轮 · " + t.elapsedMs() + "ms]");
        }
    }

    /** {@code chat} 等待上限（配置键 {@code chatTimeoutMs}，非法值退回默认）。 */
    private long timeoutMs() {
        long ms = DEF_CHAT_TIMEOUT_MS;
        try {
            if (boot != null && boot.conf() != null) ms = boot.conf().chatTimeoutMs();
        } catch (Throwable ignored) {
        }
        return ms > 0 ? ms : DEF_CHAT_TIMEOUT_MS;
    }

    /**
     * 「初始化中文案」（{@code prompts/tools-index.md}）。装配早期 {@code conf} 可能还没建好，
     * 那时按数据根直接定位同一份文件；读到空 = 缺键 → 返回 null（调用方报错，不编话）。
     */
    private String initializingText() {
        try {
            sair.v4.tool.ToolIndex ix = index();
            if (ix == null) return null;
            return ix.initializingText(boot.stepNo(), sair.v4.Boot.STEP_TOTAL, boot.stepName(),
                    boot.bootElapsedMs());
        } catch (Throwable t) {
            out.err("[v4] 读 prompts/tools-index.md 的「初始化中文案」失败：" + t);
            return null;
        }
    }

    private void perm(String a, Caller me) {
        if (boot == null) {
            out.warn("perm 不可用：基板未创建");
            return;
        }
        String[] v = Str.verb(a);
        if (v[0].isEmpty()) {
            out.print(boot.permissions(me), Out.Tone.NORMAL);
            return;
        }
        if (boot.favor() == null) {
            out.warn("perm 不可用：好感度存储未装配（" + why() + "）");
            return;
        }
        if ("set".equalsIgnoreCase(v[0])) {
            String[] p = v[1].split("\\s+");
            if (p.length < 2) {
                out.warn("用法：perm set <QQ> <好感度>");
                return;
            }
            long qq = Long.parseLong(p[0].trim());
            double val = Double.parseDouble(p[1].trim());
            boot.favor().set(qq, val, "console");
            out.ok("已设置 " + qq + " 好感度 = " + (long) val + "（" + boot.favor().levelName(val) + "）");
            return;
        }
        if ("reset".equalsIgnoreCase(v[0])) {
            out.ok("已清空 " + boot.favor().resetAll() + " 条好感度记录");
            return;
        }
        if ("list".equalsIgnoreCase(v[0])) {
            out.print(J.pretty(Boot.arr(boot.favor().top(20))) + "\n", Out.Tone.NORMAL);
            return;
        }
        // P9b-1：这里原来还有 perm levels / perm setlevel 两个分支（旧档位注册表的读写口）—— 整组已删除。
        // 它们背靠 Boot.permTableText/permLevelText/setPerm，而那是旧档位体系写 perms.json（= ACL 账本）
        // 的入口（notes\acl-decisions.md D11）；perm 工具侧也早已没有 levels/setlevel 这些 op。
        try {
            long qq = Long.parseLong(v[0]);
            out.print(J.pretty(boot.favor().snapshot(qq)) + "\n", Out.Tone.NORMAL);
        } catch (Exception e) {
            out.warn("用法：perm [list|set <QQ> <值>|reset|<QQ>]");
        }
    }

    /**
     * {@code acl}：<b>只读</b>打印权限账本（ACL）。
     *
     * <p>账本 = 数据根的 {@code perms.json}（{@code {"version":3,"entries":[…]}}），权限的唯一判据。
     * 输出四段：{@link Acl#stat()} 的一行摘要（<b>条目数量</b> + 分类计数 + 默认分配 + 文件名）、
     * {@link Acl#defaultText()} / {@link Acl#systemText()} 的<b>默认分配行</b>（按资源类型 + 她自己那份）、
     * {@link Acl#list()} 的<b>逐条原文</b>（带主体层级、命中时生效位、范围匹配串与顺序说明）、
     * 以及被跳过的非法条目（有才打 —— 写错了要看得见，不能静默失效）。</p>
     *
     * <p><b>本命令不写盘、不改任何状态</b>：授权/撤销/查询/验算都在她那边的 {@code perm} 工具
     * （{@code op=grant/revoke/acl/whoami…}）。控制台这里只给主人一个"现在到底放开了什么"的窗口
     * （{@code notes/acl-decisions.md} D34 遗留项②）。</p>
     */
    private void aclCmd() {
        if (boot == null || boot.auth() == null) {
            out.warn("acl 不可用：权限面未装配（" + why() + "）");
            return;
        }
        Acl a = boot.auth().acl();
        if (a == null) {
            out.warn("acl 不可用：账本没能装载（" + why() + "）");
            return;
        }
        out.print(a.stat() + "\n", Out.Tone.NORMAL);
        out.print("默认分配 " + Acl.defaultText() + " · 她自己的默认分配（SYSTEM）" + Acl.systemText() + "\n",
                Out.Tone.NORMAL);
        for (String line : a.list()) out.print(line + "\n", Out.Tone.NORMAL);
        for (Acl.Reject r : a.rejects()) out.warn("非法条目已跳过：" + r);
        out.dim("账本文件：" + (a.file() == null ? "(内存账本，没落盘)" : a.file().getAbsolutePath()));
        out.dim("只读命令：授权/撤销/查询/验算请让她用 perm 工具（op=grant/revoke/acl/whoami…）。");
    }

    /**
     * 装配还没走到第①步时用的"壳配置"：直接按数据根读 {@code config.json}。
     *
     * <p>为什么需要它：外置 {@code .ir} 脚本（框架 {@code autoRun} 在 {@code autorun.ir} 之后立刻执行）
     * 里最常见的写法就是 {@code ai/config set apiUrl …}，而基板的 {@link Conf} 要到装配第①步才建出来 ——
     * 实测这些设置<b>全部被丢掉</b>（控制台只反复回"config 不可用：配置未加载"）。</p>
     *
     * <p>壳配置只负责改文件：基板第①步会再读同一份 {@code config.json}，所以设置照样生效；
     * 若第①步已经跑过（{@code boot.conf() != null}），这里根本不会被用到。</p>
     */
    private volatile Conf shellConf;

    private Conf shellConf() {
        Conf c = shellConf;
        if (c != null) return c;
        java.io.File r = dataRootHint;
        if (r == null) {
            try {
                if (boot != null) r = boot.root();
            } catch (Throwable ignored) {
            }
        }
        if (r == null) return null;
        try {
            c = new Conf(r);
            c.load();
            c.ensureDefaults();
            shellConf = c;
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code config [键 [值]]}：查看 / 修改配置。
     *
     * <p><b>生效口径（主人 2026-09-16 定稿）</b>：{@code config set} <b>只改 config.json 这个文件</b>
     * —— 运行中的基板一个键都不动；要让文件里的值生效只有 {@code ai/start}
     * （已经在跑则 {@code ai/restart}）。所以这里：</p>
     * <ul>
     *   <li>总览 / 单键都读<b>文件</b>（主人在外面手改了 config.json 也看得见），
     *       文件里的值与已生效的值不一样就标 <b>待生效（要 ai/start）</b>；</li>
     *   <li>{@code set} 走 {@link Conf#fileSet(String, Object)}（只动文件、不动内存），
     *       回执明说"未生效"；</li>
     *   <li>{@code reload}（旧的热重载）已无意义：命令保留，但只回一句"已退休"。</li>
     * </ul>
     */
    private void config(String a) {
        Conf conf = boot == null ? null : boot.conf();
        boolean shell = conf == null;
        if (conf == null) conf = shellConf();      // 装配还没到第①步：用数据根临时开一份壳配置
        if (conf == null) {
            out.warn("config 不可用：配置未加载（" + why() + "）");
            return;
        }
        String[] v = Str.verb(a);
        if (v[0].isEmpty()) {
            // 总览 = 文件里那份（不再打印内存里的生效面）；敏感键一律打码
            Conf fv = conf.fileView();
            out.print(prettyMasked(fv.all()) + "\n", Out.Tone.NORMAL);
            out.dim("配置文件：" + conf.file().getAbsolutePath());
            if (shell) {
                out.dim("基板未启动：这份配置一个键都还没加载 —— 全部待生效，输入 " + name()
                        + "/start 让它加载并运行。");
                return;
            }
            java.util.List<String> pend = conf.pendingKeys(fv);
            out.print(pendingText(pend) + "\n", pend.isEmpty() ? Out.Tone.DIM : Out.Tone.WARN);
            if (pend.isEmpty()) {
                out.dim("生效口径：文件里的值与已生效的值一致（没有待生效的键）。");
            } else {
                for (String k : pend) {
                    out.print("    " + k + " = " + mask(k, fv.get(k, "")) + "（已生效："
                            + mask(k, appliedText(conf, k)) + "）\n", Out.Tone.WARN);
                }
            }
            out.dim("改一个键：config set <键> <值>（只改文件，不重启、不热加载）。");
            return;
        }
        if ("reload".equalsIgnoreCase(v[0])) {
            out.warn("config reload 已退休：基板不再热读配置 —— 改 config.json 只是改文件，"
                    + "要 " + name() + "/start 才加载。");
            out.dim("  已经在跑：要按新配置重装用 " + name() + "/restart（它先停旧基板，再读文件重新装配 19 步）。");
            out.dim("  提示词正文有它自己的热重载：" + name() + "/prompt reload（不受本口径影响）。");
            return;
        }
        if ("set".equalsIgnoreCase(v[0])) {
            String[] p = v[1].split("\\s+", 2);
            if (p.length < 2) {
                out.warn("用法：config set <键> <值>");
                return;
            }
            String k = p[0].trim();
            String raw = p[1].trim();
            Object val = raw;
            if (raw.matches("-?\\d+")) val = Long.parseLong(raw);
            else if (raw.matches("-?\\d+\\.\\d+")) val = Double.parseDouble(raw);
            else if ("true".equalsIgnoreCase(raw) || "false".equalsIgnoreCase(raw)) val = Boolean.parseBoolean(raw);
            boolean ok = conf.fileSet(k, val);
            out.ok("已写入 config.json：" + k + " = " + mask(k, raw)
                    + (ok ? "" : "（**写盘失败**：看控制台那行告警）")
                    + "（未生效 —— 要 " + name() + "/start；已在运行则 " + name() + "/restart）");
            Conf.Key known = conf.knownKey(k);
            if (known == null) {
                out.dim("  注意：" + k + " 不在已知键表里（没有消费者读它）—— 写进去了，但不会有任何行为变化。");
            }
            return;
        }
        // 单键：读**文件**，并把它与已生效值摆在一起（不一样就是待生效）
        Conf fv = conf.fileView();
        String v0 = fv.get(v[0], null);
        if (v0 == null) {
            out.print(v[0] + " = (文件里没有这个键)\n", Out.Tone.NORMAL);
            if (!shell && conf.get(v[0], null) != null) {
                out.dim("  已生效面里有它：" + mask(v[0], conf.get(v[0], "")) + "（来自出厂默认或运行期写入）");
            }
            return;
        }
        out.print(v[0] + " = " + mask(v[0], v0) + "\n", Out.Tone.NORMAL);
        if (shell) {
            out.dim("  基板未启动：这个值还没被加载（要 " + name() + "/start）。");
        } else if (conf.isPending(v[0], fv)) {
            out.warn("  ＊ 待生效（要 " + name() + "/start；已经在跑则 " + name()
                    + "/restart）：已生效的值是 " + mask(v[0], conf.get(v[0], "")));
        } else {
            out.dim("  已生效（文件与跑着的基板一致）。");
        }
    }

    /**
     * <b>已生效</b>的那个值（人读的字符串）：优先走已知键表（含"只靠出厂默认生效"的键），
     * 表里没有才退回内存里的原始 JSON 值。
     * <p>为什么要这一手：{@code conf.get(k,"")} 读的是内存 JSON 里**写了**的键，
     * 而 {@code agentMaxRounds} 这类键常常是"文件里没写、靠出厂默认 30 跑"——
     * 直接读 JSON 会显示成空，看起来像"没有生效值"。</p>
     */
    private static String appliedText(Conf conf, String k) {
        try {
            Conf.Key live = conf.knownKey(k);
            if (live != null) return live.value();
        } catch (Throwable ignored) {
        }
        return conf.get(k, "");
    }

    /**
     * 一份配置 JSON 里的敏感键打码（其余原样）。
     * <p>总览原来直接 {@code J.pretty(conf.all())} 把 apiKey / napcatToken / relayToken 的<b>明文</b>
     * 打了出来（{@link #mask} 的注释写着"config 总览与回显都不搬原文"，实际总览漏了）；
     * 现在总览读的是文件面，密文也在打码范围内 —— 两层都不外露。</p>
     */
    private static String prettyMasked(JsonObject o) {
        if (o == null) return "{}";
        JsonObject m = new JsonObject();
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
            String k = e.getKey();
            com.google.gson.JsonElement el = e.getValue();
            String s = el == null || el.isJsonNull() ? "" : el.getAsString();
            if (Conf.isSensitive(k)) m.addProperty(k, mask(k, s));
            else m.add(k, el);
        }
        return J.pretty(m);
    }

    /** 密文/凭据键打码（config 总览与回显都不搬原文）。 */
    private static String mask(String key, String value) {
        if (value == null || value.isEmpty()) return "";
        if (Conf.isSensitive(key)) {
            return value.length() <= 8 ? "****" : value.substring(0, 4) + "…（已打码，共 "
                    + value.length() + " 字符）";
        }
        return value;
    }

    /**
     * 配置写盘后的统一回执（{@code config set} / {@code napcat on|off} / {@code napcat relay on|off} 共用）。
     * <p>它们都只是"改文件"：内存里的生效值一个字段都不动，所以回执必须说清楚"未生效"。</p>
     */
    private void wroteFile(String k, String shown, boolean ok) {
        out.ok("已写入 config.json：" + k + " = " + shown
                + (ok ? "" : "（**写盘失败**：看控制台那行告警）")
                + "（未生效 —— 要 " + name() + "/start；已在运行则 " + name() + "/restart）");
    }

    /**
     * {@code debug [on [token]|off|status|simulate on|off]}：<b>本机调试面的唯一开关</b>。
     *
     * <p>它管<b>两个口</b>：</p>
     * <ol>
     *   <li><b>输出口</b> {@link Conf#DEF_DEBUG_PORT}（2660）：读控制台捕获/对话，也能直接下框架命令；</li>
     *   <li><b>输入口</b> {@link Conf#DEF_DEBUG_SIM_PORT}（2661）：以任意 QQ 身份造入站消息 / 下发 SFW 命令。</li>
     * </ol>
     *
     * <p><b>调试落盘不归它管</b>（主人 2026-09-16 追加裁定，反掉了更早那条"同生共死"口径）：
     * {@code <数据根>/logs/console-<yyyyMMdd>.log} / {@code talk-<yyyyMMdd>.log} 是"日志"这个
     * 产品能力（日志只增不删是工程规矩），随<b>壳</b>存在 —— 进程一起来就写，口开着关着都写，
     * {@code ai/start} / {@code ai/restart} 也照写。{@code off} <b>只停两个口</b>，
     * <b>不停落盘、不删任何已存在的日志文件</b>（旧口径下"正常启动（没敲过 ai/debug on）的产品
     * 一行控制台日志都不写"，那是把日志绑在调试口上的直接后果）。</p>
     *
     * <p><b>它与基板相互独立</b>（主人 2026-09-16 裁定）：<b>不需要先 {@code ai/start}</b>，
     * {@code ai/debug on} 可以在基板没跑起来时就开（那时 {@code status} 会说
     * {@code substrate=not_started}）—— 好让人一眼分清"口开着、基板没跑"与"口也没开"。
     * 而 {@code ai/start} / {@code ai/restart} 之后，<b>同一对口</b>报的就是新的活基板
     * （{@code ready=1} + 真的技能/工具数）：两个口每次用基板都现取，绝不缓存
     * （缓存会让"先开调试口再启动"这条顺序下的口永远看着空壳）。</p>
     *
     * <p>口径与安全性质（一条都没放宽）：</p>
     * <ul>
     *   <li><b>端口固定</b> 2660 / 2661，<b>命令不接受端口参数</b>（也不看任何环境变量）；</li>
     *   <li>只绑 {@code 127.0.0.1}（回环），外部网卡够不着；</li>
     *   <li><b>必带 token</b>：不给就现生成一个（写进运行现场凭据文件
     *       {@code <数据根>/debug-port.json}，控制台只回指纹，不回 token 原文）；</li>
     *   <li><b>绝不无 token 裸开</b>：token 为空一律拒绝并打一行可读原因；</li>
     *   <li><b>只有这条命令能起停两个口</b>：装配不自动起它们，停基板也不收它们；</li>
     *   <li>仿真开关默认开（{@code ai/debug simulate off} 时端口照常听、动作回
     *       {@code -ERR debugSimulate=off}，绝不表现成"连不上"）。</li>
     * </ul>
     */
    private void debug(String a) {
        String[] v = Str.verb(a);
        String op = v[0].isEmpty() ? "status" : v[0].toLowerCase();
        if ("on".equals(op)) {
            Boot b = boot;
            if (sair.v4.dev.DebugPort.running() || sair.v4.dev.DebugSimPort.running()) {
                out.ok("调试口已经在听（" + debugFacts() + "）—— 要重开先 " + name() + "/debug off。");
                return;
            }
            String tok = Str.trim(v[1]);
            boolean generated = tok.isEmpty();
            if (generated) tok = sair.v4.dev.DebugPort.newToken();
            if (tok.isEmpty()) {          // 理论上到不了；真到了也绝不裸开
                out.warn("调试口未启动：token 为空 —— 本机调试口必须带 token（拒绝无 token 裸开）。");
                return;
            }
            boolean up = sair.v4.dev.DebugPort.start(b, out, sair.v4.dev.DebugPort.port(),
                    sair.v4.dev.DebugPort.simPort(), tok, Conf.DEF_DEBUG_SIMULATE);
            if (generated) {
                out.dim("  token 已现生成（" + tok.length() + " 字符，指纹 " + sair.v4.dev.DebugPort.fp(tok)
                        + "）—— 只写进运行现场凭据文件，不进 config.json；完整值读："
                        + credentialFile());
            }
            if (!up && !sair.v4.dev.DebugSimPort.running()) {
                out.warn("调试口没起来：上面那行 warn 就是原因（端口被占 / 保留口 / token 问题），"
                        + "基板照常可用；两个口都没有本基板的监听。落盘状态见下面这一行。");
                out.dim("  " + debugFacts());
                return;
            }
            out.ok("调试面已开：" + debugFacts());
            if (b == null || !b.started()) {
                out.dim("  注意：基板还没跑起来（" + why() + "）—— 调试口照常可用（读控制台/下命令），"
                        + "但它看到的是「未启动」的基板；两个口不缓存基板："
                        + "之后敲 " + name() + "/start（或 /restart）它们报的就是那台活基板。");
            }
            return;
        }
        if ("off".equals(op)) {
            boolean was = sair.v4.dev.DebugPort.running() || sair.v4.dev.DebugSimPort.running();
            boolean spool = sair.v4.dev.DebugLog.enabled();
            sair.v4.dev.DebugPort.stop();      // 只停两个口（落盘不归它管，见方法注释）
            if (was) {
                out.ok("调试面已关闭：" + Conf.DEF_DEBUG_PORT + "/" + Conf.DEF_DEBUG_SIM_PORT
                        + " 都不再监听；运行现场凭据文件已删。"
                        + "**落盘不受影响**（" + (spool ? "仍在写" : "本来就没在写")
                        + "：它随壳存在，与 on/off 无关），已有日志文件一个都没删。");
            } else {
                out.ok("调试面本来就没开（" + Conf.DEF_DEBUG_PORT + "/" + Conf.DEF_DEBUG_SIM_PORT
                        + " 上没有本基板的监听）。落盘是另一回事：它随壳存在，现在是 "
                        + (spool ? "在写" : "没在写") + "。");
            }
            return;
        }
        if ("simulate".equals(op)) {
            String[] p = Str.verb(v[1]);
            String want = p[0].toLowerCase();
            boolean on = !"off".equals(want) && !"false".equals(want) && !"0".equals(want);
            if (!sair.v4.dev.DebugSimPort.running()) {
                out.warn("simulate 没改：调试输入口没在听（先 " + name() + "/debug on）。");
                return;
            }
            boolean ok = sair.v4.dev.DebugSimPort.setSimulate(on);
            out.ok(ok ? ("调试输入口 simulate=" + (on ? "on" : "off")
                    + (on ? "（动作照常）" : "（端口照常在听：动作一律回 -ERR debugSimulate=off）"))
                    : "simulate 没改成（输入口重起失败，看上面那行原因）");
            return;
        }
        // status
        out.print(debugFacts() + "\n", Out.Tone.NORMAL);
        out.print(J.pretty(debugJson()) + "\n", Out.Tone.NORMAL);
        out.dim("  用法：" + name() + "/debug [on [token]|off|status|simulate on|off]"
                + " —— 只有这条命令能起停 2660/2661 这两个口；它不依赖 ai/start，"
                + "停基板（ai/restart）也不会把它关掉。");
        out.dim("  落盘（logs\\console-<日期>.log / talk-<日期>.log）不归它管：随壳存在，"
                + "口开着关着都写，只有壳卸载才关句柄；已有日志只增不删。");
    }

    /**
     * 调试面的一行事实：两个口 + 落盘 + 基板状态（{@code substrate=}）+ 待生效键数。
     * <p>字段名沿用原有风格（{@code ready= / loading= / degraded=}），另加
     * {@code reason=} 与 {@code pending=}（主人 2026-09-16 定的名字）。</p>
     * <p>{@code 落盘} 那一段后面明写 {@code (随壳)}：它<b>不</b>跟着 {@code on/off} 走
     * （主人同日追加裁定），不能让人从这一行读成"off 之后就没日志了"。</p>
     */
    private String debugFacts() {
        int op = sair.v4.dev.DebugPort.activePort();
        int sp = sair.v4.dev.DebugSimPort.activePort();
        boolean up = sair.v4.dev.DebugPort.running();
        boolean sup = sair.v4.dev.DebugSimPort.running();
        boolean log = sair.v4.dev.DebugLog.enabled();
        boolean started = boot != null && boot.started() && !boot.loading();
        int pending = pendingCount();
        return "输出口 " + (up ? "on@" + op : "off@" + Conf.DEF_DEBUG_PORT)
                + " / 输入口 " + (sup ? "on@" + sp : "off@" + Conf.DEF_DEBUG_SIM_PORT)
                + " / 落盘 " + (log ? "on" : "off") + "(随壳，与 on/off 无关)"
                + (up ? " / token_fp=" + sair.v4.dev.DebugPort.activeTokenFp() : "")
                + " / simulate=" + (sup ? (sair.v4.dev.DebugSimPort.simulateOn() ? "on" : "off") : "-")
                + " / substrate=" + (started ? "running" : "not_started")
                + (started ? "" : " reason=" + (boot == null ? "no_substrate"
                        : boot.loading() ? "loading" : boot.degraded() ? "degraded" : "not_started"))
                + " pending=" + pending
                + " / 凭据文件 " + credentialFile();
    }

    /** 调试面的机器可读面（{@code ai/debug status} 用）。 */
    private JsonObject debugJson() {
        JsonObject o = new JsonObject();
        boolean started = boot != null && boot.started() && !boot.loading();
        o.addProperty("out_port", sair.v4.dev.DebugPort.running()
                ? sair.v4.dev.DebugPort.activePort() : Conf.DEF_DEBUG_PORT);
        o.addProperty("out_listening", sair.v4.dev.DebugPort.running());
        o.addProperty("sim_port", sair.v4.dev.DebugSimPort.running()
                ? sair.v4.dev.DebugSimPort.activePort() : Conf.DEF_DEBUG_SIM_PORT);
        o.addProperty("sim_listening", sair.v4.dev.DebugSimPort.running());
        o.addProperty("simulate", sair.v4.dev.DebugSimPort.simulateOn());
        o.addProperty("spool", sair.v4.dev.DebugLog.enabled());
        // 落盘的归属：shell（随壳存在，与 ai/debug on|off 无关）。老字段名一个都没改，
        // 只追加这一个 —— 免得客户端把 spool=0 读成"off 关掉了落盘"。
        o.addProperty("spool_owner", "shell");
        o.addProperty("spool_dir", sair.v4.dev.DebugLog.dir() == null
                ? "" : sair.v4.dev.DebugLog.dir().getAbsolutePath());
        o.addProperty("ready", started ? 1 : 0);
        if (!started) {
            o.addProperty("reason", boot == null ? "no_substrate"
                    : boot.loading() ? "loading" : boot.degraded() ? "degraded" : "not_started");
        }
        o.addProperty("pending", pendingCount());
        o.addProperty("substrate", started ? "running" : "not_started");
        o.addProperty("token_fp", sair.v4.dev.DebugPort.running()
                ? sair.v4.dev.DebugPort.activeTokenFp() : "");
        o.addProperty("credential_file", credentialFile());
        return o;
    }

    /** 运行现场凭据文件路径（客户端读的那份；不在这里时给空串）。 */
    private String credentialFile() {
        try {
            Boot b = boot;
            java.io.File r = b == null ? dataRootHint : b.root();
            if (r == null) {
                Conf c = shellConf();
                r = c == null ? null : c.root();
            }
            return r == null ? "(数据根未知)" : new File(r, "debug-port.json").getAbsolutePath();
        } catch (Throwable t) {
            return "(取不到)";
        }
    }

    /** 待生效的键数（没加载过配置时 -1）。 */
    private int pendingCount() {
        try {
            Conf c = boot == null ? null : boot.conf();
            if (c == null) return -1;
            return c.pendingKeys().size();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 待生效键的可读清单（{@code pending=<N>} + 最多 8 个键名 + "还有 M 个"）。
     * <p>主人 2026-09-16 的口径：{@code ai/status} 不只要个数，还要<b>点名</b>——
     * 写完 config.json 要能看见是哪几个键待生效，{@code ai/start}/{@code ai/restart} 之后
     * 要能看见这份清单变空。上限口径与 {@code ai/acl} 的账本块一致（8 行 + 余数）。</p>
     */
    private String pendingText(java.util.List<String> pend) {
        if (pend == null || pend.isEmpty()) return "pending=0";
        int cap = 8;
        StringBuilder sb = new StringBuilder("pending=").append(pend.size()).append("（待生效，要 ")
                .append(name()).append("/start；已经在跑则 ").append(name()).append("/restart）：");
        int n = Math.min(cap, pend.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pend.get(i));
        }
        if (pend.size() > n) sb.append(" 还有 ").append(pend.size() - n).append(" 个");
        return sb.toString();
    }

    private void skill(String a) {
        if (boot == null || boot.skills() == null) {
            out.warn("skill 不可用：技能库未装配（" + why() + "）");
            out.dim("  技能是外挂层；技能库不可用时基板其余能力照常。");
            return;
        }
        String[] v = Str.verb(a);
        String op = v[0].isEmpty() ? "list" : v[0].toLowerCase();
        if ("reload".equals(op)) {
            // 技能正在编译（启动期的后台编译 / 另一次 reload）：不排到扫描锁后面去，免得把控制台卡住
            if (boot.skills().scanning()) {
                out.warn("技能正在编译中，本次 reload 未执行 —— 编完再试（" + name() + "/skill list 看当前进度）");
                return;
            }
            boot.skills().scan();
            out.ok("已重扫：技能 " + boot.skills().size() + " 个，提供工具 " + boot.skills().toolCount() + " 个");
            return;
        }
        if ("validate".equals(op)) {
            List<String> p = boot.skills().validate();
            if (p.isEmpty()) out.ok("技能库无问题（共 " + boot.skills().size() + " 个）");
            else for (String s : p) out.warn(s);
            return;
        }
        if ("read".equals(op)) {
            Sk sk = boot.skills().get(v[1]);
            if (sk == null) {
                out.warn("没有技能 " + v[1]);
                return;
            }
            out.print(J.pretty(sk.toJson()) + "\n", Out.Tone.NORMAL);
            out.print(Str.nz(sk.doc) + "\n", Out.Tone.DIM);
            return;
        }
        out.dim("技能 " + boot.skills().size() + " 个（工具 " + boot.skills().toolCount()
                + "，钩子 " + boot.skills().hookCount() + "）");
        for (Sk sk : boot.skills().list()) {
            out.print("  " + pad(sk.name, 16) + " " + pad(sk.state.name(), 8)
                    + (sk.providesTool() ? "tool=" + pad(sk.tool, 16) : pad("", 21))
                    + (sk.hooks.isEmpty() ? "" : "hooks=" + Str.join(sk.hooks, ",")) + "\n", Out.Tone.NORMAL);
        }
    }

    private void promptCmd(String a) {
        if (boot == null || boot.prompts() == null) {
            out.warn("prompt 不可用：提示词未装配（" + why() + "）");
            return;
        }
        String[] v = Str.verb(a);
        String op = v[0].isEmpty() ? "show" : v[0].toLowerCase();
        if ("reload".equals(op)) {
            boot.prompts().reload();
            out.ok("初始提示词已重载（" + boot.prompts().identity().raw().length() + " 字符）");
            return;
        }
        if ("list".equals(op)) {
            out.dim("初始提示词：" + boot.conf().identityFile().getAbsolutePath());
            for (String f : boot.prompts().files()) out.print("  " + f + "\n", Out.Tone.NORMAL);
            return;
        }
        if ("sections".equals(op)) {
            out.print(Str.join(boot.prompts().identity().sectionNames(), "\n") + "\n", Out.Tone.NORMAL);
            return;
        }
        if ("read".equals(op)) {
            String txt = boot.prompts().read(v[1]);
            out.print(txt == null ? "(没有这份提示词)" : txt, Out.Tone.NORMAL);
            out.print("\n", Out.Tone.NORMAL);
            return;
        }
        if ("inject".equals(op)) {
            String[] p = v[1].split("\\s+", 3);
            if (p.length < 3) {
                out.warn("用法：prompt inject <slot> <key> <文本>");
                return;
            }
            boot.prompts().inject().put(p[0], p[1], p[2], "console");
            out.ok("已注入 " + p[0] + "/" + p[1]);
            return;
        }
        if ("clear".equals(op)) {
            boot.prompts().inject().clearAll();
            out.ok("已清空全部注入");
            return;
        }
        if ("stat".equals(op)) {
            out.print(boot.prompts().identity().stats() + "\n", Out.Tone.NORMAL);
            return;
        }
        out.print(boot.prompts().system("console") + "\n", Out.Tone.NORMAL);
        out.dim("（以上是运行时真正送进模型的基础系统提示词）");
    }

    private void napcat(String a) {
        if (boot == null || boot.link() == null || boot.conf() == null) {
            out.warn("napcat 不可用：NapCat 连接未装配（" + why() + "）");
            return;
        }
        String[] v = Str.verb(a);
        String op = v[0].isEmpty() ? "status" : v[0].toLowerCase();
        if ("relay".equals(op)) {
            relay(v[1]);
            return;
        }
        if ("on".equals(op) || "off".equals(op)) {
            // 新口径：这条命令**只改文件**（napcatEnabled），监听的启停跟着 ai/start / ai/restart 走。
            boolean on = "on".equals(op);
            wroteFile("napcatEnabled", String.valueOf(on), boot.conf().fileSet("napcatEnabled", on));
            out.dim("  NapCat 监听（端口 " + boot.conf().napcatPort() + "）现在还在按已生效的值跑："
                    + (boot.link().running() ? "正在监听" : "没在监听")
                    + " —— 要按新值重绑请 " + name() + "/restart。");
            return;
        }
        if ("list".equals(op)) {
            out.print(J.pretty(boot.api().catalog()) + "\n", Out.Tone.NORMAL);
            return;
        }
        if ("send".equals(op)) {
            String[] p = v[1].split("\\s+", 3);
            if (p.length < 3) {
                out.warn("用法：napcat send <group|private> <目标号> <内容>");
                return;
            }
            // 这条要等 NapCat 回包（有超时，最长 napcatTimeoutMs）——**不能在 EDT 上等**：
            // 控制台命令就跑在 EDT 上，等一次网络往返就是界面卡住那么久。
            // 所以丢给后台线程发，结果回来再打一行（控制台输出任何线程都能打）。
            final boolean group = "group".equalsIgnoreCase(p[0]);
            final long target = Long.parseLong(p[1]);
            final String text = p[2];
            out.dim("正在发送（" + (group ? "群 " : "私聊 ") + target + "）……");
            sair.v4.kit.Th.start("v4-napcat-send", new Runnable() {
                @Override
                public void run() {
                    try {
                        JsonObject r = group ? boot.api().sendGroupMsg(target, text)
                                : boot.api().sendPrivateMsg(target, text);
                        out.print(J.pretty(r) + "\n", Out.Tone.NORMAL);
                    } catch (Throwable t) {
                        out.err("发送失败：" + t);
                    }
                }
            });
            return;
        }
        String state = boot.link().connected() ? "已连接" : (boot.link().running() ? "监听中（未连接）" : "未启动");
        out.print("NapCat：" + state + "  端口=" + boot.conf().napcatPort()
                + "  连接数=" + boot.link().clients()
                + (boot.link().connected() ? "" : "  → 调用 NapCat 能力时会提示「" + sair.v4.qq.Api.NOT_CONNECTED + "」") + "\n",
                Out.Tone.NORMAL);
    }

    /**
     * 文件外链中转（{@code napcat relay [status|on|off]}）。
     * <p>它决定"本地文件怎么交给 NapCat"：开着就走 URL（跨机可取），关着就走 {@code file:///}（只有同机能取）。
     * 状态输出里的 token 一律脱敏，要看完整值用 {@code config relayToken}。</p>
     */
    private void relay(String a) {
        String[] v = Str.verb(a);
        String op = v[0].isEmpty() ? "status" : v[0].toLowerCase();
        sair.v4.qq.Relay r = boot.relay();
        if (r == null) {
            out.warn("没有装配文件外链中转");
            return;
        }
        if ("on".equals(op)) {
            if (Str.blank(boot.conf().relayPublicHost())) {
                out.warn("relayPublicHost 还没填：外链会用本机探测到的地址，跨机部署请先 config set relayPublicHost <局域网IP>");
            }
            // 新口径：只改文件（relayEnabled）；中转的启停跟着 ai/start / ai/restart 走
            wroteFile("relayEnabled", "true", boot.conf().fileSet("relayEnabled", true));
            out.dim("  文件外链中转现在还是按已生效的值跑：" + (r.running() ? "在跑" : "没在跑")
                    + " —— 要按新值起停请 " + name() + "/restart。");
            return;
        }
        if ("off".equals(op)) {
            wroteFile("relayEnabled", "false", boot.conf().fileSet("relayEnabled", false));
            out.dim("  文件外链中转现在还是按已生效的值跑：" + (r.running() ? "在跑" : "没在跑")
                    + " —— 要按新值起停请 " + name() + "/restart。");
            return;
        }
        out.print(J.pretty(r.status()) + "\n", Out.Tone.NORMAL);
        out.dim("  url_shape 里的 token 已脱敏；完整值：config relayToken");
        out.dim("  用法：napcat relay [status|on|off] —— on/off 只改写 config.json 的 relayEnabled，"
                + "真正起停要 ai/start（已在跑则 ai/restart）。");
    }

    private void storeCmd(String a) {
        if (boot == null || boot.store() == null) {
            out.warn("store 不可用：六库未装配（" + why() + "）");
            out.dim("  其余命令不受影响：" + name() + "/help、/status、/tools、/config 照常可用。");
            return;
        }
        String[] v = Str.verb(a);
        String op = v[0].isEmpty() ? "stat" : v[0].toLowerCase();
        if ("maintain".equals(op)) {
            // 与 tick 的定时维护同口径：三档保留期都取配置（记忆/对话/群聊各有各的天数）
            Conf c = boot.conf();
            out.print(J.pretty(boot.store().maintain(c.keepDays(), c.dialogKeepDays(), c.grouplogKeepDays(),
                    c.maxLowImportance())) + "\n", Out.Tone.NORMAL);
            return;
        }
        if ("optimize".equals(op)) {
            // 批量删行之后合并 FTS 段（删除标记才真正丢掉）；随后 store vacuum 才还盘
            out.ok(boot.store().optimizeFts(v[1]) ? ("已合并 " + v[1] + " 的全文索引段") : "合并失败（库名不对 / 该库没有索引）");
            return;
        }
        if ("vacuum".equals(op)) {
            boot.store().db().exec("VACUUM");
            out.ok("VACUUM 已执行（回收空闲页；期间独占数据库）");
            return;
        }
        if ("export".equals(op)) {
            File f = new File(boot.conf().filesDir(), v[1] + "-" + Str.stamp() + ".jsonl");
            out.ok(boot.store().exportJson(v[1], f) ? ("已导出 " + f.getAbsolutePath()) : "导出失败");
            return;
        }
        if ("import".equals(op)) {
            String[] p = v[1].split("\\s+", 2);
            int n = boot.store().importJson(p[0], new File(p[1]));
            out.ok(n >= 0 ? ("已导入 " + n + " 条") : "导入失败");
            return;
        }
        out.print(boot.storeStat() + "\n", Out.Tone.NORMAL);
    }

    // ==================== 帮助 ====================

    public String name() { return name; }

    /**
     * 命令表。<b>契约：永不抛异常、永远返回非空数组</b> —— 这是 SFW 能拿到的最底线的回应
     * （哪怕装配全炸、{@code Out} 不可用，也必须有一张命令表回给框架）。
     */
    public String[] help() {
        try {
            String n = name();
            return new String[] {
                    "AiAgent V4 —— 基板只做九件事：存储 / 热插拔 / 提示词 / Agent / 上下文 / 权限 / 模型 / NapCat / 控制台",
                    "本地控制台交互 ≡ QQ 中的主人交互；主人 = MASTER，工具一律全放行。",
                    "对话：",
                    "\t" + n + "/chat <内容>      和 AI 说话（与 QQ 主人消息同一条链路）",
                    "\t" + n + "/stop             中断当前输出",
                    "\t" + n + "/reset            重置控制台会话上下文",
                    "生命周期（装配跑在自己的线程上，进度直接打在这里）：",
                    "\t" + n + "/start            ★ 唯一的「加载并运行」：读 config.json + 跑 19 步装配",
                    "\t" + n + "/restart          停旧基板 → 重读 config.json → 重新装配 19 步（不并发，重复的会被拒）",
                    "\t" + n + "/exit             框架转发到组件 exit()：停基板（不改配置、不卸载插件；之后可再 start）",
                    "\t" + n + "/close | /open     框架语义：停止 / 恢复「接受命令」（close 后只有 exit/close/open/uninstall 进得来）",
                    "\t" + n + "/uninstall        框架级卸载：卸载前自动 exit()+close()（卸载后要重装才可用）",
                    "★ 生效口径：除 " + n + "/start 与 " + n + "/restart 外，其余命令都**只改配置文件**——"
                            + "改完不生效，要 " + n + "/start（没在跑）或 " + n + "/restart（已经在跑）。",
                    "\t" + n + "/status 会列出「待生效」的键（文件里是新值、跑着的基板还是旧值）。",
                    "基板状态：",
                    "\t" + n + "/status           运行状态（未启动会说 ready=0 reason=not_started；装配失败时给出失败步骤）",
                    "\t" + n + "/tools [all]      当前可见工具（all=含被权限挡住的名字）",
                    "\t" + n + "/config [键 [值]]  看/改配置（读的是 config.json 这个文件；config set 只写文件，不生效）",
                    "\t" + n + "/config reload    已退休：热重载没了，改动只有 " + n + "/start 或 " + n + "/restart 才生效",
                    "\t" + n + "/tick             手动跑一次心跳（on_timer + 定时唤醒）",
                    "\t" + n + "/alarm            定时唤醒清单",
                    "能力：",
                    "\t" + n + "/skill [list|reload|validate|read 名]   技能库（外挂层）",
                    "\t" + n + "/prompt [show|list|sections|read 名|stat|reload]  提示词",
                    "\t" + n + "/prompt inject <slot> <key> <文本>      注入到 system/context/tool/arg",
                    "\t" + n + "/perm [list|set QQ 值|reset|QQ]        好感度（权限已改由 ACL 账本管）",
                    "\t" + n + "/acl                              权限账本（ACL）：条目 + 按类默认分配（只读）",
                    "\t" + n + "/napcat [status|on|off|list|send group 群号 内容]",
                    "\t" + n + "/napcat relay [status|on|off]       文件外链中转（本地文件怎么交给 NapCat）",
                    "\t" + n + "/store [stat|maintain|export 库|import 库 路径]",
                    "\t" + n + "/debug [on [token]|off|status|simulate on|off]   本机调试面的开关（只有它能起停）",
                    "永不失联：装配部分失败时 help/status/tools/config 仍然可用，",
                    "\t" + n + "/status 的 boot.failed_step 会指出是哪一步失败、因为什么。",
                    "说明：业务能力（发图/禁言/搜索/天气/审核/主动发言……）不在控制台命令里，",
                    "\t它们由 data/skills 提供为 AI 工具；技能目录整体删掉，基板照常运转。",
                    "说明：权限 = 资源 ACL（数据根的 perms.json 账本 + 按资源类型的默认分配 + 例外突破默认）；",
                    "\t授权/撤销/查询/验算走她那边的 perm 工具。控制台 ai/acl 只看账本，ai/perm 只管好感度。",
            };
        } catch (Throwable t) {
            return fallbackHelp();
        }
    }

    /** 最底线的命令表（构造/装配都不可用时的兜底；静态常量，不可能失败）。 */
    public static String[] fallbackHelp() {
        return new String[] {
                "AiAgent V4（降级命令表）",
                "控制台可用：help / status / tools / config；其余命令需要基板装配成功。",
                "如果 status 也不可用，请查看组件日志里 [v4] 开头的错误行。",
        };
    }

    public void printHelp() {
        String[] lines;
        try {
            lines = help();
            if (lines == null || lines.length == 0) lines = fallbackHelp();
        } catch (Throwable t) {
            lines = fallbackHelp();
        }
        try {
            out.print(sair.Pathes.printSplit + "\n", Out.Tone.TITLE);
            for (String line : lines) out.print(line + "\n", Out.Tone.NORMAL);
            out.print(sair.Pathes.printSplit + "\n", Out.Tone.TITLE);
        } catch (Throwable t) {
            // 连输出都不可用：逐行写 stdout 兜底（至少让框架/日志里看得见）
            for (String line : lines) System.out.println(line);
        }
    }

    private static String pad(String s, int n) {
        StringBuilder sb = new StringBuilder(Str.nz(s));
        while (length(sb.toString()) < n) sb.append(' ');
        return sb.toString();
    }

    private static int length(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            n += (c > 0x2E80) ? 2 : 1;
        }
        return n;
    }
}
