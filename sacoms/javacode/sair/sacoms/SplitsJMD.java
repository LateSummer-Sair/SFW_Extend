package sair.sacoms;

import sair.sacoms.until.ObjectBySplits;

/**
 * 默认语法格式化工具
 */
public final class SplitsJMD implements ObjectBySplits {
	private String Head, End, jmdStr;
	private String[] runs;

	private SplitsJMD() {

	}

	/**
	 * 传入完整的语句进行封装
	 * 
	 * @param jmdStr
	 *            完整的JMD命令行语句
	 * @return SplitsJMD 类型
	 */
	public final static SplitsJMD split(String jmdStr) {
		return (SplitsJMD) new SplitsJMD().set(jmdStr);
	}

	/**
	 * 传入完整的OtherRuns进行反封装
	 * 
	 * @param id
	 *            被排除的值下标集合
	 * @param args
	 *            OtherRuns
	 * @return String
	 */
	public final static String ReturnOtherRunsToJMD(SairLists<Integer> id, String... args) {
		StringBuffer sbf = new StringBuffer();
		Integer isa = null;
		for (int i = 0; i < args.length; i++) {
			if (id != null) {
				isa = id.getHeadIndex();
				if (isa == null)
					isa = -1;
				if (i != isa)
					sbf.append(args[i]).append(" ");
				else
					id.reMove(0);
			} else
				sbf.append(args[i]).append(" ");
		}
		sbf.deleteCharAt(sbf.length() - 1);
		return sbf.toString();
	}

	/**
	 * 用于判断此语句是否为空尾语句且空参数,是空尾且空参数则返回true,反之false
	 * 
	 * @param spl
	 *            已经处理完的ObjectBySplits
	 * @return boolean 类型
	 */
	public final static boolean isNullEndAndNullArgs(ObjectBySplits spl) {
		if ("".equals(spl.getEnd()) && spl.getOtherRuns().length > 0 && "".equals(spl.getOtherRuns()[0]))
			return true;
		return false;
	}

	private void spliting() {
		if (jmdStr == null)
			return;
		StringBuffer usermod = new StringBuffer();
		if (jmdStr.length() >= 7) {
			for (int i = 0; i < 7; i++)
				usermod.append(jmdStr.charAt(i));
		}
		/*if (!For.name.equals(usermod.toString()))
			jmdStr = Var.varSet(jmdStr);*/
		StringBuffer[] sbfs = new StringBuffer[] { new StringBuffer(), new StringBuffer(), new StringBuffer() };
		SairLists<String> list = new SairLists<String>();
		list.setArrToList(StrEdit.splitStr(jmdStr));
		String str = "";
		String[] locaArr = { "/", " ", "" };
		for (int i = 0; i < sbfs.length; i++) {
			boolean isPath = false;
			while ((!locaArr[i].equals((str = list.getHeadIndex()))) && (str != null)) {
				if (isPath && locaArr[1].equals(str))
					str = "//";
				if ("\"".equals(str) && !isPath)
					isPath = true;
				else if ("\"".equals(str) && isPath)
					isPath = false;
				sbfs[i].append(str);
				list.reMove(0);
			}
			if (sbfs[i].length() <= 0)
				sbfs[i].append(locaArr[0]);
		}
		this.Head = sbfs[0].toString();
		this.End = sbfs[1].deleteCharAt(0).toString();
		this.runs = sbfs[2].deleteCharAt(0).toString().split(" ");
	}

	@Override
	public String getHead() {
		return Head;
	}

	@Override
	public String getEnd() {
		return End;
	}

	@Override
	public String[] getOtherRuns() {
		return runs;
	}

	private ObjectBySplits set(String str) {
		this.jmdStr = str;
		spliting();
		return this;
	}

	public String toString() {
		return jmdStr;
	}
}
