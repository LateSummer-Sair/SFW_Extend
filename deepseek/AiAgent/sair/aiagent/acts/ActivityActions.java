package sair.aiagent.acts;

import java.awt.Color;
import java.io.File;
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
    private final OneBotCommandActions oneBotCmd;

    public ActivityActions(AiAgentActivity act) {
        this.act = act;
        this.oneBotCmd = new OneBotCommandActions(act);
    }

    // ==================== 命令路由 ====================

    public Object route(String funcName, String args) {
        AiAgentActivity.debugLog("路由: " + funcName);
        switch (funcName) {
            case "chat":       return handleExecs(args);
            case "exec":       return handleExecs(args);
            case "execs":      return handleExecs(args);
            case "execfc":     return handleExecs(args);
            case "orchestrate": return handleOrchestrate(args);
            case "harnesseval": return handleHarnessEval();
            case "criticon":   return handleCriticOn();
            case "criticoff":  return handleCriticOff();
            case "setkey":     return handleSetKey(args);
            case "seturl":     return handleSetUrl(args);
            case "setmodel":   return handleSetModel(args);
            case "setocrkey":  return handleSetOcrKey(args);
            case "setthirdpartycode": return handleSetThirdPartyCode(args);
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
            case "execq":               return oneBotCmd.handleExecq(args);
            case "onebotconnect":      return oneBotCmd.handleOneBotConnect();
            case "onebotdisconnect":   return oneBotCmd.handleOneBotDisconnect();
            case "onebotstatus":       return oneBotCmd.handleOneBotStatus();
            case "onebotsetport":      return oneBotCmd.handleOneBotSetPort(args);
            case "onebotsettoken":     return oneBotCmd.handleOneBotSetToken(args);
            case "onebotsetselfid":    return oneBotCmd.handleOneBotSetSelfId(args);
            case "onebotsetprompt":   return oneBotCmd.handleOneBotSetPrompt(args);
            case "onebotshowprompt":  return oneBotCmd.handleOneBotShowPrompt();
            // === 主动查看配置 ===
            case "onebotenableproactive": return oneBotCmd.handleOneBotEnableProactive();
            case "onebotdisableproactive": return oneBotCmd.handleOneBotDisableProactive();
            case "onebotaddgroup":       return oneBotCmd.handleOneBotAddGroup(args);
            case "onebotremovegroup":    return oneBotCmd.handleOneBotRemoveGroup(args);
            case "onebotlistgroups":     return oneBotCmd.handleOneBotListGroups();
            // === 拟人化监听队列开关 ===
            case "onebotenablelisten":   return oneBotCmd.handleOneBotEnableListen();
            case "onebotdisablelisten":  return oneBotCmd.handleOneBotDisableListen();
            case "resetaffection":       return oneBotCmd.handleResetAffection();
            case "resetdonations":      return oneBotCmd.handleResetDonations();
            // === 自动清屏 ===
            case "autoclearon":  return handleAutoClearOn();
            case "autoclearoff": return handleAutoClearOff();
            case "qqlogon":   return handleQqLogOn();
            case "qqlogoff":  return handleQqLogOff();
            // === Skills ===
            case "skills":         return handleSkills();
            case "skillsearch":    return handleSkillSearch(args);
            case "skilldelete":    return handleSkillDelete(args);
            case "skillextract":   return handleSkillExtract();
            case "skillevolve":    return handleSkillEvolve();
            case "skillinfo":      return handleSkillInfo(args);
            case "skillexport":    return handleSkillExport(args);
            case "skillexportall": return handleSkillExportAll();
            case "clearstickers":  return handleClearStickers();
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

    /** 设置在线 OCR 的 Access Key（EasyOCR 等），设置后启用图片文字识别能力。 */
    public Object handleSetOcrKey(String args) {
        if (isEmpty(args)) return err("用法: ai/setocrkey [OCR Access Key]");
        act.getConfig().setOcrAccessKey(args);
        act.getConfig().save();
        sair.aiagent.ocr.OcrManager.getInstance().setAccessKey(args);
        println(C_INFO, "OCR Access Key 已设置，OCR 能力已启用（引擎: "
                + sair.aiagent.ocr.OcrManager.getInstance().getEngineName() + "）");
        return true;
    }

    /**
     * 放权开关：三方技能代码段是否在所有通道（含 execq/QQ）可用。
     * 默认 on=放权（用户导入的三方技能视为刚需，安全由用户自行审查）；off=仅 execs/本地可用。
     */
    public Object handleSetThirdPartyCode(String args) {
        if (isEmpty(args)) {
            boolean cur = act.getConfig().isThirdPartyCodeExecq();
            println(C_INFO, "三方技能代码段执行权限（execq/QQ 通道）："
                    + (cur ? "开（放权，所有通道可用）" : "关（仅 execs/本地可用）"));
            println(C_INFO, "用法: ai/setthirdpartycode on|off");
            return true;
        }
        String v = args.trim().toLowerCase();
        if ("on".equals(v) || "true".equals(v) || "1".equals(v)) {
            act.getConfig().setThirdPartyCodeExecq(true);
            act.getConfig().save();
            println(C_INFO, "已放权：三方技能代码段在所有通道（含 execq/QQ）可用。");
        } else if ("off".equals(v) || "false".equals(v) || "0".equals(v)) {
            act.getConfig().setThirdPartyCodeExecq(false);
            act.getConfig().save();
            println(C_INFO, "已收紧：三方技能代码段仅在 execs/本地通道可用，execq/QQ 通道不可用。");
        } else {
            return err("用法: ai/setthirdpartycode on|off");
        }
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

    public Object handleClearStickers() {
        sair.aiagent.core.StickerManager sm = act.getAgent().getStickerManager();
        if (sm == null) {
            println(C_ERR, "表情包管理器未初始化。");
            return false;
        }
        int before = sm.count();
        sm.clearAll();
        println(C_INFO, "表情包图片库已清空（删除 " + before + " 张）。");
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

    // ==================== Chat 模式（已合并到 execs） ====================

    public Object handleChat(String args) {
        return handleExecs(args);
    }

    // ==================== Agent 模式（已合并到 execs） ====================

    public Object handleExec(String args) {
        return handleExecs(args);
    }

    /** execs 模式 —— 唯一入口：免确认 + 原生 Function Calling（全能模式，模型自动决定聊天还是调工具）。 */
    public Object handleExecs(String args) {
        AiAgentActivity.debugLog("handleExecs 进入: " + args);
        if (isEmpty(args)) return err("用法: ai/execs [任务描述]");
        if (!checkKey()) { AiAgentActivity.debugLog("handleExecs: 未设置API密钥"); return false; }

        final String fcTask = args.trim();

        // === 情绪：检测用户消息中的情绪 ===
        act.getEmotionManager().detectEmotion(fcTask);
        if (act.getEmotionManager().isPaused()) {
            String result = act.getEmotionManager().handleUserInteraction(fcTask);
            if ("comforted".equals(result)) {
                println(new Color(180, 255, 180), "💚 AI感受到你的安慰，心情平静下来了~");
            } else if ("guided".equals(result)) {
                println(new Color(180, 220, 255), "📝 指导已记录，AI继续工作中...");
            }
            return true;
        }

        act.stopActivePrinter();

        // === 注入上下文 ===
        final String memoryContext = act.getMemory().buildContext(fcTask);
        if (memoryContext != null) {
            println(C_MEM, "[记忆] 找到相关记忆，已注入上下文。");
        }
        act.getAgent().setMemoryContext(memoryContext);

        sair.aiagent.core.SkillBank fcBank = sair.aiagent.core.SkillBank.getInstance();
        if (fcBank != null && fcBank.getPersistenceManager() != null) {
            act.getAgent().setNotesContext(fcBank.getPersistenceManager().buildNotesContext(fcTask));
            act.getAgent().setCorrectionsContext(fcBank.getPersistenceManager().buildCorrectionsContext(fcTask, 1200));
        }
        act.getAgent().setMemoryManager(act.getMemory());
        act.getAgent().setChatHistoryContext(buildChatContextForAgent());
        act.getAgent().setJournal(act.getJournal());
        act.getAgent().setEmotionManager(act.getEmotionManager());

        final String task = fcTask;
        final JournalManager journal = act.getJournal();

        println(C_INFO, "[execs] 使用原生 Function Calling 执行（免确认全能模式）...");

        AiAgentActivity.debugLog("handleExecs: 启动后台线程");
        act.setActiveThread(new Thread(new Runnable() {
            public void run() {
                AiAgentActivity.debugLog("ExecsThread: 开始");
                try {
                    String result = act.getAgent().executeFcLocal(task);
                    AiAgentActivity.debugLog("ExecsThread: executeFcLocal() 完成");
                    if (result != null && !result.isEmpty()) {
                        StreamPrinter printer = StreamPrinter.getInstance();
                        printer.start();
                        printer.setColor(C_AI);
                        printer.offer(result);
                        printer.flushAndStop();
                    }
                    journal.addEntry("agent", "execs", task, result);
                } catch (Exception e) {
                    AiAgentActivity.debugLog("ExecsThread: 错误: " + e.toString());
                    println(C_ERR, "[错误] Function Calling 执行失败: " + e.toString());
                } finally {
                    act.getGate().setBypassConfirm(false);
                    if (act.getActiveThread() == Thread.currentThread()) act.setActiveThread(null);
                    AiAgentActivity.debugLog("ExecsThread: 结束");
                }
            }
        }, "AiAgent-Execs"));
        act.getActiveThread().setDaemon(true);
        act.getActiveThread().start();

        return true;
    }

    /** orchestrate 模式 —— 多智能体编排（pipeline / fanout / expert，opt-in）。 */
    public Object handleOrchestrate(String args) {
        AiAgentActivity.debugLog("handleOrchestrate 进入: " + args);
        if (isEmpty(args)) return err("用法: ai/orchestrate [pipeline|fanout|expert] [任务描述]");
        if (!checkKey()) { AiAgentActivity.debugLog("handleOrchestrate: 未设置API密钥"); return false; }

        String trimmed = args.trim();
        String mode = "pipeline";
        String task = trimmed;
        String lower = trimmed.toLowerCase();
        if (lower.startsWith("pipeline ")) { mode = "pipeline"; task = trimmed.substring("pipeline".length()).trim(); }
        else if (lower.startsWith("fanout ")) { mode = "fanout"; task = trimmed.substring("fanout".length()).trim(); }
        else if (lower.startsWith("expert ")) { mode = "expert"; task = trimmed.substring("expert".length()).trim(); }
        if (task.isEmpty()) return err("用法: ai/orchestrate [pipeline|fanout|expert] [任务描述]");

        final String fMode = mode;
        final String fTask = task;
        println(C_INFO, "[orchestrate] 多智能体编排（模式=" + fMode + "）执行中...");

        act.setActiveThread(new Thread(() -> {
            try {
                String result = act.getAgent().executeOrchestrated(fTask, fMode, new sair.aiagent.core.ToolContext("console"), null);
                if (result != null && !result.isEmpty()) {
                    StreamPrinter printer = StreamPrinter.getInstance();
                    printer.start();
                    printer.setColor(C_AI);
                    printer.offer(result);
                    printer.flushAndStop();
                }
                act.getJournal().addEntry("agent", "orchestrate", fTask, result);
            } catch (Exception e) {
                AiAgentActivity.debugLog("OrchestrateThread: 错误: " + e.toString());
                println(C_ERR, "[错误] 编排执行失败: " + e.toString());
            } finally {
                act.getGate().setBypassConfirm(false);
                if (act.getActiveThread() == Thread.currentThread()) act.setActiveThread(null);
            }
        }, "AiAgent-Orchestrate"));
        act.getActiveThread().setDaemon(true);
        act.getActiveThread().start();
        return true;
    }

    /** harnesseval —— 输出最近 Harness 执行轨迹评测报告（可观测性）。 */
    public Object handleHarnessEval() {
        try {
            sair.aiagent.core.PersistenceManager pm = sair.aiagent.core.PersistenceManager.getInstance();
            if (pm == null) {
                println(C_ERR, "[HarnessEval] 持久化层未初始化");
                return true;
            }
            sair.aiagent.core.HarnessEval eval = new sair.aiagent.core.HarnessEval(pm);
            String report = eval.evaluate(50);
            println(C_INFO, report);
        } catch (Exception e) {
            println(C_ERR, "[HarnessEval] 评测失败: " + e.toString());
        }
        return true;
    }

    /** criticon —— 开启 Harness 验证闭环（opt-in），每次最终回复增加一次独立审查。 */
    public Object handleCriticOn() {
        sair.aiagent.core.HarnessConfig.getInstance().setCriticEnabled(true);
        act.getAgent().setCriticEnabled(true);
        println(C_INFO, "[Critic] Harness 验证闭环已开启（每次最终回复增加一次独立审查）");
        return true;
    }

    /** criticoff —— 关闭 Harness 验证闭环（默认状态，老功能零干扰）。 */
    public Object handleCriticOff() {
        sair.aiagent.core.HarnessConfig.getInstance().setCriticEnabled(false);
        act.getAgent().setCriticEnabled(false);
        println(C_INFO, "[Critic] Harness 验证闭环已关闭");
        return true;
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

    // ==================== 自动清屏 ====================

    public Object handleAutoClearOn() {
        if (act.isAutoClearEnabled()) {
            println(C_INFO, "[AutoClear] 已在运行中（每3分钟自动清屏）");
            return true;
        }
        act.startAutoClear();
        println(C_INFO, "[AutoClear] 已启用 — 每3分钟自动执行 /clear，防止日志OOM");
        return true;
    }

    public Object handleAutoClearOff() {
        if (!act.isAutoClearEnabled()) {
            println(C_INFO, "[AutoClear] 未在运行");
            return true;
        }
        act.stopAutoClear();
        println(C_INFO, "[AutoClear] 已停止");
        return true;
    }

    // ==================== QQ日志开关 ====================

    public Object handleQqLogOn() {
        AiAgentActivity.setQqLogEnabled(true);
        println(C_INFO, "[QqLog] QQ消息控制台输出已开启");
        return true;
    }

    public Object handleQqLogOff() {
        AiAgentActivity.setQqLogEnabled(false);
        println(C_INFO, "[QqLog] QQ消息控制台输出已关闭");
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

    // ==================== Skills 命令 ====================

    public Object handleSkills() {
        sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
        String list = bank.formatSkillList();
        println(C_INFO, list);
        return true;
    }

    public Object handleSkillSearch(String args) {
        if (isEmpty(args)) return err("用法: ai/skillsearch [关键词]");
        sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
        java.util.List<sair.aiagent.model.SkillEntry> results = bank.search(args.trim(), 10);
        if (results.isEmpty()) {
            println(C_INFO, "未找到匹配的技能。");
            return true;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("搜索结果 (").append(results.size()).append(" 条):\n");
        for (sair.aiagent.model.SkillEntry s : results) {
            sb.append("[").append(s.getId()).append("] ").append(s.getName())
              .append(" v").append(s.getVersion())
              .append(" [").append(s.getCategory()).append("] ")
              .append(s.successRate()).append("% 成功率\n");
            sb.append("  ").append(s.getDescription()).append("\n");
        }
        println(C_INFO, sb.toString().trim());
        return true;
    }

    public Object handleSkillInfo(String args) {
        if (isEmpty(args)) return err("用法: ai/skillinfo [ID]");
        try {
            int id = Integer.parseInt(args.trim());
            sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
            sair.aiagent.model.SkillEntry skill = bank.getSkill(id);
            if (skill == null) {
                println(C_INFO, "技能 #" + id + " 不存在。");
                return true;
            }
            println(C_INFO, bank.formatSkillDetail(skill));
        } catch (NumberFormatException e) {
            return err("请输入有效的技能ID");
        }
        return true;
    }

    public Object handleSkillDelete(String args) {
        if (isEmpty(args)) return err("用法: ai/skilldelete [ID]");
        try {
            int id = Integer.parseInt(args.trim());
            sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
            if (bank.delete(id)) {
                println(C_INFO, "技能 #" + id + " 已删除。");
            } else {
                println(C_ERR, "删除失败，技能 #" + id + " 不存在。");
            }
        } catch (NumberFormatException e) {
            return err("请输入有效的技能ID");
        }
        return true;
    }

    public Object handleSkillExtract() {
        sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
        sair.aiagent.core.PersistenceManager pm = sair.aiagent.core.PersistenceManager.getInstance();
        if (pm == null) {
            println(C_ERR, "持久化管理器未初始化。");
            return false;
        }
        sair.aiagent.core.SkillExtractor extractor = act.getAgent().getSkillExtractor();
        if (extractor == null) {
            // Create on demand
            extractor = new sair.aiagent.core.SkillExtractor(act.getClient(), bank);
        }
        println(C_INFO, "正在从最近操作日志提取技能...");
        int added = extractor.extractFromJournal(pm, 30);
        if (added > 0) {
            println(C_INFO, "✓ 提取了 " + added + " 个新技能。使用 ai/skills 查看。");
        } else {
            println(C_INFO, "未发现可提取的新技能。");
        }
        return true;
    }

    public Object handleSkillEvolve() {
        sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
        println(C_INFO, "正在分析失败模式并进化技能...");
        int evolved = bank.evolve();
        if (evolved > 0) {
            println(C_INFO, "✓ 进化了 " + evolved + " 个技能。使用 ai/skills 查看更新。");
        } else {
            println(C_INFO, "没有需要进化的技能（所有技能失败率都低于阈值）。");
        }
        return true;
    }

    public Object handleSkillExport(String args) {
        sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
        if (isEmpty(args)) return err("用法: ai/skillexport [ID]");
        try {
            int id = Integer.parseInt(args.trim());
            String md = bank.exportSkill(id);
            if (md == null) {
                println(C_INFO, "技能 #" + id + " 不存在。");
            } else {
                println(C_INFO, md);
            }
        } catch (NumberFormatException e) {
            return err("请输入有效的技能ID");
        }
        return true;
    }

    public Object handleSkillExportAll() {
        sair.aiagent.core.SkillBank bank = sair.aiagent.core.SkillBank.getInstance();
        println(C_INFO, bank.exportAllSkills());
        return true;
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
        return checkKey(act);
    }

    static boolean checkKey(AiAgentActivity act) {
        if (!act.getConfig().hasApiKey()) {
            println(C_ERR, "请先设置API密钥: ai/setkey [你的密钥]");
            return false;
        }
        return true;
    }

    static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    static boolean err(String msg) {
        println(C_ERR, msg);
        return false;
    }
}
