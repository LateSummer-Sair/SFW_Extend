package 层压组胚计算器;

import java.awt.Color;
import java.awt.Dimension;
import java.math.BigDecimal;


import sair.sacoms.MathCast;
import sair.sys.gui.swing.control.SButton;

class 自定义按钮 extends SButton {

	/**
	 * 
	 */
	private static final long serialVersionUID = -1637222524364607157L;
	final String 规格;
	int 点击数;

	public 自定义按钮(String 规格, 主窗口 窗口) {
		super();
		this.规格 = 规格;
		setText("<html>" + this.规格 + "mm<br>  " + 点击数 + "张" + "<br>" + "0KG" + "</html>");
		setPreferredSize(new Dimension(110, 110));
		addActionListener(new 计算.生成按钮单击事件(窗口, this));
		setFont(主窗口.按钮字体);
		this.setForeground(Color.GREEN);
	}

	public void 刷新(double 总厚度, double 总KG) {
		double 当前厚度 = MathCast.dstringToDouble(规格) * ((double) 点击数);
		double 缓存 = 当前厚度 / 总厚度 * 总KG;
		BigDecimal b = new BigDecimal(缓存);
		setText("<html>" + 规格 + "mm <br> " + 点击数 + "张" + "<br>" + b.setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue() + "KG</html>");
	}

}
