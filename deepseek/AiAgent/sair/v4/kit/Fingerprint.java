package sair.v4.kit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文本指纹 —— 「近似重复」判定的轻量相似度工具（基板唯一一份，技能共用）。
 *
 * <h3>为什么不复用全文检索的切词</h3>
 * <p>FTS 的 trigram 口径必须丢掉长度 &lt; 3 的查询项，但「参数 / 价格 / 规格 / 配队」这类 2 字词
 * 恰恰是判断"两句话是不是在说同一件事"的<b>关键区分词</b>：丢了它们，指纹就退化成"都在讲显卡"。
 * 所以指纹另用一套切词：<b>中文 2-gram 滑窗 + 小写英数词（≥2）</b>，见 {@link #bigram}。</p>
 *
 * <h3>两种度量，对应两种数据形状</h3>
 * <ul>
 *   <li>{@link #minOverlap} 包含度（交集 / 较小集合）：适合<b>短描述型</b>数据（技能名 + 一句话描述）：
 *       "短的那条完全被长的那条覆盖"就该算重复。</li>
 *   <li>{@link #jaccard} 对称 Jaccard（交集 / 并集）：适合<b>长正文 / 模板化</b>数据（笔记正文是搜索结果堆，
 *       同主题不同问题的实体词高度重合）。此时包含度会把"同主题不同问题"误判成重复，
 *       所以笔记这类数据要用对称度量，而且<b>只用标题做指纹</b>（标题即身份）。</li>
 * </ul>
 *
 * <p>阈值不在这里：{@link #near} 的 {@code threshold} 由调用方从自己的 {@code prompt.md} 读
 * （V3 的口径是笔记标题 0.85、技能经验 0.6）。本类<b>不产任何面向用户/模型的文案</b>。</p>
 */
public final class Fingerprint {

    private Fingerprint() {}

    /** 中文 2-gram + 小写英数词（≥2）的指纹集合。 */
    public static Set<String> bigram(String text) {
        Set<String> out = new HashSet<String>();
        if (Str.blank(text)) return out;
        String s = Str.lower(text);
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
                    if (run.length() == 1) out.add(run);             // 单字也留一个（"猫" vs "猫粮"）
                    for (int k = 0; k + 2 <= run.length(); k++) out.add(run.substring(k, k + 2));
                } else if (run.length() >= 2) {
                    out.add(run);
                }
                i = j;
            }
        }
        return out;
    }

    /** 中文 3-gram + 英数词（≥4，或含数字的 3 字符）的指纹集合（更严，用于短标题的粗筛）。 */
    public static Set<String> trigram(String text) {
        Set<String> out = new HashSet<String>();
        if (Str.blank(text)) return out;
        String s = Str.lower(text);
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
                    for (int k = 0; k + 3 <= run.length(); k++) out.add(run.substring(k, k + 3));
                } else if (run.length() >= 4 || (run.length() == 3 && hasDigit(run))) {
                    out.add(run);
                }
                i = j;
            }
        }
        return out;
    }

    /** 包含度：交集 / 较小集合大小。空集返回 0。 */
    public static double minOverlap(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0D;
        int inter = 0;
        for (String t : a) if (b.contains(t)) inter++;
        return (double) inter / (double) Math.min(a.size(), b.size());
    }

    /** 对称 Jaccard：交集 / 并集。空集返回 0。 */
    public static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0.0D;
        int inter = 0;
        for (String t : a) if (b.contains(t)) inter++;
        int union = a.size() + b.size() - inter;
        return union <= 0 ? 0.0D : (double) inter / (double) union;
    }

    /**
     * 两段文本的相似度（2-gram 指纹）。
     *
     * @param symmetric true = Jaccard（长正文/模板化数据）；false = 包含度（短描述型数据）
     */
    public static double score(String a, String b, boolean symmetric) {
        Set<String> fa = bigram(a);
        Set<String> fb = bigram(b);
        return symmetric ? jaccard(fa, fb) : minOverlap(fa, fb);
    }

    /**
     * 是否算「近似重复」。
     *
     * @param threshold 阈值来自调用方的 {@code prompt.md}（V3 口径：笔记标题 0.85 / 技能经验 0.6）
     */
    public static boolean near(String a, String b, double threshold) {
        return score(a, b, true) >= threshold;
    }

    /**
     * 判定并给出分数（一次算完两份指纹，避免重复切词）。
     *
     * @return 相似度 0..1（对称 Jaccard）
     */
    public static double titleScore(String a, String b) {
        return jaccard(bigram(a), bigram(b));
    }

    /**
     * 归一化：去首尾空白、压掉所有空白，便于"同一句话写法不同"的比较。
     * <p>注意它<b>不</b>做同义替换、不剥前缀 —— 剥前缀由 {@link #stripPrefixes} 按调用方给的清单做。</p>
     */
    public static String norm(String text) {
        if (text == null) return "";
        return text.replaceAll("\\s+", "").trim();
    }

    /**
     * 按调用方给的清单剥掉标题前缀（V3 会剥 {@code [联网搜索]} / {@code [网页抓取]} / {@code [搜索]}）。
     * <p>前缀清单属于<b>各技能自己的口径</b>，所以是从技能 {@code prompt.md} 读出来传进来的，
     * 不是写死在本类里。</p>
     */
    public static String stripPrefixes(String title, List<String> prefixes) {
        if (title == null) return "";
        String t = title.trim();
        if (prefixes == null || prefixes.isEmpty()) return t;
        boolean again = true;
        while (again) {
            again = false;
            for (String p : prefixes) {
                if (Str.blank(p)) continue;
                if (t.startsWith(p)) {
                    t = t.substring(p.length()).trim();
                    again = true;
                }
            }
        }
        return t;
    }

    /** 指纹是否够"实"（太短的文本不该走相似判定：两三个字什么都能撞上）。 */
    public static boolean enough(Set<String> fp, int minTerms) {
        return fp != null && fp.size() >= (minTerms > 0 ? minTerms : 2);
    }

    /** 把候选前缀清单从逗号分隔的配置值拆出来。 */
    public static List<String> prefixes(String raw) {
        List<String> out = new ArrayList<String>();
        if (Str.blank(raw)) return out;
        for (String p : raw.split("[,，]+")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static boolean hasDigit(String s) {
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
