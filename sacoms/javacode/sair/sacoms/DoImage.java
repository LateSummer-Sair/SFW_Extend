package sair.sacoms;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

import sair.sacoms.until.Plane;
import sair.sacoms.until.Point;

/**
 * PNG图片工厂，用于图片合并裁剪
 * 
 * @version 1.0
 * @author _Sair
 */
public class DoImage {

	/**
	 * 水平输出
	 */
	public final static Key<DoImage> Horizontal = Key.creatKey();
	/**
	 * 垂直输出
	 */
	public final static Key<DoImage> Vertical = Key.creatKey();

	/**
	 * 从BufferedImage对象裁剪一张图片的BufferedImage
	 * 
	 * @param image
	 *            需要被加工的对象
	 * @param point
	 *            裁剪的X轴Y轴起始点
	 * @param plane
	 *            裁剪的空间面积
	 * @return BufferedImage
	 */
	public static BufferedImage cut(BufferedImage image, Point point, Plane plane) {
		if (image == null)
			return null;
		return image.getSubimage(point.getX(), point.getY(), plane.getWidth(), plane.getHeight());
	}

	/**
	 * 合并图片成一张图片
	 * 
	 * @param howToOut
	 *            DoImage.Horizontal | DoImage.Vertical
	 * @param background
	 *            新图片的背景色
	 * @param images
	 *            需要被合并的BufferedImage
	 * @return BufferedImage
	 * @throws Exception
	 */
	public static BufferedImage merge(Key<DoImage> howToOut, Color background, BufferedImage... images)
			throws Exception {
		if (images == null)
			return null;
		BufferedImage destImage = null;
		int allw = 0, allh = 0, allwMax = 0, allhMax = 0;
		for (int i = 0; i < images.length; i++) {
			BufferedImage img = images[i];
			if (img == null)
				continue;
			allw += img.getWidth();
			allh += img.getHeight();
			if (img.getWidth() > allwMax)
				allwMax = img.getWidth();
			if (img.getHeight() > allhMax)
				allhMax = img.getHeight();
		}
		if (howToOut == Horizontal)
			destImage = new BufferedImage(allw, allhMax, BufferedImage.TYPE_INT_ARGB);
		else if (howToOut == Vertical)
			destImage = new BufferedImage(allwMax, allh, BufferedImage.TYPE_INT_ARGB);
		else
			return null;
		Graphics g = destImage.getGraphics();
		g.setColor(background);
		g.fillRect(0, 0, destImage.getWidth(), destImage.getHeight());
		int wx = 0, wy = 0;
		for (int i = 0; i < images.length; i++) {
			BufferedImage img = images[i];
			int w1 = img.getWidth();
			int h1 = img.getHeight();
			int[] ImageArrayOne = new int[w1 * h1];
			ImageArrayOne = img.getRGB(0, 0, w1, h1, ImageArrayOne, 0, w1);
			if (howToOut == Horizontal)
				destImage.setRGB(wx, 0, w1, h1, ImageArrayOne, 0, w1);
			else if (howToOut == Vertical)
				destImage.setRGB(0, wy, w1, h1, ImageArrayOne, 0, w1);
			else
				return null;
			wx += w1;
			wy += h1;
		}
		return destImage;
	}

	/**
	 * 输出一个文字图片到指定目录(支持多行输出)
	 * 
	 * @param outStrings
	 *            字符串（多行）
	 * @param font
	 *            字体
	 * @param background
	 *            背景色
	 * @param fontColor
	 *            字体色
	 * @param outFile
	 *            文件输出位置
	 * @throws Exception
	 */
	public static void stringToPng(Key<DoImage> howToOut, File outFile, Color background, Color fontColor, Font font,
			String... outStrings) throws Exception {
		if (outStrings == null)
			outStrings = new String[] { "null" };
		if (outStrings.length <= 0)
			outStrings = new String[] { " " };
		SairLists<BufferedImage> bflist = new SairLists<BufferedImage>();
		for (String outString : outStrings)
			bflist.add(DoImage.getBufferedImage(outString, font, background, fontColor));
		ImageIO.write(merge(howToOut, background, bflist.getListArr(BufferedImage.class)), "png", outFile);
	}

	private static BufferedImage getBufferedImage(String outString, Font font, Color background, Color fontColor) {
		if (outString == null)
			outString = " ";
		if (outString.length() <= 0)
			outString = " ";
		outString = outString.replaceAll("\t", "    ");

		int height = font.getSize() + 3;
		int width = font.getSize() * outString.length();

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		Graphics g = DoImage.initG(font, width, height, background, fontColor, image.getGraphics());
		FontMetrics fm = g.getFontMetrics(font);

		int x = 0;
		int y = (height - (fm.getAscent() + fm.getDescent())) / 2 + fm.getAscent();

		g.drawString(outString, x, y);
		g.dispose();
		return image;
	}

	private static Graphics initG(Font font, int width, int height, Color background, Color fontColor,
			Graphics graphics) {
		graphics.setClip(0, 0, width, height);
		graphics.setColor(background);
		graphics.fillRect(0, 0, width, height);
		graphics.setColor(fontColor);
		graphics.setFont(font);
		((Graphics2D) graphics).setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
				(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB));
		return graphics;
	}

}
