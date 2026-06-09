package sair.sacoms;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

/**
 * 文本文件编码识别类
 * 
 * @author _Sair
 *
 */
public class CodeReader {

	public CodeReader(String... decoder) {
		code.setArrToList(decoder);
	}

	private final SairLists<String> code = new SairLists<String>();

	/**
	 * 判断文本文件的编码，如果不符合白名单则返回null
	 * 
	 * @param path
	 *            文本文件路径
	 * @return 文件编码名String
	 * @throws FileNotFoundException
	 */
	public String detectCharsetForFile(String path) throws FileNotFoundException {
		File file = new File(path);
		if (file.exists()) {
			for (String charsetName : code)
				if (detectCharset(new BufferedInputStream(new FileInputStream(file)),
						Charset.forName(charsetName)) != null)
					return charsetName;
			return null;
		} else
			return null;
	}

	/**
	 * 判断文本文件的编码，如果不符合白名单则返回null
	 * 
	 * @param iss
	 *            自定义输入流
	 * @return 文件编码名String
	 */
	public String detectCharsetForStream(InputStream iss) {
		for (String charsetName : code)
			if (detectCharset(new BufferedInputStream(iss), Charset.forName(charsetName)) != null)
				return charsetName;
		return null;
	}

	private Charset detectCharset(BufferedInputStream input, Charset inputCharset) {
		try {
			CharsetDecoder decoder = inputCharset.newDecoder();
			decoder.reset();
			byte[] buffer = new byte[512];
			boolean identified = false;
			while ((input.read(buffer) != -1) && (!identified))
				identified = chk(buffer, decoder);
			input.close();
			if (identified)
				return inputCharset;
			else
				return null;
		} catch (Exception e) {
			return null;
		}
	}

	private boolean chk(byte[] bytes, CharsetDecoder decoder) {
		try {
			decoder.decode(ByteBuffer.wrap(bytes));
		} catch (CharacterCodingException e) {
			return false;
		}
		return true;
	}
}