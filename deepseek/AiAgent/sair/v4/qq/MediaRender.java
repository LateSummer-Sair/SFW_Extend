package sair.v4.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * <b>一条消息的确定性文本标签</b>（纯函数：无模型、无网络、无副作用、无随机、无时钟、不读配置、不读库）。
 *
 * <h3>它解决什么</h3>
 * <p>今天"只有媒体段的消息"落到 {@code grouplog.content} 是<b>空串</b>，窗口里被渲染成「（无文本）」；
 * 模型能看到的只有事实块里那行给机器看的 JSON（{@code media: [{"type":"image",…}]}）。
 * 本类只做一件事：把一条消息的内容段翻成<b>人话标签</b>（{@code [图片 猫.png 20KB]}），
 * 而且<b>一律非空</b>——一条消息只要有非 text 段，就绝不会被渲染成空。</p>
 *
 * <h3>唯一的产法（这是"同一条消息只有一种渲染"的结构性保证）</h3>
 * <p>签名只有 {@link #render(Ev, JsonArray)}：{@code facts} <b>必须</b>来自同一条 {@code Ev} 的
 * {@link sair.v4.QqGateway#mediaFacts(Ev)}，按段序对齐（facts[i] 对应第 i 个"非 text 且 type 非空"的段）。
 * <b>刻意不提供一参数重载</b>：没有第二个入口，就没有第二种渲染。</p>
 *
 * <h3>契约（逐条可断言）</h3>
 * <ol>
 *   <li>纯函数：同一 {@code Ev} + 同一 {@code facts} ⇒ 逐字节相同输出，且<b>不修改</b> {@code facts}；</li>
 *   <li>只渲染<b>非 text 段</b>（文字归 {@link Ev#plainText()} 管）；</li>
 *   <li>每个段最多一个方括号标签，标签之间单个空格，段序 = 输出序（嵌套转发节点的内容不成标签，
 *       见下）⇒ <b>不会出现嵌套方括号</b>；</li>
 *   <li>输出<b>绝不</b>含 {@code ·}（那是机器产出描述的标记，见 {@link #MACHINE_FMT}）、
 *       绝不含换行/Tab、值的方括号一律换成圆括号；</li>
 *   <li>输出长度 ≤ {@value #MAX_CHARS}，超出在<b>标签边界</b>截断并补 {@code …+<剩余字符数>}；</li>
 *   <li>任何段型都有标签（未知段型 ⇒ {@code [未知段 type=…]}）⇒ 有内容段就<b>非空</b>；</li>
 *   <li><b>不编造</b>：只打事实/段里真有的字段（不写"未识别"之类的推断词 —— 那是识图那一步的语义）；</li>
 *   <li>不抛异常：null / 畸形段 / 缺 data / facts 缺失（null 或空）都返回字符串。</li>
 * </ol>
 *
 * <h3>与出站占位符的关系（刻意不统一）</h3>
 * <p>{@code data\skills\消息发送\SendSkill.java} 的出站占位符是 {@code [image]/[record]/[face]/[file]/
 * [video]/[card]/[forward]} + {@code @{qq}}（英文、方括号）—— 那是<b>出站转述</b>（模型写给人看的话里
 * 用什么记号表示"这里要放一张图"），本类是<b>入站记录</b>（把收到的段翻成人话）。两者用途不同、
 * 消费者不同，<b>不许互相抄</b>：改出站占位符等于改主人能看见的文案（那套文案外挂在 {@code prompt.md}）。</p>
 *
 * <h3>机器产出的文本怎么包（只提供形状，本步不写任何机器文本）</h3>
 * <p><b>硬规定：基板自己的标签里永远不出现 {@code ·}。</b>机器写的东西一律走 {@link #machine}，
 * 形如 {@code [识图·deepseek-flash] 一只橘猫}。于是两种形状不可能混：基板标签 = {@code [} + 中文段型词 + 空格；
 * 机器文本 = {@code [} + 来源 + {@code ·} + 模型 + {@code ]} + 空格。第三方文本里的 {@code ·} 会在
 * {@link #san} 里被换成 {@code -}（真实的位置卡 prompt 就带 {@code ·}），所以"标签里有 {@code ·}
 * ⇒ 那是机器写的"这条判据成立。</p>
 */
public final class MediaRender {

    private MediaRender() {}

    /** 整串标签的上限（超出在标签边界截断并补 {@code …+N}）。 */
    public static final int MAX_CHARS = 1000;
    /** 单个值的上限（{@code file}/{@code name}/{@code nickname} 等都按它截断）。 */
    public static final int MAX_FIELD_CHARS = 80;
    /** 机器产出文本的包装形状：{@code [来源·模型] }。 */
    public static final String MACHINE_FMT = "[%s·%s] ";
    /** 机器文本正文的上限。 */
    public static final int MACHINE_TEXT_CHARS = 800;
    /** 最多渲染多少个段（防"一万个段"这种畸形把渲染拖成炸弹；超出的段不再出标签）。 */
    private static final int MAX_LABELS = 400;
    /** 未知段最多打几个标量字段。 */
    private static final int UNKNOWN_FIELDS_MAX = 6;
    /** 未知段单个值的上限。 */
    private static final int UNKNOWN_VALUE_CHARS = 40;
    /** 嵌套转发节点内容的渲染深度上限。 */
    private static final int NODE_DEPTH_MAX = 2;
    /** 嵌套转发节点里最多渲染几个子段。 */
    private static final int NODE_INNER_MAX = 8;

    /** 卡片类段型（与 {@code QqGateway.CARD_TYPES} 同一口径）。 */
    private static final java.util.Set<String> CARD_TYPES = new java.util.HashSet<String>(
            java.util.Arrays.asList("json", "xml", "markdown", "rich", "ark", "card"));

    /**
     * 一条消息的确定性文本标签。
     *
     * @param ev    入站事件（null ⇒ 返回空串）
     * @param facts {@link sair.v4.QqGateway#mediaFacts(Ev)} 对同一条 {@code Ev} 的输出（按段序对齐）；
     *              null / 空 / 短了都不崩，只是字段少一些（段里直读的那几个仍在）
     * @return 标签串（没有内容段 ⇒ 空串；有内容段 ⇒ 必定非空）
     */
    public static String render(Ev ev, JsonArray facts) {
        List<String> labels = new ArrayList<String>();
        try {
            if (ev != null) {
                List<JsonObject> segs = ev.segments();
                Map<String, Integer> counts = typeCounts(segs);
                Map<String, Integer> seen = new HashMap<String, Integer>();
                int fi = 0;
                for (JsonObject seg : segs) {
                    String type = norm(J.s(seg, "type", ""));
                    if (type.isEmpty() || "text".equals(type)) continue;
                    JsonObject f = factAt(facts, fi);
                    fi++;
                    if (labels.size() >= MAX_LABELS) break;
                    // 序数：只有本条消息里同型段 ≥2 个时才打（单个图片的标签保持干净）
                    Integer c = counts.get(type);
                    Integer k = seen.get(type);
                    int seq = (k == null ? 1 : k.intValue() + 1);
                    seen.put(type, Integer.valueOf(seq));
                    int ord = (c != null && c.intValue() >= 2) ? seq : 0;
                    String l = label(seg, type, f, ord, 0);
                    if (Str.has(l)) labels.add(l);
                }
            }
        } catch (Throwable ignored) {
            // 纯函数不许抛：任何畸形输入就返回已经渲染出来的那部分（最差是空串）
        }
        return join(labels);
    }

    /** 机器产出的文本（识别器 / 子 Agent 写的）→ 带来源与模型的标签前缀；缺任何一段 ⇒ 空串。 */
    public static String machine(String source, String model, String text) {
        String s = san(source, MAX_FIELD_CHARS);
        String m = san(model, MAX_FIELD_CHARS);
        if (s.isEmpty() || m.isEmpty() || Str.blank(text)) return "";
        return String.format(java.util.Locale.ROOT, MACHINE_FMT, s, m) + san(text, MACHINE_TEXT_CHARS);
    }

    // ---------------------------------------------------------------- 格式助手

    /** 字节数的人话：{@code 512B} / {@code 20KB} / {@code 3.4MB}（≤0 或负数 ⇒ 空串）。 */
    public static String sizeText(long bytes) {
        if (bytes <= 0L) return "";
        if (bytes < 1024L) return bytes + "B";
        if (bytes < 1024L * 1024L) return Math.round(bytes / 1024.0) + "KB";
        return String.format(java.util.Locale.ROOT, "%.1fMB", bytes / (1024.0 * 1024.0));
    }

    /** 秒数的人话：{@code 12秒} / {@code 1分12秒}（≤0 ⇒ 空串）。 */
    public static String durText(long sec) {
        if (sec <= 0L) return "";
        if (sec < 60L) return sec + "秒";
        return (sec / 60L) + "分" + (sec % 60L) + "秒";
    }

    // ---------------------------------------------------------------- 逐段型标签

    private static String label(JsonObject seg, String type, JsonObject f, int ord, int depth) {
        JsonObject d = J.sub(seg, "data");
        if ("at".equals(type)) {
            return "[提及" + kv("qq", val(d, f, "qq")) + "]";
        }
        if ("image".equals(type)) {
            return "[图片" + ord(ord) + kv("", base(val(d, f, "file"))) + kv("", sizeField(d, f, "file_size")) + "]";
        }
        if ("record".equals(type) || "video".equals(type) || "file".equals(type)) {
            return mediaLabel(type, d, f, ord);
        }
        if ("face".equals(type) || "mface".equals(type) || "sface".equals(type)) {
            return "[表情" + kv("id", val(d, f, "id")) + kv("emoji_id", val(d, f, "emoji_id"))
                    + kv("summary", val(d, f, "summary")) + "]";
        }
        if ("reply".equals(type)) {
            return "[引用" + kv("msg_id", val(d, f, "id")) + "]";
        }
        if ("forward".equals(type)) {
            return "[折叠转发" + kv("id", val(d, f, "id")) + " 未展开]";
        }
        if ("node".equals(type)) {
            return nodeLabel(d, f, depth);
        }
        if (CARD_TYPES.contains(type)) {
            return cardLabel(type, f);
        }
        return unknownLabel(type, f);
    }

    /** 语音 / 视频 / 文件：同一套字段，词不同；语音的内联二进制单独一档。 */
    private static String mediaLabel(String type, JsonObject d, JsonObject f, int ord) {
        String word = "record".equals(type) ? "语音" : ("video".equals(type) ? "视频" : "文件");
        String len = val(d, f, "data_len");
        if ("record".equals(type) && Str.has(len)) {
            // 内联音频（NapCat 给不出文件时把 base64 塞在 record.data 里）：只打长度，正文绝不进标签
            return "[内联音频" + kv("", base(val(d, f, "file"))) + kv("data_len", len) + "]";
        }
        String name = "file".equals(type) ? val(d, f, "name") : "";
        if (Str.blank(name)) name = base(val(d, f, "file"));
        StringBuilder sb = new StringBuilder("[").append(word).append(ord(ord));
        sb.append(kv("", name));
        if ("record".equals(type) || "video".equals(type)) {
            sb.append(kv("", durField(d, f, "duration")));
        }
        sb.append(kv("", sizeField(d, f, "file_size")));
        return sb.append(']').toString();
    }

    /** 嵌套转发节点：{@code [转发节点 张三(10001): 内容]}（内容是段数组时递归渲染再去方括号）。 */
    private static String nodeLabel(JsonObject d, JsonObject f, int depth) {
        String who = val(d, f, "nickname");
        if (Str.blank(who)) who = val(d, f, "user_id");
        String uid = val(d, f, "user_id");
        String head = san(who, MAX_FIELD_CHARS);
        if (Str.has(head) && Str.has(uid) && !head.equals(uid)) head = head + "(" + san(uid, 24) + ")";
        String content = innerText(d == null ? null : d.get("content"), depth);
        StringBuilder sb = new StringBuilder("[转发节点");
        if (Str.has(head)) sb.append(' ').append(head);
        if (Str.has(content)) sb.append(": ").append(san(content, MAX_FIELD_CHARS * 2));
        return sb.append(']').toString();
    }

    /**
     * 嵌套内容：字符串原样；段数组则把每个子段渲染成标签后<b>去掉方括号</b>再拼
     * （这样整串里只有外层那一对方括号 —— 不嵌套、一个段一个标签）。
     */
    private static String innerText(JsonElement content, int depth) {
        if (content == null || content.isJsonNull() || depth > NODE_DEPTH_MAX) return "";
        if (content.isJsonPrimitive()) {
            try {
                return content.getAsString();
            } catch (Throwable t) {
                return "";
            }
        }
        if (!content.isJsonArray()) return "";
        List<String> parts = new ArrayList<String>();
        for (JsonElement e : content.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) continue;
            if (parts.size() >= NODE_INNER_MAX) break;
            JsonObject sub = e.getAsJsonObject();
            String st = norm(J.s(sub, "type", ""));
            if (st.isEmpty()) continue;
            if ("text".equals(st)) {
                parts.add(J.s(J.sub(sub, "data"), "text", ""));
                continue;
            }
            String l = label(sub, st, null, 0, depth + 1);
            parts.add(Str.nz(l).replace('[', ' ').replace(']', ' ').trim());
        }
        return Str.join(parts, " ");
    }

    /** 卡片：按 {@code card_state} 分档（缺该键 ⇒ 确定性降级，不崩）。 */
    private static String cardLabel(String type, JsonObject f) {
        String state = f == null ? "" : J.s(f, "card_state", "");
        StringBuilder sb = new StringBuilder("[卡片 type=").append(san(type, MAX_FIELD_CHARS));
        if ("empty".equals(state)) {
            return sb.append(" 无可用字段]").toString();
        }
        if ("bad".equals(state)) {
            return sb.append(" 载荷无法解析]").toString();
        }
        if ("cut".equals(state)) {
            return sb.append(kv("data_len", J.s(f, "data_len", ""))).append(" 未展开]").toString();
        }
        sb.append(kv("bizsrc", J.s(f, "card_bizsrc", "")));
        sb.append(kv("app", J.s(f, "card_app", "")));
        sb.append(kv("prompt", J.s(f, "card_prompt", "")));
        sb.append(kv("title", J.s(f, "card_title", "")));
        sb.append(kv("desc", J.s(f, "card_desc", "")));
        sb.append(kv("url", firstOf(f, "card_url", "card_jump_url", "card_qqdocurl", "card_pc_jump_url")));
        sb.append(kv("text", J.s(f, "card_text", "")));
        if (state.isEmpty()) {
            // M1 还没落地的形状（没有 card_state）：只留长度 —— 确定性降级，不崩、不编
            sb.append(kv("data_len", J.s(f, "data_len", "")));
        }
        return sb.append(']').toString();
    }

    /** 未知段（含将来新增的段型）：{@code [未知段 type=… k=v…]} —— 保证"有内容段就非空"。 */
    private static String unknownLabel(String type, JsonObject f) {
        StringBuilder sb = new StringBuilder("[未知段 type=").append(san(type, MAX_FIELD_CHARS));
        int n = 0;
        if (f != null) {
            for (Map.Entry<String, JsonElement> e : f.entrySet()) {
                if (n >= UNKNOWN_FIELDS_MAX) break;
                String k = e.getKey();
                if (Str.blank(k) || "type".equals(k)) continue;
                JsonElement v = e.getValue();
                if (v == null || v.isJsonNull() || !v.isJsonPrimitive()) continue;
                String s = san(J.s(f, k, ""), UNKNOWN_VALUE_CHARS);
                if (s.isEmpty()) continue;
                sb.append(kv(k, s));
                n++;
            }
        }
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------- 取值卫生 / 拼接

    /**
     * 值卫生（硬规定）：压单行 ⇒ 方括号换圆括号（第三方文本不可能伪造出 {@code [图片]} 这种基板标签
     * 形状）⇒ {@code ·} 换成 {@code -}（{@code ·} 是"机器写的"标记）⇒ 截断。
     *
     * <p><b>同包共用（N1）</b>：{@link NoticeRender} 的值卫生<b>就调这一份</b>（包内可见，故意
     * <b>不</b>开 public —— 对外的产法仍然只有 {@link #render} 一个入口）。于是"基板标签里永远没有
     * {@code ·}、第三方字符串永远伪造不出方括号标签"这条判据在两个渲染器上<b>构造性</b>一致，
     * 而不是两边各写一遍规则再靠人去对齐。</p>
     */
    static String san(String v, int max) {
        String s = Str.oneLine(v);
        if (s.isEmpty()) return "";
        s = s.replace('[', '(').replace(']', ')').replace('·', '-');
        return Str.cut(s, max > 0 ? max : MAX_FIELD_CHARS);
    }

    /** {@code k=v}（值空则整段不出现；{@code k} 空 = 只打值，用于文件名这种裸值）。 */
    private static String kv(String k, String v) {
        String s = san(v, MAX_FIELD_CHARS);
        if (s.isEmpty()) return "";
        return Str.blank(k) ? (" " + s) : (" " + k + "=" + s);
    }

    /** 序数：只有本条消息里同型段 ≥2 个时才打（单个图片的标签保持干净）。 */
    private static String ord(int n) {
        return n <= 0 ? "" : (" #" + n);
    }

    /** 本条消息里每种段型各几个（决定要不要打 {@code #N} 序数）。 */
    private static Map<String, Integer> typeCounts(List<JsonObject> segs) {
        Map<String, Integer> m = new HashMap<String, Integer>();
        if (segs == null) return m;
        for (JsonObject s : segs) {
            String t = norm(J.s(s, "type", ""));
            if (t.isEmpty() || "text".equals(t)) continue;
            Integer c = m.get(t);
            m.put(t, Integer.valueOf(c == null ? 1 : c.intValue() + 1));
        }
        return m;
    }


    private static String val(JsonObject d, JsonObject f, String key) {
        String v = d == null ? "" : J.s(d, key, "");
        if (Str.has(v)) return v;
        return f == null ? "" : J.s(f, key, "");
    }

    private static String sizeField(JsonObject d, JsonObject f, String key) {
        String raw = val(d, f, key);
        if (Str.blank(raw)) return "";
        try {
            return sizeText(Long.parseLong(raw.trim()));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String durField(JsonObject d, JsonObject f, String key) {
        String raw = val(d, f, key);
        if (Str.blank(raw)) return "";
        try {
            return durText(Long.parseLong(raw.trim()));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String firstOf(JsonObject f, String... keys) {
        if (f == null) return "";
        for (String k : keys) {
            String v = J.s(f, k, "");
            if (Str.has(v)) return v;
        }
        return "";
    }

    private static String base(String p) {
        String s = Str.oneLine(p).replace('\\', '/');
        int i = s.lastIndexOf('/');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    private static String norm(String s) { return Str.lower(Str.trim(s)); }

    private static JsonObject factAt(JsonArray facts, int i) {
        if (facts == null || i < 0 || i >= facts.size()) return null;
        JsonElement e = facts.get(i);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    /** 标签按段序用单空格拼；超 {@link #MAX_CHARS} 就在标签边界截断并补 {@code …+<剩余字符数>}。 */
    private static String join(List<String> labels) {
        if (labels == null || labels.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String l : labels) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(l);
        }
        if (sb.length() <= MAX_CHARS) return sb.toString();
        int n = labels.size();
        for (int k = n - 1; k >= 0; k--) {
            StringBuilder head = new StringBuilder();
            for (int i = 0; i < k; i++) {
                if (head.length() > 0) head.append(' ');
                head.append(labels.get(i));
            }
            int rest = 0;
            for (int i = k; i < n; i++) rest += (i > k ? 1 : 0) + labels.get(i).length();
            String cand = (head.length() == 0 ? "" : head + " ") + "…+" + rest;
            if (cand.length() <= MAX_CHARS) return cand;
        }
        return Str.cut(labels.get(0), MAX_CHARS);
    }
}
