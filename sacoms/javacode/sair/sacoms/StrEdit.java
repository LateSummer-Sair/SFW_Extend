package sair.sacoms;

import java.io.IOException;
import java.io.InputStream;

import sair.sacoms.until.SLPage;

/**
 * String类型编辑器
 * <p>
 * 提供了String类型编辑工具<br>
 * 
 * @author _Sair
 * @version StrEdit_1.3
 * 
 **/
public class StrEdit {
	/**
	 * 行删除模式
	 **/
	public final static Key<StrEdit> LINEMODE = Key.creatKey();
	/**
	 * 单删除模式
	 **/
	public final static Key<StrEdit> ONEMODE = Key.creatKey();
	private String[] Dir, carr;
	private int cDir;
	private Key<StrEdit> key;
	private SLPage pageCache;
	private StringBuffer sbf;

	/**
	 * 初始化
	 * <p>
	 * String类型编辑工具初始化函数<br>
	 * 
	 * @param dir
	 *            传入的筛选黑名单---String[]
	 **/
	public StrEdit(String... dir) {
		setDir(dir);
	}

	/**
	 * 设置黑名单
	 * <p>
	 * String类型编辑工具初始化函数<br>
	 * 
	 * @param dir
	 *            传入的筛选黑名单---String[]
	 **/
	public void setDir(String... dir) {
		Dir = dir;
	}

	private void setCDir(int id) {
		cDir = id;
	}

	private void setKey(Key<StrEdit> key) {
		this.key = key;
	}

	private void setPage(SLPage v) {
		pageCache = v;
		Object o = pageCache.getIndex();
		String cc = null;
		if (o != null)
			cc = (String) o;
		if (v != null)
			carr = splitStr(cc);
	}

	/**
	 * 开始筛选
	 * <p>
	 * 设置传入参数开始分析，传入参数为SairLists<String>类型<br>
	 * 
	 * @param list
	 *            需要被操作的SairLists集合
	 * @param key
	 *            筛选模式钥匙
	 * @return SairLists<String>类型
	 **/
	public SairLists<String> toRemove(SairLists<String> list, Key<StrEdit> key) {
		setKey(key);
		for (int i = 0; i < list.getLength(); i++)
			until(list.getPageIndex(i));
		return list;
	}

	private void until(SLPage v) {
		setPage(v);
		for (int i = 0; i < Dir.length; i++)
			if (untilS(i) == true)
				break;
	}

	private boolean untilS(int id) {
		setCDir(id);
		int allen = 0, dllen = 0, cllen = 0;
		if (Dir[cDir] != null && Dir[cDir].length() > 0) {
			dllen = Dir[cDir].length();
			cllen = carr.length;
			allen = cllen - (dllen - 1);
		} else
			return true;
		for (int i = 0; i < allen; i++) {
			sbf = new StringBuffer();
			for (int j = 0; j < dllen; j++)
				sbf.append(carr[i + j]);
			if (LINEMODE == key && sbf.toString().equals(Dir[cDir])) {
				pageCache.setIndex("");
				return true;
			} else if (ONEMODE == key && sbf.toString().equals(Dir[cDir]))
				for (int j = 0; j < dllen; j++)
					carr[i + j] = "";
		}
		pageCache.setIndex(toStr(carr, false));
		return false;
	}

	/**
	 * 合并方法
	 * <p>
	 * 设置传入参数开始分析，传入参数为Object[]类型<br>
	 * 
	 * @param sc
	 *            需要被合并的字符(串)
	 * @param line
	 *            是否换行
	 * @return String类型
	 **/
	public static String toStr(Object[] sc, boolean line) {
		if (sc == null)
			return "";
		StringBuffer back = new StringBuffer();
		String ca = "";
		if (line)
			ca = "\r\n";
		for (int i = 0; i < sc.length; i++)
			back.append(String.valueOf(sc[i]) + ca);
		return back.toString();
	}

	/**
	 * 切割方法
	 * <p>
	 * 设置传入参数开始分析，传入参数为String类型<br>
	 * 
	 * @param str
	 *            需要被分割的String字符串
	 * @return String[]类型
	 **/
	public static String[] splitStr(String str) {
		if (str == null)
			return new String[0];
		String[] back = new String[str.length()];
		for (int i = 0; i < back.length; i++)
			back[i] = String.valueOf(str.charAt(i));
		return back;
	}

	/**
	 * 批量转换方法
	 * <p>
	 * 设置传入参数开始分析，传入参数为String类型<br>
	 * 
	 * @param str
	 *            需要被分析转化的Object[]
	 * @return String[]类型
	 **/
	public static String[] castStr(Object[] str) {
		if (str == null)
			return new String[0];
		String[] back = new String[str.length];
		for (int i = 0; i < back.length; i++)
			back[i] = String.valueOf(str[i]);
		return back;
	}

	/**
	 * 获得DataInputStream的字符串
	 * <p>
	 * 
	 * @param dis
	 *            DataInputStream网络流对象
	 * @param byteSize
	 *            获取的byte数组大小
	 * @return String类型
	 **/
	public static String getNetDataString(InputStream dis, int byteSize) throws IOException {
		if (byteSize <= 0)
			byteSize = 4096;
		byte[] buf = new byte[byteSize];
		int len = dis.read(buf);
		if (len == -1)
			return null;
		return new String(buf, 0, len);
	}

}
