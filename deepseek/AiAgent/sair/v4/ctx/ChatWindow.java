package sair.v4.ctx;

import com.google.gson.JsonObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.v4.Conf;
import sair.v4.auth.Caller;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.qq.SelfEcho;
import sair.v4.store.Store;

/**
 * <b>本会话最近 N 条聊天记录</b>（动态窗口）：每触发一个回合，现查一次"这个会话最近的
 * {@code chatWindowSize} 条记录"，做成一段有标签的事实块交给上下文。
 *
 * <h3>它替换掉了什么（主人 2026-09-16 的口径）</h3>
 * <p>主人原话：「取消自动快照，而是在触发动态取聊天记录一个窗口，这个窗口要求能被设置大小的，
 * 默认值 30 条，不论是私聊还是群聊。」于是原来的<b>游标快照</b>（{@code ctx.Snapshot}：
 * {@code kv: cursor:<session>} + 双上界 + {@code gap}/{@code skipped}）整条删掉，换成这里：
 * 不再有游标、不再有"上次触发点到这次触发点"的增量、不再有落库的进度状态 ——
 * <b>每一轮都重新取最近 N 条</b>，窗口大小只有一个旋钮 {@link Conf#chatWindowSize()}。</p>
 *
 * <p>代价与收益都说清楚：窗口按定义会在两次触发之间<b>重叠</b>（同一条消息可能被看两遍），
 * 消息密集时也可能<b>漏掉</b> N 条之外的部分（本类<b>不</b>做 COUNT、不谎报"还有多少条没带"，
 * 见下"一条查询"）。换来的是：她一定看得见"最近发生了什么" —— 包括<b>没有触发词的那些话</b>
 * （游标快照的窗口只覆盖"上次触发到现在"，触发词接话那条路根本不做快照，所以天生看不见）。</p>
 *
 * <h3>两个源（不是对称的，别当成 bug）</h3>
 * <ul>
 *   <li><b>群</b>（{@code group:<gid>}）→ {@code grouplog}：
 *       {@code WHERE group_id = ? ORDER BY id DESC LIMIT n} 再翻成时间顺序。
 *       列：{@code id, ts, user_id, nickname, content, msg_id}；</li>
 *   <li><b>私聊</b>（{@code qq:<qq>}）与<b>控制台</b>（{@code console}）→ {@code dialog}：
 *       {@code WHERE session = ? ORDER BY id DESC LIMIT n} 再翻成时间顺序。
 *       列：{@code id, ts, role, content}；两个角色都带，并按角色标注（{@code role} 是
 *       {@code user}/{@code assistant}）。</li>
 * </ul>
 *
 * <p><b>为什么私聊不进 {@code grouplog}</b>：{@code grouplog.group_id} 里<b>从来没有一行 ≤ 0</b>
 * （真机实测最小 70559059），私聊消息根本不在 {@code grouplog} 里 —— 它们只有 {@code dialog}
 * 一份。所以"群读 grouplog、私聊/控制台读 dialog"是数据本身的形状，不是取巧。</p>
 *
 * <h3>一条查询（性能口径）</h3>
 * <p>每回合<b>就这一条</b>查询（群去重时另有 1 条读对话历史的查询，见"去重"），
 * 按<b>主键 {@code id}</b> 排序取尾部 n 行 —— 只要读到 n 行就停，窗口外有多少历史都不扫。
 * 索引可走性、{@code EXPLAIN QUERY PLAN} 的实测见 {@code tmp/ctxwin/REPORT.md}。</p>
 *
 * <p><b>不报"还有多少条更早的"</b>：那需要一个 {@code COUNT(*)}（第二条查询），
 * 而本类刻意只花一条查询。所以事实里只有"这个窗口里有几行、多少字符"，
 * <b>没有一个编出来的完成度数字</b>。</p>
 *
 * <h3>去重（best effort，做到哪说到哪）</h3>
 * <ol>
 *   <li><b>窗口内部</b>：同一 {@code msg_id} 在窗口里出现两次（重连重放重复入库）只留最早那条
 *       （{@code dropped_repeat} 报条数）；</li>
 *   <li><b>与对话历史</b>（{@code CtxBuild.appendHistory} 接下来要注入的那些行）：
 *       <b>只对群窗口做</b>。群窗口来自 {@code grouplog}，而历史来自 {@code dialog}，
 *       同一句"被 @ 过的话"两张表各有一份 —— 内容相同且时间相近（±{@value #DUP_TS_SLACK_MS}ms）
 *       就当作同一条，窗口里不再重复列（{@code dropped_dup} 报条数）；</li>
 *   <li><b>私聊/控制台的窗口不做第 2 条</b>，而且这是刻意的：那个窗口与历史<b>本来就是同一张表的同一批行</b>，
 *       精确去重会把整块删空（实测 30/30 行重合），等于把主人要的窗口变成一行都没有。
 *       所以事实里那一格写成 {@code "dup_history":"same-table"}（<b>不是数字 0</b>）——
 *       读的人一眼看出"这块记录和历史是同一份东西"，而不是被一个假的 0 骗成"没有重复"。</li>
 * </ol>
 *
 * <h3>渲染</h3>
 * <pre>
 * chat_window: {"session":"group:123","source":"grouplog","size":30,"rows":12,"chars":812,…}
 * 【本会话最近 12 条聊天记录】
 * [09-16 12:03:11] 张三: 你好
 * </pre>
 * <p>一行一条：{@code [时间] 谁: 说了什么}。群行的人名取 {@code nickname}（没有就退 {@code #user_id}）；
 * 对话表的两行按角色标 {@link #ME}（{@code assistant} = 她自己说的）/ {@link #PEER}（{@code user}）。
 * 非文本消息（{@code content} 为空串或 NULL）如实标 {@link #EMPTY_TEXT}，不编内容。
 * 窗口里那些消息<b>已有的标记</b>若存在，末尾再跟一行 {@code marks: [...]}（见 {@link #marksOf}）。</p>
 */
public final class ChatWindow {

    /** 事实块里那一行元数据的前缀（键值形态，与 {@code media:} / {@code person:} 同一约定）。 */
    public static final String META_PREFIX = "chat_window: ";

    /** 标签行前缀（中文标签就是"这是本会话最近的聊天记录"，不是给模型看的指令）。 */
    public static final String TITLE_LEAD = "【本会话最近 ";
    /** 标签行后缀。 */
    public static final String TITLE_TAIL = " 条聊天记录】";

    /** 她自己说的话（{@code dialog.role = assistant}）在窗口里的标注。 */
    public static final String ME = "我";
    /** 对方说的话（{@code dialog.role = user}）在窗口里的标注。 */
    public static final String PEER = "对方";

    /**
     * 非文本消息（图片/表情/语音/文件…）在 {@code grouplog.content} 里是空串甚至是 NULL。
     * 如实标一行"没有文本"，<b>不编</b>"[图片]"这种猜出来的内容。
     */
    public static final String EMPTY_TEXT = "（无文本）";

    /** 窗口条数的下限：配置 {@code <=0} = 不带这个窗口（等价于关掉），所以真正的下限是 1。 */
    public static final int MIN_ROWS = 1;

    /**
     * 窗口条数的硬上限（{@code chatWindowSize} 写大了就在此夹住）。
     * <p>它防的是"配置写成 100000 等于把整库拉进上下文"——不是性能闸门（查询本身有 LIMIT），
     * 而是<b>上下文预算</b>的兜底。200 条 ≈ 群聊一天的量级。</p>
     */
    public static final int MAX_ROWS = 200;

    /**
     * <b>不截断</b>（主人 2026-09-16 裁定）：这一格原来是一条 {@code ROW_MAX_CHARS = 400} 的单条截断
     * （{@code Str.cut(..., 400)}，与删除前的快照同一口径），主人裁定<b>取消</b>——
     * 「她要看到完整的记录」，窗口里的每条记录<b>逐字照抄 {@code content}</b>，不再加省略号。
     *
     * <p>为什么可以：实测最坏的真实窗口是 5,469 字符（{@code tmp\winsize\MEASURE.md} §2.4/§5），
     * 而它旁边的预算 {@code historyMaxChars} 是 24,000 —— 22.79%。同处也<b>没有</b>整窗字符上限
     * （这条是主人的同一句裁定：不加整窗上限、也不加单条上限）。</p>
     *
     * <p>仍然保留的是：正文压单行（{@link Str#oneLine}，否则一行一条的渲染会被换行撑坏）。</p>
     */

    /**
     * 去重时"时间相近"的容差（毫秒）：同一条消息在 {@code grouplog} 与 {@code dialog} 里的
     * {@code ts} 不是同一个数（两条路各自 {@code currentTimeMillis()}，还隔着排队时间），
     * 所以内容相同之外还要时间相近才算同一条。
     */
    public static final long DUP_TS_SLACK_MS = 60000L;

    /** 记录时间格式（一行一条，只到秒）。 */
    private static final String TS_FMT = "MM-dd HH:mm:ss";

    private ChatWindow() {}

    /** 窗口条数（配置 {@code chatWindowSize}，默认 {@value sair.v4.Conf#DEF_CHAT_WINDOW_SIZE}）。 */
    public static int size(Conf conf) {
        if (conf == null) return 0;
        int n = conf.chatWindowSize();
        if (n <= 0) return 0;                       // <=0 = 不带这个窗口
        return Math.min(n, MAX_ROWS);
    }

    public static String facts(Conf conf, Store store, Caller caller) {
        return facts(conf, store, caller, null);
    }

    /**
     * 做这个会话的"最近 N 条聊天记录"事实块。
     *
     * @param caller 本轮的调用者（决定会话键与读哪张表）；{@code null} = 控制台
     * @return 可放进 {@link CtxBuild#ST_CHAT_WINDOW} 的整块文本；没有记录/读不出来 = 空串
     *         （空串 = 这一轮事实块里没有这一段，绝不写一段空壳）
     */
    public static String facts(Conf conf, Store store, Caller caller, Out out) {
        if (conf == null || store == null) return "";
        int size = size(conf);
        if (size <= 0) return "";
        String session = caller == null ? "console" : Str.trim(caller.session());
        if (Str.blank(session)) return "";
        boolean group = session.startsWith("group:");
        long gid = group ? groupId(session) : 0L;
        if (group && gid <= 0) return "";           // 认不出群号 = 读不了，不带这一段

        List<JsonObject> rows;
        try {
            rows = group ? tailGrouplog(store, gid, size) : tailDialog(store, session, size);
        } catch (Throwable t) {
            if (out != null) out.warn("[ctx] 本会话聊天记录窗口读取失败（这一轮不带窗口）: " + t);
            return "";
        }
        if (rows.isEmpty()) return "";

        int repeat = dropRepeat(rows, group);
        // ★ P0-1「窗口读侧 (b′)」第 2 步：窗口内"去头正文"相同的自我行/普通行，只留带头部那条。
        //   **必须排在 dropAlreadyInHistory 之前**：否则当"她那一行"在窗口、"她自己那句成品"
        //   在历史里时，第 3 步会先把带真 msg_id 的那行丢掉，第 2 步就再也没机会"留带头部的"。
        //   两步的顺序因此是判据的一部分，不是随手排的。
        int selfDup = dropSelfDupInWindow(rows);
        int dup = group ? dropAlreadyInHistory(rows, historyRows(conf, store, session)) : -1;
        if (rows.isEmpty()) return "";

        long selfId = 0L;
        try { selfId = conf.selfId(); } catch (Throwable ignored) { }
        String marks = marksOf(store, session, rows);
        return render(session, group ? "grouplog" : "dialog", size, rows, repeat, dup, selfDup, selfId, marks);
    }

    // ---------------------------------------------------------------- 两个源的两条查询

    /**
     * 群窗口：{@code grouplog} 里这个群<b>最新的</b> n 行（再翻成时间顺序）。
     *
     * <p><b>排序口径 {@code ORDER BY ts DESC, id DESC}</b>：它正好是索引
     * {@code idx_grouplog_group(group_id, ts)} 的<b>反向遍历</b>（同一 ts 的索引项按 rowid 升序排，
     * 所以 {@code id DESC} 也落在索引顺序里），计划里<b>没有 {@code USE TEMP B-TREE FOR ORDER BY}</b>，
     * 读到 n 行就停 —— 窗口外还有多少历史都不扫。</p>
     *
     * <p><b>为什么不是 {@code ORDER BY id DESC}</b>（本实现<b>唯一</b>一处偏离任务书原话的地方，
     * 有实测依据）：真机库副本上 {@code group:793669790}（13,311 行）实测 ——
     * {@code ORDER BY id DESC LIMIT 30} 的计划是
     * {@code SEARCH grouplog USING INDEX idx_grouplog_group (group_id=?) | USE TEMP B-TREE FOR ORDER BY}，
     * <b>32.6 ms/次</b>（临时 B 树：把该群<b>所有</b>行读出来排一遍再取 30）；换成本口径 <b>0.99 ms/次</b>，
     * 33 倍差距，而且前者随群大小线性增长（46k 行的群 ≈ 113ms）并每回合把上万行对象堆一遍。
     * 取舍：{@code id} 是入库顺序、{@code ts} 是消息时间，正常情况下两者一致；只有本机时钟回跳/乱序时
     * 两者才可能不同 —— 那时"按时间取最近 30 条"更符合"最近"的语义。
     * 两条查询的原始 {@code EXPLAIN QUERY PLAN} 与耗时见 {@code tmp/ctxwin/REPORT.md}。</p>
     */
    private static List<JsonObject> tailGrouplog(Store store, long groupId, int n) {
        // M4：多取一列 extra —— 引文（被引消息的正文/标签）就落在这一列里（qq\QuoteCache 写的
        // {"quote":{…}}），窗口行据此把"这条引了什么"拼进同一行（见 quoteSuffix）。
        // 只加一个尾列，索引可用性与"读到 n 行就停"都不变（见 §(g) 的计划断言）。
        List<JsonObject> desc = store.db().query(
                "SELECT id, ts, user_id, nickname, content, msg_id, extra FROM grouplog"
                        + " WHERE group_id = ? ORDER BY ts DESC, id DESC LIMIT " + n, groupId);
        Collections.reverse(desc);
        return desc;
    }

    /**
     * 私聊/控制台窗口：{@code dialog} 里这个会话最新的 n 行（同一口径、同一理由）。
     *
     * <p>索引口径同上：{@code ORDER BY ts DESC, id DESC} 是 {@code idx_dialog_session(session, ts)}
     * 的反向遍历，实测计划 {@code SEARCH dialog USING INDEX idx_dialog_session (session=?)}，
     * 没有临时排序；{@code ORDER BY id DESC} 则需要 {@code USE TEMP B-TREE FOR ORDER BY}。</p>
     */
    private static List<JsonObject> tailDialog(Store store, String session, int n) {
        // M4：同样多取 extra —— 私聊引用 id 有先例（dialog.extra.message_id 5 行），
        // 这一列加上之后"私聊那一行引了什么"也能在窗口里看见（本步不为私聊改 appendDialog 签名）。
        List<JsonObject> desc = store.db().query(
                "SELECT id, ts, role, content, extra FROM dialog"
                        + " WHERE session = ? ORDER BY ts DESC, id DESC LIMIT " + n, session);
        Collections.reverse(desc);
        return desc;
    }

    // ---------------------------------------------------------------- 去重

    /**
     * 窗口内部重复：同一个 {@code msg_id} 在窗口里出现两次时只留<b>最早</b>那条。
     * <p>只对群窗口有意义（{@code dialog} 的行按 {@code id} 唯一，重复不可能发生）。
     * {@code msg_id} 为空/认不出的行不参与（没有稳定身份可比）。</p>
     *
     * @return 丢掉的条数
     */
    private static int dropRepeat(List<JsonObject> rows, boolean group) {
        if (!group) return 0;
        Set<String> seen = new HashSet<String>();
        int dropped = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {          // 从最新往前看，先见到的留
            String mid = Str.trim(Str.nz(J.s(rows.get(i), "msg_id", "")));
            if (mid.isEmpty() || "0".equals(mid)) continue;
            if (!seen.add(mid)) { rows.remove(i); dropped++; }
        }
        return dropped;
    }

    /**
     * <b>P0-1「窗口读侧 (b′)」第 1 步</b>：窗口内的行，比较串改用<b>去头</b>后的正文。
     *
     * <p>她的标记行 {@code content} 带一个真 {@code msg_id} 头部（{@code [我发的 msg_id=N] }），
     * 而 {@code dialog} 里那条 {@code assistant} 行是<b>裸正文</b> —— 整串逐字相等<b>永不</b>命中。
     * 结果（T2 在只读库副本上实测）：低流量群最坏 <b>14/30 = 47%</b> 的窗口会被她自己的话挤掉
     * （{@code group:121873503} 的 30 行窗口跨 50.6 小时），机制就是这条"加了头部就永不相等"。
     * 比较前把头部剥掉（{@link SelfEcho#withoutHead}），"同一条话"就重新可比 ⇒
     * 她的成品句<b>净占用回到 0</b>（和加头部之前一样），而<b>别的通路发的中间句</b>
     * （历史里没有对应行）照旧保留 —— 那正是 P0-1 要补的价值。</p>
     *
     * <p><b>历史那一侧刻意不剥头</b>：历史来自 {@code dialog}，它本来就只有裸正文
     * （标记行是 {@code content} 带头部的那一份，不是历史行）。剥了反而会把"她自己说过的一句
     * 带头部的话"与"别人说的一模一样的话"错当同一条。</p>
     *
     * <p><b>误杀风险与既有那条同一类</b>：同一个人两分钟内说了两句一模一样的话、其中只有一句
     * 进过历史时，窗口里那句也会被丢掉 —— 与下面 {@link #dropAlreadyInHistory} 今天承认的代价
     * 逐字同一个（"代价是窗口少一行，历史里那句还在"）⇒ <b>不新增风险类别</b>。</p>
     *
     * <p>零新增查询、零新增字段：纯字符串处理，行都已在内存里。</p>
     */
    private static String cmpText(JsonObject r) {
        return SelfEcho.withoutHead(oneLine(r));
    }

    /**
     * 群窗口里"对话历史已经带上了"的行：内容相同 + 时间相近（±{@value #DUP_TS_SLACK_MS}ms）→ 丢掉。
     *
     * <p><b>能做什么</b>：被 @ / 触发过的那句话在 {@code dialog} 里有一条 {@code role=user} 的行
     * （{@code Agent.ask} 落库），在 {@code grouplog} 里也有一行 —— 这一条不再在窗口里重复出现。
     * <b>做不到什么</b>：① 两条路的 {@code ts} 各写各的，且 {@code content} 可能被
     * {@code stripAt} 改过（群里被 @ 的那条：{@code grouplog} 存 {@code plainText()}，
     * {@code dialog} 存去掉 @ 之后的正文），所以这只是<b>尽力而为</b>，命中数实测见报告；
     * ② 同一个人在两分钟内说了两句一模一样的话、其中只有一句进过历史时，
     * 窗口里那句也会被当成重复丢掉（代价是窗口少一行，历史里那句还在）。</p>
     *
     * <p><b>P0-1 起比较串是"去头正文"</b>（见 {@link #cmpText}）：窗口侧剥掉标记行头部，
     * 历史侧原样 —— 否则她自己的话<b>永不</b>与历史里那条 {@code assistant} 行相等。</p>
     *
     * @return 丢掉的条数
     */
    private static int dropAlreadyInHistory(List<JsonObject> rows, List<JsonObject> hist) {
        if (hist == null || hist.isEmpty()) return 0;
        Map<String, List<Long>> byText = new HashMap<String, List<Long>>();
        for (JsonObject h : hist) {
            String t = oneLine(h);
            if (t.isEmpty()) continue;
            List<Long> l = byText.get(t);
            if (l == null) { l = new ArrayList<Long>(); byText.put(t, l); }
            l.add(Long.valueOf(J.l(h, "ts", 0L)));
        }
        if (byText.isEmpty()) return 0;
        int dropped = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            String t = cmpText(rows.get(i));
            if (t.isEmpty()) continue;
            List<Long> l = byText.get(t);
            if (l == null) continue;
            long ts = J.l(rows.get(i), "ts", 0L);
            for (Long h : l) {
                if (Math.abs(h.longValue() - ts) <= DUP_TS_SLACK_MS) { rows.remove(i); dropped++; break; }
            }
        }
        return dropped;
    }

    /**
     * <b>P0-1「窗口读侧 (b′)」第 2 步</b>：同一批窗口行里"去头正文"相同的两行，
     * <b>丢掉不带头部的那条、留带头部的那条</b>（带真 {@code msg_id} ⇒ 信息更多）。
     *
     * <p>治什么：私聊落点只有 {@code dialog}，她的成品句已经有一行 {@code role=assistant}
     * （{@code Agent.ask} 落库），P0-1 的标记行又是一行 ⇒ 窗口里同一句话会<b>出现两次</b>
     * （T2 实测：{@code qq:1284688456} 的 17 条 {@code assistant} 行里 9 条落在 {@code sent} 窗口内，
     * 约每回合多一条重复）。群侧同理（她的成品句既进 {@code dialog} 又进 {@code grouplog}）。</p>
     *
     * <p><b>只动自我行</b>：判据是"这一行去头后与另一行的去头正文相同、且<b>至少有一行</b>
     * 是自我行"。两行都不是自我行 ⇒ 一个字都不动（那是"她说了两句一样的话"以外的情况，
     * 例如真群友重复发言 —— 窗口该如实列出，去重不是本步的目的）。</p>
     *
     * <p>从<b>最新</b>往前看（与 {@link #dropRepeat} 同向），先见到的那条留。
     * {@code 0}（库里 {@code msg_id} 的"没有"）不参与比较 —— 与 {@code dropRepeat} 同一口径。</p>
     *
     * @return 丢掉的条数
     */
    private static int dropSelfDupInWindow(List<JsonObject> rows) {
        int dropped = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            JsonObject newer = rows.get(i);
            boolean newerSelf = markedSelfRow(newer);
            String key = cmpText(newer);
            if (key.isEmpty() || "0".equals(key)) continue;
            for (int j = i - 1; j >= 0; j--) {
                JsonObject older = rows.get(j);
                boolean olderSelf = markedSelfRow(older);
                if (!newerSelf && !olderSelf) continue;         // 都不是她自己发的：不碰
                if (!key.equals(cmpText(older))) continue;
                if (newerSelf && !olderSelf) { rows.remove(j); dropped++; }        // 留带头部的新行
                else if (!newerSelf && olderSelf) { rows.remove(i); dropped++; }   // 留带头部的老行
                else { rows.remove(j); dropped++; }                                // 都带头部：留新的
                break;
            }
        }
        return dropped;
    }

    /**
     * 这一行是不是"她自己发的"的<b>标记行</b>（两个判据都要满足）。
     *
     * <p>只看 {@code content} 的头部前缀（{@link SelfEcho#isSelfContent}）—— 头部的拼接是唯一的
     * （{@link SelfEcho}），而且值一律过 {@code san} ⇒ 外部正文伪造不出这个前缀。
     * <b>不看 {@code extra.self}</b>：本步是"窗口内去重"，宁可少去重一行，也不因为 {@code extra}
     * 历史形状参差（真机 {@code extra} 非空 95.8%，且 {@code {"v3_id":N}} 为主）而漏判。</p>
     * <p>（{@code who()} 的显示判据里<b>额外</b>要求 {@code extra.self=true}，那是抗伪造要求；
     * 两处的判据不同是刻意的：这一处只求"别多列一行"，那一处求"别把伪造的行显示成'我'"。）</p>
     */
    private static boolean markedSelfRow(JsonObject r) {
        if (r == null) return false;
        try {
            return SelfEcho.isSelfContent(J.s(r, "content", ""));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 窗口内消息<b>已有的标记</b>（事实行 {@code marks: [{msg_id,mark,note,by,by_agent,legacy}]}）。
     *
     * <p>键与技能「消息标记」一致：{@code mark:<safeName(session)>/<msg_id>}；查不到就跳过，不报错。</p>
     *
     * <p><b>为什么这一格留着</b>：它不是"快照机制"的一部分，而是<b>标记功能的读侧</b> ——
     * "窗口里这些消息带没带标记、标的是什么"只有在这里能随上下文一起给她；
     * 删掉它，她要看标记就只剩每轮再调一次 {@code mark} 工具。主人删的是"自动快照
     * （游标 + 双上界 + 落库进度）"，标记这条读侧照旧，<b>输出形状与删除前逐字同形</b>
     * （{@code ProbeConcurrency} 的 M1 钉的就是它）。</p>
     *
     * <p>成本：窗口里带 {@code msg_id} 的每一行一次 kv 点查（主键查，窗口 = 30 行量级）；
     * 私聊/控制台的行没有 {@code msg_id}，一次都不查（与删除前一致）。</p>
     */
    private static String marksOf(Store store, String session, List<JsonObject> rows) {
        if (store == null || rows == null || rows.isEmpty()) return "";
        String prefix = "mark:" + Str.safeName(session) + "/";
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (JsonObject r : rows) {
            long mid = J.l(r, "msg_id", 0L);
            if (mid <= 0) continue;
            String v;
            try {
                v = store.kv(prefix + mid, "");
            } catch (Throwable t) {
                continue;
            }
            if (Str.blank(v)) continue;
            JsonObject o = J.obj(v);
            if (o == null) continue;
            JsonObject f = new JsonObject();
            f.addProperty("msg_id", mid);
            f.addProperty("mark", J.s(o, "mark", ""));
            String note = J.s(o, "note", "");
            if (Str.has(note)) f.addProperty("note", note);
            f.addProperty("by", J.l(o, "by", 0L));
            f.addProperty("by_agent", J.s(o, "by_agent", "main"));
            if (J.b(o, "legacy", false)) f.addProperty("legacy", true);
            out.add(f);
        }
        return out.size() == 0 ? "" : ("marks: " + J.json(out));
    }

    /**
     * 本轮对话历史<b>将要</b>注入的那些行（{@link CtxBuild#appendHistory} 读的是同一支、同一个上限、
     * 同一个字符预算）—— 去重只跟"模型真的会看到的历史"比，不跟一整张表比。
     *
     * <p>这就是群回合比别的回合多出来的那一条查询（{@code recentDialog} 的 LIMIT 查询，走
     * {@code idx_dialog_session}）。私聊/控制台不做第 2 条去重，因此也不会多这一条。</p>
     */
    private static List<JsonObject> historyRows(Conf conf, Store store, String session) {
        try {
            int limit = conf.getInt("historyLimit", Conf.DEF_HISTORY_LIMIT);
            int maxChars = conf.getInt("historyMaxChars", Conf.DEF_HISTORY_MAX_CHARS);
            return CtxBuild.trimHistory(store.recentDialog(session, limit), maxChars);
        } catch (Throwable t) {
            return null;                              // 历史读不出来 = 不做这一层去重（少一层，不报错）
        }
    }

    // ---------------------------------------------------------------- 渲染

    private static String render(String session, String source, int size, List<JsonObject> rows,
                                 int repeat, int dup, int selfDup, long selfId, String marks) {
        SimpleDateFormat fmt = new SimpleDateFormat(TS_FMT);
        StringBuilder body = new StringBuilder();
        for (JsonObject r : rows) {
            if (body.length() > 0) body.append('\n');
            long ts = J.l(r, "ts", 0L);
            body.append('[').append(ts > 0 ? fmt.format(new Date(ts)) : "-").append("] ")
                .append(who(r, source, selfId)).append(": ").append(text(r)).append(quoteSuffix(r));
        }
        JsonObject meta = new JsonObject();
        meta.addProperty("session", session);
        meta.addProperty("source", source);
        meta.addProperty("size", size);                     // 配置的窗口大小
        meta.addProperty("rows", rows.size());              // 真的列了几行
        meta.addProperty("chars", body.length());           // 这一块的真实字符数（不是估的）
        if (repeat > 0) meta.addProperty("dropped_repeat", repeat);
        if (dup >= 0) meta.addProperty("dropped_dup", dup);
        // P0-1：窗口内"同一条话出现两次（一条带头部、一条不带头部）"压掉的条数。
        // 只在真的压掉过时才出现 —— 与 dropped_repeat / dropped_dup 同一口径
        // （没有这一格时，事实块的字节与加这个特性之前**完全一致**）。
        if (selfDup > 0) meta.addProperty("dropped_self_dup", selfDup);
        // 私聊/控制台：窗口与历史同源，去重故意不做 —— 写字符串而不是 0，别让人读成"没有重复"
        if (dup < 0) meta.addProperty("dup_history", "same-table");

        StringBuilder sb = new StringBuilder(META_PREFIX).append(J.json(meta));
        sb.append('\n').append(TITLE_LEAD).append(rows.size()).append(TITLE_TAIL);
        sb.append('\n').append(body);
        // 标记（若有）跟在记录后面：行列表保持连续，这一格是"这些消息已有的标记"的冻结事实
        if (Str.has(marks)) sb.append('\n').append(marks);
        return sb.toString();
    }

    /**
     * 一条记录里的"谁"。群看昵称（没有就退 {@code #user_id}）；对话表按角色标"我/对方"。
     *
     * <p><b>P0-1</b>：群里她自己的那一行标既有的常量 {@link #ME}（{@code "我"}）——
     * 靠"她自己知道她叫椰羊"是不够的（群里别人也都有名字，而且群名片可以被别人改成同一个名字），
     * {@code #<QQ>} 更不是"我"。判据<b>两个都要满足</b>：{@code user_id == conf.selfId()}
     * <b>且</b> {@code extra.self == true}。</p>
     *
     * <p><b>为什么必须同时看 {@code extra.self}（抗伪造要求）</b>：入站那一行写的是
     * {@code row.addProperty("user_id", ev.userId())}，而 {@code user_id} 来自帧本身 ——
     * 任何能连上反向 WS 端口的人都能推一条 {@code user_id = self_id} 的假帧。
     * 只看 {@code user_id} 就会把它<b>显示成"我"</b>（看起来更像她自己说的）。
     * 加上 {@code extra.self=true}（只有 {@code QqGateway.selfEcho} 会写）之后，
     * "我"这个显示<b>只给真标记行</b>。</p>
     *
     * <p><b>已知的历史行，行为要说清</b>：真库里<b>已有 1 行</b> {@code user_id = self_id}
     * （V3 迁移来的 {@code id=293335}、{@code msg_id} 为空、{@code nickname='椰羊'}），
     * 它<b>没有</b> {@code extra.self} ⇒ 它<b>继续显示原样</b>（昵称 {@code 椰羊}），不会被改写成"我"。
     * 这是刻意的：那一行的来源无法证明，宁可显示它自称的名字。</p>
     *
     * <p>{@code selfId <= 0}（配置取不到 / 探针没配）⇒ 判据自动失效，回到改之前<b>逐字节相同</b>
     * 的行为（fail-safe：取不到自身 QQ 就绝不猜哪一行是自己）。</p>
     */
    private static String who(JsonObject r, String source, long selfId) {
        if ("grouplog".equals(source)) {
            if (selfId > 0L && J.l(r, "user_id", 0L) == selfId && markedSelf(r)) return ME;
            String name = Str.oneLine(Str.nz(J.s(r, "nickname", "")));
            if (!name.isEmpty()) return Str.cut(name, 32);
            long uid = J.l(r, "user_id", 0L);
            return uid > 0 ? ("#" + uid) : "?";
        }
        return "assistant".equals(Str.trim(Str.nz(J.s(r, "role", "")))) ? ME : PEER;
    }

    /**
     * 这一行的 {@code extra.self} 是不是 {@code true}（P0-1 的抗伪造判据，见 {@link #who}）。
     *
     * <p>{@code extra} 是"历史与新写混居"的列（真机非空 95.8%，以 {@code {"v3_id":N}} 为主），
     * 而且既可能是<b>对象</b>也可能是<b>字符串</b> —— 解析写法与 {@link #quoteSuffix} 逐字同一套
     * （那里已经有现成的"两种形态都认"）。任何畸形值 ⇒ {@code false}，绝不抛、绝不让整块窗口消失。</p>
     */
    private static boolean markedSelf(JsonObject r) {
        try {
            com.google.gson.JsonElement e = J.get(r, "extra");
            if (e == null || e.isJsonNull()) return false;
            JsonObject x = e.isJsonObject() ? e.getAsJsonObject()
                    : (e.isJsonPrimitive() ? J.obj(e.getAsString()) : null);
            return x != null && J.b(x, "self", false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 一条记录的正文：<b>压单行后逐字照抄</b>（主人 2026-09-16 裁定：不加单条截断，她要看到完整记录）；
     * 空/NULL 如实标"（无文本）"。
     */
    private static String text(JsonObject r) {
        String s = Str.oneLine(Str.nz(J.s(r, "content", "")));
        return s.isEmpty() ? EMPTY_TEXT : s;
    }

    /** 去重比较用的正文（空正文不参与比较；这里<b>不</b>加"（无文本）"标记）。 */
    private static String oneLine(JsonObject r) {
        return Str.oneLine(Str.nz(J.s(r, "content", "")));
    }

    /**
     * <b>M4</b>：这一行引用了什么 —— {@code extra.quote} 里那段正文/标签（甲方要的"能看到引文"）。
     *
     * <p>形状：{@code （引用了：<text>）}；只有 {@code state=ok} 才加（取不到就不加，
     * <b>绝不</b>编"（引文取不到）"这类填充词 —— 与 {@link #EMPTY_TEXT} 那条"不编"同一口径）。
     * {@code text} 空但有 {@code render}（被引的是图/语音/卡片）时拼 {@code render} ——
     * 标签是"那是什么段型"，不是图里有什么。</p>
     *
     * <p><b>解析失败就不加</b>：{@code extra} 是历史与新写混居的列（真机非空 95.8%，
     * {@code {"v3_id":N}} 为主），任何畸形值都只是"这一行看不到引文"，绝不抛、绝不让整块窗口消失。</p>
     */
    private static String quoteSuffix(JsonObject r) {
        try {
            com.google.gson.JsonElement e = J.get(r, "extra");
            if (e == null || e.isJsonNull()) return "";
            JsonObject x = e.isJsonObject() ? e.getAsJsonObject()
                    : (e.isJsonPrimitive() ? J.obj(e.getAsString()) : null);
            JsonObject q = x == null ? null : J.sub(x, "quote");
            if (q == null) return "";
            if (!"ok".equals(Str.trim(J.s(q, "state", "")))) return "";
            String s = Str.oneLine(Str.nz(J.s(q, "text", "")));
            if (s.isEmpty()) s = Str.oneLine(Str.nz(J.s(q, "render", "")));
            return s.isEmpty() ? "" : ("（引用了：" + s + "）");
        } catch (Throwable t) {
            return "";
        }
    }

    /** {@code group:123} → 123（不是群键 / 认不出 → 0）。 */
    private static long groupId(String session) {
        try {
            return Long.parseLong(session.substring(6).trim());
        } catch (Throwable t) {
            return 0L;
        }
    }
}
