package sair.v4.kit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 工具（Gson 之上的薄封装）。
 * <p>取值一律走 {@code getAsString}/{@code getAsLong} 等强类型读取 —— 直接用
 * {@code String.valueOf(JsonPrimitive)} 会带上引号，是 V3 踩过的坑。</p>
 */
public final class J {

    private J() {}

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    // ---------- 解析 ----------

    public static JsonElement el(String json) {
        if (json == null) return null;
        String s = json.trim();
        if (s.isEmpty()) return null;
        try {
            return JsonParser.parseString(s);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonObject obj(String json) {
        JsonElement e = el(json);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    public static JsonArray arr(String json) {
        JsonElement e = el(json);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    public static JsonObject obj() { return new JsonObject(); }

    /** 交替的 key/value 构造对象；value 支持 String/Number/Boolean/JsonElement/其他（转字符串）。 */
    public static JsonObject obj(Object... kv) {
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String k = String.valueOf(kv[i]);
            put(o, k, kv[i + 1]);
        }
        return o;
    }

    public static void put(JsonObject o, String k, Object v) {
        if (o == null || k == null) return;
        if (v == null) { o.add(k, null); return; }
        if (v instanceof JsonElement) { o.add(k, (JsonElement) v); return; }
        if (v instanceof String) { o.addProperty(k, (String) v); return; }
        if (v instanceof Number) { o.addProperty(k, (Number) v); return; }
        if (v instanceof Boolean) { o.addProperty(k, (Boolean) v); return; }
        o.addProperty(k, String.valueOf(v));
    }

    // ---------- 读取 ----------

    public static JsonElement get(JsonObject o, String k) {
        if (o == null || k == null) return null;
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? null : e;
    }

    public static String s(JsonObject o, String k, String def) {
        JsonElement e = get(o, k);
        if (e == null) return def;
        try {
            return e.isJsonPrimitive() ? e.getAsString() : e.toString();
        } catch (Exception ex) {
            return def;
        }
    }

    public static String s(JsonObject o, String k) { return s(o, k, ""); }

    public static long l(JsonObject o, String k, long def) {
        JsonElement e = get(o, k);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsLong();
        } catch (Exception ex) {
            String s = s(o, k, "");
            try {
                return Long.parseLong(s.trim());
            } catch (Exception ex2) {
                return def;
            }
        }
    }

    public static int i(JsonObject o, String k, int def) {
        long v = l(o, k, def);
        return (int) v;
    }

    public static double d(JsonObject o, String k, double def) {
        JsonElement e = get(o, k);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsDouble();
        } catch (Exception ex) {
            return def;
        }
    }

    public static boolean b(JsonObject o, String k, boolean def) {
        JsonElement e = get(o, k);
        if (e == null || !e.isJsonPrimitive()) return def;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            String s = s(o, k, "").trim();
            if ("1".equals(s) || "yes".equalsIgnoreCase(s) || "on".equalsIgnoreCase(s)) return true;
            if ("0".equals(s) || "no".equalsIgnoreCase(s) || "off".equalsIgnoreCase(s)) return false;
            return def;
        }
    }

    public static JsonObject sub(JsonObject o, String k) {
        JsonElement e = get(o, k);
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    public static JsonArray list(JsonObject o, String k) {
        JsonElement e = get(o, k);
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    public static List<String> strings(JsonObject o, String k) {
        List<String> out = new ArrayList<String>();
        JsonArray a = list(o, k);
        if (a == null) return out;
        for (JsonElement e : a) {
            if (e == null || e.isJsonNull()) continue;
            out.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
        }
        return out;
    }

    public static List<JsonObject> objects(JsonObject o, String k) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        JsonArray a = list(o, k);
        if (a == null) return out;
        for (JsonElement e : a) {
            if (e != null && e.isJsonObject()) out.add(e.getAsJsonObject());
        }
        return out;
    }

    /** JsonObject → 有序 Map（技能入参/上下文用）。 */
    public static Map<String, Object> map(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (o == null) return m;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) m.put(e.getKey(), plain(e.getValue()));
        return m;
    }

    /** JsonElement → 原生 Java 值（供技能反射入参）。 */
    public static Object plain(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) {
            if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
            if (e.getAsJsonPrimitive().isNumber()) {
                double dv = e.getAsDouble();
                if (dv == Math.rint(dv) && !Double.isInfinite(dv)) return e.getAsLong();
                return dv;
            }
            return e.getAsString();
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<Object>();
            for (JsonElement x : e.getAsJsonArray()) l.add(plain(x));
            return l;
        }
        return map(e.getAsJsonObject());
    }

    // ---------- 序列化 ----------

    public static String json(Object v) { return GSON.toJson(v); }

    public static String pretty(Object v) { return PRETTY.toJson(v); }

    /** 安全序列化：失败返回 "{}"。 */
    public static String jsonSafe(Object v) {
        try {
            return GSON.toJson(v);
        } catch (Exception e) {
            return "{}";
        }
    }
}
