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
