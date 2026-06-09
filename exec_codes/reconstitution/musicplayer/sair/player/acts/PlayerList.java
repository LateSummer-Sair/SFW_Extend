package sair.player.acts;

import java.util.ArrayList;

import javax.swing.DefaultListModel;
import javax.swing.JList;

import sair.sacoms.Urler;

/**
 * 播放列表数据模型 —— 管理歌曲的路径、名称和序号。
 * 
 * <h3>三层数据结构</h3>
 * <table border="1">
 *   <tr><th>ArrayList&lt;String&gt; listPath</th><td>存储完整文件路径（如 E:\\music\\song.mp3）</td></tr>
 *   <tr><th>DefaultListModel&lt;String&gt; listName</th><td>存储文件名（如 song.mp3），绑定到歌曲名 JList</td></tr>
 *   <tr><th>DefaultListModel&lt;String&gt; listHead</th><td>存储序号（如 "0", "1", "2"...），绑定到序号 JList</td></tr>
 * </table>
 * 
 * <h3>JList 绑定</h3>
 * 构造时通过 {@code list.setModel(listName)} 和 {@code listh.setModel(listHead)}
 * 将 DefaultListModel 直接绑定到 JList 上，后续对 Model 的增删改会立即反映到 UI。
 * 
 * @see ListPage 单项数据接口
 */
public class PlayerList {

	/** 完整文件路径列表 */
	private ArrayList<String> listPath = new ArrayList<String>();
	/** 序号列表模型（"0", "1", "2"...） */
	private DefaultListModel<String> listHead = new DefaultListModel<String>();
	/** 歌曲名列表模型 */
	DefaultListModel<String> listName = new DefaultListModel<String>();

	/**
	 * 构造播放列表并绑定 UI 组件。
	 * @param list  歌曲名 JList（中间列）
	 * @param listh 序号 JList（左侧行头）
	 */
	public PlayerList(JList<String> list, JList<String> listh) {
		list.setModel(listName);  // 绑定歌曲名
		listh.setModel(listHead); // 绑定序号
	}

	/**
	 * 获取有效的列表大小。
	 * <p>取 path 和 name 的最小值，防止数据不一致导致的越界。</p>
	 */
	public int listSize() {
		int pathSize = listPath.size();
		int nameSize = listName.size();
		return (pathSize > nameSize) ? nameSize : pathSize;
	}

	/**
	 * 添加一首歌曲。
	 * <p>三个列表同步追加：path 存完整路径，name 提取文件名，head 用当前 size 作为序号。</p>
	 */
	void addPath(String path) {
		Urler file = new Urler(path);
		listPath.add(path);                                    // 完整路径
		listName.addElement(file.getFileName());                // 文件名
		listHead.addElement(String.valueOf(listHead.getSize())); // 序号
	}

	/** 清空所有数据（path + name + head） */
	void clearAll() {
		listName.clear();
		listPath.clear();
		listHead.clear();
	}

	/**
	 * 获取指定序号的歌曲数据。
	 * @return ListPage 匿名实例（惰性读取，非预存），越界返回 null
	 */
	public ListPage get(int index) {
		if (index < 0 || index >= listPath.size() || index >= listName.size())
			return null;
		return new ListPage() {
			@Override
			public String getPath() { return listPath.get(index); }
			@Override
			public String getName() { return listName.get(index); }
		};
	}

	/**
	 * 移除指定序号的歌曲并返回其数据。
	 * <p>同时删除 path 和 name 中的对应项，并将 head 的最后一个元素移除
	 * （因为序号是连续的 0,1,2...，移除后最后一个序号失效）。</p>
	 * @return 被移除的歌曲数据（匿名 ListPage），越界返回 null
	 */
	ListPage remove(int index) {
		if (index < listSize() && index >= 0) {
			String path = listPath.remove(index);
			String name = listName.remove(index);
			if (path != null && name != null) {
				listHead.remove(listHead.size() - 1); // 移除最后一个序号
				return new ListPage() {
					@Override
					public String getPath() { return path; }
					@Override
					public String getName() { return name; }
				};
			}
		}
		return null;
	}
}
