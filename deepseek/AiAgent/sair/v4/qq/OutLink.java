package sair.v4.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sair.v4.kit.J;
import sair.v4.kit.Out;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OutLink —— 出站 OneBot WebSocket 客户端。
 *
 * <p>{@link Link} 是<b>服务端</b>（NapCat 连进来）；本类是<b>客户端</b>
 * （我们连出去，连 DC小枫 / Yunzai 这类把端口开着的 OneBot 实现）。</p>
 *
 * <p><b>关键差异</b>：WebSocket 规范要求<b>客户端帧必须加掩码</b>，服务端帧不加。
 * 直接复用 {@code Link.Conn.sendFrame} 会协议错误断开，所以这里独立实现。</p>
 */
public final class OutLink {

    /** 对端问问题时的回答者。返回 null 表示"我不会"，会回 retcode=1404。 */
    public interface Responder {
        /** 返回 JsonElement（可以是对象也可以是数组）；null = 我不会。 */
        JsonElement answer(String action, JsonObject params);
    }

    /** 对端推来事件时的回调。 */
    public interface EventSink {
        void onEvent(OutLink self, JsonObject event);
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int OP_TEXT = 0x1;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private final String name;
    private final String url;
    private final String token;
    /** 我们冒充的 QQ 号（OneBot 反向WS 的 X-Self-ID 头，服务端靠它认 Bot）。 */
    private volatile long selfId = 0;
    private final Responder responder;
    private final EventSink sink;
    private final AtomicLong seq = new AtomicLong(1);
    private final SecureRandom rnd = new SecureRandom();

    private volatile boolean stopped = false;
    /** 最近一次连上的时刻（用来让重连退避复位）。 */
    private volatile long lastOkAt = 0;
    /** 心跳间隔（毫秒，默认 5000）。 */
    private volatile int heartbeatMs = 5000;
    /** 心跳线程（每 heartbeatMs 发一次 meta_event/heartbeat）。 */
    private volatile Thread hbThread;
    private volatile boolean open = false;
    private volatile Socket sock;
    private volatile OutputStream os;
    private volatile Out out;
    private final Object writeLock = new Object();

    public OutLink(String name, String url, String token, Responder responder, EventSink sink) {
        this.name = name == null ? "out" : name;
        this.url = url == null ? "" : url.trim();
        this.token = token == null ? "" : token.trim();
        this.responder = responder;
        this.sink = sink;
    }

    /** 设置自报的 QQ 号（对应 X-Self-ID 头）。QQ 号可能超过 int 上限，用 long。 */
    public void setSelfId(long id) { this.selfId = id; }

    /** 心跳间隔（毫秒）。5 秒是主人定的默认值。 */
    public void setHeartbeatMs(int ms) {
        if (ms >= 3000) this.heartbeatMs = ms;
    }

    /** 注入控制台输出（可空：空则静默）。 */
    public void setOut(Out o) { this.out = o; }

    public String name() { return name; }
    public String url() { return url; }
    public boolean connected() { return open; }

    private void log(String msg, Out.Tone tone) {
        Out o = out;
        if (o == null) return;
        try { o.print("[Out:" + name + "] " + msg + "\n", tone); } catch (Throwable ignored) {}
    }

    private void dim(String msg) { log(msg, Out.Tone.DIM); }
    private void err(String msg) { log(msg, Out.Tone.ERR); }
    private void ok(String msg) { log(msg, Out.Tone.OK); }

    public void stop() {
        stopped = true;
        open = false;
        stopHeartbeat();
        try { Socket s = sock; if (s != null) s.close(); } catch (Exception ignored) {}
    }

    // ---------------- 主循环 ----------------

    public void start() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "v4-out-" + name);
        t.setDaemon(true);
        t.start();
    }

    private void loop() {
        long backoff = 3000L;
        while (!stopped) {
            try {
                connectAndServe();
            } catch (Throwable t) {
                err("连接异常：" + brief(t));
            }
            boolean wasOk = lastOkAt > 0 && (System.currentTimeMillis() - lastOkAt) < 60000L;
            open = false;
            if (stopped) break;
            // 上一次是连上过的（不是从没连上）⇒ 退避复位，恢复得快一点
            if (wasOk) backoff = 3000L;
            try { Thread.sleep(backoff); } catch (InterruptedException e) { break; }
            backoff = Math.min(backoff * 2, 60000L);
        }
        dim("已停止");
    }

    private void connectAndServe() throws Exception {
        URI u = URI.create(url);
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : ("wss".equals(u.getScheme()) ? 443 : 80);
        String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
        if (u.getRawQuery() != null) path = path + "?" + u.getRawQuery();

        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 10000);
        s.setSoTimeout(0);
        this.sock = s;
        InputStream in = s.getInputStream();
        OutputStream o = s.getOutputStream();
        this.os = o;

        handshake(in, o, host, port, path);
        open = true;
        lastOkAt = System.currentTimeMillis();
        ok("已连上 " + url + "（selfId=" + selfId + "）");

        // ★ 必须发 lifecycle/connect —— Yunzai 的 OneBotv11 适配器靠它注册 Bot：
        //   OneBotv11.js:1529  if (meta_event_type !== "lifecycle" && !Bot.uin.includes(self_id)) 丢弃
        //   不发这一帧，Bot 永远不在 Bot.uin 里，之后每条消息都会报「找不到对应Bot」。
        sendLifecycle();
        startHeartbeat();

        readLoop(in);
    }

    private void handshake(InputStream in, OutputStream o, String host, int port, String path) throws IOException {
        byte[] kb = new byte[16];
        rnd.nextBytes(kb);
        String key = Base64.getEncoder().encodeToString(kb);

        StringBuilder req = new StringBuilder();
        req.append("GET ").append(path).append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host);
        if (port != 80 && port != 443) req.append(":").append(port);
        req.append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        if (!token.isEmpty()) {
            req.append("Authorization: Bearer ").append(token).append("\r\n");
        }
        // ★ OneBot 反向WS 规范头：服务端靠 X-Self-ID 识别「这个连接是哪个 Bot」
        //   缺了它，Yunzai 会报「找不到对应Bot，忽略消息」并丢弃所有事件
        long sid = selfId;
        if (sid > 0) {
            req.append("X-Self-ID: ").append(sid).append("\r\n");
        }
        req.append("X-Client-Role: Universal\r\n");
        req.append("\r\n");
        o.write(req.toString().getBytes("UTF-8"));
        o.flush();

        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) throw new IOException("握手时对端断开");
            head.write(b);
            if (b == '\r' && (state == 0 || state == 2)) state++;
            else if (b == '\n' && (state == 1 || state == 3)) state++;
            else state = 0;
            if (head.size() > 32 * 1024) throw new IOException("响应头过大");
        }
        String resp = head.toString("UTF-8");
        String firstLine = resp.split("\r\n")[0];
        if (!firstLine.contains("101")) throw new IOException("握手失败：" + firstLine);
    }

    // ---------------- 读 ----------------

    private void readLoop(InputStream in) throws IOException {
        try {
            innerReadLoop(in);
        } finally {
            stopHeartbeat();
        }
    }

    private void innerReadLoop(InputStream in) throws IOException {
        while (!stopped) {
            int b0 = in.read();
            if (b0 < 0) break;
            int b1 = in.read();
            if (b1 < 0) break;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) len = ((long) rd(in) << 8) | rd(in);
            else if (len == 127) { len = 0; for (int i = 0; i < 8; i++) len = (len << 8) | rd(in); }
            if (len > MAX_MESSAGE) throw new IOException("消息过大：" + len);
            byte[] mask = null;
            if (masked) { mask = new byte[4]; readN(in, mask); }
            byte[] payload = new byte[(int) len];
            readN(in, payload);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];

            if (opcode == OP_CLOSE) { dim("对端关闭"); break; }
            if (opcode == OP_PING) {
                try { sendFrame(OP_PONG, payload); } catch (Exception ignored) {}
                continue;
            }
            if (opcode == OP_PONG || opcode == 0x0) continue;
            if (opcode != OP_TEXT) continue;
            onText(new String(payload, "UTF-8"));
        }
    }

    private void onText(String text) {
        JsonObject o = J.obj(text);
        if (o == null) return;
        String act = J.s(o, "action", "");
        if (!act.isEmpty()) {
            String echo = J.s(o, "echo", "");
            JsonElement data = null;
            try {
                JsonObject pp = null;
                JsonElement pe = o.get("params");
                if (pe != null && pe.isJsonObject()) pp = pe.getAsJsonObject();
                if (responder != null) data = responder.answer(act, pp);
            } catch (Throwable t) {
                err("处理动作 " + act + " 异常：" + brief(t));
            }
            JsonObject resp = new JsonObject();
            if (data == null) {
                resp.addProperty("status", "failed");
                resp.addProperty("retcode", 1404);
                resp.add("data", new JsonObject());
            } else {
                resp.addProperty("status", "ok");
                resp.addProperty("retcode", 0);
                resp.add("data", data);
            }
            if (!echo.isEmpty()) resp.addProperty("echo", echo);
            try { sendText(resp.toString()); } catch (Exception e) { dim("回包失败：" + brief(e)); }
            return;
        }
        if (!J.s(o, "post_type", "").isEmpty()) {
            if (sink != null) {
                try { sink.onEvent(this, o); } catch (Throwable t) { err("事件处理异常：" + brief(t)); }
            }
        }
    }

    /**
     * 自报 {@code lifecycle/connect}。
     *
     * <p>OneBot 11 反向 WS 规范要求客户端连上后先推一条 {@code meta_event}，
     * 服务端据此注册 Bot（Yunzai 的 {@code Bot.uin}、NoneBot 的 bot 对象等）。
     * 缺这一帧，后续所有消息都会被判成「来历不明的 Bot」直接丢弃。</p>
     */
    private void sendLifecycle() {
        long sid = selfId;
        if (sid <= 0) {
            dim("跳过 lifecycle：selfId=" + sid + "（未设置）");
            return;
        }
        JsonObject ev = new JsonObject();
        ev.addProperty("post_type", "meta_event");
        ev.addProperty("meta_event_type", "lifecycle");
        ev.addProperty("sub_type", "connect");
        ev.addProperty("time", System.currentTimeMillis() / 1000L);
        ev.addProperty("self_id", sid);
        ev.addProperty("interval", 0);
        try {
            sendText(ev.toString());
            dim("已发送 lifecycle/connect（self_id=" + sid + "）");
        } catch (Exception e) {
            dim("发送 lifecycle 失败：" + brief(e));
        }
    }

    /**
     * 启用心跳：每 {@code heartbeatMs} 推一条 {@code meta_event/heartbeat}。
     *
     * <p>OneBot 11 规范里心跳是<b>客户端主动推</b>的（不是 ping/pong 帧），
     * 下游（Yunzai / DC）靠 {@code interval} 字段判断对端还活着。
     * 只发 WS ping 帧是不够的 —— 很多实现根本不看 ping。</p>
     */
    private void startHeartbeat() {
        stopHeartbeat();
        final int every = Math.max(3000, heartbeatMs);
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                while (!stopped && open) {
                    try {
                        Thread.sleep(every);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (stopped || !open) return;
                    try {
                        sendText(heartbeatFrame(every));
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        }, "v4-out-hb-" + name);
        t.setDaemon(true);
        this.hbThread = t;
        t.start();
        dim("心跳已启动：" + every + "ms");
    }

    private void stopHeartbeat() {
        Thread t = hbThread;
        this.hbThread = null;
        if (t != null) t.interrupt();
    }

    private String heartbeatFrame(int every) {
        JsonObject ev = new JsonObject();
        ev.addProperty("post_type", "meta_event");
        ev.addProperty("meta_event_type", "heartbeat");
        ev.addProperty("time", System.currentTimeMillis() / 1000L);
        ev.addProperty("self_id", selfId);
        ev.addProperty("interval", every);
        JsonObject st = new JsonObject();
        st.addProperty("online", true);
        st.addProperty("good", true);
        ev.add("status", st);
        return ev.toString();
    }

    // ---------------- 发 ----------------

    /** 事件推送（我们 → 对端）。 */
    public void pushEvent(JsonObject event) {
        if (!open) return;
        try { sendText(event.toString()); } catch (Exception e) { dim("推事件失败：" + brief(e)); }
    }

    private void sendText(String text) throws IOException {
        sendFrame(OP_TEXT, text.getBytes("UTF-8"));
    }

    /** <b>客户端帧：必须加掩码</b>（与服务端相反）。 */
    private void sendFrame(int opcode, byte[] payload) throws IOException {
        byte[] p = payload == null ? new byte[0] : payload;
        ByteArrayOutputStream b = new ByteArrayOutputStream(p.length + 14);
        b.write(0x80 | (opcode & 0x0F));
        int len = p.length;
        if (len < 126) {
            b.write(0x80 | len);
        } else if (len <= 0xFFFF) {
            b.write(0x80 | 126);
            b.write((len >>> 8) & 0xFF);
            b.write(len & 0xFF);
        } else {
            b.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) b.write((int) (((long) len >>> (8 * i)) & 0xFF));
        }
        byte[] mk = new byte[4];
        rnd.nextBytes(mk);
        b.write(mk, 0, 4);
        byte[] outb = new byte[len];
        for (int i = 0; i < len; i++) outb[i] = (byte) (p[i] ^ mk[i % 4]);
        b.write(outb, 0, len);
        synchronized (writeLock) {
            OutputStream w = os;
            if (w == null) throw new IOException("未连接");
            w.write(b.toByteArray());
            w.flush();
        }
    }

    // ---------------- 工具 ----------------

    private static int rd(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("EOF");
        return b;
    }

    private static void readN(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
    }

    private static String brief(Throwable t) {
        String m = t.getClass().getSimpleName();
        String s = t.getMessage();
        return s == null ? m : (m + ": " + s);
    }
}
