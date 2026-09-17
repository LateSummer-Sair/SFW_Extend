package sair.v4.auth;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 资源描述 + <b>类别自动判定</b>（资源五类：A 本机 / B SFW / C 数据 / E 外部交互 / T 工具）。
 *
 * <h3>五类的权威定义（主人裁定 2026-09-15）</h3>
 * <ul>
 *   <li><b>A</b> = <b>本机：所有本机文件 + 内存中的所有进程</b>（含网络资源 —— 直接指向本机实体的那些）；</li>
 *   <li><b>B</b> = <b>SFW 运行时目录，仅文件</b>（运行实例属 A）；</li>
 *   <li><b>C</b> = <b>数据：数据库的全部内容 + {@code files} 目录</b>
 *       （{@code db:} / {@code mem:} + <b>本插件自己 dataDir 的 {@code files} 目录</b>，
 *       后者是 C 类里的<b>特例文件实体</b>）；</li>
 *   <li><b>E</b> = <b>外部交互</b>：NapCat 全部输入输出动作 + 向 SFW 发送命令及 SFW 的输出
 *       （{@link #platform(String)}）；</li>
 *   <li><b>T</b> = <b>工具</b>：只有<b>"入口"受权限管控</b>（{@code R} 看工具 / {@code W} 改·注册工具 /
 *       {@code X} 执行），工具<b>执行过程不判位</b>（{@link #tool(String)}）。</li>
 * </ul>
 *
 * <h3>怎么自动判</h3>
 * <ul>
 *   <li>{@link #proc(String)}：<b>永远是 A</b>（"本机程序 / 进程"资源，与它落在哪无关）；</li>
 *   <li>{@link #path(String)}：路径规范化（真身路径）之后
 *       <b>落在 {@code <dataDir>/files} 之下 = C</b>，否则<b>在 SFW 根之下 = B</b>，再否则 = <b>A</b>；</li>
 *   <li>{@link #db(String)} / {@link #mem(String)} = <b>C</b>（数据侧）；
 *       {@link #tool(String)} = <b>T</b>（工具入口）；</li>
 *   <li>{@link #platform(String)} = <b>E</b>（外部交互）；网络动作归 <b>A</b>（见上，A 类定义涵盖网络资源）；</li>
 *   <li>{@link #all()} = 通配（没有单一类别，判定时非主人一律 {@code NONE}，见 {@code Acl}）。</li>
 * </ul>
 *
 * <h3>SFW 根从哪来（两个基准，取更严的那个解释）</h3>
 * <ol>
 *   <li><b>主基准</b> = 框架代码自己的 CodeSource（{@code sair.Main} 等框架类的
 *       {@code getProtectionDomain().getCodeSource().getLocation()} → {@code <fw>\SFW.jar} → 取父目录）；</li>
 *   <li><b>兜底</b> = {@code user.dir}（CodeSource 取不到时才用）；</li>
 *   <li><b>保守规则</b>：两个基准都可用且不一致时，只有<b>同时落在两个根之下</b>才算 B，否则算 A。
 *       宁可把框架文件误判成 A（上限 {@code R}：功能少一点），也绝不把整机文件误判成 B（上限 {@code R}：放宽）。</li>
 * </ol>
 * <p>没有任何基准可用 → 一律 A（fail-strict）。{@link #roots(File, File)} 是给探针/装配用的显式基准。</p>
 *
 * <h3>插件数据根（D22：{@code <dataDir>/files} = C 类）</h3>
 * <p>主人"加强定义"：C 类里的"本插件自己的 dataDir"<b>只有 {@code files} 目录</b>这一块范围
 * （不是整个 dataDir —— {@code skills/**} 等仍是 B 类）。装配时用 {@link #dataDir(java.io.File)}
 * 把数据根接上（{@code null} = 撤销）；<b>没接线时行为与接线前一字不差</b>（{@code files} 照老规则判），
 * 未接线<b>不抛异常</b>。探针可用它显式模拟一个临时数据根（用完 {@code Res.dataDir(null)} 撤销）。</p>
 *
 * <h3>防逃逸</h3>
 * <p>匹配串 = 真身路径（{@code toRealPath()}：解开软链 / junction / 8.3 短名，叶子不存在时真实化最深的
 * 已存在祖先）→ 统一分隔符 {@code /} → 去尾斜杠 → 全小写。{@code .} / {@code ..} / 相对路径都被折掉，
 * 所以"从 SFW 里 {@code ..} 出去"或"SFW 里的 junction 指向外面"都判成 A。</p>
 * <p>规范化整条失败时（例如 {@code getCanonicalPath()} 抛异常）：退回绝对路径 + 手工折叠，
 * 并<b>强制判成 A</b>（更严的那一类），<b>绝不</b>判成 B。</p>
 */
public final class Res {

    /**
     * A 本机：<b>所有本机文件 + 内存中的所有进程</b>（主人裁定 2026-09-15）。
     * <p>含<b>网络资源</b> —— 那些直接指向本机实体的网络动作（按定义归 A 类）。
     * 一个<b>程序</b>无论落在哪，都算 A（见 {@link #proc(String)}）。</p>
     */
    public static final char A = 'A';
    /** B SFW：<b>SFW 运行时目录，仅文件</b>（运行实例属 A）；插件数据根里 {@code files/} 以外的部分（含 {@code skills/**}）也是它。 */
    public static final char B = 'B';
    /**
     * C 数据：<b>数据库的全部内容 + {@code files} 目录</b>（主人裁定 2026-09-15）——
     * 六库 / 内存状态（{@code db:} / {@code mem:}）+ <b>本插件数据根的 {@code files} 目录</b>
     * （{@code <dataDir>/files/**}，{@link #dataDir(java.io.File)}；它是 C 类里的<b>特例文件实体</b>，
     * 位一律服从 C，没有专有分配）。
     * <p><b>工具不在 C 里了</b>：{@code tool:} 归 {@link #T}。</p>
     */
    public static final char C = 'C';
    /** E 外部交互：NapCat 全部输入输出动作 + 向 SFW 发送命令与 SFW 的输出。 */
    public static final char E = 'E';
    /**
     * T 工具（主人裁定 2026-09-15）：<b>只有"入口"受权限管控</b> ——
     * {@code R} = 看工具（可见性）、{@code W} = 改 / 注册工具、{@code X} = 执行；
     * 工具<b>执行过程不判位</b>（碰到的每个资源各自再判）。
     * <p>工厂是 {@link #tool(String)}（匹配串前缀 {@code tool:}）。</p>
     */
    public static final char T = 'T';
    /** 通配（{@link #all()}）：没有单一类别。 */
    public static final char ALL_CLS = '*';

    /** 框架类的 CodeSource 候选（只用来推框架根，任何一个都行）。 */
    private static final String[] FW_CLASSES = {
            "sair.Main", "sair.SairLoader", "sair.SairBaseLoader", "sair.Pathes", "sair.Safe"
    };
    /** 找框架根时最多往上找几层（开发态从 classes 目录往上找 SFW.jar）。 */
    private static final int UP_LIMIT = 4;

    private final char cls;
    private final String target;
    /** 是不是"插件数据根的 files 树"（D22）：只有 {@link #path} 判到 {@code <dataDir>/files/**} 时为 true。 */
    private final boolean filesTree;

    private Res(char cls, String target) {
        this(cls, target, false);
    }

    private Res(char cls, String target, boolean filesTree) {
        this.cls = cls;
        this.target = target == null ? "" : target;
        this.filesTree = filesTree;
    }

    // ==================== 工厂 ====================

    /**
     * 路径资源：规范化 + 真身化 →
     * <b>落在 {@code <dataDir>/files} 之下 = C</b>（D22，数据根未接线时这条不生效）→
     * 否则<b>在 SFW 根之下 = B</b> → 再否则 = <b>A</b>。
     *
     * <p><b>C 那一档的匹配串就是规范化路径本身</b>（不是 {@code files/} 前缀之类的别名），
     * 便于诊断时一眼看出是哪个文件；代价是 {@link #isPath()} 对它为 {@code false} ——
     * C 类条目<b>按名字相等</b>匹配（沿用 {@code Acl.Entry#scopeHits} 的 C/E 口径），
     * 所以给 {@code files} 下某个文件写范围例外要写<b>整条规范化路径</b>；
     * 写 {@code C["User<QQ>","","RWX"]} / {@code C["User<QQ>","",""]} 则命中整个 C 类。
     * 这是有意为之（C 类按名字相等匹配范围）。</p>
     *
     * <p>真身化不可靠时（{@link #realOf} 走到兜底分支）仍判 <b>A</b>：A 类对非主人一位都不给，
     * 比 C 类（对 ALLUSER 是 {@code R}）更严 —— fail-strict，绝不因为"规范化没成功"把文件白送成 C。</p>
     *
     * <p><b>"files 树"标记</b>（{@link #isFilesTree()}）：判到 {@code <dataDir>/files/**} 的那些资源
     * 类别是 C，并带这个标记。主人裁定（2026-09-15）：<b>files 树<b>不再有</b>专有位，位一律服从 C</b>
     * —— 它只是 C 类里的一个特例文件实体。标记保留只为"范围按路径式命中"（{@code Acl.Entry#scopeHits}）
     * 与诊断，判定侧不再为它单开一档。</p>
     */
    public static Res path(String p) {
        String raw = p == null ? "" : p.trim();
        if (raw.isEmpty()) {
            // 空路径：没有可判定的目标 —— 按 A（更严的一类），匹配串为空（只会命中"整类"条目）
            return new Res(A, "");
        }
        Real r = realOf(new File(raw));
        // 数据根的 files 目录 = C 类里的特例文件实体（覆盖"在 SFW 根之下 = B"），位服从 C
        if (r.resolved && isUnderDataFiles(r.path)) return new Res(C, r.path, true);
        // 规范化不可靠（真身化全失败）→ 强制 A，绝不 B；可靠时才看"是否落在两个基准根之下"
        char c = (r.resolved && isUnderSfw(r.path)) ? B : A;
        return new Res(c, r.path);
    }

    /** 同 {@link #path(String)}。 */
    public static Res path(File f) { return path(f == null ? null : f.getPath()); }

    /**
     * <b>本机程序 / 进程：强制 A 类</b>（D22："A = SFW 路径以外的所有文件<b>以及系统进程</b>"）。
     *
     * <p>语义 = "本机程序 / 进程"资源（{@code what} = 程序名 / 路径 / 进程标识）。
     * {@code what} 照 {@link #path} 的<b>同一套规范化</b>（真身化 → 小写 → {@code /} 分隔）取匹配串；
     * 真身化取不到时退回规范化原串（{@link #norm(String)}）；空串 = A 类 + 空匹配串
     * （只命中"整类"条目）。<b>唯一与 {@code path} 不同的是：不看它落在哪 —— 永远是 A。</b></p>
     *
     * <p><b>为什么不直接用 {@link #path}</b>：{@code path} 的分类是"落在 SFW 根之下 = B，否则 = A"，
     * 那是给"文件资源"用的正确尺子；可<b>程序</b>不能这么判 —— 一个<b>相对路径</b>
     * （例如 {@code %ComSpec%} 缺失时 {@code exec} 回落到的 {@code new File("cmd.exe")}）
     * 会被归一化到进程工作目录（框架根）下，于是被判成 <b>B 类</b>；B 类曾经对 {@code ALLUSER}
     * 含 {@code X}（{@code RX}）⇒ 非主人白拿"执行任意系统命令"的位（现在 B 类只剩 {@code R}、
     * 不含 {@code X}，但"程序 = A 类"这条口径不因此松动）。
     * 所以凡是"要执行的本机程序"，一律按 A 类判：A 类对 {@code ALLUSER} 是一位都不给、
     * {@code SYSTEM} 只有 {@code R}，两者都拿不到执行位，只有主人（MASTER 恒全权）能起进程。</p>
     *
     * <p>{@link #exec(String)} 与它<b>等价</b>（"执行一个程序" = 起一个进程），保留老名字只为兼容既有调用点。</p>
     */
    public static Res proc(String what) {
        String raw = what == null ? "" : what.trim();
        if (raw.isEmpty()) return new Res(A, "");
        Real r = realOf(new File(raw));
        String t = r.path.isEmpty() ? norm(raw) : r.path;
        return new Res(A, t);
    }

    /** 同 {@link #proc(String)}（{@link File} 版）。 */
    public static Res proc(File f) { return proc(f == null ? null : f.getPath()); }

    /**
     * 同 {@link #proc(String)}：<b>执行的本机程序 = 起一个进程 = A 类</b>（D10/D22）。
     *
     * <p>签名与语义<b>一字未变</b>（既有调用点例如 {@code DynCode.denyExec()} 照用），
     * 现在只是委托到 {@link #proc(String)} —— 名字从"要执行"换成"程序 / 进程"，
     * 让"A 类 = 本机文件 + 系统进程"这条定义在调用点一眼可见。</p>
     */
    public static Res exec(String p) { return proc(p); }

    /** 同 {@link #exec(String)}（{@link File} 版）。 */
    public static Res exec(File f) { return proc(f); }

    /**
     * C 类：数据侧的一个库（六库 / kv / favor / task / alarm / skill_index）。
     * <p>{@link #mem(String)} 与它同类；<b>工具</b>不在这里（见 {@link #tool(String)} = {@link #T}）。</p>
     */
    public static Res db(String lib) { return named(C, "", lib); }

    /** C 类：运行期内存状态（{@code mem:acl} = 授权动作本身，受保护）。 */
    public static Res mem(String what) { return named(C, "mem:", what); }

    /**
     * <b>T 类：一把工具的"入口"</b>（主人裁定 2026-09-15）—— {@code R} 看工具 / {@code W} 改·注册 /
     * {@code X} 执行。
     * <p>匹配串 = {@code tool:<工具名>}（小写）；判定只发生在<b>入口</b>：执行过程里碰到的每个资源
     * 各自按自己的类判，不拿本函数的结果兜。</p>
     */
    public static Res tool(String name) { return named(T, "tool:", name); }

    /** E 类：外部交互动作 —— NapCat 全部输入输出 + 向 SFW 发送命令（{@code get_cookies} 等，受保护清单见 {@code Acl}）。 */
    public static Res platform(String action) { return named(E, "", action); }

    /** 通配资源（对应账本里"该类全部"之外的{@code *}语义）：非主人一律 {@code NONE}。 */
    public static Res all() { return new Res(ALL_CLS, "*"); }

    private static Res named(char cls, String prefix, String v) {
        return new Res(cls, prefix + normName(v));
    }

    // ==================== 读 ====================

    /** 类别：{@link #A} / {@link #B} / {@link #C} / {@link #E} / {@link #T} / {@link #ALL_CLS}。 */
    public char cls() { return cls; }

    /** 规范化匹配串（判定与账本条目都拿它比对）。 */
    public String target() { return target; }

    /**
     * 是不是文件系统路径资源（A / B）。
     * <p><b>注意</b>：{@link #path(String)} 判成 C 的那一档（{@code <dataDir>/files/**}）在这里是
     * {@code false} —— C 类按名字相等匹配范围（D22 有意的后果），不是"按路径包含"匹配。</p>
     */
    public boolean isPath() { return cls == A || cls == B; }

    /**
     * 是不是<b>插件数据根的 files 树</b>：只有 {@link #path} 判到 {@code <dataDir>/files/**} 的那一档
     * 为 {@code true}（类别是 C，匹配串是规范化路径本身）。
     *
     * <p><b>按主人裁定（2026-09-15）：files 树不再有专有位，位一律服从 C</b> ——
     * {@code files} 是 C 类里的特例文件实体（ALLUSER = {@code R}、SYSTEM = {@code RWX}，
     * 与通用 C 类一字不差）。这个标记只服务两件事：① {@code Acl.Entry#scopeHits} 让
     * {@code C["User<QQ>","<数据根>/files","RWX"]} 这类<b>路径式范围</b>能命中它下面的文件；
     * ② 诊断（{@code Acl.source} 说得出"这是 files 目录"）。
     * 其余任何资源（含 {@code db:} / {@code mem:} / {@code tool:} / A 类 / B 类）都是 {@code false}。</p>
     */
    public boolean isFilesTree() { return filesTree; }

    /** 类别的人话名（回执 / 日志用）。 */
    public String clsName() {
        switch (cls) {
            case A: return "本机";
            case B: return "SFW";
            case C: return "数据";
            case E: return "外部交互";
            case T: return "工具";
            default: return "通配";
        }
    }

    /** 可读形式：{@code "A:d:/share/x.txt"}。 */
    @Override
    public String toString() {
        if (cls == ALL_CLS) return "*";
        return cls + ":" + (target.isEmpty() ? "(空)" : target);
    }

    // ==================== 基准根 ====================

    private static final Object LOCK = new Object();
    private static volatile boolean inited;
    private static volatile File rCode;
    private static volatile File rCwd;
    private static volatile String tCode;
    private static volatile String tCwd;

    /** 主基准（框架根）：CodeSource 推出来的那个；推不出来返回 {@code null}。 */
    public static File codeRoot() { ensure(); return rCode; }

    /** 兜底基准（{@code user.dir}）：取不到返回 {@code null}。 */
    public static File cwdRoot() { ensure(); return rCwd; }

    /** 生效的主基准（codeRoot 优先，其次 cwd）：诊断 / 兼容用。 */
    public static File root() { ensure(); return rCode != null ? rCode : rCwd; }

    /**
     * <b>测试 / 装配用</b>：显式指定两个基准根（{@code null} = 该基准不可用）。
     * <p>正常运行时不要调它 —— 判定口径与线上必须一致，探针只在需要模拟布局时调。</p>
     */
    public static void roots(File codeRoot, File cwdRoot) {
        synchronized (LOCK) {
            rCode = codeRoot;
            rCwd = cwdRoot;
            tCode = rCode == null ? null : realOf(rCode).path;
            tCwd = rCwd == null ? null : realOf(rCwd).path;
            inited = true;
        }
    }

    /** 交回自动探测（撤销 {@link #roots(File, File)}）。 */
    public static void resetRoots() {
        synchronized (LOCK) {
            inited = false;
            rCode = null;
            rCwd = null;
            tCode = null;
            tCwd = null;
        }
    }

    // ==================== 插件数据根（D22：<dataDir>/files = C 类） ====================

    /** {@code files} 目录名（数据根下，与 {@code skills} / {@code prompts} 并列）。 */
    public static final String DATA_FILES_DIR = "files";

    private static volatile File rData;
    private static volatile String tDataFiles;

    /**
     * <b>接线插件数据根</b>：接上之后，落在 {@code <root>/files} 之下（含该目录本身）的路径
     * 在 {@link #path(String)} 里判 <b>C 类</b>（C 类里的特例文件实体，位服从 C）；{@code null} = 撤销接线。
     *
     * <p><b>只影响 {@code files} 这一块范围</b>：{@code <root>/skills/**} 等仍是 B 类，
     * {@code prompts/**}、{@code config.json} 等仍是 B 类里的第二层受保护资源（口径不变）。</p>
     *
     * <p><b>没接线时行为与接线前完全一致</b>（{@code files} 照老规则判 B/A），
     * 传入 {@code null} / 取不到真身都<b>不抛异常</b>（退化成"未接线"）。</p>
     *
     * <p>装配处：{@code Boot} 装配第①步（数据根建好之后）调 {@code Res.dataDir(conf.root())}；
     * 探针/测试可显式设一个临时根模拟布局，用完 <b>务必</b> {@code Res.dataDir(null)} 撤销
     * （它是进程级静态状态，撤销前会一直影响判定）。</p>
     */
    public static void dataDir(File root) {
        synchronized (LOCK) {
            rData = root;
            String t = null;
            if (root != null) {
                try {
                    t = realOf(new File(root, DATA_FILES_DIR)).path;
                } catch (Throwable ignore) {
                    t = null;   // 取不到就当没接线（绝不抛）
                }
            }
            tDataFiles = (t == null || t.isEmpty()) ? null : t;
        }
    }

    /** 当前接线的插件数据根；没接线 = {@code null}。 */
    public static File dataDir() { return rData; }

    /** 当前生效的 {@code <dataDir>/files} 规范化匹配串；没接线 = {@code ""}（诊断用）。 */
    public static String dataFilesTarget() { String t = tDataFiles; return t == null ? "" : t; }

    /** 是不是落在接线的 {@code <dataDir>/files} 之下（含它自己）；没接线恒 false。 */
    private static boolean isUnderDataFiles(String target) {
        String d = tDataFiles;          // 只读一次快照（并发下也不会半读）
        return d != null && under(target, d);
    }

    private static void ensure() {
        if (inited) return;
        synchronized (LOCK) {
            if (inited) return;
            rCode = detectCodeRoot();
            rCwd = detectCwdRoot();
            tCode = rCode == null ? null : realOf(rCode).path;
            tCwd = rCwd == null ? null : realOf(rCwd).path;
            inited = true;
        }
    }

    /**
     * 是不是落在 SFW 根之下：两个可用基准<b>都要</b>满足（保守）；一个都没有 → false（算 A）。
     */
    private static boolean isUnderSfw(String target) {
        ensure();
        boolean any = false;
        if (tCode != null) { any = true; if (!under(target, tCode)) return false; }
        if (tCwd != null) { any = true; if (!under(target, tCwd)) return false; }
        return any;
    }

    /** 框架根：框架类的 CodeSource（jar → 父目录；目录 → 往上找 SWF.jar 所在层）。 */
    private static File detectCodeRoot() {
        for (int i = 0; i < FW_CLASSES.length; i++) {
            try {
                Class<?> k = Class.forName(FW_CLASSES[i], false, Res.class.getClassLoader());
                java.security.CodeSource cs = k.getProtectionDomain().getCodeSource();
                if (cs == null || cs.getLocation() == null) continue;
                File loc = new File(cs.getLocation().toURI());
                if (loc.isFile()) {
                    File p = loc.getParentFile();
                    if (p != null) return p;
                } else if (loc.isDirectory()) {
                    File hit = findFwFrom(loc);
                    if (hit != null) return hit;
                }
            } catch (Throwable ignore) {
                // 换下一个候选
            }
        }
        return null;
    }

    /** 从某个目录往上找"里面有 SFW.jar"的那一层。 */
    private static File findFwFrom(File from) {
        File d = from;
        for (int i = 0; i < UP_LIMIT && d != null; i++) {
            if (new File(d, "SFW.jar").isFile()) return d;
            d = d.getParentFile();
        }
        return null;
    }

    private static File detectCwdRoot() {
        try {
            String d = System.getProperty("user.dir");
            if (d == null || d.trim().isEmpty()) return null;
            return new File(d.trim());
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 规范化 ====================

    /** 名字类资源（库 / 动作 / 工具）的规范化：去空白 + 小写。 */
    public static String normName(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 路径 → 规范化匹配串（真身路径 + {@code /} 分隔 + 去尾斜杠 + 小写）。
     * <p>账本里的路径范围也走这个函数，保证两边同一把尺子。</p>
     */
    public static String normPath(String p) {
        String raw = p == null ? "" : p.trim();
        if (raw.isEmpty()) return "";
        return realOf(new File(raw)).path;
    }

    /** {@code target}（或它自己）是不是在 {@code dir} 之下。都按规范化匹配串比较。 */
    public static boolean under(String target, String dir) {
        if (target == null || dir == null) return false;
        if (target.isEmpty() || dir.isEmpty()) return false;
        if (target.equals(dir)) return true;
        String d = dir.endsWith("/") ? dir : (dir + "/");
        return target.startsWith(d);
    }

    /** 真身化结果：{@code path} = 规范化匹配串，{@code resolved} = 是否拿到了可靠的真身。 */
    private static final class Real {
        final String path;
        final boolean resolved;
        Real(String path, boolean resolved) { this.path = path; this.resolved = resolved; }
    }

    /**
     * 真身化：① 存在的路径 → {@code toRealPath()}（解软链 / junction / 短名）；
     * ② 叶子不存在 → 真实化最深的已存在祖先，再接回剩下的段；
     * ③ 都不行 → {@code getCanonicalPath()}；④ 连它都抛 → 绝对路径 + 手工折叠，并标记"不可靠"。
     */
    private static Real realOf(File f) {
        File abs = f.getAbsoluteFile();
        // ① 整条路径真实化
        if (abs.exists()) {
            try {
                return new Real(norm(abs.toPath().toRealPath().toString()), true);
            } catch (Throwable ignore) {
            }
        }
        // ② 叶子（或整段）不存在：真实化最深的已存在祖先
        try {
            List<String> tail = new ArrayList<String>();
            File p = abs;
            while (p != null && !p.exists()) {
                String n = p.getName();
                if (n != null && !n.isEmpty()) tail.add(0, n);
                File up = p.getParentFile();
                if (up == null || up.equals(p)) break;
                p = up;
            }
            if (p != null && p.exists()) {
                StringBuilder sb = new StringBuilder(p.toPath().toRealPath().toString());
                for (int i = 0; i < tail.size(); i++) sb.append('/').append(tail.get(i));
                return new Real(norm(sb.toString()), true);
            }
        } catch (Throwable ignore) {
        }
        // ③ canonical（不保证解开软链，但至少折掉 . 与 ..）
        try {
            return new Real(norm(abs.getCanonicalPath()), true);
        } catch (Throwable ignore) {
        }
        // ④ 兜底：绝对路径 + 手工折叠 —— 不可靠，判定侧必须按 A 处理
        return new Real(norm(lexical(abs.getAbsolutePath())), false);
    }

    /** 规范化匹配串：统一 {@code /}、去尾斜杠（根除外）、全小写。 */
    private static String norm(String abs) {
        String s = abs == null ? "" : abs.trim().replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.ROOT);
    }

    /** 手工折叠 {@code .} 与 {@code ..}（只在真身化全失败时用）。 */
    private static String lexical(String abs) {
        String s = abs == null ? "" : abs.replace('\\', '/').trim();
        String prefix = "";
        if (s.length() >= 2 && s.charAt(1) == ':') { prefix = s.substring(0, 2); s = s.substring(2); }
        else if (s.startsWith("//")) { prefix = "//"; s = s.substring(2); }
        else if (s.startsWith("/")) { prefix = "/"; s = s.substring(1); }
        List<String> out = new ArrayList<String>();
        for (String seg : Arrays.asList(s.split("/"))) {
            if (seg.isEmpty() || ".".equals(seg)) continue;
            if ("..".equals(seg)) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
                continue;
            }
            out.add(seg);
        }
        StringBuilder sb = new StringBuilder(prefix);
        for (int i = 0; i < out.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(out.get(i));
        }
        if (sb.length() == 0) sb.append('/');
        return sb.toString();
    }
}
