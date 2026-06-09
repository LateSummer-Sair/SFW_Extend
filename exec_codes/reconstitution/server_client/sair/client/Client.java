package sair.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

public abstract class Client {

	private Socket socket = null;

	private InputStream socketInputStream = null;

	private OutputStream socketOutputStream = null;

	protected Client(int port, String host) {
		try {
			this.socket = new Socket(host, port);
			socketInputStream = socket.getInputStream();
			socketOutputStream = socket.getOutputStream();
		} catch (IOException e) {
			//e.printStackTrace();
		}
	}

	protected Object toWork(Work work) {
		if (work != null)
			return work.startWork(socketInputStream, socketOutputStream);
		return null;
	}

	public abstract Object toWork();

	public void close() {
		if (socketInputStream != null)
			try {
				socketInputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		if (socketOutputStream != null)
			try {
				socketOutputStream.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		if (socket != null)
			try {
				socket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
	}
}
