package sair.pic;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;

import javax.swing.JButton;
import javax.swing.JTextField;

import sair.sacoms.SairLists;
import sair.sacoms.Urler;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.tools.Backgrounds;
import sair.sys.gui.swing.tools.Clicks;
import sair.sys.tools.ToolPack;

class MainFrame extends SFrame {

	/**
	 * 
	 */
	private static final long serialVersionUID = 994358726627764654L;

	static final SairLists<String> picPath = new SairLists<String>();

	MainFrame() {
		super(800, 600);
		this.setTitle("PicShower");
		this.layoutInit();
		this.actionInit();
	}

	private int key = 0;

	private JButton nextButton = new SButton("next Image");
	private JTextField input = new JTextField();

	private void layoutInit() {
		input.setForeground(Color.RED);
		input.setFont(ConsFrame.font);
		input.setBorder(new SBorder(Color.RED));
		input.setOpaque(false);
		input.setPreferredSize(new Dimension(50, 50));

		nextButton.setForeground(Color.RED);
		nextButton.setFont(ConsFrame.font);

		centerPanel.setLayout(new BorderLayout());
		centerPanel.add(input, BorderLayout.NORTH);
		centerPanel.add(nextButton, BorderLayout.SOUTH);
	}

	private void actionInit() {
		nextButton.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (key >= picPath.getLength())
					key = 0;
				String localPath = picPath.getIndex(key);
				Backgrounds.BG_TOOLS.setNewImageToJPanel(localPath, MainFrame.this.centerPanel);
				key++;
			}
		});
		Clicks.CLICKS_TOOLS.drag(input);
		Clicks.CLICKS_TOOLS.enterPressesWhenFocused(input, new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				Urler url = new Urler(input.getText());
				if (url.getUrlFound()) {
					picPath.reStartClearAll();
					String path = url.getUrl();
					File file = new File(path);
					ArrayList<String> list = ToolPack.getAllFilesPath(file, true);
					for (String str : list)
						if (str.endsWith("jpg") || str.endsWith("png") || str.endsWith("bmp"))
							picPath.add(str);
					input.setText("");
					SairCons.println("加载成功！一共加载" + picPath.getLength() + "张图片！");
				} else {
					SairCons.println("加载失败！检查路径是否正确！");
				}
			}
		}, KeyEvent.VK_ENTER);
	}

}
