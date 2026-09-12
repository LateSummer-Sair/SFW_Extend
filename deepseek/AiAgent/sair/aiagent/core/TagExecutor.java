package sair.aiagent.core;

import sair.user.Activity;

/**
 * 标签执行器（精简版）—— 仅保留被 {@link AgentActionHandler} 复用的工具实现。
 *
 * <p>原 XML 标签解析（parseActions / parseExecqActions / executeAction）及
 * 与 AgentActionHandler 重复的工具实现（cmd/sys/web/eval 等）已全部移除。
 * 工具调用统一由 Function Calling 的 {@link ToolDispatcher} 经
 * {@link AgentActionHandler#executeAction} 分发。</p>
 */
public class TagExecutor {

    private final ConfirmationGate gate;
    private final DynamicCodeEngine codeEngine;
    private final Activity selfActivity;
    private volatile MemoryManager memoryManager;
    private volatile EmotionManager emotionManager;
    private volatile StickerManager stickerManager;
    private volatile JournalManager journal;
    private volatile java.util.function.Consumer<String> qqExecsCallback;
    private volatile CronScheduler cronScheduler;
    private volatile DeepSeekClient deepSeekClient;

    public TagExecutor(ConfirmationGate gate, DynamicCodeEngine codeEngine, Activity selfActivity) {
        this.gate = gate;
        this.codeEngine = codeEngine;
        this.selfActivity = selfActivity;
    }

    public void setMemoryManager(MemoryManager memoryManager) { this.memoryManager = memoryManager; }
    public void setEmotionManager(EmotionManager emotionManager) { this.emotionManager = emotionManager; }
    public void setStickerManager(StickerManager stickerManager) { this.stickerManager = stickerManager; }
    public void setJournal(JournalManager journal) { this.journal = journal; }
    public void setQqExecsCallback(java.util.function.Consumer<String> callback) { this.qqExecsCallback = callback; }
    public void setCronScheduler(CronScheduler cronScheduler) { this.cronScheduler = cronScheduler; }
    public void setDeepSeekClient(DeepSeekClient client) { this.deepSeekClient = client; }

    // ==================== note 知识库 ====================

    /** note knowledge base */
    /** search note alias */
    // ==================== cron 定时任务 ====================

    /** cron schedule management */
    private static String extractQuoted(String args, int index) {
        return TagHandlers.extractQuoted(args, index);
    }

    // ==================== 批量文件操作 ====================

}
