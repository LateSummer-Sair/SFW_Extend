package sair.v4.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;

import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;

/**
 * 让本地交流面板"长得像 SFW 的一部分"：字体、配色、滚动条都用框架那一套。
 *
 * <h3>为什么要单独一个类</h3>
 * <p>{@link TalkPanel} 刻意<b>不依赖 {@code sair.sys} 任何类型</b>（探针可以在没有 SFW 的 JVM 里直接跑它）。
 * 所以"跟框架对齐"这件事只在这里做，而且只在真挂载之后做（{@link TalkMount#mount} 调它）——
 * 探针里的面板永远是"素"的，不受影响。</p>
 *
 * <h3>对齐了什么（都取框架的活值，不抄常量）</h3>
 * <ul>
 *   <li><b>字体族</b>：从控制台正文窗格 {@code ConsFrame.cf.infoPane.getFont()} 取族名，
 *       交给 {@link TalkPanel#useFontFamily(String)} —— 字号层级仍是我们自己的语义字号
 *       （正文 16 / 主人 15 / 小字 12…），只统一族名；</li>
 *   <li><b>滚动条</b>：换成框架自绘的 {@code sair.sys.gui.swing.control.SairScrollBarUI}
 *       （控制台/列表用的同一支），三色用控制台的默认前景色 —— 与框架
 *       {@code ConsFrame.reinit_Color()} 里 {@code new SairScrollBarUI(otC, otC, otC)} 同一写法；</li>
 *   <li><b>光标与选区</b>：直接抄控制台正文窗格的 {@code getCaretColor/getSelectionColor/
 *       getSelectedTextColor}；</li>
 *   <li><b>背景</b>：不动（面板默认透明，透出的就是控制台底色，本来就是统一的）。</li>
 * </ul>
 *
 * <p>框架的字号是"按窗口尺寸算"的（{@code ConsFrame.reinitFont()}），窗口一改尺寸就会重刷；
 * 所以这里挂了一个监听：窗口尺寸变化 → 面板被重排 → 重新取一次框架字体族，跟着走。</p>
 *
 * <p><b>失败一律静默</b>：这个类只影响观感，任何一步取不到（无框架、无窗格）就跳过，
 * 绝不把异常抛给挂载流程。</p>
 */
public final class SfwStyle {

    private SfwStyle() {
    }

    /** 是否已经应用过（诊断）。 */
    private static volatile boolean applied = false;
    /** 最近一次失败原因（诊断；成功时为空串）。 */
    private static volatile String lastError = "";
    /** 最近一次用到的族名（诊断）。 */
    private static volatile String family = "";

    public static boolean applied() { return applied; }

    public static String lastError() { return lastError; }

    public static String family() { return family; }

    /**
     * 把面板对齐到框架样式（挂载之后调；重复调是幂等的）。
     *
     * @return true = 至少应用了字体族或滚动条
     */
    public static boolean apply(final TalkPanel panel) {
        if (panel == null) return false;
        boolean any = false;
        try {
            Font f = consoleFont();
            if (f != null) {
                String fam = f.getFamily();
                if (TalkPanel.useFontFamily(fam)) {
                    family = fam;
                    any = true;
                }
                // 空的编辑框（还没有内容）也要跟框架字体一致，不然"等待第一条消息"时大小不一
                JTextPane pane = panel.textPane();
                if (pane != null) {
                    pane.setFont(f);
                    Color caret = consoleColor(true);
                    if (caret != null) pane.setCaretColor(caret);
                    Color sel = consoleColor(false);
                    if (sel != null) pane.setSelectionColor(sel);
                    // 光标也用框架那支：ConsFrame.SairCaret（DefaultCaret 子类，ALWAYS_UPDATE 贴底策略，
                    // 并屏蔽"关窗时向已拆除视图投递重绘"那个 NPE）——与控制台正文窗格同一支
                    try {
                        pane.setCaret(new ConsFrame.SairCaret());
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        }
        try {
            if (applyScrollBar(panel)) any = true;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
        }
        try {
            watchResize(panel);
        } catch (Throwable ignored) {
        }
        // 换了 caret 会把视口带回顶部：补一次贴底，保持"新内容在底部"的观感
        try {
            panel.scrollToBottom();
        } catch (Throwable ignored) {
        }
        if (any) applied = true;
        return any;
    }

    /** 滚动条换成框架自绘那支（与控制台/列表同一支）。 */
    private static boolean applyScrollBar(TalkPanel panel) {
        JScrollPane sp = panel.scrollPane();
        if (sp == null) return false;
        Color c = consoleColor(false);
        if (c == null) c = Color.DARK_GRAY;
        boolean any = false;
        JScrollBar v = sp.getVerticalScrollBar();
        if (v != null) {
            v.setUI(new sair.sys.gui.swing.control.SairScrollBarUI(c, c, c));
            v.setOpaque(false);
            v.setUnitIncrement(fontSize(12));
            any = true;
        }
        JScrollBar h = sp.getHorizontalScrollBar();
        if (h != null && sp.getHorizontalScrollBarPolicy() != JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            h.setUI(new sair.sys.gui.swing.control.SairScrollBarUI(c, c, c));
            h.setOpaque(false);
            any = true;
        }
        return any;
    }

    /** 框架字体是"按窗口尺寸算"的：窗口尺寸一变就重取一次族名，跟着框架走。 */
    private static void watchResize(final TalkPanel panel) {
        if (panel.getClientProperty("v4.style.watch") != null) return;
        panel.putClientProperty("v4.style.watch", Boolean.TRUE);
        panel.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                try {
                    Font f = consoleFont();
                    if (f != null) TalkPanel.useFontFamily(f.getFamily());
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 控制台正文窗格的活字体（取不到返回 null）。 */
    private static Font consoleFont() {
        try {
            JTextPane pane = ConsFrame.cf == null ? null : ConsFrame.getTextPane();
            Font f = pane == null ? null : pane.getFont();
            return f != null ? f : ConsFrame.font;
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            return null;
        }
    }

    /** 颜色：取控制台正文窗格的（{@code caret=true} 要光标色，否则要选区色），失败退回控制台默认前景色。 */
    private static Color consoleColor(boolean caret) {
        try {
            JTextPane pane = ConsFrame.cf == null ? null : ConsFrame.getTextPane();
            if (pane != null) {
                Color c = caret ? pane.getCaretColor() : pane.getSelectionColor();
                if (c != null) return c;
            }
        } catch (Throwable ignored) {
        }
        try {
            return SairCons.getDefaultColor();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 字号 → 滚动步进（框架字体大小的一档；取不到给 14）。 */
    private static int fontSize(int def) {
        try {
            Font f = consoleFont();
            return f == null ? def : Math.max(10, f.getSize());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 便于探针断言"确实换过 UI"：返回滚动条 UI 的类名（没挂上返回空串）。 */
    public static String scrollBarUiName(TalkPanel panel) {
        try {
            JScrollPane sp = panel == null ? null : panel.scrollPane();
            JScrollBar v = sp == null ? null : sp.getVerticalScrollBar();
            javax.swing.plaf.ScrollBarUI ui = v == null ? null : v.getUI();
            return ui == null ? "" : ui.getClass().getName();
        } catch (Throwable t) {
            return "";
        }
    }
}
