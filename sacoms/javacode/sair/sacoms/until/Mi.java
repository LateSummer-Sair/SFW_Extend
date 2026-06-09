package sair.sacoms.until;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;

import sair.sacoms.Urler;

public class Mi {

	private void add(String fileUrl, String[] content, boolean LN) throws IOException {
		boolean yn = false;
		BufferedWriter writer = null;
		Urler url = new Urler(fileUrl);
		if (!url.getUrlFound()) {
			url.creatThisUrl();
			yn = true;
		}
		try {
			writer = new BufferedWriter(new FileWriter(url.getUrl(), true));
			for (int i = 0; i < content.length; i++) {
				if (LN == true && yn == false)
					writer.write("\r\n");
				writer.write(content[i]);
			}
		} catch (IOException e) {
			throw e;
		} finally {
			try {
				if (writer != null)
					writer.close();
			} catch (IOException e) {
				throw e;
			}
		}
	}

	private void Fwriters(String fileUrl, String[] arrs, boolean LN) throws FileNotFoundException, IOException {
		Urler url = new Urler(fileUrl);
		if (!url.getUrlFound())
			url.creatThisUrl();
		int i = 0;
		int len = arrs.length;
		BufferedWriter writer = null;
		try {
			String context = null;
			writer = new BufferedWriter(new FileWriter(new File(url.getUrl())));
			while (i < len) {
				context = arrs[i];
				writer.write(context);
				if (LN == true)
					if (i < len - 1)
						writer.newLine();
				i++;
			}
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw e;
		} finally {
			if (writer != null)
				writer.close();
		}
	}

	public void memorriesToIo(String fileUrl, String[] arrs, boolean LN) throws IOException {
		try {
			this.Fwriters(fileUrl, arrs, LN);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw e;
		}
	}

	public void addToEnd(String fileUrl, String[] content, boolean LN) throws IOException {
		this.add(fileUrl, content, LN);
	}
}
