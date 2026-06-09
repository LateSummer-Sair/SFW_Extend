package sair.nsc;
 
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.Iterator;

import sair.sacoms.MathCast;
import sair.sys.SairCons;
import sair.user.Activity;

/**
 * Client MainActivity </br>
 * 
 * This is the main activity portal of the client </br>
 * 
 * The srun method of parent class activity is implemented, which supports the
 * return of arbitrary objects</br>
 * 
 * @version 3.0
 * @author Sair
 **/
public class C extends Activity {
	
	public static void main(String[] args){
		C c = new C();
		sair.Main.toTest(c, "ip", "192.168.31.90");
		sair.Main.toTest(c, "port", "8060");
		sair.Main.toTest(c, "start", "");
	}
	

	/* Server port for client connection */
	private int port = -1;

	/* Server local or IP for client connection */
	private String local = null;

	/* Client Manager Object (Using singleton mode) */
	private CM cm;

	public Object main(String name, String args) {

		/**
		 * client/start</br>
		 * </br>
		 * client/ip 127.0.0.1</br>
		 * </br>
		 * client/port 8060</br>
		 * </br>
		 * client/send 1/ [JMD]</br>
		 * [JMD] : jmd/help</br>
		 * [JMD] : pl/start 2</br>
		 * [JMD] : ......</br>
		 * </br>
		 * client/get [Name]</br>
		 * [Name] : -6843677 </br>
		 * [Name] : 4843677 </br>
		 * [Name] : ...... </br>
		 * client/list</br>
		 * 
		 **/

		/*
		 * if command ERR,return false,if fin return true.
		 **/
		if(name ==null) return false;
		switch (name) {
			case "start": return tostart();
			case "ip": return setlocal(args);
			case "port": return setport(args);
			case "send": return send(args);
			case "get": return get(args);
			case "list": return list();
			case "getadr": return getList();
			default: return false;
		}

	}

	/**
	 * from Lib.map Takes a specified mod object value from the Lib.</br>
	 * map If the mod object exists, </br>
	 * it will be returned mod.getObject The value obtained</br>
	 * 
	 * @param otherRuns
	 *            in args
	 * @return mod.getObject
	 */
	private Object get(String otherRuns) {
		if (otherRuns != null && !"".equals(otherRuns)) {
			MOD mod = Lib.map.remove(otherRuns);
			if (mod != null)
				return mod.getObj();
		}
		return null;
	}

	/**
	 * Using iterator output Lib.map All elements are used for display</br>
	 * </br>
	 * outPut : [JMD] ==> [hashName] ==> [instanceClass]</br>
	 * 
	 * @return default true
	 **/
	private boolean list() {
		Iterator<String> it = Lib.map.keySet().iterator();
		while (it.hasNext()) {
			String name = it.next();
			MOD mod = Lib.map.get(name);
			SairCons.println(
					"[" + mod.getJmd() + "]" + " ==>" + name + " ==>" + mod.getObj().getClass().getSimpleName());
		}
		return true;
	}

	/**
	 * 
	 * The send method is used to send instructions to the server for
	 * execution,</br>
	 * find another target client, </br>
	 * and bring the command to the specified client for execution. </br>
	 * When you run send, </br>
	 * you will first check whether the entire client has successfully connected
	 * to the server. </br>
	 * If not, you will be</br>
	 * prompted with an error and return a value of false. If the server
	 * has</br>
	 * been connected, it will add the @ symbol to the head of the initial</br>
	 * command and send it to the server. The server will recognize the @
	 * symbol</br>
	 * to distinguish the link command from the client command.</br>
	 * 
	 * @param otherRuns
	 *            in args
	 * 
	 * @return fin is true,err is false
	 **/
	private boolean send(String otherRuns) {
		if (cm == null) {
			SairCons.println("还没开始启用客户端！");
			return false;
		}
		String infos = "@" + otherRuns;
		try {
			Tool.printSEND(new ObjectOutputStream(cm.getSocket().getOutputStream()), infos);
		} catch (IOException e) {
			// SaLogger.outLogger(e);
			SairCons.println("客户端发生通讯错误！");
			// exit();
		}
		return true;
	}

	private boolean getList() {
		if (cm == null) {
			SairCons.println("还没开始启用客户端！");
			return false;
		}
		try {
			Tool.printSEND(new ObjectOutputStream(cm.getSocket().getOutputStream()), Lib.getList);
		} catch (IOException e) {
			// SaLogger.outLogger(e);
			SairCons.println("客户端发生通讯错误！");
			// exit();
		}
		return true;
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
	 *            in args
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
	 * This is the local or IP setting method.</br>
	 * First of all, </br>
	 * it will determine whether args is legal.</br>
	 * If it is not legal,</br>
	 * it will not be set,</br>
	 * and the assignment will be set only if it is legal.</br>
	 * </br>
	 * 
	 * @param args
	 *            in args
	 * @return fin is true,err is false
	 **/
	private boolean setlocal(String args) {
		if (args != null && !"".equals(args)) {
			local = args;
			if (local == null || "".equals(local))
				local = "127.0.0.1";
			SairCons.println("IP已设置：" + local);
			return true;
		} else {
			SairCons.println("IP参数错误格式！");
			return false;
		}
	}

	/**
	 * The client start method is actually a way to greet the server after the
	 * initial connection </br>
	 * Lib.newLink The command is sent to the server to get in touch with the
	 * server.</br>
	 * 
	 * @return fin is true,err is false
	 **/
	private boolean tostart() {
		if (local == null || port <= 0) {
			SairCons.println("请先设置正确的IP参数和端口参数！");
			return false;
		}

		if (cm != null)
			return true;
		cm = new CM(port, local) {
			@Override
			public void run(OutputStream dos) {
				try {
					Tool.printSEND(new ObjectOutputStream(dos), Lib.newLink);
				} catch (IOException e) {
					// SaLogger.outLogger(e);
					SairCons.println("客户端发生通讯错误！");
					exit();
				}
			}
		};
		cm.startWork(this);
		return true;
	}

	public String[] help() {

		return new String[] { "JMD Client V3.0", "Coder : Sair", "*/port [portID] 设置 JMD_client 客户端的端口参数",
				"*/ip [IP/local] 设置 JMD_client 客户端的IP/local参数", "*/start 开始 JMD_client 客户端工作",
				"*/send [targetID]/ [JMD] 发送指定JMD到targetID执行并返回结果", "*/get [targetName] Get指定名字的变量值，获取之后自动从list删除",
				"*/list 查看已保存的所有值", "*/getadr 查看已经连接服务的所有Client端", "本次更新不再支持静态的端口设置，需要使用过命令设置端口参数", };
	}

	public void exit() {
		if (cm != null) {
			cm.close();
			cm = null;
			// JGUI.setInfoTitle(null);
			SairCons.println("JMD远程客户端已经关闭");
		}
	}

	@Override
	protected String dataDir() {
		// TODO Auto-generated method stub
		return null;
	}
}
