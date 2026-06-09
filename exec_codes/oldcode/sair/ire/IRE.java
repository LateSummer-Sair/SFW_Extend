package sair.ire;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import sair.FCM;
import sair.Main;
import sair.Pathes;
import sair.sacoms.SairLists;
import sair.sacoms.Urler;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.sys.acticity.Exection;
import sair.sys.acticity.Mod;
import sair.sys.tools.ToolPack;
import sair.user.Activity;

public class IRE extends Activity {

	private static final String split = "______";

	private static final String t = "\t\t\t\t\t";

	public static void main(String[] args) {

		IRE ire = new IRE();
		Libraries.activities.put("iret", ire);
		Main.main(args);

	}

	private String JSEN = "JavaScript";

	private boolean isloaded = false;
	private HashSet<String> names = new HashSet<String>();

	private final SEMod semod = new SEMod();
	private final JCPMod jcmod = new JCPMod();

	@Override
	public Object main(String funcName, String args) {
		if (!isloaded)
			enginelist(true);

		switch (funcName) {
		case "evalfunc":
			try {
				return evalfunc(args);
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, "函数无法处理此参数，请检查函数是否支持仅字符串的参数！");
				return null;
			}

		case "evalline":
			return evalline(args);

		case "loadfile":
			return loadfile(args);

		case "enginelist":
			return enginelist(false);

		case "thisengine":
			return thisengine();

		case "setengine":
			return setengine(args);

		case "cpjavafile":
			return cpjavafile(args);

		case "invokmeth":
			return invokmeth(args);

		case "newobject":
			return newobject(args);

		case "loadbootmod":
			return loadbootmod(args);

		case "loadlibmod":
			return loadlibmod(args);

		case "loadact":
			return loadact(args);

		case "objlist":
			return objlist();

		case "classlist":
			return classlist();

		case "omlist":
			return omlist(args);

		case "loadall":
			return loadall(args);

		case "unloadacti":
			return unloadacti(args);

		case "unloadmod":
			return unloadmod(args);

		}

		return false;
	}

	private Object unloadmod(String args) {
		Mod mod = null;

		Urler urler = new Urler(args);
		mod = Libraries.mods.get(urler.getUrl());
		if (mod != null)
			try {
				mod.unLoadJar();
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, "貌似无法卸载[" + urler.getUrl() + "]你自己查一下看看");
				return mod;
			}
		return mod;
	}

	private Object unloadacti(String args) {
		Exection exec = null;

		Activity acti = Libraries.activities.get(args);
		if (acti != null) {
			exec = Libraries.exections.get(acti);
			if (exec != null)
				try {
					exec.unLoadJar();
				} catch (Exception e) {
					SairCons.println(FCM.Error_Color, "貌似无法卸载[" + args + "]你自己查一下看看");
					return exec;
				}
		}
		return acti;
	}

	private Object loadall(String code) {
		SairLists<File> listFile = Tools.getJavaFiles(getDataDir());
		for (File path : listFile) {
			Boolean flag = jcmod.cpJavaFile(path.getAbsolutePath(), code);
			if (flag == null)
				SairCons.println(FCM.Error_Color, path.getAbsolutePath() + "\t不存在！");
			else if (flag == true)
				SairCons.println(path.getAbsolutePath() + "\t编译成功！");
			else if (flag == false)
				SairCons.println(FCM.Error_Color, path.getAbsolutePath() + "\t编译失败！可能您没有填写编码参数？");
		}
		return listFile;
	}

	private Object classlist() {
		Set<String> keySet = UDFParse.javaUDFParseMap.keySet();
		SairCons.println(FCM.split_Color, Pathes.printSplit);
		SairCons.println(FCM.EXECTION_help_Color, "ClassName");
		for (String key : keySet)
			SairCons.println(FCM.EXECTION_help_Color, key);
		return keySet;
	}

	private Object omlist(String args) {
		if (args == null)
			return null;
		Object obj = JCPMod.omap.get(args);
		if (obj == null) {
			SairCons.println(FCM.Error_Color, "变量名[" + args + "]不存在！");
			return true;
		}

		Class<?> clazz = obj.getClass();
		Method[] ms = clazz.getDeclaredMethods();
		SairCons.println(FCM.split_Color, Pathes.printSplit + Pathes.printSplit);
		SairCons.println(FCM.EXECTION_help_Color, "MethodName" + t + "MethodParameterCount");
		for (Method m : ms) {
			String name = m.getName();
			int ct = m.getParameterCount();
			SairCons.println(FCM.EXECTION_help_Color, name + t + ct);
		}
		return ms;
	}

	private Object objlist() {
		HashMap<String, Object> omap = JCPMod.omap;
		Set<String> keySet = omap.keySet();
		SairCons.println(FCM.split_Color, Pathes.printSplit + Pathes.printSplit);
		SairCons.println(FCM.EXECTION_help_Color, "ObjectName" + t + "ObjectInstance");
		for (String key : keySet) {
			Object obj = omap.get(key);
			SairCons.println(FCM.EXECTION_help_Color, key + t + obj.getClass().getName());
		}
		return omap;
	}

	private Object loadact(String args) {
		return jcmod.loadAct(args);
	}

	private Object loadlibmod(String args) {
		try {
			return jcmod.loadLib(args);
		} catch (MalformedURLException e) {
			SairCons.println(FCM.Error_Color, e.getMessage());
		}
		return true;
	}

	private Object loadbootmod(String args) {
		try {
			return jcmod.loadBoot(args);
		} catch (NoSuchMethodException | SecurityException | ClassNotFoundException | IllegalAccessException
				| IllegalArgumentException | InvocationTargetException | MalformedURLException e) {
			SairCons.println(FCM.Error_Color, e.getMessage());
		}
		return true;
	}

	private Object newobject(String args) {
		String[] splits = args.split(" ");
		try {
			return jcmod.newInstance(splits[0], splits[1]);
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, e.getMessage());
		}
		return null;
	}

	private Object invokmeth(String args) {
		String[] spargs = args.split(" ");

		String oName = spargs[0];
		String methodName = spargs[1];
		String argss = ToolPack.reArg(spargs, new Integer[] { 0, 1 });

		try {
			String[] argss_arr = argss.split(" ");
			if ("null".equals(argss))
				return jcmod.invokeMethod(oName, methodName, null);
			else
				return jcmod.invokeMethod(oName, methodName, argss_arr);
		} catch (NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException
				| InvocationTargetException e) {
			SairCons.println(FCM.Error_Color, e.getMessage());
		}
		return true;
	}

	private Object cpjavafile(String args) {
		try {
			String[] sp = args.split(" ");
			return jcmod.cpJavaFile(sp[1], sp[0]);
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, "Error!!!");
		}
		return null;
	}

	private Object evalfunc(String args) throws Exception {
		if (args == null || args.length() <= 0)
			return false;
		String[] splits = args.split(" ");
		if (splits.length <= 0)
			return false;

		String funcName = splits[0];

		ArrayList<String> list = new ArrayList<String>();
		for (int i = 1; i < splits.length; i++)
			list.add(splits[i]);

		Object o = semod.eval(funcName, list.toArray());
		// SairCons.println(String.valueOf(o));
		return o;
	}

	private Object setengine(String args) {
		if (names.contains(args))
			semod.JSE = semod.SEM.getEngineByName(args);
		else
			SairCons.println(FCM.Error_Color, "JVM支持的列表中没有找到语言引擎！");
		return true;
	}

	private Object thisengine() {
		SairCons.println(FCM.EXECTION_help_Color, JSEN);
		return JSEN;
	}

	private Object enginelist(boolean isloader) {
		ScriptEngineManager factory = new ScriptEngineManager();
		StringBuffer sbf = new StringBuffer();
		for (ScriptEngineFactory available : factory.getEngineFactories()) {
			List<String> names = available.getNames();
			sbf.append("\r\n");
			sbf.append(split);
			sbf.append(available.getEngineName());
			sbf.append(split);
			sbf.append(available.getLanguageName());
			for (String name : names) {
				sbf.append("\r\n");
				sbf.append(name);
				if (isloader)
					this.names.add(name);
			}
		}
		isloaded = true;
		return sbf.toString();
	}

	private Object loadfile(String args) {
		Object o = null;
		try {
			Urler url = new Urler(args);
			File file = new File(url.getUrl());
			o = semod.JSE.eval(new FileReader(file));
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, e.getMessage());
		}
		// SairCons.println(String.valueOf(o));
		return o;
	}

	private Object evalline(String args) {
		Object o = null;
		try {
			o = semod.JSE.eval(args);
		} catch (ScriptException e) {
			SairCons.println(FCM.Error_Color, e.getMessage());
		}
		// SairCons.println(String.valueOf(o));
		return o;
	}

	@Override
	public String[] help() {
		return new String[] { //
				"IRE V1.5.3", //
				"Coder : Sair", //
				"(别问我为什么不加一个热卸载bootLib的功能，因为这真的不安全，你要加自己加，反正我不加)", //
				"(还有就是，编译java文件的能力由java8的JDK提供，如果你是JRE，那么出门左拐，不要用这玩意儿比较好)", "",
				this.getName() + "/evalfunc [funcName] [funcARGS...] : 执行已经加载的函数，[funcName]为函数名，[funcARGS...]为函数的参数", //
				"\t\t注意！函数接收的参数为字符串类型，如果需要，请自行在脚本内转化！", //
				this.getName() + "/loadfile [scriptPath] : [scriptPath]为脚本所在的路径", //
				this.getName() + "/evalline [script] : [script]为单句执行的脚本命令", //
				this.getName() + "/enginelist : 打印当前JVM所支持的所有引擎", //
				this.getName() + "/thisengine : 打印现在正在使用的引擎", //
				this.getName()
						+ "/cpjavafile [code] [javaFilePath] : 使用JVM的编译器编译无包名的java文件，[code]为文本编码格式 [javaFilePath]为java文件的路径", //
				this.getName()
						+ "/newobject [name] [ClassName] : 新建对象，[ClassName]为对象名（文件名不包括拓展名），[name]为变量名（仅支持无参构造函数）", //
				this.getName() + "/invokmeth [oName] [mName] [null|args...] : 执行名称为[oName]的对象中的[mName]方法", //
				"\t\t注意！[args]接收的参数为字符串类型，如果需要，请自行在代码内转化！如果无参数，则可以传入字符串的null", //
				this.getName() + "/loadbootmod [jarFilePath] : 把jar拓展模组库以spi形式加载", //
				this.getName() + "/loadlibmod [jarFilePath] : 把jar拓展模组库以sair_ext形式加载", //
				this.getName() + "/unloadacti [actiName] : 强制使用ucp热卸载acti，与[actiName]相关的其他acti也会被一同卸载！", //
				this.getName() + "/unloadmod [modPath] : 强制使用ucp热卸载mod，[modPath]需要全路径", //
				this.getName() + "/loadact [jarFilePath] : 把jar以sair_act形式加载（需要有jar内有符合规格的MF文件）", //
				this.getName() + "/objlist : 遍历显示omap中所有对象", //
				this.getName() + "/omlist [name] : 遍历显示omap中指定对象的所有公开方法", //
				this.getName() + "/loadall [code] : 加载编译" + this.getDataDir() + "下面所有的java文件", //
				this.getName() + "/classlist : 遍历显示已加载的所有Class", //
		};
	}

	@Override
	public void exit() {

	}

}
