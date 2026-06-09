package sair.client.file;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.OutputStream;

import sair.client.Work;
import sair.scsys.IOTools;

final class WorkFile implements Work {
	private File readySendFile;

	public WorkFile(File readySendFile) {
		this.readySendFile = readySendFile;
	}

	@Override
	public Object startWork(InputStream input, OutputStream output) {
		boolean flag = IOTools.chkIO(input, output);

		if (flag) {
			try {
				IOTools.fileSendByOutputStream(readySendFile, output);
				output.flush();
			} catch (IOException e) {
				// e.printStackTrace();
			}
			ObjectInputStream ois = null;
			try {
				ois = new ObjectInputStream(input);
			} catch (IOException e) {
				// e.printStackTrace();
				return false;
			}
			try {
				return ois.readBoolean();
			} catch (IOException e) {
				return false;
			}
		}
		return false;
	}

}
