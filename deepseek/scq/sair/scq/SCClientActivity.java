package sair.scq;

import sair.Pathes;
import sair.scq.acts.ClientActions;
import sair.scq.client.LocalStore;
import sair.scq.client.SCClientCore;
import sair.scq.model.UserInfo;
import sair.scq.protocol.MessageType;
import sair.scq.protocol.SCMessage;
import sair.scq.ui.ChatPanel;
import sair.scq.ui.ContactPanel;
import sair.scq.ui.InputPanel;
import sair.scq.ui.LoginPanel;
import sair.scq.ui.SCClientUI;
import sair.scq.ui.UserInfoPanel;
import sair.sys.SairCons;
import sair.user.Activity;

/**
 * SCQ客户端Activity —— 提供SCQ即时聊天客户端的控制台命令和UI入口。
 * 
 * <h3>命令命名空间</h3>
 * 注册名为 "scqc"，命令格式为 scqc/命令 参数。
 * 
 * <h3>支持的命令</h3>
 * <ul>
 *   <li>connect [host] [port] - 连接服务端</li>
 *   <li>disconnect - 断开连接</li>
 *   <li>login <用户名> <密码> - 登录</li>
 *   <li>register <用户名> <密码> - 注册</li>
 *   <li>addContact <UID> - 添加好友</li>
 *   <li>createGroup <名称> - 创建群组</li>
 *   <li>joinGroup <GID> - 加入群组</li>
 *   <li>refreshUsers - 刷新用户列表</li>
 *   <li>showUI - 显示聊天界面</li>
 *   <li>autoExec [true|false] - 设置命令自动执行模式</li>
 * </ul>
 */
public class SCClientActivity extends Activity {

    private SCClientCore clientCore;
    private ClientActions clientActions;
    private LocalStore localStore;

    private SCClientUI clientUI;
    private ChatPanel chatPanel;
    private ContactPanel contactPanel;
    private InputPanel inputPanel;
    private LoginPanel loginPanel;
    private UserInfoPanel userInfoPanel;

    private boolean initialized = false;

    @Override
    public Object main(String funcName, String args) {
        initIfNeeded();

        switch (funcName) {
            case "connect":
                return clientActions.connect(args);
            case "disconnect":
                return clientActions.disconnect();
            case "login":
                return clientActions.login(args);
            case "register":
                return clientActions.register(args);
            case "addContact":
                return clientActions.addContact(args);
            case "createGroup":
                return clientActions.createGroup(args);
            case "joinGroup":
                return clientActions.joinGroup(args);
            case "refreshUsers":
                return clientActions.refreshUsers();
            case "showUI":
                return clientActions.showUI();
            case "autoExec":
                return clientActions.setAutoExecute(args);
            case "sendMsg":
                return sendMsgFromConsole(args);
            case "sendCmd":
                return sendCmdFromConsole(args);
            default:
                return false;
        }
    }

    private void initIfNeeded() {
        if (initialized) return;
        initialized = true;

        // 1. 创建核心组件
        localStore = new LocalStore(this.getDataDir());
        clientCore = new SCClientCore(localStore);
        clientActions = new ClientActions(clientCore);

        // 2. 创建UI组件
        clientUI = new SCClientUI();
        chatPanel = new ChatPanel();
        contactPanel = new ContactPanel(new ContactPanel.ContactCallback() {
            public void onContactDoubleClick(long uid, String name, boolean isGroup) {
                clientActions.handleContactDoubleClick(uid, name, isGroup);
            }
            public void onContactRightClick(long uid, String name, boolean isGroup) {
                clientActions.handleContactRightClick(uid, name, isGroup);
            }
        });
        inputPanel = new InputPanel(new InputPanel.InputCallback() {
            public void onSendMessage(String text) {
                clientActions.handleUISendMessage(text);
                clientUI.showMainUI();
            }
            public void onSendCommand(String command) {
                clientActions.handleUISendCommand(command);
                clientUI.showMainUI();
            }
        });
        loginPanel = new LoginPanel(new LoginPanel.LoginCallback() {
            public void onLogin(String username, String password) {
                clientActions.handleUILogin(username, password);
            }
            public void onRegister(String username, String password) {
                clientActions.handleUIRegister(username, password);
            }
        });
        userInfoPanel = new UserInfoPanel(new UserInfoPanel.UserInfoCallback() {
            public void onSave(String signature, String avatarPath, String newPassword) {
                clientActions.handleSaveProfile(signature, avatarPath, newPassword);
            }
        });

        // 3. 设置UI引用
        clientUI.setChatPanel(chatPanel);
        clientUI.setContactPanel(contactPanel);
        clientUI.setInputPanel(inputPanel);
        clientUI.setLoginPanel(loginPanel);
        clientUI.setUserInfoPanel(userInfoPanel);

        clientActions.setClientUI(clientUI);
        clientActions.setChatPanel(chatPanel);
        clientActions.setContactPanel(contactPanel);
        clientActions.setInputPanel(inputPanel);
        clientActions.setLoginPanel(loginPanel);
        clientActions.setUserInfoPanel(userInfoPanel);

        // 4. 设置消息处理器
        clientCore.setMessageHandler(new SCClientCore.MessageHandler() {
            public void onMessage(SCMessage msg) {
                handleServerMessage(msg);
            }
            public void onDisconnect(String reason) {
                chatPanel.addSystemMessage("连接断开: " + reason);
                clientUI.setStatusText("未连接");
            }
            public void onConnect() {
                chatPanel.addSystemMessage("已连接到服务端");
            }
        });
    }

    // ==================== 服务端消息处理 ====================

    private void handleServerMessage(SCMessage msg) {
        MessageType type = msg.getType();
        if (type == null) return;

        switch (type) {
            case OK:
                handleOk(msg);
                break;
            case ERROR:
                handleError(msg);
                break;
            case PRIVATE_MSG:
                handlePrivateMsg(msg);
                break;
            case GROUP_MSG:
                handleGroupMsg(msg);
                break;
            case COMMAND:
                handleCommand(msg);
                break;
            case USER_LIST:
                handleUserList(msg);
                break;
            case CONTACT_LIST:
                handleContactList(msg);
                break;
            case STATUS:
                handleStatus(msg);
                break;
            case CONTACT_ADDED:
                handleContactAdded(msg);
                break;
            default:
                break;
        }
    }

    private void handleOk(SCMessage msg) {
        String data = msg.getData();
        String extra = msg.getExtraData();

        // 检查是否是登录/注册成功的响应（必须含passwordHash才是完整UserInfo）
        if (extra != null && extra.startsWith("{") && extra.contains("\"passwordHash\"")) {
            UserInfo user = UserInfo.fromJson(extra);
            if (user != null) {
                clientCore.setCurrentUser(user);
                clientUI.setCurrentUsername(user.getUsername());
                clientUI.setStatusText("在线 - UID:" + user.getUid());
                chatPanel.addSystemMessage("登录成功！UID: " + user.getUid());
                clientCore.requestContactList();
                clientCore.requestUserList();
                clientUI.showMainUI();
            }
        }

        // 检查是否是入群申请通知
        SairCons.println("[服务端] " + (data != null ? data : "操作成功"));
    }

    private void handleError(SCMessage msg) {
        String data = msg.getData();
        chatPanel.addSystemMessage("错误: " + (data != null ? data : "未知错误"));
        if (loginPanel != null) {
            loginPanel.setStatus(data != null ? data : "操作失败", java.awt.Color.RED);
        }
    }

    private void handlePrivateMsg(SCMessage msg) {
        long fromUID = msg.getFromUID();
        String content = msg.getData();
        UserInfo currentUser = clientCore.getCurrentUser();
        boolean isSelf = (currentUser != null && fromUID == currentUser.getUid());

        // 获取发送者名称
        String fromName = String.valueOf(fromUID);
        if (currentUser != null && fromUID == currentUser.getUid()) {
            fromName = currentUser.getUsername();
        }

        chatPanel.addMessage(fromUID, fromName, content, false, isSelf);
        clientUI.showMainUI();
    }

    private void handleGroupMsg(SCMessage msg) {
        long fromUID = msg.getFromUID();
        long gid = msg.getToUID();
        String content = msg.getData();
        UserInfo currentUser = clientCore.getCurrentUser();
        boolean isSelf = (currentUser != null && fromUID == currentUser.getUid());

        String fromName = "UID:" + fromUID;
        if (currentUser != null && fromUID == currentUser.getUid()) {
            fromName = currentUser.getUsername();
        }

        chatPanel.addMessage(fromUID, "[群" + gid + "] " + fromName, content, false, isSelf);
        clientUI.showMainUI();
    }

    private void handleCommand(SCMessage msg) {
        long fromUID = msg.getFromUID();
        String command = msg.getData();
        UserInfo currentUser = clientCore.getCurrentUser();

        boolean isSelf = (currentUser != null && fromUID == currentUser.getUid());
        String fromName = "UID:" + fromUID;

        // 区分 IR 脚本和普通命令
        if (command.startsWith("IR:")) {
            String irContent = command.substring(3);

            // 显示IR脚本摘要而非完整内容（避免刷屏）
            String preview = irContent.length() > 100 ? irContent.substring(0, 100) + "..." : irContent;
            chatPanel.addMessage(fromUID, fromName + " → IR脚本", preview, true, isSelf);
            clientUI.showMainUI();

            // IR脚本始终自动执行（程序化内容，不依赖autoExecute开关）
            if (!isSelf && currentUser != null && msg.getToUID() == currentUser.getUid()) {
                SairCons.println("收到IR脚本，来源UID:" + fromUID + "，通过IRRunnable模式执行...");
                SairCons.runner(true, irContent);
                chatPanel.addSystemMessage("已执行来自 UID:" + fromUID + " 的IR脚本");
            }
        } else {
            // 普通SairFramework命令
            chatPanel.addMessage(fromUID, fromName + " → 命令", command, true, isSelf);
            clientUI.showMainUI();

            if (!isSelf && currentUser != null && msg.getToUID() == currentUser.getUid()) {
                if (clientActions != null && clientActions.isAutoExecute()) {
                    SairCons.println("收到命令 [" + fromUID + "]: " + command + "，自动执行...");
                    SairCons.runner(false, command);
                    chatPanel.addSystemMessage("已自动执行来自 UID:" + fromUID + " 的命令: " + command);
                } else {
                    chatPanel.addSystemMessage("收到来自 UID:" + fromUID + " 的命令，设置 scqc/autoExec true 开启自动执行");
                }
            }
        }
    }

    private void handleUserList(SCMessage msg) {
        String data = msg.getData();
        if (data == null || !data.startsWith("[")) return;

        // 简单解析JSON数组中的用户
        // 格式: [{uid,username,role,signature,...},...]
        // 此处简化处理，实际应该用JSON解析
        SairCons.println("在线用户列表已更新");
    }

    private void handleContactList(SCMessage msg) {
        String data = msg.getData();
        if (data == null) return;

        contactPanel.clear();

        // 解析通讯录JSON
        // 格式: {"contacts":[{...}],"groups":[{...}]}
        // 简化为提取关键信息
        try {
            // 解析好友
            int contactsStart = data.indexOf("\"contacts\":[");
            if (contactsStart >= 0) {
                int start = data.indexOf("[", contactsStart);
                int end = findClosingBracket(data, start);
                String contactsStr = data.substring(start + 1, end);

                // 简单按},{ 分割
                String[] users = splitTopLevel(contactsStr);
                for (String userJson : users) {
                    if (userJson.trim().isEmpty()) continue;
                    long uid = extractLong(userJson, "\"uid\":");
                    String name = extractStr(userJson, "\"username\":\"");
                    String sig = extractStr(userJson, "\"signature\":\"");
                    boolean online = userJson.contains("\"isOnline\":true");
                    if (uid > 0) {
                        contactPanel.addContact(uid, name, sig, online);
                    }
                }
            }

            // 解析群组
            int groupsStart = data.indexOf("\"groups\":[");
            if (groupsStart >= 0) {
                int start = data.indexOf("[", groupsStart);
                int end = findClosingBracket(data, start);
                String groupsStr = data.substring(start + 1, end);

                String[] groups = splitTopLevel(groupsStr);
                for (String groupJson : groups) {
                    if (groupJson.trim().isEmpty()) continue;
                    long gid = extractLong(groupJson, "\"gid\":");
                    String name = extractStr(groupJson, "\"groupName\":\"");
                    int count = (int) extractLong(groupJson, "\"memberCount\":");
                    if (gid > 0) {
                        contactPanel.addGroup(gid, name, count);
                    }
                }
            }
        } catch (Exception e) {
            SairCons.println("解析通讯录失败: " + e.getMessage());
        }

        clientUI.showMainUI();
    }

    private void handleStatus(SCMessage msg) {
        long fromUID = msg.getFromUID();
        boolean online = "online".equals(msg.getData());
        contactPanel.updateContactStatus(fromUID, online);
    }

    private void handleContactAdded(SCMessage msg) {
        String adderName = msg.getData();
        chatPanel.addSystemMessage(adderName + " [UID:" + msg.getFromUID() + "] 已将你添加为好友");
        // 自动刷新通讯录
        clientCore.requestContactList();
    }

    // ==================== 控制台快捷发送 ====================

    private Object sendMsgFromConsole(String args) {
        if (args == null || args.isEmpty()) return false;
        clientActions.handleUISendMessage(args);
        return true;
    }

    private Object sendCmdFromConsole(String args) {
        if (args == null || args.isEmpty()) return false;
        clientActions.handleUISendCommand(args);
        return true;
    }

    // ==================== JSON解析辅助 ====================

    private int findClosingBracket(String s, int start) {
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return s.length() - 1;
    }

    private String[] splitTopLevel(String s) {
        java.util.List<String> result = new java.util.ArrayList<String>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(s.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return result.toArray(new String[0]);
    }

    private long extractLong(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        idx += key.length();
        int end = json.indexOf(",", idx);
        if (end < 0) end = json.indexOf("}", idx);
        if (end < 0) end = json.length();
        try { return Long.parseLong(json.substring(idx, end).trim()); }
        catch (Exception e) { return 0; }
    }

    private String extractStr(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return "";
        idx += key.length();
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) { sb.append(c); esc = false; }
            else if (c == '\\') esc = true;
            else if (c == '"') return sb.toString();
            else sb.append(c);
        }
        return sb.toString();
    }

    // ==================== 生命周期 ====================

    @Override
    public String[] help() {
        String name = this.getName();
        return new String[] {
            Pathes.printSplit,
            "SCQ 即时聊天 - 客户端",
            "Version: 1.0",
            Pathes.printSplit,
            "连接:",
            "\t" + name + "/connect [host] [port] 连接服务端",
            "\t" + name + "/disconnect 断开连接",
            "认证:",
            "\t" + name + "/login <用户名> <密码> 登录",
            "\t" + name + "/register <用户名> <密码> 注册",
            "通讯:",
            "\t" + name + "/sendMsg <消息> 发送消息到当前聊天对象",
            "\t" + name + "/sendCmd <命令> 发送命令到当前聊天对象",
            "社交:",
            "\t" + name + "/addContact <UID> 添加好友",
            "\t" + name + "/createGroup <名称> 创建群组（需要超管权限）",
            "\t" + name + "/joinGroup <GID> 申请加入群组",
            "\t" + name + "/refreshUsers 刷新用户列表和通讯录",
            "界面:",
            "\t" + name + "/showUI 显示聊天界面",
            "设置:",
            "\t" + name + "/autoExec [true|false] 设置命令自动执行模式",
            Pathes.printSplit,
        };
    }

    @Override
    public void exit() {
        if (clientActions != null) {
            clientActions.exit();
        }
    }
}
