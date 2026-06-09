package 夏希尔.工具包;

/**
 * 这个类是StringBuilder的封装类,只封装了本次要用到的功能
 */
public class 文本累加器 {
	private StringBuilder 累加器容器 = new StringBuilder();

	private 文本累加器() {
	}

	public 文本累加器 插值到(int 下标, Object 元素) {
		累加器容器.insert(下标, 元素);
		return this;
	}

	public 文本累加器 添加到末尾(Object 元素) {
		累加器容器.append(元素);
		return this;
	}

	public 文本型 转文本() {
		return 文本型.新建文本(累加器容器.toString());
	}

	public static 文本累加器 新建文本累加器() {
		return new 文本累加器();
	}
}
