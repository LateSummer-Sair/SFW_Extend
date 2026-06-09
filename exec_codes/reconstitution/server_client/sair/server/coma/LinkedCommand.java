package sair.server.coma;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;

import sair.activities.SCActivity;
import sair.server.Server;
import sair.sys.SairCons;
import sair.server.Linked;

final class LinkedCommand extends Linked {

	LinkedCommand(int socketID, Socket socket, Server server, SCActivity sa) {
		super(socketID, socket, server, sa);
	}

	@Override
	public void whileToServer(InputStream inputStream, OutputStream outputStream) {
		try {
			ObjectInputStream ois = castInputStream(sid, inputStream);
			ObjectOutputStream oos = castOutputStream(sid, outputStream);
			Object input = readObj(sid, ois);
			Object result = new Object();
			if (input instanceof String)
				result = SairCons.runner(false, (String) input);
			sendObj(oos, result);
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (server != null)
			server.setLinkedClientClose(sid);
	}

	private boolean sendObj(ObjectOutputStream oos, Object result) throws IOException {
		oos.writeObject(result);
		oos.flush();
		return true;
	}

	private static Object readObj(int sid, ObjectInputStream ois) {
		try {
			return ois.readObject();
		} catch (ClassNotFoundException | IOException e) {
			e.printStackTrace();
			return null;
		}
	}

	private static ObjectInputStream castInputStream(int sid, InputStream inputStream) {
		try {
			return new ObjectInputStream(inputStream);
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
}
