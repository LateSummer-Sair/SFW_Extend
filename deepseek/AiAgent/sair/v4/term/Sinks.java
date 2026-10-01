package sair.v4.term;

import com.google.gson.JsonObject;

import sair.v4.auth.Caller;
import sair.v4.ctx.Sink;
import sair.v4.kit.Out;
import sair.v4.qq.Api;

/** 输出落点：本地控制台 / QQ（群、私聊）/ 定时任务。 */
public final class Sinks {

    private Sinks() {}

    /**
     * 出站 stage 管线（基板⑦；装配期由 {@code Boot} 注入）。
     *
     * <p><b>为什么是静态注入口</b>：{@link QqSink} / {@link TaskSink} 在网关、定时器、工具、
     * 探针等<b>十几处</b>被构造，而扩展点注册表是<b>基板级</b>的（同一时刻只有一个，
     * 见 {@code Boot} 的装配）。为了传一个引用去改所有构造点，只会让每个调用方都多背一个
     * "跟它无关"的参数。静态的话默认是 null —— 谁都没注入过（包括所有老探针）= 零 stage =
     * 与加这一层之前逐字节一致。</p>
     */
    private static volatile sair.v4.ext.ExtRegistry EXT;

    /** 注入/卸下出站扩展点（装配期注入；{@code Boot.stop()} 卸下，避免停完还在改正文）。 */
    public static void setExt(sair.v4.ext.ExtRegistry e) { EXT = e; }

    /**
     * 跑一遍出站 stage 管线（<b>分段之后、发送之前</b>）。
     *
     * <p>零 stage 时原样返回同一个 List 对象 —— 调用方据此做到"行为逐字节不变"。</p>
     *
     * @param seg 分段口径：追加消息（stage 的 {@code extra}）与正文共用同一把尺子
     */
    private static java.util.List<String> stage(Caller caller, java.util.List<String> parts,
                                                boolean group, long target, boolean echo, Segmenter seg) {
        sair.v4.ext.ExtRegistry e = EXT;
        if (e == null || !e.onStages()) return parts;
        final Segmenter s = seg;
        final boolean g = group;
        sair.v4.ext.ExtRegistry.Splitter sp = new sair.v4.ext.ExtRegistry.Splitter() {
            @Override
            public java.util.List<String> split(String text) {
                return s == null ? sair.v4.qq.Seg.messages(text, 1200) : s.plan(text, g);
            }
        };
        try {
            sair.v4.ext.Outbound m = new sair.v4.ext.Outbound(caller, "", group, target, echo);
            return e.runStages(m, parts, sp).parts;
        } catch (Throwable t) {
            // 注册表自己炸了：这一条按原样发。外挂改不动的东西，不能因为它坏了就丢话。
            return parts;
        }
    }

    /**
     * <b>出站最后一道闸（唯一一处）</b>：这条正文里是不是混进了"不该由她当正文说出去"的东西。
     *
     * <p><b>逐条这道闸有七条规则</b>；另有<b>不在这一层</b>的整条/整批级纪律 ——
     * 「基板事实块写法」跳闸（G1，在 {@link #wholeGate}，<b>分段之前</b>、<b>整批不发</b>）
     * 与「一轮条数上限」（G3，在 {@link #capParts}，<b>分段之后</b>、只发前 N 条），
     * 两者都在 2026-09-22「内部文字外泄防线批」落地；
     * ★ 2026-09-23 批 6 再加两条：<b>①(c)</b>（{@link #markStopTail}，<b>分段之后、stage 之前</b>）
     * —— 止语点跨段生效（标记之后那几段一起丢弃）；<b>②</b>（{@link #degradedPrint}）——
     * 把 G1 补进三条降级打印路与本地控制台。见各自的方法注释。
     * ★ 2026-09-28「贴边暗号」批（续）再加一条：{@link #seamSilent}（与 {@code markStopTail}
     * <b>同一层、同一位置</b>，刻意排在它<b>之前</b>）—— 长度分段正好把一个括号暗号劈成两半时，
     * 两个残片谁都不许单独进群（判据只有 {@code agent.Agent.silentSeam} 一处）。
     * 判据都在别处（各自有唯一产法），这里只做决定 —— <b>整条不发</b>、
     * <b>剥掉头部再发</b>、<b>剥掉标记及其之后的正文再发</b>，或<b>就地换词再发</b>：</p>
     * <ol>
     *   <li><b>工具调用标记</b>（{@link sair.v4.qq.ToolMarkup}，真机事故 2026-09-16 22:12，
     *       测试群 121873503）：模型把工具调用标记当正文吐了出来（{@code delta.content} 里是 DSML 标签、
     *       {@code tool_calls} 是空的），{@code agent.Loop} 因此判定"这一轮没有工具调用 = 最终回答"，
     *       那段标签被切成 3 条发进了真群。判据是<b>结构</b>（标签形状 + 竖线/DSML/结构词），不是关键词表。
     *       <b>命中就整条不发</b>，绝不"正则删字符后照发"：半剥的残留（半个标签、半句参数）
     *       比一条都不发更像乱码，而且会把残缺内容同时写进聊天记忆。要拦的那一条丢了，
     *       其余条照发（各条独立判）。</li>
     *   <li><b>自我记账头</b>（P0-1 的标记行形状 {@code [我发的 msg_id=N] }，唯一产法见
     *       {@link sair.v4.qq.SelfEcho#content}）：那是<b>记账那行的行标签</b>，不是她要说的话。
     *       真机 2026-09-20 复现：私聊出站 {@code sent} 台账 id=1003/709 两条正文以它开头，
     *       用户真收到了 —— 形状是模型从上下文里学来的。所以：头（<b>任一形态</b>，含
     *       {@code san} 中和出来的圆括号变体）在<b>开头</b> ⇒ 剥掉再发（剩下的才是她真正要说的话，
     *       与"别人那一行不带 msg_id"一致）；头在<b>中段</b> / 剥完什么都不剩 / 头残缺
     *       （认得出前缀却没有收尾）⇒ <b>整条不发</b>。开头<b>连续多个</b>头一起剥
     *       （"外层真头 + 内层模型写的头"那种双层就是线上实际形态：{@code dialog id=2370}）。
     *       判据<b>一律复用</b> {@link sair.v4.qq.SelfEcho#headAt}/{@link sair.v4.qq.SelfEcho#withoutHead}
     *       —— 识别（方括号与圆括号变体）只在 {@code qq.SelfEcho} 一处，本层不写第二套括号判断。</li>
     *   <li><b>协议暗号</b>（{@code <silent>} 一类"这一条不出声"的记号，真机事故 2026-09-21 21:37：
     *       两条正文<b>整条就是</b> {@code <silent>} 的消息发进了真群 {@code 543986616}，
     *       {@code sent id=1597/1598}、{@code message_id=1901253020/1490762514}；同一晚群
     *       {@code 126369686} 另有 3 条）：这个记号的唯一语义是"我这一轮不出声"，
     *       <b>它本身绝不能成为正文</b>。判据是<b>整条相等</b>（不是包含）：整条正文
     *       （含剥掉自我记账头之后剩下的那句）就是那个暗号 ⇒ <b>整条不发</b>；暗号夹在正常句子里
     *       （"她回了一句 {@code <silent>} 试试"）<b>照发</b> —— 那时它是正文的一部分。
     *       判据<b>一律复用</b> {@link sair.v4.agent.Agent#silent(String)}（{@code <silent>} /
     *       {@code [silent]} / {@code silent} 与 {@code 不语} 一组全在那一处，本层不写第二套词表）。
     *       这一处是<b>逐段</b>跑的（分段之后），所以"整条"正好是"这一条消息"。它原来住在一个
     *       <b>可选技能</b>里（{@code 拟人化} 的套话正则），技能一关纪律就没了 —— 基板纪律
     *       不该依赖任何可选技能，故下沉到这道唯一的闸。
     *       <br>★ <b>2026-09-28「贴边暗号」批</b>：又发现<b>贴边</b>形态从"整条相等"这条缝里漏
     *       出去（真机 {@code grouplog #452484} 2026-09-28 01:20 群 {@code 543986616}
     *       {@code sent_id=3280}：正文 {@code "…我照最新那条走。 <silent>"}；{@code #421563}
     *       2026-09-24 22:08 群 {@code 70559059} {@code sent_id=2594}：正文以 {@code "<silent> "}
     *       开头）⇒ 整条相等<b>照旧整条不发</b>，另<b>新增</b>：贴边的括号暗号
     *       （{@link sair.v4.agent.Agent#stripSilent(String)}，只剥首/尾、只剥带括号形态、
     *       裸词 {@code 不发言} 一族一律不动 —— 反例是真机 {@code #403457} 的正常中文
     *       "她发不发言我哪知道呀"）<b>剥掉那个 token，再拿剥完的正文走后面几道闸</b>
     *       （红线等；剥完为空 ⇒ 这一条不发）。判据仍只有 {@code Agent} 那一处词表。</li>
     *   <li><b>半截自报标记</b>（2026-09-23「出站核心批」①(b) 新增）：这一段<b>末行</b>写全了
     *       {@code [[mood:} 却没有收尾 {@code ]}（{@link sair.v4.qq.MoodMarks#openMark}），
     *       或整条**结尾**只写到 {@code [[mo} 这种"标记开头的正前缀"
     *       （{@link sair.v4.qq.MoodMarks#openTail}）⇒ <b>这一条不发</b>
     *       （{@code rule=mood_mark residual=split}）。理由：分段（{@code <split>} 或长度硬切）
     *       正好切在标记中间时，两半各自都不满足标记形状 ⇒ 半个标记会当正文进群。</li>
     *   <li><b>{@code [[mood:…]]} 自报标记族</b>（协议级；唯一实现 {@link sair.v4.qq.MoodMarks}）：
     *       提示词每轮都在教她写这一行（{@code 做不到} / {@code 为难} / {@code 违规} / {@code 转述}），
     *       它是<b>基板与模型之间的约定</b>，用户不该看到字面量。判据是<b>整行</b>：标记行整行丢掉、
     *       保留标记<b>之前</b>的文字；剥完什么都不剩 ⇒ <b>整条不发</b>。
     *       <b>★ 2026-09-22「内部文字外泄防线批」G2 起，标记 = 止语点</b>：标记所在行<b>连同它之后的
     *       全部正文</b>一起丢弃（旧口径只丢标记行本身、后面照发）。依据是真机 09:06 那一轮 ——
     *       她先写该说的那句、再接 {@code [[mood:违规]]}、再接 2082 字自述推理，旧口径把独白
     *       切成 14 条发进真群；新口径只剩她那句正确的回答（14 → 1，实测 67 条带标记历史里
     *       66 条标记本就在最后一段 ⇒ 误伤 0）。旧 → 新逐条写在 {@link sair.v4.qq.MoodMarks} 的类注释里。
     *       它原先<b>只有</b>一个实现、住在<b>可选技能</b> {@code 情绪} 里 ⇒ 技能移走 / 编译失败 /
     *       {@code extEnabled=false}（整条 stage 管线不跑）/ 工具发送路 四种形态必现外泄
     *       （2026-09-22 审计 §5-1），故下沉。★ 刻意<b>只</b>落在这一处逐条口（分段之后）：
     *       放进 {@code wholeGate} 会让 {@code 情绪} 看不到 {@code [[mood:转述]]} ⇒ 转述轮错误 @ 人
     *       （用户可见回归）。{@code [[mood:转述]]} 的语义（本轮不 @、不拆句）<b>不</b>在这一层，仍归
     *       {@code 情绪}。</li>
     *   <li><b>出站红线</b>（常开族；唯一实现 {@link sair.v4.term.Redline}，词表 =
     *       {@code data/prompts/redline.md} 热读）：<b>不可替换</b>类目（威胁 / 自伤）命中 ⇒
     *       <b>整条不发</b>；<b>可替换</b>类目（隐私 / 机密键）命中 ⇒ <b>就地换词再发</b>。
     *       <b>不需要 caller、不需要场合</b> —— 定时任务 / 闹钟落点（caller 恒 null）也要拦得住。
     *       它原先<b>只有</b>一个实现、住在<b>可选技能</b> {@code 出站红线} 里，而它同时是<b>唯一</b>
     *       覆盖"无 caller 落点"的安全闸（情绪那一层明确"没 caller 一律不否决"，见 {@code MoodStage}）
     *       ⇒ 技能一卸即<b>零安全网</b>（2026-09-22 审计 §5-2），故下沉。
     *       粒度与技能那一层相同：命中就丢<b>这一条</b>（其余条照发），不是丢整批。</li>
     *   <li><b>★ 2026-09-28「协议标记漏出」批（族 B）· 半截/畸形协议标记</b>
     *       （判据唯一产法 {@link sair.v4.qq.MarkerTags#halfFact(String)}）：正文里有"名单里的标记名
     *       + 属性形状开了头、到这个 {@code <} 之后<b>再没有 {@code >}</b>"⇒ <b>整条不发</b>。
     *       真机依据 {@code sent id=2866}（群 {@code 70559059}、{@code message_id=1450226835}）正文
     *       {@code "…是那个指数太狠了。 <at qq=\"2312678168\"给3 的 10 的 14 次方，结果…"}：
     *       属性形状开了头、到结尾都没有 {@code >} ⇒ 老口径两条支路都匹配不到、原样落群，
     *       还把后面的正文"看起来"吞进了属性。纪律与「自我记账头残缺 ⇒ 整条不发」逐字同条：
     *       <b>不猜截断点、也不做"正则删几个字符后照发"</b>（半剥的残渣比整条不发更糟）。
     *       位置：逐条口（{@link #gate}）与整条口（{@link #wholeGate}）<b>各判一次</b>
     *       —— 整条口那一次是为了"分段正好把它劈成两半"的情形（两个残片各自都认不出来）。
     *       <b>族 A/族 C 不是这一层剥的</b>：它们由 {@code qq.MarkerTags.strip} 在<b>喂进这道闸之前</b>
     *       剥掉（{@link QqSink} / {@link TaskSink} 各两处，{@code qq.Api} 内容级闸同源），
     *       本层看到的是剥完的正文 —— 所以"标记剪掉、剩句照发"这条语义只有 {@code MarkerTags} 一处实现。</li>
     * </ol>
     *
     * <p><b>为什么是这一处</b>：全树的出站纪律只有这一支 {@code gate(...)}，{@link QqSink}、
     * {@link TaskSink}（含"NapCat 未连接"的降级打印路、以及技能 {@code reply=true} 派子 Agent 后
     * 宿主代发的 {@code sink.say(body)} 那条路）与 {@link ConsoleSink}（本地交互面 ——
     * 无 QQ 落点时的降级打印路同样从它出去）都从这里过。{@code qq.Api.sendText} 是"真发出去"的
     * 漏斗：那里另有一套<b>只拦不剥</b>的 fail-closed 判定（补-2 / 补-3 / SILENT-FIX / 本轮的
     * 内容级闸，覆盖绕过 Sinks 的直连通道），判据与本层<b>同源</b>（同一个 {@code SelfEcho.headAt}、
     * 同一个 {@code Agent.silent}、同一个 {@code MoodMarks}、同一个 {@code Redline}），不是第二套规则。
     * 而"剥头再发"这个语义只留在这一层 —— 上一次筛过的正文到 {@code Api} 必然是干净的，不会双重处理。</p>
     *
     * @param part 待发正文（已过 {@code PlainText.clean} + {@code MarkerTags.strip}）
     */
    private static Gate gate(String part) {
        // ① 工具调用标记：整条不发（与加自我记账头之前逐字同一条判据、同一行 warn）。
        //    注意它**只在逐段这一道**判：整条那道闸（wholeGate）刻意不碰它 ——
        //    它的判据不是"前缀"，分段不影响它能不能拦住，行为因此一个字不变
        //    （probe\ProbeLeak 钉的正是它逐段拦 3 条 + 整批事实行）。
        if (sair.v4.qq.ToolMarkup.has(part)) {
            return Gate.drop("正文命中工具调用标记（" + sair.v4.qq.ToolMarkup.fact(part) + "）");
        }
        // ★ 2026-09-23「出站核心批」①(b)：**半截标记** fail-closed（两种形状各管一半，
        //   判据只在 qq.MoodMarks 一处：openMark / openTail）——
        //     ① 末行写全了 `[[mood:` 却没有收尾 `]`（工单口径逐字）⇒ 这一条不发；
        //     ② 整条**结尾**只写到 `[[mo` 这种"标记开头的正前缀"（openMark 认不出的那一半）⇒ 这一条不发。
        //   为什么必须 fail-closed：`<split>` / 长度分段正好切在标记中间时，两半各自都不满足 start()
        //   ⇒ "正文甲[[mo" 这种残片会当正文发进群（真机 2026-09-22 事故形状）。
        //   影响面：历史 67 条带标记正文里只有事故那 1 条被劈开（标记本就在末段）⇒ 误伤 0。
        //   位置：判的是**进闸的这段原文**（不是剥过标记的 body）—— 剥完就看不见"没闭合"了。
        String lastLine = part;
        int nl = part.lastIndexOf('\n');
        if (nl >= 0) lastLine = part.substring(nl + 1);
        int halfHead = sair.v4.qq.MoodMarks.openMark(lastLine);
        int halfTail = sair.v4.qq.MoodMarks.openTail(part);
        if (halfHead >= 0 || halfTail >= 0) {
            return Gate.drop("半截自报标记（rule=mood_mark residual=split at="
                    + (halfHead >= 0 ? halfHead : halfTail) + " chars=" + part.length() + "）");
        }
        // ★ 2026-09-28「协议标记漏出」批（族 B）：**半截/畸形协议标记** fail-closed ——
        //   真机 sent id=2866（群 70559059，message_id=1450226835）正文是
        //   `…是那个指数太狠了。 <at qq="2312678168"给3 的 10 的 14 次方，结果…`：属性形状开了头、
        //   到结尾都没有 `>` ⇒ 老口径（结构完整 / 第一个 `>` 收尾）两条都匹配不到，那一串原样落群，
        //   还把后面的正文"看起来"吞进了属性。判据只有 qq.MarkerTags.halfFact 一处
        //   （只对名单里的名字判，`<div class="x"` 这种一个字都不动）。
        //   理由与「自我记账头残缺 ⇒ 整条不发」同一条纪律：不猜截断点、也不"删几个字符后照发"。
        //   位置与上面那条半截 [[mood:…]] 判定同一层、同一顺序（都在剥之前判原文视图）。
        String protoHalf = sair.v4.qq.MarkerTags.halfFact(part);
        if (protoHalf != null) {
            return Gate.drop("半截/畸形协议标记（" + protoHalf
                    + " residual=half at=this_part chars=" + part.length() + "）");
        }
        // ② [[mood:…]] 自报标记族（协议级）：**整行**丢；剥完为空 ⇒ 整条不发。
        //    判据只有 sair.v4.qq.MoodMarks 一处（技能那份实现已删除，情绪改成调它）；
        //    这里是"技能关掉 / 卸掉 / 总闸关掉 / 无 caller 落点"时的那道兜底。
        //    ★ 位置：在**逐条**这一口（分段之后），不在 wholeGate —— 否则 [[mood:转述]] 会在
        //      情绪看到它之前被吃掉，转述轮又会错误 @ 人。
        String body = part;
        boolean marked = false;
        String ms = sair.v4.qq.MoodMarks.strip(body);
        if (!ms.equals(body)) {
            if (ms.trim().isEmpty()) {
                return Gate.drop("正文剥掉自报标记之后什么都不剩（rule=mood_mark chars="
                        + part.length() + " remain=0）");
            }
            body = ms;
            marked = true;
        }
        Gate g = headGate(body);
        if (g.drop()) return g;
        // ④ 协议暗号（<silent> 一类）：**整条正文就是它** ⇒ 整条不发；夹在句子里 ⇒ 照发。
        //    判据只有 agent.Agent.silent 一处（本层不写第二套词表）；剥掉自我记账头（以及标记行）
        //    之后剩下的那句同样要判 —— "[我发的 msg_id=1] <silent>" 要发出去的正是那个暗号。
        //    注意 silent(null/空/纯空白) 也是 true：空白正文**不是**暗号，先排除掉，
        //    否则"空白段"会被当成暗号整条丢掉（原有的空段语义一个字不变）。
        String visible = g.stripped ? g.text : body;
        if (!visible.trim().isEmpty() && sair.v4.agent.Agent.silent(visible)) {
            return Gate.drop("整条正文就是协议暗号（rule=silent_token chars=" + visible.length() + "）");
        }
        // ★ 2026-09-28「贴边暗号」批（真机仍在漏的两条，见 Agent.stripSilent 的注释）：整条相等
        //   拦不住的**贴边**形态（尾部 "…我照最新那条走。 <silent>"、头部 "<silent> 等等，…"）——
        //   剥掉那个 token（只剥贴边、只剥带括号形态、裸词一律不动），**再用剥完的正文走后面几道闸**
        //   （红线等），不是"剥完就放行"。判据只有 agent.Agent.stripSilent 一处。
        //   ★ 不变量：**没剥任何东西时**（`cut == visible`，stripSilent 没命中就返回原对象）
        //     下面一个字都不走 ⇒ 返回的 Gate 与今天逐字节一致（marked/stripped/rule 都不变）。
        boolean desilent = false;
        if (!visible.trim().isEmpty()) {
            String cut = sair.v4.agent.Agent.stripSilent(visible);
            if (cut != visible) {
                desilent = true;
                visible = cut;
                if (visible.trim().isEmpty()) {
                    return Gate.drop("正文剥掉贴边的协议暗号之后什么都不剩（rule=silent_token edge=strip"
                            + " remain=0 chars=" + part.length() + "）");
                }
            }
        }
        // ⑤ 出站红线（常开族）：不可替换类目 ⇒ 整条不发；可替换类目 ⇒ 就地换词。
        //    判据与词表只有 sair.v4.term.Redline 一处（数据在 prompts/redline.md，热读；
        //    技能那份实现已改成调它）。**不需要 caller、不需要场合** —— 定时/闹钟落点也要拦得住。
        String hit = Redline.veto(visible);
        if (hit != null) {
            return Gate.drop("正文命中出站红线类目「" + hit + "」（rule=redline cat=" + hit
                    + " chars=" + visible.length() + "）");
        }
        String rep = Redline.replace(visible);
        boolean swapped = !rep.equals(visible);
        String text = swapped ? rep : visible;
        if (swapped) return new Gate(text, null, g.stripped, marked, hit, desilent);
        if (!marked && !desilent) return g;   // 一个字都没多动 ⇒ 与既有行为逐字节一致
        return new Gate(text, null, g.stripped, marked, null, desilent);
    }

    /**
     * <b>自我记账头</b>那一半的闸（补-9 起整条与逐段共用）：头在<b>可见开头</b> ⇒ 剥掉再发；
     * 头在中段 / 剥完为空 / 头残缺 ⇒ 整条不发。
     */
    private static Gate headGate(String part) {
        // ② 自我记账头（任一形态，含全角/零宽/大小写一类变体写法）：头在**可见开头** ⇒ 剥掉再发；
        //    其余位置 ⇒ 整条不发。用 isSelfContent（视图坐标 0）判"开头"，用 headAt（原文下标）报位置。
        if (sair.v4.qq.SelfEcho.isSelfContent(part)) {
            // 开头连续多个头（方括号 / 圆括号变体 / "?" 的那种 / 变体写法）一次剥完；中段的头它不动。
            String body = sair.v4.qq.SelfEcho.withoutHead(part);
            if (body.equals(part)) {
                // 认得出前缀，却剥不掉（没有收尾）⇒ 不猜截断点，整条不发。
                return Gate.drop("自我记账头残缺（rule=self_head at=0 unterminated chars="
                        + part.length() + "）");
            }
            int rest = sair.v4.qq.SelfEcho.headAt(body);
            if (rest >= 0) {
                // 剥掉开头那几段之后还剩头 ⇒ 那一段在正文中段 ⇒ 整条不发（中段一律不剥）。
                return Gate.drop("正文中段还有自我记账头（rule=self_head at=" + rest
                        + " rest chars=" + part.length() + "）");
            }
            body = body.trim();
            if (body.isEmpty()) {
                return Gate.drop("正文剥掉自我记账头之后什么都不剩（rule=self_head at=0 remain=0 chars="
                        + part.length() + "）");
            }
            return Gate.strip(body);
        }
        int at = sair.v4.qq.SelfEcho.headAt(part);
        if (at >= 0) {
            return Gate.drop("正文中段出现自我记账头（rule=self_head at=" + at
                    + " chars=" + part.length() + "）");
        }
        return Gate.pass(part);
    }

    /**
     * <b>FIX-ECHO 补-9（第三轮复核证伪：分段把跨越切点的头劈成两半）</b>：整条正文在<b>分段之前</b>
     * 先过一次同一支闸 —— 否则 {@code [我发的 msg_id=1]} 正好跨在分段切点上时，两段各自"干净"、
     * 全文照发，用户连着收到两条消息、拼起来就是完整的头（复核实测：默认 1200 的分段器在 pad≥1190
     * 泄漏；生产 Segmenter（私聊 1000）在 990/992/995/998/1000 泄漏；群聊 180 的窗口 ≈7%）。
     *
     * <p>三种结论的落法：{@code pass} ⇒ 照旧进入分段（一个字不动）；{@code strip} ⇒ <b>用剥头后的
     * 文本</b>去分段（并照旧留一行"剥头"痕迹）；{@code drop} ⇒ <b>整条不发</b>（含它会被切成的所有段）。
     * 逐段那道闸<b>保留</b>（两道都留：整条过一遍，段里再有头也照样拦）。</p>
     *
     * <p><b>2026-09-22「内部文字外泄防线批」G1 起，这道整条口多了一条判据</b>：正文里<b>至少两行</b>
     * 各自呈现"基板事实块的写法"（{@code 键:} 紧跟结构化值 / {@code {"type":"…} 形态；全角、大小写、
     * 空白都归一化）⇒ 同样<b>整条不发</b>（判据 {@code qq.InternalFacts}，理由带
     * {@code rule=internal_fact}；真机依据：2026-09-22 09:06 群 543986616 那 14 条泄漏连发）。
     * 它放在这条整条口上而不是逐段口上，正是因为它必须<b>行级 fail-closed</b>：逐段看会漏掉一半；
     * 阈值取"两行"而不是"一行里凑够两个词"是**返工**要求 —— 后者在独立复核的 11 组合法句里
     * 误伤了 8 组（用户什么都收不到）。</p>
     *
     * <p><b>计数口径（自证用）</b>：整条被丢时 {@code blockedParts} 记 <b>1</b>（"一条完整的回复"，
     * 而不是"它会被切成几段"——那时还没分段，不编数字），并额外打一行
     * {@code [qq] 整条拦截：…（不进分段）}；整批事实行（讲的是分段后的条数）在这种情况下不打印。</p>
     *
     * <p><b>2026-09-23「出站核心批」①：这道整条口又多了三条判据（都判在"去掉分段标记后的视图"上）</b>
     * —— 起因是 {@code 弄死<split>你} 这种写法：段发生在 stage/闸<b>之前</b>，两段各自干净
     * （红线的词被劈成两半就两边都不命中）⇒ 两条都照发、零拦截。三条依次是：</p>
     * <ol>
     *   <li><b>红线（不可替换类目）</b>：整条视图命中威胁/自伤 ⇒ <b>整批不发</b>
     *       （{@link Redline#veto}，词表仍是 {@code data/prompts/redline.md} 一处）；</li>
     *   <li><b>红线（可替换类目的跨段劈开）</b>：整条视图里能换到词、逐条却一条都换不到
     *       （词正好跨在切点上）⇒ <b>整批不发</b>（{@code rule=redline residual=split}，
     *       与 {@code qq.Api} 段数组那条 {@code residual=split} 同一口径）。可替换类目<b>正常</b>情况
     *       仍交给逐段 {@link #gate} 就地换词 —— 整条口不换词（一换就得重新分段）；</li>
     *   <li><b>标记被劈开</b>：某个 {@code <split>} 正好落在 {@code [[mood:…]]} 的跨度**里面**
     *       ⇒ <b>整批不发</b>（{@code rule=mood_mark residual=split}）。判据是<b>位置</b>不是形状：
     *       把分段标记删掉得到整条视图，被删的那个下标若落在某枚标记的跨度内（{@link #splitMarkFact}）
     *       ⇒ 那枚标记是被劈开的。比工单口径（只丢被劈的那一段）更严一点：不整批丢的话，
     *       另外半截（{@code od:做不到]]}）会单独发进群。</li>
     * </ol>
     * <p><b>为什么这里不做标记族剥离</b>（{@link sair.v4.qq.MoodMarks#strip}）：那会让
     * {@code 情绪} 看不到 {@code [[mood:转述]]} ⇒ 转述轮错误 @ 人（用户可见回归）。上面第 3 条只
     * <b>判位置、不剥字</b>。另外"去掉分段标记后的视图"不需要另算一遍：{@code judged} 本来就是
     * {@code MarkerTags.strip} 过的，而 {@code <split>} 就在那张白名单里；这里的 {@link #desplit}
     * 只用于**记住标记被删在哪个下标**（第 3 条要的坐标）。</p>
     *
     * <p><b>★ 2026-09-28「协议标记漏出」批再加一条（分段之前）</b>：协议标记在<b>进分段之前</b>
     * 就被剥掉（{@link sair.v4.qq.MarkerTags#stripKeepSplit}：与 {@code strip} 同一判据、
     * 只把 {@code <split>} 原样留给那把尺子）。理由是长度硬切会把一枚写在末尾的长标记切在里面，
     * 两个残片各自都不再满足标记形状（真机 {@code sent id=2361}：前半片 {@code …tags="群规,陈欣欣,}
     * + 后半片 {@code @" />}）⇒ 逐条那道闸谁也认不出。没剥掉任何东西时它返回原对象
     * ⇒ {@code segBase == original}，"一个字都没多动"那条不变量照旧。</p>
     *
     * @return 放行时要继续处理的文本；整条该丢时返回 {@code null}
     */
    private static String wholeGate(Out out, String where, String judged, String original, int[] dropped,
                                    Segmenter seg, boolean group) {
        // ★ 2026-09-22「内部文字外泄防线批」G1：**行级**「基板事实块写法」跳闸 ⇒ 整批不发。
        //   判据只有 qq.InternalFacts 一处（纯函数，不读盘）；挂在这道**分段之前**的整条口上
        //   ⇒ QqSink / TaskSink 两条发送路一处覆盖。为什么必须**行级**：事故那 14 段里
        //   parts 4/6/7/10/11/12/13 一个键名都不含，只看"整条命中几个"只拦得住 6/14。
        //   ★ 口径在返工第 2 条收紧过：不是"提过哪个键名"，而是"这一行按事实块的写法写出来了"
        //     （键紧跟冒号 + 值以结构化记号开头；全角/大小写/空白都归一化），且**至少两行**各自成形状。
        //     旧口径（裸键名计数）在独立复核的 11 组合法句里误伤 8 组；收紧后 11 组 0 误伤、
        //     全库 assistant 0 误伤、事故重放仍然 0 条出站（原始输出见 tmp\v6-real\leakguard\rework\）。
        //   一行 warn：带 rule=internal_fact 与命中的形状名，**不含正文**。
        String leak = sair.v4.qq.InternalFacts.fact(judged);
        if (leak != null) {
            if (dropped != null) dropped[0] = 1;
            if (out != null) {
                out.warn(where + " 出站拦截：正文写成了基板事实块（" + leak
                        + "）→ 整条丢弃，不再进入分段");
            }
            return null;
        }
        // ★ 2026-09-28「协议标记漏出」批（族 B，整条口）：**半截/畸形协议标记** ⇒ 整批不发。
        //   为什么必须在**分段之前**也判一遍：分段（`<split>` / 空行 / 长度硬切）正好把一个
        //   没闭合的 `<at qq="…` 劈成两半时，两段各自都不再满足"属性形状 + 没有 `>`"
        //   （后半段里那个 `>` 或结尾位置都变了）⇒ 两个残片会各自进群。判在整条视图上一次覆盖。
        String protoHalf = sair.v4.qq.MarkerTags.halfFact(judged);
        if (protoHalf != null) {
            if (dropped != null) dropped[0] = 1;
            if (out != null) {
                out.warn(where + " 整条拦截：正文里有半截/畸形协议标记（" + protoHalf
                        + "）→ 不再进入分段");
            }
            return null;
        }
        Gate g = headGate(judged);          // ★ 只判自我记账头：工具调用标记照旧留给逐段那道闸
        if (g.drop()) {
            if (dropped != null) dropped[0] = 1;
            blocked(out, where, g.why);
            if (out != null) out.warn(where + " 整条拦截：这一条不再进入分段（" + g.why + "）");
            return null;
        }
        // ★ 2026-09-23「出站核心批」①：**先算出"真正要去分段的那份原文"**（剥过头就是剥头后的原文），
        //   再在它的"去掉分段标记视图"上判下面三条。注意 effective 是**原文**（带 <split>），
        //   因为分段还要按它切；flat 才是判据用的视图。
        String base = g.stripped ? sair.v4.qq.SelfEcho.withoutHead(original) : original;
        // ★ 2026-09-28「协议标记漏出」批：**协议标记必须在分段之前剥掉**。
        //   为什么：分段的长度是"硬切"，一枚写在末尾的长协议标记会被正好切在里面 ——
        //   真机 sent id=2361（dialog id=5214，182 字正文 + 群聊上限 180）的形状是
        //   前半片 `…<memory … tags="群规,陈欣欣,` + 后半片 `@" />`，两片各自都不再满足标记形状
        //   ⇒ 逐条那道闸（gate）一个都认不出，后半片原样落群。
        //   用 stripKeepSplit 而不是 strip：`<split>` 是分段自己的标记，必须原样活到分段之后。
        //   不变量：一个字都没剥掉时它返回**原对象** ⇒ 下面逐字与加这一层之前相同。
        String segBase = sair.v4.qq.MarkerTags.stripKeepSplit(base);
        java.util.List<Integer> cuts = new java.util.ArrayList<Integer>();
        String flat = desplit(segBase, cuts);
        // ①-1 不可替换类目（威胁 / 自伤）⇒ 整批不发。可替换类目不在整条口换词（交给逐段那段）。
        String rlCat = Redline.veto(flat);
        if (rlCat != null) {
            if (dropped != null) dropped[0] = 1;
            if (out != null) {
                out.warn(where + " 整条拦截：整条视图命中出站红线类目「" + rlCat + "」（rule=redline cat="
                        + rlCat + " view=desplit chars=" + flat.length() + "）→ 不再进入分段");
            }
            return null;
        }
        // ①-2 可替换类目被分段劈成两半（逐条一条都换不到）⇒ 整批不发（fail-closed，同 Api 口径）。
        String soft = softSplitFact(flat, segBase, seg, group);
        if (soft != null) {
            if (dropped != null) dropped[0] = 1;
            if (out != null) out.warn(where + " 整条拦截：正文命中出站内容闸（" + soft + "）→ 不再进入分段");
            return null;
        }
        // ①-3 分段标记正好切在一枚 [[mood:…]] **里面** ⇒ 整批不发（两半各自都不是标记、都会进群）。
        String mkCut = splitMarkFact(flat, cuts);
        if (mkCut != null) {
            if (dropped != null) dropped[0] = 1;
            if (out != null) {
                out.warn(where + " 整条拦截：自报标记被分段标记劈开（" + mkCut + "）→ 不再进入分段");
            }
            return null;
        }
        // pass ⇒ **原文**照旧（<split> 一类分段标记必须保住）；没剥过任何协议标记时
        // segBase 就是 original 本身（stripKeepSplit 返回原对象）⇒ 逐字节与加这一层之前相同。
        if (!g.stripped) return segBase;
        // strip ⇒ 对**原文**去头（不是对清洗后的文本去头：清洗会把 <split> 剥掉，那就没法按它切了）
        if (segBase.trim().isEmpty()) {
            if (dropped != null) dropped[0] = 1;
            blocked(out, where, "正文剥掉自我记账头之后什么都不剩（rule=self_head at=0 remain=0）");
            if (out != null) out.warn(where + " 整条拦截：这一条不再进入分段（剥完为空）");
            return null;
        }
        return gateText(out, where, Gate.strip(segBase));
    }

    /**
     * <b>去掉分段标记（{@code <split>}）后的视图</b>（形状与 {@link sair.v4.qq.Seg#byMarker} 同一份
     * {@link sair.v4.qq.Seg#MARKER}，大小写不敏感；不做别的清洗）。
     *
     * @param cuts 收下每个被删标记在<b>结果视图</b>里的下标（按出现次序）—— {@link #splitMarkFact} 用它
     *             判"这个标记是不是被切在了一枚 {@code [[mood:…]]} 里面"
     */
    private static String desplit(String text, java.util.List<Integer> cuts) {
        if (text == null || text.isEmpty()) return "";
        String m = sair.v4.qq.Seg.MARKER;
        StringBuilder sb = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (i + m.length() <= text.length() && text.regionMatches(true, i, m, 0, m.length())) {
                if (cuts != null) cuts.add(Integer.valueOf(sb.length()));
                i += m.length();
                continue;
            }
            sb.append(text.charAt(i));
            i++;
        }
        return sb.toString();
    }

    /**
     * <b>分段标记切在自报标记里面</b>（①-3 的判据本体，纯位置判据）：整条视图里逐枚找
     * {@code [[mood:…]]} 的跨度（{@code start} 到闭括号），若某个被删的分段标记正好落在跨度<b>内部</b>
     * ⇒ 返回一条结构事实。
     *
     * <p>判据只在 {@code qq.MoodMarks} 一处（{@link sair.v4.qq.MoodMarks#start}），这里不写第二套形状；
     * 本方法只做下标比较。跨度的两个端点不算"切在里面"：标记正好从一个切点开始、或正好在切点前结束，
     * 都是**正常写法**（{@code 甲<split>[[mood:做不到]]}）。</p>
     */
    private static String splitMarkFact(String flat, java.util.List<Integer> cuts) {
        if (flat == null || flat.isEmpty() || cuts == null || cuts.isEmpty()) return null;
        int from = 0;
        while (from < flat.length()) {
            int s = sair.v4.qq.MoodMarks.start(flat.substring(from));
            if (s < 0) return null;
            int at = from + s;
            int close = flat.indexOf(']', at);
            int end = close < 0 ? flat.length() : close;
            for (int i = 0; i < cuts.size(); i++) {
                int c = cuts.get(i).intValue();
                if (c > at && c < end) {
                    return "rule=mood_mark residual=split view=desplit at=" + at
                            + " cut=" + c + " chars=" + flat.length();
                }
            }
            from = at + 1;
        }
        return null;
    }

    /**
     * <b>可替换类目的词正好跨在分段切点上</b>（①-2 的判据本体）：整条视图里能换到词、而按同一把尺子
     * 分段、逐条（清洗 + 剥标记之后）却换不到同一个结果 ⇒ 返回结构事实（fail-closed）。
     *
     * <p>为什么需要它：工单口径是"可替换类目交给逐条换词"，但 {@code 手机<split>号} 这种写法
     * 两条各自都不带完整词 ⇒ 换词一次都不会发生，用户看到的还是那两个字。这里用**与真正分段同一把
     * 尺子**做一次干跑（{@link Segmenter#plan} / {@link sair.v4.qq.Seg#messages}，不改任何东西、不发任何东西），
     * 再把"整条换完"与"逐条换完拼起来"对比 —— 两者不一致当且仅当有词的命中跨在切点上
     * （与 {@code qq.Api} 段数组那条 {@code residual=split} 同一个写法）。能一致 ⇒ 返回 {@code null}
     * （正常路，逐条换词照旧）。</p>
     *
     * <p>逐条那一侧刻意先过 {@code PlainText.clean + MarkerTags.strip}（与 {@code QqSink}/{@code TaskSink}
     * 里逐条的清洗口径逐字相同）—— 否则 {@code 手机**号**} 这种"清洗后才成词"的写法会被误判成跨段。</p>
     */
    private static String softSplitFact(String flat, String base, Segmenter seg, boolean group) {
        if (flat == null || flat.isEmpty() || base == null || base.isEmpty()) return null;
        // ★ 2026-09-28「协议标记漏出」批：**两侧同一把尺子**。逐条那一侧是"清洗 + 剥标记 + 换词"，
        //   而整条这一侧原来用的是**没剥标记**的原文视图（{@code flat} 由 {@code original=raw} 来）
        //   ⇒ 只要正文里同时有"可替换类目的词"和<b>任何一枚可剥标记</b>（最典型的是她 @ 人时写的
        //   {@code <at qq="…"/>}），两侧必然不等 ⇒ 整条被 fail-closed 丢掉（用户什么都收不到）。
        //   本轮标记名单变大（V4 工具名并进来了）⇒ 这条缝的误伤面跟着变大，故在此对齐：
        //   整条这一侧也过同一个 {@code MarkerTags.strip}（判据仍只有那一处，不写第二套）。
        //   **方向只收紧不放松**：真正跨切点的红线词两侧依然换不到 ⇒ 照样 {@code residual=split} 整批不发。
        String markFlat = sair.v4.qq.MarkerTags.strip(flat);
        String whole = Redline.replace(markFlat);
        if (whole.equals(markFlat)) return null;             // 整条视图里没有可替换词 ⇒ 不关它的事
        java.util.List<String> parts = seg == null
                ? sair.v4.qq.Seg.messages(base, 1200)
                : seg.plan(base, group);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String p = sair.v4.qq.MarkerTags.strip(sair.v4.qq.PlainText.clean(parts.get(i)));
            sb.append(Redline.replace(p));
        }
        if (whole.equals(sb.toString())) return null;         // 逐条换完拼起来 = 整条换完 ⇒ 逐条覆盖得住
        return "rule=redline residual=split view=desplit chars=" + flat.length();
    }

    /**
     * 一次闸门的结论：{@code text} = 放行后<b>要发</b>的正文（{@code text == null} ⇔ 整条不发），
     * {@code why} = 拦截事实（给日志用，<b>不含正文</b>），{@code stripped} = 这一条被剥过<b>头部</b>，
     * {@code marked} = 这一条被剥过<b>{@code [[mood:…]]} 标记行</b>，
     * {@code rule} = 这一条被<b>红线换过词</b>（记的是命中的类目名，给日志用），
     * {@code desilent} = 这一条被剥过<b>贴边的协议暗号</b>（{@code <silent>} 一族；★ 2026-09-28 批新增，
     * 只在 {@code stripSilent} 真剥掉东西时才为 {@code true} —— 没剥时它与旧对象逐字节等价）。
     */
    private static final class Gate {
        final String text;
        final String why;
        final boolean stripped;
        final boolean marked;
        final String rule;
        final boolean desilent;
        private Gate(String text, String why, boolean stripped) {
            this(text, why, stripped, false, null);
        }
        private Gate(String text, String why, boolean stripped, boolean marked, String rule) {
            this(text, why, stripped, marked, rule, false);
        }
        private Gate(String text, String why, boolean stripped, boolean marked, String rule, boolean desilent) {
            this.text = text; this.why = why; this.stripped = stripped;
            this.marked = marked; this.rule = rule; this.desilent = desilent;
        }
        static Gate pass(String text) { return new Gate(text, null, false); }
        static Gate strip(String text) { return new Gate(text, null, true); }
        static Gate drop(String why) { return new Gate(null, why, false); }
        boolean drop() { return text == null; }
    }

    /**
     * 放行后的正文；剥过头 / 剥过标记行 / 换过词，各留一行痕迹 —— 改写绝不静默
     * （只报长度与类目名，<b>不含正文</b>）。
     */
    private static String gateText(Out out, String where, Gate g) {
        if (out != null) {
            if (g.stripped) {
                out.dim(where + " 出站剥头：正文带自我记账头 → 剥掉头部发出（chars=" + g.text.length() + "）");
            }
            if (g.marked) {
                out.dim(where + " 出站剥标记：正文带 [[mood:…]] 自报标记 → 剥掉标记及其之后的正文发出（chars="
                        + g.text.length() + "）");
            }
            if (g.desilent) {
                out.dim(where + " 出站剥暗号：正文首/尾贴着协议暗号（<silent> 一族）→ 剥掉那个 token 再走后面几道闸"
                        + "（chars=" + g.text.length() + "）");
            }
            if (g.rule != null) {
                out.dim(where + " 出站换词：命中出站红线类目「" + g.rule + "」 → 命中词已换成替换串（chars="
                        + g.text.length() + "）");
            }
        }
        return g.text;
    }

    /** 记一行"拦下了"（结构事实，无正文 —— 与 {@code [qq] msg … chars=} 同口径）。 */
    private static void blocked(Out out, String where, String fact) {
        if (out != null) out.warn(where + " 出站拦截：" + fact + " → 整条丢弃");
    }

    /**
     * <b>★ 2026-09-28「协议标记漏出」批：剥协议标记 + 基板方括号标注，并留一行痕</b>。
     *
     * <p>剥的判据<b>只有一处</b>（{@link sair.v4.qq.MarkerTags#strip}：族 A 角度协议标记 +
     * 族 C 基板方括号标注，名字来自静态 30 名 ∪ 运行期工具名）。这一支只做两件事：
     * 调它、并在真剥掉东西时打一行 {@code warn}（{@code rule=proto_tag name=… chars=A→B}，
     * <b>只报名字与字符数，不含正文</b> —— 与 {@code ToolMarkup.fact} 同一口径）。
     *
     * <p><b>不变量</b>：没剥掉任何东西时下面一个字节都不走，返回值与
     * {@code MarkerTags.strip} 的原物逐字节相同（"零改动 = 零字节差"）。</p>
     */
    private static String stripMarks(Out out, String where, String text) {
        String cut = sair.v4.qq.MarkerTags.strip(text);
        if (out != null && !cut.equals(text)) {
            String f = sair.v4.qq.MarkerTags.fact(text, cut);
            out.warn(where + " 出站剥协议标记：" + (f == null ? "rule=proto_tag chars=" + text.length() : f));
        }
        return cut;
    }

    /**
     * <b>★ 2026-09-23 批 6-②：三条降级打印路与本地控制台共用的那一支闸</b>。
     *
     * <p>降级路指的是"正文没有 QQ 落点、只打进控制台"的那几条：<b>①</b> NapCat 未连接
     * （{@link QqSink} / {@link TaskSink}）；<b>②</b> caller 没有群号/QQ（{@link QqSink}）；
     * <b>③</b> 定时任务没有目标（{@link TaskSink}，目标号 ≤ 0）；另加 {@link ConsoleSink}
     * （本地交互面）—— 五处调用点，一支实现。它们以前只看逐段那道 {@link #gate}，<b>整条口的事实块判据
     * （G1，{@link sair.v4.qq.InternalFacts}）不在这条路上</b> —— 于是"NapCat 掉线 / 没有落点"
     * 时，写成基板事实块的正文会照原样打进控制台与日志（既有缺口，本轮之前如实记在
     * {@code notes\status-20260922.md} §5）。现在判据同源、位置同序：<b>先判事实块整条丢，再走逐段闸</b>。</p>
     *
     * <p>为什么放在这一处而不是每条路各写一遍：这些路的口径必须逐字一样（降级不是降纪律），
     * 且以后再加降级落点只改这一处。判据一律复用既有实现，不写第二套词表。</p>
     */
    private static void degradedPrint(Out out, String where, String clean) {
        if (out == null) return;
        // ★ 族 A / 族 C：降级打印路也是用户看得到的面（本地控制台）—— "降级不是降纪律"，
        //   剥的判据与 QQ 落点同源（MarkerTags 一处），并且剥完什么都不剩时同样不发。
        String before = clean;
        clean = stripMarks(out, where, clean);
        if (!clean.equals(before) && clean.trim().isEmpty()) {
            out.warn(where + " 出站拦截：正文剥掉协议标记之后什么都不剩（rule=proto_tag remain=0"
                    + " chars=" + before.length() + "）→ 整条丢弃，降级打印也不放行");
            return;
        }
        String leak = sair.v4.qq.InternalFacts.fact(clean);
        if (leak != null) {
            out.warn(where + " 出站拦截：正文写成了基板事实块（" + leak
                    + "）→ 整条丢弃，降级打印也不放行");
            return;
        }
        Gate g = gate(clean);
        if (g.drop()) { blocked(out, where, g.why); return; }
        out.print(gateText(out, where, g) + "\n", Out.Tone.NORMAL);   // 投递降级：不打出来这条就丢了
    }

    /**
     * <b>★ 2026-09-23 批 6-①(c)：止语点跨段生效（标记之后的每一段都不发）</b>。
     *
     * <p>为什么需要它：{@code [[mood:…]]} 的语义是<b>止语点</b>（标记所在行连同它之后的<b>全部</b>
     * 正文一起丢弃，见 {@link sair.v4.qq.MoodMarks#strip}），但那个剥离是<b>逐段</b>跑的（分段之后），
     * 而分段发生在 stage / 闸<b>之前</b> ⇒ 标记<b>后面那几段</b>根本不在它看得见的文本里，会照原样
     * 发进群。真机 2026-09-22 09:06 那一轮（她先写该说的那句、再接 {@code [[mood:违规]]}、再接
     * 2082 字自述推理）正是这一类：段内那半截被止住了，<b>段外那几段没有</b>。</p>
     *
     * <p>同一条纪律也管"劈开的标记"：长度硬切 / 空行分段正好落在标记中间时
     * （{@code 正文甲[[mo} + {@code od:做不到]]} 那种），前半段被逐段闸 fail-closed 丢掉，
     * 后半段的碎片却会单独发出去。这里把"第一段带标记的段之后的每一段"一起丢掉 ⇔ 标记的
     * 后半截无论长什么样都不会单独进群。</p>
     *
     * <p>判据只看"这一段里有没有标记形状"（{@link sair.v4.qq.MoodMarks#start} 认完整的与没闭合的，
     * {@link sair.v4.qq.MoodMarks#openTail} 认结尾只写到 {@code [[mo} 那种正前缀），
     * <b>不写第二套形状</b>；那一段自己照旧交给逐段那道闸（剥 / fail-closed）⇒ 既有的逐段语义
     * 一个字没变。位置：<b>分段之后、{@code stage} 之前</b> —— 与"分段发生在 stage 之前"同一层，
     * 且必须在 stage 之前（{@code 情绪} 的 stage 会把标记剥掉，之后这里就看不见它了）。</p>
     *
     * <p><b>已知残余</b>（如实，与逐段闸同一条口径）：切点正好落在 {@code [[} / {@code [[m}
     * 这两格（标记开头还没写出 {@code mo}）时，前后两段都没有可认的形状 ⇒ 认不出。
     * 口径一致的理由：正常正文真的会以"一个/两个方括号"收尾，不许误伤。</p>
     *
     * @return 没有要丢的段时返回<b>同一个 List 对象</b>（调用方据此做到"逐字节不变"）
     */
    private static java.util.List<String> markStopTail(Out out, String where,
                                                       java.util.List<String> parts) {
        if (parts == null || parts.size() < 2) return parts;
        int k = -1;
        for (int i = 0; i + 1 < parts.size(); i++) {
            String p = sair.v4.qq.MarkerTags.strip(sair.v4.qq.PlainText.clean(parts.get(i)));
            if (p.isEmpty()) continue;
            if (sair.v4.qq.MoodMarks.start(p) >= 0 || sair.v4.qq.MoodMarks.openTail(p) >= 0) {
                k = i;
                break;
            }
        }
        if (k < 0) return parts;
        int dropped = parts.size() - (k + 1);
        if (out != null) {
            out.warn(where + " 出站剥标记：第 " + (k + 1) + "/" + parts.size()
                    + " 段带 [[mood:…]]（止语点 = 它之后的全部正文）→ 之后那 " + dropped
                    + " 段一起丢弃（rule=mood_mark stop=parts chars=" + parts.get(k).length() + "）");
        }
        return new java.util.ArrayList<String>(parts.subList(0, k + 1));
    }

    /**
     * <b>★ 2026-09-28「贴边暗号」批（续）：长度分段正好把一个括号暗号劈成两半时，
     * 两个残片谁都不许单独进群</b>。
     *
     * <p><b>要修的形状</b>（写者实测原始输出：{@code %TEMP%\v4silent\evidence2\scratch-seg-split.log}）：
     * 群聊上限 24 时
     * 正文 {@code "AAAAAAAAAAAAAAAAAAAA <silent>"} 被切成 {@code "AAAAAAAAAAAAAAAAAAAA <sil"} +
     * {@code "ent>"}，<b>两条都真发出去了</b>（{@code sent[0]} / {@code sent[1]}）—— 两个残片各自
     * 既不满足"整条相等"也不满足"贴边"（判据都在 {@code agent.Agent} 一处），于是整条暗号的字面量
     * 以两半的形式进了群。这正是 {@link #markStopTail} 管的那一类（长度硬切劈开标记）的姊妹形状，
     * 位置也刻意与它<b>同一层</b>：<b>分段之后、{@code stage} 之前</b>。</p>
     *
     * <p><b>为什么口径比 {@code markStopTail} 更精确（不许整段砍尾巴）</b>：止语点那条纪律对
     * {@code [[mood:…]]} 是"标记之后全丢"，因为标记的语义就是"从这儿闭嘴"；而 {@code <silent>} 的
     * 语义是"<b>这一条</b>别出声"、不是"从这儿闭嘴"，且 {@code <s} 这类前缀在正常正文里真的会出现
     * （HTML / 命令行片段被切在 {@code <s} 上）。所以这里的判据只有一条，且是<b>构造性</b>的：</p>
     * <ul>
     *   <li>第 k 段的<b>结尾</b>取非空正前缀 {@code pre}、第 k+1 段的<b>开头</b>取其余部分 {@code rem}，
     *       两者归一化后拼起来<b>恰好等于</b> {@code <silent>} 词表里那 6 个带括号写法之一
     *       （判据只有 {@link sair.v4.agent.Agent#silentSeam} 一处 ⇒ 归一化口径与
     *       {@code Agent.silent}/{@code Agent.stripSilent} 逐字相同）⇒
     *       <b>把 {@code pre} 从第 k 段尾部剪掉、把 {@code rem} 从第 k+1 段开头剪掉</b>；</li>
     *   <li><b>对不上就一个字都不动</b>：{@code "…<s" + "cript>"}、{@code "…[s" + "tart]"}、
     *       {@code "…<sil" + "ent 不是暗号"} 这类"拼起来不是完整暗号"的一律照旧原样发（零误伤）；</li>
     *   <li>剪完<b>空掉的那一段不发</b>（整段就是半截暗号 ⇒ 那一条没有正文）。</li>
     * </ul>
     *
     * <p><b>为什么排在 {@link #markStopTail} 之前</b>：它会把"带 {@code [[mood:…]]} 的那一段之后"
     * 整段丢掉 —— 若暗号的后半截正好落在被丢掉的那一段里，先剪前一半才不会让上一段剩下一个孤零零的
     * {@code "<sil"} 照发出去（先剪 = 覆盖全部原始接缝，后剪 = 只覆盖存活下来的接缝）。</p>
     *
     * <p><b>已知残余</b>（如实）：① <b>跨三段</b>才拼出来的（段极小才会发生）<b>不做</b> ——
     * 判据只看"相邻两段的接缝"；② 判据落在<b>原段</b>上（逐段清洗之前）：若清洗会删掉 token 中间
     * 的字符（例如 {@code "<si**l"} + {@code "ent>"}），原段拼起来不是暗号 ⇒ 认不出（照发）。
     * 反过来这正是"不误伤"的代价：判在清洗视图上就得反推原段下标，猜错就会剪坏正常正文；
     * ③ 单侧残片（只有前半截或只有后半截、<b>没有配对的另一半</b>）不动它 —— 与
     * {@code markStopTail} 的既有口径一致（正常正文真的会以 {@code <s}/{@code [[} 这类前缀收尾，
     * 单看一侧认不出）。</p>
     *
     * @return 一个字都没动时返回<b>同一个 List 对象</b>（调用方据此做到"逐字节不变"；
     *         与 {@link #markStopTail} / {@link #capParts} 的契约相同）
     */
    private static java.util.List<String> seamSilent(Out out, String where,
                                                     java.util.List<String> parts) {
        if (parts == null || parts.size() < 2) return parts;
        java.util.ArrayList<String> cut = null;
        for (int k = 0; k + 1 < parts.size(); k++) {
            String a = parts.get(k);
            String b = parts.get(k + 1);
            int[] hit = seamCut(a, b);
            if (hit == null) continue;
            if (cut == null) cut = new java.util.ArrayList<String>(parts);
            cut.set(k, a.substring(0, hit[0]));
            cut.set(k + 1, b.substring(hit[1]));
            if (out != null) {
                out.warn(where + " 出站剪暗号：第 " + (k + 1) + "/" + parts.size() + " 段的结尾与第 "
                        + (k + 2) + " 段的开头正好拼成一个协议暗号（<silent> 一族）→ 半截 token 从两段剪掉"
                        + "（rule=silent_token residual=seam cut=" + (a.length() - hit[0]) + "+" + hit[1]
                        + " segs=" + parts.size() + "）");
            }
        }
        if (cut == null) return parts;
        java.util.ArrayList<String> keep = new java.util.ArrayList<String>(cut.size());
        for (int i = 0; i < cut.size(); i++) {
            String p = cut.get(i);
            if (p == null || p.trim().isEmpty()) continue;    // 剪空的那一段不发
            keep.add(p);
        }
        return keep;
    }

    /**
     * <b>相邻两段的接缝上有没有一个被劈开的括号暗号</b>（{@link #seamSilent} 的判据本体）：
     * 返回 {@code {从第 k 段哪个下标起剪, 从第 k+1 段剪掉几个字符}}，没有返回 {@code null}。
     *
     * <p>枚举是<b>有界</b>的（{@link #SEAM_WINDOW} 个字符以内，两个方向各一遍），且唯一的判断是
     * {@link sair.v4.agent.Agent#silentSeam} —— "拼起来恰好是一个完整暗号"。这里不写第二套词表、
     * 也不做"像暗号前缀"的猜测：{@code pre} 短到只有一个 {@code "<"} 也算（只要另一半把它补全）。</p>
     */
    private static int[] seamCut(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return null;
        int an = Math.min(a.length(), SEAM_WINDOW);
        int bn = Math.min(b.length(), SEAM_WINDOW);
        for (int i = 1; i <= an; i++) {                       // pre = a 结尾 i 个字符（非空）
            String pre = a.substring(a.length() - i);
            for (int j = 1; j <= bn; j++) {                   // rem = b 开头 j 个字符（非空）
                if (sair.v4.agent.Agent.silentSeam(pre, b.substring(0, j))) {
                    return new int[] { a.length() - i, j };
                }
            }
        }
        return null;
    }

    /**
     * 接缝判据的搜索窗口（字符）：词表里最长的写法是 {@code <silent>}（8 字），窗口取 24 已把
     * "全角/大小写/半角空格混写"（例如 {@code "< sil ent >"}）都罩住；窗口外的写法（残片之间隔着
     * 二十多个空格那种）认不出 ⇒ 照发，如实记在 {@link #seamSilent} 的残余里。
     */
    private static final int SEAM_WINDOW = 24;

    /**
     * <b>★ 2026-09-22「内部文字外泄防线批」G3：一轮条数上限（分段之后、发送之前）</b>。
     *
     * <p>判据是纯结构：{@code parts.size() > cap} ⇒ <b>只发前 cap 条</b>，其余丢弃，
     * 并留一行 warn（{@code rule=parts_cap n=… cap=…}）。{@code cap <= 0} = 不限制
     * （键是可关的：{@code replyMaxParts}）。</p>
     *
     * <p>为什么要有它：它是<b>内容无关</b>的那一层兜底 —— 内容判据（工具调用标记 / 记账头 / 暗号 /
     * {@code [[mood:…]]} / 出站红线 / 事实块写法）全部漏掉时，爆炸半径仍被钉在 cap 条以内；
     * 顺带消掉 QQ「每群每分钟只能发 10 条」的限流失败（事故那一轮 14 条里 4 条正是因此被拒收）。</p>
     *
     * <p>为什么是这一段而不是 {@code Agent} 或落库那一层：只影响<b>投递</b>，不改内容、不改
     * {@code dialog} 落库（落库在 {@code Agent.java:220}，早于 {@code say}）。
     * 口径与位置见 {@link Segmenter#maxParts()}。</p>
     *
     * @return 未超限时返回<b>同一个 List 对象</b>（调用方据此做到"逐字节不变"）
     */
    private static java.util.List<String> capParts(Out out, String where,
                                                   java.util.List<String> parts, int cap) {
        if (parts == null || cap <= 0 || parts.size() <= cap) return parts;
        int n = parts.size();
        if (out != null) {
            out.warn(where + " 本批条数超上限（rule=parts_cap n=" + n + " cap=" + cap
                    + "）→ 只发前 " + cap + " 条，其余 " + (n - cap) + " 条丢弃");
        }
        return new java.util.ArrayList<String>(parts.subList(0, cap));
    }

    /**
     * <b>什么都不做的落点</b>（一个字都不发、也不打控制台）。
     *
     * <p>唯一的用途：给"<b>只能记账、不许说话</b>"的钩子当落点 —— 现在只有一条这样的路：
     * {@code QqGateway} 的 {@code on_notice} 派发（主人裁定「通知接入…<b>不必当即触发响应</b>」，
     * 见 {@code notes\workorders\N1-notices-ingest.md} 与 {@code tmp\n1\REPORT.md}）。
     * 那条路上钩子拿到的 {@code Turn.sink()} 必须是它：{@code h.say(...)} 能调、但<b>发不出去</b>。</p>
     *
     * <p>为什么不是"给钩子传 {@code null} 的 {@link sair.v4.ctx.Turn}"：{@code Boot.host(sk,c,null)}
     * 的 {@code Host.say} 在 {@code t == null} 时会<b>退回控制台落点</b>（那是为 on_timer 那条路设计的），
     * 于是"钩子说什么都发不出去"这句话就不成立了。传一个静默落点才是构造性的保证：
     * 这条路上没有任何一条通路能到用户（QQ 或控制台）。</p>
     */
    public static final class SilentSink implements Sink {
        public static final SilentSink I = new SilentSink();
        @Override public void say(String text) { }
        @Override public void stream(String delta) { }
        @Override public void notice(String text) { }
    }

    /** 本地控制台落点（≡ 主人交互）。 */
    public static final class ConsoleSink implements Sink {
        private final Out out;
        private volatile boolean streaming = false;
        /** 流式时扣住的尾巴：`**` `#` `|` 这类标记可能被劈成两批，扣住几个字符等下一批一起清。 */
        private String hold = "";
        public ConsoleSink(Out out) { this.out = out; }

        @Override
        public void say(String text) {
            if (streaming) {
                if (!hold.isEmpty()) {
                    out.print(sair.v4.qq.PlainText.cleanChunk(hold), Out.Tone.NORMAL);
                    hold = "";
                }
                out.print("\n", Out.Tone.NORMAL);   // 流式已打完内容，只补一个换行
                streaming = false;
                return;
            }
            // 纯文字口径（主人裁 2026-09-18：控制台输出也严禁 Markdown）
            String clean = sair.v4.qq.PlainText.clean(text == null ? "" : text);
            // ★ 与 QQ / 定时落点**同一道闸**（审计 §7-5 的缝）：控制台是"无 QQ 落点 / 降级打印"
            //   时唯一能看到正文的地方 —— 协议暗号、自我记账头、[[mood:…]] 标记、工具调用标记、
            //   出站红线在这一层同样不许当正文打出来。判据同源（gate 一处），不写第二套。
            //   ★ 2026-09-23「出站核心批」②：G1「基板事实块写法」也补进这条降级路（degradedPrint 一处）。
            degradedPrint(out, "[console]", clean);
        }

        @Override
        public void stream(String delta) {
            if (delta == null || delta.isEmpty()) return;
            streaming = true;
            String s = hold + delta;
            hold = "";
            int n = s.length();
            int cut = n;
            while (cut > 0 && cut > n - 3) {
                char c = s.charAt(cut - 1);
                if (c == '*' || c == '_' || c == '`' || c == '#' || c == '|' || c == '>' || c == '~') cut--;
                else break;
            }
            if (cut < n) {
                hold = s.substring(cut);
                s = s.substring(0, cut);
            }
            String cleaned = sair.v4.qq.PlainText.cleanChunk(s);
            if (!cleaned.isEmpty()) out.print(cleaned, Out.Tone.NORMAL);
        }

        @Override
        public void notice(String text) { out.dim(text); }
    }

    /**
     * QQ 落点：回复发到原会话。NapCat 未连接时降级到控制台并明确提示，
     * 但不影响其余能力（其它工具照常可用）。
     *
     * <p><b>不回显回复正文</b>：回复是"发出去"的动作，控制台只打一行结构事实
     * （{@code [qq→] sent … chars=…}）—— 默认口径是"只打逻辑怎么走"，
     * 正文只在 {@code logVerbose=true} 时打（构造参数 {@code echo} 由网关按该配置传入）。</p>
     *
     * <p><b>分段发送</b>：一条太长的回复拆成几条发（见 {@link sair.v4.qq.Seg}）——
     * 模型自己写 {@code <split>} 就按它切；没写就按"空行段落 → 长度上限"切，群聊还按
     * {@code replyGroupMaxChars} 再压短一档；条与条之间按 {@code replySplitDelayMs}(+抖动) 停一下，
     * 读起来才像人在连着说话。上限/停顿都能在 config.json 里调（见 {@link Segmenter}）。</p>
     */
    public static final class QqSink implements Sink {
        private final Api api;
        private final sair.v4.qq.Sender sender;
        private final Caller caller;
        private final Out out;
        private final boolean echo;
        /** 分段口径（上限、停顿）；为 null 时按出厂默认（不分段的后备路径）。 */
        private final Segmenter seg;
        /** 好感度子系统（她自己的加减标记在这里执行）；为 null = 本路径不管好感度。 */
        private final sair.v4.auth.Favor favor;

        public QqSink(Api api, Caller caller, Out out, boolean echo) {
            this(api, caller, out, echo, null);
        }

        public QqSink(Api api, Caller caller, Out out, boolean echo, Segmenter seg) {
            this(api, caller, out, echo, seg, null);
        }

        public QqSink(Api api, Caller caller, Out out, boolean echo, Segmenter seg, sair.v4.auth.Favor favor) {
            this(api, sair.v4.qq.Sender.of(api), caller, out, echo, seg, favor);
        }

        /** 直接给"发送器"（探针用记录器；生产走上面的 {@link Api} 构造）。 */
        public QqSink(sair.v4.qq.Sender sender, Caller caller, Out out, boolean echo, Segmenter seg) {
            this(null, sender, caller, out, echo, seg, null);
        }

        private QqSink(Api api, sair.v4.qq.Sender sender, Caller caller, Out out, boolean echo, Segmenter seg,
                       sair.v4.auth.Favor favor) {
            this.api = api;
            this.sender = sender == null ? sair.v4.qq.Sender.of(null) : sender;
            this.caller = caller;
            this.out = out;
            this.echo = echo;
            this.seg = seg;
            this.favor = favor;
        }

        @Override
        public void say(String text) {
            if (text == null) return;
            String raw = text.trim();
            if (raw.isEmpty()) return;
            // 注意顺序：**先分段再剥标记** —— <split> 是分段标记，剥早了就没法按它切了
            if (api != null && !api.available()) {
                out.warn("[qq] " + Api.NOT_CONNECTED + "：回复只打进控制台");
                // ★ 批 6-②（G1 上降级打印路）之后，这一支只剩一句话：整条口（含 G1 事实块）与逐段闸
                //   都在 degradedPrint 里
                degradedPrint(out, "[qq]", stripMarks(out, "[qq]", sair.v4.qq.PlainText.clean(raw)));
                return;
            }
            if (caller == null || (caller.groupId() <= 0 && caller.qq() <= 0)) {
                out.warn("[qq] 没有可用的会话落点（caller 缺群号/QQ），回复只打进控制台");
                degradedPrint(out, "[qq]", stripMarks(out, "[qq]", sair.v4.qq.PlainText.clean(raw)));
                return;
            }
            // ★ FIX-ECHO 补-9：**整条先过一次闸**，再分段 —— 否则头正好跨在分段切点上时，
            //   两段各自干净、全文照发（用户连着收到的两条拼起来就是完整的头）。
            String wholeClean = stripMarks(out, "[qq]", sair.v4.qq.PlainText.clean(raw));
            int[] wholeDropped = new int[1];
            String segBase = wholeGate(out, "[qq]", wholeClean, raw, wholeDropped, seg, caller.isGroup());
            if (segBase == null) return;                  // 整条不发（含它会被切成的所有段）
            java.util.List<String> parts = seg == null
                    ? sair.v4.qq.Seg.messages(segBase, 1200)
                    : seg.plan(segBase, caller.isGroup());
            // ★ 2026-09-28「贴边暗号」批（续）：长度分段正好劈开一个括号暗号 ⇒ 两个残片谁都不许单独进群。
            //   位置与 markStopTail 同一层（分段之后、stage 之前），且**排在它之前**：被它丢掉的那一段
            //   若是暗号的后半截，先把前一半剪掉才不会剩下孤零零的 "<sil" 照发（见 seamSilent 的说明）。
            parts = seamSilent(out, "[qq]", parts);
            // ★ ①(c)：止语点跨段生效 —— 第一段带 [[mood:…]] 的段之后的每一段一起丢掉（见 markStopTail）。
            //   位置刻意在 stage 之前：情绪那层会把标记剥掉，之后这里就看不见它了。
            parts = markStopTail(out, "[qq]", parts);
            // 她自己的好感度加减标记：**在出站 stage 之前**就执行掉。
            // 为什么放在 stage 前面：stages 会否决/洗空某一批（实测「情绪」的禁词否决会把整批打掉），
            // 而好感度是"她的判断"，不是"这句话的一部分"——话被拦下来，账照样要记；
            // 反过来，标记在 stage 之前就消失，别的 stage 看到的正文是干净的。
            if (favor != null) {
                java.util.List<String> cleaned = sair.v4.qq.FavorTags.apply(parts, caller, favor, out);
                if (cleaned != null) parts = cleaned;
            }
            // 出站管线：分段与发送之间（零 stage = 原样，见 stage()）
            parts = stage(caller, parts, caller.isGroup(),
                    caller.isGroup() ? caller.groupId() : caller.qq(), echo, seg);
            // ★ G3：一轮条数上限（分段之后、发送之前）—— 超限只发前 N 条 + 一行 warn（rule=parts_cap）
            parts = capParts(out, "[qq]", parts, seg == null ? Segmenter.DEF_MAX_PARTS : seg.maxParts());
            int blockedParts = 0;                       // 本批被这道闸拦下的条数（工具调用标记 / 自我记账头 / 协议暗号）
            if (wholeDropped[0] > 0) blockedParts += wholeDropped[0];   // 整条拦下记 1（还没分段，不编条数）
            for (int i = 0; i < parts.size(); i++) {
                // 纯文字口径（主人裁 2026-09-18：严禁 Markdown）：先清洗格式符号、再剥控制标记
                String part = stripMarks(out, "[qq]", sair.v4.qq.PlainText.clean(parts.get(i)));
                if (part.isEmpty()) continue;
                // ★ 最后一道闸（唯一一处）：① 模型的工具调用标记（真机事故 2026-09-16 22:12）⇒ 整条丢；
                //   ② 自我记账头（真机 2026-09-20：私聊 sent id=1003/709）⇒ 头在开头就剥掉再发，
                //      头在中段/剥完为空/头残缺 ⇒ 整条丢。命中就丢这一条（其余条照发），
                //      日志只写结构事实。见 gate(...) 的说明。
                Gate g = gate(part);
                if (g.drop()) { blocked(out, "[qq]", g.why); blockedParts++; continue; }
                part = gateText(out, "[qq]", g);
                if (echo) out.dim("[qq→] " + part);           // 只有 logVerbose 才打正文
                // 条间停顿：拟人化（最后一条之后不停）
                if (i > 0 && seg != null) seg.pause();
                String err = sender.send(caller.isGroup(),
                        caller.isGroup() ? caller.groupId() : caller.qq(), part);
                if (err != null) {
                    out.warn("[qq] 发送失败（" + err + "）：本条改打控制台");
                    out.print(part + "\n", Out.Tone.NORMAL);
                }
            }
            // 结构事实：发了几条、各多长（上限生效了没有，看这一行）
            if (out != null && parts.size() > 1) out.dim("[qq→] sent " + sair.v4.qq.Seg.fact(parts)
                    + " group=" + caller.isGroup());
            // 整批级别的拦截事实（只有真拦到了才打；没拦到 = 与加这道闸之前逐字节一致）
            if (blockedParts > 0 && out != null) {
                out.warn("[qq] 本批 " + parts.size() + " 条里拦下 " + blockedParts
                        + " 条（工具调用标记 / 自我记账头 / 协议暗号 / 自报标记 / 出站红线）；"
                        + "一个字的正文都没有发出去的那几条见上面的拦截行");
            }
        }

        @Override
        public void stream(String delta) {
            // QQ 不流式：等 final 一次发出，避免刷屏
        }

        @Override
        public void notice(String text) { out.dim(text); }
    }

    /** 定时/后台任务的落点：有目标就发 QQ，否则打控制台。 */
    public static final class TaskSink implements Sink {
        private final Api api;
        private final sair.v4.qq.Sender sender;
        private final Out out;
        private final boolean group;
        private final long target;
        private final boolean echo;
        /** 分段口径（与回复同一条：长结果也得分条发）。 */
        private final Segmenter seg;

        public TaskSink(Api api, Out out, boolean group, long target) {
            this(api, out, group, target, false);
        }

        /** {@code echo=true} 时把发出的正文也打进控制台（{@code logVerbose} 口径；默认只打结构事实）。 */
        public TaskSink(Api api, Out out, boolean group, long target, boolean echo) {
            this(api, out, group, target, echo, null);
        }

        public TaskSink(Api api, Out out, boolean group, long target, boolean echo, Segmenter seg) {
            this.api = api;
            this.sender = sair.v4.qq.Sender.of(api);
            this.out = out;
            this.group = group;
            this.target = target;
            this.echo = echo;
            this.seg = seg;
        }

        /** 直接给"发送器"（探针用；生产走 {@link Api} 构造）。 */
        public TaskSink(sair.v4.qq.Sender sender, Out out, boolean group, long target, boolean echo, Segmenter seg) {
            this.api = null;
            this.sender = sender == null ? sair.v4.qq.Sender.of(null) : sender;
            this.out = out;
            this.group = group;
            this.target = target;
            this.echo = echo;
            this.seg = seg;
        }

        /**
         * <b>投递通路</b>：目标会话键（{@code group:<群号>} / {@code qq:<QQ>}）。
         * <p>给 {@code Agent.ask} 用：正文会被直接投到这里的回合，纪律与普通回复不同（只写对用户说的话，
         * 不写回执腔）。号 ≤ 0（没目标、降级打控制台）时返回 {@code null} —— 那时正文没有落点。</p>
         */
        @Override
        public String deliverTo() {
            if (target <= 0L) return null;
            return (group ? "group:" : "qq:") + target;
        }

        @Override
        public void say(String text) {
            if (text == null) return;
            String raw = text.trim();
            if (raw.isEmpty()) return;
            String body = stripMarks(out, "[task]", sair.v4.qq.PlainText.clean(raw));
            if (api != null && !api.available() && target > 0) {
                if (out != null) out.warn("[task] " + Api.NOT_CONNECTED + "：结果只打进控制台");
                degradedPrint(out, "[task]", body);      // ★ ② 降级路也判 G1 事实块（同源、同序）
                return;
            }
            if (target > 0) {
                // ★ FIX-ECHO 补-9：与 QqSink 同一条纪律 —— **整条先过一次闸**，再分段
                int[] wholeDropped = new int[1];
                String segBase = wholeGate(out, "[task]", body, raw, wholeDropped, seg, group);
                if (segBase == null) return;               // 整条不发（含它会被切成的所有段）
                java.util.List<String> parts = seg == null
                        ? sair.v4.qq.Seg.messages(segBase, 1200)
                        : seg.plan(segBase, group);
                // ★ 2026-09-28「贴边暗号」批（续）：与 QqSink 同一条纪律 —— 长度分段劈开的括号暗号，
                //   两个残片谁都不许单独进群（位置同样在 markStopTail 之前、stage 之前）。
                parts = seamSilent(out, "[task]", parts);
                // ★ ①(c)：与 QqSink 同一条纪律 —— 止语点跨段生效（位置同样在 stage 之前）
                parts = markStopTail(out, "[task]", parts);
                // 出站管线：分段与发送之间（零 stage = 原样）。定时任务没有调用者，caller 传 null。
                parts = stage(null, parts, group, target, echo, seg);
                // ★ G3：与 QqSink 同一条上限（QqSink / TaskSink 共用 capParts 一处判断）
                parts = capParts(out, "[task]", parts, seg == null ? Segmenter.DEF_MAX_PARTS : seg.maxParts());
                String firstErr = null;
                int blockedParts = wholeDropped[0];
                for (int i = 0; i < parts.size(); i++) {
                    String part = stripMarks(out, "[task]", sair.v4.qq.PlainText.clean(parts.get(i)));
                    if (part.isEmpty()) continue;
                    // ★ 与 QqSink 同一道闸（唯一一处）：工具调用标记 / 自我记账头 / 协议暗号
                    Gate g = gate(part);
                    if (g.drop()) { blocked(out, "[task]", g.why); blockedParts++; continue; }
                    part = gateText(out, "[task]", g);
                    if (i > 0 && seg != null) seg.pause();
                    String err = sender.send(group, target, part);
                    if (err != null && firstErr == null) firstErr = err;
                }
                // 结构事实：发给谁、几条、各多长；正文只在 echo（logVerbose）时出现
                if (out != null) {
                    out.dim("[task→] sent scope=" + (group ? "group" : "private") + " target=" + target
                            + " chars=" + body.length() + " " + sair.v4.qq.Seg.fact(parts)
                            + (blockedParts > 0 ? " blocked=" + blockedParts : "")
                            + (firstErr == null ? "" : " fail=" + firstErr)
                            + (echo ? " text=" + body : ""));
                }
                return;
            }
            if (out != null) {
                // ★ ② 无目标（结果只打控制台）同样走那一支闸：先判 G1 事实块，再走逐段闸
                degradedPrint(out, "[task]", body);
            }
        }

        @Override
        public void stream(String delta) { }

        @Override
        public void notice(String text) {
            if (out != null) out.dim(text);
        }
    }

    /**
     * 长文本分段（QQ 单条消息有长度上限；优先在换行/句号处切，避免把句子劈开）。
     */
    public static java.util.List<String> split(String text, int max) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text == null || text.trim().isEmpty()) return out;
        String rest = text;
        int limit = Math.max(16, max);
        while (rest.length() > limit) {
            int cut = -1;
            for (int i = Math.min(limit, rest.length() - 1); i > limit / 2; i--) {
                char c = rest.charAt(i);
                if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut <= 0 || cut > limit) cut = limit;
            String head = rest.substring(0, cut).trim();
            if (!head.isEmpty()) out.add(head);
            rest = rest.substring(cut);
        }
        String tail = rest.trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }
}
