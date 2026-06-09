package com.sair.memory;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SFrame;

class MainFrame extends SFrame {

	/**
	 * 
	 */
	private static final long serialVersionUID = 7564630994992799127L;

	private FlushThread ft;

	private String tileString = "JVM Memory";

	private JPanel titleP = new JPanel();

	private JLabel title = new JLabel(tileString);

	private JButton bt = new JButton("EXIT");

	JProgressBar jsb = new JProgressBar();

	JTextArea txt = new JTextArea();

	private JScrollPane jsp = new JScrollPane();

	MainFrame() {
		super(300, 220);
		this.title.setFont(ConsFrame.font);
		this.title.setForeground(Color.WHITE);
		this.setTitle(tileString);
		this.init();
	}

	private void init() {
		this.centerPanel.setBackground(Color.BLACK);

		this.txt.setForeground(this.title.getForeground());
		this.bt.setForeground(this.title.getForeground());

		this.jsb.setBackground(this.centerPanel.getBackground());
		this.bt.setBackground(this.centerPanel.getBackground());
		this.titleP.setBackground(this.centerPanel.getBackground());
		this.txt.setBackground(this.centerPanel.getBackground());

		this.bt.setFont(ConsFrame.font);
		this.txt.setFont(ConsFrame.font);
		this.txt.setEditable(false);

		this.jsp.setViewportView(txt);
		this.centerPanel.setLayout(new BorderLayout(0, 0));
		this.titleP.setLayout(new BorderLayout(0, 0));

		this.titleP.add(title, BorderLayout.CENTER);
		this.titleP.add(bt, BorderLayout.EAST);

		this.centerPanel.add(titleP, BorderLayout.NORTH);
		this.centerPanel.add(jsp, BorderLayout.CENTER);
		this.centerPanel.add(jsb, BorderLayout.SOUTH);

		this.bt.addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				MainFrame.this.setVisible(false);
			}
		});
	}

	private void StartThread() {
		StopThread();
		new Thread((ft = new FlushThread(this))).start();
	}

	private void StopThread() {
		if (ft != null)
			ft.Stop();
	}

	public void setVisible(boolean b) {
		if (b)
			StartThread();
		else
			StopThread();
		super.setVisible(b);
	}

}
