package sair.v4.qq;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.v4.auth.Caller;
import sair.v4.auth.Favor;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 好感度标记：<b>她自己</b>在回复里表达"这个人该加还是该减"，基板照她说的执行。
 *
 * <h3>为什么是一支标记、而不是一个工具</h3>
 * <p>主人裁 2026-09-17：「加减好感度由它自己（SYSTEM）主导，它觉得那个人该减，那就减……
 * 主人拥有让它修改任何人好感度直接数值的能力。」工具调用的身份是<b>当轮说话的人</b>
 * （ACL：T 类默认分配对 {@code ALLUSER} 是 {@code NONE}，见 D43）—— 若走工具，她在普通群友
 * 的会话里<b>根本没有手</b>，"她自己主导"就成了空话。标记跑在<b>基板侧（SYSTEM 路径）</b>：
 * 谁在说话都不影响她表达自己的判断，权限面也不必为一个动作开口子。</p>
 *
 * <h3>她写什么</h3>
 * <ul>
 *   <li>{@code <favor qq="123" add="2" why="他帮了我"/>} ⇒ 加（不写 {@code kind} ⇒ 夹在总区间
 *       {@code [1,7]}）；</li>
 *   <li>{@code <favor qq="123" sub="8" why="张嘴就骂人"/>} ⇒ 减（同 ⇒ {@code [1,12]}）；</li>
 *   <li>{@code <favor qq="123" kind="帮忙" add="4" why="替我查了东西"/>} ⇒ 加，<b>按来往类别夹</b>
 *       （帮忙 = {@code [3,5]}；类别表 13 条见 {@link Favor#kindTable()}）；</li>
 *   <li>{@code <favor qq="123" set="800"/>} ⇒ 直接定值，<b>只有主人</b>能走（不是主人 ⇒ 整条不执行）；</li>
 *   <li>{@code qq} 省略 = 这一轮跟她说话的人（{@code caller.qq()}）。</li>
 * </ul>
 *
 * <h3>kind 的两条 fail-closed（甲方/GM 2026-09-26）</h3>
 * <ul>
 *   <li><b>认不出的 kind</b>（例 {@code kind="乱写"}）⇒ 整条不执行，一行结构事实写明"kind 不认"；
 *       <b>绝不回落成总区间</b>（打错一个词就等于放宽）；</li>
 *   <li><b>kind 与方向不符</b>（例 {@code kind="夸赞" sub="2"}）⇒ 同样整条不执行；</li>
 *   <li>带 {@code kind} 时只认<b>一个</b>动作：同时写 {@code add} 与 {@code sub} ⇒ 整条不执行。</li>
 * </ul>
 *
 * <h3>★ 批 15 / 2026-09-26：步数扫描要"看引号"（缺陷：属性里的 {@code >} 会按顺序静默失效）</h3>
 * <p>{@link #PAIR}/{@link #ONE} 原来是 {@code <favor\b([^>]*?)…>} —— <b>碰到的第一个 {@code >}</b>
 * 就当成属性区的结束。属性值里出现 {@code >} 时（例 {@code why="他说 a>b 很硬"}）属性区被截断，
 * 后果<b>取决于属性顺序</b>：</p>
 * <ul>
 *   <li>{@code <favor qq="1" add="5" why="他说 a>b 很硬"/>} ⇒ {@code add} 在截断点之前 ⇒ 标记仍执行、
 *       但 {@code why} 丢了（事实行里没有 why）；</li>
 *   <li>{@code <favor qq="1" why="他说 a>b 很硬" add="5"/>} ⇒ {@code add} 在 {@code why} <b>之后</b>
 *       ⇒ {@link #attr} 拿不到 add/sub/set ⇒ {@code act} 开头那句"没有动作 = 什么都不做"
 *       <b>整条静默不执行</b>：无日志、无拒绝、用户侧什么都看不见。</li>
 * </ul>
 * <p>新口径：属性区先按<b>引号感知的结构化文法</b>认（{@link #ATTRS}：引号里的 {@code >} 不算结束），
 * 认不出来（半截引号、模型乱写）逐字退回老口径 {@link #ATTRS_LAX} —— <b>老行为一个字节都不丢</b>。
 * {@code qq}/{@code add}/{@code sub}/{@code set}/{@code kind}/{@code why} 的语义、夹取、
 * 三条 fail-closed 与事实行形状<b>一个字节都没改</b>（{@code kind=… 允许 [lo,hi]} 那一段照旧）。</p>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>标记一定<b>不会发给用户</b>：{@code favor} 已进 {@link MarkerTags} 白名单，而剥除发生在
 *       本类之后 —— 这里漏了，基板也会剥掉（安全网，两处都认同一个标签名）；</li>
 *   <li>号码/数值不合法 ⇒ 这条标记丢掉（宁可不动，也不写一个坏数）；</li>
 *   <li>值夹在 {@code [0, Favor.CAP]}、单次幅度由 {@link Favor#change} 夹死；每一次改动都写
 *       {@code favor_event} 流水；</li>
 *   <li>异常全吞：出问题最多不改好感度，<b>绝不拖垮一条发送</b>。</li>
 * </ul>
 */
public final class FavorTags {

    /** 标签名（与 {@link MarkerTags} 白名单里的同名项必须一致）。 */
    public static final String TAG = "favor";

    /**
     * 属性区（批 15：<b>引号感知</b>）—— {@code 名="值"} / {@code 名='值'} / {@code 名=值} 的序列，
     * <b>引号里的 {@code >} 不算属性区的结束</b>。属性名限定 ASCII 标识符（与 {@link MarkerTags}
     * 的收尾文法同一套口径）；裸值不含 {@code /}、{@code >}、引号，所以这里捕获到的属性区与老口径
     * 在"标准形状"上逐字相同（不会把结尾那个 {@code /} 吞进来）。
     */
    private static final String ATTRS =
            "(?:\\s+[A-Za-z_][A-Za-z0-9_.:-]*\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>\"'/]+))*";
    /** 老口径兜底（批 15 之前逐字）：碰到的第一个 {@code >} 就是属性区的结束。 */
    private static final String ATTRS_LAX = "[^>]*?";

    /** 成对写法：{@code <favor …>文字</favor>}（内层文字一并吃掉）。 */
    private static final Pattern PAIR = Pattern.compile(
            "<favor\\b(" + ATTRS + "\\s*|" + ATTRS_LAX + ")>[\\s\\S]*?</favor\\s*>", Pattern.CASE_INSENSITIVE);
    /** 自闭合/单标签写法：{@code <favor …/>}。 */
    private static final Pattern ONE = Pattern.compile(
            "<favor\\b(" + ATTRS + "\\s*|" + ATTRS_LAX + ")/?>", Pattern.CASE_INSENSITIVE);
    /** 属性：{@code 名="值"} / {@code 名='值'} / {@code 名=值}。 */
    private static final Pattern ATTR = Pattern.compile(
            "([a-zA-Z_]+)\\s*=\\s*\"([^\"]*)\"|([a-zA-Z_]+)\\s*=\\s*'([^']*)'|([a-zA-Z_]+)\\s*=\\s*([^\\s\"/>]+)");

    private FavorTags() {}

    /**
     * 把整批正文里的好感度标记全部执行掉，并返回"去掉标记之后"的正文表。
     *
     * @param parts  本批最终消息（{@code stage(...)} 之后、剥标记之前）
     * @param caller 这一轮的说话人（可为 null：定时任务落点）
     * @param favor  好感度子系统（可为 null：没装配 ⇒ 本类整个不做事）
     * @param out    日志出口（可为 null）
     * @return 去掉标记后的新表；一条标记都没有 ⇒ {@code null}（调用方原样不动）
     */
    public static List<String> apply(List<String> parts, Caller caller, Favor favor, Out out) {
        if (parts == null || parts.isEmpty() || favor == null) return null;
        List<String> outParts = new ArrayList<String>(parts);
        boolean hit = false;
        for (int i = 0; i < outParts.size(); i++) {
            String s = outParts.get(i);
            if (s == null || s.indexOf('<') < 0 || s.toLowerCase(Locale.ROOT).indexOf("<favor") < 0) continue;
            String r = run(s, caller, favor, out);
            if (!r.equals(s)) {
                outParts.set(i, r.trim());
                hit = true;
            }
        }
        return hit ? outParts : null;
    }

    /** 一条正文里的所有标记：先执行、再从正文里去掉。 */
    private static String run(String text, Caller caller, Favor favor, Out out) {
        String r = exec(text, PAIR, caller, favor, out);
        return exec(r, ONE, caller, favor, out);
    }

    private static String exec(String text, Pattern p, Caller caller, Favor favor, Out out) {
        Matcher m = p.matcher(text);
        if (!m.find()) return text;
        StringBuffer sb = new StringBuffer();
        do {
            act(m.group(1), caller, favor, out);
            m.appendReplacement(sb, "");
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    /** 执行一条标记（属性 → 目标 + 动作 + 原因）。 */
    private static void act(String attrs, Caller caller, Favor favor, Out out) {
        try {
            String qqArg = attr(attrs, "qq");
            String add = attr(attrs, "add");
            String sub = attr(attrs, "sub");
            String set = attr(attrs, "set");
            String why = attr(attrs, "why");
            String kind = attr(attrs, "kind");
            if (add == null && sub == null && set == null) return;      // 没有动作 = 什么都不做

            long qq = num(qqArg, 0L);
            if (qq <= 0L) qq = caller == null ? 0L : caller.qq();
            if (qq <= 0L) {
                dim(out, "好感度标记没有可用的对象（qq 没给、这一轮也没有说话人）⇒ 不动");
                return;
            }
            boolean master = caller != null && caller.master();
            String by = master ? "master" : (caller == null ? "system" : "self");

            if (set != null) {
                if (!master) {
                    dim(out, "好感度直改被拒（只有主人能定值）：qq=" + qq + " set=" + set);
                    return;
                }
                double v = dbl(set, -1.0D);
                if (v < 0.0D) return;
                Favor.Change c = favor.setValue(qq, v, why, by);
                dim(out, line(c, "直改", by, why));
                return;
            }
            // ★ 带 kind 时只认一个动作：同时写 add 与 sub = 她自己把两件事说岔了 ⇒ 整条不执行（fail-closed）。
            if (!Str.blank(kind) && add != null && sub != null) {
                dim(out, "好感度未执行 qq=" + qq + " kind=" + Str.cut(Str.oneLine(kind), 24)
                        + " 同时写了 add 与 sub（带 kind 时只认一个动作）⇒ 整条不执行（fail-closed）"
                        + (Str.blank(why) ? "" : " · why=" + Str.cut(Str.oneLine(why), 40)));
                kindTableOnce(out);
                return;
            }
            boolean any = false;
            if (sub != null) {
                double d = dbl(sub, -1.0D);
                if (d < 0.0D) return;
                Favor.Change c = favor.change(qq, d, "sub", kind, why, by);
                dim(out, line(c, "减", by, why));
                any = true;
                if (c != null && c.rejected) {
                    kindTableOnce(out);
                    return;
                }
            }
            if (add != null) {
                double d = dbl(add, -1.0D);
                if (d < 0.0D) return;
                Favor.Change c = favor.change(qq, d, "add", kind, why, by);
                dim(out, line(c, "加", by, why));
                any = true;
                if (c != null && c.rejected) kindTableOnce(out);
            }
            if (any && !Str.blank(kind)) kindTableOnce(out);
        } catch (Throwable t) {
            // 好感度是"顺手记的账"，绝不能因为它拖垮一条发送
        }
    }

    /** 这一行只打一次（一个进程一次）：可机读的 kind 清单 + 区间，让她/日志能学会怎么记。 */
    private static final java.util.concurrent.atomic.AtomicBoolean KIND_TABLE_SHOWN =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void kindTableOnce(Out out) {
        if (!KIND_TABLE_SHOWN.compareAndSet(false, true)) return;
        try {
            dim(out, "kind表（来往类别 → 区间；写 <favor kind=\"…\"/> 时按它夹，"
                    + "不认识或方向不符 ⇒ 整条不执行）" + Favor.kindTable());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 一行结构事实：改前 → 改后（档位）· 实际幅度 · 谁改的 · 为什么。
     * <p><b>不写 kind 时与加这一批之前逐字节相同</b>（既有字段一个不删、顺序不变）；
     * 写了 kind 才在"实际幅度"之后多插一段 {@code · kind=… 允许 [lo,hi]}；被拒的那一条走
     * {@link #rejectLine}，形状与它不同（一眼能分辨"没执行"与"执行了"）。</p>
     */
    private static String line(Favor.Change c, String what, String by, String why) {
        if (c == null) return "好感度标记没有生效";
        if (c.rejected) return rejectLine(c, by, why);
        StringBuilder sb = new StringBuilder();
        sb.append("好感度").append(what).append(" qq=").append(c.qq).append(' ').append(fmt(c.before))
                .append(" → ").append(fmt(c.after)).append("（").append(level(c.after))
                .append(" · 实际 ").append(c.applied >= 0 ? "+" : "").append(fmt(c.applied));
        if (!c.kind.isEmpty()) {
            sb.append(" · kind=").append(c.kind).append(" 允许 ").append(Favor.rangeText(c.lo, c.hi));
        }
        if (c.clamped) sb.append(" · 已按单次区间夹取");
        sb.append(" · by=").append(by);
        if (!Str.blank(why)) sb.append(" · why=").append(Str.cut(Str.oneLine(why), 40));
        sb.append('）');
        return sb.toString();
    }

    /** 整条没执行的那一行事实（kind 不认 / 方向不符）：既有字段一个不删，另加原因与允许区间。 */
    private static String rejectLine(Favor.Change c, String by, String why) {
        StringBuilder sb = new StringBuilder();
        sb.append("好感度未执行 qq=").append(c.qq).append(" ").append(c.op).append("=")
                .append(c.kindRaw.isEmpty() ? "?" : c.kindRaw);
        if (!c.kind.isEmpty()) sb.append(" · kind=").append(c.kind);
        sb.append(" ⇒ ").append(c.reject).append(" · by=").append(by);
        if (!Str.blank(why)) sb.append(" · why=").append(Str.cut(Str.oneLine(why), 40));
        return sb.toString();
    }

    /** 档位名（只为日志好读；判定一律用数值）。 */
    private static String level(double v) {
        try {
            return new Favor(null).levelName(v);
        } catch (Throwable t) {
            return "";
        }
    }

    private static String attr(String attrs, String key) {
        if (Str.blank(attrs)) return null;
        Matcher m = ATTR.matcher(attrs);
        while (m.find()) {
            String k = m.group(1) != null ? m.group(1) : (m.group(3) != null ? m.group(3) : m.group(5));
            String v = m.group(2) != null ? m.group(2) : (m.group(4) != null ? m.group(4) : m.group(6));
            if (k != null && k.equalsIgnoreCase(key)) return v == null ? "" : v.trim();
        }
        return null;
    }

    /** 只认非负十进制（含小数）；认不出返回 {@code def}。 */
    private static double dbl(String s, double def) {
        if (Str.blank(s)) return def;
        String v = Str.trim(s);
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if ((ch < '0' || ch > '9') && ch != '.') return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (Throwable t) {
            return def;
        }
    }

    private static long num(String s, long def) {
        double d = dbl(s, -1.0D);
        return d < 0.0D ? def : (long) d;
    }

    /** 整数不带小数点，小数留一位（日志里别印一长串浮点尾巴）。 */
    private static String fmt(double v) {
        long l = (long) v;
        return v == (double) l ? String.valueOf(l) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static void dim(Out out, String s) {
        try {
            if (out != null) out.dim("[favor] " + s);
        } catch (Throwable ignored) {
        }
    }
}
