package sair.sacoms;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
/**
 * 简单的粘贴板工具
 * <p>
 * 
 * @author _陈小兵
 * @version Tables
 * 
 **/
public class Tables {

	/**
	 * 从剪切板获得文字。
	 * 
	 * @return String
	 */
	public static String getSysClipboardText() {
		String ret = "";
		Clipboard sysClip = Toolkit.getDefaultToolkit().getSystemClipboard();
		Transferable clipTf = sysClip.getContents(null);
		if (clipTf != null)
			if (clipTf.isDataFlavorSupported(DataFlavor.stringFlavor))
				try {
					ret = (String) clipTf.getTransferData(DataFlavor.stringFlavor);
				} catch (Exception e) {
					e.printStackTrace();
				}
		return ret;
	}

	/**
	 * 将字符串复制到剪切板。
	 * 
	 * @param strings
	 *            需要被写入的字符串
	 */
	public static void setSysClipboardText(String strings) {
		Clipboard clip = Toolkit.getDefaultToolkit().getSystemClipboard();
		Transferable tText = new StringSelection(strings);
		clip.setContents(tText, null);
	}

	/**
	 * 从剪切板获得图片。
	 * 
	 * @return Image
	 * 
	 */
	public static Image getImageFromClipboard() throws Exception {
		Clipboard sysc = Toolkit.getDefaultToolkit().getSystemClipboard();
		Transferable cc = sysc.getContents(null);
		if (cc == null)
			return null;
		else if (cc.isDataFlavorSupported(DataFlavor.imageFlavor))
			return (Image) cc.getTransferData(DataFlavor.imageFlavor);
		return null;

	}

	/**
	 * 复制图片到剪切板。
	 * 
	 * @param image
	 *            图像对象
	 */
	public static void setClipboardImage(final Image image) throws Exception {
		Transferable trans = new Transferable() {
			public DataFlavor[] getTransferDataFlavors() {
				return new DataFlavor[] { DataFlavor.imageFlavor };
			}

			public boolean isDataFlavorSupported(DataFlavor flavor) {
				return DataFlavor.imageFlavor.equals(flavor);
			}

			public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
				if (isDataFlavorSupported(flavor))
					return image;
				throw new UnsupportedFlavorException(flavor);
			}

		};
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(trans, null);
	}
}
