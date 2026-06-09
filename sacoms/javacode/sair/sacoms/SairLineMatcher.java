package sair.sacoms;

public class SairLineMatcher {
	private SairBracketMatcher sbm;

	public SairLineMatcher(SairBracketMatcher sbm) {
		this.sbm = sbm;
		if (sbm != null)
			sbm.split();
	}

	public String getSuperFuncName(int index, char escapeChar, char... outRule) {
		if (sbm == null)
			return null;
		if (index < 0 || index >= sbm.list.length)
			return null;
		String line = sbm.list[index];
		String rule;
		if ((rule = creatRule(line, escapeChar)) != null)
			return findSuperFuncName(rule, line, outRule);
		return null;
	}

	public int getSBMLength() {
		if (sbm == null)
			return 0;
		return sbm.list.length;
	}

	public String get(int index) {
		if (sbm == null || index >= sbm.list.length || index < 0)
			return null;
		return sbm.list[index];
	}

	private String findSuperFuncName(String rule, String line, char[] outRule) {
		String[] sped = line.split(rule);
		if (sped.length <= 0)
			return null;
		if (chk(sped[0], new char[][] { outRule }) || "".equals(sped[0]) || String.valueOf(sbm.capChar).equals(sped[0]))
			return null;
		else
			return sped[0];
	}

	private String creatRule(String line, char escapeChar) {
		char[][] rules = sbm.rule;
		boolean flag = chk(line, rules);
		if (flag) {
			StringBuilder sb = new StringBuilder();
			for (char[] rule : rules)
				sb.append(escapeChar).append(rule[0]).append('|');
			if (sb.length() > 0)
				sb.deleteCharAt(sb.length() - 1);
			return sb.toString();
		}
		return null;
	}

	private boolean chk(String line, char[][] rules) {
		char[] cs = line.toCharArray();
		for (char c : cs) {
			if (-1 != SairMatcherStatic.chk(c, rules, 0))
				return true;
		}
		return false;
	}
}
