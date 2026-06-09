package sair.player.acts;

import java.io.File;
import java.io.IOException;

import sair.FCM;
import sair.player.PlayerActivity;
import sair.player.ui.FlushJSBThread;
import sair.player.ui.FlushLRCThread;
import sair.player.ui.FlushDesktopLRC;
import sair.sacoms.LRC;
import sair.sacoms.Urler;
import sair.sys.SairCons;

/**
 * 自动切歌 + 播放生命周期管理线程。
 * 
 * <h3>两种运行模式</h3>
 * <ol>
 *   <li><b>列表自动切歌模式</b>（{@link #AutoNextPlayThread(PlayerActivity, int)}）：
 *       从指定序号开始，逐首播放，当前歌曲播放完毕后自动切到下一首，
 *       循环回到列表头部。连续 5 首出错后终止。</li>
 *   <li><b>单曲播放模式</b>（{@link #AutoNextPlayThread(PlayerActivity, String)}）：
 *       直接播放指定文件，播完后线程终止。</li>
 * </ol>
 * 
 * <h3>线程协作</h3>
 * 每首歌曲播放时同步启动三个辅助线程：
 * <ul>
 *   <li><b>FlushJSBThread</b> — 进度条刷新（500ms 间隔）</li>
 *   <li><b>FlushLRCThread</b> — 面板歌词刷新（200ms 间隔）</li>
 *   <li><b>FlushDesktopLRC</b> — 桌面歌词刷新（200ms 间隔，受开关控制）</li>
 * </ul>
 * 
 * @see SairMP3Player#Start() 播放主循环
 * @see FlushLRCThread 面板歌词线程
 * @see FlushDesktopLRC 桌面歌词线程
 */
public class AutoNextPlayThread implements Runnable {

	private PlayerActivity pa;
	/** 是否处于自动切歌循环中 */
	private boolean isContinue;
	/** 当前播放序号（自动切歌时递增） */
	private int localIndex;
	/** 起始序号（用于判断是否需要刷新 UI 选中） */
	private int startIndex;
	/** 面板歌词刷新线程 */
	private FlushLRCThread lrcp = null;
	/** 桌面歌词刷新线程 */
	private FlushDesktopLRC desktopLrc = null;
	/** 进度条刷新线程 */
	private FlushJSBThread fjb = null;
	/** 单曲模式的目标文件路径 */
	private String pathLocal;

	/**
	 * 列表自动切歌模式构造。
	 * @param pa    Activity 引用
	 * @param index 起始播放序号
	 */
	public AutoNextPlayThread(PlayerActivity pa, int index) {
		this.isContinue = true;
		this.pa = pa;
		this.localIndex = index;
		this.startIndex = index;
	}

	/**
	 * 单曲播放模式构造。
	 * @param pa   Activity 引用
	 * @param path 文件路径
	 */
	public AutoNextPlayThread(PlayerActivity pa, String path) {
		this.isContinue = false;
		this.pathLocal = path;
		this.pa = pa;
	}

	/** 连续错误计数器：达到 5 次则终止循环 */
	private int ecount = 0;

	/** 根据模式分发到 {@link #autoPlay()} 或 {@link #play(String)} */
	@Override
	public void run() {
		if (isContinue) {
			autoPlay(); // 列表循环模式
		} else {
			play(pathLocal); // 单曲模式
		}
	}

	/**
	 * 列表自动切歌主循环。
	 * 
	 * <h4>流程</h4>
	 * <ol>
	 *   <li>获取列表大小，空列表则退出</li>
	 *   <li>序号越界则回到 0（循环播放）</li>
	 *   <li>设置 nowPlayID → 获取 ListPage → 调用 play()</li>
	 *   <li>play() 是阻塞的（内部 player.Start() 在线程内运行直到歌曲结束）</li>
	 *   <li>歌曲结束 → play() 返回 → localIndex++ → 继续下一首</li>
	 * </ol>
	 */
	private void autoPlay() {
		PlayerActions playAction = pa.getPA();
		PlayerList list = pa.getList();
		ecount = 0;
		while (isContinue) {
			int size = list.listSize();
			if (ecount > 5 || size <= 0) {
				SairCons.println(FCM.Error_Color, "请检查你的播放列表");
				break;
			}
			if (localIndex >= size)
				localIndex = 0; // 循环到头
			playAction.nowPlayID = localIndex;
			ListPage page = list.get(localIndex);
			if (page != null)
				play(page.getPath());
			else
				break;
			localIndex++;
		}
		isContinue = false;
	}

	// ==================== 进度条辅助线程 ====================

	/** 停止进度条刷新线程 */
	private void closeFSB() {
		if (this.fjb != null) {
			this.fjb.Stop();
			this.fjb = null;
		}
	}

	/** 启动进度条刷新线程 */
	private void startFSB() {
		closeFSB();
		this.fjb = new FlushJSBThread(pa);
		new Thread(this.fjb).start();
	}

	/**
	 * 播放单首歌曲的核心方法（阻塞执行直到歌曲结束或出错）。
	 * 
	 * <h4>执行流程</h4>
	 * <ol>
	 *   <li>创建 {@link SairMP3Player} 实例（解码 + 内存加载）</li>
	 *   <li>通过 {@link PlayerActions#setNewPlayer(SairMP3Player)} 设置到调度层，
	 *       此方法内部会停止旧播放器并注入所有处理器</li>
	 *   <li>如果需要则滚动列表到当前项</li>
	 *   <li>调用 {@link #chkLRC(String)} 查找同名 LRC 歌词文件</li>
	 *   <li>启动进度条刷新线程</li>
	 *   <li>调用 {@link SairMP3Player#Start()} — 阻塞直到播放完成或停止</li>
	 *   <li>播放完成后关闭进度条和歌词线程，触发 GC</li>
	 * </ol>
	 */
	private void play(String path) {
		File file = new File(path);
		if (file.exists()) {
			PlayerActions action = pa.getPA();
			SairMP3Player player = null;
			try {
				player = new SairMP3Player(file); // 解码 + 内存加载
				action.setNewPlayer(player);       // 注入处理器并替换旧实例
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, "[" + path + "] 错误!");
				ecount++;
				return;
			}
			if (pa.isPrint())
				SairCons.println("正在播放:" + new Urler(path).getFileName());
			// 非首首歌曲且序号不是起始序号时刷新列表 UI
			if (localIndex >= 0 && localIndex < pa.getList().listSize() && localIndex != startIndex)
				action.flushUI(localIndex);
			chkLRC(path); // 查找并加载歌词
			try {
				if (player != null) {
					startFSB();         // 启动进度条刷新
					player.Start();     // 阻塞直到播放结束
					closeFSB();         // 关进度条线程
				}
				closeLRC();            // 关歌词线程
				System.gc();           // 提示 GC 回收上一个播放器的内存
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, "[" + path + "] 错误!");
				ecount++;
				return;
			}
			ecount = 0; // 播放成功，重置错误计数
		} else {
			SairCons.println(FCM.Error_Color, "[" + path + "] 不存在!");
			ecount++;
		}
	}

	// ==================== 歌词查找与加载 ====================

	/** LRC 文件名大小写组合：遍历所有可能的 .lrc 大小写变体 */
	private static final String[] locals = {
		".lrc", ".Lrc", ".lRc", ".lrC", ".LRc", ".lRC", ".LrC", ".LRC"
	};

	/**
	 * 查找与 MP3 文件同名的 LRC 歌词文件。
	 * 
	 * <h4>查找顺序</h4>
	 * <ol>
	 *   <li>先查找 {@code data/lrc/} 目录下的独立歌词文件</li>
	 *   <li>如未找到，回退到 MP3 同目录下查找</li>
	 *   <li>对每个位置尝试所有大小写变体（{@code .lrc, .Lrc, .lRc ... .LRC}）</li>
	 * </ol>
	 * 
	 * @param mp3Path MP3 文件的完整路径
	 */
	private void chkLRC(String mp3Path) {
		Urler mp3urler = new Urler(mp3Path);
		String lrcDir = pa.getDataDir() + "lrc" + File.separator;

		// 确保 lrc 目录存在
		File lcrDirFile = new File(lrcDir);
		if (!lcrDirFile.exists())
			lcrDirFile.mkdirs();

		// 两个查找位置：独立 lrc 目录 + MP3 同目录
		String[] local = {
			lrcDir + mp3urler.getFileName(),
			mp3urler.getFileInHardDisk() + mp3urler.getFileFatherUrl() + mp3urler.getFileName()
		};
		boolean flag = false;
		// 双层循环：外层遍历大小写变体，内层遍历目录位置
		baseFor: for (String l : locals) {
			for (String dl : local) {
				String lrcPath = dl + l; // 拼接完整 LRC 路径
				File file = new File(lrcPath);
				if (file.exists()) {
					flag = creatLRC(file); // 找到后立即加载并跳出双层循环
					break baseFor;
				}
			}
		}
		// 无歌词文件：显示提示信息
		if (flag == false) {
			this.pa.getLrcListC().setListData(new String[] { " ", "没有找到同名的歌词文件!" });
			this.pa.getPA().flushLRCUI(1, 100);
		}
	}

	/**
	 * 创建并启动歌词刷新线程。
	 * 
	 * <h4>线程创建</h4>
	 * <ol>
	 *   <li>创建 LRC 解析对象（使用 Activity 的当前编码设置）</li>
	 *   <li>启动面板歌词线程（{@link FlushLRCThread}）</li>
	 *   <li>根据 {@link PlayerActivity#isDesktopLyricEnabled()} 决定是否启动桌面歌词线程</li>
	 * </ol>
	 * 
	 * <p>两个歌词线程共享同一个 LRC 对象，确保解析数据一致。</p>
	 */
	private boolean creatLRC(File file) {
		try {
			closeLRC(); // 先停止旧的歌词线程

			// 使用 Activity 配置的编码创建 LRC 解析器
			LRC lrcObj = new LRC(file, pa.getNowCode());

			// 面板歌词线程：更新 JList 歌词显示
			new Thread((lrcp = new FlushLRCThread(lrcObj, pa))).start();

			// 桌面歌词线程：仅在开关启用时启动
			if (pa.isDesktopLyricEnabled()) {
				new Thread((desktopLrc = new FlushDesktopLRC(lrcObj, pa))).start();
			}
		} catch (IOException e) {
			SairCons.println(FCM.Error_Color, "歌词文件读取错误!");
			return false;
		}
		return true;
	}

	/** 停止面板歌词 + 桌面歌词线程 */
	private void closeLRC() {
		if (lrcp != null)
			lrcp.Stop();
		if (desktopLrc != null) {
			desktopLrc.Stop();
			desktopLrc = null;
		}
	}

	/**
	 * 停止自动切歌线程（由 PlayerActions.stopChose 调用）。
	 * 关闭歌词和进度条辅助线程，设置 isContinue = false 退出循环。
	 */
	public void Stop() {
		isContinue = false;
		closeLRC();
		closeFSB();
	}

}
