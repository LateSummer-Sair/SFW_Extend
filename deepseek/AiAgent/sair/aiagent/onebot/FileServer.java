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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

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
    private volatile int port = -1;
    private final Map<String, File> registry = new ConcurrentHashMap<>();
    private final AtomicLong idGen = new AtomicLong(0);

    private FileServer() {}

    public static FileServer getInstance() { return INSTANCE; }

    /** 启动文件服务（绑定 127.0.0.1，端口自动分配，避免冲突）。 */
    public synchronized boolean start() {
        if (server != null) return true;
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/file/", new FileHandler());
            server.setExecutor(Executors.newFixedThreadPool(4));
            server.start();
            port = server.getAddress().getPort();
            return true;
        } catch (Exception e) {
            server = null;
            port = -1;
            return false;
        }
    }

    /** 停止文件服务并清空注册表。 */
    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        registry.clear();
        port = -1;
    }

    public boolean isRunning() { return server != null; }

    public int getPort() { return port; }

    /**
     * 注册文件，返回 HTTP URL（NapCat 可通过此 URL 下载文件；AI 也可用 web 工具抓取）。
     *
     * @return 文件 URL；服务未启动或文件无效时返回 null
     */
    public String register(File file) {
        if (server == null || file == null || !file.exists() || !file.isFile()) return null;
        String id = Long.toString(idGen.incrementAndGet(), 36) + "_" + sanitize(file.getName());
        registry.put(id, file);
        return "http://127.0.0.1:" + port + "/file/" + id;
    }

    /** 文件名保留字母/数字/点/下划线/横线，避免 URL 特殊字符。 */
    private static String sanitize(String name) {
        if (name == null || name.isEmpty()) return "file";
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
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
