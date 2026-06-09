package sair.client.coma;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;

import sair.client.Work;
import sair.scsys.IOTools;

public final class WorkCommand implements Work {
	private ObjectInputStream Oin = null;
	private ObjectOutputStream Oout = null;
	private String command;

	public WorkCommand(ClientCommand client, String command) {
		this.command = command;
	}

	@Override
	public Object startWork(InputStream input, OutputStream output) {
		boolean flag = IOTools.chkIO(input, output);
		if (flag) {
			try {
				Oout = new ObjectOutputStream(output);
				Oin = new ObjectInputStream(input);
			} catch (IOException e) {
			}
			try {
				Oout.writeObject(new String(command));
				Oout.flush();
			} catch (IOException e) {
			}
			try {
				Object result = Oin.readObject();
				return result;
			} catch (ClassNotFoundException | IOException e) {
			}

			if (Oin != null)
				try {
					Oin.close();
				} catch (IOException e) {
				}
			if (Oout != null)
				try {
					Oout.close();
				} catch (IOException e) {
				}
		}
		return null;
	}

}
