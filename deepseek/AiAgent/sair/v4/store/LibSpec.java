package sair.v4.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 一张表的<b>声明</b>（纯数据，只描述结构，不含任何策略）。
 *
 * <p>基板不再把表结构写死在枚举里：基板自己声明可查询的环境库
 * （{@code dialog} / {@code grouplog}），业务表由<b>用到它的插件</b>声明
 * （{@code memory} / {@code note} / {@code sticker} / {@code pref} + 插件自己的新表）。
 * 见 {@code notes/lib-declaration.md}。</p>
 *
 * <h3>三条不许破的规矩</h3>
 * <ol>
 *   <li><b>表名与列名是白名单的唯一来源</b>：{@link Store} 拼 SQL 之前一律拿 {@link #hasField}
 *       校验列名，别处不许再有一份名单。</li>
 *   <li><b>声明是幂等的</b>：{@link #ddl()} 只产出 {@code CREATE TABLE/INDEX IF NOT EXISTS}，
 *       反复跑不出错、不动已有数据。</li>
 *   <li><b>逐字兼容</b>：现网 {@code v4.db} 已经有数据，表名/列名/索引名一个字符都不许改
 *       （改列 = 迁移 = 本阶段禁止的事）。所以索引名可以显式指定（{@link #idx}）。</li>
 * </ol>
 *
 * <h3>为什么还留着 {@code table()} / {@code cn()} 这种"字段的同义方法"</h3>
 * <p>老的调用点写的是 {@code Lib l = Lib.of(lib); l.table()}（共 12 处）。S2 把
 * {@code Lib.of()} 换成 {@code libs.get()} 之后，同一个调用点要能一模一样地编译过去 ——
 * 少改一处调用点就少一份回归风险。</p>
 */
public final class LibSpec {

    /** 一列的声明。 */
    public static final class Col {
        /** 列名（SQL 标识符白名单之一）。 */
        public final String name;
        /** {@code INTEGER} / {@code TEXT} / {@code REAL}。 */
        public final String type;
        /** 默认值字面量（{@code null} = 不写 DEFAULT）。 */
        public final String def;
        /** 是否 {@code NOT NULL}。 */
        public final boolean notNull;

        public Col(String name, String type) { this(name, type, null, false); }

        public Col(String name, String type, String def) { this(name, type, def, false); }

        public Col(String name, String type, String def, boolean notNull) {
            this.name = name;
            this.type = type;
            this.def = def;
            this.notNull = notNull;
        }

        /** 这一列在建表语句里的样子。 */
        public String decl() {
            StringBuilder sb = new StringBuilder(name).append(' ').append(type);
            if (notNull) sb.append(" NOT NULL");
            if (def != null) sb.append(" DEFAULT ").append(def);
            return sb.toString();
        }
    }

    /** 一个索引的声明（名字显式给 —— 索引名也是现网的一部分，不许猜）。 */
    public static final class Idx {
        public final String name;
        public final String cols;

        public Idx(String name, String cols) {
            this.name = name;
            this.cols = cols;
        }
    }

    /** 表名（= SQL 标识符；小写蛇形，与今天一致）。 */
    public final String table;
    /** 中文名（清单、日志、以及模型用中文指库时的显示名）。 */
    public final String cn;
    /** 列（{@code id} / {@code ts} 由基板统一补，这里不用重复声明）。 */
    public final List<Col> cols = new ArrayList<Col>();
    /** FTS5 索引列（空 = 这张表不建虚表；虚表与触发器由 {@link Db} 按这个清单建）。 */
    public final List<String> ftsCols = new ArrayList<String>();
    /** 没有 FTS（或 MATCH 无果）时的 {@code LIKE} 回退列。 */
    public final List<String> likeCols = new ArrayList<String>();
    /** 中文别名（{@code 记忆} → {@code memory}）—— 模型可以用中文指库。 */
    public final List<String> alias = new ArrayList<String>();
    /** 附加索引。 */
    public final List<Idx> index = new ArrayList<Idx>();
    /** 声明者（基板 = {@code ""}；插件 = 文件夹名），只用于诊断，不参与 SQL。 */
    public final String owner;

    public LibSpec(String table, String cn) { this(table, cn, ""); }

    public LibSpec(String table, String cn, String owner) {
        this.table = table;
        this.cn = cn;
        this.owner = owner == null ? "" : owner;
    }

    // ---------- 链式声明（写起来像读一份表定义） ----------

    public LibSpec col(String name, String type) { cols.add(new Col(name, type)); return this; }

    public LibSpec col(String name, String type, String def) { cols.add(new Col(name, type, def)); return this; }

    public LibSpec col(String name, String type, String def, boolean notNull) {
        cols.add(new Col(name, type, def, notNull));
        return this;
    }

    /** 常用组合：{@code ts INTEGER NOT NULL DEFAULT 0}（毫秒，与现网六库一致）。 */
    public LibSpec ts() { return col("ts", "INTEGER", "0", true); }

    public LibSpec fts(String... names) {
        for (String n : names) ftsCols.add(n);
        return this;
    }

    public LibSpec like(String... names) {
        for (String n : names) likeCols.add(n);
        return this;
    }

    public LibSpec alias(String... names) {
        for (String n : names) alias.add(n);
        return this;
    }

    /** 显式索引（现网索引名一律这么给，别用推导）。 */
    public LibSpec idx(String name, String cols) {
        index.add(new Idx(name, cols));
        return this;
    }

    /** 推导索引名（{@code idx_<表名>_<首列>}）；只在推导结果与现网一致时用。 */
    public LibSpec index(String... colsJoined) {
        for (String c : colsJoined) {
            String first = c.split(",")[0].trim();
            index.add(new Idx("idx_" + table + "_" + first, c));
        }
        return this;
    }

    // ---------- 给老调用点的同义方法（见类注释） ----------

    public String table() { return table; }

    public String cn() { return cn; }

    public String owner() { return owner; }

    /** 有 {@code ts} 列吗（没有就按 id 排序）。 */
    public boolean hasTs() { return hasField("ts"); }

    public boolean fts() { return hasFts(); }

    /** 检索用的列：有 FTS 用 FTS 列，否则 LIKE 列（与现网 `searchCols()` 同义）。 */
    public String[] searchCols() {
        List<String> src = ftsCols.isEmpty() ? likeCols : ftsCols;
        return src.toArray(new String[src.size()]);
    }

    /** 默认排序（与现网 `defOrder()` 同义）。 */
    public String defOrder() { return hasTs() ? "ts DESC, id DESC" : "id DESC"; }

    public String ftsTable() { return "fts_" + table; }

    // ---------- 校验（Store/Db 用它当白名单） ----------

    /** 这个列名在这张表里存在吗（含基板统一补的 {@code id} / {@code ts}）。 */
    public boolean hasField(String name) {
        if (name == null || name.isEmpty()) return false;
        if ("id".equals(name) || "ts".equals(name)) return true;
        for (Col c : cols) if (c.name.equals(name)) return true;
        return false;
    }

    /** 需要建 FTS 虚表吗。 */
    public boolean hasFts() { return !ftsCols.isEmpty(); }

    /** 表名或任一别名是否匹配这次查询（大小写不敏感）。 */
    public boolean matches(String key) {
        if (key == null) return false;
        String k = key.trim();
        if (k.equalsIgnoreCase(table)) return true;
        for (String a : alias) if (a.equals(k)) return true;
        return false;
    }

    /** 列名清单（含 {@code id} / {@code ts}，各一份），日志与体检用。 */
    public List<String> fieldNames() {
        List<String> out = new ArrayList<String>();
        out.add("id");
        if (!hasCol("ts")) out.add("ts");
        for (Col c : cols) out.add(c.name);
        return out;
    }

    /** 这个列在 {@link #cols} 里显式声明过吗（{@code id}/{@code ts} 是基板统一补的，不算）。 */
    private boolean hasCol(String name) {
        for (Col c : cols) if (c.name.equals(name)) return true;
        return false;
    }

    /**
     * 建表 + 建索引语句（{@code IF NOT EXISTS}，幂等）。
     *
     * <p>{@code id}/{@code ts} 由基板统一补（见类注释）；声明里若用 {@link #ts()} 又写了一遍，
     * <b>只算一次</b> —— 两处都写是常见写法（{@link EnvLibs} 就是 {@code .ts()} 起头），
     * 但 DDL 里出现两个 {@code ts} 列是 SQL 错误（duplicate column name）。</p>
     *
     * <p>FTS 虚表与它的三个触发器<b>不在这里</b> —— 由 {@link Db} 按 {@link #ftsCols} 统一建
     * （与今天同一处机制）。</p>
     */
    public String[] ddl() {
        StringBuilder sb = new StringBuilder("CREATE TABLE IF NOT EXISTS ").append(table).append('(')
                .append("id INTEGER PRIMARY KEY AUTOINCREMENT");
        if (!hasCol("ts")) sb.append(',').append("ts INTEGER NOT NULL DEFAULT 0");
        for (Col c : cols) sb.append(',').append(c.decl());
        sb.append(')');
        List<String> out = new ArrayList<String>();
        out.add(sb.toString());
        for (Idx i : index) {
            out.add("CREATE INDEX IF NOT EXISTS " + i.name + " ON " + table + "(" + i.cols + ")");
        }
        return out.toArray(new String[out.size()]);
    }

    @Override
    public String toString() {
        return table + (cn.isEmpty() ? "" : "(" + cn + ")") + " 列=" + fieldNames()
                + (hasFts() ? " fts=" + ftsCols : "") + (owner.isEmpty() ? "" : " by=" + owner);
    }
}
