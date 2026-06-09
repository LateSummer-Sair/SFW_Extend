package sair.player.acts;

import java.io.File;
import java.util.ArrayList;

import sair.FCM;
import sair.player.PlayerActivity;
import sair.sacoms.FileMana;
import sair.sacoms.MathCast;
import sair.sacoms.Urler;
import sair.sys.SairCons;

/**
 * 命令动作实现层 —— 每个控制台命令对应一个公开方法。
 * 
 * <h3>职责</h3>
 * <ul>
 *   <li>将 {@link PlayerActivity#main(String, String)} 的路由转换为具体业务逻辑，</li>
 *   <li>调起 {@link PlayerActions} 的播放/停止/跳转方法，</li>
 *   <li>管理播放列表的增删改查，</li>
 *   <li>管理控制台输出开关、歌词编码/偏移、桌面歌词、音量/EQ/高级音效等运行时状态。</li>
 * </ul>
 * 
 * <p>所有公开方法返回类型为 {@code Object}，这是 SairFramework Activity 的约定。</p>
 */
public class ActivityActions {

	/**
	 * 控制台输出开关：控制歌曲播放时是否在控制台打印歌曲名及相关提示。
	 * 由 {@code plt/cp [true|false]} 命令控制。
	 */
	private boolean isPrint = true;

	public boolean isPrint() {
		return isPrint;
	}

	/** 播放器调度层引用 */
	private PlayerActions pa;
	/** Activity 引用，用于访问 UI 组件和全局配置 */
	private PlayerActivity pacti;

	public ActivityActions(PlayerActions playerActivity, PlayerActivity pacti) {
		this.pa = playerActivity;
		this.pacti = pacti;
	}

	// ==================== 播放控制 ====================

	/**
	 * 开始播放或继续播放。
	 * <p>
	 * 参数为空 → 尝试继续当前暂停的歌曲（调用 {@link PlayerActions#start(int, boolean, boolean, boolean)} 的 continue 分支）；
	 * 参数为序号 → 从列表中指定序号开始播放。
	 * </p>
	 * @param args 歌曲序号（可为空表示继续）
	 */
	public Object start(String args) {
		boolean isContinue = false;
		int index = 0;
		if (null == args || "".equals(args))
			isContinue = true; // 无参数 = 继续
		else
			index = MathCast.StringsIntToInt(args); // 有参数 = 播放指定序号
		pa.start(index, isContinue, false, true);
		return true;
	}

	/** 暂停当前播放（可恢复） */
	public Object pause() {
		pa.pause();
		return true;
	}

	/** 完全停止播放并释放所有资源（不可恢复，下次需 start） */
	public Object stop() {
		pa.stopChose(true, true);
		return true;
	}

	// ==================== 列表管理 ====================

	/**
	 * 显示播放列表 UI 组件。
	 * <p>
	 * 触发 {@link ConsUI#toPrint()} 重新计算布局并通过 {@code ConsFrame.printComponent} 渲染到控制台。
	 * </p>
	 */
	public Object list(String args) {
		pacti.ui.toPrint();
		return true;
	}

	/**
	 * 添加歌曲到播放列表。
	 * <p>
	 * 支持添加单个 MP3 文件或整个文件夹（递归扫描 .mp3）。
	 * 在后台线程中逐条添加，每 5ms 间隔避免 UI 阻塞。
	 * </p>
	 * @param args 文件或文件夹路径
	 */
	public Object add(String args) {
		if (reloading) {
			SairCons.println(FCM.Error_Color, "你还有一首歌曲正在加载!");
			return true;
		}
		if (args != null) {
			Urler u = new Urler(args);
			if (u.getUrlFound()) {
				new Thread() {
					public void run() {
						reloading = true;
						SairCons.println("正在读取 " + u.getUrl() + " 请耐心等待...");
						// 递归扫描目录下所有 .mp3 文件
						ArrayList<String> list = IOTools.readMusicFilePath(u.getUrl());
						for (String path : list) {
							pa.addPath(path);
							try { Thread.sleep(5L); } catch (InterruptedException e) {}
						}
						SairCons.println("已加载 " + list.size() + " 个MP3文件");
						reloading = false;
					}
				}.start();
			} else {
				SairCons.println("add:请输入正确的文件或文件夹路径");
			}
		}
		return true;
	}

	/**
	 * 移除歌曲。
	 * @param args "all" = 清空全部，数字 = 移除指定序号
	 */
	public Object remove(String args) {
		if (reloading) {
			SairCons.println(FCM.Error_Color, "你还有一首歌曲正在加载!");
			return true;
		}
		if (args == null || "".equals(args))
			return false;
		PlayerList list = pacti.getList();
		if ("all".equals(args)) {
			list.clearAll();
			SairCons.println("已经清空列表");
			return true;
		}
		int index = MathCast.StringsIntToInt(args);
		ListPage page = list.remove(index);
		if (page != null) {
			SairCons.println(FCM.Error_Color, "已移除目标: [" + index + "] " + page.getName());
			return true;
		}
		SairCons.println(FCM.Error_Color, "没找到你选择的移除目标:" + index);
		return true;
	}

	/**
	 * 保存当前播放列表到磁盘。
	 * <p>
	 * 将所有路径收集为 String[]，序列化到 {@code savelist.data} 文件。
	 * </p>
	 */
	public Object savelist() {
		if (reloading) {
			SairCons.println(FCM.Error_Color, "你还有一首歌曲正在加载!");
			return true;
		}
		PlayerList listo = pacti.getList();
		if (listo.listSize() > 0) {
			ArrayList<String> pathList = new ArrayList<String>();
			for (int i = 0; i < listo.listSize(); i++) {
				ListPage lp = listo.get(i);
				if (lp != null)
					pathList.add(lp.getPath());
			}
			String path = pacti.getDataFilePath();
			// 删除旧文件再写入新文件（Objserialize 不会覆盖）
			if (new File(path).exists())
				try { FileMana.delFiles(path); } catch (Exception e) {}
			IOTools.saveListDataFile(path, pathList.toArray(new String[pathList.size()]));
		}
		return true;
	}

	/** 防重入标记：防止同时进行多个列表加载操作 */
	private boolean reloading = false;

	/**
	 * 从磁盘重新加载已保存的播放列表。
	 * @param args 存档文件路径（为空则使用默认 savelist.data）
	 */
	public Object reloadlist(String args) {
		if (reloading) {
			SairCons.println(FCM.Error_Color, "你还有一首歌曲正在加载!");
			return true;
		}
		boolean flag = true;
		String dataDir = new Urler(args).getUrl();
		if ("".equals(args) || args == null) {
			dataDir = pacti.getDataFilePath();
			flag = false;
		}
		String[] list = IOTools.readListDataFile(dataDir, flag);
		if (list != null && list.length > 0) {
			pacti.getList().clearAll();
			new Thread() {
				public void run() {
					reloading = true;
					for (String path : list) {
						pacti.getList().addPath(path);
						try { Thread.sleep(5L); } catch (InterruptedException e) {}
					}
					SairCons.println("重新加载列表存档完成!");
					reloading = false;
				}
			}.start();
		}
		return true;
	}

	// ==================== 高级功能 ====================

	/**
	 * 直接播放指定文件路径（不依赖列表）。
	 * @param args 音频文件的绝对路径
	 */
	public Object playat(String args) {
		Urler url = new Urler(args);
		String path = url.getUrl();
		pa.start(path, false, -1);
		return true;
	}

	/** @return 当前播放列表所有歌曲路径数组（供外部 Activity 通过 API 调用） */
	public Object getListToOvar() {
		ArrayList<String> list = new ArrayList<String>();
		PlayerList listLib = pacti.getList();
		for (int i = 0; i < listLib.listSize(); i++) {
			ListPage page = listLib.get(i);
			if (page != null) list.add(page.getPath());
		}
		return list;
	}

	/** @return 当前播放歌曲的列表序号（-1 表示无） */
	public Object getNowPlayID() {
		return pa.nowPlayID;
	}

	/** @return 是否有歌曲正在播放 */
	public boolean isPlaying() {
		return pa.isPlaying();
	}

	/**
	 * 控制台输出开关。
	 * <p>关闭后，歌曲播放/音效保存等提示不再输出到控制台。</p>
	 * @param args "false" = 关闭，其他 = 开启
	 */
	public Object cp(String args) {
		isPrint = !"false".equals(args);
		return true;
	}

	/**
	 * 设置歌词文件编码。
	 * @param args "1" = GB2312，其他 = UTF-8
	 */
	public Object lrccode(String args) {
		int chose = MathCast.StringsIntToInt(args);
		pacti.setNowCode(chose);
		return true;
	}

	/**
	 * 设置歌词时间偏移量（秒），用于修正 LRC 与音频的时间差。
	 * @param args 偏移秒数（正值 = 歌词滞后，负值 = 歌词提前）
	 */
	public Object lrcoffset(String args) {
		int chose = MathCast.StringsIntToInt(args);
		pacti.setLrcOffset(chose);
		return true;
	}

	/**
	 * 桌面歌词开关。
	 * <p>
	 * 关闭后当前桌面歌词窗口立即隐藏，后续切歌也不弹出；
	 * 开启后下一首有 LRC 的歌曲会自动弹出桌面歌词窗口。
	 * </p>
	 * @param args "off" 或 "false" = 关闭，其他 = 开启
	 */
	public Object desktoplyric(String args) {
		boolean enable = !("off".equalsIgnoreCase(args) || "false".equalsIgnoreCase(args));
		pacti.setDesktopLyricEnabled(enable);
		SairCons.println("桌面歌词: " + (enable ? "已开启" : "已关闭"));
		return true;
	}

	// ==================== 音效控制 ====================

	/**
	 * 音量控制。
	 * <p>无参数 = 查询当前音量；"mute" = 切换静音；数字 = 设置百分比音量。</p>
	 */
	public Object volume(String args) {
		if (args == null || args.isEmpty()) {
			float vol = pa.getVolume();
			SairCons.println("当前音量: " + (int) (vol * 100) + "%");
			return true;
		}
		if ("mute".equalsIgnoreCase(args) || "m".equalsIgnoreCase(args)) {
			pa.toggleMute();
			return true;
		}
		int volPercent = MathCast.StringsIntToInt(args);
		if (volPercent < 0) volPercent = 0;
		if (volPercent > 100) volPercent = 100;
		pa.setVolume(volPercent / 100.0f);
		return true;
	}

	/**
	 * 均衡器控制。
	 * <p>
	 * 无参数 → 打开图形 EQ 面板；<br>
	 * "reset" → 重置所有频段到 0dB；<br>
	 * "[频段] [dB]" → 设置指定频段增益（0-14 对应 20Hz-20kHz，-18 到 +18 dB）。
	 * </p>
	 */
	public Object eq(String args) {
		if (args == null || args.isEmpty()) {
			pacti.ui.showEQPanel();
			SairCons.println("用法: eq [reset] | [band] [gain]");
			SairCons.println("  eq reset - 重置均衡器");
			SairCons.println("  eq [0-14] [gain] - 设置频段增益 (-18到18dB)");
			return true;
		}
		if ("reset".equalsIgnoreCase(args)) {
			pa.resetEQ();
			return true;
		}
		String[] parts = args.split("\\s+");
		if (parts.length == 2) {
			int band = MathCast.StringsIntToInt(parts[0]);
			float gainDB = Float.parseFloat(parts[1]);
			if (band < 0 || band >= 15) {
				SairCons.println("频段范围: 0-14");
				return true;
			}
			// dB → 线性增益：gain = 10^(dB/20)
			float linearGain = (float) Math.pow(10.0, gainDB / 20.0);
			pa.setEQBand(band, linearGain);
		}
		return true;
	}
	
	/**
	 * 高级音效控制。
	 * <p>
	 * 无参数 → 显示帮助；<br>
	 * "panel" → 打开高级音效图形面板；<br>
	 * "surround mode [模式]" → 设置环绕声模式；<br>
	 * "soundstage enable [true|false]" → 声场扩展开关；<br>
	 * "viper enable [true|false]" → Viper 音效开关；<br>
	 * 等等。
	 * </p>
	 */
	public Object effects(String args) {
		if (args == null || args.isEmpty()) {
			printEffectsHelp();
			return true;
		}
		if ("panel".equalsIgnoreCase(args)) {
			pacti.ui.showAdvancedEffectsPanel();
			return true;
		}
		String[] parts = args.split("\\s+");
		if (parts.length < 2) {
			SairCons.println("参数不足，输入 'effects' 查看帮助");
			return true;
		}
		// 按类别分发到对应的子处理器
		switch (parts[0].toLowerCase()) {
			case "surround":   handleSurroundCommand(parts);   break;
			case "soundstage": handleSoundstageCommand(parts); break;
			case "viper":      handleViperCommand(parts);      break;
			default: SairCons.println("未知类别: " + parts[0]); break;
		}
		return true;
	}
	
	private void printEffectsHelp() {
		SairCons.println("高级音效控制面板");
		SairCons.println("用法: effects [surround|soundstage|viper] [参数]");
		SairCons.println("");
		SairCons.println("环绕声控制:");
		SairCons.println("  effects surround mode [none|stereo_expand|virtual_surround|differential|wide_stereo]");
		SairCons.println("  effects surround width [1.0-3.0]");
		SairCons.println("  effects surround mix [0.0-1.0]");
		SairCons.println("");
		SairCons.println("声场扩展控制:");
		SairCons.println("  effects soundstage enable [true|false]");
		SairCons.println("  effects soundstage preset [small_room|large_hall|concert|studio|wide]");
		SairCons.println("  effects soundstage width [0.5-3.0]");
		SairCons.println("  effects soundstage depth [0.0-2.0]");
		SairCons.println("  effects soundstage height [0.5-2.0]");
		SairCons.println("");
		SairCons.println("Viper 音效控制:");
		SairCons.println("  effects viper enable [true|false]");
		SairCons.println("  effects viper preset [natural|bass_boost|vocal_clarity|dynamic|warm|bright]");
		SairCons.println("");
		SairCons.println("打开图形界面: effects panel");
	}
	
	/**
	 * 处理环绕声子命令。
	 * <p>格式：{@code effects surround [mode|width|mix] [值]}</p>
	 */
	private void handleSurroundCommand(String[] parts) {
		if (parts.length < 3) {
			SairCons.println("用法: effects surround [mode|width|mix] [值]");
			return;
		}
		switch (parts[1].toLowerCase()) {
			case "mode":
				try {
					sair.player.audio.SurroundProcessor.SurroundMode mode = 
						sair.player.audio.SurroundProcessor.SurroundMode.valueOf(parts[2].toUpperCase());
					pa.getSurroundProcessor().setMode(mode);
					SairCons.println("环绕模式设置为: " + mode);
				} catch (Exception e) {
					SairCons.println("无效的模式，可选: none, stereo_expand, virtual_surround, differential, wide_stereo");
				}
				break;
			case "width":
				float width = Float.parseFloat(parts[2]);
				pa.getSurroundProcessor().setExpandWidth(width);
				SairCons.println("环绕宽度设置为: " + width);
				break;
			case "mix":
				float mix = Float.parseFloat(parts[2]);
				pa.getSurroundProcessor().setSurroundMix(mix);
				SairCons.println("环绕混合度设置为: " + mix);
				break;
		}
	}
	
	/**
	 * 处理声场扩展子命令。
	 * <p>格式：{@code effects soundstage [enable|preset|width|depth|height] [值]}</p>
	 */
	private void handleSoundstageCommand(String[] parts) {
		if (parts.length < 3) {
			SairCons.println("用法: effects soundstage [enable|preset|width|depth|height] [值]");
			return;
		}
		switch (parts[1].toLowerCase()) {
			case "enable":
				pa.getSoundstageExpander().setEnabled(Boolean.parseBoolean(parts[2]));
				SairCons.println("声场扩展: " + (Boolean.parseBoolean(parts[2]) ? "已启用" : "已禁用"));
				break;
			case "preset":
				pa.getSoundstageExpander().applyPreset(parts[2]);
				SairCons.println("应用声场预设: " + parts[2]);
				break;
			case "width":
				pa.getSoundstageExpander().setWidth(Float.parseFloat(parts[2]));
				SairCons.println("声场宽度设置为: " + parts[2]);
				break;
			case "depth":
				pa.getSoundstageExpander().setDepth(Float.parseFloat(parts[2]));
				SairCons.println("声场深度设置为: " + parts[2]);
				break;
			case "height":
				pa.getSoundstageExpander().setHeight(Float.parseFloat(parts[2]));
				SairCons.println("声场高度设置为: " + parts[2]);
				break;
		}
	}
	
	/**
	 * 处理 Viper 综合音效子命令。
	 * <p>格式：{@code effects viper [enable|preset] [值]}</p>
	 */
	private void handleViperCommand(String[] parts) {
		if (parts.length < 3) {
			SairCons.println("用法: effects viper [enable|preset] [值]");
			return;
		}
		switch (parts[1].toLowerCase()) {
			case "enable":
				pa.getViperProcessor().setEnabled(Boolean.parseBoolean(parts[2]));
				SairCons.println("Viper 音效: " + (Boolean.parseBoolean(parts[2]) ? "已启用" : "已禁用"));
				break;
			case "preset":
				pa.getViperProcessor().applyPreset(parts[2]);
				SairCons.println("应用 Viper 预设: " + parts[2]);
				break;
		}
	}
}
