package sair.sacoms;

import java.util.Stack;

import sair.sacoms.SairMatcherStatic.CharPage;

public class SairBracketMatcher {

	private static char[][] toRule(String[] rule) {
		if (rule == null)
			rule = new String[] {};
		char[][] rtcs = new char[rule.length][];
		for (int i = 0; i < rtcs.length; i++) {
			if (rule[i] == null)
				rule[i] = new String();
			rtcs[i] = rule[i].toCharArray();
		}
		return rtcs;
	}

	private static char[][] chkRule(char[][] rule) {
		if (rule == null || rule.length <= 0)
			return new char[][] { { 0, 0 } };
		for (int i = 0; i < rule.length; i++) {
			if (rule[i] == null || rule[i].length <= 0)
				rule[i] = new char[] { 0, 0 };
			else if (rule[i].length <= 1)
				rule[i] = new char[] { rule[i][0], rule[i][0] };
			else if (rule[i].length >= 2)
				rule[i] = new char[] { rule[i][0], rule[i][1] };
		}
		return rule;
	}

	private static boolean toSplit0(char[] cs, char[][] rule, SairBracketMatcher sbm) {
		Stack<CharPage> stack = new Stack<CharPage>();
		Stack<CharPage> resultStack = new Stack<CharPage>();
		boolean isCupCut = true;
		int rc = 0, lc = 0;
		for (int i = 0; i < cs.length; i++) {
			char c = cs[i];
			if (c == sbm.capChar) {
				if (isCupCut)
					isCupCut = false;
				else
					isCupCut = true;
				continue;
			}
			if (isCupCut) {
				int bracket = SairMatcherStatic.chk(c, rule, 0);
				if (bracket >= 0) {
					put(i, bracket, rule, stack, resultStack);
					lc++;
				} else {
					if (!stack.isEmpty()) {
						CharPage top = stack.peek();
						if (chkR(top, c) >= 0)
							pop(i, top, stack);
					}
				}
				if (SairMatcherStatic.chk(c, rule, 1) >= 0)
					rc++;
			}
		}
		boolean flag = stack.isEmpty() && (lc == rc) && isCupCut;
		makeStack(cs, resultStack, sbm);
		return flag;
	}

	private static void pop(int i, CharPage top, Stack<CharPage> stack) {
		top.ri = i;
		stack.pop();
	}

	private static void put(int i, int bracket, char[][] rule, Stack<CharPage> stack, Stack<CharPage> resultStack) {
		CharPage cp = new CharPage();
		cp.li = i;
		cp.bracket = rule[bracket];
		stack.push(cp);
		resultStack.push(cp);

	}

	private static int chkR(CharPage cp, char n) {
		if (cp.bracket[1] == n)
			return 0;
		return -1;
	}

	private static void makeStack(char[] cs, Stack<CharPage> resultStack, SairBracketMatcher sbm) {
		sbm.list = new String[resultStack.size() + 1];
		int k = 0;
		while (!resultStack.isEmpty()) {
			CharPage cp = resultStack.pop();

			int start = cp.li + 1;
			int end = cp.ri;
			StringBuilder sb = new StringBuilder();
			for (int i = start; i < end; i++)
				sb.append(cs[i]);
			sbm.list[k] = sb.toString();
			k++;

		}
		sbm.list[k] = new String(cs);
	}

	char capChar;
	char[] cs;
	char[][] rule;
	private boolean isTrueVaule;
	private boolean isChked;

	public SairBracketMatcher(char capChar, String str, String... rule) {
		this.reSetRule(rule);
		this.reSetString(str);
		this.reSetCapChar(capChar);
	}

	public SairBracketMatcher(char capChar, String str, char[]... rule) {
		this.reSetRule(rule);
		this.reSetString(str);
		this.reSetCapChar(capChar);
	}

	public SairBracketMatcher(char capChar, char[] cs, char[]... rule) {
		this.reSetRule(rule);
		this.reSetString(cs);
		this.reSetCapChar(capChar);
	}

	public SairBracketMatcher(char capChar, char[] cs, String... rule) {
		this.reSetRule(rule);
		this.reSetString(cs);
		this.reSetCapChar(capChar);
	}

	public void reSetCapChar(char capChar) {
		this.capChar = capChar;
	}

	public void reSetRule(String... rule) {
		reSetRule(toRule(rule));
	}

	public void reSetString(String str) {
		reSetString(SairMatcherStatic.toCharArray(str));
	}

	public void reSetRule(char[]... rule) {
		this.rule = chkRule(rule);
		this.isChked = false;
		this.isTrueVaule = false;
	}

	public void reSetString(char[] cs) {
		this.cs = cs;
		this.isChked = false;
		this.isTrueVaule = false;
	}

	public boolean chkSplit() {
		if (isChked)
			return isTrueVaule;
		isChked = true;
		return (isTrueVaule = toSplit0(cs, rule, this));
	}

	String[] list = null;

	public String[] split() {
		isTrueVaule = toSplit0(cs, rule, this);
		isChked = true;
		return list;
	}

	public String toString() {
		return new String(cs);
	}
}