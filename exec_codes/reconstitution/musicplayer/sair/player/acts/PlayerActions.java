package sair.player.acts;

import java.awt.Point;
import java.io.File;
import java.io.IOException;

import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.JViewport;

import sair.FCM;
import sair.player.PlayerActivity;
import sair.player.audio.AudioConfig;
import sair.player.audio.EQProcessor;
import sair.player.audio.VolumeController;
import sair.player.audio.SurroundProcessor;
import sair.player.audio.SoundstageExpander;
import sair.player.audio.ViperEffectsProcessor;
import sair.sys.SairCons;

/**
 * 播放器核心调度层 —— 连接 UI 层和播放引擎的桥梁。
 * 
 * <h3>核心职责</h3>
 * <ul>
 *   <li><b>播放生命周期管理</b>：start/pause/stop/continue，内部委托给 {@link SairMP3Player}</li>
 *   <li><b>进度控制</b>：通过 {@link #setPlayPos(float)} 跳转，{@link #getNowPos()} 获取当前进度</li>
 *   <li><b>处理器统一管理</b>：EQ/Viper/Surround/Volume/Soundstage 在此创建并注入播放器，
 *       确保所有歌曲共享同一组处理器实例（单例模式）</li>
 *   <li><b>UI 刷新</b>：切换歌曲时自动滚动列表到当前播放项，LRCDI 面板同理</li>
 *   <li><b>配置持久化</b>：通过 {@link AudioConfig} 保存/加载音效参数</li>
 * </ul>
 * 
 * <h3>处理器共享机制</h3>
 * 所有音频处理器在首次 {@link #initAdvancedEffects(javax.sound.sampled.AudioFormat)} 调用时创建，
 * 后续切歌时通过 {@link #setNewPlayer(SairMP3Player)} 注入到新播放器实例。
 * 这确保了 EQ 面板的滑块状态、Viper 预设等在切歌时保持不变。
 * 
 * @see SairMP3Player#processAudio(byte[], int) 处理器链执行顺序
 * @see AudioConfig 配置持久化
 */
public class PlayerActions {

	/** 播放列表数据持久化文件名 */
	public final static String irName = "savelist.data";

	/**
	 * 首次加载时从 savelist.data 恢复播放列表。
	 * <p>仅在 {@link PlayerActivity#firstLoad()} 中调用一次。</p>
	 * @return 路径数组，文件不存在时返回 null
	 */
	public static String[] firstLoad(PlayerActivity pa) {
		if (pa.isLoad() == true)
			return null;
		String[] list = IOTools.readListDataFile(pa.getDataFilePath(), false);
		return list;
	}

	/** 当前正在播放的歌曲在列表中的序号（-1 表示无） */
	int nowPlayID = -1;

	/** 自动切歌线程：当前歌曲播放完毕后自动播放下一首 */
	private AutoNextPlayThread anpt = null;
	/** 当前播放引擎实例 */
	private SairMP3Player player = null;
	/** Activity 引用 */
	private PlayerActivity pa = null;
	/** 音频配置管理器（持久化到 audio_config.cfg） */
	private AudioConfig audioConfig = null;

	// ==================== 统一管理的音频处理器（跨歌曲共享，单例模式） ====================

	private EQProcessor eqProcessor = null;           // 15段均衡器
	private VolumeController volumeController = null;  // 音量 + 静音
	private SurroundProcessor surroundProcessor = null; // 环绕声
	private SoundstageExpander soundstageExpander = null; // 声场扩展
	private ViperEffectsProcessor viperProcessor = null; // Viper 综合音效

	public PlayerActions(PlayerActivity pa) {
		this.pa = pa;
		this.audioConfig = new AudioConfig(pa.getDataDir());
	}

	/**
	 * 初始化所有高级音效处理器（单例模式，仅首次创建）。
	 * 
	 * <h4>为什么在 PlayerActions 中统一管理？</h4>
	 * 如果每个 SairMP3Player 各自创建 EQ/Viper 等处理器，切歌时会丢失用户的调整。
	 * 统一管理确保所有歌曲共用同一处理器实例，EQ 滑块位置和音效设置得以保留。
	 * 
	 * @param format PCM 音频格式（用于初始化滤波器和声道配置）
	 */
	public void initAdvancedEffects(javax.sound.sampled.AudioFormat format) {
		// EQ 和音量控制器是所有歌曲都需要的核心处理器
		if (eqProcessor == null)
			eqProcessor = new EQProcessor(format);
		if (volumeController == null)
			volumeController = new VolumeController();

		// 高级音效处理器：仅在启用时生效
		if (surroundProcessor == null)
			surroundProcessor = new SurroundProcessor(format);
		if (soundstageExpander == null)
			soundstageExpander = new SoundstageExpander(format);
		if (viperProcessor == null)
			viperProcessor = new ViperEffectsProcessor(format);
	}

	// ==================== 播放列表操作 ====================

	/** 向播放列表添加一首歌曲 */
	public void addPath(String path) {
		if (path != null && pa != null)
			pa.getList().addPath(path);
	}

	/** 清空播放列表 */
	public void clearAll() {
		if (pa != null)
			pa.getList().clearAll();
	}

	// ==================== 播放控制 ====================

	/**
	 * 开始播放入口（由 ActivityActions 调用）。
	 * @param index       目标序号（isContinue=true 时忽略）
	 * @param isContinue  是否继续当前暂停的歌曲（true = 继续，false = 重新播放）
	 * @param isClick     是否由鼠标点击触发（影响 UI 刷新策略）
	 * @param isIndexPlay 是否为列表索引播放模式（true 启用自动切歌，false 为单曲播放）
	 */
	public void start(int index, boolean isContinue, boolean isClick, boolean isIndexPlay) {
		if (isContinue)
			toBeContinue(); // 继续当前暂停的歌曲
		else
			whatStart(index, isClick, isIndexPlay); // 播放指定序号
	}

	/**
	 * 开始播放内部实现：刷新 UI 选中 → 启动播放。
	 * 点击触发时跳过 UI 刷新（UI 已在 Click 事件中刷新）。
	 */
	private void whatStart(int index, boolean isClick, boolean isIndexPlay) {
		if (!isClick)
			flushUI(index); // 滚动列表到当前项并高亮
		toBeStart(index, isIndexPlay);
	}

	// ==================== UI 刷新 ====================

	/**
	 * 刷新歌曲列表 UI：选中目标行并滚动到可见位置。
	 * 
	 * <h4>计算逻辑</h4>
	 * 根据 {@code (行高 × 序号) / (列表总行数 + 1)} 计算滚动偏移量，
	 * 使目标行大致出现在列表中央区域。
	 */
	public void flushUI(int index) {
		JList<String> uilist = pa.getListC();
		JScrollPane pane = pa.ui.getUIListPane();
		uilist.setSelectedIndex(index);
		int local = countLocal(index, uilist, (float) (pa.getList().listSize() + 1));
		flushSelect(pane.getViewport(), local);
	}

	/**
	 * 刷新歌词 UI：选中目标行并居中显示。
	 * <p>与普通列表不同，歌词面板需要居中显示当前行，所以偏移量减去面板高度的一半。</p>
	 */
	public void flushLRCUI(int index, int size) {
		JList<String> uilist = pa.getLrcListC();
		JScrollPane pane = pa.ui.getUILRCPane();
		uilist.setSelectedIndex(index);
		int local = (int) ((countLocal(index, uilist, (float) (size)) - (((float) pane.getHeight()) / 2)));
		flushSelect(pane.getViewport(), local);
	}

	/** 计算指定序号的行 Y 坐标：{@code 行高 × 序号} */
	private int countLocal(int index, JList<String> uilist, float listsize) {
		float cellH = (float) uilist.getHeight() / listsize;
		float now = cellH * index;
		return (int) now;
	}

	/** 设置 JScrollPane 的视口位置（滚动条位置） */
	private static void flushSelect(JViewport viewport, int local) {
		Point p = viewport.getViewPosition();
		p.setLocation(p.getX(), local);
		viewport.setViewPosition(p);
	}

	// ==================== 播放启动 ====================

	/** 从列表指定序号获取文件路径并启动播放 */
	private void toBeStart(int index, boolean isIndexPlay) {
		ListPage page = pa.getList().get(index);
		if (page != null) {
			String path = page.getPath();
			start(path, isIndexPlay, index);
		} else
			SairCons.println(FCM.Error_Color, "列表中没有此歌曲 : " + index);
	}

	/**
	 * 核心启动方法：停止当前播放 → 创建 AutoNextPlayThread 在新线程中执行播放。
	 * 
	 * @param path        文件路径
	 * @param isIndexPlay 是否为列表模式（true → 播放完自动切下一首，false → 单曲播放）
	 * @param index       列表序号（for 自动切歌时追踪位置）
	 */
	public void start(String path, boolean isIndexPlay, int index) {
		File file = new File(path);
		if (file.exists()) {
			stopChose(true, true); // 先停止当前播放
			if (isIndexPlay)
				new Thread((anpt = new AutoNextPlayThread(pa, index))).start(); // 列表模式：自动切歌
			else
				new Thread((anpt = new AutoNextPlayThread(pa, path))).start(); // 单曲模式：播完即止
		} else
			SairCons.println(FCM.Error_Color, "[" + path + "] 文件不存在!");
	}

	// ==================== 暂停 / 继续 / 停止 ====================

	/** 暂停当前播放 */
	public void pause() {
		if (player == null) return;
		player.Pause();
		SairCons.println("音乐暂停成功");
	}

	/**
	 * 停止控制和清理。
	 * @param isStopAnpt   是否停止自动切歌线程
	 * @param isStopPlayer 是否停止播放引擎并保存配置
	 */
	public void stopChose(boolean isStopAnpt, boolean isStopPlayer) {
		try {
			if (anpt != null && isStopAnpt) {
				anpt.Stop(); // 关闭自动切歌 + LRC + JSB 线程
				anpt = null;
			}
			if (player != null && isStopPlayer) {
				saveAudioConfig(); // 停止前保存音效配置
				player.Stop();
				player = null;
			}
		} catch (IOException e) {
			SairCons.println(FCM.Error_Color, "播放器停止时发生异常!");
		}
	}

	/** 继续播放：仅在可继续的暂停状态（canContinue）下有效 */
	private void toBeContinue() {
		if (player == null) return;
		if (player.canContinue()) {
			SairCons.println("已继续播放");
			player.Continue();
		} else
			SairCons.println("错误,并没有处于暂停状态,无法执行继续操作.");
	}

	/** 移除列表中的指定序号歌曲 */
	public void remove(int index) {
		PlayerList list = pa.getList();
		if (index >= 0 && index < list.listSize()) {
			ListPage page = list.remove(index);
			if (page != null) {
				SairCons.println("移除列表:" + page.getName());
				return;
			}
		}
		SairCons.println("移除列表失败!");
	}

	// ==================== 进度控制 ====================

	/** 跳转到指定进度（0.0 ~ 1.0） */
	public void setPlayPos(float mResult) {
		if (player != null)
			player.setPlayPosition(mResult);
	}

	/** @return PCM 总字节数 */
	public int getMaxSize()    { return (player != null) ? player.getMaxBytesSize() : 0; }
	/** @return 当前已播放字节数 */
	public int getNowLimit()   { return (player != null) ? player.getPlayLimit() : 0; }
	/** @return 音频总时长（秒） */
	public int getMp3MaxTime() { return (player != null) ? player.getMaxTime() : 0; }
	/** @return 当前播放进度（0.0 ~ 1.0） */
	public float getNowPos()   { return (player != null) ? player.getPlayPosition() : 0; }
	/** @return 是否正在播放 */
	public boolean isPlaying() { return player != null; }

	// ==================== 播放器实例管理 ====================

	/**
	 * 设置新的播放器实例（切歌时调用）。
	 * 
	 * <h4>执行流程</h4>
	 * <ol>
	 *   <li>停止旧的播放器实例</li>
	 *   <li>初始化/获取处理器单例</li>
	 *   <li>将所有处理器注入到新播放器中</li>
	 * </ol>
	 * 
	 * <p>配置的保存/加载已改为手动（由 UI 按钮触发），不再在切歌时自动执行。</p>
	 */
	public void setNewPlayer(SairMP3Player player) {
		if (this.player != null)
			try {
				this.player.Stop(); // 停止旧播放器
			} catch (IOException e) {
				e.printStackTrace();
			}
		this.player = player;

		// 初始化所有处理器（首次创建后后续调用为 no-op）
		if (player.getAudioFormat() != null) {
			initAdvancedEffects(player.getAudioFormat());

			// 将统一的处理器注入到新播放器
			player.setEQProcessor(eqProcessor);
			player.setVolumeController(volumeController);
			player.setSurroundProcessor(surroundProcessor);
			player.setSoundstageExpander(soundstageExpander);
			player.setViperProcessor(viperProcessor);
		}
	}

	public SairMP3Player getPlayer() { return player; }

	// ==================== 配置持久化 ====================

	/** 保存音效配置到 audio_config.cfg（停止播放时自动调用） */
	private void saveAudioConfig() {
		if (audioConfig != null && eqProcessor != null && volumeController != null) {
			// 根据 isPrint 决定是否显示保存提示
			audioConfig.saveConfig(eqProcessor, volumeController,
				surroundProcessor, soundstageExpander, viperProcessor, pa.isPrint());
		}
	}

	/** ⭐ 手动保存音效配置（供 UI 按钮调用，强制显示提示） */
	public void saveAudioConfigManual() {
		if (audioConfig != null && eqProcessor != null && volumeController != null) {
			audioConfig.saveConfig(eqProcessor, volumeController,
				surroundProcessor, soundstageExpander, viperProcessor, true);
		} else {
			SairCons.println("无法保存：音频处理器未初始化");
		}
	}

	/** ⭐ 手动加载音效配置（供 UI 按钮调用，强制显示提示） */
	public void loadAudioConfigManual() {
		if (audioConfig != null && eqProcessor != null && volumeController != null) {
			audioConfig.loadConfig(eqProcessor, volumeController,
				surroundProcessor, soundstageExpander, viperProcessor, true);
		} else {
			SairCons.println("无法加载：音频处理器未初始化");
		}
	}

	// ==================== 音效处理器访问 ====================

	public EQProcessor getEQProcessor()                { return eqProcessor; }
	public VolumeController getVolumeController()       { return volumeController; }
	public SurroundProcessor getSurroundProcessor()     { return surroundProcessor; }
	public SoundstageExpander getSoundstageExpander()   { return soundstageExpander; }
	public ViperEffectsProcessor getViperProcessor()    { return viperProcessor; }

	// ==================== 音效便捷控制 ====================

	/**
	 * 设置音量。
	 * @param volume 0.0 ~ 1.0（对应 0%~100%）
	 */
	public void setVolume(float volume) {
		VolumeController vc = getVolumeController();
		if (vc != null) {
			vc.setVolume(volume);
			SairCons.println("音量设置为: " + (int) (volume * 100) + "%");
		}
	}

	/** @return 当前音量（0.0 ~ 1.0），未初始化时返回 1.0 */
	public float getVolume() {
		VolumeController vc = getVolumeController();
		return (vc != null) ? vc.getVolume() : 1.0f;
	}

	/** 切换静音状态 */
	public void toggleMute() {
		VolumeController vc = getVolumeController();
		if (vc != null) {
			vc.toggleMute();
			SairCons.println(vc.isMuted() ? "已静音" : "已取消静音");
		}
	}

	/** 重置均衡器所有频段到 0dB */
	public void resetEQ() {
		EQProcessor eq = getEQProcessor();
		if (eq != null) {
			eq.resetEQ();
			SairCons.println("均衡器已重置");
		}
	}

	/**
	 * 设置 EQ 频段增益。
	 * @param band 频段编号（0~14）
	 * @param gain 线性增益值（1.0 = 0dB，0.5 = -6dB，2.0 = +6dB）
	 */
	public void setEQBand(int band, float gain) {
		EQProcessor eq = getEQProcessor();
		if (eq != null) {
			eq.setBandGain(band, gain);
			SairCons.println("频段 " + band + " 增益设置为: " + gain);
		}
	}

	/** @return Activity 数据目录路径 */
	public String getDataDir() {
		return this.pa.getDataDir();
	}

}
