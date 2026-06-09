package sair.sacoms;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.Iterator;

import sair.sacoms.until.SLPage;

/**
 * SairLists双向链表泛型式LinkedList
 * <p>
 * index：引索</br>
 * Page：值本体</br>
 * id：Page下标
 * 
 * @author _Sair
 * @version SairLists_4.0
 * 
 **/
public class SairLists<T> implements Iterable<T>, Serializable {
	private static final long serialVersionUID = -5494703752593817954L;
	private SLPage head, now, CachePage;
	private int upId, len;

	/**
	 * SairListsNewInstacer
	 * 
	 * @param arrayLv
	 *            数组对应的维度
	 * @param len
	 *            定义SairLists<T>数组总长度
	 * @param clazz
	 *            传入的防擦除类型
	 * @return T 类型
	 */
	@SuppressWarnings("unchecked")
	public static <T> T creatSairListsArray(int arrayLv, Integer len, Class<?> clazz) {
		if (arrayLv <= 0)
			return (T) new SairLists<T>();
		T t = (T) Array.newInstance(SairLists.class, len);
		for (int i = 1; i < arrayLv; i++)
			t = (T) Array.newInstance(t.getClass(), len);
		return t;
	}

	/**
	 * 初始化类对象
	 * <p>
	 * 初始化函数，传入的数据类型为:T[]/T.../T/null<br>
	 * 
	 * @param ts
	 *            值
	 * 
	 **/

	@SafeVarargs
	public SairLists(T... ts) {
		this.reStartClearAll();
		this.setArrToList(ts);
	}

	private SLPage idFounder(int id) {
		// {[0],[1],[2],[3],[4],[5],[6],[7],[8],[9],[10],[11],[12],[13],[14],[15],[16],[17],[18],[19]}
		if (id < 0 || id >= this.len || this.len <= 0)
			return null;
		int a = 0, b = 0, i = 0;
		boolean isStartForLeft = true;
		if (upId < id) {
			a = id - upId;
			b = this.len - 1 - id;
			isStartForLeft = false;
		} else if (upId > id) {
			a = id;
			b = upId - id;
			isStartForLeft = true;
		}
		if (isStartForLeft == true)
			if (a > b) {
				if (this.CachePage == null)
					this.CachePage = this.now.getUp();
				for (i = upId; i > id; i--)
					this.CachePage = this.CachePage.getUp();
			} else {
				this.CachePage = this.head;
				for (i = 0; i < id; i++)
					this.CachePage = this.CachePage.getDown();
			}
		else if (a < b) {
			if (this.CachePage == null)
				this.CachePage = this.head;
			for (i = upId; i < id; i++)
				this.CachePage = this.CachePage.getDown();
		} else {
			this.CachePage = this.now.getUp();
			for (i = this.len - 1; i > id; i--)
				this.CachePage = this.CachePage.getUp();
		}
		this.upId = i;
		return this.CachePage;
	}

	/**
	 * 初始化
	 * <p>
	 * 清空所有SairLists页，让其回到初始化状态<br>
	 * 
	 **/
	public void reStartClearAll() {
		this.now = new SLPage();
		this.len = 0;
		this.head = this.now;
		this.CachePage = null;
		this.upId = 0;
		System.gc();
	}

	/**
	 * 添加值
	 * <p>
	 * 向尾部添加一个类型为T的值<br>
	 * 
	 * @param index
	 *            传入的参数
	 * @return boolean类型
	 **/
	public boolean add(T index) {
		this.now.setIndex(index);
		this.now = new SLPage(this.now);
		this.len++;
		return true;
	}

	/**
	 * 值插入到指定位置并刷新
	 * <p>
	 * 将类型为T的值插入到指定的id位置，并刷新所有页id<br>
	 * 
	 * @param index
	 *            传入的参数
	 * @param id
	 *            需要插入到的下标
	 * @return boolean
	 **/
	public boolean insert(T index, int id) {
		SLPage nc = this.idFounder(id);
		SLPage insertC;
		if (nc != null) {
			insertC = insertSetting(index, new SLPage(), nc);
			if (id == 0)
				this.head = insertC;
			if (id <= upId)
				upId++;
			this.len++;
			return true;
		}
		return false;
	}

	private static SLPage insertSetting(Object index, SLPage insertPage, SLPage nc) {
		insertPage.setIndex(index);
		if (nc.getUp() != null)
			nc.getUp().setDown(insertPage);
		nc.setUp(insertPage);
		insertPage.setUp(nc.getUp());
		insertPage.setDown(nc);
		return insertPage;
	}

	/**
	 * 插入多条值
	 * <p>
	 * 将T/T[]/T这类的单个或者多个T类型值按顺序加入到List的尾部<br>
	 * 
	 * @param index
	 *            传入的参数（T...数组形式）
	 **/
	public SairLists<T> setArrToList(@SuppressWarnings("unchecked") T... index) {
		if (index != null) {
			int indexLen = index.length;
			if (indexLen != 0)
				for (int i = 0; i < indexLen; i++)
					this.add(index[i]);
		}
		return this;
	}

	/**
	 * 将值添加到头部
	 * <p>
	 * 将相同类型的SairList内所有值添加到头部（不忽略相同值）<br>
	 * 
	 * @param list
	 *            另外一个SairLists<T>类型的数据，T类型要相同
	 **/
	public void setListAllToHead(SairLists<T> list) {
		if (list != null)
			for (int i = list.getLength() - 1; i >= 0; i--)
				insert(list.getIndex(i), 0);
	}

	/**
	 * 将值添加到尾部
	 * <p>
	 * 将相同类型的SairList内所有值添加到尾部（不忽略相同值）<br>
	 * 
	 * @param list
	 *            另外一个SairLists<T>类型的数据，T类型要相同
	 **/
	public void setListAllToEnd(SairLists<T> list) {
		if (list != null)
			for (T index : list)
				add(index);
	}

	/**
	 * 删除值
	 * <p>
	 * 仅移除指定id的T类型值，不移除List节点页<br>
	 * 
	 * @param id
	 *            数据的下标
	 * @return T
	 **/
	public T delete(int id) {
		return this.setTo(id, null);
	}

	/**
	 * 移除值与节点页
	 * <p>
	 * 移除指定id位置的页，并刷新整个List的节点页id<br>
	 * 
	 * @param id
	 *            需要移除的数据下标
	 * @return SLPage
	 **/
	public SLPage reMove(int id) {
		SLPage nc = this.idFounder(id);
		if (nc != null) {
			SLPage Uper = nc.getUp(), Dner = nc.getDown();
			if ((Uper == null) || (Uper.getIndex() == null)) {
				this.head = this.head.getDown();
				this.head.setUp(null);
			} else if ((Dner == null) || Dner.getIndex() == null) {
				this.now = this.now.getUp();
				this.now.setDown(null);
			} else {
				Uper.setDown(Dner);
				Dner.setUp(Uper);
			}
			this.CachePage = nc.getDown();
			this.len--;
		}
		return nc;
	}

	/**
	 * 修改值
	 * <p>
	 * 将类型为T的指定id位置修改为新的值<br>
	 * 
	 * @param NewIndex
	 *            传入的新数据
	 * @param id
	 *            需要修改的数据下标
	 * @return boolean
	 **/
	public T setTo(int id, T NewIndex) {
		SLPage back = this.idFounder(id);
		if (back != null) {
			T t = back.getIndex();
			back.setIndex(NewIndex);
			return t;
		}
		return null;
	}

	/**
	 * 值位置转换
	 * <p>
	 * 将两个不同id位置的T类型值变换位置<br>
	 * 
	 * @param idA
	 *            第一个数据的下标
	 * @param idB
	 *            第二个数据的下标
	 * @return boolean
	 **/
	public boolean AtoB(int idA, int idB) {
		SLPage A = this.idFounder(idA), B = this.idFounder(idB);
		if ((A != null) && (B != null)) {
			T indexc = A.getIndex();
			A.setIndex(B.getIndex());
			B.setIndex(indexc);
			return true;
		}
		return false;
	}

	/**
	 * 获取长度大小
	 * <p>
	 * 获取整个List大小长度<br>
	 * 
	 * @return 返回类型为Integer类型
	 **/
	public int getLength() {
		return this.len;
	}

	/**
	 * 获取头部值
	 * <p>
	 * 获取最头部的值<br>
	 * 
	 * @return 返回类型为T
	 **/
	public T getHeadIndex() {
		if (this.head == null)
			return null;
		return this.head.getIndex();
	}

	/**
	 * 获取尾部值
	 * <p>
	 * 获取最尾部的值<br>
	 * 
	 * @return 返回类型为T
	 **/
	public T getEndIndex() {
		SLPage back = this.now;
		if (back == null)
			return null;
		back = back.getUp();
		if (back == null)
			return null;
		return back.getIndex();
	}

	/**
	 * 获取单个值
	 * <p>
	 * 获取指定id位置的T类型值<br>
	 * 
	 * @param id
	 *            数据的下标
	 * @return 返回类型为T
	 **/
	public T getIndex(int id) {
		SLPage back = this.idFounder(id);
		if (back == null)
			return null;
		return back.getIndex();
	}

	/**
	 * 返回所有值
	 * <p>
	 * 将所有录入的值全部导出为Object[]类型<br>
	 * 
	 * @return 返回类型为Object[]
	 **/
	public Object[] getListObjArr() {
		Object[] back = new Object[this.len];
		for (int i = 0; i < this.len; i++)
			back[i] = this.idFounder(i).getIndex();
		return back;
	}

	/**
	 * 返回所有值
	 * <p>
	 * 将所有录入的值全部导出为T[]类型<br>
	 * 
	 * @return 返回类型为T[]
	 **/
	public T[] getListArr(Class<?> clazz) {
		@SuppressWarnings("unchecked")
		T[] back = (T[]) Array.newInstance(clazz, this.getLength());
		for (int i = 0; i < this.getLength(); i++)
			try {
				back[i] = this.idFounder(i).getIndex();
			} catch (Exception e) {
				back[i] = null;
			}
		return back;
	}

	/**
	 * 寻找指定值id方法
	 * <p>
	 * 在List所有值中查找与指定T类型值相同的值，并返回一个SairLists装载所有的值存在id<br>
	 * 
	 * @param exitsIndexs
	 *            需要被寻找对比的值
	 * @return 返回类型为SairLists<Integer>
	 **/
	public SairLists<Integer> getIndexId(T exitsIndexs) {
		SairLists<Integer> backCache = new SairLists<Integer>();
		SLPage cache;
		for (int i = 0; i < this.len; i++) {
			cache = this.idFounder(i);
			if ((cache != null) && ((exitsIndexs == null && exitsIndexs == cache.getIndex())
					|| (exitsIndexs != null && exitsIndexs.equals(cache.getIndex()))))
				backCache.add(i);
		}
		return backCache;
	}

	/**
	 * 寻找是否有相同值
	 * <p>
	 * 寻找List内是否已经有相同的值，有将返回true，没有将返回false<br>
	 * 
	 * @param existsIndexs
	 *            需要被寻找对比的值
	 * @return 返回类型为boolean
	 **/
	public boolean existsIndex(T existsIndexs) {
		SLPage cache;
		for (int i = 0; i < this.len; i++) {
			cache = this.idFounder(i);
			if ((existsIndexs != null && cache != null) && (existsIndexs.equals(cache.getIndex())))
				return true;
		}
		return false;
	}

	/**
	 * 获取值
	 * <p>
	 * 强制获取List节点页<br>
	 * 
	 * @param id
	 *            List节点页ID
	 * @return SLPage类型
	 **/
	public SLPage getPageIndex(int id) {
		return idFounder(id);
	}

	@Override
	public Iterator<T> iterator() {
		return new Iterator<T>() {
			private int i;

			@Override
			public boolean hasNext() {
				if (i < SairLists.this.getLength())
					return true;
				else
					return false;
			}

			@Override
			public T next() {
				T t = SairLists.this.getIndex(i);
				i++;
				return t;
			}

			@Override
			public void remove() {

			}
		};
	}
}
