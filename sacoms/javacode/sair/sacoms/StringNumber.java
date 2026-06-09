package sair.sacoms;

import java.util.Collection;

/**
 * 数String类型集合或者数组里面所有的字数
 * <p>
 * 
 * @author _Sair
 * @version StringNumber_1.0
 * 
 **/
public class StringNumber {
	public StringNumber(Collection<String> list) {
		cast(list);
	}

	private long len;

	private SairLists<String> list;

	private void cast(Collection<String> strs) {
		String cache;
		list = new SairLists<String>();
		for (String str : strs) {
			cache = str.replaceAll("[\\pP\\p{Punct}]", "").replaceAll("\\s*", "");
			if (cache != null && !"".equals(cache)) {
				list.add(cache);
				len += cache.length();
			}
		}
	}

	/**
	 * 获取字数
	 * <p>
	 * 
	 * @return Long_字数
	 * 
	 **/
	public long getLen() {
		return len;
	}

	public SairLists<String> getStrs() {
		return list;
	}

	public String toString() {
		return "Has: " + this.getLen();

	}

}
