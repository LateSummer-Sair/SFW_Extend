package sair.v4.kit;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 线程治理：基板所有后台线程都从这里出，便于统一收口（{@link #shutdown()}）。
 * <p>基板只允许两类常驻后台：①NapCat 链接服务 ②唯一的事件 tick；
 * 其余一切定时行为都应挂在 tick 的钩子上，而不是各自起线程。</p>
 */
public final class Th {

    private Th() {}

    private static final AtomicInteger SEQ = new AtomicInteger(1);

    private static final ThreadFactory FACTORY = new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "v4-" + SEQ.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    };

    private static volatile ScheduledExecutorService pool;

    private static ScheduledExecutorService pool() {
        ScheduledExecutorService p = pool;
        if (p == null || p.isShutdown()) {
            synchronized (Th.class) {
                if (pool == null || pool.isShutdown()) {
                    pool = Executors.newScheduledThreadPool(4, FACTORY);
                }
                p = pool;
            }
        }
        return p;
    }

    public static Thread daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public static void start(String name, Runnable r) { daemon(name, r).start(); }

    /** 固定间隔执行（首次延迟 delayMs）。 */
    public static ScheduledFuture<?> every(String name, long delayMs, long periodMs, final Runnable r) {
        return pool().scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } catch (Throwable t) {
                    // 后台任务异常不允许杀死调度
                }
            }
        }, Math.max(0, delayMs), Math.max(1, periodMs), TimeUnit.MILLISECONDS);
    }

    public static ScheduledFuture<?> once(long delayMs, Runnable r) {
        return pool().schedule(r, Math.max(0, delayMs), TimeUnit.MILLISECONDS);
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 基板静态资源的收敛点（{@code Boot.stop()} 唯一调它）：<b>只收调度池</b>。
     *
     * <p><b>控制台捕获器不在这里注销了</b>（主人 2026-09-16 裁定）：它是<b>壳层</b>设施
     * （控制台的一部分，插件构造期就挂上），不是基板组件。旧口径在这里
     * {@code ConsoleTap.uninstall()}，真机后果是 {@code ai/restart} 停旧基板那一瞬间
     * 控制台捕获被摘掉，而新装配路径上<b>没有任何一处</b>把它装回来 →
     * 重启之后"控制台输出既不捕获、也不落盘"（{@code logs\console-<日期>.log} 从此不再增长），
     * 框架还会退回"调用线程直插文档"那条会写坏视图树的老路。
     * 现在只有壳卸载（{@code DebugShell.detach()} ← {@code V4Activity.exit()}）才注销它。</p>
     */
    public static void shutdown() {
        ScheduledExecutorService p = pool;
        if (p != null) {
            p.shutdownNow();
            pool = null;
        }
    }
}
