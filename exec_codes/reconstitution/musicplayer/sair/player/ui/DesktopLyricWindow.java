package sair.player.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JPanel;
import javax.swing.JWindow;

import sair.FCM;

/**
 * 桌面歌词浮动窗口 —— 透明背景、始终置顶、可拖拽、双行显示。
 * 
 * <h4>窗口特性</h4>
 * <ul>
 *   <li><b>完全透明背景</b>：{@code setBackground(new Color(0,0,0,0))} + 
 *       {@code setOpaque(false)}，只绘制文字，无窗口装饰</li>
 *   <li><b>始终置顶</b>：{@code setAlwaysOnTop(true)}，歌词始终浮于其他窗口之上</li>
 *   <li><b>可拖拽</b>：通过鼠标监听实现窗口拖动（见 {@link LyricPanel}）</li>
 *   <li><b>JWindow 而不是 JFrame</b>：JWindow 无标题栏和边框，适合做悬浮层</li>
 * </ul>
 * 
 * <h4>显示模式</h4>
 * <pre>
 *   ┌─────────────────────────────────────┐
 *   │     当前行（大字 30pt 粗体 + 阴影）   │
 *   │                                     │
 *   │     下一行（小字 17pt 普通 + 淡影）  │
 *   └─────────────────────────────────────┘
 * </pre>
 * 
 * <h4>窗口位置</h4>
 * 初始位置：屏幕水平居中，底部距离屏幕下边缘 80px。
 * 窗口大小：700 x 130 px。
 * 
 * <h4>线程安全</h4>
 * 由于 Swing 要求 UI 操作在 EDT 线程执行，本窗口的方法（{@code setLyrics} / {@code clear}）
 * 应在 {@link FlushDesktopLRC} 的 Swing 定时器回调中调用，自然处于 EDT 线程。
 * 
 * <h4>颜色同步</h4>
 * 每次 {@code paintComponent} 时从 {@link FCM} 动态读取主题色，
 * 而非在构造时缓存，保证主题切换后颜色即时生效。
 * 
 * @author SairFramework
 */
public class DesktopLyricWindow extends JWindow {

	private static final long serialVersionUID = -7903451919045173234L;

	/** 歌词绘制面板（内部类，负责渲染和拖拽） */
	private final LyricPanel lyricPanel;

	/**
	 * 构造 —— 创建透明置顶窗口，初始位置在屏幕底部居中。
	 * 
	 * <ul>
	 *   <li>窗口大小：700 x 130 px</li>
	 *   <li>初始位置：水平居中，底部距屏幕边缘 80px</li>
	 *   <li>使用 {@link Toolkit} 获取屏幕尺寸以兼容多显示器</li>
	 * </ul>
	 */
	public DesktopLyricWindow() {
		// 完全透明背景（alpha=0）
		setBackground(new Color(0, 0, 0, 0));

		lyricPanel = new LyricPanel();
		setContentPane(lyricPanel);

		// 始终置顶于其他窗口
		setAlwaysOnTop(true);

		// 计算初始位置：屏幕底部居中
		Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
		int w = 700, h = 130;
		setSize(w, h);
		setLocation((screenSize.width - w) / 2, screenSize.height - h - 80);
	}

	/**
	 * 设置当前和下一句歌词文本，触发重绘。
	 * 
	 * <p>应在 Swing 定时器（{@code javax.swing.Timer}）回调中调用，
	 * 以确保在 EDT 线程执行。</p>
	 * 
	 * @param current 当前演唱的歌词行（大字显示）
	 * @param next    下一句歌词（小字预览显示）
	 */
	public void setLyrics(String current, String next) {
		lyricPanel.setLyrics(current, next);
	}

	/**
	 * 清空歌词并销毁窗口。
	 * 
	 * <p>{@link JWindow#dispose()} 释放窗口的本地资源，
	 * 调用后此窗口不可再用。需要重新 {@code new DesktopLyricWindow()}。</p>
	 */
	public void clear() {
		lyricPanel.setLyrics("", "");
		dispose();
	}

	// ========== 歌词绘制面板 ==========

	/**
	 * 歌词绘制面板 —— 负责双行歌词渲染和鼠标拖拽。
	 * 
	 * <h4>绘制逻辑</h4>
	 * <ol>
	 *   <li><b>当前行</b>：30pt 粗体微软雅黑，先绘制黑色半透明阴影（偏移 2px），再绘制 FCM 主色文字</li>
	 *   <li><b>下一行</b>：17pt 普通微软雅黑，先绘制淡阴影（偏移 1px），再绘制 FCM 副色半透明文字</li>
	 *   <li>每行文字水平居中，若文字过长则限制最小左边距 10px</li>
	 * </ol>
	 * 
	 * <h4>拖拽实现</h4>
	 * {@code mousePressed} 记录起始点，{@code mouseDragged} 计算偏移量后更新
	 * {@link DesktopLyricWindow#setLocation(int, int)}。
	 * 
	 * <p>这是一个非静态内部类，可以直接访问外层 {@link DesktopLyricWindow}.this 的
	 * {@code setLocation} 和 {@code getLocation} 方法。</p>
	 */
	private class LyricPanel extends JPanel {

		private static final long serialVersionUID = 1482940745927215679L;

		/** 当前演唱的歌词文本 */
		private String currentLyric = "";
		/** 下一句歌词文本 */
		private String nextLyric = "";
		/** 拖拽起始点（相对于面板的坐标） */
		private Point dragStart;

		public LyricPanel() {
			setOpaque(false);  // 透明背景

			// 记录拖拽起始点
			addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					dragStart = e.getPoint();
				}
			});

			// 计算偏移并更新窗口位置
			addMouseMotionListener(new MouseAdapter() {
				@Override
				public void mouseDragged(MouseEvent e) {
					// 获取 JWindow 的屏幕坐标位置
					Point winLoc = DesktopLyricWindow.this.getLocation();
					// 新位置 = 原窗口位置 + 鼠标位移
					DesktopLyricWindow.this.setLocation(
							winLoc.x + e.getX() - dragStart.x,
							winLoc.y + e.getY() - dragStart.y);
				}
			});
		}

		/**
		 * 设置歌词文本并触发重绘。
		 * 
		 * @param current 当前行（null 转为空字符串）
		 * @param next    下一行（null 转为空字符串）
		 */
		public void setLyrics(String current, String next) {
			this.currentLyric = current != null ? current : "";
			this.nextLyric = next != null ? next : "";
			repaint();  // 触发 paintComponent 重新绘制
		}

		/**
		 * 绘制双行歌词。
		 * 
		 * <h4>渲染设置</h4>
		 * 开启抗锯齿（{@code VALUE_ANTIALIAS_ON}）和文本抗锯齿
		 * （{@code VALUE_TEXT_ANTIALIAS_LCD_HRGB}，适合 LCD 屏幕的亚像素渲染）。
		 * 
		 * <h4>颜色说明</h4>
		 * <ul>
		 *   <li><b>主色</b>：{@code EXECTION_pathInfo_Color} —— 当前歌词行</li>
		 *   <li><b>副色</b>：{@code EXECTION_help_Color} —— 下一行歌词（alpha=180 半透明）</li>
		 * </ul>
		 * 
		 * <h4>文本位置</h4>
		 * <ul>
		 *   <li>当前行 Y：面板高度/2 - 12（略偏上，给阴影留空间）</li>
		 *   <li>下一行 Y：面板高度/2 + 34（下方 46px 偏移，与当前行有视觉间距）</li>
		 *   <li>X：水平居中，但最小 10px 左边距防止溢出</li>
		 * </ul>
		 */
		@Override
		protected void paintComponent(Graphics g) {
			super.paintComponent(g);

			Graphics2D g2d = (Graphics2D) g;
			// 抗锯齿设置 — 曲线和文字平滑
			g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			// LCD 亚像素文本渲染 — 更清晰的文字边缘
			g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);

			int w = getWidth();
			int h = getHeight();

			// 动态从 FCM 获取当前主题色（每次绘制时读取，支持运行时主题切换）
			Color mainColor = FCM.EXECTION_pathInfo_Color;
			Color subColor = FCM.EXECTION_help_Color;

			// --- 当前行：大字 30pt 粗体 + 黑色阴影 + FCM 主色 ---
			if (!currentLyric.isEmpty()) {
				Font curFont = new Font("Microsoft YaHei", Font.BOLD, 30);
				g2d.setFont(curFont);
				FontMetrics fm = g2d.getFontMetrics();
				int tw = fm.stringWidth(currentLyric);  // 文字像素宽度
				int x = Math.max(10, (w - tw) / 2);      // 居中，防止左溢出
				int y = h / 2 - 12;                       // 垂直居中偏上

				// 阴影：黑色半透明，偏移 2px
				g2d.setColor(new Color(0, 0, 0, 140));
				g2d.drawString(currentLyric, x + 2, y + 2);
				// 主体：FCM 主色
				g2d.setColor(mainColor);
				g2d.drawString(currentLyric, x, y);
			}

			// --- 下一行：小字 17pt 普通 + 淡阴影 + FCM 副色半透明 ---
			if (!nextLyric.isEmpty()) {
				Font nextFont = new Font("Microsoft YaHei", Font.PLAIN, 17);
				g2d.setFont(nextFont);
				FontMetrics fm = g2d.getFontMetrics();
				int tw = fm.stringWidth(nextLyric);
				int x = Math.max(10, (w - tw) / 2);      // 居中
				int y = h / 2 + 34;                       // 当前行下方

				// 淡阴影：黑色半透明，偏移 1px
				g2d.setColor(new Color(0, 0, 0, 100));
				g2d.drawString(nextLyric, x + 1, y + 1);
				// 主体：FCM 副色 + alpha=180 半透明，与当前行形成层次感
				g2d.setColor(new Color(
						subColor.getRed(), subColor.getGreen(), subColor.getBlue(), 180));
				g2d.drawString(nextLyric, x, y);
			}
		}
	}
}
