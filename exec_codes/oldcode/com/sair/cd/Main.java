package com.sair.cd;

import java.io.File;

import sair.sys.tools.ToolPack;
import sair.user.Activity;

public class Main extends Activity {

	public static void main(String[] args) {
		Main main = new Main();

		sair.Main.toTest(main, "help", "");
		sair.Main.toTest(main, "ls", "");
		sair.Main.toTest(main, "into", "\"E:\\linkLibs\"");
		sair.Main.toTest(main, "..", "");
		sair.Main.toTest(main, "into", "SairFrameWork");
	}

	File localPath = new File(ToolPack.getPath());
	private Actions act = new Actions();

	@Override
	public Object main(String funcName, String args) {
		switch (funcName) {
		case "..":
			return act.Return(this);
		case "ls":
			return act.ls(args, this, false);
		case "into":
			return act.ls(args, this, true);
		case "getlocal":
			return localPath.getAbsoluteFile();
		default:
			return false;
		}
	}

	@Override
	public String[] help() {
		return new String[] { //
				getName() + "/.. 回到上级目录", //
				getName() + "/ls [local_target]|[path]|null  "
						+ "\r\n\t显示local或者显示path，留空参数则显示local，非双引号参数则local下的文件夹，双引号参数则全路径",
				getName() + "/into [local_target]|[path] " + "\r\n\t进入local_target或者进入path，非双引号参数则local下的文件夹，双引号参数则全路径",
				getName() + "/getlocal 返回一个Object，返回值为localPath的String值", };
	}

	@Override
	public void exit() {
		localPath = new File(ToolPack.getPath());
	}

}
