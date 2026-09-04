package sair.aiagent.acts;

import java.awt.Color;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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
    private final Map<String, Function<String, Object>> commands = new LinkedHashMap<>();

    public ActivityActions(AiAgentActivity act) {
        this.act = act;
        this.oneBotCmd = new OneBotCommandActions(act);
        registerCommands();
    }

    // ==================== 命令路由 ====================

    public Object route(String funcName, String args) {
        AiAgentActivity.debugLog("路由: " + funcName);
        Function<String, Object> handler = commands.get(funcName);
        return handler != null ? handler.apply(args) : false;
    }

    private void registerCommands() {
        commands.put("chat", this::handleExecs);
        commands.put("exec", this::handleExecs);
        commands.put("execs", this::handleExecs);
        commands.put("execfc", this::handleExecs);
        commands.put("orchestrate", this::handleOrchestrate);
        commands.put("harnesseval", a -> handleHarnessEval());
        commands.put("criticon", a -> handleCriticOn());
        commands.put("criticoff", a -> handleCriticOff());
        commands.put("setkey", this::handleSetKey);
        commands.put("seturl", this::handleSetUrl);
        commands.put("setmodel", this::handleSetModel);
        commands.put("setthirdpartycode", this::handleSetThirdPartyCode);
        commands.put("setprompt", this::handleSetPrompt);
        commands.put("showprompt", a -> handleShowPrompt());
        commands.put("memories", a -> handleMemories());
        commands.put("forget", this::handleForget);
        commands.put("forgetall", a -> handleForgetAll());
        commands.put("yes", a -> handleYes());
        commands.put("no", a -> handleNo());
        commands.put("config", a -> handleConfig());
        commands.put("setconfig", this::handleSetConfig);
        commands.put("status", a -> handleStatus());
        commands.put("reset", a -> handleReset());
        commands.put("stop", a -> handleStop());
        commands.put("mood", a -> handleMood());
        commands.put("execq", oneBotCmd::handleExecq);
        commands.put("onebotconnect", a -> oneBotCmd.handleOneBotConnect());
        commands.put("onebotdisconnect", a -> oneBotCmd.handleOneBotDisconnect());
        commands.put("onebotstatus", a -> oneBotCmd.handleOneBotStatus());
        commands.put("onebotsetport", oneBotCmd::handleOneBotSetPort);
        commands.put("onebotsettoken", oneBotCmd::handleOneBotSetToken);
        commands.put("onebotsetselfid", oneBotCmd::handleOneBotSetSelfId);
        commands.put("onebotsetprompt", oneBotCmd::handleOneBotSetPrompt);
        commands.put("onebotshowprompt", a -> oneBotCmd.handleOneBotShowPrompt());
        commands.put("onebotenableproactive", a -> oneBotCmd.handleOneBotEnableProactive());
        commands.put("onebotdisableproactive", a -> oneBotCmd.handleOneBotDisableProactive());
        commands.put("onebotaddgroup", oneBotCmd::handleOneBotAddGroup);
        commands.put("onebotremovegroup", oneBotCmd::handleOneBotRemoveGroup);
        commands.put("onebotlistgroups", a -> oneBotCmd.handleOneBotListGroups());
        commands.put("onebotenablelisten", a -> oneBotCmd.handleOneBotEnableListen());
        commands.put("onebotdisablelisten", a -> oneBotCmd.handleOneBotDisableListen());
        commands.put("resetaffection", a -> oneBotCmd.handleResetAffection());
        commands.put("resetdonations", a -> oneBotCmd.handleResetDonations());
        commands.put("autoclearon", a -> handleAutoClearOn());
        commands.put("autoclearoff", a -> handleAutoClearOff());
        commands.put("qqlogon", a -> handleQqLogOn());
        commands.put("qqlogoff", a -> handleQqLogOff());
        commands.put("skills", a -> handleSkills());
        commands.put("skillsearch", this::handleSkillSearch);
        commands.put("skilldelete", this::handleSkillDelete);
        commands.put("skillextract", a -> handleSkillExtract());
        commands.put("skillevolve", a -> handleSkillEvolve());
        commands.put("skillinfo", this::handleSkillInfo);
        commands.put("skillexport", this::handleSkillExport);
        commands.put("skillexportall", a -> handleSkillExportAll());
        commands.put("clearstickers", a -> handleClearStickers());
        commands.put("exportdata", this::handleExportData);
        commands.put("importdata", this::handleImportData);
    }

    // ==================== 配置命令 ====================

    public Object handleSetKey(String args) {
        if (isEmpty(args)) return err("用法: ai/setkey [API密钥]");
        act.getConfig().setApiKey(args);
        act.getConfig().save();
        println(C_INFO, "API密钥已设置: " + act.getConfig().getMaskedKey());
        return true;
    }

    /** 设置在线 OCR 的 Access Key（已移除 OCR 能力，此方法保留仅为兼容旧路由） */
    public Object handleSetOcrKey(String args) {
        return err("OCR 能力已移除，图片理解已改由 DeepSeek Vision 多模态模型负责，无需设置 OCR Key。");
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

    public Object handleConfig() {
        sair.aiagent.core.AiConfig cfg = act.getConfig();
        println(C_INFO, "== AiAgent 配置 ==");
        println(C_INFO, "API地址 : " + cfg.getApiUrl());
        println(C_INFO, "API密钥 : " + cfg.getMaskedKey());
        println(C_INFO, "模型    : " + cfg.getModel());
        println(C_INFO, "思考模式: " + describeReasoningEffort(cfg.getReasoningEffort()));
        println(C_INFO, "温度    : " + (cfg.getTemperature() < 0 ? "(默认)" : String.valueOf(cfg.getTemperature())));
        println(C_INFO, "top_p   : " + (cfg.getTopP() < 0 ? "(默认)" : String.valueOf(cfg.getTopP())));
        println(C_INFO, "最大输出: " + (cfg.getMaxOutputTokens() <= 0 ? "(默认)" : String.valueOf(cfg.getMaxOutputTokens())));
        println(C_INFO, "strict  : " + (cfg.isStrictMode() ? "开" : "关"));
        println(C_INFO, "文件端口: " + cfg.getFileServerPort());
        println(C_INFO, "提示词  : " + cfg.getSystemPrompt().length() + " 字符");
        println(C_INFO, "对话    : " + act.getHistory().size() + " 条/~"
                + act.getHistory().estimateTotalTokens() + " tokens");
        println(C_INFO, "记忆    : " + act.getMemory().size() + " 条");
        println(C_INFO, "系统    : " + SysConsoleExecutor.getOsIdentifier()
                + " | Shell: " + SysConsoleExecutor.getShellType());
        println(C_INFO, "数据目录: " + act.getDataDir());
        return true;
    }

    /** 运行时状态总览：OneBot / Redis / FileServer / 线程池 / 轨迹 / 技能与记忆计数。 */
    public Object handleStatus() {
        println(C_SYS, "== AiAgent Runtime Status ==");

        sair.aiagent.onebot.OneBotServer oneBot = act.getOneBotServer();
        if (oneBot == null) {
            println(C_INFO, "OneBot    : 未初始化");
        } else {
            println(C_INFO, "OneBot    : " + (oneBot.isRunning() ? "运行中" : "已停止")
                    + " | 端口 " + oneBot.getPort()
                    + " | 连接 " + oneBot.getConnectionCount()
                    + " | Token " + (act.getConfig().getOnebotToken().isEmpty() ? "(未设置)" : "已设置"));
        }

        sair.aiagent.core.RedisClient redis = sair.aiagent.core.RedisClient.getInstance();
        println(C_INFO, "Redis     : " + redis.statusSummary()
                + " | prefix=" + redis.getKeyPrefix());

        sair.aiagent.onebot.FileServer fileServer = sair.aiagent.onebot.FileServer.getInstance();
        String fileUrl = fileServer.getPublicBaseUrl();
        println(C_INFO, "FileServer: " + (fileServer.isRunning() ? "运行中" : "已停止")
                + " | 端口 " + (fileServer.getPort() > 0 ? String.valueOf(fileServer.getPort()) : "-")
                + " | 注册文件 " + fileServer.getRegisteredFileCount()
                + (fileUrl != null ? " | " + fileUrl : ""));

        println(C_INFO, "Threads   : " + sair.aiagent.core.ThreadManager.getInstance().statusSummary());
        println(C_INFO, "DeepSeek  : " + act.getClient().getUsageSummary());
        for (Map.Entry<String, long[]> e : act.getClient().getUsageByModel().entrySet()) {
            long[] u = e.getValue();
            long cacheTotal = u[2] + u[3];
            double hitRate = cacheTotal > 0 ? (double) u[2] / cacheTotal * 100 : 0;
            println(C_INFO, "  model=" + e.getKey()
                    + " prompt=" + u[0]
                    + " comp=" + u[1]
                    + " cacheHit=" + u[2]
                    + " cacheMiss=" + u[3]
                    + " hitRate=" + String.format("%.0f%%", hitRate));
        }

        sair.aiagent.core.PersistenceManager pm = act.getPersistenceManager();
        int coreMemories = act.getMemory().size();
        int skills = sair.aiagent.core.SkillBank.getInstance().listAll().size();
        int thirdPartySkills = act.getThirdPartySkillStore() != null ? act.getThirdPartySkillStore().size() : 0;
        int skillPackages = act.getAgentSkillStore() != null ? act.getAgentSkillStore().size() : 0;
        int cronTasks = pm != null ? pm.listCronTasks().size() : 0;
        int alarms = pm != null ? pm.listAlarms().size() : 0;
        int harnessTraces = pm != null ? pm.harnessTraceCount() : 0;
        int toolTraces = pm != null ? pm.toolTraceCount() : 0;
        int preferences = pm != null ? pm.listAllPreferences().size() : 0;
        int badImpressions = pm != null ? pm.countBadImpressions() : 0;

        println(C_INFO, "Counts    : memories=" + coreMemories
                + ", skills=" + skills
                + ", thirdPartySkills=" + thirdPartySkills
                + ", skillPackages=" + skillPackages
                + ", cronTasks=" + cronTasks
                + ", alarms=" + alarms
                + ", harnessTraces=" + harnessTraces
                + ", toolTraces=" + toolTraces
                + ", preferences=" + preferences
                + ", badImpressions=" + badImpressions);

        if (act.getOneBotMessageHandler() != null) {
            sair.aiagent.onebot.UnifiedQQMemoryManager qqMemory = act.getOneBotMessageHandler().getUnifiedMemory();
            if (qqMemory != null) {
                println(C_INFO, "QQ Memory : conversations=" + qqMemory.countConversations()
                        + ", memories=" + qqMemory.countMemories()
                        + ", groupHistory=" + qqMemory.countGroupHistory()
                        + ", knownGroups=" + qqMemory.getAllKnownGroups().size()
                        + ", knownFriends=" + qqMemory.getAllKnownFriends().size());
            }
        }

        if (pm != null) {
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
            List<sair.aiagent.core.HarnessTrace> recentHarness = pm.listTraces(5);
            println(C_SYS, "Recent Harness traces (" + recentHarness.size() + "):");
            for (sair.aiagent.core.HarnessTrace t : recentHarness) {
                println(C_INFO, "  " + fmt.format(new Date(t.timestamp))
                        + " [" + t.mode + "] " + (t.success ? "OK" : "FAIL")
                        + " tools=" + t.toolCalls
                        + " " + t.durationMs + "ms"
                        + " | " + brief(t.task, 80));
            }

            List<sair.aiagent.core.ToolCallTrace> recentTools = pm.listToolTraces(5);
            println(C_SYS, "Recent tool calls (" + recentTools.size() + "):");
            for (sair.aiagent.core.ToolCallTrace t : recentTools) {
                println(C_INFO, "  " + fmt.format(new Date(t.timestamp))
                        + " [" + t.channel + "] " + t.toolName
                        + " " + t.outcome
                        + " " + t.durationMs + "ms"
                        + " | " + brief(t.result, 80));
            }
        }
        return true;
    }

    private static String brief(String text, int maxLen) {
        if (text == null) return "(null)";
        String t = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return "(空)";
        if (t.length() > maxLen) t = t.substring(0, maxLen) + "...";
        return t;
    }

    /**
     * 统一配置开关：ai/setconfig &lt;key&gt; &lt;value&gt;，长期存储的 config.properties
     * 均可由命令控制，无需手动编辑文件。
     */
    public Object handleSetConfig(String args) {
        if (isEmpty(args)) {
            println(C_INFO, "== 可配置项（用法: ai/setconfig <key> <value>）==");
            println(C_INFO, "reasoning   思考模式（深度思考）：low/high/max=开启且控制思考深度，none=关闭");
            println(C_INFO, "temperature 温度（输出随机性）：-1=不设置，0~2.0 越高越发散有创意、越低越严谨确定");
            println(C_INFO, "topp        核采样（候选词范围）：-1=不设置，0~1.0 与 temperature 二选一微调");
            println(C_INFO, "maxtokens   单次回复最大长度上限：0=默认，正整数=上限 token 数");
            println(C_INFO, "freq        频率惩罚（抑制重复用词）：-1=不设置，-2.0~2.0 越高越少车轱辘话");
            println(C_INFO, "pres        存在惩罚（鼓励新话题/新词）：-1=不设置，-2.0~2.0 越高越倾向引入新内容");
            println(C_INFO, "strict      Function Calling 严格模式：on=切 /beta+严格JSON Schema(工具调用更可靠)，off=关闭");
            println(C_INFO, "userid      DeepSeek user_id（缓存隔离/内容安全标识）");
            println(C_INFO, "botname     Bot 消息触发词（多个用 ; 分隔，群聊中提到任一触发词触发回复）");
            println(C_INFO, "fileport    文件中转服务端口（跨机器 NapCat 下载文件用，默认 2671，重启后生效）");
            println(C_INFO, "filehost    文件中转服务对外地址（跨机器时填 Windows 本机内网 IP，如 192.168.1.5，空=自动探测）");
            println(C_INFO, "qqfileroots QQ 通道文件访问根目录（分号分隔；留空=不限制，重启后生效）");
            return true;
        }
        String[] parts = args.trim().split("\\s+", 2);
        if (parts.length < 2) return err("用法: ai/setconfig <key> <value>（key 列表见 ai/setconfig）");
        String key = parts[0].trim().toLowerCase();
        String val = parts[1].trim();
        sair.aiagent.core.AiConfig cfg = act.getConfig();
        try {
            switch (key) {
                case "reasoning": case "reasoningeffort":
                    if ("none".equalsIgnoreCase(val) || "off".equalsIgnoreCase(val) || "close".equalsIgnoreCase(val)) {
                        cfg.setReasoningEffort("");
                    } else {
                        cfg.setReasoningEffort(val);
                    }
                    println(C_INFO, "思考模式 -> " + describeReasoningEffort(cfg.getReasoningEffort()));
                    break;
                case "temperature": case "temp":
                    cfg.setTemperature(Double.parseDouble(val));
                    println(C_INFO, "temperature -> " + cfg.getTemperature());
                    break;
                case "topp": case "top_p":
                    cfg.setTopP(Double.parseDouble(val));
                    println(C_INFO, "top_p -> " + cfg.getTopP());
                    break;
                case "maxtokens": case "maxoutputtokens":
                    cfg.setMaxOutputTokens(Integer.parseInt(val));
                    println(C_INFO, "maxOutputTokens -> " + cfg.getMaxOutputTokens());
                    break;
                case "freq": case "frequencypenalty":
                    cfg.setFrequencyPenalty(Double.parseDouble(val));
                    println(C_INFO, "frequencyPenalty -> " + cfg.getFrequencyPenalty());
                    break;
                case "pres": case "presencepenalty":
                    cfg.setPresencePenalty(Double.parseDouble(val));
                    println(C_INFO, "presencePenalty -> " + cfg.getPresencePenalty());
                    break;
                case "strict": case "strictmode":
                    boolean on = "on".equalsIgnoreCase(val) || "true".equalsIgnoreCase(val) || "1".equals(val);
                    cfg.setStrictMode(on);
                    println(C_INFO, "strictMode -> " + (on ? "开（切 /beta + 严格Schema）" : "关"));
                    break;
                case "userid": case "deepseekuserid":
                    cfg.setDeepSeekUserId(val);
                    println(C_INFO, "deepSeekUserId -> " + cfg.getDeepSeekUserId());
                    break;
                case "botname": case "trigger": case "triggerword":
                    cfg.setBotName(val);
                    println(C_INFO, "触发词 -> " + String.join("; ", cfg.getTriggerWords()));
                    break;
                case "fileport": case "fileserverport":
                    int fp = Integer.parseInt(val);
                    if (fp < 1 || fp > 65535) return err("端口范围 1~65535");
                    cfg.setFileServerPort(fp);
                    println(C_INFO, "fileServerPort -> " + cfg.getFileServerPort() + "（重启后生效）");
                    break;
                case "filehost": case "fileserverhost":
                    cfg.setFileServerHost(val);
                    println(C_INFO, "fileServerHost -> " + (cfg.getFileServerHost().isEmpty() ? "(自动探测)" : cfg.getFileServerHost()) + "（重启后生效）");
                    break;
                case "qqfileroots": case "qqfileaccessroots":
                    cfg.setQqFileAccessRoots(val);
                    println(C_INFO, "qqFileAccessRoots -> " + (cfg.getQqFileAccessRoots().isEmpty() ? "(不限制)" : cfg.getQqFileAccessRoots()) + "（重启后生效）");
                    break;
                default:
                    return err("未知配置项: " + key + "（输入 ai/setconfig 查看可配置项）");
            }
            cfg.save();
            return true;
        } catch (NumberFormatException e) {
            return err("值必须是数字: " + val);
        }
    }

    private String describeReasoningEffort(String re) {
        if (re == null || re.isEmpty() || "none".equalsIgnoreCase(re)) return "关闭";
        return re;
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

    // ==================== 数据导入导出 ====================

    /** 导出指定库到 JSON 文件（SFW 命令，仅主人手动触发）。 */
    public Object handleExportData(String args) {
        sair.aiagent.core.PersistenceManager pm = sair.aiagent.core.PersistenceManager.getInstance();
        if (pm == null) return err("持久化层未初始化。");
        String[] parts = (args == null ? "" : args.trim()).split("\\s+", 2);
        String lib = (parts.length > 0 && !parts[0].isEmpty()) ? parts[0].trim().toLowerCase() : "";
        String path = parts.length > 1 ? parts[1].trim() : "";
        if (lib.isEmpty()) return err("用法: ai/exportdata <lib> [path]  （lib=memory/note/impression/sticker；path 缺省存数据目录）");

        String json;
        String defaultName;
        switch (lib) {
            case "memory":     json = pm.exportMemoriesJson(); defaultName = "backup_memory.json"; break;
            case "note":       json = pm.exportNotesJson();    defaultName = "backup_note.json"; break;
            case "impression": json = pm.exportImpressionsJson(); defaultName = "backup_impression.json"; break;
            case "sticker":    json = pm.exportStickersJson(); defaultName = "backup_sticker.json"; break;
            default: return err("未知库名：" + lib + "（可选 memory/note/impression/sticker）");
        }

        File target;
        if (!path.isEmpty()) {
            target = new File(path);
        } else {
            File db = pm.getDbFile();
            String dir = (db != null && db.getParentFile() != null) ? db.getParent() : ".";
            target = new File(dir, defaultName);
        }
        boolean ok = pm.exportJsonToFile(json, target);
        if (ok) {
            println(C_INFO, "已导出 " + lib + " → " + target.getAbsolutePath() + "（" + json.length() + " 字符）");
        } else {
            println(C_ERR, "导出失败：" + target.getAbsolutePath());
        }
        return true;
    }

    /** 从 JSON 文件导入指定库（清空覆盖，仅主人手动触发）。 */
    public Object handleImportData(String args) {
        sair.aiagent.core.PersistenceManager pm = sair.aiagent.core.PersistenceManager.getInstance();
        if (pm == null) return err("持久化层未初始化。");
        String[] parts = (args == null ? "" : args.trim()).split("\\s+", 2);
        if (parts.length < 2 || parts[0].trim().isEmpty() || parts[1].trim().isEmpty()) {
            return err("用法: ai/importdata <lib> <path>  （lib=memory/note/impression/sticker；导入会清空覆盖原库）");
        }
        String lib = parts[0].trim().toLowerCase();
        String path = parts[1].trim();

        File file = new File(path);
        String json = pm.readJsonFromFile(file);
        if (json == null) return err("读取文件失败：" + file.getAbsolutePath());

        switch (lib) {
            case "memory": {
                int n = pm.importMemoriesJson(json);
                if (n < 0) return err("记忆 JSON 解析失败");
                println(C_INFO, "已导入记忆 " + n + " 条（原库已清空覆盖）。");
                return true;
            }
            case "note": {
                int n = pm.importNotesJson(json);
                if (n < 0) return err("笔记 JSON 解析失败");
                println(C_INFO, "已导入笔记 " + n + " 条（原库已清空覆盖）。");
                return true;
            }
            case "impression": {
                int[] r = pm.importImpressionsJson(json);
                if (r[0] < 0) return err("印象 JSON 解析失败");
                println(C_INFO, "已导入人物印象 " + r[0] + " 条、群印象 " + r[1] + " 条（原库已清空覆盖）。");
                return true;
            }
            case "sticker": {
                int n = pm.importStickersJson(json);
                if (n < 0) return err("表情库 JSON 解析失败");
                println(C_INFO, "已导入表情包 " + n + " 条（原库已清空覆盖）。");
                return true;
            }
            default: return err("未知库名：" + lib + "（可选 memory/note/impression/sticker）");
        }
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
        act.setActiveThread(sair.aiagent.core.ThreadManager.getInstance().newDaemonThread("AiAgent-Execs", new Runnable() {
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
        }));
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

        act.setActiveThread(sair.aiagent.core.ThreadManager.getInstance().newDaemonThread("AiAgent-Orchestrate", () -> {
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
        }));
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
