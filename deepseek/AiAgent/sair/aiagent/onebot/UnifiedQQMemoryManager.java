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
import java.util.List;
import java.util.Map;

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
        String displayNick = nickname != null ? nickname : "未知用户";
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO group_chat_history (user_id, nickname, content, group_id, created_at) VALUES (?,?,?,?,?)")) {
                ps.setLong(1, userId);
                ps.setString(2, displayNick);
                ps.setString(3, content.trim());
                ps.setLong(4, groupId);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException ignored) {}
            
            // 同时更新昵称-QQ映射
            recordGroupNickname(groupId, userId, displayNick);
        }
    }
    
    /** 记录群昵称→QQ号映射 */
    private void recordGroupNickname(long groupId, long userId, String nickname) {
        try (PreparedStatement ps = conn.prepareStatement(
                "REPLACE INTO group_nicknames (group_id, user_id, nickname, updated_at) VALUES (?,?,?,?)")) {
            ps.setLong(1, groupId);
            ps.setLong(2, userId);
            ps.setString(3, nickname);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
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
    
    /** 获取群内所有昵称映射（昵称→QQ号） */
    public java.util.Map<String, Long> getGroupNicknameMap(long groupId) {
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

    // ==================== 群成员角色管理 ====================

    /** 记录群成员角色（来自消息 sender.role） */
    public void recordGroupMemberRole(long groupId, long userId, String nickname, String role) {
        if (role == null || role.isEmpty()) return;
        String displayNick = nickname != null ? nickname : "未知";
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
    }

    /** 获取群内管理员和群主列表（返回 [userId, nickname, role]） */
    public List<String[]> getGroupAdmins(long groupId) {
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
    }

    /** 获取缓存的群名（无缓存返回 null） */
    public String getGroupName(long groupId) {
        return getState("group_name_" + groupId);
    }
}
