package sair.v4.ctx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.Conf;
import sair.v4.auth.Acl;
import sair.v4.auth.Bits;
import sair.v4.auth.Caller;
import sair.v4.auth.Res;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.prompt.Inject;
import sair.v4.prompt.Prompts;
import sair.v4.store.Store;
import sair.v4.tool.ToolIndex;

/**
 * 上下文装配（基板⑤）：把"系统提示词 + 动态事实 + 历史 + 本轮输入"拼成 messages。
 * <p>基板只产<b>事实</b>（键值形式），不产文案：怎么理解这些事实由插件层的提示词负责。
 * 任何插件都可以通过 {@link Inject} 往 {@code context}/{@code system}/{@code arg} 槽位追加内容。</p>
 *
 * <p>唯一的例外是<b>偏好块</b>（{@link #stable}）：六库 {@code pref} 里这个调用者可见的三层偏好
 * 合并成"标题 + {@code - key: value}"注入<b>稳定段</b>（系统提示词那条 system）。
 * 它不是事实而是"必须遵守的设定"，且每轮都要在 —— 只靠模型主动调 {@code pref} 工具，
 * 等于"设了没人看"。三行文案外挂 {@code prompts/tools-index.md}，缺键用内置默认值。</p>
 *
 * <p><b>装配骨架（终态）</b>：{@code system(identity + 偏好) → 稳定段的外挂块 → 基板内置事实块 →
 * 易变段的外挂块 → 历史 → 本轮 user}。外挂块来自 {@link sair.v4.ext.ContextProvider}（基板⑦），
 * 内容与文案全在插件里；基板只管<b>顺序、段位、预算</b>。今天内置事实块还在基板手上（S3 才搬走），
 * 它走的是上面标出来的那一个"内置 provider 的位置"，搬走时删那一段即可。</p>
 */
public final class CtxBuild {

    /**
     * {@link Turn#state()} 里的键：本轮的事实行（{@code media: [...]}，由入口侧写入）。
     * <p>媒体段元数据是<b>事实</b>，所以它和 {@code caller}/{@code napcat}/{@code models} 一样
     * 进事实块（同一个 system），而不是被编成一句自然语言。</p>
     */
    public static final String ST_MEDIA = "media";

    /**
     * {@link Turn#state()} 里的键：本轮的<b>本会话最近 N 条聊天记录</b>（
     * {@code chat_window: {…}} 一行 + 「【本会话最近 N 条聊天记录】」标签行 + 一行一条记录，
     * 由 {@code sair.v4.ctx.ChatWindow} 在<b>这一轮开始之前现查一次</b>做好，
     * 大小 = 配置 {@code chatWindowSize}，默认 30，私聊与群一视同仁）。
     *
     * <p>它替掉的是原来的<b>会话快照</b>（{@code snapshot: …} + {@code snapshot_msgs: […]}
     * 两行，游标机制）—— 主人 2026-09-16 取消自动快照、改成"每触发一次就现取一个窗口"。
     * 和媒体事实一样是<b>事实</b>，所以进事实块（system），不进用户轮；而且它是事实块的<b>最后一段</b>
     * （紧挨着后面的对话历史消息）。</p>
     *
     * <p><b>谁写它</b>：{@code Agent.ask}（主回合：QQ 触发 / 控制台 / 定时投递）与
     * {@code Agent.execSub}（派出去的子 Agent —— 触发词接话就是这条路，
     * 子 Agent 不带主会话历史，窗口是它唯一能看见"刚才群里/私聊里说了什么"的地方）。
     * 收尾回合（{@code assembleSystem}）不带：那一轮是内部记账，不是"在会话里说话"。</p>
     */
    public static final String ST_CHAT_WINDOW = "chat_window";

    /**
     * {@link Turn#state()} 里的键：<b>这一轮到底是谁在跑</b>（P0-3 S1）。
     *
     * <p>为什么需要它：事实块里的 {@code models} 一行原先只有一个模型 —— {@code Conf.resolveModel()}
     * （= 主 Agent 的模型）。可是<b>子 Agent 可以带自己的模型</b>（{@code agent} 工具的 {@code model} 参数，
     * 被派去"看图"的 flash 子 Agent 就是这么来的）。不区分的话，那个子 Agent 在自己的事实块里读到的是
     * {@code main_has_vision:false} ⇒ <b>它会以为自己看不了图，从而拒绝看图</b> —— 派它来的意义当场消失。
     *
     * <p><b>单一来源</b>：这个键只有两个写法（都是 {@code put}，幂等）——
     * {@code Agent.execSub}（派活时，在 {@code ctx.facts(sub)} <b>之前</b>）与
     * {@code Loop.setModel}（顺手也写同一个源）。读取只有一处：{@link #facts}。
     * 缺键（主回合、探针裸 Turn）⇒ {@code Conf.resolveModel()}，与改之前逐字节相同。
     */
    public static final String MODELS_SELF = "models_self";

    /**
     * {@link Turn#state()} 里的键：本轮的<b>挂图结果事实行</b>（{@code attach: {…}}，含前缀）。
     * <p>由 {@link #user} 写（它是"这一轮 user 轮"的产出者，只有它知道挂没挂上），
     * 由 {@code Loop.run} 在<b>第 1 次请求返回之后</b>读出来追加进 messages —— 见 {@link #user} 的注释。
     */
    public static final String ST_ATTACH = "attach";

    /** 基板需要知道的外部状态（由 Boot 提供，避免 ctx 反向依赖 qq/agent 包）。 */
    public interface Env {
        boolean napcatConnected();
        int tasksRunning();
        /** 附加事实行（可空）；插件层可用它补自己关心的事实。 */
        String extraFacts();
        /**
         * 一行能力索引（{@code tools: {"类别":[...]}}，可空）。
         * <p>纯事实：类别词表外挂 {@code prompts/tools-index.md}，工具与取值取自<b>这个调用者可见的工具表</b>；
         * 配置缺失即返回 null（事实块里就没有这一行，基板不编兜底文案）。</p>
         *
         * @param t 这一轮（{@code null} = 诊断路径）：<b>子 Agent 的索引只给名字</b>（省 token），
         *          判据是 {@code t.isSubagent()}（B10）
         */
        String toolIndex(Turn t);

        /**
         * 事实块那一行 {@code rounds: {...}}（B10；<b>可空</b> —— 缺省 {@code null} = 不产出这一行）。
         *
         * <p>为什么不在这里自己算：上限的算法（主 {@code agentMaxRounds} / 子 {@code subagentMaxRounds}、
         * 判据 {@code Turn.isSubagent()}）在 {@code agent.Loop}，而 {@code ctx} 包<b>刻意不反向依赖
         * agent 包</b>。所以由装配方（{@code Boot}）把它接给 {@code Loop} 的同一个静态函数 ——
         * 事实块里的值与 {@code Loop} 真正用的上限<b>同源</b>，不可能对不上。</p>
         *
         * <p>装配方没接（探针直接 new 一个 CtxBuild）= 事实块里没有这一行，基板不编。</p>
         */
        default String roundsFact(Turn t) { return null; }

        /**
         * <b>权限面</b>（可空）：事实块里的 {@code caller.kind} / {@code caller.bits} 要问
         * "这个人对五类资源各有什么位"，那只能由权限账本（{@code perms.json}）回答。
         *
         * <p>装配方接了这一口（{@code Boot} 的 {@code auth()}）就用它的账本 —— 与授权 op 操作的是
         * <b>同一份内存状态</b>，主人刚写完例外，下一轮事实块立刻是对的。
         * 默认返回 {@code null} = 没接：{@link CtxBuild} 自己按数据根读一份<b>只读</b>账本
         * （同一个文件、同一套 {@code Acl.bitsOf}，文件变了自动重读），
         * 所以探针那种"直接 new 一个 CtxBuild"的场合也能拿到真位。</p>
         */
        default sair.v4.auth.Auth auth() { return null; }
    }

    /**
     * 偏好块的内置默认文案（{@code prompts/tools-index.md} 的同名键缺失时用它）。
     * <p>这里<b>刻意</b>不跟其余文案键一个口径（"缺键就不发"）：偏好进上下文就是功能本身，
     * 静默不注入 = 功能消失。缺键只 warn 一次，块照旧渲染。</p>
     */
    static final String DEF_PREF_TITLE = "偏好/设定（必须遵守）";
    static final String DEF_PREF_ROW = "- {key}: {value}";
    static final String DEF_PREF_CUT = "…（还有 {rest} 条未列）";

    /** 作用域层数：一个 key 最多在 {@code user}/{@code group}/{@code global} 各有一行（取数上限 = 条数预算 × 它）。 */
    private static final int PREF_LAYERS = 3;

    /** 不按条数截断（{@code prefInjectMaxRows<=0}）时的取数硬上限。 */
    private static final int PREF_FETCH_MAX = 300;

    private final Conf conf;
    private final Prompts prompts;
    private final Store store;
    private final Env env;
    /** 只用于"偏好块缺键"的一次性 warn；可空（没有控制台的场合就少一行日志）。 */
    private final Out out;
    /** 偏好块三行文案的外挂来源（同 {@code prompts/tools-index.md}，mtime 变了自动重读）。 */
    private final ToolIndex texts;
    /** 插件上下文提供者（基板⑦；装配期由 Boot 注入，没注入 = 零外挂块）。 */
    private volatile sair.v4.ext.ExtRegistry ext;
    /**
     * 识图组件的在面/计数状态（T12-R3；装配期由 Boot 注入 —— <b>与 {@code agent.Loop} 计数用的是同一份</b>）。
     * <p>默认实例 = 恒 {@code off} / 0 ⇒ <b>没注入的场合（例如探针只 new 一个 CtxBuild）
     * 事实块与加这一层之前逐字节相同</b>。非空由构造初始化保证，永不为 null。</p>
     */
    private volatile RecognizerState recognizer = new RecognizerState();
    /** 缺键只 warn 一次（每轮都拼偏好块，不能每轮都刷一行）。 */
    private volatile boolean prefKeysWarned = false;

    public CtxBuild(Conf conf, Prompts prompts, Store store, Env env) {
        this(conf, prompts, store, env, null);
    }

    public CtxBuild(Conf conf, Prompts prompts, Store store, Env env, Out out) {
        this.conf = conf;
        this.prompts = prompts;
        this.store = store;
        this.env = env;
        this.out = out;
        this.texts = ToolIndex.of(conf);
    }

    /**
     * 注入插件上下文提供者（基板⑦；装配期调一次，改到一半也没关系 —— 读到的是最新注册表）。
     * <p>不注入 / 一个 provider 都没挂 = {@link #assemble} 与加这一层之前<b>逐字节一致</b>。</p>
     */
    public void setExt(sair.v4.ext.ExtRegistry e) { this.ext = e; }

    /**
     * 注入识图组件的状态载体（T12-R3；装配期调一次，{@code Boot} 注的是
     * {@code RecognizerState.shared()} —— 也就是 {@code agent.Loop} 计数的那一份）。
     * <p>传 {@code null} = 保持默认的"恒 {@code off} / 0"（不抛、不改任何键）。</p>
     */
    public void setRecognizer(RecognizerState r) { if (r != null) this.recognizer = r; }

    /**
     * 这个模型名<b>自己能不能看图</b>（唯一算法；不读配置，纯函数）。
     *
     * <p><b>白名单，不是猜名字。</b>旧口径是松判（{@code contains("vision")||contains("vl")||contains("flash")}），
     * 它把"名字里带 flash 的野生模型"一律当成能看图 —— 判错的代价是<b>不对称</b>的：
     * 误判"能看"⇒ 请求体里挂了图 ⇒ 模型不认 ⇒ <b>HTTP 400 整个回合失败</b>；
     * 误判"不能看"⇒ 退回派识图子 Agent，多花一次调用，但回合照常。
     * 所以未知一律 {@code false}（安全默认）。
     *
     * <p>基准表 = V3 权威写法（{@code AiAgent\sair\aiagent\core\AiConfig#hasNativeVision}）：
     * {@code deepseek-flash} / {@code deepseek-v4-flash} / {@code deepseek-v4-flash-vision-exp} 为真；
     * {@code deepseek-v4-pro}（官方标记"图像理解：不支持"）/ {@code deepseek-reasoner} / 一切未知为假；
     * {@code auto} <b>不是模型名</b> ⇒ 假（调用方应先用 {@code Conf.resolveModel()} 解析）。
     * 配置里可用 {@code visionModels}（逗号分隔）与 {@code visionModel} 追加白名单项 ——
     * 那是"主人说这个也能看"，属于显式覆盖，走得通。
     *
     * @param visionModels 追加白名单（逗号分隔，可 null/空）
     * @param visionModel  追加白名单（单个，可 null/空；{@code auto} 不算）
     */
    public static boolean looksVisionCapable(String model, String visionModels, String visionModel) {
        String m = model == null ? "" : model.trim().toLowerCase();
        if (m.isEmpty() || "auto".equals(m)) return false;
        if ("deepseek-flash".equals(m)
                || "deepseek-v4-flash".equals(m)
                || "deepseek-v4-flash-vision-exp".equals(m)) return true;
        // 配置追加：主人显式点名"这个也能看"
        String single = visionModel == null ? "" : visionModel.trim().toLowerCase();
        if (!single.isEmpty() && !"auto".equals(single) && single.equals(m)) return true;
        String many = visionModels == null ? "" : visionModels;
        for (String part : many.split(",")) {
            String p = part == null ? "" : part.trim().toLowerCase();
            if (!p.isEmpty() && !"auto".equals(p) && p.equals(m)) return true;
        }
        return false;
    }

    /**
     * 只按<b>基准白名单</b>判（不追加配置里的 {@code visionModels}/{@code visionModel}）。
     *
     * <p><b>为什么保留这个 1 参数重载</b>：{@code data\skills\视觉识图\VisionSkill.java} 是
     * <b>运行时</b>编译的技能（{@code _build.ps1} 只收 {@code src}），它按这个名字与签名直接链进来。
     * 改了名或改成实例方法 ⇒ 线上技能编译当场炸。所以它是"没有配置可读的调用方"的入口，
     * 算法仍然只有上面那一个（这里只是把两个追加项传 {@code null}）。
     */
    public static boolean looksVisionCapable(String model) {
        return looksVisionCapable(model, null, null);
    }

    /**
     * <b>这一轮到底能不能用原生喂图</b>（{@code models.mode} 的唯一算法）：
     * {@code mediaVision} × "自己能不能看图"。
     *
     * <ul>
     *   <li>{@code no} ⇒ {@code off}（一律不看，即使模型能看）；</li>
     *   <li>{@code yes} ⇒ {@code native}（主人强制认为能看）；</li>
     *   <li>{@code relay} / {@code look} ⇒ {@code relay}（<b>甲方急令：所有路径都只能过看图手</b> ——
     *       图一律不挂进请求，只看 {@code look} 交回来的机器描述。<b>不看模型能力</b>：能看也照样 relay）；</li>
     *   <li>{@code auto}（以及其余认不出的值，按 auto 处理）⇒ 能看 {@code native}，否则 {@code relay}；</li>
     * </ul>
     *
     * <p><b>{@code off} 不等于"她不能看"</b>：{@code off} 只说"这一轮不许把图挂上去"（见 facts 里
     * {@code self_has_vision} 仍报白名单真值）—— "我能不能"与"允不允许"是两件事。
     * {@code relay} 同理：白名单真值照报，只是不许挂图。</p>
     *
     * <p><b>为什么需要显式的 {@code relay}</b>：{@code deepseek-flash} 在硬白名单里 ⇒ 原来的
     * {@code auto} 恒判 {@code native}，而 {@code visionModels}/{@code visionModel} 只能<b>追加</b>
     * 白名单、摘不掉 ⇒ 光靠配置改不出"永远 relay"。这个取值就是那条<b>唯一</b>的显式出口。</p>
     *
     * @param model 这一轮真正跑的模型（见 {@link #MODELS_SELF}）
     */
    public String visionMode(String model) {
        String mv = Str.lower(Str.trim(conf.mediaVision()));
        if ("no".equals(mv) || "off".equals(mv) || "false".equals(mv)) return MODE_OFF;
        if ("yes".equals(mv) || "on".equals(mv) || "true".equals(mv)) return MODE_NATIVE;
        // 显式 relay（别名 look：甲方口径里"图手"就是这个组件）⇒ 恒 relay，不看模型能力。
        // 注意顺序：这一判**必须**落在下面那条 auto 兜底之前，但**不许**动 auto 的语义
        // （auto 仍 = 能看就 native）。no/yes 两组也不受影响。
        if ("relay".equals(mv) || "look".equals(mv)) return MODE_RELAY;
        return selfHasVision(model) ? MODE_NATIVE : MODE_RELAY;
    }

    /** 这个模型自己能不能看图（读配置的三个追加项；与 facts 里 {@code self_has_vision} 同源）。 */
    public boolean selfHasVision(String model) {
        return looksVisionCapable(model, conf.visionModels(), conf.visionModel());
    }

    /** {@code models.mode} 的三个取值。 */
    public static final String MODE_OFF = "off";
    public static final String MODE_NATIVE = "native";
    public static final String MODE_RELAY = "relay";

    /** 每轮带进上下文的历史条数（默认与 V3 对齐：{@code DEF_HISTORY_LIMIT}）。 */
    public int historyLimit() { return conf.getInt("historyLimit", sair.v4.Conf.DEF_HISTORY_LIMIT); }

    /** 历史正文的字符预算（V3 口径：{@code DEF_HISTORY_MAX_CHARS}；{@code <=0} = 不限）。 */
    public int historyMaxChars() { return conf.getInt("historyMaxChars", sair.v4.Conf.DEF_HISTORY_MAX_CHARS); }

    /** 动态事实块（键值形式，纯数据）。 */
    public String facts(Turn t) {
        StringBuilder sb = new StringBuilder();
        sb.append("time: ").append(Str.now()).append("\n");
        if (t != null && t.caller() != null) {
            JsonObject c = new JsonObject();
            c.addProperty("entry", t.caller().isConsole() ? "console" : "qq");
            c.addProperty("qq", t.caller().qq());
            String nm = t.caller().name();
            if (Str.has(nm)) c.addProperty("name", nm);
            c.addProperty("master", t.caller().master());
            c.addProperty("session", t.session());
            if (t.caller().isGroup()) {
                c.addProperty("group", t.caller().groupId());
                if (Str.has(t.caller().groupRole())) c.addProperty("role", t.caller().groupRole());
            }
            // 好感度：**跟身份无关**地给她（主人裁 2026-09-17 —— 它不判权限、是"关系值"，
            // 每个人都能问她"我的好感度是多少"；她得先看得到，说不说完全由她定）
            c.addProperty("favor", (long) t.caller().favor());
            // 权限事实（ACL）：主体种类 + 这个人对五类资源的**有效位**。提示词层拿这两个判断
            // "能不能答应他"，不再看工具名（工具的可见性不再按调用者筛，见 notes/acl-design-draft.md）。
            // 位一律问账本（Acl.bitsOf），基板不在这里重算任何一位。
            c.addProperty("kind", kindName(t.caller()));
            c.add("bits", callerBits(t.caller()));
            // 权限事实（续）：这个调用者**能命中的例外条目**（主体层级 > 0），原样条目一行一条。
            // 目的只有一个 —— 让她"快速查到此人是否具备特例放行"，不用每次去翻纪律文本。
            // 取值一律问账本（Acl.ruleLines），基板在这里不算任何一位、也不下任何结论。
            c.add("rules", callerRules(t.caller()));
            sb.append("caller: ").append(J.json(c)).append("\n");
        }
        // 人格化事实：「正在说话的这个人」是谁（跨群同一人）——六库聚合出来的客观数据，纯事实。
        // 只给数据不给文案：认不认识、在哪些群见过、聊过多少，全部由提示词层的模型自己解读。
        // 构建失败（库不可用等）就当这一行不存在，绝不让事实块把回合拖垮。
        if (t != null && t.caller() != null && store != null && t.caller().qq() > 0L) {
            try {
                JsonObject p = sair.v4.store.Person.facts(store, t.caller().qq(), t.caller().name(),
                        t.caller().favor(), t.caller().master());
                if (p != null && p.size() > 0) sb.append("person: ").append(J.json(p)).append("\n");
            } catch (Throwable ignored) {
                // 事实块少一行不影响这一轮
            }
        }
        // 本轮的媒体事实（只有媒体段的消息才有这一行；纯数据，读法归提示词层）
        String media = t == null ? "" : t.get(ST_MEDIA, "");
        if (Str.has(media)) sb.append(media.trim()).append("\n");
        sb.append("napcat: ").append(env != null && env.napcatConnected() ? "connected" : "disconnected").append("\n");
        // 模型事实（P0-3 S1）：她必须知道"这一轮跑的是哪台模型、它自己能不能看图"。
        // self = 这一轮真正在跑的模型（子 Agent 的 model 覆盖由 Agent.execSub 写在同一个源上，
        // 见 MODELS_SELF）；main 仍然报配置里的主模型 —— 三个既有字段（main/vision/main_has_vision）
        // 一个不删、语义不变（Builtins 的工具说明、VisionSkill、identity.md 都在读它们）。
        // 键序固定，便于逐字断言。
        JsonObject mm = new JsonObject();
        String main = conf.resolveModel();
        String vision = conf.visionModel();
        String self = selfModel(t);
        mm.addProperty("self", self);
        // "我能不能"（白名单真值）——**不因 mediaVision=no 改写成 false**：那是把事实编掉。
        // "允不允许"由 mode 表达，两件事分开写。
        mm.addProperty("self_has_vision", selfHasVision(self));
        mm.addProperty("main", main);
        mm.addProperty("main_has_vision", selfHasVision(main));
        mm.addProperty("vision", vision);
        mm.addProperty("mode", visionMode(self));
        // 识图组件事实（T12-R3）：名字 = **组件注册的工具名**（约定名 look，见 RecognizerState.TOOL），
        // 数值 = **今天**它经工具面被调用了多少次（**含被拒 / 失败**；技能内部 h.call 不计入 —— 这是定义，
        // 见 RecognizerState 类注释的口径条）。全部口径都在 RecognizerState 的类注释里
        // （约定名 / 计数定义 / 内存计数跨日归零、**重启后从 0 重计** / 不注入恒 off-0 且绝不抛）。这里只读，不编值：
        // 名字不在工具面就照实答 off —— 编一个不存在的组件与编一个不存在的次数一样是谎报。
        // 数据源是装配期注入的状态载体（Loop 计的是同一份）；**没注入 = 默认实例 ⇒ 与加这一层之前逐字节相同**。
        mm.addProperty("recognizer", recognizer.state());
        mm.addProperty("recognizer_uses", recognizer.uses());
        sb.append("models: ").append(J.json(mm)).append("\n");
        // 外挂的只读口：同一行的 JSON 同时落到 Turn 状态上，插件可用现成的
        // BuildContext.state("models","") 读到（**不新增扩展点接口**）。
        if (t != null) t.put("models", J.json(mm));
        int running = env == null ? 0 : env.tasksRunning();
        if (running > 0) sb.append("tasks_running: ").append(running).append("\n");
        // 能力索引：一行事实（类别 → 工具[一句话]），帮模型在几十个工具里定位。
        // 子 Agent 只给名字（同一份 schema 已经在工具表里，索引再带描述是重复付费）。
        String ix = env == null ? null : env.toolIndex(t);
        if (Str.has(ix)) sb.append(ix.trim()).append("\n");
        // 轮次预算：一行事实（第几轮 / 还剩几轮 / 这一轮的上限是谁的）—— 给她自己规划用。
        // 值与 Loop 真正用的上限同源（见 Env.roundsFact）；Loop 每回合会就地刷新这一行。
        String rounds = env == null || t == null ? null : env.roundsFact(t);
        if (Str.has(rounds)) sb.append(rounds.trim()).append("\n");
        String extra = env == null ? null : env.extraFacts();
        if (Str.has(extra)) sb.append(extra.trim()).append("\n");
        String inj = prompts.context(t.session());
        if (Str.has(inj)) sb.append("\n").append(inj.trim()).append("\n");
        // 本会话最近 N 条聊天记录（动态窗口，纯事实）——**放在事实块的最后一段**：
        // 事实块这条 system 之后紧接着就是 appendHistory() 拼的对话历史，
        // 窗口贴着它放，读起来就是"这是最近的原话，下面是按角色排的历史"。
        String win = t == null ? "" : t.get(ST_CHAT_WINDOW, "");
        if (Str.has(win)) sb.append("\n").append(win.trim()).append("\n");
        return sb.toString().trim();
    }

    // ---------------------------------------------------------------- 事实块里的权限事实（caller.kind / caller.bits）

    /** 装配方没接权限面（{@link Env#auth()} 返回 {@code null}）时，自己按数据根读的那份账本（只读缓存）。 */
    private volatile Acl ownAcl;
    /** 上面那份账本的"文件指纹"（mtime + 长度）：变了就重读，免得事实块报旧位。 */
    private volatile long ownAclStamp = Long.MIN_VALUE;
    private volatile long ownAclLen = Long.MIN_VALUE;

    /**
     * 主体种类（事实块 {@code caller.kind}）：{@code MASTER} / {@code SYSTEM} / {@code ALLUSER}
     * —— 与 {@link Caller.Kind} 同名（主任定标：正式身份只有这三个，{@code OTHER} 已退休）。
     */
    static String kindName(Caller c) {
        if (c == null) return "";
        return c.kind().name();
    }

    /**
     * 事实块 {@code caller.bits}：<b>这个人对五类资源的有效位</b>
     * （{@code {"A":"R","B":"RX","C":"RWX","E":"NONE","T":"NONE"}}，{@link Bits#format} 的规范写法）。
     *
     * <p><b>五类都写</b>（一位都没有也显式写 {@code NONE}）：提示词层就是拿这五个值判断"能不能答应他"，
     * 少写一类会被读成"没限制"。</p>
     *
     * <p>取值一律问账本（{@link Acl#bitsOf}）—— <b>基板不在这里重算任何一位</b>。
     * 每一类用它的<b>代表资源</b>去问（见 {@link #repRes(char)}）：资源自己决定类别，
     * 所以代表资源的类别必须对得上；例外条目能否命中取决于它的范围，因此这里体现的是
     * "整类例外 + 覆盖该代表资源的例外"，更细的例外仍由判定那一刻的 {@code Auth.allowRes} 说了算。</p>
     */
    private JsonObject callerBits(Caller c) {
        JsonObject o = new JsonObject();
        Acl a = acl();
        o.addProperty("A", bitsText(a, c, Res.A));
        o.addProperty("B", bitsText(a, c, Res.B));
        o.addProperty("C", bitsText(a, c, Res.C));
        o.addProperty("E", bitsText(a, c, Res.E));
        o.addProperty("T", bitsText(a, c, Res.T));
        return o;
    }

    /** 事实块 {@code caller.rules} 的<b>条数上限</b>（超出只写一句"还有 N 条"，不把账本整段搬进上下文）。 */
    static final int CALLER_RULES_MAX = 8;

    /**
     * 事实块 <b>{@code caller.rules}</b>：<b>这个调用者能命中的例外条目</b>
     * （主体层级 &gt; 0，按账本顺序；{@link Acl.Entry#raw()} 的原样写法，例如
     * {@code A["User123456","D:/share","R"]}）。
     *
     * <p><b>用途</b>（主人 2026-09-15 21:1x：「特例表加载进内存可以给她快速查询到此人是否具备特例放行」）：
     * 她拿这一格就能一眼看到"此人有没有特例放行"，不必每次去翻纪律文本或反复试。
     * 与 {@code caller.bits} 分工明确：{@code bits} 是<b>结论</b>（五类有效位），
     * {@code rules} 是<b>依据</b>（哪几条例外在起作用），两个都不含判定过程。</p>
     *
     * <p>取值一律问账本（{@link Acl#ruleLines(Caller, int)} → {@link Acl#rulesFor(Caller)}，只遍历
     * 内存里<b>已加载</b>的条目）：<b>不重读磁盘</b>、<b>不算位</b>、<b>不碰 {@code Auth} 之外的判定</b>
     * —— 基板在这里只"喂查询视图"。取舍（只看主体层级 &gt; 0、不看具体资源）见
     * {@link Acl#rulesFor(Caller)} 的注释：「某条条目有没有命中某个具体资源」仍由判定那一刻的
     * {@code Auth.allowRes} 说了算。</p>
     *
     * <p>上限 {@value #CALLER_RULES_MAX} 条（超出的不列，末尾追加一句"还有 N 条"，N = 没列出来的条数）；
     * <b>一条都没有 = 空数组 {@code []}</b> —— <b>绝不写 {@code null}</b>（写 null 会被读成"没有这个键"，
     * "没有例外"与"没这一格"就分不清了）。账本读不到（{@link #acl()} 返回 {@code null}）同样是空数组，
     * 与 {@code bits} 那边"读不到就退该类默认分配"一样：事实块宁可少一个值，也不把回合拖垮。
     * MASTER 必然是空数组（条目管不到他）—— 那是正确结果，不是查不到。</p>
     *
     * <p><b>{@code caller.bits} 的五类语义（A/B/C/E/T）一个字都没动</b>：这一格是<b>新增</b>的键，
     * 不是把 {@code bits} 拆开重排。</p>
     */
    private JsonArray callerRules(Caller c) {
        JsonArray arr = new JsonArray();
        if (c == null) return arr;
        Acl a = acl();
        if (a == null) return arr;
        try {
            List<String> lines = a.ruleLines(c, CALLER_RULES_MAX);
            for (int i = 0; i < lines.size(); i++) arr.add(lines.get(i));
        } catch (Throwable ignored) {
            // 账本查询出异常 = 这一格当"没有例外"（与 person 事实同一口径：少一格不影响这一轮）
        }
        return arr;
    }

    /**
     * 某一类的有效位文字。账本读不到 / 这一类在这个进程里没有可问的资源时，
     * 按<b>该类默认分配</b>给（{@link Acl#allocOf}，它自己就是"一条例外都没命中"的口径），绝不猜更宽的位。
     */
    private String bitsText(Acl a, Caller c, char cls) {
        if (c == null) return Bits.format(Bits.NONE);
        Res r = repRes(cls);
        if (a == null || r == null) return Bits.format(Acl.allocOf(c.kind(), cls));
        return Bits.format(a.bitsOf(c, r));
    }

    /**
     * 五类的<b>代表资源</b>（只用来问"这个人对那一类有什么位"）：资源自己决定类别，
     * 所以代表资源必须先判成对的那一类，判不成时宁可退"默认分配"（{@code null}）也不把别类的位报上来。
     * <ul>
     *   <li><b>A 本机</b> → 系统盘根（{@code C:/}）；不在 SFW 之下才算数，否则退空路径
     *       （{@link Res#path(String)} 对空路径的口径：类别 A + 匹配串空，只命中"整类"例外）；</li>
     *   <li><b>B SFW</b> → 数据根（线上它就在 SFW 之内；它自己不是 {@code prompts/**}、{@code config.json}
     *       那几个受保护目标，所以不会被"第二层受保护"抹成 {@code NONE}）；
     *       <b>判不成 B</b>（SFW 根与进程工作目录不一致的场合，例如探针的临时数据根 ——
     *       那时这个进程里根本没有 B 类资源）→ {@code null}；</li>
     *   <li><b>C 数据</b> → {@code db:memory}（不是 {@code mem:acl} 那个受保护项）；</li>
     *   <li><b>E 对外</b> → {@code send_group_msg}（不是 {@code get_cookies} 那个受保护动作）；</li>
     *   <li><b>T 工具</b> → {@code tool:perm}（一个常驻工具名，只用来读"整类 + 覆盖它的例外"）。</li>
     * </ul>
     */
    private Res repRes(char cls) {
        if (cls == Res.A) return repA();
        if (cls == Res.B) return repB();
        if (cls == Res.C) return Res.db("memory");
        if (cls == Res.T) return Res.tool("perm");
        return Res.platform("send_group_msg");
    }

    /** A 类的代表资源：系统盘根（取不到就退空路径）。 */
    private static Res repA() {
        String drive = null;
        try {
            drive = System.getenv("SystemDrive");
        } catch (Throwable ignored) {
            // 环境变量不可读 → 退空路径
        }
        if (Str.has(drive)) {
            Res r = Res.path(drive.trim() + "/");
            if (r.cls() == Res.A) return r;
        }
        return Res.path("");
    }

    /** B 类的代表资源：数据根；它没被判成 B 就返回 {@code null}（调用方按默认分配给）。 */
    private Res repB() {
        File r = conf == null ? null : conf.root();
        Res res = Res.path(r == null ? "" : r.getPath());
        return res.cls() == Res.B ? res : null;
    }

    /**
     * 权限账本（只读）：优先用装配方的权限面（{@link Env#auth()}），没接就自己按数据根读一份。
     * <p>自己读的那份带"文件指纹"缓存：{@code perms.json} 改了（主人写完例外）下一轮就重读。</p>
     */
    private Acl acl() {
        if (env != null) {
            try {
                sair.v4.auth.Auth a = env.auth();
                if (a != null) return a.acl();
            } catch (Throwable ignored) {
                // 权限面没装好 → 退回下面自己读
            }
        }
        File f = Acl.fileOf(conf == null ? null : conf.root());
        long m = f.lastModified();
        long n = f.length();
        Acl a = ownAcl;
        if (a != null && m == ownAclStamp && n == ownAclLen) return a;
        synchronized (this) {
            a = ownAcl;
            if (a == null || m != ownAclStamp || n != ownAclLen) {
                try {
                    // out=null：同一份账本的"非法条目"告警由权限面那边报一次，这里不重复刷屏
                    a = Acl.load(f, null);
                } catch (Throwable t) {
                    a = null;                       // 读不到 = 按默认分配（见 bitsText），不让事实块拖垮回合
                }
                ownAcl = a;
                ownAclStamp = m;
                ownAclLen = n;
            }
        }
        return a;
    }

    /**
     * 装配 messages 到 turn 上：{@code system(identity + 偏好) → 稳定块 → 内置事实块 → 易变块 → 历史 → 本轮 user}。
     *
     * <p><b>零 provider 时</b>（今天的样子）产出与"没有这一层"完全相同：
     * 第一条 system 是稳定段、第二条是事实块、然后是历史与 user。</p>
     *
     * <p><b>为什么稳定块插在事实块之前</b>：前缀缓存按 messages 的前缀逐字节命中，
     * 稳定块紧贴系统提示词才进得了那段前缀；事实块第一行就是 {@code time:}（每轮都变），
     * 排在它后面的东西一律进不了前缀。</p>
     *
     * <p><b>为什么易变块排在事实块之后</b>：让"内置事实块恒为第二条 system"这件事不受插件数量影响 ——
     * 序号稳定，排障与断言才不会被"装了哪个插件"改变。</p>
     */
    public void assemble(Turn t, String userText) {
        if (t == null) return;
        // 一次产出（provider 每轮只应该跑一次），再按段位归位
        List<sair.v4.ext.ExtRegistry.Block> blocks = extBlocks(t, userText);
        JsonArray msgs = t.messages();
        msgs.add(sys(t, stable(t)));
        addBlocks(msgs, t, blocks, true);
        // 基板内置事实块 —— 这是"内置 provider"的位置（S3 把它搬进插件时删的就是这一段，以及下面的 facts()）。
        // 它**刻意**不走 ExtRegistry 的超时与预算：事实块（时间/调用者/媒体/聊天记录窗口/能力索引）是基板骨架，
        // 外挂再多也不能把它挤掉 —— 否则一个插件就能让基板"看不见自己"，那正是"基板永不失联"要防的事。
        // 同理，它读的是整个 Turn，而扩展点的只读口刻意不给 Turn（见 BuildContext 的注释）。
        String facts = facts(t);
        if (Str.has(facts)) msgs.add(sys(t, facts));
        addBlocks(msgs, t, blocks, false);
        appendHistory(t);
        msgs.add(user(t, userText));
    }

    /** 只装配系统与上下文（供子 Agent 复用历史片段）；段位与 {@link #assemble} 完全一致。 */
    public void assembleSystem(Turn t) {
        if (t == null) return;
        List<sair.v4.ext.ExtRegistry.Block> blocks = extBlocks(t, "");
        t.messages().add(sys(t, stable(t)));
        addBlocks(t.messages(), t, blocks, true);
        String facts = facts(t);
        if (Str.has(facts)) t.messages().add(sys(t, facts));
        addBlocks(t.messages(), t, blocks, false);
    }

    /**
     * 产出这一轮的外挂块（已排序、已逐段截断、已按全局预算裁过）。
     * <p>没有挂 provider / 扩展点总开关关掉 = 一个字节都不多（连 provider 都不会被调用）。</p>
     */
    private List<sair.v4.ext.ExtRegistry.Block> extBlocks(Turn t, String text) {
        sair.v4.ext.ExtRegistry e = ext;
        if (e == null || !e.on()) return Collections.emptyList();
        try {
            return e.blocks(t.caller(), t, text);
        } catch (Throwable x) {
            // 扩展点自己已经做了异常隔离；这里是"连注册表都炸了"的最后一道，同样只丢外挂块
            if (out != null) out.warn("[ext] 上下文块产出失败（这一轮没有外挂块）：" + x);
            return Collections.emptyList();
        }
    }

    /** 把某一<b>段位</b>（稳定/易变）的块各拼成一条 system 消息（一段一条，顺序即注册顺序）。 */
    private static void addBlocks(JsonArray msgs, Turn t, List<sair.v4.ext.ExtRegistry.Block> blocks, boolean stable) {
        if (blocks == null || blocks.isEmpty()) return;
        for (sair.v4.ext.ExtRegistry.Block b : blocks) {
            if (b.stable != stable) continue;
            if (Str.blank(b.text)) continue;
            msgs.add(sys(t, b.text));
        }
    }

    // ---------------------------------------------------------------- 稳定段（系统提示词 + 偏好块）

    /**
     * <b>稳定段</b> = 系统提示词 + 偏好块（同一条 system 消息）。
     *
     * <p>为什么偏好进这一段、而不是进 {@link #facts}：事实块第一行就是 {@code time:}，<b>每轮都变</b>；
     * 而"这个人的偏好"在一个会话里长期不变。放进第一条 system，前缀能整段字节复用（前缀缓存友好）；
     * 排序也必须确定（见 {@link #mergePrefs}），否则同一份偏好每轮排出来的字节都不同，缓存白搭。</p>
     *
     * <p>两条装配路径（{@link #assemble} 普通回合 / {@link #assembleSystem} 收尾回合）都走这里，
     * 所以偏好对两者都生效。</p>
     */
    private String stable(Turn t) {
        String sys = prompts == null ? "" : prompts.system(t == null ? null : t.session());
        String prefs = prefBlock(t == null ? null : t.caller());
        if (!Str.has(prefs)) return sys;                      // 没有偏好 = 一个字都不多（零开销）
        return Str.has(sys) ? sys + "\n\n" + prefs : prefs;
    }

    /**
     * 偏好块：当前调用者可见的三层偏好合并后的 {@code - key: value} 清单；没有偏好返回空串。
     *
     * <p>取值层级：{@code global}（全体）+ {@code user}（调用者 QQ）+ {@code group}（当前群）；
     * <b>控制台/本地没有 QQ 身份，只有 {@code global}</b>（与 V3 控制台口径一致）。</p>
     *
     * <p>取数走 {@link Store#prefsFor}（一条 SQL 查三层，命中 {@code idx_pref_scope}）。
     * <b>不额外加内存缓存</b>：{@code pref} 是"人写的配置表"，量级是几十行，点查成本可以忽略；
     * 而一旦加 TTL 缓存，就出现"刚设的偏好这一轮看不到"的窗口 —— 那是拿正确性换一个不需要的性能。</p>
     */
    private String prefBlock(sair.v4.auth.Caller c) {
        if (conf == null || store == null || !conf.prefInject()) return "";
        long qq = 0L;
        long gid = 0L;
        if (c != null && !c.isConsole()) {                    // 控制台/本地 = 主人但无 QQ 语境 → 只看 global
            qq = c.qq();
            gid = c.groupId();
        }
        int maxRows = conf.prefInjectMaxRows();
        // 取数上限 = 条数预算 × 层数：排序后前 N 个 key 的胜出行一定落在这个窗口里（一个 key 最多三层各一行）；
        // 不按条数截断时用一把硬上限，避免"配置成不限制"变成"把整库拉进内存"。
        int fetch = maxRows > 0 ? maxRows * PREF_LAYERS + PREF_LAYERS : PREF_FETCH_MAX;
        List<JsonObject> rows;
        try {
            rows = store.prefsFor(qq, gid, fetch);
        } catch (Throwable t) {
            return "";                                        // 读库失败少一段不影响这一轮（与 person 事实同一口径）
        }
        List<JsonObject> pick = mergePrefs(rows);
        if (pick.isEmpty()) return "";

        String title = texts == null ? null : texts.prefTitle();
        String rowTpl = texts == null ? null : texts.prefRow();
        String cutTpl = texts == null ? null : texts.prefCut();
        if (Str.blank(title) || Str.blank(rowTpl) || Str.blank(cutTpl)) {
            warnPrefKeysOnce();
            if (Str.blank(title)) title = DEF_PREF_TITLE;
            if (Str.blank(rowTpl)) rowTpl = DEF_PREF_ROW;
            if (Str.blank(cutTpl)) cutTpl = DEF_PREF_CUT;
        }
        // 先把每行渲染好（模板只在这里用一次），后面两次截断都只做"丢尾巴"。
        List<String> lines = new ArrayList<String>();
        for (JsonObject r : pick) lines.add(fill(rowTpl, r));

        int maxChars = conf.prefInjectMaxChars();
        int keep = maxRows > 0 && maxRows < pick.size() ? maxRows : pick.size();
        // 截断口径：丢尾巴。排序是 importance DESC、key ASC，所以"尾巴"恰好就是
        // "先丢 importance 最小的、同档再按 key 降序丢" —— 与要求的顺序逐字一致。
        String block = renderPref(title, lines, cutTpl, keep);
        // 字符预算：整块（含标题与截断行）装不下就继续丢尾巴。循环上界 = 条数预算（默认 30），成本可忽略。
        // 极端配置（预算小到连标题都装不下）时仍发"标题 + 截断行"：宁可略超预算，也不让偏好静默消失。
        while (maxChars > 0 && keep > 0 && block.length() > maxChars) {
            keep--;
            block = renderPref(title, lines, cutTpl, keep);
        }
        return block;
    }

    /** 渲染偏好块：标题一行 + 前 {@code keep} 行 + （有丢弃时）截断行一行。 */
    private static String renderPref(String title, List<String> lines, String cutTpl, int keep) {
        StringBuilder sb = new StringBuilder(title);
        for (int i = 0; i < keep && i < lines.size(); i++) sb.append('\n').append(lines.get(i));
        int rest = lines.size() - keep;
        if (rest > 0) sb.append('\n').append(cutTpl.replace("{rest}", String.valueOf(rest)));
        return sb.toString();
    }

    /** 一行偏好：{@code {key}}/{@code {value}}/{@code {scope}}/{@code {importance}} 替换（值压成单行，保证"一行一条"）。 */
    private static String fill(String tpl, JsonObject row) {
        return tpl.replace("{key}", Str.trim(J.s(row, "key", "")))
                .replace("{value}", Str.oneLine(J.s(row, "value", "")))
                .replace("{scope}", J.s(row, "scope", ""))
                .replace("{importance}", String.valueOf(J.i(row, "importance", 0)));
    }

    /**
     * 合并三层：<b>同一个 key 只留一条</b> —— 先比 {@code importance}（大的赢），
     * 打平则"更具体的作用域赢"（{@code user} &gt; {@code group} &gt; {@code global}）。
     *
     * <p>顺序：{@code importance DESC}、{@code key ASC}（字母序）。<b>必须确定</b>：
     * 这段文本进的是每条消息的前缀，同内容必须逐字节稳定，前缀缓存才有意义。</p>
     *
     * <p>同 scope 同 key 万一有多行（表上没有唯一约束）：取先到的那条。查询按 {@code id DESC} 排，
     * 所以"先到 = 新写的那条"。</p>
     */
    private static List<JsonObject> mergePrefs(List<JsonObject> rows) {
        Map<String, JsonObject> best = new LinkedHashMap<String, JsonObject>();
        if (rows != null) {
            for (JsonObject r : rows) {
                if (r == null) continue;
                String key = Str.trim(J.s(r, "key", ""));
                if (key.isEmpty()) continue;
                JsonObject cur = best.get(key);
                if (cur == null || beats(r, cur)) best.put(key, r);
            }
        }
        List<JsonObject> out = new ArrayList<JsonObject>(best.values());
        Collections.sort(out, new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject a, JsonObject b) {
                int c = J.i(b, "importance", 0) - J.i(a, "importance", 0);
                if (c != 0) return c;
                return J.s(a, "key", "").compareTo(J.s(b, "key", ""));
            }
        });
        return out;
    }

    /** {@code a} 是否压过 {@code b}（importance 大的赢；打平 = 作用域更具体的赢）。 */
    private static boolean beats(JsonObject a, JsonObject b) {
        int ia = J.i(a, "importance", 0);
        int ib = J.i(b, "importance", 0);
        if (ia != ib) return ia > ib;
        return scopeRank(J.s(a, "scope", "")) > scopeRank(J.s(b, "scope", ""));
    }

    /** 作用域具体度：{@code user}(2) &gt; {@code group}(1) &gt; {@code global}(0)。 */
    private static int scopeRank(String scope) {
        if ("user".equals(scope)) return 2;
        if ("group".equals(scope)) return 1;
        return 0;
    }

    /** 偏好块缺键：只 warn 一次（每轮都拼块，不能每轮刷一行），然后照旧用内置默认值渲染。 */
    private void warnPrefKeysOnce() {
        if (prefKeysWarned) return;
        prefKeysWarned = true;
        if (out == null) return;
        String miss = texts == null ? "偏好标题、偏好行、偏好截断" : texts.missingPrefKeys();
        out.warn("[ctx] prompts/tools-index.md 缺键：" + miss
                + "（偏好块改用内置默认文案渲染 —— 缺键不停止注入）");
    }

    /** 历史对话（dialog 库，最近 N 条，按时间升序；按字符预算从最旧的整条丢）。 */
    public void appendHistory(Turn t) {
        if (store == null || t == null) return;
        List<JsonObject> rows = store.recentDialog(t.session(), historyLimit());
        rows = trimHistory(rows, historyMaxChars());
        rows = dropSelfDup(rows);
        for (JsonObject r : rows) {
            String role = J.s(r, "role", "user");
            String content = J.s(r, "content", "");
            if (Str.blank(content)) continue;
            if (!"user".equals(role) && !"assistant".equals(role)) continue;
            t.messages().add(msg(role, content));
        }
    }

    /**
     * <b>P0-1「窗口读侧 (b′)」第 3 步</b>：同一批历史里"去头正文"相同的两条，
     * 只留<b>带头部</b>的那一条（带真 {@code msg_id} ⇒ 信息更多）。
     *
     * <p>治什么：私聊只有 {@code dialog} 一份落点 —— 她的成品句本来就有一行
     * {@code role=assistant}（{@code Agent.ask} 落库），P0-1 的标记行又是一行 ⇒
     * 上下文里同一句话会被注入<b>两遍</b>（一遍裸正文、一遍带头部）。群侧同理。</p>
     *
     * <p>与窗口那一侧（{@code ctx.ChatWindow.dropSelfDupInWindow}）是<b>同一条规则</b>，
     * 但刻意各写一份而不是共用一支：两边的输入形状不同（这里是"要注入的历史行"，
     * 那里是"窗口行"），而共用一支就得把"顺序敏感性"（窗口那边必须先去自我重复、
     * 再去历史重复）编码进一个公共函数 —— 那反而更容易被后来的改动弄错。
     * 规则本体只有一句：<b>去头正文相同、且至少一条是自我标记行 ⇒ 留带头部的那条。</b></p>
     *
     * <p>零新增查询、零新增字段：纯字符串处理，行都已在内存里（{@code recentDialog} 的结果）。
     * 两行都不是自我标记行 ⇒ 一个字都不动。</p>
     *
     * @return 去重后的列表（**不修改入参**：{@code trimHistory} 可能返回的是原列表的视图，
     *         就地删会改到调用方看不见的共享结构）
     */
    static List<JsonObject> dropSelfDup(List<JsonObject> rows) {
        if (rows == null || rows.size() < 2) return rows;
        List<JsonObject> out = new ArrayList<JsonObject>(rows);
        for (int i = out.size() - 1; i >= 0; i--) {
            JsonObject newer = out.get(i);
            boolean newerSelf = isSelfRow(newer);
            String key = selfCmpText(newer);
            if (key.isEmpty()) continue;
            for (int j = i - 1; j >= 0; j--) {
                JsonObject older = out.get(j);
                boolean olderSelf = isSelfRow(older);
                if (!newerSelf && !olderSelf) continue;
                if (!key.equals(selfCmpText(older))) continue;
                // 留带头部的那条（逐条判据，别用"if/else 恰好对"隐式表达）：
                //   新带头部、旧不带头部 ⇒ 去旧的（保留带真 msg_id 的那条）
                //   新不带头部、旧带头部 ⇒ 去新的
                //   都带头部           ⇒ 去旧的（留新的，与窗口那一侧同一取向）
                if (newerSelf && !olderSelf) { out.remove(j); }
                else if (!newerSelf && olderSelf) { out.remove(i); }
                else { out.remove(j); }
                break;
            }
        }
        return out;
    }

    /** 这一行是不是"她自己发的"标记行（只看 {@code content} 头部前缀；头部产法唯一，见 {@code qq.SelfEcho}）。 */
    private static boolean isSelfRow(JsonObject r) {
        try {
            return r != null && sair.v4.qq.SelfEcho.isSelfContent(J.s(r, "content", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /** 去重比较用的正文（压单行 + 去头部；空正文不参与比较）。 */
    private static String selfCmpText(JsonObject r) {
        try {
            return sair.v4.qq.SelfEcho.withoutHead(Str.oneLine(Str.nz(J.s(r, "content", ""))));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 历史字符预算（V3 口径：从最旧的开始整条丢，留最新的）。
     *
     * <p>V3 原样逻辑在 {@code QQPromptBuilder}：从尾往前累加，一旦超预算就 {@code keepFrom = i + 1}。
     * 这里保持一致 —— <b>整条丢，不切半条</b>（半条历史比没有历史更容易把模型带偏）。
     * {@code budget <= 0} 或行数为 0 时原样返回（不改行为）。</p>
     */
    static List<JsonObject> trimHistory(List<JsonObject> rows, int budget) {
        if (budget <= 0 || rows == null || rows.isEmpty()) return rows;
        int total = 0;
        int keepFrom = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            total += J.s(rows.get(i), "content", "").length();
            if (total > budget) {
                keepFrom = i + 1;
                break;
            }
        }
        if (keepFrom <= 0) return rows;
        return new ArrayList<JsonObject>(rows.subList(keepFrom, rows.size()));
    }

    /**
     * 构造本轮用户消息（叠加 {@code arg} 槽位注入），并在闸门允许时<b>把图挂上去</b>（P0-4 S2）。
     *
     * <p><b>三道闸全过才挂</b>：① {@code models.mode == native}（这台模型能不能看 + 主人允不允许看）；
     * ② 本轮媒体事实里有 image 段；③ {@code mediaAttachMax > 0}。
     * 过了就把 URL 交给 {@link sair.v4.ai.Msg#userWithImages}（content 变成数组），
     * 否则原样 {@code msg("user", body)} —— 未过闸时这一轮的 messages 与改之前<b>逐字节相同</b>，
     * 只多一行 {@code attach:} 事实（见下）。
     *
     * <p><b>降级绝不静默</b>：本来该有图（事实里有 image 段）却一张都没挂上时，
     * 这里就补一行 {@code attach:{"state":"none","why":…}} —— 而且必须是<b>现在</b>补，
     * 不能等 {@code Loop} 第 1 轮跑完：那一轮要是直接出终稿，这一行就永远送不到她眼前，
     * 她就会凭空描述图里有什么（比"没有图"更糟）。
     * 纯文字消息（事实里一张 image 都没有）<b>不补</b> —— 否则每一轮普通聊天都多一行噪音。
     *
     * <p><b>挂上了的那一行由 {@code Loop} 追加</b>（{@link #ST_ATTACH}）：它的 note 是过去式
     * （"图已在本轮第 1 次请求里看过"），只有第 1 次请求真的返回之后才成立；
     * 而且同一时刻 {@code Loop} 会把图从 messages 里摘掉（{@code Loop} 每轮重发整个 messages，
     * 不摘就是每轮重发一遍图）。
     */
    public JsonObject user(Turn t, String text) {
        String body = text == null ? "" : text;
        String arg = prompts.inject().render(t == null ? Inject.GLOBAL : t.session(), Inject.ARG);
        if (Str.has(arg)) body = arg.trim() + "\n" + body;
        if (t == null) return msg("user", body);
        // ---- 原生喂图：三道闸 ----
        String media = Str.nz(t.get(ST_MEDIA, ""));
        JsonArray facts = sair.v4.qq.MediaAttach.parse(media);
        int max = conf.mediaAttachMax();
        long maxBytes = conf.mediaAttachMaxBytes();
        sair.v4.qq.MediaAttach.Result r =
                sair.v4.qq.MediaAttach.scan(facts, max, maxBytes);
        String mode = visionMode(selfModel(t));
        // 事实里连一张 image 都没有 = 这条消息本来就没有图可挂（普通聊天）⇒ 不产出 attach 行
        if (r.images() == 0) return msg("user", body);
        if (!MODE_NATIVE.equals(mode) || max <= 0 || r.empty()) {
            String why = !MODE_NATIVE.equals(mode)
                    ? (MODE_OFF.equals(mode)
                            ? sair.v4.qq.MediaAttach.NONE_VISION_OFF
                            : sair.v4.qq.MediaAttach.NONE_VISION_RELAY)
                    : (max <= 0 ? sair.v4.qq.MediaAttach.NONE_ATTACH_OFF
                                : sair.v4.qq.MediaAttach.NONE_ALL_SKIPPED);
            t.addSystem(sair.v4.qq.MediaAttach.noneFact(why, r, max));
            return msg("user", body);
        }
        t.put(ST_ATTACH, sair.v4.qq.MediaAttach.sentFact(r));
        return sair.v4.ai.Msg.userWithImages(body, r.urls());
    }

    /**
     * 这一轮真正在跑的模型：{@link #MODELS_SELF} 有就取它（子 Agent 带的 model 覆盖），
     * 否则 {@code Conf.resolveModel()}。与 {@link #facts} 读的是<b>同一个源、同一个默认</b>。
     */
    public String selfModel(Turn t) {
        if (t == null) return conf.resolveModel();
        String s = t.get(MODELS_SELF, "");
        return Str.has(s) ? s.trim() : conf.resolveModel();
    }

    private static JsonObject sys(Turn t, String text) {
        return msg("system", text == null ? "" : text);
    }

    private static JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content == null ? "" : content);
        return m;
    }
}
