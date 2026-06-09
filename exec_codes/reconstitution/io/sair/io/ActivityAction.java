package sair.io;

import java.io.IOException;
import java.net.URL;

import javax.swing.JPanel;

import sair.FCM;
import sair.io.ui.ConsUI;
import sair.sacoms.FileMana;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.tools.ToolPack;

class ActivityAction {

	public String copy(String args) {
		String[] paths = spchk(args, 2);
		if (paths == null)
			return null;
		try {
			FileMana.copyFiles(paths[0], paths[1]);
			return "fin";
		} catch (IOException e) {
			SairCons.println(FCM.Error_Color, "文件操作出错!");
		}
		return null;
	}

	public String move(String args) {
		String flag = copy(args);
		if (null != flag)
			return del(args);
		return null;
	}

	public String del(String args) {
		String[] paths = spchk(args, 1);
		if (paths == null)
			return null;
		try {
			FileMana.delFiles(paths[0]);
			return "fin";
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, "文件操作出错!");
		}
		return null;
	}

	public Object net(String args) {
		String[] paths = spchk(args, 2);
		if (paths == null)
			return null;
		URL url;
		try {
			url = new URL(paths[0]);
			FileMana.netDownloader(url, paths[1]);
			return "fin";
		} catch (IOException e) {
			SairCons.println(FCM.Error_Color, "文件操作出错!");
		}
		return null;
	}

	public Object txt(String args) {
		String[] paths = spchk(args, 1);
		if (null == paths)
			return null;
		String flag = "\"" + paths[0] + "\"";
		if (!args.equals(flag))
			args = args.replace(flag + " ", "");
		else
			args = "utf-8";
		JPanel jp = ConsUI.getTextEdt(paths[0], args);
		if (null != jp) {
			ConsFrame.printComponent(jp);
			return "fin";
		} else {
			SairCons.println(FCM.Error_Color, "文件打开失败!");
			return null;
		}
	}

	private static String[] spchk(String args, int len) {
		String[] paths = ToolPack.pathRepack(args);
		if (paths.length < len) {
			SairCons.println(FCM.Error_Color, "请检查你输入的路径!");
			return null;
		}
		return paths;
	}

}
