package sair.aiagent.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.util.List;

import sair.aiagent.AiAgentActivity;

/**
 * 技能路径缓存与自适应权重路由。
 * <p>
 * 记录成功的标签执行序列(task → tags)，权重随成败动态升降。
 * 最短路径优先，失败降权换路，全失败进入探索模式。
 * </p>
 */
public class RouteCache {

    private final PersistenceManager pm;
    private final Object lock;

    public RouteCache(PersistenceManager pm, Object lock) {
        this.pm = pm;
        this.lock = lock;
    }

    /**
     * 查找任务的最佳路径。
     * @param task 任务描述文本（用于提取关键词hash）
     * @return 权重最高的 RouteEntry，无缓存返回 null
     */
    /** 时间衰减系数：每过期一天权重自然降至 0.95 */
    private static final double DECAY_PER_DAY = 0.95;

    public RouteEntry findRoute(String task) {
        if (pm == null || task == null || task.trim().isEmpty()) return null;
        String hash = hashTask(task);
        synchronized (lock) {
            try (PreparedStatement ps = pm.getConnection().prepareStatement(
                     "SELECT * FROM route_cache WHERE task_hash=? ORDER BY weight DESC LIMIT 1")) {
                ps.setString(1, hash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        RouteEntry route = mapRoute(rs);
                        // Apply time decay for unused routes
                        if (route.lastUsed != null && !route.lastUsed.isEmpty()) {
                            try {
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                                long lastUsedMs = sdf.parse(route.lastUsed).getTime();
                                long daysSince = (System.currentTimeMillis() - lastUsedMs) / 86_400_000L;
                                if (daysSince > 0) {
                                    route.weight *= Math.pow(DECAY_PER_DAY, daysSince);
                                    if (route.weight < 0.05) route.weight = 0.05;
                                }
                            } catch (Exception ignored) {}
                        }
                        return route;
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[RouteCache] findRoute error: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 记录或更新一条路径。
     * @param task   任务描述
     * @param tags   标签序列，用逗号分隔，如 "web,evaljs"
     * @param rounds 执行轮数
     * @param success 是否成功
     */
    public void recordRoute(String task, String tags, int rounds, boolean success) {
        if (pm == null || task == null || tags == null) return;
        String hash = hashTask(task);
        synchronized (lock) {
            try {
                Connection conn = pm.getConnection();
                // 查找已有路径
                RouteEntry existing = null;
                try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT * FROM route_cache WHERE task_hash=? AND tag_sequence=?")) {
                    ps.setString(1, hash);
                    ps.setString(2, tags);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) existing = mapRoute(rs);
                    }
                }

                if (existing != null) {
                    // 更新已存在路径：成功提权，失败降权
                    double newWeight;
                    int newSuccess = existing.successCount + (success ? 1 : 0);
                    int newTotal = existing.totalRounds + rounds;
                    if (success) {
                        newWeight = 1.0;  // 成功 → 最高权重
                    } else {
                        newWeight = Math.max(0.05, existing.weight * 0.5);  // 失败 → 权重减半
                    }
                    double newAvg = (existing.avgRounds * existing.totalRounds + rounds) / (double) newTotal;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE route_cache SET success_count=?,total_rounds=?,avg_rounds=?,weight=?,last_used=datetime('now') WHERE id=?")) {
                        ps.setInt(1, newSuccess);
                        ps.setInt(2, newTotal);
                        ps.setDouble(3, newAvg);
                        ps.setDouble(4, newWeight);
                        ps.setInt(5, existing.id);
                        ps.executeUpdate();
                    }
                } else {
                    // 新路径：初始权重 0.5
                    double initWeight = success ? 1.0 : 0.5;
                    try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO route_cache (task_hash,task_pattern,tag_sequence,success_count,total_rounds,avg_rounds,weight,last_used) " +
                                "VALUES (?,?,?,?,?,?,?,datetime('now'))")) {
                        ps.setString(1, hash);
                        ps.setString(2, task.length() > 200 ? task.substring(0, 200) : task);
                        ps.setString(3, tags);
                        ps.setInt(4, success ? 1 : 0);
                        ps.setInt(5, rounds);
                        ps.setDouble(6, (double) rounds);
                        ps.setDouble(7, initWeight);
                        ps.executeUpdate();
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[RouteCache] recordRoute error: " + e.getMessage());
            }
        }
    }

    /**
     * 获取路由提示文本，用于注入 system prompt。
     * @param task 当前任务（null 返回通用最高权重路径）
     */
    public String getRouteHint(String task) {
        RouteEntry route;
        if (task != null) {
            route = findRoute(task);
        } else {
            route = getBestRouteAny();
        }
        if (route == null) return null;
        return "\n## 路由提示（已知最短路径，权重 " + String.format("%.2f", route.weight)
                + "，成功率 " + (route.totalRounds > 0 ? route.successCount * 100 / route.totalRounds : 0) + "%）\n"
                + "- 推荐标签序列: " + route.tagSequence + "\n"
                + "- 按此顺序执行可获得最佳结果\n";
    }

    /**
     * 获取降级路由提示 — 排除所有已试路径，取下一权重路径。
     * @param task 任务描述
     * @param triedTags 已尝试的标签序列列表（每个元素如 "web" 或 "web,evaljs"）
     * @return 次优路径提示，无更多路径返回 null（触发探索模式）
     */
    public String getFallbackRouteHint(String task, java.util.List<String> triedTags) {
        if (pm == null || task == null || triedTags == null || triedTags.isEmpty()) return null;
        String hash = hashTask(task);
        synchronized (lock) {
            // 构建 NOT IN (?,?,...) 动态 SQL，排除所有已试路径
            StringBuilder placeholders = new StringBuilder();
            int cap = Math.min(triedTags.size(), 20); // 安全上限，防止SQL过长
            for (int i = 0; i < cap; i++) {
                if (i > 0) placeholders.append(",");
                placeholders.append("?");
            }
            String sql = "SELECT * FROM route_cache WHERE task_hash=? AND tag_sequence NOT IN ("
                       + placeholders + ") ORDER BY weight DESC LIMIT 1";
            try (PreparedStatement ps = pm.getConnection().prepareStatement(sql)) {
                ps.setString(1, hash);
                for (int i = 0; i < cap; i++) {
                    ps.setString(i + 2, triedTags.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        RouteEntry route = mapRoute(rs);
                        return "\n## ⚠ 上一条路径失败，权重已降级，切换新路径\n"
                                + "- 新推荐标签序列: " + route.tagSequence
                                + " (权重 " + String.format("%.2f", route.weight) + ")\n"
                                + "- 请用此新路径重新尝试\n";
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[RouteCache] fallback error: " + e.getMessage());
            }
        }
        return null;  // 无更多路径 → 触发探索模式
    }

    /** 获取全局权重最高的路径（无任务关联） */
    private RouteEntry getBestRouteAny() {
        if (pm == null) return null;
        synchronized (lock) {
            try (PreparedStatement ps = pm.getConnection().prepareStatement(
                     "SELECT * FROM route_cache ORDER BY weight DESC LIMIT 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapRoute(rs);
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[RouteCache] getBestRouteAny error: " + e.getMessage());
            }
        }
        return null;
    }

    /** 获取指定数量的最佳路由 */
    public List<RouteEntry> getBestRoutes(int limit) {
        java.util.List<RouteEntry> list = new java.util.ArrayList<>();
        if (pm == null) return list;
        synchronized (lock) {
            try (PreparedStatement ps = pm.getConnection().prepareStatement(
                     "SELECT * FROM route_cache ORDER BY weight DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(mapRoute(rs));
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[RouteCache] getBestRoutes error: " + e.getMessage());
            }
        }
        return list;
    }

    /** 清理低于阈值的低权重路由 */
    public int cleanupLowWeight(double threshold) {
        if (pm == null) return 0;
        synchronized (lock) {
            try (PreparedStatement ps = pm.getConnection().prepareStatement(
                     "DELETE FROM route_cache WHERE weight < ?")) {
                ps.setDouble(1, threshold);
                return ps.executeUpdate();
            } catch (Exception e) {
                AiAgentActivity.debugLog("[RouteCache] cleanup error: " + e.getMessage());
            }
        }
        return 0;
    }

    // ==================== 工具方法 ====================

    /** 任务哈希：取关键词的简单 hash */
    static String hashTask(String task) {
        if (task == null) return "null";
        // 提取前几个关键词
        String cleaned = task.replaceAll("[，。！？\\s]+", " ").trim();
        if (cleaned.length() > 100) cleaned = cleaned.substring(0, 100);
        int h = 0;
        for (char c : cleaned.toCharArray()) {
            h = h * 31 + c;
        }
        return Integer.toHexString(h & 0x7FFFFFFF);
    }

    private static RouteEntry mapRoute(ResultSet rs) throws java.sql.SQLException {
        RouteEntry e = new RouteEntry();
        e.id = rs.getInt("id");
        e.taskHash = rs.getString("task_hash");
        e.taskPattern = rs.getString("task_pattern");
        e.tagSequence = rs.getString("tag_sequence");
        e.successCount = rs.getInt("success_count");
        e.totalRounds = rs.getInt("total_rounds");
        e.avgRounds = rs.getDouble("avg_rounds");
        e.weight = rs.getDouble("weight");
        e.lastUsed = rs.getString("last_used");
        return e;
    }

    // ==================== 数据模型 ====================

    public static class RouteEntry {
        public int id;
        public String taskHash;
        public String taskPattern;
        public String tagSequence;
        public int successCount;
        public int totalRounds;
        public double avgRounds;
        public double weight;
        public String lastUsed;

        @Override
        public String toString() {
            return "[Route#" + id + " w=" + String.format("%.2f", weight)
                    + "] " + tagSequence + " (" + successCount + "/" + totalRounds + ")";
        }
    }
}
