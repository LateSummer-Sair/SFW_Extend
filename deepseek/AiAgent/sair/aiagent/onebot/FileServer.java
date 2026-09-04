package sair.aiagent.onebot;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import sair.aiagent.core.AiConfig;

/**
 * 文件中转 Web 服务 —— 把本地文件以 HTTP URL 形式暴露，供 NapCat 下载后发送给用户。
 *
 * <p>采用「注册表」模式：仅暴露显式注册的文件，不暴露整个目录，保证安全。
 * 基于 JDK 内置 {@link com.sun.net.httpserver.HttpServer}，零第三方依赖。</p>
 *
 * <p>发送本地文件的完整链路：本地文件 → {@link #register(File)} 得到 HTTP URL →
 * NapCat 的 upload_group_file / upload_private_file 以该 URL 作为 file 参数下载 →
 * 发送到目标会话。AI 也可用 web 工具抓取该 URL 校验文件内容。</p>
 */
public class FileServer {

    private static final FileServer INSTANCE = new FileServer();

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile Thread cleanupThread;
    private volatile int port = -1;
    private final Map<String, File> registry = new ConcurrentHashMap<>();
    private final Map<String, Long> registeredAt = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);
    private volatile String publicHost;   // 对外 URL 的 host（懒解析，重启后重置）

    /** 注册文件最多保留时长（10 分钟）。 */
    private static final long REGISTRY_TTL_MS = 10 * 60 * 1000L;

    private FileServer() {}

    public static FileServer getInstance() { return INSTANCE; }

    /**
     * 启动文件服务（绑定 0.0.0.0 所有网卡，供跨机器 NapCat 访问）。
     * <p>优先使用配置端口 {@link AiConfig#getFileServerPort()}（默认 2671），
     * 端口被占用等异常时回退自动分配端口。</p>
     */
    public synchronized boolean start() {
        if (server != null) return true;
        int cfgPort = AiConfig.getInstance().getFileServerPort();
        if (startOn(cfgPort)) return true;
        // 配置端口不可用（如被占用）：回退自动分配端口，保底可用
        return startOn(0);
    }

    private boolean startOn(int bindPort) {
        try {
            server = HttpServer.create(new InetSocketAddress("0.0.0.0", bindPort), 0);
            server.createContext("/file/", new FileHandler());
            executor = sair.aiagent.core.ThreadManager.getInstance().newNamedFixed("FileServer", 4);
            server.setExecutor(executor);
            server.start();
            port = server.getAddress().getPort();
            publicHost = null;   // 重置，让下次 register 重新解析对外地址
            startCleanupThread();
            return true;
        } catch (Exception e) {
            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
            server = null;
            port = -1;
            return false;
        }
    }

    /** 停止文件服务并清空注册表。 */
    public synchronized void stop() {
        if (cleanupThread != null) {
            cleanupThread.interrupt();
            cleanupThread = null;
        }
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        registry.clear();
        registeredAt.clear();
        port = -1;
        publicHost = null;
    }

    private void startCleanupThread() {
        if (cleanupThread != null) return;
        cleanupThread = sair.aiagent.core.ThreadManager.getInstance().newDaemonThread("FileServer-Cleanup", () -> {
            while (server != null) {
                try { Thread.sleep(60_000L); } catch (InterruptedException e) { break; }
                cleanupExpired();
            }
        });
        cleanupThread.start();
    }

    public boolean isRunning() { return server != null; }

    public int getPort() { return port; }

    /** 当前注册表内可下载文件数。 */
    public int getRegisteredFileCount() { return registry.size(); }

    /** 返回对外访问的基础 URL（如 http://192.168.1.5:5801/file/），供日志/调试；服务未启动返回 null。 */
    public String getPublicBaseUrl() {
        if (server == null || port <= 0) return null;
        return "http://" + formatHost(resolvePublicHost()) + ":" + port + "/file/";
    }

    /**
     * 注册文件，返回 HTTP URL（NapCat 可通过此 URL 下载文件；AI 也可用 web 工具抓取）。
     *
     * @return 文件 URL；服务未启动或文件无效时返回 null
     */
    public String register(File file) {
        if (server == null || file == null || !file.exists() || !file.isFile()) return null;
        cleanupExpired();
        String id = Long.toString(idGen.incrementAndGet(), 36) + "_" + sanitize(file.getName());
        registry.put(id, file);
        registeredAt.put(id, System.currentTimeMillis());
        return "http://" + formatHost(resolvePublicHost()) + ":" + port + "/file/" + id;
    }

    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : registeredAt.entrySet()) {
            if (now - e.getValue() > REGISTRY_TTL_MS) {
                registry.remove(e.getKey());
                registeredAt.remove(e.getKey());
            }
        }
    }

    /** 文件名保留字母/数字/点/下划线/横线，避免 URL 特殊字符。 */
    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "file";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** 解析对外可访问的 host：优先配置值，否则自动探测局域网 IPv4，最后回退 127.0.0.1。 */
    private String resolvePublicHost() {
        if (publicHost != null) return publicHost;
        String configured = AiConfig.getInstance().getFileServerHost();
        if (configured != null && !configured.trim().isEmpty()) {
            publicHost = configured.trim();
        } else {
            publicHost = detectLanIp();
        }
        return publicHost;
    }

    /** 探测本机局域网 IPv4 地址（跳过虚拟网卡），无结果回退 127.0.0.1。 */
    private static String detectLanIp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> nis = java.net.NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (nis.hasMoreElements()) {
                java.net.NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if (name.contains("docker") || name.contains("vmware") || name.contains("virtual")
                        || name.contains("vbox") || name.contains("wsl") || name.contains("vethernet")) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    if (addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip == null || ip.isEmpty()) continue;
                        if (addr.isSiteLocalAddress()) return ip;   // 内网 IP 优先
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback != null ? fallback : "127.0.0.1";
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    /** IPv6 地址用方括号包裹，确保 URL 合法。 */
    private static String formatHost(String host) {
        return (host != null && host.contains(":")) ? ("[" + host + "]") : host;
    }

    /** 静态文件处理器：/file/{id} → 从注册表取文件并流式返回。 */
    private class FileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                String id = path.substring("/file/".length());
                File file = registry.get(id);
                if (file == null || !file.exists() || !file.isFile()) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + java.net.URLEncoder.encode(file.getName(), "UTF-8") + "\"");
                exchange.sendResponseHeaders(200, file.length());
                try (FileInputStream in = new FileInputStream(file);
                     OutputStream out = exchange.getResponseBody()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                    }
                }
            } catch (Exception e) {
                try { exchange.sendResponseHeaders(500, -1); } catch (Exception ignored) {}
            } finally {
                exchange.close();
            }
        }
    }
}
