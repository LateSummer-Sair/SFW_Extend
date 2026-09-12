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
        loadSensitiveWords();
    }

    // ==================== RuleAgent: 敏感词库 ====================

    /**
     * 内置默认敏感词（<b>只收明确无歧义的辱骂/色情词</b>）。
     * <p>
     * 之所以必须有默认值：{@code sensitiveWords} 此前<b>从来没有被任何代码填充过</b>
     * （没有加载器、没有配置项、{@code addSensitiveWord} 也没有调用方），
     * 于是 {@code containsSensitiveWords} 恒为 false —— 整个敏感词/辱骂/开黄腔
     * 检测、警告、拉黑、情绪惩罚链路<b>全部是死代码</b>，Bot 挨骂既不生气也不处理。
     * </p>
     * <p>
     * 词表刻意保守：像「傻瓜」「滚」这类在熟人之间也会用的词一律不收，避免误伤正常聊天。
     * 主人可通过数据目录下的 {@code sensitive_words.txt}（每行一个词，# 开头为注释）
     * <b>整体替换</b>默认词表，或用 addsensitiveword 逐步追加（追加会被持久化）。
     * </p>
     */
    private static final String[] DEFAULT_SENSITIVE_WORDS = {
        // 辱骂
        "傻逼", "煞笔", "沙比", "傻B", "脑残", "智障", "弱智", "白痴", "废物点心",
        "去死", "死全家", "死妈", "你妈死", "尼玛", "草泥马", "操你", "草你", "日你", "干你妈",
        "婊子", "贱人", "杂种", "狗东西", "畜生", "王八蛋", "滚出去", "神经病吧",
        // 色情
        "操逼", "做爱", "约炮", "一夜情", "裸聊", "发骚", "淫荡", "色情", "黄图",
        "打飞机", "自慰", "口交", "性交", "卖淫", "嫖娼", "舔我", "硬了想"
    };

    /** 敏感词持久化键（app_state）。 */
    private static final String SENSITIVE_WORDS_STATE_KEY = "ruleagent.sensitive_words";

    /**
     * 加载敏感词：默认词表 → 数据目录 sensitive_words.txt 覆盖 → 持久化追加词。
     */
    private void loadSensitiveWords() {
        for (String w : DEFAULT_SENSITIVE_WORDS) sensitiveWords.add(w.toLowerCase());
        // 1) 数据目录下的可编辑词表（存在则整体替换默认词表）
        try {
            String dataDir = (handler != null) ? handler.getDataDirPath() : null;
            if (dataDir != null && !dataDir.isEmpty()) {
                java.io.File f = new java.io.File(dataDir, "sensitive_words.txt");
                if (f.isFile()) {
                    List<String> lines = java.nio.file.Files.readAllLines(f.toPath(),
                            java.nio.charset.StandardCharsets.UTF_8);
                    Set<String> custom = new LinkedHashSet<>();
                    for (String line : lines) {
                        String w = line.trim();
                        if (w.isEmpty() || w.startsWith("#")) continue;
                        custom.add(w.toLowerCase());
                    }
                    if (!custom.isEmpty()) {
                        sensitiveWords.clear();
                        sensitiveWords.addAll(custom);
                        AiAgentActivity.debugLog("[RuleAgent] 已用 sensitive_words.txt 覆盖敏感词表: "
                                + custom.size() + " 个");
                    }
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[RuleAgent] 读取 sensitive_words.txt 失败（保留默认词表）: " + e);
        }
        // 2) 持久化追加词（addSensitiveWord 写入过的话）
        try {
            sair.aiagent.core.PersistenceManager pm = sair.aiagent.core.PersistenceManager.getInstance();
            String stored = (pm != null) ? pm.getState(SENSITIVE_WORDS_STATE_KEY) : null;
            if (stored != null && !stored.isEmpty()) {
                int n = 0;
                for (String w : stored.split("\n")) {
                    String t = w.trim();
                    if (!t.isEmpty() && sensitiveWords.add(t.toLowerCase())) n++;
                }
                if (n > 0) AiAgentActivity.debugLog("[RuleAgent] 已恢复持久化敏感词 " + n + " 个");
            }
        } catch (Exception ignored) {}
        AiAgentActivity.debugLog("[RuleAgent] 敏感词库已加载: " + sensitiveWords.size() + " 个");
    }

    /** 把当前「非默认词」持久化，保证 addSensitiveWord 的效果能跨重启保留。 */
    private void persistSensitiveWords() {
        try {
            StringBuilder sb = new StringBuilder();
            for (String w : sensitiveWords) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(w);
            }
            sair.aiagent.core.PersistenceManager pm = sair.aiagent.core.PersistenceManager.getInstance();
            if (pm != null) pm.setState(SENSITIVE_WORDS_STATE_KEY, sb.toString());
        } catch (Exception ignored) {}
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
        int count;
        if (persistence != null) {
            count = persistence.addWarning(userId, groupId, reason);
            // sync local cache with persistence
            warningCounts.put(userId, count);
            lastWarningTime.put(userId, System.currentTimeMillis());
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
     * 检查是否为色情内容。
     * <p>旧实现只要正文出现「色/黄/裸/性/骚」任意一个字就判为开黄腔 —— 而「性格」「属性」
     * 「个性」「感性」「黄色」（颜色）全都命中，于是一句普通辱骂经常被升级成开黄腔处理
     * （怒气 +25 而非 +15、好感 −30 而非 −20、控制台失败信号 ×2）。这里改为具体词组匹配。</p>
     */
    private boolean isSexualContent(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        for (String w : SEXUAL_WORDS) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    /** 明确的色情词（用于把「辱骂」与「开黄腔」分流，见 {@link #isSexualContent}）。 */
    private static final String[] SEXUAL_WORDS = {
        "操逼", "做爱", "约炮", "一夜情", "裸聊", "发骚", "淫荡", "色情", "黄图",
        "打飞机", "自慰", "口交", "性交", "卖淫", "嫖娼", "脱衣", "舔我", "开黄腔"
    };
    
    /** 供群管 Agent 复用的敏感词检测入口。 */
    public boolean hasSensitiveWord(String content) {
        return containsSensitiveWords(content);
    }

    /**
     * 添加敏感词（持久化，重启后仍生效）
     */
    public void addSensitiveWord(String word) {
        if (word == null || word.trim().isEmpty()) return;
        sensitiveWords.add(word.trim().toLowerCase());
        persistSensitiveWords();
        AiAgentActivity.debugLog("[RuleAgent] 已添加敏感词: " + word);
    }
    
    /**
     * 移除敏感词（持久化）
     */
    public void removeSensitiveWord(String word) {
        if (word == null || word.trim().isEmpty()) return;
        sensitiveWords.remove(word.trim().toLowerCase());
        persistSensitiveWords();
        AiAgentActivity.debugLog("[RuleAgent] 已移除敏感词: " + word);
    }

    /** 当前敏感词数量（供 ai/status 等观测）。 */
    public int getSensitiveWordCount() {
        return sensitiveWords.size();
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
    
    /** 自定义规则存储（规则名 → 触发动作）。 */
    private final Map<String, Runnable> customRules = new ConcurrentHashMap<>();

    /**
     * 添加自定义规则（由主人定义）
     * @param ruleName 规则名称
     * @param ruleDescription 规则描述
     * @param action 触发动作
     */
    public void addCustomRule(String ruleName, String ruleDescription, Runnable action) {
        if (ruleName == null || ruleName.trim().isEmpty() || action == null) return;
        customRules.put(ruleName.trim(), action);
        AiAgentActivity.debugLog("[RuleAgent] 添加自定义规则: " + ruleName + " | 描述: " + ruleDescription);
    }

    /** 按名称执行自定义规则；不存在返回 false。 */
    public boolean runCustomRule(String ruleName) {
        Runnable action = ruleName == null ? null : customRules.get(ruleName.trim());
        if (action == null) return false;
        try {
            action.run();
            return true;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[RuleAgent] 自定义规则执行失败: " + ruleName + " -> " + e.toString());
            return false;
        }
    }

    /** 按消息内容匹配自定义规则名（规则名作为触发关键词），命中则执行。 */
    public boolean runCustomRulesMatching(String content) {
        if (content == null || content.trim().isEmpty() || customRules.isEmpty()) return false;
        String c = content.toLowerCase();
        boolean ran = false;
        for (Map.Entry<String, Runnable> e : customRules.entrySet()) {
            if (e.getKey() != null && c.contains(e.getKey().toLowerCase())) {
                try { e.getValue().run(); ran = true; }
                catch (Exception ex) { AiAgentActivity.debugLog("[RuleAgent] 自定义规则执行失败: " + e.getKey()); }
            }
        }
        return ran;
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
