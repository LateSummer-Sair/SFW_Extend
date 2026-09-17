package sair.v4.schedule;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 按会话分道的回合调度器（基板调度核心）。
 *
 * <h3>它替换掉了什么</h3>
 * <p>旧口径是一条全局单线程队列（{@code ThreadPoolExecutor(1,1,ArrayBlockingQueue(64))}）：
 * 所有会话抢同一条队列，<b>一个慢群把所有人的消息堵在后面</b>，队列满时只打一行告警、
 * 消息<b>静默消失</b>（用户那边什么都不发生）。</p>
 *
 * <h3>现在的模型</h3>
 * <ul>
 *   <li><b>每会话一条道（lane）</b>：同一个会话的任务严格 FIFO、同一条道同一时刻只有一个在跑
 *       —— 保序；不同会话的道互不相干 —— 一个慢群不再堵住别人。</li>
 *   <li><b>N 个工作线程</b>（{@code turnWorkers}，默认 2，保守）：道是逻辑上的，真正并行的是它们。</li>
 *   <li><b>溢出策略</b>（{@code turnOverflow}）：{@code coalesce}（默认）把后到的<b>并进该会话还在排队的那一条</b>
 *       —— 合并不新增待办，因此洪泛下不丢内容；合并装不下或选了 {@code drop} 时，
 *       按 {@code turnDropNotice} 回一句外挂文案（{@code 队列溢出文案}），绝不静默消失。</li>
 *   <li><b>优先级</b>（{@code turnPriority=master_first}，默认）：主人 / 私聊的道优先于群道；
 *       设成 {@code fifo} 就是纯先到先得。</li>
 *   <li><b>两道硬上限</b>：单会话待办 {@code turnLaneMax}、全体待办 {@code turnQueueMax}，
 *       保证内存有界（旧口径只有一条 64 槽队列，且那条队列之外还有无界的线程）。</li>
 *   <li><b>可观测</b>：{@see #stat()} 给出深度/峰值/丢弃/合并/平均与最长回合耗时，进 {@code status}。</li>
 * </ul>
 *
 * <p>线程全部是 daemon，{@link #shutdown()} 后工作线程在 200ms 内退出（正在跑的回合不打断，
 * 让模型/工具自己收尾）。</p>
 */
public final class Lanes implements Dispatcher {

    /** 一个会话的道。 */
    private final class Lane {
        final String key;
        /** 待办（不含正在跑的那条）；只在 {@link #lock} 内访问。 */
        final Deque<Job> q = new ArrayDeque<Job>();
        /** 是否已经在 {@link #ready} 里（避免重复入队）。 */
        boolean queued;
        /** 是否有一个任务正在跑（同一条道同时最多一个）。 */
        boolean running;
        /** 道首任务的优先级（master_first 用）。 */
        boolean high;
        /** 道首任务<b>入队</b>的时刻（纳秒）：优先级老化用它，保证普通道不会被高优流量饿死。 */
        long since;
        /** 本轮繁忙期是否已经回过"排队中"（每繁忙期最多一句，避免刷屏）。 */
        boolean notified;

        Lane(String key) {
            this.key = key;
        }
    }

    private static final class Slot {
        Lane lane;
        Job job;
    }

    private final Out out;
    private final Texts texts;
    /** 配置（只为日志类别开关留着；{@code logConsole} 里没开 {@code lane} 就不打调度决策行）。 */
    private final Conf conf;

    private final int workers;
    private final int queueMax;
    private final int laneMax;
    private final int mergeMax;
    /** {@code coalesce} | {@code drop}。 */
    private final String overflow;
    private final boolean dropNotice;
    private final boolean queuedNotice;
    private final boolean masterFirst;
    /** 连续服务高优先级道的额度：用满就必须让一次普通道（防饿死）。 */
    private final int priorityBurst;
    /** 普通道等超过这个时长就按高优先级对待（老化；0 = 不老化）。 */
    private final long priorityAgingMs;

    private final Object lock = new Object();
    private final Map<String, Lane> lanes = new HashMap<String, Lane>();
    private final Deque<Lane> ready = new ArrayDeque<Lane>();
    private final List<Thread> pool = new ArrayList<Thread>();
    private volatile boolean shutdown;

    private final AtomicInteger pending = new AtomicInteger();
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicLong in = new AtomicLong();
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong coalesced = new AtomicLong();
    private final AtomicLong mergedChars = new AtomicLong();
    private final AtomicLong notices = new AtomicLong();
    private final AtomicLong lanesCreated = new AtomicLong();
    private final AtomicLong runMs = new AtomicLong();
    private final AtomicLong maxRunMs = new AtomicLong();
    private final AtomicInteger maxDepth = new AtomicInteger();
    private final AtomicLong missingTexts = new AtomicLong();
    /** 因为"等太久"而被优先取走的普通道次数（防饿死兜底的证据）。 */
    private final AtomicLong agedPicks = new AtomicLong();
    /** 连续服务高优先级道的计数（每个工作线程各自一份，命中普通道即清零）。 */
    private int highStreak = 0;

    /** 缺键只报一次（两把键各自一次）。 */
    private volatile boolean reportedOverflow = false;
    private volatile boolean reportedQueued = false;

    public Lanes(Conf conf, Out out, Texts texts) {
        this.out = out;
        this.texts = texts;
        this.conf = conf;
        this.workers = clamp(conf == null ? 2 : conf.getInt("turnWorkers", Conf.DEF_TURN_WORKERS), 1, 32);
        this.queueMax = clamp(conf == null ? 256 : conf.getInt("turnQueueMax", Conf.DEF_TURN_QUEUE_MAX), 1, 100000);
        this.laneMax = clamp(conf == null ? 8 : conf.getInt("turnLaneMax", Conf.DEF_TURN_LANE_MAX), 1, 10000);
        this.mergeMax = clamp(conf == null ? 4000 : conf.getInt("turnMergeMaxChars", Conf.DEF_TURN_MERGE_CHARS), 0, 1000000);
        String ov = conf == null ? "coalesce" : Str.lower(Str.trim(conf.get("turnOverflow", "coalesce")));
        this.overflow = "drop".equals(ov) ? "drop" : "coalesce";
        this.dropNotice = conf == null || conf.getBool("turnDropNotice", true);
        this.queuedNotice = conf != null && conf.getBool("turnQueuedNotice", false);
        String pri = conf == null ? "master_first" : Str.lower(Str.trim(conf.get("turnPriority", "master_first")));
        this.masterFirst = !"fifo".equals(pri);
        this.priorityBurst = clamp(conf == null ? Conf.DEF_TURN_PRIORITY_BURST
                : conf.getInt("turnPriorityBurst", Conf.DEF_TURN_PRIORITY_BURST), 1, 100000);
        this.priorityAgingMs = conf == null ? Conf.DEF_TURN_PRIORITY_AGING_MS
                : Math.max(0L, conf.getLong("turnPriorityAgingMs", Conf.DEF_TURN_PRIORITY_AGING_MS));
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ==================== 提交 ====================

    @Override
    public void submit(String session, boolean high, Job job) {
        if (job == null) return;
        in.incrementAndGet();
        if (shutdown) {
            dropped.incrementAndGet();
            return;
        }
        ensurePool();
        String key = Str.blank(session) ? "-" : session.trim();
        // 回话/告警一律在<b>锁外</b>做：job.notice 会走 NapCat（网络 I/O），
        // 持着调度锁发消息 = 一个慢连接把所有人的排队都卡住。
        String dropWhy = null;
        boolean queuedTell = false;
        // 结构事实用（调度决策："这条交给谁、并了还是排了还是丢了"）—— 默认只打这些，不打消息正文
        String decision = null;
        int depth = 0;
        boolean started = false;
        synchronized (lock) {
            Lane lane = lanes.get(key);
            if (lane == null) {
                lane = new Lane(key);
                lanes.put(key, lane);
                lanesCreated.incrementAndGet();
            }
            // ① 先试着并进"该会话还在排队的那一条"：合并不占新的名额，所以优先于一切上限判断
            boolean merged = false;
            if (!"drop".equals(overflow)) {
                Job tail = lane.q.peekLast();
                if (tail != null) {
                    int n = tail.merge(job);
                    if (n >= 0) {
                        merged = true;
                        coalesced.incrementAndGet();
                        mergedChars.addAndGet(n);
                        decision = "merged n=" + n;
                    }
                }
            }
            if (!merged) {
                // ② 单会话上限
                if (lane.q.size() >= laneMax) {
                    dropWhy = "单会话待办已达上限 " + laneMax;
                } else {
                    // ③ 全体上限（先占名额再判，避免并发下越界）
                    int before = pending.incrementAndGet();
                    if (before > queueMax) {
                        pending.decrementAndGet();
                        dropWhy = "全体待办已达上限 " + queueMax;
                    } else {
                        if (lane.q.isEmpty()) lane.since = System.nanoTime();
                        lane.q.addLast(job);
                        bumpDepth(before);
                        if (!lane.queued && !lane.running) {
                            lane.high = job.high();
                            enqueue(lane);
                            decision = "start";
                            started = true;
                        } else if (queuedNotice && !lane.notified) {
                            lane.notified = true;
                            queuedTell = true;
                            decision = "queued";
                        } else {
                            decision = "queued";
                        }
                        depth = lane.q.size();
                    }
                }
            }
            lock.notifyAll();
        }
        // 调度决策一行（结构性事实：这条走了哪条路。正文只在 logVerbose 时由上游打）
        // 默认不打（控制台只留 Agent 调用事件）；要看加 logConsole 里的 lane
        if (out != null && job.log() && (conf == null || conf.logOn("lane"))) {
            String d = dropWhy != null ? ("dropped why=" + dropWhy) : decision;
            out.dim("[lane] session=" + key + " decision=" + (d == null ? "?" : d)
                    + (started ? " high=" + (job.high() ? 1 : 0) : "")
                    + " depth=" + depth + " pending=" + pending.get());
        }
        if (dropWhy != null) {
            drop(job, dropWhy);
        } else if (queuedTell) {
            tell(job, texts == null ? null : texts.queued(), true);
        }
    }

    private void bumpDepth(int d) {
        int m = maxDepth.get();
        while (d > m && !maxDepth.compareAndSet(m, d)) m = maxDepth.get();
    }

    /** 只能在 {@link #lock} 内调用。 */
    private void enqueue(Lane lane) {
        if (lane.queued) return;
        lane.queued = true;
        ready.addLast(lane);
    }

    private static boolean headHigh(Lane lane) {
        Job j = lane.q.peekFirst();
        return j != null && j.high();
    }

    /** 丢弃一条：计数 + 告警 + （按配置）回一句外挂文案。 */
    private void drop(Job job, String why) {
        dropped.incrementAndGet();
        if (out != null) {
            out.warn("[v4] 回合队列已满（" + why + "），本条按丢弃处理 —— 消息太密了；"
                    + "累计丢弃 " + dropped.get() + " 条");
        }
        if (dropNotice) tell(job, texts == null ? null : texts.overflow(), false);
    }

    /** 回一句话；缺键时<b>报错、不兜底</b>。 */
    private void tell(Job job, String text, boolean queuedKind) {
        if (Str.blank(text)) {
            boolean already = queuedKind ? reportedQueued : reportedOverflow;
            if (!already) {
                if (queuedKind) reportedQueued = true;
                else reportedOverflow = true;
                missingTexts.incrementAndGet();
                String key = queuedKind ? "排队中告知文案" : "队列溢出文案";
                if (texts != null) texts.missing(key);
            }
            return;
        }
        try {
            if (job.notice(text)) notices.incrementAndGet();
        } catch (Throwable t) {
            if (out != null) out.warn("[v4] 溢出提示发送失败: " + t);
        }
    }

    // ==================== 工作线程 ====================

    private void ensurePool() {
        synchronized (lock) {
            if (!pool.isEmpty()) return;
            for (int i = 1; i <= workers; i++) {
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        work();
                    }
                }, "v4-lane-" + i);
                t.setDaemon(true);
                pool.add(t);
                t.start();
            }
        }
    }

    private void work() {
        while (true) {
            Slot s = take();
            if (s == null) return;
            long t0 = System.nanoTime();
            try {
                s.job.run();
            } catch (Throwable t) {
                if (out != null) out.err("[v4] 回合执行异常（已隔断，队列继续）: " + t);
            } finally {
                long ms = (System.nanoTime() - t0) / 1000000L;
                done.incrementAndGet();
                runMs.addAndGet(ms);
                long m = maxRunMs.get();
                while (ms > m && !maxRunMs.compareAndSet(m, ms)) m = maxRunMs.get();
                finish(s.lane);
            }
        }
    }

    /**
     * 取下一个任务。
     *
     * <p>选道规则（{@code master_first}）：<br>
     * ① 额度内先挑高优先级道（主人/私聊）；<br>
     * ② 额度用满（{@code turnPriorityBurst}）或没有高优道时，挑一条<b>已经老化</b>的普通道
     *    （等超过 {@code turnPriorityAgingMs}）；<br>
     * ③ 剩下的按先到先得。</p>
     *
     * <p>两条兜底缺一不可：只做①会在"私聊一直来"的情况下把群消息饿死；只做②在突发高优下没有优先级意义。
     * 老化把最坏等待钉在 {@code max(burst × 单回合耗时, agingMs)} 量级（实测见探针场景 I6）。</p>
     */
    private Slot take() {
        synchronized (lock) {
            while (!shutdown) {
                Lane pick = null;
                long now = System.nanoTime();
                if (masterFirst) {
                    // ① 老化优先：等太久的普通道直接提到最前（防饿死的第一道保险，与额度无关）
                    if (priorityAgingMs > 0) {
                        long ageNanos = priorityAgingMs * 1000000L;
                        for (Lane l : ready) {
                            if (l.high) continue;
                            if (now - l.since >= ageNanos) {
                                pick = l;
                                agedPicks.incrementAndGet();
                                break;
                            }
                        }
                    }
                    // ② 额度内的高优先级道
                    if (pick == null && highStreak < priorityBurst) {
                        for (Lane l : ready) {
                            if (l.high) {
                                pick = l;
                                break;
                            }
                        }
                    }
                    // ③ 额度用尽：必须让普通道 —— 注意这里**不能**直接 peekFirst()，
                    //    因为 ready 里排在前面的多半还是高优道（它们先到），那样额度等于没设
                    if (pick == null) {
                        for (Lane l : ready) {
                            if (!l.high) {
                                pick = l;
                                break;
                            }
                        }
                    }
                }
                if (pick == null) pick = ready.peekFirst();
                if (pick != null) {
                    ready.remove(pick);
                    pick.queued = false;
                    Job j = pick.q.pollFirst();
                    if (j == null) continue;
                    highStreak = pick.high ? highStreak + 1 : 0;
                    pick.running = true;
                    pick.high = headHigh(pick);
                    pending.decrementAndGet();
                    running.incrementAndGet();
                    Slot s = new Slot();
                    s.lane = pick;
                    s.job = j;
                    return s;
                }
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }
    }

    private void finish(Lane lane) {
        synchronized (lock) {
            lane.running = false;
            running.decrementAndGet();
            if (!lane.q.isEmpty()) {
                lane.high = headHigh(lane);
                enqueue(lane);                 // 同会话的下一条：回到队尾（道之间先到先得）
            } else {
                lane.notified = false;         // 本会话这一轮繁忙期结束
                if (lane.key != null) lanes.remove(lane.key);
            }
            lock.notifyAll();
        }
    }

    @Override
    public int depth() {
        return pending.get();
    }

    @Override
    public boolean isShutdown() {
        return shutdown;
    }

    /** 关闭：不再接受新任务；工作线程在 200ms 内自然退出（不打断正在跑的回合）。 */
    public void shutdown() {
        synchronized (lock) {
            shutdown = true;
            lock.notifyAll();
        }
    }

    // ==================== 状态 ====================

    @Override
    public JsonObject stat() {
        JsonObject o = new JsonObject();
        o.addProperty("per_session_max", laneMax);
        o.addProperty("depth", pending.get());
        o.addProperty("max_depth", maxDepth.get());
        o.addProperty("lanes", lanes.size());
        o.addProperty("lanes_seen", lanesCreated.get());
        o.addProperty("workers", workers);
        o.addProperty("running", running.get());
        o.addProperty("submitted", in.get());
        o.addProperty("done", done.get());
        o.addProperty("dropped", dropped.get());
        o.addProperty("coalesced", coalesced.get());
        o.addProperty("merged_chars", mergedChars.get());
        o.addProperty("notices", notices.get());
        o.addProperty("missing_texts", missingTexts.get());
        o.addProperty("overflow", overflow);
        o.addProperty("priority", masterFirst ? "master_first" : "fifo");
        o.addProperty("priority_burst", priorityBurst);
        o.addProperty("priority_aging_ms", priorityAgingMs);
        o.addProperty("aged_picks", agedPicks.get());
        long d = done.get();
        o.addProperty("avg_ms", d <= 0 ? 0 : runMs.get() / d);
        o.addProperty("max_ms", maxRunMs.get());
        return o;
    }

    /** 供诊断：当前各道深度（最多 limit 条，按深度降序）。 */
    public Map<String, Integer> lanes(int limit) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        List<Lane> all = new ArrayList<Lane>();
        synchronized (lock) {
            all.addAll(lanes.values());
        }
        java.util.Collections.sort(all, new java.util.Comparator<Lane>() {
            @Override
            public int compare(Lane a, Lane b) {
                int c = Integer.compare(b.q.size(), a.q.size());
                return c != 0 ? c : a.key.compareTo(b.key);
            }
        });
        for (Lane l : all) {
            if (m.size() >= Math.max(1, limit)) break;
            m.put(l.key, Integer.valueOf(l.q.size() + (l.running ? 1 : 0)));
        }
        return m;
    }
}
