package sair.v4.prompt;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Str;

/**
 * 注入台（基板③的"注入"半边）：把任意文本注入到<b>指定位置</b>。
 *
 * <h3>槽位</h3>
 * <pre>
 *   system   追加进系统提示词（影响角色/规则）
 *   context  追加进每轮动态上下文（影响本轮事实）
 *   tool     补充工具描述（影响模型怎么用某个工具）
 *   arg      拼在用户消息之前（一次性的强提示）
 * </pre>
 *
 * <h3>作用域</h3>
 * {@link #GLOBAL} 对所有会话生效；其余键是会话键（如 {@code group:123} / {@code qq:456} / {@code console}），
 * 只对该会话生效。渲染时先全局后会话，同 key 会话覆盖全局。
 */
public final class Inject {

    public static final String GLOBAL = "*";
    public static final String SYSTEM = "system";
    public static final String CONTEXT = "context";
    public static final String TOOL = "tool";
    public static final String ARG = "arg";

    /** 每个槽位最多保留的注入条目（超出丢最旧）。 */
    public static final int MAX_PER_SLOT = 64;

    /** scope → slot → key → {text, source, ts} */
    private final Map<String, Map<String, Map<String, JsonObject>>> table =
            new LinkedHashMap<String, Map<String, Map<String, JsonObject>>>();

    public synchronized void put(String slot, String key, String text, String source) {
        put(GLOBAL, slot, key, text, source);
    }

    public synchronized void put(String scope, String slot, String key, String text, String source) {
        if (Str.blank(slot) || Str.blank(key)) return;
        String sc = Str.blank(scope) ? GLOBAL : scope.trim();
        Map<String, Map<String, JsonObject>> slots = table.get(sc);
        if (slots == null) {
            slots = new LinkedHashMap<String, Map<String, JsonObject>>();
            table.put(sc, slots);
        }
        Map<String, JsonObject> entries = slots.get(slot);
        if (entries == null) {
            entries = new LinkedHashMap<String, JsonObject>();
            slots.put(slot, entries);
        }
        JsonObject e = new JsonObject();
        e.addProperty("text", text == null ? "" : text);
        e.addProperty("source", source == null ? "" : source);
        e.addProperty("ts", System.currentTimeMillis());
        entries.put(key, e);
        // 上限保护：同一槽位条目过多时丢最旧的（注入表不能被技能无限撑大）
        while (entries.size() > MAX_PER_SLOT) {
            String oldest = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<String, JsonObject> x : entries.entrySet()) {
                long ts = x.getValue().has("ts") ? x.getValue().get("ts").getAsLong() : 0;
                if (ts <= oldestTs) {
                    oldestTs = ts;
                    oldest = x.getKey();
                }
            }
            if (oldest == null) break;
            entries.remove(oldest);
        }
    }

    public synchronized boolean remove(String scope, String slot, String key) {
        String sc = Str.blank(scope) ? GLOBAL : scope.trim();
        Map<String, Map<String, JsonObject>> slots = table.get(sc);
        if (slots == null) return false;
        Map<String, JsonObject> entries = slots.get(slot);
        if (entries == null) return false;
        return entries.remove(key) != null;
    }

    /** 拼接某槽位在该会话下的全部注入文本（全局在前）。 */
    public synchronized String render(String scope, String slot) {
        StringBuilder sb = new StringBuilder();
        append(sb, GLOBAL, slot);
        String sc = Str.blank(scope) ? GLOBAL : scope.trim();
        if (!GLOBAL.equals(sc)) append(sb, sc, slot);
        return sb.toString().trim();
    }

    private void append(StringBuilder sb, String scope, String slot) {
        Map<String, Map<String, JsonObject>> slots = table.get(scope);
        if (slots == null) return;
        Map<String, JsonObject> entries = slots.get(slot);
        if (entries == null) return;
        for (Map.Entry<String, JsonObject> e : entries.entrySet()) {
            String t = e.getValue() == null ? "" : e.getValue().has("text") ? e.getValue().get("text").getAsString() : "";
            if (Str.blank(t)) continue;
            if (sb.length() > 0) sb.append("\n\n");
            sb.append(t.trim());
        }
    }

    public synchronized int count(String scope, String slot) {
        int n = 0;
        n += countScope(GLOBAL, slot);
        String sc = Str.blank(scope) ? GLOBAL : scope.trim();
        if (!GLOBAL.equals(sc)) n += countScope(sc, slot);
        return n;
    }

    private int countScope(String scope, String slot) {
        Map<String, Map<String, JsonObject>> slots = table.get(scope);
        if (slots == null) return 0;
        Map<String, JsonObject> entries = slots.get(slot);
        return entries == null ? 0 : entries.size();
    }

    /** 列出某会话可见的注入项（含全局）。 */
    public synchronized List<JsonObject> list(String scope) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        collect(out, GLOBAL);
        String sc = Str.blank(scope) ? GLOBAL : scope.trim();
        if (!GLOBAL.equals(sc)) collect(out, sc);
        return out;
    }

    private void collect(List<JsonObject> out, String scope) {
        Map<String, Map<String, JsonObject>> slots = table.get(scope);
        if (slots == null) return;
        for (Map.Entry<String, Map<String, JsonObject>> s : slots.entrySet()) {
            for (Map.Entry<String, JsonObject> e : s.getValue().entrySet()) {
                JsonObject o = new JsonObject();
                o.addProperty("scope", scope);
                o.addProperty("slot", s.getKey());
                o.addProperty("key", e.getKey());
                o.addProperty("source", e.getValue().has("source") ? e.getValue().get("source").getAsString() : "");
                String t = e.getValue().has("text") ? e.getValue().get("text").getAsString() : "";
                o.addProperty("chars", t.length());
                out.add(o);
            }
        }
    }

    public synchronized void clearScope(String scope) {
        String sc = Str.blank(scope) ? GLOBAL : scope.trim();
        table.remove(sc);
    }

    public synchronized void clearAll() { table.clear(); }
}
