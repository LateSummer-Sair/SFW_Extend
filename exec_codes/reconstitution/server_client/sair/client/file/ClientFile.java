package sair.client.file;

import java.io.File;

import sair.client.Client;

public final class ClientFile extends Client {
	private File readySendFile;

	public ClientFile(int port, String host, File readySendFile) {
		super(port, host);
		this.readySendFile = readySendFile;
	}

	public Object toWork() {
		Object o = super.toWork(new WorkFile(readySendFile));
		super.close();
		return o;
	}

}
