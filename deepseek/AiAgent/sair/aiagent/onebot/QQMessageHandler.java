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

/**
 * QQ消息处理器 —— 消息解析、过滤、路由到AI Agent。
 *
 * <h3>处理规则</h3>
 * <ul>
 *   <li><b>群消息</b>：仅响应@机器人 或 引用回复，其余忽略</li>
 *   <li><b>私聊消息</b>：全部响应</li>
 *   <li>execq 通道只开放：cmd / web / readdir 三个标签</li>
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

    // ==================== 配置 ====================

    public void setSelfId(long selfId) { this.selfId = selfId; }
    public long getSelfId() { return selfId; }

    public void setServer(OneBotServer server) { this.server = server; }
    public void setAgentExecutor(AgentExecutor executor) { this.agentExecutor = executor; }
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

                // 构建分析提示词
                StringBuilder analysisPrompt = new StringBuilder();
                analysisPrompt.append("你是群聊助手。请分析以下群聊消息，如果有需要回应的话题，请生成回复。\n\n");
                
                // 添加AI名字信息
                String botName = sair.aiagent.core.AiConfig.getInstance().getBotName();
                if (botName != null && !botName.isEmpty()) {
                    analysisPrompt.append("你的名字是: " + botName + "\n");
                    analysisPrompt.append("如果群友提到了你的名字，你应该参与对话。\n\n");
                }
                
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
            }
            segments.add(seg);
        }
        return segments;
    }

    /** 检测是否@了机器人、是否引用回复 */
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
        
        AiAgentActivity.debugLog("[QQMsg] @检测结果: isAtBot=" + msg.isAtBot() + ", isReply=" + msg.isReply());
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
                // 1. 记录到群聊完整历史（持久化）
                unifiedMemory.addGroupChatMessage(
                    msg.getUserId(),
                    msg.getDisplayName(),
                    msg.getPlainText(),
                    msg.getGroupId()
                );
                
                // 2. 如果消息触发了AI响应，也记录到统一对话历史
                boolean shouldRespond = msg.isAtBot() || msg.isReply();
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
                    // 记录到统一对话历史，标记为群聊来源
                    unifiedMemory.addConversation(
                        "user",
                        msg.getPlainText(),
                        "group",
                        msg.getGroupId(),
                        msg.getUserId(),
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

            // 群消息过滤：仅响应@或引用或提到名字
            if (msg.isGroupMessage()) {
                boolean shouldRespond = msg.isAtBot() || msg.isReply();
                
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
                    AiAgentActivity.debugLog("[QQMsg] 群消息已忽略(未@或引用或提到名字): " + msg.getRawMessage());
                    processingMessages.remove(msgId);
                    return;
                }
            }

            // 获取发送者QQ号用于记忆隔离
            long qq = msg.getUserId();
            
            // 注意：用户消息已在上面的if-else块中记录到unifiedMemory，这里不需要再次记录
            AiAgentActivity.debugLog("[QQMsg] 准备执行AI: " + (msg.isGroupMessage() ? "群聊" : "私聊") + ", userId=" + qq);

            // 构建execq系统提示词
            String systemPrompt = buildExecqSystemPrompt(msg, null);
            AiAgentActivity.debugLog("[QQMsg] 系统提示词构建完成，长度: " + systemPrompt.length());

            // 提交到线程池执行（防重复标记在异步任务完成后才移除）
            execPool.submit(() -> {
                try {
                    AiAgentActivity.debugLog("[QQMsg] 开始执行AI代理...");
                    executeQqAgent(msg, unifiedMemory, systemPrompt, responseSender);
                    AiAgentActivity.debugLog("[QQMsg] AI代理执行完成");
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[QQMsg] 执行错误: " + e.toString());
                    // 打印完整堆栈
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
                try {
                    AiAgentActivity.debugLog("[QQMsg-Execs] execs线程启动，开始执行任务");
                    
                    // 发送开始通知
                    sendReply(msg, "\n[椰羊execs] 🚀 开始执行任务...\n任务: " + (task.length() > 50 ? task.substring(0, 50) + "..." : task));
                    
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
                    sendReply(msg, "\n[椰羊execs] ✅ 任务执行完成\n详细输出请查看SFW控制台");
                    
                } catch (Exception e) {
                    // 清除回调
                    agentExecutor.setQqExecsCallback(null);
                    
                    AiAgentActivity.debugLog("[QQMsg-Execs] execs执行异常: " + e.toString());
                    sendReply(msg, "\n[椰羊execs] ❌ 任务执行失败: " + e.getMessage() + "\n详细错误请查看SFW控制台");
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

        try {
            // 构建任务描述
            String task = buildTaskDescription(msg);
            
            // 判断是否是主人
            long senderQQ = msg.getUserId();
            boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ);
            
            // 检测是否有execs:前缀
            String plainText = msg.getPlainText();
            boolean useExecs = isMaster && plainText != null && plainText.startsWith("execs:");
            
            AiAgentActivity.debugLog("[QQMsg] 发送者QQ: " + senderQQ + ", 是否主人: " + isMaster + ", 使用execs: " + useExecs);

            String aiResponse;
            if (useExecs) {
                // 主人 + execs:前缀：走真正的execs链路
                AiAgentActivity.debugLog("[QQMsg] 主人execs消息，执行完整execs链路");
                
                // 先发送提示消息给主人
                sendReply(msg, "[椰羊execs] 这条消息将交给另一个身份进行处理...\n正在启动execs链路...");
                
                // 去掉execs:前缀，获取实际任务
                String actualTask = plainText.substring(6).trim();
                
                // 执行真正的execs（与SFW控制台execs完全一样）
                aiResponse = executeRealExecs(msg, actualTask, responseSender);
            } else if (isMaster) {
                // 主人但没有execs:前缀：使用execq模式（受限）
                AiAgentActivity.debugLog("[QQMsg] 主人普通消息，使用execq模式（受限）");
                aiResponse = agentExecutor.executeQq(task, systemPrompt, memoryManager);
            } else {
                // 普通用户：使用execq模式（受限模式，插件白名单）
                AiAgentActivity.debugLog("[QQMsg] 普通用户消息，使用execq模式（受限模式）");
                aiResponse = agentExecutor.executeQq(task, systemPrompt, memoryManager);
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
                        "AI助手"
                    );
                } else {
                    // 私聊：记录到统一历史，标记为私聊来源
                    unifiedMemory.addConversation(
                        "assistant",
                        aiResponse,
                        "private",
                        msg.getUserId(),
                        selfId,
                        "AI助手"
                    );
                }

                // 移除XML标签后发送
                String cleanResponse = aiResponse.replaceAll("<[^>]+>", "").trim();
                if (!cleanResponse.isEmpty()) {
                    // 支持多消息发送
                    List<String> messages = splitIntoMessages(cleanResponse);
                    sendMultipleReplies(msg, messages);
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
    private String buildExecqSystemPrompt(QQMessage msg, QQMemoryManager memMgr) {
        StringBuilder sb = new StringBuilder(4096);

        // === 可配置的execq核心提示词 ===
        String corePrompt = sair.aiagent.core.AiConfig.getInstance().getExecqPrompt();
        sb.append(corePrompt).append("\n\n");

        // === QQ上下文信息 ===
        sb.append("## QQ Chat Context\n");
        sb.append("你正在通过QQ与用户聊天。当前是");
        if (msg.isGroupMessage()) {
            sb.append("群聊，群号: ").append(msg.getGroupId());
        } else {
            sb.append("私聊");
        }
        sb.append("\n");
        sb.append("- 用户QQ: ").append(msg.getUserId()).append("\n");
        sb.append("- 用户昵称: ").append(msg.getDisplayName()).append("\n");
        
        // 添加AI名字信息
        String botName = sair.aiagent.core.AiConfig.getInstance().getBotName();
        if (botName != null && !botName.isEmpty()) {
            sb.append("- 你的名字: ").append(botName).append("\n");
            sb.append("  如果用户在消息中提到你的名字，你应该回应。\n");
        }
        sb.append("\n");

        // === 对话历史 ===
        if (msg.isGroupMessage()) {
            // 群聊：显示最近的群聊上下文
            List<String[]> groupHistory = unifiedMemory.getRecentGroupChatHistory(msg.getGroupId(), 50);
            if (!groupHistory.isEmpty()) {
                sb.append("## Recent Group Chat Context\n");
                sb.append("以下是最近的群聊消息，请根据上下文理解当前对话：\n");
                for (String[] h : groupHistory) {
                    String nickname = h[1];
                    String content = h[2];
                    if (content.length() > 200) content = content.substring(0, 200) + "...";
                    sb.append(nickname).append(": ").append(content).append("\n");
                }
                sb.append("\n");
            }
            
            // 同时显示与该用户的个人对话历史（保证记忆连贯性）
            List<String[]> personalConvs = unifiedMemory.getPrivateConversations(msg.getUserId(), 10);
            if (!personalConvs.isEmpty()) {
                sb.append("## Your Previous Conversations with this User\n");
                sb.append("以下是你与 ").append(msg.getDisplayName()).append(" 之前的对话历史：\n");
                for (String[] c : personalConvs) {
                    String roleLabel = "user".equals(c[0]) ? msg.getDisplayName() : "You";
                    String content = c[1];
                    if (content.length() > 300) content = content.substring(0, 300) + "...";
                    sb.append(roleLabel).append(": ").append(content).append("\n");
                }
                sb.append("\n");
            }
            
            // 显示AI的全局最近对话（让AI了解自己的整体状态）
            List<String[]> globalConvs = unifiedMemory.getGlobalRecentConversations(20);
            if (!globalConvs.isEmpty()) {
                sb.append("## Your Recent Global Activity\n");
                sb.append("以下是你最近的活跃记录，帮助你保持记忆的连贯性：\n");
                int count = 0;
                for (String[] c : globalConvs) {
                    if (count >= 10) break; // 只显示10条
                    String sourceType = c[2];
                    String sourceId = c[3];
                    String senderName = c[5] != null ? c[5] : "未知";
                    String content = c[1];
                    if (content.length() > 150) content = content.substring(0, 150) + "...";
                    
                    String context = "group".equals(sourceType) ? 
                        "[群:" + sourceId + "] " + senderName : 
                        "[私聊] " + senderName;
                    sb.append(context).append(": ").append(content).append("\n");
                    count++;
                }
                sb.append("\n");
            }
        } else {
            // 私聊：显示与该用户的对话历史
            List<String[]> convs = unifiedMemory.getPrivateConversations(msg.getUserId(), 20);
            if (!convs.isEmpty()) {
                sb.append("## Recent Conversation with this User\n");
                for (String[] c : convs) {
                    String roleLabel = "user".equals(c[0]) ? "User" : "Assistant";
                    String content = c[1];
                    if (content.length() > 300) content = content.substring(0, 300) + "...";
                    sb.append(roleLabel).append(": ").append(content).append("\n");
                }
                sb.append("\n");
            }
            
            // 显示AI的全局最近对话
            List<String[]> globalConvs = unifiedMemory.getGlobalRecentConversations(15);
            if (!globalConvs.isEmpty()) {
                sb.append("## Your Recent Global Activity\n");
                sb.append("以下是你最近的活跃记录：\n");
                int count = 0;
                for (String[] c : globalConvs) {
                    if (count >= 8) break;
                    String sourceType = c[2];
                    String sourceId = c[3];
                    String senderName = c[5] != null ? c[5] : "未知";
                    String content = c[1];
                    if (content.length() > 150) content = content.substring(0, 150) + "...";
                    
                    String context = "group".equals(sourceType) ? 
                        "[群:" + sourceId + "] " + senderName : 
                        "[私聊] " + senderName;
                    sb.append(context).append(": ").append(content).append("\n");
                    count++;
                }
                sb.append("\n");
            }
        }

        // === 相关记忆（已禁用，使用统一记忆管理器） ===
        // 注意：memMgr可能为null，因为我们现在使用UnifiedQQMemoryManager
        // if (memMgr != null) {
        //     List<String> memories = memMgr.searchMemories(msg.getPlainText(), 3);
        //     if (!memories.isEmpty()) {
        //         sb.append("## Related Memories about this User\n");
        //         for (String m : memories) {
        //             sb.append("- ").append(m).append("\n");
        //         }
        //         sb.append("\n");
        //     }
        // }

        return sb.toString();
    }

    /** 构建任务描述 */
    private String buildTaskDescription(QQMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append("QQ用户 ").append(msg.getDisplayName());
        sb.append("（QQ:").append(msg.getUserId()).append("）");
        if (msg.isGroupMessage()) {
            sb.append(" 在群聊中说：");
        } else {
            sb.append(" 私聊你说：");
        }
        sb.append(msg.getPlainText());
        return sb.toString();
    }

    // ==================== 记忆管理器 ====================

    /** 获取或创建指定QQ号的记忆管理器 */
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

    // ==================== JSON工具方法（无第三方依赖） ====================

    /** 从JSON中提取字符串值 */
    static String extractString(String json, String key) {
        // 匹配 "key":"value" 或 "key": "value"
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + searchKey.length());
        if (colonIdx < 0) return null;

        // 跳过冒号后的空白
        int valStart = colonIdx + 1;
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\t')) {
            valStart++;
        }

        if (valStart >= json.length()) return null;

        char first = json.charAt(valStart);
        if (first != '"') {
            // 非字符串值（数字/布尔/null），读取到逗号或}为止
            int valEnd = valStart;
            while (valEnd < json.length() && json.charAt(valEnd) != ',' && json.charAt(valEnd) != '}') {
                valEnd++;
            }
            String raw = json.substring(valStart, valEnd).trim();
            return "null".equals(raw) ? null : raw;
        }

        // 字符串值，处理转义
        StringBuilder sb = new StringBuilder();
        int i = valStart + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case '/': sb.append('/'); i += 2; continue;
                    case 'n': sb.append('\n'); i += 2; continue;
                    case 'r': sb.append('\r'); i += 2; continue;
                    case 't': sb.append('\t'); i += 2; continue;
                    case 'u':
                        if (i + 5 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                                i += 6; continue;
                            } catch (NumberFormatException ignored) {}
                        }
                        sb.append(c); i++; continue;
                    default: sb.append(c); i++; continue;
                }
            } else if (c == '"') {
                break;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 从JSON中提取long值 */
    static long extractLong(String json, String key) {
        String s = extractString(json, key);
        if (s == null || s.isEmpty()) return 0;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 从JSON中提取对象 {} 内容 */
    static String extractObject(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + searchKey.length());
        if (colonIdx < 0) return null;

        int braceStart = json.indexOf('{', colonIdx + 1);
        if (braceStart < 0) return null;

        int depth = 1;
        int pos = braceStart + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '{') depth++;
            else if (c == '}') depth--;
            else if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') { pos += 2; continue; }
                    if (sc == '"') break;
                    pos++;
                }
            }
            pos++;
        }
        return json.substring(braceStart, pos);
    }

    /** 从JSON中提取数组 [] 内容 */
    static String extractArray(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;

        int colonIdx = json.indexOf(':', idx + searchKey.length());
        if (colonIdx < 0) return null;

        int bracketStart = json.indexOf('[', colonIdx + 1);
        if (bracketStart < 0) return null;

        int depth = 1;
        int pos = bracketStart + 1;
        while (pos < json.length() && depth > 0) {
            char c = json.charAt(pos);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            else if (c == '"') {
                pos++;
                while (pos < json.length()) {
                    char sc = json.charAt(pos);
                    if (sc == '\\') { pos += 2; continue; }
                    if (sc == '"') break;
                    pos++;
                }
            }
            pos++;
        }
        return json.substring(bracketStart + 1, pos - 1);
    }

    /** 简单拆分JSON数组中的对象 */
    static List<String> splitJsonArray(String arrayStr) {
        List<String> items = new ArrayList<>();
        if (arrayStr == null || arrayStr.trim().isEmpty()) return items;

        int start = 0;
        int depth = 0;
        boolean inString = false;

        for (int i = 0; i < arrayStr.length(); i++) {
            char c = arrayStr.charAt(i);
            if (inString) {
                if (c == '\\') { i++; continue; }
                if (c == '"') inString = false;
            } else {
                if (c == '"') inString = true;
                else if (c == '{') {
                    if (depth == 0) start = i;
                    depth++;
                }
                else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        items.add(arrayStr.substring(start, i + 1));
                    }
                }
            }
        }
        return items;
    }
}
