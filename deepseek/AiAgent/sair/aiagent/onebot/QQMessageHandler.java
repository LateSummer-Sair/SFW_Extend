package sair.aiagent.onebot;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.core.AgentExecutor;
import sair.aiagent.onebot.model.QQMessage;
import sair.aiagent.onebot.util.JsonUtil;

/**
 * QQ消息处理器 —— 消息解析、过滤、路由到AI Agent。
 *
 * <h3>处理规则</h3>
 * <ul>
 *   <li><b>群消息</b>：仅响应@机器人 或 提到名字，其余忽略</li>
 *   <li><b>私聊消息</b>：全部响应</li>
 *   <li>execq 通道只开放：cmd / web / readdir / setname / stop 五个标签</li>
 *   <li>默认允许无需确认（自动绕过确认门控）</li>
 *   <li>按QQ号隔离记忆存储</li>
 * </ul>
 */
public class QQMessageHandler {

    /** 机器人自身QQ号 */
    private volatile long selfId;

    /** OneBotServer引用，用于发送回复 */
    private OneBotServer server;

    /** AgentExecutor引用 */
    private AgentExecutor agentExecutor;

    /** 数据根目录 */
    private String dataDir;

    /** 统一记忆管理器（所有QQ和群聊共享） */
    private UnifiedQQMemoryManager unifiedMemory;

    /** execq专用的线程池 */
    private final ExecutorService execPool = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "OneBot-ExecQ");
        t.setDaemon(true);
        return t;
    });

    /** 正在处理中的消息ID集合（防重复） */
    private final Set<Long> processingMessages = Collections.synchronizedSet(new HashSet<>());

    /** 定时任务线程池（用于主动查看群聊） */
    private final ExecutorService scheduledPool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OneBot-Scheduled");
        t.setDaemon(true);
        return t;
    });

    /** 是否启用主动查看功能 */
    private volatile boolean proactiveCheckEnabled = false;

    /** 当前监听的群号列表 */
    private final Set<Long> monitoredGroups = Collections.synchronizedSet(new HashSet<>());

    /** NapCat API封装 */
    private NapCatApi napcatApi;

    /** 内部Agent系统 */
    private InternalAgents internalAgents;
    
    /** Bot持久化管理器 */
    private BotPersistenceManager persistenceManager;
    
    /** 群管理员Agent */
    private GroupModeratorAgent groupModerator;
    
    /** 情绪状态管理器 */
    private EmotionStateManager emotionManager;

    /** 好友通过时间戳（用于反滥用：5分钟内发起群邀请则拒+警告） */
    private final Map<Long, Long> friendAcceptTimestamps = new ConcurrentHashMap<>();

    // ==================== 配置 ====================

    public void setSelfId(long selfId) { this.selfId = selfId; }
    public long getSelfId() { return selfId; }

    public void setServer(OneBotServer server) { 
        this.server = server;
        // 初始化所有组件
        if (server != null && napcatApi == null && dataDir != null) {
            // 初始化持久化管理器
            persistenceManager = new BotPersistenceManager(dataDir);
            persistenceManager.init();
            
            // 初始化API封装
            napcatApi = new NapCatApi(server);
            
            // 初始化情绪状态管理器（传入持久化管理器）
            emotionManager = new EmotionStateManager(persistenceManager);
            
            // 初始化内部Agent（传入持久化管理器）
            internalAgents = new InternalAgents(napcatApi, this, persistenceManager);
            
            // 初始化群管理员Agent
            groupModerator = new GroupModeratorAgent(napcatApi, internalAgents, persistenceManager);
            
            // 设置GroupModerator的记忆管理器引用
            if (unifiedMemory != null) {
                groupModerator.setMemoryManager(unifiedMemory);
            }
            
            AiAgentActivity.debugLog("[QQMsg] 所有组件初始化完成: EmotionStateManager, NapCatApi, BotPersistenceManager, InternalAgents, GroupModeratorAgent");
            bridgeEmotionManagers();
        }
    }
    
    /** 获取NapCatApi实例 */
    public NapCatApi getNapcatApi() { return napcatApi; }
    
    /** 获取InternalAgents实例 */
    public InternalAgents getInternalAgents() { return internalAgents; }
    
    /** 获取GroupModeratorAgent实例 */
    public GroupModeratorAgent getGroupModerator() { return groupModerator; }
    
    /** 获取EmotionStateManager实例 */
    public EmotionStateManager getEmotionManager() { return emotionManager; }
    public void setAgentExecutor(AgentExecutor executor) { this.agentExecutor = executor; }

    /** Bridge core EmotionManager to QQ EmotionStateManager */
    public void bridgeEmotionManagers() {
        if (agentExecutor != null && emotionManager != null) {
            sair.aiagent.core.EmotionManager coreEm = agentExecutor.getEmotionManager();
            if (coreEm != null) {
                emotionManager.setCoreEmotionManager(coreEm);
                sair.aiagent.AiAgentActivity.debugLog("[QQMsg] Emotion bridge established");
            }
        }
    }
    public void setDataDir(String dir) { 
        this.dataDir = dir;
        AiAgentActivity.debugLog("[QQMsg] setDataDir被调用: dataDir=" + dir);
        // 初始化统一记忆管理器
        if (unifiedMemory == null && dir != null) {
            AiAgentActivity.debugLog("[QQMsg] 正在初始化UnifiedQQMemoryManager...");
            unifiedMemory = new UnifiedQQMemoryManager(dir);
            unifiedMemory.init();
            AiAgentActivity.debugLog("[QQMsg] UnifiedQQMemoryManager初始化完成");
        } else if (dir == null) {
            AiAgentActivity.debugLog("[QQMsg] 警告: setDataDir传入null，无法初始化unifiedMemory");
        }
    }

    // ==================== 主动查看功能 ====================

    /** 启用主动查看功能，每5分钟检查一次群聊 */
    public void enableProactiveCheck() {
        if (proactiveCheckEnabled) return;
        proactiveCheckEnabled = true;
        
        // 启动定时任务
        scheduledPool.submit(() -> {
            while (proactiveCheckEnabled && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(5 * 60 * 1000); // 5分钟
                    if (proactiveCheckEnabled) {
                        performProactiveCheck();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[QQMsg] 主动查看错误: " + e.toString());
                }
            }
        });
        
        AiAgentActivity.debugLog("[QQMsg] 主动查看功能已启用");
    }

    /** 禁用主动查看功能 */
    public void disableProactiveCheck() {
        proactiveCheckEnabled = false;
        AiAgentActivity.debugLog("[QQMsg] 主动查看功能已禁用");
    }

    /** 添加要监听的群号 */
    public void addMonitoredGroup(long groupId) {
        monitoredGroups.add(groupId);
        AiAgentActivity.debugLog("[QQMsg] 添加监听群: " + groupId);
    }

    /** 移除监听的群号 */
    public void removeMonitoredGroup(long groupId) {
        monitoredGroups.remove(groupId);
        AiAgentActivity.debugLog("[QQMsg] 移除监听群: " + groupId);
    }

    /** 执行主动查看 */
    private void performProactiveCheck() {
        if (monitoredGroups.isEmpty()) {
            AiAgentActivity.debugLog("[QQMsg] 没有监听的群，跳过主动查看");
            return;
        }

        for (long groupId : monitoredGroups) {
            try {
                // 获取该群的最近群聊消息
                List<String[]> recentMessages = unifiedMemory.getRecentGroupChatHistory(groupId, 20);
                if (recentMessages.isEmpty()) {
                    AiAgentActivity.debugLog("[QQMsg] 群 " + groupId + " 没有新消息");
                    continue;
                }

                // 构建分析提示词（包含群上下文）
                StringBuilder analysisPrompt = new StringBuilder();
                analysisPrompt.append("你在QQ群聊中，请分析以下消息，有趣的话题可参与讨论。\n\n");
                
                // 群名
                String gname = unifiedMemory.getGroupName(groupId);
                analysisPrompt.append("## 当前群: ").append(gname != null ? gname + "(" + groupId + ")" : String.valueOf(groupId)).append("\n");
                
                // Bot名字
                String botName = sair.aiagent.core.AiConfig.getInstance().getBotName();
                if (botName != null && !botName.isEmpty()) {
                    analysisPrompt.append("你的名字: ").append(botName).append("\n");
                }
                
                // 群管理员/群主
                java.util.List<String[]> admins = unifiedMemory.getGroupAdmins(groupId);
                if (!admins.isEmpty()) {
                    StringBuilder ol = new StringBuilder();
                    StringBuilder al = new StringBuilder();
                    for (String[] a : admins) {
                        if ("owner".equals(a[2])) {
                            if (ol.length() > 0) ol.append(", ");
                            ol.append(a[1]).append("(QQ:").append(a[0]).append(")");
                        } else {
                            if (al.length() > 0) al.append(", ");
                            al.append(a[1]).append("(QQ:").append(a[0]).append(")");
                        }
                    }
                    if (ol.length() > 0) analysisPrompt.append("群主: ").append(ol).append("\n");
                    if (al.length() > 0) analysisPrompt.append("管理员: ").append(al).append("\n");
                }
                
                // 群昵称映射
                java.util.Map<String, Long> nickMap = unifiedMemory.getGroupNicknameMap(groupId);
                if (!nickMap.isEmpty()) {
                    analysisPrompt.append("群昵称→QQ: ");
                    int nc = 0;
                    for (java.util.Map.Entry<String, Long> e : nickMap.entrySet()) {
                        if (nc >= 12) break;
                        analysisPrompt.append(e.getKey()).append("→").append(e.getValue()).append(" ");
                        nc++;
                    }
                    analysisPrompt.append("\n");
                }
                analysisPrompt.append("\n");
                
                analysisPrompt.append("## 最近群聊消息\n");
                
                for (String[] msg : recentMessages) {
                    String userId = msg[0];
                    String nickname = msg[1];
                    String content = msg[2];
                    analysisPrompt.append(nickname).append(": ").append(content).append("\n");
                }
                
                analysisPrompt.append("\n## 要求\n");
                analysisPrompt.append("- 如果有有趣的话题或问题，可以参与讨论\n");
                analysisPrompt.append("- 如果没有特别需要回应的内容，返回空字符串\n");
                analysisPrompt.append("- 保持自然、友好的语气\n");
                analysisPrompt.append("- 不要@任何人，自然地加入对话\n");
                analysisPrompt.append("- 可以分多条消息发送，每条不要太长\n");
                
                // 提交到线程池执行
                final long finalGroupId = groupId;
                execPool.submit(() -> {
                    try {
                        String response = agentExecutor.executeQq(
                            "分析群聊并生成回复",
                            analysisPrompt.toString(),
                            unifiedMemory  // 传入统一记忆管理器
                        );
                        
                        if (response != null && !response.trim().isEmpty()) {
                            String cleanResponse = response.replaceAll("<[^>]+>", "").trim();
                            if (!cleanResponse.isEmpty()) {
                                // 支持多消息发送：按换行符分割成多条消息
                                List<String> messages = splitIntoMessages(cleanResponse);
                                if (server != null) {
                                    for (String message : messages) {
                                        if (!message.trim().isEmpty()) {
                                            server.sendGroupMsg(finalGroupId, message.trim());
                                            // 消息之间稍微延迟
                                            try {
                                                Thread.sleep(300);
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                break;
                                            }
                                        }
                                    }
                                    AiAgentActivity.debugLog("[QQMsg] 主动发送" + messages.size() + "条消息到群 " + finalGroupId);
                                }
                            }
                        }
                    } catch (Exception e) {
                        AiAgentActivity.debugLog("[QQMsg] 主动查看执行错误: " + e.toString());
                    }
                });
                
            } catch (Exception e) {
                AiAgentActivity.debugLog("[QQMsg] 主动查看群 " + groupId + " 失败: " + e.toString());
            }
        }
    }

    // ==================== 消息入口 ====================

    /**
     * 处理来自OneBotServer的原始JSON消息。
     * @param rawJson 原始JSON字符串
     * @param responseSender 发送回复的回调（直接通过WebSocket发送）
     */
    public void handleRawMessage(String rawJson, Consumer<String> responseSender) {
        QQMessage msg = parseMessage(rawJson);
        if (msg == null) {
            // 只在debug模式下显示非消息事件
            // AiAgentActivity.debugLog("[QQMsg] 忽略非消息事件");
            return;
        }
        AiAgentActivity.debugLog("[QQMsg] 收到消息: type=" + msg.getMessageType() + ", userId=" + msg.getUserId());
        handleMessage(msg, responseSender);
    }

    // ==================== 消息解析 ====================

    /**
     * 手动解析OneBot v11消息JSON（无第三方JSON库依赖）。
     */
    QQMessage parseMessage(String rawJson) {
        if (rawJson == null || rawJson.trim().isEmpty()) return null;

        try {
            // 检查post_type
            String postType = extractString(rawJson, "post_type");
            
            if (!"message".equals(postType)) {
                // 静默忽略非消息事件（心跳、通知等）
                return null;
            }

            // 检查message_type
            String messageType = extractString(rawJson, "message_type");
            
            if (!"private".equals(messageType) && !"group".equals(messageType)) {
                return null;
            }

            QQMessage msg = new QQMessage();
            msg.setPostType(postType);
            msg.setMessageType(messageType);
            msg.setSubType(extractString(rawJson, "sub_type"));
            msg.setMessageId(extractLong(rawJson, "message_id"));
            msg.setUserId(extractLong(rawJson, "user_id"));
            msg.setGroupId(extractLong(rawJson, "group_id"));
            msg.setRawMessage(extractString(rawJson, "raw_message"));

            // 解析 sender
            String senderJson = extractObject(rawJson, "sender");
            if (senderJson != null) {
                QQMessage.Sender sender = new QQMessage.Sender();
                sender.userId = extractLong(senderJson, "user_id");
                sender.nickname = extractString(senderJson, "nickname");
                sender.card = extractString(senderJson, "card");
                sender.sex = extractString(senderJson, "sex");
                sender.age = (int) extractLong(senderJson, "age");
                sender.role = extractString(senderJson, "role");
                sender.title = extractString(senderJson, "title");
                msg.setSender(sender);
            }

            // 解析消息段（数组格式）
            String messageArray = extractArray(rawJson, "message");
            if (messageArray != null) {
                msg.setSegments(parseSegments(messageArray));
            }

            // 检测是否@了机器人、是否引用回复
            checkAtAndReply(msg);
            
            // 检测图片和折叠消息
            detectMediaContent(msg);

            return msg;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] 解析错误: " + e.getMessage());
            return null;
        }
    }

    /** 解析消息段数组 */
    private List<QQMessage.MessageSegment> parseSegments(String arrayJson) {
        List<QQMessage.MessageSegment> segments = new ArrayList<>();
        List<String> items = splitJsonArray(arrayJson);
        for (String item : items) {
            QQMessage.MessageSegment seg = new QQMessage.MessageSegment();
            seg.type = extractString(item, "type");
            if ("text".equals(seg.type)) {
                String dataObj = extractObject(item, "data");
                if (dataObj != null) {
                    seg.text = extractString(dataObj, "text");
                }
            } else if ("at".equals(seg.type)) {
                String dataObj = extractObject(item, "data");
                if (dataObj != null) {
                    String qqStr = extractString(dataObj, "qq");
                    if ("all".equals(qqStr)) {
                        seg.qq = 0;
                    } else {
                        try { seg.qq = Long.parseLong(qqStr); } catch (NumberFormatException ignored) {}
                    }
                }
            } else if ("reply".equals(seg.type)) {
                String dataObj = extractObject(item, "data");
                if (dataObj != null) {
                    seg.id = extractLong(dataObj, "id");
                }
            } else if ("image".equals(seg.type)) {
                String dataObj = extractObject(item, "data");
                if (dataObj != null) {
                    seg.url = extractString(dataObj, "url");
                    seg.file = extractString(dataObj, "file");
                    seg.subType = extractString(dataObj, "sub_type");
                    seg.fileId = extractString(dataObj, "file_id");
                }
            } else if ("forward".equals(seg.type)) {
                String dataObj = extractObject(item, "data");
                if (dataObj != null) {
                    // 尝试提取content（部分实现如NapCat直接内嵌内容）
                    seg.content = extractString(dataObj, "content");
                    // 提取forward_id（标准OneBot v11）
                    String fwdId = extractString(dataObj, "id");
                    if (fwdId != null && !fwdId.isEmpty()) {
                        seg.forwardId = fwdId;
                    }
                }
            } else if ("record".equals(seg.type)) {
                String dataObj = extractObject(item, "data");
                if (dataObj != null) {
                    seg.url = extractString(dataObj, "url");
                    seg.file = extractString(dataObj, "file");
                }
            } else if ("file".equals(seg.type)) {
                String dataObj = extractObject(item, "data");
                if (dataObj != null) {
                    seg.file = extractString(dataObj, "file");
                    seg.fileName = extractString(dataObj, "name");
                    seg.url = extractString(dataObj, "url");
                }
            }
            segments.add(seg);
        }
        return segments;
    }

    /** 检测是否@了机器人、是否引用回复、收集所有@提及 */
    private void checkAtAndReply(QQMessage msg) {
        AiAgentActivity.debugLog("[QQMsg] @检测: selfId=" + selfId + ", segments=" + (msg.getSegments() != null ? msg.getSegments().size() : "null"));
        
        if (msg.getSegments() == null) {
            // 降级：从raw_message检查
            String raw = msg.getRawMessage();
            if (raw != null) {
                AiAgentActivity.debugLog("[QQMsg] 使用raw_message检测@: " + raw.substring(0, Math.min(100, raw.length())));
                if (selfId > 0 && raw.contains("[CQ:at,qq=" + selfId)) {
                    msg.setAtBot(true);
                    AiAgentActivity.debugLog("[QQMsg] 检测到@机器人 (raw): " + selfId);
                }
                if (raw.contains("[CQ:at,qq=all")) {
                    msg.setAtBot(true);
                    AiAgentActivity.debugLog("[QQMsg] 检测到@all");
                }
                if (raw.startsWith("[CQ:reply")) {
                    msg.setReply(true);
                    AiAgentActivity.debugLog("[QQMsg] 检测到回复消息");
                }
                // raw_message降级模式也收集@提及的其他用户
                int idx = 0;
                while ((idx = raw.indexOf("[CQ:at,qq=", idx)) >= 0) {
                    int start = idx + 10;
                    int end = raw.indexOf("]", start);
                    if (end > start) {
                        String qqStr = raw.substring(start, end);
                        // 可能包含,name=xxx
                        int commaIdx = qqStr.indexOf(',');
                        if (commaIdx > 0) qqStr = qqStr.substring(0, commaIdx);
                        try {
                            long qq = Long.parseLong(qqStr.trim());
                            if (qq > 0 && qq != selfId) {
                                msg.addMentionedUser(qq, raw.substring(idx, end + 1));
                                AiAgentActivity.debugLog("[QQMsg] 收集到@提及: qq=" + qq);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    idx = end > 0 ? end : idx + 1;
                }
            }
            return;
        }

        for (QQMessage.MessageSegment seg : msg.getSegments()) {
            if ("at".equals(seg.type)) {
                AiAgentActivity.debugLog("[QQMsg] 发现@段: qq=" + seg.qq + ", selfId=" + selfId);
                // @all 或 @机器人（需先设置selfId）
                if (seg.qq == 0) {
                    msg.setAtBot(true); // @all
                    AiAgentActivity.debugLog("[QQMsg] 检测到@all");
                } else if (selfId > 0 && seg.qq == selfId) {
                    msg.setAtBot(true);
                    AiAgentActivity.debugLog("[QQMsg] 检测到@机器人: " + selfId);
                } else if (seg.qq > 0 && seg.qq != selfId) {
                    // 收集@提及的其他用户
                    msg.addMentionedUser(seg.qq, "[CQ:at,qq=" + seg.qq + "]");
                    AiAgentActivity.debugLog("[QQMsg] 收集到@提及: qq=" + seg.qq);
                } else if (selfId == 0) {
                    AiAgentActivity.debugLog("[QQMsg] 警告: selfId未设置，无法检测@机器人！请执行 ai/onebotsetselfid <QQ号>");
                }
            }
            if ("reply".equals(seg.type)) {
                msg.setReply(true);
                msg.setReplyMessageId(seg.id);
                AiAgentActivity.debugLog("[QQMsg] 检测到回复消息");
            }
        }
        
        AiAgentActivity.debugLog("[QQMsg] @检测结果: isAtBot=" + msg.isAtBot() + ", isReply=" + msg.isReply() + ", mentionedUsers=" + msg.getMentionedUsers().size());
    }
    
    /** 检测消息中的图片和折叠消息内容 */
    private void detectMediaContent(QQMessage msg) {
        if (msg.getSegments() == null || msg.getSegments().isEmpty()) return;
        
        boolean hasImage = false;
        boolean hasForward = false;
        StringBuilder forwardText = new StringBuilder();
        
        for (QQMessage.MessageSegment seg : msg.getSegments()) {
            if (seg.isImage()) {
                hasImage = true;
                if (seg.url != null && !seg.url.isEmpty()) {
                    msg.addImageUrl(seg.url);
                }
                if (seg.file != null && !seg.file.isEmpty()) {
                    msg.addImageFilePath(seg.file);
                }
            }
            if (seg.isForward()) {
                hasForward = true;
                if (seg.content != null && !seg.content.isEmpty()) {
                    // content直接可用（部分实现如NapCat内嵌）
                    String extracted = extractForwardText(seg.content);
                    if (extracted != null && !extracted.isEmpty()) {
                        if (forwardText.length() > 0) forwardText.append("\n");
                        forwardText.append(extracted);
                    }
                } else if (seg.forwardId != null && !seg.forwardId.isEmpty()) {
                    // 标准OneBot v11：只有forward_id，需异步API获取
                    msg.setForwardId(seg.forwardId);
                    AiAgentActivity.debugLog("[QQMsg] 检测到折叠消息，forward_id=" + seg.forwardId + "，将异步获取内容");
                }
            }
        }
        
        if (hasImage) {
            msg.setHasImage(true);
            AiAgentActivity.debugLog("[QQMsg] 检测到图片消息: " + msg.getImageUrls().size() + "张");
        }
        if (hasForward) {
            msg.setHasForward(true);
            if (forwardText.length() > 0) {
                String content = forwardText.toString();
                if (content.length() > 1000) content = content.substring(0, 1000) + "...";
                msg.setForwardContent(content);
                AiAgentActivity.debugLog("[QQMsg] 检测到折叠消息，内容长度: " + content.length());
            }
        }
    }
    
    /** 从get_forward_msg API响应中提取文本 */
    private String extractForwardMsgContent(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        
        StringBuilder result = new StringBuilder();
        try {
            // 提取 data.messages 数组
            String dataObj = extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String messagesArr = extractArray(dataObj, "messages");
            if (messagesArr == null || messagesArr.isEmpty()) return null;
            
            // 拆分消息数组
            List<String> msgItems = splitJsonArray(messagesArr);
            for (String msgItem : msgItems) {
                // 提取发送者昵称
                String senderObj = extractObject(msgItem, "sender");
                String nickname = senderObj != null ? extractString(senderObj, "nickname") : null;
                if (nickname == null || nickname.isEmpty()) nickname = "未知";
                
                // 提取消息段数组
                String messageArr = extractArray(msgItem, "message");
                if (messageArr == null || messageArr.isEmpty()) continue;
                
                // 从消息段中提取文本
                List<String> segs = splitJsonArray(messageArr);
                StringBuilder msgText = new StringBuilder();
                for (String seg : segs) {
                    String type = extractString(seg, "type");
                    if ("text".equals(type)) {
                        String data = extractObject(seg, "data");
                        if (data != null) {
                            String text = extractString(data, "text");
                            if (text != null && !text.isEmpty()) {
                                if (msgText.length() > 0) msgText.append(" ");
                                msgText.append(text);
                            }
                        }
                    } else if ("image".equals(type)) {
                        if (msgText.length() > 0) msgText.append(" ");
                        msgText.append("[图片]");
                    } else if ("face".equals(type)) {
                        if (msgText.length() > 0) msgText.append(" ");
                        msgText.append("[表情]");
                    }
                }
                
                if (msgText.length() > 0) {
                    if (result.length() > 0) result.append("\n");
                    result.append(nickname).append(": ").append(msgText.toString());
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] extractForwardMsgContent解析失败: " + e.toString());
            return null;
        }
        
        return result.length() > 0 ? result.toString() : null;
    }

    /** 从get_msg API响应中提取消息段文本 */
    private String extractMsgSegmentsText(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        try {
            String dataObj = extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String msgArr = extractArray(dataObj, "message");
            if (msgArr == null || msgArr.isEmpty()) return null;
            List<String> segs = splitJsonArray(msgArr);
            for (String seg : segs) {
                String type = extractString(seg, "type");
                if ("text".equals(type)) {
                    String data = extractObject(seg, "data");
                    if (data != null) {
                        String text = extractString(data, "text");
                        if (text != null && !text.isEmpty()) sb.append(text);
                    }
                } else if ("image".equals(type)) {
                    sb.append("[图片]");
                } else if ("face".equals(type)) {
                    sb.append("[表情]");
                } else if ("forward".equals(type)) {
                    sb.append("[折叠消息]");
                } else if ("at".equals(type)) {
                    String data = extractObject(seg, "data");
                    if (data != null) {
                        String qq = extractString(data, "qq");
                        if (qq != null) sb.append("@").append(qq);
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
    
    /** 从get_msg API响应中检测是否包含forward段，返回forward_id */
    private String extractForwardIdFromMsgJson(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        try {
            String dataObj = extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String msgArr = extractArray(dataObj, "message");
            if (msgArr == null || msgArr.isEmpty()) return null;
            List<String> segs = splitJsonArray(msgArr);
            for (String seg : segs) {
                String type = extractString(seg, "type");
                if ("forward".equals(type)) {
                    String data = extractObject(seg, "data");
                    if (data != null) {
                        String fwdId = extractString(data, "id");
                        if (fwdId != null && !fwdId.isEmpty()) return fwdId;
                    }
                }
            }
        } catch (Exception e) { }
        return null;
    }
    
    /** 从forward content JSON中提取文本 */
    private String extractForwardText(String forwardContent) {
        if (forwardContent == null || forwardContent.isEmpty()) return null;
        
        StringBuilder result = new StringBuilder();
        try {
            // forward content是消息段数组的JSON
            List<String> items = splitJsonArray(forwardContent);
            for (String item : items) {
                // 每个item可能包含message数组
                String msgArr = extractArray(item, "message");
                if (msgArr != null && !msgArr.isEmpty()) {
                    List<String> msgSegments = splitJsonArray(msgArr);
                    for (String seg : msgSegments) {
                        String type = extractString(seg, "type");
                        if ("text".equals(type)) {
                            String dataObj = extractObject(seg, "data");
                            if (dataObj != null) {
                                String text = extractString(dataObj, "text");
                                if (text != null && !text.isEmpty()) {
                                    if (result.length() > 0) result.append(" | ");
                                    result.append(text);
                                }
                            }
                        } else if ("image".equals(type)) {
                            if (result.length() > 0) result.append(" | ");
                            result.append("[图片]");
                        }
                    }
                } else {
                    // 直接是消息段
                    String type = extractString(item, "type");
                    if ("text".equals(type)) {
                        String dataObj = extractObject(item, "data");
                        if (dataObj != null) {
                            String text = extractString(dataObj, "text");
                            if (text != null && !text.isEmpty()) {
                                if (result.length() > 0) result.append(" | ");
                                result.append(text);
                            }
                        }
                    } else if ("image".equals(type)) {
                        if (result.length() > 0) result.append(" | ");
                        result.append("[图片]");
                    }
                }
            }
        } catch (Exception e) {
            // 解析失败，降级：尝试直接提取文本
            return forwardContent.replaceAll("\\{[^}]*\\}", "").replaceAll("[\"\\[\\]]", "").trim();
        }
        
        return result.length() > 0 ? result.toString() : null;
    }

    // ==================== 消息处理 ====================

    /**
     * 处理已解析的QQ消息。
     */
    void handleMessage(QQMessage msg, Consumer<String> responseSender) {
        // 防重复
        final long msgId = msg.getMessageId();
        if (!processingMessages.add(msgId)) {
            AiAgentActivity.debugLog("[QQMsg] 消息已处理中，忽略: " + msgId);
            return;
        }
        try {
            AiAgentActivity.debugLog("[QQMsg] 开始处理: " + msg);
            
            // 检查unifiedMemory是否初始化
            if (unifiedMemory == null) {
                AiAgentActivity.debugLog("[QQMsg] 错误: unifiedMemory未初始化！dataDir=" + dataDir);
                sendReply(msg, "系统错误：记忆管理器未就绪，请稍后再试。");
                processingMessages.remove(msgId);
                return;
            }
            
            // 检查selfId是否设置（群聊@检测需要）
            if (msg.isGroupMessage() && selfId == 0) {
                AiAgentActivity.debugLog("[QQMsg] 警告: selfId未设置，群聊@检测将失效！请执行 ai/onebotsetselfid <QQ号>");
            }

            // === 记录所有群聊消息到历史记录（无论是否@） ===
            if (msg.isGroupMessage()) {
                // 0. 记录群成员角色（用于@管理员/群主时查表）
                if (msg.getSender() != null && msg.getSender().role != null) {
                    unifiedMemory.recordGroupMemberRole(
                        msg.getGroupId(),
                        msg.getUserId(),
                        msg.getDisplayName(),
                        msg.getSender().role
                    );
                }
                
                // 1. 记录到群聊完整历史（持久化）- 仅用于临时查阅，不作为长期记忆
                unifiedMemory.addGroupChatMessage(
                    msg.getUserId(),
                    msg.getDisplayName(),
                    msg.getPlainText(),
                    msg.getGroupId()
                );
                
                // 2. 如果消息触发了AI响应，也记录到个人对话历史（长期存储）
                boolean shouldRespond = msg.isAtBot(); // 仅响应@自己的消息
                if (!shouldRespond) {
                    String botName = sair.aiagent.core.AiConfig.getInstance().getBotName();
                    if (botName != null && !botName.isEmpty()) {
                        String plainText = msg.getPlainText();
                        if (plainText != null && plainText.contains(botName)) {
                            shouldRespond = true;
                        }
                    }
                }
                
                if (shouldRespond) {
                    // 记录到统一对话历史，标记为群聊来源和个人ID（长期存储个人印象）
                    unifiedMemory.addConversation(
                        "user",
                        msg.getPlainText(),
                        "group",
                        msg.getGroupId(),
                        msg.getUserId(),  // 使用个人QQ号作为标识
                        msg.getDisplayName()
                    );
                }
            } else {
                // 私聊：记录到统一对话历史，标记为私聊来源
                unifiedMemory.addConversation(
                    "user",
                    msg.getPlainText(),
                    "private",
                    msg.getUserId(),
                    msg.getUserId(),
                    msg.getDisplayName()
                );
            }

            // 群消息过滤：仅响应@或提到名字（移除isReply，避免响应所有回复）
            if (msg.isGroupMessage()) {
                boolean shouldRespond = msg.isAtBot(); // 仅响应@自己的消息
                
                // 检查是否提到了AI的名字
                if (!shouldRespond) {
                    String botName = sair.aiagent.core.AiConfig.getInstance().getBotName();
                    if (botName != null && !botName.isEmpty()) {
                        String plainText = msg.getPlainText();
                        if (plainText != null && plainText.contains(botName)) {
                            shouldRespond = true;
                            AiAgentActivity.debugLog("[QQMsg] 检测到提到名字: " + botName);
                        }
                    }
                }
                
                if (!shouldRespond) {
                    AiAgentActivity.debugLog("[QQMsg] 群消息已忽略(未@或提到名字): " + msg.getRawMessage());
                    processingMessages.remove(msgId);
                    return;
                }
            }

            // 获取发送者QQ号用于记忆隔离
            long qq = msg.getUserId();
            
            // === InternalAgents规则检查 ===
            if (internalAgents != null) {
                boolean shouldBlock = internalAgents.checkAndEnforceRules(
                    qq, 
                    msg.isGroupMessage() ? msg.getGroupId() : 0,
                    msg.getPlainText()
                );
                
                if (shouldBlock) {
                    AiAgentActivity.debugLog("[QQMsg] 消息被InternalAgents拦截: userId=" + qq);
                    processingMessages.remove(msgId);
                    return;
                }
            }
            
            // 注意：用户消息已在上面的if-else块中记录到unifiedMemory，这里不需要再次记录
            AiAgentActivity.debugLog("[QQMsg] 准备执行AI: " + (msg.isGroupMessage() ? "群聊" : "私聊") + ", userId=" + qq);

            // === 群管理员被动检查（被@时检查违规） ===
            List<String> punishmentRecords = new ArrayList<>();
            if (msg.isGroupMessage() && groupModerator != null) {
                GroupModerationConfig config = groupModerator.getGroupConfig(msg.getGroupId());
                if (config != null && config.isAutoMonitorEnabled()) {
                    AiAgentActivity.debugLog("[QQMsg] Bot是管理员，被@时检查群消息违规");
                    punishmentRecords = groupModerator.checkMessagesOnAt(msg.getGroupId(), qq);
                    
                    if (!punishmentRecords.isEmpty()) {
                        AiAgentActivity.debugLog("[QQMsg] 发现 " + punishmentRecords.size() + " 个违规行为");
                    }
                }
            }

            // === 情绪检测（在主线程快速完成，不阻塞） ===
            if (emotionManager != null) {
                try {
                    String pt = msg.getPlainTextOnly();
                    if (pt != null) {
                        String detected = emotionManager.detectFriendlyEmotion(pt);
                        if ("praise".equals(detected)) {
                            emotionManager.onPraise(qq);
                        } else if ("comfort".equals(detected)) {
                            emotionManager.onComfort(qq);
                        } else if ("friendly".equals(detected)) {
                            emotionManager.onFriendlyInteraction(qq);
                        }
                    }
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[QQMsg] Emotion detect fail: " + e.toString());
                }
            }

            // === 提交到线程池：获取引用/折叠消息 → 构建提示词 → 执行AI ===
            // 引用和折叠消息需要API调用，在线程池内同步等待（最多各10秒）
            // 构建提示词必须在获取引用/折叠内容之后，确保上下文完整
            final List<String> finalPunishmentRecords = punishmentRecords;
            execPool.submit(() -> {
                try {
                    // 获取引用消息内容
                    if (msg.isReply() && msg.getReplyMessageId() > 0 && napcatApi != null) {
                        int quotedMsgId = (int) msg.getReplyMessageId();
                        AiAgentActivity.debugLog("[QQMsg] 获取引用消息: messageId=" + quotedMsgId);
                        try {
                            String quotedJson = napcatApi.getMessage(quotedMsgId);
                            if (quotedJson != null && !quotedJson.isEmpty()) {
                                // 优先从 data.message 数组解析文本（比raw_message更准确）
                                String quotedText = extractMsgSegmentsText(quotedJson);
                                if (quotedText != null && !quotedText.isEmpty()) {
                                    msg.setQuotedMessageContent(quotedText);
                                    AiAgentActivity.debugLog("[QQMsg] 引用消息内容(segment): " +
                                        (quotedText.length() > 100 ? quotedText.substring(0, 100) + "..." : quotedText));
                                }
                                // 检查引用消息是否本身就是转发/折叠消息，递归展开
                                String quotedFwdId = extractForwardIdFromMsgJson(quotedJson);
                                if (quotedFwdId != null && !quotedFwdId.isEmpty()) {
                                    AiAgentActivity.debugLog("[QQMsg] 引用消息是折叠消息: forwardId=" + quotedFwdId);
                                    try {
                                        String fwdJson = napcatApi.getForwardMsg(quotedFwdId);
                                        if (fwdJson != null && !fwdJson.isEmpty()) {
                                            String fwdC = extractForwardMsgContent(fwdJson);
                                            if (fwdC != null && !fwdC.isEmpty()) {
                                                if (fwdC.length() > 1000) fwdC = fwdC.substring(0, 1000) + "...";
                                                String exist = msg.getQuotedMessageContent();
                                                msg.setQuotedMessageContent((exist != null ? exist + "\n\n" : "")
                                                    + "[折叠消息展开内容]\n" + fwdC);
                                                AiAgentActivity.debugLog("[QQMsg] 引用折叠消息展开成功，长度: " + fwdC.length());
                                            }
                                        }
                                    } catch (Exception ef) {
                                        AiAgentActivity.debugLog("[QQMsg] 引用折叠消息展开失败: " + ef.toString());
                                    }
                                }
                                // 降级：raw_message
                                if (msg.getQuotedMessageContent() == null || msg.getQuotedMessageContent().isEmpty()) {
                                    String raw = extractString(quotedJson, "raw_message");
                                    if (raw != null && !raw.isEmpty()) {
                                        msg.setQuotedMessageContent(raw);
                                        AiAgentActivity.debugLog("[QQMsg] 引用消息降级raw_message: " +
                                            (raw.length() > 100 ? raw.substring(0, 100) + "..." : raw));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            AiAgentActivity.debugLog("[QQMsg] 获取引用消息失败: " + e.toString());
                        }
                    }                    // 获取折叠消息内容（需API调用获取forward详情）
                    if (msg.getForwardId() != null && !msg.getForwardId().isEmpty() && napcatApi != null) {
                        AiAgentActivity.debugLog("[QQMsg] 获取折叠消息: forwardId=" + msg.getForwardId());
                        try {
                            String forwardJson = napcatApi.getForwardMsg(msg.getForwardId());
                            if (forwardJson != null && !forwardJson.isEmpty()) {
                                String extracted = extractForwardMsgContent(forwardJson);
                                if (extracted != null && !extracted.isEmpty()) {
                                    if (extracted.length() > 1000) extracted = extracted.substring(0, 1000) + "...";
                                    msg.setForwardContent(extracted);
                                    AiAgentActivity.debugLog("[QQMsg] 获取到折叠消息内容，长度: " + extracted.length());
                                }
                            }
                        } catch (Exception e) {
                            AiAgentActivity.debugLog("[QQMsg] 获取折叠消息失败: " + e.toString());
                        }
                    }
                    
                    // 构建系统提示词（此时引用/折叠内容已就绪）
                    String systemPrompt = buildExecqSystemPrompt(msg, finalPunishmentRecords);
                    AiAgentActivity.debugLog("[QQMsg] 系统提示词构建完成，长度: " + systemPrompt.length());
                    
                    // 执行AI代理
                    AiAgentActivity.debugLog("[QQMsg] 开始执行AI代理...");
                    executeQqAgent(msg, unifiedMemory, systemPrompt, responseSender);
                    AiAgentActivity.debugLog("[QQMsg] AI代理执行完成");
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[QQMsg] 执行错误: " + e.toString());
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    AiAgentActivity.debugLog("[QQMsg] 堆栈跟踪:\n" + sw.toString());
                } finally {
                    processingMessages.remove(msgId);
                }
            });

        } catch (Exception e) {
            processingMessages.remove(msgId);
            AiAgentActivity.debugLog("[QQMsg] handleMessage错误: " + e.toString());
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            AiAgentActivity.debugLog("[QQMsg] 堆栈跟踪:\n" + sw.toString());
            throw e;
        }
    }

    // ==================== 好友申请处理 ====================

    /**
     * 处理好友申请（OneBot v11 request/friend/add事件）。
     * 规则：无验证消息拒绝，有验证消息同意。同意后记录时间戳用于反滥用检测。
     * @param userId 申请人QQ号
     * @param comment 验证消息（可能为空）
     * @param flag OneBot请求标识（用于API调用）
     */
    public void handleFriendRequest(long userId, String comment, String flag) {
        AiAgentActivity.debugLog("[QQMsg] 好友申请: userId=" + userId + ", comment=" + 
            (comment != null ? (comment.length() > 30 ? comment.substring(0, 30) + "..." : comment) : "(空)"));
        
        if (napcatApi == null) {
            AiAgentActivity.debugLog("[QQMsg] NapCatApi未就绪，无法处理好友申请");
            return;
        }
        
        // 无验证消息 → 拒绝
        if (comment == null || comment.trim().isEmpty()) {
            AiAgentActivity.debugLog("[QQMsg] 好友申请无验证消息，拒绝");
            napcatApi.handleFriendRequest(flag, false, "请发送验证消息说明来意");
            return;
        }
        
        // 有验证消息 → 同意并记录时间戳
        AiAgentActivity.debugLog("[QQMsg] 好友申请有验证消息，同意");
        napcatApi.handleFriendRequest(flag, true, null);
        friendAcceptTimestamps.put(userId, System.currentTimeMillis());
        
        // 异步清理过期记录（5分钟后自动过期，避免内存泄漏）
        execPool.submit(() -> {
            try {
                Thread.sleep(5 * 60 * 1000);
                Long ts = friendAcceptTimestamps.get(userId);
                if (ts != null && (System.currentTimeMillis() - ts) >= 5 * 60 * 1000) {
                    friendAcceptTimestamps.remove(userId);
                }
            } catch (InterruptedException ignored) {}
        });
    }

    // ==================== 群邀请处理 ====================

    /**
     * 处理群邀请请求（OneBot v11 request/group/invite事件）。
     * 决策优先级：①主人无条件同意 → ②刚通过好友(5min内)拉群拒+警告 → ③好感度/身份决策。
     * @param userId 邀请者QQ号
     * @param groupId 群号
     * @param flag OneBot请求标识（用于API调用）
     */
    public void handleGroupInviteRequest(long userId, long groupId, String flag) {
        AiAgentActivity.debugLog("[QQMsg] 群邀请决策: userId=" + userId + ", groupId=" + groupId);
        
        if (napcatApi == null) {
            AiAgentActivity.debugLog("[QQMsg] NapCatApi未就绪，无法处理群邀请");
            return;
        }
        
        // ① 主人 → 无条件同意（主人绕过所有检测）
        boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(userId);
        if (isMaster) {
            AiAgentActivity.debugLog("[QQMsg] 主人邀请加群，无条件同意");
            napcatApi.handleGroupInvite(flag, true, null);
            if (server != null) server.sendPrivateMsg(userId, "[Bot] 主人邀请，已自动同意加群~");
            return;
        }
        
        // ② 反滥用：刚通过好友申请5分钟内发起群邀请 → 拒+警告
        Long acceptTime = friendAcceptTimestamps.get(userId);
        if (acceptTime != null && (System.currentTimeMillis() - acceptTime) < 5 * 60 * 1000) {
            AiAgentActivity.debugLog("[QQMsg] 刚通过好友申请即邀请加群，拒绝+警告: userId=" + userId);
            napcatApi.handleGroupInvite(flag, false, "滥用检测");
            if (server != null) server.sendPrivateMsg(userId,
                "[Bot] 请不要厚着脸皮拉我进群，我还没认可你，也更没充分了解你。");
            return;
        }
        
        // ③ 获取好感度与特殊身份
        int affection = emotionManager != null ? emotionManager.getAffection(userId) : 0;
        boolean isBetrayer = emotionManager != null && emotionManager.isBetrayer(userId);
        
        // ④ 背叛者 → 直接拒绝
        if (isBetrayer) {
            AiAgentActivity.debugLog("[QQMsg] 背叛者邀请加群，自动拒绝");
            napcatApi.handleGroupInvite(flag, false, "你已被永久拉黑");
            return;
        }
        
        // ⑤ 好感度>600 → 同意加群
        if (affection > 600) {
            AiAgentActivity.debugLog("[QQMsg] 好感度>600，同意群邀请: affection=" + affection);
            napcatApi.handleGroupInvite(flag, true, null);
            if (server != null) server.sendPrivateMsg(userId, "[Bot] 好感度达标，已同意加群邀请~");
            return;
        }
        
        // ⑥ 好感度200-600 → 忽略，保持pending（Bot酌情考虑但不自动处理）
        if (affection > 200) {
            AiAgentActivity.debugLog("[QQMsg] 好感度一般，忽略群邀请(保持pending): affection=" + affection);
            return;
        }
        
        // ⑦ 好感度≤200（含陌生人=0）→ 拒绝
        AiAgentActivity.debugLog("[QQMsg] 好感度过低，拒绝群邀请: affection=" + affection);
        napcatApi.handleGroupInvite(flag, false, "好感度不足");
    }

    // ==================== execq Agent执行 ====================

    /**
     * 执行真正的execs链路（与SFW控制台execs完全一样）。
     * 实时推送每一轮的思考和执行结果给主人。
     * @param msg QQ消息对象
     * @param task 任务描述（已去掉execs:前缀）
     * @param responseSender 消息发送回调
     * @return 执行结果摘要
     */
    private String executeRealExecs(QQMessage msg, String task, Consumer<String> responseSender) {
        if (agentExecutor == null) {
            return "[错误] AI引擎未就绪";
        }
        
        try {
            AiAgentActivity.debugLog("[QQMsg] 开始执行真实execs链路: " + task);
            
            // 在新线程中异步执行execs（不阻塞QQ响应）
            Thread execThread = new Thread(() -> {
                String botLabel = getBotLabel();
                try {
                    AiAgentActivity.debugLog("[QQMsg-Execs] execs线程启动，开始执行任务");
                    
                    // 发送开始通知
                    sendReply(msg, "\n[" + botLabel + "execs] 🚀 开始执行任务...\n任务: " + (task.length() > 50 ? task.substring(0, 50) + "..." : task));
                    
                    // 设置QQ execs回调，实时接收每一轮的输出
                    agentExecutor.setQqExecsCallback((roundOutput) -> {
                        // 在后台线程中发送消息给主人
                        sendReply(msg, roundOutput);
                    });
                    
                    // 执行真正的execs
                    agentExecutor.execute(task);
                    
                    // 清除回调
                    agentExecutor.setQqExecsCallback(null);
                    
                    AiAgentActivity.debugLog("[QQMsg-Execs] execs执行完成");
                    sendReply(msg, "\n[" + botLabel + "execs] ✅ 任务执行完成\n详细输出请查看SFW控制台");
                    
                } catch (Exception e) {
                    // 清除回调
                    agentExecutor.setQqExecsCallback(null);
                    
                    AiAgentActivity.debugLog("[QQMsg-Execs] execs执行异常: " + e.toString());
                    sendReply(msg, "\n[" + botLabel + "execs] ❌ 任务执行失败: " + e.getMessage() + "\n详细错误请查看SFW控制台");
                }
            }, "QQ-Execs-" + msg.getUserId());
            
            execThread.setDaemon(true); // 设置为守护线程，避免阻止JVM退出
            execThread.start();
            
            AiAgentActivity.debugLog("[QQMsg] execs任务已提交到后台线程");
            
            // 立即返回，不等待execs完成
            return "execs任务已在后台启动，请查看SFW控制台获取实时输出";
            
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] execs提交失败: " + e.toString());
            return "[错误] execs任务提交失败: " + e.getMessage();
        }
    }

    /**
     * 执行QQ通道的Agent处理。
     * 根据发送者身份决定使用execq（受限）还是execs（自由）模式。
     */
    private void executeQqAgent(QQMessage msg, Object memoryManager,
                                 String systemPrompt, Consumer<String> responseSender) {
        if (agentExecutor == null) {
            sendReply(msg, "AI引擎未就绪，请稍后再试。");
            return;
        }

        // 检测 <stop> 标签：直接停止正在运行的 Agent 执行
        String plainText = msg.getPlainText();
        if (plainText != null && plainText.contains("<stop>")) {
            AiAgentActivity.debugLog("[QQMsg] 检测到<stop>标签，停止Agent执行");
            agentExecutor.markStopped();
            sendReply(msg, "\u2705 Agent执行已停止。");
            return;
        }

        try {
            // 构建任务描述
            String task = buildTaskDescription(msg);
            
            // 判断是否是主人
            long senderQQ = msg.getUserId();
            boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ);
            
            // 检测是否有execs:前缀
            boolean useExecs = isMaster && plainText != null && plainText.startsWith("execs:");
            
            AiAgentActivity.debugLog("[QQMsg] 发送者QQ: " + senderQQ + ", 是否主人: " + isMaster + ", 使用execs: " + useExecs);

            String aiResponse;
            if (useExecs) {
                // 主人 + execs:前缀：走真正的execs链路
                AiAgentActivity.debugLog("[QQMsg] 主人execs消息，执行完整execs链路");
                
                // 先发送提示消息给主人
                sendReply(msg, "[execs] 这条消息将交给另一个身份进行处理...\n正在启动execs链路...");
                
                // 去掉execs:前缀，获取实际任务
                String actualTask = plainText.substring(6).trim();
                
                // 执行真正的execs（与SFW控制台execs完全一样）
                aiResponse = executeRealExecs(msg, actualTask, responseSender);
            } else if (isMaster) {
                // 主人但没有execs:前缀：使用execq模式（受限）
                AiAgentActivity.debugLog("[QQMsg] 主人普通消息，使用execq模式（受限）");
                // 群聊构建群管回调，作为线程安全的参数传入（不再用共享字段）
                final java.util.function.Function<String, String> groupHandler = msg.isGroupMessage() && napcatApi != null
                    ? (r -> { processQqActions(r, msg); return "[群管已执行] "; }) : null;
                aiResponse = agentExecutor.executeQq(task, systemPrompt, memoryManager, groupHandler);
            } else {
                // 普通用户：使用execq模式（受限模式，插件白名单）
                AiAgentActivity.debugLog("[QQMsg] 普通用户消息，使用execq模式（受限模式）");
                // 群聊构建群管回调，作为线程安全的参数传入（不再用共享字段）
                final java.util.function.Function<String, String> groupHandler = msg.isGroupMessage() && napcatApi != null
                    ? (r -> { processQqActions(r, msg); return "[群管已执行] "; }) : null;
                aiResponse = agentExecutor.executeQq(task, systemPrompt, memoryManager, groupHandler);
            }
            
            // 记录AI回复到统一对话历史
            if (aiResponse != null && !aiResponse.trim().isEmpty()) {
                if (msg.isGroupMessage()) {
                    // 群聊：记录到统一历史，标记为群聊来源
                    unifiedMemory.addConversation(
                        "assistant",
                        aiResponse,
                        "group",
                        msg.getGroupId(),
                        selfId,  // AI的QQ号
                        null      // Bot名不存入SQL，仅存于config.properties
                    );
                } else {
                    // 私聊：记录到统一历史，标记为私聊来源
                    unifiedMemory.addConversation(
                        "assistant",
                        aiResponse,
                        "private",
                        msg.getUserId(),
                        selfId,
                        null      // Bot名不存入SQL，仅存于config.properties
                    );
                }

                // === v2.2: 发送前拦截 <ban>/<kick> 标签，执行群管操作（OneBot层自有能力） ===
                processQqActions(aiResponse, msg);

                // 移除XML标签后发送，支持<split>拆分为多条消息
                String cleanResponse = aiResponse.replaceAll("<[^>]+>", "").trim();
                if (!cleanResponse.isEmpty()) {
                    // 先用<split>分割（标签已被移除，内容变成连续文本在段落间）
                    // 检查原始响应中是否有<split>来决定是否拆分
                    String[] splitParts = aiResponse.split("<split>");
                    if (splitParts.length > 1) {
                        // AI用了<split>标签，拆分为多条
                        List<String> messages = new ArrayList<>();
                        for (String part : splitParts) {
                            String cleaned = part.replaceAll("<[^>]+>", "").trim();
                            if (!cleaned.isEmpty()) messages.add(cleaned);
                        }
                        sendMultipleReplies(msg, messages);
                    } else {
                        // 没使用<split>，正常按段落分割
                        List<String> messages = splitIntoMessages(cleanResponse);
                        sendMultipleReplies(msg, messages);
                    }
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] Agent执行失败: " + e.toString());
            sendReply(msg, "处理失败: " + e.getMessage());
        }
    }

    /** 发送回复消息（智能选择是否@发送者） */
    private void sendReply(QQMessage msg, String text) {
        if (server == null || text == null || text.trim().isEmpty()) return;

        if (msg.isGroupMessage()) {
            // 群聊回复：智能判断是否需要@
            // 如果是被@触发的回复，可以选择@回去；如果是主动查看，不@
            String finalText = text;
            
            // 如果消息中包含[CQ:reply]标签或者是被@触发的，可以选择@用户
            // 这里默认不@，让AI更自然地参与对话
            server.sendGroupMsg(msg.getGroupId(), finalText);
        } else {
            server.sendPrivateMsg(msg.getUserId(), text);
        }
    }

    /** 发送多条消息（支持分段发送长文本） */
    private void sendMultipleReplies(QQMessage msg, List<String> texts) {
        if (texts == null || texts.isEmpty()) return;
        
        for (String text : texts) {
            if (text != null && !text.trim().isEmpty()) {
                sendReply(msg, text);
                // 每条消息之间稍微延迟，避免发送过快
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /**
     * 发送前处理AI响应中的群管标签（<ban>/<kick>）。
     * 这是OneBot层的自有能力，不污染AgentExecutor主线。
     */
    private void processQqActions(String aiResponse, QQMessage msg) {

        long groupId = msg.isGroupMessage() ? msg.getGroupId() : 0;
        
        // === 群管标签（需要群聊+API上下文） ===
        if (msg.isGroupMessage() && napcatApi != null) {
            // 处理 <ban>QQ号 [秒数]</ban>
        java.util.regex.Matcher banMatcher = java.util.regex.Pattern.compile(
            "<ban>\\s*(\\d+)(?:\\s+(\\d+))?\\s*</ban>").matcher(aiResponse);
        while (banMatcher.find()) {
            try {
                long userId = Long.parseLong(banMatcher.group(1));
                int duration = 180; // 默认3分钟
                if (banMatcher.group(2) != null) {
                    duration = Integer.parseInt(banMatcher.group(2));
                    if (duration < 0) duration = 0;
                    if (duration > 2592000) duration = 2592000;
                }
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<ban>: groupId=" + groupId + ", userId=" + userId + ", duration=" + duration + "s");
                napcatApi.muteGroupMember(groupId, userId, duration);
            } catch (NumberFormatException e) {
                AiAgentActivity.debugLog("[QQMsg] <ban>标签格式错误: " + banMatcher.group());
            }
        }
        
        // 处理 <kick>QQ号 [block]</kick>
        java.util.regex.Matcher kickMatcher = java.util.regex.Pattern.compile(
            "<kick>\\s*(\\d+)(?:\\s+(block))?\\s*</kick>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(aiResponse);
        while (kickMatcher.find()) {
            try {
                long userId = Long.parseLong(kickMatcher.group(1));
                boolean block = kickMatcher.group(2) != null;
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<kick>: groupId=" + groupId + ", userId=" + userId + ", block=" + block);
                napcatApi.kickGroupMember(groupId, userId, block);
            } catch (NumberFormatException e) {
                AiAgentActivity.debugLog("[QQMsg] <kick>标签格式错误: " + kickMatcher.group());
            }
        }
        
        // 处理 <muteall>on|off</muteall> — 全员禁言
        java.util.regex.Matcher muteallMatcher = java.util.regex.Pattern.compile(
            "<muteall>\\s*(on|off|true|false|1|0)\\s*</muteall>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(aiResponse);
        while (muteallMatcher.find()) {
            String val = muteallMatcher.group(1).toLowerCase();
            boolean enable = "on".equals(val) || "true".equals(val) || "1".equals(val);
            AiAgentActivity.debugLog("[QQMsg] 发送前拦截<muteall>: groupId=" + groupId + ", enable=" + enable);
            napcatApi.muteAll(groupId, enable);
        }
        
        // 处理 <setadmin>QQ号 on|off</setadmin> — 设置/取消管理员
        java.util.regex.Matcher setadminMatcher = java.util.regex.Pattern.compile(
            "<setadmin>\\s*(\\d+)\\s+(on|off|true|false|1|0)\\s*</setadmin>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(aiResponse);
        while (setadminMatcher.find()) {
            try {
                long userId = Long.parseLong(setadminMatcher.group(1));
                String val = setadminMatcher.group(2).toLowerCase();
                boolean enable = "on".equals(val) || "true".equals(val) || "1".equals(val);
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<setadmin>: groupId=" + groupId + ", userId=" + userId + ", enable=" + enable);
                napcatApi.setGroupAdmin(groupId, userId, enable);
            } catch (NumberFormatException e) {
                AiAgentActivity.debugLog("[QQMsg] <setadmin>标签格式错误: " + setadminMatcher.group());
            }
        }
        
        // 处理 <setcard>QQ号 名片</setcard> — 设置群名片
        java.util.regex.Matcher setcardMatcher = java.util.regex.Pattern.compile(
            "<setcard>\\s*(\\d+)\\s+(.+?)\\s*</setcard>", java.util.regex.Pattern.DOTALL).matcher(aiResponse);
        while (setcardMatcher.find()) {
            try {
                long userId = Long.parseLong(setcardMatcher.group(1));
                String card = setcardMatcher.group(2).trim();
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<setcard>: groupId=" + groupId + ", userId=" + userId + ", card=" + card);
                napcatApi.setGroupCard(groupId, userId, card);
            } catch (NumberFormatException e) {
                AiAgentActivity.debugLog("[QQMsg] <setcard>标签格式错误: " + setcardMatcher.group());
            }
        }
        
        // 处理 <setgroupname>新群名</setgroupname> — 设置群名
        java.util.regex.Matcher setgnMatcher = java.util.regex.Pattern.compile(
            "<setgroupname>\\s*(.+?)\\s*</setgroupname>", java.util.regex.Pattern.DOTALL).matcher(aiResponse);
        while (setgnMatcher.find()) {
            String name = setgnMatcher.group(1).trim();
            AiAgentActivity.debugLog("[QQMsg] 发送前拦截<setgroupname>: groupId=" + groupId + ", name=" + name);
            napcatApi.setGroupName(groupId, name);
        }
        
        // 处理 <leavegroup>[dismiss]</leavegroup> — 退出/解散群
        java.util.regex.Matcher leavegroupMatcher = java.util.regex.Pattern.compile(
            "<leavegroup>\\s*(dismiss)?\\s*</leavegroup>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(aiResponse);
        while (leavegroupMatcher.find()) {
            boolean isDismiss = leavegroupMatcher.group(1) != null;
            AiAgentActivity.debugLog("[QQMsg] 发送前拦截<leavegroup>: groupId=" + groupId + ", dismiss=" + isDismiss);
            napcatApi.leaveGroup(groupId, isDismiss);
        }
        
        // 处理 <block>QQ号</block> — 拉黑用户
        java.util.regex.Matcher blockMatcher = java.util.regex.Pattern.compile(
            "<block>\\s*(\\d+)\\s*</block>").matcher(aiResponse);
        while (blockMatcher.find()) {
            try {
                long userId = Long.parseLong(blockMatcher.group(1));
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<block>: userId=" + userId);
                if (internalAgents != null) {
                    internalAgents.blockUser(userId, "AI判定违规拉黑", true);
                }
            } catch (NumberFormatException e) {
                AiAgentActivity.debugLog("[QQMsg] <block>标签格式错误: " + blockMatcher.group());
            }
        }
        
        // 处理 <unblock>QQ号</unblock> — 取消拉黑
        java.util.regex.Matcher unblockMatcher = java.util.regex.Pattern.compile(
            "<unblock>\\s*(\\d+)\\s*</unblock>").matcher(aiResponse);
        while (unblockMatcher.find()) {
            try {
                long userId = Long.parseLong(unblockMatcher.group(1));
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<unblock>: userId=" + userId);
                if (internalAgents != null) {
                    internalAgents.unblockUser(userId);
                }
            } catch (NumberFormatException e) {
                AiAgentActivity.debugLog("[QQMsg] <unblock>标签格式错误: " + unblockMatcher.group());
            }
        }
        
        } // end 群管标签块
        
        // === 图片/语音/文件发送标签 ===
        if (napcatApi != null) {
            // 处理 <sendimage>路径或内容</sendimage> — 发送图片
            java.util.regex.Matcher sendImageMatcher = java.util.regex.Pattern.compile(
                "<sendimage>\\s*(.+?)\\s*</sendimage>", java.util.regex.Pattern.DOTALL).matcher(aiResponse);
            while (sendImageMatcher.find()) {
                String imageContent = sendImageMatcher.group(1).trim();
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<sendimage>: " + (imageContent.length() > 40 ? imageContent.substring(0, 40) + "..." : imageContent));
                // 判断是文件路径还是文字内容
                File imgFile = new File(imageContent);
                if (imgFile.exists() && imgFile.isFile()) {
                    // 本地文件路径，直接发送
                    if (msg.isGroupMessage()) {
                        napcatApi.sendGroupImage(msg.getGroupId(), imageContent);
                    } else {
                        napcatApi.sendPrivateImage(msg.getUserId(), imageContent);
                    }
                } else if (imageContent.startsWith("http://") || imageContent.startsWith("https://")) {
                    // URL，直接发送
                    if (msg.isGroupMessage()) {
                        napcatApi.sendGroupImage(msg.getGroupId(), imageContent);
                    } else {
                        napcatApi.sendPrivateImage(msg.getUserId(), imageContent);
                    }
                } else {
                    // 文字内容，渲染为图片后发送
                    try {
                        String dataDirPath = dataDir;
                        File outputDir = new File(dataDirPath, "rendered");
                        String fileName = "img_" + System.currentTimeMillis() + ".png";
                        File outputFile = new File(outputDir, fileName);
                        sair.aiagent.util.ImageRenderer.renderTextToImage(imageContent, outputFile);
                        String absPath = outputFile.getAbsolutePath();
                        if (msg.isGroupMessage()) {
                            napcatApi.sendGroupImage(msg.getGroupId(), absPath);
                        } else {
                            napcatApi.sendPrivateImage(msg.getUserId(), absPath);
                        }
                    } catch (Exception e) {
                        AiAgentActivity.debugLog("[QQMsg] <sendimage>渲染失败: " + e.toString());
                    }
                }
            }
            
            // 处理 <sendrecord>路径或URL</sendrecord> — 发送语音
            java.util.regex.Matcher sendRecordMatcher = java.util.regex.Pattern.compile(
                "<sendrecord>\\s*(.+?)\\s*</sendrecord>", java.util.regex.Pattern.DOTALL).matcher(aiResponse);
            while (sendRecordMatcher.find()) {
                String recordPath = sendRecordMatcher.group(1).trim();
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<sendrecord>: " + recordPath);
                if (msg.isGroupMessage()) {
                    napcatApi.sendGroupRecord(msg.getGroupId(), recordPath);
                } else {
                    napcatApi.sendPrivateRecord(msg.getUserId(), recordPath);
                }
            }
            
            // 处理 <sendfile>本地文件路径</sendfile> — 发送文件（仅主人可用）
            java.util.regex.Matcher sendFileMatcher = java.util.regex.Pattern.compile(
                "<sendfile>\\s*(.+?)\\s*</sendfile>", java.util.regex.Pattern.DOTALL).matcher(aiResponse);
            while (sendFileMatcher.find()) {
                String filePath = sendFileMatcher.group(1).trim();
                long senderQQ = msg.getUserId();
                boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ);
                
                if (!isMaster) {
                    AiAgentActivity.debugLog("[QQMsg] <sendfile>被拒绝: 用户 " + senderQQ + " 不是主人");
                    continue;
                }
                
                File f = new File(filePath);
                if (!f.exists() || !f.isFile()) {
                    AiAgentActivity.debugLog("[QQMsg] <sendfile>文件不存在: " + filePath);
                    continue;
                }
                
                AiAgentActivity.debugLog("[QQMsg] 发送前拦截<sendfile>: " + filePath);
                if (msg.isGroupMessage()) {
                    napcatApi.sendGroupFile(msg.getGroupId(), filePath, f.getName());
                } else {
                    napcatApi.sendPrivateFile(msg.getUserId(), filePath, f.getName());
                }
            }
        }
        
        // === 好友管理标签（私聊/群聊均可） ===
        // 处理 <delfriend>QQ号</delfriend> — 删除好友
        if (napcatApi != null) {
            java.util.regex.Matcher delfriendMatcher = java.util.regex.Pattern.compile(
                "<delfriend>\\s*(\\d+)\\s*</delfriend>").matcher(aiResponse);
            while (delfriendMatcher.find()) {
                try {
                    long userId = Long.parseLong(delfriendMatcher.group(1));
                    AiAgentActivity.debugLog("[QQMsg] 发送前拦截<delfriend>: userId=" + userId);
                    napcatApi.deleteFriend(userId);
                } catch (NumberFormatException e) {
                    AiAgentActivity.debugLog("[QQMsg] <delfriend>标签格式错误: " + delfriendMatcher.group());
                }
            }
        }
    }

    /** 将长文本分割成多条消息（按段落或长度） */
    private List<String> splitIntoMessages(String text) {
        List<String> messages = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return messages;
        
        // 按换行符分割成段落
        String[] paragraphs = text.split("\n\\s*\n");
        
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (!trimmed.isEmpty()) {
                // 如果段落太长（超过200字符），进一步分割
                if (trimmed.length() > 200) {
                    // 按句子分割
                    String[] sentences = trimmed.split("(?<=[。！？.!?])");
                    StringBuilder currentMsg = new StringBuilder();
                    
                    for (String sentence : sentences) {
                        if (currentMsg.length() + sentence.length() > 200 && currentMsg.length() > 0) {
                            messages.add(currentMsg.toString());
                            currentMsg = new StringBuilder();
                        }
                        currentMsg.append(sentence);
                    }
                    
                    if (currentMsg.length() > 0) {
                        messages.add(currentMsg.toString());
                    }
                } else {
                    messages.add(trimmed);
                }
            }
        }
        
        // 如果没有分割成功，返回原文本
        if (messages.isEmpty()) {
            messages.add(text);
        }
        
        return messages;
    }

    // ==================== 提示词构建 ====================

    /** 构建execq通道的系统提示词 */
    private String buildExecqSystemPrompt(QQMessage msg, List<String> punishmentRecords) {
        StringBuilder sb = new StringBuilder(8192);

        // === 可配置的execq核心提示词 ===
        String corePrompt = sair.aiagent.core.AiConfig.getInstance().getExecqPrompt();
        sb.append(corePrompt).append("\n\n");

        // === 预先收集身份信息（用于上下文标注） ===
        java.util.Set<Long> masterQQSet = sair.aiagent.core.AiConfig.getInstance().getMasterQQs();
        long masterQQ = masterQQSet.isEmpty() ? 0L : masterQQSet.iterator().next();
        java.util.Set<Long> adminSet = new java.util.HashSet<>();
        java.util.Set<Long> ownerSet = new java.util.HashSet<>();
        java.util.Map<Long, String> roleCache = new java.util.LinkedHashMap<>();
        if (msg.isGroupMessage() && unifiedMemory != null) {
            java.util.List<String[]> admins = unifiedMemory.getGroupAdmins(msg.getGroupId());
            for (String[] a : admins) {
                long aid = Long.parseLong(a[0]);
                roleCache.put(aid, a[2]); // "owner" or "admin"
                if ("owner".equals(a[2])) ownerSet.add(aid);
                else adminSet.add(aid);
            }
        }

        // === QQ上下文信息 ===
        sb.append("## 当前上下文\n");
        if (msg.isGroupMessage()) {
            // 获取群名（优先缓存，无缓存则异步拉取）
            String groupName = unifiedMemory.getGroupName(msg.getGroupId());
            if (groupName == null && napcatApi != null) {
                final long gid = msg.getGroupId();
                execPool.submit(() -> {
                    try {
                        String info = napcatApi.getGroupInfo(gid, false);
                        String name = sair.aiagent.onebot.util.JsonUtil.extractString(info, "group_name");
                        if (name != null && !name.isEmpty()) {
                            unifiedMemory.setGroupName(gid, name);
                            AiAgentActivity.debugLog("[QQMsg] 已缓存群名: " + gid + " -> " + name);
                        }
                    } catch (Exception ignored) {}
                });
            }
            // 当前发送者角色
            String senderRole = (msg.getSender() != null && msg.getSender().role != null) 
                ? msg.getSender().role : "member";
            String roleLabel = "owner".equals(senderRole) ? "群主" :
                               "admin".equals(senderRole) ? "管理员" : "成员";
            
            sb.append("群: ").append(groupName != null ? groupName + "(" + msg.getGroupId() + ")" : msg.getGroupId());
            sb.append(" | 说话者: ").append(msg.getDisplayName());
            sb.append("(QQ:").append(msg.getUserId()).append(")");
            sb.append(" | 身份: ").append(roleLabel);
        } else {
            sb.append("私聊 | QQ:").append(msg.getUserId());
            sb.append(" | ").append(msg.getDisplayName());
        }
        
        // 添加AI名字信息
        String botName = sair.aiagent.core.AiConfig.getInstance().getBotName();
        if (botName != null && !botName.isEmpty()) {
            sb.append(" | 你的名字:").append(botName);
        }
        
        // 主人标记
        boolean isSpeakerMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(msg.getUserId());
        if (isSpeakerMaster) {
            sb.append(" | ⭐此人是主人");
        }
        sb.append("\n");
        
        // === 当前消息@提及（v2.1 新增） ===
        if (msg.isGroupMessage() && !msg.getMentionedUsers().isEmpty()) {
            sb.append("⚠ 此消息@了以下用户: ");
            int mc = 0;
            for (Long mu : msg.getMentionedUsers()) {
                if (mc > 0) sb.append(", ");
                String tagName = resolveUserName(mu, msg.getGroupId(), unifiedMemory);
                sb.append(tagName).append("(QQ:").append(mu).append(")");
                if (mu == masterQQ) sb.append("⭐");
                if (ownerSet.contains(mu)) sb.append("👑");
                else if (adminSet.contains(mu)) sb.append("🔧");
                mc++;
            }
            sb.append("\n");
        }
        sb.append("\n");
        
        // === 图片信息 ===
        if (msg.hasImage() && !msg.getImageUrls().isEmpty()) {
            sb.append("📷 此消息包含 ").append(msg.getImageUrls().size()).append(" 张图片\n");
            for (int i = 0; i < msg.getImageUrls().size() && i < 3; i++) {
                sb.append("  图片URL: ").append(msg.getImageUrls().get(i)).append("\n");
            }
            sb.append("你可以分析图片内容。用户可能在图中包含文字、表情等信息。\n");
            sb.append("如果需要发送图片，使用 <sendimage>描述</sendimage> 标签。\n\n");
        }
        
        // === 折叠消息内容 ===
        if (msg.hasForward()) {
            if (msg.getForwardContent() != null && !msg.getForwardContent().isEmpty()) {
                sb.append("📨 此消息包含转发/折叠消息，以下是其中的内容:\n");
                sb.append(msg.getForwardContent()).append("\n");
                sb.append("请认真分析折叠消息中的每一条内容并做出回复。\n\n");
            } else {
                sb.append("📨 此消息包含转发/折叠消息，但详细内容暂未获取到。\n");
                sb.append("请告知用户你暂时无法查看折叠消息的具体内容。\n\n");
            }
        }
        
        // === 引用消息内容 ===
        if (msg.getQuotedMessageContent() != null && !msg.getQuotedMessageContent().isEmpty()) {
            sb.append("💬 用户回复了以下消息:\n").append(msg.getQuotedMessageContent()).append("\n");
            sb.append("请结合被引用的消息理解上下文，做出针对性回复。\n\n");
        }
        
        // === 群昵称映射（群聊时） ===
        if (msg.isGroupMessage() && unifiedMemory != null) {
            java.util.Map<String, Long> nickMap = unifiedMemory.getGroupNicknameMap(msg.getGroupId());
            if (!nickMap.isEmpty()) {
                sb.append("## 群昵称→QQ映射(⭐主人👑群主🔧管理,无图标=普通成员群昵称)\n");
                int nc = 0;
                for (java.util.Map.Entry<String, Long> e : nickMap.entrySet()) {
                    if (nc >= 20) break;
                    sb.append(e.getKey()).append("→").append(e.getValue());
                    // 标注身份
                    if (e.getValue() == masterQQ) sb.append("⭐");
                    if (ownerSet.contains(e.getValue())) sb.append("👑");
                    else if (adminSet.contains(e.getValue())) sb.append("🔧");
                    sb.append(" ");
                    nc++;
                }
                sb.append("\n");
            }
            
            // === 群管理员/群主 ===
            java.util.List<String[]> admins = unifiedMemory.getGroupAdmins(msg.getGroupId());
            if (!admins.isEmpty()) {
                sb.append("## 群管理员/群主\n");
                StringBuilder ownerLine = new StringBuilder();
                StringBuilder adminLine = new StringBuilder();
                for (String[] a : admins) {
                    long aid = Long.parseLong(a[0]);
                    String label = a[1] + "(QQ:" + a[0] + ")";
                    if (aid == masterQQ) label += "⭐";
                    if ("owner".equals(a[2])) {
                        if (ownerLine.length() > 0) ownerLine.append(", ");
                        ownerLine.append(label);
                    } else {
                        if (adminLine.length() > 0) adminLine.append(", ");
                        adminLine.append(label);
                    }
                }
                if (ownerLine.length() > 0) sb.append("群主: ").append(ownerLine).append("\n");
                if (adminLine.length() > 0) sb.append("管理员: ").append(adminLine).append("\n");
                sb.append("若@管理员或@群主，从上表选。多管理随机@1-2个即可\n\n");
            }
        }
        
        // === 个人昵称→QQ映射（跨群/私聊） ===
        if (unifiedMemory != null) {
            java.util.Map<String, Long> personalMap = unifiedMemory.getPersonalNicknameMap();
            if (!personalMap.isEmpty()) {
                sb.append("## 个人昵称→QQ映射(跨群,⭐主人👑群主🔧管理,其余=昵称)\n");
                int pc = 0;
                for (java.util.Map.Entry<String, Long> e : personalMap.entrySet()) {
                    if (pc >= 15) break;
                    sb.append(e.getKey()).append("→").append(e.getValue());
                    if (e.getValue() == masterQQ) sb.append("⭐");
                    if (ownerSet.contains(e.getValue())) sb.append("👑");
                    else if (adminSet.contains(e.getValue())) sb.append("🔧");
                    sb.append(" ");
                    pc++;
                }
                sb.append("\n");
            }
        }
        
        // === 对话历史（v2.1: 扩容+身份标注） ===
        if (msg.isGroupMessage()) {
            // 群聊：临时读取最近50条消息作为上下文（30→50）
            List<String[]> groupHistory = unifiedMemory.getRecentGroupChatHistory(msg.getGroupId(), 50);
            if (!groupHistory.isEmpty()) {
                sb.append("## 临时群上下文(近50条,⭐主人👑群主🔧管理,其余=群昵称)\n");
                for (String[] h : groupHistory) {
                    long hUid = Long.parseLong(h[0]);
                    String hName = h[1];
                    String content = h[2];
                    if (content.length() > 200) content = content.substring(0, 200) + "...";
                    // 身份标注
                    StringBuilder prefix = new StringBuilder();
                    if (hUid == masterQQ) prefix.append("⭐");
                    if (ownerSet.contains(hUid)) prefix.append("👑");
                    else if (adminSet.contains(hUid)) prefix.append("🔧");
                    if (prefix.length() > 0) prefix.append(" ");
                    sb.append(prefix).append(hName).append(": ").append(content).append("\n");
                }
                sb.append("\n");
            }
            
            // 与该用户的个人对话历史（10→15）
            List<String[]> personalConvs = unifiedMemory.getPrivateConversations(msg.getUserId(), 15);
            if (!personalConvs.isEmpty()) {
                sb.append("## 与该用户的历史\n");
                for (String[] c : personalConvs) {
                    String roleLabel = "user".equals(c[0]) ? msg.getDisplayName() : "You";
                    String content = c[1];
                    if (content.length() > 300) content = content.substring(0, 300) + "...";
                    sb.append(roleLabel).append(": ").append(content).append("\n");
                }
                sb.append("\n");
            }
            
            // 全局最近活跃（20→30）
            List<String[]> globalConvs = unifiedMemory.getGlobalRecentConversations(30);
            if (!globalConvs.isEmpty()) {
                sb.append("## 全局最近活跃\n");
                int count = 0;
                for (String[] c : globalConvs) {
                    if (count >= 15) break;
                    String sourceType = c[2];
                    String sourceId = c[3];
                    String senderName = c[5] != null ? c[5] : "?";
                    String senderIdStr = c[4];
                    String content = c[1];
                    if (content.length() > 150) content = content.substring(0, 150) + "...";
                    
                    // 身份标注
                    StringBuilder prefix = new StringBuilder();
                    if (senderIdStr != null) {
                        try {
                            long sid = Long.parseLong(senderIdStr);
                            if (sid == masterQQ) prefix.append("⭐");
                            if (ownerSet.contains(sid)) prefix.append("👑");
                            else if (adminSet.contains(sid)) prefix.append("🔧");
                        } catch (NumberFormatException ignored) {}
                    }
                    if (prefix.length() > 0) prefix.append(" ");
                    
                    String ctx = "group".equals(sourceType) ? 
                        (sourceId.equals(String.valueOf(msg.getGroupId())) ? "[本群]" : "[群"+sourceId+"]") : "[私]";
                    sb.append(ctx).append(prefix).append(senderName).append(": ").append(content).append("\n");
                    count++;
                }
                sb.append("\n");
            }
        } else {
            // 私聊：显示与该用户的对话历史（20→25）
            List<String[]> convs = unifiedMemory.getPrivateConversations(msg.getUserId(), 25);
            if (!convs.isEmpty()) {
                sb.append("## 与该用户的历史\n");
                for (String[] c : convs) {
                    String roleLabel = "user".equals(c[0]) ? "User" : "Assistant";
                    String content = c[1];
                    if (content.length() > 300) content = content.substring(0, 300) + "...";
                    sb.append(roleLabel).append(": ").append(content).append("\n");
                }
                sb.append("\n");
            }
            
            // 全局最近活跃（15→20）
            List<String[]> globalConvs = unifiedMemory.getGlobalRecentConversations(20);
            if (!globalConvs.isEmpty()) {
                sb.append("## 全局最近活跃\n");
                int count = 0;
                for (String[] c : globalConvs) {
                    if (count >= 10) break;
                    String sourceId = c[3];
                    String senderName = c[5] != null ? c[5] : "?";
                    String content = c[1];
                    if (content.length() > 150) content = content.substring(0, 150) + "...";
                    String ctx = "group".equals(c[2]) ? "[群"+sourceId+"]" : "[私]";
                    sb.append(ctx).append(senderName).append(": ").append(content).append("\n");
                    count++;
                }
                sb.append("\n");
            }
        }

        // === 相关记忆（已禁用） ===

        // === Bot mood (enhanced: tone + relationship + emotional memory) ===
        if (emotionManager != null) {
            String emotionCtx = emotionManager.buildRichEmotionContext(msg.getUserId());
            sb.append(emotionCtx);
        }

        // === 违规处罚记录 ===
        if (punishmentRecords != null && !punishmentRecords.isEmpty()) {
            sb.append("## 违规处罚\n");
            for (String record : punishmentRecords) {
                sb.append("- ").append(record).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    /** 解析用户显示名（优先群昵称缓存→回退QQ号） */
    private String resolveUserName(long qq, long groupId, UnifiedQQMemoryManager mem) {
        if (mem != null) {
            java.util.Map<String, Long> nickMap = mem.getGroupNicknameMap(groupId);
            for (java.util.Map.Entry<String, Long> e : nickMap.entrySet()) {
                if (e.getValue() == qq) return e.getKey();
            }
        }
        return String.valueOf(qq);
    }

    /** 构建任务描述 */
    private String buildTaskDescription(QQMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append(msg.getDisplayName());
        sb.append("(QQ:").append(msg.getUserId()).append(")");
        sb.append(msg.isGroupMessage() ? " 群聊: " : " 私聊: ");
        sb.append(msg.getPlainText());
        
        // 图片信息
        if (msg.hasImage() && !msg.getImageUrls().isEmpty()) {
            sb.append("\n📷 此消息包含 ").append(msg.getImageUrls().size()).append(" 张图片");
            for (int i = 0; i < msg.getImageUrls().size(); i++) {
                sb.append("\n  图片URL: ").append(msg.getImageUrls().get(i));
            }
        }
        
        
        
        // 折叠/转发消息内容（显式追加到用户消息，确保 AI 看到）
        if (msg.hasForward() && msg.getForwardContent() != null && !msg.getForwardContent().isEmpty()) {
            sb.append("\n" + "📨" + " 转发/折叠消息内容:\n").append(msg.getForwardContent());
        }
        
        // 引用消息（如果已获取）
        if (msg.getQuotedMessageContent() != null && !msg.getQuotedMessageContent().isEmpty()) {
            sb.append("\n💬 被引用的消息: ").append(msg.getQuotedMessageContent());
        }
        
        return sb.toString();
    }

    // ==================== 记忆管理器 ====================

    /** 关闭统一记忆管理器 */
    public void shutdown() {
        // 禁用主动查看
        proactiveCheckEnabled = false;
        
        // 关闭线程池
        execPool.shutdown();
        scheduledPool.shutdownNow();
        
        // 关闭统一记忆管理器
        if (unifiedMemory != null) {
            unifiedMemory.close();
        }
        
        AiAgentActivity.debugLog("[QQMsg] 已关闭");
    }

    // ==================== 工具 ====================

    /** 获取Bot标签名（优先用配置名，无配置则用"Bot"） */
    private String getBotLabel() {
        String name = sair.aiagent.core.AiConfig.getInstance().getBotName();
        return (name != null && !name.isEmpty()) ? name : "Bot";
    }

    // ==================== JSON工具方法（无第三方依赖） ====================

    /** 从JSON中提取字符串值 */
    static String extractString(String json, String key) {
        return JsonUtil.extractString(json, key);
    }

    /** 从JSON中提取long值 */
    static long extractLong(String json, String key) {
        return JsonUtil.extractLong(json, key);
    }

    /** 从JSON中提取对象 {} 内容 */
    static String extractObject(String json, String key) {
        return JsonUtil.extractObject(json, key);
    }

    /** 从JSON中提取数组 [] 内容 */
    static String extractArray(String json, String key) {
        return JsonUtil.extractArray(json, key);
    }

    /** 简单拆分JSON数组中的对象 */
    static List<String> splitJsonArray(String arrayStr) {
        return JsonUtil.splitJsonArray(arrayStr);
    }
}