package sair.v4.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import sair.v4.Conf;
import sair.v4.ai.DeepSeek;
import sair.v4.ai.Msg;
import sair.v4.ai.Res;
import sair.v4.auth.Caller;
import sair.v4.ctx.RecognizerState;
import sair.v4.ctx.Turn;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.tool.Registry;
import sair.v4.tool.Tool;

/**
 * 主循环（基板④）：一次 Turn 跑到"模型不再要求调用工具"为止。
 * <p>工具表按调用者权限筛出来（可选再叠加一个白名单，供子 Agent 收窄能力）。
 * 上下文每一轮都重新读 system / context 注入，因此运行期注入随时生效。</p>
 *
 * <h3>连续失败闸门（事实化；V3 的 {@code isFailureResult} + 5/10 次兜底在 V4 的形态）</h3>
 * <p>同一个工具<b>连续</b>失败达到提示阈值时，下一轮往上下文里加一行<b>纯事实</b>
 * {@code tool_failures: {"<工具>":n,"last_error":"…"}}；达到终止阈值就停止本轮并回终止文案。
 * 判定只看<b>结构信号</b>（结果首行是不是 {@code [工具名]} / {@code [tool]} / {@code [auth]} 前缀 +
 * 自报结果里的结构标记 {@code ok=0|1}），没有关键词表、没有劝导语；
 * 阈值与终止文案都外挂在 {@code prompts/tools-index.md}，缺键只报错、不兜底。</p>
 */
public final class Loop {

    /** 一轮的结果。 */
    public static final class Outcome {
        public String text = "";
        public int rounds = 0;
        public int toolCalls = 0;
        public long ms = 0;
        public String error = "";

        public boolean ok() { return error.isEmpty(); }
    }

    private final Conf conf;
    private final Out out;
    private final DeepSeek ai;
    private final Registry registry;
    private final sair.v4.prompt.Prompts prompts;

    private volatile boolean stopped = false;
    /** 本次运行使用的模型（子 Agent 可指定，如视觉兜底用 flash）；null=按配置。 */
    private volatile String modelOverride;
    /** 本轮的会话键（按会话停止用）。 */
    private volatile String session = "";

    /** 工具名 → 连续失败次数（成功即清零）。 */
    private final java.util.Map<String, Integer> failStreak =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    /**
     * 剥掉"说话类"工具（{@code send} / {@code sendimage} / {@code sticker}）。
     * <p>给<b>收尾回合</b>用：那一轮只该记账（写记忆、改配置），不该再对外说一句 ——
     * 否则"正常回复 + 收尾又发一条"看起来就是重复回答。</p>
     */
    private volatile boolean noSpeak = false;

    /** 收尾回合等内部收口用：这一轮不给"能对用户说话"的工具。 */
    public void setNoSpeak(boolean b) { this.noSpeak = b; }

    /** 工具名 → 最后一次失败结果的截断（进事实行）。 */
    private final java.util.Map<String, String> failLast =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    /** 已经注入过的失败事实（值变了才重新注入，避免每轮重复塞同一行）。 */
    private volatile String injectedFailFact = null;

    /**
     * 这一轮用哪个模型（覆盖 {@code Conf.resolveModel()}）。
     *
     * <p>它同时也决定「这一轮是谁」（{@link sair.v4.ctx.CtxBuild#MODELS_SELF}）——
     * 真正落键的地方在 {@link #run}（那里才拿得到 {@code Turn}），与 {@code Agent.execSub}
     * 写的是同一个键、同一个值，所以是<b>幂等</b>的。
     * 两处都写是刻意的：{@code execSub} 那处保证"事实块装配之前"就已经写好（那是关键，
     * 见 {@code execSub} 里的注释），这里保证任何"先 setModel 再 run"的路径（探针、将来别处的调用）也不漏。
     * <b>但不能只有这一处</b>：{@code setModel} 通常发生在 {@code ctx.facts(sub)} 之后，那时事实块已经拼完了。
     */
    public void setModel(String m) { this.modelOverride = m; }

    public Loop(Conf conf, Out out, DeepSeek ai, Registry registry, sair.v4.prompt.Prompts prompts) {
        this.conf = conf;
        this.out = out;
        this.ai = ai;
        this.registry = registry;
        this.prompts = prompts;
    }

    public void stop() { stopped = true; }

    public void reset() { stopped = false; }

    public boolean stopped() { return stopped; }

    /** 本轮的会话键（{@code Agent.stop(session)} 按它判定"停哪个会话"）。 */
    public String session() { return session; }

    /**
     * <b>这一轮的轮次上限（唯一口径）</b>：主 Agent 用 {@code agentMaxRounds}，
     * 子 Agent 用 {@code subagentMaxRounds}（D46★1，两个键<b>分开计次</b>）。
     *
     * <p>判据只有一条：{@link Turn#isSubagent()}（{@code Turn} 上有没有基板自己写的
     * {@code subagent} 标记）—— 不靠技能/提示词自称。两个键都<b>每回合热读</b>（改完不用重启）。</p>
     *
     * <p>{@code public static} 是给"事实块那一行 {@code rounds:}（B10）"用的：
     * <b>数据源必须是同一处</b> —— {@link #run} 与 {@link #roundsFact} 都走这一个函数，
     * 所以"她看到的上限"与她"实际被掐停的那一轮"不可能对不上。</p>
     */
    public static int maxRounds(Conf conf, Turn t) {
        if (conf == null) return 1;
        boolean isSub = t != null && t.isSubagent();
        return Math.max(1, isSub ? conf.subagentMaxRounds() : conf.agentMaxRounds());
    }

    /**
     * 事实块里那一行 {@code rounds: {"round":n,"left":m,"max":M,"kind":"main|subagent"}}（B10）。
     *
     * <p>给她自己规划用：她在第几轮、还剩几轮、这一轮的上限是谁的（主 / 子）。
     * {@code kind} 取 {@link Turn#isSubagent()}；{@code max} 取 {@link #maxRounds}；
     * 这三个值全部是<b>算出来的</b>，没有一个另抄一份。</p>
     *
     * @param round 当前轮次（1 起；0 = 还没开跑）
     * @param max   本轮上限（见 {@link #maxRounds}）
     */
    public static String roundsFact(Conf conf, Turn t, int round, int max) {
        JsonObject o = new JsonObject();
        o.addProperty("round", Math.max(0, round));
        o.addProperty("left", Math.max(0, max - Math.max(0, round)));
        o.addProperty("max", max);
        o.addProperty("kind", t != null && t.isSubagent() ? "subagent" : "main");
        return "rounds: " + J.json(o);
    }

    /** 事实行前缀（{@link #roundsFact} 的产出形态；刷新时按它就地替换那一行）。 */
    public static final String ROUNDS_PREFIX = "rounds: ";

    /**
     * <b>每回合刷新事实块里那一行 {@code rounds:}（B10）</b>。
     *
     * <p>为什么不是"装配时写一次就完了"：事实块是 {@code CtxBuild.assemble} 在<b>本轮开跑之前</b>
     * 拼的，那时 {@code t.rounds()==0}；而"还剩几轮"是<b>跑起来才有的数</b>。
     * 所以这里每轮把那一行<b>就地换成最新值</b>（只改这一行，不追加消息、不动别的正文）——
     * 与 {@code injectFailFact} 同一类"纯事实"，但它是<b>刷新</b>而不是追加，避免 30 轮攒 30 行。</p>
     *
     * <p>找不到那一行（没有事实块的场合，例如探针只给一个裸 Turn）就<b>追加一条 system</b>：
     * 她要能规划，就得看得见这个数。</p>
     */
    private void refreshRoundsFact(Turn t, int round, int maxRounds) {
        if (t == null) return;
        String fact = roundsFact(conf, t, round, maxRounds);
        JsonArray msgs = t.messages();
        for (int i = 0; i < msgs.size(); i++) {
            JsonElement e = msgs.get(i);
            if (e == null || !e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            if (!"system".equals(J.s(m, "role", ""))) continue;
            String c = J.s(m, "content", "");
            int at = c.indexOf(ROUNDS_PREFIX);
            if (at < 0) continue;
            int end = c.indexOf('\n', at);
            if (end < 0) end = c.length();
            J.put(m, "content", c.substring(0, at) + fact + c.substring(end));
            return;
        }
        JsonObject m = new JsonObject();
        m.addProperty("role", "system");
        m.addProperty("content", fact);
        msgs.add(m);
    }

    /** 跑一轮（onlyTools 非空时收窄工具表，用于子 Agent）。 */
    public Outcome run(Turn t, List<String> onlyTools) {
        Outcome oc = new Outcome();
        long t0 = System.currentTimeMillis();
        this.session = t == null ? "" : t.session();
        // 注意：不要在这里重置 stopped —— Loop 每次 run 都是新实例，
        // 重置会把"刚被 stop() 掉"的信号抹掉（Agent.stop 可能落在 active.add 之后）。
        // 轮次上限按"这一轮是不是子 Agent"选键（D46★1，主人 2026-09-16 裁定：主/子分开计次）。
        // 判据只有一条：Turn 上有没有基板自己写的 `subagent` 标记（Agent.execSub 派活时 put）——
        // 不靠技能/提示词自称，也没有第二种判法。两个键都**每回合热读**（不缓存，改完不用重启）。
        // 上限的唯一算法在 maxRounds（事实块的 rounds: 行读的就是同一个函数）。
        boolean isSub = t != null && t.isSubagent();
        int maxRounds = maxRounds(conf, t);
        // 「这一轮是谁」的第二个写入点（P0-3 S1）：与 Agent.execSub 同一个键、同一个值 ⇒ 幂等。
        // 这里写的意义是"任何先 setModel 再 run 的路径也不漏"；关键的那一处仍必须留在 execSub
        // （事实块装配之前），否则子 Agent 会读到主模型的 main_has_vision。
        if (t != null && Str.has(modelOverride)) t.put(sair.v4.ctx.CtxBuild.MODELS_SELF, modelOverride.trim());
        // 本轮挂图的结果事实（P0-4 S2）：sent 的那一行要等第 1 次请求返回之后才追加（note 是过去式），
        // 由 CtxBuild.user 算好放在 Turn 状态上；none 的那一行 CtxBuild.user 已经自己补进 messages 了。
        String attachFact = t == null ? "" : Str.nz(t.get(sair.v4.ctx.CtxBuild.ST_ATTACH, ""));
        Caller caller = t.caller();
        // 子 Agent 的工具表永远不含 agent（D46★2 闸①）：
        //   · 显式名单那条路已在 Agent.subTools 里剔过（并打日志）；
        //   · 这里挡住"空名单 = 继承主 Agent 的全部工具"那条路 —— 空名单在 toolsFor 里就是"不加白名单"，
        //     不在这里收窄的话，主 Agent 能看见的 agent 会原样交到子 Agent 手里。
        // 收窄后的名字集来自 registry.visible(caller)，所以与"不改白名单"的可见面**逐条等价**，只少了 agent。
        List<String> only = onlyTools;
        if (isSub && (only == null || only.isEmpty())) {
            only = registry.visibleNames(caller);
            only.remove("agent");
        }
        JsonArray tools = toolsFor(caller, only);
        try {
            for (int round = 0; round < maxRounds; round++) {
                if (stopped || t.stopped()) {
                    oc.text = t.lastText();
                    break;
                }
                t.round();
                oc.rounds = round + 1;
                // 每回合刷新事实块那一行 rounds:（B10）—— 她在第几轮 / 还剩几轮 / 上限是谁的，
                // 值全部与上面的 maxRounds 同源，所以"她看到的"与"实际掐停她的"永远是同一个数。
                refreshRoundsFact(t, round + 1, maxRounds);
                JsonObject opts = new JsonObject();
                if (Str.has(modelOverride)) opts.addProperty("model", modelOverride);
                Res r = ai.stream(t.messages(), tools.size() > 0 ? tools : null, opts, new DeepSeek.Stream() {
                    @Override
                    public void on(String delta) {
                        if (delta == null || delta.isEmpty()) return;
                        if (t.sink() != null) t.sink().stream(delta);
                    }
                });
                if (!r.ok()) {
                    oc.error = r.error;
                    break;
                }
                // ★ 第 1 轮请求返回之后：把图摘掉 + 告诉她"图只在第 1 次请求里出现过"（P0-4 S2）。
                //   为什么必须摘：这个 for 循环每一轮都把**整个** t.messages() 发出去，
                //   不摘的话那张图会每轮重发一遍 —— 成本随轮数倍增，而行为上完全看不出来。
                //   为什么在这里才追加事实：note 是过去式（"图已在本轮第 1 次请求里看过"），
                //   第 1 次请求真的返回之后这句话才成立。
                //   只做 round==0：strip 本身幂等，事实也只该有一行。
                if (round == 0 && Str.has(attachFact)) {
                    sair.v4.qq.MediaAttach.strip(t.messages());
                    JsonObject fm = new JsonObject();
                    fm.addProperty("role", "system");
                    fm.addProperty("content", attachFact);
                    t.messages().add(fm);
                    if (out != null) out.dim("[agent] 注入事实 " + attachFact);
                }
                String text = Str.nz(r.content).trim();
                if (!text.isEmpty()) t.lastText(text);
                List<JsonObject> calls = toolCalls(r.toolCalls);
                if (calls.isEmpty()) {
                    // 收尾轮：正文留在 messages 里（这一轮之后不再发给模型），思维链按同一口径附带
                    if (!text.isEmpty()) {
                        t.messages().add(Msg.withReasoning(Msg.assistant(text), r.reasoning));
                    }
                    oc.text = text;
                    break;
                }
                // 助手消息（含 tool_calls）必须进上下文，否则工具结果没有归属。
                // **一条响应 = 一条 assistant 消息**：正文、tool_calls 与这一轮的 reasoning_content 同条回传。
                // 思考模式下只要请求带 tools（工具闭环的每一轮都带），历史里每条 assistant 消息的思维链都必须
                // 完整回传，漏一条就是 HTTP 400 —— 真机上「用了工具的一轮，第二轮必 400」的根因（官方口径见
                // guides/thinking_mode 的「工具调用」：即使该轮未实际调用工具也要回传）。
                t.messages().add(Msg.assistantToolCalls(text, r.toolCalls, r.reasoning));
                String stop = null;
                for (JsonObject call : calls) {
                    if (stopped) break;
                    oc.toolCalls++;
                    String id = J.s(call, "id", "");
                    String name = J.s(J.sub(call, "function"), "name", "");
                    String argText = J.s(J.sub(call, "function"), "arguments", "");
                    JsonObject args = J.obj(argText);
                    if (args == null) args = new JsonObject();
                    if (out != null) out.dim("[agent] 调用工具 " + name + " " + Str.cut(Str.oneLine(argText), 200));
                    // 识图组件计数（T12-R3）：**放在 registry.call 之前**，与上面那行"调用工具"日志同一时点。
                    // **口径（鱼总裁定，定格）：recognizer_uses = 该工具经工具面被调用的次数（含被拒 / 失败）。**
                    // 记的是"她经工具面伸手去用了这个工具"这个动作本身 —— 放在 call 之后的话，
                    // 调用抛异常 / 被中途掐停就漏计。失败与被拒的调用**也计一笔**：判它成没成要读结果正文
                    // （那是耦合），这里不读；要"仅成功"得改 Registry.call 的返回结构（可选精化、非必需，本轮不做）。
                    // **已知边界（是定义、不是漏计）**：技能内部 h.call("look",…) 不走这个入口 ⇒ 不计入。
                    // 只有 RecognizerState.TOOL 会 +1，别的工具名一律不计；不注入时它恒为 0。
                    RecognizerState.shared().bump(name);
                    String result = registry.call(name, args, t);
                    t.addTool(id, result);
                    if (speaks(name) && !deniedResult(result)) t.markSpoke();
                    note(name, result);
                    stop = failStop(name);
                    if (stop != null) break;
                }
                if (stop != null) {
                    // 到终止阈值：停止本轮并回终止文案（文案来自 md，缺键时已经报错）
                    oc.error = stop;
                    oc.text = t.lastText();
                    if (out != null) out.warn("[agent] " + stop);
                    break;
                }
                injectFailFact(t);
                if (round == maxRounds - 1) {
                    oc.text = t.lastText();
                    oc.error = "";
                    // 日志写清归属：这一轮的上限是主 Agent 的还是子 Agent 的（两个键分开计次）
                    if (out != null) out.warn("[agent] 达到最大轮数 " + maxRounds + (isSub ? "（子 Agent）" : "（主）"));
                }
            }
        } catch (Throwable e) {
            oc.error = "主循环异常: " + e;
        }
        oc.ms = System.currentTimeMillis() - t0;
        oc.text = oc.text == null ? "" : oc.text;
        return oc;
    }

    /**
     * 这个工具是不是"对外说话"的那一类（消息发送 / 发图 / 表情包）。
     *
     * <p>用途只有一个：回合结束时判断"这轮已经真的对用户说过话了没有"——
     * 说过就不再拿回合正文补一句，免得用户收到两遍。</p>
     */
    public static boolean speaks(String tool) {
        if (tool == null) return false;
        String t = tool.trim().toLowerCase();
        return "send".equals(t) || "sendimage".equals(t) || "sticker".equals(t);
    }

    /**
     * 工具结果是不是"被权限拦下"（拦下不算说过话）。
     *
     * <p><b>D12 ★4：三种形态都要认</b> —— ACL 内核产出的 {@code [权限阻断] }（唯一现代判据）、
     * 旧档位面写死的 {@code [auth] 权限阻断}（只作兼容识别），以及基板 {@code Host.need*} 的信息性
     * 前缀 {@code 缺 [RWX] 位 —— }（它的产出形态是 {@code 缺 W 位 —— [权限阻断] 需要 …}）。
     * 只认旧串时，ACL 面的拒绝会被当成"说过话了"。</p>
     */
    private static boolean deniedResult(String result) {
        if (result == null) return false;
        String s = result.trim();
        return s.startsWith(sair.v4.auth.Auth.DENY_PREFIX)
                || s.contains(sair.v4.auth.Acl.DENY_PREFIX)
                || s.startsWith("缺 R 位 —— ")
                || s.startsWith("缺 W 位 —— ")
                || s.startsWith("缺 X 位 —— ");
    }

    /** 工具表按权限筛 + 可选白名单。 */
    public JsonArray toolsFor(Caller c, List<String> onlyTools) {        JsonArray arr = new JsonArray();        for (Tool tool : registry.visible(c)) {
            if (onlyTools != null && !onlyTools.isEmpty() && !onlyTools.contains(tool.name())) continue;
            if (noSpeak && speaks(tool.name())) continue;      // 收尾回合：只记账，不发言
            arr.add(tool.schema());
        }
        return arr;
    }

    // ==================== 连续失败闸门（事实化） ====================

    /**
     * 结果算不算失败：只看<b>结构前缀</b>与<b>结构标记</b>，不看内容关键词 ——
     * 首行形如 {@code [<工具名>]…}（工具自报结果）、{@code [tool]…}（未命中/无实现/执行异常）、
     * {@code [auth]…}（旧的档位面权限阻断）、以及基板 <b>ACL 面</b>产出的三种拒绝形态
     * （见 {@link #deniedResult}：{@code [权限阻断] } / {@code [auth] 权限阻断} / {@code 缺 [RWX] 位 —— }）。
     * 这些都不需要任何硬编码关键词表。
     *
     * <p><b>为什么权限阻断必须算失败</b>（P9b-1 补的第四处识别面）：连续失败闸门靠它计数，
     * 只认旧前缀时 ACL 面的拒绝一次都不计数 ⇒ {@code failStop} 永不触发（她可以无限撞权限墙）。
     * 判据仍只看<b>首行</b>（截断 60 字）：拒绝文案的标记一定在首位
     * （{@code [权限阻断] } 或 {@code 缺 X 位 —— }），所以首行足够；也避免"正文里引用了一句拒文"
     * 被误算成这次调用失败了。</p>
     *
     * <p><b>自报结果里的 {@code ok=0|1} 是结构标记，不是关键词表</b>（B8，2026-09-16 真机事故）：
     * 前缀本身不足以判成败 —— <b>有的工具族的"成败"都带同一个 {@code [工具名]} 前缀</b>
     * （基板自己的 {@code console} 一族：{@code run} / {@code read} / {@code visible} / {@code history} /
     * {@code size} / {@code clear} 成功回 {@code [console] run ok=1 …}、失败回 {@code [console] run ok=0 …}；
     * 技能面的 {@code Host.sfwRun} 同一形态）。只认前缀就会把<b>每一次成功都算成失败</b>：
     * 真机上 {@code [tool] console ms=… ok=1 by=main}（工具自报成功）与
     * {@code tool_failures:{"console":5,…}} 同时出现，第 5 次把她的整轮掐停。
     * 所以前缀命中之后再看这一格的<b>结构性取值</b>：{@code ok=1} ⇒ 成功；{@code ok=0} ⇒ 失败；
     * <b>两个都没有 ⇒ 仍按老规矩算失败</b>（"首行带自己的名字前缀 = 自报失败"，V4 技能 md 全线遵循）。
     * 判据里没有工具名豁免表：新工具只要用同一套 {@code ok=} 协议就自动正确，
     * 换个工具不会再复发。</p>
     */
    static boolean failed(String tool, String result) {
        String s = Str.nz(result).trim();
        int nl = s.indexOf('\n');
        String head = nl > 0 ? s.substring(0, nl) : s;
        if (head.length() > 60) head = head.substring(0, 60);
        if (head.startsWith("[tool]") || head.startsWith("[auth]")) return true;
        if (deniedResult(head)) return true;                  // ACL 面：三种形态（含 缺 X 位 —— [权限阻断] …）
        if (!Str.has(tool) || !head.startsWith("[" + tool + "]")) return false;
        return okMark(head) != 1;          // 自报前缀命中：ok=1 才是成功；ok=0 与"没有标记"都算失败
    }

    /**
     * 首行里 {@code ok=} 这一格的<b>结构取值</b>：{@code 1} / {@code 0} / {@code -1}（没写/写坏）。
     * <p>只认"取值恰是 0 或 1"的形态（{@code ok=1} 后面不能再跟数字，
     * 所以 {@code ok=10} 这种不算命中），其它一概当作"没有标记"——
     * 这不是内容关键词匹配，而是读一个协议字段。</p>
     */
    private static int okMark(String head) {
        int i = head.indexOf("ok=");
        while (i >= 0) {
            int v = i + 3;
            if (v < head.length()) {
                char c = head.charAt(v);
                if (c == '0' || c == '1') {
                    int n = v + 1;
                    if (n >= head.length() || !Character.isDigit(head.charAt(n))) return c - '0';
                }
            }
            i = head.indexOf("ok=", i + 1);
        }
        return -1;
    }

    /** 记一次调用的成败（成功清零该工具的连续计数）。 */
    private void note(String tool, String result) {
        if (Str.blank(tool)) return;
        if (failed(tool, result)) {
            Integer n = failStreak.get(tool);
            failStreak.put(tool, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
            failLast.put(tool, Str.cut(Str.oneLine(result), 120));
        } else {
            failStreak.remove(tool);
            failLast.remove(tool);
        }
    }

    /** 到终止阈值就返回终止文案（来自 md；缺键已报错 → 返回 null 不终止）。 */
    private String failStop(String tool) {
        sair.v4.tool.ToolIndex ix = indexPath();
        if (ix == null) return null;
        int stopAt = ix.failStopAt();
        Integer n = failStreak.get(tool);
        if (stopAt <= 0 || n == null || n.intValue() < stopAt) return null;
        String txt = ix.failStopText(tool, n.intValue());
        if (txt == null) {
            if (out != null) out.err("[agent] 连续失败已达终止阈值，但 prompts/tools-index.md 缺「失败终止文案」，未终止");
            return null;
        }
        return txt;
    }

    /**
     * 达到提示阈值就往上下文里加一行事实 {@code tool_failures: {...}}（纯 JSON，无劝导语）。
     * 同一份事实只注入一次；计数变了会再注入一份新的。
     */
    private void injectFailFact(Turn t) {
        sair.v4.tool.ToolIndex ix = indexPath();
        if (ix == null || t == null) return;
        int warnAt = ix.failWarnAt();
        if (warnAt <= 0) return;
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> e : failStreak.entrySet()) {
            if (e.getValue() == null || e.getValue().intValue() < warnAt) continue;
            if (sb.length() > 0) sb.append(",");
            sb.append(J.json(e.getKey())).append(":").append(e.getValue().intValue());
        }
        if (sb.length() == 0) return;
        String last = "";
        int most = 0;
        for (java.util.Map.Entry<String, Integer> e : failStreak.entrySet()) {
            if (e.getValue() != null && e.getValue().intValue() >= most) {
                most = e.getValue().intValue();
                last = Str.nz(failLast.get(e.getKey()));
            }
        }
        String fact = "tool_failures: {" + sb + ",\"last_error\":" + J.json(last) + "}";
        if (fact.equals(injectedFailFact)) return;
        injectedFailFact = fact;
        JsonObject m = new JsonObject();
        m.addProperty("role", "system");
        m.addProperty("content", fact);
        t.messages().add(m);
        if (out != null) out.dim("[agent] 注入事实 " + fact);
    }

    private sair.v4.tool.ToolIndex indexPath() {
        sair.v4.tool.ToolIndex ix = index;
        if (ix == null) {
            ix = sair.v4.tool.ToolIndex.of(conf);
            index = ix;
            if (ix != null) {
                String miss = ix.missingFailKeys();
                if (Str.has(miss) && out != null) {
                    out.err("[agent] prompts/tools-index.md 缺键：" + miss + "（连续失败闸门不生效）");
                }
            }
        }
        return ix;
    }

    private volatile sair.v4.tool.ToolIndex index;

    private static List<JsonObject> toolCalls(JsonArray raw) {
        List<JsonObject> list = new ArrayList<JsonObject>();
        if (raw == null) return list;
        for (JsonElement e : raw) {
            if (e != null && e.isJsonObject()) list.add(e.getAsJsonObject());
        }
        return list;
    }
}
