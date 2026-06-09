package com.sair.tosys;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;

import sair.Main;
import sair.Pathes;
import sair.sys.SairCons;
import sair.user.Activity;

public class Tosys extends Activity {

	public static void main(String[] args) {
		Activity testActivity = new Tosys();
		Main.toTest(testActivity, "url", "http://www.baidu.com/");
		Main.toTest(testActivity, "list", "");
	}

	private final HashMap<String, CMDThread> map = new HashMap<String, CMDThread>();

	@Override
	public Object main(String funcName, String args) {
		if (CMDThread.exitfor.equals(funcName)) {
			exitfor(args);
		} else if (CMDThread.list.equals(funcName)) {
			Iterator<String> it = map.keySet().iterator();
			SairCons.println(Pathes.printSplit);
			while (it.hasNext()) {
				String name = it.next();
				CMDThread cmt = map.get(name);
				if (cmt != null && cmt.isRunning())
					SairCons.println(name);
			}
			SairCons.println(Pathes.printSplit);
		} else if (args != null && !"".equals(args)) {
			CMDThread ct = new CMDThread(funcName, args, map);
			ct.start();
		} else
			return false;
		return true;
	}

	private void exitfor(String name) {
		CMDThread cmt = map.get(name);
		if (cmt != null) {
			try {
				cmt.move();
			} catch (Exception e) {
				printER_cmtE(name);
			}
		} else
			printER_nofound(name);
	}

	@Override
	public String[] help() {
		return new String[] { //
				this.getName() + "/ [系统命令]", //
				this.getName() + "/" + CMDThread.url + " [网页链接] 抛出命令给底层系统控制台", //
				this.getName() + "/" + CMDThread.path + " [本地文件] 抛出命令给底层系统控制台", //
				this.getName() + "/" + CMDThread.list + " [命令参数] 查看正在运行中的命令线程（相同命令单例型执行）", //
				this.getName() + "/" + CMDThread.exitfor + " [命令参数] 强制退出正在运行的指定命令线程",//
		};
	}

	@Override
	public void exit() {
		Iterator<String> it = map.keySet().iterator();
		while (it.hasNext()) {
			String name = it.next();
			CMDThread cmt = map.get(name);
			try {
				if (cmt != null)
					cmt.move();
			} catch (Exception e) {
				printER_cmtE(name);
			}
		}
	}

	@Override
	protected String dataDir() {
		return null;
	}

	static void printER_nofound(String name) {
		SairCons.println(Color.RED, "N_F : " + name);
	}

	static void printER_cmtE(String name) {
		SairCons.println(Color.RED, "C_E : " + name);
	}

}
