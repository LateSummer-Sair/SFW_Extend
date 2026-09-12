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
    
    // ==================== 持久化 ====================
    //
    // 旧实现所有配置只存在内存里（groupConfigs 是 ConcurrentHashMap）：
    // 插件重启后 autoMonitorEnabled 回到默认 false、监控间隔/禁言时长、
    // 自定义违规关键词、白名单用户、群规描述<b>全部丢失</b>。
    // 这里提供紧凑的序列化格式，由 GroupModeratorAgent 落到 app_state 表。

    private static final String FIELD_SEP = "\u0001";
    private static final String ITEM_SEP = "\u0002";

    /** 序列化为一行文本（最后一段是规则描述，可以包含任意分隔符之外的内容）。 */
    public String toStorageString() {
        StringBuilder kw = new StringBuilder();
        for (String k : customViolationKeywords) {
            if (kw.length() > 0) kw.append(ITEM_SEP);
            kw.append(k);
        }
        StringBuilder wl = new StringBuilder();
        for (Long u : whitelistUsers) {
            if (wl.length() > 0) wl.append(ITEM_SEP);
            wl.append(u);
        }
        return (autoMonitorEnabled ? "1" : "0") + FIELD_SEP
                + monitorIntervalSeconds + FIELD_SEP
                + messagesPerCheck + FIELD_SEP
                + violationMuteDuration + FIELD_SEP
                + (sensitiveWordCheckEnabled ? "1" : "0") + FIELD_SEP
                + (spamCheckEnabled ? "1" : "0") + FIELD_SEP
                + (adCheckEnabled ? "1" : "0") + FIELD_SEP
                + kw + FIELD_SEP
                + wl + FIELD_SEP
                + (ruleDescription == null ? "" : ruleDescription);
    }

    /** 从 {@link #toStorageString()} 的输出恢复配置；解析失败时保留默认值。 */
    public static GroupModerationConfig fromStorageString(long groupId, String s) {
        GroupModerationConfig cfg = new GroupModerationConfig(groupId);
        if (s == null || s.isEmpty()) return cfg;
        try {
            String[] parts = s.split(FIELD_SEP, -1);
            if (parts.length >= 9) {
                cfg.autoMonitorEnabled = "1".equals(parts[0]);
                cfg.monitorIntervalSeconds = Integer.parseInt(parts[1]);
                cfg.messagesPerCheck = Integer.parseInt(parts[2]);
                cfg.violationMuteDuration = Integer.parseInt(parts[3]);
                cfg.sensitiveWordCheckEnabled = "1".equals(parts[4]);
                cfg.spamCheckEnabled = "1".equals(parts[5]);
                cfg.adCheckEnabled = "1".equals(parts[6]);
                if (!parts[7].isEmpty()) {
                    for (String k : parts[7].split(ITEM_SEP)) {
                        if (!k.isEmpty()) cfg.customViolationKeywords.add(k);
                    }
                }
                if (!parts[8].isEmpty()) {
                    for (String u : parts[8].split(ITEM_SEP)) {
                        if (!u.isEmpty()) {
                            try { cfg.whitelistUsers.add(Long.parseLong(u.trim())); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                if (parts.length >= 10) cfg.ruleDescription = parts[9];
            }
        } catch (Exception ignored) {
            // 数据损坏时退回默认配置，不让启动失败
        }
        return cfg;
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
