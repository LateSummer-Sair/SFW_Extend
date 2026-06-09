package sair.scq.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import sair.scq.model.UserInfo;
import sair.scq.protocol.MessageType;
import sair.scq.protocol.SCMessage;
import sair.sys.SairCons;

/**
 * SCQ客户端核心 —— TCP持久连接客户端，管理服务端连接和消息收发。
 * 
 * <h3>架构（参照nsc的CM+AutoPrintln持久连接模式）</h3>
 * <ul>
 *   <li>一个Socket持久连接服务端（非点对点一次性传输）</li>
 *   <li>发送线程：从BlockingQueue取消息 → DataOutputStream发送（类似nsc的CM.send）</li>
 *   <li>接收线程：while(running)循环读取DataInputStream → 回调MessageHandler（类似nsc的AutoPrintln）</li>
 *   <li>断线处理：IOException时退出循环并通知上层（类似nsc AutoPrintln中IOException→c.exit()）</li>
 *   <li>断线重连：自动检测断线并尝试重连（SCQ特有增强）</li>
 * </ul>
 * 
 * <h3>与nsc的关键差异</h3>
 * nsc使用ObjectInputStream/ObjectOutputStream传输Java对象，
 * 本客户端使用DataInputStream/DataOutputStream传输长度前缀+JSON字符串，
 * 更适合跨语言和调试。
 * 
 * <h3>消息处理</h3>
 * 通过{@link MessageHandler}接口将收到的消息回调给UI层处理。
 */
public class SCClientCore {

    /** 当前连接的用户 */
    private UserInfo currentUser;
    /** 连接状态 */
    private volatile boolean connected = false;
    /** 运行状态 */
    private volatile boolean running = false;

    /** Socket连接 */
    private Socket socket;
    /** 输出流 */
    private DataOutputStream dos;
    /** 输入流 */
    private DataInputStream dis;

    /** 发送队列（线程安全） */
    private final BlockingQueue<SCMessage> sendQueue = new LinkedBlockingQueue<SCMessage>();
    /** 消息回调处理器 */
    private MessageHandler messageHandler;
    /** 聊天记录存储 */
    private LocalStore localStore;

    /** 重连参数 */
    private String lastHost;
    private int lastPort;
    private boolean autoReconnect = false;

    /**
     * 消息回调接口 —— UI层实现此接口以处理收到的消息。
     */
    public interface MessageHandler {
        void onMessage(SCMessage msg);
        void onDisconnect(String reason);
        void onConnect();
    }

    public SCClientCore(LocalStore localStore) {
        this.localStore = localStore;
    }

    public UserInfo getCurrentUser() { return currentUser; }
    public boolean isConnected() { return connected; }
    public void setMessageHandler(MessageHandler handler) { this.messageHandler = handler; }
    public LocalStore getLocalStore() { return localStore; }

    // ==================== 连接管理 ====================

    /**
     * 连接服务端。
     * @param host 服务端IP
     * @param port 服务端端口
     * @return 是否连接成功
     */
    public boolean connect(String host, int port) {
        if (connected) return false;

        this.lastHost = host;
        this.lastPort = port;

        try {
            socket = new Socket(host, port);
            socket.setSoTimeout(0); // 无超时
            dos = new DataOutputStream(socket.getOutputStream());
            dis = new DataInputStream(socket.getInputStream());

            connected = true;
            running = true;

            // 启动发送线程
            new Thread(new Runnable() {
                public void run() {
                    sendLoop();
                }
            }, "SCQ-Sender").start();

            // 启动接收线程
            new Thread(new Runnable() {
                public void run() {
                    receiveLoop();
                }
            }, "SCQ-Receiver").start();

            if (messageHandler != null) {
                messageHandler.onConnect();
            }

            return true;
        } catch (IOException e) {
            SairCons.println("连接服务端失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 断开连接。
     */
    public void disconnect() {
        autoReconnect = false;
        running = false;
        connected = false;
        currentUser = null;

        try { if (dos != null) dos.close(); } catch (Exception e) {}
        try { if (dis != null) dis.close(); } catch (Exception e) {}
        try { if (socket != null) socket.close(); } catch (Exception e) {}

        if (messageHandler != null) {
            messageHandler.onDisconnect("已断开连接");
        }
    }

    // ==================== 发送 ====================

    /**
     * 发送消息到队列。
     */
    public void sendMessage(SCMessage msg) {
        if (!connected) return;
        sendQueue.offer(msg);
    }

    /** 发送循环 */
    private void sendLoop() {
        while (running) {
            try {
                SCMessage msg = sendQueue.take();
                byte[] jsonBytes = msg.toJson().getBytes(StandardCharsets.UTF_8);
                synchronized (dos) {
                    dos.writeInt(jsonBytes.length);
                    dos.write(jsonBytes);
                    dos.flush();
                }
            } catch (InterruptedException e) {
                break;
            } catch (IOException e) {
                handleDisconnect("发送失败: " + e.getMessage());
                break;
            }
        }
    }

    // ==================== 接收 ====================

    /** 接收循环 */
    private void receiveLoop() {
        while (running) {
            try {
                int length = dis.readInt();
                if (length <= 0 || length > 1048576) {
                    handleDisconnect("协议错误");
                    break;
                }

                byte[] bytes = new byte[length];
                dis.readFully(bytes);
                String json = new String(bytes, StandardCharsets.UTF_8);

                SCMessage msg = SCMessage.fromJson(json);
                if (msg != null && messageHandler != null) {
                    messageHandler.onMessage(msg);
                }

                // 如果是PRIVATE_MSG或GROUP_MSG，存储聊天记录
                if (msg != null && (msg.getType() == MessageType.PRIVATE_MSG || msg.getType() == MessageType.GROUP_MSG)) {
                    saveChatRecord(msg);
                }
            } catch (IOException e) {
                handleDisconnect("连接断开: " + e.getMessage());
                break;
            }
        }
    }

    /** 处理断线 */
    private void handleDisconnect(String reason) {
        if (!running) return;
        running = false;
        connected = false;
        try { socket.close(); } catch (Exception e) {}

        if (messageHandler != null) {
            messageHandler.onDisconnect(reason);
        }

        // 尝试重连
        if (autoReconnect) {
            tryReconnect();
        }
    }

    /** 尝试重连 */
    private void tryReconnect() {
        new Thread(new Runnable() {
            public void run() {
                for (int i = 0; i < 5 && !running; i++) {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { break; }
                    SairCons.println("尝试重连... (" + (i + 1) + "/5)");
                    if (connect(lastHost, lastPort)) {
                        SairCons.println("重连成功");
                        return;
                    }
                }
                SairCons.println("重连失败");
            }
        }, "SCQ-Reconnect").start();
    }

    /** 保存聊天记录 */
    private void saveChatRecord(SCMessage msg) {
        if (localStore == null || currentUser == null) return;
        try {
            sair.scq.model.ChatRecord record = new sair.scq.model.ChatRecord();
            record.setTimestamp(msg.getTimestamp());
            record.setFromUID(msg.getFromUID());
            record.setToUID(msg.getToUID());
            record.setContent(msg.getData());
            record.setGroup(msg.getType() == MessageType.GROUP_MSG);
            record.setCommand(false);
            localStore.saveRecord(record);
        } catch (Exception e) {}
    }

    // ==================== 便捷方法 ====================

    /** 发送登录请求 */
    public void login(String username, String password) {
        SCMessage msg = new SCMessage(MessageType.LOGIN);
        msg.setData(username);
        msg.setExtraData(password);
        sendMessage(msg);
    }

    /** 发送注册请求 */
    public void register(String username, String password) {
        SCMessage msg = new SCMessage(MessageType.REGISTER);
        msg.setData(username);
        msg.setExtraData(password);
        sendMessage(msg);
    }

    /** 发送私聊消息 */
    public void sendPrivateMsg(long toUID, String content) {
        SCMessage msg = new SCMessage(MessageType.PRIVATE_MSG);
        msg.setFromUID(currentUser != null ? currentUser.getUid() : 0);
        msg.setToUID(toUID);
        msg.setData(content);
        sendMessage(msg);
    }

    /** 发送群聊消息 */
    public void sendGroupMsg(long gid, String content) {
        SCMessage msg = new SCMessage(MessageType.GROUP_MSG);
        msg.setFromUID(currentUser != null ? currentUser.getUid() : 0);
        msg.setToUID(gid);
        msg.setData(content);
        sendMessage(msg);
    }

    /** 发送命令 */
    public void sendCommand(long toUID, String command) {
        SCMessage msg = new SCMessage(MessageType.COMMAND);
        msg.setFromUID(currentUser != null ? currentUser.getUid() : 0);
        msg.setToUID(toUID);
        msg.setData(command);
        sendMessage(msg);
    }

    /** 请求在线用户列表 */
    public void requestUserList() {
        sendMessage(new SCMessage(MessageType.USER_LIST));
    }

    /** 请求通讯录 */
    public void requestContactList() {
        sendMessage(new SCMessage(MessageType.CONTACT_LIST));
    }

    /** 请求群组列表 */
    public void requestGroupList() {
        sendMessage(new SCMessage(MessageType.GROUP_LIST));
    }

    /** 添加好友 */
    public void addContact(long contactUID) {
        SCMessage msg = new SCMessage(MessageType.CONTACT_ADD);
        msg.setData(String.valueOf(contactUID));
        sendMessage(msg);
    }

    /** 创建群组 */
    public void createGroup(String groupName) {
        SCMessage msg = new SCMessage(MessageType.GROUP_CREATE);
        msg.setData(groupName);
        sendMessage(msg);
    }

    /** 申请加入群组 */
    public void joinGroup(long gid) {
        SCMessage msg = new SCMessage(MessageType.GROUP_JOIN);
        msg.setData(String.valueOf(gid));
        sendMessage(msg);
    }

    /** 退出群组 */
    public void leaveGroup(long gid) {
        SCMessage msg = new SCMessage(MessageType.GROUP_LEAVE);
        msg.setData(String.valueOf(gid));
        sendMessage(msg);
    }

    /** 踢出成员 */
    public void kickMember(long gid, long targetUID) {
        SCMessage msg = new SCMessage(MessageType.GROUP_KICK);
        msg.setToUID(gid);
        msg.setExtraData(String.valueOf(targetUID));
        sendMessage(msg);
    }

    /** 禁言 */
    public void muteMember(long gid, long targetUID) {
        SCMessage msg = new SCMessage(MessageType.GROUP_MUTE);
        msg.setToUID(gid);
        msg.setExtraData(String.valueOf(targetUID));
        sendMessage(msg);
    }

    /** 取消禁言 */
    public void unmuteMember(long gid, long targetUID) {
        SCMessage msg = new SCMessage(MessageType.GROUP_UNMUTE);
        msg.setToUID(gid);
        msg.setExtraData(String.valueOf(targetUID));
        sendMessage(msg);
    }

    /** 设置管理员 */
    public void setAdmin(long gid, long targetUID) {
        SCMessage msg = new SCMessage(MessageType.GROUP_SET_ADMIN);
        msg.setToUID(gid);
        msg.setExtraData(String.valueOf(targetUID));
        sendMessage(msg);
    }

    /** 更新个人信息 */
    public void updateProfile(String signature, String avatarPath, String newPassword) {
        SCMessage msg = new SCMessage(MessageType.USER_UPDATE);
        msg.setData(signature);
        // extraData包含avatarPath和newPassword
        String extra = "{\"avatarPath\":\"" + (avatarPath != null ? avatarPath.replace("\\", "\\\\").replace("\"", "\\\"") : "") + "\""
                    + ",\"newPassword\":\"" + (newPassword != null ? newPassword.replace("\\", "\\\\").replace("\"", "\\\"") : "") + "\"}";
        msg.setExtraData(extra);
        sendMessage(msg);
    }

    /** 发送文件传输请求 */
    public void sendFileTransfer(long toUID, String fileName, long fileSize) {
        SCMessage msg = new SCMessage(MessageType.FILE_TRANSFER);
        msg.setToUID(toUID);
        msg.setData(fileName);
        msg.setExtraData(String.valueOf(fileSize));
        sendMessage(msg);
    }

    /** 设置当前用户（登录成功后调用） */
    public void setCurrentUser(UserInfo user) {
        this.currentUser = user;
        if (user != null) {
            this.localStore.init(user.getUid(), user.getPasswordHash());
        }
    }

    /** 设置是否自动重连 */
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }
}
