package sair.sacoms;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

/**
 *
 * <p>
 * 打印<br>
 * 
 * @author _Sair
 * @version printer_2.3
 * 
 **/

public class CMD {

	private static final String exitcmd = "exit";

	private static final String CMDURL_win = "explorer ";

	private static final String CMDURL_lin = "x-www-browser ";

	private static final String CMD_win = "cmd.exe /k ";

	private static final String CMD_lin = "/bin/bash";

	// private static final String CMD_nofound = "您的Linux目录下没有bash程序！";
	/**
	 * Windows 系统接口Key
	 **/
	public final static Key<String> win = Key.creatKey(CMD_win);
	/**
	 * Linux 系统接口Key
	 **/
	public static final Key<String> lin = Key.creatKey(CMD_lin);

	/**
	 * 命令行调用接口(默认返回消息)
	 * <p>
	 * 将多条命令在原窗口执行，执行完后关闭窗口<br>
	 * 
	 * @param head
	 *            指定调用命令行的系统类型
	 * @param cmd
	 *            将指定命令发送到Windows的CMD控制台执行
	 * @return SairLists<String>
	 **/
	public static SairLists<String> runCommands(Key<String> head, String... cmd) {
		SairLists<String> rspList = new SairLists<String>();
		if (head != null && lin.about().equals(head.about()) && !new Urler(head.about()).getUrlFound()) {
			// SaLogger.outLogger(CMD_nofound);
			return rspList;
		}
		Runtime run = Runtime.getRuntime();
		Process proc = null;
		BufferedWriter bw = null;
		BufferedReader br = null;
		PrintWriter pw = null;
		try {
			proc = run.exec(head.about(), null, null);
			InputStreamReader isr = new InputStreamReader(proc.getInputStream());
			br = new BufferedReader(isr);

			OutputStreamWriter osw = new OutputStreamWriter(proc.getOutputStream());
			bw = new BufferedWriter(osw);
			pw = new PrintWriter(bw);

			for (String line : cmd) {
				pw.println(line);
			}
			pw.println(exitcmd);
			pw.flush();
			if (pw != null)
				pw.close();
			String line;
			while ((line = br.readLine()) != null)
				rspList.add(line);
		} catch (IOException e) {
			// SaLogger.outLogger(e);
		} finally {
			if (br != null)
				try {
					br.close();
				} catch (IOException e) {
					// SaLogger.outLogger(e);
				}
			if (proc != null)
				proc.destroy();
		}
		return rspList;
	}

	/**
	 * 命令行调用接口
	 * <p>
	 * 将多条命令在原窗口执行，执行完后关闭窗口（本接口自动判断系统类型Windows/Linux）<br>
	 * 
	 * @param cmd
	 *            将指定命令发送到Windows的CMD控制台执行
	 * @return SairLists<String>
	 **/
	public static SairLists<String> autoRunCommands(String... cmd) {
		String os = Urler.getOS();
		Key<String> key = lin;
		if (Urler.getOSlist()[0].equals(os))
			key = win;
		return runCommands(key, cmd);
	}

	/**
	 * 命令行调用接口(网页专用)
	 * <p>
	 * 将多条命令在原窗口执行，执行完后关闭窗口（本接口自动判断系统类型Windows/Linux）<br>
	 * 
	 * @param cmd
	 *            将指定命令发送到Windows的CMD控制台执行
	 * @return SairLists<String>
	 **/
	public static SairLists<String> autoOpenToUrl(String... cmd) {
		String os = Urler.getOS();
		Key<String> key = lin;
		if (Urler.getOSlist()[0].equals(os))
			key = win;
		return openToUrl(key, cmd);
	}

	/**
	 * 命令行调用接口(网页专用)
	 * <p>
	 * 将多条命令在原窗口执行，执行完后关闭窗口（本接口自动判断系统类型Windows/Linux）<br>
	 * 
	 * @param cmd
	 *            将指定命令发送到Windows的CMD控制台执行
	 * @return SairLists<String>
	 **/
	public static SairLists<String> openToUrl(Key<String> key, String... cmd) {
		SairLists<String> list = new SairLists<String>();
		String head = CMDURL_lin;
		if (Urler.getOSlist()[0].equals(Urler.getOS()))
			head = CMDURL_win;
		if (cmd != null)
			for (String str : cmd)
				list.setListAllToEnd(runCommands(key, head + str));
		return list;
	}
}
