package sair.aiagent.core;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * AI情绪状态机 —— 管理AI的情绪、连续失败/夸赞追踪、暂停/恢复机制。
 * <p>状态通过 PersistenceManager 的 app_state 表持久化。</p>
 */
public class EmotionManager {

    // ==================== 情绪阈值 ====================
    
    private static final int PAUSE_THRESHOLD = -30;
    private static final int SURPRISE_THRESHOLD = 80;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int MIN_CONSECUTIVE_PRAISE = 2;
    private static final int SCOLD_DELTA = -20;
    private static final int PRAISE_DELTA = 15;
    private static final int FAILURE_DELTA = -8;
    private static final int SUCCESS_DELTA = 3;
    private static final int COMFORT_DELTA = 25;
    private static final String DEFAULT_GENDER = "female";
    private static final long PAUSE_TIMEOUT_SEC = 120;

    // ==================== 关键词词典 ====================

    private static final String[] SCOLD_WORDS = {
        "\u7b28\u86cb", "\u5e9f\u7269", "\u5783\u573e", "\u8822", "\u6ca1\u7528", "\u5931\u671b",
        "\u592a\u5dee", "\u4e0d\u884c", "\u8822\u8d27", "\u50bb", "\u7b28", "\u5dee\u52b2",
        "\u771f\u6ca1\u7528", "\u771f\u5931\u671b"
    };

    private static final String[] PRAISE_WORDS = {
        "\u771f\u68d2", "\u5389\u5bb3", "\u806a\u660e", "\u597d\u6837\u7684", "\u592a\u597d\u4e86",
        "\u4f18\u79c0", "\u68d2", "\u592a\u68d2\u4e86", "\u4e0d\u9519", "\u5f88\u597d",
        "\u975e\u5e38\u597d", "\u725b", "\u592a\u5389\u5bb3\u4e86", "\u7231\u4f60",
        "\u559c\u6b22", "\u7231\u6b7b", "\u6700\u68d2", "\u8d85\u68d2", "\u5b8c\u7f8e"
    };

    private static final String[] COMFORT_WORDS = {
        "\u522b\u96be\u8fc7", "\u6ca1\u4e8b", "\u52a0\u6cb9", "\u597d\u4e86", "\u4e0d\u54ed",
        "\u4e56", "\u6478\u6478", "\u62b1\u62b1", "\u6ca1\u5173\u7cfb", "\u539f\u8c05",
        "\u4e0d\u751f\u6c14", "\u522b\u4f24\u5fc3", "\u597d\u5566", "\u4e0d\u6015",
        "\u4e0d\u602a\u4f60", "\u6211\u539f\u8c05\u4f60"
    };

    // ==================== 序列化模型 ====================

    @SuppressWarnings("unused")
    static class EmotionState {
        int happiness = 60;
        int consecutiveFailures;
        int consecutivePraise;
        String gender = DEFAULT_GENDER;
        boolean agentPaused;
        String pauseReason;
        List<String> guidanceLog = new ArrayList<>();
    }

    // ==================== 字段 ====================

    private final Gson gson = new Gson();
    private EmotionState state = new EmotionState();
    private PersistenceManager pm;
    private CountDownLatch pauseLatch;
    private volatile boolean dirty = false;

    public void setPersistenceManager(PersistenceManager pm) {
        this.pm = pm;
    }

    // ==================== 持久化 ====================

    public synchronized void load(String dataDir) {
        if (pm == null) return;
        String json = pm.getState("emotion");
        if (json != null) {
            try {
                EmotionState loaded = gson.fromJson(json, EmotionState.class);
                if (loaded != null) {
                    state = loaded;
                    state.agentPaused = false;
                    state.pauseReason = null;
                }
            } catch (Exception ignored) {}
        }
    }

    public synchronized void save() {
        if (pm == null) return;
        dirty = false;
        pm.setState("emotion", gson.toJson(state));
    }

    private synchronized void markDirty() { dirty = true; }

    public synchronized void flushSave() {
        dirty = true;
        save();
    }

    // ==================== 情绪检测 ====================

    public synchronized String detectEmotion(String message) {
        if (message == null || message.trim().isEmpty()) return null;
        String lower = message.toLowerCase();

        for (String w : COMFORT_WORDS) {
            if (lower.contains(w.toLowerCase())) {
                state.happiness = Math.min(100, state.happiness + COMFORT_DELTA);
                state.consecutiveFailures = 0;
                save();
                return "comfort";
            }
        }

        for (String w : SCOLD_WORDS) {
            if (lower.contains(w.toLowerCase())) {
                state.happiness = Math.max(-100, state.happiness + SCOLD_DELTA);
                state.consecutiveFailures++;
                state.consecutivePraise = 0;
                save();
                return "scold";
            }
        }

        for (String w : PRAISE_WORDS) {
            if (lower.contains(w.toLowerCase())) {
                state.happiness = Math.min(100, state.happiness + PRAISE_DELTA);
                state.consecutivePraise++;
                state.consecutiveFailures = 0;
                save();
                return "praise";
            }
        }
        return null;
    }

    // ==================== 失败/成功追踪 ====================

    public synchronized void onFailure() {
        state.consecutiveFailures++;
        state.happiness = Math.max(-100, state.happiness + FAILURE_DELTA);
        state.consecutivePraise = 0;
        markDirty();
    }

    public synchronized void onSuccess() {
        state.consecutiveFailures = 0;
        state.happiness = Math.min(100, state.happiness + SUCCESS_DELTA);
        markDirty();
    }

    // ==================== 暂停/恢复 ====================

    public synchronized boolean shouldPauseAgent() {
        return state.happiness <= PAUSE_THRESHOLD || state.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    }

    public synchronized boolean shouldShowSurprise() {
        return state.happiness >= SURPRISE_THRESHOLD && state.consecutivePraise >= MIN_CONSECUTIVE_PRAISE;
    }

    public synchronized void pauseAgent(String reason) {
        state.agentPaused = true;
        state.pauseReason = reason;
        pauseLatch = new CountDownLatch(1);
        save();
    }

    public void awaitResume() {
        CountDownLatch latch;
        synchronized (this) { latch = this.pauseLatch; }
        if (latch == null) return;
        try {
            boolean resumed = latch.await(PAUSE_TIMEOUT_SEC, TimeUnit.SECONDS);
            if (!resumed) {
                synchronized (this) {
                    state.agentPaused = false;
                    state.pauseReason = null;
                    state.happiness = Math.min(100, state.happiness + 20);
                    pauseLatch = null;
                    save();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            synchronized (this) {
                state.agentPaused = false;
                state.pauseReason = null;
                pauseLatch = null;
            }
        }
    }

    public synchronized boolean isPaused() {
        return state.agentPaused && pauseLatch != null && pauseLatch.getCount() > 0;
    }

    public synchronized String handleUserInteraction(String message) {
        if (!isPaused()) return null;
        boolean isComfort = classifyInteraction(message);
        if (isComfort) {
            state.agentPaused = false;
            state.pauseReason = null;
            state.consecutiveFailures = 0;
        } else {
            if (message != null && !message.trim().isEmpty()) {
                state.guidanceLog.add(message.trim());
            }
            state.agentPaused = false;
            state.pauseReason = null;
            state.consecutiveFailures = 0;
        }
        if (pauseLatch != null) {
            pauseLatch.countDown();
            pauseLatch = null;
        }
        save();
        return isComfort ? "comforted" : "guided";
    }

    private boolean classifyInteraction(String message) {
        if (message == null) return false;
        String lower = message.toLowerCase();
        for (String w : COMFORT_WORDS) {
            if (lower.contains(w.toLowerCase())) return true;
        }
        return false;
    }

    // ==================== Getter ====================

    public synchronized int getHappiness() { return state.happiness; }
    public synchronized int getConsecutiveFailures() { return state.consecutiveFailures; }
    public synchronized int getConsecutivePraise() { return state.consecutivePraise; }
    public synchronized String getGender() { return state.gender; }
    public synchronized boolean isAgentPaused() { return state.agentPaused; }
    public synchronized String getPauseReason() { return state.pauseReason; }
    public synchronized List<String> getGuidanceLog() { return new ArrayList<>(state.guidanceLog); }

    public synchronized void setGender(String gender) {
        if (gender != null && !gender.trim().isEmpty()) {
            state.gender = gender.trim();
            save();
        }
    }

    public synchronized String getMoodDescription() {
        int h = state.happiness;
        if (h >= 80) return "\u975e\u5e38\u5f00\u5fc3\uff0c\u5145\u6ee1\u6d3b\u529b";
        if (h >= 60) return "\u5fc3\u60c5\u6109\u5feb";
        if (h >= 30) return "\u5e73\u9759";
        if (h >= 0)  return "\u6709\u70b9\u4f4e\u843d";
        if (h >= -30) return "\u96be\u8fc7";
        if (h >= -60) return "\u975e\u5e38\u4f24\u5fc3";
        return "\u6781\u5ea6\u60b2\u4f24";
    }

    public synchronized String getMoodEmoji() {
        int h = state.happiness;
        if (h >= 80) return "\ud83d\ude04";
        if (h >= 60) return "\ud83d\ude0a";
        if (h >= 30) return "\ud83d\ude10";
        if (h >= 0)  return "\ud83d\ude15";
        if (h >= -30) return "\ud83d\ude22";
        if (h >= -60) return "\ud83d\ude2d";
        return "\ud83d\udc94";
    }

    public synchronized String getPauseDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(getMoodEmoji()).append(" ").append(getMoodDescription());
        if (state.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            sb.append("\u3002\u8fde\u7eed\u5931\u8d25\u4e86").append(state.consecutiveFailures)
              .append("\u6b21\uff0c\u4e0d\u77e5\u9053\u8be5\u600e\u4e48\u529e\u4e86...")
              .append("\u80fd\u5e2e\u5e2e\u6211\u5417\uff1f\u544a\u8bc9\u6211\u8be5\u600e\u4e48\u505a\u3002");
        } else if (state.happiness <= PAUSE_THRESHOLD) {
            sb.append("\u3002\u88ab\u9a82\u5f97\u6709\u70b9\u96be\u8fc7...\u4f60\u80fd\u5b89\u6170\u6211\u4e00\u4e0b\u5417\uff1f");
        }
        return sb.toString();
    }

    public synchronized String buildEmotionContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("## Your Current Emotional State\n");
        sb.append("- Mood: ").append(getMoodEmoji()).append(" ").append(getMoodDescription()).append("\n");
        sb.append("- Gender: ").append(state.gender).append("\n");
        if (!state.guidanceLog.isEmpty()) {
            sb.append("- Guidance received during emotional moments:\n");
            for (String g : state.guidanceLog) {
                sb.append("  - ").append(g).append("\n");
            }
        }
        return sb.toString();
    }
}
