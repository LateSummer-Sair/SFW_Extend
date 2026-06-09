package 夏希尔.夏希尔框架代理;

import sair.user.Activity;
import 夏希尔.工具包.常量;
import 夏希尔.工具包.文本型;

public abstract class 活动 extends Activity {

	@Override
	public Object main(String funcName, String args) {
		return 入口(文本型.新建文本(funcName), 文本型.新建文本(args));
	}

	@Override
	public String[] help() {
		文本型[] 帮助文本 = 帮助();
		if (帮助文本 == 常量.空对象())
			return 常量.空对象();
		else
			return 文本型.批量解包(帮助文本);
	}

	@Override
	public void exit() {
		退出方法();
	}

	public final 文本型 取当前活动数据路径() {
		return 文本型.新建文本(getDataDir());
	}

	// #--UserRunnable
	protected String dataDir() {
		return 判断返回(设置当前活动数据路径());
	}

	private String 判断返回(文本型 设置当前活动数据路径) {
		if (设置当前活动数据路径 == 常量.空对象())
			return null;
		else
			return 设置当前活动数据路径.取值();
	}

	protected 文本型 设置当前活动数据路径() {
		return null;
	}

	public abstract Object 入口(文本型 对应函数名, 文本型 参数);

	public abstract 文本型[] 帮助();

	public abstract void 退出方法();

}
