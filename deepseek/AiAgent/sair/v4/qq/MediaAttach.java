package sair.v4.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * <b>把本轮的媒体事实翻成"这一轮到底能挂哪几张图"的纯函数</b>
 * （无模型、无网络、无时钟、不读配置、不读库、不改入参 ⇒ 探针不需要 Boot 就能断言）。
 *
 * <h3>它解决什么</h3>
 * <p>今天图片段在事实块里只是一行给机器看的 JSON（{@code media: [{"type":"image","file":"…","url":"…"}]}），
 * 模型要"看图"得靠她自己调工具、或者主 Agent 派一个会看图的子 Agent 兜底。
 * flash 这类模型<b>自己就能看图</b>，把 URL 直接挂进请求体（{@code image_url}）是零额外成本的，
 * 但"什么时候能挂、挂哪几张、丢掉的为什么丢"必须有<b>一处</b>说得清的口径 —— 这个类就是那一处。
 *
 * <h3>唯一产法（与 {@link MediaRender} 同一套纪律）</h3>
 * <p>{@link #scan(JsonArray, int, long)} 是唯一的判定入口：{@link #imageUrls} 只是它的薄包装，
 * {@link #sentFact}/{@link #noneFact} 只是把同一个 {@link Result} 序列化成事实行。
 * <b>没有第二个入口</b>，就不可能有两套"能挂/不能挂"的说法。
 *
 * <h3>三档判据（逐条可断言）</h3>
 * <ol>
 *   <li>段型必须是 {@code image}（大小写不敏感；本类不认 {@code face}/表情 —— 那些没有 URL）；</li>
 *   <li>URL 必须过 {@link #usable}：与 {@code qq.Inbound.usable} <b>逐字同一口径</b>
 *       （{@code http://} / {@code https://} / {@code data:image/}，先 lower+trim）；
 *       {@code Inbound.usable} 是 {@code private}，所以这里<b>复制口径</b>而不是调用它 ——
 *       两边任何一处改了，另一处必须跟着改，这是唯一需要人工同步的地方（已在此写明）；</li>
 *   <li>图片的<b>序号</b>（在 image 序列里，1 起）必须 {@code <= max}；
 *       {@code max <= 0} ⇒ 一张都不挂（这是"关掉挂图"的表达方式，不是"无上限"）；</li>
 *   <li>{@code file_size} <b>缺失 ⇒ 放行</b>（表情类小图常常没有这个字段，为了一个可选字段把图丢掉是错的）；
 *       存在则必须 {@code <= maxBytes}（{@code maxBytes <= 0} = 不限）。</li>
 * </ol>
 *
 * <h3>降级必须看得见</h3>
 * <p>丢掉的每一张都进 {@link Result#skipped()}，带<b>序号</b>与<b>原因</b>（{@code max}/{@code bytes}/{@code url}）；
 * {@code file_size} 缺失而放行的条数记 {@link Result#nosize()}。
 * 一张都没挂上时由 {@link #noneFact} 产出一行 {@code attach:{"state":"none","why":…}} ——
 * 绝不静默：她要是不知道"图没挂上来"，就会凭空描述图里有什么。
 */
public final class MediaAttach {

    private MediaAttach() {}

    /** 事实行前缀（与 {@code rounds:} / {@code media:} / {@code tool_failures:} 同一形状）。 */
    public static final String PREFIX = "attach: ";

    /** 丢图的原因词表（只有这三个值，供事实行与断言逐字比对）。 */
    public static final String WHY_MAX = "max";
    public static final String WHY_BYTES = "bytes";
    public static final String WHY_URL = "url";

    /** "一张都没挂上"的原因词表（{@link #noneFact} 的 {@code why}）。 */
    public static final String NONE_VISION_OFF = "vision_off";
    public static final String NONE_VISION_RELAY = "vision_relay";
    public static final String NONE_ATTACH_OFF = "attach_off";
    public static final String NONE_ALL_SKIPPED = "all_skipped";

    /** 挂图后追加的那句话（她必须在后续轮次知道"图只在第 1 次请求里出现过"）。 */
    public static final String SENT_NOTE = "图已在本轮第 1 次请求里看过，后续轮次只有文字";

    // ---------------------------------------------------------------- 结果结构

    /** 一张被丢掉的图：{@code ord} = 它是第几张图（1 起），{@code why} = 见上面三个词表。 */
    public static final class Skip {
        private final int ord;
        private final String why;

        Skip(int ord, String why) { this.ord = ord; this.why = why; }

        public int ord() { return ord; }

        public String why() { return why; }
    }

    /** {@link #scan} 的结构化结果（纯数据；不可变）。 */
    public static final class Result {
        private final int images;
        private final List<String> urls;
        private final List<Skip> skipped;
        private final int nosize;

        Result(int images, List<String> urls, List<Skip> skipped, int nosize) {
            this.images = images;
            this.urls = Collections.unmodifiableList(urls);
            this.skipped = Collections.unmodifiableList(skipped);
            this.nosize = nosize;
        }

        /** 事实里 image 段的总数（含被丢的）。 */
        public int images() { return images; }

        /** 真正要挂上去的 URL（段序）。 */
        public List<String> urls() { return urls; }

        /** 被丢掉的图（序号 + 原因）。 */
        public List<Skip> skipped() { return skipped; }

        /** {@code file_size} 缺失因而放行的条数。 */
        public int nosize() { return nosize; }

        /** 一张都没挂上。 */
        public boolean empty() { return urls.isEmpty(); }
    }

    // ---------------------------------------------------------------- 唯一判定入口

    /**
     * 逐段判定"这一轮能挂哪几张图"。<b>本类的唯一算法。</b>
     *
     * @param facts    媒体事实的段数组（见 {@link #parse}；null / 空 ⇒ 空结果，不抛）
     * @param max      最多挂几张（{@code <= 0} ⇒ 一张都不挂）
     * @param maxBytes 单张字节上限（{@code <= 0} = 不限；{@code file_size} 缺失一律放行）
     */
    public static Result scan(JsonArray facts, int max, long maxBytes) {
        List<String> urls = new ArrayList<String>();
        List<Skip> skipped = new ArrayList<Skip>();
        int images = 0;
        int nosize = 0;
        if (facts == null) return new Result(0, urls, skipped, 0);
        for (int i = 0; i < facts.size(); i++) {
            JsonElement e = facts.get(i);
            if (e == null || !e.isJsonObject()) continue;
            JsonObject f = e.getAsJsonObject();
            if (!"image".equals(Str.lower(Str.trim(J.s(f, "type", ""))))) continue;
            images++;
            int ord = images;                       // 第几张图（1 起）
            String url = Str.trim(J.s(f, "url", ""));
            if (!usable(url)) {
                skipped.add(new Skip(ord, WHY_URL));
                continue;
            }
            if (max <= 0 || ord > max) {
                skipped.add(new Skip(ord, WHY_MAX));
                continue;
            }
            String size = Str.trim(J.s(f, "file_size", ""));
            if (size.isEmpty()) {
                // 缺字段 = 放行（表情类小图常常没有 file_size）。为了一个可选字段丢图是错的。
                nosize++;
            } else {
                long n = num(size);
                if (n >= 0L && maxBytes > 0L && n > maxBytes) {
                    skipped.add(new Skip(ord, WHY_BYTES));
                    continue;
                }
            }
            urls.add(url);
        }
        return new Result(images, urls, skipped, nosize);
    }

    /** {@link #scan} 的薄包装（真值一律来自 scan，没有第二处算法）。 */
    public static List<String> imageUrls(JsonArray facts, int max, long maxBytes) {
        return scan(facts, max, maxBytes).urls();
    }

    /**
     * 媒体事实行的字符串 → 段数组。
     * <p>吃两种形状：{@code media: [...]}（{@code ST_MEDIA} 的现码形状）与裸 {@code [...]}。
     * <b>畸形一律返回空数组、绝不抛</b> —— 事实块少一行不能把回合拖垮。
     */
    public static JsonArray parse(String mediaLine) {
        JsonArray out = new JsonArray();
        String s = Str.trim(mediaLine);
        if (s.isEmpty()) return out;
        if (s.startsWith("media:")) s = Str.trim(s.substring("media:".length()));
        if (!s.startsWith("[")) return out;
        JsonArray a = J.arr(s);
        return a == null ? out : a;
    }

    /** 与 {@code qq.Inbound.usable} 逐字同一口径（那边是 private，所以这里复制口径）。 */
    public static boolean usable(String url) {
        String u = Str.lower(Str.trim(url));
        return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("data:image/");
    }

    // ---------------------------------------------------------------- 摘图

    /**
     * 把<b>最后一条 user</b> 的 {@code content} 从数组降回纯 text（保留原文案）。
     *
     * <p>为什么必须摘：{@code Loop.run} 每一轮把整个 {@code messages} 发出去，
     * 挂着图就等于<b>每轮重发一次</b>（成本随轮数倍增，行为上完全看不出来）。
     * 摘的依据是"第 1 次请求已经发过了"—— 调用时机由 {@code Loop} 负责（first request 返回之后）。
     *
     * <p>幂等：content 不是数组（已经摘过 / 本来就是纯 text）就直接返回，第二次调用无所作为。
     * 只碰最后一条 user，历史里的 user 轮一个字节不动。
     */
    public static void strip(JsonArray messages) {
        if (messages == null) return;
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonElement e = messages.get(i);
            if (e == null || !e.isJsonObject()) continue;
            JsonObject m = e.getAsJsonObject();
            if (!"user".equals(J.s(m, "role", ""))) continue;
            JsonElement c = m.get("content");
            if (c == null || c.isJsonNull() || !c.isJsonArray()) return;
            String text = "";
            for (JsonElement p : c.getAsJsonArray()) {
                if (p == null || !p.isJsonObject()) continue;
                JsonObject po = p.getAsJsonObject();
                if ("text".equals(J.s(po, "type", ""))) { text = J.s(po, "text", ""); break; }
            }
            J.put(m, "content", text);
            return;
        }
    }

    // ---------------------------------------------------------------- 事实行

    /**
     * 挂上了的那一行：{@code attach: {"state":"sent","n":N,…,"note":…}}。
     * <p>由 {@code Loop} 在<b>第 1 次请求返回之后</b>追加 —— 那时"看过"才成立（note 是过去式）。
     */
    public static String sentFact(Result r) {
        JsonObject o = new JsonObject();
        o.addProperty("state", "sent");
        o.addProperty("n", r == null ? 0 : r.urls().size());
        o.addProperty("images", r == null ? 0 : r.images());
        addSkipped(o, r);
        o.addProperty("note", SENT_NOTE);
        return PREFIX + J.json(o);
    }

    /**
     * 一张都没挂上的那一行：{@code attach: {"state":"none","why":…}}。
     *
     * @param why 见 {@code NONE_*} 词表（调用方按三道闸的哪一道没过给出）
     * @param max 这一轮的 {@code mediaAttachMax}（写进事实，便于"为什么只有 2 张"自查）
     */
    public static String noneFact(String why, Result r, int max) {
        JsonObject o = new JsonObject();
        o.addProperty("state", "none");
        o.addProperty("why", why == null ? "" : why);
        o.addProperty("images", r == null ? 0 : r.images());
        o.addProperty("max", max);
        addSkipped(o, r);
        return PREFIX + J.json(o);
    }

    /** 被丢的图：{@code "skipped":[{"ord":3,"why":"max"}]} + {@code "nosize":n}（都没有就不写这两个键）。 */
    private static void addSkipped(JsonObject o, Result r) {
        if (r == null) return;
        if (!r.skipped().isEmpty()) {
            JsonArray a = new JsonArray();
            for (Skip s : r.skipped()) {
                JsonObject x = new JsonObject();
                x.addProperty("ord", s.ord());
                x.addProperty("why", s.why());
                a.add(x);
            }
            o.add("skipped", a);
        }
        if (r.nosize() > 0) o.addProperty("nosize", r.nosize());
    }

    /** 一个字符串里的非负整数（{@code file_size} 可能是字符串或数字；非数字 ⇒ -1 = 判不了，按放行处理）。 */
    private static long num(String s) {
        try {
            return Long.parseLong(Str.trim(s));
        } catch (Exception e) {
            try {
                double d = Double.parseDouble(Str.trim(s));
                return d < 0 ? -1L : (long) d;
            } catch (Exception e2) {
                return -1L;
            }
        }
    }
}
