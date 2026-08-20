package sair.aiagent.onebot;

import java.util.List;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.onebot.util.JsonUtil;

/**
 * 折叠/转发消息内容展开工具类。
 * 从 QQMessageHandler 中提取，负责从 OneBot v11 API 响应中提取文本内容。
 */
public final class ForwardMessageExpander {

    private ForwardMessageExpander() {} // 纯静态工具类，禁止实例化

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
                    if (data != null) {
                        String url = JsonUtil.extractString(data, "url");
                        if (url != null && !url.isEmpty()) urls.add(url);
                    }
                }
            }
            return urls;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 get_group_msg_history / get_friend_msg_history 响应中提取图片 URL。
     * 优先返回「发送者 == fromUserId」的最近一条含图消息的图片 URL；
     * 若找不到该发送者的图片，则回退返回最近一条任意发送者的含图消息图片 URL。
     * @param apiResponse 历史消息 API 响应 JSON
     * @param fromUserId 期望的发送者QQ（<=0 表示不限定发送者）
     * @return 图片 URL 列表（无图片返回空列表）
     */
    public static List<String> extractHistoryImageUrls(String apiResponse, long fromUserId) {
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
                    if ("text".equals(type)) {
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
