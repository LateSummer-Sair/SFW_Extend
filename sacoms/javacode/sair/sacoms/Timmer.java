package sair.sacoms;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import sair.sacoms.until.Th;
import sair.sacoms.until.Times;

/**
 * timmer_时间工具
 * <p>
 * 提供了计时器与时间获取器<br>
 * 
 * @author _Sair
 * @version timmer_2.7
 * 
 **/

public class Timmer {
	private static final String TIMMER_STR = "Asia/Shanghai";
	private static final String TIMMER_YEAR = "年";
	private static final String TIMMER_MON = "月";
	private static final String TIMMER_DAY = "日";
	private static final String TIMMER_H = "时";
	private static final String TIMMER_M = "分";
	private static final String TIMMER_S = "秒";
	private static final Integer[] P = new Integer[] { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
	private static final Integer[] R = new Integer[] { 31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
	private static final int MON_LEN = 12;
	private static final int[][] capsRul = new int[][] { { 0, 4 }, { 4, 6 }, { 6, 8 }, { 8, 10 }, { 10, 12 },
			{ 12, 14 } };

	private static SimpleDateFormat sdc = new SimpleDateFormat("yyyy" + TIMMER_YEAR + "MM" + TIMMER_MON + "dd"
			+ TIMMER_DAY + "HH" + TIMMER_H + "mm" + TIMMER_M + "ss" + TIMMER_S);
	private static Date date;
	private long use;
	private SimpleDateFormat sd;
	private Th th;

	/**
	 * 初始化类对象
	 * <p>
	 * 无参数将初始化计时器，并关闭计时器<br>
	 **/
	public Timmer() {
		this(false);
	}

	/**
	 * 初始化类对象
	 * <p>
	 * char参数控制了日期格式，boolean参数控制了计时器开关<br>
	 * 
	 * @param insertCharb
	 *            是否开启计时器
	 * @param insertChar
	 *            日期之间的分隔符
	 * @param insertCharT
	 *            时间之间的分隔符
	 **/
	public Timmer(boolean insertCharb, char insertChar, char insertCharT) {
		this(insertCharb);
		this.formate(insertChar, insertCharT);
	}

	/**
	 * 初始化类对象
	 * <p>
	 * char参数控制了日期格式，boolean参数控制了计时器开关<br>
	 * 
	 * @param insertChar
	 *            日期之间的分隔符
	 * @param insertCharT
	 *            时间之间的分隔符
	 **/
	public Timmer(char insertChar, char insertCharT) {
		this(false, insertChar, insertCharT);
	}

	/**
	 * 初始化类对象
	 * <p>
	 * boolean参数控制了计时器开关<br>
	 * 
	 * @param insertCharb
	 *            是否开启计时器
	 **/
	public Timmer(boolean insertCharb) {
		if (insertCharb == true)
			Start();
	}

	/**
	 * 获取日期
	 * <p>
	 * 获取当前日期<br>
	 * 
	 * @return String类型
	 **/
	public String getTodayStr() {
		date = new Date();
		String back = "";
		try {
			back = sd.format(date);
		} catch (Exception e) {
			this.dateDef();
			back = sd.format(date);
		}
		return back;
	}

	/**
	 * 获取日期
	 * <p>
	 * 获取当前日期<br>
	 * 
	 * @return String类型
	 **/
	public static String getToday() {
		date = new Date();
		sdc.setTimeZone(TimeZone.getTimeZone(TIMMER_STR));
		return sdc.format(date);
	}

	/**
	 * 获取日期格式
	 * <p>
	 * 获取当前日期格式<br>
	 * 
	 * @return SimpleDateFormat类型
	 **/
	public static SimpleDateFormat getSDF() {
		sdc.setTimeZone(TimeZone.getTimeZone(TIMMER_STR));
		return sdc;
	}

	/**
	 * 恢复成默认日期格式
	 * <p>
	 * 将日期格式恢复成默认的汉字日期格式（ 年 月 日 时 分 秒）<br>
	 **/
	public synchronized void dateDef() {
		this.sd = sdc;
		this.sd.setTimeZone(TimeZone.getTimeZone(TIMMER_STR));
	}

	/**
	 * 更改日期格式
	 * <p>
	 * 将日期格式更改成指定格式<br>
	 * 
	 * @param insertChar
	 *            时间格式化字符串
	 **/
	public synchronized void formate(char insertChar, char insertCharT) {
		this.sd = new SimpleDateFormat(
				"yyyy" + insertChar + "MM" + insertChar + "dd HH" + insertCharT + "mm" + insertCharT + "ss");
		this.sd.setTimeZone(TimeZone.getTimeZone(TIMMER_STR));
	}

	/**
	 * 获取年份
	 * <p>
	 * 获取当前日期年份<br>
	 * 
	 * @return Integer类型
	 **/
	public synchronized int getYear() {
		return MathCast.StringsIntToInt(Timmer.getToday().substring(0, 4));
	}

	/**
	 * 获取月份
	 * <p>
	 * 获取当前月期年份<br>
	 * 
	 * @return Integer类型
	 **/
	public synchronized int getMon() {
		return MathCast.StringsIntToInt(Timmer.getToday().substring(5, 7));
	}

	/**
	 * 获取日份
	 * <p>
	 * 获取当前日期日份<br>
	 * 
	 * @return Integer类型
	 **/
	public synchronized int getDay() {
		return MathCast.StringsIntToInt(Timmer.getToday().substring(8, 10));
	}

	/**
	 * 获取小时
	 * <p>
	 * 获取当前日期小时<br>
	 * 
	 * @return Integer类型
	 **/
	public synchronized int getHour() {
		return MathCast.StringsIntToInt(Timmer.getToday().substring(11, 13));
	}

	/**
	 * 获取分钟
	 * <p>
	 * 获取当前日期分钟<br>
	 * 
	 * @return Integer类型
	 **/
	public synchronized int getMin() {
		return MathCast.StringsIntToInt(Timmer.getToday().substring(14, 16));
	}

	/**
	 * 获取秒
	 * <p>
	 * 获取当前日期秒数<br>
	 * 
	 * @return Integer类型
	 **/
	public synchronized int getS() {
		return MathCast.StringsIntToInt(Timmer.getToday().substring(17, 19));
	}

	/**
	 * 获取计时器时间
	 * <p>
	 * 获取计时器从开始到目前的统计时间<br>
	 * 
	 * @return Long类型
	 **/
	public synchronized long getUseTime() {
		return use;
	}

	/**
	 * 設置计时器时间
	 * <p>
	 * 設置计时器的开始时间<br>
	 * 
	 **/
	public synchronized void setStartTime(long time) {
		use = time;
	}

	/**
	 * 开始计时器
	 * <p>
	 * 打开计时器开始计时，每次运行此方法将重置计时器<br>
	 **/
	public synchronized void Start() {
		if ((th == null) || (th != null && th.isStart() == false)) {
			reStartUseTime();
			th = new Th(true, this);
			new Thread(th).start();
		}
	}

	/**
	 * 设置计时器内部方法
	 * <p>
	 * 打开计时器开始计时，达到标准自动触发执行<br>
	 **/
	public synchronized Timmer setTimmerRunnable(Runnable run) {
		if (th == null)
			Start();
		th.setTimmerRunnable(run);
		return this;
	}

	/**
	 * 停止计时器
	 * <p>
	 * 停止计时器计时<br>
	 **/
	public synchronized void Stop() {
		if (th != null)
			this.th.setStart(false);
	}

	/**
	 * 重置计时器
	 * <p>
	 * 重置计时器计时<br>
	 **/
	public synchronized void reStartUseTime() {
		use = 0;
	}

	/**
	 * 設置计时器間隔
	 * <p>
	 * 設置計時器睡眠時間間隔<br>
	 * 
	 * @param sleeptime
	 *            睡眠時間---毫秒
	 **/
	public synchronized void setSleepTime(long sleeptime) {
		if (th != null)
			th.setSleep(sleeptime);
	}

	/**
	 * 計時器自增一秒
	 * <p>
	 * 設置計時器時間调整自增一秒<br>
	 **/
	public synchronized void setUseMe() {
		use += 1;
	}

	/**
	 * 获取平闰年列表
	 * <p>
	 * 获取指定年的月列表<br>
	 * 
	 * @param year
	 *            年数
	 * @return Integer[]类型
	 **/
	public static Integer[] yearFactory(int year) {
		if (year % 4 == 0 && year % 100 != 0 || year % 400 == 0)
			return R;// 闰年
		else
			return P;// 平年
	}

	/**
	 * 标准时间比较器(已经在此版本重新定向到格式化比较器)
	 * <p>
	 * 比较已经显示的数字时间秒数差距<br>
	 * 
	 * @param time_1
	 *            时间1
	 * @param time_2
	 *            时间2
	 * @return Time类型
	 **/
	public static Times getTime(String time_1, String time_2) {
		return getTime_chk(time_1, time_2);
	}

	/**
	 * 格式化时间比较器
	 * <p>
	 * 比较已经显示的数字时间秒数差距（强制格式化时间）<br>
	 * 
	 * @param time_1
	 *            时间1
	 * @param time_2
	 *            时间2
	 * @return Time类型
	 **/
	public static Times getTime_chk(String time_1, String time_2) {
		if (time_1 == null || time_2 == null)
			return null;
		String[] times = { time_1, time_2 };

		String[][] timeList = new String[][] { times[0].replaceAll("[^0-9]", " ").split(" "),
				times[1].replaceAll("[^0-9]", " ").split(" ") };
		String[][] arrStr = new String[2][capsRul.length];

		if (chkList_SetArr(times, timeList, arrStr)) {

			Integer[][] allArr = new Integer[2][capsRul.length];
			for (int i = 0; i < arrStr.length; i++)
				for (int j = 0; j < capsRul.length; j++)
					allArr[i][j] = Integer.parseInt(arrStr[i][j]);

			return setTimes_chk(allArr[0], allArr[1]);
		} else
			return null;
	}

	private static boolean chkList_SetArr(String[] times, String[][] timeList, String[][] arrStr) {

		if (arrStr[0].length < capsRul.length || arrStr[1].length < capsRul.length)
			return false;

		for (int i = 0; i < timeList.length; i++) {
			int elementCount = 0;
			for (int j = 0; j < timeList[i].length && elementCount < arrStr[i].length; j++) {
				String str = timeList[i][j];
				if (!"".equals(str)) {
					arrStr[i][elementCount] = str;
					elementCount++;
				}
			}
			if (elementCount < capsRul.length) {
				times[i] = times[i].replaceAll("[^0-9]", "");
				timeList[i] = split(times[i]);
				i--;
			}
		}

		return true;
	}

	private static String[] split(String time) {
		String[] spBak = new String[capsRul.length];
		int timeKey = 0;
		for (int i = 0; i < capsRul.length; i++) {
			StringBuffer sbf = new StringBuffer();
			if (time.length() > timeKey)
				for (timeKey = capsRul[i][0]; timeKey < capsRul[i][1] && timeKey < time.length(); timeKey++)
					sbf.append(time.charAt(timeKey));
			spBak[i] = sbf.toString();
		}
		return spBak;
	}

	private static Times setTimes_chk(Integer[] time1, Integer[] time2) {
		if (time1.length < capsRul.length || time2.length < capsRul.length)
			return null;
		Integer[][] allTime = chkInitArr(time1, time2);

		int[] backlist = new int[capsRul.length];
		long backlong = startCount(allTime[0], allTime[1]);
		long cache = backlong;

		for (int i = 2; i < backlist.length; i++) {
			if (i < backlist.length - 1) {
				backlist[i] = (int) (cache / Times.modlist[i - 2]);
				cache = (int) (cache % Times.modlist[i - 2]);
			} else
				backlist[i] = (int) cache;
		}

		return new Times() {
			private int[] backintarr;
			private long backlong;

			@Override
			public int[] getDatas() {
				return backintarr;
			}

			@Override
			public long getTimes() {
				return backlong;
			}

			@Override
			public String toString() {
				StringBuffer sb = new StringBuffer();
				sb.append("Ts:");
				sb.append(backlong);
				sb.append(" [");
				if (backintarr != null && backintarr.length >= 4) {
					sb.append(backintarr[2]);
					sb.append(' ');
					sb.append(backintarr[3]);
					sb.append(':');
					sb.append(backintarr[4]);
					sb.append(':');
					sb.append(backintarr[5]);
				} else {
					sb.append("ErrorTimeFormat");
				}
				sb.append(']');
				return sb.toString();
			}

			public Times set(long backlong, int[] backintarr) {
				this.backlong = backlong;
				this.backintarr = backintarr;
				return this;
			}
		}.set(backlong, backlist);//
	}

	private static Integer[][] chkInitArr(Integer[] time1, Integer[] time2) {
		Integer[][] itg = { time1, time2 };

		for (int i = 0; i < capsRul.length; i++) {
			if (time1[i] > time2[i])
				return new Integer[][] { time2, time1 };
			else if (time1[i] < time2[i])
				return itg;
			else
				continue;
		}
		return itg;
	}

	// 0 1 2 3 4 5
	// 年 月 日 时 分 秒
	private static long startCount(Integer[] ts, Integer[] te) {
		return 60L
				* ((60 * (24 * (countYearAllDay(ts[0], te[0]) - (countYearHeadDay(ts[1], ts[2], yearFactory(ts[0]))
						+ countYearEndDay(te[1], te[2], yearFactory(te[0])))) + (te[3] - ts[3]))) + (te[4] - ts[4]))
				+ te[5] - ts[5];
	}

	/**
	 * 格式化时间比较器辅助计算器
	 * <p>
	 * 计算大致月数列表用作取中间时间计算<br>
	 * 
	 * @param startYear
	 *            开始时间（小）
	 * @param endYear
	 *            结束时间（大）
	 * @return int类型
	 **/
	private static int countYearAllDay(int startYear, int endYear) {
		int buf = 0;
		for (int keyYear = startYear; keyYear <= endYear; keyYear++) {
			Integer[] integerList = yearFactory(keyYear);
			for (int monDay : integerList)
				buf += monDay;
		}
		return buf;
	}

	private static int countYearHeadDay(int mon, int day, Integer[] table) {
		int buf = 0;
		for (int i = 0; i < (mon - 1); i++)
			buf += table[i];
		return (buf += day);
	}

	private static int countYearEndDay(int mon, int day, Integer[] table) {
		int buf = 0;
		for (int i = table.length - 1; i > (mon - 1); i--)
			buf += table[i];
		return (buf += (table[mon - 1] - day));
	}

	/**
	 * 时间转化秒数
	 * 
	 * @param time
	 *            格式为hh:mm:ss
	 * @return long
	 **/
	public static long castTime(String time) {
		if (time == null)
			return 0;
		String[] sp = time.split(":");
		if (sp.length < 3)
			return 0;
		int sph = MathCast.StringsIntToInt(sp[0]);
		int spm = MathCast.StringsIntToInt(sp[1]);
		int sps = MathCast.StringsIntToInt(sp[2]);
		spm += sph * 60;
		sps += spm * 60;
		return sps;
	}

	/**
	 * 格式化时间比较器辅助计算器
	 * <p>
	 * 计算大致月数列表用作取中间时间计算<br>
	 * 
	 * @param s
	 *            开始时间（小）
	 * @param e
	 *            结束时间（大）
	 * @return SairLists<Integer>类型
	 **/
	@Deprecated
	public static SairLists<Integer> counterMons(int[] s, int[] e) {
		SairLists<Integer> list = new SairLists<Integer>(), list_back = new SairLists<Integer>();
		for (int i = 0, head = s[0]; i < e[0] - s[0] + 1; i++)
			list.setArrToList(yearFactory(head++));
		for (int i = s[1]; i <= list.getLength() - (MON_LEN - e[1]); i++)
			list_back.add(list.getIndex((i - 1)));
		return list_back;
	}

}
