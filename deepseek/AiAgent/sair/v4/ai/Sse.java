package sair.v4.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sair.v4.kit.J;

/**
 * SSE（Server-Sent Events）流的解析与增量合并。
 *
 * <p>刻意做成<b>无网络、无状态、纯函数</b>的一层：{@link DeepSeek#stream} 只负责读字节，
 * 真正的协议细节全在这里，因此可以在探针里直接喂假分片验证（含 tool_calls 分片拼接）。</p>
 *
 * <p>要点：</p>
 * <ul>
 *   <li>逐行取 {@code data: ...} 载荷；空行、{@code :} 注释（心跳）、{@code event:}/{@code id:} 一律忽略；</li>
 *   <li>{@code data: [DONE]} 是结束标志，见 {@link #done(String)}；</li>
 *   <li>{@code delta.content} 增量<b>回调</b>并追加，{@code delta.reasoning_content} 只累积；</li>
 *   <li>{@code delta.tool_calls} 是分片：按 {@code index} 归并，{@code function.arguments} 字符串<b>拼接</b>，
 *       首片可能只有 {@code index}+{@code id}+{@code function.name}；</li>
 *   <li><b>任何非法输入都不会抛异常</b>，最多是被忽略。</li>
 * </ul>
 */
public final class Sse {

    private Sse() {}

    /** 流结束标志载荷。 */
    public static final String DONE = "[DONE]";

    /** 增量回调。与 {@link DeepSeek.Stream} 同形，避免解析层反向依赖客户端。 */
    public interface Sink {
        void on(String deltaText);
    }

    // ---------------- 行级 ----------------

    /** 是否是 {@code data: [DONE]}。 */
    public static boolean done(String line) {
        String p = payload(line);
        return p != null && DONE.equalsIgnoreCase(p);
    }

    /**
     * 取出 {@code data:} 之后的载荷原文（已去首尾空白）。
     * 非 data 行、空行、注释行、空载荷都返回 null。
     */
    public static String payload(String line) {
        if (line == null) return null;
        String s = line.trim();
        if (s.isEmpty()) return null;
        if (s.charAt(0) == ':') return null;            // SSE 注释 / 心跳
        if (!s.startsWith("data:")) return null;        // event: / id: / retry: 一律忽略
        String p = s.substring(5).trim();
        return p.isEmpty() ? null : p;
    }

    // ---------------- 合并 ----------------

    /** 解析一行并合并进 acc（不回调）。 */
    public static Res feed(String line, Res acc) {
        return feed(line, acc, null);
    }

    /**
     * 解析一行并合并进 acc；{@code delta.content} 同时回调给 sink。
     * 非法 JSON、控制载荷、注释行等都静默忽略。
     */
    public static Res feed(String line, Res acc, Sink sink) {
        Res r = (acc == null) ? new Res() : acc;
        String p = payload(line);
        if (p == null || DONE.equalsIgnoreCase(p)) return r;
        if (p.charAt(0) == '[') return r;                // 其它数组型控制载荷
        JsonObject o = J.obj(p);
        if (o == null) return r;                         // 非法 JSON：忽略，不抛异常
        return merge(o, r, sink);
    }

    /**
     * 合并一个已解析的分片对象。
     *
     * <p>{@code choices[0].delta} 走流式合并；没有 delta 时回退读 {@code choices[0].message}
     * （结构兼容，非流式响应体也能用同一套代码解析）。</p>
     */
    public static Res merge(JsonObject chunk, Res acc, Sink sink) {
        Res r = (acc == null) ? new Res() : acc;
        if (chunk == null) return r;

        JsonObject err = J.sub(chunk, "error");
        if (err != null) {                               // 流中报错（HTTP 200 也常见）
            String m = errText(err);
            if (!m.isEmpty()) r.error = m;
            return r;
        }

        String model = J.s(chunk, "model", "");
        if (!model.isEmpty()) r.modelUsed = model;

        JsonObject usage = J.sub(chunk, "usage");
        if (usage != null) r.usage = usage;

        JsonArray choices = J.list(chunk, "choices");
        if (choices == null || choices.size() == 0) return r;
        JsonObject c0 = firstObject(choices);
        if (c0 == null) return r;

        String fr = J.s(c0, "finish_reason", "");
        if (!fr.isEmpty()) r.finishReason = fr;

        JsonObject delta = J.sub(c0, "delta");
        boolean streaming = delta != null;
        if (delta == null) delta = J.sub(c0, "message");  // 兜底：非流式
        if (delta == null) return r;

        // content：追加（空串也要拼接，一个空格/换行都是合法分片）
        String content = J.s(delta, "content", "");
        if (!content.isEmpty()) {
            r.content = (r.content == null ? "" : r.content) + content;
            if (streaming && sink != null) {
                try {
                    sink.on(content);
                } catch (Throwable ignored) {
                    // 回调方异常不能污染解析结果
                }
            }
        }

        // reasoning_content：只累积（部分实现叫 reasoning）
        String rc = J.s(delta, "reasoning_content", "");
        if (rc.isEmpty()) rc = J.s(delta, "reasoning", "");
        if (!rc.isEmpty()) r.reasoning = (r.reasoning == null ? "" : r.reasoning) + rc;

        JsonArray tcs = J.list(delta, "tool_calls");
        if (tcs != null && tcs.size() > 0) mergeToolCalls(tcs, r, streaming);
        return r;
    }

    /**
     * 合并 tool_calls。
     *
     * @param streaming true=按 index 归并且 arguments 拼接；false=整条调用（非流式响应体）
     */
    public static void mergeToolCalls(JsonArray parts, Res acc, boolean streaming) {
        if (parts == null || acc == null) return;
        if (acc.toolCalls == null) acc.toolCalls = new JsonArray();
        for (int i = 0; i < parts.size(); i++) {
            JsonElement el = parts.get(i);
            if (el == null || !el.isJsonObject()) continue;
            JsonObject part = el.getAsJsonObject();

            if (!streaming) {                              // 完整调用：整条规整后追加
                acc.toolCalls.add(norm(part));
                continue;
            }

            int idx = J.i(part, "index", i);
            if (idx < 0) idx = i;
            while (acc.toolCalls.size() <= idx) acc.toolCalls.add(blankCall());
            JsonObject slot = acc.toolCalls.get(idx).getAsJsonObject();

            String id = J.s(part, "id", "");
            if (!id.isEmpty()) J.put(slot, "id", id);
            String type = J.s(part, "type", "");
            if (!type.isEmpty()) J.put(slot, "type", type);

            JsonObject pf = J.sub(part, "function");
            if (pf == null) continue;
            JsonObject sf = J.sub(slot, "function");
            if (sf == null) {
                sf = new JsonObject();
                sf.addProperty("name", "");
                sf.addProperty("arguments", "");
                slot.add("function", sf);
            }
            String name = J.s(pf, "name", "");
            if (!name.isEmpty()) J.put(sf, "name", mergeName(J.s(sf, "name", ""), name));
            JsonElement args = J.get(pf, "arguments");
            if (args != null) appendArgs(sf, argText(args));
        }
    }

    // ---------------- 规整 ----------------

    /** 规整成 {@code {"id":..,"type":"function","function":{"name":..,"arguments":"<字符串>"}}}。 */
    public static JsonObject norm(JsonObject src) {
        JsonObject o = blankCall();
        if (src == null) return o;
        String id = J.s(src, "id", "");
        if (!id.isEmpty()) o.addProperty("id", id);
        String type = J.s(src, "type", "");
        o.addProperty("type", type.isEmpty() ? "function" : type);
        JsonObject f = J.sub(src, "function");
        JsonObject nf = o.getAsJsonObject("function");
        if (f != null) {
            String name = J.s(f, "name", "");
            if (!name.isEmpty()) nf.addProperty("name", name);
            nf.addProperty("arguments", argText(J.get(f, "arguments")));
        } else {
            nf.addProperty("arguments", argText(J.get(src, "arguments")));
        }
        return o;
    }

    /** 空壳工具调用（占位用）。 */
    public static JsonObject blankCall() {
        JsonObject f = new JsonObject();
        f.addProperty("name", "");
        f.addProperty("arguments", "");
        JsonObject o = new JsonObject();
        o.addProperty("id", "");
        o.addProperty("type", "function");
        o.add("function", f);
        return o;
    }

    /** {@code error} 对象 → 人类可读的一行错误。 */
    public static String errText(JsonObject err) {
        if (err == null) return "";
        String m = J.s(err, "message", "");
        String t = J.s(err, "type", "");
        String c = J.s(err, "code", "");
        StringBuilder sb = new StringBuilder();
        if (!t.isEmpty()) sb.append(t).append(": ");
        sb.append(m.isEmpty() ? err.toString() : m);
        if (!c.isEmpty()) sb.append(" (code ").append(c).append(")");
        return sb.toString();
    }

    /** arguments 取值：字符串原样，对象/数组转成 JSON 字符串。 */
    public static String argText(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        if (e.isJsonPrimitive()) {
            try {
                return e.getAsString();
            } catch (Exception ex) {
                return "";
            }
        }
        return e.toString();
    }

    // ---------------- 内部 ----------------

    private static void appendArgs(JsonObject fn, String frag) {
        if (frag == null || frag.isEmpty()) return;
        fn.addProperty("arguments", J.s(fn, "arguments", "") + frag);
    }

    /** 工具名分片：重复片忽略、渐进片替换、其它一律拼接。 */
    private static String mergeName(String cur, String frag) {
        if (cur == null || cur.isEmpty()) return frag;
        if (frag.equals(cur) || cur.endsWith(frag)) return cur;
        if (frag.startsWith(cur)) return frag;
        return cur + frag;
    }

    private static JsonObject firstObject(JsonArray a) {
        for (JsonElement e : a) {
            if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        }
        return null;
    }
}
