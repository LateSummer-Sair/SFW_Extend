package sair.v4.qq;

import java.util.ArrayList;
import java.util.List;

import sair.v4.kit.Str;

/**
 * 回复分段（<b>V3 的"分段发送"口径</b>）：一条太长的回复拆成几条发，读起来才像人。
 *
 * <h3>为什么要有它</h3>
 * <p>模型一口气输出 800 字，塞进一条 QQ 消息里就是一屏刷不到头的长条；V3 的做法是
 * "先按段落拆、再按长度兜底、最后按上限合并"，并且群聊还会把长句再压短（拟人化）。
 * V4 起先把这段逻辑落成了"只按 1200 字符硬切"，句子会被拦腰劈开、段落也丢了 —— 这里补回来。</p>
 *
 * <h3>三段式（与 V3 {@code QQAgentBridge.splitIntoMessages} 一致）</h3>
 * <ol>
 *   <li><b>语义单元</b>：{@code ```} 代码块整体是不可分割的原子单元（围栏一起带走）；
 *       代码块之外按<b>空行</b>（{@code \n\n}）切段落 —— 段落边界就是天然的"换条"位置。</li>
 *   <li><b>按上限合并</b>：依次把单元并进当前这条，合并后会超上限就先发出去；
 *       单元之间用空行连接（保持段落观感）。</li>
 *   <li><b>超长单元</b>：单个段落/代码块本身就超上限时，再按换行 → 句末标点 → 硬切 的顺序拆。</li>
 * </ol>
 *
 * <h3>显式分段</h3>
 * <p>模型可以自己写 {@code <split>} 标记来指定"这里断开"（V3 的约定标记）：有标记就<b>只按标记拆</b>
 * （标记本身在发送前被 {@link MarkerTags} 剥掉），不再按长度兜底切 —— 模型自己分好的段最贴合语气。</p>
 */
public final class Seg {

    private Seg() {
    }

    /** 显式分段标记（V3 约定；大小写不敏感）。 */
    public static final String MARKER = "<split>";

    /**
     * 把一条回复拆成若干条消息。
     *
     * @param text    原文（可含控制标记；调用方负责先剥或后剥）
     * @param maxChar 单条上限（字符数；{@code <=0} = 不限制，返回单条）
     * @return 拆好的消息（已 trim、丢掉空段；原文为空时返回空表）
     */
    public static List<String> messages(String text, int maxChar) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String t = text.trim();
        if (t.isEmpty()) return out;
        int max = maxChar <= 0 ? t.length() : maxChar;
        if (t.length() <= max) {
            out.add(t);
            return out;
        }
        return merge(semanticUnits(t), max);
    }

    /**
     * 按显式标记拆（{@code <split>}），标记不保留；没有标记返回空表（调用方改用 {@link #messages}）。
     */
    public static List<String> byMarker(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String flat = text.toLowerCase();
        if (flat.indexOf(MARKER) < 0) return out;
        String[] parts = text.split("(?i)" + java.util.regex.Pattern.quote(MARKER));
        for (String p : parts) {
            String s = p == null ? "" : p.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /**
     * 语义单元：{@code ```} 代码块原子化，其它按空行段落切。
     */
    public static List<String> semanticUnits(String t) {
        List<String> units = new ArrayList<String>();
        StringBuilder plain = new StringBuilder();
        int i = 0, n = t.length();
        while (i < n) {
            int fence = t.indexOf("```", i);
            if (fence < 0) {
                plain.append(t.substring(i));
                break;
            }
            int close = t.indexOf("```", fence + 3);
            if (close < 0) {                       // 围栏没闭合：当普通文本，别吞掉后半段
                plain.append(t.substring(i));
                break;
            }
            plain.append(t.substring(i, fence));
            appendParagraphs(units, plain.toString());
            plain.setLength(0);
            String block = t.substring(fence, close + 3).trim();
            if (!block.isEmpty()) units.add(block);
            i = close + 3;
        }
        if (plain.length() > 0) appendParagraphs(units, plain.toString());
        return units;
    }

    /** 按空行切段落（连续多个空行也只当一次分隔）。 */
    private static void appendParagraphs(List<String> units, String text) {
        if (text == null) return;
        for (String para : text.split("\\n\\s*\\n")) {
            String p = para.trim();
            if (!p.isEmpty()) units.add(p);
        }
    }

    /** 把单元按上限合并成消息（单元之间用空行连接，保持段落观感）。 */
    public static List<String> merge(List<String> units, int max) {
        List<String> out = new ArrayList<String>();
        if (units == null || units.isEmpty()) return out;
        int limit = Math.max(1, max);
        StringBuilder cur = new StringBuilder();
        for (String unit : units) {
            if (unit == null) continue;
            String u = unit.trim();
            if (u.isEmpty()) continue;
            if (u.length() > limit) {
                if (cur.length() > 0) {
                    out.add(cur.toString().trim());
                    cur.setLength(0);
                }
                for (String piece : splitLongUnit(u, limit)) out.add(piece);
                continue;
            }
            int merged = cur.length() + (cur.length() > 0 ? 2 : 0) + u.length();
            if (merged > limit && cur.length() > 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            }
            if (cur.length() > 0) cur.append("\n\n");
            cur.append(u);
        }
        if (cur.length() > 0) out.add(cur.toString().trim());
        return out;
    }

    /**
     * 单个超长单元再拆：优先换行 → 句末标点（。！？…；!.?）→ 逗号/顿号 → 硬切。
     * <p>切点尽量落在标点<b>之后</b>，避免把句子劈成两半。</p>
     */
    public static List<String> splitLongUnit(String unit, int max) {
        List<String> out = new ArrayList<String>();
        if (unit == null) return out;
        String rest = unit.trim();
        int limit = Math.max(16, max);
        while (rest.length() > limit) {
            int cut = findCut(rest, limit);
            String head = rest.substring(0, cut).trim();
            if (!head.isEmpty()) out.add(head);
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    /** 在 {@code [limit/2, limit]} 区间里找最靠后的"好切点"；找不到就硬切在 limit。 */
    private static int findCut(String s, int limit) {
        int hi = Math.min(limit, s.length() - 1);
        int lo = Math.max(1, limit / 2);
        // ① 换行（最自然的分条点）
        for (int i = hi; i >= lo; i--) {
            if (s.charAt(i) == '\n') return i + 1;
        }
        // ② 句末标点
        for (int i = hi; i >= lo; i--) {
            if (isSentenceEnd(s.charAt(i))) return i + 1;
        }
        // ③ 次级标点（逗号/顿号/分号）
        for (int i = hi; i >= lo; i--) {
            char c = s.charAt(i);
            if (c == '，' || c == '、' || c == '；' || c == ',' || c == ';') return i + 1;
        }
        return hi + 1;
    }

    private static boolean isSentenceEnd(char c) {
        return c == '。' || c == '！' || c == '？' || c == '…' || c == '.' || c == '!' || c == '?';
    }

    /**
     * 群聊拟人化：把"比群聊上限还长"的每一条再压短一档（{@code groupMax<=0} 时原样返回）。
     * <p>V3 的群聊口径是"短句优先"（默认 180），私聊不加这一层。</p>
     */
    public static List<String> humanize(List<String> parts, int groupMax) {
        if (parts == null || parts.isEmpty() || groupMax <= 0) return parts;
        List<String> out = new ArrayList<String>();
        for (String p : parts) {
            if (p == null) continue;
            String s = p.trim();
            if (s.isEmpty()) continue;
            if (s.length() <= groupMax) out.add(s);
            else out.addAll(splitLongUnit(s, groupMax));
        }
        return out;
    }

    /**
     * 整条链路：显式标记优先 → 长度分段 → 群聊压短。
     *
     * @param text     回复正文
     * @param maxChar  单条上限（私聊也用它）
     * @param groupMax 群聊上限（{@code <=0} = 不加这一层）
     * @param group    是不是群会话
     */
    public static List<String> plan(String text, int maxChar, int groupMax, boolean group) {
        List<String> parts = byMarker(text);
        if (parts.isEmpty()) parts = messages(text, maxChar);
        if (group) parts = humanize(parts, groupMax);
        return parts;
    }

    /** 结构化事实（控制台一行，便于排查"为什么发了 N 条"）：条数与每条的长度。 */
    public static String fact(List<String> parts) {
        if (parts == null || parts.isEmpty()) return "parts=0";
        StringBuilder sb = new StringBuilder("parts=").append(parts.size()).append(" lens=");
        int upto = Math.min(parts.size(), 8);
        for (int i = 0; i < upto; i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i) == null ? 0 : parts.get(i).length());
        }
        if (parts.size() > upto) sb.append("/…");
        return sb.toString();
    }

    /** 记忆口径：把显式分段标记换成一个空行（写进聊天记录的文本不该带控制标记）。 */
    public static String forMemory(String text) {
        if (text == null) return "";
        if (Str.blank(text) || text.toLowerCase().indexOf(MARKER) < 0) return text;
        return text.replaceAll("(?i)" + java.util.regex.Pattern.quote(MARKER), "\n\n").trim();
    }
}
