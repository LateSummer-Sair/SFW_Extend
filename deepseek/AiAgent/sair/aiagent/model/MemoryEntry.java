package sair.aiagent.model;

import java.io.Serializable;

/**
 * 记忆条目模型 —— 一条持久化记忆。
 * <p>
 * AI可以将重要信息存入记忆系统，重启SFW后仍然保留。
 * 每次对话时自动检索相关记忆注入上下文。
 * </p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>id</b>       —— 自增编号，用于删除</li>
 *   <li><b>category</b> —— 分类：general/user_pref/project/lesson</li>
 *   <li><b>importance</b> —— 重要性 0-10</li>
 *   <li><b>timestamp</b> —— 记忆创建时间（毫秒）</li>
 *   <li><b>content</b>  —— 记忆内容</li>
 * </ul>
 */
public class MemoryEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 缓存的日期格式化器 */
    private static final java.text.SimpleDateFormat DATE_FORMAT =
            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");

    private int id;
    private long timestamp;
    private String content;

    /** 记忆分类：general / user_pref / project / lesson */
    private String category = "general";
    /** 重要性 0-10 */
    private int importance;

    /** 无参构造（供Gson反序列化） */
    public MemoryEntry() {}

    /**
     * 构造记忆条目。
     *
     * @param id       自增编号
     * @param content  记忆内容
     */
    public MemoryEntry(int id, String content) {
        this.id = id;
        this.timestamp = System.currentTimeMillis();
        this.content = (content != null) ? content.trim() : "";
    }

    /** 完整构造器（含 category, importance） */
    public MemoryEntry(int id, String content, String category, int importance, long timestamp) {
        this.id = id;
        this.content = (content != null) ? content.trim() : "";
        this.category = (category != null) ? category : "general";
        this.importance = importance;
        this.timestamp = timestamp;
    }

    public int getId()           { return id; }
    public long getTimestamp()   { return timestamp; }
    public String getContent()   { return content; }
    public String getCategory()  { return category; }
    public int getImportance()   { return importance; }

    public void setId(int id)           { this.id = id; }
    public void setTimestamp(long ts)   { this.timestamp = ts; }
    public void setContent(String c)    { this.content = c; }
    public void setCategory(String c)   { this.category = c; }
    public void setImportance(int i)    { this.importance = i; }

    @Override
    public String toString() {
        String formatted;
        synchronized (DATE_FORMAT) {
            formatted = DATE_FORMAT.format(new java.util.Date(timestamp));
        }
        String catTag = (category != null && !"general".equals(category)) ? "[" + category + "] " : "";
        return "[" + id + "] " + catTag + content + " (" + formatted + ")";
    }
}