package sair.aiagent.model;

import java.io.Serializable;

/**
 * 表情包条目模型 —— 一条收集到的表情包图片。
 * <p>
 * QQ Bot 自动收集群聊中出现过的表情包图片，记录其语境，
 * 后续对话时根据语境匹配发送合适的表情。
 * </p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>id</b>       —— 自增编号</li>
 *   <li><b>imageUrl</b> —— QQ 消息中的原始图片 URL（CQ码 file/url 字段）</li>
 *   <li><b>filePath</b> —— 下载到本地的文件路径（dataDir/stickers/）</li>
 *   <li><b>context</b>  —— 收集时周围对话的语境文本（用于后续匹配）</li>
 *   <li><b>keywords</b> —— 从语境中提取的关键词（空格分隔）</li>
 *   <li><b>timestamp</b>—— 收集时间戳</li>
 *   <li><b>usageCount</b>—— 使用次数</li>
 * </ul>
 */
public class StickerEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private int id;
    private String imageUrl;
    private String filePath;
    private String context;
    private String keywords;
    private long timestamp;
    private int usageCount;
    /** 图片注释（随图持久化，AI 可修改，图片进入长期存储时一并保留） */
    private String remark = "";

    /** 无参构造（Gson 反序列化） */
    public StickerEntry() {}

    /** 完整构造 */
    public StickerEntry(int id, String imageUrl, String filePath,
                        String context, String keywords, long timestamp) {
        this.id = id;
        this.imageUrl = (imageUrl != null) ? imageUrl : "";
        this.filePath = (filePath != null) ? filePath : "";
        this.context = (context != null) ? context.trim() : "";
        this.keywords = (keywords != null) ? keywords.trim() : extractKeywords(this.context);
        this.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        this.usageCount = 0;
    }

    // ==================== Getters & Setters ====================

    public int getId()           { return id; }
    public String getImageUrl()  { return imageUrl; }
    public String getFilePath()  { return filePath; }
    public String getContext()   { return context; }
    public String getKeywords()  { return keywords; }
    public long getTimestamp()   { return timestamp; }
    public int getUsageCount()   { return usageCount; }
    public String getRemark()    { return remark; }

    public void setId(int id)              { this.id = id; }
    public void setImageUrl(String url)    { this.imageUrl = url; }
    public void setFilePath(String path)   { this.filePath = path; }
    public void setContext(String ctx)     { this.context = ctx; }
    public void setKeywords(String kw)     { this.keywords = kw; }
    public void setTimestamp(long ts)      { this.timestamp = ts; }
    public void setUsageCount(int c)       { this.usageCount = c; }
    public void setRemark(String r)        { this.remark = (r != null) ? r : ""; }

    /** 使用次数 +1 */
    public void incrementUsage() { this.usageCount++; }

    // ==================== 关键词提取 ====================

    /**
     * 从语境文本中提取关键词（简单分词 + 过滤停用词）。
     * @param text 语境文本
     * @return 空格分隔的关键词
     */
    public static String extractKeywords(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        // 去标点、分词（按中文/英文边界拆分）
        String cleaned = text.replaceAll("[\\p{Punct}\\p{Ps}\\p{Pe}，。！？、；：【】《》（）…—\\s]+", " ");
        StringBuilder kw = new StringBuilder();
        for (String word : cleaned.split("\\s+")) {
            String w = word.trim().toLowerCase();
            if (w.length() < 2) continue;
            if (isStopWord(w)) continue;
            if (kw.length() > 0) kw.append(' ');
            kw.append(w);
        }
        // 截断过长关键词
        String result = kw.toString();
        if (result.length() > 500) result = result.substring(0, 500);
        return result;
    }

    /** 简单停用词过滤 */
    private static boolean isStopWord(String word) {
        if (word.length() <= 1) return true;
        String[] stopWords = {
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一",
            "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
            "没有", "看", "好", "自己", "这", "他", "她", "它", "吗", "呢", "吧",
            "啊", "哦", "嗯", "哈", "呀", "the", "is", "a", "an", "to", "of",
            "in", "and", "that", "it", "for", "you", "with", "on", "this"
        };
        for (String sw : stopWords) {
            if (sw.equals(word)) return true;
        }
        return false;
    }

    @Override
    public String toString() {
        String kw = (keywords != null && keywords.length() > 60)
                ? keywords.substring(0, 60) + "..." : keywords;
        return "[Sticker#" + id + "] kw: " + kw + " | used: " + usageCount + "x";
    }
}
