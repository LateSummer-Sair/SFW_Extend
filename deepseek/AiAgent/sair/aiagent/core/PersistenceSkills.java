package sair.aiagent.core;

import java.sql.*;
import java.util.*;
import java.util.function.Function;

import sair.aiagent.AiAgentActivity;

import sair.aiagent.model.SkillEntry;

/**
 * Skills 表 CRUD 操作 —— 从 {@link PersistenceManager} 提取的技能存储层。
 */
class PersistenceSkills {

    private final Connection conn;
    private final Object lock;

    /**
     * 技能表「结构版本号」：任何改变 active 技能集合/顺序/文本的写入都会自增。
     * 上层（{@link SkillBank} 索引构建）据此判断索引字符串缓存是否失效。
     * <p>
     * 注意：使用计数（success/failure）<b>不再</b>自增此版本号 —— 它不影响索引字节，
     * 而且现在走的是缓存增量更新；否则每条消息的索引缓存都会被无谓打掉。
     * </p>
     */
    private volatile long version = 1L;

    /**
     * active 技能快照缓存。
     * <p>
     * <b>增量维护</b>：加载一次之后，所有写入（新增/更新/删除/下线/合并/通道回填/使用计数）
     * 都在 {@link #lock} 内对这份快照做「引用级」局部修改，<b>不再全量重读数据库</b>。
     * 全量重读的成本是「行数 × 行的字符串解码」，技能库已按 440 条/天在增长
     * （实测 4.6K 行 = 46ms，按此推算 45K 行 ≈ 0.5s、160K 行 ≈ 1.6s），
     * 而 AutoDistill 每 5 分钟就会插入一批、每次插入都会让缓存失效 ——
     * 那会让热路径在全局 DB 锁内反复付出秒级代价。局部拷贝是 O(行数) 次指针复制
     * （160K 行 ≈ 亚毫秒），且不碰数据库。
     * </p>
     * <p>快照本身不可变（替换式更新），读取无需加锁。</p>
     */
    private volatile List<SkillEntry> activeCache = null;

    PersistenceSkills(Connection conn, Object lock) {
        this.conn = conn;
        this.lock = lock;
    }

    /** 当前技能表结构版本号（供上层做缓存键）。 */
    long getVersion() {
        return version;
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
                String cat = category != null ? category : "general";
                String desc = description != null ? description : "";
                String body = content != null ? content : "";
                String src = source != null ? source : "extracted";
                String sc = scope != null ? scope : "task";
                String hash = sair.aiagent.model.SkillEntry.computeContentHash(body);
                ps.setString(1, name);
                ps.setString(2, cat);
                ps.setString(3, desc);
                ps.setString(4, body);
                ps.setString(5, src);
                ps.setString(6, sc);
                ps.setString(7, hash);
                ps.setLong(8, now);
                ps.setLong(9, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        int id = rs.getInt(1);
                        version++;   // 索引字符串缓存失效（技能集合变了）
                        // 增量入缓存：直接用刚写入的值构造条目，省掉一次 SELECT
                        SkillEntry entry = new SkillEntry(id, name, cat, desc, body, 1, 0, 0, 0L, 0,
                                "active", src, sc, hash, now, now);
                        cacheAdd(entry);
                        return id;
                    }
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
            // 先留存旧版本：merge/evolve 会覆盖 skills 表里的唯一副本，
            // 若 LLM 合并/进化产出垃圾，没有快照就再也找不回原内容了。
            snapshotCurrentVersion(id, "update");
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE skills SET name=?,description=?,content=?,version=?,updated_at=?,content_hash=? WHERE id=?")) {
                long now = System.currentTimeMillis();
                String hash = sair.aiagent.model.SkillEntry.computeContentHash(content != null ? content : "");
                ps.setString(1, name);
                ps.setString(2, description != null ? description : "");
                ps.setString(3, content != null ? content : "");
                ps.setInt(4, newVersion);
                ps.setLong(5, now);
                ps.setString(6, hash);
                ps.setInt(7, id);
                int n = ps.executeUpdate();
                if (n > 0) {
                    version++;
                    // 增量替换缓存条目（顺序不受影响：name/description/content/version 都不参与排序）
                    SkillEntry cur = cachedById(id);
                    if (cur != null) {
                        cacheReplace(id, cur.withContent(name, description, content, newVersion, now, hash));
                    }
                }
                return n > 0;
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] updateSkill error: " + e.getMessage());
            }
        }
        return false;
    }

    /** 把 skills 中当前这一版内容写入 skill_versions（幂等：同 skill+version 只留一条）。 */
    private void snapshotCurrentVersion(int id, String reason) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO skill_versions (skill_id,version,name,description,content,content_hash,reason,snapshot_at) " +
                "SELECT id,version,name,description,content,content_hash,?,? FROM skills WHERE id=? " +
                "AND NOT EXISTS (SELECT 1 FROM skill_versions v WHERE v.skill_id=skills.id AND v.version=skills.version)")) {
            ps.setString(1, reason);
            ps.setLong(2, System.currentTimeMillis());
            ps.setInt(3, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[PersistenceSkills] snapshotCurrentVersion error: " + e.getMessage());
        }
    }

    /** 列出某技能的版本快照（新→旧），供人工/工具回溯恢复被覆盖的内容。 */
    List<String[]> listSkillVersions(int id) {
        List<String[]> out = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT version, name, content, reason, snapshot_at FROM skill_versions " +
                    "WHERE skill_id=? ORDER BY version DESC")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new String[]{
                                String.valueOf(rs.getInt(1)), rs.getString(2), rs.getString(3),
                                rs.getString(4), String.valueOf(rs.getLong(5))});
                    }
                }
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] listSkillVersions error: " + e.getMessage());
            }
        }
        return out;
    }

    boolean removeSkill(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM skills WHERE id=?")) {
                ps.setInt(1, id);
                int n = ps.executeUpdate();
                if (n > 0) {
                    version++;
                    cacheRemove(id);
                }
                return n > 0;
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

    /**
     * 全部 active 技能（增量维护的只读快照）。
     * <p>
     * 排序刻意采用「内置优先，其次新建优先」：
     * <pre>
     *   builtin 技能 id 升序  →  非 builtin 技能 id 降序
     * </pre>
     * 原因：内置技能是真实的工具/API 能力清单（id 很小），此前 {@code ORDER BY id DESC}
     * 把它们排到最后，在预算截断下会被「新学到的经验技能」整体挤出索引 —— AI 因此看不到
     * 大部分 NapCat API 与系统工具。内置段按 id 升序还有额外好处：新增经验技能时只有列表
     * 中部之后变化，前缀字节保持稳定，可继续命中 KV 前缀缓存。
     * <p>
     * 只有<b>首次</b>读取会查库；之后所有写入都在锁内对快照做局部替换（见 {@link #activeCache}）。
     * 返回的列表是共享只读快照，调用方不得修改（需要排序请自行 copy）。
     */
    List<SkillEntry> listAllSkills() {
        List<SkillEntry> cached = activeCache;   // volatile 读，无锁快路径
        if (cached != null) return cached;
        synchronized (lock) {
            if (activeCache != null) return activeCache;
            List<SkillEntry> list = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT * FROM skills WHERE status='active' " +
                     "ORDER BY (CASE WHEN source='builtin' THEN 0 ELSE 1 END), " +
                     "         (CASE WHEN source='builtin' THEN id ELSE -id END)")) {
                while (rs.next()) list.add(mapSkill(rs));
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] listAllSkills error: " + e.getMessage());
                // 查询失败时退回旧快照，避免热路径拿到空技能索引
                if (activeCache != null) return activeCache;
                return Collections.emptyList();
            }
            List<SkillEntry> snapshot = Collections.unmodifiableList(list);
            activeCache = snapshot;
            return snapshot;
        }
    }

    // ==================== 快照增量维护（全部要求在 lock 内调用） ====================

    /** 在快照中按 id 定位下标（-1 = 不在 active 集合里）。 */
    private static int indexOfId(List<SkillEntry> list, int id) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId() == id) return i;
        }
        return -1;
    }

    /** 在快照中按 id 取条目（不在其中返回 null）。 */
    private SkillEntry cachedById(int id) {
        List<SkillEntry> cur = activeCache;
        if (cur == null) return null;
        int idx = indexOfId(cur, id);
        return idx < 0 ? null : cur.get(idx);
    }

    /**
     * 新增行进入快照：按「内置段 id 升序 + 学到段 id 降序」的既定顺序插入正确位置。
     * 缓存未加载时直接返回（下次读取自然会包含这一行）。
     */
    private void cacheAdd(SkillEntry s) {
        List<SkillEntry> cur = activeCache;
        if (cur == null || s == null) return;
        List<SkillEntry> next = new ArrayList<>(cur.size() + 1);
        int pos = 0;
        if ("builtin".equals(s.getSource())) {
            // 内置段内部按 id 升序：插到第一个「非内置」或「id 更大」的位置之前
            while (pos < cur.size()) {
                SkillEntry e = cur.get(pos);
                if (!"builtin".equals(e.getSource()) || e.getId() > s.getId()) break;
                pos++;
            }
        } else {
            // 学到的技能按 id 降序，新行 id 最大 → 紧跟在内置段之后
            while (pos < cur.size() && "builtin".equals(cur.get(pos).getSource())) pos++;
        }
        next.addAll(cur.subList(0, pos));
        next.add(s);
        next.addAll(cur.subList(pos, cur.size()));
        activeCache = Collections.unmodifiableList(next);
    }

    /** 用新的条目替换快照中同 id 的条目（不在快照里则忽略 —— 例如已下线的行被更新）。 */
    private void cacheReplace(int id, SkillEntry s) {
        List<SkillEntry> cur = activeCache;
        if (cur == null || s == null) return;
        int idx = indexOfId(cur, id);
        if (idx < 0) return;
        List<SkillEntry> next = new ArrayList<>(cur);
        next.set(idx, s);
        activeCache = Collections.unmodifiableList(next);
    }

    /** 从快照中移除某 id（下线/合并/删除）。 */
    private void cacheRemove(int id) {
        List<SkillEntry> cur = activeCache;
        if (cur == null) return;
        int idx = indexOfId(cur, id);
        if (idx < 0) return;
        List<SkillEntry> next = new ArrayList<>(cur);
        next.remove(idx);
        activeCache = Collections.unmodifiableList(next);
    }

    /** 从快照中按 scope 过滤（不再单独走 SQL：原先每次调用都要全表 SELECT *）。 */
    List<SkillEntry> getSkillsByScope(String scope) {
        if (scope == null) return Collections.emptyList();
        List<SkillEntry> out = new ArrayList<>();
        for (SkillEntry s : listAllSkills()) {
            if (scope.equals(s.getScope())) out.add(s);
        }
        return out;
    }

    List<SkillEntry> searchSkills(String query, int limit,
                                  Function<String, String> sanitizeFts) {
        synchronized (lock) {
            List<SkillEntry> list = new ArrayList<>();
            if (query == null || query.trim().isEmpty()) return list;
            int lim = limit > 0 ? limit : 5;
            String safe = sanitizeFts != null ? sanitizeFts.apply(query) : query;
            // sanitizeFts（trigram 构造器）对过短查询返回 null → 直接走 LIKE
            if (safe != null && !safe.trim().isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT s.* FROM skills s JOIN skills_fts f ON s.id = f.rowid " +
                        "WHERE skills_fts MATCH ? AND s.status='active' ORDER BY rank LIMIT ?")) {
                    ps.setString(1, safe);
                    ps.setInt(2, lim);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) list.add(mapSkill(rs));
                    }
                } catch (SQLException e) {
                    AiAgentActivity.debugLog("[PersistenceSkills] 技能 FTS 检索失败，回退 LIKE: " + e.getMessage());
                }
            }
            // FTS 无命中或查询过短 → LIKE 兜底（转义通配符）
            // 注意：不加 ORDER BY 时 SQLite 返回的是任意行，等于「随机技能」；中文 2 字查询
            //（哈哈/文件/好的）必然走这里，所以按「名称命中 > 描述命中 > 正文命中」排序。
            if (list.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT * FROM skills WHERE status='active' AND " +
                        "(name LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' " +
                        "OR content LIKE ? ESCAPE '\\') " +
                        "ORDER BY (CASE WHEN name LIKE ? ESCAPE '\\' THEN 0 " +
                        "               WHEN description LIKE ? ESCAPE '\\' THEN 1 ELSE 2 END), " +
                        "         (CASE WHEN source='builtin' THEN 0 ELSE 1 END), id DESC " +
                        "LIMIT ?")) {
                    String like = "%" + sair.aiagent.util.FtsQuery.likeEscape(query.trim()) + "%";
                    ps.setString(1, like); ps.setString(2, like); ps.setString(3, like);
                    ps.setString(4, like); ps.setString(5, like);
                    ps.setInt(6, lim);
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
                long now = System.currentTimeMillis();
                ps.setLong(1, now);
                ps.setInt(2, id);
                ps.executeUpdate();
                // 原地更新缓存计数：计数是「参考值」，不影响索引字节（索引行只有名称+描述），
                // 因此这里刻意 **不** 自增 version —— 否则每次技能被查阅都会把索引字符串缓存打掉。
                // int/long 的单次写是原子的，最坏情况只是短时间内读到略旧的计数。
                SkillEntry cur = cachedById(id);
                if (cur != null) {
                    if (success) cur.setSuccessCount(cur.getSuccessCount() + 1);
                    else cur.setFailureCount(cur.getFailureCount() + 1);
                    cur.setLastUsed(now);
                }
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] incrementSkillUsage error: " + e.getMessage());
            }
        }
    }

    /**
     * 记录一次「近似重复被抑制」（新学到的经验被判定为已有经验的改写）。
     * <p>重复次数本身就是「这条经验很重要」的强信号 —— 比空转的成功率有用得多，
     * 所以不静默丢弃，而是记在被命中的那条上。</p>
     */
    void incrementDupHit(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE skills SET dup_hit_count=dup_hit_count+1 WHERE id=?")) {
                ps.setInt(1, id);
                ps.executeUpdate();
                SkillEntry cur = cachedById(id);
                if (cur != null) cur.setDupHitCount(cur.getDupHitCount() + 1);
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] incrementDupHit error: " + e.getMessage());
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
                int n = ps.executeUpdate();
                if (n > 0) {
                    version++;          // 技能退出 active 集合 → 索引字符串缓存失效
                    cacheRemove(id);    // 同步从快照移除
                }
                return n > 0;
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
                int n = ps.executeUpdate();
                if (n > 0) {
                    version++;          // 技能退出 active 集合 → 索引字符串缓存失效
                    cacheRemove(id);    // 同步从快照移除
                }
                return n > 0;
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] markSkillMerged error: " + e.getMessage());
            }
        }
        return false;
    }

    // ==================== Scope ====================

    List<SkillEntry> getGeneralSkills() {
        return getSkillsByScope("general");
    }

    List<SkillEntry> getPersonaSkills() {
        return getSkillsByScope("persona");
    }

    /** 设置技能适用通道（改变索引可见性，因此 bump version）。 */
    boolean setSkillChannels(int id, String channels) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE skills SET channels=?, updated_at=? WHERE id=?")) {
                ps.setString(1, channels != null ? channels : "");
                ps.setLong(2, System.currentTimeMillis());
                ps.setInt(3, id);
                int n = ps.executeUpdate();
                if (n > 0) {
                    version++;   // 通道可见性变了 → 索引字符串缓存失效
                    // 用副本替换（顺序不受 channels 影响），避免原地修改共享对象
                    SkillEntry cur = cachedById(id);
                    if (cur != null) {
                        SkillEntry copy = cur.copy();
                        copy.setChannels(channels);
                        cacheReplace(id, copy);
                    }
                }
                return n > 0;
            } catch (SQLException e) {
                System.err.println("[PersistenceSkills] setSkillChannels error: " + e.getMessage());
            }
        }
        return false;
    }

    // ==================== Mapping ====================

    private static SkillEntry mapSkill(ResultSet rs) throws SQLException {
        SkillEntry s = new SkillEntry(
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
        // channels / dup_hit_count 列由 ensureColumn 幂等补上；老库/异常时退回默认值
        try {
            s.setChannels(rs.getString("channels"));
        } catch (SQLException ignored) {}
        try {
            s.setDupHitCount(rs.getInt("dup_hit_count"));
        } catch (SQLException ignored) {}
        return s;
    }
}