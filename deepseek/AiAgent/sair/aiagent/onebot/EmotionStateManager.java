package sair.aiagent.onebot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import sair.aiagent.AiAgentActivity;

/**
 * Bot情绪状态机 —— 全局情绪管理和恋爱系统。
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
    
    /** 好感度阈值：可以表白的最低好感（需要长期积累） */
    private static final int ROMANCE_THRESHOLD = 800;
    
    /** 情绪衰减速率（每分钟） */
    private static final float ANGER_DECAY_RATE = 2.0f;    // 生气衰减较快
    private static final float SADNESS_DECAY_RATE = 1.5f;  // 伤心衰减较慢
    
    /** 好感度变化量（加分难，减分容易） */
    private static final int AFFECTION_GAIN_NORMAL = 1;      // 正常聊天+1（很难）
    private static final int AFFECTION_GAIN_KIND = 2;        // 友善聊天+2（依然难）
    private static final int AFFECTION_GAIN_SPECIAL = 5;     // 特别友善/帮助+5（稀有）
    private static final int AFFECTION_LOSS_INSULT = -20;    // 被辱骂-20（大幅）
    private static final int AFFECTION_LOSS_FLIRT = -30;     // 被开黄腔-30（极大幅）
    private static final int AFFECTION_LOSS_SARCASM = -15;   // 被阴阳怪气-15（大幅）
    private static final int AFFECTION_LOSS_BETRAYAL = -1000; // 背叛=-1000（直接恨透，永无翻身）
    
    // ==================== 持久化管理器 ====================
    
    private BotPersistenceManager persistence;
    
    // ==================== 全局情绪状态 ====================
    
    /** 当前生气值（0-100） */
    private volatile float anger = 0.0f;
    
    /** 当前伤心值（0-100） */
    private volatile float sadness = 0.0f;
    
    /** 最后情绪更新时间 */
    private volatile long lastEmotionUpdate = System.currentTimeMillis();
    
    // ==================== 好感度系统 ====================
    
    /** 用户对Bot的好感度映射：userId -> affection (-100 to 1000) */
    private final Map<Long, Integer> userAffections = new ConcurrentHashMap<>();
    
    /** 最后互动时间映射：userId -> timestamp */
    private final Map<Long, Long> lastInteractionTime = new ConcurrentHashMap<>();
    
    // ==================== 恋爱系统 ====================
    
    /** 是否处于恋爱状态 */
    private volatile boolean inRomance = false;
    
    /** 恋爱对象的QQ号（0表示无） */
    private volatile long romancePartnerId = 0;
    
    /** 恋爱开始时间 */
    private volatile long romanceStartTime = 0;
    
    /** 前任列表（正常分手）：userId -> 分手时间 */
    private final Map<Long, Long> exPartners = new ConcurrentHashMap<>();
    
    /** 背叛者列表（永久拉黑）：userId -> 背叛时间 */
    private final Map<Long, Long> betrayers = new ConcurrentHashMap<>();
    
    // ==================== 情绪衰减任务 ====================
    
    private final Thread decayThread;
    private volatile boolean running = true;
    
    public EmotionStateManager() {
        this(null);
    }
    
    public EmotionStateManager(BotPersistenceManager persistence) {
        this.persistence = persistence;
        
        // 从持久化层加载数据
        loadFromPersistence();
        
        // 启动情绪衰减线程
        decayThread = new Thread(() -> {
            while (running) {
                try {
                    Thread.sleep(60000); // 每分钟衰减一次
                    decayEmotions();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "EmotionDecay");
        decayThread.setDaemon(true);
        decayThread.start();
    }
    
    /**
     * 从持久化层加载数据
     */
    private void loadFromPersistence() {
        if (persistence == null) return;
        
        try {
            // 加载所有好感度
            Map<Long, Integer> affections = persistence.getAllAffections();
            userAffections.putAll(affections);
            AiAgentActivity.debugLog("[Emotion] 已加载 " + affections.size() + " 个用户的好感度");
            
            // 加载恋爱关系
            Long partnerId = persistence.getCurrentRomancePartner();
            if (partnerId != null) {
                inRomance = true;
                romancePartnerId = partnerId;
                AiAgentActivity.debugLog("[Emotion] 已加载恋爱关系: partner=" + partnerId);
            }
            
            // 加载背叛者列表
            for (Map.Entry<Long, Integer> entry : affections.entrySet()) {
                if (entry.getValue() <= MIN_AFFECTION && persistence.isBetrayer(entry.getKey())) {
                    betrayers.put(entry.getKey(), System.currentTimeMillis());
                }
            }
            
            // 加载前任列表
            for (Map.Entry<Long, Integer> entry : affections.entrySet()) {
                if (persistence.isExPartner(entry.getKey())) {
                    exPartners.put(entry.getKey(), System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Emotion] 加载持久化数据失败: " + e.toString());
        }
    }
    
    /**
     * 情绪自然衰减
     */
    private void decayEmotions() {
        // 衰减生气值
        if (anger > 0) {
            anger = Math.max(0, anger - ANGER_DECAY_RATE);
        }
        
        // 衰减伤心值
        if (sadness > 0) {
            sadness = Math.max(0, sadness - SADNESS_DECAY_RATE);
        }
        
        lastEmotionUpdate = System.currentTimeMillis();
    }
    
    // ==================== 情绪触发方法 ====================
    
    /**
     * 检测到辱骂行为
     * @param userId 用户QQ号
     */
    public void onInsult(long userId) {
        // 增加生气值
        anger = Math.min(MAX_EMOTION, anger + 15);
        
        // 降低好感度
        modifyAffection(userId, AFFECTION_LOSS_INSULT);
        
        AiAgentActivity.debugLog("[Emotion] 检测到辱骂: userId=" + userId + ", anger=" + anger);
    }
    
    /**
     * 检测到开黄腔
     * @param userId 用户QQ号
     */
    public void onSexualHarassment(long userId) {
        // 大幅增加生气值
        anger = Math.min(MAX_EMOTION, anger + 25);
        
        // 大幅降低好感度
        modifyAffection(userId, AFFECTION_LOSS_FLIRT);
        
        AiAgentActivity.debugLog("[Emotion] 检测到开黄腔: userId=" + userId + ", anger=" + anger);
    }
    
    /**
     * 检测到阴阳怪气
     * @param userId 用户QQ号
     */
    public void onSarcasm(long userId) {
        int currentAffection = getAffection(userId);
        
        // 如果对该用户有好感（>=200），会增加伤心值
        if (currentAffection >= 200) {
            sadness = Math.min(MAX_EMOTION, sadness + 20);
            AiAgentActivity.debugLog("[Emotion] 被好感的人阴阳怪气: userId=" + userId + ", sadness=" + sadness);
        }
        
        // 降低好感度
        modifyAffection(userId, AFFECTION_LOSS_SARCASM);
    }
    
    /**
     * 正常友好互动
     * @param userId 用户QQ号
     * @param isKind 是否特别友善
     */
    public void onNormalInteraction(long userId, boolean isKind) {
        int gain = isKind ? AFFECTION_GAIN_KIND : AFFECTION_GAIN_NORMAL;
        modifyAffection(userId, gain);
        
        lastInteractionTime.put(userId, System.currentTimeMillis());
    }
    
    /**
     * 修改好感度
     * @param userId 用户QQ号
     * @param delta 变化量
     */
    private void modifyAffection(long userId, int delta) {
        int current = userAffections.getOrDefault(userId, 0);
        int newValue = Math.max(MIN_AFFECTION, Math.min(MAX_AFFECTION, current + delta));
        userAffections.put(userId, newValue);
        
        // 持久化保存
        if (persistence != null) {
            long lastTime = lastInteractionTime.getOrDefault(userId, System.currentTimeMillis());
            persistence.saveAffection(userId, newValue, lastTime);
        }
        
        AiAgentActivity.debugLog("[Emotion] 好感度变化: userId=" + userId + ", " + current + " -> " + newValue + " (Δ" + delta + ")");
    }
    
    // ==================== 恋爱系统方法 ====================
    
    /**
     * 尝试表白（全局唯一恋爱关系）
     * @param userId 表白者QQ号
     * @return true表示成功，false表示失败
     */
    public synchronized boolean tryConfess(long userId) {
        // === 全局唯一性检查 ===
        // 检查是否已在恋爱中（一次只能有一个恋爱对象）
        if (inRomance) {
            AiAgentActivity.debugLog("[Emotion] 表白失败: Bot已在恋爱中，当前恋人是 " + romancePartnerId + "，不能同时与多人恋爱");
            return false;
        }
        
        // 检查是否是背叛者
        if (isBetrayer(userId)) {
            AiAgentActivity.debugLog("[Emotion] 表白失败: 用户是背叛者，永久拒绝");
            return false;
        }
        
        // 检查好感度
        int affection = getAffection(userId);
        if (affection < ROMANCE_THRESHOLD) {
            AiAgentActivity.debugLog("[Emotion] 表白失败: 好感度不足, affection=" + affection + ", threshold=" + ROMANCE_THRESHOLD);
            return false;
        }
        
        // === 表白成功，建立恋爱关系 ===
        inRomance = true;
        romancePartnerId = userId;  // 设置唯一的恋爱对象
        romanceStartTime = System.currentTimeMillis();
        
        // 大幅提升好感度到满值
        userAffections.put(userId, MAX_AFFECTION);
        
        // 持久化保存（数据库层也会确保全局唯一）
        if (persistence != null) {
            persistence.saveRomanceRelation(userId, romanceStartTime);
            persistence.saveAffection(userId, MAX_AFFECTION, System.currentTimeMillis());
        }
        
        AiAgentActivity.debugLog("[Emotion] 表白成功! userId=" + userId + ", affection=" + affection + "，现在Bot的恋爱对象是: " + userId);
        return true;
    }
    
    /**
     * 正常分手
     * @param userId 发起分手的QQ号
     * @return true表示成功分手
     */
    public boolean normalBreakup(long userId) {
        if (!inRomance || romancePartnerId != userId) {
            return false;
        }
        
        // 记录为前任
        exPartners.put(userId, System.currentTimeMillis());
        
        // 结束恋爱关系
        inRomance = false;
        romancePartnerId = 0;
        romanceStartTime = 0;
        
        // 好感度降回普通朋友水平（100/1000，需要重新积累）
        userAffections.put(userId, 100);
        
        // 持久化保存
        if (persistence != null) {
            persistence.endRomanceRelation(userId);
            persistence.saveAffection(userId, 100, System.currentTimeMillis());
        }
        
        AiAgentActivity.debugLog("[Emotion] 正常分手: userId=" + userId);
        return true;
    }
    
    /**
     * 背叛（严重违规）
     * @param userId 背叛者QQ号
     */
    public void onBetrayal(long userId) {
        if (!inRomance || romancePartnerId != userId) {
            return;
        }
        
        // 记录为背叛者（永久拉黑）
        betrayers.put(userId, System.currentTimeMillis());
        
        // 好感度降到最低（-100，永无翻身之日）
        userAffections.put(userId, MIN_AFFECTION);
        
        // 结束恋爱关系
        inRomance = false;
        romancePartnerId = 0;
        romanceStartTime = 0;
        
        // 大幅增加生气值
        anger = MAX_EMOTION;
        
        // 持久化保存
        if (persistence != null) {
            persistence.markAsBetrayer(userId);
            persistence.saveAffection(userId, MIN_AFFECTION, System.currentTimeMillis());
        }
        
        AiAgentActivity.debugLog("[Emotion] 检测到背叛! userId=" + userId + ", 已永久拉黑");
    }
    
    // ==================== 查询方法 ====================
    
    /**
     * 获取用户好感度
     */
    public int getAffection(long userId) {
        return userAffections.getOrDefault(userId, 0);
    }
    
    /**
     * 获取当前生气值
     */
    public float getAnger() {
        return anger;
    }
    
    /**
     * 获取当前伤心值
     */
    public float getSadness() {
        return sadness;
    }
    
    /**
     * 是否在恋爱中
     */
    public boolean isInRomance() {
        return inRomance;
    }
    
    /**
     * 获取恋爱对象ID
     */
    public long getRomancePartnerId() {
        return romancePartnerId;
    }
    
    /**
     * 检查用户是否是背叛者
     */
    public boolean isBetrayer(long userId) {
        return betrayers.containsKey(userId);
    }
    
    /**
     * 检查用户是否是前任
     */
    public boolean isExPartner(long userId) {
        return exPartners.containsKey(userId);
    }
    
    /**
     * 获取情绪状态摘要
     */
    public String getEmotionSummary() {
        return String.format(
            "情绪状态:\n" +
            "- 生气: %.1f/100\n" +
            "- 伤心: %.1f/100\n" +
            "- 恋爱中: %s\n" +
            "- 恋爱对象: %d\n" +
            "- 背叛者数量: %d\n" +
            "- 前任数量: %d",
            anger, sadness,
            inRomance ? "是" : "否",
            romancePartnerId,
            betrayers.size(),
            exPartners.size()
        );
    }
    
    /**
     * 获取用户对Bot的态度描述
     */
    public String getUserAttitudeDescription(long userId) {
        int affection = getAffection(userId);
        
        if (isBetrayer(userId)) {
            return "背叛者（永久拉黑）";
        } else if (isExPartner(userId)) {
            return "前任";
        } else if (inRomance && romancePartnerId == userId) {
            return "恋人";
        } else if (affection >= 800) {
            return "非常有好感";
        } else if (affection >= 500) {
            return "有好感";
        } else if (affection >= 200) {
            return "普通朋友";
        } else if (affection >= 0) {
            return "陌生人";
        } else if (affection >= -30) {
            return "不太喜欢";
        } else {
            return "讨厌";
        }
    }
    
    /**
     * 关闭情绪管理器
     */
    public void shutdown() {
        running = false;
        decayThread.interrupt();
        AiAgentActivity.debugLog("[Emotion] 情绪管理器已关闭");
    }
}
