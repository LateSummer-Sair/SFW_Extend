package sair.aiagent.model;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 技能条目模型 —— 从 Agent 执行经验中蒸馏出的可复用技能。
 * <p>
 * Skills 自进化机制的核心数据单元。每一条技能代表从交互轨迹中
 * 提炼出的一条可执行策略/规则/偏好，存于 SQLite skills 表。
 * </p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>id</b>             —— 自增编号</li>
 *   <li><b>name</b>           —— 技能名称（简短标识）</li>
 *   <li><b>category</b>       —— 分类：preference/strategy/lesson/rule</li>
 *   <li><b>description</b>    —— 一句话描述</li>
 *   <li><b>content</b>        —— 技能规则文本（注入 prompt 的核心内容）</li>
 *   <li><b>version</b>        —— 版本号（合并/进化时递增）</li>
 *   <li><b>successCount</b>   —— 成功使用次数</li>
 *   <li><b>failureCount</b>   —— 失败次数</li>
 *   <li><b>lastUsed</b>       —— 上次使用时间戳</li>
 *   <li><b>parentSkillId</b>  —— 演化谱系（从哪个技能进化而来，0 表示原始）</li>
 *   <li><b>status</b>         —— active / deprecated / merged</li>
 *   <li><b>source</b>         —— extracted / manual / evolved</li>
 *   <li><b>createdAt</b>      —— 创建时间戳</li>
 *   <li><b>updatedAt</b>      —— 最后更新时间戳</li>
 * </ul>
 */
public class SkillEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String name;
    private String category;
    private String description;
    private String content;
    private int version;
    private String contentHash;  // SHA-256 of content for change detection
    private int successCount;
    private int failureCount;
    private long lastUsed;
    private int parentSkillId;
    private String status;
    private String scope;   // general (always inject) or task (top-K semantic)
    /**
     * 适用通道（逗号分隔，如 {@code "execq,execs"} / {@code "console"}）。
     * <p>
     * <b>空值 = 未声明 = 全通道可见</b>。这条规则是刻意的：
     * 「学到的经验」是 AI 自己的知识，不该有通道边界，一律留空；
     * 只有「内置工具说明书」才需要声明通道 —— 而声明它的唯一理由是
     * <b>该文档描述的工具在这个通道里到底存不存在</b>（索引头写的是「可用工具索引」，
     * 列出不存在的工具就是在骗模型）。通道归属从此与 {@link #scope} 解耦。
     * </p>
     */
    private String channels;

    /**
     * 近似重复被抑制的次数：新学到的经验被判定为「这条的另一种改写」时 +1。
     * <p>重复次数本身就是「这条经验很重要」的强信号（反复被独立学到），
     * 比从未被回写的成功率更能反映价值。</p>
     */
    private int dupHitCount;
    private double weight = 0.5;  // 0.0-1.0, adaptive routing weight
    private String source;
    private long createdAt;
    private long updatedAt;

    /** 无参构造（Gson 反序列化） */
    public SkillEntry() {}

    /** 新建技能构造 */
    public SkillEntry(String name, String category, String description, String content, String source) {
        this(name, category, description, content, source, "task");
    }

    /** 合法 scope 集合：general / persona / execq / task。 */
    private static String normalizeScope(String scope) {
        if (scope == null) return "task";
        String s = scope.trim();
        if ("general".equals(s) || "persona".equals(s) || "execq".equals(s) || "task".equals(s)) return s;
        return "task";
    }

    /** 新建技能构造（带 scope） */
    public SkillEntry(String name, String category, String description, String content, String source, String scope) {
        this.name = (name != null) ? name.trim() : "";
        this.category = (category != null) ? category : "general";
        this.description = (description != null) ? description.trim() : "";
        this.content = (content != null) ? content.trim() : "";
        this.version = 1;
        this.contentHash = computeContentHash(this.content);
        this.successCount = 0;
        this.failureCount = 0;
        this.lastUsed = 0;
        this.parentSkillId = 0;
        this.status = "active";
        // 注意：旧实现只认 general/persona，其余（含 execq）一律被强制改成 task，
        // 于是「QQ 通道专属技能」永远落成 task，而 execq 通道的工具索引只显示 scope=execq
        // —— 从 execq 通道学到的 NapCat 技能永远不会再出现在 execq 索引里（自学成果自锁死）。
        this.scope = normalizeScope(scope);
        this.source = (source != null) ? source : "extracted";
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** DB 加载构造 */
    public SkillEntry(int id, String name, String category, String description,
                      String content, int version, int successCount, int failureCount,
                      long lastUsed, int parentSkillId, String status, String source,
                      String scope, long createdAt, long updatedAt) {
        this(id, name, category, description, content, version, successCount, failureCount,
             lastUsed, parentSkillId, status, source, scope, null, createdAt, updatedAt);
    }

    /** DB 加载构造（带 contentHash） */
    public SkillEntry(int id, String name, String category, String description,
                      String content, int version, int successCount, int failureCount,
                      long lastUsed, int parentSkillId, String status, String source,
                      String scope, String contentHash, long createdAt, long updatedAt) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.description = description;
        this.content = content;
        this.version = version;
        this.contentHash = contentHash;
        this.successCount = successCount;
        this.failureCount = failureCount;
        this.lastUsed = lastUsed;
        this.parentSkillId = parentSkillId;
        this.status = status;
        this.source = source;
        // DB 加载路径同样归一化：库里已存在的 execq 技能必须原样保留（旧代码此处会用原值，
        // 但写入路径已把它改成 task，这里统一走 normalizeScope 保证读写一致）。
        this.scope = normalizeScope(scope);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    // ==================== Getters ====================

    public int getId()            { return id; }
    public String getName()       { return name; }
    public String getCategory()   { return category; }
    public String getDescription(){ return description; }
    public String getContent()    { return content; }
    public int getVersion()       { return version; }
    public String getContentHash()  { return contentHash; }
    public int getSuccessCount()  { return successCount; }
    public int getFailureCount()  { return failureCount; }
    public long getLastUsed()     { return lastUsed; }
    public int getParentSkillId() { return parentSkillId; }
    public String getStatus()     { return status; }
    public String getScope()      { return scope; }
    public String getSource()     { return source; }
    public String getChannels()   { return channels; }
    /** 适用通道（逗号分隔）；空 = 全通道可见。 */
    public void setChannels(String channels) { this.channels = (channels == null) ? "" : channels; }
    public int getDupHitCount()   { return dupHitCount; }
    public void setDupHitCount(int c) { this.dupHitCount = c; }

    /** 浅拷贝（用于缓存增量替换：只改少数字段，不必重读整表）。 */
    public SkillEntry copy() {
        SkillEntry c = new SkillEntry(id, name, category, description, content, version,
                successCount, failureCount, lastUsed, parentSkillId, status, source, scope,
                contentHash, createdAt, updatedAt);
        c.channels = this.channels;
        c.dupHitCount = this.dupHitCount;
        c.weight = this.weight;
        return c;
    }

    /** 浅拷贝并替换内容相关字段（名称/描述/正文/版本/时间/哈希）。 */
    public SkillEntry withContent(String name, String description, String content,
                                  int version, long updatedAt, String contentHash) {
        SkillEntry c = copy();
        c.name = (name != null) ? name : c.name;
        c.description = (description != null) ? description : "";
        c.content = (content != null) ? content : "";
        c.version = version;
        c.updatedAt = updatedAt;
        c.contentHash = contentHash;
        return c;
    }
    /** 该技能是否适用于指定通道（空 channels = 全通道）。 */
    public boolean appliesToChannel(String channel) {
        if (channel == null || channel.isEmpty()) return true;
        if (channels == null || channels.isEmpty()) return true;
        return ("," + channels.replace(" ", "") + ",").contains("," + channel + ",");
    }
    public long getCreatedAt()    { return createdAt; }
    public long getUpdatedAt()    { return updatedAt; }

    // ==================== Setters ====================

    public void setId(int id)                  { this.id = id; }
    public void setName(String name)           { this.name = name; }
    public void setCategory(String category)   { this.category = category; }
    public void setDescription(String desc)    { this.description = desc; }
    public void setContent(String content)     { this.content = content; }
    public void setVersion(int version)        { this.version = version; }
    public void setContentHash(String h)          { this.contentHash = h; }
    public void setSuccessCount(int c)         { this.successCount = c; }
    public void setFailureCount(int c)         { this.failureCount = c; }
    public void setLastUsed(long ts)           { this.lastUsed = ts; }
    public void setParentSkillId(int pid)      { this.parentSkillId = pid; }
    public void setStatus(String status)       { this.status = status; }
    public void setScope(String scope)       { this.scope = normalizeScope(scope); }
    public void setSource(String source)       { this.source = source; }
    public void setCreatedAt(long ts)          { this.createdAt = ts; }
    public void setUpdatedAt(long ts)          { this.updatedAt = ts; }

    /** 成功次数 +1，更新 lastUsed */
    public void recordSuccess() {
        this.successCount++;
        this.lastUsed = System.currentTimeMillis();
    }

    /** 失败次数 +1，更新 lastUsed */
    public void recordFailure() {
        this.failureCount++;
        this.lastUsed = System.currentTimeMillis();
    }

    /** 成功率（百分比，0-100），无使用记录时返回 50（中性） */
    public int successRate() {
        int total = successCount + failureCount;
        if (total == 0) return 50;
        return (int) ((long) successCount * 100 / total);
    }

    /** 是否为活跃技能 */
    public boolean isActive() {
        return "active".equals(status);
    }

    /** 是否值得注入上下文：活跃且成功率 >= 50% */
    public boolean isWorthInjecting() {
        return isActive() && successRate() >= 50;
    }

    /** 计算 content 的 SHA-256 哈希（用于内置技能变化检测，避免重启时无意义 LLM 合并） */
    public static String computeContentHash(String content) {
        if (content == null || content.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return Integer.toHexString(content.hashCode()); }
    }

    @Override
    public String toString() {
        return "[Skill#" + id + " v" + version + "] " + name
                + " [" + category + "/" + scope + "] " + successRate() + "%"
                + " (" + status + ")";
    }
}