package sair.player.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import sair.FCM;
import sair.player.audio.SoundstageExpander;
import sair.player.audio.SurroundProcessor;
import sair.player.audio.SurroundProcessor.SurroundMode;
import sair.player.audio.ViperEffectsProcessor;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;

/**
 * 高级音效控制面板 —— 集成环绕声、声场扩展、Viper 效果三大子模块。
 * 
 * <h4>面板布局（GridLayout 3×1 + SOUTH 按钮栏）</h4>
 * <pre>
 *   ┌──────────────────────────────────────────┐
 *   │  [0] 环绕声面板（Surround Sound）         │
 *   │      模式选择 + Width/Mix 滑块            │
 *   ├──────────────────────────────────────────┤
 *   │  [1] 声场扩展面板（Soundstage）           │
 *   │      启用复选 + 预设 + Width/Depth/Height │
 *   ├──────────────────────────────────────────┤
 *   │  [2] Viper 效果面板（Viper Effects）     │
 *   │      启用复选 + 预设选择                  │
 *   ├──────────────────────────────────────────┤
 *   │  SOUTH: Save Config / Load Config 按钮   │
 *   └──────────────────────────────────────────┘
 * </pre>
 * 
 * <h4>三大处理器及其 DSP 管线位置</h4>
 * <ol>
 *   <li><b>环绕声</b>（{@link SurroundProcessor}）—— EQ 之后：
 *       5 种模式：None / Stereo Expand / Virtual Surround / Differential / Wide Stereo</li>
 *   <li><b>声场扩展</b>（{@link SoundstageExpander}）—— 环绕声之后：
 *       3D 声场模拟（Width/Depth/Height），实现开阔的空间感</li>
 *   <li><b>Viper 效果</b>（{@link ViperEffectsProcessor}）—— 声场之后：
 *       Bass/Treble/Compressor/Exciter 四种音效的组合应用</li>
 * </ol>
 * 
 * <h4>滑块值映射</h4>
 * 本面板的滑块值除以 10 后传给处理器（{@code sliderValue / 10.0f}），
 * 实现 0.1 精度的浮点参数控制。例如滑块 15 → 处理器接收 1.5。
 * 
 * <h4>配置持久化</h4>
 * 与 EQPanel 相同，通过反射调用 {@code PlayerActions.saveAudioConfigManual()} / 
 * {@code loadAudioConfigManual()}，避免跨包的直接类型依赖。
 * 
 * @author SairFramework
 */
public class AdvancedEffectsPanel extends JPanel {

	private static final long serialVersionUID = 1L;

	// === 处理器引用 ===
	private SurroundProcessor surroundProcessor;
	private SoundstageExpander soundstageExpander;
	private ViperEffectsProcessor viperProcessor;
	/** PlayerActions 引用（用于反射调用保存/加载方法） */
	private Object playerActions;

	// === 环绕声 UI 组件 ===
	private JComboBox<String> surroundModeCombo;    // 环绕模式选择
	private JSlider surroundWidthSlider;            // 声道宽度（0.1 精度）
	private JSlider surroundMixSlider;              // 混合比例（0.1 精度）

	// === 声场扩展 UI 组件 ===
	private JCheckBox soundstageEnabledCheck;       // 启用/停用
	private JSlider widthSlider;                    // 宽度
	private JSlider depthSlider;                    // 深度
	private JSlider heightSlider;                   // 高度
	private JComboBox<String> soundstagePresetCombo; // 预设选择

	// === Viper 效果 UI 组件 ===
	private JCheckBox viperEnabledCheck;            // 启用/停用
	private JComboBox<String> viperPresetCombo;     // 预设选择

	/**
	 * 构造 —— 接收三个处理器引用并构建 UI。
	 * 
	 * @param surround  环绕声处理器
	 * @param soundstage 声场扩展处理器
	 * @param viper      Viper 效果处理器
	 */
	public AdvancedEffectsPanel(SurroundProcessor surround,
	                            SoundstageExpander soundstage,
	                            ViperEffectsProcessor viper) {
		this.surroundProcessor = surround;
		this.soundstageExpander = soundstage;
		this.viperProcessor = viper;

		initUI();
	}

	/**
	 * 设置 PlayerActions 引用（用于保存/加载配置的反射调用）。
	 * 
	 * <p>使用 {@code Object} 类型而非强类型引用，避免 UI 包直接依赖 acts 包。</p>
	 * 
	 * @param pa PlayerActions 实例
	 */
	public void setPlayerActions(Object pa) {
		this.playerActions = pa;
	}

	/**
	 * 构建完整 UI 结构。
	 * 
	 * <h4>布局层次</h4>
	 * <ul>
	 *   <li>最外层：BorderLayout</li>
	 *   <li>CENTER：3 行 GridLayout（环绕声 + 声场 + Viper）</li>
	 *   <li>SOUTH：配置保存/加载按钮栏</li>
	 * </ul>
	 */
	private void initUI() {
		setLayout(new BorderLayout(10, 10));
		setOpaque(false);
		setBorder(new SBorder(FCM.EXECTION_help_Color));

		// 主面板：3 行 GridLayout
		JPanel mainPanel = new JPanel(new GridLayout(3, 1, 10, 10));
		mainPanel.setOpaque(false);
		mainPanel.setBackground(new Color(0, 0, 0, 200));

		mainPanel.add(createSurroundPanel());    // 第 1 行：环绕声
		mainPanel.add(createSoundstagePanel());  // 第 2 行：声场扩展
		mainPanel.add(createViperPanel());       // 第 3 行：Viper 效果

		add(mainPanel, BorderLayout.CENTER);

		// 底部保存/加载按钮
		add(createConfigButtonPanel(), BorderLayout.SOUTH);
	}

	/**
	 * 创建环绕声控制面板。
	 * 
	 * <h4>控件列表</h4>
	 * <ul>
	 *   <li><b>Mode 下拉框</b>：5 种环绕模式
	 *     <ul>
	 *       <li>"None" — 直通，无处理</li>
	 *       <li>"Stereo Expand" — 立体声扩展（M/S 分解 + 增益调整）</li>
	 *       <li>"Virtual Surround" — 虚拟环绕声（哈斯效应延迟线）</li>
	 *       <li>"Differential" — 差分模式（仅保留 Side 信号）</li>
	 *       <li>"Wide Stereo" — 宽立体声（增强 Side，保留 Mid）</li>
	 *     </ul>
	 *   </li>
	 *   <li><b>Width 滑块</b>：声道扩展宽度（1.0 ~ 3.0，默认 1.5）</li>
	 *   <li><b>Mix 滑块</b>：干/湿混合比例（0.0 ~ 1.0，默认 0.3）</li>
	 * </ul>
	 * 
	 * <p>模式选择直接映射到 {@link SurroundMode} 枚举的 ordinal 值。</p>
	 * 
	 * @return 环绕声面板
	 */
	private JPanel createSurroundPanel() {
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createTitledBorder(
			new SBorder(FCM.EXECTION_help_Color), "Surround Sound"));
		panel.setBackground(new Color(0, 0, 0, 150));

		// 环绕模式选择
		JPanel modePanel = new JPanel(new BorderLayout(5, 5));
		modePanel.setOpaque(false);
		modePanel.setBackground(new Color(0, 0, 0, 100));

		JLabel modeLabel = new JLabel("Mode:", SwingConstants.RIGHT);
		modeLabel.setForeground(FCM.EXECTION_pathInfo_Color);
		modeLabel.setFont(ConsFrame.font.deriveFont(11f));

		String[] modes = {"None", "Stereo Expand", "Virtual Surround",
		                 "Differential", "Wide Stereo"};
		surroundModeCombo = new JComboBox<>(modes);
		surroundModeCombo.setForeground(FCM.EXECTION_help_Color);
		surroundModeCombo.setBackground(Color.BLACK);
		surroundModeCombo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				// 下拉索引 → SurroundMode 枚举
				int index = surroundModeCombo.getSelectedIndex();
				SurroundMode mode = SurroundMode.values()[index];
				surroundProcessor.setMode(mode);
			}
		});

		modePanel.add(modeLabel, BorderLayout.WEST);
		modePanel.add(surroundModeCombo, BorderLayout.CENTER);

		// 参数控制（2 行滑块）
		JPanel paramsPanel = new JPanel(new GridLayout(2, 1, 5, 5));
		paramsPanel.setOpaque(false);
		paramsPanel.setBackground(new Color(0, 0, 0, 100));

		// 宽度滑块：1.0 ~ 3.0（内部乘 10 为 10~30）
		surroundWidthSlider = createSlider("Width:", 10, 30, 15,
			new ChangeListener() {
				@Override
				public void stateChanged(ChangeEvent e) {
					surroundProcessor.setExpandWidth(surroundWidthSlider.getValue() / 10.0f);
				}
			});

		// 混合滑块：0.0 ~ 1.0（内部乘 10 为 0~10）
		surroundMixSlider = createSlider("Mix:", 0, 10, 3,
			new ChangeListener() {
				@Override
				public void stateChanged(ChangeEvent e) {
					surroundProcessor.setSurroundMix(surroundMixSlider.getValue() / 10.0f);
				}
			});

		paramsPanel.add(surroundWidthSlider);
		paramsPanel.add(surroundMixSlider);

		panel.add(modePanel, BorderLayout.NORTH);
		panel.add(paramsPanel, BorderLayout.CENTER);

		return panel;
	}

	/**
	 * 创建声场扩展控制面板。
	 * 
	 * <h4>控件列表</h4>
	 * <ul>
	 *   <li><b>Enable 复选框</b>：启用/停用声场扩展处理</li>
	 *   <li><b>Preset 下拉框</b>：6 种预设场景
	 *     <ul>
	 *       <li>"Custom" — 手动调整（不应用预设）</li>
	 *       <li>"Small Room" — 小房间</li>
	 *       <li>"Large Hall" — 大音乐厅</li>
	 *       <li>"Concert" — 音乐会</li>
	 *       <li>"Studio" — 录音室</li>
	 *       <li>"Wide" — 宽阔场景</li>
	 *     </ul>
	 *   </li>
	 *   <li><b>Width 滑块</b>：声场宽度（0.5 ~ 3.0）</li>
	 *   <li><b>Depth 滑块</b>：声场深度（0.0 ~ 2.0）</li>
	 *   <li><b>Height 滑块</b>：声场高度（0.5 ~ 2.0）</li>
	 * </ul>
	 * 
	 * <h4>预设 → Custom 自动切换</h4>
	 * 当用户手动拖动 Width/Depth/Height 任意滑块时，预设下拉框自动切换为 "Custom"，
	 * 表示当前参数已偏离预设值。
	 * 
	 * @return 声场扩展面板
	 */
	private JPanel createSoundstagePanel() {
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createTitledBorder(
			new SBorder(FCM.EXECTION_help_Color), "Soundstage"));
		panel.setBackground(new Color(0, 0, 0, 150));

		// 启用复选框 — 控制 soundstageExpander.setEnabled()
		soundstageEnabledCheck = new JCheckBox("Enable");
		soundstageEnabledCheck.setForeground(FCM.EXECTION_help_Color);
		soundstageEnabledCheck.setBackground(Color.BLACK);
		soundstageEnabledCheck.setFont(ConsFrame.font.deriveFont(11f));
		soundstageEnabledCheck.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				soundstageExpander.setEnabled(soundstageEnabledCheck.isSelected());
			}
		});

		// 预设选择
		JPanel presetPanel = new JPanel(new BorderLayout(5, 5));
		presetPanel.setOpaque(false);
		presetPanel.setBackground(new Color(0, 0, 0, 100));

		JLabel presetLabel = new JLabel("Preset:", SwingConstants.RIGHT);
		presetLabel.setForeground(FCM.EXECTION_pathInfo_Color);
		presetLabel.setFont(ConsFrame.font.deriveFont(11f));

		String[] presets = {"Custom", "Small Room", "Large Hall",
		                   "Concert", "Studio", "Wide"};
		soundstagePresetCombo = new JComboBox<>(presets);
		soundstagePresetCombo.setForeground(FCM.EXECTION_help_Color);
		soundstagePresetCombo.setBackground(Color.BLACK);
		soundstagePresetCombo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String preset = (String) soundstagePresetCombo.getSelectedItem();
				// "Custom" 不应用预设（保持手动设定值）
				if (preset != null && !preset.equals("Custom")) {
					soundstageExpander.applyPreset(preset.toLowerCase().replace(" ", "_"));
				}
			}
		});

		presetPanel.add(presetLabel, BorderLayout.WEST);
		presetPanel.add(soundstagePresetCombo, BorderLayout.CENTER);

		// 参数滑块（3 行）
		JPanel slidersPanel = new JPanel(new GridLayout(3, 1, 5, 5));
		slidersPanel.setOpaque(false);
		slidersPanel.setBackground(new Color(0, 0, 0, 100));

		// Width 滑块：0.5 ~ 3.0（内部分辨率 0.1）
		widthSlider = createSlider("Width:", 5, 30, 10,
			new ChangeListener() {
				@Override
				public void stateChanged(ChangeEvent e) {
					soundstageExpander.setWidth(widthSlider.getValue() / 10.0f);
					soundstagePresetCombo.setSelectedIndex(0);  // 切换到 Custom
				}
			});

		// Depth 滑块：0.0 ~ 2.0
		depthSlider = createSlider("Depth:", 0, 20, 10,
			new ChangeListener() {
				@Override
				public void stateChanged(ChangeEvent e) {
					soundstageExpander.setDepth(depthSlider.getValue() / 10.0f);
					soundstagePresetCombo.setSelectedIndex(0);  // 切换到 Custom
				}
			});

		// Height 滑块：0.5 ~ 2.0
		heightSlider = createSlider("Height:", 5, 20, 10,
			new ChangeListener() {
				@Override
				public void stateChanged(ChangeEvent e) {
					soundstageExpander.setHeight(heightSlider.getValue() / 10.0f);
					soundstagePresetCombo.setSelectedIndex(0);  // 切换到 Custom
				}
			});

		slidersPanel.add(widthSlider);
		slidersPanel.add(depthSlider);
		slidersPanel.add(heightSlider);

		panel.add(soundstageEnabledCheck, BorderLayout.NORTH);
		panel.add(presetPanel, BorderLayout.WEST);
		panel.add(slidersPanel, BorderLayout.CENTER);

		return panel;
	}

	/**
	 * 创建 Viper 效果控制面板。
	 * 
	 * <h4>控件列表</h4>
	 * <ul>
	 *   <li><b>Enable 复选框</b>：启用/停用 Viper 效果链</li>
	 *   <li><b>Preset 下拉框</b>：6 种预设音效风格
	 *     <ul>
	 *       <li>"Natural" — 自然（轻微增强）</li>
	 *       <li>"Bass Boost" — 低音增强（增强 Bass，减小 Treble）</li>
	 *       <li>"Vocal Clarity" — 人声清晰（增强 Treble + Exciter）</li>
	 *       <li>"Dynamic" — 动态（启用 Compressor 压缩器）</li>
	 *       <li>"Warm" — 温暖（Bass 增强 + 轻微压缩）</li>
	 *       <li>"Bright" — 明亮（Treble 增强 + Exciter 激励）</li>
	 *     </ul>
	 *   </li>
	 * </ul>
	 * 
	 * <p>Viper 效果是四个子模块的组合（BassEnhancer → TrebleEnhancer → 
	 * Compressor → Exciter），通过预设统一配置它们的参数。</p>
	 * 
	 * @return Viper 效果面板
	 */
	private JPanel createViperPanel() {
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createTitledBorder(
			new SBorder(FCM.EXECTION_help_Color), "Viper Effects"));
		panel.setBackground(new Color(0, 0, 0, 150));

		// 启用复选框
		viperEnabledCheck = new JCheckBox("Enable");
		viperEnabledCheck.setForeground(FCM.EXECTION_help_Color);
		viperEnabledCheck.setBackground(Color.BLACK);
		viperEnabledCheck.setFont(ConsFrame.font.deriveFont(11f));
		viperEnabledCheck.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				viperProcessor.setEnabled(viperEnabledCheck.isSelected());
			}
		});

		// 预设选择
		JPanel presetPanel = new JPanel(new BorderLayout(5, 5));
		presetPanel.setOpaque(false);
		presetPanel.setBackground(new Color(0, 0, 0, 100));

		JLabel presetLabel = new JLabel("Preset:", SwingConstants.RIGHT);
		presetLabel.setForeground(FCM.EXECTION_pathInfo_Color);
		presetLabel.setFont(ConsFrame.font.deriveFont(11f));

		String[] presets = {"Natural", "Bass Boost", "Vocal Clarity",
		                   "Dynamic", "Warm", "Bright"};
		viperPresetCombo = new JComboBox<>(presets);
		viperPresetCombo.setForeground(FCM.EXECTION_help_Color);
		viperPresetCombo.setBackground(Color.BLACK);
		viperPresetCombo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String preset = (String) viperPresetCombo.getSelectedItem();
				if (preset != null) {
					viperProcessor.applyPreset(preset.toLowerCase().replace(" ", "_"));
				}
			}
		});

		presetPanel.add(presetLabel, BorderLayout.WEST);
		presetPanel.add(viperPresetCombo, BorderLayout.CENTER);

		panel.add(viperEnabledCheck, BorderLayout.NORTH);
		panel.add(presetPanel, BorderLayout.CENTER);

		return panel;
	}

	/**
	 * 通用滑块构造器 —— 创建带标签的水平滑块面板。
	 * 
	 * <h4>参数说明</h4>
	 * <ul>
	 *   <li>{@code label}：左侧标签文字（如 "Width:"）</li>
	 *   <li>{@code min} / {@code max}：滑块范围</li>
	 *   <li>{@code value}：初始值</li>
	 *   <li>{@code listener}：变化监听器（可 null）</li>
	 * </ul>
	 * 
	 * <h4>刻度计算</h4>
	 * 主刻度 = (max - min) / 5，副刻度 = (max - min) / 10。
	 * 例如 10~30 范围 → 主刻度每 4 单位，副刻度每 2 单位。
	 * 
	 * <p>返回的是 JSlider 而非 JPanel，因为调用者通常只需要滑块的引用。
	 * 标签已通过 JPanel 包装在左侧。</p>
	 * 
	 * @param label    标签文字
	 * @param min      最小值
	 * @param max      最大值
	 * @param value    初始值
	 * @param listener 变化监听器
	 * @return 创建好的 JSlider 实例
	 */
	private JSlider createSlider(String label, int min, int max, int value,
	                             ChangeListener listener) {
		JPanel panel = new JPanel(new BorderLayout(5, 0));
		panel.setOpaque(false);

		JLabel lbl = new JLabel(label, SwingConstants.RIGHT);
		lbl.setForeground(FCM.EXECTION_pathInfo_Color);
		lbl.setFont(ConsFrame.font.deriveFont(10f));

		JSlider slider = new JSlider(SwingConstants.HORIZONTAL, min, max, value);
		slider.setMajorTickSpacing((max - min) / 5);
		slider.setMinorTickSpacing((max - min) / 10);
		slider.setPaintTicks(true);
		slider.setPaintLabels(false);                 // 不显示数值标签（面板空间有限）
		slider.setPreferredSize(new Dimension(150, 30));
		slider.setForeground(FCM.EXECTION_help_Color);
		slider.setBackground(Color.BLACK);

		if (listener != null) {
			slider.addChangeListener(listener);
		}

		panel.add(lbl, BorderLayout.WEST);
		panel.add(slider, BorderLayout.CENTER);

		return slider;
	}

	/**
	 * 从处理器回读所有参数并刷新 UI 控件状态。
	 * 
	 * <h4>刷新顺序</h4>
	 * <ol>
	 *   <li>环绕声：模式下拉框、Width/Mix 滑块</li>
	 *   <li>声场扩展：启用复选框、Width/Depth/Height 滑块</li>
	 *   <li>Viper：启用复选框</li>
	 * </ol>
	 * 
	 * <h4>滑块值逆映射</h4>
	 * 处理器浮点值 × 10 → 四舍五入取整 → 设置滑块值。
	 * 例如处理器 Width=1.5 → 滑块值=15 → 用户拖动后再除 10 传回处理器。
	 * 
	 * <p>注意：Preset 下拉框不在此处更新，因为加载预设后
	 * 预设选择状态由各面板内部的监听器维护。</p>
	 */
	public void updateFromProcessors() {
		// 更新环绕声
		surroundModeCombo.setSelectedIndex(surroundProcessor.getMode().ordinal());
		surroundWidthSlider.setValue((int) (surroundProcessor.getExpandWidth() * 10));
		surroundMixSlider.setValue((int) (surroundProcessor.getSurroundMix() * 10));

		// 更新声场
		soundstageEnabledCheck.setSelected(soundstageExpander.isEnabled());
		widthSlider.setValue((int) (soundstageExpander.getWidth() * 10));
		depthSlider.setValue((int) (soundstageExpander.getDepth() * 10));
		heightSlider.setValue((int) (soundstageExpander.getHeight() * 10));

		// 更新 Viper
		viperEnabledCheck.setSelected(viperProcessor.isEnabled());
	}

	/**
	 * 创建配置保存/加载按钮面板（SOUTH 位置）。
	 * 
	 * <h4>按钮列表</h4>
	 * <ul>
	 *   <li><b>💾 Save Config</b>（绿色）：反射调用 {@code saveAudioConfigManual()}</li>
	 *   <li><b>📂 Load Config</b>（蓝色）：反射调用 {@code loadAudioConfigManual()} + 
	 *       {@link #updateFromProcessors()} 刷新 UI</li>
	 * </ul>
	 * 
	 * <h4>错误处理</h4>
	 * 如果 {@code playerActions} 为 null（歌曲未播放），打印提示信息。
	 * 如果反射调用失败（方法不存在等），打印异常信息。
	 * 
	 * @return 按钮面板
	 */
	private JPanel createConfigButtonPanel() {
		JPanel panel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
		panel.setOpaque(false);
		panel.setBackground(new Color(0, 0, 0, 150));

		// 保存按钮
		JButton saveBtn = new JButton("💾 Save Config");
		saveBtn.setForeground(FCM.EXECTION_help_Color);
		saveBtn.setBackground(new Color(0, 100, 0));
		saveBtn.setFont(ConsFrame.font.deriveFont(11f));
		saveBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (playerActions != null) {
					try {
						java.lang.reflect.Method method = playerActions.getClass().getMethod("saveAudioConfigManual");
						method.invoke(playerActions);
					} catch (Exception ex) {
						sair.sys.SairCons.println("保存配置失败: " + ex.getMessage());
					}
				} else {
					sair.sys.SairCons.println("请先播放一首歌曲");
				}
			}
		});

		// 加载按钮
		JButton loadBtn = new JButton("📂 Load Config");
		loadBtn.setForeground(FCM.EXECTION_help_Color);
		loadBtn.setBackground(new Color(0, 0, 100));
		loadBtn.setFont(ConsFrame.font.deriveFont(11f));
		loadBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (playerActions != null) {
					try {
						java.lang.reflect.Method method = playerActions.getClass().getMethod("loadAudioConfigManual");
						method.invoke(playerActions);
						updateFromProcessors(); // 加载后刷新 UI 控件
					} catch (Exception ex) {
						sair.sys.SairCons.println("加载配置失败: " + ex.getMessage());
					}
				} else {
					sair.sys.SairCons.println("请先播放一首歌曲");
				}
			}
		});

		panel.add(saveBtn);
		panel.add(loadBtn);

		return panel;
	}
}
