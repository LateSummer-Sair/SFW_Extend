package sair.fans;
import java.io.IOException;

import sair.sacoms.FileMana;
import sair.sacoms.MathCast;
import sair.sacoms.Timmer;
import sair.sacoms.Urler;

class AutoThread implements Runnable {

	private FansFrame ff;

	AutoThread(FansFrame ffm) {
		this.ff = ffm;
	}

	private boolean iscon = true;

	void close() {
		iscon = false;
	}

	public void run() {
		while (iscon) {
			Main m = ff.m;
			m.relink();
			if (m.get != null) {
				try {
					m.b = m.equals(m.b);
				} catch (IOException e) {
					//SaLogger.outLogger(e);
				}
				String o = "";
				if (m.b.other != null) {
					o = m.b.other;
					if ('+' == o.charAt(0))
						try {
							o = add(o);
						} catch (IOException e) {
							//SaLogger.outLogger(e);
						}
					else if ('-' == o.charAt(0))
						try {
							del(o);
						} catch (IOException e) {
							//SaLogger.outLogger(e);
						}
				}
				ff.info.setText("\r\nUP主ID：" + m.b.getName() + "\t\r\n\r\n当前粉丝数："
						+ MathCast.custoString(m.b.getFans(), MathCast.ToSmallChinese) + "\t\r\n\r\n" + o + "\t");
			}
			try {
				callJSB();
			} catch (InterruptedException e) {
				//SaLogger.outLogger(e);
			}
		}
	}

	private void callJSB() throws InterruptedException {
		int um = 10;
		int min = 0;
		for (int i = min; (i < ff.speed / um) && this.iscon; i++) {
			ff.jsb.setMaximum(ff.speed / um);
			ff.jsb.setMinimum(min);
			ff.jsb.setValue(i);
			Thread.sleep(um);
		}
	}

	private void del(String o) throws IOException {
		String[] strs = new String[] { Main.line, Timmer.getToday() + +'：', o };

		for (String str : strs)
			ff.add(str);

		FileMana.addToFilesEnd(chkfile(ff.m.UploaderUID, ff.m.getDataDir()), strs, true);
	}

	private String add(String o) throws IOException {
		boolean isstart = false;
		StringBuffer sbf = new StringBuffer(), head = new StringBuffer();
		for (int i = 0; i < o.length(); i++) {
			char c = o.charAt(i);
			if (c == '：' && !isstart) {
				isstart = true;
				continue;
			}
			if (isstart)
				sbf.append(c);
			else
				head.append(c);
		}

		String[] strs = new String[] { Main.line, Timmer.getToday() + '：' };

		ff.add(strs[0]);
		ff.add(strs[1]);

		String[] names = sbf.toString().split(",");
		for (String name : names)
			ff.add(name);

		FileMana.addToFilesEnd(chkfile(ff.m.UploaderUID, ff.m.getDataDir()), strs, true);
		FileMana.addToFilesEnd(chkfile(ff.m.UploaderUID, ff.m.getDataDir()), names, true);

		return head.toString();
	}

	static String chkfile(int uid, String MyPath) {

		String Path = MyPath + "UP_UID_" + uid + ".log";

		Urler path = new Urler(Path);
		if (!path.getUrlFound())
			try {
				path.creatThisUrl();
			} catch (IOException e) {
				//SaLogger.outLogger(e);
			}

		return Path;

	}
}
