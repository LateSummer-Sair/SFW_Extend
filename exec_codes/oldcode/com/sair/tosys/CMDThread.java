package com.sair.tosys;

import java.util.HashMap;

import sair.sacoms.CMD;
import sair.sacoms.Urler;

class CMDThread extends Thread {
	static String list = "list";
	static String url = "url";
	static String path = "path";
	static String exitfor = "exitfor";
	private String arg, fn;
	private HashMap<String, CMDThread> localMap;
	private boolean running;

	CMDThread(String fn, String arg, HashMap<String, CMDThread> localMap) {
		this.arg = arg;
		this.fn = fn;
		this.localMap = localMap;
		if (localMap.containsKey(arg)) {
			try {
				localMap.get(arg).move();
			} catch (Exception e) {
				Tosys.printER_cmtE(arg);
			}
			localMap.remove(arg);
		}
		localMap.put(arg, this);
	}

	public void run() {
		setRunning(true);
		if (url.equals(fn))
			CMD.autoOpenToUrl(arg);
		else if (path.equals(fn))
			CMD.autoOpenToUrl("file:///" + new Urler(arg).getUrl());
		else
			CMD.autoRunCommands(arg);
		setRunning(false);
	}

	@SuppressWarnings("deprecation")
	public void move() throws Exception {
		CMDThread cmt = localMap.get(arg);
		if (cmt != null) {
			localMap.remove(arg);
			cmt.interrupt();
			if (cmt.isAlive()) {
				cmt.stop();
			}
		} else
			Tosys.printER_nofound(arg);
	}

	public boolean isRunning() {
		return running;
	}

	private void setRunning(boolean running) {
		this.running = running;
		if (!running) {
			try {
				move();
			} catch (Exception e) {
				Tosys.printER_cmtE(arg);
			}
		}
	}

}
