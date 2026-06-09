package sair.player.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;

import sair.FCM;
import sair.player.PlayerActivity;
import sair.player.audio.EQProcessor;
import sair.player.audio.VolumeController;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SairScrollBarUI;

/**
 * 主界面布局管理器 —— 负责构建播放器的三列式界面布局。
 * 
 * <h4>整体布局（{@link #reinit()} / {@link #chkInit(JList, JList, JList)}）</h4>
 * <pre>
 *   ┌─────────────────────────────────────────────┐
 *   │  BorderLayout.CENTER: jspLRC (歌词面板)      │
 *   │  BorderLayout.WEST:   jsp   (歌曲列表)      │
 *   │  BorderLayout.SOUTH:  jsb   (进度条)        │
 *   └─────────────────────────────────────────────┘
 * </pre>
 * 
 * <h4>面板尺寸计算</h4>
 * <ul>
 *   <li><b>歌曲列表</b>：宽度 = 窗口宽度/3，高度 = 窗口高度*0.8</li>
 *   <li><b>歌词面板</b>：宽度 = 窗口宽度/2，高度 = 窗口高度*0.8</li>
 *   <li>歌词面板宽度更大，因为它是主视图（CENTER），但实际视觉效果由 BorderLayout 决定</li>
 * </ul>
 * 
 * <h4>关键设计</h4>
 * <ul>
 *   <li><b>单例缓存模式</b>：EQ 面板（{@code eqPanel}）和高级音效面板（{@code advancedEffectsPanel}）
 *       仅在首次打开时创建，后续复用。避免重复创建浪费资源</li>
 *   <li><b>处理器引用更新</b>：切换歌曲时通过 {@link #updateEQPanelProcessors()} 
 *       将新的 EQ/音量处理器实例注入到已打开的 EQ 面板中，确保滑块同步</li>
 *   <li><b>一次性初始化</b>：{@code isInit} 标志确保复杂的 Swing 组件布局代码只执行一次，
 *       避免重复添加组件导致布局异常</li>
 *   <li><b>FCM 主题色同步</b>：{@code reinit()} 在每次 {@code toPrint()} 时调用，
 *       将当前 FCM 主题色应用到所有组件，支持运行时主题切换</li>
 * </ul>
 * 
 * <h4>颜色体系</h4>
 * <ul>
 *   <li>{@code EXECTION_pathInfo_Color}：主色（标签文字、选中前景色）</li>
 *   <li>{@code EXECTION_help_Color}：副色（边框、普通文字）</li>
 *   <li>滚动条使用三色渐变：普通/加载中/悬停</li>
 * </ul>
 * 
 * @author SairFramework
 */
public class ConsUI {
	/** 主 Activity 引用——用于获取播放器、列表、歌词等组件 */
	private PlayerActivity o;
	/** 列表区域的根容器，采用 BorderLayout 三列布局 */
	private JPanel listPane = new JPanel();
	/** 歌曲列表滚动面板（WEST 位置） */
	private JScrollPane jsp = new JScrollPane();
	/** 歌词列表滚动面板（CENTER 位置） */
	private JScrollPane jspLRC = new JScrollPane();
	/** 歌曲列表上方的提示标签："双击列表 : 左键播放/右键移除" */
	private JLabel lab = new JLabel("双击列表 : 左键播放/右键移除  ");
	/** 歌词面板上方的标题标签前缀 */
	public final String lrcShowInfo = "歌词显示 : ";
	/** 歌词面板上方标题标签 */
	private JLabel labLRC = new JLabel(lrcShowInfo);
	/** 布局是否已完成一次性初始化（{@link #chkInit} 只执行一次） */
	private boolean isInit = false;
	/** 底部进度条 */
	private JProgressBar jsb = new JProgressBar();
	/** EQ 面板单例（null = 未打开，非 null = 已创建并缓存） */
	private EQPanel eqPanel = null;
	/** 高级音效面板单例（null = 未打开，非 null = 已创建并缓存） */
	private AdvancedEffectsPanel advancedEffectsPanel = null;

	/**
	 * 构造 —— 仅初始化进度条的基本属性，不构建 UI。
	 * 实际的 UI 构建在 {@link #toPrint()} → {@link #reinit()} → {@link #chkInit(JList, JList, JList)} 中完成。
	 * 
	 * @param o 主 Activity 引用
	 */
	public ConsUI(PlayerActivity o) {
		jsb.setOpaque(false);   // 进度条背景透明
		jsb.setMinimum(0);
		this.o = o;
	}

	/**
	 * 获取进度条组件 —— 提供给 {@link FlushJSBThread} 更新播放进度。
	 * 
	 * @return JProgressBar 实例
	 */
	public JProgressBar getJSB() {
		return jsb;
	}

	/**
	 * 获取歌曲列表的滚动面板 —— 供外部获取其垂直滚动条来同步歌词滚动。
	 * 
	 * @return 歌曲列表 JScrollPane
	 */
	public JScrollPane getUIListPane() {
		return jsp;
	}

	/**
	 * 输出 UI 到控制台框架。
	 * 
	 * <h4>调用链</h4>
	 * <ol>
	 *   <li>{@link #reinit()} —— 刷新所有组件的颜色和尺寸（每次调用都会执行）</li>
	 *   <li>{@code ConsFrame.printComponent(listPane)} —— 将根容器渲染到终端</li>
	 * </ol>
	 * 
	 * <p>注意：{@code reinit()} 的异常被静默捕获，因为这是底层框架渲染层次的问题，
	 * 不应阻断正常播放流程。</p>
	 */
	public void toPrint() {
		try {
			reinit(); // 重新应用颜色和尺寸
		} catch (InstantiationException | IllegalAccessException | NoSuchFieldException | SecurityException
				| NoSuchMethodException | IllegalArgumentException | InvocationTargetException e) {
			SairCons.print(FCM.Error_Color, "底层框架重绘出错!");
		}
		ConsFrame.printComponent(listPane);
	}

	/**
	 * 显示 EQ 均衡器面板。
	 * 
	 * <h4>懒加载单例模式</h4>
	 * <ol>
	 *   <li>首次调用：检查 {@code eqPanel == null}，创建新 {@link EQPanel} 实例，
	 *       注入 {@link EQProcessor} 和 {@link VolumeController}，并设置 {@code PlayerActions} 引用</li>
	 *   <li>后续调用：直接复用已缓存的 {@code eqPanel}，避免重复创建</li>
	 *   <li>若 EQ 处理器尚未初始化（歌曲未播放），打印提示信息</li>
	 * </ol>
	 * 
	 * <p>歌曲切换时需要通过 {@link #updateEQPanelProcessors()} 刷新处理器引用，
	 * 否则 EQ 面板操作的是旧处理器实例。</p>
	 */
	public void showEQPanel() {
		if (eqPanel == null) {
			EQProcessor eq = o.getPA().getEQProcessor();
			VolumeController vc = o.getPA().getVolumeController();
			if (eq != null && vc != null) {
				eqPanel = new EQPanel(eq, vc);
				// 设置 PlayerActions 引用，以便保存/加载配置
				eqPanel.setPlayerActions(o.getPA());
			}
		}

		if (eqPanel != null) {
			ConsFrame.printComponent(eqPanel);
		} else {
			SairCons.println("请先播放一首歌曲后再打开均衡器面板");
		}
	}

	/**
	 * 更新 EQ 面板的处理器引用（切换歌曲时调用）。
	 * 
	 * <h4>为什么需要</h4>
	 * 每次播放新歌曲时，{@link sair.player.acts.PlayerActions} 会创建新的 {@link EQProcessor}
	 * 和 {@link VolumeController} 实例。但 {@code eqPanel} 是单例缓存的，
	 * 如果不更新引用，面板操作将作用于旧的（已被替换的）处理器，导致音效无效。
	 * 
	 * <p>该方法通过 {@link EQPanel#updateProcessors(EQProcessor, VolumeController)}
	 * 将新处理器注入，同时同步滑块位置到新处理器的当前设置。</p>
	 */
	public void updateEQPanelProcessors() {
		if (eqPanel != null) {
			EQProcessor eq = o.getPA().getEQProcessor();
			VolumeController vc = o.getPA().getVolumeController();
			if (eq != null && vc != null) {
				// 更新 EQPanel 内部的处理器引用
				eqPanel.updateProcessors(eq, vc);
			}
		}
	}

	/**
	 * 显示高级音效面板（环绕声 + 声场扩展 + Viper 效果）。
	 * 
	 * <h4>懒加载单例模式</h4>
	 * <ol>
	 *   <li>首次调用：获取音频格式 → 初始化高级音效处理器 → 创建面板 → 缓存</li>
	 *   <li>后续调用：直接复用已缓存的面板</li>
	 * </ol>
	 * 
	 * <h4>音频格式获取优先级</h4>
	 * <ol>
	 *   <li>从当前播放器获取实际格式（{@code getPlayer().getAudioFormat()}）</li>
	 *   <li>播放器未初始化时，回退到默认格式（44100Hz, 16bit, 立体声, 小端序）</li>
	 * </ol>
	 * 
	 * <p>注意：与 EQ 面板不同，高级效果面板没有提供 {@code updateXxxProcessors} 方法，
	 * 因为高级效果处理器在 {@link sair.player.acts.PlayerActions} 中是全局单例，
	 * 切换歌曲不会重新创建。</p>
	 */
	public void showAdvancedEffectsPanel() {
		if (advancedEffectsPanel == null) {
			// 初始化高级音效处理器
			javax.sound.sampled.AudioFormat format = null;
			if (o.getPA().getPlayer() != null) {
				format = o.getPA().getPlayer().getAudioFormat();
			}

			if (format == null) {
				// 使用默认格式
				format = new javax.sound.sampled.AudioFormat(44100, 16, 2, true, false);
			}

			o.getPA().initAdvancedEffects(format);

			advancedEffectsPanel = new AdvancedEffectsPanel(
				o.getPA().getSurroundProcessor(),
				o.getPA().getSoundstageExpander(),
				o.getPA().getViperProcessor()
			);

			// 设置 PlayerActions 引用，以便保存/加载配置
			advancedEffectsPanel.setPlayerActions(o.getPA());
		}

		ConsFrame.printComponent(advancedEffectsPanel);
	}

	/**
	 * 重新初始化 UI 颜色和尺寸（每次 {@link #toPrint()} 都会调用）。
	 * 
	 * <h4>执行内容</h4>
	 * <ol>
	 *   <li>设置各 JList 的前景色、选中前景色（FCM 主题色）</li>
	 *   <li>设置标签的文字颜色和字体</li>
	 *   <li>设置滚动条的自定义 UI 样式（{@link SairScrollBarUI}）</li>
	 *   <li>计算并设置面板尺寸（基于窗口大小比例）</li>
	 *   <li>调用 {@link #chkInit(JList, JList, JList)} 完成一次性布局</li>
	 * </ol>
	 * 
	 * <h4>尺寸比例</h4>
	 * <ul>
	 *   <li>歌曲列表：宽 = 窗口宽/3，高 = 窗口高*0.8</li>
	 *   <li>歌词面板：宽 = 窗口宽/2，高 = 窗口高*0.8</li>
	 * </ul>
	 * 
	 * @throws 反射异常 —— 来自底层框架的颜色/组件属性设置（静默处理）
	 */
	private void reinit() throws InstantiationException, IllegalAccessException, NoSuchFieldException,
			SecurityException, NoSuchMethodException, IllegalArgumentException, InvocationTargetException {
		if (o == null)
			return;

		// --- 获取三个核心列表组件 ---
		JList<String> lrc = o.getLrcListC();     // 歌词列表
		JList<String> list = o.getListC();        // 歌曲列表
		JList<String> listh = o.getListCHead();   // 歌曲列表行头（序号列）

		// --- 歌词列表颜色 ---
		lrc.setForeground(FCM.EXECTION_help_Color);
		lrc.setSelectionForeground(FCM.EXECTION_pathInfo_Color);

		// --- 歌曲列表颜色和边框 ---
		list.setForeground(FCM.EXECTION_help_Color);
		list.setSelectionForeground(FCM.EXECTION_pathInfo_Color);
		list.setBorder(new SBorder(FCM.EXECTION_help_Color));

		// --- 行头颜色和边框 ---
		listh.setForeground(FCM.EXECTION_pathInfo_Color);
		listh.setBorder(new SBorder(FCM.EXECTION_help_Color));
		listh.setSelectionForeground(FCM.EXECTION_pathInfo_Color);

		// --- 标签样式（提示文字和歌词标题）---
		lab.setForeground(FCM.EXECTION_pathInfo_Color);
		lab.setFont(ConsFrame.getTextPane().getFont());
		lab.setBorder(new SBorder(FCM.EXECTION_help_Color));

		labLRC.setForeground(FCM.EXECTION_pathInfo_Color);
		labLRC.setFont(ConsFrame.getTextPane().getFont());
		labLRC.setBorder(new SBorder(FCM.EXECTION_help_Color));

		// --- 歌曲列表滚动条样式（三色：普通/加载/悬停）---
		JScrollBar hsb = jsp.getHorizontalScrollBar(), vsb = jsp.getVerticalScrollBar();
		hsb.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
		vsb.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
		vsb.setOpaque(false);
		hsb.setOpaque(false);

		// --- 歌词面板滚动条样式 ---
		JScrollBar hsbl = jspLRC.getHorizontalScrollBar(), vsbl = jspLRC.getVerticalScrollBar();
		hsbl.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
		vsbl.setUI(new SairScrollBarUI(FCM.EXECTION_help_Color, FCM.loadExection_Color, FCM.EXECTION_help_Color));
		vsbl.setOpaque(false);
		hsbl.setOpaque(false);

		// --- 歌曲列表面板尺寸：宽=窗口/3，高=窗口*0.8 ---
		float listhi = ((float) ConsFrame.cf.getHeight()) / 10f * 8f;
		float listwi = ConsFrame.cf.getWidth() / 3;
		Dimension listsize = new Dimension((int) listwi, (int) listhi);
		jsp.setBorder(new SBorder(FCM.EXECTION_help_Color));
		jsp.setPreferredSize(listsize);

		// --- 歌词面板尺寸：宽=窗口/2，高=窗口*0.8 ---
		float lrchi = ((float) ConsFrame.cf.getHeight()) / 10f * 8f;
		float lrcwi = ConsFrame.cf.getWidth() / 2;
		Dimension lrcsize = new Dimension((int) lrcwi, (int) lrchi);

		jspLRC.setBorder(new SBorder(FCM.EXECTION_help_Color));
		jspLRC.setPreferredSize(lrcsize);

		// 一次性初始化布局（仅首次执行）
		chkInit(list, lrc, listh);
	}

	/**
	 * 一次性初始化布局 —— 仅在首次 UI 渲染时执行。
	 * 
	 * <h4>布局结构</h4>
	 * <pre>
	 *   listPane (BorderLayout, 透明背景)
	 *   ├── CENTER: jspLRC (歌词面板)
	 *   │   ├── 列头: labLRC ("歌词显示 : ...")
	 *   │   └── 视口: lrc (JList，居中渲染)
	 *   ├── WEST:   jsp (歌曲列表)
	 *   │   ├── 列头: lab ("双击列表 : ...")
	 *   │   ├── 行头: listh (序号列)
	 *   │   └── 视口: list (歌曲名 JList)
	 *   └── SOUTH:  jsb (进度条)
	 * </pre>
	 * 
	 * <h4>透明背景策略</h4>
	 * 所有组件均设置为 {@code setOpaque(false)}，配合终端渲染框架实现透明效果。
	 * 选中背景色设为 {@code (0,0,0,0)} 完全透明，选中项通过前景色变化（{@code pathInfo_Color}）来区分。
	 * 
	 * <h4>单元格高度</h4>
	 * 每个列表行高度 = 字体大小 + 10px（{@code font.getSize2D() + 10}），
	 * 确保行之间有足够的视觉间距。
	 * 
	 * @param list  歌曲列表（WEST 视口）
	 * @param lrc   歌词列表（CENTER 视口）
	 * @param listh 行头列表（序号）
	 */
	private void chkInit(JList<String> list, JList<String> lrc, JList<String> listh) {
		if (!isInit) {
			// --- 根容器设置 ---
			listPane.setLayout(new BorderLayout());
			listPane.setOpaque(false);
			jsp.setOpaque(false);
			jspLRC.setOpaque(false);

			// --- 行头（序号列）样式 ---
			listh.setOpaque(false);
			listh.setSelectionBackground(new Color(0, 0, 0, 0));  // 选中背景透明
			listh.setFont(ConsFrame.font);
			listh.setFixedCellHeight((int) ConsFrame.font.getSize2D() + 10);
			((JComponent) listh.getCellRenderer()).setOpaque(false);

			// --- 歌曲列表样式 ---
			list.setFont(ConsFrame.font);
			list.setOpaque(false);
			list.setFixedCellHeight((int) ConsFrame.font.getSize2D() + 10);
			list.setSelectionBackground(new Color(0, 0, 0, 0));   // 选中背景透明
			((JComponent) list.getCellRenderer()).setOpaque(false);
			list.addMouseListener(new ListClick(list, o));        // 注册双击/右键监听

			// --- 歌词列表样式 ---
			lrc.setFont(ConsFrame.font);
			lrc.setOpaque(false);
			lrc.setFixedCellHeight((int) ConsFrame.font.getSize2D() + 10);
			lrc.setSelectionBackground(new Color(0, 0, 0, 0));   // 选中背景透明

			// 歌词居中渲染器
			LRCListCellRenderer lrcRen = new LRCListCellRenderer();
			lrcRen.setHorizontalAlignment(SwingConstants.CENTER);
			lrc.setCellRenderer(lrcRen);

			// --- 组装层级 ---
			// jsp: 歌曲列表 → 行头=序号, 列头=提示标签, 视口=歌名列表
			jsp.setViewportView(list);
			jsp.setRowHeaderView(jspLRC);
			jsp.setColumnHeaderView(lab);
			jsp.getColumnHeader().setOpaque(false);
			jsp.getViewport().setOpaque(false);
			jsp.setRowHeaderView(listh);
			jsp.getRowHeader().setOpaque(false);

			// jspLRC: 歌词面板 → 列头=歌词标题, 视口=歌词列表
			jspLRC.setViewportView(lrc);
			jspLRC.setColumnHeaderView(labLRC);
			jspLRC.getColumnHeader().setOpaque(false);
			jspLRC.getViewport().setOpaque(false);

			// 最终布局：歌词居中, 列表左侧, 进度条底部
			listPane.add(jspLRC, BorderLayout.CENTER);
			listPane.add(jsp, BorderLayout.WEST);
			listPane.add(jsb, BorderLayout.SOUTH);
			jsb.setBorder(new SBorder(FCM.EXECTION_help_Color));
		}
		isInit = true;
	}

	/**
	 * 获取歌词面板的滚动面板 —— 供歌词滚动线程获取滚动条。
	 * 
	 * @return 歌词 JScrollPane
	 */
	public JScrollPane getUILRCPane() {
		return jspLRC;
	}

	/**
	 * 获取布局根容器 —— 供 {@code ConsFrame.printComponent()} 渲染整个播放器界面。
	 * 
	 * @return 根 JPanel
	 */
	public JPanel getListSuperPane() {
		return listPane;
	}

	/**
	 * 获取歌词标题标签 —— 供外部更新歌词状态信息（如"歌词显示 : xxx"）。
	 * 
	 * @return 歌词标题 JLabel
	 */
	public JLabel getLRCLabel() {
		return labLRC;
	}
}
