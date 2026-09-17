package sair.v4.ui;

import sair.sys.gui.ConsFrame;

/**
 * 把 {@link TalkPanel} 挂进 SFW 控制台（用框架<b>现成</b>的控件输出协议，不新增/不改框架）。
 *
 * <h3>协议（读源码得到的事实，不是猜的）</h3>
 * <ol>
 *   <li>公开入口只有一个：{@code ConsFrame.printComponent(java.awt.Component)}
 *       （{@code ConsFrame.java:569}）。</li>
 *   <li>它按类型分派（{@code ConsFrame.printComponent0}，{@code ConsFrame.java:581-619}）：
 *       <b>画布</b>（{@code JPanel}/{@code Panel}/{@code Canvas}，判据 {@code isCanvas}
 *       {@code ConsFrame.java:576-578}）→ 新开一个选项卡入"画布选项卡隔离区"
 *       （{@code addCanvasTab} → {@code cf.tabsPane.addTab(name, canvas)}，{@code ConsFrame.java:641-650}）；</li>
 *   <li>选项卡标题取 {@code component.getName()}，没有名字就自动叫 {@code 面板N}
 *       （{@code ConsFrame.java:644-647}）—— 所以想控制标题就 {@code setName()}；</li>
 *   <li>隔离区是主窗口<b>中心面板右侧的 EAST 停靠区</b>（{@code eastWrap} + {@code tabsPane}，
 *       {@code ConsFrame.java:186-196} / {@code 1051-1068}），与文本流<b>完全隔离</b>：
 *       进选项卡的东西不写进 {@code infoPane} —— 这就是"不污染主输出框"的机制保证；</li>
 *   <li>翻页/关闭由框架自己的右键菜单管：{@code CloseTabAction}（关被右键的那一页）/
 *       {@code ClearTabsAction} → {@code ConsFrame.clearComponents()}（{@code ClicksAct.java:184-211}）。</li>
 * </ol>
 *
 * <h3>为什么不用别的入口</h3>
 * <ul>
 *   <li>{@code SairCons.insertPrinto(Integer index, Color, String)} 的 {@code index}
 *       是<b>文档插入偏移</b>（不是标签索引、也不是打印序号）：{@code index == null} 表示追加到末尾。
 *       证据：{@code PrintRunnable} 的形参 javadoc"插入位置(可为 null 表示追加)"，
 *       以及 {@code ConsFrame.printo0} 里 {@code if (index == null) index = docs.getLength();}
 *       （{@code ConsFrame.java:489-491}）。它写的是主输出框，正是我们要避开的东西。</li>
 *   <li>{@code ConsFrame.cf.tabsPane} 是包私有字段（无 public getter），
 *       插件在 {@code sair.sys.gui} 之外<b>拿不到</b>，所以"自己 addTab、只关自己那一页"都做不到；
 *       能用的就是 {@code printComponent} + {@code clearComponents} 这两个公开方法。</li>
 *   <li>{@code sair.sys.gui.PackageClasses} <b>不是</b>插件面板注册协议：它只是插件列表的
 *       鼠标适配（单击打印选中项、双击执行 {@code /print-cpr}）与单元格渲染器
 *       （{@code PackageClasses.java:18-30} / {@code 41-66}），与选项卡无关。</li>
 * </ul>
 *
 * <h3>线程</h3>
 * <p><b>挂载动作投递到 EDT 上做</b>（{@link javax.swing.SwingUtilities#invokeLater}，调用方按
 * {@code waitMs} 等结果）。原因与 {@link TalkPanel} 的文档写入同一条：往活着的
 * {@code JTabbedPane} 里加组件、让隔离区从隐藏变可见，都会触碰 Swing 的视图/布局树 ——
 * 从非 EDT 线程做会与 EDT 正在跑的视图更新打架（实测过 {@code AIOOBE} 抛穿 EDT，
 * 界面从此"点不动"）。V3 当年也是 {@code SwingUtilities.invokeLater(() -> ConsFrame.printComponent(panel))}
 * 挂的（旧工程 {@code core/AgentActionHandler.java:110-118}）。</p>
 * <p>无 GUI 环境（探针）里 {@code ConsFrame} 的静态初始化会失败
 * （{@code new SFrame} → {@code HeadlessException} → {@code ExceptionInInitializerError}），
 * 本类用 {@code catch (Throwable)} 兜住，返回 false，<b>绝不抛给基板</b>。</p>
 */
public final class TalkMount {

    private TalkMount() {
    }

    /** 最近一次挂上的面板（诊断用）。 */
    private static volatile TalkPanel attached;
    /** 最近一次挂载是否成功。 */
    private static volatile boolean mounted;
    /** 最近一次失败原因（成功时为 null）。 */
    private static volatile String lastError;
    /** 默认等待上限：等 EDT 把挂载做完（毫秒）。 */
    public static final long DEF_WAIT_MS = 5000L;

    /** 挂载（标题沿用面板自己的 {@code getName()}）。 */
    public static boolean mount(TalkPanel p) {
        return mount(p, null, DEF_WAIT_MS);
    }

    /**
     * 挂载到控制台的选项卡隔离区（<b>动作在 EDT 上执行</b>，本方法等它做完）。
     *
     * @param p        面板
     * @param tabTitle 选项卡标题（非空时先 {@code p.setName(tabTitle)}；null/空则沿用面板已设的名字）
     * @return 挂上了返回 true；无 GUI / 框架拒绝 / 等待超时返回 false（不抛异常）
     */
    public static boolean mount(TalkPanel p, String tabTitle) {
        return mount(p, tabTitle, DEF_WAIT_MS);
    }

    /** 同 {@link #mount(TalkPanel, String)}，等待上限可指定（毫秒；{@code <=0} = 不等待）。 */
    public static boolean mount(TalkPanel p, final String tabTitle, long waitMs) {
        if (p == null) {
            lastError = "panel == null";
            mounted = false;
            return false;
        }
        if (onEdt()) return mountNow(p, tabTitle);      // 已经在 EDT 上：直接做（不能再投递，会等自己）
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        mountNow(p, tabTitle);
                    } finally {
                        done.countDown();
                    }
                }
            });
        } catch (Throwable t) {
            mounted = false;
            lastError = err(t);
            return false;
        }
        try {
            done.await(Math.max(0L, waitMs), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (done.getCount() > 0L && lastError == null) lastError = "等待 EDT 挂载超时（" + waitMs + "ms）";
        return mounted;
    }

    /** 真正做挂载（<b>必须在 EDT 上调用</b>）。 */
    private static boolean mountNow(TalkPanel p, String tabTitle) {
        try {
            // 幂等：框架的 printComponent 不查重，同一个面板挂两次就是多开一页。
            // 本方法只在 EDT 上跑（EDT 上就地调 / 非 EDT 投递过来），所以"查一下再挂"之间没人能插进来。
            if (p.getParent() != null) {
                attached = p;
                mounted = true;
                lastError = null;
                return true;
            }
            if (tabTitle != null && tabTitle.length() > 0) p.setName(tabTitle);
            // 唯一公开入口：JPanel 会被识别成"画布" → 入选项卡隔离区（不碰主输出框）
            ConsFrame.printComponent(p);
            // 框架 addCanvasTab 会对整棵子树做 transparentTree（opaque 全置 false）：
            // 面板想要实底就得在挂载之后再刷一遍自己的底色
            p.reapplySurface();
            // 对齐框架样式（字体族取控制台正文窗格的活字体、滚动条换 SairScrollBarUI）。
            // 只在真挂载之后做：TalkPanel 本身不依赖 sair.sys，探针里的面板永远是"素"的。
            sair.v4.ui.SfwStyle.apply(p);
            attached = p;
            mounted = true;
            lastError = null;
            return true;
        } catch (Throwable t) {
            // 无界面环境：ConsFrame 静态初始化失败（HeadlessException / ExceptionInInitializerError /
            // NoClassDefFoundError）。基板不能因为挂不上面板就起不来。
            mounted = false;
            lastError = err(t);
            return false;
        }
    }

    /** 当前线程是不是 EDT。 */
    private static boolean onEdt() {
        try {
            return javax.swing.SwingUtilities.isEventDispatchThread();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 最近一次挂载是否成功。 */
    public static boolean mounted() {
        return mounted;
    }

    /** 最近一次挂上的面板（可能为 null）。 */
    public static TalkPanel panel() {
        return attached;
    }

    /** 最近一次失败原因（成功时为 null）。 */
    public static String lastError() {
        return lastError;
    }

    /**
     * 摘除<b>全部</b>选项卡（框架没有"只关某一页"的公开入口；单页关闭只能由用户在
     * 控制台里右键"关闭此面板"，见 {@code ClicksAct.CloseTabAction}）。
     * <p>与 {@link #mount} 同一条规矩：动作投递到 EDT 上做，本方法等它做完（有上限）。</p>
     */
    public static void unmountAll() {
        if (onEdt()) {
            unmountNow();
            return;
        }
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            javax.swing.SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    try {
                        unmountNow();
                    } finally {
                        done.countDown();
                    }
                }
            });
            done.await(DEF_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            lastError = err(t);
        }
    }

    /** 真正摘除（必须在 EDT 上调用）。 */
    private static void unmountNow() {
        try {
            ConsFrame.clearComponents();
            attached = null;
            mounted = false;
            lastError = null;
        } catch (Throwable t) {
            lastError = err(t);
        }
    }

    /** 失败原因（部分 Error 的 getMessage 为 null，带上类名更可读）。 */
    private static String err(Throwable t) {
        if (t == null) return "unknown";
        String m = t.getMessage();
        String n = t.getClass().getName();
        return (m == null || m.length() == 0) ? n : (n + ": " + m);
    }
}
