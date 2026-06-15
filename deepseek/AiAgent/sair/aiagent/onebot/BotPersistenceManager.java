package sair.aiagent.onebot;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import sair.aiagent.AiAgentActivity;

/**
 * Bot规则和数据持久化管理器。
 * <p>
 * 负责将以下数据持久化到SQLite数据库：
 * - 黑名单列表
 * - 警告记录
 * - 自定义规则配置
 * - Bot行为日志
 * </p>
 */
public class BotPersistenceManager {
    
    private Connection conn;
    private final String dbPath;
    private final Object lock = new Object();
    
    // 内存缓存（提高性能）
    private final Map<Long, String> blockedUsersCache = new ConcurrentHashMap<>();
    private final Map<Long, Integer> warningCountsCache = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastWarningTimeCache = new ConcurrentHashMap<>();
    
    public BotPersistenceManager(String dataDir) {
        this.dbPath = dataDir + File.separator + "bot_rules.db";
    }
    
    /**
     * 初始化数据库
     */
    public void init() {
        synchronized (lock) {
            try {
                conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
                createTables();
                loadCacheFromDatabase();
                AiAgentActivity.debugLog("[BotPersistence] 数据库初始化完成: " + dbPath);
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 数据库初始化失败: " + e.toString());
            }
        }
    }
    
    /**
     * 创建表结构
     */
    private void createTables() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // 黑名单表
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS blocked_users (" +
                "  user_id INTEGER PRIMARY KEY," +
                "  reason TEXT NOT NULL," +
                "  blocked_at INTEGER NOT NULL," +
                "  blocked_by TEXT DEFAULT 'bot'" +
                ")"
            );
            
            // 警告记录表
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS warnings (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  user_id INTEGER NOT NULL," +
                "  group_id INTEGER DEFAULT 0," +
                "  reason TEXT NOT NULL," +
                "  warned_at INTEGER NOT NULL," +
                "  is_active INTEGER DEFAULT 1" +
                ")"
            );
            
            // 自定义规则表
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS custom_rules (" +
                "  rule_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  rule_name TEXT NOT NULL UNIQUE," +
                "  rule_type TEXT NOT NULL," +
                "  rule_config TEXT," +
                "  enabled INTEGER DEFAULT 1," +
                "  created_at INTEGER NOT NULL" +
                ")"
            );
            
            // 行为日志表
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS action_logs (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  action_type TEXT NOT NULL," +
                "  target_id INTEGER," +
                "  group_id INTEGER DEFAULT 0," +
                "  details TEXT," +
                "  performed_at INTEGER NOT NULL" +
                ")"
            );
            
            // 好感度表
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS user_affections (" +
                "  user_id INTEGER PRIMARY KEY," +
                "  affection INTEGER NOT NULL DEFAULT 0," +
                "  last_interaction_at INTEGER NOT NULL," +
                "  updated_at INTEGER NOT NULL" +
                ")"
            );
            
            // 恋爱关系表
            stmt.execute(
                "CREATE TABLE IF NOT EXISTS romance_relations (" +
                "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "  user_id INTEGER NOT NULL UNIQUE," +
                "  status TEXT NOT NULL," +  // current, ex, betrayer
                "  start_time INTEGER," +
                "  end_time INTEGER," +
                "  created_at INTEGER NOT NULL" +
                ")"
            );
            
            // 创建索引
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_warnings_user ON warnings(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_warnings_active ON warnings(is_active)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_logs_time ON action_logs(performed_at)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_affections_user ON user_affections(user_id)");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_romance_user ON romance_relations(user_id)");
        }
    }
    
    /**
     * 从数据库加载缓存
     */
    private void loadCacheFromDatabase() {
        try {
            // 加载黑名单
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, reason FROM blocked_users")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        blockedUsersCache.put(rs.getLong(1), rs.getString(2));
                    }
                }
            }
            
            // 加载活跃警告计数
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, COUNT(*) as count, MAX(warned_at) as last_time " +
                    "FROM warnings WHERE is_active = 1 GROUP BY user_id")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long userId = rs.getLong(1);
                        warningCountsCache.put(userId, rs.getInt(2));
                        lastWarningTimeCache.put(userId, rs.getLong(3));
                    }
                }
            }
            
            AiAgentActivity.debugLog("[BotPersistence] 缓存加载完成: 黑名单=" + 
                blockedUsersCache.size() + ", 警告用户=" + warningCountsCache.size());
                
        } catch (SQLException e) {
            AiAgentActivity.debugLog("[BotPersistence] 缓存加载失败: " + e.toString());
        }
    }
    
    // ==================== 黑名单操作 ====================
    
    /**
     * 检查用户是否在黑名单中
     */
    public boolean isBlocked(long userId) {
        return blockedUsersCache.containsKey(userId);
    }
    
    /**
     * 获取拉黑原因
     */
    public String getBlockReason(long userId) {
        return blockedUsersCache.get(userId);
    }
    
    /**
     * 添加用户到黑名单
     */
    public void blockUser(long userId, String reason, String blockedBy) {
        if (isBlocked(userId)) {
            return; // 已存在
        }
        
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO blocked_users (user_id, reason, blocked_at, blocked_by) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, userId);
                ps.setString(2, reason);
                ps.setLong(3, System.currentTimeMillis());
                ps.setString(4, blockedBy != null ? blockedBy : "bot");
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 添加黑名单失败: " + e.toString());
                return;
            }
        }
        
        // 更新缓存
        blockedUsersCache.put(userId, reason);
        
        // 记录日志
        logAction("block_user", userId, 0, "原因: " + reason);
        
        AiAgentActivity.debugLog("[BotPersistence] 已拉黑用户 " + userId + ": " + reason);
    }
    
    /**
     * 从黑名单移除用户
     */
    public void unblockUser(long userId) {
        if (!isBlocked(userId)) {
            return;
        }
        
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM blocked_users WHERE user_id = ?")) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 移除黑名单失败: " + e.toString());
                return;
            }
        }
        
        // 更新缓存
        blockedUsersCache.remove(userId);
        
        // 记录日志
        logAction("unblock_user", userId, 0, null);
        
        AiAgentActivity.debugLog("[BotPersistence] 已解除对用户 " + userId + " 的拉黑");
    }
    
    /**
     * 获取所有黑名单用户
     */
    public Map<Long, String> getAllBlockedUsers() {
        return Collections.unmodifiableMap(blockedUsersCache);
    }
    
    // ==================== 警告操作 ====================
    
    /**
     * 添加警告记录
     */
    public int addWarning(long userId, long groupId, String reason) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO warnings (user_id, group_id, reason, warned_at, is_active) VALUES (?, ?, ?, ?, 1)")) {
                ps.setLong(1, userId);
                ps.setLong(2, groupId);
                ps.setString(3, reason);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 添加警告失败: " + e.toString());
                return 0;
            }
        }
        
        // 更新缓存
        int newCount = warningCountsCache.getOrDefault(userId, 0) + 1;
        warningCountsCache.put(userId, newCount);
        lastWarningTimeCache.put(userId, System.currentTimeMillis());
        
        // 记录日志
        logAction("warn_user", userId, groupId, "原因: " + reason + ", 累计警告: " + newCount);
        
        AiAgentActivity.debugLog("[BotPersistence] 警告用户 " + userId + " (第" + newCount + "次): " + reason);
        
        return newCount;
    }
    
    /**
     * 清除用户的活跃警告
     */
    public void clearWarnings(long userId) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE warnings SET is_active = 0 WHERE user_id = ? AND is_active = 1")) {
                ps.setLong(1, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 清除警告失败: " + e.toString());
                return;
            }
        }
        
        // 更新缓存
        warningCountsCache.remove(userId);
        lastWarningTimeCache.remove(userId);
        
        // 记录日志
        logAction("clear_warnings", userId, 0, null);
        
        AiAgentActivity.debugLog("[BotPersistence] 已清除用户 " + userId + " 的警告记录");
    }
    
    /**
     * 获取警告次数
     */
    public int getWarningCount(long userId) {
        return warningCountsCache.getOrDefault(userId, 0);
    }
    
    /**
     * 获取所有警告计数
     */
    public Map<Long, Integer> getAllWarningCounts() {
        synchronized (lock) {
            return new HashMap<>(warningCountsCache);
        }
    }
    
    /**
     * 获取最后警告时间
     */
    public Long getLastWarningTime(long userId) {
        return lastWarningTimeCache.get(userId);
    }
    
    /**
     * 获取用户的警告历史
     */
    public List<Map<String, Object>> getWarningHistory(long userId, int limit) {
        List<Map<String, Object>> history = new ArrayList<>();
        
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT group_id, reason, warned_at FROM warnings " +
                    "WHERE user_id = ? ORDER BY warned_at DESC LIMIT ?")) {
                ps.setLong(1, userId);
                ps.setInt(2, limit);
                
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> record = new HashMap<>();
                        record.put("group_id", rs.getLong(1));
                        record.put("reason", rs.getString(2));
                        record.put("warned_at", rs.getLong(3));
                        history.add(record);
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 查询警告历史失败: " + e.toString());
            }
        }
        
        return history;
    }
    
    // ==================== 自定义规则 ====================
    
    /**
     * 添加自定义规则
     */
    public void addCustomRule(String ruleName, String ruleType, String config) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO custom_rules (rule_name, rule_type, rule_config, enabled, created_at) " +
                    "VALUES (?, ?, ?, 1, ?)")) {
                ps.setString(1, ruleName);
                ps.setString(2, ruleType);
                ps.setString(3, config);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 添加规则失败: " + e.toString());
            }
        }
        
        AiAgentActivity.debugLog("[BotPersistence] 已添加规则: " + ruleName);
    }
    
    /**
     * 启用/禁用规则
     */
    public void setRuleEnabled(String ruleName, boolean enabled) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE custom_rules SET enabled = ? WHERE rule_name = ?")) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setString(2, ruleName);
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 更新规则状态失败: " + e.toString());
            }
        }
    }
    
    /**
     * 获取所有启用的规则
     */
    public List<Map<String, String>> getEnabledRules() {
        List<Map<String, String>> rules = new ArrayList<>();
        
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT rule_name, rule_type, rule_config FROM custom_rules WHERE enabled = 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, String> rule = new HashMap<>();
                        rule.put("name", rs.getString(1));
                        rule.put("type", rs.getString(2));
                        rule.put("config", rs.getString(3));
                        rules.add(rule);
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 查询规则失败: " + e.toString());
            }
        }
        
        return rules;
    }
    
    // ==================== 行为日志 ====================
    
    /**
     * 记录行为日志
     */
    public void logAction(String actionType, long targetId, long groupId, String details) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO action_logs (action_type, target_id, group_id, details, performed_at) " +
                    "VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, actionType);
                ps.setLong(2, targetId);
                ps.setLong(3, groupId);
                ps.setString(4, details);
                ps.setLong(5, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 记录日志失败: " + e.toString());
            }
        }
    }
    
    /**
     * 获取最近的行为日志
     */
    public List<Map<String, Object>> getRecentLogs(int limit) {
        List<Map<String, Object>> logs = new ArrayList<>();
        
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM action_logs ORDER BY performed_at DESC LIMIT ?")) {
                ps.setInt(1, limit);
                
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> log = new HashMap<>();
                        log.put("id", rs.getInt(1));
                        log.put("action_type", rs.getString(2));
                        log.put("target_id", rs.getLong(3));
                        log.put("group_id", rs.getLong(4));
                        log.put("details", rs.getString(5));
                        log.put("performed_at", rs.getLong(6));
                        logs.add(log);
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 查询日志失败: " + e.toString());
            }
        }
        
        return logs;
    }
    
    // ==================== 清理和维护 ====================
    
    /**
     * 清理旧日志（保留最近30天）
     */
    public void cleanupOldLogs() {
        long thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000);
        
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM action_logs WHERE performed_at < ?")) {
                ps.setLong(1, thirtyDaysAgo);
                int deleted = ps.executeUpdate();
                AiAgentActivity.debugLog("[BotPersistence] 已清理 " + deleted + " 条旧日志");
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 清理日志失败: " + e.toString());
            }
        }
    }
    
    /**
     * 关闭数据库连接
     */
    public void close() {
        synchronized (lock) {
            if (conn != null) {
                try {
                    conn.close();
                    AiAgentActivity.debugLog("[BotPersistence] 数据库连接已关闭");
                } catch (SQLException e) {
                    AiAgentActivity.debugLog("[BotPersistence] 关闭数据库失败: " + e.toString());
                }
                conn = null;
            }
        }
    }
    
    // ==================== 好感度持久化 ====================
    
    /**
     * 保存用户好感度
     */
    public void saveAffection(long userId, int affection, long lastInteractionTime) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO user_affections (user_id, affection, last_interaction_at, updated_at) VALUES (?, ?, ?, ?)")) {
                ps.setLong(1, userId);
                ps.setInt(2, affection);
                ps.setLong(3, lastInteractionTime);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 保存好感度失败: " + e.toString());
            }
        }
    }
    
    /**
     * 获取用户好感度
     */
    public int getAffection(long userId) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT affection FROM user_affections WHERE user_id = ?")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 查询好感度失败: " + e.toString());
            }
        }
        return 0; // 默认值
    }
    
    /**
     * 获取所有用户的好感度映射
     */
    public Map<Long, Integer> getAllAffections() {
        Map<Long, Integer> result = new HashMap<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id, affection FROM user_affections")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        result.put(rs.getLong(1), rs.getInt(2));
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 加载好感度失败: " + e.toString());
            }
        }
        return result;
    }
    
    // ==================== 恋爱关系持久化 ====================
    
    /**
     * 保存当前恋爱关系（确保全局唯一）
     */
    public void saveRomanceRelation(long userId, long startTime) {
        synchronized (lock) {
            try {
                // 先删除所有当前的恋爱关系（确保全局唯一）
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE romance_relations SET status = 'ex', end_time = ? WHERE status = 'current'")) {
                    ps.setLong(1, System.currentTimeMillis());
                    int updated = ps.executeUpdate();
                    if (updated > 0) {
                        AiAgentActivity.debugLog("[BotPersistence] 已结束之前的恋爱关系: 影响行数=" + updated);
                    }
                }
                
                // 插入新记录
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO romance_relations (user_id, status, start_time, created_at) VALUES (?, 'current', ?, ?)")) {
                    ps.setLong(1, userId);
                    ps.setLong(2, startTime);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                
                AiAgentActivity.debugLog("[BotPersistence] 已保存新的恋爱关系: userId=" + userId);
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 保存恋爱关系失败: " + e.toString());
            }
        }
    }
    
    /**
     * 结束恋爱关系（正常分手）
     */
    public void endRomanceRelation(long userId) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE romance_relations SET status = 'ex', end_time = ? WHERE user_id = ? AND status = 'current'")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 结束恋爱关系失败: " + e.toString());
            }
        }
    }
    
    /**
     * 标记为背叛者
     */
    public void markAsBetrayer(long userId) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE romance_relations SET status = 'betrayer', end_time = ? WHERE user_id = ?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 标记背叛者失败: " + e.toString());
            }
        }
    }
    
    /**
     * 检查用户是否是背叛者
     */
    public boolean isBetrayer(long userId) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM romance_relations WHERE user_id = ? AND status = 'betrayer'")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 查询背叛者失败: " + e.toString());
            }
        }
        return false;
    }
    
    /**
     * 检查用户是否是前任
     */
    public boolean isExPartner(long userId) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM romance_relations WHERE user_id = ? AND status = 'ex'")) {
                ps.setLong(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1) > 0;
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 查询前任失败: " + e.toString());
            }
        }
        return false;
    }
    
    /**
     * 获取当前恋爱对象ID
     */
    public Long getCurrentRomancePartner() {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id FROM romance_relations WHERE status = 'current' LIMIT 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[BotPersistence] 查询恋爱对象失败: " + e.toString());
            }
        }
        return null;
    }
}
