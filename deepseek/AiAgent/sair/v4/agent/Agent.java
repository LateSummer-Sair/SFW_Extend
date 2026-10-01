package sair.v4.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.ai.DeepSeek;
import sair.v4.auth.Caller;
import sair.v4.ctx.CtxBuild;
import sair.v4.ctx.Sink;
import sair.v4.ctx.Turn;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.kit.Th;
import sair.v4.prompt.Prompts;
import sair.v4.store.Store;
import sair.v4.tool.Registry;

/**
 * Agent 架构（基板④）：主 Agent 跑一轮 + 派子 Agent + 清单表（台账）。
 * <p>子 Agent 的能力由主 Agent 指定（工具白名单，仍受权限筛表约束），不向下再派发；
 * 异步子 Agent 完成后回灌主 Agent 并触发一轮收尾回复。</p>
 */
public final class Agent {

    private final Conf conf;
    private final Out out;
    private final DeepSeek ai;
    private final Registry registry;
    private final Store store;
    private final CtxBuild ctx;
    private final Prompts prompts;

    private final AtomicLong seq = new AtomicLong();
    private final Set<Loop> active = Collections.newSetFromMap(new ConcurrentHashMap<Loop, Boolean>());
    /** 后台回合的调度器（由基板装配注入：与主回合共用同一套按会话分道的队列，模型调用不并发）。 */
    private volatile sair.v4.schedule.Dispatcher dispatcher;
    /** 在跑的子 Agent 数（上限见 conf.subAgentMax）。 */
    private final java.util.concurrent.atomic.AtomicInteger subs = new java.util.concurrent.atomic.AtomicInteger();
    /** 插件生命周期钩子（基板⑦；装配期由 Boot 注入，没注入 = 零钩子 = 与今天逐字节一致）。 */
    private volatile sair.v4.ext.ExtRegistry ext;

    public Agent(Conf conf, Out out, DeepSeek ai, Registry registry, Store store, CtxBuild ctx, Prompts prompts) {
        this.conf = conf;
        this.out = out;
        this.ai = ai;
        this.registry = registry;
        this.store = store;
        this.ctx = ctx;
        this.prompts = prompts;
    }

    /** 注入插件生命周期钩子（基板⑦；装配期调一次）。 */
    public void setExt(sair.v4.ext.ExtRegistry e) { this.ext = e; }

    public Registry registry() { return registry; }

    /**
     * 这一轮没跑成时对外说的那句<b>兜底话</b>（人设口吻，不含任何内部错误）。
     * <p>为什么不把错误原文发出去：会话里坐着的是用户，不是排障的人。原因照旧进控制台警告行。</p>
     */
    private static final String FAIL_NOTICE = "诶，我这边刚卡了一下，没接上话……缓一缓我再说，别急哈。";

    /** 同一会话两次兜底话之间的冷却（毫秒）：连续失败不要每轮都回一句，免得刷屏。 */
    private static final long FAIL_NOTICE_COOLDOWN_MS = 120000L;

    /** 各会话上次说兜底话的时刻（进程内即可；热重载重置无妨）。 */
    private static final java.util.Map<String, Long> FAIL_NOTICE_AT =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /** 对外兜底话：优先用外挂文案（{@code prompts/tools-index.md} 的「失败回话」），拿不到就用内置那句。 */
    private String failNotice() {
        try {
            if (conf != null) {
                String s = sair.v4.tool.ToolIndex.of(conf).failNoticeText();
                if (s != null && !s.trim().isEmpty()) return s.trim();
            }
        } catch (Throwable ignored) {
        }
        return FAIL_NOTICE;
    }

    /**
     * 这个调用者手上有没有"显式说话"的手（{@code send} 这类发送工具看得见吗）。
     * <p>判定失败按"有手"处理（保守：宁可她自己说，也不擅自替她开口）。</p>
     */
    private boolean canSpeak(Caller c) {
        try {
            if (registry == null) return true;
            return registry.visibleNames(c).contains("send");
        } catch (Throwable t) {
            return true;
        }
    }

    public CtxBuild ctx() { return ctx; }

    /** 注入后台回合调度器（基板的按会话分道队列）。 */
    public void setExecutor(sair.v4.schedule.Dispatcher d) { this.dispatcher = d; }

    /** 把一个后台回合排进队列（闹钟、异步子 Agent 都走这里，避免与主回合并发打模型）。 */
    public void submit(Runnable r) {
        submit("", false, r, null);
    }

    /**
     * 会话感知版：按会话分道（同会话保序）+ 优先级（主人/私聊优先）。
     *
     * @param notice 溢出时回话的落点（可为 null = 没有落点，调度器会据实不把它算成"已告知"）
     */
    public void submit(String session, boolean high, Runnable r, sair.v4.schedule.SimpleJob.Notice notice) {
        sair.v4.schedule.Dispatcher d = dispatcher;
        if (d == null) {
            Th.start("v4-bg", r);
            return;
        }
        d.submit(session, high, new sair.v4.schedule.SimpleJob(session, high, r, notice));
    }

    /** 正在跑的子 Agent 数。 */
    public int subs() { return subs.get(); }

    // ==================== 主入口 ====================

    /**
     * <b>本会话最近 N 条聊天记录</b>（{@link sair.v4.ctx.ChatWindow}）挂到这一轮的 {@link Turn} 上。
     *
     * <p>写进 {@link CtxBuild#ST_CHAT_WINDOW}，由 {@link CtxBuild#facts} 拼进事实块的<b>最后一段</b>
     * （紧挨着后面的对话历史消息）。读不出来 / 没有记录 / 配置关掉（{@code chatWindowSize<=0}）
     * 时<b>什么都不写</b> —— 事实块里就没有这一段，绝不写一段空壳。</p>
     *
     * <p>谁调它（= 谁能看到窗口）：{@link #ask}（主回合：QQ 触发 / 本地控制台 / 定时投递）与
     * {@link #execSub}（会话里的子 Agent，含<b>触发词接话</b>那条路）。收尾回合
     * （{@code wake} → {@code assembleSystem}）不调：那一轮是内部记账，不是"在会话里说话"。</p>
     */
    private void putChatWindow(Turn t, Caller c) {
        if (t == null || store == null) return;
        try {
            String win = sair.v4.ctx.ChatWindow.facts(conf, store, c, out);
            if (Str.has(win)) t.put(CtxBuild.ST_CHAT_WINDOW, win);
        } catch (Throwable e) {
            // 窗口做不出来只是"这一轮少一段事实"，绝不能把回合拖垮（与偏好块同一口径）
            if (out != null) out.warn("[agent] 本会话聊天记录窗口构建失败（这一轮不带窗口）: " + e);
        }
    }

    /** 一句话进来，一次完整对话出去（含历史装配与落库）。 */
    public Loop.Outcome ask(Caller c, Sink sink, String text) {
        return ask(c, sink, text, null);
    }

    /**
     * 一次完整对话（可带本轮的<b>结构化事实</b>行）。
     *
     * @param mediaFact 本轮的事实行，形如 {@code media: [{"type":"image","file":"…","url":"…"}]}；
     *                  非空时进事实块 system。当 {@code text} 为空（只发了媒体）时，
     *                  它<b>同时就是这一轮的用户输入</b> —— 不编任何自然语言句子，也不留空 user 轮
     *                  （严格的 provider 会因空 content 直接 400），并照样落进 dialog 历史，
     *                  这样"回复了哪张图"在下一轮装配历史时仍然对得上。
     */
    public Loop.Outcome ask(Caller c, Sink sink, String text, String mediaFact) {
        return ask(c, sink, text, mediaFact, "");
    }

    /**
     * 与 {@link #ask(Caller, Sink, String, String)} 同一个入口，多一个<b>"这一轮是怎么被叫到的"</b>
     * （能力③的事实来源，取值域见 {@link CtxBuild#AD_TRIGGER} … {@link CtxBuild#AD_SYSTEM}）。
     *
     * <p><b>谁给</b>：起这一轮的那个产者 —— {@code QqGateway.submitTurn}（{@code trigger/at/private/master}）、
     * {@code Boot.askConsole}（{@code master}）、{@code Tick}（{@code system}）。
     * <b>{@code CtxBuild.facts} 只渲染、绝不重推</b>（重推就是第二个产法：只有产者知道这一轮为什么起来）。</p>
     *
     * <p><b>空串 = 没有产者表态</b> ⇒ {@code Turn} 上不写这个键 ⇒ 事实块里没有 {@code addressed:} 那一行
     * （取不到就不写，不是写个空值）。这里只是一个"贴上去"的动作，不做任何判定、不读配置。</p>
     */
    public Loop.Outcome ask(Caller c, Sink sink, String text, String mediaFact, String addressed) {
        Turn t = new Turn(c, sink);
        if (Str.has(mediaFact)) t.put(CtxBuild.ST_MEDIA, mediaFact.trim());
        // ★ 能力③：把"怎么被叫到的"贴到 Turn 上（渲染在 CtxBuild.facts，判据在产者那一侧）
        if (Str.has(addressed)) t.put(CtxBuild.ST_ADDRESSED, Str.trim(addressed));
        // ★ 本会话最近 N 条聊天记录（键 chatWindowSize，默认 30）：**每触发一个回合就在这里现查一次**。
        //   放在 ask 里（而不是某个入口网关里）是为了"不论哪条触发路都同一份"：
        //   QQ 入站触发、本地控制台的 chat、定时/闹钟投递，三条路都从这里进，
        //   于是私聊、群、控制台处理完全一致（主人 2026-09-16：私聊和群聊都要有）。
        //   它替掉的是原来的游标快照（由 QqGateway 在提交回合前做好再传进来）——
        //   现在没有游标、不落进度状态，窗口就是"此刻这个会话最近的 N 条"。
        putChatWindow(t, c);
        String input = Str.has(text) ? text : Str.nz(mediaFact);
        // 落 dialog = 她自己的自主行为（判定主体 = SYSTEM，她本人恒全权）：这里**不做权限判定** ——
        // 资源不再有位、技能管控表也管不到她自己这条线，没有任何 op 可判（旧口径的"先判 C 类 W 位"
        // 整段已随资源位删除）。
        ctx.assemble(t, input);
        // ★ 落点纪律（2026-09-15 真机事故：定时那一轮的正文被"投递通路"直接发进了群，而那条正文是
        //   "已经查了并回群里了…得跟你说清"这种**回执口吻**——它不是对用户说的话）。
        //   两种落点的规矩不一样：
        //   ① 会话落点（QQ/本地控制台/面板）：正文就是她的回复本身（"牌面"就从这里出）；
        //   ② 投递通路（TaskSink：定时/闹钟/task 的落点，deliverTo() 非空）：**正文不再自动外发** ——
        //      那条隐式通路一律关掉，要对他们说话必须她自己显式调发送类工具（人设口吻、翻译成人话）。
        final String deliverTo = sink == null ? null : sink.deliverTo();
        final boolean deliveryPath = Str.has(deliverTo);
        // 投递通路的例外：**这个调用者手上没有"显式说话"的手**（普通用户的回合按 D43 工具面为空）
        // ⇒ 正文直投就是他要听到的那句话。否则"别人委派到点的事"永远送不出去（真机实测：
        // 她把提醒写好了，却因为没有 send 的位而只能记 failed）。有手的情况照旧一律关掉（防回执腔泄漏）。
        final boolean noSpeakTools = deliveryPath && !canSpeak(t.caller());
        if (deliveryPath) {
            if (noSpeakTools) {
                t.addSystem("delivery: {\"session\":\"" + deliverTo + "\"} —— ★这一轮你的正文**会直接发到那个会话**"
                        + "（你手上没有显式发消息的工具）：写出来的就是你对他们说的话 —— 人话、自然、不端着；"
                        + "不许写汇报/回执腔（「已经查了」「已安排」「子 Agent 说…」都不是人话），"
                        + "不许写任务号、message_id、工具名、调度过程、内部键值；要 @ 谁就在正文里写 @ 标记。");
            } else {
                t.addSystem("delivery: {\"session\":\"" + deliverTo + "\"} —— ★这一轮是**投递通路**的回合："
                        + "你的正文**不会被发出去**（没有人替你转达），它只留在基板里。"
                        + "要对他们说话，就必须自己**显式调发送类工具**（send 等），而且："
                        + "① 只发给他们（这个会话），不要发给别人；② 内容要说成人话（人设口吻、自然、不端着），"
                        + "**不要**写汇报/回执腔（「已经查了」「得跟你说清」「已安排」「已回复」"
                        + "「子 Agent 说…」这类都不是对用户说的话）；③ 不要写任务号、message_id、"
                        + "工具名、调度过程、内部键值。拿不准就问自己一句：这句话发给他们看，体面吗。");
            }
        }
        if (store != null && Str.has(input)) store.appendDialog(t.session(), "user", input, 0);
        // 生命周期：回合开始（基板⑦）。钩子拿到的"本轮正文"就是这一轮的用户输入；
        // 一个钩子都没挂 = 一行代码都不多走（见 ext.ExtRegistry.on()）。
        hookStart(t, input);
        Loop.Outcome oc = run(t);
        if (oc.ok() && Str.has(oc.text)) {
            if (store != null) store.appendDialog(t.session(), "assistant", sair.v4.qq.Seg.forMemory(sair.v4.qq.MarkerTags.strip(oc.text)), 0);
            // ★ 唯一对外出口 = 她的显式说话动作（发送类工具）。基板的"正文自动投递"这条隐式通路：
            //   ① 投递通路（TaskSink：定时/闹钟/task）—— **一律关掉**（真机事故：她本来就自己 send 了，
            //      那 145 字的回执正文又被多投了一遍到群里）；
            //   ② 会话回复（QQ/控制台）—— 正文就是她的回复，保留；但**本回合已经对外发过**时不再补一条
            //      （F1 去重：判据见 alreadyOut，"说过就不再拿回合正文补一句"）。
            if (sink != null && deliveryPath && !noSpeakTools) {
                if (out != null) {
                    out.dim("[agent] 投递通路（" + deliverTo + "）：正文不外发（要说话得她显式调发送类工具）"
                            + " chars=" + oc.text.length());
                }
            } else if (sink != null && !alreadyOut(t, t.session())) {
                hookSend(t, oc.text);
                sink.say(oc.text);
            } else if (sink != null && out != null) {
                out.dim("[agent] 本回合已对外发过 → 正文不再自动投递（chars=" + oc.text.length() + "）");
            }
        } else if (!oc.ok() && sink != null) {
            // 失败兜底同理：投递通路的回合不替她说话（她自己没说 = 这一轮没有对外的话）。
            if (!deliveryPath) {
                // 对外只说人话：**不把内部错误抛给会话**（真机事故 2026-09-19：群里看到
                // 「（本轮失败：SocketTimeoutException: connect timed out）」）。原因只进控制台日志。
                // 文案外挂在 prompts/tools-index.md 的「失败回话」；同一会话 2 分钟内不重复说（免得刷屏）。
                String notice = failNotice();
                long nowMs = System.currentTimeMillis();
                Long last = FAIL_NOTICE_AT.get(t.session());
                if (last != null && nowMs - last.longValue() < FAIL_NOTICE_COOLDOWN_MS) {
                    if (out != null) out.warn("[agent] 本轮失败，冷却中不回话（原因：" + oc.error + "）");
                } else {
                    FAIL_NOTICE_AT.put(t.session(), Long.valueOf(nowMs));
                    if (out != null) out.warn("[agent] 本轮失败，对外用兜底回话（原因：" + oc.error + "）");
                    hookSend(t, notice);
                    sink.say(notice);
                }
            } else if (out != null) {
                out.warn("[agent] 投递通路（" + deliverTo + "）：本轮失败且不代发（" + oc.error + "）");
            }
        }
        // 生命周期：回合结束（成功与失败各通知一次；失败时 reply 为空串、error 非空）
        hookEnd(t, oc.ok() ? oc.text : "", oc.ok() ? "" : oc.error);
        return oc;
    }

    /** 跑一个已装配好的 Turn。 */
    public Loop.Outcome run(Turn t) { return run(t, null); }

    public Loop.Outcome run(Turn t, List<String> onlyTools) { return run(t, onlyTools, null); }

    /** 跑一轮，并指定使用的模型（子 Agent 视觉兜底用）。 */
    public Loop.Outcome run(Turn t, List<String> onlyTools, String model) {
        return run(t, onlyTools, model, false);
    }

    /**
     * 跑一轮。
     *
     * @param noSpeak true = <b>这一轮不给"能对用户说话"的工具</b>（收尾回合用：只记账、不发言）
     */
    public Loop.Outcome run(Turn t, List<String> onlyTools, String model, boolean noSpeak) {
        Loop loop = new Loop(conf, out, ai, registry, prompts);
        if (model != null && !model.trim().isEmpty()) loop.setModel(model.trim());
        if (noSpeak) loop.setNoSpeak(true);
        active.add(loop);
        sair.v4.ctx.Ctx.Scope scope = sair.v4.ctx.Ctx.of(t == null ? null : t.caller());
        try {
            return loop.run(t, onlyTools);
        } finally {
            scope.close();
            active.remove(loop);
        }
    }

    /** 中断所有在跑的回合（进程收尾 / 主人停全部）。 */
    public void stop() {
        stop(null, true);
    }

    /**
     * 按会话停止在跑的回合。
     *
     * <p>多会话并行之后，"一个用户敲停"不能变成"打断所有人的回合"：<b>主人 / session 为空</b>
     * 才停全部，其他调用者只停<b>自己那个会话</b>的回合（别的会话照跑）。</p>
     *
     * @param session 会话键（{@code group:<群号>} / {@code qq:<QQ>} / {@code console}）；null 或空 = 全部
     * @param master  是否主人（主人可停全部）
     * @return 真正被停的回合数
     */
    public int stop(String session, boolean master) {
        int n = 0;
        boolean all = master || session == null || session.trim().isEmpty();
        for (Loop l : active) {
            if (all || session.trim().equals(l.session())) {
                l.stop();
                n++;
            }
        }
        return n;
    }

    /** 在跑的回合所属会话快照（诊断：谁在跑）。 */
    public java.util.List<String> runningSessions() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        for (Loop l : active) {
            String s = l.session();
            if (!out.contains(s)) out.add(s);
        }
        return out;
    }

    // ==================== 子 Agent ====================

    /**
     * 派一个子 Agent。tools 为空 = 用主 Agent 的（权限筛表后的）全部工具。
     * async=true 立即返回任务票，后台执行，完成后回灌并触发收尾。
     *
     * <p>默认 {@code reply=false}：结论<b>内部回灌</b>给主 Agent（不外发）。</p>
     *
     * <p><b>子 Agent 的工具表永远不含 {@code agent}</b>（D46★2 闸①，禁止嵌套）：显式传进来的
     * {@code agent} 会被剔除，而"tools 为空 = 继承主 Agent 全部工具"那条路继承出来的表同样不含它
     * —— 两条路都过 {@link #subTools}。</p>
     */
    public JsonObject spawn(final Caller c, final Sink sink, String task,
                            List<String> tools, final boolean async) {
        return spawn(c, sink, task, tools, async, null);
    }

    /**
     * 派一个子 Agent。
     *
     * @param brief 子 Agent 的<b>变化部分</b>提示词 —— 由主 Agent 自己生成（角色、边界、输出格式、验收标准）。
     *              公共部分在外挂文件 {@code prompts/subagent.md} 里，基板不带任何正文。
     */
    public JsonObject spawn(final Caller c, final Sink sink, String task,
                            List<String> tools, final boolean async, final String brief) { return spawn(c, sink, task, tools, async, brief, null); }

    /** 派子 Agent，并指定它用的模型（例如视觉兜底用会看图的模型）。 */
    public JsonObject spawn(final Caller c, final Sink sink, String task,
                            List<String> tools, final boolean async, final String brief, final String model) {
        return spawn(c, sink, task, tools, async, brief, model, false);
    }

    /**
     * 派子 Agent（完整参数）。
     *
     * @param reply {@code true} = 这一单就是"替机器人说一句话"（技能派的接话/通报）：
     *              子 Agent 写成什么，<b>由宿主替它发出去</b>（它自己没发过、且内容不是"我不想说"时才会发）。
     *              宿主投递属于"机器人自己说话"，不走发送工具的权限门禁；
     *              {@code false} = 内部材料，结论只回灌给主 Agent，用户看不到。
     */
    public JsonObject spawn(final Caller c, final Sink sink, String task,
                            List<String> tools, final boolean async, final String brief, final String model,
                            final boolean reply) {
        final long id = nextId();
        final String label = "sub-" + id;
        // 闸①（D46★2）：子 Agent 的工具表**永远不含 agent** —— 显式传的与"继承主 Agent 全部工具"
        // 两条路都在这里滤（见 subTools 的 javadoc）。
        final List<String> toolList = subTools(tools, c);
        final JsonObject row = new JsonObject();
        row.addProperty("ts", System.currentTimeMillis());
        row.addProperty("status", "running");
        row.addProperty("owner", c == null ? "" : c.session());
        row.addProperty("agent", label);
        row.addProperty("tools", Str.join(toolList, ","));
        row.addProperty("task", task);
        row.addProperty("result", "");
        row.addProperty("ms", 0);
        // 白名单里的名字不再是"静默丢弃"：分成"压根没有这个名字"与"有但你没权限"两类事实，
        // 并把该调用者可见的清单一并回传（V3 的 worker 路径就是这么回 `{available}` 的）。
        fillToolFacts(row, c, toolList);
        final long taskId = store == null ? 0 : store.addTask(row);

        // 并发上限（conf.subAgentMax）：超了就不派，直接回话，避免把线程/队列打爆。
        // 先占坑再判（原来的 `get() >= max` 之后才 increment 是典型的 check-then-act 竞态：
        // 高并发下同时派发会把上限顶穿）。
        int max = Math.max(1, conf.subAgentMax());
        if (subs.incrementAndGet() > max) {
            subs.decrementAndGet();
            JsonObject r = new JsonObject();
            r.addProperty("id", taskId);
            r.addProperty("status", "rejected");
            r.addProperty("error", "同时在跑的子 Agent 已达上限 " + max + "，等它们回来再派。");
            return r;
        }

        if (!async) {
            try {
                String result = execSub(c, task, toolList, label, brief, model);
                finishTask(taskId, result, System.currentTimeMillis(), true);
                JsonObject r = new JsonObject();
                r.addProperty("id", taskId);
                r.addProperty("status", "done");
                r.addProperty("result", result);
                fillToolFacts(r, c, toolList);
                return r;
            } finally {
                subs.decrementAndGet();
            }
        }

        final long startedAt = System.currentTimeMillis();
        final sair.v4.schedule.SimpleJob.Notice subNotice = sair.v4.schedule.SimpleJob.sink(sink);
        final boolean deliver = reply;
        submit(c == null ? "" : c.session(), c != null && (c.master() || !c.isGroup()), new Runnable() {
            @Override
            public void run() {
                String result;
                Holder h = new Holder();
                try {
                    result = execSub(c, task, toolList, label, brief, model, h);
                } catch (Throwable e) {
                    result = "子 Agent 异常: " + e;
                } finally {
                    subs.decrementAndGet();
                }
                finishTask(taskId, result, startedAt, false);
                // ① 这一单是"替机器人说一句话"（技能派发的接话/通报）：由**宿主**把它投递出去。
                //    投递条件三条：明确声明了 reply、子 Agent 自己没发过（免得说两遍）、内容不是"我不想说"。
                //    —— 宿主投递是"机器人自己说话"，与模型调 send 工具不是一回事：它不受发送工具的权限门禁，
                //       所以群里任何人命中触发词都能得到一句回复（V3 的口径）。
                boolean delivered = false;
                if (deliver && sink != null && Str.has(result) && !h.spoke && !silent(result)) {
                    String body = sair.v4.qq.MarkerTags.strip(result);
                    if (Str.has(body)) {
                        if (store != null) store.appendDialog(c == null ? "" : c.session(), "assistant", body, 0);
                        sink.say(body);
                        delivered = true;
                        if (out != null) out.dim("[agent] " + label + " 完成 → 已替你回话（chars=" + body.length() + "）");
                    }
                }
                // ② 收尾回合仍是内部回合：正文不自己发出去、也不写进对话历史（"Agent 之间的交流"不该
                //    出现在用户的聊天里）。它只有**恰好一次**对外投递机会：把结论报给发起会话（B2）。
                //    ★ 但这一单刚刚已经替她投递过（delivered=true）⇒ 收尾结论不再投递，免得同一件事说两遍
                //      （这就是"不得与定时投递重复"：技能派的定时单是 reply=true，投递与收尾在同一次派发里）。
                try {
                    if (sink != null) wake(c, sink, "任务 " + taskId + "（" + label + "）已完成：\n" + result, delivered);
                } catch (Throwable e) {
                    if (out != null) out.err("[agent] 收尾失败: " + e);
                }
            }
        }, subNotice);

        JsonObject r = new JsonObject();
        r.addProperty("id", taskId);
        r.addProperty("status", "running");
        r.addProperty("agent", label);
        r.addProperty("tools", Str.join(toolList, ","));
        r.addProperty("note", reply
                ? "已派出；它写成什么就由我替它发出去（它自己不会再发一遍），本轮不用等。"
                : "已派出，完成后会自动回灌结论（本轮不需要等待）；结论不会直接发给用户。");
        fillToolFacts(r, c, toolList);
        return r;
    }

    /** 子 Agent 一轮的输出（正文 + 它自己有没有发过话）。 */
    private static final class Holder {
        boolean spoke = false;
    }

    /**
     * 静默哨兵：子 Agent 想说"这轮我不说话"时只回这个，宿主就不发任何消息。
     * <p>技能的任务文案里会写清楚（不想说就回 {@code <silent>}）——比"回一句解释为什么不说"干净得多。</p>
     *
     * <p><b>词表就是下面那几个字面量（唯一一处）</b>：{@code <silent>} / {@code [silent]} /
     * {@code silent}（裸词）、{@code <不语>} / {@code [不语]} / {@code 不语} / {@code (不语)}、
     * {@code 不发言} / {@code [不发言]}。口径：</p>
     * <ul>
     *   <li><b>大小写不敏感</b>、<b>半角空格不敏感</b>（先 trim、再 lowercase、再去空格）；</li>
     *   <li><b>括号 / 尖括号的半角与全角都认</b>（口径与 {@link sair.v4.qq.ToolMarkup} 的
     *       半角/全角一致）：{@code ＜＞}（U+FF1C/U+FF1E）、{@code ［］}（U+FF3B/U+FF3D）、
     *       {@code （）}（U+FF08/U+FF09）一律先折成半角再比 —— 审计 §7-4 的缝：
     *       原来只认 {@code <silent>}，全角写法 {@code ＜silent＞} 会漏出去；</li>
     *   <li>{@code null} / 空串 / 纯空白 = {@code true}（"没有正文"也算"不出声"）。
     *       ⚠ 调用方要自己区分"空白不是暗号"：{@code term.Sinks.gate} 与 {@code qq.Api.silentFact}
     *       都先排掉"正文是空/纯空白"再调这里（否则空白段会被当成暗号丢掉）。</li>
     * </ul>
     * <p>判据<b>一律复用这一处</b>：{@code term.Sinks.gate}（逐条）与 {@code qq.Api}
     * （{@code call} / {@code callQuiet} / {@code sendText}）都调这个函数，没有第二套词表。</p>
     */
    public static boolean silent(String text) {
        if (text == null) return true;
        String t = text.trim().toLowerCase().replace(" ", "");
        if (t.isEmpty()) return true;
        t = foldBrackets(t);
        return t.equals("<silent>") || t.equals("[silent]") || t.equals("silent")
                || t.equals("<不语>") || t.equals("[不语]") || t.equals("不语")
                || t.equals("(不语)") || t.equals("不发言") || t.equals("[不发言]");
    }

    /**
     * <b>可见词表里"带括号"的那一半</b>（{@link #stripSilent} 唯一用它）——就是 {@link #silent}
     * 那份字面量清单里所有<b>带括号</b>的写法，<b>一个字都不新加、也不减</b>（{@code silent} /
     * {@code 不语} / {@code 不发言} 三个<b>裸词</b>刻意不在表里：裸词会出现在正常中文里，
     * 见 {@link #stripSilent} 的说明）。比之前先 lowercase、去掉半角空格，再把全角括号折成半角
     * （{@link #foldBrackets}，与 {@code silent} 同一支口径）。
     */
    private static final String[] SILENT_TOKENS = {
        "<silent>", "[silent]", "<不语>", "[不语]", "(不语)", "[不发言]"
    };

    /**
     * <b>剥掉"贴在正文首/尾"的带括号协议暗号</b>（{@code <silent>} 一族；真机事故 2026-09-28 01:20
     * 群 {@code 543986616}、{@code sent_id=3280} 与 2026-09-24 22:08 群 {@code 70559059}、
     * {@code sent_id=2594}：正文是「…我照最新那条走。 {@code <silent>}」/「{@code <silent>} 等等，…」，
     * <b>整条不相等</b> ⇒ 旧的"整条相等才拦"判据放行，暗号字面量真进了群）。
     *
     * <p><b>加性</b>：{@link #silent} 的语义一个字没动（它仍被多处调用），这一支只回答
     * "把贴边的暗号剃掉之后，这句话还剩什么"。落点见 {@code term.Sinks.gate} ④ 与
     * {@code qq.Api.silentFact}（两处出站口共用这一支判据，不写第二套词表）。</p>
     *
     * <p><b>算暗号（可剥）</b>：就是 {@link #SILENT_TOKENS} 那六个带括号写法，尖/方/圆括号的
     * <b>半角与全角都认</b>（{@code ＜＞} {@code ［］} {@code （）} 先折成半角）、
     * <b>大小写不敏感</b>、<b>半角空格不敏感</b>（口径与 {@link #foldBrackets} /
     * {@link #silent} 一致）。</p>
     *
     * <p><b>只剥"贴边"的</b>（在 {@code trim} 之后的坐标上）：token 在<b>开头</b>（后面紧跟空白或
     * 就是结尾）或在<b>结尾</b>（前面紧接着空白或就是开头）。</p>
     *
     * <p><b>绝对不动</b>：① 夹在句子中间的 token（{@code 她回了一句 <silent> 试试} —— 那时它是
     * 正文的一部分，口径与 {@link #silent} / {@code Sinks.gate} 的既有注释一致）；② <b>裸词</b>
     * {@code silent} / {@code 不语} / {@code 不发言}（没有括号）——除非整条正文就是它
     * （那由 {@link #silent} 覆盖）。反例依据（真机 {@code grouplog #403457} 群
     * {@code 543986616}）：她的正常正文里有「她<b>发不发言</b>我哪知道呀」，
     * 末尾的 {@code 不发言} 是真实中文的一部分 —— 所以裸词一律不剥，且 {@code trim} 之后
     * <b>逐字节原样返回</b>。</p>
     *
     * <p><b>返回</b>：命中就返回剥完并 {@code trim} 的结果（<b>可能是空串</b>
     * ⇒ 调用方按"这一条不发"处理；能循环剥到不再变）；<b>没命中就返回原对象</b>
     * （{@code ==} 同一个引用，保证"没命中时逐字节不变"）。{@code null} 原样返回。
     * 只剥"括号 token 整体"：不猜半个括号、不动 token 之外的任何一个字符。</p>
     */
    public static String stripSilent(String text) {
        if (text == null || text.isEmpty()) return text;
        String cur = text;
        boolean hit = false;
        for (int round = 0; round < 8; round++) {
            String s = cur.trim();
            if (s.isEmpty()) break;
            int[] span = edgeSilent(s);
            if (span == null) break;
            hit = true;
            cur = (s.substring(0, span[0]) + s.substring(span[1])).trim();
        }
        return hit ? cur : text;      // 没命中 ⇒ 原对象（逐字节不变）
    }

    /**
     * <b>★ 2026-09-28「贴边暗号」批（续）：长度分段正好把一个括号暗号劈成两半时的"接缝"判据</b>。
     *
     * <p>{@code pre} = 前一段的<b>结尾</b>、{@code rem} = 后一段的<b>开头</b>：两者<b>归一化之后拼起来
     * 恰好等于</b> {@link #SILENT_TOKENS} 里的某一个（大小写 / 半角空格 / 全角括号的口径与
     * {@link #silent} 逐字相同，<b>复用 {@link #isSilentToken}，不写第二套词表</b>）⇒ 这两半合起来
     * 正是一个完整的协议暗号，谁都不许单独进群（真机形状：长度分段正好切在 {@code <sil} + {@code ent>}
     * 中间时，两个残片各自都不满足"整条相等"也不满足"贴边" ⇒ 两条都真发出去了）。</p>
     *
     * <p><b>刻意只回答"正好拼成一个完整暗号"</b>：{@code <s} / {@code [s} / {@code <sil} 这种
     * "半截前缀"单独拿来问，一律 {@code false} —— 理由与 {@link #stripSilent} 同一条口径：
     * 这类前缀在<b>正常正文</b>里真的会出现（HTML / 命令行片段被切在 {@code <s} 上），而
     * {@code <silent>} 的语义是"<b>这一条</b>别出声"、<b>不是</b>"从这儿闭嘴"，所以判据只能落在
     * "拼出来就是一个完整暗号"这一条上 —— 对不上就一个字都不动（{@code "…<s" + "cript>"}、
     * {@code "…[s" + "tart]"} 照旧原样发）。</p>
     *
     * <p>调用方只有一处：{@code term.Sinks} 的跨界剪除（分段之后、{@code stage} 之前）。
     * <b>加性</b>：{@link #silent} 与 {@link #stripSilent} 的语义一个字没动。</p>
     */
    public static boolean silentSeam(String pre, String rem) {
        if (pre == null || rem == null || pre.isEmpty() || rem.isEmpty()) return false;
        return isSilentToken(pre + rem);
    }

    /**
     * 找一个"贴边"的括号暗号 token，返回它在 {@code s} 里的 {@code [from,to)} 下标；没有返回 {@code null}。
     * 判据就是 {@link #SILENT_TOKENS}（大小写 / 半角空格 / 全角括号先归一化，同 {@link #silent}）。
     */
    private static int[] edgeSilent(String s) {
        if (s == null || s.isEmpty()) return null;
        // ---- 头：第一个字符就是左括号；token 后面必须紧跟空白或就是结尾 ----
        if (isOpen(s.charAt(0))) {
            int close = -1;
            for (int i = 1; i < s.length(); i++) {
                if (isClose(s.charAt(i))) { close = i; break; }
            }
            if (close > 0 && (close + 1 == s.length() || Character.isWhitespace(s.charAt(close + 1)))
                    && isSilentToken(s.substring(0, close + 1))) {
                return new int[] { 0, close + 1 };
            }
        }
        // ---- 尾：最后一个字符就是右括号；token 前面必须紧跟空白或就是开头 ----
        int last = s.length() - 1;
        if (isClose(s.charAt(last))) {
            for (int i = last; i > 0; i--) {
                if (isOpen(s.charAt(i)) && Character.isWhitespace(s.charAt(i - 1))) {
                    if (isSilentToken(s.substring(i))) return new int[] { i, s.length() };
                }
            }
            if (isOpen(s.charAt(0)) && isSilentToken(s)) return new int[] { 0, s.length() };
        }
        return null;
    }

    /** 一个候选 token（含括号）是不是词表里的暗号（归一化口径与 {@link #silent} 逐字相同）。 */
    private static boolean isSilentToken(String cand) {
        if (cand == null || cand.length() < 3) return false;
        String t = foldBrackets(cand.toLowerCase().replace(" ", ""));
        for (int i = 0; i < SILENT_TOKENS.length; i++) if (SILENT_TOKENS[i].equals(t)) return true;
        return false;
    }

    /** 左括号（半角与全角，口径与 {@link #foldBrackets} 一致）。 */
    private static boolean isOpen(char c) {
        return c == '<' || c == '[' || c == '('
                || c == '\uFF1C' || c == '\uFF3B' || c == '\uFF08';
    }

    /** 右括号（半角与全角，口径与 {@link #foldBrackets} 一致）。 */
    private static boolean isClose(char c) {
        return c == '>' || c == ']' || c == ')'
                || c == '\uFF1E' || c == '\uFF3D' || c == '\uFF09';
    }

    /**
     * 全角括号 / 尖括号折成半角（只有这一支口径，与 {@code qq.ToolMarkup} 的半角/全角一致）：
     * {@code ＜＞} {@code ［］} {@code （）} ⇒ {@code <>} {@code []} {@code ()}。
     * <p>半角原样返回（同一个对象），所以"本来就是半角"的路径逐字节不变。</p>
     */
    private static String foldBrackets(String s) {
        if (s == null) return null;
        if (s.indexOf('\uFF1C') < 0 && s.indexOf('\uFF1E') < 0 && s.indexOf('\uFF3B') < 0
                && s.indexOf('\uFF3D') < 0 && s.indexOf('\uFF08') < 0 && s.indexOf('\uFF09') < 0) {
            return s;
        }
        return s.replace('\uFF1C', '<').replace('\uFF1E', '>')
                .replace('\uFF3B', '[').replace('\uFF3D', ']')
                .replace('\uFF08', '(').replace('\uFF09', ')');
    }

    private String execSub(Caller c, String task, List<String> tools, String label, String brief, String model) {
        return execSub(c, task, tools, label, brief, model, null);
    }

    private String execSub(Caller c, String task, List<String> tools, String label, String brief, String model,
                           Holder holder) {
        Turn sub = new Turn(c, null);
        sub.markInternal();                  // 子 Agent 的正文一律不外发（Turn.say 会拦）
        sub.messages();     // 子 Agent 不带主会话历史（结论要自包含）
        // ★ 但"这个会话刚才说了什么"必须让它看见：技能派的子 Agent 是**唯一**的执行者
        //   （触发词接话走的就是这条路：技能 h.spawn(...) + payload._handled=true，基板不再自己回一轮），
        //   不给它窗口，它天生答不上"我刚才/刚才群里说了什么"——主人报的正是这个毛病。
        //   窗口仍然是**这个调用者自己那个会话**的最近 N 条（chatWindowSize），
        //   与主回合同一支、同一份事实（见 putChatWindow），不跨会话、不带别人的记录。
        putChatWindow(sub, c);
        // 子 Agent 身份标记：技能据此把"有副作用的写"改成"提议"（写者唯一 = 会话的主回合）
        sub.put("subagent", label);
        // ★ 这一轮到底是谁（P0-3 S1）：**必须写在 ctx.facts(sub) 之前**。
        //   写晚了（例如只在 Loop.setModel 里写）就是这个 bug：一个被派去"看图"的 flash 子 Agent
        //   在自己的事实块里读到 main_has_vision=false（那是主模型的），于是**拒绝看图**——
        //   派它来的意义当场消失。Loop.setModel 也写同一个源（幂等），但不能只有它。
        if (model != null && !model.trim().isEmpty()) sub.put(CtxBuild.MODELS_SELF, model.trim());
        // 1) 同一套身份（主/子 Agent 共享同一份 prompts/identity.md）
        String sys = prompts.system(sub.session());
        if (Str.has(sys)) sub.addSystem(sys);
        // 2) 子 Agent 的公共部分：外挂文件 prompts/subagent.md（可自定义；缺文件即空，基板不兜底）
        String common = prompts.subagentSystem(sub.session());
        if (Str.has(common)) sub.addSystem(common);
        // 3) 事实块 + 子 Agent 的键值事实（基板只给数据，不给文案）
        String facts = ctx.facts(sub);
        if (Str.has(facts)) sub.addSystem(facts);
        JsonObject subFacts = new JsonObject();
        subFacts.addProperty("label", label);
        // 与上面 CtxBuild.MODELS_SELF 的写入**同一个入参**（都是 model.trim()），
        // 这里不另算一份 —— 事实块里的 models.self 与 subagent.model 永远不可能对不上
        if (model != null && !model.trim().isEmpty()) subFacts.addProperty("model", model.trim());
        if (c != null) subFacts.addProperty("master", c.master());
        com.google.gson.JsonArray tl = new com.google.gson.JsonArray();
        for (String t : tools) tl.add(t);
        subFacts.add("tools", tl);
        sub.addSystem("subagent: " + J.json(subFacts));
        // 4) 变化部分：由主 Agent 在派发时生成（角色/边界/输出格式/验收标准）
        if (Str.has(brief)) sub.addSystem(brief.trim());
        sub.addUser(task);
        Loop.Outcome oc = run(sub, tools, model);
        if (oc.ok() && holder != null) holder.spoke = sub.spoke();
        if (!oc.ok()) return "子 Agent 失败：" + oc.error;
        return Str.has(oc.text) ? oc.text : "";
    }

    /**
     * <b>wake（回灌收尾）回合的工具白名单</b>：只给"说话"与"只读事实"两类。
     *
     * <p><b>为什么收窄</b>：收尾回合的使命只有三件 —— 说话（把结论讲给发起会话）、看事实、
     * 结束。它<b>不该</b>再派活、再定闹钟、再改库：那会变成"收尾里又派一个子 Agent / 又定一个闹钟"
     * 的雪崩（每个任务完成都再制造任务）。所以这里用<b>白名单</b>（不是黑名单）：名单外的一律不在工具表里，
     * 连"看得见"都做不到（{@code Loop.toolsFor} 按名字过滤，再叠一层 {@code T:X} 权限筛）。</p>
     *
     * <p>名单是常量，主人要加/减一行即可；不在名单里的名字不会报错，只是不出现。</p>
     */
    private static final List<String> WAKE_TOOLS = Collections.unmodifiableList(java.util.Arrays.asList(
            // 说话：消息 / 图片 / 表情包（与 Loop.speaks 的判定同一组）
            "send", "sendimage", "sticker",
            // 只读事实：读库 / 问模型（含查余额）/ 看当前上下文 / 看技能清单 / 看提示词
            "store", "model", "ctx", "skill", "prompt"));

    /** 收尾回合能用的工具名（诊断/探针用：白名单原文）。 */
    public static List<String> wakeTools() { return WAKE_TOOLS; }

    /**
     * 后台任务收尾：把结果回灌给主 Agent 一次，让她决定"要不要记 / 要不要改 / 要不要对人说话"。
     *
     * <p><b>这一轮是纯内部回合</b>：正文<b>不</b>外发（{@code markInternal} 拦住 {@code Turn.say}，
     * 基板也不替她投递任何正文 —— 它到不了任何 sink），也<b>不</b>写进对话历史。
     * 它是"Agent 之间的交流"：任务号、message_id、调度过程、子 Agent 的原文、回执口吻，
     * 说给用户听就是一封内部汇报。</p>
     *
     * <p><b>★ 但它能对外说话（B2，2026-09-15 真机事故）</b>：事故现场是她说了一句"要等查询"之后
     * 结论再也没出去（用户 93 秒后才从闹钟那条路收到答案）—— 根因就是这一轮当时被
     * {@code run(..., noSpeak=true)} 收走了<b>全部发送类工具</b>，于是"查到了"也说不出口。
     * 现在：<b>唯一对外出口 = 她自己显式调发送类工具</b>（走正常出站通路：{@code (guarded)Api.call}
     * ⇒ 出站台账 {@code sent}、闸门拒绝与发送失败照常上报）；提示词明确要求她
     * <b>只发到发起会话、只发一次、把人设化的话说给对方听</b>，不要报内部过程。</p>
     *
     * <p><b>不重复（F1）</b>：同一次派发里已经替她投递过（{@code alreadyDelivered}，
     * 例如 {@code reply=true} 的技能单：子 Agent 那句话由宿主发过了）⇒ 这一轮她也不再说话
     * （工具表里没有发送类工具），免得同一件事说两遍。</p>
     */
    public Loop.Outcome wake(Caller c, Sink sink, String note) {
        return wake(c, sink, note, false);
    }

    /**
     * @param alreadyDelivered 同一次派发里<b>已经</b>替她对外投递过一次 ⇒ 这一轮不再给她发送类工具
     *                         （"不得与定时投递重复"）
     */
    public Loop.Outcome wake(Caller c, Sink sink, String note, boolean alreadyDelivered) {
        Turn t = new Turn(c, sink);
        t.markInternal();                     // 内部回合：正文不外发、也不进聊天记录
        // ★ 能力③：收尾回合不是"会话里某个人叫她" —— 是她自己在时间里跑的那一轮 ⇒ self
        //   （取值表见 CtxBuild.ST_ADDRESSED；这一轮没有人发消息，事实行必须说清这一点）
        t.put(CtxBuild.ST_ADDRESSED, CtxBuild.AD_SELF);
        ctx.assembleSystem(t);
        String ledger = ledger(t.session(), 5);
        if (Str.has(ledger)) t.addSystem(ledger);
        t.addSystem(note);
        // 发起会话 = 谁问的、问到哪个会话。sink 就是发起那一跳的落点（网关的 QqSink / 定时那一跳按任务行
        // 解析出的 TaskSink / 本地控制台），所以"回哪里"不需要猜；而"可不可知"看的是发起者（没有调用者、
        // 或会话键为空 = 认不出来 ⇒ 这一轮不许对外说话）。
        final String origin = c == null ? "" : Str.trim(c.session());
        final boolean maySpeak = Str.has(origin);
        t.addUser("（后台任务已完成，这一轮是内部收尾：**你的正文不会发给用户，也不进聊天记录**——"
                + "它是我们之间的交流，别人看不到。这一轮的工具表只有「说话」与「只读查询」两类"
                + "（要记东西、改库、再派活、再定闹钟都不在这一轮做）。"
                + (alreadyDelivered
                    ? "★这一单**已经**由基板替你把话发过去了（reply=true 的技能单），所以这一轮**不要再重复说一遍**"
                      + "（说话类工具也不在表里）；"
                    : maySpeak
                        ? "★要把结论告诉对方，就**自己显式调发送类工具**（send 等）："
                          + "发起会话是 " + origin + "，**只发这里、只发一次**，不要发给别的人/别的群。"
                          + "发出去的那句话要说成人话（人设口吻、自然、不端着）——"
                        : "★这一轮**发起会话不可知**，所以不要对外说话（一个字的投递都没有），只做只读查询；")
                + "尤其不要写「已经查了」「得跟你说清」「已安排」「子 Agent 说…」这种汇报/回执腔，"
                + "也不要写任务号、message_id、子 Agent 的原文、调度过程、内部键值 —— "
                + "那些都要先翻译成「该让对方看到的那句话」再说。没有什么要说的就不用发。）");
        // 生命周期：回合开始 / 结束（基板⑦）。这一轮是内部收尾，"本轮正文"传空串（它对用户不是一句话）；
        // 她的显式发送由工具路径自己上报（onSend 只描述"把正文交给落点"那一刻，工具发送本来就不触发它）。
        hookStart(t, "");
        // 工具表 = 白名单 ∩ 该调用者可见（T:X）；发起会话不可知 ⇒ 连说话工具都不给（B2-4：不许猜一个会话发出去）。
        Loop.Outcome oc = run(t, WAKE_TOOLS, null, alreadyDelivered || !maySpeak);
        hookEnd(t, oc.ok() ? oc.text : "", oc.ok() ? "" : oc.error);
        if (out != null && oc.ok() && Str.has(oc.text)) {
            out.dim("[agent] 收尾（内部，未外发"
                    + (alreadyDelivered ? " · 这一单已经替她投递过，本轮不给说话工具" : "")
                    + "）：" + Str.cut(Str.oneLine(oc.text), 200));
        }
        return oc;
    }

    /**
     * 本回合是否<b>已经对外发过</b>（F1 去重判据；只看结构事实，不判内容）。
     *
     * <p><b>★ 批 15 / 2026-09-26（缺陷 A2：发失败 = 她哑了）—— 第一问改成台账。</b>
     * {@code sent} 台账<b>只记真发出去的消息</b>（{@code qq.Api.recordSent}：{@code ok(o)} 且
     * {@code message_id > 0} 才写一行），所以"本回合一行都没有"= 这一轮的发送类尝试
     * <b>要么被权限拦、要么平台失败</b>（真机 18:16:47 {@code upload_group_file status=failed retcode=1200}
     * → 18:17:10 那 96 字的解释被"已对外发过"吞掉，群里纯沉默）⇒ 她的回合正文<b>必须替他投出去</b>。
     * 判据用"不限会话、本回合之后"那条：{@code sentList("", since, 1)}（空会话 = 不限会话）。</p>
     *
     * <p>台账这一关过了再看两条老判据（口径一个字没改，只是排到台账之后）：</p>
     * <ol>
     *   <li>{@link Turn#spoke()}：本回合调用过发送类工具（{@code send}/{@code sendimage}/{@code sticker}）
     *       且没被权限拦 —— 这就是 {@code Loop.speaks} 那句"说过就不再拿回合正文补一句"的既有口径；</li>
     *   <li>{@code sent} 台账里本回合已有发往<b>这个会话</b>的记录 —— 覆盖"不是模型调发送工具"的出站
     *       （{@code napcat} 工具直发、技能/钩子自己发的），它天生是"对目标会话"的。</li>
     * </ol>
     * <p>会话键两种写法都认：{@code Caller.session()} 私聊是 {@code qq:<QQ>}，而 {@code Api} 记台账用的是
     * {@code user:<QQ>}（{@code Api.sessionOf}）—— 同一件事两个键，不认就会"明明发过却没认出来"。</p>
     * <p><b>台账读不动</b>时退回老口径 {@code t.spoke()}，分两种：①没装库（{@code store == null}）——
     * 既有行为一个字不改；②库异常（{@code catch}）—— 同样退 {@code t.spoke()}（不新增"吞正文"的路径）。
     * 注意真机上 SQL 读失败在 {@code Db.query} 里是<b>返回空表、不抛</b>，所以那种情况走的是上面
     * {@code isEmpty()} 那条 ⇒ 判<b>投正文</b>（台账坏掉只会多补一句，绝不会反过来让她哑掉）。</p>
     */
    private boolean alreadyOut(Turn t, String session) {
        if (t == null) return false;
        if (store == null) return t.spoke();
        long since = t.startedAt();
        try {
            // ★ 台账是"成功才有行"的：本回合一行都没落 ⇒ 没有一次真发出去（被拒 / 平台失败）⇒ 正文必须投。
            if (store.sentList("", since, 1).isEmpty()) return false;
            if (t.spoke()) return true;
            if (Str.has(session) && !store.sentList(session, since, 1).isEmpty()) return true;
            String alt = altSessionKey(session);
            return Str.has(alt) && !store.sentList(alt, since, 1).isEmpty();
        } catch (Throwable e) {
            if (out != null) out.warn("[agent] 出站台账查不动（本次退回「调过发送类工具」口径，不影响回复）：" + e);
            return t.spoke();
        }
    }

    /** 会话键的等价写法：私聊 {@code qq:<QQ>} ↔ {@code user:<QQ>}（群键没有第二写法，返回空串）。 */
    private static String altSessionKey(String session) {
        String s = Str.trim(session);
        if (s.regionMatches(true, 0, "qq:", 0, 3)) return "user:" + s.substring(3);
        if (s.regionMatches(true, 0, "user:", 0, 5)) return "qq:" + s.substring(5);
        return "";
    }

    // ==================== 插件生命周期钩子（基板⑦） ====================

    private void hookStart(Turn t, String text) {
        sair.v4.ext.ExtRegistry e = ext;
        if (e == null || !e.on()) return;
        try {
            e.turnStart(t == null ? null : t.caller(), t, text);
        } catch (Throwable x) {
            if (out != null) out.warn("[ext] onTurnStart 派发失败（不影响这一轮）：" + x);
        }
    }

    private void hookSend(Turn t, String text) {
        sair.v4.ext.ExtRegistry e = ext;
        if (e == null || !e.on()) return;
        try {
            e.send(t == null ? null : t.caller(), t, text);
        } catch (Throwable x) {
            if (out != null) out.warn("[ext] onSend 派发失败（不影响这一轮）：" + x);
        }
    }

    private void hookEnd(Turn t, String reply, String error) {
        sair.v4.ext.ExtRegistry e = ext;
        if (e == null || !e.on()) return;
        try {
            e.turnEnd(t == null ? null : t.caller(), t, reply, error);
        } catch (Throwable x) {
            if (out != null) out.warn("[ext] onTurnEnd 派发失败（不影响这一轮）：" + x);
        }
    }

    private void finishTask(long taskId, String result, long startedAt, boolean sync) {
        if (store == null || taskId <= 0) return;
        JsonObject patch = new JsonObject();
        patch.addProperty("status", "done");
        patch.addProperty("result", result == null ? "" : result);
        patch.addProperty("ms", Math.max(0, System.currentTimeMillis() - startedAt));
        store.updateTask(taskId, patch);
    }

    // ==================== 清单表 ====================

    public List<JsonObject> tasks(String status) { return tasks(status, null); }

    /**
     * 台账查询。{@code owner} 非空时只看该会话的任务（非主人只能看自己的）；
     * {@code status} 为空表示全部（"all" 不是合法状态值，调用方已归一）。
     */
    public List<JsonObject> tasks(String status, String owner) {
        if (store == null) return new ArrayList<JsonObject>();
        List<JsonObject> rows = store.listTasks(Str.blank(status) ? null : status, 50);
        if (!Str.has(owner)) return rows;
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (JsonObject r : rows) {
            if (owner.equals(J.s(r, "owner", ""))) out.add(r);
        }
        return out;
    }

    public int running() {
        int n = 0;
        for (JsonObject t : tasks("running")) n++;
        return n;
    }

    /** 台账文本（可按会话过滤）。 */
    public String ledger(String session, int limit) {
        List<JsonObject> rows = store == null ? new ArrayList<JsonObject>() : store.listTasks(null, Math.max(1, limit));
        if (rows.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("tasks:");
        for (JsonObject r : rows) {
            String owner = J.s(r, "owner", "");
            if (Str.has(session) && Str.has(owner) && !session.equals(owner)) continue;
            sb.append("\n  #").append(J.l(r, "id", 0))
              .append(" [").append(J.s(r, "status", "")).append("] ")
              .append(Str.cut(Str.oneLine(J.s(r, "task", "")), 80));
            String res = J.s(r, "result", "");
            if (Str.has(res)) sb.append(" → ").append(Str.cut(Str.oneLine(res), 80));
        }
        return sb.length() > 7 ? sb.toString() : "";
    }

    public JsonArray tasksJson(String status) { return tasksJson(status, null); }

    public JsonArray tasksJson(String status, String owner) {
        JsonArray arr = new JsonArray();
        for (JsonObject t : tasks(status, owner)) arr.add(t);
        return arr;
    }

    /**
     * 往返回给模型的那一行里补三条<b>工具白名单事实</b>（纯数据，没有劝导语）：
     * {@code tools_unknown}（注册表里压根没有这些名字）、{@code tools_denied}（有这个名字，但当前调用者看不到）、
     * {@code tools_available}（该调用者现在能用的全部工具名）。
     * 一个名字都没错时不写这两个"错误"键（不制造噪音）。
     */
    private void fillToolFacts(JsonObject row, Caller c, List<String> toolList) {
        if (row == null || toolList == null || toolList.isEmpty() || registry == null) return;
        List<String> vis = registry.visibleNames(c);
        List<String> unknown = new ArrayList<String>();
        List<String> denied = new ArrayList<String>();
        for (String t : toolList) {
            if (vis.contains(t)) continue;
            if (registry.get(t) != null) denied.add(t);
            else unknown.add(t);
        }
        if (!unknown.isEmpty()) row.addProperty("tools_unknown", Str.join(unknown, ","));
        if (!denied.isEmpty()) row.addProperty("tools_denied", Str.join(denied, ","));
        if (!unknown.isEmpty() || !denied.isEmpty()) row.addProperty("tools_available", Str.join(vis, ","));
    }

    /**
     * <b>子 Agent 的工具表：永远不含 {@code agent}</b>（D46★2 闸①：子 Agent 是最小单位，不许嵌套）。
     *
     * <p>两条路都在这里收口：</p>
     * <ol>
     *   <li>主 Agent <b>显式</b>把 {@code agent} 写进了 {@code tools}（哪怕它自己有权用）⇒ 剔除；</li>
     *   <li>{@code tools} 为空 = <b>继承主 Agent 的全部工具</b>（见 {@link #spawn} 的 javadoc：空表在
     *       {@code Loop.toolsFor} 里就是"不加白名单"）⇒ 这里只是**记账/提醒**：真正把 {@code agent}
     *       从继承出来的表里摘掉的是 {@code Loop.run} 的同一判据（`isSubagent()` 时不给 agent），
     *       本类<b>不</b>把继承那条路改写成显式全表（那会动到任务台账里的 {@code tools} 字段与子 Agent
     *       事实块，属于没人要求的行为变化）。</li>
     * </ol>
     * <p>真会剔到 {@code agent} 时打<b>一行</b>可读日志（两条路各最多一行；不会刷屏）。这不是
     * "提醒主 Agent 别派" —— 名字根本不在表里，模型连"看得见"都做不到；工具内的硬拒是第二道闸。</p>
     */
    private List<String> subTools(List<String> tools, Caller c) {
        List<String> l = normalize(tools);
        boolean had = l.remove("agent");
        if (!had && l.isEmpty()) had = inheritsAgent(c);
        if (had && out != null) {
            out.dim("[agent] 子 Agent 的工具表已剔除 agent（禁止嵌套：子 Agent 是最小单位，同级、由主 Agent 统一分配）");
        }
        return l;
    }

    /** 继承那条路（tools 为空）：这个调用者的可见工具面里有没有 {@code agent}（有就说明"本来会继承到"）。 */
    private boolean inheritsAgent(Caller c) {
        try {
            return registry != null && registry.visibleNames(c).contains("agent");
        } catch (Throwable e) {
            return false;
        }
    }

    private static List<String> normalize(List<String> tools) {        if (tools == null) return new ArrayList<String>();
        List<String> l = new ArrayList<String>(new LinkedHashSet<String>(tools));
        return l;
    }

    private long nextId() { return seq.incrementAndGet(); }

    /** 供诊断。 */
    public Map<String, Object> stat() {
        Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
        m.put("active", active.size());
        m.put("running_tasks", running());
        return m;
    }
}
