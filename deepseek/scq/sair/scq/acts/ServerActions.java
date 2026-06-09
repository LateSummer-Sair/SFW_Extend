package sair.scq.acts;

import java.util.List;

import sair.scq.model.GroupInfo;
import sair.scq.model.UserInfo;
import sair.scq.server.SCServerCore;
import sair.scq.ui.ServerUI;
import sair.sys.SairCons;

/**
 * 服务端命令动作层 —— 处理所有服务端控制台命令。
 */
public class ServerActions {

    private SCServerCore serverCore;
    private ServerUI serverUI;

    public ServerActions(SCServerCore serverCore) {
        this.serverCore = serverCore;
    }

    public void setServerUI(ServerUI serverUI) {
        this.serverUI = serverUI;
    }

    public SCServerCore getServerCore() {
        return serverCore;
    }

    // ==================== 命令处理 ====================

    /** 启动服务端 */
    public Object start(String args) {
        if (serverCore.isRunning()) {
            SairCons.println("服务端已在运行中，端口: " + serverCore.getPort());
            return true;
        }

        int port = 9000;
        if (args != null && !args.isEmpty()) {
            try { port = Integer.parseInt(args.trim()); }
            catch (NumberFormatException e) {
                SairCons.println("无效的端口号: " + args);
                return false;
            }
        }

        boolean result = serverCore.start(port);
        if (result) {
            SairCons.println("SCQ服务端启动成功，端口: " + port);
        }
        return result;
    }

    /** 停止服务端 */
    public Object stop() {
        if (!serverCore.isRunning()) {
            SairCons.println("服务端未运行");
            return false;
        }
        serverCore.stop();
        SairCons.println("SCQ服务端已停止");
        return true;
    }

    /** 查看服务端状态 */
    public Object status() {
        if (serverCore.isRunning()) {
            SairCons.println("SCQ服务端运行中");
            SairCons.println("监听端口: " + serverCore.getPort());
            SairCons.println("在线用户数: " + serverCore.getOnlineCount());
            SairCons.println("注册用户数: " + serverCore.getUserManager().getAllUsers().size());
        } else {
            SairCons.println("SCQ服务端未运行");
        }
        return true;
    }

    /** 列出在线用户 */
    public Object listUsers() {
        List<UserInfo> onlineUsers = serverCore.getUserManager().getOnlineUsers(serverCore.getOnlineUIDs());
        if (onlineUsers.isEmpty()) {
            SairCons.println("当前没有在线用户");
            return true;
        }

        SairCons.println("=== 在线用户 ===");
        for (UserInfo u : onlineUsers) {
            SairCons.println("UID:" + u.getUid() + " 用户名:" + u.getUsername()
                + " 角色:" + u.getRole().name() + " IP:" + u.getIp()
                + " 签名:" + u.getSignature());
        }
        SairCons.println("共 " + onlineUsers.size() + " 人在线");
        return true;
    }

    /** 列出所有注册用户 */
    public Object listAllUsers() {
        List<UserInfo> allUsers = serverCore.getUserManager().getAllUsers();
        SairCons.println("=== 所有注册用户 ===");
        for (UserInfo u : allUsers) {
            SairCons.println("UID:" + u.getUid() + " 用户名:" + u.getUsername()
                + " 角色:" + u.getRole().name() + " 在线:" + serverCore.isUserOnline(u.getUid()));
        }
        SairCons.println("共 " + allUsers.size() + " 个注册用户");
        return true;
    }

    /** 列出所有群组 */
    public Object listGroups() {
        List<GroupInfo> groups = serverCore.getGroupManager().getAllGroups();
        if (groups.isEmpty()) {
            SairCons.println("当前没有群组");
            return true;
        }

        SairCons.println("=== 所有群组 ===");
        for (GroupInfo g : groups) {
            SairCons.println("GID:" + g.getGid() + " 名称:" + g.getGroupName()
                + " 创建者:" + g.getCreatorUID() + " 成员数:" + g.getMemberUIDs().size()
                + " 管理员数:" + g.getAdminUIDs().size());
        }
        return true;
    }

    /** 显示服务端管理面板 */
    public Object showUI() {
        if (serverUI != null) {
            serverUI.show();
        }
        return true;
    }

    /** 设置用户角色 */
    public Object setRole(String args) {
        if (args == null || args.isEmpty()) {
            SairCons.println("用法: setRole <UID> <SUPER_ADMIN|ADMIN|MEMBER>");
            return false;
        }
        String[] parts = args.split(" ");
        if (parts.length < 2) {
            SairCons.println("用法: setRole <UID> <SUPER_ADMIN|ADMIN|MEMBER>");
            return false;
        }
        try {
            long uid = Long.parseLong(parts[0]);
            UserInfo.Role role = UserInfo.Role.fromString(parts[1]);
            boolean result = serverCore.getUserManager().setRole(uid, role);
            SairCons.println(result ? "角色设置成功" : "角色设置失败");
            return result;
        } catch (Exception e) {
            SairCons.println("参数格式错误");
            return false;
        }
    }

    /** 退出时清理 */
    public void exit() {
        if (serverCore.isRunning()) {
            serverCore.stop();
        }
    }
}
