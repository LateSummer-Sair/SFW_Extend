package sair.scq.ui;

import java.awt.Color;
import java.awt.Component;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JList;

import sair.FCM;

/**
 * SCQ自定义列表单元格渲染器 —— 支持透明背景和消息对齐方向。
 * 
 * <h3>功能</h3>
 * <ul>
 *   <li>透明背景（setOpaque(false)）</li>
 *   <li>FCM主题色</li>
 *   <li>支持左对齐和右对齐（用于聊天消息区分自己/他人）</li>
 * </ul>
 */
public class SCListCellRenderer extends DefaultListCellRenderer {
    private static final long serialVersionUID = 1L;

    /** 水平对齐方式（SwingConstants.LEFT/CENTER/RIGHT） */
    private int horizontalAlignment;

    /** 是否透明 */
    private boolean transparent = true;

    public SCListCellRenderer() {
        this.horizontalAlignment = javax.swing.SwingConstants.LEFT;
    }

    public SCListCellRenderer(int horizontalAlignment) {
        this.horizontalAlignment = horizontalAlignment;
    }

    public void setHorizontalAlignment(int alignment) {
        this.horizontalAlignment = alignment;
    }

    public void setTransparent(boolean transparent) {
        this.transparent = transparent;
    }

    @Override
    public Component getListCellRendererComponent(JList<?> list, Object value,
            int index, boolean isSelected, boolean cellHasFocus) {

        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);

        if (transparent) {
            setOpaque(false);
        }

        setHorizontalAlignment(horizontalAlignment);

        // FCM 主题色
        if (isSelected) {
            setForeground(FCM.EXECTION_pathInfo_Color);
            setBackground(new Color(0, 0, 0, 0));
        } else {
            setForeground(FCM.EXECTION_help_Color);
            setBackground(new Color(0, 0, 0, 0));
        }

        setFont(list.getFont());
        return this;
    }
}
