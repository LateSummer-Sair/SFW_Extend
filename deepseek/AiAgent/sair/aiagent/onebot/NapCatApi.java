package sair.aiagent.onebot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sair.aiagent.AiAgentActivity;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * NapCat API 封装类 —— 提供所有OneBot v11 API的便捷调用。
 * <p>
 * 这个类封装了所有可以通过OneBot协议调用的API，让AI能够直接操作QQ功能。
 * AI可以通过execs模式调用这些API，实现自主管理群聊和用户的能力。
 * </p>
 * 
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在execs模式下，AI可以这样调用：
 * NapCatApi api = new NapCatApi(server);
 * api.muteGroupMember(groupId, userId, 600); // 禁言10分钟
 * api.sendGroupMessage(groupId, "大家好！");
 * }</pre>
 */
public class NapCatApi {
    
    private final OneBotServer server;
    
    public NapCatApi(OneBotServer server) {
        this.server = server;
    }
    
    // ==================== 消息发送API ====================
    
    /**
     * 发送私聊消息
     * @param userId 目标QQ号
     * @param message 消息内容
     * @return API响应JSON
     */
    public String sendPrivateMessage(long userId, String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("message", message);
        return server.sendApiCall("send_private_msg", params);
    }
    
    /**
     * 发送群消息
     * @param groupId 群号
     * @param message 消息内容
     * @return API响应JSON
     */
    public String sendGroupMessage(long groupId, String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("message", message);
        return server.sendApiCall("send_group_msg", params);
    }
    
    /**
     * 回复消息
     * @param messageId 原消息ID
     * @param message 回复内容
     * @return API响应JSON
     */
    public String replyMessage(long messageId, String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        params.put("message", message);
        return server.sendApiCall("send_msg", params);
    }

    /**
     * 获取语音转文字结果（fetch_ptt_text）。
     * <p>由程序自动调用（非 AI 触发），将收到的语音消息转为文本后注入上下文，避免消耗 AI token。</p>
     *
     * @param messageId 语音消息ID
     * @return 转文字文本；失败或为空返回 null
     */
    public String fetchPttText(long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        String resp = server.sendApiCall("fetch_ptt_text", params);
        if (resp == null || resp.isEmpty()) return null;
        try {
            JsonObject obj = JsonParser.parseString(resp).getAsJsonObject();
            if (obj.has("data") && !obj.get("data").isJsonNull()) {
                JsonObject data = obj.getAsJsonObject("data");
                if (data.has("text") && !data.get("text").isJsonNull()) {
                    String text = data.get("text").getAsString();
                    return (text != null && !text.trim().isEmpty()) ? text.trim() : null;
                }
            }
            return null;
        } catch (Exception e) {
            AiAgentActivity.qqLog("[NapCat] fetch_ptt_text 解析失败: " + e.toString());
            return null;
        }
    }
    
    // ==================== 群管理API ====================
    
    /**
     * 禁言群成员
     * @param groupId 群号
     * @param userId 被禁言的QQ号
     * @param duration 禁言时长（秒），0表示解除禁言
     * @return API响应JSON
     */
    public String muteGroupMember(long groupId, long userId, int duration) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        params.put("duration", duration);
        return server.sendApiCall("set_group_ban", params);
    }
    
    /**
     * 全员禁言
     * @param groupId 群号
     * @param enable true开启全员禁言，false关闭
     * @return API响应JSON
     */
    public String muteAll(long groupId, boolean enable) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("enable", enable);
        return server.sendApiCall("set_group_whole_ban", params);
    }
    
    /**
     * 设置群管理员
     * @param groupId 群号
     * @param userId 目标QQ号
     * @param enable true设置为管理员，false取消管理员
     * @return API响应JSON
     */
    public String setGroupAdmin(long groupId, long userId, boolean enable) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        params.put("enable", enable);
        return server.sendApiCall("set_group_admin", params);
    }
    
    /**
     * 设置群名片
     * @param groupId 群号
     * @param userId 目标QQ号
     * @param card 新名片（空字符串表示删除名片）
     * @return API响应JSON
     */
    public String setGroupCard(long groupId, long userId, String card) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        params.put("card", card);
        return server.sendApiCall("set_group_card", params);
    }
    
    /**
     * 设置群名
     * @param groupId 群号
     * @param groupName 新群名
     * @return API响应JSON
     */
    public String setGroupName(long groupId, String groupName) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("group_name", groupName);
        return server.sendApiCall("set_group_name", params);
    }
    
    /**
     * 退出群聊
     * @param groupId 群号
     * @param isDismiss true解散群（仅群主可用），false退出群
     * @return API响应JSON
     */
    public String leaveGroup(long groupId, boolean isDismiss) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("is_dismiss", isDismiss);
        return server.sendApiCall("set_group_leave", params);
    }
    
    /**
     * 踢出群成员
     * @param groupId 群号
     * @param userId 被踢的QQ号
     * @param rejectAdd true拒绝再次申请加群
     * @return API响应JSON
     */
    public String kickGroupMember(long groupId, long userId, boolean rejectAdd) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        params.put("reject_add_request", rejectAdd);
        return server.sendApiCall("set_group_kick", params);
    }
    
    // ==================== 好友管理API ====================
    
    /**
     * 添加好友
     * @param userId 目标QQ号
     * @param comment 验证信息
     * @return API响应JSON
     */
    public String addFriend(long userId, String comment) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("comment", comment);
        return server.sendApiCall("send_friend_add_request", params);
    }
    
    /**
     * 删除好友
     * @param userId 目标QQ号
     * @return API响应JSON
     */
    public String deleteFriend(long userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        return server.sendApiCall("delete_friend", params);
    }
    
    /**
     * 处理好友请求
     * @param flag 请求flag
     * @param approve true同意，false拒绝
     * @param remark 备注（仅在同意时有效）
     * @return API响应JSON
     */
    public String handleFriendRequest(String flag, boolean approve, String remark) {
        Map<String, Object> params = new HashMap<>();
        params.put("flag", flag);
        params.put("approve", approve);
        if (remark != null && !remark.isEmpty()) {
            params.put("remark", remark);
        }
        return server.sendApiCall("set_friend_add_request", params);
    }
    
    /**
     * 处理群邀请
     * @param flag 请求flag
     * @param approve true同意，false拒绝
     * @param reason 拒绝理由（仅在拒绝时有效）
     * @return API响应JSON
     */
    public String handleGroupInvite(String flag, boolean approve, String reason) {
        Map<String, Object> params = new HashMap<>();
        params.put("flag", flag);
        params.put("approve", approve);
        if (reason != null && !reason.isEmpty()) {
            params.put("reason", reason);
        }
        return server.sendApiCall("set_group_add_request", params);
    }
    
    // ==================== 信息查询API ====================
    
    /**
     * 获取陌生人信息
     * @param userId QQ号
     * @param noCache 是否不使用缓存
     * @return API响应JSON
     */
    public String getStrangerInfo(long userId, boolean noCache) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("no_cache", noCache);
        return server.sendApiCall("get_stranger_info", params);
    }
    
    /**
     * 获取群成员信息
     * @param groupId 群号
     * @param userId QQ号
     * @param noCache 是否不使用缓存
     * @return API响应JSON
     */
    public String getGroupMemberInfo(long groupId, long userId, boolean noCache) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        params.put("no_cache", noCache);
        return server.sendApiCall("get_group_member_info", params);
    }
    
    /**
     * 获取群信息（群名、成员数等）
     * @param groupId 群号
     * @param noCache 是否不使用缓存
     * @return API响应JSON
     */
    public String getGroupInfo(long groupId, boolean noCache) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("no_cache", noCache);
        return server.sendApiCall("get_group_info", params);
    }

    /**
     * 获取群列表
     * @return API响应JSON
     */
    public String getGroupList() {
        return server.sendApiCall("get_group_list", new HashMap<>());
    }
    
    /**
     * 获取群成员列表
     * @param groupId 群号
     * @return API响应JSON
     */
    public String getGroupMemberList(long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return server.sendApiCall("get_group_member_list", params);
    }
    
    /**
     * 获取好友列表
     * @return API响应JSON
     */
    public String getFriendList() {
        return server.sendApiCall("get_friend_list", new HashMap<>());
    }
    
    // ==================== 图片发送API ====================
    
    /**
     * 发送群图片消息
     * @param groupId 群号
     * @param fileOrUrl 图片文件路径（本地绝对路径）或URL
     * @return API响应JSON
     */
    public String sendGroupImage(long groupId, String fileOrUrl) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("message", "[CQ:image,file=" + escapeCQ(fileOrUrl) + "]");
        return server.sendApiCall("send_group_msg", params);
    }
    
    /**
     * 发送私聊图片消息
     * @param userId QQ号
     * @param fileOrUrl 图片文件路径（本地绝对路径）或URL
     * @return API响应JSON
     */
    public String sendPrivateImage(long userId, String fileOrUrl) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("message", "[CQ:image,file=" + escapeCQ(fileOrUrl) + "]");
        return server.sendApiCall("send_private_msg", params);
    }
    
    // ==================== 语音发送API ====================
    
    /**
     * 发送群语音消息
     * @param groupId 群号
     * @param fileOrUrl 语音文件路径（本地绝对路径）或URL
     * @return API响应JSON
     */
    public String sendGroupRecord(long groupId, String fileOrUrl) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("message", "[CQ:record,file=" + escapeCQ(fileOrUrl) + "]");
        return server.sendApiCall("send_group_msg", params);
    }
    
    /**
     * 发送私聊语音消息
     * @param userId QQ号
     * @param fileOrUrl 语音文件路径（本地绝对路径）或URL
     * @return API响应JSON
     */
    public String sendPrivateRecord(long userId, String fileOrUrl) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("message", "[CQ:record,file=" + escapeCQ(fileOrUrl) + "]");
        return server.sendApiCall("send_private_msg", params);
    }
    
    // ==================== 文件发送API ====================
    
    /**
     * 发送群文件
     * @param groupId 群号
     * @param filePath 文件本地绝对路径 或 HTTP(S) URL（文件中转服务）
     * @param fileName 文件显示名称
     * @return API响应JSON
     */
    public String sendGroupFile(long groupId, String filePath, String fileName) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("file", toFileParam(filePath));
        params.put("name", fileName != null ? fileName : new java.io.File(filePath).getName());
        return server.sendApiCall("upload_group_file", params);
    }

    /**
     * 发送私聊文件
     * @param userId QQ号
     * @param filePath 文件本地绝对路径 或 HTTP(S) URL（文件中转服务）
     * @param fileName 文件显示名称
     * @return API响应JSON
     */
    public String sendPrivateFile(long userId, String filePath, String fileName) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("file", toFileParam(filePath));
        params.put("name", fileName != null ? fileName : new java.io.File(filePath).getName());
        return server.sendApiCall("upload_private_file", params);
    }

    /**
     * 将文件参数统一化：HTTP(S) URL 直接透传（文件中转服务）；本地路径转换为 NapCat 标准 file:/// URI。
     */
    private static String toFileParam(String filePath) {
        String p = filePath == null ? "" : filePath.trim();
        if (p.startsWith("http://") || p.startsWith("https://")) {
            return p;
        }
        // NapCat 需要标准 file:/// URI（三斜杠）：Windows 盘符 C:/x → file:///C:/x，Linux 绝对路径 /x → file:///x
        String forwardPath = p.replace('\\', '/');
        return forwardPath.startsWith("/") ? ("file://" + forwardPath) : ("file:///" + forwardPath);
    }
    
    // ==================== 消息查询API ====================
    
    /**
     * 获取消息详情（用于引用回复分析）
     * @param messageId 消息ID
     * @return API响应JSON
     */
    public String getMessage(long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        return server.sendApiCall("get_msg", params);
    }
    
    /**
     * 获取合并转发消息内容（用于折叠消息分析）
     * @param forwardId 转发消息ID（来自forward段的data.id）
     * @return API响应JSON
     */
    public String getForwardMsg(String forwardId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", forwardId);
        return server.sendApiCall("get_forward_msg", params);
    }
    
    // ==================== 合并转发API ====================
    
    /**
     * 发送群聊合并转发消息（QQ原生折叠卡片效果）。
     * <p>消息节点格式：List&lt;Map&gt;，每个Map包含type=node和data={uin,name,content}。</p>
     * @param groupId 群号
     * @param nodes 转发消息节点列表
     * @return API响应JSON
     */
    public String sendGroupForwardMsg(long groupId, List<Map<String, Object>> nodes) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("messages", nodes);
        return server.sendApiCall("send_group_forward_msg", params);
    }
    
    /**
     * 发送私聊合并转发消息（QQ原生折叠卡片效果）。
     * <p>消息节点格式：List&lt;Map&gt;，每个Map包含type=node和data={uin,name,content}。</p>
     * @param userId 目标QQ号
     * @param nodes 转发消息节点列表
     * @return API响应JSON
     */
    public String sendPrivateForwardMsg(long userId, List<Map<String, Object>> nodes) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("messages", nodes);
        return server.sendApiCall("send_private_forward_msg", params);
    }
    
    // ==================== 个人资料API (v2.4) ====================
    
    /**
     * 设置QQ个人资料（昵称、个性签名等）。
     * @param nickname 新昵称（null表示不修改）
     * @param personalNote 个性签名/个人说明（null表示不修改）
     * @return API响应JSON
     */
    public String setQQProfile(String nickname, String personalNote) {
        Map<String, Object> params = new HashMap<>();
        if (nickname != null && !nickname.isEmpty()) params.put("nickname", nickname);
        if (personalNote != null && !personalNote.isEmpty()) params.put("personal_note", personalNote);
        return server.sendApiCall("set_qq_profile", params);
    }

    /**
     * 设置个性签名（NapCat 专用 API set_self_longnick）。
     * @param longNick 个性签名内容（空字符串表示清空）
     * @return API响应JSON
     */
    public String setSelfLongnick(String longNick) {
        Map<String, Object> params = new HashMap<>();
        params.put("longNick", longNick == null ? "" : longNick);
        return server.sendApiCall("set_self_longnick", params);
    }
    
    /**
     * 获取登录号信息（自己的QQ号、昵称等）
     * @return API响应JSON
     */
    public String getLoginInfo() {
        return server.sendApiCall("get_login_info", new HashMap<>());
    }
    
    // ==================== 互动API (v2.4) ====================
    
    /**
     * 给好友点赞（拟人化互动）。
     * @param userId 好友QQ号
     * @param times 点赞次数（默认10）
     * @return API响应JSON
     */
    public String sendLike(long userId, int times) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("times", times > 0 ? times : 10);
        return server.sendApiCall("send_like", params);
    }
    
    // ==================== 消息管理API (v2.4) ====================
    
    /**
     * 撤回消息。
     * @param messageId 消息ID
     * @return API响应JSON
     */
    public String deleteMsg(long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        return server.sendApiCall("delete_msg", params);
    }
    
    /**
     * 标记消息已读。
     * @param messageId 消息ID
     * @return API响应JSON
     */
    public String markMsgRead(long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        return server.sendApiCall("mark_msg_as_read", params);
    }
    
    // ==================== 系统API (v2.4) ====================
    
    /**
     * 获取运行状态。
     * @return API响应JSON
     */
    public String getStatus() {
        return server.sendApiCall("get_status", new HashMap<>());
    }
    
    /**
     * 获取版本信息。
     * @return API响应JSON
     */
    public String getVersionInfo() {
        return server.sendApiCall("get_version_info", new HashMap<>());
    }
    
    /**
     * 检查是否可以发送图片。
     * @return API响应JSON
     */
    public String canSendImage() {
        return server.sendApiCall("can_send_image", new HashMap<>());
    }
    
    /**
     * 检查是否可以发送语音。
     * @return API响应JSON
     */
    public String canSendRecord() {
        return server.sendApiCall("can_send_record", new HashMap<>());
    }
    
    // ==================== 图片/文件资源API ====================
    
    /**
     * 获取图片文件数据（OneBot get_image API）。
     * 用于下载 QQ 图片转为 Vision API 可用格式的兜底方案。
     * @param file 图片文件名或 file_id
     * @return API响应JSON（data 字段包含 base64 编码的图片数据）
     */
    public String getImage(String file) {
        Map<String, Object> params = new HashMap<>();
        params.put("file", file);
        return server.sendApiCall("get_image", params);
    }

    // ==================== 消息历史API ====================

    /**
     * 获取群历史消息记录。
     * @param groupId 群号
     * @param messageSeq 起始消息序号（0表示最新）
     * @param count 获取数量（默认20）
     * @return API响应JSON
     */
    public String getGroupMsgHistory(long groupId, int messageSeq, int count) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        if (messageSeq > 0) params.put("message_seq", messageSeq);
        params.put("count", count > 0 ? count : 20);
        return server.sendApiCall("get_group_msg_history", params);
    }

    /**
     * 获取好友历史消息记录。
     * @param userId 好友QQ号
     * @param messageSeq 起始消息序号（0表示最新）
     * @param count 获取数量（默认20）
     * @return API响应JSON
     */
    public String getFriendMsgHistory(long userId, int messageSeq, int count) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        if (messageSeq > 0) params.put("message_seq", messageSeq);
        params.put("count", count > 0 ? count : 20);
        return server.sendApiCall("get_friend_msg_history", params);
    }

    // ==================== 群公告API ====================

    /**
     * 发送群公告。
     * @param groupId 群号
     * @param content 公告内容
     * @param image 公告图片（可选，本地路径或URL）
     * @return API响应JSON
     */
    public String sendGroupNotice(long groupId, String content, String image) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("content", content);
        if (image != null && !image.isEmpty()) params.put("image", image);
        return server.sendApiCall("_send_group_notice", params);
    }

    /**
     * 获取群公告列表。
     * @param groupId 群号
     * @return API响应JSON
     */
    public String getGroupNotice(long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return server.sendApiCall("_get_group_notice", params);
    }

    // ==================== 群设置扩展API ====================

    /**
     * 设置群头像。
     * @param groupId 群号
     * @param file 头像图片路径（本地绝对路径）
     * @param cache 是否使用缓存（0关闭，1开启）
     * @return API响应JSON
     */
    public String setGroupPortrait(long groupId, String file, int cache) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("file", file);
        params.put("cache", cache >= 0 ? cache : 1);
        return server.sendApiCall("set_group_portrait", params);
    }

    /**
     * 获取群 @全体成员 剩余次数。
     * @param groupId 群号
     * @return API响应JSON（data字段：can_at_all, remain_at_all_count_for_group, remain_at_all_count_for_uin）
     */
    public String getGroupAtAllRemain(long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return server.sendApiCall("get_group_at_all_remain", params);
    }

    /**
     * 设置群成员专属头衔。
     * @param groupId 群号
     * @param userId 目标QQ号
     * @param title 头衔内容（空字符串删除头衔）
     * @param duration 有效期（秒，-1永久）
     * @return API响应JSON
     */
    public String setGroupSpecialTitle(long groupId, long userId, String title, long duration) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        params.put("special_title", title != null ? title : "");
        if (duration >= 0) params.put("duration", duration);
        return server.sendApiCall("set_group_special_title", params);
    }

    // ==================== 群信息查询API ====================

    /**
     * 获取群荣誉信息（龙王/群聊之火等）。
     * @param groupId 群号
     * @param type 荣誉类型：talkative(龙王)/performer(群聊之火)/legend(群聊炽焰)/strong_newbie(冒泡)/emotion(快乐之源)
     * @return API响应JSON
     */
    public String getGroupHonorInfo(long groupId, String type) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("type", type != null ? type : "all");
        return server.sendApiCall("get_group_honor_info", params);
    }

    /**
     * 获取群系统消息（加群请求等）。
     * @return API响应JSON
     */
    public String getGroupSystemMsg() {
        return server.sendApiCall("get_group_system_msg", new HashMap<>());
    }

    /**
     * 获取群禁言列表。
     * @param groupId 群号
     * @return API响应JSON
     */
    public String getGroupBanList(long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return server.sendApiCall("get_group_shut_list", params);
    }

    // ==================== 精华消息API ====================

    /**
     * 获取群精华消息列表。
     * @param groupId 群号
     * @return API响应JSON
     */
    public String getEssenceMsgList(long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return server.sendApiCall("get_essence_msg_list", params);
    }

    /**
     * 设置群精华消息。
     * @param messageId 消息ID
     * @return API响应JSON
     */
    public String setEssenceMsg(long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        return server.sendApiCall("set_essence_msg", params);
    }

    /**
     * 移除群精华消息。
     * @param messageId 消息ID
     * @return API响应JSON
     */
    public String deleteEssenceMsg(long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        return server.sendApiCall("delete_essence_msg", params);
    }

    // ==================== 互动/工具API ====================

    /**
     * 发送群戳一戳。
     * @param groupId 群号
     * @param userId 被戳的QQ号
     * @return API响应JSON
     */
    public String sendGroupPoke(long groupId, long userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userId);
        return server.sendApiCall("group_poke", params);
    }

    /**
     * 图片 OCR 识别。
     * @param image 图片文件路径或图片URL
     * @return API响应JSON（data字段含识别文本）
     */
    public String ocrImage(String image) {
        Map<String, Object> params = new HashMap<>();
        params.put("image", image);
        return server.sendApiCall("ocr_image", params);
    }

    /**
     * 设置在线状态（NapCat 扩展，非标准OneBot v11）。
     * @param status 状态码：10在线/30离开/40隐身/50忙碌/60Q我吧
     * @return API响应JSON
     */
    public String setOnlineStatus(int status) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", status);
        return server.sendApiCall("set_online_status", params);
    }

    /**
     * 获取 Cookies（NapCat 扩展）。
     * @param domain 域名（如 qq.com）
     * @return API响应JSON
     */
    public String getCookies(String domain) {
        Map<String, Object> params = new HashMap<>();
        if (domain != null && !domain.isEmpty()) params.put("domain", domain);
        return server.sendApiCall("get_cookies", params);
    }

    /**
     * 获取 CSRF Token。
     * @return API响应JSON
     */
    public String getCsrfToken() {
        return server.sendApiCall("get_csrf_token", new HashMap<>());
    }

    // ==================== 好友/用户扩展API ====================

    /**
     * 设置好友备注。
     * @param userId 好友QQ号
     * @param remark 备注内容
     * @return API响应JSON
     */
    public String setFriendRemark(long userId, String remark) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("remark", remark != null ? remark : "");
        return server.sendApiCall("set_friend_remark", params);
    }

    /**
     * 获取最近会话列表（NapCat 扩展）。
     * @return API响应JSON
     */
    public String getRecentContact() {
        return server.sendApiCall("get_recent_contact", new HashMap<>());
    }

    // ==================== 文件/语音资源API ====================

    /**
     * 获取文件数据（file 或 file_id）。
     * @param file 文件路径、URL 或 Base64
     * @param fileId 文件ID（与 file 二选一）
     * @return API响应JSON
     */
    public String getFile(String file, String fileId) {
        Map<String, Object> params = new HashMap<>();
        if (file != null && !file.isEmpty()) params.put("file", file);
        if (fileId != null && !fileId.isEmpty()) params.put("file_id", fileId);
        return server.sendApiCall("get_file", params);
    }

    /**
     * 获取语音数据（file 或 file_id，可转码输出）。
     * @param file 文件路径、URL 或 Base64
     * @param fileId 文件ID（与 file 二选一）
     * @param outFormat 输出格式：mp3/amr/wma/m4a/spx/ogg/wav/flac
     * @return API响应JSON
     */
    public String getRecord(String file, String fileId, String outFormat) {
        Map<String, Object> params = new HashMap<>();
        if (file != null && !file.isEmpty()) params.put("file", file);
        if (fileId != null && !fileId.isEmpty()) params.put("file_id", fileId);
        if (outFormat != null && !outFormat.isEmpty()) params.put("out_format", outFormat);
        return server.sendApiCall("get_record", params);
    }

    /**
     * 获取群文件直链 URL。
     * @param groupId 群号
     * @param fileId 文件ID
     * @return API响应JSON
     */
    public String getGroupFileUrl(long groupId, String fileId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("file_id", fileId);
        return server.sendApiCall("get_group_file_url", params);
    }

    /**
     * 获取私聊文件直链 URL。
     * @param fileId 文件ID
     * @return API响应JSON
     */
    public String getPrivateFileUrl(String fileId) {
        Map<String, Object> params = new HashMap<>();
        params.put("file_id", fileId);
        return server.sendApiCall("get_private_file_url", params);
    }

    /**
     * 下载文件到本地（url 或 base64）。
     * @param url 下载链接
     * @param base64 base64 数据
     * @param name 文件名
     * @param headers 请求头（字符串或数组）
     * @return API响应JSON
     */
    public String downloadFile(String url, String base64, String name, Object headers) {
        Map<String, Object> params = new HashMap<>();
        if (url != null && !url.isEmpty()) params.put("url", url);
        if (base64 != null && !base64.isEmpty()) params.put("base64", base64);
        if (name != null && !name.isEmpty()) params.put("name", name);
        if (headers != null) params.put("headers", headers);
        return server.sendApiCall("download_file", params);
    }

    // ==================== 流式传输API ====================

    /**
     * 流式上传文件（upload_file_stream）。
     */
    public String uploadFileStream(String streamId, String chunkData, long chunkIndex,
                                   long totalChunks, long fileSize, String expectedSha256,
                                   boolean isComplete, String filename, boolean reset,
                                   boolean verifyOnly, long fileRetention) {
        Map<String, Object> params = new HashMap<>();
        params.put("stream_id", streamId);
        if (chunkData != null) params.put("chunk_data", chunkData);
        params.put("chunk_index", chunkIndex);
        params.put("total_chunks", totalChunks);
        params.put("file_size", fileSize);
        if (expectedSha256 != null) params.put("expected_sha256", expectedSha256);
        params.put("is_complete", isComplete);
        if (filename != null) params.put("filename", filename);
        params.put("reset", reset);
        params.put("verify_only", verifyOnly);
        params.put("file_retention", fileRetention);
        return server.sendApiCall("upload_file_stream", params);
    }

    /**
     * 流式下载文件（download_file_stream）。
     */
    public String downloadFileStream(String file, String fileId, long chunkSize) {
        Map<String, Object> params = new HashMap<>();
        if (file != null && !file.isEmpty()) params.put("file", file);
        if (fileId != null && !fileId.isEmpty()) params.put("file_id", fileId);
        params.put("chunk_size", chunkSize);
        return server.sendApiCall("download_file_stream", params);
    }

    /**
     * 清理流式传输临时文件。
     * @return API响应JSON
     */
    public String cleanStreamTempFile() {
        return server.sendApiCall("clean_stream_temp_file", new HashMap<>());
    }

    // ==================== RKey / 凭证扩展API ====================

    /**
     * 获取 RKey（用于刷新图片/文件链接，NapCat 扩展）。
     * @return API响应JSON
     */
    public String getRkey() {
        return server.sendApiCall("get_rkey", new HashMap<>());
    }

    /**
     * 获取 RKey 服务器信息（NapCat 扩展）。
     * @return API响应JSON
     */
    public String getRkeyServer() {
        return server.sendApiCall("get_rkey_server", new HashMap<>());
    }

    /**
     * 获取 RKey（NapCat 原生 nc_get_rkey）。
     * @return API响应JSON
     */
    public String ncGetRkey() {
        return server.sendApiCall("nc_get_rkey", new HashMap<>());
    }

    /**
     * 获取登录凭证（domain 必填）。
     * @param domain 需要获取 cookies 的域名
     * @return API响应JSON
     */
    public String getCredentials(String domain) {
        Map<String, Object> params = new HashMap<>();
        if (domain != null && !domain.isEmpty()) params.put("domain", domain);
        return server.sendApiCall("get_credentials", params);
    }

    /**
     * 获取 ClientKey（NapCat 扩展）。
     * @return API响应JSON
     */
    public String getClientkey() {
        return server.sendApiCall("get_clientkey", new HashMap<>());
    }

    // ==================== 公告/消息扩展API ====================

    /**
     * 删除群公告。
     * @param groupId 群号
     * @param noticeId 公告ID
     * @return API响应JSON
     */
    public String deleteGroupNotice(long groupId, String noticeId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("notice_id", noticeId);
        return server.sendApiCall("_del_group_notice", params);
    }

    /**
     * 发送合并转发消息（通用，自动按 user_id/group_id 路由）。
     * @param userId 目标QQ号（私聊时填）
     * @param groupId 目标群号（群聊时填）
     * @param nodes 转发消息节点列表
     * @return API响应JSON
     */
    public String sendForwardMsg(Long userId, Long groupId, List<Map<String, Object>> nodes) {
        Map<String, Object> params = new HashMap<>();
        if (userId != null) params.put("user_id", userId);
        if (groupId != null) params.put("group_id", groupId);
        params.put("messages", nodes);
        return server.sendApiCall("send_forward_msg", params);
    }

    /**
     * 转发单条消息给好友。
     * @param messageId 要转发的消息ID
     * @param userId 目标用户QQ
     * @param groupId 目标群号（可选）
     * @return API响应JSON
     */
    public String forwardFriendSingleMsg(long messageId, long userId, Long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        params.put("user_id", userId);
        if (groupId != null) params.put("group_id", groupId);
        return server.sendApiCall("forward_friend_single_msg", params);
    }

    /**
     * 转发单条消息到群。
     * @param messageId 要转发的消息ID
     * @param groupId 目标群号
     * @param userId 目标用户QQ（可选）
     * @return API响应JSON
     */
    public String forwardGroupSingleMsg(long messageId, long groupId, Long userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("message_id", messageId);
        params.put("group_id", groupId);
        if (userId != null) params.put("user_id", userId);
        return server.sendApiCall("forward_group_single_msg", params);
    }

    /**
     * 标记群聊消息已读。
     * @param groupId 群号
     * @param messageId 消息ID
     * @return API响应JSON
     */
    public String markGroupMsgRead(long groupId, long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("message_id", messageId);
        return server.sendApiCall("mark_group_msg_as_read", params);
    }

    /**
     * 标记私聊消息已读。
     * @param userId 用户QQ
     * @param messageId 消息ID
     * @return API响应JSON
     */
    public String markPrivateMsgRead(long userId, long messageId) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("message_id", messageId);
        return server.sendApiCall("mark_private_msg_as_read", params);
    }

    /**
     * 标记所有消息已读。
     * @return API响应JSON
     */
    public String markAllAsRead() {
        return server.sendApiCall("_mark_all_as_read", new HashMap<>());
    }

    // ==================== 个人资料/头像扩展API ====================

    /**
     * 设置 QQ 头像。
     * @param file 图片路径、URL 或 Base64
     * @return API响应JSON
     */
    public String setQqAvatar(String file) {
        Map<String, Object> params = new HashMap<>();
        params.put("file", file);
        return server.sendApiCall("set_qq_avatar", params);
    }

    // ==================== 群信息查询扩展API ====================

    /**
     * 获取群详细信息（get_group_detail_info）。
     * @param groupId 群号
     * @return API响应JSON
     */
    public String getGroupDetailInfo(long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return server.sendApiCall("get_group_detail_info", params);
    }

    /**
     * 获取群详细信息（扩展 get_group_info_ex）。
     * @param groupId 群号
     * @return API响应JSON
     */
    public String getGroupInfoEx(long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return server.sendApiCall("get_group_info_ex", params);
    }

    // ==================== 戳一戳扩展API ====================

    /**
     * 好友戳一戳（friend_poke）。
     * @param userId 用户QQ
     * @param targetId 目标QQ（可选）
     * @return API响应JSON
     */
    public String friendPoke(long userId, Long targetId) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        if (targetId != null) params.put("target_id", targetId);
        return server.sendApiCall("friend_poke", params);
    }

    /**
     * 通用戳一戳（send_poke）。
     * @param userId 用户QQ
     * @param targetId 目标QQ（可选）
     * @param groupId 群号（可选）
     * @return API响应JSON
     */
    public String sendPoke(long userId, Long targetId, Long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        if (targetId != null) params.put("target_id", targetId);
        if (groupId != null) params.put("group_id", groupId);
        return server.sendApiCall("send_poke", params);
    }

    // ==================== 群文件管理API ====================

    /**
     * 获取群根目录文件列表。
     * @param groupId 群号
     * @param fileCount 文件数量
     * @return API响应JSON
     */
    public String getGroupRootFiles(long groupId, int fileCount) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("file_count", fileCount > 0 ? fileCount : 100);
        return server.sendApiCall("get_group_root_files", params);
    }

    /**
     * 删除群文件。
     * @param groupId 群号
     * @param fileId 文件ID
     * @return API响应JSON
     */
    public String deleteGroupFile(long groupId, String fileId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("file_id", fileId);
        return server.sendApiCall("delete_group_file", params);
    }

    /**
     * 创建群文件目录。
     * @param groupId 群号
     * @param folderName 文件夹名称
     * @return API响应JSON
     */
    public String createGroupFileFolder(long groupId, String folderName) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("folder_name", folderName);
        return server.sendApiCall("create_group_file_folder", params);
    }

    /**
     * 删除群文件目录。
     * @param groupId 群号
     * @param folderId 文件夹ID
     * @return API响应JSON
     */
    public String deleteGroupFolder(long groupId, String folderId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("folder_id", folderId);
        return server.sendApiCall("delete_group_folder", params);
    }

    /**
     * 获取群文件系统信息。
     * @param groupId 群号
     * @return API响应JSON
     */
    public String getGroupFileSystemInfo(long groupId) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        return server.sendApiCall("get_group_file_system_info", params);
    }

    /**
     * 获取群文件夹文件列表。
     * @param groupId 群号
     * @param folderId 文件夹ID
     * @param fileCount 文件数量
     * @return API响应JSON
     */
    public String getGroupFilesByFolder(long groupId, String folderId, int fileCount) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        if (folderId != null && !folderId.isEmpty()) params.put("folder_id", folderId);
        params.put("file_count", fileCount > 0 ? fileCount : 100);
        return server.sendApiCall("get_group_files_by_folder", params);
    }

    /**
     * 移动群文件。
     * @param groupId 群号
     * @param fileId 文件ID
     * @param currentParentDirectory 当前父目录
     * @param targetParentDirectory 目标父目录
     * @return API响应JSON
     */
    public String moveGroupFile(long groupId, String fileId, String currentParentDirectory, String targetParentDirectory) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("file_id", fileId);
        params.put("current_parent_directory", currentParentDirectory);
        params.put("target_parent_directory", targetParentDirectory);
        return server.sendApiCall("move_group_file", params);
    }

    /**
     * 重命名群文件。
     * @param groupId 群号
     * @param fileId 文件ID
     * @param currentParentDirectory 当前父目录
     * @param newName 新文件名
     * @return API响应JSON
     */
    public String renameGroupFile(long groupId, String fileId, String currentParentDirectory, String newName) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("file_id", fileId);
        params.put("current_parent_directory", currentParentDirectory);
        params.put("new_name", newName);
        return server.sendApiCall("rename_group_file", params);
    }

    // ==================== 群设置扩展API ====================

    /**
     * 设置群加群选项。
     * @param groupId 群号
     * @param addType 加群方式
     * @param groupQuestion 加群问题
     * @param groupAnswer 加群答案
     * @return API响应JSON
     */
    public String setGroupAddOption(long groupId, int addType, String groupQuestion, String groupAnswer) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("add_type", addType);
        if (groupQuestion != null) params.put("group_question", groupQuestion);
        if (groupAnswer != null) params.put("group_answer", groupAnswer);
        return server.sendApiCall("set_group_add_option", params);
    }

    /**
     * 设置群备注。
     * @param groupId 群号
     * @param remark 备注
     * @return API响应JSON
     */
    public String setGroupRemark(long groupId, String remark) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("remark", remark);
        return server.sendApiCall("set_group_remark", params);
    }

    /**
     * 设置群机器人加群选项。
     * @param groupId 群号
     * @param robotMemberSwitch 机器人成员开关
     * @param robotMemberExamine 机器人成员审核
     * @return API响应JSON
     */
    public String setGroupRobotAddOption(long groupId, Integer robotMemberSwitch, Integer robotMemberExamine) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        if (robotMemberSwitch != null) params.put("robot_member_switch", robotMemberSwitch);
        if (robotMemberExamine != null) params.put("robot_member_examine", robotMemberExamine);
        return server.sendApiCall("set_group_robot_add_option", params);
    }

    /**
     * 设置群搜索选项。
     * @param groupId 群号
     * @param noCodeFingerOpen 参数
     * @param noFingerOpen 参数
     * @return API响应JSON
     */
    public String setGroupSearch(long groupId, Integer noCodeFingerOpen, Integer noFingerOpen) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        if (noCodeFingerOpen != null) params.put("no_code_finger_open", noCodeFingerOpen);
        if (noFingerOpen != null) params.put("no_finger_open", noFingerOpen);
        return server.sendApiCall("set_group_search", params);
    }

    /**
     * 设置群成员邀请策略。
     * @param groupId 群号
     * @param policy 策略：disabled/require_approval/no_approval/no_approval_under_100
     * @return API响应JSON
     */
    public String setGroupMemberInvitePolicy(long groupId, String policy) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("policy", policy);
        return server.sendApiCall("set_group_member_invite_policy", params);
    }

    /**
     * 设置群成员功能权限。
     * @param groupId 群号
     * @param allowMemberUploadAlbum 允许上传群相册
     * @param allowMemberTemporarySession 允许发起临时会话
     * @param allowMemberCreateGroup 允许发起新群聊
     * @return API响应JSON
     */
    public String setGroupMemberPermissions(long groupId, Boolean allowMemberUploadAlbum,
                                            Boolean allowMemberTemporarySession, Boolean allowMemberCreateGroup) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        if (allowMemberUploadAlbum != null) params.put("allow_member_upload_album", allowMemberUploadAlbum);
        if (allowMemberTemporarySession != null) params.put("allow_member_temporary_session", allowMemberTemporarySession);
        if (allowMemberCreateGroup != null) params.put("allow_member_create_group", allowMemberCreateGroup);
        return server.sendApiCall("set_group_member_permissions", params);
    }

    /**
     * 设置新成员历史消息可见性。
     * @param groupId 群号
     * @param visible 是否可见
     * @return API响应JSON
     */
    public String setGroupNewMemberHistoryVisibility(long groupId, boolean visible) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("visible", visible);
        return server.sendApiCall("set_group_new_member_history_visibility", params);
    }

    /**
     * 批量踢出群成员。
     * @param groupId 群号
     * @param userIds QQ号列表
     * @param rejectAddRequest 是否拒绝加群请求
     * @return API响应JSON
     */
    public String setGroupKickMembers(long groupId, List<Object> userIds, Boolean rejectAddRequest) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("user_id", userIds);
        if (rejectAddRequest != null) params.put("reject_add_request", rejectAddRequest);
        return server.sendApiCall("set_group_kick_members", params);
    }

    /**
     * 获取群忽略通知。
     * @return API响应JSON
     */
    public String getGroupIgnoredNotifies() {
        return server.sendApiCall("get_group_ignored_notifies", new HashMap<>());
    }

    /**
     * 获取群被忽略的加群请求。
     * @return API响应JSON
     */
    public String getGroupIgnoreAddRequest() {
        return server.sendApiCall("get_group_ignore_add_request", new HashMap<>());
    }

    // ==================== 系统扩展API ====================

    /**
     * 重启服务（set_restart）。
     * @return API响应JSON
     */
    public String setRestart() {
        return server.sendApiCall("set_restart", new HashMap<>());
    }

    /**
     * 清理缓存（clean_cache）。
     * @return API响应JSON
     */
    public String cleanCache() {
        return server.sendApiCall("clean_cache", new HashMap<>());
    }

    /**
     * 获取 Packet 状态。
     * @return API响应JSON
     */
    public String ncGetPacketStatus() {
        return server.sendApiCall("nc_get_packet_status", new HashMap<>());
    }

    /**
     * 设置输入状态（正在输入等）。
     * @param userId 用户QQ
     * @param eventType 事件类型
     * @return API响应JSON
     */
    public String setInputStatus(long userId, int eventType) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("event_type", eventType);
        return server.sendApiCall("set_input_status", params);
    }

    /**
     * 获取用户在线状态。
     * @param userId 用户QQ
     * @return API响应JSON
     */
    public String ncGetUserStatus(long userId) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        return server.sendApiCall("nc_get_user_status", params);
    }

    /**
     * 获取在线客户端列表。
     * @return API响应JSON
     */
    public String getOnlineClients() {
        return server.sendApiCall("get_online_clients", new HashMap<>());
    }

    /**
     * 检查 URL 安全性。
     * @param url 要检查的 URL
     * @return API响应JSON
     */
    public String checkUrlSafely(String url) {
        Map<String, Object> params = new HashMap<>();
        params.put("url", url);
        return server.sendApiCall("check_url_safely", params);
    }

    /**
     * 英文单词翻译。
     * @param words 待翻译单词列表
     * @return API响应JSON
     */
    public String translateEn2zh(List<String> words) {
        Map<String, Object> params = new HashMap<>();
        params.put("words", words);
        return server.sendApiCall("translate_en2zh", params);
    }

    // ==================== 好友/用户扩展API ====================

    /**
     * 处理可疑好友申请。
     * @param flag 请求flag
     * @param approve 是否同意
     * @return API响应JSON
     */
    public String setDoubtFriendsAddRequest(String flag, boolean approve) {
        Map<String, Object> params = new HashMap<>();
        params.put("flag", flag);
        params.put("approve", approve);
        return server.sendApiCall("set_doubt_friends_add_request", params);
    }

    /**
     * 获取可疑好友申请。
     * @param count 获取数量
     * @return API响应JSON
     */
    public String getDoubtFriendsAddRequest(int count) {
        Map<String, Object> params = new HashMap<>();
        params.put("count", count > 0 ? count : 10);
        return server.sendApiCall("get_doubt_friends_add_request", params);
    }

    /**
     * 获取带分组的好友列表。
     * @return API响应JSON
     */
    public String getFriendsWithCategory() {
        return server.sendApiCall("get_friends_with_category", new HashMap<>());
    }

    /**
     * 获取单向好友列表。
     * @return API响应JSON
     */
    public String getUnidirectionalFriendList() {
        return server.sendApiCall("get_unidirectional_friend_list", new HashMap<>());
    }

    /**
     * 获取资料点赞列表。
     * @param userId 用户QQ（可选）
     * @param start 起始位置
     * @param count 获取数量
     * @return API响应JSON
     */
    public String getProfileLike(Long userId, int start, int count) {
        Map<String, Object> params = new HashMap<>();
        if (userId != null) params.put("user_id", userId);
        params.put("start", start);
        params.put("count", count > 0 ? count : 20);
        return server.sendApiCall("get_profile_like", params);
    }

    /**
     * 设置自定义在线状态。
     * @param faceId 图标ID
     * @param faceType 图标类型
     * @param wording 状态文字
     * @return API响应JSON
     */
    public String setDiyOnlineStatus(int faceId, int faceType, String wording) {
        Map<String, Object> params = new HashMap<>();
        params.put("face_id", faceId);
        params.put("face_type", faceType);
        params.put("wording", wording);
        return server.sendApiCall("set_diy_online_status", params);
    }

    /**
     * 获取机器人 UIN 范围。
     * @return API响应JSON
     */
    public String getRobotUinRange() {
        return server.sendApiCall("get_robot_uin_range", new HashMap<>());
    }

    // ==================== 工具方法 ====================
    
    /**
     * 解析API响应，提取状态码
     * @param response API响应JSON字符串
     * @return 状态码（0表示成功）
     */
    public int parseStatus(String response) {
        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            return json.has("retcode") ? json.get("retcode").getAsInt() : -1;
        } catch (Exception e) {
            return -1;
        }
    }
    
    /**
     * 解析API响应，提取数据部分
     * @param response API响应JSON字符串
     * @return 数据部分的JsonObject，失败返回null
     */
    public JsonObject parseData(String response) {
        try {
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            return json.has("data") ? json.getAsJsonObject("data") : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 检查API调用是否成功
     * @param response sendApiCall返回的API响应JSON（status+retcode+data+echo）
     * @return 非空即表示请求已发送并收到响应
     */
    public boolean isSuccess(String response) {
        // sendApiCall返回的是API响应JSON（如{"status":"ok","retcode":0,...}）
        // 非空即表示请求已成功发送并收到响应
        return response != null && !response.isEmpty();
    }
    
    /**
     * 转义CQ码参数中的特殊字符
     * 将 & [ ] , 替换为 &amp; &#91; &#93; &#44;
     */
    public static String escapeCQ(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("[", "&#91;")
                .replace("]", "&#93;")
                .replace(",", "&#44;");
    }
}
