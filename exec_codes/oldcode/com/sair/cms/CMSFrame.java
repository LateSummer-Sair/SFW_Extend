package com.sair.cms;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import sair.sys.gui.swing.control.A_JPanel;
import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.tools.Backgrounds;
import sair.sys.gui.swing.tools.Fonts;

class CMSFrame extends SFrame {

	/**
	 * 
	 */
	private static final long serialVersionUID = -562759840418889793L;

	private static final Font font = Fonts.FONTS_TOOLS.getFont(null, null, 45f);

	private static final Dimension dimension = new Dimension(200, 160);

	CMSFrame(CMS cms) {
		super();
		super.set(1024, 768);

		this.cms = cms;
		this.setTitle(title);
		this.initComponentStyle();
		this.initPanel();
		this.initActions();
	}

	private CMS cms;

	private String title = " MathCastGUI_v1.0";
	private JPanel panelTop = new JPanel(), panelCenter = new JPanel(), panelCenter_lables = new JPanel(),
			panelCenter_inputs = new JPanel(), panelCenter_inputs_chose = new JPanel();
	private SButton buttonExit = new SButton("EXIT"), buttonStart = new SButton("START");
	private JTextField inputText = new JTextField(), outputText = new JTextField();
	private JLabel inputLabel = new JLabel(" input:"), outputLabel = new JLabel("output:"),
			titleLabel = new JLabel(this.title);

	// 互斥组合，只存在一个被选中的单选框
	private ButtonGroup RadioGroup = new ButtonGroup();
	private JRadioButton ch2int = new JRadioButton("汉转数"), int2ch = new JRadioButton("数转汉"),
			def = new JRadioButton("默认");

	private void initComponentStyle() {
		JComponent[] components = new JComponent[] { buttonExit, buttonStart, inputText, outputText, inputLabel,
				outputLabel, titleLabel, ch2int, int2ch, def };
		for (JComponent jc : components) {
			jc.setFont(font);
			jc.setPreferredSize(dimension);
			if (jc instanceof JTextField) {
				jc.setBorder(new SBorder(Color.BLUE));
				jc.setForeground(Color.ORANGE);
				jc.setOpaque(false);
			}
			if (jc instanceof JRadioButton) {
				jc.setOpaque(false);
				jc.setForeground(Color.GREEN);
			}
			if (jc instanceof JButton)
				jc.setForeground(Color.GREEN);
			if (jc instanceof JLabel)
				jc.setForeground(Color.ORANGE);
		}
		outputText.setEditable(false);
	}

	private void initPanel() {
		JPanel[] jps = new JPanel[] { centerPanel, panelTop, panelCenter, panelCenter_lables, panelCenter_inputs,
				panelCenter_inputs_chose };
		for (JPanel jp : jps) {
			jp.setLayout(new BorderLayout());
			if (!(jp instanceof A_JPanel))
				jp.setOpaque(false);
		}
		initBackground();

		RadioGroup.add(ch2int);
		RadioGroup.add(int2ch);
		RadioGroup.add(def);

		panelTop.add(buttonExit, BorderLayout.EAST);
		panelTop.add(titleLabel, BorderLayout.CENTER);

		panelCenter_lables.add(inputLabel, BorderLayout.NORTH);
		panelCenter_lables.add(outputLabel, BorderLayout.SOUTH);

		panelCenter_inputs_chose.add(ch2int, BorderLayout.WEST);
		panelCenter_inputs_chose.add(int2ch, BorderLayout.CENTER);
		panelCenter_inputs_chose.add(def, BorderLayout.EAST);

		panelCenter_inputs.add(inputText, BorderLayout.NORTH);
		panelCenter_inputs.add(panelCenter_inputs_chose, BorderLayout.CENTER);
		panelCenter_inputs.add(outputText, BorderLayout.SOUTH);

		panelCenter.add(panelCenter_lables, BorderLayout.WEST);
		panelCenter.add(panelCenter_inputs, BorderLayout.CENTER);

		centerPanel.add(buttonStart, BorderLayout.SOUTH);
		centerPanel.add(panelCenter, BorderLayout.CENTER);
		centerPanel.add(panelTop, BorderLayout.NORTH);
	}

	private void initBackground() {
		String cmsPath = "/";
		if (cms != null)
			cmsPath = cms.getDataDir();
		String path = cmsPath + "BG.jpg";
		File file = new File(path);
		if (file.exists())
			Backgrounds.BG_TOOLS.setNewImageToJPanel(path, centerPanel);
		else
			centerPanel.setBackground(Color.GRAY);
	}

	private void actionCMS() {
		String inputText = this.inputText.getText();
		String outputText = "";
		if (this.int2ch.isSelected())
			outputText = CMS.int2ch(inputText);
		else if (this.ch2int.isSelected())
			outputText = String.valueOf(CMS.ch2int(inputText));
		else
			outputText = String.valueOf(inputText);
		this.outputText.setText(outputText);
	}

	private void initActions() {
		buttonExit.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				if (cms == null)
					System.exit(0);
				else
					CMSFrame.this.setVisible(false);
			}
		});

		buttonStart.addActionListener(new ActionListener() {
			@Override
			public void actionPerformed(ActionEvent e) {
				CMSFrame.this.actionCMS();
			}
		});
	}

	public Object setCMS(CMS cms) {
		this.cms = cms;
		return true;
	}

}
