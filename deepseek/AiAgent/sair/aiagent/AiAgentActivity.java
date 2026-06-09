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

    private volatile PersistenceManager persistenceManager;
    private volatile Thread activeThread;
    private volatile boolean initialized = false;

    // ==================== 构造 ====================

    public AiAgentActivity() {
        debugLog("=== AiAgentActivity CONSTRUCTOR ===");
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
        debugLog("Constructor done.");
    }

    // ==================== Activity 生命周期 ====================

    @Override
    public Object main(String funcName, String args) {
        debugLog("main() called: funcName=" + funcName + " args=" + args);
        if (!initialized) {
            debugLog("First call - initializing...");
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

            debugLog("History loaded: " + history.size() + " messages");

            // === 跨会话上下文 ===
            String prevCtx = memory.loadContext();
            if (prevCtx != null) {
                agent.setPreviousSessionSummary(prevCtx);
                debugLog("Context loaded from previous session");
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
            Pathes.printSplit,
        };
    }

    @Override
    public void exit() {
        stopActivePrinter();
        if (persistenceManager != null) {
            persistenceManager.close();
            persistenceManager = null;
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
