package sair.scq.protocol;

/**
 * SCQ消息封装对象 —— 服务端与客户端通信的标准化消息格式。
 * 
 * <h3>序列化格式</h3>
 * 使用手动JSON拼接（无外部依赖），格式如下：
 * <pre>{@code {"t":"LOGIN","f":0,"d":"1001","o":"{\"k\":\"v\"}"}}</pre>
 * 其中：t=type, f=fromUID, d=data, o=extraData, i=toUID
 */
public class SCMessage {

    /** 消息类型 */
    private MessageType type;
    /** 发送方UID */
    private long fromUID;
    /** 接收方UID（私聊）或群组GID（群聊） */
    private long toUID;
    /** 主要数据载荷（JSON字符串） */
    private String data;
    /** 额外数据载荷 */
    private String extraData;
    /** 时间戳 */
    private long timestamp;

    public SCMessage() {
        this.timestamp = System.currentTimeMillis();
    }

    public SCMessage(MessageType type) {
        this();
        this.type = type;
    }

    // ==================== Getter / Setter ====================

    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public long getFromUID() { return fromUID; }
    public void setFromUID(long fromUID) { this.fromUID = fromUID; }

    public long getToUID() { return toUID; }
    public void setToUID(long toUID) { this.toUID = toUID; }

    public String getData() { return data; }
    public void setData(String data) { this.data = data; }

    public String getExtraData() { return extraData; }
    public void setExtraData(String extraData) { this.extraData = extraData; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    // ==================== JSON 序列化 ====================

    /**
     * 将消息序列化为JSON字符串。
     * 格式: {"t":"LOGIN","f":1001,"i":0,"d":"...","o":"...","ts":123456789}
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"t\":\"");
        sb.append(type != null ? type.name() : "ERROR");
        sb.append("\",\"f\":");
        sb.append(fromUID);
        sb.append(",\"i\":");
        sb.append(toUID);
        sb.append(",\"d\":");
        sb.append(jsonEscape(data));
        sb.append(",\"o\":");
        sb.append(jsonEscape(extraData));
        sb.append(",\"ts\":");
        sb.append(timestamp);
        sb.append("}");
        return sb.toString();
    }

    /**
     * 从JSON字符串反序列化消息。
     * @param json JSON字符串
     * @return SCMessage对象，解析失败返回null
     */
    public static SCMessage fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            SCMessage msg = new SCMessage();

            // 解析 type
            String t = extractJsonValue(json, "\"t\":\"", "\"");
            msg.type = MessageType.fromString(t);

            // 解析 fromUID
            String f = extractJsonValue(json, "\"f\":", ",");
            if (f != null && !f.isEmpty()) msg.fromUID = Long.parseLong(f);

            // 解析 toUID
            String i = extractJsonValue(json, "\"i\":", ",");
            if (i != null && !i.isEmpty()) msg.toUID = Long.parseLong(i);

            // 解析 data
            msg.data = extractJsonString(json, "\"d\":\"");

            // 解析 extraData
            msg.extraData = extractJsonString(json, "\"o\":\"");

            // 解析 timestamp
            String ts = extractJsonValue(json, "\"ts\":", "}");
            if (ts != null && !ts.isEmpty()) msg.timestamp = Long.parseLong(ts);

            return msg;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 辅助工具方法 ====================

    /**
     * 从JSON中提取简单值（非字符串类型）。
     */
    private static String extractJsonValue(String json, String key, String endDelim) {
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        int end = json.indexOf(endDelim, start);
        if (end < 0) return null;
        return json.substring(start, end).trim();
    }

    /**
     * 从JSON中提取字符串值（处理转义引号）。
     */
    private static String extractJsonString(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) return null;
        start += key.length();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    default: sb.append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 将字符串转义为JSON字符串字面值（null转为""）。
     */
    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder(s.length() + 4);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c); break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ==================== 工厂方法 ====================

    /** 创建成功响应消息 */
    public static SCMessage ok(String msg) {
        SCMessage m = new SCMessage(MessageType.OK);
        m.data = msg;
        return m;
    }

    /** 创建错误响应消息 */
    public static SCMessage error(String msg) {
        SCMessage m = new SCMessage(MessageType.ERROR);
        m.data = msg;
        return m;
    }

    @Override
    public String toString() {
        return "SCMsg[type=" + type + " from=" + fromUID + " to=" + toUID + " data=" + data + "]";
    }
}
