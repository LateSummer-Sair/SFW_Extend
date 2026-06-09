package sair.sacoms;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.zip.Inflater;

import sair.sacoms.until.SFS;

/**
 * KRC文件读取工具类
 * 
 * @author _Sair
 **/
public class KRC {

	/**
	 * 获取单行的KRC头时间</br>
	 * 请使用KRC工具类产生的歌词解析字符串
	 * 
	 * @param line
	 *            一行含时间的歌词
	 **/
	public static final String getHead_Time(String line) {
		if (line == null)
			return "";
		if (line.length() <= 0)
			return "";
		if (line != null && line.length() > 0) {
			StringBuffer sbf = null;
			for (int i = 0; i < line.length(); i++) {
				char c = line.charAt(i);
				if (c == '[') {
					sbf = new StringBuffer();
					continue;
				} else if (c == ']')
					break;
				if (sbf != null)
					if (c == ',')
						sbf.append('_');
					else
						sbf.append(c);
			}
			return sbf.toString();
		} else
			return "";
	}

	/**
	 * 获取单行的KRC所有字符和延迟数据</br>
	 * 请使用KRC工具类产生的歌词解析字符串
	 * 
	 * @param line
	 *            一行含时间的歌词
	 **/
	public static final SairLists<String> get_Chars_Time(String line) {
		if (line == null)
			return null;
		SairLists<String> list = new SairLists<String>();
		if (!line.contains("<"))
			return list;
		StringBuffer sbf = null;
		for (int i = 0; i < line.length() + 1; i++) {
			char c = '<';
			if (i < line.length())
				c = line.charAt(i);
			if (c == '<') {
				String str = "";
				if (sbf != null)
					str = sbf.toString();
				if (!"".equals(str))
					list.add(castU0(str));
				sbf = new StringBuffer();
				continue;
			}
			if (sbf != null)
				if (c == '>')
					sbf.append(":");
				else
					sbf.append(c);
		}
		return list;
	}

	private static String castU0(String s) {

		if (s != null) {
			StringBuffer time = new StringBuffer();
			StringBuffer chars = new StringBuffer();
			Boolean isTime = null;
			int id = 0;
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if (c == ',') {
					isTime = true;
					id++;
					continue;
				}
				if (c == ':') {
					isTime = false;
					continue;
				}

				if (isTime != null)
					if (isTime) {
						if (id < 2)
							time.append(c);
					} else
						chars.append(c);
			}

			return time.append(" : ").append(chars.toString()).toString();

		}

		return "";
	}

	@SuppressWarnings("deprecation")
	private static final String getKrcText(KRC krc) throws IOException {
		byte[] zip_byte = new byte[(int) krc.krcFile.length()];
		FileInputStream fileinstrm = new FileInputStream(krc.krcFile);
		byte[] top = new byte[4];
		fileinstrm.read(top);
		fileinstrm.read(zip_byte);
		int j = zip_byte.length;
		for (int k = 0; k < j; k++) {
			int l = k % 16;
			int tmp67_65 = k;
			byte[] tmp67_64 = zip_byte;
			tmp67_64[tmp67_65] = (byte) (tmp67_64[tmp67_65] ^ SFS.miarry[l]);
		}
		byte[] output = new byte[0];
		Inflater decompresser = new Inflater();
		decompresser.reset();
		decompresser.setInput(zip_byte);
		ByteArrayOutputStream o = new ByteArrayOutputStream(zip_byte.length);
		try {
			byte[] buf = new byte[1024];
			while (!decompresser.finished()) {
				int i = decompresser.inflate(buf);
				o.write(buf, 0, i);
			}
			output = o.toByteArray();
		} catch (Exception e) {
			output = zip_byte;
			// SaLogger.outLogger(e);
		} finally {
			try {
				o.close();
			} catch (IOException e) {
				// SaLogger.outLogger(e);
			}
		}
		decompresser.end();
		String krc_text = new String(output, "utf-8");
		fileinstrm.close();
		return krc_text;
	}

	private static final String rexFilesimpleName(KRC krc) {
		StringBuffer sbfFilesimpleName = new StringBuffer();
		String[] nameColl = krc.pathUrler.getFileName().split("-");
		if (nameColl.length > 3) {
			for (int i = 0; i < nameColl.length - 3; i++) {
				sbfFilesimpleName.append(nameColl[i]);
				if (i < nameColl.length - 4)
					sbfFilesimpleName.append('-');
			}
			return sbfFilesimpleName.toString();
		} else
			return krc.pathUrler.getFileName();
	}

	private Urler pathUrler;
	private File krcFile;
	private String path;
	private String allStringData;
	private String FilesimpleName;
	private SairLists<String> infoTi;
	private SairLists<String> lrcBody;

	private void toCast() {
		if (this.allStringData != null) {
			String[] allData = this.allStringData.split("\r\n");
			SairLists<String> list1 = new SairLists<String>();
			SairLists<String> list2 = new SairLists<String>();
			for (String info : allData)
				if (!info.contains("<"))
					list1.add(info);
				else
					list2.add(info);
			this.infoTi = list1;
			this.lrcBody = list2;
		}
	}

	/**
	 * 初始化</br>
	 * 
	 * @param path
	 *            KRC文件的路径
	 **/
	public KRC(String path) throws IOException {
		this.krcFile = new File(path);
		if (!this.krcFile.exists())
			throw new IOException();
		this.path = path;
		this.pathUrler = new Urler(path);
		this.FilesimpleName = rexFilesimpleName(this);
		this.allStringData = getKrcText(this);
		this.toCast();
	}

	/**
	 * 返回含哈希码的KRC文件短名字</br>
	 * 
	 * @return String的短名字
	 **/
	public String getFilesimpleName() {
		return FilesimpleName;
	}

	/**
	 * 返回不含歌词的KRC内部注释信息</br>
	 * 
	 * @return SairLists<String>的注释信息
	 **/
	public SairLists<String> getInfoTi() {
		return infoTi;
	}

	/**
	 * 返回所有含歌词的KRC歌词单行体</br>
	 * 
	 * @return SairLists<String>歌词体
	 **/
	public SairLists<String> getLrcBody() {
		return lrcBody;
	}

	public String toString() {
		return this.path;
	}
}
