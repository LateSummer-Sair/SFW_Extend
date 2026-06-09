package com.sair.jj;

import java.awt.Color;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javazoom.jl.player.Player;
import sair.Pathes;
import sair.SairLoader;
import sair.sacoms.MathCast;
import sair.sacoms.SplitsJMD;
import sair.sys.SairCons;
import sair.user.Activity;

public class Main extends Activity {

	public static void main(String[] args) {
		Activity acti = new Main();

		sair.Main.toTest(acti, "cainfo", "");
		sair.Main.toTest(acti, "unstop", "");
		sair.Main.toTest(acti, "at", "A/100");
		sair.Main.toTest(acti, "at", "B/100");
		sair.Main.toTest(acti, "at", "C/100");
	}

	private LineIndexManager lim = new LineIndexManager();

	public static String au_path = null;

	private static String[] pathList = new String[] {};

	private HashSet<Player> set = new HashSet<>();

	@Override
	public Object main(String name, String args) {
		// this.myName = name;
		String end = name;

		if (au_path == null)
			initPath();

		if (end != null) {
			switch (end) {
			case "at":
				return AtPlay(args, true);
			case "atnt":
				return AtPlay(args, false);
			case "setca": {
				setCa(args);
				return true;
			}
			case "cainfo": {
				info();
				return true;
			}
			case "unstop": {
				unstop();
				return true;
			}
			case "resetline": {
				resetline();
				return true;
			}
			}
		}
		return false;
	}

	private void info() {
		SairCons.println(Pathes.printSplit);
		SairCons.println("当前的音源目录：" + au_path);
		SairCons.println(Pathes.printSplit);
		SairCons.println("可选择的音源:");
		for (int i = 0; i < pathList.length; i++) {
			SairCons.println(Color.YELLOW, "序号：" + i);
			SairCons.print(Color.BLUE, "\t目录：" + pathList[i]);
		}
		SairCons.println(Pathes.printSplit);
	}

	private void initPath() {
		String infos = SairLoader.getMOD_MEAT_INFFILE("JPDT_RES");
		if (infos == null) {
			SairCons.println("你的JPDT_RES包不正确！请使用Sair提供的，且符合这个版本的包！");
			return;
		}
		String[] infosSplit = infos.split(";");
		pathList = new String[infosSplit.length];
		for (int i = 0; i < infosSplit.length; i++)
			pathList[i] = Strs.au_fatherpath + infosSplit[i];
		if (pathList.length > 0)
			au_path = pathList[0];
	}

	private void setCa(String args) {
		int chose = MathCast.StringsIntToInt(args);
		if (chose < 0 || chose >= pathList.length) {
			SairCons.println("你选的什么玩意儿？你要不看看cainfo列表有没有这个？");
			return;
		}
		au_path = pathList[chose];
	}

	private String AtPlay(String args, boolean isPool) {
		if (EXEC.exec == null) {
			SairCons.println(Strs.NO_INIT);
			return "";
		}
		if (args == null)
			SairCons.println(Strs.ERR_ARGS_NULL);
		if (args.length() <= 0)
			SairCons.println(Strs.ERR_ARGS);
		LineData ld = LineData.getLineData(SplitsJMD.ReturnOtherRunsToJMD(null, args), lim.get());
		if (ld == null || ld.isErroLine())
			SairCons.println(Color.RED, String.valueOf(ld));
		SingleLinePlayer slp = SingleLinePlayer.getSingleLinePlayer(true, ld, set);
		if (isPool)
			EXEC.exec.execute(slp);
		else
			new Thread(slp).start();
		return "";
	}

	public void resetline() {
		lim.reset();
		SairCons.println(Strs.IS_RESET);
	}

	public void unstop() {
		if (EXEC.exec == null) {
			EXEC.exec = new ThreadPoolExecutor(// 自定义一个线程池

					20, // coreSize

					60, // maxSize

					25, // 60s

					TimeUnit.SECONDS, new ArrayBlockingQueue<>(180) // 有界队列，容量是3个

					, Executors.defaultThreadFactory()

					, new ThreadPoolExecutor.AbortPolicy()// 拒绝策略
			);
			SairCons.println(Strs.IS_CANPLAY);
		}
	}

	public String[] help() {
		return new String[] { "version:1.2", //
				"", //
				this.getName() + "/at [Name/time]: 播放指定的音源,Name为包中文件名（不包括拓展名），time为播放的时间百分比", //
				"atnt参数是一样的，只是at用线程池，atnt非线程池", //
				this.getName() + "/unstop : 启用此插件", //
				this.getName() + "/resetline : 重置行号", //
				this.getName() + "/setca [序号]: 选择设置cainfo显示的源，序号为cainfo中对应的序号", //
				this.getName() + "/cainfo : 显示当前启用的音源以及能用的源",//

		};
	}

	public void exit() {
		if (EXEC.exec != null) {
			EXEC.exec.shutdownNow();
			EXEC.exec = null;
			SairCons.println(Strs.IS_CANTPLAY);
		}
	}

	@Override
	protected String dataDir() {
		return null;
	}

}
