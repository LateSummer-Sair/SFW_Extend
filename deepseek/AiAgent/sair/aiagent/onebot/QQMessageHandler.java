package sair.aiagent.onebot;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.core.AgentExecutor;
import sair.aiagent.model.StickerEntry;
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
public class QQMessageHandler implements ListeningStateManager.TaskHandler {

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

    /** execq 消息处理线程池：有界队列 + 拒绝降级（CallerRuns），线程数可经系统属性 aiagent.execq.poolSize 配置。 */
    private static final int EXECQ_POOL_SIZE = resolveExecqPoolSize();
    private final ExecutorService execPool = new ThreadPoolExecutor(
            EXECQ_POOL_SIZE, Math.max(EXECQ_POOL_SIZE, 6), 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(256),
            r -> { Thread t = new Thread(r, "OneBot-ExecQ"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    /** 解析 execq 线程池大小（系统属性 aiagent.execq.poolSize，默认 3，限幅 1..32）。 */
    private static int resolveExecqPoolSize() {
        String v = System.getProperty("aiagent.execq.poolSize");
        if (v != null && !v.trim().isEmpty()) {
            try { return Math.max(1, Math.min(32, Integer.parseInt(v.trim()))); }
            catch (NumberFormatException ignored) {}
        }
        return 3;
    }

    /** 拟人化监听态状态管理器 */
    private ListeningStateManager listeningState;

    /** 是否启用拟人化监听态（默认关闭；需手动开启，关闭时回到 execPool 并发处理消息） */
    private volatile boolean listenStateEnabled = false;

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

    /** 待处理请求池（好友申请/群邀请交由 AI 决策） */
    private final PendingRequestPool pendingRequestPool = new PendingRequestPool();

    /** execs转发处理器 */
    private ExecsForwardHandler execsForwardHandler;
    /** 提示词构建器 */
    private QQPromptBuilder promptBuilder;
    /** Agent桥接器 */
    private QQAgentBridge agentBridge;

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
            
            AiAgentActivity.qqLog("[QQMsg] 所有组件初始化完成: EmotionStateManager, NapCatApi, BotPersistenceManager, InternalAgents, GroupModeratorAgent");
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

    /** 获取待处理请求池 */
    public PendingRequestPool getPendingRequestPool() { return pendingRequestPool; }
    
    /** 获取统一记忆管理器（戳一戳上下文分析用） */
    public UnifiedQQMemoryManager getUnifiedMemory() { return unifiedMemory; }
    
    /** 获取Bot名称（戳一戳上下文分析用） */
    public String getSelfName() { return getBotLabel(); }
    
    /** 获取DeepSeekClient（AI戳一戳回复用） */
    public sair.aiagent.core.DeepSeekClient getDeepSeekClient() {
        return agentExecutor != null ? agentExecutor.getDeepSeekClient() : null;
    }
    
    /** 根据QQ号获取显示名称（戳一戳上下文用） */
    public String getDisplayNameForQQ(long qq) {
        return qq > 0 ? String.valueOf(qq) : "unknown";
    }
    
    public void setAgentExecutor(AgentExecutor executor) {
        this.agentExecutor = executor;
        initHandlers();
        initListeningState();
    }

    /** 初始化拟人化监听态管理器（在 agentExecutor 与 unifiedMemory 就绪后） */
    private void initListeningState() {
        if (listeningState == null && agentExecutor != null && unifiedMemory != null) {
            listeningState = new ListeningStateManager(this);
            listeningState.setEmotionManager(emotionManager);
            AiAgentActivity.qqLog("[QQMsg] 拟人化监听态管理器已初始化");
        }
    }

    /** 获取监听态管理器（供外部/好感度主动监听使用） */
    public ListeningStateManager getListeningStateManager() { return listeningState; }

    /** 启用拟人化监听态 */
    public void enableListeningState() { this.listenStateEnabled = true; }
    /** 禁用拟人化监听态（回到 execPool 并发处理） */
    public void disableListeningState() { this.listenStateEnabled = false; }
    /** 查询监听态开关状态 */
    public boolean isListeningStateEnabled() { return listenStateEnabled; }
    
    /** 初始化拆分出的处理器 */
    private void initHandlers() {
        if (execsForwardHandler == null && agentExecutor != null && napcatApi != null) {
            execsForwardHandler = new ExecsForwardHandler(agentExecutor, napcatApi, server, selfId, getBotLabel());
        }
        if (promptBuilder == null && unifiedMemory != null) {
            promptBuilder = new QQPromptBuilder(unifiedMemory, napcatApi, execPool);
            promptBuilder.setEmotionManager(emotionManager);
            promptBuilder.setAgentExecutor(agentExecutor);
        }
        if (agentBridge == null && agentExecutor != null && unifiedMemory != null) {
            agentBridge = new QQAgentBridge(agentExecutor, unifiedMemory);
            agentBridge.setServer(server);
            agentBridge.setExecsForwardHandler(execsForwardHandler);
            agentBridge.setSelfId(selfId);
            agentBridge.setNapcatApi(napcatApi);
            agentBridge.setInternalAgents(internalAgents);
            agentBridge.setDataDir(dataDir);
            agentBridge.setEmotionManager(emotionManager);
            agentBridge.setPendingRequestPool(pendingRequestPool);
        }
    }

    /** Bridge core EmotionManager to QQ EmotionStateManager */
    public void bridgeEmotionManagers() {
        if (agentExecutor != null && emotionManager != null) {
            sair.aiagent.core.EmotionManager coreEm = agentExecutor.getEmotionManager();
            if (coreEm != null) {
                emotionManager.setCoreEmotionManager(coreEm);
                sair.aiagent.AiAgentActivity.qqLog("[QQMsg] Emotion bridge established");
            }
        }
    }
    public void setDataDir(String dir) { 
        this.dataDir = dir;
        AiAgentActivity.qqLog("[QQMsg] setDataDir被调用: dataDir=" + dir);
        // 初始化统一记忆管理器
        if (unifiedMemory == null && dir != null) {
            AiAgentActivity.qqLog("[QQMsg] 正在初始化UnifiedQQMemoryManager...");
            unifiedMemory = new UnifiedQQMemoryManager(dir);
            unifiedMemory.init();
            AiAgentActivity.qqLog("[QQMsg] UnifiedQQMemoryManager初始化完成");
        } else if (dir == null) {
            AiAgentActivity.qqLog("[QQMsg] 警告: setDataDir传入null，无法初始化unifiedMemory");
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
                    AiAgentActivity.qqLog("[QQMsg] 主动查看错误: " + e.toString());
                }
            }
        });
        
        AiAgentActivity.qqLog("[QQMsg] 主动查看功能已启用");
    }

    /** 禁用主动查看功能 */
    public void disableProactiveCheck() {
        proactiveCheckEnabled = false;
        AiAgentActivity.qqLog("[QQMsg] 主动查看功能已禁用");
    }

    /** 添加要监听的群号 */
    public void addMonitoredGroup(long groupId) {
        monitoredGroups.add(groupId);
        AiAgentActivity.qqLog("[QQMsg] 添加监听群: " + groupId);
    }

    /** 移除监听的群号 */
    public void removeMonitoredGroup(long groupId) {
        monitoredGroups.remove(groupId);
        AiAgentActivity.qqLog("[QQMsg] 移除监听群: " + groupId);
    }

    /** 判断群号是否已设为监听群 */
    public boolean isMonitoredGroup(long groupId) {
        return groupId > 0 && monitoredGroups.contains(groupId);
    }

    /** 执行主动查看 */
    private void performProactiveCheck() {
        if (monitoredGroups.isEmpty()) {
            AiAgentActivity.qqLog("[QQMsg] 没有监听的群，跳过主动查看");
            return;
        }

        for (long groupId : monitoredGroups) {
            try {
                // 获取该群的最近群聊消息
                List<String[]> recentMessages = unifiedMemory.getRecentGroupChatHistory(groupId, 20);
                if (recentMessages.isEmpty()) {
                    AiAgentActivity.qqLog("[QQMsg] 群 " + groupId + " 没有新消息");
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
                        sair.aiagent.core.ToolContext ctx = new sair.aiagent.core.ToolContext("execq");
                        ctx.napcatApi = napcatApi;
                        ctx.unifiedMemory = unifiedMemory;
                        ctx.isMaster = false;
                        String response = agentExecutor.executeFcExecq(
                            "分析群聊并生成回复",
                            analysisPrompt.toString(),
                            ctx,
                            sair.aiagent.core.AiConfig.getInstance().getExecqModel()
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
                                    AiAgentActivity.qqLog("[QQMsg] 主动发送" + messages.size() + "条消息到群 " + finalGroupId);
                                }
                            }
                        }
                    } catch (Exception e) {
                        AiAgentActivity.qqLog("[QQMsg] 主动查看执行错误: " + e.toString());
                    }
                });
                
            } catch (Exception e) {
                AiAgentActivity.qqLog("[QQMsg] 主动查看群 " + groupId + " 失败: " + e.toString());
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
            // AiAgentActivity.qqLog("[QQMsg] 忽略非消息事件");
            return;
        }
        AiAgentActivity.qqLog("[QQMsg] 收到消息: type=" + msg.getMessageType() + ", userId=" + msg.getUserId());
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
            AiAgentActivity.qqLog("[QQMsg] 解析错误: " + e.getMessage());
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
            } else if ("mface".equals(seg.type) || "sticker".equals(seg.type)) {
                String dataObj = extractObject(item, "data");
                if (dataObj != null) {
                    seg.url = extractString(dataObj, "url");
                    if (seg.url == null || seg.url.isEmpty()) {
                        seg.url = extractString(dataObj, "file");
                    }
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
        AiAgentActivity.qqLog("[QQMsg] @检测: selfId=" + selfId + ", segments=" + (msg.getSegments() != null ? msg.getSegments().size() : "null"));
        
        if (msg.getSegments() == null) {
            // 降级：从raw_message检查
            String raw = msg.getRawMessage();
            if (raw != null) {
                AiAgentActivity.qqLog("[QQMsg] 使用raw_message检测@: " + raw.substring(0, Math.min(100, raw.length())));
                if (selfId > 0 && raw.contains("[CQ:at,qq=" + selfId)) {
                    msg.setAtBot(true);
                    AiAgentActivity.qqLog("[QQMsg] 检测到@机器人 (raw): " + selfId);
                }
                if (raw.contains("[CQ:at,qq=all")) {
                    msg.setAtBot(true);
                    AiAgentActivity.qqLog("[QQMsg] 检测到@all");
                }
                if (raw.startsWith("[CQ:reply")) {
                    msg.setReply(true);
                    AiAgentActivity.qqLog("[QQMsg] 检测到回复消息");
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
                                AiAgentActivity.qqLog("[QQMsg] 收集到@提及: qq=" + qq);
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
                AiAgentActivity.qqLog("[QQMsg] 发现@段: qq=" + seg.qq + ", selfId=" + selfId);
                // @all 或 @机器人（需先设置selfId）
                if (seg.qq == 0) {
                    msg.setAtBot(true); // @all
                    AiAgentActivity.qqLog("[QQMsg] 检测到@all");
                } else if (selfId > 0 && seg.qq == selfId) {
                    msg.setAtBot(true);
                    AiAgentActivity.qqLog("[QQMsg] 检测到@机器人: " + selfId);
                } else if (seg.qq > 0 && seg.qq != selfId) {
                    // 收集@提及的其他用户
                    msg.addMentionedUser(seg.qq, "[CQ:at,qq=" + seg.qq + "]");
                    AiAgentActivity.qqLog("[QQMsg] 收集到@提及: qq=" + seg.qq);
                } else if (selfId == 0) {
                    AiAgentActivity.qqLog("[QQMsg] 警告: selfId未设置，无法检测@机器人！请执行 ai/onebotsetselfid <QQ号>");
                }
            }
            if ("reply".equals(seg.type)) {
                msg.setReply(true);
                msg.setReplyMessageId(seg.id);
                AiAgentActivity.qqLog("[QQMsg] 检测到回复消息");
            }
        }
        
        AiAgentActivity.qqLog("[QQMsg] @检测结果: isAtBot=" + msg.isAtBot() + ", isReply=" + msg.isReply() + ", mentionedUsers=" + msg.getMentionedUsers().size());
    }
    
    /** 下载接收到的文件到 fileDownloadPath */
    private void downloadIncomingFile(String fileUrl, String fileName, long senderQQ) {
        // 仅主人发送的文件才自动下载
        if (!sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ)) {
            AiAgentActivity.qqLog("[QQMsg] 非主人发送文件，跳过自动下载: sender=" + senderQQ);
            return;
        }
        try {
            String dlDir = sair.aiagent.core.AiConfig.getInstance().getFileDownloadPath();
            java.io.File dir = new java.io.File(dlDir);
            if (!dir.exists()) dir.mkdirs();
            if (fileName == null || fileName.isEmpty()) fileName = "file_" + System.currentTimeMillis();
            java.io.File outFile = new java.io.File(dir, fileName);
            // 防止覆盖：加时间戳后缀
            if (outFile.exists()) {
                String base = fileName;
                int dot = base.lastIndexOf('.');
                String ext = dot > 0 ? base.substring(dot) : "";
                String stem = dot > 0 ? base.substring(0, dot) : base;
                outFile = new java.io.File(dir, stem + "_" + System.currentTimeMillis() + ext);
            }
            java.net.URL url = new java.net.URL(fileUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            java.io.InputStream is = conn.getInputStream();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            try {
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            } finally {
                try { fos.close(); } catch (Exception ignored) {}
                try { is.close(); } catch (Exception ignored) {}
                conn.disconnect();
            }
            AiAgentActivity.qqLog("[QQMsg] 文件已下载: " + outFile.getAbsolutePath() + " (" + outFile.length() + " bytes)");
        } catch (Exception e) {
            AiAgentActivity.qqLog("[QQMsg] 文件下载失败: " + e.toString());
        }
    }
    
    /** 检测消息中的图片和折叠消息内容 */
    private void detectMediaContent(QQMessage msg) {
        if (msg.getSegments() == null || msg.getSegments().isEmpty()) return;
        
        boolean hasImage = false;
        boolean hasForward = false;
        StringBuilder forwardText = new StringBuilder();
        
        for (QQMessage.MessageSegment seg : msg.getSegments()) {
            if (seg.isFile()) {
                // 主人发送的文件自动下载到 fileDownloadPath
                AiAgentActivity.qqLog("[QQMsg] 检测到文件消息: name=" + seg.fileName + ", url=" + seg.url);
                if (seg.url != null && !seg.url.isEmpty()) {
                    downloadIncomingFile(seg.url, seg.fileName, msg.getUserId());
                }
            }
            if (seg.isImage()) {
                hasImage = true;
                if (seg.url != null && !seg.url.isEmpty()) {
                    msg.addImageUrl(seg.url);
                    if (seg.isStickerImage()) msg.addStickerUrl(seg.url);
                }
                if (seg.file != null && !seg.file.isEmpty()) {
                    msg.addImageFilePath(seg.file);
                }
            }
            if (seg.isMface() || seg.isSticker()) {
                if (seg.url != null && !seg.url.isEmpty()) {
                    msg.addStickerUrl(seg.url);
                }
            }
            if (seg.isForward()) {
                hasForward = true;
                if (seg.content != null && !seg.content.isEmpty()) {
                    // content直接可用（部分实现如NapCat内嵌）
                    String extracted = ForwardMessageExpander.extractForwardText(seg.content);
                    if (extracted != null && !extracted.isEmpty()) {
                        if (forwardText.length() > 0) forwardText.append("\n");
                        forwardText.append(extracted);
                    }
                } else if (seg.forwardId != null && !seg.forwardId.isEmpty()) {
                    // 标准OneBot v11：只有forward_id，需异步API获取
                    msg.setForwardId(seg.forwardId);
                    AiAgentActivity.qqLog("[QQMsg] 检测到折叠消息，forward_id=" + seg.forwardId + "，将异步获取内容");
                }
            }
        }
        
        if (hasImage) {
            msg.setHasImage(true);
            AiAgentActivity.qqLog("[QQMsg] 检测到图片消息: " + msg.getImageUrls().size() + "张");
        }
        if (hasForward) {
            msg.setHasForward(true);
            if (forwardText.length() > 0) {
                String content = forwardText.toString();
                if (content.length() > 1000) content = content.substring(0, 1000) + "...";
                msg.setForwardContent(content);
                AiAgentActivity.qqLog("[QQMsg] 检测到折叠消息，内容长度: " + content.length());
            }
        }
    }
    
    /** @deprecated 委托给 ForwardMessageExpander */
    private String extractForwardMsgContent(String apiResponse) {
        return ForwardMessageExpander.extractForwardMsgContent(apiResponse);
    }

    /** @deprecated 委托给 ForwardMessageExpander */
    private String extractMsgSegmentsText(String apiResponse) {
        return ForwardMessageExpander.extractMsgSegmentsText(apiResponse);
    }
    
    /** @deprecated 委托给 ForwardMessageExpander */
    private String extractForwardIdFromMsgJson(String apiResponse) {
        return ForwardMessageExpander.extractForwardIdFromMsgJson(apiResponse);
    }
    
    /** @deprecated 委托给 ForwardMessageExpander */
    private String extractForwardText(String forwardContent) {
        return ForwardMessageExpander.extractForwardText(forwardContent);
    }

    // ==================== 消息处理 ====================

    /**
     * 处理已解析的QQ消息。
     */
    void handleMessage(QQMessage msg, Consumer<String> responseSender) {
        // 防重复
        final long msgId = msg.getMessageId();
        if (!processingMessages.add(msgId)) {
            AiAgentActivity.qqLog("[QQMsg] 消息已处理中，忽略: " + msgId);
            return;
        }
        try {
            AiAgentActivity.qqLog("[QQMsg] 开始处理: " + msg);
            
            // 检查unifiedMemory是否初始化
            if (unifiedMemory == null) {
                AiAgentActivity.qqLog("[QQMsg] 错误: unifiedMemory未初始化！dataDir=" + dataDir);
                sendReply(msg, "系统错误：记忆管理器未就绪，请稍后再试。");
                processingMessages.remove(msgId);
                return;
            }
            
            // 检查selfId是否设置（群聊@检测需要）
            if (msg.isGroupMessage() && selfId == 0) {
                AiAgentActivity.qqLog("[QQMsg] 警告: selfId未设置，群聊@检测将失效！请执行 ai/onebotsetselfid <QQ号>");
            }


            // === 折叠消息内容预展开（必须在存入上下文之前，确保所有消息的折叠内容不丢失） ===
            if (msg.getForwardId() != null && !msg.getForwardId().isEmpty()
                    && (msg.getForwardContent() == null || msg.getForwardContent().isEmpty())
                    && napcatApi != null) {
                try {
                    AiAgentActivity.qqLog("[QQMsg] 同步展开折叠消息: forwardId=" + msg.getForwardId());
                    String fwdJson = napcatApi.getForwardMsg(msg.getForwardId());
                    if (fwdJson != null && !fwdJson.isEmpty()) {
                        String extracted = ForwardMessageExpander.extractForwardMsgContent(fwdJson);
                        if (extracted != null && !extracted.isEmpty()) {
                            if (extracted.length() > 5000) extracted = extracted.substring(0, 5000) + "...";
                            msg.setForwardContent(extracted);
                            AiAgentActivity.qqLog("[QQMsg] 折叠消息同步展开成功，长度: " + extracted.length());
                        }
                    }
                } catch (Exception e) {
                    AiAgentActivity.qqLog("[QQMsg] 同步展开折叠消息失败: " + e.toString());
                }
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
                
                // 0.1 记录群成员关系到好感度层（用于好感度排行按群查询）
                if (emotionManager != null) {
                    emotionManager.recordGroupMember(msg.getGroupId(), msg.getUserId());
                }
                
                // 0.5 记录群印象消息计数（群印象系统：累计消息数与最后活跃时间）
                try {
                    sair.aiagent.core.SkillBank sk = sair.aiagent.core.SkillBank.getInstance();
                    if (sk != null && sk.getPersistenceManager() != null) {
                        sk.getPersistenceManager().recordGroupImpressionMessage(
                            msg.getGroupId(),
                            unifiedMemory.getGroupName(msg.getGroupId())
                        );
                    }
                } catch (Exception ignored) {}

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
                            AiAgentActivity.qqLog("[QQMsg] 检测到提到名字: " + botName);
                        }
                    }
                }
                
                if (!shouldRespond) {
                    // 非触发：监听态期间做相关性判定，相关则并发处理续期（仅监听开启时）
                    if (listenStateEnabled && listeningState != null && listeningState.onListeningMessage(msg)) {
                        AiAgentActivity.qqLog("[QQMsg] 监听态相关性命中，自动续期(并发): " + msg.getRawMessage());
                        final QQMessage listenMsg = msg;
                        execPool.submit(() -> processTask(new TriggerTask(
                                TriggerTask.TYPE_GROUP, listenMsg.getGroupId(), listenMsg.getUserId(),
                                TriggerTask.TRIGGER_LISTEN, listenMsg)));
                        // 不 remove msgId，待 processTask 完成时移除
                        return;
                    }
                    AiAgentActivity.qqLog("[QQMsg] 群消息已忽略(未@或提到名字): " + msg.getRawMessage());
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
                    AiAgentActivity.qqLog("[QQMsg] 消息被InternalAgents拦截: userId=" + qq);
                    processingMessages.remove(msgId);
                    return;
                }
            }
            
            // 注意：用户消息已在上面的if-else块中记录到unifiedMemory，这里不需要再次记录
            // === 记录印象消息计数（人格印象蒸馏系统的第一步） ===
            sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
            if (bank != null && bank.getPersistenceManager() != null) {
                bank.getPersistenceManager().recordImpressionMessage(qq, msg.getDisplayName());
            }
            // === 群印象差时 30% 概率拒答（对主人单独排除） ===
            if (msg.isGroupMessage() && bank != null && bank.getPersistenceManager() != null) {
                sair.aiagent.model.GroupImpression gi = bank.getPersistenceManager().getGroupImpression(msg.getGroupId());
                if (gi != null && gi.isUnfriendly()) {
                    // 群印象→情绪反向联动：群氛围差会让 AI 情绪变差（双向联动第二向）
                    if (emotionManager != null) {
                        try { emotionManager.noteUnfriendlyGroup(); } catch (Exception ignored) {}
                    }
                    if (!sair.aiagent.core.AiConfig.getInstance().isMasterQQ(qq)) {
                        int roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(100);
                        if (roll < 30) {
                            AiAgentActivity.qqLog("[QQMsg] 群印象差(friendliness=" + gi.getFriendliness()
                                + ")，30%概率拒答命中 roll=" + roll + "，群=" + msg.getGroupId());
                            String why = (gi.getAtmosphere() != null && !gi.getAtmosphere().isEmpty())
                                ? gi.getAtmosphere() : "这个群的氛围让我有些不适";
                            sendReply(msg, "（我注意到" + why + "，暂时不太想参与本群的互动。"
                                + "如果我之前的言行有冒犯之处，请告诉我，我会认真改进。）");
                            processingMessages.remove(msgId);
                            return;
                        }
                    }
                }
            }

            AiAgentActivity.qqLog("[QQMsg] 准备执行AI: " + (msg.isGroupMessage() ? "群聊" : "私聊") + ", userId=" + qq);

            // === 情绪检测（在主线程快速完成，不阻塞） ===
            if (emotionManager != null) {
                try {
                    String pt = msg.getPlainTextOnly();
                    if (pt != null && !pt.trim().isEmpty()) {
                        String detectedEmotion = emotionManager.processMessageEmotion(pt, qq);
                        // 情绪→印象联动：负面情绪降低群印象，正面情绪提升（双向联动第一向）
                        linkEmotionToImpression(detectedEmotion, msg);
                    }
                } catch (Exception e) {
                    AiAgentActivity.qqLog("[QQMsg] Emotion detect fail: " + e.toString());
                }
            }

            // === 全部并发处理：取消串行队列，所有消息统一走 execPool 并发 ===
            // 主动触发（@/名字）时重置该群自动续期计数（监听机制保留）
            if (msg.isGroupMessage() && listenStateEnabled && listeningState != null) {
                listeningState.resetAutoEnqueueCount(msg.getGroupId());
            }
            execPool.submit(() -> processTask(new TriggerTask(
                    msg.isGroupMessage() ? TriggerTask.TYPE_GROUP : TriggerTask.TYPE_PRIVATE,
                    msg.getGroupId(), msg.getUserId(),
                    msg.isGroupMessage() ? (msg.isAtBot() ? TriggerTask.TRIGGER_AT : TriggerTask.TRIGGER_NAME) : TriggerTask.TRIGGER_PRIVATE,
                    msg)));


        } catch (Exception e) {
            processingMessages.remove(msgId);
            AiAgentActivity.qqLog("[QQMsg] handleMessage错误: " + e.toString());
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            AiAgentActivity.qqLog("[QQMsg] 堆栈跟踪:\n" + sw.toString());
            throw e;
        }
    }

    /**
     * 处理单个触发任务（由 execPool 并发线程调用）。
     * <p>群管理员检查 → 引用/折叠消息 → 历史图片回看 → 表情包收集 →
     * 构建提示词 → 转发检查 → 执行 AI。</p>
     */
    public void processTask(TriggerTask task) {
        QQMessage msg = task.getMessage();
        if (msg == null) return;
        final long msgId = msg.getMessageId();
        try {
            // === 群管理员被动检查（被@时检查违规） ===
            List<String> punishmentRecords = new ArrayList<>();
            if (msg.isGroupMessage() && groupModerator != null) {
                GroupModerationConfig config = groupModerator.getGroupConfig(msg.getGroupId());
                if (config != null && config.isAutoMonitorEnabled()) {
                    AiAgentActivity.qqLog("[QQMsg] Bot是管理员，被@时检查群消息违规");
                    punishmentRecords = groupModerator.checkMessagesOnAt(msg.getGroupId(), msg.getUserId());
                    if (!punishmentRecords.isEmpty()) {
                        AiAgentActivity.qqLog("[QQMsg] 发现 " + punishmentRecords.size() + " 个违规行为");
                    }
                }
            }

            // === 获取引用消息内容 ===
            if (msg.isReply() && msg.getReplyMessageId() > 0 && napcatApi != null) {
                int quotedMsgId = (int) msg.getReplyMessageId();
                AiAgentActivity.qqLog("[QQMsg] 获取引用消息: messageId=" + quotedMsgId);
                try {
                    String quotedJson = napcatApi.getMessage(quotedMsgId);
                    if (quotedJson != null && !quotedJson.isEmpty()) {
                        String quotedText = ForwardMessageExpander.extractQuotedMessageFull(quotedJson);
                        if (quotedText != null && !quotedText.isEmpty()) {
                            msg.setQuotedMessageContent(quotedText);
                            AiAgentActivity.qqLog("[QQMsg] 引用消息内容(segment): " +
                                (quotedText.length() > 100 ? quotedText.substring(0, 100) + "..." : quotedText));
                        }
                        java.util.List<String> quotedImgUrls = ForwardMessageExpander.extractQuotedImageUrls(quotedJson);
                        if (quotedImgUrls != null && !quotedImgUrls.isEmpty()) {
                            for (String imgUrl : quotedImgUrls) {
                                msg.addQuotedImageUrl(imgUrl);
                            }
                            AiAgentActivity.qqLog("[QQMsg] 引用消息包含图片: " + quotedImgUrls.size() + "张");
                        }
                        String quotedFwdId = ForwardMessageExpander.extractForwardIdFromMsgJson(quotedJson);
                        if (quotedFwdId != null && !quotedFwdId.isEmpty()) {
                            AiAgentActivity.qqLog("[QQMsg] 引用消息是折叠消息: forwardId=" + quotedFwdId);
                            try {
                                String fwdJson = napcatApi.getForwardMsg(quotedFwdId);
                                if (fwdJson != null && !fwdJson.isEmpty()) {
                                    String fwdC = ForwardMessageExpander.extractForwardMsgContent(fwdJson);
                                    if (fwdC != null && !fwdC.isEmpty()) {
                                        if (fwdC.length() > 5000) fwdC = fwdC.substring(0, 5000) + "...";
                                        String exist = msg.getQuotedMessageContent();
                                        msg.setQuotedMessageContent((exist != null ? exist + "\n\n" : "")
                                            + "[折叠消息展开内容]\n" + fwdC);
                                        AiAgentActivity.qqLog("[QQMsg] 引用折叠消息展开成功，长度: " + fwdC.length());
                                    }
                                }
                            } catch (Exception ef) {
                                AiAgentActivity.qqLog("[QQMsg] 引用折叠消息展开失败: " + ef.toString());
                            }
                        }
                        if (msg.getQuotedMessageContent() == null || msg.getQuotedMessageContent().isEmpty()) {
                            String raw = extractString(quotedJson, "raw_message");
                            if (raw != null && !raw.isEmpty()) {
                                msg.setQuotedMessageContent(raw);
                                AiAgentActivity.qqLog("[QQMsg] 引用消息降级raw_message: " +
                                    (raw.length() > 100 ? raw.substring(0, 100) + "..." : raw));
                            }
                        }
                    }
                } catch (Exception e) {
                    AiAgentActivity.qqLog("[QQMsg] 获取引用消息失败: " + e.toString());
                }
            }

            // === 历史图片回看：当前消息无图且未引用图片时，从最近历史消息中找回用户刚发的图片 ===
            if (!msg.hasImage() && msg.getQuotedImageUrls().isEmpty() && napcatApi != null) {
                try {
                    String historyJson = null;
                    if (msg.isGroupMessage() && msg.getGroupId() > 0) {
                        historyJson = napcatApi.getGroupMsgHistory(msg.getGroupId(), 0, 10);
                    } else if (msg.isPrivateMessage() && msg.getUserId() > 0) {
                        historyJson = napcatApi.getFriendMsgHistory(msg.getUserId(), 0, 10);
                    }
                    if (historyJson != null && !historyJson.isEmpty()) {
                        java.util.List<String> historyImgUrls = ForwardMessageExpander.extractHistoryImageUrls(historyJson, msg.getUserId());
                        if (historyImgUrls != null && !historyImgUrls.isEmpty()) {
                            for (String imgUrl : historyImgUrls) {
                                msg.addImageUrl(imgUrl);
                            }
                            AiAgentActivity.qqLog("[QQMsg] 历史图片回看命中: " + historyImgUrls.size() + "张");
                        }
                    }
                } catch (Exception e) {
                    AiAgentActivity.qqLog("[QQMsg] 历史图片回看失败: " + e.toString());
                }
            }

            // === 自动收集表情包图片（仅群聊，sub_type=1/3/7 的 image 段 + mface/sticker 段） ===
            if (msg.isGroupMessage() && msg.hasStickerImages() && agentExecutor != null && agentExecutor.getStickerManager() != null) {
                String ctx = msg.getPlainText();
                for (String imgUrl : msg.getStickerUrls()) {
                    try {
                        agentExecutor.collectStickerFromQQ(imgUrl, ctx);
                    } catch (Exception ex) {
                        AiAgentActivity.qqLog("[QQMsg] Sticker collect fail: " + ex.toString());
                    }
                }
            }

            // 构建稳定 system 前缀与动态上下文（缓存优化：稳定前缀作 system，动态上下文下沉 user）
            String stableSystem = buildStableSystemPrompt();
            String dynamicContext = buildDynamicContext(msg, punishmentRecords);
            AiAgentActivity.qqLog("[QQMsg] 系统提示词构建完成，稳定前缀: " + stableSystem.length()
                    + ", 动态上下文: " + dynamicContext.length());

            // === 检查是否为转发请求：引用消息 + "转发到XXX"模式 ===
            if (handleForwardRequest(msg)) {
                AiAgentActivity.qqLog("[QQMsg] 转发请求已处理，跳过AI");
                return;
            }

            // 执行AI代理
            AiAgentActivity.qqLog("[QQMsg] 开始执行AI代理...");
            executeQqAgent(msg, unifiedMemory, stableSystem, dynamicContext, null);
            AiAgentActivity.qqLog("[QQMsg] AI代理执行完成");

            // 群聊任务执行完 → 进入监听态（监听机制保留，仅监听开启且群在监听群内）
            if (task.isGroup() && listenStateEnabled && listeningState != null
                    && isMonitoredGroup(task.getGroupId())) {
                listeningState.enterListening(task);
            }
        } catch (Exception e) {
            AiAgentActivity.qqLog("[QQMsg] 执行错误: " + e.toString());
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            AiAgentActivity.qqLog("[QQMsg] 堆栈跟踪:\n" + sw.toString());
        } finally {
            processingMessages.remove(msgId);
        }
    }

    /**
     * AI 判定群友新消息是否与当前话题相关（监听续期用）。
     * <p>用 flash 模型轻量判定：结合触发话题 + 最近群聊历史，返回「相关」或「不相关」。</p>
     */
    @Override
    public boolean isRelevant(QQMessage newMsg, TriggerTask currentTask) {
        try {
            sair.aiagent.core.DeepSeekClient client = getDeepSeekClient();
            if (client == null) return false;
            String newText = newMsg.getPlainText();
            if (newText == null || newText.trim().isEmpty()) return false;

            StringBuilder ctx = new StringBuilder();
            String topic = currentTask.getMessage() != null ? currentTask.getMessage().getPlainText() : "";
            ctx.append("触发话题: ").append(topic).append("\n");
            if (unifiedMemory != null && currentTask.isGroup()) {
                List<String[]> recent = unifiedMemory.getRecentGroupChatHistory(currentTask.getGroupId(), 10);
                if (recent != null) {
                    for (String[] m : recent) {
                        if (m != null && m.length >= 3) ctx.append(m[1]).append(": ").append(m[2]).append("\n");
                    }
                }
            }

            String prompt = "你是QQ群聊话题相关性判断助手。判断群友新消息是否在接续当前话题。\n\n"
                + ctx.toString() + "\n"
                + "群友新消息: \"" + newText + "\"\n\n"
                + "只回答: 相关 或 不相关";

            List<sair.aiagent.model.ChatMessage> msgs = new ArrayList<>();
            msgs.add(new sair.aiagent.model.ChatMessage("user", prompt));
            String resp = client.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());
            if (resp == null) return false;
            resp = resp.trim();
            boolean relevant = resp.contains("相关") && !resp.contains("不相关");
            AiAgentActivity.qqLog("[Queue] 相关性判定: \""
                + newText.substring(0, Math.min(20, newText.length())) + "\" → " + (relevant ? "相关" : "不相关"));
            return relevant;
        } catch (Exception e) {
            AiAgentActivity.qqLog("[Queue] 相关性判定失败: " + e.toString());
            return false;
        }
    }

    /**
     * 情绪→印象联动：把检测到的即时情绪映射为群印象友好度调整。
     * 负面情绪降低群友好度，正面情绪提升（情绪→印象的第一向）。
     * 个人层面的「印象数值」由 EmotionStateManager 的好感度（affection）承担，
     * 这里只做群维度联动。
     */
    private void linkEmotionToImpression(String emotion, QQMessage msg) {
        if (emotion == null || "平静".equals(emotion)) return;
        sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
        if (bank == null || bank.getPersistenceManager() == null) return;
        sair.aiagent.core.PersistenceManager pm = bank.getPersistenceManager();

        int delta;
        switch (emotion) {
            case "生气": delta = -2; break;
            case "难过":
            case "调侃": delta = -1; break;
            case "开心":
            case "感激":
            case "友善":
            case "撒娇": delta = +1; break;
            default: return;
        }

        // 群消息：调整群印象友好度（群内成员情绪影响群体印象）
        if (msg.isGroupMessage() && msg.getGroupId() > 0) {
            pm.adjustGroupFriendliness(msg.getGroupId(), delta);
            AiAgentActivity.debugLog("[Impression] 群印象联动: 群=" + msg.getGroupId()
                + " 情绪=" + emotion + " Δ" + delta);
        }
    }

    // ==================== 好友申请处理 ====================

    /**
     * 处理好友申请（OneBot v11 request/friend/add事件）。
     * 不再自动同意/拒绝：登记到待处理请求池，交由 AI/主人通过 FC 工具决策。
     * @param userId 申请人QQ号
     * @param comment 验证消息（可能为空）
     * @param flag OneBot请求标识（用于API调用）
     */
    public void handleFriendRequest(long userId, String comment, String flag) {
        AiAgentActivity.qqLog("[QQMsg] 好友申请: userId=" + userId + ", comment=" + 
            (comment != null ? (comment.length() > 30 ? comment.substring(0, 30) + "..." : comment) : "(空)"));
        
        if (napcatApi == null) {
            AiAgentActivity.qqLog("[QQMsg] NapCatApi未就绪，无法处理好友申请");
            return;
        }
        
        // 不再自动同意/拒绝：登记到待处理请求池，交由 AI/主人通过 FC 工具决策
        pendingRequestPool.add(new PendingRequestPool.PendingRequest(
            "friend", flag, userId, 0, comment));
        AiAgentActivity.qqLog("[QQMsg] 好友申请已入池，待AI决策: userId=" + userId);
    }

    // ==================== 群邀请处理 ====================

    /**
     * 处理群邀请请求（OneBot v11 request/group/invite事件）。
     * 主人无条件同意；非主人登记到待处理请求池，交由 AI 决策（结合好感度分级权限）。
     * @param userId 邀请者QQ号
     * @param groupId 群号
     * @param flag OneBot请求标识（用于API调用）
     */
    public void handleGroupInviteRequest(long userId, long groupId, String flag) {
        AiAgentActivity.qqLog("[QQMsg] 群邀请: userId=" + userId + ", groupId=" + groupId);
        
        if (napcatApi == null) {
            AiAgentActivity.qqLog("[QQMsg] NapCatApi未就绪，无法处理群邀请");
            return;
        }
        
        // 主人 → 无条件同意（主人始终拥有 NapCat 全部权限）
        boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(userId);
        if (isMaster) {
            AiAgentActivity.qqLog("[QQMsg] 主人邀请加群，无条件同意");
            napcatApi.handleGroupInvite(flag, true, null);
            if (server != null) server.sendPrivateMsg(userId, "[Bot] 主人邀请，已自动同意加群~");
            return;
        }
        
        // 非主人：登记到待处理请求池，交由 AI 决策（结合好感度分级权限）
        pendingRequestPool.add(new PendingRequestPool.PendingRequest(
            "group", flag, userId, groupId, null));
        AiAgentActivity.qqLog("[QQMsg] 群邀请已入池，待AI决策: userId=" + userId + " groupId=" + groupId);
    }

    // ==================== 图片多模态支持 ====================

    /**
     * 下载图片并转换为 base64 data URI。
     * <p>用于 DeepSeek Vision API：当 QQ 图片 URL 对 API 不可访问时，
     * 通过本地下载转为 base64 后传入。</p>
     *
     * @param imageUrl 图片 URL
     * @return base64 data URI 字符串（如 "data:image/jpeg;base64,..."），失败返回 null
     */
    /** @deprecated 委托给 ImageDownloader */
    private String downloadImageAsBase64(String imageUrl) {
        return ImageDownloader.downloadImageAsBase64(imageUrl);
    }

    /**
     * 解析图片 URL 为 Vision API 可用格式。
     * <p>策略：优先使用原始 URL（DeepSeek 可直接访问公网 URL），
     * 若 URL 看起来像腾讯内网地址则下载转 base64。</p>
     *
     * @param imageUrl 原始图片 URL
     * @return Vision API 可用的图片 URL 或 base64 data URI，失败返回 null
     */
    /** @deprecated 委托给 ImageDownloader */
    private String resolveImageForVision(String imageUrl) {
        return ImageDownloader.resolveImageForVision(imageUrl);
    }

    /**
     * 执行真正的execs链路（与SFW控制台execs完全一样）。
     * 实时推送每一轮的思考和执行结果给主人。
     * @param msg QQ消息对象
     * @param task 任务描述（已去掉execs:前缀）
     * @param responseSender 消息发送回调
     * @return 执行结果摘要
     */
    /** @deprecated delegated to QQAgentBridge */
    private String executeRealExecs(QQMessage msg, String task, Consumer<String> responseSender) {
        if (agentBridge != null) return agentBridge.executeRealExecs(msg, task, responseSender);
        return "[错误] AI引擎未就绪";
    }

    /**
     * 将execs思考过程以QQ原生合并转发（合并转发）消息卡片发送。
     * <p>按 [第N轮思考] 标识拆分为多个转发节点，每轮思考为一个节点，
     * 最终形成一个可折叠/展开的消息卡片。</p>
     * @param msg 原始QQ消息（用于获取群号/私聊对象）
     * @param thinkingText 累积的思考文本（含 [第N轮思考] 标记）
     */
    /** @deprecated delegated to QQAgentBridge */
    private void sendThinkingAsForward(QQMessage msg, String thinkingText) {
        if (agentBridge != null) agentBridge.sendThinkingAsForward(msg, thinkingText);
    }

    /**
     * 执行QQ通道的Agent处理。
     * 根据发送者身份决定使用execq（受限）还是execs（自由）模式。
     */
    /** @deprecated delegated to QQAgentBridge */
    private void executeQqAgent(QQMessage msg, Object memoryManager,
                                 String stableSystem, String dynamicContext, Consumer<String> responseSender) {
        if (agentBridge != null) { agentBridge.executeQqAgent(msg, memoryManager, stableSystem, dynamicContext, responseSender); return; }
        sendReply(msg, "AI引擎未就绪，请稍后再试。");
    }

    /** @deprecated delegated to QQAgentBridge */
    private void sendReply(QQMessage msg, String text) {
        if (agentBridge != null) agentBridge.sendReply(msg, text);
    }

    /** @deprecated delegated to QQAgentBridge */
    private void sendMultipleReplies(QQMessage msg, List<String> texts) {
        if (agentBridge != null) agentBridge.sendMultipleReplies(msg, texts);
    }

    /** @deprecated delegated to QQAgentBridge */
    private List<String> splitIntoMessages(String text) {
        if (agentBridge != null) return agentBridge.splitIntoMessages(text);
        return java.util.Collections.emptyList();
    }

    // ==================== 提示词构建 ====================

    /** 构建 execq 通道稳定 system 前缀（角色/规范/能力，缓存命中点） */
    private String buildStableSystemPrompt() {
        if (promptBuilder != null) return promptBuilder.buildStableSystemPrompt();
        return "";
    }

    /** 构建 execq 通道动态上下文（历史/记忆/情绪等，下沉 user 消息） */
    private String buildDynamicContext(QQMessage msg, List<String> punishmentRecords) {
        if (promptBuilder != null) return promptBuilder.buildDynamicContext(msg, punishmentRecords);
        return "";
    }

    /** @deprecated delegated to QQPromptBuilder */
    private String resolveUserName(long qq, long groupId, UnifiedQQMemoryManager mem) {
        return QQPromptBuilder.resolveUserName(qq, groupId, mem);
    }

    /** @deprecated delegated to QQAgentBridge */
    private String buildTaskDescription(QQMessage msg) {
        if (agentBridge != null) return agentBridge.buildTaskDescription(msg);
        return "";
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
        
        AiAgentActivity.qqLog("[QQMsg] 已关闭");
    }

    // ==================== 工具 ====================

    /** @deprecated delegated to QQAgentBridge */
    public String getBotLabel() {
        if (agentBridge != null) return agentBridge.getBotLabel();
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

    // ==================== 转发请求预处理器 ====================

    /** 转发目标解析结果 */
    private static class ResolvedTarget {
        final long id;
        final boolean isGroup;
        ResolvedTarget(long id, boolean isGroup) { this.id = id; this.isGroup = isGroup; }
    }

    /**
     * 检测并处理转发请求：引用消息 + "转发到XXX"/"发给XXX" 模式。
     * <p>在 AI Agent 之前执行，避免不必要的大模型调用。</p>
     * @return true 表示已处理（应跳过 AI），false 表示不是转发请求
     */
    private boolean handleForwardRequest(QQMessage msg) {
        // 必须是引用回复消息
        if (!msg.isReply() || msg.getReplyMessageId() <= 0) return false;

        // 必须有引用消息内容（已在前面同步获取）
        String quotedContent = msg.getQuotedMessageContent();
        if (quotedContent == null || quotedContent.isEmpty()) return false;

        // 检测转发意图关键词
        String text = msg.getPlainText();
        if (text == null || text.isEmpty()) return false;

        java.util.regex.Pattern fwPattern = java.util.regex.Pattern.compile("(?:\u8f6c\u53d1|\u53d1\u7ed9|\u53d1\u5230|\u8f6c\u8fbe)\\s*(?:\u5230|\u7ed9)?\\s*(.+?)(?:\\s*$|\\s*[\uff0c\u3002\uff01\uff1f,!?]|\\s*\u5427|\\s*\u54e6|\\s*\u54c8)");
        java.util.regex.Matcher m = fwPattern.matcher(text);
        if (!m.find()) return false;

        String targetDesc = m.group(1).trim();
        if (targetDesc.isEmpty()) return false;

        AiAgentActivity.qqLog("[QQMsg] 检测到转发请求: target=" + targetDesc);

        // 解析目标
        ResolvedTarget target = resolveForwardTarget(targetDesc, msg);
        if (target == null) {
            sendReply(msg, "\u274c 未找到目标：" + targetDesc + "\n请确认QQ号/群号/群名/昵称是否正确。");
            return true;
        }

        // 构建转发消息（含免责声明）
        String senderName = msg.getDisplayName();
        String forwardMsg = "\ud83d\udce8【转发消息】\n" + quotedContent
                + "\n\n\u26a0\ufe0f 此消息由 " + senderName
                + "(QQ:" + msg.getUserId() + ") 触发转发，非AI自动发送";

        // 发送到目标
        try {
            if (target.isGroup) {
                napcatApi.sendGroupMessage(target.id, forwardMsg);
            } else {
                napcatApi.sendPrivateMessage(target.id, forwardMsg);
            }
            // 告知触发人
            sendReply(msg, "\u2705 已转发到 " + targetDesc + "（" + (target.isGroup ? "群" : "私聊") + "）");
        } catch (Exception e) {
            AiAgentActivity.qqLog("[QQMsg] 转发失败: " + e.toString());
            sendReply(msg, "\u274c 转发失败: " + e.getMessage());
        }
        return true;
    }

    /**
     * 智能解析转发目标：优先级 QQ号/群号 > 群名称 > 群昵称 > 管理员名称。
     */
    private ResolvedTarget resolveForwardTarget(String desc, QQMessage msg) {
        if (desc == null || desc.isEmpty()) return null;

        // 1. 纯数字 -> 先查是否已知群号，否则按QQ号处理
        try {
            long num = Long.parseLong(desc.trim());
            if (unifiedMemory != null && unifiedMemory.getGroupName(num) != null) {
                return new ResolvedTarget(num, true);
            }
            return new ResolvedTarget(num, false);
        } catch (NumberFormatException ignored) {}

        // 2. 按群名称搜索
        if (napcatApi != null) {
            try {
                String groupListJson = napcatApi.getGroupList();
                if (groupListJson != null && !groupListJson.isEmpty()) {
                    String dataArr = JsonUtil.extractArray(groupListJson, "data");
                    if (dataArr != null) {
                        java.util.List<String> groups = JsonUtil.splitJsonArray(dataArr);
                        for (String g : groups) {
                            String gname = JsonUtil.extractString(g, "group_name");
                            String gidStr = JsonUtil.extractString(g, "group_id");
                            if (gname != null && gname.contains(desc) && gidStr != null) {
                                try { return new ResolvedTarget(Long.parseLong(gidStr), true); }
                                catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.qqLog("[QQMsg] 群列表查询失败: " + e.toString());
            }
        }

        // 3. 按群昵称搜索（当前群）
        if (msg.isGroupMessage() && unifiedMemory != null) {
            java.util.Map<String, Long> nickMap = unifiedMemory.getGroupNicknameMap(msg.getGroupId());
            for (java.util.Map.Entry<String, Long> e : nickMap.entrySet()) {
                if (e.getKey().contains(desc)) return new ResolvedTarget(e.getValue(), false);
            }
        }

        // 4. 按管理员名称搜索
        if (msg.isGroupMessage() && unifiedMemory != null) {
            java.util.List<String[]> admins = unifiedMemory.getGroupAdmins(msg.getGroupId());
            if (admins != null) {
                for (String[] a : admins) {
                    if (a.length >= 2 && a[1] != null && a[1].contains(desc)) {
                        try { return new ResolvedTarget(Long.parseLong(a[0]), false); }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }
        }

        return null;
    }

}
