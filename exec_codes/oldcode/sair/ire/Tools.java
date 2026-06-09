package sair.ire;

import java.io.File;

import sair.sacoms.SairLists;
import sair.sacoms.Urler;

class Tools {
	private static final void getAllFilesPath(SairLists<File> result, File file) {
		if (file.isDirectory()) {
			File[] documentArr = file.listFiles();
			if (documentArr != null)
				for (File document : documentArr) {
					result.add(document);
					getAllFilesPath(result, document);
				}
		}
	}

	public static final SairLists<File> getJavaFiles(String dirPath) {
		SairLists<File> listFile = new SairLists<File>();
		Tools.getAllFilesPath(listFile, new File(dirPath));
		File[] files = listFile.getListArr(File.class);
		listFile = new SairLists<File>();
		for (File file : files)
			if (new Urler(file.getAbsolutePath()).getEques(".java"))
				listFile.add(file);
		return listFile;
	}
}
