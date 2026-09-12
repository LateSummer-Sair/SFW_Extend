package sair.aiagent.util;

/**
 * FC 工具名规则 —— DeepSeek / OpenAI 对 {@code tools[].function.name} 有硬性正则要求。
 *
 * <p>
 * <b>为什么必须有这个类</b>：违规时服务端返回的是<b>整条请求 400</b>
 * （{@code Invalid 'tools[i].function.name': string does not match pattern. Expected a string that
 * matches the pattern '^[a-zA-Z0-9_-]+$'}），而不是「这一个工具不可用」——
 * 也就是一个中文名的技能文件会让此后<b>每一条消息</b>都失败。
 * </p>
 *
 * <p>
 * {@link #sanitize} 的两段策略：纯 ASCII 名原样保留（可读）；
 * 发生有损转写（中文名、空格等）时追加原名哈希后缀。后者不只是为了可读性 ——
 * 纯中文名转写后什么都不剩，不加后缀时<b>所有中文技能会退化成同一个工具名</b>，
 * 且同名重复同样会让请求失败。哈希取自 {@link String#hashCode()}（Java 规范固定），
 * 因此跨重启稳定，不会破坏 KV 前缀缓存。
 * </p>
 */
public final class ToolNames {

    private ToolNames() {}

    /** 官方要求的工具名字符集正则。 */
    public static final String LEGAL_PATTERN = "^[a-zA-Z0-9_-]+$";
    /** 官方要求的工具名最大长度。 */
    public static final int MAX_LENGTH = 64;
    /** {@code tp_} 前缀长度（规范化时预留）。 */
    public static final String TP_PREFIX = "tp_";

    /** 是否为合法工具名（字符集 + 长度）。 */
    public static boolean isLegal(String toolName) {
        return toolName != null && !toolName.isEmpty()
                && toolName.length() <= MAX_LENGTH
                && toolName.matches(LEGAL_PATTERN);
    }

    /**
     * 把任意名字规范化为合法工具名（保证返回合法、非空、长度 ≤ {@code 64 - prefixLen}）。
     *
     * @param name      原始名字（技能名或工具名）
     * @param prefixLen 调用方预留的前缀长度（如 {@code "tp_"} 为 3），用于把总长控制在 64 以内
     */
    public static String sanitize(String name, int prefixLen) {
        int maxBase = Math.max(1, MAX_LENGTH - Math.max(0, prefixLen));
        if (name == null) return "skill";
        String original = name.trim();
        StringBuilder sb = new StringBuilder(maxBase);
        for (char c : original.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-') {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(0) == '-') sb.deleteCharAt(0);
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.deleteCharAt(sb.length() - 1);
        String base = sb.toString();
        boolean lossy = !base.equals(original);
        if (base.isEmpty()) {
            return fit("skill" + "-" + shortHash(original), maxBase);
        }
        if (!lossy) {
            return fit(base, maxBase);
        }
        String suffix = "-" + shortHash(original);
        int room = maxBase - suffix.length();
        if (base.length() > room) base = base.substring(0, Math.max(0, room));
        return fit(base.isEmpty() ? "skill" + suffix : base + suffix, maxBase);
    }

    /** 规范化（按 {@code tp_} 前缀预留 3 字符，即总长 ≤64）。 */
    public static String sanitize(String name) {
        return sanitize(name, TP_PREFIX.length());
    }

    private static String fit(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** 6 位稳定哈希（{@code String.hashCode} 的算法由 Java 规范固定，重启后不变）。 */
    public static String shortHash(String s) {
        int h = (s == null) ? 0 : s.hashCode();
        return String.format("%06x", h & 0xFFFFFF);
    }
}
