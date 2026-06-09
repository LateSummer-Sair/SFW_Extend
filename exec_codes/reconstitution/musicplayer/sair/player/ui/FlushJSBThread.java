package sair.player.ui;

import java.awt.Color;

import sair.FCM;
import sair.player.PlayerActivity;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;

/**
 * 进度条自动刷新线程 —— 每 80ms 刷新一次 JProgressBar。
 * 
 * <h4>工作流程</h4>
 * <ol>
 *   <li>设置进度条最大值为 PCM 总字节数（{@code getMaxBytesSize()}）</li>
 *   <li>循环获取当前已播放字节数（{@code getNowLimit()}），更新进度条值</li>
 *   <li>检查 FCM 主题颜色是否改变，若改变则同步进度条样式</li>
 *   <li>首次循环时注册鼠标点击监听器（延迟绑定避免时机问题）</li>
 * </ol>
 * 
 * <h4>刷新间隔</h4>
 * 80ms（约 12.5 fps），比歌词的 200ms 更频繁以保证进度条流畅感。
 * 
 * <p>注意：循环中调用两次 wait()（共 160ms 实际间隔），设计如此。</p>
 */
public class FlushJSBThread implements Runnable {
	/** 循环控制标志 */
	private boolean iscon = true;
	/** 进度条点击监听器是否已注册 */
	private boolean isInitClick = false;

	private PlayerActivity paction;
	/** 缓存的主题颜色，用于检测变化 */
	private Color localBarColor;
	private Color localBorderColor;

	public FlushJSBThread(PlayerActivity pa) {
		this.paction = pa;
	}

	public void run() {
		// 设置进度条最大值为 PCM 总字节数
		paction.ui.getJSB().setMaximum(paction.getPA().getMaxSize());

		while (this.iscon) {
			if (paction != null && paction.getPA() != null) {
				int now;
				try {
					now = paction.getPA().getNowLimit(); // 当前已播放字节数
				} catch (Exception e) {
					now = 0;
				}
				paction.ui.getJSB().setValue(now);
				waits();
				paction.ui.getJSB().repaint();

				// 检测 FCM 主题色变化 → 同步进度条颜色
				if (localBarColor != ConsFrame.getFontColor() || localBorderColor != FCM.EXECTION_help_Color)
					reinitColor();

				// 延迟注册进度条点击监听（等待进度条组件完全初始化）
				if (!isInitClick) {
					paction.ui.getJSB().addMouseListener(new BarClick(paction.ui.getJSB(), paction.getPA()));
					isInitClick = true;
				}
			}
			waits(); // 再次等待，实际刷新间隔约 160ms
		}

		// 停止时重置进度条
		paction.ui.getJSB().setMaximum(1);
		paction.ui.getJSB().setValue(0);
	}

	/** 睡眠 80ms */
	private static void waits() {
		try {
			Thread.sleep(80L);
		} catch (InterruptedException e) {}
	}

	/** 同步进度条颜色到当前 FCM 主题 */
	private void reinitColor() {
		localBarColor = ConsFrame.getFontColor();
		localBorderColor = FCM.EXECTION_help_Color;
		paction.ui.getJSB().setForeground(localBarColor);
		paction.ui.getJSB().setBorder(new SBorder(localBorderColor));
	}

	/** 停止线程 */
	public boolean Stop() {
		this.iscon = false;
		return !this.iscon;
	}

}
