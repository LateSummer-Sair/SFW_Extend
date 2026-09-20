package sair.v4.dev;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import sair.sys.SairCons;
import sair.v4.Boot;
import sair.v4.Conf;
import sair.v4.auth.Acl;
import sair.v4.ctx.Sink;
import sair.v4.kit.Fs;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.term.ConsoleTap;
import sair.v4.term.SfwOut;

/**
 * 本机调试口（基板⑨ 的旁路，<b>默认关闭</b>）：让主会话/父 Agent 在进程外
 * 「起框架、看框架输出、像敲控制台一样下命令」。
 *
 * <h3>它落在哪条机制上</h3>
 * <p>不是新造输出通道，而是复用已存在的那条：{@code SairCons.insertPrinto} 只要发现
 * <b>有任意打印代理（{@link sair.user.PrintRunnable}）</b>就不再自己写控制台，而是把每一行交给代理
 * （框架把这个状态叫 <b>{@code SFW的其他输出模式}</b>，见 {@code SairCons.java:147-158} 与
 * {@code :182-202} 的 {@code runAgo}）。工程侧的 {@link ConsoleTap} 就是这样的代理
 * （{@code addPrintRunnable("AiAgentV4_ConsoleTap", …)}），把每行收进有界环形缓冲并给每条打自增 seq。
 * 所以本类<b>一行捕获代码都不用写</b>：读输出 = {@link SfwOut#read}（游标式），
 * 下命令 = {@link SfwOut#run}（内部 {@code SairCons.runner(true, cmd)}），
 * 退出 = {@code SairCons.runner(false, "/exit")}。</p>
 *
 * <h3>协议（行文本，UTF-8，越简单越好）</h3>
 * <pre>
 * 客户端连上后第一行必须是： AUTH &lt;token&gt;
 *   成功 → "+OK &lt;name&gt;"；失败 → "-ERR bad token" 并断开（每次失败记一行 warn）
 * 之后每行一条命令，服务端逐行回一个"带结束哨"的块：
 *   [debug] code=ok ...            ← 事实行（一行，机器可读）
 *   ...正文（0..n 行）...
 *   [debug:end]                    ← 结束哨（正文里出现同名字符串会被加一个前导空格转义）
 * 命令表：
 *   ping                      → pong
 *   status                    → 一行摘要（就绪/技能数/工具面/ext/六库/napcat/最近错误行数）+ 事实行
 *                               （事实行含 [acl] 技能管控表：N 条（Run x / Ban y）、覆盖 op k 个）
 *   read [sinceSeq] [maxChars]→ 增量读控制台捕获（游标式）；sinceSeq 省略=从最旧
 *   tail [N]                  → 读最近 N 条（N 省略=50）
 *   hist [N]                  → 框架命令历史最近 N 条
 *   help                      → 本表
 *   exit | /exit              → 回块后按主人说的那条路关框架：SairCons.runner(false, "/exit")
 *                               （框架 ConsFrame.close()；本组件路径**不结束进程** —— 见下面 help 表）
 *   quit | bye                → 只关这条连接（框架不动）
 *   其它任何一行              → 当<b>框架控制台命令</b>执行（等价于人在控制台敲），回它的输出
 * </pre>
 *
 * <h3>安全（只回环、必带 token）</h3>
 * <ul>
 *   <li><b>只绑 {@code 127.0.0.1}</b>：不监听 0.0.0.0，外部网卡够不着；</li>
 *   <li><b>token 必填</b>：token 为空 → <b>拒绝启动</b>并打一行 warn（无 token 裸开是不允许的）。
 *       token 不再是配置键：{@code ai/debug on} 不带 token 时现场生成一个，写进凭据文件；</li>
 *   <li><b>只有 {@code ai/debug on|off} 能起停</b>：装配不自动起这两个口（原来第⑲步起一次），
 *       配置里也没有它们的键。所以"端口什么时候开"只有一个出处，也只有一条能关掉它的路；
 *       <b>停基板不是那条路</b>（{@code Boot.stop()} 不碰调试面 —— 见 {@link DebugShell}）；</li>
 *   <li><b>基板是现取的</b>：两个口<b>不</b>缓存 {@code Boot}（旧实现缓存的壳基板让
 *       {@code status} 永远 {@code ready=0}、输入口永远 {@code -ERR link_not_ready}）。
 *       现取规则见 {@link #liveBoot()} 与 {@link DebugShell.Host}；</li>
 *   <li><b>落盘不归它管</b>：{@code logs\} 的控制台/对话日志随壳存在（{@link DebugLog}），
 *       {@code ai/debug off} 只停口、不停落盘、不删任何日志文件；</li>
 *   <li><b>单条命令长度上限</b> {@value #MAX_LINE} 字符：超了断开，不让一行把内存撑爆；</li>
 *   <li><b>连接数上限</b> {@value #MAX_CONN}：超了直接拒（回一行 busy 就关），不让 socket 堆积；</li>
 *   <li><b>空闲超时</b> {@value #IDLE_MS} 毫秒：一条连接挂住不发也不收就自己断，防止"一个 socket 挂死"；</li>
 *   <li>所有日志与命令都走基板 {@link Out}，所以在框架输出里看得见（也就意味着 {@code -Tail} 读得到）。</li>
 * </ul>
 *
 * <p>线程模型：一条 accept 线程 + 每连接一条守护线程，全部 {@code catch (Throwable)}；
 * 调试口自身<b>绝不能</b>让基板起不来或停不下来。</p>
 */
public final class DebugPort {

    /** 名字：出现在日志、pong、以及框架控制台的代理列表里。 */
    public static final String NAME = "AiAgentV4_DebugPort";

    /** 单条入站行长度上限（字符）。 */
    public static final int MAX_LINE = 8192;

    /** 并发连接数上限。 */
    public static final int MAX_CONN = 4;

    /** 空闲超时（毫秒）：一条连接在这段时间里一个字节都没来就断开。 */
    public static final int IDLE_MS = 120000;

    /** 一条命令的执行上限（毫秒）；等不到就回超时（命令本身可能还在 EDT 上跑）。 */
    public static final int CMD_WAIT_MS = 60000;

    /** 单次 read/tail 的字符上限。 */
    public static final int MAX_READ_CHARS = 24000;

    /** 结束哨：正文里出现同名字符串时会被转义（加一个前导空格）。 */
    public static final String END = "[debug:end]";

    /** 日志前缀。 */
    private static final String TAG = NAME + " ";

    /** 当前活着的实例（{@link #start} 幂等：先停旧的再起新的）。 */
    private static volatile DebugPort current;

    /**
     * 对话通道要不要镜像：<b>任一个调试口在听</b>就要（2660 的 talk* 读它；2661 与它同一装配点）。
     * <p>为什么不能再用 {@code current != null} 判：输出口没起来（绑不上/被占）而输入口开着时，
     * 对话仍然是"调试面的一部分"，镜像不该跟着输出口一起消失。</p>
     */
    private static volatile boolean capture = false;

    /** 认证失败日志的限流：同一来源 N 秒内最多一行（防客户端重试把控制台刷爆）。 */
    public static final long REJECT_LOG_MS = 10000L;
    private static final AtomicInteger rejectTotal = new AtomicInteger();
    private static volatile long rejectLastLogMs = 0L;

    /**
     * <b>基板是现问现取的，绝不缓存</b>（主人 2026-09-16 裁定）。
     *
     * <p>为什么这是硬约束：口是在 {@code ai/debug on} 时起的，而那条命令<b>故意</b>允许在
     * {@code ai/start} 之前敲（那时的"基板"只是 {@code V4Activity.ensureReady()} 搭的空壳
     * {@code new Boot(root, out)}）。真正的基板是 {@code ai/start}/{@code ai/restart} 时
     * {@code V4Activity.kick} <b>另建的一台</b> {@code Boot}。旧实现把壳基板存进字段
     * {@code private final Boot boot}，于是口永远看着那台空壳：{@code ready=0 reason=not_started
     * skills=-1 tools=-1 ext=-1 napcat=0}，输入口的 {@code msg} 永远
     * {@code -ERR link_not_ready} —— 而同一瞬间控制台 {@code ai/status} 明明是
     * {@code ready=1 skills=35}。</p>
     *
     * <p>现在每次要用基板都问一次：壳绑了 {@link DebugShell.Host}（{@code V4Activity}，
     * {@code kick()} 时它自己的字段就换成新基板）就<b>只信宿主</b>；没有壳的环境
     * （探针 / 裸 {@code Cmd}）退回 {@code start()} 传进来的那一台。</p>
     */
    static Boot liveBoot() {
        DebugShell.Host h = DebugShell.host();
        if (h != null) {
            try {
                return h.boot();
            } catch (Throwable t) {
                return null;
            }
        }
        return fallbackBoot;
    }

    /** 无壳环境（探针 / 裸 {@code Cmd}）的基板：{@code start()} 传进来的那一台。 */
    private static volatile Boot fallbackBoot;

    private final Out out;
    private final int port;
    private final String token;
    private final AtomicInteger conns = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private volatile boolean stopping;

    private DebugPort(Out out, int port, String token) {
        this.out = out;
        this.port = port;
        this.token = token;
    }

    // ==================== 第二路捕获：对话正文（talk） ====================
    //
    // 为什么需要第二路（主人原话："ai/chat 之后的命令你是回调不到输出的，因为内容都被输出到了
    // 单独的控件上去了"）：
    //   控制台打印代理（PrintRunnable / ConsoleTap）只拿得到"框架往主输出框打的东西"。
    //   而 ai/chat 的正文根本不走主输出框 —— 它走"交流面板"这个独立控件：
    //     Cmd.chat → Boot.localSink() → PanelSink → TalkPanel（挂进 ConsFrame 的选项卡隔离区，
    //     TalkMount.java:10-23 —— 进选项卡的东西不写进 infoPane）。
    //   所以只挂打印代理，能看到的只有装配进度/工具台账/错误行，<b>看不到她说了什么</b>。
    //
    // 怎么接（最省事的那条：文本经过我们的代码）：
    //   TalkPanel 只由插件的 Sink 写入，而本地对话的 Sink 只有一个出处 ——
    //   Boot.localSink()（Cmd.java:457 与 Boot.askConsole:1092 都取它）。所以在这里套一层
    //   镜像装饰器，say/stream/notice 每一次调用都镜像进本缓冲：
    //     · 两路落点（面板 / 回落控制台）都被覆盖，且只镜像一次，不会因落点不同而重复；
    //     · 与第一路同一个读法：自增 seq + 游标增量读（talk / talktext）。
    //   装饰器"本轮是否流过式"的状态与 PanelSink/ConsoleSink 逐字同构，
    //   所以 say 在"已流过式"时只记一条 end 边界（<b>不重记全文</b>）——
    //   这样把 stream 增量按 seq 顺序拼起来，就是她这一轮说的完整正文，不多不少。

    /** 对话缓冲的条数上限。 */
    public static final int TALK_ITEMS = 2000;
    /** 对话缓冲的字符预算。 */
    public static final int TALK_CHARS = 256 * 1024;
    /** 单次 talk 读的字符上限。 */
    public static final int TALK_READ_CHARS = 24000;

    /** 一条对话记录。 */
    public static final class TLine {
        public final long seq;
        public final long ms;
        /** {@code user}（主人说的）/ {@code say}（整段回话）/ {@code stream}（流式增量）/
         *  {@code notice}（过程行）/ {@code end}（流式收尾，无正文）。 */
        public final String kind;
        public final String text;

        TLine(long seq, long ms, String kind, String text) {
            this.seq = seq;
            this.ms = ms;
            this.kind = kind;
            this.text = text;
        }
    }

    private static final Object TALK_LOCK = new Object();
    private static final ArrayDeque<TLine> TALK = new ArrayDeque<TLine>();
    private static volatile long talkSeqTail = 0L;
    private static long talkChard = 0L;
    private static long talkCaptured = 0L;
    private static long talkDropped = 0L;

    /** 记一条对话（越界丢最旧的）。任意线程可调；永不抛。 */
    public static void acceptTalk(String kind, String text) {
        if (kind == null) return;
        String t = text == null ? "" : text;
        if (t.isEmpty() && !"end".equals(kind)) return;
        try {
            long now = System.currentTimeMillis();
            synchronized (TALK_LOCK) {
                talkSeqTail++;
                TALK.addLast(new TLine(talkSeqTail, now, kind, t));
                talkChard += t.length();
                talkCaptured++;
                while (TALK.size() > TALK_ITEMS || talkChard > TALK_CHARS) {
                    TLine l = TALK.pollFirst();
                    if (l == null) break;
                    talkChard -= l.text.length();
                    talkDropped++;
                }
            }
            // 落盘（talkdump 的"一次取全部对话"）：与内存窗口无关，越界丢掉的也已经在盘上。
            // 没装 DebugLog（探针 / 调试口没开）时是空操作。
            sair.v4.dev.DebugLog.talk(now, kind, t);
        } catch (Throwable ignored) {
        }
    }

    /** 对话缓冲的当前游标（下次当 sinceSeq 传回来就只拿新增）。 */
    public static long talkCursor() { return talkSeqTail; }

    /** 对话缓冲快照（顺序 = seq 升序）。 */
    public static List<TLine> talkSnapshot() {
        synchronized (TALK_LOCK) {
            return new ArrayList<TLine>(TALK);
        }
    }

    /**
     * 对话增量读（注解版：每条一行 {@code #seq 时间 [kind] 文本}）——给人看/排查用。
     */
    public static String talkRead(long sinceSeq, int maxChars) {
        int budget = maxChars > 0 ? maxChars : TALK_READ_CHARS;
        if (budget > TALK_READ_CHARS) budget = TALK_READ_CHARS;
        List<TLine> snap = talkSnapshot();
        StringBuilder sb = new StringBuilder(256);
        sb.append("[talk] lines=").append(snap.size())
          .append(" seq_tail=").append(talkSeqTail)
          .append(" captured=").append(talkCaptured)
          .append(" dropped=").append(talkDropped)
          .append(" since_seq=").append(sinceSeq);
        SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss");
        int used = 0;
        int n = 0;
        for (TLine l : snap) {
            if (l.seq <= sinceSeq) continue;
            String row = "#" + l.seq + " " + f.format(new Date(l.ms)) + " [" + l.kind + "] " + l.text + "\n";
            if (used + row.length() > budget) { sb.append("\n…(超出本次长度上限，游标停在 #").append(l.seq - 1).append(")"); break; }
            sb.append("\n").append(row.endsWith("\n") ? row.substring(0, row.length() - 1) : row);
            used += row.length();
            n++;
        }
        sb.append("\n[talk] returned=").append(n).append(" next_seq=").append(talkSeqTail);
        return sb.toString();
    }

    /**
     * 对话增量读（<b>纯正文拼接</b>）：把 {@code seq > sinceSeq} 的记录文本按顺序原样相接。
     *
     * <p>这是"她到底说了什么"的权威读法，也是流式完整性的判据：一轮里 {@code stream} 增量会
     * 逐条相接成完整正文，收尾的 {@code say}（已流过式那条）只留一条无正文的 {@code end}，
     * 所以既不会丢开头，也不会把中段重复一遍。</p>
     */
    public static String talkText(long sinceSeq, int maxChars) {
        int budget = maxChars > 0 ? maxChars : TALK_READ_CHARS;
        if (budget > TALK_READ_CHARS) budget = TALK_READ_CHARS;
        List<TLine> snap = talkSnapshot();
        StringBuilder sb = new StringBuilder(256);
        int used = 0;
        int n = 0;
        int skipped = 0;
        long last = sinceSeq;
        for (TLine l : snap) {
            if (l.seq <= sinceSeq) continue;
            if ("say-dup".equals(l.kind)) { skipped++; last = l.seq; continue; }   // 增量已记过这一段
            if (used + l.text.length() > budget) break;
            sb.append(l.text);
            used += l.text.length();
            last = l.seq;
            n++;
        }
        return "[talktext] lines=" + n + " skipped_say_dup=" + skipped + " next_seq=" + last
                + " seq_tail=" + talkSeqTail + " chars=" + used + "\n" + sb.toString();
    }

    /**
     * 「只要最终整句」桶：回 {@code say}/{@code say-dup} 两类记录的文本（流式增量不算）。
     * 想确认"她这一轮最终说了什么"用这个；想拼增量用 {@link #talkText}。
     */
    public static String talkSay(long sinceSeq, int maxChars) {
        int budget = maxChars > 0 ? maxChars : TALK_READ_CHARS;
        if (budget > TALK_READ_CHARS) budget = TALK_READ_CHARS;
        List<TLine> snap = talkSnapshot();
        StringBuilder sb = new StringBuilder(256);
        int used = 0;
        int n = 0;
        long last = sinceSeq;
        for (TLine l : snap) {
            if (l.seq <= sinceSeq) continue;
            if (!"say".equals(l.kind) && !"say-dup".equals(l.kind)) continue;
            if (used + l.text.length() > budget) break;
            sb.append(l.text);
            used += l.text.length();
            last = l.seq;
            n++;
        }
        return "[talksay] lines=" + n + " next_seq=" + last
                + " seq_tail=" + talkSeqTail + " chars=" + used + "\n" + sb.toString();
    }

    /** 最近 N 条对话记录（注解版）。 */
    public static String talkTail(int n) {
        int want = n <= 0 ? 50 : Math.min(n, 1000);
        List<TLine> snap = talkSnapshot();
        if (snap.isEmpty()) return "[talk] (对话缓冲还是空的：还没走过本地对话)";
        int from = Math.max(0, snap.size() - want);
        long since = from == 0 ? 0L : snap.get(from - 1).seq;
        return talkRead(since, TALK_READ_CHARS);
    }

    /**
     * 给一个 {@link Sink} 套上镜像（本地对话正文进对话缓冲）。
     *
     * <p><b>调试口没开时原样返回</b> —— 不镜像、不缓冲、一行代码都不多走，基板行为逐字不变。</p>
     */
    public static Sink mirror(Sink inner) {
        if (inner == null || !capture) return inner;
        return new Mirror(inner);
    }

    /**
     * 镜像装饰器：语义与 {@code PanelSink}/{@code ConsoleSink} 的"本轮是否流过式"逐字同构，
     * 但<b>两个桶分开记</b>，读的人可以自己选：
     * <ul>
     *   <li>{@code stream} 桶 —— 流式增量，一条一个片段（按 seq 拼起来 = 完整正文）；</li>
     *   <li>{@code say} 桶 —— 整段最终版；<b>本轮流过式时标成 {@code say-dup}</b>，
     *       表示"这段正文已经由 stream 记过了"。所以：想只看整句用 {@code talksay}，
     *       想拼增量用 {@code talktext}（它自动跳过 {@code say-dup}），两条路都不会读两遍。</li>
     * </ul>
     */
    private static final class Mirror implements Sink {
        private final Sink inner;
        /** 本轮是否已经输出过流式增量（收尾判据）。 */
        private volatile boolean streamed;

        Mirror(Sink inner) { this.inner = inner; }

        @Override
        public void say(String text) {
            String t = text == null ? "" : text;
            if (streamed) {
                streamed = false;
                // 整段正文与 stream 桶重复：只留边界 + 标 dup，绝不把同一句话记两遍
                acceptTalk("say-dup", t.endsWith("\n") ? t : t + "\n");
            } else {
                acceptTalk("say", t.endsWith("\n") ? t : t + "\n");
            }
            inner.say(text);
        }

        @Override
        public void stream(String delta) {
            if (delta != null && !delta.isEmpty()) {
                streamed = true;
                acceptTalk("stream", delta);           // 增量原样记，按 seq 拼起来就是完整正文
            }
            inner.stream(delta);
        }

        @Override
        public void notice(String text) {
            if (text != null && !text.isEmpty()) {
                // kind 带上落点样式（TOOL/TURN），方便分辨"工具/系统行"与"她说的话"
                acceptTalk(noticeKind(text), text.endsWith("\n") ? text : text + "\n");
            }
            inner.notice(text);
        }
    }

    /**
     * 过程行的 kind：与 {@code PanelSink.noticeStyle} 同一判据 —— 以 {@code [} 开头的算工具事实行。
     * 直接复用面板的公开判据，避免两处判据漂移；面板不可用（无界面）时退回同一条 ASCII 形状规则。
     */
    static String noticeKind(String text) {
        boolean tool;
        try {
            tool = sair.v4.ui.PanelSink.noticeStyle(text) == sair.v4.ui.TalkPanel.Style.TOOL;
        } catch (Throwable t) {
            String s = text == null ? "" : text;
            int i = 0;
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
            tool = i < s.length() && s.charAt(i) == '[';
        }
        return tool ? "notice-tool" : "notice-turn";
    }

    // ==================== 生命周期 ====================

    /**
     * 起调试口（幂等：重复调用先停旧的）。<b>端口与 token 全部来自调用方</b>（= 控制台
     * {@code ai/debug on}），不再从 {@code config.json} 读 —— 那四个键已整条撤出配置面。
     *
     * <p><b>契约：不抛异常</b>。端口被占用、token 为空、绑定失败 —— 一律记一行日志后返回 false，
     * 基板照常跑（调试口是观测面，不是关键路径）。</p>
     *
     * <p>本方法同时负责<b>两个口</b>的装配顺序：先绑输出口（{@code port}），
     * 再起输入口（{@link DebugSimPort}，{@code simPort}），最后把两个口一起写进
     * 运行现场凭据文件 {@code debug-port.json}。两个口<b>各自独立</b>：任何一个起不来，
     * 另一个照常可用，基板照常可用。</p>
     *
     * @param port     输出口端口（{@code <=0} = 不起）
     * @param simPort  输入口端口（{@code <=0} = 不起）
     * @param token    凭据（<b>空 = 两口都拒绝启动</b>，绝不无 token 裸开）
     * @param simulate 输入口的仿真开关（默认 {@link Conf#DEF_DEBUG_SIMULATE} = true）
     * @return 输出口真的起来了吗
     */
    public static boolean start(Boot boot, Out out, int port, int simPort, String token, boolean simulate) {
        stop();                                             // 幂等：先收旧的
        int p = port;
        String t = Str.nz(token).trim();
        if (t.isEmpty()) {
            // 硬规矩：不许无 token 裸开（两个口一起拒绝；没凭据就没有调试面）
            warn(out, "调试口未启动：token 为空（输出口 " + p + " / 输入口 " + simPort
                    + "）。本机调试口必须带 token —— ai/debug on 不给 token 时会现生成一个。");
            return false;
        }
        // 没有壳（探针 / 裸 Cmd）时把传进来的这台记成兜底基板；有壳则只信壳的现取（见 liveBoot()）。
        if (DebugShell.host() == null) fallbackBoot = boot;
        // ---- 落盘（dump / talkdump 的"一次取全部输出"）**不在这里装了** ----
        // 主人 2026-09-16 追加裁定：落盘与两个口解耦 —— 它随壳存在（DebugShell.attach），
        // 口开着关着都写、基板停摆也不停。旧口径"on 起落盘 / off 停落盘 / Boot.stop 连落盘一起收"
        // 在真机上产出的是"正常启动的产品一行控制台日志都不写"与"ai/restart 之后日志永远停在
        // 停旧基板那一毫秒"。这里只把当前事实说清楚，绝不改落盘状态。
        if (out != null) {
            try {
                out.dim("  " + sair.v4.dev.DebugLog.facts());
            } catch (Throwable ignored) { }
        }
        boolean up = false;
        if (p <= 0) {
            info(out, "调试输出口关闭（端口 " + p + "；0=关）");
        } else if (p < 1024 || p == 2671 || p == 8082 || p == 8083) {
            // 端口纪律：绝不去挤框架/插件自己的口，也不碰特权口；被占就放弃，绝不换端口重试。
            // 本机实测已占：2671 文件中转 / 8082 NapCat 反向口 / 8083 另一个 java（Mirai 控制台）。
            warn(out, "调试输出口未启动：端口 " + p
                    + " 落在保留/特权端口（<1024，或 2671 中转 / 8082 NapCat / 8083）。"
                    + "请改用别的冷门端口（默认 " + Conf.DEF_DEBUG_PORT + "）再 ai/debug on。");
        } else {
            up = bind(out, p, t);
        }
        capture = up;
        // 输入口：与输出口同一个装配点，但**互不影响** —— 它自己的失败只记一行，绝不冒到这里。
        try {
            if (sair.v4.dev.DebugSimPort.start(boot, out, simPort, t, simulate)) capture = true;
        } catch (Throwable e) {
            warn(out, "调试输入口启动异常（基板不受影响）：" + e);
        }
        if (up || sair.v4.dev.DebugSimPort.running()) {
            // 凭据文件必须在**两个口都试完之后**写：客户端读一次就拿到全部（port/simPort/两个 token）。
            // 基板用现取的那一台（写文件要它的 root/conf/auth）—— 不缓存。
            writeRuntimeFile(liveBoot(), out, up ? p : 0, t, up, simPort, simulate,
                    sair.v4.dev.DebugSimPort.running());
        }
        return up;
    }

    /**
     * 输出口端口：<b>固定 {@link Conf#DEF_DEBUG_PORT}（2660）</b>。
     * <p>{@code ai/debug} 不接受端口参数，也没有任何环境变量/系统属性口子 —— 这两个口就是固定的一对
     * （2660 输出 / 2661 输入），换端口这件事不存在，免得"谁在听哪个口"变成要靠猜。</p>
     */
    public static int port() { return Conf.DEF_DEBUG_PORT; }

    /** 输入口端口：<b>固定 {@link Conf#DEF_DEBUG_SIM_PORT}（2661）</b>（同 {@link #port()}）。 */
    public static int simPort() { return Conf.DEF_DEBUG_SIM_PORT; }

    /**
     * 现生成一个调试口 token（{@code ai/debug on} 没带 token 时用）：16 字节随机 → 32 位十六进制。
     * <p>它只落在运行现场凭据文件里（{@code debug-port.json}），<b>不进 config.json</b>；
     * 控制台只回指纹，不回原文。</p>
     */
    public static String newToken() {
        byte[] b = new byte[16];
        try {
            new java.security.SecureRandom().nextBytes(b);
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) {
            int c = x & 0xFF;
            sb.append(HEX[c >> 4]).append(HEX[c & 0xF]);
        }
        return sb.toString();
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 绑 2660 并把 accept 线程起起来（{@link #start} 的实体；失败只记一行，返回 false）。 */
    private static boolean bind(Out out, int p, String t) {
        DebugPort dp = new DebugPort(out, p, t);
        try {
            ServerSocket ss = new ServerSocket();
            // 只绑回环：外部网卡够不着（不用 bind(port) 的无参重载，那个等价 0.0.0.0）
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 8);
            ss.setReuseAddress(true);
            dp.server = ss;
        } catch (Throwable e) {
            warn(out, "调试输出口未启动：绑定 127.0.0.1:" + p + " 失败（端口被占用？）：" + e
                    + " —— 实例照常可用，2660 上没有任何监听。");
            return false;
        }
        // 落盘（dump / talkdump 的"一次取全部输出"）在 start() 里已经装好（与两个口同生共死），
        // 这里不再装一次：重复 install 会重开文件句柄，日志文件是"只增不删"的，不该被多余地重开。
        Thread at = new Thread(new Runnable() {
            @Override
            public void run() { dp.loop(); }
        }, "v4-debugport");
        at.setDaemon(true);
        at.setName(NAME);
        dp.acceptThread = at;
        current = dp;
        at.start();
        info(out, "调试输出口已就绪：127.0.0.1:" + p + "（只绑回环 + 必带 token；token 指纹 "
                + fp(t) + "；凭据现场文件 " + dp.runtimeFile().getAbsolutePath() + "）");
        info(out, "调试输出口读法：read/tail=控制台捕获（内存窗口，游标式），dump=**全部**控制台输出（从落盘读），"
                + "talk/talktext/talksay=对话窗口，talkdump=**全部**对话（从落盘读），"
                + "其它行=框架控制台命令，exit=/exit");
        return true;
    }

    /**
     * 运行现场凭据文件（<b>唯一真源</b>）：{@code {name, host, port, pid, token, token_fp, ts}}。
     *
     * <p>放哪儿：实例自己的数据根下（{@code Boot.root()}），由<b>真正持有端口的这一侧</b>写 ——
     * 这样"谁起的实例"和"谁连的客户端"必然读到同一份值，不会出现两边各写一半、
     * 客户端拿着空 token 去撞门（实测发生过：客户端在配置生成之前就先读了配置）。</p>
     */
    public File runtimeFile() {
        try {
            Boot b = liveBoot();
            return new File(b == null ? new File(".") : b.root(), "debug-port.json");
        } catch (Throwable t) { return new File("debug-port.json"); }
    }

    /** 上次写凭据文件用的数据根（{@link #stop} 里删它用；只活一个口时也要删得到）。 */
    private static volatile File runtimeRoot;

    /**
     * 写运行现场凭据文件（<b>唯一真源</b>）：输出口 + 输入口<b>一起描述</b>，客户端读一次就够。
     *
     * <p><b>向后兼容是硬约束</b>：{@code name/host/port/pid/token/token_fp/ts} 七个老字段
     * <b>原样保留</b>（{@code _dbg.ps1} / {@code _bridge.ps1} 都按它们取值），本批只<b>追加</b>
     * {@code sim*} 一族（{@code simName/simHost/simPort/simToken/simToken_fp/simulate/simListening}）。</p>
     *
     * <p>静态方法：输出口没起来（被占 / 绑不上）而输入口起来了时，也必须写得出这份文件 ——
     * 否则客户端连"输入口在哪个端口、token 是什么"都读不到。</p>
     */
    static void writeRuntimeFile(Boot boot, Out out, int port, String token, boolean listening,
                                 int simPort, boolean simulate, boolean simListening) {
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("name", NAME);
            o.addProperty("host", "127.0.0.1");
            o.addProperty("port", port);
            o.addProperty("pid", currentPid());
            o.addProperty("token", token);
            o.addProperty("token_fp", fp(token));
            o.addProperty("listening", listening);
            o.addProperty("ts", System.currentTimeMillis());
            // ---- 追加：调试输入口。老字段一个都没动 ----
            o.addProperty("simName", DebugSimPort.NAME);
            o.addProperty("simHost", "127.0.0.1");
            o.addProperty("simPort", simPort);
            o.addProperty("simToken", token);                 // 两口共用同一个 token
            o.addProperty("simToken_fp", fp(token));
            o.addProperty("simulate", simulate);
            o.addProperty("simListening", simListening);
            String n = DebugSimPort.note();
            if (n != null && !n.isEmpty()) o.addProperty("simNote", n);
            File root = boot == null ? new File(".") : boot.root();
            File f = new File(root, "debug-port.json");
            runtimeRoot = root;
            // 写前**没有权限判定**（新口径：权限只管控技能 / op，资源不再有位、不再按路径判）。
            // 这一笔是基板内部动作（写自己的运行现场凭据），不是技能调用 —— 所以没有可判的 op，
            // 也不该借身份去"临时放行一次资源写"（旧口径那句 B 类受保护资源的 needWrite 已随资源位一起退休）。
            Fs.write(f, sair.v4.kit.J.pretty(o));
        } catch (Throwable t) {
            warn(out, "写运行现场凭据文件失败（调试口照常可用，但客户端要显式给 token）：" + t);
        }
    }

    /** 删凭据文件（两个口都停了才该删；这里不看 {@code current}，只看"上次写到哪"）。 */
    private static void removeRuntimeFileStatic() {
        try {
            File r = runtimeRoot;
            File f = r == null ? null : new File(r, "debug-port.json");
            if (f != null && f.exists()) f.delete();
        } catch (Throwable ignored) { }
    }

    /** 当前 JVM 的 pid（写现场文件用；取不到就 -1）。 */
    private static long currentPid() {
        try {
            java.lang.management.RuntimeMXBean rt = java.lang.management.ManagementFactory.getRuntimeMXBean();
            String n = rt.getName();
            int i = n.indexOf('@');
            return Long.parseLong(i > 0 ? n.substring(0, i) : n);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** token 指纹（诊断用；<b>绝不回显 token 本身</b>）。 */
    public static String fp(String t) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((t == null ? "" : t).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(8);
            for (int i = 0; i < 4; i++) sb.append(String.format("%02x", Byte.valueOf(d[i])));
            return sb.toString();
        } catch (Throwable e) {
            return "?";
        }
    }

    /**
     * 停掉当前实例（幂等；不在跑就什么都不做）。两个口一起停。
     *
     * <p><b>只停两个口</b>：调用点是 {@code ai/debug off}（命令层）与
     * {@code DebugShell.detach()}（壳卸载）。落盘<b>不归它管</b>（主人 2026-09-16 追加裁定），
     * 基板停摆（{@code Boot.stop()}）也<b>不再</b>走这里 —— 停基板不等于停观测面。
     * 凭据文件（{@code debug-port.json}）随两个口的停止而删：它是"口在听"的运行现场凭据。</p>
     */
    public static void stop() {
        DebugPort dp = current;
        current = null;
        capture = false;
        try { sair.v4.dev.DebugSimPort.stop(); } catch (Throwable ignored) { }
        if (dp != null) {
            dp.stopping = true;
            try {
                ServerSocket ss = dp.server;
                if (ss != null) ss.close();                 // 关监听 → accept 抛异常退出
            } catch (Throwable ignored) {
            }
            try {
                Thread t = dp.acceptThread;
                if (t != null) t.interrupt();
            } catch (Throwable ignored) {
            }
            info(dp.out, "调试输出口已停止");
        }
        removeRuntimeFileStatic();
    }

    /** 调试口在跑吗（诊断面）。 */
    public static boolean running() { return current != null; }

    /** 当前端口（没跑返回 -1）。 */
    public static int activePort() {
        DebugPort dp = current;
        return dp == null ? -1 : dp.port;
    }

    /** 当前凭据的指纹（没跑返回空串）。<b>绝不回 token 原文</b>（{@code ai/debug status} 用它）。 */
    public static String activeTokenFp() {
        DebugPort dp = current;
        return dp == null ? "" : fp(dp.token);
    }

    // ==================== accept 循环 ====================

    private void loop() {
        while (!stopping) {
            final Socket s;
            try {
                s = server.accept();
            } catch (Throwable e) {
                if (!stopping) warn(out, "accept 失败（调试口继续等）：" + e);
                continue;
            }
            if (conns.get() >= MAX_CONN) {
                // 超上限：回一行就关，绝不让连接堆积
                try {
                    s.setSoTimeout(2000);
                    OutputStream os = s.getOutputStream();
                    os.write(("[debug] code=err reason=busy max=" + MAX_CONN + "\n" + END + "\n")
                            .getBytes("UTF-8"));
                    os.flush();
                } catch (Throwable ignored) {
                }
                closeQuietly(s);
                warn(out, "连接被拒：已达上限 " + MAX_CONN);
                continue;
            }
            conns.incrementAndGet();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try { serve(s); } catch (Throwable ignored) { } finally {
                        conns.decrementAndGet();
                        closeQuietly(s);
                    }
                }
            }, "v4-debugport-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    // ==================== 一条连接 ====================

    private void serve(Socket s) throws Exception {
        s.setSoTimeout(IDLE_MS);                             // 空闲超时：一个 socket 挂不死
        s.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        OutputStream os = s.getOutputStream();

        String hello = in.readLine();
        if (hello == null) return;
        if (hello.length() > MAX_LINE) { closeQuietly(s); return; }
        if (!checkAuth(hello)) {
            write(os, "[debug] code=err reason=bad_token note=check_debug_port_json");
            rejectLog(safeHost(s));
            return;
        }
        write(os, "[debug] code=ok auth=1 name=" + NAME + " port=" + port);
        info(out, "调试口接入：" + safeHost(s));

        String line;
        while ((line = in.readLine()) != null) {
            if (line.length() > MAX_LINE) {
                write(os, "[debug] code=err reason=line_too_long max=" + MAX_LINE);
                warn(out, "断开一个连接：单条命令超过 " + MAX_LINE + " 字符");
                return;
            }
            String cmd = line.trim();
            if (cmd.isEmpty()) continue;
            boolean close = dispatch(os, cmd);
            if (close) return;
        }
    }

    /**
     * 认证失败的日志<b>限流</b>：第一次立刻记一行，之后同一来源每 {@link #REJECT_LOG_MS} 秒最多一行，
     * 并把累计次数带上。理由是客户端一旦陷入重试，逐条记会把控制台刷爆（实测发生过），
     * 掩盖真正要看的东西。token 本身<b>永不</b>进日志。
     */
    private void rejectLog(String host) {
        int n = rejectTotal.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = rejectLastLogMs;
        if (n != 1 && now - last < REJECT_LOG_MS) return;
        rejectLastLogMs = now;
        warn(out, "拒绝一个连接：token 不对（来自 " + host + "，累计 " + n + " 次）。"
                + "客户端应与实例读同一份凭据：运行现场的 debug-port.json（端口 " + port + "）。"
                + "本行最多每 " + (REJECT_LOG_MS / 1000) + " 秒记一次。");
    }

    /** 认证：{@code AUTH <token>} 常量时间比对。 */
    private boolean checkAuth(String hello) {
        String h = hello.trim();
        if (h.length() < 5) return false;
        if (!h.regionMatches(true, 0, "AUTH", 0, 4)) return false;
        String got = h.substring(4).trim();
        return constantTimeEquals(got, token);
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] x = a.getBytes();
        byte[] y = b.getBytes();
        if (x.length != y.length) return false;
        int r = 0;
        for (int i = 0; i < x.length; i++) r |= (x[i] ^ y[i]);
        return r == 0;
    }

    /**
     * 处理一条命令。
     *
     * @return true = 这条处理完要关连接（quit / exit）
     */
    private boolean dispatch(OutputStream os, String cmd) {
        String low = cmd.toLowerCase();
        try {
            if ("ping".equals(low)) { write(os, "[debug] code=ok pong=1"); return false; }
            if ("help".equals(low) || "/?".equals(low)) { write(os, "[debug] code=ok", HELP); return false; }
            if ("status".equals(low)) { write(os, "[debug] code=ok", status()); return false; }
            // 「一次取全部输出」：从落盘读，不受内存窗口（默认 500 条 / 64KB）限制。
            // 放在 talk*/read/tail 之前：talkdump 会被下面的 talk 前缀吃掉，顺序不能反。
            if (low.startsWith("talkdump")) {
                write(os, "[debug] code=ok", dump(true, parse1(cmd, 0L)));
                return false;
            }
            if (low.startsWith("dump")) {
                write(os, "[debug] code=ok", dump(false, parse1(cmd, 0L)));
                return false;
            }
            if ("quit".equals(low) || "bye".equals(low)) { write(os, "[debug] code=ok bye=1"); return true; }
            if ("exit".equals(low) || "/exit".equals(low)) {
                // 主人说的那条路：SairCons.runner(false, "/exit") → FrameActivity.exit()
                // → 框架 ConsFrame.close()。本组件路径**不结束进程**（全程 0 处 System.exit；
                // 真退出要另走 ai/exit → V4Activity.exit() → Boot.stop()，那也是"停基板"，
                // 同样不结束 JVM）。端口/日志/基板会关掉，**进程还在**。
                // 先把这个块发出去再动手，否则回包会随着框架关闭一起消失。
                write(os, "[debug] code=ok exit=1 note=SairCons.runner(false,\"/exit\") → 框架 ConsFrame.close()；本组件路径不结束进程（见 ai/exit）");
                info(out, "调试口收到 exit：走 SairCons.runner(false, \"/exit\") 关闭 SFW");
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try { Thread.sleep(150L); } catch (InterruptedException ignored) { }
                        try {
                            SairCons.runner(false, "/exit");
                        } catch (Throwable e) {
                            err(out, "exit 失败（进程可能还在）：" + e);
                        }
                    }
                }, "v4-debugport-exit");
                t.setDaemon(true);
                t.start();
                return true;
            }
            if (low.startsWith("talksay")) {
                long[] a = parse2(cmd, 0L, TALK_READ_CHARS);
                write(os, "[debug] code=ok", talkSay(a[0], (int) a[1]));
                return false;
            }
            if (low.startsWith("talktext")) {
                long[] a = parse2(cmd, 0L, TALK_READ_CHARS);
                write(os, "[debug] code=ok", talkText(a[0], (int) a[1]));
                return false;
            }
            if (low.startsWith("talktail")) {
                write(os, "[debug] code=ok", talkTail((int) parse1(cmd, 50L)));
                return false;
            }
            if (low.startsWith("talk")) {
                long[] a = parse2(cmd, 0L, TALK_READ_CHARS);
                write(os, "[debug] code=ok", talkRead(a[0], (int) a[1]));
                return false;
            }
            if (low.startsWith("read")) {
                long[] args = parse2(cmd, 0L, 4000);
                write(os, "[debug] code=ok", SfwOut.read(args[0], (int) args[1], null));
                return false;
            }
            if (low.startsWith("tail")) {
                long n = parse1(cmd, 50L);
                write(os, "[debug] code=ok", tail((int) n));
                return false;
            }
            if (low.startsWith("hist")) {
                long n = parse1(cmd, 20L);
                write(os, "[debug] code=ok", SfwOut.history((int) n));
                return false;
            }
            // 其它一切：当框架控制台命令执行（等价于人在控制台敲）
            info(out, "调试口命令：" + cmd);
            // 命令顺带产生的"对话正文"（ai/chat 的回话走独立控件，第一路看不见）一并回给调用方，
            // 这样 -Cmd "ai/chat 你好" 一次就能同时拿到控制台输出与她的回复，不用再对齐游标。
            long t0 = talkCursor();
            String body = SfwOut.run(cmd);
            String talk = talkText(t0, TALK_READ_CHARS);
            if (talk.indexOf('\n') >= 0 && talk.length() > talk.indexOf('\n') + 1) {
                body = body + "\n--- talk (new since #" + t0 + ") ---\n" + talk;
            }
            write(os, "[debug] code=ok", body);
            return false;
        } catch (Throwable e) {
            err(out, "调试口命令异常：" + cmd + " → " + e);
            write(os, "[debug] code=err reason=exception error=" + oneLine(String.valueOf(e)));
            return false;
        }
    }

    // ==================== 各条命令的正文 ====================

    /** 一行摘要 + 细项。事实化，不做解释性文案。 */
    private String status() {
        StringBuilder sb = new StringBuilder(512);
        try {
            // ★ 现取活基板（绝不缓存）：ai/debug on 可以在 ai/start 之前敲，
            //   那时这里是"壳基板 = not_started"（正确），ai/start / ai/restart 之后
            //   这里立刻是新的那台（同一时刻控制台 ai/status 说什么，这里就说什么）。
            final Boot boot = liveBoot();
            boolean ready = boot != null && boot.started() && !boot.loading();
            // 没就绪时把原因说出来（新口径：没敲 ai/start 的基板是一个正常状态，不是"崩溃/挂住"）
            String reason = ready ? "" : (boot == null ? "no_substrate"
                    : boot.loading() ? "loading" : (boot.degraded() ? "degraded" : "not_started"));
            int pending = -1;
            try {
                if (boot != null && boot.conf() != null) pending = boot.conf().pendingKeys().size();
            } catch (Throwable ignored) { }
            int skills = boot == null || boot.skills() == null ? -1 : boot.skills().size();
            int tools = boot == null || boot.registry() == null ? -1 : boot.registry().size();
            int ext = -1;
            try {
                if (boot != null && boot.ext() != null) {
                    com.google.gson.JsonObject es = boot.ext().stat();
                    // ext 统计 = 四类贡献点之和（provider/stage/voter/hook），与 status.ext 同源
                    ext = sair.v4.kit.J.i(es, "providers", 0) + sair.v4.kit.J.i(es, "stages", 0)
                            + sair.v4.kit.J.i(es, "voters", 0) + sair.v4.kit.J.i(es, "hooks", 0);
                }
            } catch (Throwable ignored) { }
            boolean napcat = false;
            try { napcat = boot != null && boot.link() != null && boot.link().running(); } catch (Throwable ignored) { }
            long seq = SfwOut.cursor();
            int lines = ConsoleTap.lines();
            String src = ConsoleTap.sourceName();
            boolean tap = ConsoleTap.installed();
            boolean sfw = ConsoleTap.available();

            sb.append("ready=").append(ready ? 1 : 0)
              .append(Str.has(reason) ? " reason=" + reason : "")
              .append(" substrate=").append(ready ? "running" : "not_started")
              .append(" pending=").append(pending)
              .append(" loading=").append(boot != null && boot.loading() ? 1 : 0)
              .append(" degraded=").append(boot != null && boot.degraded() ? 1 : 0)
              .append(" skills=").append(skills)
              .append(" tools=").append(tools)
              .append(" ext=").append(ext)
              .append(" napcat=").append(napcat ? 1 : 0)
              .append(" tap=").append(tap ? 1 : 0)
              .append(" sfw_console=").append(sfw ? 1 : 0)
              .append(" src=").append(src)
              .append(" lines=").append(lines)
              .append(" seq=").append(seq)
              .append(" err_lines=").append(countErrLines())
              .append(" talk_lines=").append(talkSnapshot().size())
              .append(" talk_seq=").append(talkCursor())
              .append(" tag=").append(DebugShell.host() == null ? "none" : "shell")
              .append(" port=").append(port)
              .append(" conns=").append(conns.get());
            if (boot != null) {
                // 没启动时不要说"第 0/19 步（装配线程已起）"—— 新口径下没有装配线程，那是骗人的
                sb.append("\nstep=").append(boot.loading() || boot.started()
                        ? boot.progressLine() : "not_started（要 ai/start；除非主基板状态，调试面照常可用）");
                if (boot.degraded()) {
                    sb.append("\nfailed_step=").append(boot.failedStep())
                      .append(" reason=").append(boot.failedReason());
                }
            }
            sb.append("\n").append(facts());
            sb.append("\n").append(aclFact(boot));
            sb.append("\n").append(sair.v4.dev.DebugLog.facts());
            sb.append("\n").append(sair.v4.dev.DebugSimPort.facts());
        } catch (Throwable e) {
            sb.append("status 计算异常：").append(e);
        }
        return sb.toString();
    }

    /** 最近缓冲里带 err 语义色的行数（"最近错误行数"，就是主人要的那个数）。 */
    private int countErrLines() {
        int n = 0;
        try {
            java.util.List<ConsoleTap.Line> snap = ConsoleTap.snapshot();
            for (ConsoleTap.Line l : snap) if ("err".equals(l.tone)) n++;
        } catch (Throwable ignored) {
        }
        return n;
    }

    /** 缓冲事实行（{@code [console] lines=… seq_head=…}）。 */
    private String facts() { return ConsoleTap.facts(SfwOut.cursor()); }

    /**
     * 权限表事实行（{@code status} 里带一行，新口径：权限只剩"身份 × op → Ban/Run/空"，
     * 读的是<b>合并后</b>的整张表：core + 每个技能自己的 {@code perms.jsonc}）。
     *
     * <p>数字一律问账本自己（{@link Acl#stat()}，形如
     * {@code 3 条（Run 2 / Ban 1），覆盖 op 3 个}）—— 调试面只转述，不自己数、不编兜底值：
     * 权限面没装 / 账本读不到就照实说"未装配 / 没能装载"，那本身就是要看的事实。</p>
     */
    private String aclFact(Boot boot) {
        try {
            if (boot == null || boot.auth() == null) return "[acl] 权限表：权限面未装配";
            Acl a = boot.auth().acl();
            if (a == null) return "[acl] 权限表：没能装载（" + Acl.CORE_FILE_NAME + " / "
                    + Acl.SKILL_FILE_NAME + "）";
            return "[acl] 权限表：" + a.stat() + "；" + a.sourceStat();
        } catch (Throwable t) {
            return "[acl] 权限表：读取异常（" + oneLine(String.valueOf(t)) + "）";
        }
    }

    /** 最近 N 条捕获输出（不是增量，是"现在往回看 N 条"）。 */
    private String tail(int n) {
        int want = n <= 0 ? 50 : Math.min(n, 1000);
        java.util.List<ConsoleTap.Line> snap = ConsoleTap.snapshot();
        if (snap.isEmpty()) return "…(捕获缓冲还是空的：框架还没打印过东西)";
        int from = Math.max(0, snap.size() - want);
        long since = from == 0 ? 0L : snap.get(from - 1).seq;   // 读 from 之后（含 from）
        return SfwOut.read(since, MAX_READ_CHARS, null);
    }

    /**
     * 「一次取全部输出」：{@code dump}（控制台）/ {@code talkdump}（对话）。
     *
     * <p>读的是<b>落盘</b>（{@code <数据根>\logs\console-<日期>.log} / {@code talk-<日期>.log}），
     * 所以内存里那个 500 条 / 64KB 的窗口<b>不再是上限</b>；单次响应有
     * {@link sair.v4.dev.DebugLog#MAX_DUMP_CHARS} 的上限，超了在结尾给
     * "还有 N 字符，用 since_seq=&lt;游标&gt; 续取"。</p>
     */
    private String dump(boolean talk, long sinceSeq) {
        String k = talk ? "talkdump" : "dump";
        try {
            if (!sair.v4.dev.DebugLog.enabled()) {
                return "[" + k + "] on=0（落盘没装：调试输出口刚起，或者装不上）"
                        + "\n" + sair.v4.dev.DebugLog.facts();
            }
            return sair.v4.dev.DebugLog.dump(talk, sinceSeq < 0L ? 0L : sinceSeq);
        } catch (Throwable e) {
            return "[" + k + "] error=" + oneLine(String.valueOf(e));
        }
    }

    // ==================== 参数解析 ====================

    /** {@code read [sinceSeq] [maxChars]}。 */
    private static long[] parse2(String cmd, long d0, long d1) {
        String[] p = cmd.split("\\s+");
        long a = d0, b = d1;
        if (p.length > 1) { try { a = Long.parseLong(p[1].trim()); } catch (Throwable ignored) { } }
        if (p.length > 2) {
            try { b = Long.parseLong(p[2].trim()); } catch (Throwable ignored) { }
        }
        if (b <= 0) b = d1;
        if (b > MAX_READ_CHARS) b = MAX_READ_CHARS;
        return new long[] { a, b };
    }

    /** {@code tail|hist [N]}。 */
    private static long parse1(String cmd, long def) {
        String[] p = cmd.split("\\s+");
        if (p.length > 1) { try { return Long.parseLong(p[1].trim()); } catch (Throwable ignored) { } }
        return def;
    }

    // ==================== 写回 ====================

    /**
     * 写一个响应块：事实行 + 正文 + 结束哨。
     * <p>正文里若出现与 {@link #END} 相同的行，加一个前导空格转义 —— 保证哨兵唯一。</p>
     */
    private static void write(OutputStream os, String head) { write(os, head, null); }

    private static void write(OutputStream os, String head, String body) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(head).append('\n');
        if (body != null && !body.isEmpty()) {
            String[] rows = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (String r : rows) {
                if (END.equals(r)) sb.append(' ').append(r).append('\n');
                else sb.append(r).append('\n');
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n'
                    && body.endsWith("\n")) {
                // 正文自带结尾换行：不额外补，保持行数可预期
            }
        }
        sb.append(END).append('\n');
        try {
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush();
        } catch (Throwable ignored) {
        }
    }

    // ==================== 小工具 ====================

    private static String safeHost(Socket s) {
        try { return String.valueOf(s.getRemoteSocketAddress()); } catch (Throwable t) { return "?"; }
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null) s.close(); } catch (Throwable ignored) { }
    }

    private static String oneLine(String s) {
        try { return Str.oneLine(s); } catch (Throwable t) { return s; }
    }

    private static void info(Out out, String msg) {
        try { if (out != null) out.dim(TAG + msg); } catch (Throwable ignored) { }
    }

    private static void warn(Out out, String msg) {
        try { if (out != null) out.warn(TAG + msg); } catch (Throwable ignored) { }
    }

    private static void err(Out out, String msg) {
        try { if (out != null) out.err(TAG + msg); } catch (Throwable ignored) { }
    }

    /** 命令表正文。 */
    private static final String HELP =
            "本机调试输出口 " + NAME + "（只绑 127.0.0.1，必带 token；输入口见 127.0.0.1:"
                    + Conf.DEF_DEBUG_SIM_PORT + "，客户端 _sim.ps1）\n"
          + "  ping                        连通性\n"
          + "  status                      一行摘要（ready/loading/degraded/skills/tools/ext/napcat/tap/lines/seq/err_lines）+ 事实行\n"
          + "                              （事实行含 [acl] 技能管控表：N 条（Run x / Ban y）、覆盖 op k 个）\n"
          + "  read [sinceSeq] [maxChars]  增量读控制台捕获（**内存窗口**，游标式）：把上次回的 seq 当 sinceSeq 传回来只拿新增\n"
          + "  tail [N]                    读最近 N 条捕获输出（默认 50，上限 1000）\n"
          + "  dump [sinceSeq]             **一次取全部**控制台输出（从落盘 logs\\console-<yyyyMMdd>.log 读，不受内存窗口限制）\n"
          + "                              单次上限 4MB；超了结尾给「还有 N 字符，用 since_seq=<游标> 续取」\n"
          + "  talk [sinceSeq] [maxChars]  增量读【对话正文】通道（注解版：每条一行 #seq 时间 [kind] 文本）\n"
          + "  talktext [sinceSeq] [maxChars] 增量拼正文：stream 增量相接（自动跳过 say-dup）= 她这一轮完整回话\n"
          + "  talksay [sinceSeq] [maxChars]  只看最终整句（say 桶，不含 stream 增量）\n"
          + "  talktail [N]                最近 N 条对话记录（默认 50）\n"
          + "  talkdump [sinceSeq]         **一次取全部**对话/聊天框内容（从落盘 logs\\talk-<yyyyMMdd>.log 读，同上限与续取口径）\n"
          + "  hist [N]                    框架命令历史最近 N 条（默认 20）\n"
          + "  help                        这张表\n"
          + "  exit | /exit                关框架：SairCons.runner(false,\"/exit\") → 框架 ConsFrame.close()；本组件路径不结束进程\n"
          + "  quit | bye                  只关这条连接\n"
          + "  其它任何一行                当框架控制台命令执行（例如 ai/status、ai/restart、ai/chat 你好）\n"
          + "                              —— 命令顺带产生的对话正文会自动附在回包末尾的 --- talk (new ...) --- 段里\n"
          + "两路捕获：控制台捕获（read/tail/dump）看框架杂项输出；对话通道（talk/talktext/talkdump）看她说了什么。\n"
          + "落盘策略：logs\\ 下按天一个文件，8MB 滚动封存（改名归档，**旧文件只增不删**）。\n"
          + "结束哨：" + END + "（正文里出现同名行会被加一个前导空格转义）";
}
