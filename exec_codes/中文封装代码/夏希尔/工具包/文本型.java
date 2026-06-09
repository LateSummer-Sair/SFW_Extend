package 夏希尔.工具包;

@SuppressWarnings("rawtypes")
public class 文本型 extends 常量 implements java.io.Serializable, Comparable<文本型>, CharSequence {

	/**
	 * 
	 */
	private static final long serialVersionUID = 4841386728516325980L;

	public static final 文本型 新建文本(CharSequence 值) {
		if (值 == 空对象())
			return 新建文本("");
		return new 文本型(值);
	}

	public static 文本型[] 批量新建(String... 批量文本) {
		文本型[] 文本批量 = new 文本型[批量文本.length];
		for (int i = 0; i < 文本批量.length; i++)
			文本批量[i] = 文本型.新建文本(批量文本[i]);
		return 文本批量;
	}

	public static String[] 批量解包(文本型... 批量文本) {
		String[] 文本批量 = new String[批量文本.length];
		for (int i = 0; i < 文本批量.length; i++) {
			if (批量文本[i] != 空对象())
				文本批量[i] = 批量文本[i].取值();
		}
		return 文本批量;
	}

	private final CharSequence 值;

	private 文本型(CharSequence 值) {
		this.值 = 值;
	}

	public String 取值() {
		return toString();
	}

	public char[] 取字符数组() {
		return toString().toCharArray();
	}

	public int 取字符数() {
		return length();
	}

	public char 取字符(int 索引) {
		return charAt(索引);
	}

	public 文本型 裁剪(int 起始, int 结束) {
		return 新建文本(subSequence(起始, 结束));
	}

	@Override
	public int length() {
		return 值.length();
	}

	@Override
	public char charAt(int 索引) {
		return 值.charAt(索引);
	}

	@Override
	public CharSequence subSequence(int 起始, int 结束) {
		return 值.subSequence(起始, 结束);
	}

	@Override
	public int compareTo(文本型 o) {
		return String.valueOf(值).compareTo(String.valueOf(o.值));
	}

	public String toString() {
		return String.valueOf(值);
	}

}
