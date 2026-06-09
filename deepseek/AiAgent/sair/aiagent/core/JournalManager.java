package sair.aiagent.core;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Session Journal — 追加式操作日志门面。
 * <p>
 * 内部完全委托给 {@link PersistenceManager} 的 SQLite journal 表。
 * 保留原有 API 以保证外部调用方零改动。
 * </p>
 */
public class JournalManager {

    private static final String FILE_NAME = "journal.json";
    private static final int MAX_ENTRIES = 80;
    private static final int DEFAULT_CONTEXT_ENTRIES = 25;

    private PersistenceManager pm;

    public void setPersistenceManager(PersistenceManager pm) {
        this.pm = pm;
    }

    // ==================== 加载（向后兼容） ====================

    public synchronized void load(String dataDir) {
        // 不再需要独立加载
    }

    // ==================== 写入 ====================

    public synchronized void addEntry(String role, String mode, String content, String result) {
        if (pm != null) pm.addJournalEntry(role, mode, content, result);
    }

    public synchronized void addAgentAction(String actionType, String content, String result) {
        addEntry("agent", "action", actionType + ": " + trunc(content, 200), result);
    }

    // ==================== 读取 ====================

    public synchronized String buildRecentContext(int maxEntries) {
        if (pm == null) return null;
        return pm.buildJournalContext(maxEntries);
    }

    public synchronized String buildRecentContext() {
        return buildRecentContext(DEFAULT_CONTEXT_ENTRIES);
    }

    public synchronized void save() {
        // SQLite 增量写入
    }

    // ==================== 工具 ====================

    private static String trunc(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "\u2026";
    }
}
