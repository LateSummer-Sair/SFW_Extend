package sair.v4.ui;

import java.awt.Adjustable;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * 本地流式交流面板（SFW 控制台里的"主人对话"专板，基板⑨的<b>界面</b>半边）。
 *
 * <h3>它解决什么</h3>
 * <p>本地控制台 = 主人对话。对话正文（流式打字机效果）和"逻辑怎么走"的结构性日志混在同一个
 * 输出流里，正文会被装配/工具/统计行冲散。本面板把<b>对话</b>单独承载到一块 JPanel 上，
 * 再按框架的控件输出协议挂进控制台的选项卡隔离区，从而<b>主输出框只留结构性日志</b>。</p>
 *
 * <h3>富文本</h3>
 * <p>内部是 {@link JTextPane} + {@link StyledDocument}，<b>不做 HTML 拼接</b>：每次追加只是
 * {@code doc.insertString(末尾, 文本, 该风格的 AttributeSet)}，所以同一文档里不同行可以有不同的
 * 字号/颜色/粗斜体。样式是语义化的（{@link Style}），只定义视觉、不含任何文案。</p>
 *
 * <h3>线程模型：账本任一线程可写，<b>文档只在 EDT 上改</b></h3>
 * <p>任一线程都可 {@link #append} / {@link #streamDelta} / {@link #line}，但<b>它们只动账本</b>
 * （{@code segs}/{@code chars}/待办队列），真正的 {@code insertString}/{@code remove}
 * 由 EDT 上的一次落盘统一做（{@link #flushNow()}）。</p>
 *
 * <p><b>为什么必须这样（实测事故，不是洁癖）</b>：{@code DefaultStyledDocument} 的插入会派发
 * 文档事件，其中"元素结构变化"是<b>异步</b>排进 EDT 的（{@code ChangeUpdateRunnable}）。
 * 于是"非 EDT 线程 insertString"与"EDT 正在跑的视图更新"会同时改同一棵视图树 —— 实测直接抛
 * {@code ArrayIndexOutOfBoundsException}：一次抛在插入线程的 {@code CompositeView.replace}，
 * 一次抛穿 EDT 的 {@code run()}（{@code CompositeView.getView}），
 * <b>EDT 线程死掉 → 窗口留着最后一帧、事件再也没人处理 = 界面永久"点不动"</b>。
 * 框架自带的"调用线程直接执行 + printLock"只串行化了写入者，管不住这条异步视图更新，
 * 所以本类不沿用那套：<b>写入者收敛到 EDT 一个</b>。</p>
 *
 * <ul>
 *   <li>读取类接口（{@link #text()} / {@link #document()} / {@link #textPane()}）会自动等一次落盘
 *       （{@link #flushSync(long)}），调用方拿到的仍是"已经写进去"的内容；</li>
 *   <li>计数类接口（{@link #entryCount()} / {@link #charCount()}）读账本，<b>立即可见</b>；</li>
 *   <li>无界面（{@code headless}）探针里没有真正跑起来的 EDT：{@link #flushNow()} 由调用线程直落，
 *       语义与旧版一致（探针不受影响）；</li>
 *   <li>贴底仍不靠我们推：插入后 {@code pane.setCaretPosition(文档末尾)}，由 JDK 的
 *       {@code DefaultCaret} 内部完成（{@code ConsFrame.java:513-525} 同款）。</li>
 * </ul>
 *
 * <h3>自动滚动</h3>
 * <p>默认贴底；<b>用户上滚时不强制拉回</b>。判据是滚轮/拖动导致的滚动事件（Swing 在 EDT 派发），
 * 每次事件把 {@code stick} 重算为"当前是否已在底部"，因此我们自己的贴底动作也会把它重算回 true，
 * 不需要区分"谁滚的"。{@link #setAutoScroll(boolean)} 可整体关掉。</p>
 *
 * <h3>无 GUI / 无 SFW 时</h3>
 * <p>构造与追加都包在 {@code catch (Throwable)} 里：Swing 文档建不起来就退化为<b>内存记录</b>
 * （{@link #degraded()} 为 true，{@link #text()} 仍读得到），<b>绝不把异常抛给基板</b>。</p>
 *
 * <h3>挂载</h3>
 * <p>本类<b>不 import 任何 {@code sair.sys} 类型</b>（探针可以在没有 SFW 的 JVM 里直接跑）；
 * 挂载在 {@link TalkMount}。</p>
 */
public class TalkPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** 有界缓冲默认上限：条数（阈值可配；接线方从 Conf 读进来）。 */
    public static final int DEF_MAX_ENTRIES = 2000;
    /** 有界缓冲默认上限：字符数。 */
    public static final int DEF_MAX_CHARS = 200 * 1024;

    /** 贴底判定容差（px）：滚动条离底不超过它就算"在底部"。 */
    private static final int BOTTOM_SLACK = 4;

    /** 面板默认首选尺寸（选项卡内容区由 JTabbedPane 决定，这里只是给个不为 0 的初值）。 */
    private static final Dimension DEF_PREF = new Dimension(480, 320);

    /** 语义样式：只定义<b>视觉</b>（字号/颜色/粗斜体），不写死任何文案。 */
    public enum Style {

        /**
         * 标题（加大加粗）。<b>唯一的例外</b>：它比正文大一号，因为"标题"这个语义本身要求"加大"；
         * 若要求 assistant 严格最大，把这里与 {@link #ASSISTANT} 的 size 对调即可（见 notes/talk-panel.md）。
         */
        TITLE("title", 18, true, false, new Color(0x78E6FF)),
        /** AI 回复正文：<b>正文字号最大</b>的一种（16），颜色沿用 V3 StreamPrinter 的 C_AI 翠绿。 */
        ASSISTANT("assistant", 16, false, false, new Color(0x64FFB4)),
        /** 主人说的话（加粗，与正文区分）。 */
        USER("user", 15, true, false, new Color(0x96D7FF)),
        /** 成功/完成一行。 */
        OK("ok", 14, false, false, new Color(0x6EEB82)),
        /** 告警一行。 */
        WARN("warn", 14, false, false, new Color(0xFFCD46)),
        /** 错误一行（加粗）。 */
        ERR("err", 14, true, false, new Color(0xFF5F5F)),
        /** 推理/思考：灰色小字斜体（可选展示）。 */
        REASONING("reasoning", 12, false, true, new Color(0x9696A5)),
        /** 工具调用一行：小字暗色。 */
        TOOL("tool", 12, false, false, new Color(0x7D8C9B)),
        /** 回合统计一行：小字暗色（比工具再暗一档）。 */
        TURN("turn", 12, false, false, new Color(0x697887));

        /** 稳定键名（探针/Conf/日志用；不是给人看的文案）。 */
        public final String key;
        /** 字号（pt）。 */
        public final int size;
        /** 是否加粗。 */
        public final boolean bold;
        /** 是否斜体。 */
        public final boolean italic;
        /** 前景色。 */
        public final Color color;

        Style(String key, int size, boolean bold, boolean italic, Color color) {
            this.key = key;
            this.size = size;
            this.bold = bold;
            this.italic = italic;
            this.color = color;
        }

        /** 本样式的字体（族名延迟解析一次，见 {@link TalkPanel#family()}）。 */
        public Font font() {
            int st = (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
            return new Font(family(), st, size);
        }

        /**
         * 本样式的文档属性集（延迟构造并缓存）：同一实例复用，{@code DefaultStyledDocument}
         * 会把相邻同属性文本并成同一个 run，不产生每段一个 Element 的开销。
         */
        public SimpleAttributeSet attrs() {
            SimpleAttributeSet a = cached;
            if (a != null) return a;
            synchronized (this) {
                if (cached == null) {
                    a = new SimpleAttributeSet();
                    StyleConstants.setFontFamily(a, family());
                    StyleConstants.setFontSize(a, size);
                    StyleConstants.setBold(a, bold);
                    StyleConstants.setItalic(a, italic);
                    StyleConstants.setForeground(a, color);
                    cached = a;
                }
                return cached;
            }
        }

        private volatile SimpleAttributeSet cached;

        /** 换字体族后重建属性集（族名换了，字号/颜色/粗斜体不动）。 */
        void rebuildAttrs() {
            synchronized (this) {
                SimpleAttributeSet a = new SimpleAttributeSet();
                StyleConstants.setFontFamily(a, family());
                StyleConstants.setFontSize(a, size);
                StyleConstants.setBold(a, bold);
                StyleConstants.setItalic(a, italic);
                StyleConstants.setForeground(a, color);
                cached = a;
            }
        }

        /** 按键名查样式；不认识返回 null（不抛异常）。 */
        public static Style of(String key) {
            if (key == null) return null;
            String k = key.trim();
            for (Style s : values()) {
                if (s.key.equalsIgnoreCase(k) || s.name().equalsIgnoreCase(k)) return s;
            }
            return null;
        }
    }

    // ==================== 字体族（延迟解析一次，失败一律回退逻辑字体） ====================

    private static volatile String FAMILY;

    /**
     * 面板字体族：优先中文界面字体，取不到就用逻辑字体 {@link Font#SANS_SERIF}。
     * <p>只在第一次真正需要时枚举一次系统字体并缓存；任何异常（含无界面环境）都静默回退，
     * 不参与构造失败。</p>
     */
    static String family() {
        String f = FAMILY;
        if (f != null) return f;
        String picked = Font.SANS_SERIF;
        try {
            String[] prefer = { "Microsoft YaHei UI", "Microsoft YaHei", "Noto Sans CJK SC", "SimHei" };
            Set<String> have = new HashSet<String>();
            String[] names = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            if (names != null) {
                for (int i = 0; i < names.length; i++) {
                    if (names[i] != null) have.add(names[i]);
                }
            }
            for (int i = 0; i < prefer.length; i++) {
                if (have.contains(prefer[i])) {
                    picked = prefer[i];
                    break;
                }
            }
        } catch (Throwable ignored) {
            // 枚举不了系统字体（无界面/受限环境）→ 逻辑字体一定在
        }
        FAMILY = picked;
        return picked;
    }

    /** 提前把字体族解析掉（可选；探针在计时前调用，避免首次构造把枚举耗时算进测量）。 */
    public static void warmup() {
        family();
        Style.ASSISTANT.attrs();
    }

    /**
     * 换字体族（挂进 SFW 控制台后由 {@code ui.SfwStyle} 调成框架那一套，风格才统一）。
     *
     * <p>只改族名，<b>字号/颜色/粗斜体这些语义层级不动</b>；已经写进文档的旧片元保持原样
     * （新建的片元用新族名）。重复调用同族名是空操作。非 SFW 环境（探针）从不调用它，
     * 所以探针看到的仍是枚举出来的中文字体族。</p>
     *
     * @return true = 真的换了族名
     */
    public static boolean useFontFamily(String fam) {
        String f = fam == null ? "" : fam.trim();
        if (f.isEmpty() || f.equals(family())) return false;
        FAMILY = f;
        for (Style s : Style.values()) s.rebuildAttrs();
        return true;
    }

    // ==================== 状态 ====================

    /** 把"文档写入 + 裁剪 + 段计数"整体串行化（与框架 printLock 同思路：调用线程直接执行）。 */
    private final Object lock = new Object();

    /** 文本窗格（null = 已退化到内存记录）。 */
    private final JTextPane pane;
    /** 滚动容器（null = 已退化到内存记录）。 */
    private final JScrollPane scroll;
    /** 富文本文档（null = 已退化到内存记录）。 */
    private final StyledDocument doc;
    /** 是否退化到内存记录。 */
    private final boolean degraded;

    /** 每段的字符数（先进先出；裁剪时按它从文档头部删除）。 */
    private final ArrayDeque<Integer> segs = new ArrayDeque<Integer>();
    /** 文档当前字符数（= 各段之和；不调 doc.getLength()，避免每次 O(1) 也去碰 Swing 锁）。 */
    private long chars = 0L;
    /** 条数上限。 */
    private volatile int maxEntries;
    /** 字符数上限。 */
    private volatile int maxChars;

    /** 关掉就完全不自动滚动。 */
    private volatile boolean autoScroll = true;
    /** 是否贴底（由滚动事件重算；初始 true = 还没人来滚过，应当贴底）。 */
    private volatile boolean stick = true;
    /** 当前是否处于一段流式增量中（文档末尾没有换行）。 */
    private volatile boolean inStream = false;
    /** 挂载后想不想要一块实底（框架会把子树 opaque 全置 false，见 {@link #reapplySurface()}）。 */
    private volatile Color solidBg = null;

    /** 退化路径的内存记录（仅 {@link #degraded} 时用）。 */
    private final List<String> memText = new ArrayList<String>();
    private final List<Style> memStyle = new ArrayList<Style>();

    // ---- 待办：账本已经加好、等 EDT 落盘的那部分（见类注释的线程模型） ----

    /** 待插入的片元（{@code [String text, Style style]}，任一线程入队、EDT 出队）。 */
    private final ArrayDeque<Object[]> pending = new ArrayDeque<Object[]>();
    /** 待从文档头部删掉的字符数（裁剪只记账，删除留到落盘时一次做）。 */
    private long pendingDrop = 0L;
    /** 待清空文档（{@link #clear()} 只记账）。 */
    private boolean pendingClear = false;
    /** 待办里是否有"插入后要贴底"。 */
    private boolean pendingFollow = false;
    /** 已经投递了一次落盘任务（EDT 上一次只有一个，落完自清）。 */
    private boolean flushScheduled = false;

    /** 落盘任务（EDT）。 */
    private final Runnable flusher = new Runnable() {
        @Override
        public void run() {
            flushNow();
        }
    };

    // ==================== 构造 ====================

    public TalkPanel() {
        this(DEF_MAX_ENTRIES, DEF_MAX_CHARS);
    }

    /**
     * @param maxEntries 条数上限（&lt;=0 用默认）
     * @param maxChars   字符数上限（&lt;=0 用默认）
     */
    public TalkPanel(int maxEntries, int maxChars) {
        super(new BorderLayout(0, 0));
        this.maxEntries = maxEntries > 0 ? maxEntries : DEF_MAX_ENTRIES;
        this.maxChars = maxChars > 0 ? maxChars : DEF_MAX_CHARS;

        JTextPane p = null;
        JScrollPane s = null;
        StyledDocument d = null;
        try {
            // 文档独立于窗格构造：即使窗格建不起来，只要文档在就仍然走真·富文本路径
            d = new DefaultStyledDocument();
            p = new JTextPane(d);
            d = (StyledDocument) p.getDocument();
            p.setEditable(false);
            p.setOpaque(false);
            p.setBorder(null);
            p.setFont(Style.ASSISTANT.font());
            p.setCaretColor(Style.ASSISTANT.color);

            s = new JScrollPane(p, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            s.setOpaque(false);
            s.setBorder(null);
            s.getViewport().setOpaque(false);
            JScrollBar vb = s.getVerticalScrollBar();
            if (vb != null) {
                vb.setUnitIncrement(Style.TOOL.size + 2);
                vb.addAdjustmentListener(new StickAdapter());
                // 拖动拇指：跟随状态 = 拖动那一刻的真实位置
                ThumbAdapter thumb = new ThumbAdapter();
                vb.addMouseListener(thumb);
                vb.addMouseMotionListener(thumb);
            }
            // 滚轮：往上滚 = 用户要读历史 → 停止跟随（往下的恢复交给"到底就恢复"那条规则）
            WheelAdapter wheel = new WheelAdapter();
            s.addMouseWheelListener(wheel);
            p.addMouseWheelListener(wheel);
            // 键盘翻页（面板拿到焦点时）：往上翻同样停止跟随
            p.addKeyListener(new StickKeyAdapter());
        } catch (Throwable t) {
            // 无界面/受限环境：不抛给调用方，退化为内存记录
            p = null;
            s = null;
            d = null;
        }
        this.pane = p;
        this.scroll = s;
        this.doc = d;
        this.degraded = (d == null);

        setOpaque(false);
        setPreferredSize(DEF_PREF);
        if (s != null) add(s, BorderLayout.CENTER);
    }

    /**
     * 滚动事件（Swing 在 EDT 派发）→ <b>只做一件事</b>：真的到底了就恢复跟随。
     *
     * <p>这里<b>绝不</b>把 {@code stick} 置 false。理由（探针实测踩到的坑）：文档增长本身也会
     * 触发调整事件 —— 面板第一次被显示/布局时，滚动条从 {@code max==ext==0} 变成
     * {@code max=1841, ext=486, val=0}，此时视口"在顶部"，但用户根本没滚过。
     * 早期实现把这个事件当成"用户滚走了"，于是自动滚动在真窗口里<b>一次都没生效</b>
     * （探针 ⑥：val=0 一直不动）。改成非对称规则后：只有"用户输入"能把 stick 置 false
     * （{@link WheelAdapter} 上滚 / {@link ThumbAdapter} 拖拇指 / {@link StickKeyAdapter} 上翻页），
     * 而"落到底部"永远能把 stick 置回 true。</p>
     */
    private final class StickAdapter implements AdjustmentListener {
        @Override
        public void adjustmentValueChanged(AdjustmentEvent e) {
            if (atBottom(e.getAdjustable())) stick = true;
        }
    }

    /** 滚轮：往上滚 = 用户要读历史（停止跟随）；往下滚只交给"到底恢复"那条规则。 */
    private final class WheelAdapter implements MouseWheelListener {
        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            if (e.getWheelRotation() < 0) stick = false;
        }
    }

    /** 拖动滚动条拇指：按下/拖动/松手时，跟随状态 = 那一刻是否真的在底部。 */
    private final class ThumbAdapter extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
            stick = atBottom(adjustableOf(e));
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            stick = atBottom(adjustableOf(e));
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            stick = atBottom(adjustableOf(e));
        }
    }

    /** 键盘上翻（面板拿到焦点时）：停止跟随。往下翻的恢复同样交给"到底恢复"。 */
    private final class StickKeyAdapter extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            int c = e.getKeyCode();
            if (c == KeyEvent.VK_UP || c == KeyEvent.VK_PAGE_UP || c == KeyEvent.VK_HOME) stick = false;
        }
    }

    /** 取事件源对应的可调对象（拿不到就用本面板的竖向滚动条）。 */
    private Adjustable adjustableOf(MouseEvent e) {
        Object s = e == null ? null : e.getSource();
        if (s instanceof Adjustable) return (Adjustable) s;
        return scroll == null ? null : scroll.getVerticalScrollBar();
    }

    /** 是否已在底部（含"还没布局过"= 视为底部）。 */
    static boolean atBottom(Adjustable a) {
        if (a == null) return true;
        int max = a.getMaximum();
        int ext = a.getVisibleAmount();
        if (max <= ext) return true;
        return a.getValue() >= (max - ext - BOTTOM_SLACK);
    }

    // ==================== 追加（任一线程可调；内部同步；不碰 EDT 调度） ====================

    /**
     * 追加一个片元（不自动换行）。片元的字号/颜色/粗斜体由 {@code style} 决定。
     *
     * @param text  文本（null/空 = 什么都不做）
     * @param style 语义样式（null = {@link Style#ASSISTANT}）
     */
    public void append(String text, Style style) {
        if (text == null || text.length() == 0) return;
        final Style st = style == null ? Style.ASSISTANT : style;
        synchronized (lock) {
            if (doc == null) {
                memAppend(text, st);
                return;
            }
            // 贴底判据必须在"真正插入之前"读（插入后滚动条 model 还是旧的，读到的是过期值）。
            // 插入现在发生在 EDT 落盘那一刻，所以这里先把"这一批要不要贴底"记进待办。
            if (autoScroll && stick) pendingFollow = true;
            segs.addLast(Integer.valueOf(text.length()));
            chars += text.length();
            pending.addLast(new Object[] { text, st });
            trimLocked();
            scheduleFlushLocked();
        }
    }

    /** 追加一整行（自动补换行），并结束当前流式段。 */
    public void line(String text, Style style) {
        inStream = false;
        append((text == null ? "" : text) + "\n", style);
    }

    /**
     * 流式增量：把 delta 原样接到文档末尾（<b>不加换行</b>），所以连续调用在视觉上就是
     * "正文一点点长出来"的打字机效果。
     *
     * <p><b>为什么不做人为节流</b>：V3 的 {@code StreamPrinter} 用一条专用线程
     * {@code Thread.sleep(10)} 每字再 {@code SwingUtilities.invokeLater} 打一个字符；
     * V4 的 delta 由 SSE 读取线程直接投递（{@code Sink.stream}），在这里 sleep 会<b>把 SSE 读取
     * 线程按住</b>，还可能把多个回合的输出顺序搅乱。所以节流不做，打字机粒度 = 上游 delta 粒度。
     * 真需要更细的节奏，应在上游（SSE 侧）切分，而不是在这里排队。</p>
     */
    public void streamDelta(String delta) {
        if (delta == null || delta.length() == 0) return;
        inStream = true;
        append(delta, Style.ASSISTANT);
    }

    /** 收尾一段流式输出（补一个换行；不在流式段中则什么都不做）。 */
    public void endStream() {
        if (!inStream) return;
        inStream = false;
        append("\n", Style.ASSISTANT);
    }

    /** 当前是否处于一段未收尾的流式输出中。 */
    public boolean streaming() {
        return inStream;
    }

    /** 清空（账本 + 待办 + 文档；退化路径清内存记录）。 */
    public void clear() {
        synchronized (lock) {
            inStream = false;
            segs.clear();
            chars = 0L;
            pending.clear();
            pendingDrop = 0L;
            pendingFollow = false;
            if (doc == null) {
                memText.clear();
                memStyle.clear();
                return;
            }
            pendingClear = true;
            scheduleFlushLocked();
        }
    }

    // ==================== 落盘（只允许 EDT；无界面时由调用线程直落） ====================

    /** 当前线程是不是 EDT（取不到就当不是）。 */
    private static boolean isEdt() {
        try {
            return javax.swing.SwingUtilities.isEventDispatchThread();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 投递一次落盘（在 {@link #lock} 内调用）。
     * <p>有界面 → {@code invokeLater}（投递失败/无界面 → 本线程直落，宁可直落也不把内容扣在待办里）。</p>
     */
    private void scheduleFlushLocked() {
        if (doc == null || flushScheduled) return;
        flushScheduled = true;
        if (isEdt() || GraphicsEnvironment.isHeadless()) {
            flushNow();
            return;
        }
        try {
            javax.swing.SwingUtilities.invokeLater(flusher);
        } catch (Throwable t) {
            flushNow();
        }
    }

    /**
     * 把待办落到文档：<b>只在 EDT 上跑</b>（无界面的探针里由调用线程直落）。
     * <p>一次落盘做三件事，且顺序固定：①清空（若有）②批量删最旧（{@link #pendingDrop}）③按顺序插入待办片元。
     * 循环到待办排空为止 —— 落盘期间新来的追加会被下一轮带走。</p>
     */
    private void flushNow() {
        while (true) {
            final boolean clear;
            final long drop;
            final boolean follow;
            final List<Object[]> batch;
            synchronized (lock) {
                if (!pendingClear && pendingDrop <= 0L && pending.isEmpty()) {
                    flushScheduled = false;
                    lock.notifyAll();           // 唤醒 flushSync 的等待者
                    return;
                }
                clear = pendingClear;
                pendingClear = false;
                drop = pendingDrop;
                pendingDrop = 0L;
                follow = pendingFollow;
                pendingFollow = false;
                batch = new ArrayList<Object[]>(pending.size());
                while (!pending.isEmpty()) batch.add(pending.pollFirst());
            }
            try {
                // 锁序：先拿 AWTTreeLock，再让 Swing 内部拿文档锁 —— 与框架布局线程（validateTree 持树锁
                // → getPreferredSize 要文档读锁）同序；反过来就是实测过的死锁（EDT 持文档锁等树锁、
                // 布局线程持树锁等文档锁 → 窗口卡死且永不可见）。见 notes/talk-panel.md。
                Object tree = pane == null ? null : pane.getTreeLock();
                if (tree != null) {
                    synchronized (tree) {
                        applyDoc(clear, drop, follow, batch);
                    }
                } else {
                    applyDoc(clear, drop, follow, batch);
                }
            } catch (Throwable t) {
                // 文档已经不可用（被拆/被清）：这一批落到内存记录，绝不抛给调用方
                synchronized (lock) {
                    for (int i = 0; i < batch.size(); i++) {
                        Object[] it = batch.get(i);
                        memAppend((String) it[0], (Style) it[1]);
                    }
                    pending.clear();
                    pendingDrop = 0L;
                    pendingClear = false;
                    // 账与文档已经对不上：以文档实际长度为准（保证"永不超上限"这条不变式）
                    try {
                        chars = doc.getLength();
                    } catch (Throwable ignored) {
                    }
                    flushScheduled = false;
                    lock.notifyAll();
                }
                return;
            }
        }
    }

    /** 真正动文档的那一段（调用方负责先拿好 AWTTreeLock）。 */
    private void applyDoc(boolean clear, long drop, boolean follow, List<Object[]> batch) throws Exception {
        if (clear) doc.remove(0, doc.getLength());
        if (drop > 0L) {
            // 删的量按文档实际长度收紧：单条自己超过上限时账与文档会差一点，宁可少删
            long n = Math.min(drop, doc.getLength());
            while (n > 0L) {
                int k = (int) Math.min(n, Integer.MAX_VALUE);
                doc.remove(0, k);
                n -= k;
            }
        }
        for (int i = 0; i < batch.size(); i++) {
            Object[] it = batch.get(i);
            String s = (String) it[0];
            doc.insertString(doc.getLength(), s, ((Style) it[1]).attrs());
            mirrorPanel(s);
        }
        if (follow && autoScroll && stick) follow1();
    }

    /**
     * 等"待办已经落到文档"（读文档 / 断言前用；{@code text()}/{@code document()} 内部已经会等一次）。
     *
     * @param timeoutMs 最长等多久
     * @return true = 已落完（含本来就没有待办、面板已退化）
     */
    public boolean flushSync(long timeoutMs) {
        if (doc == null) return true;
        if (isEdt() || GraphicsEnvironment.isHeadless()) {
            flushNow();
            return !flushScheduled;
        }
        final long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        synchronized (lock) {
            while (flushScheduled) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0L) return false;
                try {
                    lock.wait(Math.min(left, 25L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    // ==================== 有界缓冲 ====================

    /**
     * 超上限时丢最旧的（在 {@link #lock} 内调用，<b>只动账，不动文档</b>）。
     *
     * <p><b>批删 + 滞回</b>：不是"每超一条就删一条"，而是"一超上限就一次性删到低水位
     * （上限的 3/4）"。原因有二：</p>
     * <ul>
     *   <li>{@code DefaultStyledDocument.remove(0, n)} 每次都要重建元素树并派发文档事件，
     *       单次成本约是 insertString 的 4 倍（探针实测：逐条删 = 1 万条 1613ms）；</li>
     *   <li>文档事件少一个数量级 → 视图更新/重绘的 EDT 队列也少一个数量级。</li>
     * </ul>
     * <p>不变式仍然是"<b>永不超上限</b>"：账本（{@link #segs}/{@link #chars}）当次立刻裁剪，
     * 文档侧的删除记进 {@link #pendingDrop}，由 EDT 落盘时<b>一次</b>做完（见 {@link #flushNow()}）。
     * 单条自己就超过字符上限时不再继续丢 —— 否则面板会变成空白。</p>
     */
    private void trimLocked() {
        if (segs.size() <= maxEntries && chars <= maxChars) return;
        int keepE = Math.max(1, (int) (maxEntries * 3L / 4L));
        int keepC = Math.max(1, (int) (maxChars * 3L / 4L));
        while (!segs.isEmpty() && segs.size() > 1 && (segs.size() > keepE || chars > keepC)) {
            int d = segs.pollFirst().intValue();
            chars -= d;
            // 这一条已经在文档里，还是还在待办里？segs 的尾部恰好是 pending 占的那几条，
            // 所以"抽掉头部之后 segs.size() >= pending.size()"= 抽掉的那条已经在文档里；
            // 否则它还没插进去 —— 那就把它从待办里作废（绝不能去删文档的字符，
            // 否则删掉的是别的行：探针实测过，文档会漂到中段、最新的行反而没进来）。
            if (segs.size() >= pending.size()) pendingDrop += d;
            else pending.pollFirst();
        }
    }

    /** 条数上限（立即生效：调小会立刻裁剪到低水位）。 */
    public void setMaxEntries(int n) {
        if (n <= 0) return;
        maxEntries = n;
        synchronized (lock) {
            if (doc != null) {
                trimLocked();
                scheduleFlushLocked();
            }
        }
    }

    /** 字符数上限（立即生效：调小会立刻裁剪到低水位）。 */
    public void setMaxChars(int n) {
        if (n <= 0) return;
        maxChars = n;
        synchronized (lock) {
            if (doc != null) {
                trimLocked();
                scheduleFlushLocked();
            }
        }
    }

    public int maxEntries() { return maxEntries; }

    public int maxChars() { return maxChars; }

    /** 当前保留的片元条数。 */
    public int entryCount() {
        synchronized (lock) {
            return doc == null ? memText.size() : segs.size();
        }
    }

    /** 当前保留的字符数。 */
    public long charCount() {
        synchronized (lock) {
            if (doc != null) return chars;
            long n = 0L;
            for (int i = 0; i < memText.size(); i++) n += memText.get(i).length();
            return n;
        }
    }

    // ==================== 自动滚动 ====================

    /** 自动滚动总开关（默认开）。 */
    public void setAutoScroll(boolean b) {
        autoScroll = b;
        if (b) stick = true;
    }

    public boolean autoScroll() { return autoScroll; }

    /** 是否处于"贴底"状态（用户上滚后会变 false）。 */
    public boolean sticking() { return stick; }

    /** 立刻拉到最底（用户主动要求时用；不在 EDT 上也能调）。 */
    public void scrollToBottom() {
        stick = true;
        follow1();
    }

    /** 贴底一次（读 Swing 状态；与 {@code ConsFrame.printo0} 同款窄兜底）。 */
    private void follow1() {
        JTextPane p = pane;
        if (p == null) return;
        if (!p.isDisplayable()) return;   // 窗口已关/未显示：不投递重绘任务，避免视图已拆除时的 NPE
        try {
            p.setCaretPosition(p.getDocument().getLength());
        } catch (Throwable ignored) {
            // 取值与定位之间文档被并发裁剪 → 跳过本次贴底
        }
    }

    // ==================== 读 ====================

    /** 当前全文（先等一次落盘，再读文档；退化路径读内存记录）。 */
    public String text() {
        if (doc != null) flushSync(2000L);
        synchronized (lock) {
            if (doc == null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < memText.size(); i++) sb.append(memText.get(i));
                return sb.toString();
            }
            try {
                return doc.getText(0, doc.getLength());
            } catch (Throwable t) {
                return "";
            }
        }
    }

    /** 富文本文档（退化路径返回 null）。<b>读它之前会先等一次落盘</b>，拿到的就是已写入的内容。 */
    public StyledDocument document() {
        flushSync(2000L);
        return doc;
    }

    /** 文本窗格（退化路径返回 null）。 */
    public JTextPane textPane() { return pane; }

    /** 滚动容器（退化路径返回 null）。 */
    public JScrollPane scrollPane() { return scroll; }

    /** 是否已退化到内存记录（无 GUI 环境）。 */
    public boolean degraded() { return degraded; }

    // ==================== 挂载后处理 ====================

    /**
     * 要一块实底（挂载后仍生效）。
     * <p>框架的 {@code ConsFrame.addCanvasTab} 会先对整个子控件树做 {@code transparentTree}
     * （{@code ConsFrame.java:626-636}：把每个 JComponent 的 opaque 置 false，让暗色窗体背景透出）。
     * 想要不透明的对话底就设它，{@link TalkMount#mount} 会在挂载<b>之后</b>重新应用。</p>
     */
    public void setSolidBackground(Color c) {
        solidBg = c;
        applySurface();
    }

    /** 挂载之后再应用一次面板自身的底色（框架 transparentTree 会把我们的设置覆盖掉）。 */
    public void reapplySurface() {
        applySurface();
    }

    private void applySurface() {
        Color c = solidBg;
        if (c == null || pane == null) return;
        try {
            setOpaque(true);
            setBackground(c);
            if (scroll != null) {
                scroll.setOpaque(true);
                scroll.setBackground(c);
                if (scroll.getViewport() != null) {
                    scroll.getViewport().setOpaque(true);
                    scroll.getViewport().setBackground(c);
                }
            }
            pane.setOpaque(true);
            pane.setBackground(c);
        } catch (Throwable ignored) {
        }
    }

    // ==================== 退化路径 ====================

    /** 文档不可用时的内存落点（仍受上限约束，仍不抛异常）。 */
    private void memAppend(String text, Style st) {
        memText.add(text);
        memStyle.add(st);
        while (memText.size() > maxEntries) {
            memText.remove(0);
            memStyle.remove(0);
        }
    }

    /** 诊断侧写（只在 {@code -Dv4.console.dump=<文件>} 时生效）：面板正文按落盘顺序镜像到 {@code <文件>.panel}。 */
    private static void mirrorPanel(String text) {
        if (DUMP_PATH.isEmpty() || text == null || text.isEmpty()) return;
        try {
            synchronized (MIRROR_LOCK) {
                if (MIRROR == null) MIRROR = new java.io.FileWriter(DUMP_PATH + ".panel", false);
                MIRROR.write(text);
                MIRROR.flush();
            }
        } catch (Throwable ignored) {
        }
    }

    private static final String DUMP_PATH = System.getProperty("v4.console.dump", "");
    private static final Object MIRROR_LOCK = new Object();
    private static java.io.FileWriter MIRROR;

    /** 退化路径下每条的样式（探针断言用；真路径请读文档属性）。 */
    public Style memStyleAt(int i) {
        synchronized (lock) {
            return (i >= 0 && i < memStyle.size()) ? memStyle.get(i) : null;
        }
    }
}
