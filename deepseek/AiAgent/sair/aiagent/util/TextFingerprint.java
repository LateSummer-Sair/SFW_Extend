package sair.aiagent.util;

import java.util.HashSet;
import java.util.Set;

/**
 * 文本指纹 —— 用于「近似重复」判定的轻量相似度工具。
 *
 * <h3>为什么不直接用 {@link FtsQuery#terms}</h3>
 * <p>
 * {@code FtsQuery.terms} 是为 <b>trigram FTS 检索</b>服务的：它必须丢弃 2 字中文词
 * （trigram 无法命中 &lt;3 字符的查询项）。但「参数 / 价格 / 规格 / 配队」这类 2 字词
 * 恰恰是判断两句话是否在说同一件事的<b>关键区分词</b>，丢了它们指纹就退化成「都在讲显卡」。
 * 所以指纹用另一套切词：<b>中文 2-gram 滑窗 + 小写英数词(≥2)</b>。
 * </p>
 *
 * <h3>两种度量，对应两种数据形状</h3>
 * <ul>
 *   <li>{@link #minOverlap} 包含度（交集 / 较小集合）：适合<b>短描述型</b>数据
 *       （技能名+一句话描述）。「短的那条完全被长的那条覆盖」就等于重复，这是想要的性质。</li>
 *   <li>{@link #jaccard} 对称 Jaccard（交集 / 并集）：适合<b>长正文 + 模板化</b>数据
 *       （知识库笔记正文是搜索结果堆，同主题的实体词高度重合）。此时包含度会把
 *       「同主题不同问题」判成重复 —— 实测把「原神 诺艾尔 满配 DPS」并进
 *       「原神 桑多涅 配队」，因此笔记改用对称度量，且<b>只用标题做指纹</b>
 *       （标题即查询、即身份；正文是模板化结果，不参与身份判定）。</li>
 * </ul>
 */
public final class TextFingerprint {

    private TextFingerprint() {}

    /** 中文 2-gram + 小写英数词(≥2) 的指纹集合。 */
    public static Set<String> bigram(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isEmpty()) return out;
        String s = text.toLowerCase();
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(Character.isLetterOrDigit(c) ? c : ' ');
        }
        for (String word : sb.toString().trim().split("\\s+")) {
            if (word.isEmpty()) continue;
            int i = 0;
            final int n = word.length();
            while (i < n) {
                boolean cjk = isCjk(word.charAt(i));
                int j = i;
                while (j < n && isCjk(word.charAt(j)) == cjk) j++;
                String run = word.substring(i, j);
                if (cjk) {
                    for (int k = 0; k + 2 <= run.length(); k++) out.add(run.substring(k, k + 2));
                } else if (run.length() >= 2) {
                    out.add(run);
                }
                i = j;
            }
        }
        return out;
    }

    /** 中文 3-gram + 英数词（复用 FTS 查询项提取），技能描述用这套已验证。 */
    public static Set<String> trigram(String text) {
        if (text == null || text.isEmpty()) return new HashSet<>();
        return new HashSet<>(FtsQuery.terms(text));
    }

    /**
     * 归一化知识库笔记标题：剥掉「自动沉淀」前缀（{@code [联网搜索] } / {@code [网页抓取] }）。
     * <p>
     * 为什么不剥不行：自动沉淀的笔记标题长这样 {@code [联网搜索] RTX 5070 Super 参数}，
     * 前缀固定贡献约 5 个中文 bigram（联网/网搜/搜索…），会平白稀释相似度 ——
     * 实测同一条 URL 的笔记只算出 0.77（应该接近 1.0），于是压制不生效。
     * 指纹要比较的是「问题本身」，不是「它从哪来」。
     * </p>
     */
    public static String normalizeNoteTitle(String title) {
        if (title == null) return "";
        String t = title.trim();
        while (true) {
            if (t.startsWith("[联网搜索]")) { t = t.substring("[联网搜索]".length()).trim(); continue; }
            if (t.startsWith("[网页抓取]")) { t = t.substring("[网页抓取]".length()).trim(); continue; }
            if (t.startsWith("[搜索]"))     { t = t.substring("[搜索]".length()).trim(); continue; }
            break;
        }
        return t;
    }

    /** 包含度：交集 / 较小集合大小。 */
    public static double minOverlap(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        for (String t : a) {
            if (b.contains(t)) inter++;
        }
        return (double) inter / Math.min(a.size(), b.size());
    }

    /** 对称 Jaccard：交集 / 并集大小。 */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0;
        int inter = 0;
        for (String t : a) {
            if (b.contains(t)) inter++;
        }
        int union = a.size() + b.size() - inter;
        return union <= 0 ? 0.0 : (double) inter / union;
    }

    private static boolean isCjk(char c) {
        return (c >= 0x2E80 && c <= 0x2EFF) || (c >= 0x3000 && c <= 0x303F)
            || (c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)
            || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0x4E00 && c <= 0x9FFF)
            || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0xAC00 && c <= 0xD7AF);
    }
}
