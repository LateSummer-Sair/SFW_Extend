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

/**
 * SFW Web 服务器 — HTTP/HTTPS + SSE + 硬编码前端兜底
 */
public class WebServer {

    private HttpsServer httpsServer;
    private HttpServer httpServer;
    private final ConfigManager config;
    private final PasswordManager passwordManager;
    private final CommandHandler commandHandler;
    private final SfwConsoleCapture consoleCapture;
    private volatile boolean running = false;

    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sessionCleaner = Executors.newSingleThreadScheduledExecutor();
    private static final String SESSION_COOKIE = "SFW_WEB_SESSION";
    private final AtomicLong sseIdCounter = new AtomicLong(0);
    private final Map<String, LoginAttempt> loginAttempts = new ConcurrentHashMap<>();
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final long LOGIN_LOCKOUT_MS = 300000;

    public WebServer(ConfigManager config, PasswordManager passwordManager,
                     CommandHandler commandHandler, SfwConsoleCapture consoleCapture) {
        this.config = config;
        this.passwordManager = passwordManager;
        this.commandHandler = commandHandler;
        this.consoleCapture = consoleCapture;
    }

    public boolean start() {
        if (running) return true;
        try {
            int port = config.getPort();
            boolean useHttps = config.isHttpsEnabled() && config.isHttpsReady();
            if (useHttps) {
                SSLContext ctx = loadUserSSLContext();
                httpsServer = HttpsServer.create(new InetSocketAddress(port), 0);
                httpsServer.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                    public void configure(HttpsParameters p) {
                        SSLContext c = getSSLContext(); SSLEngine e = c.createSSLEngine();
                        p.setNeedClientAuth(false); p.setCipherSuites(e.getEnabledCipherSuites());
                        p.setProtocols(e.getEnabledProtocols()); p.setSSLParameters(c.getDefaultSSLParameters());
                    }
                });
                setupEndpoints(httpsServer);
                httpsServer.setExecutor(Executors.newFixedThreadPool(10));
                httpsServer.start();
            } else {
                httpServer = HttpServer.create(new InetSocketAddress(port), 0);
                setupEndpoints(httpServer);
                httpServer.setExecutor(Executors.newFixedThreadPool(10));
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
        s.createContext("/", this::handleStaticFiles);
    }

    private SSLContext loadUserSSLContext() throws Exception {
        // 加载 PEM 证书
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(config.getCertFile())) {
            cert = (X509Certificate) cf.generateCertificate(fis);
        }

        // 加载私钥（支持 PKCS8 和 PKCS1 两种格式）
        String keyPem = new String(Files.readAllBytes(java.nio.file.Paths.get(config.getKeyFile())), StandardCharsets.UTF_8);
        PrivateKey privateKey;
        if (keyPem.contains("BEGIN PRIVATE KEY")) {
            // PKCS8 格式
            privateKey = loadPkcs8Key(keyPem);
        } else if (keyPem.contains("BEGIN RSA PRIVATE KEY")) {
            // PKCS1 格式 → 转为 PKCS8
            privateKey = loadPkcs1Key(keyPem);
        } else if (keyPem.contains("BEGIN EC PRIVATE KEY")) {
            privateKey = loadEcPkcs1Key(keyPem);
        } else {
            throw new IllegalArgumentException("Unsupported key format. Expected PKCS8 or PKCS1 PEM.");
        }

        // 构建内存 KeyStore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("sfwweb", privateKey, new char[0], new java.security.cert.Certificate[]{cert});

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

    /** RSA PKCS1 → PKCS8 DER 包装 */
    private static byte[] rsaPkcs1ToPkcs8(byte[] pkcs1) throws Exception {
        // OID: 1.2.840.113549.1.1.1 (rsaEncryption)
        byte[] rsaOid = {0x06, 0x09, 0x2A, (byte)0x86, 0x48, (byte)0x86, (byte)0xF7, 0x0D, 0x01, 0x01, 0x01};
        byte[] algId = derSeq(concat(rsaOid, new byte[]{0x05, 0x00})); // SEQUENCE { OID, NULL }
        byte[] octStr = derTag(0x04, pkcs1); // OCTET STRING
        byte[] inner = concat(new byte[]{0x02, 0x01, 0x00}, algId, octStr); // INTEGER(0) + AlgId + OCTET
        return derTag(0x30, inner); // SEQUENCE
    }

    /** EC PKCS1 → PKCS8 DER 包装 */
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
            try { Thread.sleep(300); } catch (InterruptedException ig) {}
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
        sendJson(ex, passwordManager.setPassword(pw) ? 200 : 500, passwordManager.setPassword(pw) ? "{\"success\":true}" : "{\"error\":\"Failed\"}");
    }
    private void handleLogin(HttpExchange ex) throws IOException {
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
        String sid = createSession();
        ex.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + sid + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + (config.getSessionTimeoutMinutes() * 60));
        sendJson(ex, 200, "{\"success\":true}");
    }
    private void handleLogout(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = getSession(ex); if (s != null) sessions.remove(s.id);
        sendJson(ex, 200, "{\"success\":true}");
    }
    private void handleChangePassword(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
        Map<String,String> p = parsePostParams(ex);
        String o = p.get("old_password"), n = p.get("new_password");
        if (o == null || n == null || n.length() < 6) { sendJson(ex, 400, "{\"error\":\"Invalid\"}"); return; }
        if (!passwordManager.verifyPassword(o)) { sendJson(ex, 401, "{\"error\":\"Wrong password\"}"); return; }
        sendJson(ex, passwordManager.setPassword(n) ? 200 : 500, passwordManager.setPassword(n) ? "{\"success\":true}" : "{\"error\":\"Failed\"}");
    }

    // ==================== 命令 ====================
    private void handleCommandExecute(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        SessionInfo s = requireAuth(ex); if (s == null) return;
        Map<String,String> p = parsePostParams(ex);
        String c = p.get("command");
        if (c == null || c.trim().isEmpty()) { sendJson(ex, 400, "{\"error\":\"Command required\"}"); return; }
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
                    try { String t = l.text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
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
        // 先加入订阅者（此后新输出会通过 onNewLine 广播），再发送历史快照
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

    // ==================== 控制台 ====================
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

    // ==================== 静态文件（含硬编码兜底） ====================
    private void handleStaticFiles(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { sendMethodNotAllowed(ex); return; }
        String path = ex.getRequestURI().getPath();
        if ("/".equals(path)) path = "/index.html";
        path = path.replace("..", "").replace("//", "/");
        if (path.contains("\\")) { sendNotFound(ex); return; }

        byte[] data = null;
        String webRoot = config.getWebRoot();

        // 1) 外部目录
        if (webRoot != null && !webRoot.isEmpty()) {
            File f = new File(webRoot + path);
            if (f.exists() && f.isFile()) data = readFileBytes(f);
        }
        // 2) JAR 多种读取方式
        if (data == null) data = readJarResource("web" + path.replace('\\', '/'));
        // 3) 硬编码兜底 HTML — 100% 可靠
        if (data == null && "/index.html".equals(path)) data = FALLBACK_HTML.getBytes(StandardCharsets.UTF_8);

        if (data == null) { sendNotFound(ex); return; }

        ex.getResponseHeaders().set("Content-Type", getContentType(path));
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.getResponseHeaders().set("X-Frame-Options", "DENY");
        ex.sendResponseHeaders(200, data.length);
        OutputStream os = ex.getResponseBody(); os.write(data); os.close();
    }

    /** 尝试多种方式从 JAR 读取资源 */
    private byte[] readJarResource(String entryPath) {
        // 1) ProtectionDomain → JarFile
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
        // 2) getResource URL → JarURLConnection
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
        return null;
    }

    // ==================== 硬编码兜底 HTML（不会被类加载器影响） ====================
    private static final String FALLBACK_HTML =
        "<!DOCTYPE html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'><title>SFW Web Console</title>" +
        "<style>" +
        ":root{--bg:#0C0C0C;--fg:#00FF41;--bd:#1A1A2E;--ac:#E94560;--ff:'Consolas',monospace;--fs:14px}" +
        "*,*::before,*::after{margin:0;padding:0;box-sizing:border-box}" +
        "html,body{width:100%;height:100%;overflow:hidden;font-family:var(--ff);font-size:var(--fs);background:#000;color:var(--fg)}" +
        "@keyframes spin{to{transform:rotate(360deg)}}@keyframes pulse{0%,100%{text-shadow:0 0 30px rgba(0,255,65,0.4)}50%{text-shadow:0 0 60px rgba(0,255,65,0.4)}}" +
        ".screen{position:fixed;inset:0;display:none;z-index:1}.screen.on{display:flex}" +
        "#loginScreen{justify-content:center;align-items:center;background:rgba(0,0,0,0.85)}" +
        ".login-box{width:420px;padding:50px 40px 40px;background:rgba(12,12,12,0.95);border:1px solid var(--bd);border-radius:4px;box-shadow:0 0 30px rgba(0,255,65,0.08)}" +
        ".logo{text-align:center;font-size:48px;font-weight:bold;letter-spacing:4px;margin-bottom:8px}" +
        ".logo span:first-child,.logo span:last-child{color:var(--ac)}.logo span:nth-child(2){color:var(--fg);animation:pulse 2s ease-in-out infinite}" +
        ".sub{text-align:center;font-size:12px;color:#666;letter-spacing:8px;margin-bottom:35px}" +
        ".ftitle{text-align:center;font-size:14px;color:var(--fg);letter-spacing:6px;margin-bottom:12px}" +
        ".fdesc{text-align:center;font-size:12px;color:#666;margin-bottom:24px}" +
        "input[type='password'],input[type='text'],input[type='number']{width:100%;padding:14px 16px;margin-bottom:18px;background:rgba(0,0,0,0.6);border:1px solid #333;border-radius:3px;color:var(--fg);font-family:var(--ff);font-size:14px;outline:none;box-sizing:border-box}" +
        "input:focus{border-color:var(--fg);box-shadow:0 0 15px rgba(0,255,65,0.4)}" +
        "button{padding:14px;background:transparent;border:1px solid var(--fg);border-radius:3px;color:var(--fg);font-family:var(--ff);font-size:13px;font-weight:bold;letter-spacing:4px;cursor:pointer;text-transform:uppercase}" +
        "button:hover{background:rgba(0,255,65,0.08);box-shadow:0 0 30px rgba(0,255,65,0.4)}" +
        "button.sm{padding:8px 20px;font-size:11px;letter-spacing:2px;width:auto}" +
        "button.ac{border-color:var(--ac);color:var(--ac)}button.ac:hover{box-shadow:0 0 20px rgba(233,69,96,0.4);background:rgba(233,69,96,0.08)}" +
        ".err{color:var(--ac);font-size:12px;text-align:center;margin-top:12px;min-height:18px}" +
        "#mainScreen{flex-direction:column;background:var(--bg)}" +
        ".tb{display:flex;align-items:center;justify-content:space-between;height:44px;padding:0 16px;background:#0A0A0A;border-bottom:1px solid var(--bd)}" +
        ".tb-l{display:flex;align-items:center;gap:8px}.tb-t{font-size:13px;font-weight:bold;color:var(--fg);letter-spacing:2px}" +
        ".tb-btn{width:34px;height:34px;background:transparent;border:1px solid transparent;border-radius:3px;color:#555;cursor:pointer;font-size:16px}" +
        ".tb-btn:hover{color:var(--fg);border-color:#333}" +
        ".cw{flex:1;margin:8px 12px;overflow:hidden}.co{width:100%;height:100%;padding:16px 20px;overflow-y:auto;font-family:var(--ff);font-size:var(--fs);line-height:1.5;white-space:pre-wrap;word-break:break-all;background:rgba(0,0,0,0.3);border:1px solid var(--bd);border-radius:3px}" +
        ".ic{display:flex;align-items:center;margin:4px 12px 8px;padding:0 12px;height:42px;background:#0A0A0A;border:1px solid var(--bd);border-radius:3px}" +
        ".ip{color:var(--ac);font-weight:bold;margin-right:10px}#ci{flex:1;background:transparent;border:none;outline:none;color:var(--fg);font-family:var(--ff);font-size:var(--fs)}" +
        ".ih{font-size:10px;color:#333;text-align:right;padding:0 8px 4px}" +
        ".ov{position:fixed;inset:0;background:rgba(0,0,0,0.7);z-index:100;display:none;justify-content:center;align-items:center}" +
        ".ov.on{display:flex}.pn{width:560px;max-height:85vh;background:#0E0E0E;border:1px solid var(--bd);border-radius:4px;overflow-y:auto;padding:20px}" +
        ".sec{margin-bottom:20px;padding-bottom:16px;border-bottom:1px solid #111}.sec h3{font-size:13px;color:var(--fg);margin-bottom:12px;letter-spacing:2px}" +
        ".row{display:flex;align-items:center;margin-bottom:10px;gap:10px}.row label{min-width:90px;font-size:12px;color:#666}" +
        ".row input[type='text'],.row input[type='password'],.row input[type='number']{flex:1;padding:8px 12px;background:#0A0A0A;border:1px solid #222;border-radius:3px;color:var(--fg);font-family:var(--ff);font-size:12px;margin-bottom:0}" +
        ".row input[type='color']{width:36px;height:30px;padding:0;border:1px solid #222;border-radius:3px;background:transparent;cursor:pointer;margin-bottom:0}" +
        ".row input[type='range']{flex:1;accent-color:var(--fg);margin-bottom:0}" +
        "</style></head><body>" +
        "<div id='loginScreen' class='screen on'><div class='login-box'>" +
        "<div class='logo'><span>{</span><span>SFW</span><span>}</span></div><div class='sub'>Web Remote Console</div>" +
        "<div id='setupForm' style='display:none'><div class='ftitle'>INITIAL SETUP</div><p class='fdesc'>首次使用，请设置登录密码（至少6位）</p>" +
        "<input type='password' id='sp' placeholder='设置密码' autocomplete='new-password'>" +
        "<input type='password' id='sc' placeholder='确认密码' autocomplete='new-password'>" +
        "<button onclick='doSetup()' style='width:100%'>SET PASSWORD</button><div id='se' class='err'></div></div>" +
        "<div id='loginForm' style='display:none'><div class='ftitle'>AUTHENTICATION</div><p class='fdesc'>请输入密码以访问 SFW 控制台</p>" +
        "<input type='password' id='lp' placeholder='输入密码' autocomplete='current-password'>" +
        "<button onclick='doLogin()' style='width:100%'>LOGIN</button><div id='le' class='err'></div></div>" +
        "<div id='ld' style='text-align:center;padding:20px;display:none'><div style='display:inline-block;width:32px;height:32px;border:2px solid #333;border-top-color:var(--fg);border-radius:50%;animation:spin 0.8s linear infinite'></div><p style='font-size:12px;color:#666;margin-top:10px'>Connecting...</p></div>" +
        "</div></div>" +
        "<div id='mainScreen' class='screen'><header class='tb'><div class='tb-l'><span style='font-size:18px'>⚡</span><span class='tb-t'>SFW Web Console</span><span style='font-size:10px;color:#555;background:#111;padding:2px 6px;border-radius:3px;margin-left:6px'>v1.0</span></div>" +
        "<div style='display:flex;gap:4px'><button class='tb-btn' onclick='toggleSettings()' title='设置'>⚙</button><button class='tb-btn' onclick='clearConsole()' title='清屏'>🗑</button><button class='tb-btn' onclick='doLogout()' title='退出'>🚪</button></div></header>" +
        "<div class='cw'><div id='co' class='co'></div></div>" +
        "<div class='ic'><span class='ip'>&gt;</span><input type='text' id='ci' placeholder='输入 SFW 命令... (如 /help, /list)' autocomplete='off' spellcheck='false'><button class='tb-btn' onclick='sendCommand()' style='width:28px;height:28px;font-size:12px'>▶</button></div>" +
        "<div class='ih'>Enter 发送 · ↑↓ 历史命令 · Ctrl+L 清屏</div></div>" +
        "<div id='settingsOverlay' class='ov' onclick='if(event.target===this)toggleSettings()'><div class='pn'>" +
        "<div style='display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;border-bottom:1px solid var(--bd);padding-bottom:12px'><h2 style='font-size:14px;color:var(--fg);letter-spacing:4px'>SETTINGS</h2><button class='tb-btn' onclick='toggleSettings()' style='font-size:18px'>✕</button></div>" +
        "<div class='sec'><h3>🔒 密码管理</h3><div class='row'><label>当前密码</label><input type='password' id='sop'></div><div class='row'><label>新密码</label><input type='password' id='snp'></div><button class='sm' onclick='changePassword()'>更新密码</button><span id='pm' style='font-size:11px;margin-left:10px'></span></div>" +
        "<div class='sec'><h3>🎨 主题定制</h3>" +
        "<div class='row'><label>背景</label><input type='color' id='tbg' onchange='al()'><input type='text' id='tbgT' style='width:80px' onchange='scf(\"tbg\")'></div>" +
        "<div class='row'><label>文字</label><input type='color' id='tfg' onchange='al()'><input type='text' id='tfgT' style='width:80px' onchange='scf(\"tfg\")'></div>" +
        "<div class='row'><label>边框</label><input type='color' id='tbd' onchange='al()'><input type='text' id='tbdT' style='width:80px' onchange='scf(\"tbd\")'></div>" +
        "<div class='row'><label>强调</label><input type='color' id='tac' onchange='al()'><input type='text' id='tacT' style='width:80px' onchange='scf(\"tac\")'></div>" +
        "<div class='row'><label>字号</label><input type='range' id='tfs' min='10' max='24' value='14' oninput='al()'><span id='fsv' style='font-size:11px;color:#666'>14px</span></div>" +
        "<div class='row'><label>透明度</label><input type='range' id='top' min='0.3' max='1' step='0.05' value='1' oninput='al()'><span id='opv' style='font-size:11px;color:#666'>1.0</span></div>" +
        "<button class='sm' onclick='saveTheme()'>保存主题</button><button class='sm ac' style='margin-left:8px' onclick='resetTheme()'>恢复默认</button><span id='tm' style='font-size:11px;margin-left:10px'></span></div>" +
        "<div class='sec'><h3>⚙ 服务器</h3><div class='row'><label>超时(分)</label><input type='number' id='sto' value='30'></div><div class='row'><label>最大行数</label><input type='number' id='sml' value='5000'></div><button class='sm' onclick='saveServerConfig()'>保存</button><span id='smg' style='font-size:11px;margin-left:10px'></span></div>" +
        "<div class='sec'><h3>🌐 网络与安全</h3>" +
        "<div class='row'><label>HTTP 端口</label><input type='number' id='sport' value='8080' min='1' max='65535'></div>" +
        "<div class='row'><label>HTTPS</label><select id='shts' onchange='htsToggle()'><option value='false'>关闭</option><option value='true'>启用</option></select><span style='font-size:10px;color:#555'>需 PEM 证书+私钥，自动重启</span></div>" +
        "<div id='ksr' class='row' style='display:none'><label>证书文件(PEM)</label><input type='text' id='scf' placeholder='C:\\cert.pem'></div>" +
        "<div id='ksp' class='row' style='display:none'><label>私钥文件(KEY)</label><input type='password' id='skf' placeholder='输入私钥文件路径'></div>" +
        "<div class='row'><label>Web 根目录</label><input type='text' id='swr' placeholder='留空=使用JAR内置页面'></div>" +
        "<button class='sm' onclick='saveNetworkConfig()'>保存网络配置</button><span id='nmg' style='font-size:11px;margin-left:10px'></span></div>" +
        "</div></div>" +
        "<script>" +
        "var ST={auth:false,th:{},ch:[],hi:-1,as:true,ss:null};" +
        "document.addEventListener('DOMContentLoaded',function(){checkAuth();document.addEventListener('keydown',hk);var o=G('co');o.addEventListener('scroll',function(){ST.as=o.scrollHeight-o.scrollTop-o.clientHeight<50})});" +
        "function G(id){return document.getElementById(id)}" +
        "function $(s,d){G(s).style.display=d}" +
        "async function checkAuth(){try{var r=await fetch('/api/auth/status');var d=await r.json();if(d.authenticated){ST.auth=true;$('loginScreen','none');$('mainScreen','flex');init()}else showLogin(d.passwordSet)}catch(e){showLogin(false)}}" +
        "function showLogin(ps){$('loginScreen','flex');$('mainScreen','none');$('ld','block');$('setupForm','none');$('loginForm','none');if(ps){$('ld','none');$('loginForm','block');G('lp').focus()}else{setTimeout(function(){$('ld','none');$('setupForm','block');G('sp').focus()},300)}}" +
        "async function doSetup(){var p=G('sp').value,c=G('sc').value,e=G('se');if(p.length<6){e.textContent='密码至少需要6位';return}if(p!==c){e.textContent='两次输入的密码不一致';return}try{var r=await fetch('/api/auth/setup',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:p})});var d=await r.json();if(d.success){G('lp').value=p;doLogin()}else e.textContent=d.error||'设置失败'}catch(x){e.textContent='网络错误'}}" +
        "async function doLogin(){var p=G('lp').value,e=G('le');if(!p){e.textContent='请输入密码';return}try{var r=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:p})});var d=await r.json();if(d.success){ST.auth=true;$('loginScreen','none');$('mainScreen','flex');init()}else{e.textContent=d.error||'密码错误';G('lp').value='';G('lp').focus()}}catch(x){e.textContent='网络错误'}}" +
        "async function doLogout(){try{await fetch('/api/auth/logout',{method:'POST'})}catch(e){}ST.auth=false;if(ST.ss){ST.ss.close();ST.ss=null}showLogin(false)}" +
        "function init(){loadTheme();loadHist();connectSSE();G('ci').focus()}" +
        "async function loadTheme(){try{var r=await fetch('/api/config/get');var d=await r.json();ST.th=d;applyCfg(d);popSett(d)}catch(e){}}" +
        "function applyCfg(c){if(c['theme.bg_color'])sv('--bg',c['theme.bg_color']);if(c['theme.font_color'])sv('--fg',c['theme.font_color']);if(c['theme.border_color'])sv('--bd',c['theme.border_color']);if(c['theme.accent_color'])sv('--ac',c['theme.accent_color']);if(c['theme.font_size'])sv('--fs',c['theme.font_size']+'px');if(c['theme.background_opacity'])sv('--bg-opacity',c['theme.background_opacity'])}" +
        "function popSett(c){var v=function(k,d){return c[k]||d};var bg=v('theme.bg_color','#0C0C0C'),fc=v('theme.font_color','#00FF41'),bc=v('theme.border_color','#1A1A2E'),ac=v('theme.accent_color','#E94560');G('tbg').value=bg;G('tbgT').value=bg;G('tfg').value=fc;G('tfgT').value=fc;G('tbd').value=bc;G('tbdT').value=bc;G('tac').value=ac;G('tacT').value=ac;G('tfs').value=v('theme.font_size',14);G('fsv').textContent=v('theme.font_size',14)+'px';G('top').value=v('theme.background_opacity',1);G('opv').textContent=v('theme.background_opacity',1);G('sto').value=v('session.timeout_minutes',30);G('sml').value=v('max_output_lines',5000);G('sport').value=v('port','8080');G('shts').value=v('https.enabled','false');G('scf').value=v('cert.file','');G('swr').value=v('web.root','');htsToggle()}" +
        "async function loadHist(){try{var r=await fetch('/api/command/history');var d=await r.json();ST.ch=d.history||[]}catch(e){}}" +
        "function connectSSE(){if(ST.ss)ST.ss.close();ST.ss=new EventSource('/api/output/stream');ST.ss.addEventListener('output',function(e){try{var d=JSON.parse(e.data);at(d.text,d.color||'#00FF41')}catch(x){}});ST.ss.addEventListener('clear',function(){G('co').innerHTML=''})}" +
        "function at(text,color){if(!text)return;var o=G('co');var s=document.createElement('span');s.style.color=color||'#00FF41';s.textContent=text;o.appendChild(s);var ml=parseInt(ST.th['max_output_lines']||5000);while(o.children.length>ml)o.removeChild(o.firstChild);if(o.textContent.length>10000){var ks=o.children,hf=Math.floor(ks.length/2);while(hf-->0)o.removeChild(o.firstChild)}if(ST.as)o.scrollTop=o.scrollHeight}" +
        "function sendCommand(){var i=G('ci'),c=i.value.trim();if(!c)return;ST.ch.push(c);ST.hi=ST.ch.length;at('\\n> '+c,'#E94560');fetch('/api/command/execute',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({command:c})}).then(function(r){return r.json()}).then(function(d){if(!d.success)at('[ERROR] '+d.message,'#FF4444')}).catch(function(e){at('[ERROR] '+e.message,'#FF4444')});i.value='';i.focus()}" +
        "function clearConsole(){G('co').innerHTML='';fetch('/api/console/clear',{method:'POST'}).catch(function(){})}" +
        "function hk(e){if(!ST.auth){if(e.key==='Enter'){if(G('setupForm').style.display!=='none')doSetup();else doLogin()}return}var so=G('settingsOverlay').classList.contains('on');if(e.key==='Escape'&&so){toggleSettings();return}if(so)return;if(e.ctrlKey&&e.key==='l'){e.preventDefault();clearConsole();return}if(e.key==='Enter'&&document.activeElement===G('ci')){e.preventDefault();sendCommand();return}if(e.key==='ArrowUp'&&document.activeElement===G('ci')){e.preventDefault();if(ST.ch.length>0){ST.hi=Math.max(0,ST.hi-1);G('ci').value=ST.ch[ST.hi]||''}return}if(e.key==='ArrowDown'&&document.activeElement===G('ci')){e.preventDefault();if(ST.hi<ST.ch.length-1){ST.hi++;G('ci').value=ST.ch[ST.hi]||''}else{ST.hi=ST.ch.length;G('ci').value=''}return}}" +
        "function toggleSettings(){var o=G('settingsOverlay');o.classList.toggle('on');if(o.classList.contains('on')){fetch('/api/config/get').then(function(r){return r.json()}).then(function(d){ST.th=d;popSett(d)}).catch(function(){})}}" +
        "function al(){var bg=G('tbg').value,fc=G('tfg').value,bc=G('tbd').value,ac=G('tac').value,sz=G('tfs').value,op=G('top').value;sv('--bg',bg);sv('--fg',fc);sv('--bd',bc);sv('--ac',ac);sv('--fs',sz+'px');sv('--bg-opacity',op);G('tbgT').value=bg;G('tfgT').value=fc;G('tbdT').value=bc;G('tacT').value=ac;G('fsv').textContent=sz+'px';G('opv').textContent=op}" +
        "function scf(id){var t=G(id+'T'),c=G(id),v=t.value.trim();if(/^#[0-9A-Fa-f]{6}$/.test(v)){c.value=v;al()}}" +
        "async function saveTheme(){var p={'theme.bg_color':G('tbg').value,'theme.font_color':G('tfg').value,'theme.border_color':G('tbd').value,'theme.accent_color':G('tac').value,'theme.font_size':G('tfs').value,'theme.background_opacity':G('top').value};try{var r=await fetch('/api/config/theme',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(p)});var d=await r.json();msg('tm',d.success?'已保存':'失败',d.success?'s':'e')}catch(e){msg('tm','网络错误','e')}}" +
        "function resetTheme(){G('tbg').value='#0C0C0C';G('tbgT').value='#0C0C0C';G('tfg').value='#00FF41';G('tfgT').value='#00FF41';G('tbd').value='#1A1A2E';G('tbdT').value='#1A1A2E';G('tac').value='#E94560';G('tacT').value='#E94560';G('tfs').value='14';G('top').value='1.0';G('fsv').textContent='14px';G('opv').textContent='1.0';al();saveTheme()}" +
        "async function changePassword(){var o=G('sop').value,n=G('snp').value;if(!o||!n){msg('pm','请填写所有字段','e');return}if(n.length<6){msg('pm','新密码至少6位','e');return}try{var r=await fetch('/api/auth/changepassword',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({old_password:o,new_password:n})});var d=await r.json();if(d.success){msg('pm','已更新 ✓','s');G('sop').value='';G('snp').value=''}else msg('pm',d.error||'失败','e')}catch(e){msg('pm','网络错误','e')}}" +
        "async function saveServerConfig(){var t=G('sto').value,m=G('sml').value;try{var r=await fetch('/api/config/update',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({'session.timeout_minutes':t,'max_output_lines':m})});var d=await r.json();msg('smg',d.success?'已保存 ✓':'失败',d.success?'s':'e')}catch(e){msg('smg','网络错误','e')}}" +
        "function msg(id,txt,ty){var e=G(id);e.textContent=txt;e.style.color=ty==='s'?'var(--fg)':'var(--ac)';setTimeout(function(){e.textContent=''},3000)}" +
        "function sv(n,v){document.documentElement.style.setProperty(n,v)}" +
        "function htsToggle(){var e=G('shts').value==='true';G('ksr').style.display=e?'':'none';G('ksp').style.display=e?'':'none'}" +
        "async function saveNetworkConfig(){var p={};var pt=G('sport').value;if(pt)p['port']=pt;var he=G('shts').value;p['https.enabled']=he;var cf=G('scf').value.trim();if(cf)p['cert.file']=cf;var kf=G('skf').value.trim();if(kf)p['key.file']=kf;var wr=G('swr').value.trim();p['web.root']=wr;try{var r=await fetch('/api/config/update',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(p)});var d=await r.json();var ok=d.success;msg('nmg',ok?(d.restart?'已保存，服务重启中 ✓':'已保存 ✓'):'失败',ok?'s':'e')}catch(e){msg('nmg','网络错误','e')}}" +
        "</script></body></html>";

    // ==================== 会话 ====================
    private String createSession() { String id = UUID.randomUUID().toString(); sessions.put(id, new SessionInfo(id)); return id; }
    private SessionInfo getSession(HttpExchange ex) {
        String ck = ex.getRequestHeaders().getFirst("Cookie"); if (ck == null) return null;
        for (String c : ck.split(";")) { c = c.trim(); if (c.startsWith(SESSION_COOKIE + "=")) {
            String sid = c.substring(SESSION_COOKIE.length() + 1); SessionInfo s = sessions.get(sid);
            if (s != null && !s.isExpired(config.getSessionTimeoutMinutes())) { s.touch(); return s; }
            else if (s != null) sessions.remove(sid);
        }} return null;
    }
    private void cleanExpiredSessions() { int t = config.getSessionTimeoutMinutes(); sessions.entrySet().removeIf(e -> e.getValue().isExpired(t)); }
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
        byte[] body = readAllBytes(ex.getRequestBody()); String raw = new String(body, StandardCharsets.UTF_8);
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
    private String esc(String s) { if (s == null) return ""; return s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t"); }
    private byte[] readAllBytes(InputStream is) throws IOException { ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] d = new byte[8192]; int n; while ((n = is.read(d)) != -1) b.write(d,0,n); return b.toByteArray(); }
    private byte[] readFileBytes(File f) throws IOException { try (FileInputStream fis = new FileInputStream(f)) { return readAllBytes(fis); } }
    private String getContentType(String p) {
        if (p.endsWith(".html")) return "text/html; charset=UTF-8"; if (p.endsWith(".css")) return "text/css; charset=UTF-8";
        if (p.endsWith(".js")) return "application/javascript; charset=UTF-8"; if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png"; if (p.endsWith(".svg")) return "image/svg+xml"; return "application/octet-stream";
    }

    private static class SessionInfo { final String id; long lastAccess; SessionInfo(String id) { this.id = id; this.lastAccess = System.currentTimeMillis(); } void touch() { this.lastAccess = System.currentTimeMillis(); } boolean isExpired(int t) { return System.currentTimeMillis() - lastAccess > t * 60 * 1000L; } }
    private static class LoginAttempt { int count; long lockTime; void recordFailure() { count++; if (count >= MAX_LOGIN_ATTEMPTS) lockTime = System.currentTimeMillis(); } boolean isLocked() { if (count < MAX_LOGIN_ATTEMPTS) return false; if (System.currentTimeMillis() - lockTime > LOGIN_LOCKOUT_MS) { count = 0; lockTime = 0; return false; } return true; } }
}
