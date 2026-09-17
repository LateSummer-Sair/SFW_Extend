package sair.v4.hot;

import java.util.List;

import sair.v4.kit.Str;

/**
 * 技能声明的工具名 → <b>注册名</b>（必须合 API 规范）。
 *
 * <h3>为什么需要它</h3>
 * <p>function 的 {@code name} 只允许 {@code ^[a-zA-Z0-9_-]+$}（≤64）。技能 md 的 {@code tool:}
 * 是<b>人（或 AI）手写的</b>，很容易写成中文（"发表情包"）——那种名字不是"这个工具不可用"，
 * 而是服务端直接拒<b>整条请求</b>（此后每一轮都失败）。V3 为此做过 {@code ToolNames.sanitize}
 * （有损转写时追加 6 位稳定哈希）。</p>
 *
 * <h3>V4 的策略（与 V3 的差异）</h3>
 * <ul>
 *   <li>合法名（`^[a-zA-Z0-9_-]{1,64}$`）→ <b>原样注册</b>，一个字符都不动；</li>
 *   <li>非法名 → 生成 {@code tp_} + <b>可读 ASCII 段</b>（名字里的 ASCII 字母数字保留）+ <b>8 位稳定哈希</b>；
 *       纯中文名没有 ASCII 段时就是 {@code tp_<8 位哈希>}；</li>
 *   <li>哈希基于原声明名：<b>同一台机器上跨重启稳定</b>（保存的是同一份 md，注册名不变，
 *       模型的"工具名记忆"与提示词里的写法不会隔夜失效）；</li>
 *   <li>撞车（两个技能转写后同名）按<b>确定性</b>规则加 {@code -2} / {@code -3}（技能目录按名排序遍历，
 *       所以谁拿到 2 谁拿到 3 是稳定的）。</li>
 * </ul>
 * <p>文件夹名与 md 名<b>保持中文不变</b>——注册名只是"给模型看的调用名"，人读的还是中文。</p>
 */
public final class ToolNames {

    private ToolNames() {}

    /** 非法名的前缀（与 V3 同名，便于两边对照）。 */
    public static final String PREFIX = "tp_";

    /** API 对 function.name 的要求。 */
    private static final java.util.regex.Pattern LEGAL =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

    /** 名字是否直接可用（不用转写）。 */
    public static boolean legal(String name) {
        return Str.has(name) && LEGAL.matcher(name.trim()).matches();
    }

    /**
     * 声明名 → 注册名。
     * <p>{@code taken} 里已有的名字算撞车，会按确定性后缀（-2、-3…）让位。</p>
     */
    public static String register(String declared, List<String> taken) {
        String base = legal(declared) ? declared.trim() : derived(declared);
        return unique(base, taken);
    }

    /** 非法名 → {@code tp_ + 可读 ASCII 段 + 8 位哈希}（纯中文名只有哈希段）。 */
    public static String derived(String declared) {
        String raw = Str.nz(declared).trim();
        String hash = hash8(raw);
        StringBuilder ascii = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) ascii.append(c);
            else if (c == '_' || c == '-') ascii.append('_');
        }
        String readable = ascii.toString().toLowerCase();
        if (readable.length() > 24) readable = readable.substring(0, 24);
        String body = readable.isEmpty() ? hash : (readable + "_" + hash);
        String name = PREFIX + body;
        return name.length() <= 64 ? name : name.substring(0, 64);
    }

    /** 撞车让位：base、base-2、base-3…（确定性）。 */
    public static String unique(String base, List<String> taken) {
        String b = Str.nz(base).trim();
        if (b.isEmpty()) b = PREFIX + "skill";
        if (taken == null || !contains(taken, b)) return b;
        for (int i = 2; i < 1000; i++) {
            String suffix = "-" + i;
            String cand = b.length() + suffix.length() <= 64 ? b + suffix
                    : b.substring(0, 64 - suffix.length()) + suffix;
            if (!contains(taken, cand)) return cand;
        }
        return b;
    }

    private static boolean contains(List<String> list, String s) {
        for (String x : list) if (x != null && x.equals(s)) return true;
        return false;
    }

    /** 8 位十六进制稳定哈希（FNV-1a 32 位；不受 JVM / 重启影响）。 */
    public static String hash8(String s) {
        String t = Str.nz(s);
        int h = 0x811c9dc5;
        for (int i = 0; i < t.length(); i++) {
            h ^= t.charAt(i);
            h *= 0x01000193;
        }
        String hex = Integer.toHexString(h);
        while (hex.length() < 8) hex = "0" + hex;
        return hex.substring(hex.length() - 8);
    }
}
