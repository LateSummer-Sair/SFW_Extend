package sair.aiagent.onebot;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 群管理规则配置。
 * <p>
 * 每个群可以有独立的管理规则，由群主定义。
 * Bot作为管理员时，会根据这些规则自动监控和处罚违规行为。
 * </p>
 */
public class GroupModerationConfig {
    
    /** 群号 */
    private final long groupId;
    
    /** 是否启用自动监控 */
    private volatile boolean autoMonitorEnabled = false;
    
    /** 监控间隔（秒），默认180秒（3分钟） */
    private volatile int monitorIntervalSeconds = 180;
    
    /** 每次获取的消息数量，默认30条 */
    private volatile int messagesPerCheck = 30;
    
    /** 违规禁言时长（秒），默认180秒（3分钟） */
    private volatile int violationMuteDuration = 180;
    
    /** 是否启用敏感词检测 */
    private volatile boolean sensitiveWordCheckEnabled = true;
    
    /** 是否启用刷屏检测 */
    private volatile boolean spamCheckEnabled = true;
    
    /** 是否启用广告检测 */
    private volatile boolean adCheckEnabled = true;
    
    /** 自定义违规关键词列表 */
    private final Set<String> customViolationKeywords = ConcurrentHashMap.newKeySet();
    
    /** 白名单用户（不会被处罚） */
    private final Set<Long> whitelistUsers = ConcurrentHashMap.newKeySet();
    
    /** 规则描述（由群主定义） */
    private String ruleDescription = "";
    
    public GroupModerationConfig(long groupId) {
        this.groupId = groupId;
    }
    
    // ==================== Getters & Setters ====================
    
    public long getGroupId() {
        return groupId;
    }
    
    public boolean isAutoMonitorEnabled() {
        return autoMonitorEnabled;
    }
    
    public void setAutoMonitorEnabled(boolean enabled) {
        this.autoMonitorEnabled = enabled;
    }
    
    public int getMonitorIntervalSeconds() {
        return monitorIntervalSeconds;
    }
    
    public void setMonitorIntervalSeconds(int seconds) {
        this.monitorIntervalSeconds = seconds;
    }
    
    public int getMessagesPerCheck() {
        return messagesPerCheck;
    }
    
    public void setMessagesPerCheck(int count) {
        this.messagesPerCheck = count;
    }
    
    public int getViolationMuteDuration() {
        return violationMuteDuration;
    }
    
    public void setViolationMuteDuration(int seconds) {
        this.violationMuteDuration = seconds;
    }
    
    public boolean isSensitiveWordCheckEnabled() {
        return sensitiveWordCheckEnabled;
    }
    
    public void setSensitiveWordCheckEnabled(boolean enabled) {
        this.sensitiveWordCheckEnabled = enabled;
    }
    
    public boolean isSpamCheckEnabled() {
        return spamCheckEnabled;
    }
    
    public void setSpamCheckEnabled(boolean enabled) {
        this.spamCheckEnabled = enabled;
    }
    
    public boolean isAdCheckEnabled() {
        return adCheckEnabled;
    }
    
    public void setAdCheckEnabled(boolean enabled) {
        this.adCheckEnabled = enabled;
    }
    
    public Set<String> getCustomViolationKeywords() {
        return customViolationKeywords;
    }
    
    public void addCustomViolationKeyword(String keyword) {
        customViolationKeywords.add(keyword.toLowerCase());
    }
    
    public void removeCustomViolationKeyword(String keyword) {
        customViolationKeywords.remove(keyword.toLowerCase());
    }
    
    public Set<Long> getWhitelistUsers() {
        return whitelistUsers;
    }
    
    public void addToWhitelist(long userId) {
        whitelistUsers.add(userId);
    }
    
    public void removeFromWhitelist(long userId) {
        whitelistUsers.remove(userId);
    }
    
    public boolean isInWhitelist(long userId) {
        return whitelistUsers.contains(userId);
    }
    
    public String getRuleDescription() {
        return ruleDescription;
    }
    
    public void setRuleDescription(String description) {
        this.ruleDescription = description;
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 检查是否为违规关键词
     */
    public boolean isViolationKeyword(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        String lowerText = text.toLowerCase();
        
        // 检查自定义违规关键词
        for (String keyword : customViolationKeywords) {
            if (lowerText.contains(keyword)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * 生成配置摘要
     */
    public String getConfigSummary() {
        return String.format(
            "群 %d 管理配置:\n" +
            "- 自动监控: %s\n" +
            "- 监控间隔: %d秒\n" +
            "- 每次检查: %d条消息\n" +
            "- 违规禁言: %d秒\n" +
            "- 敏感词检测: %s\n" +
            "- 刷屏检测: %s\n" +
            "- 广告检测: %s\n" +
            "- 自定义关键词: %d个\n" +
            "- 白名单用户: %d个",
            groupId,
            autoMonitorEnabled ? "启用" : "禁用",
            monitorIntervalSeconds,
            messagesPerCheck,
            violationMuteDuration,
            sensitiveWordCheckEnabled ? "启用" : "禁用",
            spamCheckEnabled ? "启用" : "禁用",
            adCheckEnabled ? "启用" : "禁用",
            customViolationKeywords.size(),
            whitelistUsers.size()
        );
    }
}
