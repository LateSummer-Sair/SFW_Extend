package sair.v4.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;

/**
 * SQLite 连接与通用 CRUD（纯 JDBC，无 ORM、无连接池）。
 *
 * <p>单连接 + 全方法 {@code synchronized}：读写都走同一把锁，写操作天然串行，
 * 不会出现 "database is locked"。WAL 模式让读不阻塞写，{@code busy_timeout=5000}
 * 兜住跨进程（比如另开一个客户端）的短暂争用。</p>
 *
 * <p>表名/列名一律先过<b>库注册表</b>（{@link Libs}）+ 基板环境表（{@link Lib#isEnvTable}）白名单，
 * 值一律走 {@link PreparedStatement} 绑定，因此外部传来的 {@code lib}/{@code filter}/{@code order}
 * 不可能变成注入点。</p>
 *
 * <p><b>建表不在 {@link #open()} 里</b>：open 只连库（PRAGMA + 连接）。表由
 * {@link #createTables()} 建 —— 装配里它在<b>插件装载完成之后</b>跑一次（缺表即建、缺列即补，幂等），
 * 因为业务表（{@code memory}/{@code note}/{@code sticker}/{@code pref}）是插件自己声明的，
 * 插件装载之前注册表里还没有它们。</p>
 *
 * <p>{@link #open()} 幂等（重复调用安全），{@link #close()} 可重复调用。
 * 所有异常就地吞掉并记日志，读失败返回空、写失败返回 -1/0 —— 存储故障不应该炸掉基板主循环。</p>
 */
public final class Db {

    /** JDBC 驱动类名（sqlite-jdbc）。 */
    public static final String DRIVER = "org.sqlite.JDBC";

    private static final long BUSY_TIMEOUT_MS = 5000L;

    /** 幂等标记：-1 未探测，0 不可用，1 可用。 */
    private static final int FTS_UNKNOWN = -1;

    private final File file;
    private final Out out;

    /**
     * 库注册表：白名单、清单、FTS 建表都问它（装配期由 {@code Boot} 注入，与插件装载用的是同一个对象）。
     * <p>没注入时退化成"只有基板环境库"（{@link EnvLibs}）—— 基板自己的环境库永远认得，
     * 业务库则要等插件声明。</p>
     */
    private volatile Libs libs;

    /**
     * 不建全文索引的库表名（配置 {@code ftsOff} → {@link Store#open} 在建表前灌进来）。
     * <p>空集合 = 老行为：所有有正文的库都建 FTS5。集合里的库连索引带同步触发器都不建，
     * 检索只剩 {@code LIKE %} 回退 —— 用检索速度换文件体积。</p>
     */
    private final java.util.Set<String> ftsOff = new java.util.HashSet<String>();

    private Connection conn;
    private int ftsState = FTS_UNKNOWN;

    public Db(File dbFile, Out out) {
        this.file = dbFile;
        this.out = out;
    }

    /** 注入库注册表（装配期调一次；与插件装载、{@link Store} 用的是同一个对象）。 */
    public void setLibs(Libs l) { this.libs = l; }

    /** 当前注册表（没注入时给一个只有基板环境库的：业务库要等插件声明）。 */
    private Libs reg() {
        Libs l = libs;
        if (l != null) return l;
        synchronized (this) {
            if (libs == null) {
                LibRegistry r = new LibRegistry(out);
                r.registerAll(EnvLibs.all());
                libs = r;
            }
            return libs;
        }
    }

    /** 表名 → 规范表名（注册表认得中文别名，比如"记忆"→{@code memory}；环境表原样）；未知返回 null。 */
    private String canon(String table) {
        if (table == null) return null;
        LibSpec s = reg().get(table);
        if (s != null) return s.table;
        return Lib.isEnvTable(table) ? table : null;
    }

    // ---------------------------------------------------------------- 连接

    /** 加载驱动、建目录、开连接、设 PRAGMA（<b>不建表</b>：表见 {@link #createTables()}）。 */
    public synchronized void open() {
        if (isOpen()) return;
        if (file == null) {
            err("Db.open: dbFile 为 null");
            return;
        }
        try {
            Class.forName(DRIVER);
        } catch (Throwable t) {
            err("Db.open: 驱动加载失败 " + DRIVER + " -> " + msg(t));
            return;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.exists() && !Fs.mkdirs(dir)) {
            err("Db.open: 数据目录创建失败 " + dir.getAbsolutePath());
            return;
        }
        try {
            String url = "jdbc:sqlite:" + file.getAbsolutePath().replace('\\', '/');
            conn = DriverManager.getConnection(url);
            conn.setAutoCommit(true);
        } catch (Throwable t) {
            err("Db.open: 连接失败 " + file.getAbsolutePath() + " -> " + msg(t));
            conn = null;
            return;
        }
        pragma("journal_mode", "WAL");
        pragma("busy_timeout", String.valueOf(BUSY_TIMEOUT_MS));
        pragma("foreign_keys", "ON");
        pragma("synchronous", "NORMAL");
        dim("Db.open: " + file.getAbsolutePath() + "（已连接；建表在插件装载之后由 createTables 跑）");
    }

    /**
     * 连接是否<b>真的可用</b>：连上不等于能用 —— 损坏的库（非 SQLite 文件）照样能连上，
     * 却读不了任何表。这里做一次真实读，好让装配能把"六库这一步"标失败。
     *
     * @return null = 可用；否则是给人看的原因
     */
    public synchronized String unusableReason() {
        if (!isOpen()) return "连接未打开";
        Statement st = null;
        ResultSet rs = null;
        try {
            st = conn.createStatement();
            rs = st.executeQuery("SELECT count(*) FROM sqlite_master");
            rs.next();
            return null;
        } catch (Throwable t) {
            return msg(t);
        } finally {
            closeQuiet(rs);
            closeQuiet(st);
        }
    }

    /** 关闭连接（可重复调用）。 */
    public synchronized void close() {
        Connection c = conn;
        conn = null;
        ftsState = FTS_UNKNOWN;
        if (c == null) return;
        try {
            Statement st = c.createStatement();
            try {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            } catch (Throwable ignored) {
                // 检查点失败无所谓，数据仍在 WAL 里
            } finally {
                closeQuiet(st);
            }
        } catch (Throwable ignored) {
            // 连接可能已失效
        }
        try {
            c.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public synchronized boolean isOpen() {
        try {
            return conn != null && !conn.isClosed();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 裸连接（调用方需自行同步；基板内部只给 {@link Store} 用）。 */
    public synchronized Connection conn() { return conn; }

    public File file() { return file; }

    /**
     * 标记"这个库不建全文索引"（库名/表名/中文别名都认）。
     * <p>必须在 {@link #open()} <b>之前</b>调用才有效果（open 里建 FTS 表）。</p>
     */
    public void ftsSkip(String lib) {
        LibSpec s = reg().get(lib);
        String t = s != null ? s.table() : (lib == null ? null : lib.trim().toLowerCase());
        if (t == null || t.isEmpty()) return;
        ftsOff.add(t);
    }

    /** 该表是否被配置为"不建全文索引"。 */
    public boolean ftsSkipped(String table) {
        return table != null && ftsOff.contains(table.trim().toLowerCase());
    }

    /** FTS5 是否可用（open 时探测一次并缓存）。 */
    public boolean fts() {
        if (ftsState != FTS_UNKNOWN) return ftsState == 1;
        synchronized (this) {
            if (ftsState == FTS_UNKNOWN) ftsState = probeFts() ? 1 : 0;
            return ftsState == 1;
        }
    }

    // ---------------------------------------------------------------- 写

    /** 执行一条写语句（含 DDL）：吞异常但记日志。 */
    public synchronized void exec(String sql, Object... args) {
        execOk(sql, args);
    }

    /**
     * 执行一条写语句并返回是否成功（含 DDL）。
     * <p>{@link #exec} 是它的丢弃返回值的版本；建表要用返回值 —— "某个库建表失败"必须是
     * <b>那个库</b>的事（打一行日志、其它库照常），不能只落一条看不出主人的 SQL 错误。</p>
     */
    public synchronized boolean execOk(String sql, Object... args) {
        if (sql == null || sql.trim().isEmpty()) return false;
        if (!isOpen()) {
            err("Db.exec: 连接未打开");
            return false;
        }
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            bind(ps, args);
            ps.execute();
            return true;
        } catch (Throwable t) {
            err("Db.exec: " + oneLine(sql) + " -> " + msg(t));
            return false;
        } finally {
            closeQuiet(ps);
        }
    }

    /** 执行一条写语句并返回受影响行数（失败 -1）。 */
    public synchronized int execRows(String sql, Object... args) {
        if (sql == null || sql.trim().isEmpty()) return -1;
        if (!isOpen()) {
            err("Db.execRows: 连接未打开");
            return -1;
        }
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            bind(ps, args);
            return ps.executeUpdate();
        } catch (Throwable t) {
            err("Db.execRows: " + oneLine(sql) + " -> " + msg(t));
            return -1;
        } finally {
            closeQuiet(ps);
        }
    }

    /** 插入一行，返回新 id（失败 -1）。row 中值为 null 的键不参与写入（走列默认值）。 */
    public synchronized long insert(String table, JsonObject row) {
        if (row == null) {
            warn("Db.insert: row 为 null");
            return -1L;
        }
        String t = canon(table);
        if (t == null) {
            warn("Db.insert: 未知表 " + table);
            return -1L;
        }
        if (!isOpen()) {
            err("Db.insert: 连接未打开");
            return -1L;
        }
        Map<String, Object> cells = cells(t, row);
        if (cells.isEmpty()) {
            warn("Db.insert: " + t + " 没有任何可写列");
            return -1L;
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (String c : cells.keySet()) {
            if (cols.length() > 0) {
                cols.append(", ");
                marks.append(", ");
            }
            cols.append(c);
            marks.append('?');
        }
        String sql = "INSERT INTO " + t + " (" + cols + ") VALUES (" + marks + ")";
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int i = 1;
            for (Object v : cells.values()) ps.setObject(i++, v);
            ps.executeUpdate();
            ResultSet gk = ps.getGeneratedKeys();
            try {
                if (gk != null && gk.next()) return gk.getLong(1);
            } finally {
                closeQuiet(gk);
            }
            return lastRowId();
        } catch (Throwable t2) {
            err("Db.insert: " + t + " -> " + msg(t2));
            return -1L;
        } finally {
            closeQuiet(ps);
        }
    }

    /** 按 where 更新，返回受影响行数（失败 0）。where 为空则拒绝执行（防全表更新）。 */
    public synchronized int update(String table, JsonObject row, String where, Object... args) {
        String t = canon(table);
        if (t == null) {
            warn("Db.update: 未知表 " + table);
            return 0;
        }
        if (row == null || row.size() == 0) return 0;
        if (where == null || where.trim().isEmpty()) {
            warn("Db.update: 拒绝空 where 的全表更新（" + t + "）");
            return 0;
        }
        if (!isOpen()) {
            err("Db.update: 连接未打开");
            return 0;
        }
        Map<String, Object> cells = cells(t, row);
        if (cells.isEmpty()) return 0;
        StringBuilder set = new StringBuilder();
        for (String c : cells.keySet()) {
            if (set.length() > 0) set.append(", ");
            set.append(c).append(" = ?");
        }
        String sql = "UPDATE " + t + " SET " + set + " WHERE " + where;
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            int i = 1;
            for (Object v : cells.values()) ps.setObject(i++, v);
            bind(ps, i, args);
            return ps.executeUpdate();
        } catch (Throwable t2) {
            err("Db.update: " + t + " -> " + msg(t2));
            return 0;
        } finally {
            closeQuiet(ps);
        }
    }

    /** 按 where 删除，返回受影响行数（失败 0）。where 为空则拒绝执行。 */
    public synchronized int delete(String table, String where, Object... args) {
        String t = canon(table);
        if (t == null) {
            warn("Db.delete: 未知表 " + table);
            return 0;
        }
        if (where == null || where.trim().isEmpty()) {
            warn("Db.delete: 拒绝空 where 的全表删除（" + t + "）");
            return 0;
        }
        if (!isOpen()) {
            err("Db.delete: 连接未打开");
            return 0;
        }
        String sql = "DELETE FROM " + t + " WHERE " + where;
        PreparedStatement ps = null;
        try {
            ps = conn.prepareStatement(sql);
            bind(ps, args);
            return ps.executeUpdate();
        } catch (Throwable t2) {
            err("Db.delete: " + t + " -> " + msg(t2));
            return 0;
        } finally {
            closeQuiet(ps);
        }
    }

    /** 计数（失败 0）。 */
    public synchronized long count(String table, String where, Object... args) {
        String t = canon(table);
        if (t == null) {
            warn("Db.count: 未知表 " + table);
            return 0L;
        }
        String sql = "SELECT COUNT(*) FROM " + t
                + (where == null || where.trim().isEmpty() ? "" : " WHERE " + where);
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            if (!isOpen()) {
                err("Db.count: 连接未打开");
                return 0L;
            }
            ps = conn.prepareStatement(sql);
            bind(ps, args);
            rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (Throwable t2) {
            err("Db.count: " + oneLine(sql) + " -> " + msg(t2));
            return 0L;
        } finally {
            closeQuiet(rs);
            closeQuiet(ps);
        }
    }

    // ---------------------------------------------------------------- 读

    /** 查询多行（失败返回空表，不抛异常）。 */
    public synchronized List<JsonObject> query(String sql, Object... args) {
        List<JsonObject> out2 = new ArrayList<JsonObject>();
        if (sql == null || sql.trim().isEmpty()) return out2;
        if (!isOpen()) {
            err("Db.query: 连接未打开");
            return out2;
        }
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement(sql);
            bind(ps, args);
            rs = ps.executeQuery();
            ResultSetMetaData md = rs.getMetaData();
            int n = md.getColumnCount();
            while (rs.next()) out2.add(row(rs, md, n));
        } catch (Throwable t) {
            err("Db.query: " + oneLine(sql) + " -> " + msg(t));
            return new ArrayList<JsonObject>();
        } finally {
            closeQuiet(rs);
            closeQuiet(ps);
        }
        return out2;
    }

    /** 查询一行（无结果返回 null）。 */
    public synchronized JsonObject queryOne(String sql, Object... args) {
        List<JsonObject> l = query(sql, args);
        return l.isEmpty() ? null : l.get(0);
    }

    // ---------------------------------------------------------------- 内部：行 / 值

    private JsonObject row(ResultSet rs, ResultSetMetaData md, int n) {
        JsonObject o = new JsonObject();
        for (int i = 1; i <= n; i++) {
            String name;
            try {
                name = md.getColumnLabel(i);
            } catch (Throwable t) {
                name = "c" + i;
            }
            if (name == null || name.isEmpty()) name = "c" + i;
            int type = Types.OTHER;
            try {
                type = md.getColumnType(i);
            } catch (Throwable ignored) {
                // 用默认类型
            }
            cell(o, rs, i, name, type);
        }
        return decode(o);
    }

    /** 按 JDBC 类型取值；SQLite 动态类型，取值失败就退回字符串。 */
    private void cell(JsonObject o, ResultSet rs, int i, String name, int type) {
        try {
            if (type == Types.INTEGER || type == Types.BIGINT || type == Types.SMALLINT
                    || type == Types.TINYINT || type == Types.BIT || type == Types.BOOLEAN) {
                long v = rs.getLong(i);
                if (rs.wasNull()) o.add(name, JsonNull.INSTANCE);
                else o.addProperty(name, v);
                return;
            }
            if (type == Types.REAL || type == Types.FLOAT || type == Types.DOUBLE
                    || type == Types.NUMERIC || type == Types.DECIMAL) {
                double v = rs.getDouble(i);
                if (rs.wasNull()) o.add(name, JsonNull.INSTANCE);
                else o.addProperty(name, v);
                return;
            }
            String s = rs.getString(i);
            if (s == null) o.add(name, JsonNull.INSTANCE);
            else o.addProperty(name, s);
        } catch (Throwable t) {
            try {
                Object v = rs.getObject(i);
                if (v == null) o.add(name, JsonNull.INSTANCE);
                else o.addProperty(name, String.valueOf(v));
            } catch (Throwable t2) {
                o.add(name, JsonNull.INSTANCE);
            }
        }
    }

    /** extra/tags 这类 JSON 文本列：出参解析回对象/数组，解析失败保持原字符串。 */
    private JsonObject decode(JsonObject o) {
        for (Map.Entry<String, JsonElement> e : new ArrayList<Map.Entry<String, JsonElement>>(o.entrySet())) {
            if (!Lib.isJsonField(e.getKey())) continue;
            JsonElement v = e.getValue();
            if (v == null || !v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) continue;
            String s = v.getAsString().trim();
            if (s.isEmpty()) continue;
            char c = s.charAt(0);
            if (c != '{' && c != '[') continue;
            JsonElement parsed = J.el(s);
            if (parsed != null && (parsed.isJsonObject() || parsed.isJsonArray())) o.add(e.getKey(), parsed);
        }
        return o;
    }

    /** row → 可写列（白名单过滤 + 值转换），null 值跳过。 */
    private Map<String, Object> cells(String table, JsonObject row) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (row == null) return m;
        for (Map.Entry<String, JsonElement> e : row.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isEmpty()) continue;
            if (!isColumn(table, k)) {
                dim("Db: " + table + " 无此列，已忽略 -> " + k);
                continue;
            }
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            m.put(k, plain(v));
        }
        return m;
    }

    /** 这个列名在这张表里存在吗（注册表那条走 {@link LibSpec#hasField}，环境表走 {@link Lib#columns}）。 */
    private boolean isColumn(String table, String col) {
        if (!IDENT.matcher(col).matches()) return false;
        LibSpec s = reg().get(table);
        if (s != null) return s.hasField(col);
        for (String c : Lib.columns(table)) if (c.equals(col)) return true;
        return false;
    }

    /** JsonElement → JDBC 值：标量拆型，对象/数组收紧成 JSON 文本。 */
    static Object plain(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return Integer.valueOf(p.getAsBoolean() ? 1 : 0);
            if (p.isNumber()) {
                double d = p.getAsDouble();
                if (!Double.isNaN(d) && !Double.isInfinite(d) && d == Math.rint(d)
                        && Math.abs(d) < 9.0E18D) {
                    return Long.valueOf(p.getAsLong());
                }
                return Double.valueOf(d);
            }
            return p.getAsString();
        }
        return J.json(e);
    }

    /** 绑定 Object... 参数（JsonElement / 原生类型 / null）。 */
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        bind(ps, 1, args);
    }

    private static void bind(PreparedStatement ps, int from, Object... args) throws SQLException {
        if (args == null) return;
        int i = from;
        for (Object a : args) {
            if (a == null) {
                ps.setObject(i++, null);
            } else if (a instanceof JsonElement) {
                ps.setObject(i++, plain((JsonElement) a));
            } else if (a instanceof Boolean) {
                ps.setInt(i++, ((Boolean) a).booleanValue() ? 1 : 0);
            } else if (a instanceof Integer || a instanceof Long || a instanceof Short || a instanceof Byte) {
                ps.setLong(i++, ((Number) a).longValue());
            } else if (a instanceof Double || a instanceof Float) {
                ps.setDouble(i++, ((Number) a).doubleValue());
            } else if (a instanceof Number) {
                ps.setObject(i++, a);
            } else if (a instanceof File) {
                ps.setString(i++, ((File) a).getAbsolutePath());
            } else {
                ps.setString(i++, String.valueOf(a));
            }
        }
    }

    // ---------------------------------------------------------------- 建表 / FTS

    /**
     * <b>建表（幂等）</b>：基板 5 张环境表（{@link Lib#runDdl()}，一个字不改）+ 每个<b>已注册</b>库的
     * {@link LibSpec#ddl()}（缺表即建、缺列即补）+ FTS 虚表与同步触发器。
     *
     * <p>调用时机：装配里在<b>插件装载完成之后</b>跑一次 —— 业务表（{@code memory}/{@code note}/
     * {@code sticker}/{@code pref}）是插件自己声明的，装载之前注册表里还没有它们。
     * 运行期重扫（{@code skill reload}）时由 {@code Boot} 再调一次，新声明的表立刻落盘。</p>
     *
     * <p>某个库的 DDL 失败只影响<b>那一个库</b>（一行 {@code [store] 库 X 建表失败：…}），
     * 不拖垮装配、不影响别的库。</p>
     *
     * @return 建表成功的库数
     */
    public synchronized int createTables() {
        for (String sql : Lib.runDdl()) exec(sql);
        // 老库补列（幂等、不刷假报错）——见 Lib.ensureColumn 的说明
        Lib.ensureColumn(this, "alarm", "owner", "TEXT DEFAULT ''");
        int ok = 0;
        for (LibSpec s : reg().all()) {
            boolean good = true;
            for (String sql : s.ddl()) if (!execOk(sql)) good = false;
            if (good) {
                good = ensureColumns(s);        // 缺列即补（老库 / 插件改过声明）；补不上也算这个库失败
            }
            if (good) {
                ok++;
            } else {
                warn("[store] 库 " + s.table + " 建表失败：见上一行 SQL 错误（该库本次不可用，其它库照常）");
            }
        }
        setupFts();
        dim("Db: 建表完成（环境表 5 张 + 注册库 " + ok + "/" + reg().size() + " 张，fts=" + fts() + "）");
        return ok;
    }

    /**
     * 缺列即补（幂等、安静）：声明里有、表里没有的列才 {@code ALTER TABLE ADD COLUMN}。
     *
     * <p>与 {@link Lib#ensureColumn} 同一口径（SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，
     * 直接 ALTER 会在补过的库上刷假报错）：<b>先查 {@code PRAGMA table_info}，缺了才加</b>。
     * 只加列 —— 不改列、不删列、不动数据（改列 = 迁移 = 本阶段禁止的事）。</p>
     *
     * @return false = 补列失败（该库本次不可用）
     */
    private boolean ensureColumns(LibSpec s) {
        if (s == null || s.cols.isEmpty()) return true;
        if (!exists(s.table)) return false;                     // 表没建出来（上一步就失败了）
        List<JsonObject> have = query("PRAGMA table_info(" + s.table + ")");
        boolean good = true;
        for (LibSpec.Col c : s.cols) {
            if (c == null || c.name == null || c.name.isEmpty()) continue;
            if ("id".equals(c.name) || "ts".equals(c.name)) continue;
            boolean found = false;
            for (JsonObject row : have) {
                if (c.name.equalsIgnoreCase(J.s(row, "name", ""))) {
                    found = true;
                    break;
                }
            }
            if (found) continue;
            if (!execOk("ALTER TABLE " + s.table + " ADD COLUMN " + c.decl())) good = false;
            else dim("Db: " + s.table + " 补列 " + c.name + "（声明里有、表里没有）");
        }
        return good;
    }

    /**
     * 建 FTS5 虚表（外部内容表 + 触发器同步）。
     * <p>优先 {@code trigram} 分词器：中文子串检索只有它是对的（unicode61 会把整串汉字
     * 当成一个 token，{@code MATCH} 命中不了中间片段）。trigram 不可用则退回默认分词器，
     * {@link Store#search} 在 MATCH 无果时会再回退 LIKE，因此中文检索始终有结果。</p>
     */
    private void setupFts() {
        String[] tokenizers = {"trigram", ""};
        for (String tk : tokenizers) {
            try {
                for (LibSpec lib : reg().all()) {
                    if (!lib.fts()) continue;
                    if (ftsSkipped(lib.table())) continue;      // 配置 ftsOff：不建索引也不建触发器
                    String vt = lib.ftsTable();
                    if (exists(vt)) continue;
                    StringBuilder sb = new StringBuilder("CREATE VIRTUAL TABLE ").append(vt).append(" USING fts5(");
                    String[] cols = lib.ftsCols.toArray(new String[lib.ftsCols.size()]);
                    for (int i = 0; i < cols.length; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(cols[i]);
                    }
                    sb.append(", content='").append(lib.table()).append("', content_rowid='id'");
                    if (!tk.isEmpty()) sb.append(", tokenize='").append(tk).append("'");
                    sb.append(')');
                    exec(sb.toString());
                    triggers(lib, vt, cols);
                    exec("INSERT INTO " + vt + "(" + vt + ") VALUES('rebuild')");
                    dim("Db: 建立全文索引 " + vt + "（tokenize=" + (tk.isEmpty() ? "unicode61" : tk) + "）");
                }
                ftsState = 1;
                return;
            } catch (Throwable t) {
                warn("Db: FTS5 建表失败（tokenize=" + (tk.isEmpty() ? "unicode61" : tk) + "）-> " + msg(t));
            }
        }
        ftsState = 0;
    }

    /** 外部内容表的三个同步触发器（ai/ad/au）。 */
    private void triggers(LibSpec lib, String vt, String[] cols) {
        String t = lib.table();
        StringBuilder names = new StringBuilder();
        StringBuilder news = new StringBuilder();
        StringBuilder olds = new StringBuilder();
        for (String c : cols) {
            if (names.length() > 0) {
                names.append(", ");
                news.append(", ");
                olds.append(", ");
            }
            names.append(c);
            news.append("new.").append(c);
            olds.append("old.").append(c);
        }
        exec("CREATE TRIGGER IF NOT EXISTS " + t + "_fts_ai AFTER INSERT ON " + t
                + " BEGIN INSERT INTO " + vt + "(rowid, " + names + ") VALUES (new.id, " + news + "); END");
        exec("CREATE TRIGGER IF NOT EXISTS " + t + "_fts_ad AFTER DELETE ON " + t
                + " BEGIN INSERT INTO " + vt + "(" + vt + ", rowid, " + names + ") VALUES('delete', old.id, " + olds + "); END");
        exec("CREATE TRIGGER IF NOT EXISTS " + t + "_fts_au AFTER UPDATE ON " + t
                + " BEGIN INSERT INTO " + vt + "(" + vt + ", rowid, " + names + ") VALUES('delete', old.id, " + olds + ");"
                + " INSERT INTO " + vt + "(rowid, " + names + ") VALUES (new.id, " + news + "); END");
    }

    /** 是否已存在某表/虚表（FTS 虚表在 sqlite_master 里也是 table）。 */
    private boolean exists(String name) {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement("SELECT 1 FROM sqlite_master WHERE name = ? LIMIT 1");
            ps.setString(1, name);
            rs = ps.executeQuery();
            return rs.next();
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuiet(rs);
            closeQuiet(ps);
        }
    }

    private boolean probeFts() {
        if (!isOpen()) return false;
        try {
            for (LibSpec lib : reg().all()) {
                if (!lib.fts()) continue;
                if (ftsSkipped(lib.table())) continue;
                if (exists(lib.ftsTable())) return true;
            }
            Statement st = conn.createStatement();
            try {
                st.execute("CREATE VIRTUAL TABLE temp.fts_probe USING fts5(x)");
                st.execute("DROP TABLE temp.fts_probe");
                return true;
            } finally {
                closeQuiet(st);
            }
        } catch (Throwable t) {
            return false;
        }
    }

    private void pragma(String name, String value) {
        if (conn == null) return;
        Statement st = null;
        try {
            st = conn.createStatement();
            st.execute("PRAGMA " + name + "=" + value);
        } catch (Throwable t) {
            warn("Db: PRAGMA " + name + "=" + value + " 失败 -> " + msg(t));
        } finally {
            closeQuiet(st);
        }
    }

    private long lastRowId() {
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            ps = conn.prepareStatement("SELECT last_insert_rowid()");
            rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : -1L;
        } catch (Throwable t) {
            return -1L;
        } finally {
            closeQuiet(rs);
            closeQuiet(ps);
        }
    }

    // ---------------------------------------------------------------- 内部：杂项

    private static final java.util.regex.Pattern IDENT =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static String oneLine(String sql) {
        String s = sql == null ? "" : sql.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    static String msg(Throwable t) {
        if (t == null) return "";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private void dim(String s) { if (out != null) out.dim("[store] " + s); }

    private void warn(String s) { if (out != null) out.warn("[store] " + s); }

    private void err(String s) { if (out != null) out.err("[store] " + s); }

    static void closeQuiet(Object c) {
        if (c == null) return;
        try {
            if (c instanceof ResultSet) ((ResultSet) c).close();
            else if (c instanceof Statement) ((Statement) c).close();
            else if (c instanceof java.io.Closeable) ((java.io.Closeable) c).close();
        } catch (Throwable ignored) {
            // 关闭失败无意义
        }
    }
}
