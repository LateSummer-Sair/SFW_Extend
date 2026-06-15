package sair.aiagent.onebot.util;

import java.util.ArrayList;
import java.util.List;

/**
 * OneBot JSON 工具方法（无第三方库依赖）。
 * 从 {@link sair.aiagent.onebot.QQMessageHandler} 和 {@link sair.aiagent.onebot.OneBotServer} 提取的公共方法。
 */
public class JsonUtil {

    /** 从JSON中提取字符串值 */
    public static String extractString(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + searchKey.length());
        if (colonIdx < 0) return null;

        int valStart = colonIdx + 1;
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\t')) {
            valStart++;
        }

        if (valStart >= json.length()) return null;

        char first = json.charAt(valStart);
        if (first != '"') {
            int valEnd = valStart;
            while (valEnd < json.length() && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}') {
                valEnd++;
            }
            String raw = json.substring(valStart, valEnd).trim();
            return "null".equals(raw) ? null : raw;
        }

        StringBuilder sb = new StringBuilder();
        int i = valStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case '/': sb.append('/'); i += 2; continue;
                    case 'n': sb.append('\n'); i += 2; continue;
                    case 'r': sb.append('\r'); i += 2; continue;
                    case 't': sb.append('\t'); i += 2; continue;
                    case 'u':
                        if (i + 5 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                                i += 6; continue;
                            } catch (NumberFormatException ignored) {}
                        }
                        sb.append(c); i++; continue;
                    default: sb.append(c); i++; continue;
                }
            } else if (c == '"') {
                break;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 从JSON中提取long值 */
    public static long extractLong(String json, String key) {
        String s = extractString(json, key);
        if (s == null || s.isEmpty()) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 从JSON中提取对象 {} 内容 */
    public static String extractObject(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + searchKey.length());
        if (colonIdx < 0) return null;

        int braceStart = json.indexOf('{', colonIdx + 1);
        if (braceStart < 0) return null;

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
        return json.substring(braceStart, pos);
    }

    /** 从JSON中提取数组 [] 内容 */
    public static String extractArray(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + searchKey.length());
        if (colonIdx < 0) return null;

        int bracketStart = json.indexOf('[', colonIdx + 1);
        if (bracketStart < 0) return null;

        int depth = 1;
        int pos = bracketStart + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '[') depth++;
            else if (c == ']') depth--;
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
        return json.substring(bracketStart + 1, pos - 1);
    }

    /** 拆分JSON数组中的对象 */
    public static List<String> splitJsonArray(String arrayStr) {
        List<String> items = new ArrayList<>();
        if (arrayStr == null || arrayStr.trim().isEmpty()) return items;

        int start = 0;
        int depth = 0;
        boolean inString = false;

        for (int i = 0; i < arrayStr.length(); i++) {
            char c = arrayStr.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                }
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        items.add(arrayStr.substring(start, i + 1));
                    }
                }
            }
        }
        return items;
    }

    /** JSON字符串转义（用于构建JSON payload） */
    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b");  break;
                case '\f': sb.append("\\f");  break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        return sb.toString();
    }
}
