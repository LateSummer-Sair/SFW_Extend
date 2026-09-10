package sair.aiagent.core;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import sair.FCM;
import sair.Pathes;
import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.ToolDefinition;
import sair.aiagent.util.EdtUtils;
import sair.sys.Libraries;
import sair.user.Activity;

public class AgentExecutor {

    private static final Color C_TOOL = new Color(255, 200, 100);
    private static final Color C_AI = new Color(100, 255, 180);
    private static final Color C_INFO = new Color(180, 180, 180);

    private final Activity selfActivity;
    private final DeepSeekClient client;
    private volatile boolean stopped = false;
    private volatile String lastCmdOutput = "";
    private volatile String memoryContext;
    private volatile String notesContext;
    private volatile String correctionsContext;
    private volatile MemoryManager memoryManager;
    private final DynamicCodeEngine codeEngine;
    private final ConfirmationGate gate;
    private volatile JournalManager journal;
    private volatile EmotionManager emotionManager;
    private volatile StickerManager stickerManager;
    /** LLM驱动的对话历史压缩器（token超阈值时自动压缩） */
    private volatile HistoryCompressor historyCompressor;

    /** execq bypass ref count - thread-safe gate switching */
    private final java.util.concurrent.atomic.AtomicInteger bypassRefCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean savedBypassState = false;

    /** 上一轮 Agent 会话的总结（用于层层递进） */
    private volatile String previousSessionSummary;
    /** 本轮 Agent 会话的总结（执行完成后捕获） */
    private volatile String lastSummary;
    /** Chat 对话历史摘要（注入到 Agent 上下文） */
    private volatile String chatHistoryContext;
    /** 缓存系统提示词的不变部分（角色定义、能力清单等），避免每轮重建 */
    private volatile String cachedStaticPrompt;
    
    /** QQ execs消息回调（用于实时推送每一轮输出给主人） */
    private volatile java.util.function.Consumer<String> qqExecsCallback;
    /** 技能路径缓存（权重路由） */
    private volatile RouteCache routeCache;

    /** 标签执行器（保留 schedule/note/batch 等被 AgentActionHandler 复用的工具实现） */
    private final TagExecutor tagExecutor;
    /** Extracted action execution handler */
    private final AgentActionHandler actionHandler;
    /** Skills self-evolution engine */
    private volatile SkillBank skillBank;
    /** LLM-driven skill extractor */
    private volatile SkillExtractor skillExtractor;
    /** Track execution steps for post-run skill extraction */
    private final List<SkillExtractor.ActionStep> trajectorySteps = java.util.Collections.synchronizedList(new ArrayList<>());
    /** The original task description (for skill extraction context) */
    private volatile String currentTask;
    /** 统一工具分发器（Function Calling 通道） */
    private volatile ToolDispatcher toolDispatcher;
    /** 审查者子 Agent（Harness 验证闭环，可空） */
    private volatile CriticAgent criticAgent;
    /** 验证闭环开关（默认关闭，opt-in）：开启后才启用审查，避免老功能成本翻倍 */
    private volatile boolean criticEnabled = false;
    /** 多智能体编排引擎（Harness 多智能体能力，懒创建，opt-in） */
    private volatile AgentOrchestrator orchestrator;
    /** Agent 总线（递归多智能体通信，懒创建） */
    private volatile AgentBus agentBus;

    public AgentExecutor(Activity selfActivity, DeepSeekClient client, DynamicCodeEngine codeEngine, ConfirmationGate gate) {
        this.selfActivity = selfActivity;
        this.client = client;
        this.codeEngine = codeEngine;
        this.gate = gate;
        this.tagExecutor = new TagExecutor(gate, codeEngine, selfActivity);
        this.tagExecutor.setDeepSeekClient(client);
        this.actionHandler = new AgentActionHandler(gate, client, codeEngine, tagExecutor, selfActivity);
        this.actionHandler.setParent(this);
    }

    public void markStopped() {
        this.stopped = true;
    }

    public void setMemoryContext(String memoryContext) {
        this.memoryContext = memoryContext;
    }

    public void setNotesContext(String notesContext) {
        this.notesContext = notesContext;
    }

    public void setCorrectionsContext(String correctionsContext) {
        this.correctionsContext = correctionsContext;
    }

    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
        tagExecutor.setMemoryManager(memoryManager);
        actionHandler.setMemoryManager(memoryManager);
    }

    /** 设置是否跳过确认（execs 模式）。 */
    public void setBypassConfirm(boolean bypass) {
        gate.setBypassConfirm(bypass);
    }

    /** 设置日志管理器引用 */
    public void setJournal(JournalManager journal) {
        this.journal = journal;
        tagExecutor.setJournal(journal);
        actionHandler.setJournal(journal);
    }

    public JournalManager getJournal() {
        return journal;
    }

    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /** 设置情绪管理器引用 */
    public void setEmotionManager(EmotionManager emotionManager) {
        this.emotionManager = emotionManager;
        tagExecutor.setEmotionManager(emotionManager);
        actionHandler.setEmotionManager(emotionManager);
    }

    /** Set sticker manager reference */
    public void setStickerManager(StickerManager stickerManager) {
        this.stickerManager = stickerManager;
        tagExecutor.setStickerManager(stickerManager);
        actionHandler.setStickerManager(stickerManager);
    }

    /** 获取情绪管理器引用（供QQ情绪系统桥接） */
    public EmotionManager getEmotionManager() {
        return emotionManager;
    }

    /** 获取表情包管理器引用（供QQ自动匹配/收集） */
    public StickerManager getStickerManager() {
        return stickerManager;
    }

    /** Set history compressor for auto-compression in long conversations */
    public void setHistoryCompressor(HistoryCompressor historyCompressor) {
        this.historyCompressor = historyCompressor;
    }

    /** 注入审查者子 Agent（Harness 验证闭环），可空表示禁用。 */
    public void setCriticAgent(CriticAgent criticAgent) {
        this.criticAgent = criticAgent;
    }

    /** 开启/关闭验证闭环（默认关闭，/criticon 开启、/criticoff 关闭）。 */
    public void setCriticEnabled(boolean enabled) {
        this.criticEnabled = enabled;
    }

    /** 验证闭环是否开启。 */
    public boolean isCriticEnabled() {
        return criticEnabled;
    }

    /** 返回当前生效的审查者：开关关闭时返回 null（禁用审查）。 */
    private CriticAgent getActiveCritic() {
        return criticEnabled ? criticAgent : null;
    }

    /** 注入多智能体编排引擎（Harness 多智能体能力），可空则懒创建。 */
    public void setOrchestrator(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /** 获取多智能体编排引擎（懒创建）。 */
    public AgentOrchestrator getOrchestrator() {
        if (orchestrator == null) {
            synchronized (this) {
                if (orchestrator == null) {
                    orchestrator = new AgentOrchestrator(client, getToolDispatcher());
                }
            }
        }
        // 每次返回前同步开关状态，确保运行时 /criticon /criticoff 即时生效
        orchestrator.setCritic(getActiveCritic());
        return orchestrator;
    }

    /** 获取 DeepSeekClient 引用（供 ImpressionExtractor 使用） */
    public DeepSeekClient getDeepSeekClient() {
        return client;
    }

    /** 设置上一轮会话总结（用于层层递进记忆链） */
    public void setPreviousSessionSummary(String summary) {
        this.previousSessionSummary = summary;
    }
    
    /** 设置QQ execs消息回调 */
    public void setQqExecsCallback(java.util.function.Consumer<String> callback) {
        this.qqExecsCallback = callback;
    }
    
    /** 获取QQ execs消息回调 */
    public java.util.function.Consumer<String> getQqExecsCallback() {
        return qqExecsCallback;
    }

    /** 获取本轮会话总结 */
    public String getLastSummary() {
        return lastSummary;
    }

    /** 设置 Chat 对话历史上下文（供 handleExec 注入） */
    public void setChatHistoryContext(String ctx) {
        this.chatHistoryContext = ctx;
    }

    /** 设置定时任务调度器 */
    public void setCronScheduler(CronScheduler cronScheduler) {
        tagExecutor.setCronScheduler(cronScheduler);
    }

    /** Set skill bank reference */
    public void setSkillBank(SkillBank skillBank) {
        this.skillBank = skillBank;
    }

    /** Set skill extractor reference */
    public void setSkillExtractor(SkillExtractor skillExtractor) {
        this.skillExtractor = skillExtractor;
    }

    /** Get skill bank */
    public SkillBank getSkillBank() {
        return skillBank;
    }

    /** Get skill extractor */
    public SkillExtractor getSkillExtractor() {
        return skillExtractor;
    }

    /** Set route cache reference */
    public void setRouteCache(RouteCache routeCache) {
        this.routeCache = routeCache;
    }

    /** Get route cache */
    public RouteCache getRouteCache() {
        return routeCache;
    }

    /** Get action handler for tag execution (used by chat multi-round) */
    public AgentActionHandler getActionHandler() {
        return actionHandler;
    }

    public void enterBypass() {
        if (bypassRefCount.getAndIncrement() == 0) {
            savedBypassState = gate.isBypassConfirm();
            gate.setBypassConfirm(true);
        }
    }
    public void exitBypass() {
        if (bypassRefCount.decrementAndGet() == 0) {
            gate.setBypassConfirm(savedBypassState);
        }
    }

    // ==================== 原生 Function Calling 通道 ====================

    private ToolDispatcher getToolDispatcher() {
        if (toolDispatcher == null) {
            synchronized (this) {
                if (toolDispatcher == null) {
                    toolDispatcher = new ToolDispatcher(actionHandler);
                    // Harness 化升级：注册确定性约束钩子（权限矩阵 + 高危代码拦截 + 结果校验）
                    toolDispatcher.registerHook(new ToolHarnessHook(HarnessConfig.getInstance()));
                    // 初始化 Agent 总线并注入 dispatcher（call_agent/ask_agent 递归唤起）
                    if (agentBus == null) {
                        AgentBus bus = new AgentBus(client, toolDispatcher);
                        registerAgents(bus);
                        toolDispatcher.setAgentBus(bus);
                        agentBus = bus;
                    }
                }
            }
        }
        return toolDispatcher;
    }

    /** 注册三个协作 Agent：main（主模型）、vision（视觉）、passage（段落）。 */
    private void registerAgents(AgentBus bus) {
        String execqModel = AiConfig.getInstance().getExecqModel();
        bus.register(new AgentBus.AgentDef("main",
                buildStableSystemPrompt(),
                execqModel,
                ctx -> (ctx != null && ctx.execsMode) ? ToolDispatcher.buildExecsTools()
                        : (ctx != null && ctx.isExecq()) ? ToolDispatcher.buildExecqTools()
                        : ToolDispatcher.buildAllTools()));
        bus.register(new AgentBus.AgentDef("vision",
                VISION_AGENT_PROMPT,
                execqModel,
                ctx -> buildVisionAgentTools()));
        bus.register(new AgentBus.AgentDef("passage",
                PASSAGE_AGENT_PROMPT,
                execqModel,
                ctx -> buildPassageAgentTools()));
    }

    /** 视觉 Agent 系统提示词。 */
    private static final String VISION_AGENT_PROMPT =
        "你是视觉分析 Agent，负责客观分析图像内容。\n"
      + "工作方式：\n"
      + "1. 识别图片时调用 vision 工具（传入图片 URL 或 file_id）。\n"
      + "2. 只做客观识别与转写，不要臆测、不要编造图片里没有的内容。\n"
      + "3. 当你不确定图片中的某个特征是否符合要求、或需要更多背景线索时，用 ask_agent 向 main 发问，或直接用 web/search/searchglobal 查询线索。\n"
      + "4. 得到足够信息后，返回结构化的分析结论（图片主体、关键文字、特征判断等）。";

    /** 段落 Agent 系统提示词。 */
    private static final String PASSAGE_AGENT_PROMPT =
        "你是段落分析 Agent，负责概括折叠消息 + 分析段落中的图片并决定是否收藏为表情包。\n"
      + "工作方式：\n"
      + "1. 阅读段落内容，提炼对话主题、各方观点、与当前对话相关的重点。\n"
      + "2. 找出段落中出现的图片（带 [图片URL:...] 标记，或段落里明确给出的图片 URL）。\n"
      + "3. 对每张【未处理】的图片（段落里没有 〖已处理〗 标记的），用 call_agent 唤起 vision 识别图片内容；传给 vision 的 url 必须是完整的 https:// 开头的 URL，若只拿到 fileid（不含 https://），先用 searchglobal 查完整 URL 再识图。\n"
      + "4. 根据 vision 返回的信息判断能否收藏为表情包：仅【单张人脸/表情】+【图中文字≤20字】+【无违规内容】才收藏。\n"
      + "5. 能收藏的图片用 collectsticker 工具收藏（content 参数 = imageUrl|context 格式，context 写该图所在的对话语境）。\n"
      + "6. 处理完的图片用 markmessage 工具打 Mark（action=set，mark 写「已收藏」或「跳过+原因」），防止重复处理。\n"
      + "7. 最终返回：你收藏了哪些图、跳过了哪些图（含原因）、以及给哪些消息打了 Mark（方便主 Agent 维护全局记录）。\n"
      + "8. 若段落里没有图片，或图片都已处理过，直接返回「无新图片需要处理」即可。";

    /** 视觉 Agent 工具集。 */
    private static List<ToolDefinition> buildVisionAgentTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(new ToolDefinition("vision", "视觉分析图片：调用视觉模型分析并返回图片特征（类型/主体/文字/色调/二维码/违规内容等）")
                .addString("url", "图片 URL（http/https）或 file_id"));
        tools.add(new ToolDefinition("web", "抓取指定 URL 的网页/API 内容")
                .addString("url", "要抓取的完整 URL（http/https）"));
        tools.add(new ToolDefinition("search", "在必应搜索网页并返回标题、链接、摘要")
                .addString("query", "搜索关键词"));
        tools.add(new ToolDefinition("searchglobal", "全局记忆检索：跨群/跨用户检索对话历史、群聊记录和长期记忆")
                .addString("query", "检索关键词"));
        return tools;
    }

    /** 段落 Agent 工具集。 */
    private static List<ToolDefinition> buildPassageAgentTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(new ToolDefinition("searchglobal", "全局记忆检索：跨群/跨用户检索对话历史、群聊记录和长期记忆")
                .addString("query", "检索关键词"));
        tools.add(new ToolDefinition("searchnote", "在知识库中检索相关笔记")
                .addString("query", "检索关键词"));
        tools.add(new ToolDefinition("vision", "视觉分析图片：调用视觉模型分析并返回图片特征")
                .addString("url", "图片 URL（http/https）或 file_id"));
        tools.add(new ToolDefinition("collectsticker", "收藏表情包（imageUrl|context）")
                .addString("content", "imageUrl|context 格式"));
        tools.add(new ToolDefinition("markmessage", "给消息打 Mark 备注（AI 自用内部标记，用户看不到）。action=set 标记某条消息的处理结果")
                .addString("action", "set/get/list")
                .addOptionalString("message", "要标记的消息内容")
                .addOptionalString("mark", "备注内容（如「已收藏」「跳过+原因」）"));
        return tools;
    }

    /**
     * 本地 execs 模式 —— 免确认 + 原生 Function Calling（全能模式）。
     * <p>替代原 exec/execs/chat/execfc 四链路：模型自动决定聊天还是调用工具。</p>
     */
    public String executeFcLocal(String task) {
        this.currentTask = task;
        this.stopped = false;
        this.trajectorySteps.clear();
        enterBypass();
        try {
            FunctionCallingBridge bridge = new FunctionCallingBridge(client);
            bridge.setCritic(getActiveCritic());
            ToolContext ctx = new ToolContext("console");
            // 上下文快照（供 alarm 工具持久化）
            ctx.stableSystemPrompt = buildStableSystemPrompt();
            ctx.dynamicContext = buildDynamicContext();
            ctx.model = AiConfig.getInstance().getAgentModel();
            return bridge.runWithDispatcher(task, ctx.stableSystemPrompt, ctx.dynamicContext,
                    ToolDispatcher.buildAllTools(), ctx.model,
                    getToolDispatcher(), ctx, () -> stopped);
        } finally {
            exitBypass();
        }
    }

    /**
     * QQ 通道普通消息（execq）—— 原生 Function Calling + 受限工具集 + QQ 上下文。
     * <p>flash 模型，工具集仅 NapCat + 低权限工具（web/readfile/readdir/受限evaljs/仅help的cmd）。</p>
     */
    public String executeFcExecq(String task, String systemPrompt, ToolContext ctx, String model) {
        return executeFcExecq(task, systemPrompt, null, ctx, model);
    }

    /**
     * QQ 通道普通消息（execq）缓存优化版 —— 稳定 system 前缀与动态上下文分离。
     * <p>稳定前缀单独作 system 消息（缓存命中点），动态上下文下沉到首条 user 消息。</p>
     */
    public String executeFcExecq(String task, String stableSystemPrompt, String dynamicContext,
                                 ToolContext ctx, String model) {
        this.currentTask = task;
        this.stopped = false;
        this.trajectorySteps.clear();
        enterBypass();
        try {
            FunctionCallingBridge bridge = new FunctionCallingBridge(client);
            bridge.setCritic(getActiveCritic());
            // 上下文快照（供 alarm 工具持久化）
            if (ctx != null) {
                ctx.stableSystemPrompt = stableSystemPrompt;
                ctx.dynamicContext = dynamicContext;
                ctx.model = model;
            }
            return bridge.runWithDispatcher(task, stableSystemPrompt, dynamicContext,
                    ToolDispatcher.buildExecqTools(), model, getToolDispatcher(), ctx, () -> stopped);
        } finally {
            exitBypass();
        }
    }

    /**
     * QQ 通道 execs: 关键字链路 —— 全权限 + 全技能（execq + execs 权限相加的究极体）。
     * <p>pro 模型，工具集为本地全量（buildAllTools），系统提示词含 NapCat 全技能。</p>
     */
    public String executeFcExecs(String task, String systemPrompt, ToolContext ctx, String model) {
        return executeFcExecs(task, systemPrompt, null, ctx, model);
    }

    /**
     * QQ 通道 execs 缓存优化版 —— 稳定 system 前缀与动态上下文分离。
     * <p>稳定前缀单独作 system 消息（缓存命中点），动态上下文下沉到首条 user 消息。</p>
     */
    public String executeFcExecs(String task, String stableSystemPrompt, String dynamicContext,
                                 ToolContext ctx, String model) {
        this.currentTask = task;
        this.stopped = false;
        this.trajectorySteps.clear();
        enterBypass();
        try {
            FunctionCallingBridge bridge = new FunctionCallingBridge(client);
            bridge.setCritic(getActiveCritic());
            // 上下文快照（供 alarm 工具持久化）
            if (ctx != null) {
                ctx.stableSystemPrompt = stableSystemPrompt;
                ctx.dynamicContext = dynamicContext;
                ctx.model = model;
                ctx.execsMode = true;
            }
            return bridge.runWithDispatcher(task, stableSystemPrompt, dynamicContext,
                    ToolDispatcher.buildExecsTools(), model, getToolDispatcher(), ctx, () -> stopped);
        } finally {
            exitBypass();
        }
    }

    /**
     * 多智能体编排入口（opt-in）：pipeline / fanout / expert 三种模式。
     * <p>本地通道用全量工具，execq 通道用 execs 全权限工具（编排为高阶能力，调用方应做主人门禁）。</p>
     */
    public String executeOrchestrated(String task, String modeName, ToolContext ctx, String model) {
        if (task == null || task.trim().isEmpty()) return "(任务为空)";
        AgentOrchestrator orch = getOrchestrator();
        AgentOrchestrator.Mode mode = parseMode(modeName);
        this.currentTask = task;
        this.stopped = false;
        this.trajectorySteps.clear();
        enterBypass();
        try {
            List<ToolDefinition> tools = (ctx != null && ctx.isExecq())
                    ? ToolDispatcher.buildExecsTools()
                    : ToolDispatcher.buildAllTools();
            String m = (model != null && !model.isEmpty()) ? model : AiConfig.getInstance().getAgentModel();
            return orch.orchestrate(task, mode, buildStableSystemPrompt(), tools, m, ctx, () -> stopped);
        } finally {
            exitBypass();
        }
    }

    /** 解析编排模式名，非法输入回退 PIPELINE。 */
    private static AgentOrchestrator.Mode parseMode(String modeName) {
        if (modeName == null) return AgentOrchestrator.Mode.PIPELINE;
        String m = modeName.trim().toLowerCase();
        if (m.startsWith("fanout") || m.equals("fan") || m.equals("ff")) return AgentOrchestrator.Mode.FANOUT_FANIN;
        if (m.startsWith("expert") || m.equals("pool") || m.equals("ep")) return AgentOrchestrator.Mode.EXPERT_POOL;
        return AgentOrchestrator.Mode.PIPELINE;
    }

    /** Shared helper: async skill extraction from trajectorySteps, called by all loops */
    private void triggerSkillExtraction(String task) {
        if (skillExtractor == null || skillBank == null || trajectorySteps.isEmpty()) return;
        final SkillExtractor.AgentTrajectory traj = new SkillExtractor.AgentTrajectory(
                currentTask != null ? currentTask : (task != null ? task : "unknown"),
                "exec");
        traj.overallSuccess = (lastSummary != null);
        for (SkillExtractor.ActionStep step : trajectorySteps) {
            traj.steps.add(step);
        }
        ThreadManager.getInstance().newNamedCached("SkillExtract").submit(() -> skillExtractor.extract(traj));
        trajectorySteps.clear();
    }

    /** Extract skills from conversation via LLM - triggered by skillextract tool */
    String executeSkillExtract(String description) {
        if (skillExtractor == null || skillBank == null) {
            return "[skillextract] 技能系统未初始化，请先配置DeepSeek API";
        }
        String focus = (description != null && !description.trim().isEmpty()) ? description.trim() : null;
        try {
            // 1) Try journal-based extraction first (works if journal was populated)
            PersistenceManager pm = skillBank.getPersistenceManager();
            int count = 0;
            if (pm != null) {
                count = skillExtractor.extractFromJournal(pm, 30);
            }

            // 2) Fallback: trajectory-based extraction from current conversation
            if (count == 0 && !trajectorySteps.isEmpty()) {
                SkillExtractor.AgentTrajectory traj = new SkillExtractor.AgentTrajectory(
                        currentTask != null ? currentTask : (focus != null ? focus : "explicit extraction"),
                        "execq");
                traj.overallSuccess = true;
                for (SkillExtractor.ActionStep step : trajectorySteps) {
                    traj.steps.add(step);
                }
                count = skillExtractor.extractImmediate(traj, focus);
            }

            if (count > 0) {
                return "[skillextract] 成功蒸馏 " + count + " 个技能到技能库。"
                        + (focus != null ? " 蒸馏目标: " + focus : "");
            }
            return "[skillextract] 未发现可蒸馏的新技能。已扫描 "
                    + trajectorySteps.size() + " 条操作轨迹"
                    + (focus != null ? "。蒸馏目标: " + focus : "");
        } catch (Exception e) {
            return "[skillextract] 蒸馏失败: " + e.toString();
        }
    }

    private void sendThinkingBlock(StringBuilder buffer) {
        if (qqExecsCallback == null || buffer == null || buffer.length() == 0) return;
        try {
            String text = buffer.toString().trim();
            if (!text.isEmpty()) qqExecsCallback.accept("[THINKING_BLOCK]" + text);
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Execs] sendThinkingBlock failed: " + e.toString());
        }
    }

    /**
     * 构建静态提示词部分（角色定义、环境、插件、能力清单），缓存复用。
     * <p>本地通道（execs）共用 systemPrompt 作为底座人格提示词，
     * execs 在此基础上追加环境变量信息。execq 通道使用独立的 execqPrompt。</p>
     */
    private String buildStaticPrompt() {
        PromptManager pm = PromptManager.getInstance();
        
        // 底座：与 chat 通道共享系统提示词（用户通过 editprompt 编辑的内容）
        String base = pm.getSystemPrompt();
        
        // 追加：exec/execs 专属的环境变量和能力清单模板
        String template = pm.getAgentStaticPrompt();
        String pluginList = buildPluginList();
        String envInfo = template
            .replace("{os}", SysConsoleExecutor.getOsIdentifier())
            .replace("{shell}", SysConsoleExecutor.getShellType())
            .replace("{jdk}", compilerAvailable() ? "available (dynamic Java compile enabled)" : "JRE only (no Java compile)")
            .replace("{plugins}", pluginList);
        
        return base + "\n\n" + envInfo;
    }
    
    /** Build plugin list from SFW runtime */
    private String buildPluginList() {
        StringBuilder sb = new StringBuilder();
        // 按插件名排序，保证 system 前缀字节稳定（DeepSeek 前缀缓存要求前缀逐字节稳定，Map 迭代顺序不稳定会破坏缓存）
        java.util.List<java.util.Map.Entry<String, Activity>> entries = new java.util.ArrayList<>(Libraries.activities.entrySet());
        entries.sort(java.util.Map.Entry.comparingByKey());
        for (java.util.Map.Entry<String, Activity> entry : entries) {
            String name = entry.getKey();
            Activity act = entry.getValue();
            if (act == selfActivity) continue;
            sb.append("- ").append(name).append("\n");
        }
        return sb.toString();
    }

    /** 稳定 system 前缀（角色定义、环境、插件、能力清单），缓存复用，作为 Prompt Cache 命中点。 */
    private String buildStableSystemPrompt() {
        if (cachedStaticPrompt == null) {
            cachedStaticPrompt = buildStaticPrompt();
        }
        return cachedStaticPrompt;
    }

    /** 动态上下文（历史、记忆、情绪、技能索引等），下沉到首条 user 消息，避免污染 system 前缀。 */
    private String buildDynamicContext() {
        StringBuilder sb = new StringBuilder(2048);

        // === 上一轮 Agent 会话（层层递进记忆链） ===
        if (previousSessionSummary != null) {
            sb.append("## Previous Agent Session (use this context to build on prior work)\n");
            sb.append("Summary of what the agent did in the PREVIOUS session:\n");
            sb.append(previousSessionSummary).append("\n");
        }

        // === Chat 对话历史（从聊天模式同步） ===
        if (chatHistoryContext != null) {
            sb.append("\n## Recent Chat History\n");
            sb.append("The user had this conversation before starting agent mode:\n");
            sb.append(chatHistoryContext).append("\n");
        }

        // === 会话日志（跨会话持久化记忆） ===
        if (journal != null) {
            String journalCtx = journal.buildRecentContext();
            if (journalCtx != null) {
                sb.append("\n").append(journalCtx).append("\n");
            }
        }

        // === 情绪状态（当前心情 + 性别） ===
        if (emotionManager != null) {
            sb.append("\n").append(emotionManager.buildEmotionContext()).append("\n");
        }

        if (memoryContext != null) {
            sb.append("\n").append(memoryContext).append("\n");
        }

        // === Notes: auto-inject relevant knowledge base entries ===
        if (notesContext != null) {
            sb.append("\n").append(notesContext).append("\n");
        }

        // === Corrections: auto-inject relevant correction records (避免重复犯错) ===
        if (correctionsContext != null) {
            sb.append("\n").append(correctionsContext).append("\n");
        }

        // === Skills: compact index (替代全量 content 注入) ===
        if (skillBank != null) {
            String skillsCtx = skillBank.buildCompactIndex("console", 2048);
            if (skillsCtx != null) {
                sb.append("\n").append(skillsCtx).append("\n");
            }
            // 路由提示
            String routeHint = skillBank.getBestRoute(currentTask);
            if (routeHint != null) sb.append(routeHint).append("\n");
        }

        // === Impression: inject persona data from QQ interactions (cross-channel) ===
        if (skillBank != null && skillBank.getPersistenceManager() != null) {
            sair.aiagent.core.PersistenceManager pm = skillBank.getPersistenceManager();
            java.util.List<sair.aiagent.model.ImpressionEntry> imps = pm.getAllImpressions(5);
            if (imps != null && !imps.isEmpty()) {
                sb.append("\n## Known People (from QQ interactions)\n");
                for (sair.aiagent.model.ImpressionEntry imp : imps) {
                    if (imp.hasContent()) {
                        String ctx = imp.toPromptContext();
                        if (ctx != null) sb.append(ctx).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        // === Memory: inject persistent cross-session memories ===
        if (memoryManager != null && currentTask != null) {
            String memCtx = memoryManager.buildContext(currentTask);
            if (memCtx != null) {
                sb.append("\n").append(memCtx).append("\n");
            }
        }

        // === Structured preferences (global, master-set) ===
        if (skillBank != null && skillBank.getPersistenceManager() != null) {
            java.util.List<String[]> prefs = skillBank.getPersistenceManager().listPreferences("global", 0);
            if (prefs != null && !prefs.isEmpty()) {
                sb.append("\n## 结构化偏好/设定（必须遵守）\n");
                for (String[] p : prefs) {
                    if (p != null && p.length >= 2) sb.append("- ").append(p[0]).append(": ").append(p[1]).append("\n");
                }
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    private boolean compilerAvailable() {
        return codeEngine != null && codeEngine.isCompilerAvailable();
    }

    // ==================== History Compression ====================

    /** Compress conversation history if token count exceeds threshold.
     *  Keeps system message intact, replaces old rounds with LLM summary. */
    private void compressHistoryIfNeeded(List<ChatMessage> history) {
        if (historyCompressor == null || history.size() <= 4) return;
        int estimatedTokens = estimateTokens(history);
        if (historyCompressor.shouldCompress(estimatedTokens)) {
            // Only compress non-system messages (skip index 0)
            List<ChatMessage> toCompress = new ArrayList<>();
            for (int i = 1; i < history.size(); i++) {
                toCompress.add(history.get(i));
            }
            String compressed = historyCompressor.compress(toCompress);
            if (compressed != null && !compressed.isEmpty()) {
                ChatMessage sysMsg = history.get(0);
                history.clear();
                history.add(sysMsg);
                history.add(new ChatMessage("user", "[对话历史压缩摘要]\n" + compressed));
                history.add(new ChatMessage("assistant", "已理解之前的对话内容，继续执行当前任务。"));
                AiAgentActivity.debugLog("[HistoryCompressor] compressed history (" + estimatedTokens + " tokens)");
            }
        }
    }

    /** Rough token estimation: ~1 token per 2 chars (CJK) or 4 chars (Latin) */
    private int estimateTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) {
            String c = m.getContent();
            if (c != null) total += c.length();
            if (m.getReasoningContent() != null) total += m.getReasoningContent().length();
        }
        return total / 2 + total / 100; // rough: chars/2 + overhead
    }
}
