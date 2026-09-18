package sair.v4.store;

import com.google.gson.JsonObject;

import java.util.List;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 基板<b>环境表</b>：建表语句、列清单、补列。
 *
 * <p><b>这里不再有"哪些库"</b>：表结构由用到它的插件声明、基板只提供注册机制
 * （表名/列名的唯一来源是 {@link LibSpec}，见 {@code notes/lib-declaration.md}）。
 * 本类只留基板自己的运行表 —— 它们不是"库"（没有 {@code id}/{@code ts}、有的还是自定义主键），
 * 进不了 {@link Libs} 注册表，所以 DDL 与列清单留在基板：</p>
 * <ul>
 *   <li>{@code favor(qq PK)} —— 好感度，权限门禁的数据源；</li>
 *   <li>{@code alarm} —— 定时表；{@code task} —— 子 Agent 台账；</li>
 *   <li>{@code kv(k PK)} —— 通用键值；{@code skill_index(name PK)} —— 插件索引。</li>
 * </ul>
 *
 * <p>可查询的库（{@code dialog}/{@code grouplog} 与插件自己的表）走
 * {@link Libs} + {@link LibSpec}，不在这里。统一约定：{@code id} 为
 * {@code INTEGER PRIMARY KEY AUTOINCREMENT}；{@code ts} 为毫秒 {@code INTEGER}；
 * {@code extra}/{@code tags} 为 TEXT 存 JSON 文本。</p>
 */
public final class Lib {

    private Lib() {}

    /** 运行表（基板自用，非"库"）：表名 + 首列为表名，其余为列名。 */
    private static final String[][] RUN_COLS = {
            {"favor", "qq", "value", "level", "updated", "note"},
            // 好感度的流水账（主人裁 2026-09-17：她自己加减、主人可直接定值 ⇒ 每一次改动都要留痕，
            // 数值必须能说出"为什么他是朋友、谁改的、改了多少"）。列在 RUN_COLS 里 = 技能不能经
            // store_write 直写它（与 favor 同一条保护）。
            {"favor_event", "id", "qq", "ts", "delta", "value_after", "op", "by", "why"},
            {"alarm", "id", "ts", "fire_at", "repeat", "scope", "target", "task", "prompt", "enabled", "state", "owner"},
            {"task", "id", "ts", "status", "owner", "agent", "tools", "task", "result", "ms"},
            {"kv", "k", "v", "updated"},
            {"skill_index", "name", "hash", "tool", "permission", "hooks", "loaded_at"},
            {"sent", "id", "ts", "session", "action", "message_id", "preview", "state"}
    };

    /** 运行表建表语句。 */
    public static String[] runDdl() {
        return new String[] {
                "CREATE TABLE IF NOT EXISTS favor ("
                        + "qq INTEGER PRIMARY KEY,"
                        + "value REAL DEFAULT 0,"
                        + "level TEXT DEFAULT '',"
                        + "updated INTEGER DEFAULT 0,"
                        + "note TEXT DEFAULT '')",
                "CREATE INDEX IF NOT EXISTS idx_favor_value ON favor(value)",

                // 好感度流水（谁、何时、加减了多少、改完是多少、什么 op、谁改的、为什么）。
                // 表建在这里 = 走 runDdl()，老库升级时幂等补表，不需要改 favor 自己的列结构。
                "CREATE TABLE IF NOT EXISTS favor_event ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "qq INTEGER DEFAULT 0,"
                        + "ts INTEGER DEFAULT 0,"
                        + "delta REAL DEFAULT 0,"
                        + "value_after REAL DEFAULT 0,"
                        + "op TEXT DEFAULT '',"
                        + "by TEXT DEFAULT '',"
                        + "why TEXT DEFAULT '')",
                "CREATE INDEX IF NOT EXISTS idx_favor_event_qq ON favor_event(qq, ts)",

                "CREATE TABLE IF NOT EXISTS alarm ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "ts INTEGER NOT NULL DEFAULT 0,"
                        + "fire_at INTEGER DEFAULT 0,"
                        + "repeat TEXT DEFAULT '',"
                        + "scope TEXT DEFAULT '',"
                        + "target TEXT DEFAULT '',"
                        + "task TEXT DEFAULT '',"
                        + "prompt TEXT DEFAULT '',"
                        + "enabled INTEGER DEFAULT 1,"
                        + "state TEXT DEFAULT '',"
                        + "owner TEXT DEFAULT '')",
                "CREATE INDEX IF NOT EXISTS idx_alarm_fire ON alarm(fire_at)",
                "CREATE INDEX IF NOT EXISTS idx_alarm_enabled ON alarm(enabled, fire_at)",
                // 老库补列走 Lib.ensureColumn（幂等：先查有没有，再决定 ALTER）——
                // 以前这里直接写 "ALTER TABLE alarm ADD COLUMN owner …"，在已经补过的库上每次都抛
                // "duplicate column name: owner" 并被 Db 当成错误行打红字（实测：每次启动刷一条假报错）。

                "CREATE TABLE IF NOT EXISTS task ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "ts INTEGER NOT NULL DEFAULT 0,"
                        + "status TEXT DEFAULT '',"
                        + "owner TEXT DEFAULT '',"
                        + "agent TEXT DEFAULT '',"
                        + "tools TEXT DEFAULT '',"
                        + "task TEXT DEFAULT '',"
                        + "result TEXT DEFAULT '',"
                        + "ms INTEGER DEFAULT 0)",
                "CREATE INDEX IF NOT EXISTS idx_task_status ON task(status, ts)",

                "CREATE TABLE IF NOT EXISTS kv ("
                        + "k TEXT PRIMARY KEY,"
                        + "v TEXT DEFAULT '',"
                        + "updated INTEGER DEFAULT 0)",

                "CREATE TABLE IF NOT EXISTS skill_index ("
                        + "name TEXT PRIMARY KEY,"
                        + "hash TEXT DEFAULT '',"
                        + "tool TEXT DEFAULT '',"
                        + "permission TEXT DEFAULT '',"
                        + "hooks TEXT DEFAULT '',"
                        + "loaded_at INTEGER DEFAULT 0)",

                // 出站消息台账（F5a）：撤回只能按 message_id 定位，而 send_*_msg 的返回里那个 id
                // 以前是用完就丢的 —— 这张表就是"她发过哪些消息"的底账（供按范围批量撤回）。
                // session 形如 group:123 / user:456（推不出为空串）；preview 是出站正文前 ~60 字，供辨认。
                "CREATE TABLE IF NOT EXISTS sent ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "ts INTEGER NOT NULL DEFAULT 0,"
                        + "session TEXT DEFAULT '',"
                        + "action TEXT DEFAULT '',"
                        + "message_id INTEGER DEFAULT 0,"
                        + "preview TEXT DEFAULT '',"
                        + "state TEXT DEFAULT 'sent')",
                "CREATE INDEX IF NOT EXISTS idx_sent_session_ts ON sent(session, ts)",
                "CREATE INDEX IF NOT EXISTS idx_sent_msgid ON sent(message_id)",
        };
    }

    /**
     * 补列（<b>幂等、安静</b>）：表里没有这一列才 ALTER。
     *
     * <p>为什么不用一句 {@code ALTER TABLE … ADD COLUMN}：SQLite 没有 {@code ADD COLUMN IF NOT EXISTS}，
     * 在已经补过列的库上它会抛 {@code SQLITE_ERROR: duplicate column name} —— 而 {@code Db.exec}
     * 把任何失败都当错误行打红字，于是<b>每次启动都刷一条吓人的假报错</b>（主人截图看到的就是它）。
     * 这里先查 {@code PRAGMA table_info}，缺了才加；查不出来就照旧 ALTER，让真正的错误暴露。</p>
     */
    public static void ensureColumn(Db db, String table, String column, String decl) {
        if (db == null || Str.blank(table) || Str.blank(column)) return;
        try {
            List<JsonObject> cols = db.query("PRAGMA table_info(" + table + ")");
            for (JsonObject c : cols) {
                if (column.equalsIgnoreCase(Str.trim(J.s(c, "name", "")))) return;   // 已经有了
            }
        } catch (Throwable ignored) {
            // 查不出来就按"没有"处理，交给下面那句 ALTER
        }
        db.exec("ALTER TABLE " + table + " ADD COLUMN " + column + " " + decl);
    }

    /** 是不是基板运行表（这些表不进 {@link Libs} 注册表，但同样要过列校验）。 */
    public static boolean isEnvTable(String table) {
        if (table == null) return false;
        for (String[] t : RUN_COLS) if (t[0].equals(table)) return true;
        return false;
    }

    /** 基板运行表表名清单（诊断/日志用）。 */
    public static String[] envTables() {
        String[] out = new String[RUN_COLS.length];
        for (int i = 0; i < RUN_COLS.length; i++) out[i] = RUN_COLS[i][0];
        return out;
    }

    /** 基板运行表的列名清单；不是运行表则返回空数组（可查询的库问 {@link LibSpec#fieldNames()}）。 */
    public static String[] columns(String table) {
        if (table != null) {
            for (String[] t : RUN_COLS) {
                if (t[0].equals(table)) {
                    String[] out = new String[t.length - 1];
                    System.arraycopy(t, 1, out, 0, out.length);
                    return out;
                }
            }
        }
        return new String[0];
    }

    /** 是否为只读检索用的 JSON 文本列（出参自动解析回对象/数组）。 */
    public static boolean isJsonField(String col) {
        return "extra".equals(col) || "tags".equals(col);
    }
}
