package sair.sacoms;

import java.util.Random;

/**
 *
 * <p>
 * 随机数<br>
 * 
 * @author _Sair
 * @version randoms_1.6
 * 
 **/

public class Randoms {
	/**
	 * 初始化
	 * <p>
	 * 初始化對象<br>
	 * 
	 * @param setDir
	 *            随机数样本--integer[]
	 **/
	public Randoms(int[] setDir) {
		setDir(setDir);
	}

	/**
	 * 初始化
	 * <p>
	 * 初始化對象<br>
	 **/
	public Randoms() {
	}

	/**
	 * 初始化
	 * <p>
	 * 初始化随机数样本<br>
	 * 
	 * @param setDir
	 *            随机数样本--integer[]
	 **/
	public void setDir(int[] setDir) {
		dir = setDir;
	}

	private int[] dir;
	private static Random random = new Random();

	/**
	 * 获取随机数
	 * <p>
	 * 获取指定大小的随机数，min_最小值、max_最大值<br>
	 * 
	 * @param min
	 *            最小值
	 * @param max
	 *            最大值
	 * @return Integer类型
	 **/
	public static int nextInt(int min, int max) {
		return random.nextInt(max) % (max - min + 1) + min;
	}

	/**
	 * 获取随机数
	 * <p>
	 * 获取指定范围的随机数<br>
	 * 
	 * @return Integer类型
	 **/
	public int nextInt() {
		if (dir != null && dir.length != 0)
			return dir[nextInt(0, dir.length)];
		return 0;
	}
}
