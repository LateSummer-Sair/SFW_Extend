package sair.v4.hot;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.v4.Conf;
import sair.v4.auth.Caller;
import sair.v4.ctx.Ctx;
import sair.v4.kit.Fs;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.kit.Text;

/**
 * 动态执行（基板②的"执行"半边）。
 * <p>两件事：①把一段 Java 源码编译并在宿主进程里跑起来（临时目录里编译，产物即抛）；
 * ②执行系统命令并取回输出。这是"可以调度系统已有资源"的入口，权限上由技能管控表按动作判：
 * {@code exec.java} / {@code exec.cmd}（动作名 = exec 工具的 {@code kind} 取值）——
 * 表里没写 = 未授权；主人与她本人恒全权。</p>
 */
public final class DynCode {

    /** 命令输出的字节上限（防止一个刷屏命令把内存吃掉；旧口径是按字符 200000）。 */
    private static final int MAX_OUT_BYTES = 400000;

    private static final Pattern CLASS_PAT = Pattern.compile(
            "(?:public\\s+)?(?:final\\s+|abstract\\s+)?(?:class|interface|enum)\\s+([A-Za-z_$][\\w$]*)");

    private final Conf conf;
    private final Out out;

    /**
     * 权限面（技能管控表）。装配期由 {@code Boot} 注入；{@code null} = 没有权限面
     * （探针直接 new 出来的实例 / 装配第④步失败），判定退到空管控表（只有主人与她本人放行）。
     */
    private volatile sair.v4.auth.Auth auth;

    public DynCode(Conf conf, Out out) {
        this.conf = conf;
        this.out = out;
    }

    /** 装权限面（{@code Boot} 装配第⑦步注入）；判定按 {@code exec.java} / {@code exec.cmd} 走。 */
    public void setAuth(sair.v4.auth.Auth a) { this.auth = a; }

    /** 编译并运行一段 Java 源码；返回输出或错误说明。 */
    public String runJava(String source, String[] args) {
        if (Str.blank(source)) return "缺少源码";
        String deny = denyOp("exec.java");
        if (deny != null) return deny;
        String className = classNameOf(source);
        if (className == null) return "源码里找不到类声明（class/interface/enum）";
        File dir = new File(conf.tmpDir(), "dyn/" + Str.stamp() + "-" + Math.abs(source.hashCode()));
        Fs.mkdirs(dir);
        File src = new File(dir, className + ".java");
        if (!Fs.write(src, source)) return "无法写入临时源码 " + src.getAbsolutePath();
        List<File> files = new ArrayList<File>();
        files.add(src);
        SkCompile.Result r = SkCompile.compile(files);
        if (!r.ok()) return "编译失败: " + Str.join(r.errors, " | ");
        try {
            ClassLoader cl = new SkClassLoader(r.classes, DynCode.class.getClassLoader());
            Class<?> cls = Class.forName(className, true, cl);
            // 优先 public static Object run(String[]) —— 能直接拿回结果
            try {
                java.lang.reflect.Method m = cls.getMethod("run", String[].class);
                Object v = m.invoke(null, (Object) (args == null ? new String[0] : args));
                return v == null ? "(无返回值)" : String.valueOf(v);
            } catch (NoSuchMethodException ignored) {
            }
            try {
                java.lang.reflect.Method m = cls.getMethod("main", String[].class);
                m.invoke(null, (Object) (args == null ? new String[0] : args));
                return "(已执行 main，输出见进程标准输出)";
            } catch (NoSuchMethodException ignored) {
            }
            return "类 " + className + " 没有 public static Object run(String[]) 或 main(String[])";
        } catch (Throwable t) {
            Throwable c = t.getCause() == null ? t : t.getCause();
            return "运行失败: " + c;
        } finally {
            Fs.deleteRec(dir);
        }
    }

    /**
     * 执行系统命令（Windows 走 cmd.exe /c，其它走 /bin/sh -c），返回 stdout+stderr。
     *
     * <p><b>输出解码</b>：命令行工具吐出来的是<b>平台原生编码</b>——简体中文 Windows 的
     * {@code cmd.exe} 是 GBK（代码页 936），Linux/macOS 是 UTF-8。所以这里不再硬当 UTF-8 解
     * （那会让 {@code echo 中文} 变成乱码），而是把整段字节交给基板唯一的解码口径
     * {@link Text#decodeNative}：BOM / 声明 / meta 有线索就按线索，无线索时按
     * {@link Text#platformDefault()}（Windows=GBK，其它=UTF-8）先试，再退回 UTF-8/GBK/GB18030。
     * 必须<b>先收字节再解码</b>（{@code InputStreamReader} 逐行读没有全局视野，做不了探测）。</p>
     */
    public String runCmd(String command, long timeoutMs) {
        if (Str.blank(command)) return "缺少命令";
        String deny = denyOp("exec.cmd");
        if (deny != null) return deny;
        ProcessBuilder pb = new ProcessBuilder();
        if (Text.isWindows()) {
            pb.command("cmd.exe", "/c", command);
        } else {
            pb.command("/bin/sh", "-c", command);
        }
        pb.directory(conf.root());
        pb.redirectErrorStream(true);
        Process p = null;
        try {
            p = pb.start();
            final Process fp = p;
            final java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            Thread reader = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        InputStream in = fp.getInputStream();
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            if (bos.size() >= MAX_OUT_BYTES) break;
                            bos.write(buf, 0, Math.min(n, MAX_OUT_BYTES - bos.size()));
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(Math.max(1000, timeoutMs), java.util.concurrent.TimeUnit.MILLISECONDS);
            String text = decode(bos.toByteArray());
            if (!done) {
                p.destroy();
                return "命令超时（" + timeoutMs + "ms）：" + Str.cut(text, 2000);
            }
            reader.join(2000);
            text = decode(bos.toByteArray()).trim();
            return text.isEmpty() ? "(无输出, exit=" + p.exitValue() + ")" : text;
        } catch (Throwable t) {
            return "命令执行失败: " + t;
        } finally {
            if (p != null) try { p.destroy(); } catch (Exception ignored) {}
        }
    }

    /**
     * 执行前的 op 判定（放行返回 {@code null}，否则返回可直接回给模型的拒绝原文）。
     *
     * <p>op 名 = {@code exec.<动作名>}：{@link #runJava(String, String[])} 判 {@code exec.java}、
     * {@link #runCmd(String, long)} 判 {@code exec.cmd}（动作名就是 exec 工具的 {@code kind} 取值）。
     * 判定主体是当轮绑定的触发者（{@code Ctx.caller()}）；没有绑定主体 = 基板内部<b>直接</b>调用
     * （不经工具，例如探针用 {@code runCmd} 取命令输出做编码测试），按她本人 {@code SYSTEM} 处理
     * —— 恒放行。技能拿不到 {@code DynCode} 的直接引用，没有攻击者可触达的"无主体" exec 路径。</p>
     *
     * <p>权限面没装配时退到<b>空管控表</b>：主人与她本人恒全权、其余一律未授权 ——
     * 与"装配失败时不假装有权限面"的同一口径（fail-closed）。</p>
     */
    private String denyOp(String op) {
        Caller caller = Ctx.caller();
        if (caller == null) caller = Caller.systemActor(conf == null ? 0L : conf.masterQQ());
        sair.v4.auth.Auth a = auth;
        if (a != null) return a.allow(caller, op);
        return sair.v4.auth.Acl.empty(null).allow(caller, op);
    }

    /** 命令输出字节 → 文本（平台原生口径；CRLF 归一成 LF，与逐行读的旧行为一致）。 */
    private static String decode(byte[] raw) {
        return Text.decodeNative(raw, null).text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 从源码里取类名。 */
    public static String classNameOf(String source) {
        if (source == null) return null;
        String s = source.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("//[^\n]*", " ");
        Matcher m = CLASS_PAT.matcher(s);
        // 取第一个 public 的；否则第一个
        String first = null;
        while (m.find()) {
            String name = m.group(1);
            if (first == null) first = name;
            int start = Math.max(0, m.start() - 40);
            if (s.substring(start, m.start()).contains("public")) return name;
        }
        return first;
    }

    /** 编译期可用的 classpath（技能/动态代码共用）。 */
    public static String classpath() { return SkCompile.substrateClasspath(); }

    /** 供诊断。 */
    public Map<String, String> info() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        m.put("compiler", javax.tools.ToolProvider.getSystemJavaCompiler() == null ? "不可用" : "可用");
        m.put("tmp", conf.tmpDir().getAbsolutePath());
        return m;
    }
}
