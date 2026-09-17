package sair.v4.qq;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.kit.Fs;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.kit.Text;
import sair.v4.kit.Th;

/**
 * 文件外链中转（设计基线能力⑧"NapCat 连接"的一部分，属于基板）：把<b>本地绝对路径</b>
 * 变成一个 NapCat 自己能取的 <b>URL</b>。
 *
 * <h3>为什么在基板里</h3>
 * <p>部署事实：NapCat 与插件<b>不一定在同一台机器</b>。而 {@code upload_group_file} /
 * {@code upload_private_file} 的 {@code file} 参数如果是本地路径，只有<b>同机</b>的 NapCat 读得到。
 * 所以"把本地文件变成 URL"是<b>连接 NapCat</b> 这件事的一部分，不是业务能力 —— 它不做任何业务判断，
 * 只回答一个问题：<i>这个本地文件，怎么交给 NapCat 才拿得到？</i></p>
 *
 * <h3>行为口径（一句话）</h3>
 * <p>{@link #urlOf(File)} 是唯一入口：中转在跑且路径是普通文件 → 外链 URL；
 * 否则 → {@code file:///C:/x}（同机口径，与 V3 现场验证过的一致）。
 * 调用方（{@link Api}）不需要判断"NapCat 在哪台机器上"——同机就用回退值，跨机时开了中转自然走 URL。</p>
 *
 * <h3>安全边界（刻意开放，但有三道闸）</h3>
 * <ol>
 *   <li><b>默认关闭</b>：{@code relayEnabled=false} 时 {@link #start()} 直接返回 false，端口不监听；</li>
 *   <li><b>随机 token 路径前缀</b>：URL 形如 {@code http://host:port/<token>/<绝对路径>}，
 *       token 不对一律 404（不区分 401/403，避免探测）；token 在 {@code config.json} 里可复现、可手改；</li>
 *   <li><b>只读</b>：只接受 GET / HEAD，别的方法 405；没有目录列举、没有写入、没有删除。</li>
 * </ol>
 * <p><b>明确取舍</b>：服务<b>拥有完整文件访问权</b>（任意本地绝对路径都能中转，不做 {@code files/} 白名单）——
 * 因为发送能力本身就要能从任意路径发文件。因此：能拿到链接 = 能读那台机器上的文件。
 * 所以只有"NapCat 确实在别的机器上"时才开，并建议配上 {@code relayTtlMinutes} 让它自己到点关掉。</p>
 *
 * <h3>失败与线程</h3>
 * <p>{@link #start()} 失败只返回 false + 打日志（端口占用一类），绝不影响基板其它能力；
 * 线程全部经 {@link Th}（daemon），{@link #stop()} 关监听、停工作池、清 TTL 定时；
 * 请求侧异常一律吞掉（一个坏请求不能把服务或基板带崩）。日志一律<b>脱敏</b>：token 只打前 4 位。</p>
 */
public final class Relay {

    // ---------------- 常量（结构性，不是文案） ----------------

    /** 监听 backlog。 */
    private static final int BACKLOG = 64;
    /** 工作线程数（同时处理的请求上限；多出来的排队）。 */
    private static final int WORKERS = 4;
    /** 拷文件的缓冲大小。 */
    private static final int BLOCK = 64 * 1024;
    /** token 自动生成的字节数（十六进制展开）。 */
    private static final int TOKEN_BYTES = 16;
    /** stop 时给在传请求的宽限秒数。 */
    private static final int STOP_GRACE_SEC = 1;

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final ThreadFactory FACTORY = new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();
        @Override
        public Thread newThread(Runnable r) {
            return Th.daemon("v4-relay-" + n.incrementAndGet(), r);
        }
    };

    // ---------------- 字段 ----------------

    private final Conf conf;
    private final Out out;

    /** 权限账本（ACL）：把本地路径变成外链 URL / file:/// 之前，先判"能不能读这个文件"（洞 2 兜底）。 */
    private volatile sair.v4.auth.Auth auth;

    private volatile HttpServer server;
    private volatile ExecutorService pool;
    private volatile ScheduledFuture<?> ttlTask;

    private volatile String token = "";
    private volatile String bindHost = "";
    private volatile String publicHost = "";
    private volatile int boundPort = -1;
    private volatile long startedAt = 0L;
    private volatile long deadline = 0L;
    private volatile int ttlMinutes = 0;
    private volatile String note = "";

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong served = new AtomicLong();

    public Relay(Conf conf, Out out) {
        this.conf = conf;
        this.out = out;
    }

    /** 装权限账本（{@code Boot} 装配时注入；本类是"唯一对外判定"的兜底）。 */
    public void setAuth(sair.v4.auth.Auth a) { this.auth = a; }

    /**
     * 判定"当前主体能不能读这个本地绝对路径"（洞 2 的兜底判定，与 {@code Api} 改写层同一把尺子）。
     * <p>放行返回 {@code null}；拒绝返回拒绝原文并打一行告警（绝不静默）。</p>
     */
    private String needPath(String path) {
        sair.v4.auth.Caller c = sair.v4.ctx.Ctx.caller();
        if (c == null) c = sair.v4.auth.Caller.systemActor(conf == null ? 0L : conf.masterQQ());
        sair.v4.auth.Auth a = auth;
        if (a == null) {
            String why = sair.v4.auth.Acl.DENY_PREFIX + "需要 " + sair.v4.auth.Res.normPath(path)
                    + " 的 R 位（权限面没有装配，按拒绝处理）";
            warn("[relay] " + why);
            return why;
        }
        String deny = a.allowRes(c, sair.v4.auth.Res.path(path), 'R');
        if (deny != null) warn("[relay] " + deny);
        return deny;
    }

    // ---------------- 生命周期 ----------------

    /**
     * 起服务（幂等：已经在跑就先停干净再按当前配置重起）。
     * <p>返回 false 的三种情况：配置没开（{@code relayEnabled=false}）、端口非法、绑不上（占用等）——
     * 都只打日志，不影响其它能力。</p>
     */
    public synchronized boolean start() {
        stop();
        if (conf == null) {
            warn("[relay] 没有配置，起不来");
            return false;
        }
        if (!conf.relayEnabled()) {
            note = "relayEnabled=false（要在配置里显式开启）";
            log("[relay] " + note);
            return false;
        }
        int want = conf.relayPort();
        if (want < 0 || want > 65535) {
            note = "relayPort 非法：" + want;
            warn("[relay] " + note);
            return false;
        }
        String tok = ensureToken();
        String bind = bindHost();
        String pub = publicHost(bind);
        HttpServer s;
        try {
            InetSocketAddress addr = Str.blank(bind)
                    ? new InetSocketAddress(want)
                    : new InetSocketAddress(bind, want);
            s = HttpServer.create(addr, BACKLOG);
        } catch (Throwable t) {
            note = "绑定失败（端口 " + want + " 被占用？）：" + t;
            warn("[relay] " + note + " —— 本地文件仍按 file:/// 交给同机 NapCat");
            return false;
        }

        int ttl = conf.relayTtlMinutes();
        if (ttl < 0) ttl = 0;

        // 先落状态再 start()：第一个请求进来时字段已经就绪
        this.token = tok;
        this.bindHost = bind;
        this.publicHost = pub;
        this.boundPort = s.getAddress().getPort();
        this.startedAt = System.currentTimeMillis();
        this.ttlMinutes = ttl;
        this.deadline = ttl > 0 ? startedAt + ttl * 60000L : 0L;
        this.note = "";

        ExecutorService p = Executors.newFixedThreadPool(WORKERS, FACTORY);
        s.createContext("/", new Files());
        s.setExecutor(p);
        try {
            s.start();
        } catch (Throwable t) {
            try { s.stop(0); } catch (Throwable ignored) {}
            try { p.shutdownNow(); } catch (Throwable ignored) {}
            this.boundPort = -1;
            note = "启动失败：" + t;
            warn("[relay] " + note);
            return false;
        }
        this.server = s;
        this.pool = p;

        if (deadline > 0L) {
            final int mins = ttl;
            ttlTask = Th.once(ttl * 60000L, new Runnable() {
                @Override
                public void run() {
                    log("[relay] 链接有效期到（" + mins + " 分钟），自动停止");
                    stop();
                }
            });
        }

        log("[relay] 已监听 " + Str.nz(bind) + ":" + boundPort + "  对外 " + urlShape()
                + "  token=" + mask(token));
        if (Str.blank(conf.relayPublicHost())) {
            log("[relay] 提示：relayPublicHost 没填，外链用的是本机探测到的地址 " + pub
                    + "；跨机部署建议在配置里显式填上（NapCat 那台机器必须能访问到它）");
        }
        log("[relay] 只读、只认 token 路径前缀；服务能读本机任意文件 —— 不需要时就 relay off / relayEnabled=false");
        return true;
    }

    /** 停服务（幂等；没在跑就是清一下状态）。 */
    public synchronized void stop() {
        ScheduledFuture<?> tt = ttlTask;
        ttlTask = null;
        if (tt != null) {
            try { tt.cancel(false); } catch (Throwable ignored) {}
        }
        HttpServer s = server;
        ExecutorService p = pool;
        server = null;
        pool = null;
        int port = boundPort;
        boundPort = -1;
        startedAt = 0L;
        deadline = 0L;
        if (s != null) {
            try { s.stop(STOP_GRACE_SEC); } catch (Throwable ignored) {}
        }
        if (p != null) {
            try { p.shutdownNow(); } catch (Throwable ignored) {}
        }
        if (s != null) log("[relay] 已停止（端口 " + port + " 已释放）");
    }

    public boolean running() { return server != null; }

    /** 监听端口；没在跑返回 -1。 */
    public int port() { return boundPort; }

    /** 对外写法前缀：{@code http://host:port/<token>}；没在跑返回 ""。 */
    public String baseUrl() {
        if (!running()) return "";
        return "http://" + Str.nz(publicHost) + ":" + boundPort + "/" + Str.nz(token);
    }

    /** 对外写法（token 已脱敏，可以进日志/状态输出）。 */
    public String urlShape() {
        if (!running()) return "";
        return "http://" + Str.nz(publicHost) + ":" + boundPort + "/" + mask(token) + "/<绝对路径>";
    }

    // ---------------- 唯一的对外判定 ----------------

    /**
     * 本地文件 → NapCat 能取到的写法。
     *
     * <ul>
     *   <li>中转在跑、且是个普通文件 → {@code http://host:port/<token>/C:/x/y.txt}（跨机可用）；</li>
     *   <li>其余情况 → {@code file:///C:/x/y.txt}（同机口径，V3 现场验证过）；</li>
     *   <li>不是本地绝对路径（相对路径、fileid 一类）→ {@code null}：交给 NapCat 自己解释，基板不改写。</li>
     * </ul>
     * <p>调用方拿到什么就用什么：不需要知道 NapCat 在哪台机器上。</p>
     */
    public String urlOf(File f) {
        if (f == null) return null;
        String raw = f.getPath();
        if (Str.blank(raw) || !looksAbsolute(raw)) return null;
        if (needPath(raw) != null) return null;      // 洞 2：读不了 → 不产出 URL / file:///
        String fwd = raw.replace('\\', '/');
        String u = externalUrlOf(f);
        if (u != null) return u;
        return fwd.startsWith("/") ? "file://" + fwd : "file:///" + fwd;
    }

    /**
     * 只有在"中转在跑、且这是个普通文件"时才给出外链 URL；其余情况返回 {@code null}
     * （调用方自己决定回退成什么：{@link Api} 用 {@code file:///}，强制层则保留原值并告警）。
     */
    public String externalUrlOf(File f) {
        if (f == null || !running() || !f.isFile()) return null;
        return urlFor(f.getPath());
    }

    /**
     * 本地绝对路径 → 外链 URL：<b>只看形态，不要求文件存在</b>（强制层用）。
     * <p>为什么不等存在性：{@link Api} 的强制改写对"像本地路径"的值一律换成外链 ——
     * 存在与否由取的时候决定（NapCat 拿到 404 时原因更直白），也避免"先探测再改写"带来的竞态。</p>
     */
    public String urlFor(String path) {
        if (!running()) return null;
        String raw = Str.trim(path);
        if (Str.blank(raw) || !looksAbsolute(raw)) return null;
        if (needPath(raw) != null) return null;      // 洞 2：读不了 → 不产出外链 URL
        return baseUrl() + "/" + encPath(raw.replace('\\', '/'));
    }

    /**
     * 上传类动作（{@code upload_group_file} / {@code upload_private_file}）的 {@code file} 参数：
     * 已是 URL / {@code base64://} / {@code file:} 的原样透传；本地绝对路径交 {@link #urlOf(File)}；
     * 其余（相对路径、NapCat fileid）原样交给 NapCat。
     */
    public String uploadParam(String file) {
        String p = Str.trim(file);
        if (p.isEmpty() || remote(p)) return p;
        String u = urlOf(new File(p));
        return u == null ? p : u;
    }

    /**
     * 图片 / 语音消息段的 {@code file} 参数：中转在跑就换成外链；
     * 没开中转时<b>原样透传</b>本地路径（NapCat 的 image / record 段本来就认同机本地路径，
     * 不加 {@code file:///} 前缀是既有口径，不动它）。
     */
    public String mediaParam(String file) {
        String p = Str.trim(file);
        if (p.isEmpty() || remote(p)) return p;
        String u = urlOf(new File(p));
        if (u == null || u.startsWith("file:")) return p;
        return u;
    }

    // ---------------- 状态 ----------------

    /** 给 {@code Boot.status()} 与控制台用的中转状态。 */
    public JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", conf != null && conf.relayEnabled());
        o.addProperty("running", running());
        o.addProperty("port", boundPort);
        o.addProperty("bind", Str.nz(bindHost));
        o.addProperty("public_host", Str.nz(publicHost));
        o.addProperty("token_set", !Str.blank(token));
        o.addProperty("token_mask", mask(token));
        o.addProperty("url_shape", urlShape());
        o.addProperty("ttl_minutes", ttlMinutes);
        o.addProperty("left_seconds", leftSeconds());
        o.addProperty("requests", requests.get());
        o.addProperty("served", served.get());
        o.addProperty("note", note);
        return o;
    }

    /** 剩余有效期秒数；0 = 不过期，-1 = 没在跑。 */
    public long leftSeconds() {
        if (!running()) return -1L;
        if (deadline <= 0L) return 0L;
        long left = (deadline - System.currentTimeMillis()) / 1000L;
        return left < 0L ? 0L : left;
    }

    // ---------------- 配置 ----------------

    /**
     * 监听地址：对外 host 是回环（同机自测）就只绑回环；其它情况绑 {@code 0.0.0.0}
     * —— 跨机 NapCat 要连进来，绑回环它够不着；同机自测没必要把端口开给整个网段。
     */
    private String bindHost() {
        String h = Str.trim(conf.relayPublicHost());
        return isLoopback(h) ? h : "0.0.0.0";
    }

    /** 对外 host：配置填了就用配置，没填就用本机探测到的地址（局域网 > 回环）。 */
    private String publicHost(String bind) {
        String h = Str.trim(conf.relayPublicHost());
        if (!h.isEmpty()) return h;
        if (isLoopback(bind)) return bind;
        String local = localAddress();
        return local.isEmpty() ? "127.0.0.1" : local;
    }

    /** token：配置里有就用（剔掉 URL 里不安全的字符），没有就生成并写回配置（可复现）。 */
    private String ensureToken() {
        String t = sanitize(conf.relayToken());
        if (!t.isEmpty()) return t;
        byte[] b = new byte[TOKEN_BYTES];
        try {
            java.security.SecureRandom rnd = new java.security.SecureRandom();
            rnd.nextBytes(b);
        } catch (Throwable ignored) {
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xFF;
            sb.append(HEX[c >> 4]).append(HEX[c & 0xF]);
        }
        t = sb.toString();
        try {
            conf.set("relayToken", t);
            conf.save();
            log("[relay] 已生成新 token 并写进 config.json（relayToken，可复现/可手改）：" + mask(t));
        } catch (Throwable ignored) {
        }
        return t;
    }

    // ---------------- HTTP ----------------

    /** 请求处理：只读、token 前缀、异常一律吞掉。 */
    private final class Files implements HttpHandler {

        @Override
        public void handle(HttpExchange x) {
            requests.incrementAndGet();
            try {
                route(x);
            } catch (Throwable t) {
                try { fail(x, 500, "读取失败"); } catch (Throwable ignored) {}
            } finally {
                try { x.close(); } catch (Throwable ignored) {}
            }
        }

        private void route(HttpExchange x) throws IOException {
            String method = Str.nz(x.getRequestMethod()).toUpperCase();
            boolean head = "HEAD".equals(method);
            if (!head && !"GET".equals(method)) {
                logRequest(x, 405);
                fail(x, 405, "只读服务：只接受 GET / HEAD");
                return;
            }
            String raw = x.getRequestURI() == null ? "" : Str.nz(x.getRequestURI().getRawPath());
            String prefix = "/" + Str.nz(token);
            if (Str.blank(token) || !(raw.equals(prefix) || raw.startsWith(prefix + "/"))) {
                logRequest(x, 404);
                fail(x, 404, "没有这个路径");
                return;
            }
            String rest = raw.length() > prefix.length() ? raw.substring(prefix.length() + 1) : "";
            String path = decode(rest);
            File f = path == null ? null : new File(path);
            if (f == null || !f.isAbsolute() || !f.exists() || f.isDirectory() || !f.isFile()) {
                logRequest(x, 404);
                fail(x, 404, "没有这个路径");
                return;
            }
            logRequest(x, 200);
            stream(x, f, head);
        }

        private void stream(HttpExchange x, File f, boolean head) throws IOException {
            long len = f.length();
            Headers hd = x.getResponseHeaders();
            hd.add("Content-Type", mimeFor(f));
            hd.add("Cache-Control", "no-store");
            hd.add("Content-Disposition", "attachment; filename*=UTF-8''" + encSeg(f.getName()));
            if (head) {
                // HEAD：JDK 对 HEAD 请求会丢掉 sendResponseHeaders 的长度（还会告警），
                // 所以长度自己写进头里，body 长度给 -1（不写 body）。
                hd.set("Content-Length", String.valueOf(len));
                x.sendResponseHeaders(200, -1);
                return;
            }
            x.sendResponseHeaders(200, len > 0L ? len : -1);
            if (len <= 0L) {
                served.incrementAndGet();
                return;
            }
            InputStream in = new FileInputStream(f);
            try {
                OutputStream os = x.getResponseBody();
                byte[] buf = new byte[BLOCK];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                os.flush();
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
            served.incrementAndGet();
        }

        private void fail(HttpExchange x, int code, String text) throws IOException {
            byte[] b = Str.nz(text).getBytes(Fs.UTF8);
            Headers hd = x.getResponseHeaders();
            hd.add("Content-Type", "text/plain; charset=utf-8");
            hd.add("Cache-Control", "no-store");
            x.sendResponseHeaders(code, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                OutputStream os = x.getResponseBody();
                os.write(b);
                os.flush();
            }
        }

        /** 请求日志（token 已脱敏）。 */
        private void logRequest(HttpExchange x, int code) {
            try {
                String raw = x.getRequestURI() == null ? "" : Str.nz(x.getRequestURI().getRawPath());
                String prefix = "/" + Str.nz(token);
                if (!Str.blank(token) && raw.startsWith(prefix)) {
                    raw = "/" + mask(token) + raw.substring(prefix.length());
                }
                log("[relay] " + Str.nz(x.getRequestMethod()) + " " + Str.cut(raw, 200) + " → " + code);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------- 小工具 ----------------

    /** 本地绝对路径判定（与 V3/V4 的 file:/// 口径一致：{@code /x}、{@code \x}、{@code C:...}）。 */
    private static boolean looksAbsolute(String p) {
        String s = Str.nz(p);
        return s.startsWith("/") || s.startsWith("\\") || (s.length() >= 2 && s.charAt(1) == ':');
    }

    private static boolean remote(String p) {
        String low = Str.nz(p).toLowerCase();
        return low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("base64://") || low.startsWith("file:");
    }

    private static boolean isLoopback(String host) {
        String h = Str.trim(host).toLowerCase();
        return h.startsWith("127.") || "localhost".equals(h) || "::1".equals(h) || "[::1]".equals(h);
    }

    /** 本机对外的 IPv4（站点本地地址优先；都取不到返回 ""）。 */
    private static String localAddress() {
        String fallback = "";
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Throwable ignored) {
                    continue;
                }
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (Str.blank(ip)) continue;
                    if (a.isSiteLocalAddress()) return ip;
                    if (fallback.isEmpty()) fallback = ip;
                }
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    /** 请求路径 → 本地绝对路径；解不出来返回 null。路径段一律 UTF-8 百分号解码。 */
    private static String decode(String rest) {
        try {
            String s = URLDecoder.decode(Str.nz(rest), "UTF-8").replace('\\', '/');
            return s.indexOf('\0') >= 0 ? null : s;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 绝对路径 → URL 路径（逐段编码；{@code /} 与 {@code :} 保留，便于阅读与粘贴）。 */
    private static String encPath(String abs) {
        String p = Str.nz(abs).replace('\\', '/');
        String[] segs = p.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encSeg(segs[i]));
        }
        return sb.toString();
    }

    /** 一段文本 → 百分号编码（UTF-8）。 */
    private static String encSeg(String s) {
        byte[] b;
        try {
            b = Str.nz(s).getBytes(Fs.UTF8);
        } catch (Throwable t) {
            b = Str.nz(s).getBytes();
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            int c = x & 0xFF;
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '.' || c == '_' || c == '~' || c == ':' || c == '(' || c == ')';
            if (keep) sb.append((char) c);
            else sb.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
        }
        return sb.toString();
    }

    /** 按扩展名给 Content-Type（认不出就 application/octet-stream）。文本类不带 charset，由 {@link #mimeFor} 按实际字节补。 */
    private static String mime(String name) {
        String e = ext(name);
        if ("png".equals(e)) return "image/png";
        if ("jpg".equals(e) || "jpeg".equals(e)) return "image/jpeg";
        if ("gif".equals(e)) return "image/gif";
        if ("webp".equals(e)) return "image/webp";
        if ("bmp".equals(e)) return "image/bmp";
        if ("svg".equals(e)) return "image/svg+xml";
        if ("mp3".equals(e)) return "audio/mpeg";
        if ("amr".equals(e)) return "audio/amr";
        if ("wav".equals(e)) return "audio/wav";
        if ("flac".equals(e)) return "audio/flac";
        if ("m4a".equals(e)) return "audio/mp4";
        if ("mp4".equals(e)) return "video/mp4";
        if (isTextExt(e)) return "text/plain";
        if ("json".equals(e)) return "application/json";
        if ("xml".equals(e)) return "application/xml";
        if ("pdf".equals(e)) return "application/pdf";
        if ("zip".equals(e)) return "application/zip";
        if ("html".equals(e) || "htm".equals(e)) return "text/html";
        return "application/octet-stream";
    }

    private static String ext(String name) {
        String n = Str.nz(name).toLowerCase();
        int i = n.lastIndexOf('.');
        return i >= 0 ? n.substring(i + 1) : "";
    }

    private static boolean isTextExt(String e) {
        return "txt".equals(e) || "md".equals(e) || "log".equals(e) || "csv".equals(e)
                || "html".equals(e) || "htm".equals(e);
    }

    /**
     * 文本文件外链时的真实 Content-Type：按<b>实际字节</b>探测字符集再声明
     * （GBK 的 .txt/.md 以前被硬声明成 {@code charset=utf-8}，浏览器/客户端按 UTF-8 解就是乱码）。
     * 探不出来（空文件/二进制/兜底）就不带 charset 声明。
     */
    private static String mimeFor(File f) {
        String base = mime(f == null ? "" : f.getName());
        if (f == null || !isTextExt(ext(f.getName()))) return base;
        try {
            java.io.InputStream in = new java.io.FileInputStream(f);
            try {
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                if (n <= 0) return base;
                byte[] head = new byte[n];
                System.arraycopy(buf, 0, head, 0, n);
                String cs = Text.declaredCharset(head);
                return cs.isEmpty() ? base : base + "; charset=" + cs;
            } finally {
                try { in.close(); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            return base;
        }
    }

    /** 脱敏：只留前 4 位。空 token 返回 ""。 */
    private static String mask(String t) {
        String s = Str.nz(t);
        if (s.isEmpty()) return "";
        return s.length() > 4 ? s.substring(0, 4) + "…" : "…";
    }

    /** token 里只保留 URL 安全的字符（其它一律剔掉；剔空了就当作"没配"重新生成）。 */
    private static String sanitize(String t) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Str.nz(t).length(); i++) {
            char c = t.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.';
            if (keep) sb.append(c);
        }
        return sb.toString();
    }

    private void log(String msg) {
        if (out != null) out.dim(msg);
    }

    private void warn(String msg) {
        if (out != null) out.warn(msg);
    }
}
