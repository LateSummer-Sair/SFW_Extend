package sair.aiagent.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ThreadManager —— 统一线程治理中心。
 *
 * <p>把散落在 {@link sair.aiagent.AiAgentActivity}、{@link AgentExecutor}、
 * {@link sair.aiagent.onebot.OneBotServer} 中的 {@code new Thread(...)}、
 * {@code Executors.newFixedThreadPool(...)} 等线程创建收敛为统一入口，
 * 提供：
 * <ul>
 *   <li>{@link #newNamedCached(String)} / {@link #newNamedFixed(String, int)} /
 *       {@link #newNamedSingle(String)} —— 命名线程池（统一守护线程、命名规范）。</li>
 *   <li>{@link #newDaemonThread(String, Runnable)} —— 注册守护线程，自动纳入治理。</li>
 *   <li>{@link #shutdown()} —— 一次性停止所有已注册线程池与守护线程，供 exit() 干净回收。</li>
 * </ul>
 * 所有线程均为 daemon（不阻塞 JVM 退出），并带可辨识名称便于日志与故障定位。</p>
 */
public class ThreadManager {

    private static volatile ThreadManager instance;

    /** 获取单例（双重检查锁定）。 */
    public static ThreadManager getInstance() {
        if (instance == null) {
            synchronized (ThreadManager.class) {
                if (instance == null) instance = new ThreadManager();
            }
        }
        return instance;
    }

    /** 已注册线程池（name → pool）。 */
    private final Map<String, ExecutorService> pools = new ConcurrentHashMap<>();
    /** 已注册守护线程（CopyOnWrite 保证并发遍历安全）。 */
    private final List<Thread> daemonThreads = new CopyOnWriteArrayList<>();

    private ThreadManager() {}

    /** 构造命名守护线程工厂（线程名 name-序号）。 */
    private static ThreadFactory namedDaemonFactory(final String name) {
        final AtomicInteger seq = new AtomicInteger(1);
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name + "-" + seq.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }

    /** 创建/复用命名缓存线程池（有界，按需扩容，空闲回收）。 */
    public ExecutorService newNamedCached(String name) {
        ExecutorService e = pools.get(name);
        if (e == null) {
            ExecutorService created = new ThreadPoolExecutor(0, 32, 60L, TimeUnit.SECONDS,
                    new SynchronousQueue<Runnable>(), namedDaemonFactory(name),
                    new ThreadPoolExecutor.CallerRunsPolicy());
            e = pools.putIfAbsent(name, created);
            if (e == null) e = created;
        }
        return e;
    }

    /** 创建/复用命名调度线程池。 */
    public ScheduledExecutorService newNamedScheduled(String name, int corePoolSize) {
        ExecutorService e = pools.get(name);
        if (e == null) {
            ExecutorService created = Executors.newScheduledThreadPool(Math.max(1, corePoolSize),
                    namedDaemonFactory(name));
            e = pools.putIfAbsent(name, created);
            if (e == null) e = created;
        }
        return (ScheduledExecutorService) e;
    }

    /** 创建/复用命名固定线程池。 */
    public ExecutorService newNamedFixed(String name, int nThreads) {
        ExecutorService e = pools.get(name);
        if (e == null) {
            synchronized (this) {
                e = pools.get(name);
                if (e == null) {
                    e = Executors.newFixedThreadPool(Math.max(1, nThreads), namedDaemonFactory(name));
                    pools.put(name, e);
                }
            }
        } else if (e instanceof java.util.concurrent.ThreadPoolExecutor) {
            java.util.concurrent.ThreadPoolExecutor tpe = (java.util.concurrent.ThreadPoolExecutor) e;
            int current = tpe.getMaximumPoolSize();
            int want = Math.max(1, nThreads);
            if (current != want) {
                tpe.setCorePoolSize(want);
                tpe.setMaximumPoolSize(want);
            }
        }
        return e;
    }

    /** 创建/复用命名单线程池。 */
    public ExecutorService newNamedSingle(String name) {
        ExecutorService e = pools.get(name);
        if (e == null) {
            ExecutorService created = Executors.newSingleThreadExecutor(namedDaemonFactory(name));
            e = pools.putIfAbsent(name, created);
            if (e == null) e = created;
        }
        return e;
    }

    /**
     * 注册一个守护线程（用于需要长期运行、手工管理生命周期的循环线程，
     * 如 AutoDistill / AutoClear）。线程会被纳入治理，{@link #shutdown()} 时 interrupt。
     *
     * @return 已注册的 Thread（未启动，调用方自行 start）
     */
    public Thread newDaemonThread(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        daemonThreads.add(t);
        return t;
    }

    /** 停止所有已注册线程池与守护线程，并清空注册表。幂等。 */
    public void shutdown() {
        for (ExecutorService e : pools.values()) {
            try { e.shutdownNow(); } catch (Exception ignored) {}
        }
        pools.clear();
        for (Thread t : daemonThreads) {
            try { t.interrupt(); } catch (Exception ignored) {}
        }
        daemonThreads.clear();
    }

    /** 运行时状态摘要：线程池数量 + 守护线程存活/总数 + 各池活跃线程/队列深度。 */
    public String statusSummary() {
        int alive = 0;
        for (Thread t : daemonThreads) {
            if (t != null && t.isAlive()) alive++;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("pools=").append(pools.size())
          .append(", daemonThreads=").append(alive).append("/").append(daemonThreads.size());
        for (Map.Entry<String, ExecutorService> e : pools.entrySet()) {
            if (e.getValue() instanceof ThreadPoolExecutor) {
                ThreadPoolExecutor tpe = (ThreadPoolExecutor) e.getValue();
                sb.append("\n    ").append(e.getKey())
                  .append(": active=").append(tpe.getActiveCount())
                  .append("/").append(tpe.getMaximumPoolSize())
                  .append(" queue=").append(tpe.getQueue().size());
            }
        }
        return sb.toString();
    }
}
