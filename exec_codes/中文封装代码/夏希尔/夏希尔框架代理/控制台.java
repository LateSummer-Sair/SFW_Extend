package 夏希尔.夏希尔框架代理;

import sair.Main;
import sair.sys.SairCons;
import sair.user.Activity;
import 夏希尔.工具包.文本型;
import 夏希尔.工具包.系统工具封装包;
import 夏希尔.工具包.颜色;

public class 控制台 extends 系统工具封装包 {
	public static void 清屏() {
		SairCons.clear();
	}

	public static void 输出行(颜色 字体颜色, 文本型 文本) {
		SairCons.println(字体颜色, 空文本对象检查(文本).取值());
	}

	public static void 输出行(文本型 文本) {
		SairCons.println(空文本对象检查(文本).取值());
	}

	public static void 输出(文本型 文本) {
		SairCons.print(空文本对象检查(文本).取值());
	}

	public static void 输出(颜色 字体颜色, 文本型 文本) {
		SairCons.print(字体颜色, 空文本对象检查(文本).取值());
	}

	public static void 转到组件调试(Activity 活动, 文本型 调试函数名, 文本型 参数) {
		Main.toTest(活动, 空文本对象检查(调试函数名).取值(), 空文本对象检查(参数).取值());
	}
}
