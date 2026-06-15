package sair.aiagent.onebot;

import java.util.*;
import java.util.concurrent.*;

import sair.aiagent.AiAgentActivity;

/**
 * 群管理员Agent —— Bot作为群管理员的被动监控和处罚能力。
 * <p>
 * 功能：
 * 1. 被@时获取群最近消息进行分析（被动模式，节省token）
 * 2. 根据群主定义的规则判断是否违规
 * 3. 发现违规自动禁言3分钟进行警告
 * 4. 听从群主的管理安排
 * </p>
 */
public class GroupModeratorAgent {
    
    private final NapCatApi api;
    private final InternalAgents internalAgents;
    private final BotPersistenceManager persistence;
    private UnifiedQQMemoryManager memoryManager;
    
    /** 群配置映射：groupId -> GroupModerationConfig */
    private final Map<Long, GroupModerationConfig> groupConfigs = new ConcurrentHashMap<>();
    
    /** 定时任务调度器 */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5, r -> {
        Thread t = new Thread(r, "GroupModerator");
        t.setDaemon(true);
        return t;
    });
    
    /** 正在监控的群号集合 */
    private final Set<Long> monitoringGroups = ConcurrentHashMap.newKeySet();
    
    public GroupModeratorAgent(NapCatApi api, InternalAgents internalAgents, BotPersistenceManager persistence) {
        this.api = api;
        this.internalAgents = internalAgents;
        this.persistence = persistence;
    }
    
    /**
     * 设置记忆管理器引用
     */
    public void setMemoryManager(UnifiedQQMemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }
    
    // ==================== 配置管理 ====================
    
    /**
     * 为群启用自动监控（改为被动模式：仅在被@时检查）
     * @param groupId 群号
     * @return 配置对象
     */
    public GroupModerationConfig enableAutoMonitor(long groupId) {
        GroupModerationConfig config = groupConfigs.computeIfAbsent(groupId, GroupModerationConfig::new);
        config.setAutoMonitorEnabled(true);
        
        // 不再启动定时任务，改为被动模式
        monitoringGroups.add(groupId);
        
        // 记录日志
        if (persistence != null) {
            persistence.logAction("enable_monitor", 0, groupId, "启用被动监控（被@时检查）");
        }
        
        AiAgentActivity.debugLog("[GroupModerator] 已为群 " + groupId + " 启用被动监控（被@时检查违规）");
        return config;
    }
    
    /**
     * 禁用群的自动监控
     * @param groupId 群号
     */
    public void disableAutoMonitor(long groupId) {
        GroupModerationConfig config = groupConfigs.get(groupId);
        if (config != null) {
            config.setAutoMonitorEnabled(false);
            monitoringGroups.remove(groupId);
            
            // 记录日志
            if (persistence != null) {
                persistence.logAction("disable_monitor", 0, groupId, "禁用被动监控");
            }
            
            AiAgentActivity.debugLog("[GroupModerator] 已禁用群 " + groupId + " 的被动监控");
        }
    }
    
    /**
     * 被@时检查群消息（被动模式）
     * @param groupId 群号
     * @param atUserId 被@的用户ID（用于排除）
     * @return 违规处罚记录列表
     */
    public List<String> checkMessagesOnAt(long groupId, long atUserId) {
        GroupModerationConfig config = groupConfigs.get(groupId);
        if (config == null || !config.isAutoMonitorEnabled()) {
            return new ArrayList<>(); // 未启用监控
        }
        
        List<String> punishmentRecords = new ArrayList<>();
        
        try {
            // 1. 获取最近N条消息
            List<String[]> messages = getRecentGroupMessages(groupId, config.getMessagesPerCheck());
            
            if (messages == null || messages.isEmpty()) {
                AiAgentActivity.debugLog("[GroupModerator] 群 " + groupId + " 无消息可检查");
                return punishmentRecords;
            }
            
            AiAgentActivity.debugLog("[GroupModerator] 群 " + groupId + " 被@时检查 " + messages.size() + " 条消息");
            
            // 2. 分析每条消息
            for (String[] msg : messages) {
                long userId = Long.parseLong(msg[0]);
                String nickname = msg[1];
                String content = msg[2];
                
                // 跳过白名单用户和被@的用户自己
                if (config.isInWhitelist(userId) || userId == atUserId) {
                    continue;
                }
                
                // 检查是否违规
                ViolationResult result = checkViolation(userId, content, config);
                
                if (result.isViolation) {
                    // 执行处罚
                    punishViolation(groupId, userId, nickname, result.reason, config);
                    punishmentRecords.add(userId + "(" + nickname + "): " + result.reason);
                }
            }
            
        } catch (Exception e) {
            AiAgentActivity.debugLog("[GroupModerator] 检查消息异常: " + e.toString());
        }
        
        return punishmentRecords;
    }
    
    /**
     * 获取群的配置
     * @param groupId 群号
     * @return 配置对象，不存在返回null
     */
    public GroupModerationConfig getGroupConfig(long groupId) {
        return groupConfigs.get(groupId);
    }
    
    /**
     * 设置群主定义的管理规则描述
     * @param groupId 群号
     * @param description 规则描述
     */
    public void setRuleDescription(long groupId, String description) {
        GroupModerationConfig config = groupConfigs.computeIfAbsent(groupId, GroupModerationConfig::new);
        config.setRuleDescription(description);
        
        AiAgentActivity.debugLog("[GroupModerator] 已设置群 " + groupId + " 的规则描述: " + description);
    }
    
    /**
     * 添加自定义违规关键词
     * @param groupId 群号
     * @param keyword 关键词
     */
    public void addViolationKeyword(long groupId, String keyword) {
        GroupModerationConfig config = groupConfigs.computeIfAbsent(groupId, GroupModerationConfig::new);
        config.addCustomViolationKeyword(keyword);
        
        AiAgentActivity.debugLog("[GroupModerator] 已为群 " + groupId + " 添加违规关键词: " + keyword);
    }
    
    /**
     * 添加白名单用户
     * @param groupId 群号
     * @param userId QQ号
     */
    public void addToWhitelist(long groupId, long userId) {
        GroupModerationConfig config = groupConfigs.computeIfAbsent(groupId, GroupModerationConfig::new);
        config.addToWhitelist(userId);
        
        AiAgentActivity.debugLog("[GroupModerator] 已将用户 " + userId + " 加入群 " + groupId + " 的白名单");
    }
    
    // ==================== 监控任务 ====================
    
    /**
     * 启动定时监控任务
     */
    private void startMonitoringTask(long groupId, GroupModerationConfig config) {
        int interval = config.getMonitorIntervalSeconds();
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!config.isAutoMonitorEnabled()) {
                    return;
                }
                
                AiAgentActivity.debugLog("[GroupModerator] 开始监控群 " + groupId);
                checkGroupMessages(groupId, config);
                
            } catch (Exception e) {
                AiAgentActivity.debugLog("[GroupModerator] 监控任务异常: " + e.toString());
            }
        }, interval, interval, TimeUnit.SECONDS);
        
        AiAgentActivity.debugLog("[GroupModerator] 已启动群 " + groupId + " 的监控任务，间隔: " + interval + "秒");
    }
    
    /**
     * 检查群消息
     */
    private void checkGroupMessages(long groupId, GroupModerationConfig config) {
        // 1. 获取最近N条消息
        List<String[]> messages = getRecentGroupMessages(groupId, config.getMessagesPerCheck());
        
        if (messages == null || messages.isEmpty()) {
            AiAgentActivity.debugLog("[GroupModerator] 群 " + groupId + " 无新消息");
            return;
        }
        
        AiAgentActivity.debugLog("[GroupModerator] 群 " + groupId + " 获取到 " + messages.size() + " 条消息");
        
        // 2. 分析每条消息
        for (String[] msg : messages) {
            long userId = Long.parseLong(msg[0]);
            String nickname = msg[1];
            String content = msg[2];
            
            // 跳过白名单用户
            if (config.isInWhitelist(userId)) {
                continue;
            }
            
            // 检查是否违规
            ViolationResult result = checkViolation(userId, content, config);
            
            if (result.isViolation) {
                // 执行处罚
                punishViolation(groupId, userId, nickname, result.reason, config);
            }
        }
    }
    
    /**
     * 获取群最近消息（从UnifiedQQMemoryManager）
     */
    private List<String[]> getRecentGroupMessages(long groupId, int limit) {
        if (memoryManager == null) {
            AiAgentActivity.debugLog("[GroupModerator] memoryManager未初始化");
            return new ArrayList<>();
        }
        
        try {
            // 从统一记忆管理器获取群聊历史
            List<String[]> history = memoryManager.getRecentGroupChatHistory(groupId, limit);
            
            // 转换为需要的格式: [userId, nickname, content]
            List<String[]> result = new ArrayList<>();
            for (String[] record : history) {
                // record格式: [userId, nickname, content, timestamp]
                if (record.length >= 3) {
                    result.add(new String[]{record[0], record[1], record[2]});
                }
            }
            
            return result;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[GroupModerator] 获取群消息失败: " + e.toString());
            return new ArrayList<>();
        }
    }
    
    /**
     * 检查是否违规
     */
    private ViolationResult checkViolation(long userId, String content, GroupModerationConfig config) {
        if (content == null || content.isEmpty()) {
            return new ViolationResult(false, "");
        }
        
        // 1. 检查自定义违规关键词
        if (config.isViolationKeyword(content)) {
            return new ViolationResult(true, "发送违规内容");
        }
        
        // 2. 检查敏感词（如果启用）
        if (config.isSensitiveWordCheckEnabled()) {
            // TODO: 集成InternalAgents的敏感词检测
        }
        
        // 3. 检查广告（如果启用）
        if (config.isAdCheckEnabled()) {
            if (containsAdKeywords(content)) {
                return new ViolationResult(true, "发送广告");
            }
        }
        
        // 4. TODO: 可以添加更多检测规则
        // - AI辅助判断（调用DeepSeek分析语义）
        // - 图片/链接检测
        // - 频率检测
        
        return new ViolationResult(false, "");
    }
    
    /**
     * 检查是否包含广告关键词
     */
    private boolean containsAdKeywords(String content) {
        String lower = content.toLowerCase();
        return lower.contains("加群") || 
               lower.contains("扫码") || 
               lower.contains("二维码") ||
               lower.contains("微信") ||
               lower.contains("公众号");
    }
    
    /**
     * 执行违规处罚
     */
    private void punishViolation(long groupId, long userId, String nickname, String reason, GroupModerationConfig config) {
        int muteDuration = config.getViolationMuteDuration();
        
        AiAgentActivity.debugLog("[GroupModerator] 检测到违规: 群=" + groupId + ", 用户=" + userId + 
            "(" + nickname + "), 原因=" + reason);
        
        // 执行禁言
        String response = api.muteGroupMember(groupId, userId, muteDuration);
        
        if (api.isSuccess(response)) {
            // 发送警告通知
            String warningMsg = String.format(
                "⚠️ 违规警告\n" +
                "用户: %s (%d)\n" +
                "原因: %s\n" +
                "处罚: 禁言%d分钟\n\n" +
                "请遵守群规，如有异议请联系群主。",
                nickname, userId, reason, muteDuration / 60
            );
            
            api.sendGroupMessage(groupId, warningMsg);
            
            // 记录日志
            if (persistence != null) {
                persistence.logAction("auto_mute", userId, groupId, 
                    "原因: " + reason + ", 禁言: " + muteDuration + "秒");
            }
            
        AiAgentActivity.debugLog("[GroupModerator] 已禁言用户 " + userId + " " + (muteDuration/60) + "分钟");
        } else {
            AiAgentActivity.debugLog("[GroupModerator] 禁言失败: " + response);
        }
    }
    
    // ==================== 工具类 ====================
    
    /**
     * 违规检测结果
     */
    private static class ViolationResult {
        final boolean isViolation;
        final String reason;
        
        ViolationResult(boolean isViolation, String reason) {
            this.isViolation = isViolation;
            this.reason = reason;
        }
    }
    
    /**
     * 关闭调度器
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        AiAgentActivity.debugLog("[GroupModerator] 调度器已关闭");
    }
}
