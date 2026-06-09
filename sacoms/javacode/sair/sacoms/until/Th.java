package sair.sacoms.until;

import sair.sacoms.Timmer;

public class Th implements Runnable {
	private long sleep;
	private boolean start;
	private Runnable tl;

	public boolean isStart() {
		return start;
	}

	private Timmer tm;

	public synchronized void setTimmerRunnable(Runnable run) {
		tl = run;
	}

	public Th(boolean isStart, Timmer timmer) {
		setFather(timmer);
		setStart(isStart);
	}

	public synchronized void setSleep(long sleepTime) {
		sleep = sleepTime;
	}

	public synchronized void setStart(boolean isStart) {
		start = isStart;
	}

	private synchronized void setFather(Timmer timmer) {
		tm = timmer;
	}

	@Override
	public void run() {
		if (sleep <= 0)
			sleep = 1000L;
		for (; start;) {
			try {
				Thread.sleep(sleep);
			} catch (InterruptedException e) {
				return;
			}
			tm.setUseMe();
			if (tl != null)
				tl.run();
		}
	}
}
