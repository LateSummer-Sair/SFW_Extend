package sair.server;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import sair.activities.SCActivity;
import sair.scsys.abstr.LinkedFact;

public abstract class Linked extends LinkedFact {

	protected int sid;// 连接池所在唯一ID

	protected Socket s;// 自身的管道

	protected Server server;// 连接池管理模板

	protected SCActivity sa;

	protected Linked(int socketID, Socket socket, Server server, SCActivity sa) {
		this.s = socket;
		this.sid = socketID;
		this.sa = sa;
	}

	@Override
	public void run() {
		try {
			whileToServer(s.getInputStream(), s.getOutputStream());
		} catch (Exception e) {
			if (server != null)
				server.setLinkedClientClose(sid);
		}
	}

	public abstract void whileToServer(InputStream inputStream, OutputStream outputStream);

}
