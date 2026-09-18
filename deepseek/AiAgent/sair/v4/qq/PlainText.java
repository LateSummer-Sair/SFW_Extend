package sair.v4.qq;

import java.util.regex.Pattern;

/**
 * 纯文字口径：<b>发出去的东西不许是 Markdown</b>（主人裁 2026-09-18：
 * 「输出控制台和 QQ 消息严禁使用 markdown 格式消息，必须纯文字和常用文章标点，符号能少用就少用」）。
 *
 * <p>为什么放在基板最底层的发送口上，而不是靠提示词：提示词只管"她大概不会写"，
 * 真正要的是<b>写了她也发不出去</b>。所以这里是一支纯函数清洗器，接在
 * {@link Api#sendGroupMsg} / {@link Api#sendPrivateMsg} 与控制台落点上 —— 无论正文从哪来
 * （她的回复、工具发的、定时任务发的、技能追加的），出去的都是纯文字。</p>
 *
 * <h3>清什么（只清格式符号，不动正文一个字）</h3>
 * <ul>
 *   <li>代码护栏 ``` / ~~~ 整行去掉，内层文字留下；行内反引号去掉；</li>
 *   <li>{@code **粗**}/{@code __粗__}/{@code *斜*}/{@code _斜_} 去标记留文字；</li>
 *   <li>行首标题 {@code #}/{@code ##}、引用 {@code >}、项目符号 {@code -}/{@code *}/{@code +} 去掉；
 *       有序列表 {@code 1.} 换成中文顿号写法 {@code 1、}（数字留着，序号是内容不是格式）；</li>
 *   <li>横向分隔线（{@code ---} / {@code ***} / {@code ___} 独占一行）整行去掉；</li>
 *   <li>表格：分隔行整行去掉，格子竖线换成空格；</li>
 *   <li>链接/图片 {@code [文字](地址)} ⇒ {@code 文字（地址）}（地址留着，它是有用的内容）；</li>
 *   <li>{@code <br>} 换成真换行；连续空行压到最多一行空行；行尾空格去掉。</li>
 * </ul>
 *
 * <p><b>不做什么</b>：不动中文标点、不动数字、不动正文用词、不删 emoji ——
 * "符号能少用就少用"是说话的分寸（写在人设里），不是把标点当违规删掉。</p>
 */
public final class PlainText {

    private PlainText() {}

    /** 代码护栏整行：```lang / ``` / ~~~ / ~~~lang。 */
    private static final Pattern FENCE = Pattern.compile("^\\s*(?:`{3,}|~{3,})\\s*\\S*\\s*$");
    /** 横向分隔线整行：三个以上 - * _ 夹杂空格。 */
    private static final Pattern HR = Pattern.compile("^\\s*(?:[-*_]\\s*){3,}$");
    /** 行首标题。 */
    private static final Pattern H = Pattern.compile("^\\s{0,3}#{1,6}\\s+");
    /** 行首引用。 */
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?");
    /** 行首项目符号。 */
    private static final Pattern BULLET = Pattern.compile("^\\s{0,3}[-*+]\\s+");
    /** 行首有序列表（1. / 1) ）⇒ 换成 1、（数字是内容，标记是格式）。 */
    private static final Pattern ORDERED = Pattern.compile("^(\\s{0,3})(\\d{1,3})[.)]\\s+");
    /** 表格分隔行：| --- | :--: |。 */
    private static final Pattern TABLE_SEP = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(?:\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    /** 加粗/斜体（成对标记）。 */
    private static final Pattern BOLD2 = Pattern.compile("\\*\\*(?=\\S)([\\s\\S]*?\\S)\\*\\*");
    private static final Pattern BOLD2U = Pattern.compile("__(?=\\S)([\\s\\S]*?\\S)__");
    private static final Pattern EM1 = Pattern.compile("(?<![\\w*])\\*(?=\\S)([^*\\n]*?\\S)\\*(?![\\w*])");
    private static final Pattern EM1U = Pattern.compile("(?<![\\w_])_(?=\\S)([^_\\n]*?\\S)_(?![\\w_])");
    /** 删除线 ~~文字~~。 */
    private static final Pattern STRIKE = Pattern.compile("~~(?=\\S)([\\s\\S]*?\\S)~~");
    /** 图片/链接：![文字](地址) / [文字](地址)。 */
    private static final Pattern IMG = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)\\s]+)[^)]*\\)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\(([^)\\s]+)[^)]*\\)");
    /** 行内代码（只去反引号，留字）。 */
    private static final String TICK = "`";
    /** 三行以上连续换行压成一个空行。 */
    private static final Pattern BLANKS = Pattern.compile("\\n{3,}");

    /**
     * 清洗一段正文。
     *
     * @param text 原文（可为 null）
     * @return 纯文字（null ⇒ 空串）；本来就干净时<b>逐字节返回原文</b>
     */
    public static String clean(String text) {
        return run(text, true);
    }

    /**
     * 流式分片用：与 {@link #clean} 同一条口径，但<b>不动首尾空白</b> ——
     * 控制台是"来一批打一批"，分片之间靠空白衔接，trim 会把词间的空格吃掉。
     */
    public static String cleanChunk(String text) {
        return run(text, false);
    }

    private static String run(String text, boolean trim) {
        if (text == null) return "";
        if (text.isEmpty()) return text;
        try {
            // 快路：一处"像格式"的痕迹都没有 ⇒ 逐字节原样返回（绝大多数消息走这条）
            if (!needs(text)) return text;
            String s = text.replace("\r\n", "\n").replace('\r', '\n');
            s = s.replaceAll("(?i)<br\\s*/?>", "\n");
            StringBuilder out = new StringBuilder(s.length());
            String[] lines = s.split("\n", -1);
            // 全角竖线「｜」当表格分隔的写法（实测她会这么排）：整段里出现两处以上才算表格，
            // 免得把正常的单个「｜」内容也吃掉（两列表格每行只有一处，所以按整段计数）。
            boolean fullTable = count(s, '｜') >= 2;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                if (FENCE.matcher(line).matches()) continue;
                if (HR.matcher(line).matches()) continue;
                if (TABLE_SEP.matcher(line).matches()) continue;
                line = H.matcher(line).replaceFirst("");
                line = QUOTE.matcher(line).replaceFirst("");
                line = BULLET.matcher(line).replaceFirst("");
                line = ORDERED.matcher(line).replaceFirst("$1$2、");
                if (line.indexOf('|') >= 0) {
                    line = line.replaceAll("\\s*\\|\\s*", " ").replaceAll("\\s{2,}", " ").trim();
                }
                // 全角竖线当表格分隔（整段两处以上才动它）
                if (fullTable && line.indexOf('｜') >= 0) {
                    line = line.replaceAll("\\s*｜\\s*", " ").replaceAll("\\s{2,}", " ").trim();
                }
                if (line.indexOf(TICK) >= 0) line = line.replace(TICK, "");
                if (out.length() > 0) out.append('\n');
                out.append(line);
            }
            s = out.toString();
            s = IMG.matcher(s).replaceAll("$1（$2）");
            s = LINK.matcher(s).replaceAll("$1（$2）");
            s = BOLD2.matcher(s).replaceAll("$1");
            s = BOLD2U.matcher(s).replaceAll("$1");
            s = EM1.matcher(s).replaceAll("$1");
            s = EM1U.matcher(s).replaceAll("$1");
            s = STRIKE.matcher(s).replaceAll("$1");
            s = BLANKS.matcher(s).replaceAll("\n\n");
            if (!trim) {
                // 分片模式：标记可能被劈成两批（开头一个 **、下一批才闭合），成对规则抓不住 ⇒
                // 分片里成串的标记符号一律去掉（这些符号在她的聊天正文里本来就没有正当用途）。
                s = s.replaceAll("\\*{2,}", "").replaceAll("~{2,}", "").replace(TICK, "");
                return s;
            }
            String[] tail = s.split("\n", -1);
            StringBuilder fix = new StringBuilder(s.length());
            for (int i = 0; i < tail.length; i++) {
                if (i > 0) fix.append('\n');
                fix.append(rtrim(tail[i]));
            }
            return fix.toString().trim();
        } catch (Throwable t) {
            return text;          // 清洗失败 = 原样发（绝不因为格式清洗把一条消息弄丢）
        }
    }

    /**
     * 这段文字里有没有"可能要看一眼"的格式痕迹（没有就直接原样返回）。
     * <p>判据刻意宽一点：**宁可多跑一遍清洗，也不要漏掉一个标记** —— 漏了就是用户看到
     * 一串裸符号（那正是主人要禁的东西）。</p>
     */
    private static final Pattern SUSPECT_LINE = Pattern.compile("(?m)^[ \\t]{0,3}(?:[-*+#>]|\\d{1,3}[.)])\\s");

    private static boolean needs(String s) {
        if (s.indexOf('*') >= 0 || s.indexOf('_') >= 0 || s.indexOf('`') >= 0 || s.indexOf('|') >= 0
                || s.indexOf('[') >= 0 || s.indexOf('<') >= 0 || s.indexOf('~') >= 0
                || s.indexOf('#') >= 0 || s.indexOf('>') >= 0 || s.indexOf('-') >= 0
                || s.indexOf('｜') >= 0) return true;
        if (s.indexOf("\n\n") >= 0 || s.indexOf("  ") >= 0 || s.indexOf('\t') >= 0) return true;
        return SUSPECT_LINE.matcher(s).find();
    }

    /** 一行里某个字符出现几次（全角竖线表格用）。 */
    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) n++;
        }
        return n;
    }

    private static String rtrim(String s) {
        int n = s.length();
        while (n > 0) {
            char c = s.charAt(n - 1);
            if (c == ' ' || c == '\t') n--;
            else break;
        }
        return n == s.length() ? s : s.substring(0, n);
    }
}
