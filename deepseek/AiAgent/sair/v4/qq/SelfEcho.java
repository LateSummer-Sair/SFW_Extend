package sair.v4.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sair.v4.kit.Str;

/**
 * <b>她自己的出站消息的标记行</b>（P0-1「让她看得见自己」的唯一产法）。
 *
 * <h3>它解决什么</h3>
 * <p>今天她自己发出去的消息/图<b>在她自己的记账里完全没有一行</b>：真机实测 {@code sent} 台账
 * （F5a 的出站底账）里 52 条 {@code message_id}，<b>0 条</b>能在 {@code grouplog.msg_id} 里找到。
 * 于是"本会话最近 N 条聊天记录"（{@link sair.v4.ctx.ChatWindow}）与对话历史
 * （{@code ctx.CtxBuild.appendHistory}）里，她看不见自己刚说过什么、发过哪张图。
 * 本类只做一件事：把一次真实发送翻成<b>一行确定性的标记行</b>
 * （{@code [我发的 msg_id=918642495] 哎，我在的 [图片 cat.png 20KB]}），交给
 * {@code QqGateway.selfEcho} 落库。</p>
 *
 * <h3>形状（逐字文法，一个字都不许改）</h3>
 * <pre>
 * content := "[我发的 msg_id=" &lt;真实十进制 id&gt; "]" [ " " &lt;san(正文,1000)&gt; ] [ " " &lt;MediaRender 段标签&gt; ]
 * </pre>
 * <p>两段都可空 ⇒ {@code content} <b>永不空</b>（最短 = {@code [我发的 msg_id=123]}）。
 * 前缀满足技能线已发布的判据 {@code content.startsWith("[我发的 ")}。</p>
 *
 * <h3>唯一的产法（与 {@link MediaRender} / {@link NoticeRender} 同一纪律）</h3>
 * <ul>
 *   <li><b>头部只在本类拼</b>（与 {@code NoticeRender} 的行标签同一先例：它不是"段标签"，
 *       是"行标签"）；</li>
 *   <li><b>段标签只由 {@link MediaRender#render} 产出</b> —— 本类<b>不</b>复制任何一条标签规则，
 *       也不新开第二套渲染器；</li>
 *   <li><b>值卫生只借 {@link MediaRender#san}</b>（同包直接调，<b>不</b>新开 public 入口、
 *       不复制一份规则）—— 所以本类<b>必须</b>在 {@code sair.v4.qq} 包内，这是选址的硬约束；
 *       {@code san} 会把 {@code [}→{@code (}、{@code ]}→{@code )}、{@code ·}→{@code -}、压单行、
 *       截断 ⇒ <b>外部文本（乃至她自己的正文）伪造不出这个形状</b>，也伪造不出第二个头部。
 *       （<b>识别侧</b>另有 {@link #HEAD_LEAD_PAREN}：{@code san} 中和出来的圆括号变体也认 —— 见那里的
 *       说明；<b>产出侧</b>仍旧只有本类这一种方括号形态，一个字没放宽。）</li>
 * </ul>
 *
 * <h3>头部不含 {@code ·}（硬规定）</h3>
 * <p>{@code ·} 是"机器写的"专用标记（{@link MediaRender#MACHINE_FMT} = {@code [来源·模型] }）。
 * 本类的头部 {@value #HEAD_LEAD}…{@value #HEAD_TAIL} 里<b>永远不出现 {@code ·}</b>，
 * 所以"标记行"与"机器产出的文本"两种形状构造性隔离。</p>
 *
 * <h3>★ 合成 {@link Ev}：全树第 4 个 {@code Ev.parse} 调用点</h3>
 * <p>{@link MediaRender} <b>刻意没有</b>"从 {@code JsonObject} 渲染"的入口
 * （它自己的 javadoc 逐字写着"刻意不提供一参数重载：没有第二个入口，就没有第二种渲染"）。
 * 而 {@code Api.SentTap} 手上只有<b>发送参数</b>（{@code {"group_id":…,"message":[…]}}），
 * 不是一条 {@code OneBot} 事件。所以要渲染段标签，<b>只能</b>把发送参数里的 {@code message}
 * 合成一条 {@link Ev}：{@link #evOf(JsonObject)}。这是全树第 4 个 {@code Ev.parse} 调用点 ——
 * 现有 3 处是 {@code QqGateway.java} 的入站分支、{@link Ev} 自身、{@code QuoteCache} 的
 * {@code get_msg} 响应。写在这里是<b>故意的</b>：这 4 处必须一眼可数，好让"同一条消息只有一种渲染"
 * 这条不变量随时可复核。</p>
 * <p><b>合成失败/畸形绝不抛</b>：{@link #evOf} / {@link #textOf} / {@link #labels} / {@link #content}
 * 全都吞掉一切异常 ⇒ 降级成"只有头部（+ 抽得出的正文）"，
 * <b>绝不</b>因为记账把一次真实发送拖垮、也绝不产出一个空串或 {@code null}。</p>
 *
 * <h3>契约（逐条可断言）</h3>
 * <ol>
 *   <li>纯函数：无网络、无模型、无时钟、无随机、无副作用、<b>不写库</b>；</li>
 *   <li>{@link #content} 永不抛、永不返回空串、永不返回 {@code null}；</li>
 *   <li>不编造：只打真实 id 与段里真有的字段；</li>
 *   <li>单行：输出里绝不含换行/Tab（正文先过 {@code san}）；</li>
 *   <li>{@link #withoutHead} 对"不带头部的串"是<b>恒等</b>的（幂等、可反复调用）。</li>
 * </ol>
 *
 * <h3>{@code extra.sent_id} 的寿命（如实写在这里，免得日后被当永久外键）</h3>
 * <p>{@code extra.sent_id} <b>可反查</b>（→ {@code sent.message_id}），但 {@code sent} 台账只保留
 * <b>最近 2000 行</b>（{@code Store.sentPrune(Store.DEF_SENT_KEEP)} 由心跳维护调用，按行数不按时间），
 * 所以<b>越界即失效</b>。标记行本身与群聊记录<b>同寿</b>（都在 {@code grouplog}/{@code dialog} 里、
 * 同一把保留期尺子），而 {@code sent_id} 的寿命<b>短于</b>它 ⇒ 解析不到就是"台账已裁"，
 * 不是"数据坏了"。</p>
 */
public final class SelfEcho {

    private SelfEcho() {}

    /** 头部前缀（逐字）。 */
    public static final String HEAD_LEAD = "[我发的 msg_id=";
    /** 头部后缀（逐字）。 */
    public static final String HEAD_TAIL = "]";
    /**
     * <b>圆括号变体</b>的前缀 —— <b>只用于"认出来"</b>，{@link #head}/{@link #content} <b>绝不用它产出</b>。
     *
     * <p>它为什么存在：{@link MediaRender#san} 会把正文里的 {@code [} 中和成 {@code (}、{@code ]} 中和成
     * {@code )}（那是"外部文本伪造不出标记形状"的硬卫生）。于是模型自己写的那截头一旦经过记账，
     * 落库就成了 {@code (我发的 msg_id=N)}：真机 {@code dialog id=2370} 就是
     * {@code [我发的 msg_id=339080329] (我发的 msg_id=1295972856) 诶，不客气喔。…}
     * —— 外层是基板的真头、内层是模型写的（已被 san 中和）。旧判据只认方括号 ⇒ 读侧剥不掉它、
     * 出站闸放行它，模型还能从旧行里学到这个变体。</p>
     *
     * <p><b>识别放宽、产出不放宽</b>：技能线已发布的判据 {@code content.startsWith("[我发的 ")}
     * 依赖的是<b>产出</b>，而产出这条路一个字没改（见 {@link #head}/{@link #content}）；
     * 这里放宽的只是"认"（读侧去头 / 出站闸 / 去重比较）。</p>
     */
    public static final String HEAD_LEAD_PAREN = "(我发的 msg_id=";
    /** 圆括号变体的后缀（识别用；与 {@link #HEAD_LEAD} 只产 {@link #HEAD_TAIL} 同一口径）。 */
    public static final String HEAD_TAIL_PAREN = ")";
    /** 头部与正文/标签之间的分隔（单空格）。 */
    public static final String SEP = " ";

    /**
     * 头部<b>内部</b>允许的最大字符数（{@code 123} / {@code 1295972856} / {@code ?} 一类）。
     * <p>它是"认头"的边界，不是产出口径：产出永远只打一个十进制 id。有它才不至于把
     * "一整句话恰好以 {@code [我发的 msg_id=} 开头"这种文本一直扫到天边。</p>
     */
    private static final int HEAD_MAX = 32;

    /**
     * 正文上限（与 {@link MediaRender#MAX_CHARS} 同量级）。
     * <p>整条标记行的上限因此约 {@code 20（头部）+ 1 + 1000（正文）+ 1 + 1000（段标签）}，
     * 与入站那条"整串标签 1000"的口径同阶。</p>
     */
    public static final int TEXT_MAX = 1000;

    /**
     * 把一次发送的<b>参数</b>合成一条只读事件（<b>全树第 4 个 {@code Ev.parse} 调用点</b>）。
     *
     * <p>只取 {@code params.message} 一个键：正文与段型全部来自它；别的键（{@code group_id} /
     * {@code user_id} / {@code auto_escape}…）对"这一行是什么"没有贡献，带了反而会让
     * {@code Ev} 的其它取值方法看起来"有值"。</p>
     *
     * <p><b>绝不抛</b>：{@code params} 为 {@code null}、没有 {@code message}、或 {@code message}
     * 是认不出的形状 ⇒ 返回一条空事件（各取值给 0/""），调用方拿到的是一条"只有头部"的标记行。
     * 注意 {@link Ev#parse} 本身对 {@code null} 是安全的（自建空对象），这里只是不依赖那一手。</p>
     *
     * @param params 本次发送的完整参数（{@code Api.call} 里 {@code rewrite} 之后的副本）
     * @return 一条合成的只读事件；永不 {@code null}
     */
    public static Ev evOf(JsonObject params) {
        try {
            JsonElement m = params == null ? null : params.get("message");
            if (m == null || m.isJsonNull()) return Ev.parse(null);
            JsonObject one = new JsonObject();
            one.add("message", m);
            return Ev.parse(one);
        } catch (Throwable t) {
            return Ev.parse(null);
        }
    }

    /**
     * 合成事件的<b>正文</b>（{@code text} 段拼接；抽不出就空串）。
     * <p>单独一支是为了让"合成失败 ⇒ 只有头部"这条降级<b>可断言</b>：本方法永不抛。</p>
     */
    public static String textOf(Ev ev) {
        try {
            return ev == null ? "" : Str.nz(ev.plainText());
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 合成事件的<b>段标签</b>（唯一产法：{@link MediaRender#render}）。
     *
     * <p>facts 由调用方按 {@link MediaRender#render} 的契约给（{@link sair.v4.QqGateway#mediaFacts}
     * 对同一条 {@code Ev} 的输出，按段序对齐）—— 本类<b>不</b>自己造 facts，也就不会造出第二套渲染。
     * 任何异常 ⇒ 空串（降级成"只有头部 + 正文"）。</p>
     */
    public static String labels(Ev ev, JsonArray facts) {
        try {
            return Str.oneLine(Str.nz(MediaRender.render(ev, facts)));
        } catch (Throwable t) {
            return "";
        }
    }

    /** 头部：{@code [我发的 msg_id=<十进制 id>]}。{@code msgId<=0} 也照打（调用方会挡住这种行）。 */
    public static String head(long msgId) {
        return HEAD_LEAD + msgId + HEAD_TAIL;
    }

    /**
     * 整条标记行（<b>本类的唯一产法</b>）。
     *
     * @param msgId  真实消息 id（NapCat 返回的那个；{@code <=0} 时调用方<b>不</b>该落行）
     * @param text   出站正文（{@code null}/空 ⇒ 这一段不出现）
     * @param labels 段标签（{@link #labels} 的产出；{@code null}/空 ⇒ 这一段不出现）
     * @return 永不为 {@code null}、永不为空串；不抛异常
     */
    public static String content(long msgId, String text, String labels) {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(head(msgId));
            String t = MediaRender.san(text, TEXT_MAX);
            if (!t.isEmpty()) sb.append(SEP).append(t);
            String l = Str.oneLine(Str.nz(labels));
            if (!l.isEmpty()) sb.append(SEP).append(l);
        } catch (Throwable t) {
            // 降级绝不静默：头部一定已经在 sb 里了；任何异常就返回"到出错为止"的那部分，
            // 最差 = 只有头部（绝不会是空串、更不会抛）。
            if (sb.length() == 0) sb.append(head(msgId));
        }
        return sb.toString();
    }

    /**
     * 这个 {@code content} 是不是本类产的标记行（只看头部<b>前缀</b>，<b>绝不解析</b>）。
     *
     * <p><b>FIX-ECHO 补-1</b>：方括号形态（{@link #HEAD_LEAD}，唯一产法）与<b>圆括号变体</b>
     * （{@link #HEAD_LEAD_PAREN}，{@code san} 中和出来的形状）<b>都认</b>。仍然只看前缀、不要求收尾 ——
     * 所以"认得出前缀却没有收尾"的半截头也算命中，调用方据此<b>整条不发</b>（fail-closed），
     * 而不是把半截头当正文发出去。</p>
     */
    public static boolean isSelfContent(String content) {
        return content != null && atVisibleStart(content);
    }

    /**
     * {@code s} 里第一个自我记账头（任一形态）的下标；{@code -1} = 没有。
     *
     * <p>与 {@link #isSelfContent} 同一判据（<b>前缀级</b>：不要求收尾），因此"中段的半截头"同样命中 ——
     * 出站闸要的正是 fail-closed。快路：一个形态首字符都没有就直接返回，正常文本一个字都不多扫。</p>
     */
    public static int headAt(String s) {
        if (s == null || s.isEmpty()) return -1;
        Norm v = norm(s);
        int at = headAtView(v.s);
        return at < 0 ? -1 : v.orig(at);          // 返回**原文**下标（日志/调用方都用原文坐标）
    }

    /** 判据本体（跑在<b>归一化视图</b>上）：视图里第一个头部<b>前缀</b>的下标；{@code -1} = 没有。 */
    private static int headAtView(String s) {
        if (s == null || s.isEmpty()) return -1;
        char a = VIEW_LEAD.charAt(0);
        char b = VIEW_LEAD_PAREN.charAt(0);
        if (s.indexOf(a) < 0 && s.indexOf(b) < 0) return -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != a && c != b) continue;
            if (leadLenView(s, i) > 0) return i;
        }
        return -1;
    }

    /**
     * {@code s} 的<b>可见开头</b>是不是一个头部（任一形态、<b>含变体写法</b>）。
     *
     * <p><b>FIX-ECHO 补-8</b>：判据跑在归一化视图上（见 {@link #norm}），所以"前导的零宽字符 /
     * 全角括号 / 双空格"都不影响"这是不是开头那一个头"；返回的是<b>视图坐标 0</b> 的事实
     * （出站闸据此决定"剥头再发"而不是"整条不发"）。</p>
     */
    public static boolean atVisibleStart(String s) {
        if (s == null || s.isEmpty()) return false;
        return headAtView(norm(s).s) == 0;
    }

    // ------------------------------------------------------------------ 归一化视图（补-8）

    /**
     * <b>归一化视图</b>：给判据用的一张"同一形状的不同写法"折叠表（GM 第七轮裁定）。
     *
     * <p><b>为什么需要它</b>：判据原本只认逐字 ASCII 方括号/圆括号形状；复核把 18 种写法逐个喂
     * {@link #headAt}，只有 5 种认。而读侧按第四轮裁定<b>故意逐字保留外人文本</b> ⇒ 用户只要打出
     * 变体，模型就可能照抄，闸门却看不见 ⇒ 用户收到"人眼与原始抱怨逐字同形"的头。</p>
     *
     * <p><b>折叠规则（判据用，只影响"认不认"）</b>：</p>
     * <ol>
     *   <li><b>不可见字符</b>一律丢掉：{@code Character.FORMAT}（Cf：{@code \u200B} 零宽空格、
     *       {@code \u200C}/\u200D}、{@code \u2060} word-joiner、{@code \uFEFF} BOM…）与软连字符
     *       {@code \u00AD}；</li>
     *   <li><b>全角→半角</b>：{@code \uFF01..\uFF5E} 整段平移（覆盖 {@code ［］（）＝} 与全角字母数字），
     *       全角空格 {@code \u3000} 与 NBSP 当空白；</li>
     *   <li><b>空白不进视图</b>（彻底去掉）⇒ 双空格、{@code msg_id = 1}、{@code 我发的 msg_id} 里
     *       那个空格、{@code [我发的msg_id=1]} 全部等价；</li>
     *   <li><b>ASCII 字母小写化</b> ⇒ {@code MSG_ID} / {@code Msg_Id} 等价；</li>
     *   <li><b>繁体「發」→「发」</b>（最小的一个字映射，不做整表）。</li>
     * </ol>
     *
     * <p><b>它只用来"判"</b>：{@link #withoutHead} 拿到视图里的头跨度之后，用
     * {@link Norm#map} 映射回<b>原文下标</b>再切 —— 所以"剥"永远作用在原文上，正文一个字不改。</p>
     */
    private static final class Norm {
        final String s;
        final int[] map;                      // map[i] = 视图第 i 个字符在原文里的下标
        Norm(String s, int[] map) { this.s = s; this.map = map; }
        int orig(int viewIdx) {
            if (viewIdx < 0 || viewIdx >= map.length) return -1;
            return map[viewIdx];
        }
    }

    private static Norm norm(String src) {
        String s = Str.nz(src);
        StringBuilder sb = new StringBuilder(s.length());
        int[] map = new int[s.length()];
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\u00AD' || Character.getType(c) == Character.FORMAT) continue;   // 不可见：丢
            char x = foldChar(c);
            if (isBlank(x)) continue;                                                // 空白：不进视图
            sb.append(x);
            map[n++] = i;
        }
        int[] m = new int[n];
        System.arraycopy(map, 0, m, 0, n);
        return new Norm(sb.toString(), m);
    }

    /** 全角→半角 / 繁「發」→简「发」/ ASCII 小写（其余原样）。 */
    private static char foldChar(char c) {
        if (c == '\u767C') return '\u53D1';                  // 發 → 发
        if (c >= '\uFF01' && c <= '\uFF5E') return (char) (c - 0xFEE0);
        if (c == '\u3000') return ' ';
        if (c >= 'A' && c <= 'Z') return (char) (c + 32);
        return c;
    }

    /** 空白判定（ASCII 空白 + 全角空格 + NBSP 一类不可断空格）。 */
    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f'
                || c == '\u000B' || c == '\u00A0' || c == '\u3000'
                || Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** {@code s} 从 {@code i} 起是不是一个头部的<b>前缀</b>（不要求收尾）；是则返回该前缀长度，否则 {@code -1}。 */
    private static int leadLenView(String s, int i) {
        if (s.startsWith(VIEW_LEAD, i)) return VIEW_LEAD.length();
        if (s.startsWith(VIEW_LEAD_PAREN, i)) return VIEW_LEAD_PAREN.length();
        return -1;
    }

    /**
     * 判据用的<b>视图版</b>前缀：把 {@link #HEAD_LEAD} / {@link #HEAD_LEAD_PAREN} 按 {@link #norm} 的同一条
     * 折叠规则处理一遍（唯一真源仍是那两个常量 —— 改文法只需改它们，视图自动跟上）。
     * 视图里没有空白，所以视图版前缀也没有那个空格。
     */
    private static final String VIEW_LEAD = deBlank(HEAD_LEAD);
    private static final String VIEW_LEAD_PAREN = deBlank(HEAD_LEAD_PAREN);

    private static String deBlank(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = foldChar(s.charAt(i));
            if (isBlank(c)) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * {@code s} 从 {@code i} 起是不是一个<b>完整</b>头部（lead + 内部 + 与 lead 配对的收尾）；
     * 是则返回整段长度，否则 {@code -1}。<b>跑在视图上</b>。
     *
     * <p>内部：1..{@value #HEAD_MAX} 个字符，不许出现四种括号字符，也不许跨行 ——
     * 于是 {@code [我发的msg_id=123]} / {@code (我发的msg_id=1295972856)} / {@code [我发的msg_id=?]}
     * 都算，而 {@code [我发的msg_id=} 后面没有收尾的<b>不算</b>（交给调用方按"残缺"处置）。</p>
     */
    private static int headLenView(String s, int i) {
        int n = completeView(s, i, VIEW_LEAD, HEAD_TAIL);
        if (n >= 0) return n;
        return completeView(s, i, VIEW_LEAD_PAREN, HEAD_TAIL_PAREN);
    }

    private static int completeView(String s, int i, String lead, String tail) {
        if (!s.startsWith(lead, i)) return -1;
        int from = i + lead.length();
        int to = Math.min(s.length(), from + HEAD_MAX);
        for (int j = from; j < to; j++) {
            char c = s.charAt(j);
            if (s.startsWith(tail, j)) return (j + tail.length()) - i;
            if (c == '\n' || c == '\r' || c == '[' || c == ']' || c == '(' || c == ')') return -1;
        }
        return -1;
    }

    /**
     * 去掉头部之后的正文（<b>读侧去重/比较 + 读侧注入 + 出站剥头都用它</b>，见 P0-1「窗口读侧 (b′)」）。
     *
     * <p>为什么需要它：窗口（{@code grouplog}）里她的那一行<b>带头部</b>，而历史
     * （{@code dialog} 的 {@code assistant} 行）里同一条话是<b>裸正文</b> —— 既有去重是
     * <b>整串逐字相等</b>，加了头部就永不命中 ⇒ 她的话会把真群友挤出窗口（实测最坏 14/30 = 47%）。
     * 比较时把头部剥掉，"同一条话"就重新可比。</p>
     *
     * <p><b>FIX-ECHO 补-1：可重复剥 + 认变体</b>。开头<b>连续多个</b>头部全部剥掉：
     * 方括号的、圆括号变体的、{@code [我发的 msg_id=?]} 的（真机 {@code dialog id=2647} 就是这个形状）、
     * 以及"外层真头 + 内层模型写的头"那种<b>双层</b>（真机 {@code dialog id=2370}）。每个头后面
     * 那<b>一个</b>分隔空格一并吃掉。中段的头<b>不</b>动 —— 那交给出站闸整条拦下，比较串也不该猜截断点。</p>
     *
     * <p><b>幂等</b>：不带头部的串原样返回；认得出前缀但没有收尾的（半截头）也<b>原样返回</b> ——
     * 宁可"比较不上"（少去重一行），也绝不猜一个截断点。</p>
     *
     * <p><b>FIX-ECHO 补-8：判在归一化视图、切在原文</b>。头跨度在 {@link Norm} 视图里找（所以全角括号、
     * 零宽字符、双空格、{@code msg_id = 1}、大写 {@code MSG_ID}、繁体「發」这些写法都认），
     * 再用 {@link Norm#map} 映射回<b>原文下标</b>去切 —— <b>正文一个字都不改</b>，
     * 也绝不会出现"判得出、剥不掉"。头后面那段空白（可能不止一个空格）一并吃掉。</p>
     */
    public static String withoutHead(String content) {
        String s = Str.nz(content);
        while (true) {
            Norm v = norm(s);
            int n = headLenView(v.s, 0);               // 视图里的完整头长度
            if (n <= 0) return s;                      // 没有（完整）头部 = 到头了
            int cut = v.orig(n - 1) + 1;               // 视图最后一格 → 原文下标 + 1
            if (cut <= 0 || cut > s.length()) return s;    // 映射不合法：宁可不动
            String body = s.substring(cut);
            // 头部与正文之间那段空白不参与比较（不带头部的正文本来就没有它）
            int k = 0;
            while (k < body.length() && isBlank(body.charAt(k))) k++;
            body = body.substring(k);
            s = body;
            if (s.isEmpty()) return s;
        }
    }
}
