package sair.sacoms.until;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import sair.LoaderManager;
import sair.sacoms.Key;
import sair.sacoms.SairLists;

public class Im {
	private SairLists<String> Freader(File file, Key<String> enc) throws FileNotFoundException, IOException {
		SairLists<String> arrs = new SairLists<String>();
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), enc.about()));
			String context = null;
			while ((context = reader.readLine()) != null)
				arrs.add(context);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw e;
		} finally {
			if (reader != null)
				reader.close();
		}
		return arrs;
	}

	public SairLists<String> iotoMemoryStringList(String fileUrl, Key<String> enc) throws IOException {
		SairLists<String> goBack = new SairLists<String>();
		try {
			goBack = Freader(new File(fileUrl), enc);
		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw e;
		}
		return goBack;
	}

	private SairLists<String> pr(String Url, Key<String> enc) throws IOException {
		SairLists<String> arrs = new SairLists<String>();
		InputStream iis = this.getNowRuntime(Url);
		if (iis == null)
			iis = Im.class.getResourceAsStream(Url);
		if (iis != null) {
			BufferedReader br = new BufferedReader(new InputStreamReader(iis, enc.about()));
			String s = "";
			while ((s = br.readLine()) != null)
				arrs.add(s);
		}
		return arrs;
	}

	public SairLists<String> PackReder(String Url, Key<String> enc) throws IOException {
		return pr(Url, enc);
	}

	public InputStream getNowRuntime(String Url) throws IOException {
		return LoaderManager.getModResStream(Url);
	}
}
