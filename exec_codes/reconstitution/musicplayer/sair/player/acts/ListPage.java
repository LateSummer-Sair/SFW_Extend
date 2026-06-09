package sair.player.acts;

/**
 * 播放列表单项数据接口。
 * 
 * <p>由 {@link PlayerList#get(int)} 和 {@link PlayerList#remove(int)} 通过匿名类实现，
 * 提供对歌曲路径和文件名的只读访问。</p>
 * 
 * @see PlayerList 播放列表数据模型
 */
public interface ListPage {

	/** @return 歌曲文件的完整绝对路径 */
	public String getPath();

	/** @return 歌曲文件名（不含路径，如 "song.mp3"） */
	public String getName();
}
