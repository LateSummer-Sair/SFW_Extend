package sair.v4.qq;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 入站消息去重（{@code message_id} + 时间窗）：<b>同一条消息只处理一次</b>。
 *
 * <h3>为什么要有它</h3>
 * <p>NapCat 断线重连会把没确认的事件重放、多连接时同一事件可能投递两次、
 * 有的实现还会补发一次；而 V4 起先把 {@code handleMessage} 写成了"来一条处理一条"，
 * 于是同一条消息被答两遍 —— 主人看到的正是"重复回答"。V3 当年有这道闸
 * （"重复消息登记 + 10 分钟陈旧窗口"），这一版补齐。</p>
 *
 * <h3>口径</h3>
 * <ul>
 *   <li>只在 {@code message_id} 非空时判重（没有 id 的消息一律放行，宁可多回也别吞消息）；</li>
 *   <li>窗口内重复 → 丢弃（调用方打一行 {@code skip reason=dup}）；窗口外（真的隔了很久）→ 当新消息；</li>
 *   <li>有界：最多记 {@value #MAX} 条，超出按插入顺序淘汰最旧的（自愈，不做定时清理）。</li>
 * </ul>
 */
public final class Seen {

    /** 最多记多少条（够覆盖一个窗口期内的高频群聊；超出淘汰最旧）。 */
    public static final int MAX = 4000;

    /** 默认窗口（秒）：V3 是 10 分钟，沿用。 */
    public static final int DEF_WINDOW_SEC = 600;

    private final Map<String, Long> when = new LinkedHashMap<String, Long>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX;
        }
    };

    private long hits = 0;

    /**
     * 这条消息见过吗（顺带登记）。
     *
     * @param id       {@code message_id}（空 = 不判重，返回 false）
     * @param nowMs    当前时间
     * @param windowMs 窗口（毫秒；{@code <=0} = 关闭判重）
     * @return true = 窗口内已见过（调用方应当丢弃）
     */
    public synchronized boolean dup(String id, long nowMs, long windowMs) {
        if (id == null || id.trim().isEmpty() || windowMs <= 0L) return false;
        String k = id.trim();
        Long last = when.get(k);
        if (last != null && nowMs - last.longValue() <= windowMs) {
            hits++;
            return true;
        }
        when.put(k, Long.valueOf(nowMs));
        return false;
    }

    /** 判重命中次数（诊断用）。 */
    public synchronized long hits() { return hits; }

    /** 记了多少条（诊断用）。 */
    public synchronized int size() { return when.size(); }

    /** 清空（重启/测试用）。 */
    public synchronized void clear() {
        when.clear();
        hits = 0;
    }
}
