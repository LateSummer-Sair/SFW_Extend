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

	private ClassLoader[] urlClassLoaders = new ClassLoader[] { LoaderManager.loader, LoaderManager.systemLoader };
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
		HashSet<String> pathString = new HashSet<String>();
		for (ClassLoader classloader : urlClassLoaders)
			if (classloader instanceof SairLoader) {
				Collection<File> con = ((SairLoader) classloader).getAllJarFile();
				for (File file : con)
					pathString.add(file.getAbsolutePath() + String.valueOf(File.pathSeparator));
			} else {
				String paths = System.getProperty("java.class.path");
				if (paths != null) {
					String[] spted = paths.split(String.valueOf(File.pathSeparator));
					for (String file : spted)
						pathString.add(file + String.valueOf(File.pathSeparator));
				}
			}
		
		StringBuilder sb = new StringBuilder();
		for(String p:pathString) {
			sb.append(p);
		}
		options.add(sb.toString());
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

	private class StringJavaFileObject extends SimpleJavaFileObject {
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

	private class ByteJavaFileObject extends SimpleJavaFileObject {
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
			return outPutStream.toByteArray();
		}
	}

	private class StringJavaFileManage<T> extends ForwardingJavaFileManager<JavaFileManager> {
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

	private class StringClassLoader extends ClassLoader {
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
