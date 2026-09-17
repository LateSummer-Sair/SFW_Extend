package sair.v4.schedule;

import com.google.gson.JsonObject;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 有界执行池（钩子技能用）。
 *
 * <h3>它替换掉了什么</h3>
 * <p>钩子以前是"在谁身上来就在谁身上跑"：{@code on_message} 钩子跑在 {@code Link} 给每条事件起的
 * <b>裸线程</b>上（{@code Th.start("v4-napcat-evt")}，无上限），{@code on_timer} 跑在唯一 tick 的
 * 4 线程池上。入站洪泛 + 慢钩子（群审核要打 NapCat 动作、触发词要 {@code spawn}、主动接话要读库）
 * 就等于"线程数跟着消息数涨"。</p>
 *
 * <h3>现在的口径</h3>
 * <ul>
 *   <li>有界：{@code hookWorkers} 个线程 + {@code hookQueueMax} 长的队列，线程数不随消息数增长；</li>
 *   <li>{@code workers <= 0}（或队列 <= 0）= <b>退化成旧行为</b>（调用线程直接跑），
 *       给"我想先关掉这一层"留一个明确的开关；</li>
 *   <li>队列满时 <b>caller-runs</b>：由调用线程自己跑这一条。钩子必须同步拿到结果
 *       （{@code payload._handled} 决定基板还要不要自己回），所以这里<b>不能</b>异步化，也不能丢；
 *       更要紧的是 caller-runs 让"钩子里再派钩子"这种嵌套调用不会自锁。</li>
 * </ul>
 */
public final class Pool {

    private final Out out;
    private final String name;
    private final int workers;
    private final int queueMax;
    /** 池满时的处置：{@code caller}（调用线程兜底跑，默认）/ {@code skip}（跳过并计数 + 告警）。 */
    private final String overflow;
    private final ThreadPoolExecutor ex;

    private final AtomicLong in = new AtomicLong();
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong callerRuns = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong runMs = new AtomicLong();
    private final AtomicLong maxRunMs = new AtomicLong();
    private final AtomicInteger peak = new AtomicInteger();

    /**
     * @param name    线程名前缀（诊断/探针按名字数线程）
     * @param workers 线程数；{@code <=0} = 不进池（调用线程直接跑，旧行为）
     * @param queueMax 队列上限；{@code <=0} = 不进池
     */
    public Pool(String name, int workers, int queueMax, Out out) {
        this(name, workers, queueMax, out, "caller");
    }

    public Pool(String name, int workers, int queueMax, Out out, String overflow) {
        this.out = out;
        this.name = Str.blank(name) ? "v4-pool" : name;
        this.workers = workers;
        this.queueMax = queueMax;
        this.overflow = "skip".equalsIgnoreCase(Str.trim(overflow)) ? "skip" : "caller";
        if (workers <= 0 || queueMax <= 0) {
            this.ex = null;
            return;
        }
        this.ex = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(queueMax),
                new ThreadFactory() {
                    private final AtomicInteger n = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, Pool.this.name + "-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                // 溢出处置是**显式**的两种，且都可观测（旧口径其实只有 caller-runs 这一种，但没有名字）
                new java.util.concurrent.RejectedExecutionHandler() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                        if ("skip".equals(Pool.this.overflow)) {
                            long n = skipped.incrementAndGet();
                            if (out != null) {
                                out.warn("[v4] " + Pool.this.name + " 队列已满（" + e.getQueue().size()
                                        + " 条在等，上限 " + Pool.this.queueMax + "），本条按 hookOverflow=skip 跳过；"
                                        + "累计跳过 " + n + " 条");
                            }
                            return;
                        }
                        long n = callerRuns.incrementAndGet();
                        if (out != null && n <= 3) {
                            out.dim("[v4] " + Pool.this.name + " 队列已满，本条由调用线程兜底执行"
                                    + "（hookOverflow=caller；累计 " + n + " 次，不丢任务）");
                        }
                        if (!e.isShutdown()) r.run();
                    }
                });
    }

    /** 由配置造一个钩子池（{@code hookWorkers} / {@code hookQueueMax} / {@code hookOverflow}）。 */
    public static Pool hooks(Conf conf, Out out) {
        int w = conf == null ? Conf.DEF_HOOK_WORKERS : conf.getInt("hookWorkers", Conf.DEF_HOOK_WORKERS);
        int q = conf == null ? Conf.DEF_HOOK_QUEUE_MAX : conf.getInt("hookQueueMax", Conf.DEF_HOOK_QUEUE_MAX);
        String ov = conf == null ? Conf.DEF_HOOK_OVERFLOW : conf.hookOverflow();
        return new Pool("v4-hook", w, q, out, ov);
    }

    /** 是否真的进了池（false = 旧行为：调用线程直接跑）。 */
    public boolean enabled() {
        return ex != null;
    }

    /**
     * 提交并<b>等它跑完</b>（钩子的契约就是同步的：跑完才知道 {@code _handled}）。
     * 池满时由调用线程自己跑（caller-runs），因此这里不会丢、也不会自锁。
     */
    public void submitAndWait(final Runnable r) {
        if (r == null) return;
        in.incrementAndGet();
        final Thread me = Thread.currentThread();
        final boolean[] inline = new boolean[1];
        Runnable task = new Runnable() {
            @Override
            public void run() {
                if (Thread.currentThread() == me) inline[0] = true;
                long t0 = System.nanoTime();
                try {
                    r.run();
                } catch (Throwable t) {
                    if (out != null) out.err("[v4] " + name + " 任务异常: " + t);
                } finally {
                    long ms = (System.nanoTime() - t0) / 1000000L;
                    done.incrementAndGet();
                    runMs.addAndGet(ms);
                    long m = maxRunMs.get();
                    while (ms > m && !maxRunMs.compareAndSet(m, ms)) m = maxRunMs.get();
                }
            }
        };
        if (ex == null) {
            task.run();
            return;
        }
        int active = ex.getActiveCount();
        int p = peak.get();
        while (active > p && !peak.compareAndSet(p, active)) p = peak.get();
        Future<?> f = ex.submit(task);
        try {
            f.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            if (out != null) out.err("[v4] " + name + " 任务异常: " + e.getCause());
        }
        if (inline[0]) callerRuns.incrementAndGet();
    }

    public void shutdown() {
        if (ex != null) ex.shutdownNow();
    }

    public JsonObject stat() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", ex != null);
        o.addProperty("workers", ex == null ? 0 : workers);
        o.addProperty("queue_max", ex == null ? 0 : queueMax);
        o.addProperty("queued", ex == null ? 0 : ex.getQueue().size());
        o.addProperty("active", ex == null ? 0 : ex.getActiveCount());
        o.addProperty("peak_active", ex == null ? 0 : peak.get());
        o.addProperty("submitted", in.get());
        o.addProperty("done", done.get());
        o.addProperty("overflow", ex == null ? "-" : overflow);
        o.addProperty("caller_runs", callerRuns.get());
        o.addProperty("skipped", skipped.get());
        long d = done.get();
        o.addProperty("avg_ms", d <= 0 ? 0 : runMs.get() / d);
        o.addProperty("max_ms", maxRunMs.get());
        return o;
    }
}
