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
 *       截断 ⇒ <b>外部文本（乃至她自己的正文）伪造不出这个形状</b>，也伪造不出第二个头部。</li>
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
    /** 头部与正文/标签之间的分隔（单空格）。 */
    public static final String SEP = " ";

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

    /** 这个 {@code content} 是不是本类产的标记行（只看头部前缀，<b>绝不解析</b>）。 */
    public static boolean isSelfContent(String content) {
        return content != null && content.startsWith(HEAD_LEAD);
    }

    /**
     * 去掉头部之后的正文（<b>给读侧的去重/比较用</b>，见 P0-1「窗口读侧 (b′)」）。
     *
     * <p>为什么需要它：窗口（{@code grouplog}）里她的那一行<b>带头部</b>，而历史
     * （{@code dialog} 的 {@code assistant} 行）里同一条话是<b>裸正文</b> —— 既有去重是
     * <b>整串逐字相等</b>，加了头部就永不命中 ⇒ 她的话会把真群友挤出窗口（实测最坏 14/30 = 47%）。
     * 比较时把头部剥掉，"同一条话"就重新可比。</p>
     *
     * <p><b>幂等</b>：不带头部的串原样返回；认不出头部（{@code head} 之后没有 {@link #HEAD_TAIL}）
     * 也原样返回 —— 宁可"比较不上"（少去重一行），也绝不猜一个截断点。</p>
     */
    public static String withoutHead(String content) {
        String s = Str.nz(content);
        if (!s.startsWith(HEAD_LEAD)) return s;
        int end = s.indexOf(HEAD_TAIL, HEAD_LEAD.length());
        if (end < 0) return s;
        String body = s.substring(end + HEAD_TAIL.length());
        // 头部与正文之间的那一个分隔空格不参与比较（不带头部的正文本来就没有它）
        if (body.startsWith(SEP)) body = body.substring(SEP.length());
        return body;
    }
}
