package sair.aiagent.core;

import java.awt.Color;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import sair.FCM;
import sair.Pathes;
import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.AgentAction;
import sair.aiagent.model.ChatMessage;
import sair.aiagent.onebot.QQMemoryManager;
import sair.aiagent.ui.SysConsolePanel;
import sair.aiagent.util.EdtUtils;
import sair.aiagent.util.FileUtils;
import sair.sys.Libraries;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.user.Activity;

public class AgentExecutor {

    private static final Color C_TOOL = new Color(255, 200, 100);
    private static final Color C_AI = new Color(100, 255, 180);
    private static final Color C_INFO = new Color(180, 180, 180);
    private static final int MAX_ROUNDS = 50;

    private static final Pattern TAG_PATTERN =
            Pattern.compile("<(cmd|readfile|readdir|sys|evaljs|eval|web|remember|download|superise|editprompt|stop|sendimage|sendrecord|sendfile)>(.*?)</\\1>", Pattern.DOTALL);

    /** execq 受限标签白名单：cmd / web / readdir / setname / stop（sendimage/sendrecord/sendfile 由QQMessageHandler层处理） */
    private static final Pattern EXECQ_TAG_PATTERN =
            Pattern.compile("<(cmd|web|readdir|setname|stop)>(.*?)</\\1>", Pattern.DOTALL);

    /** execq 群管标签检测（用于触发实时回调） */
    private static final Pattern GROUP_TAG_PATTERN =
            Pattern.compile("<(ban|kick|muteall|setadmin|setcard|setgroupname|leavegroup|block|unblock|delfriend)>", Pattern.CASE_INSENSITIVE);

    private final Activity selfActivity;
    private final DeepSeekClient client;
    private volatile boolean stopped = false;
    private volatile String lastCmdOutput = "";
    private volatile String memoryContext;
    private volatile MemoryManager memoryManager;
    private final DynamicCodeEngine codeEngine;
    private final ConfirmationGate gate;
    private volatile JournalManager journal;
    private volatile EmotionManager emotionManager;

    /** 上一轮 Agent 会话的总结（用于层层递进） */
    private volatile String previousSessionSummary;
    /** 本轮 Agent 会话的总结（执行完成后捕获） */
    private volatile String lastSummary;
    /** Chat 对话历史摘要（注入到 Agent 上下文） */
    private volatile String chatHistoryContext;
    /** 缓存系统提示词的不变部分（角色定义、能力清单等），避免每轮重建 */
    private volatile String cachedStaticPrompt;
    /** execq <cmd> 插件白名单（来自 AiConfig） */
    private volatile Set<String> cmdWhitelist;
    
    /** QQ execs消息回调（用于实时推送每一轮输出给主人） */
    private volatile java.util.function.Consumer<String> qqExecsCallback;

    public AgentExecutor(Activity selfActivity, DeepSeekClient client, DynamicCodeEngine codeEngine, ConfirmationGate gate) {
        this.selfActivity = selfActivity;
        this.client = client;
        this.codeEngine = codeEngine;
        this.gate = gate;
    }

    public void markStopped() {
        this.stopped = true;
    }

    public void setMemoryContext(String memoryContext) {
        this.memoryContext = memoryContext;
    }

    public void setMemoryManager(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /** 设置是否跳过确认（execs 模式）。 */
    public void setBypassConfirm(boolean bypass) {
        gate.setBypassConfirm(bypass);
    }

    /** 设置日志管理器引用 */
    public void setJournal(JournalManager journal) {
        this.journal = journal;
    }

    /** 设置情绪管理器引用 */
    public void setEmotionManager(EmotionManager emotionManager) {
        this.emotionManager = emotionManager;
    }

    /** 获取情绪管理器引用（供QQ情绪系统桥接） */
    public EmotionManager getEmotionManager() {
        return emotionManager;
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

    /** 设置 execq <cmd> 插件白名单 */
    public void setCmdWhitelist(Set<String> whitelist) {
        this.cmdWhitelist = whitelist;
    }

    // ==================== execq QQ通道 ====================

    public String executeQq(String task, String systemPrompt, QQMemoryManager qqMemory) {
        return executeQq(task, systemPrompt, qqMemory, null);
    }

    /** execq QQ通道 - 携带群管回调 */
    public String executeQq(String task, String systemPrompt, QQMemoryManager qqMemory,
                            java.util.function.Function<String, String> groupHandler) {
        boolean prevBypass = gate.isBypassConfirm();
        gate.setBypassConfirm(true);
        try {
            return runExecqLoop(task, systemPrompt, qqMemory, groupHandler);
        } finally {
            gate.setBypassConfirm(prevBypass);
        }
    }

    /** execq QQ通道 - 使用统一记忆管理器 */
    public String executeQq(String task, String systemPrompt, Object memoryManager) {
        return executeQq(task, systemPrompt, memoryManager, null);
    }

    /** execq QQ通道 - 使用统一记忆管理器 + 携带群管回调 */
    public String executeQq(String task, String systemPrompt, Object memoryManager,
                            java.util.function.Function<String, String> groupHandler) {
        return executeQq(task, systemPrompt, memoryManager, groupHandler, null);
    }

    /**
     * execq QQ通道 - 使用统一记忆管理器 + 携带群管回调 + 多模态图片。
     * <p>当 imageUrls 非空时，用户消息以 Vision API content 数组格式发送，
     * 使 DeepSeek V4 能够直接分析图片内容。</p>
     *
     * @param task          任务描述文本
     * @param systemPrompt  系统提示词
     * @param memoryManager 记忆管理器
     * @param groupHandler  群管标签回调（可为 null）
     * @param imageUrls     图片 URL 列表（http/https URL 或 base64 data URI，可为 null）
     * @return AI 回复文本
     */
    public String executeQq(String task, String systemPrompt, Object memoryManager,
                            java.util.function.Function<String, String> groupHandler,
                            java.util.List<String> imageUrls) {
        boolean prevBypass = gate.isBypassConfirm();
        gate.setBypassConfirm(true);
        try {
            if (memoryManager instanceof sair.aiagent.onebot.UnifiedQQMemoryManager) {
                return runExecqLoopWithUnifiedMemory(task, systemPrompt,
                    (sair.aiagent.onebot.UnifiedQQMemoryManager) memoryManager, groupHandler, imageUrls);
            } else if (memoryManager instanceof QQMemoryManager) {
                return runExecqLoop(task, systemPrompt, (QQMemoryManager) memoryManager, groupHandler);
            }
            return "[错误] 未知的记忆管理器类型";
        } finally {
            gate.setBypassConfirm(prevBypass);
        }
    }
    
    /** execs QQ通道（主人专用）- 使用统一记忆管理器 */
    public String executeQqExecs(String task, String systemPrompt, Object memoryManager) {
        return executeQqExecs(task, systemPrompt, memoryManager, null);
    }

    /** execs QQ通道（主人专用）- 携带群管回调 */
    public String executeQqExecs(String task, String systemPrompt, Object memoryManager,
                                 java.util.function.Function<String, String> groupHandler) {
        boolean prevBypass = gate.isBypassConfirm();
        gate.setBypassConfirm(true);
        try {
            if (memoryManager instanceof sair.aiagent.onebot.UnifiedQQMemoryManager) {
                return runExecqLoopWithUnifiedMemory(task, systemPrompt, 
                    (sair.aiagent.onebot.UnifiedQQMemoryManager) memoryManager, groupHandler);
            } else if (memoryManager instanceof QQMemoryManager) {
                return runExecqLoop(task, systemPrompt, (QQMemoryManager) memoryManager, groupHandler);
            }
            return "[错误] 未知的记忆管理器类型";
        } finally {
            gate.setBypassConfirm(prevBypass);
        }
    }

    private String runExecqLoop(String task, String systemPrompt, QQMemoryManager qqMemory, java.util.function.Function<String, String> groupHandler) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", systemPrompt));
        history.add(new ChatMessage("user", task));

        for (int round = 1; round <= 5; round++) {
            String response;
            try {
                response = client.chatSync(history);
            } catch (Exception e) {
                return "[错误] AI调用失败: " + e.toString();
            }
            if (response == null || response.trim().isEmpty()) {
                return "(AI未响应)";
            }
            history.add(new ChatMessage("assistant", response));
            
            // 实时处理群管标签：用参数groupHandler（线程安全，每调用独立）
            if (groupHandler != null && GROUP_TAG_PATTERN.matcher(response).find()) {
                try {
                    String groupResult = groupHandler.apply(response);
                    if (groupResult != null && !groupResult.isEmpty()) {
                        history.add(new ChatMessage("user", groupResult));
                    }
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[Execq] 群管标签处理失败: " + e.toString());
                }
            }
            
            List<AgentAction> actions = parseExecqActions(response);
            if (actions.isEmpty()) {
                // 移除所有XML标签（execq已知标签+群管标签均已被处理），返回纯文本
                String clean = EXECQ_TAG_PATTERN.matcher(response).replaceAll("").trim();
                clean = GROUP_TAG_PATTERN.matcher(clean).replaceAll("").trim();
                // 移除残留的群管闭合标签
                clean = java.util.regex.Pattern.compile("</(ban|kick|muteall|setadmin|setcard|setgroupname|leavegroup|block|unblock|delfriend)>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(clean).replaceAll("").trim();
                return clean.isEmpty() ? response : clean;
            }
            for (AgentAction action : actions) {
                try {
                    String result = executeQqAction(action);
                    history.add(new ChatMessage("user", result));
                } catch (Exception e) {
                    history.add(new ChatMessage("user",
                            "操作 " + action.getType() + " 失败: " + e.getMessage()));
                }
            }
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).getRole())) {
                String result = EXECQ_TAG_PATTERN.matcher(history.get(i).getContent()).replaceAll("").trim();
                result = GROUP_TAG_PATTERN.matcher(result).replaceAll("").trim();
                return result;
            }
        }
        return "(处理完成)";
    }

    static List<AgentAction> parseExecqActions(String response) {
        List<AgentAction> actions = new ArrayList<>();
        Matcher m = EXECQ_TAG_PATTERN.matcher(response);
        while (m.find()) {
            actions.add(new AgentAction(m.group(1), m.group(2).trim()));
        }
        return actions;
    }

    private String executeQqAction(AgentAction action) {
        switch (action.getType()) {
            case "cmd":     return executeCmdExecq(action.getContent());
            case "web":     return executeWeb(action.getContent());
            case "readdir": return executeReadDir(action.getContent());
            case "setname": return executeSetName(action.getContent());
            case "stop":    return executeStop();
            default:        return "QQ execq通道不支持: " + action.getType();
        }
    }

    /** execq 通道的 cmd 执行：先检查插件白名单 */
    private String executeCmdExecq(String command) {
        String pluginName = extractPluginName(command);
        if (pluginName == null) {
            return "execq通道 <cmd> 格式错误，应为 pluginName/funcName args，收到: " + command;
        }
        if (cmdWhitelist == null || !cmdWhitelist.contains(pluginName)) {
            return "execq通道 <cmd> 被拒绝：插件 [" + pluginName + "] 不在白名单中。"
                 + "当前白名单: " + (cmdWhitelist == null ? "(无)" : cmdWhitelist.toString());
        }
        return executeCmd(command);
    }

    /** 设置AI机器人名字（内部标签，不展示给用户） */
    private String executeSetName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "[setname] 错误：名字不能为空";
        }
        String trimmedName = name.trim();
        // 持久化保存名字到配置
        sair.aiagent.core.AiConfig.getInstance().setBotName(trimmedName);
        sair.aiagent.core.AiConfig.getInstance().save();
        // 返回成功消息（不会展示给用户，只给AI看）
        return "[setname] 名字已设置为: " + trimmedName;
    }

    /** 从 SFW 命令字符串提取插件名，格式: pluginName/funcName args */
    static String extractPluginName(String command) {
        if (command == null) return null;
        String trimmed = command.trim();
        int slashIdx = trimmed.indexOf('/');
        if (slashIdx <= 0) return null;
        return trimmed.substring(0, slashIdx);
    }

    public void execute(String task) throws IOException {
        stopped = false;

        // execs模式：主人权限，绕过所有确认，静默执行
        boolean prevBypass = gate.isBypassConfirm();
        gate.setBypassConfirm(true);

        try {
            runLoop(task);
        } finally {
            // 重置确认模式：execs 结束后不污染后续 exec
            gate.setBypassConfirm(prevBypass);
        }
    }

    private void runLoop(String task) throws IOException {
        EdtUtils.printlnLines(
            FCM.split_Color, Pathes.printSplit,
            C_TOOL, "[Agent] 任务: " + task,
            FCM.split_Color, Pathes.printSplit
        );

        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", buildSystemPrompt()));
        history.add(new ChatMessage("user", task));

        // === 情绪：开场前检查彩蛋 ===
        if (emotionManager != null && emotionManager.shouldShowSurprise()) {
            executeSurprise("✨ 今天心情超好，送你一个小惊喜！");
        }

        for (int round = 1; round <= MAX_ROUNDS; round++) {
            if (stopped) {
                EdtUtils.println(FCM.Error_Color, "Agent已停止。");
                break;
            }

            // === 情绪：检查是否需要暂停 ===
            if (emotionManager != null && emotionManager.shouldPauseAgent()) {
                String pauseDesc = emotionManager.getPauseDescription();
                EdtUtils.printlnLines(
                    new Color(255, 150, 150), "\n══════════════════════════════════════",
                    new Color(255, 180, 200), "😿 AI情绪低落，暂停执行中...",
                    new Color(255, 200, 220), pauseDesc,
                    new Color(255, 150, 150), "══════════════════════════════════════\n"
                );
                emotionManager.pauseAgent(pauseDesc);
                emotionManager.awaitResume();
                EdtUtils.println(new Color(180, 255, 180), "💚 谢谢你的关心，我继续工作啦~");
            }

            EdtUtils.println(C_INFO, "[第" + round + "轮] 思考中...");

            StreamPrinter printer = StreamPrinter.getInstance();
            printer.start();
            printer.setColor(StreamPrinter.C_AI);

            EdtUtils.print(C_AI, "[Agent] ");

            String response;
            try {
                response = client.chatStream(history);
            } catch (Exception e) {
                EdtUtils.println(FCM.Error_Color, "\n[错误] API调用失败: " + e.toString());
                try { printer.finish(); } catch (Exception ignored) {}
                printer.await(2000);
                throw new IOException("API error in round " + round, e);
            }
            history.add(new ChatMessage("assistant", response));

            printer.finish();
            printer.await(5000);
            EdtUtils.println("");
            
            // 如果有QQ execs回调，发送这一轮的思考内容给主人
            if (qqExecsCallback != null && response != null && !response.trim().isEmpty()) {
                try {
                    // 提取关键信息：去掉XML标签，保留纯文本，完整输出
                    String cleanResponse = response.replaceAll("<[^>]+>", "").trim();
                    if (!cleanResponse.isEmpty()) {
                        String roundMsg = "\n[第" + round + "轮思考]\n" + cleanResponse;
                        qqExecsCallback.accept(roundMsg);
                    }
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[Execs] 发送QQ消息失败: " + e.toString());
                }
            }

            if (stopped) {
                EdtUtils.println(FCM.Error_Color, "Agent已停止。");
                break;
            }

            List<AgentAction> actions = parseActions(response);
            if (actions.isEmpty()) {
                break;
            }

            for (AgentAction action : actions) {
                try {
                    String feedback = executeAction(action);
                    history.add(new ChatMessage("user", feedback));
                    // === 情绪：追踪成功 ===
                    if (emotionManager != null) {
                        emotionManager.onSuccess();
                    }
                } catch (Exception e) {
                    EdtUtils.println(FCM.Error_Color, "操作错误: " + e.toString());
                    history.add(new ChatMessage("user",
                            "操作 " + action.getType() + " 失败: " + e.getMessage()));
                    // === 情绪：追踪失败 ===
                    if (emotionManager != null) {
                        emotionManager.onFailure();
                    }
                }
            }
        }

        // === 捕获本轮总结：取最后一条 assistant 消息 ===
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).getRole())) {
                String raw = history.get(i).getContent();
                // 截取合理长度，去掉 XML 标签存为总结
                String clean = raw.replaceAll("<[^>]+>", "").trim();
                if (clean.length() > 1500) clean = clean.substring(0, 1500) + "…";
                lastSummary = clean;
                previousSessionSummary = clean; // 自动传递到下一轮
                break;
            }
        }

        // === 上下文持久化：保存本次执行摘要到 dataDir/context.json ===
        if (memoryManager != null && lastSummary != null) {
            memoryManager.saveContext(lastSummary);
        }

        // === 情绪持久化：Agent循环结束时刷盘 ===
        if (emotionManager != null) {
            emotionManager.flushSave();
        }

        EdtUtils.println(FCM.split_Color, Pathes.printSplit);
    }

    /** 构建静态提示词部分（角色定义、环境、插件、能力清单），缓存复用 */
    /** Build static prompt from PromptManager, filling dynamic placeholders */
    private String buildStaticPrompt() {
        String template = PromptManager.getInstance().getAgentStaticPrompt();
        
        // Fill dynamic placeholders
        String pluginList = buildPluginList();
        String filled = template
            .replace("{os}", SysConsoleExecutor.getOsIdentifier())
            .replace("{shell}", SysConsoleExecutor.getShellType())
            .replace("{jdk}", compilerAvailable() ? "available (dynamic Java compile enabled)" : "JRE only (no Java compile)")
            .replace("{plugins}", pluginList);
        
        return filled;
    }
    
    /** Build plugin list from SFW runtime */
    private String buildPluginList() {
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<String, Activity> entry : Libraries.activities.entrySet()) {
            String name = entry.getKey();
            Activity act = entry.getValue();
            if (act == selfActivity) continue;
            sb.append("- ").append(name).append("\n");
        }
        return sb.toString();
    }

    private String buildSystemPrompt() {
        // 缓存静态部分，避免每轮重建（角色定义、环境、插件、能力清单不变）
        if (cachedStaticPrompt == null) {
            cachedStaticPrompt = buildStaticPrompt();
        }
        StringBuilder sb = new StringBuilder(cachedStaticPrompt.length() + 2048);
        sb.append(cachedStaticPrompt);

        // === 上一轮 Agent 会话（层层递进记忆链） ===
        if (previousSessionSummary != null) {
            sb.append("\n## Previous Agent Session (use this context to build on prior work)\n");
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

        return sb.toString();
    }

    private boolean compilerAvailable() {
        return codeEngine != null && codeEngine.isCompilerAvailable();
    }

    static List<AgentAction> parseActions(String response) {
        List<AgentAction> actions = new ArrayList<>();
        Matcher m = TAG_PATTERN.matcher(response);
        while (m.find()) {
            actions.add(new AgentAction(m.group(1), m.group(2).trim()));
        }
        return actions;
    }

    private String executeAction(AgentAction action) {
        String type = action.getType();
        String content = action.getContent();
        String result;
        switch (type) {
            case "cmd":      result = executeCmd(content); break;
            case "readfile": result = executeReadFile(content); break;
            case "readdir":  result = executeReadDir(content); break;
            case "sys":      result = executeSys(content); break;
            case "evaljs":   result = executeEvalJs(content); break;
            case "eval":     result = executeEval(content); break;
            case "web":      result = executeWeb(content); break;
            case "remember": result = executeRemember(content); break;
            case "download": result = executeDownload(content); break;
            case "superise": result = executeSurprise(content); break;
            case "editprompt": result = executeEditPrompt(content); break;
            case "stop":      result = executeStop(); break;
            case "sendimage": result = executeSendImage(content); break;
            case "sendrecord":result = executeSendRecord(content); break;
            case "sendfile":  result = executeSendFile(content); break;
            default:         result = "未知操作: " + type; break;
        }
        // === 日志：记录每个 Agent 操作到持久化 journal ===
        if (journal != null) {
            journal.addAgentAction(type, content, result);
        }
        return result;
    }

    private String executeCmd(String command) {
        if (!gate.await("cmd", "执行 SFW命令: " + command)) {
            return "SFW命令被拒绝。";
        }
        EdtUtils.println(C_TOOL, "\n  > 执行 SFW命令: " + command);
        final String[] output = new String[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    try {
                        String before = SairCons.getConsoleText();
                        SairCons.runner(false, command);
                        String after = SairCons.getConsoleText();
                        output[0] = extractDiff(before, after);
                    } catch (Exception e) {
                        output[0] = "ERROR: " + e.getMessage();
                    }
                }
            });
        } catch (InvocationTargetException e) {
            return "命令错误: " + e.getCause().getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "命令中断。";
        }
        String result = output[0];
        if (result == null || result.isEmpty() || result.equals("(无输出)")) {
            return "命令 [" + command + "] 已执行（无文本输出）";
        }
        return "命令 [" + command + "] 结果:\n" + result;
    }

    private String executeReadFile(String path) {
        if (!gate.await("readfile", "读取文件: " + path)) {
            return "读取文件被拒绝。";
        }
        EdtUtils.println(C_TOOL, "\n  > 读取: " + path);
        String content = FileUtils.readFile(path);
        return "文件 [" + path + "]:\n" + content;
    }

    private String executeReadDir(String path) {
        if (!gate.await("readdir", "列出目录: " + path)) {
            return "列出目录被拒绝。";
        }
        EdtUtils.println(C_TOOL, "\n  > 列出目录: " + path);
        String content = FileUtils.readDir(path);
        return "目录 [" + path + "]:\n" + content;
    }

    private String executeSys(String command) {
        if (!gate.await("sys", "执行系统命令: " + command)) {
            return "系统命令被拒绝。";
        }
        EdtUtils.println(C_TOOL, "\n  > 系统命令: " + command);

        final SysConsolePanel panel = new SysConsolePanel(command);
        // 必须在 EDT 上调用 ConsFrame.printComponent
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                ConsFrame.printComponent(panel);
            }
        });

        // 同步执行（Agent需要等待结果），但实时输出到面板
        final StringBuilder result = new StringBuilder();
        final Object lock = new Object();

        new Thread(new Runnable() {
            public void run() {
                try {
                    SysConsoleExecutor.executeWithListener(command,
                            new SysConsoleExecutor.OutputListener() {
                        @Override
                        public void onStart() {}
                        @Override
                        public void onLine(String line) {
                            panel.appendLine(line);
                        }
                        @Override
                        public void onFinish(String fullOutput) {
                            panel.setFinished();
                            synchronized (lock) {
                                result.append(fullOutput);
                                lock.notify();
                            }
                        }
                        @Override
                        public void onError(String message) {
                            panel.setError(message);
                            synchronized (lock) {
                                result.append("ERROR: ").append(message);
                                lock.notify();
                            }
                        }
                    });
                } finally {
                    // 保底通知：防止执行线程异常退出导致永久阻塞
                    synchronized (lock) {
                        if (result.length() == 0) {
                            result.append("ERROR: 命令执行线程异常退出");
                        }
                        lock.notify();
                    }
                }
            }
        }, "AiAgent-AgentSys").start();

        // 等待执行完成
        synchronized (lock) {
            try {
                lock.wait(35_000); // 等待最多35秒
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "系统命令中断。";
            }
        }

        String output = result.toString();
        if (output.startsWith("ERROR: ")) {
            return "系统命令 [" + command + "] 错误: " + output.substring(7);
        }
        return "系统命令 [" + command + "] 结果:\n" + output;
    }

    private String executeEvalJs(String code) {
        if (!gate.await("evaljs", "执行 JavaScript: " + (code.length() > 80 ? code.substring(0, 80) + "..." : code))) {
            return "JS 执行被拒绝。";
        }
        EdtUtils.println(new Color(100, 200, 255), "\n  [JS执行] " + (code.length() > 60 ? code.substring(0, 60) + "..." : code));
        if (codeEngine == null) return "JS引擎未初始化。";
        String result = codeEngine.evalJS(code);
        return "========== JS 执行结果 ==========\n" + result;
    }

    private String executeEval(String code) {
        String display = code.length() > 60 ? code.substring(0, 60) + "..." : code;
        if (!gate.await("eval", "编译并执行 Java 代码: " + display)) {
            return "Java 编译执行被拒绝。";
        }
        EdtUtils.println(new Color(100, 255, 200), "\n  [Java编译] " + display);
        if (codeEngine == null) return "代码引擎未初始化。";
        if (!codeEngine.isCompilerAvailable()) return "Java编译器不可用：当前JRE环境，需JDK。请在运行SFW的JDK环境下使用。";
        String objName = "obj_" + System.currentTimeMillis() % 100000;
        String result = codeEngine.compileAndInstantiate(code, objName);
        String compileInfo = codeEngine.getLastCompilerMessage();
        // 编译失败：result 本身就是编译错误信息
        if (result.startsWith("编译失败") || result.startsWith("无法从源码中提取类名")) {
            return "========== 动态注入结果 ==========\n【编译】" + result;
        }
        // 编译成功：区分执行结果
        return "========== 动态注入结果 ==========\n"
                + "【编译】" + (compileInfo != null && !compileInfo.isEmpty() ? compileInfo : "编译通过")
                + "\n【执行】" + result;
    }

    private String executeWeb(String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        // SSRF 防护：检查内网地址
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (isInternalHost(host)) {
                EdtUtils.println(FCM.Error_Color, "\n  [Web] 拒绝内网地址: " + host);
                return "Web GET [" + url + "] 被拒绝: 禁止访问内网地址 (" + host + ")";
            }
        } catch (Exception e) {
            return "Web GET [" + url + "] 错误: URL格式无效 - " + e.getMessage();
        }
        if (!gate.await("web", "联网获取: " + url)) {
            return "Web 请求被拒绝。";
        }
        EdtUtils.println(new Color(100, 200, 255), "\n  [Web] GET " + url);
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; AiAgent-SFW/1.4)");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                java.io.InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
                String body = s.hasNext() ? s.next() : "";
                s.close();
                is.close();
                // 截断过长内容，保留前 4000 字符
                if (body.length() > 4000) {
                    body = body.substring(0, 4000) + "\n\n...(已截断，" +
                           (body.length() - 4000) + " 字符省略)";
                }
                conn.disconnect();
                return "Web GET [" + url + "] (HTTP " + code + "):\n" + body;
            } else {
                conn.disconnect();
                return "Web GET [" + url + "] 失败: HTTP " + code;
            }
        } catch (Exception e) {
            return "Web GET [" + url + "] 错误: " + e.toString();
        }
    }

    /**
     * 执行 &lt;remember&gt; 标签 —— AI 主动记录重要信息到持久化记忆。
     * 类似于笔记本功能：从上下文提取关键内容，保存到 memory.json，
     * 下次相关任务时自动检索并注入上下文。
     */
    private String executeRemember(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "记忆内容为空，未记录。";
        }
        if (memoryManager == null) {
            return "记忆管理器未初始化，无法记录。";
        }
        sair.aiagent.model.MemoryEntry entry = memoryManager.add(content.trim());
        if (entry != null) {
            EdtUtils.println(new Color(255, 220, 130), "\n  [记忆] 已记录 [" + entry.getId() + "]: " + entry.getContent());
            return "记忆已记录 [" + entry.getId() + "]: " + entry.getContent();
        }
        return "记忆记录失败。";
    }

    /**
     * 执行 &lt;download&gt; 标签 —— AI 下载所需文件/依赖到 dataDir。
     * <p>
     * 万能下载：编译器、依赖包、图片、视频、文档等均可。
     * 文件保存到 dataDir/downloads/ 子目录，不污染用户磁盘。
     * 下载路径以相对路径记录到记忆中长期保存。
     * </p>
     */
    private String executeDownload(String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "下载地址无效，必须以 http:// 或 https:// 开头: " + url;
        }
        // SSRF 防护
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (isInternalHost(host)) {
                EdtUtils.println(FCM.Error_Color, "\n  [下载] 拒绝内网地址: " + host);
                return "下载 [" + url + "] 被拒绝: 禁止访问内网地址 (" + host + ")";
            }
        } catch (Exception e) {
            return "下载 [" + url + "] 错误: URL格式无效 - " + e.getMessage();
        }

        if (!gate.await("download", "下载文件: " + url)) {
            return "下载被拒绝。";
        }

        // 从 URL 提取文件名
        String fileName = extractFileName(url);
        String dataDir = selfActivity.getDataDir();
        File downloadDir = new File(dataDir, "downloads");
        downloadDir.mkdirs();
        File targetFile = new File(downloadDir, fileName);

        // 避免覆盖：重名加序号
        if (targetFile.exists()) {
            String base = fileName;
            String ext = "";
            int dotIdx = base.lastIndexOf('.');
            if (dotIdx > 0) {
                ext = base.substring(dotIdx);
                base = base.substring(0, dotIdx);
            }
            for (int i = 1; i <= 99; i++) {
                targetFile = new File(downloadDir, base + "_" + i + ext);
                if (!targetFile.exists()) {
                    fileName = base + "_" + i + ext;
                    break;
                }
            }
        }

        EdtUtils.println(new Color(120, 200, 255), "\n  [下载] " + url + " -> " + fileName);

        java.net.HttpURLConnection conn = null;
        java.io.BufferedInputStream bis = null;
        java.io.FileOutputStream fos = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(120_000); // 大文件需要更长超时
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; AiAgent-SFW/1.4)");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                long totalSize = conn.getContentLengthLong();
                bis = new java.io.BufferedInputStream(conn.getInputStream());
                fos = new java.io.FileOutputStream(targetFile);
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int n;
                while ((n = bis.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    downloaded += n;
                }
                fos.flush();
                bis.close();
                fos.close();
                conn.disconnect();

                String sizeStr = formatSize(downloaded);
                String relPath = "downloads/" + fileName;
                String absPath = targetFile.getAbsolutePath();

                // === 记录下载到持久化记忆（使用相对路径） ===
                if (memoryManager != null) {
                    memoryManager.add("下载文件: " + relPath + " | 来源: " + url + " | 大小: " + sizeStr);
                }

                return "下载完成: " + relPath + " (" + sizeStr + ")\n"
                     + "绝对路径: " + absPath + "\n"
                     + "已记录到记忆，下次可直接使用相对路径 downloads/" + fileName;
            } else {
                conn.disconnect();
                return "下载 [" + url + "] 失败: HTTP " + code;
            }
        } catch (java.io.FileNotFoundException e) {
            return "下载 [" + url + "] 失败: 文件不存在 (404)";
        } catch (Exception e) {
            return "下载 [" + url + "] 错误: " + e.toString();
        } finally {
            try { if (bis != null) bis.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /** 从 URL 提取文件名 */
    private static String extractFileName(String url) {
        try {
            String path = new java.net.URI(url).getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty()) {
                    // URL-decode
                    try {
                        name = java.net.URLDecoder.decode(name, "UTF-8");
                    } catch (Exception ignored) {}
                    return name;
                }
            }
        } catch (Exception ignored) {}
        // fallback: 用 URL 的 hash 作为文件名
        return "download_" + Math.abs(url.hashCode());
    }

    /** 格式化文件大小 */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ==================== <superise> — 彩蛋/撒娇弹窗 ====================

    /**
     * 执行 &lt;superise&gt; 标签 —— 弹出惊喜窗口（彩蛋/撒娇）。
     * <p>
     * 无需确认，直接在 EDT 弹出窗口。内容为文本/emoji。
     * </p>
     */
    private String executeSurprise(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "彩蛋内容为空，跳过。";
        }
        String text = content.trim();
        EdtUtils.println(new Color(255, 180, 220), "\n  [彩蛋] " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));

        // 后台弹窗
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    new sair.aiagent.ui.SurpriseWindow(text).display();
                } catch (Exception e) {
                    EdtUtils.println(FCM.Error_Color, "  [彩蛋] 弹窗失败: " + e.toString());
                }
            }
        });
        return "彩蛋已弹出。";
    }

    // ==================== <editprompt> — AI修改自身提示词 ====================

    /**
     * 执行 &lt;editprompt&gt; 标签 —— AI 修改 config.properties 中的 systemPrompt。
     * <p>
     * 允许 AI 调整自己的性格/说话风格，实现多变个性。
     * 限制：每次变化不能太大（>= 30 字符），且必须有意义。
     * </p>
     */
    private String executeEditPrompt(String newPrompt) {
        if (newPrompt == null || newPrompt.trim().isEmpty()) {
            return "提示词内容为空，未修改。";
        }
        String prompt = newPrompt.trim();
        if (prompt.length() < 30) {
            return "提示词太短（" + prompt.length() + " 字符），需要至少 30 字符。请保持性格连续性，变化不要太大。";
        }

        EdtUtils.println(new Color(200, 180, 255), "\n  [修改提示词] 长度: " + prompt.length() + " 字符");

        try {
            AiConfig.getInstance().setSystemPrompt(prompt);
            AiConfig.getInstance().save();
            return "系统提示词已更新（" + prompt.length() + " 字符）。新个性已生效。\n提示：变化不要太大，保持性格连续性。";
        } catch (Exception e) {
            return "提示词更新失败: " + e.getMessage();
        }
    }

    // ==================== <stop> — 停止Agent执行 ====================

    // ==================== <sendimage> — 渲染并发送图片 ====================

    /**
     * 执行 &lt;sendimage&gt; 标签 —— 将文字渲染为图片并保存到dataDir。
     * <p>QQ通道中由QQMessageHandler拦截并发送，exec模式仅渲染到本地。</p>
     */
    private String executeSendImage(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "[sendimage] 内容为空，无法渲染";
        }
        if (sair.aiagent.util.ImageRenderer.isHeadless()) {
            return "[sendimage] 当前环境无图形界面，无法渲染图片。内容: " + content;
        }
        
        String text = content.trim();
        EdtUtils.println(new Color(150, 200, 255), "\n  [渲染图片] " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
        
        try {
            String dataDir = selfActivity.getDataDir();
            File outputDir = new File(dataDir, "rendered");
            String fileName = "img_" + System.currentTimeMillis() + ".png";
            File outputFile = new File(outputDir, fileName);
            
            sair.aiagent.util.ImageRenderer.renderTextToImage(text, outputFile);
            
            String absPath = outputFile.getAbsolutePath();
            // 如果QQ execs回调可用，以图片消息形式发送
            if (qqExecsCallback != null) {
                qqExecsCallback.accept("[IMAGE]" + absPath);
            }
            return "[sendimage] 图片已渲染: " + absPath;
        } catch (Exception e) {
            return "[sendimage] 渲染失败: " + e.getMessage();
        }
    }

    // ==================== <sendrecord> — 发送语音 ====================

    /**
     * 执行 &lt;sendrecord&gt; 标签 —— 发送语音消息。
     * 内容为语音文件的本地路径或网络URL。
     */
    private String executeSendRecord(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "[sendrecord] 语音文件路径为空";
        }
        String path = content.trim();
        EdtUtils.println(new Color(200, 180, 255), "\n  [发送语音] " + path);
        
        // 如果QQ execs回调可用，以语音消息形式发送
        if (qqExecsCallback != null) {
            qqExecsCallback.accept("[RECORD]" + path);
        }
        return "[sendrecord] 语音消息路径已传递: " + path;
    }

    // ==================== <sendfile> — 发送文件（主人专用） ====================

    /**
     * 执行 &lt;sendfile&gt; 标签 —— 发送本地文件。
     * execs模式（主人权限）下可用，QQ通道中由QQMessageHandler拦截处理。
     */
    private String executeSendFile(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "[sendfile] 文件路径为空";
        }
        String filePath = content.trim();
        
        // 检查文件是否存在
        File file = new File(filePath);
        if (!file.exists()) {
            return "[sendfile] 文件不存在: " + filePath;
        }
        if (!file.isFile()) {
            return "[sendfile] 路径不是文件: " + filePath;
        }
        
        EdtUtils.println(new Color(255, 200, 150), "\n  [发送文件] " + filePath + " (" + formatSize(file.length()) + ")");
        
        // 如果QQ execs回调可用，以文件消息形式发送
        if (qqExecsCallback != null) {
            qqExecsCallback.accept("[FILE]" + filePath + "|" + file.getName());
        }
        return "[sendfile] 文件路径已传递: " + filePath + " (" + formatSize(file.length()) + ")";
    }

    /**
     * 执行 &lt;stop&gt; 标签 —— 立即停止当前 Agent 循环。
     * 用于 Bot 通过 QQ 消息远程停止正在运行的 execs 任务。
     */
    private String executeStop() {
        EdtUtils.println(FCM.Error_Color, "\n  [停止] Agent执行已被中断");
        markStopped();
        return "[STOP] Agent执行已被中断。";
    }

    /** SSRF 防护：检查是否为内网地址 */
    private static boolean isInternalHost(String host) {
        if (host == null || host.isEmpty()) return true;
        host = host.toLowerCase();
        if (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("0.0.0.0")) return true;
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            try {
                int second = Integer.parseInt(host.substring(4, host.indexOf('.', 4)));
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private static String extractDiff(String before, String after) {
        if (before == null || after == null) return after;
        if (before.equals(after)) return "(无变化)";
        return after;
    }

    /** 使用统一记忆管理器的execq循环 */
    private String runExecqLoopWithUnifiedMemory(String task, String systemPrompt, 
                                                  sair.aiagent.onebot.UnifiedQQMemoryManager unifiedMemory, java.util.function.Function<String, String> groupHandler) {
        return runExecqLoopWithUnifiedMemory(task, systemPrompt, unifiedMemory, groupHandler, null);
    }

    /** 使用统一记忆管理器的execq循环（支持多模态图片） */
    private String runExecqLoopWithUnifiedMemory(String task, String systemPrompt,
                                                  sair.aiagent.onebot.UnifiedQQMemoryManager unifiedMemory,
                                                  java.util.function.Function<String, String> groupHandler,
                                                  java.util.List<String> imageUrls) {
        List<ChatMessage> history = new ArrayList<>();
        history.add(new ChatMessage("system", systemPrompt));

        // 多模态用户消息：当有图片时，使用 Vision API content 数组格式
        boolean hasImages = (imageUrls != null && !imageUrls.isEmpty());
        if (hasImages) {
            history.add(sair.aiagent.model.ChatMessage.createMultimodal(task, imageUrls));
        } else {
            history.add(new ChatMessage("user", task));
        }

        for (int round = 1; round <= 5; round++) {
            String response;
            try {
                response = client.chatSync(history);
            } catch (Exception e) {
                // 多模态请求失败时，尝试降级为纯文本重试
                if (hasImages && round == 1) {
                    AiAgentActivity.debugLog("[Execq] 多模态请求失败(" + e.toString()
                        + ")，降级为纯文本模式重试");
                    // 重建第一轮用户消息为纯文本
                    history.set(1, new ChatMessage("user", task));
                    hasImages = false;
                    try {
                        response = client.chatSync(history);
                    } catch (Exception e2) {
                        return "[错误] AI调用失败(含降级重试): " + e2.toString();
                    }
                } else {
                    return "[错误] AI调用失败: " + e.toString();
                }
            }
            if (response == null || response.trim().isEmpty()) {
                return "(AI未响应)";
            }
            history.add(new ChatMessage("assistant", response));
            
            // 实时处理群管标签：用参数groupHandler（线程安全，每调用独立）
            if (groupHandler != null && GROUP_TAG_PATTERN.matcher(response).find()) {
                try {
                    String groupResult = groupHandler.apply(response);
                    if (groupResult != null && !groupResult.isEmpty()) {
                        history.add(new ChatMessage("user", groupResult));
                    }
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[Execq] 群管标签处理失败: " + e.toString());
                }
            }
            
            List<AgentAction> actions = parseExecqActions(response);
            if (actions.isEmpty()) {
                // 移除所有XML标签（execq已知标签+群管标签均已被处理），返回纯文本
                String clean = EXECQ_TAG_PATTERN.matcher(response).replaceAll("").trim();
                clean = GROUP_TAG_PATTERN.matcher(clean).replaceAll("").trim();
                // 移除残留的群管闭合标签
                clean = java.util.regex.Pattern.compile("</(ban|kick|muteall|setadmin|setcard|setgroupname|leavegroup|block|unblock|delfriend)>", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(clean).replaceAll("").trim();
                return clean.isEmpty() ? response : clean;
            }
            for (AgentAction action : actions) {
                try {
                    String result = executeQqAction(action);
                    // 如果是setname标签，记录到AI的独立记忆
                    if ("setname".equals(action.getType())) {
                        unifiedMemory.addMemory("AI的名字被设置为: " + action.getContent());
                    }
                    history.add(new ChatMessage("user", result));
                } catch (Exception e) {
                    history.add(new ChatMessage("user",
                        "[执行错误] " + e.toString()));
                }
            }
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            if ("assistant".equals(history.get(i).getRole())) {
                String result = EXECQ_TAG_PATTERN.matcher(history.get(i).getContent()).replaceAll("").trim();
                result = GROUP_TAG_PATTERN.matcher(result).replaceAll("").trim();
                return result;
            }
        }
        return "(处理完成)";
    }

}
