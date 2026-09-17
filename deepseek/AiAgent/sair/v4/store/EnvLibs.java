package sair.v4.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 基板自己声明的那几张<b>可查询的库</b>（其余环境表不走这里，见下）。
 *
 * <h3>为什么只有这两张</h3>
 * <p>今天的 {@code store} 工具面 = 6 张库（{@code memory/note/dialog/grouplog/sticker/pref}）。
 * S2 之后：{@code memory}/{@code note}/{@code sticker}/{@code pref} 是**业务表**，
 * 由各自的插件声明（谁写入谁声明）；只有 {@code dialog}/{@code grouplog} 留基板 ——
 * **消息流就是环境**，基板是它们唯一的写入者（{@code Agent}/{@code QqGateway} 落行、
 * {@code CtxBuild}/{@code Person} 读回），交给插件声明会出现"插件不在 → 基板自己的上下文装配没历史"。</p>
 *
 * <h3>为什么 {@code kv}/{@code favor}/{@code task}/{@code alarm}/{@code skill_index} 不进来</h3>
 * <p>两条理由，任何一条都足够：
 * ① <b>安全</b>：{@code store_write} 的出厂档位是 {@code AFFECTION:300}，把 {@code favor} 变成"库"
 * 就等于让好感度 ≥300 的人绕过 {@code perm} 工具直写自己的好感度 —— 权限门禁当场失效；
 * ② <b>形状</b>：{@code favor(qq PK)}/{@code kv(k PK)}/{@code skill_index(name PK)} 是自定义主键、
 * 没有 {@code id}/{@code ts}，本来就不是 {@link LibSpec} 的形状。
 * 它们继续走 {@link Lib#runDdl()} 的原样 DDL（一个字不动）。</p>
 *
 * <h3>逐字兼容</h3>
 * <p>下面每一列/每个索引名都照抄现网 {@code Lib} 枚举的定义（见 {@code notes/lib-declaration.md} 第六节）：
 * 表结构变了 = 需要迁移 = 本阶段禁止的事。{@code grouplog} 上那条
 * {@code (user_id,group_id,ts)} 覆盖索引是 {@link Person#COVER_INDEX}，**不能少**
 * （36 万行实测：没有它就是 5.8 秒全表扫）。</p>
 */
public final class EnvLibs {

    private EnvLibs() {}

    /** 对话历史（私聊与控制台共用；{@code session} 形如 {@code qq:123} / {@code console}）。 */
    public static LibSpec dialog() {
        return new LibSpec("dialog", "对话历史")
                .ts()
                .col("session", "TEXT", "''")
                .col("role", "TEXT", "''")
                .col("content", "TEXT", "''")
                .col("tokens", "INTEGER", "0")
                .col("extra", "TEXT", "''")
                .fts("content")
                .alias("对话", "对话历史", "会话", "chat", "histories")
                .idx("idx_dialog_session", "session,ts")
                .idx("idx_dialog_ts", "ts")
                .idx("idx_dialog_role", "role");
    }

    /** 群聊历史（群消息留痕；{@code Person} 的"谁在哪些群出现过"也读它）。 */
    public static LibSpec grouplog() {
        return new LibSpec("grouplog", "群聊历史")
                .ts()
                .col("group_id", "INTEGER", "0")
                .col("user_id", "INTEGER", "0")
                .col("nickname", "TEXT", "''")
                .col("content", "TEXT", "''")
                .col("msg_id", "TEXT", "''")
                .col("extra", "TEXT", "''")
                .fts("content", "nickname")
                .alias("群聊", "群聊历史", "群记录", "group", "grouplogs")
                .idx("idx_grouplog_group", "group_id,ts")
                .idx("idx_grouplog_user", "user_id,ts")
                .idx("idx_grouplog_ts", "ts")
                .idx(Person.COVER_INDEX, "user_id,group_id,ts");
    }

    /** 基板要注册进 {@link Libs} 的全部库。 */
    public static List<LibSpec> all() {
        List<LibSpec> out = new ArrayList<LibSpec>();
        out.add(dialog());
        out.add(grouplog());
        return out;
    }

    /** 库名清单（诊断/日志用）。 */
    public static List<String> tables() {
        List<String> out = new ArrayList<String>();
        for (LibSpec s : all()) out.add(s.table);
        return out;
    }
}
