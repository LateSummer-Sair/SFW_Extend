package com.sair.ovar;

import java.util.HashMap;

import sair.user.Activity;

public class Main extends Activity {

	public static void main(String[] args) {

		// test
		Activity main = new Main();
		sair.Main.toTest(main, "help", "");
		Object o = sair.Main.toTest(main, "add", "play pl/start 2");
		System.out.println(o);

		Object o2 = sair.Main.toTest(main, "add", "playid pl/getNowPlayID");
		System.out.println(o2);

		Object o4 = sair.Main.toTest(main, "add", "playlist pl/getListToOvar");
		System.out.println(o4);

		Object o3 = sair.Main.toTest(main, "list", "");
		System.out.println(o3);

	}

	private static final HashMap<String, Object> ovar_map = new HashMap<String, Object>();

	private static final Actions act = new Actions();

	@Override
	public Object main(String funcName, String args) {
		switch (funcName) {

		case "add": {
			return act.add(args, ovar_map);
		}

		case "del": {
			return act.del(args, ovar_map);
		}

		case "set": {
			return act.set(args, ovar_map);
		}

		case "ishas": {
			return act.ishas(args, ovar_map);
		}

		case "get": {
			return act.get(args, ovar_map);
		}

		case "list": {
			return act.list(ovar_map);
		}

		default:
			return false;
		}
	}

	@Override
	public String[] help() {
		return new String[] {

				"临时Object库v1.0", //
				getName() + "/add [name] cmd 从命令中得到的返回对象存入临时库中（添加值）", //
				getName() + "/del [name] 从库中删除指定命名的对象（此删除命令会返回被删除的值）", //
				getName() + "/set [name] cmd 从命令中得到的返回对象存入临时库中（修改值）", //
				getName() + "/ishas [name] 检查库中是否存在这个命名，返回false不存在，返回true存在（返回的是String值）", //
				getName() + "/get [name] 从库中得到指定命名的对象", //
				getName() + "/list 预览遍历库中所有的值", //
				"注意！此命令的ofunc不做object处理，而是直接返回object",//
		};
	}

	@Override
	public void exit() {
		ovar_map.clear();
	}

	public Object o_funcMain(Object var) {
		return var;
	}

}
