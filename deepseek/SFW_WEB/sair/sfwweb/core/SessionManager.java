package sair.sfwweb.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * 会话管理器 — 记录会话元数据（IP、登录时间、UA）和每会话命令审计日志。
 * 命令历史持久化到文件，7 天自动过期，不可删除。
 */
public class SessionManager {

    private static final long SEVEN_DAYS_MS = 7L * 24 * 60 * 60 * 1000;

    /** 会话元数据 */
    public static class SessionMeta {
        public final String id;
        public final String ip;
        public final long loginTime;
        public final String userAgent;
        public SessionMeta(String id, String ip, long loginTime, String userAgent) {
            this.id = id; this.ip = ip; this.loginTime = loginTime; this.userAgent = userAgent;
        }
    }

    /** 审计条目 */
    public static class AuditEntry {
        public final String command;
        public final long timestamp;
        public AuditEntry(String command, long timestamp) {
            this.command = command; this.timestamp = timestamp;
        }
    }

    private final Map<String, SessionMeta> metas = new ConcurrentHashMap<>();
    private final Map<String, List<AuditEntry>> commands = new ConcurrentHashMap<>();
    private final File dataFile;
    private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SFW_WEB_AuditWriter");
        t.setDaemon(true);
        return t;
    });

    public SessionManager(File dataDir) {
        if (!dataDir.exists()) dataDir.mkdirs();
        this.dataFile = new File(dataDir, "audit_commands.json");
        load();
    }

    // ==================== 会话元数据 ====================

    public void registerSession(String id, String ip, String userAgent) {
        metas.put(id, new SessionMeta(id, ip, System.currentTimeMillis(),
            userAgent != null ? userAgent : ""));
    }

    public void removeSession(String id) {
        metas.remove(id);
    }

    public Collection<SessionMeta> getActiveSessions() {
        return new ArrayList<>(metas.values());
    }

    // ==================== 命令审计 ====================

    public void logCommand(String sessionId, String command) {
        if (command == null || command.trim().isEmpty()) return;
        AuditEntry entry = new AuditEntry(command.trim(), System.currentTimeMillis());
        commands.computeIfAbsent(sessionId, k ->
            Collections.synchronizedList(new ArrayList<>())).add(entry);
        // 异步写入磁盘（使用线程池）
        writerExecutor.submit(() -> appendToFile(sessionId, entry));
    }

    public List<AuditEntry> getSessionHistory(String sessionId) {
        List<AuditEntry> list = commands.get(sessionId);
        if (list == null) return Collections.emptyList();
        long cutoff = System.currentTimeMillis() - SEVEN_DAYS_MS;
        synchronized (list) {
            list.removeIf(e -> e.timestamp < cutoff);
        }
        return new ArrayList<>(list);
    }

    // ==================== 持久化 ====================

    private synchronized void load() {
        if (!dataFile.exists()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    // 格式: {"sid":"...","cmd":"...","time":1234567890}
                    String sid = extractJsonStr(line, "sid");
                    String cmd = extractJsonStr(line, "cmd");
                    long time = extractJsonLong(line, "time");
                    if (sid != null && cmd != null && time > 0) {
                        long cutoff = System.currentTimeMillis() - SEVEN_DAYS_MS;
                        if (time >= cutoff) {
                            commands.computeIfAbsent(sid, k ->
                                Collections.synchronizedList(new ArrayList<>()))
                                .add(new AuditEntry(cmd, time));
                        }
                    }
                } catch (Exception ignore) {}
            }
        } catch (IOException e) {
            System.err.println("[SFW_WEB] SessionManager load error: " + e.getMessage());
        }
    }

    private synchronized void appendToFile(String sid, AuditEntry entry) {
        try (FileWriter fw = new FileWriter(dataFile, true);
             BufferedWriter bw = new BufferedWriter(fw)) {
            bw.write("{\"sid\":\"" + escJson(sid) + "\",\"cmd\":\"" +
                escJson(entry.command) + "\",\"time\":" + entry.timestamp + "}");
            bw.newLine();
            bw.flush();
        } catch (IOException e) {
            System.err.println("[SFW_WEB] SessionManager write error: " + e.getMessage());
        }
    }

    // ==================== JSON 工具（轻量，不引入第三方库） ====================

    private static String extractJsonStr(String json, String key) {
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        i += pat.length();
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) { sb.append(c); esc = false; continue; }
            if (c == '\\') { esc = true; continue; }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    private static long extractJsonLong(String json, String key) {
        String pat = "\"" + key + "\":";
        int i = json.indexOf(pat);
        if (i < 0) return -1;
        i += pat.length();
        StringBuilder sb = new StringBuilder();
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c >= '0' && c <= '9' || c == '-') sb.append(c);
            else if (sb.length() > 0) break;
        }
        try { return Long.parseLong(sb.toString()); } catch (NumberFormatException e) { return -1; }
    }

    private static String escJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
