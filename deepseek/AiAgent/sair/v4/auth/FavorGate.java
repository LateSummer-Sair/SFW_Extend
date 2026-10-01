package sair.v4.auth;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import sair.v4.Conf;

/**
 * <b>好感度档位放权闸 —— 与 ACL 平行的第二道闸</b>（主人令 2026-09-26）。
 *
 * <p>它不是权限表的一部分，也不是往权限表里加规则：{@link Acl} 仍在<b>前面</b>原样判它的
 * （{@code Ban > Run > 空}、最长路径优先、未列到 = 不给）。当 ACL 把某个 {@code ALLUSER}
 * 拒掉之后，{@link Acl} 才回头问这里一次：
 * 「<b>这一档是不是本来就该让他做这件只读的事</b>？」放行 ⇒ 按放行处理（并在结构事实上标注
 * {@code [favor] lift …}，可审计）；不适用 ⇒ 返回 {@code null}，ACL 的拒绝原样生效。</p>
 *
 * <h3>放行清单（写死的窄表 —— 不许通配、不许按前缀）</h3>
 * <p>只有下面这 <b>4</b> 个 op 会被这道闸碰到，且全部要求 {@code favor >= favorFileMin}（出厂 500）。
 * 别的 op 一个字都不受影响 —— 包括同一个工具下的别的动作（{@code file.find} / {@code file.write} /
 * {@code send.group} / {@code send.private} / {@code send.fileto} …）与全部 {@code napcat.*} 内层动作。</p>
 * <pre>
 *   file.read   读文本文件          >= favorFileMin (出厂 500)
 *   file.list   列目录              >= favorFileMin (出厂 500)
 *   send.file   发文件到当前会话     >= favorFileMin (出厂 500)
 *   send.record 发语音              >= favorFileMin (出厂 500)
 * </pre>
 * <p>{@code send.fileto} 曾被列进这张表又被划掉（"放行了却发不出去"的死条目）—— 理由写在
 * {@code LIFT_OPS} 的 javadoc 里。</p>
 *
 * <h3>两件不受此闸影响的事</h3>
 * <ul>
 *   <li><b>主人（MASTER）与她本人（SYSTEM）</b>：本来就恒全权，{@link #lift} 对他们一律
 *       {@code null}（"不需要这道闸"），他们的行为与加这一层之前逐字节一致；</li>
 *   <li><b>{@code favor < } 档位</b>：一个 op 都不放宽（返回 {@code null}）。</li>
 * </ul>
 *
 * <h3>只读范围（{@code File} 域）</h3>
 * <p>{@link #liftPath} 只在 {@code write=false}（读）时生效，且只对"ACL 没列到"的路径生效 ——
 * ACL 里<b>显式</b>的 {@code Ban} 与 {@code Read} 决定永远压过它。默认允许根两处：
 * {@code D:/share/}（今天就有）与数据根下的 {@code files/} 那一格，由配置键
 * {@code favorFileRoots}（分号分隔）给出。</p>
 * <p><b>硬 Ban 压过一切"非主人 / 非她自己"的放行</b>（两组都写死在代码里、不看表、不看档位；主人与她本人恒全权）：</p>
 * <ul>
 *   <li>{@link #HARD_BAN} —— 绝对路径前缀（{@code D:/share/SairFrameWork/}）；</li>
 *   <li>{@link #HARD_BAN_REL} —— 数据根相对（{@code config.json} / {@code debug-port.json} /
 *       {@code v4.db}（前缀，连带 {@code -wal}/{@code -shm}）/ {@code logs/} / {@code SFW-BOT.ir}
 *       （前缀，连带 {@code .bak-*}））：<b>① 锚定在数据根那一层的前缀匹配（原有语义，一字未动）
 *       ＋ ② 数据根之内的任意深度匹配（批 15 / 2026-09-26 新增，见 {@link #banned(String, File)}）
 *       —— 这两条合起来才叫"这几条读不到"</b>。<b>就算把 {@code favorFileRoots} 改回整棵
 *       {@code {root}}，这几条对**非主人**照样读不到（主人与她本人不受它约束）。</b></li>
 * </ul>
 *
 * <h3>配置</h3>
 * <p>四个键都<b>进 {@link Conf#knownKeys()}、不进 {@link Conf#ensureDefaults()}</b>
 * （出厂不写盘，缺省值只有本类这一处）。键名常量在这里，{@code Conf} 的 getter 读它们
 * （单一真源，与 {@code qqPttRetry} 那一族的登记口径相同）。</p>
 */
public final class FavorGate {

    // ==================== 配置键与出厂默认（唯一真源） ====================

    /** 这道闸的总开关（{@code favorGateEnabled}，出厂 {@code true}）。 */
    public static final String CFG_ENABLED = "favorGateEnabled";
    /** 文件那一档的门槛（{@code favorFileMin}，出厂 {@code 500}）。 */
    public static final String CFG_FILE_MIN = "favorFileMin";
    /** "不会再对此人发怒 + 尊重口气"那一档的门槛（{@code favorNoAngerMin}，出厂 {@code 700}）。 */
    public static final String CFG_NO_ANGER_MIN = "favorNoAngerMin";
    /** 读放行的允许根（{@code favorFileRoots}，分号分隔；{@code {root}} = 数据根）。 */
    public static final String CFG_FILE_ROOTS = "favorFileRoots";

    /** 总开关出厂默认。 */
    public static final boolean DEF_ENABLED = true;
    /** 文件档出厂门槛（= 主人令的 {@code >=500}）。 */
    public static final int DEF_FILE_MIN = 500;
    /** 尊重档出厂门槛（= 主人令的 {@code >=700}，情绪那一半由 data 侧做）。 */
    public static final int DEF_NO_ANGER_MIN = 700;
    /**
     * 读放行允许根的出厂默认：{@code D:/share/}（今天成员就能读的）+ 数据根下的
     * <b>{@code files/}</b> 那一格（她的库 / 收藏 / 文件）。
     *
     * <p><b>不是整棵数据根</b>（GM 2026-09-26 裁决收窄）：数据根里同时躺着 {@code config.json}
     * （apiKey / napcatToken / relayToken）、{@code v4.db}（六库全量：所有人的 dialog / grouplog /
     * memory / impression）、{@code logs/} —— "她的库/收藏/文件"只指 {@code files/} 那一格，
     * 把整台机器的数据面一起开出去不是这条放权的本意。</p>
     *
     * <p>{@code {root}} 是占位符，判定时换成那个进程的数据根（{@code Acl} 把自己的
     * {@code dataRoot} 交下来），并一律补成"末尾带斜杠"的目录写法（数据根按定义是目录）。</p>
     */
    public static final String DEF_FILE_ROOTS = "D:/share/;{root}/files/";

    /** 允许根写法里的数据根占位符。 */
    public static final String ROOT_PLACEHOLDER = "{root}";

    /**
     * <b>写死的硬 Ban —— 绝对路径前缀</b>（一律归一化后比）：档位再高也读不了这里。
     * <p>第一条就是线上那张表里的 {@code File.Ban}，这里再钉一遍 —— 主人的原话是
     * "既有的敏感 Ban 一律仍然 Ban（压过一切）"，所以它<b>不许</b>只依赖权限表里那条规则
     * （表被改、被判成 {@code NONE}、或换一份 workspace，这条都得还成立）。</p>
     */
    public static final String[] HARD_BAN = {
        "D:/share/SairFrameWork/",
    };

    /**
     * <b>写死的硬 Ban —— 数据根相对前缀</b>（GM 2026-09-26 裁决）：{@link #ROOT_PLACEHOLDER} 展开成
     * 当前数据根之后按前缀比。
     *
     * <p>这几条<b>与档位无关、与允许根也无关</b>：就算主人把 {@code favorFileRoots} 改回整棵
     * {@code {root}}，它们照样读不到。前缀语义顺手覆盖同类文件 —— {@code v4.db} 一条就管住了
     * {@code v4.db} / {@code v4.db-wal} / {@code v4.db-shm}；{@code SFW-BOT.ir} 一条管住了它自己与
     * {@code SFW-BOT.ir.bak-*} 那些备份。</p>
     *
     * <p><b>这条表在数据根之内是"任意深度"的</b>（批 15 / 2026-09-26，GM 裁决）：光锚定在数据根那一层
     * 只护得住深度 1 —— 一个 {@code {root}/files/v4.db}（同名件住在允许根 {@code files/} 里）就漏了。
     * 所以除了原来那套锚定前缀（{@link #relative} ＋ {@link #hitPrefix}，一位没动），
     * {@link #banned(String, File)} 还会在数据根之内按<b>路径段边界</b>比第二遍
     * （{@link #bannedDeep} / {@link #hitDeep}）。两条边界同时成立：<b>只在数据根之内</b>
     * （{@code D:/share/logs/x} 这种数据根之外的同名路径照旧允许）、<b>只认段首</b>
     * （{@code files/xv4.db} 不算 {@code v4.db}）。</p>
     */
    public static final String[] HARD_BAN_REL = {
        "config.json",
        "debug-port.json",
        "v4.db",
        "logs/",
        "SFW-BOT.ir",
    };

    // ==================== 档位（主人令 2026-09-26 的新线） ====================

    /** 自动同意好友那一档（已由 K6 在"申请审批"技能里做，不归本闸）。 */
    public static final int TIER_FRIEND = 100;
    /** 可被邀请进其他群那一档（同上，K6 做）。 */
    public static final int TIER_INVITE = 300;
    /** 文件放权那一档（= {@link #DEF_FILE_MIN}）。 */
    public static final int TIER_FILES = 500;
    /** 尊重档（= {@link #DEF_NO_ANGER_MIN}；情绪那半是 data 侧 K7 做，本闸只给"档位"这个事实）。 */
    public static final int TIER_RESPECT = 700;

    /** 档位名：没到 {@link #TIER_FRIEND}。 */
    public static final String TIER_STRANGER = "stranger";
    /** 档位名：{@code >=100}。 */
    public static final String TIER_NAME_FRIEND = "friend";
    /** 档位名：{@code >=300}。 */
    public static final String TIER_NAME_INVITE = "invite";
    /** 档位名：{@code >=} 文件档。 */
    public static final String TIER_NAME_FILES = "files";
    /** 档位名：{@code >=} 尊重档。 */
    public static final String TIER_NAME_RESPECT = "respect";
    /** 档位名：顶到上限。 */
    public static final String TIER_NAME_CAP = "cap";

    // ==================== 放行清单（窄表，写死） ====================

    /**
     * <b>放行清单原文</b>：只有这 <b>4</b> 个 op 会被这道闸碰到（纯<b>成员表</b>，没有通配、
     * 没有前缀匹配、没有第二个入口）。{@link LinkedHashSet} 只为让 {@link #liftOps()} 的顺序稳定
     * （可读、可断言）。</p>
     *
     * <p><b>为什么没有 {@code send.fileto}</b>（GM 2026-09-26 裁决划掉）：规格曾经既把它列进放行清单、
     * 又要求"以她本人身份发出去"的出口<b>落点只能是当前会话</b>。两条一起 ⇒ 它是一条
     * <b>"放行了却发不出去"的死条目</b>：op 那一判过了，真到发的那一跳仍撞
     * {@code napcat.upload_private_file} 的 {@code Ban}（发给别人 ≠ 当前会话 ⇒ 出口按规约返回
     * {@code null} ⇒ 回退老路 ⇒ 被拒）。留着它只会让她在事实块里看到一个自己做不到的能力 ——
     * 那正是"描述与权限表不一致"这一类缺陷。要恢复它，得先有一条**独立的、经过复核的**
     * "发给指定的人"出口，而不是把它塞回这张表。</p>
     *
     * <p>门槛不在这张表里，而在配置键 {@code favorFileMin}（出厂 {@link #DEF_FILE_MIN} = 500）——
     * 主人把这个门槛调大调小，这几条一起跟着走（"文件那一档"是一条线，不是四个数）。</p>
     */
    private static final LinkedHashSet<String> LIFT_OPS = new LinkedHashSet<String>();
    static {
        LIFT_OPS.add("file.read");
        LIFT_OPS.add("file.list");
        LIFT_OPS.add("send.file");
        LIFT_OPS.add("send.record");
    }

    /** 这张窄表的<b>出厂门槛</b>（= {@link #DEF_FILE_MIN}；生效值是 {@link #fileMin()}）。 */
    public static final int LIFT_MIN = DEF_FILE_MIN;

    /** 这张窄表里的全部 op（顺序稳定；事实块与探针用）。 */
    public static List<String> liftOps() {
        return Collections.unmodifiableList(new ArrayList<String>(LIFT_OPS));
    }

    /** 这个 op 在不在这张窄表里（反向钉子：表外一律 false）。 */
    public static boolean inTable(String op) {
        String o = op == null ? "" : op.trim();
        return !o.isEmpty() && LIFT_OPS.contains(o);
    }

    // ==================== 配置面（Auth 装机时注进来） ====================

    /**
     * 当前这一份配置。<b>由 {@link Auth} 的构造器注入</b>（一个进程一份 Conf，与 Auth 同源）。
     * <p>没有注入（{@code null}，例：直接 {@code Acl.load(...)} 的独立用例）时一律按出厂默认跑 ——
     * 也就是说这道闸<b>默认是开的</b>（{@code favorGateEnabled=true}）。</p>
     */
    private static volatile Conf CONF;

    /** 注入配置面（{@link Auth} 构造器调；重复注入以最后一次为准）。 */
    public static void install(Conf conf) { CONF = conf; }

    /** 当前配置面（没注入 = {@code null}）。 */
    public static Conf conf() { return CONF; }

    /** 这道闸现在开不开（{@code favorGateEnabled}，出厂 {@code true}）。 */
    public static boolean enabled() {
        Conf c = CONF;
        return c == null ? DEF_ENABLED : c.favorGateEnabled();
    }

    /** 文件那一档现在是多少（{@code favorFileMin}，出厂 {@code 500}）。 */
    public static int fileMin() {
        Conf c = CONF;
        return c == null ? DEF_FILE_MIN : c.favorFileMin();
    }

    /** 尊重那一档现在是多少（{@code favorNoAngerMin}，出厂 {@code 700}）。 */
    public static int noAngerMin() {
        Conf c = CONF;
        return c == null ? DEF_NO_ANGER_MIN : c.favorNoAngerMin();
    }

    /** 读放行的允许根原文（{@code favorFileRoots}，出厂 {@link #DEF_FILE_ROOTS}）。 */
    public static String fileRoots() {
        Conf c = CONF;
        return c == null ? DEF_FILE_ROOTS : c.favorFileRoots();
    }

    // ==================== 判定：op ====================

    /**
     * <b>这一档是否给这个调用者放行这个 op</b>；不放行 / 不适用 ⇒ {@code null}
     * （ACL 的结论原样生效）。
     *
     * <p>顺序（每一步都可能直接 {@code null}）：① 闸关着；② 没有调用者；③ 主人 / 她本人
     * （他们本来就全权，这道闸对他们没有意义）；④ op 不在 {@link #LIFT_OPS} 这张窄表里；
     * ⑤ {@code favor <} 需要的门槛。</p>
     *
     * @return 放行时返回<b>可审计的一句话</b>（{@code favor>=500 tier=files op=file.read}）；
     *         {@code null} = 不适用 / 不放行
     */
    public static String lift(String op, Caller c) {
        if (!enabled()) return null;
        if (c == null) return null;
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM) return null;   // 恒全权，不受此闸影响
        String o = op == null ? "" : op.trim();
        if (o.isEmpty()) return null;
        if (!LIFT_OPS.contains(o)) return null;                               // ★ 窄表之外：一律不放行
        int min = fileMin();
        double f = c.favor();
        if (f < (double) min) return null;                                    // ★ 不到档：一个 op 都不放宽
        return "favor>=" + min + " tier=" + tier(f) + " op=" + o;
    }

    /** 同 {@link #lift(String, Caller)} 的布尔版（"这一档放不放这个 op"）。 */
    public static boolean allows(String op, Caller c) { return lift(op, c) != null; }

    /**
     * 这个 op 是不是本闸能替 {@code ALLUSER} 接住的那 4 个之一（= {@link #lift} 的窄表，
     * 见 {@code LIFT_OPS}：{@code file.read} / {@code file.list} / {@code send.file} / {@code send.record}）。
     * <p>给出口（{@code Host.sayFile} / {@code Host.sayMedia}）判"要不要先过闸"用。</p>
     */
    public static boolean gated(String op) { return inTable(op); }

    // ==================== 判定：路径（只读范围） ====================

    /**
     * <b>这一档是否给这个调用者放行这个本地路径的读</b>；不放行 / 不适用 ⇒ {@code null}。
     *
     * <p>规矩（缺一不可）：① 闸开着；② 有调用者；③ 不是主人 / 她本人；④ <b>{@code write=false}</b>
     * （放权的是<b>只读</b>范围，写一个字节都不放宽）；⑤ {@code favor >=} 文件档；
     * ⑥ 归一化后的路径落在允许根之内；⑦ 不落在 {@link #HARD_BAN} 之内（压过一切）。</p>
     *
     * <p><b>只填"ACL 没列到"的洞</b>：ACL 里显式的 {@code Ban} 由调用方（{@link Acl#fileDeny}）
     * 先一步挡掉，根本走不到这里；显式写了的 {@code Read} 表（含空表）也由调用方按原样收尾。</p>
     *
     * @param path     已经归一化过的路径（{@link Acl} 的 {@code normPath}，与装载时同一套）
     * @param dataRoot 数据根（{@link #ROOT_PLACEHOLDER} 换成它；可为 {@code null}）
     * @return 放行时返回可审计的一句话；{@code null} = 不适用 / 不放行
     */
    public static String liftPath(Caller c, String path, boolean write, File dataRoot) {
        if (!enabled()) return null;
        if (c == null) return null;
        Caller.Kind k = c.kind();
        if (k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM) return null;
        if (write) return null;                                               // ★ 只读放权
        int min = fileMin();
        double f = c.favor();
        if (f < (double) min) return null;
        String p = path == null ? "" : path.trim();
        if (p.isEmpty()) return null;
        String np = Acl.normPath(p);
        if (np.isEmpty()) return null;
        if (banned(np, dataRoot)) return null;                                // ★ 硬 Ban 压过一切
        String root = underRoot(np, dataRoot);
        if (root == null) return null;                                        // 不在允许根之内
        return "favor>=" + min + " tier=" + tier(f) + " path-root=" + root;
    }

    /**
     * 这个路径落不落在硬 Ban 里（Windows 大小写不敏感）。
     * <p>传进来的写法原样收：自己先过一遍 {@link Acl#normPath}（与本类别处的口径一致），
     * 所以 {@code D:\share\SairFrameWork\x} 这种反斜杠写法同样认得。</p>
     *
     * <p><b>三条判据（批 15 / 2026-09-26 的第二条是新增）</b>：</p>
     * <ol>
     *   <li>{@link #HARD_BAN} 绝对路径前缀 —— 原样，一位没动；</li>
     *   <li>{@link #HARD_BAN_REL} <b>锚定在数据根</b>那一层的前缀 —— 原样，一位没动；</li>
     *   <li>★ {@link #HARD_BAN_REL} 在<b>数据根之内</b>按<b>路径段边界</b>的任意深度命中 ——
     *       新增（{@link #bannedDeep}）。加它的原因见 {@link #HARD_BAN_REL} 的 javadoc：
     *       光有 ①②，{@code {root}/files/v4.db} 就漏在允许根 {@code files/} 里了。
     *       <b>它只往"更禁"的方向动，绝不让任何既有路径变宽</b>；而且只在数据根之内生效
     *       （{@code D:/share/logs/x} 仍然允许），{@code underRoot} 那一支一个字都没碰。</li>
     * </ol>
     *
     * <p>没有数据根 ⇒ 只比 {@link #HARD_BAN}（绝对路径那几条）；给了数据根才连
     * {@link #HARD_BAN_REL} 一起比（②③ 两半都要数据根）。两个重载的差别只有这一件事。</p>
     */
    public static boolean banned(String path) { return banned(path, null); }

    /** 同 {@link #banned(String)}，并把 {@link #HARD_BAN_REL} 按这个数据根展开一起比。 */
    public static boolean banned(String path, File dataRoot) {
        if (path == null || path.trim().isEmpty()) return false;
        String np = Acl.normPath(path);
        if (np.isEmpty()) return false;
        String p = Acl.cmpPath(np);
        // ★ P1（批 15 / 2026-09-26）：绝对表照旧 —— 原来那张 sets 循环里的第一半，逐字搬出来，
        //   语义一位没动（拆开只是为了给第二半加一条"任意深度"的判据）。
        String[] abs = absolute(HARD_BAN);
        for (int i = 0; i < abs.length; i++) {
            if (hitPrefix(p, abs[i])) return true;
        }
        // ★ P1 ②：数据根相对表，锚定前缀那一半 —— 原有语义，一字未动。
        String[] rel = relative(HARD_BAN_REL, dataRoot);
        for (int i = 0; i < rel.length; i++) {
            if (hitPrefix(p, rel[i])) return true;
        }
        // ★ P1 ③：数据根之内、路径段边界的任意深度那一半 —— 缺陷 1 的本体。
        return bannedDeep(p, dataRoot);
    }

    /** 一个前缀表（归一化 + 比较口径化）。 */
    private static String[] absolute(String[] raw) {
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = Acl.cmpPath(Acl.normPath(raw[i]));
        return out;
    }

    /** 数据根相对前缀表 → 绝对（{@code {root}} 展开；没有数据根 ⇒ 空表，宁可少判也绝不乱判）。 */
    private static String[] relative(String[] raw, File dataRoot) {
        if (dataRoot == null) return new String[0];
        String rootPath = Acl.normPath(dataRoot.getAbsolutePath());
        if (rootPath.isEmpty()) return new String[0];
        String dir = rootPath.endsWith("/") ? rootPath : (rootPath + "/");
        String[] out = new String[raw.length];
        for (int i = 0; i < raw.length; i++) out[i] = Acl.cmpPath(Acl.normPath(dir + raw[i]));
        return out;
    }

    // ============ ★ P1（批 15 / 2026-09-26）：硬 Ban 的"数据根之内任意深度"那一半 ============
    // 独立的三个小函数，**只给 banned() 用**：underRoot() / hitPrefix() 一个字都没碰 ——
    // 允许根那一支的语义与加这一半之前逐字节相同（深配不许顺手放宽任何一条读路径）。

    /**
     * 数据根的判定形态：归一化 → 比较口径 → 末尾补斜杠；没有数据根 / 认不出 ⇒ 空串（那一半不生效）。
     * <p>与 {@link #relative(String[], File)} 里那段写法同源同口径 —— 相对表<b>只能</b>在数据根之内
     * 生效，两处必须用同一个"数据根"的定义，否则两条判据的边界会各说各话。</p>
     */
    private static String rootDir(File dataRoot) {
        if (dataRoot == null) return "";
        String rootPath = Acl.normPath(dataRoot.getAbsolutePath());
        if (rootPath.isEmpty()) return "";
        String dir = rootPath.endsWith("/") ? rootPath : (rootPath + "/");
        return Acl.cmpPath(dir);
    }

    /**
     * ★★ {@link #HARD_BAN_REL} 在<b>数据根之内、任意深度</b>的命中（批 15 / 2026-09-26，GM 裁决）。
     *
     * <p><b>为什么非加不可</b>：{@link #DEF_FILE_ROOTS} 把 {@code {root}/files/} 开成了允许根，
     * 而 {@link #banned(String, File)} 原来只在数据根那一层做前缀比 ⇒ 一个 {@code files/v4.db}
     * （六库全量的同名副本）对一个 {@code favor >= 500} 的人就是可读的。放权放的是"她的库 / 收藏 /
     * 文件"那一格，不是"任何叫 {@code v4.db} 的东西"。</p>
     *
     * <p><b>两条边界同时成立（缺一不可）</b>：① <b>只在数据根之内</b> —— 先把数据根那一段剥掉再比，
     * 所以 {@code D:/share/logs/x} 这种数据根之外的同名路径照旧允许（相对表绝不外溢）；
     * ② <b>只认路径段的开头</b>（{@link #hitDeep}）—— {@code files/xv4.db} 不算 {@code v4.db}。</p>
     *
     * <p>方向只许更禁：这一半只会把 {@code false} 变成 {@code true}，永远不会把既有路径放宽。</p>
     */
    private static boolean bannedDeep(String p, File dataRoot) {
        String dir = rootDir(dataRoot);
        if (dir.isEmpty()) return false;                       // 没有数据根 ⇒ 相对表整块不生效
        if (!p.startsWith(dir)) return false;                  // ★ 数据根之外：一个字都不改
        String rest = p.substring(dir.length());               // 数据根之内那一段（不以 / 打头）
        if (rest.isEmpty()) return false;                      // 数据根自己不是硬 Ban
        for (int i = 0; i < HARD_BAN_REL.length; i++) {
            if (hitDeep(rest, HARD_BAN_REL[i])) return true;
        }
        return false;
    }

    /**
     * 一条相对条目在"数据根之内那一段"里的任意深度命中。
     *
     * <p>语义逐条对齐 {@link #hitPrefix(String, String)}（同一套"宁可多禁"的口径），只把锚点从
     * "整串开头"放宽到"<b>每一层的层首</b>"：</p>
     * <ul>
     *   <li>目录条目（末尾 {@code /}）：那一层目录本身（{@code files/logs}）或它的整棵子树
     *       （{@code files/logs/x}）都命中；</li>
     *   <li>其余条目：按前缀（{@code files/v4.db-wal}、{@code files/SFW-BOT.ir.bak-1} 命中）。</li>
     * </ul>
     *
     * @param rest 数据根<b>之内</b>那一段（已经过 {@link Acl#cmpPath}）
     * @param rel  {@link #HARD_BAN_REL} 里的原文条目
     */
    private static boolean hitDeep(String rest, String rel) {
        if (rest == null || rest.isEmpty() || rel == null || rel.isEmpty()) return false;
        String r = Acl.cmpPath(rel);
        if (r.endsWith("/")) {
            String plain = r.substring(0, r.length() - 1);      // "logs"
            if (rest.equals(plain) || rest.startsWith(r)) return true;
            return rest.indexOf("/" + plain + "/") >= 0 || rest.endsWith("/" + plain);
        }
        if (rest.startsWith(r)) return true;                    // 数据根下第一层
        return rest.indexOf("/" + r) >= 0;                      // 更深层：只认段首（前面必须是 /）
    }

    /** 前缀命中：目录前缀（末尾 {@code /}）比子树；其余按"相等或以它打头"比（前缀语义，宁可多禁）。 */
    private static boolean hitPrefix(String p, String key) {
        if (key == null || key.isEmpty()) return false;
        if (key.endsWith("/")) {
            return p.equals(key.substring(0, key.length() - 1)) || p.startsWith(key);
        }
        return p.equals(key) || p.startsWith(key);
    }

    /** 返回命中的那个允许根（原样、便于审计）；不在任何根之内返回 {@code null}。 */
    private static String underRoot(String normPath, File dataRoot) {
        List<String> roots = roots(dataRoot);
        String p = Acl.cmpPath(normPath);
        for (int i = 0; i < roots.size(); i++) {
            String r = roots.get(i);
            if (hitPrefix(p, Acl.cmpPath(r))) return r;
        }
        return null;
    }

    /** 上一次解析用的原文 + 结果（配置很少变；判定在热路径上，不做每次重解析）。 */
    private static volatile String rootsRaw;
    private static volatile List<String> rootsCache = Collections.emptyList();

    /** 允许根（归一化过；{@link #ROOT_PLACEHOLDER} 已换成数据根）。 */
    public static List<String> roots(File dataRoot) {
        String raw = fileRoots();
        String key = (raw == null ? "" : raw) + "\u001F" + (dataRoot == null ? "" : dataRoot.getAbsolutePath());
        List<String> c = rootsCache;
        if (key.equals(rootsRaw)) return c;
        List<String> l = new ArrayList<String>();
        String s = raw == null ? "" : raw;
        String rootPath = dataRoot == null ? "" : Acl.normPath(dataRoot.getAbsolutePath());
        String[] parts = s.split("[;,\\r\\n]+");
        for (int i = 0; i < parts.length; i++) {
            String one = parts[i] == null ? "" : parts[i].trim();
            if (one.isEmpty()) continue;
            if (one.indexOf(ROOT_PLACEHOLDER) >= 0) {
                if (rootPath.isEmpty()) continue;                 // 没有数据根 ⇒ 这一条无效（宁可少放）
                // 数据根按定义就是个目录：占位符一律展开成"末尾带斜杠"的写法，
                // 否则它会被当成一个文件键，整棵子树都进不来（写配置的人不该被这件事绊住）。
                String dir = rootPath.endsWith("/") ? rootPath : (rootPath + "/");
                one = one.replace(ROOT_PLACEHOLDER, dir);
            }
            String np = Acl.normPath(one);
            if (np.isEmpty()) continue;
            l.add(np);
        }
        rootsRaw = key;
        rootsCache = Collections.unmodifiableList(l);
        return rootsCache;
    }

    // ==================== 事实（给模型看） ====================

    /**
     * 档位名：{@code stranger} → {@code friend} → {@code invite} → {@code files} → {@code respect}
     * → {@code cap}。
     * <p>门槛取自当前配置（{@code favorFileMin} / {@code favorNoAngerMin}），所以主人改了键，
     * 事实块里的档位线跟着走。</p>
     */
    public static String tier(double favor) {
        if (favor >= (double) Favor.CAP) return TIER_NAME_CAP;
        if (favor >= (double) noAngerMin()) return TIER_NAME_RESPECT;
        if (favor >= (double) fileMin()) return TIER_NAME_FILES;
        if (favor >= (double) TIER_INVITE) return TIER_NAME_INVITE;
        if (favor >= (double) TIER_FRIEND) return TIER_NAME_FRIEND;
        return TIER_STRANGER;
    }

    /**
     * <b>事实块里那一行</b>："档位表 + 当前说话人的档位 + 这一档现在能为他做哪些只读的事"。
     *
     * <p>形状（键序固定，便于逐字断言）：</p>
     * <pre>
     *   "favorGate": {"enabled":true,"tier":"files",
     *                 "tiers":{"friend":100,"invite":300,"files":500,"respect":700,"cap":1000},
     *                 "table":["file.read","file.list","send.file","send.record"],
     *                 "lifted":["file.read"]}
     * </pre>
     * <p><b>{@code table} 原来叫 {@code ops}</b>（批 15 / 2026-09-26 改名）：同一个 {@code caller}
     * 对象里已经有一个 {@code "ops"}（= <b>这个人能用哪些 op</b>，{@code CtxBuild} 从
     * {@code auth.allowedOps} 取），而这一层装的是<b>这张放权清单表本身</b>（放行清单原文，
     * 与"这个人能不能用"无关）。同名不同义已经真实害人一次：{@code probe\ProbeHitRate} 的取数口
     * 被这个同名键抢过（它只好改成"只认 {@code caller} 自己那一层"）。改名 = 让两个键在事实块里
     * 一眼分得开。<b>清单的内容、顺序、门槛与 {@link #liftOps()} 这个方法名一个字都没动</b>，
     * 改的只有事实块里这个键。<br>
     * （{@code probe\} 的取数口与它那段注释归测试线按这次口径变更更新 —— 写者不动 probe。）</p>
     * <p>{@code lifted} = 这一档<b>此刻真的</b>给他放行的 op（放行清单 ∩ 已到档）；
     * 主人与她本人（恒全权）那一列永远是空的 —— 他们不受这道闸影响，能做的事由
     * 既有的 {@code ops} 那一行（{@code ALL}）说了算。</p>
     */
    public static JsonObject facts(Caller c) {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled());
        double f = c == null ? 0.0D : c.favor();
        o.addProperty("tier", tier(f));
        JsonObject t = new JsonObject();
        t.addProperty(TIER_NAME_FRIEND, TIER_FRIEND);
        t.addProperty(TIER_NAME_INVITE, TIER_INVITE);
        t.addProperty(TIER_NAME_FILES, fileMin());
        t.addProperty(TIER_NAME_RESPECT, noAngerMin());
        t.addProperty(TIER_NAME_CAP, (long) Favor.CAP);
        o.add("tiers", t);
        JsonArray ops = new JsonArray();
        List<String> table = liftOps();
        for (int i = 0; i < table.size(); i++) ops.add(table.get(i));
        // ★ P3（批 15 / 2026-09-26）：键名 ops → table。同一个 caller 对象里还有一个 "ops"
        //   （= 这个人能用的 op，见 CtxBuild），两个同名键装的是两件事，已经害过测试线一次
        //   （ProbeHitRate 的取数口被抢）。**只改键名**：清单内容、顺序、liftOps() 都不动。
        o.add("table", ops);
        JsonArray lifted = new JsonArray();
        for (int i = 0; i < table.size(); i++) {
            String op = table.get(i);
            if (lift(op, c) != null) lifted.add(op);
        }
        o.add("lifted", lifted);
        return o;
    }

    // ==================== 形态判定（出口用） ====================

    /**
     * 这个写法像不像"本机文件"（像 ⇒ 出口必须先按当轮调用者过一遍 {@code File} 域）。
     *
     * <p><b>与 {@code Api.localPathOf} 同一套认法，但只认形态、不改写</b>：{@code http(s)://} /
     * {@code base64://} 返回 {@code false}；{@code file:} / {@code C:\x} / {@code C:/x} / {@code /x} /
     * {@code \x} 返回 {@code true}。认不准一律按<b>本机文件</b>处理（宁可多判一次，不可漏判 ——
     * 漏判就是"未经 File 域就把盘符交出去"）。</p>
     */
    public static boolean looksLocal(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) return false;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("base64://")) return false;
        if (low.startsWith("file:")) return true;
        if (s.length() >= 3 && s.charAt(1) == ':' && (s.charAt(2) == '\\' || s.charAt(2) == '/')) return true;
        return s.startsWith("/") || s.startsWith("\\");
    }

    private FavorGate() { }
}
