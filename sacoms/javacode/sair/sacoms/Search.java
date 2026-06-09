package sair.sacoms;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import sair.sacoms.until.SearchPage;

/**
 * Search简单搜索引擎
 * <p>
 * 
 * @author _Sair
 * @version Search_1.5
 * 
 **/
public class Search<T> implements Serializable {
	private static final long serialVersionUID = 4830476252579581221L;

	private HashMap<String, T> details;
	private HashSet<String>[] keySearch;

	/**
	 * 构造方法
	 */
	public Search() {

		reStartClearAll();
	}

	/**
	 * 根据分析标识key来获取对象的唯一标识
	 * <p>
	 * 
	 * @param id
	 *            Object的唯一标识id
	 * @return Object
	 */
	public T serachObjectByID(String id) {
		return details.get(id);
	}

	/**
	 * 根据对象的唯一标识来获取对象（串）
	 * <p>
	 * 
	 * @param ids
	 *            Object的唯一标识（串）
	 * @return SairLists<Object>
	 */
	public T[] serachObjectsByIDs(Class<?> clazz, String... ids) {
		if (ids == null || ids.length <= 0)
			return null;
		SairLists<T> objsc = new SairLists<T>();
		// objsc.setClazz(super.getClazz());
		for (String id : ids)
			objsc.add(serachObjectByID(id));
		return objsc.getListArr(clazz);
	}

	/**
	 * 根据分析标识key来获取对象的唯一标识（串）
	 * <p>
	 * 
	 * @param key
	 *            Object的标识key
	 * @param number
	 *            搜索到ID的数量(此参数为null时将视为全部获取)
	 * @return String[]
	 */
	public String[] serachIDsByKey(Class<?> clazz, String key, Integer number) {
		if (key == null)
			return null;
		int z = 0;
		HashMap<String, Integer> idTimes = new HashMap<String, Integer>();
		HashSet<String> ids = new HashSet<String>();
		for (int i = 0; i < key.length(); i++) {
			int at = key.charAt(i);
			if (keySearch[at] == null)
				continue;
			Object[] objs = keySearch[at].toArray();
			for (Object obj : objs) {
				if (number != null && z >= number)
					break;
				String id = (String) obj;
				int times = 1;
				if (ids.contains(id)) {
					times += idTimes.get(id);
					idTimes.put(id, times);
				} else {
					ids.add(id);
					idTimes.put(id, times);
					z++;
				}
			}
		}
		List<SearchPage> sortBeans = new ArrayList<SearchPage>();
		for (String id : ids)
			sortBeans.add(new SearchPage(id, idTimes.get(id)));
		Collections.sort(sortBeans, new Comparator<SearchPage>() {
			@Override
			public int compare(SearchPage o1, SearchPage o2) {
				return o2.getTimes() - o1.getTimes();
			}
		});
		SairLists<String> sl = new SairLists<String>();
		for (SearchPage sortBean : sortBeans)
			sl.add(sortBean.getId());
		idTimes.clear();
		idTimes = null;
		ids.clear();
		ids = null;
		sortBeans.clear();
		sortBeans = null;
		return sl.getListArr(clazz);
	}

	/**
	 * 添加搜索记录
	 * <p>
	 * 
	 * @param id
	 *            Object的唯一标识id
	 * @param searchKey
	 *            Object的标识key
	 * @param obj
	 *            存储的对象
	 */
	public void add(String id, String searchKey, T obj) {
		if (id == null || searchKey == null || obj == null)
			return;
		details.put(id, obj);
		addSearchKey(id, searchKey);
	}

	/**
	 * 重置整个搜索对象，并释放资源
	 * <p>
	 */
	@SuppressWarnings("unchecked")
	public void reStartClearAll() {
		if (this.details != null)
			this.details.clear();
		this.details = new HashMap<String, T>();
		this.keySearch = new HashSet[Character.MAX_VALUE];
	}

	private void addSearchKey(String id, String searchKey) {
		if (id == null || searchKey == null)
			return;
		for (int i = 0; i < searchKey.length(); i++) {
			int at = searchKey.charAt(i);
			if (keySearch[at] == null) {
				HashSet<String> value = new HashSet<String>();
				keySearch[at] = value;
			}
			keySearch[at].add(id);
		}
	}

	/**
	 * details的Getter
	 * 
	 * @return HashMap<String, T>
	 */
	public HashMap<String, T> getDetails() {
		return details;
	}

	/**
	 * keySearch的Getter
	 * 
	 * @return HashSet<String>[]
	 */
	public HashSet<String>[] getKeySearch() {
		return keySearch;
	}

}
