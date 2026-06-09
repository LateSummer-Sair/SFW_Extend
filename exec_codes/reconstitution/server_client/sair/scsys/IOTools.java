package sair.scsys;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import sair.FCM;
import sair.sys.gui.ConsFrame;

public final class IOTools {

	private final static int tage = 2;

	public final static void fileSendByOutputStream(File file, OutputStream byos) throws IOException {
		if (file == null)
			return;
		if (!file.exists() || !file.canRead())
			throw new IOException("文件不能被读取或者不存在!");
		FileInputStream bis = new FileInputStream(file);
		// ByteArrayOutputStream byos = new ByteArrayOutputStream(tage * 1024 *
		// 1024);
		int len;
		byte[] buffer = new byte[tage * 40960];
		try {
			while (true) {
				len = bis.read(buffer);
				if (len < 0)
					break;
				byos.write(buffer, 0, len);
			}
			byos.flush();
			// return byos.toByteArray();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (bis != null)
					bis.close();
				bis = null;
			} catch (Exception eclose) {
				eclose.printStackTrace();
			}
			try {
				byos.close();
				byos = null;
			} catch (Exception eclose) {
				eclose.printStackTrace();
			}
			// System.gc();
		}
		// return null;
	}

	public final static void inputStreamSaveToFile(InputStream bais, File saveTo) throws IOException {
		if (saveTo == null || bais == null)
			return;
		if (!saveTo.exists())
			saveTo.createNewFile();
		if (!saveTo.canWrite())
			throw new IOException("文件不能被写入!");

		FileOutputStream fos = new FileOutputStream(saveTo, false);

		int len;
		byte[] buffer = new byte[tage * 40960];
		try {
			while (true) {
				len = bais.read(buffer);
				if (len < 0)
					break;
				fos.write(buffer, 0, len);
			}
			fos.flush();
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			try {
				if (bais != null)
					bais.close();
				bais = null;
			} catch (Exception eclose) {
				eclose.printStackTrace();
			}
			try {
				fos.close();
				fos = null;
			} catch (Exception eclose) {
				eclose.printStackTrace();
			}
		}
	}

	public final static byte[] StringToByteArray(String str) {
		if (str == null)
			return null;
		return str.getBytes();
	}

	public final static String ByteArrayToString(byte[] data) {
		if (data == null)
			return null;
		return new String(data);
	}

	public static boolean chkIO(InputStream input, OutputStream output) {
		if (output == null || input == null) {
			ConsFrame.printComponent(FCM.Error_Color, "此远程已经失效");
			return false;
		}
		return true;
	}

}
