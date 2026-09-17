package sair.v4.qq;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>出站最后一道闸：识别"工具调用标记被当成正文"的文本</b>。
 *
 * <h3>为什么要有它（真机事故，2026-09-16 22:12，测试群 121873503）</h3>
 * <p>模型（{@code deepseek-flash}）把它的<b>工具调用标记</b>当成正文吐了出来：那一轮的
 * {@code choices[0].delta.content} 里是一串标签，而 {@code tool_calls} 是<b>空</b>。
 * 于是 {@code agent.Loop} 判定"这一轮没有工具调用 = 最终回答"，正文经
 * {@code agent.Agent.ask} → {@code Sinks.QqSink.say} → 按群聊上限切成 3 条，
 * 原封不动发进了真群（甲方看到的"乱码"）。</p>
 *
 * <p>{@link MarkerTags} 拦不住它：那张表是<b>白名单</b>（{@code <split>} / {@code <quote>} 这类
 * V3 控制标记），而工具调用标记（DSML 一族）根本不在名单里；而且白名单剥离是"删字符后照发"，
 * 半剥的残留比整条不发更糟。所以这里<b>不做删除、只做判断</b>：命中就<b>整条丢弃</b>，
 * 由 {@code term.Sinks} 那两个落点各留一行可观测日志。</p>
 *
 * <h3>判据是<b>结构</b>，不是关键词表</h3>
 * <p>三条规则都要求<b>标签形状</b>（必须有一个 {@code <}，且候选词与它同处一个尖括号片段内，
 * 不跨 {@code >}）。因此正常中文/代码里的 {@code List<String>}、{@code x < 0 > y}、
 * {@code a &lt; b}、"我 invoke 了一个函数"这类文本<b>一条都不命中</b>。三条规则：</p>
 * <ol>
 *   <li><b>DSML 标记</b>：{@code <} 之后 8 字符内出现 {@code DSML}（大小写不敏感）——
 *       事故里每一条都是这个形态；</li>
 *   <li><b>工具调用结构词</b>：标签名是 {@code invoke} / {@code parameter} / {@code tool_calls}
 *       / {@code tool_call} / {@code function_calls} / {@code functions} / {@code tools}
 *       （尖括号与词名之间只允许竖线、空白、{@code /}，或一个 {@code DSML}）——
 *       覆盖"没有 DSML 外壳、只有 invoke/parameter"的写法；</li>
 *   <li><b>特殊 token 形状</b>：{@code <} 或 {@code </} 之后<b>紧跟</b> 1–3 个竖线
 *       （{@code <||…} / {@code </||…}）—— 模型特殊 token 的形状本身。</li>
 * </ol>
 *
 * <p><b>竖线半角与全角都算</b>：{@code |}（U+007C）与 {@code ｜}（U+FF5C，全角竖线）。
 * 全角那一支是模型特殊 token 的分隔符（它的词表就是 {@code …｜…} 形状）。真机上漏出来的正是
 * <b>全角</b>竖线 —— 全仓 {@code src\} 里一个 U+FF5C 都没有，所以它<b>不是</b>被哪一层改写出来的，
 * 而是 API 返回的正文本来就长这样（证据见 {@code tmp\leak\REPORT.md}）。</p>
 */
public final class ToolMarkup {

    private ToolMarkup() {
    }

    /** 竖线：半角 {@code |}（U+007C）与全角 {@code ｜}（U+FF5C）。 */
    private static final String PIPE = "[|\uFF5C]";

    /** 左尖括号：半角 {@code <}（U+003C）与全角 {@code ＜}（U+FF1C）。 */
    private static final String LT = "[<\uFF1C]";

    /** 一个"标签内"字符：不跨尖括号（半角/全角都算边界），也不跨换行。 */
    private static final String IN_TAG = "[^<>\uFF1C\uFF1E\\n]";

    /** ① DSML 标记（同一个尖括号片段内，不跨尖括号）。 */
    private static final Pattern DSML =
            Pattern.compile(LT + "/?" + IN_TAG + "{0,8}DSML", Pattern.CASE_INSENSITIVE);

    /** ② 标签形状的工具调用结构词（尖括号与词名之间只允许竖线/空白/{@code /}，或一个 DSML）。 */
    private static final Pattern STRUCT = Pattern.compile(
            LT + "/?" + PIPE + "*\\s*(?:DSML" + PIPE + "*\\s*)?"
            + "(?:invoke|parameter|tool_calls|tool_call|function_calls|functions|tools)\\b",
            Pattern.CASE_INSENSITIVE);

    /** ③ 特殊 token 形状：左尖括号（可带 {@code /}）之后紧跟 1–3 个竖线。 */
    private static final Pattern SPECIAL = Pattern.compile(LT + "/?" + PIPE + "{1,3}");

    /**
     * 这段文本是不是"工具调用标记被当成了正文"。
     *
     * @param text 待发正文（null / 不含 {@code <} → false，一个字都不多花）
     */
    public static boolean has(String text) {
        return rule(text) != null;
    }

    /**
     * 命中的判据名（{@code dsml} / {@code special_token} / {@code struct_word}）；
     * 没命中返回 {@code null}。给日志用 —— <b>日志里只出现判据名，不出现正文</b>。
     */
    public static String rule(String text) {
        if (text == null) return null;
        // 不含尖括号（半角或全角）= 不可能有标签形状 ⇒ 三个正则一个都不跑（热路径省一次扫描）
        if (text.indexOf('<') < 0 && text.indexOf('\uFF1C') < 0) return null;
        if (DSML.matcher(text).find()) return "dsml";
        if (SPECIAL.matcher(text).find()) return "special_token";
        if (STRUCT.matcher(text).find()) return "struct_word";
        return null;
    }

    /**
     * 一行结构事实：判据名 + 标记个数 + 字符数（<b>不含正文</b>）。
     * 与 {@code [qq] msg … chars=} 同一口径：够回答"为什么拦、拦了多大一条"，又不把正文抄进日志。
     */
    public static String fact(String text) {
        String r = rule(text);
        if (r == null) return "rule=none";
        return "rule=" + r + " tags=" + count(text) + " chars=" + (text == null ? 0 : text.length());
    }

    /** 标记个数（三条规则命中次数之和；只用于事实行，不影响判定）。 */
    private static int count(String text) {
        int n = 0;
        n += matches(DSML, text);
        n += matches(SPECIAL, text);
        n += matches(STRUCT, text);
        return n;
    }

    private static int matches(Pattern p, String text) {
        Matcher m = p.matcher(text == null ? "" : text);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
