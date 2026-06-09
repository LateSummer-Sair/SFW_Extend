package sair.nsc;
 
import java.net.Socket;

import sair.sacoms.MathCast;
import sair.sys.SairCons;
import sair.user.Activity;

/**
 * Server MainActivity </br>
 * 
 * This is the main activity portal of the server </br>
 * 
 * The srun method of parent class activity is implemented, which supports the
 * return of arbitrary objects</br>
 * 
 * @version 3.0
 * @author Sair
 **/
public class S extends Activity {

	/**
	 * This main entrance is used for debugging, not the main entrance of the
	 * program
	 * 
	 * @param args
	 *            Main method ARGS
	 **/
/*	public static void main(String[] args) {
		JMD.toTest(Cons.creatAnCons("server", new S()), "jmd/gui");
	}*/

	/* Server Manager Object (Using singleton mode) */
	private SM sm;

	/* Server port */
	private int port = -1;

	public Object main(String name,String args) {

		/**
		 * server/start</br>
		 * </br>
		 * server/port 8060</br>
		 * </br>
		 **/

		/*
		 * if command ERR,return false,if fin return true.
		 **/

		String chose = name;
		if ("start".equals(chose))
			return tostart();
		else if ("port".equals(chose))
			return setport(args);
		else
			return false;
	}

	/**
	 * This is the port setting method.</br>
	 * First of all, </br>
	 * it will determine whether args is legal.</br>
	 * If it is not legal,</br>
	 * it will not be set,</br>
	 * and the assignment will be set only if it is legal.</br>
	 * </br>
	 * 
	 * 
	 * @param args
	 *            in spl.getOtherRuns()
	 * 
	 * @return fin is true,err is false
	 **/
	private boolean setport(String args) {
		if (args != null && !"".equals(args)) {
			port = MathCast.StringsIntToInt(args);
			if (port <= 0)
				port = 8060;
			SairCons.println("端口已设置：" + port);
			return true;
		} else {
			SairCons.println("端口参数错误格式！");
			return false;
		}
	}

	/**
	 * The server starts to work command method.</br>
	 * Before running,</br>
	 * it will check whether the port parameters are set normally. </br>
	 * If it is abnormal, </br>
	 * it will prompt an error. Normal operation is allowed only when the
	 * parameters are normal and legal.</br>
	 * 
	 * @return fin is true,err is false
	 **/
	private boolean tostart() {
		if (port <= 0) {
			SairCons.println("请先设置端口参数！");
			return false;
		} else {
			if (sm == null) {
				sm = new SM(port) {
					public Runnable run(final int socketID, final Socket s) {
						return new SingleServer(socketID, s, sm);
					}
				};
				sm.startServer();
				//JGUI.setInfoTitle("JMD远程服务端已经启动");
			} else{
				SairCons.println("请不要重复使用start命令！");
				}
		}
		return true;
	}

	public void exit() {
		if (sm != null) {
			sm.close();
			sm = null;
			//JGUI.setInfoTitle(null);
			SairCons.println("JMD远程服务端已经关闭");
		}
	}

	public String[] help() {
		return new String[]{
				"JMD Server V3.0",
				"Coder : Sair",
				"*/port [portID] 设置 JMD_server 服务端的端口参数",
				"*/start 开始 JMD_server 服务端工作",
				"本次更新不再支持静态的端口设置，需要使用过命令设置端口参数",};
	}

	@Override
	protected String dataDir() {
		// TODO Auto-generated method stub
		return null;
	}

}
