package sair.v4.kit;

/**
 * 按类别放行的输出包装：<b>过程行（dim/normal）只看开关，警告与错误永远放行</b>。
 *
 * <h3>它解决什么</h3>
 * <p>插件的技能是"外部代码"，它们习惯用 {@code h.out().dim("[技能名] …")} 打自己的过程细节
 * （取样多少条、冷却还剩多久……）。这些行对主人来说是噪声：主人的口径是
 * <b>控制台只留 Agent 调用事件</b>（哪个 Agent 调了哪个工具、走的哪个模型、缓存命中、用时）。
 * 让每个技能自己去读配置不现实，所以在这里统一拦一道：</p>
 * <ul>
 *   <li>{@link Tone#DIM} / {@link Tone#NORMAL}（过程与正文）：开关关着就丢弃；</li>
 *   <li>{@link Tone#WARN} / {@link Tone#ERR}（警告与错误）：<b>永远放行</b> —— 那不是噪声，
 *       是"出事了"的必经通道；</li>
 *   <li>{@link Tone#TITLE}（标题）：放行（分隔与定位用）。</li>
 * </ul>
 *
 * <p>开关由调用方注入（{@link Gate}），本类不认识配置系统 —— {@code kit} 层不依赖上层类型。</p>
 */
public final class GatedOut implements Out {

    /** 开关：返回 true = 过程行也放行。 */
    public interface Gate {
        boolean on();
    }

    private final Out real;
    private final Gate gate;

    public GatedOut(Out real, Gate gate) {
        this.real = real;
        this.gate = gate;
    }

    @Override
    public void print(String text, Tone tone) {
        if (real == null) return;
        if (text == null || text.isEmpty()) return;
        if (tone == Tone.WARN || tone == Tone.ERR || tone == Tone.TITLE) {
            real.print(text, tone);
            return;
        }
        boolean on = false;
        try {
            on = gate != null && gate.on();
        } catch (Throwable ignored) {
        }
        if (on) real.print(text, tone);
    }

    /** 里面那层（诊断用）。 */
    public Out real() { return real; }
}
