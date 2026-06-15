package sair.aiagent.onebot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import sair.aiagent.AiAgentActivity;

/**
 * 内部Agent系统 —— Bot的自主决策和执行能力。
 * <p>
 * 这些Agent让Bot能够自主管理用户和群聊，包括：
 * - BlockAgent: 拉黑/屏蔽用户
 * - MuteAgent: 禁言管理
 * - WarningAgent: 警告系统
 * - RuleAgent: 规则执行
 * </p>
 * 
 * <h3>使用原则</h3>
 * <ul>
 *   <li>Bot觉得烦时，先警告用户联系主人</li>
 *   <li>具备管理权限时可以禁言，但禁止擅自踢人</li>
 *   <li>不能自行退群</li>
 *   <li>所有操作都需要记录日志</li>
 * </ul>
 */
public class InternalAgents {
    
    private final NapCatApi api;
    private final QQMessageHandler handler;
    private final BotPersistenceManager persistence;
    private EmotionStateManager emotionManager;
    
    /** 黑名单映射：userId -> 拉黑原因（从持久化层加载） */
    private final Map<Long, String> blockedUsers = new ConcurrentHashMap<>();
    
    /** 警告计数映射：userId -> 警告次数（从持久化层加载） */
    private final Map<Long, Integer> warningCounts = new ConcurrentHashMap<>();
    
    /** 最后警告时间映射：userId -> 时间戳（从持久化层加载） */
    private final Map<Long, Long> lastWarningTime = new ConcurrentHashMap<>();
    
    public InternalAgents(NapCatApi api, QQMessageHandler handler, BotPersistenceManager persistence) {
        this.api = api;
        this.handler = handler;
        this.persistence = persistence;
        
        // 获取情绪管理器引用
        if (handler != null) {
            this.emotionManager = handler.getEmotionManager();
        }
        
        // 从持久化层加载数据到内存缓存
        loadFromPersistence();
    }
    
    /**
     * 从持久化层加载数据
     */
    private void loadFromPersistence() {
        if (persistence == null) return;
        
        // 加载黑名单
        Map<Long, String> blocked = persistence.getAllBlockedUsers();
        blockedUsers.putAll(blocked);
        
        // 加载警告计数（所有用户，不仅是被拉黑的）
        Map<Long, Integer> allWarnings = persistence.getAllWarningCounts();
        warningCounts.putAll(allWarnings);
        
        // 加载最后警告时间
        for (Long userId : allWarnings.keySet()) {
            Long lastTime = persistence.getLastWarningTime(userId);
            if (lastTime != null) {
                lastWarningTime.put(userId, lastTime);
            }
        }
        
        AiAgentActivity.debugLog("[InternalAgents] 已从持久化层加载: 黑名单=" + blocked.size() + ", 警告用户=" + allWarnings.size());
    }
    
    // ==================== BlockAgent: 拉黑/屏蔽 ====================
    
    /**
     * 检查用户是否被拉黑
     * @param userId QQ号
     * @return true表示已拉黑
     */
    public boolean isBlocked(long userId) {
        return blockedUsers.containsKey(userId);
    }
    
    /**
     * 拉黑用户（需要主人授权）
     * @param userId QQ号
     * @param reason 拉黑原因
     * @param notifyUser 是否通知用户
     */
    public void blockUser(long userId, String reason, boolean notifyUser) {
        if (isBlocked(userId)) {
            AiAgentActivity.debugLog("[BlockAgent] 用户 " + userId + " 已在黑名单中");
            return;
        }
        
        // 持久化存储
        if (persistence != null) {
            persistence.blockUser(userId, reason, "bot");
        }
        
        blockedUsers.put(userId, reason);
        AiAgentActivity.debugLog("[BlockAgent] 已拉黑用户 " + userId + "，原因: " + reason);
        
        if (notifyUser) {
            String message = "你已被拉黑。如有异议，请联系主人处理。\n拉黑原因: " + reason;
            api.sendPrivateMessage(userId, message);
        }
    }
    
    /**
     * 解除拉黑
     * @param userId QQ号
     */
    public void unblockUser(long userId) {
        if (!isBlocked(userId)) {
            AiAgentActivity.debugLog("[BlockAgent] 用户 " + userId + " 不在黑名单中");
            return;
        }
        
        // 持久化存储
        if (persistence != null) {
            persistence.unblockUser(userId);
        }
        
        blockedUsers.remove(userId);
        AiAgentActivity.debugLog("[BlockAgent] 已解除对用户 " + userId + " 的拉黑");
    }
    
    /**
     * 获取拉黑原因
     * @param userId QQ号
     * @return 拉黑原因，未拉黑返回null
     */
    public String getBlockReason(long userId) {
        return blockedUsers.get(userId);
    }
    
    // ==================== WarningAgent: 警告系统 ====================
    
    /**
     * 警告用户
     * @param userId QQ号
     * @param groupId 群号（0表示私聊）
     * @param reason 警告原因
     * @return 当前警告次数
     */
    public int warnUser(long userId, long groupId, String reason) {
        // 持久化存储
        int count = 0;
        if (persistence != null) {
            count = persistence.addWarning(userId, groupId, reason);
        } else {
            count = warningCounts.getOrDefault(userId, 0) + 1;
            warningCounts.put(userId, count);
            lastWarningTime.put(userId, System.currentTimeMillis());
        }
        
        String warningMsg = String.format(
            "⚠️ 警告 (%d/%d)\n原因: %s\n\n" +
            "如果继续不当行为，我可能会采取进一步措施。\n" +
            "如有异议，请联系主人处理。",
            count, 3, reason
        );
        
        if (groupId > 0) {
            api.sendGroupMessage(groupId, "@" + userId + "\n" + warningMsg);
        } else {
            api.sendPrivateMessage(userId, warningMsg);
        }
        
        AiAgentActivity.debugLog("[WarningAgent] 警告用户 " + userId + " (第" + count + "次): " + reason);
        
        return count;
    }
    
    /**
     * 清除警告记录
     * @param userId QQ号
     */
    public void clearWarnings(long userId) {
        // 持久化存储
        if (persistence != null) {
            persistence.clearWarnings(userId);
        }
        
        warningCounts.remove(userId);
        lastWarningTime.remove(userId);
        AiAgentActivity.debugLog("[WarningAgent] 已清除用户 " + userId + " 的警告记录");
    }
    
    /**
     * 获取警告次数
     * @param userId QQ号
     * @return 警告次数
     */
    public int getWarningCount(long userId) {
        if (persistence != null) {
            return persistence.getWarningCount(userId);
        }
        return warningCounts.getOrDefault(userId, 0);
    }
    
    /**
     * 检查是否需要自动禁言（警告3次后）
     * @param userId QQ号
     * @param groupId 群号
     * @return true表示应该禁言
     */
    public boolean shouldAutoMute(long userId, long groupId) {
        int warnings = getWarningCount(userId);
        Long lastWarn = persistence != null ? persistence.getLastWarningTime(userId) : lastWarningTime.get(userId);
        
        // 警告3次且最后一次警告在24小时内
        if (warnings >= 3 && lastWarn != null) {
            long hoursSinceLastWarning = (System.currentTimeMillis() - lastWarn) / (1000 * 60 * 60);
            return hoursSinceLastWarning <= 24;
        }
        
        return false;
    }
    
    // ==================== MuteAgent: 禁言管理 ====================
    
    /**
     * 禁言用户
     * @param groupId 群号
     * @param userId 被禁言的QQ号
     * @param duration 禁言时长（秒）
     * @param reason 禁言原因
     */
    public void muteUser(long groupId, long userId, int duration, String reason) {
        String response = api.muteGroupMember(groupId, userId, duration);
        
        if (api.isSuccess(response)) {
            int minutes = duration / 60;
            AiAgentActivity.debugLog("[MuteAgent] 已禁言用户 " + userId + " " + minutes + "分钟，原因: " + reason);
            
            // 发送通知
            String notice = String.format(
                "🔇 用户 %d 已被禁言 %d 分钟\n原因: %s",
                userId, minutes, reason
            );
            api.sendGroupMessage(groupId, notice);
        } else {
            AiAgentActivity.debugLog("[MuteAgent] 禁言失败: " + response);
        }
    }
    
    /**
     * 解除禁言
     * @param groupId 群号
     * @param userId 被解除禁言的QQ号
     */
    public void unmuteUser(long groupId, long userId) {
        String response = api.muteGroupMember(groupId, userId, 0);
        
        if (api.isSuccess(response)) {
            AiAgentActivity.debugLog("[MuteAgent] 已解除对用户 " + userId + " 的禁言");
            
            String notice = "🔊 用户 " + userId + " 已被解除禁言";
            api.sendGroupMessage(groupId, notice);
        } else {
            AiAgentActivity.debugLog("[MuteAgent] 解除禁言失败: " + response);
        }
    }
    
    /**
     * 全员禁言
     * @param groupId 群号
     * @param enable true开启，false关闭
     */
    public void muteAll(long groupId, boolean enable) {
        String response = api.muteAll(groupId, enable);
        
        if (api.isSuccess(response)) {
            String status = enable ? "开启" : "关闭";
            AiAgentActivity.debugLog("[MuteAgent] 已" + status + "群 " + groupId + " 的全员禁言");
        } else {
            AiAgentActivity.debugLog("[MuteAgent] 全员禁言操作失败: " + response);
        }
    }
    
    // ==================== RuleAgent: 规则执行 ====================
    
    /** 消息频率限制映射：userId -> 最近时间戳列表 */
    private final Map<Long, List<Long>> messageTimestamps = new ConcurrentHashMap<>();
    
    /** 敏感词列表（可从配置加载） */
    private final Set<String> sensitiveWords = ConcurrentHashMap.newKeySet();
    
    /**
     * 检查并执行自动规则
     * @param userId 用户QQ号
     * @param groupId 群号（0表示私聊）
     * @param content 消息内容
     * @return 是否需要拦截该消息
     */
    public boolean checkAndEnforceRules(long userId, long groupId, String content) {
        // 1. 检查是否在黑名单中
        if (isBlocked(userId)) {
            AiAgentActivity.debugLog("[RuleAgent] 拦截黑名单用户 " + userId + " 的消息");
            return true; // 拦截
        }
        
        // 2. 检查敏感词
        if (containsSensitiveWords(content)) {
            AiAgentActivity.debugLog("[RuleAgent] 检测到敏感词，用户 " + userId);
            
            // 触发情绪：判断是辱骂还是开黄腔
            if (isSexualContent(content)) {
                if (emotionManager != null) {
                    emotionManager.onSexualHarassment(userId);
                }
                warnUser(userId, groupId, "发送不当内容（开黄腔）");
            } else {
                if (emotionManager != null) {
                    emotionManager.onInsult(userId);
                }
                warnUser(userId, groupId, "发送敏感内容（辱骂）");
            }
            return true; // 拦截
        }
        
        // 3. 检查频率限制（防刷屏）
        if (isSpamming(userId)) {
            AiAgentActivity.debugLog("[RuleAgent] 检测到刷屏行为，用户 " + userId);
            warnUser(userId, groupId, "频繁发送消息（刷屏）");
            return true; // 拦截
        }
        
        // 4. 检查是否触发自动禁言
        if (groupId > 0 && shouldAutoMute(userId, groupId)) {
            AiAgentActivity.debugLog("[RuleAgent] 用户 " + userId + " 触发自动禁言规则");
            muteUser(groupId, userId, 600, "累计警告3次，自动禁言10分钟");
            clearWarnings(userId); // 禁言后清除警告
            return true; // 拦截
        }
        
        // 5. TODO: 可以添加更多自定义规则
        // - 广告检测
        // - 链接过滤
        // - 图片/视频限制
        // 等等...
        
        return false; // 不拦截
    }
    
    /**
     * 检查消息是否包含敏感词
     */
    private boolean containsSensitiveWords(String content) {
        if (content == null || sensitiveWords.isEmpty()) {
            return false;
        }
        
        String lowerContent = content.toLowerCase();
        for (String word : sensitiveWords) {
            if (lowerContent.contains(word.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否为色情内容
     */
    private boolean isSexualContent(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return lower.contains("色") || 
               lower.contains("黄") ||
               lower.contains("裸") ||
               lower.contains("性") ||
               lower.contains("骚");
    }
    
    /**
     * 添加敏感词
     */
    public void addSensitiveWord(String word) {
        sensitiveWords.add(word);
        AiAgentActivity.debugLog("[RuleAgent] 已添加敏感词: " + word);
    }
    
    /**
     * 移除敏感词
     */
    public void removeSensitiveWord(String word) {
        sensitiveWords.remove(word);
        AiAgentActivity.debugLog("[RuleAgent] 已移除敏感词: " + word);
    }
    
    /**
     * 检查是否刷屏（同一用户5秒内超过5条消息）
     */
    private boolean isSpamming(long userId) {
        long now = System.currentTimeMillis();
        List<Long> timestamps = messageTimestamps.computeIfAbsent(userId, k -> new ArrayList<>());
        
        synchronized (timestamps) {
            // 清理5秒前的旧记录
            timestamps.removeIf(ts -> (now - ts) > 5000);
            // 记录当前消息时间
            timestamps.add(now);
            // 5秒内超过5条即视为刷屏
            return timestamps.size() > 5;
        }
    }
    
    /**
     * 添加自定义规则（由主人定义）
     * @param ruleName 规则名称
     * @param ruleDescription 规则描述
     * @param action 触发动作
     */
    public void addCustomRule(String ruleName, String ruleDescription, Runnable action) {
        // TODO: 实现规则存储和执行
        AiAgentActivity.debugLog("[RuleAgent] 添加自定义规则: " + ruleName + " | 描述: " + ruleDescription);
    }
    
    // ==================== 统计和日志 ====================
    
    /**
     * 获取黑名单列表
     * @return 黑名单映射
     */
    public Map<Long, String> getBlockedList() {
        return Collections.unmodifiableMap(blockedUsers);
    }
    
    /**
     * 获取警告统计
     * @param userId QQ号
     * @return 包含警告次数和最后警告时间的Map
     */
    public Map<String, Object> getWarningStats(long userId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("warning_count", getWarningCount(userId));
        stats.put("last_warning_time", lastWarningTime.get(userId));
        stats.put("is_blocked", isBlocked(userId));
        
        // 添加好感度信息
        if (emotionManager != null) {
            stats.put("affection", emotionManager.getAffection(userId));
            stats.put("attitude", emotionManager.getUserAttitudeDescription(userId));
            stats.put("is_betrayer", emotionManager.isBetrayer(userId));
            stats.put("is_ex_partner", emotionManager.isExPartner(userId));
        }
        
        return stats;
    }
    
    /**
     * 生成好感度报告（用于AI回复）
     * @param userId 查询者QQ号
     * @param targetUserId 被查询者QQ号（0表示查询自己）
     * @return 好感度报告文本
     */
    public String generateAffectionReport(long userId, long targetUserId) {
        if (emotionManager == null) {
            return "抱歉，我暂时无法查询好感度。";
        }
        
        long queryTarget = targetUserId > 0 ? targetUserId : userId;
        int affection = emotionManager.getAffection(queryTarget);
        String attitude = emotionManager.getUserAttitudeDescription(queryTarget);
        
        StringBuilder report = new StringBuilder();
        
        if (targetUserId == 0 || targetUserId == userId) {
            // 查询自己的好感度
            report.append("你对我的好感度是：").append(affection).append("/1000\n");
            report.append("我对你的态度：").append(attitude).append("\n\n");
            
            if (emotionManager.isInRomance() && emotionManager.getRomancePartnerId() == userId) {
                report.append("💕 我们正在恋爱中！\n");
            } else if (emotionManager.isExPartner(userId)) {
                report.append("我们曾经是恋人，但现在已经分手了。\n");
            } else if (emotionManager.isBetrayer(userId)) {
                report.append("⚠️ 你背叛过我，我不想再见到你。\n");
            } else if (affection >= 800) {
                report.append("你对我非常好，我们已经很亲密了！\n");
            } else if (affection >= 500) {
                report.append("我们是好朋友呢~\n");
            } else if (affection >= 200) {
                report.append("我们关系还不错。\n");
            } else if (affection >= 0) {
                report.append("我们是普通朋友。\n");
            } else {
                report.append("我对你有些不满...\n");
            }
        } else {
            // 查询他人的好感度
            report.append("TA对我的好感度是：").append(affection).append("/1000\n");
            report.append("我对TA的态度：").append(attitude).append("\n");
            
            if (emotionManager.isInRomance() && emotionManager.getRomancePartnerId() == queryTarget) {
                report.append("💕 TA是我的恋人！\n");
            } else if (emotionManager.isExPartner(queryTarget)) {
                report.append("TA曾经是我的恋人。\n");
            } else if (emotionManager.isBetrayer(queryTarget)) {
                report.append("⚠️ TA背叛过我，已被永久拉黑。\n");
            }
        }
        
        return report.toString();
    }
    
    /**
     * 重置所有数据（谨慎使用）
     */
    public void resetAll() {
        blockedUsers.clear();
        warningCounts.clear();
        lastWarningTime.clear();
        AiAgentActivity.debugLog("[InternalAgents] 已重置所有Agent数据");
    }
}
