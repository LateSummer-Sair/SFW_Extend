package sair.sacoms;

import java.io.IOException;

import sair.sacoms.until.Tkp;

/**
 *
 * <p>
 * 文本文档文件操作<br>
 * 
 * @author _Sair
 * @version txtkey_1.3
 * 
 **/

public class Txtkey {
	private static Tkp p = new Tkp();

	private static void reSetter(String inUrl, Key<String> enc) throws IOException {
		p.setArr(FileMana.getFileToStringArr(inUrl, enc));
	}

	private static void feKey(String outUrl) throws Exception {
		Objserialize.toSerial(p, outUrl);
	}

	private static void reKey(String inUrl, String outUrl) throws Exception {
		p = (Tkp) Objserialize.unSerial(inUrl);
		FileMana.memorriesToIO(outUrl, p.getArr(), true);
	}

	/**
	 * 文档加密
	 * <p>
	 * 加密指定目录的文本文件到新的目录<br>
	 * 
	 * @param inUrl
	 *            目標路徑，绝对路径--String
	 * @param outUrl
	 *            输出路徑，绝对路径--String
	 * @throws IOException
	 **/
	public static void fxTxt(String inUrl, String outUrl, Key<String> enc) throws Exception {
		reSetter(inUrl, enc);
		feKey(outUrl);
	}

	/**
	 * 文档解密
	 * <p>
	 * 解密指定目录的文本文件到新的目录<br>
	 * 
	 * @param inUrl
	 *            目標路徑，绝对路径--String
	 * @param outUrl
	 *            输出路徑，绝对路径--String
	 * @throws IOException
	 **/
	public static void reTxt(String inUrl, String outUrl) throws Exception {
		reKey(inUrl, outUrl);
	}
}