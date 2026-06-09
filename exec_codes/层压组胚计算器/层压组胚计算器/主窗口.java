package 层压组胚计算器;

import sair.sys.gui.swing.control.SBorder;
import sair.sys.gui.swing.control.SButton;
import sair.sys.gui.swing.control.SFrame;
import sair.sys.gui.swing.control.SairScrollBarUI;
import sair.sys.gui.swing.tools.Fonts;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

import javax.swing.JScrollPane;
import javax.swing.JPanel;
import javax.swing.JLabel;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JTextField;
import java.awt.Font;
import java.awt.GridLayout;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;

class 主窗口 extends SFrame {

	/**
	 * 
	 */
	public static final Font 按钮字体 = Fonts.FONTS_TOOLS.getFont(null, null, 14f);
	public static final Font 输入字体 = Fonts.FONTS_TOOLS.getFont(null, null, 22f);
	private static final long serialVersionUID = 1010130528327460917L;
	DefaultListModel<String> 左表数据 = new DefaultListModel<String>();
	JLabel 抬头标题 = new JLabel("<html>德信加工件车间组胚计算器</html>");
	JPanel 抬头画布 = new JPanel();
	JPanel 底部输入画布 = new JPanel();
	JPanel 中心流式选项画布 = new JPanel();
	JScrollPane 左明细显示画布 = new JScrollPane();
	JScrollPane 输出明细画布 = new JScrollPane();
	JScrollPane 中心流画布 = new JScrollPane();
	JTextArea 输出 = new JTextArea();
	JList<String> 明细显示表 = new JList<String>();

	JTextField 输入规格 = new JTextField();

	JButton 关闭按钮 = new SButton("<html>退出</html>");
	JButton 确定按钮 = new SButton("<html>确认重量规格</html>");
	JLabel 组胚信息提示 = new JLabel("<html>组胚信息</html>");
	JTextField 重量 = new JTextField();

	JPanel 输入重量画布 = new JPanel();
	JLabel 输入重量提示 = new JLabel("<html>在此输入目标重量 ↓</html>");
	JPanel 输入规格画布 = new JPanel();
	JLabel 输入规格提示 = new JLabel("<html>在此输入规格，规格厚度之间使用空格分开 ↓</html>");
	JComponent[] 全部控件 = { 抬头标题, 抬头画布, 底部输入画布, 中心流式选项画布, 左明细显示画布, 输入规格, 明细显示表, 关闭按钮, 确定按钮, 重量, 输出明细画布, 输出, 输入重量画布,
			输入重量提示, 输入规格画布, 输入规格提示, 组胚信息提示, 中心流画布 };

	@SuppressWarnings("rawtypes")
	主窗口() {
		super(800, 600);
		this.setTitle("组胚用量计算器");
		centerPanel.setLayout(new BorderLayout(0, 0));
		centerPanel.add(左明细显示画布, BorderLayout.WEST);
		明细显示表.setModel(左表数据);
		左明细显示画布.setViewportView(明细显示表);
		组胚信息提示.setHorizontalAlignment(SwingConstants.CENTER);

		左明细显示画布.setColumnHeaderView(组胚信息提示);
		左明细显示画布.getVerticalScrollBar().setUI(new SairScrollBarUI());
		左明细显示画布.getHorizontalScrollBar().setUI(new SairScrollBarUI());
		centerPanel.add(抬头画布, BorderLayout.NORTH);
		抬头画布.setLayout(new BorderLayout(0, 0));

		抬头画布.add(抬头标题, BorderLayout.CENTER);
		关闭按钮.addActionListener(new 计算.关闭事件());

		抬头画布.add(关闭按钮, BorderLayout.EAST);

		centerPanel.add(底部输入画布, BorderLayout.SOUTH);
		底部输入画布.setLayout(new BorderLayout(0, 0));
		确定按钮.addActionListener(new 计算.确定按钮被单击事件(this));

		底部输入画布.add(确定按钮, BorderLayout.EAST);

		底部输入画布.add(输入重量画布, BorderLayout.WEST);
		输入重量画布.setLayout(new BorderLayout(0, 0));
		输入重量画布.add(重量, BorderLayout.CENTER);
		重量.setColumns(10);
		输入重量提示.setHorizontalAlignment(SwingConstants.CENTER);

		输入重量画布.add(输入重量提示, BorderLayout.NORTH);

		底部输入画布.add(输入规格画布, BorderLayout.CENTER);
		输入规格画布.setLayout(new BorderLayout(0, 0));
		输入规格画布.add(输入规格, BorderLayout.CENTER);
		输入规格.setColumns(10);
		输入规格提示.setHorizontalAlignment(SwingConstants.CENTER);

		输入规格画布.add(输入规格提示, BorderLayout.NORTH);

		centerPanel.add(中心流画布, BorderLayout.CENTER);
		中心流画布.setViewportView(中心流式选项画布);
		中心流式选项画布.setLayout(new GridLayout(0, 3, 0, 0));

		centerPanel.add(输出明细画布, BorderLayout.EAST);

		输出明细画布.setViewportView(输出);
		输出.setEditable(false);
		this.getCenter().setBackground(Color.BLACK);

		for (JComponent 控件 : 全部控件) {

			控件.setOpaque(false);
			控件.setFont(按钮字体);
			控件.setForeground(Color.PINK);
			控件.setBorder(new SBorder(Color.PINK));
			if (控件 instanceof JList) {
				((JComponent) (((JList) 控件).getCellRenderer())).setOpaque(false);
			}
			if (控件 instanceof JScrollPane) {
				JScrollPane 画布 = ((JScrollPane) 控件);
				画布.setPreferredSize(new Dimension(210, 210));
				画布.getViewport().setOpaque(false);
				画布.getVerticalScrollBar().setOpaque(false);
				画布.getHorizontalScrollBar().setOpaque(false);
				if (画布.getColumnHeader() != null)
					画布.getColumnHeader().setOpaque(false);
				if (画布.getRowHeader() != null)
					画布.getRowHeader().setOpaque(false);
			} else if (!(控件 instanceof JList) && !(控件 instanceof JLabel))
				控件.setPreferredSize(new Dimension(130, 80));
			if (控件 instanceof JLabel)
				控件.setForeground(Color.ORANGE);
			if ((控件 instanceof JTextField) || (控件 instanceof JTextArea)) {
				控件.setFont(输入字体);
				控件.setForeground(Color.GREEN);
			}
			if (控件 instanceof JButton)
				控件.setForeground(Color.RED);
		}
	}

}
