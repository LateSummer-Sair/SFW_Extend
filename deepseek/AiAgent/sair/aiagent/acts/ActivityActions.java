package sair.aiagent.acts;

import java.awt.Color;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

import sair.aiagent.util.EdtUtils;

import sair.FCM;
import sair.aiagent.AiAgentActivity;
import sair.aiagent.core.StreamPrinter;
import sair.aiagent.core.JournalManager;
import sair.aiagent.core.EmotionManager;
import sair.aiagent.core.SysConsoleExecutor;
import sair.aiagent.model.ChatMessage;
import sair.sys.SairCons;

/**
 * 命令动作实现层 —— 每个控制台命令对应一个公开方法。
 * 从 {@link AiAgentActivity#main(String, String)} 路由到此。
 *
 * <h3>职责</h3>
 * <ul>
 *   <li>将 Activity 的路由转换为具体业务逻辑，</li>
 *   <li>管理 Chat / Agent / 配置 / 记忆 / 反射 / 系统命令 / 文件读写，</li>
 *   <li>所有控制台输出通过 EDT-safe 包装器。</li>
 * </ul>
 */
public class ActivityActions {

    // ==================== 颜色常量 ====================

    static final Color C_INFO   = new Color(180, 180, 180);
    static final Color C_AI     = new Color(100, 255, 180);
    static final Color C_STREAM = new Color(200, 220, 255);
    static final Color C_MEM    = new Color(255, 220, 130);
    static final Color C_SYS    = new Color(120, 200, 255);
    static final Color C_ERR    = FCM.Error_Color;

    // ==================== Activity 引用 ====================

    private final AiAgentActivity act;

    public ActivityActions(AiAgentActivity act) {
        this.act = act;
    }

    // ==================== 命令路由 ====================

    public Object route(String funcName, String args) {
        AiAgentActivity.debugLog("路由: " + funcName);
        switch (funcName) {
            case "chat":       return handleChat(args);
            case "exec":       return handleExec(args);
            case "execs":      return handleExecs(args);
            case "setkey":     return handleSetKey(args);
            case "seturl":     return handleSetUrl(args);
            case "setmodel":   return handleSetModel(args);
            case "setprompt":  return handleSetPrompt(args);
            case "showprompt": return handleShowPrompt();
            case "memories":   return handleMemories();
            case "forget":     return handleForget(args);
            case "forgetall":  return handleForgetAll();
            case "yes":        return handleYes();
            case "no":         return handleNo();
            case "info":       return handleInfo();
            case "reset":      return handleReset();
            case "stop":       return handleStop();
            case "mood":       return handleMood();
            // === OneBot QQ 通道 ===
            case "execq":               return handleExecq(args);
            case "onebotconnect":      return handleOneBotConnect();
            case "onebotdisconnect":   return handleOneBotDisconnect();
            case "onebotstatus":       return handleOneBotStatus();
            case "onebotsetport":      return handleOneBotSetPort(args);
            case "onebotsettoken":     return handleOneBotSetToken(args);
            case "onebotsetselfid":    return handleOneBotSetSelfId(args);
            case "onebotsetprompt":   return handleOneBotSetPrompt(args);
            case "onebotshowprompt":  return handleOneBotShowPrompt();
            case "onebotwhitelist":      return handleOneBotWhitelist();
            case "onebotwhitelistadd":   return handleOneBotWhitelistAdd(args);
            case "onebotwhitelistremove": return handleOneBotWhitelistRemove(args);
            // === 主动查看配置 ===
            case "onebotenableproactive": return handleOneBotEnableProactive();
            case "onebotdisableproactive": return handleOneBotDisableProactive();
            case "onebotaddgroup":       return handleOneBotAddGroup(args);
            case "onebotremovegroup":    return handleOneBotRemoveGroup(args);
            case "onebotlistgroups":     return handleOneBotListGroups();
            default:           return false;
        }
    }

    // ==================== 配置命令 ====================

    public Object handleSetKey(String args) {
        if (isEmpty(args)) return err("用法: ai/setkey [API密钥]");
        act.getConfig().setApiKey(args);
        act.getConfig().save();
        println(C_INFO, "API密钥已设置: " + act.getConfig().getMaskedKey());
        return true;
    }

    public Object handleSetUrl(String args) {
        if (isEmpty(args)) return err("用法: ai/seturl [地址]");
        act.getConfig().setApiUrl(args);
        act.getConfig().save();
        println(C_INFO, "API地址 -> " + act.getConfig().getApiUrl());
        return true;
    }

    public Object handleSetModel(String args) {
        if (isEmpty(args)) return err("用法: ai/setmodel [模型名]");
        act.getConfig().setModel(args);
        act.getConfig().save();
        println(C_INFO, "模型 -> " + act.getConfig().getModel());
        return true;
    }

    public Object handleSetPrompt(String args) {
        if (isEmpty(args)) return err("用法: ai/setprompt [提示词]");
        act.getConfig().setSystemPrompt(args);
        act.getConfig().save();
        println(C_INFO, "提示词已更新 (长度: " + act.getConfig().getSystemPrompt().length() + ")");
        return true;
    }

    public Object handleShowPrompt() {
        print(C_AI, "[系统提示词]");
        println(C_INFO, "\n" + act.getConfig().getSystemPrompt());
        return true;
    }

    public Object handleInfo() {
        println(C_INFO, "== AiAgent 配置 ==");
        println(C_INFO, "API地址 : " + act.getConfig().getApiUrl());
        println(C_INFO, "API密钥 : " + act.getConfig().getMaskedKey());
        println(C_INFO, "模型    : " + act.getConfig().getModel());
        println(C_INFO, "提示词  : " + act.getConfig().getSystemPrompt().length() + " 字符");
        println(C_INFO, "对话    : " + act.getHistory().size() + " 条/~"
                + act.getHistory().estimateTotalTokens() + " tokens");
        println(C_INFO, "记忆    : " + act.getMemory().size() + " 条");
        println(C_INFO, "系统    : " + SysConsoleExecutor.getOsIdentifier()
                + " | Shell: " + SysConsoleExecutor.getShellType());
        println(C_INFO, "数据目录: " + act.getDataDir());
        return true;
    }

    public Object handleReset() {
        act.getHistory().clear();
        act.getAgent().setPreviousSessionSummary(null);
        println(C_INFO, "对话历史与Agent记忆链已重置。");
        return true;
    }

    public Object handleStop() {
        boolean hadActive = act.getActiveThread() != null;
        act.stopActivePrinter();
        if (hadActive) {
            println(C_ERR, "已停止。");
        } else {
            println(C_INFO, "没有正在运行的输出。");
        }
        return true;
    }

    // ==================== 记忆命令 ====================

    public Object handleMemories() {
        println(C_MEM, act.getMemory().listAll());
        return true;
    }

    public Object handleForget(String args) {
        if (isEmpty(args)) return err("用法: ai/forget [ID]");
        try {
            int id = Integer.parseInt(args.trim());
            if (act.getMemory().remove(id)) {
                println(C_INFO, "已遗忘记忆 [" + id + "]。");
            } else {
                println(C_ERR, "记忆 [" + id + "] 未找到。");
            }
        } catch (NumberFormatException e) {
            return err("ID必须是数字。");
        }
        return true;
    }

    public Object handleForgetAll() {
        act.getMemory().clear();
        println(C_INFO, "所有记忆已清空。");
        return true;
    }

    // ==================== 确认命令 ====================

    public Object handleYes() {
        if (act.getGate().isAwaiting()) {
            act.getGate().confirm(true);
            String type = act.getGate().getPendingType();
            String label = type != null ? "[" + type + "] " : "";
            println(new Color(100, 255, 100), label + "已确认。正在执行...");
        } else {
            println(C_INFO, "没有待处理的高危操作。");
        }
        return true;
    }

    public Object handleNo() {
        if (act.getGate().isAwaiting()) {
            act.getGate().confirm(false);
            String type = act.getGate().getPendingType();
            String label = type != null ? "[" + type + "] " : "";
            println(C_ERR, label + "已拒绝。");
        } else {
            println(C_INFO, "没有待处理的高危操作。");
        }
        return true;
    }

    // ==================== Chat 模式 ====================

    public Object handleChat(String args) {
        AiAgentActivity.debugLog("handleChat 进入: " + args);
        if (isEmpty(args)) return err("用法: ai/chat [问题]");
        if (!checkKey()) { AiAgentActivity.debugLog("handleChat: 未设置API密钥"); return false; }

        final String userMsg = args.trim();
        AiAgentActivity.debugLog("handleChat: 用户消息=" + userMsg);

        // === 情绪：检测用户消息中的情绪 ===
        act.getEmotionManager().detectEmotion(userMsg);

        // === 情绪：检查Agent是否暂停，若是则处理互动 ===
        if (act.getEmotionManager().isPaused()) {
            String result = act.getEmotionManager().handleUserInteraction(userMsg);
            if ("comforted".equals(result)) {
                println(new Color(180, 255, 180), "💚 AI感受到你的安慰，心情平静下来了~");
            } else if ("guided".equals(result)) {
                println(new Color(180, 220, 255), "📝 指导已记录，AI继续工作中...");
            }
            return true;
        }

        println(C_INFO, "\n[你] " + userMsg);

        act.stopActivePrinter();

        final String memoryContext = act.getMemory().buildContext(userMsg);
        if (memoryContext != null) {
            println(C_MEM, "[记忆] 找到相关记忆，已注入上下文。");
        }

        final StreamPrinter printer = StreamPrinter.getInstance();
        printer.start();

        print(C_AI, "[AI] ");
        AiAgentActivity.debugLog("handleChat: 启动后台线程");

        act.setActiveThread(new Thread(new Runnable() {
            public void run() {
                AiAgentActivity.debugLog("ChatThread: 开始");
                final String[] capturedResponse = new String[1];  // 用于上下文持久化
                try {
                    act.getHistory().add(new ChatMessage("user", userMsg));
                    String sysPrompt = act.getConfig().getSystemPrompt();

                    // 注入 Agent 最近的执行结果（Agent ↔ Chat 同步）
                    String agentSummary = act.getAgent().getLastSummary();
                    if (agentSummary == null) {
                        // 跨会话回退：从 context.json 加载 AI 上次的思维摘要
                        agentSummary = act.getMemory().loadContext();
                    }
                    if (agentSummary != null) {
                        sysPrompt += "\n\n## Recent Agent Execution\n"
                                   + "The following is what you (the assistant/agent) did recently on this system.\n"
                                   + "Use this context to answer user questions about prior agent actions:\n"
                                   + agentSummary + "\n";
                    }

                    // === 注入会话日志（跨会话持久化记忆） ===
                    String journalCtx = act.getJournal().buildRecentContext();
                    if (journalCtx != null) {
                        sysPrompt += "\n" + journalCtx;
                    }

                    List<ChatMessage> messages = act.getHistory().buildFullContext(sysPrompt);
                    if (memoryContext != null) {
                        ChatMessage sysMsg = messages.get(0);
                        String enhanced = sysMsg.getContent() + "\n\n" + memoryContext;
                        messages.set(0, new ChatMessage("system", enhanced));
                    }

                    AiAgentActivity.debugLog("ChatThread: 调用chatStream...");
                    String fullResponse = act.getClient().chatStream(messages);
                    AiAgentActivity.debugLog("ChatThread: chatStream返回, 长度=" + fullResponse.length());
                    capturedResponse[0] = fullResponse;
                    act.getHistory().add(new ChatMessage("assistant", fullResponse));
                    // === 日志：记录用户消息 + AI 回复 ===
                    act.getJournal().addEntry("user", "chat", userMsg, null);
                    act.getJournal().addEntry("assistant", "chat", fullResponse, null);
                } catch (Exception e) {
                    AiAgentActivity.debugLog("ChatThread: 错误: " + e.toString());
                    println(C_ERR, "\n[错误] 对话失败: " + e.toString());
                    if (e.getMessage() != null) {
                        println(C_ERR, "        " + e.getMessage());
                    }
                } finally {
                    printer.finish();
                    printer.await(5000);
                    println("");
                    if (act.getActiveThread() == Thread.currentThread()) act.setActiveThread(null);
                    // === 上下文持久化：保存 Chat 摘要到 dataDir/context.json ===
                    if (capturedResponse[0] != null) {
                        String clean = capturedResponse[0].replaceAll("<[^>]+>", "").trim();
                        if (clean.length() > 1500) clean = clean.substring(0, 1500) + "...";
                        act.getMemory().saveContext(clean);
                    }
                    AiAgentActivity.debugLog("ChatThread: 结束");
                }
            }
        }, "AiAgent-Chat"));
        act.getActiveThread().setDaemon(true);
        act.getActiveThread().start();

        AiAgentActivity.debugLog("handleChat: 完成");
        return true;
    }

    // ==================== Agent 模式 ====================

    public Object handleExec(String args) {
        AiAgentActivity.debugLog("handleExec 进入: " + args);
        if (isEmpty(args)) return err("用法: ai/exec [任务描述]");
        if (!checkKey()) { AiAgentActivity.debugLog("handleExec: 未设置API密钥"); return false; }

        final String execTask = args.trim();

        // === 情绪：检测用户消息中的情绪 ===
        act.getEmotionManager().detectEmotion(execTask);

        // === 情绪：检查Agent是否暂停，若是则处理互动 ===
        if (act.getEmotionManager().isPaused()) {
            String result = act.getEmotionManager().handleUserInteraction(execTask);
            if ("comforted".equals(result)) {
                println(new Color(180, 255, 180), "💚 AI感受到你的安慰，心情平静下来了~");
            } else if ("guided".equals(result)) {
                println(new Color(180, 220, 255), "📝 指导已记录，AI继续工作中...");
            }
            return true;
        }

        act.stopActivePrinter();

        final String memoryContext = act.getMemory().buildContext(execTask);
        if (memoryContext != null) {
            println(C_MEM, "[记忆] 找到相关记忆，已注入上下文。");
        }

        act.getAgent().setMemoryContext(memoryContext);
        act.getAgent().setMemoryManager(act.getMemory());

        // 注入 Chat 对话历史到 Agent 上下文（Chat ↔ Agent 同步）
        act.getAgent().setChatHistoryContext(buildChatContextForAgent());
        // === 注入 journal 引用（Agent 需要它来记录操作并可读取历史） ===
        act.getAgent().setJournal(act.getJournal());
        // === 注入 EmotionManager 引用 ===
        act.getAgent().setEmotionManager(act.getEmotionManager());

        final String task = execTask;

        AiAgentActivity.debugLog("handleExec: 启动后台线程");
        final JournalManager journal = act.getJournal();

        act.setActiveThread(new Thread(new Runnable() {
            public void run() {
                AiAgentActivity.debugLog("ExecThread: 开始");
                try {
                    act.getAgent().execute(task);
                    AiAgentActivity.debugLog("ExecThread: agent.execute() 完成");
                    // === 日志：记录 Agent 任务 + 执行摘要 ===
                    String agentSummary = act.getAgent().getLastSummary();
                    journal.addEntry("agent", "exec", task, agentSummary);
                } catch (Exception e) {
                    AiAgentActivity.debugLog("ExecThread: 错误: " + e.toString());
                    println(C_ERR, "[错误] Agent执行失败: " + e.toString());
                    if (e.getMessage() != null) {
                        println(C_ERR, "        " + e.getMessage());
                    }
                } finally {
                    StreamPrinter.getInstance().flushAndStop();
                    if (act.getActiveThread() == Thread.currentThread()) act.setActiveThread(null);
                    AiAgentActivity.debugLog("ExecThread: 结束");
                }
            }
        }, "AiAgent-Exec"));
        act.getActiveThread().setDaemon(true);
        act.getActiveThread().start();

        return true;
    }

    /** execs 模式 —— 与 exec 相同，但跳过所有高危操作确认。 */
    public Object handleExecs(String args) {
        act.getGate().setBypassConfirm(true);
        println(C_INFO, "[execs] 安全模式关闭 — 所有高危操作将自动执行，不再提示确认。");
        return handleExec(args);
    }

    // ==================== 心情查询 ====================

    /** 查询AI当前心情 */
    public Object handleMood() {
        EmotionManager em = act.getEmotionManager();
        String mood = em.getMoodEmoji() + " " + em.getMoodDescription();
        println(new Color(255, 200, 220), mood);
        println(C_INFO, "开心值: " + em.getHappiness() + "/100"
                + " | 连续失败: " + em.getConsecutiveFailures()
                + " | 连续夸赞: " + em.getConsecutivePraise()
                + " | 性别: " + em.getGender());
        return true;
    }

    // ==================== 上下文同步 ====================

    /**
     * 从 Chat 对话历史构建 Agent 可读的上下文摘要。
     * 取最近 6 条消息，格式化后注入到 Agent system prompt。
     */
    private String buildChatContextForAgent() {
        List<ChatMessage> all = act.getHistory().getAll();
        if (all.isEmpty()) return null;

        int start = Math.max(0, all.size() - 6);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < all.size(); i++) {
            ChatMessage m = all.get(i);
            String role = m.getRole();
            String content = m.getContent();
            if (content == null) content = "";
            if (content.length() > 200) {
                content = content.substring(0, 200) + "…";
            }
            String label = "user".equals(role) ? "User" : "assistant".equals(role) ? "Assistant" : role;
            if (i == start) {
                sb.append(label).append(": ").append(content);
            } else {
                sb.append("\n").append(label).append(": ").append(content);
            }
        }
        return sb.toString();
    }

    // ==================== EDT-safe 输出（委托 EdtUtils） ====================

    static void println(Color c, String msg) {
        EdtUtils.println(c, msg);
    }

    static void print(Color c, String msg) {
        EdtUtils.print(c, msg);
    }

    static void println(String msg) {
        EdtUtils.println(msg);
    }

    // ==================== 工具方法 ====================

    private boolean checkKey() {
        if (!act.getConfig().hasApiKey()) {
            println(C_ERR, "请先设置API密钥: ai/setkey [你的密钥]");
            return false;
        }
        return true;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean err(String msg) {
        println(C_ERR, msg);
        return false;
    }

    // ==================== OneBot QQ 命令 ====================

    /** execq —— QQ通道Agent模式（受限标签，自动允许） */
    public Object handleExecq(String args) {
        // execq 通过QQ消息处理器调用，不直接从控制台使用
        // 这里提供一个手动入口用于测试
        if (isEmpty(args)) return err("用法: ai/execq [QQ消息内容]\n注意：execq通常由QQ消息自动触发，此命令仅用于测试。");
        if (!checkKey()) return false;

        final String task = args.trim();
        println(C_INFO, "[execq测试] " + task);

        // 如果OneBot已连接，通过QQMessageHandler处理
        // 否则直接使用execq模式进行测试
        act.getGate().setBypassConfirm(true);
        return handleExec(args); // 回退到exec模式
    }

    public Object handleOneBotConnect() {
        sair.aiagent.onebot.OneBotServer server = act.getOneBotServer();
        if (server == null) {
            println(C_ERR, "OneBot服务未初始化。");
            return false;
        }
        if (server.isRunning()) {
            println(C_INFO, "OneBot服务已在运行中，端口: " + server.getPort());
            return true;
        }
        server.setPort(act.getConfig().getOnebotPort());
        server.setAccessToken(act.getConfig().getOnebotToken());
        if (server.start()) {
            act.getConfig().setOnebotEnabled(true);
            act.getConfig().save();
            println(new Color(100, 255, 100), "OneBot服务已启动，监听端口: " + server.getPort());
            println(C_INFO, "请在OneBot实现端配置反向WebSocket连接到: ws://127.0.0.1:" + server.getPort() + "/");
        } else {
            println(C_ERR, "OneBot服务启动失败，端口 " + server.getPort() + " 可能被占用。");
        }
        return true;
    }

    public Object handleOneBotDisconnect() {
        sair.aiagent.onebot.OneBotServer server = act.getOneBotServer();
        if (server == null) {
            println(C_ERR, "OneBot服务未初始化。");
            return false;
        }
        server.stop();
        act.getConfig().setOnebotEnabled(false);
        act.getConfig().save();
        println(C_INFO, "OneBot服务已停止。");
        return true;
    }

    public Object handleOneBotStatus() {
        sair.aiagent.onebot.OneBotServer server = act.getOneBotServer();
        if (server == null) {
            println(C_ERR, "OneBot服务未初始化。");
            return false;
        }
        println(C_INFO, "== OneBot QQ 状态 ==");
        println(C_INFO, "运行状态: " + (server.isRunning() ? "运行中" : "已停止"));
        println(C_INFO, "监听端口: " + server.getPort());
        println(C_INFO, "连接数  : " + server.getConnectionCount());
        println(C_INFO, "Token   : " + (act.getConfig().getOnebotToken().isEmpty() ? "(未设置)" : "已设置"));
        println(C_INFO, "机器人QQ: " + (act.getConfig().getOnebotSelfId() > 0 ? String.valueOf(act.getConfig().getOnebotSelfId()) : "(未设置)"));
        return true;
    }

    public Object handleOneBotSetPort(String args) {
        if (isEmpty(args)) return err("用法: ai/onebot/setport [端口号]");
        try {
            int port = Integer.parseInt(args.trim());
            act.getConfig().setOnebotPort(port);
            act.getConfig().save();
            println(C_INFO, "OneBot端口 -> " + port);
        } catch (NumberFormatException e) {
            return err("端口号必须是数字。");
        }
        return true;
    }

    public Object handleOneBotSetToken(String args) {
        if (isEmpty(args)) return err("用法: ai/onebot/settoken [Token]");
        act.getConfig().setOnebotToken(args.trim());
        act.getConfig().save();
        println(C_INFO, "OneBot Token已设置。");
        return true;
    }

    public Object handleOneBotSetSelfId(String args) {
        if (isEmpty(args)) return err("用法: ai/onebot/setselfid [QQ号]");
        try {
            long qq = Long.parseLong(args.trim());
            act.getConfig().setOnebotSelfId(qq);
            act.getConfig().save();
            if (act.getOneBotMessageHandler() != null) {
                act.getOneBotMessageHandler().setSelfId(qq);
            }
            println(C_INFO, "机器人QQ号 -> " + qq);
        } catch (NumberFormatException e) {
            return err("QQ号必须是数字。");
        }
        return true;
    }

    public Object handleOneBotSetPrompt(String args) {
        if (isEmpty(args)) return err("用法: ai/onebot/setprompt [提示词]");
        act.getConfig().setExecqPrompt(args);
        act.getConfig().save();
        println(C_INFO, "execq提示词已更新 (长度: " + act.getConfig().getExecqPrompt().length() + ")");
        return true;
    }

    public Object handleOneBotShowPrompt() {
        print(new Color(100, 255, 180), "[execq 系统提示词]");
        println(C_INFO, "\n" + act.getConfig().getExecqPrompt());
        return true;
    }

    // ==================== execq 插件白名单管理 ====================

    /** 显示当前白名单 */
    public Object handleOneBotWhitelist() {
        java.util.Set<String> wl = act.getConfig().getExecqCmdWhitelist();
        print(new Color(100, 255, 180), "[execq 插件白名单]");
        if (wl.isEmpty()) {
            println(C_INFO, "\n(空 — 所有 <cmd> 命令均被拒绝。使用 ai/onebot/whitelist/add 添加插件)");
        } else {
            println(C_INFO, "\n允许的插件: " + wl);
            println(C_INFO, "配置文件: config.properties → execqCmdWhitelist");
        }
        return true;
    }

    /** 添加插件到白名单 */
    public Object handleOneBotWhitelistAdd(String args) {
        if (isEmpty(args)) return err("用法: ai/onebot/whitelist/add [插件名]");
        String pluginName = args.trim();
        if (act.getConfig().addExecqCmdPlugin(pluginName)) {
            act.getConfig().save();
            act.getAgent().setCmdWhitelist(act.getConfig().getExecqCmdWhitelist());
            println(C_INFO, "execq白名单已添加: [" + pluginName + "]，当前白名单: " + act.getConfig().getExecqCmdWhitelist());
        } else {
            println(C_INFO, "插件 [" + pluginName + "] 已在白名单中，当前: " + act.getConfig().getExecqCmdWhitelist());
        }
        return true;
    }

    /** 从白名单移除插件 */
    public Object handleOneBotWhitelistRemove(String args) {
        if (isEmpty(args)) return err("用法: ai/onebotwhitelistremove [插件名]");
        String pluginName = args.trim();
        if (act.getConfig().removeExecqCmdPlugin(pluginName)) {
            act.getConfig().save();
            act.getAgent().setCmdWhitelist(act.getConfig().getExecqCmdWhitelist());
            println(C_INFO, "execq白名单已移除: [" + pluginName + "]，当前白名单: " + act.getConfig().getExecqCmdWhitelist());
        } else {
            println(C_INFO, "插件 [" + pluginName + "] 不在白名单中，当前: " + act.getConfig().getExecqCmdWhitelist());
        }
        return true;
    }

    // ==================== 主动查看配置命令 ====================

    /** 启用主动查看功能 */
    public Object handleOneBotEnableProactive() {
        act.getConfig().setProactiveCheckEnabled(true);
        act.getConfig().save();
        
        if (act.getOneBotMessageHandler() != null) {
            act.getOneBotMessageHandler().enableProactiveCheck();
            println(C_INFO, "主动查看功能已启用（每5分钟检查一次）");
        } else {
            println(C_INFO, "主动查看配置已保存，下次启动OneBot时生效");
        }
        return true;
    }

    /** 禁用主动查看功能 */
    public Object handleOneBotDisableProactive() {
        act.getConfig().setProactiveCheckEnabled(false);
        act.getConfig().save();
        
        if (act.getOneBotMessageHandler() != null) {
            act.getOneBotMessageHandler().disableProactiveCheck();
        }
        println(C_INFO, "主动查看功能已禁用");
        return true;
    }

    /** 添加监听的群号 */
    public Object handleOneBotAddGroup(String args) {
        if (isEmpty(args)) return err("用法: ai/onebotaddgroup [群号]");
        try {
            long groupId = Long.parseLong(args.trim());
            if (act.getConfig().addMonitoredGroup(groupId)) {
                act.getConfig().save();
                if (act.getOneBotMessageHandler() != null) {
                    act.getOneBotMessageHandler().addMonitoredGroup(groupId);
                }
                println(C_INFO, "已添加监听群: " + groupId);
            } else {
                println(C_INFO, "群号已在监听列表中: " + groupId);
            }
        } catch (NumberFormatException e) {
            return err("群号必须是数字。");
        }
        return true;
    }

    /** 移除监听的群号 */
    public Object handleOneBotRemoveGroup(String args) {
        if (isEmpty(args)) return err("用法: ai/onebotremovegroup [群号]");
        try {
            long groupId = Long.parseLong(args.trim());
            if (act.getConfig().removeMonitoredGroup(groupId)) {
                act.getConfig().save();
                if (act.getOneBotMessageHandler() != null) {
                    act.getOneBotMessageHandler().removeMonitoredGroup(groupId);
                }
                println(C_INFO, "已移除监听群: " + groupId);
            } else {
                println(C_INFO, "群号不在监听列表中: " + groupId);
            }
        } catch (NumberFormatException e) {
            return err("群号必须是数字。");
        }
        return true;
    }

    /** 列出所有监听的群号 */
    public Object handleOneBotListGroups() {
        java.util.Set<Long> groups = act.getConfig().getMonitoredGroups();
        if (groups.isEmpty()) {
            println(C_INFO, "当前没有监听的群");
        } else {
            println(C_INFO, "监听的群列表 (共" + groups.size() + "个):");
            for (Long g : groups) {
                println(C_INFO, "  - " + g);
            }
        }
        println(C_INFO, "主动查看状态: " + (act.getConfig().isProactiveCheckEnabled() ? "已启用" : "已禁用"));
        return true;
    }
}
