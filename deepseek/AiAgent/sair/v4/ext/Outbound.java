package sair.v4.ext;

import sair.v4.auth.Caller;

/**
 * 一次出站的上下文：<b>这一条要发出去的正文 + 落点信息</b>。
 *
 * <p>管线里每个 {@link OutboundStage} 都会拿到<b>同一个实例</b>（逐条复用，见
 * {@link ExtRegistry#runStages}）：前一个 stage 的 {@code transform} 结果就是后一个 stage 看到的
 * {@link #text()}。所以插件<b>不要把这个对象存起来</b>（它只在这一次 {@code say} 期间有效），
 * 要留状态请留在自己的库里 / 自己的 kv 里。</p>
 *
 * <h3>与设计稿的两处差别（都是为了让"改正文"只有一条路）</h3>
 * <ol>
 *   <li>设计稿的 {@code Outbound.text} 是公有可变字段。这里改成
 *       {@link #text()} / {@link #text(String)} 一对方法：{@link OutboundStage#transform} 的
 *       <b>返回值</b>才是权威语义（{@code null} = 不改），再留一个"直接改字段"的入口就会出现
 *       "返回 null 但字段已经变了"的第二种含义 —— 一个东西一种改法，排障时不用猜是谁改的。</li>
 *   <li>追加消息（{@link OutboundStage#extra}）<b>不放在这里</b>：它是"这一轮除正文之外还要补几条"，
 *       属于一次 {@code say} 的整体结果，不属于"当前这一条"。放这里会随分段条数被重复收集
 *       （三段回复追加三张表情包）。</li>
 * </ol>
 *
 * <p><b>落点信息是只读的</b>：改"发给谁"不是扩展点的事（那是调度与会话的路）。
 * {@link #caller()} 在定时任务落点（{@code term.Sinks.TaskSink}）上是 null —— 那种出站没有调用者，
 * 插件必须容忍。</p>
 */
public final class Outbound {

    private String text;
    private final String session;
    private final boolean group;
    private final long target;
    private final Caller caller;
    private final boolean echo;

    /**
     * @param caller 调用者（可为 null：定时任务落点没有"谁在说话"）
     * @param text   这一条当前的正文（已分段、还没剥标记）
     * @param group  true = 群，false = 私聊
     * @param target 群号 / QQ 号（{@code <=0} = 没有 QQ 落点，只进控制台）
     * @param echo   这一条会不会把正文也打进控制台（{@code logVerbose} 口径）
     */
    public Outbound(Caller caller, String text, boolean group, long target, boolean echo) {
        this.caller = caller;
        this.text = text == null ? "" : text;
        this.group = group;
        this.target = target;
        this.echo = echo;
        // 没有调用者时也要给一个稳定的会话键（诊断行与插件自己的按会话状态都按它分组）
        this.session = caller != null ? caller.session()
                : (target > 0 ? ((group ? "group:" : "qq:") + target) : "");
    }

    /** 这一条当前的正文（{@code transform} 改的就是它）。 */
    public String text() { return text; }

    /** 覆盖正文（一般用 {@link OutboundStage#transform} 的返回值；这里给"确实要就地改"的场合）。 */
    public void text(String t) { this.text = t == null ? "" : t; }

    /** 会话键（{@code console} / {@code qq:123} / {@code group:456}）。 */
    public String session() { return session; }

    public boolean group() { return group; }

    public long target() { return target; }

    /** 调用者（定时任务落点上是 null）。 */
    public Caller caller() { return caller; }

    /** 这一条会不会把正文也打进控制台（插件据此决定要不要打自己的过程行）。 */
    public boolean echo() { return echo; }

    // ==================== 位置（本次发送的第几条） ====================

    private int index;
    private int total;

    /**
     * 本次发送里这是第几条（0 起）。
     * <p>{@code transform}/{@code veto} 阶段 = 分段结果里的下标；{@code extra}/{@code finish} 阶段
     * 指向本批最后一条（它们是"整批"级别的口，不是某一条的）。</p>
     */
    public int index() { return index; }

    /** 本次发送一共几条（{@code finish} 阶段 = 含追加消息的最终条数）。 */
    public int total() { return total; }

    /** 是不是本批第一条（{@code @ 提问者} 这类"只加首条"的插件看它）。 */
    public boolean first() { return index == 0; }

    /** 是不是本批最后一条（"末条补标点"这类看它）。 */
    public boolean last() { return total > 0 && index >= total - 1; }

    /** 基板内部用：标记当前处理到第几条。**故意不加 public** —— 位置由基板说了算，插件不许伪造。 */
    void pos(int i, int t) {
        this.index = i;
        this.total = t;
    }

    // ==================== 宿主（读自己插件的文案/配置用） ====================

    private sair.v4.skill.Host host;

    /**
     * 当前 stage 所属插件的<b>只读能力面</b>（可为 {@code null}）。
     *
     * <p>为什么出站路径也要给宿主：插件要把文案/阈值外挂在<b>自己目录的 prompt.md</b> 里，
     * 而 {@link OutboundStage} 的签名里没有 {@link BuildContext}（出站期没有回合），
     * 拿不到宿主就只能把文案写死在 java 里 —— 那是这个工程明确不要的做法。</p>
     *
     * <p><b>可能为 null</b>（宿主绑定不上、或探针直接注册的合成 stage）：插件必须容忍，
     * 拿不到就用自己的兜底默认值，绝不抛异常。注意它是一次对话的视图：<b>别把它存起来</b>。</p>
     */
    public sair.v4.skill.Host host() { return host; }

    /** 基板内部用：把宿主绑到当前 stage 上（每个 stage 各自绑自己的插件）。 */
    void host(sair.v4.skill.Host h) { this.host = h; }
}
