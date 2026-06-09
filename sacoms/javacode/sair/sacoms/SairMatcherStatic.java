package sair.sacoms;

class SairMatcherStatic {

	static class CharPage {

		StringBuilder sb;
		int li;
		int ri;

		char[] bracket;

		public String toString() {
			this.sb = new StringBuilder();
			return sb.append(li).append("_").append(ri).append("_").append("lv = ").append(bracket).toString();
		}
	}

	static int chk(char c, char[][] rule, int LR) {
		for (int i = 0; i < rule.length; i++) {
			if (LR < rule[i].length)
				if (rule[i][LR] == c)
					return i;
		}
		return -1;
	}

	static char[] toCharArray(String str) {
		if (str == null)
			str = new String();
		return str.toCharArray();
	}
}
