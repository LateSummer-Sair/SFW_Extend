package sair.aiagent.onebot;

import java.util.ArrayList;
import java.util.List;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.onebot.util.JsonUtil;

/**
 * 折叠/转发消息内容展开工具类。
 * 从 QQMessageHandler 中提取，负责从 OneBot v11 API 响应中提取文本内容。
 *
 * <p>除原有的「扁平化文本」提取外，另提供<b>结构化解析</b>（{@link #parseForwardStructure}）：
 * 保留图片真实 URL 与嵌套折叠的 forward_id，供上层递归展开（嵌套折叠需再调 get_forward_msg）。</p>
 */
public final class ForwardMessageExpander {

    private ForwardMessageExpander() {} // 纯静态工具类，禁止实例化

    // ==================== 结构化解析（保留图片 URL 与嵌套折叠） ====================

    /** 折叠消息中的一个内容项。 */
    public static final class ForwardItem {
        public static final String TYPE_TEXT = "text";
        public static final String TYPE_IMAGE = "image";
        public static final String TYPE_FACE = "face";
        public static final String TYPE_AT = "at";
        public static final String TYPE_NESTED = "nested";
        /** 未单独建模的消息段（视频/语音/文件/卡片/位置等），渲染为可读占位符，避免静默丢失。 */
        public static final String TYPE_PLACEHOLDER = "placeholder";

        public final String type;
        /** text 内容 / at 的 QQ 号 */
        public final String text;
        /** image 的图片 URL（可能为空，说明该图拿不到地址） */
        public final String imageUrl;
        /** nested: 嵌套折叠消息的 forward_id（可用于 get_forward_msg） */
        public final String nestedId;
        /** nested: 直接内嵌的 content JSON（部分实现会内嵌，优先用它，省一次 API 调用） */
        public final String nestedRaw;

        private ForwardItem(String type, String text, String imageUrl, String nestedId, String nestedRaw) {
            this.type = type; this.text = text; this.imageUrl = imageUrl;
            this.nestedId = nestedId; this.nestedRaw = nestedRaw;
        }
        static ForwardItem text(String t) { return new ForwardItem(TYPE_TEXT, t, null, null, null); }
        static ForwardItem image(String url) { return new ForwardItem(TYPE_IMAGE, null, url, null, null); }
        static ForwardItem face() { return new ForwardItem(TYPE_FACE, null, null, null, null); }
        static ForwardItem at(String qq) { return new ForwardItem(TYPE_AT, qq, null, null, null); }
        static ForwardItem nested(String id, String raw) { return new ForwardItem(TYPE_NESTED, null, null, id, raw); }
        /** 未建模段落：只带一段可读占位文本（如 "[视频]"）。 */
        static ForwardItem placeholder(String label) { return new ForwardItem(TYPE_PLACEHOLDER, label, null, null, null); }

        public boolean isText() { return TYPE_TEXT.equals(type); }
        public boolean isImage() { return TYPE_IMAGE.equals(type); }
        public boolean isNested() { return TYPE_NESTED.equals(type); }
    }

    /** 折叠消息中的一条消息（发送者 + 内容项列表）。 */
    public static final class ForwardMessage {
        public String senderName;
        public long senderQQ;
        public final List<ForwardItem> items = new ArrayList<>();

        /** 拼出该条消息的纯文本（图片渲染为 [图片:url]）。 */
        public String render() {
            StringBuilder sb = new StringBuilder();
            for (ForwardItem it : items) {
                switch (it.type) {
                    case ForwardItem.TYPE_TEXT:
                        if (it.text != null && !it.text.isEmpty()) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(it.text);
                        }
                        break;
                    case ForwardItem.TYPE_IMAGE:
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(it.imageUrl != null && !it.imageUrl.isEmpty()
                                ? "[图片:" + it.imageUrl + "]" : "[图片]");
                        break;
                    case ForwardItem.TYPE_FACE:
                        if (sb.length() > 0) sb.append(" ");
                        sb.append("[表情]");
                        break;
                    case ForwardItem.TYPE_AT:
                        if (sb.length() > 0) sb.append(" ");
                        sb.append("@").append(it.text != null ? it.text : "");
                        break;
                    case ForwardItem.TYPE_PLACEHOLDER:
                        // 折叠消息里未单独建模的段（视频/语音/文件/卡片/位置/骰子…）。
                        // 旧实现这些段在 parseOneMessage 的 default 分支被直接丢弃，
                        // 于是「折叠里发了个视频/文件」在 AI 看来完全不存在。
                        if (sb.length() > 0) sb.append(" ");
                        sb.append(it.text);
                        break;
                    default:
                        break; // nested 由递归展开处理，不在此渲染
                }
            }
            return sb.toString();
        }

        /** 该条消息中是否含嵌套折叠。 */
        public boolean hasNested() {
            for (ForwardItem it : items) if (it.isNested()) return true;
            return false;
        }
    }

    /** 嵌套折叠消息的取回回调（由持有 NapCatApi 的调用方实现）。 */
    public interface NestedForwardResolver {
        /** 按 forward_id 取回嵌套折叠消息的原始 JSON（get_forward_msg 响应或 content 数组）；取不到返回 null。 */
        String resolve(String forwardId);
    }

    /**
     * 结构化解析折叠消息内容。入参可为：
     * <ul>
     *   <li>get_forward_msg 响应：{@code {"data":{"messages":[...]}}}</li>
     *   <li>内嵌 content 数组：{@code [{"type":"node","data":{...}}]} 或 {@code [{sender,message}...]}</li>
     * </ul>
     */
    public static List<ForwardMessage> parseForwardStructure(String raw) {
        List<ForwardMessage> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        String src = raw.trim();
        String arr = JsonUtil.extractArray(src, "messages");
        if (arr == null) {
            if (src.startsWith("[")) {
                arr = src.substring(1, src.length() - 1);
            } else {
                arr = src; // 单个对象当一条消息处理
            }
        }
        for (String item : JsonUtil.splitJsonArray(arr)) {
            ForwardMessage fm = parseOneMessage(item);
            if (fm != null && (!fm.items.isEmpty() || fm.senderName != null)) out.add(fm);
        }
        return out;
    }

    /** 解析单条消息/节点（兼容 {sender,message} 与 {type:node,data:{name,content}} 两种形态）。 */
    private static ForwardMessage parseOneMessage(String item) {
        ForwardMessage fm = new ForwardMessage();
        try {
            String senderObj = JsonUtil.extractObject(item, "sender");
            if (senderObj != null) {
                fm.senderQQ = JsonUtil.extractLong(senderObj, "user_id");
                String card = JsonUtil.extractString(senderObj, "card");
                String nick = JsonUtil.extractString(senderObj, "nickname");
                fm.senderName = (card != null && !card.isEmpty()) ? card
                        : (nick != null && !nick.isEmpty() ? nick : null);
            }
            String segs = JsonUtil.extractArray(item, "message");
            if (segs == null) {
                String dataObj = JsonUtil.extractObject(item, "data");
                if (dataObj != null) {
                    segs = JsonUtil.extractArray(dataObj, "content");
                    if (fm.senderName == null) {
                        String name = JsonUtil.extractString(dataObj, "name");
                        if (name != null && !name.isEmpty()) fm.senderName = name;
                    }
                    if (fm.senderQQ <= 0) fm.senderQQ = JsonUtil.extractLong(dataObj, "user_id");
                }
            }
            if (segs == null) return fm;

            for (String seg : JsonUtil.splitJsonArray(segs)) {
                String type = JsonUtil.extractString(seg, "type");
                if (type == null) continue;
                String dataObj = JsonUtil.extractObject(seg, "data");
                switch (type) {
                    case "text": {
                        String t = (dataObj != null) ? JsonUtil.extractString(dataObj, "text") : null;
                        if (t != null && !t.isEmpty()) fm.items.add(ForwardItem.text(t));
                        break;
                    }
                    case "image": {
                        String url = (dataObj != null) ? JsonUtil.extractString(dataObj, "url") : null;
                        if ((url == null || url.isEmpty()) && dataObj != null) {
                            url = JsonUtil.extractString(dataObj, "file");
                        }
                        fm.items.add(ForwardItem.image(url));
                        break;
                    }
                    case "face":
                        fm.items.add(ForwardItem.face());
                        break;
                    case "at": {
                        String qq = (dataObj != null) ? JsonUtil.extractString(dataObj, "qq") : null;
                        fm.items.add(ForwardItem.at(qq));
                        break;
                    }
                    case "forward": {
                        String fid = (dataObj != null) ? JsonUtil.extractString(dataObj, "id") : null;
                        String inner = (dataObj != null) ? JsonUtil.extractArray(dataObj, "content") : null;
                        if (inner == null && dataObj != null) inner = JsonUtil.extractString(dataObj, "content");
                        fm.items.add(ForwardItem.nested(fid, inner));
                        break;
                    }
                    default:
                        // 不再静默丢弃：视频/语音/文件/卡片/位置等段落渲染为可读占位符，
                        // 让 AI 至少知道「这里还有一条非文本内容」。
                        fm.items.add(ForwardItem.placeholder(foldSegmentLabel(type)));
                        break;
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Fwd] 结构化解析单条消息失败: " + e.toString());
        }
        return fm;
    }

    /** 折叠消息内未建模段落 → 可读占位标签。 */
    private static String foldSegmentLabel(String type) {
        if (type == null) return "[其他内容]";
        switch (type.toLowerCase()) {
            case "video":    return "[视频]";
            case "record":   return "[语音]";
            case "file":     return "[文件]";
            case "json":
            case "xml":
            case "markdown": return "[卡片]";
            case "location": return "[位置]";
            case "music":    return "[音乐]";
            case "contact":  return "[名片]";
            case "share":    return "[分享]";
            case "dice":     return "[骰子]";
            case "rps":      return "[猜拳]";
            case "poke":     return "[戳一戳]";
            case "mface":
            case "sface":    return "[表情]";
            default:         return "[CQ:" + type + "]";
        }
    }

    /** 结构里是否含嵌套折叠（供上层决定要不要递归展开）。 */
    public static boolean hasNestedForward(List<ForwardMessage> msgs) {
        if (msgs == null) return false;
        for (ForwardMessage m : msgs) {
            if (m == null) continue;
            if (m.hasNested()) return true;
            for (ForwardItem it : m.items) {
                if (it.isNested() && it.nestedRaw != null) {
                    List<ForwardMessage> inner = parseForwardStructure(it.nestedRaw);
                    if (!inner.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    /**
     * 把结构渲染成带缩进的文本，<b>嵌套折叠就地递归展开</b>（不调模型，纯结构展开）。
     *
     * @param resolver   嵌套折叠取回回调（可为 null，此时嵌套只留占位）
     * @param maxDepth   最大递归层数
     * @param maxChars   渲染总长度上限
     * @param imageUrls  输出参数：收集到的所有图片 URL（含各嵌套层）
     */
    public static String renderStructure(List<ForwardMessage> msgs, NestedForwardResolver resolver,
                                         int maxDepth, int maxChars, List<String> imageUrls) {
        StringBuilder sb = new StringBuilder();
        renderLevel(msgs, resolver, 0, maxDepth, maxChars, imageUrls, sb, "");
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private static void renderLevel(List<ForwardMessage> msgs, NestedForwardResolver resolver,
                                    int depth, int maxDepth, int maxChars,
                                    List<String> imageUrls, StringBuilder sb, String indent) {
        if (msgs == null) return;
        for (ForwardMessage m : msgs) {
            if (m == null) continue;
            if (sb.length() >= maxChars) return;
            StringBuilder line = new StringBuilder();
            for (ForwardItem it : m.items) {
                if (it.isText()) {
                    if (line.length() > 0) line.append(" ");
                    line.append(it.text);
                } else if (it.isImage()) {
                    if (line.length() > 0) line.append(" ");
                    if (it.imageUrl != null && !it.imageUrl.isEmpty()) {
                        line.append("[图片:").append(it.imageUrl).append("]");
                        if (imageUrls != null && !imageUrls.contains(it.imageUrl)) imageUrls.add(it.imageUrl);
                    } else {
                        line.append("[图片]");
                    }
                } else if (ForwardItem.TYPE_FACE.equals(it.type)) {
                    if (line.length() > 0) line.append(" ");
                    line.append("[表情]");
                } else if (ForwardItem.TYPE_AT.equals(it.type)) {
                    if (line.length() > 0) line.append(" ");
                    line.append("@").append(it.text != null ? it.text : "");
                }
            }
            String name = (m.senderName != null && !m.senderName.isEmpty()) ? m.senderName : "未知";
            if (line.length() > 0) {
                sb.append(indent).append(name).append(": ").append(line).append("\n");
            }
            // 嵌套折叠就地递归展开
            for (ForwardItem it : m.items) {
                if (!it.isNested()) continue;
                if (sb.length() >= maxChars) return;
                if (depth >= maxDepth) {
                    sb.append(indent).append("  └[嵌套折叠：已达最大展开层数 ").append(maxDepth).append("，未继续展开]\n");
                    continue;
                }
                List<ForwardMessage> inner = null;
                if (it.nestedRaw != null && !it.nestedRaw.isEmpty()) {
                    inner = parseForwardStructure(it.nestedRaw);
                }
                if ((inner == null || inner.isEmpty()) && it.nestedId != null && !it.nestedId.isEmpty()
                        && resolver != null) {
                    String nestedJson = resolver.resolve(it.nestedId);
                    if (nestedJson != null && !nestedJson.isEmpty()) inner = parseForwardStructure(nestedJson);
                }
                if (inner == null || inner.isEmpty()) {
                    sb.append(indent).append("  └[内嵌折叠消息：内容获取失败]\n");
                    continue;
                }
                sb.append(indent).append("  ┌[内嵌折叠消息]\n");
                renderLevel(inner, resolver, depth + 1, maxDepth, maxChars, imageUrls, sb, indent + "    ");
                sb.append(indent).append("  └[内嵌折叠消息结束]\n");
            }
        }
    }

    /** 从get_forward_msg API响应中提取文本 */
    public static String extractForwardMsgContent(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;

        StringBuilder result = new StringBuilder();
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String messagesArr = JsonUtil.extractArray(dataObj, "messages");
            if (messagesArr == null || messagesArr.isEmpty()) return null;

            List<String> msgItems = JsonUtil.splitJsonArray(messagesArr);
            for (String msgItem : msgItems) {
                String senderObj = JsonUtil.extractObject(msgItem, "sender");
                String nickname = senderObj != null ? JsonUtil.extractString(senderObj, "nickname") : null;
                if (nickname == null || nickname.isEmpty()) nickname = "未知";

                String messageArr = JsonUtil.extractArray(msgItem, "message");
                if (messageArr == null || messageArr.isEmpty()) continue;

                List<String> segs = JsonUtil.splitJsonArray(messageArr);
                StringBuilder msgText = new StringBuilder();
                for (String seg : segs) {
                    String type = JsonUtil.extractString(seg, "type");
                    if ("text".equals(type)) {
                        String data = JsonUtil.extractObject(seg, "data");
                        if (data != null) {
                            String text = JsonUtil.extractString(data, "text");
                            if (text != null && !text.isEmpty()) {
                                if (msgText.length() > 0) msgText.append(" ");
                                msgText.append(text);
                            }
                        }
                    } else if ("image".equals(type)) {
                        if (msgText.length() > 0) msgText.append(" ");
                        msgText.append("[图片]");
                    } else if ("face".equals(type)) {
                        if (msgText.length() > 0) msgText.append(" ");
                        msgText.append("[表情]");
                    } else if ("forward".equals(type)) {
                        if (msgText.length() > 0) msgText.append(" ");
                        msgText.append("[内嵌折叠消息]");
                    } else if ("at".equals(type)) {
                        String dd = JsonUtil.extractObject(seg, "data");
                        if (dd != null) {
                            String qqq = JsonUtil.extractString(dd, "qq");
                            if (qqq != null) msgText.append("@").append(qqq);
                        }
                    }
                }

                if (msgText.length() > 0) {
                    if (result.length() > 0) result.append("\n");
                    result.append(nickname).append(": ").append(msgText.toString());
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] extractForwardMsgContent解析失败: " + e.toString());
            return null;
        }

        return result.length() > 0 ? result.toString() : null;
    }

    /** 从get_msg API响应中提取消息段文本 */
    public static String extractMsgSegmentsText(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String msgArr = JsonUtil.extractArray(dataObj, "message");
            if (msgArr == null || msgArr.isEmpty()) return null;
            List<String> segs = JsonUtil.splitJsonArray(msgArr);
            for (String seg : segs) {
                String type = JsonUtil.extractString(seg, "type");
                if ("text".equals(type)) {
                    String data = JsonUtil.extractObject(seg, "data");
                    if (data != null) {
                        String text = JsonUtil.extractString(data, "text");
                        if (text != null && !text.isEmpty()) sb.append(text);
                    }
                } else if ("image".equals(type)) {
                    sb.append("[图片]");
                } else if ("face".equals(type)) {
                    sb.append("[表情]");
                } else if ("forward".equals(type)) {
                    sb.append("[折叠消息]");
                } else if ("at".equals(type)) {
                    String data = JsonUtil.extractObject(seg, "data");
                    if (data != null) {
                        String qq = JsonUtil.extractString(data, "qq");
                        if (qq != null) sb.append("@").append(qq);
                    }
                }
            }
        } catch (Exception e) {
            return null;
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 从get_msg API响应中提取引用消息的完整信息（含发送者+内容）。
     * @return 格式: "【引用】张三(QQ:123): 消息内容"
     */
    public static String extractQuotedMessageFull(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return null;

            // 提取发送者信息
            String senderObj = JsonUtil.extractObject(dataObj, "sender");
            long senderQQ = 0;
            String senderNick = "未知";
            if (senderObj != null) {
                senderQQ = JsonUtil.extractLong(senderObj, "user_id");
                String nick = JsonUtil.extractString(senderObj, "nickname");
                if (nick != null && !nick.isEmpty()) senderNick = nick;
                // 群名片优先
                String card = JsonUtil.extractString(senderObj, "card");
                if (card != null && !card.isEmpty()) senderNick = card;
            }

            // 提取消息文本
            String msgText = extractMsgSegmentsText(apiResponse);
            if (msgText == null || msgText.isEmpty()) {
                // 降级: raw_message
                msgText = JsonUtil.extractString(dataObj, "raw_message");
                if (msgText == null || msgText.isEmpty()) return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("【引用】");
            sb.append(senderNick);
            if (senderQQ > 0) sb.append("(QQ:").append(senderQQ).append(")");
            sb.append(": ").append(msgText);
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 get_msg API 响应中提取引用消息发送者信息。
     * @return String[]{昵称/群名片, QQ号字符串}，解析失败返回 null
     */
    public static String[] extractQuotedSender(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String senderObj = JsonUtil.extractObject(dataObj, "sender");
            if (senderObj == null) return null;
            long senderQQ = JsonUtil.extractLong(senderObj, "user_id");
            String nick = JsonUtil.extractString(senderObj, "nickname");
            if (nick == null || nick.isEmpty()) nick = "未知";
            String card = JsonUtil.extractString(senderObj, "card");
            if (card != null && !card.isEmpty()) nick = card;
            return new String[]{ nick, String.valueOf(senderQQ) };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 get_msg API 响应中提取引用消息里图片的 URL 列表（用于 OCR 识别）。
     * @return 图片 URL 列表（无图片时返回 null 或空列表）
     */
    public static List<String> extractQuotedImageUrls(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String msgArr = JsonUtil.extractArray(dataObj, "message");
            if (msgArr == null || msgArr.isEmpty()) return null;
            List<String> urls = new java.util.ArrayList<>();
            List<String> segs = JsonUtil.splitJsonArray(msgArr);
            for (String seg : segs) {
                String type = JsonUtil.extractString(seg, "type");
                if ("image".equals(type)) {
                    String data = JsonUtil.extractObject(seg, "data");
                    String url = (data != null) ? JsonUtil.extractString(data, "url") : null;
                    // 关键：即使 url 为空也占位，保证与 extractQuotedImageFiles 逐项对齐。
                    // 旧实现会跳过空 url，导致后续 (url, file) 全部错位一格 ——
                    // 于是 NapCat 兜底下载拿到的是**另一张图**的 md5，图注/去重全部张冠李戴。
                    urls.add(url != null ? url : "");
                }
            }
            return urls;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 get_msg API 响应中提取引用消息里图片的 file 字段（md5 值）列表。
     * <p>NapCat get_image API 需要图片的 file 字段（md5 值）而非 URL，
     * 因此本方法用于内网图 HTTP 直连下载失败时的 NapCat 兜底下载。</p>
     * <p>顺序与 {@link #extractQuotedImageUrls} 返回的 URL 一一对应；
     * file 为空时降级取 file_id，再为空则占位空串保持对齐。</p>
     * @return file(md5) 列表（无图片时返回 null 或空列表）
     */
    public static List<String> extractQuotedImageFiles(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String msgArr = JsonUtil.extractArray(dataObj, "message");
            if (msgArr == null || msgArr.isEmpty()) return null;
            List<String> files = new java.util.ArrayList<>();
            List<String> segs = JsonUtil.splitJsonArray(msgArr);
            for (String seg : segs) {
                String type = JsonUtil.extractString(seg, "type");
                if ("image".equals(type)) {
                    String data = JsonUtil.extractObject(seg, "data");
                    if (data == null) continue;
                    String file = JsonUtil.extractString(data, "file");
                    if (file == null || file.isEmpty()) {
                        file = JsonUtil.extractString(data, "file_id");
                    }
                    files.add(file != null ? file : "");
                }
            }
            return files;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 get_group_msg_history / get_friend_msg_history 响应中提取图片 URL。
     * 优先返回「发送者 == fromUserId」的最近一条含图消息的图片 URL；
     * 若找不到该发送者的图片，则回退返回最近一条任意发送者的含图消息图片 URL。
     * 始终跳过机器人自己（selfId）发出的图片，避免重复存图与重复视觉分析。
     * @param apiResponse 历史消息 API 响应 JSON
     * @param fromUserId 期望的发送者QQ（<=0 表示不限定发送者）
     * @param selfId 机器人自身QQ（>0 时跳过 bot 自己发的图）
     * @return 图片 URL 列表（无图片返回空列表）
     */
    public static List<String> extractHistoryImageUrls(String apiResponse, long fromUserId, long selfId) {
        if (apiResponse == null || apiResponse.isEmpty()) return new java.util.ArrayList<>();
        List<String> fallback = new java.util.ArrayList<>();
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return fallback;
            String messagesArr = JsonUtil.extractArray(dataObj, "messages");
            if (messagesArr == null || messagesArr.isEmpty()) return fallback;
            List<String> msgItems = JsonUtil.splitJsonArray(messagesArr);
            for (String msgItem : msgItems) {
                long senderUid = 0;
                String senderObj = JsonUtil.extractObject(msgItem, "sender");
                if (senderObj != null) senderUid = JsonUtil.extractLong(senderObj, "user_id");

                // 跳过机器人自己发的图（避免重复存图 + 重复视觉分析）
                if (selfId > 0 && senderUid == selfId) continue;

                String messageArr = JsonUtil.extractArray(msgItem, "message");
                if (messageArr == null || messageArr.isEmpty()) continue;

                List<String> urls = new java.util.ArrayList<>();
                List<String> segs = JsonUtil.splitJsonArray(messageArr);
                for (String seg : segs) {
                    if (!"image".equals(JsonUtil.extractString(seg, "type"))) continue;
                    String data = JsonUtil.extractObject(seg, "data");
                    if (data == null) continue;
                    String url = JsonUtil.extractString(data, "url");
                    if (url != null && !url.isEmpty()) urls.add(url);
                }
                if (urls.isEmpty()) continue;

                if (fromUserId > 0 && senderUid == fromUserId) {
                    return urls; // 命中该用户最近一条含图消息
                }
                if (fallback.isEmpty()) {
                    fallback.addAll(urls); // 记录最近一条任意含图消息
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] extractHistoryImageUrls解析失败: " + e.toString());
        }
        return fallback;
    }

    /** 从get_msg API响应中检测是否包含forward段，返回forward_id */
    public static String extractForwardIdFromMsgJson(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String msgArr = JsonUtil.extractArray(dataObj, "message");
            if (msgArr == null || msgArr.isEmpty()) return null;
            List<String> segs = JsonUtil.splitJsonArray(msgArr);
            for (String seg : segs) {
                String type = JsonUtil.extractString(seg, "type");
                if ("forward".equals(type)) {
                    String data = JsonUtil.extractObject(seg, "data");
                    if (data != null) {
                        String fwdId = JsonUtil.extractString(data, "id");
                        if (fwdId != null && !fwdId.isEmpty()) return fwdId;
                    }
                }
            }
        } catch (Exception e) { }
        return null;
    }

    /**
     * 从 get_msg API 响应中提取 forward 段的 data.content（内嵌转发内容）。
     * <p>NapCat 的 get_msg 返回 forward 段时，data.content 直接内嵌了转发消息列表，
     * data.id 字段对获取内容无用；因此优先用本方法拿内嵌内容，失败再回退 get_forward_msg。</p>
     * @return forward content 的 JSON 数组字符串，无内嵌 content 时返回 null
     */
    public static String extractForwardContentFromMsgJson(String apiResponse) {
        if (apiResponse == null || apiResponse.isEmpty()) return null;
        try {
            String dataObj = JsonUtil.extractObject(apiResponse, "data");
            if (dataObj == null) return null;
            String msgArr = JsonUtil.extractArray(dataObj, "message");
            if (msgArr == null || msgArr.isEmpty()) return null;
            List<String> segs = JsonUtil.splitJsonArray(msgArr);
            for (String seg : segs) {
                String type = JsonUtil.extractString(seg, "type");
                if ("forward".equals(type)) {
                    String data = JsonUtil.extractObject(seg, "data");
                    if (data != null) {
                        String content = JsonUtil.extractArray(data, "content");
                        if (content != null && !content.isEmpty()) return content;
                    }
                }
            }
        } catch (Exception e) { }
        return null;
    }

    /** 从forward content JSON中提取文本 */
    public static String extractForwardText(String forwardContent) {
        if (forwardContent == null || forwardContent.isEmpty()) return null;

        StringBuilder result = new StringBuilder();
        try {
            List<String> items = JsonUtil.splitJsonArray(forwardContent);
            for (String item : items) {
                String msgArr = JsonUtil.extractArray(item, "message");
                if (msgArr != null && !msgArr.isEmpty()) {
                    List<String> msgSegments = JsonUtil.splitJsonArray(msgArr);
                    for (String seg : msgSegments) {
                        String type = JsonUtil.extractString(seg, "type");
                        if ("text".equals(type)) {
                            String dataObj = JsonUtil.extractObject(seg, "data");
                            if (dataObj != null) {
                                String text = JsonUtil.extractString(dataObj, "text");
                                if (text != null && !text.isEmpty()) {
                                    if (result.length() > 0) result.append(" | ");
                                    result.append(text);
                                }
                            }
                        } else if ("image".equals(type)) {
                            if (result.length() > 0) result.append(" | ");
                            result.append("[图片]");
                        }
                    }
                } else {
                    String type = JsonUtil.extractString(item, "type");
                    if ("node".equals(type)) {
                        // NapCat 合并转发节点：data.content 才是真正的消息段列表，递归解析。
                        String dataObj = JsonUtil.extractObject(item, "data");
                        if (dataObj != null) {
                            String nodeContent = JsonUtil.extractArray(dataObj, "content");
                            if (nodeContent != null && !nodeContent.isEmpty()) {
                                String nested = extractForwardText(nodeContent);
                                if (nested != null && !nested.isEmpty()) {
                                    if (result.length() > 0) result.append(" | ");
                                    String name = JsonUtil.extractString(dataObj, "name");
                                    if (name != null && !name.isEmpty()) result.append(name).append(": ");
                                    result.append(nested);
                                }
                            }
                        }
                    } else if ("text".equals(type)) {
                        String dataObj = JsonUtil.extractObject(item, "data");
                        if (dataObj != null) {
                            String text = JsonUtil.extractString(dataObj, "text");
                            if (text != null && !text.isEmpty()) {
                                if (result.length() > 0) result.append(" | ");
                                result.append(text);
                            }
                        }
                    } else if ("image".equals(type)) {
                        if (result.length() > 0) result.append(" | ");
                        result.append("[图片]");
                    }
                }
            }
        } catch (Exception e) {
            return forwardContent.replaceAll("\\{[^}]*\\}", "").replaceAll("[\"\\[\\]]", "").trim();
        }

        return result.length() > 0 ? result.toString() : null;
    }
}
