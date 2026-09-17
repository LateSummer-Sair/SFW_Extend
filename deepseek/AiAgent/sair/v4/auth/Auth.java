package sair.v4.auth;

import java.io.File;

import sair.v4.Conf;
import sair.v4.kit.Out;

/**
 * 权限面（基板⑥）。<b>这是一条判定：资源 ACL 账本 + 按资源类型的默认分配 + 例外条目突破默认。</b>
 *
 * <h3>判定的三个维度</h3>
 * <ul>
 *   <li><b>主体</b>（{@link Caller}）：{@code MASTER}（恒 {@code RWX}，不受任何条目影响）/
 *       {@code SYSTEM}（她自己的自主行为）/ {@code ALLUSER}（伞身份，含 {@code User<QQ>} 与
 *       {@code Group<群号>} 两个细分）；</li>
 *   <li><b>资源</b>（{@link Res}）：{@code A}（SFW 路径之外的本机文件与系统进程）/
 *       {@code B}（SFW 路径之内的文件）/ {@code C}（她自己的运行时脚手架：库、工具、{@code dataDir}）/
 *       {@code E}（NapCat 与 SFW 的对外交互）；</li>
 *   <li><b>操作位</b>（{@link Bits}）：{@code R} 读 / {@code W} 写 / {@code X} 执行·对外，
 *       空位 = 拒绝。</li>
 * </ul>
 *
 * <h3>判定从哪来</h3>
 * <p>只有数据根的 {@code perms.json}（{@link Acl}，{@code {"version":3,"entries":[…]}}）这一本账。
 * 一条例外都没命中时按<b>资源类型的默认分配</b>给位：{@code Acl.defaultText()}（{@code ALLUSER}）
 * 与 {@code Acl.systemText()}（{@code SYSTEM}）；命中例外就<b>直接用例外声明的位</b>（例外突破默认）。
 * 主体层级 {@code User<QQ>(3) > Group<群号>(2) > ALLUSER(1)}，同级里后面的覆盖前面的。</p>
 *
 * <h3>P9b-2：旧"档位"体系（{@code PermTable} / {@code AuthView} / 好感度门禁）已整批删除</h3>
 * <p>本类原来还挂着一整套旧档位 facade（{@code table()/levelOf()/keyOf()/gateCall()/effectiveLevel()/
 * describe()/validLevel()/napcatLevel()/defaultFor()/check()/checkTool()/matrix()/saveTable()}），
 * 判据是 {@code MASTER} 与 {@code AFFECTION:<N>}（好感度门槛）。现在：
 * {@code PermTable} 整类、{@code skill.AuthView} 整接口、以及上述方法<b>全部从 src 删除</b>
 * —— 好感度不决定任何权限，旧档位表也不存在了（{@code notes/acl-decisions.md} D13/D34）。
 * 于是这里只剩下 ACL 面。</p>
 */
public final class Auth {

    /**
     * 旧档位面的拒绝前缀（"{@code [auth] 权限阻断：}"）—— <b>只保留给识别面</b>（兼容老文案/老断言）。
     *
     * <p><b>产出面不再用它</b>（{@code notes/acl-decisions.md} D12 ★4① / D13 ⑤：产出面一律
     * {@link Acl#DENY_PREFIX} {@code [权限阻断] }）。识别面（{@code tool.Registry.isDenyText}、
     * {@code agent.Loop.deniedResult/failed}）同时认这两种形态，所以历史写死这个字面量的文案
     * 仍会被标成 {@code ok=0}；这里保留常量就是为了那张兼容识别表，不是给产出用的。</p>
     */
    public static final String DENY_PREFIX = "[auth] 权限阻断：";

    private final Conf conf;
    private final Out out;

    /** 账本（懒装载，进程内缓存一份）。 */
    private volatile Acl acl;

    /**
     * @param favor 好感度存储：<b>只保留形参</b>（老调用点/探针仍在用三参构造器，{@code Boot} 第④步就是）。
     *              好感度已不参与任何权限判定（P9b-1 删掉最后一条门禁路径），所以本类不再持有它 ——
     *              要读好感度请走 {@code Host.favor()} / {@code Favor} 本身。
     */
    public Auth(Conf conf, Favor favor, Out out) {
        this.conf = conf;
        this.out = out;
    }

    /**
     * 装载权限面（基板装配第④步；控制台 {@code config reload} 也会调它）。
     *
     * <p>P9b-2：旧实现读的是旧档位表（{@code PermTable.load}）—— 那个类已删除。现在的唯一权限面是
     * <b>ACL 账本</b>（数据根的 {@code perms.json}），它是<b>懒装载</b>的：第一次判定时由 {@link #acl()}
     * 读盘。所以这里保持两件事：<b>账本已经装过就重读一次</b>（这就是 {@code config reload} 的语义：
     * 把外挂文件的改动读进来），<b>还没装过就什么都不做</b>（首次用到时自然会读，启动期的输出
     * 与"只读装载"完全一致）。</p>
     *
     * <p><b>绝不写盘</b>：旧路径会把"缺行"补进 {@code perms.json} 并落盘 —— 那会把 ACL 账本覆盖成旧格式、
     * 条目全丢（D11）。这条路径已不存在。</p>
     */
    public void init() {
        if (acl != null) reloadAcl();
    }

    // ==================== ACL 面（唯一判定） ====================

    /** 权限账本文件（数据根下的 {@code perms.json}）。 */
    public File aclFile() {
        File root = conf == null ? null : conf.root();
        return Acl.fileOf(root);
    }

    /** 账本（懒装载；外部手改了文件之后用 {@link #reloadAcl()}）。 */
    public Acl acl() {
        Acl a = acl;
        if (a == null) {
            synchronized (this) {
                a = acl;
                if (a == null) {
                    a = Acl.load(aclFile(), out);
                    acl = a;
                }
            }
        }
        return a;
    }

    /** 重新读账本（返回同一个账本对象的新内容）。 */
    public Acl reloadAcl() {
        Acl a = Acl.load(aclFile(), out);
        acl = a;
        return a;
    }

    /** 主体对资源的有效位（{@code RWX} 的整数掩码，见 {@link Bits}）。 */
    public int bits(Caller c, Res r) {
        return acl().bitsOf(c, r);
    }

    /** 资源判定：放行返回 {@code null}，否则返回可读理由（缺哪一位 / 该资源类型上限 / 当前有效位）。 */
    public String allowRes(Caller c, Res r, char need) {
        return acl().allow(c, r, need);
    }
}
