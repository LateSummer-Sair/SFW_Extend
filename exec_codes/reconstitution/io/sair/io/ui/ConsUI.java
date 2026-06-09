package sair.io.ui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.IOException;
import java.util.HashMap;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

import sair.FCM;
import sair.sacoms.FileMana;
import sair.sacoms.Key;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SairScrollBarUI;

public class ConsUI {

	public static final HashMap<String, JPanel> panelMap = new HashMap<String, JPanel>();

	public static JPanel getTextEdt(String path, String code) {
		String name = code + ":" + path;
		if (panelMap.containsKey(name))
			return panelMap.get(name);
		JPanel p = creat(path, code);
		if (p != null)
			panelMap.put(name, p);
		return p;
	}

	public static JPanel delTextEdt(String path) {
		return panelMap.remove(path);
	}

	private static JPanel creat(String path, String code) {

		String[] texts = readFile(path, code);
		if (texts == null)
			return null;

		JPanel p = new JPanel();
		JLabel lab = new JLabel(path);
		SButton saveButton = new SButton("±£´æÐÞ¸Ä");
		JScrollPane jsp = new JScrollPane();
		JTextArea jta = new JTextArea();
		jta.setFont(ConsFrame.font);

		int i = 0;
		for (String text : texts) {
			jta.append(text);
			if (i < texts.length - 1)
				jta.append("\n");
		}

		jsp.setViewportView(jta);
		jsp.setPreferredSize(new Dimension(ConsFrame.cf.getWidth() / 2, ConsFrame.cf.getHeight() / 2));
		jsp.setOpaque(false);
		jsp.getViewport().setOpaque(false);
		JScrollBar hbar = jsp.getHorizontalScrollBar();
		JScrollBar vbar = jsp.getVerticalScrollBar();
		hbar.setOpaque(false);
		vbar.setOpaque(false);
		p.setOpaque(false);

		p.setLayout(new BorderLayout());
		p.add(jsp, BorderLayout.CENTER);
		p.add(saveButton, BorderLayout.SOUTH);
		p.add(lab, BorderLayout.NORTH);

		hbar.setUI(new SairScrollBarUI(FCM.loadExection_Color, FCM.loadExection_Color, FCM.loadExection_Color));
		vbar.setUI(new SairScrollBarUI(FCM.loadExection_Color, FCM.loadExection_Color, FCM.loadExection_Color));
		jta.setOpaque(false);
		jta.setCaretColor(FCM.loadExection_Color);

		saveButton.setFont(ConsFrame.font);
		saveButton.setForeground(FCM.loadExection_Color);
		saveButton.addActionListener(new ButtonClicks(jta, path, saveButton));
		saveButton.setPreferredSize(new Dimension(ConsFrame.cf.getWidth() / 2, 50));

		lab.setFont(ConsFrame.font);
		lab.setForeground(FCM.loadExection_Color);
		lab.setBorder(new SBorder(FCM.loadExection_Color));
		jta.setForeground(ConsFrame.getFontColor());
		jta.setBorder(new SBorder(FCM.loadExection_Color));
		jta.addKeyListener(new TextKeyListener(saveButton));
		return p;
	}

	private static String[] readFile(String path, String code) {
		try {
			String[] texts = FileMana.getFileToStringArr(path, Key.creatKey(code));
			return texts;
		} catch (IOException e) {

		}
		return null;
	}

	public static void clear() {
		panelMap.clear();
	}
}
