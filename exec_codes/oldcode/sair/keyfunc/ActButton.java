package sair.keyfunc;

import java.awt.Dimension;
import java.util.concurrent.ConcurrentLinkedDeque;

import sair.FCM;
import sair.sacoms.SplitsJMD;
import sair.sacoms.until.ObjectBySplits;
import sair.sacoms.until.SeqPageI;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SButton;

class ActButton extends SButton implements SeqPageI {

	boolean isCanContinue = true;

	private String cmd = "";

	private long sqtl = 0L;

	private KeyFuncFrame kff;

	static final int h = 100;

	static final int w = 100;

	static final Dimension dimen = new Dimension(w, h);

	ActButton(String string, long sqtl, KeyFuncFrame kff) {
		super(string);
		this.setFont(ConsFrame.font);
		this.setForeground(KeyFuncMain.rc);
		this.addActionListener(new ActActionListener(this));
		this.setPreferredSize(dimen);
		this.addKeyListener(kff);
		this.kff = kff;
		this.sqtl = sqtl;
		this.setFocusable(false);
	}

	private static final long serialVersionUID = 2971018304783868532L;

	void setCMD(String cmd) {
		this.cmd = cmd;
	}

	String getCMD() {
		return cmd;
	}

	void press(boolean isSetC) {
		if (!isCanContinue)
			return;
		try {
			if (isSetC)
				setForeground(KeyFuncMain.pc);

			markTimeCMD();

			SairCons.runner(false, cmd);
		} catch (Exception err) {
			SairCons.println(FCM.Error_Color, this.getText() + " is err");
		}
	}

	private void markTimeCMD() {
		if (!kff.isreco || isThisFuncName())
			return;
		ConcurrentLinkedDeque<CMDTimePage> list = kff.keyList;
		if (list.size() > 0) {
			CMDTimePage page = list.getLast();
			long sleepTime = System.currentTimeMillis() - page.systime();
			list.add(CMDTimePage.creat(sleepTime, cmd));
		} else {
			list.add(CMDTimePage.creat(0, cmd));
		}
	}

	private boolean isThisFuncName() {
		ObjectBySplits spl = SplitsJMD.split(cmd);
		boolean endFunc = KeyFuncMain.STOP_STR.equals(spl.getEnd());
		boolean endHead = kff.km.getName().equals(spl.getHead());
		if (endFunc && endHead)
			return true;
		else
			return false;
	}

	void releas() {
		setForeground(KeyFuncMain.rc);
	}

	@Override
	public long getSeqID() {
		return sqtl;
	}

	public String toString() {
		return String.valueOf(sqtl);
	}
}
