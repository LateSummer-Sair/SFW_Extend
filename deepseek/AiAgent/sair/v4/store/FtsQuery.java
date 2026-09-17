package sair.v4.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * FTS5 查询构造器（<b>拆词 OR 口径</b>）—— 面向 {@code trigram} 分词器的中文检索适配。
 *
 * <h3>为什么需要它</h3>
 * <p>{@code Store.search} 的默认口径是"把用户给的整串包成一个 FTS5 短语"。对单个词（子串）没问题，
 * 但对<b>多词 / 带空格的自然语言查询</b>（"AI Agent 记忆"、"服务器 端口 连接失败"）：
 * 短语要求这几个词在原文里<b>连续且同序</b>出现，于是 FTS 命中 0；随后的整串 {@code LIKE} 也几乎必然 0
 * —— 用户/模型看到的是"知识库/群聊里没有这条内容"，而实际语料里有几百条相关记录。</p>
 *
 * <p>对拍实测（{@code data/v4.db}，368,775 行群聊）：8 条多词查询里 6 条 V4 实得 0 行，
 * 而拆词 OR 能召回 1–569 行。所以 {@code Store.search} 在"短语 0 命中"之后再走一步拆词 OR，
 * 最后才落到 {@code LIKE}（三层：短语 → 拆词 OR → LIKE）。</p>
 *
 * <h3>切词口径（与 V3 {@code util/FtsQuery} 一致）</h3>
 * <ol>
 *   <li>{@link #clean} —— 去掉 URL / CQ 码 / 图片标记（否则 URL 里的 {@code http}/{@code com}/{@code jpg}
 *       会被切成 3-gram，产生数万条无关命中）</li>
 *   <li>{@link #terms} —— 中文取 <b>3-gram 滑窗</b>；英数词保留整词（≥4 字符，或含数字的 3 字符，
 *       如 {@code 403}/{@code mp3}）。trigram 分词器要求查询项 ≥3 字符，2 字中文词只能交给 LIKE</li>
 *   <li>{@link #build} —— {@code "词1" OR "词2" …}（每项加双引号转义，可承受任意标点）；
 *       超过 {@link #DEFAULT_MAX_TERMS} 项时<b>均匀采样</b>，避免只取到查询的头或尾</li>
 * </ol>
 */
public final class FtsQuery {

    private FtsQuery() {}

    /** 最多保留的查询项数（过多 OR 项会稀释 BM25 权重并拖慢排序）。 */
    public static final int DEFAULT_MAX_TERMS = 8;

    /** 清洗查询文本：去 URL / CQ 码 / 图片标记，仅保留中英文与数字（其余转空格作分隔）。 */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replaceAll("https?://\\S+", " ");
        s = s.replaceAll("\\[包含图片:[^\\]]*\\]", " ");
        s = s.replaceAll("\\[CQ:[^\\]]*\\]", " ");
        s = s.replaceAll("图片\\s*\\d+\\s*:.*", " ");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return sb.toString().trim().replaceAll("\\s{2,}", " ");
    }

    /** 提取查询项（去重、保序）。 */
    public static List<String> terms(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<String>();
        String s = clean(raw);
        if (s.isEmpty()) return new ArrayList<String>();
        for (String word : s.split("\\s+")) {
            if (word.isEmpty()) continue;
            // 按「中文段 / 英数段」切开，绝不跨脚本取滑窗（跨脚本会产出 "一下D"、"D盘s" 这类永远命不中的垃圾项）
            int i = 0;
            final int n = word.length();
            while (i < n) {
                boolean cjk = isCjk(word.charAt(i));
                int j = i;
                while (j < n && isCjk(word.charAt(j)) == cjk) j++;
                String run = word.substring(i, j);
                if (cjk) {
                    if (run.length() >= 3) {
                        for (int k = 0; k + 3 <= run.length(); k++) out.add(run.substring(k, k + 3));
                    }
                } else if (run.length() >= 4 || (run.length() == 3 && containsDigit(run))) {
                    out.add(run.toLowerCase());
                }
                i = j;
            }
        }
        return new ArrayList<String>(out);
    }

    /** 拆词后是否具备可用的检索项（没有就说明该查询只能走 LIKE）。 */
    public static boolean usable(String raw) {
        return !terms(raw).isEmpty();
    }

    /**
     * 查询里有没有中文。
     *
     * <p>{@link Store#search} 只用它决定"要不要启用拆词 OR 这一层"：拆词 OR 的目的是修
     * <b>中文</b>的召回（一个连续中文串在 FTS 里是一个 token，短语口径几乎必然 0 命中），
     * 代价是通用词会带来误召回。纯 ASCII 查询没有这个问题（{@code LIKE} 的子串口径本来就是对的），
     * 所以不启用 —— 否则 {@code "ZZZ-NOPE-PROBE"} 这种查询会因为 {@code nope}/{@code probe}
     * 两个通用词召回一堆无关记录（实测：correct.list 的"查不到就不编内容"断言就是被这种情况打红的）。</p>
     */
    public static boolean hasCjk(String raw) {
        if (raw == null) return false;
        for (int i = 0; i < raw.length(); i++) if (isCjk(raw.charAt(i))) return true;
        return false;
    }

    /**
     * 构造拆词 OR 表达式：{@code "词1" OR "词2" …}。
     *
     * @param raw      原始查询
     * @param maxTerms 最多保留的项数（≤0 取 {@link #DEFAULT_MAX_TERMS}）
     * @return MATCH 表达式；无可用项时返回 {@code null}
     */
    public static String build(String raw, int maxTerms) {
        List<String> list = terms(raw);
        if (list.isEmpty()) return null;
        int cap = maxTerms > 0 ? maxTerms : DEFAULT_MAX_TERMS;
        if (list.size() > cap) {
            List<String> sampled = new ArrayList<String>(cap);
            double step = (double) list.size() / cap;
            for (int i = 0; i < cap; i++) {
                sampled.add(list.get(Math.min(list.size() - 1, (int) (i * step))));
            }
            list = sampled;
        }
        StringBuilder sb = new StringBuilder(list.size() * 12);
        for (String t : list) {
            if (sb.length() > 0) sb.append(" OR ");
            sb.append(phrase(t));
        }
        return sb.toString();
    }

    /** 整串作为一个 FTS5 短语（内部双引号翻倍转义）——可承受任意标点，不抛 syntax error。 */
    public static String phrase(String q) {
        if (q == null) return "\"\"";
        return "\"" + q.replace("\"", "\"\"") + "\"";
    }

    /** 转义 LIKE 的通配符（配合 {@code ESCAPE '\'} 使用）。 */
    public static String likeEscape(String q) {
        if (q == null) return "";
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x2E80 && c <= 0x2EFF) || (c >= 0x3000 && c <= 0x303F)
                || (c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)
                || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xAC00 && c <= 0xD7AF);
    }
}
