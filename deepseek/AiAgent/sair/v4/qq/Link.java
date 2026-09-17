package sair.v4.qq;

import com.google.gson.JsonObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Th;

/**
 * NapCat 连接层（设计基线能力⑧）：反向 WebSocket <b>服务端</b> + OneBot v11 动作调用。
 *
 * <p>部署事实：NapCat 作为反向 WS <b>客户端</b> 连到插件（默认端口 {@code conf.napcatPort()}，
 * 部署态 8082），因此本类是 {@link ServerSocket} 服务端；每发一条带 {@code echo} 的动作帧，
 * NapCat 会回一条带 <b>相同 echo</b> 的响应帧。</p>
 *
 * <p>实现口径（项目原则）：只用 JDK + gson，纯 {@code java.net.ServerSocket} + 手写 WebSocket，
 * 不引 Netty/Java-WebSocket 等第三方框架。</p>
 *
 * <p>线程：全部经 {@link Th}（daemon）——accept 循环、握手、每连接读循环、事件派发、30 秒保活 ping；
 * {@link #stop()} 关闭 ServerSocket 与全部连接并清空 pending，异常一律不冒出调用方。</p>
 *
 * <p>安全：日志只打印动作名与响应摘要（≤200 字符），绝不打印 token / apiKey。</p>
 */
public final class Link {

    // ---------------- 常量 ----------------

    /** WebSocket 握手魔数（RFC 6455）。 */
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OP_CONT = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BIN = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    /** 主动 ping 保活间隔。 */
    private static final long PING_MS = 30000L;
    /** 超过 3 个 ping 周期没有任何入帧视为死连接，主动清理。 */
    private static final long IDLE_LIMIT_MS = PING_MS * 3;

    /** 单帧上限（超过则读完丢弃，保持帧边界同步，不中断连接）。 */
    private static final long MAX_FRAME = 16L * 1024 * 1024;
    /** 荒谬长度（长度字段已不可信）：不再尝试对齐，直接断开该连接。 */
    private static final long ABSURD_FRAME = 256L * 1024 * 1024;
    /** 单条（分片重组后）消息上限。 */
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;
    /** 握手阶段读请求头的超时。 */
    private static final int HANDSHAKE_TIMEOUT_MS = 10000;
    /** 请求头上限。 */
    private static final int MAX_HEAD = 32 * 1024;
    /** 日志摘要截断长度。 */
    private static final int LOG_CUT = 200;

    // ---------------- 接口 ----------------

    /** 事件回调：参数是原始 OneBot v11 事件 JSON。实现方在独立线程里被调用。 */
    public interface Handler {
        void onEvent(JsonObject event);
    }

    /** 原始帧级文本钩子（调试用）。 */
    public interface RawSink {
        void onFrame(String text);
    }

    // ---------------- 字段 ----------------

    private final Conf conf;
    private final Out out;

    private volatile ServerSocket server;
    private volatile boolean running;
    private volatile int boundPort;

    /** 已握手连接（未握手的 Socket 不进列表，也不计入 {@link #clients()}）。 */
    private final ConcurrentLinkedQueue<Conn> conns = new ConcurrentLinkedQueue<Conn>();

    /** echo → 等待中的响应 Future。 */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pending =
            new ConcurrentHashMap<String, CompletableFuture<String>>();

    private final AtomicLong echoSeq = new AtomicLong(1L);

    /** 多连接时 API 调用的首选连接：最近收到事件的连接。 */
    private volatile Conn preferred;

    private volatile Handler handler;
    private volatile RawSink rawSink;
    private volatile ScheduledFuture<?> pingTask;

    public Link(Conf conf, Out out) {
        this.conf = conf;
        this.out = out;
    }

    // ---------------- 回调注册 ----------------

    public void setHandler(Handler h) { this.handler = h; }

    /** 是否本机回环地址。 */
    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.trim().toLowerCase();
        return h.startsWith("127.") || "localhost".equals(h) || "::1".equals(h) || "[::1]".equals(h);
    }

    /** 原始帧钩子；传 null 表示不再需要（收到帧时忽略）。 */
    public void setRawSink(RawSink s) { this.rawSink = s; }

    // ---------------- 生命周期 ----------------

    /**
     * 绑定 {@code conf.napcatPort()} 并开始接受 NapCat 的反向 WS 连接。
     * <p>{@code napcatEnabled} 的开关判断属于调用方（基板），本方法只负责绑定。</p>
     *
     * @return 绑定失败（端口占用/权限不足）返回 false，不抛异常；已在运行返回 true。
     */
    public boolean start() {
        if (running) return true;
        int want = conf == null ? Conf.DEF_NAPCAT_PORT : conf.napcatPort();
        String host = conf == null ? Conf.DEF_NAPCAT_HOST : conf.napcatHost();
        boolean loopback = isLoopback(host);
        String token = conf == null ? "" : conf.napcatToken();
        // 安全默认：非本机监听 + 空 token = 任何能连到这个端口的人都能冒充 NapCat/主人 → 拒绝启动
        if (!loopback && token.trim().isEmpty()) {
            log("拒绝启动 NapCat 服务：绑定地址 " + host + " 不是本机回环，而 napcatToken 为空"
                    + "（否则任何能连到该端口的人都能冒充 NapCat 甚至冒充主人）。"
                    + "请设 napcatToken，或把 napcatHost 改回 127.0.0.1。", Out.Tone.ERR);
            return false;
        }
        if (loopback && token.trim().isEmpty()) {
            log("提示：napcatToken 为空，本机进程均可冒充 NapCat。设一个 token 更安全。", Out.Tone.WARN);
        }
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, want), 50);
        } catch (Exception e) {
            if (ss != null) try { ss.close(); } catch (Exception ignored) {}
            log("NapCat 端口 " + host + ":" + want + " 绑定失败：" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + "（仅 NapCat 连接不可用，进程其它部分不受影响）", Out.Tone.ERR);
            return false;
        }
        server = ss;
        boundPort = ss.getLocalPort();
        running = true;
        Th.start("v4-napcat-accept", new Runnable() {
            @Override public void run() { acceptLoop(); }
        });
        pingTask = Th.every("v4-napcat-ping", PING_MS, PING_MS, new Runnable() {
            @Override public void run() { pingTick(); }
        });
        log("NapCat 反向 WS 服务已启动，端口 " + boundPort + "，等待 NapCat 连接", Out.Tone.OK);
        return true;
    }

    /** 干净收尾：停 accept、关全部连接、清 pending；可重复调用。 */
    public void stop() {
        boolean wasUp = running || server != null;
        running = false;
        ScheduledFuture<?> p = pingTask;
        pingTask = null;
        if (p != null) try { p.cancel(false); } catch (Exception ignored) {}
        ServerSocket ss = server;
        server = null;
        if (ss != null) try { ss.close(); } catch (Exception ignored) {}
        for (Conn c : new ArrayList<Conn>(conns)) drop(c);
        conns.clear();
        preferred = null;
        failPending(null);
        boundPort = 0;
        if (wasUp) log("NapCat 反向 WS 服务已停止", Out.Tone.DIM);
    }

    // ---------------- 状态 ----------------

    public boolean running() { return running; }

    /** 至少一个已握手 WS 连接。 */
    public boolean connected() { return clients() > 0; }

    /** 已握手连接数。 */
    public int clients() {
        int n = 0;
        for (Conn c : conns) if (c.open) n++;
        return n;
    }

    /** 实际绑定端口；未运行时回退配置端口。 */
    public int port() {
        int p = boundPort;
        return p > 0 ? p : (conf == null ? Conf.DEF_NAPCAT_PORT : conf.napcatPort());
    }

    // ---------------- 动作调用 ----------------

    /**
     * 发送动作帧并等待同 echo 响应。
     *
     * @return 响应帧原文；未连接 / 超时 / 异常一律返回 null（不抛异常）。
     */
    public String call(String action, JsonObject params) {
        return call(action, params, false);
    }

    /**
     * 同上，但可要求<b>静默响应</b>（M4：引用解析用）。
     *
     * <p><b>为什么必须有这个重载</b>：成功分支原来无条件把响应原文打进控制台
     * （{@code cut(resp, LOG_CUT)}，200 字符，<b>不看 {@code logQq}/{@code logVerbose}</b>）。
     * 而 {@code get_msg} 的响应前 200 字<b>必含被引消息的 {@code raw_message}</b> ——
     * 于是"自动取引用正文"会把<b>别人的聊天正文印到主人控制台</b>，直接违反基板自己写下的
     * 日志口径（控制台不是聊天记录的回声墙）。</p>
     *
     * <p>静默分支只打"规模"：{@code 响应 ok=1 chars=1234} —— 有排障价值、<b>没有一个正文字符</b>。
     * 其余分支（未连接 / 发送失败 / 超时 / 异常）与 {@code quietResponse=false} 时<b>逐字节相同</b>。</p>
     *
     * @param quietResponse true = 成功分支只打 {@code ok}/{@code chars}，不打响应原文
     */
    public String call(String action, JsonObject params, boolean quietResponse) {
        if (action == null || action.trim().isEmpty()) return null;
        Conn c = pick();
        if (c == null) {
            log("NapCat 未连接，动作 " + action + " 未发送", Out.Tone.DIM);
            return null;
        }
        int timeout = timeoutMs();
        String echo = "v4_" + echoSeq.getAndIncrement();
        CompletableFuture<String> f = new CompletableFuture<String>();
        pending.put(echo, f);
        long t0 = System.currentTimeMillis();
        try {
            c.sendText(frame(action, params, echo));
        } catch (Exception e) {
            pending.remove(echo);
            log("动作 " + action + " 发送失败：" + brief(e), Out.Tone.ERR);
            drop(c);
            return null;
        }
        try {
            String resp = f.get(timeout, TimeUnit.MILLISECONDS);
            pending.remove(echo);
            if (quietResponse) {
                log("动作 " + action + " 用时 " + (System.currentTimeMillis() - t0) + "ms 响应 ok="
                        + statusOk(resp) + " chars=" + (resp == null ? 0 : resp.length()), Out.Tone.DIM);
            } else {
                log("动作 " + action + " 用时 " + (System.currentTimeMillis() - t0) + "ms 响应 "
                        + cut(resp, LOG_CUT), Out.Tone.DIM);
            }
            return resp;
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(echo);
            log("动作 " + action + " 等待响应超时（" + timeout + "ms）", Out.Tone.WARN);
            return null;
        } catch (InterruptedException e) {
            pending.remove(echo);
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            pending.remove(echo);
            log("动作 " + action + " 等待响应异常：" + brief(e), Out.Tone.ERR);
            return null;
        }
    }

    /** 轻量通知：发出去就不管响应（仍带 echo 便于帧级排查，响应帧无人认领时忽略）。 */
    public void post(String action, JsonObject params) {
        if (action == null || action.trim().isEmpty()) return;
        Conn c = pick();
        if (c == null) {
            log("NapCat 未连接，通知 " + action + " 未发送", Out.Tone.DIM);
            return;
        }
        try {
            c.sendText(frame(action, params, "v4_" + echoSeq.getAndIncrement()));
        } catch (Exception e) {
            log("通知 " + action + " 发送失败：" + brief(e), Out.Tone.ERR);
            drop(c);
        }
    }

    /** 首选连接：最近收到事件的连接优先，其次任意 open 连接。 */
    private Conn pick() {
        Conn p = preferred;
        if (p != null && p.open) return p;
        for (Conn c : conns) if (c.open) return c;
        return null;
    }

    private int timeoutMs() {
        int t = conf == null ? 10000 : conf.napcatTimeoutMs();
        return t > 0 ? t : 10000;
    }

    /** 组装动作帧文本。 */
    private static String frame(String action, JsonObject params, String echo) {
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        o.add("params", params == null ? new JsonObject() : params);
        if (echo != null) o.addProperty("echo", echo);
        return J.json(o);
    }

    /** 让所有等待中的调用立刻拿到 null（连接全断 / stop）。 */
    private void failPending(String v) {
        for (CompletableFuture<String> f : pending.values()) {
            try { f.complete(v); } catch (Exception ignored) {}
        }
        pending.clear();
    }

    // ---------------- accept / 握手 ----------------

    private void acceptLoop() {
        while (running) {
            Socket s;
            try {
                ServerSocket ss = server;
                if (ss == null) break;
                s = ss.accept();
            } catch (Exception e) {
                if (!running) break;
                log("NapCat accept 异常：" + brief(e), Out.Tone.WARN);
                Th.sleep(200);
                continue;
            }
            final Socket sock = s;
            Th.start("v4-napcat-hs", new Runnable() {
                @Override public void run() { serve(sock); }
            });
        }
    }

    /** 单连接：握手 → 注册 → 读循环（读循环退出即清理）。 */
    private void serve(Socket sock) {
        Conn c = null;
        try {
            sock.setTcpNoDelay(true);
            c = handshake(sock);
            if (c == null) {
                closeQuietly(sock);
                return;
            }
            conns.add(c);
            log("NapCat 已连接：" + c.remote + "（当前连接数 " + clients() + "）", Out.Tone.OK);
            readLoop(c);
        } catch (Exception e) {
            if (running && c == null) log("NapCat 握手失败：" + brief(e), Out.Tone.WARN);
        } finally {
            if (c != null) drop(c); else closeQuietly(sock);
        }
    }

    /**
     * WebSocket 握手：读 HTTP 请求头 → 校验 token（如配置）→ 回 101。
     *
     * @return 握手成功的连接；失败返回 null（已回 401/400 并关闭）。
     */
    private Conn handshake(Socket sock) throws IOException {
        sock.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        InputStream in = new BufferedInputStream(sock.getInputStream(), 16384);
        OutputStream os = new BufferedOutputStream(sock.getOutputStream(), 16384);

        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int p1 = -1, p2 = -1, p3 = -1, n;
        while ((n = in.read()) >= 0) {
            head.write(n);
            if (head.size() > MAX_HEAD) {
                respond(os, "431 Request Header Fields Too Large");
                return null;
            }
            // 识别 "\r\n\r\n"（容忍裸 "\n\n"）
            if (p1 == '\r' && p2 == '\n' && p3 == '\r' && n == '\n') break;
            if (p3 == '\n' && n == '\n') break;
            p1 = p2; p2 = p3; p3 = n;
        }
        if (head.size() == 0) return null;

        String text = new String(head.toByteArray(), Fs.UTF8);
        String[] lines = text.split("\r?\n");
        String reqLine = lines.length > 0 ? lines[0].trim() : "";
        String target = "";
        String[] parts = reqLine.split(" ");
        if (parts.length >= 2) target = parts[1];
        int q = target.indexOf('?');
        String query = q < 0 ? "" : target.substring(q + 1);

        String key = null;
        String auth = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String k = line.substring(0, c).trim().toLowerCase();
            String v = line.substring(c + 1).trim();
            if ("sec-websocket-key".equals(k)) key = v;
            else if ("authorization".equals(k)) auth = v;
        }

        if (key == null || key.isEmpty()) {
            log("NapCat 握手缺少 Sec-WebSocket-Key（请求行 " + cut(reqLine, 80) + "）", Out.Tone.WARN);
            respond(os, "400 Bad Request");
            return null;
        }

        // token 校验：Authorization: Bearer <token> 或 URL 上的 ?access_token=<token>
        String token = conf == null ? "" : conf.napcatToken();
        if (token != null && !token.trim().isEmpty()) {
            String want = token.trim();
            String qToken = queryParam(query, "access_token");
            boolean okAuth = auth != null && (("Bearer " + want).equals(auth) || want.equals(auth));
            boolean okUrl = qToken != null && (want.equals(qToken) || want.equals(urlDecode(qToken)));
            if (!okAuth && !okUrl) {
                log("NapCat 握手认证失败，已拒绝 " + sock.getRemoteSocketAddress(), Out.Tone.WARN);
                respond(os, "401 Unauthorized");
                return null;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 101 Switching Protocols\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Accept: ").append(acceptKey(key)).append("\r\n");
        sb.append("\r\n");
        os.write(sb.toString().getBytes(Fs.UTF8));
        os.flush();
        sock.setSoTimeout(0);
        return new Conn(sock, in, os);
    }

    /** 计算 Sec-WebSocket-Accept = base64(SHA1(key + GUID))。 */
    private static String acceptKey(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] d = sha1.digest((key + WS_GUID).getBytes(Fs.UTF8));
            return Base64.getEncoder().encodeToString(d);
        } catch (Exception e) {
            return "";
        }
    }

    /** 回一个非 101 的 HTTP 响应并关闭。 */
    private static void respond(OutputStream os, String status) {
        try {
            String s = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(s.getBytes(Fs.UTF8));
            os.flush();
        } catch (Exception ignored) {}
    }

    // ---------------- 帧读取 ----------------

    /**
     * 读循环：0x0/0x1/0x2/0x8/0x9/0xA；客户端帧按 4 字节掩码解码；文本帧支持分片重组。
     * <p>任何异常都不外抛，退出即清理连接。</p>
     */
    private void readLoop(Conn c) {
        ByteArrayOutputStream frag = new ByteArrayOutputStream();
        boolean fragStarted = false;
        boolean skipBinary = false;
        try {
            while (running && c.open) {
                int b0 = c.in.read();
                if (b0 < 0) break;
                int b1 = c.in.read();
                if (b1 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = ((long) readByte(c.in) << 8) | readByte(c.in);
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte(c.in);
                }

                byte[] mask = masked ? readN(c.in, 4) : null;

                if (len < 0 || len > MAX_FRAME) {
                    // 读完并丢弃，保持帧边界同步；绝不 break（否则整个连接变聋）
                    if (len < 0 || len > ABSURD_FRAME) {
                        // 长度字段本身已不可信（帧边界无法恢复），只能断开这条连接
                        log("NapCat 帧长度不可信（" + len + " 字节），关闭该连接", Out.Tone.ERR);
                        break;
                    }
                    log("NapCat 帧过大（" + len + " 字节），丢弃该帧但保持连接", Out.Tone.WARN);
                    drain(c.in, len);
                    frag.reset();
                    fragStarted = false;
                    continue;
                }

                byte[] payload = len == 0 ? new byte[0] : readN(c.in, (int) len);
                if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
                c.lastRecv = System.currentTimeMillis();

                if (op == OP_PING) {
                    c.sendFrame(OP_PONG, payload);
                    continue;
                }
                if (op == OP_PONG) continue;
                if (op == OP_CLOSE) {
                    try {
                        c.sendFrame(OP_CLOSE, payload.length == 0 ? CLOSE_NORMAL : payload);
                    } catch (Exception ignored) {}
                    c.open = false;
                    break;
                }
                if (op == OP_BIN) {
                    // OneBot 走文本帧；二进制帧只做边界消费，不参与事件
                    if (!fin) skipBinary = true;
                    continue;
                }
                if (op == OP_CONT && skipBinary) {
                    if (fin) skipBinary = false;
                    continue;
                }
                if (op == OP_TEXT) {
                    frag.reset();
                    fragStarted = true;
                } else if (op == OP_CONT) {
                    if (!fragStarted) continue; // 没有起始帧的续帧：丢弃
                } else {
                    continue;
                }

                if (frag.size() + payload.length > MAX_MESSAGE) {
                    log("NapCat 分片消息超过 " + MAX_MESSAGE + " 字节，丢弃该条", Out.Tone.WARN);
                    frag.reset();
                    fragStarted = false;
                    continue;
                }
                frag.write(payload, 0, payload.length);
                if (!fin) continue;

                String txt = new String(frag.toByteArray(), Fs.UTF8);
                frag.reset();
                fragStarted = false;
                onText(c, txt);
            }
        } catch (Exception e) {
            // 对端正常断开（EOF）不当作异常刷屏
            if (running && c.open && !"连接已关闭".equals(e.getMessage())) {
                log("NapCat 读循环结束：" + brief(e), Out.Tone.DIM);
            }
        } finally {
            drop(c);
        }
    }

    /** 收到一条完整的文本帧。 */
    private void onText(Conn c, String text) {
        RawSink rs = rawSink;
        if (rs != null) {
            try { rs.onFrame(text); } catch (Throwable ignored) {}
        }
        JsonObject o = J.obj(text);
        if (o == null) {
            log("NapCat 收到非 JSON 帧：" + cut(text, LOG_CUT), Out.Tone.DIM);
            return;
        }
        String echo = J.s(o, "echo", "");
        if (!echo.isEmpty()) {
            CompletableFuture<String> f = pending.remove(echo);
            if (f != null) {
                try { f.complete(text); } catch (Exception ignored) {}
                return; // 响应帧不再当事件
            }
            // 无人认领（多半是 post() 的响应）：忽略
        }
        if (!J.s(o, "post_type", "").isEmpty()) {
            preferred = c; // 最近收到事件的连接 = 后续动作的首选
            dispatch(o);
        }
    }

    /**
     * 事件派发。
     *
     * <p>口径一直是"绝不阻塞读循环、绝不断连接"：读循环只把帧交给这里，处理放在别的线程上。
     * 差别在于<b>放在哪种线程上</b> —— 旧口径是 {@code Th.start("v4-napcat-evt")}，
     * 即<b>每条事件一个裸线程、没有上限</b>（入站洪泛 + 慢钩子 = 线程数跟着消息数涨）；
     * 装了 {@link #setEventExecutor} 之后是有界池（{@code eventWorkers} 个线程 + {@code eventQueueMax} 队列），
     * 池与队列都满时按 {@code eventOverflow} 处置并计数（默认丢弃：只在真的超过设计容量时发生）。</p>
     */
    private void dispatch(final JsonObject event) {
        final Handler h = handler;
        // 入站事件行默认不打（控制台只留 Agent 调用事件）；要看加 logConsole 里的 msg
        if (conf == null || (conf.logQq() && conf.logOn("msg"))) log(briefEvent(event), Out.Tone.DIM);
        if (h == null) return;
        Runnable r = new Runnable() {
            @Override public void run() {
                try {
                    h.onEvent(event);
                } catch (Throwable t) {
                    log("事件处理异常（已吞掉，连接保持）：" + brief(t), Out.Tone.ERR);
                } finally {
                    eventsDone.incrementAndGet();
                }
            }
        };
        java.util.concurrent.ThreadPoolExecutor ex = eventPool;
        if (ex == null) {
            Th.start("v4-napcat-evt", r);
            return;
        }
        try {
            ex.execute(r);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            long n = eventsDropped.incrementAndGet();
            log("事件队列已满（" + ex.getQueue().size() + " 条在等，上限 " + eventQueueMax
                    + "），本条事件丢弃；累计丢弃 " + n + " 条", Out.Tone.ERR);
        }
    }

    /**
     * 调试用：把一条与 NapCat 同形状的事件喂进真实入口。只能从调试输入口到达。
     *
     * <p><b>唯一用途是转发</b>：本方法不做任何加工、不做任何判定、不新增任何字段口径 ——
     * 它调的就是真实 WS 帧走的那一个 {@link #dispatch(JsonObject)}（{@code onText} 收到
     * {@code post_type} 非空的事件时调的就是它）。事件字段照 {@code dispatch} 现在读的那一套
     * （{@code post_type} / {@code message_type} / {@code user_id} / {@code group_id} /
     * {@code message} / {@code raw_message} / {@code sender}…），<b>不另定一套</b>。</p>
     *
     * <p>调用方只有一个：{@code dev.DebugSimPort}（127.0.0.1:2661，回环 + token + 审计行）。
     * 它<b>不绕过</b>任何东西：身份由 {@code user_id} 现算（等于 {@code masterQQ} = MASTER，
     * 否则 ALLUSER，按 ACL 默认位被闸门拒），去重（{@code Seen}）、黑名单、技能钩子、
     * 调度、回合全部照常走。</p>
     */
    public void inject(JsonObject event) { dispatch(event); }

    /** 事件执行池；不装 = 每事件一个裸线程（旧行为）。由基板按配置装配。 */
    private volatile java.util.concurrent.ThreadPoolExecutor eventPool;
    private volatile int eventQueueMax;
    private final AtomicLong eventsDropped = new AtomicLong();
    private final AtomicLong eventsDone = new AtomicLong();

    public void setEventExecutor(java.util.concurrent.ThreadPoolExecutor ex) {
        this.eventPool = ex;
        this.eventQueueMax = ex == null ? 0 : ex.getQueue().size() + ex.getQueue().remainingCapacity();
    }

    /** 事件执行面的观测值（进 {@code status}）。 */
    public JsonObject eventStat() {
        JsonObject o = new JsonObject();
        java.util.concurrent.ThreadPoolExecutor ex = eventPool;
        o.addProperty("pooled", ex != null);
        if (ex != null) {
            o.addProperty("workers", ex.getMaximumPoolSize());
            o.addProperty("queue_max", eventQueueMax);
            o.addProperty("queued", ex.getQueue().size());
            o.addProperty("active", ex.getActiveCount());
            o.addProperty("completed", ex.getCompletedTaskCount());
        }
        o.addProperty("dropped", eventsDropped.get());
        o.addProperty("proc_done", eventsDone.get());
        o.addProperty("mode", ex == null ? "thread_per_event" : "bounded_pool");
        return o;
    }

    /** 一行事件摘要（不打印消息正文）。 */
    private static String briefEvent(JsonObject e) {
        String post = J.s(e, "post_type", "?");
        StringBuilder sb = new StringBuilder("事件 ").append(post);
        String mt = J.s(e, "message_type", "");
        if (!mt.isEmpty()) sb.append('/').append(mt);
        long g = J.l(e, "group_id", 0L);
        long u = J.l(e, "user_id", 0L);
        if (g > 0) sb.append(" 群=").append(g);
        if (u > 0) sb.append(" 用户=").append(u);
        String nt = J.s(e, "notice_type", "");
        if (!nt.isEmpty()) sb.append(' ').append(nt);
        String rt = J.s(e, "request_type", "");
        if (!rt.isEmpty()) sb.append(' ').append(rt);
        return sb.toString();
    }

    // ---------------- 保活 / 清理 ----------------

    private void pingTick() {
        if (!running) return;
        long now = System.currentTimeMillis();
        for (Conn c : conns) {
            if (!c.open) continue;
            if (now - c.lastRecv > IDLE_LIMIT_MS) {
                log("NapCat 连接 " + (IDLE_LIMIT_MS / 1000) + " 秒无入帧，主动回收", Out.Tone.WARN);
                drop(c);
                continue;
            }
            try {
                c.sendFrame(OP_PING, EMPTY);
            } catch (Exception e) {
                drop(c);
            }
        }
    }

    /** 清连接：关 socket、出列表、必要时让等待中的调用立刻失败。 */
    private void drop(Conn c) {
        if (c == null) return;
        c.open = false;
        closeQuietly(c.sock);
        boolean removed = conns.remove(c);
        if (preferred == c) preferred = null;
        if (removed) log("NapCat 连接已断开：" + c.remote + "（剩余 " + clients() + "）", Out.Tone.DIM);
        if (conns.isEmpty()) failPending(null);
    }

    // ---------------- 连接 ----------------

    private static final byte[] EMPTY = new byte[0];
    private static final byte[] CLOSE_NORMAL = new byte[] {0x03, (byte) 0xE8}; // 1000

    /** 一条已握手的 WS 连接。 */
    private final class Conn {
        final Socket sock;
        final InputStream in;
        final OutputStream os;
        final Object writeLock = new Object();
        final String remote;
        volatile boolean open = true;
        volatile long lastRecv = System.currentTimeMillis();

        Conn(Socket sock, InputStream in, OutputStream os) {
            this.sock = sock;
            this.in = in;
            this.os = os;
            this.remote = String.valueOf(sock.getRemoteSocketAddress());
        }

        void sendText(String text) throws IOException {
            sendFrame(OP_TEXT, text.getBytes(Fs.UTF8));
        }

        /** 服务端帧不加掩码。 */
        void sendFrame(int opcode, byte[] payload) throws IOException {
            byte[] p = payload == null ? EMPTY : payload;
            ByteArrayOutputStream b = new ByteArrayOutputStream(p.length + 10);
            b.write(0x80 | (opcode & 0x0F));
            int len = p.length;
            if (len < 126) {
                b.write(len);
            } else if (len <= 0xFFFF) {
                b.write(126);
                b.write((len >>> 8) & 0xFF);
                b.write(len & 0xFF);
            } else {
                b.write(127);
                for (int i = 7; i >= 0; i--) b.write((int) (((long) len >>> (8 * i)) & 0xFF));
            }
            b.write(p, 0, len);
            synchronized (writeLock) {
                os.write(b.toByteArray());
                os.flush();
            }
        }
    }

    // ---------------- 工具 ----------------

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("连接已关闭");
        return b & 0xFF;
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new IOException("连接已关闭");
            off += r;
        }
        return buf;
    }

    private static void drain(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) throw new IOException("连接已关闭");
            left -= r;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try { s.close(); } catch (Exception ignored) {}
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String kv : query.split("&")) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            if (name.equals(k)) return i < 0 ? "" : kv.substring(i + 1);
        }
        return null;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String brief(Throwable t) {
        if (t == null) return "?";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    /** 压成单行并截断（响应摘要用）。 */
    private static String cut(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ');
        if (t.length() <= max) return t;
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    /**
     * 响应是不是 {@code "status":"ok"}（<b>静默分支的日志只用它</b>：1/0，不解析、不含正文）。
     * <p>刻意不是完整 JSON 解析：这条只在"要打一行 dim 日志"时用，子串判定足够，
     * 而且它<b>绝不</b>把任何字段值带进日志。</p>
     */
    private static int statusOk(String resp) {
        if (resp == null) return 0;
        return resp.indexOf("\"status\":\"ok\"") >= 0 ? 1 : 0;
    }

    private void log(String msg, Out.Tone tone) {
        Out o = out;
        if (o == null) return;
        try {
            o.print("[NapCat] " + msg + "\n", tone);
        } catch (Throwable ignored) {}
    }
}
