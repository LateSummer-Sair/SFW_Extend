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
        AiAgentActivity.debugLog("Dispatching to: " + funcName);
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
        AiAgentActivity.debugLog("handleChat ENTER: " + args);
        if (isEmpty(args)) return err("用法: ai/chat [问题]");
        if (!checkKey()) { AiAgentActivity.debugLog("handleChat: no API key"); return false; }

        final String userMsg = args.trim();
        AiAgentActivity.debugLog("handleChat: userMsg=" + userMsg);

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

        println(C_INFO, "\n[You] " + userMsg);

        act.stopActivePrinter();

        final String memoryContext = act.getMemory().buildContext(userMsg);
        if (memoryContext != null) {
            println(C_MEM, "[记忆] 找到相关记忆，已注入上下文。");
        }

        final StreamPrinter printer = StreamPrinter.getInstance();
        printer.start();

        print(C_AI, "[AI] ");
        AiAgentActivity.debugLog("handleChat: starting background thread");

        act.setActiveThread(new Thread(new Runnable() {
            public void run() {
                AiAgentActivity.debugLog("ChatThread: START");
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

                    AiAgentActivity.debugLog("ChatThread: calling chatStream...");
                    String fullResponse = act.getClient().chatStream(messages);
                    AiAgentActivity.debugLog("ChatThread: chatStream returned, len=" + fullResponse.length());
                    capturedResponse[0] = fullResponse;
                    act.getHistory().add(new ChatMessage("assistant", fullResponse));
                    // === 日志：记录用户消息 + AI 回复 ===
                    act.getJournal().addEntry("user", "chat", userMsg, null);
                    act.getJournal().addEntry("assistant", "chat", fullResponse, null);
                } catch (Exception e) {
                    AiAgentActivity.debugLog("ChatThread: ERROR: " + e.toString());
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
                    AiAgentActivity.debugLog("ChatThread: END");
                }
            }
        }, "AiAgent-Chat"));
        act.getActiveThread().setDaemon(true);
        act.getActiveThread().start();

        AiAgentActivity.debugLog("handleChat: returning true");
        return true;
    }

    // ==================== Agent 模式 ====================

    public Object handleExec(String args) {
        AiAgentActivity.debugLog("handleExec ENTER: " + args);
        if (isEmpty(args)) return err("用法: ai/exec [任务描述]");
        if (!checkKey()) { AiAgentActivity.debugLog("handleExec: no API key"); return false; }

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

        AiAgentActivity.debugLog("handleExec: starting background thread");
        final JournalManager journal = act.getJournal();

        act.setActiveThread(new Thread(new Runnable() {
            public void run() {
                AiAgentActivity.debugLog("ExecThread: START");
                try {
                    act.getAgent().execute(task);
                    AiAgentActivity.debugLog("ExecThread: agent.execute() done");
                    // === 日志：记录 Agent 任务 + 执行摘要 ===
                    String agentSummary = act.getAgent().getLastSummary();
                    journal.addEntry("agent", "exec", task, agentSummary);
                } catch (Exception e) {
                    AiAgentActivity.debugLog("ExecThread: ERROR: " + e.toString());
                    println(C_ERR, "[错误] Agent执行失败: " + e.toString());
                    if (e.getMessage() != null) {
                        println(C_ERR, "        " + e.getMessage());
                    }
                } finally {
                    StreamPrinter.getInstance().flushAndStop();
                    if (act.getActiveThread() == Thread.currentThread()) act.setActiveThread(null);
                    AiAgentActivity.debugLog("ExecThread: END");
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
}
