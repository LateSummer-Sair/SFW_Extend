package sair.v4.skill;

import sair.v4.store.Libs;

/**
 * 技能入口接口（插件层）。
 * <p>技能类实现本接口即被识别为工具入口；类名不限，放在技能文件夹里即可。
 * 也支持反射回退：没有实现本接口的类，只要有一个 {@code airun(...)} 方法也能被调用
 * （便于 AI 快速写小技能，不必写 import）。</p>
 * <p>技能只能用 {@link Host} 给的能力面调度基板已有资源，改不动基板的任何判定。</p>
 */
public interface Skill {

    /**
     * 执行一次调用。返回值可以是 String / Map / JsonObject / List / null（空输出）。
     */
    Object call(Args a, Host h);

    /**
     * <b>装载期</b>声明自己的库（表结构由用到它的插件声明，基板只给注册表）。
     *
     * <p>时机：装载器（{@code Skills.loadOne}）在<b>实例化入口类之后、注册工具之前</b>调用一次 ——
     * 表必须早于用它的工具存在；失败只让该插件不可用并 warn 一行，不拖垮装配。</p>
     *
     * <p>写法（列/类型/默认值/索引名逐字对应现网 {@code v4.db}，改结构 = 迁移，禁止）：</p>
     * <pre>
     * public void declare(Libs libs) {
     *     libs.register(new LibSpec("memory", "长期记忆", "记忆")
     *             .ts().col("content", "TEXT", "''").fts("content")
     *             .alias("记忆", "长期记忆").idx("idx_memory_ts", "ts"));
     * }
     * </pre>
     *
     * <p>这里给的是<b>注册表</b>而不是 {@link Host}：{@code Host} 是一次对话的视图，装载期没有对话。</p>
     *
     * <p><b>纯行为插件（只挂 ContextProvider / OutboundStage / Hooked）想声明自己的表怎么办</b>：
     * 因为 {@link #call} 是抽象方法，这个插件得额外有一个类实现 {@code Skill} —— 让那个类只写
     * {@code declare(...)}、{@code call(...)} 直接 {@code return null} 即可（md 里不写 {@code tool:}
     * 就不会注册任何工具，模型看不到它）。基板会扫该插件的<b>每个顶层类</b>，谁实现了谁来声明。</p>
     */
    default void declare(Libs libs) {}

    /**
     * <b>装载期</b>声明自己有哪些 op（动作）—— 技能管控表就是按这些名字放行或封禁的。
     *
     * <p>时机与 {@link #declare(Libs)} 相同（实例化入口类之后、注册工具之前，只调一次）。</p>
     *
     * <p><b>为什么要声明</b>：{@code ai/perm gen} 要按这份清单把每个 op 列进账本
     * （默认空 = 未授权，等主人补身份）；判定侧也靠它知道"这个工具下面有哪些动作"。多动作技能
     * （一个工具名 + 一个选择子参数）把每个动作各声明一条，并在<b>每个分支开头判自己那个 op</b>
     * （{@code String deny = h.need("memory.remember");}）—— 这样"允许他记一笔"与
     * "允许他删别人的"才能分开。单动作工具声明工具名即可（{@code action} 传空串）。</p>
     *
     * <pre>
     * public void declareOps(sair.v4.auth.OpList ops) {
     *     ops.add("memory", "remember", "记一件事");
     *     ops.add("memory", "recall", "回想");
     *     ops.add("memory", "forget", "忘掉一条");
     * }
     * </pre>
     */
    default void declareOps(sair.v4.auth.OpList ops) {}
}
