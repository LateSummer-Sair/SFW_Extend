package sair.sacoms.until;

public class Csi {
	private static final String charNumber = "0123456789";
	private static final String VMS = initVMS();
	private static final long[] VMSP = new long[] { 10L, 100L, 1000L, 10000L, 100000000L, 10000000000000000L };

	private static String[] Spl(String index, char arg0) {
		if (index == null || "".equals(index))
			return null;
		StringBuffer[] result = new StringBuffer[] { new StringBuffer(), new StringBuffer() };
		int id = 0;
		char cache;
		for (int i = 0; i < index.length(); i++) {
			cache = index.charAt(i);
			if (cache == arg0) {
				id++;
				i++;
			}
			if (i >= index.length())
				break;
			result[id].append(index.charAt(i));
		}
		return new String[] { result[0].toString(), result[1].toString() };
	}

	private static String initVMS() {
		StringBuilder sbf = new StringBuilder();
		for (int i = 1; i <= 6; i++)
			sbf.append(SFS.VM[i]);
		return sbf.toString();
	}

	public long StrChToMath(String index) {
		boolean hasLose = false;
		if (index != null) {
			int i = 0;
			for (; i < index.length(); i++)
				if ((index.charAt(i) == SFS.lowCh)) {
					hasLose = true;
					i++;
					break;
				}
			if (hasLose == true) {
				StringBuffer sbf = new StringBuffer();
				for (; i < index.length(); i++)
					sbf.append(index.charAt(i));
				index = sbf.toString();
			}
		}
		long result = StrChToLong(index);
		if (hasLose == true)
			result = -result;
		return result;
	}

	private long StrChToLong(String index) {
		long result = 0, left = 0, right = 0, nextUn = 0;
		char cache;
		String[] LR = null;
		if (index != null) {
			for (int i = 5; i >= 0; i--)
				for (int j = 0; j < index.length(); j++) {
					cache = index.charAt(j);
					if (VMS.charAt(i) == cache) {
						LR = Spl(index, cache);
						nextUn = VMSP[i];
						i = -1;
						break;
					}
				}
			if (LR == null) {
				if ("".equals(index))
					cache = 0;
				else
					cache = index.charAt(0);
				if (cache == SFS.SmallChMath[0] && index.length() == 2)
					cache = index.charAt(1);
				for (int i = 0; i < 10; i++) {
					if ((cache == SFS.SmallChMath[i]) || (cache == SFS.BigChMath[i])) {
						result = i;
						break;
					}
				}
			} else {
				if (nextUn == 0)
					nextUn = 1;
				left = StrChToLong(LR[0]) * nextUn;
				right = StrChToLong(LR[1]);
				result = left + right;
			}
		}
		return result;
	}

	public long StrMathToLong(String index) {
		long result = 0;
		char cache;
		boolean hasLose = false;
		if (index != null) {
			for (int i = 0; i < index.length(); i++) {
				cache = index.charAt(i);
				for (int j = 0; j < 10; j++)
					if (charNumber.charAt(j) == cache)
						result = result * 10 + j;
				if ((hasLose != true) && ('-' == cache))
					hasLose = true;
			}
			if (hasLose == true)
				result = 0 - result;
		}
		return result;
	}

	public double StrMathToFloat(String index) {
		double result = 0;
		char cache;
		if (index != null) {
			for (int i = index.length() - 1; i >= 0; i--) {
				cache = index.charAt(i);
				if (SFS.point == cache)
					break;
				for (int j = charNumber.length() - 1; j >= 0; j--)
					if (charNumber.charAt(j) == cache)
						result = (result + j) / 10;
			}
		}
		return result;
	}

	public double StrChToFloat(String index) {
		double result = 0;
		char cache;
		if (index != null)
			for (int i = index.length() - 1; i >= 0; i--) {
				cache = index.charAt(i);
				if (SFS.pointCh == cache)
					break;
				for (int j = 9; j >= 0; j--)
					if (VMS.charAt(j) == cache)
						result = (result + j) / 10;
			}
		return result;
	}

	public double getSStiII2(String index) {
		double result = 0;
		int len = index.length();
		for (int i = len - 1; i >= 0; i--)
			for (int j = 0; j < 10; j++)
				if (SFS.BigChMath[j] == index.charAt(i) || SFS.SmallChMath[j] == index.charAt(i)) {
					result = j + result / 10;
					break;
				}
		return (result /= 10);
	}

	public double getSStiII22(String index) {
		double result = 0;
		int len = index.length();
		for (int i = len - 1; i >= 0; i--)
			for (int j = 0; j < 10; j++)
				if (index.charAt(i) == charNumber.charAt(j)) {
					result = j + result / 10;
					break;
				}
		return (result /= 10);
	}
}
