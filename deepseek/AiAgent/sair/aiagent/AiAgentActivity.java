package sair.aiagent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;


import sair.FCM;
import sair.Pathes;
import sair.aiagent.acts.ActivityActions;
import sair.aiagent.core.AgentExecutor;
import sair.aiagent.core.AiConfig;
import sair.aiagent.core.ConfirmationGate;
import sair.aiagent.core.CriticAgent;
import sair.aiagent.core.ConversationHistory;
import sair.aiagent.core.DeepSeekClient;
import sair.aiagent.core.DynamicCodeEngine;
import sair.aiagent.core.EmotionManager;
import sair.aiagent.core.HarnessConfig;
import sair.aiagent.core.JournalManager;
import sair.aiagent.core.MemoryManager;
import sair.aiagent.core.MemoryLifecycleManager;
import sair.aiagent.core.HistoryCompressor;
import sair.aiagent.core.PersistenceManager;
import sair.aiagent.core.CronScheduler;
import sair.aiagent.core.StickerManager;
import sair.aiagent.core.StreamPrinter;
import sair.aiagent.core.AlarmScheduler;
import sair.aiagent.core.ToolContext;
import sair.aiagent.model.AlarmEntry;
import sair.aiagent.onebot.OneBotServer;
import sair.aiagent.onebot.QQMessageHandler;
import sair.aiagent.onebot.NapCatApi;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.user.Activity;
import sair.user.PrintRunnable;

/**
 * AiAgent V3.4 - AI智能助手 | 反射 · 系统终端 · 记忆 · 动态代码注入 · 流式输出 · OneBot QQ
 *
 * <h3>架构</h3>
 * 路由与命令实现分离 —— {@code main()} 仅做初始化 + 委托 {@link ActivityActions#route}，
 * 具体业务逻辑由 {@code ActivityActions} 实现。
 */
public class AiAgentActivity extends Activity {

    // ==================== 修复 SFW 窗口标题乱码 ====================
    /**
     * SFW SairCons.java L78 编码损坏字符串导致 PrintRunnable 激活
     * 时窗口标题变乱码。此静态块注册 AiAgent 的 PrintRunnable，
     * 强制恢复正确标题并正常委托输出，覆盖框架损坏的标题行为。
     */
    static {
        SairCons.addPrintRunnable("AiAgent_TitleFix", new PrintRunnable() {
            @Override
            public void run(Integer index, java.awt.Color c, String info) {
                ConsFrame.setTitleInfo(ConsFrame.title_str);
                ConsFrame.printo(index, c, info);
            }
        });
    }

    // ==================== 组件字段 ====================

    private final AiConfig            config;
    private final ConversationHistory history;
    private final DeepSeekClient      client;
    private final AgentExecutor       agent;
    private final MemoryManager       memory;
    private final DynamicCodeEngine   codeEngine;
    private final ConfirmationGate    gate;
    private final JournalManager      journal;
    private final EmotionManager      emotionManager;
    private final StickerManager      stickerManager;
    private final ActivityActions     actions;

    /** OneBot QQ 集成 */
    private volatile OneBotServer oneBotServer;
    private CronScheduler cronScheduler;
    private volatile QQMessageHandler oneBotMessageHandler;
    private volatile AlarmScheduler alarmScheduler;

    /** 自动清屏守护线程 — 每3分钟执行一次 /clear 防止日志OOM */
    private volatile Thread autoClearThread;
    private volatile boolean autoClearEnabled = false;

    private volatile PersistenceManager persistenceManager;
    private volatile MemoryLifecycleManager memoryLifecycleManager;
    private volatile HistoryCompressor historyCompressor;
    private volatile sair.aiagent.core.ThirdPartySkillStore thirdPartySkillStore;
    private volatile sair.aiagent.core.AgentSkillStore agentSkillStore;
    private volatile Thread activeThread;
    /** 自主蒸馏守护线程（已纳入 ThreadManager 治理） */
    private volatile Thread distillerThread;
    private volatile boolean initialized = false;


    // ==================== Activity 生命周期 ====================

    public AiAgentActivity() {
        debugLog("=== AiAgentActivity 构造 ===");
        config          = AiConfig.getInstance();
        history         = new ConversationHistory();
        client          = new DeepSeekClient(config);
        memory          = new MemoryManager();
        gate            = new ConfirmationGate();
        codeEngine      = new DynamicCodeEngine();
        journal         = new JournalManager();
        emotionManager  = new EmotionManager();
        stickerManager  = new StickerManager();
        agent           = new AgentExecutor(this, client, codeEngine, gate);
        actions         = new ActivityActions(this);
        debugLog("构造完成");
    }

    @Override
    public Object main(String funcName, String args) {
        debugLog("main() 调用: funcName=" + funcName + " args=" + args);
        if (!initialized) {
            debugLog("首次调用 - 初始化中...");
            String dataDir = getDataDir();
            config.init(dataDir);
            HarnessConfig.getInstance().init(dataDir); // Harness 确定性约束配置中心

            // === Redis 旁路缓存初始化（未运行则静默降级，不影响主流程） ===
            sair.aiagent.core.RedisClient.init(
                    config.getRedisHost(), config.getRedisPort(), config.getRedisDb(),
                    config.getRedisPrefix(), config.getRedisPassword(), config.isRedisEnabled());
            debugLog("[Redis] 旁路缓存已初始化，enabled=" + config.isRedisEnabled());

            // === 新持久化架构：PersistentManager 先行 ===
            persistenceManager = new PersistenceManager();
            boolean isNew = persistenceManager.init(dataDir);

            // 注入到各管理器
            memory.setPersistenceManager(persistenceManager);
            journal.setPersistenceManager(persistenceManager);
            history.setPersistenceManager(persistenceManager);
            history.setCacheFile(new File(dataDir, "history.json"));
            emotionManager.setPersistenceManager(persistenceManager);
            stickerManager.setPersistenceManager(persistenceManager);
            stickerManager.setDataDir(dataDir);
            agent.setStickerManager(stickerManager);

            // 从 SQLite 加载（首次运行时自动迁移旧 JSON）
            if (isNew) {
                persistenceManager.migrateFromJson(dataDir);
            }
            memory.load(dataDir);
            journal.load(dataDir);
            history.loadFromFile();
            emotionManager.load(dataDir);

            // === CronScheduler 初始化 ===
            cronScheduler = new CronScheduler(persistenceManager);
            agent.setCronScheduler(cronScheduler);
            cronScheduler.start();

            // === Skills 自进化机制 ===
            sair.aiagent.core.SkillBank skillBank = sair.aiagent.core.SkillBank.getInstance();
            skillBank.init(persistenceManager, client);
            agent.setSkillBank(skillBank);
            sair.aiagent.core.SkillExtractor skillExtractor = new sair.aiagent.core.SkillExtractor(client, skillBank);
            agent.setSkillExtractor(skillExtractor);
            debugLog("[Skills] 技能库已初始化");

            // === 系统默认技能库：通用标签/SFW/动态注入教学 ===
            sair.aiagent.core.SystemSkillLib.initSkills(skillBank);
            debugLog("[Skills] 系统技能库已初始化");

            // === NapCat技能库：QQ通道专属API技能 ===
            sair.aiagent.onebot.NapCatSkillLib.initSkills(skillBank);
            debugLog("[Skills] NapCat技能库已初始化");

            // === 三方技能库：data/skills 目录单文件 .md + 文件监听 ===
            sair.aiagent.core.ThirdPartySkillStore thirdPartyStore = new sair.aiagent.core.ThirdPartySkillStore();
            thirdPartyStore.init(new File(dataDir));
            skillBank.setThirdPartyStore(thirdPartyStore);
            thirdPartySkillStore = thirdPartyStore;
            debugLog("[ThirdPartySkill] 三方技能库已接线（data/skills 目录监听中）");

            // === agentskills.io 技能包库：data/skillpackages 目录 + 文件监听 ===
            sair.aiagent.core.AgentSkillStore agentStore = new sair.aiagent.core.AgentSkillStore();
            agentStore.init(new File(dataDir));
            skillBank.setAgentSkillStore(agentStore);
            this.agentSkillStore = agentStore;
            debugLog("[AgentSkill] agentskills.io 技能包库已接线（data/skillpackages 目录监听中）");

            // === RouteCache: 路径权重路由缓存 ===
            sair.aiagent.core.RouteCache routeCache = new sair.aiagent.core.RouteCache(
                    persistenceManager, persistenceManager.getLock());
            skillBank.setRouteCache(routeCache);
            agent.setRouteCache(routeCache);
            debugLog("[RouteCache] 路径路由缓存已初始化");

            // === ImpressionExtractor: 人格印象蒸馏引擎 ===
            final sair.aiagent.core.ImpressionExtractor impressionExtractor
                    = new sair.aiagent.core.ImpressionExtractor(client, persistenceManager);
            debugLog("[Impression] 人格印象蒸馏引擎已初始化");

            // === AutonomousDistiller: 自主蒸馏守护线程（技能+印象） ===
            final PersistenceManager fPm = persistenceManager;
            final sair.aiagent.core.SkillExtractor fExtractor = skillExtractor;
            final sair.aiagent.core.SkillBank fBank = skillBank;
            final sair.aiagent.core.ImpressionExtractor fImpExtractor = impressionExtractor;
            final sair.aiagent.core.RouteCache fRouteCache = routeCache;
            this.distillerThread = sair.aiagent.core.ThreadManager.getInstance().newDaemonThread("AutoDistill", new Runnable() {
                public void run() {
                    debugLog("[AutoDistill] daemon started (interval=5min)");
                    while (true) {
                        try { Thread.sleep(5 * 60 * 1000); } catch (InterruptedException e) { break; }
                        try {
                            int count = fExtractor.extractFromJournal(fPm, 30);
                            if (count > 0) debugLog("[AutoDistill] journal: +" + count + " skills");
                            int evolved = fBank.evolve();
                            if (evolved > 0) debugLog("[AutoDistill] evolved: " + evolved + " skills");
                            if (oneBotMessageHandler != null) {
                                int impCount = fImpExtractor.distillIfNeeded(
                                        oneBotMessageHandler.getUnifiedMemory());
                                if (impCount > 0) debugLog("[AutoDistill] impression: " + impCount + " users");
                            }
                            // 清理低权重路由（权重 < 0.1）
                            int cleaned = fRouteCache.cleanupLowWeight(0.1);
                            if (cleaned > 0) debugLog("[RouteCache] cleanup: " + cleaned + " routes");
                        } catch (Exception e) { debugLog("[AutoDistill] error: " + e.getMessage()); }
                    }
                    debugLog("[AutoDistill] daemon stopped");
                }
            });
            this.distillerThread.start();
            debugLog("[Skills] 自主蒸馏守护线程已启动（含技能+印象）");

            // === MemoryLifecycleManager: 记忆生命周期管理 ===
            memoryLifecycleManager = new MemoryLifecycleManager(persistenceManager);
            memoryLifecycleManager.start();
            debugLog("[MemLifecycle] 记忆生命周期管理已启动（30分钟间隔）");

            // === HistoryCompressor: 对话历史压缩器 ===
            historyCompressor = new HistoryCompressor(client);
            agent.setHistoryCompressor(historyCompressor);
            debugLog("[HistoryCompressor] 对话历史压缩器已初始化并注入Agent");

            // === CriticAgent: Harness 验证闭环审查者子 Agent（默认关闭，/criticon opt-in） ===
            sair.aiagent.core.CriticAgent criticAgent = new sair.aiagent.core.CriticAgent(client);
            agent.setCriticAgent(criticAgent);
            agent.setCriticEnabled(sair.aiagent.core.HarnessConfig.getInstance().isCriticEnabled());
            debugLog("[CriticAgent] Harness 验证闭环审查者已初始化（默认关闭，/criticon 开启）");

            debugLog("历史加载: " + history.size() + " 条消息");

            // === OneBot QQ 初始化 ===
            initOneBot(dataDir);

            // === AlarmScheduler 系统闹钟调度器（到点唤醒 AI 走 Agent 链路） ===
            final String fAlarmDataDir = dataDir;
            alarmScheduler = new AlarmScheduler(persistenceManager, agent, new AlarmScheduler.AlarmBridge() {
                @Override
                public ToolContext buildToolContext(AlarmEntry alarm) {
                    // 本地控制台闹钟：返回 console 上下文（无 QQ 组件）
                    if (!"execq".equals(alarm.getChannel())) {
                        return new ToolContext("console");
                    }
                    ToolContext ctx = new ToolContext("execq");
                    if (oneBotMessageHandler != null) {
                        ctx.napcatApi = oneBotMessageHandler.getNapcatApi();
                        ctx.unifiedMemory = oneBotMessageHandler.getUnifiedMemory();
                        ctx.internalAgents = oneBotMessageHandler.getInternalAgents();
                        ctx.emotionManager = oneBotMessageHandler.getEmotionManager();
                        ctx.pendingRequestPool = oneBotMessageHandler.getPendingRequestPool();
                    }
                    ctx.senderQQ = alarm.getSenderQq();
                    ctx.isMaster = alarm.isMaster();
                    ctx.dataDir = fAlarmDataDir;
                    ctx.execsMode = "EXECS".equals(alarm.getScope());
                    if (alarm.isGroup()) {
                        sair.aiagent.onebot.model.QQMessage qqMsg = new sair.aiagent.onebot.model.QQMessage();
                        qqMsg.setMessageType("group");
                        qqMsg.setGroupId(alarm.getGroupId());
                        qqMsg.setUserId(alarm.getSenderQq());
                        ctx.qqMsg = qqMsg;
                    }
                    return ctx;
                }

                @Override
                public void sendReply(AlarmEntry alarm, String text) {
                    if (text == null || text.trim().isEmpty()) return;
                    // 本地控制台闹钟：结果打印到控制台
                    if (!"execq".equals(alarm.getChannel())) {
                        debugLog("[Alarm] " + text);
                        return;
                    }
                    if (oneBotMessageHandler == null || oneBotMessageHandler.getNapcatApi() == null) {
                        debugLog("[Alarm] 无 QQ 通道，回传失败: " + text);
                        return;
                    }
                    NapCatApi api = oneBotMessageHandler.getNapcatApi();
                    if (alarm.isGroup()) {
                        String atPrefix = alarm.getSenderQq() > 0
                                ? "[CQ:at,qq=" + alarm.getSenderQq() + "] " : "";
                        api.sendGroupMessage(alarm.getGroupId(), atPrefix + text);
                    } else {
                        api.sendPrivateMessage(alarm.getSenderQq(), text);
                    }
                }
            });
            alarmScheduler.start();
            debugLog("[Alarm] 系统闹钟调度器已启动");

            // === 文件中转 Web 服务（发送本地文件以 HTTP URL 中转给 NapCat） ===
            try {
                if (sair.aiagent.onebot.FileServer.getInstance().start()) {
                    debugLog("[FileServer] 文件中转服务已启动，端口=" + sair.aiagent.onebot.FileServer.getInstance().getPort());
                } else {
                    debugLog("[FileServer] 文件中转服务启动失败，文件发送回退本地路径");
                }
            } catch (Exception e) {
                debugLog("[FileServer] 初始化错误: " + e.toString());
            }

            // === 跨会话上下文 ===
            String prevCtx = memory.loadContext();
            if (prevCtx != null) {
                agent.setPreviousSessionSummary(prevCtx);
                debugLog("已加载上次会话上下文");
            }

            initialized = true;
        }
        return actions.route(funcName, args);
    }

    @Override
    public String[] help() {
        String n = getName();
        return new String[] {
            Pathes.printSplit,
            "AiAgent V3.4 - AI智能助手 | 反射 · 系统终端 · 记忆 · 动态代码 · 流式输出 · OneBot QQ",
            "DeepSeek API, 流式打字机效果, Agent自主操作, 持久化记忆, JS/Java动态注入",
            "配置:",
            "\t" + n + "/setkey [密钥]        设置API密钥",
            "\t" + n + "/seturl [地址]        设置API地址 (默认: " + AiConfig.DEFAULT_API_URL + ")",
            "\t" + n + "/setmodel [模型]      设置模型 (默认: " + AiConfig.DEFAULT_MODEL + ")",
            "\t" + n + "/setthirdpartycode on|off  三方技能代码段权限（默认on放权，所有通道可用；off则仅execs/本地）",
            "\t" + n + "/setprompt [提示词]   设置系统提示词",
            "\t" + n + "/showprompt           显示当前提示词",
            "\t" + n + "/info                 显示配置信息",
            "对话:",
            "\t" + n + "/execs [任务]         Agent模式 (原生Function Calling，免确认全能)",
            "\t" + n + "/orchestrate [pipeline|fanout|expert] [任务]  多智能体编排（流水线/扇出扇入/专家池，opt-in）",
            "\t" + n + "/harnesseval         输出最近Harness执行轨迹评测（成功率/耗时）",
            "\t" + n + "/criticon             开启Harness验证闭环审查（每次回复增加一次独立审查）",
            "\t" + n + "/criticoff            关闭Harness验证闭环审查（默认）",
            "\t" + n + "/reset                重置对话历史",
            "\t" + n + "/stop                 停止当前输出",
            "记忆:",
            "\t" + n + "/memories             列出所有记忆",
            "\t" + n + "/forget [ID]          按ID删除记忆",
            "\t" + n + "/forgetall            清空所有记忆",
            "反射确认:",
            "\t" + n + "/yes                  确认高危操作 (反射/系统命令/动态注入)",
            "\t" + n + "/no                   拒绝高危操作",
            "Agent工具 (Function Calling):",
            "\t系统/文件: cmd, readfile, readdir, sys, evaljs, eval, web, download",
            "\t记忆/知识: remember, note, searchnote, schedule, skillextract, skillinfo",
            "\t媒体/文件: sendimage, sendrecord, sendfile, batchrename, batchconvert",
            "\t图片注释: setimageremark (注释与图片MD5强绑定持久化)",
            "\t其他: balance, weather, superise, editprompt, stop",
            "\t系统闹钟: alarm (add schedule|task / list / remove id / cancel id / append id|备注，到点唤醒AI执行)",
            "QQ OneBot:",
            "\t" + n + "/onebotconnect       启动OneBot服务",
            "\t" + n + "/onebotdisconnect    停止OneBot服务",
            "\t" + n + "/onebotstatus        查看连接状态",
            "\t" + n + "/onebotsetport [端口] 设置监听端口 (默认5800)",
            "\t" + n + "/onebotsettoken [Token] 设置Access Token",
            "\t" + n + "/onebotsetselfid [QQ号] 设置机器人QQ号",
            "\t" + n + "/onebotsetprompt [提示词] 设置execq通道提示词",
            "\t" + n + "/onebotshowprompt 显示execq提示词",
            "监听群设置:",
            "\t" + n + "/onebotaddgroup [群号1 群号2 ...]   添加监听群（空格分隔多个）",
            "\t" + n + "/onebotremovegroup [群号1 群号2 ...] 移除监听群（空格分隔多个）",
            "\t" + n + "/onebotlistgroups        列出监听的群",
            "主动查看:",
            "\t" + n + "/onebotenableproactive   启用主动查看（每5分钟检查群聊）",
            "\t" + n + "/onebotdisableproactive  禁用主动查看",
            "监听机制:",
            "\t" + n + "/onebotenablelisten   启用监听态（群聊后自动关注相关消息）",
            "\t" + n + "/onebotdisablelisten  禁用监听态",
            "好感度:",
            "\t" + n + "/resetaffection      置空所有人的好感度（清空内存缓存+数据库）",
            "\t" + n + "/resetdonations      清空全部捐赠记录（数据库层面彻底重置）",
            "QQ日志:",
			"\t" + n + "/qqlogon              开启QQ消息控制台输出",
			"\t" + n + "/qqlogoff             关闭QQ消息控制台输出",
			"自动清屏:",
            "\t" + n + "/autoclearon           启用自动清屏（每3分钟 /clear 防OOM）",
            "\t" + n + "/autoclearoff          停止自动清屏",
            "三方技能库:",
            "\t单文件技能：将 .md 文件放入 data/skills/ 目录即自动加载（front matter 声明 name/description）",
            "\t文件名必须与 name 字段同名；同名三方技能优先于内置；删除文件自动移除；编辑自动重载",
            "\t管理工具 thirdskill（list/add/delete，仅 execs / QQ execs 可用）",
            "\t代码段：md 内可用 <java/js/nodejs/python start>…end> 内嵌可执行代码，固定入口 airun（强制返回值）",
            "\t  java/js 由 JVM 原生执行；nodejs/python 走系统命令行（缺失时红字提示自行安装）",
            "\tairun 签名：front matter 声明 airun(description+params) 后，自动注册为工具 tp_技能名，AI 直接调用",
            "\t  未声明签名则工具退化为单个 args 参数；也可用 callskill(skill, args) 兜底",
            "\t  权限受 /setthirdpartycode 控制：on=放权（所有通道，含QQ）；off=仅 execs/本地",
            "\t注意：三方技能库（单文件 + 技能包）数量越多，注入 token 消耗越大，会相应影响 API 余额",
            "agentskills.io 技能包:",
            "\t将符合 agentskills.io 标准的技能包（含 SKILL.md）放入 data/skillpackages/ 目录即自动加载",
            "\tzip 技能包放入后自动解压导入并删除原 zip；删除目录自动移除",
            "技能自进化:",
            "\t" + n + "/skills               列出所有学习到的技能",
            "\t" + n + "/skillsearch [关键词]  FTS5全文搜索技能",
            "\t" + n + "/skillinfo [ID]        查看技能详情与内容",
            "\t" + n + "/skilldelete [ID]      删除指定技能",
            "\t" + n + "/skillextract          从最近日志中LLM提取技能",
            "\t" + n + "/skillevolve           LLM分析失败模式并进化技能",
            "\t" + n + "/skillexport [ID]      导出单个技能为Markdown（系统内置技能不可导出）",
            "\t" + n + "/skillexportall        导出全部技能为Markdown（系统内置技能自动排除）",
            "\t" + n + "/clearstickers         清空表情包图片库（本地+数据库）",
            "\t" + n + "/execq [消息]         QQ通道Agent (受限标签自动允许)",
            "\t" + "  QQ主人消息: execs:任务 (全权免确认执行)",
            Pathes.printSplit,
        };
    }

    @Override
    public void exit() {
        stopAutoClear();
        // === 统一线程治理：中断所有 ThreadManager 注册的守护线程（含 AutoDistill） ===
        try { sair.aiagent.core.ThreadManager.getInstance().shutdown(); } catch (Exception ignored) {}
        // === 停止三方技能库文件监听 ===
        if (thirdPartySkillStore != null) {
            thirdPartySkillStore.shutdown();
            thirdPartySkillStore = null;
        }
        // === 停止 agentskills.io 技能包库文件监听 ===
        if (agentSkillStore != null) {
            agentSkillStore.shutdown();
            agentSkillStore = null;
        }
        // === 关闭 Redis 旁路缓存 ===
        try {
            sair.aiagent.core.RedisClient redis = sair.aiagent.core.RedisClient.getInstance();
            if (redis != null) redis.close();
        } catch (Exception ignored) {}
        // === 停止记忆生命周期管理器 ===
        if (memoryLifecycleManager != null) {
            memoryLifecycleManager.stop();
            debugLog("[MemLifecycle] 守护线程已停止");
        }
        // === 停止OneBot ===
        if (oneBotServer != null) {
            oneBotServer.stop();
        }
        if (oneBotMessageHandler != null) {
            oneBotMessageHandler.shutdown();
        }
        // === 停止系统闹钟调度器 ===
        if (alarmScheduler != null) {
            alarmScheduler.stop();
            alarmScheduler = null;
        }
        // === 停止文件中转服务 ===
        sair.aiagent.onebot.FileServer.getInstance().stop();
        stopActivePrinter();
        if (persistenceManager != null) {
            persistenceManager.close();
            persistenceManager = null;
        }
        // 关闭调试日志写入器
        synchronized (debugLock) {
            if (debugWriter != null) {
                debugWriter.close();
                debugWriter = null;
            }
        }
    }

    // ==================== 自动清屏守护线程 ====================

    /** 启动自动清屏守护线程 — 每3分钟执行 SairCons.runner(false, "/clear") */
    public synchronized void startAutoClear() {
        if (autoClearEnabled) return;
        autoClearEnabled = true;
        autoClearThread = new Thread(() -> {
            debugLog("[AutoClear] 守护线程启动，每3分钟清屏一次");
            while (autoClearEnabled) {
                try { Thread.sleep(180_000); } catch (InterruptedException e) { break; }
                if (!autoClearEnabled) break;
                try {
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        try { SairCons.runner(false, "/clear"); } catch (Exception ignored) {}
                    });
                    debugLog("[AutoClear] 已执行清屏");
                } catch (Exception e) {
                    debugLog("[AutoClear] 清屏失败: " + e.toString());
                }
            }
            debugLog("[AutoClear] 守护线程退出");
        }, "AiAgent-AutoClear");
        autoClearThread.setDaemon(true);
        autoClearThread.start();
    }

    /** 停止自动清屏守护线程 */
    public synchronized void stopAutoClear() {
        autoClearEnabled = false;
        if (autoClearThread != null) {
            autoClearThread.interrupt();
            autoClearThread = null;
        }
    }

    /** @return 自动清屏是否已启用 */
    public boolean isAutoClearEnabled() { return autoClearEnabled; }

    /** 初始化 OneBot QQ 集成 */
    private void initOneBot(String dataDir) {
        try {
            oneBotServer = new OneBotServer();
            oneBotServer.setDataDir(dataDir);

            oneBotMessageHandler = new QQMessageHandler();
            oneBotMessageHandler.setDataDir(dataDir);          // 必须在setServer之前调用！
            oneBotMessageHandler.setServer(oneBotServer);
            oneBotMessageHandler.setAgentExecutor(agent);
            oneBotMessageHandler.setSelfId(config.getOnebotSelfId());

            oneBotServer.setMessageHandler(oneBotMessageHandler);
            oneBotServer.setPort(config.getOnebotPort());
            oneBotServer.setAccessToken(config.getOnebotToken());

            // === 配置主动查看功能 ===
            if (config.isProactiveCheckEnabled()) {
                oneBotMessageHandler.enableProactiveCheck();
                for (Long groupId : config.getMonitoredGroups()) {
                    oneBotMessageHandler.addMonitoredGroup(groupId);
                }
                qqLog("[OneBot] 主动查看已启用，监听群: " + config.getMonitoredGroups());
            }

            // === 配置拟人化监听态开关 ===
            if (config.isListenStateEnabled()) {
                oneBotMessageHandler.enableListeningState();
            } else {
                oneBotMessageHandler.disableListeningState();
                qqLog("[OneBot] 拟人化监听态已禁用（使用 execPool 并发处理）");
            }

            qqLog("[OneBot] 已初始化. 启用=" + config.isOnebotEnabled()
                    + " 端口=" + config.getOnebotPort());

            // 如果配置中已启用，自动启动
            if (config.isOnebotEnabled()) {
                if (oneBotServer.start()) {
                    qqLog("[OneBot] 自动启动，端口: " + config.getOnebotPort());
                } else {
                    qqLog("[OneBot] 自动启动失败");
                }
            }
        } catch (Exception e) {
            qqLog("[OneBot] 初始化错误: " + e.toString());
        }
    }

    @Override
    protected String dataDir() {
        return "sair.aiagent.AiAgentActivity";
    }

    // ==================== 公开 Getter ====================

    public AiConfig getConfig()                { return config; }
    public ConversationHistory getHistory()     { return history; }
    public DeepSeekClient getClient()           { return client; }
    public AgentExecutor getAgent()             { return agent; }
    public MemoryManager getMemory()            { return memory; }
    public DynamicCodeEngine getCodeEngine()    { return codeEngine; }
    public ConfirmationGate getGate()           { return gate; }
    public JournalManager getJournal()          { return journal; }
    public EmotionManager getEmotionManager()   { return emotionManager; }

    /** OneBot QQ 集成 */
    public OneBotServer getOneBotServer()              { return oneBotServer; }
    public QQMessageHandler getOneBotMessageHandler()   { return oneBotMessageHandler; }

    public Thread getActiveThread()             { return activeThread; }
    public void setActiveThread(Thread t)       { this.activeThread = t; }

    // ==================== 公开工具方法 ====================

        // ==================== Debug 日志 ====================

    private static PrintWriter debugWriter;
    private static final Object debugLock = new Object();

    /** QQ消息控制台输出开关 — 默认开启，通过 ai/qqlogoff 关闭 */
    private static volatile boolean qqLogEnabled = true;

    /** Debug log to file - cross-platform */
    public static void debugLog(String msg) {
        synchronized (debugLock) {
            try {
                SairCons.println("[AiAgent] " + msg);
                if (debugWriter == null) {
                    debugWriter = new PrintWriter(new OutputStreamWriter(
                            new FileOutputStream(
                                System.getProperty("user.home") + File.separator + "aiagent_debug.log", true),
                            StandardCharsets.UTF_8), true);
                }
                debugWriter.println(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + " " + msg);
            } catch (Exception ignored) {}
        }
    }

    /** QQ消息专用日志：受 qqLogEnabled 开关控制 */
    public static void qqLog(String msg) {
        if (!qqLogEnabled) return;
        debugLog(msg);
    }

    public static boolean isQqLogEnabled() { return qqLogEnabled; }
    public static void setQqLogEnabled(boolean v) { qqLogEnabled = v; }

    /** 停止当前输出并清理 */
    public void stopActivePrinter() {
        if (activeThread != null && activeThread.isAlive()) {
            activeThread.interrupt();
            activeThread = null;
        }
        StreamPrinter.getInstance().flushAndStop();
        agent.markStopped();
    }
}

