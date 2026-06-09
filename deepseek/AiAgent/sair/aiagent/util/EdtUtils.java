package sair.aiagent.util;

import java.awt.Color;

import sair.aiagent.core.StreamPrinter;

/**
 * 统一输出工具类 —— 所有控制台输出通过单例 {@link StreamPrinter} 队列化。
 * <p>
 * 从此不再直接操作 EDT/SairCons，所有输出进入 StreamPrinter 的
 * 守护线程队列，按 FIFO 顺序统一输出，彻底消除多线程穿插打断问题。
 * </p>
 */
public final class EdtUtils {

    private EdtUtils() {} // 工具类禁止实例化

    private static StreamPrinter p() {
        return StreamPrinter.getInstance();
    }

    /** 输出纯文本（不加换行） */
    public static void print(final Color c, final String msg) {
        p().offerPlain(c, msg);
    }

    /** 输出一行（加换行），指定颜色 */
    public static void println(final Color c, final String msg) {
        p().offerLine(c, msg);
    }

    /** 输出一行（加换行），使用默认色 */
    public static void println(final String msg) {
        p().offerLine(null, msg);
    }

    /**
     * 批量输出多行，参数为 (Color, String) 成对交替。
     * <p>Color 为 null 时使用默认色。示例：</p>
     * <pre>{@code
     *   printlnLines(
     *       Color.RED, "第一行",
     *       Color.BLUE, "第二行",
     *       null, "第三行（默认色）"
     *   );
     * }</pre>
     */
    public static void printlnLines(final Object... colorAndLines) {
        StreamPrinter sp = p();
        for (int i = 0; i < colorAndLines.length; i += 2) {
            Color c = (Color) colorAndLines[i];
            String line = (String) colorAndLines[i + 1];
            sp.offerLine(c, line);
        }
    }
}
