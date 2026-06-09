package sair.player;

import java.util.List;

import javax.swing.JList;

import sair.Main;
import sair.Pathes;
import sair.player.acts.ActivityActions;
import sair.player.acts.PlayerActions;
import sair.player.acts.PlayerList;
import sair.player.ui.ConsUI;
import sair.sacoms.LRC;
import sair.sacoms.until.LrcLine;
import sair.sys.Libraries;
import sair.user.Activity;

/**
 * 音乐播放器主活动（Activity）。
 * 
 * <h3>职责</h3>
 * <ul>
 *   <li>作为 SairFramework 的 Activity 子类，注册到 {@code Libraries.activities} 中，</li>
 *   <li>持有所有核心组件引用：播放列表、播放控制、UI、歌词系统，</li>
 *   <li>通过 {@link #main(String, String)} 路由控制台命令到 {@link ActivityActions} 处理器，</li>
 *   <li>管理歌词编码（UTF-8/GB2312）、歌词偏移量、桌面歌词开关等全局状态。</li>
 * </ul>
 * 
 * <h3>命令命名空间</h3>
 * 注册名为 {@code "plt"}，所有命令格式为 {@code plt/命令 参数}。
 * 
 * @author Sair
 * @version 4.1
 */
public class PlayerActivity extends Activity {

	/**
	 * 测试入口：创建实例、注册到框架、自动打开列表/EQ/高级面板。
	 * 实际生产环境中由 SairFramework 的 {@code Main.toTest()} 机制调用。
	 */
	public static void main(String[] args) throws Exception {
		PlayerActivity cti = new PlayerActivity();
		cti.setName("plt");
		Libraries.activities.put("plt", cti);
		Main.toTest(cti, "cp", "false");
		Main.toTest(cti, "list", "");
		Main.toTest(cti, "effects", "panel");
		Main.toTest(cti, "eq", "");
		// Main.toTest(cti, "add", "\"E:\\Test\\LRCTEST\"");
	}

	/** @return 歌曲名列表 JList（中间列） */
	public JList<String> getListC() {
		return jl;
	}

	// ==================== 歌词编码配置 ====================

	/** 支持的歌词编码集：优先 UTF-8，备选 GB2312 */
	private static String[] code = { "utf-8", "gb2312" };
	/** 当前使用的歌词编码，默认 UTF-8 */
	private String nowCode = code[0];
	/** 歌词时间偏移量（秒），用于补偿 LRC 时间轴与音频实际进度的时间差 */
	private int lrcOffset = 0;

	// ==================== UI 组件（JList 裸实例） ====================

	/** 歌曲名列表（中间列，显示文件名） */
	private JList<String> jl = new JList<String>();
	/** 歌曲序号列表（左侧行头，显示 0, 1, 2...） */
	private JList<String> jlh = new JList<String>();
	/** 歌词列表（右侧歌词面板，居中显示 LRC 歌词行） */
	private JList<String> lrc = new JList<String>();

	/** 播放列表数据模型：管理 path/name/head 三个 ArrayList，同时绑定 jl 和 jlh 两个 JList */
	private PlayerList playerlist = new PlayerList(jl, jlh);

	/** 懒加载标记：首次 main() 调用时从 savelist.data 反序列化恢复播放列表，之后跳过 */
	private boolean isLoad = false;

	// ==================== 核心业务组件 ====================

	/** 播放器核心调度层：管理播放/停止/跳转、处理器绑定、配置存取 */
	private PlayerActions pa = new PlayerActions(this);
	/** 命令动作实现层：每个控制台命令对应一个公开方法 */
	private ActivityActions aa = new ActivityActions(pa, this);
	/** 当前正在播放歌曲的 LRC 文件路径（无歌词时为 null） */
	private String lrcpath = null;
	/**
	 * 桌面歌词开关：默认开启。
	 * 关闭后当前窗口隐藏，且后续切歌时不再自动弹出桌面歌词窗口。
	 */
	private boolean desktopLyricEnabled = true;

	/** 主 UI 管理器：负责布局初始化、面板单例缓存、进度条管理 */
	public final ConsUI ui = new ConsUI(this);

	/**
	 * 是否输出播放状态到控制台。
	 * 受 {@code plt/cp [true|false]} 命令控制，关闭后静默播放。
	 */
	public boolean isPrint() {
		return aa.isPrint();
	}

	/**
	 * 命令路由入口 —— 整个播放器的总调度点。
	 * <p>
	 * 首次调用时触发 {@link #firstLoad()} 从 savelist.data 恢复播放列表。
	 * 后续所有命令都通过 switch-case 分发到 {@link ActivityActions} 的对应方法。
	 * </p>
	 * 
	 * @param funcName 命令名（如 "start"、"eq"、"effects"）
	 * @param args     命令参数（可为空字符串或 null）
	 * @return 执行结果（通常为 {@code Boolean.TRUE}，未匹配时返回 false）
	 */
	@Override
	public Object main(String funcName, String args) {
		this.firstLoad();
		switch (funcName) {

		// ── 播放控制 ──
		case "start":   return aa.start(args);
		case "pause":   return aa.pause();
		case "stop":    return aa.stop();

		// ── 列表管理 ──
		case "list":       return aa.list(args);
		case "add":        return aa.add(args);
		case "remove":     return aa.remove(args);
		case "savelist":   return aa.savelist();
		case "reloadlist": return aa.reloadlist(args);

		// ── 高级功能 ──
		case "playat":       return aa.playat(args);
		case "cp":           return aa.cp(args);
		case "lrccode":      return aa.lrccode(args);
		case "lrcoffset":    return aa.lrcoffset(args);
		case "desktoplyric": return aa.desktoplyric(args);

		// ── 音效控制 ──
		case "volume":  return aa.volume(args);
		case "eq":      return aa.eq(args);
		case "effects": return aa.effects(args);

		// ── API 接口（供外部 Activity 调用） ──
		case "getListToOvar": return aa.getListToOvar();
		case "getNowPlayID":  return aa.getNowPlayID();
		case "isPlaying":     return aa.isPlaying();
		}
		return false;
	}

	/**
	 * 懒加载恢复播放列表。
	 * <p>
	 * 仅在首次 main() 调用时执行一次（isLoad 标记），
	 * 从 {@code savelist.data} 反序列化路径数组，逐条 addPath 重建列表模型。
	 * </p>
	 */
	private void firstLoad() {
		if (!isLoad) {
			String[] list = PlayerActions.firstLoad(this);
			isLoad = true;
			if (list == null)
				return;
			pa.clearAll();
			for (String path : list)
				pa.addPath(path);
		}
	}

	// ==================== Getter / Setter ====================

	public PlayerActions getPA()         { return pa; }
	public PlayerList getList()          { return playerlist; }
	public boolean isLoad()              { return isLoad; }
	public JList<String> getLrcListC()   { return lrc; }
	public JList<String> getListCHead()  { return jlh; }
	public String getLrcpath()           { return lrcpath; }
	public String getNowCode()           { return nowCode; }
	public int getLrcOffset()            { return lrcOffset; }

	public void setLrcpath(String lrcpath) { this.lrcpath = lrcpath; }

	/**
	 * 设置歌词时间偏移量。
	 * @param lrcOffset 偏移秒数（正值 = 歌词滞后显示，负值 = 歌词提前显示）
	 */
	public void setLrcOffset(int lrcOffset) { this.lrcOffset = lrcOffset; }

	/**
	 * 从 LRC 对象中提取所有歌词文本行。
	 * <p>
	 * 遍历 {@code lrc.getLrcLines()} 取出每行 {@link LrcLine#getLyric()} 文本，
	 * 存入 String[] 数组。注意：当前方法创建的数组未被持久化到字段，
	 * 仅作为数据提取工具使用。
	 * </p>
	 */
	public void setLrc(LRC lrc) {
		if (lrc == null)
			return;
		List<LrcLine> list = lrc.getLrcLines();
		String[] datas = new String[list.size()];
		for (int i = 0; i < datas.length; i++) {
			LrcLine ll = list.get(i);
			if (ll != null)
				datas[i] = ll.getLyric();
		}
	}

	/**
	 * 设置歌词文件编码。
	 * @param nowCode 1 = GB2312，其他值（含默认 0）= UTF-8
	 */
	public void setNowCode(int nowCode) {
		this.nowCode = (nowCode == 1) ? code[1] : code[0];
	}

	/** @return 播放列表持久化文件 ({@code savelist.data}) 的完整路径 */
	public String getDataFilePath() {
		return this.getDataDir() + PlayerActions.irName;
	}

	/** @return 桌面歌词功能是否启用 */
	public boolean isDesktopLyricEnabled() {
		return desktopLyricEnabled;
	}

	/**
	 * 设置桌面歌词启用状态。
	 * <p>
	 * 关闭后：当前桌面歌词窗口立即隐藏，后续切歌也不会再弹出。
	 * 开启后：下一首有 LRC 的歌曲会自动弹出桌面歌词窗口。
	 * </p>
	 */
	public void setDesktopLyricEnabled(boolean desktopLyricEnabled) {
		this.desktopLyricEnabled = desktopLyricEnabled;
	}

	// ==================== 生命周期 ====================

	/** Activity 退出时完全停止播放并释放资源 */
	@Override
	public void exit() {
		aa.stop();
	}

	// ==================== 帮助信息 ====================

	/**
	 * 返回帮助文本数组，包含所有支持的命令和用法说明。
	 * 由 SairFramework 在 {@code plt/help} 命令时调用。
	 */
	@Override
	public String[] help() {
		String name = this.getName();
		return new String[] {
			Pathes.printSplit,
			"music player V4.1",
			"Creater:Sair",
			Pathes.printSplit,
			"播放控制:",
			"\t" + name + "/start [歌曲序号] 开始播放/继续播放",
			"\t" + "(如果直接 " + name + "/start 那么就是继续播放)",
			"\t" + name + "/pause 暂停播放",
			"\t" + name + "/stop : 完全停止",

			"列表查看:",
			"\t" + name + "/list 显示列表组件",

			"列表操作:",
			"\t" + name + "/add [文件/文件夹路径] 添加歌曲",
			"\t" + name + "/remove [all] | [歌曲序号] 移除歌曲",
			"\t" + name + "/savelist 保存当前的歌曲列表",
			"\t" + name + "/reloadlist [path] 重新加载保存的歌曲列表",
			"\t" + "(如果path为空直接 " + name + "/reloadlist 那么就加载默认目录的列表)",

			"高级功能:",
			"\t" + name + "/playat [文件路径] 直接开始播放",
			"\t" + name + "/cp [true] | [false] 是否每次把播放的歌曲名输出到控制台",
			"\t" + name + "/lrcoffset [number] 设置歌词偏移量",
			"\t" + name + "/lrccode [number] 为1为GBK编码,其他1以外的就默认为UTF-8",
			"\t" + name + "/desktoplyric [on|off] 桌面歌词开关",

			"音效控制:",
			"\t" + name + "/volume [0-100] | mute 设置音量或静音",
			"\t" + name + "/eq 打开EQ面板",
			"\t" + name + "/eq reset 重置均衡器",
			"\t" + name + "/eq [0-14] [gain] 设置均衡器频段增益(-18到18dB)",
			"\t" + name + "/effects 查看高级音效帮助",
			"\t" + name + "/effects panel 打开高级音效面板",
			"\t" + name + "/effects surround mode [模式] 设置环绕声(none/stereo_expand/virtual_surround/differential/wide_stereo)",
			"\t" + name + "/effects soundstage enable [true/false] 启用声场扩展",
			"\t" + name + "/effects soundstage preset [预设] 应用声场预设(small_room/large_hall/concert/studio/wide)",
			"\t" + name + "/effects viper enable [true/false] 启用Viper音效",
			"\t" + name + "/effects viper preset [预设] 应用Viper预设(natural/bass_boost/vocal_clarity/dynamic/warm/bright)",

			"API接口:",
			"\t" + name + "/getListToOvar 获取歌曲路径集合返回",
			"\t" + name + "/getNowPlayID 获取正在播放的歌曲序号返回",
			"\t" + name + "/isPlaying 获取播放状态",
			Pathes.printSplit,
		};
	}
}
