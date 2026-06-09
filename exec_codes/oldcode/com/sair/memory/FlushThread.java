package com.sair.memory;

import java.awt.Color;

class FlushThread implements Runnable {

	private MainFrame mf;
	private boolean isContinue;

	FlushThread(MainFrame mf) {
		this.mf = mf;
		this.isContinue = true;
	}

	public void run() {
		while (this.isContinue) {
			this.mf.txt.setText("\r\n" + MainClass.getInfo());
			int used = (int) MainClass.infos.Used;
			int max = (int) MainClass.infos.Max;
			this.mf.jsb.setMaximum(max);
			this.mf.jsb.setValue(used);

			double u = used, m = max;
			double c = u / m;

			if (c <= 0.38)
				this.mf.jsb.setForeground(Color.GREEN);
			else if (c <= 0.65)
				this.mf.jsb.setForeground(Color.ORANGE);
			else if (c <= 0.90)
				this.mf.jsb.setForeground(Color.RED);

			try {
				Thread.sleep(500L);
			} catch (InterruptedException e) {
				// SaLogger.outLogger(e);
			}
		}
	}

	void Stop() {
		this.isContinue = false;
	}

}
