package sair.v4.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

import sair.v4.auth.Caller;
import sair.v4.kit.J;
import sair.v4.kit.Str;
import sair.v4.store.Store;

/**
 * <b>一条通知事件的确定性文本标签</b>（N1：通知接入为「特殊消息记录」）。
 *
 * <h3>它解决什么</h3>
 * <p>接单前 {@code QqGateway.onEvent} 对 {@code post_type=notice} <b>整类丢弃</b>
 * （只打一行 {@code event=notice ignored}）：8 天普查里 1,827 条通知（= 入站事件帧的 5.6%）
 * 一条都没留痕 —— 谁戳了她、谁撤回了什么、谁进了哪个群，窗口里都看不见。
 * 本类只做一件事：把一条通知翻成<b>一行确定性的方括号标签</b>
 * （{@code [撤回] 张三 撤回了一条消息 (msg_id=657075616)}），交给 {@code QqGateway} 落库。
 * 标签就是模型在「本会话最近 N 条聊天记录」里看到的东西（{@code ctx.ChatWindow} 逐字照抄
 * {@code content}）⇒ <b>本类是通知进模型的唯一通路</b>。</p>
 *
 * <h3>纯函数（与 {@link MediaRender} 同一纪律）</h3>
 * <p>无网络、无模型、无时钟、无随机、无副作用；<b>不写库</b>。唯一的"读"是
 * {@link #label(Ev, String, Store, Caller)} 里那两次<b>只读</b>的点查
 * （{@code grouplog}/{@code dialog} 里这个人最近一条 {@code nickname}）——
 * 主人裁定「昵称只查库；查不到 {@code #<user_id>}」，所以这一步<b>不可能</b>有外部调用或等待。
 * 允许的异常只有"库不可用"，由调用方（{@code QqGateway}，那一层本来就有 try/catch）兜住。</p>
 *
 * <h3>契约（逐条可断言）</h3>
 * <ol>
 *   <li><b>绝不静默丢弃</b>：认不出的 {@code notice_type}/{@code sub_type} 也给一行
 *       {@code [未知通知 type=… sub=…]}（后接最多 {@value #UNKNOWN_FIELDS_MAX} 个标量
 *       {@code k=v}，值 ≤ {@value #UNKNOWN_VALUE_CHARS} 字符，按 {@code raw} 键序）；</li>
 *   <li><b>标签永远非空</b>：每个分支返回的都是 {@code [} 开头 {@code ]} 结尾的整串；</li>
 *   <li><b>单行</b>：输出里绝不含换行/Tab（值先压单行）；</li>
 *   <li><b>第三方字符串伪造不出基板标签</b>：值卫生一律走 {@link MediaRender} 那个助手
 *       （本包内共用，见下"只此一条产法"）—— {@code [}/{@code ]} 换圆括号、{@code ·} 换 {@code -}，
 *       所以"标签里的 {@code ·} ⇒ 那是机器写的"这条判据不被通知破坏；</li>
 *   <li><b>不改既有行</b>：撤回事件自成一行，不去改/删那条被撤回的原行
 *       （主人裁定"日志只增不减"）；</li>
 *   <li>不抛异常：{@code ev=null} ⇒ 空串（调用方拿空串就不落行）。</li>
 * </ol>
 *
 * <h3>只此一条产法（标签构造只在这一处）</h3>
 * <p>标签的<b>拼接</b>只在本类；值卫生只借 {@link MediaRender#san(String, int)} 那一份实现
 * （同包直接调，<b>不</b>新开 public 入口、不复制一份规则）。{@code QqGateway} 只负责
 * 把 {@link #label} 的结果落库，自己不拼任何一个字。</p>
 *
 * <h3>字段复核（本轮的原始帧普查，见 {@code tmp\n1\REPORT.md}）</h3>
 * <p>逐字段拿 {@code C:\Sair\Overflow\logs\onebot\2026-09-0{9..16}.log} 复算过：11 种
 * {@code notice_type}、19 组 {@code type/sub_type}、1,827 条。本类用到的那几个字段全在原始帧里
 * <b>亲眼见过</b>；表里标了但实测不存在的字段（如 {@code notify/profile_like} 的 {@code user_id}）
 * <b>没有</b>被写成代码，见 REPORT「未核实/与工单不符」一节。</p>
 */
public final class NoticeRender {

    private NoticeRender() {}

    // ---------------------------------------------------------------- 段型词（逐字对工单 §3 的标签表）

    /** 戳一戳（戳的是本机时用 {@link #POKE_YOU}）。 */
    public static final String POKE = "[戳一戳] ";
    public static final String POKE_YOU = " 戳了戳你";
    public static final String POKE_OTHER = " 戳了戳 ";
    public static final String INPUT_STATUS = "[正在输入] ";
    public static final String INPUT_SUFFIX = " 正在输入";
    public static final String PROFILE_LIKE = "[资料点赞] ";
    public static final String PROFILE_LIKE_MID = " 赞了 ";
    public static final String PROFILE_LIKE_TAIL = " 的资料";
    public static final String TITLE = "[头衔] ";
    public static final String TITLE_TAIL = " 的头衔有变更";
    public static final String EMOJI_LIKE = "[表情回应] ";
    public static final String EMOJI_LIKE_MID = " 给消息 ";
    public static final String EMOJI_LIKE_ADD = " 加了 ";
    public static final String EMOJI_LIKE_REMOVE = " 取消了回应，原有 ";
    public static final String EMOJI_LIKE_TAIL = " 个表情";
    public static final String RECALL = "[撤回] ";
    public static final String RECALL_TAIL = " 撤回了一条消息 (msg_id=";
    public static final String KICK = "[移出群] ";
    public static final String KICK_MID = " 把 ";
    public static final String KICK_TAIL = " 移出了群";
    public static final String LEAVE = "[退群] ";
    public static final String LEAVE_TAIL = " 退出了群";
    public static final String KICK_ME = "[被移出] 本机器人被 ";
    public static final String KICK_ME_TAIL = " 移出了群";
    public static final String JOIN = "[进群] ";
    public static final String JOIN_INVITE_MID = " 邀请 ";
    public static final String JOIN_TAIL = " 加入了群";
    public static final String CARD = "[名片] ";
    public static final String CARD_MID = " 把群名片改成 ";
    public static final String BAN = "[禁言] ";
    public static final String BAN_MID = " 被禁言 ";
    public static final String BAN_TAIL = " 秒";
    public static final String LIFT_BAN = "[解除禁言] ";
    public static final String LIFT_BAN_TAIL = " 的禁言被解除";
    public static final String UPLOAD = "[群文件] ";
    public static final String UPLOAD_MID = " 上传了文件 ";
    public static final String FRIEND_ADD = "[加好友] ";
    public static final String FRIEND_ADD_TAIL = " 请求加好友";
    public static final String ESSENCE = "[精华] ";
    public static final String ESSENCE_MID = " 把消息 ";
    public static final String ESSENCE_TAIL = " 设为精华";
    public static final String UNKNOWN = "[未知通知 type=";
    public static final String UNKNOWN_MID = " sub=";
    public static final String UNKNOWN_TAIL = "]";

    /** 本机在标签里的自称（{@code user_id}/{@code target_id} = {@code self_id} 时用；见 {@link #nick}）。 */
    public static final String SELF_NAME = "本机";

    /** 昵称查不到时的兜底形状（{@code #<user_id>}；主人裁定）。 */
    public static final String FALLBACK_PREFIX = "#";

    /** {@code dialog.role}：私聊通知与私聊消息<b>同一个取值</b>（{@code Agent.ask} 落 user 行也是它）。 */
    public static final String DIALOG_ROLE = "user";

    /**
     * 这一条通知要不要把 {@code message_id} 写进 {@code msg_id} 列。
     *
     * <p><b>为什么不是"一律写"</b>（这是与工单 N1 §1.3 原话的唯一一处偏差，理由见
     * {@code tmp\n1\REPORT.md} 的"异议"一节）：{@code group_msg_emoji_like}/{@code essence} 的
     * {@code message_id} 是<b>别人那条真消息的 id</b>（不是通知自己的行身份）。窗口
     * （{@code ctx.ChatWindow.dropRepeat}）按 {@code msg_id} 去重、留最新的那一行 —— 一旦通知与
     * 被它指向的消息共用同一个 {@code msg_id}，<b>两行会被压成一行</b>：响应后从窗口里既看不到
     * 那条消息、也看不到"有人给谁加了表情"。主人要的是"两条都在"（撤回那一类的原话），
     * 所以这里对这两类写 {@code 0}，真 id 留在 {@code extra.message_id} 里（结构化、可过滤，
     * 不改表结构）。</p>
     *
     * <p>{@code group_recall}/{@code friend_recall} 照工单写原样（那个 id 指向的消息<b>已经被撤回了</b>，
     * 通知行就是它唯一的留痕）；其余类型本来就没有 {@code message_id}。</p>
     */
    public static boolean msgIdIsSubject(String type) {
        String t = low(type);
        return "group_msg_emoji_like".equals(t) || "essence".equals(t);
    }

    /** 未知通知最多带几个标量字段。 */
    private static final int UNKNOWN_FIELDS_MAX = 6;
    /** 未知通知单个值的上限（字符）。 */
    private static final int UNKNOWN_VALUE_CHARS = 40;
    /** 昵称的长度上界（比 {@link MediaRender#MAX_FIELD_CHARS} 更紧：昵称不该占满一格）。 */
    private static final int NICK_CHARS = 48;
    /** 非标量值（对象/数组）压成 JSON 后的取值上限。 */
    private static final int NESTED_VALUE_CHARS = 200;
    /** {@code extra} 里 {@code sub_type} 的最大长度。 */
    private static final int SUB_TYPE_CHARS = 40;

    /**
     * 未知通知不带进标签的结构性键（它们不是"这条通知说了什么"，事件分类已经在别处）。
     * <p>{@code post_type} 也不是内容：{@code [未知通知 type=…]} 里的 {@code type} 已经是
     * 事件自己的 {@code notice_type}。</p>
     */
    private static final java.util.Set<String> STRUCT_KEYS = new java.util.HashSet<String>(
            java.util.Arrays.asList("post_type", "notice_type", "sub_type", "time", "self_id",
                    "user_id", "group_id", "message_id", "operator_id", "target_id"));

    // ---------------------------------------------------------------- 对外两条产法

    /**
     * 一条通知的标签（<b>标签构造的唯一入口</b>）。
     *
     * @param ev   入站通知事件（{@code null} 或没有 {@code notice_type} ⇒ 空串）
     * @param self 本机 QQ（{@code self_id}；用来判"戳的是本机"/"本机被移出"；{@code <=0} = 不认本机）
     * @param st   库（<b>只读</b>，用来查昵称；{@code null} ⇒ 一律走 {@code #<user_id>}）
     * @param c    本条通知的调用者（决定会话键与群号；{@code null} 且事件带 {@code group_id} 时
     *             退回"只按 user_id 查群昵称"）
     * @return 标签串（永远是单行、{@code [} 开头 {@code ]} 结尾、非空）；没有 {@code notice_type} ⇒ 空串
     */
    public static String label(Ev ev, long self, Store st, Caller c) {
        if (ev == null) return "";
        String t = low(ev.noticeType());
        if (t.isEmpty()) return "";
        String sub = low(ev.subType());
        if ("notify".equals(t)) return labelNotify(ev, sub, self, st, c);
        if ("group_msg_emoji_like".equals(t)) return labelEmojiLike(ev, st, c);
        if ("group_recall".equals(t)) return RECALL + nick(ev.operatorId(), st, c) + RECALL_TAIL + ev.messageId() + ")";
        if ("friend_recall".equals(t)) return RECALL + nick(ev.userId(), st, c) + RECALL_TAIL + ev.messageId() + ")";
        if ("group_decrease".equals(t)) return labelDecrease(ev, sub, self, st, c);
        if ("group_increase".equals(t)) return labelIncrease(ev, sub, st, c);
        if ("group_card".equals(t)) return CARD + nick(ev.userId(), st, c) + CARD_MID + cut(val(ev, "card_new"));
        if ("group_ban".equals(t)) return labelBan(ev, sub, st, c);
        if ("group_upload".equals(t)) return labelUpload(ev, st, c);
        if ("friend_add".equals(t)) return FRIEND_ADD + nick(ev.userId(), st, c) + FRIEND_ADD_TAIL;
        if ("essence".equals(t)) {
            return ESSENCE + nick(ev.operatorId(), st, c) + ESSENCE_MID + ev.messageId() + ESSENCE_TAIL;
        }
        // 未知 notice_type（含普查里 0 例的 group_admin/honor/offline_file 与将来新增的）：
        // 绝不静默丢弃 —— 一行标签 + 最多 6 个标量事实。
        return unknown(ev, t, sub);
    }

    /**
     * 落库时写进 {@code extra} 的结构化事实（<b>不是</b>标签；方便日后不开 schema 变更就能过滤）。
     *
     * @return {@code {"notice":true,"notice_type":…,"sub_type":…}}；{@code ev=null} 或没有
     *         {@code notice_type} ⇒ {@code null}（调用方据此不落行）
     */
    public static JsonObject extra(Ev ev) {
        if (ev == null) return null;
        String t = low(ev.noticeType());
        if (t.isEmpty()) return null;
        String sub = low(ev.subType());
        JsonObject o = new JsonObject();
        o.addProperty("notice", true);
        o.addProperty("notice_type", t);
        o.addProperty("sub_type", cut(sub, SUB_TYPE_CHARS));
        return o;
    }

    // ---------------------------------------------------------------- 逐类标签

    /** {@code notify/*}：poke / input_status / profile_like / title（其余子类型 ⇒ 未知那一路）。 */
    private static String labelNotify(Ev ev, String sub, long self, Store st, Caller c) {
        if ("poke".equals(sub)) {
            String who = nick(ev.userId(), st, c);
            long target = longOf(ev, "target_id", 0L);
            if (target <= 0L || (self > 0L && target == self)) return POKE + who + POKE_YOU;
            return POKE + who + POKE_OTHER + nick(target, st, c);
        }
        if ("input_status".equals(sub)) return INPUT_STATUS + nick(ev.userId(), st, c) + INPUT_SUFFIX;
        if ("profile_like".equals(sub)) {
            // 注意：这一类的 actor 是 operator_id（普查 2/2 都只有 operator_id，**没有** user_id）
            long op = longOf(ev, "operator_id", 0L);
            return PROFILE_LIKE + nick(op > 0L ? op : ev.userId(), st, c)
                    + PROFILE_LIKE_MID + nick(ev.userId(), st, c) + PROFILE_LIKE_TAIL;
        }
        if ("title".equals(sub)) return TITLE + nick(ev.userId(), st, c) + TITLE_TAIL;
        return unknown(ev, "notify", sub);
    }

    /**
     * {@code group_msg_emoji_like}：{@code likes} 是<b>对象数组</b>
     * （实测形状 {@code [{"emoji_id":"66","count":1}]}）⇒ N 取各元素 {@code count} 之和，
     * 没有 {@code count} 时退回 {@code emoji_id} 让内容不丢。
     */
    private static String labelEmojiLike(Ev ev, Store st, Caller c) {
        String who = nick(ev.userId(), st, c);
        long id = ev.messageId();
        long n = likeCount(ev);
        if (!boolOf(ev, "is_add", true)) {
            return EMOJI_LIKE + who + EMOJI_LIKE_MID + id
                    + EMOJI_LIKE_REMOVE + n + EMOJI_LIKE_TAIL;
        }
        return EMOJI_LIKE + who + EMOJI_LIKE_MID + id + EMOJI_LIKE_ADD + n + EMOJI_LIKE_TAIL;
    }

    /** {@code likes} 里的表情个数（{@code count} 之和；没有就数元素；再没有就 {@code emoji_id} 串）。 */
    private static long likeCount(Ev ev) {
        JsonElement e = J.get(ev.raw(), "likes");
        if (e == null || e.isJsonNull()) return 0L;
        if (e.isJsonPrimitive()) {
            try { return e.getAsLong(); } catch (Throwable t) { return 0L; }
        }
        if (e.isJsonArray()) {
            JsonArray a = e.getAsJsonArray();
            long sum = 0L;
            boolean anyCount = false;
            StringBuilder ids = new StringBuilder();
            for (int i = 0; i < a.size(); i++) {
                JsonElement it = a.get(i);
                if (it == null || !it.isJsonObject()) continue;
                JsonObject o = it.getAsJsonObject();
                if (o.has("count") && !o.get("count").isJsonNull()) {
                    try { sum += o.get("count").getAsLong(); anyCount = true; } catch (Throwable ignored) {}
                }
                if (ids.length() > 0) ids.append(',');
                ids.append(Str.nz(J.s(o, "emoji_id", "")));
            }
            if (anyCount) return sum;
            if (ids.length() > 0) return ids.length();
            return 0L;
        }
        if (e.isJsonObject()) {
            try { return e.getAsJsonObject().get("count").getAsLong(); } catch (Throwable t) { return 0L; }
        }
        return 0L;
    }

    /** {@code group_decrease/*}：kick（谁被踢）/ leave（谁自己退）/ kick_me（本机被踢）。 */
    private static String labelDecrease(Ev ev, String sub, long self, Store st, Caller c) {
        long u = ev.userId();
        long op = longOf(ev, "operator_id", 0L);
        if ("kick".equals(sub)) return KICK + nick(op, st, c) + KICK_MID + nick(u, st, c) + KICK_TAIL;
        if ("leave".equals(sub)) return LEAVE + nick(u, st, c) + LEAVE_TAIL;
        if ("kick_me".equals(sub)) return KICK_ME + nick(op, st, c) + KICK_ME_TAIL;
        // 其余子类型（含普查 0 例的 kick_me 之外任何一种）：走"谁走了"这条最保守的读法，
        // 但**不编**：标签里带 sub_type，一眼看出是没见过的形状。
        long actor = op > 0L ? op : u;
        return LEAVE + nick(actor, st, c) + LEAVE_TAIL + UNKNOWN_MID + sub + "]";
    }

    /**
     * {@code group_increase/approve|invite}。
     * <p>{@code approve}（108 例）的 {@code operator_id} <b>不是</b>动作发起者，而是系统的处理记录：
     * 实测 108 例群里，把 {@code operator_id} 当"批准人"念出来会得到"某管理员邀请某人入群"这种
     * <b>基板编出来的事实</b>。所以只有 {@code invite}（7 例）才念操作者，{@code approve} 只念进群的人。</p>
     */
    private static String labelIncrease(Ev ev, String sub, Store st, Caller c) {
        long u = ev.userId();
        long op = longOf(ev, "operator_id", 0L);
        if ("invite".equals(sub) && op > 0L && op != u) {
            return JOIN + nick(op, st, c) + JOIN_INVITE_MID + nick(u, st, c) + JOIN_TAIL;
        }
        return JOIN + nick(u, st, c) + JOIN_TAIL;
    }

    /** {@code group_ban/ban|lift_ban}。 */
    private static String labelBan(Ev ev, String sub, Store st, Caller c) {
        if ("lift_ban".equals(sub)) return LIFT_BAN + nick(ev.userId(), st, c) + LIFT_BAN_TAIL;
        return BAN + nick(ev.userId(), st, c) + BAN_MID + longOf(ev, "duration", 0L) + BAN_TAIL;
    }

    /** {@code group_upload}：{@code file.name}（实测形状 {@code {id,name,size,busid}}）+ 大小（走 M2 的 {@code sizeText}）。 */
    private static String labelUpload(Ev ev, Store st, Caller c) {
        JsonObject f = J.sub(ev.raw(), "file");
        String name = f == null ? "" : J.s(f, "name", "");
        StringBuilder sb = new StringBuilder(UPLOAD).append(nick(ev.userId(), st, c)).append(UPLOAD_MID);
        sb.append(cut(name));
        String size = f == null ? "" : MediaRender.sizeText(J.l(f, "size", 0L));
        if (!size.isEmpty()) sb.append(' ').append(size);
        return sb.toString();
    }

    /** 未知通知：{@code [未知通知 type=… sub=…]} + 最多 6 个标量 {@code k=v}（按 {@code raw} 键序）。 */
    private static String unknown(Ev ev, String type, String sub) {
        StringBuilder sb = new StringBuilder(UNKNOWN).append(cut(type, UNKNOWN_VALUE_CHARS))
                .append(UNKNOWN_MID).append(cut(sub, UNKNOWN_VALUE_CHARS));
        int n = 0;
        for (Map.Entry<String, JsonElement> e : ev.raw().entrySet()) {
            if (n >= UNKNOWN_FIELDS_MAX) break;
            String k = e.getKey();
            if (Str.blank(k) || STRUCT_KEYS.contains(k)) continue;
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            String s = value(v);
            if (s.isEmpty()) continue;
            sb.append(' ').append(cut(k, UNKNOWN_VALUE_CHARS)).append('=').append(cut(s, UNKNOWN_VALUE_CHARS));
            n++;
        }
        return sb.append(UNKNOWN_TAIL).toString();
    }

    // ---------------------------------------------------------------- 取值 / 卫生 / 昵称

    /**
     * 昵称：<b>只查库</b>，查不到 {@code #<user_id>}（主人裁定；本轮零网络、零等待）。
     *
     * <p>顺序：① 这个人在<b>这个群</b>里最近一条 {@code grouplog.nickname}（同一个人在不同群的名片不一样）；
     * ② 这个人在<b>任意群</b>里最近一条（{@code idx_grouplog_user}，事件没带 {@code group_id} 时走这条）；
     * ③ 私聊会话（{@code dialog} + {@code role=user}）那一路。三条都只走索引点查。</p>
     *
     * <p>查到的名字同样过卫生 + 长度上界：库里的老行可能是别人写进去的任意串。</p>
     */
    public static String nick(long uid, Store st, Caller c) {
        if (uid <= 0L) return FALLBACK_PREFIX + uid;
        String v = look(uid, st, c);
        if (v == null || v.trim().isEmpty()) return FALLBACK_PREFIX + uid;
        String s = MediaRender.san(v, NICK_CHARS);
        return s.isEmpty() ? (FALLBACK_PREFIX + uid) : s;
    }

    private static String look(long uid, Store st, Caller c) {
        if (st == null || st.db() == null) return null;
        try {
            String sess = c == null ? "" : Str.trim(c.session());
            long gid = c != null && c.groupId() > 0L ? c.groupId() : 0L;
            // 顺序：先**群**（通知绝大多数发生在群里，而 grouplog 存的是"这个人+这个群的名片"，
            // 比私聊会话那边的正文可靠得多），再"这个人的任意最近一条"，最后私聊会话的正文。
            if (gid > 0L) {
                JsonObject r = st.db().queryOne(
                        "SELECT nickname AS n FROM grouplog WHERE user_id = ? AND group_id = ?"
                                + " ORDER BY ts DESC, id DESC LIMIT 1", uid, gid);
                if (r != null && !Str.blank(J.s(r, "n", ""))) return J.s(r, "n", "");
            }
            JsonObject a = st.db().queryOne(
                    "SELECT nickname AS n FROM grouplog WHERE user_id = ?"
                            + " ORDER BY ts DESC, id DESC LIMIT 1", uid);
            if (a != null && !Str.blank(J.s(a, "n", ""))) return J.s(a, "n", "");
            if (sess.startsWith("qq:")) {
                // 私聊：与私聊消息同一条路（dialog + role=user）。
                // 必须**排除通知行自己**（extra 里有 notice:true）—— 否则会自引用成一串套娃标签：
                // 通知 A 的"昵称"取到通知 A 的正文，下一条通知再把它包一层（实测过一次，见 REPORT）。
                JsonObject r = st.db().queryOne(
                        "SELECT content AS n FROM dialog WHERE session = ? AND role = ?"
                                + " AND (extra IS NULL OR extra NOT LIKE '%\"notice\":true%')"
                                + " ORDER BY ts DESC, id DESC LIMIT 1", sess, DIALOG_ROLE);
                if (r != null && !Str.blank(J.s(r, "n", ""))) return J.s(r, "n", "");
            }
        } catch (Throwable t) {
            return null;            // 库不可用 = 查不到（调用方兜异常；这里不抛）
        }
        return null;
    }

    /** {@code raw} 里的长整数值（缺/非数字 ⇒ {@code def}）。 */
    private static long longOf(Ev ev, String key, long def) {
        try {
            if (!ev.raw().has(key) || ev.raw().get(key).isJsonNull()) return def;
            return ev.raw().get(key).getAsLong();
        } catch (Throwable t) {
            return def;
        }
    }

    /** {@code raw} 里的布尔值（缺/非布尔 ⇒ {@code def}）。 */
    private static boolean boolOf(Ev ev, String key, boolean def) {
        try {
            if (!ev.raw().has(key) || ev.raw().get(key).isJsonNull()) return def;
            return ev.raw().get(key).getAsBoolean();
        } catch (Throwable t) {
            return def;
        }
    }

    /** 取一个字符串字段（缺 ⇒ 空串）。 */
    private static String val(Ev ev, String key) {
        return ev == null ? "" : J.s(ev.raw(), key, "");
    }

    /** 标量值 → 字符串（对象/数组压成有界 JSON；都不是 ⇒ 空串 = 不出现）。 */
    private static String value(JsonElement v) {
        if (v == null || v.isJsonNull()) return "";
        if (v.isJsonPrimitive()) {
            try { return Str.nz(v.getAsString()); } catch (Throwable t) { return ""; }
        }
        try { return Str.cut(J.json(v), NESTED_VALUE_CHARS); } catch (Throwable t) { return ""; }
    }

    /**
     * 长度上界（先 {@link MediaRender#san} 卫生再截断）。
     * <p>{@code max<=0} ⇒ 用 {@link MediaRender#MAX_FIELD_CHARS}（与 M2 同一默认）。</p>
     */
    private static String cut(String v) {
        return cut(v, 0);
    }

    private static String cut(String v, int max) {
        return MediaRender.san(v, max > 0 ? max : MediaRender.MAX_FIELD_CHARS);
    }

    private static String low(String s) {
        return Str.lower(Str.trim(s));
    }
}
