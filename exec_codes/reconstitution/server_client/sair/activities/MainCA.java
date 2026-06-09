package sair.activities;

import sair.Main;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.user.Activity;

class MainCA {

	public static void main(String[] args) throws Exception {
		final String name = "SCN";
		final int filePort = 8063;
		final int comaPort = 8064;
		Activity scn = new SCActivity();
		scn.setName(name);
		Libraries.activities.put(name, scn);

		Main.toTest(scn, "", "");

		SairCons.runner(false, name + "/startFileServer " + filePort);
		SairCons.runner(false, name + "/startComaServer " + comaPort);
		
		SairCons.runner(false, name + "/sendComa 127.0.0.1 8062 /println ÄãºÃ");
	}

}
