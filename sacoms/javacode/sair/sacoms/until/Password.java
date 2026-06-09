package sair.sacoms.until;

import sair.sacoms.Key;

/**
 * 密码父类
 * 
 * @author _Sair
 **/
public class Password {
	private Key<Long> Key;
	private char[] Passw;

	private Password(char[] passw, Key<Long> key) {
		Key = key;
		Passw = passw;
	}

	/**
	 * 新建密码
	 * <p>
	 * 
	 * @param pw
	 *            密码字符串
	 * @param creatKey
	 *            认证key（来自手动新建）
	 * 
	 * @return 返回类型为Password
	 **/
	public final static Password castoPassword(String pw, Key<Long> creatKey) {
		if (pw == null)
			return null;
		return new Password(pw.toCharArray(), creatKey);
	}

	/**
	 * 获取密码
	 * <p>
	 * 
	 * @param creatKey
	 *            认证key（来自手动新建）
	 * 
	 * @return 返回类型为char[]
	 **/
	public char[] getPassWordOf(Key<Long> creatKey) {
		if (!Key.equals(creatKey))
			return new char[0];
		return Passw;
	}
}
