package sair.v4;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sair.v4.auth.Caller;
import sair.v4.term.Sinks;
import sair.v4.ctx.Sink;
import sair.v4.ctx.Turn;
import sair.v4.hot.Skills;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Api;
import sair.v4.qq.Ev;
import sair.v4.qq.Inbound;
import sair.v4.qq.NoticeRender;

/**
 * QQ 入站网关（基板⑧ 的事件半边 + ④ 的调度胶水）。
 *
 * <h3>基板负责的最小判定</h3>
 * 只要"这条消息是不是直接冲着 AI 来的"：<b>主人说话 / 私聊 / 被 @</b> → 回。
 * 其它一切"要不要接话、要不要审核、要不要记录"，都交给技能（{@code on_message} 钩子）——
 * 技能把 {@code payload._handled} 置 true 表示"这条我接管了"，基板就不再自己回。
 *
 * <h3>内容判定：有文本<b>或</b>有媒体段</h3>
 * <p>"有没有内容"不等于"有没有文字"：一条只带 {@code [CQ:image,...]}（或 record/file/face/reply…）
 * 的消息同样是冲着 AI 来的（群里 @ 了机器人只发图、主人只发图、私聊只发语音/文件）。
 * 这类消息一律<b>照常触发一轮</b>，本轮由基板给出<b>结构化事实</b>（事实块与用户轮的
 * {@code media: [...]} 一行 JSON，元数据取自消息段本身），不编任何自然语言文案 ——
 * 怎么理解这些事实由提示词层负责（见 {@code prompts/identity.md} 的「只有媒体的消息」）。</p>
 *
 * <h3>本地黑名单：补 NapCat 的短板</h3>
 * <p>QQ 侧的拉黑/删好友管不到<b>群内互动</b>（NapCat 没有"群内屏蔽某人"这个动作），
 * 所以技能 {@code 好友管理} 的 {@code block} 除了尽力发平台动作，还会写一份<b>本地</b>黑名单
 * （kv {@code block:<qq>}，见 {@link #BLOCK_PREFIX}）。本地这份是<b>权威</b>：命中者的消息
 * <b>不触发 AI</b>（私聊与群内一视同仁），但<b>照常记进 grouplog</b> 留痕，控制台打一行
 * {@code [qq] skip reason=blocked} 说明"为什么不理他"。</p>
 *
 * <h3>日志口径（默认静默，只打"逻辑怎么走"）</h3>
 * <p>控制台<b>不是聊天记录的回声墙</b>：默认<b>一条消息正文都不打</b>，只打结构性事实 ——
 * {@code [qq] msg …}（这条消息是什么、算不算冲着 AI 来）、{@code [qq] skip reason=…}（为什么不接）、
 * {@code [qq] → lane=…}（交给哪条道）、{@code [lane] …}（调度器怎么处理）、
 * {@code [turn] …}（这轮几轮/几次工具/多久/成没成）、{@code [tool] …}（工具调用的规模）。
 * 判据：<b>这条日志能不能帮人看出"为什么走这条路"？不能就别打。</b>
 * 正文（消息文本、回复文本、工具结果）只在 {@code logVerbose=true} 时出现；
 * 错误、警告、权限阻断、启动/重载进度不受开关影响，永远在。</p>
 */
public final class QqGateway {

    /**
     * 本地黑名单的 kv 键前缀（{@code block:<qq>}，值非空 = 已拉黑）。
     *
     * <p>写方 = 技能 {@code 好友管理} 的 {@code block}/{@code unblock}；读方 = 本网关（入站拦截）
     * 与同一技能的 {@code blocklist}。两侧共用这一个常量，避免键名漂移。</p>
     */
    public static final String BLOCK_PREFIX = "block:";

    private final Boot boot;
    private final Conf conf;
    private final Out out;
    /** 分段发送口径（配置驱动的上限与停顿；见 {@link sair.v4.term.Segmenter}）。 */
    private final sair.v4.term.Segmenter seg;
    /**
     * 入站去重（{@code message_id} + 时间窗）。
     * <p>V3 有这道闸，V4 起先漏了：NapCat 重连重放 / 多连接 / 同一事件投递两次时，
     * 同一条消息会被答两遍（主人实测"重复回答"的经典来源）。</p>
     */
    private final sair.v4.qq.Seen seen = new sair.v4.qq.Seen();

    /**
     * <b>戳一戳回复</b>（本批 2026-09-22「空@兜底 + 戳一戳应答」）：就是<b>这一个字符</b>。
     *
     * <p><b>为什么写死在 Java 里、不走 {@code prompts/tools-index.md} 外挂 —— 这是 GM 裁定，不是随手决定</b>：
     * 它<b>不是"她对用户说的话"，而是系统级固定回执</b>，与仓库既有的"冻结措辞"同类
     * （{@code [权限阻断] } 那一类就是写死的）。外挂一个键会新增"缺键 ⇒ 她连问号都不回"的失败面，
     * 而这条路的全部价值恰恰是"<b>永远有一个最小回应</b>"。⇒ 裁定：<b>作为冻结回执写死在 Java 里，
     * 不新增配置键</b>。若甲方以后要改这个字，改动面 = <b>本处 1 行 + 部署一次</b>，代价可接受。</p>
     */
    private static final String POKE_REPLY = "？";

    /**
     * 戳回复的<b>内存</b>窗口：{@code session#user_id → [窗口起点 ms, 本窗口内被压掉的次数]}。
     *
     * <p>口径（<b>GM 裁定二</b>）：<b>同一会话、同一个人 60 秒内连戳只回一次</b>。
     * 键刻意用既有会话键 + 人（{@code group:<群号>#<qq>} / {@code qq:<qq>#<qq>}，会话键由
     * {@link Caller#session()} 现码，本处<b>不</b>自己拼会话字符串）—— <b>不同会话互不影响</b>。</p>
     *
     * <p><b>为什么不是"只按人"</b>（最初的写法）：同一个人在 A 群戳完、30 秒内在 B 群再戳就会被一起压掉，
     * 在 B 群表现得像"她不理人"。GM 明确不要这个副作用，故收窄成"人 + 会话"。</p>
     *
     * <p>窗口是<b>纯内存态</b>：进程重启即清空（如实记在报告里；不落库是有意的 —— 这是一条
     * 反刷屏的礼貌闸，不是业务账本）。</p>
     */
    private final Map<String, long[]> pokeWin = new java.util.concurrent.ConcurrentHashMap<String, long[]>();
    private static final long POKE_WINDOW_MS = 60000L;
    /** 窗口表的回收水位：超过它就顺手清掉已过期的条目（防止长期运行只增不减）。 */
    private static final int POKE_WIN_SWEEP = 512;

    /**
     * <b>外部来源行</b>（D1）在 {@code dialog.role} 上的取值：本账号<b>另一个机器人服务</b>发出来的私聊消息。
     *
     * <p>为什么不是 {@code "user"}：{@code dialog.role} 只有 {@code user}/{@code assistant} 两个"人"的位置，
     * 而 {@code CtxBuild.appendHistory} 把 {@code user} 行<b>逐字</b>注入这一轮的对话历史 —— 写 {@code user}
     * 会让模型把它读成"<b>对方说的</b>"（这正是 D1 要治的"错归属"，只是换了个人）。写一个第三种角色值
     * 之后：窗口（{@code ChatWindow}）照旧列它并标「其他」（见 {@code ChatWindow.FOREIGN}），
     * 而 role 历史那条路（只认 user/assistant）自然不收它 —— 它在模型眼里<b>永远不会</b>变成某个人说的话。
     * 代价如实记在报告里：私聊的外部行只经"窗口"可见，不重复进 role 历史。</p>
     */
    public static final String DIALOG_ROLE_FOREIGN = "foreign";

    /**
     * 本进程内"<b>我们自己真的发出去过</b>"的 {@code msg_id} 环的容量（D1 的正向证据第 ① 层）。
     *
     * <p>它比 {@code sent} 台账的保留量（{@link sair.v4.store.Store#DEF_SENT_KEEP} = 2000 行，
     * 按行数裁）大 ⇒ <b>进程还活着的时候</b>，"台账被裁掉的老消息"仍然认得出是我们发的
     * （见 {@link #ownKind}）。纯内存、有界（超出淘汰最旧的），重启即空 —— 那一段由台账与
     * {@link #recentMidKind} 兜。</p>
     */
    private static final int RECENT_SENT_MAX = 4096;
    /** {@link #recentMidKind} 只看目标会话<b>最近</b>多少行（有界、走会话索引的点查）。 */
    private static final int ECHO_SCAN_ROWS = 200;
    /** {@link #ownKind} 的三档结论：确定是外部来源 / 已经有她自己的行 / 确实是这次我们发的。 */
    private static final int OWN_FOREIGN = 0;
    private static final int OWN_ROW_SELF = 1;
    private static final int OWN_MINE = 2;
    /** 本进程内我们发过的 {@code msg_id}（LRU 有界环；读写都加锁，A 路来自任意线程）。 */
    private final Map<Long, Boolean> sentRing = new java.util.LinkedHashMap<Long, Boolean>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
            return size() > RECENT_SENT_MAX;
        }
    };

    public QqGateway(Boot boot) {
        this.boot = boot;
        this.conf = boot.conf();
        this.out = boot.out();
        this.seg = new sair.v4.term.Segmenter(conf);
    }

    // ==================== 日志口径 ====================
    //
    // 控制台不是聊天记录的回声墙：**默认只打"逻辑怎么走"的结构性事实，一条正文都不打**。
    //   [qq] msg session=group:123 type=group qq=456 at=1 master=0 chars=7 media=0   ← 这条消息走哪条路
    //   [qq] skip reason=not_addressed session=group:123                             ← 为什么没接
    //   [qq] → lane=group:123 high=0 chars=7                                         ← 交给哪条道
    //   [lane] session=group:123 decision=queued depth=1                             ← 调度器怎么处理（schedule.Lanes）
    //   [turn] session=group:123 rounds=2 tools=3 ms=4200 ok=1                       ← 这轮跑成什么样
    //   [tool] console ms=12 chars=345 ok=1                                          ← 工具调用的规模（tool.Registry）
    // 正文（消息文本、回复文本、工具结果）只在配置 logVerbose=true 时出现 —— 排障开关，不是默认行为。
    // 判据：这条日志能不能帮人看出"为什么走这条路"？不能就别打。

    /** 结构性事实行开关（配置 {@code logQq}，默认开）+ 类别开关（{@code logConsole} 里的 {@code msg}）。 */
    private boolean logQq() {
        try {
            return (conf == null || conf.getBool("logQq", true))
                    && (conf == null || conf.logOn("msg"));      // 默认不打：控制台只留 Agent 调用事件
        } catch (Throwable t) {
            return false;
        }
    }

    /** 正文级日志开关（配置 {@code logVerbose}，默认关）。 */
    private boolean verbose() {
        try {
            return conf != null && conf.getBool("logVerbose", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一行结构事实（dim 色，前缀 [qq]）。 */
    private void facts(String line) {
        if (out != null && logQq()) out.dim("[qq] " + line);
    }

    /**
     * <b>与 {@code [qq→] sent …} 同级的可见出口</b>：一行结构事实（dim 色，前缀 {@code [qq] }），
     * <b>只受"有没有 out"约束，不受 {@code logConsole} 的 {@code msg} 类别开关影响</b>。
     *
     * <p><b>为什么需要第二个出口</b>（2026-09-22 真机实测 + 本批根因定位，原文见
     * {@link #pokeFact(String)}）：{@link #facts(String)} 的口子是 {@code logQq()} =
     * {@code conf.getBool("logQq", true)} <b>且</b> {@code conf.logOn("msg")}；而 {@code logConsole}
     * 的出厂值是 {@code "tool,model"}（{@code Conf.DEF_LOG_CONSOLE}）⇒ <b>默认
     * {@code logOn("msg") == false}</b>。于是"已经发生、但用户看不见"的既成事实
     * （戳回复、语音转写）会整行被那两道类别开关挡掉 —— 现象看起来像"功能没做"。</p>
     *
     * <p>判据：<b>这件事是否已经发生且不可从别处看见</b>。是 ⇒ 走这个出口（一行、只有结构量、
     * 一个正文字符都不打）；不是（普通路由/跳过说明）⇒ 照旧走 {@link #facts(String)} 受类别管。
     * 这个出口<b>不改</b>任何全局日志配置：{@code logConsole} / {@code logQq} 的语义与出厂值
     * 一个字节没动。</p>
     */
    private void visibleFact(String line) {
        if (out != null) out.dim("[qq] " + line);
    }

    /**
     * 一行<b>戳回复</b>结构事实（{@code [qq] poke reply=…}）—— 与 {@code [qq→] sent …}
     * （{@code term.Sinks}）同级：<b>只受"有没有 out"约束，不受 {@code logConsole} 的
     * {@code msg} 类别开关影响</b>。
     *
     * <h3>为什么不走 {@link #facts(String)}（2026-09-22 真机实测 + 本批根因定位）</h3>
     * <p>{@code facts()} 的口子是 {@code logQq()} = {@code conf.getBool("logQq", true)}
     * <b>且</b> {@code conf.logOn("msg")}；而 {@code logConsole} 的出厂值是 {@code "tool,model"}
     * （{@code Conf.DEF_LOG_CONSOLE}）⇒ <b>默认 {@code logOn("msg") == false}</b>
     * ⇒ 戳回复那条事实行<b>一行都没进控制台</b>（真机 grep {@code poke reply} = 0 命中，
     * 只剩 {@code Link.call} 那条不受开关约束的 {@code [NapCat] 动作 …} 行）。</p>
     *
     * <p><b>根因不是级别</b>：{@code QqGateway.out} 就是 {@code boot.out()} 原对象
     * （{@code Boot.out()}），{@code SfwOut.print} 对任何 tone 都照印，dim 直达控制台 ——
     * 整行是在<b>调用之前</b>被上面那两道类别开关挡掉的（"供应商没错，闸口认错了类别"）。
     * 所以修法是给这条结构性事实换一个<b>与 {@code [qq→] sent} 同级</b>的出口，
     * <b>不动日志全局配置</b>：{@code logConsole} / {@code logQq} 的语义与出厂值一个字节没改，
     * {@code notice …} 那条既有事实行照旧受 {@code msg} 类别管。</p>
     *
     * <p><b>不带正文/昵称</b>：只有 {@code reply} / {@code user} / {@code gid} /
     * {@code suppressed} 四个结构量；每次戳<b>至多一行</b>（{@code reply=1} 与
     * {@code reply=0} 二选一，不叠加）。</p>
     */
    private void pokeFact(String line) {
        visibleFact(line);
    }

    /** 一行回合摘要（前缀 [turn]：这轮跑了多久/几轮/几次工具/成没成）；默认不打。 */
    private void turn(String line) {
        try {
            if (out == null) return;
            if (conf != null && !conf.logOn("turn")) return;
            out.dim("[turn] " + line);
        } catch (Throwable ignored) {
        }
    }

    /** 一行正文（只有 logVerbose 才出）。 */
    private void detail(String line) {
        if (out != null && logQq() && verbose()) out.dim("[qq] " + line);
    }

    public void onEvent(JsonObject raw) {
        if (raw == null) return;
        try {
            Ev ev = Ev.parse(raw);
            if (ev == null) return;
            String post = ev.postType();
            if (ev.isHeartbeat() || "meta_event".equals(post)) {
                // 心跳/元事件不解释任何路由，默认一行不打（要排障再开 logVerbose）
                detail("meta type=" + post + " " + Str.cut(J.json(raw), 160));
                return;
            }
            if ("request".equals(post)) {
                handleRequest(raw, ev);
                return;
            }
            if ("message".equals(post)) {
                handleMessage(raw, ev);
                return;
            }
            if ("notice".equals(post)) {
                // N1（主人 2026-09-16 裁定「通知接入，作为特殊消息记录存形式在，不必当即触发响应，
                // 而是等它响应后从窗口拿到这些」）：通知**只落库然后 return** —— 零触发。
                // 这里是本分支的全部：不进 shouldAnswer、不进 pluginVote/_reply、不派
                // Skills.ON_MESSAGE、不调模型、不发消息。以前这一整类事件是 facts("…ignored") 丢掉的。
                handleNotice(ev);
                return;
            }
            if ("message_sent".equals(post)) {
                // 【不是入站，绝不回】这是"本账号自己发出去的消息"事件：
                // 私聊分支对任何私聊都返回"该回"，一旦把它当入站，机器人就会对自己刚发的那条再起一轮
                //（主人实测的"重复回答"来源之一）。
                //
                // ★ P0-1 起这一步多了一件事：**落一行自我标记行**（让她看得见自己）。零触发纪律
                // 与下面的 notice 分支逐字同一份 —— 只写一行、然后 return：
                // 不进地址门（shouldAnswer）、不进触发投票（pluginVote）、不派 ON_MESSAGE、
                // 不调模型、不发消息。所以原来那条"不做入站"的语义**一个字都没变**，
                // 现在多的只是"顺手记一笔账"。
                handleMessageSent(ev);
                return;
            }
            facts("event=" + post + " ignored（基板不管这类事件）"
                    + (verbose() ? " " + Str.cut(J.json(raw), 160) : ""));
        } catch (Throwable t) {
            if (out != null) out.err("[qq] 事件处理异常: " + t);
        }
    }

    // ==================== 通知（N1：特殊消息记录） ====================
    //
    // 主人 2026-09-16 裁定：「通知接入，作为特殊消息记录存形式在，不必当即触发响应，而是等它响应后
    // 从窗口拿到这些」。落地口径（对外契约逐条见 probe\ProbeNotices 与 tmp\n1\REPORT.md）：
    //   * 一行一条，与消息**同表**：群通知 → grouplog、私聊通知 → dialog；
    //   * **绝不**触发任何回合（不进地址门/触发投票/技能钩子/模型/发送）—— 见 onEvent 的 notice 分支；
    //   * 标签由 qq\NoticeRender 一处产出，这里一个字都不拼；
    //   * 撤回**不改**那条被撤回的原行（日志只增不减），[撤回] 自成一行；
    //   * 去重键前缀 n:，与消息键（裸 msg_id）构造性隔离。

    /**
     * 一条通知事件 → 一行"特殊消息记录"（<b>只落库，不做别的</b>）。
     *
     * <p><b>零触发</b>：本方法只读库（查昵称）、只写一行；不派钩子、不投票、不调模型、不发消息。</p>
     *
     * <p><b>权限</b>：落库的是<b>她自己</b>的行（主体即她本人 / {@code SYSTEM}），
     * 这台机器上只有两种人 —— 主人与她本人<b>恒全权</b>，{@code ALLUSER} 才按技能管控表
     * （身份 × op → Ban / Run / 空）受管。这里是她写自己的库，<b>不是技能，不判</b>。</p>
     */
    private void handleNotice(Ev ev) {
        if (ev == null || boot == null || boot.store() == null) return;
        String type = Str.lower(Str.trim(ev.noticeType()));
        if (type.isEmpty()) return;                 // 没有 notice_type 的 notice 事件：不是一条可留痕的通知
        // 去重（口径同消息那一支，键不同）：同一事件重复投递 ⇒ 只留一行。
        // 键里必须带 notice_type/sub_type —— 只按 message_id 会把 group_recall 与 group_msg_emoji_like
        // 当成同一条（两者都带 message_id），只按 user_id 会把同一个人连续两次通知吞掉。
        String nkey = "n:" + type + "|" + Str.lower(Str.trim(ev.subType())) + "|" + ev.groupId() + "|"
                + ev.userId() + "|" + ev.operatorId() + "|" + ev.targetId() + "|" + ev.messageId() + "|" + ev.time();
        if (seen.dup(nkey, System.currentTimeMillis(), dedupWindowMs())) {
            boolean on = false;
            try { on = conf != null && conf.logOn("notice"); } catch (Throwable ignored) {}
            if (on && out != null) {
                out.dim("[qq] notice skip reason=dup session=" + nkey
                        + "（窗口内已经记过这条通知；累计挡下 " + seen.hits() + " 条）");
            }
            return;
        }
        NoticeRow nr = noticeRow(ev, type);
        if (nr == null) return;
        String table = nr.dialog ? "dialog" : "grouplog";
        boot.store().put(table, nr.row);
        // 结构事实一行（只有"哪个会话、哪类通知、多少字"，**没有标签正文**）：通知落没落库、
        // 落哪张表，看日志就能核。正文（标签）只在库里，窗口读它，控制台不当回声墙。
        facts("notice session=" + sessionOf(ev) + " type=" + type
                + (Str.blank(Str.trim(ev.subType())) ? "" : "/" + Str.lower(Str.trim(ev.subType())))
                + " table=" + table + " chars=" + Str.nz(nr.label).length());
        dispatchNotice(ev, type, nr.row);
        // ★ 本批 2026-09-22「戳一戳应答」：通知分支里**唯一**一条会往外说话的路。
        //   刻意排在 dispatchNotice **之后**：既有那条"落库 → 结构事实 → 派记账钩子"的顺序
        //   一个字节都不动（情绪技能的戳计数靠的就是那条钩子，本批不碰情绪记账）。
        pokeReply(ev);
    }

    /**
     * 有人<b>戳的是她本人</b> ⇒ 直接回一个「？」（<b>不走模型</b>）。
     *
     * <h3>这条路的纪律（与消息路彻底分开，工单 P2 第 1/2 条）</h3>
     * <ul>
     *   <li><b>判据是结构字段</b>：{@link sair.v4.qq.NoticeRender#pokeAtSelf(Ev, long)}
     *       （{@code notice_type=notify} + {@code sub_type=poke} + {@code target_id == self_id}）——
     *       与标签产法<b>共用同一处</b>判据，<b>不</b>拿 {@code content} 里的
     *       {@code [戳一戳]} 文案当判据（标签是别处产出的，用它就是自证）；</li>
     *   <li><b>零回合</b>：不进 {@code shouldAnswer}（地址门）、不进 {@code pluginVote}（触发投票）、
     *       不派 {@code Skills.ON_MESSAGE}、<b>不调模型</b>、不提交任何回合 —— 本方法在 notice 分支里，
     *       那几处在 {@code handleMessage} 里，构造上够不着；</li>
     *   <li><b>走同一道出站闸</b>：与消息回复同一个 {@link Sinks.QqSink} + {@code boot.guardedApi()}
     *       ⇒ 整条事实块闸（G1 {@code qq.InternalFacts}）、自我记账头、工具调用标记、出站红线、
     *       一轮条数上限、内部字面量闸全部照旧生效（<b>不</b>自己拼 NapCat 调用）；</li>
     *   <li><b>同一会话、同一人 60 秒只回一次</b>（{@link #pokeWin}；键 = {@code session#qq}，
     *       内存态、重启即清。GM 裁定二：不同会话互不影响）。</li>
     * </ul>
     *
     * <p><b>一条结构事实行</b>（<b>不含正文/昵称</b>，只有号码与计数）：
     * {@code [qq] poke reply=1|0 user=<qq> gid=<群号或 0> suppressed=<本窗口内被压掉的次数>}。
     * {@code reply=0} = 这次被窗口压掉；{@code suppressed} 在 {@code reply=1} 那一行上带的是
     * <b>上一个窗口</b>压掉的次数（窗口刚重置，本窗口此刻必然是 0）—— 这样一行就能看出
     * "刚才被连着戳了几下才让我回这一声"。{@code gid} 就是窗口键里那个会话的群号（私聊为 0），
     * 所以"是哪个会话被压掉的"这一行自己说得清。</p>
     *
     * <p>不喂情绪状态机：本方法<b>不</b>碰任何情绪库/账本；戳的记账照旧只由 {@code dispatchNotice}
     * 那条记账钩子（{@code Skills.ON_NOTICE}）负责，本批一个字都没动它。</p>
     */
    private void pokeReply(Ev ev) {
        try {
            if (ev == null || boot == null) return;
            if (!sair.v4.qq.NoticeRender.pokeAtSelf(ev, ev.selfId())) return;
            long qq = ev.userId();
            if (qq <= 0L || qq == ev.selfId()) return;     // 自己戳自己：不回
            Caller c = callerOf(ev);
            if (c == null) return;
            // 本地黑名单是"我们这边不处理"的权威（与消息路同一条纪律，见类注释）：
            // 被本地拉黑的人戳她，同样一个字都不回。工单没写这一条，这里按既有纪律办，报告里登记。
            if (blocked(qq)) {
                facts("poke skip reason=blocked user=" + qq
                        + "（本地黑名单；戳已记入 " + (ev.isGroup() ? "grouplog" : "dialog") + "）");
                return;
            }
            long gid = ev.isGroup() ? ev.groupId() : 0L;
            long now = System.currentTimeMillis();
            if (pokeWin.size() > POKE_WIN_SWEEP) sweepPokeWin(now);
            // ★ GM 裁定二：窗口键 = **会话 + 人**（不是只按人）—— 同一会话同一个人 60 秒只回一次，
            //   不同会话互不影响。会话键取既有 Caller.session()（本处不自己拼会话字符串）。
            String key = c.session() + "#" + qq;
            long[] w = pokeWin.get(key);
            if (w != null && now - w[0] < POKE_WINDOW_MS) {
                w[1] = w[1] + 1L;
                pokeFact("poke reply=0 user=" + qq + " gid=" + gid + " suppressed=" + w[1]
                        + "（同一会话同一人 " + (POKE_WINDOW_MS / 1000L) + " 秒内只回一次）");
                return;
            }
            long suppressed = w == null ? 0L : w[1];       // 上一个窗口压掉的次数（窗口已过期）
            pokeWin.put(key, new long[] { now, 0L });
            pokeFact("poke reply=1 user=" + qq + " gid=" + gid + " suppressed=" + suppressed);
            Sink sink = new Sinks.QqSink(boot.guardedApi(), c, out, false, seg, boot.favor());
            sink.say(POKE_REPLY);
        } catch (Throwable t) {
            if (out != null) out.warn("[qq] 戳回复失败（不影响这条通知的留痕）：" + t);
        }
    }

    /** 清掉过期的窗口条目（只在表变大时顺手做一次；口径与 {@link #pokeReply} 同一个窗口长度）。 */
    private void sweepPokeWin(long now) {
        java.util.Iterator<Map.Entry<String, long[]>> it = pokeWin.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, long[]> e = it.next();
            long[] v = e.getValue();
            if (v == null || now - v[0] >= POKE_WINDOW_MS) it.remove();
        }
    }

    /** 一条通知落在哪个会话键上（群 → {@code group:<群号>}，私聊 → {@code Caller.session()}）。 */
    private String sessionOf(Ev ev) {
        if (ev.isGroup() && ev.groupId() > 0L) return "group:" + ev.groupId();
        Caller c = callerOf(ev);
        return c == null ? "" : c.session();
    }

    /**
     * 把一条通知派给技能钩子 {@link Skills#ON_NOTICE}（<b>先落库，再派发</b>）。
     *
     * <p><b>硬契约（与"零触发"同一条）</b>：这条路<b>只能记账</b> ——</p>
     * <ul>
     *   <li>不读 {@code _handled}：钩子置它<b>没有</b>任何效果（这条路上没有"接管"这回事）；</li>
     *   <li>不读 {@code _reply}、不进 {@code shouldAnswer}、不碰 {@code pluginVote}、不提交任何回合；</li>
     *   <li>落点是 {@link sair.v4.term.Sinks.SilentSink}：{@code h.say(...)} 能调，<b>一个字都发不出去</b>
     *       （不传 {@code null} 的 Turn —— 那条路在 {@code Boot.host} 里会退回控制台落点）；</li>
     *   <li>钩子抛异常由 {@code Skills.invokeHook} 吞掉记日志，绝不影响"通知已经留痕"这件事
     *       （所以派发刻意放在 {@code store().put} <b>之后</b>）。</li>
     * </ul>
     *
     * @param ev   入站通知事件
     * @param type 已归一化的 {@code notice_type}
     * @param row  刚写下的那一行（{@code payload} 里原样带上，技能不必再查库）
     */
    private void dispatchNotice(Ev ev, String type, JsonObject row) {
        try {
            if (boot.skills() == null) return;
            Caller c = callerOf(ev);
            JsonObject payload = ev.raw().deepCopy();
            payload.add("_caller", callerJson(c));
            payload.addProperty("_handled", false);
            payload.addProperty("_notice_type", type);
            payload.addProperty("_notice_sub_type", Str.lower(Str.trim(ev.subType())));
            JsonObject stored = row.deepCopy();
            stored.addProperty("_table", ev.isGroup() && ev.groupId() > 0L ? "grouplog" : "dialog");
            payload.add("_record", stored);
            // Turn 只用来让 h.call(...) 这类"记账/查询"有落点；sink 是静默的 ⇒ 说不出话。
            Turn t = new Turn(c, sair.v4.term.Sinks.SilentSink.I);
            t.markInternal();
            boot.skills().dispatch(Skills.ON_NOTICE, payload, c, t);
        } catch (Throwable t) {
            if (out != null) out.warn("[qq] 通知钩子派发失败（通知已留痕，忽略）：" + t);
        }
    }

    /**
     * 一行待落库的通知记录（列与消息行同一套；<b>不改表结构</b>）。
     *
     * <p>私聊那一支的会话键走 {@link Caller#session()} 现码 —— 与私聊消息<b>同一个产法</b>
     * （同理 {@code role} 取 {@link NoticeRender#DIALOG_ROLE}，与 {@code Agent.ask} 落 user 行那一个），
     * 这里<b>不自己拼</b>任何会话字符串。</p>
     */
    private static final class NoticeRow {
        final JsonObject row;
        final boolean dialog;
        /** 这一行的标签（就是 {@code content}；留一份给日志/诊断，免得再渲染一遍）。 */
        final String label;
        NoticeRow(JsonObject row, boolean dialog, String label) {
            this.row = row;
            this.dialog = dialog;
            this.label = label;
        }
    }

    /**
     * 通知 → 一行记录（{@code null} = 认不出通知类型 / 拼不出会话键 ⇒ 不落行）。
     *
     * @param ev   入站通知事件
     * @param type 已经归一化（小写去空白）的 {@code notice_type}
     */
    private NoticeRow noticeRow(Ev ev, String type) {
        Caller c = callerOf(ev);
        String label = NoticeRender.label(ev, ev.selfId(), boot.store(), c);
        if (Str.blank(label)) return null;
        JsonObject extra = NoticeRender.extra(ev);
        if (extra == null) return null;
        // 被指向的那条消息的 id（只在 msg_id 列没写它的时候补进 extra：结构化、可过滤，不改表结构）
        if (NoticeRender.msgIdIsSubject(type) && ev.messageId() > 0L) extra.addProperty("message_id", ev.messageId());
        JsonObject row = new JsonObject();
        row.addProperty("ts", System.currentTimeMillis());
        row.addProperty("user_id", ev.userId());        // 动作发起者（撤回/禁言看 operator_id，见 NoticeRender）
        row.addProperty("content", label);              // 标签：模型在窗口里看到的就是这一串
        // msg_id：通知自带的 message_id（没有就 0）。两类例外见 NoticeRender.msgIdIsSubject ——
        // 它们的 message_id 是"被指向的那条真消息"，写进 msg_id 会让窗口的 msg_id 去重把两行压成一行。
        row.addProperty("msg_id", NoticeRender.msgIdIsSubject(type) ? 0L
                : (ev.messageId() > 0L ? ev.messageId() : 0L));
        row.add("extra", extra);
        if (ev.isGroup() && ev.groupId() > 0L) {
            row.addProperty("group_id", ev.groupId());
            // 昵称：**与标签同一个产法**（NoticeRender 只查库；查不到 ⇒ #<user_id>）。
            // 刻意不用 Caller.name()：通知事件没有 sender 字段，senderName() 给的是 QQ 号本身。
            row.addProperty("nickname", NoticeRender.nick(ev.userId(), boot.store(), c));
            return new NoticeRow(row, false, label);
        }
        String session = c == null ? "" : c.session();
        if (Str.blank(session)) return null;
        row.addProperty("session", session);
        row.addProperty("role", NoticeRender.DIALOG_ROLE);
        row.addProperty("tokens", 0);
        return new NoticeRow(row, true, label);
    }

    // ==================== 自我记账（P0-1「让她看得见自己」） ====================
    //
    // 问题（真机实测）：她自己发出去的消息在她自己的记账里**一行都没有** —— `sent` 台账里 52 条
    // message_id，0 条能在 `grouplog.msg_id` 里找到。于是"本会话最近 N 条聊天记录"与对话历史里，
    // 她看不见自己刚说过什么、发过哪张图。
    //
    // 落地口径（对外契约见 SelfEcho 的类注释）：
    //   * **唯一写库口** = `selfEcho(...)`：A 路（发送那一刻的 SentTap）与 B 路（message_sent 旁路）
    //     **都只经它**，两条路**共用同一把去重键** `s:<msg_id>`（与入站的裸 msg_id、与通知的 `n:…`
    //     构造性隔离）；
    //   * 一行一条，与消息**同表**：群 → grouplog、私聊 → dialog（role=assistant）；
    //   * **零触发**：这一行不进地址门/触发投票/技能钩子/模型/发送（与 N1 通知同一纪律）；
    //   * 标记行的**全部拼接**在 `qq\SelfEcho` 一处；段标签借 `MediaRender.render`（唯一产法）；
    //   * 去重三层：内存 `Seen`（键 `s:`，挡同进程重复投递）→ 持久 `Store.sentKnown`
    //     （走 `idx_sent_msgid`，挡跨重启重放）→ 落行带 `extra.self=true`（读侧可辨）。
    //
    // ★ D1（2026-09-28，本批修的缺陷）：**同一个 selfId 下有三个机器人服务共用一个 QQ 账号**
    //   （我们 / 云崽 / 舞立方，NapCat 向三家各发一条反向 WS，并且把 `message_sent` **广播**给所有连接）。
    //   于是"另一个机器人服务"发出去的消息，会以**和我们自己发的完全同形**的 `message_sent` 帧
    //   （`user_id == selfId`）送到这里。改之前 `handleMessageSent` 只挡 `uid != selfId` ⇒ 挡不住它们；
    //   接下去的 `selfEcho` 判据是"台账里有没有这条" ⇒ 查不到就当**她说的**写一行
    //   （`grouplog` 里 `user_id=selfId` + `[我发的 msg_id=…]` 头；私聊那侧 `dialog.role=assistant`）
    //   ⇒ **别家机器人的话被记成她说的**，窗口显示「我」，还进她的记忆/印象素材。
    //   现在的口径（判据都写死在这里，**没有任何"关键词→归属"的规则**）：
    //     * **只有正向证据**才走"她的行"（{@link #ownKind}）：① 这次发送自带台账行（A 路）；
    //       ② 本进程内我们发过的 id（内存环，台账被裁也在）；③ 台账点查命中；
    //       ④ 台账已经裁过时，目标会话最近若干行里已经有这个 id 的**自我行**；
    //     * 拿不到正向证据 ⇒ **不写她的行**，改写**外部来源行**（{@link #foreignEcho}：
    //       无自我记账头、`extra.self` 不为 true、`extra.foreign=true`、窗口标「其他」）；
    //     * 台账"从没裁过"时（`count(sent) < DEF_SENT_KEEP`）不在台账里 = **确定不是我们发的**
    //       （不做任何猜测）：这是把"我们自己的老消息被误判成外部"这个方向堵死的那一步。
    //   A 路（`sentId > 0`）一字未改：它仍旧是"这条是我们发的"的铁证。
    //

    /** 记一笔"这个 id 是我们发的"（正向证据的内存层；A 路与已确认的 B 路都写它）。 */
    private void rememberSent(long msgId) {
        if (msgId <= 0L) return;
        try {
            Long k = Long.valueOf(msgId);
            synchronized (sentRing) {
                sentRing.remove(k);              // 重插 ⇒ 它挪到最年轻的一端（LRU）
                sentRing.put(k, Boolean.TRUE);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 这个 id 是不是本进程内我们发过的（纯内存，无 IO）。 */
    private boolean recentSent(long msgId) {
        if (msgId <= 0L) return false;
        try {
            synchronized (sentRing) {
                return sentRing.containsKey(Long.valueOf(msgId));
            }
        } catch (Throwable t) {
            return false;
        }
    }
    // ★ 自我识别（A 路与 B 路怎么认出"这一条我已经记过了"）—— 这一段的结论是被实测逼出来的，
    //   写清楚免得后人再踩一次：
    //   朴素做法是"落行前先查 `sent` 台账里有没有这个 msg_id"。**它会让 A 路永远不落行**：
    //   `Boot.sentTap()` 的第①步（`sentAdd`）本来就已经把这一行插进 `sent` 了，于是紧接着的
    //   `sentKnown(msgId)` 必然为 true，A 路每次都从"台账已记过"那一支 return。
    //   （本轮实测现场：`Store.sentKnown=true` 对**刚发出去**的 id 也成立，A 路 6 次发送 0 行落库。）
    //   正确的自我识别是**那次 insert 的返回值**：`Store.sentAdd` 本来就 `return db.insert(...)`，
    //   `Boot` 把它当 `sentId` 交给 `selfEcho` —— `sentId > 0` ⇔ "这次发送已经有台账行" ⇔
    //   这正是 A 路；B 路没有台账行（它压根没走 `Api.call`），给 `0`，于是它去查
    //   `Store.sentKnown(msgId)`（持久、跨重启、走 `idx_sent_msgid` 点查）决定"要不要补记"。
    //   两条路因此合流成一行，而且**都不需要动 `sent` 表的语义、也不新增任何索引**。

    /**
     * 记一行"她自己发的消息"（<b>唯一写库口</b>：A 路与 B 路都只经这里）。
     *
     * <p><b>防双写（硬要求）</b>，三层各挡什么，逐条写清：</p>
     * <ol>
     *   <li><b>内存 {@code Seen}，键 {@code s:<msg_id>}</b> —— 挡"同一进程内短窗口的重复投递"
     *       （NapCat 重连重放 / 多连接）。与入站的裸 {@code msg_id} 键、通知的
     *       {@code n:…} 键<b>构造性隔离</b>（不同串，永不相等）；</li>
     *   <li><b>持久 {@code Store.sentKnown(msg_id)}</b> —— 走现成索引 {@code idx_sent_msgid}
     *       （<b>不</b>用 {@code grouplog.msg_id} 的存在性检查：那列没有索引，实测 61.8 ms 全表扫）。
     *       它是<b>SQLite 表</b>，所以"同一 {@code msg_id} 只落一行"这条<b>跨重启</b>也成立 ——
     *       这正是 B 路需要的：A 路记账后进程重启，NapCat 再把同一条 {@code message_sent} 推一遍；</li>
     *   <li>落行带 {@code extra.self=true} —— 读侧（{@code ChatWindow.who} / 技能线）据此可辨，
     *       是最后一道"可辨别"而不是"可阻挡"。</li>
     * </ol>
     *
     * <p><b>零触发</b>：本方法只读 {@code conf}、读 {@code Seen}、{@code store.put}、
     * 打一行 {@code facts}。没有：{@code shouldAnswer}、{@code pluginVote}、
     * {@code Skills.dispatch}/{@code invokeHook}（任何 {@code ON_*}）、{@code Agent.ask}/{@code Loop}、
     * {@code Api.call}（发送）、{@code Sinks.*}、{@code Inbound.remember}、任何 {@code Turn}。
     * ⇒ <b>挂图通路够不着</b>：{@code media:} 事实只在 {@link #handleMessage} 里造
     * （它把 {@code mediaFacts} 的输出交给 {@code Turn}），本方法只把 {@code mediaFacts} 的
     * <b>输出</b>喂给 {@code MediaRender.render} 做标签，<b>绝不</b>把它交给任何上下文。</p>
     *
     * <p><b>权限口径与通知留痕（N1）同一份</b>：写的是<b>她自己</b>的行（主体即她本人 / {@code SYSTEM}），
     * 这台机器上只有两种人 —— 主人与她本人<b>恒全权</b>，{@code ALLUSER} 才按技能管控表
     * （身份 × op → Ban / Run / 空）受管。这里是她写自己的库，<b>不是技能，不判</b>。</p>
     *
     * @param action  真实动作名（A 路 = {@code send_group_msg}/{@code send_private_msg}/{@code send_msg}；
     *                B 路 = {@code message_sent}）
     * @param msgId   本次真发出去的消息 id（{@code <=0} ⇒ 不落行：没有真 id 就没有"对应哪条消息"）
     * @param params  本次发送的完整参数（A 路 = {@code rewrite} 之后的副本；B 路 = 由事件合成）；
     *                只为渲染正文/段标签而读，<b>不</b>落库
     * @param sentId  {@code sent} 台账行主键；<b>{@code <=0} = 这次没有台账行</b>（B 路就是）
     * @param preview 出站正文的纯文本预览（A 路给、B 路为空串）—— <b>只用于一行 dim 诊断</b>，
     *                绝不进 {@code content}（{@code content} 一律由 {@link sair.v4.qq.SelfEcho} 现场渲染，
     *                免得同一件事有两个真相）
     */
    public void selfEcho(String action, long msgId, JsonObject params, long sentId, String preview) {
        if (boot == null || boot.store() == null) return;      // 六库没装配：不记（与 N1 同）
        if (msgId <= 0L) return;                               // 没有真 id 就没有"③ 对应哪条消息"
        // ★ D1：A 路的 `sentId > 0` 是"这条是我们发的"的**铁证** ⇒ 立刻进内存环
        //   （于是台账以后被裁掉、同一个 id 再以 message_sent 回来时，第 ② 层还认得出）。
        if (sentId > 0L) rememberSent(msgId);
        String sess = echoSession(params);                     // 落点会话键（归一成 Caller.session() 形状）
        boolean group = sess.startsWith("group:");
        if (!group && !sess.startsWith("qq:")) {
            // 只认两种落点：推不出会话（Api.sessionOf 给空串）或控制台。
            // 控制台的"发送"其实不走 Api.call（ConsoleSink 直接打控制台）⇒ 实际不会出现；
            // 真出现也跳过：dialog 的 console 会话是"主人 vs 她"的对话，写标记行只是噪声。
            facts("self_echo skip reason=no_session action=" + action + " msg_id=" + msgId);
            return;
        }
        // ① 内存层：同一进程内短窗口的重复投递（键前缀 s: 与裸 msg_id / n:… 构造性隔离）
        if (seen.dup("s:" + msgId, System.currentTimeMillis(), dedupWindowMs())) {
            facts("self_echo skip reason=dup msg_id=" + msgId + "（窗口内已经记过这条发送）");
            return;
        }
        // ② 持久层：跨重启的重复投放。判据是"**这次发送**在台账里有没有一行"
        //    （sentId > 0 = 有），走的是现成索引 idx_sent_msgid 的那次 insert 的返回值 ——
        //    不查 grouplog.msg_id（那列没有索引，实测 61.8 ms 全表扫，见 Store.sentKnown 的注释）。
        //    ★ 为什么用 sentId 而不是 sentKnown(msgId)：见类注释里的"自我识别"一段。
        if (sentId <= 0L) {
            try {
                if (boot.store().sentKnown(msgId)) {
                    rememberSent(msgId);                       // ★ D1：台账点查命中 = 正向证据 ⇒ 进内存环
                    facts("self_echo skip reason=sent_known msg_id=" + msgId + "（台账已记过这条）");
                    return;
                }
            } catch (Throwable t) {
                if (out != null) out.warn("[qq] 自我记账：台账点查失败（继续记，按未记过处理）: " + t);
            }
        }
        // ③ 渲染：正文 + 段标签（合成 Ev ⇒ 唯一产法；合成失败绝不抛，降级成"只有头部"）
        sair.v4.qq.Ev sev = sair.v4.qq.SelfEcho.evOf(params);
        String content = sair.v4.qq.SelfEcho.content(msgId,
                sair.v4.qq.SelfEcho.textOf(sev),
                sair.v4.qq.SelfEcho.labels(sev, mediaFacts(sev)));
        String table = group ? "grouplog" : "dialog";
        // 她自己的留痕（主体即她本人）：恒全权，不是技能 op ⇒ 不判，直接落。
        JsonObject row = new JsonObject();
        row.addProperty("ts", System.currentTimeMillis());
        if (group) {
            row.addProperty("group_id", groupIdOf(sess));
            row.addProperty("user_id", conf.selfId());     // 甲方①"这是她发的"的结构化落点
            row.addProperty("nickname", "");               // 不编名字；窗口靠 who() 判自我
            // msg_id 是 TEXT 列（EnvLibs.grouplog），入站那一路写的是 long（QqGateway:398）
            // ⇒ 这里写十进制串，两者落库后读回来的字符串形状一致（同一口径，见 REPORT 实测）
            row.addProperty("msg_id", String.valueOf(msgId));
        } else {
            row.addProperty("session", sess);              // Caller.session() 形状：qq:<QQ>
            row.addProperty("role", "assistant");          // 她说的（ChatWindow 据此渲染"我"）
            row.addProperty("tokens", 0);
        }
        row.addProperty("content", content);
        row.add("extra", echoExtra(action, sentId));
        boot.store().put(table, row);
        rememberSent(msgId);            // ★ D1：这一行落库 = 这条确实是我们发的 ⇒ 进内存环（B 路补记的那一支）
        // 结构事实一行（只有"哪个会话、哪个动作、多少字"，**没有正文**）：
        // 与 N1 的 facts 行同一形状 —— 控制台不当聊天记录的回声墙。
        facts("self_echo session=" + sess + " action=" + action + " table=" + table
                + " msg_id=" + msgId + " chars=" + content.length()
                + (verbose() ? " preview=" + Str.cut(Str.oneLine(preview), 60) : ""));
        // ★ 到此为止：不派钩子、不投票、不调模型、不发消息（零触发，见方法注释）。
    }

    /**
     * A/B 两路共用的 {@code extra}：{@code {"self":true,"action":…,"sent_id":…}}。
     *
     * <p>{@code sent_id} = {@code sent} 台账行主键（{@code Store.sentAdd} 的返回值，A 路直接带过来）
     * ⇒ 有了它就能反查 {@code sent.message_id}（撤回用同一把 id）。
     * <b>{@code <=0} 就整键不写</b>（不落空壳，与 N1 不写空 {@code sub_type} 同一口径）。</p>
     *
     * <p><b>为什么是"带过来"而不是在这里现查</b>：A 路手上已经有那个 id（{@code sentAdd} 的返回值），
     * B 路则<b>没有</b>台账行。现查（{@code Store.sentIdOf}）看似更统一，实际会把 A 路带回
     * "自己刚插的那一行"这个先有鸡还是先有蛋的坑里 —— 见下面"自我识别"一段。</p>
     *
     * <p><b>寿命</b>：{@code sent} 台账只保留最近 2000 行 ⇒ 越界即失效（见 {@link sair.v4.qq.SelfEcho} 类注释）。</p>
     *
     * <p>{@code action} 只可能是三个已知动作名或 {@code message_sent}（B 路），
     * 前者由 {@code Api.SEND_ACTIONS} 白名单保证 —— 这里仍过一次 {@code Str.lower}，
     * 免得同一次发送在两条路上落出两种大小写。</p>
     */
    private JsonObject echoExtra(String action, long sentId) {
        JsonObject extra = new JsonObject();
        extra.addProperty("self", true);
        extra.addProperty("action", Str.lower(Str.trim(action)));
        if (sentId > 0L) extra.addProperty("sent_id", sentId);
        return extra;
    }

    /**
     * 标记行落在哪个会话上（A/B 两路共用的<b>唯一</b>归一）。
     *
     * <p>两路的入参形状<b>不一样</b>，而它们必须落同一个键，所以归一只在这一处做：</p>
     * <ul>
     *   <li>A 路：{@code Api.SentTap} 给的 {@code session}（{@code Api.sessionOf} 的产出：
     *       群 {@code group:<gid>}、私聊 {@code user:<QQ>}）—— 而 {@code Caller.session()} 私聊给的是
     *       {@code qq:<QQ>}（{@code Caller.java:150-153}），窗口（{@code tailDialog}）与历史
     *       （{@code appendHistory}）用的都是后者 ⇒ <b>不归一就永远读不到这一行</b>。
     *       ⚠ 这个参数从 {@code params} 里现取（见 {@link #echoSession(JsonObject)}），
     *       所以两路都从 {@code message_sent} 事件的 {@code message_type} 判据取，
     *       不依赖 tap 那一个可能为空的 {@code session} 串。</li>
     *   <li>B 路：事件本身就是消息形状（{@code message_type} + {@code group_id}/{@code user_id}）。</li>
     * </ul>
     *
     * <p>认不出（既没有群号也没有对端 QQ）⇒ 空串 ⇒ 调用方跳过标记行（只记台账）。</p>
     */
    private static String echoSession(JsonObject params) {
        try {
            if (params == null) return "";
            long g = J.l(params, "group_id", 0L);
            if (g > 0L) return "group:" + g;
            long u = J.l(params, "user_id", 0L);
            return u > 0L ? ("qq:" + u) : "";
        } catch (Throwable t) {
            return "";
        }
    }

    /** {@code group:<gid>} → 群号（认不出 0；与 {@code ChatWindow.groupId} 同一写法）。 */
    private static long groupIdOf(String session) {
        try {
            return Long.parseLong(session.substring("group:".length()).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * B 路（{@code message_sent} 旁路）：把"她自己发的那条"也记进她自己的账。
     *
     * <p><b>为什么它是旁路、主路是 A</b>（口径按路径推断，<b>不</b>按那个被证伪的计数）：
     * {@code message_sent} 是本账号出站动作的<b>回执事件</b>，它到不到取决于上游实现
     * —— 实测 8 天 OneBot 日志里的 144 个 {@code message_sent} <b>全在 {@code get_msg}
     * 的响应体里</b>（那份日志 4,040 条 {@code [Recv]} 全带 {@code echo}，
     * <b>0 条事件推送帧</b>），所以那个计数<b>证明不了</b>"这条路不会被推送"，
     * 它只证明"那 8 天的日志里没推过"。而 {@code QqGateway.java:163-166} 那条事故注释
     * （"一旦把它当入站，机器人就会对自己刚发的那条再起一轮"）说明这条路<b>在真实部署里确实能到</b>。
     * ⇒ 结论按<b>路径推断</b>：A 路是主路（发送那一刻就拿到真 id），B 路是旁路（到了就补记，
     * 靠 {@code s:<id>} 与 {@code sentKnown} 与 A 路合流成一行）。</p>
     *
     * <p><b>零触发</b>：与 notice 分支逐字同一份纪律 —— 只落一行、然后 return。
     * 不进地址门、不进触发投票、不派 {@code ON_MESSAGE}、不调模型、不发消息。
     * 原来"<b>绝不做入站</b>"的语义一个字都没变，多的只是"顺手记一笔账"。</p>
     *
     * <p><b>fail-closed 的自我核对</b>：帧里若给了 {@code user_id}/{@code sender.user_id}
     * 且都对不上 {@code self_id}，这条<b>不是</b>她发的 ⇒ 丢弃。
     * 两个来源都为 0（对端没填）时不拦（不能因为对方少填一个字段就丢掉记账）。</p>
     *
     * <p><b>★ D1 归属（本批新增，判据就一句：<u>只有正向证据才算她的</u>）</b>：
     * 上面那条 {@code uid != selfId} 的核对对"共用同一个 QQ 账号的另一个机器人服务"<b>不设防</b>
     * （三家的 {@code user_id} 都是 selfId）。所以这一步之后先问 {@link #ownKind}：
     * 拿得到正向证据 ⇒ 照旧走 {@link #selfEcho}（她的行，字节与改之前一致）；
     * 拿不到 ⇒ 走 {@link #foreignEcho}（<b>外部来源行</b>：不是她的行，但仍留痕、仍进窗口、标「其他」）。
     * 两条路<b>共用同一把内存去重键</b> {@code s:<msg_id>} ⇒ 同一条重放不会写两行（不论归属）。</p>
     */
    private void handleMessageSent(Ev ev) {
        if (ev == null || boot == null || boot.store() == null) return;
        long mid = ev.messageId();
        if (mid <= 0L) return;                                  // 没有真 id：没有"③"可记
        long self = conf == null ? 0L : conf.selfId();
        if (self > 0L) {
            long uid = ev.userId();
            if (uid <= 0L) uid = J.l(J.sub(ev.raw(), "sender"), "user_id", 0L);
            if (uid > 0L && uid != self) {
                facts("self_echo skip reason=not_self msg_id=" + mid + " uid=" + uid);
                return;
            }
        }
        JsonObject params = new JsonObject();
        params.add("message", ev.raw().get("message"));
        // ★ 会话判据（群/私聊）也一并交给 selfEcho —— 两条路**共用同一处归一**
        //   （selfEcho → echoSession 读 params 的 group_id/user_id）。
        //   刻意**不**让 B 路自己算一个 session 字符串再传进去：那就成了两个产法，
        //   而"A 路与 B 路落同一个键"正是本设计唯一必须成立的等式。
        //
        //   ★ 私聊那支必须用 targetId()（= target_id 优先，回退群号，再回退 user_id）而**不是** userId()：
        //     message_sent 帧里 user_id 是**发送者**（= 她自己！），对端在 target_id 里
        //     （实测帧逐字：{"message_type":"private","user_id":2671623601,"target_id":<对端QQ>,
        //      "message_sent_type":"self"}）。用 userId() 会把她的私聊行落到 **qq:<selfId>**
        //     ——一个既不是对端、也不是她与主人的会话的键上（本轮实测现场：期望 qq:800000300、实际 qq:800000002）。
        //     A 路不受影响：它给的是 send_private_msg 的 user_id（那本来就是**收件人**）。
        if (ev.isGroup() && ev.groupId() > 0L) {
            params.addProperty("group_id", ev.groupId());
        } else if (ev.targetId() > 0L) {
            params.addProperty("user_id", ev.targetId());      // 对端（见上面那段注释）
        } else if (ev.userId() > 0L) {
            params.addProperty("user_id", ev.userId());        // 兜底：帧里连 target_id 都没有
        }
        // sentId 给 0：这条路**没有**台账行（台账是 Api.call 那一步写的），于是：
        //   ① A 路已经记过 ⇒ 台账里有那一行 ⇒ selfEcho 的 ② 层挡下（跨重启也成立）；
        //   ② A 路没记过（比如这次发送走了不走 Api.call 的通路）⇒ 台账里没有 ⇒ 这一路补记一行。
        //
        // ★ D1：这两条**只对"确实是我们发的"成立**。共用同一个 QQ 账号的其它机器人服务发出来的
        //   消息，在帧上与她的东西逐字同形 ⇒ 归属必须先问 ownKind（正向证据），
        //   拿不到证据的一律走 external 那一支（绝不写成她的行）。
        int own = ownKind(mid, echoSession(params), ev.isGroup() && ev.groupId() > 0L);
        if (own == OWN_ROW_SELF) {
            rememberSent(mid);
            facts("self_echo skip reason=already_self msg_id=" + mid + "（这个 id 已经有她自己的行）");
            return;
        }
        if (own == OWN_MINE) {
            selfEcho("message_sent", mid, params, 0L, "");
            return;
        }
        foreignEcho("message_sent", mid, params);
    }

    // ==================== 归属（D1：正向证据） ====================

    /**
     * <b>这条 {@code message_sent} 是不是"我们自己发出去的"</b>（{@link #OWN_MINE} /
     * {@link #OWN_ROW_SELF} / {@link #OWN_FOREIGN}）。
     *
     * <p>判据按"从便宜到贵"排，且<b>每一条都是正向证据</b>（没有任何关键词表、没有任何猜测）：</p>
     * <ol>
     *   <li><b>内存环</b>（{@link #recentSent}）：本进程内我们发过的 id —— 台账后来被裁掉也还认得出。
     *       它是"台账超窗 ⇒ 我们自己的老消息被误判成外部"这个方向的第 ① 道堵塞；</li>
     *   <li><b>台账点查</b>（{@code Store.sentKnown}，走 {@code idx_sent_msgid}）：跨重启也成立；</li>
     *   <li><b>台账完整性</b>：{@code sent} 表<b>从没裁过</b>（行数 &lt; {@link sair.v4.store.Store#DEF_SENT_KEEP}）
     *       ⇒ "不在台账里"就是<b>确定不是我们发的</b>（{@code Api.call} 的每一次发送都在台账里插一行）
     *       —— 这一档让新装/低频期的判定是**结论**而不是猜测；</li>
     *   <li><b>会话近期行</b>（{@link #recentMidKind}，有界、走会话索引）：台账已经裁过时，
     *       目标会话最近 {@link #ECHO_SCAN_ROWS} 行里已经有这个 id 的<b>自我行</b> ⇒ 是她发的
     *       （B 路跨重启重放那一支）。<b>成本与盲区</b>：一次会话内有界查询（不是全表扫 ——
     *       {@code grouplog.msg_id} 没有索引，全表扫实测 61.8 ms，这里刻意不那样查）；
     *       盲区 = 那一行已经滑出"最近 {@link #ECHO_SCAN_ROWS} 行"时看不出来，如实记在报告里。</li>
     * </ol>
     *
     * <p><b>读不出来时的方向</b>：一律按"没有正向证据"处理（{@link #OWN_FOREIGN}）——
     * 这正是本批的口径"<b>只有正向证据才算她的</b>"：宁可把一条她自己的老消息标成外部来源，
     * 也不许把别家机器人的话记成她说的（两边的代价不对称，见报告）。</p>
     */
    private int ownKind(long mid, String sess, boolean group) {
        if (recentSent(mid)) return OWN_MINE;                  // ①
        try {
            if (boot.store().sentKnown(mid)) return OWN_MINE;   // ②
            boolean pruned = boot.store().db() != null
                    && boot.store().db().count("sent", null) >= sair.v4.store.Store.DEF_SENT_KEEP;
            if (!pruned) return OWN_FOREIGN;                    // ③ 台账完整 ⇒ 不在台账里 = 确定不是我们发的
        } catch (Throwable t) {
            if (out != null) out.warn("[qq] 归属判据读库失败（按外部来源处理）: " + t);
            return OWN_FOREIGN;
        }
        return recentMidKind(sess, group, mid) == OWN_ROW_SELF ? OWN_ROW_SELF : OWN_FOREIGN;   // ④
    }

    /**
     * 目标会话<b>最近</b> {@link #ECHO_SCAN_ROWS} 行里这条 {@code msg_id} 的状态（B 路第 ③ 层有界点查）。
     *
     * <p>返回 {@link #OWN_ROW_SELF}（已有<b>她自己的行</b>：{@code content} 带这个 id 的自我记账头，
     * 两种头形态都认）/ 2（已有行但不是她的：外部来源行、或入站的别人发言）/ {@link #OWN_FOREIGN}（没有）。</p>
     *
     * <p><b>为什么是"会话最近 N 行"而不是 {@code WHERE msg_id = ?}</b>：{@code grouplog.msg_id}
     * 没有索引（四个索引都在 {@code group_id}/{@code user_id}/{@code ts} 上），全表存在性检查实测
     * 61.8 ms（{@code Store.sentKnown} 的注释里有实测数）。这里有界查询走
     * {@code idx_grouplog_group(group_id, ts)} / {@code idx_dialog_session(session, ts)}，
     * 读到 N 行就停，代价与表大小无关。换来的盲区就是"更老的行看不见"，如实写在报告里。</p>
     */
    private int recentMidKind(String sess, boolean group, long mid) {
        if (mid <= 0L || Str.blank(sess)) return OWN_FOREIGN;
        List<JsonObject> rows;
        try {
            rows = group
                    ? boot.store().list("grouplog", J.obj("group_id", groupIdOf(sess)), ECHO_SCAN_ROWS, "ts DESC")
                    : boot.store().list("dialog", J.obj("session", sess), ECHO_SCAN_ROWS, "ts DESC");
        } catch (Throwable t) {
            if (out != null) out.warn("[qq] 归属判据：会话近期行点查失败（按无正向证据处理）: " + t);
            return OWN_FOREIGN;
        }
        if (rows == null || rows.isEmpty()) return OWN_FOREIGN;
        String want = String.valueOf(mid);
        String head = sair.v4.qq.SelfEcho.head(mid);                       // [我发的 msg_id=N]
        String headP = sair.v4.qq.SelfEcho.HEAD_LEAD_PAREN + mid           // (我发的 msg_id=N)（san 中和出来的形态）
                + sair.v4.qq.SelfEcho.HEAD_TAIL_PAREN;
        for (JsonObject r : rows) {
            String c = Str.nz(J.s(r, "content", ""));
            if (c.startsWith(head) || c.startsWith(headP)) return OWN_ROW_SELF;      // 她自己的行（唯一产法）
            if (group && want.equals(Str.trim(Str.nz(J.s(r, "msg_id", ""))))) return 2;   // 群行按 msg_id 列认
            JsonObject ex = J.sub(r, "extra");
            if (ex != null && J.b(ex, "foreign", false) && J.l(ex, "msg_id", 0L) == mid) return 2;
        }
        return OWN_FOREIGN;
    }

    /**
     * <b>外部来源行</b>（D1）：本账号<b>另一个机器人服务</b>发出来的消息 —— 不是她的行，但照旧留痕。
     *
     * <p>与 {@link #selfEcho} 的关系（逐条对齐，差别只有归属）：</p>
     * <ul>
     *   <li><b>同表同会话</b>：群 → {@code grouplog}、私聊 → {@code dialog}，会话键由**同一处归一**
     *       （{@link #echoSession}）算出来；</li>
     *   <li><b>内容一样干净</b>：走 {@link sair.v4.qq.SelfEcho#content}（唯一产法）+ {@code withoutHead}
     *       ⇒ 就是"她的行去掉自我记账头"的那一串（正文 + 段标签，段标签仍只由 {@code MediaRender} 产出），
     *       <b>不写</b> {@code [我发的 msg_id=…]}、<b>不写</b> {@code dialog.role=assistant}；</li>
     *   <li><b>{@code extra.foreign=true}</b>（键名冻结）：读侧的唯一身份判据（窗口标「其他」、
     *       技能线不当素材、{@code Person} 不计入）；另带 {@code action} 与 {@code msg_id}（都是事实）；</li>
     *   <li><b>同一把内存去重键</b> {@code s:<msg_id>}（与她的行共用）⇒ 同一条重放不会写两行；
     *       跨重启重放由 {@link #recentMidKind} 在会话近期行里挡住（群行额外有 {@code msg_id} 列可比）；</li>
     *   <li><b>零触发</b>：与 {@code selfEcho} 逐字同一份纪律 —— 只落一行、然后 return。不进地址门、
     *       不进触发投票、不派 {@code ON_MESSAGE}、<b>不调模型、不发消息</b>（能力③要的"仍然唤起思考"
     *       不是在这里做的，这里一个字都不做）。</li>
     * </ul>
     *
     * <p><b>{@code user_id} 写什么（本处唯一的"取舍"）</b>：写 {@code conf.selfId()} —— 帧里给的发送者
     * 就是本账号（不编）。代价：这一列与她的自我行同形 ⇒ "按 {@code user_id == selfId} 就当成她说的"
     * 这种读法会再次踩坑，所以读侧的身份判据<b>必须</b>看 {@code extra}（窗口 / 技能线都已经这样判）。</p>
     */
    private void foreignEcho(String action, long msgId, JsonObject params) {
        if (boot == null || boot.store() == null) return;
        if (msgId <= 0L) return;
        String sess = echoSession(params);
        boolean group = sess.startsWith("group:");
        if (!group && !sess.startsWith("qq:")) {
            facts("foreign_echo skip reason=no_session action=" + action + " msg_id=" + msgId);
            return;
        }
        // ① 内存层：与她的行共用同一把键（`s:`）⇒ 同一条重放不写第二行
        if (seen.dup("s:" + msgId, System.currentTimeMillis(), dedupWindowMs())) {
            facts("foreign_echo skip reason=dup msg_id=" + msgId + "（窗口内已经记过这条）");
            return;
        }
        // ② 持久层：目标会话近期行里已经有这个 id ⇒ 不重复写（跨重启重放）
        if (recentMidKind(sess, group, msgId) != OWN_FOREIGN) {
            facts("foreign_echo skip reason=known msg_id=" + msgId + "（会话近期行里已经有这个 id）");
            return;
        }
        // ③ 渲染：正文 + 段标签（唯一产法；合成失败绝不抛，降级成"只有正文/空"）
        sair.v4.qq.Ev sev = sair.v4.qq.SelfEcho.evOf(params);
        String content = sair.v4.qq.SelfEcho.withoutHead(sair.v4.qq.SelfEcho.content(msgId,
                sair.v4.qq.SelfEcho.textOf(sev),
                sair.v4.qq.SelfEcho.labels(sev, mediaFacts(sev))));
        String table = group ? "grouplog" : "dialog";
        JsonObject row = new JsonObject();
        row.addProperty("ts", System.currentTimeMillis());
        if (group) {
            row.addProperty("group_id", groupIdOf(sess));
            row.addProperty("user_id", conf.selfId());     // 帧里给的发送者（见方法注释的取舍）
            row.addProperty("nickname", "");               // 不编名字：它不是这个群里的任何一个人
            row.addProperty("msg_id", String.valueOf(msgId));   // 与自我行同一口径（TEXT 列写十进制串）
        } else {
            row.addProperty("session", sess);
            row.addProperty("role", DIALOG_ROLE_FOREIGN);  // ★ 不是 assistant、也不是 user（见常量注释）
            row.addProperty("tokens", 0);
        }
        row.addProperty("content", content);
        row.add("extra", foreignExtra(action, msgId));
        boot.store().put(table, row);
        facts("foreign_echo session=" + sess + " action=" + action + " table=" + table
                + " msg_id=" + msgId + " chars=" + content.length());
        // ★ 到此为止：不派钩子、不投票、不调模型、不发消息（零触发，与 selfEcho 同一份）。
    }

    /**
     * 外部来源行的 {@code extra}：{@code {"foreign":true,"action":…,"msg_id":…}}。
     *
     * <p>{@code foreign} 是<b>冻结键名</b>（读侧唯一身份判据，见 {@code ChatWindow.markedForeign}）；
     * {@code action} 与 {@code msg_id} 都是帧里的事实，只用于诊断与跨重启重放的比对。
     * <b>绝不写 {@code self}</b> ⇒ 任何"看 {@code extra.self} 判她自己"的读点天然拿不到它。</p>
     */
    private static JsonObject foreignExtra(String action, long msgId) {
        JsonObject extra = new JsonObject();
        extra.addProperty("foreign", true);
        extra.addProperty("action", Str.lower(Str.trim(action)));
        if (msgId > 0L) extra.addProperty("msg_id", msgId);
        return extra;
    }

    // ==================== 消息 ====================

    private void handleMessage(JsonObject raw, Ev ev) {
        Caller c = callerOf(ev);
        if (c == null) return;
        // ⓪ 入站去重：同一条 message_id 在窗口内只处理一次（重连重放 / 多连接 / 重复投递都挡在这）
        long msgId = ev.messageId();
        if (seen.dup(msgId <= 0L ? "" : String.valueOf(msgId), System.currentTimeMillis(), dedupWindowMs())) {
            facts("skip reason=dup session=" + c.session() + " msg_id=" + msgId
                    + "（窗口内已经处理过这条；累计挡下 " + seen.hits() + " 条）");
            return;
        }
        // ⑥ 好感度绑到这次调用上（它不再是权限门禁：放不放行只由技能管控表按 身份 × op 判）
        //    （主人裁 2026-09-17 起它还多一层身份：她对每个人的"关系值" ⇒ 第一次打交道就按人建账，
        //     这样谁都能问她"我的好感度是多少"，而说不说由她定）
        boot.favor().ensure(c.qq());
        c = c.withFavor(boot.favor().of(c.qq()));

        // 入站媒体登记：图片段里的 file 值 → 直连 URL（跨机 NapCat 时，技能只能靠这条 URL 取图）。
        // 必须先登记再取媒体事实：只有媒体没有文字时那一轮的全部输入就是这条事实，
        // 而图片段的 URL 兜底读的就是这本登记簿。
        sair.v4.qq.Inbound.remember(ev);
        final JsonArray mediaArr = mediaFacts(ev);
        // ★ D2（N2）：语音转写（`fetch_ptt_text`）—— 入站渲染链上的**唯一**调用点。
        //   为什么必须接在这里：`Api.pttEnrich` 只是"前置一步"（就地给 record 段补 `ptt_text`），
        //   不接 = 能力备好了却永不发生（静默失效，不报错 —— N1 报告⑥-1 的那个卡点）。
        //   ① 用**不带闸门**的 `boot.api()`：这是基板自主行为（与落库/自我记账同一档），
        //      不是技能发起的动作；带闸门那条路要 `napcat.fetch_ptt_text` 有授权才动得了；
        //   ② 放在 `mediaFacts` 之后、`media:` 事实串与 `MediaRender.render` **之前**：
        //      转写进 fact ⇒ 标签形状 `[语音 12秒：…]` 与事实行 `media: […]` 是同一串（同一次渲染）；
        //   ③ 走 `visibleFact`（与 `pokeFact` **同一个可见出口**）：出厂 `logConsole="tool,model"`
        //      ⇒ `facts()` 那条口子里 `msg` 类别默认关，"转写发生了"会被整行挡掉（看不见 = 以为没做）；
        //      这一行只有规模（records/text/none/enabled/[why]），**一个转写正文字符都没有**。
        String pttLine = "";
        String urlLine = "";
        try {
            sair.v4.qq.Api bootApi = boot.api();
            if (bootApi != null) {
                pttLine = bootApi.pttEnrich(ev, mediaArr);
                // ★ D3（N2）：图片直链刷新（`nc_get_rkey` → `get_image` → `get_file` → `get_msg`）
                //   的**唯一**调用点。为什么是这里、为什么不是 QuoteCache / MediaAttach.scan，
                //   逐条写在 `Api.refreshFactsUrlsQuiet` 的 javadoc 里（那边是判据本体）。
                //   要点：只在"有 rkey 的图 + 有理由怀疑它旧"时才发动作；刷不出来就**保留原地址**；
                //   rkey 是令牌 ⇒ 刷出来的地址只留在本轮内存的 fact 里，不落库、不落日志。
                urlLine = bootApi.refreshFactsUrlsQuiet(ev, mediaArr);
            }
        } catch (Throwable ignored) {
            // 纯附属步骤：转写/刷新失败绝不改这条消息的任何既有行为（两个方法自己也不抛）
        }
        if (Str.has(pttLine)) visibleFact(pttLine);
        if (Str.has(urlLine)) visibleFact(urlLine);
        final String media = mediaArr.size() == 0 ? "" : "media: " + J.json(mediaArr);
        // ★ M3：入站一次性确定性渲染。全树的调用点只有两处 —— 这一处（M3：入站那一次）
        // 与 qq\QuoteCache 里那一处（M4：取回被引消息）；两处都走 MediaRender 的唯一入口，
        // **没有第二套渲染器**（另造一套是 M2/M3 明令禁止的）—— 算一次，落库（grouplog）、
        // 本轮正文（主 Agent 的 user 轮 ⇒ 经 Agent.ask 的 input 同时进 dialog）三处必然是
        // **同一串**，这就是"同一条消息只有一种渲染"的结构性保证（Seen 保证一条消息只入一次库）。
        //
        // 判据是 hasContent(ev)（"除 text/at 之外确实还有内容段"），**不是** media.isEmpty()：
        // O-1 之后 at 段也进 mediaFacts，拿 media 当判据会把"只 @ 了一下"的消息渲染成
        // [提及 qq=…] —— 那条路（外挂的「被单@时的输入」+ grouplog.content 继续是空串）
        // 是刻意保留的不变量（见下面 :468 那段与 probe\ProbeMediaOnly ⑥）。
        final String render = hasContent(ev) ? sair.v4.qq.MediaRender.render(ev, mediaArr) : "";
        // M4：这条消息引用了哪一条（第一个 reply 段的 id；没有就是空串）。
        // 入站这一步只把它**结构化留一份 ID**（写进 extra），不解析、不查库、不走网 ——
        // content 里 M2 冻结的 [引用 msg_id=…] 标签一个字节都不改。
        final String quoteRef = sair.v4.qq.QuoteCache.replyId(ev);

        // 结构事实：这条消息是什么、算不算"冲着 AI 来"（只有长度/条数，没有正文）
        final String session = c.session();
        final String text0 = Str.nz(ev.plainText());
        facts("msg session=" + session + " type=" + (ev.isGroup() ? "group" : "private")
                + (ev.isGroup() ? " g=" + ev.groupId() : "") + " qq=" + c.qq()
                + " at=" + (ev.atSelf() ? 1 : 0) + " master=" + (c.master() ? 1 : 0)
                + " chars=" + text0.length() + " media=" + mediaArr.size());
        detail("text=" + Str.cut(Str.oneLine(text0), 200));

        // ⑤ 上下文捕获：群消息入库（供上下文装配与检索）
        // 被本地拉黑的人的消息【也记】—— 本地黑名单只决定"我们这边不处理"，留痕照旧（见类注释）
        long quoteRowId = 0L;                       // M4：这一行的 id（异步解析完按主键写回 extra）
        if (ev.isGroup() && boot.store() != null) {
            JsonObject row = new JsonObject();
            row.addProperty("ts", System.currentTimeMillis());
            row.addProperty("group_id", ev.groupId());
            row.addProperty("user_id", ev.userId());
            row.addProperty("nickname", ev.senderName());
            // ★ M3：有文字就存文字（逐字不变），没有文字才存上面那条渲染标签（旧值 = 空串）。
            // 判据用 Str.has(text0)（与下面 prompt 那一支的 Str.blank 同一口径）：
            // 纯空白正文不是"文字"，它跟"只有媒体段"是同一种消息。
            row.addProperty("content", Str.has(text0) ? text0 : render);
            row.addProperty("msg_id", ev.messageId());
            // M4：引用链接结构化落 extra（列本来就存在；全树此前没有任何写方）。
            // 形状 {"quote":{"id":…,"state":"pending","src":"none"}}；没有 reply 段就**不加这个键**
            // （于是"这条不是引用"这一格与"没有 extra 的既有行"完全同形，不引入空壳）。
            String qlink = sair.v4.qq.QuoteCache.linkExtra(quoteRef);
            if (qlink != null) row.addProperty("extra", qlink);
            // 落 grouplog = 她自己的行为（主体即她本人 / SYSTEM）：恒全权，不是技能 op ⇒ 不判，直接落。
            quoteRowId = boot.store().put("grouplog", row);
            // 这里原来还有一行「会话快照落后」检查（noteInboundLag：算"这条消息还没进快照的条数"，
            // 超阈值 warn 一行）。游标机制随主人 2026-09-16 的口径整条删除，那行告警也没有了衡量对象 ——
            // 现在每触发一个回合都现查"本会话最近 N 条聊天记录"（chatWindowSize），
            // 不存在"落后多少条"这个概念。见 ctx.ChatWindow。
        }

        // 本地黑名单：命中就不处理（连技能钩子也不派 —— "我们这边不响应"），但上面已经留痕
        if (blocked(c.qq())) {
            facts("skip reason=blocked session=" + session + " qq=" + c.qq() + "（本地黑名单；消息已记入群聊历史）");
            return;
        }

        // 钩子：技能先看（可以接管；钩子技能拿到的 Host 绑定了本会话，因此也能 h.say）
        JsonObject payload = raw.deepCopy();
        payload.add("_caller", callerJson(c));
        payload.addProperty("_handled", false);
        final Caller hookCaller = c;
        final Sink hookSink = new Sinks.QqSink(boot.guardedApi(), c, out, false, seg, boot.favor());
        boot.skills().dispatch(Skills.ON_MESSAGE, payload, hookCaller, new Turn(hookCaller, hookSink));
        boolean handled = J.b(payload, "_handled", false);

        if (handled) {
            facts("hook handled=1 session=" + session + "（技能接管，基板不再自动回复）");
            return;
        }
        // 技能还能投"这条要不要自动回"这一票（缺省 = 按地址规则走）：
        //   _reply=false → 别自动回（例如触发词没命中）—— 直接结束，省掉一整轮模型调用；
        //   _reply=true  → 即使没被 @、也不是主人也回（例如触发词命中）。
        // 口径在技能里（触发词那份词表），基板只认这一票，不替技能做判断。
        //
        // 基板⑦ 起，这一票升级成"插件公共"（ext.TriggerVoter），口径按顺序确定：
        //   先问插件投票 →（没人表态才）看技能那一票 →（还是没有才）看地址规则。
        // 今天没有任何 voter，pluginVote 直接返回 null，下面两步与加这一层之前逐字节一样。
        sair.v4.ext.ExtRegistry.Verdict pv = pluginVote(ev, c);
        if (pv != null && pv.vote == sair.v4.ext.TriggerVoter.Vote.DECLINE) {
            facts("skip reason=" + pv.reason + " session=" + session
                    + "（插件投票 " + pv.by + "：不自动回 —— 不产生模型调用）");
            return;
        }
        // ★ 能力③（2026-10-01）：这一轮是不是**被投票要回来的**（插件那一票）—— 见下面 vote 那一支。
        boolean byVote = pv != null && pv.vote == sair.v4.ext.TriggerVoter.Vote.ANSWER;
        if (pv == null || pv.vote != sair.v4.ext.TriggerVoter.Vote.ANSWER) {
            Boolean vote = null;
            try {
                if (payload.has("_reply") && !payload.get("_reply").isJsonNull()) {
                    vote = Boolean.valueOf(payload.get("_reply").getAsBoolean());
                }
            } catch (Throwable ignored) {
            }
            if (vote != null && !vote.booleanValue()) {
                facts("skip reason=" + J.s(payload, "_reply_reason", "declined") + " session=" + session
                        + "（技能判定这条不自动回 —— 不产生模型调用）");
                return;
            }
            if (vote == null && !shouldAnswer(ev, c)) {
                facts("skip reason=not_addressed session=" + session + "（非主人/非私聊/未@）");
                return;
            }
            // ★ 能力③（2026-10-01）：这一轮是**被投票要回来的**（触发词那一票 / 插件那一票）——
            //   它压过地址规则，是"为什么起这一轮"最具体的那一条（事实行 addressed: trigger 的判据）。
            if (vote != null && vote.booleanValue()) byVote = true;
        }
        final String text = Str.nz(ev.plainText());
        // 只有 at 段（单 @ 一下、没有文字也没有媒体）不跳过：主人/群友"点个名"就是要它说话，
        // 这一轮的全部输入就是**上下文里的那段「本会话最近 N 条聊天记录」**（chatWindowSize，
        // 到这条 @ 为止都看得见），让模型读一遍自己接。
        // 给模型的输入文案外挂在 prompts/tools-index.md 的「被单@时的输入」，缺键 → 报缺键且不发这一轮
        // （基板不编兜底话，与其余文案同一契约）。
        String prompt;
        // O-1（主人 2026-09-16 批准）：判据是"除 @ 之外还有没有别的东西"，不是"media 事实行是不是空" ——
        // at 段现在也进事实（别人被 @ 的对模型可见），拿 media 当判据会把这条"单 @ 一下"的路堵死。
        // 逐字对应的旧口径是 `Str.blank(text) && media.isEmpty()`；两条路（外挂的「被单@时的输入」、
        // 无文本无内容段直接 return）行为一个字都没变 —— 见 probe\ProbeMediaOnly ⑥。
        if (Str.blank(text) && !hasContent(ev)) {
            if (!ev.atSelf()) {
                facts("skip reason=empty session=" + session + "（无文本，除 @ 之外也没有内容段）");
                return;
            }
            String tpl = index() == null ? null : index().bareMentionText();
            if (Str.blank(tpl)) {
                if (out != null) {
                    out.err("[v4] prompts/tools-index.md 缺键「被单@时的输入」—— 这一路不发"
                            + "（基板不编兜底话；补上该键即可让「单 @ 一下」也能起一轮）");
                }
                return;
            }
            facts("summon-only session=" + session + " qq=" + c.qq() + "（只有 at 段：带上下文起一轮）");
            prompt = tpl;
        } else {
            prompt = (ev.isGroup() && ev.atSelf()) ? stripAt(text) : text;
        }
        // ★ M3：只有内容段、没有文字的消息 —— 这一轮的正文就是那条人话标签（与 grouplog.content
        // 同一个 render 变量、同一串）。JSON 事实**一个字都没撤**：它照旧在事实块的 media: 行里。
        // 渲染为空是 M2 契约（"有内容段就非空"）排除掉的情况；真出现就回落到 media 事实行 ——
        // user 轮绝不为空（严格的 provider 会因空 content 直接 400，这是 :143 那句注释的既有理由）。
        // 注意上面 :468 的条件一个字都没改：两条老路（外挂的「被单@时的输入」、无文本无内容段
        // 直接 return）在这里都走不到（prompt 已非空 / 已 return）。
        if (Str.blank(prompt) && hasContent(ev)) prompt = Str.has(render) ? render : media;
        final Caller caller = c;
        final Sink sink = new Sinks.QqSink(boot.guardedApi(), caller, out, verbose(), seg, boot.favor());
        // ★ M4：**只在"这条消息要起一轮的那一刻"**解析一次引用（真机口径 ≈16.5 次/天）。
        //   顺序就是甲方的裁定：先本地库（纯本地、无网络、最优形状 order by id desc limit 1）
        //   ⇒ 命中且有正文就直接用；没命中才入队走 NapCat（单线程 worker，**这一轮不等**）。
        //   绝不在每条入站上查、绝不批量补查（实测全量 2,586 个 id 要 154.8 秒）。
        sair.v4.qq.QuoteCache qc = boot.quote();
        final String quoteFact = qc == null ? "" : qc.onTurn(quoteRef, quoteRowId, ev.groupId());
        final String mediaFact = Str.blank(quoteFact) ? media
                : (Str.blank(media) ? quoteFact : media + "\n" + quoteFact);
        facts("→ lane=" + session + " high=" + ((c.master() || !c.isGroup()) ? 1 : 0)
                + " chars=" + prompt.length() + (media.isEmpty() ? "" : " media=1")
                + (Str.blank(quoteFact) ? "" : " quote=1")
                + " addressed=" + Str.nz(addressedOf(ev, c, byVote)));
        submitTurn(caller, sink, prompt, mediaFact, addressedOf(ev, c, byVote));
    }

    /**
     * <b>这一轮是怎么被叫到的</b>（能力③的事实来源，{@code CtxBuild.ST_ADDRESSED} 的取值）。
     *
     * <p>判据表（优先级从上到下，第一个命中即取值；取值域见 {@code CtxBuild.AD_*}）：</p>
     * <ol>
     *   <li>{@code trigger} —— {@code byVote}：技能把 {@code payload._reply} 置 true 或插件投了 ANSWER。
     *       <b>最具体的"为什么起这一轮"</b>，压过下面的地址规则；</li>
     *   <li>{@code at} —— 群里 @ 到了自己（{@code Ev.atSelf()}）；</li>
     *   <li>{@code private} —— 私聊（{@code Ev.isPrivate()}）；</li>
     *   <li>{@code master} —— 主人（兜底：网关这一侧正常轮不到它，主人的私聊读 {@code private}、
     *       主人在群里 @ 读 {@code at}；它的正主是本地控制台/面板那两条没有 QQ 地址的入口，
     *       由 {@code Boot.askConsole} 与 {@code Cmd} 直接塞）。</li>
     * </ol>
     *
     * <p><b>认不出就返回空串</b> ⇒ 产者不塞、事实块里没有 {@code addressed:} 这一行（取不到就不写，
     * 不是写个空值）。纯函数：只看入参，不读配置、不读库、不抛。</p>
     */
    static String addressedOf(Ev ev, Caller c, boolean byVote) {
        try {
            if (byVote) return sair.v4.ctx.CtxBuild.AD_TRIGGER;
            if (ev != null && ev.isGroup() && ev.atSelf()) return sair.v4.ctx.CtxBuild.AD_AT;
            if (ev != null && ev.isPrivate()) return sair.v4.ctx.CtxBuild.AD_PRIVATE;
            if (c != null && c.master()) return sair.v4.ctx.CtxBuild.AD_MASTER;
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** 外挂文案（{@code prompts/tools-index.md}）；加载不上时返回一个"什么都缺"的实例，绝不抛异常。 */
    private sair.v4.tool.ToolIndex index() {
        try {
            if (conf != null) return sair.v4.tool.ToolIndex.of(conf);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 把一个回合交给基板调度器（按会话分道）。
     *
     * <p>旧口径是"所有会话排一条 64 槽队列"，一个慢群堵住所有人、队列满时静默丢弃；
     * 现在交给 {@link sair.v4.schedule.Lanes}：同会话保序、不同会话并行，
     * 溢出时优先把后到的消息<b>并进该会话还在排队的那一条</b>（合并成一回合），
     * 真装不下才丢弃并按配置回一句外挂文案。</p>
     *
     * <p><b>提交时不再做任何上下文准备</b>：原来这里会先做一份"会话快照"（游标窗口）再随任务带进去，
     * 主人 2026-09-16 取消自动快照之后，上下文里的"本会话最近 N 条聊天记录"改成
     * <b>回合真正开跑的那一刻现查</b>（见 {@code agent.Agent.ask} → {@code ctx.ChatWindow}）——
     * 排队期间新到的消息因此也能进窗口，而"这一轮看见的到底是哪一段"只有一个地方说了算。</p>
     */
    private void submitTurn(final Caller caller, final Sink sink, String prompt, String media,
                            final String addressed) {
        final boolean high = caller.master() || !caller.isGroup();
        final String session = caller.session();
        // ★ 2026-09-22「内部文字外泄防线批」G7：给 job 带上**说话人标识** ⇒ 同会话道里
        //   不同人后到的消息**不再并进**这一条（合了就按第一个人的 caller 办事，见 schedule.ChatJob）。
        //   同一人连发仍照旧合并（省一次模型调用）。行为影响：极少数轮会多跑一轮（可接受）。
        final String owner = session + "#" + caller.qq();
        sair.v4.schedule.ChatJob job = new sair.v4.schedule.ChatJob(session, high,
                new sair.v4.schedule.ChatJob.Body() {
                    @Override
                    public void run(String text, String mediaFact) {
                        sair.v4.agent.Loop.Outcome oc = boot.agent().ask(caller, sink, text, mediaFact, addressed);
                        // 回合摘要：这轮跑了多久、几轮、几次工具、成没成 —— 结构事实，无正文
                        if (oc != null) {
                            turn("session=" + session + " rounds=" + oc.rounds + " tools=" + oc.toolCalls
                                    + " ms=" + oc.ms + " ok=" + (oc.ok() ? 1 : 0)
                                    + (oc.ok() ? "" : " error=" + Str.cut(Str.oneLine(oc.error), 160)));
                            detail("reply_chars=" + Str.nz(oc.text).length() + " reply=" + Str.cut(Str.oneLine(oc.text), 200));
                        }
                    }
                },
                new sair.v4.schedule.ChatJob.Notice() {
                    @Override
                    public void say(String text) {
                        if (sink != null) sink.say(text);
                    }
                },
                prompt, media, boot.conf() == null ? 4000 : boot.conf().turnMergeMaxChars(), owner);
        sair.v4.schedule.Lanes lanes = boot.turnsQq();
        if (lanes != null) lanes.submit(session, high, job);
        else boot.submit(job);          // 调度器没装配起来时的降级路径（仍然不在这里直接跑回合）
    }

    /**
     * 问一遍插件投票（{@code ext.TriggerVoter}，基板⑦）。
     *
     * <p>三态由插件给，顺序由基板定：按 {@code order, 插件名} 依次问，<b>第一个非 ABSTAIN 的票生效</b>
     * （理由与"为什么不是多数决"见 {@link sair.v4.ext.TriggerVoter}）。</p>
     *
     * @return {@code null} = 没有插件表态（网关据此走技能那一票与地址规则那条老路）
     */
    public sair.v4.ext.ExtRegistry.Verdict pluginVote(Ev ev, Caller c) {
        try {
            sair.v4.ext.ExtRegistry e = boot == null ? null : boot.ext();
            return e == null ? null : e.vote(ev, c);
        } catch (Throwable t) {
            // 扩展点自己已经做了异常隔离；这里是"连注册表都炸了"的最后一道 —— 按"没人表态"处理，
            // 绝不让投票这一步成为"这条消息消失了"的原因。
            if (out != null) out.warn("[ext] 触发投票失败（按没人表态处理）：" + t);
            return null;
        }
    }

    /**
     * 基板最小判定：<b>私聊一律回；群里一律要求被 @</b>。
     *
     * <p>{@code 主人}在这里<b>不再有豁免</b>：早先 {@code if (c.master()) return true;} 让主人在群里说任何话都被回，
     * 而"别自动回"那一票只有触发词技能会投、且它对<b>没有文字的消息</b>（纯表情/图片/语音/文件）直接 return 不投票 ——
     * 于是主人在群里丢一个表情，就没人投票、主人豁免生效、机器人答一句（主人实测的"没叫也回"）。
     * 现在口径与技能注释一致：<b>群里要么 @ 它，要么用触发词</b>，主人也一样。</p>
     */
    public boolean shouldAnswer(Ev ev, Caller c) {
        if (c == null) return false;
        if (ev.isPrivate()) return true;      // 私聊 = 直接找它
        return ev.atSelf();                   // 群里：只认被 @（触发词那条路由技能接管，不走这里）
    }

    /** 入站去重窗口（毫秒；配置 {@code msgDedupWindowSec}，{@code 0} = 关闭判重）。 */
    private long dedupWindowMs() {
        try {
            int sec = conf == null ? sair.v4.qq.Seen.DEF_WINDOW_SEC
                    : conf.getInt("msgDedupWindowSec", sair.v4.qq.Seen.DEF_WINDOW_SEC);
            return sec <= 0 ? 0L : sec * 1000L;
        } catch (Throwable t) {
            return sair.v4.qq.Seen.DEF_WINDOW_SEC * 1000L;
        }
    }

    /** 去重统计（诊断/状态面用）。 */
    public String dedupStat() {
        return "seen=" + seen.size() + " dup_hit=" + seen.hits() + " window_s=" + (dedupWindowMs() / 1000L);
    }

    // ==================== 本地黑名单 ====================

    /** 本地黑名单的 kv 键（{@code block:<qq>}）。写作方是技能 {@code 好友管理}。 */
    public static String blockKey(long qq) { return BLOCK_PREFIX + qq; }

    /**
     * 这个 QQ 是否在<b>本地</b>黑名单里（kv 存在且值非空 = 已拉黑）。
     *
     * <p>读失败（库没起来）一律当"没拉黑"：宁可多回一句，也别让整条消息静默消失。</p>
     */
    public boolean blocked(long qq) {
        if (qq <= 0) return false;
        try {
            if (boot == null || boot.store() == null) return false;
            return Str.has(boot.store().kv(blockKey(qq), ""));
        } catch (Throwable t) {
            if (out != null) out.warn("[qq] 本地黑名单读取失败（按未拉黑处理）: " + t);
            return false;
        }
    }

    // ==================== 媒体事实（结构化，不编文案） ====================

    /**
     * 一条消息里所有"内容段"的结构化元数据。
     *
     * <p><b>什么算一段</b>：除 {@code text} 之外的段 ——
     * {@code at / image / record / video / file / face / mface / reply / forward / node / json / xml /
     * markdown …} 以及 NapCat 将来新增的段型（白名单会把新段型变成静默丢失，正是这次要修的 bug，
     * 所以这里取"非文本即一段"的口径）。</p>
     *
     * <p><b>O-1（主人 2026-09-16 批准）</b>：{@code at} 段<b>也进事实</b>了（{@code {"type":"at","qq":"…"}}）——
     * 今天群里"@李四 看这个"里的李四对模型完全不可见。代价是每条被 @ 的消息事实块里多一小段；
     * 路由判据随之从"有没有媒体段"换成"有没有非 at 的内容段"（{@link #hasContent(Ev)}），
     * 这样"单 @ 一下"那条路（只有 at、没有别的东西）逐字节不变。</p>
     *
     * <p>每段给一个 JSON 对象：{@code type} + 该段 {@code data} 里的全部字段
     * （{@code file/url/file_id/name/path/duration/file_size/…} 原样带上）；
     * 超长值截断到 {@link #FACT_MAX}。</p>
     *
     * <p><b>内联二进制 vs 卡片载荷</b>：{@code base64}/{@code hex} 只留长度；{@code data} 先留长度，
     * 且当段型是卡片类（{@link #CARD_TYPES}）时另给一份短的可读副本 {@code data_text}（≤{@link #DATA_TEXT_MAX}）。
     * 修的是这个 bug：OneBot v11 的 {@code json}/{@code xml} 段<b>载荷本来就在 {@code data.data}</b>，
     * 旧口径把它一律长度化 ⇒ 卡片到模型手上只剩 {@code {"type":"json","data_len":421}}，
     * app/prompt/title/desc 全被量掉。真正的卡片字段由 {@link #cardFacts} 走真 JSON 抽出来。</p>
     *
     * <p>图片段若 data 里没有可用 URL，回落到基板入站登记簿（{@link Inbound}）里记下的那条直连 URL。</p>
     */
    public static JsonArray mediaFacts(Ev ev) {
        JsonArray arr = new JsonArray();
        if (ev == null) return arr;
        for (JsonObject seg : ev.segments()) {
            String type = Str.lower(Str.trim(J.s(seg, "type", "")));
            if (type.isEmpty() || "text".equals(type)) continue;
            JsonObject f = new JsonObject();
            f.addProperty("type", type);
            JsonObject d = J.sub(seg, "data");
            if (d != null) {
                for (Map.Entry<String, JsonElement> e : d.entrySet()) {
                    String k = e.getKey();
                    if (Str.blank(k)) continue;
                    JsonElement v = e.getValue();
                    if (v == null || v.isJsonNull()) continue;
                    String kl = Str.lower(k);
                    // 内联二进制：只留长度（原口径，一字不改）
                    if ("base64".equals(kl) || "hex".equals(kl)) {
                        f.addProperty(kl + "_len", v.isJsonPrimitive() ? v.getAsString().length() : -1);
                        continue;
                    }
                    // data：先只留长度；卡片类段型另给一份短的可读副本（卡片载荷在这个键上，不能量掉）
                    if ("data".equals(kl)) {
                        String raw = cardRaw(v);
                        f.addProperty("data_len", v.isJsonPrimitive() ? v.getAsString().length() : -1);
                        if (CARD_TYPES.contains(type) && raw.length() > 0 && raw.length() <= DATA_TEXT_MAX) {
                            f.addProperty("data_text", raw);
                        }
                        continue;
                    }
                    if (!v.isJsonPrimitive()) {
                        f.addProperty(k, Str.cut(J.json(v), FACT_MAX));
                        continue;
                    }
                    JsonPrimitive p = v.getAsJsonPrimitive();
                    if (p.isNumber()) f.addProperty(k, p.getAsNumber());
                    else if (p.isBoolean()) f.addProperty(k, p.getAsBoolean());
                    else f.addProperty(k, Str.cut(p.getAsString(), FACT_MAX));
                }
            }
            // 卡片类段型：走真 JSON 抽人看得懂的字段（只加键，绝不覆盖段里已有的同名键）
            if (CARD_TYPES.contains(type)) cardFacts(f, type, d);
            // 图片：data 里没有可用 URL 时，补上入站登记簿里那条（跨机 NapCat 场景的关键线索）
            if ("image".equals(type) && Str.blank(J.s(f, "url", ""))) {
                String u = Inbound.urlOfFile(J.s(f, "file", J.s(f, "file_id", "")));
                if (Str.has(u)) f.addProperty("url", Str.cut(u, FACT_MAX));
            }
            arr.add(f);
        }
        return arr;
    }

    /**
     * 这条消息有没有"非 at 的内容段"（{@link #mediaFacts} 的同一口径，但把 {@code at} 排除在外）。
     *
     * <p>路由用（{@code handleMessage}）：判据是"除 @ 之外还有没有别的东西"，
     * 而不是"media 事实行是不是空" —— O-1 之后 media 行里会有 at 段，
     * 拿它当判据会让"只 @ 了一下"那条路（外挂的「被单@时的输入」）永远走不到。</p>
     */
    public static boolean hasContent(Ev ev) {
        if (ev == null) return false;
        for (JsonObject seg : ev.segments()) {
            String type = Str.lower(Str.trim(J.s(seg, "type", "")));
            if (type.isEmpty() || "text".equals(type) || "at".equals(type)) continue;
            return true;
        }
        return false;
    }


    /**
     * 本轮的事实行：{@code media: [ … ]}（一行 JSON；没有媒体段时返回空串）。
     *
     * <p>只给事实，不给句子：模型看到的是 {@code [{"type":"image","file":"…","url":"…"}]}，
     * 至于"这是什么意思、要不要看图、怎么回应"由提示词层决定。</p>
     */
    public static String mediaFact(Ev ev) {
        JsonArray arr = mediaFacts(ev);
        return arr.size() == 0 ? "" : "media: " + J.json(arr);
    }

    /** 事实里单个字符串值的上限（超出截断；内联二进制另按长度处理）。 */
    private static final int FACT_MAX = 512;

    /** 卡片类段型：载荷在 {@code data.data}（json/xml），或 {@code data.content}（markdown）。 */
    private static final java.util.Set<String> CARD_TYPES = new java.util.HashSet<String>(
            java.util.Arrays.asList("json", "xml", "markdown", "rich", "ark", "card"));
    /** {@code data.data} 的"短的可读副本"上限（超过就只留 data_len）。 */
    private static final int DATA_TEXT_MAX = 512;
    /** 允许解析的载荷上限（超过 = {@code card_state=cut}，不解析、也不给短副本）。 */
    private static final int CARD_PARSE_MAX = 8192;
    /** 单个抽出来的卡片字段上限。 */
    private static final int CARD_FIELD_MAX = 300;
    /** 叶子收集上限（条数 / 深度）——防畸形或巨大载荷把抽取变成遍历炸弹。 */
    private static final int CARD_LEAF_MAX = 96;
    private static final int CARD_DEPTH_MAX = 4;

    /**
     * 卡片字段的<b>键名白名单</b>（末段名规则）：只有这些名字的叶子标量会被收成事实。
     *
     * <p>来源是实测（{@code notes\archive\keep-cardhunt-FINDINGS.md}：120 个真实 {@code json} 段），
     * 不是猜的；{@code uin}/{@code nick} 在 {@code meta.<家族>.host} 下时另起 {@code card_host_uin} /
     * {@code card_host_nick}（键名撞车就用路径区分，不改白名单的语义）。</p>
     */
    private static final java.util.Set<String> CARD_FIELDS = new java.util.HashSet<String>(
            java.util.Arrays.asList("app", "prompt", "bizsrc", "view",
                    "title", "desc", "jumpUrl", "pcJumpUrl", "url", "qqdocurl", "preview", "icon",
                    "tag", "tagName", "appid", "uin", "nick",
                    "source", "nickname", "cover", "musicUrl", "address", "lat", "lng", "name"));

    /** 段 {@code data} 里的载荷原文：原语按原样，对象/数组按紧凑 JSON（长度要算真的，不截断）。 */
    private static String cardRaw(JsonElement v) {
        if (v == null || v.isJsonNull()) return "";
        if (v.isJsonPrimitive()) {
            try {
                return Str.nz(v.getAsString());
            } catch (Throwable t) {
                return "";
            }
        }
        try {
            return Str.nz(J.json(v));
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 卡片字段抽取（M1）。
     *
     * <p><b>真实形状</b>（{@code notes\archive\keep-cardhunt-FINDINGS.md}：8 天 120 个 {@code json} 段实测）：
     * {@code {app, prompt, bizsrc, view, ver, meta, config, extra, desc?}}，其中</p>
     * <ul>
     *   <li>{@code app}：顶层、120/120 都在、<b>永远是点分字符串</b>（{@code com.tencent.tuxiang.lua}）——
     *       V3 的"app 本身是 JSON 再取 app.prompt/app.desc"是<b>死代码</b>，这里不抄那个形状；</li>
     *   <li>{@code prompt}：顶层、最有用的一格 —— 人话摘要<b>并且</b>带着种类标签
     *       （{@code [链接]} / {@code [QQ小程序]} / {@code [位置]} / {@code [分享]} / {@code [群公告]}）；</li>
     *   <li>{@code bizsrc}：顶层、最便宜的判别位（{@code qun.invite} / {@code qqconnect.sdkshare} / …）；</li>
     *   <li>{@code meta}：它<b>唯一的孩子</b>就是这张卡片的家族（{@code news} / {@code detail_1} /
     *       {@code music} / {@code miniapp} / {@code contact} / {@code feed} / {@code Location.Search}
     *       —— 带点！ / {@code mannounce} / {@code metadata}），有用的字段都在那孩子里；</li>
     *   <li>顶层 {@code desc} 存在但<b>通常是空串</b>（真有描述在 {@code meta.<家族>.desc}）；
     *       {@code title} <b>从来不顶层</b>；{@code summary} 与 {@code content}（json 段）在 120 个实测里
     *       <b>0 次</b> ⇒ <b>不抽</b>（V3 抽过那两个键，是编的）。</li>
     * </ul>
     *
     * <p><b>只加不删、不覆盖</b>：段 {@code data} 里本来就带的键一个都不动；{@code card_*} 一律后加，
     * 同名键以段里的为准（{@link #putOnce}）。</p>
     *
     * <p><b>不抛异常</b>：畸形 JSON ⇒ {@code card_state=bad}；没有可用字段 ⇒ {@code empty}；
     * 载荷超过 {@link #CARD_PARSE_MAX} ⇒ {@code cut}（不解析，也不给短副本）。</p>
     */
    private static void cardFacts(JsonObject f, String type, JsonObject d) {
        if (d == null) {
            putOnce(f, "card_state", "empty");
            return;
        }
        // 载荷槽：json/xml 在 data.data；markdown 在 data.content（这是真实存在的不对称）
        String payload = cardRaw(d.get("data"));
        if (Str.blank(payload)) payload = cardRaw(d.get("content"));
        if (Str.blank(payload)) {
            putOnce(f, "card_state", "empty");
            return;
        }
        if (payload.length() > CARD_PARSE_MAX) {
            putOnce(f, "card_state", "cut");
            return;
        }
        Map<String, String> card = new java.util.LinkedHashMap<String, String>();
        String state;
        if ("xml".equals(type)) {
            state = xmlCard(payload, card);                 // 有界字符串扫描（不用 DOM/SAX）
        } else {
            JsonObject root = J.obj(payload);
            if (root == null) {
                if ("markdown".equals(type)) {
                    // markdown 段的载荷天生是一段 markdown 文本（实测），不是 JSON ⇒ 当文本收
                    putOnceMap(card, "card_text", payload);
                    state = "ok";
                } else {
                    state = "bad";
                }
            } else {
                walkCard(root, card);
                state = card.isEmpty() ? "empty" : "ok";
            }
        }
        for (Map.Entry<String, String> e : card.entrySet()) putOnce(f, e.getKey(), e.getValue());
        putOnce(f, "card_state", state);
    }

    /**
     * 走真 JSON（V4 有真的 {@code JsonObject}，不用 V3 那种扁平 {@code indexOf} —— 那次实测正是
     * 因为扁平查找先命中顶层空 {@code desc}，把卡片真正的描述整条丢掉了）。
     *
     * <p>顺序刻意是"先 {@code meta} 的孩子，再顶层兜底"：顶层 {@code desc} 通常是空串，
     * 而 {@link #putOnceMap} 只认第一个<b>非空</b>值 ⇒ 真的描述不会被空串占位。</p>
     */
    private static void walkCard(JsonObject root, Map<String, String> out) {
        JsonObject meta = J.sub(root, "meta");
        if (meta != null) {
            for (Map.Entry<String, JsonElement> e : meta.entrySet()) {
                JsonElement ch = e.getValue();
                if (ch == null || !ch.isJsonObject()) continue;
                putOnceMap(out, "card_meta", e.getKey());
                leaves(ch.getAsJsonObject(), null, 0, out);
                break;      // 实测 meta 只有一个孩子；有多个也只看第一个（确定性）
            }
        }
        leaves(root, null, 0, out);     // 顶层兜底（app/prompt/bizsrc/view/desc…）
    }

    /** 递归收叶子标量：按末段名（{@code host.uin} 另起名）映射成 {@code card_<snake>}。 */
    private static void leaves(JsonObject o, String parent, int depth, Map<String, String> out) {
        if (o == null || depth > CARD_DEPTH_MAX || out.size() >= CARD_LEAF_MAX) return;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (out.size() >= CARD_LEAF_MAX) return;
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            if (v.isJsonObject()) {
                leaves(v.getAsJsonObject(), e.getKey(), depth + 1, out);
                continue;
            }
            if (!v.isJsonPrimitive()) continue;         // 数组（buttons[…] 等）不收
            String key = cardKey(e.getKey(), parent);
            if (key == null) continue;
            try {
                putOnceMap(out, key, v.getAsString());
            } catch (Throwable ignored) {
            }
        }
    }

    /** 叶子键名 → 事实键（不在白名单里 = {@code null}，不收）。 */
    private static String cardKey(String name, String parent) {
        String n = Str.trim(name);
        if (n.isEmpty()) return null;
        if ("uin".equals(n) && parent != null) return "card_host_uin";
        if ("nick".equals(n) && parent != null) return "card_host_nick";
        if (!CARD_FIELDS.contains(n)) return null;
        return "card_" + snake(n);
    }

    /** camelCase → snake_case（{@code jumpUrl} → {@code jump_url}；全小写的键名原样）。 */
    private static String snake(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (sb.length() > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * XML 卡片：<b>有界字符串扫描</b>，绝不用 DOM/SAX。
     *
     * <p>载荷是不可信的外部字符串：JDK 的 {@code DocumentBuilderFactory} 默认允许 DTD ⇒ 换成 DOM/SAX
     * 就等于同时开了 XXE（读本地文件/内网）与实体炸弹面。这里只做"简单、非嵌套、输入已夹在
     * {@link #CARD_PARSE_MAX} 内"的正则取标签文本，并只解 5 个预定义实体（{@code &xxe;} 这种
     * <b>原样保留</b>，不做任何实体展开）。</p>
     *
     * <p><b>未经真实流量验证</b>：8 天里 120 个 {@code json} + 87 个 {@code markdown} 段，
     * {@code xml} 出现 <b>0 次</b>（{@code keep-cardhunt-FINDINGS.md} §4.1）—— 这一段是推断。</p>
     *
     * @return {@code ok}（抽到 ≥1 个字段）/ {@code bad}（一个已知标签都没有）
     */
    private static String xmlCard(String xml, Map<String, String> out) {
        // 键名 → 事实键：V3 的三档 desc/summary/brief 收在同一格 card_desc
        String[][] scan = {
            {"title", "card_title"}, {"summary", "card_desc"}, {"brief", "card_desc"},
            {"desc", "card_desc"}, {"app", "card_app"}, {"url", "card_url"},
        };
        int hit = 0;
        for (String[] kv : scan) {
            String v = xmlTag(xml, kv[0]);
            if (Str.blank(v)) continue;
            putOnceMap(out, kv[1], v);
            hit++;
        }
        return hit > 0 ? "ok" : "bad";
    }

    /** 取一个 XML 标签的文本（有界、非嵌套；只解 5 个预定义实体，不展开自定义实体）。 */
    private static String xmlTag(String xml, String tag) {
        if (Str.blank(xml) || Str.blank(tag)) return "";
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(?is)<" + tag + "(\\s[^>]*)?>(.*?)</" + tag + "\\s*>")
                    .matcher(xml);
            if (!m.find()) return "";
            String v = m.group(2);
            if (v == null) return "";
            v = v.replace("<![CDATA[", "").replace("]]>", "");
            return v.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                    .replace("&#39;", "'").replace("&amp;", "&");
        } catch (Throwable t) {
            return "";      // 正则/输入任何异常都不许冒泡（畸形载荷绝不抛）
        }
    }

    /** 收进卡片映射：第一个<b>非空</b>值胜（同键后面再来的不覆盖）。 */
    private static void putOnceMap(Map<String, String> out, String key, String val) {
        if (out == null || Str.blank(key) || val == null) return;
        String v = Str.oneLine(val);
        if (v.isEmpty() || out.containsKey(key)) return;
        out.put(key, v);
    }

    /**
     * 写进事实对象：段里已经有这个键就<b>不覆盖</b>（段里的为准），值压单行 + 截断到
     * {@link #CARD_FIELD_MAX}。
     */
    private static void putOnce(JsonObject f, String key, String val) {
        if (f == null || Str.blank(key) || val == null) return;
        if (f.has(key)) return;
        String v = Str.oneLine(val);
        if (v.isEmpty()) return;
        f.addProperty(key, Str.cut(v, CARD_FIELD_MAX));
    }


    // ==================== 申请 ====================

    private void handleRequest(JsonObject raw, Ev ev) {
        // 发起申请的人：不是主人（除非真的是主人），技能据此做审批策略
        Caller requester = new Caller(Caller.Entry.QQ, ev.userId(), ev.isGroup() ? ev.groupId() : 0,
                ev.senderRole(), isMaster(ev.userId()), boot.favor().of(ev.userId()), ev.senderName());
        // 本地黑名单预过滤（与消息路径 :226 同一口径）：被拉黑的人连 on_request 钩子都不能触发。
        if (blocked(requester.qq())) {
            facts("request skip reason=blocked qq=" + requester.qq() + "（本地黑名单；不派给 on_request 钩子）");
            return;
        }
        // 结构事实：谁的什么申请（flag 是对外凭据、不是正文；验证信息正文只在 logVerbose 出）
        facts("request type=" + ev.requestType() + " qq=" + requester.qq()
                + (ev.isGroup() ? " g=" + ev.groupId() : "") + " flag=" + ev.flag());
        detail("comment=" + Str.cut(Str.oneLine(ev.comment()), 200));
        JsonObject payload = raw.deepCopy();
        payload.add("_caller", callerJson(requester));
        payload.addProperty("_handled", false);
        if (boot.skills() != null) {
            boot.skills().dispatch(Skills.ON_REQUEST, payload, requester, new Turn(requester, null));
        }
        if (!J.b(payload, "_handled", false)) {
            facts("request handled=0（按设计：基板不自带审批策略）");
        } else {
            facts("request handled=1（技能接管）");
        }
    }

    // ==================== 身份 ====================

    /** 事件 → 调用者（入口=QQ；控制台不是通道，这里只是"在哪说话"）。 */
    public Caller callerOf(Ev ev) {
        long qq = ev.userId();
        boolean master = isMaster(qq);
        String role = ev.senderRole();
        long gid = ev.isGroup() ? ev.groupId() : 0;
        return new Caller(Caller.Entry.QQ, qq, gid, role, master, 0, ev.senderName());
    }

    public boolean isMaster(long qq) {
        if (qq <= 0) return false;
        if (qq == conf.masterQQ()) return true;
        List<String> list = J.strings(conf.all(), "masterQQs");
        for (String s : list) {
            try {
                if (Long.parseLong(s.trim()) == qq) return true;
            } catch (Exception ignored) {
            }
        }
        String raw = J.s(conf.all(), "masterQQs", "");
        if (Str.has(raw)) {
            for (String s : raw.split("[,\\s]+")) {
                try {
                    if (Long.parseLong(s.trim()) == qq) return true;
                } catch (Exception ignored) {
                }
            }
        }
        return false;
    }

    /**
     * 调用者 → 钩子 payload 里的 {@code _caller}（<b>这条消息是谁发的</b>）。
     *
     * <p>只有<b>身份</b>：{@code entry/qq/session/master/favor/name/group/role}。
     * 它里面<b>不含</b>任何权限结论 —— 这台机器上只有两种人：主人与她本人<b>恒全权</b>，
     * {@code ALLUSER} 才按技能管控表（身份 × op → Ban / Run / 空）受管；
     * 具体某个 op 放不放行由拿着这份身份的判定主体现算。</p>
     */
    public static JsonObject callerJson(Caller c) {
        JsonObject o = new JsonObject();
        if (c == null) return o;
        o.addProperty("entry", c.isConsole() ? "console" : "qq");
        o.addProperty("qq", c.qq());
        o.addProperty("session", c.session());
        o.addProperty("master", c.master());
        o.addProperty("favor", (long) c.favor());
        o.addProperty("name", c.name());
        if (c.isGroup()) {
            o.addProperty("group", c.groupId());
            o.addProperty("role", c.groupRole());
        }
        return o;
    }

    private static String stripAt(String text) {
        if (text == null) return "";
        return text.replaceAll("\\[CQ:at,[^\\]]*\\]", "").trim();
    }

    /** 当前是否连上 NapCat（工具层据此给"Napcat 没有连接"）。 */
    public boolean connected() {
        return boot.link() != null && boot.link().connected();
    }

    public String notConnectedNote() { return Api.NOT_CONNECTED; }

    /** 诊断：已知主人列表（含 masterQQs 里的附加号）。 */
    public List<Long> masters() {
        List<Long> l = new ArrayList<Long>();
        if (conf.masterQQ() > 0) l.add(conf.masterQQ());
        List<String> extra = J.strings(conf.all(), "masterQQs");
        String raw = J.s(conf.all(), "masterQQs", "");
        if (Str.has(raw)) extra.addAll(Str.split(raw, ","));
        for (String s : extra) {
            try {
                long v = Long.parseLong(s.trim());
                if (v > 0 && !l.contains(v)) l.add(v);
            } catch (Exception ignored) {
            }
        }
        return l;
    }
}
