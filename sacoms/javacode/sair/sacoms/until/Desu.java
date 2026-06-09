package sair.sacoms.until;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import sair.sacoms.FileMana;
import sair.sacoms.Key;
import sair.sacoms.SairLists;
import sair.sacoms.Urler;

public class Desu {
	private void deletefile(String Url) throws FileNotFoundException {
		try {
			File file = new File(Url);
			if (!file.isDirectory())
				file.delete();
			else if (file.isDirectory()) {
				String[] filelist = file.list();
				for (int i = 0; i < filelist.length; i++) {
					File delfile = new File(Url + File.separator + filelist[i]);
					if (!delfile.isDirectory())
						delfile.delete();
					else if (delfile.isDirectory())
						deletefile(Url + File.separator + filelist[i]);
				}
				file.delete();
			}

		} catch (FileNotFoundException e) {
			throw e;
		}
	}

	private void copyFile(String inUrl, String outUrl) throws IOException {
		File sourceFile = new File(inUrl);
		File destFile = new File(outUrl + File.separator + sourceFile.getName());

		try {
			FileInputStream fis = new FileInputStream(sourceFile);
			FileOutputStream fos = new FileOutputStream(destFile);
			BufferedInputStream bis = new BufferedInputStream(fis, 4096);
			BufferedOutputStream bos = new BufferedOutputStream(fos, 4096);

			int len = 0;
			byte[] b = new byte[4096];
			while ((len = bis.read(b)) != -1)
				bos.write(b, 0, len);
			bos.close();
			bis.close();
			fos.close();
			fis.close();

		} catch (FileNotFoundException e) {
			throw e;
		} catch (IOException e) {
			throw e;
		}
	}

	private void copyField(String inUrl, String outUrl) throws IOException {
		File sourceFile = new File(inUrl);
		if (sourceFile.isDirectory()) {
			String[] names = sourceFile.list();
			File file = new File(outUrl + File.separator + sourceFile.getName());
			if (!file.exists())
				file.mkdir();
			for (String name : names)
				copyField(inUrl + File.separator + name, file.getPath());
		} else
			copyFile(inUrl, outUrl);
	}

	private void download(URL inUrl, String outUrl) throws IOException {
		BufferedInputStream bis = null;
		BufferedOutputStream bos = null;
		try {
			bis = new BufferedInputStream(inUrl.openStream(), 4096);
			bos = new BufferedOutputStream(new FileOutputStream(outUrl), 4096);
			byte[] b = new byte[4096];
			int len = 0;
			while ((len = bis.read(b)) != -1)
				bos.write(b, 0, len);
		} catch (IOException e) {
			throw e;
		} finally {
			if (bos != null)
				bos.close();
			if (bis != null)
				bis.close();
		}
	}

	private void zipFiles(String filePath, String descDir) throws Exception {
		ZipOutputStream zos = null;
		try {
			zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(descDir), 8192));
			startZip(zos, "", filePath);
		} catch (Exception e) {
			throw e;
		} finally {
			if (zos != null)
				try {
					zos.close();
				} catch (IOException e) {
					throw e;
				}
		}
	}

	private void startZip(ZipOutputStream zos, String oppositePath, String filePath) throws IOException {
		File file = new File(filePath);
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			for (int i = 0; i < files.length; i++) {
				File aFile = files[i];
				if (aFile.isDirectory()) {
					String newoppositePath = oppositePath + aFile.getName() + "/";
					ZipEntry entry = new ZipEntry(newoppositePath);
					zos.putNextEntry(entry);
					zos.closeEntry();
					startZip(zos, newoppositePath, aFile.getPath());
				} else
					zipFile(zos, oppositePath, aFile);
			}
		} else {
			if (!file.exists())
				return;
			zipFile(zos, oppositePath, file);
		}
	}

	private void zipFile(ZipOutputStream zos, String oppositePath, File file) throws IOException {
		BufferedInputStream is = null;
		try {
			ZipEntry entry = new ZipEntry(oppositePath + file.getName());
			zos.putNextEntry(entry);
			is = new BufferedInputStream(new FileInputStream(file));
			int length = 0, bufferSize = 8192;
			byte[] buffer = new byte[bufferSize];
			while ((length = is.read(buffer, 0, bufferSize)) >= 0)
				zos.write(buffer, 0, length);
			zos.closeEntry();
		} catch (IOException e) {
			throw e;
		} finally {
			if (is != null)
				try {
					is.close();
				} catch (IOException e) {
					throw e;
				}
		}
	}

	private void unZiFiles(String zipFilePath, String descDir) throws Exception {
		File zipFile = new File(zipFilePath), pathFile = new File(descDir);
		if (!pathFile.exists())
			pathFile.mkdirs();
		ZipFile zip = null;
		BufferedInputStream in = null;
		BufferedOutputStream out = null;
		try {
			zip = new ZipFile(zipFile);
			Enumeration<?> entries = zip.entries();
			while (entries.hasMoreElements()) {
				ZipEntry entry = (ZipEntry) entries.nextElement();
				String zipEntryName = entry.getName();
				in = new BufferedInputStream(zip.getInputStream(entry), 8192);
				String outPath = (descDir + "/" + zipEntryName).replace(File.separator + "*", "/");
				File fileDir = new File(outPath.substring(0, outPath.lastIndexOf('/')));
				if (!fileDir.exists())
					fileDir.mkdirs();
				File file = new File(outPath);
				if (file.isDirectory())
					continue;
				out = new BufferedOutputStream(new FileOutputStream(file), 8192);
				byte[] buf = new byte[8192];
				int len;
				while ((len = in.read(buf)) >= 0)
					out.write(buf, 0, len);
				in.close();
			}
		} catch (Exception e) {
			throw e;
		} finally {
			try {
				if (zip != null)
					zip.close();
				if (in != null)
					in.close();
				if (out != null)
					out.close();
			} catch (IOException e) {
				throw e;
			}
		}
	}

	private void getFiles(SairLists<String> back, File dir, Key<FileMana> all) {
		if (dir.isDirectory()) {
			String adds = "";
			File[] documentArr = dir.listFiles();
			if (documentArr != null) {
				for (File document : documentArr) {
					if (all == FileMana.GETDIR && document.isDirectory()) {
						back.add(document.toString() + File.separator);
						getFiles(back, document, all);
					} else if (all == FileMana.GETFILE)
						getFiles(back, document, all);
					else if (all == FileMana.GETALL) {
						adds = document.toString();
						if (document.isDirectory())
							adds = adds + File.separator;
						back.add(adds);
					}
				}
			}
		} else
			back.add(dir.toString());
	}

	public void del(String Url) throws Exception {
		Urler url = new Urler(Url);
		try {
			deletefile(url.getUrl());
		} catch (Exception e) {
			throw e;
		}
	}

	public void copy(String inUrl, String outUrl) throws IOException {
		Urler inurl = new Urler(inUrl), outurl = new Urler(outUrl + File.separator);
		if (!outurl.getHDFound())
			outurl.creatThisHD();
		copyField(inurl.getUrl(), outurl.getUrl());
	}

	public void netload(URL inUrl, String outUrl) throws IOException {
		download(inUrl, new Urler(outUrl).getUrl());
	}

	public void toZip(String inUrl, String outUrl) throws Exception {
		Urler inurl = new Urler(inUrl), outurl = new Urler(outUrl);
		if (!outurl.getHDFound())
			outurl.creatThisHD();
		zipFiles(inurl.getUrl(), outurl.getUrl());
	}

	public void zipOut(String inUrl, String outUrl) throws Exception {
		Urler inurl = new Urler(inUrl), outurl = new Urler(outUrl + File.separator);
		if (!outurl.getHDFound())
			outurl.creatThisHD();
		unZiFiles(inurl.getUrl(), outurl.getUrl());
	}

	public SairLists<String> getAllFiles(String dir, Key<FileMana> all) {
		File f = new File(new Urler(dir).getUrl());
		SairLists<String> result = new SairLists<String>();
		getFiles(result, f, all);
		return result;
	}
}
