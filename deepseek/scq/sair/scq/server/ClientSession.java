package sair.scq.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import sair.scq.model.GroupInfo;
import sair.scq.model.UserInfo;
import sair.scq.protocol.MessageType;
import sair.scq.protocol.SCMessage;
import sair.sys.SairCons;

/**
 * 客户端会话 —— 管理单个客户端连接的全生命周期。
 * 
 * <h3>职责</h3>
 * <ul>
 *   <li>持有Socket连接和输入输出流</li>
 *   <li>持续读取消息循环，解析SCMessage</li>
 *   <li>根据消息类型路由到对应的处理方法</li>
 *   <li>维护客户端关联的UserInfo引用</li>
 *   <li>断线时自动清理在线状态</li>
 * </ul>
 * 
 * <h3>协议格式</h3>
 * 每条消息：[4字节长度(int)][JSON字符串UTF-8]
 */
public class ClientSession implements Runnable {

    private final SCServerCore serverCore;
    private final Socket socket;
    private DataInputStream dis;
    private DataOutputStream dos;
    private volatile boolean running = true;

    /** 关联的用户（登录后绑定） */
    private UserInfo user;

    public ClientSession(SCServerCore serverCore, Socket socket) {
        this.serverCore = serverCore;
        this.socket = socket;
    }

    public UserInfo getUser() { return user; }
    public Socket getSocket() { return socket; }

    // ==================== 消息循环 ====================

    @Override
    public void run() {
        try {
            dis = new DataInputStream(socket.getInputStream());
            dos = new DataOutputStream(socket.getOutputStream());

            while (running && !socket.isClosed()) {
                String json = readMessage();
                if (json == null) break;

                SCMessage msg = SCMessage.fromJson(json);
                if (msg == null || msg.getType() == null) continue;

                handleMessage(msg);
            }
        } catch (IOException e) {
            // 客户端断线
        } finally {
            disconnect();
        }
    }

    /** 读取一条消息：4字节长度 + UTF-8 JSON字符串 */
    private String readMessage() throws IOException {
        try {
            int length = dis.readInt();
            if (length <= 0 || length > 1048576) return null; // 最大1MB

            byte[] bytes = new byte[length];
            dis.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    // ==================== 消息处理路由 ====================

    private void handleMessage(SCMessage msg) {
        MessageType type = msg.getType();

        switch (type) {
            case REGISTER:
                handleRegister(msg);
                break;
            case LOGIN:
                handleLogin(msg);
                break;
            case LOGOUT:
                handleLogout(msg);
                break;
            case USER_LIST:
                handleUserList(msg);
                break;
            case USER_UPDATE:
                handleUserUpdate(msg);
                break;
            case PRIVATE_MSG:
                handlePrivateMsg(msg);
                break;
            case GROUP_MSG:
                handleGroupMsg(msg);
                break;
            case GROUP_CREATE:
                handleGroupCreate(msg);
                break;
            case GROUP_JOIN:
                handleGroupJoin(msg);
                break;
            case GROUP_LEAVE:
                handleGroupLeave(msg);
                break;
            case JOIN_REQUEST:
                handleJoinRequest(msg);
                break;
            case APPROVE_JOIN:
                handleApproveJoin(msg);
                break;
            case REJECT_JOIN:
                handleRejectJoin(msg);
                break;
            case GROUP_KICK:
                handleGroupKick(msg);
                break;
            case GROUP_MUTE:
                handleGroupMute(msg);
                break;
            case GROUP_UNMUTE:
                handleGroupUnmute(msg);
                break;
            case GROUP_SET_ADMIN:
                handleGroupSetAdmin(msg);
                break;
            case GROUP_LIST:
                handleGroupList(msg);
                break;
            case GROUP_INFO:
                handleGroupInfo(msg);
                break;
            case COMMAND:
                handleCommand(msg);
                break;
            case FILE_TRANSFER:
                handleFileTransfer(msg);
                break;
            case CONTACT_ADD:
                handleContactAdd(msg);
                break;
            case CONTACT_LIST:
                handleContactList(msg);
                break;
            default:
                sendMessage(SCMessage.error("未知消息类型: " + type));
        }
    }

    // ==================== 具体消息处理 ====================

    private void handleRegister(SCMessage msg) {
        // data格式: "username" extraData: password
        String username = msg.getData();
        String password = msg.getExtraData();
        if (username == null || password == null) {
            sendMessage(SCMessage.error("用户名和密码不能为空"));
            return;
        }

        UserInfo newUser = serverCore.getUserManager().register(username, password, 0, 0);
        if (newUser == null) {
            sendMessage(SCMessage.error("注册失败，用户名可能已存在"));
            return;
        }
        user = newUser;
        // 注册即自动登录：注册sessionMap条目（参照nsc：在线=sessionMap中有活跃socket）
        serverCore.kickOldSession(newUser.getUid(), this);
        serverCore.registerSession(newUser.getUid(), this);

        SCMessage resp = SCMessage.ok("注册成功");
        resp.setExtraData(newUser.toJson());
        sendMessage(resp);

        SairCons.println("用户注册并登录: " + username + " [UID:" + newUser.getUid() + "]");
        broadcastStatus(newUser.getUid(), true);
    }

    private void handleLogin(SCMessage msg) {
        String username = msg.getData();
        String password = msg.getExtraData();
        if (username == null || password == null) {
            sendMessage(SCMessage.error("用户名和密码不能为空"));
            return;
        }

        String ip = socket.getInetAddress().getHostAddress();
        // 参照nsc：登录只验证凭据，在线状态由registerSession（=socket在sessionMap中）决定
        UserInfo loginUser = serverCore.getUserManager().login(username, password, ip);
        if (loginUser == null) {
            sendMessage(SCMessage.error("登录失败，用户名或密码错误"));
            return;
        }

        // 如果已有旧连接，踢掉旧连接（参照nsc的setLinkedClientClose）
        serverCore.kickOldSession(loginUser.getUid(), this);

        user = loginUser;
        serverCore.registerSession(loginUser.getUid(), this);

        SCMessage resp = SCMessage.ok("登录成功");
        resp.setExtraData(loginUser.toJson());
        sendMessage(resp);

        SairCons.println("用户登录: " + username + " [UID:" + loginUser.getUid() + " IP:" + ip + "]");

        // 通知其他在线用户状态变更
        broadcastStatus(loginUser.getUid(), true);
    }

    private void handleLogout(SCMessage msg) {
        if (user != null) {
            broadcastStatus(user.getUid(), false);
            SairCons.println("用户登出: " + user.getUsername() + " [UID:" + user.getUid() + "]");
        }
        sendMessage(SCMessage.ok("已登出"));
        // disconnect() 内部会调用 unregisterSession + logout(清IP)，这里不用重复调用
        disconnect();
    }

    private void handleUserList(SCMessage msg) {
        java.util.Collection<Long> onlineUIDs = serverCore.getOnlineUIDs();
        List<UserInfo> onlineUsers = serverCore.getUserManager().getOnlineUsers(onlineUIDs);
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (UserInfo u : onlineUsers) {
            if (!first) sb.append(",");
            // 发送不含密码哈希的公开信息（在线状态通过serverCore.isUserOnline实时获取）
            sb.append(publicUserJson(u, true));
            first = false;
        }
        sb.append("]");

        SCMessage resp = new SCMessage(MessageType.USER_LIST);
        resp.setData(sb.toString());
        sendMessage(resp);
    }

    private void handleUserUpdate(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        // data: 新的签名 extraData: {"avatarPath":"...","newPassword":"..."}
        String newSignature = msg.getData();
        String extraInfo = msg.getExtraData();

        String newAvatar = null;
        String newPassword = null;

        // 简单解析extraData中的JSON
        if (extraInfo != null && extraInfo.startsWith("{")) {
            newAvatar = extractJsonStr(extraInfo, "\"avatarPath\":\"");
            newPassword = extractJsonStr(extraInfo, "\"newPassword\":\"");
        }

        serverCore.getUserManager().updateProfile(user.getUid(), newSignature, newAvatar, newPassword);
        sendMessage(SCMessage.ok("信息更新成功"));
    }

    private void handlePrivateMsg(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long toUID = msg.getToUID();
        msg.setFromUID(user.getUid());

        if (serverCore.isUserOnline(toUID)) {
            serverCore.getSession(toUID).sendMessage(msg);
        } else {
            sendMessage(SCMessage.error("对方不在线"));
        }
    }

    private void handleGroupMsg(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid = msg.getToUID();
        GroupInfo group = serverCore.getGroupManager().getGroup(gid);
        if (group == null) { sendMessage(SCMessage.error("群组不存在")); return; }
        if (!group.isMember(user.getUid())) { sendMessage(SCMessage.error("你不是该群组成员")); return; }
        if (group.isMuted(user.getUid())) { sendMessage(SCMessage.error("你已被禁言")); return; }

        msg.setFromUID(user.getUid());

        // 转发给群组所有在线成员
        for (Long memberUID : group.getMemberUIDs()) {
            if (memberUID == user.getUid()) continue; // 不发给自己
            ClientSession memberSession = serverCore.getSession(memberUID);
            if (memberSession != null && serverCore.isSessionActive(memberSession)) {
                memberSession.sendMessage(msg);
            }
        }
    }

    private void handleGroupCreate(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        String groupName = msg.getData();
        GroupInfo group = serverCore.getGroupManager().createGroup(user.getUid(), groupName);
        if (group == null) {
            sendMessage(SCMessage.error("创建群组失败，请确认有超级管理员权限"));
            return;
        }

        SCMessage resp = SCMessage.ok("群组创建成功");
        resp.setExtraData(group.toJson());
        sendMessage(resp);
    }

    private void handleGroupJoin(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid;
        try { gid = Long.parseLong(msg.getData()); } catch (Exception e) {
            sendMessage(SCMessage.error("无效的群组ID")); return;
        }

        boolean result = serverCore.getGroupManager().requestJoin(user.getUid(), gid);
        if (result) {
            sendMessage(SCMessage.ok("已发送入群申请，等待管理员审核"));

            // 通知群组管理员有新申请
            GroupInfo group = serverCore.getGroupManager().getGroup(gid);
            if (group != null) {
                for (Long adminUID : group.getAdminUIDs()) {
                    ClientSession adminSession = serverCore.getSession(adminUID);
                    if (adminSession != null && serverCore.isSessionActive(adminSession)) {
                        SCMessage notify = new SCMessage(MessageType.OK);
                        notify.setData("新入群申请: 用户 " + user.getUsername() + " [UID:" + user.getUid() + "] 申请加入群组 " + group.getGroupName());
                        notify.setExtraData("{\"action\":\"join_request\",\"uid\":" + user.getUid() + ",\"gid\":" + gid + "}");
                        adminSession.sendMessage(notify);
                    }
                }
            }
        } else {
            sendMessage(SCMessage.error("申请失败，可能已在群中或已申请过"));
        }
    }

    private void handleGroupLeave(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid;
        try { gid = Long.parseLong(msg.getData()); } catch (Exception e) {
            sendMessage(SCMessage.error("无效的群组ID")); return;
        }

        boolean result = serverCore.getGroupManager().leaveGroup(user.getUid(), gid);
        sendMessage(result ? SCMessage.ok("已退出群组") : SCMessage.error("退出群组失败"));
    }

    private void handleJoinRequest(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid;
        try { gid = Long.parseLong(msg.getData()); } catch (Exception e) {
            sendMessage(SCMessage.error("无效的群组ID")); return;
        }

        GroupInfo group = serverCore.getGroupManager().getGroup(gid);
        if (group == null) { sendMessage(SCMessage.error("群组不存在")); return; }
        if (!group.isMember(user.getUid())) { sendMessage(SCMessage.error("你不是该群组成员")); return; }
        if (!group.isAdmin(user.getUid())) { sendMessage(SCMessage.error("你不是该群组管理员")); return; }

        List<Long> pendingRequests = serverCore.getGroupManager().getPendingRequests(gid);
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (Long uid : pendingRequests) {
            UserInfo userInfo = serverCore.getUserManager().getUser(uid);
            if (userInfo != null) {
                if (!first) sb.append(",");
                sb.append(publicUserJson(userInfo, serverCore.isUserOnline(uid)));
                first = false;
            }
        }
        sb.append("]");

        SCMessage resp = new SCMessage(MessageType.OK);
        resp.setData(sb.toString());
        sendMessage(resp);
    }

    private void handleApproveJoin(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid, targetUID;
        try {
            gid = msg.getToUID();
            targetUID = Long.parseLong(msg.getExtraData());
        } catch (Exception e) {
            sendMessage(SCMessage.error("参数错误")); return;
        }

        boolean result = serverCore.getGroupManager().approveJoin(user.getUid(), targetUID, gid);
        if (result) {
            sendMessage(SCMessage.ok("已批准入群申请"));

            // 通知被批准的成员
            if (serverCore.isUserOnline(targetUID)) {
                ClientSession targetSession = serverCore.getSession(targetUID);
                if (targetSession != null) {
                    SCMessage notify = new SCMessage(MessageType.OK);
                    notify.setData("你的入群申请已被批准");
                    targetSession.sendMessage(notify);
                }
            }
        } else {
            sendMessage(SCMessage.error("批准失败，权限不足或申请不存在"));
        }
    }

    private void handleRejectJoin(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid, targetUID;
        try {
            gid = msg.getToUID();
            targetUID = Long.parseLong(msg.getExtraData());
        } catch (Exception e) {
            sendMessage(SCMessage.error("参数错误")); return;
        }

        boolean result = serverCore.getGroupManager().rejectJoin(user.getUid(), targetUID, gid);
        if (result) {
            sendMessage(SCMessage.ok("已拒绝入群申请"));

            // 通知被拒绝的成员
            if (serverCore.isUserOnline(targetUID)) {
                ClientSession targetSession = serverCore.getSession(targetUID);
                if (targetSession != null) {
                    SCMessage notify = new SCMessage(MessageType.OK);
                    notify.setData("你的入群申请已被拒绝");
                    targetSession.sendMessage(notify);
                }
            }
        } else {
            sendMessage(SCMessage.error("拒绝失败，权限不足或申请不存在"));
        }
    }

    private void handleGroupKick(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        // data: gid extraData: targetUID
        long gid, targetUID;
        try {
            gid = msg.getToUID();
            targetUID = Long.parseLong(msg.getExtraData());
        } catch (Exception e) {
            sendMessage(SCMessage.error("参数错误")); return;
        }

        boolean result = serverCore.getGroupManager().kickMember(user.getUid(), targetUID, gid);
        if (result) {
            sendMessage(SCMessage.ok("已踢出成员"));
            // 通知被踢的成员
            if (serverCore.isUserOnline(targetUID)) {
                ClientSession targetSession = serverCore.getSession(targetUID);
                if (targetSession != null) {
                    SCMessage notify = new SCMessage(MessageType.OK);
                    notify.setData("你已被移出群组 [GID:" + gid + "]");
                    targetSession.sendMessage(notify);
                }
            }
        } else {
            sendMessage(SCMessage.error("踢出失败，权限不足或成员不存在"));
        }
    }

    private void handleGroupMute(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid = msg.getToUID();
        long targetUID;
        try { targetUID = Long.parseLong(msg.getExtraData()); } catch (Exception e) {
            sendMessage(SCMessage.error("参数错误")); return;
        }

        boolean result = serverCore.getGroupManager().muteMember(user.getUid(), targetUID, gid);
        sendMessage(result ? SCMessage.ok("已禁言") : SCMessage.error("禁言失败"));
    }

    private void handleGroupUnmute(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid = msg.getToUID();
        long targetUID;
        try { targetUID = Long.parseLong(msg.getExtraData()); } catch (Exception e) {
            sendMessage(SCMessage.error("参数错误")); return;
        }

        boolean result = serverCore.getGroupManager().unmuteMember(user.getUid(), targetUID, gid);
        sendMessage(result ? SCMessage.ok("已取消禁言") : SCMessage.error("取消禁言失败"));
    }

    private void handleGroupSetAdmin(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long gid = msg.getToUID();
        long targetUID;
        try { targetUID = Long.parseLong(msg.getExtraData()); } catch (Exception e) {
            sendMessage(SCMessage.error("参数错误")); return;
        }

        boolean result = serverCore.getGroupManager().setAdmin(user.getUid(), targetUID, gid);
        sendMessage(result ? SCMessage.ok("已设置为管理员") : SCMessage.error("设置管理员失败"));
    }

    private void handleGroupList(SCMessage msg) {
        List<GroupInfo> groups = serverCore.getGroupManager().getAllGroups();
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        boolean first = true;
        for (GroupInfo g : groups) {
            if (!first) sb.append(",");
            sb.append(groupBriefJson(g));
            first = false;
        }
        sb.append("]");

        SCMessage resp = new SCMessage(MessageType.GROUP_LIST);
        resp.setData(sb.toString());
        sendMessage(resp);
    }

    private void handleGroupInfo(SCMessage msg) {
        long gid;
        try { gid = Long.parseLong(msg.getData()); } catch (Exception e) {
            sendMessage(SCMessage.error("无效的群组ID")); return;
        }
        GroupInfo group = serverCore.getGroupManager().getGroup(gid);
        if (group == null) { sendMessage(SCMessage.error("群组不存在")); return; }

        SCMessage resp = new SCMessage(MessageType.GROUP_INFO);
        resp.setData(group.toJson());
        sendMessage(resp);
    }

    private void handleCommand(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long toUID = msg.getToUID();
        msg.setFromUID(user.getUid());

        if (serverCore.isUserOnline(toUID) && serverCore.getSession(toUID).getUser() != null) {
            serverCore.getSession(toUID).sendMessage(msg);
        } else {
            sendMessage(SCMessage.error("目标用户不在线"));
        }
    }

    private void handleFileTransfer(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        long toUID = msg.getToUID();
        msg.setFromUID(user.getUid());
        // data: 文件信息（发送方IP+端口+文件名）
        // extraData: 文件大小等

        if (serverCore.isUserOnline(toUID) && serverCore.getSession(toUID).getUser() != null) {
            serverCore.getSession(toUID).sendMessage(msg);
        } else {
            sendMessage(SCMessage.error("目标用户不在线"));
        }
    }

    private void handleContactAdd(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        // data: 对方的UID
        long contactUID;
        try { contactUID = Long.parseLong(msg.getData()); } catch (Exception e) {
            sendMessage(SCMessage.error("无效的UID")); return;
        }

        if (contactUID == user.getUid()) {
            sendMessage(SCMessage.error("不能添加自己为好友")); return;
        }

        // 检查是否已经是好友
        UserInfo currentUserInfo = serverCore.getUserManager().getUser(user.getUid());
        if (currentUserInfo != null && currentUserInfo.getContacts().contains(contactUID)) {
            sendMessage(SCMessage.error("对方已经是你的好友")); return;
        }

        // 检查对方是否存在
        UserInfo contactUser = serverCore.getUserManager().getUser(contactUID);
        if (contactUser == null) {
            sendMessage(SCMessage.error("用户不存在")); return;
        }

        // 双向添加好友
        boolean result = serverCore.getUserManager().addContact(user.getUid(), contactUID);
        if (!result) { sendMessage(SCMessage.error("添加好友失败")); return; }
        serverCore.getUserManager().addContact(contactUID, user.getUid());

        // 通知请求方
        SCMessage resp = SCMessage.ok("已添加好友: " + contactUser.getUsername() + " [UID:" + contactUID + "]");
        resp.setExtraData("{\"uid\":" + contactUID + ",\"username\":\"" + esc(contactUser.getUsername()) + "\"}");
        sendMessage(resp);
        // 立即推送更新后的通讯录给请求方
        handleContactList(null);

        // 通知被添加方
        if (serverCore.isUserOnline(contactUID)) {
            ClientSession targetSession = serverCore.getSession(contactUID);
            if (targetSession != null) {
                SCMessage notify = new SCMessage(MessageType.CONTACT_ADDED);
                notify.setFromUID(user.getUid());
                notify.setData(user.getUsername());
                targetSession.sendMessage(notify);
            }
        }

        SairCons.println("好友添加: " + user.getUsername() + " [UID:" + user.getUid() + "] <-> " + contactUser.getUsername() + " [UID:" + contactUID + "]");
    }

    private void handleContactList(SCMessage msg) {
        if (user == null) { sendMessage(SCMessage.error("请先登录")); return; }

        // 构建通讯录：好友信息 + 群组信息
        StringBuilder sb = new StringBuilder();
        sb.append("{\"contacts\":[");
        boolean first = true;
        UserInfo currentUser = serverCore.getUserManager().getUser(user.getUid());
        if (currentUser != null) {
            for (Long contactUID : currentUser.getContacts()) {
                UserInfo contact = serverCore.getUserManager().getUser(contactUID);
                if (contact != null) {
                    if (!first) sb.append(",");
                    sb.append(publicUserJson(contact, serverCore.isUserOnline(contactUID)));
                    first = false;
                }
            }
        }
        sb.append("],\"groups\":[");
        first = true;
        if (currentUser != null) {
            for (Long gid : currentUser.getGroups()) {
                GroupInfo group = serverCore.getGroupManager().getGroup(gid);
                if (group != null) {
                    if (!first) sb.append(",");
                    sb.append(groupBriefJson(group));
                    first = false;
                }
            }
        }
        sb.append("]}");

        SCMessage resp = new SCMessage(MessageType.CONTACT_LIST);
        resp.setData(sb.toString());
        sendMessage(resp);
    }

    // ==================== 辅助方法 ====================

    /** 发送消息 */
    public synchronized void sendMessage(SCMessage msg) {
        if (socket.isClosed() || dos == null) return;
        try {
            byte[] jsonBytes = msg.toJson().getBytes(StandardCharsets.UTF_8);
            dos.writeInt(jsonBytes.length);
            dos.write(jsonBytes);
            dos.flush();
        } catch (IOException e) {
            disconnect();
        }
    }

    /**
     * 断开连接（参照nsc的setLinkedClientClose：关闭socket并清理会话）。
     * 在nsc中，当SingleServer的while循环因IOException退出时，
     * 自动调用sm.setLinkedClientClose(sid)清理socketList中的槽位。
     */
    public void disconnect() {
        running = false;
        if (user != null) {
            long uid = user.getUid();
            serverCore.unregisterSession(uid);
            // 仅清空IP，不设置isOnline标志（参照nsc：在线=sessionMap中有活跃socket）
            serverCore.getUserManager().logout(uid);
            broadcastStatus(uid, false);
            String username = user.getUsername();
            user = null;
            SairCons.println("用户断线: " + username + " [UID:" + uid + "]");
        }
        try { if (dos != null) dos.close(); } catch (Exception e) {}
        try { if (dis != null) dis.close(); } catch (Exception e) {}
        try { socket.close(); } catch (Exception e) {}
    }

    /** 广播状态变更 */
    private void broadcastStatus(long uid, boolean online) {
        SCMessage statusMsg = new SCMessage(MessageType.STATUS);
        statusMsg.setFromUID(uid);
        statusMsg.setData(online ? "online" : "offline");

        for (ClientSession session : serverCore.getAllSessions()) {
            if (session != this && serverCore.isSessionActive(session) && session.getUser() != null) {
                session.sendMessage(statusMsg);
            }
        }
    }

    /** 生成不含敏感信息的公开用户JSON（参照nsc：isOnline通过socket状态确定） */
    private static String publicUserJson(UserInfo u, boolean isOnline) {
        return "{\"uid\":" + u.getUid()
            + ",\"username\":\"" + esc(u.getUsername()) + "\""
            + ",\"role\":\"" + u.getRole().name() + "\""
            + ",\"signature\":\"" + esc(u.getSignature()) + "\""
            + ",\"avatarPath\":\"" + esc(u.getAvatarPath()) + "\""
            + ",\"ip\":\"" + esc(u.getIp()) + "\""
            + ",\"isOnline\":" + isOnline
            + "}";
    }

    /** 生成群组简要信息JSON */
    private static String groupBriefJson(GroupInfo g) {
        return "{\"gid\":" + g.getGid()
            + ",\"groupName\":\"" + esc(g.getGroupName()) + "\""
            + ",\"creatorUID\":" + g.getCreatorUID()
            + ",\"memberCount\":" + g.getMemberUIDs().size()
            + "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 从JSON字符串中提取字符串值 */
    private static String extractJsonStr(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) { sb.append(c); esc = false; }
            else if (c == '\\') esc = true;
            else if (c == '"') return sb.toString();
            else sb.append(c);
        }
        return sb.toString();
    }
}
