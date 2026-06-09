package sair.player.ui;

import java.util.List;

import sair.player.PlayerActivity;
import sair.player.acts.PlayerActions;
import sair.sacoms.LRC;
import sair.sacoms.until.LrcLine;

/**
 * 桌面歌词刷新线程 —— 与 {@link FlushLRCThread} 同源数据（同一个 LRC 对象），
 * 每 200ms 将当前/下一行歌词渲染到 {@link DesktopLyricWindow} 浮动窗口。
 * 
 * <h4>更新逻辑</h4>
 * <ol>
 *   <li>从 LRC 提取时间和文本数组</li>
 *   <li>创建透明浮动 {@link DesktopLyricWindow}</li>
 *   <li>每 200ms 计算当前时间 → 查找匹配行 → 设置 current/next 歌词</li>
 *   <li>检查 {@link PlayerActivity#isDesktopLyricEnabled()} 开关：
 *       关闭时隐藏窗口，开启时显示（仅在有歌词内容时）</li>
 * </ol>
 * 
 * <h4>与面板歌词的差异</h4>
 * <ul>
 *   <li>面板歌词显示全部歌词行并高亮当前行</li>
 *   <li>桌面歌词仅显示当前行 + 下一行</li>
 * </ul>
 * 
 * @see FlushLRCThread 面板歌词刷新线程
 * @see DesktopLyricWindow 浮动桌面歌词窗口
 */
public class FlushDesktopLRC implements Runnable {

	private final PlayerActivity pa;
	private final LRC lrc;
	/** 线程循环控制 */
	private volatile boolean isContinue = true;
	/** 轮询间隔（ms），与 FlushLRCThread 保持一致 */
	private static final int POLL_INTERVAL = 200;

	/** 浮动歌词窗口引用 */
	private DesktopLyricWindow window;

	public FlushDesktopLRC(LRC lrc, PlayerActivity pa) {
		this.lrc = lrc;
		this.pa = pa;
	}

	@Override
	public void run() {
		if (pa == null || lrc == null)
			return;

		List<LrcLine> lineList = lrc.getLrcLines();
		if (lineList.isEmpty())
			return;

		// 提取时间和文本数组
		final int n = lineList.size();
		String[] lyrics = new String[n];
		int[] times = new int[n];
		for (int i = 0; i < n; i++) {
			lyrics[i] = lineList.get(i).getLyric();
			times[i] = (int) (lineList.get(i).getTime() / 1000); // 毫秒转秒
		}

		// 创建浮动歌词窗口（居中靠下定位）
		window = new DesktopLyricWindow();

		PlayerActions pacti = pa.getPA();
		int maxTime = pacti.getMp3MaxTime(); // 歌曲时长（秒）

		while (isContinue) {
			// ⭐ 检查桌面歌词开关（可由 plt/desktoplyric off 动态关闭）
			if (!pa.isDesktopLyricEnabled()) {
				if (window != null && window.isVisible()) {
					window.setVisible(false);
				}
				try { Thread.sleep(POLL_INTERVAL); } catch (InterruptedException e) { break; }
				continue; // 跳过本次循环，等待开关重新开启
			}

			// 计算当前播放秒数并查找匹配的歌词行
			int nowSec = (int) (((float) maxTime) * pacti.getNowPos());
			int idx = findLine(nowSec, times);

			// 设置当前行和下一行
			String cur = (idx >= 0 && idx < n) ? lyrics[idx] : "";
			String nxt = (idx + 1 >= 0 && idx + 1 < n) ? lyrics[idx + 1] : "";

			window.setLyrics(cur, nxt);

			// 首次有歌词内容时显示窗口
			if (!window.isVisible() && !cur.isEmpty()) {
				window.setVisible(true);
			}

			try {
				Thread.sleep(POLL_INTERVAL);
			} catch (InterruptedException e) {
				break;
			}
		}
	}

	/**
	 * 停止线程并关闭桌面歌词窗口（dispose 释放窗口资源）。
	 */
	public void Stop() {
		isContinue = false;
		if (window != null) {
			window.clear();
			window = null;
		}
	}

	/**
	 * 从后往前查找最后一条时间 ≤ 当前时间（秒）的歌词行号。
	 * <p>与 {@link FlushLRCThread#getApproximate} 算法一致。</p>
	 * @param x     当前播放时间（秒）
	 * @param times 时间戳数组（秒）
	 * @return 匹配的行号，未找到返回 0
	 */
	private static int findLine(int x, int[] times) {
		for (int i = times.length - 1; i > 0; i--) {
			if (times[i] < x)
				return i;
		}
		return 0;
	}
}
