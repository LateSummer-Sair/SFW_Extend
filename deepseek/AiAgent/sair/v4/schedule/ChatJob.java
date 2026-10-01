package sair.v4.schedule;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 一条"用户说话"的回合任务：可被同会话的后到消息<b>合并</b>（合并成一回合），
 * 也能在溢出时回一句外挂文案。
 *
 * <p>合并是"零丢失"的替代品：与其把第 2~10 条消息丢掉，不如把它们并进该会话<b>还在排队</b>的
 * 那一条里，一轮全部交给模型（用户看到一条把十句都回了的回复）。合并<b>只做整段拼接</b>：
 * 装不下就 {@code -1}（不合），绝不腰斩正文 —— 半个句子比丢一条更难查。</p>
 *
 * <h3>★ 2026-09-22「内部文字外泄防线批」G7：<b>不同说话人不合并</b></h3>
 * <p>合并块的身份 = <b>先排队那条消息的说话人</b>（{@code caller} 是创建任务时绑死在闭包里的，
 * 合并动不了它，见 {@code QqGateway.submitTurn}），而 {@link #merge} 只拼 {@code text}/{@code media}
 * ⇒ 后并进来的那个人说的话，会被按<b>第一个人的身份</b>处理（真机实测：主人的话被并进群友那条排队任务，
 * 那一轮按群友的 {@code ALLUSER} 身份办）。所以本类带一个<b>说话人标识</b> {@link #owner()}
 * （{@code session + "#" + qq} 或等价物，由创建方给），{@link #merge} 在<b>说话人不同时返回 -1</b>：
 * 宁可不合、多跑一轮（各自 caller 正确），也不串人 —— 与 {@code ChatJob.java:69-73} 为
 * {@code quote:} 事实行做过的取舍同一方向。</p>
 * <p>空标识（老调用方 / 探针不传）与空标识视为<b>同一个人</b>（保持既有合并行为不变）；
 * 一边有标识一边没有 ⇒ 不合（保守方向）。</p>
 */
public final class ChatJob implements Job {

    /** 真正跑回合：{@code text} 是正文，{@code mediaFact} 是结构化事实行（可空）。 */
    public interface Body {
        void run(String text, String mediaFact);
    }

    /** 回话落点（可为 null）。 */
    public interface Notice {
        void say(String text);
    }

    private final String session;
    private final boolean high;
    private final Body body;
    private final Notice notice;
    private final int maxChars;
    /** 说话人标识（G7；空串 = 老调用方/探针没给 —— 见类注释「不同说话人不合并」）。 */
    private final String owner;

    private String text;
    private String media;
    private int merged;

    public ChatJob(String session, boolean high, Body body, Notice notice,
                   String text, String mediaFact, int maxChars) {
        this(session, high, body, notice, text, mediaFact, maxChars, "");
    }

    /**
     * 带<b>说话人标识</b>的那一支（G7；生产走这一支，{@code owner} 由 {@code QqGateway.submitTurn}
     * 按 {@code caller} 给）。
     *
     * @param owner 说话人标识（{@code session + "#" + qq} 或等价物；空串 = 不知道 ⇒ 见 {@link #merge}）
     */
    public ChatJob(String session, boolean high, Body body, Notice notice,
                   String text, String mediaFact, int maxChars, String owner) {
        this.session = session == null ? "" : session;
        this.high = high;
        this.body = body;
        this.notice = notice;
        this.text = Str.nz(text);
        this.media = Str.blank(mediaFact) ? "" : mediaFact.trim();
        this.maxChars = maxChars <= 0 ? 4000 : maxChars;
        this.owner = Str.nz(owner).trim();
    }

    /** 说话人标识（G7）：{@code session + "#" + qq} 或等价物；空串 = 创建方没给。 */
    public String owner() {
        return owner;
    }

    /** 已经并进来多少字符（诊断用）。 */
    public int mergedChars() {
        return merged;
    }

    @Override
    public String session() {
        return session;
    }

    @Override
    public boolean high() {
        return high;
    }

    @Override
    public int merge(Job later) {
        if (!(later instanceof ChatJob)) return -1;
        ChatJob o = (ChatJob) later;
        // ★ G7（2026-09-22「内部文字外泄防线批」）：**不同说话人不合并**。
        //   合并只拼 text/media，身份是 final ⇒ 合了就按第一个人的 caller 办事（真机实测：
        //   主人的话被并进群友那条排队任务，那一轮按群友的 ALLUSER 身份办）。宁可不合、
        //   多跑一轮（各自 caller 正确），也不串人。空标识（老调用方/探针）之间仍按老行为合并；
        //   一边有标识一边没有 ⇒ 不合（保守方向）。
        if (!owner.equals(Str.nz(o.owner).trim())) return -1;
        // M4：带引文事实行（`quote: …`）的回合**不参与合并** —— 下面第 :85 行会**重建** media 串
        // （`newMedia = "media: " + J.json(a)`），任何非 `media:` 的行都会被**静默丢掉**
        // ⇒ 引文事实凭空消失、两轮里只有一轮看得见那句话。宁可多跑一轮（与 :74「装不下就不合，
        // 不腰斩」同一口径：合并是"零丢失"的替代品，不是承诺）。
        if (hasQuote(media) || hasQuote(o.media)) return -1;
        String addText = Str.nz(o.text).trim();
        String newText = text;
        int add = 0;
        if (Str.has(addText)) {
            int room = maxChars - text.length() - 1;
            if (room <= 0 || addText.length() > room) return -1;      // 装不下：不合（不腰斩）
            newText = text + (text.isEmpty() ? "" : "\n") + addText;
            add += addText.length() + (text.isEmpty() ? 0 : 1);
        }
        // 媒体事实：两行 media: [...] 合成一行（数组拼接）；解析不出来就整条不合
        String newMedia = media;
        if (Str.has(o.media)) {
            JsonArray a = arrOf(media);
            JsonArray b = arrOf(o.media);
            if (a == null || b == null) return -1;
            for (JsonElement e : b) a.add(e);
            newMedia = "media: " + J.json(a);
            int grew = newMedia.length() - media.length();
            if (newText.length() + grew > maxChars) return -1;
        }
        text = newText;
        media = newMedia;
        merged += add;
        return add;
    }

    /** {@code media: [ ... ]} → JsonArray；空串 = 空数组；解析不出来返回 null。 */
    private static JsonArray arrOf(String s) {
        if (Str.blank(s)) return new JsonArray();
        String t = s.trim();
        int i = t.indexOf('[');
        if (i < 0) return null;
        JsonElement e = J.el(t.substring(i));
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    /**
     * M4：这条事实串里有没有引文行（{@code quote:}）。
     *
     * <p>为什么用子串判定而不是"解析出所有行再比"：{@code media} 串就是"一行行事实"，
     * 引文行的前缀是固定的 {@link sair.v4.qq.QuoteCache#FACT_PREFIX}；这里要的只是
     * "能不能安全合并"这一个布尔，判错了的代价也必须是不合并（保守方向）。</p>
     */
    private static boolean hasQuote(String s) {
        return Str.has(s) && s.indexOf(sair.v4.qq.QuoteCache.FACT_PREFIX) >= 0;
    }

    @Override
    public boolean notice(String text) {
        if (notice == null || Str.blank(text)) return false;
        try {
            notice.say(text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void run() {
        body.run(text, media);
    }

    /** 诊断：本轮真正送进模型的正文。 */
    public String text() {
        return text;
    }

    /** 诊断：本轮的事实行。 */
    public String mediaFact() {
        return media;
    }
}
