package com.sair.whiles;

import java.awt.Color;
import java.util.HashMap;

import sair.sys.SairCons;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

public class Main extends Activity {

	public static void main(String[] args) throws InterruptedException {
		Main testActivity = new Main();
		sair.Main.toTest(testActivity, "ab", "0 E:\\Test\\Main.ir");
		sair.Main.toTest(testActivity, "ab1", "0 E:\\Test\\Main.ir");
		sair.Main.toTest(testActivity, "ab2", "0 E:\\Test\\Main.ir");
		Thread.sleep(3000L);
		testActivity.exit();
	}

	private HashMap<String, WhileThread> map = new HashMap<String, WhileThread>();

	@Override
	public Object main(String funcName, String args) {

		if ("getPath".equals(funcName)) {
			WhileThread wt = map.get(args);
			if (wt != null)
				return wt.getPath();
			else {
				SairCons.println(Color.RED, args + " : 名称不存在！");
				return null;
			}
		} else if ("exitfor".equals(funcName)) {
			exitfor(args);
			return true;
		} else if (!"".equals(funcName) && !"".equals(args)) {
			exection(funcName, args);
			return true;
		} else
			return false;
	}

	private void exection(String funcName, String args) {
		String[] local = args.split(" ");
		if (local.length < 2) {
			SairCons.println(Color.RED, "args Error! -> args length is " + local.length);
			return;
		}
		int count = 0;
		try {
			count = ToolPack.IntegerValOfString(local[0]);
		} catch (Exception e) {
			SairCons.println(Color.RED, "count Error!! -> " + local[0]);
		}

		String cmd_path = ToolPack.reArg(local, new Integer[] { 0 });

		WhileThread wt = new WhileThread(count, funcName, cmd_path, map);
		wt.start();
	}

	@Override
	public String[] help() {
		// TODO Auto-generated method stub
		return new String[] { //
				this.getName() + "/[name] [count] [path]  以循环形式执行指定脚本", //
				"[name]不能空，[count]如果为0就是死循环，[path]需要双引号路径", //
				this.getName() + "/exitfor [name] 退出指定名称定义的循环线程", //
				"\t[name] 在定义时候不能使用exitfor，因为exitfor是一个functionName，并且按照名称单例执行循环", //
				this.getName() + "/getPath [name]  获得指定名称的线程脚本路径(作为Object返回)", //
		};
	}

	@Override
	public void exit() {
		Object[] localSet = map.keySet().toArray();
		for (Object etName : localSet)
			exitfor((String) etName);
	}

	private void exitfor(String name) {
		WhileThread et = map.get(name);
		if (et == null) {
			// SairCons.println(Color.RED, name + " is nofound or early exit
			// !");
			return;
		}
		et.exit();
	}

	@Override
	protected String dataDir() {
		return null;
	}

}
