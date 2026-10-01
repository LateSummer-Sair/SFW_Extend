package sair.v4.auth;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sair.v4.Conf;
import sair.v4.kit.J;

/**
 * 好感度子系统：<b>她对每个人的"关系值"</b>（主人裁 2026-09-17；加减规则 2026-09-26 重定）。
 *
 * <p>它<b>不判任何权限</b>（P9b-2 起旧档位体系与好感度门禁已整批删除，见 D13/D34）：
 * 数值只表达"她跟这个人处得怎么样"，谁可以用她多少工具由 ACL 账本说了算，两件事互不相干。
 * （"好感度档位 → 只读放权"那一层是<b>另一件事</b>，在 {@link FavorGate}，与本类的加减规则无关。）</p>
 *
 * <p>谁改它：① <b>她自己</b>——{@link #change} 按主人令 2026-09-26 的区间<b>夹死</b>，
 * 由她在回复里用 {@code <favor …/>} 表达；② <b>主人</b>——{@link #setValue} 直接定值，
 * 不受单次区间限制（控制台 {@code ai/perm set} 走同一条）。
 * 每一次改动都写一行流水（{@code favor_event}），数值要能说出"谁改的、改了多少、为什么"。</p>
 *
 * <h3>单次加减区间（甲方 2026-09-26 重定）</h3>
 * <ul>
 *   <li><b>总区间</b>（不写 {@code kind} 时的回落）：加 {@code [1,7]}、减 {@code [1,12]};
 *       <b>不设日上限</b>（一次来往能改多少只有这一道单次闸，没有按天累计的闸 —— 查证见类注释末）。</li>
 *   <li><b>按"来往类别"分档</b>（{@code kind} 属性，见 {@link #kind(String)}，共 13 类，
 *       可读清单见 {@link #kindTable()}）：友好 = 夸赞 {@code [5,7]} / 帮忙 {@code [3,5]} /
 *       怜悯 {@code [2,3]} / 投喂 {@code [2,4]} / 陪伴 {@code [1,2]} / 普通 {@code [1,1]}；
 *       非友好 = 无厘头 {@code [1,1]} / 羞辱 {@code [2,3]} / 撒谎 {@code [3,4]} / 吵架 {@code [3,5]} /
 *       越界 {@code [4,6]} / 发怒 {@code [5,12]} / 背叛 {@code [8,12]}。</li>
 *   <li><b>fail-closed 两条</b>：① {@code kind} <b>认不出</b>（例 {@code kind="乱写"}）⇒
 *       {@link Change#rejected}，整条不执行；② {@code kind} 认得出、方向却对不上
 *       （例 {@code kind="夸赞" sub="2"}）⇒ 同样整条不执行。原因都在 {@link Change#reject} 里。
 *       <b>绝不"回落成总区间"</b> —— 打错一个词就等于放宽到 {@code [-12,+7]}。</li>
 *   <li>越界一律<b>夹到边界</b>（{@link Change#clamped}），不拒绝 —— 她意图明确时夹到边界
 *       比回一句"范围不对"更贴合。</li>
 * </ul>
 *
 * <h3>没有日上限（2026-09-26 查证，逐条列在这里备核）</h3>
 * <p>全仓搜 {@code favor} 的写路径只有三处：{@code qq.FavorTags}（她自己的标记）、
 * {@code Builtins.permTool} 的 {@code set}/{@code reset}（主人直改）、{@code term.Cmd} 的
 * {@code favor set|reset}（主人）。三条最终都落到本类的 {@link #change} / {@link #setValue} /
 * {@link #set} / {@link #resetAll}，再落到 {@code store.setFavor} 与 {@code store.favorLog}。
 * 这两处一个字节的"按天累计 / 当日限额 / 冷却"都没有（{@code Store} 里只有
 * {@code favor(QQ)} / {@code setFavor} / {@code favorTop} / {@code clearFavor} / {@code favorLog} /
 * {@code favorEvents} 六个方法，全是单次读写）。{@code Tick} / {@code Cron} 的 {@code daily}
 * 只属于定时任务，与好感度无关。⇒ <b>结论：今天没有任何日累计闸，无需拆除。</b></p>
 */
public final class Favor implements sair.v4.skill.FavorView {

    // ==================== 加减区间（甲方 2026-09-26；常量 = 出厂默认，生效值走配置） ====================

    /**
     * 她单次<b>加</b>好感度的<b>总</b>区间（{@code kind} 没写时的回落）。
     * <p>2026-09-26 甲方重定：上限 {@code 3 → 7}；下限仍是 1。</p>
     */
    public static final double ADD_MIN = 1.0D;
    public static final double ADD_MAX = 7.0D;
    /**
     * 她单次<b>减</b>好感度的<b>总</b>区间（{@code kind} 没写时的回落）。
     * <p>2026-09-26 甲方重定：下限 {@code 5 → 1}（"无厘头无边界玩笑 −1"要能用）、
     * 上限 {@code 10 → 12}（"发怒 −12"）。</p>
     */
    public static final double SUB_MIN = 1.0D;
    public static final double SUB_MAX = 12.0D;

    // ---- 配置键（唯一真源；进 Conf.knownKeys()、不进 ensureDefaults()）----

    /** 总区间下界（加）。 */
    public static final String CFG_ADD_MIN = "favorAddMin";
    /** 总区间上界（加）。 */
    public static final String CFG_ADD_MAX = "favorAddMax";
    /** 总区间下界（减）。 */
    public static final String CFG_SUB_MIN = "favorSubMin";
    /** 总区间上界（减）。 */
    public static final String CFG_SUB_MAX = "favorSubMax";
    /** <b>按来往类别分档的区间表</b>（一条字符串，见 {@link #DEF_KIND_RANGES}）。 */
    public static final String CFG_KIND_RANGES = "favorKindRanges";
    /** 事实块里要不要给一行可机读的 kind 表（见 {@link #kindTable()}）。 */
    public static final String CFG_KIND_FACTS = "favorKindFacts";

    /**
     * {@link #CFG_KIND_RANGES} 的出厂值：{@code 名称=方向:下界-上界}，条目用 {@code ;} 或 {@code ,} 分隔；
     * 数值一律写<b>正数</b>（方向由 {@code add} / {@code sub} 表达）。
     * <p>GM 2026-09-26 扩容后的 13 条（数字写正数）：</p>
     * <ul>
     *   <li><b>友好（add）</b>：夸赞 {@code [5,7]}（被夸/被认可/被捧）、帮忙 {@code [3,5]}（替她办事、
     *       查东西、解决麻烦）、怜悯 {@code [2,3]}（可怜、示弱、求助到点上）、投喂 {@code [2,4]}
     *       （给她素材/表情包/图，喂她的库）、陪伴 {@code [1,2]}（长期在场、深夜陪聊这类"没什么事但在"）、
     *       普通 {@code [1,1]}（普通问答）；</li>
     *   <li><b>非友好（sub）</b>：无厘头 {@code [1,1]}（无边界玩笑）、羞辱 {@code [2,3]}、
     *       撒谎 {@code [3,4]}（骗她/糊弄她被识破）、吵架 {@code [3,5]}、越界 {@code [4,6]}
     *       （开她不能接受的车、骚扰、越界玩笑）、发怒 {@code [5,12]}、背叛 {@code [8,12]}
     *       （泄她隐私、当众出卖 —— 最重，与 −12 上限对齐）。</li>
     * </ul>
     * <p>改这张表 = 改一个键，不用碰代码。</p>
     */
    public static final String DEF_KIND_RANGES =
            "夸赞=add:5-7;帮忙=add:3-5;怜悯=add:2-3;投喂=add:2-4;陪伴=add:1-2;普通=add:1-1"
            + ";无厘头=sub:1-1;羞辱=sub:2-3;撒谎=sub:3-4;吵架=sub:3-5;越界=sub:4-6;发怒=sub:5-12;背叛=sub:8-12";

    /** 规范 kind 名（配置串里的键，也是事实行里印的那个）。 */
    public static final String KIND_PRAISE = "夸赞";
    public static final String KIND_HELP = "帮忙";
    public static final String KIND_PITY = "怜悯";
    public static final String KIND_FEED = "投喂";
    public static final String KIND_COMPANY = "陪伴";
    public static final String KIND_NORMAL = "普通";
    public static final String KIND_SILLY = "无厘头";
    public static final String KIND_INSULT = "羞辱";
    public static final String KIND_LIE = "撒谎";
    public static final String KIND_QUARREL = "吵架";
    public static final String KIND_OVERSTEP = "越界";
    public static final String KIND_RAGE = "发怒";
    public static final String KIND_BETRAY = "背叛";

    /**
     * 认得出的 <b>kind 别名</b>（全小写 → 规范名）。她写中文口语、写英文、大小写混写都认；
     * <b>认不出的整条不执行</b>（fail-closed —— 打错一个词就等于放宽，正是要防的"判据变松"）。
     */
    private static final Map<String, String> KIND_ALIAS = new LinkedHashMap<String, String>();
    static {
        alias(KIND_PRAISE, "夸赞", "表扬", "称赞", "赞美", "被夸", "praise", "compliment", "kua", "kuazan");
        alias(KIND_HELP, "帮忙", "帮助", "帮了我", "替我办事", "解围", "help", "favor", "bangmang");
        alias(KIND_PITY, "怜悯", "同情", "可怜", "心疼", "示弱", "pity", "mercy", "sympathy", "compassion");
        alias(KIND_FEED, "投喂", "喂料", "给素材", "给图", "给表情包", "feed", "material", "touwei");
        alias(KIND_COMPANY, "陪伴", "陪聊", "在场", "陪着我", "company", "companion", "peiban");
        alias(KIND_NORMAL, "普通", "平常", "正常", "问答", "normal", "plain", "ordinary", "common");
        alias(KIND_SILLY, "无厘头", "没边界", "没边界玩笑", "闹着玩", "silly", "nonsense", "random", "joke");
        alias(KIND_INSULT, "羞辱", "侮辱", "骂人", "贬低", "insult", "shame", "humiliate", "abuse");
        alias(KIND_LIE, "撒谎", "骗我", "骗她", "糊弄", "lie", "lying", "deceive", "cheat");
        alias(KIND_QUARREL, "吵架", "争吵", "争执", "抬杠", "对着干", "quarrel", "fight", "argue", "bicker");
        alias(KIND_OVERSTEP, "越界", "骚扰", "开黄腔", "越界玩笑", "overstep", "harass", "boundary");
        alias(KIND_RAGE, "发怒", "生气", "愤怒", "暴怒", "惹火", "anger", "rage", "fury", "angry");
        alias(KIND_BETRAY, "背叛", "出卖", "泄密", "泄我隐私", "betray", "betrayal", "treason");
    }

    private static void alias(String canonical, String... names) {
        for (int i = 0; i < names.length; i++) {
            String n = names[i] == null ? "" : names[i].trim().toLowerCase(Locale.ROOT);
            if (!n.isEmpty()) KIND_ALIAS.put(n, canonical);
        }
    }

    /** 一个"来往类别"的夹取规则（只读）。 */
    public static final class Kind {
        /** 规范名（配置串里的键）。 */
        public final String name;
        /** true = 这一类只许<b>减</b>（{@code sub}）；false = 只许<b>加</b>（{@code add}）。 */
        public final boolean sub;
        /** 夹取下界。 */
        public final double lo;
        /** 夹取上界。 */
        public final double hi;

        Kind(String name, boolean sub, double lo, double hi) {
            this.name = name;
            this.sub = sub;
            this.lo = lo;
            this.hi = hi;
        }

        /** 方向名（{@code add} / {@code sub}），与标记属性同名。 */
        public String dir() { return sub ? "sub" : "add"; }

        /** 可读区间：{@code [5,7]}。 */
        public String range() { return rangeText(lo, hi); }
    }

    /** 上一次解析用的原文 + 结果（判定在热路径上；配置很少变）。 */
    private static volatile String kindRaw;
    private static volatile Map<String, Kind> kindCache = Collections.emptyMap();

    /** 认得出的全部来往类别（规范名 → 规则；顺序 = 配置串里的顺序）。 */
    public static Map<String, Kind> kinds() {
        String raw = kindRangesRaw();
        if (raw.equals(kindRaw)) return kindCache;
        Map<String, Kind> m = new LinkedHashMap<String, Kind>();
        String[] parts = raw.split("[;,\\r\\n]+");
        for (int i = 0; i < parts.length; i++) {
            String one = parts[i] == null ? "" : parts[i].trim();
            if (one.isEmpty()) continue;
            int eq = one.indexOf('=');
            if (eq <= 0) continue;
            String name = one.substring(0, eq).trim();
            String body = one.substring(eq + 1).trim();
            int colon = body.indexOf(':');
            if (name.isEmpty() || colon < 0) continue;
            boolean sub = "sub".equalsIgnoreCase(body.substring(0, colon).trim());
            String rng = body.substring(colon + 1).trim();
            int dash = rng.indexOf('-');
            if (dash < 0) continue;
            double lo = num(rng.substring(0, dash), -1.0D);
            double hi = num(rng.substring(dash + 1), -1.0D);
            if (lo < 0.0D || hi < 0.0D) continue;
            if (hi < lo) {
                double t = lo;
                lo = hi;
                hi = t;
            }
            m.put(name, new Kind(name, sub, lo, hi));
        }
        kindRaw = raw;
        kindCache = Collections.unmodifiableMap(m);
        return kindCache;
    }

    /** 来往类别的规范名（别名 / 大小写都收）；认不出返回空串。 */
    public static String normKind(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return "";
        Kind k = kinds().get(s);
        if (k != null) return k.name;
        String a = KIND_ALIAS.get(s.toLowerCase(Locale.ROOT));
        return a == null ? "" : a;
    }

    /** 来往类别的规则；认不出返回 {@code null}（<b>调用方按 fail-closed 处理</b>）。 */
    public static Kind kind(String raw) {
        String n = normKind(raw);
        return n.isEmpty() ? null : kinds().get(n);
    }

    /**
     * <b>可机读的 kind 清单 + 区间</b>（一行，给她/给日志/给事实块用；生效值，不是出厂值）。
     *
     * <p>形状：{@code 夸赞=+5..7,帮忙=+3..5,…,无厘头=-1,羞辱=-2..3,…} —— 带正负号，
     * 一眼看出方向；单点区间（下界=上界）只写一个数。顺序 = 配置串里的顺序。</p>
     * <p><b>唯一真源</b>：{@code [favor] kind表} 日志行、事实块的 {@code favorKinds} 行都调它，
     * 不许各自拼一份。</p>
     */
    public static String kindTable() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Kind> e : kinds().entrySet()) {
            Kind k = e.getValue();
            if (sb.length() > 0) sb.append(',');
            sb.append(k.name).append('=').append(k.sub ? "-" : "+");
            if (k.lo == k.hi) {
                sb.append(fmt(k.lo));
            } else {
                sb.append(fmt(k.lo)).append("..").append(fmt(k.hi));
            }
        }
        return sb.toString();
    }

    /** 事实块里要不要给那一行（{@code favorKindFacts}，出厂 true）。 */
    public static boolean kindFacts() {
        Conf c = CONF;
        return c == null ? DEF_KIND_FACTS : c.favorKindFacts();
    }

    // ==================== 配置面 ====================

    /** 当前这一份配置（{@link Auth} 构造器注入；没注入时按出厂默认跑）。 */
    private static volatile Conf CONF;

    /** 注入配置面（{@link Auth} 构造器调）。 */
    public static void install(Conf conf) { CONF = conf; }

    /** 当前配置面（没注入 = {@code null}）。 */
    public static Conf conf() { return CONF; }

    /** 单次加的下界（生效值）。 */
    public static double addMin() { return cfg(CFG_ADD_MIN, ADD_MIN); }

    /** 单次加的上界（生效值）。 */
    public static double addMax() { return cfg(CFG_ADD_MAX, ADD_MAX); }

    /** 单次减的下界（生效值）。 */
    public static double subMin() { return cfg(CFG_SUB_MIN, SUB_MIN); }

    /** 单次减的上界（生效值）。 */
    public static double subMax() { return cfg(CFG_SUB_MAX, SUB_MAX); }

    /** 来往类别区间表的原文（生效值）。 */
    public static String kindRangesRaw() {
        Conf c = CONF;
        String s = c == null ? DEF_KIND_RANGES : c.favorKindRanges();
        return s == null ? "" : s;
    }

    /** 出厂 {@link #CFG_KIND_FACTS} 的值。 */
    public static final boolean DEF_KIND_FACTS = true;

    private static double cfg(String key, double def) {
        Conf c = CONF;
        return c == null ? def : c.getDouble(key, def);
    }

    /**
     * 整数不带小数点，小数留一位（数值进事实行/日志时的统一印法）。
     * <p><b>唯一真源</b>：{@link #rangeText(double, double)} / {@link #kindTable()} /
     * 事实块的 {@code favorKinds} 行都走它，免得三处各印一种数。</p>
     */
    public static String fmt(double v) {
        long l = (long) v;
        return v == (double) l ? String.valueOf(l) : String.format(Locale.ROOT, "%.1f", v);
    }

    /**
     * 区间的可读写法：{@code [5,7]}（单点也写成 {@code [1,1]}，与配置串里的写法一致，便于逐字对照）。
     * <p><b>唯一真源</b>：{@code Kind.range()} 与 {@code qq.FavorTags} 的事实行都调它，
     * 免得两个地方各印一种区间形状。</p>
     */
    public static String rangeText(double lo, double hi) {
        return "[" + fmt(lo) + "," + fmt(hi) + "]";
    }

    /** 只认非负十进制（含小数）；认不出返回 {@code def}。 */
    private static double num(String s, double def) {
        String v = s == null ? "" : s.trim();
        if (v.isEmpty()) return def;
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            if ((ch < '0' || ch > '9') && ch != '.') return def;
        }
        try {
            return Double.parseDouble(v);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * 好感度<b>上限</b>（主人令 2026-09-26：上限 1000、下限 0）。
     *
     * <p>此前"上不封顶"（{@code term.Cmd} 的 {@code favor set} 提示里那句原话已随之改掉）。
     * 夹取而不是拒绝，与单次加减区间同一口径：<b>意图明确时夹到边界比回一句"越界"更贴合她的意思</b>；
     * 三条写入口（{@link #set} / {@link #change} / {@link #setValue}）与 {@link #add}
     * 一律走 {@link #clamp}，谁都不可能把它顶到 1000 以上。</p>
     */
    public static final double CAP = 1000.0D;

    /** 把一个数值夹进 {@code [0, CAP]}（唯一的夹取口；三条写路径与 {@link #add} 都走它）。 */
    public static double clamp(double v) {
        if (v < 0.0D) return 0.0D;
        return v > CAP ? CAP : v;
    }

    private final FavorStore store;
    private final Map<Long, Double> cache = new ConcurrentHashMap<Long, Double>();
    /** 本次运行里已经建过账/写过账的 QQ（只省一次点查，重启后重建，不影响正确性）。 */
    private final Map<Long, Boolean> known = new ConcurrentHashMap<Long, Boolean>();

    public Favor(FavorStore store) {
        this.store = store;
    }

    /** 一次改动的回执（日志与工具结果用）。 */
    public static final class Change {
        public String op = "";
        public long qq;
        public double before;
        public double after;
        public double applied;
        public boolean clamped;
        public String why = "";
        /** 生效的来往类别<b>规范名</b>（没写 {@code kind} = 空串）。 */
        public String kind = "";
        /** 她写的 {@code kind} 原文（只在她写的认不出时用来出话；认得出时等于 {@link #kind}）。 */
        public String kindRaw = "";
        /** 这次<b>实际用的</b>夹取下界（没写 kind 时 = 总区间下界；被拒时为 0）。 */
        public double lo;
        /** 这次实际用的夹取上界。 */
        public double hi;
        /** true = 整条<b>没执行</b>（kind 不认识 / 方向不符 / 同时给了两个动作），原因见 {@link #reject}。 */
        public boolean rejected;
        /** 不执行的原因（事实行原文）。 */
        public String reject = "";
    }

    /** 取好感度（缓存）。 */
    public double of(long qq) {
        if (qq <= 0) return 0;
        Double v = cache.get(qq);
        if (v != null) return v;
        double d = 0;
        try {
            d = store == null ? 0 : store.favor(qq);
        } catch (Exception ignored) {
        }
        cache.put(qq, d);
        return d;
    }

    public void set(long qq, double value, String note) {
        if (qq <= 0) return;
        double v = clamp(value);
        double before = of(qq);
        cache.put(qq, v);
        known.put(qq, Boolean.TRUE);
        try {
            if (store != null) {
                store.setFavor(qq, v, levelName(v), note == null ? "" : note);
                store.log(qq, v - before, v, "set", "exec", note == null ? "" : note);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 按人建账：这个人第一次跟她打交道就建一行（值 0），已有则一个字段都不动。
     * <p>只写一次（本进程内同一个人不再点查），所以群再吵也不会变成写放大。</p>
     */
    public void ensure(long qq) {
        if (qq <= 0L) return;
        if (known.putIfAbsent(qq, Boolean.TRUE) != null) return;
        try {
            if (store != null) store.ensure(qq);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 她自己的加减（{@code op} = {@code add} / {@code sub}）：幅度由基板<b>夹死</b>在甲方 2026-09-26
     * 重定的区间里，值夹在 {@code [0, CAP]}；写库 + 记一行流水。
     *
     * <p>旧签名（{@code kind} 缺省 = 空）：与"只写 {@code add} / {@code sub} 的老标记"完全等价 ——
     * 区间 = 总区间（加 {@code [1,7]}、减 {@code [1,12]}）。</p>
     */
    public Change change(long qq, double delta, String op, String why, String by) {
        return change(qq, delta, op, null, why, by);
    }

    /**
     * 她自己的加减，<b>带"来往类别"分档</b>（甲方 2026-09-26）：{@code kind} 认得出就按它那一档夹，
     * 认不出就回落总区间（并在回执里说明），<b>方向对不上就整条不执行</b>（fail-closed）。
     *
     * <p>夹取而不是拒绝（区间越界时）：她说"减 20"，意图明确，夹到 12 比回一句"范围不对"更贴合
     * 她的意思；回执里带 {@code clamped} 标记，日志与工具结果都看得见。</p>
     *
     * <p><b>为什么方向不符要拒而不是夹</b>：{@code kind="夸赞" sub="2"} 是"她自己把两件事说岔了"
     * ——夹一夹会变成"夸他一句却把他的分扣了"，是最坏的一种半懂。宁可整条不动、留一行说明让她
     * 下一轮改口，也不猜她的意思。</p>
     *
     * @param kind 来往类别（中文名 / 别名 / 英文都收，见 {@link #kind(String)}）；{@code null}/空 = 不分类
     */
    public Change change(long qq, double delta, String op, String kind, String why, String by) {
        Change r = new Change();
        boolean sub = "sub".equals(op);
        r.op = sub ? "sub" : "add";
        r.qq = qq;
        r.why = why == null ? "" : why;
        r.kindRaw = kind == null ? "" : kind.trim();
        // 总区间（回落用；也是回执里 lo/hi 的起点）
        r.lo = sub ? subMin() : addMin();
        r.hi = sub ? subMax() : addMax();
        if (!r.kindRaw.isEmpty()) {
            Kind k = kind(r.kindRaw);
            if (k == null) {
                // ★ fail-closed：认不出的类别整条不执行。绝不"回落成总区间"——
                //   打错一个词就等于放宽到 [-12,+7]，那正是"判据变松"这一类缺陷。
                r.rejected = true;
                r.reject = "kind「" + r.kindRaw + "」不认识 ⇒ 整条不执行（fail-closed；"
                        + "认得的类别与区间见 [favor] kind表 或事实块 favorKinds）";
                return r;
            }
            if (k.sub != sub) {
                r.rejected = true;
                r.reject = "kind=" + k.name + " 只认 " + k.dir() + "（" + (k.sub ? "减" : "加")
                        + "），与这次写的 " + r.op + " 不符 ⇒ 整条不执行（fail-closed）";
                return r;
            }
            r.kind = k.name;
            r.lo = k.lo;
            r.hi = k.hi;
        }
        if (qq <= 0L || !(delta > 0.0D)) return r;
        double d = delta;
        double lo = r.lo;
        double hi = r.hi;
        if (d < lo) {
            d = lo;
            r.clamped = true;
        }
        if (d > hi) {
            d = hi;
            r.clamped = true;
        }
        r.before = of(qq);
        r.after = clamp(r.before + (sub ? -d : d));
        r.applied = r.after - r.before;
        if (r.before + (sub ? -d : d) > CAP) r.clamped = true;
        cache.put(qq, r.after);
        known.put(qq, Boolean.TRUE);
        try {
            if (store != null) {
                store.setFavor(qq, r.after, levelName(r.after), r.why);
                store.log(qq, r.applied, r.after, r.op, by, r.why);
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    /**
     * 直接定值 —— <b>只有主人</b>能走这条（身份判定在执行口，不在这里）：不受单次区间限制。
     * <p>主人裁的"主人拥有让它修改任何人好感度直接数值的能力"就落在这一支。</p>
     */
    public Change setValue(long qq, double value, String why, String by) {
        Change r = new Change();
        r.op = "set";
        r.qq = qq;
        r.why = why == null ? "" : why;
        if (qq <= 0L || value < 0.0D) return r;
        r.before = of(qq);
        r.after = clamp(value);
        r.applied = r.after - r.before;
        if (value > CAP) r.clamped = true;               // 主人写了 1200 ⇒ 夹到 1000 并留痕
        // 下界 0 仍然走"拒绝"这条老口径（值 < 0 上面已经返回了），上界是新的：夹取。
        cache.put(qq, r.after);
        known.put(qq, Boolean.TRUE);
        try {
            if (store != null) {
                store.setFavor(qq, r.after, levelName(r.after), r.why);
                store.log(qq, r.applied, r.after, "set", by, r.why);
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    /** 某人的好感度流水（新 → 旧）。 */
    public List<JsonObject> events(long qq, int limit) {
        try {
            return store == null ? new ArrayList<JsonObject>() : store.events(qq, limit);
        } catch (Exception e) {
            return new ArrayList<JsonObject>();
        }
    }

    public void add(long qq, double delta, String note) {
        if (qq <= 0 || delta == 0) return;
        set(qq, of(qq) + delta, note);
    }

    public void invalidate(long qq) { cache.remove(qq); }

    public int resetAll() {
        cache.clear();
        try {
            return store == null ? 0 : store.clear();
        } catch (Exception e) {
            return 0;
        }
    }

    public List<JsonObject> top(int limit) {
        try {
            return store == null ? new ArrayList<JsonObject>() : store.top(limit);
        } catch (Exception e) {
            return new ArrayList<JsonObject>();
        }
    }

    /** 数值 → 关系名（只做展示，判定一律用数值）。 */
    public String levelName(double v) {
        if (v >= CAP) return "挚友";
        if (v >= FavorGate.TIER_RESPECT) return "敬重";
        if (v >= FavorGate.TIER_FILES) return "信重";
        if (v >= FavorGate.TIER_INVITE) return "朋友";
        if (v >= FavorGate.TIER_FRIEND) return "眼熟";
        if (v > 0) return "陌生";
        return "初识";
    }

    /** 门禁快照（给模型/控制台看）。 */
    public JsonObject snapshot(long qq) {
        double v = of(qq);
        return J.obj("qq", qq, "favor", v, "level", levelName(v));
    }
}
