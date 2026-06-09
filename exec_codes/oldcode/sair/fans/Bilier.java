package sair.fans;

class Bilier {

	private void setName(String name) {
/*		if (name != null)
			try {
				name = new String(name.getBytes(), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				SaLogger.outLogger(e);
			}*/

		this.name = name;
	}

	private void setFans(int fans) {
		this.fans = fans;
	}

	private String name;
	private int fans;
	String other = null;

	Bilier(String name, int fans) {
		this.setName(name);
		this.setFans(fans);
	}

	int getFans() {
		return fans;
	}

	String getName() {
		return name;
	}
}
