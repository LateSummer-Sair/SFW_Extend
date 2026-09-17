package sair.v4.ext;

/**
 * 上下文提供者：每轮往上下文里加一段。
 *
 * <p><b>内容与文案全在插件里</b>，基板只负责三件事：顺序（{@link #order()} + 插件名）、
 * 段位（{@link #stable()}）、预算（{@link #maxChars()} + 全局 {@code ctxBudgetChars}）。
 * 基板自己<b>不产生任何一条上下文内容</b> —— 这是终态边界（见 {@code notes/substrate-env.md}）。</p>
 *
 * <h3>稳定段 / 易变段（前缀缓存）</h3>
 * <p>装配出来的 messages 是"系统提示词 + 各块 + 历史 + 本轮 user"。模型服务的前缀缓存按
 * <b>前缀逐字节</b>命中，所以：</p>
 * <ul>
 *   <li>{@link #stable()} 返回 true 的块排在同一条系统提示词<b>之后、事实块之前</b>，
 *       它们一起构成可复用的前缀 —— 块内容必须"同输入逐字节不变"（别把时间戳、随机数、当前消息正文放进去）；</li>
 *   <li>返回 false 的块排在事实块之后（每轮都可能变），顺序仍按 {@code order, 插件名} 确定。</li>
 * </ul>
 *
 * <h3>护栏（见 {@code notes/plugin-api.md} 第四节）</h3>
 * <ul>
 *   <li><b>超时</b>：默认 200ms（配置 {@code extTimeoutMs}）；超时只丢这一段，其余照常。</li>
 *   <li><b>预算</b>：先按 {@link #maxChars()} 逐段截断（丢尾巴），再按全局 {@code ctxBudgetChars}
 *       从 order 最大的开始<b>整段</b>丢，并记一行。</li>
 *   <li><b>异常隔离</b>：{@link #build(BuildContext)} 抛异常 = 这一段丢弃，一行 {@code [ext] error …}，
 *       <b>绝不影响这一轮</b>，更不影响别的插件。</li>
 *   <li><b>只读</b>：{@link BuildContext#host()} 是只读查询口；要写就写自己的库 / 自己的 kv。</li>
 * </ul>
 */
public interface ContextProvider {

    /** 稳定段（同输入逐字节不变，前缀缓存友好）还是易变段。 */
    boolean stable();

    /** 拼装顺序（小的在前）；同序按插件名。 */
    int order();

    /**
     * 这一段最多多少字符（基板还要再按全局预算截断一次）。
     * <p>出厂默认 {@code 1200}（与偏好块同一量级）。{@code <=0} = 本段不按自身预算截断，
     * 但仍受全局 {@code ctxBudgetChars} 管。<b>这不是"要多少给多少"的开关</b>：
     * 一个 provider 把整库拉进上下文，挨罚的是同一轮的所有人。</p>
     */
    default int maxChars() { return 1200; }

    /**
     * 只对哪些会话生效。
     * <p>基板<b>不</b>按调用者权限裁定扩展点行为（否则"给某人写画像"会随权限随机失效）；
     * 这里是插件自己的策略口，返回 false = 这一段这一轮不出现（基板不会记成异常）。</p>
     */
    default boolean appliesTo(BuildContext b) { return true; }

    /**
     * 产出这一段（返回空串 / null = 这一段不出现）。
     *
     * <p>返回值会被基板 trim 后按"一段一条 system 消息"装配。<b>不要在这里打日志刷屏</b>：
     * 每轮都会调用，诊断行走基板自己的 {@code [ext]} 行。</p>
     */
    String build(BuildContext b);
}
