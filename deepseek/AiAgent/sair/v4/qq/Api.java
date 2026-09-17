package sair.v4.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import sair.v4.Conf;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * OneBot v11 动作目录 + 类型化封装（设计基线能力⑧的"动作面"）。
 *
 * <p>统一返回口径：所有方法都返回同一个 <b>JsonObject 形状</b>：</p>
 * <ul>
 *   <li>成功：NapCat 原始响应对象（{@code status}/{@code retcode}/{@code data}/{@code echo}）；</li>
 *   <li>未连接：{@code {"error":"Napcat 没有连接"}}（NapCat 未连接时基板的统一话术）；</li>
 *   <li>其它失败：{@code {"error":"…"}} —— 无响应/超时、响应不是 JSON、以及
 *       {@code status=failed} 或 {@code retcode≠0}（此时原字段仍在，只是补一个 error 便于统一判错）。</li>
 * </ul>
 * <p>判错统一用 {@link #ok(JsonObject)} / {@link #error(JsonObject)}。</p>
 *
 * <p>参数名一律按 OneBot v11（{@code group_id}/{@code user_id}/{@code message}/{@code duration}…）。
 * 图片/语音用消息段数组（{@code [{"type":"image","data":{"file":".."}}]}），文件上传用
 * {@code upload_group_file}/{@code upload_private_file}。本地绝对路径的写法由 {@link Relay} 统一裁定：
 * <b>中转在跑 → 外链 URL（NapCat 在别的机器上也取得到）；没跑 → {@code file:///C:/x}（同机口径）</b>。
 * 调用方不需要知道 NapCat 在哪台机器上。</p>
 *
 * <h3>强制层（唯一漏斗，技能绕不过去）</h3>
 * <p>{@link #call(String, JsonObject)} 是所有动作的<b>唯一出口</b>，它在把参数交给 NapCat 之前
 * 统一改写一遍"要发出去的本地文件"（{@link #rewrite(String, JsonObject)}）：</p>
 * <ul>
 *   <li>{@code upload_group_file} / {@code upload_private_file} 的 {@code file}；</li>
 *   <li>{@code send_group_msg} / {@code send_private_msg} / {@code send_msg} 的 {@code message} ——
 *       消息段数组里的 {@code data.file}、以及 CQ 串里的 {@code file=}。</li>
 * </ul>
 * <p>只要值长得像本地路径（{@code C:\x}、{@code C:/x}、{@code file:///C:/x}），就换成中转 URL；
 * 中转没开（或路径不是普通文件）则保留原值，并 warn 一句"跨机 NapCat 读不到本地路径"。
 * 因此<b>技能只要把本地绝对路径交给 {@code h.napcat()} 就是合规的</b>，不需要自己拼 URL、也不需要知道中转。</p>
 */
public final class Api {

    /** NapCat 未连接时的统一返回（设计基线⑧）。 */
    public static final String NOT_CONNECTED = "Napcat 没有连接";

    /** 带"文件"参数的参数名（任何动作都管）：主形态是 {@code file}，另有 NapCat 扩展用 {@code image}。 */
    private static final String[] FILE_KEYS = {"file", "image"};
    /** 消息类动作（{@code params.message}：消息段数组或 CQ 串）。 */
    private static final String[] SEND_ACTIONS = {"send_group_msg", "send_private_msg", "send_msg"};

    private final Link link;
    private final Conf conf;

    /** 文件外链中转（可空：没装配时本地路径一律走 file:/// 回退）。 */
    private volatile Relay relay;

    /** 控制台日志去向（可空；只用于"本地文件没经中转"这类告警）。 */
    private volatile Out out;

    /** 动作闸门（由基板装配时注入：技能/模型发起的动作要过权限复核）。 */
    public interface Guard {
        /** 返回非空 = 拒绝（该文案直接作为 error 返回）；null = 放行。 */
        String deny(String action, JsonObject params);
    }

    private volatile Guard guard;

    /**
     * <b>出站消息台账注入口</b>（F5a）：{@link #call} 成功发出一条消息时，把这次拿到的
     * {@code data.message_id} 交给基板记下来 —— 撤回只能按 {@code message_id} 定位，
     * 而这个 id 以前是用完就丢的。
     *
     * <p><b>为什么是函数式接口而不是直接注入 {@code Store}</b>：① 与 {@link Guard} 同一先例；
     * ② {@code qq} 这层不需要知道"台账存在 SQL 里"（不引入 {@code qq → store} 的包依赖）；
     * ③ 探针可以直接装一个假 tap 断言"记了什么"，不必碰库。</p>
     *
     * <p><b>它是 fire-and-forget 的</b>：返回 {@code void}，由 {@link #call} 在 try/catch 里调 ——
     * 台账记录因此<b>不可能</b>改变、吞掉或拖住动作本身的返回值。</p>
     */
    public interface SentTap {
        /**
         * @param session   会话键（{@code group:<群号>} / {@code user:<QQ>}；推不出时为空串）
         * @param action    真实动作名（{@code send_group_msg} / {@code send_private_msg} / {@code send_msg}）
         * @param messageId 本次真发出去的消息 id（{@code > 0}）
         * @param preview   出站正文的纯文本预览（前 ~60 字；抽不出为空串）
         * @param params    本次发送的<b>完整参数</b>（{@link #call} 里 {@code rewrite} <b>之后</b>的副本，
         *                  因此本地路径已经换成了中转 URL）—— P0-1 起交给台账侧，
         *                  好让她自己发的那条也能渲染成一行标记行进她自己的记账。
         *                  <p>{@code rewrite} 之后的副本是<b>故意</b>的：记账要记的是"真发出去的那个东西"，
         *                  不是"她本来想发的本地路径"。唯一例外见 {@code Relay}（换 URL 只改这一个键）。</p>
         */
        void sent(String session, String action, long messageId, String preview, JsonObject params);
    }

    private volatile SentTap sentTap;

    /** 权限账本（ACL）：改写层在把本地文件交出去之前，按它判"能不能读这个文件"（洞 2 的第二个资源判定）。 */
    private volatile sair.v4.auth.Auth auth;

    public Api(Link link, Conf conf) {
        this.link = link;
        this.conf = conf;
    }

    /** 装闸门；{@code null} 表示不检查（基板自身的回复/定时推送走不装闸门的实例）。 */
    public void setGuard(Guard g) { this.guard = g; }

    public Guard guard() { return guard; }

    /** 装权限账本（{@code Boot} 装配时注入）；改写层据此判"读本地文件"的 R 位。 */
    public void setAuth(sair.v4.auth.Auth a) { this.auth = a; }

    /** 装文件外链中转（{@code Boot} 装配时注入）。 */
    public void setRelay(Relay r) { this.relay = r; }

    public void setOut(Out o) { this.out = o; }

    /** 装出站消息台账（{@code Boot} 装配时注入）；{@code null} = 不记台账（发送行为一字不变）。 */
    public void setSentTap(SentTap t) { this.sentTap = t; }

    public SentTap sentTap() { return sentTap; }

    public Relay relay() { return relay; }

    public Link link() { return link; }

    public Conf conf() { return conf; }

    /** 连接可用性（= link.connected()）。 */
    public boolean available() { return link != null && link.connected(); }

    // ---------------- 调用入口 ----------------

    /**
     * 调一个动作并返回解析后的响应对象。
     *
     * @param action OneBot v11 动作名，见 {@link #catalog()}
     * @param params 动作参数，可为 null（按空对象处理）
     */
    public JsonObject call(String action, JsonObject params) {
        if (action == null || action.trim().isEmpty()) return error("动作名为空");
        // 强制层：所有本地文件都在这里被换成中转 URL（技能与模型都绕不过这一层）。
        // 洞 2 根治：一个动作碰多个资源要逐个判定 —— 发文件 = E 类 X（闸门判）+ A/B 类 R（读那个文件，这里判）。
        // 改写层对每个"要交出去的本地文件"先判 R 位，拒绝就把拒绝原文作为整条动作的结果返回，绝不静默跳过。
        Deny fileDeny = new Deny();
        JsonObject p = rewrite(action, params == null ? new JsonObject() : params, fileDeny);
        if (fileDeny.text != null) return error(fileDeny.text);
        Guard g = guard;
        if (g != null) {
            String deny;
            try {
                deny = g.deny(action, p);
            } catch (Throwable t) {
                deny = "[auth] 动作闸门异常：" + t;
            }
            if (deny != null && !deny.trim().isEmpty()) return error(deny);
        }
        if (!available()) return error(NOT_CONNECTED);
        String text = link.call(action, p);
        if (text == null) return error("Napcat 没有响应（超时或连接已断开）: " + action);
        JsonObject o = J.obj(text);
        if (o == null) return error("Napcat 响应不是 JSON: " + brief(text));
        if (J.get(o, "error") == null) {
            long code = J.l(o, "retcode", 0L);
            String status = J.s(o, "status", "");
            if (code != 0 || "failed".equalsIgnoreCase(status)) {
                String msg = J.s(o, "message", "").trim();
                if (msg.isEmpty()) msg = J.s(o, "wording", "").trim();
                if (msg.isEmpty()) msg = "retcode=" + code;
                o.addProperty("error", msg);
            }
        }
        // 台账（可选）：真发出去的消息留一条底账（F5a）。它排在 return 之前的最后一步，
        // 只读入参、不改 o、不返回值 —— 记录失败绝不影响这条动作的结果（见 recordSent）。
        recordSent(action, p, o);
        return o;
    }

    /** 响应是否成功（没有 error 字段）。 */
    public static boolean ok(JsonObject resp) {
        return resp != null && J.get(resp, "error") == null;
    }

    /**
     * <b>静默调用</b>（M4：引用解析专用）：与 {@link #call} 的判定口径<b>逐条相同</b>
     * （未连接 / 非 JSON / retcode≠0 / status=failed 都补 {@code error}），
     * 但底层走 {@link Link#call(String, JsonObject, boolean) 静默重载} ⇒
     * <b>响应原文不进控制台</b>（{@code get_msg} 的响应前 200 字含被引消息正文）。
     *
     * <p>刻意<b>不做</b>两件事：① 不过 ACL 闸门 —— 这是<b>基板自己的读动作</b>
     * （同"落库"这一类的自主行为；带闸门那份是给技能/模型发起动作用的，
     * {@code Boot.api()} 的注释把这条口径写死了）；② 不走文件改写层 —— {@code get_msg}
     * 的参数里没有任何本地文件（只有 {@code message_id}）。</p>
     */
    public JsonObject callQuiet(String action, JsonObject params) {
        if (action == null || action.trim().isEmpty()) return error("动作名为空");
        if (!available()) return error(NOT_CONNECTED);
        String text = link.call(action, params == null ? new JsonObject() : params, true);
        if (text == null) return error("Napcat 没有响应（超时或连接已断开）: " + action);
        JsonObject o = J.obj(text);
        if (o == null) return error("Napcat 响应不是 JSON: " + brief(text));
        if (J.get(o, "error") == null) {
            long code = J.l(o, "retcode", 0L);
            String status = J.s(o, "status", "");
            if (code != 0 || "failed".equalsIgnoreCase(status)) {
                String msg = J.s(o, "message", "").trim();
                if (msg.isEmpty()) msg = J.s(o, "wording", "").trim();
                if (msg.isEmpty()) msg = "retcode=" + code;
                o.addProperty("error", msg);
            }
        }
        return o;
    }

    /**
     * 静默取一条消息（M4 的 {@code get_msg} 路）。
     *
     * <p>与 {@link #getMsg(long)} 的差别只有一个：<b>响应原文不进控制台</b>。
     * 契约（成功 / {@code data.message=[]} 空正文 / {@code retcode 1200} 已撤回）逐条相同。</p>
     */
    public JsonObject getMsgQuiet(long messageId) {
        return callQuiet("get_msg", J.obj("message_id", messageId));
    }

    /** 响应的错误串；成功返回 ""。 */
    public static String error(JsonObject resp) {
        return resp == null ? "" : J.s(resp, "error", "");
    }

    private static JsonObject error(String msg) {
        return J.obj("error", msg);
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ');
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    // ---------------- 出站消息台账（F5a：为"按范围批量撤回"留的 message_id 底账） ----------------

    /** 台账预览的字数上限（供她/主人辨认"这条是哪句"，不存全文）。 */
    private static final int PREVIEW_CHARS = 60;

    /**
     * 记一条台账（<b>可选、fire-and-forget</b>）：三个条件全满足才记 ——
     * ① 动作是 {@link #SEND_ACTIONS} 之一；② 响应成功（{@link #ok}：没有 error ⇒ status=ok 且 retcode=0）；
     * ③ {@code data.message_id > 0}（没有 id 就没什么可撤的）。
     *
     * <p><b>绝不影响发送</b>：本方法在 {@link #call} 里排在 {@code return} 之前的最后一步，
     * 只读入参、不改响应对象、不返回值；整个体包在 try/catch 里，异常只在控制台打一行 dim。
     * 台账写失败 = 少一条"可撤的消息"，而<b>不是</b>"这条消息没发出去"。</p>
     */
    private void recordSent(String action, JsonObject p, JsonObject o) {
        SentTap tap = sentTap;
        if (tap == null || !isSendAction(action)) return;
        try {
            if (!ok(o)) return;                                   // failed / retcode≠0：没真发出去
            JsonObject data = J.sub(o, "data");
            long mid = data == null ? 0L : J.l(data, "message_id", 0L);
            if (mid <= 0L) return;
            tap.sent(sessionOf(p), action, mid, previewOf(p), p);
        } catch (Throwable t) {
            Out sink = out;
            if (sink != null) sink.dim("[napcat] 出站消息台账没记上（不影响发送）：" + t);
        }
    }

    private static boolean isSendAction(String action) {
        if (action == null) return false;
        for (int i = 0; i < SEND_ACTIONS.length; i++) if (SEND_ACTIONS[i].equals(action)) return true;
        return false;
    }

    /** 会话键：{@code group:<群号>} / {@code user:<QQ>}；两个都没给就空串（台账里仍可按时间找）。 */
    private static String sessionOf(JsonObject p) {
        if (p == null) return "";
        long g = J.l(p, "group_id", 0L);
        if (g > 0L) return "group:" + g;
        long u = J.l(p, "user_id", 0L);
        if (u > 0L) return "user:" + u;
        return "";
    }

    /**
     * 出站正文 → 纯文本预览。两种形态都认：<b>CQ 串</b>（{@code [CQ:image,file=..]你好}）与
     * <b>消息段数组</b>（{@code [{"type":"text","data":{"text":"你好"}}]}）；非文本段折成占位符，
     * 抽不出任何正文就返回空串（<b>不猜、不兜底</b>）。
     */
    private static String previewOf(JsonObject p) {
        if (p == null) return "";
        JsonElement m = p.get("message");
        if (m == null || m.isJsonNull()) return "";
        String s;
        if (m.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : m.getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject seg = e.getAsJsonObject();
                String type = J.s(seg, "type", "");
                JsonObject d = J.sub(seg, "data");
                if ("text".equals(type)) sb.append(d == null ? "" : J.s(d, "text", ""));
                else sb.append(segMark(type));
            }
            s = sb.toString();
        } else if (m.isJsonPrimitive()) {
            s = stripCq(m.getAsString());
        } else {
            return "";
        }
        return preview60(s);
    }

    /** 非文本段的占位符（认不出的段名也方括号包一下，别把信息丢了）。 */
    private static String segMark(String type) {
        if ("image".equals(type)) return "[图片]";
        if ("face".equals(type)) return "[表情]";
        if ("at".equals(type)) return "[@]";
        if ("record".equals(type)) return "[语音]";
        if ("video".equals(type)) return "[视频]";
        if ("file".equals(type)) return "[文件]";
        if ("reply".equals(type)) return "[回复]";
        return Str.blank(type) ? "" : ("[" + type + "]");
    }

    /** CQ 串 → 纯文本（去掉 {@code [CQ:..]} 码，非文本码折成占位符；码没闭合就到此为止，不往里猜）。 */
    private static String stripCq(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf("[CQ:", i);
            if (a < 0) {
                sb.append(s.substring(i));
                break;
            }
            sb.append(s, i, a);
            int b = s.indexOf(']', a);
            if (b < 0) break;
            String code = s.substring(a + 4, b);
            int eq = code.indexOf(',');
            sb.append(segMark(eq < 0 ? code : code.substring(0, eq)));
            i = b + 1;
        }
        return unescape(sb.toString());
    }

    /** CQ 实体反转义（只做最常见几个；预览是给人看的，不做完整 HTML 解码）。 */
    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#91;", "[").replace("&#93;", "]").replace("&#44;", ",");
    }

    /** 压平空白 + 截到 {@link #PREVIEW_CHARS} 字（不切断代理对，避免半个字符落库）。 */
    private static String preview60(String s) {
        String t = Str.nz(s).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        StringBuilder sb = new StringBuilder();
        boolean sp = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ') {
                if (!sp && sb.length() > 0) sb.append(' ');
                sp = true;
            } else {
                sb.append(c);
                sp = false;
            }
        }
        String u = sb.toString().trim();
        if (u.length() <= PREVIEW_CHARS) return u;
        int cut = PREVIEW_CHARS;
        if (Character.isHighSurrogate(u.charAt(cut - 1))) cut--;
        return u.substring(0, cut) + "…";
    }

    // ---------------- 强制层：本地文件一律经中转变成本地可取的写法 ----------------

    /**
     * 判定"当前主体能不能读这个本地文件路径"（洞 2 的第二个资源判定）。
     *
     * <p>主体 = {@code Ctx.caller()}，取不到 → {@code Caller.systemActor(masterQQ)}（她自己的自主行为）。
     * 放行返回 {@code null}；拒绝返回可直接回给调用方的原文（绝不静默）。</p>
     */
    private String needPath(String path) {
        sair.v4.auth.Caller c = sair.v4.ctx.Ctx.caller();
        if (c == null) c = sair.v4.auth.Caller.systemActor(conf == null ? 0L : conf.masterQQ());
        sair.v4.auth.Auth a = auth;
        if (a == null) {
            // 装配失败：fail-closed，绝不静默放权
            return sair.v4.auth.Acl.DENY_PREFIX + "需要 " + sair.v4.auth.Res.normPath(path)
                    + " 的 R 位（权限面没有装配，按拒绝处理）";
        }
        return a.allowRes(c, sair.v4.auth.Res.path(path), 'R');
    }

    /** 改写层判出的拒绝（局部传递；{@link #call} 把它作为整条动作的结果返回）。 */
    private static final class Deny {
        String text;
    }

    /**
     * 改写这条动作里的本地文件参数（唯一漏斗）。
     *
     * <p>覆盖三类形态（其余参数一律不动）：</p>
     * <ol>
     *   <li>{@code send_group_msg} / {@code send_private_msg} / {@code send_msg} 的 {@code message} ——
     *       消息段数组里的 {@code data.file}（image / record / video / file…）与 CQ 串里的 {@code file=}；</li>
     *   <li><b>任何动作</b>的 {@code file} 参数（上传类 {@code upload_*_file} 的主形态，
     *       也顺手管住别的动作里同名的文件参数）；</li>
     *   <li>{@code image} 参数（NapCat 扩展 {@code _send_group_notice} 的公告图片、{@code ocr_image} 等）。</li>
     * </ol>
     * <p>值不像本地路径（http(s) / base64:// / fileid / 相对路径）就原样留着。参数是<b>副本</b>，
     * 改它不影响调用方手里的对象。</p>
     */
    private JsonObject rewrite(String action, JsonObject params, Deny deny) {
        JsonObject p = params == null ? new JsonObject() : params.deepCopy();
        String a = Str.lower(Str.trim(action));
        if (a.isEmpty()) return p;
        for (String s : SEND_ACTIONS) {
            if (s.equals(a)) {
                rewriteMessage(a, p, deny);
                break;
            }
        }
        for (String k : FILE_KEYS) rewriteKey(a, p, k, deny);
        return p;
    }

    /** 顶层的一个文件形态参数（{@code file} / {@code image}）：像本地路径就换成中转 URL。 */    private void rewriteKey(String action, JsonObject params, String key, Deny deny) {
        String v = J.s(params, key, "");
        if (!Str.has(v)) return;
        String nv = rewriteLocalFile(action, v.trim(), deny);
        if (!nv.equals(v)) params.addProperty(key, nv);
    }

    /** {@code params.message}：消息段数组里的 {@code data.file} 与 CQ 串里的 {@code file=} 都改写。 */
    private void rewriteMessage(String action, JsonObject params, Deny deny) {
        JsonElement el = J.get(params, "message");
        if (el == null) return;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject data = J.sub(e.getAsJsonObject(), "data");
                if (data == null) continue;
                String f = J.s(data, "file", "");
                if (!Str.has(f)) continue;
                String nv = rewriteLocalFile(action, f.trim(), deny);
                if (!nv.equals(f)) data.addProperty("file", nv);
            }
            return;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString();
            String nv = rewriteCq(action, s, deny);
            if (!nv.equals(s)) params.addProperty("message", nv);
        }
    }

    /** CQ 串：逐个 {@code [CQ:…]} 块改写 {@code file=} 的值，块外的文字原样保留。 */
    private String rewriteCq(String action, String s, Deny deny) {
        if (Str.blank(s) || s.indexOf("[CQ:") < 0) return s;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int p = s.indexOf("[CQ:", i);
            if (p < 0) {
                out.append(s.substring(i));
                break;
            }
            int q = s.indexOf(']', p);
            if (q < 0) {
                out.append(s.substring(i));
                break;
            }
            out.append(s, i, p);
            out.append(rewriteCqBlock(action, s.substring(p, q + 1), deny));
            i = q + 1;
        }
        return out.toString();
    }

    /** 一个 {@code [CQ:type,file=…]} 块：只动 {@code file=} 的值（到下一个 {@code ,} 或 {@code ]} 为止）。 */
    private String rewriteCqBlock(String action, String block, Deny deny) {
        StringBuilder sb = new StringBuilder(block);
        int from = 0;
        while (true) {
            int p = sb.indexOf("file=", from);
            if (p < 0) break;
            int e = p + 5;
            while (e < sb.length() && sb.charAt(e) != ',' && sb.charAt(e) != ']') e++;
            String v = sb.substring(p + 5, e);
            String nv = rewriteLocalFile(action, v, deny);
            if (nv.equals(v)) {
                from = e;
            } else {
                sb.replace(p + 5, e, nv);
                from = p + 5 + nv.length();
            }
        }
        return sb.toString();
    }

    /**
     * 一个 file 值：像本地路径就换成中转 URL；中转没开保留原值并告警。
     * <p>不像本地路径的值（http(s) 链接、{@code base64://}、NapCat fileid、相对路径）一律不动。</p>
     */
    private String rewriteLocalFile(String action, String value, Deny deny) {
        String path = localPathOf(value);
        if (path == null) return value;
        // 洞 2：把本地路径改写成可被 NapCat 取到的写法之前，先判"当前主体能不能读这个文件"。
        // 拒绝 → 不产出 URL，把拒绝原文交给 call() 作为动作结果返回（绝不静默跳过）。
        String pd = needPath(path);
        if (pd != null) {
            if (deny.text == null) deny.text = pd;
            return value;
        }
        Relay r = relay;
        if (r == null || !r.running()) {
            warn("[relay] " + action + " 要发的本地文件没经中转（relayEnabled=false 或中转没起来）："
                    + Str.cut(value, 160) + " —— 跨机 NapCat 读不到本地路径（同机部署不受影响）");
            return value;
        }
        String u = r.urlFor(path);
        if (u == null) return value;      // 形态不合法（不该发生）：原样交给 NapCat
        return u;
    }

    /**
     * 本地路径形态判定：{@code C:\x}、{@code C:/x}、{@code /x}、{@code file:///C:/x}。
     * 其余（URL / base64 / fileid / 相对路径）返回 null = 不是本地文件，不改写。
     */
    private static String localPathOf(String value) {
        String s = Str.trim(value);
        if (s.isEmpty()) return null;
        String low = s.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("base64://")) return null;
        if (low.startsWith("file:")) {
            String rest = s.substring(5);
            while (rest.startsWith("//")) rest = rest.substring(2);
            if (rest.length() > 2 && rest.startsWith("/") && rest.charAt(2) == ':') rest = rest.substring(1);
            if (rest.length() >= 3 && rest.charAt(1) == ':' && (rest.charAt(2) == '/' || rest.charAt(2) == '\\')) {
                return rest;
            }
            return rest.startsWith("/") ? rest : null;      // file://主机名/… 一类不管
        }
        if (s.length() >= 3 && s.charAt(1) == ':' && (s.charAt(2) == '\\' || s.charAt(2) == '/')) return s;
        if (s.startsWith("/") || s.startsWith("\\")) return s;
        return null;
    }

    private void warn(String msg) {
        try {
            if (out != null) out.warn(msg);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 消息 ----------------

    public JsonObject sendGroupMsg(long groupId, String message) {
        return call("send_group_msg", J.obj("group_id", groupId, "message", message));
    }

    public JsonObject sendPrivateMsg(long userId, String message) {
        return call("send_private_msg", J.obj("user_id", userId, "message", message));
    }

    public JsonObject sendMsg(boolean group, long id, String message) {
        return group ? sendGroupMsg(id, message) : sendPrivateMsg(id, message);
    }

    public JsonObject sendGroupImage(long groupId, String file) {
        return call("send_group_msg", J.obj("group_id", groupId, "message", seg("image", file)));
    }

    public JsonObject sendPrivateImage(long userId, String file) {
        return call("send_private_msg", J.obj("user_id", userId, "message", seg("image", file)));
    }

    public JsonObject sendGroupRecord(long groupId, String file) {
        return call("send_group_msg", J.obj("group_id", groupId, "message", seg("record", file)));
    }

    public JsonObject sendPrivateRecord(long userId, String file) {
        return call("send_private_msg", J.obj("user_id", userId, "message", seg("record", file)));
    }

    public JsonObject sendGroupFile(long groupId, String file, String name) {
        return call("upload_group_file", J.obj("group_id", groupId, "file", fileParam(file), "name", fileName(file, name)));
    }

    public JsonObject sendPrivateFile(long userId, String file, String name) {
        return call("upload_private_file", J.obj("user_id", userId, "file", fileParam(file), "name", fileName(file, name)));
    }

    // ---------------- 查询 ----------------

    public JsonObject getGroupList() { return call("get_group_list", new JsonObject()); }

    public JsonObject getGroupMemberList(long groupId) {
        return call("get_group_member_list", J.obj("group_id", groupId));
    }

    public JsonObject getGroupInfo(long groupId) {
        return call("get_group_info", J.obj("group_id", groupId));
    }

    public JsonObject getFriendList() { return call("get_friend_list", new JsonObject()); }

    public JsonObject getLoginInfo() { return call("get_login_info", new JsonObject()); }

    public JsonObject getStrangerInfo(long userId) {
        return call("get_stranger_info", J.obj("user_id", userId));
    }

    public JsonObject getGroupMemberInfo(long groupId, long userId) {
        return call("get_group_member_info", J.obj("group_id", groupId, "user_id", userId));
    }

    public JsonObject getMsg(long messageId) {
        return call("get_msg", J.obj("message_id", messageId));
    }

    /** 合并转发内容：{@code message_id} 与 {@code id} 同时给，兼容不同实现取值习惯。 */
    public JsonObject getForwardMsg(String id) {
        return call("get_forward_msg", J.obj("message_id", id, "id", id));
    }

    // ---------------- 群管 ----------------

    public JsonObject setGroupBan(long groupId, long userId, int durationSec) {
        return call("set_group_ban", J.obj("group_id", groupId, "user_id", userId, "duration", durationSec));
    }

    public JsonObject setGroupWholeBan(long groupId, boolean enable) {
        return call("set_group_whole_ban", J.obj("group_id", groupId, "enable", enable));
    }

    public JsonObject setGroupAdmin(long groupId, long userId, boolean enable) {
        return call("set_group_admin", J.obj("group_id", groupId, "user_id", userId, "enable", enable));
    }

    public JsonObject setGroupCard(long groupId, long userId, String card) {
        return call("set_group_card", J.obj("group_id", groupId, "user_id", userId, "card", card == null ? "" : card));
    }

    public JsonObject setGroupName(long groupId, String name) {
        return call("set_group_name", J.obj("group_id", groupId, "group_name", name));
    }

    public JsonObject setGroupLeave(long groupId, boolean dismiss) {
        return call("set_group_leave", J.obj("group_id", groupId, "is_dismiss", dismiss));
    }

    public JsonObject setGroupKick(long groupId, long userId, boolean rejectAdd) {
        return call("set_group_kick", J.obj("group_id", groupId, "user_id", userId, "reject_add_request", rejectAdd));
    }

    // ---------------- 请求 / 好友 ----------------

    public JsonObject setFriendAddRequest(String flag, boolean approve, String remark) {
        return call("set_friend_add_request",
                J.obj("flag", flag, "approve", approve, "remark", remark == null ? "" : remark));
    }

    public JsonObject setGroupAddRequest(String flag, String subType, boolean approve, String reason) {
        return call("set_group_add_request",
                J.obj("flag", flag, "sub_type", subType, "approve", approve, "reason", reason == null ? "" : reason));
    }

    public JsonObject deleteFriend(long userId) {
        return call("delete_friend", J.obj("user_id", userId));
    }

    // ---------------- 消息管理 / 互动 ----------------

    public JsonObject deleteMsg(long messageId) {
        return call("delete_msg", J.obj("message_id", messageId));
    }

    public JsonObject markMsgRead(long messageId) {
        return call("mark_msg_as_read", J.obj("message_id", messageId));
    }

    public JsonObject sendLike(long userId, int times) {
        return call("send_like", J.obj("user_id", userId, "times", times > 0 ? times : 10));
    }

    // ---------------- 系统 ----------------

    public JsonObject getStatus() { return call("get_status", new JsonObject()); }

    public JsonObject getVersionInfo() { return call("get_version_info", new JsonObject()); }

    public JsonObject canSendImage() { return call("can_send_image", new JsonObject()); }

    public JsonObject canSendRecord() { return call("can_send_record", new JsonObject()); }

    // ---------------- 动作目录 ----------------

    /**
     * 动作目录：{@code { "<动作名>": {"desc":"…","params":{"<参数名>":"…"}}, … }}。
     * <p>给模型看的"能力形状"清单；{@code napcat} 工具据此让模型选动作、填参数。</p>
     */
    public JsonObject catalog() {
        JsonObject c = new JsonObject();

        // 消息
        c.add("send_group_msg", act("发送群消息（message 可用 CQ 码字符串或消息段数组）",
                J.obj("group_id", "群号", "message", "消息内容", "auto_escape", "是否不解析 CQ 码（可选）")));
        c.add("send_private_msg", act("发送私聊消息（message 可用 CQ 码字符串或消息段数组）",
                J.obj("user_id", "QQ 号", "message", "消息内容", "auto_escape", "是否不解析 CQ 码（可选）")));
        c.add("send_group_forward_msg", act("发送群合并转发（messages 为 node 数组）",
                J.obj("group_id", "群号", "messages", "转发节点数组 [{type:node,data:{uin,name,content}}]")));
        c.add("send_private_forward_msg", act("发送私聊合并转发（messages 为 node 数组）",
                J.obj("user_id", "QQ 号", "messages", "转发节点数组")));
        c.add("delete_msg", act("撤回消息", J.obj("message_id", "消息 ID")));
        c.add("mark_msg_as_read", act("标记消息已读", J.obj("message_id", "消息 ID")));
        c.add("get_msg", act("获取消息详情", J.obj("message_id", "消息 ID")));
        c.add("get_forward_msg", act("获取合并转发内容", J.obj("message_id", "转发消息 ID", "id", "同上（兼容字段）")));
        c.add("get_group_msg_history", act("拉取群聊历史消息",
                J.obj("group_id", "群号", "message_seq", "起始消息序号（可选）", "count", "条数")));
        c.add("get_friend_msg_history", act("拉取私聊历史消息",
                J.obj("user_id", "QQ 号", "message_seq", "起始消息序号（可选）", "count", "条数")));
        c.add("set_msg_emoji_like", act("给消息贴表情回应",
                J.obj("message_id", "消息 ID", "emoji_id", "表情 ID", "set", "true=贴上 false=取消")));

        // 媒体
        c.add("get_image", act("获取图片文件（返回本地路径）", J.obj("file", "图片 file 值")));
        c.add("get_record", act("获取语音文件",
                J.obj("file", "语音 file 值", "out_format", "输出格式 mp3/amr/wav/flac（可选）")));
        c.add("can_send_image", act("能否发图（账号风控状态）", J.obj()));
        c.add("can_send_record", act("能否发语音（账号风控状态）", J.obj()));
        c.add("ocr_image", act("图片文字识别", J.obj("image", "图片 file 值")));

        // 文件
        c.add("upload_group_file", act("上传群文件（本地绝对路径交给基板：中转开着→外链 URL，没开→file:/// URI）",
                J.obj("group_id", "群号", "file", "本地路径或 HTTP(S) URL", "name", "显示文件名", "folder", "群文件夹 ID（可选）")));
        c.add("upload_private_file", act("上传私聊文件（本地绝对路径交给基板：中转开着→外链 URL，没开→file:/// URI）",
                J.obj("user_id", "QQ 号", "file", "本地路径或 HTTP(S) URL", "name", "显示文件名")));

        // 查询
        c.add("get_group_list", act("获取群列表", J.obj()));
        c.add("get_group_info", act("获取群信息", J.obj("group_id", "群号", "no_cache", "是否不用缓存（可选）")));
        c.add("get_group_member_list", act("获取群成员列表", J.obj("group_id", "群号")));
        c.add("get_group_member_info", act("获取群成员信息",
                J.obj("group_id", "群号", "user_id", "QQ 号", "no_cache", "是否不用缓存（可选）")));
        c.add("get_friend_list", act("获取好友列表", J.obj()));
        c.add("get_stranger_info", act("获取陌生人信息", J.obj("user_id", "QQ 号", "no_cache", "是否不用缓存（可选）")));
        c.add("get_login_info", act("获取登录号信息", J.obj()));
        c.add("get_recent_contact", act("获取最近联系人", J.obj("count", "条数")));

        // 群管
        c.add("set_group_ban", act("群禁言（duration 秒，0=解除；duration=0 且 user_id=0 无意义）",
                J.obj("group_id", "群号", "user_id", "QQ 号", "duration", "禁言秒数，0 表示解除")));
        c.add("set_group_whole_ban", act("全员禁言开关", J.obj("group_id", "群号", "enable", "true=开启")));
        c.add("set_group_admin", act("设置/取消群管理", J.obj("group_id", "群号", "user_id", "QQ 号", "enable", "true=设置")));
        c.add("set_group_card", act("设置群名片（空串=清空）", J.obj("group_id", "群号", "user_id", "QQ 号", "card", "群名片")));
        c.add("set_group_name", act("设置群名", J.obj("group_id", "群号", "group_name", "新群名")));
        c.add("set_group_leave", act("退群（is_dismiss=true 且是群主时解散）", J.obj("group_id", "群号", "is_dismiss", "是否解散")));
        c.add("set_group_kick", act("踢人", J.obj("group_id", "群号", "user_id", "QQ 号", "reject_add_request", "是否拒绝再加群")));
        c.add("set_group_sign", act("群打卡", J.obj("group_id", "群号")));
        c.add("get_group_at_all_remain", act("查询 @全体成员 剩余次数", J.obj("group_id", "群号")));
        c.add("group_poke", act("群内戳一戳", J.obj("group_id", "群号", "user_id", "QQ 号")));
        c.add("_get_group_notice", act("获取群公告（NapCat 扩展）", J.obj("group_id", "群号")));
        c.add("_send_group_notice", act("发布群公告（NapCat 扩展）",
                J.obj("group_id", "群号", "content", "公告内容", "image", "公告图片（可选）")));

        // 请求 / 好友
        c.add("set_friend_add_request", act("处理加好友请求",
                J.obj("flag", "请求 flag", "approve", "true=同意", "remark", "备注")));
        c.add("set_group_add_request", act("处理加群/邀请请求",
                J.obj("flag", "请求 flag", "sub_type", "add=加群 invite=邀请", "approve", "true=同意", "reason", "拒绝理由")));
        c.add("delete_friend", act("删除好友", J.obj("user_id", "QQ 号")));
        c.add("send_like", act("给好友点赞", J.obj("user_id", "QQ 号", "times", "次数，默认 10")));
        c.add("friend_poke", act("私聊戳一戳", J.obj("user_id", "QQ 号")));

        // 账号 / 系统
        c.add("set_qq_profile", act("修改账号资料", J.obj("nickname", "昵称", "personal_note", "个性签名")));
        c.add("set_self_longnick", act("修改个性签名（NapCat 扩展）", J.obj("longNick", "签名内容")));
        c.add("get_status", act("获取运行状态（online/good）", J.obj()));
        c.add("get_version_info", act("获取实现版本信息", J.obj()));
        c.add("get_cookies", act("获取登录态 Cookies", J.obj("domain", "域名，如 qun.qq.com")));
        return c;
    }

    private static JsonObject act(String desc, JsonObject params) {
        return J.obj("desc", desc, "params", params == null ? new JsonObject() : params);
    }

    // ---------------- 参数工具 ----------------

    /** 单段消息：{@code [{"type":type,"data":{"file":file}}]}（图片/语音的本地路径经中转载定）。 */
    private JsonArray seg(String type, String file) {
        JsonArray a = new JsonArray();
        a.add(J.obj("type", type, "data", J.obj("file", mediaParam(file))));
        return a;
    }

    /**
     * 上传类动作的 file 参数：URL / base64:// / 已是 file: 的原样透传；
     * 本地绝对路径交给 {@link Relay}（中转在跑 → 外链 URL；没跑 → NapCat 认的 {@code file:///C:/x}）；
     * 相对路径与 fileid 原样交给 NapCat。
     */
    private String fileParam(String file) {
        String p = file == null ? "" : file.trim();
        if (p.isEmpty()) return p;
        // 洞 2：这是"把本机文件交出去"的一跳 —— 先判"能不能读这个文件"再决定要不要转成 URL/file:///。
        // 拒绝时不产出 URL（返回原值）；真正的拒绝由 call() 里的改写层再判一次并作为动作结果返回。
        String path = localPathOf(p);
        if (path != null && needPath(path) != null) return p;
        Relay r = relay;
        if (r == null) return legacyFileParam(p);
        return r.uploadParam(p);
    }

    /**
     * 图片 / 语音段的 file 参数：中转在跑就换成外链；没跑时<b>原样透传</b>
     * （NapCat 的 image / record 段本来就认同机本地路径，不加 {@code file:///} 是既有口径）。
     */
    private String mediaParam(String file) {
        String p = file == null ? "" : file.trim();
        if (p.isEmpty()) return p;
        // 洞 2：同上 —— 先判"能不能读这个文件"再决定要不要换外链。
        String path = localPathOf(p);
        if (path != null && needPath(path) != null) return p;
        Relay r = relay;
        return r == null ? p : r.mediaParam(p);
    }

    /** 没装配中转时的同机口径（V3 现场验证过）：本地绝对路径 → {@code file:///C:/x}。 */
    private static String legacyFileParam(String p) {
        String low = p.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("file:") || low.startsWith("base64://")) return p;
        boolean abs = p.startsWith("/") || (p.length() >= 2 && p.charAt(1) == ':');
        if (!abs) return p;
        String fwd = p.replace('\\', '/');
        return fwd.startsWith("/") ? "file://" + fwd : "file:///" + fwd;
    }

    /** 没给显示名时用文件名兜底。 */
    private static String fileName(String file, String name) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String s = file == null ? "" : file.trim();
        int i = s.indexOf('?');
        if (i >= 0) s = s.substring(0, i);
        i = s.indexOf('#');
        if (i >= 0) s = s.substring(0, i);
        s = s.replace('\\', '/');
        i = s.lastIndexOf('/');
        String base = i >= 0 ? s.substring(i + 1) : s;
        return base.trim().isEmpty() ? "file" : base.trim();
    }

    /** 目录里所有动作名（控制台/工具清单用）。 */
    public static List<String> actionNames() {
        List<String> out = new ArrayList<String>();
        for (String k : new Api(null, null).catalog().keySet()) out.add(k);
        return out;
    }
}
