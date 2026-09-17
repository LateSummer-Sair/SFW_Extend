package sair.v4.dev;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonObject;

import sair.v4.Boot;
import sair.v4.Conf;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.Link;
import sair.v4.term.SfwOut;

/**
 * 本机调试<b>输入</b>口（127.0.0.1:{@value sair.v4.Conf#DEF_DEBUG_SIM_PORT}）：
 * 把"外面能伪造出什么"这件事收成一条有闸门、有审计的通路。
 *
 * <p><b>起停口径（主人 2026-09-16）</b>：本口只由控制台 {@code ai/debug on|off} 起停
 * （装配不再自动起它），端口/token/仿真开关都走命令参数 —— 它们已经不是 {@code config.json}
 * 里的键了。</p>
 *
 * <h3>它能干两件事（就这两件）</h3>
 * <ol>
 *   <li><b>以任意 QQ 身份（含主人）造一条入站消息</b> —— {@code msg private}/{@code msg group}，
 *       以及原样的 {@code event}（任意 NapCat 同形状事件）。落点是
 *       {@link Link#inject(JsonObject)} → {@code Link.dispatch}，也就是真实 WS 帧走的<b>同一个入口</b>：
 *       去重、黑名单、技能钩子、调度、回合、ACL 判定<b>一样都不少</b>。
 *       身份由 {@code user_id} 现算 —— 等于 {@code masterQQ} 就是 MASTER，否则 ALLUSER
 *       （按 D43，ALLUSER 的 T 类默认无位 ⇒ 她的工具表为空，动作被闸门拒；那是<b>正确</b>行为）。</li>
 *   <li><b>以框架控制台的身份下一条 SFW 命令</b> —— {@code cmd}，走的
 *       {@link SfwOut#run(String)}，与 {@code console} 工具 {@code op=run} 是<b>同一条路</b>，
 *       没有第二条实现。</li>
 * </ol>
 *
 * <h3>闸门（唯一的一道，写清楚）</h3>
 * <ul>
 *   <li><b>只绑 {@code 127.0.0.1}</b>（不监听 0.0.0.0，外部网卡够不着）；</li>
 *   <li><b>token 必填</b>：token 为空 ⇒ <b>本口拒绝启动</b>并打一行可读原因，
 *       实例照常可用（与输出口同一条铁律；两口共用同一个 token —— {@code ai/debug on}
 *       不给就现生成一个，写进运行现场凭据文件）；</li>
 *   <li>{@code ai/debug simulate off} ⇒ 端口<b>照常监听、AUTH 照常能过</b>，但每个动作回
 *       {@code -ERR debugSimulate=off} —— <b>绝不表现成"连不上"</b>。</li>
 * </ul>
 * <p><b>这个口的分量（不许含糊）</b>：拿到 token 的本机进程，等价于
 * 「<b>能以任意 QQ 身份（含主人）造一条入站消息</b>」＋「<b>能在框架控制台敲任意命令</b>」。
 * 它不进 {@code ai/help}（隐藏能力），只在 {@code docs\} 里写明给技能开发者调试用。</p>
 *
 * <h3>协议（行文本 UTF-8；短连接 —— 连一次、AUTH、把命令一次发完、读回执、关）</h3>
 * <pre>
 * 客户端连上后第一行必须是： AUTH &lt;token&gt;
 *   成功 → "+OK AiAgentV4_DebugSim"（失败 → "-ERR bad_token"，随后断开）
 * 之后每行一条命令，服务端逐行回一个以 [sim:end] 收尾的块：
 *   +OK …  /  -ERR &lt;原因&gt;
 *   …若干事实行…
 *   [sim:end]
 * 命令表：
 *   ping                                    连通性（不触发任何动作）
 *   help                                    这张表
 *   msg private &lt;qq&gt; &lt;text&gt;                 注入私聊（user_id=&lt;qq&gt;）
 *   msg group &lt;gid&gt; &lt;qq&gt; [role] &lt;text&gt;       注入群消息（role = member|admin|owner，缺省 member）
 *   event &lt;一行 JSON&gt;                        任意 NapCat 同形状事件（必须带 post_type）
 *   cmd &lt;一行 SFW 命令&gt;                      直接下发框架命令（SfwOut.run，与 console op=run 同路）
 *   close | quit | bye                       断开
 * </pre>
 * <p>{@code <text>} 是<b>行尾原样</b>（空格保留），并且支持 CQ 码 —— 例如
 * {@code msg group 121873503 10001 member [CQ:at,qq=2671623601] 帮我查一下余额}。
 * 事件里的 {@code message} 按"message_format=string"的形状给出（{@code Ev} 会解析成段），
 * 与 NapCat 的另一种部署同形。</p>
 *
 * <h3>线程与容错</h3>
 * <p>一条 accept 线程 + 每连接一条守护线程，全部 {@code catch (Throwable)}。
 * 输入口自身<b>绝不能</b>让基板起不来或停不下来；它起不来时只记一行可读原因，基板与输出口都不受影响。</p>
 */
public final class DebugSimPort {

    /** 名字（日志、回执、凭据文件里都用它）。 */
    public static final String NAME = "AiAgentV4_DebugSimPort";

    /** AUTH 成功那一行回的名字（客户端可据此确认连的是输入口）。 */
    public static final String HELLO = "AiAgentV4_DebugSim";

    /** 结束哨。 */
    public static final String END = "[sim:end]";

    /** 单条入站行上限（字符）：事件 JSON / 命令都比较长，给足余量。 */
    public static final int MAX_LINE = 65536;

    /** 并发连接上限。 */
    public static final int MAX_CONN = 4;

    /** 空闲超时（毫秒）。 */
    public static final int IDLE_MS = 120000;

    private static final String TAG = NAME + " ";

    /** 注入行 / 审计行里正文的截断长度。 */
    private static final int AUDIT_CUT = 200;

    private static volatile DebugSimPort current;

    /** 拒绝日志限流（与输出口同一口径）。 */
    public static final long REJECT_LOG_MS = 10000L;
    private static final AtomicInteger rejectTotal = new AtomicInteger();
    private static volatile long rejectLastLogMs = 0L;

    private final Out out;
    private final int port;
    private final String token;
    private final boolean simulate;
    private final AtomicInteger conns = new AtomicInteger();
    private volatile ServerSocket server;
    private volatile Thread acceptThread;
    private volatile boolean stopping;

    /** 最近一次起不来的可读原因（进 {@code debug-port.json} 的 {@code simNote} 与 status）。 */
    private static volatile String note = "";

    /** 最近一次读到的配置端口（状态面用：口没起来时也要报**配置里的那个**端口，不能报别的数字）。 */
    private static volatile int lastPort = Conf.DEF_DEBUG_SIM_PORT;

    /** 注入消息的 message_id 发生器（只保证去重窗口内不重复）。 */
    private static final AtomicLong MSG_ID = new AtomicLong(0L);

    private DebugSimPort(Out out, int port, String token, boolean simulate) {
        this.out = out;
        this.port = port;
        this.token = token;
        this.simulate = simulate;
    }

    // ==================== 生命周期 ====================

    /**
     * 起输入口（幂等：重复调用先停旧的）。<b>端口/token/simulate 全部来自调用方</b>
     * （= 控制台 {@code ai/debug on}），不再从 {@code config.json} 读。
     * <b>契约：不抛异常</b>（起不来只记一行可读原因）。
     *
     * @param port     监听端口（{@code <=0} = 不起）
     * @param token    凭据（<b>空 = 拒绝启动</b>）
     * @param simulate 仿真开关（默认 {@link Conf#DEF_DEBUG_SIMULATE} = true）
     * @return 真的起来了吗
     */
    public static boolean start(Boot boot, Out out, int port, String token, boolean simulate) {
        stop();
        note = "";
        int p = port;
        String t = Str.nz(token).trim();
        boolean sim = simulate;
        lastPort = p;
        lastSimulate = sim;
        if (p <= 0) {
            info(out, "调试输入口关闭（端口 " + p + "；0=关）");
            note = "输入口端口=0（没给输入口）";
            return false;
        }
        if (p < 1024 || p == 2671 || p == 8082 || p == 8083) {
            warn(out, "调试输入口未启动：端口 " + p
                    + " 落在保留/特权端口（<1024，或 2671 中转 / 8082 NapCat / 8083）。"
                    + "请改用别的冷门端口（默认 " + Conf.DEF_DEBUG_SIM_PORT + "）再 ai/debug on。");
            note = "输入口端口 " + p + " 是保留/特权端口";
            return false;
        }
        if (p == DebugPort.activePort()) {
            warn(out, "调试输入口未启动：输入口与输出口都是 " + p
                    + " —— 输出口与输入口必须是两个不同的端口（默认 "
                    + Conf.DEF_DEBUG_PORT + " / " + Conf.DEF_DEBUG_SIM_PORT + "）。");
            note = "输入口与输出口撞在同一个端口 " + p;
            return false;
        }
        if (t.isEmpty()) {
            // 硬规矩：不许无 token 裸开（与输出口同一条）
            warn(out, "调试输入口未启动：token 为空（端口 " + p
                    + "）。本机调试口必须带 token —— ai/debug on 不给 token 时会现生成一个。");
            note = "token 为空（端口 " + p + "）";
            return false;
        }
        DebugSimPort dp = new DebugSimPort(out, p, t, sim);
        try {
            ServerSocket ss = new ServerSocket();
            // 只绑回环（与输出口同一条理由：不用 bind(port) 的无参重载，那个等价 0.0.0.0）
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 8);
            ss.setReuseAddress(true);
            dp.server = ss;
        } catch (Throwable e) {
            warn(out, "调试输入口未启动：绑定 127.0.0.1:" + p + " 失败（端口被占用？）：" + e
                    + " —— 实例照常可用；调试输出口不受影响。");
            note = "绑定 127.0.0.1:" + p + " 失败（端口被占用？）：" + Str.oneLine(String.valueOf(e));
            return false;
        }
        Thread at = new Thread(new Runnable() {
            @Override
            public void run() { dp.loop(); }
        }, "v4-debugsim");
        at.setDaemon(true);
        at.setName(NAME);
        dp.acceptThread = at;
        current = dp;
        at.start();
        info(out, "调试输入口已就绪：127.0.0.1:" + p + "（只绑回环 + 必带 token；token 指纹 " + DebugPort.fp(t)
                + "；simulate=" + (sim ? "on" : "off") + "）");
        info(out, "调试输入口能力：msg=以任意 QQ 身份（含主人）造一条入站消息（走 Link.inject → 真实入口），"
                + "cmd=下发任意 SFW 命令（SfwOut.run）；每次注入/命令都在这里打一行 [sim] 审计。");
        if (!sim) {
            warn(out, "调试输入口在听但 simulate=off：所有动作会回 -ERR debugSimulate=off（端口是通的，别去查网络）。");
        }
        return true;
    }

    /**
     * 改仿真开关（{@code ai/debug simulate on|off}）：<b>原端口原地重起</b>（端口/token 不变），
     * 所以"关掉"只改行为、不改可达性 —— 端口照常在听。
     *
     * @return 改成了吗（没在跑 / 重起失败都返回 false，原因在上面那行日志里）
     */
    public static boolean setSimulate(boolean on) {
        DebugSimPort dp = current;
        if (dp == null) return false;
        int p = dp.port;
        String t = dp.token;
        Out o = dp.out;
        if (!start(null, o, p, t, on)) {
            // 重起失败：至少把口的原状说清楚（别让"调试面没了"变成一句静默）
            warn(o, "调试输入口按新 simulate=" + (on ? "on" : "off") + " 重起失败：端口 " + p
                    + " 现在没有任何监听（" + Str.nz(note) + "）。");
            return false;
        }
        return true;
    }

    /** 停掉当前实例（幂等；不在跑就什么都不做）。 */
    public static void stop() {
        DebugSimPort dp = current;
        current = null;
        if (dp == null) return;
        dp.stopping = true;
        try {
            ServerSocket ss = dp.server;
            if (ss != null) ss.close();
        } catch (Throwable ignored) {
        }
        try {
            Thread t = dp.acceptThread;
            if (t != null) t.interrupt();
        } catch (Throwable ignored) {
        }
        info(dp.out, "调试输入口已停止");
    }

    /** 在听吗。 */
    public static boolean running() { return current != null; }

    /** 当前端口（没跑返回 -1）。 */
    public static int activePort() {
        DebugSimPort dp = current;
        return dp == null ? -1 : dp.port;
    }

    /** 最近一次起不来的原因（空串 = 没问题）。 */
    public static String note() { return note; }

    /** 最近一次交给本口的端口（没跑时也报**那个**数字；凭据文件/状态面用它）。 */
    public static int configuredPort() { return lastPort; }

    /**
     * 仿真开关现在是什么（没在跑就报"最近一次"用的那个值，从没起过则是出厂默认 true）。
     * <p>它不再是配置键（见 {@code Conf} 的调试口口径）：开关由 {@code ai/debug simulate on|off} 改。</p>
     */
    public static boolean simulateOn() {
        DebugSimPort dp = current;
        return dp == null ? lastSimulate : dp.simulate;
    }

    /** 最近一次起的仿真开关（没跑时状态面报它）。 */
    private static volatile boolean lastSimulate = Conf.DEF_DEBUG_SIMULATE;

    /** 诊断事实行（{@code status} 里带一行）。 */
    public static String facts() {
        DebugSimPort dp = current;
        StringBuilder sb = new StringBuilder(160);
        sb.append("[sim] on=").append(dp == null ? 0 : 1)
          .append(" port=").append(dp == null ? lastPort : dp.port)
          .append(" simulate=").append(simulateOn() ? "on" : "off")
          .append(" conns=").append(dp == null ? 0 : dp.conns.get());
        if (!note.isEmpty()) sb.append(" note=").append(Str.oneLine(note));
        return sb.toString();
    }

    // ==================== accept 循环 ====================

    private void loop() {
        while (!stopping) {
            final Socket s;
            try {
                s = server.accept();
            } catch (Throwable e) {
                if (!stopping) warn(out, "accept 失败（调试输入口继续等）：" + e);
                continue;
            }
            if (conns.get() >= MAX_CONN) {
                try {
                    s.setSoTimeout(2000);
                    OutputStream os = s.getOutputStream();
                    os.write((("+ERR busy max=" + MAX_CONN) + "\n" + END + "\n").getBytes("UTF-8"));
                    os.flush();
                } catch (Throwable ignored) {
                }
                closeQuietly(s);
                warn(out, "调试输入口连接被拒：已达上限 " + MAX_CONN);
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
            }, "v4-debugsim-conn");
            t.setDaemon(true);
            t.start();
        }
    }

    // ==================== 一条连接 ====================

    private void serve(Socket s) throws Exception {
        s.setSoTimeout(IDLE_MS);
        s.setTcpNoDelay(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
        OutputStream os = s.getOutputStream();

        String hello = in.readLine();
        if (hello == null) return;
        if (hello.length() > MAX_LINE) { closeQuietly(s); return; }
        if (!checkAuth(hello)) {
            block(os, "-ERR bad_token", "note=check_debug_port_json（本口与输出口共用同一个 token）");
            rejectLog(safeHost(s));
            return;
        }
        block(os, "+OK " + HELLO,
                "port=" + port + " simulate=" + (simulate ? "on" : "off") + " name=" + NAME
                + (simulate ? "" : "\n注意：simulate=off ⇒ 动作一律回 -ERR debugSimulate=off（端口是通的）"));
        info(out, "调试输入口接入：" + safeHost(s));

        String line;
        while ((line = in.readLine()) != null) {
            if (line.length() > MAX_LINE) {
                block(os, "-ERR line_too_long", "max=" + MAX_LINE);
                return;
            }
            String cmd = line.trim();
            if (cmd.isEmpty()) continue;
            if (dispatch(os, cmd)) return;
        }
    }

    /**
     * 处理一条命令。{@return true = 处理完要关连接（close/quit/bye）。
     */
    private boolean dispatch(OutputStream os, String cmd) {
        try {
            String low = cmd.toLowerCase();
            if ("close".equals(low) || "quit".equals(low) || "bye".equals(low)) {
                block(os, "+OK bye=1", null);
                return true;
            }
            if ("ping".equals(low)) { block(os, "+OK pong=1", null); return false; }
            if ("help".equals(low) || "/?".equals(low)) { block(os, "+OK help=1", HELP); return false; }
            if (!simulate) {
                // 关着的时候**不是连不上**：端口在听、AUTH 也过了，回的是明确的原因。
                block(os, "-ERR debugSimulate=off",
                        "note=ai/debug simulate off：端口照常监听，动作一律拒绝。"
                        + " 这是调试面的开关，不是网络/端口问题。");
                return false;
            }
            if (low.startsWith("msg")) return doMsg(os, cmd);
            if (low.startsWith("event")) return doEvent(os, cmd);
            if (low.startsWith("cmd")) return doCmd(os, cmd);
            block(os, "-ERR unknown_verb", "usage:\n" + HELP);
            return false;
        } catch (Throwable e) {
            err(out, "调试输入口命令异常：" + cmd + " → " + e);
            block(os, "-ERR exception", "error=" + oneLine(String.valueOf(e)));
            return false;
        }
    }

    // ==================== 动词：msg ====================

    private boolean doMsg(OutputStream os, String cmd) {
        String[] t = head(cmd, 3);
        String kind = t[1] == null ? "" : t[1].toLowerCase();
        if ("private".equals(kind)) {
            if (t[2] == null || Str.blank(t[3])) {
                block(os, "-ERR bad_args", "usage: msg private <qq> <text>");
                return false;
            }
            Long qq = num(t[2]);
            if (qq == null || qq.longValue() <= 0L) {
                block(os, "-ERR bad_qq", "qq=" + t[2]);
                return false;
            }
            String text = t[3];                        // 行尾原样（内部空格保留）
            JsonObject ev = privateEvent(qq.longValue(), text);
            return injectMsg(os, ev, "private user:" + qq.longValue(), qq.longValue(), 0L, "",
                    text, "msg private user:" + qq.longValue());
        }
        if ("group".equals(kind)) {
            String[] g = head(cmd, 4);
            if (g[2] == null || g[3] == null || Str.blank(g[4])) {
                block(os, "-ERR bad_args", "usage: msg group <gid> <qq> [role] <text>");
                return false;
            }
            Long gid = num(g[2]);
            Long qq = num(g[3]);
            if (gid == null || gid.longValue() <= 0L) {
                block(os, "-ERR bad_gid", "gid=" + g[2]);
                return false;
            }
            if (qq == null || qq.longValue() <= 0L) {
                block(os, "-ERR bad_qq", "qq=" + g[3]);
                return false;
            }
            // 可选 role：只有当"正文的第一个词"恰好是 member/admin/owner 时才当 role 吃掉，
            // 剩下的部分（含原有空白）原样当正文。
            String rest = g[4];
            String role = "member";
            String text = rest;
            int sp = firstWs(rest);
            String firstTok = sp < 0 ? rest : rest.substring(0, sp);
            if (isRole(firstTok)) {
                role = firstTok.toLowerCase();
                text = sp < 0 ? "" : rest.substring(sp).trim();
                if (Str.blank(text)) {
                    block(os, "-ERR bad_args", "usage: msg group <gid> <qq> [role] <text>");
                    return false;
                }
            }
            JsonObject ev = groupEvent(gid.longValue(), qq.longValue(), role, text);
            return injectMsg(os, ev, "group " + gid.longValue() + " " + role + " user:" + qq.longValue(),
                    qq.longValue(), gid.longValue(), role, text, "msg group " + gid.longValue());
        }
        block(os, "-ERR bad_args", "usage: msg private <qq> <text> | msg group <gid> <qq> [role] <text>");
        return false;
    }

    /**
     * 取一行命令的前 {@code n} 个空白分隔 token，并把<b>剩余部分原样</b>放在返回值最后一格
     * （保留内部原有空白 —— 消息正文不该被"折成单空格"）。
     *
     * @return 长度 {@code n+1}：{@code [0..n-1]} 是 token（缺则 null），{@code [n]} 是原样剩余（可能为 ""）
     */
    private static String[] head(String cmd, int n) {
        String[] out = new String[n + 1];
        String s = cmd == null ? "" : cmd;
        int len = s.length();
        int i = 0;
        int k = 0;
        while (k < n) {
            while (i < len && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= len) break;
            int st = i;
            while (i < len && !Character.isWhitespace(s.charAt(i))) i++;
            out[k] = s.substring(st, i);
            k++;
        }
        int j = i;
        while (j < len && Character.isWhitespace(s.charAt(j))) j++;
        out[n] = j < len ? s.substring(j) : "";
        return out;
    }

    /** 第一个空白字符的下标（没有则 -1）。 */
    private static int firstWs(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }

    private static boolean isRole(String s) {
        if (s == null) return false;
        String t = s.toLowerCase();
        return "member".equals(t) || "admin".equals(t) || "owner".equals(t);
    }

    // ==================== 动词：event ====================

    private boolean doEvent(OutputStream os, String cmd) {
        String json = cmd.length() > 5 ? cmd.substring(5).trim() : "";
        if (json.isEmpty()) {
            block(os, "-ERR bad_args", "usage: event <一行 JSON>（NapCat 同形状，必须带 post_type）");
            return false;
        }
        JsonObject o = J.obj(json);
        if (o == null) {
            block(os, "-ERR bad_json", "parse_failed chars=" + json.length());
            return false;
        }
        String post = J.s(o, "post_type", "");
        if (post.isEmpty()) {
            block(os, "-ERR event_missing_post_type",
                    "note=真实入口只处理带 post_type 的事件（Link.onText 的判据）；请补上 post_type。");
            return false;
        }
        long qq = J.l(o, "user_id", 0L);
        long gid = J.l(o, "group_id", 0L);
        String role = J.s(J.sub(o, "sender"), "role", "");
        String audit = "event post_type=" + post
                + (J.s(o, "message_type", "").isEmpty() ? "" : " message_type=" + J.s(o, "message_type", ""))
                + (gid > 0 ? " group=" + gid : "")
                + (qq > 0 ? " user:" + qq : "")
                + (role.isEmpty() ? "" : " role=" + role);
        audit(out, audit + " ← " + quote(Str.cut(Str.oneLine(json), AUDIT_CUT)));
        Link l = link();
        if (l == null) {
            block(os, "-ERR link_not_ready", "note=基板没有装配 NapCat 连接（napcat 步骤失败？）");
            return false;
        }
        l.inject(o);
        block(os, "+OK event injected=1 post_type=" + post
                        + (qq > 0 ? " user_id=" + qq + " master=" + (isMaster(qq) ? 1 : 0) : "")
                        + (gid > 0 ? " group_id=" + gid : ""),
                "route=Link.inject → Link.dispatch（真实入口）");
        return false;
    }

    // ==================== 动词：cmd ====================

    private boolean doCmd(OutputStream os, String cmd) {
        String c = cmd.length() > 3 ? cmd.substring(3).trim() : "";
        if (c.isEmpty()) {
            block(os, "-ERR bad_args", "usage: cmd <一行 SFW 命令>（例如 cmd ai/status）");
            return false;
        }
        audit(out, "cmd " + Str.cut(Str.oneLine(c), AUDIT_CUT));
        String body = SfwOut.run(c);
        // 回执口径：+OK 表示"这条命令已经交给框架控制台"（SfwOut.run，与 console op=run 同路）；
        // 框架自己成没成，原样附在正文里（它自己会打 ok=0 …），这里把那个结论也提到事实行上。
        boolean frameOk = body == null || !body.startsWith("[console] run ok=0");
        block(os, "+OK cmd dispatched=1 frame_ok=" + (frameOk ? 1 : 0), body);
        return false;
    }

    // ==================== 注入 ====================

    private boolean injectMsg(OutputStream os, JsonObject ev, String who,
                              long qq, long gid, String role, String text, String verb) {
        boolean master = isMaster(qq);
        audit(out, who + " ← " + quote(Str.cut(Str.oneLine(text), AUDIT_CUT)));
        Link l = link();
        if (l == null) {
            block(os, "-ERR link_not_ready", "note=基板没有装配 NapCat 连接（napcat 步骤失败？）");
            return false;
        }
        l.inject(ev);
        block(os, "+OK " + verb + " user_id=" + qq + " master=" + (master ? 1 : 0)
                        + (gid > 0 ? " group_id=" + gid + " role=" + role : "")
                        + " chars=" + Str.nz(text).length(),
                "identity=" + (master ? "MASTER" : "ALLUSER")
                        + " route=Link.inject → Link.dispatch（真实入口）"
                        + "\n注意：身份由 user_id 现算；ALLUSER 的工具入口默认无位（D43），被闸门拒是正确行为。");
        return false;
    }

    /** 私聊事件（NapCat 同形状；{@code message} 按 message_format=string 的形状给，{@code Ev} 会解析成段）。 */
    private JsonObject privateEvent(long qq, String text) {
        long now = System.currentTimeMillis();
        JsonObject o = new JsonObject();
        o.addProperty("post_type", "message");
        o.addProperty("message_type", "private");
        o.addProperty("sub_type", "friend");
        o.addProperty("message_id", nextMsgId());
        o.addProperty("user_id", qq);
        o.addProperty("self_id", selfId());
        o.addProperty("time", now / 1000L);
        o.addProperty("raw_message", text);
        o.addProperty("message", text);
        JsonObject sender = new JsonObject();
        sender.addProperty("user_id", qq);
        sender.addProperty("nickname", "");
        o.add("sender", sender);
        return o;
    }

    /** 群消息事件（同上；{@code sender.role} 就是给她的"这个人在本群是什么角色"）。 */
    private JsonObject groupEvent(long gid, long qq, String role, String text) {
        long now = System.currentTimeMillis();
        JsonObject o = new JsonObject();
        o.addProperty("post_type", "message");
        o.addProperty("message_type", "group");
        o.addProperty("sub_type", "normal");
        o.addProperty("message_id", nextMsgId());
        o.addProperty("group_id", gid);
        o.addProperty("user_id", qq);
        o.addProperty("self_id", selfId());
        o.addProperty("time", now / 1000L);
        o.addProperty("raw_message", text);
        o.addProperty("message", text);
        JsonObject sender = new JsonObject();
        sender.addProperty("user_id", qq);
        sender.addProperty("role", role);
        sender.addProperty("nickname", "");
        o.add("sender", sender);
        return o;
    }

    private long selfId() {
        try { Boot b = DebugPort.liveBoot(); return b == null || b.conf() == null ? 0L : b.conf().selfId(); }
        catch (Throwable t) { return 0L; }
    }

    /** 身份判定的权威出处 = {@code QqGateway.isMaster}（回执里那个 master= 就是它算的）。 */
    private boolean isMaster(long qq) {
        Boot b = DebugPort.liveBoot();
        try {
            if (b != null && b.gateway() != null) return b.gateway().isMaster(qq);
        } catch (Throwable ignored) {
        }
        try { return b != null && b.conf() != null && qq > 0L && qq == b.conf().masterQQ(); }
        catch (Throwable t) { return false; }
    }

    /**
     * 当前 NapCat 连接（<b>现取活基板</b>，绝不缓存）。
     *
     * <p>旧实现把 {@code ai/debug on} 那一刻的基板存进字段，于是"先 {@code ai/debug on}
     * 再 {@code ai/start}"这条启动顺序下，本口永远拿着装配前的空壳 —— 基板明明起来了、
     * NapCat 步骤明明 ok，{@code msg} 却永远回 {@code -ERR link_not_ready}。
     * 现在每次注入都问一次 {@link DebugPort#liveBoot()}。</p>
     */
    private Link link() {
        try {
            Boot b = DebugPort.liveBoot();
            return b == null ? null : b.link();
        } catch (Throwable t) { return null; }
    }

    private static long nextMsgId() {
        long base = System.currentTimeMillis();
        long n = MSG_ID.incrementAndGet();
        return base * 1000L + (n % 1000L);
    }

    // ==================== 审计 / 认证 / 写回 ====================

    /**
     * 注入审计：每次注入/命令都在控制台打一行<b>醒目</b>的事实行（谁注的/什么会话/什么内容）。
     * 用 warn 色 —— 这不是她产生的输出，是外面塞进来的输入，看到就该注意。
     */
    private static void audit(Out out, String line) {
        try { if (out != null) out.warn("[sim] " + line); } catch (Throwable ignored) { }
    }

    private static String quote(String s) {
        return "\"" + (s == null ? "" : s) + "\"";
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

    /** 认证失败限流（token 永不进日志）。 */
    private void rejectLog(String host) {
        int n = rejectTotal.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = rejectLastLogMs;
        if (n != 1 && now - last < REJECT_LOG_MS) return;
        rejectLastLogMs = now;
        warn(out, "调试输入口拒绝一个连接：token 不对（来自 " + host + "，累计 " + n + " 次）。"
                + "客户端应与实例读同一份凭据：运行现场的 debug-port.json（simPort=" + port + "）。"
                + "本行最多每 " + (REJECT_LOG_MS / 1000) + " 秒记一次。");
    }

    /** 写一个以 {@link #END} 收尾的回执块。 */
    private static void block(OutputStream os, String head, String body) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(head).append('\n');
        if (body != null && !body.isEmpty()) {
            String[] rows = body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            for (String r : rows) {
                if (END.equals(r)) sb.append(' ').append(r).append('\n');
                else sb.append(r).append('\n');
            }
        }
        sb.append(END).append('\n');
        try {
            os.write(sb.toString().getBytes("UTF-8"));
            os.flush();
        } catch (Throwable ignored) {
        }
    }

    private static Long num(String s) {
        try { return Long.valueOf(Long.parseLong(s.trim())); } catch (Throwable t) { return null; }
    }

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
            "本机调试输入口 " + NAME + "（只绑 127.0.0.1，必带 token；输出口见 127.0.0.1:"
                    + Conf.DEF_DEBUG_PORT + "，客户端 _dbg.ps1）\n"
          + "  ping                                    连通性（不触发任何动作）\n"
          + "  help                                    这张表\n"
          + "  msg private <qq> <text>                 注入私聊（user_id=<qq>）\n"
          + "  msg group <gid> <qq> [role] <text>      注入群消息（role = member|admin|owner，缺省 member）\n"
          + "  event <一行 JSON>                        任意 NapCat 同形状事件（必须带 post_type）\n"
          + "  cmd <一行 SFW 命令>                      下发框架命令（SfwOut.run，与 console op=run 同路）\n"
          + "  close | quit | bye                       断开\n"
          + "说明：<text> 是行尾原样，支持 CQ 码（[CQ:at,qq=…] / [CQ:image,file=…]）。\n"
          + "说明：身份由 user_id 现算 —— 等于 masterQQ 即 MASTER，否则 ALLUSER（默认无工具入口位，被拒是正确行为）。\n"
          + "说明：每个动作都在控制台留一行 [sim] 审计（谁注的/什么会话/什么内容）。\n"
          + "结束哨：" + END;
}
