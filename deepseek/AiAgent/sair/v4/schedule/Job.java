package sair.v4.schedule;

/**
 * 一个可被调度的"回合任务"。
 *
 * <p>之所以不直接用 {@link Runnable}：按会话分道的调度器需要在<b>任务还没开始跑</b>的时候，
 * 把后到的同类输入<b>并进这一条</b>（同会话合并成一回合），这件事只有任务自己知道怎么做
 * （什么样的输入能合、合并后正文怎么拼）。</p>
 */
public interface Job extends Runnable {

    /** 会话键（{@code console} / {@code group:<群号>} / {@code qq:<QQ>}，或调度器给的其它键）。 */
    String session();

    /** 是否高优先级（主人 / 私聊；具体口径由提交方决定，调度器只认这个布尔）。 */
    boolean high();

    /**
     * 把后到的同类输入并入自己（只会在本任务<b>还在排队、尚未开始跑</b>时被调用）。
     *
     * @return 真正并入的字符数（&gt;=0）；{@code -1} 表示合并不了（类型不同 / 已经装不下），
     *         此时调用方按溢出策略处置（丢弃并计数，必要时回一句文案）。
     */
    int merge(Job later);

    /**
     * 给这个会话回一句话（溢出/排队提示用）。
     *
     * @return 是否真的发出去了（没有落点的任务返回 false，调度器据此不把它算成"已告知用户"）
     */
    boolean notice(String text);

    /**
     * 这条任务要不要进"调度决策日志"（{@code [lane] session=… decision=… depth=…}）。
     *
     * <p>默认 {@code true}：调度决策是<b>结构性事实</b>（并了／排了／丢了／直接起跑），
     * 属于"为什么走这条路"那一类，默认口径就要看得见；它<b>不含任何消息正文</b>。
     * 不想上日志的任务（例如基板内部的维护任务）覆盖成 false 即可。</p>
     */
    default boolean log() { return true; }
}
