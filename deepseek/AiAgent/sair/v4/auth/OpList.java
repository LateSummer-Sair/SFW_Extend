package sair.v4.auth;

/**
 * 技能在<b>装载期</b>声明自己有哪些 op（动作）。
 *
 * <p>op 名 = {@code 工具名} 或 {@code 工具名.动作名}。单动作工具只声明工具名；多动作工具
 * （一个工具名 + 一个选择子参数）把每个动作各声明一条 —— 管控表就是按这些名字放行或封禁的，
 * 所以"允许他记一笔"与"允许他删别人的"能在表里分开写。</p>
 *
 * <pre>
 *   // 单动作
 *   ops.add("weather", "", "查天气");
 *   // 多动作：每个分支都要有自己的 op，并在分支开头判它
 *   ops.add("memory", "remember", "记一件事");
 *   ops.add("memory", "recall", "回想");
 *   ops.add("memory", "forget", "忘掉");
 * </pre>
 *
 * <p>判定见 {@link Acl#allow(Caller, String)}。</p>
 */
public interface OpList {

    /**
     * 声明一个 op。
     *
     * @param tool   工具名（模型看到的那个名字）
     * @param action 动作名；单动作工具传空串
     * @param note   一句话说明（只用于 {@code ai/perm list} 的展示）
     */
    void add(String tool, String action, String note);

    /**
     * 声明"这把工具是干什么的"（一句话，写进技能管控表的分组注释里，给人看）。
     *
     * <p>基板也会自动补这一句（工具注册时取它的中文说明），技能一般不写。</p>
     */
    default void tool(String tool, String note) {}
}
