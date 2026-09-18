package sair.v4.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.v4.Conf;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 六库通用接口门面（基板①对外只暴露这一层）。
 *
 * <p>全部方法以 {@code lib} 名字为入口（{@code memory/note/dialog/grouplog/sticker/pref}
 * 及中文别名，见 {@link Libs#get(String)}）；名字非法一律返回空/0/-1 并记日志，绝不抛异常 ——
 * 工具面是模型直接调用的，非法参数必须变成一句可读的失败，而不是一次崩溃。</p>
 *
 * <p><b>"有哪些库"问的是注册表</b>（{@link Libs}）：基板自己的环境库 + 插件装载时声明的业务表。
 * 插件没装 = 少一个库，查它是空结果、不是异常（{@code store} 工具会回"未知的库"）。</p>
 *
 * <p>行统一是 {@link JsonObject}：{@code extra}/{@code tags} 入参可给对象或数组（自动序列化存库），
 * 出参自动解析回来；{@code ts} 缺省时由 {@link #put} 自动填当前毫秒。</p>
 *
 * <p>检索：{@link #search} 在有 FTS5 的库上走 {@code MATCH}（中文靠 trigram 分词器），
 * 无 FTS 或 MATCH 无果时回退 {@code LIKE}，结果行带 {@code _hit} 说明命中来源。</p>
 */
public final class Store {

    /** {@code list}/{@code search} 未给 limit 时的默认条数。 */
    public static final int DEF_LIMIT = 100;

    /** 单次查询硬上限，防止模型要"全部"把上下文撑爆。 */
    public static final int MAX_LIMIT = 10000;

    /** {@link #maintain} 里"低重要度"的界定：{@code importance <= 4}。 */
    public static final int LOW_IMPORTANCE = 4;

    /** {@link #maintain} 未给 keepDays 时的默认保留天数。 */
    public static final int DEF_KEEP_DAYS = 30;

    /** {@link #sentList} 未给 limit 时的默认条数（台账只服务于"最近发过什么"）。 */
    public static final int DEF_SENT_LIMIT = 20;

    /** {@link #sentPrune} 默认保留的行数（心跳维护每次裁到这里，别让台账跟着运行时长无限长）。 */
    public static final int DEF_SENT_KEEP = 2000;

    private static final long DAY_MS = 86400000L;

    private final Db db;
    private final Out out;
    /** 库注册表（装配期由 {@code Boot} 注入；与插件装载用的是同一个对象）。 */
    private volatile Libs libs;

    public Store(Db db, Out out) {
        this(db, out, null);
    }

    public Store(Db db, Out out, Libs libs) {
        this.db = db == null ? new Db(null, out) : db;
        this.out = out;
        setLibs(libs);
    }

    /** 注入库注册表（同时告诉 {@link Db}：白名单与建表都用它）。 */
    public void setLibs(Libs l) {
        this.libs = l == null ? new LibRegistry(out) : l;
        if (l == null) ((LibRegistry) this.libs).registerAll(EnvLibs.all());
        if (db != null) db.setLibs(this.libs);
    }

    /** 当前注册表（"有哪些库"的唯一答案）。 */
    public Libs libs() { return libs; }

    /** 建库（内部 {@code new Db + open}）并返回门面。库表由调用方在插件装载之后用
     *  {@code store().db().createTables()} 建 —— 见 {@link Db#createTables()}。 */
    public static Store open(Conf conf, Out out) {
        return open(conf, out, null);
    }

    /** 建库 + 绑定注册表（装配路径：注册表里已经有基板环境库与插件声明的业务库）。 */
    public static Store open(Conf conf, Out out, Libs libs) {
        File f = conf == null ? null : conf.dbFile();
        Db db = new Db(f, out);
        Store s = new Store(db, out, libs);
        // 配置说某个库不建全文索引：必须在 open() 之前告诉 Db（否则建表那一步会把索引建回来）
        if (conf != null) {
            for (String lib : conf.ftsOffLibs()) db.ftsSkip(lib);
        }
        db.open();
        return s;
    }

    public Db db() { return db; }

    // ---------------------------------------------------------------- 通用 CRUD

    /**
     * 列表查询。
     *
     * @param filter 等值过滤 {@code {字段: 值}}；null 值忽略；支持控制键
     *               {@code _like:{字段:片段}}、{@code _gte}/{@code _lte}:{字段:值}
     * @param limit  {@code <=0} 用 {@link #DEF_LIMIT}，超过 {@link #MAX_LIMIT} 截断
     * @param order  形如 {@code "ts DESC"} / {@code "importance DESC, ts DESC"}；
     *               列名不在本库字段里则退回本库默认排序（有 ts 用 ts，否则 id）
     */
    public List<JsonObject> list(String lib, JsonObject filter, int limit, String order) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("list: 未知库 " + lib);
            return new ArrayList<JsonObject>();
        }
        List<Object> args = new ArrayList<Object>();
        String where = whereOf(l, filter, args);
        String sql = "SELECT * FROM " + l.table() + where
                + " ORDER BY " + orderOf(l, order)
                + " LIMIT " + norm(limit);
        return db.query(sql, args.toArray());
    }

    /** 按 id 取一行，不存在返回 null。 */
    public JsonObject get(String lib, long id) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("get: 未知库 " + lib);
            return null;
        }
        return db.queryOne("SELECT * FROM " + l.table() + " WHERE id = ?", id);
    }

    /** 带 id（{@code id>0}）则按 id 更新，否则插入；返回 id（失败 -1）。 */
    public long put(String lib, JsonObject row) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("put: 未知库 " + lib);
            return -1L;
        }
        if (row == null) {
            warn("put: row 为 null（" + l.table() + "）");
            return -1L;
        }
        synchronized (db) {
            long id = J.l(row, "id", 0L);
            if (id > 0L) {
                return db.update(l.table(), without(row, "id"), "id = ?", id) > 0 ? id : -1L;
            }
            if (l.hasTs() && J.get(row, "ts") == null) row.addProperty("ts", System.currentTimeMillis());
            return db.insert(l.table(), row);
        }
    }

    /** 局部更新（patch 里的键才会被写），返回是否真的改到了行。 */
    public boolean update(String lib, long id, JsonObject patch) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("update: 未知库 " + lib);
            return false;
        }
        if (patch == null || patch.size() == 0 || id <= 0L) return false;
        synchronized (db) {
            return db.update(l.table(), without(patch, "id"), "id = ?", id) > 0;
        }
    }

    /** 按 id 删除。 */
    public boolean delete(String lib, long id) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("delete: 未知库 " + lib);
            return false;
        }
        if (id <= 0L) return false;
        synchronized (db) {
            return db.delete(l.table(), "id = ?", id) > 0;
        }
    }

    /** 条件计数（filter 语义同 {@link #list}）。 */
    public long count(String lib, JsonObject filter) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("count: 未知库 " + lib);
            return 0L;
        }
        List<Object> args = new ArrayList<Object>();
        String where = whereOf(l, filter, args);
        return db.count(l.table(), where.trim().isEmpty() ? null : where.substring(" WHERE ".length()), args.toArray());
    }

    // ---------------------------------------------------------------- 检索

    /**
     * 全文检索（三层，逐层放宽）：<br>
     * ① FTS5 {@code MATCH "整串短语"} —— 单个词/子串的精确口径（走索引，最快）；<br>
     * ② 短语 0 命中时再试<b>拆词 OR</b>（{@link FtsQuery#build}，V3 口径）——
     *    多词/带空格的自然语言查询（"AI Agent 记忆"）用短语要求连续同序，几乎必然 0 命中，
     *    而真实语料里有几百条相关记录；这一步把"召回为 0"修回来；<br>
     * ③ 仍无命中或没有 FTS → 回退 {@code LIKE %整串%}（同时是 sticker/pref 的唯一检索路径）。<br>
     * 结果行附带 {@code _hit}：{@code "fts"} 或命中的列名。
     */
    public List<JsonObject> search(String lib, String text, int limit) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("search: 未知库 " + lib);
            return new ArrayList<JsonObject>();
        }
        if (Str.blank(text)) return new ArrayList<JsonObject>();
        int n = norm(limit);
        String q = text.trim();
        List<JsonObject> rows = new ArrayList<JsonObject>();

        if (l.fts() && db.fts() && !db.ftsSkipped(l.table())) {
            String t = l.table();
            String vt = l.ftsTable();
            String ord = l.hasTs() ? "t.ts DESC, t.id DESC" : "t.id DESC";
            rows = db.query("SELECT t.* FROM " + t + " t JOIN " + vt + " ON " + vt + ".rowid = t.id"
                    + " WHERE " + vt + " MATCH ? ORDER BY " + ord + " LIMIT " + n, FtsQuery.phrase(q));
            if (rows.isEmpty()) {
                String or = FtsQuery.hasCjk(q) ? FtsQuery.build(q, FtsQuery.DEFAULT_MAX_TERMS) : null;
                if (or != null && !or.equals(FtsQuery.phrase(q))) {
                    rows = db.query("SELECT t.* FROM " + t + " t JOIN " + vt + " ON " + vt + ".rowid = t.id"
                            + " WHERE " + vt + " MATCH ? ORDER BY " + ord + " LIMIT " + n, or);
                    if (!rows.isEmpty()) dim("search: " + vt + " 短语无命中，拆词 OR 命中 " + rows.size() + " 行（" + q + "）");
                }
            }
            if (!rows.isEmpty()) {
                for (JsonObject o : rows) o.addProperty("_hit", "fts");
                return rows;
            }
            dim("search: " + vt + " MATCH 无命中，回退 LIKE（" + q + "）");
        }
        return like(l, q, n);
    }

    /** LIKE 回退（同时是 sticker/pref 的唯一检索路径）。 */
    private List<JsonObject> like(LibSpec l, String q, int n) {
        String[] cols = l.searchCols();
        if (cols.length == 0) return new ArrayList<JsonObject>();
        StringBuilder w = new StringBuilder();
        List<Object> args = new ArrayList<Object>();
        String pat = "%" + FtsQuery.likeEscape(q) + "%";
        for (String c : cols) {
            if (w.length() > 0) w.append(" OR ");
            w.append(c).append(" LIKE ? ESCAPE '\\'");
            args.add(pat);
        }
        List<JsonObject> rows = db.query("SELECT * FROM " + l.table() + " WHERE (" + w + ")"
                + " ORDER BY " + l.defOrder() + " LIMIT " + n, args.toArray());
        for (JsonObject o : rows) o.addProperty("_hit", hitCol(l, o, q));
        return rows;
    }

    private static String hitCol(LibSpec l, JsonObject row, String q) {
        for (String c : l.searchCols()) {
            if (J.s(row, c, "").indexOf(q) >= 0) return c;
        }
        return "like";
    }

    /** 把用户文本包成 FTS5 短语，避免 {@code AND}、{@code OR}、{@code NEAR} 与通配符被当成语法。 */
    // ---------------------------------------------------------------- 统计 / 导入导出

    /** {@code {"db":路径,"size":主库字节,"disk":含 WAL 的占用,"libs":{六库:条数},"fts":bool,"rows":总条数}}。 */
    public JsonObject stat() {
        JsonObject o = new JsonObject();
        File f = db.file();
        String p = f == null ? "" : f.getAbsolutePath();
        o.addProperty("db", p);
        o.addProperty("size", Fs.size(f));
        // WAL 模式下刚写完的数据还在 -wal 里，只报主库会显得"没写进去"
        o.addProperty("disk", Fs.size(f)
                + Fs.size(new File(p + "-wal"))
                + Fs.size(new File(p + "-shm")));
        JsonObject libs = new JsonObject();
        long total = 0L;
        for (LibSpec l : this.libs.all()) {      // 清单 = 注册表（基板环境库 + 插件声明的业务库）
            long n = db.count(l.table(), null);
            libs.addProperty(l.table(), n);
            total += n;
        }
        o.add("libs", libs);
        o.addProperty("fts", db.fts());
        o.addProperty("rows", total);
        return o;
    }

    /** 导出为 JSONL（每行一条 JSON，UTF-8 无 BOM）。 */
    public boolean exportJson(String lib, File dst) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("exportJson: 未知库 " + lib);
            return false;
        }
        if (dst == null) {
            warn("exportJson: dst 为 null");
            return false;
        }
        synchronized (db) {
            List<JsonObject> rows = db.query("SELECT * FROM " + l.table() + " ORDER BY id");
            File p = dst.getParentFile();
            if (p != null && !p.exists()) Fs.mkdirs(p);
            Writer w = null;
            try {
                w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(dst), Fs.UTF8));
                for (JsonObject row : rows) {
                    w.write(J.json(row));
                    w.write("\n");
                }
                w.flush();
                dim("exportJson: " + l.table() + " -> " + dst.getAbsolutePath() + "（" + rows.size() + " 行）");
                return true;
            } catch (Throwable t) {
                err("exportJson: " + l.table() + " -> " + Db.msg(t));
                return false;
            } finally {
                Db.closeQuiet(w);
            }
        }
    }

    /**
     * 从 JSONL 导入（先 {@code DELETE FROM} 清空覆盖），返回导入条数。
     * <p>参数非法/文件不可读返回 -1；空文件返回 0。</p>
     */
    public int importJson(String lib, File src) {
        LibSpec l = libs.get(lib);
        if (l == null) {
            warn("importJson: 未知库 " + lib);
            return -1;
        }
        if (src == null || !src.isFile()) {
            warn("importJson: 文件不存在 " + src);
            return -1;
        }
        synchronized (db) {
            db.exec("DELETE FROM " + l.table());
            Reader r = null;
            int n = 0;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(src), Fs.UTF8));
                BufferedReader br = (BufferedReader) r;
                String line;
                boolean first = true;
                while ((line = br.readLine()) != null) {
                    if (first) {
                        first = false;
                        if (line.length() > 0 && line.charAt(0) == '\uFEFF') line = line.substring(1);
                    }
                    if (line.trim().isEmpty()) continue;
                    JsonObject row = J.obj(line);
                    if (row == null) {
                        warn("importJson: 跳过非法 JSON 行 -> " + Str.cut(line, 120));
                        continue;
                    }
                    if (db.insert(l.table(), row) > 0L) n++;
                }
                dim("importJson: " + src.getAbsolutePath() + " -> " + l.table() + "（" + n + " 行）");
                return n;
            } catch (Throwable t) {
                err("importJson: " + l.table() + " -> " + Db.msg(t));
                return -1;
            } finally {
                Db.closeQuiet(r);
            }
        }
    }

    // ---------------------------------------------------------------- 记忆维护

    /**
     * 记忆维护（只做 SQL，不读提示词、不写死文案）：
     * <ol>
     *   <li>删超龄低重要度记忆：{@code ts < now-keepDays 且 importance <= }{@link #LOW_IMPORTANCE}</li>
     *   <li>低重要度截断：只保留最新 {@code maxLowImportance} 条（{@code <=0} 表示不截断）</li>
     *   <li>清超龄对话历史（dialog）与群聊历史（grouplog）</li>
     * </ol>
     *
     * <p><b>旧签名（向后兼容）</b>：记忆与两种聊天记录共用同一个 {@code keepDays}，
     * 行为与拆分之前逐字一致。新代码请用
     * {@link #maintain(int, int, int, int)} 分开给两把尺子 —— 记忆不该跟聊天记录绑在一起
     * （群聊动辄几十万行、记忆只有几十行，绑在一起就只能二选一：要么撑爆盘，要么丢记忆）。</p>
     *
     * @return {@code {"memory_deleted":n,"dialog_deleted":n,"grouplog_deleted":n,"compacted":n,
     *         "sent_pruned":n,"cutoff":ms,"keep_days":d}}
     */
    public JsonObject maintain(int keepDays, int maxLowImportance) {
        int days = keepDays <= 0 ? DEF_KEEP_DAYS : keepDays;
        return maintain(days, days, days, maxLowImportance);
    }

    /**
     * 六库维护（分档保留期版）：
     * <ul>
     *   <li>{@code keepDays}：记忆/笔记类 —— 删 {@code memory} 里 {@code ts < now-keepDays}
     *       且 {@code importance <= }{@link #LOW_IMPORTANCE} 的行 + 低重要度截断；
     *       {@code note} 不受维护影响（从不删）。{@code <=0} 用 {@link #DEF_KEEP_DAYS}。</li>
     *   <li>{@code dialogKeepDays}：对话历史，删更早的 {@code dialog} 行；<b>{@code <=0} = 不清理</b>。</li>
     *   <li>{@code grouplogKeepDays}：群聊历史，删更早的 {@code grouplog} 行；<b>{@code <=0} = 不清理</b>。</li>
     * </ul>
     *
     * <p>{@code <=0} 在聊天记录上是"不过期"而不是"用默认值"：保留期是唯一会删数据的旋钮，
     * 0 必须偏向不删。</p>
     *
     * @return 同 {@link #maintain(int, int)}，另加 {@code dialog_keep_days}/{@code grouplog_keep_days}
     *         与两条各自的 {@code *_cutoff}（未清理时为 0）
     */
    public JsonObject maintain(int keepDays, int dialogKeepDays, int grouplogKeepDays, int maxLowImportance) {
        int days = keepDays <= 0 ? DEF_KEEP_DAYS : keepDays;
        long now = System.currentTimeMillis();
        long cutoff = now - days * DAY_MS;
        long dlgCut = dialogKeepDays > 0 ? now - dialogKeepDays * DAY_MS : 0L;
        long glogCut = grouplogKeepDays > 0 ? now - grouplogKeepDays * DAY_MS : 0L;
        JsonObject r = new JsonObject();
        synchronized (db) {
            int mem = db.delete("memory", "ts < ? AND importance <= ?", cutoff, LOW_IMPORTANCE);
            int compacted = 0;
            if (maxLowImportance > 0) {
                compacted = db.delete("memory",
                        "importance <= ? AND id NOT IN"
                                + " (SELECT id FROM memory WHERE importance <= ? ORDER BY ts DESC, id DESC LIMIT ?)",
                        LOW_IMPORTANCE, LOW_IMPORTANCE, maxLowImportance);
            }
            int dlg = dlgCut > 0 ? db.delete("dialog", "ts < ?", dlgCut) : 0;
            int glog = glogCut > 0 ? db.delete("grouplog", "ts < ?", glogCut) : 0;
            // 出站消息台账（sent）也在这里裁一次：它每次心跳都在长（她发的每一条消息一行），
            // 与聊天记录的保留期无关 —— 只按"最近 N 行"裁剪，不按时间（撤回通常发生在这几天内）。
            int sent = sentPrune(DEF_SENT_KEEP);
            r.addProperty("memory_deleted", mem);
            r.addProperty("dialog_deleted", dlg);
            r.addProperty("grouplog_deleted", glog);
            r.addProperty("compacted", compacted);
            r.addProperty("sent_pruned", sent);
            r.addProperty("cutoff", cutoff);
            r.addProperty("keep_days", days);
            r.addProperty("dialog_keep_days", dialogKeepDays);
            r.addProperty("grouplog_keep_days", grouplogKeepDays);
            r.addProperty("dialog_cutoff", dlgCut);
            r.addProperty("grouplog_cutoff", glogCut);
        }
        dim("maintain: " + r.toString());
        return r;
    }

    /**
     * 一次性删掉某库的全文索引（虚表 + 影子表 + 三个同步触发器），返回是否真的没了。
     * <p>这是<b>唯一</b>能真正把 {@code fts_*} 占的空间还回来的动作（索引比表本身大得多时值得做）；
     * 删完该库检索只剩 {@code LIKE %} 回退。<b>触发器必须一起删</b> —— 否则往该表写新行时会往
     * 已经不存在的虚表里插，报 "no such table"。空间要到 {@code VACUUM} 之后才真正还给文件系统；
     * 想让索引别再被建回来，把该库写进配置 {@code ftsOff}（否则下次 {@code Db.open()} 会重建并 rebuild）。</p>
     */
    public boolean dropFts(String lib) {
        LibSpec l = libs.get(lib);
        if (l == null || !l.fts()) {
            warn("dropFts: 该库没有全文索引 -> " + lib);
            return false;
        }
        synchronized (db) {
            for (String suffix : new String[] {"_fts_ai", "_fts_ad", "_fts_au"}) {
                db.exec("DROP TRIGGER IF EXISTS " + l.table() + suffix);
            }
            db.exec("DROP TABLE IF EXISTS " + l.ftsTable());
            db.ftsSkip(l.table());                              // 本次运行内不再走 MATCH
            boolean gone = db.queryOne("SELECT 1 FROM sqlite_master WHERE name = ?", l.ftsTable()) == null;
            dim("dropFts: " + l.table() + " 索引" + (gone ? "已删（检索回退 LIKE）" : "仍在，删除失败"));
            return gone;
        }
    }

    /**
     * 合并某库全文索引的全部段（FTS5 {@code optimize}），返回是否成功。
     * <p>批量删行（保留期清理）之后<b>必须做这一步</b>才谈得上省空间：FTS5 的删除只是往索引里
     * 追加"删除键"，旧词条仍然占着页 —— 实测 {@code grouplog} 删掉 20 万行后 trigram 索引反而从
     * 108 MB 涨到 149 MB。{@code optimize} 把段合成一个并丢掉删除键，之后 {@code VACUUM} 才能把
     * 空间还给文件系统。代价：要重写整个索引（临时占用与用时都不小），属于停机维护动作，
     * 不在 tick 里做。</p>
     */
    public boolean optimizeFts(String lib) {
        LibSpec l = libs.get(lib);
        if (l == null || !l.fts()) {
            warn("optimizeFts: 该库没有全文索引 -> " + lib);
            return false;
        }
        if (db.ftsSkipped(l.table()) || db.queryOne("SELECT 1 FROM sqlite_master WHERE name = ?",
                l.ftsTable()) == null) {
            warn("optimizeFts: " + l.table() + " 没有索引可合并（或已配置 ftsOff）");
            return false;
        }
        synchronized (db) {
            db.exec("INSERT INTO " + l.ftsTable() + "(" + l.ftsTable() + ") VALUES('optimize')");
            dim("optimizeFts: " + l.ftsTable() + " 已整段合并");
        }
        return ftsIntegrityOk(l.ftsTable());
    }

    /** FTS5 外部内容表的自检（索引与内容表不一致会抛错）；返回 true = 一致。 */
    public boolean ftsIntegrityOk(String ftsTable) {
        if (Str.blank(ftsTable)) return false;
        java.sql.Statement st = null;
        try {
            synchronized (db) {
                st = db.conn().createStatement();
                st.execute("INSERT INTO " + ftsTable + "(" + ftsTable + ") VALUES('integrity-check')");
            }
            return true;
        } catch (Throwable t) {
            warn("ftsIntegrity: " + ftsTable + " -> " + Db.msg(t));
            return false;
        } finally {
            Db.closeQuiet(st);
        }
    }

    // ---------------------------------------------------------------- 对话便捷

    /** 追加一条对话（ts 自动填当前毫秒），返回新 id。 */
    public long appendDialog(String session, String role, String content, int tokens) {
        JsonObject row = new JsonObject();
        row.addProperty("ts", System.currentTimeMillis());
        row.addProperty("session", Str.nz(session));
        row.addProperty("role", Str.blank(role) ? "user" : role);
        row.addProperty("content", Str.nz(content));
        row.addProperty("tokens", tokens);
        return db.insert("dialog", row);
    }

    /** 最近 N 条对话，按 ts 升序（老 → 新）返回，便于直接拼进上下文。 */
    public List<JsonObject> recentDialog(String session, int limit) {
        int n = norm(limit);
        String inner = Str.blank(session)
                ? "SELECT * FROM dialog ORDER BY ts DESC, id DESC LIMIT " + n
                : "SELECT * FROM dialog WHERE session = ? ORDER BY ts DESC, id DESC LIMIT " + n;
        String sql = "SELECT * FROM (" + inner + ") ORDER BY ts ASC, id ASC";
        if (Str.blank(session)) return db.query(sql);
        return db.query(sql, session);
    }

    /** 清空某会话的对话；session 为空则清空整表。返回删除条数。 */
    public int clearDialog(String session) {
        synchronized (db) {
            if (Str.blank(session)) return db.delete("dialog", "1=1");
            return db.delete("dialog", "session = ?", session);
        }
    }

    /**
     * 某个群最近 N 条群聊（{@code grouplog}），按时间<b>升序</b>（老 → 新）返回。
     *
     * <p>用途：控制台/诊断按需看一段最近的群聊（{@code Boot.groupRecentFacts}）。
     * 回合上下文<b>不走这一支</b> —— 事实块里那段「本会话最近 N 条聊天记录」由
     * {@code ctx.ChatWindow} 现查（条数 = 配置 {@code chatWindowSize}），
     * 它按主键 {@code id} 取尾部、并且只读需要的那几列。</p>
     *
     * @param groupId 群号；{@code <=0} 返回空表
     */
    public List<JsonObject> recentGrouplog(long groupId, int limit) {
        int n = norm(limit);
        if (groupId <= 0 || n <= 0) return new ArrayList<JsonObject>();
        String inner = "SELECT * FROM grouplog WHERE group_id = ? ORDER BY ts DESC, id DESC LIMIT " + n;
        return db.query("SELECT * FROM (" + inner + ") ORDER BY ts ASC, id ASC", groupId);
    }

    // P9b-2 / CTXWIN 删除：这里原有四支只服务于"会话快照（游标窗口）"的查询 ——
    //   grouplogWindow（游标窗口按 id 升序）、assistantBetween（把机器人自己同时段的话并进快照）、
    //   maxDialogId（私聊的"触发点"）、dialogWindow（私聊游标窗口）。
    // 主人 2026-09-16「取消自动快照，改成触发时动态取一个窗口（chatWindowSize，默认 30）」之后，
    // 基板不再有游标、不再需要那四个量，四支查询的调用点全部消失，故一并删除。
    // 新的那一支查询写在 ctx/ChatWindow.java 里（SQL 只此一处，索引可走性也在那里交代）。

    // ---------------------------------------------------------------- 好感度（权限门禁数据源）

    /** 好感度值，不存在返回 0。 */
    public double favor(long qq) {
        JsonObject o = db.queryOne("SELECT value FROM favor WHERE qq = ?", qq);
        return o == null ? 0.0D : J.d(o, "value", 0.0D);
    }

    /** 写入/覆盖好感度（INSERT OR REPLACE）。 */
    public void setFavor(long qq, double value, String level, String note) {
        db.exec("INSERT OR REPLACE INTO favor(qq, value, level, updated, note) VALUES(?,?,?,?,?)",
                qq, value, Str.nz(level), System.currentTimeMillis(), Str.nz(note));
    }

    /** 好感度排行榜（高 → 低）。 */
    public List<JsonObject> favorTop(int limit) {
        return db.query("SELECT * FROM favor ORDER BY value DESC, qq ASC LIMIT " + norm(limit));
    }

    /** 清空好感度表，返回删除条数。 */
    public int clearFavor() {
        synchronized (db) {
            return db.delete("favor", "1=1");
        }
    }

    /**
     * 按人建账：这个人第一次跟她打交道就建一行（值 0），已经有了就一个字段都不动。
     *
     * <p>为什么要它：主人裁 2026-09-17「可以对每个交流过的人进行单独记录」——
     * 只有每个交流过的人都有自己的行，她才能回答"我的好感度是多少"（0 也是一条记录）。
     * {@code INSERT OR IGNORE} 保证它幂等且不覆盖已有数值；建不上（库不可用）就当没这回事，
     * 绝不抛给调用方（入站路径不许被记账拖垮）。</p>
     */
    public void ensureFavor(long qq) {
        if (qq <= 0L) return;
        try {
            db.exec("INSERT OR IGNORE INTO favor(qq, value, level, updated, note) VALUES(?,?,?,?,?)",
                    qq, 0.0D, "初识", System.currentTimeMillis(), "");
        } catch (Throwable ignored) {
        }
    }

    /** 好感度流水：记一笔（谁、加减多少、改完多少、什么 op、谁改的、为什么）。 */
    public void favorLog(long qq, double delta, double after, String op, String by, String why) {
        if (qq <= 0L) return;
        try {
            db.exec("INSERT INTO favor_event(qq, ts, delta, value_after, op, by, why) VALUES(?,?,?,?,?,?,?)",
                    qq, System.currentTimeMillis(), delta, after, Str.nz(op), Str.nz(by), Str.nz(why));
        } catch (Throwable ignored) {
        }
    }

    /** 某人的好感度流水（新 → 旧）。 */
    public List<JsonObject> favorEvents(long qq, int limit) {
        return db.query("SELECT * FROM favor_event WHERE qq = ? ORDER BY ts DESC, id DESC LIMIT " + norm(limit), qq);
    }

    // ---------------------------------------------------------------- KV

    /** 取 KV，缺失返回 def。 */
    public String kv(String k, String def) {
        if (Str.blank(k)) return def;
        JsonObject o = db.queryOne("SELECT v FROM kv WHERE k = ?", k);
        return o == null ? def : J.s(o, "v", def);
    }

    /** 写 KV（INSERT OR REPLACE）。 */
    public void kvSet(String k, String v) {
        if (Str.blank(k)) {
            warn("kvSet: 空 key");
            return;
        }
        db.exec("INSERT OR REPLACE INTO kv(k, v, updated) VALUES(?,?,?)",
                k, Str.nz(v), System.currentTimeMillis());
    }

    /**
     * 按前缀列 KV（按 key 升序）。技能用多条记录时不必把 JSON 全塞进一个值里。
     *
     * @param prefix 为空 = 全部
     * @param limit  上限（&lt;=0 取 200）
     */
    public List<JsonObject> kvList(String prefix, int limit) {
        int n = limit > 0 ? limit : 200;
        String p = Str.nz(prefix);
        if (p.isEmpty()) return db.query("SELECT k, v, updated FROM kv ORDER BY k ASC LIMIT ?", n);
        return db.query("SELECT k, v, updated FROM kv WHERE k LIKE ? ORDER BY k ASC LIMIT ?", p + "%", n);
    }

    /** 删 KV（按前缀可批量）；返回删除条数。 */
    public int kvDelete(String keyOrPrefix, boolean prefix) {
        if (Str.blank(keyOrPrefix)) return 0;
        synchronized (db) {
            return prefix
                    ? db.delete("kv", "k LIKE ?", Str.nz(keyOrPrefix) + "%")
                    : db.delete("kv", "k = ?", keyOrPrefix);
        }
    }

    // ---------------------------------------------------------------- 偏好（pref 库：三层一次查）

    /**
     * 一次查出<b>三层偏好</b>：{@code user(qq)} + {@code group(gid)} + {@code global}（{@code scope_id=''}），
     * 按 {@code importance DESC, key ASC, id DESC} 排序。
     *
     * <p>为什么专门开一支、而不是调用方发三次 {@link #list}：注入方（{@code ctx.CtxBuild}）<b>每回合</b>
     * 都要拼一次偏好块，三次查询 = 三份结果对象 + 三次 SQL 解析。这里一条 SQL 出去，
     * 三个条件是 {@code (scope, scope_id)} 的等值点查，命中 {@code idx_pref_scope}（不是全表扫）。</p>
     *
     * <p>{@code qq}/{@code gid} 给 {@code <=0} 时对应那一层<b>不进 WHERE</b> —— 控制台/本地没有 QQ 身份，
     * 它只看 {@code global}。排序里带 {@code id DESC} 是给"同一 (scope,scope_id,key) 万一有多行"
     * （表上没有唯一约束）留一个确定性口径：新行在前，调用方先到先留。</p>
     *
     * @param limit 条数上限（{@code <=0} 用 {@link #DEF_LIMIT}，超过 {@link #MAX_LIMIT} 截断）
     */
    public List<JsonObject> prefsFor(long qq, long groupId, int limit) {
        // pref 是「偏好设定」插件声明的库：插件没装 = 没有偏好可注入（返回空列表，不抛异常）。
        // 这里用字面量而不是注册表取表名，是为了让"插件不在"与"表名写错"分得清：
        // 前者返回空、后者注册表里也没有 pref，同样是空。
        if (libs.get("pref") == null) {
            dim("prefsFor: 没有 pref 库（偏好设定插件未装载）→ 注入空列表");
            return new ArrayList<JsonObject>();
        }
        List<String> conds = new ArrayList<String>();
        List<Object> args = new ArrayList<Object>();
        if (qq > 0L) {
            conds.add("(scope = ? AND scope_id = ?)");
            args.add("user");
            args.add(String.valueOf(qq));
        }
        if (groupId > 0L) {
            conds.add("(scope = ? AND scope_id = ?)");
            args.add("group");
            args.add(String.valueOf(groupId));
        }
        conds.add("(scope = 'global' AND scope_id = '')");
        String sql = "SELECT * FROM pref WHERE " + Str.join(conds, " OR ")
                + " ORDER BY importance DESC, key ASC, id DESC LIMIT " + norm(limit);
        return db.query(sql, args.toArray());
    }

    // ---------------------------------------------------------------- 运行表：task

    /** 新建任务（缺 ts/status 自动补），返回 id。 */
    public long addTask(JsonObject row) {
        if (row == null) {
            warn("addTask: row 为 null");
            return -1L;
        }
        if (J.get(row, "ts") == null) row.addProperty("ts", System.currentTimeMillis());
        if (J.get(row, "status") == null) row.addProperty("status", "pending");
        return db.insert("task", row);
    }

    /** 更新任务（局部），返回是否真的改到了行。 */
    public boolean updateTask(long id, JsonObject patch) {
        if (patch == null || patch.size() == 0 || id <= 0L) return false;
        synchronized (db) {
            return db.update("task", without(patch, "id"), "id = ?", id) > 0;
        }
    }

    /** 任务列表（新 → 老）；status 为空则不限状态。 */
    public List<JsonObject> listTasks(String status, int limit) {
        int n = norm(limit);
        if (Str.blank(status)) return db.query("SELECT * FROM task ORDER BY id DESC LIMIT " + n);
        return db.query("SELECT * FROM task WHERE status = ? ORDER BY id DESC LIMIT " + n, status);
    }

    // ---------------------------------------------------------------- 运行表：alarm

    /** 新建或更新闹钟（带 id 且存在则更新，否则插入；不存在时按给定 id 落库），返回 id。 */
    public long upsertAlarm(JsonObject row) {
        if (row == null) {
            warn("upsertAlarm: row 为 null");
            return -1L;
        }
        synchronized (db) {
            long id = J.l(row, "id", 0L);
            if (J.get(row, "ts") == null) row.addProperty("ts", System.currentTimeMillis());
            if (id > 0L) {
                if (db.update("alarm", without(row, "id"), "id = ?", id) > 0) return id;
                return db.insert("alarm", row);
            }
            return db.insert("alarm", row);
        }
    }

    /** 闹钟列表（按触发时间升序）；enabledOnly=true 只给启用中的。 */
    public List<JsonObject> listAlarms(boolean enabledOnly) {
        return db.query("SELECT * FROM alarm"
                + (enabledOnly ? " WHERE enabled = 1" : "")
                + " ORDER BY fire_at ASC, id ASC");
    }

    public boolean deleteAlarm(long id) {
        if (id <= 0L) return false;
        synchronized (db) {
            return db.delete("alarm", "id = ?", id) > 0;
        }
    }

    // ---------------------------------------------------------------- 运行表：skill_index

    /** 记录/刷新一个技能的索引行（按 name 覆盖）。 */
    public void indexSkill(String name, String hash, String tool, String permission, String hooks) {
        if (Str.blank(name)) {
            warn("indexSkill: 空 name");
            return;
        }
        db.exec("INSERT OR REPLACE INTO skill_index(name, hash, tool, permission, hooks, loaded_at) VALUES(?,?,?,?,?,?)",
                name, Str.nz(hash), Str.nz(tool), Str.nz(permission), Str.nz(hooks), System.currentTimeMillis());
    }

    public void removeSkillIndex(String name) {
        if (Str.blank(name)) return;
        synchronized (db) {
            db.delete("skill_index", "name = ?", name);
        }
    }

    /** 全部技能索引行（按 name 升序）。 */
    public List<JsonObject> skillIndex() {
        return db.query("SELECT * FROM skill_index ORDER BY name ASC");
    }

    // ---------------------------------------------------------------- 运行表：sent（出站消息台账）

    /**
     * 记一条"我发出去的消息"（F5a：撤回只能按 {@code message_id} 定位，所以先留底账）。
     *
     * <p>{@code messageId <= 0} 直接返回 {@code -1} 且<b>不落行</b>：没有 id 的消息撤回不了，
     * 记下来只会让台账里多一堆查不到东西的行。</p>
     *
     * @param session 会话键（{@code group:<群号>} / {@code user:<QQ>}；推不出给空串）
     * @param action  真实动作名（{@code send_group_msg} / {@code send_private_msg} / {@code send_msg}）
     * @param preview 出站正文的纯文本预览（前 ~60 字，供辨认）
     * @return 自增 id；没记成返回 {@code -1}
     */
    public long sentAdd(String session, String action, long messageId, String preview) {
        if (messageId <= 0L) return -1L;
        JsonObject row = new JsonObject();
        row.addProperty("ts", System.currentTimeMillis());
        row.addProperty("session", Str.nz(session));
        row.addProperty("action", Str.nz(action));
        row.addProperty("message_id", messageId);
        row.addProperty("preview", Str.nz(preview));
        row.addProperty("state", "sent");
        return db.insert("sent", row);
    }

    /**
     * 台账清单（新的在前）：{@code session} 空 = 不限会话；{@code sinceTs > 0} = 只看这个时刻之后发的。
     *
     * @param limit {@code <= 0} 用 {@link #DEF_SENT_LIMIT}（超过 {@link #MAX_LIMIT} 会被夹到上限）
     */
    public List<JsonObject> sentList(String session, long sinceTs, int limit) {
        int n = limit <= 0 ? DEF_SENT_LIMIT : Math.min(limit, MAX_LIMIT);
        StringBuilder sql = new StringBuilder("SELECT * FROM sent WHERE 1 = 1");
        List<Object> args = new ArrayList<Object>();
        if (!Str.blank(session)) {
            sql.append(" AND session = ?");
            args.add(Str.trim(session));
        }
        if (sinceTs > 0L) {
            sql.append(" AND ts >= ?");
            args.add(sinceTs);
        }
        sql.append(" ORDER BY ts DESC, id DESC LIMIT ?");
        args.add(n);
        return db.query(sql.toString(), args.toArray());
    }

    /**
     * 这个 {@code message_id} 在出站台账里<b>记过没有</b>（P0-1：自我记账的<b>第二层防双写</b>）。
     *
     * <p><b>为什么不用 {@code grouplog.msg_id} 的存在性检查</b>：那个列<b>没有索引</b>
     * （{@code EnvLibs.grouplog()} 的四个索引都在 {@code group_id}/{@code user_id}/{@code ts} 上），
     * 实测一次存在性检查要 <b>61.8 ms 全表扫</b>（几十万行）；而这张台账上现成就有
     * {@code idx_sent_msgid ON sent(message_id)}（{@code Lib.java:108}）⇒ 点查。</p>
     *
     * <p><b>持久层，不是内存 LRU</b>：{@code sent} 是 SQLite 表，所以"同一 {@code msg_id} 只落一行"
     * 这条在<b>跨重启</b>后仍然成立（内存里的 {@code Seen} 只管"同一进程内短窗口"那一层，
     * 见 {@code qq.Seen}）。这条差别的分工是刻意的：{@code Seen}（键 {@code s:<id>}）挡重复投递，
     * 这里挡"进程重启后 NapCat 把同一条 {@code message_sent} 再推一遍"。</p>
     *
     * <p><b>读不出来按"没记过"处理</b>：记账的多寡绝不能变成"这条消息发不出去"的原因
     * （与 {@code QqGateway.blocked} 那条"宁可多回一句"同一取向 —— 这里宁可多记一行）。
     * 真要多记一行也还有第三层：{@code QqGateway.selfEcho} 落的行带 {@code extra.self=true}，
     * 读侧据此可辨。</p>
     *
     * @param messageId 出站消息 id（{@code <=0} 一律 false：没有 id 的行不可能在台账里）
     */
    public boolean sentKnown(long messageId) {
        if (messageId <= 0L) return false;
        try {
            return !db.query("SELECT 1 FROM sent WHERE message_id = ? LIMIT 1", messageId).isEmpty();
        } catch (Throwable t) {
            warn("sentKnown: 台账点查失败（按未记过处理） -> " + t);
            return false;
        }
    }

    /**
     * 某条出站消息在台账里的<b>行主键</b>（P0-1：标记行的 {@code extra.sent_id}）。
     *
     * <p>走 {@code idx_sent_msgid}（与 {@link #sentKnown} 同一把索引）。同一 {@code message_id}
     * 万一有多行（历史遗留），取 {@code id DESC} 的第一条 —— 新的那条才是这一次发送的。</p>
     *
     * @return 行 id；没有或读不出来返回 {@code -1}
     */
    public long sentIdOf(long messageId) {
        if (messageId <= 0L) return -1L;
        try {
            JsonObject o = db.queryOne(
                    "SELECT id FROM sent WHERE message_id = ? ORDER BY id DESC LIMIT 1", messageId);
            long id = o == null ? 0L : J.l(o, "id", 0L);
            return id > 0L ? id : -1L;
        } catch (Throwable t) {
            warn("sentIdOf: 台账点查失败（不写 sent_id） -> " + t);
            return -1L;
        }
    }

    /**
     * 群聊历史（{@code grouplog}）的<b>保留天数</b>（P0-1：回答"她自己的行会不会被提前删掉"）。
     *
     * <p>就是 {@link #maintain(int, int, int, int)} 用的那一把尺子
     * （{@code grouplogKeepDays}，{@code <=0} = <b>不清理</b>）；读出来只为诊断/自省，
     * <b>不</b>改变任何行为。标记行与群聊记录<b>同表同寿</b>：没有任何 {@code grouplog} 专属的
     * 清理规则，只有这一处按天裁剪。</p>
     *
     * <h4>这条"同寿"为什么需要专门写下来</h4>
     * <p>因为同时存在<b>另一条更短的尺子</b>：{@code sent} 台账按<b>行数</b>裁到
     * {@link #DEF_SENT_KEEP} 行（{@link #sentPrune}，每次心跳维护调一次）——
     * 于是标记行里的 {@code extra.sent_id} 会比那一行本身<b>先失效</b>。
     * 两把尺子的区别必须一眼看得出，否则日后有人会把 {@code sent_id} 当永久外键。</p>
     *
     * <h4>返回形状</h4>
     * <p>键名与 {@code maintain} 自己的返回字段<b>逐字一致</b>（{@code grouplog_keep_days} /
     * {@code grouplog_cutoff}），免得两个面用两个名字说同一件事。</p>
     *
     * @param grouplogKeepDays 配置里那一档的<b>生效值</b>（{@code <=0} = 不清理）；
     *                         调用方从 {@code Conf} 取（{@code Store} 不认识 {@code Conf} 的键名）
     * @return {@code {"grouplog_keep_days":d,"grouplog_cutoff":ms,"retention":"days"|"none",
     *         "sent_keep_rows":2000}}
     */
    public JsonObject grouplogRetention(int grouplogKeepDays) {
        JsonObject o = new JsonObject();
        o.addProperty("grouplog_keep_days", grouplogKeepDays);
        o.addProperty("grouplog_cutoff",
                grouplogKeepDays > 0 ? System.currentTimeMillis() - (long) grouplogKeepDays * DAY_MS : 0L);
        o.addProperty("retention", grouplogKeepDays > 0 ? "days" : "none");
        // 另一把尺子（按行数，不是按天）：sent_id 的寿命由它决定
        o.addProperty("sent_keep_rows", DEF_SENT_KEEP);
        return o;
    }

    /** 改一条台账的状态（撤回成功/失败回写）；{@code state} 空按 {@code recalled}。 */
    public boolean sentMark(long id, String state) {
        if (id <= 0L) return false;
        String st = Str.blank(state) ? "recalled" : Str.trim(state);
        synchronized (db) {
            return db.execRows("UPDATE sent SET state = ? WHERE id = ?", st, id) > 0;
        }
    }

    /**
     * 只保留最近 {@code keepRows} 行（默认 {@link #DEF_SENT_KEEP}），返回删掉的行数。
     *
     * <p>由 {@link #maintain(int, int, int, int)} 在<b>心跳维护</b>里调一次 —— 台账是
     * "最近发过什么"的短期底账，不设上限它会跟着运行时长无限长（每次心跳多一行）。</p>
     */
    public int sentPrune(int keepRows) {
        int keep = keepRows <= 0 ? DEF_SENT_KEEP : keepRows;
        synchronized (db) {
            return db.delete("sent", "id NOT IN (SELECT id FROM sent ORDER BY id DESC LIMIT ?)", keep);
        }
    }

    // ---------------------------------------------------------------- 内部：过滤 / 排序

    /** filter → " WHERE ..."（无条件返回空串）。控制键：_like / _gte / _lte。 */
    private String whereOf(LibSpec l, JsonObject f, List<Object> args) {
        if (f == null || f.size() == 0) return "";
        List<String> conds = new ArrayList<String>();
        for (Map.Entry<String, JsonElement> e : f.entrySet()) {
            String k = e.getKey();
            if (k == null || k.isEmpty() || k.charAt(0) == '_') continue;
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            if (!l.hasField(k)) {
                dim("filter: " + l.table() + " 无此列，已忽略 -> " + k);
                continue;
            }
            conds.add(k + " = ?");
            args.add(Db.plain(v));
        }
        range(conds, args, l, J.sub(f, "_gte"), ">=");
        range(conds, args, l, J.sub(f, "_lte"), "<=");
        JsonObject lk = J.sub(f, "_like");
        if (lk != null) {
            for (Map.Entry<String, JsonElement> e : lk.entrySet()) {
                String k = e.getKey();
                JsonElement v = e.getValue();
                if (k == null || v == null || v.isJsonNull() || !l.hasField(k)) continue;
                conds.add(k + " LIKE ? ESCAPE '\\'");
                args.add("%" + FtsQuery.likeEscape(textOf(v)) + "%");
            }
        }
        if (conds.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(" WHERE ");
        for (int i = 0; i < conds.size(); i++) {
            if (i > 0) sb.append(" AND ");
            sb.append(conds.get(i));
        }
        return sb.toString();
    }

    private void range(List<String> conds, List<Object> args, LibSpec l, JsonObject r, String op) {
        if (r == null) return;
        for (Map.Entry<String, JsonElement> e : r.entrySet()) {
            String k = e.getKey();
            JsonElement v = e.getValue();
            if (k == null || v == null || v.isJsonNull() || !l.hasField(k)) continue;
            conds.add(k + " " + op + " ?");
            args.add(Db.plain(v));
        }
    }

    private static String textOf(JsonElement v) {
        if (v.isJsonPrimitive()) return v.getAsString();
        return J.json(v);
    }

    /** order → 白名单校验后的 ORDER BY 子句（不合法退回默认）。 */
    private String orderOf(LibSpec l, String order) {
        if (Str.blank(order)) return l.defOrder();
        List<String> parts = new ArrayList<String>();
        for (String raw : order.split(",")) {
            String[] seg = raw.trim().split("\\s+");
            if (seg.length == 0 || seg[0].isEmpty()) continue;
            String col = seg[0];
            if (!l.hasField(col)) {
                dim("order: " + l.table() + " 无此列，忽略 -> " + col);
                continue;
            }
            String dir = "ASC";
            if (seg.length > 1) {
                if ("desc".equalsIgnoreCase(seg[1])) dir = "DESC";
                else if (!"asc".equalsIgnoreCase(seg[1])) continue;
            }
            parts.add(col + " " + dir);
        }
        if (parts.isEmpty()) return l.defOrder();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        if (l.hasField("id")) sb.append(", id DESC");
        return sb.toString();
    }

    private static int norm(int limit) {
        if (limit <= 0) return DEF_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    /** 浅拷贝去掉若干键（避免把主键写进 SET 子句）。 */
    private static JsonObject without(JsonObject row, String... keys) {
        JsonObject o = new JsonObject();
        Set<String> skip = new HashSet<String>(Arrays.asList(keys));
        for (Map.Entry<String, JsonElement> e : row.entrySet()) {
            if (skip.contains(e.getKey())) continue;
            o.add(e.getKey(), e.getValue());
        }
        return o;
    }

    // ---------------------------------------------------------------- 内部：日志

    private void dim(String s) { if (out != null) out.dim("[store] " + s); }

    private void warn(String s) { if (out != null) out.warn("[store] " + s); }

    private void err(String s) { if (out != null) out.err("[store] " + s); }
}
