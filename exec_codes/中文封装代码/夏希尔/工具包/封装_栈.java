package 夏希尔.工具包;

import java.util.Stack;

/**
 * 这个类,是 夏希尔括号匹配工具 中用来转义中文的封装类,封装的是工具类:栈_Stack
 */
public class 封装_栈<E> extends Stack<E> {
	private 封装_栈(){}

	public static <E> 封装_栈<E> 新建栈() {
		return new 封装_栈<E>();
	}

	private static final long serialVersionUID = -3033847235151163682L;

	/**
	 * 这个方法意味着将栈顶元素移出
	 * 
	 * @return 返回的就是移出的元素
	 */
	public E 出栈() {
		return this.pop();
	}

	/**
	 * 这个方法仅仅只是查看栈顶的元素,不会造成和出栈一样的操作
	 * 
	 * @return 返回的就是想要查看的栈顶元素
	 */
	public E 查看栈顶() {
		return this.peek();
	}

	/**
	 * 与添加的方法不同的是含义,这个是入栈,把它当作栈来用
	 * 
	 * @param 元素
	 *            这个元素是你自己自定的任意元素都行
	 * @return 返回值就是你自己入栈的元素
	 */
	public E 入栈(E 元素) {
		return this.push(元素);
	}

	/**
	 * 在末尾添加新元素,这个方法是把它当作集合来用
	 * 
	 * @param 元素
	 *            这个元素是你自己自定的任意元素都行
	 * @return 返回值就是你自己入栈的元素
	 */
	public boolean 添加(E 元素) {
		return this.add(元素);
	}

	public E[] 转数组(E[] 数组容器) {
		return this.toArray(数组容器);
	}

	public int 取容器内容大小() {
		return this.size();
	}

	public boolean 容器是否为空() {
		return this.isEmpty();
	}

}
