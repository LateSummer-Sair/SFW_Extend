package sair.fans;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollBar;
import javax.swing.JTextField;

import sair.sacoms.MathCast;
import sair.sys.SairCons;
import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.SairScrollBarUI;
import sair.sys.gui.swing.tools.Fonts;

import javax.swing.JScrollPane;
import javax.swing.JList;
import javax.swing.JTextArea;
import java.awt.event.ActionListener;
import java.awt.event.AdjustmentEvent;
import java.awt.event.AdjustmentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.event.ActionEvent;

class FansFrame extends SFrame {

	FansFrame(Main mi) {
		super();
		this.setTitle("哔哩哔哩UP主粉丝变动记录仪");
		this.m = mi;
		this.init();
		super.set(520, 320);
	}

	/**
	 * 
	 */
	private static final long serialVersionUID = 1284688456L;

	private static final Font font = Fonts.FONTS_TOOLS.getFont(null, null, 15f);

	int speed = 5000;

	AutoThread th;
	Main m;

	JTextField uid = new JTextField();
	JList<String> list = new JList<String>();
	JTextArea info = new JTextArea();
	JProgressBar jsb = new JProgressBar();

	private JLabel title = new JLabel(), speedLabel = new JLabel("刷新速度:");
	private JPanel center = new JPanel(), top = new JPanel(), input = new JPanel(), barPanel = new JPanel(),
			right = new JPanel();
	private JButton setuid = new JButton("<-设置此UID并开始监控"), exit = new JButton("退出记录仪");
	private JScrollPane listSP = new JScrollPane();
	private DefaultListModel<String> names = new DefaultListModel<String>();
	private JScrollBar sb = new JScrollBar();

	void add(String str) {
		if (names.getSize() >= 3000)
			resetnames();
		names.addElement(str);
	}

	private void resetnames() {
		names.removeAllElements();
		names.addElement("粉丝增长记录：");
	}

	private void init() {
		resetnames();

		title.setText(this.getTitle());

		sb.setBackground(Color.BLACK);
		sb.setMinimum(1000);
		sb.setMaximum(30000);
		sb.setValue(speed);
		sb.setOrientation(JScrollBar.HORIZONTAL);

		list.setModel(names);

		title.setFont(font);
		uid.setFont(font);
		list.setFont(font);
		info.setFont(font);
		exit.setFont(font);
		uid.setFont(font);
		setuid.setFont(font);
		speedLabel.setFont(font);

		exit.setForeground(Color.RED);
		title.setForeground(Color.RED);
		info.setForeground(Color.GREEN);
		list.setForeground(Color.GREEN);
		setuid.setForeground(Color.BLUE);
		uid.setForeground(Color.BLUE);
		jsb.setForeground(Color.WHITE);
		speedLabel.setForeground(Color.GREEN);

		setuid.setBackground(Color.LIGHT_GRAY);
		exit.setBackground(Color.LIGHT_GRAY);
		uid.setBackground(Color.LIGHT_GRAY);
		info.setBackground(Color.BLACK);
		list.setBackground(Color.BLACK);
		jsb.setBackground(Color.BLACK);
		barPanel.setBackground(Color.BLACK);
		listSP.setBackground(Color.BLACK);

		center.setBackground(Color.LIGHT_GRAY);
		top.setBackground(Color.LIGHT_GRAY);

		sb.setUI(new SairScrollBarUI(Color.BLACK, Color.GREEN, Color.BLACK));
		listSP.getVerticalScrollBar().setUI(new SairScrollBarUI(Color.GREEN, Color.GREEN, Color.GREEN));
		listSP.getHorizontalScrollBar().setUI(new SairScrollBarUI(Color.GREEN, Color.GREEN, Color.GREEN));
		listSP.getVerticalScrollBar().setOpaque(false);
		listSP.getHorizontalScrollBar().setOpaque(false);
		listSP.setViewportView(list);

		barPanel.setLayout(new BorderLayout());
		centerPanel.setLayout(new BorderLayout());
		top.setLayout(new BorderLayout());
		input.setLayout(new BorderLayout());
		center.setLayout(new BorderLayout());
		right.setLayout(new BorderLayout());

		exit.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent ae) {
				if (th != null)
					th.close();
				setVisible(false);
				try {
					SairCons.runner(false, "/exit");
				} catch (Exception e) {
					// SaLogger.outLogger(e);
				}
			}
		});
		setuid.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				int uidn = MathCast.StringsIntToInt(uid.getText());
				resetnames();
				if (m != null)
					m.UploaderUID = uidn;
				if (th != null)
					th.close();
				new Thread((th = new AutoThread(FansFrame.this))).start();
			}
		});

		sb.addAdjustmentListener(new AdjustmentListener() {

			@Override
			public void adjustmentValueChanged(AdjustmentEvent e) {
				speed = sb.getValue();
			}
		});

		top.add(title, BorderLayout.CENTER);
		top.add(exit, BorderLayout.EAST);

		input.add(uid, BorderLayout.CENTER);
		input.add(setuid, BorderLayout.EAST);

		barPanel.add(speedLabel, BorderLayout.WEST);
		barPanel.add(sb, BorderLayout.CENTER);

		right.add(info, BorderLayout.CENTER);
		right.add(barPanel, BorderLayout.SOUTH);

		center.add(input, BorderLayout.NORTH);
		center.add(listSP, BorderLayout.CENTER);
		center.add(right, BorderLayout.EAST);
		center.add(jsb, BorderLayout.SOUTH);

		centerPanel.add(center, BorderLayout.CENTER);
		centerPanel.add(top, BorderLayout.NORTH);

		info.setEditable(false);

	}
}
