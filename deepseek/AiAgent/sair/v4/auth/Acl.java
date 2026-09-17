package sair.v4.auth;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;

/**
 * 权限账本 = {@code perms.json}（<b>主人的例外授权清单</b>）+ 唯一一条判定：
 * <b>按类默认分配 + 例外突破默认</b>。
 *
 * <h3>账本形状（version 3，一条一行、人能直接读写）</h3>
 * <pre>
 * {
 *   "version": 3,
 *   "entries": [
 *     "A[\"Group123456\",\"D:/share\",\"RWX\"]",
 *     "A[\"User987654\",\"D:/share\",\"R\"]",
 *     "A[\"ALLUSER\",\"\",\"\"]",
 *     "C[\"User123456\",\"grouplog\",\"R\"]"
 *   ]
 * }
 * </pre>
 * <p>{@code "default"} 字段<b>已废弃</b>：默认分配按资源类型（{@link #defaultAlloc(char)}），
 * 读到时忽略并告警一次（见 {@link #read()}）。</p>
 * <pre>
 * 条目 := 类 '[' '"' 主体 '"' ',' '"' 范围 '"' ',' '"' 位 '"' ']'
 * 类   := 字母（A/B/C/E/T；将来加 N 也认，认不出的类不会命中任何资源）
 * 主体 := User&lt;QQ号&gt; | Group&lt;群号&gt; | SYSTEM | ALLUSER   （裸 QQ 号 = User&lt;QQ号&gt; 的便捷写法）
 *         MASTER <b>不接受</b>：主人恒全权，不需要例外
 * 范围 := A/B 类 → 路径（文件或目录，"" = 整个 A/B 类）
 *         C   类 → 库名 / mem:xxx / files 目录下的路径（"" = 整个 C 类）
 *         E   类 → 动作名（"" = 整个 E 类）
 *         T   类 → 工具名（可省 tool: 前缀，"" = 整个 T 类）
 * 位   := ""（一位都没有 = <b>显式拒绝</b>）| R | W | X | RW | RX | WX | RWX
 * </pre>
 * <p><b>条目字符串原样保留、原样打印</b>（{@link Entry#raw()}）；{@link #list()} 给出
 * "层级 + 原始条目 + 例外命中时生效位"三列，并分别标出默认分配与例外。</p>
 *
 * <h3>判定（唯一一条）</h3>
 * <pre>
 * DEFAULT_BITS = { A:"",   B:"",  C:"R",   E:"",  T:"" }     # 按类默认分配（ALLUSER，一条例外都没命中时）
 * SYSTEM_BITS  = { A:"R",  B:"RW", C:"RWX", E:"RWX", T:"RWX" }  # 她自己的默认分配
 *
 * bits_of(caller, resource):
 *     MASTER                     -&gt; "RWX"            # 恒全权，任何条目都不影响他
 *     第一层受保护资源            -&gt; ""               # 账本/授权动作/凭据：连她也不给（例外也不开）
 *     第二层受保护资源 且 ALLUSER -&gt; ""               # 提示词/配置：她照常，外人恒空（例外也不开）
 *     命中 = 最高层级那条命中条目（见下）            # 例外直接生效，不再与默认分配取交集
 *     命中 != null               -&gt; 命中
 *     caller == SYSTEM           -&gt; SYSTEM_BITS[类]
 *     else                       -&gt; DEFAULT_BITS[类]
 * </pre>
 * <p><b>A 类默认一位都不给（主人 2026-09-15 定标：「A 类资源默认 ALLUSER 不允许 R，RWX 全部收回」）</b>：
 * {@code DEFAULT_BITS[A] == NONE}。理由：A 类 = <b>SFW 数据根之外的任意本机磁盘路径</b>
 * （整块硬盘、系统目录、别人的工程、任何程序，含网络资源），普通用户与她没有半点业务关系，
 * 默认连"读"都不该有 —— 读走一个文件就可能带走凭据或隐私，所以 R 也一并收回，
 * 而不是"只把写和执行收回、读留着"。{@code SYSTEM}（她自己的自主行为）的 A 类<b>仍是 {@code R}</b>
 * （{@link #SYS_A}），不动。</p>
 * <p><b>五类资源的最终默认位表（主人裁定 2026-09-15，逐位对齐）</b>：
 * <table border="1">
 *   <tr><th>主体</th><th>A 本机</th><th>B SFW</th><th>C 数据</th><th>E 外部交互</th><th>T 工具</th></tr>
 *   <tr><td>MASTER</td><td colspan="5">ROOT（全权，恒定）</td></tr>
 *   <tr><td>SYSTEM（她本人 / 自主）</td><td>{@code R}</td><td>{@code RW}</td><td>{@code RWX}</td>
 *       <td>{@code RWX}</td><td>{@code RWX}</td></tr>
 *   <tr><td>ALLUSER</td><td>无</td><td>无</td><td>{@code R}</td><td>无</td><td>无</td></tr>
 * </table>
 * 即：{@link #DEF_B} 由 {@code R} 收到 {@code NONE}（B 对非主人无位）、{@link #DEF_C} 由 {@code RWX}
 * 收到 {@code R}（数据库与 {@code files} 只给读）、{@link #DEF_T} 新设 = {@code NONE}（非主人摸不到工具入口）、
 * {@link #SYS_E} 由 {@code X} 扩到 {@code RWX}、新增 {@link #SYS_T} = {@code RWX}。
 * 要按人放开，唯一通道仍是<b>例外条目</b>（例外照旧突破默认，见 {@link #bitsOf(Caller, Res)}）：
 * 整块给读 = {@code C["User<QQ>","","R"]}；只给某个范围 = {@code A["User<QQ>","D:/share","RWX"]}
 * （范围外照样一位都没有）；全体用户一起放开 = {@code A["ALLUSER","","R"]}。</p>
 * <p><b>{@code files} 树没有专有位</b>：{@code <数据根>/files/**} 是 C 类里的<b>特例文件实体</b>，
 * 位一律服从 C（{@link Res#isFilesTree()} 只用来让路径式范围命中它，判定侧不为它单开一档）。</p>
 * <p><b>主体层级</b>（主人 2026-09-15 追加定标）：{@code User<QQ>} 3 &gt; {@code Group<群号>} 2 &gt;
 * {@code ALLUSER} 1；{@code SYSTEM} 自成一路（只对 {@code Caller.Kind.SYSTEM} 生效，层级 3）。</p>
 * <ol>
 *   <li>候选 = 该类 + 主体层级 &gt; 0 + 范围命中 的条目；</li>
 *   <li><b>最高层级的那条说了算</b>（User 条目哪怕写在 Group 条目前面也照样赢）；</li>
 *   <li>同一层级内 → <b>后写的覆盖先写的</b>（{@code >= bestTier} 天然做到）；</li>
 *   <li><b>范围的具体度不参与优先级</b> —— 只有"主体层级 + 行序"两条规则（旧的"最具体优先"已删）。</li>
 * </ol>
 * <p>{@code ALLUSER} = 全部"用户"主体（{@code User<QQ>} 与 {@code Group<群号>}），
 * <b>不含 MASTER、也不含 SYSTEM</b>（{@code ALLUSER} 连她自己也盖的话，一句
 * {@code A["ALLUSER","",""]} 会把她的自主行为整个打死）。</p>
 *
 * <h3>受保护资源（两层，见 {@link #masterOnlyRes(Res)} / {@link #outsiderBlockedRes(Res)}）</h3>
 * <table border="1">
 *   <tr><th>层</th><th>内容</th><th>MASTER</th><th>SYSTEM（她自己）</th><th>ALLUSER</th></tr>
 *   <tr><td>第一层（只有主人）</td><td>账本自身（{@code perms.json} + {@code .tmp}）、
 *       {@code mem:acl}（授权动作本身）、{@code get_cookies} 一类账号凭据</td>
 *       <td>RWX</td><td><b>∅（连她也不给）</b></td><td>∅</td></tr>
 *   <tr><td>第二层（她自己的东西，只是不给外人）</td><td>{@code <数据根>/prompts/**}（身份/系统提示词）、
 *       {@code config.json}、{@code debug-port.json}（含 token），
 *       以及<b>按扩展名认定的受限配置文件与脚本</b>（后缀清单见 {@link #PROTECTED_EXTS}，
 *       只对 B 类路径，见 {@link #protectedExtRes(Res)}）</td>
 *       <td>RWX</td><td>按类默认（B 类 {@code RW}）</td><td><b>∅</b></td></tr>
 * </table>
 * <p>两层<b>例外都不开</b>。第一层单列的理由：她若能通过工具碰账本/授权动作，任何"诱导她自己改权限"
 * 的路径都能绕过主人。第二层"按扩展名"那一档的理由：{@code .json} 里是 token 与配置、
 * {@code .bat}/{@code .cmd} 是能改本机状态的脚本、{@code .ir} 是框架启动脚本（主人原话"绝对不可泄露"），
 * 这些东西给普通用户读一眼就等于把主人的机位交出去 —— 而她（{@code SYSTEM}）与主人照常。</p>
 */
public final class Acl {

    /** 账本文件名（数据根下，与 skills 目录并列）。 */
    public static final String FILE_NAME = "perms.json";
    /** 账本格式版本。 */
    public static final int VERSION = 3;
    /** 拒绝文案前缀。 */
    public static final String DENY_PREFIX = "[权限阻断] ";
    /** 主体前缀。 */
    public static final String USER_PREFIX = "User";
    /** 群主体前缀。 */
    public static final String GROUP_PREFIX = "Group";
    /** 她自己的主体键（{@code SYSTEM} 条目只对 {@code Kind.SYSTEM} 生效）。 */
    public static final String SYSTEM_KEY = "SYSTEM";
    /** 全体用户的主体键（{@code ALLUSER} 条目匹配任何 ALLUSER 调用者）。 */
    public static final String ALLUSER_KEY = "ALLUSER";

    /** 主体层级：{@code User<QQ>}（个人，跨群有效）。 */
    public static final int TIER_USER = 3;
    /** 主体层级：{@code Group<群号>}（只在该群会话生效）。 */
    public static final int TIER_GROUP = 2;
    /** 主体层级：{@code ALLUSER}（全体用户 = 最底层的底稿）。 */
    public static final int TIER_ALLUSER = 1;
    /** 不命中任何主体（含 MASTER 看任何条目）。 */
    public static final int TIER_NONE = 0;

    /**
     * 默认分配（ALLUSER，一条例外都没命中时）：<b>A 本机 = {@code NONE}（一位都不给）</b>。
     * <p>主人 2026-09-15 定标「A 类资源默认 ALLUSER 不允许 R，RWX 全部收回」：A 类 = SFW 数据根之外的
     * 任意本机磁盘路径，普通用户默认<b>连读都不许</b>（读走一个文件就可能带走凭据或隐私），
     * 所以 R/W/X 三位一起收回。要按人放开只能写例外条目：
     * {@code A["User<QQ>","","R"]}（整块给读）/ {@code A["User<QQ>","D:/share","RWX"]}（只给某个范围）
     * / {@code A["ALLUSER","","R"]}（全体用户）—— 例外照样突破默认。</p>
     */
    public static final int DEF_A = Bits.NONE;
    /**
     * 默认分配（ALLUSER，一条例外都没命中时）：<b>B SFW = {@code NONE}（一位都不给）</b>。
     * <p>主人裁定 2026-09-15 的最终三张位表：B 类（SFW 运行时目录里的文件，含数据根里那些
     * {@code .json} / {@code .bat} / {@code .ir} 配置与脚本）对非主人<b>一位都没有</b> ——
     * 原来那一档"只给读"（{@code R}）也一并收回。要按人 / 按范围放开，写例外条目即可
     * （例外照样突破默认）；这类受限配置文件与脚本本身还是<b>第二层受保护资源</b>
     * （见 {@link #protectedExtRes(Res)}），对 {@code ALLUSER} 连读都不给。</p>
     */
    public static final int DEF_B = Bits.NONE;
    /**
     * 默认分配：<b>C 数据 = {@code R}（只给读）</b> —— 主人裁定 2026-09-15。
     * <p>C 类 = 数据库的全部内容（六库 / 内存状态）+ 数据根的 {@code files} 目录。非主人对数据
     * <b>只读</b>：{@code W}（改库）与 {@code X} 都收回。{@code files} 树没有另设专有位 ——
     * 它是 C 类里的特例<b>文件实体</b>，位一律服从本表（见 {@link Res#isFilesTree()}）。</p>
     */
    public static final int DEF_C = Bits.R;
    /**
     * <b>默认分配：E 外部交互 = {@code NONE}</b>（一条例外都没命中时，非主人对外一位都没有）。
     * <p>NapCat 输入输出动作与"向 SFW 发命令"都算 E 类：默认不给，要放开得主人写例外
     * （{@code E["User<QQ>","send_group_msg","X"]}）。{@code SYSTEM} 那条线是 {@link #SYS_E}，
     * 与这里无关。</p>
     */
    public static final int DEF_E = Bits.NONE;
    /**
     * <b>默认分配：T 工具 = {@code NONE}</b>（主人裁定 2026-09-15）——
     * 非主人<b>摸不到任何工具入口</b>：{@code R}（看工具 = 可见性）/ {@code W}（改·注册）/ {@code X}（执行）
     * 一位都不给。工具执行过程不判位，判定只发生在入口（{@link Res#tool(String)} 的 T 类资源）。</p>
     */
    public static final int DEF_T = Bits.NONE;
    /** SYSTEM（她自己的自主行为）：A 本机 = R。 */
    public static final int SYS_A = Bits.R;
    /** SYSTEM：B SFW = RW。 */
    public static final int SYS_B = Bits.R | Bits.W;
    /** SYSTEM：C 数据 = RWX。 */
    public static final int SYS_C = Bits.ALL;
    /** SYSTEM：E 外部交互 = RWX（主人裁定 2026-09-15：由 {@code X} 扩到 {@code ALL}）。 */
    public static final int SYS_E = Bits.ALL;
    /** SYSTEM：T 工具 = RWX（看她自己的工具、改·注册工具、执行工具）。 */
    public static final int SYS_T = Bits.ALL;

    /** 受保护的平台动作。 */
    public static final String PROTECTED_PLATFORM = "get_cookies";
    /** 受保护的运行期资源（授权动作本身）。 */
    public static final String PROTECTED_MEM = "mem:acl";
    /** 受保护的提示词目录名（数据根下）。 */
    public static final String PROMPTS_DIR = "prompts";
    /** 受保护的配置文件（数据根下，含 token）。 */
    public static final String CONFIG_FILE = "config.json";
    /** 受保护的调试凭据（数据根下）。 */
    public static final String DEBUG_PORT_FILE = "debug-port.json";

    /**
     * 第二层「按扩展名」认定的<b>敏感后缀清单</b>（受限配置、脚本、密钥材料；<b>只对 B 类资源生效</b>，
     * 见 {@link #protectedExtRes(Res)}）：配置 {@code .json/.ini/.conf/.properties/.yml/.yaml/.toml}、
     * 脚本 {@code .bat/.cmd/.ir}、密钥与证书材料 {@code .xml/.env/.key/.pem/.p12}（主人 2026-09-15 定标
     * 「受限配置文件与 bat 脚本，ir 脚本绝对不可泄露」，同日裁定按建议扩充后 5 类）。
     * <p>匹配：<b>大小写不敏感</b>（{@code toLowerCase}）、<b>精确到后缀</b>（{@code endsWith}）——
     * {@code export.jsonl} <b>不</b>算 {@code .json}；<b>例外条目在这一档不开</b>（ALLUSER 恒 ∅）。</p>
     */
    public static final String[] PROTECTED_EXTS = {
        ".json", ".ini", ".conf", ".properties", ".yml", ".yaml", ".toml", ".bat", ".cmd", ".ir",
        ".xml", ".env", ".key", ".pem", ".p12"
    };

    // ==================== 条目 ====================

    /** 一条账本条目。{@link #raw()} 是<b>原样</b>的条目字符串，{@link #scope()} 是规范化后的匹配串。 */
    public static final class Entry {

        private final char cls;
        private final String principal;
        private final String scopeRaw;
        private final String scope;
        private final int mask;
        private final String raw;

        private Entry(char cls, String principal, String scopeRaw, String scope, int mask, String raw) {
            this.cls = cls;
            this.principal = principal;
            this.scopeRaw = scopeRaw;
            this.scope = scope;
            this.mask = mask;
            this.raw = raw;
        }

        /** 类别字母（原样大写）。 */
        public char cls() { return cls; }
        /** 主体（规范化：{@code User123456} / {@code Group123456} / {@code SYSTEM} / {@code ALLUSER}）。 */
        public String principal() { return principal; }
        /** 范围（原样文字，打印用）。 */
        public String scopeRaw() { return scopeRaw; }
        /** 范围（规范化匹配串）。 */
        public String scope() { return scope; }
        /** 声明的位掩码（{@link Bits#NONE} = 显式拒绝）。 */
        public int mask() { return mask; }
        /** 声明的位（条目写法：{@code NONE} 写成空串）。 */
        public String bits() { return entryBits(mask); }
        /** 原始条目字符串（原样保留、原样打印）。 */
        public String raw() { return raw; }
        /** 规范写法的条目字符串（去重 / 归一用）。 */
        public String canonical() { return format(cls, principal, scopeRaw, mask); }
        /** 是不是群主体。 */
        public boolean group() { return principal.startsWith(GROUP_PREFIX); }
        /** 是不是"全体用户"主体。 */
        public boolean allUser() { return ALLUSER_KEY.equals(principal); }
        /** 是不是"她自己"的主体。 */
        public boolean system() { return SYSTEM_KEY.equals(principal); }

        /**
         * 这条条目的主体对某个调用者的<b>层级</b>：{@code 0} = 不匹配。
         * <p>{@code User<QQ>} = 3（必须 qq 相同）&gt; {@code Group<群号>} = 2（必须当前群相同）
         * &gt; {@code ALLUSER} = 1；{@code SYSTEM} = 3（只对 {@code Kind.SYSTEM} 生效）。
         * MASTER 一律 0（主人恒全权，条目管不到他）。</p>
         */
        public int tier(Caller c) {
            if (c == null) return TIER_NONE;
            Caller.Kind k = c.kind();
            if (allUser()) return k == Caller.Kind.ALLUSER ? TIER_ALLUSER : TIER_NONE;
            if (system()) return k == Caller.Kind.SYSTEM ? TIER_USER : TIER_NONE;
            // User<QQ> / Group<群号>：只对"普通用户"这个身份生效（MASTER 与 SYSTEM 都不吃）
            if (k != Caller.Kind.ALLUSER) return TIER_NONE;
            if (principal.equals(USER_PREFIX + c.qq())) return TIER_USER;
            if (c.groupId() > 0 && principal.equals(GROUP_PREFIX + c.groupId())) return TIER_GROUP;
            return TIER_NONE;
        }

        /** 层级的人话名（诊断 / 显示用）。 */
        public String tierName() {
            if (allUser()) return "ALLUSER(1)";
            if (system()) return "SYSTEM(3)";
            if (group()) return "Group(2)";
            return "User(3)";
        }

        /**
         * 范围命中：{@code ""} 命中整类；<b>路径式资源</b>（A/B 类路径，以及 C 类里带
         * "files 树"标记的 {@code <数据根>/files/**}）按"相等或在其之下"（盘符 {@code d:} 也走这条）；
         * 其它类（C 的名字档 / E / T）按名字相等。<b>具体度不参与优先级</b>（只判命中与否）。
         *
         * <p><b>为什么 files 树也算路径式</b>：{@code Res.path(<数据根>/files/x.txt)} 的类别是 C、
         * {@link Res#isPath()} 是 {@code false}（它归 C 类），但它的匹配串<b>就是规范化路径</b>。
         * 若只认 {@code isPath()}，主人写的 {@code C["User<QQ>","<数据根>/files","RWX"]}
         * 就永远命不中它下面的文件 —— 授权静默失效。所以判据扩到
         * {@code r.isPath() || r.isFilesTree()}；<b>通用 C 类（库 / mem）仍按名字相等</b>，
         * 名字档条目落到 files 树上只会"不命中"，不会误命中。</p>
         */
        public boolean scopeHits(Res r) {
            if (r == null || cls != r.cls()) return false;
            if (scope.isEmpty()) return true;
            if (r.isPath() || r.isFilesTree()) return scope.equals(r.target()) || Res.under(r.target(), scope);
            return scope.equals(r.target());
        }

        /** 这条条目对某个调用者 + 某个资源是不是候选（层级 &gt; 0 且范围命中）。 */
        public boolean hits(Caller c, Res r) { return tier(c) > TIER_NONE && scopeHits(r); }

        @Override
        public String toString() { return raw; }
    }

    /** 一条被跳过的非法条目（绝不静默吞掉）。 */
    public static final class Reject {
        private final String raw;
        private final String reason;

        private Reject(String raw, String reason) { this.raw = raw; this.reason = reason; }

        /** 原始文字。 */
        public String raw() { return raw; }
        /** 为什么非法。 */
        public String reason() { return reason; }

        @Override
        public String toString() { return "\"" + raw + "\" —— " + reason; }
    }

    // ==================== 解析 / 格式化 ====================

    /**
     * 条目字符串 → 条目。<b>认不出来一律返回 {@code null}（绝不猜）</b>；
     * 想知道为什么，用 {@link #reason(String)}。
     */
    public static Entry parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() < 5) return null;
        char cls = Character.toUpperCase(s.charAt(0));
        if (!isLetter(cls)) return null;
        if (s.charAt(1) != '[' || !s.endsWith("]")) return null;
        String[] f = splitFields(s.substring(2, s.length() - 1));
        if (f == null || f.length != 3) return null;
        String principal = principalKey(f[0]);
        if (principal == null) return null;
        if (f[1].indexOf('"') >= 0) return null;
        String scope = scopeOf(cls, f[1]);
        if (scope == null) return null;
        int mask = bitsOfField(f[2]);
        if (mask == Bits.INVALID) return null;
        return new Entry(cls, principal, f[1].trim(), scope, mask, s);
    }

    /** 条目为什么非法；合法返回空串。与 {@link #parse(String)} 同一套判据。 */
    public static String reason(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "条目是空的";
        String s = raw.trim();
        if (s.length() < 5) return "太短，写不成 类[\"主体\",\"范围\",\"位\"]";
        char cls = Character.toUpperCase(s.charAt(0));
        if (!isLetter(cls)) return "开头不是类别字母（A/B/C/E/T…）";
        if (s.charAt(1) != '[') return "类别字母后面不是 '['";
        if (!s.endsWith("]")) return "不是以 ']' 结尾";
        String body = s.substring(2, s.length() - 1);
        String[] f = splitFields(body);
        if (f == null) return "中括号里要写三段 \"主体\",\"范围\",\"位\"（每段都用双引号包住）";
        if (f.length != 3) return "要三段（主体、范围、位），现在是 " + f.length + " 段";
        String pr = principalReason(f[0]);
        if (!pr.isEmpty()) return pr;
        if (f[1].indexOf('"') >= 0) return "范围里不能有双引号";
        if (scopeOf(cls, f[1]) == null) return "范围写法认不出";
        int m = bitsOfField(f[2]);
        if (m == Bits.INVALID) return "位只认 \"\" / " + Bits.hint() + "（现在是 \"" + f[2] + "\"）";
        return "";
    }

    /** 规范写法：{@code A["User123456","D:/share","RWX"]}。 */
    public static String format(char cls, String principal, String scope, int mask) {
        StringBuilder sb = new StringBuilder();
        sb.append(Character.toUpperCase(cls)).append("[\"")
          .append(principal == null ? "" : principal.trim()).append("\",\"")
          .append(scope == null ? "" : scope.trim()).append("\",\"")
          .append(entryBits(mask)).append("\"]");
        return sb.toString();
    }

    /** 条目里的位写法：{@code NONE} 写成空串（{@code ""} = 显式拒绝）。 */
    public static String entryBits(int mask) {
        return mask == Bits.NONE ? "" : Bits.format(mask);
    }

    /** 条目里的位字段 → 掩码；空串 = {@link Bits#NONE}；认不出 = {@link Bits#INVALID}。 */
    public static int bitsOfField(String f) {
        String s = f == null ? "" : f.trim();
        if (s.isEmpty()) return Bits.NONE;
        return Bits.parse(s);
    }

    /**
     * 主体写法 → 规范键：{@code User123456} / {@code Group123456} / {@code SYSTEM} / {@code ALLUSER}
     * （大小写不敏感）；裸数字 = {@code User<数字>}；认不出返回 {@code null}。
     * <p><b>{@code MASTER} 一律拒收</b>（主人恒全权，不需要例外）—— 想知道为什么用
     * {@link #principalReason(String)}。</p>
     */
    public static String principalKey(String p) {
        if (p == null) return null;
        String s = p.trim();
        if (s.isEmpty()) return null;
        String up = s.toUpperCase(Locale.ROOT);
        if (SYSTEM_KEY.equals(up)) return SYSTEM_KEY;
        if (ALLUSER_KEY.equals(up)) return ALLUSER_KEY;
        if ("MASTER".equals(up) || "ROOT".equals(up)) return null;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("user")) {
            String id = s.substring(4).trim();
            return id.isEmpty() ? null : (USER_PREFIX + id);
        }
        if (low.startsWith("group")) {
            String id = s.substring(5).trim();
            return id.isEmpty() ? null : (GROUP_PREFIX + id);
        }
        if (isDigits(s)) return USER_PREFIX + s;
        return null;
    }

    /** 主体写法为什么不能用；能用返回空串（{@link #principalKey(String)} 的"说得出理由"版）。 */
    public static String principalReason(String p) {
        if (p != null && !p.trim().isEmpty()) {
            String up = p.trim().toUpperCase(Locale.ROOT);
            if ("MASTER".equals(up) || "ROOT".equals(up)) {
                return "MASTER 恒全权，不需要例外（主体只写 User<QQ号> / Group<群号> / SYSTEM / ALLUSER）";
            }
        }
        return principalKey(p) != null ? ""
                : "主体要写 User<QQ号> / Group<群号> / SYSTEM / ALLUSER（现在是 \"" + (p == null ? "" : p) + "\"）";
    }

    /**
     * 范围写法 → 规范化匹配串：
     * A/B = 真身路径（{@code D:} / {@code D盘} = 盘符）；
     * C = <b>看写法</b>：像路径（盘符 / 含 {@code /} 或 {@code \}）就按 {@link Res#normPath(String)}
     * —— 与 A/B <b>同一把尺子</b>（真身路径、{@code /} 分隔、小写）；否则按名字（小写，{@code db:memory} 剥前缀）；
     * E = 名字（小写）；
     * T = 工具名（小写；<b>写不写 {@code tool:} 前缀都一样</b> —— 统一归一成
     * {@link Res#tool(String)} 的匹配串 {@code tool:<工具名>}，否则 {@code T["User<QQ>","agent","X"]}
     * 会在账本里存成 {@code "agent"} 而永远命不中 {@code tool:agent}，静默失效）；
     * 空串 = 整个该类；认不出返回 {@code null}。
     *
     * <p><b>为什么 C 要分两档</b>：C 类里既有"库名 / {@code mem:xxx}"这种<b>名字</b>，
     * 也有 {@code <数据根>/files/**} 这一档<b>路径</b>。
     * 旧版对 C 一律 {@code toLowerCase} 当名字处理 —— 于是
     * {@code C["User<QQ>","<数据根>\files","RWX"]} 的匹配串里<b>分隔符还是反斜杠、大小写也没归一</b>，
     * 永远对不上 {@code Res.path(...)} 给出的 {@code c:/…/files/x.txt}（静默失效：条目进得去、永远命不中）。
     * 现在按写法分流，两条路各自都用各自的规范化。</p>
     *
     * <p>{@code mem:acl} <b>不会被误判成路径</b>：它既没有分隔符、也不满足 {@link #driveOf(String)}
     * 的盘符形态（{@code m} 后面的 {@code em:acl} 不是 {@code :}），所以照旧走名字档 —— 行为不变。
     * {@code tool:xxx} 现在归 <b>T 类</b>（{@link Res#T}）；老写法 {@code C["User<QQ>","tool:xxx","X"]}
     * 里的那个 {@code tool:xxx} 只是 C 类里的一个名字，永远不会命中工具（工具是 T 类资源）。</p>
     */
    public static String scopeOf(char cls, String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        if (cls == Res.A || cls == Res.B) {
            String drive = driveOf(s);
            return drive != null ? drive : Res.normPath(s);
        }
        String low = s.toLowerCase(Locale.ROOT);
        if (cls == Res.C && low.startsWith("db:")) low = low.substring(3).trim();       // 别名 db:memory → memory
        if (cls == Res.C) {
            String drive = driveOf(low);
            if (drive != null) return drive;
            if (low.indexOf('/') >= 0 || low.indexOf('\\') >= 0) return Res.normPath(low);   // 路径式范围
        }
        if (cls == Res.E) {
            if (low.startsWith("platform:")) low = low.substring(9).trim();
            else if (low.startsWith("napcat:")) low = low.substring(7).trim();
            else if (low.startsWith("napcat.")) low = low.substring(7).trim();
        }
        if (cls == Res.T) {
            // 工具带前缀才是它的匹配串（Res.tool 的前缀是 "tool:"）：不写前缀就补上，写了就原样 —— 两种写法同一个串
            if (low.startsWith("tool:")) low = low.substring(5).trim();
            return low.isEmpty() ? "" : ("tool:" + low);
        }
        return low.isEmpty() ? "" : low;
    }

    /** {@code D:} / {@code D盘} / {@code D:/} → {@code d:}；不是盘符返回 {@code null}。 */
    private static String driveOf(String s) {
        String t = s.trim();
        if (t.length() < 2) return null;
        char c = Character.toLowerCase(t.charAt(0));
        if (c < 'a' || c > 'z') return null;
        String rest = t.substring(1).trim();
        if ("盘".equals(rest)) return c + ":";
        while (rest.endsWith("/") || rest.endsWith("\\")) rest = rest.substring(0, rest.length() - 1).trim();
        if (":".equals(rest) || "：".equals(rest)) return c + ":";
        return null;
    }

    /** 中括号里的三段：{@code "a","b","c"}（分隔符外的空白容忍，字段内容原样）。 */
    private static String[] splitFields(String body) {
        List<String> out = new ArrayList<String>();
        int i = 0;
        int n = body.length();
        while (true) {
            while (i < n && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= n || body.charAt(i) != '"') return null;
            int end = body.indexOf('"', i + 1);
            if (end < 0) return null;
            out.add(body.substring(i + 1, end));
            i = end + 1;
            while (i < n && Character.isWhitespace(body.charAt(i))) i++;
            if (i >= n) break;
            if (body.charAt(i) != ',') return null;
            i++;
        }
        return out.size() == 3 ? new String[] {out.get(0), out.get(1), out.get(2)} : null;
    }

    private static boolean isLetter(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); }

    private static boolean isDigits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        return true;
    }

    // ==================== 账本本体 ====================

    private final File file;
    private final Out out;
    private final List<Entry> entries = new ArrayList<Entry>();
    private final List<Reject> rejects = new ArrayList<Reject>();

    /** 数据根（账本所在目录）：受保护清单以它为准。 */
    private final File dataRoot;
    private final String promptsTarget;
    private final String ledgerTarget;
    private final String ledgerTmpTarget;
    private final String configTarget;
    private final String debugTarget;

    private Acl(File file, Out out) {
        this.file = file;
        this.out = out;
        File p = file == null ? null : file.getParentFile();
        this.dataRoot = p == null ? new File(".") : p;
        this.promptsTarget = Res.path(new File(dataRoot, PROMPTS_DIR)).target();
        this.configTarget = Res.path(new File(dataRoot, CONFIG_FILE)).target();
        this.debugTarget = Res.path(new File(dataRoot, DEBUG_PORT_FILE)).target();
        this.ledgerTarget = file == null ? "" : Res.path(file).target();
        this.ledgerTmpTarget = ledgerTarget.isEmpty() ? "" : (ledgerTarget + ".tmp");
    }

    /** 账本文件（数据根下）。 */
    public static File fileOf(File dataRoot) {
        return dataRoot == null ? new File(FILE_NAME) : new File(dataRoot, FILE_NAME);
    }

    /** 装载账本（文件不在 = 空账本，判定按"按类默认分配"走）。 */
    public static Acl load(File file, Out out) {
        Acl a = new Acl(file, out);
        a.read();
        return a;
    }

    /** 只有内存的账本（不落盘）：测试 / 降级用。 */
    public static Acl empty(Out out) { return new Acl(null, out); }

    /** 重新读盘（外部手改了账本之后用）。 */
    public boolean reload() {
        entries.clear();
        rejects.clear();
        read();
        return true;
    }

    private void read() {
        if (file == null || !file.isFile()) return;
        JsonObject root = J.obj(Fs.read(file, ""));
        if (root == null) {
            warn("[acl] 权限账本不是合法 JSON —— 按空账本处理（默认分配 " + defaultText() + "）："
                    + file.getAbsolutePath());
            return;
        }
        int ver = J.i(root, "version", 0);
        if (ver != VERSION) {
            warn("[acl] 权限账本 version=" + ver + "（这一版是 " + VERSION + "）—— 按 " + VERSION + " 的字段读");
        }
        // 旧字段 "default"：默认分配改成"按资源类型"之后它就没有意义了 —— 忽略 + 告警一次，绝不照它判
        JsonObject d = J.sub(root, "default");
        if (d != null) {
            warn("[acl] default 字段已废弃，默认分配按资源类型（" + defaultText()
                    + "）—— 账本里写的 " + (d.has("bits") ? ("bits=" + J.s(d, "bits", "")) : "default")
                    + " 一律忽略");
        }
        JsonArray arr = J.list(root, "entries");
        if (arr == null) {
            if (J.sub(root, "principals") != null) {
                warn("[acl] 账本里是旧版 principals 嵌套结构（已废弃）—— 按空账本处理，请改成 entries 一行一条");
            } else if (J.sub(root, "rows") != null) {
                warn("[acl] 账本里是更旧的 op 档位表（已废弃）—— 按空账本处理");
            }
            return;
        }
        for (JsonElement e : arr) {
            if (e == null || e.isJsonNull()) {
                reject("", "空元素");
                continue;
            }
            String raw = e.isJsonPrimitive() ? e.getAsString() : e.toString();
            Entry en = parse(raw);
            if (en == null) {
                reject(raw, reason(raw));
                continue;
            }
            entries.add(en);
        }
    }

    private void reject(String raw, String why) {
        Reject r = new Reject(raw, why);
        rejects.add(r);
        warn("[acl] 非法条目已跳过：" + r + "（" + Bits.hint() + "；条目写法 类[\"主体\",\"范围\",\"位\"]）");
    }

    private void warn(String s) { if (out != null) out.warn(s); }

    /** 当前条目（只读副本，<b>按文件顺序</b>）。 */
    public List<Entry> entries() { return new ArrayList<Entry>(entries); }

    /** 被跳过的非法条目（加载时记下）。 */
    public List<Reject> rejects() { return new ArrayList<Reject>(rejects); }

    /** 主体 → 它的条目（按账本顺序）。 */
    public Map<String, List<Entry>> principals() {
        Map<String, List<Entry>> m = new LinkedHashMap<String, List<Entry>>();
        for (Entry e : entries) {
            List<Entry> l = m.get(e.principal());
            if (l == null) { l = new ArrayList<Entry>(); m.put(e.principal(), l); }
            l.add(e);
        }
        return m;
    }

    /**
     * 人类可读清单：<b>默认分配 + 逐条例外（按顺序，带主体层级）</b>。
     * <p>区分"默认分配"与"例外（突破默认）"；并写明"同级里后面的覆盖前面的"。</p>
     */
    public List<String> list() {
        List<String> out2 = new ArrayList<String>();
        out2.add("默认分配（一条例外都没命中时，按资源类型）：" + defaultText());
        out2.add("她自己的默认分配（SYSTEM）：" + systemText());
        out2.add("主体层级：User<QQ>(3) > Group<群号>(2) > ALLUSER(1)；SYSTEM 自成一路(3)");
        int n = 0;
        for (Entry e : entries) {
            n++;
            out2.add(n + ". " + e.raw() + "   [主体 " + e.tierName()
                    + "] -> 例外命中时生效 " + Bits.format(effective(e))
                    + "（默认分配 " + Bits.format(defaultAlloc(e.cls())) + "）"
                    // 「不许静默失效」：把判定真正在比的那个串原样打出来，主人一眼能对账
                    + "   范围匹配串 \"" + e.scope() + "\""
                    + (e.scope().isEmpty() ? "（空串 = 整个 " + e.cls() + " 类）" : ""));
        }
        out2.add("顺序：同一层级里从上往下逐条执行，后面的覆盖前面的（范围的具体度不参与优先级）");
        return out2;
    }

    /**
     * 一条条目<b>命中时</b>的生效位：就是条目声明的位（<b>例外突破默认分配</b>，不再取交集）。
     */
    public int effective(Entry e) {
        if (e == null) return Bits.NONE;
        return e.mask();
    }

    /** 诊断用：一行摘要。 */
    public String stat() {
        StringBuilder sb = new StringBuilder();
        sb.append("权限账本 ").append(principals().size()).append(" 位主体 / ").append(entries.size())
          .append(" 条条目（A ").append(count(Res.A)).append(" / B ").append(count(Res.B))
          .append(" / C ").append(count(Res.C)).append(" / E ").append(count(Res.E))
          .append(" / T ").append(count(Res.T))
          .append(" / 其它 ").append(otherCount()).append("）")
          .append(" · 默认分配 ").append(defaultText())
          .append(" · 主体层级 User>Group>ALLUSER（同级后者覆盖前者）")
          .append(" · ").append(file == null ? "(无文件)" : file.getName());
        if (!rejects.isEmpty()) sb.append(" · 跳过非法条目 ").append(rejects.size()).append(" 条");
        return sb.toString();
    }

    private int count(char cls) {
        int n = 0;
        for (Entry e : entries) if (e.cls() == cls) n++;
        return n;
    }

    private int otherCount() {
        int n = 0;
        for (Entry e : entries) {
            if (e.cls() != Res.A && e.cls() != Res.B && e.cls() != Res.C && e.cls() != Res.E
                    && e.cls() != Res.T) n++;
        }
        return n;
    }

    /** 默认分配表的人话写法（{@code A=NONE B=NONE C=R E=NONE T=NONE}）。 */
    public static String defaultText() {
        return "A=" + Bits.format(DEF_A) + " B=" + Bits.format(DEF_B)
                + " C=" + Bits.format(DEF_C) + " E=" + Bits.format(DEF_E)
                + " T=" + Bits.format(DEF_T);
    }

    /** 她自己的默认分配表的人话写法（{@code A=R B=RW C=RWX E=RWX T=RWX}）。 */
    public static String systemText() {
        return "A=" + Bits.format(SYS_A) + " B=" + Bits.format(SYS_B)
                + " C=" + Bits.format(SYS_C) + " E=" + Bits.format(SYS_E)
                + " T=" + Bits.format(SYS_T);
    }

    // ==================== 授权 / 撤销 ====================

    /**
     * 授权 / 覆盖一条：定位键 = 主体 + 类 + 规范化范围（同一条就地覆盖，否则追加到末尾）。
     * <p><b>不落盘</b>：要持久化自己调 {@link #save()}。</p>
     * <p>{@code SYSTEM} 主体写盘时额外提醒一行（这是给她自己的例外，会影响她的自主行为）。</p>
     *
     * @param bits 位写法（{@code ""} = 显式拒绝）
     * @return 是否成功（主体 / 类 / 范围 / 位任一处认不出 → false，绝不猜）
     */
    public boolean grant(String principal, char cls, String scope, String bits) {
        int m = bitsOfField(bits);
        if (m == Bits.INVALID) {
            warn("[acl] 位写法认不出（" + bits + "），已拒绝：" + Bits.hint() + "；空串 = 一位都没有");
            return false;
        }
        return grant(principal, cls, scope, m);
    }

    /** 同 {@link #grant(String, char, String, String)} 的位值版。 */
    public boolean grant(String principal, char cls, String scope, int mask) {
        if (!Bits.valid(mask)) { warn("[acl] 位值非法（" + mask + "），已拒绝"); return false; }
        String p = principalKey(principal);
        if (p == null) { warn("[acl] " + principalReason(principal)); return false; }
        char c = Character.toUpperCase(cls);
        if (!isLetter(c)) { warn("[acl] 类别要是一个字母（A/B/C/E/T…）"); return false; }
        String scopeRaw = scope == null ? "" : scope.trim();
        if (scopeRaw.indexOf('"') >= 0) { warn("[acl] 范围里不能有双引号：" + scopeRaw); return false; }
        String sc = scopeOf(c, scopeRaw);
        if (sc == null) { warn("[acl] 范围写法认不出：" + scopeRaw); return false; }
        Entry put = new Entry(c, p, scopeRaw, sc, mask, format(c, p, scopeRaw, mask));
        if (SYSTEM_KEY.equals(p)) {
            warn("[acl] 注意：这是给她自己（SYSTEM）的例外，会影响她的自主行为 —— " + put.raw());
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.cls() == c && e.principal().equals(p) && e.scope().equals(sc)) {
                entries.set(i, put);
                return true;
            }
        }
        entries.add(put);
        return true;
    }

    /**
     * 撤销：删掉"主体 + 类 + 规范化范围"那一条。
     *
     * @return 是否真的删掉了（没有这条 → false）
     */
    public boolean revoke(String principal, char cls, String scope) {
        String p = principalKey(principal);
        if (p == null) return false;
        char c = Character.toUpperCase(cls);
        if (!isLetter(c)) return false;
        String raw = scope == null ? "" : scope.trim();
        if (raw.indexOf('"') >= 0) return false;
        String sc = scopeOf(c, raw);
        if (sc == null) return false;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.cls() == c && e.principal().equals(p) && e.scope().equals(sc)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    // ==================== 落盘 ====================

    /** 写盘：<b>先写 {@code <file>.tmp} 再 rename</b>（原子替换，不会留半截文件）。 */
    public boolean save() {
        try {
            if (file == null) return false;
            Fs.mkdirs(file.getParentFile());
            if (!atomicWrite(file, renderJson())) {
                warn("[acl] 权限账本写盘失败：" + file.getAbsolutePath());
                return false;
            }
            return true;
        } catch (Throwable t) {
            warn("[acl] 权限账本写盘异常：" + t);
            return false;
        }
    }

    /**
     * 渲染成 JSON 正文（version + entries，条目原样一行一条）。
     * <p><b>不再写 {@code default} 字段</b>：默认分配按资源类型，账本里不需要它。</p>
     */
    public String renderJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonArray arr = new JsonArray();
        for (Entry e : entries) arr.add(e.raw());
        root.add("entries", arr);
        String text = J.pretty(root);
        return text.endsWith("\n") ? text : (text + "\n");
    }

    /** 账本文件。 */
    public File file() { return file; }

    /** 数据根（受保护清单的基准）。 */
    public File dataRoot() { return dataRoot; }

    /** 原子写：同目录 tmp → rename 覆盖目标（rename 失败才退回直接写）。 */
    private static boolean atomicWrite(File target, String text) {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        if (!Fs.write(tmp, text)) return false;
        if (move(tmp, target)) return true;
        boolean ok = Fs.write(target, text);
        tmp.delete();
        return ok;
    }

    private static boolean move(File from, File to) {
        try {
            Files.move(from.toPath(), to.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable ignore) {
            // 原子移动不被支持（跨卷 / 文件系统不支持）→ 退一步：覆盖式移动
        }
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable ignore) {
        }
        if (to.exists() && !to.delete()) return false;
        return from.renameTo(to);
    }

    // ==================== 默认分配 ====================

    /** <b>默认分配</b>（ALLUSER 一条例外都没命中时）：按资源类型。认不出的类 → {@code NONE}（fail-closed）。 */
    public static int defaultAlloc(char cls) {
        switch (cls) {
            case Res.A: return DEF_A;
            case Res.B: return DEF_B;
            case Res.C: return DEF_C;
            case Res.E: return DEF_E;
            case Res.T: return DEF_T;
            default: return Bits.NONE;
        }
    }

    /** <b>她自己的默认分配</b>（SYSTEM 一条例外都没命中时）：按资源类型。 */
    public static int systemAlloc(char cls) {
        switch (cls) {
            case Res.A: return SYS_A;
            case Res.B: return SYS_B;
            case Res.C: return SYS_C;
            case Res.E: return SYS_E;
            case Res.T: return SYS_T;
            default: return Bits.NONE;
        }
    }

    /** 该身份在该类的默认分配（MASTER = RWX 到处；SYSTEM = 她自己的表；ALLUSER = 按类默认）。 */
    public static int allocOf(Caller.Kind kind, char cls) {
        if (kind == Caller.Kind.MASTER) return Bits.ALL;
        if (kind == Caller.Kind.SYSTEM) return systemAlloc(cls);
        return defaultAlloc(cls);
    }

    /** 这次判定里"默认分配"是多少（诊断用）：未知身份 / 通配 → {@code NONE}。 */
    public int allocOf(Caller c, Res r) {
        if (c == null || r == null) return Bits.NONE;
        if (r.cls() == Res.ALL_CLS) return Bits.NONE;
        return allocOf(c.kind(), r.cls());
    }

    /** @deprecated 旧名（"类别上限"口径已废）：{@link #defaultAlloc(char)}。 */
    @Deprecated
    public static int capOf(char cls) { return defaultAlloc(cls); }

    /** @deprecated 旧名：{@code system=true} → {@link #systemAlloc(char)}，否则 {@link #defaultAlloc(char)}。 */
    @Deprecated
    public static int capOf(char cls, boolean system) { return system ? systemAlloc(cls) : defaultAlloc(cls); }

    /** @deprecated 旧名：{@link #allocOf(Caller.Kind, char)}。 */
    @Deprecated
    public static int capOf(Caller.Kind kind, char cls) { return allocOf(kind, cls); }

    /** @deprecated 旧名：{@link #allocOf(Caller, Res)}。 */
    @Deprecated
    public int ceilingOf(Caller c, Res r) { return allocOf(c, r); }

    // ==================== 判定 ====================

    /**
     * 命中条目：{@code null} = 一条都没命中（走默认分配）。
     * <p>规则：范围命中 + 主体层级 &gt; 0，<b>层级最高者说了算</b>；
     * 同一层级里<b>后写的覆盖先写的</b>；层级低的条目不覆盖层级高的（{@code t >= bestTier}）。</p>
     */
    public Entry hit(Caller c, Res r) {
        if (c == null || r == null) return null;
        Entry best = null;
        int bestTier = TIER_NONE;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.cls() != r.cls()) continue;
            int t = e.tier(c);
            if (t <= TIER_NONE) continue;
            if (!e.scopeHits(r)) continue;
            if (t >= bestTier) {          // ★ 高层级直接接管；同级后来者覆盖
                best = e;
                bestTier = t;
            }
        }
        return best;
    }

    /**
     * 这份位<b>从哪来</b>（拒绝文案 / 回执用）：MASTER / 例外条目 / 默认分配 / 受保护资源。
     */
    public String source(Caller c, Res r) {
        if (c == null) return "缺少调用者身份";
        if (r == null) return "缺少资源描述";
        if (c.kind() == Caller.Kind.MASTER) return "MASTER 恒全权（任何条目都不影响他）";
        if (r.cls() == Res.ALL_CLS) return "通配资源，非主人一律不给";
        if (masterOnlyRes(r)) {
            return "第一层受保护资源（权限与账号：账本自己 / 授权动作 / 登录凭据），只有 MASTER 拿得到（连她的自主行为也不给）";
        }
        if (c.kind() == Caller.Kind.ALLUSER && outsiderBlockedRes(r)) {
            // 核心第二层（提示词 / 含 token 的配置）沿用老文案；"按扩展名"那一档单独说清缘由
            if (secondLayerCoreRes(r)) {
                return "第二层受保护资源（她自己的身份与配置），外人一律读不到（例外也不开）";
            }
            return "受保护资源（配置/脚本不可外泄：非主人一律读不到）";
        }
        Entry e = hit(c, r);
        if (e != null) return "来自例外条目 " + e.raw();
        if (c.kind() == Caller.Kind.SYSTEM) return "她自己的默认分配 " + Bits.format(systemAlloc(r.cls()));
        // files 树没有专有位（它是 C 类里的特例文件实体）：位一律服从 C 的默认分配，所以这里不再单开一档
        return "默认分配 " + Bits.format(defaultAlloc(r.cls()));
    }

    /**
     * 要害函数：主体对资源的<b>有效位</b> = 按类默认分配 + 例外突破默认。
     * <p>{@code null} 资源 / 主体 → {@code NONE}；通配资源对非主人 → {@code NONE}（fail-closed）；
     * 受保护资源对 ALLUSER 恒 {@code NONE}（例外也不开）。</p>
     */
    public int bitsOf(Caller c, Res r) {
        if (c == null || r == null) return Bits.NONE;
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER) return Bits.ALL;                    // 主人恒全权
        if (r.cls() == Res.ALL_CLS) return Bits.NONE;                    // 通配：非主人一律空
        if (masterOnlyRes(r)) return Bits.NONE;                          // 第一层：连她也不给
        if (k == Caller.Kind.ALLUSER && outsiderBlockedRes(r)) return Bits.NONE;  // 第二层：外人恒空
        Entry e = hit(c, r);
        if (e != null) return e.mask();                                  // 例外直接生效（突破默认）
        if (k == Caller.Kind.SYSTEM) return systemAlloc(r.cls());
        // 注：{@code <数据根>/files} 树（Res.isFilesTree）不再有专有位 —— 主人裁定 2026-09-15：
        // 它是 C 类里的特例文件实体，位一律服从 C（这里与 defaultAlloc(Res.C) 是同一条路）。
        return defaultAlloc(r.cls());
    }

    /** 放行返回 {@code null}，否则返回可读理由。 */
    public String allow(Caller c, Res r, char need) {
        if (r == null) return DENY_PREFIX + "缺少资源描述";
        int bit = Bits.of(need);
        if (bit <= 0) {
            return DENY_PREFIX + "需要 " + r + " 的 " + need + " 位（位名认不出，只认 R/W/X）";
        }
        char n = Character.toUpperCase(need);
        if (c == null) {
            return DENY_PREFIX + "需要 " + r.target() + " 的 " + n + " 位（缺少调用者身份）";
        }
        int bits = bitsOf(c, r);
        if (Bits.has(bits, bit)) return null;
        return DENY_PREFIX + "需要 " + r.target() + " 的 " + n + " 位（" + source(c, r)
                + "，当前有效位 " + Bits.format(bits) + "）";
    }

    /**
     * <b>第一层受保护资源：只有主人</b>（连她自己的自主行为也不给）——
     * 账本自身（{@code perms.json} + {@code .tmp}）、授权动作本身（{@code mem:acl}）、
     * 账号凭据（{@code get_cookies} 一类登录态）。
     * <p>理由：这一层是"权限与账号"。她若能通过工具碰它，任何"诱导她自己改权限"的路径都能绕过主人，
     * 所以 <b>SYSTEM 也恒 {@code NONE}</b>，例外也不开。</p>
     */
    public boolean masterOnlyRes(Res r) {
        if (r == null) return false;
        String t = r.target();
        if (r.cls() == Res.E) return PROTECTED_PLATFORM.equals(t);
        if (r.cls() == Res.C) return PROTECTED_MEM.equals(t);
        if (!r.isPath()) return false;
        return !ledgerTarget.isEmpty() && (t.equals(ledgerTarget) || t.equals(ledgerTmpTarget));
    }

    /**
     * <b>第二层受保护资源：她自己的东西，只是不给外人</b>，两档：
     * <ol>
     *   <li><b>核心档</b>（{@link #secondLayerCoreRes(Res)}）：身份/系统提示词目录
     *       （{@code <数据根>/prompts/**}）、含 token 的配置（{@code config.json}、{@code debug-port.json}）；</li>
     *   <li><b>按扩展名档</b>（{@link #protectedExtRes(Res)}，后缀清单见 {@link #PROTECTED_EXTS}）：B 类路径里
     *       以受限配置 / 脚本 / 密钥材料后缀结尾的那些 —— 主人 2026-09-15 定标「受限配置文件与 bat 脚本，
     *       ir 脚本绝对不可泄露」，同日裁定按建议扩充保护后缀（{@code .xml/.env/.key/.pem/.p12}）。</li>
     * </ol>
     * <p>MASTER 与 SYSTEM 照常（她可读写自己的提示词与配置），<b>ALLUSER 恒 {@code NONE}</b>（例外也不开）。</p>
     */
    public boolean outsiderBlockedRes(Res r) { return secondLayerCoreRes(r) || protectedExtRes(r); }

    /**
     * 第二层核心档：提示词目录（含其下所有文件）、含 token 的配置。
     * <p>与 {@link #protectedExtRes(Res)} 分开，是为了让拒绝文案能分辨"哪一档挡的"。</p>
     */
    public boolean secondLayerCoreRes(Res r) {
        if (r == null || !r.isPath()) return false;
        String t = r.target();
        if (t.equals(configTarget) || t.equals(debugTarget)) return true;
        return Res.under(t, promptsTarget);
    }

    /**
     * 第二层「<b>按扩展名</b>」档：<b>只对 B 类路径</b>（SFW 根之内）生效，后缀见 {@link #PROTECTED_EXTS}。
     *
     * <p><b>为什么只对 B 类</b>：这条规则管的是"SFW 里的受限配置与脚本"（数据根里的 {@code .json}、
     * 能改本机状态的 {@code .bat}/{@code .cmd}、框架启动脚本 {@code .ir}）。A 类已经由
     * {@link #DEF_A}（对 ALLUSER 一位都不给）兜住了，不需要也不该再按扩展名分档 ——
     * 免得让人以为"A 类换个后缀就能读"。C/E 类不是路径资源，一律不适用。</p>
     * <p><b>大小写不敏感</b>（{@code CONFIG.JSON} 与 {@code config.json} 同命）；
     * <b>精确后缀</b>（{@code endsWith}）：{@code export.jsonl} 不匹配 {@code .json}，
     * {@code store_admin export} 导出的 {@code .jsonl} <b>不</b>受影响。</p>
     */
    public boolean protectedExtRes(Res r) {
        if (r == null || r.cls() != Res.B) return false;
        String t = r.target();
        if (t == null || t.isEmpty()) return false;
        String low = t.toLowerCase(Locale.ROOT);
        for (int i = 0; i < PROTECTED_EXTS.length; i++) {
            if (low.endsWith(PROTECTED_EXTS[i])) return true;
        }
        return false;
    }

    /** 受保护资源（两层合集）：诊断 / 清单用。判定请分别看两层。 */
    public boolean protectedRes(Res r) { return masterOnlyRes(r) || outsiderBlockedRes(r); }

    /** 诊断用：一批主体的条目（{@code perm acl} 之外的地方用）。 */
    public List<Entry> rulesOf(String principal) {
        String p = principalKey(principal);
        if (p == null) return Collections.emptyList();
        List<Entry> out2 = new ArrayList<Entry>();
        for (Entry e : entries) if (e.principal().equals(p)) out2.add(e);
        return out2;
    }

    // ==================== 只读查询（喂事实块 caller.rules / perm 的只读 op） ====================
    //
    // 这一节是"基板只做三件事"里的第三件（喂养查询视图）：只把内存里的条目**读出来**给她看，
    // 既不改账本、也不算位、更不下任何"有没有权限"的结论 —— 结论永远只出自 bitsOf/allow 那一处。

    /**
     * <b>只读查询：这个调用者能命中的候选条目</b> —— 按账本顺序返回（{@link Entry#raw()} 就是原样条目）。
     *
     * <p><b>取舍：为什么取"主体层级 &gt; 0"而不是"某个资源命中"</b>。账本条目命中 = 主体层级 &gt; 0
     * <b>且</b>范围命中该资源（{@link Entry#hits(Caller, Res)}）。本方法手上<b>没有具体资源</b> ——
     * 它的用途是"这个人的例外清单长什么样"（事实块 {@code caller.rules}、{@code perm op=check} 的整类视图），
     * 所以这里只过前一半（{@link Entry#tier(Caller)} &gt; 0），<b>把范围那一半留给判定那一刻</b>：
     * "某条条目到底有没有命中某个具体资源"仍由 {@code Auth.allowRes}（→ {@link #hit(Caller, Res)}）
     * 说了算。本方法<b>不做判定</b>，也不给任何"有没有位"的结论。</p>
     *
     * <p>口径与判定完全同源（主体层级用的是同一个 {@link Entry#tier(Caller)}：
     * {@code User<QQ>} 3 / {@code Group<群号>} 2 / {@code ALLUSER} 1 / {@code SYSTEM} 3 / 不匹配 0），
     * 于是两件必然的后果：<b>MASTER 恒返回空表</b>（条目管不到他，{@link #bitsOf} 里他恒 {@code RWX}）；
     * {@code SYSTEM} 只看到 {@code SYSTEM} 条目（{@code User}/{@code Group}/{@code ALLUSER} 条目都不吃他）。
     * 这两种"空表"是<b>正确结果</b>，不是"查不到"。</p>
     *
     * <p><b>只读</b>：只遍历内存里已加载的 {@code entries} —— 不重读磁盘、不算位、不碰判定。
     * 想按主体键查"这个人名下写了什么"（不看调用者是谁、不过层级）用 {@link #rulesOf(String)}。</p>
     */
    public List<Entry> rulesFor(Caller c) {
        List<Entry> out2 = new ArrayList<Entry>();
        if (c == null) return out2;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.tier(c) > TIER_NONE) out2.add(e);
        }
        return out2;
    }

    /**
     * 同 {@link #rulesFor(Caller)} 的"一行一条"文字形态：原样条目（{@link Entry#raw()}），最多 {@code max} 条；
     * 被截断时末尾<b>追加一句</b> {@code 还有 N 条}（N = 没列出来的条数）。
     *
     * <p>{@code max <= 0} = 不截断；<b>空表 = 一条都没有</b>（调用方照旧渲染成空数组，
     * <b>绝不是 {@code null}</b>）。同理只读：不重读磁盘、不算位、不做判定。</p>
     */
    public List<String> ruleLines(Caller c, int max) {
        List<Entry> es = rulesFor(c);
        int n = es.size();
        int keep = (max <= 0 || max > n) ? n : max;
        List<String> out2 = new ArrayList<String>();
        for (int i = 0; i < keep; i++) out2.add(es.get(i).raw());
        if (keep < n) out2.add("还有 " + (n - keep) + " 条");
        return out2;
    }
}
