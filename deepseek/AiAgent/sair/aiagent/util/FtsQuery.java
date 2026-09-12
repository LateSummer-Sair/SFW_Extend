package sair.aiagent.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * FTS5 查询构造器 —— 面向 <b>trigram 分词器</b> 的中文检索适配。
 *
 * <h3>背景</h3>
 * SQLite FTS5 默认分词器 {@code unicode61} 会把一整段连续中文当成<b>一个 token</b>，
 * 于是 {@code MATCH} 只能命中"整段完全相同"的文档：实测 35.9 万条群聊记录里，
 * 查询「文件」FTS 命中 2 条，而真实子串匹配有 352 条（召回 0.6%）。
 * 改用 {@code tokenize='trigram'} 后可做子串匹配，实测召回与 LIKE 一致，
 * 且耗时从 ~244ms（全表扫描）降到 ~1ms（走索引）。
 *
 * <h3>本类的职责</h3>
 * <ol>
 *   <li>{@link #clean} —— 去掉 URL、CQ 码、图片标记等噪音。若不去掉，URL 里的
 *       {@code http}/{@code com}/{@code jpg} 会被切成 3-gram，产生数万条无关命中。</li>
 *   <li>{@link #build} —— 把清洗后的文本构造为「中文 3-gram + 长英数词」的 OR 查询，
 *       整词加双引号转义，因此可承受任意标点（现状的转义遗漏 {@code [ ] / . = & %} 等，
 *       会导致 37% 的真实消息抛 {@code fts5: syntax error}）。</li>
 * </ol>
 *
 * <h3>重要限制</h3>
 * trigram 分词器要求查询项长度 ≥ 3 个字符，<b>2 字及以下的查询必须回退 LIKE</b>；
 * {@link #build} 对这种情况返回 {@code null}，调用方据此走 LIKE 兜底。
 */
public final class FtsQuery {

    private FtsQuery() {}

    /** 最多保留的查询项数（过多 OR 项会稀释 BM25 权重并拖慢排序）。 */
    public static final int DEFAULT_MAX_TERMS = 8;

    /**
     * 清洗查询文本：去 URL / CQ 码 / 图片标记，仅保留中英文与数字（其余转空格作分隔）。
     */
    public static String clean(String raw) {
        if (raw == null) return "";
        String s = raw;
        s = s.replaceAll("https?://\\S+", " ");          // URL（含腾讯多媒体长链）
        s = s.replaceAll("\\[包含图片:[^\\]]*\\]", " ");   // 历史里注入的图片标记
        s = s.replaceAll("\\[CQ:[^\\]]*\\]", " ");        // 残留 CQ 码
        s = s.replaceAll("图片\\s*\\d+\\s*:.*", " ");      // 图片URL行
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        return sb.toString().trim().replaceAll("\\s{2,}", " ");
    }

    /** 是否为 CJK（中/日/韩）字符。 */
    private static boolean isCjk(char c) {
        return (c >= 0x2E80 && c <= 0x2EFF) || (c >= 0x3000 && c <= 0x303F)
            || (c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)
            || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)
            || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xAC00 && c <= 0xD7AF);
    }

    /** 是否含数字（用于判定 3 字符英数词是否值得保留，如 403 / mp3 / 4k）。 */
    private static boolean containsDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    /**
     * 提取查询项（去重、保序）。同时被用作「文本指纹」：比较两个文本的项集合重叠度
     * 即可判定它们是否在说同一件事（近似去重）。
     */
    public static List<String> terms(String raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String s = clean(raw);
        if (s.isEmpty()) return new ArrayList<>();
        for (String word : s.split("\\s+")) {
            if (word.isEmpty()) continue;
            // 按「中文段 / 英数段」切开，绝不跨脚本取滑窗。
            // 旧实现在混合串上直接滑窗（如「帮我找一下D盘share里的mp3音乐文件」整串是一个 word），
            // 会产出 "一下D"、"D盘s"、"盘sh"、"shar"、"hare" 这类永远不可能命中的垃圾项；
            // 而 DEFAULT_MAX_TERMS=8 是均匀采样，垃圾项会把真正有用的词挤出查询 ——
            // 消息越长、越中英混杂，召回越差（这类消息在这套部署里恰恰最常见）。
            int i = 0;
            final int n = word.length();
            while (i < n) {
                boolean cjk = isCjk(word.charAt(i));
                int j = i;
                while (j < n && isCjk(word.charAt(j)) == cjk) j++;
                String run = word.substring(i, j);
                if (cjk) {
                    if (run.length() >= 3) {
                        for (int k = 0; k + 3 <= run.length(); k++) {
                            out.add(run.substring(k, k + 3));
                        }
                    }
                } else if (run.length() >= 4 || (run.length() == 3 && containsDigit(run))) {
                    // 英数词保留整词。长度门槛分两档：
                    //  · 含数字的 3 字符词（403/404/500/mp3/4k/6g）信息量很高，必须保留 ——
                    //    实测技能库里有 900 条经验在讲「HTTP 403 怎么办」，而 "403" 恰好是 3 字符，
                    //    按旧的 ≥4 规则会被整个丢掉，导致「官网403打不开怎么办」这类查询 0 命中；
                    //  · 纯字母仍要求 ≥4，避免 the/and/for 这类虚词污染 OR 列表。
                    out.add(run.toLowerCase());
                }
                i = j;
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 清洗后是否具备足够的检索长度（≥3 个 CJK 字符，或存在 ≥4 字符的英数词）。
     * <p>trigram 无法处理更短的查询，调用方应改用 LIKE。</p>
     */
    public static boolean trigramUsable(String raw) {
        return !terms(raw).isEmpty();
    }

    /**
     * 构造 FTS5 MATCH 表达式：{@code "词1" OR "词2" OR …}（每项加双引号转义，任意标点安全）。
     *
     * @param raw      原始查询（通常是整条用户消息）
     * @param maxTerms 最多保留的项数（≤0 时取 {@link #DEFAULT_MAX_TERMS}）
     * @return MATCH 表达式；无法用于 trigram（如纯 2 字中文）时返回 {@code null}
     */
    public static String build(String raw, int maxTerms) {
        int cap = maxTerms > 0 ? maxTerms : DEFAULT_MAX_TERMS;
        List<String> list = terms(raw);
        if (list.isEmpty()) return null;
        if (list.size() > cap) {
            // 均匀采样：避免只取到查询开头或结尾（旧实现 subList(size-16) 只保留尾部，丢失前半段召回）
            List<String> sampled = new ArrayList<>(cap);
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

    /** 整串作为一个 FTS5 短语（内部双引号翻倍转义）——可承受任意标点，不再抛 syntax error。 */
    public static String phrase(String q) {
        if (q == null) return "\"\"";
        return "\"" + q.replace("\"", "\"\"") + "\"";
    }

    /** 转义 LIKE 的通配符（配合 ESCAPE '\' 使用），避免用户输入 % _ 被当通配符。 */
    public static String likeEscape(String q) {
        if (q == null) return "";
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
