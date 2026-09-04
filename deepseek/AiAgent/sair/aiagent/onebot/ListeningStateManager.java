package sair.aiagent.onebot;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.onebot.model.QQMessage;

/**
 * 监听态状态管理器 —— 取消串行队列后，仅保留「拟人化监听态」机制。
 * <p>
 * 职责：
 * <ul>
 *   <li>为每个监听群独立维护监听窗口（8~20 秒随机）与话题上下文</li>
 *   <li>群聊任务处理完后进入该群监听态，关注该群的相关回复</li>
 *   <li>监听态期间该群的相关消息（无需触发词）判定为相关后，由调用方并发处理续期</li>
 *   <li>同群自动续期超过 8 次后暂停监听，需重新 @/名字 触发</li>
 * </ul>
 * 消息本身全部由 {@code QQMessageHandler} 的 execPool 并发处理，本类不再持有队列或消费线程；
 * 各群监听态相互独立、互不覆盖。
 * </p>
 */
public class ListeningStateManager {

    /** 相关性判定回调：由 QQMessageHandler 实现 */
    public interface TaskHandler {
        /** 判定新消息与当前话题的相关性（AI 判定） */
        boolean isRelevant(QQMessage newMsg, TriggerTask currentTask);
    }

    /** 同群自动续期次数上限 */
    private static final int MAX_AUTO_ENQUEUE = 8;
    /** 同群自动续期最小间隔，避免同一话题连续插话 */
    private static final long MIN_AUTO_ENQUEUE_INTERVAL_MS = 12000L;
    /** 监听窗口下限（毫秒） */
    private static final int LISTEN_MIN_MS = 8000;
    /** 监听窗口上限（毫秒） */
    private static final int LISTEN_MAX_MS = 20000;

    private final TaskHandler handler;

    /** 各群的监听窗口结束时间戳（群号 → 截止毫秒时间戳） */
    private final Map<Long, Long> listenEndTimes = new ConcurrentHashMap<>();
    /** 各群进入监听态的话题上下文任务（群号 → 触发任务） */
    private final Map<Long, TriggerTask> listenTasks = new ConcurrentHashMap<>();

    /** 各群自动续期次数统计 */
    private final Map<Long, Integer> autoEnqueueCount = new ConcurrentHashMap<>();
    /** 各群最近一次自动续期时间戳，用于同话题冷却 */
    private final Map<Long, Long> lastAutoEnqueueAt = new ConcurrentHashMap<>();

    /** 情绪状态管理器（用于高好感度用户的主动关注概率监听） */
    private volatile EmotionStateManager emotionManager;

    public ListeningStateManager(TaskHandler handler) {
        this.handler = handler;
    }

    /** 是否正在监听指定群 */
    public boolean isListening(long groupId) {
        if (groupId <= 0) return false;
        Long end = listenEndTimes.get(groupId);
        return end != null && System.currentTimeMillis() < end;
    }

    /**
     * 监听态期间收到群消息：AI 判定相关性，相关或高好感度主动关注则返回 true，
     * 由调用方并发处理续期任务（不再入队）。
     * @return 是否应作为监听续期处理
     */
    public boolean onListeningMessage(QQMessage msg) {
        long gid = msg.getGroupId();
        if (!isListening(gid)) return false;
        TriggerTask ctx = listenTasks.get(gid);
        if (ctx == null) return false;

        // 同群自动续期达到上限 → 暂停监听
        if (autoEnqueueCount.getOrDefault(gid, 0) >= MAX_AUTO_ENQUEUE) {
            return false;
        }

        long now = System.currentTimeMillis();
        long last = lastAutoEnqueueAt.getOrDefault(gid, 0L);
        if (now - last < MIN_AUTO_ENQUEUE_INTERVAL_MS) {
            return false;
        }

        boolean relevant = (handler != null && handler.isRelevant(msg, ctx));
        if (!relevant && !shouldProactiveListen(msg)) {
            return false;
        }

        autoEnqueueCount.merge(gid, 1, Integer::sum);
        lastAutoEnqueueAt.put(gid, now);
        AiAgentActivity.qqLog("[Listen] " + (relevant ? "监听续期命中" : "主动关注命中")
                + ": 群" + gid + " user=" + msg.getUserId()
                + " count=" + autoEnqueueCount.get(gid));
        return true;
    }

    /** 高好感度用户（挚友/恋人/灵魂伴侣）的主动关注概率监听 */
    private boolean shouldProactiveListen(QQMessage msg) {
        if (emotionManager == null) return false;
        long uid = msg.getUserId();
        int level = emotionManager.getAffectionLevelForUser(uid);
        double prob;
        switch (level) {
            case EmotionStateManager.LEVEL_SOULMATE: prob = 0.10; break; // ≥1000：10%
            case EmotionStateManager.LEVEL_LOVER:    prob = 0.05; break; // ≥800：5%
            case EmotionStateManager.LEVEL_BFF:      prob = 0.02; break; // ≥400：挚友关注 2%
            default: return false;
        }
        return ThreadLocalRandom.current().nextDouble() < prob;
    }

    /** 主动触发（@/名字/私聊）时重置该群自动续期计数 */
    public void resetAutoEnqueueCount(long groupId) {
        if (groupId > 0) {
            autoEnqueueCount.remove(groupId);
            lastAutoEnqueueAt.remove(groupId);
        }
    }

    /** 注入情绪状态管理器（供主动关注概率监听使用） */
    public void setEmotionManager(EmotionStateManager em) { this.emotionManager = em; }

    /** 进入指定群的监听态：独立记录该群的话题上下文与监听窗口 */
    public void enterListening(TriggerTask task) {
        long gid = task.getGroupId();
        if (gid <= 0) return;
        // 监听续期任务且已达 8 次边界 → 不再监听
        if (task.isListenRenewal() && autoEnqueueCount.getOrDefault(gid, 0) >= MAX_AUTO_ENQUEUE) {
            AiAgentActivity.qqLog("[Listen] 群" + gid + " 自动续期达上限，暂停监听，需重新触发");
            return;
        }
        listenTasks.put(gid, task);
        long window = LISTEN_MIN_MS
                + ThreadLocalRandom.current().nextLong(LISTEN_MAX_MS - LISTEN_MIN_MS + 1);
        listenEndTimes.put(gid, System.currentTimeMillis() + window);
        AiAgentActivity.qqLog("[Listen] 进入监听态: 群" + gid + " 窗口" + (window / 1000) + "秒");
    }
}
