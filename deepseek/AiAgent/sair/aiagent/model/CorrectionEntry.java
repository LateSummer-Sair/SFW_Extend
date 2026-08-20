package sair.aiagent.model;

import java.io.Serializable;

/**
 * 纠正记录模型 —— 记录 AI 犯错后被纠正的信息，供后续回答参考，避免重复犯错。
 * <p>
 * 支持「多观点查询」：同一主题（topic）下可以存在多条不同观点（viewpoint）的记录，
 * 查询时按主题返回全部观点，而不是只返回最新一条，让 AI 在回答前能综合多种视角。
 * </p>
 */
public class CorrectionEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;            // 自增主键
    private String topic;       // 主题/关键词（用于检索归类）
    private String content;     // 纠正内容
    private String viewpoint;   // 观点标签（如"观点A"/"观点B"，用于区分同一主题下的不同观点）
    private String source;      // 来源：user / ai / system
    private long qq;            // 记录发起者 QQ（0 表示本地/系统）
    private long createdAt;     // 创建时间戳
    private long updatedAt;     // 更新时间戳

    public CorrectionEntry() {}

    public CorrectionEntry(long id, String topic, String content, String viewpoint,
            String source, long qq, long createdAt, long updatedAt) {
        this.id = id;
        this.topic = topic == null ? "" : topic;
        this.content = content == null ? "" : content;
        this.viewpoint = viewpoint == null ? "" : viewpoint;
        this.source = source == null ? "user" : source;
        this.qq = qq;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ==================== Getters ====================
    public long getId()          { return id; }
    public String getTopic()     { return topic; }
    public String getContent()   { return content; }
    public String getViewpoint() { return viewpoint; }
    public String getSource()    { return source; }
    public long getQq()          { return qq; }
    public long getCreatedAt()   { return createdAt; }
    public long getUpdatedAt()   { return updatedAt; }

    // ==================== Setters ====================
    public void setId(long v)          { this.id = v; }
    public void setTopic(String v)     { this.topic = v == null ? "" : v; }
    public void setContent(String v)   { this.content = v == null ? "" : v; }
    public void setViewpoint(String v) { this.viewpoint = v == null ? "" : v; }
    public void setSource(String v)    { this.source = v == null ? "user" : v; }
    public void setQq(long v)          { this.qq = v; }
    public void setCreatedAt(long v)   { this.createdAt = v; }
    public void setUpdatedAt(long v)   { this.updatedAt = v; }

    /** 是否有有效内容 */
    public boolean hasContent() {
        return !content.isEmpty();
    }

    /** 构建注入上下文的单条纠正记录文本 */
    public String toPromptContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("[纠正] ");
        if (!topic.isEmpty()) sb.append("主题:").append(topic).append(" ");
        if (!viewpoint.isEmpty()) sb.append("(").append(viewpoint).append(") ");
        sb.append("来源:").append(source);
        sb.append("\n").append(content);
        return sb.toString();
    }

    @Override
    public String toString() {
        return "[Correction id=" + id + " topic=" + topic + " viewpoint=" + viewpoint + "]";
    }
}
