package sair.v4.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sair.v4.Conf;
import sair.v4.auth.Acl;
import sair.v4.auth.Auth;
import sair.v4.auth.Bits;
import sair.v4.auth.Caller;
import sair.v4.auth.Res;
import sair.v4.ctx.Turn;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 唯一工具注册表（基板）。
 * <p>V4 只有一套工具表：内置能力与技能能力都注册在这里。</p>
 *
 * <p><b>工具面按 T 类的 {@code X} 位筛</b>（主人裁定 2026-09-15："工具"从 C 类拆出来独立成 T 类，
 * 只有<b>入口</b>受权限管控：{@code R} 看工具 / {@code W} 改·注册 / {@code X} 执行）：
 * {@link #visible(Caller)} 只把 {@code auth.bits(c, Res.tool(名))} 含 {@code X} 的工具交出去。
 * 拿不到调用者（{@code null}，诊断路径）或权限面没装配（{@code auth == null}）时不筛，
 * 交全量（见 {@link #all()}）—— 这是"没有权限面就不假装有权限面"的老口径，一字未改。
 * <p><b>执行过程不判位</b>：能不能碰某个资源，仍由"要碰资源那一刻"的 ACL 判定说了算
 * （见 {@link #call}）。</p>
 */
public final class Registry implements sair.v4.skill.ToolView {

    private final Map<String, Tool> tools = new ConcurrentHashMap<String, Tool>();
    private final Auth auth;
    private final Conf conf;
    private final Out out;

    /** 能力索引 / 名字兜底 / 参数复核（配置外挂 data/prompts/tools-index.md）。 */
    private volatile ToolIndex index;

    /**
     * <b>最近一次被拒的原文</b>（B10；{@code tools op=show} 的"上次被拒"那一格读它）。
     *
     * <p>键 = {@code <调用者键>\u0000<工具名>} —— <b>只记"这个人自己"那次被拒</b>，
     * 别人的拒绝原文（可能带路径、带主体名）不跨人泄露。容量有上限：超过
     * {@link #DENY_MEM_MAX} 条就整表清空（这是"最近的证据"，不是审计台账 ——
     * 审计在 {@code [tool] … ok=0} 日志行里）。</p>
     *
     * <p>为什么不落库/不做全局表：这是**运行期一行证据**，为了回答"我刚为什么调不动"，
     * 重启后本来就没有意义；做成持久化反而多一个要维护的表。</p>
     */
    private final Map<String, String> lastDeny = new ConcurrentHashMap<String, String>();

    /** {@link #lastDeny} 的容量上限（清空式淘汰：见字段注释）。 */
    private static final int DENY_MEM_MAX = 256;

    /**
     * 记一次拒绝（{@link #call} 的两个拒绝出口都调它）。
     * <p>不抛异常、不影响判定结果：记不动就不记（这是证据面，不是判定面）。</p>
     */
    private void noteDeny(Caller c, String tool, String text) {
        if (c == null || Str.blank(tool) || Str.blank(text)) return;
        try {
            if (lastDeny.size() >= DENY_MEM_MAX) lastDeny.clear();
            lastDeny.put(callerKey(c) + "\u0000" + tool, Str.cut(Str.oneLine(text), 400));
        } catch (Throwable ignored) {
        }
    }

    /**
     * 这个调用者上一次调这把工具被拒的原文（没有就 {@code null} —— <b>调用方必须省略那一格，不许编</b>）。
     * <p>只有"同一个人 + 同一把工具"才拿得到：拒绝原文里可能有路径/主体名，不跨人给。</p>
     */
    public String lastDeny(Caller c, String tool) {
        if (c == null || Str.blank(tool)) return null;
        return lastDeny.get(callerKey(c) + "\u0000" + tool);
    }

    /** 调用者的缓存键（主体种类 + QQ + 群号；够区分"同一个人在不同群"）。 */
    private static String callerKey(Caller c) {
        return c.kind().name() + "|" + c.qq() + "|" + c.groupId();
    }

    public Registry(Auth auth, Conf conf, Out out) {
        this.auth = auth;
        this.conf = conf;
        this.out = out;
    }

    private ToolIndex index() {
        ToolIndex ix = index;
        if (ix == null) {
            ix = ToolIndex.of(conf);
            index = ix;
        }
        return ix;
    }

    /**
     * 注册一个工具。
     * <p><b>没有优先级</b>：不存在"基板优先 / 技能优先 / 手写优先 / 自生成次之"这种层级 ——
     * 同名就是撞车，由调用方（技能加载器）按确定性规则改名或拒绝，注册表本身不做让步。
     * 唯一的例外是<b>命名空间保护</b>：{@link #add} 不允许技能顶替基板的 15 个保留名
     * （那不是优先级，是防止删掉一个技能时把基板工具一起带走）。</p>
     */
    public void add(Tool t) {
        if (t == null || Str.blank(t.name())) return;
        // 没写示例的工具：从 prompts/tools-index.md 的「示例 <工具名>: …」补上 —— 内置工具没有自己的 md，
        // 这是它们唯一的示例来源；技能工具自己有 airun.examples，不会被覆盖。
        if (t.examples().isEmpty()) t = t.withExamples(index().examplesFor(t.name()));
        Tool exist = tools.get(t.name());
        if (exist != null && t.skill() && !exist.skill()) {
            if (out != null) out.err("[tool] 拒绝注册技能工具 " + t.name() + "（属 " + t.owner()
                    + "）：该名字是基板保留名，技能不能顶替基板工具");
            return;
        }
        tools.put(t.name(), t);
    }

    /** 便捷：直接收构造器（免去每处 .build()）。 */
    public void add(Tool.Builder b) {
        if (b != null) add(b.build());
    }

    public void removeByOwner(String owner) {
        if (owner == null) return;
        List<String> del = new ArrayList<String>();
        for (Tool t : tools.values()) if (owner.equals(t.owner())) del.add(t.name());
        for (String n : del) tools.remove(n);
    }

    public Tool get(String name) { return name == null ? null : tools.get(name); }

    /** 技能视图：拿到的是去掉实现的副本（避免绕过 {@link #call} 的权限复核）。 */
    @Override
    public Tool getView(String name) {
        Tool t = get(name);
        return t == null ? null : t.withoutHandler();
    }

    public int size() { return tools.size(); }

    /** 已注册的全部工具名（技能工具注册前用它避开撞车；顺序稳定）。 */
    public List<String> names() {
        List<String> l = new ArrayList<String>(tools.keySet());
        Collections.sort(l, String.CASE_INSENSITIVE_ORDER);
        return l;
    }

    /** 全部工具（不做权限过滤，仅内部/诊断用）。 */
    public List<Tool> all() {
        List<Tool> l = new ArrayList<Tool>(tools.values());
        sort(l);
        return l;
    }

    /**
     * 该调用者可见的工具 = <b>他在 T 类上拿到 {@code X} 的那些工具</b>（主人裁定 2026-09-15）。
     *
     * <p>"工具"从 C 类拆出来独立成 <b>T 类</b>：{@code R} 看工具（= 可见性）、{@code W} 改·注册工具、
     * {@code X} 执行。这里判的就是<b>入口</b>的那一位 ——
     * 逐把工具问 {@code auth.bits(c, Res.tool(名))}，含 {@code X} 才交出去；
     * 因而"看得到"和"执行得了"在入口处是同一件事（{@link #miss} 的候选也因此跟着筛）。</p>
     *
     * <p>默认位表下：MASTER 恒全权、SYSTEM（她自己的自主行为）{@code T=RWX}、{@code ALLUSER}
     * {@code T=NONE} —— 所以普通用户的回合看到的工具面是<b>空的</b>（要放开只能由主人写
     * {@code T["User<QQ>","","X"]} 这类例外条目）。</p>
     *
     * <p><b>不筛的两种情况</b>（与旧行为一字不差，别把它们当成"漏判"）：
     * ① {@code c == null}（没有调用者 —— 诊断 / 控制台的"全量"视图，{@link #describe} 走的就是它）；
     * ② {@code auth == null}（权限面没装配：装配失败时基板<b>不假装</b>自己有权限面，
     * 交全量而不是交空表，见 {@code Boot} 装配第⑥步的注释）。
     * 判定过程抛异常时<b>不交这把工具</b>（fail-closed）。</p>
     */
    public List<Tool> visible(Caller c) {
        List<Tool> l = new ArrayList<Tool>();
        for (Tool t : tools.values()) {
            if (mayEnter(c, t)) l.add(t);
        }
        sort(l);
        return l;
    }

    /**
     * 这把工具的"入口"给不给这个调用者（T 类的 {@code X} 位）。
     * <p>判据只有一条：{@code auth.bits(c, Res.tool(t.name()))} 含 {@code X}；
     * 拿不到调用者 / 拿不到权限面 → 放行（全量视图，见 {@link #visible}）；
     * 判定抛异常 → 不放行。</p>
     *
     * <p><b>例外：对外公开的工具</b>（{@link #PUBLIC}）谁都点得动 —— 见那个常量的说明。</p>
     */
    private boolean mayEnter(Caller c, Tool t) {
        if (t == null) return false;
        if (PUBLIC.contains(t.name())) return true;
        if (c == null || auth == null) return true;
        try {
            return Bits.has(auth.bits(c, Res.tool(t.name())), 'X');
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * <b>对外公开的工具</b>：入口不按 T 位筛，谁都能点（内部仍按归属 / 配额 / 资源位管）。
     *
     * <p>只有一把：{@code alarm}（到点的事）。理由：<b>别人委托的事必须由委托人自己记、自己查</b>
     * —— 普通用户的回合按 D43 是"工具面为空"，如果 {@code alarm} 也被筛掉，
     * 他既托不成事、也问不到"我托你的事办妥了没"。它的写口按 SYSTEM 判（她自己的账本），
     * 归属按委托人 QQ 硬判（只看得到自己的、别人的一个字都看不到），另有每人/全场配额。</p>
     */
    private static final java.util.Set<String> PUBLIC =
            java.util.Collections.unmodifiableSet(
                    new java.util.HashSet<String>(java.util.Arrays.asList("alarm")));

    /**
     * 入口闸门的拒文（{@code null} = 放行）—— 与 {@link #mayEnter} 同一个判据（T 类的 {@code X} 位），
     * 只是走 ACL 的正规出口，好让拒绝原文与别处<b>一字不差</b>（{@code 缺 X 位 —— [权限阻断] …}，
     * 识别面 {@link #isDenyText} 直接认）。不筛的两种情况见 {@link #call}。
     */
    private String mayEnterDeny(Caller c, String tool) {
        if (c == null || auth == null) return null;
        if (PUBLIC.contains(tool)) return null;
        try {
            return auth.allowRes(c, Res.tool(tool), 'X');
        } catch (Throwable ignored) {
            return Acl.DENY_PREFIX + "工具入口判定异常：" + tool;
        }
    }

    public List<String> visibleNames(Caller c) {
        List<String> l = new ArrayList<String>();
        for (Tool t : visible(c)) l.add(t.name());
        return l;
    }

    /** 供 DeepSeek 的 tools 参数。 */
    public JsonArray schemas(Caller c) {
        JsonArray arr = new JsonArray();
        for (Tool t : visible(c)) arr.add(t.schema());
        return arr;
    }

    /**
     * 真正调用：<b>先过入口闸门</b>（T 类的 {@code X} 位），过了才执行，再把结果规整成字符串。
     *
     * <p><b>两道门，缺一不可</b>：{@link #visible} 决定"<b>交不交到手</b>"（模型只拿到有位的工具名），
     * 本函数决定"<b>点不点得动</b>"（模型或技能即便喊出一个没位的工具名，也在这里被拒）。
     * 只做前者会被"喊一个不在清单里的工具名"整个绕过去。</p>
     *
     * <p><b>工具内部执行过程不判位</b>（主人裁定的口径："只管入口"）：真正的资源判定发生在
     * <b>工具碰到资源的那一刻</b> —— 文件读写在 {@code h.needPath}、库在 {@code h.needDb}、
     * 平台动作在 {@code h.needPlatform}。</p>
     *
     * <p>不筛的两种情况与 {@link #visible} 一致：{@code c == null}（内部 / 诊断，没有调用者）与
     * {@code auth == null}（权限面没装配 —— 装配失败时基板<b>不假装</b>自己有权限面）。
     * 判定过程抛异常 → <b>拒</b>（fail-closed）。</p>
     *
     * <p>旧的那层 op 键复核（{@code Auth.gateCall}：工具行 / op 行 / 好感度）已随 ACL 模型删除，
     * 旧档位体系整个在 P9 清理。</p>
     */
    public String call(String name, JsonObject args, Turn t) {
        Tool tool = tools.get(name);
        Caller c = t == null ? null : t.caller();
        if (tool == null) return miss(name, c);
        // ★入口闸门（T 类的 X 位）：可见性只是"交不交到手"，这里才是"点不点得动"。
        String entryDeny = mayEnterDeny(c, name);
        if (entryDeny != null) {
            noteDeny(c, name, entryDeny);
            return entryDeny;
        }
        if (tool.handler() == null) return "[tool] " + name + " 没有实现";
        long t0 = System.currentTimeMillis();
        try {
            Object r = tool.handler().call(args == null ? new JsonObject() : args, t);
            String text = toText(r);
            if (isDenyText(text)) noteDeny(c, name, text);     // 工具自己判位后的拒绝，也记一笔
            // 参数复核（只提示、不拦截）：枚举值越界 / 必填缺失时，把合法取值附在结果后面，
            // 让模型下一轮自己改对 —— 技能可能接受 md 未声明的别名，硬拦会把能跑的调用打死。
            String warn = index().argWarn(tool, args);
            if (warn != null && !text.contains(warn)) text = text + "\n" + warn;
            logTool(name, System.currentTimeMillis() - t0, text, !looksFailed(text), null, t);
            int max = conf == null ? 6000 : conf.toolResultMax();
            if (max > 0 && text.length() > max) {
                text = text.substring(0, max) + "\n…（结果已截断，共 " + text.length() + " 字符）";
            }
            return Str.blank(text) ? "(无输出)" : text;
        } catch (Throwable e) {
            logTool(name, System.currentTimeMillis() - t0, null, false, e, t);
            StringBuilder sb = new StringBuilder("[tool] " + name + " 执行异常: " + e);
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) sb.append("\n  at ").append(st[i]);
            return sb.toString();
        }
    }

    /**
     * 工具调用日志：<b>只打规模与成败，不打结果正文</b>（默认口径 = 只打"逻辑怎么走"）。
     * <p>判据：这一行要能回答"调了哪个工具、多大、成没成、多久"，而<b>不是</b>"结果是什么"。
     * 正文（结果前 120 字符）只在配置 {@code logVerbose=true} 时追加 —— 排障开关，不是默认行为。
     * 开关仍是 {@code logTools}（默认开；关掉这一行也不影响错误与权限阻断）。</p>
     */
    private void logTool(String name, long ms, String text, boolean ok, Throwable err, Turn turn) {
        if (out == null || conf == null || !conf.logTools()) return;
        // 控制台默认只留"Agent 调用事件"这一类行（tool/model）；要过程细节得在 logConsole 里加类别
        if (!conf.logOn("tool")) return;
        StringBuilder sb = new StringBuilder(80);
        sb.append("[tool] ").append(name).append(" ms=").append(ms)
          .append(" chars=").append(text == null ? 0 : text.length())
          .append(" ok=").append(ok ? 1 : 0);
        if (err != null) sb.append(" error=").append(err.getClass().getSimpleName());
        // "谁调的"：主 Agent 还是哪个子 Agent（主人要的就是"什么 Agent 调用了什么工具"）
        String by = callerLabel(turn);
        if (Str.has(by)) sb.append(" by=").append(by);
        if (text != null && text.length() > 0 && conf.logVerbose()) {
            sb.append(" → ").append(Str.cut(Str.oneLine(text), 120));
        }
        out.dim(sb.toString());
    }

    /** 当前这条工具调用是谁发起的（主 Agent = {@code main}，子 Agent = 它的标签；取不到 = 空串）。 */
    private static String callerLabel(Turn turn) {
        try {
            if (turn == null) return "main";
            String sub = Str.trim(turn.get("subagent", ""));
            return Str.blank(sub) ? "main" : sub;
        } catch (Throwable e) {
            return "";
        }
    }

    /**
     * 结果像不像"失败" —— 只看基板自己的失败/拒绝<b>前缀</b>（事实标记，不猜语气）：
     * 权限阻断（三种形态，见 {@link #isDenyText}）、执行异常（{@code [tool] x 执行异常}）、
     * 显式失败（{@code 执行失败}）。判不准时按成功算：这只是一行日志，
     * 要看正文排障请开 {@code logVerbose}。
     */
    static boolean looksFailed(String text) {
        if (text == null) return true;
        String t = text.trim();
        if (t.isEmpty()) return false;
        return isDenyText(t)
                || t.startsWith("[tool]") && t.contains("执行异常")
                || t.startsWith("执行失败")
                || t.startsWith("[v4]");
    }

    /**
     * 这条正文是不是"被权限拦下"（D12 ★4：<b>三种形态都要认</b>，否则 ACL 面的拒绝会被标成 {@code ok=1}）：
     * <ol>
     *   <li>{@link Acl#DENY_PREFIX} = {@code [权限阻断] } —— ACL 内核的产出（唯一的现代判据）；</li>
     *   <li>{@code [auth] 权限阻断} = 旧 {@link Auth#DENY_PREFIX} —— 只用于<b>兼容识别</b>
     *       （产出面已不再用它，但历史上写死这个字面量的技能/工具文案还在，认它才不会被误标成功）；</li>
     *   <li>{@code 缺 [RWX] 位 —— } —— {@code Host.need*} 的<b>信息性</b>位名前缀
     *       （产出形态：{@code 缺 W 位 —— [权限阻断] 需要 …}）。它自己不是拦下的证据，
     *       但保留识别面：断言一律用"<b>包含</b> {@code [权限阻断]} "而不是"以它开头"。</li>
     * </ol>
     */
    static boolean isDenyText(String t) {
        if (t == null) return false;
        String s = t.trim();
        return s.startsWith(Auth.DENY_PREFIX)        // 形态②：旧档位面（兼容）
                || s.contains(Acl.DENY_PREFIX)       // 形态①：[权限阻断]（可能在"缺 X 位 —— "之后）
                || s.startsWith("缺 R 位 —— ")        // 形态③：基板 need* 的信息性前缀
                || s.startsWith("缺 W 位 —— ")
                || s.startsWith("缺 X 位 —— ");
    }

    /**
     * 工具名没命中：在该调用者<b>可见</b>的工具里给候选 + 正确用法（V3 的普通工具路径只回
     * 「未知工具: x」不给候选，这是明确的缺口；只有 worker 路径会回可用清单）。
     * <p>候选与回退清单都走 {@link #visible}，因而<b>跟着 T 类的 {@code X} 位一起筛</b>
     * （没有位的人看不到任何工具名，候选自然也是空的）。
     * 认不出来的写法包括：大小写/分隔符差异、<b>直接喊中文技能名</b>（模型很容易这么干）、
     * 少一个字母的拼写错误。</p>
     */
    private String miss(String name, Caller c) {
        List<Tool> vis = visible(c);
        List<Tool> near = index().nearest(name, vis);
        StringBuilder sb = new StringBuilder("[tool] 没有名为 ").append(name).append(" 的工具。");
        if (near.isEmpty()) {
            List<String> names = new ArrayList<String>();
            for (Tool x : vis) names.add(x.name());
            return sb.append("当前可用：").append(Str.join(names, ", ")).toString();
        }
        sb.append("最接近的是：");
        for (Tool x : near) sb.append("\n- ").append(index().hint(x));
        if (out != null) {
            StringBuilder got = new StringBuilder();
            for (Tool x : near) got.append(got.length() == 0 ? "" : ",").append(x.name());
            out.dim("[tool] 名字未命中 " + name + " → 候选 " + got);
        }
        return sb.toString();
    }

    /**
     * 在该调用者<b>可见</b>的工具里找最接近的几个（{@code tools op=show} 的名字兜底用）。
     * <p>与"喊错工具名"走同一个机制（{@link ToolIndex#nearest} + {@link #visible}），
     * 所以候选也<b>跟着 T 类的 X 位一起筛</b> —— 没有位的人看不到任何候选。</p>
     */
    public List<Tool> nearestVisible(String name, Caller c) {
        return index().nearest(name, visible(c));
    }

    /** 一行能力索引（类别 → 工具[取值]）；配置缺失时返回 null（事实块里就没有这一行）。 */
    public String capabilityIndex(Caller c) {
        return index().line(visible(c));
    }

    /**
     * 一行能力索引，<b>按这一轮是不是子 Agent 决定带不带"一句话"</b>（B10）。
     *
     * @param withDesc {@code true} = 主 Agent（名字 + 一句话）；{@code false} = 子 Agent（只给名字，
     *                 省 token：它拿到的 schema 与主 Agent 是同一份，描述再抄一遍是纯浪费）
     */
    public String capabilityIndex(Caller c, boolean withDesc) {
        return index().line(visible(c), withDesc);
    }

    /** 结果规整：String 原样、其余 JSON 化、null 视为成功无输出。 */
    public static String toText(Object r) {
        if (r == null) return "";
        if (r instanceof String) return (String) r;
        if (r instanceof JsonObject || r instanceof JsonArray) return J.json(r);
        if (r instanceof Map || r instanceof List) return J.json(r);
        return String.valueOf(r);
    }

    /**
     * 控制台/诊断用清单：工具名 + <b>该调用者在这把工具上的有效位</b>（<b>T 类</b>资源
     * {@code Res.tool(名)} 的 {@code RWX} 掩码）+ 来源 + 一句话描述。
     *
     * <p>P9b-2：这一列原来是旧档位（{@code Auth.keyOf/effectiveLevel/describe} 算出的
     * {@code 仅主人} / {@code 好感度≥N}）。旧档位 facade 已随 {@code PermTable} 删除，改印 ACL 的
     * <b>有效位</b> —— 与"能不能碰看 {@code bits}"的现行口径同源。{@code c == null}
     * （{@code /tools all}：不按调用者筛，走 {@link #all()}）或权限面没装配时印 {@code "-"}。</p>
     */
    public String describe(Caller c) {
        StringBuilder sb = new StringBuilder();
        List<Tool> list = c == null ? all() : visible(c);
        for (Tool t : list) {
            sb.append("  ").append(t.name()).append("  [")
              .append(bitsText(c, t))
              .append(t.skill() ? " · 技能:" + t.owner() : " · 基板")
              .append("]  ").append(Str.cut(Str.oneLine(t.desc()), 60)).append("\n");
        }
        return sb.toString();
    }

    /** 一把工具对某个调用者的有效位（{@code RWX} / {@code NONE}）；问不出来时 {@code "-"}。 */
    private String bitsText(Caller c, Tool t) {
        if (auth == null || c == null || t == null) return "-";
        try {
            return Bits.format(auth.bits(c, Res.tool(t.name())));
        } catch (Throwable ignored) {
            return "-";
        }
    }

    /**
     * 工具表排序：<b>只按名字</b>（大小写不敏感，再按原名定序）。
     * <p>这里以前是"基板在前、技能在后"的两段式——那是"来源层级"的残留。现在不存在技能优先级
     * （见 {@link #add}），排序只服务于一件事：同一套工具表<b>每次都是同一个字节序</b>，
     * 这样服务端的前缀缓存才有得吃。</p>
     */
    private static void sort(List<Tool> l) {
        Collections.sort(l, new Comparator<Tool>() {
            @Override
            public int compare(Tool a, Tool b) {
                int c = a.name().compareToIgnoreCase(b.name());
                return c != 0 ? c : a.name().compareTo(b.name());
            }
        });
    }
}
