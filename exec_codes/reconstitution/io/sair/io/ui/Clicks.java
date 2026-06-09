package sair.io.ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.io.IOException;

import javax.swing.JButton;
import javax.swing.JTextArea;

import sair.FCM;
import sair.sacoms.FileMana;
import sair.sys.SairCons;
import sair.sys.gui.swing.control.SButton;

class ButtonClicks implements ActionListener {
	private JTextArea jta;
	private String path;
	private JButton button;

	public ButtonClicks(JTextArea jta, String path, JButton button) {
		this.jta = jta;
		this.path = path;
		this.button = button;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		String text = jta.getText();
		try {
			FileMana.memorriesToIO(path, new String[] { text }, false);
			button.setText("文件已保存");
		} catch (IOException e1) {
			SairCons.println(FCM.Error_Color, "文件保存出错!");
		}
	}

	/*
	 * private static String[] creat(String text) { if (text == null) return new
	 * String[] { "" }; ArrayList<String> list = new ArrayList<String>();
	 * StringBuilder sb = new StringBuilder(); char[] chars =
	 * text.toCharArray(); for (char c : chars) { if (c == '\r' || c == '\n') {
	 * list.add(sb.toString()); sb = new StringBuilder(); } sb.append(c); }
	 * String[] arr = list.toArray(new String[list.size()]); return arr; }
	 */

}

class TextKeyListener implements KeyListener {
	private SButton saveButton;
	public TextKeyListener(SButton saveButton) {
		this.saveButton = saveButton;
	}

	@Override
	public void keyTyped(KeyEvent e) {
		saveButton.setText("文件已编辑");
	}

	@Override
	public void keyPressed(KeyEvent e) {

	}

	@Override
	public void keyReleased(KeyEvent e) {

	}

}
