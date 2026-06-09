package sair.sacoms.until;

/**
 * _Timmer类型的辅助处理接口
 * 
 * @author _Sair
 **/
public interface Times {

	public static final int[] modlist = { 86400, 3600, 60 };

	/**
	 * 获取计算日期具体差距
	 * 
	 * @return Integer[] 类型
	 **/
	int[] getDatas();

	/**
	 * 获取日期秒数差距
	 * 
	 * @return long 类型
	 **/
	long getTimes();
}
