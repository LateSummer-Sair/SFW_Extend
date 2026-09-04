package sair.ire;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import sair.LoaderManager;
import sair.SairLoader;

/**
 */
public class UDFParse {

	private static ClassLoader[] urlClassLoaders = new ClassLoader[] { LoaderManager.loader,
			LoaderManager.systemLoader };
	private String fullClassName;
	private String sourceCode;
	private static Map<String, ByteJavaFileObject> javaFileObjectMap = new ConcurrentHashMap<>();
	static Map<String, UDFParse> javaUDFParseMap = new ConcurrentHashMap<>();
	private JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
	private DiagnosticCollector<JavaFileObject> diagnosticsCollector = new DiagnosticCollector<>();

	UDFParse(String sourceCode) {
		this.sourceCode = sourceCode;
		this.fullClassName = getFullClassName(sourceCode);
	}

	Boolean compiler() throws MalformedURLException {
		if (compiler == null)
			return null;
		StandardJavaFileManager standardFileManager = compiler.getStandardFileManager(diagnosticsCollector, null, null);
		JavaFileManager javaFileManager = new StringJavaFileManage<JavaFileManager>(standardFileManager);
		JavaFileObject javaFileObject = new StringJavaFileObject(fullClassName, sourceCode);
		List<String> options = new ArrayList<String>();
		options.add("-classpath");
		options.add(buildClasspath(null));
		JavaCompiler.CompilationTask task = compiler.getTask(null, javaFileManager, diagnosticsCollector, options, null,
				Arrays.asList(javaFileObject));
		Boolean call = task.call();
		if (null != call && true == call)
			javaUDFParseMap.put(fullClassName, this);
		return call;
	}

	Object getUDF() throws Exception {
		StringClassLoader stringClassLoader = new StringClassLoader();
		Class<?> obj = stringClassLoader.findClass(fullClassName);
		Constructor<?> constructor = obj.getConstructor();
		return constructor.newInstance();
	}

	/** 获取编译后生成的 Class（不实例化，用于反射调用 static 方法） */
	Class<?> getCompiledClass() throws Exception {
		StringClassLoader stringClassLoader = new StringClassLoader();
		return stringClassLoader.findClass(fullClassName);
	}

	/**
	 * 批量编译多个 Java 源文件（用于 import 递归转译的多类一次性编译）。
	 *
	 * @param sources   fullClassName -> javaSource
	 * @param extraJars 额外的 jar 路径（ire 的 jar{} 导入）
	 * @return null 表示成功，否则返回编译错误信息
	 */
	public static String compileBatch(Map<String, String> sources, Collection<String> extraJars) {
		JavaCompiler c = ToolProvider.getSystemJavaCompiler();
		if (c == null)
			return "no JDK compiler available";
		DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<JavaFileObject>();
		StandardJavaFileManager stdFm = c.getStandardFileManager(diag, null, null);
		JavaFileManager fm = new StringJavaFileManage<JavaFileManager>(stdFm);
		List<JavaFileObject> files = new ArrayList<JavaFileObject>();
		for (Map.Entry<String, String> e : sources.entrySet())
			files.add(new StringJavaFileObject(e.getKey(), e.getValue()));

		List<String> options = new ArrayList<String>();
		options.add("-classpath");
		options.add(buildClasspath(extraJars));

		boolean ok = c.getTask(null, fm, diag, options, null, files).call();
		if (ok)
			return null;
		StringBuilder sb = new StringBuilder();
		for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics())
			sb.append(d.toString()).append("\r\n");
		return sb.toString();
	}

	/** 加载已编译的 Class（不实例化） */
	public static Class<?> loadClass(String fullClassName) throws Exception {
		StringClassLoader stringClassLoader = new StringClassLoader();
		return stringClassLoader.findClass(fullClassName);
	}

	/** 返回当前编译字节码缓存的类名快照 */
	public static Set<String> compiledClassNames() {
		return new HashSet<String>(javaFileObjectMap.keySet());
	}

	/** 移除编译字节码缓存中不在 keep 集合内的类（用于 run 后清理本次新增字节码） */
	public static void removeCompiled(Collection<String> keep) {
		for (String k : new HashSet<String>(javaFileObjectMap.keySet()))
			if (!keep.contains(k))
				javaFileObjectMap.remove(k);
	}

	/** 移除指定类的编译字节码（用于热更新缓存清理） */
	public static void removeClasses(Collection<String> classNames) {
		for (String cn : classNames)
			javaFileObjectMap.remove(cn);
	}

	/** 移除单个类：同时清理字节码缓存与 UDFParse 映射（用于 cpjavafile/newobject 手动卸载） */
	public static void removeClass(String fullClassName) {
		javaFileObjectMap.remove(fullClassName);
		javaUDFParseMap.remove(fullClassName);
	}

	/** 判断某个类是否已有编译字节码缓存 */
	public static boolean hasClass(String fullClassName) {
		return javaFileObjectMap.containsKey(fullClassName);
	}

	/** 构建编译期 classpath：当前 SFW 类加载链 + act 加载器 + 额外 jar */
	private static String buildClasspath(Collection<String> extraJars) {
		Set<String> set = new HashSet<String>();
		// 加入所有 act 加载器（ExecLoaders）中的 jar，确保 IRE 插件自身（含 IREHelper）在编译 classpath 中。
		for (SairLoader actLoader : LoaderManager.ExecLoaders.values()) {
			if (actLoader == null)
				continue;
			for (File file : actLoader.getAllJarFile())
				addClasspathEntry(set, file);
		}
		for (ClassLoader classloader : urlClassLoaders) {
			if (classloader instanceof SairLoader) {
				for (File file : ((SairLoader) classloader).getAllJarFile())
					addClasspathEntry(set, file);
			} else {
				String paths = System.getProperty("java.class.path");
				if (paths != null)
					for (String p : paths.split(java.util.regex.Pattern.quote(File.pathSeparator)))
						if (p != null && !p.trim().isEmpty())
							set.add(p);
			}
		}
		if (extraJars != null)
			for (String j : extraJars)
				if (j != null && !j.trim().isEmpty())
					addClasspathEntry(set, new File(j));

		StringBuilder sb = new StringBuilder();
		for (String p : set) {
			if (sb.length() > 0)
				sb.append(File.pathSeparator);
			sb.append(p);
		}
		return sb.toString();
	}

	/** 仅添加存在的 jar 或目录到 classpath 集合（#14：去重、过滤非 jar、去掉尾部分隔符） */
	private static void addClasspathEntry(Set<String> set, File file) {
		if (file == null)
			return;
		String p = file.getAbsolutePath();
		if (!file.exists())
			return;
		if (file.isDirectory() || p.toLowerCase().endsWith(".jar"))
			set.add(p);
	}

	String getCompilerMessage() {
		StringBuilder sb = new StringBuilder();
		List<Diagnostic<? extends JavaFileObject>> diagnostics = diagnosticsCollector.getDiagnostics();
		for (Diagnostic<?> diagnostic : diagnostics) {
			sb.append(diagnostic.toString()).append("\r\n");
		}
		return sb.toString();
	}

	/*
	 * private long getCompilerTakeTime() { return compilerTakeTime; }
	 */

	private static String getFullClassName(String sourceCode) {
		String pkg = "";
		Matcher m = Pattern.compile("package\\s+([\\w.]+)\\s*;").matcher(sourceCode);
		if (m.find())
			pkg = m.group(1) + ".";
		// 匹配顶层 class/interface/enum，忽略修饰符、泛型参数与 extends/implements 部分
		Matcher c = Pattern.compile("\\b(?:class|interface|enum)\\s+([A-Za-z_$][\\w$]*)").matcher(sourceCode);
		if (c.find())
			return pkg + c.group(1);
		return pkg;
	}

	private static class StringJavaFileObject extends SimpleJavaFileObject {
		private String contents;

		StringJavaFileObject(String className, String contents) {
			super(URI.create("string:///" + className.replaceAll("\\.", "/") + Kind.SOURCE.extension), Kind.SOURCE);
			this.contents = contents;
		}

		@Override
		public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
			return contents;
		}

	}

	private static class ByteJavaFileObject extends SimpleJavaFileObject {
		private ByteArrayOutputStream outPutStream;

		ByteJavaFileObject(String className, Kind kind) {
			super(URI.create("string:///" + className.replaceAll("\\.", "/") + Kind.SOURCE.extension), kind);
		}

		@Override
		public OutputStream openOutputStream() {
			outPutStream = new ByteArrayOutputStream();
			return outPutStream;
		}

		byte[] getCompiledBytes() {
			return outPutStream == null ? new byte[0] : outPutStream.toByteArray();
		}
	}

	private static class StringJavaFileManage<T> extends ForwardingJavaFileManager<JavaFileManager> {
		StringJavaFileManage(JavaFileManager fileManager) {
			super(fileManager);
		}

		@Override
		public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
				JavaFileObject.Kind kind, FileObject sibling) throws IOException {
			ByteJavaFileObject javaFileObject = new ByteJavaFileObject(className, kind);
			javaFileObjectMap.put(className, javaFileObject);
			return javaFileObject;
		}
	}

	private static class StringClassLoader extends ClassLoader {
		@Override
		protected Class<?> findClass(String name) throws ClassNotFoundException {
			ByteJavaFileObject fileObject = javaFileObjectMap.get(name);
			if (fileObject != null) {
				byte[] bytes = fileObject.getCompiledBytes();
				return defineClass(name, bytes, 0, bytes.length);
			}
			try {
				return toLoad(name);
			} catch (Exception e) {
				return super.findClass(name);
			}
		}

		private Class<?> toLoad(String name) {
			Class<?> clazz = null;
			// 先尝试 act 加载器（IRE 插件自身通过 ExectionLoader 加载），确保 IREHelper 等插件类可被脚本类解析
			for (SairLoader actLoader : LoaderManager.ExecLoaders.values()) {
				if (actLoader == null)
					continue;
				try {
					clazz = actLoader.loadClass(name);
				} catch (ClassNotFoundException e) {
				}
				if (clazz != null)
					return clazz;
			}
			// 再尝试原有加载链
			for (ClassLoader classloader : urlClassLoaders) {
				try {
					clazz = classloader.loadClass(name);
				} catch (ClassNotFoundException e) {
				}
				if (clazz != null)
					return clazz;
			}
			return clazz;
		}
	}
}
