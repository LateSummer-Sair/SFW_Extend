package sair.sacoms;

import java.io.IOException;
import java.io.Serializable;
import java.util.HashMap;


/**
 * 汉语拼音互转工具
 * <p>
 * 本工具使用简单，提供了汉字转成拼音或拼音转成汉字的方法
 * 
 * @author _Sair
 * @version PY_1.2
 * 
 **/
public class PY implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -912488800909153588L;

	private static final String PY_SPLIT = "=";
	private static String[] arr;
	static {
		try {
			String PY_PATH = "/sair/res/PY_CH.txt";
			arr = FileMana.getPackFileToStringArr(PY_PATH, FileMana.GBK);// 读取表到内存
		} catch (IOException e) {
			// SaLogger.outLogger(e);
		}
	}
	private HashMap<String, String> PtS = new HashMap<String, String>();

	private HashMap<Character, String> StP = new HashMap<Character, String>();

	/**
	 * 默认构造函数
	 * <p>
	 * 将设置为默认的关联列表<br>
	 * 
	 **/
	public PY() {
		this(null, null);
	}

	/**
	 * 用户设定的构造函数
	 * <p>
	 * 将设置成用户修改过的关联列表<br>
	 * 
	 * @param pts
	 *            设置所有汉字关联到所有罗马拼音的列表
	 * @param stp
	 *            所有汉字关联到所有罗马拼音的列表
	 **/
	public PY(HashMap<String, String> pts, HashMap<Character, String> stp) {
		if (pts != null && stp != null) {
			this.setPtS(pts);
			this.setStP(stp);
		} else
			set();
	}

	private void set() {
		if (arr != null)// 格式化数据
			for (int i = 1; i < arr.length; i++) {
				String[] carr = arr[i].split(PY_SPLIT);
				PtS.put(carr[0], carr[1]);
				for (int j = 0; j < carr[1].length(); j++) {
					Character c = carr[1].charAt(j);
					if (StP.containsKey(c))
						StP.put(c, StP.get(c) + PY_SPLIT + carr[0]);
					else
						StP.put(c, carr[0]);
				}
			}
	}

	/**
	 * 拼音转换成汉字的方法
	 * <p>
	 * 此方法将把所有与此拼音有关的汉字全部返回给用户<br>
	 * 
	 * @param py
	 *            拼音
	 * @return char[]
	 * 
	 **/
	public char[] PYgetCH(String py) {
		String pb = PtS.get(py);
		if (pb != null)
			return pb.toCharArray();
		else
			return new char[] {};
	}

	/**
	 * 汉字转换成罗马拼音的方法
	 * <p>
	 * 此方法将把与此汉字有关的读音全部返回给用户<br>
	 * 
	 * @param ch
	 *            传进的char字符
	 * @return String[]
	 **/
	public String[] CHgetPY(Character ch) {
		String pb = StP.get(ch);
		if (pb != null)
			return pb.split(PY_SPLIT);
		else
			return new String[] {};
	}

	/**
	 * 获取所有罗马拼音关联到所有汉字的列表
	 * <p>
	 * 此方法将把罗马拼音关联到所有汉字的列表全部返回给用户<br>
	 * 
	 * @return ConsHashLib<String, String>
	 **/
	public HashMap<String, String> getPtS() {
		return PtS;
	}

	/**
	 * 获取所有汉字关联到所有罗马拼音的列表
	 * <p>
	 * 此方法将把所有汉字关联到所有罗马拼音的列表全部返回给用户<br>
	 * 
	 * @return ConsHashLib<Character, String>
	 **/
	public HashMap<Character, String> getStP() {
		return StP;
	}

	/**
	 * 设置所有汉字关联到所有罗马拼音的列表
	 * <p>
	 * 此方法将重置所有汉字关联到所有罗马拼音的列表<br>
	 * 
	 * @param ptS
	 *            所有汉字关联到所有罗马拼音的列表
	 **/
	public void setPtS(HashMap<String, String> ptS) {
		PtS = ptS;
	}

	/**
	 * 设置所有汉字关联到所有罗马拼音的列表
	 * <p>
	 * 此方法将重置所有汉字关联到所有罗马拼音的列表<br>
	 * 
	 * @param stP
	 *            所有汉字关联到所有罗马拼音的列表
	 **/
	public void setStP(HashMap<Character, String> stP) {
		StP = stP;
	}
}