package sair.pic;

import sair.Main;
import sair.user.Activity;

public class MainClass extends Activity {

	static final String pack = "bin/sair/pic/res/";
	static final String fileLastName = ".jpg";

	public static void main(String[] args) {
		Main.toTest(new MainClass(), "", "");
	}

	private MainFrame mf = new MainFrame();

	@Override
	public Object main(String funcName, String args) {
		mf.setVisible(true);
		return false;
	}

	@Override
	public String[] help() {
		// TODO Auto-generated method stub
		return new String[] { "PicShower", "先将图片文件夹拖拽进顶部编辑框再回车，就会自动加载了" };
	}

	@Override
	public void exit() {
		mf.setVisible(false);
	}

}
