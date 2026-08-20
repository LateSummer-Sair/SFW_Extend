package sair.aiagent.core;

import java.io.File;
import java.util.regex.Pattern;

/**
 * TagExecutor 的辅助处理器：批量文件操作 + HTML/文本提取工具。
 */
class TagHandlers {

    // ==================== 批量文件操作 ====================

    static String executeBatchRename(String content) {
        if (content == null || content.trim().isEmpty())
            return "[batchrename] usage: <batchrename dir=/path pattern=regex replacement=text> or with preview prefix";
        String c = content.trim();
        String dir = extractParam(c, "dir");
        String pattern = extractParam(c, "pattern");
        String replacement = extractParam(c, "replacement");
        boolean preview = c.startsWith("preview") || c.contains("preview ");
        if (dir.isEmpty() || pattern.isEmpty())
            return "[batchrename] needs dir and pattern params";
        File d = new File(dir);
        if (!d.exists() || !d.isDirectory()) return "[batchrename] dir not found: " + dir;
        File[] files = d.listFiles();
        if (files == null || files.length == 0) return "[batchrename] no files in: " + dir;
        Pattern pat;
        try { pat = Pattern.compile(pattern); }
        catch (Exception e) { return "[batchrename] invalid regex: " + pattern; }
        StringBuilder sb = new StringBuilder("[batchrename] ");
        if (preview) sb.append("PREVIEW:\n"); else sb.append((replacement.isEmpty() ? "matches" : "rename") + ":\n");
        int count = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            java.util.regex.Matcher m = pat.matcher(name);
            if (m.find()) {
                String newName = replacement.isEmpty() ? name : m.replaceAll(replacement);
                sb.append("  ").append(name).append(" -> ").append(newName).append("\n");
                if (!preview && !replacement.isEmpty()) {
                    File dest = new File(d, newName);
                    if (dest.exists()) { sb.append("    SKIP: already exists\n"); continue; }
                    if (f.renameTo(dest)) count++;
                } else if (preview) { count++; }
            }
        }
        sb.append("  Total: ").append(count).append(" files");
        return sb.toString();
    }

    static String executeBatchConvert(String content) {
        if (content == null || content.trim().isEmpty())
            return "[batchconvert] usage: <batchconvert dir=/path from=EXT to=EXT>";
        String c = content.trim();
        String dir = extractParam(c, "dir");
        String from = extractParam(c, "from");
        String to = extractParam(c, "to");
        if (dir.isEmpty() || from.isEmpty() || to.isEmpty())
            return "[batchconvert] needs dir, from, to params";
        File d = new File(dir);
        if (!d.exists() || !d.isDirectory()) return "[batchconvert] dir not found: " + dir;
        File[] files = d.listFiles();
        if (files == null || files.length == 0) return "[batchconvert] no files in: " + dir;
        StringBuilder sb = new StringBuilder("[batchconvert]");
        int count = 0;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (!f.isFile() || !name.endsWith("." + from.toLowerCase())) continue;
            String base = f.getName().substring(0, f.getName().length() - from.length() - 1);
            File dest = new File(d, base + "." + to);
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img == null) {
                    sb.append("\n  FAIL: ").append(f.getName()).append(" (not readable)");
                    continue;
                }
                if (!javax.imageio.ImageIO.write(img, to, dest)) {
                    sb.append("\n  FAIL: ").append(f.getName()).append(" (no writer for ").append(to).append(")");
                    continue;
                }
                sb.append("\n  ").append(f.getName()).append(" -> ").append(dest.getName());
                count++;
            } catch (Exception e) {
                sb.append("\n  ERROR: ").append(f.getName()).append(" - ").append(e.getMessage());
            }
        }
        sb.append("\n  Converted: ").append(count).append(" files");
        return sb.toString();
    }

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