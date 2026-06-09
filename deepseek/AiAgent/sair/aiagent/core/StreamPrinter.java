package sair.aiagent.core;

import java.awt.Color;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

import sair.sys.SairCons;

/**
 * 单例流式打印机 —— 所有控制台输出统一入口。
 * <p>
 * 采用生产者-消费者模式，独立打印线程处理队列。
 * 两种输出类型：
 * <ul>
 *   <li><b>行输出</b>（{@link #offerLine}/{@link #offerPlain}）—— 通过 EdtUtils 调用，
 *       整行无延迟直接输出，不会被打断。</li>
 *   <li><b>逐字流式输出</b>（{@link #offer(char)}/{@link #offer(String)}）—— 
 *       AI 流式响应，10ms/字打字机效果。</li>
 * </ul>
 * </p>
 *
 * <h3>排空机制</h3>
 * <p>调用 {@link #start()} 开启新输出会话时，先将旧队列中剩余字符以全速排空，
 * 再开始新的流式输出。保证旧内容完整展示，不与新内容穿插。</p>
 *
 * <h3>颜色约定</h3>
 * <ul>
 *   <li>AI正文输出 —— {@code (100, 255, 180)} 翠绿色</li>
 *   <li>AI思考过程 —— {@code (128, 128, 255)} 淡紫蓝</li>
 * </ul>
 */
public class StreamPrinter {

    // ==================== 颜色常量 ====================

    /** AI正文输出颜色 —— 翠绿色 */
    public static final Color C_AI = new Color(100, 255, 180);

    /** AI思考过程颜色 —— 淡紫蓝 */
    public static final Color C_THINK = new Color(128, 128, 255);

    // ==================== 单例 ====================

    private static final StreamPrinter INSTANCE = new StreamPrinter();

    public static StreamPrinter getInstance() {
        return INSTANCE;
    }

    // ==================== 配置 ====================

    /** 每字延迟（毫秒），打字机节奏感 */
    private static final int DELAY_MS = 10;

    /** 队列为空时等待新条目的超时（毫秒） */
    private static final int POLL_TIMEOUT_MS = 200;

    // ==================== 队列条目 ====================

    /**
     * 队列条目 —— 区分行输出（无延迟）与逐字输出（有延迟）。
     */
    private static class Item {
        final Color color;
        final String text;      // non-null → 行/纯文本输出（无延迟）
        final char ch;          // 当 text == null 时使用（逐字输出）
        final boolean newline;  // true = println（加换行）, false = print（不加）

        /** 行/纯文本输出 */
        Item(Color c, String t, boolean nl) {
            this.color = c; this.text = t; this.ch = '\0'; this.newline = nl;
        }

        /** 逐字输出 */
        Item(Color c, char character) {
            this.color = c; this.text = null; this.ch = character; this.newline = false;
        }
    }

    // ==================== 内部状态 ====================

    /** 条目阻塞队列（线程安全） */
    private final BlockingQueue<Item> queue = new LinkedBlockingQueue<>();

    /** 当前打印颜色 */
    private volatile Color currentColor = C_AI;

    /** 打印线程（守护线程，随 JVM 生命周期运行） */
    private Thread printerThread;

    /** 是否活跃（线程运行中） */
    private volatile boolean active = false;

    /** 排空模式：跳过延迟全速输出 */
    private volatile boolean flushMode = false;

    // ==================== 构造 ====================

    private StreamPrinter() {
        startThread();
    }

    /** 启动守护打印线程 */
    private synchronized void startThread() {
        if (active) return;
        active = true;
        printerThread = new Thread(this::printLoop, "AiAgent-StreamPrinter");
        printerThread.setDaemon(true);
        printerThread.start();
    }

    // ==================== 行输出（EdtUtils 调用，无延迟） ====================

    /**
     * 输出一行（自动加换行），无延迟，立即进入队列。
     * @param color 颜色，null 使用当前默认色
     * @param text  文本内容
     */
    public void offerLine(Color color, String text) {
        if (text == null) return;
        queue.offer(new Item(color != null ? color : currentColor, text, true));
    }

    /**
     * 输出纯文本（不加换行），无延迟，立即进入队列。
     * @param color 颜色，null 使用当前默认色
     * @param text  文本内容
     */
    public void offerPlain(Color color, String text) {
        if (text == null) return;
        queue.offer(new Item(color != null ? color : currentColor, text, false));
    }

    // ==================== 逐字流式输出（AI 流式响应，有延迟） ====================

    /**
     * 将一个字符放入打印队列（生产者调用）。
     * <p>线程安全，可在任意线程中调用。</p>
     */
    public void offer(char ch) {
        queue.offer(new Item(currentColor, ch));
    }

    /**
     * 将字符串中的所有字符依次放入打印队列。
     */
    public void offer(String text) {
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                queue.offer(new Item(currentColor, text.charAt(i)));
            }
        }
    }

    /**
     * 切换当前打印颜色。
     */
    public void setColor(Color color) {
        if (color != null) {
            this.currentColor = color;
        }
    }

    // ==================== 会话控制 ====================

    /**
     * 启动新的输出会话。
     * <p>先将旧队列中剩余条目以全速排空，确保旧内容完整展示后，
     * 再开始新的流式输出。</p>
     */
    public void start() {
        // 排空旧内容
        flushMode = true;
        try {
            while (!queue.isEmpty()) {
                Thread.sleep(5);
            }
            Thread.sleep(60); // 给 EDT 一点余量渲染最后几个条目
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flushMode = false;
        // 确保线程在运行
        startThread();
    }

    /**
     * 通知打印机：当前批次生产已完成（流式响应结束）。
     * <p>调用后打印机仍运行（守护线程），只是标记当前批次结束，
     * 配合 {@link #await(long)} 等待队列清空。</p>
     */
    public void finish() {
        // no-op in perpetual thread model
    }

    /**
     * 查询打印机是否仍在运行。
     */
    public boolean isActive() {
        return active;
    }

    /**
     * 等待打印队列清空。
     *
     * @param timeoutMs 最长等待时间（毫秒）
     */
    public void await(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(30);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 立即停止打印（丢弃队列中剩余条目，重启线程）。
     */
    public synchronized void stop() {
        active = false;
        flushMode = true;
        queue.clear();
        if (printerThread != null && printerThread.isAlive()) {
            printerThread.interrupt();
            try {
                printerThread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
        printerThread = null;
        startThread();
    }

    /**
     * 排空模式停止：全速输出队列中剩余条目后重启线程。
     * <p>调用前应确保生产者（API线程）已停止，否则可能持续等待新条目。</p>
     */
    public synchronized void flushAndStop() {
        flushMode = true;
        try {
            while (!queue.isEmpty()) {
                Thread.sleep(5);
            }
            Thread.sleep(100); // 给 EDT 渲染余量
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 重启线程（清状态）
        active = false;
        if (printerThread != null && printerThread.isAlive()) {
            printerThread.interrupt();
            try {
                printerThread.join(2000);
            } catch (InterruptedException ignored) {
            }
        }
        printerThread = null;
        flushMode = false;
        startThread();
    }

    // ==================== 核心打印循环 ====================

    /**
     * 主打印循环 —— 在守护线程中运行。
     * <p>
     * 不断从队列取出条目：
     * <ul>
     *   <li>行条目（text != null）：整行/纯文本无延迟直接输出</li>
     *   <li>字符条目（text == null）：逐字输出，10ms 延迟（排空模式跳过延迟）</li>
     * </ul>
     * SFW 使用 {@code \r\n} 换行约定，故将 {@code \n} 转换为 {@code \r\n}。
     * </p>
     */
    private void printLoop() {
        while (active) {
            try {
                Item item = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (item == null) continue;

                if (item.text != null) {
                    // === 行/纯文本输出：无延迟 ===
                    final Color c = item.color;
                    final String t = item.text;
                    if (item.newline) {
                        SwingUtilities.invokeLater(() -> SairCons.println(c, t));
                    } else {
                        SwingUtilities.invokeLater(() -> SairCons.print(c, t));
                    }
                } else {
                    // === 逐字流式输出：有延迟 ===
                    final char ch = item.ch;

                    // 跳过孤立 \r，防止与 \n 组合重复换行
                    if (ch == '\r') continue;

                    final boolean isNewline = (ch == '\n');
                    final Color c = item.color;

                    SwingUtilities.invokeLater(() -> {
                        if (isNewline) {
                            SairCons.print(c, "\r\n");
                        } else {
                            SairCons.print(c, String.valueOf(ch));
                        }
                    });

                    // 打字机延迟：换行符和排空模式跳过
                    if (!isNewline && !flushMode) {
                        Thread.sleep(DELAY_MS);
                    }
                }
            } catch (InterruptedException e) {
                if (!active) break;
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                // Swing 异常等，继续尝试
            }
        }
    }
}
