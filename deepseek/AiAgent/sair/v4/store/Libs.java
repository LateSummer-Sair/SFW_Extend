package sair.v4.store;

import java.util.List;

/**
 * 库注册表：<b>"世界上有哪些表"的唯一答案</b>。
 *
 * <p>基板启动时注册自己的 7 张环境表；插件装载时注册自己的业务表。之后
 * {@link Store} 的所有 SQL 白名单校验、清单展示、中文别名解析都问这里 ——
 * 换库不换代码，加表不改基板。</p>
 *
 * <h3>约定</h3>
 * <ol>
 *   <li><b>同表名被别人抢 = 报错</b>（不静默覆盖）：两张表抢一个名字一定是 bug，
 *       宁可让后来者失败，也不要让先来者的数据被另一套列定义解释。
 *       例外：<b>同一个主人</b>重新声明自己那张表（插件重扫时必然发生）= 原地更新，返回 true。</li>
 *   <li><b>卸载插件不删声明</b>：表是数据，不该随插件消失；但记下 owner 便于诊断。</li>
 *   <li><b>查不到就是查不到</b>：{@link #get} 返回 {@code null}，调用方（{@link Store}）
 *       把"没有这个库"翻成一句人话，不许抛异常拖垮一轮对话。</li>
 * </ol>
 */
public interface Libs {

    /**
     * 注册一张表的声明。
     *
     * @return true = 注册成功（含"同一个主人重新声明"的原地更新）；
     *         false = 表名/别名已被<b>别的</b>主人占用（调用方自己决定是警告还是失败）
     */
    boolean register(LibSpec spec);

    /** 按表名或中文别名取声明（{@code 记忆} → {@code memory}）；没有返回 null。 */
    LibSpec get(String tableOrAlias);

    /** 全部声明（环境表在前、插件表按注册顺序）。 */
    List<LibSpec> all();

    /** 表名清单（{@code store} 工具报"可用库"与体检用）。 */
    List<String> tables();

    /** 基板自己的环境表（{@link #all} 的子集，owner 为空串的那些）。 */
    List<LibSpec> env();

    /** 注册条数。 */
    int size();
}
