package sair.aiagent.core;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import sair.aiagent.AiAgentActivity;

/**
 * Memory Lifecycle Manager — 记忆生命周期管理器。
 * <p>
 * 参照 AI Agent 记忆系统设计文章，实现：
 * <ul>
 *   <li><b>重要性分级</b>：高(≥8)立即保护 / 中(5-8)正常 / 低(<5)可淘汰</li>
 *   <li><b>定期清理</b>：删除超过 max_age_days 且 importance < min_importance 的记忆</li>
 *   <li><b>数量阈值压缩</b>：记忆数 > 100 条时保留 70% 高重要性记忆</li>
 *   <li><b>守护线程</b>：每 30 分钟自动执行维护</li>
 * </ul>
 * </p>
 */
public class MemoryLifecycleManager {

    // === 配置参数 ===
    /** 最大记忆保存天数 */
    private static final int MAX_AGE_DAYS = 30;
    /** 最小保护重要性 */
    private static final int MIN_IMPORTANCE = 3;
    /** 压缩触发阈值（记忆总数） */
    private static final int COMPRESS_THRESHOLD = 100;
    /** 压缩保留比例 */
    private static final double COMPRESS_KEEP_RATIO = 0.7;
    /** 维护间隔（毫秒，30分钟） */
    private static final long MAINTENANCE_INTERVAL_MS = 30 * 60 * 1000L;

    private final PersistenceManager pm;
    private final Object lock = new Object();
    private volatile boolean running = false;
    private Thread maintenanceThread;

    public MemoryLifecycleManager(PersistenceManager pm) {
        this.pm = pm;
    }

    /** 获取 PersistenceManager 引用（AiAgentActivity 需要） */
    public PersistenceManager getPersistenceManager() {
        return pm;
    }

    // ==================== 启动/停止 ====================

    /** 启动定期维护守护线程 */
    public synchronized void start() {
        if (running) return;
        running = true;
        maintenanceThread = new Thread(new Runnable() {
            public void run() {
                AiAgentActivity.debugLog("[MemLifecycle] daemon started (interval=30min)");
                while (running) {
                    try { Thread.sleep(MAINTENANCE_INTERVAL_MS); } catch (InterruptedException e) { break; }
                    try {
                        int cleaned = cleanupOldMemories(MAX_AGE_DAYS, MIN_IMPORTANCE);
                        if (cleaned > 0) AiAgentActivity.debugLog("[MemLifecycle] cleaned " + cleaned + " old memories");
                        int compressed = compressIfNeeded(COMPRESS_THRESHOLD, COMPRESS_KEEP_RATIO);
                        if (compressed > 0) AiAgentActivity.debugLog("[MemLifecycle] compressed " + compressed + " memories");
                        // 清理旧 journal（超过 30 天）
                        int journalCleaned = cleanupOldJournal(30);
                        if (journalCleaned > 0) AiAgentActivity.debugLog("[MemLifecycle] cleaned " + journalCleaned + " old journal entries");
                    } catch (Exception e) {
                        AiAgentActivity.debugLog("[MemLifecycle] error: " + e.getMessage());
                    }
                }
                AiAgentActivity.debugLog("[MemLifecycle] daemon stopped");
            }
        }, "MemLifecycle");
        maintenanceThread.setDaemon(true);
        maintenanceThread.start();
    }

    /** 停止维护线程 */
    public synchronized void stop() {
        running = false;
        if (maintenanceThread != null) {
            maintenanceThread.interrupt();
        }
    }

    // ==================== 清理旧记忆 ====================

    /**
     * 清理旧的低重要性记忆。
     * 删除条件：created_at < cutoff（超过 maxAgeDays 天）且 importance < minImportance。
     *
     * @param maxAgeDays     最大保存天数
     * @param minImportance  最小保护重要性
     * @return 删除的记忆条数
     */
    public int cleanupOldMemories(int maxAgeDays, int minImportance) {
        long cutoff = System.currentTimeMillis() - (maxAgeDays * 86_400_000L);
        synchronized (lock) {
            try {
                java.sql.Connection conn = pm.getConnection();
                if (conn == null) return 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM memories WHERE created_at < ? AND importance < ?")) {
                    ps.setLong(1, cutoff);
                    ps.setInt(2, minImportance);
                    return ps.executeUpdate();
                }
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    // ==================== 数量阈值压缩 ====================

    /**
     * 当记忆总数超过阈值时，保留高重要性记忆。
     * 保留策略：按 importance DESC, created_at DESC 排序，保留前 keepRatio 比例。
     *
     * @param threshold  触发阈值
     * @param keepRatio  保留比例 (0-1)
     * @return 删除的记忆条数
     */
    public int compressIfNeeded(int threshold, double keepRatio) {
        synchronized (lock) {
            try {
                java.sql.Connection conn = pm.getConnection();
                if (conn == null) return 0;

                // 检查总数
                int total = 0;
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM memories")) {
                    if (rs.next()) total = rs.getInt(1);
                }
                if (total <= threshold) return 0;

                // 计算保留数量
                int keepCount = (int) (total * keepRatio);
                if (keepCount < 10) keepCount = 10; // 最少保留 10 条

                // 删除低排名记忆：保留 importance DESC, created_at DESC 的前 keepCount 条
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM memories WHERE id NOT IN (" +
                        "SELECT id FROM memories ORDER BY importance DESC, created_at DESC LIMIT ?)")) {
                    ps.setInt(1, keepCount);
                    return ps.executeUpdate();
                }
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    // ==================== 清理旧日志 ====================

    /** 清理超过指定天数的 journal 条目 */
    public int cleanupOldJournal(int maxAgeDays) {
        long cutoff = System.currentTimeMillis() - (maxAgeDays * 86_400_000L);
        synchronized (lock) {
            try {
                java.sql.Connection conn = pm.getConnection();
                if (conn == null) return 0;
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM journal WHERE created_at < ?")) {
                    ps.setLong(1, cutoff);
                    return ps.executeUpdate();
                }
            } catch (SQLException e) {
                return 0;
            }
        }
    }

    // ==================== 手动触发 ====================

    /** 手动触发一次完整维护（清理旧记忆 + 压缩 + 清理旧日志） */
    public String performMaintenance() {
        int cleaned = cleanupOldMemories(MAX_AGE_DAYS, MIN_IMPORTANCE);
        int compressed = compressIfNeeded(COMPRESS_THRESHOLD, COMPRESS_KEEP_RATIO);
        int journalCleaned = cleanupOldJournal(30);
        return String.format(
            "[Maintenance] memories: cleaned=%d, compressed=%d; journal: cleaned=%d",
            cleaned, compressed, journalCleaned);
    }

    /** 获取记忆统计信息 */
    public String getStats() {
        try {
            java.sql.Connection conn = pm.getConnection();
            if (conn == null) return "[MemLifecycle] DB not available";
            int total = 0;
            int highImportance = 0;
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM memories")) {
                    if (rs.next()) total = rs.getInt(1);
                }
                try (ResultSet rs = stmt.executeQuery(
                        "SELECT COUNT(*) FROM memories WHERE importance >= 8")) {
                    if (rs.next()) highImportance = rs.getInt(1);
                }
            }
            return String.format("[MemLifecycle] total=%d, high-importance(>=8)=%d, threshold=%d",
                total, highImportance, COMPRESS_THRESHOLD);
        } catch (SQLException e) {
            return "[MemLifecycle] error: " + e.getMessage();
        }
    }
}
