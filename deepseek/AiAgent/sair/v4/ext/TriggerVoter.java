package sair.v4.ext;

import sair.v4.auth.Caller;
import sair.v4.qq.Ev;

/**
 * 触发投票：这条消息要不要自动回。
 *
 * <p>它<b>不是新机制</b>，而是把今天只有技能能投的那一票（网关 {@code payload._reply}）
 * 升级成"插件公共"：三态、有顺序、有缘由。基板原有语义一个字不改 ——
 * 今天没有任何 voter 时，网关走的还是"技能那一票 → 地址规则"那条老路。</p>
 *
 * <h3>三态</h3>
 * <ul>
 *   <li>{@link Vote#ANSWER}：回（即使没被 @、也不是主人 —— 例如触发词命中）；</li>
 *   <li>{@link Vote#DECLINE}：不回（直接结束，<b>省掉一整轮模型调用</b>）；</li>
 *   <li>{@link Vote#ABSTAIN}：不管（交给下一个 voter；全都不管 = 按基板的地址规则走）。</li>
 * </ul>
 *
 * <h3>判定口径（基板的确定性规则）</h3>
 * <ul>
 *   <li>按 {@code order, 插件名} 依次问，<b>第一个非 ABSTAIN 的票生效</b>，后面的不再问。
 *       为什么不是"多数决"：今天这一票的来源就只有一个，语义是"有人明确表态就按他说的走"；
 *       改成投票制会让"谁回谁不回"变得依赖插件数量 —— 顺序确定，结果才确定。</li>
 *   <li>{@link #vote} 抛异常 / 超时 = 这一票作废（当 ABSTAIN），一行 {@code [ext] timeout|error …}。</li>
 *   <li><b>不得阻塞</b>：宁可 ABSTAIN，也别在这儿等网络 —— 超时上限是 {@code extTimeoutMs}（默认 200ms），
 *       超了这一条消息就按"没人表态"处理。</li>
 * </ul>
 */
public interface TriggerVoter {

    /** 回 / 不回 / 不管。 */
    enum Vote { ANSWER, DECLINE, ABSTAIN }

    /** 顺序（小的在前）；同序按插件名。 */
    int order();

    /** 投票（返回 null 等同于 {@link Vote#ABSTAIN}）。 */
    Vote vote(Ev ev, Caller c);

    /** 拒绝的缘由（写进 {@code [qq] skip reason=…} 的结构事实）；只在 {@link Vote#DECLINE} 时有意义。 */
    default String reason() { return "plugin"; }
}
