package sair.ire;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.script.ScriptEngineFactory;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

import sair.FCM;
import sair.LoaderManager;
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

	/** 已自动扫描过同父目录的目录集合，避免递归重复扫描 */
	private final LinkedHashSet<String> scannedSiblingDirs = new LinkedHashSet<String>();

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

		case "run":
			return run(args);

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

	private Object run(String args) {
		if (args == null || args.trim().isEmpty()) {
			SairCons.println(FCM.Error_Color, "usage: ire/run [path] [args...]");
			return false;
		}

		String[] parts = splitPathAndArgs(args);
		String path = new Urler(parts[0]).getUrl();
		String runArgs = parts[1];

		File mainFile = new File(path);
		if (!mainFile.exists()) {
			SairCons.println(FCM.Error_Color, "file not found: " + path);
			return false;
		}
		String mainPath = mainFile.getAbsolutePath();

		scannedSiblingDirs.clear();

		// 1. 收集阶段：递归收集 ire 文件、ir 文件与 jar 路径
		LinkedHashMap<String, String> irPaths = new LinkedHashMap<String, String>();
		List<String> jarPaths = new ArrayList<String>();
		LinkedHashSet<String> ireFiles = new LinkedHashSet<String>();
		collectImports(mainPath, irPaths, jarPaths, ireFiles);

		// 2. jar 运行期加载：加入当前 SairLoader 链
		for (String jar : jarPaths) {
			try {
				LoaderManager.loadLibJar(jar);
			} catch (Exception e) {
				SairCons.println(FCM.Error_Color, "load jar failed: " + jar + " " + e.getMessage());
			}
		}

		// 3. 转译阶段：统一转译所有 ire 文件（带完整 ir 路径映射）
		LinkedHashMap<String, String> sources = new LinkedHashMap<String, String>();
		String mainClassName = null;
		for (String irePath : ireFiles) {
			String src = readFile(irePath);
			if (src == null) {
				SairCons.println(FCM.Error_Color, "read file failed: " + irePath);
				return false;
			}
			IRETranslator tr = new IRETranslator(src, irPaths);
			if (!tr.translate()) {
				SairCons.println(FCM.Error_Color, "translate failed: " + irePath + " " + tr.getError());
				return false;
			}
			String cls = tr.getFullClassName();
			if (sources.containsKey(cls)) {
				SairCons.println(FCM.Error_Color, "duplicate group name: " + cls + " (" + irePath + ")");
				return false;
			}
			sources.put(cls, tr.getJavaSource());
			if (irePath.equals(mainPath))
				mainClassName = cls;
		}

		if (mainClassName == null) {
			SairCons.println(FCM.Error_Color, "main group not found");
			return false;
		}

		// 4. 批量编译 + 5. 反射调用（finally 清理本次编译产生的字节码缓存）
		Set<String> compiledBefore = new HashSet<String>(UDFParse.compiledClassNames());
		try {
			String err = UDFParse.compileBatch(sources, jarPaths);
			if (err != null) {
				SairCons.println(FCM.Error_Color, "compile failed:\r\n" + err);
				return false;
			}

			Class<?> clazz = UDFParse.loadClass(mainClassName);
			Method main = clazz.getMethod("main", String.class);
			return main.invoke(null, runArgs);
		} catch (NoSuchMethodException e) {
			SairCons.println(FCM.Error_Color, "main(String) method not found: " + e.getMessage());
		} catch (Exception e) {
			SairCons.println(FCM.Error_Color, "execute failed: " + e.getMessage());
		} finally {
			UDFParse.removeCompiled(compiledBefore);
		}
		return null;
	}

	/** 递归收集 import 的 ire 文件、ir 文件与 jar 路径，建立 ir 逻辑名映射 */
	private void collectImports(String irePath, LinkedHashMap<String, String> irPaths, List<String> jarPaths,
			LinkedHashSet<String> ireFiles) {
		irePath = new File(irePath).getAbsolutePath();
		if (!ireFiles.add(irePath))
			return;

		String src = readFile(irePath);
		if (src == null)
			return;
		IRETranslator tr = new IRETranslator(src);
		if (!tr.extractImports())
			return;

		String baseDir = new File(irePath).getParent();

		// 同父目录下无需 import：自动扫描当前文件所在目录的一级 .ire/.ir
		scanSiblings(baseDir, irPaths, jarPaths, ireFiles);

		for (String jar : tr.getJarPaths()) {
			String abs = resolvePath(baseDir, jar);
			File f = new File(abs);
			if (f.isDirectory()) {
				File[] fs = f.listFiles();
				if (fs != null)
					for (File ff : fs)
						if (ff.isFile() && ff.getName().toLowerCase().endsWith(".jar"))
							jarPaths.add(ff.getAbsolutePath());
			} else if (f.isFile()) {
				jarPaths.add(f.getAbsolutePath());
			}
		}

		for (String ir : tr.getIrImports()) {
			String abs = resolvePath(baseDir, ir);
			File f = new File(abs);
			if (f.isDirectory()) {
				scanIrDir(f, irPaths);
			} else {
				putIrPath(f, irPaths);
			}
		}

		for (String imp : tr.getIreImports()) {
			String abs = resolvePath(baseDir, imp);
			File f = new File(abs);
			if (f.isDirectory()) {
				File[] fs = f.listFiles();
				if (fs != null)
					for (File ff : fs)
						if (ff.isFile()) {
							String n = ff.getName().toLowerCase();
							if (n.endsWith(".ire"))
								collectImports(ff.getAbsolutePath(), irPaths, jarPaths, ireFiles);
							else if (n.endsWith(".ir"))
								putIrPath(ff, irPaths);
						}
			} else if (f.isFile() && f.getName().toLowerCase().endsWith(".ire")) {
				collectImports(f.getAbsolutePath(), irPaths, jarPaths, ireFiles);
			}
		}
	}

	private void scanIrDir(File dir, LinkedHashMap<String, String> irPaths) {
		File[] fs = dir.listFiles();
		if (fs == null)
			return;
		for (File f : fs)
			if (f.isFile() && f.getName().toLowerCase().endsWith(".ir"))
				putIrPath(f, irPaths);
	}

	/** 扫描某目录一级下的 .ire/.ir（同父目录免 import 规则），每个目录只扫描一次 */
	private void scanSiblings(String dir, LinkedHashMap<String, String> irPaths, List<String> jarPaths,
			LinkedHashSet<String> ireFiles) {
		if (dir == null || !scannedSiblingDirs.add(dir))
			return;
		File d = new File(dir);
		File[] fs = d.listFiles();
		if (fs == null)
			return;
		for (File f : fs) {
			if (!f.isFile())
				continue;
			String n = f.getName().toLowerCase();
			if (n.endsWith(".ire"))
				collectImports(f.getAbsolutePath(), irPaths, jarPaths, ireFiles);
			else if (n.endsWith(".ir"))
				putIrPath(f, irPaths);
		}
	}

	private void putIrPath(File f, LinkedHashMap<String, String> irPaths) {
		if (!f.exists())
			return;
		String name = f.getName();
		int dot = name.lastIndexOf('.');
		if (dot > 0)
			name = name.substring(0, dot);
		irPaths.put(name, f.getAbsolutePath());
	}

	private String resolvePath(String baseDir, String p) {
		File f = new File(p);
		if (f.isAbsolute())
			return f.getAbsolutePath();
		return new File(baseDir, p).getAbsolutePath();
	}

	private String readFile(String path) {
		try {
			return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	private String[] splitPathAndArgs(String args) {
		args = args.trim();
		if (args.startsWith("\"")) {
			int end = args.indexOf('"', 1);
			if (end > 0) {
				String path = args.substring(1, end);
				String rest = args.substring(end + 1).trim();
				return new String[] { path, rest };
			}
		}
		int sp = args.indexOf(' ');
		if (sp < 0)
			return new String[] { args, "" };
		return new String[] { args.substring(0, sp), args.substring(sp + 1).trim() };
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
				"========== IRE 脚本语言语法 ==========", //
				"ire/run [path] [args...] : 执行 .ire 脚本，[args...] 拼接为单个字符串传入 main(String args)", //
				"", //
				"【文件结构】一个 .ire 文件对应一个 group（转译为一个 Java 类）", //
				"  group 组名 {", //
				"    fc main(String args) { ... }   // 入口函数", //
				"  }", //
				"", //
				"【基础类型】Bool/Int/Double/String/Long/LLong/Char/var(Object)", //
				"  基础类型统一转译为封装类型：Int→Integer、Long→Long、Double→Double、Bool→Boolean、Char→Character（可作泛型实参）", //
				"  数组类型支持：Int[] arr、String[][] matrix 等", //
				"", //
				"【函数 fc】fc [返回类型] 函数名(参数) { ... }", //
				"  fc 统一编译为 public static；无 return 时统一返回 null", //
				"", //
				"【字段】group 内可直接声明：Int count = 0;", //
				"", //
				"【import 导入】", //
				"  import { java:java.util.List;  ../MyLib.ire;  Sair\\MyLib2.ir;  Sair }", //
				"  （同父目录下的 .ire/.ir 无需 import，自动可用）", //
				"", //
				"【jar 导入】jar { 路径或目录 }  // 目录仅扫描一级下的全部 jar", //
				"", //
				"【调用】ire 调用：MyLib.func1(args);  ir 调用：MyLib2.LabelName; 或 MyLib2;", //
				"", //
				"【控制流/表达式】if/else、switch/case、for、while、do-while、三目、数学/位移运算、(Int)x 强转 全部透传 Java", //
				"", //
				"【泛型/Java类型】声明支持 List<Int>、java.util.List<String>、Map<String,Integer> 等", //
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
