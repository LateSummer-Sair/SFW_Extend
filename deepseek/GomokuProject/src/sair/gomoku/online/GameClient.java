package sair.gomoku.online;

import java.io.*;
import java.net.*;

/**
 * 联机游戏客户端 — 连接到已有房间
 */
public class GameClient {

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread listenThread;
    private Thread heartbeatThread;
    private boolean running = false;
    private boolean connected = false;
    private int lastHeartbeatTime;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT = 5;

    private String hostIP;
    private int hostPort;
    private String connectionCode;

    private int myStone = 0; // 由服务器分配

    private ClientCallback callback;

    public interface ClientCallback {
        void onAccepted();
        void onConnected(int myStone);
        void onConnectionFailed(String reason);
        void onDisconnected();
        void onOpponentMove(int row, int col, int stone);
        void onChatReceived(String message);
        void onRestart(int newStone);
        void onGameOver(int winner);
        void onLog(String log);
    }

    public GameClient(String hostIP, int port, String code, ClientCallback callback) {
        this.hostIP = hostIP;
        this.hostPort = port;
        this.connectionCode = code;
        this.callback = callback;
    }

    public void connect() {
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(hostIP, hostPort), 10000);
            setupStreams();
            connected = true;
            running = true;

            // 发送加入请求
            sendMessage(GameProtocol.CMD_JOIN + GameProtocol.SEP + connectionCode);

            // 等待服务器响应
            String response = reader.readLine();
            if (response == null) {
                disconnect();
                if (callback != null) callback.onConnectionFailed("服务器无响应");
                return;
            }

            String[] parts = response.split("\\" + GameProtocol.SEP);
            if (GameProtocol.CMD_ACCEPT.equals(parts[0])) {
                log("连接成功！等待服务器分配颜色...");
                // 连接成功立即通知UI显示棋盘（默认白色）
                if (callback != null) callback.onAccepted();
                startListening();
                startHeartbeat();
            } else if (GameProtocol.CMD_REJECT.equals(parts[0])) {
                String reason = parts.length > 1 ? parts[1] : "未知原因";
                disconnect();
                if (callback != null) callback.onConnectionFailed(reason);
            } else {
                disconnect();
                if (callback != null) callback.onConnectionFailed("服务器返回未知响应");
            }
        } catch (IOException e) {
            log("连接失败: " + e.getMessage());
            if (callback != null) callback.onConnectionFailed(e.getMessage());
        }
    }

    private void setupStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
    }

    private void startListening() {
        listenThread = new Thread(() -> {
            try {
                String line;
                while (running && connected && (line = reader.readLine()) != null) {
                    handleMessage(line);
                }
            } catch (IOException e) {
                if (running) {
                    log("连接断开: " + e.getMessage());
                    connected = false;
                    if (callback != null) callback.onDisconnected();
                    tryReconnect();
                }
            }
        });
        listenThread.setDaemon(true);
        listenThread.start();
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            lastHeartbeatTime = (int)(System.currentTimeMillis() / 1000);
            while (running && connected) {
                try {
                    Thread.sleep(5000);
                    sendMessage(GameProtocol.CMD_PING);
                    int now = (int)(System.currentTimeMillis() / 1000);
                    if (now - lastHeartbeatTime > 15) {
                        log("心跳超时，连接断开");
                        connected = false;
                        if (callback != null) callback.onDisconnected();
                        tryReconnect();
                        break;
                    }
                } catch (InterruptedException ignored) {}
            }
        });
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    private void handleMessage(String message) {
        String[] parts = message.split("\\" + GameProtocol.SEP);
        if (parts.length == 0) return;

        String cmd = parts[0];
        // 心跳/落子/聊天消息不输出日志到SFW控制台，避免刷屏
        if (!GameProtocol.CMD_PING.equals(cmd) && !GameProtocol.CMD_PONG.equals(cmd)
                && !GameProtocol.CMD_MOVE.equals(cmd) && !GameProtocol.CMD_CHAT.equals(cmd)) {
            log("收到: " + message);
        }

        switch (cmd) {
            case GameProtocol.CMD_MOVE:
                handleMove(parts);
                break;
            case GameProtocol.CMD_CHAT:
                handleChat(parts);
                break;
            case GameProtocol.CMD_START:
                handleStart(parts);
                break;
            case GameProtocol.CMD_RESTART:
                handleRestart(parts);
                break;
            case GameProtocol.CMD_GAMEOVER:
                handleGameOver(parts);
                break;
            case GameProtocol.CMD_PING:
                sendMessage(GameProtocol.CMD_PONG);
                lastHeartbeatTime = (int)(System.currentTimeMillis() / 1000);
                break;
            case GameProtocol.CMD_PONG:
                lastHeartbeatTime = (int)(System.currentTimeMillis() / 1000);
                break;
        }
    }

    private void handleMove(String[] parts) {
        if (parts.length >= 4) {
            try {
                int row = Integer.parseInt(parts[1]);
                int col = Integer.parseInt(parts[2]);
                int stone = Integer.parseInt(parts[3]);
                if (callback != null) callback.onOpponentMove(row, col, stone);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void handleChat(String[] parts) {
        if (parts.length >= 2) {
            if (callback != null) callback.onChatReceived(parts[1]);
        }
    }

    private void handleStart(String[] parts) {
        if (parts.length >= 2) {
            myStone = Integer.parseInt(parts[1]);
            log("游戏开始！你是" + (myStone == 1 ? "黑棋" : "白棋"));
            if (callback != null) callback.onConnected(myStone);
        }
    }

    private void handleRestart(String[] parts) {
        if (parts.length >= 2) {
            myStone = Integer.parseInt(parts[1]);
            log("重新开始！你是" + (myStone == 1 ? "黑棋" : "白棋"));
            if (callback != null) callback.onRestart(myStone);
        }
    }

    private void handleGameOver(String[] parts) {
        if (parts.length >= 2) {
            int winner = Integer.parseInt(parts[1]);
            if (callback != null) callback.onGameOver(winner);
        }
    }

    private void tryReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT) {
            log("重连失败，已达最大尝试次数");
            return;
        }
        reconnectAttempts++;
        log("尝试重连... (" + reconnectAttempts + "/" + MAX_RECONNECT + ")");

        new Thread(() -> {
            for (int i = 0; i < 3; i++) {
                try {
                    Thread.sleep(2000);
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(hostIP, hostPort), 10000);
                    setupStreams();
                    sendMessage(GameProtocol.CMD_RECONNECT + GameProtocol.SEP + connectionCode);
                    connected = true;
                    reconnectAttempts = 0;
                    log("重连成功！");
                    startListening();
                    startHeartbeat();
                    if (callback != null) callback.onConnected(myStone);
                    return;
                } catch (Exception e) {
                    log("重连尝试 " + (i + 1) + " 失败");
                }
            }
            log("重连失败");
        }).start();
    }

    /**
     * 发送落子消息
     */
    public void sendMove(int row, int col, int stone) {
        sendMessage(GameProtocol.CMD_MOVE + GameProtocol.SEP + row + GameProtocol.SEP + col + GameProtocol.SEP + stone);
    }

    /**
     * 发送聊天消息
     */
    public void sendChat(String message) {
        sendMessage(GameProtocol.CMD_CHAT + GameProtocol.SEP + message);
    }

    /**
     * 发送准备状态
     */
    public void sendReady(boolean ready) {
        sendMessage(ready ? GameProtocol.CMD_READY : GameProtocol.CMD_UNREADY);
    }

    private void sendMessage(String message) {
        if (writer != null && connected) {
            writer.println(message);
            writer.flush();
        }
    }

    public boolean isConnected() {
        return connected;
    }

    public int getMyStone() {
        return myStone;
    }

    private void log(String msg) {
        if (callback != null) callback.onLog("[Client] " + msg);
    }

    public void disconnect() {
        running = false;
        connected = false;
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }
}
