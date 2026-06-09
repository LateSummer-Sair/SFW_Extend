package sair.sacoms;

import java.io.Serializable;

import sair.sacoms.until.Ser;

/**
 *
 * <p>
 * IO简单序列化工具，需要被序列化的对象，必须继承 SerializesListenner接口<br>
 * 
 * @author _Sair
 * @version Objserialize_1.1
 * 
 **/

public class Objserialize {

	/**
	 * 反序列化
	 * <p>
	 * 将指定路径的序列化文件反序列化进内存，使用前不需要强制转化，若反序列化的對象不適配，將抛出錯誤<br>
	 * 
	 * @param url
	 *            目標路徑，绝对路径--String
	 * @return T_类型
	 **/
	public static <T extends Serializable> T unSerial(String url) throws Exception {
		return Ser.getOser(url);
	}

	/**
	 * 序列化
	 * <p>
	 * 将已经继承本类的对象序列化到指定路径的文件，有则覆盖，无则添加<br>
	 * 
	 * @param obj
	 *            需要被序列化的对象
	 * @param url
	 *            目標路徑，绝对路径--String
	 **/
	public static <T extends Serializable> void toSerial(T obj, String url) throws Exception {
		Ser.setOser(obj, url);
	}
}
