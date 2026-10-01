package sair.v4.qq;

/**
 * <b>「基板事实块写法」跳闸 —— 行级 × 形状 × 归一化（唯一实现，纯函数）</b>。
 *
 * <h3>它拦的是什么（真机事故 2026-09-22 09:06，群 543986616）</h3>
 * <p>那一轮模型把 2082 字「先答一句、接着自述推理」的整段写进了 {@code delta.content}，
 * 而这段独白里<b>逐行抄着基板自己注入的事实块写法</b>（{@code media: [{"type":"at",…}]}、
 * {@code msgs:1} …）。基板没有任何一条判据认得出"这段是内部文字"，于是它被当正常正文交给唯一出口
 * {@code Agent.java:220 → QqSink.say → Seg.plan}，切成 14 条连发：10 条投递成功、4 条被 QQ
 * 「本群每分钟只能发 10 条」拒收。</p>
 *
 * <h3>判据（2026-09-22 返工第 2 条收紧后的口径）</h3>
 * <p><b>不是"提过哪个词"，是"这一行按事实块的写法写出来了"</b>：</p>
 * <ol>
 *   <li><b>归一化</b>（{@link #norm}）：全角 ASCII ⇒ 半角（含全角冒号 {@code ：}、全角花括号）、
 *       大小写不分、空白无关。⇒ {@code CHAT_WINDOW: …} / {@code ｃｈａｔ＿ｗｉｎｄｏｗ：…} /
 *       {@code {"type": "at} 都与 {@code chat_window: …} / {@code {"type":"at} 同判
 *       （这是"事实行的写法"，不是"提过哪个词"）。</li>
 *   <li><b>形状</b>（{@link #shaped}）：一行里出现「事实键 <b>紧跟冒号</b>，且<b>值以结构化记号开头</b>」
 *       —— 键 ∈ {@link #KEYS}（基板事实块的键名），值以 {@code &#123;} / {@code [} / {@code "} /
 *       数字 / {@code true|false|null|connected|disconnected} 开头；
 *       或者出现 JSON 片段 {@code {"type":"}（媒体段的形状）。</li>
 *   <li><b>行级计数 ≥ {@link #MIN_HITS}（2）</b>：同一批里<b>至少两行</b>各自呈现事实行形状
 *       ⇒ 整批不发。一行里凑再多形状也只是一行（合法地"引用一句"不会被误伤）。</li>
 * </ol>
 *
 * <h3>为什么从"裸键名计数"改成"形状"（返工原因，如实记）</h3>
 * <p>第一版是"一行里出现 ≥2 个裸键名就整批不发"。独立复核构造了 11 组<b>合法</b>句子
 * （主人让她解释自己的字段、她转述主人的原话、引用 {@code [本群最近消息]} 标题、群友贴
 * {@code {"type":"at"}} 让她解释）⇒ <b>8 组被整批静默丢弃、用户什么都收不到</b>；
 * 而且裸子串判据<b>换写法就漏</b>（{@code CHAT_WINDOW} / 全角 / {@code {"type": "at} 都不命中）。
 * 收紧后：<b>11 组合法句 0 误伤、全库 assistant 全文 0 误伤、事故重放仍然 0 条出站</b>
 * （原始输出见 {@code tmp\v6-real\leakguard\rework\}）。</p>
 *
 * <h3>已知覆盖代价（如实）</h3>
 * <ul>
 *   <li>它是<b>写法</b>判据：纯推理散文（一个事实键都不引用）<b>会漏</b>；把事实行改写成
 *       {@code msgs 1}（去掉冒号）也会漏 —— 事故那一条靠 {@code media: […]} 与 {@code msgs:1}
 *       两行命中（阈值 2 刚好够），这一点在收工报告里逐行登记。</li>
 *   <li>只认 {@link #KEYS} 里的键；事实块新增键时这张表要跟着加（判据只有这一处）。</li>
 *   <li><b>降级打印路与本地控制台也已覆盖</b>（批 6-②，2026-09-23）：三条降级路 —— NapCat 未连接
 *       （{@code QqSink} / {@code TaskSink}）、caller 没有落点（{@code QqSink}）、定时/任务无目标
 *       （{@code TaskSink}）—— 与 {@code ConsoleSink}（本地交互面）现在都走
 *       {@code term.Sinks.degradedPrint}：那里<b>先判本条事实块闸（整条丢）、再走逐段闸</b>，
 *       与真发路（{@code wholeGate} / {@code Api.contentJudge}）<b>同源同序</b>
 *       （在那之前这三处只看逐段闸，是一条已记录的缝）。</li>
 * </ul>
 *
 * <h3>挂点（两处，判据同源）</h3>
 * <ol>
 *   <li>{@code term.Sinks.wholeGate}（<b>分段之前</b>）—— 一处覆盖 {@code QqSink} /
 *       {@code TaskSink} 两条<b>回复</b>路。命中 ⇒ <b>整批不发</b>，留一行 warn。</li>
 *   <li>{@code qq.Api.contentJudge} / {@code qq.Api.contentFact}（2026-09-22「戳一戳批」P3 补）——
 *       覆盖<b>工具</b>发送路（{@code send} 一类走 {@code h.napcat()} → {@code Api.call}，
 *       完全不过 {@code Sinks.stage}）。同一处判据、同一条"≥2 行"阈值、同一个理由串
 *       {@code rule=internal_fact}；<b>不</b>写第二套。</li>
 * </ol>
 * <p>命中一律<b>整条不发</b>（回复路 = 整批；工具路 = 整个动作），留一行结构事实、<b>不含正文</b>。
 * 刻意<b>不</b>做"删掉那几行再发"：半剥的残留比一条都不发更像乱码，而且会把残缺内容同时写进
 * 聊天记忆（与 {@code Sinks.gate} 对工具调用标记的同一条口径）。</p>
 * <p><b>降级不是降纪律</b>（批 6-②，2026-09-23）：两条降级打印路（NapCat 未连接 / caller 没有落点）、
 * 定时/任务的无目标降级路与 {@code ConsoleSink} 都收敛到 {@code term.Sinks.degradedPrint} 这一处，
 * <b>与真发路同源同序</b> —— 同样先过这道整条闸（命中即整条丢弃、留一行不含正文的 warn），
 * 再走逐段闸。以前它们只走逐段闸，是本判据的一条缺口，现已补上。</p>
 */
public final class InternalFacts {

    private InternalFacts() {}

    /** 跳闸阈值：同一批里<b>至少这么多行</b>各自呈现事实行形状。 */
    public static final int MIN_HITS = 2;

    /**
     * 事实块的键（形状判据的"键"那一半）—— 逐字取自 {@code ctx.CtxBuild.facts} 注入的那些键。
     *
     * <p>比较前一律先过 {@link #norm}（全角→半角、大小写不分、空白无关），所以这张表只用写小写半角。</p>
     */
    public static final String[] KEYS = {
            "time",
            "caller",
            "person",
            "media",
            "napcat",
            "models",
            "tools",
            "rounds",
            "tasks_running",
            "chat_window",
            "fact",
            "msgs",
            "seen_before",
            "first_seen",
            "last_seen",
    };

    /**
     * 派工单冻结的那十个字面量（<b>词汇表</b>）。
     *
     * <p>收紧之后它们<b>不再单独计数</b>（判据是 {@link #shaped} 的形状），只用于两件事：
     * ① {@code fact()} 的日志里给出"哪几个形状"；② 与历史口径（"裸键名计数"那一版）对照时当名字表。
     * 刻意保留这十个名字与 {@link #MIN_HITS} 的公开面：独立复核的夹具按这两个字段编译。</p>
     */
    public static final String[] LITERALS = {
            "chat_window",
            "user turn",
            "seen_before",
            "first_seen",
            "fact:",
            "caller 是",
            "msgs:",
            "last_seen",
            "{\"type\":\"at",
            "本群最近消息",
    };

    /** JSON 媒体段的形状（归一化后比较）：{@code {"type":"…}}。 */
    private static final String JSON_TYPE = "{\"type\":\"";

    // ================================================================ 归一化

    /**
     * 归一化一行：<b>全角 ASCII ⇒ 半角</b>（{@code ：} / {@code ｛} / 全角字母数字）、
     * <b>全角空格 ⇒ 半角空格</b>、<b>去掉所有空白</b>、<b>ASCII 大写 ⇒ 小写</b>。
     *
     * <p>为什么去空白：{@code {"type": "at}（冒号后多一个空格）与 {@code {"type":"at} 是同一个形状
     * —— 复核实测旧判据在这里漏判。去空白之后两者同判。</p>
     */
    public static String norm(String line) {
        if (line == null || line.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c >= '\uFF01' && c <= '\uFF5E') c = (char) (c - 0xFEE0);   // 全角 ASCII
            else if (c == '\u3000') c = ' ';                              // 全角空格
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\f' || c == '\u000B') continue;
            if (c >= 'A' && c <= 'Z') c = (char) (c + 32);
            sb.append(c);
        }
        return sb.toString();
    }

    // ================================================================ 形状

    /**
     * 这一行是不是"事实行形状"（判据本体）。
     *
     * @return 命中的形状名（给日志用，<b>只有键名与值首字符，不含正文</b>）；不是形状返回 {@code null}
     */
    public static String shaped(String line) {
        String s = norm(line);
        if (s.isEmpty()) return null;
        for (int k = 0; k < KEYS.length; k++) {
            String key = KEYS[k];
            int from = 0;
            while (true) {
                int i = s.indexOf(key, from);
                if (i < 0) break;
                from = i + 1;
                if (i > 0 && isWord(s.charAt(i - 1))) continue;              // 键必须是整词
                int j = i + key.length();
                if (j >= s.length() || s.charAt(j) != ':') continue;         // 键必须紧跟冒号
                if (!structured(s.substring(j + 1))) continue;               // 值必须以结构化记号开头
                return key + ":" + s.charAt(j + 1);
            }
        }
        if (s.indexOf(JSON_TYPE) >= 0) return JSON_TYPE;                     // 媒体段的 JSON 形状
        return null;
    }

    /** {@code [a-z0-9_]}（键的整词边界用）。 */
    private static boolean isWord(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_';
    }

    /**
     * 冒号之后那一串是不是以<b>结构化值</b>开头（{@code &#123;} / {@code [} / {@code "} / 数字 /
     * {@code true|false|null|connected|disconnected}）。
     *
     * <p>这是把"提过那个词"与"照事实行的写法写出来"分开的那一刀：{@code msgs: 那种行}（值以中文开头）
     * <b>不是</b>形状，{@code msgs:1} <b>是</b>。</p>
     */
    private static boolean structured(String v) {
        if (v == null || v.isEmpty()) return false;
        char c = v.charAt(0);
        if (c == '{' || c == '[' || c == '"') return true;
        if (c >= '0' && c <= '9') return true;
        return v.startsWith("true") || v.startsWith("false") || v.startsWith("null")
                || v.startsWith("connected") || v.startsWith("disconnected");
    }

    // ================================================================ 判据出口

    /**
     * 命中的判据事实（给日志用，<b>不含正文</b>）；形状行数没到 {@link #MIN_HITS} 时返回 {@code null}。
     *
     * <p>{@code line} 是<b>1 起</b>的行号（第一行形状行），{@code forms} 只列形状名（键名 + 值首字符），
     * {@code chars} 是整条正文长度 —— 与 {@code [qq] msg … chars=} 同口径。</p>
     */
    public static String fact(String text) {
        if (text == null || text.isEmpty()) return null;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int shapes = 0;
        int firstLine = -1;
        StringBuilder forms = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String form = shaped(lines[i]);
            if (form == null) continue;
            shapes++;
            if (firstLine < 0) firstLine = i + 1;
            if (forms.length() > 0) forms.append('+');
            forms.append(form);
            if (shapes >= MIN_HITS) {
                return "rule=internal_fact lines=" + shapes + " line=" + firstLine
                        + " forms=" + forms + " chars=" + text.length();
            }
        }
        return null;
    }

    /** 这一段正文该不该整批拦下（{@link #fact} 的布尔面）。 */
    public static boolean leaked(String text) {
        return fact(text) != null;
    }
}
