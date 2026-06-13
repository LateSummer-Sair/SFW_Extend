package sair.aiagent;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;


import sair.Pathes;
import sair.aiagent.acts.ActivityActions;
import sair.aiagent.core.AgentExecutor;
import sair.aiagent.core.AiConfig;
import sair.aiagent.core.ConfirmationGate;
import sair.aiagent.core.ConversationHistory;
import sair.aiagent.core.DeepSeekClient;
import sair.aiagent.core.DynamicCodeEngine;
import sair.aiagent.core.EmotionManager;
import sair.aiagent.core.JournalManager;
import sair.aiagent.core.MemoryManager;
import sair.aiagent.core.PersistenceManager;
import sair.aiagent.core.StreamPrinter;
import sair.aiagent.onebot.OneBotServer;
import sair.aiagent.onebot.QQMessageHandler;
import sair.sys.SairCons;
import sair.user.Activity;

/**
 * AiAgent v1.4 - AI智能助手 | 反射 · 系统终端 · 记忆 · 动态代码注入 · 流式输出
 *
 * <h3>架构</h3>
 * 路由与命令实现分离 —— {@code main()} 仅做初始化 + 委托 {@link ActivityActions#route}，
 * 具体业务逻辑由 {@code ActivityActions} 实现。
 */
public class AiAgentActivity extends Activity {

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
    private final ActivityActions     actions;

    /** OneBot QQ 集成 */
    private volatile OneBotServer oneBotServer;
    private volatile QQMessageHandler oneBotMessageHandler;

    private volatile PersistenceManager persistenceManager;
    private volatile Thread activeThread;
    private volatile boolean initialized = false;

    // ==================== 构造 ====================

    public AiAgentActivity() {
        debugLog("=== AiAgentActivity 构造 ===");
        config     = AiConfig.getInstance();
        history    = new ConversationHistory();
        client     = new DeepSeekClient(config);
        memory     = new MemoryManager();
        gate       = new ConfirmationGate();
        codeEngine = new DynamicCodeEngine();
        journal    = new JournalManager();
        emotionManager = new EmotionManager();
        agent      = new AgentExecutor(this, client, codeEngine, gate);
        actions    = new ActivityActions(this);
        debugLog("构造完成");
    }

    // ==================== Activity 生命周期 ====================

    @Override
    public Object main(String funcName, String args) {
        debugLog("main() 调用: funcName=" + funcName + " args=" + args);
        if (!initialized) {
            debugLog("首次调用 - 初始化中...");
            String dataDir = getDataDir();
            config.init(dataDir);

            // === 新持久化架构：PersistentManager 先行 ===
            persistenceManager = new PersistenceManager();
            boolean isNew = persistenceManager.init(dataDir);

            // 注入到各管理器
            memory.setPersistenceManager(persistenceManager);
            journal.setPersistenceManager(persistenceManager);
            history.setPersistenceManager(persistenceManager);
            history.setCacheFile(new File(dataDir, "history.json"));
            emotionManager.setPersistenceManager(persistenceManager);

            // 从 SQLite 加载（首次运行时自动迁移旧 JSON）
            if (isNew) {
                persistenceManager.migrateFromJson(dataDir);
            }
            memory.load(dataDir);
            journal.load(dataDir);
            history.loadFromFile();
            emotionManager.load(dataDir);

            debugLog("历史加载: " + history.size() + " 条消息");

            // === OneBot QQ 初始化 ===
            initOneBot(dataDir);

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
            "AiAgent v1.4 - AI智能助手 | 反射 · 系统终端 · 记忆 · 动态代码 · 流式输出",
            "DeepSeek API, 流式打字机效果, Agent自主操作, 持久化记忆, JS/Java动态注入",
            "配置:",
            "\t" + n + "/setkey [密钥]        设置API密钥",
            "\t" + n + "/seturl [地址]        设置API地址 (默认: " + AiConfig.DEFAULT_API_URL + ")",
            "\t" + n + "/setmodel [模型]      设置模型 (默认: " + AiConfig.DEFAULT_MODEL + ")",
            "\t" + n + "/setprompt [提示词]   设置系统提示词",
            "\t" + n + "/showprompt           显示当前提示词",
            "\t" + n + "/info                 显示配置信息",
            "对话:",
            "\t" + n + "/chat [消息]          AI对话 (流式打字机输出)",
            "\t" + n + "/exec [任务]          Agent模式 (高危操作需确认)",
            "\t" + n + "/execs [任务]         Agent安全模式 (自动允许高危操作)",
            "\t" + n + "/reset                重置对话历史",
            "\t" + n + "/stop                 停止当前输出",
            "记忆:",
            "\t" + n + "/memories             列出所有记忆",
            "\t" + n + "/forget [ID]          按ID删除记忆",
            "\t" + n + "/forgetall            清空所有记忆",
            "反射确认:",
            "\t" + n + "/yes                  确认高危操作 (反射/系统命令/动态注入)",
            "\t" + n + "/no                   拒绝高危操作",
            "Agent XML标签 (内部):",
            "\t<cmd>命令</cmd>               执行SFW命令",
            "\t<readfile>路径</readfile>     读取文件",
            "\t<readdir>路径</readdir>       列出目录",
            "\t<sys>命令</sys>               执行系统命令",
            "\t<evaljs>代码</evaljs>         执行JavaScript",
            "\t<eval>代码</eval>             编译执行Java (须定义run方法)",
            "\t<web>URL</web>                联网获取网页内容",
            "\t<remember>内容</remember>     记录重要信息到持久化记忆",
            "\t<download>URL</download>        下载文件/依赖到 dataDir/downloads/",
            "QQ OneBot:",
            "\t" + n + "/onebotconnect       启动OneBot服务",
            "\t" + n + "/onebotdisconnect    停止OneBot服务",
            "\t" + n + "/onebotstatus        查看连接状态",
            "\t" + n + "/onebotsetport [端口] 设置监听端口 (默认5800)",
            "\t" + n + "/onebotsettoken [Token] 设置Access Token",
            "\t" + n + "/onebotsetselfid [QQ号] 设置机器人QQ号",
            "\t" + n + "/onebotsetprompt [提示词] 设置execq通道提示词",
            "\t" + n + "/onebotshowprompt 显示execq提示词",
            "\t" + n + "/onebotwhitelist       显示execq插件白名单",
            "\t" + n + "/onebotwhitelistadd [插件名] 添加插件到白名单",
            "\t" + n + "/onebotwhitelistremove [插件名] 移除插件",
            "主动查看:",
            "\t" + n + "/onebotenableproactive   启用主动查看（每5分钟检查群聊）",
            "\t" + n + "/onebotdisableproactive  禁用主动查看",
            "\t" + n + "/onebotaddgroup [群号]   添加监听的群",
            "\t" + n + "/onebotremovegroup [群号] 移除监听的群",
            "\t" + n + "/onebotlistgroups        列出监听的群",
            "\t" + n + "/execq [消息]         QQ通道Agent (受限标签自动允许)",
            Pathes.printSplit,
        };
    }

    @Override
    public void exit() {
        // === 停止OneBot ===
        if (oneBotServer != null) {
            oneBotServer.stop();
        }
        if (oneBotMessageHandler != null) {
            oneBotMessageHandler.shutdown();
        }
        stopActivePrinter();
        if (persistenceManager != null) {
            persistenceManager.close();
            persistenceManager = null;
        }
    }

    /** 初始化 OneBot QQ 集成 */
    private void initOneBot(String dataDir) {
        try {
            oneBotServer = new OneBotServer();
            oneBotServer.setDataDir(dataDir);

            oneBotMessageHandler = new QQMessageHandler();
            oneBotMessageHandler.setServer(oneBotServer);
            oneBotMessageHandler.setAgentExecutor(agent);
            oneBotMessageHandler.setDataDir(dataDir);
            oneBotMessageHandler.setSelfId(config.getOnebotSelfId());

            // execq 插件白名单注入 AgentExecutor
            agent.setCmdWhitelist(config.getExecqCmdWhitelist());

            oneBotServer.setMessageHandler(oneBotMessageHandler);
            oneBotServer.setPort(config.getOnebotPort());
            oneBotServer.setAccessToken(config.getOnebotToken());

            // === 配置主动查看功能 ===
            if (config.isProactiveCheckEnabled()) {
                oneBotMessageHandler.enableProactiveCheck();
                for (Long groupId : config.getMonitoredGroups()) {
                    oneBotMessageHandler.addMonitoredGroup(groupId);
                }
                debugLog("[OneBot] 主动查看已启用，监听群: " + config.getMonitoredGroups());
            }

            debugLog("[OneBot] 已初始化. 启用=" + config.isOnebotEnabled()
                    + " 端口=" + config.getOnebotPort());

            // 如果配置中已启用，自动启动
            if (config.isOnebotEnabled()) {
                if (oneBotServer.start()) {
                    debugLog("[OneBot] 自动启动，端口: " + config.getOnebotPort());
                } else {
                    debugLog("[OneBot] 自动启动失败");
                }
            }
        } catch (Exception e) {
            debugLog("[OneBot] 初始化错误: " + e.toString());
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

    /** Debug log to file - cross-platform */
    public static void debugLog(String msg) {
        synchronized (debugLock) {
            try {
                // 输出到SFW控制台
                SairCons.println("[AiAgent] " + msg);
                
                // 输出到日志文件
                if (debugWriter == null) {
                    debugWriter = new PrintWriter(new FileWriter(
                            System.getProperty("user.home") + File.separator + "aiagent_debug.log", true), true);
                }
                debugWriter.println(new SimpleDateFormat("HH:mm:ss.SSS").format(new Date()) + " " + msg);
            } catch (Exception ignored) {}
        }
    }

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
