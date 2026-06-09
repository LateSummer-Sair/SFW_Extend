package sair.keyfunc;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.concurrent.ConcurrentLinkedDeque;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;

import sair.sacoms.Seq;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.SairScrollBarUI;

class KeyFuncFrame extends SFrame implements KeyListener {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7767109104413228115L;

	KeyFuncMain km;

	boolean isreco;

	final ConcurrentLinkedDeque<CMDTimePage> keyList = new ConcurrentLinkedDeque<CMDTimePage>();

	private final int w = 799;

	private final int h = 399;

	private final int defTall = w / ActButton.w;

	private JLabel tiLabel = new JLabel();

	private JPanel buttonPanel = new JPanel();

	private JPanel inputPanel = new JPanel();

	KeyFuncFrame(KeyFuncMain km) {
		super();
		super.set(w, h);

		this.km = km;
		this.setTitle(this.km.title);
		this.centerPanel.setLayout(new BorderLayout());
		this.tiLabel.setForeground(Color.WHITE);
		this.tiLabel.setFont(ConsFrame.font);
		this.tiLabel.setText(this.getTitle());
		// this.buttonPanel.setSize(w, h);
		this.buttonPanel.setLayout(new GridLayout(0, 7, 0, 0));
		this.buttonPanel.setOpaque(false);

		JScrollPane scoll = new JScrollPane();
		scoll.getHorizontalScrollBar().setOpaque(false);
		scoll.getHorizontalScrollBar().setUI(new SairScrollBarUI());
		scoll.getVerticalScrollBar().setOpaque(false);
		scoll.getVerticalScrollBar().setUI(new SairScrollBarUI());
		scoll.setViewportView(buttonPanel);
		scoll.getViewport().setOpaque(false);
		scoll.setOpaque(false);

		this.inputPanel.setOpaque(false);

		this.centerPanel.add(scoll, BorderLayout.CENTER);
		this.centerPanel.add(tiLabel, BorderLayout.NORTH);
		this.centerPanel.add(inputPanel, BorderLayout.SOUTH);
		this.centerPanel.setBackground(Color.DARK_GRAY);

		this.buttonPanel.addKeyListener(this);
		this.centerPanel.addKeyListener(this);
		this.addKeyListener(this);
		this.setFocusable(true);
	}

	@Override
	public void keyPressed(KeyEvent e) {

		String key = String.valueOf(e.getKeyChar());
		ActButton cmd = km.map.get(key.toUpperCase());
		if (cmd != null) {
			cmd.press(true);
			cmd.isCanContinue = false;
		} else
			SairCons.println("no CMD key: " + key);
	}

	@Override
	public void keyTyped(KeyEvent e) {
	}

	@Override
	public void keyReleased(KeyEvent e) {
		String key = String.valueOf(e.getKeyChar());
		ActButton cmd = km.map.get(key.toUpperCase());
		if (cmd != null) {
			cmd.releas();
			cmd.isCanContinue = true;
		}
	}

	void flushBT() {
		if (km == null)
			return;

		ActButton[] arr = new ActButton[km.map.size()];
		km.map.values().toArray(arr);
		Seq.Of(Seq.BySort, arr);
		this.buttonPanel.removeAll();
		for (ActButton bt : arr)
			this.buttonPanel.add(bt);
		if (this.isVisible()) {
			this.setVisible(false);
			int lh = (arr.length / defTall);
			int mo = (arr.length % defTall);
			if (mo > 0 && mo < defTall)
				mo = 2;
			else
				mo = 1;
			Dimension sized = new Dimension(w, (lh + mo) * ActButton.h);
			this.setSize(sized);
			this.setVisible(true);
		}
	}
}
