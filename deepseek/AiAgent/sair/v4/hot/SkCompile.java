package sair.v4.hot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

/**
 * 技能源码编译（基板②：动态加载的编译半边）。
 * <p>把技能文件夹里的 {@code *.java} 编译成<b>内存字节码</b>：技能目录不是 classpath，
 * 也不在磁盘上留 .class（技能删掉即彻底消失）。</p>
 */
public final class SkCompile {

    private SkCompile() {}

    public static final class Result {
        public final Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
        public final List<String> errors = new ArrayList<String>();

        public boolean ok() { return errors.isEmpty() && !classes.isEmpty(); }
    }

    /** 编译一组源文件；classpath 由 {@link #substrateClasspath()} 提供。 */
    public static Result compile(List<File> sources) {
        Result r = new Result();
        if (sources == null || sources.isEmpty()) {
            r.errors.add("没有源文件");
            return r;
        }
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) {
            r.errors.add("当前 JVM 没有 java 编译器（需要 JDK 而不是 JRE）");
            return r;
        }
        DiagnosticCollector<JavaFileObject> diag = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager sfm = jc.getStandardFileManager(diag, null, java.nio.charset.Charset.forName("UTF-8"));
        MemFileManager mfm = new MemFileManager(sfm, r.classes);
        try {
            Iterable<? extends JavaFileObject> units = sfm.getJavaFileObjectsFromFiles(sources);
            List<String> opts = Arrays.asList("-encoding", "UTF-8", "-nowarn", "-proc:none",
                    "-classpath", substrateClasspath());
            JavaCompiler.CompilationTask task = jc.getTask(null, mfm, diag, opts, null, units);
            boolean ok = task.call();
            for (Diagnostic<? extends JavaFileObject> d : diag.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) {
                    String f = d.getSource() == null ? "?" : new File(d.getSource().getName()).getName();
                    r.errors.add(f + ":" + d.getLineNumber() + " " + d.getMessage(null));
                }
            }
            if (!ok && r.errors.isEmpty()) r.errors.add("编译失败（无诊断信息）");
        } catch (Throwable t) {
            r.errors.add("编译异常: " + t);
        } finally {
            try {
                mfm.close();
            } catch (Exception ignored) {
            }
        }
        return r;
    }

    /**
     * 运行期编译所需的 classpath：宿主进程 + 本插件字节码位置 + 框架与其依赖库。
     *
     * <p><b>真机踩过的坑（26 个技能全部"程序包 sair.v4.* 不存在"）</b>：框架的类加载器是
     * {@code SairBaseLoader extends SecureClassLoader}，它<b>直接用 JarFile 字节流 defineClass</b>
     * （不是 {@code URLClassLoader}，也没有 CodeSource），所以
     * {@code Host.class.getProtectionDomain().getCodeSource()} 在真机上是 <b>null</b>；
     * 而真机启动是 {@code javaw -jar SFW.jar}，{@code java.class.path} 里只有 {@code SFW.jar} ——
     * 于是编译技能时压根看不到我们自己的类，26 个技能全编译失败、技能工具 0 个。
     * 探针 JVM 的 {@code -cp} 里恰好带着 {@code out}，所以这个坑在探针里照不出来。</p>
     *
     * <p>现在的取法（从可靠到兜底）：</p>
     * <ol>
     *   <li>宿主进程的 {@code java.class.path}（探针/开发机：这里就有 {@code out}）；</li>
     *   <li>本插件类的 CodeSource（能拿到时最准：开发机上是 {@code out} 目录，某些加载器下是插件 jar）；</li>
     *   <li>框架类（{@code SairCons}，走应用类加载器）的 CodeSource → 定位到 <b>SFW.jar</b>，
     *       再由它推出框架根目录 → 把 {@code plugins/lib/*.jar} 与 {@code plugins/exection/*.jar}
     *       全部加进来（gson / sqlite / jsoup / zxing 这些技能常用库都在 lib 里）；</li>
     *   <li>老逻辑里的固定路径兜底（换机器时最后一道保险）。</li>
     * </ol>
     * <p>结果缓存一次（目录内容在一次运行里不会变），失败也缓存，避免每个技能都去扫盘。</p>
     */
    public static String substrateClasspath() {
        String c = cpCache;
        if (c != null) return c;
        StringBuilder sb = new StringBuilder();
        append(sb, System.getProperty("java.class.path"));
        addClassSource(sb, sair.v4.skill.Host.class);
        addClassSource(sb, sair.v4.V4Activity.class);
        addClassSource(sb, sair.sys.SairCons.class);
        File root = frameworkRoot();
        if (root != null) {
            addJars(sb, new File(root, "plugins" + File.separator + "lib"));
            addJars(sb, new File(root, "plugins" + File.separator + "exection"));
            File sfw = new File(root, "SFW.jar");
            if (sfw.isFile()) append(sb, sfw.getAbsolutePath());
        }
        // 兜底：进程工作目录（run.bat 先 cd 到框架根，所以这里通常就是框架根）。
        // 一律由运行期推导，**代码里不写任何机器相关路径**。
        addRootLike(sb, System.getProperty("user.dir"));
        c = sb.toString();
        cpCache = c;
        return c;
    }

    /** 把"看起来像框架根"的目录下的 SFW.jar / plugins/lib / plugins/exection 补进来（不存在就什么都不做）。 */
    private static void addRootLike(StringBuilder sb, String dir) {
        if (dir == null || dir.trim().isEmpty()) return;
        File root = new File(dir.trim());
        if (!root.isDirectory()) return;
        File sfw = new File(root, "SFW.jar");
        if (sfw.isFile()) append(sb, sfw.getAbsolutePath());
        addJars(sb, new File(root, "plugins" + File.separator + "lib"));
        addJars(sb, new File(root, "plugins" + File.separator + "exection"));
    }

    /** 缓存（一次运行内不变；探针要重算可以调 {@link #refreshClasspath()}）。 */
    private static volatile String cpCache = null;

    /** 丢掉缓存（改过目录内容后重算；探针用）。 */
    public static void refreshClasspath() { cpCache = null; }

    /** 某个类来自哪儿（CodeSource；拿不到就什么都不加）。 */
    private static void addClassSource(StringBuilder sb, Class<?> c) {
        try {
            java.security.CodeSource cs = c.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return;
            File f = new File(cs.getLocation().toURI());
            if (f.exists()) append(sb, f.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 框架根目录：由框架类（应用类加载器加载的 {@code SairCons}）的 CodeSource 推出。
     * {@code javaw -jar SFW.jar} 时它的位置就是 {@code <框架根>/SFW.jar}。
     */
    private static File frameworkRoot() {
        try {
            java.security.CodeSource cs = sair.sys.SairCons.class.getProtectionDomain().getCodeSource();
            if (cs == null || cs.getLocation() == null) return null;
            File f = new File(cs.getLocation().toURI());
            return f.isFile() ? f.getParentFile() : f;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把目录下的 jar 全加进来（排序保证结果稳定）。 */
    private static void addJars(StringBuilder sb, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".jar")) append(sb, f.getAbsolutePath());
        }
    }

    private static void append(StringBuilder sb, String cp) {
        if (cp == null) return;
        for (String p : cp.split(File.pathSeparator)) {
            if (p == null || p.trim().isEmpty()) continue;
            String t = p.trim();
            if (sb.indexOf(t) >= 0) continue;
            if (sb.length() > 0) sb.append(File.pathSeparator);
            sb.append(t);
        }
    }

    /** 输出到内存的文件管理器。 */
    private static final class MemFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {

        private final Map<String, byte[]> out;
        private final Map<String, MemClassFile> files = new LinkedHashMap<String, MemClassFile>();

        MemFileManager(StandardJavaFileManager fm, Map<String, byte[]> out) {
            super(fm);
            this.out = out;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className,
                                                   JavaFileObject.Kind kind, FileObject sibling) {
            MemClassFile f = new MemClassFile(className, kind);
            files.put(className, f);
            out.put(className, new byte[0]);
            return f;
        }

        @Override
        public void close() throws java.io.IOException {
            for (Map.Entry<String, MemClassFile> e : files.entrySet()) {
                out.put(e.getKey(), e.getValue().bytes());
            }
            super.close();
        }
    }

    /** 内存里的 .class。 */
    private static final class MemClassFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream();

        MemClassFile(String className, Kind kind) {
            super(URI.create("mem:///" + className.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() { return bos; }

        byte[] bytes() { return bos.toByteArray(); }
    }
}
