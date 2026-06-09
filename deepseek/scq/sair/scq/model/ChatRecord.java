package sair.scq.model;

/**
 * 聊天记录模型 —— 存储单条聊天消息的元数据。
 * 
 * <h3>存储策略</h3>
 * 聊天记录仅在客户端加密存储，服务端不做存储。
 * 私聊时 toUID=对方UID，群聊时 toUID=群组GID。
 */
public class ChatRecord {

    /** 消息时间戳 */
    private long timestamp;
    /** 发送者UID */
    private long fromUID;
    /** 接收者UID（私聊=对方UID，群聊=群组GID） */
    private long toUID;
    /** 消息内容 */
    private String content;
    /** 是否群聊消息 */
    private boolean isGroup;
    /** 是否命令消息 */
    private boolean isCommand;

    public ChatRecord() {
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== Getter / Setter ====================

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public long getFromUID() { return fromUID; }
    public void setFromUID(long fromUID) { this.fromUID = fromUID; }

    public long getToUID() { return toUID; }
    public void setToUID(long toUID) { this.toUID = toUID; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public boolean isGroup() { return isGroup; }
    public void setGroup(boolean group) { isGroup = group; }

    public boolean isCommand() { return isCommand; }
    public void setCommand(boolean command) { isCommand = command; }

    // ==================== JSON 序列化 ====================

    /**
     * 序列化为JSON字符串。
     * 格式: {"ts":123456789,"f":1001,"t":1002,"c":"hello","g":false,"cmd":false}
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"ts\":").append(timestamp);
        sb.append(",\"f\":").append(fromUID);
        sb.append(",\"t\":").append(toUID);
        sb.append(",\"c\":\"").append(escape(content)).append("\"");
        sb.append(",\"g\":").append(isGroup);
        sb.append(",\"cmd\":").append(isCommand);
        sb.append("}");
        return sb.toString();
    }

    /**
     * 从JSON字符串反序列化。
     */
    public static ChatRecord fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            ChatRecord r = new ChatRecord();
            r.timestamp = ecLong(json, "\"ts\":");
            r.fromUID = ecLong(json, "\"f\":");
            r.toUID = ecLong(json, "\"t\":");
            r.content = ecStr(json, "\"c\":\"");
            r.isGroup = "true".equals(ecVal(json, "\"g\":", ","));
            r.isCommand = "true".equals(ecVal(json, "\"cmd\":", "}"));
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static long ecLong(String json, String key) {
        String v = ecVal(json, key, ",");
        if (v.isEmpty()) return 0;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return 0; }
    }

    private static String ecStr(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    default: sb.append(c); break;
                }
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String ecVal(String json, String key, String endDelim) {
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = json.indexOf(endDelim, start);
        if (end < 0) end = json.length();
        return json.substring(start, end).trim();
    }

    @Override
    public String toString() {
        return "ChatRecord[from=" + fromUID + " to=" + toUID + " content=" + content + "]";
    }
}
