package sair.aiagent.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.MemoryEntry;

/**
 * SQLite 统一持久化管理器 —— 替代 5 个独立 JSON 文件。
 * <p>
 * 单文件 aiagent.db，WAL 模式，FTS5 全文搜索，事务保护。
 * </p>
 *
 * <h3>表结构</h3>
 * <ul>
 *   <li>memories — 记忆条目（带 category/importance）</li>
 *   <li>memories_fts — FTS5 全文索引</li>
 *   <li>journal — 操作日志（最多 80 条）</li>
 *   <li>conversations — 对话历史</li>
 *   <li>app_state — 键值状态</li>
 * </ul>
 */
public class PersistenceManager {

    private static final int JRN_MAX = 80;
    private static final int CONV_MAX_TOKENS = 900_000;
    private static final int FTS_MAX_RESULTS = 5;
    private static final int CTX_MAX_CHARS = 2000;

    private final Gson gson = new Gson();
    private final Object lock = new Object();
    private Connection conn;
    private File dbFile;

    // ==================== 初始化 ====================

    /**
     * 打开/创建数据库，建表，启用 WAL 模式。
     * @param dataDir 插件数据目录
     * @return 是否需要迁移旧 JSON 数据（首次初始化）
     */
    public boolean init(String dataDir) {
        if (dataDir == null) return false;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("sqlite-jdbc driver not found", e);
        }

        File dir = new File(dataDir);
        dir.mkdirs();
        dbFile = new File(dir, "aiagent.db");
        boolean isNew = !dbFile.exists();

        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA foreign_keys=OFF");
                stmt.execute("PRAGMA busy_timeout=3000");
            }

            createTables();

            if (isNew) {
                System.err.println("[Persistence] new DB created at " + dbFile.getAbsolutePath());
            }
            return isNew;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to init SQLite", e);
        }
    }

    /** 建表 + FTS5 虚拟表 + 触发器 */
    private void createTables() throws SQLException {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                // 记忆表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  category TEXT NOT NULL DEFAULT 'general'," +
                    "  content TEXT NOT NULL," +
                    "  importance INTEGER NOT NULL DEFAULT 0," +
                    "  created_at INTEGER NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );

                // FTS5 全文索引
                stmt.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(" +
                    "  content, category," +
                    "  content='memories'," +
                    "  content_rowid='id'" +
                    ")"
                );

                // 触发器（INSERT）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS mft_ai AFTER INSERT ON memories BEGIN " +
                    "  INSERT INTO memories_fts(rowid, content, category) " +
                    "  VALUES (new.id, new.content, new.category); " +
                    "END"
                );

                // 触发器（DELETE）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS mft_ad AFTER DELETE ON memories BEGIN " +
                    "  INSERT INTO memories_fts(memories_fts, rowid, content, category) " +
                    "  VALUES ('delete', old.id, old.content, old.category); " +
                    "END"
                );

                // 触发器（UPDATE）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS mft_au AFTER UPDATE ON memories BEGIN " +
                    "  INSERT INTO memories_fts(memories_fts, rowid, content, category) " +
                    "  VALUES ('delete', old.id, old.content, old.category); " +
                    "  INSERT INTO memories_fts(rowid, content, category) " +
                    "  VALUES (new.id, new.content, new.category); " +
                    "END"
                );

                // 日志表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS journal (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  role TEXT NOT NULL," +
                    "  mode TEXT NOT NULL DEFAULT ''," +
                    "  content TEXT NOT NULL DEFAULT ''," +
                    "  result TEXT NOT NULL DEFAULT ''," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_jn_created ON journal(created_at)");

                // 对话表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS conversations (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  role TEXT NOT NULL," +
                    "  content TEXT NOT NULL," +
                    "  token_count INTEGER NOT NULL DEFAULT 0," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_created ON conversations(created_at)");

                // 键值状态表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS app_state (" +
                    "  key TEXT PRIMARY KEY," +
                    "  value TEXT NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );
            }
        }
    }

    /** 关闭数据库连接 */
    public void close() {
        synchronized (lock) {
            if (conn != null) {
                try {
                    // WAL checkpoint
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (SQLException ignored) {}
                    conn.close();
                } catch (SQLException ignored) {}
                conn = null;
            }
        }
    }

    // ==================== Memories ====================

    /**
     * 添加记忆条目。
     * @param content    内容
     * @param category   分类
     * @param importance 重要性 0-10
     * @return 创建的 MemoryEntry（含自增 id），失败返回 null
     */
    public MemoryEntry addMemory(String content, String category, int importance) {
        if (content == null || content.trim().isEmpty()) return null;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO memories (category, content, importance, created_at, updated_at) VALUES (?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                long now = System.currentTimeMillis();
                ps.setString(1, category != null ? category : "general");
                ps.setString(2, content.trim());
                ps.setInt(3, Math.max(0, Math.min(10, importance)));
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int id = rs.getInt(1);
                        return new MemoryEntry(id, content.trim(), category, importance, now);
                    }
                }
            } catch (SQLException e) {
                System.err.println("[Persistence] addMemory FAILED: " + e.getMessage());
            }
            return null;
        }
    }

    /** 简单添加（默认category=general, importance=0） */
    public MemoryEntry addMemory(String content) {
        return addMemory(content, "general", 0);
    }

    /** 按 ID 删除 */
    public boolean removeMemory(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM memories WHERE id=?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                return false;
            }
        }
    }

    /** 清空所有记忆 */
    public void clearMemories() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM memories");
            } catch (SQLException ignored) {}
        }
    }

    /** 记忆总数 */
    public int memoryCount() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM memories")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    /** 列出所有记忆（按 ID 排序） */
    public List<MemoryEntry> listAllMemories() {
        synchronized (lock) {
            List<MemoryEntry> list = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT id, category, content, importance, created_at, updated_at " +
                         "FROM memories ORDER BY id")) {
                while (rs.next()) {
                    list.add(new MemoryEntry(
                            rs.getInt(1), rs.getString(3), rs.getString(2),
                            rs.getInt(4), rs.getLong(5)));
                }
            } catch (SQLException ignored) {}
            return list;
        }
    }

    /**
     * FTS5 全文搜索。
     * @param query      搜索词
     * @param maxResults 最大结果数
     * @return 得分排序的记忆列表
     */
    public List<MemoryEntry> searchMemories(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) return new ArrayList<>();
        synchronized (lock) {
            List<MemoryEntry> results = new ArrayList<>();
            // 用 FTS5 匹配表达式
            String ftsQuery = query.trim().replaceAll("\\s+", " OR ");
            String sql =
                "SELECT m.id, m.category, m.content, m.importance, m.created_at, m.updated_at " +
                "FROM memories_fts f JOIN memories m ON f.rowid = m.id " +
                "WHERE memories_fts MATCH ? " +
                "ORDER BY rank " +
                "LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ftsQuery);
                int limit = maxResults > 0 ? maxResults : FTS_MAX_RESULTS;
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        results.add(new MemoryEntry(
                                rs.getInt(1), rs.getString(3), rs.getString(2),
                                rs.getInt(4), rs.getLong(5)));
                    }
                }
            } catch (SQLException e) {
                // FTS5 查询失败（特殊字符等），回退到 LIKE
                return searchMemoriesFallback(query, maxResults);
            }
            return results;
        }
    }

    /** LIKE 回退搜索 */
    private List<MemoryEntry> searchMemoriesFallback(String query, int maxResults) {
        List<MemoryEntry> list = new ArrayList<>();
        String pattern = "%" + query.trim() + "%";
        String sql =
            "SELECT id, category, content, importance, created_at, updated_at " +
            "FROM memories WHERE content LIKE ? OR category LIKE ? " +
            "ORDER BY created_at DESC LIMIT ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, pattern);
            ps.setString(2, pattern);
            int limit = maxResults > 0 ? maxResults : FTS_MAX_RESULTS;
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new MemoryEntry(
                            rs.getInt(1), rs.getString(3), rs.getString(2),
                            rs.getInt(4), rs.getLong(5)));
                }
            }
        } catch (SQLException ignored) {}
        return list;
    }

    /** 构建记忆上下文字符串（供 System Prompt 注入） */
    public String buildMemoryContext(String query, int maxChars) {
        List<MemoryEntry> related = searchMemories(query, FTS_MAX_RESULTS);
        if (related.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("## Related Memories\n");
        sb.append("Previously remembered information that may be relevant:\n\n");

        int chars = 0;
        int limit = maxChars > 0 ? maxChars : CTX_MAX_CHARS;
        for (MemoryEntry m : related) {
            String line = "- [" + m.getCategory() + "] " + m.getContent() + "\n";
            if (chars + line.length() > limit) break;
            sb.append(line);
            chars += line.length();
        }
        return sb.toString();
    }

    // ==================== Journal ====================

    /** 添加一条日志 */
    public void addJournalEntry(String role, String mode, String content, String result) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO journal (role, mode, content, result, created_at) VALUES (?,?,?,?,?)")) {
                ps.setString(1, role);
                ps.setString(2, mode);
                ps.setString(3, trunc(content, 300));
                ps.setString(4, trunc(result, 500));
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}

            // 裁剪：保留最近 80 条
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM journal WHERE id NOT IN " +
                        "(SELECT id FROM journal ORDER BY created_at DESC LIMIT " + JRN_MAX + ")");
            } catch (SQLException ignored) {}
        }
    }

    /** 构建最近 N 条日志上下文 */
    public String buildJournalContext(int maxEntries) {
        synchronized (lock) {
            List<String[]> entries = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT role, mode, content, result, created_at " +
                         "FROM journal ORDER BY created_at DESC LIMIT " + maxEntries)) {
                while (rs.next()) {
                    entries.add(new String[] {
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), String.valueOf(rs.getLong(5))
                    });
                }
            } catch (SQLException ignored) {}

            if (entries.isEmpty()) return null;

            // 反转回时间顺序
            Collections.reverse(entries);

            StringBuilder sb = new StringBuilder();
            sb.append("## Recent Activity\n");
            sb.append("Recent interactions on this system:\n\n");

            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
            int totalChars = 0;
            for (String[] e : entries) {
                String time = sdf.format(new java.util.Date(Long.parseLong(e[4])));
                String role = e[0];
                String mode = e[1];
                String content = e[2] != null ? e[2] : "";
                String result = e[3] != null ? e[3] : "";

                String roleLabel;
                switch (role) {
                    case "user":      roleLabel = "User";      break;
                    case "assistant": roleLabel = "Assistant"; break;
                    case "agent":     roleLabel = "Agent";     break;
                    default:          roleLabel = role;
                }

                String line = "[" + time + "] " + roleLabel +
                        (mode != null && !mode.isEmpty() && !"action".equals(mode) ? " (" + mode + ")" : "") +
                        ": " + content;
                if (!result.isEmpty()) line += " → " + result;
                line += "\n";

                if (totalChars + line.length() > 5000) {
                    sb.append("… (truncated)");
                    break;
                }
                sb.append(line);
                totalChars += line.length();
            }
            return sb.toString();
        }
    }

    // ==================== Conversations ====================

    /** 添加一条对话消息 */
    public void addConversationMessage(ChatMessage msg) {
        if (msg == null) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO conversations (role, content, token_count, created_at) VALUES (?,?,?,?)")) {
                ps.setString(1, msg.getRole());
                ps.setString(2, msg.getContent());
                ps.setInt(3, msg.estimateTokens());
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}

            // 裁剪：控制在 900K token
            trimConversations();
        }
    }

    /** 加载所有对话消息 */
    public List<ChatMessage> loadConversations() {
        synchronized (lock) {
            List<ChatMessage> list = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT role, content, token_count FROM conversations ORDER BY id")) {
                while (rs.next()) {
                    ChatMessage msg = new ChatMessage(rs.getString(1), rs.getString(2));
                    list.add(msg);
                }
            } catch (SQLException ignored) {}
            return list;
        }
    }

    /** 清空对话 */
    public void clearConversations() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM conversations");
            } catch (SQLException ignored) {}
        }
    }

    /** 裁剪超 token 的对话记录 */
    private void trimConversations() {
        synchronized (lock) {
            try {
                // 从最旧开始累计 token，超出上限则删除
                int totalTokens = 0;
                int cutoffId = -1;
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(
                             "SELECT id, token_count FROM conversations ORDER BY created_at DESC")) {
                    List<int[]> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new int[] { rs.getInt(1), rs.getInt(2) });
                    }
                    // 保留至少 4 条
                    int minKeep = Math.min(4, rows.size());
                    for (int i = 0; i < rows.size() - minKeep; i++) {
                        int[] r = rows.get(i);
                        if (totalTokens + r[1] > CONV_MAX_TOKENS) {
                            cutoffId = r[0];
                            break;
                        }
                        totalTokens += r[1];
                    }
                }
                if (cutoffId > 0) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM conversations WHERE id <= ?")) {
                        ps.setInt(1, cutoffId);
                        ps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        }
    }

    // ==================== App State (K-V) ====================

    /** 设置状态值 */
    public void setState(String key, String value) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "REPLACE INTO app_state (key, value, updated_at) VALUES (?,?,?)")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    /** 获取状态值 */
    public String getState(String key) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM app_state WHERE key=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (SQLException ignored) {}
            return null;
        }
    }

    // ==================== 旧数据迁移 ====================

    /**
     * 从旧 JSON 文件迁移数据到 SQLite。
     * @param dataDir 插件数据目录
     */
    public void migrateFromJson(String dataDir) {
        if (dataDir == null) return;
        File dir = new File(dataDir);
        if (!dir.exists()) return;

        migrateMemoryJson(dir);
        migrateJournalJson(dir);
        migrateHistoryJson(dir);
        migrateEmotionJson(dir);
        migrateContextJson(dir);

        System.err.println("[Persistence] JSON→SQLite migration done");
    }

    private void migrateMemoryJson(File dir) {
        File f = new File(dir, "memory.json");
        if (!f.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<MemoryEntry>>() {}.getType();
            List<MemoryEntry> old = gson.fromJson(reader, listType);
            if (old != null) {
                for (MemoryEntry m : old) {
                    addMemory(m.getContent(), m.getCategory(), m.getImportance());
                }
                System.err.println("[Persistence] migrated " + old.size() + " memories from JSON");
            }
            // 迁移后重命名为 .bak 避免重复迁移
            f.renameTo(new File(dir, "memory.json.bak"));
        } catch (Exception e) {
            System.err.println("[Persistence] memory.json migration FAILED: " + e.getMessage());
        }
    }

    private void migrateJournalJson(File dir) {
        File f = new File(dir, "journal.json");
        if (!f.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<GsonJournalEntry>>() {}.getType();
            List<GsonJournalEntry> old = gson.fromJson(reader, listType);
            if (old != null) {
                for (GsonJournalEntry e : old) {
                    addJournalEntry(e.role, e.mode, e.content, e.result);
                }
            }
            f.renameTo(new File(dir, "journal.json.bak"));
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unused")
    private static class GsonJournalEntry {
        long timestamp; String role; String mode; String content; String result;
    }

    private void migrateHistoryJson(File dir) {
        File f = new File(dir, "history.json");
        if (!f.exists()) return;
        try (Reader reader = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
            Type listType = new TypeToken<List<ChatMessage>>() {}.getType();
            List<ChatMessage> old = gson.fromJson(reader, listType);
            if (old != null) {
                for (ChatMessage m : old) {
                    addConversationMessage(m);
                }
            }
            f.renameTo(new File(dir, "history.json.bak"));
        } catch (Exception ignored) {}
    }

    private void migrateEmotionJson(File dir) {
        File f = new File(dir, "emotion.json");
        if (!f.exists()) return;
        try {
            String content = new String(
                    java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            setState("emotion", content);
            System.err.println("[Persistence] migrated emotion from JSON");
            f.renameTo(new File(dir, "emotion.json.bak"));
        } catch (Exception ignored) {}
    }

    private void migrateContextJson(File dir) {
        File f = new File(dir, "context.json");
        if (!f.exists()) return;
        try {
            String content = new String(
                    java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            // 只取 summary 字段
            ContextSnapshot snap = gson.fromJson(content, ContextSnapshot.class);
            if (snap != null && snap.summary != null && !snap.summary.isEmpty()) {
                setState("context_summary", snap.summary);
            }
            f.renameTo(new File(dir, "context.json.bak"));
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unused")
    private static class ContextSnapshot {
        String summary; long timestamp;
    }

    // ==================== 工具方法 ====================

    /** @return 数据库文件路径 */
    public File getDbFile() { return dbFile; }

    private static String trunc(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }
}
