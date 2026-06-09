package sair.scq.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Insets;

import javax.swing.border.AbstractBorder;

/**
 * 圆角边框实现 —— 所有控件的棱角要求圆角化。
 * 
 * <h3>使用方式</h3>
 * <pre>{@code
 * component.setBorder(new RoundedBorder(10, FCM.EXECTION_help_Color));
 * }</pre>
 */
public class RoundedBorder extends AbstractBorder {

    private int radius;
    private Color color;
    private int thickness;

    /**
     * @param radius 圆角半径
     * @param color 边框颜色
     */
    public RoundedBorder(int radius, Color color) {
        this.radius = radius;
        this.color = color;
        this.thickness = 1;
    }

    /**
     * @param radius 圆角半径
     * @param color 边框颜色
     * @param thickness 边框粗细
     */
    public RoundedBorder(int radius, Color color, int thickness) {
        this.radius = radius;
        this.color = color;
        this.thickness = thickness;
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        g.setColor(color);
        for (int i = 0; i < thickness; i++) {
            g.drawRoundRect(x + i, y + i, width - 1 - i * 2, height - 1 - i * 2, radius, radius);
        }
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(thickness + radius / 2, thickness + radius / 2,
                         thickness + radius / 2, thickness + radius / 2);
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.left = insets.right = insets.top = insets.bottom = thickness + radius / 2;
        return insets;
    }
}
