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
 *   <li>{@code <favor qq="123" add="2" why="他帮了我"/>} ⇒ 加（基板夹在 {@code [1,3]}）；</li>
 *   <li>{@code <favor qq="123" sub="8" why="张嘴就骂人"/>} ⇒ 减（基板夹在 {@code [5,10]}）；</li>
 *   <li>{@code <favor qq="123" set="800"/>} ⇒ 直接定值，<b>只有主人</b>能走（不是主人 ⇒ 整条不执行）；</li>
 *   <li>{@code qq} 省略 = 这一轮跟她说话的人（{@code caller.qq()}）。</li>
 * </ul>
 *
 * <h3>边界</h3>
 * <ul>
 *   <li>标记一定<b>不会发给用户</b>：{@code favor} 已进 {@link MarkerTags} 白名单，而剥除发生在
 *       本类之后 —— 这里漏了，基板也会剥掉（安全网，两处都认同一个标签名）；</li>
 *   <li>号码/数值不合法 ⇒ 这条标记丢掉（宁可不动，也不写一个坏数）；</li>
 *   <li>值下界 0、单次幅度由 {@link Favor} 夹死；每一次改动都写 {@code favor_event} 流水；</li>
 *   <li>异常全吞：出问题最多不改好感度，<b>绝不拖垮一条发送</b>。</li>
 * </ul>
 */
public final class FavorTags {

    /** 标签名（与 {@link MarkerTags} 白名单里的同名项必须一致）。 */
    public static final String TAG = "favor";

    /** 成对写法：{@code <favor …>文字</favor>}（内层文字一并吃掉）。 */
    private static final Pattern PAIR = Pattern.compile(
            "<favor\\b([^>]*?)>[\\s\\S]*?</favor\\s*>", Pattern.CASE_INSENSITIVE);
    /** 自闭合/单标签写法：{@code <favor …/>}。 */
    private static final Pattern ONE = Pattern.compile(
            "<favor\\b([^>]*?)/?>", Pattern.CASE_INSENSITIVE);
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
            if (sub != null) {
                double d = dbl(sub, -1.0D);
                if (d < 0.0D) return;
                Favor.Change c = favor.change(qq, d, "sub", why, by);
                dim(out, line(c, "减", by, why));
            }
            if (add != null) {
                double d = dbl(add, -1.0D);
                if (d < 0.0D) return;
                Favor.Change c = favor.change(qq, d, "add", why, by);
                dim(out, line(c, "加", by, why));
            }
        } catch (Throwable t) {
            // 好感度是"顺手记的账"，绝不能因为它拖垮一条发送
        }
    }

    /** 一行结构事实：改前 → 改后（档位）· 实际幅度 · 谁改的 · 为什么。 */
    private static String line(Favor.Change c, String what, String by, String why) {
        if (c == null) return "好感度标记没有生效";
        return "好感度" + what + " qq=" + c.qq + " " + fmt(c.before) + " → " + fmt(c.after)
                + "（" + level(c.after) + " · 实际 " + (c.applied >= 0 ? "+" : "") + fmt(c.applied)
                + (c.clamped ? " · 已按单次区间夹取" : "") + " · by=" + by
                + (Str.blank(why) ? "" : " · why=" + Str.cut(Str.oneLine(why), 40)) + "）";
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
