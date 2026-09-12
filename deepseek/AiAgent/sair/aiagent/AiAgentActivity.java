package sair.aiagent;

import java.io.File;


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
 * AiAgent V4.0 - AI智能助手 | 反射 · 系统终端 · 记忆 · 动态代码注入 · 流式输出 · OneBot QQ
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
        DeepSeekClient.setInstance(client);   // 供三方技能复用同一份客户端
        memory          = new MemoryManager();
        MemoryManager.setInstance(memory);   // 供三方技能复用同一份记忆实现
        gate            = new ConfirmationGate();
        ConfirmationGate.setInstance(gate);   // 供三方技能复用同一份确认闸门（ai/yes、execs 绕过都作用它）
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
            sair.aiagent.core.PromptManager.getInstance().init(new File(dataDir)); // 提示词独立 md 文件（systemPrompt.md / execqPrompt.md）
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
            stickerManager.setDeepSeekClient(client);
            stickerManager.startAutoCleanup();
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
            CronScheduler.setInstance(cronScheduler);   // 供三方技能复用同一份调度器
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

            // === 三方技能库：data/skills 目录「一技能一文件夹（md + Java 源码）」+ 文件监听 ===
            sair.aiagent.core.ThirdPartySkillStore thirdPartyStore = new sair.aiagent.core.ThirdPartySkillStore();
            thirdPartyStore.init(new File(dataDir));
            skillBank.setThirdPartyStore(thirdPartyStore);
            thirdPartySkillStore = thirdPartyStore;
            // 加载期注册：把技能文件夹里的 .java 编译并登记进运行时。
            // 这样调用时不再动态编译、编译错误在加载期就暴露；改 md/.java 时由 store 重扫触发「新替旧」。
            thirdPartyStore.setCodeRunner(new sair.aiagent.core.SkillCodeRunner(
                    codeEngine, new File(dataDir, "tmp")));
            debugLog("[ThirdPartySkill] 三方技能库已接线（data/skills 目录与技能文件夹都在监听中）");

            // 技能是外置资产（不随 jar 发布）：核对「已剥离工具」是否都由技能提供。
            // 缺就明确点名，避免工具静默消失（模型以为能调、用户以为坏了）。
            String missingPeeled = thirdPartyStore.describeMissingPeeledTools();
            if (missingPeeled != null) {
                sair.aiagent.util.EdtUtils.println(FCM.Error_Color, missingPeeled);
                debugLog(missingPeeled);
            } else {
                debugLog("[ThirdPartySkill] 已剥离的 "
                        + sair.aiagent.core.ThirdPartySkillStore.PEELED_TOOL_NAMES.length
                        + " 个工具均由技能提供");
            }

            // 注：多文件技能包（agentskills.io / data/skillpackages）自 V3.14 起已整体移除，
            // 三方技能只有「一技能一文件夹（md + Java 源码）」一种形态。

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
                    String base = sair.aiagent.onebot.FileServer.getInstance().getPublicBaseUrl();
                    debugLog("[FileServer] 文件中转服务已启动，对外地址=" + base);
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
            "AiAgent V4.0 - AI智能助手 | 反射 · 系统终端 · 记忆 · 动态代码 · 流式输出 · OneBot QQ",
            "DeepSeek API, 流式打字机效果, Agent自主操作, 持久化记忆, JS/Java动态注入",
            "配置:",
            "\t" + n + "/setkey [密钥]        设置API密钥",
            "\t" + n + "/seturl [地址]        设置API地址 (默认: " + AiConfig.DEFAULT_API_URL + ")",
            "\t" + n + "/setmodel [模型]      设置模型 (默认: " + AiConfig.DEFAULT_MODEL + ")",
            "\t" + n + "/setthirdpartycode on|off  三方技能代码段权限（默认on放权，所有通道可用；off则仅execs/本地）",
            "\t" + n + "/setprompt [提示词]   设置系统提示词（只替换「角色设定」，基础规则不动）",
            "\t" + n + "/showprompt           显示当前提示词",
            "\t" + n + "/config              显示配置信息",
            "\t" + n + "/status              显示运行时状态（OneBot/Redis/FileServer/线程/轨迹/计数）",
            "\t" + n + "/ctx                 上下文诊断（提示词各段字符数/估算token + 累计截断丢弃计数）",
            "\t" + n + "/dedup [apply] [阈值]  技能库去重：扫描近似重复的自学经验（默认阈值0.85，预演不改库；apply 执行合并）",
            "\t" + n + "/setconfig <key> <value>  设置配置项（思考模式/温度/top_p等，输入 setconfig 看用法）",
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
            "数据备份:",
            "\t" + n + "/exportdata <lib> [path]  导出库为JSON（lib=memory/note/impression/sticker；path缺省存数据目录）",
            "\t" + n + "/importdata <lib> <path>  从JSON导入库（清空覆盖原库）",
            "提示词（全部外置在 md 里，Java 内不保留正文；改文件即热重载，不用重编译）:",
            "\tdata/prompts/systemPrompt.md（聊天/本地底座）· execqPrompt.md（QQ 通道）· agentPrompt.md（execs 追加段）",
            "\t  放在 data/prompts/ 下最整洁；也可直接放数据目录根（两种位置都认，prompts/ 优先）",
            "\t  三份文件都在插件数据目录；仓库 prompts/ 下是「内容与内置时代完全一致」的可拷贝副本",
            "\t  标记行 <!-- AiAgent:base --> / <!-- AiAgent:persona --> 只用于分界，加载时被剥离、不进提示词",
            "\t  改文件即热重载（约 0.5 秒节流）；" + n + "/setprompt 与 editprompt 只替换「角色设定」段，",
            "\t    上面的规则原样保留（想改规则就直接编辑文件正文）",
            "\t  ★ 文件不存在 = 该提示词为空（不报警）：想要提示词就必须把文件放进数据目录",
            "反射确认:",
            "\t" + n + "/yes                  确认高危操作 (反射/系统命令/动态注入)",
            "\t" + n + "/no                   拒绝高危操作",
            "Agent工具 (Function Calling):",
            "\t系统/文件: cmd, readfile, readdir, findfile, sys, evaljs, eval, web, download",
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
            "技能库（一技能一文件夹：md 文档 + Java 源码）:",
            "技能彻底外置：不进 jar，请自行把 skills/ 目录内容放到 data/skills（目录被监听，放下即热加载）",
            "\t纯文档技能：data/skills/<技能名>.md（平铺，只有文档、无代码）",
            "\t工具技能：data/skills/<技能名>/<技能名>.md + *.java（文件夹与 md 必须同名；同名散文件会被忽略）",
            "\t  项目 skills/ 目录是本仓库的副本，拷进 data/skills/ 即热加载；删除即移除；编辑即重载",
            "\t  同一技能可以有多个 .java（辅助类放同目录一起编译）；入口类 = 带 airun 方法的那个",
            "\t  多个类都有 airun 时用 front matter entry: <类名> 指定；文件名建议与类名一致",
            "\t  只支持 Java（V4.0 起 js/nodejs/python 已移除）；内嵌 <java start> 标签写法已废弃",
            "\t  绝不加载 .class/.jar 等二进制（技能目录不是 classpath）",
            "\t已剥离工具（实现只在技能里，Java 侧已删除）：readfile/readdir/findfile/web/search/download/",
            "\t  note/searchnote/correct/balance/weather/schedule/editprompt/settrigger/batchrename/",
            "\t  batchconvert/setimageremark/time/skillinfo/thirdskill —— 删掉对应技能文件夹，工具就会消失",
            "\t安全闸门仍留在 Java：确认闸门（ConfirmationGate）/ 内网拦截（NetGuard）/ 权限矩阵 / 路径规则；",
            "\t  技能只能调用，改技能文件改不动这些判定",
            "\t管理工具 thirdskill（list/validate/add/delete，仅 execs / QQ execs 可用）",
            "\t  validate：重扫目录+重编译，点名「配对失败 / 编译失败 / 没 airun 入口 / 遗留内嵌标签 / 未声明通道权限」",
            "\t  md 与 .java 一被加载就编译并注册进运行时（改文件=新替旧，内容未变则复用），调用时不再动态编译",
            "\t  Java 源码改动（含辅助类）也会触发重载：技能文件夹本身在监听范围内",
            "\tfront matter: name(须等于文件夹名与文件名) / description / channels(该能力可走的通道) / permission(所需权限) / tool(顶替的内置工具名)",
            "\t  entry: AirunSkill           # 可选，多个类都有 airun 时指定入口类",
            "\t  airun:",
            "\t    description: 工具说明（展示给模型）",
            "\t    permission: AFFECTION:300     # 可选，覆盖默认权限位（默认 AFFECTION:300，主人=ROOT 不受限）",
            "\t    returns: 返回一句话        # 可选，写进工具描述的是「返回什么」，帮模型决定怎么用结果",
            "\t    context: true             # 可选，airun 第二入参收到只读上下文（channel/user_id/group_id/is_master/affection/data_dir…）",
            "\t    timeout: 30               # 可选，单次调用超时秒数（5~300，默认 30）",
            "\t    state: per_call           # 可选，per_call(默认，每次新实例) / shared(复用实例，注意并发)",
            "\t    params:",
            "\t      city:                       # 参数名",
            "\t        type: string              # string/integer/number/boolean/array/object",
            "\t        description: 城市名",
            "\t        required: true            # true/yes/1 均可",
            "\t        default: 北京            # 可选，模型没填时由框架补上",
            "\t        enum: [晴,雨,雪]         # 可选，限定取值（写进 JSON Schema 的 enum）",
            "\t        example: 上海            # 可选，示例（会拼进参数描述，显著提高模型填对率）",
            "\t      tags:",
            "\t        type: array",
            "\t        items: string             # 数组元素类型",
            "\t        items_description: 标签名 # 可选，元素说明",
            "\t  代码侧可选签名（按此顺序尝试）：airun(Map 入参, Map 上下文) → airun(Map, String) → airun(String, Map) → airun(String, String) → airun(Map) → airun(String) → airun()",
            "\t  入口方法与传参方式是统一的（就是上面这一行）；但每个工具的 params 各自不同 —— 那是给模型看的",
            "\t  工具 schema，不是方法签名，因此不存在「按参数名找方法」这回事（参数永远是一个 Map）",
            "\t  同名类不冲突：每个技能由自己的 ClassLoader 定义（20 个技能都叫 AirunSkill 也能各跑各的）",
            "\t  entry: <类名> 仅在「一个技能里有多个类都写了 airun」时才需要；跨技能不能互相引用",
            "\t  声明了 params 时，入参会按声明类型归一化（\"3\"→3、\"true\"→true、单值→单元素数组），缺省值已补齐",
            "\t  返回 Map/List/数组会自动序列化为 JSON；返回空值视为「执行成功但无输出」",
            "\t  技能里的 println/print 会被捕获并回显在工具结果里；抛异常时返回技能栈帧（前 5 帧）",
            "\t  未声明 params 则工具退化为单个 args 参数；也可用 callskill(skill, args) 兜底",
            "\t工具名规则：官方要求 function.name 匹配 ^[a-zA-Z0-9_-]+$，中文名会被规范化为 tp_skill-<hash>",
            "\t  并在控制台打印映射；skillinfo 用原名或规范化名都能查到",
            "\t权限：tp_ 工具默认 AFFECTION:300（主人不受限）；/setthirdpartycode on=所有通道可用，off=仅 execs/本地",
            "\t注意：三方技能数量越多，注入 token 消耗越大，会相应影响 API 余额（zip 式技能包仍然不支持）",
            "技能自进化:",
            "\t" + n + "/skills               列出所有学习到的技能",
            "\t" + n + "/skillsearch [关键词]  FTS5全文搜索技能",
            "\t" + n + "/skillinfo [ID]        查看技能详情与内容",
            "\t" + n + "/skilldelete [ID]      删除指定技能",
            "\t" + n + "/skillextract          从最近日志中LLM提取技能",
            "\t" + n + "/skillevolve           LLM分析失败模式并进化技能",
            "\t" + n + "/skillexport [ID]      导出单个技能为Markdown（系统内置技能不可导出）",
            "\t" + n + "/skillexportall        导出全部技能为Markdown（系统内置技能自动排除）",
            "\t" + n + "/dedup [apply] [阈值]  去重：扫描「同一条经验的不同说法」并合并",
            "\t  默认阈值 0.85（只拦近乎复述的）；不带 apply 只预演不改库，确认后 /dedup apply",
            "\t  更低阈值更激进（如 /dedup 0.7）；合并=标记 merged 并指向代表条目，内容不删除",
            "\t  知识库笔记同样支持: /dedup notes [apply] [阈值]（按标题=问题的不同问法去重）",
            "\t  两者一起: /dedup all apply；提取端已自带同样的抑制（被拦下的改写累加 dup_hit_count=重要度）",
            "\t" + n + "/clearstickers         清空表情包图片库（本地+数据库）",
            "\t" + n + "/execq [消息]         QQ通道Agent (受限标签自动允许)",
            "\t" + "  QQ主人消息: execs:任务 (全权免确认执行)",
            Pathes.printSplit,
        };
    }

    @Override
    public void exit() {
        stopAutoClear();
        // === 停止定时任务调度（先取消 future，再统一关闭线程池） ===
        if (cronScheduler != null) {
            cronScheduler.stop();
            cronScheduler = null;
        }
        // === 统一线程治理：中断所有 ThreadManager 注册的守护线程（含 AutoDistill） ===
        try { sair.aiagent.core.ThreadManager.getInstance().shutdown(); } catch (Exception ignored) {}
        // === 停止三方技能库文件监听 ===
        if (thirdPartySkillStore != null) {
            thirdPartySkillStore.shutdown();
            thirdPartySkillStore = null;
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
    }

    // ==================== 自动清屏守护线程 ====================

    /** 启动自动清屏守护线程 — 每3分钟执行 SairCons.runner(false, "/clear") */
    public synchronized void startAutoClear() {
        if (autoClearEnabled) return;
        autoClearEnabled = true;
        autoClearThread = sair.aiagent.core.ThreadManager.getInstance().newDaemonThread("AiAgent-AutoClear", () -> {
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
        });
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
            stickerManager.setNapcatApi(oneBotMessageHandler.getNapcatApi());
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

    /** 运行时状态查询所需组件。 */
    public PersistenceManager getPersistenceManager()   { return persistenceManager; }
    public CronScheduler getCronScheduler()             { return cronScheduler; }
    public AlarmScheduler getAlarmScheduler()           { return alarmScheduler; }
    public sair.aiagent.core.ThirdPartySkillStore getThirdPartySkillStore() { return thirdPartySkillStore; }

    public Thread getActiveThread()             { return activeThread; }
    public void setActiveThread(Thread t)       { this.activeThread = t; }

    // ==================== 公开工具方法 ====================

        // ==================== Debug 日志 ====================

    /** QQ消息控制台输出开关 — 默认开启，通过 ai/qqlogoff 关闭 */
    private static volatile boolean qqLogEnabled = true;

    /** Debug log 仅输出到 SFW 控制台（不写文件） */
    public static void debugLog(String msg) {
        try {
            SairCons.println("[AiAgent] " + msg);
        } catch (Exception ignored) {}
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

