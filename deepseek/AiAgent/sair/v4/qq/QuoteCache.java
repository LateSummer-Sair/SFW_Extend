package sair.v4.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.QqGateway;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.kit.Th;
import sair.v4.store.Store;

/**
 * <b>M4：引用（{@code reply}）的按需取回</b>—— 把"被引那条消息"的正文/段型拿回来，
 * 落进发起引用那一行的 {@code extra}，并在这一轮的事实块里给她一行 {@code quote: {…}}。
 *
 * <h3>甲方裁定（逐字）与本文的落点</h3>
 * <blockquote>「引用消息可以记录下ID…它自己可以单独获取（优先从数据库拿，要是没有再去 Napcat 取，
 * 其中注意消息内容类型，多模态这种一定要注意），从而它自己不仅知道了这是引用也知道了消息内容。」</blockquote>
 * <ul>
 *   <li><b>记录里留 ID</b>：入站那一次就在 {@code grouplog.extra} 写
 *       {@code {"quote":{"id":"…","state":"pending","src":"none"}}}（{@code extra} 列早就存在，
 *       {@code EnvLibs.java} 的 DDL 里就有；<b>不改表结构、不加列</b>）；</li>
 *   <li><b>优先本地库、没有再去 NapCat</b>：{@link #fromDb} 先查 {@code grouplog}
 *       （最优形状 {@code where msg_id=? and group_id=? order by id desc limit 1}，实测 7.3 ms，
 *       比 {@code count(*)} 形状快 8.4 倍 —— 见 {@code tmp\verify\REPORT.md} §2），
 *       命中且有正文就用（{@code src:"db"}，<b>0 次网络</b>）；没命中/命中但没正文才入队走网。
 *       <b>NapCat 是主路不是兜底</b>：真机库实测 addressed 引用里 <b>87.1%</b> 本地库根本没有
 *       （人们 @ 她时引的多半是她自己上一句，而她自己的话永远不进 {@code grouplog}）；</li>
 *   <li><b>多模态</b>：被引消息取回来一律走<b>已冻结的那一套</b>
 *       （{@code QqGateway.mediaFacts(quoted)} + {@code qq\MediaRender.render(quoted, facts)}）
 *       ⇒ {@code [图片 …]}/{@code [语音 …]}/{@code [卡片 …]}，<b>不另造一套</b>；
 *       <b>空正文 ≠ 没内容</b>（{@code get_msg} 有 10.4% 是 {@code status:ok} + {@code message:[]}
 *       ⇒ {@code state:"empty"}）；<b>标签不是内容</b>（她看不见图里的东西 ⇒ 不许描述）。</li>
 * </ul>
 *
 * <h3>时机与预算（硬契约）</h3>
 * <ul>
 *   <li><b>只在"这条消息要起一轮的那一刻"查一次</b>（{@link #onTurn}，由
 *       {@code QqGateway.handleMessage} 在过了地址门之后调）⇒ 真机口径 ≈16.5 次/天
 *       （对照：每条入站都查 = 350 次/天；每个被引 id 都补查 = 实测 154.8 s/次）；</li>
 *   <li><b>回合线程 0 次网络、0 次全表扫</b>：本地库那一次查询发生在<b>入站事件线程</b>上，
 *       网络调用只在 {@link #worker} 这一个专用线程上；</li>
 *   <li><b>队列 64、超预算丢弃、失败不重试</b>（缓存是优化，不是正确性依赖）；
 *       {@code retcode 1200} 落<b>负缓存</b>（撤回是永久的）；瞬时失败只在内存里抑制
 *       {@value #TRANSIENT_SUPPRESS_MS} ms 且<b>不写 kv</b>；</li>
 *   <li><b>"同一 id 只解析一次"是构造性的</b>：单线程 worker（队列串行）+ {@link #inFlight}
 *       的 {@code putIfAbsent} + 闸内双检缓存；</li>
 *   <li><b>静默取数</b>：走 {@link Api#getMsgQuiet(long)}（{@code Link} 的静默重载）——
 *       {@code get_msg} 的响应前 200 字含被引正文，默认那条 {@code Link.call} 会把别人的话
 *       印到主人控制台（违反基板自己的日志口径），所以这条路<b>必须</b>用静默重载。</li>
 * </ul>
 *
 * <h3>三条"绝不编造"的硬规定</h3>
 * <ol>
 *   <li>{@code text} <b>只</b>来自被引消息自己的内容（DB 那一列 / {@code Ev.plainText()}），
 *       绝不写"她说了什么"这类转述；</li>
 *   <li>{@code state} 是唯一的状态承载：<b>只有 {@code ok} 才写 {@code text}</b>
 *       （非 ok 时那个键<b>不出现</b>，不是空串、更不是"（取不到）"这类填充词）；</li>
 *   <li>卫生沿用 M2：第三方文本一律过 {@link MediaRender#san}（压单行、方括号换圆括号、
 *       {@code ·}→{@code -}、截断 {@value #TEXT_MAX}），于是它伪造不出基板标签、也撑不破单行事实行。</li>
 * </ol>
 *
 * <h3>不改什么（非目标）</h3>
 * <p>{@code content} 一个字节都不改（引文<b>不进</b> {@code content}、不进 FTS、不进窗口去重口径）；
 * 不改表结构；不改 {@code Ev}；不改 {@code MediaRender}；不碰私聊的 {@code appendDialog} 签名
 * （私聊只有 {@code rowId<=0} 这一条路：事实行进当轮，不落 {@code extra}）。</p>
 */
public final class QuoteCache {

    // ---------------------------------------------------------------- 冻结的形状

    /** kv 键前缀（沿用冻结的 {@code mediax:} 命名空间）。 */
    public static final String KV_PREFIX = "mediax:quote/";

    /** 事实块里那一行的前缀（与 {@code media:} 行并列，见 {@code CtxBuild} 的 {@code ST_MEDIA}）。 */
    public static final String FACT_PREFIX = "quote: ";

    /** {@code extra} 里的那个键。 */
    public static final String EXTRA_KEY = "quote";

    /** 状态：拿到了内容（此时才有 {@code text}/{@code render}）。 */
    public static final String ST_OK = "ok";
    /** 状态：取回来了但<b>没有内容</b>（{@code message:[]} 或只有空文本段）—— 不编。 */
    public static final String ST_EMPTY = "empty";
    /** 状态：已撤回/不存在（{@code retcode 1200}）—— 落负缓存，永不重试。 */
    public static final String ST_GONE = "gone";
    /** 状态：这一轮还没拿到（未入队 / 在飞 / 超时 / 未连接 / 非 JSON）—— "看不见"是可见事实。 */
    public static final String ST_PENDING = "pending";
    /** 状态：永久性失败（非 1200 的 {@code retcode≠0}：参数错/权限不足这类）—— 落负缓存。 */
    public static final String ST_FAIL = "fail";

    /** 来源：本地库。 */
    public static final String SRC_DB = "db";
    /** 来源：NapCat {@code get_msg}。 */
    public static final String SRC_NAPCAT = "napcat";
    /** 来源：还没有任何来源（pending 的行就是这么写的）。 */
    public static final String SRC_NONE = "none";

    /** 正文进记录/事实行的字符上限（M2 卫生口径）。 */
    public static final int TEXT_MAX = 500;
    /** 取回队列长度（超了<b>丢弃不排队</b>）。 */
    public static final int QUEUE_MAX = 64;
    /** 限流：每 60 秒最多几次真调用。 */
    public static final int RATE_PER_MIN = 6;
    /** 瞬时失败的内存抑制窗口（不写 kv；防"一条坏 id 反复解析"）。 */
    public static final long TRANSIENT_SUPPRESS_MS = 60000L;

    /**
     * 冻结标签的词头（{@code MediaRender} 的唯一产法；认出来就进 {@code render} 而不是 {@code text}）。
     *
     * <p><b>{@code "[我发的"} 是 P0-1 加的（1 项）</b>：她自己的标记行
     * （{@code [我发的 msg_id=N] …}，产法见 {@link SelfEcho}）也是一行"冻结形状的标签"。
     * 不加这一项的话，引到<b>她自己那条</b>时（{@code fromDb} → {@code fill}）会被当 {@code text}
     * 处理、并过一次 {@code san} ⇒ 头部 {@code [我发的} 被中和成 {@code (我发的}，
     * 引文展示与窗口里的头部<b>形状不一致</b>。加了之后两边同形。</p>
     *
     * <p>它仍然只是<b>消费侧的识别表</b>：本数组只用于判断"这一串该进 {@code render} 还是
     * {@code text}"，<b>不</b>是第二套渲染器（本类一个字都不生成标签）。</p>
     */
    private static final String[] LABEL_HEADS = {
        "[提及", "[图片", "[语音", "[视频", "[文件", "[内联音频", "[表情",
        "[引用", "[折叠转发", "[转发节点", "[卡片", "[未知段", "[我发的"};

    // ---------------------------------------------------------------- 状态

    private final Store store;
    private final Conf conf;
    private final Api api;
    private final Out out;

    private final ArrayBlockingQueue<Task> queue = new ArrayBlockingQueue<Task>(QUEUE_MAX);
    /** 同一 id 同一时刻只有一个在飞（"只解析一次"的构造性闸）。 */
    private final ConcurrentHashMap<String, Long> inFlight = new ConcurrentHashMap<String, Long>();
    /** 瞬时失败过的 id（60 秒内不再试）—— <b>只在内存</b>，重启即清。 */
    private final ConcurrentHashMap<String, Long> cooldown = new ConcurrentHashMap<String, Long>();

    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong fetches = new AtomicLong();
    private final AtomicLong dbHits = new AtomicLong();
    private final AtomicLong enqueued = new AtomicLong();

    private final Object rateLock = new Object();
    private long windowStart;
    private final AtomicInteger windowCount = new AtomicInteger();

    private volatile boolean stopped;
    private volatile Thread worker;

    public QuoteCache(Store store, Conf conf, Api api, Out out) {
        this.store = store;
        this.conf = conf;
        this.api = api;
        this.out = out;
    }

    // ---------------------------------------------------------------- 入站：只留 link（微秒级，不查库、不走网）

    /**
     * 一条事件里第一个 {@code reply} 段的被引 {@code message_id}（没有引用段 ⇒ 空串）。
     * <p>只取第一个：QQ 的引用 UI 只允许引一条，多引用同条消息是长尾（8 天口径 1.08 段/id）。</p>
     */
    public static String replyId(Ev ev) {
        if (ev == null) return "";
        try {
            for (JsonObject seg : ev.segments()) {
                if (!"reply".equals(Str.lower(Str.trim(J.s(seg, "type", ""))))) continue;
                JsonObject d = J.sub(seg, "data");
                String id = d == null ? "" : Str.trim(J.s(d, "id", ""));
                if (!id.isEmpty()) return id;
                // data.id 缺失时退 raw 里的字段名（不同实现取值习惯不同）
                id = d == null ? "" : Str.trim(J.s(d, "message_id", ""));
                if (!id.isEmpty()) return id;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * 入站那一行 {@code extra} 的文本（<b>只有真有引用时才写这个键</b>）：
     * {@code {"quote":{"id":"…","state":"pending","src":"none"}}}。
     *
     * <p>这一步<b>不解析、不查库、不走网</b>：只把 ID 结构化留一份（甲方"记录下 ID"那条原话），
     * 于是"这是引用"进持久记录、而 {@code content} 里的 M2 冻结标签<b>一个字节都不改</b>。</p>
     *
     * @return 要写进 {@code extra} 的 JSON 文本；没有引用 id ⇒ {@code null}（调用方据此不加这个键）
     */
    public static String linkExtra(String replyId) {
        String id = Str.trim(replyId);
        if (id.isEmpty()) return null;
        return J.json(link(id));
    }

    /** {@code {"quote":{…}} 那一层壳（给"这一行已经有别的键"的合并写用）。 */
    static JsonObject link(String id) {
        return J.obj(EXTRA_KEY, base(id, ST_PENDING, SRC_NONE, 0L));
    }

    // ---------------------------------------------------------------- 起一轮那一刻：查一次

    /**
     * <b>这条消息要起一轮的那一刻</b>调一次（{@code QqGateway.handleMessage} 过了地址门之后）。
     *
     * <p>顺序就是甲方裁定：<b>先本地库</b>（{@code WHERE msg_id=? AND group_id=? ORDER BY id DESC LIMIT 1}，
     * 纯本地、无网络）⇒ 命中且有正文就用（{@code src:"db"}，0 次网络）；
     * 否则<b>入队走 NapCat</b>（worker 线程、fire-and-forget、这一轮不等）。</p>
     *
     * <p>沿途每一次"真的拿到了一条记录"都按主键写回那一行的 {@code extra}（读改写 merge）——
     * 这就是"引文进持久记录"的落点（{@code content} 不碰）。</p>
     *
     * @param replyId 被引 {@code message_id}（{@link #replyId(Ev)}；空串 ⇒ 直接返回 ""）
     * @param rowId   发起引用那一行的 {@code grouplog.id}（{@code Store.put} 的返回值）；
     *                {@code <=0} ⇒ 不写回（私聊没有 {@code grouplog} 行）
     * @param groupId 被引消息所在群（{@code >0} 才加进查询条件：真机只有 2 个 id 跨群重复，
     *                加它能把"同 id 落两个群"的误配挡掉；没有会话上下文时退化并接受风险）
     * @return 本轮事实行（{@code quote: {…}}）；没有引用 / 开关关掉 ⇒ 空串（拼进 {@code media} 事实串）
     */
    public String onTurn(String replyId, long rowId, long groupId) {
        String id = Str.trim(replyId);
        if (id.isEmpty()) return "";
        if (!enabled()) return "";
        JsonObject rec = null;
        try {
            rec = cached(id);                                  // ① kv（正/负缓存）
            if (rec == null) {
                rec = fromDb(id, groupId);                     // ② 本地库（纯本地）
                if (rec != null) {
                    dbHits.incrementAndGet();
                    save(id, rec);
                }
            }
            if (rec == null) {
                // ③ 没命中 ⇒ 入队走网（不等待）；行里保持 pending（入站那次已经写下）
                enqueue(id, rowId, groupId);
            } else {
                // 拿到真记录（不论来自 kv 还是本地库）⇒ 按主键写回**这一行**的 extra。
                // 这一步**不能**只在"本地库刚命中"那一支里做：同一个 id 被第二次引用时命中的是
                // kv，第二行同样必须拿到引文（否则它的 extra 永远停在 pending —— 探针实测到的缺陷）。
                writeBack(rowId, rec);
            }
        } catch (Throwable t) {
            warn("引用解析失败（这一轮按 pending 处理）：" + t);
        }
        if (rec == null) rec = base(id, ST_PENDING, SRC_NONE, 0L);
        return fact(rec);
    }

    /** 这一轮的事实行（{@code quote: {…}}）。 */
    public static String fact(JsonObject rec) {
        return rec == null ? "" : (FACT_PREFIX + J.json(rec));
    }

    /** 总开关（键 {@code mediaExpandQuote}，默认 true）。 */
    private boolean enabled() {
        try {
            return conf == null || conf.mediaExpandQuote();
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------- ① kv 缓存

    /** 缓存里的记录（过期 ⇒ 当未命中并顺手删掉那一格）。 */
    private JsonObject cached(String id) {
        String k = KV_PREFIX + id;
        String v;
        try {
            v = kv(k, "");
        } catch (Throwable t) {
            return null;
        }
        if (Str.blank(v)) return null;
        JsonObject o = J.obj(v);
        if (o == null) {
            drop(k);
            return null;
        }
        long ttl = ttlMs();
        long ts = J.l(o, "ts", 0L);
        if (ttl > 0L && ts > 0L && System.currentTimeMillis() - ts > ttl) {
            drop(k);                                           // 读路径自愈：过期即删
            return null;
        }
        return o;
    }

    /**
     * 有效 TTL（毫秒；{@code 0} = 永不过期）。
     *
     * <p>名义值是 {@code mediaCacheHours}（默认 168 = 7 天），再<b>夹到
     * {@code grouplogKeepDays × 24}</b>：{@code Store.maintain()} 完全不清 {@code kv}，
     * 不夹这一刀，引文会活得比群聊记录还久（保留期口径的漏洞）。</p>
     *
     * <p><b>对外可见</b>：这条 "min 规则" 是可断言的契约（{@code ProbeQuote} §10 钉它），
     * 所以它不是一个内部小工具。</p>
     */
    public long ttlMs() {
        try {
            int h = conf == null ? 168 : conf.mediaCacheHours();
            if (h <= 0) return 0L;
            int keep = conf == null ? 30 : conf.grouplogKeepDays();
            // kv 表 Store.maintain() 完全不清 ⇒ 不夹这一刀，引文会活得比群聊记录久（保留期口径漏洞）
            if (keep > 0) h = Math.min(h, keep * 24);
            return h * 3600000L;
        } catch (Throwable t) {
            return 168L * 3600000L;
        }
    }

    private void save(String id, JsonObject rec) {
        try {
            kvSet(KV_PREFIX + id, J.json(rec));
        } catch (Throwable t) {
            warn("引用缓存写失败（这一轮照样有事实行）：" + t);
        }
    }

    private void drop(String k) {
        try {
            kvDelete(k);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- ② 本地库

    /**
     * 本地库那一条（最优形状：{@code order by id desc limit 1} ⇒ 7.3 ms，命中第一条就停）。
     *
     * <p>命中判据：<b>那一行的 {@code content} 非空白</b>。命中但 {@code content} 空 ⇒ 本地这一列
     * 没告诉我们它是图还是语音（pre-M3 的媒体消息）⇒ 返回 {@code null}，让调用方转走 NapCat
     * （{@code tmp\reply\PLAN-v2.md} §3.1 的那张判定表）。</p>
     *
     * <p>{@code content} 形如冻结标签（{@code [图片 …]} 这类，M2/M3 的产法）⇒ 进 {@code render}，
     * 不进 {@code text}：<b>标签不是内容</b>。</p>
     */
    private JsonObject fromDb(String id, long groupId) {
        if (store == null || store.db() == null) return null;
        String sql = "SELECT id, ts, user_id, nickname, content FROM grouplog WHERE msg_id = ?"
                + (groupId > 0L ? " AND group_id = ?" : "")
                + " ORDER BY id DESC LIMIT 1";
        List<JsonObject> l;
        try {
            l = groupId > 0L
                    ? store.db().query(sql, id, Long.valueOf(groupId))
                    : store.db().query(sql, id);
        } catch (Throwable t) {
            warn("引用查本地库失败（转网络路）：" + t);
            return null;
        }
        if (l == null || l.isEmpty()) return null;
        JsonObject row = l.get(0);
        String content = Str.nz(J.s(row, "content", ""));
        if (Str.blank(content)) return null;                   // 命中但没正文 ⇒ 转网络
        JsonObject rec = base(id, ST_OK, SRC_DB, System.currentTimeMillis());
        long uid = J.l(row, "user_id", 0L);
        if (uid > 0L) rec.addProperty("uid", uid);
        String from = MediaRender.san(J.s(row, "nickname", ""), 80);
        if (!from.isEmpty()) rec.addProperty("from", from);
        long ts = J.l(row, "ts", 0L);
        if (ts > 0L) rec.addProperty("time", ts / 1000L);
        fill(rec, content, "");
        return rec;
    }

    // ---------------------------------------------------------------- ③ 网络（只在 worker 线程）

    /**
     * 真去取一次（<b>只在 worker 线程上被调</b>）。
     *
     * @return 拿到的记录；<b>瞬时失败</b>（超时/未连接/非 JSON）⇒ {@code null}（不写 kv、不写回，
     *         行里保持 {@code pending}），并在内存里抑制 60 秒
     */
    private JsonObject fetch(String id, long groupId) {
        Api a = api;
        if (a == null || !a.available()) return null;
        long mid = num(id);
        if (mid <= 0L) return null;
        fetches.incrementAndGet();
        JsonObject resp;
        try {
            resp = a.getMsgQuiet(mid);                          // ★ 静默重载（响应原文不进控制台）
        } catch (Throwable t) {
            markTransient(id);
            return null;
        }
        if (resp == null) {
            markTransient(id);
            return null;
        }
        if (!Api.ok(resp)) {
            long rc = J.l(resp, "retcode", 0L);
            String err = J.s(resp, "error", "");
            if (rc == 1200L || gone(err)) {
                // 已撤回/不存在：永久事实 ⇒ 负缓存（8 天实测 115 次，且失败的 id 与成功的 id 零重叠）
                JsonObject rec = base(id, ST_GONE, SRC_NAPCAT, System.currentTimeMillis());
                String why = MediaRender.san(err, 80);
                if (!why.isEmpty()) rec.addProperty("reason", why);
                return rec;
            }
            if (rc != 0L) {
                // 非 1200 的 retcode≠0 = 永久性拒绝（参数错/权限不足）⇒ 也落负缓存
                JsonObject rec = base(id, ST_FAIL, SRC_NAPCAT, System.currentTimeMillis());
                String why = MediaRender.san(err, 80);
                if (!why.isEmpty()) rec.addProperty("reason", why);
                return rec;
            }
            markTransient(id);                                  // 非 JSON / 空响应：瞬时
            return null;
        }
        JsonObject data = J.sub(resp, "data");
        if (data == null) {
            markTransient(id);
            return null;
        }
        Ev quoted = Ev.parse(data);
        JsonArray facts = QqGateway.mediaFacts(quoted);
        String render = MediaRender.render(quoted, facts);      // 冻结文法：有内容段 ⇒ 非空标签
        String text = Str.nz(quoted.plainText());
        JsonObject rec = base(id, ST_OK, SRC_NAPCAT, System.currentTimeMillis());
        long uid = quoted.userId();
        if (uid > 0L) rec.addProperty("uid", uid);
        String from = MediaRender.san(quoted.senderName(), 80);
        if (!from.isEmpty()) rec.addProperty("from", from);
        long t = quoted.time();
        if (t > 0L) rec.addProperty("time", t);
        fill(rec, text, render);
        return rec;
    }

    // ---------------------------------------------------------------- 记录形状

    /**
     * 记录骨架（键序固定，便于逐字断言）：{@code id, state, src, …}。
     * <p>{@code ts} = 这一份记录的产生时刻（毫秒；TTL 判定用），不是被引消息的时间
     * （那个在 {@code time}，秒）。</p>
     */
    private static JsonObject base(String id, String state, String src, long ts) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("state", state);
        o.addProperty("src", src);
        if (ts > 0L) o.addProperty("ts", ts);
        return o;
    }

    /**
     * 把"内容"填进记录：{@code text} / {@code render} / {@code chars}。
     *
     * <p><b>硬规定</b>：两个都没有 ⇒ 状态改判 {@code empty}（"取回来了但没内容"），
     * <b>不写 text、不写 render、不写什么"（无文本）"</b>—— 空正文 ≠ 没内容，所以先看 {@code render}。</p>
     *
     * @param content 硬文本（DB 那一列 / {@code Ev.plainText()}）
     * @param render  已冻结文法渲染出来的标签（NapCat 路给，DB 路为空串）
     */
    private static void fill(JsonObject rec, String content, String render) {
        String raw = Str.nz(content);
        String lab = Str.oneLine(Str.nz(render));
        boolean labelFromDb = raw.length() > 0 && lab.isEmpty() && isFrozenLabel(raw);
        if (labelFromDb) {
            rec.addProperty("render", Str.cut(Str.oneLine(raw), TEXT_MAX));
            return;
        }
        String clean = MediaRender.san(raw, TEXT_MAX);          // 压单行 + 括号中和 + ·→- + 截断
        if (!clean.isEmpty()) {
            rec.addProperty("text", clean);
            rec.addProperty("chars", Str.oneLine(raw).length());   // 截断前的长度（让她知道这只是前 500）
        }
        if (!lab.isEmpty()) rec.addProperty("render", Str.cut(lab, TEXT_MAX));
        if (Str.blank(clean) && Str.blank(lab)) {
            rec.addProperty("state", ST_EMPTY);                 // 空正文 ≠ 没内容，但这里两样都空
        }
    }

    /** 这个串像不像 {@code MediaRender} 产出的冻结标签（只认词头，绝不解析）。 */
    private static boolean isFrozenLabel(String s) {
        String t = Str.trim(s);
        for (int i = 0; i < LABEL_HEADS.length; i++) if (t.startsWith(LABEL_HEADS[i])) return true;
        return false;
    }

    /** 状态是不是"永久事实"（可以进 kv 负缓存）。 */
    static boolean cacheable(JsonObject rec) {
        String st = rec == null ? "" : J.s(rec, "state", "");
        return ST_OK.equals(st) || ST_EMPTY.equals(st) || ST_GONE.equals(st) || ST_FAIL.equals(st);
    }

    private static boolean gone(String err) {
        String e = Str.nz(err);
        return e.indexOf("撤回") >= 0 || e.indexOf("不存在") >= 0;
    }

    // ---------------------------------------------------------------- extra 写回（读改写 merge）

    /**
     * 把解析结果并进那一行的 {@code extra}（<b>读改写 merge</b>，绝不 {@code SET extra=?} 覆盖）。
     *
     * <p>为什么必须 merge：真机 {@code grouplog.extra} <b>非空 95.8%</b>
     * （{@code {"v3_id":N}} 145,778 行、带 {@code "mark"} 的 1,221 行）—— 整体替换会毁掉既有键。</p>
     *
     * <p>条件幂等：{@code AND (extra IS NULL OR extra <> ?)} ⇒ <b>同值重复写返回 0 行</b>
     * （"这一次真的写进去了"因此是可判定的，不靠"记得判断一下"）。</p>
     *
     * @return 受影响行数（{@code 1} = 真写进去了；{@code 0} = 内容没变/行不在；{@code -1} = 出错）
     */
    public int writeBack(long rowId, JsonObject rec) {
        if (rowId <= 0L || rec == null || store == null || store.db() == null) return 0;
        try {
            String merged = mergeExtra(currentExtra(rowId), rec);
            if (merged == null) return 0;
            return store.db().execRows(
                    "UPDATE grouplog SET extra = ? WHERE id = ? AND (extra IS NULL OR extra <> ?)",
                    merged, Long.valueOf(rowId), merged);
        } catch (Throwable t) {
            warn("引用写回 extra 失败（行 " + rowId + "）：" + t);
            return -1;
        }
    }

    /** 那一行现在的 {@code extra} 文本（没有/读不出来 ⇒ 空串）。 */
    private String currentExtra(long rowId) {
        try {
            JsonObject r = store.db().queryOne("SELECT extra FROM grouplog WHERE id = ?", Long.valueOf(rowId));
            if (r == null) return "";
            JsonElement e = J.get(r, "extra");
            if (e == null || e.isJsonNull()) return "";
            return e.isJsonPrimitive() ? Str.nz(e.getAsString()) : J.json(e);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 旧 JSON 文本 + 新 {@code quote} ⇒ 合并后的 JSON 文本（旧键一个不丢）。
     *
     * <p><b>对外可见</b>：它是"引文落 extra"这条链上唯一的纯函数（不碰库、不碰网、不看时钟），
     * 探针直接钉它（{@code ProbeQuote} §5）—— "不覆盖别人的键"这条契约必须能被独立复算。</p>
     */
    public static String mergeExtra(String oldJson, JsonObject rec) {
        JsonObject o = null;
        String s = Str.trim(Str.nz(oldJson));
        if (!s.isEmpty() && s.charAt(0) == '{') o = J.obj(s);
        if (o == null) o = new JsonObject();                    // 旧值不是 JSON 对象 ⇒ 只保留新的 quote
        o.add(EXTRA_KEY, rec.deepCopy());                       // 只覆盖我们自己的那个键
        return J.json(o);
    }

    // ---------------------------------------------------------------- worker（单线程、队列 64、超预算丢弃）

    private static final class Task {
        final String id;
        final long rowId;
        final long groupId;
        Task(String id, long rowId, long groupId) {
            this.id = id;
            this.rowId = rowId;
            this.groupId = groupId;
        }
    }

    /** 入队（不等待、不重试、超预算丢弃）。 */
    private void enqueue(String id, long rowId, long groupId) {
        if (api == null || !api.available()) return;              // 未连接：根本不入队（0 次网络）
        if (recentTransient(id)) return;                          // 60 秒内试过坏 id
        if (!budgetOk()) {
            long n = dropped.incrementAndGet();
            dim("引用取回超过限流（" + RATE_PER_MIN + "/分钟），这一条丢弃（缓存是优化，不是正确性依赖）；累计丢弃 " + n);
            return;
        }
        if (!queue.offer(new Task(id, rowId, groupId))) {
            long n = dropped.incrementAndGet();
            dim("引用取回队列已满（" + QUEUE_MAX + " 条在等），这一条丢弃（不排队）；累计丢弃 " + n);
            return;
        }
        enqueued.incrementAndGet();
        ensureWorker();
    }

    /** 限流窗口（60 秒滑动）。 */
    private boolean budgetOk() {
        synchronized (rateLock) {
            long now = System.currentTimeMillis();
            if (now - windowStart >= 60000L) {
                windowStart = now;
                windowCount.set(0);
            }
            if (windowCount.get() >= RATE_PER_MIN) return false;
            windowCount.incrementAndGet();
            return true;
        }
    }

    private void ensureWorker() {
        if (worker != null && worker.isAlive()) return;
        synchronized (this) {
            if (worker != null && worker.isAlive()) return;
            if (stopped) return;
            Thread t = Th.daemon("v4-quote", new Runnable() {
                @Override
                public void run() { loop(); }
            });
            worker = t;
            t.start();
        }
    }

    private void loop() {
        while (!stopped) {
            Task t;
            try {
                t = queue.poll(500L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (t == null) continue;
            run(t);
        }
    }

    /**
     * 一个任务：闸（{@code putIfAbsent}）⇒ 闸内双检缓存 ⇒ 取 ⇒ 落 kv ⇒ 写回那一行。
     * <p>worker 是<b>单线程</b>，所以"两条消息引同一个 id"在这里天然串行：第一条取完、第二条
     * 在双检那一步命中缓存 ⇒ <b>同一个 id 永远只打一次网</b>，而且第二条那一行也能拿到正文。</p>
     */
    void run(Task t) {
        if (t == null) return;
        if (inFlight.putIfAbsent(t.id, Long.valueOf(System.currentTimeMillis())) != null) return;
        try {
            JsonObject rec = cached(t.id);                      // 闸内双检（第一个刚写完缓存的情况）
            if (rec == null) rec = fetch(t.id, t.groupId);
            if (rec == null) return;                            // 瞬时失败：不写 kv、不写回（保持 pending）
            if (cacheable(rec)) save(t.id, rec);
            writeBack(t.rowId, rec);
            dim("引用取回 msg_id=" + t.id + " state=" + J.s(rec, "state", "") + " src=" + J.s(rec, "src", "")
                    + " chars=" + factChars(rec));
        } catch (Throwable e) {
            warn("引用取回异常（忽略）：" + e);
        } finally {
            inFlight.remove(t.id);                              // 任何异常路径都会摘闸
        }
    }

    private static int factChars(JsonObject rec) {
        return Str.nz(J.s(rec, "text", "")).length() + Str.nz(J.s(rec, "render", "")).length();
    }

    private void markTransient(String id) {
        cooldown.put(id, Long.valueOf(System.currentTimeMillis()));
        dim("引用取回失败 msg_id=" + id + "（瞬时；" + (TRANSIENT_SUPPRESS_MS / 1000L) + " 秒内不再试）");
    }

    private boolean recentTransient(String id) {
        Long t = cooldown.get(id);
        if (t == null) return false;
        if (System.currentTimeMillis() - t.longValue() > TRANSIENT_SUPPRESS_MS) {
            cooldown.remove(id);
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 收口 / 诊断

    /** 关停（{@code Boot.stop()} 调）：停循环 + 清空队列与两张内存表；<b>不做任何网络收尾</b>。 */
    public void shutdown() {
        stopped = true;
        queue.clear();
        inFlight.clear();
        cooldown.clear();
        Thread t = worker;
        if (t != null) t.interrupt();
        worker = null;
    }

    /** 真打网的次数（探针/排障："DB 命中 ⇒ 0 次网络"这条断言就看它）。 */
    public long fetchCount() { return fetches.get(); }

    /** 本地库命中的次数。 */
    public long dbHitCount() { return dbHits.get(); }

    /** 入队成功次数（含后来命中缓存而没打网的）。 */
    public long enqueueCount() { return enqueued.get(); }

    /** 丢弃次数（限流 / 队满 / 未连接不入队不算）。 */
    public long dropCount() { return dropped.get(); }

    /** 队列里还剩几条在等。 */
    public int pendingTasks() { return queue.size(); }

    /** 这个 id 现在在不在飞（构造性闸的可观测面）。 */
    public boolean inFlight(String id) { return inFlight.containsKey(Str.trim(id)); }

    /** 这个 id 现在在不在"60 秒抑制"表里。 */
    public boolean suppressed(String id) { return recentTransient(Str.trim(id)); }

    /** 清掉 60 秒抑制（只有探针用；线上没有调用点）。 */
    public void clearTransient() { cooldown.clear(); }

    /** 等队列跑空（只有探针/关停用；超时返回 false —— 绝不无限等）。 */
    public boolean awaitIdle(long timeoutMs) {
        long t0 = System.currentTimeMillis();
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            if (queue.isEmpty() && inFlight.isEmpty()) return true;
            Th.sleep(20L);
        }
        return queue.isEmpty() && inFlight.isEmpty();
    }

    /** 缓存里这个 id 的原始 JSON（没有 ⇒ 空串）。 */
    public String cachedJson(String id) {
        try {
            return Str.nz(kv(KV_PREFIX + Str.trim(id), ""));
        } catch (Throwable t) {
            return "";
        }
    }

    /** kv 键（探针要按它断言"负缓存真的在"）。 */
    public static String kvKey(String id) {
        return KV_PREFIX + Str.trim(id);
    }

    private static long num(String id) {
        try {
            return Long.parseLong(Str.trim(id));
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ---- 几个薄封装：让"探针能不能替掉库/网络"这件事不改调用面（异常一律不外抛） ----

    private String kv(String k, String def) {
        return store == null ? def : store.kv(k, def);
    }

    private void kvSet(String k, String v) {
        if (store != null) store.kvSet(k, v);
    }

    private void kvDelete(String k) {
        if (store != null) store.kvDelete(k, false);
    }

    private void dim(String line) {
        if (out != null) out.dim("[quote] " + line);
    }

    private void warn(String line) {
        if (out != null) out.warn("[quote] " + line);
    }
}
