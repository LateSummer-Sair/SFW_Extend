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
    String executeNote(String content) {
        if (content == null || content.trim().isEmpty()) 
            return "[note] usage: add title|content|tags | search query | list | get id | delete id | update id title|content|tags";
        String c = content.trim();
        if (c.startsWith("add ")) {
            String rest = c.substring(4).trim();
            String[] parts = rest.split("\\|", 3);
            if (parts.length < 2) return "[note] add needs title|content format";
            String title = parts[0].trim();
            String body = parts[1].trim();
            String tags = parts.length > 2 ? parts[2].trim() : "";
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return "[note] PersistenceManager not available";
            int id = pm.addNote(title, body, tags);
            return id > 0 ? "[note] saved #" + id + ": " + title : "[note] save failed";
        } else if (c.startsWith("search ")) {
            String query = c.substring(7).trim();
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return "[note] PersistenceManager not available";
            java.util.List<String[]> results = pm.searchNotes(query, 10);
            if (results.isEmpty()) return "[note] no results for: " + query;
            StringBuilder sb = new StringBuilder("[note] search results (" + results.size() + "):");
            for (String[] r : results) {
                sb.append("\n  #").append(r[0]).append(" ").append(r[1]);
                if (r[2] != null && !r[2].isEmpty()) sb.append(" - ").append(r[2]);
            }
            return sb.toString();
        } else if (c.startsWith("list")) {
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return "[note] PersistenceManager not available";
            java.util.List<String[]> notes = pm.listNotes(20);
            if (notes.isEmpty()) return "[note] no notes yet";
            StringBuilder sb = new StringBuilder("[note] recent notes (" + notes.size() + "):");
            for (String[] n : notes) {
                sb.append("\n  #").append(n[0]).append(" ").append(n[1]);
                if (n[3] != null && !n[3].isEmpty()) sb.append(" [").append(n[3]).append("]");
            }
            return sb.toString();
        } else if (c.startsWith("get ")) {
            try {
                int id = Integer.parseInt(c.substring(4).trim());
                PersistenceManager pm = PersistenceManager.getInstance();
                if (pm == null) return "[note] PersistenceManager not available";
                String[] note = pm.getNote(id);
                if (note == null) return "[note] #" + id + " not found";
                return "[note] #" + id + " " + note[0] + "\n" + note[1] 
                    + (note[2] != null && !note[2].isEmpty() ? "\nTags: " + note[2] : "");
            } catch (NumberFormatException e) { return "[note] get needs numeric id"; }
        } else if (c.startsWith("delete ")) {
            try {
                int id = Integer.parseInt(c.substring(7).trim());
                PersistenceManager pm = PersistenceManager.getInstance();
                if (pm == null) return "[note] PersistenceManager not available";
                return pm.removeNote(id) ? "[note] #" + id + " deleted" : "[note] #" + id + " not found";
            } catch (NumberFormatException e) { return "[note] delete needs numeric id"; }
        } else if (c.startsWith("update ")) {
            String rest = c.substring(7).trim();
            String[] parts2 = rest.split(" ", 2);
            try {
                int id = Integer.parseInt(parts2[0]);
                String updateArgs = parts2.length > 1 ? parts2[1].trim() : "";
                String[] fields = updateArgs.split("\\|", 3);
                if (fields.length < 2) return "[note] update needs: id title|content|tags";
                PersistenceManager pm = PersistenceManager.getInstance();
                if (pm == null) return "[note] PersistenceManager not available";
                String title = fields[0].trim();
                String body = fields[1].trim();
                String tags = fields.length > 2 ? fields[2].trim() : "";
                return pm.updateNote(id, title, body, tags) 
                    ? "[note] #" + id + " updated" : "[note] #" + id + " not found";
            } catch (NumberFormatException e) { return "[note] update needs: numeric_id title|content|tags"; }
        }
        return "[note] unknown command. try: add/search/list/get/delete/update";
    }

    /** search note alias */
    String executeSearchNote(String content) {
        return executeNote("search " + (content != null ? content.trim() : ""));
    }

    // ==================== cron 定时任务 ====================

    /** cron schedule management */
    String executeSchedule(String content) {
        if (cronScheduler == null) return "[schedule] CronScheduler not initialized";
        if (content == null || content.trim().isEmpty()) 
            return "[schedule] usage: add \"cron\" \"command\" | list | remove id | enable id | disable id";
        String c = content.trim();
        String[] parts = c.split("\\s+", 2);
        String subCmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";
        switch (subCmd) {
            case "add": {
                String cronExpr = extractQuoted(args, 0);
                String command = extractQuoted(args, 1);
                String desc = extractQuoted(args, 2);
                if (cronExpr.isEmpty() || command.isEmpty()) 
                    return "[schedule] add needs cron_expr and command in quotes";
                return cronScheduler.addTask(cronExpr, command, desc);
            }
            case "list": return cronScheduler.listTasks();
            case "remove":
                try { return cronScheduler.removeTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] remove needs task ID"; }
            case "enable":
                try { return cronScheduler.enableTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] enable needs task ID"; }
            case "disable":
                try { return cronScheduler.disableTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] disable needs task ID"; }
            default: return "[schedule] unknown: " + subCmd + ". try: add/list/remove/enable/disable";
        }
    }

    private static String extractQuoted(String args, int index) {
        return TagHandlers.extractQuoted(args, index);
    }

    // ==================== 批量文件操作 ====================

    String executeBatchRename(String content) {
        return TagHandlers.executeBatchRename(content);
    }

    String executeBatchConvert(String content) {
        return TagHandlers.executeBatchConvert(content);
    }
}
