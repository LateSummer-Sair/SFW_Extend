package sair.keyfunc;

import java.awt.Color;
import java.io.IOException;
import java.util.HashMap;

import sair.Main;
import sair.sacoms.FileMana;
import sair.sacoms.SairLists;
import sair.sacoms.Timmer;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

public class KeyFuncMain extends Activity {

	public static void main(String[] args) throws Exception {
		final String keys = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		final String cmds = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
		final int len = keys.length();
		final String set = "set";
		final String spcenter = "=jj/at ";
		final String spend = "/100";
		Activity keyfunct = new KeyFuncMain();
		keyfunct.setName("kft");
		Main.toTest(keyfunct, "", "");
		// Main.toTest(keyfunct, "initgs", "");
		for (int i = 0; i < len; i++)
			Main.toTest(keyfunct, set, keys.charAt(i) + spcenter + cmds.charAt(i) + spend);
		Main.toTest(keyfunct, set, "[=kft/reco");
		Main.toTest(keyfunct, set, "]=kft/stopreco");
		Libraries.activities.put("kft", keyfunct);
		SairCons.runner(false, "kft/frame");
		SairCons.runner(false, "jj/unstop");
		SairCons.runner(false, "jj/setca 0");
	}

	public static final Color pc = new Color(218, 122, 56);
	public static final Color rc = new Color(56, 122, 218);

	private static final String spChar = "=";
	public static final String STOP_STR = "stopreco";
	private KeyFuncFrame kf;

	final HashMap<String, ActButton> map = new HashMap<String, ActButton>();

	final String title = "全局键盘监听器 v1.3";

	public KeyFuncMain() {
		kf = new KeyFuncFrame(this);
	}

	@Override
	public Object main(String funcName, String args) {
		switch (funcName) {

		case "frame": {
			if ("x".equals(args))
				kf.setVisible(false);
			else {
				kf.setVisible(true);
				kf.flushBT();
			}
			return true;
		}
		case "set": {
			setBT(args);
			return true;
		}

		case "remove": {
			remove(args);
			return true;
		}

		case "reco": {
			reco(args);
			return true;
		}
		case STOP_STR: {
			stopreco(args);
			return true;
		}

		}
		return false;
	}

	private void stopreco(String args) {
		kf.isreco = false;
		try {
			String path = this.getDataDir() + Timmer.getToday() + ".ir";
			SairLists<String> list = CMDTimePage.cast(kf.keyList);
			FileMana.memorriesToIO(path, list, true);
			SairCons.println(Color.GREEN, "save as : " + path);
		} catch (IOException e) {
			SairCons.println(Color.RED, e.getMessage());
		}
	}

	private void reco(String args) {
		SairCons.println(Color.GREEN, "已经开启录制功能");
		kf.keyList.clear();
		kf.isreco = true;
	}

	private void remove(String keyName) {
		if ("all".equals(keyName))
			map.clear();
		else
			map.remove(keyName);
		kf.flushBT();
	}

	private long seqi = Long.MIN_VALUE;

	private void setBT(String args) {
		String[] args_sp = args.split(spChar);
		ActButton cmd = map.get(args_sp[0]);
		if (cmd == null)
			cmd = new ActButton(args_sp[0], seqi++, this.kf);
		if (args_sp.length >= 2)
			cmd.setCMD(ToolPack.reArg(args_sp, new Integer[] { 0 }));
		map.put(args_sp[0], cmd);
		kf.flushBT();
	}

	@Override
	public String[] help() {
		return new String[] { title, " ", "set [keyName=cmd]: 给frame内设置名为key的按钮，命令指向为cmd（中间=隔开）",
				"remove [keyName | all] : 移除为keyName的按钮，参数为all时清空", "frame : 打开窗体，frame后面加参数x，就是关闭窗体",
				"reco : 录制功能,执行此命令后将开启命令触发的录制(仅录制成功触发的按键)",
				"stopreco : 关闭录制功能,关闭时会自动保存到:" + this.getDataDir() + "目录", };
	}

	@Override
	public void exit() {
		map.clear();
		kf.flushBT();
	}

}
