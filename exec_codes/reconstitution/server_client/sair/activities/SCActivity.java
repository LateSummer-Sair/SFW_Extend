package sair.activities;

import sair.user.Activity;

public class SCActivity extends Activity {

	public final Runs runs = Runs.runs(this);

	@Override
	public Object main(String funcName, String args) {
		return runs.main(funcName, args);
	}

	@Override
	public String[] help() {
		return runs.helpStr();
	}

	@Override
	public void exit() {
		runs.exit();
	}

}
