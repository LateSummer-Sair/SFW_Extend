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
    private static final int MAX_FRAME_SIZE = 256 * 1024; // 256KB
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
                if (payloadLen > MAX_FRAME_SIZE || payloadLen > Integer.MAX_VALUE) {
                    AiAgentActivity.qqLog("[OneBot] 帧过大: " + payloadLen);
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
                        AiAgentActivity.qqLog("[OneBot] 未知操作码: " + opcode);
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
                                
                        // 即时回复：AI分析上下文动态生成
                        String pokeReply = getPokeReply(userId, groupId, messageHandler);
                        if (pokeReply != null && !pokeReply.isEmpty()) {
                            if (groupId > 0) {
                                // 群聊中明确@戳自己的那个人
                                OneBotServer.this.sendGroupMsg(groupId, "[CQ:at,qq=" + userId + "] " + pokeReply);
                            } else {
                                OneBotServer.this.sendPrivateMsg(userId, pokeReply);
                            }
                            AiAgentActivity.qqLog("[OneBot] 戳一戳回复: " + pokeReply);
                        }
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.qqLog("[OneBot] notice事件处理错误: " + e.toString());
            }
        }

        /** AI驱动的戳一戳回复：分析上下文动态生成 */
    private String getPokeReply(long userId, long groupId, sair.aiagent.onebot.QQMessageHandler handler) {
        sair.aiagent.onebot.EmotionStateManager em = handler.getEmotionManager();
        int affection = (em != null) ? em.getAffection(userId) : 0;
        if (em != null) {
            if (em.isBetrayer(userId)) return null;
            if (em.isInRomance() && em.getRomancePartnerId() == userId) return "啊~你又戳我！\uD83D\uDC95";
        }

        // 最近对话上下文
        sair.aiagent.onebot.UnifiedQQMemoryManager mem = handler.getUnifiedMemory();
        String context = buildPokeContext(userId, groupId, mem, handler);
        String selfName = handler.getSelfName();

        // 优先：有该用户最近的非空消息 → AI 结合上下文自然回应（不要只回「空消息」）
        String recentUserMsg = findUserRecentMessage(userId, groupId, mem);
        if (recentUserMsg != null && !recentUserMsg.isEmpty()) {
            String aiReply = aiGeneratePokeReply(selfName, context, recentUserMsg, userId, groupId, handler);
            if (aiReply != null && !aiReply.isEmpty()) return aiReply;
        }

        // 其次：名字出现在上下文中 → AI 自然续聊
        boolean nameInContext = selfName != null && !selfName.isEmpty()
                && context != null && context.contains(selfName);
        if (nameInContext) {
            String aiReply = aiGeneratePokeReply(selfName, context, null, userId, groupId, handler);
            if (aiReply != null && !aiReply.isEmpty()) return aiReply;
        }

        // 兜底：随机困惑/温暖回复
        String[] confused = {"何意味？", "啊？", "嘟嘟？", "啊呀？", "唉？"};
        if (affection >= 500) {
            String[] warm = {"哎呀，别戳了啦~", "咔，戳我干嘛～", "戳戳怪哦！"};
            return warm[new java.util.Random().nextInt(warm.length)];
        }
        return confused[new java.util.Random().nextInt(confused.length)];
    }

    /** 查找该用户最近一条非空消息（群聊查群历史，私聊查会话记录），找不到返回 null。 */
    private String findUserRecentMessage(long userId, long groupId,
            sair.aiagent.onebot.UnifiedQQMemoryManager mem) {
        if (mem == null) return null;
        try {
            if (groupId > 0) {
                java.util.List<String[]> history = mem.getRecentGroupChatHistory(groupId, 30);
                if (history != null) {
                    String uid = String.valueOf(userId);
                    for (int i = history.size() - 1; i >= 0; i--) {
                        String[] m = history.get(i);
                        if (m != null && m.length > 2 && uid.equals(m[0])) {
                            String c = m[2];
                            if (c != null && !c.trim().isEmpty()) return c.trim();
                        }
                    }
                }
            } else {
                java.util.List<String[]> conv = mem.getPrivateConversations(userId, 30);
                if (conv != null) {
                    for (String[] m : conv) {
                        if (m != null && m.length > 1 && "user".equals(m[0])) {
                            String c = m[1];
                            if (c != null && !c.trim().isEmpty()) return c.trim();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Build recent conversation context from unified memory */
    private String buildPokeContext(long userId, long groupId,
            sair.aiagent.onebot.UnifiedQQMemoryManager mem,
            sair.aiagent.onebot.QQMessageHandler handler) {
        if (mem == null) return "";
        StringBuilder sb = new StringBuilder();
        java.util.List<String[]> msgs;
        if (groupId > 0) {
            msgs = mem.getGroupConversations(groupId, 10);
        } else {
            msgs = mem.getPrivateConversations(userId, 10);
        }
        if (msgs == null || msgs.isEmpty()) return "";
        for (String[] m : msgs) {
            String name = (m.length > 5 && m[5] != null && !m[5].isEmpty()) ? m[5]
                    : (m.length > 4 && m[4] != null ? m[4] : "unknown");
            String content = m.length > 1 ? m[1] : "";
            if (content.length() > 200) content = content.substring(0, 200) + "...";
            sb.append(name).append(": ").append(content).append("\n");
        }
        return sb.toString();
    }

    /** Use AI to generate a context-aware poke reply */
    private String aiGeneratePokeReply(String selfName, String context, String recentUserMsg,
            long pokerUserId, long groupId, sair.aiagent.onebot.QQMessageHandler handler) {
        try {
            sair.aiagent.core.DeepSeekClient client = handler.getDeepSeekClient();
            if (client == null) return null;
            String pokerName = resolvePokerName(pokerUserId, groupId, handler);
            String pokerRole = resolvePokerRole(pokerUserId, groupId, handler);
            String prompt = "Someone just poked you in a chat. Reply naturally.\n\n"
                    + "Your name is: " + selfName + "\n"
                    + "The person who poked you: " + pokerName + " (QQ:" + pokerUserId + ") [身份: " + pokerRole + "]\n"
                    + "Recent chat context:\n" + context + "\n";
            if (recentUserMsg != null && !recentUserMsg.isEmpty()) {
                prompt += "\nThe person who poked you recently said: \"" + recentUserMsg + "\"\n"
                        + "Respond to what they said instead of just acting confused.\n";
            }
            prompt += "\nRules:\n"
                    + "- If there is a recent message from this person, respond to it naturally\n"
                    + "- Do NOT say things like \"empty message\" or \"no content\"\n"
                    + "- Keep reply short and friendly (under 30 chars if possible)\n"
                    + "- IMPORTANT: 只有身份是【⭐主人】的人才能称为'主人'，其他人一律用昵称称呼，绝不喊'主人'\n"
                    + "- Output ONLY the reply text, nothing else";
            java.util.List<sair.aiagent.model.ChatMessage> msgs = new java.util.ArrayList<>();
            msgs.add(new sair.aiagent.model.ChatMessage("user", prompt));
            String reply = client.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());
            if (reply != null) {
                reply = reply.trim().replaceAll("^[\"']+|[\"']+$", "");
                if (reply.length() > 80) reply = reply.substring(0, 80);
                if (!reply.isEmpty()) return reply;
            }
        } catch (Exception e) {
            AiAgentActivity.qqLog("[OneBot] AI poke reply failed: " + e.getMessage());
        }
        return null;
    }

    /** 解析戳一戳者的显示名称（群昵称优先，否则回退 QQ 号） */
    private String resolvePokerName(long userId, long groupId,
            sair.aiagent.onebot.QQMessageHandler handler) {
        try {
            sair.aiagent.onebot.UnifiedQQMemoryManager mem = handler.getUnifiedMemory();
            if (mem != null && groupId > 0) {
                java.util.Map<String, Long> nickMap = mem.getGroupNicknameMap(groupId);
                if (nickMap != null) {
                    for (java.util.Map.Entry<String, Long> e : nickMap.entrySet()) {
                        if (e.getValue() == userId) return e.getKey();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "QQ:" + userId;
    }

    /** 解析戳一戳者的身份：⭐主人 / 👑群主 / 🔧管理员 / 普通成员 */
    private String resolvePokerRole(long userId, long groupId,
            sair.aiagent.onebot.QQMessageHandler handler) {
        if (sair.aiagent.core.AiConfig.getInstance().isMasterQQ(userId)) return "⭐主人";
        try {
            sair.aiagent.onebot.UnifiedQQMemoryManager mem = handler.getUnifiedMemory();
            if (mem != null && groupId > 0) {
                java.util.List<String[]> admins = mem.getGroupAdmins(groupId);
                for (String[] a : admins) {
                    if (a.length > 0 && String.valueOf(userId).equals(a[0])) {
                        if (a.length > 2 && "owner".equals(a[2])) return "👑群主";
                        return "🔧管理员";
                    }
                }
            }
        } catch (Exception ignored) {}
        return "普通成员";
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
