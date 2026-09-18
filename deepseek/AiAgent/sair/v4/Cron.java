package sair.v4;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 五段式日历表达式（{@code 分 时 日 月 周}）—— <b>基板自带</b>。
 *
 * <p>为什么在基板里：闹钟（到点的事）是基板的能力（{@code alarm} 表 + {@code alarm} 工具 +
 * {@code Tick} 到点唤起）。原先"按日历规则周期做事"是外挂技能 {@code 定时任务} 的活，
 * 主人 2026-09-18 定下"待办并入闹钟、一个账本"⇒ 把日历判定也收进基板，技能退休。</p>
 *
 * <p>支持的写法（与退休技能逐条一致）：{@code *}、{@code *}{@code /n}、{@code a}、
 * {@code a-b}、{@code a-b/n}、{@code a/n}、逗号列表；周 {@code 0}/{@code 7} = 周日。
 * <b>日与周都写了具体值时按 cron 惯例取"或"</b>（{@code 0 0 1 * 1} = 每月 1 号或每周一）。</p>
 */
public final class Cron {

    /** 字段数不对 / 认不出的值 ⇒ null（调用方当"这条不触发"，绝不猜）。 */
    public static Cron parse(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("\\s+", " ");
        if (s.isEmpty()) return null;
        String[] f = s.split(" ");
        if (f.length != 5) return null;
        try {
            Cron c = new Cron();
            field(f[0], 0, 59, c.min, "分");
            field(f[1], 0, 23, c.hour, "时");
            c.domRestricted = field(f[2], 1, 31, c.dom, "日");
            field(f[3], 1, 12, c.month, "月");
            boolean[] w = new boolean[8];
            c.dowRestricted = field(f[4], 0, 7, w, "周");
            for (int i = 0; i <= 6; i++) c.dow[i] = w[i];
            if (w[7]) c.dow[0] = true;                       // 7 也当周日
            return c;
        } catch (Throwable t) {
            return null;
        }
    }

    private final boolean[] min = new boolean[60];
    private final boolean[] hour = new boolean[24];
    private final boolean[] dom = new boolean[32];
    private final boolean[] month = new boolean[13];
    private final boolean[] dow = new boolean[7];
    private boolean domRestricted;
    private boolean dowRestricted;

    private Cron() {
    }

    /** 这一段命中的分钟/时/日/月/周（返回"这一段是不是被限制了"，供"日或周"的判定用）。 */
    private static boolean field(String f, int lo, int hi, boolean[] out, String label) {
        String s = f == null ? "" : f.trim();
        if (s.isEmpty()) throw new IllegalArgumentException(label);
        if ("*".equals(s)) {
            for (int i = lo; i <= hi; i++) out[i] = true;
            return false;
        }
        boolean restricted = false;
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) throw new IllegalArgumentException(label);
            int step = 1;
            int slash = p.indexOf('/');
            if (slash >= 0) {
                step = Integer.parseInt(p.substring(slash + 1).trim());
                if (step <= 0) throw new IllegalArgumentException(label);
                p = p.substring(0, slash).trim();
            }
            int a;
            int b;
            if ("*".equals(p)) {
                a = lo;
                b = hi;
                restricted = true;          // "*/n" 也算限制（与退休技能同一口径）
            } else if (p.indexOf('-') >= 0) {
                String[] r = p.split("-");
                a = Integer.parseInt(r[0].trim());
                b = Integer.parseInt(r[1].trim());
                restricted = true;
            } else {
                a = Integer.parseInt(p);
                b = slash >= 0 ? hi : a;    // "a/n" = 从 a 到最大值每 n 个
                restricted = true;
            }
            if (a < lo || b > hi || a > b) throw new IllegalArgumentException(label);
            for (int i = a; i <= b; i += step) out[i] = true;
        }
        return restricted;
    }

    /** 这个时刻命中吗。 */
    public boolean matches(long ms) {
        LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), ZoneId.systemDefault());
        return matches(t);
    }

    private boolean matches(LocalDateTime t) {
        if (!min[t.getMinute()]) return false;
        if (!hour[t.getHour()]) return false;
        if (!month[t.getMonthValue()]) return false;
        boolean day = dom[t.getDayOfMonth()];
        boolean week = dow[t.getDayOfWeek().getValue() % 7];
        if (domRestricted && dowRestricted) return day || week;   // cron 惯例：两者都限制时取"或"
        if (domRestricted) return day;
        if (dowRestricted) return week;
        return true;
    }

    /**
     * 从现在往后的下一个命中时刻（按分钟扫；超过 {@code maxMinutes} 还没找到 ⇒ 0）。
     * 扫描量级：一天 1440 次廉价判定，找"下周一"这种最多扫 7 天（~1 万次），够快。
     */
    public long next(long nowMs, int maxMinutes) {
        long t = (nowMs / 60000L + 1L) * 60000L;              // 下一分钟整
        int cap = maxMinutes <= 0 ? 366 * 24 * 60 : maxMinutes;
        for (int i = 0; i < cap; i++) {
            if (matches(t)) return t;
            t += 60000L;
        }
        return 0L;
    }

    /** 给人看的一行（回执/日志用）。 */
    public String text() {
        return "cron";
    }
}
