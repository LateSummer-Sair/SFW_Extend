package com.sair.cd;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;

import sair.Pathes;
import sair.sys.SairCons;
import sair.sys.tools.ToolPack;

class Actions {

	Object Return(Main main) {
		File localFile = main.localPath;
		File p_path = localFile.getParentFile();
		String path = null;
		if (p_path != null) {
			path = p_path.getAbsolutePath();
			localFile = new File(path);
			main.localPath = localFile;
		}
		printFile(localFile);
		return true;
	}

	Object ls(String args, Main main, boolean isinto) {
		String localPath = ToolPack.pathRepack(args)[0];
		if ("".equals(args)) {
			printFile(main.localPath);
			return true;
		}
		if (args.equals(localPath)) {
			String path = main.localPath.getPath() + File.separator + args;
			File localFile = new File(path);
			if (localFile.exists()) {
				if (isinto)
					main.localPath = localFile;
				printFile(localFile);
			} else
				SairCons.println(Color.RED, "\"" + localPath + "\" is not found");
			return true;
		} else {
			File file = new File(localPath);
			if (!file.exists()) {
				SairCons.println(Color.RED, "\"" + localPath + "\" is not found");
				return true;
			} else if (isinto)
				main.localPath = file;
			printFile(file);
			return true;
		}
	}

	private void printFile(File local) {
		ArrayList<String> pathes = ToolPack.getAllFilesPath(local, false);
		SairCons.println(Pathes.printSplit);
		SairCons.println(Color.YELLOW, "[" + local.getPath() + "]          -> [isHidden:" + local.isHidden() + "]");
		SairCons.println(Pathes.printSplit);
		for (String path : pathes) {
			File lp = new File(path);
			if (lp.isDirectory())
				SairCons.println(Color.YELLOW, "[DIRE]\t" + lp.getName() + "\t\t-> [isHidden:" + lp.isHidden() + "]");
			else if (lp.isFile())
				SairCons.println(Color.WHITE, "[FILE]\t" + lp.getName() + "\t\t-> [isHidden:" + lp.isHidden() + "]");
		}
		SairCons.println(Pathes.printSplit);

	}

}
