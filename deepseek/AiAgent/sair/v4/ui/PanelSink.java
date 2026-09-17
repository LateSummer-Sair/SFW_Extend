package sair.v4.ui;

import sair.v4.ctx.Sink;
import sair.v4.kit.Out;

/**
 * 会话落点 → 本地交流面板（基板⑨的 {@link Sink} 适配器）。
 *
 * <h3>映射（就是"本地对话只进面板"的那道闸）</h3>
 * <ul>
 *   <li>{@link #say} → 面板的 {@code assistant} 整行；若这一轮已经流过式，则只收尾（补换行），
 *       <b>不重复打一遍全文</b> —— 与 {@code Sinks.ConsoleSink.say} 的"流式已打完内容就只补换行"
 *       语义完全一致，只是落点换成了面板；</li>
 *   <li>{@link #stream} → {@link TalkPanel#streamDelta}（打字机增量，不换行）；</li>
 *   <li>{@link #notice} → 小字过程行：以 {@code [} 开头的（工具类事实行）用 {@code tool}，
 *       其余用 {@code turn}。分类判据是纯 ASCII 的前缀形状，<b>不识别任何中文关键词</b>。</li>
 * </ul>
 *
 * <h3>它不做什么</h3>
 * <p>本类<b>不往主控制台写任何东西</b>：结构性日志（装配进度、工具台账、错误面）仍然走
 * {@code Out}/{@code SfwOut}，两边互不污染。只有在面板整个不可用（构造失败 / 调用方传 null）
 * 且给了 {@code fallback} 时，才会回落到 {@code Out} —— 那是"没有面板也不能丢对话"的兜底。</p>
 */
public final class PanelSink implements Sink {

    private final TalkPanel panel;
    private final Out fallback;

    /** 本轮是否已经输出过流式增量（收尾判据）。 */
    private volatile boolean streamed = false;

    public PanelSink(TalkPanel panel) {
        this(panel, null);
    }

    /**
     * @param panel    交流面板（null = 只用 fallback）
     * @param fallback 面板不可用时的兜底落点（null = 丢弃；正常装配不该走到这里）
     */
    public PanelSink(TalkPanel panel, Out fallback) {
        this.panel = panel;
        this.fallback = fallback;
    }

    @Override
    public void say(String text) {
        String t = text == null ? "" : text;
        TalkPanel p = visible();
        if (p != null) {
            if (streamed) {
                // 流式已经把这轮的正文打在面板上了：只收尾，不重打（否则正文会出现两遍）
                streamed = false;
                p.endStream();
                return;
            }
            p.line(t, TalkPanel.Style.ASSISTANT);
            return;
        }
        // 面板这一轮中途被主人关掉（getParent()==null）：有兜底落点就整段打过去，绝不丢这一轮的话
        if (fallback != null) {
            streamed = false;
            fallback.print(t + "\n", Out.Tone.NORMAL);
            return;
        }
        // 没有兜底（探针/独立用法：面板本来就不在窗口里）：照旧写进面板对象
        TalkPanel raw = panel;
        if (raw == null) return;
        if (streamed) {
            streamed = false;
            raw.endStream();
            return;
        }
        raw.line(t, TalkPanel.Style.ASSISTANT);
    }

    @Override
    public void stream(String delta) {
        if (delta == null || delta.length() == 0) return;
        TalkPanel p = visible();
        if (p == null) {
            // 有兜底落点（生产）：这里**不回落**，否则每个 token 都会刷一行控制台；
            // 这一轮的正文由收尾的 say() 整段打到兜底落点（不丢话、也不刷屏）。
            if (fallback != null) return;
            // 没有兜底（探针/独立用法：面板本来就不在窗口里）：照旧写进面板对象
            p = panel;
            if (p == null) return;
        }
        streamed = true;
        p.streamDelta(delta);
    }

    @Override
    public void notice(String text) {
        if (text == null || text.length() == 0) return;
        TalkPanel p = visible();
        if (p != null) {
            p.line(text, noticeStyle(text));
            return;
        }
        if (fallback != null) {
            fallback.dim(text);
            return;
        }
        if (panel != null) panel.line(text, noticeStyle(text));
    }

    /** 当前真正可见的落点：面板存在且还挂在容器里（被关掉 = 不在容器里）。 */
    private TalkPanel visible() {
        TalkPanel p = panel;
        if (p == null) return null;
        try {
            return p.getParent() != null ? p : null;
        } catch (Throwable t) {
            return p;
        }
    }

    /** 过程行样式：以 {@code [} 开头的算工具事实行，其余算回合/流程小字。 */
    public static TalkPanel.Style noticeStyle(String text) {
        if (text == null) return TalkPanel.Style.TURN;
        int i = 0;
        int n = text.length();
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        return (i < n && text.charAt(i) == '[') ? TalkPanel.Style.TOOL : TalkPanel.Style.TURN;
    }

    /** 面板（可为 null）。 */
    public TalkPanel panel() { return panel; }

    /** 本轮是否已流过式（诊断用）。 */
    public boolean streamed() { return streamed; }
}
