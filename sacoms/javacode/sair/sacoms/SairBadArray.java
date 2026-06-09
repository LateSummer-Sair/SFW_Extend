package sair.sacoms;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Iterator;

/**
 * 泛型数组List（实现原理和ArrayList不一样，扩容也不一样）</br>
 * 不支持remove，仅支持扩容
 **/
public class SairBadArray<T> implements Iterable<T>, Serializable {

	private static final long serialVersionUID = -561483967027874201L;

	/**
	 * 构造函数
	 * 
	 **/
	public SairBadArray() {

		this.reInitNodeMax(0);
	}

	private SairBadArray(int nodeMax, SairBadArray<T> father) {

		this.reInitNodeMax(nodeMax);
		this.father = father;
	}

	private boolean hasSon;

	private int thisLen;
	private int alldatalen;

	private Object[] data;

	private SairBadArray<T> father, lastSon;

	/**
	 * 重新设置Node的扩容值</br>
	 * 当Node大小到达这个值时会执行扩容</br>
	 * 建议初始化后第一时间调用较为安全
	 * 
	 * @param nodeMax
	 *            Node最大值
	 **/
	public void reInitNodeMax(int nodeMax) {
		if (nodeMax <= 1)
			nodeMax = 30;
		this.data = new Object[nodeMax];
		thisLen = 0;
		lastSon = null;
		hasSon = false;
	}

	/**
	 * 等效getSize，获取List长度</br>
	 * 
	 * @return 长度值
	 **/
	public int getLen() {
		return alldatalen;
	}

	/**
	 * 在尾部添加一个值</br>
	 * 
	 * @param t
	 *            值元素
	 **/
	public void add(T t) {
		if (t != null) {
			lastSon = adds(t);
			alldatalen++;
		}
	}

	@SuppressWarnings("unchecked")
	private SairBadArray<T> adds(T t) {
		if (thisLen < this.data.length - 1) {
			this.data[thisLen] = t;
			thisLen++;
		} else {
			if (this.hasSon != true) {
				hasSon = true;
				this.data[thisLen] = new SairBadArray<T>((int) ((double) this.data.length * 1.6), this);
			}
			return ((SairBadArray<T>) this.data[thisLen]).adds(t);
		}
		return this;
	}

	/**
	 * 获取指定id的值元素</br>
	 * 
	 * @param id
	 *            等效数组下标
	 * @return 值元素
	 **/
	public T get(int id) {
		if (id < alldatalen) {
			if (id <= alldatalen / 2) {
				return setLtR(id, null, false);
			} else
				return setRtL(lastSon, alldatalen - id, null, false);
		}
		return null;
	}

	/**
	 * 修改指定id的值元素</br>
	 * 
	 * @param id
	 *            等效数组下标
	 * @param t
	 *            新值元素
	 * @return 旧值元素
	 **/
	public T set(int id, T t) {
		if (id < alldatalen) {
			if (id <= alldatalen / 2)
				return setLtR(id, t, true);
			else
				return setRtL(lastSon, alldatalen - id, t, true);
		}
		return null;
	}

	private T setRtL(SairBadArray<T> data, int id, T t, boolean isSet) {
		if (id < data.thisLen) {
			return data.setLtR(data.thisLen - id, t, isSet);
		} else if (id >= data.thisLen) {
			return setRtL(data.father, id - data.thisLen, t, isSet);
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private T setLtR(int id, T t, boolean isSet) {
		if (id < this.data.length - 1) {
			if (id % 2 != 0)
				id--;
			T tb = (T) this.data[id];
			if (isSet)
				this.data[id] = t;
			return tb;

		} else if (hasSon)
			return ((SairBadArray<T>) this.data[this.data.length - 1]).setLtR((id - (this.data.length - 1)), t, isSet);
		return null;
	}

	/**
	 * 获得全部的值封装成数组
	 * 
	 * @return T[]
	 **/
	@SuppressWarnings("unchecked")
	public T[] getAllData(Class<?> clazz) {
		T[] back = (T[]) Array.newInstance(clazz, this.alldatalen);
		int key = 0;
		SairBadArray<T> c = this;
		while (c != null) {
			for (int i = 0; i < c.data.length - 1 && key < this.alldatalen; i++, key++)
				back[key] = (T) c.data[i];
			c = (c.hasSon) ? (SairBadArray<T>) c.data[c.data.length - 1] : null;
		}
		return back;
	}

	@Override
	public Iterator<T> iterator() {
		return new Iterator<T>() {
			private SairBadArray<T> cnode;
			private int max;
			private int i, j;

			public Iterator<T> set(SairBadArray<T> sairBadArray) {
				cnode = sairBadArray;
				max = cnode.getLen();
				return this;
			}

			@Override
			public boolean hasNext() {
				return ((i < cnode.data.length - 1) || cnode.hasSon) && j < max;
			}

			@SuppressWarnings("unchecked")
			@Override
			public T next() {
				T t = null;
				if (i < cnode.data.length - 1)
					t = (T) cnode.data[i];
				else if (cnode.hasSon) {
					cnode = (SairBadArray<T>) cnode.data[cnode.data.length - 1];
					i = 0;
					t = (T) cnode.data[i];
				}
				i++;
				j++;
				return t;
			}

			@Override
			public void remove() {

			}
		}.set(this);
	}
}
