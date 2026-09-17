package sair.v4.kit;

/**
 * 基板输出抽象。SFW 控制台（{@code con.SfwOut}）与探针（stdout/捕获）共用同一口径，
 * 使基板代码不直接依赖 SFW GUI，可在无界面环境里跑测试。
 */
public interface Out {

    /** 语义色（由具体实现映射到实际颜色）。 */
    enum Tone { NORMAL, DIM, OK, WARN, ERR, TITLE }

    /** 原样输出一段文本（不追加换行）。 */
    void print(String text, Tone tone);

    default void line(String text) { print(text + "\n", Tone.NORMAL); }

    default void line(String text, Tone tone) { print(text + "\n", tone); }

    default void dim(String text) { print(text + "\n", Tone.DIM); }

    default void ok(String text) { print(text + "\n", Tone.OK); }

    default void warn(String text) { print(text + "\n", Tone.WARN); }

    default void err(String text) { print(text + "\n", Tone.ERR); }

    default void title(String text) { print(text + "\n", Tone.TITLE); }
}
