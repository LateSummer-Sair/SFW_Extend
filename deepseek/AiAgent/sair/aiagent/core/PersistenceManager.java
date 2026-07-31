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

import sair.aiagent.AiAgentActivity;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.MemoryEntry;
import sair.aiagent.model.StickerEntry;

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

    private static PersistenceManager instance;
    private final Gson gson = new Gson();
    private final Object lock = new Object();
    private Connection conn;
    private File dbFile;

    public static PersistenceManager getInstance() {
        return instance;
    }

    // ==================== 初始化 ====================

    /**
     * 打开/创建数据库，建表，启用 WAL 模式。
     * @param dataDir 插件数据目录
     * @return 是否需要迁移旧 JSON 数据（首次初始化）
     */
    public boolean init(String dataDir) {
        if (dataDir == null) return false;
        instance = this;
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
                AiAgentActivity.debugLog("[Persistence] new DB created at " + dbFile.getAbsolutePath());
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

                // 表情包表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS stickers (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  image_url TEXT NOT NULL DEFAULT ''," +
                    "  file_path TEXT NOT NULL DEFAULT ''," +
                    "  context TEXT NOT NULL DEFAULT ''," +
                    "  keywords TEXT NOT NULL DEFAULT ''," +
                    "  usage_count INTEGER NOT NULL DEFAULT 0," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sticker_created ON stickers(created_at)");

                // 定时任务表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS cron_tasks (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  cron_expr TEXT NOT NULL DEFAULT ''," +
                    "  command TEXT NOT NULL DEFAULT ''," +
                    "  description TEXT NOT NULL DEFAULT ''," +
                    "  enabled INTEGER NOT NULL DEFAULT 1," +
                    "  last_run INTEGER NOT NULL DEFAULT 0," +
                    "  created_at INTEGER NOT NULL" +
                    ")");

                // 笔记表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS notes (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  title TEXT NOT NULL DEFAULT ''," +
                    "  content TEXT NOT NULL DEFAULT ''," +
                    "  tags TEXT NOT NULL DEFAULT ''," +
                    "  created_at INTEGER NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );

                // FTS5 笔记全文索引
                stmt.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(" +
                    "  title, content, tags," +
                    "  content='notes'," +
                    "  content_rowid='id'" +
                    ")"
                );

                // 触发器（INSERT）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS nft_ai AFTER INSERT ON notes BEGIN " +
                    "  INSERT INTO notes_fts(rowid, title, content, tags) " +
                    "  VALUES (new.id, new.title, new.content, new.tags); " +
                    "END"
                );

                // 触发器（DELETE）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS nft_ad AFTER DELETE ON notes BEGIN " +
                    "  INSERT INTO notes_fts(notes_fts, rowid, title, content, tags) " +
                    "  VALUES ('delete', old.id, old.title, old.content, old.tags); " +
                    "END"
                );

                // 触发器（UPDATE）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS nft_au AFTER UPDATE ON notes BEGIN " +
                    "  INSERT INTO notes_fts(notes_fts, rowid, title, content, tags) " +
                    "  VALUES ('delete', old.id, old.title, old.content, old.tags); " +
                    "  INSERT INTO notes_fts(rowid, title, content, tags) " +
                    "  VALUES (new.id, new.title, new.content, new.tags); " +
                    "END"
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
                AiAgentActivity.debugLog("[Persistence] addMemory FAILED: " + e.getMessage());
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

        AiAgentActivity.debugLog("[Persistence] JSON→SQLite migration done");
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
                AiAgentActivity.debugLog("[Persistence] migrated " + old.size() + " memories from JSON");
            }
            // 迁移后重命名为 .bak 避免重复迁移
            f.renameTo(new File(dir, "memory.json.bak"));
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Persistence] memory.json migration FAILED: " + e.getMessage());
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
            AiAgentActivity.debugLog("[Persistence] migrated emotion from JSON");
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

    // ==================== Stickers ====================

    /** 添加表情包条目 */
    public StickerEntry addSticker(String imageUrl, String filePath, String context, String keywords) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return null;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO stickers (image_url, file_path, context, keywords, usage_count, created_at) "
                    + "VALUES (?,?,?,?,0,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                long now = System.currentTimeMillis();
                ps.setString(1, imageUrl.trim());
                ps.setString(2, filePath != null ? filePath : "");
                ps.setString(3, context != null ? context : "");
                String kw = (keywords != null && !keywords.trim().isEmpty())
                        ? keywords.trim() : StickerEntry.extractKeywords(context);
                ps.setString(4, kw);
                ps.setLong(5, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        return new StickerEntry(rs.getInt(1), imageUrl.trim(), filePath, context, kw, now);
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[Persistence] addSticker FAILED: " + e.getMessage());
            }
            return null;
        }
    }

    /** 增加表情包使用计数 */
    public void incrementStickerUsage(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE stickers SET usage_count = usage_count + 1 WHERE id=?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    /** 列出所有表情包（按时间倒序） */
    public List<StickerEntry> listAllStickers() {
        synchronized (lock) {
            List<StickerEntry> list = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT id, image_url, file_path, context, keywords, usage_count, created_at "
                         + "FROM stickers ORDER BY created_at DESC")) {
                while (rs.next()) {
                    StickerEntry se = new StickerEntry(
                            rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(7));
                    se.setUsageCount(rs.getInt(6));
                    list.add(se);
                }
            } catch (SQLException ignored) {}
            return list;
        }
    }

    /** 获取表情包总数 */
    public int stickerCount() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM stickers")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) { return 0; }
        }
    }

    /** 删除最旧的 N 条表情包（保留最近 maxKeep 条） */
    public void trimStickers(int maxKeep) {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM stickers WHERE id NOT IN "
                        + "(SELECT id FROM stickers ORDER BY created_at DESC LIMIT " + maxKeep + ")");
            } catch (SQLException ignored) {}
        }
    }

    /** 删除指定表情包 */
    public boolean removeSticker(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM stickers WHERE id=?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) { return false; }
        }
    }

    // ==================== Cron Tasks (定时任务) ====================

    /** 添加定时任务，返回自增 ID */
    public int addCronTask(String cronExpr, String command, String description) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO cron_tasks (cron_expr, command, description, enabled, last_run, created_at) VALUES (?,?,?,1,0,?)")) {
                ps.setString(1, cronExpr != null ? cronExpr : "");
                ps.setString(2, command != null ? command : "");
                ps.setString(3, description != null ? description : "");
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) { return -1; }
        }
        return -1;
    }

    /** 列出所有定时任务 */
    public List<sair.aiagent.core.CronScheduler.CronTask> listCronTasks() {
        List<sair.aiagent.core.CronScheduler.CronTask> list = new ArrayList<>();
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM cron_tasks ORDER BY created_at")) {
                while (rs.next()) {
                    list.add(new sair.aiagent.core.CronScheduler.CronTask(
                            rs.getInt("id"), rs.getString("cron_expr"),
                            rs.getString("command"), rs.getString("description"),
                            rs.getInt("enabled") != 0, rs.getLong("created_at")));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 获取单个定时任务 */
    public sair.aiagent.core.CronScheduler.CronTask getCronTask(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM cron_tasks WHERE id=?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new sair.aiagent.core.CronScheduler.CronTask(
                                rs.getInt("id"), rs.getString("cron_expr"),
                                rs.getString("command"), rs.getString("description"),
                                rs.getInt("enabled") != 0, rs.getLong("created_at"));
                    }
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    /** 删除定时任务 */
    public boolean removeCronTask(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM cron_tasks WHERE id=?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) { return false; }
        }
    }

    /** 设置任务启用/禁用 */
    public void setCronTaskEnabled(int id, boolean enabled) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE cron_tasks SET enabled=? WHERE id=?")) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    /** 更新最后执行时间 */
    public void updateCronTaskLastRun(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE cron_tasks SET last_run=? WHERE id=?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    // ==================== Notes (knowledge base) ====================

    /** add note, returns auto-inc id */
    public int addNote(String title, String content, String tags) {
        synchronized (lock) {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO notes (title, content, tags, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                long now = System.currentTimeMillis();
                ps.setString(1, title != null ? title : "");
                ps.setString(2, content != null ? content : "");
                ps.setString(3, tags != null ? tags : "");
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
                try (java.sql.ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (java.sql.SQLException e) { return -1; }
        }
        return -1;
    }

    /** search notes by FTS5, returns list of [id, title, snippet] */
    public java.util.List<String[]> searchNotes(String query, int limit) {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        synchronized (lock) {
            try {
                String sql = "SELECT n.id, n.title, snippet(notes_fts, 2, '<b>', '</b>', '...', 32) as sn " +
                             "FROM notes_fts f JOIN notes n ON f.rowid = n.id " +
                             "WHERE notes_fts MATCH ? ORDER BY rank LIMIT ?";
                java.sql.PreparedStatement ps = conn.prepareStatement(sql);
                ps.setString(1, query);
                ps.setInt(2, limit > 0 ? limit : 10);
                java.sql.ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    list.add(new String[]{String.valueOf(rs.getInt("id")), 
                                          rs.getString("title"), rs.getString("sn")});
                }
                rs.close(); ps.close();
            } catch (java.sql.SQLException e) {
                // FTS5 match failure, try LIKE fallback
                try (java.sql.Statement stmt = conn.createStatement();
                     java.sql.ResultSet rs = stmt.executeQuery(
                         "SELECT id, title, substr(content,1,200) FROM notes WHERE title LIKE '%" +
                         query.replace("'","''") + "%' OR content LIKE '%" + 
                         query.replace("'","''") + "%' ORDER BY updated_at DESC LIMIT " + limit)) {
                    while (rs.next()) {
                        list.add(new String[]{String.valueOf(rs.getInt(1)), 
                                              rs.getString(2), rs.getString(3)});
                    }
                } catch (java.sql.SQLException ignored) {}
            }
        }
        return list;
    }

    /** list recent notes */
    public java.util.List<String[]> listNotes(int limit) {
        java.util.List<String[]> list = new java.util.ArrayList<>();
        synchronized (lock) {
            try (java.sql.Statement stmt = conn.createStatement();
                 java.sql.ResultSet rs = stmt.executeQuery(
                     "SELECT id, title, substr(content,1,100), tags FROM notes ORDER BY updated_at DESC LIMIT " + limit)) {
                while (rs.next()) {
                    list.add(new String[]{String.valueOf(rs.getInt(1)), rs.getString(2),
                                          rs.getString(3), rs.getString(4)});
                }
            } catch (java.sql.SQLException ignored) {}
        }
        return list;
    }

    /** get note by id */
    public String[] getNote(int id) {
        synchronized (lock) {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "SELECT title, content, tags FROM notes WHERE id=?")) {
                ps.setInt(1, id);
                java.sql.ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    return new String[]{rs.getString("title"), rs.getString("content"), rs.getString("tags")};
                }
            } catch (java.sql.SQLException ignored) {}
        }
        return null;
    }

    /** delete note */
    public boolean removeNote(int id) {
        synchronized (lock) {
            try (java.sql.PreparedStatement ps = conn.prepareStatement("DELETE FROM notes WHERE id=?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (java.sql.SQLException e) { return false; }
        }
    }

    /** update note */
    public boolean updateNote(int id, String title, String content, String tags) {
        synchronized (lock) {
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "UPDATE notes SET title=?, content=?, tags=?, updated_at=? WHERE id=?")) {
                ps.setString(1, title != null ? title : "");
                ps.setString(2, content != null ? content : "");
                ps.setString(3, tags != null ? tags : "");
                ps.setLong(4, System.currentTimeMillis());
                ps.setInt(5, id);
                return ps.executeUpdate() > 0;
            } catch (java.sql.SQLException e) { return false; }
        }
    }

    private static String trunc(String s, int maxLen) {

        if (s == null || s.isEmpty()) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }
}
