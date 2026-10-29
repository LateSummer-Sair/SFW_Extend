package sair.v4.qq;

import java.util.HashMap;
import java.util.Map;

import sair.v4.kit.Str;

/**
 * 跟进窗口（「接着说」）：她刚回完话之后的一小段时间里，<b>同一个人在同一会话</b>再说话，
 * 就算在叫她 —— 不必重新 @ 或触发词。超时才回到「要触发」。
 *
 * <p><b>时长由她自己定</b>（工具 {@code follow}，夹在 5~20 秒）；她不调就用配置 {@code followupSec}
 * （出厂 10 秒，{@code 0} = 关掉这个功能）。窗口每次被用到、她再回完一句，都会按同一口径重开，
 * 所以可以一直「以此反复」，直到她收工或超时。</p>
 *
 * <p><b>为什么按「会话 + QQ」两维</b>：同一个人换个群说话不算接着聊；同一群里别人插嘴也不算 ——
 * 这一条正好挡住「在群里把窗口借给陌生人」的滥用面。</p>
 */
public final class Focus {

    public static final int MIN_SEC = 5;
    public static final int MAX_SEC = 20;
    public static final int DEF_SEC = 10;
    private static final int MAX_ENTRIES = 256;

    private static final class Win {
        long until;
        long openedAt;
        int sec;
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Win> MAP = new HashMap<String, Win>();

    private Focus() {}

    public static String key(String session, long qq) {
        return Str.nz(session) + "#" + qq;
    }

    /** 夹到 [5,20]；{@code sec <= 0} 原样回 0（= 立刻收工）。 */
    public static int clamp(int sec) {
        if (sec <= 0) return 0;
        if (sec < MIN_SEC) return MIN_SEC;
        if (sec > MAX_SEC) return MAX_SEC;
        return sec;
    }

    /** 开窗口（她自己定）。{@code sec <= 0} 或没有具体的人 ⇒ 不动。 */
    public static void open(String session, long qq, int sec) {
        int s = clamp(sec);
        if (s <= 0 || qq <= 0L) return;
        long now = System.currentTimeMillis();
        Win w = new Win();
        w.until = now + s * 1000L;
        w.openedAt = now;
        w.sec = s;
        synchronized (LOCK) {
            MAP.put(key(session, qq), w);
            if (MAP.size() > MAX_ENTRIES) prune(now);
        }
    }

    /**
     * 她这轮说完之后调用：<b>这轮她自己定过就沿用</b>（{@code openedAt >= turnStart}），
     * 否则按默认值开一个。
     */
    public static void afterTurn(String session, long qq, int defaultSec, long turnStart) {
        int s = clamp(defaultSec);
        if (s <= 0 || qq <= 0L) return;
        String k = key(session, qq);
        synchronized (LOCK) {
            Win w = MAP.get(k);
            if (w != null && w.openedAt >= turnStart) return;
        }
        open(session, qq, s);
    }

    /** 窗口还开着吗（开着 = 这个人现在说话不用再触发）。 */
    public static boolean hot(String session, long qq) {
        if (qq <= 0L) return false;
        long now = System.currentTimeMillis();
        String k = key(session, qq);
        synchronized (LOCK) {
            Win w = MAP.get(k);
            if (w == null) return false;
            if (now > w.until) {
                MAP.remove(k);
                return false;
            }
            return true;
        }
    }

    /** 还剩几秒（没有窗口 ⇒ 0）。 */
    public static int leftSec(String session, long qq) {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            Win w = MAP.get(key(session, qq));
            if (w == null) return 0;
            long left = w.until - now;
            return left <= 0L ? 0 : (int) ((left + 999L) / 1000L);
        }
    }

    /** 收工（她说的「先不听了」）。 */
    public static void close(String session, long qq) {
        synchronized (LOCK) {
            MAP.remove(key(session, qq));
        }
    }

    /** 诊断/探针用：<b>先清过期</b>再计数（否则超时后 size 仍算旧条目，读起来像"窗口还开着"）。 */
    public static int size() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            prune(now);
            return MAP.size();
        }
    }

    /** 探针与重启用。 */
    public static void clear() {
        synchronized (LOCK) {
            MAP.clear();
        }
    }

    public static String stat() {
        long now = System.currentTimeMillis();
        int live = 0;
        synchronized (LOCK) {
            for (Win w : MAP.values()) if (w != null && now <= w.until) live++;
        }
        return "focus=" + live + "/" + size();
    }

    private static void prune(long now) {
        java.util.Iterator<Map.Entry<String, Win>> it = MAP.entrySet().iterator();
        while (it.hasNext()) {
            Win w = it.next().getValue();
            if (w == null || now > w.until) it.remove();
        }
        while (MAP.size() > MAX_ENTRIES && !MAP.isEmpty()) {
            String oldest = null;
            long ts = Long.MAX_VALUE;
            for (Map.Entry<String, Win> e : MAP.entrySet()) {
                if (e.getValue() != null && e.getValue().openedAt < ts) {
                    ts = e.getValue().openedAt;
                    oldest = e.getKey();
                }
            }
            if (oldest == null) break;
            MAP.remove(oldest);
        }
    }
}
