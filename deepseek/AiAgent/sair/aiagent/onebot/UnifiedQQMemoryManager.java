package sair.aiagent.onebot;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sair.aiagent.AiAgentActivity;

/**
 * QQ统一记忆管理器 —— 所有消息存储在一个数据库中，通过标记区分来源。
 * <p>
 * 使用单一数据库文件存储所有QQ和群聊的记忆，避免隔离导致的"失忆"问题。
 * 每条消息标记来源类型（群聊/私聊）、来源ID（群号/QQ号）、发送者QQ。
 * </p>
 *
 * <h3>表结构</h3>
 * <ul>
 *   <li>conversations — 对话历史 (id, role, content, source_type, source_id, sender_id, created_at)</li>
 *   <li>memories — AI的独立记忆 (id, content, created_at)</li>
 *   <li>group_chat_history — 群聊完整历史 (id, user_id, nickname, content, group_id, created_at)</li>
 *   <li>group_nicknames — 群昵称关联表 (group_id, user_id, nickname, updated_at)</li>
 *   <li>app_state — 键值状态</li>
 * </ul>
 */
public class UnifiedQQMemoryManager {

    private static final int CONV_MAX = 500; // 对话历史保留最近500条
    private static final int MEM_MAX = 200;  // AI记忆保留最近200条
    
    private final String dbPath;
    private Connection conn;
    private final Object lock = new Object();
    /** 对话写入计数：降低 conversations 裁剪（DELETE）执行频率。 */
    private int conversationWrites = 0;

    /** 群管理员/昵称映射短 TTL 缓存（60s），避免每条群消息重复查询多行结果。 */
    private static final long GROUP_CACHE_TTL_MS = 60_000L;
    private final Map<Long, TtlCache<List<String[]>>> groupAdminsCache = new ConcurrentHashMap<>();
    private final Map<Long, TtlCache<Map<String, Long>>> groupNickMapCache = new ConcurrentHashMap<>();
    private final Map<Long, TtlCache<String>> groupNameCache = new ConcurrentHashMap<>();
    /** 个人昵称映射纯 TTL 缓存（60s，不随 addConversation 失效，昵称变化不频繁）。 */
    private volatile Map<String, Long> cachedPersonalNickMap;
    private volatile long cachedPersonalNickMapTime;

    /** 群成员角色/昵称内存快照：用于跳过「值未变化」的重复 REPLACE 写库。
     *  角色/昵称变化不频繁，仅在检测到变化时才落库，减少每条群消息的写放大。 */
    private final Map<Long, Map<Long, String>> memberRoleCache = new ConcurrentHashMap<>();
    private final Map<Long, Map<Long, String>> memberNickCache = new ConcurrentHashMap<>();

    /** 带时间戳的简单 TTL 缓存项。 */
    private static final class TtlCache<T> {
        final long time;
        final T value;
        TtlCache(long time, T value) { this.time = time; this.value = value; }
    }

    private <T> T getCached(Map<Long, TtlCache<T>> cache, long groupId) {
        TtlCache<T> e = cache.get(groupId);
        if (e != null && System.currentTimeMillis() - e.time < GROUP_CACHE_TTL_MS) return e.value;
        return null;
    }

    private <T> void putCached(Map<Long, TtlCache<T>> cache, long groupId, T value) {
        cache.put(groupId, new TtlCache<>(System.currentTimeMillis(), value));
    }

    /**
     * 构造统一记忆管理器。
     * @param dataDir 数据根目录
     */
    public UnifiedQQMemoryManager(String dataDir) {
        this.dbPath = dataDir + File.separator + "qq_unified_memory.db";
    }

    /** 初始化数据库连接并建表 */
    public void init() {
        File dbFile = new File(dbPath);
        dbFile.getParentFile().mkdirs();
        
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("找不到 SQLite JDBC 驱动", e);
        }
        
        try {
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");
                stmt.execute("PRAGMA busy_timeout=3000");
            }
            createTables();
        } catch (SQLException e) {
            throw new RuntimeException("初始化统一记忆数据库失败", e);
        }
    }

    private void createTables() throws SQLException {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                // 对话历史表 - 包含来源信息
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS conversations (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  role TEXT NOT NULL," +  // user 或 assistant
                    "  content TEXT NOT NULL," +
                    "  source_type TEXT NOT NULL," +  // 'group' 或 'private'
                    "  source_id INTEGER NOT NULL," +  // 群号或QQ号
                    "  sender_id INTEGER," +  // 发送者QQ号（群聊时有意义）
                    "  sender_name TEXT," +  // 发送者昵称
                    "  created_at INTEGER NOT NULL," +
                    "  mark TEXT" +  // AI 自用 Mark 备注（标记已处理/处理结果）
                    ")"
                );
                
                // AI的独立记忆表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  content TEXT NOT NULL," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );

                // FTS5 全文索引（升级：替代 LIKE 搜索，支持 BM25 相关性排序）
                stmt.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(" +
                    "  content," +
                    "  content='memories'," +
                    "  content_rowid='id'" +
                    ")"
                );

                // 触发器（INSERT）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS umft_ai AFTER INSERT ON memories BEGIN " +
                    "  INSERT INTO memories_fts(rowid, content) VALUES (new.id, new.content); " +
                    "END"
                );

                // 触发器（DELETE）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS umft_ad AFTER DELETE ON memories BEGIN " +
                    "  INSERT INTO memories_fts(memories_fts, rowid, content) " +
                    "  VALUES ('delete', old.id, old.content); " +
                    "END"
                );

                // 触发器（UPDATE）
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS umft_au AFTER UPDATE ON memories BEGIN " +
                    "  INSERT INTO memories_fts(memories_fts, rowid, content) " +
                    "  VALUES ('delete', old.id, old.content); " +
                    "  INSERT INTO memories_fts(rowid, content) VALUES (new.id, new.content); " +
                    "END"
                );
                
                // 群聊完整历史表（持久化保存所有群消息）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS group_chat_history (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  user_id INTEGER NOT NULL," +
                    "  nickname TEXT," +
                    "  content TEXT NOT NULL," +
                    "  group_id INTEGER NOT NULL," +
                    "  message_id INTEGER NOT NULL DEFAULT 0," +
                    "  created_at INTEGER NOT NULL," +
                    "  mark TEXT" +  // AI 自用 Mark 备注（标记已处理/处理结果）
                    ")"
                );
                
                // 群聊历史 FTS5 全文索引（支持跨群检索群聊内容）
                stmt.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS group_chat_history_fts USING fts5(" +
                    "  content," +
                    "  content='group_chat_history'," +
                    "  content_rowid='id'" +
                    ")"
                );
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS umft_gch_ai AFTER INSERT ON group_chat_history BEGIN " +
                    "  INSERT INTO group_chat_history_fts(rowid, content) VALUES (new.id, new.content); " +
                    "END"
                );
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS umft_gch_ad AFTER DELETE ON group_chat_history BEGIN " +
                    "  INSERT INTO group_chat_history_fts(group_chat_history_fts, rowid, content) " +
                    "  VALUES ('delete', old.id, old.content); " +
                    "END"
                );
                
                // 群昵称关联表（群号+昵称→QQ号映射）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS group_nicknames (" +
                    "  group_id INTEGER NOT NULL," +
                    "  user_id INTEGER NOT NULL," +
                    "  nickname TEXT NOT NULL," +
                    "  updated_at INTEGER NOT NULL," +
                    "  PRIMARY KEY (group_id, user_id)" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_nicknames_lookup ON group_nicknames(group_id, nickname)");
                
                // 群成员角色表（群号+QQ号→角色，用于@管理员/群主）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS group_members (" +
                    "  group_id INTEGER NOT NULL," +
                    "  user_id INTEGER NOT NULL," +
                    "  nickname TEXT NOT NULL," +
                    "  role TEXT NOT NULL," +  // owner/admin/member
                    "  updated_at INTEGER NOT NULL," +
                    "  PRIMARY KEY (group_id, user_id)" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_members_role ON group_members(group_id, role)");
                
                // 应用状态表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS app_state (" +
                    "  key TEXT PRIMARY KEY," +
                    "  value TEXT NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );

                // 幂等迁移：老库补 mark 列（列已存在时 ALTER TABLE 会报错，静默忽略）
                ensureColumn("conversations", "mark TEXT");
                ensureColumn("group_chat_history", "mark TEXT");
                ensureColumn("group_chat_history", "message_id INTEGER NOT NULL DEFAULT 0");

                // 热路径查询索引：避免每消息的全表扫描（私聊历史/去重/群聊近期历史）
                try (Statement idxStmt = conn.createStatement()) {
                    idxStmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_source ON conversations(source_type, source_id)");
                    idxStmt.execute("CREATE INDEX IF NOT EXISTS idx_conv_sender ON conversations(sender_id)");
                    idxStmt.execute("CREATE INDEX IF NOT EXISTS idx_gch_group_time ON group_chat_history(group_id, created_at)");
                } catch (SQLException ignored) {}
            }
        }
    }

    /** 幂等加列：列已存在时 ALTER TABLE 报错，静默忽略。 */
    private void ensureColumn(String table, String columnDef) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + columnDef);
        } catch (SQLException ignored) {
            // 列已存在
        }
    }

    /** 关闭数据库连接 */
    public void close() {
        synchronized (lock) {
            if (conn != null) {
                try {
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                    } catch (SQLException ignored) {}
                    conn.close();
                } catch (SQLException ignored) {}
                conn = null;
            }
        }
    }

    // ==================== 对话历史（统一存储） ====================

    /** 
     * 添加一条对话消息到统一历史（自动去重）
     * @param role "user" 或 "assistant"
     * @param content 消息内容
     * @param sourceType "group" 或 "private"
     * @param sourceId 群号或QQ号
     * @param senderId 发送者QQ号（可选）
     * @param senderName 发送者昵称（可选）
     */
    public void addConversation(String role, String content, String sourceType, 
                                 long sourceId, Long senderId, String senderName) {
        if (content == null || content.trim().isEmpty()) return;
        
        String trimmedContent = content.trim();
        
        synchronized (lock) {
            // 去重检查：检查最近10条消息中是否有相同内容（避免重复存储）
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM conversations WHERE sender_id = ? AND content = ? ORDER BY created_at DESC LIMIT 10")) {
                if (senderId != null) {
                    ps.setLong(1, senderId);
                } else {
                    ps.setNull(1, java.sql.Types.INTEGER);
                }
                ps.setString(2, trimmedContent);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        // 发现重复，不插入
                        AiAgentActivity.debugLog("[Memory] 检测到重复消息，跳过存储: " + trimmedContent.substring(0, Math.min(50, trimmedContent.length())));
                        return;
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[Memory] 去重检查失败: " + e.toString());
            }
            
            // 插入新消息
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO conversations (role, content, source_type, source_id, sender_id, sender_name, created_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, role);
                ps.setString(2, trimmedContent);
                ps.setString(3, sourceType);
                ps.setLong(4, sourceId);
                if (senderId != null) {
                    ps.setLong(5, senderId);
                } else {
                    ps.setNull(5, java.sql.Types.INTEGER);
                }
                ps.setString(6, senderName);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}

            // 裁剪：每 50 次写入才裁剪一次（保留最近 CONV_MAX 条），避免每次写入都执行 DELETE 子查询
            if (++conversationWrites >= 50) {
                conversationWrites = 0;
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE FROM conversations WHERE id NOT IN " +
                            "(SELECT id FROM conversations ORDER BY created_at DESC LIMIT " + CONV_MAX + ")");
                } catch (SQLException ignored) {}
            }
        }
    }

    /** 
     * 获取最近的对话历史（可过滤来源）
     * @param limit 数量限制
     * @param sourceType 来源类型（null表示不限制）
     * @param sourceId 来源ID（null表示不限制）
     */
    public List<String[]> getRecentConversations(int limit, String sourceType, Long sourceId) {
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try {
                String sql = "SELECT role, content, source_type, source_id, sender_id, sender_name FROM conversations WHERE 1=1";
                List<Object> params = new ArrayList<>();
                
                if (sourceType != null) {
                    sql += " AND source_type = ?";
                    params.add(sourceType);
                }
                if (sourceId != null) {
                    sql += " AND source_id = ?";
                    params.add(sourceId);
                }
                sql += " ORDER BY created_at DESC LIMIT ?";
                params.add(limit);
                
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++) {
                        if (params.get(i) instanceof String) {
                            ps.setString(i + 1, (String) params.get(i));
                        } else if (params.get(i) instanceof Long) {
                            ps.setLong(i + 1, (Long) params.get(i));
                        } else if (params.get(i) instanceof Integer) {
                            ps.setInt(i + 1, (Integer) params.get(i));
                        }
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            list.add(new String[] { 
                                rs.getString(1),  // role
                                rs.getString(2),  // content
                                rs.getString(3),  // source_type
                                String.valueOf(rs.getLong(4)),  // source_id
                                rs.getString(5),  // sender_id
                                rs.getString(6)   // sender_name
                            });
                        }
                    }
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 获取全局最近对话（不过滤） */
    public List<String[]> getGlobalRecentConversations(int limit) {
        return getRecentConversations(limit, null, null);
    }

    /** 获取特定群的对话历史 */
    public List<String[]> getGroupConversations(long groupId, int limit) {
        return getRecentConversations(limit, "group", groupId);
    }

    /** 获取与特定用户的私聊历史 */
    public List<String[]> getPrivateConversations(long userId, int limit) {
        return getRecentConversations(limit, "private", userId);
    }

    /** 获取某用户在群聊中的最近发言（用于印象蒸馏，返回 [nickname, content]，时间升序）。 */
    public List<String[]> getUserGroupMessages(long userId, int limit) {
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT nickname, content FROM group_chat_history WHERE user_id = ? ORDER BY created_at DESC LIMIT ?")) {
                ps.setLong(1, userId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String[]> temp = new ArrayList<>();
                    while (rs.next()) {
                        temp.add(new String[] { rs.getString(1), rs.getString(2) });
                    }
                    for (int i = temp.size() - 1; i >= 0; i--) list.add(temp.get(i));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 给对话历史中该来源最近一条相同内容的 user 消息打 Mark 备注（AI 自用，标记已处理）。 */
    public void setConversationMark(String sourceType, long sourceId, String content, String mark) {
        if (content == null || content.trim().isEmpty() || mark == null || mark.trim().isEmpty()) return;
        synchronized (lock) {
            long targetId = -1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM conversations WHERE role = 'user' AND source_type = ? AND source_id = ? AND content = ? ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, sourceType);
                ps.setLong(2, sourceId);
                ps.setString(3, content.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) targetId = rs.getLong(1);
                }
            } catch (SQLException ignored) {}
            if (targetId <= 0) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE conversations SET mark = ? WHERE id = ?")) {
                ps.setString(1, mark.trim());
                ps.setLong(2, targetId);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    /** 查询对话历史中该来源最近一条相同内容 user 消息的 Mark 备注（无则返回 null）。 */
    public String getConversationMark(String sourceType, long sourceId, String content) {
        if (content == null || content.trim().isEmpty()) return null;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT mark FROM conversations WHERE role = 'user' AND source_type = ? AND source_id = ? AND content = ? AND mark IS NOT NULL ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, sourceType);
                ps.setLong(2, sourceId);
                ps.setString(3, content.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    // ==================== AI独立记忆 ====================

    /** 添加一条AI记忆 */
    public void addMemory(String content) {
        if (content == null || content.trim().isEmpty()) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO memories (content, created_at) VALUES (?,?)")) {
                ps.setString(1, content.trim());
                ps.setLong(2, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}

            // 裁剪：保留最近 MEM_MAX 条
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM memories WHERE id NOT IN " +
                        "(SELECT id FROM memories ORDER BY created_at DESC LIMIT " + MEM_MAX + ")");
            } catch (SQLException ignored) {}
        }
    }

    /** 列出所有记忆 */
    public List<String> listMemories() {
        List<String> list = new ArrayList<>();
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT content FROM memories ORDER BY created_at DESC")) {
                while (rs.next()) {
                    list.add(rs.getString(1));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 统一对话历史条数。 */
    public int countConversations() {
        return countTable("conversations");
    }

    /** QQ 通道 AI 独立记忆条数。 */
    public int countMemories() {
        return countTable("memories");
    }

    /** 群聊完整历史条数。 */
    public int countGroupHistory() {
        return countTable("group_chat_history");
    }

    /** 通用表计数，失败返回 0。 */
    private int countTable(String table) {
        if (conn == null || table == null || table.trim().isEmpty()) return 0;
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException ignored) {
                return 0;
            }
        }
    }

    /** 搜索相关记忆（FTS5 全文搜索 + LIKE 回退） */
    public List<String> searchMemories(String query, int maxResults) {
        List<String> list = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return list;
        synchronized (lock) {
            // 优先 FTS5：清理特殊字符后执行全文搜索
            String ftsQuery = query.trim()
                .replaceAll("[*\"()\\-:]", " ")
                .replaceAll("\\s+", " OR ")
                .trim();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT m.content FROM memories_fts f " +
                    "JOIN memories m ON f.rowid = m.id " +
                    "WHERE memories_fts MATCH ? ORDER BY rank LIMIT ?")) {
                ps.setString(1, ftsQuery);
                ps.setInt(2, maxResults);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString(1));
                    }
                }
            } catch (SQLException ftsErr) {
                // FTS5 失败回退 LIKE
                list.clear();
                String pattern = "%" + query.trim() + "%";
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT content FROM memories WHERE content LIKE ? ORDER BY created_at DESC LIMIT ?")) {
                    ps.setString(1, pattern);
                    ps.setInt(2, maxResults);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            list.add(rs.getString(1));
                        }
                    }
                } catch (SQLException ignored) {}
            }
        }
        return list;
    }

    /** 跨群全文检索群聊历史（返回 "[群X] 昵称: 内容" 格式化结果，用于群与群之间的记忆共享） */
    public List<String> searchGroupChatHistory(String query, int maxResults) {
        List<String> list = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return list;
        String q = query.trim();
        synchronized (lock) {
            LinkedHashSet<String> matched = new LinkedHashSet<>();
            // 1. FTS5 全文检索（英文/空格分词）
            String ftsQuery = q.replaceAll("[*\"()\\-:]", " ")
                    .replaceAll("\\s+", " OR ").trim();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT gch.nickname, gch.group_id, gch.content FROM group_chat_history_fts f " +
                    "JOIN group_chat_history gch ON f.rowid = gch.id " +
                    "WHERE group_chat_history_fts MATCH ? ORDER BY rank LIMIT ?")) {
                ps.setString(1, ftsQuery);
                ps.setInt(2, maxResults);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next() && matched.size() < maxResults) {
                        matched.add(formatHistoryHit(rs));
                    }
                }
            } catch (SQLException ignored) {}
            // 2. 中文 N-gram 子串匹配（覆盖无空格中文长句的关键词召回，单次 SQL）
            if (matched.size() < maxResults) {
                List<String> grams = extractNGrams(q);
                if (!grams.isEmpty()) {
                    StringBuilder where = new StringBuilder("content LIKE ?");
                    for (int i = 1; i < grams.size(); i++) where.append(" OR content LIKE ?");
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT nickname, group_id, content FROM group_chat_history WHERE " +
                            where + " ORDER BY created_at DESC LIMIT ?")) {
                        for (int i = 0; i < grams.size(); i++) ps.setString(i + 1, "%" + grams.get(i) + "%");
                        ps.setInt(grams.size() + 1, maxResults);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next() && matched.size() < maxResults) {
                                matched.add(formatHistoryHit(rs));
                            }
                        }
                    } catch (SQLException ignored) {}
                }
            }
            list.addAll(matched);
        }
        return list;
    }

    /** 按关键词检索 conversations 表（跨用户/群/私聊对话历史），返回 "[群X]/[私聊X] 昵称: 内容" 格式化结果 */
    public List<String> searchConversations(String query, int maxResults) {
        List<String> list = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return list;
        String q = query.trim();
        synchronized (lock) {
            LinkedHashSet<String> matched = new LinkedHashSet<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT role, content, source_type, source_id, sender_name FROM conversations " +
                    "WHERE content LIKE ? ORDER BY created_at DESC LIMIT ?")) {
                ps.setString(1, "%" + q + "%");
                ps.setInt(2, maxResults);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String role = rs.getString(1);
                        String content = rs.getString(2);
                        String sourceType = rs.getString(3);
                        long sourceId = rs.getLong(4);
                        String senderName = rs.getString(5);
                        if (content == null) content = "";
                        if (content.length() > 160) content = content.substring(0, 160) + "...";
                        String label = "group".equals(sourceType) ? "[群" + sourceId + "]" : "[私聊" + sourceId + "]";
                        String who = "assistant".equals(role) ? "Bot" : (senderName != null && !senderName.isEmpty() ? senderName : "用户");
                        matched.add(label + " " + who + ": " + content);
                    }
                }
            } catch (SQLException ignored) {}
            list.addAll(matched);
        }
        return list;
    }

    /** 将历史命中行格式化为 "[群X] 昵称: 内容"（截断内容长度） */
    private String formatHistoryHit(ResultSet rs) throws SQLException {
        String nick = rs.getString(1);
        long groupId = rs.getLong(2);
        String content = rs.getString(3);
        if (content == null) content = "";
        if (content.length() > 160) content = content.substring(0, 160) + "...";
        return "[群" + groupId + "] " + (nick != null && !nick.isEmpty() ? nick : "某人") + ": " + content;
    }

    /** 提取中文 N-gram（2-gram 与 3-gram），用于无空格中文长句的关键词召回 */
    private List<String> extractNGrams(String text) {
        LinkedHashSet<String> grams = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        String t = text.replaceAll("[\\s\\p{Punct}]+", "");
        for (int n = 2; n <= 3; n++) {
            for (int i = 0; i + n <= t.length(); i++) {
                grams.add(t.substring(i, i + n));
            }
        }
        List<String> result = new ArrayList<>(grams);
        // 限制数量（最多 16 个），避免 SQL 过长
        int limit = Math.min(result.size(), 16);
        if (result.size() > limit) {
            result = new ArrayList<>(result.subList(result.size() - limit, result.size()));
        }
        return result;
    }

    // ==================== 群聊历史记录（持久化） ====================

    /** 添加一条群聊消息到完整历史（持久化，不限制数量） */
    public void addGroupChatMessage(long userId, String nickname, String content, long groupId) {
        addGroupChatMessage(userId, nickname, content, groupId, 0L);
    }

    /** 添加一条群聊消息到完整历史，并保存 OneBot message_id。 */
    public void addGroupChatMessage(long userId, String nickname, String content, long groupId, long messageId) {
        if (content == null || content.trim().isEmpty()) return;
        String displayNick = nickname != null ? nickname : "未知用户";
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO group_chat_history (user_id, nickname, content, group_id, message_id, created_at) VALUES (?,?,?,?,?,?)")) {
                ps.setLong(1, userId);
                ps.setString(2, displayNick);
                ps.setString(3, content.trim());
                ps.setLong(4, groupId);
                ps.setLong(5, messageId);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            
            // 同时更新昵称-QQ映射
            recordGroupNickname(groupId, userId, displayNick);
        }
    }
    
    /** 记录群昵称→QQ号映射 */
    private void recordGroupNickname(long groupId, long userId, String nickname) {
        // 昵称未变化则跳过写库（昵称变化不频繁，避免每条群消息都 REPLACE）
        Map<Long, String> nicks = memberNickCache.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());
        String prev = nicks.get(userId);
        if (nickname != null && nickname.equals(prev)) return;
        try (PreparedStatement ps = conn.prepareStatement(
                "REPLACE INTO group_nicknames (group_id, user_id, nickname, updated_at) VALUES (?,?,?,?)")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            ps.setString(3, nickname);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
        if (nickname != null) nicks.put(userId, nickname);
        // 不在此失效群缓存：本方法经 addGroupChatMessage 每条群消息都调用，失效会让缓存形同虚设。
        // 昵称变化不频繁，60s TTL 缓存自动刷新即可。
    }
    
    /** 根据群号和昵称查找QQ号 */
    public Long findUserIdByNickname(long groupId, String nickname) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id FROM group_nicknames WHERE group_id=? AND nickname=? ORDER BY updated_at DESC LIMIT 1")) {
                ps.setLong(1, groupId);
                ps.setString(2, nickname);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getLong(1);
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }
    
    /** 获取群内所有昵称映射（昵称→QQ号）；60s 短缓存。 */
    public java.util.Map<String, Long> getGroupNicknameMap(long groupId) {
        java.util.Map<String, Long> cached = getCached(groupNickMapCache, groupId);
        if (cached != null) return cached;
        java.util.Map<String, Long> map = new java.util.LinkedHashMap<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT nickname, user_id FROM group_nicknames WHERE group_id=? ORDER BY updated_at DESC")) {
                ps.setLong(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String nick = rs.getString(1);
                        Long uid = rs.getLong(2);
                        if (!map.containsKey(nick)) {
                            map.put(nick, uid);
                        }
                    }
                }
            } catch (SQLException ignored) {}
        }
        putCached(groupNickMapCache, groupId, map);
        return map;
    }

    /** 获取特定群的最近N条消息 */
    public List<String[]> getRecentGroupChatHistory(long groupId, int limit) {
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, nickname, content, created_at FROM group_chat_history WHERE group_id = ? ORDER BY created_at DESC LIMIT ?")) {
                ps.setLong(1, groupId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String[]> tempList = new ArrayList<>();
                    while (rs.next()) {
                        tempList.add(new String[] {
                            String.valueOf(rs.getLong(1)),
                            rs.getString(2),
                            rs.getString(3),
                            String.valueOf(rs.getLong(4))
                        });
                    }
                    // 反转为时间升序
                    for (int i = tempList.size() - 1; i >= 0; i--) {
                        list.add(tempList.get(i));
                    }
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 获取特定群的最近N条消息（含 Mark 备注，返回 [user_id, nickname, content, mark]，mark 可能为 null），时间升序。 */
    public List<String[]> getRecentGroupChatHistoryWithMark(long groupId, int limit) {
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, nickname, content, mark FROM group_chat_history WHERE group_id = ? ORDER BY created_at DESC LIMIT ?")) {
                ps.setLong(1, groupId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String[]> tempList = new ArrayList<>();
                    while (rs.next()) {
                        tempList.add(new String[] {
                            String.valueOf(rs.getLong(1)),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4)
                        });
                    }
                    for (int i = tempList.size() - 1; i >= 0; i--) {
                        list.add(tempList.get(i));
                    }
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 获取特定群的最近N条消息（含 Mark 和 message_id，返回 [user_id, nickname, content, mark, message_id]），时间升序。 */
    public List<String[]> getRecentGroupChatHistoryWithMessageId(long groupId, int limit) {
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, nickname, content, mark, message_id FROM group_chat_history WHERE group_id = ? ORDER BY created_at DESC LIMIT ?")) {
                ps.setLong(1, groupId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<String[]> tempList = new ArrayList<>();
                    while (rs.next()) {
                        tempList.add(new String[] {
                            String.valueOf(rs.getLong(1)),
                            rs.getString(2),
                            rs.getString(3),
                            rs.getString(4),
                            String.valueOf(rs.getLong(5))
                        });
                    }
                    for (int i = tempList.size() - 1; i >= 0; i--) {
                        list.add(tempList.get(i));
                    }
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }
    
    /** 给群聊历史中「该群该用户最近一条相同内容」的消息打 Mark 备注（AI 自用，标记已处理）。 */
    public void setGroupMessageMark(long groupId, long userId, String content, String mark) {
        if (content == null || content.trim().isEmpty() || mark == null || mark.trim().isEmpty()) return;
        synchronized (lock) {
            long targetId = -1;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM group_chat_history WHERE group_id = ? AND user_id = ? AND content = ? ORDER BY created_at DESC LIMIT 1")) {
                ps.setLong(1, groupId);
                ps.setLong(2, userId);
                ps.setString(3, content.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) targetId = rs.getLong(1);
                }
            } catch (SQLException ignored) {}
            if (targetId <= 0) return;
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE group_chat_history SET mark = ? WHERE id = ?")) {
                ps.setString(1, mark.trim());
                ps.setLong(2, targetId);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }
    
    /** 按 OneBot message_id 给群聊历史打 Mark 备注（引用回复语义回填：标记该消息已被 Bot 回复）。 */
    public void setGroupMessageMarkById(long groupId, long messageId, String mark) {
        if (groupId <= 0 || messageId <= 0 || mark == null || mark.trim().isEmpty()) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE group_chat_history SET mark = ? WHERE group_id = ? AND message_id = ?")) {
                ps.setString(1, mark.trim());
                ps.setLong(2, groupId);
                ps.setLong(3, messageId);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    /** 查询群聊历史中该用户最近一条相同内容消息的 Mark 备注（无则返回 null）。 */
    public String getGroupMessageMark(long groupId, long userId, String content) {
        if (content == null || content.trim().isEmpty()) return null;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT mark FROM group_chat_history WHERE group_id = ? AND user_id = ? AND content = ? AND mark IS NOT NULL ORDER BY created_at DESC LIMIT 1")) {
                ps.setLong(1, groupId);
                ps.setLong(2, userId);
                ps.setString(3, content.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }
    
    /** 获取全局最近的群聊历史（跨群，按时间倒序，排除指定群），用于跨群续聊上下文 */
    public List<String> getRecentGroupChatHistoryGlobal(int limit, long excludeGroupId) {
        List<String> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT nickname, group_id, content FROM group_chat_history " +
                    "WHERE group_id != ? ORDER BY created_at DESC LIMIT ?")) {
                ps.setLong(1, excludeGroupId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(formatHistoryHit(rs));
                    }
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    // ==================== 群成员角色管理 ====================

    /** 记录群成员角色（来自消息 sender.role） */
    public void recordGroupMemberRole(long groupId, long userId, String nickname, String role) {
        if (role == null || role.isEmpty()) return;
        String displayNick = nickname != null ? nickname : "未知";
        // 角色未变化则跳过写库（角色变化不频繁，避免每条群消息都 REPLACE）
        Map<Long, String> roles = memberRoleCache.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>());
        String prev = roles.get(userId);
        if (role.equals(prev)) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "REPLACE INTO group_members (group_id, user_id, nickname, role, updated_at) VALUES (?,?,?,?,?)")) {
                ps.setLong(1, groupId);
                ps.setLong(2, userId);
                ps.setString(3, displayNick);
                ps.setString(4, role);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
        roles.put(userId, role);
        // 不在此失效群缓存：本方法每条群消息都调用，失效会让 getGroupAdmins/getGroupNicknameMap 缓存形同虚设。
        // 角色变化不频繁，60s TTL 缓存自动刷新即可。
    }

    /** 获取群内管理员和群主列表（返回 [userId, nickname, role]）；60s 短缓存。 */
    public List<String[]> getGroupAdmins(long groupId) {
        List<String[]> cached = getCached(groupAdminsCache, groupId);
        if (cached != null) return cached;
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, nickname, role FROM group_members WHERE group_id=? AND role IN ('owner','admin') ORDER BY role='owner' DESC, updated_at DESC")) {
                ps.setLong(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new String[] {
                            String.valueOf(rs.getLong(1)),
                            rs.getString(2),
                            rs.getString(3)
                        });
                    }
                }
            } catch (SQLException ignored) {}
        }
        putCached(groupAdminsCache, groupId, list);
        return list;
    }

    /** 根据个人昵称（跨群/私聊）查找QQ号 */
    public Long findUserIdByPersonalNickname(String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) return null;
        String q = nickname.trim();
        synchronized (lock) {
            // 1. 从对话历史中搜索（私聊+群聊）
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT sender_id FROM conversations WHERE sender_name=? AND sender_id IS NOT NULL ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, q);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
            } catch (SQLException ignored) {}
            
            // 2. 从 group_nicknames 表搜索
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id FROM group_nicknames WHERE nickname=? ORDER BY updated_at DESC LIMIT 1")) {
                ps.setString(1, q);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
            } catch (SQLException ignored) {}
            
            // 3. 从群聊历史搜索
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id FROM group_chat_history WHERE nickname=? ORDER BY created_at DESC LIMIT 1")) {
                ps.setString(1, q);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return rs.getLong(1); }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    /** 获取跨群个人昵称→QQ映射表（从私聊+群聊历史提取） */
    public Map<String, Long> getPersonalNicknameMap() {
        Map<String, Long> cached = cachedPersonalNickMap;
        if (cached != null && System.currentTimeMillis() - cachedPersonalNickMapTime < GROUP_CACHE_TTL_MS) return cached;
        Map<String, Long> map = new LinkedHashMap<>();
        synchronized (lock) {
            // GROUP BY 避免 DISTINCT+非SELECT列ORDER BY 的歧义
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT sender_name, sender_id, MAX(created_at) FROM conversations WHERE sender_name IS NOT NULL AND sender_name!='' AND sender_id IS NOT NULL AND sender_id!=0 GROUP BY sender_name, sender_id ORDER BY 3 DESC LIMIT 30")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    Long uid = rs.getLong(2);
                    if (!map.containsKey(name)) {
                        map.put(name, uid);
                    }
                }
            } catch (SQLException ignored) {}
        }
        cachedPersonalNickMap = map;
        cachedPersonalNickMapTime = System.currentTimeMillis();
        return map;
    }

    // ==================== 状态 ====================

    /** 设置键值状态 */
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

    /** 获取键值状态 */
    public String getState(String key) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT value FROM app_state WHERE key=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    /** 缓存群名 */
    public void setGroupName(long groupId, String name) {
        if (name != null && !name.trim().isEmpty()) {
            setState("group_name_" + groupId, name.trim());
        }
        groupNameCache.remove(groupId);
    }

    /** 获取缓存的群名（无缓存返回 null）；60s 短缓存。 */
    public String getGroupName(long groupId) {
        String cached = getCached(groupNameCache, groupId);
        if (cached != null) return cached;
        String name = getState("group_name_" + groupId);
        if (name != null) putCached(groupNameCache, groupId, name);
        return name;
    }

    /** 获取所有已知群（群号→群名），从 app_state 表 group_name_ 前缀查询。 */
    public Map<Long, String> getAllKnownGroups() {
        Map<Long, String> result = new LinkedHashMap<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT key, value FROM app_state WHERE key LIKE 'group_name_%'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        String name = rs.getString(2);
                        if (key == null || key.length() <= "group_name_".length()) continue;
                        String idStr = key.substring("group_name_".length());
                        try {
                            result.put(Long.parseLong(idStr), name);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (SQLException ignored) {}
        }
        return result;
    }

    /** 缓存好友昵称（备注优先） */
    public void setFriendName(long userId, String name) {
        if (name != null && !name.trim().isEmpty()) {
            setState("friend_name_" + userId, name.trim());
        }
    }

    /** 获取缓存的好友昵称（无缓存返回 null） */
    public String getFriendName(long userId) {
        return getState("friend_name_" + userId);
    }

    /** 获取所有已知好友（QQ号→昵称），从 app_state 表 friend_name_ 前缀查询。 */
    public Map<Long, String> getAllKnownFriends() {
        Map<Long, String> result = new LinkedHashMap<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT key, value FROM app_state WHERE key LIKE 'friend_name_%'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String key = rs.getString(1);
                        String name = rs.getString(2);
                        if (key == null || key.length() <= "friend_name_".length()) continue;
                        String idStr = key.substring("friend_name_".length());
                        try {
                            result.put(Long.parseLong(idStr), name);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } catch (SQLException ignored) {}
        }
        return result;
    }
}
