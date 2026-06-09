package sair.server;

import sair.activities.SCActivity;
import sair.scsys.abstr.ServerManager;

public abstract class Server extends ServerManager {

	protected SCActivity sa;
	private int serverUID;

	protected Server(int port, SCActivity sa, int serverUID) {
		super(port);
		this.sa = sa;
		this.serverUID = serverUID;
	}

	public int getServerUID() {
		return serverUID;
	}

}
