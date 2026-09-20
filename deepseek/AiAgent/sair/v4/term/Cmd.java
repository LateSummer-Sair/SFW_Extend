package sair.v4.term;

import com.google.gson.JsonObject;

import java.io.File;
import java.util.List;
import java.util.concurrent.Future;

import sair.v4.Boot;
import sair.v4.Builtins;
import sair.v4.Conf;
import sair.v4.Tick;
import sair.v4.auth.Acl;
import sair.v4.auth.Caller;
import sair.v4.auth.Favor;
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
            if ("favor".equals(f)) {
                favor(a, caller());
                return null;
            }
            if ("acl".equals(f)) {
                // 旧名退休：权限账本 → 按归属分散的权限文件。名字留着只为给一句可读的指路（命令表里已不列它）
                out.warn("acl 已退休：权限改成了按归属分散的权限文件（数据根 " + Acl.CORE_FILE_NAME
                        + " + 每个技能的 " + Acl.SKILL_FILE_NAME + "）—— 看表与改表都在 "
                        + name() + "/perm，下面就是它。");
                if (boot != null && boot.auth() != null) perm("", caller());
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
                out.dim("  工具能不能用完全由技能管控表决定（主人与她本人恒全权），不再有按通道裁剪的工具表。");
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

    // 旧面已清：`perm set/reset|list|<QQ>`（好感度控制台口）与更早的 `perm levels/setlevel`（旧档位注册表）
    // 都随这一轮权限整改下线 —— 好感度不是权限、也不参与任何判定（notes\perm-rework-spec.md）。
    // 新的 `perm` 只管一件事：身份 × op → Ban / Run / 空，权限文件按归属分散
    // （data\perms-core.jsonc + 每个技能的 data\skills\<技能名>\perms.jsonc）。

    /**
     * {@code perm}：<b>权限面</b> —— 控制台唯一的权限视图（读的是<b>合并后</b>的整张表）。
     *
     * <p>判定只剩一条：<b>身份 × op → Ban / Run / 空</b>。{@code Run} 白名单 = 可用，
     * {@code Ban} 黑名单 = 不可用，<b>空</b>（{@code Run["op"]} 没写身份，或账本里根本没有这一行）
     * = 未授权 = 不可用；优先级 <b>Ban &gt; Run &gt; 空</b>。主人与她本人（SYSTEM）恒全权，
     * 不受这张表影响。</p>
     *
     * <p>权限文件按<b>归属</b>分散存放：基板内置工具（{@code napcat}/{@code console}/{@code exec}/
     * {@code perm}/…）写数据根下的 {@code perms-core.jsonc}；每个技能可以在自己的目录里写一份
     * {@code perms.jsonc}（只写它自己提供的工具，越界条目忽略）。技能目录里没有那份文件 =
     * 该技能全部 op 一律不授权；写回时按归属落到对应的那个文件。</p>
     *
     * <p>子命令：</p>
     * <ul>
     *   <li>{@code perm}            逐行打印<b>合并后</b>的整张表 + {@link Acl#stat()} + 来源汇总；</li>
     *   <li>{@code perm list [op]}  按 op 看谁能用（合并视图）；</li>
     *   <li>{@code perm gen}        只在<b>已有</b>权限文件里重排/补注释（<b>不新建文件</b>）；</li>
     *   <li>{@code perm run|ban <op> <身份>…}  白名单（授权）/ 黑名单（封禁），写回该 op 归属的文件；</li>
     *   <li>{@code perm db run|read|ban <数据键> <身份>…}   <b>DB 块</b>：这类数据谁能改 / 谁能读 / 谁不能；</li>
     *   <li>{@code perm file run|read|ban <路径> <身份>…}   <b>File 块</b>：这个路径谁能碰（目录末尾带斜杠 = 整棵子树）；</li>
     *   <li>{@code perm revoke <键> <身份>}    撤掉该身份在这个键上的全部条目（op / DB / File 自动认）；</li>
     *   <li>{@code perm reload}     重新合并装载（重新取工具归属之后重读全部权限文件）。</li>
     * </ul>
     *
     * <p><b>写命令（{@code gen}/{@code run}/{@code ban}/{@code db}/{@code file}/{@code revoke}）只有主人能跑</b>；
     * 身份先过 {@link Acl#principalReason(String)}，整行再让 {@link Acl#reason(String)} 用自己的原话回绝
     * —— 写法认不出来就一个字都不写（先全验、再动账本，绝不猜）。{@code db}/{@code file} 还要先过
     * <b>键空间</b>（{@link Acl#dbClasses()} / {@link Acl#fileKeys()}）：认不出的键在控制台就拒掉，
     * 绝不交给 {@link Acl#grantKey} 去猜（那会把认不出的数据键当成 op 静默写进 Skill 块）。</p>
     */
    private void perm(String a, Caller me) {
        if (boot == null || boot.auth() == null) {
            out.warn("perm 不可用：权限面未装配（" + why() + "）");
            return;
        }
        String[] v = Str.verb(a);
        String sub = v[0].toLowerCase();
        if (sub.isEmpty()) {
            permTable();
            return;
        }
        if ("list".equals(sub)) {
            permList(v[1]);
            return;
        }
        if ("gen".equals(sub)) {
            permGen(me);
            return;
        }
        if ("run".equals(sub)) {
            permWrite(me, false, v[1]);
            return;
        }
        if ("ban".equals(sub)) {
            permWrite(me, true, v[1]);
            return;
        }
        if ("db".equals(sub)) {
            permKeyWrite(me, true, v[1]);
            return;
        }
        if ("file".equals(sub)) {
            permKeyWrite(me, false, v[1]);
            return;
        }
        if ("revoke".equals(sub)) {
            permRevoke(me, v[1]);
            return;
        }
        if ("reload".equals(sub)) {
            permReload();
            return;
        }
        out.warn("未知子命令：" + v[0]);
        out.dim("  用法：" + name() + "/perm [list [op]|gen|run <op> <身份> [身份…]|"
                + "ban <op> <身份> [身份…]|db run|read|ban <数据键> <身份> [身份…]|"
                + "file run|read|ban <路径> <身份> [身份…]|revoke <键> <身份>|reload]");
    }

    /** 取账本；取不到给一句可读的失败，绝不抛异常。 */
    private Acl aclOrWarn() {
        if (boot == null) {
            out.warn("技能管控表不可用：基板未创建");
            return null;
        }
        if (boot.auth() == null) {
            out.warn("技能管控表不可用：权限面未装配（" + why() + "）");
            return null;
        }
        Acl a = boot.auth().acl();
        if (a == null) out.warn("技能管控表不可用：账本没能装载（" + why() + "）");
        return a;
    }

    /** 权限文件的落点路径（回执里用）。 */
    private static String fileText(Acl a) {
        if (a == null) return "(内存账本，没落盘)";
        List<File> ws = a.writtenFiles();
        if (!ws.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ws.size(); i++) sb.append(i == 0 ? "" : "、").append(permPath(a, ws.get(i)));
            return sb.toString();
        }
        return permPath(a, a.file());
    }

    /**
     * 权限文件的人读路径：<b>数据根下用相对路径</b>（{@code data\skills\天气查询\perms.jsonc}），
     * 根外或取不到根就给全路径。
     */
    private static String permPath(Acl a, File f) {
        if (f == null) return "(无)";
        File root = a == null ? null : a.dataRoot();
        try {
            if (root == null) return f.getAbsolutePath();
            String r = root.getAbsolutePath();
            String p = f.getAbsolutePath();
            if (p.startsWith(r + File.separator)) {
                // 带上数据根自己的名字（"data\"），主人一眼知道是哪个目录
                return root.getName() + File.separator + p.substring(r.length() + 1);
            }
        } catch (Throwable ignored) {
        }
        return f.getAbsolutePath();
    }

    /** 这个 op 是不是当前注册表里的（只用于提示，不拦写入）。 */
    private boolean knownOp(String op) {
        try {
            return boot != null && boot.auth() != null && boot.auth().ops().known(op);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 写账本的命令只有主人能跑（控制台调用者就是主人；别人一律拒）。 */
    private boolean masterOnly(Caller me, String what) {
        if (me != null && me.master()) return true;
        out.warn(what + " 只有主人能跑（当前调用者：" + (me == null ? "(无)" : me.label())
                + "）—— 本次什么都没改。");
        return false;
    }

    /**
     * {@code perm}（无参数）：<b>合并后</b>的整张表逐行 + 摘要 + 来源汇总 + 一段怎么用。
     * <p>v6 追加（只是追加，上面那几行一个字都没动）：DB / File 两块（{@link Acl#ruleList()}，
     * 域 → 侧 → 键、同键已取并集）、"未声明 Read、按默认能读"的点名披露（{@link Acl#readDefaultList()}）、
     * 以及那一行装载汇总（{@link Acl#summary()}）。</p>
     */
    private void permTable() {
        Acl a = aclOrWarn();
        if (a == null) return;
        out.print("技能管控表（合并视图：core + 每个技能的 perms.jsonc；身份 × op → Ban / Run / 空；"
                + "主人与她本人恒全权）\n", Out.Tone.NORMAL);
        List<Acl.Entry> es = a.entries();
        if (es.isEmpty()) {
            out.dim("  （一条都没有 = 一切未授权；还没进表的动作在下面那行）");
        }
        for (Acl.Entry e : es) out.print("  " + permLine(e) + "\n", e.ban() ? Out.Tone.WARN : Out.Tone.NORMAL);
        for (Acl.Reject r : a.rejects()) {
            out.warn("  认不出的条目（原样留着，改完 " + name() + "/perm reload）：" + r.text());
        }
        out.print("  " + a.stat() + "\n", Out.Tone.DIM);
        permRules(a);
        permSources(a);
        permUnauthorized(a);
        permHowTo();
    }

    /**
     * <b>DB / File 两块</b>（v6 新增面）+ 规格 §2 要求的"未声明 Read 的类别"披露 + 装载汇总那一行。
     *
     * <p>三样都走 {@code Acl} 的现成口：{@link Acl#ruleList()}（域 → 侧 → 键 → 身份，同键先并集）、
     * {@link Acl#readDefaultList()}（没有显式 Read 声明、也没在任何 Ban 里出现过的数据类别）、
     * {@link Acl#summary()}（{@code 权限：Skill N 条 / DB M 条 / File K 条；…}）。</p>
     *
     * <p>Skill 域不在这里打（它走上面那一段，格式冻结）—— 这一个是纯追加，不改任何既有行的顺序。</p>
     */
    private void permRules(Acl a) {
        List<String> rs = a.ruleList();
        List<String> db = new java.util.ArrayList<String>();
        List<String> fl = new java.util.ArrayList<String>();
        for (String line : rs) {
            if (line.startsWith("[DB.")) db.add(line);
            else if (line.startsWith("[File.")) fl.add(line);
        }
        out.print("  DB（数据类别：Run 谁能改 / Read 谁能读 / Ban 谁都不能；没列到 = 能读不能改；"
                + "改命中后还要看那一行是不是他自己的）\n", Out.Tone.NORMAL);
        if (db.isEmpty()) out.dim("    （DB 块还没有任何规则）");
        for (String line : db) {
            out.print("    " + line + "\n", line.startsWith("[DB.Ban]") ? Out.Tone.WARN : Out.Tone.NORMAL);
        }
        out.print("  File（路径：目录末尾带斜杠 = 整棵子树，最长路径优先；没列到 = 不能碰）\n", Out.Tone.NORMAL);
        if (fl.isEmpty()) out.dim("    （File 块还没有任何规则）");
        for (String line : fl) {
            out.print("    " + line + "\n", line.startsWith("[File.Ban]") ? Out.Tone.WARN : Out.Tone.NORMAL);
        }
        // 规格 §2 要求的披露：DB 的"未定义 = 能读"是全表唯一默认放开的地方，必须点名列清单
        List<String> dflt = a.readDefaultList();
        if (dflt.isEmpty()) {
            out.dim("  未声明 Read、按默认能读的类别：无（每一类都有显式 Read 或 Ban 声明）");
        } else {
            out.print("  未声明 Read、按默认能读的类别（规格 §2 唯一的默认放开）："
                    + Str.join(dflt, "、") + "\n", Out.Tone.WARN);
        }
        out.print("  " + a.summary() + "\n", Out.Tone.DIM);
        out.dim("  改 DB / File 块（只有主人能跑）：" + name() + "/perm db run|read|ban <数据键> <身份> [身份…]；"
                + name() + "/perm file run|read|ban <路径> <身份> [身份…]"
                + "（认不出的键会被拒，不会静默写进表）");
    }

    /**
     * <b>来源汇总</b>：这一遍是从哪几份权限文件合并出来的、各几条。
     * <p>一行总数（{@code 权限文件：core 79 条；技能 22 份共 92 条}）+ 每份文件一条明细。</p>
     */
    private void permSources(Acl a) {
        out.dim("  " + a.sourceStat());
        for (File f : a.sources()) {
            out.dim("    " + permPath(a, f) + "  " + a.entriesOf(f) + " 条");
        }
    }

    /**
     * 注册表里有、表里没提过的动作（= 未授权）。<b>不写进文件</b>（没列到就等于未授权），
     * 只在这里给人看 —— 要看全都有哪些能开，就看这一行。
     */
    private void permUnauthorized(Acl a) {
        try {
            if (boot == null || boot.auth() == null || boot.auth().ops() == null) return;
            List<String> miss = new java.util.ArrayList<String>();
            for (String op : boot.auth().allOps()) {
                boolean hit = false;
                for (Acl.Entry e : a.entries()) {
                    if (e.op().equals(op)) { hit = true; break; }
                    String tool = sair.v4.auth.Ops.toolOf(op);
                    if (e.op().equals(tool)) { hit = true; break; }
                }
                if (!hit) miss.add(op);
            }
            if (miss.isEmpty()) return;
            int show = Math.min(12, miss.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < show; i++) {
                if (i > 0) sb.append(", ");
                sb.append(miss.get(i));
            }
            out.dim("  另有 " + miss.size() + " 个动作没在表里（= 未授权 = 不可用）：" + sb
                    + (miss.size() > show ? " …" : ""));
            out.dim("  要给谁开哪一项：" + name() + "/perm run <op> <身份>"
                    + "（写进该 op 归属的那个权限文件；看全部动作：" + name() + "/perm list）");
        } catch (Throwable ignored) {
        }
    }

    /** 技能管控表的「怎么用」短说明。 */
    private void permHowTo() {
        String n = name();
        out.dim("  怎么用（写命令只有主人能跑）：");
        out.dim("    " + n + "/perm gen                       已有权限文件重排：按工具分组、补中文释义（不新建文件）");
        out.dim("    " + n + "/perm run <op> <身份> [身份…]    白名单：授权（写进该 op 归属的那个文件）");
        out.dim("    " + n + "/perm ban <op> <身份> [身份…]    黑名单：封禁（优先级 Ban > Run > 空）");
        out.dim("    " + n + "/perm db run|read|ban <数据键> <身份> [身份…]   DB 块：这类数据谁能改 / 谁能读 / 谁不能");
        out.dim("    " + n + "/perm file run|read|ban <路径> <身份> [身份…]  File 块：这个路径谁能碰"
                + "（目录末尾带斜杠 = 整棵子树；路径含空格时整段用双引号包起来）");
        out.dim("    " + n + "/perm revoke <op> <身份>          撤掉该身份在这个 op 上的全部条目");
        out.dim("    " + n + "/perm revoke <键> <身份>          键会自动认属于哪一块（op / DB / File），一次摘干净");
        out.dim("    " + n + "/perm list [op]                  按 op 看谁能用（合并视图）");
        out.dim("    " + n + "/perm reload                     重新合并装载（外面手改了权限文件之后）");
        out.dim("  权限文件按归属分散：基板工具写数据根 " + Acl.CORE_FILE_NAME + "；每个技能写自己的 "
                + "skills\\<技能名>\\" + Acl.SKILL_FILE_NAME + "（只写它自己提供的工具，越界条目忽略；"
                + "技能没有那份文件 = 它的全部 op 不授权）。");
        out.dim("  身份写法：user:<QQ> / group:<群号> / ALLUSER（裸 QQ 号 = user:<QQ>）；"
                + "写工具名（memory）覆盖它全部动作；主人与她本人恒全权，不用写进表。");
    }

    /** {@code perm list [op]}：表里每个 op 逐行 + 每个 op 谁能用；给了 op 就只看它（合并视图）。 */
    private void permList(String rawOp) {
        Acl a = aclOrWarn();
        if (a == null) return;
        String op = Str.trim(rawOp);
        if (op.isEmpty()) {
            java.util.Map<String, List<Acl.Entry>> by = a.byOp();
            if (by.isEmpty()) {
                out.dim("技能管控表里还没有任何 op（没有权限文件 = 一切未授权；要开口敲 "
                        + name() + "/perm run <op> <身份>）");
                return;
            }
            out.print("技能管控表按 op 逐行：\n", Out.Tone.NORMAL);
            for (java.util.Map.Entry<String, List<Acl.Entry>> e : by.entrySet()) {
                out.print("  " + e.getKey() + "\n", Out.Tone.NORMAL);
                for (Acl.Entry en : e.getValue()) {
                    out.print("    " + permLine(en) + "\n", en.ban() ? Out.Tone.WARN : Out.Tone.NORMAL);
                }
            }
            out.print("  " + a.stat() + "\n", Out.Tone.DIM);
            out.dim("  " + a.sourceStat());
            out.dim("  说明：Ban > Run > 空；空 = 未授权 = 不可用；条目写工具名（memory）时覆盖它全部动作。");
            return;
        }
        out.print("op " + op + "\n", Out.Tone.NORMAL);
        String tool = sair.v4.auth.Ops.toolOf(op);
        int n = 0;
        for (Acl.Entry en : a.entries()) {
            String et = sair.v4.auth.Ops.toolOf(en.op());
            if (!en.op().equals(op) && !en.op().equals(tool) && !et.equals(op)) continue;
            out.print("    " + permLine(en) + "\n", en.ban() ? Out.Tone.WARN : Out.Tone.NORMAL);
            n++;
        }
        if (n == 0) out.dim("    （账本里没有它的条目 = 未授权 = 谁都不能用）");
        File tf = a.targetOf(op);
        if (tf != null) out.dim("  这个 op 归属的权限文件：" + permPath(a, tf)
                + (tf.isFile() ? "" : "（还不存在：敲 " + name() + "/perm run 会创建它）"));
        if (!tool.equals(op)) {
            out.dim("  条目写工具名（" + tool + "）时覆盖它全部动作 —— 上面 op 是 " + tool + " 的行都管它。");
        } else {
            out.dim("  （按工具名过滤：它的动作级条目都列在上面。）");
        }
        if (!knownOp(op)) {
            out.dim("  注意：这个 op 不在当前注册表里（装上新技能后才会出现）；账本里的条目先留着无妨。");
        }
    }

    /**
     * {@code perm gen}：<b>只在已有的权限文件里重排 / 补注释</b>（不新建文件）——
     * 按工具分组、把注册表里的中文释义补进注释，每份文件只重写它自己管的那些 op。
     */
    private void permGen(Caller me) {
        if (!masterOnly(me, "perm gen")) return;
        Acl a = aclOrWarn();
        if (a == null) return;
        a.reload();                        // 先重读：主人可能手改过文件，别拿旧内容盖掉
        // gen 的口径：只在**已有**权限文件里重排/补注释，**不新建文件**（没有文件 = 一切未授权）
        boolean saved = a.saveAll(boot.auth(), false);
        List<File> wrote = a.writtenFiles();
        if (!saved) {
            out.warn("重排没落盘：权限文件写盘失败（" + fileText(a) + "）");
            return;
        }
        if (wrote.isEmpty()) {
            // wrote 为空有两种情形：一份已有文件都没有（真话就是"没有文件"），
            // 或者已有文件的内容与要写的文本逐字节相同（saveAll 的 sameText 跳过了）—— 这时说
            // "都不存在"是假话。sources 里可能有盘上不存在的文件，所以用 isFile() 数。
            int onDisk = 0;
            for (File f : a.sources()) if (f != null && f.isFile()) onDisk++;
            if (onDisk == 0) {
                out.dim("没有任何权限文件可重排（" + Acl.CORE_FILE_NAME + " 与技能目录里的 "
                        + Acl.SKILL_FILE_NAME + " 都不存在）—— gen 不新建文件。");
            } else {
                out.dim("已有 " + onDisk + " 份权限文件，本次没有需要重排的内容 —— gen 不新建文件。");
            }
        } else {
            out.ok("已重排 " + wrote.size() + " 份权限文件：" + fileText(a));
        }
        List<String> miss = a.unwrittenOps();
        if (!miss.isEmpty()) {
            int show = Math.min(12, miss.size());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < show; i++) sb.append(i == 0 ? "" : "、").append(miss.get(i));
            out.dim("  这些 op 还没有权限文件（= 不授权）：" + sb + (miss.size() > show ? " …" : ""));
        }
        out.dim("  要给谁开哪一项：" + name() + "/perm run <op> <身份>（会写进该 op 归属的那个文件，"
                + "不存在就创建；没列到的 op = 未授权 = 不可用）。");
    }

    /** {@code perm run|ban <op> <身份> [身份…]}：写白名单（授权）或黑名单（封禁）并落盘。 */
    private void permWrite(Caller me, boolean ban, String rest) {
        String word = ban ? "ban" : "run";
        if (!masterOnly(me, "perm " + word)) return;
        String[] p = Str.trim(rest).split("\\s+");
        if (p.length < 2 || Str.blank(p[0])) {
            out.warn("用法：" + name() + "/perm " + word + " <op> <身份> [身份…]"
                    + "（身份：user:<QQ> / group:<群号> / ALLUSER，裸 QQ 号也行）");
            return;
        }
        String op = p[0].trim();
        // 第一道：身份逐个过 principalReason（主人/她自己会得到"恒全权"那句，原话回）
        for (int i = 1; i < p.length; i++) {
            String raw = p[i].trim();
            if (raw.isEmpty()) continue;
            String why = Acl.principalReason(raw);
            if (!why.isEmpty()) {
                out.warn("身份写法不能用：" + raw + " —— " + why);
                out.dim("  身份只认 user:<QQ> / group:<群号> / ALLUSER（裸 QQ 号 = user:<QQ>）；"
                        + "主人（MASTER）与她本人（SYSTEM）恒全权，不用写进表。");
                return;
            }
        }
        StringBuilder sb = new StringBuilder(ban ? "Ban[\"" : "Run[\"").append(op).append("\"");
        for (int i = 1; i < p.length; i++) {
            if (Str.blank(p[i])) continue;
            sb.append(",\"").append(p[i].trim()).append("\"");
        }
        sb.append("]");
        String raw = sb.toString();
        // 第二道：整行让 Acl.reason 判（它的原话就是回绝文案；认不出来一个字都不写）
        String why = Acl.reason(raw);
        if (!why.isEmpty()) {
            out.warn("写法不能用：" + why);
            return;
        }
        Acl.Entry entry = Acl.parse(raw);
        if (entry == null) {
            out.warn("写法不能用：" + Acl.reason(raw));
            return;
        }
        Acl a = aclOrWarn();
        if (a == null) return;
        for (String principal : entry.principals()) a.grant(op, principal, ban, boot.auth().ops().noteOf(op));
        if (!a.saveAll(boot.auth())) {
            a.reload();                    // 没落盘就别在内存里留下"看着改了"的假象
            out.warn("写盘失败 —— 本次" + (ban ? "封禁" : "授权") + "没落盘（" + permPath(a, a.targetOf(op)) + "）");
            return;
        }
        String where = wroteText(a, op);
        for (String principal : entry.principals()) {
            out.ok("已" + (ban ? "封禁" : "授权") + "：" + op + " ← " + principal + "（已写入 " + where + "）");
        }
        if (!knownOp(op)) {
            out.dim("  注意：op " + op + " 不在当前注册表里（装上新技能后才会出现）—— 条目先留着无妨。");
        }
    }

    /**
     * {@code perm db|file run|read|ban <键> <身份> [身份…]}：给 <b>DB / File 块</b>加一条决定并落盘。
     *
     * <p>两道校验，<b>先全验、再动账本</b>：</p>
     * <ol>
     *   <li><b>键空间</b>（本波次新增的那一道）：{@code db} 的键必须在 {@link Acl#dbClasses()}
     *       （数据类别白名单）里；{@code file} 的键要么已经在 {@link Acl#fileKeys()} 里，要么
     *       长得像一个路径（带 {@code /} 或 {@code \}，不带通配符）。<b>认不出的一律拒</b> ——
     *       这一道不能省：{@link Acl#grantKey} 对"既不是已知数据类别、又不像路径"的键会
     *       <b>当成 op</b> 静默写进 Skill 块（{@code db run kv.attach ALLUSER} 会变成一条
     *       永远不会被用到的 Skill 条目），那是"静默写进去"，规格 §3 明确不许。</li>
     *   <li><b>身份</b>：逐个过 {@link Acl#principalReason(String)}（主人/她自己会得到"恒全权"那句原话）。</li>
     * </ol>
     *
     * <p>拒绝一律直接搬 {@link Acl#DENY_PREFIX} 开头的原文（键认不出用规格 §5 那一句），
     * <b>不拼新前缀、不列白名单内容</b>。落盘走 {@link Acl#saveAll(Auth)}：DB / File 规则写回它
     * <b>读自的那份文件</b>（读自旧统一表时退回 core），回执写明落到哪一份。</p>
     */
    private void permKeyWrite(Caller me, boolean db, String rest) {
        String word = db ? "db" : "file";
        String domain = db ? "DB" : "File";
        if (!masterOnly(me, "perm " + word)) return;
        Args ar = splitArgs(rest);                     // 先剥一层成对的双引号，再按空白切词
        if (ar.badQuote) {
            out.warn("引号没配对 —— 这一条不执行（键里含空格时整段用一对双引号包起来）");
            out.dim("  " + keyUsage(word, db));
            return;
        }
        if (ar.toks.size() < 3) {
            out.warn(keyUsage(word, db));
            return;
        }
        String side = ar.toks.get(0).toLowerCase();
        if (!"run".equals(side) && !"read".equals(side) && !"ban".equals(side)) {
            out.warn("侧只认 run / read / ban：" + ar.toks.get(0));
            out.dim("  run = " + (db ? "谁能改" : "谁能碰") + "；read = 谁能读；ban = 谁不能（压过 run 与 read）。");
            return;
        }
        String key = ar.toks.get(1);
        Acl a = aclOrWarn();
        if (a == null) return;
        // 第一道：键要在白名单里（认不出的一律拒 + 说清为什么；绝不静默写进别的块）
        String bad = keyProblem(a, db, key);
        if (!bad.isEmpty()) {
            out.warn(Acl.DENY_PREFIX + "不认识的权限键「" + key + "」（装载时已点名）");
            out.dim("  " + bad + " —— 认不出的键一律不写进表（本次什么都没改）。");
            out.dim("  " + keyUsage(word, db));
            return;
        }
        // 第二道：身份写法（先全验、再动账本）
        List<String> ids = new java.util.ArrayList<String>();
        for (int i = 2; i < ar.toks.size(); i++) {
            String raw = ar.toks.get(i);
            // 看着像路径的一段 = 键里含空格却没加引号：明确拒掉并给写法，绝不静默写一条错规则
            if (!db && (raw.indexOf('/') >= 0 || raw.indexOf('\\') >= 0)) {
                out.warn("身份写法不能用：" + raw + " —— 这一段看着像路径（键里含空格时整段要用双引号包起来）");
                out.dim("  写法：" + name() + "/perm file " + side + " \"<路径>\" <身份> [身份…]");
                return;
            }
            if (raw.isEmpty()) continue;
            String why = Acl.principalReason(raw);
            if (!why.isEmpty()) {
                out.warn("身份写法不能用：" + raw + " —— " + why);
                out.dim("  身份只认 user:<QQ> / group:<群号> / ALLUSER（裸 QQ 号 = user:<QQ>）；"
                        + "主人（MASTER）与她本人（SYSTEM）恒全权，不用写进表。");
                if (!db) {
                    out.dim("  提示：路径里含空格时整段用双引号包起来（\"" + "D:/a b/" + "\"）；"
                            + "不加引号会被切成两段 —— 键会写错。");
                }
                return;
            }
            String id = Acl.principalKey(raw);           // 统一成规范身份（裸 QQ → user:<QQ>），同一个人只写一次
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) {
            out.warn(keyUsage(word, db));
            return;
        }
        List<String> before = db ? new java.util.ArrayList<String>() : a.fileKeys();
        int n = 0;
        for (String id : ids) if (a.grantKey(key, id, side)) n++;
        if (n != ids.size()) {
            a.reload();                                  // 别在内存里留下"看着改了"的假象（一个字都还没落盘）
            out.warn("这条没被收下（本次什么都没写）：" + "[" + domain + "." + sideName(side) + "] " + key
                    + " ← " + ids);
            return;
        }
        if (!a.saveAll(boot.auth())) {
            a.reload();
            out.warn("写盘失败 —— 本次没落盘（" + permPath(a, a.targetOfKey(key)) + "）");
            return;
        }
        String where = permPath(a, a.targetOfKey(key));
        String shown = shownKey(a, db, key, before);
        String act = "ban".equals(side) ? "已封禁" : ("read".equals(side) ? "已开放读" : "已授权");
        for (String id : ids) {
            out.ok(act + "：[" + domain + "." + sideName(side) + "] " + shown + " ← " + id
                    + "（已写入 " + where + "）");
        }
    }

    /** 侧名（{@code run} / {@code read} / {@code ban} → {@code Run} / {@code Read} / {@code Ban}）。 */
    private static String sideName(String side) {
        return "run".equals(side) ? "Run" : ("read".equals(side) ? "Read" : "Ban");
    }

    /** {@code perm db|file} 的用法一行（不列白名单内容）。 */
    private String keyUsage(String word, boolean db) {
        return "用法：" + name() + "/perm " + word + " run|read|ban <" + (db ? "数据键" : "路径")
                + "> <身份> [身份…]（" + (db
                ? "数据键要是已知的数据类别（先敲 " + name() + "/perm 看表）"
                : "路径带 / 或 \\，目录末尾带斜杠 = 整棵子树，不带通配符；"
                        + "路径含空格时整段用双引号包起来（\"" + "D:/a b/" + "\"）")
                + "；身份：user:<QQ> / group:<群号> / ALLUSER）";
    }

    /** 切词结果：{@code toks} = 剥掉一层成对双引号后的词；{@code badQuote} = 有没配对的引号。 */
    private static final class Args {
        final List<String> toks = new java.util.ArrayList<String>();
        boolean badQuote;
    }

    /**
     * 按空白切词，<b>先剥一层成对的双引号</b>：{@code "D:/a b/"} 这样含空格的键才写得进来
     * （Windows 上 {@code C:\Program Files\…} 这种很常见）。
     *
     * <p>不带引号的老写法与 {@code split("\\s+")} <b>完全一致</b>（引号只在"词首"才特殊）；
     * 只开不闭的引号一律 {@code badQuote=true}（调用方拒掉 —— 否则后面的身份会被吞进路径里，
     * 那就成了"静默写错"）。</p>
     */
    private static Args splitArgs(String s) {
        Args r = new Args();
        String t = Str.trim(s);
        int i = 0;
        while (i < t.length()) {
            char c = t.charAt(i);
            if (c == ' ' || c == '\t') { i++; continue; }
            StringBuilder sb = new StringBuilder();
            if (c == '"') {
                i++;
                boolean closed = false;
                while (i < t.length()) {
                    char d = t.charAt(i);
                    if (d == '"') { closed = true; i++; break; }
                    if (d == '\n' || d == '\r') break;
                    sb.append(d);
                    i++;
                }
                if (!closed) r.badQuote = true;
                while (i < t.length() && t.charAt(i) != ' ' && t.charAt(i) != '\t') {
                    sb.append(t.charAt(i));                // 闭引号后紧跟的字符算同一个词（"a b"x → a bx）
                    i++;
                }
            } else {
                while (i < t.length() && t.charAt(i) != ' ' && t.charAt(i) != '\t') {
                    sb.append(t.charAt(i));
                    i++;
                }
            }
            if (sb.length() > 0) r.toks.add(sb.toString());
        }
        return r;
    }

    /**
     * 这个键能不能进对应的块：能用返回空串，不能用返回"为什么"（<b>只说理由，不列白名单</b>）。
     * <p>口径与 {@code Acl} 装载时的那两道完全同源：数据键必在 {@link Acl#dbClasses()} 里；
     * 路径键要么已在 {@link Acl#fileKeys()} 里，要么是"带分隔符、不带通配符"的路径写法。</p>
     */
    private static String keyProblem(Acl a, boolean db, String key) {
        if (db) {
            if (a.dbClasses().contains(key)) return "";
            return "它不在数据类别表里（DB 块的键只能是已知的数据类别）";
        }
        if (a.fileKeys().contains(key)) return "";
        if (key.indexOf('/') < 0 && key.indexOf('\\') < 0) {
            return "它不像一个路径（File 块的键要带 / 或 \\；目录末尾带 / = 整棵子树）";
        }
        if (key.indexOf('*') >= 0 || key.indexOf('?') >= 0) {
            return "路径不认通配符（要整棵子树就把目录写成末尾带斜杠）";
        }
        if (key.indexOf('\u0000') >= 0) return "路径里有 NUL";
        return "";
    }

    /** 回执里显示的键：File 用表里的规范写法（刚写进去的那一个优先）。 */
    private static String shownKey(Acl a, boolean db, String key, List<String> before) {
        if (db) return key;
        for (String fk : a.fileKeys()) if (!before.contains(fk)) return fk;
        return matchFileKey(a.fileKeys(), key);
    }

    /**
     * 在<b>表里已有的路径键</b>里认出这个入参的规范写法（回执用）。
     *
     * <p>只把"确实是同一个键"的几种等价写法认出来：原样、反斜杠 → 正斜杠、重复斜杠折叠；
     * Windows 下再加一条大小写不敏感（与 {@code Acl} 的路径比对口径一致，Linux 下不做，
     * 免得把 {@code /A/} 与 {@code /a/} 认成同一个）。认不出就原样返回。</p>
     *
     * <p><b>调用时机有讲究</b>：{@code revoke} 必须在<b>撤销之前</b>问这一句 —— 撤销之后那一行
     * 可能已经不在表里，那时候再问 {@link Acl#fileKeys()} 就只能回退成原样入参（回执会不一致）。</p>
     */
    private static String matchFileKey(List<String> keys, String raw) {
        if (keys == null || keys.isEmpty() || raw == null) return raw;
        for (String k : keys) if (k.equals(raw)) return k;
        String alt = canonPath(raw);
        for (String k : keys) if (k.equals(alt)) return k;
        if (File.separatorChar == '\\') {
            for (String k : keys) if (k.equalsIgnoreCase(alt)) return k;
        }
        return raw;
    }

    /** 只折叠"反斜杠 → 正斜杠"与重复斜杠（{@code ..} 的解析归 Acl，这里只认表里已有的键）。 */
    private static String canonPath(String p) {
        String s = p == null ? "" : p.replace('\\', '/');
        while (s.indexOf("//") >= 0) s = s.replace("//", "/");
        return s;
    }

    /**
     * 回执里"写到哪个文件"：按<b>归属</b>算出来的那个落点（技能 op → 该技能的 perms.jsonc，其余 → core）；
     * 没有具体 op（例如"撤他名下全部条目"）就把这次真正写了的文件都列出来。
     */
    private static String wroteText(Acl a, String op) {
        if (a == null) return "(无)";
        if (op != null && !op.trim().isEmpty()) {
            File tf = a.targetOf(op);
            if (tf != null) return permPath(a, tf);
        }
        List<File> ws = a.writtenFiles();
        if (ws.isEmpty()) return permPath(a, a.file());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ws.size(); i++) sb.append(i == 0 ? "" : "、").append(permPath(a, ws.get(i)));
        return sb.toString();
    }

    /**
     * {@code perm revoke <键> <身份> [身份…]}：撤掉该身份在这个<b>键</b>上的全部条目 / 规则。
     *
     * <p>键<b>自动认属于哪一块</b>（规格 §6）：{@link Acl#revoke(String, String)} 一次把命中的 op 条目
     * 与 DB / File 规则里的那个身份都摘掉（Ban 与 Run / Read 一起撤）。控制台这一层只做两件事：
     * <b>先全验</b>（键三选一：数据类别 / 路径 / op 名；身份过 {@link Acl#principalReason(String)}），
     * 落盘后按 {@link Acl#targetOfKey(String)} 写明落到哪一份文件。</p>
     */
    private void permRevoke(Caller me, String rest) {
        if (!masterOnly(me, "perm revoke")) return;
        Args ar = splitArgs(rest);                     // 键也可能是路径：同样认一层成对的双引号
        if (ar.badQuote) {
            out.warn("引号没配对 —— 这一条不执行（键里含空格时整段用一对双引号包起来）");
            out.dim("  用法：" + name() + "/perm revoke <键> <身份> [身份…]");
            return;
        }
        if (ar.toks.size() < 2) {
            out.warn("用法：" + name() + "/perm revoke <键> <身份> [身份…]"
                    + "（键 = op / 数据类别 / 路径，自动认；身份：user:<QQ> / group:<群号> / ALLUSER）");
            return;
        }
        String key = ar.toks.get(0);
        Acl a = aclOrWarn();
        if (a == null) return;
        String keyWhy = revokeKeyProblem(a, key);      // 键先全验：认不出就不动账本
        if (!keyWhy.isEmpty()) {
            out.warn("键写法不能用：" + keyWhy);
            return;
        }
        List<String> ids = new java.util.ArrayList<String>();
        for (int i = 1; i < ar.toks.size(); i++) {
            String one = ar.toks.get(i);
            if (one.isEmpty()) continue;
            String why = Acl.principalReason(one);
            if (!why.isEmpty()) {
                out.warn("身份写法不能用：" + one + " —— " + why);
                out.dim("  只认 user:<QQ> / group:<群号> / ALLUSER（裸 QQ 号 = user:<QQ>）。");
                return;                    // 一个身份不合法就整条不执行（先全验再动账本）
            }
            // 统一成规范身份再动手（裸 QQ → user:<QQ>），同一个人写两遍只撤一次
            String id = Acl.principalKey(one);
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        if (ids.isEmpty()) {
            out.warn("用法：" + name() + "/perm revoke <键> <身份> [身份…]");
            return;
        }
        // ★ 回执要显示**规范化后的键**：必须在**撤销之前**先认出来 —— 撤销之后那一行可能已经
        //   不在表里（"摘空 = 整条删"落地后就是如此），到那时再问 fileKeys() 只能回退成原样入参，
        //   于是 `revoke D:\gm\rev\` 的回执会显示反斜杠原文（庚 钉的低危缺陷）。
        String shown = a.dbClasses().contains(key) ? key : matchFileKey(a.fileKeys(), key);
        List<String> hit = new java.util.ArrayList<String>();
        List<String> miss = new java.util.ArrayList<String>();
        for (String one : ids) {
            if (a.revoke(key, one)) hit.add(one);
            else miss.add(one);
        }
        for (String m : miss) out.warn("没撤：" + shown + " ← " + m + " 不在账本里（这条没动）");
        if (hit.isEmpty()) {
            out.dim("  本次什么都没改，也没落盘。");
            return;
        }
        if (!a.saveAll(boot.auth())) {
            a.reload();
            out.warn("写盘失败 —— 本次撤销没落盘（" + permPath(a, a.targetOfKey(key)) + "）");
            return;
        }
        String where = permPath(a, a.targetOfKey(key));
        for (String h : hit) out.ok("已撤销：" + shown + " ← " + h + "（已写入 " + where + "）");
    }

    /** {@code perm revoke} 的键能不能用：能用返回空串，不能用返回原因（不列白名单内容）。 */
    private static String revokeKeyProblem(Acl a, String key) {
        if (a.dbClasses().contains(key)) return "";                 // DB 数据类别
        if (keyProblem(a, false, key).isEmpty()) return "";          // File 路径（已知的或路径写法）
        String opWhy = Acl.reason("Run[\"" + key + "\"]");           // op 名：走 Acl 自己的原话
        return opWhy.isEmpty() ? "" : opWhy;
    }

    /** {@code perm reload}：<b>重新合并装载</b>（外面手改了 core / 技能权限文件之后）。 */
    private void permReload() {
        if (boot == null || boot.auth() == null) {
            out.warn("perm reload 不可用：权限面未装配（" + why() + "）");
            return;
        }
        // 重新 bind 工具注册表：技能重扫之后"哪个 op 属哪个技能"可能变了，
        // 而归属决定技能权限文件里的哪些条目算越界（越界忽略）与写回落到哪个文件。
        try {
            sair.v4.tool.Registry r = boot.registry();
            if (r != null) {
                boot.auth().bind(r, boot.conf() == null ? null : boot.conf().skillsDir());
            }
        } catch (Throwable ignored) {
            // 拿不到注册表 = 退回只读 core（加载器自己会 warn 一行）
        }
        Acl a = boot.auth().reloadAcl();
        if (a == null) {
            out.warn("perm reload 没成功：账本没能装载（" + why() + "）");
            return;
        }
        out.ok("已重读权限文件（合并装载）：" + a.stat() + " / " + a.sourceStat());
        // 规格 §2.5-7：装载汇总那一行由 ②波次启动汇总（Boot）与 ai/perm 负责打印 —— reload 也是
        // "装载了一次"，所以这里追加同一行（既有那两行一字不动，这一行只往后加）。
        out.dim("  " + a.summary());
    }

    // ==================== 好感度（与权限无关） ====================

    /** 榜单上限（沿用旧 `perm list` 的口径：`top(20)`）。 */
    private static final int FAVOR_TOP = 20;

    /**
     * {@code favor}：<b>好感度</b>（她对每个人的关系值）—— 与技能管控表彻底分开：
     * 它不判任何权限，只表达"她跟这个人处得怎么样"，影响回话的热络程度。
     *
     * <p>甲方口径（本命令存在的理由）：「好感度由 SYSTEM 主导，<b>主人可直改数值</b>」——
     * 她自己只能在单次区间里小步加减，主人这一支不受限。</p>
     *
     * <p>子命令：</p>
     * <ul>
     *   <li>{@code favor}                 榜单：逐行 {@code QQ/昵称 ← 数值 · 档位}（最多 {@link #FAVOR_TOP} 行）；</li>
     *   <li>{@code favor get <QQ>}        某人当前数值与档位；</li>
     *   <li>{@code favor set <QQ> <数值>}  <b>主人直改</b>（写命令）；</li>
     *   <li>{@code favor reset <QQ>}      清成初值（0 = 初识）；不给 QQ = 清空全部记录（写命令）。</li>
     * </ul>
     *
     * <p><b>{@code set}/{@code reset} 只有主人能跑</b>（{@link #masterOnly}）。数值与档位一律走
     * {@code boot.favor()} 的现有 API（{@link Favor#of(long)} / {@link Favor#setValue} /
     * {@link Favor#resetAll()} / {@link Favor#levelName(double)}）——这里自己不算档、
     * 不碰技能管控表、也不新增任何判定。</p>
     */
    private void favor(String a, Caller me) {
        if (boot == null || boot.favor() == null) {
            out.warn("favor 不可用：好感度存储未装配（" + why() + "）");
            return;
        }
        String[] v = Str.verb(a);
        String sub = v[0].toLowerCase();
        if (sub.isEmpty()) {
            favorTop();
            return;
        }
        if ("get".equals(sub)) {
            long qq = favorQq(v[1]);
            if (qq <= 0L) {
                out.warn("用法：" + name() + "/favor get <QQ>");
                return;
            }
            out.print(favorLine(qq, null) + "\n", Out.Tone.NORMAL);
            return;
        }
        if ("set".equals(sub)) {
            favorSet(me, v[1]);
            return;
        }
        if ("reset".equals(sub)) {
            favorReset(me, v[1]);
            return;
        }
        out.warn("未知子命令：" + v[0]);
        out.dim("  用法：" + name() + "/favor [get <QQ>|set <QQ> <数值>|reset [QQ]]");
    }

    /** {@code favor}（裸）：榜单（数值降序，最多 {@link #FAVOR_TOP} 行）。 */
    private void favorTop() {
        List<JsonObject> rows = boot.favor().top(FAVOR_TOP);
        if (rows == null || rows.isEmpty()) {
            out.dim("好感度还没有记录：她对谁都没建过账（第一次打交道时自动建一行，初值 0 = 初识）。");
            return;
        }
        out.print("好感度榜单（数值降序，最多 " + FAVOR_TOP + " 行）：\n", Out.Tone.NORMAL);
        int n = 0;
        for (JsonObject r : rows) {
            if (r == null) continue;
            out.print("  " + favorLine(J.l(r, "qq", 0L), r) + "\n", Out.Tone.NORMAL);
            n++;
        }
        out.dim("  共 " + n + " 行" + (n >= FAVOR_TOP ? "（到上限 " + FAVOR_TOP + " 行，可能还有别人没列出）" : "")
                + "；改数值：" + name() + "/favor set <QQ> <数值>。");
    }

    /** 一行：{@code QQ/昵称 ← 数值 · 档位}（行里有昵称字段就带上，没有就只给 QQ）。 */
    private String favorLine(long qq, JsonObject row) {
        double v = row == null ? boot.favor().of(qq) : J.d(row, "value", boot.favor().of(qq));
        return favorWho(qq, row) + " ← " + favorNum(v) + " · " + boot.favor().levelName(v);
    }

    /** 一行里的"谁"：{@code QQ}；行里有昵称字段就 {@code QQ/昵称}（favor 表本身没有昵称列）。 */
    private static String favorWho(long qq, JsonObject row) {
        StringBuilder sb = new StringBuilder(String.valueOf(qq));
        if (row != null) {
            for (String k : new String[] {"name", "nick", "nickname", "card"}) {
                com.google.gson.JsonElement el = row.get(k);
                if (el == null || el.isJsonNull() || !el.isJsonPrimitive()) continue;
                String s = Str.trim(el.getAsString());
                if (!s.isEmpty()) {
                    sb.append('/').append(s);
                    break;
                }
            }
        }
        return sb.toString();
    }

    /** 数值打印：整数不带小数点（与旧控制台观感一致），小数原样。 */
    private static String favorNum(double v) {
        return v == Math.rint(v) && !Double.isInfinite(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** 解析一个 QQ 号；不是正整数返回 {@code -1}（调用方给可读的用法）。 */
    private static long favorQq(String raw) {
        String s = Str.trim(raw);
        if (s.isEmpty()) return -1L;
        try {
            long qq = Long.parseLong(s);
            return qq > 0L ? qq : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 解析一个数值；不是有限数返回 {@code null}（调用方给可读的用法）。 */
    private static Double favorValue(String raw) {
        String s = Str.trim(raw);
        if (s.isEmpty()) return null;
        try {
            double v = Double.parseDouble(s);
            return Double.isNaN(v) || Double.isInfinite(v) ? null : Double.valueOf(v);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * {@code favor set <QQ> <数值>}：主人直改。
     * <p>校验口径就是 {@link Favor#setValue} 那一支的现有口径：QQ 必须是正整数、数值必须
     * {@code ≥ 0}（上不封顶）——越界在这里回一句可读的拒绝，绝不抛、也绝不写。</p>
     */
    private void favorSet(Caller me, String rest) {
        if (!masterOnly(me, "favor set")) return;
        String[] p = Str.trim(rest).split("\\s+");
        long qq = p.length > 0 ? favorQq(p[0]) : -1L;
        Double want = p.length > 1 ? favorValue(p[1]) : null;
        if (qq <= 0L || want == null) {
            out.warn("用法：" + name() + "/favor set <QQ> <数值>（QQ 是正整数；数值 ≥ 0，可带小数）");
            return;
        }
        if (want.doubleValue() < 0.0D) {
            out.warn("数值越界：" + favorNum(want.doubleValue())
                    + " —— 好感度的下界是 0（负值不收；要降就设一个更小的非负数）。");
            out.dim("  上不封顶：≥900 就是最高档「" + boot.favor().levelName(900) + "」；下界 0 = 「"
                    + boot.favor().levelName(0) + "」。");
            return;
        }
        Favor.Change c = boot.favor().setValue(qq, want.doubleValue(), "控制台直改", "console");
        out.ok("已直改：" + qq + " 好感度 " + favorNum(c.before) + " → " + favorNum(c.after)
                + "（" + boot.favor().levelName(c.after) + "）");
        out.dim("  主人定值不受她自己单次加减区间（加 [1,3] / 减 [5,10]）限制；她自己的加减照旧由基板夹死。");
    }

    /**
     * {@code favor reset <QQ>}：清成初值（0 = 初识）；不给 QQ = 清空全部记录。
     * <p>单人重置走 {@link Favor#setValue}（= 她心里的"初值"就是 0，见 {@link Favor#of(long)} 的缺省）；
     * 全清走 {@link Favor#resetAll()}。两条都只有主人能跑。</p>
     */
    private void favorReset(Caller me, String rest) {
        if (!masterOnly(me, "favor reset")) return;
        String s = Str.trim(rest);
        if (s.isEmpty()) {
            out.ok("已清空全部好感度记录（" + boot.favor().resetAll() + " 条）");
            return;
        }
        long qq = favorQq(s);
        if (qq <= 0L) {
            out.warn("用法：" + name() + "/favor reset <QQ>（不给 QQ = 清空全部记录）");
            return;
        }
        double before = boot.favor().of(qq);
        Favor.Change c = boot.favor().setValue(qq, 0.0D, "控制台重置", "console");
        out.ok("已重置：" + qq + " 好感度 " + favorNum(before) + " → " + favorNum(c.after)
                + "（" + boot.favor().levelName(c.after) + "）");
    }

    // aclCmd() 已删：旧账本（perms.jsonc）那一套随本轮权限整改消失 —— 看表/写表都在上面的 perm
    //（skillctl.json）；旧命令名 ai/acl 只在 route 里留了一句退休指路。

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
     * 要能看见这份清单变空。上限口径：最多 8 行 + 余数（控制台清单的一贯做法）。</p>
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
            if (p.length < 2 || Str.blank(p[0]) || Str.blank(p[1])) {
                out.warn("用法：" + name() + "/store import <库> <路径>"
                        + "（路径从参数取 ⇒ 这个写口要过 File 判定）");
                return;
            }
            File src = new File(p[1].trim());
            // ★ import 的语义是"把本机那份文件读进来"：两道判定都按工具面 store_admin.import 同一套口径。
            // 判定主体取法与 Cmd 里既有判定同源：caller()（控制台 = 主人；主人与她本人恒全权，
            // 所以控制台这一条永远放行 —— 但"过不过判定"这件事必须是显式的两行，不能靠没人问）。
            Acl acl = aclOrWarn();
            if (acl == null) return;                      // 拿不到权限面 = 不判 = 不写（fail-closed）
            // ★ DB 域：整库覆盖（没有逐行归属可验）⇒ rowOwner=null；库名 → 数据键复用工具面那一份
            // 现成映射 Builtins.dbKeyOf（同一个库名在各处必须是同一个键，不许另起一套）。
            String dbDeny = acl.dbDeny(caller(), Builtins.dbKeyOf(p[0]), null);
            if (dbDeny != null) {
                out.warn(dbDeny);
                return;
            }
            // ★ File 域：import 读的是本机那份文件（路径从参数取）⇒ write=false 看 Read。
            String deny = acl.fileDeny(caller(), src.getAbsolutePath(), false);
            if (deny != null) {
                out.warn(deny);
                return;
            }
            int n = boot.store().importJson(p[0], src);
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
                    "AiAgent V4.2 —— 基板只做九件事：存储 / 热插拔 / 提示词 / Agent / 上下文 / 权限 / 模型 / NapCat / 控制台",
                    "本地控制台交互 ≡ QQ 中的主人交互；主人（MASTER）与她本人（SYSTEM）恒全权，工具一律放行。",
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
                    "\t" + n + "/tools [all]      当前可见工具（all=含没授权/被封禁的名字）",
                    "\t" + n + "/config [键 [值]]  看/改配置（读的是 config.json 这个文件；config set 只写文件，不生效）",
                    "\t" + n + "/config reload    已退休：热重载没了，改动只有 " + n + "/start 或 " + n + "/restart 才生效",
                    "\t" + n + "/tick             手动跑一次心跳（on_timer + 定时唤醒）",
                    "\t" + n + "/alarm            定时唤醒清单",
                    "能力：",
                    "\t" + n + "/skill [list|reload|validate|read 名]   技能库（外挂层）",
                    "\t" + n + "/prompt [show|list|sections|read 名|stat|reload]  提示词",
                    "\t" + n + "/prompt inject <slot> <key> <文本>      注入到 system/context/tool/arg",
                    "\t" + n + "/perm [list [op]|reload]           权限表（合并视图）：逐行看表 / 按 op 看谁能用 / 重新合并装载",
                    "\t" + n + "/perm gen                          只在已有权限文件里重排：按工具分组、补中文释义（不新建文件）",
                    "\t" + n + "/perm run|ban <op> <身份> [身份…]   白名单（授权）/ 黑名单（封禁）—— 只有主人能跑",
                    "\t" + n + "/perm db run|read|ban <数据键> <身份…>   DB 块：这类数据谁能改 / 谁能读 / 谁不能 —— 只有主人能跑",
                    "\t" + n + "/perm file run|read|ban <路径> <身份…>  File 块：这个路径谁能碰（目录末尾带 / = 整棵子树；路径含空格用双引号）—— 只有主人能跑",
                    "\t" + n + "/perm revoke <键> <身份>           撤掉该身份在这个键上的全部条目（op / DB / File 自动认）—— 只有主人能跑",
                    "\t" + n + "/acl                              已退休（旧权限账本）：看表请用 " + n + "/perm",
                    "\t" + n + "/favor                            好感度榜单（数值降序，最多 20 行）",
                    "\t" + n + "/favor get <QQ>                     某人当前数值与档位",
                    "\t" + n + "/favor set <QQ> <数值>              主人直改数值（不受她自己加减区间限制）—— 只有主人能跑",
                    "\t" + n + "/favor reset <QQ>                   清成初值（0 = 初识）；不给 QQ 就清空全部记录 —— 只有主人能跑",
                    "\t" + n + "/napcat [status|on|off|list|send group 群号 内容]",
                    "\t" + n + "/napcat relay [status|on|off]       文件外链中转（本地文件怎么交给 NapCat）",
                    "\t" + n + "/store [stat|maintain|export 库|import 库 路径]",
                    "\t" + n + "/debug [on [token]|off|status|simulate on|off]   本机调试面的开关（只有它能起停）",
                    "永不失联：装配部分失败时 help/status/tools/config 仍然可用，",
                    "\t" + n + "/status 的 boot.failed_step 会指出是哪一步失败、因为什么。",
                    "说明：业务能力（发图/禁言/搜索/天气/审核/主动发言……）不在控制台命令里，",
                    "\t它们由 data/skills 提供为 AI 工具；技能目录整体删掉，基板照常运转。",
                    "说明：权限 = 按归属分散的权限文件（数据根 " + Acl.CORE_FILE_NAME + " + 每个技能自己的 "
                            + Acl.SKILL_FILE_NAME + "）：身份 × op → Ban / Run / 空；"
                            + "主人与她本人恒全权；ALLUSER 按文件判，没列到 = 未授权 = 不可用。",
                    "\t身份写法 user:<QQ> / group:<群号> / ALLUSER（裸 QQ 号 = user:<QQ>）；"
                            + "条目写工具名（memory）覆盖它全部动作；Ban > Run > 空。",
                    "\t技能目录里没有 " + Acl.SKILL_FILE_NAME + " = 该技能全部 op 不授权；"
                            + "技能文件里写了不属于它的 op 会被忽略（启动日志里有一行告警）。",
                    "\t看表与改表都在控制台 " + n + "/perm；授权/撤销/查询也可以让她用 perm 工具。",
                    "说明：好感度不是权限，也不参与任何判定 —— 只影响回话热络；主人可直改"
                            + "（" + n + "/favor set <QQ> <数值>）。",
            };
        } catch (Throwable t) {
            return fallbackHelp();
        }
    }

    /** 最底线的命令表（构造/装配都不可用时的兜底；静态常量，不可能失败）。 */
    public static String[] fallbackHelp() {
        return new String[] {
                "AiAgent V4.2（降级命令表）",
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
    /** 表里的一行 + op 的中文释义（人看；取不到释义就只打 op）。 */
    private String permLine(Acl.Entry e) {
        String t = e.text();
        String g = "";
        try {
            if (boot != null && boot.auth() != null && boot.auth().ops() != null) g = boot.auth().ops().noteOf(e.op());
        } catch (Throwable ignored) {
        }
        if (g == null || g.isEmpty()) return t;
        int k = g.indexOf('（');
        if (k > 1) g = g.substring(0, k);
        return t + "（" + g + "）";
    }
}