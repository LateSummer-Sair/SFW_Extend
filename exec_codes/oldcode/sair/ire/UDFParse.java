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
		Object o = javaFileObjectMap.get(fullClassName);
		if (o != null)
			return true;
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

	/** 构建编译期 classpath：当前 SFW 类加载链 + 额外 jar */
	private static String buildClasspath(Collection<String> extraJars) {
		HashSet<String> set = new HashSet<String>();
		for (ClassLoader classloader : urlClassLoaders) {
			if (classloader instanceof SairLoader) {
				Collection<File> con = ((SairLoader) classloader).getAllJarFile();
				for (File file : con)
					set.add(file.getAbsolutePath() + File.pathSeparator);
			} else {
				String paths = System.getProperty("java.class.path");
				if (paths != null)
					for (String p : paths.split(String.valueOf(File.pathSeparator)))
						set.add(p + File.pathSeparator);
			}
		}
		if (extraJars != null)
			for (String j : extraJars)
				if (j != null && !j.trim().isEmpty())
					set.add(j + File.pathSeparator);
		StringBuilder sb = new StringBuilder();
		for (String p : set)
			sb.append(p);
		return sb.toString();
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
		String className = "";
		Pattern pattern = Pattern.compile("package\\s+\\S+\\s*;");
		Matcher matcher = pattern.matcher(sourceCode);
		if (matcher.find()) {
			className = matcher.group().replaceFirst("package", "").replace(";", "").trim() + ".";
		}

		pattern = Pattern.compile("class((?:(?!extends).))+");
		matcher = pattern.matcher(sourceCode);
		if (matcher.find()) {
			className += matcher.group().replaceFirst("class", "").replace("{", "").trim();
		}
		return className;
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
