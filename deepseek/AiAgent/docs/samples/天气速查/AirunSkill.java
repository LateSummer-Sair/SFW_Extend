import java.util.*;

/**
 * 演示技能「天气速查」的入口类。
 * <p>入口方法固定叫 {@code airun}：框架按 airun(Map,Map) → airun(Map,String) → airun(String,Map)
 * → airun(String,String) → airun(Map) → airun(String) → airun() 的顺序探测。
 * 本类写了双入参版本，所以自动拿到只读上下文（不必再声明 context: true，本示例仍显式声明了）。</p>
 */
public class AirunSkill {

    public Map<String, Object> airun(Map<String, Object> args, Map<String, Object> ctx) {
        String city = str(args.get("city"), "北京");
        long days = num(args.get("days"), 3L);
        String mode = str(args.get("mode"), "简要");
        Object tags = args.get("tags");     // 声明 array → 这里是 List

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("city", city);
        out.put("days", days);
        out.put("mode", mode);
        out.put("tags", tags == null ? Collections.emptyList() : tags);
        // 上下文是只读投影：谁在哪个通道问的
        out.put("asked_in", ctx == null ? "unknown" : String.valueOf(ctx.get("channel")));
        out.put("by_master", ctx != null && Boolean.TRUE.equals(ctx.get("is_master")));
        out.put("summary", WeatherFmt.summarize(city, days, mode));
        out.put("note", "演示技能，未查询真实天气");
        return out;   // 返回 Map 会被框架自动序列化为 JSON
    }

    private static String str(Object v, String def) {
        return (v == null || String.valueOf(v).trim().isEmpty()) ? def : String.valueOf(v);
    }

    private static long num(Object v, long def) {
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return (v == null) ? def : Long.parseLong(String.valueOf(v).trim());
        } catch (Exception e) {
            return def;
        }
    }
}
