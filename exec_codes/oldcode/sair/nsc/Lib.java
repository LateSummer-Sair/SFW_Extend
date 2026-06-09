package sair.nsc;
 
import java.util.HashMap;

/**
 * Global constant pool
 * 
 * @author Sair
 **/
public class Lib {
	public static final String newLink = "newLink";
	public static final String getList = "getList";
	public static final char findRun = '@';
	public static final String SERVER_INFO_TI = "[SERVER INFO] ";
	static final HashMap<String, MOD> map = new HashMap<String, MOD>();

	/**
	 * 
	 *
	 * A service provided for the use of external packages Lib.map How to do it
	 * 
	 * @param name
	 *            hashName
	 * @param o
	 *            MOD Object
	 **/
	public static final void addObject(String name, MOD o) {
		map.put(name, o);
	}
}
