package sair.player.ui;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.JList;
import javax.swing.JProgressBar;

import sair.player.PlayerActivity;
import sair.player.acts.PlayerActions;

/**
 * 歌曲列表鼠标事件处理 —— 双击左键播放，双击右键移除。
 * 
 * <p>注册在 {@link ConsUI#chkInit} 中，作为歌曲名 JList 的 MouseListener。</p>
 */
class ListClick extends java.awt.event.MouseAdapter {
	private JList<String> jl;
	private PlayerActivity pa;

	public ListClick(JList<String> jl, PlayerActivity pa) {
		this.jl = jl;
		this.pa = pa;
	}

	@Override
	public void mouseClicked(MouseEvent e) {
		// 左键双击 → 播放选中歌曲
		if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() == 2) {
			int index = jl.getSelectedIndex();
			if (index < pa.getList().listSize() && index >= 0)
				pa.getPA().start(index, false, true, true);
		}
		// 右键双击 → 移除选中歌曲
		else if (e.getButton() == MouseEvent.BUTTON3 && e.getClickCount() == 2) {
			int index = jl.getSelectedIndex();
			if (index < pa.getList().listSize() && index >= 0)
				pa.getPA().remove(index);
		}
	}
}

/**
 * 进度条鼠标点击处理 —— 点击位置映射为播放进度。
 * 
 * <h4>计算公式</h4>
 * <pre>
 *   target = clickX / barWidth      (0.0 ~ 1.0)
 * </pre>
 * 通过 {@link PlayerActions#setPlayPos(float)} 触发 {@link SairMP3Player#setPlayPosition(float)}
 * 实现 pause → 重定位 → continue 的跳转流程。
 */
class BarClick extends MouseAdapter {
	private JProgressBar jb;
	private PlayerActions paction;

	BarClick(JProgressBar jb, PlayerActions paction) {
		this.jb = jb;
		this.paction = paction;
	}

	public void mousePressed(MouseEvent e) {
		float max = jb.getWidth();       // 进度条像素宽度
		float mx = e.getX();             // 点击 X 坐标
		float mResult = mx / max;        // 归一化 0.0~1.0
		paction.setPlayPos(mResult);     // 委托跳转
	}
}
