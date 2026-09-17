package sair.v4.schedule;

/**
 * 调度器要回给用户的那几句话，<b>全部外挂在 md</b>（{@code data/prompts/tools-index.md}）。
 *
 * <p>契约与主循环的连续失败闸门一致：<b>缺键返回 null，调用方报错而不是编一句</b>
 * （见 {@link #missing(String)}）。基板 Java 里没有一句面向用户的话。</p>
 */
public interface Texts {

    /** "溢出（这条没排上队）"文案；缺键返回 null。 */
    String overflow();

    /** "排队中（正在处理上一条）"文案；缺键返回 null。 */
    String queued();

    /** 缺键上报（同一把键只会被报一次）。 */
    void missing(String key);
}
