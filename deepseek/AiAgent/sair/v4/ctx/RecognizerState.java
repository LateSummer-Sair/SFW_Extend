package sair.v4.ctx;

import java.util.function.Predicate;
import java.util.function.Supplier;

import sair.v4.kit.Str;
import sair.v4.tool.Registry;

/**
 * 识图组件的两件事实：<b>它注册的工具名在不在工具面</b>、<b>今天被调用了几次</b>。
 *
 * <p>事实块那一行 {@code models: {... "recognizer": …, "recognizer_uses": …}} 的唯一数据源就是这里：
 * {@link CtxBuild#facts} 读它，{@code sair.v4.agent.Loop} 在调工具之前记它。加这一层状态载体的理由 ——
 * {@code CtxBuild} 手上没有工具注册表，{@code Loop} 手上没有上下文装配器，两边都只认识这一个极小的对象，
 * 于是谁都不用改公开签名、也不用互相依赖。</p>
 *
 * <p><b>三条写死的口径</b>（改这里之前先读完）：</p>
 * <ol>
 *   <li><b>{@link #TOOL} 是"约定名"，不是新接口面</b>：识图组件用哪个工具名注册，由技能 md 的
 *       {@code tool:} 声明（现码 {@code data/skills/辅助识图/辅助识图.md:4} 的 {@code tool: look}）。
 *       基板只做一件事 —— 拿这个名字去<b>既有的</b>工具注册表问一句"在不在"。<br>
 *       <b>将来若有第二个识图技能，必须在这里重新设计</b>（例如从工具面反查、或由声明方登记），
 *       <b>不许在别处偷偷硬编码第二个名字</b> —— 那会让"哪一个是识图组件"这个判断散落到多个文件，
 *       以后谁改都改不干净。</li>
 *   <li><b>{@link #uses()} 的定义（鱼总裁定，定格）：</b>
 *       <b>{@code recognizer_uses} = 该工具经工具面被调用的次数（含被拒 / 失败）。</b>
 *       计数点是<b>工具面入口</b>（{@code agent.Loop} 调 {@code registry.call} 之前记的那一笔），
 *       <b>不是</b>"成功识别次数" —— 别把它读成后者，也别拿它算成功率。<br>
 *       <b>已知边界（是定义、不是漏计）</b>：技能内部 {@code h.call("look", …)} 走
 *       {@code Host.call → Registry.call}，<b>不经过工具面入口那一点</b> ⇒ <b>不计入</b>。
 *       这条口径就是"经工具面调了几次"，技能互调天然不在其中。</li>
 *   <li><b>计数是内存计数：跨日归零，进程重启后从 0 重计</b>。不落盘、也<b>不读技能的私有 kv 键</b>
 *       （那是禁止的耦合）。所以 {@link #uses()} 在<b>重启后从 0 重新开始</b>，这是刻意且已知的取舍：
 *       不要把它当持久账本用，也不要拿它算"历史总量"。唯一会归零的两个时点 = <b>跨日</b>与<b>重启</b>。</li>
 *   <li><b>不注入 = 恒 {@code off} / 0，且任何路径都不许抛</b>：工具名不在面、注册表为 {@code null}、
 *       取不到"今天"、并发 bump —— 全部退化成"没有这个组件"，事实块绝不许因为一行事实把回合拖垮，
 *       也不许刷日志。</li>
 * </ol>
 */
public final class RecognizerState {

    /**
     * 识图组件的<b>约定工具名</b>（约定，不是新接口面 —— 见类注释第 1 条）。
     *
     * <p>来源：{@code data/skills/辅助识图/辅助识图.md:4} 的 {@code tool: look}。这是基板与技能之间
     * <b>唯一的那一处约定</b>：技能侧改名，这里跟着改，<b>而且只许改这一处</b>。</p>
     */
    public static final String TOOL = "look";

    /** 组件不在工具面时的取值（与"没有这个组件"同义；事实块里就是这么写的）。 */
    public static final String OFF = "off";

    /** 全进程共享的一份：{@code Boot} 注入探测口，{@code Loop} 计数，{@code CtxBuild} 读事实。 */
    private static final RecognizerState SHARED = new RecognizerState();

    /** 上面那一份（{@code Loop} 只认识这个静态入口，因此不必新增任何依赖或签名）。 */
    public static RecognizerState shared() { return SHARED; }

    /**
     * 工具名 ⇒ <b>在不在工具面</b>。{@code null} = 没有探测口 = 恒不在面（不注入的场合）。
     * <p>每次问都是现问（技能可以热重载），所以这里不缓存、也不读配置。</p>
     */
    private volatile Predicate<String> probe;

    /**
     * "今天"的来源（{@code Str.day()} 的 {@code yyyy-MM-dd} 口径）。
     * <p>默认走系统时钟；可注入<b>只是为了"跨日归零"这条能被断言</b>（探针不可能等到明天），
     * 装配期永远用默认值 —— 不新增配置键、不新增时钟组件。</p>
     */
    private final Supplier<String> clock;

    /** 下面两个字段一起受它保护（跨日翻页必须与自增原子）。 */
    private final Object lock = new Object();

    /** 计数所属的那一天（受 {@link #lock} 保护）。 */
    private String day = "";

    /** 今天的调用次数（受 {@link #lock} 保护）。 */
    private long count;

    public RecognizerState() { this(null); }

    public RecognizerState(Supplier<String> clock) { this.clock = clock; }

    /** 注入探测口（装配期调一次）。传 {@code null} = 回到"恒不在面"。 */
    public void probe(Predicate<String> p) { this.probe = p; }

    /**
     * 组件注册的工具名；不在工具面时是 {@link #OFF}。
     * <p>每次都现问一遍工具面（技能热重载后立刻变对）。<b>绝不抛</b>：探测口出岔子 = 当作不在面。</p>
     */
    public String state() {
        try {
            Predicate<String> p = probe;
            return (p != null && p.test(TOOL)) ? TOOL : OFF;
        } catch (Throwable e) {
            return OFF;
        }
    }

    /** 今天被调用了几次（跨日自动归零；重启从 0 重计 —— 见类注释第 3 条）。<b>绝不抛。</b>
     *  <p>口径（鱼总裁定）：<b>该工具经工具面被调用的次数（含被拒 / 失败）</b>，不是"成功识别次数"。</p> */
    public long uses() {
        try {
            synchronized (lock) {
                roll();
                return count;
            }
        } catch (Throwable e) {
            return 0L;
        }
    }

    /**
     * 记一次调用。<b>只有 {@link #TOOL} 才计数</b>，别的工具名一律忽略（不做前缀匹配、不留白名单表）。
     *
     * <p><b>口径（鱼总裁定，定格）</b>：计的是<b>该工具经工具面被调用的次数（含被拒 / 失败）</b> ——
     * 所以 {@code agent.Loop} 把这一笔记在 {@code registry.call} <b>之前</b>；调用后来被拒、报错、
     * 或被中途掐停，这一笔<b>照样算</b>。要"仅成功计数"得改 {@code Registry.call} 的返回结构
     * （可选精化、非必需，本轮不做）。</p>
     *
     * <p><b>已知边界（是定义、不是漏计）</b>：技能内部 {@code h.call("look", …)} 不经过这个入口
     * ⇒ <b>不计入</b>（口径就是"经工具面调了几次"）。</p>
     *
     * @param toolName 这一轮真正被调用的工具名（{@code agent.Loop} 在调工具之前记的那一笔）
     */
    public void bump(String toolName) {
        if (!TOOL.equals(toolName)) return;
        try {
            synchronized (lock) {
                roll();
                count++;
            }
        } catch (Throwable ignored) {
            // 少记一次数不该影响这一轮工具调用本身
        }
    }

    /** 跨日翻页：与"今天"不同就归零（只在 {@link #lock} 里调）。 */
    private void roll() {
        String d = today();
        if (!d.equals(day)) {
            day = d;
            count = 0L;
        }
    }

    /** 今天是哪天；取不到时退化成一个固定串（= 一整天都不翻页），绝不抛。 */
    private String today() {
        try {
            String d = clock == null ? null : clock.get();
            return d == null ? Str.day() : d;
        } catch (Throwable e) {
            return "?";
        }
    }

    /**
     * 用<b>既有只读口</b>拼出来的探测口：{@code Registry.get(名字) != null} ⇒ 在工具面。
     *
     * <p><b>为什么问 {@link Registry}，不问 {@code Skills}、也不问 ACL：</b></p>
     * <ul>
     *   <li>注册表就是"工具面"本身：一个名字在不在，它一次哈希查找说了算。{@code Skills} 是装载器，
     *       答的是"哪个技能声明了这个名字"，绕一层，还可能答"还没扫到"。</li>
     *   <li>这一行事实说的是<b>组件装没装</b>，不是"这个调用者能不能用"（后者是 ACL 的 T 类 X 位）。
     *       按调用者筛的话普通用户的回合会显示 {@code off}（他们的工具面本来就是空的），
     *       等于把"组件不存在"与"你看不见它"编成同一句话。可见性另有既有事实承载（工具表 / 工具索引）。</li>
     *   <li>按调用者筛要每轮遍历全表、逐把问账本（{@code visible(c)} 每次新建一个 List），
     *       而这一行事实<b>每轮都要拼</b> —— 代价与收益不成比例。</li>
     * </ul>
     *
     * @param reg 工具注册表（可空：没装配 = 恒不在面，不抛）
     */
    public static Predicate<String> registryProbe(final Registry reg) {
        return new Predicate<String>() {
            @Override
            public boolean test(String name) {
                try {
                    return reg != null && name != null && reg.get(name) != null;
                } catch (Throwable e) {
                    return false;   // 问不出来 = 当作不在面（fail-clean）
                }
            }
        };
    }
}
