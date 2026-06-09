package sair.sacoms;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import sair.sacoms.until.Cis;
import sair.sacoms.until.Csi;
import sair.sacoms.until.SFS;

/**
 * 数形转换工具
 * 
 * @version 2.1
 * @author Small
 **/
public class MathCast {
	private static final String[] superDouble = { "乘十的", "次方" };

	private static Csi csi = new Csi();
	private static Cis cis = new Cis();
	/**
	 * 小写中文钥匙
	 **/
	public final static Key<MathCast> ToSmallChinese = Key.creatKey();
	/**
	 * 大写中文钥匙
	 **/
	public final static Key<MathCast> ToBigChinese = Key.creatKey();
	/**
	 * 默认钥匙
	 **/
	public final static Key<MathCast> ToDefault = Key.creatKey();

	/**
	 * 字符串转双精度小数逆转
	 * <p>
	 * String类型的中国汉字转化为double类型并返回<br>
	 * 
	 * @param v
	 *            需要被转换的参数
	 * @return double类型
	 **/
	public static double StringToDouble(String v) {
		boolean YnD = false;
		StringBuilder Ints = new StringBuilder(), Ds = new StringBuilder();
		int len = v.length();
		for (int i = 0; i < len; i++) {
			char c = v.charAt(i);
			if (SFS.pointCh == c) {
				YnD = true;
				continue;
			}
			if (YnD == false)
				Ints.append(c);
			else if (YnD == true)
				Ds.append(c);
		}
		int backInt = StringsStringToInt(Ints.toString());
		double backDouble = csi.getSStiII2(Ds.toString());
		if (backInt < 0)
			return -((-backInt) + backDouble);
		else
			return backInt + backDouble;
	}

	/**
	 * 字符串转双精度小数转化
	 * <p>
	 * double类型的小数转化为String类型并返回，转化Key在MathCast提供<br>
	 * 
	 * @param v
	 *            需要被转换的参数
	 * @param how
	 *            转化Key
	 * @return String类型
	 **/
	public static String DoubleToString(double v, Key<MathCast> how) {
		String[] splited = String.valueOf(new BigDecimal(v).setScale(8, BigDecimal.ROUND_HALF_UP).doubleValue())
				.split("\\.");
		String cf = null;
		String splited0 = superLongCustToStringCh(splited[0], how);
		if (splited[1].length() > 8) {
			String[] caArr = splited[1].split("E");
			splited[1] = caArr[0];
			cf = caArr[1];
		}
		String splited1 = LongStrCustoStrSS2(splited[1], how);
		StringBuilder sbf = new StringBuilder(splited1);
		if (cf != null)
			sbf.append(superDouble[0]).append(superLongCustToStringCh(cf, how)).append(superDouble[1]);
		return sbf.insert(0, SFS.pointCh).insert(0, splited0).toString();
	}

	/**
	 * 字符串转双精度小数逆转
	 * <p>
	 * String类型的double汉字转化为double类型小数并返回<br>
	 * 
	 * @param v
	 *            需要被转换的参数
	 * @return double类型
	 **/
	public static double dstringToDouble(String v) {
		boolean YnD = false;
		StringBuilder Ints = new StringBuilder(), Ds = new StringBuilder();
		int len = v.length();
		for (int i = 0; i < len; i++) {
			char c = v.charAt(i);
			if (SFS.point == c) {
				YnD = true;
				continue;
			}
			if (YnD == false)
				Ints.append(c);
			else if (YnD == true)
				Ds.append(c);
		}
		int backLong = StringsIntToInt(Ints.toString());
		double backDouble = csi.getSStiII22(Ds.toString());
		if (backLong < 0)
			return -((-backLong) + backDouble);
		else
			return backLong + backDouble;
	}

	/**
	 * 整数转字符串转化
	 * <p>
	 * Integer类型的数字转化为字符串类型并返回，转化Key在MathCast提供<br>
	 * 
	 * @param v
	 *            需要被转换的参数
	 * @param how
	 *            转化Key
	 * @return String类型
	 **/
	public static String IntToString(int v, Key<MathCast> how) {
		return custoString(v, how);
	}

	/**
	 * 字符串转整数转化
	 * <p>
	 * String类型的汉字转化为Integer类型并返回<br>
	 * 
	 * @param v
	 *            需要被转换的参数
	 * @return Integer类型
	 **/
	public static int StringsStringToInt(String v) {
		return Integer.valueOf(String.valueOf(StrChCustoLong(v)));
	}

	/**
	 * 字符串转整数转化
	 * <p>
	 * String类型的数字转化为Integer类型并返回<br>
	 * 
	 * @param v
	 *            需要被转换的参数
	 * @return Integer类型
	 **/
	public static int StringsIntToInt(String v) {
		return Integer.valueOf(String.valueOf(StrMathCustoLong(v)));
	}

	/**
	 * 字符串转汉字字符串转化
	 * <p>
	 * String类型的数字转化为String类型的汉字并返回，转化Key在MathCast提供<br>
	 * 
	 * @param v
	 *            需要被转换的参数
	 * @param how
	 *            转化Key
	 * @return String类型
	 **/
	public static String StringIntToStringSS2(String v, Key<MathCast> how) {
		return LongStrCustoStrSS2(v, how);
	}

	/**
	 * 长整数转字符串转化
	 * <p>
	 * Long类型的数字转化为字符串类型并返回，转化Key在MathCast提供<br>
	 * 
	 * @param v
	 *            需要被转换的参数--long类型
	 * @param key
	 *            转化Key
	 * @return String类型
	 **/
	public static String custoString(long v, Key<MathCast> key) {
		return custoString(String.valueOf(v), key);
	}

	/**
	 * 长整数转字符串转化
	 * <p>
	 * Long类型的数字转化为字符串类型并返回，转化Key在MathCast提供<br>
	 * 
	 * @param v
	 *            需要被转换的参数--String类型
	 * @param key
	 *            转化Key
	 * @return String类型
	 **/
	public static String custoString(String v, Key<MathCast> key) {
		return superLongCustToStringCh(v, key);
	}

	/**
	 * 长整数转字符串转化
	 * <p>
	 * String类型的数字转化为Long类型并返回<br>
	 * 
	 * @param v
	 *            需要被转换的参数--"123"
	 * @return long类型
	 **/
	public static long StrMathCustoLong(String v) {
		return csi.StrMathToLong(v);
	}

	/**
	 * 长整数转字符串转化
	 * <p>
	 * String类型的汉字转化为Long类型并返回<br>
	 * 
	 * @param v
	 *            需要被转换的参数--"一百二十三"
	 * @return long类型
	 **/
	public static long StrChCustoLong(String v) {
		return csi.StrChToMath(v);
	}

	/**
	 * 长整数转字符串转化为不读单位的字符串
	 * <p>
	 * String类型的数字转化为不读单位的字符串类型并返回，转化Key在MathCast提供<br>
	 * <br>
	 * 
	 * @param v
	 *            需要被转换的参数--"123"
	 * @param key
	 *            转化Key
	 * @return String类型
	 **/
	public static String LongStrCustoStrSS2(String v, Key<MathCast> key) {
		char[] sn = SFS.SmallChMath;
		if (ToBigChinese.equals(key))
			sn = SFS.BigChMath;
		else if (ToDefault.equals(key))
			return Pattern.compile("[^0-9]").matcher(String.valueOf(v)).replaceAll("").trim().replaceAll(" ", "");
		return cis.superLongCustToStringChFloatRight(v, sn);
	}

	/**
	 * 超长整数转字符串转化为中文字符串
	 * <p>
	 * String类型的数字转化为超长整数转字符串转化为中文字符串类型并返回，转化Key在MathCast提供<br>
	 * <br>
	 * 
	 * @param v
	 *            需要被转换的参数--"123"
	 * @param key
	 *            转化Key
	 * @return String类型
	 **/
	private static String superLongCustToStringCh(String v, Key<MathCast> key) {
		char[] sn = SFS.SmallChMath;
		if (ToBigChinese.equals(key))
			sn = SFS.BigChMath;
		else if (ToDefault.equals(key))
			return Pattern.compile("[^0-9]").matcher(String.valueOf(v)).replaceAll("").trim().replaceAll(" ", "");
		return cis.superLongCustToStringCh(v, sn);
	}
}
