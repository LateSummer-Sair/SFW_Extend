package sair.v4.store;

import com.google.gson.JsonArray;
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
 * <p><b>量级</b>：{@code grouplog} 可能几十万行。三条查询都走索引、都不做全表扫：<br>
 * ① {@code memory} 走 {@code idx_memory_scope(scope, scope_id)}；<br>
 * ② 总量/首末时间走 {@code idx_grouplog_user(user_id, ts)}（覆盖索引，只扫这个人的索引项）；<br>
 * ③ 按群聚合走覆盖索引 {@link #COVER_INDEX}（{@code user_id, group_id, ts}，只走索引不读表）。
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
    private static final String SQL_GLOG = "SELECT COUNT(*) AS n, MIN(ts) AS f, MAX(ts) AS l FROM grouplog WHERE user_id = ?";
    private static final String SQL_GROUPS = "SELECT group_id, COUNT(*) AS n, MIN(ts) AS f, MAX(ts) AS l"
            + " FROM grouplog WHERE user_id = ? GROUP BY group_id";
    private static final String SQL_DM = "SELECT COUNT(*) AS n, MIN(ts) AS f, MAX(ts) AS l"
            + " FROM dialog WHERE session = ? AND role = ?";

    private Person() {}

    /**
     * 这个人的客观事实；拿不到库或 QQ 非法返回 {@code null}（基板不编兜底文案，直接不产这一行）。
     *
     * @param master 主人也照常带 {@code favor}（主人裁 2026-09-17：好感度不判权限、是"关系值"，
     *               每个人都能问她"我的好感度是多少" —— 她得先看得到；说不说由她定）
     */
    public static JsonObject facts(Store s, long qq, String name, double favor, boolean master) {
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

        // 在哪些群见过（最近在前）+ 见过几个群
        List<JsonObject> gs = groups(db, qq);
        JsonArray arr = new JsonArray();
        for (int i = 0; i < gs.size() && i < MAX_GROUPS; i++) {
            arr.add(J.l(gs.get(i), "group_id", 0L));
        }
        o.add("known_groups", arr);
        o.addProperty("groups_count", gs.size());

        // 群聊侧：说过多少条、第一次/最近一次是什么时候
        JsonObject gl = db.queryOne(SQL_GLOG, qq);
        long msgs = J.l(gl, "n", 0L);
        long f = J.l(gl, "f", 0L);
        long l = J.l(gl, "l", 0L);
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
        return rows;
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
