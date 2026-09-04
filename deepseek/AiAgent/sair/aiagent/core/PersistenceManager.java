package sair.aiagent.core;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
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
import sair.aiagent.model.SkillEntry;
import sair.aiagent.model.StickerEntry;
import sair.aiagent.model.ImpressionEntry;
import sair.aiagent.model.GroupImpression;
import sair.aiagent.model.CorrectionEntry;
import sair.aiagent.model.AlarmEntry;

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

    private int toolTraceInsertCount = 0;
    private int harnessTraceInsertCount = 0;

    /** Escape FTS5 special characters to prevent syntax errors in MATCH queries */
    private static String sanitizeFtsQuery(String query) {
        if (query == null || query.isEmpty()) return query;
        // Remove FTS5 special chars: *, ", parentheses, -, :
        return query.replaceAll("[*\"()\\-:]", " ")
                    .replaceAll("\\s+", " OR ")
                    .trim();
    }


    private static PersistenceManager instance;
    private final Gson gson = new Gson();
    private final Object lock = new Object();
    private Connection conn;
    private File dbFile;
    private File dataDir;

    /** 临时存储层（内存 LRU + Redis 可选加速，覆盖印象/群印象/纠正查询） */
    private final TemporalStore temporalStore = new TemporalStore(500, 300_000L);

    // Skills CRUD delegation
    private PersistenceSkills skillsDb;

    public static PersistenceManager getInstance() {
        return instance;
    }

    /** 获取数据库连接（供 RouteCache 等内部组件使用） */
    public Connection getConnection() { return conn; }

    /** 获取同步锁（供 RouteCache 等内部组件使用） */
    public Object getLock() { return lock; }

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
        this.dataDir = dir;
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

            skillsDb = new PersistenceSkills(conn, lock);
            createTables();
            ensureSchemaVersion();

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
                    "  remark TEXT NOT NULL DEFAULT ''," +
                    "  usage_count INTEGER NOT NULL DEFAULT 0," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_sticker_created ON stickers(created_at)");

                // 图片注释表（按图片 MD5 强绑定，AI 可自由修改，随图持久化）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS image_remarks (" +
                    "  image_md5 TEXT PRIMARY KEY," +
                    "  remark TEXT NOT NULL DEFAULT ''," +
                    "  source TEXT NOT NULL DEFAULT 'ocr'," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );

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

                // 系统闹钟表（AI Alarm：到点唤醒 AI 走 Agent 链路）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS alarms (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  scope TEXT NOT NULL DEFAULT 'REMIND'," +
                    "  schedule TEXT NOT NULL DEFAULT ''," +
                    "  task TEXT NOT NULL DEFAULT ''," +
                    "  snapshot TEXT," +
                    "  notes TEXT," +
                    "  channel TEXT NOT NULL DEFAULT 'console'," +
                    "  sender_qq INTEGER NOT NULL DEFAULT 0," +
                    "  is_group INTEGER NOT NULL DEFAULT 0," +
                    "  group_id INTEGER NOT NULL DEFAULT 0," +
                    "  is_master INTEGER NOT NULL DEFAULT 0," +
                    "  repeat INTEGER NOT NULL DEFAULT 0," +
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

                // 纠正记录表（AI犯错被纠正，支持同一主题多观点）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS corrections (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  topic TEXT NOT NULL DEFAULT ''," +
                    "  content TEXT NOT NULL DEFAULT ''," +
                    "  viewpoint TEXT NOT NULL DEFAULT ''," +
                    "  source TEXT NOT NULL DEFAULT 'user'," +
                    "  qq INTEGER NOT NULL DEFAULT 0," +
                    "  created_at INTEGER NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );

                // 技能表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS skills (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  name TEXT NOT NULL DEFAULT ''," +
                    "  category TEXT NOT NULL DEFAULT 'general'," +
                    "  description TEXT NOT NULL DEFAULT ''," +
                    "  content TEXT NOT NULL DEFAULT ''," +
                    "  version INTEGER NOT NULL DEFAULT 1," +
                    "  success_count INTEGER NOT NULL DEFAULT 0," +
                    "  failure_count INTEGER NOT NULL DEFAULT 0," +
                    "  last_used INTEGER NOT NULL DEFAULT 0," +
                    "  parent_skill_id INTEGER NOT NULL DEFAULT 0," +
                    "  status TEXT NOT NULL DEFAULT 'active'," +
                    "  source TEXT NOT NULL DEFAULT 'extracted'," +
                    "  scope TEXT NOT NULL DEFAULT 'task'," +
                    "  content_hash TEXT NOT NULL DEFAULT ''," +
                    "  created_at INTEGER NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_skill_status ON skills(status)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_skill_category ON skills(category)");
                // 人格印象表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS impressions (" +
                    "  qq INTEGER PRIMARY KEY," +
                    "  nickname TEXT NOT NULL DEFAULT ''," +
                    "  emotion_stability TEXT NOT NULL DEFAULT ''," +
                    "  interests TEXT NOT NULL DEFAULT ''," +
                    "  speaking_style TEXT NOT NULL DEFAULT ''," +
                    "  honesty TEXT NOT NULL DEFAULT ''," +
                    "  image_habit TEXT NOT NULL DEFAULT ''," +
                    "  impression_level INTEGER NOT NULL DEFAULT 0," +
                    "  message_count INTEGER NOT NULL DEFAULT 0," +
                    "  first_seen INTEGER NOT NULL," +
                    "  last_seen INTEGER NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );

                // 结构化偏好表（scope=user/group/global，target_id=QQ号/群号/0）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS user_preferences (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  scope TEXT NOT NULL DEFAULT 'user'," +
                    "  target_id INTEGER NOT NULL DEFAULT 0," +
                    "  pref_key TEXT NOT NULL DEFAULT ''," +
                    "  pref_value TEXT NOT NULL DEFAULT ''," +
                    "  importance INTEGER NOT NULL DEFAULT 0," +
                    "  updated_at INTEGER NOT NULL," +
                    "  UNIQUE(scope, target_id, pref_key)" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_pref_scope_target ON user_preferences(scope, target_id)");

                // 群印象表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS group_impressions (" +
                    "  group_id INTEGER PRIMARY KEY," +
                    "  group_name TEXT NOT NULL DEFAULT ''," +
                    "  friendliness INTEGER NOT NULL DEFAULT 0," +
                    "  atmosphere TEXT NOT NULL DEFAULT ''," +
                    "  message_count INTEGER NOT NULL DEFAULT 0," +
                    "  first_seen INTEGER NOT NULL," +
                    "  last_seen INTEGER NOT NULL," +
                    "  updated_at INTEGER NOT NULL" +
                    ")"
                );


                // FTS5 skills full-text index
                stmt.execute(
                    "CREATE VIRTUAL TABLE IF NOT EXISTS skills_fts USING fts5(" +
                    "  name, description, content," +
                    "  content='skills'," +
                    "  content_rowid='id'" +
                    ")"
                );

                // 路由缓存表
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS route_cache (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  task_hash TEXT NOT NULL," +
                    "  task_pattern TEXT NOT NULL DEFAULT ''," +
                    "  tag_sequence TEXT NOT NULL DEFAULT ''," +
                    "  success_count INTEGER NOT NULL DEFAULT 1," +
                    "  total_rounds INTEGER NOT NULL DEFAULT 1," +
                    "  avg_rounds REAL NOT NULL DEFAULT 1.0," +
                    "  weight REAL NOT NULL DEFAULT 0.5," +
                    "  last_used TEXT NOT NULL DEFAULT ''," +
                    "  created_at TEXT NOT NULL DEFAULT (datetime('now'))" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_route_hash ON route_cache(task_hash)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_route_weight ON route_cache(weight)");

                // Harness 执行轨迹表（可观测性：成功/失败/轮次/耗时/审查判定）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS harness_traces (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  task_id TEXT NOT NULL," +
                    "  mode TEXT NOT NULL DEFAULT 'console'," +
                    "  task TEXT NOT NULL DEFAULT ''," +
                    "  tool_calls INTEGER NOT NULL DEFAULT 0," +
                    "  success INTEGER NOT NULL DEFAULT 0," +
                    "  duration_ms INTEGER NOT NULL DEFAULT 0," +
                    "  critic_verdict TEXT NOT NULL DEFAULT ''," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_htrace_mode ON harness_traces(mode)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_htrace_time ON harness_traces(created_at)");

                // 工具调用轻量遥测表（与 harness_traces 分离，不改变 HarnessTrace 语义）
                stmt.execute(
                    "CREATE TABLE IF NOT EXISTS tool_call_traces (" +
                    "  id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "  trace_id TEXT NOT NULL," +
                    "  tool_name TEXT NOT NULL DEFAULT ''," +
                    "  channel TEXT NOT NULL DEFAULT 'console'," +
                    "  arguments TEXT NOT NULL DEFAULT ''," +
                    "  result TEXT NOT NULL DEFAULT ''," +
                    "  success INTEGER NOT NULL DEFAULT 0," +
                    "  duration_ms INTEGER NOT NULL DEFAULT 0," +
                    "  outcome TEXT NOT NULL DEFAULT 'ok'," +
                    "  created_at INTEGER NOT NULL" +
                    ")"
                );
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tooltrace_time ON tool_call_traces(created_at)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_tooltrace_tool ON tool_call_traces(tool_name)");

                // triggers for skills FTS
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS sft_ai AFTER INSERT ON skills BEGIN " +
                    "  INSERT INTO skills_fts(rowid, name, description, content) " +
                    "  VALUES (new.id, new.name, new.description, new.content); " +
                    "END"
                );
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS sft_ad AFTER DELETE ON skills BEGIN " +
                    "  INSERT INTO skills_fts(skills_fts, rowid, name, description, content) " +
                    "  VALUES ('delete', old.id, old.name, old.description, old.content); " +
                    "END"
                );
                stmt.execute(
                    "CREATE TRIGGER IF NOT EXISTS sft_au AFTER UPDATE ON skills BEGIN " +
                    "  INSERT INTO skills_fts(skills_fts, rowid, name, description, content) " +
                    "  VALUES ('delete', old.id, old.name, old.description, old.content); " +
                    "  INSERT INTO skills_fts(rowid, name, description, content) " +
                    "  VALUES (new.id, new.name, new.description, new.content); " +
                    "END"
                );

                // === 高频查询索引 ===
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_mem_importance ON memories(importance, created_at)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_notes_updated ON notes(updated_at)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_imp_last_seen ON impressions(last_seen)");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_corr_topic ON corrections(topic)");

                // === Schema 迁移：老库补列（幂等，避免用户手动重置数据库） ===
                ensureColumn(stmt, "skills", "scope", "TEXT NOT NULL DEFAULT 'task'");
                ensureColumn(stmt, "skills", "content_hash", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(stmt, "memories", "importance", "INTEGER NOT NULL DEFAULT 0");
                ensureColumn(stmt, "memories", "category", "TEXT NOT NULL DEFAULT 'general'");
                ensureColumn(stmt, "stickers", "keywords", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(stmt, "stickers", "file_path", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(stmt, "stickers", "remark", "TEXT NOT NULL DEFAULT ''");
                ensureColumn(stmt, "impressions", "impression_level", "INTEGER NOT NULL DEFAULT 0");

            }
        }
    }

    /** 使用 PRAGMA user_version 记录 schema 版本，方便未来迁移。 */
    private void ensureSchemaVersion() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                try (ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        stmt.execute("PRAGMA user_version = 1");
                    }
                }
            } catch (SQLException ignored) {}
        }
    }

    /**
     * 老库 schema 迁移：若表缺少某列则 ALTER TABLE 补列。
     * 幂等：列已存在时自动跳过，不重复添加。
     */
    private void ensureColumn(Statement stmt, String table, String column, String columnDdl) throws SQLException {
        boolean exists = false;
        try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (column.equals(rs.getString("name"))) { exists = true; break; }
            }
        }
        if (!exists) {
            stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + columnDdl);
            AiAgentActivity.debugLog("[Persistence] schema migrate: " + table + " + " + column);
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

    // ==================== Harness Traces ====================

    /**
     * 持久化一条 Harness 执行轨迹。失败静默忽略（可观测性不应阻断业务）。
     */
    public void saveTrace(HarnessTrace trace) {
        if (trace == null || conn == null) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO harness_traces (task_id, mode, task, tool_calls, success, duration_ms, critic_verdict, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?)")) {
                ps.setString(1, trace.taskId);
                ps.setString(2, trace.mode);
                ps.setString(3, HarnessTrace.truncateTask(trace.task, 500));
                ps.setInt(4, trace.toolCalls);
                ps.setInt(5, trace.success ? 1 : 0);
                ps.setLong(6, trace.durationMs);
                ps.setString(7, HarnessTrace.truncateTask(trace.criticVerdict, 500));
                ps.setLong(8, trace.timestamp);
                ps.executeUpdate();
            } catch (SQLException ignored) {
                // 轨迹落库失败不影响业务
            }
            if (++harnessTraceInsertCount % 100 == 0) {
                cleanupHarnessTraces(5000);
            }
        }
    }

    /** 清理过旧 harness 执行轨迹，只保留最近 keep 条。 */
    public void cleanupHarnessTraces(int keep) {
        if (conn == null || keep <= 0) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM harness_traces WHERE id NOT IN (" +
                    "SELECT id FROM harness_traces ORDER BY id DESC LIMIT ?)")) {
                ps.setInt(1, keep);
                ps.executeUpdate();
            } catch (SQLException ignored) {
                // 清理失败不影响业务
            }
        }
    }

    /**
     * 按时间倒序列出最近 N 条 Harness 执行轨迹。
     * @param limit 最大条数
     */
    public List<HarnessTrace> listTraces(int limit) {
        List<HarnessTrace> traces = new ArrayList<>();
        if (conn == null) return traces;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT task_id, mode, task, tool_calls, success, duration_ms, critic_verdict, created_at " +
                    "FROM harness_traces ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        traces.add(new HarnessTrace(
                                rs.getString("task_id"),
                                rs.getString("mode"),
                                rs.getString("task"),
                                rs.getInt("tool_calls"),
                                rs.getInt("success") != 0,
                                rs.getLong("duration_ms"),
                                rs.getString("critic_verdict"),
                                rs.getLong("created_at")));
                    }
                }
            } catch (SQLException ignored) {
                // 读取失败返回空列表
            }
        }
        return traces;
    }

    // ==================== Tool Call Traces ====================

    /**
     * 持久化一条工具调用轻量遥测。失败静默忽略（可观测性不应阻断业务）。
     */
    public void saveToolTrace(ToolCallTrace trace) {
        if (trace == null || conn == null) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tool_call_traces (trace_id, tool_name, channel, arguments, result, success, duration_ms, outcome, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?)")) {
                ps.setString(1, trace.traceId);
                ps.setString(2, ToolCallTrace.truncate(trace.toolName, 120));
                ps.setString(3, ToolCallTrace.truncate(trace.channel, 32));
                ps.setString(4, ToolCallTrace.truncate(trace.arguments, 500));
                ps.setString(5, ToolCallTrace.truncate(trace.result, 500));
                ps.setInt(6, trace.success ? 1 : 0);
                ps.setLong(7, trace.durationMs);
                ps.setString(8, ToolCallTrace.truncate(trace.outcome, 32));
                ps.setLong(9, trace.timestamp);
                ps.executeUpdate();
            } catch (SQLException ignored) {
                // 遥测落库失败不影响业务
            }
            if (++toolTraceInsertCount % 100 == 0) {
                cleanupToolTraces(5000);
            }
        }
    }

    /** 清理过旧工具调用遥测，只保留最近 keep 条。 */
    public void cleanupToolTraces(int keep) {
        if (conn == null || keep <= 0) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM tool_call_traces WHERE id NOT IN (" +
                    "SELECT id FROM tool_call_traces ORDER BY id DESC LIMIT ?)")) {
                ps.setInt(1, keep);
                ps.executeUpdate();
            } catch (SQLException ignored) {
                // 清理失败不影响业务
            }
        }
    }

    /**
     * 按时间倒序列出最近 N 条工具调用遥测。
     * @param limit 最大条数
     */
    public List<ToolCallTrace> listToolTraces(int limit) {
        List<ToolCallTrace> traces = new ArrayList<>();
        if (conn == null) return traces;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT trace_id, tool_name, channel, arguments, result, success, duration_ms, outcome, created_at " +
                    "FROM tool_call_traces ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        traces.add(new ToolCallTrace(
                                rs.getString("trace_id"),
                                rs.getString("tool_name"),
                                rs.getString("channel"),
                                rs.getString("arguments"),
                                rs.getString("result"),
                                rs.getInt("success") != 0,
                                rs.getLong("duration_ms"),
                                rs.getString("outcome"),
                                rs.getLong("created_at")));
                    }
                }
            } catch (SQLException ignored) {
                // 读取失败返回空列表
            }
        }
        return traces;
    }

    /** 工具调用遥测总数。 */
    public int toolTraceCount() {
        return countTable("tool_call_traces");
    }

    /** Harness 执行轨迹总数。 */
    public int harnessTraceCount() {
        return countTable("harness_traces");
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
        temporalStore.invalidateByPrefix("memctx:");
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
        temporalStore.invalidateByPrefix("memctx:");
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
        temporalStore.invalidateByPrefix("memctx:");
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
            String ftsQuery = sanitizeFtsQuery(query);
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

    /** 构建记忆上下文字符串（供 System Prompt 注入）。
     *  按 重要性×新鲜度 加权排序：FTS5 rank 得分 × (1 + importance/10) × 时间衰减因子 */
    public String buildMemoryContext(String query, int maxChars) {
        String cacheKey = "memctx:" + (query == null ? "" : query);
        String cached = temporalStore.get(cacheKey, String.class);
        if (cached != null) return cached;
        List<MemoryEntry> related = searchMemories(query, FTS_MAX_RESULTS * 2);
        if (related.isEmpty()) return null;

        // 按重要性+新鲜度重新排序
        long now = System.currentTimeMillis();
        java.util.Collections.sort(related, new java.util.Comparator<MemoryEntry>() {
            public int compare(MemoryEntry a, MemoryEntry b) {
                double scoreA = getMemoryScore(a, now);
                double scoreB = getMemoryScore(b, now);
                return Double.compare(scoreB, scoreA); // 降序
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("## Related Memories\n");
        sb.append("Previously remembered information that may be relevant:\n\n");

        int chars = 0;
        int limit = maxChars > 0 ? maxChars : CTX_MAX_CHARS;
        int count = 0;
        for (MemoryEntry m : related) {
            if (count >= FTS_MAX_RESULTS) break; // 限制最多 FTS_MAX_RESULTS 条
            double score = getMemoryScore(m, now);
            // 过滤低重要性记忆（< 2 且超过 60 天）
            long ageDays = (now - m.getTimestamp()) / 86_400_000L;
            if (m.getImportance() < 2 && ageDays > 60) continue;
            String line = "- [" + m.getCategory() + "] " + m.getContent();
            if (m.getImportance() >= 8) line += " ⭐"; // 高重要性标记
            line += "\n";
            if (chars + line.length() > limit) break;
            sb.append(line);
            chars += line.length();
            count++;
        }
        String result = sb.toString();
        temporalStore.put(cacheKey, result, 60_000L);
        return result;
    }

    /** 计算记忆的综合得分：importance (0-10) × 时间衰减 (30天半衰期) */
    private double getMemoryScore(MemoryEntry m, long now) {
        double importanceScore = 1.0 + (m.getImportance() / 10.0); // 1.0 ~ 2.0
        long ageMs = now - m.getTimestamp();
        double ageDays = ageMs / 86_400_000.0;
        // 30天半衰期：1天=0.977, 7天=0.85, 30天=0.5, 90天=0.125
        double decay = Math.pow(0.5, ageDays / 30.0);
        return importanceScore * decay;
    }

    // ==================== Notes Context (auto-injection for layered memory) ====================

    /**
     * Build auto-injected notes context from FTS5 search.
     * Unlike {@link #searchNotes} which is user-triggered, this is automatically called
     * before each AI invocation to inject relevant knowledge into the system prompt.
     */
    public String buildNotesContext(String query, int maxChars) {
        String cacheKey = "notectx:" + (query == null ? "" : query);
        String cached = temporalStore.get(cacheKey, String.class);
        if (cached != null) return cached;
        java.util.List<String[]> related = searchNotes(query, 5);
        if (related.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("## Knowledge Base Notes\n");
        sb.append("Relevant notes from the persistent knowledge base:\n\n");

        int chars = 0;
        int limit = maxChars > 0 ? maxChars : 1500;
        for (String[] r : related) {
            String line = "- [#" + r[0] + "] " + r[1];
            if (r[2] != null && !r[2].isEmpty()) {
                line += ": " + r[2];
            }
            line += "\n";
            if (chars + line.length() > limit) break;
            sb.append(line);
            chars += line.length();
        }
        String result = sb.toString();
        temporalStore.put(cacheKey, result, 60_000L);
        return result;
    }

    /** Convenience: build notes context with default char limit */
    public String buildNotesContext(String query) {
        return buildNotesContext(query, 1500);
    }

    // ==================== Corrections Context (纠正记录自动注入) ====================

    /**
     * 构建纠正记录上下文 —— 自动检索与 query 相关的纠正记录并注入系统提示词，
     * 让 AI 在回答前先参考过去被纠正的内容，避免重复犯错。
     */
    public String buildCorrectionsContext(String query, int maxChars) {
        List<CorrectionEntry> related = searchCorrections(query, 5);
        if (related.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("## Corrections (纠错记录)\n");
        sb.append("之前被纠正过的相关记录，回答前先参考、避免重犯:\n\n");

        int chars = 0;
        int limit = maxChars > 0 ? maxChars : 1200;
        for (CorrectionEntry c : related) {
            String line = "- [" + c.getTopic() + "]";
            if (c.getViewpoint() != null && !c.getViewpoint().isEmpty()) line += "(" + c.getViewpoint() + ")";
            line += ": " + c.getContent() + "\n";
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
        // 失效日志上下文缓存（60s TTL 兜底，Redis 短暂旧值可接受）
        temporalStore.invalidateByPrefix("journalctx:");
    }

    /** 构建最近 N 条日志上下文（结果缓存 60s，写入日志时失效）。 */
    public String buildJournalContext(int maxEntries) {
        String cacheKey = "journalctx:" + maxEntries;
        String cached = temporalStore.get(cacheKey, String.class);
        if (cached != null) return cached;
        String journalCtx = null;
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
            journalCtx = sb.toString();
        }
        if (journalCtx != null) {
            temporalStore.put(cacheKey, journalCtx, 60_000L);
        }
        return journalCtx;
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


    // ==================== Impressions ====================

    /** Get impression for a QQ user */
    public ImpressionEntry getImpression(long qq) {
        ImpressionEntry cached = temporalStore.get("imp:" + qq, ImpressionEntry.class);
        if (cached != null) return cached;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT qq, nickname, emotion_stability, interests, speaking_style,"
                    + " honesty, image_habit, impression_level, message_count, first_seen, last_seen, updated_at"
                    + " FROM impressions WHERE qq=?")) {
                ps.setLong(1, qq);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        ImpressionEntry imp = new ImpressionEntry(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getLong(10),
                            rs.getLong(11), rs.getLong(12));
                        temporalStore.put("imp:" + qq, imp);
                        return imp;
                    }
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    /** Get recent impressions (for cross-channel injection) */
    public List<ImpressionEntry> getAllImpressions(int limit) {
        List<ImpressionEntry> list = new ArrayList<>();
        synchronized (lock) {
            String sql = "SELECT qq, nickname, emotion_stability, interests, speaking_style,"
                    + " honesty, image_habit, impression_level, message_count, first_seen, last_seen, updated_at"
                    + " FROM impressions WHERE message_count > 5 ORDER BY last_seen DESC LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new ImpressionEntry(
                            rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            rs.getString(7), rs.getInt(8), rs.getInt(9), rs.getLong(10),
                            rs.getLong(11), rs.getLong(12)));
                    }
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** Insert or update impression */
    public void upsertImpression(ImpressionEntry imp) {
        if (imp == null) return;
        temporalStore.invalidate("imp:" + imp.getQq());
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO impressions (qq, nickname, emotion_stability, interests,"
                    + " speaking_style, honesty, image_habit, impression_level, message_count, first_seen,"
                    + " last_seen, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT(qq) DO UPDATE SET"
                    + " nickname=excluded.nickname, emotion_stability=excluded.emotion_stability,"
                    + " interests=excluded.interests, speaking_style=excluded.speaking_style,"
                    + " honesty=excluded.honesty, image_habit=excluded.image_habit,"
                    + " impression_level=excluded.impression_level,"
                    + " message_count=excluded.message_count, last_seen=excluded.last_seen,"
                    + " updated_at=excluded.updated_at")) {
                ps.setLong(1, imp.getQq());
                ps.setString(2, imp.getNickname() != null ? imp.getNickname() : "");
                ps.setString(3, imp.getEmotionStability());
                ps.setString(4, imp.getInterests());
                ps.setString(5, imp.getSpeakingStyle());
                ps.setString(6, imp.getHonesty());
                ps.setString(7, imp.getImageHabit());
                ps.setInt(8, imp.getImpressionLevel());
                ps.setInt(9, imp.getMessageCount());
                ps.setLong(10, imp.getFirstSeen());
                ps.setLong(11, imp.getLastSeen());
                ps.setLong(12, imp.getUpdatedAt());
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[Persistence] upsertImpression FAILED: " + e.getMessage());
            }
        }
    }

    /** Record a message for impression tracking (lightweight, no upsert) */
    public void recordImpressionMessage(long qq, String nickname) {
        temporalStore.invalidate("imp:" + qq);
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO impressions (qq, nickname, message_count, first_seen, last_seen, updated_at)"
                    + " VALUES (?,?,1,?,?,?)"
                    + " ON CONFLICT(qq) DO UPDATE SET"
                    + " nickname=CASE WHEN excluded.nickname!='' THEN excluded.nickname ELSE nickname END,"
                    + " message_count=message_count+1, last_seen=excluded.last_seen")) {
                long now = System.currentTimeMillis();
                ps.setLong(1, qq);
                ps.setString(2, nickname != null ? nickname : "");
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    // ==================== Group Impressions ====================

    /** Get group impression by group id */
    public GroupImpression getGroupImpression(long groupId) {
        GroupImpression cached = temporalStore.get("gimp:" + groupId, GroupImpression.class);
        if (cached != null) return cached;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT group_id, group_name, friendliness, atmosphere, message_count, first_seen, last_seen, updated_at"
                    + " FROM group_impressions WHERE group_id=?")) {
                ps.setLong(1, groupId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        GroupImpression gi = new GroupImpression(
                                rs.getLong(1), rs.getString(2), rs.getInt(3),
                                rs.getString(4), rs.getInt(5), rs.getLong(6),
                                rs.getLong(7), rs.getLong(8));
                        temporalStore.put("gimp:" + groupId, gi);
                        return gi;
                    }
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    /** Insert or update group impression */
    public void upsertGroupImpression(GroupImpression gi) {
        if (gi == null) return;
        temporalStore.invalidate("gimp:" + gi.getGroupId());
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO group_impressions (group_id, group_name, friendliness, atmosphere, message_count, first_seen, last_seen, updated_at)"
                    + " VALUES (?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT(group_id) DO UPDATE SET"
                    + " group_name=CASE WHEN excluded.group_name!='' THEN excluded.group_name ELSE group_name END,"
                    + " friendliness=excluded.friendliness, atmosphere=excluded.atmosphere,"
                    + " message_count=excluded.message_count, first_seen=group_impressions.first_seen,"
                    + " last_seen=excluded.last_seen, updated_at=excluded.updated_at")) {
                ps.setLong(1, gi.getGroupId());
                ps.setString(2, gi.getGroupName());
                ps.setInt(3, gi.getFriendliness());
                ps.setString(4, gi.getAtmosphere());
                ps.setInt(5, gi.getMessageCount());
                ps.setLong(6, gi.getFirstSeen());
                ps.setLong(7, gi.getLastSeen());
                ps.setLong(8, gi.getUpdatedAt());
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[Persistence] upsertGroupImpression FAILED: " + e.getMessage());
            }
        }
    }

    /** 调整群友好度（情绪联动）：delta 正负增减，-100~100 区间 */
    public void adjustGroupFriendliness(long groupId, int delta) {
        temporalStore.invalidate("gimp:" + groupId);
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO group_impressions (group_id, group_name, friendliness, atmosphere, message_count, first_seen, last_seen, updated_at)"
                    + " VALUES (?,?," + delta + ",'',0,?,?,?)"
                    + " ON CONFLICT(group_id) DO UPDATE SET"
                    + " friendliness=MAX(-100, MIN(100, group_impressions.friendliness + excluded.friendliness)),"
                    + " updated_at=excluded.updated_at")) {
                long now = System.currentTimeMillis();
                ps.setLong(1, groupId);
                ps.setString(2, "");
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    /** 记录一条群消息（轻量，累计消息数） */
    public void recordGroupImpressionMessage(long groupId, String groupName) {
        // 不在此失效 gimp 缓存：本方法每条群消息都调用，失效会让 getGroupImpression 缓存形同虚设。
        // 唯一消费方（30%拒答判定）只读 friendliness/atmosphere，二者仅在 upsert/adjust 时变化并已失效。
        // message_count 仅用于导出，不参与缓存读取，短暂滞后可接受。
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO group_impressions (group_id, group_name, friendliness, atmosphere, message_count, first_seen, last_seen, updated_at)"
                    + " VALUES (?,?,0,'',1,?,?,?)"
                    + " ON CONFLICT(group_id) DO UPDATE SET"
                    + " group_name=CASE WHEN excluded.group_name!='' THEN excluded.group_name ELSE group_name END,"
                    + " message_count=group_impressions.message_count+1, last_seen=excluded.last_seen")) {
                long now = System.currentTimeMillis();
                ps.setLong(1, groupId);
                ps.setString(2, groupName != null ? groupName : "");
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    // ==================== User Preferences (结构化偏好) ====================

    /** 设置/更新一条结构化偏好。 */
    public void setPreference(String scope, long targetId, String key, String value, int importance) {
        if (key == null || key.trim().isEmpty() || value == null) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO user_preferences (scope, target_id, pref_key, pref_value, importance, updated_at)"
                    + " VALUES (?,?,?,?,?,?)"
                    + " ON CONFLICT(scope, target_id, pref_key) DO UPDATE SET"
                    + " pref_value=excluded.pref_value, importance=excluded.importance, updated_at=excluded.updated_at")) {
                ps.setString(1, scope != null ? scope : "user");
                ps.setLong(2, targetId);
                ps.setString(3, key.trim());
                ps.setString(4, value);
                ps.setInt(5, Math.max(0, Math.min(10, importance)));
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[Persistence] setPreference FAILED: " + e.getMessage());
            }
        }
        // 偏好写入后按精确 key 失效列表缓存（内存 + Redis 都清，避免旧偏好被 Redis 回源）
        temporalStore.invalidate("pref:" + (scope != null ? scope : "user") + ":" + targetId);
    }

    /** 读取单条偏好。 */
    public String getPreference(String scope, long targetId, String key) {
        if (key == null || key.trim().isEmpty()) return null;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT pref_value FROM user_preferences WHERE scope=? AND target_id=? AND pref_key=?")) {
                ps.setString(1, scope != null ? scope : "user");
                ps.setLong(2, targetId);
                ps.setString(3, key.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    /** 列出某 scope+target 的所有偏好，返回 [key, value, importance]（结果缓存，setPreference 时失效）。 */
    public List<String[]> listPreferences(String scope, long targetId) {
        String scopeN = scope != null ? scope : "user";
        String cacheKey = "pref:" + scopeN + ":" + targetId;
        List<String[]> cached = temporalStore.get(cacheKey, new TypeToken<List<String[]>>(){}.getType());
        if (cached != null) return cached;
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT pref_key, pref_value, importance FROM user_preferences WHERE scope=? AND target_id=? ORDER BY importance DESC, updated_at DESC")) {
                ps.setString(1, scopeN);
                ps.setLong(2, targetId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3))});
                    }
                }
            } catch (SQLException ignored) {}
        }
        temporalStore.put(cacheKey, list, 60_000L);
        return list;
    }

    /** 印象差的人数（impression_level < 0）。 */
    public int countBadImpressions() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM impressions WHERE impression_level < 0")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException ignored) {}
        }
        return 0;
    }

    /** 列出所有偏好（供状态/调试）。 */
    public List<String[]> listAllPreferences() {
        List<String[]> list = new ArrayList<>();
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT scope, target_id, pref_key, pref_value, importance FROM user_preferences ORDER BY scope, target_id, importance DESC")) {
                while (rs.next()) {
                    list.add(new String[]{rs.getString(1), String.valueOf(rs.getLong(2)),
                            rs.getString(3), rs.getString(4), String.valueOf(rs.getInt(5))});
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    // ==================== Corrections (纠正记录) ====================

    /** 记录一条纠正（AI犯错被纠正），返回自增 id */
    public int addCorrection(String topic, String content, String viewpoint, String source, long qq) {
        temporalStore.invalidateByPrefix("corr_topic:");
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO corrections (topic, content, viewpoint, source, qq, created_at, updated_at)"
                    + " VALUES (?,?,?,?,?,?,?)")) {
                long now = System.currentTimeMillis();
                ps.setString(1, topic != null ? topic : "");
                ps.setString(2, content != null ? content : "");
                ps.setString(3, viewpoint != null ? viewpoint : "");
                ps.setString(4, source != null ? source : "user");
                ps.setLong(5, qq);
                ps.setLong(6, now);
                ps.setLong(7, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) { return -1; }
        }
        return -1;
    }

    /** 按主题查询纠正记录（返回全部观点，实现「多观点查询」） */
    public List<CorrectionEntry> getCorrectionsByTopic(String topic) {
        if (topic == null || topic.trim().isEmpty()) return new ArrayList<>();
        String key = "corr_topic:" + topic.trim();
        List<CorrectionEntry> cached = temporalStore.get(key, new TypeToken<List<CorrectionEntry>>(){}.getType());
        if (cached != null) return cached;
        List<CorrectionEntry> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, topic, content, viewpoint, source, qq, created_at, updated_at"
                    + " FROM corrections WHERE topic LIKE ? ORDER BY created_at DESC")) {
                ps.setString(1, "%" + topic.trim() + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(mapCorrection(rs));
                }
            } catch (SQLException ignored) {}
        }
        temporalStore.put(key, list);
        return list;
    }

    /** 模糊搜索纠正记录（匹配 topic 或 content） */
    public List<CorrectionEntry> searchCorrections(String query, int limit) {
        List<CorrectionEntry> list = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return list;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, topic, content, viewpoint, source, qq, created_at, updated_at"
                    + " FROM corrections WHERE topic LIKE ? OR content LIKE ?"
                    + " ORDER BY updated_at DESC LIMIT ?")) {
                String like = "%" + query.trim().replace("'", "''") + "%";
                ps.setString(1, like);
                ps.setString(2, like);
                ps.setInt(3, limit > 0 ? limit : 10);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(mapCorrection(rs));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 获取最近的纠正记录 */
    public List<CorrectionEntry> getAllCorrections(int limit) {
        List<CorrectionEntry> list = new ArrayList<>();
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, topic, content, viewpoint, source, qq, created_at, updated_at"
                    + " FROM corrections ORDER BY updated_at DESC LIMIT ?")) {
                ps.setInt(1, limit > 0 ? limit : 50);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(mapCorrection(rs));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 删除一条纠正记录 */
    public boolean deleteCorrection(long id) {
        temporalStore.invalidateByPrefix("corr_topic:");
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM corrections WHERE id=?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) { return false; }
        }
    }

    private CorrectionEntry mapCorrection(ResultSet rs) throws SQLException {
        return new CorrectionEntry(
                rs.getLong(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getLong(6),
                rs.getLong(7), rs.getLong(8));
    }

    /** Get recent impressions for distillation (return qq+messageCount for users with >10 messages and stale >1h) */
    public List<Long> getUsersForImpressionDistillation(int minMessages, long staleMs) {
        List<Long> list = new ArrayList<>();
        long cutoff = System.currentTimeMillis() - staleMs;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT qq FROM impressions WHERE message_count>=? AND updated_at<?")) {
                ps.setInt(1, minMessages);
                ps.setLong(2, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) list.add(rs.getLong(1));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }


        // ==================== Stickers ====================

    /** 添加表情包条目 */
    public StickerEntry addSticker(String imageUrl, String filePath, String context, String keywords, String remark) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) return null;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO stickers (image_url, file_path, context, keywords, remark, usage_count, created_at) "
                    + "VALUES (?,?,?,?,?,0,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                long now = System.currentTimeMillis();
                ps.setString(1, imageUrl.trim());
                String fp = (filePath != null && !filePath.isEmpty()) ? filePath : "";
                ps.setString(2, fp);
                ps.setString(3, context != null ? context : "");
                String kw = (keywords != null && !keywords.trim().isEmpty())
                        ? keywords.trim() : StickerEntry.extractKeywords(context);
                ps.setString(4, kw);
                ps.setString(5, remark != null ? remark : "");
                ps.setLong(6, now);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) {
                        StickerEntry e = new StickerEntry(rs.getInt(1), imageUrl.trim(), filePath, context, kw, now);
                        e.setRemark(remark != null ? remark : "");
                        return e;
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
                         "SELECT id, image_url, file_path, context, keywords, remark, usage_count, created_at "
                         + "FROM stickers ORDER BY created_at DESC")) {
                while (rs.next()) {
                    StickerEntry se = new StickerEntry(
                            rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getLong(8));
                    se.setUsageCount(rs.getInt(7));
                    se.setRemark(rs.getString(6));
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

    // ==================== Image Remarks (图片注释) ====================

    /** 获取图片注释（按 MD5 强绑定），未命中返回 null */
    public String getImageRemark(String md5) {
        if (md5 == null || md5.isEmpty()) return null;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT remark FROM image_remarks WHERE image_md5=?")) {
                ps.setString(1, md5);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    /** 写入/更新图片注释（AI 修改或 OCR 生成），UPSERT 语义 */
    public void setImageRemark(String md5, String remark, String source) {
        if (md5 == null || md5.isEmpty() || remark == null) return;
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO image_remarks (image_md5, remark, source, updated_at) VALUES (?,?,?,?) "
                    + "ON CONFLICT(image_md5) DO UPDATE SET remark=excluded.remark, "
                    + "source=excluded.source, updated_at=excluded.updated_at")) {
                ps.setString(1, md5);
                ps.setString(2, remark);
                ps.setString(3, source != null ? source : "ai");
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                AiAgentActivity.debugLog("[Persistence] setImageRemark FAILED: " + e.getMessage());
            }
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

    // ==================== Alarms (系统闹钟 AI Alarm) ====================

    /** 添加闹钟，返回自增 ID */
    public int addAlarm(String scope, String schedule, String task, String snapshot,
                        String notes, String channel, long senderQq, boolean isGroup,
                        long groupId, boolean isMaster, boolean repeat) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO alarms (scope, schedule, task, snapshot, notes, channel, " +
                    "sender_qq, is_group, group_id, is_master, repeat, enabled, last_run, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,1,0,?)")) {
                ps.setString(1, scope != null ? scope : "REMIND");
                ps.setString(2, schedule != null ? schedule : "");
                ps.setString(3, task != null ? task : "");
                ps.setString(4, snapshot);
                ps.setString(5, notes);
                ps.setString(6, channel != null ? channel : "console");
                ps.setLong(7, senderQq);
                ps.setInt(8, isGroup ? 1 : 0);
                ps.setLong(9, groupId);
                ps.setInt(10, isMaster ? 1 : 0);
                ps.setInt(11, repeat ? 1 : 0);
                ps.setLong(12, System.currentTimeMillis());
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) return rs.getInt(1);
                }
            } catch (SQLException e) { return -1; }
        }
        return -1;
    }

    /** 列出所有闹钟 */
    public List<AlarmEntry> listAlarms() {
        return listAlarms(false);
    }

    /** 列出闹钟；onlyEnabled=true 时只返回启用项（调度轮询用）。 */
    public List<AlarmEntry> listAlarms(boolean onlyEnabled) {
        List<AlarmEntry> list = new ArrayList<>();
        synchronized (lock) {
            String sql = onlyEnabled
                    ? "SELECT * FROM alarms WHERE enabled=1 ORDER BY created_at"
                    : "SELECT * FROM alarms ORDER BY created_at";
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    list.add(mapAlarm(rs));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 获取单个闹钟 */
    public AlarmEntry getAlarm(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM alarms WHERE id=?")) {
                ps.setInt(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return mapAlarm(rs);
                }
            } catch (SQLException ignored) {}
        }
        return null;
    }

    /** 删除闹钟（同时删除其快照/备注） */
    public boolean removeAlarm(int id) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM alarms WHERE id=?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) { return false; }
        }
    }

    /** 追加备注到闹钟（notes 为 JSON 数组字符串） */
    public boolean appendAlarmNote(int id, String newNotesJson) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE alarms SET notes=? WHERE id=?")) {
                ps.setString(1, newNotesJson);
                ps.setInt(2, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) { return false; }
        }
    }

    /** 设置闹钟启用/禁用 */
    public boolean setAlarmEnabled(int id, boolean enabled) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE alarms SET enabled=? WHERE id=?")) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setInt(2, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) { return false; }
        }
    }

    /** 更新闹钟最后执行时间 */
    public void updateAlarmLastRun(int id, long ts) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement("UPDATE alarms SET last_run=? WHERE id=?")) {
                ps.setLong(1, ts);
                ps.setInt(2, id);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    private AlarmEntry mapAlarm(ResultSet rs) throws SQLException {
        return new AlarmEntry(
                rs.getInt("id"), rs.getString("scope"), rs.getString("schedule"),
                rs.getString("task"), rs.getString("snapshot"), rs.getString("notes"),
                rs.getString("channel"), rs.getLong("sender_qq"),
                rs.getInt("is_group") != 0, rs.getLong("group_id"),
                rs.getInt("is_master") != 0, rs.getInt("repeat") != 0,
                rs.getInt("enabled") != 0, rs.getLong("last_run"), rs.getLong("created_at"));
    }

    // ==================== Notes (knowledge base) ====================

    /** add note (带去重：title 精确匹配时新内容替换旧笔记，保持知识最新), returns auto-inc id */
    public int addNote(String title, String content, String tags) {
        temporalStore.invalidateByPrefix("notectx:");
        synchronized (lock) {
            String normTitle = title != null ? title.trim() : "";
            String normContent = content != null ? content.trim() : "";
            // 去重：title 精确匹配时，直接用新内容替换旧笔记（保持知识最新）
            if (!normTitle.isEmpty()) {
                try (java.sql.PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM notes WHERE title = ? ORDER BY updated_at DESC LIMIT 1")) {
                    ps.setString(1, normTitle);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            int oldId = rs.getInt(1);
                            updateNote(oldId, normTitle, normContent, tags);
                            AiAgentActivity.debugLog("[Note] 去重替换：相同标题，已用新内容更新 #" + oldId);
                            return oldId;
                        }
                    }
                } catch (java.sql.SQLException e) {
                    // 去重查询失败则走正常插入
                }
            }
            // 正常插入
            try (java.sql.PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO notes (title, content, tags, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                long now = System.currentTimeMillis();
                ps.setString(1, normTitle);
                ps.setString(2, normContent);
                ps.setString(3, tags != null ? tags.trim() : "");
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
                ps.setString(1, sanitizeFtsQuery(query));
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
        temporalStore.invalidateByPrefix("notectx:");
        synchronized (lock) {
            try (java.sql.PreparedStatement ps = conn.prepareStatement("DELETE FROM notes WHERE id=?")) {
                ps.setInt(1, id);
                return ps.executeUpdate() > 0;
            } catch (java.sql.SQLException e) { return false; }
        }
    }

    /** update note */
    public boolean updateNote(int id, String title, String content, String tags) {
        temporalStore.invalidateByPrefix("notectx:");
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

    // ==================== Skills (delegated to PersistenceSkills) ====================

    public int addSkill(String name, String category, String description,
                        String content, String source) {
        return skillsDb.addSkill(name, category, description, content, source, "task");
    }

    public int addSkill(String name, String category, String description,
                        String content, String source, String scope) {
        return skillsDb.addSkill(name, category, description, content, source, scope);
    }

    public boolean updateSkill(int id, String name, String description,
                               String content, int newVersion) {
        return skillsDb.updateSkill(id, name, description, content, newVersion);
    }

    public boolean removeSkill(int id) {
        return skillsDb.removeSkill(id);
    }

    public SkillEntry getSkill(int id) {
        return skillsDb.getSkill(id);
    }

    public List<SkillEntry> listAllSkills() {
        return skillsDb.listAllSkills();
    }

    public List<SkillEntry> searchSkills(String query, int limit) {
        return skillsDb.searchSkills(query, limit, PersistenceManager::sanitizeFtsQuery);
    }

    public SkillEntry findSimilarSkill(String name, String description) {
        return skillsDb.findSimilarSkill(name, description);
    }

    public void incrementSkillUsage(int id, boolean success) {
        skillsDb.incrementSkillUsage(id, success);
    }

    public List<SkillEntry> getSkillsForEvolution(int minFailureCount, int minTotal) {
        return skillsDb.getSkillsForEvolution(minFailureCount, minTotal);
    }

    public boolean deprecateSkill(int id) {
        return skillsDb.deprecateSkill(id);
    }

    public boolean markSkillMerged(int id, int mergedIntoId) {
        return skillsDb.markSkillMerged(id, mergedIntoId);
    }

    public List<SkillEntry> getSkillsByScope(String scope) {
        return skillsDb.getSkillsByScope(scope);
    }

    public List<SkillEntry> getGeneralSkills() {
        return skillsDb.getGeneralSkills();
    }

    public List<SkillEntry> getPersonaSkills() {
        return skillsDb.getPersonaSkills();
    }


    // ==================== 导入导出（JSON） ====================

    /** 清空所有笔记 */
    public void clearNotes() {
        temporalStore.invalidateByPrefix("notectx:");
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM notes");
            } catch (SQLException ignored) {}
        }
    }

    /** 清空所有人物印象 */
    public void clearImpressions() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM impressions");
            } catch (SQLException ignored) {}
        }
    }

    /** 清空所有群印象 */
    public void clearGroupImpressions() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM group_impressions");
            } catch (SQLException ignored) {}
        }
    }

    /** 清空所有表情包数据库记录（不删本地文件） */
    public void clearStickers() {
        synchronized (lock) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("DELETE FROM stickers");
            } catch (SQLException ignored) {}
        }
    }

    /** 列出所有人物印象（不过滤 message_count） */
    public List<ImpressionEntry> listAllImpressions() {
        List<ImpressionEntry> list = new ArrayList<>();
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT qq, nickname, emotion_stability, interests, speaking_style,"
                     + " honesty, image_habit, impression_level, message_count, first_seen, last_seen, updated_at"
                     + " FROM impressions")) {
                while (rs.next()) {
                    list.add(new ImpressionEntry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                            rs.getInt(8), rs.getInt(9), rs.getLong(10), rs.getLong(11), rs.getLong(12)));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    /** 列出所有群印象 */
    public List<GroupImpression> getAllGroupImpressions() {
        List<GroupImpression> list = new ArrayList<>();
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT group_id, group_name, friendliness, atmosphere, message_count,"
                     + " first_seen, last_seen, updated_at FROM group_impressions")) {
                while (rs.next()) {
                    list.add(new GroupImpression(rs.getLong(1), rs.getString(2), rs.getInt(3),
                            rs.getString(4), rs.getInt(5), rs.getLong(6), rs.getLong(7), rs.getLong(8)));
                }
            } catch (SQLException ignored) {}
        }
        return list;
    }

    // ---- DTO ----

    /** 笔记导出记录 */
    public static class NoteRecord {
        public String title;
        public String content;
        public String tags;
        public long createdAt;
        public long updatedAt;
    }

    /** 印象导出包（人物 + 群） */
    public static class ImpressionBundle {
        public List<ImpressionEntry> persons;
        public List<GroupImpression> groups;
    }

    /** 表情包导出记录（图片以 Base64 编码内嵌） */
    public static class StickerRecord {
        public String imageUrl;
        public String filePath;
        public String context;
        public String keywords;
        public String remark;
        public int usageCount;
        public long createdAt;
        public String imageBase64;
    }

    // ---- 长期记忆 ----

    public String exportMemoriesJson() {
        return gson.toJson(listAllMemories());
    }

    public int importMemoriesJson(String json) {
        if (json == null || json.trim().isEmpty()) return 0;
        List<MemoryEntry> list;
        try {
            list = gson.fromJson(json, new TypeToken<List<MemoryEntry>>(){}.getType());
        } catch (Exception e) { return -1; }
        if (list == null) return 0;
        clearMemories();
        int count = 0;
        for (MemoryEntry m : list) {
            if (m == null || m.getContent() == null || m.getContent().trim().isEmpty()) continue;
            addMemory(m.getContent(), m.getCategory(), m.getImportance());
            count++;
        }
        return count;
    }

    // ---- 知识库笔记 ----

    public String exportNotesJson() {
        List<NoteRecord> list = new ArrayList<>();
        synchronized (lock) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT title, content, tags, created_at, updated_at FROM notes ORDER BY id")) {
                while (rs.next()) {
                    NoteRecord n = new NoteRecord();
                    n.title = rs.getString(1);
                    n.content = rs.getString(2);
                    n.tags = rs.getString(3);
                    n.createdAt = rs.getLong(4);
                    n.updatedAt = rs.getLong(5);
                    list.add(n);
                }
            } catch (SQLException ignored) {}
        }
        return gson.toJson(list);
    }

    public int importNotesJson(String json) {
        if (json == null || json.trim().isEmpty()) return 0;
        List<NoteRecord> list;
        try {
            list = gson.fromJson(json, new TypeToken<List<NoteRecord>>(){}.getType());
        } catch (Exception e) { return -1; }
        if (list == null) return 0;
        clearNotes();
        int count = 0;
        for (NoteRecord n : list) {
            if (n == null || n.title == null || n.title.trim().isEmpty()) continue;
            insertNoteRaw(n.title, n.content, n.tags, n.createdAt > 0 ? n.createdAt : System.currentTimeMillis());
            count++;
        }
        return count;
    }

    private void insertNoteRaw(String title, String content, String tags, long createdAt) {
        synchronized (lock) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO notes (title, content, tags, created_at, updated_at) VALUES (?,?,?,?,?)")) {
                long now = System.currentTimeMillis();
                ps.setString(1, title != null ? title : "");
                ps.setString(2, content != null ? content : "");
                ps.setString(3, tags != null ? tags : "");
                ps.setLong(4, createdAt > 0 ? createdAt : now);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (SQLException ignored) {}
        }
    }

    // ---- 印象库（人物 + 群） ----

    public String exportImpressionsJson() {
        ImpressionBundle bundle = new ImpressionBundle();
        bundle.persons = listAllImpressions();
        bundle.groups = getAllGroupImpressions();
        return gson.toJson(bundle);
    }

    public int[] importImpressionsJson(String json) {
        if (json == null || json.trim().isEmpty()) return new int[]{0, 0};
        ImpressionBundle bundle;
        try {
            bundle = gson.fromJson(json, ImpressionBundle.class);
        } catch (Exception e) { return new int[]{-1, -1}; }
        int p = 0, g = 0;
        if (bundle != null) {
            clearImpressions();
            if (bundle.persons != null) {
                for (ImpressionEntry imp : bundle.persons) {
                    if (imp == null || imp.getQq() <= 0) continue;
                    upsertImpression(imp);
                    p++;
                }
            }
            clearGroupImpressions();
            if (bundle.groups != null) {
                for (GroupImpression gi : bundle.groups) {
                    if (gi == null || gi.getGroupId() <= 0) continue;
                    upsertGroupImpression(gi);
                    g++;
                }
            }
        }
        return new int[]{p, g};
    }

    // ---- 表情库（图片 Base64） ----

    public String exportStickersJson() {
        List<StickerRecord> list = new ArrayList<>();
        for (StickerEntry s : listAllStickers()) {
            StickerRecord r = new StickerRecord();
            r.imageUrl = s.getImageUrl();
            r.filePath = s.getFilePath();
            r.context = s.getContext();
            r.keywords = s.getKeywords();
            r.remark = s.getRemark();
            r.usageCount = s.getUsageCount();
            r.createdAt = s.getTimestamp();
            r.imageBase64 = readFileBase64(s.getFilePath());
            list.add(r);
        }
        return gson.toJson(list);
    }

    public int importStickersJson(String json) {
        if (json == null || json.trim().isEmpty()) return 0;
        List<StickerRecord> list;
        try {
            list = gson.fromJson(json, new TypeToken<List<StickerRecord>>(){}.getType());
        } catch (Exception e) { return -1; }
        if (list == null) return 0;
        clearStickers();
        deleteStickerFiles();
        int count = 0;
        for (StickerRecord r : list) {
            if (r == null) continue;
            String localPath = (r.imageBase64 != null && !r.imageBase64.isEmpty())
                    ? writeBase64File(r.imageBase64, r.filePath) : "";
            if (localPath == null) localPath = "";
            addSticker(r.imageUrl != null ? r.imageUrl : "", localPath,
                    r.context != null ? r.context : "",
                    r.keywords != null ? r.keywords : "",
                    r.remark != null ? r.remark : "");
            count++;
        }
        return count;
    }

    private String readFileBase64(String path) {
        if (path == null || path.isEmpty()) return "";
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) return "";
            return Base64.getEncoder().encodeToString(Files.readAllBytes(f.toPath()));
        } catch (Exception e) { return ""; }
    }

    private String writeBase64File(String base64, String hintPath) {
        if (base64 == null || base64.isEmpty()) return "";
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            File dir = (dataDir != null) ? new File(dataDir, "stickers") : new File("stickers");
            dir.mkdirs();
            String name = null;
            if (hintPath != null && !hintPath.isEmpty()) {
                String hint = new File(hintPath).getName();
                int dot = hint.lastIndexOf('.');
                if (dot > 0 && dot < hint.length() - 1) {
                    String ext = hint.substring(dot);
                    if (ext.length() <= 6) name = "sticker_" + Math.abs(base64.hashCode()) + "_" + System.currentTimeMillis() + ext;
                }
            }
            if (name == null) name = "sticker_" + System.currentTimeMillis() + "_" + Math.abs(base64.hashCode()) + ".png";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            return out.getAbsolutePath();
        } catch (Exception e) { return ""; }
    }

    private void deleteStickerFiles() {
        if (dataDir == null) return;
        try {
            File dir = new File(dataDir, "stickers");
            if (dir.exists() && dir.isDirectory()) {
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) if (f.isFile()) f.delete();
                }
            }
        } catch (Exception ignored) {}
    }

    // ---- 文件读写 ----

    public boolean exportJsonToFile(String json, File file) {
        if (json == null || file == null) return false;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {
                w.write(json);
            }
            return true;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Persistence] export file failed: " + e.getMessage());
            return false;
        }
    }

    public String readJsonFromFile(File file) {
        if (file == null || !file.exists() || !file.isFile()) return null;
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
            return sb.toString();
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Persistence] read file failed: " + e.getMessage());
            return null;
        }
    }


    private static String trunc(String s, int maxLen) {

        if (s == null || s.isEmpty()) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "…";
    }
}
