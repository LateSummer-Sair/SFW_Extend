package sair.v4.qq;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sair.v4.kit.J;

/**
 * OneBot v11 事件模型（只读视图）。
 *
 * <p>不做任何状态：{@link #parse(JsonObject)} 把原始事件包一层，字段按需读取，
 * 取值一律走 {@link J} 的强类型读取（避免 {@code String.valueOf} 带引号的坑）。</p>
 *
 * <p>{@code self_id} 每个事件都带，因此 {@link #atSelf()} 不需要外部注入自身 QQ 号；
 * 若上游事件缺 {@code self_id}，则 {@code atSelf()} 恒为 false。</p>
 *
 * <p>{@code message} 既支持 OneBot v11 的<b>消息段数组</b>，也支持 <b>CQ 码字符串</b>
 * （{@code message_format=string} 的部署），后者会被就地解析成同样的段结构。</p>
 */
public final class Ev {

    private final JsonObject raw;
    private final List<JsonObject> segs;

    private Ev(JsonObject raw) {
        this.raw = raw == null ? new JsonObject() : raw;
        this.segs = Collections.unmodifiableList(readSegs(this.raw));
    }

    /**
     * 解析事件。
     *
     * @param raw 原始事件；null 时返回一个空事件（{@link #raw()} 为空对象，各取值返回 0/""），
     *            调用方不必判空。
     */
    public static Ev parse(JsonObject raw) {
        return new Ev(raw);
    }

    /** 原始事件对象（永不为 null）。 */
    public JsonObject raw() { return raw; }

    /** 空事件（无 post_type）。 */
    public boolean isEmpty() { return raw.size() == 0; }

    // ---------------- 事件分类 ----------------

    /** message / message_sent / notice / request / meta_event。 */
    public String postType() { return J.s(raw, "post_type", ""); }

    /** group / private，其它为空串。 */
    public String messageType() { return J.s(raw, "message_type", ""); }

    /**
     * 子类型：群消息 {@code normal|anonymous|notice}；私聊 {@code friend|group|other}；
     * 请求 {@code add|invite}；没有则空串。
     */
    public String subType() { return J.s(raw, "sub_type", ""); }

    public String noticeType() { return J.s(raw, "notice_type", ""); }

    public String requestType() { return J.s(raw, "request_type", ""); }

    /** 元事件类型：{@code heartbeat|lifecycle}。 */
    public String metaEventType() { return J.s(raw, "meta_event_type", ""); }

    public boolean isMessage() { return "message".equals(postType()); }

    public boolean isMessageSent() { return "message_sent".equals(postType()); }

    public boolean isNotice() { return "notice".equals(postType()); }

    public boolean isRequest() { return "request".equals(postType()); }

    public boolean isMeta() { return "meta_event".equals(postType()); }

    public boolean isHeartbeat() {
        return "meta_event".equals(postType()) && "heartbeat".equals(metaEventType());
    }

    /** 事件时间戳（秒）。 */
    public long time() { return J.l(raw, "time", 0L); }

    // ---------------- 身份 ----------------

    public long selfId() { return J.l(raw, "self_id", 0L); }

    public long userId() { return J.l(raw, "user_id", 0L); }

    public long groupId() { return J.l(raw, "group_id", 0L); }

    public long messageId() { return J.l(raw, "message_id", 0L); }

    /**
     * {@code target_id}（message_sent 事件的目标）；缺该字段时回退到群号，再回退到 QQ 号。
     */
    public long targetId() {
        long t = J.l(raw, "target_id", 0L);
        if (t > 0) return t;
        long g = groupId();
        return g > 0 ? g : userId();
    }

    /** 操作者（notice/message_sent 场景）：{@code operator_id} 优先，其次 {@code user_id}。 */
    public long operatorId() {
        long o = J.l(raw, "operator_id", 0L);
        return o > 0 ? o : userId();
    }

    public boolean isGroup() {
        String mt = messageType();
        if (!mt.isEmpty()) return "group".equals(mt);
        return groupId() > 0;
    }

    public boolean isPrivate() {
        String mt = messageType();
        if (!mt.isEmpty()) return "private".equals(mt);
        return groupId() == 0 && userId() > 0;
    }

    // ---------------- 消息内容 ----------------

    /** {@code raw_message}（CQ 码字符串，NapCat 原样给出）。 */
    public String rawMessage() { return J.s(raw, "raw_message", ""); }

    /** 消息段（不可变列表）；CQ 码字符串会被解析成段。 */
    public List<JsonObject> segments() { return segs; }

    /**
     * 纯文本：拼接 {@code type=text} 段的 {@code data.text}；
     * 没有任何文本段时回退到 {@code raw_message} 去掉 CQ 码。
     */
    public String plainText() {
        StringBuilder sb = new StringBuilder();
        for (JsonObject s : segs) {
            if (!"text".equals(J.s(s, "type", ""))) continue;
            JsonObject d = J.sub(s, "data");
            if (d != null) sb.append(J.s(d, "text", ""));
        }
        if (sb.length() > 0) return sb.toString();
        return stripCq(rawMessage());
    }

    /**
     * 取某种媒体段的地址：{@code data.url} 优先，其次 {@code data.file}。
     *
     * @param type {@code image} / {@code record} / {@code file} / {@code video} …
     */
    public List<String> mediaUrls(String type) {
        List<String> out = new ArrayList<String>();
        if (type == null || type.isEmpty()) return out;
        for (JsonObject s : segs) {
            if (!type.equalsIgnoreCase(J.s(s, "type", ""))) continue;
            JsonObject d = J.sub(s, "data");
            if (d == null) continue;
            String u = J.s(d, "url", "").trim();
            if (u.isEmpty()) u = J.s(d, "file", "").trim();
            if (!u.isEmpty()) out.add(u);
        }
        return out;
    }

    /** 是否 @ 了指定 QQ 号（段优先，其次 raw_message 里的 CQ 码）。 */
    public boolean isAt(long qq) {
        if (qq <= 0) return false;
        String q = String.valueOf(qq);
        for (JsonObject s : segs) {
            if (!"at".equals(J.s(s, "type", ""))) continue;
            JsonObject d = J.sub(s, "data");
            if (d != null && q.equals(J.s(d, "qq", "").trim())) return true;
        }
        String rm = rawMessage();
        return rm.contains("[CQ:at,qq=" + q + "]") || rm.contains("[CQ:at,qq=" + q + ",");
    }

    /** 是否 @ 了本机（用事件里的 {@code self_id}）。 */
    public boolean atSelf() { return isAt(selfId()); }

    // ---------------- 发送者 ----------------

    /** {@code sender.card} 优先，其次 {@code sender.nickname}，其次 QQ 号。 */
    public String senderName() {
        JsonObject s = J.sub(raw, "sender");
        if (s != null) {
            String card = J.s(s, "card", "").trim();
            if (!card.isEmpty()) return card;
            String nick = J.s(s, "nickname", "").trim();
            if (!nick.isEmpty()) return nick;
        }
        long u = userId();
        return u > 0 ? String.valueOf(u) : "";
    }

    /** {@code sender.role}：owner / admin / member；没有则空串。 */
    public String senderRole() { return J.s(J.sub(raw, "sender"), "role", "").trim(); }

    /** 匿名消息的显示名（{@code anonymous.name}）；非匿名空串。 */
    public String anonymousName() { return J.s(J.sub(raw, "anonymous"), "name", "").trim(); }

    // ---------------- 请求事件 ----------------

    /** 请求事件的 flag（用于 set_friend_add_request / set_group_add_request）。 */
    public String flag() { return J.s(raw, "flag", ""); }

    /** 请求事件的验证信息。 */
    public String comment() { return J.s(raw, "comment", ""); }

    // ---------------- 内部 ----------------

    /** 读取消息段：数组直接用；字符串按 CQ 码解析。 */
    private static List<JsonObject> readSegs(JsonObject raw) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        JsonElement m = J.get(raw, "message");
        if (m == null) return out;
        if (m.isJsonArray()) {
            for (JsonElement e : m.getAsJsonArray()) {
                if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
            }
            return out;
        }
        if (m.isJsonPrimitive()) {
            String s = m.getAsString();
            if (s != null && !s.trim().isEmpty()) out.addAll(cq(s));
        }
        return out;
    }

    /** 解析 CQ 码字符串为消息段（{@code [CQ:type,k=v,...]}）。 */
    private static List<JsonObject> cq(String text) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        int i = 0;
        while (i < text.length()) {
            int s = text.indexOf("[CQ:", i);
            if (s < 0) {
                addText(out, text.substring(i));
                break;
            }
            if (s > i) addText(out, text.substring(i, s));
            int e = text.indexOf(']', s);
            if (e < 0) {
                addText(out, text.substring(s));
                break;
            }
            String body = text.substring(s + 4, e);
            int comma = body.indexOf(',');
            String type = comma < 0 ? body : body.substring(0, comma);
            JsonObject data = new JsonObject();
            if (comma >= 0) {
                for (String kv : body.substring(comma + 1).split(",")) {
                    if (kv.isEmpty()) continue;
                    int eq = kv.indexOf('=');
                    if (eq < 0) data.addProperty(kv, "");
                    else data.addProperty(kv.substring(0, eq), unescape(kv.substring(eq + 1)));
                }
            }
            if (type != null && !type.isEmpty()) out.add(J.obj("type", type, "data", data));
            i = e + 1;
        }
        return out;
    }

    private static void addText(List<JsonObject> out, String t) {
        if (t == null || t.isEmpty()) return;
        out.add(J.obj("type", "text", "data", J.obj("text", unescape(t))));
    }

    /** 去 CQ 码（保留普通文本），并还原 CQ 转义。 */
    private static String stripCq(String s) {
        if (s == null) return "";
        return unescape(s.replaceAll("\\[CQ:[^\\]]*\\]", ""));
    }

    private static String unescape(String s) {
        if (s == null) return "";
        return s.replace("&#44;", ",").replace("&#91;", "[").replace("&#93;", "]").replace("&amp;", "&");
    }
}
