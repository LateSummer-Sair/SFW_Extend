package sair.player.acts;

import java.util.ArrayList;

import sair.sacoms.FileMana;
import sair.sacoms.SairLists;
import sair.sacoms.Urler;
import sair.sys.SairCons;

/**
 * 文件 I/O 工具类 —— 播放列表序列化 + MP3 文件扫描。
 * 
 * <h3>功能</h3>
 * <ul>
 *   <li>{@link #saveListDataFile(String, String[])} 将播放列表路径数组序列化到磁盘</li>
 *   <li>{@link #readListDataFile(String, boolean)} 从磁盘反序列化播放列表</li>
 *   <li>{@link #readMusicFilePath(String)} 递归扫描目录下所有 .mp3 文件</li>
 * </ul>
 * 
 * <p>包级权限（class），不对外暴露。</p>
 */
class IOTools {

	/**
	 * 从磁盘读取播放列表存档。
	 * 
	 * <p>使用 SairFramework 的 {@code Objserialize.unSerial()} 进行反序列化。
	 * 如果文件不存在且非外部加载（{@code isOther=false}），则自动创建空文件。</p>
	 * 
	 * @param dataDir 存档文件路径
	 * @param isOther true=外部加载（无提示），false=内部加载（失败时显示提示并创建空文件）
	 * @return 路径数组，失败返回 null 或空数组
	 */
	public static String[] readListDataFile(String dataDir, boolean isOther) {
		String[] list = null;
		Urler file = new Urler(dataDir);
		if (file.getUrlFound()) {
			try {
				// 使用框架的反序列化工具
				list = sair.sacoms.Objserialize.unSerial(dataDir);
			} catch (Exception e) {
				list = null;
			}
		}

		// 内部加载失败时：提示并初始化空文件
		if (list == null && !isOther) {
			SairCons.println("列表文件读取失败!");
			list = new String[] {};
			saveListDataFile(dataDir, list);
		}
		return list;
	}

	/**
	 * 将路径数组序列化到磁盘。
	 * 
	 * <p>使用 SairFramework 的 {@code Objserialize.toSerial()} 进行序列化。
	 * 注意：调用前应确保旧文件已删除（Objserialize 不会覆盖已存在的文件）。</p>
	 */
	public static void saveListDataFile(String dataDir, String[] o) {
		try {
			sair.sacoms.Objserialize.toSerial(o, dataDir);
		} catch (Exception e) {
			SairCons.println("写入列表文件失败!");
		}
	}

	/**
	 * 递归扫描目录下所有 .mp3 文件。
	 * 
	 * <p>使用 {@code FileMana.getFilesToList(url, GETALL)} 获取所有文件子文件，
	 * 然后过滤出扩展名为 .mp3 的条目。</p>
	 * 
	 * @param url 目录路径
	 * @return 所有 .mp3 文件的绝对路径列表
	 */
	public static ArrayList<String> readMusicFilePath(String url) {
		ArrayList<String> r = new ArrayList<String>();
		SairLists<String> list = FileMana.getFilesToList(url, FileMana.GETALL);
		for (String path : list)
			if (new Urler(path).getEques(".mp3")) // 仅保留 .mp3 文件
				r.add(path);
		return r;
	}
}
