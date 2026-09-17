package sair.v4.hot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

import sair.v4.kit.Str;

/**
 * 技能 md 的解析：front matter（--- 之间的极简 YAML 子集）+ 正文（说明书）。
 *
 * <h3>支持的 YAML 子集</h3>
 * <pre>
 * key: value              标量
 * key: [a, b, c]          内联数组
 * key:                    空值 → 进入缩进块（map 或 list）
 *   sub: value
 * list:
 *   - a
 *   - b
 * </pre>
 * 不支持锚点/多行块/引号转义等高级语法 —— 技能文档保持简单可手写。
 */
public final class SkMd {

    private SkMd() {}

    /** 解析结果。 */
    public static final class Parsed {
        public JsonObject front = new JsonObject();
        public String body = "";
        public String error = "";
    }

    public static Parsed parse(String text) {
        Parsed p = new Parsed();
        if (text == null) {
            p.error = "空文件";
            return p;
        }
        String[] raw = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int start = -1, end = -1;
        for (int i = 0; i < raw.length; i++) {
            if ("---".equals(raw[i].trim())) {
                if (start < 0) start = i;
                else { end = i; break; }
            } else if (start < 0 && !raw[i].trim().isEmpty()) {
                break;      // 第一行不是 --- ：没有 front matter
            }
        }
        if (start < 0 || end < 0) {
            p.body = text.trim();
            return p;
        }
        List<String> fm = new ArrayList<String>();
        for (int i = start + 1; i < end; i++) fm.add(raw[i]);
        p.front = yaml(fm);
        StringBuilder body = new StringBuilder();
        for (int i = end + 1; i < raw.length; i++) body.append(raw[i]).append('\n');
        p.body = body.toString().trim();
        return p;
    }

    /** 极简 YAML → JsonObject。 */
    public static JsonObject yaml(List<String> lines) {
        int[] idx = new int[] {0};
        JsonObject o = block(lines, idx, firstNonBlank(lines));
        return o;
    }

    private static int firstNonBlank(List<String> lines) {
        for (String l : lines) if (!l.trim().isEmpty()) return indentOf(l);
        return 0;
    }

    private static JsonObject block(List<String> lines, int[] idx, int indent) {
        JsonObject o = new JsonObject();
        while (idx[0] < lines.size()) {
            String line = lines.get(idx[0]);
            if (line.trim().isEmpty()) { idx[0]++; continue; }
            int ind = indentOf(line);
            if (ind < indent) break;
            if (ind > indent) { idx[0]++; continue; }     // 意外缩进：跳过
            String t = line.trim();
            if (t.startsWith("#")) { idx[0]++; continue; }
            if (t.startsWith("- ")) { idx[0]++; continue; } // 列表项在 map 位置：忽略
            int c = t.indexOf(':');
            if (c < 0) { idx[0]++; continue; }
            String key = t.substring(0, c).trim();
            String val = t.substring(c + 1).trim();
            idx[0]++;
            if (val.isEmpty()) {
                // 子块：list 或 map
                int next = nextNonBlank(lines, idx[0]);
                if (next < 0 || indentOf(lines.get(next)) <= ind) {
                    o.add(key, new JsonObject());
                    continue;
                }
                int childIndent = indentOf(lines.get(next));
                if (lines.get(next).trim().startsWith("- ")) {
                    JsonArray arr = new JsonArray();
                    while (idx[0] < lines.size()) {
                        String l = lines.get(idx[0]);
                        if (l.trim().isEmpty()) { idx[0]++; continue; }
                        if (indentOf(l) != childIndent || !l.trim().startsWith("- ")) break;
                        arr.add(scalar(l.trim().substring(2).trim()));
                        idx[0]++;
                    }
                    o.add(key, arr);
                } else {
                    o.add(key, block(lines, idx, childIndent));
                }
            } else {
                o.add(key, scalar(val));
            }
        }
        return o;
    }

    private static int nextNonBlank(List<String> lines, int from) {
        for (int i = from; i < lines.size(); i++) if (!lines.get(i).trim().isEmpty()) return i;
        return -1;
    }

    private static com.google.gson.JsonElement scalar(String v) {
        String s = v.trim();
        if (s.isEmpty()) return new com.google.gson.JsonPrimitive("");
        if (s.startsWith("{") && s.endsWith("}")) {
            // 内联流式 map：{type: string, description: xx, required: true}
            JsonObject o = new JsonObject();
            for (String part : splitTop(s.substring(1, s.length() - 1))) {
                int c = part.indexOf(':');
                if (c <= 0) continue;
                o.add(part.substring(0, c).trim(), clean(part.substring(c + 1).trim()));
            }
            return o;
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            JsonArray arr = new JsonArray();
            for (String p : s.substring(1, s.length() - 1).split(",")) {
                String q = p.trim();
                if (!q.isEmpty()) arr.add(clean(q));
            }
            return arr;
        }
        return clean(s);
    }

    private static com.google.gson.JsonPrimitive clean(String s) {
        String t = s;
        if ((t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2)
                || (t.startsWith("'") && t.endsWith("'") && t.length() >= 2)) {
            t = t.substring(1, t.length() - 1);
        }
        return new com.google.gson.JsonPrimitive(t);
    }

    /** 按顶层逗号切分（不切进 {} 里）。 */
    private static java.util.List<String> splitTop(String s) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '{' || ch == '[') depth++;
            else if (ch == '}' || ch == ']') depth--;
            else if (ch == ',' && depth == 0) { out.add(s.substring(start, i)); start = i + 1; }
        }
        out.add(s.substring(start));
        return out;
    }

    private static int indentOf(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    /**
     * 把技能 md 里的 {@code airun.params} 描述转成 JSON Schema。
     * <p>逐参数支持：{@code type/description/required/default/enum/example/items/items_description}。
     * 声明的 params 直接决定模型怎么填参 —— 写得越清楚，填得越准。</p>
     */
    public static JsonObject schema(JsonObject paramsNode) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();
        JsonArray required = new JsonArray();
        if (paramsNode != null) {
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : paramsNode.entrySet()) {
                String name = e.getKey();
                com.google.gson.JsonElement v = e.getValue();
                JsonObject def = new JsonObject();
                if (v != null && v.isJsonObject()) {
                    JsonObject node = v.getAsJsonObject();
                    String type = Str.nz(s(pick(node, "type", "类型"), "string")).trim();
                    if (type.isEmpty()) type = "string";
                    def.addProperty("type", type);
                    String desc = s(pick(node, "description", "说明", "desc"), "");
                    String example = s(pick(node, "example", "示例"), "");
                    if (Str.has(example)) desc = (desc.isEmpty() ? "" : desc + " ") + "示例：" + example;
                    if (Str.has(desc)) def.addProperty("description", desc);
                    JsonArray en = arr(pick(node, "enum", "取值"));
                    if (en != null && en.size() > 0) def.add("enum", en);
                    com.google.gson.JsonElement dv = pick(node, "default", "默认");
                    if (dv != null && !dv.isJsonNull()) def.add("default", dv);
                    if ("array".equals(type)) {
                        JsonObject items = new JsonObject();
                        items.addProperty("type", Str.nz(s(pick(node, "items", "元素"), "string")).trim());
                        String id = s(pick(node, "items_description", "元素说明"), "");
                        if (Str.has(id)) items.addProperty("description", id);
                        def.add("items", items);
                    }
                    if (truthy(pick(node, "required", "必填"))) required.add(name);
                } else {
                    def.addProperty("type", "string");
                    String d = v == null || v.isJsonNull() ? "" : v.getAsString();
                    if (Str.has(d)) def.addProperty("description", d);
                }
                props.add(name, def);
            }
        }
        schema.add("properties", props);
        if (required.size() > 0) schema.add("required", required);
        return schema;
    }

    private static com.google.gson.JsonElement pick(JsonObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            com.google.gson.JsonElement e = o.get(k);
            if (e != null && !e.isJsonNull()) return e;
        }
        return null;
    }

    private static String s(com.google.gson.JsonElement e, String def) {
        if (e == null || e.isJsonNull()) return def;
        return e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }

    private static JsonArray arr(com.google.gson.JsonElement e) {
        return (e != null && e.isJsonArray()) ? e.getAsJsonArray() : null;
    }

    private static boolean truthy(com.google.gson.JsonElement e) {
        if (e == null || e.isJsonNull()) return false;
        if (!e.isJsonPrimitive()) return false;
        try {
            return e.getAsBoolean();
        } catch (Exception ex) {
            String s = e.getAsString().trim().toLowerCase();
            return "1".equals(s) || "true".equals(s) || "yes".equals(s) || "on".equals(s) || "是".equals(s);
        }
    }
}
