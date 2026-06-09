package sair.sacoms.until;

/**
 * JMD命令切割对象接口
 * 
 * @author _Sair
 *
 */
public interface ObjectBySplits {
	/**
	 * 获取文件名/命令头
	 * 
	 * @return String类型
	 */
	String getHead();

	/**
	 * 获取命令结束指向字符串
	 * 
	 * @return String类型
	 */
	String getEnd();

	/**
	 * 获取命令空格后的其他部分 </br>
	 * 等效args，但是此方法返回的参数可以用默认回包工具重新回成单个String
	 * 
	 * @return String[]类型
	 */
	String[] getOtherRuns();
}
