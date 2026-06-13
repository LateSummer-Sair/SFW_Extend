package sair.aiagent.onebot;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

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
 *   <li>app_state — 键值状态</li>
 * </ul>
 */
public class UnifiedQQMemoryManager {

    private static final int CONV_MAX = 500; // 对话历史保留最近500条
    private static final int MEM_MAX = 200;  // AI记忆保留最近200条
    
    private final String dbPath;
    private Connection conn;
    private final Object lock = new Object();

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
                    "  created_at INTEGER NOT NULL" +
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
                
                // 群聊完整历史表（持久化保存所有群消息）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS group_chat_history (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  user_id INTEGER NOT NULL," +
                    "  nickname TEXT," +
                    "  content TEXT NOT NULL," +
                    "  group_id INTEGER NOT NULL," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                
                // 应用状态表
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
     * 添加一条对话消息到统一历史
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
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO conversations (role, content, source_type, source_id, sender_id, sender_name, created_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, role);
                ps.setString(2, content.trim());
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

            // 裁剪：保留最近 CONV_MAX 条
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM conversations WHERE id NOT IN " +
                        "(SELECT id FROM conversations ORDER BY created_at DESC LIMIT " + CONV_MAX + ")");
            } catch (SQLException ignored) {}
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
                sql += " ORDER BY created_at ASC LIMIT ?";
                params.add(limit);
                
                PreparedStatement ps = conn.prepareStatement(sql);
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

    /** 搜索相关记忆 */
    public List<String> searchMemories(String query, int maxResults) {
        List<String> list = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return list;
        synchronized (lock) {
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
        return list;
    }

    // ==================== 群聊历史记录（持久化） ====================

    /** 添加一条群聊消息到完整历史（持久化，不限制数量） */
    public void addGroupChatMessage(long userId, String nickname, String content, long groupId) {
        if (content == null || content.trim().isEmpty()) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO group_chat_history (user_id, nickname, content, group_id, created_at) VALUES (?,?,?,?,?)")) {
                ps.setLong(1, userId);
                ps.setString(2, nickname != null ? nickname : "未知用户");
                ps.setString(3, content.trim());
                ps.setLong(4, groupId);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            // 不再裁剪，持久化保存所有群聊历史
        }
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
}
