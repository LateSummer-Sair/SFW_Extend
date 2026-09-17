package sair.v4.auth;

import com.google.gson.JsonObject;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 调用者身份：<b>谁 + 在哪说话</b>。
 * <p>V4 没有"通道"概念 —— {@link Entry} 只决定两件事：①回复输出到哪里（控制台 / QQ）；
 * ②能不能调用 NapCat（本地交互在 NapCat 未连接时照常可用）。它<b>不</b>参与权限裁剪：
 * 用什么工具，完全由权限档位裁定（见 {@link Auth}）。</p>
 * <p>本地控制台交互 ≡ QQ 中的主人交互：{@link #console()} 造出来的调用者就是主人（MASTER）。</p>
 *
 * <h3>主体三类（ACL 模型，{@link #kind()}）—— <b>MASTER / SYSTEM / ALLUSER</b></h3>
 * <pre>
 *   MASTER   主人 / 本地控制台          A/B/C/E 全 RWX（恒全权，任何条目都不影响他）
 *   SYSTEM   她自己的自主行为           A:R  B:RW  C:RWX  E:X
 *   ALLUSER  除主人与她之外的所有人      A:R  B:RX  C:RWX  E:NONE（按类默认分配）
 * </pre>
 * <p>{@code OTHER} 这个叫法<b>已退休</b>，等价于 {@code ALLUSER}（工厂别名 {@link #other(long)} 委托到
 * {@link #allUser(long)}，只为不炸老调用点）。正式身份只有上面三个。</p>
 * <p><b>SYSTEM</b> 是"她自己的自主行为"这个主体：定时任务、钩子、维护、她自己发出去的话。
 * 它<b>不是主人</b>（{@link #system(long)} 那个老工厂仍然是 master 语义，一字未动，见它自己的注释）。
 * 纯 SYSTEM 主体用 {@link #systemActor(long)} / {@link #systemActor()} 造。</p>
 * <p>判定顺序：<b>显式 SYSTEM 标志 &gt; master &gt; 其它</b>。将来接线时，下面四处"没有触发者"的路径
 * 都要落到 SYSTEM：{@code Ctx.bind(null)}（钩子派发 / 扩展点回调）、{@code Tick} 定时任务、
 * {@code QqGateway} 网关回调、{@code ExtRegistry} 扩展点回调 —— 现在它们传 {@code null}，
 * 而 {@code Boot} 的旧闸门对 {@code null} 是"直接放行"，这在新模型里等于没有主体。</p>
 */
public final class Caller {

    /** 说话的入口（不是通道，不参与权限）。 */
    public enum Entry { CONSOLE, QQ }

    /** 主体种类（权限判定的第一刀）：MASTER 主人 / SYSTEM 她自己 / ALLUSER 全体用户。 */
    public enum Kind { MASTER, SYSTEM, ALLUSER }

    private final Entry entry;
    private final long qq;
    private final long groupId;
    private final String groupRole;
    private final boolean master;
    private final boolean system;
    private final double favor;
    private final String name;

    public Caller(Entry entry, long qq, long groupId, String groupRole,
                  boolean master, double favor, String name) {
        this(entry, qq, groupId, groupRole, master, false, favor, name);
    }

    /**
     * 带主体标志的构造（新增，老构造委托到它）：{@code system=true} = 她自己的自主行为（SYSTEM）。
     * <p>不会改动只传老参数的那些调用点的语义（{@code system} 默认 {@code false}）。</p>
     */
    public Caller(Entry entry, long qq, long groupId, String groupRole,
                  boolean master, boolean system, double favor, String name) {
        this.entry = entry == null ? Entry.CONSOLE : entry;
        this.qq = qq;
        this.groupId = groupId;
        this.groupRole = groupRole == null ? "" : groupRole;
        this.master = master;
        this.system = system;
        this.favor = favor;
        this.name = name == null ? "" : name;
    }

    /** 本地控制台 = 主人。 */
    public static Caller console(long masterQq) {
        return new Caller(Entry.CONSOLE, masterQq, 0L, "", true, Double.MAX_VALUE, "主人");
    }

    /** 系统内部（tick / 定时任务）以主人身份运行。 */
    public static Caller system(long masterQq) {
        return new Caller(Entry.CONSOLE, masterQq, 0L, "", true, Double.MAX_VALUE, "系统");
    }

    /**
     * <b>纯 SYSTEM 主体</b>：她自己的自主行为（A:{@code R}、B:{@code RW}、C:{@code RWX}、E:{@code X}）。
     *
     * <p>与 {@link #system(long)} <b>不是</b>一回事：那个是"系统内部以主人身份运行"（{@code master=true}，
     * 语义原样保留，等接线阶段再换）；这个 {@code master=false} + SYSTEM 标志，是 ACL 模型里的独立主体。</p>
     *
     * <p>将来要接到这四处"没有触发者"的路径：{@code Ctx.bind(null)}（钩子 / 扩展点回调）、
     * {@code Tick} 定时任务、{@code QqGateway} 网关回调、{@code ExtRegistry} 扩展点回调。</p>
     */
    public static Caller systemActor(long masterQq) {
        return new Caller(Entry.CONSOLE, masterQq, 0L, "", false, true, Double.MAX_VALUE, "系统");
    }

    /** 同 {@link #systemActor(long)}，没有具体 QQ（自主行为本来就没有触发者）。 */
    public static Caller systemActor() { return systemActor(0L); }

    /**
     * 普通 QQ 用户（正式身份 {@code ALLUSER}）：不是主人、不是系统。
     * <p>主体条目里 {@code ALLUSER} = 全体用户（{@code User<QQ>} 与 {@code Group<群号>} 的底稿），
     * 不含 MASTER、也不含 SYSTEM。</p>
     */
    public static Caller allUser(long qq) {
        return new Caller(Entry.QQ, qq, 0L, "", false, false, 0, "");
    }

    /**
     * @deprecated {@code OTHER} 这个叫法<b>已退休</b>，等价于 {@code ALLUSER}：请改用
     *     {@link #allUser(long)}。这里原样委托，只为不炸老调用点。
     */
    @Deprecated
    public static Caller other(long qq) { return allUser(qq); }

    public Entry entry() { return entry; }

    public long qq() { return qq; }

    public long groupId() { return groupId; }

    public String groupRole() { return groupRole; }

    public boolean master() { return master; }

    /** 显式 SYSTEM 主体（她自己的自主行为）。默认 {@code false}；老构造点语义不变。 */
    public boolean system() { return system; }

    /**
     * 主体种类：<b>显式 SYSTEM 标志 &gt; {@link #master()} &gt; ALLUSER</b>。
     * <p>权限判定（{@code Acl}）与事实块都看它。</p>
     */
    public Kind kind() {
        if (system) return Kind.SYSTEM;
        return master ? Kind.MASTER : Kind.ALLUSER;
    }

    /** 标记 / 取消"她自己的自主行为"（返回新对象，不改本对象）。 */
    public Caller withSystem(boolean on) {
        return new Caller(entry, qq, groupId, groupRole, master, on, favor, name);
    }

    public double favor() { return favor; }

    public String name() { return name; }

    public boolean isGroup() { return groupId > 0; }

    public boolean isConsole() { return entry == Entry.CONSOLE; }

    /** 是否会话落在群里（决定输出落点）。 */
    public boolean toGroup() { return isGroup(); }

    /** 会话键：控制台 / 私聊 / 群。 */
    public String session() {
        if (entry == Entry.CONSOLE) return "console";
        return isGroup() ? ("group:" + groupId) : ("qq:" + qq);
    }

    /** 记忆作用域键：群内为群，私聊为个人。 */
    public String scopeKey() {
        return isGroup() ? String.valueOf(groupId) : String.valueOf(qq);
    }

    public String label() {
        if (system) return "系统(自主行为)";
        if (entry == Entry.CONSOLE) return "本地控制台(主人)";
        StringBuilder sb = new StringBuilder();
        sb.append(isGroup() ? "群" : "私聊").append(" ");
        if (Str.has(name())) sb.append(name()).append("(").append(qq).append(")");
        else sb.append(qq);
        if (master) sb.append(" [主人]");
        return sb.toString();
    }

    /** 换一个会话（同一人换个群说话）但保留身份。 */
    public Caller inGroup(long gid, String role) {
        return new Caller(entry, qq, gid, role, master, system, favor, name);
    }

    public Caller withFavor(double f) {
        return new Caller(entry, qq, groupId, groupRole, master, system, f, name);
    }
}
