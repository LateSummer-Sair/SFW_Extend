package com.sair.whiles;

import java.awt.Color;
import java.io.File;
import java.util.HashMap;

import sair.sys.SairCons;
import sair.sys.tools.ToolPack;

class WhileThread extends Thread {
	private String name;
	private int count;
	private String cmds;

	private File file;
	private boolean isContinue = true;
	private HashMap<String, WhileThread> localMap;

	WhileThread(int count, String name, String cmds, HashMap<String, WhileThread> localMap) {
		this.count = count;
		this.cmds = cmds;
		this.localMap = localMap;
		this.name = name;
		chkName();
		this.localMap.put(name, this);
	}

	private void chkName() {
		WhileThread wt = localMap.get(name);
		if (wt != null)
			wt.exit();
	}

	public void run() {
		for (int i = 0; ((i < count) || (count == 0)) && isContinue; i++)
			SairCons.runner(false, "/ir " + cmds);
		exit();
	}

	public void exit() {
		isContinue = false;
		WhileThread wt = localMap.remove(name);
		if (wt == null)
			SairCons.println(Color.RED, name + " is early Exited !");
	}

	public Object getPath() {
		if (file != null)
			return file.getAbsolutePath();

		file = new File(ToolPack.pathRepack(cmds)[0]);
		if (!file.exists()) {
			file = null;
			return null;
		}
		file = file.getParentFile();
		return file.getAbsolutePath();
	}
}
