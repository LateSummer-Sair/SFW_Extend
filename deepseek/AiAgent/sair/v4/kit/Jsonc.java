package sair.v4.kit;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * JSONC 读盘管线：<b>IO 流 → 过滤器 → 解析器</b>（权限表 v6 的语法底座）。
 *
 * <p>过滤器只做四件事，其余一个字都不动地交给解析器：</p>
 * <ol>
 *   <li><b>吃掉 BOM</b>：文件最开头的 U+FEFF 不喂解析器（{@link Fs#read} 已经去一次，这里再去一次，
 *       免得换一个读取口就漏进来）；</li>
 *   <li><b>丢注释</b>：<b>独占一行</b>、以 {@code //}（或 {@code #}）开头的整行不喂解析器 ——
 *       沿用主人定的口径：注释写在被注内容的<b>前一行</b>，内容里不放中文；</li>
 *   <li><b>键数组规范成一个字符串键</b>：键位置遇到 {@code [}，<b>认得字符串地</b>配对到 {@code ]}
 *       且紧跟 {@code :} ⇒ 判为键数组，元素之间用 {@link #SEP}（U+001F）连成<b>一个</b>字符串键；
 *       值位置的 {@code [}（前面是 {@code :}）一个字都不碰；</li>
 *   <li><b>重复键改名</b>：同一个对象里同一个键写了两遍时（"同一个键出现多处取并集"的写法），
 *       Gson 的 {@code JsonObject} 是"后者覆盖前者"—— 后一条被改名成
 *       {@code \u0002<序号>\u0003<原键>}，读取端用 {@link #undup(String)} 还原，并集才成立。</li>
 * </ol>
 *
 * <p><b>为什么必须"认得字符串"</b>：规则层的键是 op / 数据类别 / <b>路径</b>。路径里会出现
 * {@code "}、{@code \}、{@code ,}、{@code |}、{@code ]}、空格（{@code ["D:/a,b|c]d"]}），
 * 不认字符串就会把配对括号数错，把一条规则拆成两条谁都认不出的垃圾。权限表宁可拒，也绝不能拆错 ——
 * 这条判定面向的是"谁能改数据"，拆错一条就是错放一条。</p>
 *
 * <p><b>键里真出现 U+001F</b>（作者写了裸控制字符，或者写了 {@code \\u001f} 转义）⇒ 这个键拆不干净了：
 * 过滤器把"第几行"记进 {@link Filtered#problems}，并且<b>一个字都不改</b>（原文里的 U+001F 照旧留着，
 * {@link #read(File)} 返回的还是原文，谁都能看见它）；{@link #obj(File)} 则<b>直接抛错</b>并带上行号 ——
 * 规格 §1.4 是"报错并点名那一行，宁可拒不可猜"，绝不许静默丢键。</p>
 *
 * <p>本类<b>不持有任何跨装载状态</b>（权限表是热重载的）：{@link #filter(File)} 的产物由调用方拿着，
 * 需要点名就把 {@link Filtered#problems} 转成告警。</p>
 */
public final class Jsonc {

    private Jsonc() {}

    /** 多键规则的元素连接符：键数组在解析前被规范成"一个字符串键"；读取端按它拆开。 */
    public static final char SEP = '\u001F';

    /**
     * 重复键的改名前缀（U+0002）：同一个对象里同一个键写了两遍时，后一条被改名成
     * {@code \u0002<序号>\u0003<原键>}。读取端用 {@link #undup(String)} 还原成原键。
     */
    public static final char DUP = '\u0002';

    /** 重复键改名里"原键从这里开始"的分隔符（U+0003）。 */
    public static final char DUP_END = '\u0003';

    private static final String DUP_ESC = "\\u0002";
    private static final String DUP_END_ESC = "\\u0003";
    private static final String SEP_ESC = "\\u001f";

    /** 过滤结果：{@link #text} 可以直接喂 Gson；{@link #problems} 是行号级的问题（点名用）。 */
    public static final class Filtered {
        /** 过滤后的文本（BOM 已吃、注释已丢、键数组已规范成 {@link #SEP} 连接的单键）。 */
        public final String text;
        /** 每条形如 {@code 第 12 行：…}；空表 = 这一遍没发现问题。 */
        public final List<String> problems;

        Filtered(String text, List<String> problems) {
            this.text = text;
            this.problems = problems;
        }
    }

    /**
     * 读盘（过滤 + 解析）。
     *
     * <p>文件不在 / 读不出 / 不是 JSON 对象 ⇒ {@code null}（调用方按"这一份空处理 + 点名文件"办）。
     * <b>过滤期发现问题（键里带 U+001F）⇒ 抛 {@link IllegalArgumentException}，消息里带行号</b> ——
     * 规格 §1.4：宁可拒，不猜；绝不把这种键当成正常键返回，也绝不静默丢。</p>
     */
    public static JsonObject obj(File f) {
        Filtered fd = filter(f);
        if (!fd.problems.isEmpty()) {
            throw new IllegalArgumentException(f == null ? "" : (f.getName() + " ")
                    + fd.problems.get(0) + "（这一份按坏文件处理）");
        }
        return J.obj(fd.text);
    }

    /** 过滤后的文本（不含注释；键数组已规范成 U+001F 连接的单键）。 */
    public static String read(File f) { return filter(f).text; }

    /** 按 {@link #SEP} 拆开一个键；不含 {@link #SEP} 的单键 = 单元素表（单元素与裸键等价）。 */
    public static List<String> splitKeys(String key) {
        List<String> out = new ArrayList<String>();
        if (key == null) return out;
        int start = 0;
        for (int i = 0; i <= key.length(); i++) {
            if (i == key.length() || key.charAt(i) == SEP) {
                out.add(key.substring(start, i));
                start = i + 1;
            }
        }
        return out;
    }

    /**
     * 还原"重复键"改名（过滤器干的）：{@code \u0002<序号>\u0003<原键>} → {@code <原键>}；
     * 不是重复键的原样返回。读取端解析出对象之后、用键之前都要过一遍。
     */
    public static String undup(String key) {
        if (key == null || key.isEmpty()) return key;
        if (key.charAt(0) != DUP) return key;
        int e = key.indexOf(DUP_END, 1);
        if (e < 0) return key;
        return key.substring(e + 1);
    }

    /** 过滤（读盘管线的前半段）：BOM → 丢独占一行的注释 → 键数组规范 + 重复键改名。 */
    public static Filtered filter(File f) {
        List<String> problems = new ArrayList<String>();
        String raw = Fs.read(f, "");
        if (raw.length() > 0 && raw.charAt(0) == '\uFEFF') raw = raw.substring(1);
        return new Filtered(fold(stripCommentLines(raw), problems), problems);
    }

    // ==================== 过滤器：丢注释 ====================

    /**
     * 去掉<b>独占一行</b>的注释（整行 trim 后以 {@code //} 或 {@code #} 开头），其余原样。
     * <p>只认独占一行的注释是主人的口径（注释不跟内容同行）—— 这样行内的 {@code //}（比如路径里的）
     * 不会被误吃。</p>
     */
    private static String stripCommentLines(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("#")) continue;
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    // ==================== 过滤器：键数组 / 重复键 ====================

    /**
     * 扫一遍文本，做两件规范化：
     * <ol>
     *   <li>键位置的 {@code [..]}: → {@code "e1\u001Fe2"}；</li>
     *   <li>同一个对象里重复出现的键 → 改名（{@link #DUP} + 序号 + {@link #DUP_END} + 原键）。</li>
     * </ol>
     * <p>只认键位置（行首 / {@code {} 之后 / {@code ,} 之后，且最内层容器是<b>对象</b>）；
     * 值位置的 {@code [} 原样保留。配对时认字符串（{@code \\" } 转义算在内）。</p>
     */
    private static String fold(String text, List<String> problems) {
        StringBuilder out = new StringBuilder(text.length() + 32);
        ArrayList<Character> kinds = new ArrayList<Character>();                   // 容器栈：'{' / '['
        ArrayList<LinkedHashSet<String>> seen = new ArrayList<LinkedHashSet<String>>();  // 每个对象里出现过的键
        int n = text.length();
        int i = 0;
        int line = 1;
        int dupSeq = 0;
        char last = 0;                       // 字符串外最后一个"有意义的"字符
        boolean inStr = false;
        while (i < n) {
            char c = text.charAt(i);
            if (inStr) {
                out.append(c);
                if (c == '\\' && i + 1 < n) {
                    char d = text.charAt(i + 1);
                    out.append(d);
                    if (d == '\n') line++;
                    i += 2;
                    continue;
                }
                if (c == '"') inStr = false;
                else if (c == '\n') line++;
                i++;
                continue;
            }
            if (c == '"') {
                boolean keyPos = keyPosition(last, kinds);
                int[] end = new int[1];
                int[] lines = new int[1];
                String body = readStringRaw(text, i, end, lines);
                if (body == null) {                       // 引号没闭合：剩下的原样交出去，让解析器报错
                    out.append(text, i, n);
                    break;
                }
                int startLine = line;
                line += lines[0];
                if (keyPos && hasSep(body)) {
                    // 拆不干净的键：只点名那一行，原文一个字都不改（read() 必须还看得见 U+001F）
                    problems.add(sepProblem(startLine));
                    out.append(text, i, end[0]);
                } else if (keyPos && isDup(seen, body)) {
                    dupSeq++;
                    out.append('"').append(DUP_ESC).append(dupSeq).append(DUP_END_ESC).append(body).append('"');
                } else {
                    out.append(text, i, end[0]);
                    if (keyPos) remember(seen, body);
                }
                i = end[0];
                last = '"';
                continue;
            }
            if (c == '[' && keyPosition(last, kinds)) {
                int[] end = new int[1];
                List<String> elems = readKeyArray(text, i, end);
                if (elems != null) {
                    String span = text.substring(i, end[0]);
                    int startLine = line;
                    line += countLines(span);
                    String emitted = joinSep(elems);
                    String canonical = joinRaw(elems);
                    boolean bad = false;
                    for (int k = 0; k < elems.size(); k++) if (hasSep(elems.get(k))) bad = true;
                    if (bad) {
                        // 同上：点名行号，键原文照旧交出去（不投毒、不改写）
                        problems.add(sepProblem(startLine));
                    }
                    out.append('"');
                    if (isDup(seen, canonical)) {
                        dupSeq++;
                        out.append(DUP_ESC).append(dupSeq).append(DUP_END_ESC).append(emitted);
                    } else {
                        out.append(emitted);
                        remember(seen, canonical);
                    }
                    out.append('"');
                    i = end[0];
                    last = '"';
                    continue;
                }
            }
            if (c == '\n') {
                out.append(c);
                line++;
                i++;
                continue;                                        // 换行不改变 last：键数组可以跨行
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                out.append(c);
                i++;
                continue;
            }
            out.append(c);
            if (c == '{' || c == '[') {
                kinds.add(Character.valueOf(c));
                seen.add(new LinkedHashSet<String>());
            } else if (c == '}' || c == ']') {
                if (!kinds.isEmpty()) {
                    kinds.remove(kinds.size() - 1);
                    seen.remove(seen.size() - 1);
                }
            }
            last = c;
            i++;
        }
        return out.toString();
    }

    private static String sepProblem(int line) {
        return "第 " + line + " 行：规则键里出现了 U+001F（多键的连接符）"
                + "—— 这个键拆不干净，不生效（宁可拒，不猜）";
    }

    /** 这个位置是不是"键位置"：行首 / {@code {} 之后 / {@code ,} 之后，且最内层容器是对象。 */
    private static boolean keyPosition(char last, List<Character> kinds) {
        if (!(last == 0 || last == '{' || last == ',')) return false;
        return kinds.isEmpty() || kinds.get(kinds.size() - 1).charValue() == '{';
    }

    /** 当前对象里这个键是不是已经出现过（真 = 重复，调用方负责改名）。 */
    private static boolean isDup(List<LinkedHashSet<String>> seen, String key) {
        if (seen.isEmpty()) return false;
        return seen.get(seen.size() - 1).contains(key);
    }

    /** 记下这个键已经出现过（只记当前这一层容器）。 */
    private static void remember(List<LinkedHashSet<String>> seen, String key) {
        if (seen.isEmpty()) return;
        seen.get(seen.size() - 1).add(key);
    }

    /**
     * 试读一个键数组：{@code start} 指向 {@code [}。成功 ⇒ 元素表（原样的字符串内容），
     * {@code end[0]} = {@code ]} 之后的第一个下标（后面的空白与 {@code :} 交给主循环原样输出）；
     * 不是键数组（元素不是字符串、或者 {@code ]} 后面不是 {@code :}）⇒ {@code null}，一切都别碰。
     */
    private static List<String> readKeyArray(String text, int start, int[] end) {
        ArrayList<String> out = new ArrayList<String>();
        int n = text.length();
        int i = start + 1;
        int after = -1;
        while (true) {
            i = skipWs(text, i);
            if (i >= n || text.charAt(i) != '"') return null;
            int[] e = new int[1];
            String body = readStringRaw(text, i, e, null);
            if (body == null) return null;
            out.add(body);
            i = skipWs(text, e[0]);
            if (i >= n) return null;
            char c = text.charAt(i);
            if (c == ',') { i++; continue; }
            if (c == ']') { i++; after = i; break; }
            return null;
        }
        if (out.isEmpty()) return null;                      // [] 不是键数组（空键没有意义）
        int j = skipWs(text, after);
        if (j >= n || text.charAt(j) != ':') return null;    // 后面不是 ':' ⇒ 这不是键位置
        end[0] = after;
        return out;
    }

    /** 跳过空白（含换行）：键数组可以跨行，冒号前也可以换行。 */
    private static int skipWs(String text, int i) {
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') i++;
            else break;
        }
        return i;
    }

    /**
     * 从 {@code start}（指向开引号）读一个 JSON 字符串，<b>原样</b>返回引号之间的文本（转义一个都不解，
     * 因为元素内部的反斜杠要原样留在规范化后的键里，交给解析器解）。
     * {@code end[0]} = 收引号之后的下标；{@code lines[0]} = 串内换行数（行号不能错）。读不完 ⇒ {@code null}。
     */
    private static String readStringRaw(String text, int start, int[] end, int[] lines) {
        int n = text.length();
        int i = start + 1;
        int nl = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\') {
                if (i + 1 < n && text.charAt(i + 1) == '\n') nl++;
                i += 2;
                continue;
            }
            if (c == '"') {
                if (end != null) end[0] = i + 1;
                if (lines != null) lines[0] = nl;
                return text.substring(start + 1, i);
            }
            if (c == '\n') nl++;
            i++;
        }
        return null;
    }

    /** 键数组 → 写进文本的形态（元素原样，之间用 {@code \u001f} 转义连接）。 */
    private static String joinSep(List<String> elems) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) sb.append(SEP_ESC);
            sb.append(elems.get(i));
        }
        return sb.toString();
    }

    /** 键数组 → 去重用的规范形态（元素原样，之间用真的 U+001F 连接）。 */
    private static String joinRaw(List<String> elems) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elems.size(); i++) {
            if (i > 0) sb.append(SEP);
            sb.append(elems.get(i));
        }
        return sb.toString();
    }

    /** 键的原文里是不是已经带了连接符：裸 U+001F，或者 {@code \u001f} 转义（大小写都算）。 */
    private static boolean hasSep(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == SEP) return true;
            if (isSepEscape(raw, i)) return true;
        }
        return false;
    }

    /** {@code raw[i]} 起是不是 {@code \u001f} 这个转义（i 指向反斜杠）。 */
    private static boolean isSepEscape(String raw, int i) {
        if (raw == null || i < 0 || i + 5 >= raw.length()) return false;
        if (raw.charAt(i) != '\\') return false;
        char u = raw.charAt(i + 1);
        if (u != 'u' && u != 'U') return false;
        return "001f".equalsIgnoreCase(raw.substring(i + 2, i + 6));
    }

    private static int countLines(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == '\n') n++;
        return n;
    }
}
