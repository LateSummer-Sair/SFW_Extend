package sair.v4.ext;

import sair.v4.auth.Caller;
import sair.v4.ctx.Turn;
import sair.v4.skill.Host;

/**
 * 一次扩展点调用的上下文（谁、在哪、本轮正文、只读查询口）。
 *
 * <p>设计稿（{@code notes/plugin-api.md}）只给了读口，这里在两处<b>刻意的收窄</b>，
 * 都是为了"基板永不失联"：</p>
 *
 * <ol>
 *   <li><b>不暴露 {@link Turn}，也不暴露 {@code messages()}</b>：拿到消息数组就能往这一轮里
 *       塞任意 system/user 段，等于绕过装配骨架的顺序与预算。真要改"发出去的东西"，
 *       那条路是 {@link OutboundStage}；要改"进去的东西"，走 {@link ContextProvider} 的返回值。</li>
 *   <li><b>本轮事实用 {@link #state(String, String)} 按键读</b>：事实块里的
 *       {@code media} / {@code chat_window} / {@code subagent} 都是纯数据（见 {@code ctx.CtxBuild} 的
 *       {@code ST_*} 常量），插件需要它们时按 key 取一个字符串即可 —— 既给到了数据，
 *       又不用把可变的 {@code Turn} 交出去。</li>
 * </ol>
 *
 * <p>{@link #host()} 是<b>只读能力面</b>（六库读、配置、文案、控制台、派子 Agent、调用受权限管的工具）。
 * 装配期由基板按"插件名 → 该插件的 {@code Sk}"绑定；绑定不上（例如探针直接注册的合成实现、
 * 或者插件已被卸载）时为 null，<b>插件必须容忍 null</b>（拿不到就少做点事，绝不抛异常）。</p>
 *
 * <p>出站路径（{@code term.Sinks}）没有 {@code Turn}：那时 {@link #state(String, String)} 一律返回默认值，
 * 落点信息（群/私聊、目标号、是否回显）在 {@link Outbound} 上。</p>
 */
public final class BuildContext {

    private final Caller caller;
    private final String session;
    private final String text;
    private final Turn turn;
    private final Host host;

    /**
     * @param caller 调用者（出站路径的定时任务没有调用者，可为 null）
     * @param text   本轮正文：装配期 = 这一轮的用户输入；出站期 = 将要发出去的正文
     * @param turn   当前回合（可为 null）；<b>只用于读事实</b>，见类注释
     * @param host   只读能力面（可为 null）
     */
    public BuildContext(Caller caller, String text, Turn turn, Host host) {
        this.caller = caller;
        this.session = caller != null ? caller.session() : (turn != null ? turn.session() : "");
        this.text = text == null ? "" : text;
        this.turn = turn;
        this.host = host;
    }

    public Caller caller() { return caller; }

    /** 会话键（{@code console} / {@code qq:123} / {@code group:456}）；没有调用者时可能为空串。 */
    public String session() { return session; }

    /** 本轮正文（装配期 = 用户输入；出站期 = 将要发出去的正文）。 */
    public String text() { return text; }

    /** 只读能力面；绑定不上时为 null。 */
    public Host host() { return host; }

    // ---------------------------------------------------------------- 调用者派生

    public boolean group() { return caller != null && caller.isGroup(); }

    public long groupId() { return caller == null ? 0L : caller.groupId(); }

    public long qq() { return caller == null ? 0L : caller.qq(); }

    public boolean master() { return caller != null && caller.master(); }

    /** 本地控制台（≡ 主人交互）。 */
    public boolean console() { return caller != null && caller.isConsole(); }

    // ---------------------------------------------------------------- 本轮事实（只读）

    /**
     * 读本轮的一条事实（{@link Turn#get(String, String)}）。
     * <p>既有的键：{@code media}（媒体段 JSON 行）、{@code chat_window}（本会话最近 N 条聊天记录，
     * 元数据行 + 标签行 + 一行一条记录；键名见 {@code ctx.CtxBuild#ST_CHAT_WINDOW}，
     * 默认 30 条的那个窗口 —— 它替掉了原来的 {@code snapshot}）、
     * {@code subagent}（子 Agent 标签）。没有 {@code Turn}（出站路径）时返回 {@code def}。</p>
     */
    public String state(String key, String def) {
        if (turn == null || key == null) return def;
        try {
            return turn.get(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 这一轮是不是<b>子 Agent</b>在跑。
     * <p>插件据此把"有副作用的写"改成"提议"（写者唯一 = 该会话的主回合）——
     * 与技能面 {@code Host.subagent()} 同一口径。</p>
     */
    public boolean subagent() { return sair.v4.kit.Str.has(state("subagent", "")); }

    /** 子 Agent 的标签（{@code sub-<n>}）；主回合为空串。 */
    public String agentLabel() { return state("subagent", ""); }
}
