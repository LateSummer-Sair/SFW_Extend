package sair.sacoms;

import java.io.Serializable;

/**
 * Ô¿³×¸¸Àà
 * 
 * @author _Sair
 **/
public class Key<T> implements Serializable {
	/**
	 * 
	 */
	private static final long serialVersionUID = -8177649503151263000L;
	private T KeyS;

	public static <T> Key<T> creatKey(T abs) {
		return new Key<T>(abs);
	}

	public static <T> Key<T> creatKey() {
		return new Key<T>(null);
	}

	private Key(T abs) {
		KeyS = abs;
	}

	public T about() {
		return KeyS;
	}
}
