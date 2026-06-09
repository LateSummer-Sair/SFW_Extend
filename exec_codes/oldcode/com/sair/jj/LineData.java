package com.sair.jj;

import java.util.ArrayList;

public class LineData {

	public final static LineData getLineData(String comam, long line) {
		if (comam == null)
			return null;
		else
			return new LineData(comam, line);
	}

	private LineData(String comam, long line) {
		lineIndex = line;
		this.comam = comam;
		UitDatas = chkErroLineAndInitDatas(comam);
		if (UitDatas == null)
			this.isErroLine = true;
		else
			this.isErroLine = false;
	}

	private UnitData[] chkErroLineAndInitDatas(String com) {
		if (com == null)
			return null;
		if (com.length() <= 2)
			return null;
		String[] spArr = com.split(" ");
		ArrayList<UnitData> listUitData = new ArrayList<UnitData>();
		for (int i = 0; i < spArr.length; i++) {
			UnitData ud = UnitData.getUnitData(spArr[i], i);
			if (ud.isErroLine())
				return null;
			listUitData.add(ud);

		}
		return listUitData.toArray(new UnitData[listUitData.size()]);
	}

	private String comam;
	private UnitData[] UitDatas;
	private boolean isErroLine;
	private long lineIndex;

	public long getLineIndex() {
		return lineIndex;
	}

	public boolean isErroLine() {
		return isErroLine;
	}

	public UnitData[] getUitDatas() {
		return UitDatas;
	}

	public String toString() {
		StringBuffer sbf = new StringBuffer();
		boolean isApd = false;
		if (this.isErroLine()) {
			sbf.append("Error: ");
			sbf.append(lineIndex);
			sbf.append("={");
			sbf.append(comam);
		} else {
			sbf.append("Played: ");
			sbf.append(lineIndex);
			sbf.append("={");
			for (UnitData ud : UitDatas) {
				sbf.append(ud.toString());
				sbf.append(",");
				isApd = true;
			}
			if (isApd)
				sbf.deleteCharAt(sbf.length() - 1);
		}
		sbf.append('}');
		return sbf.toString();
	}
}
