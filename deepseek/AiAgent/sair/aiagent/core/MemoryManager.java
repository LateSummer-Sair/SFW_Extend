package sair.aiagent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import sair.aiagent.model.MemoryEntry;

/**
 * Memory manager — 记忆系统门面。
 * <p>
 * 内部完全委托给 {@link PersistenceManager} 的 SQLite 存储。
 * 保留原有 API 以保证外部调用方（AgentExecutor / ActivityActions）零改动。
 * </p>
 */
public class MemoryManager {

    private static final String FILE_NAME = "memory.json";
    private static final int MAX_SEARCH_RESULTS = 5;
    private static final int MAX_CONTEXT_CHARS = 2000;

    private PersistenceManager pm;

    public void setPersistenceManager(PersistenceManager pm) {
        this.pm = pm;
    }

    // ==================== 加载（向后兼容：静默空实现，迁移由 PersistenceManager.init 完成） ====================

    public synchronized void load(String dataDir) {
        // 不再需要独立加载，由 PersistenceManager.init() 统一处理
    }

    // ==================== CRUD ====================

    public synchronized MemoryEntry add(String content) {
        if (pm == null) return null;
        return pm.addMemory(content, "general", 0);
    }

    public synchronized MemoryEntry add(String content, String category, int importance) {
        if (pm == null) return null;
        return pm.addMemory(content, category, importance);
    }

    public synchronized boolean remove(int id) {
        if (pm == null) return false;
        return pm.removeMemory(id);
    }

    public synchronized void clear() {
        if (pm != null) pm.clearMemories();
    }

    public synchronized int size() {
        if (pm == null) return 0;
        return pm.memoryCount();
    }

    // ==================== 查询 ====================

    public synchronized String listAll() {
        if (pm == null) return "(no memories)";
        List<MemoryEntry> memories = pm.listAllMemories();
        if (memories.isEmpty()) return "(no memories)";
        StringBuilder sb = new StringBuilder();
        sb.append("Total: ").append(memories.size()).append(" memories:\n");
        for (MemoryEntry m : memories) {
            sb.append(m.toString()).append("\n");
        }
        return sb.toString().trim();
    }

    public synchronized List<MemoryEntry> search(String query, int maxResults) {
        if (pm == null) return new ArrayList<>();
        return pm.searchMemories(query, maxResults > 0 ? maxResults : MAX_SEARCH_RESULTS);
    }

    public synchronized List<MemoryEntry> search(String query) {
        return search(query, MAX_SEARCH_RESULTS);
    }

    public synchronized String buildContext(String query, int maxChars) {
        if (pm == null) return null;
        return pm.buildMemoryContext(query, maxChars > 0 ? maxChars : MAX_CONTEXT_CHARS);
    }

    public synchronized String buildContext(String query) {
        return buildContext(query, MAX_CONTEXT_CHARS);
    }

    // ==================== 上下文持久化（改为 app_state） ====================

    public synchronized void saveContext(String summary) {
        if (pm == null || summary == null || summary.trim().isEmpty()) return;
        pm.setState("context_summary", summary.trim());
    }

    public synchronized String loadContext() {
        if (pm == null) return null;
        return pm.getState("context_summary");
    }

    /** 非同步的 save 接口（用于高频调用，无操作——增量写入已由 SQLite 自动完成） */
    public synchronized void save() {
        // SQLite 增量写入，无需全量 save
    }
}
