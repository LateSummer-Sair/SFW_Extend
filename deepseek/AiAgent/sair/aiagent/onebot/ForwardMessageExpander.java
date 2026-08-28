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
