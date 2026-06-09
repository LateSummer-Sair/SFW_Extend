package sair.server.file;

import java.io.File;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;

import sair.FCM;
import sair.activities.SCActivity;
import sair.scsys.IOTools;
import sair.server.Server;
import sair.sys.SairCons;
import sair.server.Linked;

final class LinkedFile extends Linked {

	LinkedFile(int socketID, Socket socket, Server server, SCActivity sa) {
		super(socketID, socket, server, sa);
	}

	@Override
	public void whileToServer(InputStream inputStream, OutputStream outputStream) {
		ObjectOutputStream oos = castOutputStream(sid, outputStream);
		if (inputStream == null)
			return;

		if (oos == null)
			return;
		File file = new File(sa.getDataDir() + System.currentTimeMillis());
		try {
			IOTools.inputStreamSaveToFile(inputStream, file);
			SairCons.println(FCM.split_Color, sid + ":\r\n传输完成!保存在:" + file.getAbsolutePath());
		} catch (Exception e) {
		}
		server.setLinkedClientClose(sid);
	}

}
