package sair.v4;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import sair.v4.kit.Crypto;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 基板配置：数据根 + 一份扁平 JSON（{@code config.json}）。
 * <p>没有"通道"概念，因此也没有 per-channel 的模型/提示词配置；
 * {@code model=auto} 就是 {@link #autoModel()}（默认 {@code deepseek-flash}），不做任何路由判断。</p>
 *
 * <p><b>敏感键静态加密</b>：{@link #SECRET_KEYS} 里的键在 {@link #save()} 时加密落盘、
 * 在 {@link #load()} 时自动识别并解密，**内存里的 {@link #all()} 始终是明文**，
 * 所以其余读配置的代码（含技能看到的 {@code ConfView}）行为完全不变。
 * 加密算法与 V3 {@code AiConfig} 逐字节一致（见 {@link Crypto}），因此能直接吃 V3 的存量密文；
 * 盘上是明文的老值也能平滑升级 —— 第一次 {@code save()} 就变密文。</p>
 *
 * <h3>「改配置」与「生效」是两件事（主人 2026-09-16 定稿的口径）</h3>
 * <p>主人原话：「在基板 {@code ai/start} 之前，整个基板是不工作的，只有到了 {@code ai/start} 命令，
 * 基板才会工作，加载，运行，其他的命令都只是在改配置文件」。</p>
 * <ul>
 *   <li>{@link #data}（{@link #all()}/{@link #get(String, String)}/所有 getter）= <b>已生效</b>的面：
 *       <b>只在装配第①步由 {@link #load()} 从文件读一次</b>，运行期不被任何控制台命令改动；</li>
 *   <li>文件面（{@link #fileView()} / {@link #fileGet(String, String)} / {@link #fileSet(String, Object)} /
 *       {@link #fileKeys()}）= <b>{@code config.json} 本身</b>。控制台 {@code config set} 与
 *       {@code config} 工具 {@code op=set} 走的是 {@link #fileSet(String, Object)}
 *       （只改文件、一个字都不动内存），{@code config}/{@code op=list}/{@code op=get} 读的是文件面；</li>
 *   <li><b>旧口径已整条退休</b>：原来还有一张 {@code RESTART_KEYS}（"这些键只在装配期读一次"）与
 *       {@code needsRestart(...)}，于是"改了要重启"与"改完即时生效"两条尺子并存。
 *       现在只有一条：<b>改文件 ≠ 生效；生效只有 {@code ai/start}（已在跑则 {@code ai/restart}）
 *       把文件读进 {@link #data} 这一条路</b>。所以本类不再有"即时/需重启"的区分；</li>
 *   <li>想让人看见"文件与生效值不一样"：{@link #pendingKeys()}（= <b>待生效</b>）。</li>
 * </ul>
 * <p><b>例外（刻意保留）</b>：{@link #set(String, Object)} 仍是运行期内部代码的"改生效值"入口 ——
 * 例如 {@code qq.Relay} 自动生成 relay token 后要立刻用它。这类内部调用点不受上面的口径影响。</p>
 */
public final class Conf implements sair.v4.skill.ConfView {

    /**
     * 需要加密落盘的配置键。
     * <p>V3 只加密了 {@code apiKey}（{@code onebotToken} 是明文存的）；V4 把 NapCat 的
     * 接入 token 一并加密 —— 它同样是"拿到就能冒充反向 WS 客户端"的凭据。</p>
     */
    public static final String[] SECRET_KEYS = {"apiKey", "napcatToken", "relayToken"};

    /**
     * 这个键是不是"值不许外露"的敏感键（<b>自省面/控制台总览用</b>，见 {@link #knownKeys()}）。
     *
     * <p><b>= {@link #SECRET_KEYS}，一个不多一个不少</b>（{@code apiKey} / {@code napcatToken} /
     * {@code relayToken}）—— 这三个都是"拿到就能冒充"的凭据，且在盘上是 AES-GCM 密文。</p>
     *
     * <p>这里原来还特判过 {@code debugToken}。它<b>已随本机调试口一起撤出配置面</b>
     * （那四个键不再进 {@link #knownKeys()} / {@code ensureDefaults()} / {@code config.json}，
     * 调试口的 token 由 {@code ai/debug on} 现场生成、只落在运行现场凭据文件里），
     * 所以配置自省面不再需要为它留一格 —— 这张尺子现在是干净的。</p>
     */
    public static boolean isSensitive(String key) {
        if (key == null) return false;
        String k = key.trim();
        for (String s : SECRET_KEYS) if (s.equals(k)) return true;
        return false;
    }

    // ---------------- 默认值 ----------------
    public static final String DEF_API_URL = "https://api.deepseek.com";
    public static final String DEF_MODEL = "auto";
    public static final String DEF_AUTO_MODEL = "deepseek-flash";
    /** 默认的"会看图"的模型（Pro 没有视觉，只能由它兜底）。 */
    public static final String DEF_VISION_MODEL = "deepseek-flash";
    public static final double DEF_TEMPERATURE = 1.3;
    /**
     * 每轮带进上下文的历史条数（默认与 V3 对齐：V3 是 {@code GROUP_HISTORY_ROWS = 60}）。
     * <p>V3 的手感很大一部分来自"她记得刚才说过什么"；V4 早期默认 20 条，明显更健忘。</p>
     */
    public static final int DEF_HISTORY_LIMIT = 60;
    /**
     * 历史正文的字符预算（默认与 V3 对齐：V3 是 {@code GROUP_HISTORY_CHAR_BUDGET = 24000}）。
     * <p>超预算时<b>从最旧的整条丢</b>（留最新的），与 V3 同一口径；{@code <=0} = 不限。</p>
     */
    public static final int DEF_HISTORY_MAX_CHARS = 24000;
    /**
     * <b>身份提示词（{@code identityFile}）的字符预算</b>，出厂默认 {@code 8000}。
     *
     * <p><b>它只是"报警线"，不是硬闸门</b>（主人 2026-09-16 裁定，B10）：身份提示词<b>一个字都不截、
     * 一个字都不丢</b>，超预算只在控制台 warn 一行
     * （{@code [prompt] identity.md 已 4199/8000 字符（超预算，但不截断）}）。
     * 旧的"贴着 4000 字符硬上限"是<b>压缩文档的口径，不是代码里的闸门</b> —— 代码从来没有截断过；
     * 现在把它改成一个<b>可配的预算</b>，想要多长的身份设定就写多长。</p>
     *
     * <p>口径来源：主人原话「预算直接改大，再压缩就不对头」。</p>
     */
    public static final int DEF_IDENTITY_MAX_CHARS = 8000;
    /** 「低重要度记忆保留上限」的出厂默认（键 {@code maxLowImportance}）。 */
    public static final int DEF_MAX_LOW_IMPORTANCE = 500;
    /** 六库自维护间隔小时数的出厂默认（键 {@code maintainEveryHours}；{@code <=0} = 关闭）。 */
    public static final int DEF_MAINTAIN_EVERY_HOURS = 24;
    /** 控制台 {@code chat} 等回合结束的上限（毫秒）的出厂默认（键 {@code chatTimeoutMs}）。 */
    public static final long DEF_CHAT_TIMEOUT_MS = 180000L;
    /** 工具调用台账开关（键 {@code logTools}）的出厂默认。 */
    public static final boolean DEF_LOG_TOOLS = true;
    /** 钩子池溢出策略（键 {@code hookOverflow}）的出厂默认。 */
    public static final String DEF_HOOK_OVERFLOW = "caller";
    /**
     * <b>主 Agent</b>一轮的执行轮次上限（出厂默认 {@code 30}）。
     * <p>主人 2026-09-16 裁定（D46★1）：主/子<b>分开计次</b> —— 主 Agent 用本键，
     * 子 Agent 用 {@link #DEF_SUBAGENT_MAX_ROUNDS}。这一轮是不是子 Agent 只看 {@code Turn} 上
     * 基板自己写的 {@code subagent} 标记（见 {@code Loop.run}），<b>不靠谁自称</b>。</p>
     */
    public static final int DEF_MAX_ROUNDS = 30;
    /** <b>子 Agent</b>一轮的执行轮次上限（出厂默认 {@code 40}；D46★1）。 */
    public static final int DEF_SUBAGENT_MAX_ROUNDS = 40;
    /** 同时在跑的子 Agent 上限（与"轮次"无关，是**并发**上限）。 */
    public static final int DEF_SUB_AGENT_MAX = 4;
    public static final int DEF_TOOL_RESULT_MAX = 6000;
    public static final int DEF_NAPCAT_PORT = 8082;
    /** 默认只听本机回环：绑到别的地址且没设 token 时基板会拒绝启动 NapCat 服务。 */
    public static final String DEF_NAPCAT_HOST = "127.0.0.1";
    public static final long DEF_TICK_MS = 20000L;
    /** 文件外链中转端口（V3 的 FileServer 就是 2671）。 */
    public static final int DEF_RELAY_PORT = 2671;
    // P9b-2 删除：DEF_PERM_BUILTIN / DEF_PERM_SKILL（旧档位体系"缺行默认档"的出厂值）与它们的读口
    // permDefaultBuiltin()/permDefaultSkill()。旧档位表（PermTable）已整类删除，权限只看数据根的
    // perms.json 账本；config.json 里若还留着这两个键，启动时只提示一行"已退休"，不报错、不参与判定。
    /**
     * "记忆/笔记类"通用保留天数（老键 {@code keepDays}）的默认值。
     * <p>只管 {@code memory} 的超龄低重要度行与运行台账（停用闹钟/已成任务）；
     * 聊天记录有各自独立的两把尺子，见 {@link #DEF_DIALOG_KEEP_DAYS}/{@link #DEF_GROUPLOG_KEEP_DAYS}。</p>
     */
    public static final int DEF_KEEP_DAYS = 30;
    /** 对话历史（{@code dialog}）默认保留天数；{@code <=0} = 不过期。 */
    public static final int DEF_DIALOG_KEEP_DAYS = 180;
    /** 群聊历史（{@code grouplog}）默认保留天数；{@code <=0} = 不过期。 */
    public static final int DEF_GROUPLOG_KEEP_DAYS = 30;

    // ---------------- 调度（高并发：按会话分道） ----------------
    /**
     * 回合工作线程数：{@code 1} = 旧口径（所有会话挤一条队列，一个慢群堵住所有人）。
     * <p>默认 {@code 2} 是<b>保守</b>取值：真正并发的只有这个数，模型闸门（{@link #DEF_MODEL_CONCURRENCY}）
     * 才是模型侧的上限；调大它只影响"同时有几个会话在跑工具/上下文装配"，不改变模型并发度。</p>
     */
    public static final int DEF_TURN_WORKERS = 2;
    /** 全体待办回合上限（内存有界；超出按溢出策略处置，绝不静默消失）。 */
    public static final int DEF_TURN_QUEUE_MAX = 256;
    /** 单会话待办上限（同会话保序，多出来的按溢出策略处置）。 */
    public static final int DEF_TURN_LANE_MAX = 8;
    /** 同会话合并后的正文字符上限：装不下就不再合并（按溢出策略处置），不会腰斩正文。 */
    public static final int DEF_TURN_MERGE_CHARS = 4000;
    /** 模型调用闸门许可数（{@code 1} = 同一时刻只有一路模型调用）。 */
    public static final int DEF_MODEL_CONCURRENCY = 1;
    /** master_first 下连续服务高优先级道的额度（用满必须让一次普通道）。 */
    public static final int DEF_TURN_PRIORITY_BURST = 8;
    /** master_first 下普通道的优先老化时长（毫秒）；0 = 不老化。 */
    public static final long DEF_TURN_PRIORITY_AGING_MS = 5000L;
    /**
     * <b>本会话最近 N 条聊天记录</b>那个动态窗口的大小（键 {@code chatWindowSize}）。
     *
     * <p>主人 2026-09-16 的口径：「取消自动快照，而是在触发动态取聊天记录一个窗口，
     * 这个窗口要求能被设置大小的，默认值 30 条，不论是私聊还是群聊。」
     * 于是原来的游标快照（{@code ctx.Snapshot}）整条删除，改成每触发一个回合
     * 现查一次"这个会话最近的 N 条记录"，私聊与群一视同仁（机制与两个数据源见
     * {@code ctx.ChatWindow}）。</p>
     *
     * <p>{@code <=0} = 不带这个窗口（等价于关掉这一段事实）；写大过
     * {@link sair.v4.ctx.ChatWindow#MAX_ROWS} 就在那里夹住。本键每回合现读 —— 改完即时生效。</p>
     */
    public static final int DEF_CHAT_WINDOW_SIZE = 30;

    /**
     * 偏好块注入开关（默认开）。
     * <p>偏好 = 六库 {@code pref} 里这个调用者可见的三层（{@code global} + {@code user} + {@code group}，
     * 控制台只有 {@code global}），合并后进系统提示词的<b>稳定段</b>（见 {@code ctx.CtxBuild}）。
     * 关掉 = 一个字都不进上下文（省 token 用），代价是"设了偏好没人看"的缺口又回来了。</p>
     */
    public static final boolean DEF_PREF_INJECT = true;

    /**
     * 偏好块的<b>条数</b>上界（默认 30；{@code <=0} = 不按条数截断，只受字符上界管）。
     * <p>V3 在这件事上没有任何预算（偏好越多提示词越长），v4 两把尺子都留。</p>
     */
    public static final int DEF_PREF_INJECT_MAX_ROWS = 30;

    /** 偏好块的<b>字符</b>上界（默认 1200；{@code <=0} = 不按字符截断）。 */
    public static final int DEF_PREF_INJECT_MAX_CHARS = 1200;

    // ---------------- 扩展点护栏（外挂越多，这里越重要；见 notes/plugin-api.md 第四节） ----------------

    /**
     * 扩展点总开关（默认开）。
     * <p>关掉它 = 基板回到"零外挂"行为：所有 {@code ext} 扩展点一概不调用，
     * 装配、出站、投票、生命周期四处与"一个插件都没挂"逐字节一致。
     * 这是给主人留的<b>救火开关</b>：某个插件把回合拖慢/把上下文撑爆时，
     * 不必去删插件目录、也不必重启，改这一个键就回到干净状态。</p>
     */
    public static final boolean DEF_EXT_ENABLED = true;

    /**
     * 每个 provider / stage / voter / hook 的<b>单次调用</b>超时（毫秒，默认 200）。
     * <p>超时只丢这一次调用（provider 丢这一段、stage 保持正文原样、voter 当"不管"、
     * hook 当没发生），记一行 {@code [ext] timeout name=… ms=…}，<b>本轮照常跑完</b>。
     * {@code <=0} = 不超时（调用线程直接跑，只靠插件自觉）。</p>
     */
    public static final int DEF_EXT_TIMEOUT_MS = 200;

    /**
     * 上下文 provider 块的<b>全局</b>字符预算（默认 6000；{@code <=0} = 不限）。
     * <p>每个 provider 自己还有一道 {@link sair.v4.ext.ContextProvider#maxChars()}；
     * 这一道是"所有块加起来"的总闸。超出时按 {@code order} 从后往前<b>整段</b>丢
     * （不是截一半），并记一行 {@code [ext] budget …}。</p>
     */
    public static final int DEF_CTX_BUDGET_CHARS = 6000;
    /** 钩子执行池线程数；{@code <=0} = 不进池（调用线程直接跑，旧行为）。 */
    public static final int DEF_HOOK_WORKERS = 4;
    /** 钩子执行池队列上限；{@code <=0} = 不进池。满时 caller-runs（不丢、不自锁）。 */
    public static final int DEF_HOOK_QUEUE_MAX = 64;
    /** NapCat 事件执行池线程数；{@code <=0} = 每事件一个裸线程（旧行为）。 */
    public static final int DEF_EVENT_WORKERS = 8;
    /** NapCat 事件执行池队列上限；{@code <=0} = 不排队（每事件一个裸线程）。 */
    public static final int DEF_EVENT_QUEUE_MAX = 512;
    /** 等模型闸门的最长时间（毫秒）；超时本次调用放弃并收敛成一条失败结果。 */
    public static final long DEF_MODEL_GATE_WAIT_MS = 180000L;
    /**
     * 技能编译是否放到后台线程（默认 {@code false} = 旧口径：启动时第⑯步同步编完）。
     * <p>{@code true} 时基板先用 15 个基板工具可用，技能在 {@code v4-skills} 线程里编完
     * <b>逐个</b>注册工具（细节见 {@code Boot.startSkillsAsync}）。</p>
     */
    public static final boolean DEF_SKILLS_ASYNC = false;

    // ---------------- 控制台捕获器（基板⑨ 的「读」半边；机制在 term.ConsoleTap） ----------------

    /**
     * 控制台捕获器的环形缓冲条数上限（默认 {@value #DEF_CONSOLE_TAP_ITEMS}）。
     * <p>捕获是"即使控制台被 {@code /clear}、或按内存预算裁掉最旧一半，模型仍读得到最近输出"的那一层，
     * 所以它自己也是有界的：条数与字符数<b>双上限</b>，超了丢最旧的。</p>
     */
    public static final int DEF_CONSOLE_TAP_ITEMS = 500;

    /** 控制台捕获器的字符预算（默认 64KB）；与 {@link #DEF_CONSOLE_TAP_ITEMS} 一起决定缓冲占用上界。 */
    public static final int DEF_CONSOLE_TAP_CHARS = 64 * 1024;

    /** {@code console op=read} 不带 {@code tail_chars} 时的默认返回长度（字符）。 */
    public static final int DEF_CONSOLE_TAP_READ_CHARS = 4000;

    /** 捕获开关（默认开）。关掉 = 不注册打印代理 → 控制台读不到（模型仍可用 {@code op=visible} 读当前全文）。 */
    public static final boolean DEF_CONSOLE_TAP_ENABLED = true;

    /**
     * 打印代理的转发档位（默认 {@code auto}）。
     * <p>框架契约：<b>只要注册了任意打印代理，框架自己就不再打印</b>，由代理负责写进控制台。
     * {@code auto} = 只在自己是唯一代理时转发（还有别的代理时假定它已转发，避免同一行打两次）；
     * {@code always} = 总是转发；{@code never} = 从不转发。</p>
     */
    public static final String DEF_CONSOLE_TAP_FORWARD = "auto";

    // ---------------- 本机调试口（机制在 dev.DebugPort / dev.DebugSimPort） ----------------
    // 两个口都固定、只走回环：2660 = 输出口（读全部控制台/对话 + 下命令），
    // 2661 = 输入口（灌注站消息 / 下发 SFW 命令）。唯一闸门 = 回环 + token。
    //
    // 【口径变更】它们<b>不再是配置键</b>：debugPort / debugToken / debugSimPort / debugSimulate
    // 四个键已从 knownKeys() / ensureDefaults() / config.json 里全部拿掉，起停只由控制台命令
    // `ai/debug on|off|status` 说了算（这也是"只有 ai/debug 能起停调试口"的唯一入口）。
    // 下面这几个 DEF_* 只剩一个用处：命令没给参数时的出厂默认值。
    // 它们都<b>不进</b> ai/help（隐藏能力），只写在 docs\ 里供技能开发者调试用。

    /**
     * 调试口监听端口（出厂 {@value #DEF_DEBUG_PORT}；{@code <=0} = 关）。
     * <p>只绑 {@code 127.0.0.1}，不对外。{@code 2660} 是本机实测没人用的冷门口，并且刻意避开
     * 框架/插件的势力范围（{@code 2671} 中转 / {@code 8082} NapCat 反向口 / {@code 8083}）。</p>
     * <p><b>不在 config.json 里</b>（见上面的口径变更）；测试/共存时可用系统属性
     * {@code -Dv4.debug.port} 覆盖（见 {@link sair.v4.dev.DebugPort#port()}）。</p>
     */
    public static final int DEF_DEBUG_PORT = 2660;

    /**
     * 调试口 token 的出厂值：<b>空</b> —— 意思不是"默认不校验"，而是"没 token 一律拒绝启动"。
     * <p>真正的 token 由 {@code ai/debug on} 现生成（或命令里给），随后只落在运行现场凭据文件
     * {@code <数据根>/debug-port.json} 里，<b>不写进 config.json</b>。</p>
     */
    public static final String DEF_DEBUG_TOKEN = "";

    /**
     * 调试<b>输入</b>口监听端口（出厂 {@value #DEF_DEBUG_SIM_PORT}；{@code <=0} = 关）。
     * <p>与 {@link #DEF_DEBUG_PORT} 是<b>两个独立的回环口</b>：2660 是输出口（读控制台/对话全文、
     * 下框架命令），2661 是输入口（灌注站消息 / 下发 SFW 命令）。
     * 同一条铁律：只绑 {@code 127.0.0.1}、<b>必须带 token</b>（无 token 拒绝启动并打一行可读原因，
     * 实例照常可用）。两个口共用同一个 token。</p>
     */
    public static final int DEF_DEBUG_SIM_PORT = 2661;

    /**
     * 调试输入口的仿真开关出厂值（{@value #DEF_DEBUG_SIMULATE} = 正常能力，默认开）。
     * <p><b>false 时的行为是硬约定</b>：端口<b>照常监听、照常收 AUTH</b>，但每一个动作回
     * {@code -ERR debugSimulate=off} —— 绝不允许表现成"连不上"（那样排查的人会去查网络/端口，
     * 而真正的原因是这里关着）。开关由 {@code ai/debug simulate on|off} 控制，不在配置文件里。</p>
     */
    public static final boolean DEF_DEBUG_SIMULATE = true;

    /**
     * 详细日志开关（默认 {@code false}）。
     * <p><b>默认只打"逻辑怎么走"的结构性事实，不打内容</b>：QQ 消息正文、模型回复正文、
     * 工具结果正文都不进控制台；开着它才把正文打出来（排障用）。
     * 结构性事实（{@code [qq]}/{@code [lane]}/{@code [turn]}/{@code [tool]} 行、启动进度、
     * 错误与警告、权限阻断）不受它影响，永远在。</p>
     */
    public static final boolean DEF_LOG_VERBOSE = false;

    /**
     * 控制台默认打哪几类行（见 {@link #logOn(String)}）。
     * <p>默认只留 <b>Agent 调用事件</b>：{@code tool}（调了哪个工具）+ {@code model}（走的哪个模型、
     * 缓存命中多少、用时多少）。消息级/调度级/回合级的"过程细节"默认不打。</p>
     */
    public static final String DEF_LOG_CONSOLE = "tool,model";

    // ---------------- 配置生效口径：只剩一条（改文件 ≠ 生效；ai/start 才加载/运行） ----------------

    /**
     * <b>旧的"即时生效 / 改了要重启"两张尺子已整条退休</b>（主人 2026-09-16 定稿的口径）。
     *
     * <p>这里原来是一张 {@code RESTART_KEYS} 表 + {@link #needsRestart(String)} 判据：
     * 键分成"每次用到时读（改完即时生效）"与"只在装配期读一次（要 {@code restart}）"两类，
     * 于是控制台要按类分别提示、自省清单要按类分组。</p>
     *
     * <p>现在只有一条：<b>{@code config set} 只改 {@code config.json} 这个文件，
     * 运行中的基板一个键都不动；要让文件里的值生效，只有 {@code ai/start}（没在跑）
     * 或 {@code ai/restart}（已经在跑）—— 它们把文件读进内存并重新装配。</b></p>
     *
     * <p>连带的三条后果（都是这条口径的必然，不是遗漏）：</p>
     * <ul>
     *   <li>{@code napcat on|off}、{@code napcat relay on|off} 也<b>只改文件</b>
     *       （原来它们会顺手启停监听）；要重绑端口就 {@code ai/restart}；</li>
     *   <li>{@code config reload}（旧的热重载）已无意义，命令保留但明确回"已退休"；</li>
     *   <li>本机调试口不再是配置键，起停只看 {@code ai/debug on|off}（见 {@link #DEF_DEBUG_PORT}）。</li>
     * </ul>
     *
     * <p>旧 {@code permissionMatrix} 键已随旧档位体系删除：这一版没有"按档位的权限矩阵"，权限只看
     * {@code perms.json} 里的例外条目 + 按资源类型的默认分配。{@code config.json} 里若还留着
     * {@code permissionMatrix}，它会被当成未知键忽略、不参与任何判定（只有 {@code permDefaultBuiltin} /
     * {@code permDefaultSkill} 这两个退休键会被 {@code Boot.warnRetiredPermKeys()} 点一次名）。</p>
     */

    // ---------------- 字段 ----------------
    private final File root;
    private final File file;
    /** <b>已生效</b>的面（只在装配第①步由 {@link #load()} 从文件读一次）。 */
    private volatile JsonObject data = new JsonObject();

    /** 解密失败等需要让主人看见的提示（{@link #load()} 时累积，只增不清到下次 load）。 */
    private final List<String> warnings = new ArrayList<String>();

    /** 判定为"是密文但解不开"的键：{@link #save()} 原样写回，**绝不二次加密**（否则把唯一一份密文毁掉）。 */
    private final Set<String> lockedSecrets = new HashSet<String>();

    public Conf(File root) {
        this.root = root;
        this.file = new File(root, "config.json");
    }

    public File root() { return root; }

    public File file() { return file; }

    /** 相对数据根的路径。 */
    public File path(String rel) { return new File(root, rel); }

    /** 加载时的告警（解密失败、因子变化等）；空列表 = 一切正常。 */
    public List<String> warnings() { return new ArrayList<String>(warnings); }

    public boolean load() {
        String txt = Fs.read(file);
        JsonObject o = J.obj(txt);
        if (o == null) return false;
        data = o;
        warnings.clear();
        lockedSecrets.clear();
        decryptSecrets();
        return true;
    }

    /** 把 {@link #SECRET_KEYS} 里的密文就地换成明文；解不开的留原值并记告警。 */
    private void decryptSecrets() {
        for (String k : SECRET_KEYS) {
            String raw = J.s(data, k, null);
            if (raw == null || raw.isEmpty()) continue;
            if (!Crypto.looksEncrypted(raw)) continue;          // 明文（老配置/手填）→ 不动，save 时再加密
            String plain = Crypto.decrypt(raw);
            if (plain != null) {
                data.addProperty(k, plain);
                continue;
            }
            lockedSecrets.add(k);
            warnings.add("config.json 的「" + k + "」看起来是加密值，但解不开 —— 多半是运行因子变了"
                    + "（换机器 / 换 JVM / 换用户名 / 换启动目录）。已保留原值不覆盖，请核对当前因子："
                    + Crypto.factorReport() + "。也可以把该键改成明文，下次保存会自动重新加密。");
        }
    }

    // 权限只管控技能（op）：基板自身触碰文件不再有"资源位"这道判定 ——
    // 资源对主人与她本人（SYSTEM）完全可见、可改、可执行；对 ALLUSER 是黑盒，唯一的路是技能。

    public boolean save() {
        JsonObject out = J.obj(J.json(data));
        if (out == null) out = new JsonObject();
        for (String k : SECRET_KEYS) {
            String v = J.s(out, k, null);
            if (v == null || v.isEmpty()) continue;             // 空值保持空（不产出一个"空密文"）
            if (lockedSecrets.contains(k)) continue;            // 解不开的：原样写回，不做二次加密
            String enc = Crypto.encrypt(v);
            if (enc != null) out.addProperty(k, enc);           // 加密失败就保持明文，至少不丢配置
        }
        return Fs.write(file, J.pretty(out));
    }

    // ==================== 文件面：「改配置」只动这个文件 ====================
    //
    // 为什么单独一层：{@link #data} 是"已生效"的面（装配第①步读一次），而 config.json 是"主人写的那份"。
    // 两者必须能真的分开 —— 控制台 `config set` / 工具 `config op=set` 走下面这几个方法，
    // 内存里的生效值一个字段都不动；要让它们生效只有 ai/start（已在跑则 ai/restart）。
    // 这一层读的是**文件本身**，所以主人在外面手改了 config.json，`ai/config` 也看得见。

    /** 判定为"加密落盘"的键（写文件面时要现加密，口径与 {@link #save()} 一致）。 */
    private static boolean isSecret(String key) {
        if (key == null) return false;
        for (String s : SECRET_KEYS) if (s.equals(key.trim())) return true;
        return false;
    }

    /**
     * <b>文件面</b>：把 {@code config.json} 读成一份<b>独立</b>的配置对象（内存里的生效值一点不动）。
     *
     * <p>返回对象是刚 load 出来的，所以敏感键在这里同样是<b>解密后的明文</b>
     * （口径与内存面一致）；要给人看请按 {@link #isSensitive(String)} 打码。</p>
     */
    public Conf fileView() {
        Conf c = new Conf(root);
        try {
            c.load();
        } catch (Throwable ignored) {
        }
        return c;
    }

    /** 文件里某个键的原值（文件里没有就返回 {@code def}；<b>不</b>回落到出厂默认）。 */
    public String fileGet(String key, String def) {
        return fileView().get(key, def);
    }

    /**
     * 写一个键：<b>只写文件，不改内存态</b>（内存态要生效得重启，或走对应面的 reload）。
     *
     * <ul>
     *   <li>{@link #SECRET_KEYS} 里的键<b>现加密</b>（空值仍写空，不产出"空密文"）；</li>
     *   <li>文件不存在/为空时<b>先落一份出厂默认</b>再改它（否则一个键孤零零躺在那儿，
     *       第一次 {@code ai/start} 会把默认值补齐，读起来像"我改的键被冲掉了"）。</li>
     * </ul>
     */
    public boolean fileSet(String key, Object v) {
        if (key == null || key.trim().isEmpty()) return false;
        String k = key.trim();
        try {
            if (!file.isFile() || Fs.size(file) == 0) ensureDefaults();      // 首次：先把默认配置落盘
        } catch (Throwable ignored) {
        }
        JsonObject o = J.obj(Fs.read(file, ""));
        if (o == null) o = new JsonObject();
        Object w = v;
        if (isSecret(k)) {
            String s = v == null ? "" : String.valueOf(v);
            if (s.isEmpty()) {
                w = "";
            } else {
                String enc = Crypto.encrypt(s);
                if (enc != null) w = enc;                                    // 加密失败就保持明文，至少不丢配置
            }
        }
        J.put(o, k, w);
        return Fs.write(file, J.pretty(o));
    }

    /**
     * {@code true} = 这个键在文件里的值与<b>已生效</b>的值不一样（即：<b>待生效</b>）。
     *
     * <p>判据只对"文件里真的写了"的键成立 —— 文件里没写的键不算待生效
     * （它跑的是出厂默认，改不改都一样）。</p>
     */
    public boolean isPending(String key, Conf fileView) {
        if (key == null) return false;
        Conf f = fileView == null ? fileView() : fileView;
        String fv = f.get(key, null);
        if (fv == null) return false;
        Key k = knownKey(key);
        String applied = k == null ? get(key, null) : k.value();
        if (applied == null) return true;
        return !fv.equals(applied);
    }

    /**
     * <b>待生效</b>的已知键（文件里的值与已生效的值不一样的那些；顺序 = {@link #knownKeys()}）。
     *
     * <p>这是新口径下唯一能让人看见"我改了、但还没生效"的地方：{@code ai/status}、
     * {@code ai/config} 总览、{@code config} 工具 {@code op=list/get} 都标它。</p>
     */
    public List<String> pendingKeys() { return pendingKeys(null); }

    /** 同 {@link #pendingKeys()}，但复用调用方已经读好的文件面（省一次文件读）。 */
    public List<String> pendingKeys(Conf fileView) {
        List<String> out = new ArrayList<String>();
        Conf f = fileView == null ? fileView() : fileView;
        for (Key k : knownKeys()) if (isPending(k.name(), f)) out.add(k.name());
        return out;
    }

    // ---------------- 通用读写 ----------------

    public String get(String key, String def) {
        String v = J.s(data, key, null);
        return v == null ? def : v;
    }

    public void set(String key, Object v) {
        J.put(data, key, v);
    }

    public JsonObject all() { return data; }

    /** 只读快照（给技能的 {@code ConfView} 用：改它不影响基板）。 */
    public JsonObject snapshot() {
        JsonObject o = J.obj(J.json(data));
        return o == null ? new JsonObject() : o;
    }

    public int getInt(String key, int def) { return J.i(data, key, def); }

    public long getLong(String key, long def) { return J.l(data, key, def); }

    public double getDouble(String key, double def) { return J.d(data, key, def); }

    public boolean getBool(String key, boolean def) { return J.b(data, key, def); }

    // ---------------- 便捷访问 ----------------

    public String apiKey() { return get("apiKey", ""); }

    public String apiUrl() {
        String u = get("apiUrl", DEF_API_URL);
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /** 配置里的模型名（可能是 {@code auto}）。 */
    public String model() { return get("model", DEF_MODEL); }

    public String autoModel() { return get("autoModel", DEF_AUTO_MODEL); }

    /**
     * 具备视觉能力的模型（Pro 看不了图 → 需要视觉时派一个用它的子 Agent 兜底）。
     * 主 Agent 会在事实块里看到 {@code models.vision} 与 {@code models.main_has_vision}。
     */
    public String visionModel() { return get("visionModel", DEF_VISION_MODEL); }

    /** 实际调用用的模型：auto = 默认模型，无路由、无分类。 */
    public String resolveModel() {
        String m = model();
        if (m == null || m.trim().isEmpty() || "auto".equalsIgnoreCase(m.trim())) return autoModel();
        return m.trim();
    }

    public boolean isAutoModel() {
        String m = model();
        return m == null || m.trim().isEmpty() || "auto".equalsIgnoreCase(m.trim());
    }

    public double temperature() { return getDouble("temperature", DEF_TEMPERATURE); }

    public double topP() { return getDouble("topP", -1.0); }

    public int maxTokens() { return getInt("maxTokens", 0); }

    // ---- NapCat ----
    public boolean napcatEnabled() { return getBool("napcatEnabled", false); }

    public int napcatPort() { return getInt("napcatPort", DEF_NAPCAT_PORT); }

    /** 反向 WS 服务绑定地址（默认 127.0.0.1；NapCat 在别的机器上时才改成 0.0.0.0 并设 token）。 */
    public String napcatHost() { return get("napcatHost", DEF_NAPCAT_HOST); }

    public String napcatToken() { return get("napcatToken", ""); }

    public long selfId() { return getLong("selfId", 0L); }

    public int napcatTimeoutMs() { return getInt("napcatTimeoutMs", 10000); }

    // ---- 文件外链中转（NapCat 不在同一台机器时，本地文件改由 URL 交给它取） ----
    /**
     * 是否启用文件外链中转。<b>默认 false</b>：这个服务能读出本机任意文件，
     * 只有明确需要"NapCat 在别的机器上"时才打开（打开前请先填 {@link #relayPublicHost()}）。
     */
    public boolean relayEnabled() { return getBool("relayEnabled", false); }

    /** 中转监听端口；0 = 让系统挑一个空闲端口（实际端口在 status 里给出）。 */
    public int relayPort() { return getInt("relayPort", DEF_RELAY_PORT); }

    /** 对外主机（NapCat 那台机器用它来取文件）；留空 = 用本机探测到的地址。 */
    public String relayPublicHost() { return get("relayPublicHost", ""); }

    /** URL 里的随机 token 前缀；留空 = 启动时生成并写回本配置（可复现、可手改）。 */
    public String relayToken() { return get("relayToken", ""); }

    /** 链接有效期（分钟）；0 = 不过期。到点中转自己停。 */
    public int relayTtlMinutes() { return getInt("relayTtlMinutes", 0); }

    // ---- 主人 ----
    public long masterQQ() { return getLong("masterQQ", 0L); }

    // ---- 运行 ----

    /**
     * QQ 事件日志开关（默认开）——<b>打的是"逻辑怎么走"的结构性事实，不是内容</b>。
     *
     * <p>口径：{@code logQq=true} 时每个入站事件/消息打一行结构事实
     * （类型、群号、QQ、是否被 @、判定结果、路由到哪条道、被谁接管、为什么不接），
     * <b>消息正文一律不打</b>；正文只在 {@link #logVerbose()} 为 true 时出现。
     * {@code logQq=false} 时连结构行也不打（错误/警告/权限阻断不受影响，永远在）。</p>
     */
    public boolean logQq() { return getBool("logQq", true); }

    /**
     * <b>工具调用台账开关</b>（配置 {@code logTools}，默认 {@link #DEF_LOG_TOOLS}）。
     * <p>打的是一行"调了哪个工具、多大、成没成、多久"（{@code [tool] 名 ms= chars= ok= by=…}）；
     * 关掉它<b>不影响</b>错误、警告与权限阻断。每次调用时读 —— 改完即时生效。</p>
     */
    public boolean logTools() { return getBool("logTools", DEF_LOG_TOOLS); }

    /**
     * 六库自维护（{@code Store.maintain}）的间隔小时数（配置 {@code maintainEveryHours}，
     * 默认 {@link #DEF_MAINTAIN_EVERY_HOURS}；{@code <=0} = 关闭）。
     * <p>{@code Tick} 每次心跳读 —— 改完即时生效。</p>
     */
    public int maintainEveryHours() { return getInt("maintainEveryHours", DEF_MAINTAIN_EVERY_HOURS); }

    /**
     * 维护时"低重要度记忆"最多留几条（配置 {@code maxLowImportance}，
     * 默认 {@link #DEF_MAX_LOW_IMPORTANCE}；{@code <=0} = 不截断）。
     * <p>{@code Tick} / {@code store_admin op=maintain} 每次读 —— 改完即时生效。</p>
     */
    public int maxLowImportance() { return getInt("maxLowImportance", DEF_MAX_LOW_IMPORTANCE); }

    /**
     * 钩子池队列满时的策略（配置 {@code hookOverflow}，默认 {@link #DEF_HOOK_OVERFLOW}）。
     * <p>{@code caller} = 调用线程直接跑（不丢任务）；{@code skip} = 跳过并回一句。
     * <b>建池时读一次</b>（改动要 {@code ai/restart} 才生效 —— 新口径下所有键都是这一条）。</p>
     */
    public String hookOverflow() { return get("hookOverflow", DEF_HOOK_OVERFLOW); }

    /**
     * 控制台 {@code chat} 等回合结束的上限（毫秒，配置 {@code chatTimeoutMs}，
     * 默认 {@link #DEF_CHAT_TIMEOUT_MS}）。每次用到时读 —— 改完即时生效。
     */
    public long chatTimeoutMs() { return getLong("chatTimeoutMs", DEF_CHAT_TIMEOUT_MS); }

    public int agentMaxRounds() { return getInt("agentMaxRounds", DEF_MAX_ROUNDS); }

    /**
     * <b>子 Agent</b>一轮的执行轮次上限（键 {@code subagentMaxRounds}，默认
     * {@link #DEF_SUBAGENT_MAX_ROUNDS} = 40）。
     * <p>与 {@link #agentMaxRounds()} <b>分开计次</b>（D46★1）：这一轮是不是子 Agent，只看
     * {@code Turn} 上的 {@code subagent} 标记（基板自己在派活时写）。<b>每回合读</b>：
     * 新口径下它照样是"改了要 {@code ai/restart} 才生效"（生效面只在装配第①步读一次文件）。</p>
     */
    public int subagentMaxRounds() { return getInt("subagentMaxRounds", DEF_SUBAGENT_MAX_ROUNDS); }

    public int subAgentMax() { return getInt("subAgentMax", DEF_SUB_AGENT_MAX); }

    public int toolResultMax() { return getInt("toolResultMax", DEF_TOOL_RESULT_MAX); }

    public long tickMs() { return getLong("tickMs", DEF_TICK_MS); }

    // ---- 调度（高并发：按会话分道；机制在 sair.v4.schedule.Lanes / Pool） ----

    /** 回合工作线程数（默认 2；1 = 旧口径：所有会话一条队列）。 */
    public int turnWorkers() { return getInt("turnWorkers", DEF_TURN_WORKERS); }

    /** 全体待办回合上限（默认 256）。 */
    public int turnQueueMax() { return getInt("turnQueueMax", DEF_TURN_QUEUE_MAX); }

    /** 单会话待办上限（默认 8）。 */
    public int turnLaneMax() { return getInt("turnLaneMax", DEF_TURN_LANE_MAX); }

    /**
     * 溢出策略（{@code coalesce} 默认 / {@code drop}）。
     * <p>{@code coalesce}：同会话后到的消息<b>并进还在排队的那一条</b>（合成一回合），不丢内容；
     * {@code drop}：丢弃并计数（是否回一句见 {@link #turnDropNotice()}）。</p>
     */
    public String turnOverflow() { return get("turnOverflow", "coalesce"); }

    /** 丢弃时是否回一句外挂文案（{@code prompts/tools-index.md} 的「队列溢出文案」）；默认 true。 */
    public boolean turnDropNotice() { return getBool("turnDropNotice", true); }

    /** 排队时是否回一句外挂文案（同一会话每轮繁忙期最多一句）；默认 false，避免刷屏。 */
    public boolean turnQueuedNotice() { return getBool("turnQueuedNotice", false); }

    /** 优先级（{@code master_first} 默认：主人/私聊优先于群；{@code fifo}：纯先到先得）。 */
    public String turnPriority() { return get("turnPriority", "master_first"); }

    /**
     * 连续服务高优先级道的额度：用满就必须让一次普通道（防"私聊一直来把群饿死"）。
     * 默认 {@link #DEF_TURN_PRIORITY_BURST}。
     */
    public int turnPriorityBurst() { return getInt("turnPriorityBurst", DEF_TURN_PRIORITY_BURST); }

    /**
     * 优先级老化：普通道等超过这个毫秒数就按高优先级对待（{@code 0} = 不老化）。
     * 默认 {@link #DEF_TURN_PRIORITY_AGING_MS}；它与"额度"一起把最坏等待钉在有限量级。
     */
    public long turnPriorityAgingMs() { return getLong("turnPriorityAgingMs", DEF_TURN_PRIORITY_AGING_MS); }

    /**
     * <b>本会话最近 N 条聊天记录</b>的窗口大小（键 {@code chatWindowSize}，
     * 默认 {@link #DEF_CHAT_WINDOW_SIZE} = 30）。
     *
     * <p>每触发一个回合现查一次这个会话最近的 N 条记录（群读 {@code grouplog}、
     * 私聊/控制台读 {@code dialog}），做成事实块里那一段"【本会话最近 N 条聊天记录】"。
     * 机制、两个数据源为什么不对称、去重做到哪一步，全在 {@code ctx.ChatWindow} 的类注释里。</p>
     *
     * <p>{@code <=0} = 不带这一段事实；下界 {@code 1}、上界
     * {@link sair.v4.ctx.ChatWindow#MAX_ROWS} 由消费者自己夹。<b>每回合热读</b>。</p>
     */
    public int chatWindowSize() { return getInt("chatWindowSize", DEF_CHAT_WINDOW_SIZE); }

    // ---- M4：引用（reply）解析与多模态媒体面（12 个键） ----
    //
    // 登记口径与 chatWindowSize 完全一致：**进 knownKeys() 的「上下文注入」面、不进
    // ensureDefaults()**（"已知但出厂不写盘"那一类；缺省值只写在这里一处）。
    // 其中 M4 真正读的两个是 mediaExpandQuote（总开关）与 mediaCacheHours（TTL）；
    // 其余 10 个属于同一批冻结的媒体键表（渲染/识图/附图/转发展开），本步只登记不读，
    // 于是"键表"与"谁读它"这两件事在 knownKeys() 里一眼可查（见 KEYS.md §2）。

    /**
     * 引用消息是否补拉被引用正文（键 {@code mediaExpandQuote}，默认 <b>true</b>）。
     *
     * <p>true = 在"这一条会起一轮的那一刻"查一次（先本地库、没命中入队走 NapCat），
     * 命中就把引文写进本轮事实行（{@code quote: {…}}）并按主键写回那一行的 {@code extra}；
     * <b>回合路径与入站路径都不发网络调用</b>（网络只在单线程 worker 上）。
     * false = 完全回到今天的行为（入站只留 ID，不查、不取、不写 extra 的 body）。</p>
     */
    public boolean mediaExpandQuote() { return getBool("mediaExpandQuote", true); }

    /**
     * 展开类缓存的 TTL 小时数（键 {@code mediaCacheHours}，默认 <b>168</b> = 7 天；{@code 0} = 永不过期）。
     * <p>有效值还会被夹到 {@code grouplogKeepDays × 24}：{@code Store.maintain()} 完全不清 {@code kv}，
     * 不夹这一刀，别人的话会活得比群聊记录还久（保留期口径的漏洞）。</p>
     */
    public int mediaCacheHours() { return getInt("mediaCacheHours", 168); }

    /** 媒体段是否渲染成标签（键 {@code mediaRender}，默认 true）。 */
    public boolean mediaRender() { return getBool("mediaRender", true); }

    /** 标签总字符上限（键 {@code mediaRenderMaxChars}，默认 1000）。 */
    public int mediaRenderMaxChars() { return getInt("mediaRenderMaxChars", 1000); }

    /** 识图档位（键 {@code mediaVision}，默认 {@code auto}）。 */
    public String mediaVision() { return get("mediaVision", "auto"); }

    /** 识图用的模型（键 {@code visionModels}，默认 {@code deepseek-flash}）。 */
    public String visionModels() { return get("visionModels", "deepseek-flash"); }

    /** 附图张数上限（键 {@code mediaAttachMax}，默认 4）。 */
    public int mediaAttachMax() { return getInt("mediaAttachMax", 4); }

    /** 附图单张字节上限（键 {@code mediaAttachMaxBytes}，默认 4194304 = 4 MiB）。 */
    public long mediaAttachMaxBytes() { return getLong("mediaAttachMaxBytes", 4194304L); }

    /** 折叠转发展开总开关（键 {@code mediaExpandForward}，默认 false）。 */
    public boolean mediaExpandForward() { return getBool("mediaExpandForward", false); }

    /** 转发展开的节点数上限（键 {@code mediaExpandMaxNodes}，默认 20）。 */
    public int mediaExpandMaxNodes() { return getInt("mediaExpandMaxNodes", 20); }

    /** 转发展开的深度上限（键 {@code mediaExpandMaxDepth}，默认 1）。 */
    public int mediaExpandMaxDepth() { return getInt("mediaExpandMaxDepth", 1); }

    /** 展开出来的字符上限（键 {@code mediaExpandMaxChars}，默认 20000）。 */
    public int mediaExpandMaxChars() { return getInt("mediaExpandMaxChars", 20000); }

    /**
     * 偏好块要不要注入（默认 {@link #DEF_PREF_INJECT}）。
     * <p>每回合装配时读一次 —— 改完即时生效（不用重启）。</p>
     */
    public boolean prefInject() { return getBool("prefInject", DEF_PREF_INJECT); }

    /** 偏好块的条数上界（默认 {@link #DEF_PREF_INJECT_MAX_ROWS}；{@code <=0} = 不按条数截断）。 */
    public int prefInjectMaxRows() { return getInt("prefInjectMaxRows", DEF_PREF_INJECT_MAX_ROWS); }

    /** 偏好块的字符上界（默认 {@link #DEF_PREF_INJECT_MAX_CHARS}；{@code <=0} = 不按字符截断）。 */
    public int prefInjectMaxChars() { return getInt("prefInjectMaxChars", DEF_PREF_INJECT_MAX_CHARS); }

    // ---- 扩展点护栏（基板⑦；机制在 sair.v4.ext.ExtRegistry） ----

    /**
     * 扩展点总开关（默认 {@link #DEF_EXT_ENABLED}）。
     * <p>每次用到时读 —— 改完即时生效（不用重启，也不用重载插件）。</p>
     */
    public boolean extEnabled() { return getBool("extEnabled", DEF_EXT_ENABLED); }

    /**
     * 每个扩展点调用的超时（毫秒，默认 {@link #DEF_EXT_TIMEOUT_MS}）。
     * <p>每次用到时读 —— 调大它能救一个"慢但有用"的插件，调小它能让快挂的插件少拖一点。</p>
     */
    public int extTimeoutMs() { return getInt("extTimeoutMs", DEF_EXT_TIMEOUT_MS); }

    /** 上下文 provider 块的全局字符预算（默认 {@link #DEF_CTX_BUDGET_CHARS}；{@code <=0} = 不限）。 */
    public int ctxBudgetChars() { return getInt("ctxBudgetChars", DEF_CTX_BUDGET_CHARS); }

    /** 同会话合并后的正文字符上限（默认 4000）。 */
    public int turnMergeMaxChars() { return getInt("turnMergeMaxChars", DEF_TURN_MERGE_CHARS); }

    /** 模型调用闸门许可数（默认 1 = 模型调用不并发）。 */
    public int modelConcurrency() { return Math.max(1, getInt("modelConcurrency", DEF_MODEL_CONCURRENCY)); }

    /** 等模型闸门的最长时间（毫秒，默认 180000）；等不到就放弃本次调用。 */
    public long modelGateWaitMs() { return getLong("modelGateWaitMs", DEF_MODEL_GATE_WAIT_MS); }

    /** 钩子执行池线程数（默认 4；{@code <=0} = 旧行为：调用线程直接跑）。 */
    public int hookWorkers() { return getInt("hookWorkers", DEF_HOOK_WORKERS); }

    /** 钩子执行池队列上限（默认 64；{@code <=0} = 旧行为）。 */
    public int hookQueueMax() { return getInt("hookQueueMax", DEF_HOOK_QUEUE_MAX); }

    /** NapCat 事件执行池线程数（默认 8；{@code <=0} = 旧行为：每事件一个裸线程）。 */
    public int eventWorkers() { return getInt("eventWorkers", DEF_EVENT_WORKERS); }

    /** NapCat 事件执行池队列上限（默认 512；{@code <=0} = 旧行为）。 */
    public int eventQueueMax() { return getInt("eventQueueMax", DEF_EVENT_QUEUE_MAX); }

    /**
     * 技能编译是否放到后台线程（默认 {@link #DEF_SKILLS_ASYNC} = false）。
     * <p>装配步骤⑯「技能扫描」是启动最慢的一步（实测 26 个技能 ≈ 2.6 秒）。开着它基板先用
     * 15 个基板工具可用，技能在后台编完逐个注册；关着就是旧口径（第⑯步一次编完才算就绪）。</p>
     */
    public boolean skillsAsync() { return getBool("skillsAsync", DEF_SKILLS_ASYNC); }

    // ---- 控制台捕获器（基板⑨；机制在 term.ConsoleTap） ----

    /**
     * 控制台捕获器开关（默认 {@link #DEF_CONSOLE_TAP_ENABLED}）。
     * <p>开着时基板注册一个 SFW 打印代理，把控制台每条输出收进环形缓冲（去色 + 时间戳 + 来源线程），
     * 于是：{@code /clear} 之后仍读得到最近输出；模型可以用 {@code console op=read} 只取"新增的那几条"。</p>
     */
    public boolean consoleTapEnabled() { return getBool("consoleTapEnabled", DEF_CONSOLE_TAP_ENABLED); }

    /** 捕获缓冲条数上限（默认 {@link #DEF_CONSOLE_TAP_ITEMS}；{@code <=0} = 用默认）。 */
    public int consoleTapItems() { return getInt("consoleTapItems", DEF_CONSOLE_TAP_ITEMS); }

    /** 捕获缓冲字符预算（默认 {@link #DEF_CONSOLE_TAP_CHARS}；{@code <=0} = 用默认）。 */
    public int consoleTapChars() { return getInt("consoleTapChars", DEF_CONSOLE_TAP_CHARS); }

    /** {@code console op=read} 默认返回长度（默认 {@link #DEF_CONSOLE_TAP_READ_CHARS}）。 */
    public int consoleTapReadChars() { return getInt("consoleTapReadChars", DEF_CONSOLE_TAP_READ_CHARS); }

    /** 打印代理转发档位（默认 {@link #DEF_CONSOLE_TAP_FORWARD}）。 */
    public String consoleTapForward() { return get("consoleTapForward", DEF_CONSOLE_TAP_FORWARD); }

    // ---- 本地交流面板（机制在 ui.TalkPanel；挂载在 Boot.ensureTalkPanel） ----
    //
    // 这两个键的**唯一读口就是下面两个 getter**：装配期建面板时读它们，
    // knownKeys() 也读同一个 getter —— 出厂默认值只在这一处写（取自 ui.TalkPanel 的常量），
    // 所以自省面报出来的值不可能与面板真正用的值不一致。

    /**
     * 本地交流面板的条数上限（默认 {@link sair.v4.ui.TalkPanel#DEF_MAX_ENTRIES}）。
     * <p>{@code <=0} 由 {@link sair.v4.ui.TalkPanel} 自己夹回默认值。</p>
     */
    public int talkPanelMaxEntries() {
        return getInt("talkPanelMaxEntries", sair.v4.ui.TalkPanel.DEF_MAX_ENTRIES);
    }

    /** 本地交流面板的字符预算（默认 {@link sair.v4.ui.TalkPanel#DEF_MAX_CHARS}）。 */
    public int talkPanelMaxChars() {
        return getInt("talkPanelMaxChars", sair.v4.ui.TalkPanel.DEF_MAX_CHARS);
    }

    // ---------------- 本机调试口 ----------------
    //
    // 【口径变更】这里原来有四个读口：debugPort() / debugToken() / debugSimPort() / debugSimulate()。
    // 四个键已从配置面整条撤出（不再进 knownKeys() / ensureDefaults() / config.json），
    // 端口与 token 由控制台命令 `ai/debug on|off|status` 直接交给 dev.DebugPort / dev.DebugSimPort，
    // 所以本类不再有它们的 getter —— 装配也不再自动起调试口。
    // 出厂端口/仿真默认值仍在 DEF_DEBUG_* 里（命令没给参数时用）。

    /**
     * 详细日志开关（默认 {@link #DEF_LOG_VERBOSE} = 只打结构，不打正文）。
     * <p>口径：<b>消息正文/回复正文/工具结果正文只在它为 true 时进控制台</b>；
     * 结构性事实行（{@code [qq]} 路由与判定、{@code [lane]} 调度决策、{@code [turn]} 回合摘要、
     * {@code [tool]} 调用摘要）默认就在，用来回答"为什么走这条路"。</p>
     */
    public boolean logVerbose() { return getBool("logVerbose", DEF_LOG_VERBOSE); }

    /**
     * 控制台默认打哪几类行（配置 {@code logConsole}，默认 {@link #DEF_LOG_CONSOLE}）。
     *
     * <p>主人的口径：<b>控制台只留"Agent 调用事件"</b> —— 哪个 Agent 调了哪个工具、走的 flash 还是 pro、
     * 缓存命中多少、用时多少。所以默认只开 {@code tool,model} 两类；每一条 QQ 消息、
     * 每一次调度决策、每轮摘要这些"过程细节"默认<b>不打</b>（要看得加类别，或者直接 {@code logVerbose=true} 全开）。</p>
     *
     * <p>类别（逗号分隔，可多选）：</p>
     * <ul>
     *   <li>{@code tool} —— 工具调用事件（{@code [tool] 名 ms= ok= by=…}）与权限阻断；</li>
     *   <li>{@code model} —— 模型调用事件（{@code [ai] … model= cache= ms= …}）；</li>
     *   <li>{@code msg} —— 入站消息/事件的结构行（{@code [qq] msg/skip/→ lane}、{@code [NapCat] 事件}）；</li>
     *   <li>{@code lane} —— 调度决策行（{@code [lane] decision=…}）；</li>
     *   <li>{@code turn} —— 回合摘要行（{@code [turn] rounds= tools= ms= ok=}）；</li>
     *   <li>{@code skill} —— 技能自己的过程行（{@code [技能名] …}，走 {@code Host.out()}）；</li>
     *   <li>{@code ext} —— 插件扩展点的过程行（{@code [ext] provider=… chars=… ms=…}，
     *       以及 stage / voter / hook 的同形行；见 {@code sair.v4.ext.ExtRegistry}）；</li>
     *   <li>{@code net} —— 外链中转行（{@code [relay] …}）。</li>
     * </ul>
     *
     * <p><b>不受本开关影响的</b>：错误（err）、警告（warn）、装配进度（{@code [v4] x/19 …}）、
     * 权限阻断、投递降级、以及各命令的回话 —— 这些不是"过程噪声"，该看见的必须看见。</p>
     */
    public boolean logOn(String cat) {
        if (logVerbose()) return true;                      // 排障总开关：全开
        if (Str.blank(cat)) return false;
        String s = get("logConsole", DEF_LOG_CONSOLE);
        if (s == null) return false;
        for (String p : s.split("[,，;\\s]+")) {
            if (cat.equalsIgnoreCase(Str.trim(p))) return true;
        }
        return false;
    }

    // ---- 保留期（生命周期维护；机制在 Store.maintain / Tick.maybeMaintain） ----

    /**
     * 记忆/笔记类的通用保留天数（老键，向后兼容）。
     * <p><b>只管</b> {@code memory} 的超龄低重要度行（{@code importance <= Store.LOW_IMPORTANCE}）
     * 与运行台账（停用闹钟、已完成/中断任务）；{@code note} 从不被维护删除。
     * 聊天记录有各自独立的尺子：{@link #dialogKeepDays()} / {@link #grouplogKeepDays()}。
     * {@code <=0} 沿用 {@code Store.DEF_KEEP_DAYS}（30）的老口径。</p>
     */
    public int keepDays() { return getInt("keepDays", DEF_KEEP_DAYS); }

    /**
     * 对话历史（{@code dialog}）保留天数，默认 {@link #DEF_DIALOG_KEEP_DAYS}。
     * <p><b>{@code <=0} = 不过期</b>（明确不删，而不是"用默认值"）—— 保留期是本项目里
     * 唯一会删数据的旋钮，配错就不可再生，所以 0 的含义必须偏向"不删"。</p>
     */
    public int dialogKeepDays() { return getInt("dialogKeepDays", DEF_DIALOG_KEEP_DAYS); }

    /** 群聊历史（{@code grouplog}）保留天数，默认 {@link #DEF_GROUPLOG_KEEP_DAYS}；{@code <=0} = 不过期。 */
    public int grouplogKeepDays() { return getInt("grouplogKeepDays", DEF_GROUPLOG_KEEP_DAYS); }

    /**
     * 不建全文索引的库（配置键 {@code ftsOff}，可给数组或逗号分隔串）。
     * <p>返回配置里写的名字原样（小写、去重、去空），<b>不做别名解析</b>；"群聊"这种中文别名
     * 由 {@link sair.v4.store.Db#ftsSkip(String)} 落成表名 {@code grouplog}。
     * 默认空 = 所有有正文的库都建 FTS5。关掉某个库的索引后，该库的检索只走
     * {@code LIKE %} 回退（还能用，但全表扫描、且中文多词查询不再有"拆词 OR"这一层）。
     * 尺寸收益很大（{@code grouplog} 的 trigram 索引通常比表本身还大一倍），代价是检索变慢。
     * 改这个键只影响<b>之后新建</b>的索引；已经建好的索引要么留着用，要么用
     * {@code Store.dropFts(lib)} 显式删掉。</p>
     */
    public List<String> ftsOffLibs() {
        List<String> out = new ArrayList<String>();
        JsonElement e = J.get(data, "ftsOff");
        if (e == null) return out;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x != null && x.isJsonPrimitive()) addLib(out, x.getAsString());
            }
            return out;
        }
        String s = J.s(data, "ftsOff", "");
        for (String part : s.split("[,，;；\\s]+")) addLib(out, part);
        return out;
    }

    private static void addLib(List<String> out, String name) {
        if (name == null) return;
        String v = name.trim().toLowerCase();
        if (!v.isEmpty() && !out.contains(v)) out.add(v);
    }

    public String identityRel() { return get("identityFile", "prompts/identity.md"); }

    /**
     * 身份提示词（{@link #identityRel()}）的<b>字符预算</b>（默认
     * {@link #DEF_IDENTITY_MAX_CHARS} = 8000）。
     *
     * <p><b>只 warn，不截断</b>：超预算时 {@code prompt.PromptFile} 只打一行
     * {@code [prompt] identity.md 已 N/M 字符}，正文原样全量加载 —— 身份是她的根，宁可长也不许丢。
     * 每回合热读（改完不用重启）。</p>
     */
    public int identityMaxChars() { return getInt("identityMaxChars", DEF_IDENTITY_MAX_CHARS); }

    /** 子 Agent 的**公共部分**提示词（外挂，可自定义；缺文件=空，不兜底）。 */
    public String subagentRel() { return get("subagentFile", "prompts/subagent.md"); }

    public File subagentFile() { return path(subagentRel()); }

    public String skillsRel() { return get("skillsDir", "skills"); }

    /** 草稿区（自生成技能的"先写后提"落点）：<b>不参与扫描、不挂监听</b>，只有 promote 才进 skills/。 */
    public String draftsRel() { return get("draftsDir", "skills-draft"); }

    // P9b-2 删除：permDefaultBuiltin() / permDefaultSkill()（旧档位表"缺行默认档"的读口，随 PermTable 一起走）

    // ---- 路径 ----
    public File skillsDir() { return path(skillsRel()); }

    /** 草稿区目录（与 skills/ 同级：<b>基板不扫描、不监听</b>，草稿不会变成工具）。 */
    public File draftsDir() { return path(draftsRel()); }

    public File promptsDir() { return path("prompts"); }

    public File identityFile() { return path(identityRel()); }

    public File filesDir() { return path("files"); }

    public File cacheDir() { return path("cache"); }

    public File tmpDir() { return path("tmp"); }

    public File dbFile() { return path("v4.db"); }

    /**
     * <b>一个已知配置键的自省条目</b>（B10；{@code config} 工具的 {@code op=list/get} 读它）。
     * <p>它是"值从哪来"的<b>唯一出口</b>：{@link Key#value()} 一律由 {@link Conf} 自己的 getter
     * 算出来（缺省值也算生效值），所以 {@code config op=list} 与判定面看到的永远是同一个值，
     * 不会出现"表里写着 30、实际跑 12"那种骗人的清单。</p>
     *
     * <p>{@link #fileKeys()} 造出来的条目规则相同，只是值取自 <b>{@code config.json}</b>，
     * 并且多带一位 {@link #pending()}（文件里的值与已生效的值不一样 = 待生效）。</p>
     */
    public static final class Key {
        private final String name;
        private final String value;
        private final boolean sensitive;
        /** 文件里的值与已生效的值不一样（= 待生效，要 {@code ai/start}）。 */
        private final boolean pending;

        Key(String name, String value, boolean sensitive) {
            this(name, value, sensitive, false);
        }

        Key(String name, String value, boolean sensitive, boolean pending) {
            this.name = name;
            this.value = value == null ? "" : value;
            this.sensitive = sensitive;
            this.pending = pending;
        }

        public String name() { return name; }

        /** 值原文（敏感键也是原文；<b>要不要打码由调用方按 {@link #sensitive()} 决定</b>）。 */
        public String value() { return value; }

        /** 敏感键（{@link Conf#isSensitive(String)}）：值不许外露。 */
        public boolean sensitive() { return sensitive; }

        /** {@code true} = 文件里的值与已生效的值不一样 —— <b>待生效</b>（要 {@code ai/start}）。 */
        public boolean pending() { return pending; }

        /** 值非空/非零 = "已设置"（自省面判断敏感键要不要说"已设置"）。 */
        public boolean set() { return !value.trim().isEmpty(); }

        /** 值长（自省面回"长度"而绝不回值）。 */
        public int length() { return value.length(); }
    }

    /**
     * <b>全部已知配置键</b>（顺序 = 这里的书写顺序，按面分组）。
     *
     * <h3>为什么要有这张表</h3>
     * <p>主人 2026-09-16 要的那条工作流是「知道该用什么工具 → 不知道就查 → 读说明书 → 验证行不行」，
     * 落到配置上就是"<b>改哪儿必须可查</b>"：以前只有 {@code term.Cmd} 的 {@code config} 控制台命令
     * 能看配置，模型侧只能靠背键名。这张表把"<b>键名 + 值 + 待不待生效</b>"三样一次给全。</p>
     *
     * <h3>两条硬口径</h3>
     * <ol>
     *   <li><b>含"只靠默认值生效"的键</b>：{@code config.json} 里没写、靠出厂默认跑起来的键
     *       （例 {@code agentMaxRounds=30} / {@code subagentMaxRounds=40} / {@code historyLimit}）也在表里，
     *       值就是"出厂默认照原样生效"的那个值 —— 缺省值一律从 {@link Conf} 自己的 getter 来，
     *       这里<b>不重抄一份常数</b>；</li>
     *   <li><b>只登记"有人读"的键</b>：每个键都有活着的消费者（getter 就是那个消费者的同一个口），
     *       表里不收 {@code permissionMatrix} 那类已退休的键（它们写进去也没人读，
     *       {@code Boot.warnRetiredPermKeys()} 会点它们的名）。<b>本机调试口的四个键
     *       （debugPort / debugToken / debugSimPort / debugSimulate）已整条撤出</b> ——
     *       它们不再是配置，起停只看 {@code ai/debug on|off|status}；</li>
     *   <li>{@link #knownKeys()} 给的是<b>已生效</b>的值，{@link #fileKeys()} 给的是<b>文件</b>里的值
     *       （文件里没写才回落到生效/出厂默认），后者多一位 {@link Key#pending()}。</li>
     * </ol>
     */
    public List<Key> knownKeys() {
        List<Key> out = new ArrayList<Key>();
        // ---- 模型与对话 ----
        add(out, "apiKey", apiKey());
        add(out, "apiUrl", apiUrl());
        add(out, "model", model());
        add(out, "autoModel", autoModel());
        add(out, "visionModel", visionModel());
        add(out, "temperature", String.valueOf(temperature()));
        add(out, "topP", String.valueOf(topP()));
        add(out, "maxTokens", String.valueOf(maxTokens()));
        add(out, "historyLimit", String.valueOf(getInt("historyLimit", DEF_HISTORY_LIMIT)));
        add(out, "historyMaxChars", String.valueOf(getInt("historyMaxChars", DEF_HISTORY_MAX_CHARS)));
        add(out, "selfId", String.valueOf(selfId()));
        add(out, "masterQQ", String.valueOf(masterQQ()));
        add(out, "masterQQs", get("masterQQs", ""));
        // ---- 回复与回合 ----
        add(out, "chatTimeoutMs", String.valueOf(chatTimeoutMs()));
        add(out, "turnMergeMaxChars", String.valueOf(turnMergeMaxChars()));
        add(out, "replySplit", String.valueOf(getBool("replySplit", true)));
        add(out, "replyMaxChars", String.valueOf(getInt("replyMaxChars", sair.v4.term.Segmenter.DEF_MAX_CHARS)));
        add(out, "replyGroupMaxChars",
                String.valueOf(getInt("replyGroupMaxChars", sair.v4.term.Segmenter.DEF_GROUP_MAX_CHARS)));
        add(out, "replySplitDelayMs", String.valueOf(getInt("replySplitDelayMs", sair.v4.term.Segmenter.DEF_DELAY_MS)));
        add(out, "replySplitJitterMs",
                String.valueOf(getInt("replySplitJitterMs", sair.v4.term.Segmenter.DEF_JITTER_MS)));
        add(out, "msgDedupWindowSec", String.valueOf(getInt("msgDedupWindowSec", sair.v4.qq.Seen.DEF_WINDOW_SEC)));
        // ---- Agent 与技能 ----
        add(out, "agentMaxRounds", String.valueOf(agentMaxRounds()));
        add(out, "subagentMaxRounds", String.valueOf(subagentMaxRounds()));
        add(out, "subAgentMax", String.valueOf(subAgentMax()));
        add(out, "toolResultMax", String.valueOf(toolResultMax()));
        add(out, "identityFile", identityRel());
        add(out, "identityMaxChars", String.valueOf(identityMaxChars()));
        add(out, "subagentFile", subagentRel());
        add(out, "skillsDir", skillsRel());
        add(out, "draftsDir", draftsRel());
        add(out, "skillsAsync", String.valueOf(skillsAsync()));
        // ---- 调度（池 / 闸门 / 优先级） ----
        add(out, "turnWorkers", String.valueOf(turnWorkers()));
        add(out, "turnQueueMax", String.valueOf(turnQueueMax()));
        add(out, "turnLaneMax", String.valueOf(turnLaneMax()));
        add(out, "turnOverflow", turnOverflow());
        add(out, "turnDropNotice", String.valueOf(turnDropNotice()));
        add(out, "turnQueuedNotice", String.valueOf(turnQueuedNotice()));
        add(out, "turnPriority", turnPriority());
        add(out, "turnPriorityBurst", String.valueOf(turnPriorityBurst()));
        add(out, "turnPriorityAgingMs", String.valueOf(turnPriorityAgingMs()));
        add(out, "modelConcurrency", String.valueOf(modelConcurrency()));
        add(out, "modelGateWaitMs", String.valueOf(modelGateWaitMs()));
        add(out, "hookWorkers", String.valueOf(hookWorkers()));
        add(out, "hookQueueMax", String.valueOf(hookQueueMax()));
        add(out, "hookOverflow", hookOverflow());
        add(out, "eventWorkers", String.valueOf(eventWorkers()));
        add(out, "eventQueueMax", String.valueOf(eventQueueMax()));
        add(out, "tickMs", String.valueOf(tickMs()));
        // ---- 上下文注入 ----
        add(out, "prefInject", String.valueOf(prefInject()));
        add(out, "prefInjectMaxRows", String.valueOf(prefInjectMaxRows()));
        add(out, "prefInjectMaxChars", String.valueOf(prefInjectMaxChars()));
        add(out, "extEnabled", String.valueOf(extEnabled()));
        add(out, "extTimeoutMs", String.valueOf(extTimeoutMs()));
        add(out, "ctxBudgetChars", String.valueOf(ctxBudgetChars()));
        // 本会话最近 N 条聊天记录（动态窗口）：与偏好/扩展点同属"往上下文里塞什么"这一类
        add(out, "chatWindowSize", String.valueOf(chatWindowSize()));
        // M4：引用解析与媒体面（12 个键；**不进 ensureDefaults()**，与 chatWindowSize 同一类）
        add(out, "mediaRender", String.valueOf(mediaRender()));
        add(out, "mediaRenderMaxChars", String.valueOf(mediaRenderMaxChars()));
        add(out, "mediaVision", mediaVision());
        add(out, "visionModels", visionModels());
        add(out, "mediaAttachMax", String.valueOf(mediaAttachMax()));
        add(out, "mediaAttachMaxBytes", String.valueOf(mediaAttachMaxBytes()));
        add(out, "mediaExpandForward", String.valueOf(mediaExpandForward()));
        add(out, "mediaExpandMaxNodes", String.valueOf(mediaExpandMaxNodes()));
        add(out, "mediaExpandMaxDepth", String.valueOf(mediaExpandMaxDepth()));
        add(out, "mediaExpandMaxChars", String.valueOf(mediaExpandMaxChars()));
        add(out, "mediaExpandQuote", String.valueOf(mediaExpandQuote()));
        add(out, "mediaCacheHours", String.valueOf(mediaCacheHours()));
        // ---- 维护与保留期 ----
        add(out, "keepDays", String.valueOf(keepDays()));
        add(out, "dialogKeepDays", String.valueOf(dialogKeepDays()));
        add(out, "grouplogKeepDays", String.valueOf(grouplogKeepDays()));
        add(out, "maxLowImportance", String.valueOf(maxLowImportance()));
        add(out, "maintainEveryHours", String.valueOf(maintainEveryHours()));
        add(out, "ftsOff", Str.join(ftsOffLibs(), ","));
        // ---- 控制台捕获器 ----
        add(out, "consoleTapEnabled", String.valueOf(consoleTapEnabled()));
        add(out, "consoleTapItems", String.valueOf(consoleTapItems()));
        add(out, "consoleTapChars", String.valueOf(consoleTapChars()));
        add(out, "consoleTapReadChars", String.valueOf(consoleTapReadChars()));
        add(out, "consoleTapForward", consoleTapForward());
        // 本地交流面板的两个上限：与捕获器同形（条数 + 字符预算），消费者只有 Boot 建面板那处
        add(out, "talkPanelMaxEntries", String.valueOf(talkPanelMaxEntries()));
        add(out, "talkPanelMaxChars", String.valueOf(talkPanelMaxChars()));
        // ---- 日志 ----
        add(out, "logQq", String.valueOf(logQq()));
        add(out, "logTools", String.valueOf(logTools()));
        add(out, "logConsole", get("logConsole", DEF_LOG_CONSOLE));
        add(out, "logVerbose", String.valueOf(logVerbose()));
        // ---- NapCat 与文件外链 ----
        add(out, "napcatEnabled", String.valueOf(napcatEnabled()));
        add(out, "napcatPort", String.valueOf(napcatPort()));
        add(out, "napcatHost", napcatHost());
        add(out, "napcatToken", napcatToken());
        add(out, "napcatTimeoutMs", String.valueOf(napcatTimeoutMs()));
        add(out, "relayEnabled", String.valueOf(relayEnabled()));
        add(out, "relayPort", String.valueOf(relayPort()));
        add(out, "relayPublicHost", relayPublicHost());
        add(out, "relayToken", relayToken());
        add(out, "relayTtlMinutes", String.valueOf(relayTtlMinutes()));
        // ---- 本机调试口：**已整条撤出**（不再是配置键）----
        // debugPort / debugToken / debugSimPort / debugSimulate 四个键从本表、从 ensureDefaults()、
        // 从 config.json 里全部拿掉：两个口的起停只由 `ai/debug on|off|status` 说了算。
        // 撤掉它们的同一批里也把"改了要重启"整张表退休了（见类头「改配置 vs 生效」）。
        return out;
    }

    private static void add(List<Key> out, String name, String value) {
        out.add(new Key(name, value, isSensitive(name)));
    }

    /**
     * <b>文件面</b>的已知键清单（{@code config op=list} 用它）：值取自 {@code config.json}。
     *
     * <p>规则：已知键一个不少（含只靠出厂默认生效的键）；<b>文件里写了就用文件里的值</b>，
     * 没写才回落到"已生效/出厂默认"的那个值。文件里的值与已生效值不一样时
     * {@link Key#pending()} 为真 = <b>待生效</b>。</p>
     */
    public List<Key> fileKeys() {
        Conf f = fileView();
        List<Key> out = new ArrayList<Key>();
        for (Key k : knownKeys()) {
            String fv = f.get(k.name(), null);
            String v = fv == null ? k.value() : fv;
            out.add(new Key(k.name(), v, k.sensitive(), fv != null && !fv.equals(k.value())));
        }
        return out;
    }

    /** 文件面的单个键（没有这个已知键就 {@code null}）。 */
    public Key fileKey(String name) {
        if (name == null) return null;
        String k = name.trim();
        for (Key key : fileKeys()) if (key.name().equals(k)) return key;
        return null;
    }

    /** 按名字取一个已知键（没有就 {@code null}；{@code op=get} 用它）。 */
    public Key knownKey(String name) {
        if (name == null) return null;
        String k = name.trim();
        for (Key key : knownKeys()) if (key.name().equals(k)) return key;
        return null;
    }

    /** 首次运行写入带注释的默认配置。 */
    public void ensureDefaults() {
        if (!file.isFile() || !file.exists() || Fs.size(file) == 0) {
            set("apiKey", "");
            set("apiUrl", DEF_API_URL);
            set("model", DEF_MODEL);
            set("autoModel", DEF_AUTO_MODEL);
            set("temperature", DEF_TEMPERATURE);
            set("historyLimit", DEF_HISTORY_LIMIT);
            set("historyMaxChars", DEF_HISTORY_MAX_CHARS);
            set("napcatEnabled", false);
            set("napcatPort", DEF_NAPCAT_PORT);
            set("napcatToken", "");
            set("relayEnabled", false);
            set("relayPort", DEF_RELAY_PORT);
            set("relayPublicHost", "");
            set("relayToken", "");
            set("relayTtlMinutes", 0);
            set("selfId", 0);
            set("masterQQ", 0);
            // 日志口径：控制台默认只留 Agent 调用事件（tool + model）；过程细节要显式加类别
            set("logQq", true);
            set("logConsole", DEF_LOG_CONSOLE);
            set("logVerbose", DEF_LOG_VERBOSE);
            // 入站去重（V3 口径）：同一条 message_id 在窗口内只处理一次（重连重放不再重复回答）
            set("msgDedupWindowSec", sair.v4.qq.Seen.DEF_WINDOW_SEC);
            // 回复分段（V3 口径）：太长就按段落拆成几条发；群聊再压短一档；条间停一下像人
            set("replySplit", true);
            set("replyMaxChars", sair.v4.term.Segmenter.DEF_MAX_CHARS);
            set("replyGroupMaxChars", sair.v4.term.Segmenter.DEF_GROUP_MAX_CHARS);
            set("replySplitDelayMs", sair.v4.term.Segmenter.DEF_DELAY_MS);
            set("replySplitJitterMs", sair.v4.term.Segmenter.DEF_JITTER_MS);
            set("keepDays", DEF_KEEP_DAYS);
            set("grouplogKeepDays", DEF_GROUPLOG_KEEP_DAYS);
            set("dialogKeepDays", DEF_DIALOG_KEEP_DAYS);
            set("maxLowImportance", 500);
            set("maintainEveryHours", 24);
            // 调度（高并发）：按会话分道 + 溢出不静默丢弃 + 闸门/钩子/事件池上限
            set("turnWorkers", DEF_TURN_WORKERS);
            set("turnQueueMax", DEF_TURN_QUEUE_MAX);
            set("turnLaneMax", DEF_TURN_LANE_MAX);
            set("turnOverflow", "coalesce");
            set("turnDropNotice", true);
            set("turnQueuedNotice", false);
            set("turnPriority", "master_first");
            set("turnPriorityBurst", DEF_TURN_PRIORITY_BURST);
            set("turnPriorityAgingMs", DEF_TURN_PRIORITY_AGING_MS);
            // 偏好块注入（三层合并进系统提示词的稳定段）：开关 + 条数/字符两把尺子
            set("prefInject", DEF_PREF_INJECT);
            set("prefInjectMaxRows", DEF_PREF_INJECT_MAX_ROWS);
            set("prefInjectMaxChars", DEF_PREF_INJECT_MAX_CHARS);
            // 插件扩展点的护栏（基板⑦）：总开关 + 单次调用超时 + 上下文块的全局字符预算
            set("extEnabled", DEF_EXT_ENABLED);
            set("extTimeoutMs", DEF_EXT_TIMEOUT_MS);
            set("ctxBudgetChars", DEF_CTX_BUDGET_CHARS);
            set("hookOverflow", "caller");
            set("turnMergeMaxChars", DEF_TURN_MERGE_CHARS);
            set("modelConcurrency", DEF_MODEL_CONCURRENCY);
            set("modelGateWaitMs", DEF_MODEL_GATE_WAIT_MS);
            set("hookWorkers", DEF_HOOK_WORKERS);
            set("hookQueueMax", DEF_HOOK_QUEUE_MAX);
            set("eventWorkers", DEF_EVENT_WORKERS);
            set("eventQueueMax", DEF_EVENT_QUEUE_MAX);
            // 技能编译是否放后台（false = 启动第⑯步同步编完才算就绪）
            set("skillsAsync", DEF_SKILLS_ASYNC);
            // 控制台 chat 等待回合结束的上限（毫秒）：超时就回话，绝不把控制台永久卡住
            set("chatTimeoutMs", 180000);
            // 控制台捕获器（基板⑨ 的「读」半边）：上限/开关/转发档位都是「每次用到时读」，改完即时生效
            set("consoleTapEnabled", DEF_CONSOLE_TAP_ENABLED);
            set("consoleTapItems", DEF_CONSOLE_TAP_ITEMS);
            set("consoleTapChars", DEF_CONSOLE_TAP_CHARS);
            set("consoleTapReadChars", DEF_CONSOLE_TAP_READ_CHARS);
            set("consoleTapForward", DEF_CONSOLE_TAP_FORWARD);
            // 本机调试口（dev.DebugPort 输出口 2660 / dev.DebugSimPort 输入口 2661）：
            // 【口径变更】这四个键**不再写进 config.json**（也不在 knownKeys() 里）——
            // 两个口的起停只由 `ai/debug on|off|status` 说了算，端口与 token 走命令参数，
            // 运行现场凭据写在 <数据根>/debug-port.json 里（那才是客户端读的那份）。
            save();
        }
    }
}
