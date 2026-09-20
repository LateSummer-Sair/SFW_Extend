package sair.v4.auth;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sair.v4.Conf;
import sair.v4.kit.Out;
import sair.v4.tool.Registry;

/**
 * 权限面（基板⑥）。<b>只有一条判定：身份 × op → Ban / Run / 空</b>，账本是<b>按归属分散</b>的权限文件
 * （内核见 {@link Acl}）：基板内置工具写数据根下的 {@code perms-core.jsonc}，每个技能可以在自己的目录里写
 * 一份 {@code perms.jsonc}（只写它自己提供的工具）。技能目录里没有那份文件 = 该技能全部 op 对
 * {@code ALLUSER} 一律不授权。
 *
 * <h3>判定的两个维度</h3>
 * <ul>
 *   <li><b>身份</b>（{@link Caller}）：{@code MASTER} 与她本人 {@code SYSTEM} <b>恒全权</b>
 *       （全部技能的使用与管理，不受账本影响）；{@code ALLUSER}（伞身份，含
 *       {@code user:&lt;QQ&gt;} 与 {@code group:&lt;群号&gt;} 两个细分）<b>一律按账本判</b>；
 *   </li>
 *   <li><b>op</b>：{@code 工具名} 或 {@code 工具名.动作名}（{@link Ops} 里的清单）。
 *       资源没有位 —— 资源对主人与她本人完全可见可改可执行，对 {@code ALLUSER} 是黑盒，
 *       只能经技能（工具）这条路触碰。</li>
 * </ul>
 *
 * <h3>清单从哪来</h3>
 * <p>{@link #ops()} 是全部 op 的注册表：技能在装载期用 {@link OpList} 声明，
 * 内置工具在注册时声明。它只用于"有哪些 op 可管"的诊断与释义，<b>不再是"默认清单"</b> ——
 * 没在权限文件里出现的 op 就是未授权。</p>
 *
 * <p><b>工具归属</b>：{@link #bind(sair.v4.tool.Registry, File)} 把工具注册表接进来，
 * 多源装载才知道"哪个 op 属于哪个技能"（技能文件里写了不属于它的 op 会被忽略）；拿不到注册表
 * （早期或桩）时退回<b>只读 core</b>。</p>
 *
 * <p><b>绝不写盘</b>：装载、判定、查看都不写；只有主人的授权命令落盘。</p>
 */
public final class Auth {

    /**
     * 旧档位面的拒绝前缀（"{@code [auth] 权限阻断：}"）—— <b>只保留给识别面</b>
     * （兼容历史文案）：产出面一律用 {@link Acl#DENY_PREFIX}。
     */
    public static final String DENY_PREFIX = "[auth] 权限阻断：";

    private final Conf conf;
    private final Out out;

    /** 全部 op 的清单（技能与内置工具声明）。 */
    private final Ops ops = new Ops();

    /** 账本（懒装载，进程内缓存一份）。 */
    private volatile Acl acl;

    /** 工具注册表（拿"工具 → 技能"的归属；可为 null = 还没装配）。 */
    private volatile Registry tools;
    /** 技能根（多源装载要扫它下面的 {@code perms.jsonc}；可为 null = 退回 {@code conf.skillsDir()}）。 */
    private volatile File boundSkillsDir;

    /**
     * @param favor 好感度存储：<b>只保留形参</b>（好感度不参与任何权限判定）
     */
    public Auth(Conf conf, Favor favor, Out out) {
        this.conf = conf;
        this.out = out;
    }

    /**
     * 接上工具注册表与技能根（基板装配第⑥步建好注册表之后调一次）。
     *
     * <p>注册表是<b>活对象</b>：技能装载期往同一个对象里注册工具，所以这里接一次就够 ——
     * {@link #toolOwners()} 每次装载权限文件时现取归属。技能重扫之后要重新读权限文件，
     * 敲 {@code ai/perm reload} 或由装配方调 {@link #reloadAcl()}。</p>
     *
     * @param tools     工具注册表（可为 null = 拿不到归属，退回只读 core）
     * @param skillsDir 技能根（可为 null = 用配置里的 {@code skillsDir}）
     */
    public void bind(Registry tools, File skillsDir) {
        this.tools = tools;
        this.boundSkillsDir = skillsDir;
    }

    /**
     * 装载权限面（基板装配第④步；控制台 {@code config reload} 也会调它）。
     *
     * <p>账本是懒装载的：第一次判定时读盘。所以这里保持两件事 —— 已经装过就重读一次
     * （这就是 {@code config reload} 的语义：把外挂文件的改动读进来），还没装过就什么都不做。</p>
     */
    public void init() {
        if (acl != null) reloadAcl();
    }

    /** 基板权限文件（数据根下的 {@link Acl#CORE_FILE_NAME}，{@code data\perms-core.jsonc}）。 */
    public File aclFile() {
        File root = conf == null ? null : conf.root();
        return Acl.fileOf(root);
    }

    /** 技能根（多源装载扫它下面的技能目录；配置里的 {@code skillsDir}）。 */
    public File skillsDir() {
        File d = boundSkillsDir;
        if (d != null) return d;
        return conf == null ? null : conf.skillsDir();
    }

    /**
     * 工具名 → 技能名（<b>只含技能提供的工具</b>；内置工具不在表里）。
     * <p>拿不到注册表 = {@code null}：多源装载退回"只读 core"（技能权限文件一律不读）。</p>
     */
    public Map<String, String> toolOwners() {
        Registry r = tools;
        return r == null ? null : r.toolOwners();
    }

    /** op 清单（装载期写入，运行期只读）。 */
    public Ops ops() { return ops; }

    /** 全部 op 名。 */
    public List<String> allOps() { return ops.all(); }

    /** 账本（懒装载；外部手改了文件之后用 {@link #reloadAcl()}）。 */
    public Acl acl() {
        Acl a = acl;
        if (a == null) {
            synchronized (this) {
                a = acl;
                if (a == null) {
                    a = loadAcl();
                    acl = a;
                }
            }
        }
        return a;
    }

    /**
     * 多源装载：core（+ 旧统一表）+ 每个技能目录里的 {@code perms.jsonc}。
     *
     * <p><b>这一处就是 knownOps 的接线</b>（规格 §2.5-1 的"②波次必做的一行接线"）：它是
     * {@link Acl#loadMerged} 在本基板里的<b>唯一调用方</b>，所以把"当前已知的 op 清单"从这里一并交下去，
     * 每一条经 {@code Auth} 的装载（首次懒装载 / {@link #reloadAcl()} / {@code ai/perm reload} /
     * 技能重扫收口）拿到的账本<b>一定</b>带着清单 —— 工具级键（{@code ["weather"]}）才覆盖得到
     * "装载时该工具已注册的动作"，而不是退化成"只覆盖表里别处授予过的动作"。</p>
     */
    private Acl loadAcl() {
        return Acl.loadMerged(aclFile(), skillsDir(), toolOwners(), aclKnownOps(), out);
    }

    /**
     * 交给 {@link Acl} 的"已知清单"（{@link #loadAcl()} 的载荷）：注册表侧的全部 op
     * <b>再加每个工具名本身</b>。
     *
     * <p>为什么还要工具名：工具级规则键（{@code "Run": { ["weather"]: [...] }}）的键就是<b>工具自身</b>
     * 这一条（规格 §2.5-1 的兜底口径把它与"已知的动作"并列）。{@code Acl.inScope} 在装载期对
     * <b>技能文件</b>里的键做"必须在清单里"的把关（越界忽略 + 点名），若清单里只有
     * {@code 工具.动作} 而没有被声明的工具名，那么<b>多动作工具</b>（{@code memory} /
     * {@code send} / {@code weather.report} 这种）的工具级条目会在装载期被整条丢掉 ——
     * 那样接线的效果正好反了（本该"覆盖已注册的动作"，却连这一条都不进表）。</p>
     *
     * <p>加工具名<b>不会放宽任何判定</b>：<b>动作级</b>的覆盖仍然逐个查
     * {@code 工具.动作} 是否真在清单里（{@code weather.nowhere} 照旧不匹配 ⇒ 未授权），
     * 工具名的用处只是让"这一把工具"这条键能被认出来（归属那一关照旧要过）。</p>
     */
    private List<String> aclKnownOps() {
        List<String> l = new ArrayList<String>();
        for (String op : ops.all()) if (!l.contains(op)) l.add(op);
        for (String t : ops.tools()) if (!l.contains(t)) l.add(t);
        return l;
    }

    /** 重新读账本（返回同一个账本对象的新内容）。 */
    public Acl reloadAcl() {
        Acl a = loadAcl();
        acl = a;
        return a;
    }

    /** 身份能不能用这个 op：放行返回 {@code null}，否则返回可直接回给模型的拒绝原文。 */
    public String allow(Caller c, String op) {
        try {
            Acl a = acl();
            if (a == null) return Acl.DENY_PREFIX + "权限面没有装配（acl == null），按拒绝处理：" + op;
            return a.allow(c, op);
        } catch (Throwable t) {
            // 判定出任何事都落到这里 = 拒（fail-closed），绝不让异常变成放行
            return Acl.DENY_PREFIX + "权限判定异常，按拒绝处理：" + op + "（" + t + "）";
        }
    }

    /** 同 {@link #allow(Caller, String)} 的布尔版。 */
    public boolean allowed(Caller c, String op) { return allow(c, op) == null; }

    /** 这个身份在全部 op 里能用哪些（给事实块与控制台清单用）。 */
    public List<String> allowedOps(Caller c) {
        List<String> all = allOps();
        try {
            Acl a = acl();
            if (a == null) return new ArrayList<String>();
            return a.opsFor(c, all);
        } catch (Throwable t) {
            return new ArrayList<String>();
        }
    }
}
