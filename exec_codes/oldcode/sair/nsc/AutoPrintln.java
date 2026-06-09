package sair.nsc;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;

import sair.sacoms.MathCast;
import sair.sacoms.SplitsJMD;
import sair.sacoms.until.ObjectBySplits;
import sair.sys.SairCons;
 
/**
 * The automatic receiver of the client uses a separate thread to receive the
 * value forwarded by the server. </br>
 * If an object object is received, </br>
 * it will be saved to the Lib.map In, </br>
 * the value is named the hash code of the JMD command.</br>
 * If it is a string,</br>
 * it will enter the string analysis. </br>
 * The string prefixed with @ symbol will be recognized as the execution
 * command, </br>
 * the JMD command will be executed, </br>
 * and the result will be packaged as mod template and returned to the server
 * again. </br>
 * The prerequisite object must implement the serialization interface.</br>
 * </br>
 * If it is not marked as a command string, it will be directly output to the
 * display.</br>
 * 
 * @author Sair
 * 
 **/
public class AutoPrintln implements Runnable {

	/* mark CM Object */
	private CM cm;

	/* mark C Object */
	private C c;

	/**
	 * @param cm
	 *            CM Object
	 * @param c
	 *            C Object
	 **/
	public AutoPrintln(CM cm, C c) {
		this.cm = cm;
		this.c = c;
	}

	public void run() {
		while (this.cm.isOpen()) {
			Object getInfo = null;
			try {
				getInfo = Tool.objREAD(new ObjectInputStream(this.cm.getSocket().getInputStream()));
			} catch (IOException e) {
				c.exit();
				break;
			}
			if (getInfo instanceof MOD) {
				MOD mod = (MOD) getInfo;
				if (mod != null && Lib.getList.equals(mod.getJmd())) {
					SairCons.println("--------------------------------------------------------------------");
					@SuppressWarnings("unchecked")
					ArrayList<String> list = (ArrayList<String>) mod.getObj();
					for (String str : list) {
						SairCons.println(str);
					}
					SairCons.println("--------------------------------------------------------------------");
				}
				Lib.addObject(String.valueOf(mod.getJmd().hashCode()), mod);
				SairCons.println("[" + mod.getJmd() + "]" + " ==>" + mod.getJmd().hashCode());
			} else if (getInfo instanceof String) {
				String info = (String) getInfo;
				if (info != null && info.length() > 0 && Lib.findRun == info.charAt(0)) {
					ObjectBySplits jds = SplitsJMD.split(new StringBuffer(info).deleteCharAt(0).toString());
					info = SplitsJMD.ReturnOtherRunsToJMD(null, jds.getOtherRuns());
					Object o = new String("null");
					try {
						o = SairCons.runner(false, info);
					} catch (Exception e) {
						// SaLogger.outLogger(e);
					}
					if (o != null && o instanceof Serializable)
						try {
							Tool.modSEND(new MOD(MathCast.StringsIntToInt(jds.getHead()), (Serializable) o, info),
									new ObjectOutputStream(this.cm.getSocket().getOutputStream()));
						} catch (IOException e) {
							// SaLogger.outLogger(e);
						}
				} else
					SairCons.println(String.valueOf(getInfo));
			} else
				SairCons.println("客户端接收到服务器传来的未知类型！");
		}
	}
}
