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

import com.google.gson.Gson;

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
    /** 单个 WebSocket 帧上限（超限走「读完丢弃」，不再断连）。 */
    private static final int MAX_FRAME_SIZE = 2 * 1024 * 1024; // 2MB
    /** 分片消息累计上限（防止分片洪峰把 ByteArrayOutputStream 撑爆内存）。 */
    private static final int MAX_MESSAGE_SIZE = 8 * 1024 * 1024; // 8MB
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    // === 状态 ===
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger wsClientCount = new AtomicInteger(0);
    private final AtomicLong echoCounter = new AtomicLong(0);
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private final List<WebSocketConnection> connections = new CopyOnWriteArrayList<>();
    private volatile WebSocketConnection activeApiConnection;
    private volatile java.util.concurrent.ScheduledFuture<?> keepAliveFuture;
    
    /** API调用等待队列：echo → 响应Future */
    private final ConcurrentHashMap<String, CompletableFuture<String>> pendingApiCalls = new ConcurrentHashMap<>();

    /** WebSocket 连接处理线程池：有界队列 + 拒绝策略，防止连接洪峰线程爆炸。 */
    private volatile ExecutorService connPool = createConnPool();

    // === 配置项 ===
    private int port = DEFAULT_PORT;
    private String accessToken = "";

    // === 回调 ===
    private QQMessageHandler messageHandler;

    // === SFW引用 ===
    private String dataDir;

    public OneBotServer() {}

    private static ExecutorService createConnPool() {
        return new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(256),
                new ThreadFactory() {
                    private final AtomicInteger seq = new AtomicInteger(1);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "OneBot-Conn-" + seq.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

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
            AiAgentActivity.qqLog("[OneBot] 服务器已在运行");
            return false;
        }
        try {
            if (connPool == null || connPool.isShutdown()) {
                connPool = createConnPool();
            }
            serverSocket = new ServerSocket(port);
            running.set(true);
            acceptThread = new Thread(this::acceptLoop, "OneBot-Acceptor");
            acceptThread.setDaemon(true);
            acceptThread.start();
            startKeepAlive();
            AiAgentActivity.qqLog("[OneBot] 服务器已启动，端口: " + port);
            return true;
        } catch (IOException e) {
            AiAgentActivity.qqLog("[OneBot] 启动失败: " + e.getMessage());
            return false;
        }
    }

    /** 停止WebSocket服务器 */
    public synchronized void stop() {
        running.set(false);
        stopKeepAlive();
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
        // 关闭连接处理线程池（已提交连接会继续处理完，拒绝新提交）
        ExecutorService pool = connPool;
        if (pool != null) {
            pool.shutdown();
        }
        AiAgentActivity.qqLog("[OneBot] 服务器已停止");
    }

    public boolean isRunning() { return running.get(); }
    public int getConnectionCount() { return wsClientCount.get(); }

    /** 启动主动 ping 保活，避免长连接被中间设备断开。 */
    private void startKeepAlive() {
        if (keepAliveFuture != null) return;
        keepAliveFuture = sair.aiagent.core.ThreadManager.getInstance()
                .newNamedScheduled("OneBot-KeepAlive", 1)
                .scheduleWithFixedDelay(() -> {
                    for (WebSocketConnection c : connections) {
                        if (c.isOpen()) {
                            try { c.sendFrame(0x09, new byte[0]); } catch (Exception ignored) {}
                        }
                    }
                }, 30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private void stopKeepAlive() {
        if (keepAliveFuture != null) {
            keepAliveFuture.cancel(false);
            keepAliveFuture = null;
        }
    }

    // ==================== 接受连接循环 ====================

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                WebSocketConnection conn = new WebSocketConnection(socket);
                connections.add(conn);
                wsClientCount.incrementAndGet();
                connPool.submit(conn::handle);
            } catch (IOException e) {
                if (running.get()) {
                    AiAgentActivity.qqLog("[OneBot] 接受连接错误: " + e.getMessage());
                }
            }
        }
    }

    // ==================== 发送API调用 ====================

    private static final Gson GSON = new Gson();

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
                } else if (v instanceof List || v instanceof Map) {
                    // 使用 Gson 序列化复杂嵌套对象（如 forward 消息的 messages 数组）
                    json.append(GSON.toJson(v));
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
        
        AiAgentActivity.qqLog("[OneBot] 发送API: " + (payload.length() > 200 ? payload.substring(0, 200) + "..." : payload));
        WebSocketConnection target = chooseApiConnection();
        if (target == null) {
            pendingApiCalls.remove(echo);
            AiAgentActivity.qqLog("[OneBot] API调用失败: 没有可用连接");
            return null;
        }
        target.sendText(payload);
        
        // 等待响应（10秒超时）
        try {
            String response = future.get(10, TimeUnit.SECONDS);
            pendingApiCalls.remove(echo);
            return response;
        } catch (TimeoutException e) {
            pendingApiCalls.remove(echo);
            AiAgentActivity.qqLog("[OneBot] API调用超时: " + action);
            return null;
        } catch (Exception e) {
            pendingApiCalls.remove(echo);
            AiAgentActivity.qqLog("[OneBot] API调用异常: " + e.getMessage());
            return null;
        }
    }
    
    /** 选择当前 API 调用应发送到的连接，避免多连接时重复执行。 */
    private WebSocketConnection chooseApiConnection() {
        WebSocketConnection active = activeApiConnection;
        if (active != null && active.isOpen()) return active;
        for (WebSocketConnection c : connections) {
            if (c.isOpen()) return c;
        }
        return null;
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
        String resp = sendApiCall("send_private_msg", params);
        logSendFailure("send_private_msg", userId, resp);
    }

    /** 发送群聊消息 */
    public void sendGroupMsg(long groupId, String message) {
        Map<String, Object> params = new HashMap<>();
        params.put("group_id", groupId);
        params.put("message", message);
        String resp = sendApiCall("send_group_msg", params);
        logSendFailure("send_group_msg", groupId, resp);
    }

    /**
     * 检查消息发送结果：失败（含被禁言/风控/消息过长等）时输出 SFW 控制台日志。
     * <p>成功（status=ok 或 retcode=0）静默返回；超时/异常已在 sendApiCall 记录，这里跳过。</p>
     * <p>用 debugLog 输出，不受 /qqlogoff 开关影响，确保发送失败始终可见。</p>
     */
    private void logSendFailure(String action, long targetId, String resp) {
        if (resp == null || resp.isEmpty()) return;
        try {
            String status = JsonUtil.extractString(resp, "status");
            long retcode = JsonUtil.extractLong(resp, "retcode");
            boolean failed = "failed".equalsIgnoreCase(status) || retcode != 0;
            if (!failed) return;
            String msg = JsonUtil.extractString(resp, "message");
            String wording = JsonUtil.extractString(resp, "wording");
            StringBuilder sb = new StringBuilder();
            sb.append("[OneBot] 消息发送失败: ").append(action).append(", targetId=").append(targetId);
            if (retcode != 0) sb.append(", retcode=").append(retcode);
            if (msg != null && !msg.isEmpty()) sb.append(", message=").append(msg);
            if (wording != null && !wording.isEmpty()) sb.append(", wording=").append(wording);
            String lower = resp.toLowerCase();
            boolean suspectMute = lower.contains("禁言") || lower.contains("mute")
                    || lower.contains("ban") || lower.contains("block");
            // QQNT 内核返回的 result 错误码（sendMsg 场景 result=120 通常是被禁言或触发风控导致发送被拒）
            long ntResult = 0;
            java.util.regex.Matcher rm = java.util.regex.Pattern.compile("\"result\"\\s*:\\s*(\\d+)").matcher(resp);
            if (rm.find()) {
                try { ntResult = Long.parseLong(rm.group(1)); } catch (NumberFormatException ignored) {}
            }
            if (ntResult == 120) suspectMute = true;
            if (suspectMute) {
                sb.append(" ⚠️ 疑似被禁言/风控");
                if (ntResult != 0) sb.append(" (QQNT result=").append(ntResult).append(")");
            }
            AiAgentActivity.debugLog(sb.toString());
        } catch (Exception ignored) {
            // 解析失败不阻塞发送
        }
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
                if (activeApiConnection == this) {
                    activeApiConnection = null;
                }
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

                AiAgentActivity.qqLog("[OneBot] 客户端已连接: " + remoteAddr);
                activeApiConnection = this;

                // 2. 读取帧循环
                readFrames();

            } catch (IOException e) {
                AiAgentActivity.qqLog("[OneBot] 连接错误: " + e.getMessage());
            } finally {
                close();
                connections.remove(this);
                AiAgentActivity.qqLog("[OneBot] 客户端已断开: " + remoteAddr);
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
                AiAgentActivity.qqLog("[OneBot] 缺少 Sec-WebSocket-Key");
                return false;
            }

            // Access Token 认证
            if (accessToken != null && !accessToken.isEmpty()) {
                String expectedAuth = "Bearer " + accessToken;
                if (authHeader == null || !expectedAuth.equals(authHeader)) {
                    AiAgentActivity.qqLog("[OneBot] 认证失败: " + remoteAddr);
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
                // SHA-1 是 JVM 必备算法，理论上不会发生
                return Base64.getEncoder().encodeToString(key.getBytes(StandardCharsets.UTF_8));
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
                    // 关键：这里绝不能 break。break 会让整个读取循环退出 → 连接断开，
                    // 该连接上的所有后续消息全部丢失（OneBot 若不自动重连，Bot 直接变聋）。
                    // 正确做法：把这段 payload 读完并丢弃（保持 WebSocket 帧边界同步），继续处理后面的消息。
                    AiAgentActivity.qqLog("[OneBot] 帧过大(" + payloadLen + " 字节)，丢弃该帧但保持连接");
                    drain(in, payloadLen);
                    messageBuf.reset();
                    continue;
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
                        if (!appendFragment(messageBuf, payload)) break;
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
                        if (!appendFragment(messageBuf, payload)) break;
                        if (fin) {
                            String text = new String(messageBuf.toByteArray(), StandardCharsets.UTF_8);
                            messageBuf.reset();
                            onTextMessage(text);
                        }
                        break;
                    default:
                        AiAgentActivity.qqLog("[OneBot] 未知操作码: " + opcode);
                        break;
                }
            }
        }

        /**
         * 追加分片内容；累计超过 {@link #MAX_MESSAGE_SIZE} 时丢弃整条消息并返回 false。
         * 旧实现无上限，分片洪峰可把 ByteArrayOutputStream 撑到 OOM。
         */
        private boolean appendFragment(ByteArrayOutputStream messageBuf, byte[] payload) {
            if (messageBuf.size() + payload.length > MAX_MESSAGE_SIZE) {
                AiAgentActivity.qqLog("[OneBot] 分片消息累计超过 " + MAX_MESSAGE_SIZE + " 字节，丢弃该条消息");
                messageBuf.reset();
                return false;
            }
            messageBuf.write(payload, 0, payload.length);
            return true;
        }

        /** 从输入流丢弃 n 字节（保持 WebSocket 帧边界同步）。 */
        private void drain(java.io.InputStream in, long n) {
            byte[] tmp = new byte[8192];
            long left = n;
            try {
                while (left > 0) {
                    int r = in.read(tmp, 0, (int) Math.min(tmp.length, left));
                    if (r == -1) return;
                    left -= r;
                }
            } catch (IOException e) {
                AiAgentActivity.qqLog("[OneBot] 丢弃超大帧时连接不可用: " + e.getMessage());
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
            
            AiAgentActivity.qqLog("[OneBot] 收到: " + (text.length() > 200 ? text.substring(0, 200) + "..." : text));
            if (messageHandler != null) {
                try {
                    messageHandler.handleRawMessage(text, this::sendText);
                } catch (Exception e) {
                    AiAgentActivity.qqLog("[OneBot] 消息处理错误: " + e.toString());
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
                        AiAgentActivity.qqLog("[OneBot] 检测到戳一戳: user=" + userId + ", group=" + groupId);

                        // 戳一戳不走 AI 思考：系统直接拦截，@ 戳的人发一个问号
                        if (groupId > 0) {
                            OneBotServer.this.sendGroupMsg(groupId, "[CQ:at,qq=" + userId + "] ？");
                        } else {
                            OneBotServer.this.sendPrivateMsg(userId, "？");
                        }
                        AiAgentActivity.qqLog("[OneBot] 戳一戳已由系统拦截回复（@" + userId + " ？）");
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.qqLog("[OneBot] notice事件处理错误: " + e.toString());
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
                        AiAgentActivity.qqLog("[OneBot] 收到好友申请: userId=" + userId);
                        messageHandler.handleFriendRequest(userId, comment, flag);
                    }
                }
                
                // 群邀请：request_type=group, sub_type=invite
                if ("group".equals(requestType) && "invite".equals(subType)) {
                    long userId = JsonUtil.extractLong(text, "user_id");
                    long groupId = JsonUtil.extractLong(text, "group_id");
                    String flag = JsonUtil.extractString(text, "flag");
                    
                    if (messageHandler != null && flag != null && userId > 0) {
                        AiAgentActivity.qqLog("[OneBot] 收到群邀请: userId=" + userId + ", groupId=" + groupId);
                        messageHandler.handleGroupInviteRequest(userId, groupId, flag);
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.qqLog("[OneBot] request事件处理错误: " + e.toString());
            }
        }

        /** 发送文本帧 */
        synchronized void sendText(String text) {
            if (!open.get() || out == null) return;
            try {
                byte[] data = text.getBytes(StandardCharsets.UTF_8);
                sendFrame(0x01, data);
            } catch (IOException e) {
                AiAgentActivity.qqLog("[OneBot] 发送错误: " + e.getMessage());
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
