package sair.keyfunc;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

class ActActionListener implements ActionListener {
	private ActButton actButton;

	public ActActionListener(ActButton actButton) {
		this.actButton = actButton;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		if (this.actButton != null){
			this.actButton.press(false);
		}
	}

}
