package 层压组胚计算器;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;

import javax.swing.JButton;

import sair.sacoms.MathCast;

class 计算 {

	static HashMap<String, JButton> 按钮序列 = new HashMap<String, JButton>();
	static HashMap<JButton, String> 规格序列 = new HashMap<JButton, String>();
	static ArrayList<自定义按钮> 全部按钮 = new ArrayList<自定义按钮>();

	static class 生成按钮单击事件 implements ActionListener {

		private 自定义按钮 按钮;
		private 主窗口 窗口;

		public 生成按钮单击事件(主窗口 窗口, 自定义按钮 按钮) {
			this.窗口 = 窗口;
			this.按钮 = 按钮;
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			按钮.点击数++;
			this.窗口.左表数据.addElement(this.窗口.左表数据.size() + 1 + "层         " + 按钮.规格 + "mm");
			double 总KG = MathCast.dstringToDouble(窗口.重量.getText());
			double 总厚度 = 0;

			for (int i = 0; i < this.窗口.左表数据.size(); i++) {
				String 元素 = this.窗口.左表数据.getElementAt(i);
				if (元素 != null) {
					String 规格 = 元素.split("层         ")[1];
					double 规格d = MathCast.dstringToDouble(规格);
					总厚度 += 规格d;
				}
			}
			// 当前厚度 / 总厚度 * 总KG
			for (自定义按钮 按钮_a : 全部按钮) {
				按钮_a.刷新(总厚度, 总KG);
			}
			double 单胚重 = 总厚度 * 1 * 2 * 1.2;
			double 可组数 = 总KG / 单胚重;
			double 余料重 = 总KG % 单胚重;
			窗口.输出.setText(String.valueOf(new BigDecimal(总厚度).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue()) + "mm\r\n\r\n" //
					+ "重" + new BigDecimal(单胚重).setScale(2, BigDecimal.ROUND_HALF_UP).doubleValue() + "KG\r\n\r\n" //
					+ "可组" + ((long)可组数) + "张\r\n\r\n"//
					+ "余料" + new BigDecimal(余料重).setScale(2, BigDecimal.ROUND_HALF_UP) + "KG\r\n\r\n"

			);//
		}

	}

	static class 确定按钮被单击事件 implements ActionListener {
		private 主窗口 窗口;

		确定按钮被单击事件(主窗口 窗口) {
			this.窗口 = 窗口;
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			String 输入 = 窗口.输入规格.getText();
			if (输入 == null)
				输入 = "";
			String[] 规格组 = 输入.split(" ");
			按钮序列.clear();
			规格序列.clear();
			窗口.左表数据.clear();
			窗口.中心流式选项画布.removeAll();
			窗口.输出.setText("");
			for (String 规格 : 规格组) {
				自定义按钮 生成按钮 = new 自定义按钮(规格, 窗口);
				窗口.中心流式选项画布.add(生成按钮);
				按钮序列.put(规格, 生成按钮);
				规格序列.put(生成按钮, 规格);
				全部按钮.add(生成按钮);
			}
			窗口.setVisible(false);
			窗口.setVisible(true);
		}

	}

	static class 关闭事件 implements ActionListener {

		@Override
		public void actionPerformed(ActionEvent e) {
			System.exit(0);
		}
	}

}
