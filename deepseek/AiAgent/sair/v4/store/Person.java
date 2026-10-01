package sair.v4.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 「这个人」的客观事实（人格化连续性：把人当一等身份，群只是场合）。
 *
 * <p>基板只给<b>数据</b>，不给句子：这里产出的每个字段都是六库聚合出来的客观值
 * （他在哪些群出现过、我有没有记过他、私聊过没有、第一次/最近一次见到是什么时候）。
 * "原来你也在这个群啊"这类话该不该说、怎么说，全部由提示词层的模型决定。</p>
 *
 * <p><b>数据来源只用现成六库，不新增表</b>：<br>
 * ① {@code memory}（{@code scope=user, scope_id=<QQ>}）→ {@code seen_before}：关于他的记忆/印象。
 * 注意<b>不</b>把 {@code grouplog} 的"他发过言"算进这一位 —— 群消息在触发回合之前就已经入库，
 * 用行数判"认不认识"会让所有人第一次说话就变成"认识"；"见过"这件事由
 * {@code known_groups} / {@code msgs} / {@code first_seen} 表达。<br>
 * ② {@code grouplog}（{@code user_id=<QQ>}）→ {@code known_groups} / {@code groups_count} / {@code msgs} /
 * 群聊侧的首末时间。<br>
 * ③ {@code dialog}（{@code session=qq:<QQ>, role=user}）→ {@code dm} 与私聊侧的首末时间。</p>
 *
 * <p><b>★ 能力②/D1（2026-09-28）：外部来源行不算"这个人说的话"</b> —— 同一个 QQ 账号下另有
 * 机器人服务在发言（{@code extra.foreign=true}），外部来源行落进 {@code grouplog} 时
 * {@code user_id} 与她自己的一样是 {@code selfId} ⇒ 只按 {@code user_id} 聚合会把"别家机器人的话"
 * 算进这个账号名下。<b>这道闸现在是 Java 侧的</b>（见 {@link #mayOwnForeignRows}）：SQL 保持批 21
 * 之前的原样（覆盖索引不被破坏），拿到行之后再按 {@code extra} 判。私聊侧的 {@code dialog} 查询按
 * {@code session + role=user} 取，而外部来源行落的是 {@code role=foreign}
 * （{@code QqGateway.DIALOG_ROLE_FOREIGN}）⇒ 结构上取不到。</p>
 *
 * <p><b>量级</b>：{@code grouplog} 可能几十万行。三条查询都走索引、都不做全表扫：<br>
 * ① {@code memory} 走 {@code idx_memory_scope(scope, scope_id)}；<br>
 * ② 总量/首末时间走 {@code idx_grouplog_user(user_id, ts)}（覆盖索引，只扫这个人的索引项）；<br>
 * ③ 按群聚合走覆盖索引 {@link #COVER_INDEX}（{@code user_id, group_id, ts}，只走索引不读表）。
 * <b>这条覆盖索引是性能的全部</b>：只要 WHERE 里出现 {@code extra} 这种"不在索引里"的列，
 * 计划就退化成"索引项 + 每行回表"，实测 5 万行的话痨单次 person 构建从 &lt;50ms 掉到 183ms
 * （门禁相位 20 抓到的那条真回归，原因与修法见 {@link #mayOwnForeignRows}）。
 * 这个覆盖索引由 {@link Lib#ddl()} 建；<b>万一它不在</b>（老库被别的工具打开过、建索引失败），
 * 这里会退化成"最近 {@link #FALLBACK_ROWS} 条发言里出现过的群"这个<b>有界</b>口径 ——
 * 有界取样只是列表不全，绝不会退化成全表扫描（实测：同一张 36 万行表上，缺索引又没有界取样时
 * SQLite 会挑中 {@code idx_grouplog_group} 做全表扫，单次 5.8 秒）。</p>
 */
public final class Person {

    /** {@code known_groups} 最多列几个群（结构性上限，不是文案）：超出只影响列表长度。 */
    public static final int MAX_GROUPS = 20;

    /** 覆盖索引缺失时的取样上限（最近多少条发言里找群）。 */
    public static final int FALLBACK_ROWS = 2000;

    /** 按群聚合用的覆盖索引名（{@link Lib} 建；缺了就退回有界取样）。 */
    public static final String COVER_INDEX = "idx_grouplog_user_group";

    private static final String SQL_MEM = "SELECT COUNT(*) AS n FROM memory WHERE scope = ? AND scope_id = ?";
    // ★ 以下三条 grouplog 查询**逐字节等于批 21 之前**（D1 的第一版把
    //   `AND (extra IS NULL OR extra NOT LIKE '%"foreign":true%')` 拼了进来 —— 那是**真回归**：
    //   extra 不在索引里 ⇒ 覆盖索引失效、每行回表，门禁相位 20 实测话痨（5 万行）从 <50ms 掉到
    //   min=183ms/中位=187ms。过滤改到 Java 侧，见 mayOwnForeignRows。）
    private static final String SQL_GLOG =
            "SELECT COUNT(*) AS n, MIN(ts) AS f, MAX(ts) AS l FROM grouplog WHERE user_id = ?";
    private static final String SQL_GROUPS = "SELECT group_id, COUNT(*) AS n, MIN(ts) AS f, MAX(ts) AS l"
            + " FROM grouplog WHERE user_id = ? GROUP BY group_id";
    private static final String SQL_DM = "SELECT COUNT(*) AS n, MIN(ts) AS f, MAX(ts) AS l"
            + " FROM dialog WHERE session = ? AND role = ?";

    /**
     * 「本账号自己那一侧」的**有界取行**上限（只有 {@link #mayOwnForeignRows} 成立时才会走这条路）。
     *
     * <p>为什么这条路可以取行、而普通调用者不能：普通调用者的两条聚合是<b>纯覆盖索引</b>读
     * （几毫秒），一旦为了过滤去取 {@code extra} 就退化（实测 183ms）；而"问的正是本账号自己"
     * 那一支，行数就是<b>她自己的出站留痕 + 外部来源行</b>（都受 {@code grouplogKeepDays} 的尺子管），
     * 量级与"一个 5 万行的群话痨"完全不是一回事。</p>
     *
     * <p><b>有界口径（如实写在这里）</b>：这一支按 {@code ts DESC LIMIT SELF_SCAN_ROWS} 取最近这么多行
     * 再在内存里聚合 ⇒ 超过这个数时 {@code msgs}/{@code groups_count}/首末时间 变成"最近这么多行"的口径
     * （不是放宽某个既有 limit：这两条 SQL 在批 21 之前<b>没有</b> limit，这一支是本次新引入的界）。
     * 取值比 {@link #FALLBACK_ROWS} 大一个量级，够覆盖"她自己 30 天的出站留痕"。</p>
     */
    public static final int SELF_SCAN_ROWS = 20000;

    /** {@link #mayOwnForeignRows} 那一支的取行（带 {@code extra}，只在 Java 侧过滤时用）。 */
    private static final String SQL_SELF_ROWS =
            "SELECT group_id, ts, extra FROM grouplog WHERE user_id = ? ORDER BY ts DESC LIMIT " + SELF_SCAN_ROWS;

    private Person() {}

    /**
     * 这个调用者问的人，<b>有没有可能带着外部来源行</b>（能力②/D1 的那道闸的开关）。
     *
     * <p><b>为什么需要这一问</b>：外部来源行写的是 {@code user_id = selfId}（{@code QqGateway.foreignEcho}），
     * 所以只有"问的正是本账号自己"时，{@code WHERE user_id = ?} 的结果里才可能混进它们；
     * 任何别的 QQ 都与它们<b>天然不相交</b> ⇒ 那时一个字都不用过滤，SQL 保持覆盖索引（快）。</p>
     *
     * <p><b>为什么过滤必须放在 Java 侧、不能拼进 SQL</b>：{@code extra} 不在任何索引里，
     * {@code extra NOT LIKE …} 会让 {@code idx_grouplog_user(user_id,ts)} / {@code COVER_INDEX}
     * 失去"覆盖"这一性质 ⇒ 每行回表。门禁相位 20 的实测：话痨（5 万行 / 12 群）单次 person 构建
     * min=183ms（中位 187 / max 189），而批 21 之前是 &lt;50ms —— 这条是真回归，本版把 SQL 还原、
     * 过滤挪到 Java 侧，只在"可能带外部行"的这一支上付代价。</p>
     *
     * <p><b>6 参重载是老口径的加强，不是新口径</b>：{@code selfId} 由装配方（{@code CtxBuild}，它手上有
     * {@code Conf}）带进来；5 参重载（探针/老调用方）不知道 selfId ⇒ 只保证"非自我轴"的那些 QQ，
     * 生产路径（{@code CtxBuild.facts}）<b>一律</b>走 6 参那一支。</p>
     */
    static boolean mayOwnForeignRows(long qq, long selfId) {
        return selfId > 0L && qq == selfId;
    }

    /** 这一行是不是外部来源行（{@code extra.foreign == true}；键名冻结，写方只有 {@code QqGateway.foreignEcho}）。 */
    static boolean foreign(JsonObject row) {
        try {
            if (row == null) return false;
            JsonElement e = J.get(row, "extra");
            if (e == null || e.isJsonNull()) return false;
            JsonObject o = e.isJsonObject() ? e.getAsJsonObject()
                    : (e.isJsonPrimitive() ? J.obj(e.getAsString()) : null);
            return o != null && J.b(o, "foreign", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 老签名的口径说明见 {@link #mayOwnForeignRows}：不知道 selfId ⇒ 不做外部行过滤。 */
    public static JsonObject facts(Store s, long qq, String name, double favor, boolean master) {
        return facts(s, qq, name, favor, master, 0L);
    }

    /**
     * 这个人的客观事实；拿不到库或 QQ 非法返回 {@code null}（基板不编兜底文案，直接不产这一行）。
     *
     * @param master 主人也照常带 {@code favor}（主人裁 2026-09-17：好感度不判权限、是"关系值"，
     *               每个人都能问她"我的好感度是多少" —— 她得先看得到；说不说由她定）
     * @param selfId 本账号 QQ（{@code <=0} = 不知道）；只有 {@code qq == selfId} 时才会在 Java 侧
     *               剔掉外部来源行（见 {@link #mayOwnForeignRows}）
     */
    public static JsonObject facts(Store s, long qq, String name, double favor, boolean master, long selfId) {
        if (s == null || qq <= 0L) return null;
        Db db = s.db();
        if (db == null) return null;

        JsonObject o = new JsonObject();
        o.addProperty("qq", qq);
        String nm = Str.oneLine(name);
        if (Str.has(nm)) o.addProperty("name", nm);
        o.addProperty("favor", (long) favor);

        // 我记过他吗（记忆 + 印象：印象蒸馏的产出也是 memory 行，scope=user/scope_id=QQ）
        o.addProperty("seen_before", num(db, SQL_MEM, "user", String.valueOf(qq)) > 0L);

        // 群聊侧的两个面：① 在哪些群见过 ② 说过多少条 / 首末时间。
        // ★ 键序与批 21 之前逐字一致（known_groups → groups_count → msgs → dm → first_seen → last_seen）：
        //   两条支路都先把 gs/msgs/f/l 算出来，再按同一个次序落键。
        List<JsonObject> gs;
        long msgs;
        long f;
        long l;
        if (mayOwnForeignRows(qq, selfId)) {
            // 这一支才可能在结果里撞上外部来源行 ⇒ 取行 + Java 侧过滤（见 SELF_SCAN_ROWS 的有界口径）
            List<JsonObject> rows = new ArrayList<JsonObject>();
            for (JsonObject r : db.query(SQL_SELF_ROWS, qq)) {
                if (!foreign(r)) rows.add(r);
            }
            gs = groupsOf(rows);
            msgs = rows.size();
            f = 0L;
            l = 0L;
            for (JsonObject r : rows) {
                long ts = J.l(r, "ts", 0L);
                if (ts <= 0L) continue;
                if (f <= 0L || ts < f) f = ts;
                if (ts > l) l = ts;
            }
        } else {
            // 常规支路：两条查询**逐字节**是批 21 之前那两条（覆盖索引，实测 <50ms）
            gs = groups(db, qq);
            JsonObject gl = db.queryOne(SQL_GLOG, qq);
            msgs = J.l(gl, "n", 0L);
            f = J.l(gl, "f", 0L);
            l = J.l(gl, "l", 0L);
        }
        JsonArray arr = new JsonArray();
        for (int i = 0; i < gs.size() && i < MAX_GROUPS; i++) {
            arr.add(J.l(gs.get(i), "group_id", 0L));
        }
        o.add("known_groups", arr);
        o.addProperty("groups_count", gs.size());
        o.addProperty("msgs", msgs);

        // 私聊侧：他跟我在私聊里说过话吗（同一口径并进首末时间）
        JsonObject dm = db.queryOne(SQL_DM, "qq:" + qq, "user");
        long dms = J.l(dm, "n", 0L);
        o.addProperty("dm", dms > 0L);
        long df = J.l(dm, "f", 0L);
        long dl = J.l(dm, "l", 0L);
        if (df > 0L && (f <= 0L || df < f)) f = df;
        if (dl > 0L && dl > l) l = dl;

        // 没有历史就不出现时间位（不知道 = 不写，别拿 0 冒充时间）
        if (f > 0L) o.addProperty("first_seen", f);
        if (l > 0L) o.addProperty("last_seen", l);
        return o;
    }

    // ---------------------------------------------------------------- 按群聚合

    /** 他出现过的群（最近在前）。有覆盖索引就是全量精确聚合，没有就是有界取样。 */
    private static List<JsonObject> groups(Db db, long qq) {
        List<JsonObject> rows;
        if (hasCoverIndex(db)) {
            rows = db.query(SQL_GROUPS, qq);
        } else {
            rows = aggregate(db.query("SELECT group_id, ts FROM grouplog WHERE user_id = ?"
                    + " ORDER BY ts DESC LIMIT " + FALLBACK_ROWS, qq));
        }
        sortGroups(rows);
        return rows;
    }

    /**
     * <b>已经拿到行</b>（Java 侧过滤过外部来源行）时的那一支：同一份聚合 + 同一个排序。
     * 形状与 {@link #groups(Db, long)} 的产出逐字相同（{@code group_id/n/f/l} + 同一比较器）。
     */
    private static List<JsonObject> groupsOf(List<JsonObject> rows) {
        List<JsonObject> g = aggregate(rows);
        sortGroups(g);
        return g;
    }

    /** 按群聚合结果的排序（最近在前；同一时刻按群号降序）—— 两支共用，保证形状不漂。 */
    private static void sortGroups(List<JsonObject> rows) {
        Collections.sort(rows, new Comparator<JsonObject>() {
            @Override
            public int compare(JsonObject a, JsonObject b) {
                long la = J.l(a, "l", 0L);
                long lb = J.l(b, "l", 0L);
                if (la != lb) return la > lb ? -1 : 1;
                long ga = J.l(a, "group_id", 0L);
                long gb = J.l(b, "group_id", 0L);
                return ga == gb ? 0 : (ga > gb ? -1 : 1);
            }
        });
    }

    /** 有界取样的结果在 Java 侧聚合成与 SQL 同一形状（{@code group_id/n/f/l}）。 */
    private static List<JsonObject> aggregate(List<JsonObject> recent) {
        Map<Long, long[]> m = new LinkedHashMap<Long, long[]>();
        if (recent != null) {
            for (JsonObject r : recent) {
                long g = J.l(r, "group_id", 0L);
                if (g <= 0L) continue;
                long ts = J.l(r, "ts", 0L);
                long[] v = m.get(Long.valueOf(g));
                if (v == null) {
                    m.put(Long.valueOf(g), new long[] {1L, ts, ts});
                    continue;
                }
                v[0]++;
                if (ts > 0L) {
                    if (v[1] <= 0L || ts < v[1]) v[1] = ts;
                    if (ts > v[2]) v[2] = ts;
                }
            }
        }
        List<JsonObject> out = new ArrayList<JsonObject>();
        for (Map.Entry<Long, long[]> e : m.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("group_id", e.getKey().longValue());
            o.addProperty("n", e.getValue()[0]);
            o.addProperty("f", e.getValue()[1]);
            o.addProperty("l", e.getValue()[2]);
            out.add(o);
        }
        return out;
    }

    /** 覆盖索引在不在（一行小查询，不缓存：探针会在运行期删/建它来验退化路径）。 */
    private static boolean hasCoverIndex(Db db) {
        JsonObject o = db.queryOne("SELECT 1 AS x FROM sqlite_master WHERE type = 'index' AND name = ?", COVER_INDEX);
        return o != null;
    }

    private static long num(Db db, String sql, Object... args) {
        JsonObject o = db.queryOne(sql, args);
        return o == null ? 0L : J.l(o, "n", 0L);
    }
}
