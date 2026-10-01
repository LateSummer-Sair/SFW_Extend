package sair.v4.qq;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出站正文的统一口径（给"不经出站管线"的那条路用：{@code Api} 直接发文本 / {@code send} 工具）。
 *
 * <p>正常回复走 {@code term.Sinks} 那条管线：先跑 OutboundStage（点名引用的标记→CQ 码）、
 * 再剥控制标记、再纯文字。但工具发消息是直接调 {@link Api} 的，**整条管线都不过** ——
 * 真机两次事故都出在这里（截图里的 {@code <at qq=…/>} 与 DSML 残片原样落群；
 * 以及把 {@code <quote id=…/>} 剥掉、引用悄悄丢了）。</p>
 *
 * <p>所以这里给那条路补一套同口径的处理，顺序与管线一致：</p>
 * <ol>
 *   <li><b>标记 → CQ 码</b>：{@code <at qq="N"/>} ⇒ {@code [CQ:at,qq=N]}、
 *       {@code <quote id="N"/>}／{@code <reply id="N"/>} ⇒ {@code [CQ:reply,id=N]}；</li>
 *   <li>{@link MarkerTags#strip} 剥掉其余白名单控制标记（这里没转的，不该原样发出去）；</li>
 *   <li>{@link PlainText#clean} 纯文字口径（主人裁：输出严禁 Markdown）。</li>
 * </ol>
 *
 * <p>{@link ToolMarkup} 那一道不在这里做：它要"整条不发"，属于调用方的决定（见 {@code Api.sendText}）。</p>
 */
public final class ChatMarkers {

    private ChatMarkers() {
    }

    /*
     * 取值口径（四条正则共用同一段：("[^"]*"|'[^']*'|[^\s>"'/]*)，标签体一律「引号感知」，
     * 改一处必须四处同改 —— 管线路的 skill「点名引用\AtPickStage」里是同一份拷贝）。
     *
     * 标签体 = (?:[^>"']|"[^"]*"|'[^']*')*：属性值里带 > 不会把标签截半
     * （<quote id="5" title="a > b"/> 是**一个**完整标签，不是到第一个 > 就收口，
     * 所以不会剩下 "/> 之类当正文漏出去）；引号必须成对。
     * 取值段 = "[^"]*"（整段双引号）/ '[^']*'（整段单引号）/ 裸词（不含空白、引号、>、/）；
     * 值后面若还跟着 /，只有当它是自闭合的那一个（/ 紧跟 >）才作数。
     *
     * 认：id="5" / id='5' / id=5 / id=5/（自闭合不带空格）/ id=" 5 " / id=" 5" / id="5 "
     *     （引号内两侧空白）/ id = "5" / 成对 <quote id="5">原文</quote> /
     *     带其它属性 <quote id="5" foo="bar"/> / 大小写混写。
     * 不认（整段 fail-closed：标记跟它自己的字节一起删掉 —— 既不产码，也不把残片当正文漏出去）：
     *     <quotation id="5"/>（标签名要精确）、<quote idx="5"/>（不是 id 这个词）、
     *     正文里孤立的 id=5（没有标记就没有这一步）；号码不合法（空 / 0 / 非十进制 / 超长 /
     *     全角数字）；取值里有垃圾 —— id="5/"、id="5 /"、id="5/6"、id="5 6"、id="5/ >"、id="5>"
     *     都丢掉：宁可没有引用，也不把 "5 /" 截断成 5 那种「看起来对、其实是猜的」号码。
     * <at> 的两条同形，键是 qq。
     */
    /** {@code <at qq="1">名字</at>}（成对，整段吃掉）。 */
    private static final Pattern AT_PAIR = Pattern.compile(
            "<at\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*\\bqq\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"'/]*)\\s*(?!/(?!>))(?:[^>\"']|\"[^\"]*\"|'[^']*')*>[\\s\\S]*?</at\\s*>",
            Pattern.CASE_INSENSITIVE);
    /** {@code <at qq="1"/>}。 */
    private static final Pattern AT_ONE = Pattern.compile(
            "<at\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*\\bqq\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"'/]*)\\s*(?!/(?!>))(?:[^>\"']|\"[^\"]*\"|'[^']*')*/>",
            Pattern.CASE_INSENSITIVE);
    /** {@code <quote id="1">原文</quote>}（{@code reply} 同义）。 */
    private static final Pattern QT_PAIR = Pattern.compile(
            "<(?:quote|reply)\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*\\bid\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"'/]*)\\s*(?!/(?!>))(?:[^>\"']|\"[^\"]*\"|'[^']*')*>[\\s\\S]*?</(?:quote|reply)\\s*>",
            Pattern.CASE_INSENSITIVE);
    /** {@code <quote id="1"/>}。 */
    private static final Pattern QT_ONE = Pattern.compile(
            "<(?:quote|reply)\\b(?:[^>\"']|\"[^\"]*\"|'[^']*')*\\bid\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>\"'/]*)\\s*(?!/(?!>))(?:[^>\"']|\"[^\"]*\"|'[^']*')*/>",
            Pattern.CASE_INSENSITIVE);

    /**
     * 标记 → CQ 码 + 剥其余控制标记 + 纯文字。号码不合法的标记直接丢掉（宁可没有引用，也不发畸形码）。
     */
    public static String clean(String text) {
        if (text == null) return "";
        String s = text;
        if (s.indexOf('<') >= 0) {
            s = swap(s, QT_PAIR, "id", "[CQ:reply,id=");
            s = swap(s, QT_ONE, "id", "[CQ:reply,id=");
            s = swap(s, AT_PAIR, "qq", "[CQ:at,qq=");
            s = swap(s, AT_ONE, "qq", "[CQ:at,qq=");
        }
        return PlainText.clean(MarkerTags.strip(s));
    }

    /** 把命中的标记换成 {@code 前缀+号码+]}；号码不是十进制正数就丢掉这个标记。 */
    private static String swap(String text, Pattern p, String key, String prefix) {
        Matcher m = p.matcher(text);
        if (!m.find()) return text;
        StringBuffer sb = new StringBuffer();
        do {
            String raw = m.group(1);
            String num = digits(raw);
            String rep = "";
            if (num != null) {
                rep = prefix + num + "]";
                if (m.end() < text.length() && !Character.isWhitespace(text.charAt(m.end()))) rep = rep + " ";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        } while (m.find());
        m.appendTail(sb);
        return sb.toString();
    }

    private static String digits(String raw) {
        String s = unquote(raw);
        if (s.isEmpty() || s.length() > 20) return null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return null;
        }
        return s.charAt(0) == '0' ? null : s;
    }

    /** 取值段是「整段取值」（可能带成对引号）：剥掉外面那一对引号与两侧空白，再判号码。 */
    private static String unquote(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() >= 2) {
            char q = s.charAt(0);
            if ((q == '"' || q == '\'') && s.charAt(s.length() - 1) == q) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }
}
