package sair.aiagent.onebot;

import java.util.HashMap;
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
     * @param filePath 文件本地绝对路径
     * @param fileName 文件显示名称
     * @return API响应JSON
     */
    public String sendGroupFile(long groupId, String filePath, String fileName) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("file", filePath);
        params.put("name", fileName != null ? fileName : new java.io.File(filePath).getName());
        return server.sendApiCall("upload_group_file", params);
    }
    
    /**
     * 发送私聊文件
     * @param userId QQ号
     * @param filePath 文件本地绝对路径
     * @param fileName 文件显示名称
     * @return API响应JSON
     */
    public String sendPrivateFile(long userId, String filePath, String fileName) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("file", filePath);
        params.put("name", fileName != null ? fileName : new java.io.File(filePath).getName());
        return server.sendApiCall("upload_private_file", params);
    }
    
    // ==================== 消息查询API ====================
    
    /**
     * 获取消息详情（用于引用回复分析）
     * @param messageId 消息ID
     * @return API响应JSON
     */
    public String getMessage(int messageId) {
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
    private static String escapeCQ(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("[", "&#91;")
                .replace("]", "&#93;")
                .replace(",", "&#44;");
    }
}
