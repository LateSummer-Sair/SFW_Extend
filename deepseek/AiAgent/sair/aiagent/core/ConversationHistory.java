package sair.aiagent.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import sair.aiagent.model.ChatMessage;

/**
 * 对话历史管理器 —— 带容量控制的滑动窗口。
 * <p>
 * 内部持久化完全委托给 {@link PersistenceManager} 的 SQLite conversations 表。
 * 滑窗 token 管理逻辑保留在内存层。
 * </p>
 */
public class ConversationHistory {

    /** 最大 token 容量（约1M） */
    private static final int MAX_TOKENS = 900_000;

    /** 最少保留消息数（最近2轮对话） */
    private static final int MIN_KEEP_MESSAGES = 4;

    /** 消息列表 */
    private final List<ChatMessage> messages = new ArrayList<>();

    /** 持久化管理器 */
    private PersistenceManager pm;

    /** 持久化缓存文件（null=不持久化） */
    private File cacheFile; // 仅为向后兼容保留

    // ==================== 公共API ====================

    /**
     * 添加一条消息到历史末尾。
     * <p>添加后自动检查容量并裁剪。</p>
     *
     * @param message 要添加的消息
     */
    public synchronized void add(ChatMessage message) {
        if (message == null) return;
        messages.add(message);
        trimIfNeeded();
        if (pm != null) pm.addConversationMessage(message);
    }

    /**
     * 获取当前所有消息的不可变副本。
     *
     * @return 消息列表拷贝
     */
    public synchronized List<ChatMessage> getAll() {
        return new ArrayList<>(messages);
    }

    /**
     * 获取消息总数。
     *
     * @return 消息数量
     */
    public synchronized int size() {
        return messages.size();
    }

    /**
     * 清空所有对话历史。
     */
    public synchronized void clear() {
        messages.clear();
        if (pm != null) pm.clearConversations();
    }

    /**
     * 估算当前历史的总 token 数。
     *
     * @return 估算 token 数
     */
    public synchronized int estimateTotalTokens() {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += msg.estimateTokens();
        }
        return total;
    }

    /**
     * 添加系统消息到列表头部。
     * <p>系统消息不计入容量裁剪，但会被保留。</p>
     *
     * @param systemPrompt 系统提示词
     * @return 包含系统消息和历史的完整消息列表
     */
    public synchronized List<ChatMessage> buildFullContext(String systemPrompt) {
        List<ChatMessage> full = new ArrayList<>();
        // 系统消息放在最前面
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            full.add(new ChatMessage("system", systemPrompt));
        }
        full.addAll(messages);
        return full;
    }

    // ==================== 持久化 ====================

    /**
     * 设置持久化管理器。
     * <p>设置后，每次 add/clear 自动保存到 SQLite conversations 表，
     * SFW 重启后可通过 {@link #loadFromFile()} 恢复上下文，实现跨会话记忆。</p>
     *
     * @param pm 持久化管理器
     */
    public void setPersistenceManager(PersistenceManager pm) {
        this.pm = pm;
    }

    /**
     * 设置持久化缓存文件。
     * <p>设置后，每次 add/clear 自动保存到 history.json，
     * SFW 重启后可通过 {@link #loadFromFile()} 恢复上下文，实现跨会话记忆。</p>
     *
     * @param cacheFile 缓存文件
     */
    public synchronized void setCacheFile(File cacheFile) {
        this.cacheFile = cacheFile;
    }

    /**
     * 从 SQLite 加载已有对话
     */
    public synchronized void loadFromFile() {
        if (pm == null) {
            messages.clear();
            return;
        }
        List<ChatMessage> loaded = pm.loadConversations();
        messages.clear();
        messages.addAll(loaded);
        trimIfNeeded();
    }

    // ==================== 容量控制 ====================

    /**
     * 检查是否需要裁剪，超出上限则移除旧消息。
     * <p>裁剪策略：从最旧的消息开始累计token，超出上限后移除，
     * 但保证至少保留 MIN_KEEP_MESSAGES 条最近消息。</p>
     */
    private void trimIfNeeded() {
        while (messages.size() > MIN_KEEP_MESSAGES) {
            int totalTokens = 0;
            int removeUpTo = -1;

            for (int i = 0; i < messages.size(); i++) {
                int msgTokens = messages.get(i).estimateTokens();
                if (totalTokens + msgTokens > MAX_TOKENS) {
                    removeUpTo = i + 1;
                    break;
                }
                totalTokens += msgTokens;
            }

            if (removeUpTo < 0) return;

            int safeRemoveEnd = Math.min(removeUpTo, messages.size() - MIN_KEEP_MESSAGES);
            if (safeRemoveEnd <= 0) return;

            for (int i = 0; i < safeRemoveEnd; i++) {
                messages.remove(0);
            }
        }
    }
}
