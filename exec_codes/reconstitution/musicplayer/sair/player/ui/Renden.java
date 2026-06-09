package sair.player.ui;

import java.awt.Component;
import java.awt.Font;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.ListCellRenderer;

/**
 * 歌词 JList 单元格渲染器。
 * 
 * <h4>渲染规则</h4>
 * <ul>
 *   <li>选中行（当前歌词）→ 使用选中前景色 + 字体放大 10pt</li>
 *   <li>未选中行 → 使用默认前景色 + 默认字体</li>
 * </ul>
 * 
 * <p>该渲染器注册到歌词 JList 上，使当前播放的歌词行自动放大并高亮，
 * 其余行为正常大小。</p>
 */
class LRCListCellRenderer extends JLabel implements ListCellRenderer<String> {

	private static final long serialVersionUID = -5523679986019468529L;

	@Override
	public Component getListCellRendererComponent(JList<? extends String> list, String value, int index,
			boolean isSelected, boolean cellHasFocus) {
		this.setText(value);
		if (isSelected) {
			// 当前歌词行：字体放大 10pt + 选中色
			Font oldFont = list.getFont();
			float size = (float) oldFont.getSize() + 10;
			Font sFont = oldFont.deriveFont(size);
			this.setForeground(list.getSelectionForeground());
			this.setFont(sFont);
		} else {
			// 普通行：默认字体 + 默认色
			this.setForeground(list.getForeground());
			this.setFont(list.getFont());
		}
		return this;
	}

}
