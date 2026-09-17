package sair.v4.skill;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;

import sair.v4.kit.J;

/** 技能入参（工具调用的参数包）。取值一律强类型，避免 {@code String.valueOf} 带引号这类坑。 */
public final class Args {

    private final String tool;
    private final JsonObject raw;

    public Args(String tool, JsonObject raw) {
        this.tool = tool;
        this.raw = raw == null ? new JsonObject() : raw;
    }

    /** 被调用的工具名。 */
    public String tool() { return tool; }

    public JsonObject raw() { return raw; }

    public boolean has(String k) { return raw.has(k) && !raw.get(k).isJsonNull(); }

    public String str(String k, String def) { return J.s(raw, k, def); }

    public String str(String k) { return J.s(raw, k, ""); }

    public long l(String k, long def) { return J.l(raw, k, def); }

    public int i(String k, int def) { return J.i(raw, k, def); }

    public double d(String k, double def) { return J.d(raw, k, def); }

    public boolean b(String k, boolean def) { return J.b(raw, k, def); }

    public JsonObject obj(String k) { return J.sub(raw, k); }

    public JsonArray arr(String k) { return J.list(raw, k); }

    public List<String> strs(String k) { return J.strings(raw, k); }

    public Map<String, Object> map() { return J.map(raw); }

    /** 参数缺失时返回的说明（技能可以让模型自我纠正）。 */
    public String missing(String k) { return "缺少参数 " + k; }
}
