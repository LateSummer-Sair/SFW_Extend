package sair.fans;

import java.io.IOException;
import java.net.URL;

import sair.sacoms.MathCast;
import sair.sys.SairCons;
import sair.user.Activity;

public class Main extends Activity {

	// 33567111
	/*
	 * public static void main(String[] args) {
	 * 
	 * JMD.toTest(Cons.creatAnCons("f", new Main()), "f/frame"); }
	 */

	static final String line = "--------------------------";

	int UploaderUID;
	Get get;
	Bilier b = null;
	FansFrame ff = new FansFrame(this);

	@Override
	public Object main(String name, String args) {
		if (!this.ff.isVisible()) {
			String end = name;
			String arg = "-1";
			if (args != null && !"".equals(args))
				arg = args;
			if ("setuid".equals(end)) {
				UploaderUID = MathCast.StringsIntToInt(arg);
				return relink();
			} else if ("get".equals(end))
				return toGet();
			else if ("frame".equals(end)) {
				ff.setVisible(true);
			}
		} else {
			SairCons.println("已使用了窗体的话，请使用窗体操作！");
			return true;
		}
		return false;
	}

	boolean relink() {
		if (UploaderUID >= 0) {
			try {
				get = new Get(new URL("http://api.bilibili.com/x/space/acc/info?mid=" + UploaderUID),
						new URL("https://api.bilibili.com/x/relation/stat?vmid=" + UploaderUID + "&jsonp=jsonp"));
			} catch (Exception e) {
				// SaLogger.outLogger(e);
			}
			return true;
		} else {
			SairCons.println("请设置正确的UID!");
			return false;
		}
	}

	private boolean toGet() {
		if (get != null) {
			try {
				b = equals(b);
			} catch (IOException e) {
				// SaLogger.outLogger(e);
			}
			SairCons.println("---------------------------------------------");
			SairCons.println("UP主ID:" + b.getName());
			SairCons.println("当前粉丝数量:" + MathCast.custoString(b.getFans(), MathCast.ToSmallChinese));
			SairCons.println(b.other);
			SairCons.println("---------------------------------------------");
		} else {
			SairCons.println("请先设置正确的UID!");
		}
		return true;
	}

	Bilier equals(Bilier b) throws IOException {
		Bilier newb = get.getData();
		if (b != null) {
			if (b.getName().equals(newb.getName())) {
				int newfansNub = newb.getFans();
				int oldfansNub = b.getFans();
				int i = newfansNub - oldfansNub;
				if (newfansNub > oldfansNub)
					newb.other = "+【涨粉 = " + MathCast.custoString(i, MathCast.ToSmallChinese) + "人】："
							+ Get.getNameOfNewestFans(UploaderUID, newfansNub - oldfansNub);
				else if (newfansNub < oldfansNub)
					newb.other = "-【掉粉 = " + MathCast.custoString(0 - i, MathCast.ToSmallChinese) + "人】";
				else
					newb.other = "=【没有变化】";
			}
		}
		return newb;
	}

	@Override
	public void exit() {
		if (this.ff != null && this.ff.th != null)
			this.ff.th.close();
		this.ff.setVisible(false);
	}

	public String[] help() {
		return new String[] { "BiliBili GetFans v1.0", "Coder:LinuxMint&Sair", "", "setuid [uid] :设置UID（uid是数字）",
				"get :获取粉丝变化信息", "frame :显示窗体", };
	}

	@Override
	protected String dataDir() {
		return null;
	}
}
