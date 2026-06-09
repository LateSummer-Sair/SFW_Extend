package sair.sacoms;

import sair.sacoms.until.SeqPageI;

/**
 * _Seq快速排序
 * <p>
 * 使用的类型必须继承接口SeqPageListenner， 然后根据SeqPageListenner规定提供的ID进行类型排序<br>
 * 
 * @author _Sair
 * @version Seq_2.1
 * 
 **/
public class Seq {
	/**
	 * 升序钥匙
	 **/
	public final static Key<Seq> BySort = Key.creatKey();
	/**
	 * 降序钥匙
	 **/
	public final static Key<Seq> ByOrder = Key.creatKey();

	/**
	 * 开始排序
	 * <p>
	 * 使用排序钥匙<br>
	 * 
	 * @param key
	 *            排序钥匙
	 * @param array
	 *            数组形式的Integer类型值
	 * @return Integer[]类型
	 **/
	public static Integer[] Of(Key<Seq> key, Integer... array) {
		if (array != null && array.length != 0) {
			Seqs(key, array, 0, array.length - 1);
			System.gc();
		}
		return array;
	}

	private static int Unit(Key<Seq> key, Integer[] array, int low, int high) {
		Integer keys = array[low];
		while (low < high)
			if (key.equals(Seq.BySort)) {
				while (array[high] >= keys && high > low)
					--high;
				array[low] = array[high];
				while (array[low] <= keys && high > low)
					++low;
				array[high] = array[low];
			} else if (key.equals(Seq.ByOrder)) {
				while (array[high] <= keys && high > low)
					--high;
				array[low] = array[high];
				while (array[low] >= keys && high > low)
					++low;
				array[high] = array[low];
			}
		array[low] = keys;
		return high;
	}

	private static void Seqs(Key<Seq> key, Integer[] array, int low, int high) {
		if (low >= high)
			return;
		int index = Unit(key, array, low, high);
		Seqs(key, array, low, index - 1);
		Seqs(key, array, index + 1, high);
	}

	/**
	 * 开始排序
	 * <p>
	 * 使用排序钥匙<br>
	 * 
	 * @param key
	 *            排序钥匙
	 * @param array
	 *            数组形式的T类型值
	 * @return T[]类型
	 **/
	@SafeVarargs
	public static <T extends SeqPageI> SeqPageI[] Of(Key<Seq> key, T... array) {
		if (array != null && array.length != 0) {
			Seqs(key, array, 0, array.length - 1);
			System.gc();
		}
		return array;
	}

	private static <T extends SeqPageI> int Unit(Key<Seq> key, T[] array, int low, int high) {
		T keys = array[low];
		while (low < high)
			if (key.equals(Seq.BySort)) {
				while (array[high].getSeqID() >= keys.getSeqID() && high > low)
					--high;
				array[low] = array[high];
				while (array[low].getSeqID() <= keys.getSeqID() && high > low)
					++low;
				array[high] = array[low];
			} else if (key.equals(Seq.ByOrder)) {
				while (array[high].getSeqID() <= keys.getSeqID() && high > low)
					--high;
				array[low] = array[high];
				while (array[low].getSeqID() >= keys.getSeqID() && high > low)
					++low;
				array[high] = array[low];
			}
		array[low] = keys;
		return high;
	}

	private static <T extends SeqPageI> void Seqs(Key<Seq> key, T[] array, int low, int high) {
		if (low >= high)
			return;
		int index = Unit(key, array, low, high);
		Seqs(key, array, low, index - 1);
		Seqs(key, array, index + 1, high);
	}

	/**
	 * 开始排序
	 * <p>
	 * 使用排序钥匙<br>
	 * 
	 * @param key
	 *            排序钥匙
	 * @param list
	 *            SairLists对象
	 * @return SairLists<T>类型
	 **/
	public static <T extends SeqPageI> SairLists<T> Of(Key<Seq> key, SairLists<T> list) {
		if (list != null && list.getLength() != 0) {
			Seqs(key, list, 0, list.getLength() - 1);
			System.gc();
		}
		return list;
	}

	private static <T extends SeqPageI> void Seqs(Key<Seq> key, SairLists<T> list, int low, int high) {
		if (low >= high)
			return;
		int index = Unit(key, list, low, high);
		Seqs(key, list, low, index - 1);
		Seqs(key, list, index + 1, high);
	}

	private static <T extends SeqPageI> int Unit(Key<Seq> key, SairLists<T> list, int low, int high) {
		T keys = list.getIndex(low);
		while (low < high)
			if (key.equals(Seq.BySort)) {
				while (list.getIndex(high).getSeqID() >= keys.getSeqID() && high > low)
					--high;
				list.setTo(low, list.getIndex(high));
				while (list.getIndex(low).getSeqID() <= keys.getSeqID() && high > low)
					++low;
				list.setTo(high, list.getIndex(low));
			} else if (key.equals(Seq.ByOrder)) {
				while (list.getIndex(high).getSeqID() <= keys.getSeqID() && high > low)
					--high;
				list.setTo(low, list.getIndex(high));
				while (list.getIndex(low).getSeqID() >= keys.getSeqID() && high > low)
					++low;
				list.setTo(high, list.getIndex(low));
			}
		list.setTo(low, keys);
		return high;
	}
}
