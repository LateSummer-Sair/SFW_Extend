package sair.aiagent.core;

import java.io.File;
import java.util.regex.Pattern;

/**
 * TagExecutor 的辅助处理器：批量文件操作 + HTML/文本提取工具。
 */
class TagHandlers {

    // ==================== 批量文件操作 ====================

    // ==================== HTML / 文本提取 ====================

    static String extractQuoted(String args, int index) {
        int count = 0, i = 0;
        while (i < args.length() && count <= index) {
            if (args.charAt(i) == '"') {
                int end = args.indexOf('"', i + 1);
                if (end < 0) break;
                if (count == index) return args.substring(i + 1, end);
                i = end + 1; count++;
            } else { i++; }
        }
        return "";
    }

    static String extractParam(String s, String key) {
        java.util.regex.Matcher m = Pattern.compile(
            key + "=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE
        ).matcher(s);
        return m.find() ? m.group(1) : "";
    }

    static String extractBySelector(String html, String selector) {
        if (html == null || selector == null) return html;
        StringBuilder result = new StringBuilder();
        String lower = selector.toLowerCase().trim();
        if (lower.startsWith(".")) {
            String cls = lower.substring(1);
            Pattern p = Pattern.compile(
                "<[^>]*class=\"[^\"]*" + Pattern.quote(cls) + "[^\"]*\"[^>]*>(.*?)</[^>]+>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                if (result.length() > 0) result.append("\n");
                result.append(stripHtml(m.group(1)));
            }
        } else if (lower.startsWith("#")) {
            String id = lower.substring(1);
            Pattern p = Pattern.compile(
                "<[^>]*id=\"" + Pattern.quote(id) + "\"[^>]*>(.*?)</[^>]+>",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                if (result.length() > 0) result.append("\n");
                result.append(stripHtml(m.group(1)));
            }
        } else {
            Pattern p = Pattern.compile(
                "<" + Pattern.quote(lower) + "[^>]*>(.*?)</" + Pattern.quote(lower) + ">",
                Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                if (result.length() > 0) result.append("\n");
                result.append(stripHtml(m.group(1)));
            }
        }
        return result.length() > 0 ? result.toString() : "(no matches for: " + selector + ")";
    }

    static String extractByRegex(String html, String regex) {
        if (html == null || regex == null) return html;
        StringBuilder result = new StringBuilder();
        try {
            Pattern p = Pattern.compile(regex, Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                if (result.length() > 0) result.append("\n");
                result.append(m.group());
                if (result.length() > 3000) { result.append("\n...(truncated)"); break; }
            }
        } catch (Exception e) { return "Regex error: " + e.getMessage(); }
        return result.length() > 0 ? result.toString() : "(no matches for regex)";
    }

    static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }
}