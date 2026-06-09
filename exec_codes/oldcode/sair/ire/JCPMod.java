package sair.ire;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

import sair.FCM;
import sair.LoaderManager;
import sair.sacoms.FileMana;
import sair.sacoms.Key;
import sair.sacoms.StrEdit;
import sair.sacoms.Urler;
import sair.sys.SairCons;
import sair.sys.acticity.Exection;
import sair.sys.acticity.Mod;
import sair.sys.tools.ToolPack;

class JCPMod {

	static HashMap<String, Object> omap = new HashMap<String, Object>();

	private static String[] getJarMainInfoMation(String jarPath) {
		String infomations = ToolPack.getExeMain(jarPath);
		if (infomations == null)
			return null;
		String[] sp_ed = infomations.split(";");
		ArrayList<String> localList = new ArrayList<String>();
		if (sp_ed.length > 0) {
			for (String clazzName : sp_ed)
				if (clazzName != null && !"".equals(clazzName) && clazzName.length() > 0)
					localList.add(clazzName);
		} else
			return null;
		return localList.toArray(new String[localList.size()]);
	}

	// private JavaCompiler javac = ToolProvider.getSystemJavaCompiler();

	boolean loadBoot(String path) throws NoSuchMethodException, SecurityException, ClassNotFoundException,
			IllegalAccessException, IllegalArgumentException, InvocationTargetException, MalformedURLException {
		path = new Urler(path).getUrl();
		File dirFilePath = new File(path);
		if (!dirFilePath.exists())
			return false;

		Method m = Class.forName("java.net.URLClassLoader").getDeclaredMethod("addURL", new Class[] { URL.class });
		m.setAccessible(true);

		if (dirFilePath.isDirectory()) {
			ArrayList<String> bootJars = ToolPack.getAllFilesPath(dirFilePath, true);
			for (String name : bootJars)
				loadBoot0(m, name);
		} else
			loadBoot0(m, path);
		return true;
	}

	private void loadBoot0(Method m, String path)
			throws IllegalAccessException, IllegalArgumentException, InvocationTargetException, MalformedURLException {
		boolean flag = false;
		path = new Urler(path).getUrl();
		if (path.endsWith(".jar")) {
			m.invoke(LoaderManager.systemLoader, new File(path).toURI().toURL());
			flag = true;
		}

		if (flag)
			SairCons.println(FCM.loadBoot_Color, "bootReaded : " + path);
		else
			SairCons.println(FCM.Error_Color, "bootReadedERR!!! : " + path);
	}

	boolean loadLib(String path) throws MalformedURLException {
		path = new Urler(path).getUrl();

		File path_File = new File(path);

		if (!path_File.exists())
			return false;

		if (path_File.isDirectory()) {
			ArrayList<String> paths = ToolPack.getAllFilesPath(path_File, true);
			for (String p : paths)
				loadLib0(p);
		} else
			loadLib0(path);

		return true;
	}

	private void loadLib0(String p) {
		p = new Urler(p).getUrl();
		if (p.endsWith(".jar")) {
			try {
				new Mod(p);
			} catch (IOException e) {
				SairCons.println(FCM.Error_Color, e.getMessage());
			}
		}

	}

	boolean loadAct(String path) {
		path = new Urler(path).getUrl();
		File path_File = new File(path);

		if (!path_File.exists())
			return false;

		if (path_File.isDirectory()) {
			ArrayList<String> paths = ToolPack.getAllFilesPath(path_File, true);
			for (String p : paths)
				loadAct0(p);
		} else
			loadAct0(path);
		return true;
	}

	private void loadAct0(String path) {
		path = new Urler(path).getUrl();
		String[] classNames = getJarMainInfoMation(path);
		if (classNames == null)
			SairCons.println(FCM.Error_Color, path + " -> ACT_infomations notFound!");

		try {
			new Exection(classNames, path);
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, path + " -> ACT_infomations or ClassInfo is Error!");
		}

	}

	Boolean cpJavaFile(String input, String code) {
		input = new Urler(input).getUrl();
		File path_File = new File(input);

		if (null == code || "".equals(code))
			return false;

		if (!path_File.exists())
			return null;

		if (path_File.isDirectory()) {
			ArrayList<String> paths = ToolPack.getAllFilesPath(path_File, true);
			for (String p : paths)
				cpJavaFile0(p, code);
		} else
			cpJavaFile0(input, code);

		return true;
	}

	private void cpJavaFile0(String input, String code) {
		input = new Urler(input).getUrl();
		/*
		 * if (javac.run(null, null, null, input) == 0) SairCons.println(input +
		 * "  编译通过！"); else SairCons.println(FCM.Error_Color, input +
		 * "  编译失败！");
		 */
		Boolean r = false;
		String info = "文件IO错误！";
		try {
			String[] arrs = FileMana.getFileToStringArr(input, Key.creatKey(code));
			UDFParse up = new UDFParse(StrEdit.toStr(arrs, true));
			r = up.compiler();
			info = up.getCompilerMessage();
		} catch (Exception e) {
			r = null;
		}
		if (r == null)
			SairCons.println(FCM.Error_Color, "有没有一种可能，只有JDK才能编译java，而你用的是JRE");
		else if (r == false)
			SairCons.println(FCM.Error_Color, info);
	}

	Object invokeMethod(String oName, String methodName, String[] args) throws NoSuchMethodException, SecurityException,
			IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		Object o = omap.get(oName);
		if (o == null)
			return null;
		Method method = null;

		if (args == null) {
			method = o.getClass().getMethod(methodName);
			return method.invoke(o);
		} else {
			int len = args.length;
			Class<?>[] clazzs = new Class<?>[len];
			Object[] oss = new Object[len];
			for (int i = 0; i < len; i++) {
				clazzs[i] = String.class;
				oss[i] = args[i];
			}

			method = o.getClass().getMethod(methodName, clazzs);
			return method.invoke(o, oss);
		}
	}

	boolean newInstance(String name, String className) throws Exception {
		UDFParse bjfo = UDFParse.javaUDFParseMap.get(className);
		Object o = bjfo.getUDF();
		omap.put(name, o);
		return true;
	}
}
