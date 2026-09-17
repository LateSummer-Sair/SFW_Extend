package sair.v4.term;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import com.google.gson.JsonObject;

import sair.v4.Conf;
import sair.v4.kit.Str;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.user.PrintRunnable;

/**
 * SFW 控制台捕获器（基板⑨ 的「读」半边）：<b>把控制台当成一个可读、可定位的信息源</b>。
 *
 * <h3>为什么需要它</h3>
 * <p>{@code SairCons.getConsoleText()} 只能拿"当前可见全文"：一旦有人 {@code /clear}、
 * 或者控制台按内存预算裁掉最旧一半，那段输出就<b>再也拿不回来</b>；而且每轮把整段控制台塞进
 * 上下文既贵又重复。本类因此提供三层读法：</p>
 * <ol>
 *   <li><b>流式捕获</b>：{@link SairCons#addPrintRunnable(String, PrintRunnable)} 注册一个打印代理，
 *       每条输出（<b>去色</b>、带时间戳与来源线程）写进<b>有界环形缓冲</b>
 *       （条数 + 字符数双上限，默认 {@value #DEF_ITEMS} 条 / {@value #DEF_CHARS} 字符）。
 *       缓冲在进程内独立于控制台文档，所以 {@code /clear} 之后仍然读得到最近输出；</li>
 *   <li><b>全量读取</b>：{@link SairCons#getConsoleText()}（当前可见全文，带长度上限与截断）；
 *       这是补充，不是主路径（见 {@link #visibleText(int)}）；</li>
 *   <li><b>游标读取</b>：每条缓冲带自增 {@code seq}，{@link #read(long, int, String)} 只回
 *       {@code seq > since_seq} 的新增部分 —— 调用方记住 {@link #cursor()} 即可，
 *       不会每轮重复吃同一段输出。</li>
 * </ol>
 *
 * <h3>线程模型（与框架一致：不用 EDT）</h3>
 * <p>框架是「调用线程直接执行 + {@code ConsFrame.printLock}」，{@code runOnEDT} 已随 EDT 调度整体弃用。
 * 所以本类：<b>绝不调用 {@code SwingUtilities}/{@code invokeLater}/{@code invokeAndWait}</b>，
 * 回调发生在打印它的那条线程上，捕获走自己的锁（{@link #LOCK}），不碰 Swing 文档锁。</p>
 *
 * <h3>「打印代理」的硬约束（必须写清楚，否则会把控制台弄空）</h3>
 * <p>{@code SairCons.insertPrinto} 的实现是：<b>只要注册了任意一个代理，框架自己就不再打印</b>，
 * 而是把这条输出交给每个代理，由代理负责真正写进控制台 —— 这是框架的既定契约
 * （旧插件 {@code AiAgent_TitleFix} 就是这么转发 {@code ConsFrame.printo} 的）。因此：</p>
 * <ul>
 *   <li>本捕获器在转发模式下会调用 {@code ConsFrame.printo(index, c, info)} 把这条输出照原样打出去；</li>
 *   <li>转发档位见 {@code consoleTapForward}：{@code auto}（默认）<b>只在自己是唯一代理时转发</b> ——
 *       若还挂着别的代理（例如旧插件的转发代理），就只捕获、不重复打印，避免同一行出现两次；
 *       {@code always} 总是转发；{@code never} 从不转发（此时控制台只能靠别的代理打印）。</li>
 *   <li>副作用（框架行为，与本类无关）：只要有代理，框架会把窗口标题设成它自己的那串提示
 *       （{@code SairCons} 里 {@code setTitleInfo} 那一行，在 {@code runAgo} <b>之后</b>执行）。</li>
 * </ul>
 *
 * <h3>无 GUI / 无 SFW 时</h3>
 * <p>{@code ConsFrame} 是类加载即 {@code new} 窗体的单例：无界面环境里它的静态初始化会失败
 * （{@code HeadlessException} → {@code ExceptionInInitializerError}，之后是 {@code NoClassDefFoundError}）。
 * 本类所有入口都 {@code catch (Throwable)}：装不上就 {@code installed=false}，
 * 读返回空、写返回一句可读的失败原因，<b>绝不把异常抛给基板</b>。</p>
 *
 * <p>配置键（每次用到时读，改完即时生效）：{@code consoleTapEnabled}(默认开) /
 * {@code consoleTapItems}(默认 {@value #DEF_ITEMS}) / {@code consoleTapChars}(默认 {@value #DEF_CHARS}) /
 * {@code consoleTapForward}(默认 {@code auto}) / {@code consoleTapReadChars}(默认 {@value #DEF_READ_CHARS})。</p>
 */
public final class ConsoleTap {

    private ConsoleTap() { }

    // ==================== 常量与状态 ====================

    /** 打印代理标识（{@code addPrintRunnable} 的键；重复注册会被框架拒绝 = 天然幂等）。 */
    public static final String PROXY_ID = "AiAgentV4_ConsoleTap";

    public static final int DEF_ITEMS = 500;
    public static final int DEF_CHARS = 64 * 1024;
    public static final int DEF_READ_CHARS = 4000;

    /** 转发档位：{@code auto}（唯一代理才转发）/ {@code always} / {@code never}。 */
    public static final String FORWARD_AUTO = "auto";
    public static final String FORWARD_ALWAYS = "always";
    public static final String FORWARD_NEVER = "never";

    /** 读结果里"因为超预算没回"的标注用；事实化，不做任何解释性文案。 */
    private static final String ELLIPSIS = "…";

    private static final Object LOCK = new Object();

    /** 环形缓冲（按 seq 升序）。 */
    private static final ArrayDeque<Line> RING = new ArrayDeque<Line>();

    /** 最新 seq（volatile 镜像，便于免锁读游标）。 */
    private static volatile long seqTail = 0L;
    /** 当前缓冲字符数。 */
    private static long chard = 0L;
    /** 历史累计接收条数（不因淘汰而减少）。 */
    private static long captured = 0L;
    /** 因超预算被挤掉的条数（累计）。 */
    private static long dropped = 0L;

    private static int maxItems = DEF_ITEMS;
    private static int maxChars = DEF_CHARS;
    private static int readChars = DEF_READ_CHARS;

    /** 转发档位（volatile：打印线程读）。 */
    private static volatile String forward = FORWARD_AUTO;

    /** 当前捕获源（null = 没装）。 */
    private static volatile Source source;
    /** 最近一次套用过的配置（{@code install(null)} 时沿用它 —— 免得"顺手补装"把主人关掉的开关又打开）。 */
    private static volatile Conf lastConf;
    /** 最近一次失败原因（诊断用，非文案）。 */
    private static volatile String lastError = "";
    /** SFW 控制台可用性探测结果（类初始化失败是<b>永久</b>的，所以结果可缓存）。 */
    private static volatile Boolean sfwUp;
    /** 可用性探测失败的原因（探测时记一次，{@link #reset()} 不清 —— 这是"这个环境为什么没有控制台"的答案）。 */
    private static volatile String probeError = "";

    // ==================== 数据结构 ====================

    /** 一条捕获到的输出。 */
    public static final class Line {

        /** 自增序号（游标读取用；从 1 开始）。 */
        public final long seq;
        /** 打印时刻（epoch 毫秒）。 */
        public final long ms;
        /** 文本（已去色/去控制字符，保留原换行）。 */
        public final String text;
        /** 语义色标记（{@code ""}/dim/ok/warn/err/title）；来源是颜色映射，不是猜的。 */
        public final String tone;
        /** 来源线程名（谁打的）；空串 = 未知。 */
        public final String thread;

        Line(long seq, long ms, String text, String tone, String thread) {
            this.seq = seq;
            this.ms = ms;
            this.text = text;
            this.tone = tone;
            this.thread = thread;
        }
    }

    /**
     * 捕获源：真源是"SFW 打印代理"，测试/探针可注入假源（{@link #useSource(Source)}）。
     * <p>实现只需保证：{@link #install()} 幂等、{@link #uninstall()} 幂等、两者都不抛异常。</p>
     */
    public interface Source {

        /** 源名字（进事实行，如 {@code sfw-print-proxy}）。 */
        String name();

        /** 现在是否挂着。 */
        boolean installed();

        /** 挂上（幂等）；返回 false = 当前环境装不上（无 GUI 等）。 */
        boolean install();

        /** 摘下（幂等）。 */
        void uninstall();
    }

    // ==================== 安装 / 注销 ====================

    /**
     * 装捕获器（幂等）：读配置 → 需要就注册 SFW 打印代理。
     *
     * @param conf 配置（可为 null = 用默认上限；装配早期就是这种情况）
     * @return true = 现在处于「已挂上」状态（已经挂着也算 true）
     */
    public static boolean install(Conf conf) {
        return install(conf, false);
    }

    /**
     * 装捕获器（幂等）。
     *
     * @param conf 配置（可为 null = 用默认上限；装配早期就是这种情况）
     * @param hold 装上后是否先"扣住"输出 —— <b>★当前没有任何调用点传 {@code true}</b>
     *             （甲方令 T12-R3d：构造期那处 {@code install(null,true)} 已整段删除，
     *             {@link SfwPrintSource#install()} 里的 {@code beginHold()} 也已删除）⇒
     *             {@code hold} 恒为 {@code false}，扣住机制运行期不可达。
     *             保留形参是因为 {@link #beginHold()} 这套代码还在（留而不触发）。
     *             历史口径：只有真机启动阶段（插件构造期）曾传 true，那段时间框架的 {@code autorun.ir}
     *             正在跑、谁都不能碰控制台文档 —— <b>这个做法已被判为"劫持框架控制台"，不许再用</b>。
     * @return true = 现在处于「已挂上」状态（已经挂着也算 true）
     */
    public static boolean install(Conf conf, boolean hold) {
        try {
            if (conf != null) lastConf = conf;
            Conf c = conf != null ? conf : lastConf;
            applyLimits(c);
            // 注意：这里**不看 consoleTapEnabled**。那个开关只决定"记不记进环形缓冲"（见 accept），
            // 代理本身必须一直挂着 —— 挂着才有"控制台输出只在 EDT 落盘"这条保证；
            // 不挂的话框架会自己在打印线程上直插文档，又会回到"并发写坏视图树 → 界面卡死"那条老路。
            Source s = source;
            if (s != null && s.installed()) {
                if (hold) beginHold();
                return true;
            }
            Source ns = new SfwPrintSource();
            boolean ok = ns.install();
            if (ok) {
                source = ns;
                lastError = "";
                if (hold) beginHold();       // 真机启动阶段：先扣住，等第一条命令再放行
            }
            return ok;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return false;
        }
    }

    /**
     * 顺手补装（{@link #run(String)} 用）：基板已经装配过捕获器时这是空操作；
     * 被 {@code Boot.stop()} 注销过、或装配早期还没轮到时，这里补上 —— 否则"跑一条命令再看它打了什么"
     * 就会永远拿到"这条命令没有往控制台打印"。
     * <p>{@code read}/{@code cursor} <b>不</b>补装：读不该改变注册状态（注销是 {@code Boot.stop()} 的决定）。</p>
     */
    private static void ensure() {
        try {
            if (!installed()) install(null);
        } catch (Throwable ignored) {
        }
    }

    /** 已经挂着吗（含注入的假源）。 */
    public static boolean installed() {
        Source s = source;
        try {
            return s != null && s.installed();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 注销捕获器（幂等）。<b>落点</b>：{@code Th.shutdown()}（= {@code Boot.stop()} 的静态收敛点），
     * 这样基板一停，框架的代理表里就不留悬挂 runnable。
     */
    public static void uninstall() {
        Source s = source;
        source = null;
        if (s == null) return;
        try {
            s.uninstall();
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        }
    }

    /** 注入捕获源（探针/测试用；不读配置）。返回是否装成功。 */
    public static boolean useSource(Source s) {
        uninstall();
        if (s == null) return false;
        try {
            boolean ok = s.install();
            if (ok) source = s;
            return ok;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return false;
        }
    }

    /** 清空缓冲与统计（探针用；不动已装的源）。 */
    public static void resetBuffer() {
        synchronized (LOCK) {
            RING.clear();
            chard = 0L;
            captured = 0L;
            dropped = 0L;
            seqTail = 0L;
        }
    }

    /** 全部恢复默认（探针用）：注销 + 清空 + 上限回默认。 */
    public static void reset() {
        uninstall();
        resetBuffer();
        maxItems = DEF_ITEMS;
        maxChars = DEF_CHARS;
        readChars = DEF_READ_CHARS;
        forward = FORWARD_AUTO;
        lastError = "";
    }

    /** 上限与开关（每次 install 读一次；{@link #refreshLimits(Conf)} 可在运行期再套用）。 */
    public static void refreshLimits(Conf conf) { applyLimits(conf); }

    private static boolean enabled(Conf conf) {
        try {
            return conf == null || conf.getBool("consoleTapEnabled", true);
        } catch (Throwable t) {
            return true;
        }
    }

    private static void applyLimits(Conf conf) {
        if (conf == null) return;
        try {
            int items = conf.getInt("consoleTapItems", DEF_ITEMS);
            int chars = conf.getInt("consoleTapChars", DEF_CHARS);
            int read = conf.getInt("consoleTapReadChars", DEF_READ_CHARS);
            String f = Str.lower(Str.trim(conf.get("consoleTapForward", FORWARD_AUTO)));
            // 口径：配置里写的值就是生效的值（只有 <=0 才回默认）——不做"悄悄抬到某个下限"，
            // 否则主人写了 4 却看到 8，会让"上限到底是多少"这件事没法核对。
            maxItems = items <= 0 ? DEF_ITEMS : items;
            maxChars = chars <= 0 ? DEF_CHARS : chars;
            readChars = read <= 0 ? DEF_READ_CHARS : read;
            forward = (FORWARD_ALWAYS.equals(f) || FORWARD_NEVER.equals(f)) ? f : FORWARD_AUTO;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        }
    }

    // ==================== 写入（捕获） ====================

    /**
     * 收一条输出（源调用）。<b>本方法绝不抛异常</b>，且必须便宜（在打印热路径上）。
     *
     * @param text   文本（null/空白照样记，空行也是事实）
     * @param tone   语义色标记（可为 null）
     * @param thread 来源线程名（可为 null）
     * @param src    源名（可为 null；只做诊断）
     */
    public static void accept(String text, String tone, String thread, String src) {
        try {
            long now = System.currentTimeMillis();
            String th = shortThread(thread);
            String tn = tone == null ? "" : tone;
            // 落盘（调试输出口 2660 的「一次取全部输出」）：与下面的环形缓冲开关**无关** ——
            // consoleTapEnabled 只管"内存里那个 500 行窗口记不记"，落盘是调试口自己的容量。
            // 没装 DebugLog（探针 / 调试口没开）时 enabled()=false：一个字节都不写，也不多调一次 stripColors。
            if (sair.v4.dev.DebugLog.enabled()) {
                sair.v4.dev.DebugLog.console(now, tn, th, stripColors(text));
            }
            // 捕获开关（consoleTapEnabled=false）：只影响"记不记"，不影响代理挂不挂（见 install 的说明）
            if (!enabled(lastConf)) return;
            if (text == null) text = "";
            String clean = stripColors(text);
            synchronized (LOCK) {
                if (clean.length() > maxChars) {
                    // 单条就超整个字符预算：留尾部（"现在在打什么"比开头重要），并如实标注
                    int cut = clean.length() - maxChars;
                    clean = ELLIPSIS + "(" + cut + " chars cut)\n" + clean.substring(cut);
                }
                Line l = new Line(++seqTail, now, clean, tn, th);
                RING.addLast(l);
                chard += clean.length();
                captured++;
                while (RING.size() > 1 && (RING.size() > maxItems || chard > maxChars)) {
                    Line old = RING.pollFirst();
                    if (old == null) break;
                    chard -= old.text.length();
                    dropped++;
                }
                if (chard < 0L) chard = 0L;
            }
        } catch (Throwable t) {
            // 捕获绝不能影响打印本身
        }
    }

    /** 去色：ANSI/CSI 转义 + 控制字符（保留 {@code \r\n\t}）。无 ESC 的常见路径只做一次 indexOf。 */
    static String stripColors(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s;
        if (out.indexOf('\u001B') >= 0) out = CSI.matcher(out).replaceAll("");
        if (hasCtl(out)) out = CTL.matcher(out).replaceAll("");
        return out;
    }

    private static boolean hasCtl(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\r' && c != '\n' && c != '\t') return true;
            if (c == 0x7F) return true;
        }
        return false;
    }

    private static final java.util.regex.Pattern CSI =
            java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[ -/]*[@-~]");
    private static final java.util.regex.Pattern CTL =
            java.util.regex.Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    /** 线程名取短（来源事实，最长 24 字符）。 */
    private static String shortThread(String name) {
        if (name == null) return "";
        String s = name.trim();
        if (s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length() && sb.length() < 24; i++) {
            char c = s.charAt(i);
            sb.append(c <= 0x20 ? '_' : c);
        }
        return sb.toString();
    }

    // ==================== 读取 ====================

    /** 最新 seq（游标：下一次读传它，就只拿新增）。没任何输出时返回 0。 */
    public static long cursor() { return seqTail; }

    /** 缓冲里最旧的 seq（空缓冲返回 0）。 */
    public static long head() {
        synchronized (LOCK) {
            Line l = RING.peekFirst();
            return l == null ? 0L : l.seq;
        }
    }

    /** 当前缓冲条数。 */
    public static int lines() {
        synchronized (LOCK) {
            return RING.size();
        }
    }

    /** 缓冲当前字符数。 */
    public static long chard() {
        synchronized (LOCK) {
            return chard;
        }
    }

    /** 历史累计接收条数。 */
    public static long captured() { return captured; }

    /** 因超预算被挤掉的条数（累计）。 */
    public static long dropped() { return dropped; }

    /** 默认读长度上限（配置 {@code consoleTapReadChars}）。 */
    public static int readChars() { return readChars; }

    /**
     * 游标读：只回 {@code seq > sinceSeq} 的条目。
     *
     * @param sinceSeq 上次读到的 seq（{@code <=0} = 从缓冲里最旧的一条开始）
     * @param maxChars 本次最多回多少字符（{@code <=0} = 用 {@link #readChars()}）；
     *                 装不下时<b>丢更旧的、留最近的</b>（"现在在打什么"优先）
     * @param filter   非空时只保留 text 含该串的条目（区分大小写，不做解释）
     * @return 文本行（{@code #seq 时间 [色] 线程 | 文本}）；没有新内容返回空串
     */
    public static String read(long sinceSeq, int maxChars, String filter) {
        List<Line> snap;
        synchronized (LOCK) {
            snap = new ArrayList<Line>(RING);
        }
        int budget = maxChars > 0 ? maxChars : readChars;
        String f = Str.blank(filter) ? null : filter;

        List<String> keep = new ArrayList<String>();
        int used = 0;
        for (int i = snap.size() - 1; i >= 0; i--) {
            Line l = snap.get(i);
            if (l.seq <= sinceSeq) continue;
            if (f != null && l.text.indexOf(f) < 0) continue;
            String row = format(l);
            if (!keep.isEmpty() && used + row.length() > budget) break;   // 装不下就更旧的不要
            keep.add(row);
            used += row.length();
        }
        if (keep.isEmpty()) return "";
        Collections.reverse(keep);
        StringBuilder sb = new StringBuilder(used + 16);
        for (String row : keep) sb.append(row);
        boolean more = false;
        long first = snap.isEmpty() ? 0L : snap.get(0).seq;
        if (sinceSeq > 0 && sinceSeq < first - 1) more = true;   // 游标之前有被挤掉的
        if (more) sb.append(ELLIPSIS).append("(seq ").append(sinceSeq + 1).append("..")
                .append(first - 1).append(" 已从缓冲淘汰)\n");
        return sb.toString();
    }

    /**
     * 事实行 + 正文（工具/技能直接用这个，模型自己判断"读到的是哪一段"）。
     *
     * <p><b>首行是 {@code [console] read ok=1 …}（B8，2026-09-16）</b>：read 这一族
     * <b>成败都带 {@code [console]} 前缀</b>，所以首行必须像 {@code run}/{@code size}/{@code history}
     * 一样把 {@code ok=1} 这个结构标记写在里面 —— 主循环的连续失败闸门靠它区分成败
     * （见 {@code Loop.failed}）。少了这个标记，每一次成功的 read 都会被算成失败。</p>
     */
    public static String readWithFacts(long sinceSeq, int maxChars, String filter) {
        String body = read(sinceSeq, maxChars, filter);
        return "[console] read ok=1 " + factsBody(sinceSeq)
                + (body.isEmpty() ? "\n" + ELLIPSIS + "(no new output)" : "\n" + body);
    }

    /**
     * 一行事实：条数 / seq 区间 / 缓冲占用 / 上限 / 累计 / 环境（{@code [console] lines=…}）。
     * <p>这是<b>事实正文</b>，不带 {@code ok=} 标记：它既当 read 的正文（那里由
     * {@link #readWithFacts} 在前面补 {@code [console] read ok=1 }），也当 {@code size} 的第二行
     * 和调试口的 status 行（那两处的首行各有自己的标记）。</p>
     */
    public static String facts(long sinceSeq) {
        return "[console] " + factsBody(sinceSeq);
    }

    /** {@link #facts(long)} 的正文部分（不含 {@code [console] } 前缀）—— 供 read 那一路套上 op+ok 标记。 */
    private static String factsBody(long sinceSeq) {
        long h = head();
        long t = seqTail;
        int n = lines();
        long ch;
        long cap, dr;
        synchronized (LOCK) {
            ch = chard;
            dr = dropped;
        }
        cap = captured;
        StringBuilder sb = new StringBuilder(160);
        sb.append("lines=").append(n)
          .append(" seq_head=").append(h)
          .append(" seq_tail=").append(t)
          .append(" chard=").append(ch)
          .append(" cap_lines=").append(maxItems)
          .append(" cap_chars=").append(maxChars)
          .append(" captured=").append(cap)
          .append(" dropped=").append(dr)
          .append(" since_seq=").append(sinceSeq)
          .append(" missed=").append(missed(sinceSeq, h))
          .append(" source=").append(sourceName())
          .append(" sfw=").append(available() ? 1 : 0)
          .append(" forward=").append(forward);
        return sb.toString();
    }

    /** 从 sinceSeq 之后被淘汰掉的条数（0 = 没丢；sinceSeq<=0 时无法判断，返回 -1）。 */
    private static long missed(long sinceSeq, long head) {
        if (sinceSeq <= 0) return -1L;
        if (head <= 0) return 0L;
        return sinceSeq < head - 1 ? (head - 1 - sinceSeq) : 0L;
    }

    /** 状态面（{@code status} 用）：captured / seq_head / seq_tail / chard / enabled + 若干事实。 */
    public static JsonObject status() {
        JsonObject o = new JsonObject();
        long h = head();
        long ch;
        long dr;
        synchronized (LOCK) {
            ch = chard;
            dr = dropped;
        }
        o.addProperty("captured", captured);
        o.addProperty("seq_head", h);
        o.addProperty("seq_tail", seqTail);
        o.addProperty("chard", ch);
        o.addProperty("enabled", installed());
        o.addProperty("lines", lines());
        o.addProperty("dropped", dr);
        o.addProperty("cap_lines", maxItems);
        o.addProperty("cap_chars", maxChars);
        o.addProperty("forward", forward);
        o.addProperty("source", sourceName());
        o.addProperty("sfw", available());
        // 注意：这里<b>不</b>报"可见全文长度"—— getConsoleText() 会把整份文档拷一份（上限 32M 字符），
        // 状态面不能有这种开销；要看规模用 console op=size（走 getConsoleSize()，O(1)）。
        int pane = -1;
        try {
            pane = SairCons.getConsoleSize();
        } catch (Throwable ignored) {
        }
        o.addProperty("pane_size", pane);
        if (!lastError.isEmpty()) o.addProperty("last_error", lastError);
        return o;
    }

    /**
     * 「当前可见全文」：{@link SairCons#getConsoleText()} 的安全包装（长度上限 + 保尾截断）。
     *
     * <p><b>开销要说清</b>：框架这个 API 就是 {@code cf.infoPane.getText()}，一次调用会把<b>整份文档</b>
     * 拷成字符串（文档上限见 {@code ConsFrame.MAX_CONSOLE_TEXT}／内存预算 {@code MAX_CONSOLE_MEMORY}=64MB，
     * 也就是最坏几十兆字符）。所以：热路径（每轮读）请用环形缓冲的 {@link #read(long, int, String)}，
     * 本方法只在"确实要现在屏幕上的全文"时按需调用，并且一定要给 {@code maxChars}。</p>
     *
     * @param maxChars {@code <=0} = 全部；否则只回最后 maxChars 个字符（前面加截断标注）
     * @return 控制台文本；控制台不可用时返回空串（不抛异常）
     */
    public static String visibleText(int maxChars) {
        String t;
        try {
            t = SairCons.getConsoleText();
        } catch (Throwable e) {
            lastError = String.valueOf(e);
            return "";
        }
        if (t == null) return "";
        if (maxChars > 0 && t.length() > maxChars) {
            return ELLIPSIS + "(" + (t.length() - maxChars) + " chars cut)\n" + t.substring(t.length() - maxChars);
        }
        return t;
    }

    // ==================== SFW 命令桥（runner / 历史 / 规模） ====================

    /**
     * 执行一条 SFW 框架命令，并回<b>这条命令打出来的新输出</b>。
     *
     * <p>口径：先记住 {@link #cursor()}，跑 {@link SairCons#runner(boolean, String)}
     * （{@code isMark=true}，与人在控制台敲一样进历史），再用 seq 游标读新增部分 ——
     * 这比"跑完 diff 两段 {@code getConsoleText}"稳（{@code /clear}、并发打印都不影响游标语义），
     * 也是旧实现最容易出错的地方。</p>
     *
     * @param cmd 完整命令串（如 {@code jj/at 1+/100}）
     * @return 事实行 {@code [console] run ok=0|1 …}，随后是该命令的新输出；<b>不抛异常</b>
     */
    public static String run(String cmd) {
        if (Str.blank(cmd)) return "[console] run ok=0 reason=need_cmd";
        if (!available()) {
            return "[console] run ok=0 reason=no_sfw_console error=" + Str.oneLine(why());
        }
        ensure();                       // 没挂捕获器就补上：不然拿不到这条命令的输出
        long before = cursor();
        long t0 = System.currentTimeMillis();
        Object r;
        try {
            r = runner(cmd);
        } catch (Throwable t) {
            return "[console] run ok=0 cmd=" + cmd + " ms=" + (System.currentTimeMillis() - t0)
                    + " error=" + Str.oneLine(String.valueOf(t));
        }
        long ms = System.currentTimeMillis() - t0;
        String body = read(before, readChars, null);
        StringBuilder sb = new StringBuilder(160);
        sb.append("[console] run ok=1 cmd=").append(cmd).append(" ms=").append(ms)
          .append(" return=").append(r == null ? "(null)" : Str.oneLine(String.valueOf(r)));
        if (body.isEmpty()) {
            sb.append("\n").append(ELLIPSIS).append("(这条命令没有往控制台打印)");
        } else {
            sb.append("\n").append(body);
        }
        return sb.toString();
    }

    /**
     * 执行一条框架命令（{@code isMark=true}，与人在控制台敲一样进历史）。
     *
     * <p><b>命令在 EDT 上跑</b>：框架命令里有些会直接动控制台文档（{@code /clear}、{@code dePrint}）
     * 甚至动窗口（{@code /hide}、{@code /resize}），从别的线程执行会踩到与"直印"同一类竞态
     * （实测把 EDT 抛死 → 界面点不动）。所以这里投递到 EDT 并等它跑完；已经在 EDT 上就直接跑，
     * 等不到（没 EDT 的环境）退回本线程。</p>
     */
    private static Object runner(final String cmd) throws Exception {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) return SairCons.runner(true, cmd);
        final Object[] box = new Object[1];
        final Throwable[] err = new Throwable[1];
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        box[0] = SairCons.runner(true, cmd);
                    } catch (Throwable t) {
                        err[0] = t;
                    } finally {
                        done.countDown();
                    }
                }
            });
            done.await(30000L, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            return SairCons.runner(true, cmd);       // 没有 EDT：退回调用线程（至少命令能跑）
        }
        if (err[0] != null) throw new Exception(String.valueOf(err[0]));
        return box[0];
    }

    /**
     * 命令历史（{@link SairCons#localRunnerHistory} 的最近 N 条；最新在后）。
     * 控制台的 {@code /clear} 会同时清掉它（框架语义）。
     */
    public static String history(int n) {
        int limit = n <= 0 ? 20 : Math.min(n, 200);
        List<String> all = new ArrayList<String>();
        try {
            synchronized (SairCons.localRunnerHistory) {
                all.addAll(SairCons.localRunnerHistory);
            }
        } catch (Throwable t) {
            return "[console] history ok=0 reason=no_sfw_console error=" + Str.oneLine(String.valueOf(t));
        }
        int from = Math.max(0, all.size() - limit);
        StringBuilder sb = new StringBuilder(128);
        sb.append("[console] history ok=1 total=").append(all.size())
          .append(" returned=").append(all.size() - from);
        for (int i = from; i < all.size(); i++) {
            sb.append("\n  ").append(i + 1).append(": ").append(all.get(i));
        }
        return sb.toString();
    }

    /** 控制台规模（{@link SairCons#getConsoleSize()}）+ 缓冲事实。 */
    public static String size() {
        int pane = -1;
        try {
            pane = SairCons.getConsoleSize();
        } catch (Throwable t) {
            return "[console] size ok=0 reason=no_sfw_console error=" + Str.oneLine(String.valueOf(t))
                    + "\n" + facts(cursor());
        }
        return "[console] size ok=1 pane=" + pane + "\n" + facts(cursor());
    }

    /** 清屏（框架 {@code /clear}：清文本 + 清命令历史；<b>不动</b>环形缓冲 —— 缓冲就是为"清屏后还读得到"存在的）。 */
    public static String clear() {
        if (!available()) return "[console] clear ok=0 reason=no_sfw_console";
        try {
            // 文档操作只能在 EDT 上做（见 run(String) 的说明）
            runner("/clear");
            return "[console] clear ok=1（缓冲仍保留 lines=" + lines() + "）";
        } catch (Throwable t) {
            return "[console] clear ok=0 error=" + Str.oneLine(String.valueOf(t));
        }
    }

    // ==================== SFW 可用性 ====================

    /**
     * SFW 控制台可用吗：读 {@code ConsFrame.cf} 触发类初始化（真机上它早就建好了）。
     * 无界面环境里这次探测会失败，结果<b>缓存</b>（类初始化失败是永久的），所以后续调用是零成本的。
     */
    public static boolean available() {
        Boolean c = sfwUp;
        if (c != null) return c.booleanValue();
        boolean ok;
        try {
            ok = ConsFrame.cf != null;
        } catch (Throwable t) {
            ok = false;
            lastError = String.valueOf(t);
            probeError = Str.oneLine(String.valueOf(t));
        }
        sfwUp = Boolean.valueOf(ok);
        return ok;
    }

    /** 「这个环境为什么没有控制台」：探测失败原因（可用时为空串）。 */
    public static String probeError() { return probeError; }

    /** 失败原因表述：最近的失败优先，其次探测原因（诊断用）。 */
    private static String why() {
        if (!lastError.isEmpty()) return lastError;
        return probeError.isEmpty() ? "no_sfw_console" : probeError;
    }

    /** 源名字（诊断）。 */
    public static String sourceName() {
        Source s = source;
        if (s == null) return "-";
        try {
            return Str.nz(s.name());
        } catch (Throwable t) {
            return "-";
        }
    }

    /** 最近一次失败原因（诊断；空串 = 没失败过）。 */
    public static String lastError() { return lastError; }

    // ==================== 格式化 ====================

    private static String format(Line l) {
        StringBuilder sb = new StringBuilder(l.text.length() + 40);
        sb.append('#').append(l.seq).append(' ').append(hms(l.ms));
        if (!l.tone.isEmpty()) sb.append(" [").append(l.tone).append(']');
        if (!l.thread.isEmpty()) sb.append(' ').append(l.thread);
        String body = l.text;
        int i = 0;
        while (i < body.length() && (body.charAt(i) == '\r' || body.charAt(i) == '\n')) i++;
        sb.append(" | ").append(body, i, body.length());
        if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
        return sb.toString();
    }

    private static final java.text.SimpleDateFormat HMS = new java.text.SimpleDateFormat("HH:mm:ss.SSS");

    private static String hms(long ms) {
        synchronized (HMS) {
            return HMS.format(new java.util.Date(ms));
        }
    }

    // ==================== 真源：SFW 打印代理 ====================

    /**
     * 真源：{@link SairCons#addPrintRunnable(String, PrintRunnable)} 注册的打印代理。
     * <p>捕获 + 按档位转发（见类注释的「硬约束」）。安装时先摘掉同 id 的旧实例：
     * 插件重载后旧类加载器的代理可能还挂在框架表里，{@code putIfAbsent} 会让新实例注册失败 ——
     * 先 remove 再 add 既避免"装不上"，也顺手解掉那个悬挂引用。</p>
     */
    static final class SfwPrintSource implements Source, PrintRunnable {

        private volatile boolean on = false;

        @Override
        public String name() { return "sfw-print-proxy"; }

        @Override
        public boolean installed() { return on; }

        @Override
        public boolean install() {
            if (on) return true;
            try {
                if (!available()) {
                    lastError = "no_sfw_console";
                    return false;
                }
                try {
                    SairCons.removePrintRunnable(PROXY_ID);
                } catch (Throwable ignored) {
                }
                boolean added = SairCons.addPrintRunnable(PROXY_ID, this);
                on = added;
                if (!added) lastError = "addPrintRunnable(" + PROXY_ID + ") 返回 false";
                // ★这里**不再**扣住（甲方令，T12-R3d）：扣住这机制只该服务"**我们自己的命令已经进来了**"
                // 的场景，**不许出现在任何框架自主启动的路径上** —— 代理是"装上了就生效"的，
                // 而"装上了"这件事本身不代表随后一定有我们的命令（不带外置 .ir 时永远没有）。
                // 原来这一行 `else beginHold();` 就是"启动脚本阶段一个字节都不插"的入口，已删除。
                diag("[diag] install id=" + PROXY_ID + " ok=" + added + " err=" + lastError);
                return added;
            } catch (Throwable t) {
                lastError = String.valueOf(t);
                on = false;
                return false;
            }
        }

        @Override
        public void uninstall() {
            if (!on) return;
            on = false;
            try {
                SairCons.removePrintRunnable(PROXY_ID);
            } catch (Throwable t) {
                lastError = String.valueOf(t);
            }
        }

        @Override
        public void run(Integer index, Color c, String info) {
            // ① 捕获（先记；转发是另一条路，失败也不影响已经拿到的这条）
            accept(info, toneOf(c), Thread.currentThread().getName(), "sfw-console");
            // ② 转发：注册了代理之后框架自己就不打印了，得有人替它打（见类注释）
            boolean fw = shouldForward();
            diag("[diag] recv len=" + (info == null ? -1 : info.length()) + " forward=" + fw
                    + " thread=" + Thread.currentThread().getName());
            if (!fw) return;
            if (index != null) forwardedWithIndex++;      // 诊断：框架/别的插件给了定位插入（我们只能追加）
            enqueuePrint(c, info);
        }

        /**
         * 该不该替框架打印：{@code never} 从不，其余档位（{@code auto} / {@code always}）都打。
         *
         * <p><b>为什么不再按 {@code listModel} 判"我是不是唯一代理"</b>（这是踩过的真坑）：
         * 那个字段是<b>已加载插件名列表</b>（{@code ConsFrame.java:166-169}
         * "已加载插件名列表模型(SairCons打印代理注册/移除时写入,列表渲染读取)"），
         * 代理 id 只是被框架顺手塞进同一个模型（{@code SairCons.java:81}）。
         * 本机 24 个插件名常驻其中 → 旧的 {@code size() <= 1} 判据<b>永远为假</b> →
         * 结果是"输出被我们捕获了、却一个字都没打进控制台"：注册代理后框架自己不再打印
         * （{@code SairCons.insertPrinto} 的 {@code hasAgo} 分支），于是<b>控制台在第一条命令之后彻底空白</b>。</p>
         *
         * <p>现在按事实判：整套安装里<b>只有我们注册打印代理</b>（plugins 下所有 jar 二进制全搜
         * {@code addPrintRunnable} 无第二处命中，框架自身也不注册）。所以默认档 = 转发。
         * 若将来真有第二个插件也注册转发代理，用 {@code consoleTapForward=never} 让我们闭嘴即可。</p>
         */
        private boolean shouldForward() {
            return !FORWARD_NEVER.equals(forward);
        }
    }

    // ==================== 落盘：EDT 单写者 + 合并 ====================

    /**
     * 待打印队列（{@code [Color color, String text]}）。<b>任一线程入队，EDT 出队落盘。</b>
     *
     * <p><b>为什么不让代理在调用线程直印</b>（这是本轮修掉的真事故）：框架的
     * {@code ConsFrame.printo0} 直接 {@code insertString} 到控制台那份
     * {@code DefaultStyledDocument}，而"元素结构变化"是<b>异步</b>排进 EDT 的。于是
     * "别的线程在 insertString"与"EDT 在跑视图更新"会同时改同一棵视图树，实测抛
     * {@code ArrayIndexOutOfBoundsException}：一次抛在 IR 脚本线程的 {@code CompositeView.replace}，
     * 一次抛穿 EDT 的 {@code run()}（{@code CompositeView.getView}）——
     * <b>EDT 线程一死，窗口就只剩最后一帧，点不动了</b>。</p>
     *
     * <p>所以代理把输出收进队列，由 EDT 上的单个落盘循环按顺序插入：只要代理挂着，
     * 框架自己就不再打印（{@code SairCons.insertPrinto} 的 {@code hasAgo} 分支），
     * 于是<b>整份控制台文档只剩一个写入者，而且它在 EDT 上</b> —— 这一类竞态从根上消失。</p>
     *
     * <p>队列有界（{@link #PRINT_Q_MAX}）；满了丢最旧并计数，<b>绝不阻塞调用线程</b>。</p>
     */
    private static final java.util.ArrayDeque<Object[]> PRINT_Q = new java.util.ArrayDeque<Object[]>();
    private static final int PRINT_Q_MAX = 4000;
    /** 一次落盘里合并同色片元的上限（字符），避免单条插入过大。 */
    private static final int PRINT_CHUNK_CHARS = 16 * 1024;
    private static boolean printScheduled = false;
    /** 是否处于"扣住输出"状态（见 {@link #holding()}）。 */
    private static volatile boolean hold = false;
    /** 扣住的自动解除时刻（毫秒时间戳；到点自动放行，防止"没人来解"时控制台永远空着）。 */
    private static volatile long holdDeadline = 0L;
    /** 看守线程只起一次。 */
    private static volatile boolean guardStarted = false;
    /** 扣住期间被 {@code /clear} 清掉、因此丢弃的缓冲字符数（诊断）。 */
    private static volatile long holdDropped = 0L;

    /**
     * 现在是否处于"扣住输出"状态（框架启动脚本阶段）。
     *
     * <p><b>为什么要有这个状态（对照实验测出来的）</b>：框架自己的 {@code autorun.ir} 在
     * {@code /clear}、{@code /print-ti}、{@code /help}、{@code /hide}、{@code /show} 之间来回，
     * 而它的打印是"调用线程直印"（IR 脚本线程），视图更新却是<b>异步排在 EDT 上</b>的 ——
     * 两边同时动同一棵视图树就会抛 {@code ArrayIndexOutOfBoundsException}，
     * 轻则这次插入失败、重则<b>异常抛穿 EDT 的 {@code run()}，EDT 线程当场死掉 → 界面永久卡死</b>。
     * 实测：把插件完全变成哑巴（不注册代理、一条命令都不跑）后框架<b>照样</b>这么崩，
     * 所以这是框架自身的坑；V3 当年之所以没撞上，是因为它把 EDT 堵在启动流程里，
     * EDT 没机会并发跑视图更新。</p>
     *
     * <p>我们的做法：注册代理（框架从此不自己打印），由 EDT 单写者按 tree→doc 的锁序落盘。</p>
     *
     * <p><b>★"扣住"这套机制现在没有调用者（甲方令，T12-R3d）</b>：它当初的用途是"启动脚本阶段先把输出
     * 扣在队列里一个字节都不插、等第一条我们的命令到了再放行"，而它的两个触发点（插件构造期
     * {@code install(null,true)}、以及 {@link SfwPrintSource#install()} 成功后的 {@code beginHold()}）
     * 都已被判为<b>劫持框架控制台</b>并删除 ⇒ 今天 {@code hold} 恒为 {@code false}，
     * {@link #beginHold()} <b>在运行期不可达</b>（语法上还剩 {@code if (hold)} 两个形参分支，永远不进）。
     * <b>代码留着不触发</b>（含 guard 线程与 {@link #HOLD_MAX_MS}），
     * <b>若将来要复用，前提只有一个：那条路径必须能保证"随后一定有我们自己的命令"</b> —— 保证不了就不许扣。</p>
     */
    public static boolean holding() { return hold; }

    /** 扣住的最长时间（毫秒）：没人来放行时自动放行，绝不让控制台一直空着。**当前无调用者**（见 {@link #holding()}）。 */
    public static final long HOLD_MAX_MS = 10000L;

    /**
     * 开始扣住（{@link SfwPrintSource#install()} 成功后调）。
     *
     * <p><b>当前无调用者（甲方令，T12-R3d）</b>：保留是因为它是"扣住"的唯一入口（含
     * {@link #startGuard()} 那条到点放行的看守线程）。将来若真要用，<b>前提是那条路径能保证
     * 随后一定有我们自己的命令</b>；构造期与框架自主启动的路径<b>一律不许</b>调它
     * （那就是把框架控制台变人质）。</p>
     *
     * <p><b>放行只有一条路，且它已 fail-open</b>：{@link #startGuard()}（到点即放行、任何出口都放行）。
     * 这里<b>不再</b>另起兜底定时器 —— 那是我在上一轮加过的东西，本轮已撤回：它运行期不可达、
     * 还会往发布 jar 里多塞一个匿名类（"多余无实际作用的增加就摘掉"）。</p>
     */
    static void beginHold() {
        hold = true;
        holdDeadline = System.currentTimeMillis() + HOLD_MAX_MS;
        startGuard();
    }

    /**
     * 放行：把扣住的输出交给 EDT 落盘（幂等；{@link com.sair.v4.V4Activity} 收到第一条命令时调）。
     */
    public static void releaseHold() {
        if (!hold) return;
        hold = false;
        boolean kick = false;
        synchronized (PRINT_Q) {
            if (!printScheduled && !PRINT_Q.isEmpty()) {
                printScheduled = true;
                kick = true;
            }
        }
        if (kick) kickDrain();
    }

    /**
     * 看守线程：扣住期间盯住文档长度，一旦被 {@code /clear} 清零就把扣住的旧内容丢掉（别把清掉的东西又打回去）。
     *
     * <p><b>fail-open（T12-R3d）</b>：整段跑在 {@code try … finally { releaseHold(); }} 里 ——
     * <b>任何出口</b>（正常到点、break 出循环、提前 {@code return}、异常）都会放行。
     * 改动前"睡够 50ms"那句的 {@code catch (InterruptedException e) { return; }} 是<b>唯一</b>
     * 早退却不放行的路径：中断一次就把 {@code hold} 永久钉在 {@code true}，控制台从此一个字都不出。</p>
     */
    private static void startGuard() {
        if (guardStarted) return;
        guardStarted = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int last = -1;
                    while (hold) {
                        try {
                            int len = ConsFrame.cf.getTextPane().getDocument().getLength();
                            if (last > 0 && len == 0) {
                                long n = 0L;
                                synchronized (PRINT_Q) {
                                    while (!PRINT_Q.isEmpty()) {
                                        Object[] it = PRINT_Q.pollFirst();
                                        n += ((String) it[1]).length();
                                    }
                                }
                                holdDropped += n;
                                diag("[diag] hold: 控制台被 /clear 清空，丢掉扣住的 " + n + " 字符（旧的一代）");
                            }
                            last = len;
                        } catch (Throwable ignored) {
                        }
                        try {
                            Thread.sleep(50L);
                        } catch (InterruptedException e) {
                            return;          // 中断 = 这个看守下班；**放行交给 finally**（不许在这里静默早退）
                        }
                        if (System.currentTimeMillis() > holdDeadline) {
                            return;              // 到点：同样交给 finally 放行
                        }
                    }
                } finally {
                    releaseHold();               // ★ 任何出口都放行（幂等）
                }
            }
        }, "v4-console-guard");
        t.setDaemon(true);
        t.start();
    }
    /** 队列满被丢掉的最旧条数（诊断）。 */
    private static volatile long printDropped = 0L;
    /** 收到的"定位插入"（非 null index）次数：异步落盘只能追加，这里只统计（诊断）。 */
    private static volatile long forwardedWithIndex = 0L;

    private static final Runnable DRAIN = new Runnable() {
        @Override
        public void run() {
            drainNow();
        }
    };

    /** 入队并（必要时）投递一次 EDT 落盘；任何异常都不外抛。 */
    static void enqueuePrint(Color c, String info) {
        if (info == null || info.length() == 0) return;
        boolean kick = false;
        synchronized (PRINT_Q) {
            if (PRINT_Q.size() >= PRINT_Q_MAX) {
                PRINT_Q.pollFirst();
                printDropped++;
            }
            PRINT_Q.addLast(new Object[] { c, info });
            // 扣住期间只入队、不投递：这段时间一个字节都不许插进文档（见 holding() 的说明）
            if (!printScheduled && !hold) {
                printScheduled = true;
                kick = true;
            }
        }
        if (kick) kickDrain();
    }

    /** 投递一次 EDT 落盘（投不出去 = 没有 EDT：本线程直落，至少不丢输出）。 */
    private static void kickDrain() {
        try {
            javax.swing.SwingUtilities.invokeLater(DRAIN);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            synchronized (PRINT_Q) {
                printScheduled = false;
            }
            drainNow();
        }
    }

    /**
     * 等"队列里的输出都已经落进控制台文档"。
     *
     * <p>落盘是<b>异步</b>的（在 EDT 上单写者执行，见 {@link #enqueuePrint}），所以
     * "刚 print 完立刻读 {@code getConsoleText()} 就能看到"不再成立。需要立刻观察文档的调用方
     * （探针、诊断）先调一次本方法：</p>
     * <pre>
     * ConsoleTap.awaitDrain(2000L);      // 最多等 2 秒
     * String visible = SairCons.getConsoleText();
     * </pre>
     *
     * @return true = 队列已排空（含本来就没有待办）
     */
    public static boolean awaitDrain(long timeoutMs) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            drainNow();                       // 已经在 EDT 上：直接落完，不能等自己
            return true;
        }
        final long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (PRINT_Q) {
            while (printScheduled || !PRINT_Q.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0L) return false;
                try {
                    PRINT_Q.wait(Math.min(left, 25L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 落盘循环（期望在 EDT 上；无 EDT 时由调用线程跑）。
     * <p>把相邻<b>同色</b>片元合并成一次插入，减少文档事件数量；循环排空为止。</p>
     */
    static void drainNow() {
        try {
            while (true) {
                Object[] head;
                synchronized (PRINT_Q) {
                    head = PRINT_Q.pollFirst();
                    if (head == null) {
                        printScheduled = false;
                        PRINT_Q.notifyAll();      // 唤醒 awaitDrain 的等待者
                        return;
                    }
                }
                Color c = (Color) head[0];
                StringBuilder sb = new StringBuilder((String) head[1]);
                while (sb.length() < PRINT_CHUNK_CHARS) {
                    Object[] next;
                    synchronized (PRINT_Q) {
                        next = PRINT_Q.peekFirst();
                        if (next == null) break;
                        if (!sameColor(c, (Color) next[0])) break;
                        PRINT_Q.pollFirst();
                    }
                    sb.append((String) next[1]);
                }
                try {
                    // 锁序：先 AWTTreeLock，再让 Swing 在内部拿文档锁（见 insert 的注释）。
                    // index 传 null = 追加到末尾：异步落盘无法兑现"定位插入"，而那类调用框架自己也没用
                    withTreeLock(c, sb.toString());
                    printChunks++;
                    diag("[diag] chunk#" + printChunks + " chars=" + sb.length()
                            + " thread=" + Thread.currentThread().getName());
                    mirror(sb.toString());
                } catch (Throwable t) {
                    lastError = String.valueOf(t);
                    diag("[diag] printo FAILED: " + t);
                }
            }
        } finally {
            // ★任何异常路径都必须复位 + 唤醒（T12-R3d，甲方实测缺陷）：
            // 原来只有"队列空"这一个正常出口复位 printScheduled；而上面"合并同色片元"那段
            // 在 synchronized 之外、也在内层 try 之外 —— 它抛异常会直接穿出本方法，
            // printScheduled 就永远停在 true ⇒ 之后 enqueuePrint(:1013)/releaseHold(:942)
            // 里的 kick 全被跳过 ⇒ **所有输出静默丢弃、控制台永久空白**。
            synchronized (PRINT_Q) {
                printScheduled = false;
                PRINT_Q.notifyAll();
            }
        }
    }

    private static boolean sameColor(Color a, Color b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 插一段进控制台。
     *
     * <p><b>锁序（这里踩过一次真死锁，务必保留）</b>：先拿 {@code AWTTreeLock}，再调
     * {@code ConsFrame.printo}（它内部才拿文档锁）。实测的那次死锁：框架的 IR 脚本线程执行
     * {@code /show} → {@code SFrame.setVisible} → {@code Window.show()} → {@code validateTree}
     * （<b>持树锁</b>）→ {@code JEditorPane.getPreferredSize()} → 文档读锁；而我们的落盘循环在 EDT 上
     * {@code insertString}（<b>持文档写锁</b>）→ 视图更新 → {@code revalidate} → {@code Component.invalidate}
     * （要树锁）—— 两边锁序相反（tree→doc 对 doc→tree），EDT 永久 BLOCKED、框架那条 `/show` 也永远走不完：
     * <b>窗口既卡死又保持不可见</b>。先拿树锁就与布局线程同序，环就断了
     * （拿不到树锁时我们手里什么都没有，绝不会占着锁等人）。</p>
     */
    private static void withTreeLock(Color c, String text) {
        Object lock = null;
        try {
            lock = ConsFrame.cf.getTreeLock();      // java.awt.Component.getTreeLock()：公开 API
        } catch (Throwable ignored) {
        }
        if (lock == null) {
            insert(c, text);
            return;
        }
        synchronized (lock) {
            insert(c, text);
        }
    }

    /**
     * 插一段进控制台；<b>文档视图已经坏了就重建它再插一次</b>。
     *
     * <p>为什么需要自愈：框架的 {@code ConsFrame.printo0} 只 catch {@code BadLocationException}，
     * 而"视图树不一致"抛的是 {@code ArrayIndexOutOfBoundsException}（实测：{@code CompositeView.replace}
     * / {@code CompositeView.getView}）。一旦那份文档被并发写坏，<b>之后每一次插入都抛</b> ——
     * 控制台从此一个字都不出（且那条异常在框架自己的直印路径上会顺着打印线程往上抛，
     * 能把正在跑的 IR 脚本打断，连 `/show` 都执行不到 → 窗口不可见）。
     * 换掉 {JTextPane} 的文档会让 UI 重建视图，是唯一能把这条链断掉的动作。</p>
     *
     * <p>代价：旧文本丢失（它本来也已经读不到/写不进了）。所以只报一行 warn，不做别的。</p>
     */
    private static void insert(Color c, String text) {
        try {
            ConsFrame.printo(null, c, text);
            return;
        } catch (Throwable first) {
            if (!repairConsole(first)) throw new RuntimeException(String.valueOf(first));
            // 重建之后重量一次（还失败就让它抛给调用方，由它记账）
            ConsFrame.printo(null, c, text);
        }
    }

    /** 重建控制台文档（必须在 EDT 上）。返回是否真的重建过。 */
    private static boolean repairConsole(Throwable why) {
        try {
            if (!javax.swing.SwingUtilities.isEventDispatchThread()) return false;
            final javax.swing.JTextPane tp = ConsFrame.cf.getTextPane();
            if (tp == null) return false;
            tp.setDocument(new javax.swing.text.DefaultStyledDocument());
            repairCount++;
            diag("[diag] console document REBUILT after " + why);
            try {
                ConsFrame.printo(null, Color.ORANGE,
                        "[v4] 控制台文档视图已损坏（" + why + "）——已重建文档，旧文本丢失；输出继续。\n");
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Throwable t) {
            diag("[diag] repair FAILED: " + t);
            return false;
        }
    }

    /** 文档重建次数（诊断）。 */
    private static volatile long repairCount = 0L;

    /** 已经插进控制台的段数（诊断）。 */
    private static volatile long printChunks = 0L;
    /** 镜像落点（只在显式 {@code -Dv4.console.dump=<文件>} 时打开；默认关闭）。 */
    private static final String DUMP_PATH = System.getProperty("v4.console.dump", "");
    private static final Object MIRROR_LOCK = new Object();
    private static java.io.FileWriter MIRROR;

    /**
     * 诊断镜像：把"真正插进控制台的每一段"按落盘顺序写进文件（默认关闭，见 {@link #DUMP_PATH}）。
     * <p>为什么需要它：控制台正文只存在于框架那份文档里（{@code getConsoleText()} 只能在进程内读），
     * 自动化跑真实 SFW 时没法从外面看见"用户到底看到了什么"。这个开关让排障时能拿到逐字一致的落盘记录。</p>
     */
    private static void mirror(String text) {
        if (DUMP_PATH.isEmpty()) return;
        try {
            synchronized (MIRROR_LOCK) {
                if (MIRROR == null) MIRROR = new java.io.FileWriter(DUMP_PATH, false);
                MIRROR.write(text);
                MIRROR.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 诊断侧写（{@code <dump>.diag}）：代理装没装上、每条输出转发与否、落盘了多少段。 */
    private static void diag(String text) {
        if (DUMP_PATH.isEmpty()) return;
        try {
            synchronized (DIAG_LOCK) {
                if (DIAG == null) DIAG = new java.io.FileWriter(DUMP_PATH + ".diag", false);
                DIAG.write(text);
                if (text.length() == 0 || text.charAt(text.length() - 1) != '\n') DIAG.write("\n");
                DIAG.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private static final Object DIAG_LOCK = new Object();
    private static java.io.FileWriter DIAG;

    /** 队列诊断：{@code pending} 待落盘条数、{@code dropped} 因队列满丢掉的最旧条数。 */
    public static com.google.gson.JsonObject printStat() {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        synchronized (PRINT_Q) {
            o.addProperty("pending", PRINT_Q.size());
            o.addProperty("scheduled", printScheduled);
        }
        o.addProperty("dropped", printDropped);
        o.addProperty("chunks", printChunks);
        o.addProperty("repairs", repairCount);
        o.addProperty("holding", hold);
        o.addProperty("hold_dropped", holdDropped);
        o.addProperty("with_index", forwardedWithIndex);
        return o;
    }

    /** 颜色 → 语义标记（与 {@code SfwOut.color(Tone)} 反着查；查不到 = 空串，不猜）。 */
    static String toneOf(Color c) {
        if (c == null) return "";
        if (Color.GRAY.equals(c)) return "dim";
        if (Color.GREEN.equals(c)) return "ok";
        if (Color.ORANGE.equals(c)) return "warn";
        if (Color.RED.equals(c)) return "err";
        if (Color.CYAN.equals(c)) return "title";
        return "";
    }

    /** 便于诊断的迭代快照（只读）。 */
    public static List<Line> snapshot() {
        synchronized (LOCK) {
            List<Line> l = new ArrayList<Line>(RING.size());
            for (Iterator<Line> it = RING.iterator(); it.hasNext(); ) l.add(it.next());
            return l;
        }
    }
}
