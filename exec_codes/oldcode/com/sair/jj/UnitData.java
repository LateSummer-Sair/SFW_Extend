package com.sair.jj;

import java.io.InputStream;
//import java.net.URL;

import sair.LoaderManager;
//import sair.LoaderManager;
//import sair.ExectionLoader;
import sair.sacoms.MathCast;

public class UnitData {

	public final static UnitData getUnitData(String comam, int col) {
		if (comam == null)
			return null;
		else
			return new UnitData(comam, col);
	}

	private UnitData(String comam, int col) {
		this.col = col;
		String[] splited = comam.split(Strs.SplitsChar);
		if (splited.length == 2) {
			chars = splited[0];
			String path = Main.au_path + chars.toUpperCase() + Strs.au_lastName;
			// Class<?> clazz = ExectionLoader.class;
			// inputstream = FileMana.getRunPack(path);

			inputstream = LoaderManager.getModResStream(path);

			int timeInt = MathCast.StringsIntToInt(splited[1]);
			if (inputstream != null && timeInt >= 10 && timeInt <= 100) {
				time = timeInt;
				isErroLine = false;
			} else {
				time = -1;
				isErroLine = true;
			}
		} else {
			chars = null;
			time = -1;
			isErroLine = true;
			inputstream = null;
		}
	}

	private InputStream inputstream;
	private String chars;
	private int time;
	private boolean isErroLine;
	private int col;

	public String getChar() {
		return chars;
	}

	public int getTime() {
		return time;
	}

	public boolean isErroLine() {
		return isErroLine;
	}

	public InputStream getInputStream() {
		return inputstream;
	}

	public String toString() {
		StringBuffer sbf = new StringBuffer().append('[').append(col).append('_').append(getChar()).append('_')
				.append(getTime()).append(']');
		return sbf.toString();
	}
}
