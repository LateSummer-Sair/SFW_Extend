package 夏希尔.工具包;

import java.awt.Color;

public class 颜色 extends Color {
	/**
	 * 
	 */
	private static final long serialVersionUID = -6831478069106116544L;

	private 颜色(int 红, int 绿, int 蓝, int 阿尔法值) {
		super(红, 绿, 蓝, 阿尔法值);
	}

	private 颜色(int 红, int 绿, int 蓝) {
		super(红, 绿, 蓝);
	}

	public final static 颜色 新建颜色(int 红, int 绿, int 蓝, int 阿尔法值) {
		return new 颜色(红, 绿, 蓝, 阿尔法值);
	}

	public final static 颜色 新建颜色(int 红, int 绿, int 蓝) {
		return new 颜色(红, 绿, 蓝);
	}

	public final static 颜色 颜色对象转颜色(Color 颜色对象) {
		if (颜色对象 == 常量.空对象())
			return 常量.空对象();
		return new 颜色(颜色对象.getRed(), 颜色对象.getGreen(), 颜色对象.getBlue(), 颜色对象.getAlpha());
	}

	public final static 颜色[] 颜色对象批量转化(Color... 颜色对象) {
		int 长度大小 = 系统工具封装包.数组取长度(颜色对象);
		颜色[] 颜色数组 = 系统工具封装包.新建指定对象数组(颜色.class, 长度大小);
		for (int 下标 = 0; 下标 < 长度大小; 下标++)
			颜色数组[下标] = 颜色对象转颜色(颜色对象[下标]);
		return 颜色数组;
	}

}
