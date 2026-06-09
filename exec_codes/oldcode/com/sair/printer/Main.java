package com.sair.printer;

import java.awt.Color;
import java.io.IOException;

import sair.Pathes;
import sair.sacoms.FileMana;
import sair.sacoms.Key;
import sair.sacoms.Urler;
import sair.sys.SairCons;
import sair.user.Activity;

public class Main extends Activity {

	public static void main(String[] args) throws InterruptedException {
		Main testActi = new Main();

		for (int i = 0; i < 1000; i++) {
			Thread.sleep(500L);
			System.out.println(sair.Main.toTest(testActi, "GBK", "\"E:\\guangmao.txt\""));
		}
	}

	@Override
	public void exit() {

	}

	@Override
	public String[] help() {
		return new String[] { getName() + "/[code] [filePath] 将filePath处文件以文本形式输出",
				"	code为编码格式(留空默认GBK)，filePath必须用双引号" };
	}

	@Override
	public Object main(String funcName, String args) {
		if ("".equals(args) || null == args)
			return false;
		Urler path = new Urler(args);
		if (!path.getUrlFound()) {
			SairCons.println(Color.RED, "文件不存在！");
			return true;
		}
		if ("".equals(funcName) || null == funcName)
			funcName = "GBK";
		try {
			String[] reslut = FileMana.getFileToStringArr(path.getUrl(), Key.creatKey(funcName));
			SairCons.println(Pathes.printSplit);
			SairCons.println("文件:" + path.getUrl());
			SairCons.println(Pathes.printSplit);
			for (String s : reslut)
				SairCons.println(s);
			SairCons.println(Pathes.printSplit);
		} catch (IOException e) {
			SairCons.println(Color.RED, e.getMessage());
		}
		return true;
	}

	@Override
	protected String dataDir() {
		return null;
	}

}
