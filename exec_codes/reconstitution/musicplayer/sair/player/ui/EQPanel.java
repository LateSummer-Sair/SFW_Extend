package sair.player.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import sair.FCM;
import sair.player.audio.EQProcessor;
import sair.player.audio.VolumeController;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;

/**
 * 15 段均衡器图形化控制面板。
 * 
 * <h4>面板布局</h4>
 * <pre>
 *   ┌──────────────────────────────────────────────────┐
 *   │  BorderLayout.NORTH  : 音量滑块 + IIR 标签        │
 *   ├──────────┬───────────────────────────────────────┤
 *   │ WEST     │  CENTER: 15 段 EQ 滑块 (GridLayout)   │
 *   │ 预设列表 │  ├── 纵向滑块 ×15                       │
 *   │ Reset   │  └── SOUTH: 频段标签 ×15               │
 *   │ Save    │                                        │
 *   │ Load    │                                        │
 *   ├──────────┴───────────────────────────────────────┤
 *   │  BorderLayout.SOUTH  : 频率响应曲线图             │
 *   └──────────────────────────────────────────────────┘
 * </pre>
 * 
 * <h4>数据流</h4>
 * <ol>
 *   <li>滑块 dB 值（-18 ~ +18）→ 监听器转为线性增益 → 写入 {@link EQProcessor}</li>
 *   <li>预设选择 → {@link EQProcessor#applyPreset(String)} → 回读增益 → 更新滑块</li>
 *   <li>滑块变化 → 触发 {@link FrequencyResponsePanel#refresh()} 实时更新曲线</li>
 * </ol>
 * 
 * <h4>dB ↔ 线性增益换算</h4>
 * <ul>
 *   <li><b>dB → 线性</b>：{@code gain = 10^(dB/20)}</li>
 *   <li><b>线性 → dB</b>：{@code dB = 20 * log10(gain)}</li>
 *   <li>{@code Math.max(0.001f, gain)} 防止 log10(0) = -∞ 溢出</li>
 * </ul>
 * 
 * <h4>单例与引用更新</h4>
 * 面板在 {@link ConsUI#showEQPanel()} 中创建并缓存（单例模式）。
 * 切换歌曲时通过 {@link #updateProcessors(EQProcessor, VolumeController)} 
 * 更新处理器引用并同步滑块位置。
 * 
 * <h4>配置持久化</h4>
 * 通过反射调用 {@code PlayerActions.saveAudioConfigManual()} / {@code loadAudioConfigManual()}。
 * 使用反射而非直接引用是因为 UI 包与 acts 包的循环依赖问题。
 * 
 * @author SairFramework
 */
public class EQPanel extends JPanel {

	private static final long serialVersionUID = -7229061108199896643L;
	
	/** EQ 频段增益处理器 */
	private EQProcessor eqProcessor;
	/** 音量控制器 */
	private VolumeController volumeController;
	/** 15 段 EQ 滑块数组 */
	private JSlider[] eqSliders;
	/** 音量滑块 */
	private JSlider volumeSlider;
	/** 15 段 EQ 频率标签数组 */
	private JLabel[] eqLabels;
	/** 底部频率响应曲线面板 */
	private FrequencyResponsePanel responsePanel;
	/** 预设选择下拉框 */
	private JComboBox<String> presetCombo;
	/** PlayerActions 引用（用于反射调用保存/加载方法） */
	private Object playerActions;

	/**
	 * 构造 —— 接收处理器引用并构建 UI。
	 * 
	 * @param eq EQ 频段增益处理器
	 * @param vc 音量控制器
	 */
	public EQPanel(EQProcessor eq, VolumeController vc) {
		this.eqProcessor = eq;
		this.volumeController = vc;
		initUI();
	}

	/**
	 * 设置 PlayerActions 引用（用于保存/加载配置的反射调用）。
	 * 
	 * <p>使用 {@code Object} 类型而非强类型引用，是因为 UI 包应避免直接依赖 acts 包
	 * （模块解耦设计）。通过反射调用 {@code saveAudioConfigManual()} / 
	 * {@code loadAudioConfigManual()} 方法。</p>
	 * 
	 * @param pa PlayerActions 实例（实际类型为 {@code sair.player.acts.PlayerActions}）
	 */
	public void setPlayerActions(Object pa) {
		this.playerActions = pa;
	}

	/**
	 * 更新处理器引用（切换歌曲时调用）。
	 * 
	 * <h4>执行步骤</h4>
	 * <ol>
	 *   <li>替换 {@code eqProcessor} 和 {@code volumeController} 字段</li>
	 *   <li>更新 {@link FrequencyResponsePanel} 的 EQ 处理器引用</li>
	 *   <li>调用 {@link #syncSlidersToEQ()} 将 15 个滑块同步到新处理器的增益值</li>
	 * </ol>
	 * 
	 * <p>这是关键的跨歌曲状态同步机制。如果没有此方法，切换歌曲后：
	 * <ul>
	 *   <li>EQ 面板滑块位置仍是旧歌曲的设置</li>
	 *   <li>滑块操作将作用于新处理器，但初始值不对齐</li>
	 * </ul></p>
	 * 
	 * @param eq 新的 EQ 处理器
	 * @param vc 新的音量控制器
	 */
	public void updateProcessors(EQProcessor eq, VolumeController vc) {
		this.eqProcessor = eq;
		this.volumeController = vc;

		// 更新频率响应面板的引用
		if (responsePanel != null) {
			responsePanel.updateEQProcessor(eq);
		}

		// 重新同步滑块位置到新的 EQ 设置
		syncSlidersToEQ();
	}

	/**
	 * 同步滑块位置到当前 EQ 处理器的增益设置。
	 * 
	 * <h4>转换流程</h4>
	 * <ol>
	 *   <li>从 {@code eqProcessor.getBandGain(i)} 获取线性增益</li>
	 *   <li>转换为 dB：{@code gainDB = 20 * log10(max(0.001, gain))}</li>
	 *   <li>dB 四舍五入取整，钳位到 [-18, 18] 范围</li>
	 *   <li>设置滑块值（一次赋值不会触发 stateChanged 回调）</li>
	 * </ol>
	 */
	private void syncSlidersToEQ() {
		if (eqSliders != null && eqProcessor != null) {
			for (int i = 0; i < eqSliders.length; i++) {
				float currentGain = eqProcessor.getBandGain(i);
				// 将线性增益转换为 dB，再转换为滑块值
				float gainDB = (float) (20.0 * Math.log10(Math.max(0.001f, currentGain)));
				int sliderValue = (int) Math.round(gainDB);
				sliderValue = Math.max(-18, Math.min(18, sliderValue));  // 钳位
				eqSliders[i].setValue(sliderValue);
			}
		}
	}

	/**
	 * 构建完整 UI 结构。
	 * 
	 * <h4>布局层次</h4>
	 * <ul>
	 *   <li>最外层：{@code EQPanel} 自身（BorderLayout），黑色半透明背景</li>
	 *   <li>{@code NORTH}：音量控制面板（{@link #createVolumePanel()}）</li>
	 *   <li>{@code WEST}：预设选择面板（{@link #createPresetPanel()}）</li>
	 *   <li>{@code CENTER}：15 段 EQ 滑块面板（{@link #createEQPanel()}）</li>
	 *   <li>{@code SOUTH}：频率响应曲线面板（{@link FrequencyResponsePanel}）</li>
	 * </ul>
	 */
	private void initUI() {
		setLayout(new BorderLayout(10, 10));
		setOpaque(false);
		setBorder(new SBorder(FCM.EXECTION_help_Color));

		// 主面板：使用半透明黑色背景衬托控件
		// 注意：setOpaque(false) 后 setBackground 可能不生效，取决于框架实现
		JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
		mainPanel.setOpaque(false);
		mainPanel.setBackground(new Color(0, 0, 0, 200));

		mainPanel.add(createVolumePanel(), BorderLayout.NORTH);
		mainPanel.add(createPresetPanel(), BorderLayout.WEST);
		mainPanel.add(createEQPanel(), BorderLayout.CENTER);

		// 频率响应曲线（底部）
		responsePanel = new FrequencyResponsePanel(eqProcessor);
		mainPanel.add(responsePanel, BorderLayout.SOUTH);

		add(mainPanel, BorderLayout.CENTER);
	}

	/**
	 * 创建预设选择面板（左侧控制栏）。
	 * 
	 * <h4>组件列表（4 行 GridLayout）</h4>
	 * <ol>
	 *   <li><b>Preset 下拉框</b>：选择预设 EQ → {@code eqProcessor.applyPreset()} → 
	 *       {@code updateFromProcessor()} 同步滑块</li>
	 *   <li><b>Reset 按钮</b>：{@code eqProcessor.resetEQ()} → 下拉框归零 → 滑块归零</li>
	 *   <li><b>Save Config 按钮</b>（绿色）：反射调用 {@code saveAudioConfigManual()}</li>
	 *   <li><b>Load Config 按钮</b>（蓝色）：反射调用 {@code loadAudioConfigManual()}</li>
	 * </ol>
	 * 
	 * <h4>预设 key 转换</h4>
	 * 下拉框显示值（如 "Bass Boost"）→ {@code toLowerCase().replace(" ", "_")}
	 * → 预设 key（如 "bass_boost"），匹配 {@link EQProcessor} 内部的预设映射。
	 * 
	 * @return 预设控制面板
	 */
	private JPanel createPresetPanel() {
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createTitledBorder(new SBorder(FCM.EXECTION_help_Color), "EQ Preset"));
		panel.setPreferredSize(new Dimension(120, 0));  // 固定宽度 120px
		panel.setBackground(new Color(0, 0, 0, 150));

		// 预设下拉框
		String[] presets = eqProcessor.getPresetNames();
		presetCombo = new JComboBox<>(presets);
		presetCombo.setForeground(FCM.EXECTION_help_Color);
		presetCombo.setBackground(Color.BLACK);
		presetCombo.setFont(ConsFrame.font.deriveFont(11f));

		presetCombo.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				String selected = (String) presetCombo.getSelectedItem();
				if (selected != null) {
					// 显示值 → 预设 key 转换
					String presetKey = selected.toLowerCase().replace(" ", "_");
					eqProcessor.applyPreset(presetKey);   // 写入处理器
					updateFromProcessor();                 // 回读 → 同步滑块
					responsePanel.refresh();               // 刷新频率响应曲线
				}
			}
		});

		// Reset 按钮 — 所有频段归零
		JButton resetBtn = new JButton("Reset");
		resetBtn.setForeground(FCM.EXECTION_help_Color);
		resetBtn.setBackground(Color.DARK_GRAY);
		resetBtn.setFont(ConsFrame.font.deriveFont(11f));
		resetBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				eqProcessor.resetEQ();
				presetCombo.setSelectedIndex(0);  // 预设下拉归零
				updateFromProcessor();
				responsePanel.refresh();
			}
		});

		// Save Config 按钮 — 反射调用保存方法
		JButton saveBtn = new JButton("Save Config");
		saveBtn.setForeground(FCM.EXECTION_help_Color);
		saveBtn.setBackground(new Color(0, 100, 0));    // 深绿色背景
		saveBtn.setFont(ConsFrame.font.deriveFont(10f));
		saveBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (playerActions != null) {
					try {
						java.lang.reflect.Method method = playerActions.getClass().getMethod("saveAudioConfigManual");
						method.invoke(playerActions);
					} catch (Exception ex) {
						SairCons.println("保存配置失败: " + ex.getMessage());
					}
				} else {
					SairCons.println("请先播放一首歌曲");
				}
			}
		});

		// Load Config 按钮 — 反射调用加载方法
		JButton loadBtn = new JButton("Load Config");
		loadBtn.setForeground(FCM.EXECTION_help_Color);
		loadBtn.setBackground(new Color(0, 0, 100));    // 深蓝色背景
		loadBtn.setFont(ConsFrame.font.deriveFont(10f));
		loadBtn.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (playerActions != null) {
					try {
						java.lang.reflect.Method method = playerActions.getClass().getMethod("loadAudioConfigManual");
						method.invoke(playerActions);
						updateFromProcessor();           // 加载后同步 UI
						responsePanel.refresh();
					} catch (Exception ex) {
						SairCons.println("加载配置失败: " + ex.getMessage());
					}
				} else {
					SairCons.println("请先播放一首歌曲");
				}
			}
		});

		// 4 行垂直排列
		JPanel btnPanel = new JPanel(new GridLayout(4, 1, 5, 5));
		btnPanel.setOpaque(false);
		btnPanel.add(presetCombo);
		btnPanel.add(resetBtn);
		btnPanel.add(saveBtn);
		btnPanel.add(loadBtn);

		panel.add(btnPanel, BorderLayout.CENTER);
		return panel;
	}

	/**
	 * 创建音量控制面板（顶部水平滑块）。
	 * 
	 * <h4>滑块参数</h4>
	 * <ul>
	 *   <li>范围：0 ~ 100（百分比）</li>
	 *   <li>初始值：从 {@code volumeController.getVolumePercent()} 读取</li>
	 *   <li>刻度：主刻度 25，副刻度 5</li>
	 * </ul>
	 * 
	 * <h4>实时生效</h4>
	 * 滑块值变化 → 直接调用 {@code volumeController.setVolumePercent(value)}，
	 * 音量在 DSP 管线末尾的 {@link sair.player.audio.VolumeController} 中生效。
	 * 
	 * @return 音量控制面板
	 */
	private JPanel createVolumePanel() {
		JPanel panel = new JPanel(new BorderLayout(10, 5));
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createTitledBorder(new SBorder(FCM.EXECTION_help_Color), "Volume"));
		panel.setBackground(new Color(0, 0, 0, 150));

		JLabel label = new JLabel("IIR: ", SwingConstants.RIGHT);
		label.setForeground(FCM.EXECTION_pathInfo_Color);
		label.setFont(ConsFrame.font);

		volumeSlider = new JSlider(SwingConstants.HORIZONTAL, 0, 100, volumeController.getVolumePercent());
		volumeSlider.setMajorTickSpacing(25);
		volumeSlider.setMinorTickSpacing(5);
		volumeSlider.setPaintTicks(true);
		volumeSlider.setPaintLabels(true);
		volumeSlider.setForeground(FCM.EXECTION_help_Color);
		volumeSlider.setBackground(Color.BLACK);

		volumeSlider.addChangeListener(new ChangeListener() {
			@Override
			public void stateChanged(ChangeEvent e) {
				int value = volumeSlider.getValue();
				volumeController.setVolumePercent(value);  // 实时生效
			}
		});

		panel.add(label, BorderLayout.WEST);
		panel.add(volumeSlider, BorderLayout.CENTER);

		return panel;
	}

	/**
	 * 创建 15 段均衡器滑块面板（核心控件区域）。
	 * 
	 * <h4>布局结构</h4>
	 * <pre>
	 *   CENTER: 15 个纵向滑块（GridLayout 1×15）
	 *   SOUTH:  15 个频率标签（GridLayout 1×15）
	 * </pre>
	 * 
	 * <h4>每个滑块的参数</h4>
	 * <ul>
	 *   <li>方向：{@code VERTICAL}（纵向）</li>
	 *   <li>范围：-18 ~ +18 dB</li>
	 *   <li>初始值：0 dB（需要从处理器读取，见下方初始值设置逻辑）</li>
	 *   <li>主刻度：6 dB</li>
	 *   <li>副刻度：3 dB</li>
	 *   <li>尺寸：45×180 px</li>
	 *   <li>不显示标签（标签在下方独立 labelsPanel）</li>
	 * </ul>
	 * 
	 * <h4>初始值设置</h4>
	 * 构造时读取 {@code eqProcessor.getBandGain(i)} 的当前增益 → 转为 dB → 
	 * 设置滑块值。如果 EQ 刚创建（默认增益 = 1.0 = 0 dB），则所有滑块为 0。
	 * 
	 * <h4>实时更新策略</h4>
	 * 使用 {@code getValueIsAdjusting()} 检查，仅在用户松手后才写入 EQ 处理器。
	 * 这避免了拖动过程中大量重复更新导致的 UI 卡顿。
	 * 
	 * @return EQ 滑块面板
	 */
	private JPanel createEQPanel() {
		JPanel panel = new JPanel(new BorderLayout(5, 5));
		panel.setOpaque(false);
		panel.setBorder(BorderFactory.createTitledBorder(new SBorder(FCM.EXECTION_help_Color), "15-Band Equalizer"));
		panel.setBackground(new Color(0, 0, 0, 150));

		String[] labels = eqProcessor.getBandLabels();  // 如 "31", "63", "125" ...
		eqSliders = new JSlider[EQProcessor.BAND_COUNT];
		eqLabels = new JLabel[EQProcessor.BAND_COUNT];

		JPanel slidersPanel = new JPanel(new GridLayout(1, EQProcessor.BAND_COUNT, 5, 5));
		slidersPanel.setOpaque(false);

		JPanel labelsPanel = new JPanel(new GridLayout(1, EQProcessor.BAND_COUNT, 5, 5));
		labelsPanel.setOpaque(false);

		for (int i = 0; i < EQProcessor.BAND_COUNT; i++) {
			final int bandIndex = i;  // 闭包需要 final 变量

			// 频率标签（底部）
			eqLabels[i] = new JLabel(labels[i], SwingConstants.CENTER);
			eqLabels[i].setForeground(FCM.EXECTION_help_Color);
			eqLabels[i].setFont(ConsFrame.font.deriveFont(10f));

			// 纵向滑块：-18 ~ +18 dB，初值 0
			eqSliders[i] = new JSlider(SwingConstants.VERTICAL, -18, 18, 0);
			eqSliders[i].setMajorTickSpacing(6);
			eqSliders[i].setMinorTickSpacing(3);
			eqSliders[i].setPaintTicks(true);
			eqSliders[i].setPaintLabels(false);             // 不显示数值标签
			eqSliders[i].setPreferredSize(new Dimension(45, 180));
			eqSliders[i].setForeground(FCM.EXECTION_help_Color);
			eqSliders[i].setBackground(Color.BLACK);

			// 读取处理器当前增益并设置滑块初始值
			float currentGain = eqProcessor.getBandGain(i);
			float gainDB = (float) (20.0 * Math.log10(Math.max(0.001f, currentGain)));
			int sliderValue = (int) Math.round(gainDB);
			sliderValue = Math.max(-18, Math.min(18, sliderValue));  // 钳位到有效范围
			eqSliders[i].setValue(sliderValue);

			// 变化监听：仅在松手后（!getValueIsAdjusting）才写入处理器
			eqSliders[i].addChangeListener(new ChangeListener() {
				@Override
				public void stateChanged(ChangeEvent e) {
					if (!eqSliders[bandIndex].getValueIsAdjusting()) {  // 拖动结束
						int value = eqSliders[bandIndex].getValue();
						// dB 值 → 线性增益：gain = 10^(dB/20)
						float gain = (float) Math.pow(10.0, value / 20.0);
						eqProcessor.setBandGain(bandIndex, gain);
						responsePanel.refresh();  // 刷新频率响应曲线
					}
				}
			});

			slidersPanel.add(eqSliders[i]);
			labelsPanel.add(eqLabels[i]);
		}

		panel.add(slidersPanel, BorderLayout.CENTER);
		panel.add(labelsPanel, BorderLayout.SOUTH);

		return panel;
	}

	/**
	 * 从处理器回读所有设置并更新 UI 控件。
	 * 
	 * <h4>读取顺序</h4>
	 * <ol>
	 *   <li>音量滑块：从 {@code volumeController.getVolumePercent()} 读取</li>
	 *   <li>15 个 EQ 滑块：遍历 {@code eqProcessor.getBandGain(i)} → 
	 *       转为 dB → 设置滑块值</li>
	 * </ol>
	 * 
	 * <p>注意：此方法不刷新频率响应曲线（调用者需要手动调用 
	 * {@code responsePanel.refresh()}），因为批量更新时只在最后刷新一次即可。</p>
	 */
	public void updateFromProcessor() {
		volumeSlider.setValue(volumeController.getVolumePercent());

		for (int i = 0; i < EQProcessor.BAND_COUNT; i++) {
			float gain = eqProcessor.getBandGain(i);
			int sliderValue = (int) Math.round(20.0 * Math.log10(gain));
			eqSliders[i].setValue(sliderValue);
		}
	}

	/**
	 * 刷新所有控件的颜色——在 FCM 主题切换时调用。
	 * 
	 * <h4>刷新范围</h4>
	 * <ul>
	 *   <li>音量滑块前景色</li>
	 *   <li>15 个 EQ 滑块前景色</li>
	 *   <li>面板外边框</li>
	 * </ul>
	 * 
	 * <p>注意：内部预设下拉框、按钮等颜色不在此处刷新，
	 * 因为它们通过不同机制获取颜色（直接读取 FCM 静态字段）。</p>
	 */
	public void refreshColors() {
		Color textColor = ConsFrame.getFontColor();
		Color borderColor = FCM.EXECTION_help_Color;

		volumeSlider.setForeground(textColor);
		for (JSlider slider : eqSliders) {
			slider.setForeground(textColor);
		}

		setBorder(new SBorder(borderColor));
	}
}
