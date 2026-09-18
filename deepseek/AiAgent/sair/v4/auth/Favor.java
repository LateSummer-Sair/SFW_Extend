package sair.v4.auth;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sair.v4.kit.J;

/**
 * 好感度子系统：<b>她对每个人的"关系值"</b>（主人裁 2026-09-17）。
 *
 * <p>它<b>不判任何权限</b>（P9b-2 起旧档位体系与好感度门禁已整批删除，见 D13/D34）：
 * 数值只表达"她跟这个人处得怎么样"，谁可以用她多少工具由 ACL 账本说了算，两件事互不相干。</p>
 *
 * <p>谁改它：① <b>她自己</b>——{@link #change} 只接受主人裁定的单次区间（加 {@code [1,3]}、
 * 减 {@code [5,10]}，越界基板夹死），由她在回复里用 {@code <favor …/>} 表达；
 * ② <b>主人</b>——{@link #setValue} 直接定值，不受区间限制（控制台 {@code ai/perm set} 走同一条）。
 * 每一次改动都写一行流水（{@code favor_event}），数值要能说出"谁改的、改了多少、为什么"。</p>
 *
 * <p>每个人第一次跟她打交道就建一行（0 也是记录，见 {@link #ensure}）——这样谁都能问
 * "我的好感度是多少"，而说不说由她定。</p>
 */
public final class Favor implements sair.v4.skill.FavorView {

    /** 她单次<b>加</b>好感度的区间（主人裁 2026-09-17：不低于 1、不高于 3）。 */
    public static final double ADD_MIN = 1.0D;
    public static final double ADD_MAX = 3.0D;
    /** 她单次<b>减</b>好感度的区间（同裁：不低于 5、不高于 10）。 */
    public static final double SUB_MIN = 5.0D;
    public static final double SUB_MAX = 10.0D;

    private final FavorStore store;
    private final Map<Long, Double> cache = new ConcurrentHashMap<Long, Double>();
    /** 本次运行里已经建过账/写过账的 QQ（只省一次点查，重启后重建，不影响正确性）。 */
    private final Map<Long, Boolean> known = new ConcurrentHashMap<Long, Boolean>();

    public Favor(FavorStore store) {
        this.store = store;
    }

    /** 一次改动的回执（日志与工具结果用）。 */
    public static final class Change {
        public String op = "";
        public long qq;
        public double before;
        public double after;
        public double applied;
        public boolean clamped;
        public String why = "";
    }

    /** 取好感度（缓存）。 */
    public double of(long qq) {
        if (qq <= 0) return 0;
        Double v = cache.get(qq);
        if (v != null) return v;
        double d = 0;
        try {
            d = store == null ? 0 : store.favor(qq);
        } catch (Exception ignored) {
        }
        cache.put(qq, d);
        return d;
    }

    public void set(long qq, double value, String note) {
        if (qq <= 0) return;
        double v = Math.max(0, value);
        double before = of(qq);
        cache.put(qq, v);
        known.put(qq, Boolean.TRUE);
        try {
            if (store != null) {
                store.setFavor(qq, v, levelName(v), note == null ? "" : note);
                store.log(qq, v - before, v, "set", "exec", note == null ? "" : note);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 按人建账：这个人第一次跟她打交道就建一行（值 0），已有则一个字段都不动。
     * <p>只写一次（本进程内同一个人不再点查），所以群再吵也不会变成写放大。</p>
     */
    public void ensure(long qq) {
        if (qq <= 0L) return;
        if (known.putIfAbsent(qq, Boolean.TRUE) != null) return;
        try {
            if (store != null) store.ensure(qq);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 她自己的加减（{@code op} = {@code add} / {@code sub}）：幅度由基板<b>夹死</b>在主人裁定的
     * 区间里（加 {@code [1,3]}、减 {@code [5,10]}），值下界 0；写库 + 记一行流水。
     *
     * <p>夹取而不是拒绝：她说"减 20"，意图明确，夹到 10 比回一句"范围不对"更贴合她的意思；
     * 回执里带 {@code clamped} 标记，日志与工具结果都看得见。</p>
     */
    public Change change(long qq, double delta, String op, String why, String by) {
        Change r = new Change();
        r.op = "sub".equals(op) ? "sub" : "add";
        r.qq = qq;
        r.why = why == null ? "" : why;
        if (qq <= 0L || !(delta > 0.0D)) return r;
        boolean sub = "sub".equals(r.op);
        double d = delta;
        double lo = sub ? SUB_MIN : ADD_MIN;
        double hi = sub ? SUB_MAX : ADD_MAX;
        if (d < lo) {
            d = lo;
            r.clamped = true;
        }
        if (d > hi) {
            d = hi;
            r.clamped = true;
        }
        r.before = of(qq);
        r.after = Math.max(0.0D, r.before + (sub ? -d : d));
        r.applied = r.after - r.before;
        cache.put(qq, r.after);
        known.put(qq, Boolean.TRUE);
        try {
            if (store != null) {
                store.setFavor(qq, r.after, levelName(r.after), r.why);
                store.log(qq, r.applied, r.after, r.op, by, r.why);
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    /**
     * 直接定值 —— <b>只有主人</b>能走这条（身份判定在执行口，不在这里）：不受单次区间限制。
     * <p>主人裁的"主人拥有让它修改任何人好感度直接数值的能力"就落在这一支。</p>
     */
    public Change setValue(long qq, double value, String why, String by) {
        Change r = new Change();
        r.op = "set";
        r.qq = qq;
        r.why = why == null ? "" : why;
        if (qq <= 0L || value < 0.0D) return r;
        r.before = of(qq);
        r.after = value;
        r.applied = r.after - r.before;
        cache.put(qq, r.after);
        known.put(qq, Boolean.TRUE);
        try {
            if (store != null) {
                store.setFavor(qq, r.after, levelName(r.after), r.why);
                store.log(qq, r.applied, r.after, "set", by, r.why);
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    /** 某人的好感度流水（新 → 旧）。 */
    public List<JsonObject> events(long qq, int limit) {
        try {
            return store == null ? new ArrayList<JsonObject>() : store.events(qq, limit);
        } catch (Exception e) {
            return new ArrayList<JsonObject>();
        }
    }

    public void add(long qq, double delta, String note) {
        if (qq <= 0 || delta == 0) return;
        set(qq, of(qq) + delta, note);
    }

    public void invalidate(long qq) { cache.remove(qq); }

    public int resetAll() {
        cache.clear();
        try {
            return store == null ? 0 : store.clear();
        } catch (Exception e) {
            return 0;
        }
    }

    public List<JsonObject> top(int limit) {
        try {
            return store == null ? new ArrayList<JsonObject>() : store.top(limit);
        } catch (Exception e) {
            return new ArrayList<JsonObject>();
        }
    }

    /** 数值 → 关系名（只做展示，判定一律用数值）。 */
    public String levelName(double v) {
        if (v >= 900) return "挚友";
        if (v >= 600) return "亲近";
        if (v >= 300) return "朋友";
        if (v >= 200) return "熟人";
        if (v >= 100) return "眼熟";
        if (v > 0) return "陌生";
        return "初识";
    }

    /** 门禁快照（给模型/控制台看）。 */
    public JsonObject snapshot(long qq) {
        double v = of(qq);
        return J.obj("qq", qq, "favor", v, "level", levelName(v));
    }
}
