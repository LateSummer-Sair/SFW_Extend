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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import sair.aiagent.AiAgentActivity;

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
    /** 白名单包前缀（Nashorn ClassFilter 允许的 Java 类包） */
    private static final String[] JS_ALLOWED_PACKAGES = {
        "java.lang.",
        "java.util.",
        "java.math.",
        "java.text.",
        "java.net.",
        "java.io.",
        "java.nio.file.",
        "sair."
    };

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

    /**
     * 执行 JavaScript 代码片段，返回结果字符串。
     * <p>通过 Nashorn ClassFilter 限制 Java 类型访问，仅允许白名单包。</p>
     */
    public String evalJS(String script) {
        if (!jsAvailable) return "JS 引擎不可用：当前 JVM 未找到 JavaScript 脚本引擎。";
        try {
            // 为此次执行设置 ClassFilter 沙箱（Nashorn 特有）
            setNashornClassFilter();
            Object result = jse.eval(script);
            return result == null ? "null" : result.toString();
        } catch (ScriptException e) {
            return "JS 错误: " + e.getMessage();
        }
    }

    /**
     * 为 Nashorn 引擎设置 ClassFilter，限制 Java.type() 可访问的类。
     * <p>仅允许白名单包前缀的 Java 类。非 Nashorn 引擎静默跳过。</p>
     */
    private void setNashornClassFilter() {
        try {
            Class<?> cfClass = Class.forName("jdk.nashorn.api.scripting.ClassFilter");
            Object filter = java.lang.reflect.Proxy.newProxyInstance(
                cfClass.getClassLoader(),
                new Class[]{cfClass},
                new java.lang.reflect.InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                        if ("exposeToScripts".equals(method.getName()) && args.length == 1) {
                            String className = (String) args[0];
                            for (String prefix : JS_ALLOWED_PACKAGES) {
                                if (className.startsWith(prefix)) return true;
                            }
                            return false;
                        }
                        return null;
                    }
                });
            java.lang.reflect.Method setFilter = jse.getClass().getMethod("setClassFilter", cfClass);
            setFilter.invoke(jse, filter);
        } catch (Exception ignored) {
            // 非 Nashorn 引擎或不支持 ClassFilter（Graal.js / Rhino），静默跳过
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

    /**
     * 编译 Java 源码并返回其实例（不执行 run，不丢弃），供调用方缓存复用。
     * <p>用于三方技能 java 代码段：编译一次、缓存实例，之后反复反射调用 {@code airun} 方法。
     * 编译失败或加载失败返回 {@code null}，错误信息见 {@link #getLastCompilerMessage()}。</p>
     *
     * @param sourceCode Java 源码（无需 package，建议定义 {@code public Object airun(String)} 或 {@code public Object airun()}）
     * @param objName    标识名（仅用于日志）
     * @return 编译成功返回实例，失败返回 null
     */
    public synchronized Object compileAndCache(String sourceCode, String objName) {
        if (compiler == null) {
            lastCompilerMessage = "Java 编译器不可用：当前运行在 JRE 环境，需要 JDK。";
            return null;
        }
        String className = compileJava(sourceCode);
        if (className == null) return null;
        Class<?> clazz = loadLastCompiled(className);
        if (clazz == null) return null;
        try {
            Object instance = clazz.getConstructor().newInstance();
            // 字节码引用已可释放（class 已加载进 JVM，实例持有其引用）
            lastCompiledObject = null;
            lastLoadError = "";
            return instance;
        } catch (NoSuchMethodException e) {
            lastCompilerMessage = "类 [" + className + "] 缺少无参构造器。";
            lastCompiledObject = null;
            return null;
        } catch (Exception e) {
            lastCompilerMessage = "创建实例失败: " + e.toString();
            lastCompiledObject = null;
            return null;
        }
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

    /**
     * <b>多源码文件一起编译</b>并加载出全部类（技能文件夹模型：一个技能 = 一个目录里的若干 .java）。
     *
     * <p>与 {@link #compileAndCache} 的区别：那份实现只保留「最后一个」class 字节
     * （{@code lastCompiledObject} 会被逐个覆盖），所以顶层辅助类会加载失败、
     * 静态内部类会在<b>调用时</b>才炸 {@code NoClassDefFoundError}。这里把每个输出 class
     * 全部收进一张表，再由同一个类加载器定义，辅助类/内部类都能正常工作。</p>
     *
     * @param sources   文件名 → 源码（键仅用于错误定位与类名兜底，如 {@code AirunSkill.java}）
     * @param logName   日志用的标识（技能名）
     * @return 编译产物句柄（含全部已加载类）；编译失败返回 {@code null}，原因见 {@link #getLastCompilerMessage()}
     */
    public synchronized CompiledUnit compileAll(Map<String, String> sources, String logName) {
        if (compiler == null) {
            lastCompilerMessage = "Java 编译器不可用：当前运行在 JRE 环境，需要 JDK。";
            return null;
        }
        if (sources == null || sources.isEmpty()) {
            lastCompilerMessage = "没有可编译的源码文件。";
            return null;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager stdMgr = compiler.getStandardFileManager(diagnostics, null, null);
        MultiClassFileManager fileMgr = new MultiClassFileManager(stdMgr);

        List<JavaFileObject> sourceObjs = new java.util.ArrayList<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String fileName = e.getKey();
            String className = fileName.toLowerCase().endsWith(".java")
                    ? fileName.substring(0, fileName.length() - 5) : fileName;
            sourceObjs.add(new StringJavaFileObject(className, e.getValue()));
        }

        // 编译（不做 -Werror：技能作者写个未使用变量不该导致技能不可用）
        JavaCompiler.CompilationTask task = compiler.getTask(null, fileMgr, diagnostics,
                buildCompilerOptions(), null, sourceObjs);
        boolean success = task.call();

        StringBuilder msg = new StringBuilder();
        if (!success) {
            msg.append("编译失败:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    msg.append("  ").append(d.toString()).append("\n");
                }
            }
            // 环境类问题（例如编译 classpath 里没有插件本体）一眼可见
            msg.append("  [编译 classpath] ").append(classpathSummary()).append("\n");
            lastCompilerMessage = msg.toString();
            return null;
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.WARNING || d.getKind() == Diagnostic.Kind.MANDATORY_WARNING) {
                msg.append("\n  ⚠ ").append(d.toString());
            }
        }

        Map<String, byte[]> bytes = fileMgr.getClassBytes();
        if (bytes.isEmpty()) {
            lastCompilerMessage = "编译成功但没有产出任何 class 文件（源码里没有类声明？）";
            return null;
        }
        MultiClassLoader loader = new MultiClassLoader(bytes);
        Map<String, Class<?>> classes = new java.util.LinkedHashMap<>();
        for (String cn : bytes.keySet()) {
            try {
                classes.put(cn, Class.forName(cn, true, loader));
            } catch (Throwable t) {
                msg.append("\n  ⚠ 类 ").append(cn).append(" 加载失败: ").append(t);
            }
        }
        if (classes.isEmpty()) {
            lastCompilerMessage = "编译成功但所有类都加载失败:" + msg;
            return null;
        }
        lastCompilerMessage = "技能 [" + logName + "] 编译成功，类: " + classes.keySet() + msg;
        return new CompiledUnit(classes, loader, lastCompilerMessage);
    }

    /** 一次多文件编译的产物：全部已加载类 + 它们的类加载器。 */
    public static final class CompiledUnit {
        private final Map<String, Class<?>> classes;
        private final ClassLoader loader;
        private final String message;

        CompiledUnit(Map<String, Class<?>> classes, ClassLoader loader, String message) {
            this.classes = java.util.Collections.unmodifiableMap(classes);
            this.loader = loader;
            this.message = message;
        }

        /** 类名 → 已加载的类（含辅助类与内部类）。 */
        public Map<String, Class<?>> getClasses() { return classes; }
        public ClassLoader getClassLoader() { return loader; }
        public String getMessage() { return message; }

        /** 按简单名或全名取类（{@code Helper} / {@code pkg.Helper} 都能查到）。 */
        public Class<?> find(String name) {
            if (name == null) return null;
            Class<?> c = classes.get(name);
            if (c != null) return c;
            for (Map.Entry<String, Class<?>> e : classes.entrySet()) {
                if (e.getKey().endsWith("." + name)) return e.getValue();
            }
            return null;
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
            sb.append("  [编译 classpath] ").append(classpathSummary()).append("\n");
            lastCompilerMessage = sb.toString();
            return null;
        }
    }

    // ==================== 内部: 类加载 ====================

    private Class<?> loadLastCompiled(String className) {
        if (lastCompiledObject == null) return null;
        // ★ 将编译产物作为构造参数传入，避免实例字段竞态
        InMemoryClassLoader loader = new InMemoryClassLoader(lastCompiledObject);
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

        // 用 LinkedHashSet 保序：**"插件本体"排最前**，避免同名旧包先被 javac 命中
        Set<String> cpSet = new LinkedHashSet<>();

        // 0. ★ 最可靠的一条：问类加载器"本类到底从哪加载的"
        //    它同时**与 jar 文件名无关、与 jar 放在哪也无关** —— 插件叫 ai.jar / bot.jar /
        //    插件名.jar 都一样；放在 plugins/exection、plugins、甚至自定目录也一样。
        //    （部署实测：CodeSource 的位置是 null、插件 jar 不在 java.class.path、
        //      加载器也不是 URLClassLoader，只有这条路能拿到真实路径。）
        String own = resolveOwnCodeLocation();
        if (own != null) cpSet.add(own);

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

        // 3. CodeSource（有就用；部署环境下常常拿不到）
        try {
            java.security.CodeSource cs = DynamicCodeEngine.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                URL loc = cs.getLocation();
                addClasspathEntry(cpSet, new File(loc.toURI()).getAbsolutePath());
            }
        } catch (Exception ignored) {}

        // 4. SFW 插件 jar / 库 jar 路径集合（按路径收集，与 jar 叫什么名字无关）：
        //    LoaderManager.execJarPathSet = 启动时加载的插件 jar；libJarPathSet = plugins/lib/*.jar
        try {
            for (String p : sair.LoaderManager.execJarPathSet) addClasspathEntry(cpSet, p);
            for (String p : sair.LoaderManager.libJarPathSet) addClasspathEntry(cpSet, p);
        } catch (Throwable ignored) {}

        // 5. SFW modlib jars (LoaderManager.loader = SairLoader)
        try {
            Object modLibLoader = LoaderManager.loader;
            if (modLibLoader != null) {
                java.lang.reflect.Method getAllJar = modLibLoader.getClass().getMethod("getAllJarFile");
                @SuppressWarnings("unchecked")
                java.util.Collection<File> jars =
                        (java.util.Collection<File>) getAllJar.invoke(modLibLoader);
                if (jars != null) {
                    for (File f : jars) addClasspathEntry(cpSet, f.getAbsolutePath());
                }
            }
        } catch (Exception ignored) {}

        // 6. 兜底：扫 SFW 的插件/库/模组目录（**只按 .jar 后缀收，不认任何文件名**）
        try {
            addJarsUnder(cpSet, sair.Pathes.execDir);
            addJarsUnder(cpSet, sair.Pathes.pluginsDir);
            addJarsUnder(cpSet, sair.Pathes.libDir);
            addJarsUnder(cpSet, sair.Pathes.modDir);
            addJarsUnder(cpSet, sair.Pathes.bootDir);
        } catch (Throwable ignored) {}

        warnIfMultiplePluginCopies(cpSet);

        // 记下这次编译用的 classpath（编译失败时回显，便于诊断"环境缺 jar"类问题）
        lastClasspath = cpSet;

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

    /** 本次编译使用的 classpath 条目（诊断用）。 */
    private volatile Set<String> lastClasspath = new LinkedHashSet<>();

    /** 本插件自己是从哪个 jar / 目录加载的（缓存一次；进程内不会变）。 */
    private static volatile String ownCodeLocation;
    private static volatile boolean ownLocationResolved = false;

    /**
     * 解析「本类实际是从哪里加载的」——编译 classpath 里最该排第一的那一条。
     *
     * <p><b>不依赖文件名，也不依赖目录</b>：向类加载器要本类自己的资源 URL，
     * 从形如 {@code jar:file:/x/bot.jar!/sair/aiagent/core/DynamicCodeEngine.class} 的 URL 里
     * 反推出 jar 路径（目录形态则是 classes 根目录）。插件叫 {@code ai.jar}、{@code bot.jar}、
     * 中文名，放在 {@code plugins/exection}、{@code plugins} 或任意自定目录，都能定位到。</p>
     *
     * <p>顺序：① 类加载器资源（最可靠）→ ② {@code Class.getResource} → ③ CodeSource。
     * 全都拿不到时返回 null，由调用方的其它来源兜底。</p>
     */
    static String resolveOwnCodeLocation() {
        if (ownLocationResolved) return ownCodeLocation;
        String found = null;
        String res = "sair/aiagent/core/DynamicCodeEngine.class";

        // ① 向上遍历加载器链，谁先给出这个资源就用谁（就是实际加载本类的那个加载器）
        try {
            ClassLoader cl = DynamicCodeEngine.class.getClassLoader();
            while (cl != null && found == null) {
                URL url = cl.getResource(res);
                if (url != null) found = locationFromResourceUrl(url.toString(), res);
                cl = cl.getParent();
            }
        } catch (Throwable ignored) {}

        // ② 类相对资源（等价写法，某些加载器只实现这一个）
        if (found == null) {
            try {
                URL url = DynamicCodeEngine.class.getResource("DynamicCodeEngine.class");
                if (url != null) found = locationFromResourceUrl(url.toString(), res);
            } catch (Throwable ignored) {}
        }

        // ③ CodeSource（有就用）
        if (found == null) {
            try {
                java.security.CodeSource cs = DynamicCodeEngine.class.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    String p = new File(cs.getLocation().toURI()).getAbsolutePath();
                    if (new File(p).exists()) found = p;
                }
            } catch (Throwable ignored) {}
        }

        ownCodeLocation = found;
        ownLocationResolved = true;
        return found;
    }

    /**
     * 从资源 URL 反推「根路径」。
     *
     * @param urlStr  形如 {@code jar:file:/a/b.jar!/pkg/C.class} 或 {@code file:/a/classes/pkg/C.class}
     * @param resource 资源相对路径（用来在目录形态下剥掉尾部）
     * @return jar 绝对路径或 classes 根目录；解析不出返回 null
     */
    static String locationFromResourceUrl(String urlStr, String resource) {
        if (urlStr == null) return null;
        try {
            if (urlStr.startsWith("jar:")) {
                int bang = urlStr.indexOf("!/");
                String inner = (bang > 0) ? urlStr.substring(4, bang) : urlStr.substring(4);
                return urlToFile(inner);
            }
            if (urlStr.startsWith("file:")) {
                String f = urlToFile(urlStr);
                if (f == null) return null;
                // 目录形态：把 <root>/sair/aiagent/core/DynamicCodeEngine.class 的尾部剥掉
                String suffix = resource.replace('/', File.separatorChar);
                if (f.endsWith(suffix)) return f.substring(0, f.length() - suffix.length());
                int idx = f.lastIndexOf(File.separatorChar);
                return (idx > 0) ? f.substring(0, idx) : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** URL → 本地绝对路径（处理 %20 等转义与 Windows 盘符）。 */
    private static String urlToFile(String urlStr) {
        try {
            String u = urlStr;
            if (u.startsWith("file:")) {
                try {
                    return new File(new java.net.URI(u)).getAbsolutePath();
                } catch (Exception ignored) {
                    // 退化为手工解码
                }
                u = u.substring(5);
                if (u.startsWith("//")) u = u.substring(2);          // UNC 或 /C:/...
                u = java.net.URLDecoder.decode(u, "UTF-8");
                if (u.length() > 2 && u.charAt(0) == '/' && u.charAt(2) == ':') u = u.substring(1);
                File f = new File(u);
                return f.exists() ? f.getAbsolutePath() : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 一次性检查：classpath 里是否有多个 jar 都含本插件的类（放了旧副本时"改了不生效"的常见原因）。 */
    private static volatile boolean dupWarned = false;

    private static void warnIfMultiplePluginCopies(Set<String> cpSet) {
        if (dupWarned) return;
        dupWarned = true;
        try {
            List<String> hits = new ArrayList<>();
            for (String p : cpSet) {
                if (!p.toLowerCase().endsWith(".jar")) continue;
                try (java.util.jar.JarFile jf = new java.util.jar.JarFile(p)) {
                    if (jf.getEntry("sair/aiagent/core/DynamicCodeEngine.class") != null) hits.add(p);
                } catch (Throwable ignored) {}
            }
            if (hits.size() > 1) {
                AiAgentActivity.debugLog("[DynamicCodeEngine] ⚠ 发现 " + hits.size()
                        + " 个 jar 都含本插件类，编译 classpath 只认第一个（已把实际加载的那个排在最前）："
                        + hits + " —— 若出现「改了代码不生效」，请删掉多余副本");
            }
        } catch (Throwable ignored) {}
    }


    /** 加入一条 classpath：去重、补全相对路径、只收真实存在的路径（javac 对不存在的条目会忽略，但没必要塞）。 */
    private static void addClasspathEntry(Set<String> cpSet, String path) {
        if (path == null || path.trim().isEmpty()) return;
        try {
            String p = path.trim();
            File f = new File(p);
            if (!f.isAbsolute()) {
                try {
                    p = LoaderManager.canonicalPath(p);
                    f = new File(p);
                } catch (Throwable ignored) {
                    f = f.getAbsoluteFile();
                    p = f.getPath();
                }
            }
            if (f.exists()) cpSet.add(f.getAbsolutePath());
        } catch (Throwable ignored) {}
    }

    /** 把一个目录下（含一层子目录）的所有 jar 加入 classpath。 */
    private static void addJarsUnder(Set<String> cpSet, String dirPath) {
        if (dirPath == null || dirPath.trim().isEmpty()) return;
        try {
            File dir = new File(dirPath);
            if (!dir.isAbsolute()) dir = new File(LoaderManager.canonicalPath(dirPath));
            if (!dir.isDirectory()) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) {
                    cpSet.add(f.getAbsolutePath());
                } else if (f.isDirectory() && !f.getName().startsWith(".")) {
                    File[] inner = f.listFiles();
                    if (inner == null) continue;
                    for (File g : inner) {
                        if (g.isFile() && g.getName().toLowerCase().endsWith(".jar")) {
                            cpSet.add(g.getAbsolutePath());
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 编译 classpath 健康摘要（技能编译失败时一并回显，便于一眼看出是不是环境缺 jar）。
     *
     * @return 形如 {@code 42 个条目；插件本体=C:\...\bot.jar ✓在列}
     */
    public String classpathSummary() {
        Set<String> cp = lastClasspath;
        // 用「本类实际从哪里加载」判定，而不是 CodeSource —— 部署环境下后者常常为 null
        String self = resolveOwnCodeLocation();
        boolean inList = false;
        if (self != null) {
            for (String p : cp) {
                if (p.equals(self)) { inList = true; break; }
            }
        }
        return cp.size() + " 个条目；插件本体=" + (self == null ? "(未解析出来)" : self)
                + (inList ? " ✓在列" : " ✗不在列");
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

    // ==================== 内部类: 多文件编译（技能文件夹模型） ====================

    /** 收集全部输出 class 的文件管理器（与只留最后一个 class 的旧实现相对）。 */
    private class MultiClassFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, ByteJavaFileObject> outputs = new java.util.LinkedHashMap<>();

        MultiClassFileManager(JavaFileManager fileManager) {
            super(fileManager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            ByteJavaFileObject obj = new ByteJavaFileObject(className, kind);
            outputs.put(className, obj);
            return obj;
        }

        Map<String, byte[]> getClassBytes() {
            Map<String, byte[]> out = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, ByteJavaFileObject> e : outputs.entrySet()) {
                byte[] b = e.getValue().getBytes();
                if (b != null && b.length > 0) out.put(e.getKey(), b);
            }
            return out;
        }
    }

    /**
     * 由「类名 → 字节码」直接定义类的加载器；父加载器 = 插件加载器（技能因此能调 ai.jar 里的类）。
     *
     * <p><b>自身优先（self-first）</b>：技能类名几乎都叫 {@code AirunSkill}，而父加载器（插件 classpath）
     * 上如果有任何同名类，标准委派会让父类"赢"，于是技能静默跑成别人的实现。
     * 因此这里先在自己这张表里找，找到了就自己 define，找不到再交给父加载器。</p>
     */
    private static class MultiClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        MultiClassLoader(Map<String, byte[]> classes) {
            super(DynamicCodeEngine.class.getClassLoader() != null
                    ? DynamicCodeEngine.class.getClassLoader()
                    : ClassLoader.getSystemClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null && classes.containsKey(name)) {
                    loaded = findClass(name);   // 自己的类，先定义（不委派给父加载器）
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
                return super.loadClass(name, resolve);
            }
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes != null) {
                return defineClass(name, bytes, 0, bytes.length);
            }
            try {
                ClassLoader modLibLoader = (ClassLoader) LoaderManager.loader;
                if (modLibLoader != null) return modLibLoader.loadClass(name);
            } catch (Exception ignored) {
                // 落到父加载器
            }
            return super.findClass(name);
        }
    }

    // ==================== 内部类: InMemoryClassLoader ====================
    private class InMemoryClassLoader extends ClassLoader {
        private final ByteJavaFileObject compiledObject;

        InMemoryClassLoader(ByteJavaFileObject compiledObject) {
            // 使用 DynamicCodeEngine 的类加载器为父加载器
            // 确保编译产物能访问 ai.jar 中的类
            super(DynamicCodeEngine.class.getClassLoader() != null
                    ? DynamicCodeEngine.class.getClassLoader()
                    : ClassLoader.getSystemClassLoader());
            this.compiledObject = compiledObject;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            if (compiledObject != null) {
                byte[] bytes = compiledObject.getBytes();
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
