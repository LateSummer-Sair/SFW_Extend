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
 * QQ记忆管理器 —— 按QQ号隔离持久化。
 * <p>
 * 每个QQ号独立一个SQLite数据库文件，存储在 dataDir/qq_memories/{qq}.db。
 * 与原始 PersistenceManager 完全隔离，QQ记忆与SFW-AiAgent记忆互不干扰。
 * </p>
 *
 * <h3>表结构</h3>
 * <ul>
 *   <li>conversations — 对话历史 (id, role, content, created_at)</li>
 *   <li>memories — 记忆条目 (id, content, created_at)</li>
 *   <li>app_state — 键值状态</li>
 * </ul>
 */
public class QQMemoryManager {

    private static final int CONV_MAX = 200;
    private static final int MEM_MAX = 100;
    // 群聊历史记录不再限制数量，持久化保存所有

    private final String baseDir;
    private final long qqNumber;
    private Connection conn;
    private final Object lock = new Object();

    /**
     * 构造指定QQ号的记忆管理器。
     * @param baseDir QQ记忆根目录（如 dataDir/qq_memories/）
     * @param qqNumber QQ号
     */
    public QQMemoryManager(String baseDir, long qqNumber) {
        this.baseDir = baseDir;
        this.qqNumber = qqNumber;
    }

    /** 初始化数据库连接并建表。返回true如果已存在。 */
    public boolean init() {
        File dir = new File(baseDir);
        dir.mkdirs();
        File dbFile = new File(dir, qqNumber + ".db");
        boolean exists = dbFile.exists();
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
            throw new RuntimeException("初始化 QQ 记忆数据库失败: " + qqNumber, e);
        }
        return exists;
    }

    private void createTables() throws SQLException {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS conversations (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  role TEXT NOT NULL," +
                    "  content TEXT NOT NULL," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS memories (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  content TEXT NOT NULL," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS app_state (" +
                    "  key TEXT PRIMARY KEY," +
                    "  value TEXT NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );
                // 群聊历史记录表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS group_chat_history (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  user_id INTEGER NOT NULL," +
                    "  nickname TEXT," +
                    "  content TEXT NOT NULL," +
                    "  created_at INTEGER NOT NULL" +
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

    // ==================== 对话历史 ====================

    /** 添加一条对话消息 */
    public void addConversation(String role, String content) {
        if (content == null || content.trim().isEmpty()) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO conversations (role, content, created_at) VALUES (?,?,?)")) {
                ps.setString(1, role);
                ps.setString(2, content.trim());
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}

            // 裁剪：保留最近 CONV_MAX 条
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM conversations WHERE id NOT IN " +
                        "(SELECT id FROM conversations ORDER BY created_at DESC LIMIT " + CONV_MAX + ")");
            } catch (SQLException ignored) {}
        }
    }

    /** 获取最近N条对话消息 */
    public List<String[]> getRecentConversations(int limit) {
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT role, content FROM conversations ORDER BY created_at ASC LIMIT " + limit)) {
                while (rs.next()) {
                    list.add(new String[] { rs.getString(1), rs.getString(2) });
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 清空对话历史 */
    public void clearConversations() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM conversations");
            } catch (SQLException ignored) {}
        }
    }

    // ==================== 记忆 ====================

    /** 添加一条记忆 */
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

    /** 搜索相关记忆（LIKE匹配） */
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

    /** 清空所有记忆 */
    public void clearMemories() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM memories");
            } catch (SQLException ignored) {}
        }
    }

    // ==================== 群聊历史记录 ====================

    /** 添加一条群聊消息到历史记录（持久化保存，不限制数量） */
    public void addGroupChatMessage(long userId, String nickname, String content) {
        if (content == null || content.trim().isEmpty()) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO group_chat_history (user_id, nickname, content, created_at) VALUES (?,?,?,?)")) {
                ps.setLong(1, userId);
                ps.setString(2, nickname != null ? nickname : "未知用户");
                ps.setString(3, content.trim());
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            // 不再裁剪，持久化保存所有群聊历史
        }
    }

    /** 获取最近N条群聊消息（用于构建上下文） */
    public List<String[]> getRecentGroupChatHistory(int limit) {
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT user_id, nickname, content, created_at FROM group_chat_history ORDER BY created_at DESC LIMIT " + limit)) {
                // 注意：这里按DESC查询，然后反转，保证返回的顺序是时间升序
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
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 清空群聊历史记录 */
    public void clearGroupChatHistory() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM group_chat_history");
            } catch (SQLException ignored) {}
        }
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

    /** 获取QQ号 */
    public long getQqNumber() {
        return qqNumber;
    }
}
