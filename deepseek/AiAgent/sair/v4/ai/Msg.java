package sair.v4.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

import sair.v4.kit.Str;

/**
 * 消息工厂：产出 OpenAI / DeepSeek 兼容的消息对象。
 *
 * <p>纯静态、无状态；返回值就是可以直接塞进 {@code messages} 数组的 {@link JsonObject}。
 * 调用方拿到后仍可自行追加字段（例如给 assistant 补 content）。</p>
 */
public final class Msg {

    private Msg() {}

    /** 系统提示词。 */
    public static JsonObject system(String text) {
        return simple("system", text);
    }

    /** 普通用户消息。 */
    public static JsonObject user(String text) {
        return simple("user", text);
    }

    /**
     * 带图片的用户消息：content 为数组，元素形如
     * {@code {"type":"text","text":".."}} 与 {@code {"type":"image_url","image_url":{"url":".."}}}。
     *
     * @param imageUrls http(s) 链接或 {@code data:image/...;base64,...}；空串与 null 元素被忽略
     */
    public static JsonObject userWithImages(String text, List<String> imageUrls) {
        JsonArray parts = new JsonArray();
        JsonObject t = new JsonObject();
        t.addProperty("type", "text");
        t.addProperty("text", text == null ? "" : text);
        parts.add(t);
        if (imageUrls != null) {
            for (String u : imageUrls) {
                if (Str.blank(u)) continue;
                JsonObject img = new JsonObject();
                img.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", u.trim());
                img.add("image_url", url);
                parts.add(img);
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("role", "user");
        o.add("content", parts);
        return o;
    }

    /** 助手消息（纯文本）。 */
    public static JsonObject assistant(String text) {
        return simple("assistant", text);
    }

    /** 助手消息（工具调用）；content 置空串，符合 DeepSeek 回传格式。 */
    public static JsonObject assistantToolCalls(JsonArray toolCalls) {
        JsonObject o = new JsonObject();
        o.addProperty("role", "assistant");
        o.addProperty("content", "");
        o.add("tool_calls", toolCalls == null ? new JsonArray() : toolCalls);
        return o;
    }

    /**
     * 助手消息（正文 + 工具调用 + 思维链）：思考模式下回传工具闭环那一轮的<b>完整</b>响应。
     *
     * <p>为什么必须有 {@code reasoning_content}：官方口径（{@code guides/thinking_mode} 的「工具调用」）——
     * <b>请求带 {@code tools} 时，历史里每条 assistant 消息的 {@code reasoning_content} 都要完整回传给 API
     * （即使该轮模型没有实际调用工具），漏了就是 HTTP 400</b>。工具闭环的每一轮都带 tools，
     * 所以这条路径上每一轮都得带上它。</p>
     *
     * <p>字段<b>始终写出</b>（空串也写）：官方给出的等价回传形式里该字段一律存在，
     * 没有"空就省掉这个键"这一说 —— 省掉正是 400 的触发条件。见 {@link #withReasoning}。</p>
     *
     * @param content          该轮正文（null 视作空串；与 tool_calls 同条消息，对齐官方样例）
     * @param reasoningContent 该轮 {@code reasoning_content}（null 视作空串）
     */
    public static JsonObject assistantToolCalls(String content, JsonArray toolCalls, String reasoningContent) {
        JsonObject o = assistantToolCalls(toolCalls);
        o.addProperty("content", content == null ? "" : content);
        return withReasoning(o, reasoningContent);
    }

    /**
     * 给 assistant 消息补上这一轮的思维链（thinking + tools 的硬要求）。
     *
     * <p>空值写成 {@code ""} 而不是省略键、也不是 JSON null：调用方回传的是"这一轮响应里真实的
     * {@code reasoning_content}"，模型没产出思维链时它的真实值就是空串 —— 原样回传才是"完整回传"。</p>
     *
     * @return 同一个对象（便于链式拼装）；传入 null 时返回 null
     */
    public static JsonObject withReasoning(JsonObject assistantMsg, String reasoningContent) {
        if (assistantMsg == null) return null;
        assistantMsg.addProperty("reasoning_content", reasoningContent == null ? "" : reasoningContent);
        return assistantMsg;
    }

    /** 工具结果消息，必须与 assistant 的 tool_call id 对应。 */
    public static JsonObject tool(String toolCallId, String content) {
        JsonObject o = new JsonObject();
        o.addProperty("role", "tool");
        o.addProperty("tool_call_id", toolCallId == null ? "" : toolCallId);
        o.addProperty("content", content == null ? "" : content);
        return o;
    }

    private static JsonObject simple(String role, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty("content", text == null ? "" : text);
        return o;
    }
}
