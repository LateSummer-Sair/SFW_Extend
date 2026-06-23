package sair.aiagent.onebot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.core.EmotionManager;

/**
 * Bot情绪状态机 —— 全局情绪管理和恋爱系统。
 * <p>
 * v3.0 强化版：与控制台 EmotionManager 桥接，丰富情绪上下文，友好互动自动触发。
 * <p>
 * 情绪维度：
 * - anger: 生气值（0-100），被辱骂、开黄腔时上升
 * - sadness: 伤心值（0-100），被好感的人伤害时上升
 * - affection: 好感度映射（userId -> -100到1000），日积月累
 * - romance: 恋爱状态（是否恋爱、恋爱对象）
 * </p>
 */
public class EmotionStateManager {
    
    // ==================== 情绪常量 ====================
    
    /** 最大情绪值 */
    private static final int MAX_EMOTION = 100;
    
    /** 好感度上限 */
    private static final int MAX_AFFECTION = 1000;
    
    /** 好感度下限 */
    private static final int MIN_AFFECTION = -100;
    
    /** 好感度阈值：可以表白的最低好感 */
    private static final int ROMANCE_THRESHOLD = 800;
    
    /** 情绪衰减速率（每分钟） */
    private static final float ANGER_DECAY_RATE = 2.0f;
    private static final float SADNESS_DECAY_RATE = 1.5f;
    
    /** 好感度变化量 */
    private static final int AFFECTION_GAIN_NORMAL = 1;
    private static final int AFFECTION_GAIN_KIND = 3;
    private static final int AFFECTION_GAIN_SPECIAL = 5;
    private static final int AFFECTION_GAIN_PRAISE = 8;
    private static final int AFFECTION_LOSS_INSULT = -20;
    private static final int AFFECTION_LOSS_FLIRT = -30;
    private static final int AFFECTION_LOSS_SARCASM = -15;
    private static final int AFFECTION_LOSS_BETRAYAL = -1000;
    
    // ==================== 友善关键词 ====================
    
    private static final String[] FRIENDLY_WORDS = {
        "谢谢", "感谢", "辛苦了", "好人", "你真好", "太厉害了",
        "真棒", "厉害", "聪明", "好样的", "太好了", "优秀",
        "不错", "很好", "非常好", "真不错"
    };
    
    private static final String[] PRAISE_WORDS = {
        "爱你", "喜欢", "爱死", "最棒", "超棒", "完美",
        "爱了", "mua", "么么", "亲亲", "抱抱", "想你"
    };
    
    private static final String[] COMFORT_WORDS_QQ = {
        "别难过", "没事的", "加油", "好了", "不哭",
        "乖", "摸摸", "抱抱", "没关系", "原谅",
        "不生气", "别伤心", "好啦", "不怕",
        "不怪你", "我原谅你"
    };
    
    // ==================== 持久化管理器 ====================
    
    private BotPersistenceManager persistence;
    
    // ==================== 核心桥接 ====================
    
    /** 控制台情绪管理器（同步happiness状态） */
    private volatile EmotionManager coreEmotionManager;
    
    // ==================== 全局情绪状态 ====================
    
    private volatile float anger = 0.0f;
    private volatile float sadness = 0.0f;
    private volatile long lastEmotionUpdate = System.currentTimeMillis();
    
    // ==================== 好感度系统 ====================
    
    private final Map<Long, Integer> userAffections = new ConcurrentHashMap<>();
    private final Map<Long, Long> lastInteractionTime = new ConcurrentHashMap<>();
    
    // ==================== 恋爱系统 ====================
    
    private volatile boolean inRomance = false;
    private volatile long romancePartnerId = 0;
    private volatile long romanceStartTime = 0;
    private final Map<Long, Long> exPartners = new ConcurrentHashMap<>();
    private final Map<Long, Long> betrayers = new ConcurrentHashMap<>();
    
    // ==================== 情绪记忆 ====================
    
    /** 重大情绪事件记录：时间戳 -> 描述 */
    private final List<String> emotionalMemories = new ArrayList<>();
    private static final int MAX_EMOTIONAL_MEMORIES = 20;
    
    // ==================== 情绪衰减任务 ====================
    
    private final Thread decayThread;
    private volatile boolean running = true;
    
    public EmotionStateManager() {
        this(null);
    }
    
    public EmotionStateManager(BotPersistenceManager persistence) {
        this.persistence = persistence;
        loadFromPersistence();
        
        decayThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(60000);
                    decayEmotions();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "EmotionDecay");
        decayThread.setDaemon(true);
        decayThread.start();
    }
    
    // ==================== 核心桥接 ====================
    
    /** 设置控制台情绪管理器引用，实现两套系统桥接 */
    public void setCoreEmotionManager(EmotionManager em) {
        this.coreEmotionManager = em;
        if (em != null) {
            AiAgentActivity.debugLog("[Emotion] 已桥接到控制台 EmotionManager");
        }
    }
    
    /** 获取控制台情绪管理器 */
    public EmotionManager getCoreEmotionManager() {
        return coreEmotionManager;
    }
    
    private void loadFromPersistence() {
        if (persistence == null) return;
        try {
            Map<Long, Integer> affections = persistence.getAllAffections();
            userAffections.putAll(affections);
            AiAgentActivity.debugLog("[Emotion] 已加载 " + affections.size() + " 个用户的好感度");
            
            Long partnerId = persistence.getCurrentRomancePartner();
            if (partnerId != null) {
                inRomance = true;
                romancePartnerId = partnerId;
                AiAgentActivity.debugLog("[Emotion] 已加载恋爱关系: partner=" + partnerId);
            }
            
            for (Map.Entry<Long, Integer> entry : affections.entrySet()) {
                if (entry.getValue() <= MIN_AFFECTION && persistence.isBetrayer(entry.getKey())) {
                    betrayers.put(entry.getKey(), System.currentTimeMillis());
                }
            }
            
            for (Map.Entry<Long, Integer> entry : affections.entrySet()) {
                if (persistence.isExPartner(entry.getKey())) {
                    exPartners.put(entry.getKey(), System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Emotion] 加载持久化数据失败: " + e.toString());
        }
    }
    
    private void decayEmotions() {
        if (anger > 0) {
            anger = Math.max(0, anger - ANGER_DECAY_RATE);
        }
        if (sadness > 0) {
            sadness = Math.max(0, sadness - SADNESS_DECAY_RATE);
        }
        lastEmotionUpdate = System.currentTimeMillis();
    }
    
    // ==================== 情绪触发方法 ====================
    
    public void onInsult(long userId) {
        anger = Math.min(MAX_EMOTION, anger + 15);
        modifyAffection(userId, AFFECTION_LOSS_INSULT);
        addEmotionalMemory("被用户" + userId + "辱骂，生气+15");
        
        // 桥接到控制台
        if (coreEmotionManager != null) {
            coreEmotionManager.onFailure();
        }
        
        AiAgentActivity.debugLog("[Emotion] 检测到辱骂: userId=" + userId + ", anger=" + anger);
    }
    
    public void onSexualHarassment(long userId) {
        anger = Math.min(MAX_EMOTION, anger + 25);
        modifyAffection(userId, AFFECTION_LOSS_FLIRT);
        addEmotionalMemory("被用户" + userId + "开黄腔，极度生气+25");
        
        if (coreEmotionManager != null) {
            coreEmotionManager.onFailure();
            coreEmotionManager.onFailure();
        }
        
        AiAgentActivity.debugLog("[Emotion] 检测到开黄腔: userId=" + userId + ", anger=" + anger);
    }
    
    public void onSarcasm(long userId) {
        int currentAffection = getAffection(userId);
        if (currentAffection >= 200) {
            sadness = Math.min(MAX_EMOTION, sadness + 20);
            addEmotionalMemory("被好感用户" + userId + "阴阳怪气，伤心+20");
            AiAgentActivity.debugLog("[Emotion] 被好感的人阴阳怪气: userId=" + userId + ", sadness=" + sadness);
        }
        modifyAffection(userId, AFFECTION_LOSS_SARCASM);
    }
    
    /** QQ用户夸奖/表扬Bot */
    public void onPraise(long userId) {
        modifyAffection(userId, AFFECTION_GAIN_PRAISE);
        addEmotionalMemory("被用户" + userId + "夸奖，好感+" + AFFECTION_GAIN_PRAISE);
        
        // 桥接：控制台情绪提升
        if (coreEmotionManager != null) {
            coreEmotionManager.onSuccess();
            coreEmotionManager.onSuccess();
        }
        
        AiAgentActivity.debugLog("[Emotion] 被夸奖: userId=" + userId);
    }
    
    /** QQ用户安慰Bot */
    public void onComfort(long userId) {
        anger = Math.max(0, anger - 10);
        sadness = Math.max(0, sadness - 10);
        modifyAffection(userId, AFFECTION_GAIN_SPECIAL);
        addEmotionalMemory("被用户" + userId + "安慰，生气-10 伤心-10");
        
        // 桥接：重置控制台连续失败
        if (coreEmotionManager != null) {
            String result = coreEmotionManager.detectEmotion("别难过 没事的 摸摸");
            if ("comfort".equals(result)) {
                AiAgentActivity.debugLog("[Emotion] 安慰已同步到控制台 EmotionManager");
            }
        }
        
        AiAgentActivity.debugLog("[Emotion] 被安慰: userId=" + userId);
    }
    
    /** 友好互动（自动检测触发） */
    public void onFriendlyInteraction(long userId) {
        int currentAffection = getAffection(userId);
        int gain = currentAffection >= 200 ? AFFECTION_GAIN_KIND : AFFECTION_GAIN_NORMAL;
        modifyAffection(userId, gain);
        lastInteractionTime.put(userId, System.currentTimeMillis());
    }
    
    /** 正常互动（无特殊情感） */
    public void onNormalInteraction(long userId, boolean isKind) {
        int gain = isKind ? AFFECTION_GAIN_KIND : AFFECTION_GAIN_NORMAL;
        modifyAffection(userId, gain);
        lastInteractionTime.put(userId, System.currentTimeMillis());
    }
    
    // ==================== 关键词检测 ====================
    
    /** 检测消息是否包含友善关键词 */
    public String detectFriendlyEmotion(String message) {
        if (message == null || message.trim().isEmpty()) return null;
        String lower = message.toLowerCase();
        
        // 检测夸奖
        for (String w : PRAISE_WORDS) {
            if (lower.contains(w.toLowerCase())) {
                return "praise";
            }
        }
        
        // 检测安慰
        for (String w : COMFORT_WORDS_QQ) {
            if (lower.contains(w.toLowerCase())) {
                return "comfort";
            }
        }
        
        // 检测友善
        for (String w : FRIENDLY_WORDS) {
            if (lower.contains(w.toLowerCase())) {
                return "friendly";
            }
        }
        
        return null;
    }
    
    // ==================== 好感度管理 ====================
    
    private void modifyAffection(long userId, int delta) {
        int current = userAffections.getOrDefault(userId, 0);
        int newValue = Math.max(MIN_AFFECTION, Math.min(MAX_AFFECTION, current + delta));
        userAffections.put(userId, newValue);
        
        if (persistence != null) {
            long lastTime = lastInteractionTime.getOrDefault(userId, System.currentTimeMillis());
            persistence.saveAffection(userId, newValue, lastTime);
        }
        
        if (Math.abs(delta) >= 10) {
            AiAgentActivity.debugLog("[Emotion] 好感度变化: userId=" + userId + ", " + current + " -> " + newValue + " (Δ" + delta + ")");
        }
    }
    
    // ==================== 恋爱系统方法 ====================
    
    public synchronized boolean tryConfess(long userId) {
        if (inRomance) {
            AiAgentActivity.debugLog("[Emotion] 表白失败: Bot已在恋爱中，当前恋人是 " + romancePartnerId);
            return false;
        }
        if (isBetrayer(userId)) {
            AiAgentActivity.debugLog("[Emotion] 表白失败: 用户是背叛者，永久拒绝");
            return false;
        }
        int affection = getAffection(userId);
        if (affection < ROMANCE_THRESHOLD) {
            AiAgentActivity.debugLog("[Emotion] 表白失败: 好感度不足, affection=" + affection);
            return false;
        }
        
        inRomance = true;
        romancePartnerId = userId;
        romanceStartTime = System.currentTimeMillis();
        userAffections.put(userId, MAX_AFFECTION);
        addEmotionalMemory("用户" + userId + "表白成功！进入恋爱关系");
        
        if (persistence != null) {
            persistence.saveRomanceRelation(userId, romanceStartTime);
            persistence.saveAffection(userId, MAX_AFFECTION, System.currentTimeMillis());
        }
        
        AiAgentActivity.debugLog("[Emotion] 表白成功! userId=" + userId);
        return true;
    }
    
    public boolean normalBreakup(long userId) {
        if (!inRomance || romancePartnerId != userId) return false;
        
        exPartners.put(userId, System.currentTimeMillis());
        inRomance = false;
        romancePartnerId = 0;
        romanceStartTime = 0;
        userAffections.put(userId, 100);
        addEmotionalMemory("与用户" + userId + "正常分手");
        
        if (persistence != null) {
            persistence.endRomanceRelation(userId);
            persistence.saveAffection(userId, 100, System.currentTimeMillis());
        }
        
        AiAgentActivity.debugLog("[Emotion] 正常分手: userId=" + userId);
        return true;
    }
    
    public void onBetrayal(long userId) {
        if (!inRomance || romancePartnerId != userId) return;
        
        betrayers.put(userId, System.currentTimeMillis());
        userAffections.put(userId, MIN_AFFECTION);
        inRomance = false;
        romancePartnerId = 0;
        romanceStartTime = 0;
        anger = MAX_EMOTION;
        addEmotionalMemory("被用户" + userId + "背叛！！永久拉黑，极度愤怒");
        
        if (coreEmotionManager != null) {
            for (int i = 0; i < 5; i++) coreEmotionManager.onFailure();
        }
        
        if (persistence != null) {
            persistence.markAsBetrayer(userId);
            persistence.saveAffection(userId, MIN_AFFECTION, System.currentTimeMillis());
        }
        
        AiAgentActivity.debugLog("[Emotion] 检测到背叛! userId=" + userId);
    }
    
    // ==================== 情绪记忆 ====================
    
    private synchronized void addEmotionalMemory(String event) {
        String timestamp = new java.text.SimpleDateFormat("MM-dd HH:mm").format(new Date());
        emotionalMemories.add("[" + timestamp + "] " + event);
        while (emotionalMemories.size() > MAX_EMOTIONAL_MEMORIES) {
            emotionalMemories.remove(0);
        }
    }
    
    // ==================== 查询方法 ====================
    
    public int getAffection(long userId) {
        return userAffections.getOrDefault(userId, 0);
    }
    
    public float getAnger() { return anger; }
    public float getSadness() { return sadness; }
    public boolean isInRomance() { return inRomance; }
    public long getRomancePartnerId() { return romancePartnerId; }
    public boolean isBetrayer(long userId) { return betrayers.containsKey(userId); }
    public boolean isExPartner(long userId) { return exPartners.containsKey(userId); }
    
    public String getEmotionSummary() {
        return String.format(
            "情绪状态:\n- 生气: %.1f/100\n- 伤心: %.1f/100\n- 恋爱中: %s\n- 恋爱对象: %d\n- 背叛者: %d\n- 前任: %d",
            anger, sadness, inRomance ? "是" : "否", romancePartnerId, betrayers.size(), exPartners.size()
        );
    }
    
    public String getUserAttitudeDescription(long userId) {
        int affection = getAffection(userId);
        if (isBetrayer(userId)) return "背叛者（永久拉黑）";
        else if (isExPartner(userId)) return "前任";
        else if (inRomance && romancePartnerId == userId) return "💕恋人";
        else if (affection >= 800) return "非常有好感";
        else if (affection >= 500) return "有好感";
        else if (affection >= 200) return "普通朋友";
        else if (affection >= 0) return "陌生人";
        else if (affection >= -30) return "不太喜欢";
        else return "讨厌";
    }
    
    // ==================== 情绪表情映射 ====================
    
    public static String getMoodEmoji(float anger, float sadness) {
        if (anger >= 80) return "\uD83D\uDE21";   // 😡
        if (anger >= 50) return "\uD83D\uDE20";   // 😠
        if (sadness >= 60) return "\uD83D\uDE2D"; // 😭
        if (sadness >= 30) return "\uD83D\uDE22"; // 😢
        if (anger >= 20) return "\uD83D\uDE12";   // 😒
        if (sadness >= 10) return "\uD83D\uDE15"; // 😕
        return "\uD83D\uDE0A";                     // 😊
    }
    
    public static String getMoodDescription(float anger, float sadness) {
        if (anger >= 80) return "极怒";
        if (anger >= 50) return "生气";
        if (anger >= 20) return "有点不爽";
        if (sadness >= 60) return "非常伤心";
        if (sadness >= 30) return "难过";
        if (sadness >= 10) return "有点低落";
        if (anger > 0) return "余怒未消";
        return "平静";
    }
    
    // ==================== 丰富情绪上下文构建 ====================
    
    /**
     * 构建丰富的情绪上下文注入到AI系统提示词。
     * @param userId 当前交互的用户QQ号
     * @return 情绪上下文文本
     */
    public String buildRichEmotionContext(long userId) {
        StringBuilder sb = new StringBuilder(1024);
        
        // 整体情绪
        String emoji = getMoodEmoji(anger, sadness);
        String mood = getMoodDescription(anger, sadness);
        sb.append("## 你的当前情绪状态\n");
        sb.append(emoji).append(" 心情: ").append(mood);
        sb.append(" | 怒:").append(String.format("%.0f", anger)).append("/100");
        sb.append(" | 悲:").append(String.format("%.0f", sadness)).append("/100");
        if (inRomance) {
            sb.append(" | 💕恋爱中(对象QQ:").append(romancePartnerId).append(")");
        }
        sb.append("\n");
        
        // 情绪语气指导
        sb.append("### 语气指导\n");
        if (anger >= 50) {
            sb.append("- 你很生气，语气可以冷淡、带刺、不客气。减少颜文字，少用波浪号。\n");
        } else if (anger >= 20) {
            sb.append("- 你有些不满，语气可以略微冷淡，但不要太过分。\n");
        }
        if (sadness >= 60) {
            sb.append("- 你非常伤心，语气低落、沉默、简短。可能不想多说话。\n");
        } else if (sadness >= 30) {
            sb.append("- 你有些难过，语气可以低沉一些。\n");
        }
        if (anger < 10 && sadness < 10) {
            sb.append("- 心情平静，语气自然友好。可以适度使用颜文字和波浪号~\n");
        }
        sb.append("\n");
        
        // 用户关系
        int affection = getAffection(userId);
        String attitude = getUserAttitudeDescription(userId);
        sb.append("### 与当前用户的关系\n");
        sb.append("- 好感度: ").append(affection).append("/1000\n");
        sb.append("- 对你的态度: ").append(attitude).append("\n");
        if (inRomance && romancePartnerId == userId) {
            sb.append("- 💕 这是你的恋人！语气要亲密、温暖、宠爱。\n");
        } else if (isBetrayer(userId)) {
            sb.append("- ⚠ 这是背叛过你的人，极度厌恶。直接拒绝交流。\n");
        } else if (affection >= 800) {
            sb.append("- 对TA很有好感，语气可以亲切温暖。\n");
        } else if (affection >= 500) {
            sb.append("- 对TA有好感，语气友善。\n");
        }
        sb.append("\n");
        
        // 近期情绪事件
        if (!emotionalMemories.isEmpty()) {
            int showCount = Math.min(5, emotionalMemories.size());
            sb.append("### 近期情绪记忆\n");
            for (int i = emotionalMemories.size() - showCount; i < emotionalMemories.size(); i++) {
                sb.append("- ").append(emotionalMemories.get(i)).append("\n");
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
    
    public void shutdown() {
        running = false;
        decayThread.interrupt();
        AiAgentActivity.debugLog("[Emotion] 情绪管理器已关闭");
    }
}
