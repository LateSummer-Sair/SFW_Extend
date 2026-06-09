package 层压组胚计算器;

import sair.sys.SairCons;
import 夏希尔.夏希尔框架代理.活动;
import 夏希尔.工具包.文本型;

public class 活动主入口 extends 活动{

	public static void main(String[] 参数组) {
		SairCons.toActiRun(new 活动主入口(), "", "");
	}

	private 主窗口 窗口 = new 主窗口();
	@Override
	public Object 入口(文本型 参数1, 文本型 参数2) {
		窗口.setVisible(true);
		return false;
	}

	@Override
	public 文本型[] 帮助() {
		return 文本型.批量新建("加工件车间-层压板组胚用量计算","V1.0","@Sair");
	}

	@Override
	public void 退出方法() {
		窗口.setVisible(false);
	}

}
