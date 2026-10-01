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
    // op 判定唯一入口是下面的 need(op)；判定主体、账本与清单见 acl()。

    /** 好感度视图（可读不可改）。 */
    FavorView favor();

    // ==================== op 判定（技能管控表：唯一一条判定） ====================

    /**
     * 基板装配的权限面（技能管控表 {@code skillctl.json} + 唯一一条判定）。
     * <p>宿主没装配权限面（或不是基板实现）时返回 {@code null} —— 调用方一律按拒绝处理。</p>
     *
     * <p><b>P9b-2</b>：本方法原来是"经 {@code AuthView auth()} 中转"的默认实现（{@code auth() instanceof Auth}）。
     * 旧档位视图 {@code AuthView} 已整条删除，所以默认实现改为<b>直接返回 {@code null}</b>；
     * 基板实现（{@code Boot} 里的宿主）覆写它，返回自己装配的 {@code sair.v4.auth.Auth}。
     * 老宿主 / 桩实现不覆写就拿到 {@code null} ⇒ 一切 {@code need(op)} 走 fail-closed 分支（行为与过去一致）。</p>
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
     * <b>op 判定</b>：放行返回 {@code null}，否则返回可直接回给模型的拒绝原文。
     *
     * <pre>
     *   String deny = h.need("memory.remember");
     *   if (deny != null) return deny;      // 技能把这句话原样回给用户
     * </pre>
     *
     * <p>op 名 = {@code 工具名} 或 {@code 工具名.动作名}（清单见 {@link sair.v4.auth.Ops}）。
     * 多动作技能<b>每个动作分支各判一次自己那个 op</b> —— 技能管控表就是按这些名字放行的，
     * 所以"允许他记一笔"与"允许他删别人的"能在表里分开写。</p>
     *
     * <p><b>判定主体</b>是 {@link #subject()}（当轮绑定的主体）：工具调用 = 触发者；钩子 / 扩展点 =
     * {@code SYSTEM}（她自己的自动策略，恒放行）。{@code MASTER} 与她本人 {@code SYSTEM} 恒全权，
     * 不受管控表影响；{@code ALLUSER} 按表判：{@code Ban} 黑名单 = 不可用 / {@code Run} 白名单 = 可用 /
     * <b>空</b>（没写这条，或写了没给身份）= 未授权 = 不可用，优先级 {@code Ban > Run > 空}。</p>
     *
     * <p>资源不再有位：资源对主人与她本人完全可见可改可执行，对 {@code ALLUSER} 是黑盒 ——
     * 想读、想写、想执行，唯一的路就是被判定的这个 op（技能）。</p>
     *
     * <p>没有权限面（{@code acl() == null}）或判定过程出任何事，一律<b>拒绝</b>（fail-closed）。</p>
     *
     * <p><b>顺序（GM 裁定，情绪 v2 §4）</b>：① <b>权限面先判</b> —— 账本没放行就把它的拒文原样返回
     * （v6 铁律：权限表是对 ALLUSER 的约束，"权限阻断"那句不许被情绪盖掉）；② 放行了才轮到
     * <b>罢工硬干活闸</b>（{@code strike} 态下除 {@code perm} 一族一律拒）。两道判据都只有一处真源，
     * 罢工那句由 {@link sair.v4.Builtins#strikeDeny} 统一产出，这里不写第二套措辞。</p>
     *
     * @param op op 名（{@code 工具名} 或 {@code 工具名.动作名}）
     * @return 放行返回 {@code null}；否则返回含 {@code [权限阻断]} 前缀的拒绝原文
     */
    default String need(String op) {
        sair.v4.auth.Auth a = acl();
        if (a == null) {
            return sair.v4.auth.Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + op;
        }
        // ① 权限面先判：ACL 的拒文优先（权限模型不许被情绪改）。
        String deny = a.allow(subject(), op);
        if (deny != null) return deny;
        // ② 放行了才是罢工闸（REUSE：判据 / 措辞 / 真源只有 Builtins.strikeDeny 那一处）。
        return sair.v4.Builtins.strikeDeny(subject(), op);
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
     * <p><b>判定</b>：与 {@code console} 工具的 {@code op=run} <b>同一个 op</b> ——
     * {@code console.run}（技能管控表：{@code Run["console.run","身份"]}）。所以：主人与她本人
     * （{@code SYSTEM}）恒全权（{@link #need(String)} 直接放行）；{@code ALLUSER} 没被授权就拒。
     * 技能不能借这一条绕过管控表。</p>
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
            String deny = need("console.run");
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

    // ==================== 媒体出口：以「她自己」（SYSTEM）的身份说出去 ====================

    /**
     * <b>把一段媒体（图 / 语音）以「她自己」的身份发进当前会话</b>。
     *
     * <p><b>为什么需要它</b>（2026-09-24 真机 P0，批 10 头号修复）：技能层的 op
     * （{@code sticker.send} / {@code sendimage} / {@code send.record}）已经是"她能不能发"的
     * <b>唯一授权闸门</b>，而<b>内层平台动作</b> {@code napcat.send_group_msg} 对 {@code ALLUSER}
     * 是黑名单（技能管控表 {@code Skill.Ban}）⇒ 群友触发的回合里她<b>自己</b>想顺手发一张表情包，
     * 会被内层动作按"当轮调用者 = 群友"判死。真机逐字证据：
     * {@code [tool] sticker ms=5 chars=49 ok=0 → 表情包没发出去：[权限阻断] 「napcat.send_group_msg」
     * 在技能管控表的黑名单里} —— 而同一张图在主人的回合里 {@code ok=1}。
     * <b>她的正文发得出去、她的图发不出去，这个不对称就是本条要消掉的东西。</b></p>
     *
     * <p><b>口径与基板替她说话那条同源</b>：内层平台动作改按 {@code SYSTEM}（她本人恒全权）判 ——
     * 与 {@code Boot#sinkForDeliver} 里"基板替机器人发一句话属于她自己说话"（那里传的也是
     * {@code guardedApi}、闸门按"没有绑定调用者 = SYSTEM"判）是同一条，<b>不新开任何绕过闸门的口</b>。</p>
     *
     * <p><b>判据一个字没放宽</b>：</p>
     * <ul>
     *   <li>① 技能 op 仍由<b>技能自己</b>判（本口不判、也不放宽任何技能 op）；</li>
     *   <li>② 要走出去的本地文件仍只经 {@code Api.call} 那一层（{@code rewriteLocalFile} 的外链中转
     *       改写 + {@code File} 域路径判定）⇒ 交给 NapCat 的仍是 {@code http://…/<token>/…}，
     *       不是盘符路径（NapCat 在另一台机器上）；</li>
     *   <li>③ {@code Api.call} 里的自我记账头 / SILENT / 标记族 / 红线那几道闸一道都不绕。</li>
     * </ul>
     *
     * <p><b>补一道（W7/批 13）</b>：{@code type="record"} 时，本地件先按<b>当轮调用者</b>过一遍
     * {@code File} 域（{@link #localFileDeny}），过了才换 {@code SYSTEM} 出门 —— 不补的话下面那句
     * SYSTEM 绑定会把 {@code File} 域判定<b>一起</b>绕过去（批 10 独立复核实测的那条外延：普通群友能让
     * 机器人把本机任意可读文件变成中转 URL）。{@code type="image"} 与没有权限面的老宿主
     * <b>逐字节不变</b>。</p>
     *
     * <p><b>只发当前会话</b>：{@code (group,target)} 必须就是调用者此刻所在的会话 ——
     * 对不上（或控制台、没有调用者、NapCat 面没装配、类型不认）一律<b>返回 {@code null}</b>，
     * 调用方按原路（{@code h.napcat()}）发。所以这条口不是"能发给任意人"的新路，
     * 也不需要任何新权限。</p>
     *
     * <p><b>可观测</b>：由本口发出去的那一次打一行结构事实
     * {@code [say] <type> ok=1 via=self as=SYSTEM by=<谁的回合> session=… target=… ms=… file=…}
     * （失败时 {@code ok=0} 并带 {@code error=}）。落点是 {@link #out()} 的 <b>{@code skill}</b> 类别
     * —— 出厂 {@code logConsole="tool,model"} ⇒ <b>默认不打</b>（要看就加 {@code skill}，或
     * {@code logVerbose=true}）；{@code ok=0} 另有一处默认可见的痕迹：工具结果那行 {@code [tool] … ok=0}
     * （类别 {@code tool}，出厂就是开的）。回退那条路由调用点自己打（同一形状、{@code via=old}）。</p>
     *
     * <p><b>默认实现 = 全部实现</b>：本口只用 {@link #caller()} / {@link #napcat()} /
     * {@link #conf()} / {@link #out()} 这几个既有面，所以任何宿主（含老宿主与桩）直接得到它。
     * 对"那份没有闸门的 {@code napcat()}"（老宿主）这层 {@code SYSTEM} 绑定是<b>空操作</b>，
     * 产物与加这条口之前逐字节相同 —— 这就是"与旧行为逐字一致"的兜底。</p>
     *
     * @param group  落点是不是群
     * @param target 群号 / QQ（必须就是当前会话那一个）
     * @param type   媒体类型：只认 {@code "image"} 与 {@code "record"}（其余一律返回 null）
     * @param file   写法：本地绝对路径 / {@code http(s)} / {@code base64://} / NapCat fileid
     * @return 与 {@link #napcat()} 同形的响应对象（判错仍用 {@link Api#ok(JsonObject)} /
     *         {@link Api#error(JsonObject)}）；{@code null} = 本口不承接，调用方按改前那条路原样发
     */
    default JsonObject sayMedia(boolean group, long target, String type, String file) {
        final String f = sair.v4.kit.Str.trim(file);
        final String kind = sair.v4.kit.Str.lower(sair.v4.kit.Str.trim(type));
        if (f.isEmpty() || target <= 0L) return null;
        if (!"image".equals(kind) && !"record".equals(kind)) return null;   // 只承接这两种形态
        final Caller me = caller();
        if (me == null || me.isConsole()) return null;                      // 当前会话不是 QQ 会话
        if (me.toGroup() != group) return null;
        final long mine = group ? me.groupId() : me.qq();
        if (mine <= 0L || mine != target) return null;                      // 只发当前会话，不接别的落点
        // ★ W7/批 13：语音（record）多一道"按当轮调用者过 File 域"的前置判定。
        //   为什么必须补：下面那句 Ctx.of(SYSTEM) 会把**内层动作与 File 域判定一起**改按她本人判，
        //   于是"该不该读这个本地文件"就没人问了 —— 批 10 独立复核实测的那条外延正是它
        //   （普通群友能让机器人把本机任意可读文件变成中转 URL 交给 NapCat；见 消息发送\perms.jsonc
        //   里把 send.record 收窄成 Ban 的那段原话）。补上以后，本地件必须先按**触发者**的档位过
        //   File 域（含好感度档的只读放行，见 Acl.fileDeny），过了才换 SYSTEM 出门。
        //   今天这一步是空操作：send.record 对 ALLUSER 是 Ban（技能那一判先挡掉了），能走到这儿的
        //   只有主人 / 她本人，而他们的 fileDeny 本来就恒 null。image 那一路<b>一个字没动</b>
        //   （表情包 / 发图片的本地件走的仍是老口径）。
        if ("record".equals(kind)) {
            String pd = localFileDeny(me, f);
            if (pd != null) return sair.v4.kit.J.obj("error", pd);
        }
        final Api api = napcat();
        if (api == null) return null;                                       // NapCat 面没装配：按原路
        final long t0 = System.currentTimeMillis();
        JsonObject r;
        // ★ 这一句是整条口的要点：把判定主体显式钉成 SYSTEM（她本人恒全权），与基板替她说话那条同源。
        //   作用域只在这一次动作内，结束即还原（异常也还原）—— 技能自己那句 need(...) 早就判完了，
        //   这里的绑定绝不改变"技能 op 由当轮调用者判"这件事。
        // ★ 批 15（2026-09-26 甲方 CEO 令 · 落点规则）：钉子换成 {@code me.withSystem(true)}。
        //   为什么必须换：旧写法 `Caller.systemActor(masterQQ)` 会把**会话本身**抹成控制台
        //   （entry=CONSOLE / groupId=0 / qq=主人号），于是 NapCat 闸门里新加的那道落点判定
        //   （{@code Boot.destDeny}）在这一跳再也看不出"其实是在给 私聊A / 群G 发东西"——
        //   合法的自主出口会被它当成"没有当前会话"整条拒掉。两者对 ACL 与 File 域**逐条等价**
        //   （{@code kind()} 都是 SYSTEM ⇒ 恒全权，判定只看 kind），差的只有 session()/qq()/groupId()
        //   这几格——而落点规则要的正是它们。
        sair.v4.ctx.Ctx.Scope sc = sair.v4.ctx.Ctx.of(me.withSystem(true));
        try {
            if ("record".equals(kind)) {
                r = group ? api.sendGroupRecord(target, f) : api.sendPrivateRecord(target, f);
            } else {
                r = group ? api.sendGroupImage(target, f) : api.sendPrivateImage(target, f);
            }
        } catch (Throwable t) {
            // 绝不外抛、也绝不假装成功：按 Api 的统一形状回一个 error 对象（与 call 的失败形状同源）。
            r = sair.v4.kit.J.obj("error", String.valueOf(t));
        } finally {
            sc.close();
        }
        try {
            Out o = out();
            if (o != null) {
                boolean ok = Api.ok(r);
                // 脱敏：日志里绝不出现"外链中转"的 token —— NapCat 的回执完全可能把刚发出去的 URL 原样带回来。
                String scrub = "";
                try {
                    sair.v4.qq.Relay rl = api.relay();
                    if (rl != null && rl.running()) scrub = rl.baseUrl();
                } catch (Throwable ignored) {
                }
                String fLog = sair.v4.kit.Str.cut(sair.v4.kit.Str.oneLine(f), 120);
                if (!scrub.isEmpty() && fLog.indexOf(scrub) >= 0) fLog = fLog.replace(scrub, "<relay>");
                String line = "[say] " + kind + " ok=" + (ok ? 1 : 0) + " via=self as=SYSTEM by=" + me.kind()
                        + ":" + me.qq() + " session=" + session()
                        + " target=" + (group ? "group:" : "qq:") + target
                        + " ms=" + (System.currentTimeMillis() - t0)
                        + " file=" + fLog;
                if (!ok) {
                    String eLog = sair.v4.kit.Str.cut(sair.v4.kit.Str.oneLine(Api.error(r)), 120);
                    if (!scrub.isEmpty() && eLog.indexOf(scrub) >= 0) eLog = eLog.replace(scrub, "<relay>");
                    line = line + " error=" + eLog;
                }
                o.dim(line);
            }
        } catch (Throwable ignored) {
            // 事实行只是可观测：它自己绝不改变这次发送的结果
        }
        return r;
    }

    /**
     * <b>按当轮调用者过一遍 File 域（本地件专属）</b>：{@code null} = 过 / 不归它管，否则是拒绝原文。
     *
     * <p>只对"像本机文件"的写法起作用（{@link sair.v4.auth.FavorGate#looksLocal}）：
     * {@code http(s)} / {@code base64://} / fileid / 相对路径一律直接放行 —— 那些不是本机文件，
     * 不归 {@code File} 域管，硬判会把一条本来合法的外链判死。</p>
     *
     * <p><b>权限面没装配（{@code acl() == null}，老宿主 / 桩）时返回 {@code null}</b>：
     * 那种宿主底下 {@code napcat()} 根本没有闸门，这里凭空加一道拒绝只会把老宿主的行为改掉 ——
     * 而 {@code sayMedia} 的口径是"对那份没有闸门的 {@code napcat()}，本口与加它之前逐字节相同"。</p>
     */
    default String localFileDeny(Caller who, String file) {
        if (!sair.v4.auth.FavorGate.looksLocal(file)) return null;   // 不是本机文件：不归 File 域
        sair.v4.auth.Auth a = acl();
        if (a == null) return null;                                  // 没有权限面：与加这道口之前一致
        if (who == null) return sair.v4.auth.Acl.DENY_PREFIX + "没有主体，按拒绝处理：" + file;
        try {
            sair.v4.auth.Acl acl = a.acl();
            if (acl == null) return sair.v4.auth.Acl.DENY_PREFIX + "权限面没有装配（acl == null），按拒绝处理：" + file;
            return acl.fileDeny(who, file, false);                   // 交出去 = 读文件 ⇒ 看 Read
        } catch (Throwable t) {
            return sair.v4.auth.Acl.DENY_PREFIX + "路径判定异常，按拒绝处理：" + file + "（" + t + "）";
        }
    }

    /**
     * <b>把一份文件 / 一段语音以「她自己」的身份发进当前会话</b>（主人令 2026-09-26：{@code favor >= 500}
     * 放权"本地文件读取 + QQ 文件发送 / 语音发送"）。
     *
     * <p><b>与 {@link #sayMedia} 同一条思路、同一套兜底</b>：内层平台动作
     * （{@code send_group_msg} / {@code upload_group_file}）对 {@code ALLUSER} 是黑名单，
     * 所以"她替这个人发一份文件"必须把主体显式钉成 {@code SYSTEM}；而<b>技能 op
     * （{@code send.file} / {@code send.fileto} / {@code send.record}）仍然是唯一授权闸门</b> ——
     * 本口不判技能 op、也不放宽任何技能 op。</p>
     *
     * <h3>四条硬口径</h3>
     * <ol>
     *   <li><b>落点只能是当前会话</b>：{@code (group,target)} 与调用者此刻所在的会话对不上
     *       （或控制台、没有调用者、NapCat 面没装配）⇒ <b>返回 {@code null}</b>，调用方按原路
     *       （{@code h.napcat()}）发。所以它不是"能发给任意人"的新路。</li>
     *   <li><b>第二道闸先过</b>：{@link sair.v4.auth.FavorGate#lift(String, Caller)} 对这个 op
     *       放行才承接（{@code favor >= favorFileMin}，出厂 500；主人 / 她本人恒全权、豁免此闸）。
     *       不放行 ⇒ {@code null} 走回退路，由原路上的 ACL 判定照旧拒掉（fail-closed）。</li>
     *   <li><b>本地件先按当轮调用者过 File 域</b>（{@link #localFileDeny}）—— 这一句不许省：
     *       下面那句 {@code Ctx.of(SYSTEM)} 会把 File 域判定一起改按她本人判，省掉就是
     *       "任何人都能让她把本机任意文件发出去"（批 10 实测过的外延）。
     *       过不了 ⇒ 返回一个 {@code error} 对象（<b>不是</b> {@code null}）：本口承接了、但这件事被拒，
     *       调用方不该再拿它去回退重试。</li>
     *   <li><b>外链中转改写一个字不改</b>：真正的发送仍只经 {@code Api.call} 那一层
     *       （{@code rewriteLocalFile} 把本地路径换成 {@code http://…/<token>/…}）——
     *       交给 NapCat 的永远是 URL，<b>绝不是盘符路径</b>。</li>
     * </ol>
     *
     * <p><b>可观测</b>：由本口发出去的那一次打一行结构事实
     * {@code [say] file ok=1 via=self as=SYSTEM by=<谁的回合> session=… target=… file=…}
     * （{@code type} = {@code file} 或 {@code record}；失败时 {@code ok=0} 并带 {@code error=}）。
     * 落点与 {@link #sayMedia} 一样是 {@link #out()} 的 {@code skill} 类别（出厂默认不打）。</p>
     *
     * <p><b>默认实现 = 全部实现</b>：只用 {@link #caller()} / {@link #napcat()} / {@link #conf()} /
     * {@link #out()} / {@link #acl()} 这几个既有面。老宿主（没有闸门的 {@code napcat()}）上它与
     * 加这条口之前逐字节相同 —— 这就是"与旧行为一致"的兜底。</p>
     *
     * @param op     替哪个技能 op 干活：只认 {@code "send.file"} / {@code "send.fileto"} /
     *               {@code "send.record"}（其余一律返回 {@code null}）；前三者一律发<b>当前会话</b>，
     *               {@code fileto} 的"指定的人"必须就是当前私聊那一个
     * @param group  落点是不是群
     * @param target 群号 / QQ（必须就是当前会话那一个）
     * @param file   写法：本地绝对路径 / {@code http(s)} / {@code base64://} / NapCat fileid
     * @param name   上传文件名（{@code send.file} / {@code send.fileto} 用；空 = 由基板按路径取）
     * @return 与 {@link #napcat()} 同形的响应对象；{@code null} = 本口不承接，调用方按改前那条路原样发
     */
    default JsonObject sayFile(String op, boolean group, long target, String file, String name) {
        final String o = sair.v4.kit.Str.trim(op);
        if (!"send.file".equals(o) && !"send.fileto".equals(o) && !"send.record".equals(o)) return null;
        final String f = sair.v4.kit.Str.trim(file);
        if (f.isEmpty() || target <= 0L) return null;
        final Caller me = caller();
        if (me == null || me.isConsole()) return null;                      // 当前会话不是 QQ 会话
        if (me.toGroup() != group) return null;
        final long mine = group ? me.groupId() : me.qq();
        if (mine <= 0L || mine != target) return null;                      // ★ 只发当前会话，不接别的落点
        final boolean free = me.kind() == Caller.Kind.MASTER || me.kind() == Caller.Kind.SYSTEM;
        // ★ 第二道闸（好感度档位）：主人 / 她本人豁免（本来就恒全权），其余人必须被它放行才承接。
        if (!free && sair.v4.auth.FavorGate.lift(o, me) == null) return null;
        // ★ 本地件先按"当轮调用者"过 File 域（含好感度档的只读放行）——见上面第 ③ 条。
        String pd = localFileDeny(me, f);
        if (pd != null) return sair.v4.kit.J.obj("error", pd);
        final Api api = napcat();
        if (api == null) return null;                                       // NapCat 面没装配：按原路
        final boolean asRecord = "send.record".equals(o);
        final long t0 = System.currentTimeMillis();
        JsonObject r;
        // ★ 与 sayMedia 同一句要点：把判定主体显式钉成 SYSTEM（她本人恒全权），作用域只在这一次动作内。
        // ★ 批 15（2026-09-26 甲方 CEO 令 · 落点规则）：钉子换成 {@code me.withSystem(true)} ——
        //   与 sayMedia 那边同一条理由：落点判定（{@code Boot.destDeny}）要看出"这一次是在给哪一个会话
        //   发东西"，而 {@code Caller.systemActor(masterQQ)} 会把会话抹成控制台。两者对 ACL/File 域
        //   逐条等价（kind() 都是 SYSTEM）。
        sair.v4.ctx.Ctx.Scope sc = sair.v4.ctx.Ctx.of(me.withSystem(true));
        try {
            if (asRecord) {
                r = group ? api.sendGroupRecord(target, f) : api.sendPrivateRecord(target, f);
            } else {
                r = group ? api.sendGroupFile(target, f, name) : api.sendPrivateFile(target, f, name);
            }
        } catch (Throwable t) {
            r = sair.v4.kit.J.obj("error", String.valueOf(t));
        } finally {
            sc.close();
        }
        try {
            Out o2 = out();
            if (o2 != null) {
                boolean ok = Api.ok(r);
                // 脱敏：日志里绝不出现"外链中转"的 token（NapCat 的回执可能把刚发出去的 URL 原样带回来）。
                String scrub = "";
                try {
                    sair.v4.qq.Relay rl = api.relay();
                    if (rl != null && rl.running()) scrub = rl.baseUrl();
                } catch (Throwable ignored) {
                }
                String fLog = sair.v4.kit.Str.cut(sair.v4.kit.Str.oneLine(f), 120);
                if (!scrub.isEmpty() && fLog.indexOf(scrub) >= 0) fLog = fLog.replace(scrub, "<relay>");
                String line = "[say] " + (asRecord ? "record" : "file") + " ok=" + (ok ? 1 : 0)
                        + " via=self as=SYSTEM by=" + me.kind() + ":" + me.qq() + " session=" + session()
                        + " target=" + (group ? "group:" : "qq:") + target
                        + " ms=" + (System.currentTimeMillis() - t0)
                        + " file=" + fLog;
                if (!ok) {
                    String eLog = sair.v4.kit.Str.cut(sair.v4.kit.Str.oneLine(Api.error(r)), 120);
                    if (!scrub.isEmpty() && eLog.indexOf(scrub) >= 0) eLog = eLog.replace(scrub, "<relay>");
                    line = line + " error=" + eLog;
                }
                o2.dim(line);
            }
        } catch (Throwable ignored) {
            // 事实行只是可观测：它自己绝不改变这次发送的结果
        }
        return r;
    }
}
