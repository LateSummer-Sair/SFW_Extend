package sair.v4.ext;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.auth.Caller;
import sair.v4.ctx.Turn;
import sair.v4.kit.Out;
import sair.v4.qq.Ev;
import sair.v4.skill.Host;

/**
 * 扩展点注册表（基板⑦）：<b>"基板更强"的核心</b>就是把外挂接进来的这道口。
 *
 * <p>它管四件事，一件都不多做：</p>
 * <ol>
 *   <li><b>注册与注销</b>：按插件（技能文件夹名）整体挂、整体摘。插件重扫时先摘后挂，
 *       所以"改一半的插件"不会留下半套扩展点。</li>
 *   <li><b>顺序</b>：{@code order, 插件名} 二级排序 —— 同输入逐字节可复现（前缀缓存要的就是这个）。</li>
 *   <li><b>护栏</b>：逐次调用独立超时、逐段字符预算 + 全局字符预算、异常只丢自己。</li>
 *   <li><b>诊断</b>：每个扩展点一行 {@code [ext]} 事实行（受 {@code logConsole} 的 {@code ext} 类别控制）
 *       + {@code status.ext} 的逐条贡献与耗时。</li>
 * </ol>
 *
 * <h3>为什么要有这道护栏</h3>
 * <p>外挂是外部代码，它可以慢、可以崩、可以一次吐十万字。基板的承诺是<b>"永不失联"</b>：
 * 插件出什么问题都只让它自己那一段消失，这一轮照常跑完、照常回话。所以：
 * 超时丢段、异常丢段、预算丢段，三条路都只产生一行日志。</p>
 *
 * <h3>线程与权限口径（刻意的两个决定）</h3>
 * <ul>
 *   <li><b>扩展点在基板的扩展工作线程上跑</b>（有界池，见 {@link #WORKERS}），不占用回合线程 ——
 *       超时才有意义（回合线程被插件卡住时，任何超时都只是句空话）。代价是插件拿不到
 *       {@code ctx.Ctx} 的线程绑定；这<b>正合设计稿的口径</b>："扩展点的行为 = 基板行为，
 *       不按调用者权限裁定"。池满（4 个槽全被"超时后还在跑"的插件占着）时退回
 *       caller-runs（调用线程直接跑，无超时）—— 与钩子池 {@code hookOverflow=caller} 同一口径：
 *       宁可慢，也不静默丢段。</li>
 *   <li><b>每个插件实例的扩展点调用串行化</b>（{@code synchronized(impl)}）。扩展点实例是<b>长活</b>的
 *       （装载时建一次，与 {@code airun.state: shared} 同一口径），而基板有两条会话道会同时进来；
 *       不串行化就等于让插件作者自己处理并发。代价是"同一插件的两轮不能同时用它的扩展点" ——
 *       这个代价比"插件的读-改-写互相覆盖"小得多。</li>
 * </ul>
 *
 * <h3>零开销开关</h3>
 * <p>四个接线点（装配 / 出站 / 投票 / 生命周期）都先问 {@link #on()}：没有任何扩展点（或总开关
 * {@code extEnabled=false}）时，它们<b>一行代码都不多走</b> —— 这是"行为不变"的实现要点。</p>
 */
public final class ExtRegistry {

    /**
     * 扩展点工作线程数（<b>刻意不做成配置键</b>）。
     * <p>它不是"并发度"旋钮：回合调度与模型闸门才决定真正的并发。这个池只用来让超时能生效，
     * 4 个槽足够覆盖"两条会话道 + 出站 + 一条后台回合"；真被占满说明有插件在超时后还不返回，
     * 那时正确的动作是停掉那个插件，而不是把池调大。</p>
     */
    private static final int WORKERS = 4;

    // ================================================================ 数据结构

    /**
     * 一条已注册的扩展点：实现 + 归属插件名 + 注册时取到的 order + 诊断统计。
     *
     * <p>{@code order} 在<b>注册时取一次</b>并兜住异常：排序路径绝不能因为某个插件的
     * {@code order()} 抛异常而整体崩掉，而且"两次装配之间的排序"不该因为插件内部状态变化而抖动。</p>
     */
    public static final class Entry<T> {
        public final T impl;
        public final String owner;
        public final int order;
        /** 实现类的短名（诊断用；不含包名）。 */
        public final String simple;

        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong timeouts = new AtomicLong();
        private final AtomicLong drops = new AtomicLong();
        private final AtomicLong chars = new AtomicLong();
        private final AtomicLong totalMs = new AtomicLong();
        private volatile long lastMs;
        /** 最近一次调用产出/处理了多少字符（{@code [ext]} 那一行打的是它，不是累计值）。 */
        private volatile long lastChars;

        Entry(T impl, String owner, int order) {
            this.impl = impl;
            this.owner = owner == null ? "" : owner;
            this.order = order;
            this.simple = simpleOf(impl);
        }

        /**
         * 诊断用的短名。
         * <p>匿名实现类的 {@code getSimpleName()} 是空串（探针里大量用匿名实现），
         * 空名字会让诊断行变成 {@code name=pT:} 这种没法定位的样子；退到父类短名，
         * 实在没有就用 {@code anon}。真机插件都是具名类，不受影响。</p>
         */
        private static String simpleOf(Object impl) {
            if (impl == null) return "?";
            String s = impl.getClass().getSimpleName();
            if (s != null && !s.isEmpty()) return s;
            Class<?> sup = impl.getClass().getSuperclass();
            String p = (sup == null || sup == Object.class) ? "" : sup.getSimpleName();
            return (p == null || p.isEmpty()) ? "anon" : (p + "$");
        }

        /** 诊断名：{@code 插件名:实现类}（没有插件名时只有实现类）。 */
        public String label() {
            return owner.isEmpty() ? simple : (owner + ":" + simple);
        }

        public long calls() { return calls.get(); }

        public long errors() { return errors.get(); }

        public long timeouts() { return timeouts.get(); }

        public long drops() { return drops.get(); }

        public long chars() { return chars.get(); }

        public long totalMs() { return totalMs.get(); }

        public long lastMs() { return lastMs; }
    }

    /** 装配期的一块（一段 system 消息）。 */
    public static final class Block {
        public final String owner;
        public final String text;
        /** 稳定段（排在系统提示词之后、事实块之前）还是易变段（排在事实块之后）。 */
        public final boolean stable;
        public final int order;
        /** 产出这一段的注册项（只给基板内部用：预算丢段时要把这一丢记到它头上）。 */
        final Entry<ContextProvider> src;

        Block(Entry<ContextProvider> src, String text, boolean stable) {
            this.src = src;
            this.owner = src.owner;
            this.text = text;
            this.stable = stable;
            this.order = src.order;
        }
    }

    /** 触发投票的结果（谁投的、投了什么、为什么）。 */
    public static final class Verdict {
        public final TriggerVoter.Vote vote;
        public final String reason;
        /** 投票人（诊断名）。 */
        public final String by;

        Verdict(TriggerVoter.Vote vote, String reason, String by) {
            this.vote = vote;
            this.reason = reason;
            this.by = by;
        }
    }

    /** 追加消息用的分段口（由 {@code term.Sinks} 把它自己的分段口径传进来，保证"同一把尺子"）。 */
    public interface Splitter {
        List<String> split(String text);
    }

    /** 出站管线的结果。 */
    public static final class Staged {
        /** 最终要发的消息（已变换的正文 + 追加消息；<b>还没剥 {@code <split>} 之类的标记</b>）。 */
        public final List<String> parts;
        /** 被 transform 改过的条数。 */
        public final int changed;
        /** 被 veto（或被清洗成空）丢掉的条数。 */
        public final int dropped;
        /** 追加了几条消息。 */
        public final int appended;

        Staged(List<String> parts, int changed, int dropped, int appended) {
            this.parts = parts;
            this.changed = changed;
            this.dropped = dropped;
            this.appended = appended;
        }
    }

    /** 给扩展点用的宿主编排口（由基板装配注入；拿不到就是 null）。 */
    public interface Hosts {
        /** {@code owner} = 插件名（空串 = 基板自身 / 没有归属）。 */
        Host hostFor(String owner, Caller c, Turn t);
    }

    // ================================================================ 字段

    private final Conf conf;
    private final Out out;

    private final CopyOnWriteArrayList<Entry<ContextProvider>> providers =
            new CopyOnWriteArrayList<Entry<ContextProvider>>();
    private final CopyOnWriteArrayList<Entry<OutboundStage>> stages =
            new CopyOnWriteArrayList<Entry<OutboundStage>>();
    private final CopyOnWriteArrayList<Entry<TriggerVoter>> voters =
            new CopyOnWriteArrayList<Entry<TriggerVoter>>();
    private final CopyOnWriteArrayList<Entry<LifecycleHook>> hooks =
            new CopyOnWriteArrayList<Entry<LifecycleHook>>();

    /** 一共挂了多少条（四个表之和；{@link #on()} 的零开销判据）。 */
    private final AtomicInteger count = new AtomicInteger();
    /** 因全局预算被整段丢掉的段数（诊断）。 */
    private final AtomicLong budgetDrops = new AtomicLong();

    private volatile Hosts hosts;

    /** 扩展点工作线程池（懒建：一个扩展点都没挂过就永远不建线程）。 */
    private volatile ThreadPoolExecutor pool;

    public ExtRegistry(Conf conf, Out out) {
        this.conf = conf;
        this.out = out;
    }

    /** 注入宿主编排口（装配期调一次）。 */
    public void setHosts(Hosts h) { this.hosts = h; }

    // ================================================================ 配置开关

    /** 扩展点总开关（配置 {@code extEnabled}，默认开）；关掉 = 基板退回"零外挂"行为。 */
    public boolean enabled() {
        try {
            return conf == null || conf.getBool("extEnabled", Conf.DEF_EXT_ENABLED);
        } catch (Throwable t) {
            return Conf.DEF_EXT_ENABLED;
        }
    }

    /** 每次扩展点调用的超时（毫秒，配置 {@code extTimeoutMs}）；{@code <=0} = 不超时。 */
    public long timeoutMs() {
        try {
            return conf == null ? Conf.DEF_EXT_TIMEOUT_MS : conf.extTimeoutMs();
        } catch (Throwable t) {
            return Conf.DEF_EXT_TIMEOUT_MS;
        }
    }

    /** 上下文 provider 块的全局字符预算（配置 {@code ctxBudgetChars}）；{@code <=0} = 不限。 */
    public int budgetChars() {
        try {
            return conf == null ? Conf.DEF_CTX_BUDGET_CHARS : conf.ctxBudgetChars();
        } catch (Throwable t) {
            return Conf.DEF_CTX_BUDGET_CHARS;
        }
    }

    /** 挂了至少一条扩展点（不管总开关）。 */
    public boolean any() { return count.get() > 0; }

    /** 四个接线点的零开销开关：总开关开着<b>且</b>至少挂了一条，才需要走扩展点这条路。 */
    public boolean on() { return enabled() && any(); }

    /** 出站热路径专用的零开销开关（每条消息都问一次，所以只查 stage 表）。 */
    public boolean onStages() { return enabled() && !stages.isEmpty(); }

    // ================================================================ 注册 / 注销

    /**
     * 一个类是不是扩展点（按接口自动识别，见 {@code hot.Skills} 的装载点）。
     * <p>只认这四个接口；{@code Skill} / {@code Hooked} 走各自的老路（工具与事件钩子）。</p>
     */
    public static boolean isExtClass(Class<?> k) {
        if (k == null) return false;
        return ContextProvider.class.isAssignableFrom(k)
                || OutboundStage.class.isAssignableFrom(k)
                || TriggerVoter.class.isAssignableFrom(k)
                || LifecycleHook.class.isAssignableFrom(k);
    }

    /**
     * 注册一个实例：<b>它实现哪个接口就注册哪个</b>（一个类可以同时是 provider 与 hook）。
     *
     * @param owner 插件名（技能文件夹名）；空串 = 基板自身 / 无归属
     * @return 注册了几条（0 = 这个实例不是扩展点，或重复注册）
     */
    public int register(String owner, Object impl) {
        if (impl == null) return 0;
        if (same(impl)) return 0;                 // 同一个实例重复注册：幂等（重扫时忘了先摘也不至于挂两遍）
        String o = owner == null ? "" : owner;
        int n = 0;
        if (impl instanceof ContextProvider) {
            providers.add(new Entry<ContextProvider>((ContextProvider) impl, o, orderOf(impl)));
            n++;
        }
        if (impl instanceof OutboundStage) {
            stages.add(new Entry<OutboundStage>((OutboundStage) impl, o, orderOf(impl)));
            n++;
        }
        if (impl instanceof TriggerVoter) {
            voters.add(new Entry<TriggerVoter>((TriggerVoter) impl, o, orderOf(impl)));
            n++;
        }
        if (impl instanceof LifecycleHook) {
            hooks.add(new Entry<LifecycleHook>((LifecycleHook) impl, o, orderOf(impl)));
            n++;
        }
        if (n > 0) count.addAndGet(n);
        return n;
    }

    /**
     * 按插件名<b>整体摘掉</b>它的全部扩展点（卸载 / 重扫前调用）。
     *
     * @return 摘掉了几条
     */
    public int unregister(String owner) {
        String o = owner == null ? "" : owner;
        int n = 0;
        n += removeFrom(providers, o);
        n += removeFrom(stages, o);
        n += removeFrom(voters, o);
        n += removeFrom(hooks, o);
        if (n > 0) count.addAndGet(-n);
        return n;
    }

    /** 全部摘掉（收尾用）。 */
    public void clear() {
        providers.clear();
        stages.clear();
        voters.clear();
        hooks.clear();
        count.set(0);
    }

    /** 关掉扩展点线程池（基板收尾时调；没有挂过扩展点 = 从来没建过池）。 */
    public void close() {
        ThreadPoolExecutor p = pool;
        pool = null;
        if (p != null) {
            try {
                p.shutdownNow();
            } catch (Throwable ignored) {
            }
        }
    }

    private static <T> int removeFrom(CopyOnWriteArrayList<Entry<T>> list, String owner) {
        int n = 0;
        for (Entry<T> e : list) {
            if (e.owner.equals(owner) && list.remove(e)) n++;
        }
        return n;
    }

    /** 同一个实例已经在表里了（身份比较）。 */
    private boolean same(Object impl) {
        for (Entry<ContextProvider> e : providers) if (e.impl == impl) return true;
        for (Entry<OutboundStage> e : stages) if (e.impl == impl) return true;
        for (Entry<TriggerVoter> e : voters) if (e.impl == impl) return true;
        for (Entry<LifecycleHook> e : hooks) if (e.impl == impl) return true;
        return false;
    }

    /** 注册时取一次 order 并兜异常（见 {@link Entry} 的注释）。 */
    private static int orderOf(Object impl) {
        try {
            if (impl instanceof ContextProvider) return ((ContextProvider) impl).order();
            if (impl instanceof OutboundStage) return ((OutboundStage) impl).order();
            if (impl instanceof TriggerVoter) return ((TriggerVoter) impl).order();
            if (impl instanceof LifecycleHook) return ((LifecycleHook) impl).order();
        } catch (Throwable ignored) {
            // order() 抛异常的插件：按默认序排（0），不让注册这一步失败
        }
        return 0;
    }

    // ================================================================ 排序列举

    private static final Comparator<Entry<?>> BY_ORDER = new Comparator<Entry<?>>() {
        @Override
        public int compare(Entry<?> a, Entry<?> b) {
            if (a.order != b.order) return a.order < b.order ? -1 : 1;
            // 同序按插件名：用 String.compareTo（不是 compareToIgnoreCase）—— 顺序要逐字节可复现，
            // 忽略大小写的比较在中文/混合名上反而不直观，也依赖 Locale。
            return a.label().compareTo(b.label());
        }
    };

    private static <T> List<Entry<T>> sorted(CopyOnWriteArrayList<Entry<T>> list) {
        List<Entry<T>> l = new ArrayList<Entry<T>>(list);
        Collections.sort(l, BY_ORDER);
        return l;
    }

    public List<Entry<ContextProvider>> providers() { return sorted(providers); }

    public List<Entry<OutboundStage>> stages() { return sorted(stages); }

    public List<Entry<TriggerVoter>> voters() { return sorted(voters); }

    public List<Entry<LifecycleHook>> hooks() { return sorted(hooks); }

    // ================================================================ 上下文

    /**
     * 造一个扩展点上下文（每个扩展点各造一个：{@link BuildContext#host()} 要绑到<b>它自己</b>的插件上，
     * 插件才知道自己的文件夹在哪）。
     */
    public BuildContext context(String owner, Caller c, Turn t, String text) {
        return new BuildContext(c, text, t, hostOf(owner, c, t));
    }

    /** 绑定某个插件的只读能力面；绑不上（没有 Hosts、或它抛异常）返回 null —— 插件必须容忍 null。 */
    private Host hostOf(String owner, Caller c, Turn t) {
        Hosts hs = hosts;
        if (hs == null) return null;
        try {
            return hs.hostFor(owner == null ? "" : owner, c, t);
        } catch (Throwable e) {
            if (out != null) out.warn("[ext] 宿主绑定失败 owner=" + owner + " err=" + e);
            return null;
        }
    }

    // ================================================================ 装配：provider 块

    /**
     * 产出这一轮的全部 provider 块（已排序、已逐段截断、已按全局预算裁过）。
     *
     * <p>零 provider 时返回空表，调用方一个字节都不多拼。</p>
     */
    public List<Block> blocks(final Caller c, final Turn t, final String text) {
        List<Block> outB = new ArrayList<Block>();
        if (!on()) return outB;
        List<Entry<ContextProvider>> list = providers();
        if (list.isEmpty()) return outB;

        for (final Entry<ContextProvider> e : list) {
            final BuildContext b = context(e.owner, c, t, text);
            Prod p = guarded(e, c, new Callable<Prod>() {
                @Override
                public Prod call() {
                    // 串行化：扩展点实例是长活的（见类注释）
                    synchronized (e.impl) {
                        if (!e.impl.appliesTo(b)) return Prod.SKIP;
                        Prod r = new Prod();
                        r.stable = e.impl.stable();
                        r.max = e.impl.maxChars();
                        String s = e.impl.build(b);
                        r.text = s == null ? "" : s.trim();
                        return r;
                    }
                }
            });
            if (p == null || p == Prod.SKIP) continue;          // 超时/异常/不适用 → 这一段不出现
            if (p.text.isEmpty()) continue;                     // 空串 = 这一段不出现（正常，不是错）
            String s = p.text;
            int max = p.max;
            if (max > 0 && s.length() > max) {
                // 逐段预算：丢尾巴（与偏好块的截断口径一致 —— 保留开头的信息量最大）
                s = s.substring(0, max);
                e.drops.incrementAndGet();
            }
            e.chars.addAndGet(s.length());
            e.lastChars = s.length();
            outB.add(new Block(e, s, p.stable));
            diag("provider", e);
        }

        // 全局预算：超出就从 order 最大的开始**整段**丢（不是截一半 —— 半截上下文比没有更坏）
        int budget = budgetChars();
        if (budget > 0) {
            int total = 0;
            for (Block x : outB) total += x.text.length();
            if (total > budget) {
                List<Block> dropped = new ArrayList<Block>();
                for (int i = outB.size() - 1; i >= 0 && total > budget; i--) {
                    Block x = outB.get(i);
                    dropped.add(x);
                    total -= x.text.length();
                }
                outB.removeAll(dropped);
                budgetDrops.addAndGet(dropped.size());
                for (Block x : dropped) if (x.src != null) x.src.drops.incrementAndGet();
                if (out != null) {
                    out.warn("[ext] budget over budget=" + budget + " kept=" + total
                            + " dropped=" + dropped.size() + " names=" + joinOwners(dropped));
                }
            }
        }
        return outB;
    }

    /**
     * provider 的一次产出。
     * <p>{@link #SKIP} = {@code appliesTo} 说不适用（正常跳过，不是错也不是丢）；
     * {@code guarded} 返回 {@code null} 才是"超时/抛异常，这一段丢了"。</p>
     */
    private static final class Prod {
        static final Prod SKIP = new Prod();
        String text = "";
        boolean stable = false;
        int max = 0;
    }

    // ================================================================ 触发投票

    /**
     * 问一遍 voter（按 {@code order, 插件名}），返回第一个非 ABSTAIN 的票。
     *
     * @return {@code null} = 没有任何插件表态（网关据此沿用原有的地址规则）
     */
    public Verdict vote(final Ev ev, final Caller c) {
        if (!on()) return null;
        List<Entry<TriggerVoter>> list = voters();
        for (final Entry<TriggerVoter> e : list) {
            Verdict v = guarded(e, c, new Callable<Verdict>() {
                @Override
                public Verdict call() {
                    synchronized (e.impl) {
                        TriggerVoter.Vote v = e.impl.vote(ev, c);
                        if (v == null || v == TriggerVoter.Vote.ABSTAIN) return null;
                        String rs = "plugin";
                        try {
                            String r = e.impl.reason();
                            if (r != null && !r.trim().isEmpty()) rs = r.trim();
                        } catch (Throwable ignored) {
                            // reason() 抛异常不影响这一票本身
                        }
                        return new Verdict(v, rs, e.label());
                    }
                }
            });
            if (v != null) {
                diag("voter", e);
                return v;
            }
        }
        return null;
    }

    // ================================================================ 出站管线

    /**
     * 跑一遍出站管线（<b>分段之后、发送之前</b>）。
     *
     * <pre>
     *   逐条：被 veto 的丢掉 → transform 改正文（改没了也丢掉）
     *   收尾：还有话要发时，收一次 extra（追加消息），并按 sp 用同一把尺子分段
     * </pre>
     *
     * <p>零 stage 时原样返回 {@code parts}（同一个 List 对象）—— 调用方据此做到"行为逐字节不变"。</p>
     *
     * @param m     出站上下文（逐条复用；它的 {@code text} 会被本方法改）
     * @param parts 分段结果
     * @param sp    追加消息的分段口（null = 追加消息按原样一条一条发）
     */
    public Staged runStages(final Outbound m, List<String> parts, Splitter sp) {
        List<String> src = parts == null ? new ArrayList<String>() : parts;
        if (!onStages()) return new Staged(src, 0, 0, 0);
        List<Entry<OutboundStage>> list = stages();

        List<String> keep = new ArrayList<String>();
        int changed = 0;
        int dropped = 0;
        for (int i = 0; i < src.size(); i++) {
            m.text(src.get(i));
            m.pos(i, src.size());                             // 位置：逐条阶段是分段结果里的下标
            boolean kill = false;
            for (final Entry<OutboundStage> e : list) {
                m.host(hostOf(e.owner, m.caller(), null));     // 每个 stage 绑它自己插件的宿主（可为 null）
                Boolean v = guarded(e, m.caller(), new Callable<Boolean>() {
                    @Override
                    public Boolean call() {
                        synchronized (e.impl) {
                            return Boolean.valueOf(e.impl.veto(m));
                        }
                    }
                });
                if (v != null && v.booleanValue()) {
                    diag("stage", e);
                    kill = true;
                    break;
                }
            }
            if (kill) {
                dropped++;
                continue;
            }
            for (final Entry<OutboundStage> e : list) {
                m.host(hostOf(e.owner, m.caller(), null));
                String t = guarded(e, m.caller(), new Callable<String>() {
                    @Override
                    public String call() {
                        synchronized (e.impl) {
                            return e.impl.transform(m);
                        }
                    }
                });
                if (t == null) continue;                  // null = 不改
                if (!t.equals(m.text())) changed++;
                m.text(t);
                e.lastChars = m.text().length();
                diag("stage", e);
                // 一旦被清洗成空，这条就到此为止：后面的 stage 不再看它。
                // 否则"清洗掉 AI 味之后什么都不剩"会被下一个 stage（比如追加装饰）重新变回非空、
                // 照样发出去 —— 插件之间会为了"这条到底该不该存在"互相打架，结果由 order 随机决定。
                if (t.trim().isEmpty()) break;
            }
            String cur = m.text() == null ? "" : m.text().trim();
            if (cur.isEmpty()) {                          // 清洗成空 = 这条不发
                dropped++;
                continue;
            }
            keep.add(cur);
        }

        // 追加消息：只在"确实还有话要发"的时候收一次。
        // 整条都被否决时再追一张表情包是自相矛盾的；而"逐条都收一次"会把三段的回复追加成三张表情包。
        List<String> extra = new ArrayList<String>();
        if (!keep.isEmpty()) {
            for (final Entry<OutboundStage> e : list) {
                m.host(hostOf(e.owner, m.caller(), null));
                List<String> x = guarded(e, m.caller(), new Callable<List<String>>() {
                    @Override
                    public List<String> call() {
                        synchronized (e.impl) {
                            return e.impl.extra(m);
                        }
                    }
                });
                if (x == null) continue;
                for (String s : x) {
                    if (s == null || s.trim().isEmpty()) continue;
                    String one = s.trim();
                    List<String> split = sp == null ? null : sp.split(one);
                    if (split == null || split.isEmpty()) extra.add(one);
                    else extra.addAll(split);
                }
                e.lastChars = extra.size();
                diag("stage", e);
            }
        }

        // 追加的消息**不再过 stage**：否则插件之间会互相追加、无限繁殖。
        List<String> all = new ArrayList<String>(keep.size() + extra.size());
        all.addAll(keep);
        all.addAll(extra);

        // 整批收尾：有些事天然是"整条回复"级别的（首条加 @ 提问者、末条补标点），逐条口做不对 ——
        // 逐条时看不到后面哪条会被别的 stage 丢掉或洗空，"首条"就会挂到一条根本没发的消息上。
        // 位置口径：这一阶段 index 指向最后一条、total = 最终条数（含追加消息）。
        if (!all.isEmpty()) {
            m.pos(all.size() - 1, all.size());
            for (final Entry<OutboundStage> e : list) {
                m.host(hostOf(e.owner, m.caller(), null));
                final List<String> cur = all;
                List<String> f = guarded(e, m.caller(), new Callable<List<String>>() {
                    @Override
                    public List<String> call() {
                        synchronized (e.impl) {
                            return e.impl.finish(cur, m);
                        }
                    }
                });
                if (f == null) continue;                       // null = 不改
                List<String> cleaned = new ArrayList<String>(f.size());
                for (String s : f) {
                    if (s == null || s.trim().isEmpty()) continue;
                    cleaned.add(s.trim());
                }
                all = cleaned;
                e.lastChars = cleaned.size();
                diag("stage", e);
            }
        }
        return new Staged(all, changed, dropped, extra.size());
    }

    // ================================================================ 生命周期

    /** 回合开始（装配完、还没跑）。 */
    public void turnStart(Caller c, Turn t, String text) {
        if (!on()) return;
        for (final Entry<LifecycleHook> e : hooks()) {
            final BuildContext b = context(e.owner, c, t, text);
            e.lastChars = text == null ? 0 : text.length();
            guarded(e, c, new Callable<Object>() {
                @Override
                public Object call() {
                    synchronized (e.impl) {
                        e.impl.onTurnStart(b);
                    }
                    return null;
                }
            });
            diag("hook", e);
        }
    }

    /** 回合结束（成功与失败都通知一次）。 */
    public void turnEnd(Caller c, Turn t, String reply, String error) {
        if (!on()) return;
        final String r = reply == null ? "" : reply;
        final String er = error == null ? "" : error;
        for (final Entry<LifecycleHook> e : hooks()) {
            final BuildContext b = context(e.owner, c, t, r);
            e.lastChars = r.length();
            guarded(e, c, new Callable<Object>() {
                @Override
                public Object call() {
                    synchronized (e.impl) {
                        e.impl.onTurnEnd(b, r, er);
                    }
                    return null;
                }
            });
            diag("hook", e);
        }
    }

    /** 即将把正文交给落点（含失败兜底文案）。 */
    public void send(Caller c, Turn t, String text) {
        if (!on()) return;
        for (final Entry<LifecycleHook> e : hooks()) {
            final BuildContext b = context(e.owner, c, t, text);
            e.lastChars = text == null ? 0 : text.length();
            guarded(e, c, new Callable<Object>() {
                @Override
                public Object call() {
                    synchronized (e.impl) {
                        e.impl.onSend(b);
                    }
                    return null;
                }
            });
            diag("hook", e);
        }
    }

    // ================================================================ 护栏：超时 + 异常隔离

    /**
     * 在一次独立超时里跑一段插件代码。
     *
     * @return 正常返回值；超时 / 抛异常 / 池满且内联也炸 → {@code null}（调用方一律当"丢这一条"）
     */
    private <T> T guarded(Entry<?> e, Callable<T> body) {
        return guarded(e, null, body);
    }

    /**
     * 在一次独立超时里跑一段插件代码，并把<b>判定主体</b>绑进执行线程。
     *
     * <p><b>为什么必须绑</b>：插件手里的 {@code Host.napcat()} 是<b>带闸门</b>的，闸门读的是线程绑定的
     * {@link sair.v4.ctx.Ctx#caller()}（{@code Boot} 的 Guard：没有主体就不再放行，见下）。扩展点跑在
     * 基板自己的池线程上，不绑的话插件内部发的敏感动作（例如 {@code get_group_member_list}，
     * 出厂档位 {@code AFFECTION:300}）就<b>绕过了权限判定</b>。</p>
     *
     * <p><b>没有触发者时绑 SYSTEM</b>（{@link Caller#systemActor(long)}）：扩展点多半是"她自己的自主行为"
     * （出站改写、落点补全、定时唤醒）。旧口径是"传 {@code null} = 基板内部动作 = 放行"，那正是
     * {@code notes/acl-impl-plan.md} §2.1(a) 点名的那条绕过 —— 新模型里 {@code null} 不再意味着放行，
     * 所以这里显式给出主体：A:{@code R}、B:{@code RW}、C:{@code RWX}、E:{@code X}。</p>
     *
     * <p><b>这不等于"扩展点本身按权限裁定"</b>：上下文档产不产出、段落长什么样，仍由插件自己决定
     * （基板不拿权限去裁扩展点的内容）；变的只是"它内部发出去的动作按谁的位复核"。</p>
     *
     * @param c 触发者（{@code null} = 没有触发者，按 SYSTEM 主体判）
     */
    private <T> T guarded(Entry<?> e, final Caller c, final Callable<T> body) {
        // 绑定必须在**执行线程**上做（池线程 ≠ 调用线程），所以包一层而不在外面 bind。
        final Caller actor = c != null ? c : Caller.systemActor(conf == null ? 0L : conf.masterQQ());
        final Callable<T> bound = new Callable<T>() {
            @Override
            public T call() throws Exception {
                sair.v4.ctx.Ctx.Scope sc = sair.v4.ctx.Ctx.of(actor);
                try {
                    return body.call();
                } finally {
                    sc.close();
                }
            }
        };
        e.calls.incrementAndGet();
        long ms = timeoutMs();
        if (ms <= 0L) {
            // 配成"不超时"= 调用线程直接跑（不建池、不换线程）；这是给"我很信任这些插件"的场合留的后门
            long t0 = System.nanoTime();
            try {
                return bound.call();
            } catch (Throwable t) {
                fail(e, t);
                return null;
            } finally {
                e.totalMs.addAndGet((System.nanoTime() - t0) / 1000000L);
            }
        }
        Future<T> f;
        try {
            f = pool().submit(bound);
        } catch (Throwable t) {
            // 池满 = 4 个槽全被"超时后还在跑"的插件占着 → caller-runs（宁可慢，也不静默丢段）
            if (out != null) out.warn("[ext] pool full name=" + e.label() + "（本段改为调用线程直接跑，无超时）");
            long t0 = System.nanoTime();
            try {
                return bound.call();
            } catch (Throwable t2) {
                fail(e, t2);
                return null;
            } finally {
                e.totalMs.addAndGet((System.nanoTime() - t0) / 1000000L);
            }
        }
        long t0 = System.nanoTime();
        try {
            T r = f.get(ms, TimeUnit.MILLISECONDS);
            long dt = (System.nanoTime() - t0) / 1000000L;
            e.lastMs = dt;
            e.totalMs.addAndGet(dt);
            return r;
        } catch (TimeoutException te) {
            f.cancel(true);                     // 尽力打断；插件不理中断也无所谓（线程是守护线程）
            e.timeouts.incrementAndGet();
            if (out != null) out.warn("[ext] timeout name=" + e.label() + " ms=" + ms);
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            f.cancel(true);
            e.timeouts.incrementAndGet();
            if (out != null) out.warn("[ext] interrupted name=" + e.label());
            return null;
        } catch (ExecutionException ee) {
            fail(e, ee.getCause() == null ? ee : ee.getCause());
            return null;
        } catch (Throwable t) {
            fail(e, t);
            return null;
        }
    }

    /** 异常隔离：只记一行，只丢它自己。 */
    private void fail(Entry<?> e, Throwable t) {
        if (e != null) e.errors.incrementAndGet();
        if (out != null) out.err("[ext] error name=" + (e == null ? "?" : e.label()) + " err=" + t);
    }

    /** 有界扩展点工作线程池（懒建；守护线程，不挡进程退出）。 */
    private ThreadPoolExecutor pool() {
        ThreadPoolExecutor p = pool;
        if (p != null) return p;
        synchronized (this) {
            if (pool == null) {
                pool = new ThreadPoolExecutor(0, WORKERS, 30L, TimeUnit.SECONDS,
                        new SynchronousQueue<Runnable>(),
                        new ThreadFactory() {
                            private final AtomicInteger n = new AtomicInteger();

                            @Override
                            public Thread newThread(Runnable r) {
                                Thread t = new Thread(r, "v4-ext-" + n.incrementAndGet());
                                t.setDaemon(true);
                                return t;
                            }
                        },
                        new ThreadPoolExecutor.AbortPolicy());
            }
            return pool;
        }
    }

    // ================================================================ 诊断

    /** 一行扩展点事实（受 {@code logConsole} 的 {@code ext} 类别控制 —— 默认不打）。 */
    private void diag(String kind, Entry<?> e) {
        if (out == null || e == null) return;
        boolean on;
        try {
            on = conf != null && conf.logOn("ext");
        } catch (Throwable t) {
            on = false;
        }
        if (!on) return;
        out.dim("[ext] " + kind + "=" + e.label() + " chars=" + e.lastChars + " ms=" + e.lastMs);
    }

    /** 逐条扩展点的可见面（status.ext.items）。 */
    private static void item(JsonArray arr, String kind, Entry<?> e) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind);
        o.addProperty("owner", e.owner);
        o.addProperty("impl", e.simple);
        o.addProperty("order", e.order);
        o.addProperty("calls", e.calls());
        o.addProperty("errors", e.errors());
        o.addProperty("timeouts", e.timeouts());
        o.addProperty("drops", e.drops());
        o.addProperty("chars", e.chars());
        o.addProperty("ms_total", e.totalMs());
        o.addProperty("ms_last", e.lastMs());
        arr.add(o);
    }

    /**
     * 状态面（{@code status.ext}）：每个插件的贡献与耗时一行一条。
     * <p>这是"外挂越多，这里越重要"的那一面：出了体感问题，先看谁慢、谁大、谁在报错。</p>
     */
    public JsonObject stat() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled());
        o.addProperty("attached", any());
        o.addProperty("timeout_ms", timeoutMs());
        o.addProperty("ctx_budget", budgetChars());
        o.addProperty("budget_drops", budgetDrops.get());
        JsonArray arr = new JsonArray();
        for (Entry<ContextProvider> e : providers()) item(arr, "provider", e);
        for (Entry<OutboundStage> e : stages()) item(arr, "stage", e);
        for (Entry<TriggerVoter> e : voters()) item(arr, "voter", e);
        for (Entry<LifecycleHook> e : hooks()) item(arr, "hook", e);
        o.addProperty("providers", providers.size());
        o.addProperty("stages", stages.size());
        o.addProperty("voters", voters.size());
        o.addProperty("hooks", hooks.size());
        o.add("items", arr);
        return o;
    }

    private static String joinOwners(List<Block> l) {
        StringBuilder sb = new StringBuilder();
        for (Block b : l) {
            if (sb.length() > 0) sb.append(",");
            sb.append(b.owner.isEmpty() ? b.text.length() + "chars" : b.owner);
        }
        return sb.toString();
    }
}
