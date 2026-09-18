package sair.v4.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.Conf;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 工具索引与「调错名字 / 填错参数」的兜底（基板，模型侧唯一一份）。
 *
 * <h3>它解决的三类命中问题</h3>
 * <ol>
 *   <li><b>工具太多挑不动</b>：{@link #line} 产出一行能力索引
 *       {@code tools: {"消息":["send 往外发东西的唯一出口…","sendimage 发图…"], …}} —— 纯事实
 *       （类别 + 工具名 + <b>一句话</b>），op 取值明细在各自的 schema 里，索引不重复；类别词表来自
 *       {@code prompts/tools-index.md}，工具名与描述取自<b>活着的注册表</b>。
 *       <b>子 Agent 只给名字</b>（{@link #line(List, boolean)} 的 {@code withDesc=false}），省 token。
 *       V3 的先例是 {@code SkillBank.buildStableIndex}（每个技能一行、单行截 120 字符、预算 maxTokens×4
 *       字符、超预算只跳过该行不放弃后面）；V4 因为已经把所有工具的完整 schema 交给模型，
 *       索引只需要给"分组 + 一句话"这一层增量，所以收成一行。</li>
 *   <li><b>工具名写错</b>：{@link #nearest} 在<b>该调用者可见的工具</b>里找候选
 *       （归一化同名 / 技能名 / 包含 / 编辑距离），{@link #hint} 给出正确用法。
 *       V3 的普通工具路径只回「未知工具: x」不给候选（只有 worker 路径回 {available}），这是明确的缺口。</li>
 *   <li><b>枚举参数填错</b>：{@link #argWarn} 在参数越界或必填缺失时给一行合法取值 ——
 *       与 V3 的 {@code SkillCodeRunner.normalizeArgs}（按声明类型强转 + 补 default）互补：
 *       那边是"尽量救活调用"，这边是"救不活时说清合法值"。</li>
 * </ol>
 *
 * <h3>零硬编码文案</h3>
 * <p>类别词表、兜底类别名、候选数、距离上限、总预算，全部外挂在
 * {@code data/prompts/tools-index.md}（格式见该文件）。文件缺失/解析不出类别 → {@link #line} 返回 null，
 * 事实块里就<b>没有</b>这一行，基板不编任何兜底文案。</p>
 */
public final class ToolIndex {

    /** 外挂配置文件名（相对 prompts/）。 */
    public static final String FILE = "tools-index.md";

    /** 索引行的出厂字符预算（与 {@code tools-index.md} 的「总预算」同一个值，见 {@link #budget}）。 */
    public static final int DEF_BUDGET = 12000;

    /** 归一化用具：工具名/技能名里的分隔符与空白一律忽略。 */
    private static final String SEP = "[_\\-\\s\\.]+";

    private final File file;

    private volatile long mtime = -1;
    private volatile boolean loaded = false;
    private volatile int candMax = 3;
    private volatile int distMax = 2;
    /**
     * 索引行的字符预算（B10 起 4000 → {@value #DEF_BUDGET}）。
     * <p>条目从"只有工具名"变成"名字 + 一句话"之后，4000 装不下全部类别（会整类跳过）。
     * <b>唯一生效来源仍是 {@code prompts/tools-index.md} 的「总预算」</b>（配置项）；
     * 这里的值只是"md 缺这个键时"的兜底，与文件里的出厂值保持一致。</p>
     */
    private volatile int budget = DEF_BUDGET;
    /**
     * 索引条目里"一句话"的字符上限（B10；{@code prompts/tools-index.md} 的「条目描述上限」）。
     * <p>{@code <=0} = 不截（只压成单行）。默认 40：够说清"这把工具是干什么的"，
     * 又不会让 38 把工具的那一行挤爆预算。</p>
     */
    private volatile int entryDescMax = 40;
    private volatile String fallbackCat = "";
    private volatile String examplePrefix = "例：";
    private volatile int failWarnAt = -1;
    private volatile int failStopAt = -1;
    private volatile String failStopTpl = "";
    /** 调度面文案（{@code sair.v4.schedule.Texts} 用）：回合队列溢出 / 排队中。 */
    private volatile String queueOverflowTpl = "";
    private volatile String queueQueuedTpl = "";
    /** 加载期间 {@code chat} 的那句话（基板还在装配时回给主人的一句）；缺键 = 不编话。 */
    private volatile String initializingTpl = "";
    /** 生命周期面文案：需重启提示 / 已在运行 / 装配中忽略 / 受理启动 / 受理重启。 */
    private volatile String restartNeedTpl = "";
    private volatile String alreadyRunningTpl = "";
    private volatile String busyTpl = "";
    private volatile String startAcceptedTpl = "";
    private volatile String restartAcceptedTpl = "";
    /** 本地交流面板的选项卡标题（缺键 = 空串 → 面板不设名字，由框架叫「面板N」）。 */
    private volatile String panelTitle = "";
    /** "只 @ 一下、没带任何话"那条消息给模型的输入（缺键 = 空串 → 这一条不起回合）。 */
    private volatile String bareMentionTpl = "";
    /**
     * 「失败回话」：这一轮没跑成（模型连接超时、接口报错…）时，对外说的那句兜底话。
     * <p>缺键返回 null，调用方用内置的人设兜底话顶上（<b>不把内部错误发出去</b>）。</p>
     */
    private volatile String failNoticeTpl = "";
    /**
     * 偏好块的三行文案（{@code ctx.CtxBuild} 每轮注入"稳定段"用）。
     * <p><b>与其余文案键的口径不同</b>：这三个键缺了<b>不能</b>"那一路就不发"——那会让
     * "偏好进上下文"整条功能静默失效（V4 之前的缺口正是没人读 {@code pref} 库）。
     * 所以缺键由调用方用内置默认值兜底，而且只 warn 一次（见 {@link #missingPrefKeys()}）。</p>
     */
    private volatile String prefTitleTpl = "";
    private volatile String prefRowTpl = "";
    private volatile String prefCutTpl = "";
    private volatile Map<String, List<String>> examples = new LinkedHashMap<String, List<String>>();
    private volatile Map<String, List<String>> cats = new LinkedHashMap<String, List<String>>();

    private ToolIndex(File file) {
        this.file = file;
    }

    public static ToolIndex of(Conf conf) {
        if (conf == null) return null;
        return new ToolIndex(new File(conf.promptsDir(), FILE));
    }

    // ------------------------------------------------------------------ 配置解析

    /** mtime 变化时重新解析（与 {@code PromptFile} 同一套口径：文件不存在 = 没有这份配置）。 */
    private void load() {
        if (file == null || !file.isFile()) {
            cats = new LinkedHashMap<String, List<String>>();
            loaded = true;
            mtime = -1;
            return;
        }
        long m = file.lastModified();
        if (loaded && m == mtime) return;
        String txt = Fs.read(file);
        Map<String, List<String>> parsed = new LinkedHashMap<String, List<String>>();
        Map<String, List<String>> exMap = new LinkedHashMap<String, List<String>>();
        int cand = 3, dist = 2, bud = DEF_BUDGET, descMax = 40;
        String fb = "", pex = "例：", stopTpl = "";
        String exKey = "示例";
        String ovTpl = "", queuedTpl = "";
        String initTpl = "";
        String needTpl = "", runningTpl = "", busyTpl2 = "", startTpl = "", restartTpl = "";
        int warnAt = -1, stopAt = -1;
        String panelTpl = "";
        String bareTpl = "";
        String prefTitle = "", prefRow = "", prefCut = "";
        if (txt != null) {
            for (String raw : txt.split("\n", -1)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("<!--")) continue;
                int c = line.indexOf(':');
                if (c <= 0) continue;
                String key = line.substring(0, c).trim();
                String val = line.substring(c + 1).trim();
                if ("候选数".equals(key)) { cand = intOf(val, cand); continue; }
                if ("距离上限".equals(key)) { dist = intOf(val, dist); continue; }
                if ("总预算".equals(key)) { bud = intOf(val, bud); continue; }
                if ("条目描述上限".equals(key)) { descMax = intOf(val, descMax); continue; }
                if ("默认类别".equals(key)) { fb = val; continue; }
                if ("示例前缀".equals(key)) { pex = val; continue; }
                if ("示例键".equals(key)) { exKey = val; continue; }
                if ("失败提示阈值".equals(key)) { warnAt = intOf(val, -1); continue; }
                if ("失败终止阈值".equals(key)) { stopAt = intOf(val, -1); continue; }
                if ("失败终止文案".equals(key)) { stopTpl = val; continue; }
                // 调度面文案（回合队列溢出 / 排队中）：与失败闸门同一口径 —— 缺键报错，不兜底
                if ("队列溢出文案".equals(key)) { ovTpl = val; continue; }
                if ("排队中告知文案".equals(key)) { queuedTpl = val; continue; }
                // 加载期间 chat 的那句话：同一口径 —— 缺键报错，不兜底
                if ("初始化中文案".equals(key)) { initTpl = val; continue; }
                // 生命周期面（start / restart / config set 的提示）：同一口径 —— 缺键报错，不兜底
                if ("需要重启提示".equals(key)) { needTpl = val; continue; }
                if ("已在运行回话".equals(key)) { runningTpl = val; continue; }
                if ("装配中回话".equals(key)) { busyTpl2 = val; continue; }
                if ("启动已受理回话".equals(key)) { startTpl = val; continue; }
                if ("重启已受理回话".equals(key)) { restartTpl = val; continue; }
                // 【已退休】「快照落后阈值」/「快照落后告警间隔秒」：游标快照随主人 2026-09-16 的
                // 「取消自动快照」整条删除，这两行 md 键不再有任何消费者 —— 但仍然在这里**认出来并丢掉**：
                // 不认它就会落进下面的 categories 表，把「快照落后阈值:[200]」当成一个工具类别
                // 混进每轮的工具索引里（那是真的会污染上下文）。md 文件本身不归本仓管，故不能靠删行解决。
                if ("快照落后阈值".equals(key) || "快照落后告警间隔秒".equals(key)) continue;
                // 本地交流面板的选项卡标题（UI 文案，同样外挂 —— Java 里不编名字）
                if ("面板标题".equals(key)) { panelTpl = val; continue; }
                // "只 @ 一下、没带任何话"那条消息给模型的输入（缺键 → 这一条不起回合）
                if ("被单@时的输入".equals(key)) { bareTpl = val; continue; }
                if ("失败回话".equals(key)) { failNoticeTpl = val; continue; }
                // 偏好块的三行文案（缺键 → 调用方用内置默认值 + 只 warn 一次，绝不因此不注入）
                if ("偏好标题".equals(key)) { prefTitle = val; continue; }
                if ("偏好行".equals(key)) { prefRow = val; continue; }
                if ("偏好截断".equals(key)) { prefCut = val; continue; }
                // 「示例 <工具名>: a, b, c」：给**内置**工具补用法示例（技能自己的示例写在技能 md 里）。
                if (key.startsWith(exKey + " ") || key.startsWith(exKey + "　")) {
                    String tool = key.substring(exKey.length()).trim();
                    if (!tool.isEmpty()) exMap.put(tool, split(val));
                    continue;
                }
                parsed.put(key, split(val));
            }
        }
        examples = exMap;
        candMax = Math.max(1, Math.min(10, cand));
        distMax = Math.max(0, Math.min(6, dist));
        budget = Math.max(200, bud);
        entryDescMax = descMax;
        fallbackCat = fb;
        examplePrefix = pex;
        failWarnAt = warnAt;
        failStopAt = stopAt;
        failStopTpl = stopTpl;
        queueOverflowTpl = ovTpl;
        queueQueuedTpl = queuedTpl;
        initializingTpl = initTpl;
        restartNeedTpl = needTpl;
        alreadyRunningTpl = runningTpl;
        busyTpl = busyTpl2;
        startAcceptedTpl = startTpl;
        restartAcceptedTpl = restartTpl;
        panelTitle = panelTpl;
        bareMentionTpl = bareTpl;
        prefTitleTpl = prefTitle;
        prefRowTpl = prefRow;
        prefCutTpl = prefCut;
        cats = parsed;
        mtime = m;
        loaded = true;
    }

    private static int intOf(String s, int def) {
        try {
            return Integer.parseInt(Str.trim(s));
        } catch (Exception e) {
            return def;
        }
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(s)) return out;
        for (String p : s.split("[,，]")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ------------------------------------------------------------------ ① 能力索引

    /**
     * 一行能力索引（含 {@code tools: } 前缀）；没有可用类别配置时返回 null。
     * <p>带"一句话"（{@link #entry(Tool, boolean)} 的 {@code withDesc=true}）：主 Agent 拿全量。</p>
     */
    public String line(List<Tool> visible) {
        return line(visible, true);
    }

    /**
     * 一行能力索引（含 {@code tools: } 前缀）；没有可用类别配置时返回 null。
     *
     * @param withDesc {@code true} = 条目是"名字 + 一句话"（主 Agent）；{@code false} = 只给名字
     *                 （<b>子 Agent</b>：它拿到的是同一份 schema，索引再带一遍描述等于重复付费）。
     *                 判据由调用方给：{@code Turn.isSubagent()}（见 {@code Loop}/{@code CtxBuild}）。
     */
    public String line(List<Tool> visible, boolean withDesc) {
        load();
        if (cats.isEmpty() || visible == null || visible.isEmpty()) return null;
        Map<String, Tool> byName = new LinkedHashMap<String, Tool>();
        Map<String, List<Tool>> byCat = new LinkedHashMap<String, List<Tool>>();
        List<Tool> rest = new ArrayList<Tool>();
        for (Tool t : visible) {
            if (t == null || Str.blank(t.name())) continue;
            byName.put(t.name(), t);
        }
        List<Tool> listed = new ArrayList<Tool>();
        for (Map.Entry<String, List<String>> e : cats.entrySet()) {
            List<Tool> got = new ArrayList<Tool>();
            for (String n : e.getValue()) {
                Tool t = byName.get(n);
                if (t == null) continue;                 // 配置里写了但不可见/不存在：跳过（不报错、不产出）
                got.add(t);
                listed.add(t);
            }
            if (!got.isEmpty()) byCat.put(e.getKey(), got);
        }
        for (Tool t : visible) if (!listed.contains(t)) rest.add(t);
        if (!rest.isEmpty() && Str.has(fallbackCat)) byCat.put(fallbackCat, rest);
        if (byCat.isEmpty()) return null;

        StringBuilder sb = new StringBuilder("tools: {");
        int used = sb.length();
        boolean firstCat = true;
        for (Map.Entry<String, List<Tool>> e : byCat.entrySet()) {
            StringBuilder seg = new StringBuilder();
            seg.append(firstCat ? "" : ",").append(J.json(e.getKey())).append(":[");
            boolean firstTool = true;
            for (Tool t : e.getValue()) {
                String item = J.json(entry(t, withDesc));
                if (!firstTool) seg.append(",");
                seg.append(item);
                firstTool = false;
            }
            seg.append("]");
            if (used + seg.length() + 1 > budget) continue;   // 这一档放不下：跳过它，后面的还能进
            sb.append(seg);
            used += seg.length();
            firstCat = false;
        }
        sb.append("}");
        return firstCat ? null : sb.toString();
    }

    /**
     * 一个工具在索引里的条目。
     *
     * <p>{@code withDesc=true} 时是 <b>工具名 + 空格 + 一句话</b>（描述压成单行、按
     * {@code 条目描述上限} 截断，例 {@code "send 往外发东西的唯一出口，也管撤回"}）。
     * 目的一句话：<b>省掉探索轮</b> —— 她看一眼索引就知道该用哪把，不必先 {@code tools op=show}
     * 转一圈（工具太多时那一圈很贵）。op 取值明细仍只在各工具的 schema 里，索引不重复。</p>
     *
     * <p>描述里的竖线 {@code |} 换成 {@code /}：索引行的既有断言
     * 「这一行里不出现 {@code |}」（= 不罗列 op 取值表）要保住，而描述是自由文本。
     * 只做这一处替换，语义不变（那本来就是个分隔符）。</p>
     */
    private String entry(Tool t, boolean withDesc) {
        if (!withDesc) return t.name();
        String d = Str.oneLine(Str.nz(t.desc())).replace('|', '/');
        if (Str.blank(d)) return t.name();               // 没描述就只给名字（不编一句话）
        return t.name() + " " + Str.cut(d, entryDescMax);
    }

    // ------------------------------------------------------------------ ② 名字没命中

    /**
     * 在该调用者可见的工具里找最接近的几个（命中顺序：归一化同名 → 技能名 → 包含 → 编辑距离）。
     * 返回空表表示"离得太远"，调用方照旧回可用清单。
     */
    public List<Tool> nearest(String name, List<Tool> visible) {
        load();
        List<Tool> out = new ArrayList<Tool>();
        if (visible == null || visible.isEmpty() || Str.blank(name)) return out;
        final String q = norm(name);
        List<Object[]> scored = new ArrayList<Object[]>();     // {Tool, score, distance}
        for (Tool t : visible) {
            if (t == null) continue;
            String n = norm(t.name());
            String o = t.skill() ? norm(t.owner()) : "";
            int score = -1;
            if (n.equals(q)) score = 0;
            else if (!o.isEmpty() && o.equals(q)) score = 1;
            else if (q.length() >= 2 && (n.startsWith(q) || q.startsWith(n) || n.contains(q))) score = 2;
            else if (n.length() >= 3 && q.length() >= 3 && o.isEmpty() && n.contains(q.substring(0, 3))) score = 3;
            else {
                int d = Math.min(edit(n, q), o.isEmpty() ? Integer.MAX_VALUE : edit(o, q));
                int lim = distMax + Math.max(0, Math.min(n.length(), q.length()) - 4) / 4;   // 长名字允许更松
                if (d <= lim) { score = 4; }
                if (score < 0) continue;
                scored.add(new Object[] {t, Integer.valueOf(score), Integer.valueOf(d)});
                continue;
            }
            scored.add(new Object[] {t, Integer.valueOf(score), Integer.valueOf(0)});
        }
        Collections.sort(scored, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                int c = ((Integer) a[1]).compareTo((Integer) b[1]);
                if (c != 0) return c;
                c = ((Integer) a[2]).compareTo((Integer) b[2]);
                if (c != 0) return c;
                return ((Tool) a[0]).name().compareToIgnoreCase(((Tool) b[0]).name());
            }
        });
        for (Object[] row : scored) {
            if (out.size() >= candMax) break;
            out.add((Tool) row[0]);
        }
        return out;
    }

    /** 一个工具"该怎么调"的一行提示：描述 + 必填/可选参数名 + 外挂示例（来自技能 md）。 */
    public String hint(Tool t) {
        load();
        if (t == null) return "";
        StringBuilder sb = new StringBuilder();
        sb.append(t.name()).append("：").append(Str.oneLine(Str.nz(t.desc())));
        List<String> req = new ArrayList<String>();
        List<String> opt = new ArrayList<String>();
        JsonObject props = J.sub(t.params() == null ? new JsonObject() : t.params(), "properties");
        JsonArray required = J.list(t.params() == null ? new JsonObject() : t.params(), "required");
        List<String> reqNames = new ArrayList<String>();
        if (required != null) for (JsonElement e : required) if (e != null && !e.isJsonNull()) reqNames.add(e.getAsString());
        if (props != null) {
            for (Map.Entry<String, JsonElement> e : props.entrySet()) {
                if (reqNames.contains(e.getKey())) req.add(param(e.getKey(), e.getValue()));
                else opt.add(e.getKey());
            }
        }
        if (!req.isEmpty() || !opt.isEmpty()) {
            sb.append("（");
            if (!req.isEmpty()) sb.append("必填 ").append(Str.join(req, "、"));
            if (!req.isEmpty() && !opt.isEmpty()) sb.append("；");
            if (!opt.isEmpty()) sb.append("可选 ").append(Str.join(opt, "/"));
            sb.append("）");
        }
        List<String> ex = t.examples();
        if (ex != null && !ex.isEmpty()) {
            sb.append(" ").append(examplePrefix);
            for (int i = 0; i < ex.size(); i++) {
                if (i > 0) sb.append("；");
                sb.append(ex.get(i));
            }
        }
        return sb.toString();
    }

    /** {@code op(send|collect|…)} 形态的参数签名。 */
    private String param(String name, JsonElement node) {
        if (node == null || !node.isJsonObject()) return name;
        JsonArray en = J.list(node.getAsJsonObject(), "enum");
        if (en == null || en.size() == 0) return name;
        StringBuilder sb = new StringBuilder(name).append("(");
        for (int i = 0; i < en.size(); i++) {
            if (i > 0) sb.append("|");
            sb.append(en.get(i).getAsString());
        }
        return sb.append(")").toString();
    }

    /**
     * 连续失败的<b>提示阈值</b>（0/负 = 没配，主循环就不注入事实）。
     * <p>阈值与文案都在 {@code prompts/tools-index.md}；缺键时返回 -1（调用方报错，不兜底文案）。</p>
     */
    public int failWarnAt() {
        load();
        return failWarnAt;
    }

    /** 连续失败的<b>终止阈值</b>（-1 = 没配）。 */
    public int failStopAt() {
        load();
        return failStopAt;
    }

    /**
     * 终止本次轮次时的说明文本（模板占位符 {@code {tool}} / {@code {n}}）。
     * 文案外挂在 md：缺键返回 null，调用方<b>报错</b>而不是编一句。
     */
    public String failStopText(String tool, int n) {
        load();
        if (Str.blank(failStopTpl)) return null;
        return failStopTpl.replace("{tool}", Str.nz(tool)).replace("{n}", String.valueOf(n));
    }

    /** md 里缺哪些键（供调用方报错，不产出任何兜底文案）。 */
    public String missingFailKeys() {
        load();
        List<String> miss = new ArrayList<String>();
        if (failWarnAt < 0) miss.add("失败提示阈值");
        if (failStopAt < 0) miss.add("失败终止阈值");
        if (Str.blank(failStopTpl)) miss.add("失败终止文案");
        return miss.isEmpty() ? "" : Str.join(miss, "、");
    }

    /**
     * 「回合队列溢出文案」（调度面，{@code sair.v4.schedule.Texts#overflow()}）。
     * <p>缺键返回 null：调用方<b>报错</b>，不编一句兜底话（与失败终止文案同一契约）。</p>
     */
    public String queueOverflowText() {
        load();
        return Str.blank(queueOverflowTpl) ? null : queueOverflowTpl;
    }

    /** 「排队中告知文案」（调度面）；缺键返回 null。 */
    public String queueQueuedText() {
        load();
        return Str.blank(queueQueuedTpl) ? null : queueQueuedTpl;
    }

    /**
     * 「面板标题」：本地交流面板（{@code sair.v4.ui.TalkPanel}）选项卡的标题。
     * <p>缺键返回 null：调用方<b>报"缺哪个键"并且不给面板设名字</b>（框架自己叫「面板N」），
     * 基板不编名字 —— 与「失败终止文案」同一契约。</p>
     */
    public String panelTitle() {
        load();
        return Str.blank(panelTitle) ? null : panelTitle;
    }

    /**
     * 「被单@时的输入」：群里只 @ 一下、没带任何内容时，给模型的用户轮文本。
     * <p>缺键返回 null：调用方<b>报"缺哪个键"并且这一条不起回合</b>（基板不编兜底话）；
     * 这一轮的全部输入就是那段「本会话最近 N 条聊天记录」（{@code chatWindowSize}），模型据此自己接。</p>
     */
    public String bareMentionText() {
        load();
        return Str.blank(bareMentionTpl) ? null : bareMentionTpl;
    }

    /** 「失败回话」：这一轮没跑成时对外说的兜底话（人设口吻、不含内部错误）；缺键返回 null，调用方用内置默认顶上。 */
    public String failNoticeText() {
        load();
        return Str.blank(failNoticeTpl) ? null : failNoticeTpl;
    }

    /**
     * 偏好块的三行文案（{@code prompts/tools-index.md} 的「偏好标题 / 偏好行 / 偏好截断」）。
     *
     * <p>占位符：{@code 偏好行} 里是 {@code {key}}/{@code {value}}（另支持 {@code {scope}}/{@code {importance}}），
     * {@code 偏好截断} 里是 {@code {rest}}（丢掉几条）。</p>
     *
     * <p><b>缺键返回 null —— 但调用方必须用内置默认值兜底</b>（不是"这一路不发"）：
     * 偏好进上下文是功能本身，静默不注入等于功能消失。见 {@link #missingPrefKeys()}。</p>
     */
    public String prefTitle() {
        load();
        return Str.blank(prefTitleTpl) ? null : prefTitleTpl;
    }

    /** 偏好行模板（缺键返回 null，调用方兜底）。 */
    public String prefRow() {
        load();
        return Str.blank(prefRowTpl) ? null : prefRowTpl;
    }

    /** 偏好截断行模板（缺键返回 null，调用方兜底）。 */
    public String prefCut() {
        load();
        return Str.blank(prefCutTpl) ? null : prefCutTpl;
    }

    /** 偏好块缺哪些键（调用方只 warn 一次，然后照旧用内置默认值渲染）。 */
    public String missingPrefKeys() {
        load();
        List<String> miss = new ArrayList<String>();
        if (Str.blank(prefTitleTpl)) miss.add("偏好标题");
        if (Str.blank(prefRowTpl)) miss.add("偏好行");
        if (Str.blank(prefCutTpl)) miss.add("偏好截断");
        return miss.isEmpty() ? "" : Str.join(miss, "、");
    }

    /** 调度面缺哪些键（供调用方在启动时就报出来）。 */
    public String missingQueueKeys() {
        load();
        List<String> miss = new ArrayList<String>();
        if (Str.blank(queueOverflowTpl)) miss.add("队列溢出文案");
        if (Str.blank(queueQueuedTpl)) miss.add("排队中告知文案");
        return miss.isEmpty() ? "" : Str.join(miss, "、");
    }

    /**
     * 「初始化中文案」：基板还在装配（{@code Boot.loading()}）时 {@code chat} 回给主人的那一句。
     * <p>支持四个占位符：{@code {n}}（第几步）、{@code {total}}（总步数）、{@code {step}}（步骤名）、
     * {@code {ms}}（已耗时毫秒）。<b>缺键返回 null</b>：调用方报"缺哪个键"，基板不编兜底话
     * （与「失败终止文案」同一契约）。</p>
     */
    public String initializingText(int n, int total, String step, long ms) {
        load();
        if (Str.blank(initializingTpl)) return null;
        return initializingTpl.replace("{n}", String.valueOf(n))
                .replace("{total}", String.valueOf(total))
                .replace("{step}", Str.nz(step))
                .replace("{ms}", String.valueOf(ms));
    }

    /** 加载面缺哪些键（供装配时就报出来；不产出任何兜底文案）。 */
    public String missingBootKeys() {
        load();
        List<String> miss = new ArrayList<String>();
        if (Str.blank(initializingTpl)) miss.add("初始化中文案");
        if (Str.blank(restartNeedTpl)) miss.add("需要重启提示");
        if (Str.blank(alreadyRunningTpl)) miss.add("已在运行回话");
        if (Str.blank(busyTpl)) miss.add("装配中回话");
        if (Str.blank(startAcceptedTpl)) miss.add("启动已受理回话");
        if (Str.blank(restartAcceptedTpl)) miss.add("重启已受理回话");
        if (Str.blank(panelTitle)) miss.add("面板标题");
        if (Str.blank(bareMentionTpl)) miss.add("被单@时的输入");
        return miss.isEmpty() ? "" : Str.join(miss, "、");
    }

    /**
     * 生命周期面的那句文案（{@code 需要重启提示} / {@code 已在运行回话} / {@code 装配中回话} /
     * {@code 启动已受理回话} / {@code 重启已受理回话}）。
     * <p>占位符：{@code {cmd}}（组件命令前缀）、{@code {key}}（配置键）、{@code {value}}、
     * {@code {progress}}（"第 x/19 步 …"）、{@code {skills}}/{@code {tools}}。
     * <b>缺键返回 null</b>：调用方报"缺哪个键"，基板不编兜底话。</p>
     */
    public String lifeText(String key, String cmd, String cfgKey, String value, String progress,
                           int skills, int tools) {
        load();
        String t;
        if ("需要重启提示".equals(key)) t = restartNeedTpl;
        else if ("已在运行回话".equals(key)) t = alreadyRunningTpl;
        else if ("装配中回话".equals(key)) t = busyTpl;
        else if ("启动已受理回话".equals(key)) t = startAcceptedTpl;
        else if ("重启已受理回话".equals(key)) t = restartAcceptedTpl;
        else return null;
        if (Str.blank(t)) return null;
        return t.replace("{cmd}", Str.nz(cmd))
                .replace("{key}", Str.nz(cfgKey))
                .replace("{value}", Str.nz(value))
                .replace("{progress}", Str.nz(progress))
                .replace("{skills}", String.valueOf(skills))
                .replace("{tools}", String.valueOf(tools));
    }

    /** 生命周期面缺哪个键（单键版；缺键时返回键名，否则空串）。 */
    public String missingLifeKey(String key) {
        load();
        String t;
        if ("需要重启提示".equals(key)) t = restartNeedTpl;
        else if ("已在运行回话".equals(key)) t = alreadyRunningTpl;
        else if ("装配中回话".equals(key)) t = busyTpl;
        else if ("启动已受理回话".equals(key)) t = startAcceptedTpl;
        else if ("重启已受理回话".equals(key)) t = restartAcceptedTpl;
        else return key;
        return Str.blank(t) ? key : "";
    }

    /**
     * 没有 {@code Conf} 时也能定位 {@code prompts/tools-index.md}：按数据根直接拼。
     * <p>装配还没走到「提示词」那一步时 {@code conf} 还是 null，而"加载期间 chat 回一句"恰恰
     * 要在那时候就能读文案。</p>
     */
    public static ToolIndex ofRoot(File root) {
        return root == null ? null : new ToolIndex(new File(new File(root, "prompts"), FILE));
    }

    /**
     * 某个工具在 md 里声明的用法示例（没有就返回空表）。
     * <p>给**内置**工具用：技能工具自己写 front matter 的 {@code airun.examples}，内置工具没有自己的 md，
     * 所以示例写在 {@code prompts/tools-index.md} 的「示例 &lt;工具名&gt;: …」行里，注册期由
     * {@link Registry#add} 补进工具描述 —— Java 里依旧一句文案都没有。</p>
     */
    public List<String> examplesFor(String tool) {
        load();
        List<String> ex = examples.get(Str.trim(tool));
        return ex == null ? new ArrayList<String>() : ex;
    }

    // ------------------------------------------------------------------ ③ 参数复核
    /**
     * 参数复核（<b>只提示、不拦截</b>）：枚举参数给了表外的值、或必填参数缺失时，
     * 返回一行「合法取值/必填清单」；一切正常返回 null。
     * <p>为什么不拦截：技能实现可能接受 md 里没声明的别名，硬拦会把本来能跑通的调用打死；
     * 追加一行提示既没有任何回归风险，又给了模型自我修正需要的信息。</p>
     */
    public String argWarn(Tool t, JsonObject args) {
        if (t == null) return null;
        JsonObject params = t.params();
        JsonObject props = params == null ? null : J.sub(params, "properties");
        if (props == null) return null;
        List<String> bad = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();
        JsonArray required = J.list(params, "required");
        if (required != null) {
            for (JsonElement e : required) {
                if (e == null || e.isJsonNull()) continue;
                String k = e.getAsString();
                JsonElement v = args == null ? null : args.get(k);
                if (v == null || v.isJsonNull() || (v.isJsonPrimitive() && Str.blank(v.getAsString()))) missing.add(k);
            }
        }
        for (Map.Entry<String, JsonElement> e : props.entrySet()) {
            JsonElement node = e.getValue();
            if (node == null || !node.isJsonObject()) continue;
            JsonArray en = J.list(node.getAsJsonObject(), "enum");
            if (en == null || en.size() == 0) continue;
            JsonElement v = args == null ? null : args.get(e.getKey());
            if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) continue;
            String got = v.getAsString();
            boolean hit = false;
            List<String> legal = new ArrayList<String>();
            for (JsonElement x : en) {
                String s = x.getAsString();
                legal.add(s);
                if (s.equalsIgnoreCase(got.trim())) hit = true;
            }
            if (!hit) bad.add(e.getKey() + "=\"" + got + "\" 不在 " + Str.join(legal, "|") + " 内");
        }
        if (bad.isEmpty() && missing.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("（参数复核：");
        if (!missing.isEmpty()) sb.append("缺少必填 ").append(Str.join(missing, "、"));
        if (!missing.isEmpty() && !bad.isEmpty()) sb.append("；");
        if (!bad.isEmpty()) sb.append(Str.join(bad, "；"));
        sb.append("。用 tool 名 ").append(t.name()).append(" 重试）");
        return sb.toString();
    }

    // ------------------------------------------------------------------ 小工具

    /** 归一化：小写 + 去掉分隔符/空白。 */
    public static String norm(String s) {
        return Str.nz(s).toLowerCase().replaceAll(SEP, "");
    }

    /** 编辑距离（两行滚动数组）。 */
    public static int edit(String a, String b) {
        String x = Str.nz(a), y = Str.nz(b);
        if (x.equals(y)) return 0;
        if (x.isEmpty()) return y.length();
        if (y.isEmpty()) return x.length();
        int[] prev = new int[y.length() + 1];
        int[] cur = new int[y.length() + 1];
        for (int j = 0; j <= y.length(); j++) prev[j] = j;
        for (int i = 1; i <= x.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= y.length(); j++) {
                int cost = x.charAt(i - 1) == y.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[y.length()];
    }
}
