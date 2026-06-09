package 夏希尔.工具包;

public class 常量<空值载体> {
	private 空值载体 e = null;
	private static Object o;

	public final static char 空字符 = 0;

	public final static boolean 真 = true;

	public final static boolean 假 = false;

	@SuppressWarnings("unchecked")
	public static <空值载体> 空值载体 空对象() {
		if (o == null)
			return ((常量<空值载体>) (o = new 常量<空值载体>())).e;
		else
			return ((常量<空值载体>) o).e;
	}
}
