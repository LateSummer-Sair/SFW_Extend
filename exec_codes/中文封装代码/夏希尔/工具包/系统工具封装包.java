package 夏希尔.工具包;

import java.lang.reflect.Array;

@SuppressWarnings("rawtypes")
public abstract class 系统工具封装包 extends 常量 {

	/*************************************************************************/
	// ******* 这里是封装系统给的一些转换方法以及常量 ******/
	/*************************************************************************/

	public static int 数组取长度(Object 数组) {

		if (数组 != 空对象() && 数组.getClass().isArray())

			return Array.getLength(数组);

		return 0;

	}

	public static char[] 文本转字符数组(文本型 文本) {
		if (文本 == 空对象())

			文本 = 新建文本();

		return 文本.取字符数组();// 调用String 自带的toCharArray方法来进行转换

	}

	@SuppressWarnings("unchecked")
	public static <T> T[] 新建指定对象数组(Class<?> 对象的类, int 长度大小) {
		return (T[]) Array.newInstance(对象的类, 长度大小);
	}

	public static 文本型[] 新建文本数组(int 长度大小) {
		return new 文本型[长度大小];
	}

	public static 文本型 新建文本(char... 字符数组) {
		return 文本型.新建文本(new String(字符数组));
	}

	public static void 系统调试错误输出(Object 值) {
		System.err.println(值);
	}

	public static void 系统调试输出(Object 值) {
		System.out.println(值);
	}

	public static void 系统调试高级输出(文本型 格式文本, Object... 参数值) {
		System.out.printf(空文本对象检查(格式文本).toString(), 参数值);
	}

	public static boolean 取反(boolean 值) {
		return (!值);
	}

	public static 文本型 空文本对象检查(文本型 文本) {
		if (文本 == 空对象())
			return 文本型.新建文本("");
		return 文本;
	}

	/*************************************************************************/
}
