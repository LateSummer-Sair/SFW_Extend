package sair.sacoms.until;

import javax.swing.JFrame;

public interface ClicksI {
	public JFrame getJFrame();

	public int getOldX();

	public int getOldY();

	public void setOldX(int x);

	public void setOldY(int y);
}
