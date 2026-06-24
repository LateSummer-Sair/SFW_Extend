package sair.aiagent.onebot;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.onebot.model.QQMessage;
import sair.aiagent.onebot.util.JsonUtil;

/**
 * OneBot v11 反向 WebSocket 服务端。
 * <p>
 * 在SFW中启动一个轻量级WebSocket服务器，接受OneBot实现端
 * （LLOneBot / NapCat / go-cqhttp等）的反向WebSocket连接。
 * 收到OneBot事件后回调 {@link QQMessageHandler} 处理。
 * </p>
 *
 * <h3>协议</h3>
 * <ul>
 *   <li>RFC 6455 WebSocket 协议</li>
 *   <li>OneBot v11 事件/API JSON 格式</li>
 *   <li>支持 Access Token 认证</li>
 * </ul>
 */
public class OneBotServer {

    // === 配置 ===
    private static final int DEFAULT_PORT = 5800;
    private static final int MAX_FRAME_SIZE = 256 * 1024; // 256KB
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // === 状态 ===
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger wsClientCount = new AtomicInteger(0);
    private final AtomicLong echoCounter = new AtomicLong(0);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final List<WebSocketConnection> connections = new CopyOnWriteArrayList<>();
    
    /** API调用等待队列：echo → 响应Future */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingApiCalls = new ConcurrentHashMap<>();

    // === 配置项 ===
    private int port = DEFAULT_PORT;
    private String accessToken = "";

    // === 回调 ===
    private QQMessageHandler messageHandler;

    // === SFW引用 ===
    private String dataDir;

    public OneBotServer() {}

    // ==================== 配置 ====================

    public void setPort(int port) { this.port = port > 0 ? port : DEFAULT_PORT; }
    public int getPort() { return port; }

    public void setAccessToken(String token) { this.accessToken = (token != null) ? token.trim() : ""; }
    public String getAccessToken() { return accessToken; }

    public void setMessageHandler(QQMessageHandler handler) { this.messageHandler = handler; }

    /** 设置数据目录 */
    public void setDataDir(String dir) { this.dataDir = dir; }

    // ==================== 生命周期 ====================

    /** 启动WebSocket服务器 */
    public synchronized boolean start() {
        if (running.get()) {
            AiAgentActivity.debugLog("[OneBot] 服务器已在运行");
            return false;
        }
        try {
            serverSocket = new ServerSocket(port);
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "OneBot-Acceptor");
            acceptThread.setDaemon(true);
            acceptThread.start();
            AiAgentActivity.debugLog("[OneBot] 服务器已启动，端口: " + port);
            return true;
        } catch (IOException e) {
            AiAgentActivity.debugLog("[OneBot] 启动失败: " + e.getMessage());
            return false;
        }
    }

    /** 停止WebSocket服务器 */
    public synchronized void stop() {
        running.set(false);
        // 关闭所有连接
        for (WebSocketConnection conn : connections) {
            conn.close();
        }
        connections.clear();
        // 关闭ServerSocket
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
        AiAgentActivity.debugLog("[OneBot] 服务器已停止");
    }

    public boolean isRunning() { return running.get(); }
    public int getConnectionCount() { return wsClientCount.get(); }

    // ==================== 接受连接循环 ====================

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                WebSocketConnection conn = new WebSocketConnection(socket);
                connections.add(conn);
                wsClientCount.incrementAndGet();
                new Thread(conn::handle, "OneBot-Conn-" + wsClientCount.get()).start();
            } catch (IOException e) {
                if (running.get()) {
                    AiAgentActivity.debugLog("[OneBot] 接受连接错误: " + e.getMessage());
                }
            }
        }
    }

    // ==================== 发送API调用 ====================

    /** 发送API调用到OneBot实现端并等待响应（超时10秒） */
    public String sendApiCall(String action, Map<String, Object> params) {
        String echo = "echo_" + echoCounter.incrementAndGet();
        StringBuilder json = new StringBuilder();
        json.append("{\"action\":\"").append(jsonEscape(action)).append("\"");
        json.append(",\"params\":{");
        boolean first = true;
        if (params != null) {
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (!first) json.append(",");
                json.append("\"").append(jsonEscape(e.getKey())).append("\":");
                Object v = e.getValue();
                if (v instanceof String) {
                    json.append("\"").append(jsonEscape((String) v)).append("\"");
                } else if (v instanceof Number) {
                    json.append(v);
                } else if (v instanceof Boolean) {
                    json.append(v);
                } else {
                    json.append("\"").append(jsonEscape(String.valueOf(v))).append("\"");
                }
                first = false;
            }
        }
        json.append("},\"echo\":\"").append(echo).append("\"}");

        String payload = json.toString();
        
        // 创建Future用于等待响应
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingApiCalls.put(echo, future);
        
        AiAgentActivity.debugLog("[OneBot] 发送API: " + (payload.length() > 200 ? payload.substring(0, 200) + "..." : payload));
        // 广播到所有连接
        for (WebSocketConnection c : connections) {
            if (c.isOpen()) {
                c.sendText(payload);
            }
        }
        
        // 等待响应（10秒超时）
        try {
            String response = future.get(10, TimeUnit.SECONDS);
            pendingApiCalls.remove(echo);
            return response;
        } catch (TimeoutException e) {
            pendingApiCalls.remove(echo);
            AiAgentActivity.debugLog("[OneBot] API调用超时: " + action);
            return null;
        } catch (Exception e) {
            pendingApiCalls.remove(echo);
            AiAgentActivity.debugLog("[OneBot] API调用异常: " + e.getMessage());
            return null;
        }
    }
    
    /** 处理API响应（由WebSocket连接的onTextMessage调用） */
    void onApiResponse(String text) {
        String echo = JsonUtil.extractString(text, "echo");
        if (echo != null && !echo.isEmpty()) {
            CompletableFuture<String> future = pendingApiCalls.get(echo);
            if (future != null) {
                future.complete(text);
            }
        }
    }

    /** 发送私聊消息 */
    public void sendPrivateMsg(long userId, String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("user_id", userId);
        params.put("message", message);
        sendApiCall("send_private_msg", params);
    }

    /** 发送群聊消息 */
    public void sendGroupMsg(long groupId, String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("message", message);
        sendApiCall("send_group_msg", params);
    }

    // ==================== WebSocket连接内部类 ====================

    private class WebSocketConnection {
        private final Socket socket;
        private InputStream in;
        private OutputStream out;
        private AtomicBoolean open = new AtomicBoolean(true);
        private String remoteAddr;

        WebSocketConnection(Socket socket) {
            this.socket = socket;
            this.remoteAddr = socket.getInetAddress().getHostAddress();
        }

        boolean isOpen() { return open.get(); }

        void close() {
            if (open.compareAndSet(true, false)) {
                try { sendCloseFrame(); } catch (Exception ignored) {}
                try { socket.close(); } catch (IOException ignored) {}
                wsClientCount.decrementAndGet();
            }
        }

        void handle() {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();

                // 1. WebSocket握手
                if (!doHandshake()) {
                    close();
                    return;
                }

                AiAgentActivity.debugLog("[OneBot] 客户端已连接: " + remoteAddr);

                // 2. 读取帧循环
                readFrames();

            } catch (IOException e) {
                AiAgentActivity.debugLog("[OneBot] 连接错误: " + e.getMessage());
            } finally {
                close();
                connections.remove(this);
                AiAgentActivity.debugLog("[OneBot] 客户端已断开: " + remoteAddr);
            }
        }

        /** WebSocket握手（RFC 6455） */
        private boolean doHandshake() throws IOException {
            // 逐字节读取HTTP升级请求（避免BufferedReader预读吞掉后续WebSocket帧）
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int prev = 0, curr;
            while ((curr = in.read()) != -1) {
                headerBuf.write(curr);
                // 检测 \r\n\r\n 结束标记
                if (prev == '\r' && curr == '\n') {
                    int size = headerBuf.size();
                    if (size >= 4) {
                        byte[] data = headerBuf.toByteArray();
                        if (data[size - 4] == '\r' && data[size - 3] == '\n'
                                && data[size - 2] == '\r' && data[size - 1] == '\n') {
                            break;
                        }
                    }
                }
                prev = curr;
            }

            String headerStr = new String(headerBuf.toByteArray(), StandardCharsets.UTF_8);
            String[] lines = headerStr.split("\r\n");
            String secKey = null;
            String authHeader = null;

            for (String line : lines) {
                if (line.startsWith("Sec-WebSocket-Key:")) {
                    secKey = line.substring("Sec-WebSocket-Key:".length()).trim();
                }
                if (line.startsWith("Authorization:")) {
                    authHeader = line.substring("Authorization:".length()).trim();
                }
            }

            if (secKey == null) {
                AiAgentActivity.debugLog("[OneBot] 缺少 Sec-WebSocket-Key");
                return false;
            }

            // Access Token 认证
            if (accessToken != null && !accessToken.isEmpty()) {
                String expectedAuth = "Bearer " + accessToken;
                if (authHeader == null || !expectedAuth.equals(authHeader)) {
                    AiAgentActivity.debugLog("[OneBot] 认证失败: " + remoteAddr);
                    String response = "HTTP/1.1 401 Unauthorized\r\n\r\n";
                    out.write(response.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    return false;
                }
            }

            // 计算Accept值
            String acceptKey = computeAcceptKey(secKey);

            // 发送101响应
            StringBuilder response = new StringBuilder();
            response.append("HTTP/1.1 101 Switching Protocols\r\n");
            response.append("Upgrade: websocket\r\n");
            response.append("Connection: Upgrade\r\n");
            response.append("Sec-WebSocket-Accept: ").append(acceptKey).append("\r\n");
            response.append("\r\n");

            out.write(response.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return true;
        }

        /** 计算Sec-WebSocket-Accept */
        private String computeAcceptKey(String key) {
            try {
                String input = key + WS_GUID;
                MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
                byte[] hash = sha1.digest(input.getBytes(StandardCharsets.UTF_8));
                return Base64.getEncoder().encodeToString(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        /** 读取WebSocket帧 */
        private void readFrames() throws IOException {
            byte[] buf = new byte[MAX_FRAME_SIZE];
            ByteArrayOutputStream messageBuf = new ByteArrayOutputStream();

            while (open.get() && !socket.isClosed()) {
                // 读取帧头（至少2字节）
                int b0 = in.read();
                if (b0 == -1) break;
                int b1 = in.read();
                if (b1 == -1) break;

                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long payloadLen = b1 & 0x7F;

                // 扩展长度
                if (payloadLen == 126) {
                    payloadLen = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);
                } else if (payloadLen == 127) {
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLen = (payloadLen << 8) | (in.read() & 0xFF);
                    }
                }

                // 读取mask key
                byte[] maskKey = null;
                if (masked) {
                    maskKey = new byte[4];
                    for (int i = 0; i < 4; i++) {
                        maskKey[i] = (byte) in.read();
                    }
                }

                // 读取payload
                if (payloadLen > MAX_FRAME_SIZE) {
                    AiAgentActivity.debugLog("[OneBot] 帧过大: " + payloadLen);
                    break;
                }

                byte[] payload = new byte[(int) payloadLen];
                int totalRead = 0;
                while (totalRead < payloadLen) {
                    int n = in.read(payload, totalRead, (int) (payloadLen - totalRead));
                    if (n == -1) break;
                    totalRead += n;
                }

                // 解码mask
                if (masked && maskKey != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] = (byte) (payload[i] ^ maskKey[i % 4]);
                    }
                }

                // 处理帧
                switch (opcode) {
                    case 0x01: // 文本帧
                        messageBuf.write(payload);
                        if (fin) {
                            String text = new String(messageBuf.toByteArray(), StandardCharsets.UTF_8);
                            messageBuf.reset();
                            onTextMessage(text);
                        }
                        break;
                    case 0x08: // 关闭帧
                        open.set(false);
                        return;
                    case 0x09: // Ping
                        sendFrame(0x0A, payload); // Pong
                        break;
                    case 0x0A: // Pong (ignore)
                        break;
                    case 0x00: // 延续帧
                        messageBuf.write(payload);
                        if (fin) {
                            String text = new String(messageBuf.toByteArray(), StandardCharsets.UTF_8);
                            messageBuf.reset();
                            onTextMessage(text);
                        }
                        break;
                    default:
                        AiAgentActivity.debugLog("[OneBot] 未知操作码: " + opcode);
                        break;
                }
            }
        }

        /** 处理收到的文本消息（OneBot事件或API响应） */
        private void onTextMessage(String text) {
            // === 优先检测API响应（status+echo，无post_type） ===
            // API响应格式: {"status":"ok","retcode":0,"data":{...},"echo":"echo_42"}
            if (text.contains("\"status\"") && text.contains("\"echo\"")) {
                onApiResponse(text);
                return;
            }
            
            // 过滤心跳包
            if (text.contains("\"post_type\":\"meta_event\"") || 
                text.contains("\"post_type\": \"meta_event\"")) {
                // 静默忽略心跳包
                return;
            }
            
            // 处理request事件（群邀请、好友请求等）
            if (text.contains("\"post_type\":\"request\"") || 
                text.contains("\"post_type\": \"request\"")) {
                handleRequestEvent(text);
                return;
            }
            
            // 处理notice事件（戳一戳等）
            if (text.contains("\"post_type\":\"notice\"") || 
                text.contains("\"post_type\": \"notice\"")) {
                handleNoticeEvent(text);
                return;
            }
            
            AiAgentActivity.debugLog("[OneBot] 收到: " + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
            if (messageHandler != null) {
                try {
                    messageHandler.handleRawMessage(text, this::sendText);
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[OneBot] 消息处理错误: " + e.toString());
                }
            }
        }
        
        /** 处理notice事件（戳一戳、好友申请等） */
        private void handleNoticeEvent(String text) {
            try {
                // 检查是否是戳一戳事件
                if (text.contains("\"sub_type\":\"poke\"") || 
                    text.contains("\"sub_type\": \"poke\"")) {
                            
                    // 手动解析戳一戳信息（无第三方依赖）
                    long targetId = JsonUtil.extractLong(text, "target_id");
                    long userId = JsonUtil.extractLong(text, "user_id");
                    long groupId = JsonUtil.extractLong(text, "group_id");
                            
                    // 从messageHandler获取selfId
                    long currentSelfId = messageHandler != null ? messageHandler.getSelfId() : 0;
                            
                    // 检查是否戳的是机器人自己
                    if (targetId == currentSelfId && messageHandler != null) {
                        AiAgentActivity.debugLog("[OneBot] 检测到戳一戳: user=" + userId + ", group=" + groupId);
                                
                        // 构造一个虚拟的@消息，触发AI响应
                        String fakeMessage = "{\"message_type\":\"" + (groupId > 0 ? "group" : "private") + "\"," +
                            "\"user_id\":" + userId + "," +
                            (groupId > 0 ? "\"group_id\":" + groupId + "," : "") +
                            "\"message\":[{\"type\":\"at\",\"data\":{\"qq\":" + currentSelfId + "}},{\"type\":\"text\",\"data\":{\"text\":\"戳了捅我\"}}]," +
                            "\"message_id\":0," +
                            "\"raw_message\":\"[CQ:poke,qq=" + userId + "]\"}";
                                
                        messageHandler.handleRawMessage(fakeMessage, this::sendText);
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[OneBot] notice事件处理错误: " + e.toString());
            }
        }

        /** 处理request事件（群邀请、好友请求等） */
        private void handleRequestEvent(String text) {
            try {
                String requestType = JsonUtil.extractString(text, "request_type");
                String subType = JsonUtil.extractString(text, "sub_type");
                
                // 好友申请：request_type=friend, sub_type=add
                if ("friend".equals(requestType) && "add".equals(subType)) {
                    long userId = JsonUtil.extractLong(text, "user_id");
                    String comment = JsonUtil.extractString(text, "comment");
                    String flag = JsonUtil.extractString(text, "flag");
                    
                    if (messageHandler != null && flag != null && userId > 0) {
                        AiAgentActivity.debugLog("[OneBot] 收到好友申请: userId=" + userId);
                        messageHandler.handleFriendRequest(userId, comment, flag);
                    }
                }
                
                // 群邀请：request_type=group, sub_type=invite
                if ("group".equals(requestType) && "invite".equals(subType)) {
                    long userId = JsonUtil.extractLong(text, "user_id");
                    long groupId = JsonUtil.extractLong(text, "group_id");
                    String flag = JsonUtil.extractString(text, "flag");
                    
                    if (messageHandler != null && flag != null && userId > 0) {
                        AiAgentActivity.debugLog("[OneBot] 收到群邀请: userId=" + userId + ", groupId=" + groupId);
                        messageHandler.handleGroupInviteRequest(userId, groupId, flag);
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[OneBot] request事件处理错误: " + e.toString());
            }
        }

        /** 发送文本帧 */
        synchronized void sendText(String text) {
            if (!open.get() || out == null) return;
            try {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                sendFrame(0x01, data);
            } catch (IOException e) {
                AiAgentActivity.debugLog("[OneBot] 发送错误: " + e.getMessage());
                close();
            }
        }

        /** 发送WebSocket帧 */
        private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            ByteArrayOutputStream frame = new ByteArrayOutputStream();

            // FIN + opcode
            frame.write(0x80 | opcode);

            // Payload length
            int len = payload.length;
            if (len < 126) {
                frame.write(len);
            } else if (len < 65536) {
                frame.write(126);
                frame.write((len >> 8) & 0xFF);
                frame.write(len & 0xFF);
            } else {
                frame.write(127);
                for (int i = 7; i >= 0; i--) {
                    frame.write((int) ((len >> (i * 8)) & 0xFF));
                }
            }

            // 服务端发客户端的帧不需要mask
            frame.write(payload);

            out.write(frame.toByteArray());
            out.flush();
        }

        /** 发送关闭帧 */
        private void sendCloseFrame() throws IOException {
            try {
                sendFrame(0x08, new byte[0]);
            } catch (IOException ignored) {}
        }
    }

    // ==================== JSON工具方法 ====================

    static String jsonEscape(String s) {
        return JsonUtil.jsonEscape(s);
    }
}
