package sair.v4.ext;

/**
 * 生命周期：回合开始 / 回合结束 / 发送前。
 *
 * <p>用途是"记账与清理"，不是"改内容"：写对话历史、记调用量、清临时态、私聊 typing。
 * <b>不得改消息内容</b> —— 那是 {@link OutboundStage} 的事。所以这里拿到的是
 * {@link BuildContext}（只读口），没有可改的正文。</p>
 *
 * <h3>时序（基板保证）</h3>
 * <pre>
 *   onTurnStart → 模型跑（可能多次工具闭环）→ onSend（真要说话了）→ 落点发出 → onTurnEnd
 * </pre>
 * <ul>
 *   <li>{@code onTurnStart}：{@code Agent.ask} / {@code Agent.wake} 在装配完这一轮之后、跑之前触发。
 *       放在装配之后是因为插件要看的"本轮输入、本轮事实"那时才齐；</li>
 *   <li>{@code onSend}：<b>这一轮马上要对外说一句话了</b>（含失败兜底那句「（本轮失败：…）」）。
 *       只挂在成功分支上会让"失败了也要做点什么"的插件永远收不到通知 —— 这一层管的是"要说了"这个时刻；</li>
 *   <li>{@code onTurnEnd}：正文发出（或失败）之后触发。失败时 {@code reply} 为空串、{@code error} 非空，
 *       <b>两种结果都会通知一次</b>。</li>
 * </ul>
 *
 * <p><b>只管主回合</b>：子 Agent 与后台收尾回合不触发（它们是"Agent 之间的交流"，
 * 对外不可见、也不进对话历史）。这正是"回合结束写 dialog"这类插件要的边界。</p>
 *
 * <p>护栏同其余扩展点：独立超时、异常只丢自己、每个回调一行 {@code [ext]} 诊断。</p>
 */
public interface LifecycleHook {

    /** 顺序（小的在前）；同序按插件名。 */
    int order();

    /** 回合开始（装配完、还没跑）。 */
    default void onTurnStart(BuildContext b) { }

    /**
     * 回合结束。
     *
     * @param reply 这一轮产出的正文（失败时为空串；<b>还没剥标记、也可能还没发出去</b>）
     * @param error 失败缘由（成功时为空串）
     */
    default void onTurnEnd(BuildContext b, String reply, String error) { }

    /** 即将把正文交给落点（含失败兜底文案）。 */
    default void onSend(BuildContext b) { }
}
