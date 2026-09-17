package sair.v4.kit;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;

/** 字符串工具。 */
public final class Str {

    private Str() {}

    public static boolean blank(String s) { return s == null || s.trim().isEmpty(); }

    public static boolean has(String s) { return s != null && !s.trim().isEmpty(); }

    public static String nz(String s) { return s == null ? "" : s; }

    public static String trim(String s) { return s == null ? "" : s.trim(); }

    public static String join(Collection<String> parts, String sep) {
        StringBuilder sb = new StringBuilder();
        if (parts != null) {
            for (String p : parts) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** 截断并加省略号（不切坏到 0 长度）。 */
    public static String cut(String s, int max) {
        if (s == null) return "";
        if (max <= 0 || s.length() <= max) return s;
        return s.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 压成单行（多处空白折叠）。 */
    public static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    public static String firstLine(String s) {
        if (s == null) return "";
        int i = s.indexOf('\n');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    public static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    public static String upperFirst(String s) {
        if (blank(s)) return nz(s);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static String lower(String s) { return s == null ? "" : s.toLowerCase(); }

    public static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    public static boolean eqIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    public static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
    }

    public static String day() {
        return new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    }

    public static long nowMs() { return System.currentTimeMillis(); }

    /** 按分隔符切分并去空白、丢空段。 */
    public static List<String> split(String s, String sep) {
        List<String> out = new ArrayList<String>();
        if (blank(s)) return out;
        for (String p : s.split(java.util.regex.Pattern.quote(sep))) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 取命令行第一段作动词，其余作参数。 */
    public static String[] verb(String s) {
        String t = trim(s);
        if (t.isEmpty()) return new String[] {"", ""};
        int i = t.indexOf(' ');
        if (i < 0) return new String[] {t, ""};
        return new String[] {t.substring(0, i), t.substring(i + 1).trim()};
    }

    /** 文件名安全化（去路径分隔与非法字符）。 */
    public static String safeName(String s) {
        if (s == null) return "";
        String t = s.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        if (t.length() > 80) t = t.substring(0, 80);
        return t;
    }
}
