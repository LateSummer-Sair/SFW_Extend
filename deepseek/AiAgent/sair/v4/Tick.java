package sair.v4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

import sair.v4.agent.Agent;
import sair.v4.auth.Caller;
import sair.v4.term.Sinks;
import sair.v4.ctx.Sink;
import sair.v4.hot.Skills;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.kit.Th;
import sair.v4.qq.Api;
import sair.v4.store.Store;

/**
 * 基板唯一的周期 tick（④ 调度）：
 * <ol>
 *   <li>派发 {@code on_timer} 钩子（一切"每隔一会儿做点什么"都挂这里，不再各起线程）；</li>
 *   <li>检查到点的定时唤醒任务（alarm），到点就唤起主 Agent 跑一轮并把结果送回目标会话。</li>
 * </ol>
 */
public final class Tick {

    private final Conf conf;
    private final Out out;
    private final Store store;
    private final Skills skills;
    private final Agent agent;
    private final Api api;
    /** 分段发送口径（闹钟/后台任务的结果也按同一条规矩分条发）。 */
    private final sair.v4.term.Segmenter seg;

    private volatile boolean running = false;
    private volatile long n = 0;
    private volatile long lastMaintain = 0;

    public Tick(Conf conf, Out out, Store store, Skills skills, Agent agent, Api api) {
        this.conf = conf;
        this.out = out;
        this.store = store;
        this.skills = skills;
        this.agent = agent;
        this.seg = new sair.v4.term.Segmenter(conf);
        this.api = api;
    }

    public void start() {
        if (running) return;
        running = true;
        long period = Math.max(1000, conf.tickMs());
        Th.every("v4-tick", period, period, new Runnable() {
            @Override
            public void run() {
                beat();
            }
        });
        if (out != null) out.dim("[tick] 已启动，周期 " + period + "ms（on_timer 钩子 + 定时唤醒）");
    }

    public void stop() { running = false; }

    public boolean running() { return running; }

    public long count() { return n; }

    /** 一次心跳。 */
    public void beat() {
        if (!running) return;
        long c = ++n;
        try {
            if (skills != null) {
                skills.tick();
                JsonObject p = new JsonObject();
                p.addProperty("tick", c);
                p.addProperty("ts", System.currentTimeMillis());
                skills.dispatch(Skills.ON_TIMER, p);
            }
        } catch (Throwable t) {
            if (out != null) out.err("[tick] on_timer 派发失败: " + t);
        }
        try {
            fireDue();
        } catch (Throwable t) {
            if (out != null) out.err("[tick] 定时唤醒检查失败: " + t);
        }
        try {
            maybeMaintain();
        } catch (Throwable t) {
            if (out != null) out.err("[tick] 六库维护失败: " + t);
        }
    }

    /**
     * 六库自我维护（基板①的自家卫生）：按 {@code maintainEveryHours}（默认 24，&lt;=0 关闭）
     * 清理超龄低重要度记忆、超龄对话/群聊日志，外加清掉过期的已停用闹钟与陈旧任务台账。
     * 上次维护时间落在 kv 里（重启不会立刻再跑一遍）。策略参数走配置，机制在基板。
     *
     * <p><b>三把独立的尺子</b>（见 {@link Conf}）：{@code keepDays} 只管记忆/笔记类，
     * {@code dialogKeepDays} 管私聊/控制台对话，{@code grouplogKeepDays} 管群聊历史。
     * 记忆与聊天记录的量级差三个数量级，绑在一起就只能二选一。</p>
     */
    private void maybeMaintain() {
        if (store == null) return;
        int hours = conf.maintainEveryHours();
        if (hours <= 0) return;
        long now = System.currentTimeMillis();
        if (lastMaintain <= 0) {
            String prev = store.kv("last_maintain", "");
            try {
                lastMaintain = Long.parseLong(prev.trim());
            } catch (Exception ignored) {
                lastMaintain = 0;
            }
        }
        if (lastMaintain > 0 && now - lastMaintain < hours * 3600_000L) return;
        lastMaintain = now;
        store.kvSet("last_maintain", String.valueOf(now));
        JsonObject r = maintainNow();
        if (out != null) out.dim("[tick] 六库维护 " + J.json(r));
    }

    /**
     * 立刻跑一次维护，<b>不看</b> {@code maintainEveryHours} 间隔、也不改 {@code last_maintain}
     * （间隔那段逻辑在 {@link #maybeMaintain}）：给手动触发与探针用，行为与定时那次逐字一致。
     *
     * <p>三档保留期分别取 {@link Conf#keepDays()} / {@link Conf#dialogKeepDays()} /
     * {@link Conf#grouplogKeepDays()}；后两档 {@code <=0} = 不过期。
     * 停用闹钟与已完成/中断任务台账跟记忆共用 {@code keepDays}。</p>
     */
    public JsonObject maintainNow() {
        if (store == null) return new JsonObject();
        long now = System.currentTimeMillis();
        JsonObject r = store.maintain(conf.keepDays(), conf.dialogKeepDays(), conf.grouplogKeepDays(),
                conf.maxLowImportance());
        // 运行表也要清：停用超过 keepDays 的闹钟、完成/中断超过 keepDays 的任务
        long cutoff = now - Math.max(1, conf.keepDays()) * 86400_000L;
        int alarms = store.db().delete("alarm", "enabled = 0 AND fire_at < ?", cutoff);
        int tasks = store.db().delete("task", "status <> 'running' AND ts < ?", cutoff);
        r.addProperty("alarm_deleted", alarms);
        r.addProperty("task_deleted", tasks);
        // 标记孤儿：grouplog 被保留期清掉之后，指向它的标记（mark:<session>/<msg_id>）也一起清。
        // 只清"群会话 + msg_id 在 grouplog 里已经不存在"的那些；文字目标、私聊标记、legacy 一律保留。
        try {
            int orphans = markOrphans();
            r.addProperty("mark_orphan_deleted", orphans);
        } catch (Throwable t) {
            if (out != null) out.warn("[tick] 标记孤儿清理失败: " + t);
        }
        return r;
    }

    /** 清掉指向已不存在的 {@code grouplog.msg_id} 的标记（返回清理条数）。 */
    private int markOrphans() {
        if (store == null || store.db() == null) return 0;
        List<JsonObject> rows = store.kvList("mark:", 2000);
        if (rows == null || rows.isEmpty()) return 0;
        int n = 0;
        for (JsonObject row : rows) {
            String k = J.s(row, "k", "");
            int slash = k.lastIndexOf('/');
            if (slash < 0) continue;                                  // 不是 <前缀><session>/<target> 形状：不碰
            String target = k.substring(slash + 1);
            String sess = k.substring("mark:".length(), slash);
            if (!sess.startsWith("group_")) continue;                 // 私聊会话的 id 不是 grouplog 的 id：不碰
            if (!target.matches("\\d+")) continue;                    // 文字目标 = legacy：保留
            long mid = Long.parseLong(target);
            if (store.db().count("grouplog", "msg_id = ?", mid) > 0) continue;
            if (store.kvDelete(k, false) > 0) n++;
        }
        return n;
    }

    // ==================== 定时唤醒（alarm） ====================

    /**
     * 建一个定时唤醒。{@code creator} 是发起者：会在闹钟行里记下来，
     * 到点时**以发起者的身份**跑（非主人建的闹钟绝不以主人身份跑）。
     */
    public JsonObject add(long fireAt, String repeat, String scope, long target,
                          String task, String prompt, Caller creator) {
        JsonObject row = new JsonObject();
        row.addProperty("ts", System.currentTimeMillis());
        row.addProperty("fire_at", fireAt);
        row.addProperty("repeat", Str.blank(repeat) ? "once" : repeat.trim());
        row.addProperty("scope", Str.blank(scope) ? "console" : scope.trim());
        row.addProperty("target", target);
        row.addProperty("task", task == null ? "" : task);
        row.addProperty("prompt", prompt == null ? "" : prompt);
        row.addProperty("enabled", 1);
        row.addProperty("state", "pending");
        row.addProperty("owner", ownerJson(creator));
        long id = store == null ? 0 : store.upsertAlarm(row);
        row.addProperty("id", id);
        return row;
    }

    /** 归属信息（存进 alarm.owner 列）：会话 + QQ + 群 + 是否主人。 */
    public static String ownerJson(Caller c) {
        JsonObject o = new JsonObject();
        if (c == null) return "";
        o.addProperty("session", c.session());
        o.addProperty("qq", c.qq());
        o.addProperty("group", c.groupId());
        o.addProperty("master", c.master());
        o.addProperty("name", c.name());
        return J.json(o);
    }

    /** 谁建的（主人 / 某个会话）。 */
    public static JsonObject ownerOf(JsonObject alarm) {
        JsonObject o = J.obj(J.s(alarm, "owner", ""));
        if (o == null) o = new JsonObject();
        return o;
    }

    /** 该闹钟是否属于这个调用者（主人可见全部；无归属的旧行只对主人可见）。 */
    public static boolean ownedBy(JsonObject alarm, Caller c) {
        if (c == null) return false;
        if (c.master()) return true;
        JsonObject o = ownerOf(alarm);
        String s = J.s(o, "session", "");
        return Str.has(s) && s.equals(c.session());
    }

    public List<JsonObject> alarms() {
        return store == null ? new java.util.ArrayList<JsonObject>() : store.listAlarms(false);
    }

    /** 该调用者能看到的闹钟（主人看全部）。 */
    public List<JsonObject> alarms(Caller c) {
        List<JsonObject> out = new java.util.ArrayList<JsonObject>();
        for (JsonObject a : alarms()) {
            if (ownedBy(a, c)) out.add(a);
        }
        return out;
    }

    public JsonArray alarmsJson(Caller c) {
        JsonArray a = new JsonArray();
        for (JsonObject o : alarms(c)) a.add(o);
        return a;
    }

    public JsonArray alarmsJson() { return alarmsJson(null); }

    /** 删除：只能删自己建的（主人可删任何）。 */
    public boolean remove(long id, Caller c) {
        if (store == null) return false;
        for (JsonObject a : alarms()) {
            if (J.l(a, "id", 0) == id) {
                if (!ownedBy(a, c)) return false;
                return store.deleteAlarm(id);
            }
        }
        return false;
    }

    public boolean remove(long id) { return remove(id, null); }

    /** 清空：只清自己的（主人清全部）。 */
    public int clear(Caller c) {
        int n = 0;
        for (JsonObject o : alarms()) {
            if (!ownedBy(o, c)) continue;
            if (store.deleteAlarm(J.l(o, "id", 0))) n++;
        }
        return n;
    }

    public int clear() { return clear(null); }

    /** 到点即唤起（每次 tick 调一次）。 */
    public int fireDue() {
        if (store == null) return 0;
        // 防御性：读定时任务（alarm）= 她自己的自主行为（SYSTEM C:RWX）。先按 ACL 判 C 类 R 位再读 ——
        // 证明基板扫闹钟不会被默认 OTHER 位误伤；被拦（理论上不会）则留日志、本轮不查。
        String deny = sair.v4.auth.Acl.empty(null).allow(
                Caller.systemActor(conf.masterQQ()), sair.v4.auth.Res.db("alarm"), 'R');
        if (deny != null) {
            if (out != null) out.warn("[auth] 自主读任务被拦（本轮不查闹钟）：" + deny);
            return 0;
        }
        long now = System.currentTimeMillis();
        int fired = 0;
        for (JsonObject a : alarms()) {
            if (J.i(a, "enabled", 1) != 1) continue;
            long fireAt = J.l(a, "fire_at", 0);
            if (fireAt <= 0 || fireAt > now) continue;
            final long id = J.l(a, "id", 0);
            final String task = J.s(a, "task", "");
            final String scope = J.s(a, "scope", "console");
            final long target = J.l(a, "target", 0);
            final String repeat = J.s(a, "repeat", "once");
            final long next = nextFire(fireAt, repeat, now);
            // 先把下一次时间写好（once 直接停用），避免任务执行期间重复触发
            if (next > 0) {
                store.db().exec("UPDATE alarm SET fire_at=?, state='pending' WHERE id=?", next, id);
            } else {
                store.db().exec("UPDATE alarm SET enabled=0, state='fired' WHERE id=?", id);
            }
            fired++;
            final String prompt = J.s(a, "prompt", "");
            final String text = Str.has(prompt) ? prompt + "\n" + task : task;
            // 以发起者的身份跑：非主人建的闹钟绝不以主人身份执行（否则等于远程提权）
            final JsonObject owner = ownerOf(a);
            final boolean ownerMaster = J.b(owner, "master", false);
            final String ownerSession = J.s(owner, "session", "");
            final long ownerQq = J.l(owner, "qq", 0);
            final long ownerGroup = J.l(owner, "group", 0);
            if (out != null) out.dim("[tick] 定时唤醒 #" + id + " → " + Str.cut(Str.oneLine(text), 80));
            // 排进"按会话分道"的回合队列：以发起者的会话为道（同会话保序），
            // 主人建的闹钟走高优先级道，与用户消息一样的排队规矩（不再另起线程抢模型）
            final String alarmSession = Str.blank(ownerSession) ? "console" : ownerSession;
            final boolean alarmGroup = "group".equalsIgnoreCase(scope);
            final long alarmTarget = "console".equalsIgnoreCase(scope) ? 0L : target;
            final Sink alarmSink = alarmTarget > 0 ? new Sinks.TaskSink(api, out, alarmGroup, alarmTarget, false, seg)
                                                   : new Sinks.ConsoleSink(out);
            agent.submit(alarmSession, ownerMaster, new Runnable() {
                @Override
                public void run() {
                    try {
                        boolean group = alarmGroup;
                        long t = alarmTarget;
                        Sink sink = alarmSink;
                        Caller c;
                        if (ownerMaster) {
                            c = "console".equalsIgnoreCase(scope)
                                    ? Caller.console(conf.masterQQ())
                                    : new Caller(Caller.Entry.QQ, group ? 0 : t, group ? t : 0, "", true, 0, "");
                        } else {
                            // 非主人：身份就是他自己（favor 现查，权限照旧受限）
                            c = new Caller(Caller.Entry.QQ, ownerQq, group ? (ownerGroup > 0 ? ownerGroup : t) : 0,
                                    "", false, store == null ? 0 : store.favor(ownerQq), Str.nz(ownerSession));
                        }
                        agent.ask(c, sink, text);
                    } catch (Throwable e) {
                        if (out != null) out.err("[tick] 定时任务 #" + id + " 执行失败: " + e);
                    }
                }
            }, sair.v4.schedule.SimpleJob.sink(alarmSink));
        }
        return fired;
    }

    /** 下一次触发时间；0 表示不再触发。 */
    public static long nextFire(long fireAt, String repeat, long now) {
        if (Str.blank(repeat)) return 0;
        String r = repeat.trim().toLowerCase();
        if ("once".equals(r)) return 0;
        long step;
        if ("daily".equals(r)) step = 24L * 3600 * 1000;
        else if ("weekly".equals(r)) step = 7L * 24 * 3600 * 1000;
        else if (r.startsWith("every:")) {
            int min = 30;
            try {
                min = Integer.parseInt(r.substring(6).trim());
            } catch (Exception ignored) {
            }
            step = Math.max(1, min) * 60L * 1000L;
        } else {
            return 0;
        }
        long next = fireAt + step;
        while (next <= now) next += step;
        return next;
    }
}
