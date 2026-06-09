package sair.aiagent.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
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

/**
 * 动态代码引擎 —— JS 脚本执行 + Java 一次性动态注入。
 *
 * <h3>设计原则</h3>
 * <ul>
 *   <li>无状态、无存储 —— 每次注入独立编译→加载→执行→丢弃</li>
 *   <li>仅接受无 package 声明、定义了 {@code public Object run()} 方法的类</li>
 *   <li>编译→实例化→调用 run()→返回结果，全程一次性</li>
 * </ul>
 *
 * <h3>功能</h3>
 * <ul>
 *   <li>JS：evalJS() 执行脚本</li>
 *   <li>Java：compileAndInstantiate() 一次性注入执行</li>
 * </ul>
 */
public class DynamicCodeEngine {

    // ==================== JS 引擎 ====================

    private final ScriptEngineManager sem;
    private final ScriptEngine jse;
    private boolean jsAvailable;

    // ==================== Java 编译器 ====================

    private final JavaCompiler compiler;
    /** 编译器→类加载器之间传递字节码，一次性使用，用完即弃 */
    private ByteJavaFileObject lastCompiledObject;
    private String lastCompilerMessage = "";
    private String lastLoadError = "";

    // ==================== 构造 ====================

    public DynamicCodeEngine() {
        // --- JS 引擎 ---
        sem = new ScriptEngineManager();
        ScriptEngine engine = sem.getEngineByName("JavaScript");
        if (engine == null) engine = sem.getEngineByName("nashorn");
        if (engine == null) engine = sem.getEngineByName("graal.js");
        this.jse = engine;
        this.jsAvailable = (engine != null);

        // --- Java 编译器 ---
        this.compiler = loadCompiler();
    }

    /** 获取 JavaCompiler：标准方式失败时手动加载 JDK 的 tools.jar */
    private static JavaCompiler loadCompiler() {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc != null) return jc;

        // 标准方式失败（SFW classpath 不含 tools.jar）→ 手动定位
        try {
            File jdkHome = new File(System.getProperty("java.home"));
            // JDK 8: tools.jar 在 ../lib/ 或 lib/
            for (String rel : new String[]{"../lib/tools.jar", "lib/tools.jar"}) {
                File toolsJar = new File(jdkHome, rel);
                if (toolsJar.exists()) {
                    URLClassLoader loader = new URLClassLoader(
                            new URL[]{toolsJar.toURI().toURL()},
                            ClassLoader.getSystemClassLoader());
                    Class<?> javacTool = Class.forName(
                            "com.sun.tools.javac.api.JavacTool", true, loader);
                    jc = (JavaCompiler) javacTool.newInstance();
                    if (jc != null) return jc;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== JS: 执行代码 ====================

    /** 执行 JavaScript 代码片段，返回结果字符串。 */
    public String evalJS(String script) {
        if (!jsAvailable) return "JS 引擎不可用：当前 JVM 未找到 JavaScript 脚本引擎。";
        try {
            Object result = jse.eval(script);
            return result == null ? "null" : result.toString();
        } catch (ScriptException e) {
            return "JS 错误: " + e.getMessage();
        }
    }

    /** JS 引擎是否可用 */
    public boolean isJsAvailable() { return jsAvailable; }

    // ==================== Java: 一次性编译+注入执行 ====================

    /**
     * 编译无 package 的 Java 源码并执行。
     * 源码中的类必须定义 {@code public Object run()} 方法。
     * <p>全程一次性：编译→加载→实例化→调用 run()→返回结果，不保留任何状态。</p>
     *
     * @param sourceCode Java 源码（无需 package，必须定义 run() 方法）
     * @param objName    标识名（仅用于日志）
     * @return 编译+执行结果
     */
    public synchronized String compileAndInstantiate(String sourceCode, String objName) {
        if (compiler == null) {
            return "Java 编译器不可用：当前运行在 JRE 环境，需要 JDK。";
        }

        // 1. 编译
        String className = compileJava(sourceCode);
        if (className == null) return lastCompilerMessage;

        // 2. 加载
        Class<?> clazz = loadLastCompiled(className);
        if (clazz == null) return "类 [" + className + "] 加载失败。" + (lastLoadError != null ? " 详情: " + lastLoadError : "");

        // 3. 实例化
        Object instance;
        try {
            instance = clazz.getConstructor().newInstance();
        } catch (NoSuchMethodException e) {
            return "类 [" + className + "] 缺少无参构造器。";
        } catch (Exception e) {
            return "创建实例失败: " + e.toString();
        }

        // 4. 执行 run()
        String execResult = invokeRun(instance, className);
        // ★ 彻底清理字节码引用，交由 GC 回收
        //    保留 lastCompilerMessage 供调用方读取编译诊断信息
        lastCompiledObject = null;
        lastLoadError = "";
        return execResult;
    }

    /** 通过反射调用 run() 方法（无需接口，协议约定即可） */
    private String invokeRun(Object instance, String className) {
        try {
            java.lang.reflect.Method runMethod = instance.getClass().getMethod("run");
            Object result = runMethod.invoke(instance);
            return "动态注入执行成功 [" + className + "]\nrun() 返回: "
                    + (result == null ? "null" : result.toString());
        } catch (NoSuchMethodException e) {
            return "编译成功但类 [" + className + "] 中找不到无参 run() 方法。"
                    + " 请在类中定义 `public Object run()` 方法。";
        } catch (Exception e) {
            return "动态注入执行异常 [" + className + "]\nrun() 异常: " + e.toString();
        }
    }

    /** 获取最后一次编译的消息 */
    public String getLastCompilerMessage() { return lastCompilerMessage; }

    /** 判断 JDK 编译器是否可用 */
    public boolean isCompilerAvailable() { return compiler != null; }

    // ==================== 内部: 编译 ====================

    private String compileJava(String sourceCode) {
        String fullClassName = getFullClassName(sourceCode);
        if (fullClassName == null || fullClassName.isEmpty()) {
            lastCompilerMessage = "无法从源码中提取类名。请确保包含 class 声明。";
            return null;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdMgr = compiler.getStandardFileManager(diagnostics, null, null);
        InMemoryFileManager fileMgr = new InMemoryFileManager(stdMgr);

        JavaFileObject sourceObj = new StringJavaFileObject(fullClassName, sourceCode);
        List<String> options = buildCompilerOptions();

        JavaCompiler.CompilationTask task = compiler.getTask(null, fileMgr, diagnostics, options, null,
                java.util.Arrays.asList(sourceObj));
        boolean success = task.call();

        if (success) {
            StringBuilder msg = new StringBuilder("类 [" + fullClassName + "] 编译成功。");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING) {
                    msg.append("\n  ⚠ ").append(d.toString());
                }
            }
            lastCompilerMessage = msg.toString();
            return fullClassName;
        } else {
            StringBuilder sb = new StringBuilder("编译失败:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append("  ").append(d.toString()).append("\n");
            }
            lastCompilerMessage = sb.toString();
            return null;
        }
    }

    // ==================== 内部: 类加载 ====================

    private Class<?> loadLastCompiled(String className) {
        if (lastCompiledObject == null) return null;
        InMemoryClassLoader loader = new InMemoryClassLoader();
        try {
            // ★ 使用 loadClass（标准委托链），而非 findClass（跳过父加载器）
            return loader.loadClass(className);
        } catch (ClassNotFoundException e) {
            lastLoadError = "ClassNotFound: " + e.getMessage();
            return null;
        } catch (NoClassDefFoundError e) {
            lastLoadError = "NoClassDef: " + e.getMessage();
            return null;
        } catch (Throwable t) {
            lastLoadError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    // ==================== 内部: 编译器选项 ====================

    private List<String> buildCompilerOptions() {
        List<String> options = new ArrayList<>();
        options.add("-encoding");
        options.add("UTF-8");

        Set<String> cpSet = new HashSet<>();

        // 1. 系统 classpath
        String sysCp = System.getProperty("java.class.path");
        if (sysCp != null) {
            for (String part : sysCp.split(File.pathSeparator)) {
                cpSet.add(part);
            }
        }

        // 2. 当前类加载器 URL
        try {
            ClassLoader cl = DynamicCodeEngine.class.getClassLoader();
            while (cl != null) {
                if (cl instanceof URLClassLoader) {
                    for (URL url : ((URLClassLoader) cl).getURLs()) {
                        try {
                            cpSet.add(new File(url.toURI()).getAbsolutePath());
                        } catch (Exception ignored) {}
                    }
                }
                cl = cl.getParent();
            }
        } catch (Exception ignored) {}

        // ★ 确保 ai.jar 自身在编译 classpath 中
        //   ProtectionDomain.getCodeSource() 无论何种类加载器都能准确定位
        try {
            java.security.CodeSource cs = DynamicCodeEngine.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                URL loc = cs.getLocation();
                cpSet.add(new File(loc.toURI()).getAbsolutePath());
            }
        } catch (Exception ignored) {}

        // 3. SFW modlib jars (LoaderManager.loader = SairLoader)
        try {
            Object modLibLoader = LoaderManager.loader;
            if (modLibLoader != null) {
                java.lang.reflect.Method getAllJar = modLibLoader.getClass().getMethod("getAllJarFile");
                @SuppressWarnings("unchecked")
                java.util.Collection<File> jars =
                        (java.util.Collection<File>) getAllJar.invoke(modLibLoader);
                if (jars != null) {
                    for (File f : jars) cpSet.add(f.getAbsolutePath());
                }
            }
        } catch (Exception ignored) {}

        if (!cpSet.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String p : cpSet) {
                if (sb.length() > 0) sb.append(File.pathSeparator);
                sb.append(p);
            }
            options.add("-classpath");
            options.add(sb.toString());
        }

        return options;
    }

    // ==================== 内部: 类名提取 ====================

    /** 从无 package 的 Java 源码中提取类名（有 package 也会正确处理）。 */
    static String getFullClassName(String sourceCode) {
        String className = "";
        java.util.regex.Pattern pkgPattern =
                java.util.regex.Pattern.compile("package\\s+(\\S+)\\s*;");
        java.util.regex.Matcher matcher = pkgPattern.matcher(sourceCode);
        if (matcher.find()) {
            className = matcher.group(1).trim() + ".";
        }

        java.util.regex.Pattern clsPattern =
                java.util.regex.Pattern.compile("class\\s+(\\w+)");
        matcher = clsPattern.matcher(sourceCode);
        if (matcher.find()) {
            className += matcher.group(1);
        }
        return className;
    }

    // ==================== 内部类: JavaFileObject ====================

    private class StringJavaFileObject extends SimpleJavaFileObject {
        private final String contents;

        StringJavaFileObject(String className, String contents) {
            super(URI.create("string:///" + className.replace('.', '/')
                    + Kind.SOURCE.extension), Kind.SOURCE);
            this.contents = contents;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return contents;
        }
    }

    static class ByteJavaFileObject extends SimpleJavaFileObject {
        private ByteArrayOutputStream bos;

        ByteJavaFileObject(String className, Kind kind) {
            super(URI.create("string:///" + className.replace('.', '/')
                    + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            bos = new ByteArrayOutputStream();
            return bos;
        }

        byte[] getBytes() {
            return bos != null ? bos.toByteArray() : new byte[0];
        }
    }

    // ==================== 内部类: InMemoryFileManager ====================

    private class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        InMemoryFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                    JavaFileObject.Kind kind, FileObject sibling) {
            lastCompiledObject = new ByteJavaFileObject(className, kind);
            return lastCompiledObject;
        }
    }

    // ==================== 内部类: InMemoryClassLoader ====================

    private class InMemoryClassLoader extends ClassLoader {
        InMemoryClassLoader() {
            // 使用 DynamicCodeEngine 的类加载器为父加载器
            // 确保编译产物能访问 ai.jar 中的类
            super(DynamicCodeEngine.class.getClassLoader() != null
                    ? DynamicCodeEngine.class.getClassLoader()
                    : ClassLoader.getSystemClassLoader());
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (lastCompiledObject != null) {
                byte[] bytes = lastCompiledObject.getBytes();
                if (bytes.length > 0) {
                    return defineClass(name, bytes, 0, bytes.length);
                }
            }
            // 回退：从 SairLoader 加载外部依赖
            try {
                ClassLoader modLibLoader = (ClassLoader) LoaderManager.loader;
                if (modLibLoader != null) {
                    return modLibLoader.loadClass(name);
                }
            } catch (Exception ignored) {}
            return super.findClass(name);
        }
    }
}
