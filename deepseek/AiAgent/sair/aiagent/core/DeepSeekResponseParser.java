package sair.aiagent.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.aiagent.model.ToolCall;

/**
 * DeepSeek API 响应解析工具 —— 从 {@link DeepSeekClient} 提取的静态 JSON/SSE 解析方法。
 */
class DeepSeekResponseParser {

    // ==================== SSE 流式解析 ====================

    /** 从SSE的data JSON中提取 delta.reasoning_content（优先 Gson，失败回退正则）。 */
    static String extractReasoningDelta(String json) {
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                com.google.gson.JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                if (delta != null && delta.has("reasoning_content") && !delta.get("reasoning_content").isJsonNull()) {
                    return delta.get("reasoning_content").getAsString();
                }
            }
            return "";
        } catch (Exception ignored) {
            // 回退正则
        }
        int deltaIdx = json.indexOf("\"delta\"");
        if (deltaIdx < 0) return "";
        int braceStart = json.indexOf('{', deltaIdx + 8);
        if (braceStart < 0) return "";
        int depth = 1;
        int pos = braceStart + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') { pos += 2; continue; }
                    if (sc == '"') break;
                    pos++;
                }
            }
            pos++;
        }
        String deltaObj = json.substring(braceStart, pos);
        Pattern p = Pattern.compile(
            "\"reasoning_content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(deltaObj);
        return m.find() ? jsonUnescape(m.group(1)) : "";
    }

    /** 从SSE的data JSON中提取 delta.content（优先 Gson，失败回退正则）。 */
    static String extractDelta(String json) {
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                com.google.gson.JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                    com.google.gson.JsonElement c = delta.get("content");
                    if (c.isJsonPrimitive()) return c.getAsString();
                }
            }
            return "";
        } catch (Exception ignored) {
            // 回退正则
        }
        int deltaIdx = json.indexOf("\"delta\"");
        if (deltaIdx < 0) return "";
        int braceStart = json.indexOf('{', deltaIdx + 8);
        if (braceStart < 0) return "";
        int depth = 1;
        int pos = braceStart + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') { pos += 2; continue; }
                    if (sc == '"') break;
                    pos++;
                }
            }
            pos++;
        }
        String deltaObj = json.substring(braceStart, pos);
        Pattern p = Pattern.compile(
            "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(deltaObj);
        return m.find() ? jsonUnescape(m.group(1)) : "";
    }

    // ==================== 非流式响应解析 ====================

    /** 从非流式响应的JSON中提取 message.reasoning_content 字段（优先 Gson，失败回退正则）。 */
    static String extractReasoningContent(String json) {
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                com.google.gson.JsonObject msg =
                        choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (msg != null && msg.has("reasoning_content") && !msg.get("reasoning_content").isJsonNull()) {
                    return msg.get("reasoning_content").getAsString();
                }
            }
            return "";
        } catch (Exception ignored) {
            // 回退正则
        }
        int msgIdx = json.indexOf("\"message\"");
        if (msgIdx < 0) return "";
        int msgBrace = json.indexOf('{', msgIdx + 9);
        if (msgBrace < 0) return "";
        int depth = 1;
        int pos = msgBrace + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') { pos += 2; continue; }
                    if (sc == '"') break;
                    pos++;
                }
            }
            pos++;
        }
        String msgBlock = json.substring(msgBrace, pos);
        Pattern p = Pattern.compile(
            "\"reasoning_content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(msgBlock);
        return m.find() ? jsonUnescape(m.group(1)) : "";
    }

    /** 从非流式响应的JSON中提取 message.content 字段（优先 Gson，失败回退正则）。 */
    static String extractFirstContent(String json) {
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonArray choices = root.getAsJsonArray("choices");
            if (choices != null && choices.size() > 0) {
                com.google.gson.JsonObject msg =
                        choices.get(0).getAsJsonObject().getAsJsonObject("message");
                if (msg != null && msg.has("content") && !msg.get("content").isJsonNull()) {
                    com.google.gson.JsonElement c = msg.get("content");
                    if (c.isJsonPrimitive()) return c.getAsString();
                }
            }
            return "";
        } catch (Exception ignored) {
            // 回退正则
        }
        int msgIdx = json.indexOf("\"message\"");
        if (msgIdx < 0) {
            Pattern p = Pattern.compile(
                "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
            Matcher m = p.matcher(json);
            return m.find() ? jsonUnescape(m.group(1)) : "";
        }
        int msgBrace = json.indexOf('{', msgIdx + 9);
        if (msgBrace < 0) return "";
        int depth = 1;
        int pos = msgBrace + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') { pos += 2; continue; }
                    if (sc == '"') break;
                    pos++;
                }
            }
            pos++;
        }
        String msgBlock = json.substring(msgBrace, pos);
        String result = "";
        Pattern p = Pattern.compile(
            "\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        Matcher m = p.matcher(msgBlock);
        while (m.find()) {
            int matchStart = m.start();
            String before = msgBlock.substring(0, matchStart);
            int toolCallsIdx = before.lastIndexOf("\"tool_calls\"");
            int funcCallIdx = before.lastIndexOf("\"function_call\"");
            int roleIdx = before.lastIndexOf("\"role\"");
            boolean isTool = false;
            if (toolCallsIdx >= 0 && toolCallsIdx > roleIdx) isTool = true;
            if (funcCallIdx >= 0 && funcCallIdx > roleIdx) isTool = true;
            if (roleIdx >= 0 && roleIdx < matchStart && !isTool) {
                result = m.group(1);
            }
        }
        return result.isEmpty() ? "" : jsonUnescape(result);
    }

    // ==================== Function Calling 解析 ====================

    /**
     * 从非流式响应 JSON 中提取 tool_calls 数组。
     * <p>使用 Gson 解析，返回 {@link ToolCall} 列表；无工具调用时返回空列表。</p>
     */
    static List<ToolCall> extractToolCalls(String json) {
        List<ToolCall> result = new ArrayList<>();
        if (json == null || json.isEmpty() || !json.contains("\"tool_calls\"")) {
            return result;
        }
        try {
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            com.google.gson.JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return result;
            com.google.gson.JsonObject msg =
                    choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (msg == null) return result;
            com.google.gson.JsonArray toolCalls = msg.getAsJsonArray("tool_calls");
            if (toolCalls == null) return result;
            for (com.google.gson.JsonElement el : toolCalls) {
                try {
                    com.google.gson.JsonObject tc = el.getAsJsonObject();
                    String id = tc.has("id") && !tc.get("id").isJsonNull()
                            ? tc.get("id").getAsString() : "";
                    com.google.gson.JsonObject fn = tc.getAsJsonObject("function");
                    String name = fn.has("name") && !fn.get("name").isJsonNull()
                            ? fn.get("name").getAsString() : "";
                    String arguments = fn.has("arguments") && !fn.get("arguments").isJsonNull()
                            ? fn.get("arguments").getAsString() : "{}";
                    result.add(new ToolCall(id, name, arguments));
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            // JSON 解析失败时返回空列表
        }
        return result;
    }

    // ==================== JSON 工具 ====================

    /** 反转义JSON字符串 */
    static String jsonUnescape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"':  sb.append('"');  i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/':  sb.append('/');  i++; break;
                    case 'b':  sb.append('\b'); i++; break;
                    case 'f':  sb.append('\f'); i++; break;
                    case 'n':  sb.append('\n'); i++; break;
                    case 'r':  sb.append('\r'); i++; break;
                    case 't':  sb.append('\t'); i++; break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                String hex = s.substring(i + 2, i + 6);
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 5;
                            } catch (NumberFormatException e) {
                                sb.append("\\u"); i++;
                            }
                        } else { sb.append("\\u"); i++; }
                        break;
                    default: sb.append(c);
                }
            } else { sb.append(c); }
        }
        return sb.toString();
    }

    /** 读取整个InputStream为字符串 */
    static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) { sb.append(line); }
        }
        return sb.toString();
    }
}