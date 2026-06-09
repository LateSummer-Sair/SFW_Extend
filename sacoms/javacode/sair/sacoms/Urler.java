package sair.sacoms;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

import sair.sys.tools.ToolPack;

/**
 *
 * <p>
 * JMD_URL-PATH路径纠正("//"---双/可以代表空格，自动删除路径中的英文双引号) <br>
 * (支持相对路径，相对路径为SaComS目录)<br>
 * 
 * @author _Sair
 * @version Urler_2.7
 * 
 **/

public class Urler implements Serializable {
	/**
	 * 
	 */

	private static final String[] OS = new String[] { "Windows", "Linux", "Android" };
	private static final long serialVersionUID = -2717062325496256909L;
	private File file;
	private File fileFather;

	/**
	 * 获取系统类型
	 * <p>
	 * 利用文件路径的特性获取当前使用的系统类型<br>
	 * 
	 * @return String类型
	 **/
	public static String getOS() {
		if (new Urler("C:\\").getHDFound())
			return OS[0];
		else {
			if (new Urler("/sdcard").getHDFound())
				return OS[2];
			else
				return OS[1];
		}
	}

	/**
	 * 转换多条
	 * <p>
	 * 将多条String_Url转换为_Urler对象<br>
	 * 
	 * @param Urls
	 *            多条路径--String[]
	 * @return _Urler[]类型
	 **/
	public static Urler[] getUrlers(String[] Urls) {
		if (Urls == null)
			return new Urler[0];
		int len = Urls.length;
		Urler[] urls = new Urler[len];
		for (int i = 0; i < len; i++)
			urls[i] = new Urler(Urls[i]);
		return urls;
	}

	/**
	 * 转换多条
	 * <p>
	 * 将SairLists<String>转换为SairLists<Urler>对象<br>
	 * 
	 * @param Urls
	 *            多条路径--SairLists<String>
	 * @return SairLists<Urler>类型
	 **/
	public static SairLists<Urler> getUrlsToList(SairLists<String> Urls) {
		return getUrlsToList(StrEdit.castStr(Urls.getListObjArr()));
	}

	/**
	 * 转换多条
	 * <p>
	 * 将多条String_Url转换为SairLists<Urler>对象<br>
	 * 
	 * @param Urls
	 *            多条路径--String[]
	 * @return SairLists<Urler>类型
	 **/
	public static SairLists<Urler> getUrlsToList(String[] Urls) {
		return new SairLists<Urler>().setArrToList(getUrlers(Urls));
	}

	private String URL, fileInHardDisk, fileFatherUrl, fileName, fileLastName;
	private int ends;
	private String[] arrCache;

	/**
	 * 初始化
	 * <p>
	 * 初始化整个类型,传入参数为String<br>
	 * 
	 * @param url
	 *            路径--String
	 **/
	public Urler(String url) {
		setURL(url);
	}

	/**
	 * 初始化
	 * <p>
	 * 初始化整个类型<br>
	 **/
	public Urler() {
	}

	private void run() {
		if (URL == null)
			return;
		toUrlMake();
		toUrlSplit();
		URL = fileInHardDisk + fileFatherUrl + fileName + fileLastName;
		file = new File(getUrl());
		fileFather = new File(getHD());
	}

	private void toUrlSplit() {
		int i;
		for (i = 0; i < arrCache.length; i++) {
			if (File.separator.equals(arrCache[i]))
				break;
			fileInHardDisk = fileInHardDisk + arrCache[i];
		}
		for (; i < ends; i++)
			fileFatherUrl = fileFatherUrl + arrCache[i];
		boolean hasCode = false;
		for (i = arrCache.length - 1; i >= ends; i--) {
			fileLastName = arrCache[i] + fileLastName;
			if (".".equals(arrCache[i])) {
				hasCode = true;
				break;
			}
		}
		if (hasCode == true)
			i--;
		else {
			i = arrCache.length - 1;
			fileLastName = "";
		}
		for (; i >= ends; i--)
			fileName = arrCache[i] + fileName;
	}

	private void toUrlMake() {
		fileInHardDisk = fileFatherUrl = fileName = fileLastName = "";
		arrCache = StrEdit.splitStr(URL);
		for (int i = 0; i < arrCache.length; i++) {
			if (i < arrCache.length - 1) {
				if ("//".equals(arrCache[i] + arrCache[i + 1])) {
					arrCache[i] = "";
					arrCache[i + 1] = " ";
				}
			}
			if ("\"".equals(arrCache[i]))
				arrCache[i] = "";
			if ("\\".equals(arrCache[i]) || "/".equals(arrCache[i])) {
				arrCache[i] = File.separator;
				ends = i + 1;
			}
		}
	}

	/**
	 * 设置初始化路径
	 * <p>
	 * 设置传入参数开始分析，传入参数为String类型<br>
	 * 
	 * @param url
	 *            路径--String
	 **/
	public void setURL(String url) {
		if (url == null)
			return;
		StringBuffer sbf = new StringBuffer(url);
		if (sbf.length() <= 0)
			sbf.append("." + File.separator);
		if (sbf.length() >= 2 && sbf.charAt(0) == '.' && (sbf.charAt(1) == '\\' || sbf.charAt(1) == '/')) {
			sbf.deleteCharAt(0);
			sbf.insert(0, Urler.getPath());
		}

		URL = sbf.toString();
		run();
	}

	/**
	 * 获取路径盘符
	 * <p>
	 * 获取路径存在的硬盘盘符（仅盘符）<br>
	 * 
	 * @return String类型
	 **/
	public String getFileInHardDisk() {
		return fileInHardDisk;
	}

	/**
	 * 获取父级路径
	 * <p>
	 * 获取路径存在的父级路径（仅父级路径）<br>
	 * 
	 * @return String类型
	 **/
	public String getFileFatherUrl() {
		return fileFatherUrl;
	}

	/**
	 * 获取文件名称
	 * <p>
	 * 获取路径存在的文件名称（仅名称）<br>
	 * 
	 * @return String类型
	 **/
	public String getFileName() {
		return fileName;
	}

	/**
	 * 获取文件拓展名
	 * <p>
	 * 获取路径存在的文件拓展名（仅拓展名）<br>
	 * 
	 * @return String类型
	 **/
	public String getFileLastName() {
		return fileLastName;
	}

	/**
	 * 获取调整纠正后的路径
	 * <p>
	 * 获取路径调整纠正后的路径<br>
	 * 
	 * @return String类型
	 **/
	public String getUrl() {
		return URL;
	}

	/**
	 * 获取父级路径
	 * <p>
	 * 获取路径存在的父级路径（包括所在的盘符）<br>
	 * 
	 * @return String类型
	 **/
	public String getHD() {
		return fileInHardDisk + fileFatherUrl;
	}

	/**
	 * 获取文件名称
	 * <p>
	 * 获取路径存在的文件名称（包括拓展名）<br>
	 * 
	 * @return String类型
	 **/
	public String getF() {
		return fileName + fileLastName;
	}

	/**
	 * 获取整个路径的存在
	 * <p>
	 * 获取整个路径的存在，不存在返回false，存在返回true<br>
	 * 
	 * @return boolean类型
	 **/
	public boolean getUrlFound() {
		return new File(URL).exists();
	}

	/**
	 * 获取父级路径的存在
	 * <p>
	 * 获取父级路径的存在，不存在返回false，存在返回true<br>
	 * 
	 * @return boolean类型
	 **/
	public boolean getHDFound() {
		return new File(getHD()).exists();
	}

	/**
	 * 创建整个路径
	 * <p>
	 * 创建整个路径，不成功返回false，成功返回true<br>
	 * 
	 * @return boolean类型
	 **/
	public boolean creatThisUrl() throws IOException {
		creatThisHD();
		if (!"".equals(getF()) && file != null)
			return file.createNewFile();
		return false;
	}

	/**
	 * 创建父级路径
	 * <p>
	 * 创建当前完整路径的父级路径，不成功返回false，成功返回true<br>
	 * 
	 * @return boolean类型
	 **/
	public boolean creatThisHD() throws IOException {
		if (fileFather == null)
			return false;
		return fileFather.mkdirs();
	}

	/**
	 * 获取比较结果
	 * <p>
	 * 获取当前路径文件拓展名的比较结果（需要传入一个用来比较的String值），不相同返回false，相同返回true<br>
	 * 
	 * @param LastName
	 *            文件名后缀--String
	 * @return boolean类型
	 **/
	public boolean getEques(String LastName) {
		if ((getFileLastName().toLowerCase()).equals(LastName.toLowerCase()))
			return true;
		return false;
	}

	/**
	 * 获取系统表
	 * <p>
	 * 获取当前支持的所有系统类型表<br>
	 * 
	 * @return String[]类型
	 **/
	public static String[] getOSlist() {
		return OS;
	}

	/**
	 * 获取当前jar/class的完整路径(兼容Linux但不适用于安卓)
	 * 
	 * @return String 类型
	 */

	public static String getPath() {
		return ToolPack.getPath();
	}

	/**
	 * 将多条正常路径转换成JMD专用路径
	 * 
	 * @param pathes
	 *            需要被转换的值数组
	 * @return String[] 类型
	 */
	public static String[] getUrlerPathesForJMD(String[] pathes) {
		if (pathes == null)
			return new String[] {};
		String[] back = new String[pathes.length];
		for (int i = 0; i < pathes.length; i++)
			back[i] = getUrlerPathForJMD(pathes[i]);
		return back;
	}

	/**
	 * 将一条正常路径转换成JMD专用路径
	 * 
	 * @param path
	 *            需要被转换的值
	 * @return String 类型
	 */
	public static String getUrlerPathForJMD(String path) {
		String[] arrc = StrEdit.splitStr(path);
		for (int i = 0; i < arrc.length; i++)
			if (" ".equals(arrc[i]))
				arrc[i] = "//";
		return StrEdit.toStr(arrc, false);
	}

}
