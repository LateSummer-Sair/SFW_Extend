package sair.sacoms;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import sair.sacoms.until.Desu;
import sair.sacoms.until.Im;
import sair.sacoms.until.Mi;

/**
 *
 * <p>
 * IO文件读写<br>
 * 
 * @author _Sair
 * @version editer_3.0
 * 
 **/

public class FileMana {

	/**
	 * 获取当前所有文件、文件夹钥匙
	 **/
	public final static Key<FileMana> GETALL = Key.creatKey();
	/**
	 * 获取所有文件夹钥匙
	 **/
	public final static Key<FileMana> GETDIR = Key.creatKey();
	/**
	 * 获取所有文件钥匙
	 **/
	public final static Key<FileMana> GETFILE = Key.creatKey();
	/**
	 * 编码，GBK
	 **/
	public final static Key<String> GBK = Key.creatKey("gb2312");
	/**
	 * 编码，UTF-8
	 **/
	public final static Key<String> UTF_8 = Key.creatKey("utf-8");

	/**
	 * 用户自定义编码
	 **/
	public final static Key<String> CreateNewE(String enc) {
		return Key.creatKey(enc);
	}

	private static Im itm = new Im();
	private static Mi mti = new Mi();
	private static Desu de = new Desu();

	/**
	 * 文件删除
	 * <p>
	 * 删除指定目录的文件（夹）<br>
	 * 
	 * @param Url
	 *            目標路徑，绝对路径--String
	 * @throws Exception
	 **/
	public static void delFiles(String Url) throws Exception {
		de.del(Url);
	}

	/**
	 * 文件复制
	 * <p>
	 * 复制指定目录的文件（夹）到另一个指定目录<br>
	 * 
	 * @param inUrl
	 *            目標路徑，绝对路径--String
	 * @param outUrl
	 *            输出路徑，绝对路径--String
	 * @throws IOException
	 **/
	public static void copyFiles(String inUrl, String outUrl) throws IOException {
		de.copy(inUrl, outUrl);
	}

	/**
	 * 文件下载
	 * <p>
	 * 复制指定网络地址的文件到另一个指定目录<br>
	 * 
	 * @param inUrl
	 *            目標URL--URL
	 * @param outUrl
	 *            输出路徑，绝对路径--String
	 * @throws IOException
	 **/
	public static void netDownloader(URL inUrl, String outUrl) throws IOException {
		de.netload(inUrl, outUrl);
	}

	/**
	 * 文件压缩
	 * <p>
	 * 压缩指定目录的文件（夹）成文件到另一个指定目录<br>
	 * 
	 * @param inUrl
	 *            目標路徑，绝对路径--String
	 * @param outUrl
	 *            输出路徑，绝对路径--String
	 * @throws Exception
	 **/
	public static void toZips(String inUrl, String outUrl) throws Exception {
		de.toZip(inUrl, outUrl);
	}

	/**
	 * 文件解压
	 * <p>
	 * 解压指定目录的压缩文件到另一个指定目录<br>
	 * 
	 * @param inUrl
	 *            目標路徑，绝对路径--String
	 * @param outUrl
	 *            输出路徑，绝对路径--String
	 * @throws Exception
	 **/
	public static void zipOuts(String inUrl, String outUrl) throws Exception {
		de.zipOut(inUrl, outUrl);
	}

	/**
	 * 获取目录全部文件
	 * <p>
	 * 获取指定目录下的所有文件的路径,布尔参数为true获取全部，布尔参数为false仅获取当前目录下文件<br>
	 * 
	 * @param Url
	 *            目標路徑，绝对路径--String
	 * @param all
	 *            在本类获取静态钥匙
	 * @return SairLists<String>类型
	 **/
	public static SairLists<String> getFilesToList(String Url, Key<FileMana> all) {
		return de.getAllFiles(Url, all);
	}

	/**
	 * 获取目录全部文件
	 * <p>
	 * 获取指定目录下的所有文件的路径,布尔参数为true获取全部，布尔参数为false仅获取当前目录下文件<br>
	 * 
	 * @param Url
	 *            目標路徑，绝对路径--String
	 * @param all
	 *            在本类获取静态钥匙
	 * @return String[]类型
	 **/
	public static String[] getFilesToArr(String Url, Key<FileMana> all) {
		return StrEdit.castStr(de.getAllFiles(Url, all).getListObjArr());
	}

	private static String[] custoStringArr(String Sv) {
		String[] goBack = new String[] { Sv };
		return goBack;
	}

	/**
	 * 获取包文件流(兼容Linux但不适用于安卓)
	 * <p>
	 * 获取当前包内的文件流<br>
	 * 
	 * @param Url
	 *            目标文件绝对路径--String
	 * @return InputStream
	 * @throws IOException
	 **/
	public static InputStream getRunPack(String Url) throws IOException {
		return itm.getNowRuntime(Url);
	}

	/**
	 * 文件编辑
	 * <p>
	 * 将多个String值在指定目录文件的最后一行添加content，boolean值决定是否换行<br>
	 * 
	 * @param fileUrl
	 *            目標路徑，绝对路径--String
	 * @param content
	 *            String[]--内存中的数据
	 * @param LN
	 *            Boolean 是否换行
	 * @throws IOException
	 **/
	public static void addToFilesEnd(String fileUrl, String[] content, boolean LN) throws IOException {
		mti.addToEnd(fileUrl, content, LN);
	}

	/**
	 * 文件编辑
	 * <p>
	 * 将单个String在指定目录文件的最后一行添加content，boolean值决定是否换行<br>
	 * 
	 * @param fileUrl
	 *            目標路徑，绝对路径--String
	 * @param content
	 *            String--内存中的数据
	 * @param LN
	 *            Boolean 是否换行
	 * @throws IOException
	 **/
	public static void addToFilesEnd(String fileUrl, String content, boolean LN) throws IOException {
		mti.addToEnd(fileUrl, custoStringArr(content), LN);
	}

	/**
	 * 文件编辑
	 * <p>
	 * 将SairLists<String>类型在指定目录文件的最后一行添加content，boolean值决定是否换行<br>
	 * 
	 * @param fileUrl
	 *            目標路徑，绝对路径--String
	 * @param saList
	 *            SairLists<String>--内存中的数据
	 * @param LN
	 *            Boolean 是否换行
	 * @throws IOException
	 **/
	public static void addToFilesEnd(String fileUrl, SairLists<String> saList, boolean LN) throws IOException {
		mti.addToEnd(fileUrl, StrEdit.castStr(saList.getListObjArr()), LN);
	}

	/**
	 * 文件读取
	 * <p>
	 * 读取指定路径的文件，加载为SairLists<String>类型<br>
	 * 
	 * @param fileUrl
	 *            目標路徑，绝对路径--String
	 * @param enc
	 *            编码格式
	 * @return SairLists<String>类型
	 * @throws IOException
	 **/
	public static SairLists<String> getFileToList(String fileUrl, Key<String> enc) throws IOException {
		return itm.iotoMemoryStringList(fileUrl, enc);
	}

	/**
	 * 文件读取
	 * <p>
	 * 读取指定路径的文件，加载为String[]类型<br>
	 * 
	 * @param fileUrl
	 *            目標路徑，绝对路径--String
	 * @param enc
	 *            编码格式
	 * @return String[]类型
	 * @throws IOException
	 **/
	public static String[] getFileToStringArr(String fileUrl, Key<String> enc) throws IOException {
		return StrEdit.castStr(getFileToList(fileUrl, enc).getListObjArr());
	}

	/**
	 * 文件保存/覆盖
	 * <p>
	 * 将String[]类型的值保存进文件，没有则新建，有则覆盖<br>
	 * 
	 * @param fileUrl
	 *            目標路徑，绝对路径--String
	 * @param arrs
	 *            String[]--内存中的数据
	 * @param LN
	 *            Boolean 是否换行
	 * @throws IOException
	 **/
	public static void memorriesToIO(String fileUrl, String[] arrs, boolean LN) throws IOException {
		mti.memorriesToIo(fileUrl, arrs, LN);
	}

	/**
	 * 文件保存/覆盖
	 * <p>
	 * 将SairLists<String>类型的值保存进文件，没有则新建，有则覆盖<br>
	 * 
	 * @param fileUrl
	 *            目標路徑，绝对路径--String
	 * @param saList
	 *            SairLists<String>--内存中的数据
	 * @param LN
	 *            Boolean 是否换行
	 * @throws IOException
	 **/
	public static void memorriesToIO(String fileUrl, SairLists<String> saList, boolean LN) throws IOException {
		mti.memorriesToIo(fileUrl, StrEdit.castStr(saList.getListObjArr()), LN);
	}

	/**
	 * 文件读取(兼容Linux但不适用于安卓)
	 * <p>
	 * 读取指定包内相对路径的文件，加载为SairLists<String>类型<br>
	 * 
	 * @param Url
	 *            目標路徑，绝对路径--String
	 * @param enc
	 *            编码格式
	 * @return SairLists<String>类型
	 * @throws IOException
	 **/
	public static SairLists<String> getPackFileToList(String Url, Key<String> enc) throws IOException {
		return itm.PackReder(Url, enc);
	}

	/**
	 * 文件读取(兼容Linux但不适用于安卓)
	 * <p>
	 * 读取指定包内相对路径的文件，加载为String[]类型<br>
	 * 
	 * @param Url
	 *            目標路徑，绝对路径--String
	 * @param enc
	 *            编码格式
	 * @return String[]类型
	 * @throws IOException
	 **/
	public static String[] getPackFileToStringArr(String Url, Key<String> enc) throws IOException {
		return StrEdit.castStr(getPackFileToList(Url, enc).getListObjArr());
	}
}
