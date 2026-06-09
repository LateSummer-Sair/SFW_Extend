package sair.server.file;

import java.net.Socket;

import sair.activities.SCActivity;
import sair.scsys.UID;
import sair.scsys.abstr.LinkedFact;
import sair.server.Server;

public final class ServerFile extends Server {

	public ServerFile(int port, SCActivity sa) {
		super(port, sa, UID.FileUID);
	}

	@Override
	public LinkedFact creatSingleServer(int socketID, Socket s) {
		return new LinkedFile(socketID, s, this, sa);
	}

}
