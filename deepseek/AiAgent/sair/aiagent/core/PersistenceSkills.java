package sair.aiagent.core;

import java.sql.*;
import java.util.*;
import java.util.function.Function;

import sair.aiagent.model.SkillEntry;

/**
 * Skills 表 CRUD 操作 —— 从 {@link PersistenceManager} 提取的技能存储层。
 */
class PersistenceSkills {

    private final Connection conn;
    private final Object lock;

    PersistenceSkills(Connection conn, Object lock) {
        this.conn = conn;
        this.lock = lock;
    }

    // ==================== Add ====================

    int addSkill(String name, String category, String description,
                 String content, String source) {
        return addSkill(name, category, description, content, source, "task");
    }

    int addSkill(String name, String category, String description,
                 String content, String source, String scope) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO skills (name,category,description,content,source,scope,content_hash,created_at,updated_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                long now = System.currentTimeMillis();
                ps.setString(1, name);
                ps.setString(2, category != null ? category : "general");
                ps.setString(3, description != null ? description : "");
                ps.setString(4, content != null ? content : "");
                ps.setString(5, source != null ? source : "extracted");
                ps.setString(6, scope != null ? scope : "task");
                ps.setString(7, sair.aiagent.model.SkillEntry.computeContentHash(content != null ? content : ""));
                ps.setLong(8, now);
                ps.setLong(9, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] addSkill error: " + e.getMessage());
            }
        }
        return -1;
    }

    // ==================== Update ====================

    boolean updateSkill(int id, String name, String description,
                        String content, int newVersion) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE skills SET name=?,description=?,content=?,version=?,updated_at=?,content_hash=? WHERE id=?")) {
                ps.setString(1, name);
                ps.setString(2, description != null ? description : "");
                ps.setString(3, content != null ? content : "");
                ps.setInt(4, newVersion);
                ps.setLong(5, System.currentTimeMillis());
                ps.setString(6, sair.aiagent.model.SkillEntry.computeContentHash(content != null ? content : ""));
                ps.setInt(7, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] updateSkill error: " + e.getMessage());
            }
        }
        return false;
    }

    boolean removeSkill(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM skills WHERE id=?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] removeSkill error: " + e.getMessage());
            }
        }
        return false;
    }

    // ==================== Query ====================

    SkillEntry getSkill(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM skills WHERE id=?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapSkill(rs);
                }
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] getSkill error: " + e.getMessage());
            }
        }
        return null;
    }

    List<SkillEntry> listAllSkills() {
        synchronized (lock) {
            List<SkillEntry> list = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT * FROM skills WHERE status='active' ORDER BY id DESC")) {
                while (rs.next()) list.add(mapSkill(rs));
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] listAllSkills error: " + e.getMessage());
            }
            return list;
        }
    }

    List<SkillEntry> searchSkills(String query, int limit,
                                  Function<String, String> sanitizeFts) {
        synchronized (lock) {
            List<SkillEntry> list = new ArrayList<>();
            if (query == null || query.trim().isEmpty()) return list;
            String safe = sanitizeFts != null ? sanitizeFts.apply(query) : query;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT s.* FROM skills s JOIN skills_fts f ON s.id = f.rowid " +
                    "WHERE skills_fts MATCH ? AND s.status='active' ORDER BY rank LIMIT ?")) {
                ps.setString(1, safe);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(mapSkill(rs));
                }
            } catch (SQLException e) {
                // FTS might fail; fallback to LIKE search
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM skills WHERE status='active' AND " +
                        "(name LIKE ? OR description LIKE ? OR content LIKE ?) LIMIT ?")) {
                    String like = "%" + query.replaceAll("[%_]", "") + "%";
                    ps.setString(1, like); ps.setString(2, like); ps.setString(3, like);
                    ps.setInt(4, limit);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) list.add(mapSkill(rs));
                    }
                } catch (SQLException ignored) {}
            }
            return list;
        }
    }

    SkillEntry findSimilarSkill(String name, String description) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM skills WHERE status='active' AND name=? LIMIT 1")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapSkill(rs);
                }
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] findSimilarSkill error: " + e.getMessage());
            }
        }
        return null;
    }

    // ==================== Usage ====================

    void incrementSkillUsage(int id, boolean success) {
        synchronized (lock) {
            String col = success ? "success_count" : "failure_count";
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE skills SET " + col + "=" + col + "+1, last_used=? WHERE id=?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] incrementSkillUsage error: " + e.getMessage());
            }
        }
    }

    List<SkillEntry> getSkillsForEvolution(int minFailureCount, int minTotal) {
        synchronized (lock) {
            List<SkillEntry> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM skills WHERE status='active' AND failure_count>=? " +
                    "AND (success_count+failure_count)>=? ORDER BY failure_count DESC")) {
                ps.setInt(1, minFailureCount);
                ps.setInt(2, minTotal);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(mapSkill(rs));
                }
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] getSkillsForEvolution error: " + e.getMessage());
            }
            return list;
        }
    }

    boolean deprecateSkill(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE skills SET status='deprecated', updated_at=? WHERE id=?")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setInt(2, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] deprecateSkill error: " + e.getMessage());
            }
        }
        return false;
    }

    boolean markSkillMerged(int id, int mergedIntoId) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE skills SET status='merged', parent_skill_id=?, updated_at=? WHERE id=?")) {
                ps.setInt(1, mergedIntoId);
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] markSkillMerged error: " + e.getMessage());
            }
        }
        return false;
    }

    // ==================== Scope ====================

    List<SkillEntry> getSkillsByScope(String scope) {
        synchronized (lock) {
            List<SkillEntry> list = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM skills WHERE status='active' AND scope=? ORDER BY id DESC")) {
                ps.setString(1, scope);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(mapSkill(rs));
                }
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] getSkillsByScope error: " + e.getMessage());
            }
            return list;
        }
    }

    List<SkillEntry> getGeneralSkills() {
        return getSkillsByScope("general");
    }

    List<SkillEntry> getPersonaSkills() {
        return getSkillsByScope("persona");
    }

    // ==================== Mapping ====================

    private static SkillEntry mapSkill(ResultSet rs) throws SQLException {
        return new SkillEntry(
            rs.getInt("id"),
            rs.getString("name"),
            rs.getString("category"),
            rs.getString("description"),
            rs.getString("content"),
            rs.getInt("version"),
            rs.getInt("success_count"),
            rs.getInt("failure_count"),
            rs.getLong("last_used"),
            rs.getInt("parent_skill_id"),
            rs.getString("status"),
            rs.getString("source"),
            rs.getString("scope"),
            rs.getString("content_hash"),
            rs.getLong("created_at"),
            rs.getLong("updated_at")
        );
    }
}