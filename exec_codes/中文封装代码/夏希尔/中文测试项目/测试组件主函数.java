package 夏希尔.中文测试项目;

import 夏希尔.夏希尔框架代理.控制台;
import 夏希尔.夏希尔框架代理.活动;
import 夏希尔.工具包.常量;
import 夏希尔.工具包.文本型;
import 夏希尔.工具包.颜色;

public class 测试组件主函数 extends 活动 {

	public static void main(String[] args) {
		测试组件主函数 活动 = new 测试组件主函数();
		文本型 函数名 = 文本型.新建文本("");
		文本型 参数 = 文本型.新建文本("你好,世界!!!");

		控制台.转到组件调试(活动, 函数名, 参数);
	}

	@Override
	public Object 入口(文本型 函数名, 文本型 参数) {
		颜色 颜色值 = 颜色.新建颜色(255, 59, 255);

		控制台.输出行(颜色值, 参数);

		return 常量.真;
	}

	@Override
	public 文本型[] 帮助() {
		return 常量.空对象();
	}

	@Override
	public void 退出方法() {

	}

}
