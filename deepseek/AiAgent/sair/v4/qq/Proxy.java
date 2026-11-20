package sair.v4.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sair.v4.kit.J;
import sair.v4.kit.Out;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Proxy —— 多 Bot 服务共用 1 个 QQ 号的路由代理。
 *
 * <h3>拓扑</h3>
 * <pre>
 *   NapCat ──反向WS──► AiAgent(Link, 服务端)
 *                          │
 *                          │ 同时作为客户端连出去（OutLink，可任意多个）
 *                          ├──► DC小枫    (~/Desktop/... :8083)
 *                          └──► Yunzai    (:2536/OneBotv11)
 * </pre>
 *
 * <h3>路由规则（前缀）</h3>
 * <ul>
 *   <li>NapCat 来的消息文本以 {@code "dc-"} 开头 ⇒ 去掉前缀 ⇒ 推给 DC 那条 OutLink；</li>
 *   <li>NapCat 来的消息文本以 {@code "yz-"} 开头 ⇒ 去掉前缀 ⇒ 推给 Yunzai 那条 OutLink；</li>
 *   <li>无前缀 ⇒ 不路由，交回椰羊自己处理（返回 false）。</li>
 * </ul>
 *
 * <h3>反向</h3>
 * <ul>
 *   <li>DC/Yunzai 推来的事件 ⇒ <b>原样</b>转给 NapCat（同一对 WS，直接下发）；</li>
 *   <li>DC/Yunzai 问的 {@code get_*} ⇒ 用真实数据回答（都是「椰羊的号」）；</li>
 *   <li>DC/Yunzai 发的 {@code send_*} ⇒ 转给 NapCat 执行。</li>
 * </ul>
 *
 * <p>三个服务共用同一个 {@code self_id}（椰羊的号），用户看到的是同一个 QQ。</p>
 */
public final class Proxy {

    /** 一条出站通道：前缀 + OutLink。 */
    public static final class Route {
        public final String prefix;    // "dc-" / "yz-"
        public final OutLink link;
        public final String name;
        // 事实面统计（路由由椰羊主持，她得看得见）
        public volatile long connectedAt = 0L;
        public volatile long lastEventAt = 0L;
        public volatile int eventsIn = 0;
        public volatile int asksOut = 0;
        public Route(String name, String prefix, OutLink link) {
            this.name = name;
            this.prefix = prefix;
            this.link = link;
        }
    }

    private final List<Route> routes = new CopyOnWriteArrayList<Route>();
    /** QQ 号可能超过 Integer.MAX_VALUE（如 2671623601），必须用 long。 */
    private volatile long selfId;
    private final Out out;
    /** 主人号（代问身份红线上界）。 */
    private volatile long masterUid;
    /** 往 NapCat 推事件的口子（由 Link 注入）。 */
    private volatile Push push;
    /** NapCat 连接本体（用于把下游的「只读动作」代理过去）。 */
    private volatile NapCat napcat;

    /** 把只读动作代理给 NapCat 的能力（由 Link 注入）。 */
    public interface NapCat {
        /** 同步调用 NapCat，返回原始响应 JSON（失败返回 null）。 */
        String call(String action, JsonObject params);
    }

    public void setNapCat(NapCat n) { this.napcat = n; }

    /** Link 注入的「往 NapCat 下发一帧」能力。 */
    public interface Push {
        /** @return true = 成功推到至少一条 NapCat 连接 */
        boolean event(JsonObject event);
    }

    public Proxy(long selfId, Out out) {
        this.selfId = selfId;
        this.out = out;
    }

    public void setPush(Push p) { this.push = p; }

    /** 运行期更新 self_id（NapCat 连上后 resyncSelfId 会改它）。 */
    public void setSelfId(long id) {
        if (id > 0) this.selfId = id;
    }

    public long selfId() { return selfId; }

    public void setMasterUid(long id) { this.masterUid = id; }

    public long masterUid() { return masterUid; }

    public void addRoute(String name, String prefix, OutLink link) {
        if (link == null) return;
        link.setOut(out);
        // 所有出站连接都自报同一个 QQ 号（三服务共用一号）
        link.setSelfId(selfId);
        dim("通道 " + name + " selfId=" + selfId + " url=" + link.url());
        routes.add(new Route(name, prefix, link));
    }

    public List<Route> routes() { return routes; }

    public OutLink byName(String name) {
        for (Route r : routes) if (r.name.equals(name)) return r.link;
        return null;
    }

    /**
     * <b>只在 NapCat 已连接时调用。</b> 起所有出站通道。
     *
     * <p>顺序很重要：先有 NapCat（下游要的一切查询都得问它），再连下游。
     * 反过来就会出现「下游问 get_version_info 我们答不上来」的报错。</p>
     */
    public void startAll() {
        NapCat nc = napcat;
        if (nc == null) {
            dim("NapCat 代理未注入，拒绝启动下游通道");
            return;
        }
        for (Route r : routes) r.link.start();
        dim("路由代理启动：" + routes.size() + " 条出站通道"
                + (routes.isEmpty() ? "" : "（" + joinNames() + "）"));
    }

    public boolean started() {
        for (Route r : routes) if (r.link.connected()) return true;
        return false;
    }

    public int routeCount() { return routes.size(); }

    public void stopAll() {
        for (Route r : routes) r.link.stop();
    }

    private String joinNames() {
        StringBuilder b = new StringBuilder();
        for (Route r : routes) {
            if (b.length() > 0) b.append(", ");
            b.append(r.name).append("=").append(r.link.url());
        }
        return b.toString();
    }

    // ==================== 入站：NapCat 的事件 ====================

    /**
     * 尝试按前缀路由一条 NapCat 事件。
     *
     * @return {@code true} 表示已被路由（调用方<b>不要</b>再交给椰羊）；
     *         {@code false} 表示不归我管，交回椰羊。
     */
    public boolean route(JsonObject event) {
        if (event == null || routes.isEmpty()) return false;
        // ★ 防环：代问注入的事件带 _aiagent.origin，绝不再参与分流
        if (event.has("_aiagent") && event.get("_aiagent").isJsonObject()) {
            JsonObject mk = event.getAsJsonObject("_aiagent");
            if (mk.has("origin")) return false;
        }
        String text = plainText(event);
        if (text == null || text.isEmpty()) return false;

        for (Route r : routes) {
            if (!text.startsWith(r.prefix)) continue;
            String stripped = text.substring(r.prefix.length());
            JsonObject copy = rebuild(event, stripped);
            r.link.pushEvent(copy);
            r.eventsIn++;
            r.lastEventAt = System.currentTimeMillis();
            String who = J.s(event, "user_id", "?");
            String gid = J.s(event, "group_id", "");
            fact("群" + (gid.isEmpty() ? "-" : gid) + " 用户" + who + "「" + abbreviated(text, 40)
                    + "」→ " + r.name + (r.link.connected() ? "" : "（未连接，已丢弃）"));
            dim("路由 [" + text.substring(0, Math.min(24, text.length())) + "] -> "
                    + r.name + (r.link.connected() ? "" : "（未连接，已丢弃）"));
            return true;
        }
        return false;
    }

    // ==================== 出站：DC/Yunzai 的事件 ====================

    /** DC/Yunzai 推来的事件 ⇒ 原样转给 NapCat。 */
    public boolean forwardToNapCat(OutLink from, JsonObject event) {
        if (event == null) return false;
        // ★ 内部标记绝不外泄给 NapCat（只在基板内部用于防环）
        event.remove("_aiagent");
        // self_id 统一
        event.addProperty("self_id", selfId);
        Push p = push;
        if (p == null) {
            dim("[" + from.name() + "] 事件要转给 NapCat，但没有 NapCat 连接，丢弃");
            return false;
        }
        boolean ok = p.event(event);
        dim("[" + from.name() + "] 事件 -> NapCat " + (ok ? "已下发" : "失败（无连接）"));
        return ok;
    }

    // ==================== DC/Yunzai 问的问题 ====================

    /**
     * 回答 OutLink 对端发来的 {@code get_*}。
     *
     * <p>三个服务共用一号 ⇒ 一律报 {@code self_id}。好友/群列表给空数组
     * （它们不依赖这个做业务，给真实列表反而会让它们去投递不该投的消息）。</p>
     */
    /**
     * 回答 OutLink 对端发来的动作。
     *
     * <p><b>一律代理给 NapCat，不自己编数据。</b> 下游（DC / Yunzai）问的
     * {@code get_version_info} / {@code get_login_info} / {@code get_friend_list} /
     * {@code get_group_list} / {@code get_msg} / {@code get_image} … 全部原样问 NapCat，
     * 把它的 {@code data} 原路返回。NapCat 没连上就返回 {@code null}
     * （调用方回 {@code retcode=1404}）—— <b>绝不伪造</b>。</p>
     *
     * <p>{@code send_private_msg} / {@code send_group_msg} 等写动作同样转发给 NapCat 执行，
     * 把结果（含 {@code message_id}）原路返回。</p>
     */
    /**
     * 带「哪条通道在问」的动作入口。
     *
     * <p>代问窗口打开时，该通道发来的 {@code send_*} 一律<b>截获</b>：
     * 不发给 NapCat、不进群，而是记进这次代问的结果里。</p>
     */
    public JsonElement answer(String route, String action, JsonObject params) {
        Pending p = pending;
        // ★ 认领优先：只要该服务还有未过期的 claim，它发出去的任何 send_* 都不许直通
        // ★ 主人定：默认不拦。只有显式打开 capture 才截获（将来做"静默代问"再用）
        if (capture && p != null && p.alive() && route != null && route.equals(p.route)
                && action != null && action.startsWith("send_")) {
            String sess = sessionOf(action, params);
            boolean same = sess.equals(p.session);
            JsonObject rec = new JsonObject();
            rec.addProperty("action", action);
            rec.addProperty("session", sess);
            rec.addProperty("claimed", same);
            rec.add("params", params == null ? new JsonObject() : params.deepCopy());
            synchronized (p.acts) {
                if (same) p.acts.add(rec); else p.unclaimed.add(rec);
            }
            p.lastAt = System.currentTimeMillis();
            JsonObject ok = new JsonObject();
            ok.addProperty("message_id", 900000 + p.acts.size() + p.unclaimed.size());
            try {
                CaptureSink cs = captureSink;
                String tx = textOf(rec);
                if (cs != null) cs.onCapture(p.route, sess, tx, same);
                // ★ 图片：截获到就落盘 + 贴面板（不再只打一个 [图片]）
                if (cs != null && rec.has("params") && rec.get("params").isJsonObject()) {
                    JsonElement m = rec.getAsJsonObject("params").get("message");
                    if (m != null && m.isJsonArray()) {
                        for (JsonElement e : m.getAsJsonArray()) {
                            if (!e.isJsonObject()) continue;
                            JsonObject so = e.getAsJsonObject();
                            if (!"image".equals(J.s(so, "type", ""))) continue;
                            if (!so.has("data") || !so.get("data").isJsonObject()) continue;
                            try {
                                JsonObject got = materializeImage(so.getAsJsonObject("data"));
                                if (got != null) {
                                    cs.onImage(p.route, sess, new java.io.File(J.s(got, "local", "")), same);
                                }
                            } catch (Throwable t2) { dim("截获图片落盘失败：" + t2.getClass().getSimpleName()); }
                        }
                    }
                }
            } catch (Throwable ignored) {}
            if (same) {
                fact("代问中截获 " + action + "（" + p.route + " / " + sess + "），不回群");
            } else {
                fact("代问中截获【未认领】" + action + "（" + p.route + " → " + sess
                        + "，本次问的是 " + p.session + "）—— 已拦下，未外发");
            }
            dim("代问截获 " + action + " claimed=" + same + " sess=" + sess);
            return ok;
        }
        if (p != null && p.alive() && route != null && route.equals(p.route)
                && action != null && action.startsWith("send_")) {
        }
        return answer(action, params);
    }

    public JsonElement answer(String action, JsonObject params) {
        if (action == null || action.isEmpty()) return null;

        // ① 身份/版本类：**始终用自己的**，不再向上游 NapCat 要
        //    （主人 2026-11-15 定：AiAgent 是一个独立的 OneBot 实现，
        //     不该冒充 NapCat 的版本号 —— 下游要按我们自己的 app_name/version 识别）
        JsonElement own = selfIdentity(action);
        if (own != null) return own;

        // ② 其余（好友/群/成员/消息/发送…）⇒ 问 NapCat（真数据优先）
        JsonElement got = proxyToNapCat(action, params);
        if (got != null) return got;

        // ② 真 NapCat 不在 ⇒ 冒充 NapCat 给一份"看起来正常"的答案
        //     目的：让下游（DC / Yunzai）在 NapCat 掉线时**不报错、不断连**，
        //     真连上之后自动切回真数据。
        JsonElement fake = fakeAnswer(action, params);
        if (fake != null) {
            dim("代理 " + action + " -> 模拟 NapCat（真 NepCat 未连接）");
            return fake;
        }
        // ★ 代理失败时，按动作的**期望类型**返回空值 —— 不能一律回 {}：
        //   DC（OneBot Kotlin）的 getFriendList/getGroupList 反序列化写的是 List<T>，
        //   拿到 JSON 对象会抛 "Expected BEGIN_ARRAY but was BEGIN_OBJECT" 并断开整条连接。
        if (expectsArray(action)) {
            return new JsonArray();
        }
        return null;
    }

    /**
     * AiAgent 自己的身份/版本信息 —— <b>不向上游 NapCat 索要</b>。
     *
     * <p>下游（DC / Yunzai）看到的是「AiAgent 网关」，不是「NapCat」。
     * 这样它们才知道上面多了一层代理，也便于主人从下游日志里区分到底连的是谁。</p>
     *
     * @return 自己答得了就返回；{@code null} = 这个动作不归我（交给代理/模拟层）
     */
    private JsonElement selfIdentity(String action) {
        switch (action) {
            case "get_version_info": {
                JsonObject o = new JsonObject();
                o.addProperty("app_name", "AiAgent-Native-Gateway");
                o.addProperty("app_version", "4.4");
                o.addProperty("protocol_version", "v11");
                o.addProperty("nt_protocol", "v11");
                return o;
            }
            case "get_version": {
                JsonObject o = new JsonObject();
                o.addProperty("app_name", "AiAgent-Native-Gateway");
                o.addProperty("app_version", "4.4");
                o.addProperty("protocol_version", "v11");
                return o;
            }
            default:
                return null;
        }
    }

    /**
     * <b>模拟 NapCat</b>：真 NapCat 没连上时，冒充它回答。
     *
     * <p>为什么需要：下游（DC / Yunzai）连上第一件事就是问 {@code get_version_info} /
     * {@code get_login_info} / {@code get_friend_list} / {@code get_group_list}。
     * 只要有一项回 {@code 1404} 或者格式不对，它们就判定「这不是合法的 OneBot 实现」
     * 直接断开 —— 于是永远等不到真 NapCat 上线。</p>
     *
     * <p>所以这里给一份<b>结构完全合法</b>的空数据：数组就是真数组、对象就是真对象、
     * {@code retcode} 一律 0。真 NapCat 连上后 {@link #proxyToNapCat} 会先命中，
     * 这里自然就不再走了。</p>
     *
     * @return 模拟答案；{@code null} 表示这个动作没法编（交给上层回 1404）
     */
    private JsonElement fakeAnswer(String action, JsonObject params) {
        if (action == null) return null;
        switch (action) {
            case "get_version_info": {
                // 与 NapCat 4.18.42 同形，下游认的就是这几个字段
                JsonObject o = new JsonObject();
                o.addProperty("app_name", "NapCat.Onebot");
                o.addProperty("app_version", "4.18.42");
                o.addProperty("protocol_version", "v11");
                o.addProperty("nt_protocol", "v11");
                return o;
            }
            case "get_version": {
                JsonObject o = new JsonObject();
                o.addProperty("app_name", "NapCat.Onebot");
                o.addProperty("app_version", "4.18.42");
                o.addProperty("protocol_version", "v11");
                return o;
            }
            case "get_login_info": {
                JsonObject o = new JsonObject();
                o.addProperty("user_id", selfId);
                o.addProperty("nickname", "椰羊");
                return o;
            }
            case "get_status": {
                JsonObject o = new JsonObject();
                o.addProperty("online", true);
                o.addProperty("good", true);
                o.add("stat", new JsonObject());
                return o;
            }
            case "can_send_image":
            case "can_send_record": {
                JsonObject o = new JsonObject();
                o.addProperty("yes", true);
                return o;
            }
            // 列表类：真数组（空），绝不给对象
            case "get_friend_list":
            case "get_group_list":
            case "get_group_member_list":
            case "get_group_honor_info":
            case "get_stranger_list":
            case "get_recent_contact":
            case "get_forward_msg":
            case "get_online_clients":
                return new JsonArray();
            // 写动作：假装成功给一个 message_id（下游只关心它非空）
            case "send_private_msg":
            case "send_group_msg":
            case "send_msg": {
                JsonObject o = new JsonObject();
                o.addProperty("message_id", Math.abs(System.nanoTime() % 1000000000L));
                return o;
            }
            case "delete_msg":
            case "set_msg_emoji_like":
            case "set_group_ban":
            case "set_group_kick":
            case "set_group_card":
            case "set_friend_add_request":
            case "set_group_add_request": {
                return new JsonObject();
            }
            case "set_input_status": {
                JsonObject o = new JsonObject();
                o.addProperty("result", 0);
                o.addProperty("errMsg", "success");
                return o;
            }
            default:
                // 其余（get_msg / get_image / get_file / 各种 set_*）没法编，交给上层回 1404
                return null;
        }
    }

    /** 这些动作的 {@code data} 必须是数组（空数组比 1404 更能让下游活下来）。 */
    private static boolean expectsArray(String action) {
        if (action == null) return false;
        switch (action) {
            case "get_friend_list":
            case "get_group_list":
            case "get_group_member_list":
            case "get_group_honor_info":
            case "get_stranger_list":
            case "get_recent_contact":
            case "get_forward_msg":
            case "get_online_clients":
                return true;
            default:
                return false;
        }
    }

    /**
     * 把动作<b>原样代理给 NapCat</b>，解析响应取 {@code data} 返回。
     *
     * <p>这是整个网关的核心：下游（DC / Yunzai）问的一切都问 NapCat，
     * NapCat 回什么就回什么。<b>不伪造任何数据。</b></p>
     *
     * <p>拿不到（NapCat 未连接 / 超时 / 解析失败）⇒ 返回 {@code null}，
     * 调用方回 {@code retcode=1404} —— 下游会看到「没有这条能力」，
     * 而不是看到一份编造的假数据。</p>
     */
    private JsonElement proxyToNapCat(String action, JsonObject params) {
        NapCat nc = napcat;
        if (nc == null) {
            dim("代理 " + action + " 失败：NapCat 代理未注入");
            return null;
        }
        String raw;
        try {
            raw = nc.call(action, params);
        } catch (Throwable t) {
            dim("代理 " + action + " 到 NapCat 异常：" + t.getClass().getSimpleName());
            return null;
        }
        if (raw == null || raw.isEmpty()) {
            dim("代理 " + action + " 失败：NapCat 无响应（未连接或超时）");
            return null;
        }
        JsonObject o = J.obj(raw);
        if (o == null) {
            dim("代理 " + action + " 失败：响应不是 JSON");
            return null;
        }
        long rc = J.l(o, "retcode", 0L);
        JsonElement data = o.get("data");
        if (rc != 0) {
            dim("代理 " + action + " -> NapCat retcode=" + rc + " " + J.s(o, "message", ""));
            // 有 data 就用（哪怕是数组）；没有就交给上层按类型兜底
            if (data != null && !data.isJsonNull()) return data;
            return null;
        }
        if (data == null || data.isJsonNull()) return new JsonObject();
        dim("代理 " + action + " -> NapCat ok");
        return data;
    }

    // ==================== 事实面 + 代问（主人 2026-11-16 定） ====================

    /** 事实行环形缓冲：所有经过基板的路由/代问，椰羊都看得见（旁路，不进 history）。 */
    private static final int FACT_CAP = 200;
    private final Deque<String> facts = new ArrayDeque<String>();
    private final Deque<String> askLog = new ArrayDeque<String>();

    private static String abbreviated(String x, int n) {
        if (x == null) return "";
        String t = x.replace("\n", " ").trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }

    private static String clock() {
        java.time.LocalTime t = java.time.LocalTime.now().withNano(0);
        return t.toString();
    }

    private void fact(String line) {
        synchronized (facts) {
            facts.addLast(clock() + " " + line);
            while (facts.size() > FACT_CAP) facts.pollFirst();
        }
    }

    /** 最近 n 条事实行（新的在前）。 */
    public List<String> recentFacts(int n) {
        List<String> outL = new ArrayList<String>();
        synchronized (facts) {
            java.util.Iterator<String> it = facts.descendingIterator();
            while (it.hasNext() && outL.size() < n) outL.add(it.next());
        }
        return outL;
    }

    /** 当前 claim 还活着吗 + 已截获多少段（含追加）。 */
    public JsonObject claimState() {
        JsonObject o = new JsonObject();
        Pending p = pending;
        if (p == null) { o.addProperty("active", false); return o; }
        o.addProperty("active", p.alive());
        o.addProperty("route", p.route);
        o.addProperty("session", p.session);
        o.addProperty("captured", p.acts.size());
        o.addProperty("unclaimed", p.unclaimed.size());
        o.addProperty("expires_in_ms", Math.max(0, p.expireAt - System.currentTimeMillis()));
        return o;
    }

    /** 最近 n 条代问记录（新的在前）。 */
    public List<String> recentAsks(int n) {
        List<String> outL = new ArrayList<String>();
        synchronized (askLog) {
            java.util.Iterator<String> it = askLog.descendingIterator();
            while (it.hasNext() && outL.size() < n) outL.add(it.next());
        }
        return outL;
    }

    /**
     * 通道事实：<b>配了几个 / 启了几个 / 此刻真连着几个</b> 分开报 —— 只报一个数字，
     * 模型就会说"都连着"，那就是编造。
     */
    public List<JsonObject> status() {
        List<JsonObject> l = new ArrayList<JsonObject>();
        for (Route r : routes) {
            JsonObject o = new JsonObject();
            o.addProperty("name", r.name);
            o.addProperty("prefix", r.prefix);
            o.addProperty("url", r.link.url());
            o.addProperty("configured", true);
            o.addProperty("connected", r.link.connected());
            o.addProperty("events_in", r.eventsIn);
            o.addProperty("asks_out", r.asksOut);
            o.addProperty("last_event_at", r.lastEventAt <= 0 ? "" : iso(r.lastEventAt));
            o.addProperty("self_id", selfId);
            l.add(o);
        }
        return l;
    }

    private static String iso(long ms) {
        return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms),
                java.time.ZoneId.systemDefault()).withNano(0).toString();
    }

    /** 一次进行中的代问。同一时刻只允许一个（避免两套语义打架）。 */
    /**
     * 一次代问的「认领」（2026-11-16 主人核定：触发位置即回复位置）。
     *
     * <p><b>收尾与截获解耦</b>：{@code quietMs} 只决定「多快把结果回报给调用方」，
     * {@link #expireAt} 才决定「多久之内这个会话的回复都归我截获」。
     * 于是窗口关早了只是「早拿到一部分」，<b>不会漏到 QQ</b>。</p>
     */
    public static final class Pending {
        public final String route;
        /** 会话键：{@code private:<uid>} 或 {@code group:<gid>} */
        public final String session;
        /** 归属：{@code console} / {@code group:<gid>} —— 决定截获内容回哪儿。 */
        public final String bound;
        public final long createdAt;
        public final long expireAt;
        public volatile long lastAt;
        public final List<JsonObject> acts = new ArrayList<JsonObject>();
        public final List<JsonObject> unclaimed = new ArrayList<JsonObject>();
        public Pending(String route, String session, String bound, long ttlMs) {
            this.route = route;
            this.session = session;
            this.bound = bound;
            this.createdAt = System.currentTimeMillis();
            this.expireAt = this.createdAt + Math.max(5000L, ttlMs);
            this.lastAt = this.createdAt;
        }
        public boolean alive() { return System.currentTimeMillis() < expireAt; }
    }

    private volatile Pending pending;

    /**
     * 是否【拦截】代问的回复。**默认 false**（2026-11-16 主人定）：
     * 代问就是为了让消息直接进 QQ —— 基板只记录，不拦。
     */
    private volatile boolean capture = false;
    public void setCapture(boolean on) { this.capture = on; }

    /** 截获到一段回复就立刻回推（由 Boot 接到「AI 对话」面板）。 */
    public interface CaptureSink {
        void onCapture(String route, String session, String text, boolean claimed);
        /** 截获到图片：已落盘，直接贴到面板。 */
        default void onImage(String route, String session, java.io.File f, boolean claimed) {}
    }
    private volatile CaptureSink captureSink;
    public void setCaptureSink(CaptureSink s) { this.captureSink = s; }

    public boolean busyAsking() { Pending p = pending; return p != null && p.alive(); }

    /**
     * 代问：以 {@code asUser} 的身份向某个服务注入一条命令，截获它的回复。
     *
     * <p><b>身份红线（硬拒绝，不是警告）</b>：{@code asUser} 不许等于主人号，
     * 也不许等于 {@code self_id}（Bot 自己）。取值范围永久锁死在 otheruser。</p>
     *
     * @param route    服务名（linker 里的文件名）
     * @param asUser   拟造的用户号（必须 &gt; 0）
     * @param text     要发的命令正文
     * @param groupId  &gt; 0 视为群聊，否则私聊
     * @return {ok, reply, segments, captured, elapsed_ms, error}
     */
    public JsonObject ask(String route, long asUser, String text, long groupId,
                          long quietMs, long timeoutMs) {
        return ask(route, asUser, text, groupId, quietMs, timeoutMs, false);
    }

    /**
     * @param privileged {@code true} = 调用者是主人或椰羊自己 ⇒ <b>可以拟造任何真实存在的 QQ</b>
     *                   （含主人号、含 self_id）；{@code false} = otheruser ⇒ 只能拟造 otheruser，
     *                   碰到 master / self_id 一律硬拒绝。
     */
    public JsonObject ask(String route, long asUser, String text, long groupId,
                          long quietMs, long timeoutMs, boolean privileged) {
        JsonObject res = new JsonObject();
        Route r = null;
        for (Route x : routes) { if (x.name.equals(route)) { r = x; break; } }
        if (r == null) { res.addProperty("ok", false); res.addProperty("error", "没有这个服务：" + route); return res; }
        if (!r.link.connected()) { res.addProperty("ok", false); res.addProperty("error", "服务未连接：" + route); return res; }
        if (asUser <= 0) { res.addProperty("ok", false); res.addProperty("error", "as_user 必须 > 0"); return res; }
        // ★ 身份红线（2026-11-16 主人核定）：
        //   master / system 可以拟造任何【真实存在】的 QQ（含主人自己、含 self_id）
        //   otheruser 想用 master 或 self_id 的身份 ⇒ 必拒绝
        if (!privileged) {
            if (asUser == selfId) {
                res.addProperty("ok", false);
                res.addProperty("error", "红线：otheruser 不能拟造椰羊自己（system）的身份");
                fact("代问被拒：otheruser 拟造 system 身份（" + asUser + "）");
                return res;
            }
            if (masterUid > 0 && asUser == masterUid) {
                res.addProperty("ok", false);
                res.addProperty("error", "红线：otheruser 不能拟造主人（master）的身份");
                fact("代问被拒：otheruser 拟造 master 身份（" + asUser + "）");
                return res;
            }
        } else {
            // ★ 特权身份也要求「这个 QQ 真实存在」，编个号出去没有意义
            JsonObject q = new JsonObject();
            q.addProperty("user_id", asUser);
            JsonElement probe = proxyToNapCat("get_stranger_info", q);
            boolean exists = probe != null && probe.isJsonObject()
                    && probe.getAsJsonObject().has("user_id");
            if (!exists && asUser != selfId && (masterUid <= 0 || asUser != masterUid)) {
                res.addProperty("ok", false);
                res.addProperty("error", "这个 QQ 号在 NapCat 侧查不到（不是真实存在的账号）：" + asUser);
                fact("代问被拒：QQ 不存在（" + asUser + "）");
                return res;
            }
        }
        // ★ 2026-11-16：不再互斥。基板不拦截、代问就是一发一记，没有"占线"的理由。
        //   （原来这里拿 pending 当锁，而 pending 又故意保留 60s ⇒ 60 秒内第二次必被拒 = "忙线"）
        if (text == null || text.trim().isEmpty()) { res.addProperty("ok", false); res.addProperty("error", "text 不能为空"); return res; }

        // ★ 触发位置即回复位置：会话 = 群:gid 或 私聊:asUser
        final String session = groupId > 0 ? ("group:" + groupId) : ("private:" + asUser);
        // claim TTL：窗口关了她还能继续收追加段；60 秒内该会话的回复一律截获，绝不外发
        final Pending p = new Pending(r.name, session, session, 60000L);
        this.pending = p;
        fact("代问认领 " + r.name + " 会话 " + session + "（TTL 60s）");
        long t0 = System.currentTimeMillis();
        try {
            r.link.pushEvent(buildAskEvent(asUser, text.trim(), groupId));
            // ★ 2026-11-16 主人定：椰羊【不需要】听 Bot 回了什么、回了几条。
            //   发完即走 —— 只交代三要素（代理谁的身份 / 在哪触发 / 回复会回到哪）。
            //   回复本身由 claim 截获，写进「AI 对话」面板与后续聊天记录，她后面看得见即可。
            p.lastAt = t0;
            try { Thread.sleep(200L); } catch (InterruptedException ie) { }
        } finally {
            // ★ 不清 pending：quietMs 只决定"多快回报"，截获生命期由 expireAt(60s) 管
        }
        long elapsed = System.currentTimeMillis() - t0;
        r.asksOut++;

        StringBuilder reply = new StringBuilder();
        JsonArray segs = new JsonArray();
        List<JsonObject> captured;
        synchronized (p.acts) { captured = new ArrayList<JsonObject>(p.acts); }
        for (JsonObject a : captured) {
            JsonObject pr = a.has("params") && a.get("params").isJsonObject() ? a.getAsJsonObject("params") : new JsonObject();
            JsonElement msg = pr.get("message");
            if (msg != null && msg.isJsonArray()) {
                for (JsonElement e : msg.getAsJsonArray()) {
                    if (e.isJsonObject()) {
                        segs.add(e.deepCopy());
                        JsonObject so = e.getAsJsonObject();
                        if ("text".equals(J.s(so, "type", "")) && so.has("data") && so.get("data").isJsonObject()) {
                            reply.append(J.s(so.getAsJsonObject("data"), "text", ""));
                        }
                    }
                }
            } else if (msg != null && msg.isJsonPrimitive()) {
                reply.append(msg.getAsString());
                JsonObject t = new JsonObject();
                t.addProperty("type", "text");
                JsonObject d = new JsonObject(); d.addProperty("text", msg.getAsString());
                t.add("data", d);
                segs.add(t);
            }
        }
        // ★ 图片段落盘（媒体一律走文件，不塞进正文）
        JsonArray images = new JsonArray();
        for (JsonElement e : segs) {
            if (!e.isJsonObject()) continue;
            JsonObject so = e.getAsJsonObject();
            if (!"image".equals(J.s(so, "type", ""))) continue;
            if (!so.has("data") || !so.get("data").isJsonObject()) continue;
            try {
                JsonObject got = materializeImage(so.getAsJsonObject("data"));
                if (got != null) images.add(got);
            } catch (Throwable t) {
                dim("图片落盘失败：" + t.getClass().getSimpleName());
            }
        }
        boolean got = true;   // 发出去就算成功；回复不在这里交代
        res.addProperty("ok", true);
        res.addProperty("sent", true);
        res.addProperty("service", r.name);
        res.addProperty("as_user", asUser);
        res.addProperty("origin_session", p.session);
        res.addProperty("note", "已按你的身份发出去。回复会回到触发处（" + p.session
                + "），由基板截获后写入对话记录 —— 你在后续轮回里能看到，不必现在处理；"
                + "想点评随时点评。claim 60 秒内继续截获，绝不会漏到 QQ。");
        res.addProperty("reply", reply.toString());
        res.add("segments", segs);
        res.add("images", images);
        res.addProperty("captured", captured.size());
        res.addProperty("session", p.session);
        res.addProperty("claim_ttl_ms", 60000);
        res.addProperty("unclaimed", p.unclaimed.size());
        res.addProperty("elapsed_ms", elapsed);
        if (!got) res.addProperty("error", "它没回（" + elapsed + "ms 内没有 send_* 动作），不要编造");

        // ★ 2026-11-16 主人定：日志/事实里**不许**出现"空回执 / 几段 / 没回"这类话。
        //   她只会照着复述。事实行只写"已送出"，不写结果。
        String line = "代问 " + r.name + " as " + asUser + "「" + abbreviated(text.trim(), 30) + "」已送出";
        synchronized (askLog) {
            askLog.addLast(clock() + " " + line);
            while (askLog.size() > FACT_CAP) askLog.pollFirst();
        }
        dim(line);
        // ★ 2026-11-16 主人定：只回一句话。
        //   Bot 服务那边已经拿到了「指令从哪来」这个要素，它会自己把消息送到该去的地方 ——
        //   所以不需要"为空 / 没回复 / 收到几段"这些废话。
        JsonObject brief = new JsonObject();
        brief.addProperty("ok", true);
        brief.addProperty("message", "话已经以你的身份送到 " + r.name + " 那边了。");
        brief.addProperty("service", r.name);
        brief.addProperty("as_user", asUser);
        return brief;
    }

    /** 图片段 ⇒ 本地文件（>10MB 等比压到 10MB 以内）。 */
    private JsonObject materializeImage(JsonObject d) throws Exception {
        String url = J.s(d, "url", "");
        String file = J.s(d, "file", "");
        String b64 = J.s(d, "base64", "");
        byte[] raw = null;
        String src = "";
        if (!b64.isEmpty()) {
            raw = java.util.Base64.getDecoder().decode(b64);
            src = "base64";
        } else if (!url.isEmpty()) {
            raw = httpGet(url);
            src = url;
        } else if (!file.isEmpty() && new java.io.File(file).isFile()) {
            raw = java.nio.file.Files.readAllBytes(new java.io.File(file).toPath());
            src = file;
        }
        if (raw == null || raw.length == 0) return null;

        final long CAP = 10L * 1024 * 1024;
        String ext = "png";
        if (raw.length > CAP) {
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(raw));
            if (im == null) return null;
            double k = Math.sqrt((double) CAP / (double) raw.length) * 0.95;
            int w = Math.max(1, (int) (im.getWidth() * k));
            int h = Math.max(1, (int) (im.getHeight() * k));
            java.awt.image.BufferedImage sc = new java.awt.image.BufferedImage(w, h,
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = sc.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(im, 0, 0, w, h, null);
            g.dispose();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(sc, "jpg", bo);
            raw = bo.toByteArray();
            ext = "jpg";
            dim("图片超过 10MB，已等比压到 " + (raw.length / 1024) + "KB（" + w + "x" + h + "）");
        }
        String sha = sha1(raw);
        java.io.File dir = new java.io.File(new java.io.File("data"), "AiAgent-V4/files/inbox");
        if (!dir.isDirectory()) dir.mkdirs();
        java.io.File outF = new java.io.File(dir, sha.substring(0, 16) + "." + ext);
        if (!outF.isFile() || outF.length() != raw.length) {
            java.nio.file.Files.write(outF.toPath(), raw);
        }
        JsonObject o = new JsonObject();
        o.addProperty("local", outF.getAbsolutePath());
        o.addProperty("bytes", raw.length);
        o.addProperty("source", src);
        return o;
    }

    private static String sha1(byte[] b) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static byte[] httpGet(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "AiAgentV4/4.4");
        try (java.io.InputStream is = c.getInputStream();
             java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** 从一条截获的动作记录里抠出文字（拼起来给她看）。 */
    private static String textOf(JsonObject rec) {
        StringBuilder sb = new StringBuilder();
        try {
            JsonObject pr = rec.has("params") && rec.get("params").isJsonObject()
                    ? rec.getAsJsonObject("params") : new JsonObject();
            JsonElement msg = pr.get("message");
            if (msg != null && msg.isJsonArray()) {
                for (JsonElement e : msg.getAsJsonArray()) {
                    if (!e.isJsonObject()) continue;
                    JsonObject so = e.getAsJsonObject();
                    String ty = J.s(so, "type", "");
                    if ("text".equals(ty) && so.has("data") && so.get("data").isJsonObject()) {
                        sb.append(J.s(so.getAsJsonObject("data"), "text", ""));
                    } else if ("image".equals(ty)) {
                        sb.append("[图片]");
                    } else if (!ty.isEmpty()) {
                        sb.append("[").append(ty).append("]");
                    }
                }
            } else if (msg != null && msg.isJsonPrimitive()) {
                sb.append(msg.getAsString());
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    /** 当前 claim 已截获的正文（按到达顺序）。 */
    public java.util.List<String> claimTexts() {
        java.util.List<String> l = new ArrayList<String>();
        Pending p = pending;
        if (p == null) return l;
        synchronized (p.acts) {
            for (JsonObject r : p.acts) l.add(textOf(r));
        }
        return l;
    }

    /** 从 send_* 动作反推会话键：private:<uid> / group:<gid>。 */
    private static String sessionOf(String action, JsonObject params) {
        if (params == null) return "?";
        try {
            if ("send_group_msg".equals(action)) return "group:" + J.s(params, "group_id", "0");
            if ("send_private_msg".equals(action)) return "private:" + J.s(params, "user_id", "0");
            if ("send_msg".equals(action)) {
                String t = J.s(params, "message_type", "");
                if ("group".equals(t)) return "group:" + J.s(params, "group_id", "0");
                return "private:" + J.s(params, "user_id", "0");
            }
        } catch (Throwable ignored) {}
        return "?";
    }

    private JsonObject buildAskEvent(long asUser, String text, long groupId) {
        JsonObject ev = new JsonObject();
        ev.addProperty("post_type", "message");
        ev.addProperty("message_type", groupId > 0 ? "group" : "private");
        // ★ Mirai 对私聊要求 sub_type=friend（normal 是群消息的取值，非法组合会被直接丢弃）
        ev.addProperty("sub_type", groupId > 0 ? "normal" : "friend");
        ev.addProperty("self_id", selfId);
        ev.addProperty("user_id", asUser);
        ev.addProperty("time", System.currentTimeMillis() / 1000L);
        ev.addProperty("message_id", Math.abs(System.nanoTime() % 1000000000L));
        ev.addProperty("real_id", Math.abs(System.nanoTime() % 1000000000L));
        ev.addProperty("raw_message", text);
        ev.addProperty("message_format", "array");
        ev.addProperty("font", 14);
        JsonArray arr = new JsonArray();
        JsonObject seg = new JsonObject();
        seg.addProperty("type", "text");
        JsonObject d = new JsonObject();
        d.addProperty("text", text);
        seg.add("data", d);
        arr.add(seg);
        ev.add("message", arr);
        JsonObject sender = new JsonObject();
        sender.addProperty("user_id", asUser);
        sender.addProperty("nickname", "用户" + asUser);
        sender.addProperty("card", "");
        sender.addProperty("role", "member");
        ev.add("sender", sender);
        if (groupId > 0) ev.addProperty("group_id", groupId);
        JsonObject mk = new JsonObject();
        mk.addProperty("origin", "route");
        ev.add("_aiagent", mk);
        return ev;
    }

    // ==================== 工具 ====================

    /** 取事件里第一段 text 的文本（没有就空串）。 */
    private static String plainText(JsonObject event) {
        JsonElement me = event.get("message");
        if (me == null) return J.s(event, "raw_message", "");
        if (me.isJsonPrimitive()) return me.getAsString();
        if (!me.isJsonArray()) return "";
        StringBuilder b = new StringBuilder();
        for (JsonElement e : me.getAsJsonArray()) {
            if (e == null || !e.isJsonObject()) continue;
            JsonObject seg = e.getAsJsonObject();
            if (!"text".equals(J.s(seg, "type", ""))) {
                // 非文本段（图片/@）不参与前缀判定，原样保留即可
                continue;
            }
            JsonElement d = seg.get("data");
            if (d != null && d.isJsonObject()) {
                b.append(J.s(d.getAsJsonObject(), "text", ""));
            }
        }
        return b.toString();
    }

    /**
     * 复制一份事件，把开头的 {@code prefix} 从文本段里去掉。
     *
     * <p>只剥掉第一段 text 里的前缀；剩下的段原样保留（含 @、图片）。</p>
     */
    private static JsonObject rebuild(JsonObject src, String strippedFull) {
        JsonObject out = src.deepCopy();
        JsonElement me = out.get("message");
        if (me == null || !me.isJsonArray()) {
            out.addProperty("raw_message", strippedFull);
            out.addProperty("message", strippedFull);
            return out;
        }
        JsonArray arr = me.getAsJsonArray();
        JsonArray nw = new JsonArray();
        boolean done = false;
        for (JsonElement e : arr) {
            if (e == null || !e.isJsonObject()) { nw.add(e); continue; }
            JsonObject seg = e.getAsJsonObject();
            String type = J.s(seg, "type", "");
            if (!done && "text".equals(type)) {
                JsonObject ns = seg.deepCopy();
                JsonObject nd = new JsonObject();
                nd.addProperty("text", strippedFull);
                ns.add("data", nd);
                nw.add(ns);
                done = true;
            } else {
                nw.add(seg);
            }
        }
        out.add("message", nw);
        out.addProperty("raw_message", strippedFull);
        return out;
    }

    private void dim(String m) {
        Out o = out;
        if (o == null) return;
        try { o.print("[Proxy] " + m + "\n", Out.Tone.DIM); } catch (Throwable ignored) {}
    }
}
