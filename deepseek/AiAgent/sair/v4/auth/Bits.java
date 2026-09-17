package sair.v4.auth;

import java.util.Locale;

/**
 * 权限位（纯函数，无状态、无 IO）。
 *
 * <p>位只有三样：{@code R=1}（读）/ {@code W=2}（写、改本机状态）/ {@code X=4}（执行、对外动作）。
 * 一个主体对一个资源的"有效位"就是这三位的任意组合 —— {@code NONE(0)} 表示一位都没有（显式拒绝）。</p>
 *
 * <h3>字面量</h3>
 * <pre>
 *   R  W  X  RW  RX  WX  RWX  NONE     大小写不敏感、顺序不敏感（"wr" 与 "RW" 同值）
 * </pre>
 * <p>输入认不出来时一律返回哨兵 {@link #INVALID}（{@code -1}），<b>绝不猜</b>：
 * 判定链上任何一处拿到 {@code -1} 都必须当"没有权限"处理（fail-closed）。</p>
 *
 * <p><b>注意</b>：账本条目里的"位"字段允许写<b>空串</b>表示 {@code NONE}（见 {@code Acl} 的条目语法），
 * 那是条目层的事；{@link #parse} 对空串返回 {@link #INVALID}（"没写" ≠ "写了空"）。</p>
 */
public final class Bits {

    private Bits() {}

    /** 一位都没有（显式拒绝、或上限封死）。 */
    public static final int NONE = 0;
    /** 读。 */
    public static final int R = 1;
    /** 写 / 改本机状态。 */
    public static final int W = 2;
    /** 执行 / 对外动作。 */
    public static final int X = 4;
    /** 三位全有。 */
    public static final int ALL = R | W | X;
    /** 认不出来的输入：哨兵（判定链上一律当"没有"）。 */
    public static final int INVALID = -1;

    /** 一位名字 → 位值；认不出返回 {@link #INVALID}。 */
    public static int of(char c) {
        switch (Character.toUpperCase(c)) {
            case 'R': return R;
            case 'W': return W;
            case 'X': return X;
            default:  return INVALID;
        }
    }

    /** 同 {@link #of(char)}（设计稿里的名字，两个都留着，免得调用点记错）。 */
    public static int needOf(char c) { return of(c); }

    /**
     * 字面量 → 位掩码。大小写不敏感、顺序不敏感、允许重复字母（{@code "rr"} = {@code R}）；
     * {@code NONE}（不分大小写）→ {@link #NONE}。
     *
     * @return 位掩码；{@code null} / 空串 / 含 R W X 之外的字符 → {@link #INVALID}
     */
    public static int parse(String s) {
        if (s == null) return INVALID;
        String t = s.trim();
        if (t.isEmpty()) return INVALID;
        String u = t.toUpperCase(Locale.ROOT);
        if ("NONE".equals(u)) return NONE;
        int m = NONE;
        for (int i = 0; i < u.length(); i++) {
            int b = of(u.charAt(i));
            if (b == INVALID) return INVALID;
            m |= b;
        }
        return m;
    }

    /**
     * 位掩码 → 规范写法：固定 {@code R,W,X} 顺序；{@link #NONE} → {@code "NONE"}。
     *
     * @return 规范字符串；掩码非法（负数 / 含 R W X 之外的位）→ {@code "?"}
     */
    public static String format(int mask) {
        if (!valid(mask)) return "?";
        if (mask == NONE) return "NONE";
        StringBuilder sb = new StringBuilder(3);
        if ((mask & R) != 0) sb.append('R');
        if ((mask & W) != 0) sb.append('W');
        if ((mask & X) != 0) sb.append('X');
        return sb.toString();
    }

    /** 掩码是不是合法值（{@code >=0} 且不含 R W X 之外的位）。 */
    public static boolean valid(int mask) { return mask >= 0 && (mask & ~ALL) == 0; }

    /**
     * {@code mask} 里有没有 {@code need} 这一位。
     * <p>认不出的位名、{@code NONE}（"什么都不需要"）、非法掩码一律 {@code false} ——
     * 判定链上"需要什么都不需要"当成放行是个权限洞，所以这里直接判否。</p>
     */
    public static boolean has(int mask, char need) { return has(mask, of(need)); }

    /** {@link #has(int, char)} 的位值版。 */
    public static boolean has(int mask, int bit) {
        if (!valid(mask) || bit <= 0 || (bit & ~ALL) != 0) return false;
        return (mask & bit) == bit;
    }

    /** 交集（瓶颈运算）。任一侧非法 → {@link #INVALID}（不猜）。 */
    public static int and(int a, int b) {
        if (!valid(a) || !valid(b)) return INVALID;
        return a & b;
    }

    /** 并集（"同具体度取更宽"用）。任一侧非法 → {@link #INVALID}（不猜）。 */
    public static int or(int a, int b) {
        if (!valid(a) || !valid(b)) return INVALID;
        return a | b;
    }

    /** 取反（只在这三位里反）。非法 → {@link #INVALID}。 */
    public static int not(int a) {
        if (!valid(a)) return INVALID;
        return ALL & ~a;
    }

    /** 合法写法提示（拒绝文案 / 校验报错统一用它）。 */
    public static String hint() { return "R / W / X / RW / RX / WX / RWX / NONE"; }
}
