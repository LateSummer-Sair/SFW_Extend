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

    private String text;
    private String media;
    private int merged;

    public ChatJob(String session, boolean high, Body body, Notice notice,
                   String text, String mediaFact, int maxChars) {
        this.session = session == null ? "" : session;
        this.high = high;
        this.body = body;
        this.notice = notice;
        this.text = Str.nz(text);
        this.media = Str.blank(mediaFact) ? "" : mediaFact.trim();
        this.maxChars = maxChars <= 0 ? 4000 : maxChars;
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
