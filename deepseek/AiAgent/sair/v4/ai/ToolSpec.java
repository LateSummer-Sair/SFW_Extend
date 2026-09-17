package sair.v4.ai;

import com.google.gson.JsonObject;

/**
 * 工具声明工厂：产出 OpenAI / DeepSeek 兼容的 tools 数组元素。
 *
 * <p>元素形如 {@code {"type":"function","function":{"name":..,"description":..,"parameters":{..}}}}。</p>
 */
public final class ToolSpec {

    private ToolSpec() {}

    /**
     * 造一个 function 工具声明。
     *
     * @param name        工具名（模型调用时回传的名字）
     * @param description 给模型看的说明
     * @param paramsSchema JSON Schema；null 时用 {@link #params()} 的空 schema
     */
    public static JsonObject fn(String name, String description, JsonObject paramsSchema) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name == null ? "" : name);
        f.addProperty("description", description == null ? "" : description);
        f.add("parameters", paramsSchema == null ? params() : paramsSchema);
        JsonObject o = new JsonObject();
        o.addProperty("type", "function");
        o.add("function", f);
        return o;
    }

    /** 空参数 schema：{@code {"type":"object","properties":{}}}。 */
    public static JsonObject params() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", new JsonObject());
        return o;
    }
}
