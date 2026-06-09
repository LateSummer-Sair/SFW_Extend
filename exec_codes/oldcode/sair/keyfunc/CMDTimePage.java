package sair.keyfunc;

import java.util.concurrent.ConcurrentLinkedDeque;

import sair.sacoms.SairLists;

class CMDTimePage {
	private long systime;
	private long sleepTime;
	private String cmd;

	private CMDTimePage(long sleepTime, String cmd) {
		this.cmd = cmd;
		this.sleepTime = sleepTime;
		this.systime = System.currentTimeMillis();
	}

	static CMDTimePage creat(long sleepTime, String cmd) {
		return new CMDTimePage(sleepTime, cmd);
	}

	long sleepTime() {
		return sleepTime;
	}

	long systime() {
		return systime;
	}

	String cmd() {
		return cmd;
	}

	public String toString() {
		return cmd + " -> " + sleepTime;
	}

	static SairLists<String> cast(ConcurrentLinkedDeque<CMDTimePage> keyList) {
		SairLists<String> list = new SairLists<String>();
		for (CMDTimePage cp : keyList) {
			list.add("/sleep " + cp.sleepTime);
			list.add(cp.cmd);
		}
		return list;
	}
}
