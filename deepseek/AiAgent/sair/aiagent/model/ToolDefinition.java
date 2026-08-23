package sair.aiagent.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Function Calling 工具定义 —— 对应 OpenAI/DeepSeek API 的 tools 数组项。
 * <p>
 * 结构：{@code {"type":"function","function":{"name","description","parameters":{JSON Schema}}}}
 * </p>
 * <p>参数由 {@link #addProperty(String, String, String, boolean, List)} 逐步构建，
 * 自动生成符合 JSON Schema 规范的 parameters 对象。</p>
 */
public class ToolDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;
    private final Map<String, Object> parameters = new LinkedHashMap<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    /** strict 模式（Beta）：模型严格遵循 JSON Schema 输出（需 /beta base_url + additionalProperties:false） */
    private boolean strict = false;

    public ToolDefinition(String name, String description) {
        this.name = name;
        this.description = (description != null) ? description : "";
        this.parameters.put("type", "object");
    }

    /**
     * 添加一个工具参数。
     *
     * @param paramName  参数名
     * @param type       JSON Schema 类型（string/integer/number/boolean）
     * @param description 参数描述
     * @param required   是否必填
     * @param enumValues 可选枚举值（null=无）
     */
    public ToolDefinition addProperty(String paramName, String type, String description,
                                      boolean required, List<String> enumValues) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", type);
        if (description != null && !description.isEmpty()) {
            prop.put("description", description);
        }
        if (enumValues != null && !enumValues.isEmpty()) {
            prop.put("enum", new ArrayList<>(enumValues));
        }
        properties.put(paramName, prop);
        if (required) {
            this.required.add(paramName);
        }
        return this;
    }

    /** 便捷方法：添加必填的字符串参数 */
    public ToolDefinition addString(String paramName, String description) {
        return addProperty(paramName, "string", description, true, null);
    }

    /** 便捷方法：添加可选的字符串参数 */
    public ToolDefinition addOptionalString(String paramName, String description) {
        return addProperty(paramName, "string", description, false, null);
    }

    /** 便捷方法：添加带枚举的字符串参数 */
    public ToolDefinition addEnum(String paramName, String description, List<String> enumValues) {
        return addProperty(paramName, "string", description, true, enumValues);
    }

    public String getName() { return name; }
    public String getDescription() { return description; }

    /**
     * 设置 strict 模式（Beta）。开启后工具定义将附带 {@code strict: true} 与
     * {@code additionalProperties: false}，optional 属性以 {@code anyOf:[原类型, {type:"null"}]} 表示可选。
     */
    public ToolDefinition setStrict(boolean strict) { this.strict = strict; return this; }

    /** @return 是否启用 strict 模式 */
    public boolean isStrict() { return strict; }

    /**
     * 将工具定义转换为 API 请求所需的 JSON 对象（Map 形式，供 Gson 序列化）。
     * @return {"type":"function","function":{...}} 结构
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toRequestObject() {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        if (strict) {
            function.put("strict", true);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        if (strict) {
            params.put("additionalProperties", false);
        }

        // strict 模式下 optional 属性（未 required）需以 anyOf:[原类型, {type:"null"}] 表示可选，
        // 否则服务端 JSON Schema 校验会因「存在未 required 的属性」而报错。
        Map<String, Object> props = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : properties.entrySet()) {
            Object v = e.getValue();
            if (strict && (v instanceof Map) && !required.contains(e.getKey())) {
                Map<String, Object> propMap = new LinkedHashMap<>((Map<String, Object>) v);
                List<Object> anyOf = new ArrayList<>();
                anyOf.add(propMap);
                Map<String, Object> nullType = new LinkedHashMap<>();
                nullType.put("type", "null");
                anyOf.add(nullType);
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("anyOf", anyOf);
                props.put(e.getKey(), wrapped);
            } else {
                props.put(e.getKey(), v);
            }
        }
        params.put("properties", props);
        if (!required.isEmpty()) {
            params.put("required", new ArrayList<>(required));
        }
        function.put("parameters", params);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    @Override
    public String toString() {
        return "ToolDefinition{" + name + ": " + description + "}";
    }
}
