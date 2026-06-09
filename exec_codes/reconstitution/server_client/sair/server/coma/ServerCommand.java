package sair.server.coma;

import java.net.Socket;

import sair.activities.SCActivity;
import sair.scsys.UID;
import sair.scsys.abstr.LinkedFact;
import sair.server.Server;

public final class ServerCommand extends Server {

	public ServerCommand(int port, SCActivity sa) {
		super(port, sa, UID.ComandUID);
	}

	@Override
	public LinkedFact creatSingleServer(int socketID, Socket socket) {
		return new LinkedCommand(socketID, socket, this, sa);
	}
}
