package com.sair.cms;

import sair.sacoms.MathCast;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.user.Activity;

public class CMS extends Activity {

	public static void main(String[] args) {
		CMS testActivity = new CMS();
		Libraries.activities.put("testActivity", testActivity);
		sair.Main.toTest(testActivity, "frame", "");
		sair.Main.toTest(testActivity, "setdefaultclose", "");
		SairCons.runner(false, "/hide");

	}

	private CMSFrame cms = new CMSFrame(this);

	@Override
	public Object main(String funcName, String args) {
		switch (funcName) {
		case "int2ch": {
			return int2ch(args);
		}
		case "ch2int": {
			return ch2int(args);
		}
		case "setdefaultclose": {
			return cms.setCMS(null);
		}
		case "setundefaultclose": {
			return cms.setCMS(this);
		}
		case "frame": {
			cms.setVisible(true);
			return false;
		}
		default:
			return false;
		}
	}

	static final Number ch2int(String args) {
		SairCons.println("intput: " + args);
		Number result;
		if (!args.contains("点"))
			result = MathCast.StrChCustoLong(args);
		else
			result = MathCast.StringToDouble(args);
		SairCons.println("output: " + String.valueOf(result));
		return result;
	}

	static final String int2ch(String args) {
		SairCons.println("intput: " + args);
		String reslut;
		if (!args.contains("."))
			reslut = MathCast.custoString(args, MathCast.ToSmallChinese);
		else
			reslut = MathCast.DoubleToString(MathCast.dstringToDouble(args), MathCast.ToSmallChinese);
		SairCons.println("output: " + reslut);
		return reslut;
	}

	@Override
	public String[] help() {
		String name = this.getName();
		return new String[] { //
				name + "/int2ch 数字转汉字", //
				name + "/ch2int 汉字转数字", //
				name + "/frame 显示GUI", //
				name + "/setdefaultclose 设置关闭此GUI默认关闭虚拟机", //
				name + "/setundefaultclose 取消设置关闭此GUI默认关闭虚拟机", //
		};
	}

	@Override
	public void exit() {
		cms.setVisible(false);
	}

}
