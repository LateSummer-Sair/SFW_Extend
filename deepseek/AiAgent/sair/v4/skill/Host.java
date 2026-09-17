package sair.v4.skill;

import com.google.gson.JsonObject;

import java.util.List;

import sair.v4.ai.DeepSeek;
import sair.v4.auth.Caller;
import sair.v4.kit.Out;
import sair.v4.prompt.Inject;
import sair.v4.prompt.Prompts;
import sair.v4.qq.Api;
import sair.v4.store.Store;

/**
 * 技能宿主（基板的对外能力面）。
 * <p>技能通过它调度基板已有资源：存储、模型、NapCat、提示词、上下文注入、权限、工具、控制台输出。
 * 这是一个<b>只读能力面</b>：技能改不动权限判定、改不动基板骨架。</p>
 */
public interface Host {

    /** 当前调用者（谁 + 在哪说话 + 好感度）。 */
    Caller caller();

    /** 本技能的名字（= 技能文件夹名）。 */
    String skillName();

    /**
     * 本技能注册后的<b>英文工具名</b>（基板自动英文化后的名字，可能和 md 里声明的不一样）。
     * 权限注册表用「技能文件夹名.工具名」当键，所以这里要拿注册名而不是声明名。
     */
    default String toolName() { return ""; }

    /** 本技能自己的文件夹（读自己的 md / prompt.md / 数据文件用）。 */
    java.io.File skillDir();

    /**
     * 机器人在某个群里是不是管理员/群主（审核这类动作要据此决定能不能执法）。
     * NapCat 未连接时返回 false。
     */
    boolean selfIsGroupAdmin(long groupId);

    /** 当前会话键（console / qq:123 / group:456）。 */
    String session();

    /** 记忆/偏好作用域键（群内=群号，私聊=QQ）。 */
    String scopeKey();

    /**
     * 这一轮是不是<b>子 Agent</b>在跑（而不是某个会话的主回合）。
     *
     * <p>用途：子 Agent 看不到会话的消息流，因此"对某条消息做有副作用的标记"这类事
     * <b>只能提议、不能直写</b>（写者唯一 = 该会话的主回合）。默认 false，老技能不受影响。</p>
     */
    default boolean subagent() { return false; }

    /** 子 Agent 的标签（{@code sub-<n>}）；主回合为空串。归属字段 {@code by_agent} 用它。 */
    default String agentLabel() { return ""; }

    ConfView conf();

    Out out();

    /** 六库通用接口（读写）。 */
    Store store();

    /**
     * 库注册表（只读能力面）：<b>"世界上有哪些表"的唯一答案</b> ——
     * 基板环境库（{@code dialog}/{@code grouplog}）+ 各插件在装载期声明的业务表。
     *
     * <p>用途：插件想知道"某张表有哪些列"（比如目标库必须带 {@code scope}/{@code scope_id} 才可用），
     * 而不是靠一份写死的库清单。查不到的库返回 {@code null} —— "没有这个库"不是异常。</p>
     */
    default sair.v4.store.Libs libs() {
        Store s = store();
        return s == null ? null : s.libs();
    }

    /** DeepSeek 客户端。 */
    DeepSeek ai();

    /** NapCat 动作接口（带权限闸门；未连接时每个动作返回 error=Napcat 没有连接）。 */
    Api napcat();

    boolean napcatConnected();

    /**
     * 最近入站消息里某个图片 file 值对应的<b>直连 URL</b>（事件里本来就带的那个）。
     * <p>跨机部署时 NapCat 给的本地路径本机读不到，这条 URL 是唯一能取到图的线索；
     * 登记簿是基板在收到消息时维护的短期缓存（进程内、不落盘），查不到返回空串。</p>
     */
    String inboundImageUrl(String file);

    /** 最近几条入站图片的直连 URL（新的在前）；没有就返回空表。 */
    List<String> recentInboundImageUrls(int limit);

    Prompts prompts();

    Inject inject();

    // P9b-1 删除：原来的 gate(String op)（技能动作级复核：this.auth().gate(skillName(), toolName(), op, caller())）
    // —— 它背后是"好感度门禁"（旧 Auth.check → "需要好感度 ≥ N"），而好感度不再决定任何权限。
    // P9b-2 删除：本接口原来的 auth()（旧档位视图 sair.v4.skill.AuthView）整条删除 ——
    //   AuthView、PermTable 与 Auth 的旧档位 facade 已随旧档位体系一起从 src 消失（见 notes\acl-decisions.md
    //   D13/D34）；技能侧零调用（P12 早已清掉旧用法），所以不保留返回类型换成 Auth 的过渡口。
    // 资源判定唯一入口是下面的 need*/needPath/needDb/needPlatform，判定主体/账本见 acl()。

    /** 好感度视图（可读不可改）。 */
    FavorView favor();

    // ==================== 资源判定（ACL：唯一一条判定） ====================

    /**
     * 基板装配的权限账本（{@code perms.json} + 唯一一条判定）。
     * <p>宿主没装配权限面（或不是基板实现）时返回 {@code null} —— 调用方一律按拒绝处理。</p>
     *
     * <p><b>P9b-2</b>：本方法原来是"经 {@code AuthView auth()} 中转"的默认实现（{@code auth() instanceof Auth}）。
     * 旧档位视图 {@code AuthView} 已整条删除，所以默认实现改为<b>直接返回 {@code null}</b>；
     * 基板实现（{@code Boot} 里的宿主）覆写它，返回自己装配的 {@code sair.v4.auth.Auth}。
     * 老宿主 / 桩实现不覆写就拿到 {@code null} ⇒ 一切 {@code need*} 走 fail-closed 分支（行为与过去一致）。</p>
     */
    default sair.v4.auth.Auth acl() { return null; }

    /**
     * 这一次调用的<b>判定主体</b> = 当轮绑定的主体 {@link sair.v4.ctx.Ctx#caller()}。
     *
     * <p><b>为什么不读 {@link #caller()} 或 {@code turn.caller()}</b>（这一条决定成败，
     * 见 {@code notes/acl-wiring-packages.md} §P1-a）：</p>
     * <ul>
     *   <li>工具调用：{@code Ctx} 绑的是<b>触发者</b> → 判定用触发者的位（"她能替这个人碰什么资源"看这个人）；</li>
     *   <li>钩子 / 扩展点：{@code Ctx} 绑的是 {@code Caller.systemActor()} → 判定用 <b>SYSTEM</b> 的位
     *       （自动禁言/自动审批是"她自己的自动策略"，按触发者判会被"陌生人的默认 R"当场拒掉）；</li>
     *   <li>{@link #caller()} 仍然是<b>真实发送者</b> —— 那是给她做上下文用的（"谁在说话"），
     *       两件事不要混。</li>
     * </ul>
     */
    default Caller subject() { return sair.v4.ctx.Ctx.caller(); }

    /**
     * 资源判定：<b>放行返回 {@code null}，否则返回可直接回给模型的拒绝原文</b>。
     *
     * <pre>
     *   String deny = h.needPath(dst, 'W');
     *   if (deny != null) return deny;      // 技能把这句话原样回给用户
     * </pre>
     *
     * <p>拒绝文案 = 「缺 X 位 —— 」+ 账本给的原文（点明资源、该资源类型上限、当前有效位；
     * 受保护资源还会点明"仅 MASTER/SYSTEM"）。没有主体 / 没有权限面同样拒绝（fail-closed）。</p>
     *
     * @param r   要碰的资源（{@link sair.v4.auth.Res}）
     * @param bit 需要哪一位：{@code 'R'} 读 / {@code 'W'} 写 / {@code 'X'} 执行·对外
     */
    default String need(sair.v4.auth.Res r, char bit) {
        String tag = needTag(bit);
        sair.v4.auth.Auth a = acl();
        if (a == null) {
            // 产出面的前缀一律用 Acl.DENY_PREFIX（"[权限阻断] "）：旧串 Auth.DENY_PREFIX
            // （"[auth] 权限阻断："）只保留给识别面兼容，不再出现在任何产出文案里（D12 ★4 / D13 ⑤）。
            return tag + sair.v4.auth.Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + r;
        }
        String deny = a.allowRes(subject(), r, bit);
        return deny == null ? null : (tag + deny);
    }

    /** 文件 / 目录：路径资源（{@code SFW 根内 = B}、{@code 根外 = A}，自动判类）。 */
    default String needPath(String path, char bit) { return need(sair.v4.auth.Res.path(path), bit); }

    /** 同 {@link #needPath(String, char)}（{@link java.io.File} 版）。 */
    default String needPath(java.io.File f, char bit) { return need(sair.v4.auth.Res.path(f), bit); }

    /** 库 / 内存态 / 工具运行时（C 类）：读该库前 {@code 'R'}、写前 {@code 'W'}。 */
    default String needDb(String lib, char bit) { return need(sair.v4.auth.Res.db(lib), bit); }

    /** QQ 平台动作（E 类）：一律 {@code 'X'}（对外 = 执行）。 */
    default String needPlatform(String action) { return need(sair.v4.auth.Res.platform(action), 'X'); }

    // ==================== 自主入口（她自发的动作，判定主体固定 SYSTEM） ====================

    /**
     * <b>自主入口：她自发的动作</b> —— 判定主体<b>固定为 {@code SYSTEM}</b>，
     * 然后<b>就按 SYSTEM 自己的位判</b>（{@code A=R} {@code B=RW} {@code C=RWX} {@code E=RWX} {@code T=RWX}），
     * <b>不按资源类别额外设限</b>。
     *
     * <p><b>为什么必须有这个入口</b>（模型见 {@code notes/acl-decisions.md}：11 总纲 / D2）：
     * 工具通路的判定主体是<b>触发者</b>（{@link #subject()} = {@code Ctx.caller()}），
     * 而"她自发要干的事"—— 收表情、<b>发表情</b>、出网取信息、读库写库、记忆维护、自发说话 ——
     * 经常正是<b>在别人的回合里</b>被触发的。走工具通路就会按那个陌生人判，
     * 被"ALLUSER 的默认位"当场拒掉。可这些动作的主意是<b>她自己的</b>：
     * 按冻结模型"她自发的一切动作 = 大脑发出的动作 ⇒ 天然 SYSTEM 位面"。
     * 所以自主动作走这个入口，主体钉死 {@code SYSTEM}，与"谁在说话"彻底解耦。</p>
     *
     * <p><b>为什么不按类别设限</b>（主人 2026-09-15 21:2x 定案，覆盖此前"E 类一律拒绝 / T 类不适用"
     * 的写法）：主人原话 ——「收<b>发</b>表情这个动作都是属于 SYSTEM 位面的，因为<b>没人要求她这么做</b>」，
     * 并明确「<b>基板不需要验证语义，因为 SYSTEM 是她自身，由 SYSTEM 验证语义合理</b>」。
     * 「这一次动作到底是不是她自发的」<b>由她判断</b>，基板<b>不替她裁决、不验证语义</b>：
     * 基板只做机械三件事 —— <b>按位放行/拒绝、记账、代执行</b>。所以这里对资源类别一律不设限，
     * 放不放得行只由 {@code SYSTEM} 那一行的位决定。</p>
     *
     * <h3>规则（只有两条，都是 fail-closed）</h3>
     * <ol>
     *   <li>{@code r == null} → <b>拒绝</b>（没有资源描述就没有可放行的动作）；</li>
     *   <li>判定过程出任何事（权限面没装配 {@code acl() == null}、{@code Auth}/账本抛异常……）→ <b>拒绝</b>
     *       —— 绝不让"抛了异常"变成放行，与 {@link #need(sair.v4.auth.Res, char)} 同口径。</li>
     * </ol>
     * <p>另外，判定主体固定 = {@link Caller#systemActor()}：本方法<b>不读</b> {@code Ctx.caller()} ——
     * 那个槽位装的是"别人"，读了就等于把"她自己的主意"重新绑回请求者面
     * （那正是这个入口要解决的问题）。通配资源（{@code Res.all()}）不需要单开一档：
     * {@code Acl.bitsOf} 对非主人恒 {@code NONE}，所以她拿通配资源也是拒 —— 位表自己说的话。</p>
     *
     * <p><b>它不给调用者任何额外权限</b>（这一条决定它安不安全）：它用的是
     * <b>{@code SYSTEM} 自己的位</b>，<b>谁调用都一样</b> —— 主人调用不会变成 MASTER、
     * 陌生人在别人的回合里调用也不会变成那个陌生人。所以技能<b>借它升级不到 {@code SYSTEM} 之外</b>：
     * 它最多把她自己的自主能力（{@code A=R}、{@code B=RW}、{@code C=RWX}、{@code E=RWX}、{@code T=RWX}）
     * 借出来，<b>接不过去主人那一档</b>；而 {@code perms.json} 账本、{@code mem:acl}（授权动作本身）、
     * {@code get_cookies} 这类<b>第一层受保护资源连她也不给</b>（{@code Acl.masterOnlyRes}），
     * 走这个入口一样拒。</p>
     *
     * <p><b>行归属不在这里，而且基板现在也没有这样一道门</b>：本方法只做"按位反射式放行/拒绝"这一件事
     * —— 它既不看行、也不看内容（拿不到、也不该拿语义：语义由她判）。"她只能动<b>自己那一行</b>"
     * （{@code sender} = 自己 / 只写自己的 {@code user} 作用域 / 只碰她自己的记忆行）
     * <b>目前仍由调用方与存储层各自负责</b>—— 现状就是技能自己按 {@code scope}/{@code scope_id}
     * 约束自己的行。基板这里<b>不宣称</b>有一层"行归属门"在替谁兜这件事（本轮不建那一层）。</p>
     *
     * <p><b>判定路径与 {@link #need(sair.v4.auth.Res, char)} 完全同一条</b>
     * （{@link #acl()} → {@code Auth.allowRes} → {@code Acl.allow} → {@code Acl.bitsOf}）：
     * 只有"主体"这一格从 {@link #subject()} 换成 {@code systemActor()}，
     * 所以例外条目 / 受保护资源 / 默认分配三件事的口径一字不差
     * （{@code SYSTEM} 名下的例外条目照旧生效）。</p>
     *
     * <pre>
     *   String deny = h.selfDb("memory", 'W');               // 她的记忆维护：按 SYSTEM 判（放行 → null）
     *   if (deny != null) return deny;                       // 拒绝原文可原样回给模型
     *   String deny2 = h.selfAct(Res.platform("set_group_card"), 'X');   // 她自发改自己的名片
     * </pre>
     *
     * @param r   她要碰的资源（{@link sair.v4.auth.Res}）
     * @param bit 需要哪一位：{@code 'R'} 读 / {@code 'W'} 写 / {@code 'X'} 执行·对外
     * @return <b>放行返回 {@code null}</b>（与 {@code need*} 一致）；否则返回可直接回给模型的拒绝原文
     *         （含 {@link sair.v4.auth.Acl#DENY_PREFIX}，所以 {@code tool.Registry.isDenyText} 认得出）
     */
    default String selfAct(sair.v4.auth.Res r, char bit) {
        String tag = needTag(bit);
        if (r == null) {
            // 规则①：没有资源描述 = 拒（fail-closed）
            return tag + sair.v4.auth.Acl.DENY_PREFIX + "自主入口缺资源描述 —— 按拒绝处理（fail-closed）";
        }
        String deny;
        try {
            // 规则②：这一整段出任何事都落到下面的 catch = 拒（含"权限面没装配"这一格）
            sair.v4.auth.Auth a = acl();
            deny = a == null
                    // 与 need(...) 逐字同一条 fail-closed 文案：权限面没装配 = 拒，绝不静默放权
                    ? (sair.v4.auth.Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + r)
                    : a.allowRes(sair.v4.auth.Caller.systemActor(), r, bit);
        } catch (Throwable t) {
            return tag + sair.v4.auth.Acl.DENY_PREFIX + "自主入口判定异常，按拒绝处理：" + r + "（" + t + "）";
        }
        return deny == null ? null : (tag + deny);
    }

    /**
     * 她的自主动作碰一个<b>路径</b>（{@code SFW 根之内 = B}、{@code 根之外 = A}，自动判类）：
     * 就是 {@code selfAct(Res.path(path), bit)}。
     *
     * <p>薄封装只为让调用点不用自己判类别（判错类别会把 A 类的动作送进 B 类的口径）；
     * 语义、规则、拒绝文案全部见 {@link #selfAct(sair.v4.auth.Res, char)}。</p>
     */
    default String selfPath(String path, char bit) {
        return selfAct(sair.v4.auth.Res.path(path), bit);
    }

    /**
     * 她的自主动作碰一个<b>库 / 内存态</b>（C 类）：就是 {@code selfAct(Res.db(lib), bit)}。
     *
     * <p>薄封装同上：<b>本方法只管位</b>。"只能动自己那一行"的作用域约束<b>由调用方按现有口径自理</b>
     * （现状就是技能自己按 {@code scope}/{@code scope_id} 约束），基板<b>没有</b>这样一道门
     * （见 {@link #selfAct(sair.v4.auth.Res, char)} 的"行归属不在这里"一段）。</p>
     */
    default String selfDb(String lib, char bit) {
        return selfAct(sair.v4.auth.Res.db(lib), bit);
    }

    /** 主体对资源的<b>有效位</b>（{@code RWX} 掩码，见 {@link sair.v4.auth.Bits}）；没有权限面 = {@code NONE}。 */
    default int bits(sair.v4.auth.Res r) {
        sair.v4.auth.Auth a = acl();
        return a == null ? sair.v4.auth.Bits.NONE : a.bits(subject(), r);
    }

    /** 拒绝文案的位名标签（{@code "缺 W 位 —— "}）。 */
    static String needTag(char bit) {
        return "缺 " + Character.toUpperCase(bit) + " 位 —— ";
    }

    /** 工具视图（可查可调，不可注册/删除）。 */
    ToolView tools();

    /** 调用任意基板/技能工具（含权限复核）。 */
    String call(String tool, JsonObject args);

    /** 往当前会话输出一段话（本地→控制台，QQ→群/私聊；NapCat 未连接则只进控制台）。 */
    void say(String text);

    /** 派一个子 Agent 任务（基板④）；async=true 立即返回任务票。 */
    JsonObject spawn(String task, List<String> tools, boolean async);

    /**
     * 派一个子 Agent，并给它这段"变化部分"提示词（角色/边界/输出格式）。
     * 公共部分在外挂文件 {@code prompts/subagent.md}，不用在这里重复。
     */
    JsonObject spawn(String task, List<String> tools, boolean async, String brief);

    /** 同上，并指定子 Agent 用的模型（视觉兜底：用会看图的模型）。 */
    JsonObject spawn(String task, List<String> tools, boolean async, String brief, String model);

    /**
     * 同上，并声明这一单"是不是替机器人说一句话"。
     *
     * <p>{@code reply=true}：子 Agent 写成什么就由<b>宿主替它发到当前会话</b>
     * （它自己发过、或回 {@code <silent>} 时不会重复发）—— 技能派的"接话/通报"用这个；
     * 宿主投递属于机器人自己说话，不受发送工具权限门禁，所以任何人都能得到回复。</p>
     * <p>{@code reply=false}（默认）：结论只回灌给主 Agent，<b>用户看不到</b>——
     * 内部材料（查资料、蒸馏、汇总）一律走这条路。</p>
     */
    default JsonObject spawn(String task, List<String> tools, boolean async, String brief, String model, boolean reply) {
        return spawn(task, tools, async, brief, model);
    }

    /**
     * 派一个子 Agent，并<b>显式指定它的主体</b>（延迟执行的"能力继承"入口）。
     *
     * <p><b>为什么需要它</b>：{@link #spawn(String, List, boolean, String, String, boolean)} 派出去的子 Agent
     * 继承的是<b>当轮调用者</b>。可定时任务（{@code on_timer} 钩子）的当轮主体是
     * {@code Caller.systemActor(...)}（她自己的自主行为），而它到点要干的活是<b>某个用户当初托付的</b>
     * —— 让子 Agent 继承钩子主体就等于"谁都能借定时任务把自己的事挂到她的位上去跑"。
     * 所以这类"到点才跑"的派发必须把主体换成<b>任务创建者</b>：主人建的任务照旧全权，
     * 普通人建的任务按<b>他自己的位</b>判（{@code exec} / 写本机文件被拒）。
     *
     * <p><b>默认实现</b>：忽略 {@code caller}，与
     * {@link #spawn(String, List, boolean, String, String, boolean)} 完全一致 —— 老宿主 / 非基板实现
     * 一个字节的行为都不变（基板实现 {@code Boot} 才真正用它顶替主体）。</p>
     *
     * @param caller 子 Agent 的主体；{@code null} = 与当轮调用者一致（默认行为）
     */
    default JsonObject spawnAs(Caller caller, String task, List<String> tools, boolean async,
                               String brief, String model, boolean reply) {
        return spawn(task, tools, async, brief, model, reply);
    }

    /**
     * 同上，并指定这一单的<b>投递目标会话</b>（{@code reply=true} 时"这句话说给谁听"）。
     *
     * <p><b>为什么需要它</b>：{@link #spawnAs(Caller, String, List, boolean, String, String, boolean)}
     * 派出去的子 Agent 写成的那句话，是由宿主投递给<b>调用它的那个宿主的落点</b>的。定时任务
     * （{@code on_timer} 钩子）没有自己的会话 —— 钩子的落点是基板的控制台（{@code systemSink}），
     * 于是"到点提醒"说出来的话会打在本机控制台上，而<b>它本该说回创建任务的那个群 / 私聊</b>。
     * 这里让技能把"这条任务要投给哪个会话"（建任务时记下的会话键）一并交下来，
     * 由<b>宿主</b>把它解析成真正的落点，技能不碰 {@code Sinks}、也不碰 NapCat。</p>
     *
     * <p><b>默认实现</b>：忽略 {@code session}，与上面那个重载逐字一致 —— 老宿主 / 非基板实现
     * 一个字节的行为都不变（基板实现 {@code Boot} 才真正解析它）。</p>
     *
     * <p><b>解析不出来必须回落</b>（硬要求）：{@code session} 为空、认不出来、或就是
     * {@code "console"} 时，宿主一律用<b>今天那个落点</b>（控制台）—— 不抛异常、不丢任务。
     * 老数据行（任务里没记过目标会话）走的正是这条路。</p>
     *
     * @param session 目标会话键（{@code group:<群号>} / {@code qq:<QQ>} / {@code console}）；
     *                {@code null} 或空 = 与当轮落点一致（默认行为）
     */
    default JsonObject spawnAs(Caller caller, String task, List<String> tools, boolean async,
                               String brief, String model, boolean reply, String session) {
        return spawnAs(caller, task, tools, async, brief, model, reply);
    }

    /** 往当前会话的某个槽位注入文本（system/context/tool/arg）。 */
    void inject(String slot, String key, String text);

    // ==================== SFW 控制台（基板⑨ 的原生交互面） ====================

    /**
     * 读 SFW 控制台最近的输出（<b>游标式</b>，只读）。
     *
     * <p>用途：技能想知道"我刚跑的东西在控制台上打出了什么"。
     * 机制是基板的控制台捕获器（环形缓冲，独立于控制台文档），
     * 所以控制台被人 {@code /clear} 过之后仍然读得到最近输出。
     * 每轮只拿新增：把上一次返回里的 {@code seq_tail} 当下次 {@code sinceSeq} 传进来。</p>
     *
     * @param sinceSeq 上次读到的 seq（{@code <=0} = 从缓冲里最旧的一条开始）
     * @param maxChars 最多回多少字符（{@code <=0} = 配置 {@code consoleTapReadChars}）
     * @return 一行事实（{@code [console] read ok=1 lines=… seq_head=… seq_tail=… missed=… sfw=…}）+ 输出正文；
     *         无界面环境里返回的是"读不到"的事实行，<b>不抛异常</b>。
     *         <b>{@code ok=1} 是结构标记</b>（B8，2026-09-16）：console 一族成败都带 {@code [console]} 前缀，
     *         主循环的连续失败闸门靠它区分"成功"与"自报失败"，别把标记删了
     */
    default String sfwRead(long sinceSeq, int maxChars) {
        try {
            return sair.v4.term.SfwOut.read(sinceSeq, maxChars, null);
        } catch (Throwable t) {
            return "[console] read ok=0 error=" + t;
        }
    }

    /** 当前控制台输出游标（下次 {@link #sfwRead(long, int)} 传它 = 只拿新输出）。 */
    default long sfwCursor() {
        try {
            return sair.v4.term.SfwOut.cursor();
        } catch (Throwable t) {
            return 0L;
        }
    }

    /**
     * 执行一条 SFW 框架命令（如 {@code jj/at 1+/100}），回它打出来的新输出。
     *
     * <p><b>判定</b>：与 {@code console} 工具的 {@code op=run} <b>同一条</b> ——
     * E 类资源 {@code Res.platform("sfw.run")} 的 {@code X} 位（主任定标 D10：E 类含"SFW 命令交互"）。
     * 所以：{@code SYSTEM}（她的自主行为，E 默认 {@code X}）放行；{@code ALLUSER}（E 默认 {@code NONE}）拒。
     * 技能不能借这一条绕过权限面。</p>
     *
     * <p><b>主人短路在判定之前</b>（与 {@code Boot} 的 NapCat Guard 里那句
     * {@code if (c.master()) return null;} <b>同口径</b>）：主人一律放行，<b>连"权限面没装配"
     * （{@code auth == null}，早期装配阶段 / 桩宿主）都不该拦他</b> —— 否则 {@code Host.need*}
     * 的 fail-closed 分支会把主人自己的控制台命令也拒掉（这是 v4 探针 {@code ProbeConsole ④f}
     * 抓到过的真回归）。</p>
     *
     * <p><b>{@code caller() == null} 仍然拒</b>（fail-closed，不回落 SYSTEM）：这条路上"没有主体"
     * 就是没有主体 —— 与 {@code console} 工具那条一样按拒绝处理。</p>
     *
     * @return 事实行 {@code [console] run ok=1 cmd=… ms=… return=…} + 该命令的新输出；
     *         被拒时 {@code [console] run ok=0 reason=denied：<拒绝原文>}；<b>不抛异常</b>
     */
    default String sfwRun(String cmd) {
        Caller c = caller();
        if (c == null) {
            return "[console] run ok=0 reason=denied（没有调用者身份，按拒绝处理）";
        }
        if (!c.master()) {                       // ← 主人短路：判定之前先看"是不是主人"
            String deny = needPlatform("sfw.run");
            if (deny != null) {
                return "[console] run ok=0 reason=denied（" + deny + "）";
            }
        }
        try {
            return sair.v4.term.SfwOut.run(cmd);
        } catch (Throwable t) {
            return "[console] run ok=0 error=" + t;
        }
    }

    /** SFW 控制台可用吗（无界面环境 false）；技能据此决定要不要走控制台那条路。 */
    default boolean sfwAvailable() {
        try {
            return sair.v4.term.SfwOut.available();
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 连接面的零碎动作 ====================

    /**
     * 私聊"正在输入"状态（{@code set_input_status}）。
     *
     * <p><b>这是机器人自己的状态，不是用户发起的动作</b> —— 和"基板替机器人发一句话"同一个口径
     * （{@link #say} 也不走发送工具的门禁），所以它<b>不按调用者权限裁决</b>：
     * 否则只有主人在私聊里看得见"正在输入"。基板只做这一个具名动作，
     * <b>不开放任意动作</b>（要发动作请走 {@link #napcat()}，那条路有闸门）。</p>
     *
     * <p>群聊没有这个动作（直接 no-op）；NapCat 没连上也是 no-op；<b>不抛异常</b>。</p>
     */
    default void typing(boolean on) { }
}
