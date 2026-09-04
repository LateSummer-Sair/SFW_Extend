package sair.aiagent.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Redis 轻量客户端 —— 手写 RESP 协议 + 旁路缓存降级。
 *
 * <p>定位：<b>纯加速/节省层，可随时摘除</b>。Redis 未运行或连接失败时，
 * 所有方法静默降级返回 null/false，绝不影响主程序运行，也不会功能缺失。</p>
 *
 * <h3>容错设计</h3>
 * <ul>
 *   <li>懒连接：首次操作时才建立连接，连接超时 2 秒，绝不阻塞启动。</li>
 *   <li>熔断：连续失败 5 次进入熔断（60 秒冷却），期间直接降级不重试。</li>
 *   <li>健康检查：后台守护线程每 30 秒 ping，Redis 恢复后自动切回加速模式。</li>
 *   <li>隔离：独立 db（默认 1）+ 独立 key 前缀（默认 aiagent:），与 YunzaiBot(db 0) 互不干扰。</li>
 * </ul>
 */
public final class RedisClient {

    private static volatile RedisClient instance;

    // === 配置 ===
    private final String host;
    private final int port;
    private final int db;
    private final String keyPrefix;
    private final String password;
    private final boolean enabled;

    // === 连接状态 ===
    private final Object lock = new Object();
    private volatile Socket socket;
    private volatile OutputStream out;
    private volatile InputStream in;

    private final AtomicBoolean available = new AtomicBoolean(false);
    private volatile int consecutiveFailures = 0;
    private volatile boolean circuitOpen = false;
    private volatile long circuitOpenUntil = 0L;

    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int READ_TIMEOUT_MS = 3000;
    private static final int CIRCUIT_THRESHOLD = 5;
    private static final long CIRCUIT_COOLDOWN_MS = 60_000L;
    private static final long HEALTH_CHECK_INTERVAL_MS = 30_000L;

    private volatile boolean running = false;
    private Thread healthThread;

    private RedisClient(String host, int port, int db, String prefix, String password, boolean enabled) {
        this.host = host;
        this.port = port;
        this.db = db;
        this.keyPrefix = (prefix != null && !prefix.isEmpty()) ? prefix : "aiagent:";
        this.password = (password != null) ? password : "";
        this.enabled = enabled;
    }

    /**
     * 初始化客户端（从 AiConfig 读取配置）。
     * 重复调用会先关闭旧连接。enabled=false 时创建空实例，所有操作直接降级。
     */
    public static synchronized void init(String host, int port, int db, String prefix, String password, boolean enabled) {
        if (instance != null) {
            instance.close();
        }
        instance = new RedisClient(host, port, db, prefix, password, enabled);
        if (enabled) {
            instance.startHealthCheck();
        }
    }

    /**
     * 获取单例。未调用 init 时返回一个 disabled 的空实例（全部降级），保证永不返回 null。
     */
    public static RedisClient getInstance() {
        if (instance == null) {
            synchronized (RedisClient.class) {
                if (instance == null) {
                    instance = new RedisClient("127.0.0.1", 6379, 1, "aiagent:", "", false);
                }
            }
        }
        return instance;
    }

    // ==================== 公共缓存 API（全部容错降级） ====================

    /** 读缓存。未命中 / Redis 不可用 / 异常 → 返回 null（调用方走原路径）。 */
    public String get(String key) {
        String resp = execute("GET", fullKey(key));
        // GET 成功返回批量字符串；"-1"(null)、错误、异常均为 null
        if (resp == null || resp.startsWith("-")) return null;
        return resp;
    }

    /** 写缓存（无过期时间）。失败静默返回 false。 */
    public boolean set(String key, String value) {
        String resp = execute("SET", fullKey(key), value);
        return "OK".equalsIgnoreCase(resp);
    }

    /** 写缓存（带 TTL 秒）。失败静默返回 false。 */
    public boolean setex(String key, int seconds, String value) {
        if (seconds <= 0) seconds = 1;
        String resp = execute("SETEX", fullKey(key), String.valueOf(seconds), value);
        return "OK".equalsIgnoreCase(resp);
    }

    /** 删除缓存。失败静默返回 false。 */
    public boolean del(String key) {
        String resp = execute("DEL", fullKey(key));
        return resp != null && !resp.startsWith("-");
    }

    /** 自增计数。失败返回 -1。 */
    public long incr(String key) {
        String resp = execute("INCR", fullKey(key));
        if (resp == null || resp.startsWith("-")) return -1;
        try { return Long.parseLong(resp.trim()); } catch (NumberFormatException e) { return -1; }
    }

    /** 判断 key 是否存在。失败返回 false。 */
    public boolean exists(String key) {
        String resp = execute("EXISTS", fullKey(key));
        if (resp == null || resp.startsWith("-")) return false;
        try { return Long.parseLong(resp.trim()) > 0; } catch (NumberFormatException e) { return false; }
    }

    /** 心跳检测。用于健康检查线程与熔断恢复。 */
    public boolean ping() {
        String resp = execute("PING");
        return "PONG".equalsIgnoreCase(resp);
    }

    /** 当前 Redis 是否可用（用于日志/诊断）。 */
    public boolean isAvailable() {
        return enabled && available.get();
    }

    /** Redis 是否启用。 */
    public boolean isEnabled() { return enabled; }

    /** Redis 主机。 */
    public String getHost() { return host; }

    /** Redis 端口。 */
    public int getPort() { return port; }

    /** Redis 数据库编号。 */
    public int getDb() { return db; }

    /** Redis key 前缀。 */
    public String getKeyPrefix() { return keyPrefix; }

    /** 运行时状态摘要。 */
    public String statusSummary() {
        if (!enabled) return "disabled";
        return (isAvailable() ? "available" : "degraded") + " " + host + ":" + port + "/" + db;
    }

    // ==================== 内部实现 ====================

    private String fullKey(String key) {
        return keyPrefix + key;
    }

    private String execute(String... args) {
        if (!enabled) return null;
        synchronized (lock) {
            if (!ensureConnectedLocked()) return null;
            try {
                writeCommand(args);
                return readResponse();
            } catch (Exception e) {
                recordFailure();
                closeQuietlyLocked();
                return null;
            }
        }
    }

    /** 确保连接可用（调用方需持有 lock）。熔断冷却期内直接返回 false。 */
    private boolean ensureConnectedLocked() {
        if (circuitOpen && System.currentTimeMillis() < circuitOpenUntil) return false;
        if (socket != null && socket.isConnected() && !socket.isClosed()) return true;
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            s.setTcpNoDelay(true);
            OutputStream o = s.getOutputStream();
            InputStream i = s.getInputStream();

            socket = s;
            out = o;
            in = i;

            // 密码认证（可选）
            if (!password.isEmpty()) {
                writeCommand("AUTH", password);
                String r = readResponse();
                if (r == null || r.startsWith("-")) {
                    closeQuietlyLocked();
                    recordFailure();
                    return false;
                }
            }
            // 选择数据库
            if (db != 0) {
                writeCommand("SELECT", String.valueOf(db));
                String r = readResponse();
                if (!"OK".equalsIgnoreCase(r)) {
                    closeQuietlyLocked();
                    recordFailure();
                    return false;
                }
            }

            consecutiveFailures = 0;
            circuitOpen = false;
            available.set(true);
            return true;
        } catch (Exception e) {
            closeQuietlyLocked();
            recordFailure();
            return false;
        }
    }

    private void recordFailure() {
        consecutiveFailures++;
        if (consecutiveFailures >= CIRCUIT_THRESHOLD) {
            circuitOpen = true;
            circuitOpenUntil = System.currentTimeMillis() + CIRCUIT_COOLDOWN_MS;
            available.set(false);
        }
    }

    private void closeQuietlyLocked() {
        try { if (out != null) out.close(); } catch (Exception ignored) {}
        try { if (in != null) in.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        out = null;
        in = null;
        socket = null;
    }

    // ==================== RESP 协议 ====================

    private void writeCommand(String... args) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(('*' + String.valueOf(args.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String a : args) {
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            baos.write(('$' + String.valueOf(b.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            baos.write(b);
            baos.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(baos.toByteArray());
        out.flush();
    }

    /**
     * 读取一条 RESP 响应并转为字符串。
     * <ul>
     *   <li>+ 简单字符串 → 内容</li>
     *   <li>- 错误 → 返回 null（调用方当作失败/未命中）</li>
     *   <li>: 整数 → 数字字符串</li>
     *   <li>$ 批量字符串 → 内容；$-1 → null</li>
     *   <li>* 数组 → 各元素拼接（换行分隔）</li>
     * </ul>
     */
    private String readResponse() throws IOException {
        int first = in.read();
        if (first == -1) throw new IOException("Redis EOF");
        switch (first) {
            case '+':
                return readLine();
            case '-':
                readLine(); // 吞掉错误信息
                return null;
            case ':':
                return readLine();
            case '$': {
                int len = parseIntSafe(readLine(), -1);
                if (len <= 0) return null;
                byte[] data = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(data, off, len - off);
                    if (n == -1) throw new IOException("Redis EOF");
                    off += n;
                }
                readCrlf();
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*': {
                int count = parseIntSafe(readLine(), -1);
                if (count <= 0) return null;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String item = readResponse();
                    if (item != null) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(item);
                    }
                }
                return sb.toString();
            }
            default:
                return null;
        }
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                int b2 = in.read();
                if (b2 == '\n') break;
                baos.write(b);
                baos.write(b2);
            } else {
                baos.write(b);
            }
        }
        return new String(baos.toByteArray(), StandardCharsets.UTF_8);
    }

    private void readCrlf() throws IOException {
        int b1 = in.read();
        if (b1 != -1 && b1 != '\r') return;
        int b2 = in.read();
        if (b2 != -1 && b2 != '\n') return;
    }

    private int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    // ==================== 生命周期 ====================

    private void startHealthCheck() {
        if (running) return;
        running = true;
        healthThread = ThreadManager.getInstance().newDaemonThread("AiAgent-RedisHealth", () -> {
            while (running) {
                try { Thread.sleep(HEALTH_CHECK_INTERVAL_MS); } catch (InterruptedException e) { break; }
                if (!running) break;
                try {
                    if (ping()) {
                        available.set(true);
                    }
                } catch (Exception ignored) {}
            }
        });
        healthThread.start();
    }

    /** 关闭连接与健康检查线程（插件退出时调用）。 */
    public synchronized void close() {
        running = false;
        if (healthThread != null) {
            healthThread.interrupt();
            healthThread = null;
        }
        synchronized (lock) {
            closeQuietlyLocked();
        }
        available.set(false);
    }
}
