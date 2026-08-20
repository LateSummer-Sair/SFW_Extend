package sair.sfwweb.core;

import com.sun.net.httpserver.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.*;
import sair.sys.SairCons;

/**
 * SFW Web 服务�?�?HTTP/HTTPS + SSE + 硬编码前端兜�?
 */
public class WebServer {

    private HttpsServer httpsServer;
    private HttpServer httpServer;
    private ExecutorService serverExecutor;
    private final ConfigManager config;
    private final PasswordManager passwordManager;
    private final CommandHandler commandHandler;
    private final SfwConsoleCapture consoleCapture;
    private SessionManager sessionManager;
    private volatile boolean running = false;
    private volatile boolean useHttps = false;

    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionCleaner = Executors.newSingleThreadScheduledExecutor();
    private static final String SESSION_COOKIE = "SFW_WEB_SESSION";
    private final AtomicLong sseIdCounter = new AtomicLong(0);
    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCKOUT_MS = 300000;
    private static final int MAX_POST_BODY_BYTES = 65536;
    private static final int MAX_FILE_UPLOAD_BYTES = 50 * 1024 * 1024;
    private final FileManager fileManager = FileManager.getInstance();

    public WebServer(ConfigManager config, PasswordManager passwordManager,
                     CommandHandler commandHandler, SfwConsoleCapture consoleCapture,
                     SessionManager sessionManager) {
        this.config = config;
        this.passwordManager = passwordManager;
        this.commandHandler = commandHandler;
        this.consoleCapture = consoleCapture;
        this.sessionManager = sessionManager;
    }

    public void setSessionManager(SessionManager sm) { this.sessionManager = sm; }

    public boolean start() {
        if (running) return true;
        try {
            int port = config.getPort();
            useHttps = config.isHttpsEnabled() && config.isHttpsReady();
            serverExecutor = Executors.newFixedThreadPool(10);
            if (useHttps) {
                SSLContext ctx = loadUserSSLContext();
                httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                    public void configure(HttpsParameters p) {
                        p.setNeedClientAuth(false);
                        p.setWantClientAuth(false);
                        SSLParameters params = getSSLContext().getDefaultSSLParameters();
                        // 仅启用安全的 TLS 协议版本
                        params.setProtocols(new String[]{"TLSv1.2", "TLSv1.3"});
                        p.setSSLParameters(params);
                    }
                });
                setupEndpoints(httpsServer);
                httpsServer.setExecutor(serverExecutor);
                httpsServer.start();
            } else {
                // HTTP 模式监听所有网卡接口（⚠ 不加密，外网暴露有风险）
                httpServer = HttpServer.create(new InetSocketAddress(port), 0);
                setupEndpoints(httpServer);
                httpServer.setExecutor(serverExecutor);
                httpServer.start();
            }
            running = true;
            sessionCleaner.scheduleAtFixedRate(() -> cleanExpiredSessions(), 5, 5, TimeUnit.MINUTES);
            return true;
        } catch (Exception e) {
            System.err.println("[SFW_WEB] Start failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public void stop() {
        running = false;
        sessionCleaner.shutdown();
        try { sessionCleaner.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ig) {}
        if (serverExecutor != null) { serverExecutor.shutdown(); try { serverExecutor.awaitTermination(3, TimeUnit.SECONDS); } catch (InterruptedException ig) {} serverExecutor = null; }
        if (httpsServer != null) { httpsServer.stop(2); httpsServer = null; }
        if (httpServer != null) { httpServer.stop(2); httpServer = null; }
    }

    private void setupEndpoints(HttpServer s) {
        s.createContext("/api/auth/status", this::handleAuthStatus);
        s.createContext("/api/auth/setup", this::handleSetupPassword);
        s.createContext("/api/auth/login", this::handleLogin);
        s.createContext("/api/auth/logout", this::handleLogout);
        s.createContext("/api/auth/changepassword", this::handleChangePassword);
        s.createContext("/api/command/execute", this::handleCommandExecute);
        s.createContext("/api/command/history", this::handleCommandHistory);
        s.createContext("/api/output/stream", this::handleSseStream);
        s.createContext("/api/config/get", this::handleConfigGet);
        s.createContext("/api/config/update", this::handleConfigUpdate);
        s.createContext("/api/config/theme", this::handleThemeUpdate);
        s.createContext("/api/console/text", this::handleConsoleText);
        s.createContext("/api/console/clear", this::handleConsoleClear);
        s.createContext("/api/sessions/list", this::handleSessionsList);
        s.createContext("/api/sessions/history", this::handleSessionHistory);
        s.createContext("/api/file/list", this::handleFileList);
        s.createContext("/api/file/read", this::handleFileRead);
        s.createContext("/api/file/write", this::handleFileWrite);
        s.createContext("/api/file/delete", this::handleFileDelete);
        s.createContext("/api/file/create", this::handleFileCreate);
        s.createContext("/api/file/upload", this::handleFileUpload);
        s.createContext("/api/file/preview", this::handleFilePreview);
        s.createContext("/api/file/move", this::handleFileMove);
        s.createContext("/api/file/ir-execute", this::handleFileIrExecute);
        s.createContext("/api/file/root", this::handleFileRoot);
        s.createContext("/", this::handleStaticFiles);
    }

    private SSLContext loadUserSSLContext() throws Exception {
        // 1. 加载 PEM 证书链（支持单证书和多证�?fullchain�?
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> certList = new ArrayList<>();
        try (FileInputStream fis = new FileInputStream(config.getCertFile())) {
            Collection<? extends java.security.cert.Certificate> certs = cf.generateCertificates(fis);
            for (java.security.cert.Certificate c : certs) {
                certList.add((X509Certificate) c);
            }
        }
        if (certList.isEmpty()) throw new IllegalArgumentException("No certificates found in PEM file: " + config.getCertFile());

        // 第一张是服务器证书，后面的是中间CA/根证�?
        X509Certificate serverCert = certList.get(0);
        java.security.cert.Certificate[] chain = certList.toArray(new java.security.cert.Certificate[0]);

        // 2. 加载私钥（支�?PKCS8 �?PKCS1 两种格式�?
        String keyPem = new String(Files.readAllBytes(java.nio.file.Paths.get(config.getKeyFile())), StandardCharsets.UTF_8);
        PrivateKey privateKey;
        if (keyPem.contains("BEGIN PRIVATE KEY")) {
            // PKCS8 格式
            privateKey = loadPkcs8Key(keyPem);
        } else if (keyPem.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS1 格式 �?转为 PKCS8
            privateKey = loadPkcs1Key(keyPem);
        } else if (keyPem.contains("BEGIN EC PRIVATE KEY")) {
            privateKey = loadEcPkcs1Key(keyPem);
        } else {
            throw new IllegalArgumentException("Unsupported key format. Expected PKCS8 or PKCS1 PEM.");
        }

        // 3. 验证证书与私钥匹�?
        try {
            serverCert.verify(serverCert.getPublicKey()); // 确保证书自签名或链验�?
        } catch (Exception ignore) { /* 非自签名证书不会通过此项，正�?*/ }

        // 4. 构建内存 KeyStore（包含完整证书链�?
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("sfwweb", privateKey, new char[0], chain);

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }

    private PrivateKey loadPkcs8Key(String pem) throws Exception {
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                        .replace("-----END PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        byte[] decoded = Base64.getDecoder().decode(b64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decoded);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private PrivateKey loadPkcs1Key(String pem) throws Exception {
        String b64 = pem.replace("-----BEGIN RSA PRIVATE KEY-----", "")
                        .replace("-----END RSA PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        byte[] pkcs1 = Base64.getDecoder().decode(b64);
        byte[] pkcs8 = rsaPkcs1ToPkcs8(pkcs1);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        return KeyFactory.getInstance("RSA").generatePrivate(spec);
    }

    private PrivateKey loadEcPkcs1Key(String pem) throws Exception {
        String b64 = pem.replace("-----BEGIN EC PRIVATE KEY-----", "")
                        .replace("-----END EC PRIVATE KEY-----", "")
                        .replaceAll("\\s", "");
        byte[] pkcs1 = Base64.getDecoder().decode(b64);
        byte[] pkcs8 = ecPkcs1ToPkcs8(pkcs1);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(pkcs8);
        return KeyFactory.getInstance("EC").generatePrivate(spec);
    }

    /** RSA PKCS1 �?PKCS8 DER 包装 */
    private static byte[] rsaPkcs1ToPkcs8(byte[] pkcs1) throws Exception {
        // OID: 1.2.840.113549.1.1.1 (rsaEncryption)
        byte[] rsaOid = {0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x01, 0x01};
        byte[] algId = derSeq(concat(rsaOid, new byte[]{0x05, 0x00})); // SEQUENCE { OID, NULL }
        byte[] octStr = derTag(0x04, pkcs1); // OCTET STRING
        byte[] inner = concat(new byte[]{0x02, 0x01, 0x00}, algId, octStr); // INTEGER(0) + AlgId + OCTET
        return derTag(0x30, inner); // SEQUENCE
    }

    /** EC PKCS1 �?PKCS8 DER 包装 */
    private static byte[] ecPkcs1ToPkcs8(byte[] pkcs1) throws Exception {
        // OID: 1.2.840.10045.2.1 (ecPublicKey)
        byte[] ecOid = {0x06, 0x07, 0x2A, (byte)0x86, 0x48, (byte)0xCE, 0x3D, 0x02, 0x01};
        byte[] algId = derSeq(ecOid); // SEQUENCE { OID }
        byte[] octStr = derTag(0x04, pkcs1);
        byte[] inner = concat(new byte[]{0x02, 0x01, 0x00}, algId, octStr);
        return derTag(0x30, inner);
    }

    private static byte[] derTag(int tag, byte[] content) throws Exception {
        return concat(new byte[]{(byte) tag}, derLen(content.length), content);
    }

    private static byte[] derSeq(byte[] content) throws Exception {
        return derTag(0x30, content);
    }

    private static byte[] derLen(int len) {
        if (len < 128) return new byte[]{(byte) len};
        if (len < 256) return new byte[]{(byte) 0x81, (byte) len};
        return new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) len};
    }

    private static byte[] concat(byte[]... arrays) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        for (byte[] a : arrays) bos.write(a);
        return bos.toByteArray();
    }

    /** 重启服务器（后台线程，避免阻塞调用方） */
    public void restart() {
        new Thread(() -> {
            try { Thread.sleep(300); } catch (InterruptedException ig) {}
            stop();
            try { Thread.sleep(500); } catch (InterruptedException ig) {}
            start();
        }).start();
    }

    // ==================== 认证 ====================
    private void handleAuthStatus(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
            sendJson(ex, 200, String.format("{\"passwordSet\":%b,\"authenticated\":%b}", passwordManager.isPasswordSet(), getSession(ex) != null));
    }
    private void handleSetupPassword(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (passwordManager.isPasswordSet()) { sendJson(ex, 403, "{\"error\":\"Password already set\"}"); return; }
        Map<String,String> p = parsePostParams(ex);
        String pw = p.get("password");
        if (pw == null || pw.length() < 6) { sendJson(ex, 400, "{\"error\":\"Password >= 6 chars\"}"); return; }
        boolean ok = passwordManager.setPassword(pw);
        sendJson(ex, ok ? 200 : 500, ok ? "{\"success\":true}" : "{\"error\":\"Failed\"}");
    }
    private void handleLogin(HttpExchange ex) throws IOException {
        try {
            if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
            String ip = ex.getRemoteAddress().getAddress().getHostAddress();
            LoginAttempt a = loginAttempts.get(ip);
            if (a != null && a.isLocked()) { sendJson(ex, 429, "{\"error\":\"Locked\"}"); return; }
        Map<String,String> p = parsePostParams(ex);
        if (!passwordManager.verifyPassword(p.get("password"))) {
            if (a == null) { a = new LoginAttempt(); loginAttempts.put(ip, a); }
            a.recordFailure();
            sendJson(ex, 401, "{\"error\":\"Invalid password\"}"); return;
        }
            loginAttempts.remove(ip);
            if (sessionManager == null) { sendJson(ex, 500, "{\"error\":\"Server not ready\"}"); return; }
            String sid = createSession(ip, ex.getRequestHeaders().getFirst("User-Agent"));
            String cookie = SESSION_COOKIE + "=" + sid + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (config.getSessionTimeoutMinutes() * 60);
            if (useHttps) cookie += "; Secure";
            ex.getResponseHeaders().add("Set-Cookie", cookie);
            sendJson(ex, 200, "{\"success\":true}");
        } catch (Exception e) {
            try { sendJson(ex, 500, "{\"error\":\"Internal error\"}"); } catch (Exception ignored) {}
        }
    }
    private void handleLogout(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = getSession(ex); if (s != null) { sessions.remove(s.id); if (sessionManager != null) sessionManager.removeSession(s.id); }
            sendJson(ex, 200, "{\"success\":true}");
    }
    private void handleChangePassword(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
        Map<String,String> p = parsePostParams(ex);
        String o = p.get("old_password"), n = p.get("new_password");
        if (o == null || n == null || n.length() < 6) { sendJson(ex, 400, "{\"error\":\"Invalid\"}"); return; }
        if (!passwordManager.verifyPassword(o)) { sendJson(ex, 401, "{\"error\":\"Wrong password\"}"); return; }
        boolean ok = passwordManager.setPassword(n);
        sendJson(ex, ok ? 200 : 500, ok ? "{\"success\":true}" : "{\"error\":\"Failed\"}");
    }

    // ==================== 命令 ====================
    private void handleCommandExecute(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
        Map<String,String> p = parsePostParams(ex);
        String c = p.get("command");
        if (c == null || c.trim().isEmpty()) { sendJson(ex, 400, "{\"error\":\"Command required\"}"); return; }
        sessionManager.logCommand(s.id, c);
        CommandHandler.CommandResult r = commandHandler.execute(c);
        sendJson(ex, r.success ? 200 : 400, String.format("{\"success\":%b,\"message\":\"%s\"}", r.success, esc(r.message)));
    }
    private void handleCommandHistory(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
        List<String> h = commandHandler.getRecentHistory(100);
        StringBuilder sb = new StringBuilder("{\"history\":[");
        for (int i = 0; i < h.size(); i++) { if (i > 0) sb.append(","); sb.append("\"").append(esc(h.get(i))).append("\""); }
        sb.append("]}"); sendJson(ex, 200, sb.toString());
    }

    // ==================== SSE ====================
    private void handleSseStream(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
            ex.getResponseHeaders().set("Content-Type", "text/event-stream");
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.getResponseHeaders().set("Connection", "keep-alive");
            ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();
        final Object writeLock = new Object();
        final String sid = "sse_" + sseIdCounter.incrementAndGet();
        SfwConsoleCapture.SseSubscriber sub = new SfwConsoleCapture.SseSubscriber() {
            public void onNewLine(SfwConsoleCapture.ConsoleLine l) {
                synchronized (writeLock) {
                    try { String t = esc(l.text);
                        os.write(("id: "+sid+"\nevent: output\ndata: {\"text\":\""+t+"\",\"color\":\""+l.colorHex+"\"}\n\n").getBytes(StandardCharsets.UTF_8)); os.flush(); }
                    catch (IOException e) { consoleCapture.removeSubscriber(this); }
                }
            }
            public void onConsoleCleared() {
                synchronized (writeLock) {
                    try { os.write("event: clear\ndata: {}\n\n".getBytes(StandardCharsets.UTF_8)); os.flush(); } catch (IOException ig) {}
                }
            }
        };
        // 先加入订阅者（此后新输出会通过 onNewLine 广播），再发送历史快�?
        consoleCapture.addSubscriber(sub);
        java.util.List<SfwConsoleCapture.ConsoleLine> snapshot = consoleCapture.getOutputLines();
        synchronized (writeLock) {
            for (SfwConsoleCapture.ConsoleLine line : snapshot) {
                sub.onNewLine(line);
            }
        }
        try { while (running) {
            synchronized (writeLock) { os.write(": hb\n\n".getBytes(StandardCharsets.UTF_8)); os.flush(); }
            Thread.sleep(15000); }
        }
        catch (Exception ig) {} finally { consoleCapture.removeSubscriber(sub); try { os.close(); } catch (IOException ig) {} }
    }

    // ==================== 配置 ====================
    private void handleConfigGet(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
        Properties p = config.getAllProperties(); p.remove("password.hash"); p.remove("password.salt"); p.remove("key.file");
        StringBuilder sb = new StringBuilder("{"); boolean f = true;
        for (String k : p.stringPropertyNames()) { if (!f) sb.append(","); sb.append("\"").append(k).append("\":\"").append(esc(p.getProperty(k))).append("\""); f = false; }
        sb.append("}"); sendJson(ex, 200, sb.toString());
    }
    private void handleConfigUpdate(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
        Map<String,String> p = parsePostParams(ex);
        boolean httpsChanged = false;
        if (p.containsKey("https.enabled")) {
            boolean was = config.isHttpsEnabled();
            config.setHttpsEnabled(Boolean.parseBoolean(p.get("https.enabled")));
            httpsChanged = (was != config.isHttpsEnabled());
        }
        try {
            if (p.containsKey("port")) config.setPort(Integer.parseInt(p.get("port")));
            if (p.containsKey("session.timeout_minutes")) config.setSessionTimeoutMinutes(Integer.parseInt(p.get("session.timeout_minutes")));
            if (p.containsKey("max_output_lines")) config.setMaxOutputLines(Integer.parseInt(p.get("max_output_lines")));
            if (p.containsKey("web.root")) config.setWebRoot(p.get("web.root"));
            if (p.containsKey("cert.file")) config.setCertFile(p.get("cert.file"));
            if (p.containsKey("key.file")) config.setKeyFile(p.get("key.file"));
            sendJson(ex, 200, httpsChanged ?
                "{\"success\":true,\"restart\":true,\"message\":\"HTTPS toggled, server restarting...\"}" :
                "{\"success\":true}");
        } catch (Exception e) { sendJson(ex, 400, "{\"error\":\"Invalid\"}"); }
        if (httpsChanged) restart();
    }
    private void handleThemeUpdate(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
        Map<String,String> p = parsePostParams(ex);
        if (p.containsKey("theme.bg_color")) config.setBgColor(p.get("theme.bg_color"));
        if (p.containsKey("theme.font_color")) config.setFontColor(p.get("theme.font_color"));
        if (p.containsKey("theme.border_color")) config.setBorderColor(p.get("theme.border_color"));
        if (p.containsKey("theme.accent_color")) config.setAccentColor(p.get("theme.accent_color"));
        if (p.containsKey("theme.font_size")) config.setFontSize(Integer.parseInt(p.get("theme.font_size")));
        if (p.containsKey("theme.background_opacity")) config.setBackgroundOpacity(Double.parseDouble(p.get("theme.background_opacity")));
            sendJson(ex, 200, "{\"success\":true}");
    }

    // ==================== 控制�?====================
    private void handleConsoleText(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
            sendJson(ex, 200, "{\"text\":\"" + esc(consoleCapture.getFullText()) + "\"}");
    }
    private void handleConsoleClear(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        consoleCapture.clear(); sendJson(ex, 200, "{\"success\":true}");
    }

    // ==================== 会话 ====================
    private void handleSessionsList(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        StringBuilder sb = new StringBuilder("[");
        for (SessionInfo s : sessions.values()) {
            sb.append("{\"id\":\"").append(s.id).append("\",\"ip\":\"").append(s.ip).append("\",\"ua\":\"").append(esc(s.userAgent)).append("\",\"loginTime\":").append(s.loginTime).append(",\"lastAccess\":").append(s.lastAccess).append("},");
        }
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        sb.append("]");
            sendJson(ex, 200, sb.toString());
    }
    private void handleSessionHistory(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        String sid = null;
        String q = ex.getRequestURI().getQuery();
        if (q != null) for (String kv : q.split("&")) { String[] kvs = kv.split("=",2); if (kvs.length==2&&"id".equals(kvs[0])) sid = java.net.URLDecoder.decode(kvs[1],"UTF-8"); }
        if (sid == null) { sendJson(ex, 400, "{\"error\":\"Session ID required\"}"); return; }
        java.util.List<SessionManager.AuditEntry> h = sessionManager.getSessionHistory(sid);
        StringBuilder sb = new StringBuilder("[");
        for (SessionManager.AuditEntry e : h) { sb.append("{\"cmd\":\"").append(esc(e.command)).append("\",\"time\":").append(e.timestamp).append("},"); }
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        sb.append("]");
            sendJson(ex, 200, sb.toString());
    }

    // ==================== 静态文件（含硬编码兜底�?====================
    private void handleStaticFiles(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";
        if (path.contains("\\")) { sendNotFound(ex); return; }

        byte[] data = null;
        String webRoot = config.getWebRoot();

        // 1) 外部目录（规范路径校验防路径遍历）
        if (webRoot != null && !webRoot.isEmpty()) {
            try {
                File baseDir = new File(webRoot).getCanonicalFile();
                File requestedFile = new File(baseDir, path).getCanonicalFile();
                if (requestedFile.getPath().startsWith(baseDir.getPath()) && requestedFile.exists() && requestedFile.isFile()) {
                    data = readFileBytes(requestedFile);
                }
            } catch (IOException e) { /* 路径不可访问，拒绝 */ }
        }
        // 2) JAR 多种读取方式
        if (data == null) data = readJarResource("web" + path.replace('\\', '/'));
        // 3) 硬编码兜�?HTML �?100% 可靠
        if (data == null && "/index.html".equals(path)) data = FALLBACK_HTML.getBytes(StandardCharsets.UTF_8);

        if (data == null) { sendNotFound(ex); return; }

            ex.getResponseHeaders().set("Content-Type", getContentType(path));
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(200, data.length);
        OutputStream os = ex.getResponseBody(); os.write(data); os.close();
    }

    /** 尝试多种方式�?JAR 读取资源 */
    private byte[] readJarResource(String entryPath) {
        // 1) ProtectionDomain �?JarFile
        try {
            java.security.ProtectionDomain pd = WebServer.class.getProtectionDomain();
            if (pd != null && pd.getCodeSource() != null && pd.getCodeSource().getLocation() != null) {
                URL u = pd.getCodeSource().getLocation();
                String jp = u.getPath();
                if (jp.startsWith("/") && System.getProperty("os.name").toLowerCase().contains("win")) jp = jp.substring(1);
                jp = URLDecoder.decode(jp, "UTF-8");
                File jf = new File(jp);
                if (jf.exists() && jf.isFile()) {
                    try (JarFile jar = new JarFile(jf)) {
                        JarEntry je = jar.getJarEntry(entryPath);
                        if (je != null) { try (InputStream is = jar.getInputStream(je)) { return readAllBytes(is); } }
                    }
                }
            }
        } catch (Exception ignored) {}
        // 2) getResource URL �?JarURLConnection
        try {
            URL ru = WebServer.class.getResource("/" + entryPath);
            if (ru != null && "jar".equals(ru.getProtocol())) {
                JarURLConnection jc = (JarURLConnection) ru.openConnection();
                try (InputStream is = jc.getInputStream()) { return readAllBytes(is); }
            }
        } catch (Exception ignored) {}
        // 3) ContextClassLoader
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                URL ru = cl.getResource(entryPath);
                if (ru != null && "jar".equals(ru.getProtocol())) {
                    JarURLConnection jc = (JarURLConnection) ru.openConnection();
                    try (InputStream is = jc.getInputStream()) { return readAllBytes(is); }
                }
            }
        } catch (Exception ignored) {}
        // 4) getResourceAsStream
        try {
            InputStream is = WebServer.class.getResourceAsStream("/" + entryPath);
            if (is != null) {
                try { return readAllBytes(is); } finally { try { is.close(); } catch (Exception ig) {} }
            }
        } catch (Exception ignored) {}
        // 5) SFW SairLoader: findResource not overridden, use getModResStream
        try {
            for (sair.SairLoader loader : sair.LoaderManager.ExecLoaders.values()) {
                java.io.InputStream is = sair.LoaderManager.getModResStream("/" + entryPath, loader);
                if (is != null) {
                    try { return readAllBytes(is); } finally { try { is.close(); } catch (Exception ig) {} }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== 硬编码兜�?HTML（不会被类加载器影响�?====================
    private static final String FALLBACK_HTML =
        "<!DOCTYPE html>\n"
        + "<html lang=\"zh-CN\">\n"
        + "<head>\n"
        + "    <meta charset=\"UTF-8\">\n"
        + "    <meta name=\"viewport\" content=\"width=device-width, initial-sc"
        + "ale=1.0\">\n"
        + "    <title>SFW Web Console</title>\n"
        + "    <style>:root {\n"
        + "    --bg-color: #0C0C0C;\n"
        + "    --font-color: #00FF41;\n"
        + "    --border-color: #1A1A2E;\n"
        + "    --accent-color: #E94560;\n"
        + "    --font-family: 'Consolas', 'Courier New', monospace;\n"
        + "    --font-size: 14px;\n"
        + "    --bg-opacity: 1.0;\n"
        + "}\n"
        + "*,*::before,*::after{margin:0;padding:0;box-sizing:border-box}\n"
        + "html,body{width:100%;height:100%;overflow:hidden;font-family:var(--f"
        + "ont-family);font-size:var(--font-size);background:#000;color:var(--f"
        + "ont-color);user-select:none;-webkit-tap-highlight-color:transparent}"
        + "\n"
        + "#particleCanvas{position:fixed;top:0;left:0;width:100%;height:100%;z"
        + "-index:0;pointer-events:none}\n"
        + ".screen{position:fixed;top:0;left:0;width:100%;height:100%;display:n"
        + "one;z-index:1}\n"
        + ".screen.active{display:flex}\n"
        + "#loginScreen{justify-content:center;align-items:center;background:rg"
        + "ba(0,0,0,0.85)}\n"
        + ".login-container{position:relative;z-index:2;width:420px;padding:50p"
        + "x 40px 40px;background:rgba(12,12,12,0.95);border:1px solid var(--bo"
        + "rder-color);border-radius:4px;box-shadow:0 0 30px rgba(0,255,65,0.08"
        + "),inset 0 0 60px rgba(0,0,0,0.5);animation:loginAppear 0.6s ease-out"
        + "}\n"
        + "@keyframes loginAppear{from{opacity:0;transform:translateY(30px) sca"
        + "le(0.96)}to{opacity:1;transform:translateY(0) scale(1)}}\n"
        + ".login-header{text-align:center;margin-bottom:35px}\n"
        + ".login-logo{font-size:48px;font-weight:bold;letter-spacing:4px;margi"
        + "n-bottom:8px}\n"
        + ".logo-bracket{color:var(--accent-color);text-shadow:0 0 20px rgba(23"
        + "3,69,96,0.4)}\n"
        + ".logo-text{color:var(--font-color);text-shadow:0 0 30px rgba(0,255,6"
        + "5,0.4);animation:logoPulse 2s ease-in-out infinite}\n"
        + "@keyframes logoPulse{0%,100%{text-shadow:0 0 30px rgba(0,255,65,0.4)"
        + "}50%{text-shadow:0 0 60px rgba(0,255,65,0.4),0 0 90px rgba(0,255,65,"
        + "0.4)}}\n"
        + ".login-subtitle{font-size:12px;color:#666;letter-spacing:8px;text-tr"
        + "ansform:uppercase}\n"
        + ".glitch-text{position:relative;display:inline-block;color:var(--font"
        + "-color);font-size:14px;letter-spacing:6px;text-shadow:0 0 10px rgba("
        + "0,255,65,0.4)}\n"
        + ".glitch-text::before,.glitch-text::after{content:attr(data-text);pos"
        + "ition:absolute;top:0;left:0;width:100%;height:100%}\n"
        + ".glitch-text::before{color:var(--accent-color);animation:glitchTop 2"
        + "s infinite linear alternate-reverse;clip-path:polygon(0 0,100% 0,100"
        + "% 35%,0 35%)}\n"
        + ".glitch-text::after{color:var(--font-color);animation:glitchBottom 2"
        + ".5s infinite linear alternate-reverse;clip-path:polygon(0 65%,100% 6"
        + "5%,100% 100%,0 100%)}\n"
        + "@keyframes glitchTop{0%{transform:translate(0)}20%{transform:transla"
        + "te(-2px,1px)}40%{transform:translate(2px,-1px)}60%{transform:transla"
        + "te(-1px,0)}80%{transform:translate(1px,1px)}100%{transform:translate"
        + "(0)}}\n"
        + "@keyframes glitchBottom{0%{transform:translate(0)}25%{transform:tran"
        + "slate(1px,-1px)}50%{transform:translate(-2px,0)}75%{transform:transl"
        + "ate(2px,1px)}100%{transform:translate(0)}}\n"
        + ".login-form{margin-top:10px}\n"
        + ".form-title{text-align:center;margin-bottom:12px}\n"
        + ".form-desc{text-align:center;font-size:12px;color:#666;margin-bottom"
        + ":24px;line-height:1.5}\n"
        + ".input-group{position:relative;margin-bottom:18px}\n"
        + ".input-group input{width:100%;padding:14px 16px;background:rgba(0,0,"
        + "0,0.6);border:1px solid #333;border-radius:3px;color:var(--font-colo"
        + "r);font-family:var(--font-family);font-size:14px;outline:none;transi"
        + "tion:all 0.3s ease}\n"
        + ".input-group input:focus{border-color:var(--font-color);box-shadow:0"
        + " 0 15px rgba(0,255,65,0.4)}\n"
        + ".input-group input::placeholder{color:#444}\n"
        + ".input-border{position:absolute;bottom:0;left:50%;width:0;height:2px"
        + ";background:var(--font-color);transition:all 0.3s ease;transform:tra"
        + "nslateX(-50%)}\n"
        + ".input-group input:focus~.input-border{width:100%}\n"
        + ".btn-neon{position:relative;display:inline-block;width:100%;padding:"
        + "14px;background:transparent;border:1px solid var(--font-color);borde"
        + "r-radius:3px;color:var(--font-color);font-family:var(--font-family);"
        + "font-size:13px;font-weight:bold;letter-spacing:4px;cursor:pointer;ov"
        + "erflow:hidden;transition:all 0.3s ease;text-transform:uppercase;marg"
        + "in-top:5px;touch-action:manipulation}\n"
        + ".btn-neon:hover{background:rgba(0,255,65,0.08);box-shadow:0 0 30px r"
        + "gba(0,255,65,0.4),inset 0 0 30px rgba(0,255,65,0.05);transform:trans"
        + "lateY(-1px)}\n"
        + ".btn-neon:active{transform:translateY(0)}\n"
        + ".btn-neon .btn-glow{position:absolute;top:-50%;left:-50%;width:200%;"
        + "height:200%;background:radial-gradient(circle,rgba(0,255,65,0.15) 0%"
        + ",transparent 70%);opacity:0;transition:opacity 0.3s ease}\n"
        + ".btn-neon:hover .btn-glow{opacity:1}\n"
        + ".btn-neon.small{width:auto;padding:8px 20px;font-size:11px;letter-sp"
        + "acing:2px}\n"
        + ".btn-neon.secondary{border-color:var(--accent-color);color:var(--acc"
        + "ent-color)}\n"
        + ".btn-neon.secondary:hover{box-shadow:0 0 20px rgba(233,69,96,0.4);ba"
        + "ckground:rgba(233,69,96,0.08)}\n"
        + ".error-msg{color:var(--accent-color);font-size:12px;text-align:cente"
        + "r;margin-top:12px;min-height:18px;text-shadow:0 0 10px rgba(233,69,9"
        + "6,0.4)}\n"
        + ".loading-spinner{text-align:center;padding:20px}\n"
        + ".spinner{display:inline-block;width:32px;height:32px;border:2px soli"
        + "d #333;border-top-color:var(--font-color);border-radius:50%;animatio"
        + "n:spin 0.8s linear infinite;margin-bottom:10px;box-shadow:0 0 15px r"
        + "gba(0,255,65,0.4)}\n"
        + "@keyframes spin{to{transform:rotate(360deg)}}\n"
        + ".loading-spinner span{display:block;font-size:12px;color:#666}\n"
        + "#mainScreen{flex-direction:column;background:var(--bg-color);opacity"
        + ":var(--bg-opacity)}\n"
        + ".titlebar{display:flex;align-items:center;justify-content:space-betw"
        + "een;height:44px;padding:0 16px;background:#0A0A0A;border-bottom:1px "
        + "solid var(--border-color);z-index:10}\n"
        + ".titlebar-left{display:flex;align-items:center;gap:8px}\n"
        + ".titlebar-icon{font-size:18px}\n"
        + ".titlebar-text{font-size:13px;font-weight:bold;color:var(--font-colo"
        + "r);letter-spacing:2px;text-shadow:0 0 10px rgba(0,255,65,0.4)}\n"
        + ".titlebar-version{font-size:10px;color:#555;background:#111;padding:"
        + "2px 6px;border-radius:3px}\n"
        + ".titlebar-center{display:flex;align-items:center}\n"
        + ".connection-status{display:flex;align-items:center;gap:6px;font-size"
        + ":10px;letter-spacing:2px;color:#555}\n"
        + ".status-dot{width:6px;height:6px;border-radius:50%;background:var(--"
        + "font-color);box-shadow:0 0 8px rgba(0,255,65,0.4);animation:dotPulse"
        + " 2s ease-in-out infinite}\n"
        + "@keyframes dotPulse{0%,100%{box-shadow:0 0 8px rgba(0,255,65,0.4)}50"
        + "%{box-shadow:0 0 18px rgba(0,255,65,0.4),0 0 30px rgba(0,255,65,0.4)"
        + "}}\n"
        + ".connection-status.disconnected .status-dot{background:var(--accent-"
        + "color);box-shadow:0 0 8px rgba(233,69,96,0.4);animation:none}\n"
        + ".connection-status.disconnected .status-text{color:var(--accent-colo"
        + "r)}\n"
        + ".titlebar-right{display:flex;align-items:center;gap:4px}\n"
        + ".titlebar-btn{display:flex;align-items:center;justify-content:center"
        + ";width:34px;height:34px;background:transparent;border:1px solid tran"
        + "sparent;border-radius:3px;color:#555;cursor:pointer;transition:all 0"
        + ".2s ease;touch-action:manipulation;-webkit-tap-highlight-color:trans"
        + "parent}\n"
        + ".titlebar-btn:hover{color:var(--font-color);border-color:#333;backgr"
        + "ound:rgba(255,255,255,0.03)}\n"
        + ".console-wrapper{flex:1;position:relative;overflow:hidden;margin:8px"
        + " 12px}\n"
        + ".console-border-inner{position:relative;width:100%;height:100%;borde"
        + "r:1px solid var(--border-color);border-radius:3px;overflow:hidden;ba"
        + "ckground:rgba(0,0,0,0.3)}\n"
        + ".console-border-corner{position:absolute;width:12px;height:12px;z-in"
        + "dex:5;pointer-events:none}\n"
        + ".corner-tl{top:-1px;left:-1px;border-top:2px solid var(--font-color)"
        + ";border-left:2px solid var(--font-color)}\n"
        + ".corner-tr{top:-1px;right:-1px;border-top:2px solid var(--font-color"
        + ");border-right:2px solid var(--font-color)}\n"
        + ".corner-bl{bottom:-1px;left:-1px;border-bottom:2px solid var(--font-"
        + "color);border-left:2px solid var(--font-color)}\n"
        + ".corner-br{bottom:-1px;right:-1px;border-bottom:2px solid var(--font"
        + "-color);border-right:2px solid var(--font-color)}\n"
        + ".console-output{width:100%;height:100%;padding:16px 20px;overflow-y:"
        + "auto;overflow-x:hidden;font-family:var(--font-family);font-size:var("
        + "--font-size);line-height:1.5;white-space:pre-wrap;word-break:break-a"
        + "ll;scroll-behavior:smooth;contain:layout style;-webkit-overflow-scro"
        + "lling:touch}\n"
        + ".console-output::-webkit-scrollbar{width:6px}\n"
        + ".console-output::-webkit-scrollbar-track{background:transparent}\n"
        + ".console-output::-webkit-scrollbar-thumb{background:#222;border-radi"
        + "us:3px}\n"
        + ".console-output::-webkit-scrollbar-thumb:hover{background:#333}\n"
        + ".output-line{padding:1px 0;animation:lineAppear 0.15s ease-out}\n"
        + "@keyframes lineAppear{from{opacity:0;transform:translateX(-4px)}to{o"
        + "pacity:1;transform:translateX(0)}}\n"
        + ".console-scroll-hint{position:absolute;bottom:8px;right:16px;z-index"
        + ":6;font-size:10px;color:#333;background:rgba(0,0,0,0.7);padding:4px "
        + "10px;border-radius:3px;border:1px solid #222;pointer-events:none;tra"
        + "nsition:all 0.3s ease;opacity:0}\n"
        + ".console-scroll-hint.visible{opacity:1}\n"
        + ".input-wrapper{padding:4px 12px 8px;z-index:10}\n"
        + ".input-container{display:flex;align-items:center;background:#0A0A0A;"
        + "border:1px solid var(--border-color);border-radius:3px;padding:0 12p"
        + "x;height:42px;transition:border-color 0.3s ease}\n"
        + ".input-container:focus-within{border-color:var(--font-color);box-sha"
        + "dow:0 0 12px rgba(0,255,65,0.4)}\n"
        + ".input-prompt{color:var(--accent-color);font-size:var(--font-size);f"
        + "ont-weight:bold;margin-right:10px;text-shadow:0 0 10px rgba(233,69,9"
        + "6,0.4)}\n"
        + "#commandInput{flex:1;background:transparent;border:none;outline:none"
        + ";color:var(--font-color);font-family:var(--font-family);font-size:va"
        + "r(--font-size);caret-color:var(--font-color)}\n"
        + "#commandInput::placeholder{color:#333}\n"
        + ".input-send-btn{display:flex;align-items:center;justify-content:cent"
        + "er;width:32px;height:32px;background:transparent;border:1px solid #3"
        + "33;border-radius:3px;color:#555;cursor:pointer;transition:all 0.2s e"
        + "ase}\n"
        + ".input-send-btn:hover{color:var(--font-color);border-color:var(--fon"
        + "t-color);box-shadow:0 0 10px rgba(0,255,65,0.4)}\n"
        + ".input-hint{display:flex;justify-content:flex-end;font-size:10px;col"
        + "or:#333;padding:4px 8px 0}\n"
        + ".settings-overlay{position:fixed;top:0;left:0;width:100%;height:100%"
        + ";background:rgba(0,0,0,0.7);z-index:100;display:none;justify-content"
        + ":center;align-items:center}\n"
        + ".settings-overlay.active{display:flex}\n"
        + ".settings-panel{width:560px;max-height:85vh;background:#0E0E0E;borde"
        + "r:1px solid var(--border-color);border-radius:4px;box-shadow:0 0 40p"
        + "x rgba(0,0,0,0.8),0 0 60px rgba(0,255,65,0.05);display:flex;flex-dir"
        + "ection:column;animation:panelAppear 0.3s ease-out}\n"
        + "@keyframes panelAppear{from{opacity:0;transform:scale(0.95)}to{opaci"
        + "ty:1;transform:scale(1)}}\n"
        + ".settings-header{display:flex;align-items:center;justify-content:spa"
        + "ce-between;padding:16px 20px;border-bottom:1px solid var(--border-co"
        + "lor)}\n"
        + ".settings-header h2{font-size:14px;letter-spacing:4px;color:var(--fo"
        + "nt-color);text-shadow:0 0 10px rgba(0,255,65,0.4)}\n"
        + ".settings-close{width:30px;height:30px;background:transparent;border"
        + ":1px solid transparent;border-radius:3px;color:#555;font-size:16px;c"
        + "ursor:pointer;transition:all 0.2s}\n"
        + ".settings-close:hover{color:var(--accent-color);border-color:#333}\n"
        + ".settings-body{flex:1;overflow-y:auto;padding:20px}\n"
        + ".settings-body::-webkit-scrollbar{width:4px}\n"
        + ".settings-body::-webkit-scrollbar-track{background:transparent}\n"
        + ".settings-body::-webkit-scrollbar-thumb{background:#222;border-radiu"
        + "s:2px}\n"
        + ".settings-section{margin-bottom:24px;padding-bottom:20px;border-bott"
        + "om:1px solid #111}\n"
        + ".settings-section:last-child{border-bottom:none;margin-bottom:0}\n"
        + ".settings-section h3{font-size:13px;color:var(--font-color);margin-b"
        + "ottom:14px;letter-spacing:2px;text-shadow:0 0 6px rgba(0,255,65,0.4)"
        + "}\n"
        + ".settings-row{display:flex;align-items:center;margin-bottom:10px;gap"
        + ":10px}\n"
        + ".settings-row label{min-width:100px;font-size:12px;color:#666}\n"
        + ".settings-row input[type=\"text\"],.settings-row input[type=\"passwo"
        + "rd\"],.settings-row input[type=\"number\"]{flex:1;padding:8px 12px;b"
        + "ackground:#0A0A0A;border:1px solid #222;border-radius:3px;color:var("
        + "--font-color);font-family:var(--font-family);font-size:12px;outline:"
        + "none;transition:border-color 0.2s}\n"
        + ".settings-row input:focus{border-color:var(--font-color)}\n"
        + ".settings-row input[type=\"range\"]{flex:1;accent-color:var(--font-c"
        + "olor);height:4px}\n"
        + ".settings-row input[type=\"color\"]{width:36px;height:30px;padding:0"
        + ";border:1px solid #222;border-radius:3px;background:transparent;curs"
        + "or:pointer}\n"
        + ".color-text{width:80px!important;flex:0!important}\n"
        + ".settings-row span{font-size:11px;color:#666;min-width:40px}\n"
        + ".settings-msg{display:inline-block;font-size:11px;margin-left:10px;m"
        + "in-height:16px}\n"
        + ".settings-msg.success{color:var(--font-color)}\n"
        + ".settings-msg.error{color:var(--accent-color)}\n"
        + ".toast{position:fixed;top:20px;right:20px;z-index:200;padding:12px 2"
        + "4px;background:#0A0A0A;border:1px solid var(--border-color);border-r"
        + "adius:3px;font-size:12px;color:var(--font-color);box-shadow:0 0 20px"
        + " rgba(0,0,0,0.8);transform:translateX(120%);transition:transform 0.3"
        + "s ease;max-width:400px}\n"
        + ".toast.show{transform:translateX(0)}\n"
        + ".toast.error{border-color:var(--accent-color);color:var(--accent-col"
        + "or);box-shadow:0 0 20px rgba(233,69,96,0.3)}\n"
        + "@media(max-width:600px){.login-container{width:90%;padding:30px 20px"
        + ";max-width:420px}.login-logo{font-size:36px}.settings-panel{width:95"
        + "%;max-height:90vh}.settings-row{flex-wrap:wrap}.settings-row label{m"
        + "in-width:80px;font-size:11px}.titlebar{padding:0 8px;height:48px}.ti"
        + "tlebar-text{font-size:11px}.titlebar-btn{width:40px;height:40px}.con"
        + "sole-wrapper{margin:4px 6px}.input-wrapper{padding:4px 6px 6px}.inpu"
        + "t-container{height:48px}.input-send-btn{width:40px;height:40px}.inpu"
        + "t-hint{display:none}.settings-row input[type=\"text\"],.settings-row"
        + " input[type=\"password\"],.settings-row input[type=\"number\"]{font-"
        + "size:16px}.color-text{width:65px!important}.settings-row span{min-wi"
        + "dth:32px}.console-output{padding:12px 14px;font-size:13px}}\n"
        + "@media(prefers-reduced-motion:reduce){.logo-text{animation:none}.gli"
        + "tch-text::before,.glitch-text::after{animation:none}.status-dot{anim"
        + "ation:none}.output-line{animation:none}.spinner{animation:none}*{ani"
        + "mation-duration:0.01ms!important;transition-duration:0.01ms!importan"
        + "t}}\n"
        + "\n"
        + "/* ========== 文件管理器 ========== */\n"
        + "#fmScreen{flex-direction:column;background:var(--bg-color);opacity:v"
        + "ar(--bg-opacity)}\n"
        + ".fm-toolbar{display:flex;align-items:center;gap:6px;padding:8px 12px"
        + ";background:#0A0A0A;border-bottom:1px solid var(--border-color);flex"
        + "-wrap:wrap;z-index:10}\n"
        + ".fm-toolbar-btn{display:flex;align-items:center;justify-content:cent"
        + "er;width:34px;height:34px;background:transparent;border:1px solid #3"
        + "33;border-radius:3px;color:#888;cursor:pointer;font-size:16px;transi"
        + "tion:all .2s;touch-action:manipulation}\n"
        + ".fm-toolbar-btn:hover{color:var(--font-color);border-color:var(--fon"
        + "t-color);box-shadow:0 0 10px rgba(0,255,65,.3)}\n"
        + ".fm-toolbar-btn.active{color:var(--font-color);border-color:var(--fo"
        + "nt-color);background:rgba(0,255,65,.08)}\n"
        + ".fm-back-btn{font-size:18px;width:36px}\n"
        + ".fm-breadcrumb{flex:1;display:flex;align-items:center;gap:2px;font-s"
        + "ize:12px;overflow-x:auto;white-space:nowrap;min-width:0;-webkit-over"
        + "flow-scrolling:touch}\n"
        + ".fm-breadcrumb::-webkit-scrollbar{height:2px}\n"
        + ".fm-breadcrumb::-webkit-scrollbar-thumb{background:#222}\n"
        + ".fm-crumb{color:var(--font-color);cursor:pointer;padding:2px 6px;bor"
        + "der-radius:3px;transition:all .2s;text-shadow:0 0 6px rgba(0,255,65,"
        + ".3);flex-shrink:0}\n"
        + ".fm-crumb:hover{background:rgba(0,255,65,.1)}\n"
        + ".fm-crumb-sep{color:#444;flex-shrink:0}\n"
        + ".fm-content{flex:1;overflow:hidden;position:relative}\n"
        + ".fm-file-list{overflow-y:auto;height:100%;padding:4px 0;-webkit-over"
        + "flow-scrolling:touch;contain:layout style}\n"
        + ".fm-file-list::-webkit-scrollbar{width:5px}\n"
        + ".fm-file-list::-webkit-scrollbar-track{background:transparent}\n"
        + ".fm-file-list::-webkit-scrollbar-thumb{background:#222;border-radius"
        + ":3px}\n"
        + ".fm-file-grid{overflow-y:auto;height:100%;display:flex;flex-wrap:wra"
        + "p;align-content:flex-start;gap:8px;padding:12px;-webkit-overflow-scr"
        + "olling:touch;contain:layout style}\n"
        + ".fm-file-grid::-webkit-scrollbar{width:5px}\n"
        + ".fm-file-grid::-webkit-scrollbar-thumb{background:#222;border-radius"
        + ":3px}\n"
        + ".fm-list-item{display:flex;align-items:center;gap:10px;padding:8px 1"
        + "6px;border-bottom:1px solid rgba(255,255,255,.03);cursor:pointer;tra"
        + "nsition:all .15s;font-size:13px;animation:lineAppear .15s ease-out}\n"
        + ".fm-list-item:hover{background:rgba(0,255,65,.04)}\n"
        + ".fm-list-item.selected{background:rgba(0,255,65,.08);border-left:2px"
        + " solid var(--font-color)}\n"
        + ".fm-item-icon{font-size:18px;flex-shrink:0;width:24px;text-align:cen"
        + "ter}\n"
        + ".fm-item-name{flex:1;overflow:hidden;text-overflow:ellipsis;white-sp"
        + "ace:nowrap;color:var(--font-color)}\n"
        + ".fm-item-size{width:70px;text-align:right;font-size:11px;color:#555;"
        + "flex-shrink:0}\n"
        + ".fm-item-date{width:130px;text-align:right;font-size:11px;color:#444"
        + ";flex-shrink:0}\n"
        + ".fm-item-actions{display:flex;gap:2px;flex-shrink:0;opacity:0;transi"
        + "tion:opacity .2s}\n"
        + ".fm-list-item:hover .fm-item-actions,.fm-grid-item:hover .fm-item-ac"
        + "tions{opacity:1}\n"
        + ".fm-grid-actions{margin-top:4px}\n"
        + ".fm-btn-sm{width:28px;height:28px;background:transparent;border:1px "
        + "solid #333;border-radius:3px;color:#666;cursor:pointer;font-size:14p"
        + "x;display:flex;align-items:center;justify-content:center;transition:"
        + "all .2s;touch-action:manipulation}\n"
        + ".fm-btn-sm:hover{color:var(--font-color);border-color:var(--font-col"
        + "or)}\n"
        + ".fm-btn-danger:hover{color:var(--accent-color);border-color:var(--ac"
        + "cent-color)}\n"
        + ".fm-grid-item{width:120px;display:flex;flex-direction:column;align-i"
        + "tems:center;padding:8px;border:1px solid transparent;border-radius:6"
        + "px;cursor:pointer;transition:all .15s;animation:lineAppear .15s ease"
        + "-out}\n"
        + ".fm-grid-item:hover{background:rgba(0,255,65,.04);border-color:rgba("
        + "0,255,65,.15)}\n"
        + ".fm-grid-item.selected{background:rgba(0,255,65,.08);border-color:va"
        + "r(--font-color)}\n"
        + ".fm-grid-preview{width:100px;height:80px;display:flex;align-items:ce"
        + "nter;justify-content:center;overflow:hidden;border-radius:4px;backgr"
        + "ound:rgba(0,0,0,.4);margin-bottom:6px}\n"
        + ".fm-grid-preview img{max-width:100%;max-height:100%;object-fit:cover"
        + "}\n"
        + ".fm-grid-media-icon{font-size:36px}\n"
        + ".fm-grid-file-icon{font-size:40px}\n"
        + ".fm-grid-name{font-size:11px;text-align:center;word-break:break-all;"
        + "max-width:100%;overflow:hidden;text-overflow:ellipsis;display:-webki"
        + "t-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;color:var(--f"
        + "ont-color);line-height:1.3}\n"
        + ".fm-grid-size{font-size:10px;color:#444;margin-top:2px}\n"
        + ".fm-empty{text-align:center;padding:60px 20px;color:#444;font-size:1"
        + "4px}\n"
        + ".fm-overlay{position:fixed;top:0;left:0;width:100%;height:100%;z-ind"
        + "ex:150;display:none;justify-content:center;align-items:center;backgr"
        + "ound:rgba(0,0,0,.85)}\n"
        + ".fm-overlay.active{display:flex}\n"
        + ".fm-overlay-panel{position:relative;max-width:90vw;max-height:90vh;b"
        + "ackground:#0E0E0E;border:1px solid var(--border-color);border-radius"
        + ":4px;box-shadow:0 0 40px rgba(0,0,0,.8);display:flex;flex-direction:"
        + "column;animation:panelAppear .3s ease-out}\n"
        + ".fm-overlay-header{display:flex;align-items:center;justify-content:s"
        + "pace-between;padding:12px 16px;border-bottom:1px solid var(--border-"
        + "color)}\n"
        + ".fm-overlay-header span{font-size:13px;color:var(--font-color);text-"
        + "shadow:0 0 6px rgba(0,255,65,.4);overflow:hidden;text-overflow:ellip"
        + "sis;white-space:nowrap}\n"
        + ".fm-overlay-close{width:30px;height:30px;background:transparent;bord"
        + "er:1px solid transparent;border-radius:3px;color:#666;font-size:16px"
        + ";cursor:pointer;transition:all .2s;touch-action:manipulation}\n"
        + ".fm-overlay-close:hover{color:var(--accent-color)}\n"
        + ".fm-overlay-body{flex:1;overflow:auto;display:flex;align-items:cente"
        + "r;justify-content:center;min-width:300px;min-height:150px}\n"
        + ".fm-overlay-body::-webkit-scrollbar{width:4px}\n"
        + ".fm-overlay-body::-webkit-scrollbar-thumb{background:#222}\n"
        + ".fm-editor-panel{width:85vw;max-width:900px;height:80vh}\n"
        + ".fm-editor-textarea{width:100%;height:100%;min-height:300px;backgrou"
        + "nd:#0A0A0A;border:none;outline:none;color:var(--font-color);font-fam"
        + "ily:var(--font-family);font-size:13px;padding:16px;resize:none;line-"
        + "height:1.5;tab-size:4}\n"
        + ".fm-code-highlight{font-family:'Consolas','Courier New',monospace;fo"
        + "nt-size:13px;line-height:1.6}\n"
        + ".fm-ir-highlight{color:#E6DB74;background:#0A0A0A}\n"
        + ".fm-editor-footer{display:flex;justify-content:flex-end;gap:8px;padd"
        + "ing:10px 16px;border-top:1px solid var(--border-color);background:#0"
        + "A0A0A}\n"
        + ".fm-editor-footer .btn-neon.small{margin-top:0}\n"
        + ".fm-dialog{position:fixed;top:0;left:0;width:100%;height:100%;z-inde"
        + "x:160;display:none;justify-content:center;align-items:center;backgro"
        + "und:rgba(0,0,0,.7)}\n"
        + ".fm-dialog.active{display:flex}\n"
        + ".fm-dialog-panel{width:400px;max-width:90vw;background:#0E0E0E;borde"
        + "r:1px solid var(--border-color);border-radius:4px;padding:20px;box-s"
        + "hadow:0 0 40px rgba(0,0,0,.8);animation:panelAppear .3s ease-out}\n"
        + ".fm-dialog-panel h3{font-size:13px;color:var(--font-color);margin-bo"
        + "ttom:14px;text-shadow:0 0 6px rgba(0,255,65,.4)}\n"
        + ".fm-dialog-panel input[type=\"text\"]{width:100%;padding:10px 12px;b"
        + "ackground:#0A0A0A;border:1px solid #333;border-radius:3px;color:var("
        + "--font-color);font-family:var(--font-family);font-size:13px;outline:"
        + "none;margin-bottom:12px;transition:border-color .2s}\n"
        + ".fm-dialog-panel input[type=\"text\"]:focus{border-color:var(--font-"
        + "color);box-shadow:0 0 10px rgba(0,255,65,.3)}\n"
        + ".fm-dialog-panel label{display:flex;align-items:center;gap:8px;font-"
        + "size:12px;color:#666;margin-bottom:12px;cursor:pointer}\n"
        + ".fm-dialog-panel label input[type=\"checkbox\"]{accent-color:var(--f"
        + "ont-color)}\n"
        + ".fm-dialog-btns{display:flex;justify-content:flex-end;gap:8px;margin"
        + "-top:4px}\n"
        + "@media(max-width:600px){\n"
        + ".fm-toolbar{padding:6px 8px;gap:4px}\n"
        + ".fm-toolbar-btn{width:40px;height:40px;font-size:18px}\n"
        + ".fm-list-item{padding:10px 8px;gap:6px;font-size:12px}\n"
        + ".fm-item-size{display:none}\n"
        + ".fm-item-date{width:auto;font-size:10px}\n"
        + ".fm-item-actions{opacity:1}\n"
        + ".fm-item-actions .fm-btn-sm{width:34px;height:34px;font-size:16px}\n"
        + ".fm-grid-item{width:100px;padding:6px}\n"
        + ".fm-grid-preview{width:84px;height:70px}\n"
        + ".fm-grid-name{font-size:10px}\n"
        + ".fm-editor-panel{width:95vw;height:85vh}\n"
        + ".fm-overlay-panel{max-width:95vw;max-height:95vh}\n"
        + ".fm-breadcrumb{font-size:11px}\n"
        + "}\n"
        + "@media(prefers-reduced-motion:reduce){.fm-list-item,.fm-grid-item{an"
        + "imation:none}}\n"
        + "</style>\n"
        + "    <link rel=\"icon\" href=\"data:image/svg+xml,<svg xmlns='http://"
        + "www.w3.org/2000/svg' viewBox='0 0 32 32'><text y='28' font-size='28'"
        + ">⚡</text></svg>\">\n"
        + "</head>\n"
        + "<body>\n"
        + "    <canvas id=\"particleCanvas\"></canvas>\n"
        + "\n"
        + "    <!-- 登录界面 -->\n"
        + "    <div id=\"loginScreen\" class=\"screen active\">\n"
        + "        <div class=\"login-container\">\n"
        + "            <div class=\"login-header\">\n"
        + "                <div class=\"login-logo\">\n"
        + "                    <span class=\"logo-bracket\">{</span>\n"
        + "                    <span class=\"logo-text\">SFW</span>\n"
        + "                    <span class=\"logo-bracket\">}</span>\n"
        + "                </div>\n"
        + "                <div class=\"login-subtitle\">Web Remote Console</di"
        + "v>\n"
        + "            </div>\n"
        + "            <div id=\"setupForm\" class=\"login-form\" style=\"displ"
        + "ay:none;\">\n"
        + "                <div class=\"form-title\">\n"
        + "                    <span class=\"glitch-text\" data-text=\"INITIAL "
        + "SETUP\">INITIAL SETUP</span>\n"
        + "                </div>\n"
        + "                <p class=\"form-desc\">首次使用，请设置登录密码（至少6位）</p>\n"
        + "                <div class=\"input-group\">\n"
        + "                    <input type=\"password\" id=\"setupPassword\" pl"
        + "aceholder=\"设置密码\" autocomplete=\"new-password\">\n"
        + "                    <span class=\"input-border\"></span>\n"
        + "                </div>\n"
        + "                <div class=\"input-group\">\n"
        + "                    <input type=\"password\" id=\"setupConfirm\" pla"
        + "ceholder=\"确认密码\" autocomplete=\"new-password\">\n"
        + "                    <span class=\"input-border\"></span>\n"
        + "                </div>\n"
        + "                <button class=\"btn-neon\" onclick=\"doSetup()\">\n"
        + "                    <span>SET PASSWORD</span><span class=\"btn-glow\">"
        + "</span>\n"
        + "                </button>\n"
        + "                <div id=\"setupError\" class=\"error-msg\"></div>\n"
        + "            </div>\n"
        + "            <div id=\"loginForm\" class=\"login-form\">\n"
        + "                <div class=\"form-title\">\n"
        + "                    <span class=\"glitch-text\" data-text=\"AUTHENTI"
        + "CATION\">AUTHENTICATION</span>\n"
        + "                </div>\n"
        + "                <p class=\"form-desc\">请输入密码以访问 SFW 控制台</p>\n"
        + "                <div class=\"input-group\">\n"
        + "                    <input type=\"password\" id=\"loginPassword\" pl"
        + "aceholder=\"输入密码\" autocomplete=\"current-password\">\n"
        + "                    <span class=\"input-border\"></span>\n"
        + "                </div>\n"
        + "                <button class=\"btn-neon\" onclick=\"doLogin()\">\n"
        + "                    <span>LOGIN</span><span class=\"btn-glow\"></spa"
        + "n>\n"
        + "                </button>\n"
        + "                <div id=\"loginError\" class=\"error-msg\"></div>\n"
        + "            </div>\n"
        + "            <div id=\"loadingIndicator\" class=\"loading-spinner\" s"
        + "tyle=\"display:none;\">\n"
        + "                <div class=\"spinner\"></div><span>Connecting...</sp"
        + "an>\n"
        + "            </div>\n"
        + "        </div>\n"
        + "    </div>\n"
        + "\n"
        + "    <!-- 主控制台 -->\n"
        + "    <div id=\"mainScreen\" class=\"screen\">\n"
        + "        <header class=\"titlebar\">\n"
        + "            <div class=\"titlebar-left\">\n"
        + "                <span class=\"titlebar-icon\">⚡</span>\n"
        + "                <span class=\"titlebar-text\">SFW Web Console</span>\n"
        + "                <span class=\"titlebar-version\">v1.0</span>\n"
        + "            </div>\n"
        + "            <div class=\"titlebar-center\">\n"
        + "                <div class=\"connection-status\" id=\"connectionStat"
        + "us\">\n"
        + "                    <span class=\"status-dot\"></span>\n"
        + "                    <span class=\"status-text\">CONNECTED</span>\n"
        + "                </div>\n"
        + "            </div>\n"
        + "            <div class=\"titlebar-right\">\n"
        + "                <button class=\"titlebar-btn\" onclick=\"enterFileMa"
        + "nager()\" title=\"文件管理\">\n"
        + "                    <svg width=\"18\" height=\"18\" viewBox=\"0 0 24"
        + " 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">\n"
        + "                        <path d=\"M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2"
        + "-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z\"/>\n"
        + "                    </svg>\n"
        + "                </button>\n"
        + "                <button class=\"titlebar-btn\" onclick=\"toggleSetti"
        + "ngs()\" title=\"设置\">\n"
        + "                    <svg width=\"18\" height=\"18\" viewBox=\"0 0 24"
        + " 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">\n"
        + "                        <circle cx=\"12\" cy=\"12\" r=\"3\"/><path d"
        + "=\"M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18.36l1.42 1.42M1 12h2M"
        + "21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.42\"/>\n"
        + "                    </svg>\n"
        + "                </button>\n"
        + "                <button class=\"titlebar-btn\" onclick=\"clearConsol"
        + "e()\" title=\"清空控制台\">\n"
        + "                    <svg width=\"18\" height=\"18\" viewBox=\"0 0 24"
        + " 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">\n"
        + "                        <polyline points=\"3 6 5 6 21 6\"/><path d=\"M"
        + "19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 "
        + "0 1 2 2v2\"/>\n"
        + "                    </svg>\n"
        + "                </button>\n"
        + "                <button class=\"titlebar-btn\" onclick=\"doLogout()\" "
        + "title=\"退出登录\">\n"
        + "                    <svg width=\"18\" height=\"18\" viewBox=\"0 0 24"
        + " 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">\n"
        + "                        <path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 "
        + "2-2h4\"/><polyline points=\"16 17 21 12 16 7\"/><line x1=\"21\" y1=\"1"
        + "2\" x2=\"9\" y2=\"12\"/>\n"
        + "                    </svg>\n"
        + "                </button>\n"
        + "            </div>\n"
        + "        </header>\n"
        + "\n"
        + "        <div class=\"console-wrapper\">\n"
        + "            <div class=\"console-border-inner\">\n"
        + "                <div class=\"console-border-corner corner-tl\"></div"
        + ">\n"
        + "                <div class=\"console-border-corner corner-tr\"></div"
        + ">\n"
        + "                <div class=\"console-border-corner corner-bl\"></div"
        + ">\n"
        + "                <div class=\"console-border-corner corner-br\"></div"
        + ">\n"
        + "                <div id=\"consoleOutput\" class=\"console-output\"><"
        + "/div>\n"
        + "            </div>\n"
        + "            <div class=\"console-scroll-hint\" id=\"scrollHint\"><sp"
        + "an>▼ 自动滚动</span></div>\n"
        + "        </div>\n"
        + "\n"
        + "        <div class=\"input-wrapper\">\n"
        + "            <div class=\"input-container\">\n"
        + "                <span class=\"input-prompt\" id=\"inputPrompt\">&gt;"
        + "</span>\n"
        + "                <input type=\"text\" id=\"commandInput\" placeholder"
        + "=\"输入 SFW 命令...\" autocomplete=\"off\" spellcheck=\"false\" autocorr"
        + "ect=\"off\" autocapitalize=\"off\" inputmode=\"text\">\n"
        + "                <button class=\"input-send-btn\" onclick=\"sendComma"
        + "nd()\" title=\"发送命令\">\n"
        + "                    <svg width=\"16\" height=\"16\" viewBox=\"0 0 24"
        + " 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">\n"
        + "                        <line x1=\"22\" y1=\"2\" x2=\"11\" y2=\"13\"/"
        + "><polygon points=\"22 2 15 22 11 13 2 9 22 2\"/>\n"
        + "                    </svg>\n"
        + "                </button>\n"
        + "            </div>\n"
        + "            <div class=\"input-hint\"><span>Enter 发送 · ↑↓ 历史命令 · Ctr"
        + "l+L 清屏</span></div>\n"
        + "        </div>\n"
        + "    </div>\n"
        + "\n"
        + "    <!-- 设置面板 -->\n"
        + "    <div id=\"settingsOverlay\" class=\"settings-overlay\" onclick=\"i"
        + "f(event.target===this)toggleSettings()\">\n"
        + "        <div class=\"settings-panel\">\n"
        + "            <div class=\"settings-header\">\n"
        + "                <h2><svg width=\"20\" height=\"20\" viewBox=\"0 0 24"
        + " 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" style"
        + "=\"vertical-align:middle;margin-right:8px;\"><circle cx=\"12\" cy=\"1"
        + "2\" r=\"3\"/><path d=\"M12 1v2M12 21v2M4.22 4.22l1.42 1.42M18.36 18."
        + "36l1.42 1.42M1 12h2M21 12h2M4.22 19.78l1.42-1.42M18.36 5.64l1.42-1.4"
        + "2\"/></svg>SETTINGS</h2>\n"
        + "                <button class=\"settings-close\" onclick=\"toggleSet"
        + "tings()\">✕</button>\n"
        + "            </div>\n"
        + "            <div class=\"settings-body\">\n"
        + "                <div class=\"settings-section\">\n"
        + "                    <h3>🔒 密码管理</h3>\n"
        + "                    <div class=\"settings-row\"><label>当前密码</label><"
        + "input type=\"password\" id=\"settingsOldPassword\" placeholder=\"输入当"
        + "前密码\"></div>\n"
        + "                    <div class=\"settings-row\"><label>新密码</label><i"
        + "nput type=\"password\" id=\"settingsNewPassword\" placeholder=\"输入新密"
        + "码（至少6位）\"></div>\n"
        + "                    <button class=\"btn-neon small\" onclick=\"chang"
        + "ePassword()\">更新密码</button>\n"
        + "                    <span id=\"passwordMsg\" class=\"settings-msg\">"
        + "</span>\n"
        + "                </div>\n"
        + "                <div class=\"settings-section\">\n"
        + "                    <h3>🎨 主题定制</h3>\n"
        + "                    <div class=\"settings-row color-row\"><label>背景颜"
        + "色</label><input type=\"color\" id=\"themeBgColor\" onchange=\"applyT"
        + "hemeLive()\"><input type=\"text\" id=\"themeBgColorText\" class=\"co"
        + "lor-text\" onchange=\"syncColorFromText('themeBgColor')\"></div>\n"
        + "                    <div class=\"settings-row color-row\"><label>文字颜"
        + "色</label><input type=\"color\" id=\"themeFontColor\" onchange=\"appl"
        + "yThemeLive()\"><input type=\"text\" id=\"themeFontColorText\" class="
        + "\"color-text\" onchange=\"syncColorFromText('themeFontColor')\"></di"
        + "v>\n"
        + "                    <div class=\"settings-row color-row\"><label>边框颜"
        + "色</label><input type=\"color\" id=\"themeBorderColor\" onchange=\"ap"
        + "plyThemeLive()\"><input type=\"text\" id=\"themeBorderColorText\" cl"
        + "ass=\"color-text\" onchange=\"syncColorFromText('themeBorderColor')\">"
        + "</div>\n"
        + "                    <div class=\"settings-row color-row\"><label>强调色"
        + "</label><input type=\"color\" id=\"themeAccentColor\" onchange=\"app"
        + "lyThemeLive()\"><input type=\"text\" id=\"themeAccentColorText\" cla"
        + "ss=\"color-text\" onchange=\"syncColorFromText('themeAccentColor')\">"
        + "</div>\n"
        + "                    <div class=\"settings-row\"><label>字体大小</label><"
        + "input type=\"range\" id=\"themeFontSize\" min=\"10\" max=\"24\" valu"
        + "e=\"14\" oninput=\"applyThemeLive()\"><span id=\"fontSizeValue\">14p"
        + "x</span></div>\n"
        + "                    <div class=\"settings-row\"><label>背景透明度</label>"
        + "<input type=\"range\" id=\"themeOpacity\" min=\"0.3\" max=\"1\" step"
        + "=\"0.05\" value=\"1\" oninput=\"applyThemeLive()\"><span id=\"opacit"
        + "yValue\">1.0</span></div>\n"
        + "                    <button class=\"btn-neon small\" onclick=\"saveT"
        + "heme()\">保存主题</button>\n"
        + "                    <button class=\"btn-neon small secondary\" oncli"
        + "ck=\"resetTheme()\">恢复默认</button>\n"
        + "                    <span id=\"themeMsg\" class=\"settings-msg\"></s"
        + "pan>\n"
        + "                </div>\n"
        + "                <div class=\"settings-section\">\n"
        + "                    <h3>⚙️ 服务器配置</h3>\n"
        + "                    <div class=\"settings-row\"><label>会话超时（分钟）</lab"
        + "el><input type=\"number\" id=\"settingsTimeout\" min=\"5\" max=\"144"
        + "0\" value=\"30\"></div>\n"
        + "                    <div class=\"settings-row\"><label>最大输出行数</label"
        + "><input type=\"number\" id=\"settingsMaxLines\" min=\"100\" max=\"50"
        + "000\" value=\"5000\"></div>\n"
        + "                    <button class=\"btn-neon small\" onclick=\"saveS"
        + "erverConfig()\">保存配置</button>\n"
        + "                    <span id=\"serverConfigMsg\" class=\"settings-ms"
        + "g\"></span>\n"
        + "                </div>\n"
        + "                <div class=\"settings-section\">\n"
        + "                    <h3>👥 会话管理</h3>\n"
        + "                    <div id=\"sessionsList\" style=\"font-size:12px;"
        + "color:#888;max-height:160px;overflow-y:auto;margin-bottom:8px\"><spa"
        + "n style=\"color:#555\">点击加载...</span></div>\n"
        + "                    <button class=\"btn-neon small\" onclick=\"loadS"
        + "essions()\">刷新会话</button>\n"
        + "                    <div id=\"sessionHistory\" style=\"display:none;"
        + "max-height:180px;overflow-y:auto;font-size:11px;color:#555;border-to"
        + "p:1px solid #111;padding-top:8px;margin-top:8px\"></div>\n"
        + "                </div>\n"
        + "                <div class=\"settings-section\">\n"
        + "                    <h3>🌐 网络与安全</h3>\n"
        + "                    <div class=\"settings-row\"><label>HTTP 端口</labe"
        + "l><input type=\"number\" id=\"settingsPort\" min=\"1\" max=\"65535\" "
        + "value=\"8080\"></div>\n"
        + "                    <div class=\"settings-row\"><label>启用 HTTPS</lab"
        + "el><select id=\"settingsHttps\" onchange=\"htsToggle()\"><option val"
        + "ue=\"false\">关闭</option><option value=\"true\">启用</option></select><"
        + "span style=\"font-size:10px;color:#555;margin-left:8px\">需 PEM 证书+私钥"
        + "，自动重启</span></div>\n"
        + "                    <div id=\"ksRow\" class=\"settings-row\" style=\"d"
        + "isplay:none\"><label>证书文件(PEM)</label><input type=\"text\" id=\"sett"
        + "ingsCertFile\" placeholder=\"C:\\path\\to\\cert.pem\"></div>\n"
        + "                    <div id=\"kpRow\" class=\"settings-row\" style=\"d"
        + "isplay:none\"><label>私钥文件(KEY)</label><input type=\"text\" id=\"sett"
        + "ingsKeyFile\" placeholder=\"C:\\path\\to\\key.pem\"></div>\n"
        + "                    <div class=\"settings-row\"><label>Web 根目录</labe"
        + "l><input type=\"text\" id=\"settingsWebRoot\" placeholder=\"留空=使用JAR"
        + "内置页面\"></div>\n"
        + "                    <button class=\"btn-neon small\" onclick=\"saveN"
        + "etworkConfig()\">保存网络配置</button>\n"
        + "                    <span id=\"networkConfigMsg\" class=\"settings-m"
        + "sg\"></span>\n"
        + "                </div>\n"
        + "            </div>\n"
        + "        </div>\n"
        + "    </div>\n"
        + "\n"
        + "    <div id=\"toast\" class=\"toast\"></div>\n"
        + "    <script src=\"/js/app.js\"></script>\n"
        + "    <script src=\"/js/filemanager.js\"></script>\n"
        + "\n"
        + "    <!-- 文件管理器 -->\n"
        + "    <div id=\"fmScreen\" class=\"screen\">\n"
        + "        <header class=\"titlebar\">\n"
        + "            <div class=\"titlebar-left\">\n"
        + "                <button class=\"titlebar-btn fm-back-btn\" onclick=\"e"
        + "xitFileManager()\" title=\"返回控制台\">\n"
        + "                    <svg width=\"18\" height=\"18\" viewBox=\"0 0 24"
        + " 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><poly"
        + "line points=\"15 18 9 12 15 6\"/></svg>\n"
        + "                </button>\n"
        + "                <span class=\"titlebar-icon\">📁</span>\n"
        + "                <span class=\"titlebar-text\">文件管理</span>\n"
        + "            </div>\n"
        + "            <div class=\"titlebar-center\">\n"
        + "                <div class=\"connection-status\" id=\"fmConnectionSt"
        + "atus\">\n"
        + "                    <span class=\"status-dot\"></span>\n"
        + "                    <span class=\"status-text\">CONNECTED</span>\n"
        + "                </div>\n"
        + "            </div>\n"
        + "            <div class=\"titlebar-right\">\n"
        + "                <button class=\"titlebar-btn\" onclick=\"exitFileMan"
        + "ager()\" title=\"退出\">\n"
        + "                    <svg width=\"18\" height=\"18\" viewBox=\"0 0 24"
        + " 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\">\n"
        + "                        <path d=\"M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 "
        + "2-2h4\"/><polyline points=\"16 17 21 12 16 7\"/><line x1=\"21\" y1=\"1"
        + "2\" x2=\"9\" y2=\"12\"/>\n"
        + "                    </svg>\n"
        + "                </button>\n"
        + "            </div>\n"
        + "        </header>\n"
        + "\n"
        + "        <!-- 工具栏 -->\n"
        + "        <div class=\"fm-toolbar\">\n"
        + "            <button class=\"fm-toolbar-btn fm-back-btn\" id=\"fmBack"
        + "Btn\" onclick=\"goBack()\" title=\"返回上级\" style=\"visibility:hidden\">"
        + "⬆</button>\n"
        + "            <div class=\"fm-breadcrumb\" id=\"fmBreadcrumb\"><span c"
        + "lass=\"fm-crumb\">📁 SFW</span></div>\n"
        + "            <button class=\"fm-toolbar-btn\" onclick=\"createFileDia"
        + "log()\" title=\"新建\">➕</button>\n"
        + "            <button class=\"fm-toolbar-btn\" onclick=\"uploadFileDia"
        + "log()\" title=\"上传\">📤</button>\n"
        + "            <button class=\"fm-toolbar-btn active\" id=\"fmViewList\" "
        + "onclick=\"setFmViewMode('list')\" title=\"列表视图\">☰</button>\n"
        + "            <button class=\"fm-toolbar-btn\" id=\"fmViewGrid\" oncli"
        + "ck=\"setFmViewMode('grid')\" title=\"平铺视图\">⊞</button>\n"
        + "            <input type=\"file\" id=\"fmUploadInput\" onchange=\"onU"
        + "ploadFile(this)\" style=\"display:none\">\n"
        + "        </div>\n"
        + "\n"
        + "        <!-- 文件列表 -->\n"
        + "        <div class=\"fm-content\">\n"
        + "            <div class=\"fm-file-list\" id=\"fmFileList\">\n"
        + "                <div class=\"fm-empty\">加载中...</div>\n"
        + "            </div>\n"
        + "        </div>\n"
        + "    </div>\n"
        + "\n"
        + "    <!-- 文件预览浮层 -->\n"
        + "    <div id=\"fmPreview\" class=\"fm-overlay\" onclick=\"if(event.ta"
        + "rget===this)closeFmPreview()\">\n"
        + "        <div class=\"fm-overlay-panel\">\n"
        + "            <div class=\"fm-overlay-header\">\n"
        + "                <span id=\"fmPreviewTitle\">预览</span>\n"
        + "                <button class=\"fm-overlay-close\" onclick=\"closeFm"
        + "Preview()\">✕</button>\n"
        + "            </div>\n"
        + "            <div class=\"fm-overlay-body\" id=\"fmPreviewContent\"><"
        + "/div>\n"
        + "        </div>\n"
        + "    </div>\n"
        + "\n"
        + "    <!-- 文件编辑器浮层 -->\n"
        + "    <div id=\"fmEditor\" class=\"fm-overlay\" onclick=\"if(event.tar"
        + "get===this)closeFmEditor()\">\n"
        + "        <div class=\"fm-overlay-panel fm-editor-panel\">\n"
        + "            <div class=\"fm-overlay-header\">\n"
        + "                <span id=\"fmEditorTitle\">编辑文件</span>\n"
        + "                <button class=\"fm-overlay-close\" onclick=\"closeFm"
        + "Editor()\">✕</button>\n"
        + "            </div>\n"
        + "            <div class=\"fm-overlay-body\" style=\"align-items:stret"
        + "ch\">\n"
        + "                <input type=\"hidden\" id=\"fmEditorPath\">\n"
        + "                <textarea id=\"fmEditorTextarea\" class=\"fm-editor-"
        + "textarea\" placeholder=\"文件内容...\" spellcheck=\"false\"></textarea>\n"
        + "            </div>\n"
        + "            <div class=\"fm-editor-footer\">\n"
        + "                <button class=\"btn-neon small secondary\" id=\"fmIr"
        + "RunBtn\" onclick=\"executeIrFile(document.getElementById('fmEditorPa"
        + "th').value, document.getElementById('fmEditorTitle').textContent)\" "
        + "style=\"display:none\">▶ 执行 IR</button>\n"
        + "                <button class=\"btn-neon small secondary\" onclick=\"c"
        + "loseFmEditor()\">取消</button>\n"
        + "                <button class=\"btn-neon small\" onclick=\"saveFile("
        + ")\">💾 保存</button>\n"
        + "            </div>\n"
        + "        </div>\n"
        + "    </div>\n"
        + "\n"
        + "    <!-- 重命名对话框 -->\n"
        + "    <div id=\"fmRenameDialog\" class=\"fm-dialog\" onclick=\"if(even"
        + "t.target===this)closeFmRename()\">\n"
        + "        <div class=\"fm-dialog-panel\">\n"
        + "            <h3>📝 重命名 / 移动</h3>\n"
        + "            <input type=\"hidden\" id=\"fmRenameOldPath\">\n"
        + "            <input type=\"text\" id=\"fmRenameNewName\" placeholder="
        + "\"输入新名称或路径\" onkeydown=\"if(event.key==='Enter')doRename()\">\n"
        + "            <div class=\"fm-dialog-btns\">\n"
        + "                <button class=\"btn-neon small secondary\" onclick=\"c"
        + "loseFmRename()\">取消</button>\n"
        + "                <button class=\"btn-neon small\" onclick=\"doRename("
        + ")\">确定</button>\n"
        + "            </div>\n"
        + "        </div>\n"
        + "    </div>\n"
        + "\n"
        + "    <!-- 新建对话框 -->\n"
        + "    <div id=\"fmCreateDialog\" class=\"fm-dialog\" onclick=\"if(even"
        + "t.target===this)closeFmCreate()\">\n"
        + "        <div class=\"fm-dialog-panel\">\n"
        + "            <h3>➕ 新建</h3>\n"
        + "            <input type=\"text\" id=\"fmCreateName\" placeholder=\"输"
        + "入名称\" onkeydown=\"if(event.key==='Enter')doCreate()\">\n"
        + "            <label><input type=\"checkbox\" id=\"fmCreateIsDir\"> 创建"
        + "为目录</label>\n"
        + "            <div class=\"fm-dialog-btns\">\n"
        + "                <button class=\"btn-neon small secondary\" onclick=\"c"
        + "loseFmCreate()\">取消</button>\n"
        + "                <button class=\"btn-neon small\" onclick=\"doCreate("
        + ")\">创建</button>\n"
        + "            </div>\n"
        + "        </div>\n"
        + "    </div>\n"
        + "\n"
        + "</body>\n"
        + "</html>\n"
        + ""
    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;    ;

    // ==================== 文件管理 ====================

    private void handleFileRoot(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
            sendJson(ex, 200, "{\"root\":\"" + esc(fileManager.getSfwRootPath()) + "\"}");
    }

    private void handleFileList(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        String path = getQueryParam(ex, "path");
        if (path == null) path = "";
        try {
            java.util.List<FileManager.FileInfo> list = fileManager.listDirectory(path);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",");
                FileManager.FileInfo fi = list.get(i);
                sb.append("{\"name\":\"").append(esc(fi.name))
                  .append("\",\"path\":\"").append(esc(fi.path))
                  .append("\",\"isDir\":").append(fi.isDir)
                  .append(",\"size\":").append(fi.size)
                  .append(",\"lastModified\":").append(fi.lastModified)
                  .append(",\"extension\":\"").append(esc(fi.extension))
                  .append("\",\"mimeType\":\"").append(esc(fi.mimeType))
                  .append("\",\"isImage\":").append(fi.isImage)
                  .append(",\"isMedia\":").append(fi.isMedia)
                  .append(",\"isAudio\":").append(fi.isAudio)
                  .append(",\"isIr\":").append(fi.isIr)
                  .append(",\"isText\":").append(fi.isText)
                  .append(",\"isCode\":").append(fi.isCode)
                  .append(",\"readable\":").append(fi.readable)
                  .append(",\"writable\":").append(fi.writable)
                  .append("}");
            }
            sb.append("]");
            sendJson(ex, 200, sb.toString());
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFileRead(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        String path = getQueryParam(ex, "path");
        if (path == null) { sendJson(ex, 400, "{\"error\":\"Missing path\"}"); return; }
        try {
            String content = fileManager.readFile(path);
            sendJson(ex, 200, "{\"content\":\"" + esc(content) + "\",\"path\":\"" + esc(path) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFileWrite(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        Map<String,String> p = parsePostParams(ex);
        String path = p.get("path");
        String content = p.get("content");
        String create = p.get("create");
        if (path == null || content == null) { sendJson(ex, 400, "{\"error\":\"Missing path or content\"}"); return; }
        try {
            fileManager.writeFile(path, content, "true".equals(create));
            sendJson(ex, 200, "{\"success\":true}");
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFileDelete(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        Map<String,String> p = parsePostParams(ex);
        String path = p.get("path");
        if (path == null) { sendJson(ex, 400, "{\"error\":\"Missing path\"}"); return; }
        try {
            fileManager.deleteFile(path);
            sendJson(ex, 200, "{\"success\":true}");
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFileCreate(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        Map<String,String> p = parsePostParams(ex);
        String path = p.get("path");
        String isDir = p.get("isDir");
        if (path == null) { sendJson(ex, 400, "{\"error\":\"Missing path\"}"); return; }
        try {
            fileManager.createFile(path, "true".equals(isDir));
            sendJson(ex, 200, "{\"success\":true}");
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFileMove(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        Map<String,String> p = parsePostParams(ex);
        String oldPath = p.get("oldPath");
        String newPath = p.get("newPath");
        if (oldPath == null || newPath == null) { sendJson(ex, 400, "{\"error\":\"Missing oldPath or newPath\"}"); return; }
        try {
            fileManager.moveFile(oldPath, newPath);
            sendJson(ex, 200, "{\"success\":true}");
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFileUpload(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.contains("multipart/form-data")) {
            sendJson(ex, 400, "{\"error\":\"Expected multipart/form-data\"}"); return;
        }
        // Parse multipart boundary
        String boundary = null;
        for (String part : ct.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                boundary = part.substring("boundary=".length());
                if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                break;
            }
        }
        if (boundary == null) { sendJson(ex, 400, "{\"error\":\"No boundary\"}"); return; }
        // 直接读取原始字节流，不经过 String 编解码
        byte[] body = readLimitedBytes(ex.getRequestBody(), MAX_FILE_UPLOAD_BYTES);
        
        // 在字节层面搜索 boundary
        byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] crlfcrlf = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] crlf = "\r\n".getBytes(StandardCharsets.ISO_8859_1);
        
        int idx = indexOfBytes(body, boundaryBytes, 0);
        if (idx < 0) { sendJson(ex, 400, "{\"error\":\"Invalid multipart\"}"); return; }
        
        String dirPath = "";
        String fileName = "uploaded_file";
        // 在字节层面找 Content-Disposition header
        int dIdx = indexOfBytes(body, "Content-Disposition:".getBytes(StandardCharsets.ISO_8859_1), idx);
        if (dIdx >= 0) {
            int endHdr = indexOfBytes(body, crlfcrlf, dIdx);
            if (endHdr < 0) {
                endHdr = indexOfBytes(body, "\n\n".getBytes(StandardCharsets.ISO_8859_1), dIdx);
            }
            if (endHdr >= 0) {
                String hdr = new String(body, dIdx, endHdr - dIdx, StandardCharsets.ISO_8859_1);
                // Extract filename
                java.util.regex.Matcher fm = java.util.regex.Pattern.compile("filename=\"([^\"]*)\"").matcher(hdr);
                if (fm.find()) {
                    fileName = fm.group(1);
                    // 防御路径穿越：拒绝包含 ../ 或 ..\\ 的文件名
                    if (fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                        sendJson(ex, 400, "{\"error\":\"Invalid filename\"}"); return;
                    }
                }
            }
        }
        // Get dirPath from query
        dirPath = getQueryParam(ex, "dirPath");
        if (dirPath == null) dirPath = "";
        
        // Extract file content (between headers end and next boundary)
        int hdrEnd = indexOfBytes(body, crlfcrlf, idx);
        if (hdrEnd < 0) hdrEnd = indexOfBytes(body, "\n\n".getBytes(StandardCharsets.ISO_8859_1), idx);
        if (hdrEnd < 0) { sendJson(ex, 400, "{\"error\":\"Invalid multipart headers\"}"); return; }
        hdrEnd += 4; // skip \r\n\r\n
        
        int nextBoundary = indexOfBytes(body, boundaryBytes, hdrEnd);
        byte[] fileData;
        if (nextBoundary >= 0) {
            int endData = nextBoundary;
            // Trim trailing \r\n before boundary
            if (endData >= 2 && body[endData - 2] == '\r' && body[endData - 1] == '\n') endData -= 2;
            else if (endData >= 1 && body[endData - 1] == '\n') endData -= 1;
            fileData = new byte[endData - hdrEnd];
            System.arraycopy(body, hdrEnd, fileData, 0, fileData.length);
        } else {
            sendJson(ex, 400, "{\"error\":\"File content too large or malformed\"}"); return;
        }
        
        try {
            fileManager.uploadFile(dirPath, fileName, new java.io.ByteArrayInputStream(fileData));
            sendJson(ex, 200, "{\"success\":true,\"name\":\"" + esc(fileName) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFilePreview(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        String path = getQueryParam(ex, "path");
        if (path == null) { sendJson(ex, 400, "{\"error\":\"Missing path\"}"); return; }
        try {
            byte[] data = fileManager.readFileBytes(path);
            // 直接根据扩展名推断 MIME 类型，避免遍历目录
            String mime = "application/octet-stream";
            String nameLow = path.toLowerCase();
            if (nameLow.endsWith(".png")) mime = "image/png";
            else if (nameLow.endsWith(".jpg") || nameLow.endsWith(".jpeg")) mime = "image/jpeg";
            else if (nameLow.endsWith(".gif")) mime = "image/gif";
            else if (nameLow.endsWith(".bmp")) mime = "image/bmp";
            else if (nameLow.endsWith(".svg")) mime = "image/svg+xml";
            else if (nameLow.endsWith(".webp")) mime = "image/webp";
            else if (nameLow.endsWith(".ico")) mime = "image/x-icon";
            else if (nameLow.endsWith(".mp3")) mime = "audio/mpeg";
            else if (nameLow.endsWith(".wav")) mime = "audio/wav";
            else if (nameLow.endsWith(".ogg")) mime = "audio/ogg";
            else if (nameLow.endsWith(".flac")) mime = "audio/flac";
            else if (nameLow.endsWith(".aac")) mime = "audio/aac";
            else if (nameLow.endsWith(".mp4")) mime = "video/mp4";
            else if (nameLow.endsWith(".webm")) mime = "video/webm";
            else if (nameLow.endsWith(".mkv")) mime = "video/x-matroska";
            else if (nameLow.endsWith(".avi")) mime = "video/x-msvideo";
            else if (nameLow.endsWith(".mov")) mime = "video/quicktime";
            else if (nameLow.endsWith(".pdf")) mime = "application/pdf";
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
            ex.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + new java.io.File(path).getName() + "\"");
            ex.sendResponseHeaders(200, data.length);
            OutputStream os = ex.getResponseBody(); os.write(data); os.close();
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private void handleFileIrExecute(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        if (requireAuth(ex) == null) return;
        Map<String,String> p = parsePostParams(ex);
        String path = p.get("path");
        if (path == null) { sendJson(ex, 400, "{\"error\":\"Missing path\"}"); return; }
        try {
            String cmd = fileManager.buildIrCommand(path);
            // 直接调用 SFW 的 /ir 命令机制，不经过 CommandHandler 的额外线程包装
            SairCons.runner(true, cmd);
            sendJson(ex, 200, "{\"success\":true,\"command\":\"" + esc(cmd) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"" + esc(e.getMessage()) + "\"}");
        }
    }

    private String getQueryParam(HttpExchange ex, String key) {
        String query = ex.getRequestURI().getQuery();
        if (query == null) return null;
        for (String kv : query.split("&")) {
            String[] parts = kv.split("=", 2);
            if (parts.length == 2 && key.equals(parts[0])) {
                try { return java.net.URLDecoder.decode(parts[1], "UTF-8"); }
                catch (Exception e) { return parts[1]; }
            }
        }
        return null;
    }


// ==================== 会话 ====================
    private String createSession(String ip, String ua) { String id = UUID.randomUUID().toString(); SessionInfo si = new SessionInfo(id, ip, System.currentTimeMillis(), ua != null ? ua : ""); sessions.put(id, si); sessionManager.registerSession(id, ip, ua); return id; }
    private void destroySession(HttpExchange ex) {
        String ck = ex.getRequestHeaders().getFirst("Cookie"); if (ck == null) return;
        for (String c : ck.split(";")) { c = c.trim(); if (c.startsWith(SESSION_COOKIE + "=")) {
            String sid = c.substring(SESSION_COOKIE.length() + 1);
            SessionInfo s = sessions.remove(sid);
            ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict" + (useHttps ? "; Secure" : ""));
            return;
        }}
    }
    private SessionInfo getSession(HttpExchange ex) {
        String ck = ex.getRequestHeaders().getFirst("Cookie"); if (ck == null) return null;
        for (String c : ck.split(";")) { c = c.trim(); if (c.startsWith(SESSION_COOKIE + "=")) {
            String sid = c.substring(SESSION_COOKIE.length() + 1); SessionInfo s = sessions.get(sid);
            if (s != null && !s.isExpired(config.getSessionTimeoutMinutes())) { s.touch(); return s; }
            else if (s != null) sessions.remove(sid);
        }} return null;
    }
    private void cleanExpiredSessions() { int t = config.getSessionTimeoutMinutes(); sessions.entrySet().removeIf(e -> e.getValue().isExpired(t)); loginAttempts.entrySet().removeIf(e -> { LoginAttempt a = e.getValue(); if (a.lockTime == 0) return false; if (System.currentTimeMillis() - a.lockTime > LOGIN_LOCKOUT_MS) { return true; } return false; }); }
    private SessionInfo requireAuth(HttpExchange ex) throws IOException { SessionInfo s = getSession(ex); if (s == null) sendJson(ex, 401, "{\"error\":\"Authentication required\"}"); return s; }

    // ==================== 工具 ====================
    private void sendJson(HttpExchange ex, int st, String json) throws IOException {
        byte[] d = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(st, d.length); OutputStream os = ex.getResponseBody(); os.write(d); os.close();
    }
    private void sendMethodNotAllowed(HttpExchange ex) throws IOException { sendJson(ex, 405, "{\"error\":\"Method not allowed\"}"); }
    private void sendNotFound(HttpExchange ex) throws IOException { sendJson(ex, 404, "{\"error\":\"Not found\"}"); }
    private Map<String,String> parsePostParams(HttpExchange ex) throws IOException {
        Map<String,String> m = new HashMap<>();
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        byte[] body = readLimitedBytes(ex.getRequestBody(), MAX_POST_BODY_BYTES); String raw = new String(body, StandardCharsets.UTF_8);
        if (ct != null && ct.contains("application/json")) { parseSimpleJson(raw, m); }
        else { for (String p : raw.split("&")) { int i = p.indexOf("="); if (i > 0) m.put(URLDecoder.decode(p.substring(0,i),"UTF-8"), URLDecoder.decode(p.substring(i+1),"UTF-8")); } }
        return m;
    }
    private void parseSimpleJson(String j, Map<String,String> m) {
        j = j.trim(); if (!j.startsWith("{") || !j.endsWith("}")) return; j = j.substring(1, j.length()-1);
        StringBuilder k = null, v = null; boolean ik = false, iv = false, is = false, es = false;
        for (int i = 0; i < j.length(); i++) { char c = j.charAt(i);
            if (es) { if (ik && k != null) k.append(c); if (iv && v != null) v.append(c); es = false; continue; }
            if (c == '\\') { es = true; continue; }
            if (c == '"') { is = !is; if (is) { if (!ik && !iv) { ik = true; k = new StringBuilder(); } }
                else { if (ik) ik = false; else if (iv) { iv = false; if (k != null && v != null) m.put(k.toString(), v.toString()); k = null; v = null; } } continue; }
            if (is) { if (ik && k != null) k.append(c); if (iv && v != null) v.append(c); continue; }
            if (c == ':') { iv = true; v = new StringBuilder(); }
        }
    }
    private String esc(String s) { if (s == null) return ""; return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t").replace("\b","\\b").replace("\f","\\f"); }
    private byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] d = new byte[8192]; int n; while ((n = is.read(d)) != -1) b.write(d,0,n); return b.toByteArray(); }
    private byte[] readLimitedBytes(InputStream is, int maxBytes) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] d = new byte[8192]; int total = 0; int n; while ((n = is.read(d, 0, Math.min(d.length, maxBytes - total))) != -1) { b.write(d, 0, n); total += n; if (total >= maxBytes) break; } return b.toByteArray(); }
    private byte[] readFileBytes(File f) throws IOException { try (FileInputStream fis = new FileInputStream(f)) { return readAllBytes(fis); } }
    /** 在字节数组中搜索子数组，返回首次出现位置，-1 表示未找到 */
    private static int indexOfBytes(byte[] haystack, byte[] needle, int fromIndex) {
        if (needle.length == 0) return fromIndex;
        outer: for (int i = fromIndex; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
    private String getContentType(String p) {
        if (p.endsWith(".html")) return "text/html; charset=UTF-8"; if (p.endsWith(".css")) return "text/css; charset=UTF-8";
        if (p.endsWith(".js")) return "application/javascript; charset=UTF-8"; if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png"; if (p.endsWith(".svg")) return "image/svg+xml"; return "application/octet-stream";
    }
    private static class SessionInfo { final String id; final String ip; final long loginTime; final String userAgent; long lastAccess; SessionInfo(String id, String ip, long loginTime, String userAgent) { this.id = id; this.ip = ip; this.loginTime = loginTime; this.userAgent = userAgent; this.lastAccess = System.currentTimeMillis(); } void touch() { this.lastAccess = System.currentTimeMillis(); } boolean isExpired(int t) { return System.currentTimeMillis() - lastAccess > t * 60 * 1000L; } }
    private static class LoginAttempt { int count; long lockTime; void recordFailure() { count++; if (count >= MAX_LOGIN_ATTEMPTS) lockTime = System.currentTimeMillis(); } boolean isLocked() { if (count < MAX_LOGIN_ATTEMPTS) return false; if (System.currentTimeMillis() - lockTime > LOGIN_LOCKOUT_MS) { count = 0; lockTime = 0; return false; } return true; } }
}
