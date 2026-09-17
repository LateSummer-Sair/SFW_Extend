package sair.v4.ext;

import java.util.Collections;
import java.util.List;

/**
 * 出站管线：发送前的东西都在这改（洗 AI 味 / @ 提问者 / 追加表情包 / 私聊 typing / 分段前处理）。
 *
 * <p>位置：<b>分段之后、发送之前</b>（{@code term.Sinks.QqSink} / {@code TaskSink}）。
 * 所以 {@link #veto} / {@link #transform} 是<b>逐条</b>看的 —— "这一条发不发、长什么样"，
 * 而不是"整条回复"。今天基板零 stage，管线不存在，行为与加这一层之前逐字节一致。</p>
 *
 * <h3>三个口的分工</h3>
 * <ul>
 *   <li>{@link #veto}：一票否决，{@code true} = 这一条不发。用于"这条其实不该发"的策略插件
 *       （内容合规、重复刷屏、纯客套话）。<b>否决是整条丢</b>，不做"改一点点再发"。</li>
 *   <li>{@link #transform}：改正文。<b>返回 null = 不改</b>（这是"没意见"的默认）；返回空串/空白的
 *       字符串 = 这一条会被基板丢掉（与"清洗之后什么都不剩"同一个意思）。</li>
 *   <li>{@link #extra}：追加独立消息（如"再追一张表情包"）。<b>只在还有话要发的时候收一次</b>，
 *       且<b>再追加的消息不再过 stage</b> —— 否则插件之间会互相追加、无限繁殖。
 *       追加的消息与正文共用同一把分段尺子。</li>
 * </ul>
 *
 * <h3>护栏</h3>
 * <p>每个 stage 的每次调用都有独立超时（默认 {@code extTimeoutMs}=200ms）；抛异常只丢它自己的
 * 那一次调用（正文保持它进来时的样子），一行 {@code [ext] error …}，不影响别的 stage 与这一条发送。</p>
 */
public interface OutboundStage {

    /** 顺序（小的在前）；同序按插件名。 */
    int order();

    /** 变换正文（返回 null = 不改）。 */
    default String transform(Outbound m) { return null; }

    /** 追加独立消息（如"再追一张表情包"），返回空表 = 不追加。 */
    default List<String> extra(Outbound m) { return Collections.emptyList(); }

    /** 一票否决（true = 这条不发），用于"这条其实不该发"的策略插件。 */
    default boolean veto(Outbound m) { return false; }

    /**
     * 整批收尾：<b>所有逐条处理之后、真正发出去之前</b>，拿到最终的整条消息表
     * （已含 {@link #extra} 追加的消息），可以整表改（返回新表）或不动（返回 {@code null}）。
     *
     * <p>为什么需要它：有些事天然是"整条回复"级别的，逐条口做不对 —— 典型是
     * <b>@ 提问者加在首条</b>："首条"必须是<b>真正会发出去</b>的那一条，而在逐条口里
     * 看不到后面哪条会被别的 stage 丢掉或洗空（"@ 挂到一条根本没发的消息上"）。
     * 另外"末条补个标点""整批去重"也只有在这里才对。</p>
     *
     * <p>调用时机在 {@code extra} 之后，所以追加的表情包也算在 {@code parts} 里。</p>
     *
     * <p><b>注意</b>：返回空表 = 这一批一条都不发（这是能力，但请谨慎用，等于绕过 {@code veto} 的语义）；
     * 表里的元素按原样发，<b>不再过 stage</b>（与 {@code extra} 同口径：防止插件互相处理、无限繁殖）。</p>
     *
     * @param parts 最终消息表（可改；元素已被逐条 stage 处理、已 trim）
     * @param m     出站上下文（此时 {@link Outbound#total()} = 最终条数）
     * @return null = 不改
     */
    default List<String> finish(List<String> parts, Outbound m) { return null; }
}
