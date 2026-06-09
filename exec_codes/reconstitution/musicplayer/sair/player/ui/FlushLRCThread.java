package sair.player.ui;

import java.util.List;

import sair.player.PlayerActivity;
import sair.player.acts.PlayerActions;
import sair.sacoms.LRC;
import sair.sacoms.until.LrcLine;

/**
 * 面板歌词自动刷新线程 —— 根据播放进度实时更新 JList 歌词选中。
 * 
 * <h4>工作原理</h4>
 * <ol>
 *   <li>从 LRC 对象提取时间戳数组（秒）和文本数组</li>
 *   <li>设置 JList 的 listData 为全部歌词行</li>
 *   <li>每 200ms 轮询一次：当前时间 = 歌曲总时长 × 播放进度</li>
 *   <li>通过 {@code getApproximate()} 查找最后一条时间 ≤ 当前时间的歌词行号</li>
 *   <li>调用 {@link PlayerActions#flushLRCUI(int, int)} 滚动并高亮该行</li>
 * </ol>
 * 
 * <h4>与桌面歌词的关系</h4>
 * 该线程与 {@link FlushDesktopLRC} 同时启动（共享同一个 LRC 对象），
 * 互不干扰：面板歌词更新 JList，桌面歌词更新浮动窗口。
 * 
 * <h4>轮询间隔</h4>
 * 200ms，平衡 UI 响应速度和 CPU 开销。
 */
public class FlushLRCThread implements Runnable {
	private PlayerActivity pa;
	private LRC lrc;
	private boolean isContinue = true;
	/** 轮询间隔（毫秒） */
	private static final int whileTime = 200;

	public FlushLRCThread(LRC lrc, PlayerActivity pa) {
		this.lrc = lrc;
		this.pa = pa;
	}

	@Override
	public void run() {
		if (pa == null || lrc == null)
			return;

		// 从 LRC 对象中提取时间和文本数组
		List<LrcLine> list = lrc.getLrcLines();
		String[] listData = getListData(list);   // 歌词文本
		int[] timeData = getListTime(list);      // 时间戳（秒）

		// 设置 JList 数据源
		pa.getLrcListC().setListData(listData);

		PlayerActions pacti = pa.getPA();
		int mp3MaxTime = pacti.getMp3MaxTime();  // 歌曲总时长（秒）

		while (isContinue) {
			// 计算当前播放时间（秒）：总时长 × 播放进度
			int nowTime = (int) (((float) mp3MaxTime) * pacti.getNowPos());

			// 查找最后一条时间 ≤ 当前时间的歌词行号
			int i = getApproximate(nowTime, timeData);

			// 滚动歌词面板到对应行并高亮
			pa.getPA().flushLRCUI(i, list.size());

			try {
				Thread.sleep(whileTime);
			} catch (InterruptedException e) {}
		}
	}

	/**
	 * 提取时间戳数组。
	 * <p>LRC 的 getTime() 返回毫秒，除以 1000 转为秒。</p>
	 */
	private static int[] getListTime(List<LrcLine> list) {
		int[] datas = new int[list.size()];
		for (int i = 0; i < datas.length; i++)
			datas[i] = (int) (list.get(i).getTime() / 1000);
		return datas;
	}

	/** 提取歌词文本数组 */
	private static String[] getListData(List<LrcLine> list) {
		String[] datas = new String[list.size()];
		for (int i = 0; i < datas.length; i++)
			datas[i] = list.get(i).getLyric();
		return datas;
	}

	/**
	 * 停止歌词刷新并重置面板。
	 * <p>恢复标签文本为默认，清空 JList 数据。</p>
	 */
	public void Stop() {
		isContinue = false;
		pa.ui.getLRCLabel().setText(pa.ui.lrcShowInfo);
		if (this.pa != null) {
			pa.getLrcListC().setListData(new String[] {});
			pa.getPA().flushLRCUI(0, 1);
		}
	}

	/**
	 * 查找最后一条时间 ≤ 当前时间的歌词行号。
	 * 
	 * <h4>算法</h4>
	 * 从后往前扫描时间戳数组，返回第一个 ≤ x 的索引。
	 * 如果所有时间都 > x，返回 0（第一行）。
	 * 
	 * @param x   当前播放时间（秒）
	 * @param src 时间戳数组（秒）
	 * @return 匹配的行号
	 */
	private static int getApproximate(int x, int[] src) {
		int index = src.length - 1;
		for (; index > 0; index--) {
			if (src[index] < x)
				return index;
		}
		return 0;
	}

}
