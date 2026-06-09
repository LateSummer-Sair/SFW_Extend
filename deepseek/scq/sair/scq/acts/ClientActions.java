package sair.scq.acts;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import sair.scq.client.SCClientCore;
import sair.scq.model.UserInfo;
import sair.scq.ui.ChatPanel;
import sair.scq.ui.ContactPanel;
import sair.scq.ui.InputPanel;
import sair.scq.ui.LoginPanel;
import sair.scq.ui.SCClientUI;
import sair.scq.ui.UserInfoPanel;
import sair.sys.SairCons;

/**
 * 客户端命令动作层 —— 处理所有客户端控制台命令和UI交互。
 */
public class ClientActions {

    private SCClientCore clientCore;
    private SCClientUI clientUI;
    private ChatPanel chatPanel;
    private ContactPanel contactPanel;
    private InputPanel inputPanel;
    private LoginPanel loginPanel;
    private UserInfoPanel userInfoPanel;

    /** 当前聊天对象（0=未选择） */
    private long currentChatUID = 0;
    /** 当前聊天是否群聊 */
    private boolean currentChatIsGroup = false;
    /** 是否自动执行命令 */
    private boolean autoExecute = false;

    public ClientActions(SCClientCore clientCore) {
        this.clientCore = clientCore;
    }

    public void setClientUI(SCClientUI ui) { this.clientUI = ui; }
    public void setChatPanel(ChatPanel cp) { this.chatPanel = cp; }
    public void setContactPanel(ContactPanel cp) { this.contactPanel = cp; }
    public void setInputPanel(InputPanel ip) { this.inputPanel = ip; }
    public void setLoginPanel(LoginPanel lp) { this.loginPanel = lp; }
    public void setUserInfoPanel(UserInfoPanel uip) { this.userInfoPanel = uip; }

    public SCClientCore getClientCore() { return clientCore; }
    public boolean isAutoExecute() { return autoExecute; }

    // ==================== 控制台命令 ====================

    /** 连接服务端 */
    public Object connect(String args) {
        if (clientCore.isConnected()) {
            SairCons.println("已连接到服务端");
            return true;
        }

        String host = "127.0.0.1";
        int port = 9000;

        if (args != null && !args.isEmpty()) {
            String[] parts = args.trim().split("\\s+");
            if (parts.length >= 1) host = parts[0];
            if (parts.length >= 2) {
                try { port = Integer.parseInt(parts[1]); }
                catch (NumberFormatException e) {
                    SairCons.println("无效的端口号");
                    return false;
                }
            }
        }

        boolean result = clientCore.connect(host, port);
        if (result) {
            SairCons.println("已连接到 " + host + ":" + port);
            clientUI.setStatusText("已连接 " + host + ":" + port);
            clientUI.showLoginPanel();
        } else {
            SairCons.println("连接失败");
        }
        return result;
    }

    /** 断开连接 */
    public Object disconnect() {
        clientCore.disconnect();
        clientUI.setStatusText("未连接");
        currentChatUID = 0;
        return true;
    }

    /** 登录 */
    public Object login(String args) {
        if (!clientCore.isConnected()) {
            SairCons.println("请先连接服务端");
            return false;
        }

        String username, password;
        if (args != null && args.contains(" ")) {
            String[] parts = args.split("\\s+", 2);
            username = parts[0];
            password = parts[1];
        } else {
            SairCons.println("用法: login <用户名> <密码>");
            return false;
        }

        clientCore.login(username, password);
        return true;
    }

    /** 注册 */
    public Object register(String args) {
        if (!clientCore.isConnected()) {
            SairCons.println("请先连接服务端");
            return false;
        }

        String username, password;
        if (args != null && args.contains(" ")) {
            String[] parts = args.split("\\s+", 2);
            username = parts[0];
            password = parts[1];
        } else {
            SairCons.println("用法: register <用户名> <密码>");
            return false;
        }

        clientCore.register(username, password);
        return true;
    }

    /** 刷新用户列表 */
    public Object refreshUsers() {
        if (!clientCore.isConnected()) return false;
        clientCore.requestUserList();
        clientCore.requestContactList();
        return true;
    }

    /** 添加好友 */
    public Object addContact(String args) {
        if (!clientCore.isConnected()) return false;
        try {
            long uid = Long.parseLong(args != null ? args.trim() : "0");
            if (uid == 0) return false;
            clientCore.addContact(uid);
            return true;
        } catch (NumberFormatException e) {
            SairCons.println("无效的UID");
            return false;
        }
    }

    /** 创建群组 */
    public Object createGroup(String args) {
        if (!clientCore.isConnected()) return false;
        if (args == null || args.isEmpty()) {
            SairCons.println("用法: createGroup <群组名称>");
            return false;
        }
        clientCore.createGroup(args.trim());
        return true;
    }

    /** 加入群组 */
    public Object joinGroup(String args) {
        if (!clientCore.isConnected()) return false;
        try {
            long gid = Long.parseLong(args != null ? args.trim() : "0");
            if (gid == 0) return false;
            clientCore.joinGroup(gid);
            return true;
        } catch (NumberFormatException e) {
            SairCons.println("无效的群组ID");
            return false;
        }
    }

    /** 显示主UI */
    public Object showUI() {
        if (clientUI != null) {
            clientUI.showMainUI();
        }
        return true;
    }

    /** 设置自动执行模式 */
    public Object setAutoExecute(String args) {
        this.autoExecute = "true".equalsIgnoreCase(args);
        SairCons.println("自动执行模式: " + (autoExecute ? "开启" : "关闭"));
        return true;
    }

    // ==================== UI回调处理 ====================

    /** 处理UI层的登录请求 */
    public void handleUILogin(String username, String password) {
        clientCore.login(username, password);
    }

    /** 处理UI层的注册请求 */
    public void handleUIRegister(String username, String password) {
        clientCore.register(username, password);
    }

    /** 处理UI层的消息发送 */
    public void handleUISendMessage(String text) {
        if (currentChatUID == 0) {
            chatPanel.addSystemMessage("请先选择聊天对象");
            return;
        }

        // 本地回显：发送者需要看到自己发的消息
        UserInfo myUser = clientCore.getCurrentUser();
        String myName = myUser != null ? myUser.getUsername() : "我";
        chatPanel.addMessage(myUser != null ? myUser.getUid() : 0, myName, text, false, true);

        if (currentChatIsGroup) {
            clientCore.sendGroupMsg(currentChatUID, text);
        } else {
            clientCore.sendPrivateMsg(currentChatUID, text);
        }
    }

    /** 处理UI层的命令发送 */
    public void handleUISendCommand(String command) {
        if (currentChatUID == 0) {
            chatPanel.addSystemMessage("请先选择发送目标");
            return;
        }

        String trimmed = command.trim();

        // 检查是否是双引号包裹的路径（"path"）
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            String path = trimmed.substring(1, trimmed.length() - 1);
            File irFile = new File(path);
            if (irFile.exists()) {
                try {
                    String irContent = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
                    clientCore.sendCommand(currentChatUID, "IR:" + irContent);
                    chatPanel.addSystemMessage("已发送IR脚本: " + path);
                } catch (IOException e) {
                    chatPanel.addSystemMessage("读取IR文件失败: " + e.getMessage());
                }
            } else {
                chatPanel.addSystemMessage("文件不存在: " + path);
            }
            return;
        }

        // 否则作为SairFramework命令发送（格式: 插件/功能名 参数）
        clientCore.sendCommand(currentChatUID, command);
        chatPanel.addSystemMessage("已发送命令: " + command);
    }

    /** 处理通讯录双击 */
    public void handleContactDoubleClick(long uid, String name, boolean isGroup) {
        currentChatUID = uid;
        currentChatIsGroup = isGroup;
        chatPanel.clear();

        // 加载聊天记录
        java.util.List<sair.scq.model.ChatRecord> records = clientCore.getLocalStore().loadRecords(uid);
        for (sair.scq.model.ChatRecord r : records) {
            UserInfo myUser = clientCore.getCurrentUser();
            boolean isSelf = (myUser != null && r.getFromUID() == myUser.getUid());
            String fromName = isSelf ? myUser.getUsername() : String.valueOf(r.getFromUID());
            chatPanel.addMessage(r.getFromUID(), fromName, r.getContent(), r.isCommand(), isSelf);
        }

        chatPanel.addSystemMessage("已打开与 " + name + " 的聊天");
        clientUI.setStatusText("正在与 " + name + " 聊天");
        clientUI.showMainUI();
    }

    /** 处理通讯录右键 */
    public void handleContactRightClick(long uid, String name, boolean isGroup) {
        // 简单处理：如果右键正在聊天的对象则清除
        if (uid == currentChatUID) {
            currentChatUID = 0;
            currentChatIsGroup = false;
            chatPanel.addSystemMessage("已关闭与 " + name + " 的聊天");
        }
    }

    /** 处理个人信息保存 */
    public void handleSaveProfile(String signature, String avatarPath, String newPassword) {
        clientCore.updateProfile(signature, avatarPath, newPassword);
    }

    /** 退出时清理 */
    public void exit() {
        if (clientCore.isConnected()) {
            clientCore.disconnect();
        }
    }
}
