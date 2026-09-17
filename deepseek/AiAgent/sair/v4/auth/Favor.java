package sair.v4.auth;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import sair.v4.kit.J;

/**
 * 好感度子系统：<b>它就是权限门禁</b>。
 * <p>除主人（MASTER，一律全放行）外，一个工具能不能用，最终都落到这里的数值上：
 * {@code AFFECTION:N} 要求 ≥N（对外一律用这个档位；旧的 {@code MEDIA} 档位已剔除 ——
 * 它和 {@code AFFECTION:600} 重复）。数值带一层内存缓存，写操作直接落库并刷新缓存。</p>
 */
public final class Favor implements sair.v4.skill.FavorView {

    private final FavorStore store;
    private final Map<Long, Double> cache = new ConcurrentHashMap<Long, Double>();

    public Favor(FavorStore store) {
        this.store = store;
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
        cache.put(qq, v);
        try {
            if (store != null) store.setFavor(qq, v, levelName(v), note == null ? "" : note);
        } catch (Exception ignored) {
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
