package com.sair.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;

class CPU {

	final static CPU instance = new CPU();

	private OperatingSystemMXBean osMxBean;
	private ThreadMXBean threadBean;
	private long preTime = System.nanoTime();
	private long preUsedTime = 0;

	private CPU() {
		osMxBean = ManagementFactory.getOperatingSystemMXBean();
		threadBean = ManagementFactory.getThreadMXBean();
	}

	int getProcessCpu() {
		long totalTime = 0;
		for (long id : threadBean.getAllThreadIds()) {
			totalTime += threadBean.getThreadCpuTime(id);
		}
		long curtime = System.nanoTime();
		long usedTime = totalTime - preUsedTime;
		long totalPassedTime = curtime - preTime;
		preTime = curtime;
		preUsedTime = totalTime;
		return (int) ((((double) usedTime) / totalPassedTime / osMxBean.getAvailableProcessors()) * 100);
	}
}
