package com.sair.memory;

import sair.user.Activity;

public class MainClass extends Activity {

	private MainFrame mf = new MainFrame();
	static InfosPage infos = new InfosPage();

	/*
	 * public static void main(String[] args) {
	 * JMD.toTest(Cons.creatAnCons("mmr", new MainClass()), "mmr/frame"); }
	 */

	public Object main(String name, String args) {
		String str = getInfo();
		if ("frame".equals(name))
			mf.setVisible(true);
		else if ("gc".equals(name)) {
			GC();
		}
		return str;
	}

	public String[] help() {
		return new String[] { mf.getTitle(), //
				" ", //
				"frame:进入主程序", //
				"gc:提醒JVM进行GC（谨慎使用！会清除所有虚引用！）"//
		};
	}

	public void stop() {
		mf.setVisible(false);
	}

	final static void GC() {
		System.gc();
		System.runFinalization();
	}

	final static synchronized String getInfo() {
		StringBuffer sbf = new StringBuffer();
		// 虚拟机级内存情况查询
		Runtime rt = Runtime.getRuntime();
		int byteToMb = 1024 * 1024;
		long vmTotal = rt.totalMemory() / byteToMb;
		long vmFree = rt.freeMemory() / byteToMb;
		long vmMax = rt.maxMemory() / byteToMb;
		long vmUse = vmTotal - vmFree;
		sbf.append("JVM总内存空间为：" + (infos.Now = vmTotal) + " MB" + "\r\n");
		sbf.append("JVM内存的空闲空间为：" + (infos.Free = vmFree) + " MB" + "\r\n");
		sbf.append("JVM总最大空间为：" + (infos.Max = vmMax) + " MB" + "\r\n");
		sbf.append("JVM内存已用的空间为：" + (infos.Used = vmUse) + " MB" + "\r\n");

		// 获得线程总数
		ThreadGroup parentThread;
		int totalThread = 0;
		for (parentThread = Thread.currentThread().getThreadGroup(); parentThread
				.getParent() != null; parentThread = parentThread.getParent()) {
			totalThread = parentThread.activeCount();
		}
		sbf.append("JVM线程总数:" + (infos.ThreadNum = totalThread) + "\r\n");
		infos.cpu = ((int) Math.round(CPU.instance.getProcessCpu() * 100) / 100);
		if (infos.cpu < 0)
			infos.cpu = 0;
		sbf.append("CPU使用率:" + infos.cpu + "%\r\n");

		return sbf.toString();
	}

	@Override
	public void exit() {
		// TODO Auto-generated method stub

	}

	@Override
	protected String dataDir() {
		// TODO Auto-generated method stub
		return null;
	}

}
