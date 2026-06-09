package sair.sacoms.until;

import java.util.HashMap;
import java.util.regex.Pattern;


public class Cis {

	private static final HashMap<Character, Integer> VMS = initVMS();

	private static final HashMap<Character, Integer> initVMS() {
		HashMap<Character, Integer> map = new HashMap<Character, Integer>();
		for (int i = 0; i < SFS.VM.length; i++)
			map.put(SFS.VM[i], i);
		return map;
	}

	private static final char[] VMA = initVMA();

	private static char[] initVMA() {
		char[] vma = new char[4];
		for (int i = vma.length - 1, j = 0; i >= 0; i--, j++)
			vma[i] = SFS.VM[j];
		return vma;
	}

	public String superLongCustToStringChFloatRight(String math, char[] sn) {
		if (math == null || math.length() <= 0)
			return "";
		math = Pattern.compile("[^0-9]").matcher(math).replaceAll("").trim().replaceAll(" ", "");
		StringBuilder sbf = new StringBuilder();
		for (int i = 0; i < math.length(); i++)
			sbf.append(sn[math.charAt(i) - 48]);
		return sbf.toString();
	}

	public String superLongCustToStringCh(String math, char[] sn) {
		if (math == null || math.length() <= 0)
			return "";
		boolean hasLow = false;
		if (SFS.low == math.charAt(0))
			hasLow = true;
		math = Pattern.compile("[^0-9]").matcher(math).replaceAll("").trim().replaceAll(" ", "");
		StringBuilder castFinal = CastW(Cast(Cut(math), sn));
		if (hasLow)
			castFinal.insert(0, SFS.lowCh);
		return castFinal.toString();
	}

	private StringBuilder CastW(String[] castR) {
		StringBuilder sbf = new StringBuilder();
		for (int i = castR.length - 1; i >= 0; i--)
			sbf.insert(0, castR[i]);
		custAllW(sbf, SFS.VM[4]);
		return sbf;
	}

	private void custAllW(StringBuilder s, char w) {
		int castKey = 0;
		int next = VMS.get(w) + 1;
		for (int i = s.length() - 1; i >= 0; i--) {
			if (s.charAt(i) == w)
				castKey++;
			if (castKey >= 2) {
				if (s.charAt(i) == s.charAt(i + 1))
					s.deleteCharAt(i + 1);
				castKey = 0;
				s.setCharAt(i, SFS.VM[next]);
			}
		}
		if (s.toString().contains(String.valueOf(SFS.VM[next])))
			custAllW(s, SFS.VM[next]);
	}

	private String[] Cast(String[] caps, char[] sn) {
		String[] result = new String[caps.length];
		result[0] = castSingle(caps[0], true, sn);
		for (int i = result.length - 1; i >= 1; i--)
			result[i] = castSingle(caps[i], false, sn);
		return result;
	}

	private String castSingle(String cap, boolean isHead, char[] sn) {
		StringBuilder sbf = new StringBuilder();
		for (int i = cap.length() - 1, j = VMA.length - 1; i >= 0; i--, j--) {
			char c = cap.charAt(i), t = VMA[j];
			if (c != SFS.zero)
				sbf.insert(0, t).insert(0, sn[c - 48]);
			else if ((sbf.length() > 0) && (sbf.charAt(0) != sn[0]))
				sbf.insert(0, sn[c - 48]);
		}
		if (!isHead)
			sbf.insert(0, SFS.VM[4]);
		else if (isHead && cap.length() == 1)
			return String.valueOf(sn[cap.charAt(0) - 48]);
		if (sbf.length() > 0 && sbf.charAt(sbf.length() - 1) == SFS.splits)
			sbf.deleteCharAt(sbf.length() - 1).toString();
		return sbf.toString();
	}

	private String[] Cut(String sti) {
		int len = sti.length() / 4;
		if ((sti.length() % 4 != 0) || sti.length() < 4)
			len++;
		String[] resultCaps = new String[len];
		StringBuilder ca = new StringBuilder();
		int subKey = 0, capsKey = len - 1, i = sti.length() - 1;
		while (i >= -1) {
			if (subKey < 4 && i >= 0) {
				ca.insert(0, sti.charAt(i));
				subKey++;
			} else if (capsKey >= 0) {
				resultCaps[capsKey] = ca.toString();
				capsKey--;
				ca = new StringBuilder();
				subKey = 0;
				continue;
			}
			i--;
		}
		return resultCaps;
	}

}
