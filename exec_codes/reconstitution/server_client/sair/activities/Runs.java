package sair.activities;

import java.awt.Color;
import java.io.File;

import sair.FCM;
import sair.Pathes;
import sair.client.Client;
import sair.client.coma.ClientCommand;
import sair.client.file.ClientFile;
import sair.sacoms.MathCast;
import sair.server.Server;
import sair.server.coma.ServerCommand;
import sair.server.file.ServerFile;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.sys.tools.ToolPack;
import sair.user.PrintRunnable;

final class Runs {

	static final class Printble implements PrintRunnable {
		private String ip;
		private int port;
		private SCActivity sca;

		public Printble(String ip, int port, SCActivity sca) {
			this.ip = ip;
			this.port = port;
			this.sca = sca;
		}

		@Override
		public void run(Integer index, Color c, String info) {
			if (c == null)
				c = FCM.EXECTION_help_Color;
			int r = c.getRed();
			int g = c.getGreen();
			int b = c.getBlue();
			String cmd = ip + " " + port + " /print-c " + r + " " + g + " " + b + " " + info;
			sca.runs.main("sendComa", cmd);
		}

	}

	private final String printbleName = "SCN_REMOTE_" + this.toString();
	private SCActivity sca;

	private Server serverComa = null;
	private Server serverFile = null;

	private Client clientFile = null;
	private Client clientComa = null;

	private Runs(SCActivity sca) {
		this.sca = sca;
	}

	final static Runs runs(SCActivity sca) {
		return new Runs(sca);
	}

	public Object main(String funcName, String args) {
		switch (funcName) {
		/**/case "startFileServer":
			/**/return startFileServer(args);
		/**/case "startComaServer":
			/**/return startComaServer(args);
		/**/case "ready":
			/**/return ready(args);
		/**/case "unReady":
			/**/return unready(args);
		/**/case "sendFile":
			/**/return sendFile(args);
		/**/case "sendComa":
			/**/return sendComa(args);
		/**/case "stopComaServer":
			/**/return stopComaServer(args);
		/**/case "stopFileServer":
			/**/return stopFileServer(args);
		/**/case "stopComaClient":
			/**/return stopComaClient(args);
		/**/case "stopFileClient":
			/**/return stopFileClient(args);
		/**/default:
			/**/return false;
		}
	}

	private Object sendFile(String args) {
		if (this.clientFile != null) {
			ConsFrame.printComponent(FCM.Error_Color, "有文件正在发送中,请先等待完成!");
			return true;
		}

		if (args == null)
			return false;

		String[] spedArgs = args.split(" ");
		if (spedArgs.length < 3)
			return false;

		String ip = spedArgs[0];
		int port = MathCast.StringsIntToInt(spedArgs[1]);
		String[] path = ToolPack.pathRepack(args);
		if (path == null || path.length < 1)
			return false;
		ConsFrame.printComponent("[" + path[0] + "]正在从本机发送到:[" + ip + "]:[" + port + "]");
		this.clientFile = new ClientFile(port, ip, new File(path[0]));
		this.clientFile.toWork();
		ConsFrame.printComponent(path[0] + "从本机发送文件[" + path[0] + "]到:[" + ip + "]:[" + port + "]成功!");
		this.clientFile = null;
		return true;
	}

	private Object sendComa(String args) {
		if (args == null)
			return false;

		String[] spedArgs = args.split(" ");
		if (spedArgs.length < 3)
			return false;

		String ip = spedArgs[0];
		int port = MathCast.StringsIntToInt(spedArgs[1]);
		String cmd = ToolPack.reArg(spedArgs, new Integer[] { 0, 1 });
		this.clientComa = new ClientCommand(port, ip, cmd);
		Object o = ((ClientCommand) Runs.this.clientComa).toWork();
		Runs.this.clientComa = null;
		if (o != null && o instanceof Boolean) {
			if ((Boolean) o == true)
				o = "true";
			else if ((Boolean) o == false)
				o = "false";
		}
		return o;
	}

	private Object startComaServer(String args) {
		if (this.clientComa != null) {
			ConsFrame.printComponent(FCM.Error_Color, "命令接收服务正在运行!请先终止!");
			return true;
		}
		if (args == null || "".equals(args))
			return false;
		int portCom = MathCast.StringsIntToInt(args);

		this.serverComa = new ServerCommand(portCom, sca);
		this.serverComa.startServer();
		ConsFrame.printComponent("命令接收服务已经运行在端口:" + String.valueOf(portCom));
		return true;
	}

	private Object startFileServer(String args) {
		if (this.serverFile != null) {
			ConsFrame.printComponent(FCM.Error_Color, "文件接收服务正在运行!请先终止!");
			return true;
		}
		if (args == null || "".equals(args))
			return false;
		int port = MathCast.StringsIntToInt(args);

		this.serverFile = new ServerFile(port, sca);
		this.serverFile.startServer();
		ConsFrame.printComponent("文件接收服务已经运行在端口:" + String.valueOf(port));

		return true;
	}

	private Object stopComaClient(String args) {
		if (this.clientComa != null)
			this.clientComa.close();
		this.clientComa = null;
		ConsFrame.printComponent("已关闭正在进行的命令客户端");
		return true;
	}

	private Object stopFileClient(String args) {
		if (this.clientFile != null)
			this.clientFile.close();
		this.clientFile = null;
		ConsFrame.printComponent("已关闭正在进行的文件发送客户端");
		return true;
	}

	private Object stopFileServer(String args) {
		if (this.serverFile != null)
			this.serverFile.close();
		this.serverFile = null;
		ConsFrame.printComponent("已关闭文件接收服务");
		return true;
	}

	private Object stopComaServer(String args) {
		if (this.serverComa != null)
			this.serverComa.close();
		this.serverComa = null;
		ConsFrame.printComponent("已关闭命令接收服务");
		return true;
	}

	private Object ready(String args) {
		if (args == null || "".equals(args))
			return false;
		String[] spedArgs = args.split(" ");
		if (spedArgs.length < 2)
			return false;
		String ip = spedArgs[0];
		int port = MathCast.StringsIntToInt(spedArgs[1]);
		SairCons.addPrintRunnable(printbleName, new Printble(ip, port, sca));
		return true;
	}

	private Object unready(String args) {
		SairCons.removePrintRunnable(printbleName);
		return true;
	}

	public String[] helpStr() {
		return new String[] { //
				Pathes.printSplit, //
				"Version:SC_New_V2.0", //
				"Coder:Sair", //
				Pathes.printSplit, //
				sca.getName() + "/startFileServer [port] 文件接收服务在指定的port端口运行,会自动接收文件到:", //
				"\t" + sca.getDataDir(), //
				sca.getName() + "/startComaServer [port] 命令接收服务在指定的port端口运行,会自动接收并且执行sfw指令", //
				//
				sca.getName() + "/ready [ip] [port] 将控制台设置成其他输出模式", //
				"\tip:远程端的ip或者域名地址", //
				"\tport:远程端的接收器所在端口", //
				//
				sca.getName() + "/unReady 卸载本机的其他输出模式", //
				//
				sca.getName() + "/sendFile [ip] [port] [\"filePath\"] 发送文件到指定SFW客户端", //
				"\tip:远程端的ip或者域名地址", //
				"\tport:远程端的接收器所在端口", //
				"\tfilePath:本机文件路径,需要双引号括住", //
				//
				sca.getName() + "/sendComa [ip] [port] [CMD] 发送SFW命令到指定SFW客户端", //
				"\tip:远程端的ip或者域名地址", //
				"\tport:远程端的接收器所在端口", //
				"\tCMD:SFW命令", //
				//
				sca.getName() + "/stopComaServer 关闭自动接收命令服务", //
				sca.getName() + "/stopFileServer 关闭自动接收文件服务", //
				sca.getName() + "/stopComaClient 关闭现在正在执行的命令流任务", //
				sca.getName() + "/stopFileClient 关闭现在正在执行的文件流任务"//

		};
	}

	public void exit() {
		stopComaServer(null);
		stopFileServer(null);
		stopComaClient(null);
		stopFileClient(null);
		unready(null);
	}
}
