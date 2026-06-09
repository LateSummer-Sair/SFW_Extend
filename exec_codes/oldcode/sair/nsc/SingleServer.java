package sair.nsc;
 
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.ArrayList;

import sair.sacoms.MathCast;
import sair.sacoms.SplitsJMD;
import sair.sacoms.until.ObjectBySplits;
import sair.sys.SairCons;

/**
 * 
 * A single service corresponds to a client connection, </br>
 * and each client connection has a single service object corresponding, </br>
 * and is a separate thread,</br>
 * which is managed by the thread pool.</br>
 * </br>
 * Each individual SingleServer has the same SM object</br>
 * 
 * @author Sair
 **/
public class SingleServer implements Runnable {

	/* mark linked SID */
	private int sid;

	/* mark linked Socket */
	private Socket s;

	/* mark server manager of singleton mode */
	private SM sm;

	public SingleServer(int sid, Socket s, SM sm) {
		this.s = s;
		this.sid = sid;
		this.sm = sm;
	}

	public void run() {

		/**
		 * A loop wrapped program logic, </br>
		 * any error will cause this connection to be forced to terminate. </br>
		 * If the OIS object resolves the object type, </br>
		 * it will call the toobj method for processing. </br>
		 * If it is string type, </br>
		 * it will call the tojmd method for execution. </br>
		 **/

		try {
			while (true) {
				ObjectInputStream ois = new ObjectInputStream(s.getInputStream());
				try {
					final Object getInfo = ois.readObject();
					if (getInfo != null) {
						ObjectOutputStream oos = new ObjectOutputStream(s.getOutputStream());
						if (getInfo instanceof String)
							toJMD((String) getInfo, oos);
						else
							toOBJ(getInfo, oos);
					} else
						throw new IOException();
				} catch (ClassNotFoundException e) {
					//SaLogger.outLogger(e);
				}
			}
		} catch (IOException e) {
			if (sm != null)
				sm.setLinkedClientClose(sid);
		}
	}

	/**
	 * If the server receives an object, </br>
	 * it will determine whether the object is a standard mod template. </br>
	 * If it is, </br>
	 * it will forward it.</br>
	 * Before forwarding,</br>
	 * it will rewrite the targetid in mod according to the current
	 * situation.</br>
	 * </br>
	 * fs is the target socket found in the connection pool</br>
	 * 
	 * @param obj
	 *            the MOD Object
	 * @param oos
	 *            the uploaderObjectOutputStream
	 **/
	private void toOBJ(Object obj, ObjectOutputStream oos) throws IOException {
		if (obj instanceof MOD) {
			MOD mod = (MOD) obj;
			Integer fid = mod.getDownloaderID();
			if (fid == null) {
				Tool.printSEND(oos, Lib.SERVER_INFO_TI + "ERR MOD");
				return;
			} else if (fid == sid) {
				Tool.printSEND(oos, Lib.SERVER_INFO_TI + "Can`t to yourself");
				return;
			} else {
				SairCons.println("[" + sid + "]" + " hasMOD to " + fid);
				/* fs is the target socket found in the connection pool */
				Socket fs = sm.getLinkedSocket(fid);
				if (fs != null) {
					Tool.modSEND(mod, new ObjectOutputStream(fs.getOutputStream()));
					Tool.printSEND(oos, Lib.SERVER_INFO_TI + "return fin");
				} else
					Tool.printSEND(oos, Lib.SERVER_INFO_TI + "Can`t find target ID");
			}
		} else {
			SairCons.println("[" + sid + "]  " + "SEND ERR MOD");
			Tool.printSEND(oos, Lib.SERVER_INFO_TI + "the Object is not MOD");
		}
	}

	/**
	 * If the server receives a string, </br>
	 * it will start to analyze the meaning of the string.</br>
	 * If a new client greets, </br>
	 * it will return a message, </br>
	 * which will report the connection ID of the client.</br>
	 * If the prefix is a string of the @ symbol,</br>
	 * It will be further parsed. </br>
	 * JMD standard command template will be used to cut and parse.</br>
	 * After cutting and obtaining the header message,</br>
	 * it will be encapsulated again, </br>
	 * and @ logo will be added at the front.Ultimately, </br>
	 * the message is transmitted to the target client and executed.</br>
	 * </br>
	 * fs is the target socket found in the connection pool</br>
	 * 
	 * @param getInfo
	 *            infoString
	 * @param oos
	 *            the uploaderObjectOutputStream
	 **/
	private void toJMD(String getInfo, ObjectOutputStream oos) throws IOException {
		SairCons.println("[" + sid + "]  " + getInfo);
		if (Lib.newLink.equals(getInfo))
			Tool.printSEND(oos, Lib.SERVER_INFO_TI + "server has linked£¬your ID is£º" + sid);
		else if (Lib.getList.equals(getInfo)) {
			ArrayList<String> list = new ArrayList<String>();
			for (int i = 0; i < sm.socketList.size(); i++) {
				Socket s = sm.socketList.get(i);
				if (s != null && s.isConnected() && !s.isClosed()) {
					String str = new StringBuffer(s.getInetAddress().getHostAddress()).append(" -  SID:[").append(i)
							.append("]").toString();
					list.add(str);
				}
			}

			MOD mod = new MOD(sid, list, getInfo);

			Tool.modSEND(mod, oos);
		} else if (Lib.findRun == getInfo.charAt(0)) {
			ObjectBySplits spl = SplitsJMD.split(new StringBuffer(getInfo).deleteCharAt(0).toString());
			int fid = MathCast.StringsIntToInt(spl.getHead());
			if (fid == sid)
				Tool.printSEND(oos, Lib.SERVER_INFO_TI + "Can`t to yourself");
			else {
				/* fs is the target socket found in the connection pool */
				Socket fs = sm.getLinkedSocket(fid);
				if (fs == null)
					Tool.printSEND(oos, Lib.SERVER_INFO_TI + "Can`t find target ID");
				else {
					Tool.printSEND(new ObjectOutputStream(fs.getOutputStream()), String.valueOf(Lib.findRun) + sid
							+ "/ " + SplitsJMD.ReturnOtherRunsToJMD(null, spl.getOtherRuns()));
					Tool.printSEND(oos, Lib.SERVER_INFO_TI + "send fin ,please wait the result");
				}
			}
		}
	}
}
