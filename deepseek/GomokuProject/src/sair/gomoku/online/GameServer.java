package sair.gomoku.online;

import java.io.*;
import java.net.*;
import java.util.Random;

/**
 * 联机游戏服务端 — 创建房间等待客户端接入
 */
public class GameServer {

    private ServerSocket serverSocket;
    private Socket clientSocket;
    private BufferedReader reader;
    private PrintWriter writer;
    private Thread listenThread;
    private Thread heartbeatThread;
    private boolean running = false;
    private boolean clientConnected = false;

    private int port;
    private String connectionCode;
    private String localIP;

    // 游戏状态
    private boolean hostReady = false;
    private boolean clientReady = false;
    private boolean gameActive = false;
    private int hostStone = 1; // 默认房主黑棋
    private int lastHeartbeatTime;

    private ServerCallback callback;
    private Random random = new Random();

    public interface ServerCallback {
        void onClientConnected(String clientInfo);
        void onClientDisconnected();
        void onClientReady(boolean ready);
        void onClientMove(int row, int col, int stone);
        void onChatReceived(String message);
        void onLog(String log);
    }

    public GameServer(int port, ServerCallback callback) {
        this.port = port;
        this.callback = callback;
        this.connectionCode = generateCode();
        try {
            this.localIP = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            this.localIP = "127.0.0.1";
        }
    }

    private String generateCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setSoTimeout(1000);
        running = true;

        log("房间已创建");
        log("本机IP: " + localIP);
        log("端口号: " + port);
        log("连接码: " + connectionCode);
        log("等待玩家加入...（120秒超时）");

        long startTime = System.currentTimeMillis();
        long timeout = 120000;

        while (running && !clientConnected) {
            if (System.currentTimeMillis() - startTime > timeout) {
                log("等待超时，房间自动关闭");
                stop();
                if (callback != null) callback.onClientDisconnected();
                return;
            }
            try {
                clientSocket = serverSocket.accept();
                clientConnected = true;
                setupStreams();
                log("玩家已连接: " + clientSocket.getInetAddress().getHostAddress());
                if (callback != null) callback.onClientConnected(clientSocket.getInetAddress().getHostAddress());
                startListening();
                startHeartbeat();
            } catch (SocketTimeoutException ignored) {
            }
        }
    }

    private void setupStreams() throws IOException {
        reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
        writer = new PrintWriter(new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"), true);
    }

    private void startListening() {
        listenThread = new Thread(() -> {
            try {
                String line;
                while (running && clientConnected && (line = reader.readLine()) != null) {
                    handleMessage(line);
                }
            } catch (IOException e) {
                if (running) {
                    log("连接断开: " + e.getMessage());
                    clientConnected = false;
                    if (callback != null) callback.onClientDisconnected();
                }
            }
        });
        listenThread.setDaemon(true);
        listenThread.start();
    }

    private void startHeartbeat() {
        heartbeatThread = new Thread(() -> {
            lastHeartbeatTime = (int)(System.currentTimeMillis() / 1000);
            while (running && clientConnected) {
                try {
                    Thread.sleep(5000);
                    sendMessage(GameProtocol.CMD_PING);
                    int now = (int)(System.currentTimeMillis() / 1000);
                    if (now - lastHeartbeatTime > 15) {
                        log("心跳超时，连接断开");
                        clientConnected = false;
                        if (callback != null) callback.onClientDisconnected();
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
            case GameProtocol.CMD_JOIN:
                handleJoin(parts);
                break;
            case GameProtocol.CMD_MOVE:
                handleMove(parts);
                break;
            case GameProtocol.CMD_CHAT:
                handleChat(parts);
                break;
            case GameProtocol.CMD_READY:
                clientReady = true;
                if (callback != null) callback.onClientReady(true);
                checkBothReady();
                break;
            case GameProtocol.CMD_UNREADY:
                clientReady = false;
                if (callback != null) callback.onClientReady(false);
                break;
            case GameProtocol.CMD_PONG:
                lastHeartbeatTime = (int)(System.currentTimeMillis() / 1000);
                break;
            case GameProtocol.CMD_RECONNECT:
                log("客户端请求重连");
                break;
        }
    }

    private void handleJoin(String[] parts) {
        if (parts.length < 2 || !parts[1].equals(connectionCode)) {
            sendMessage(GameProtocol.CMD_REJECT + GameProtocol.SEP + "连接码错误");
            log("客户端连接码错误，已拒绝");
        } else {
            sendMessage(GameProtocol.CMD_ACCEPT);
            log("客户端验证通过");
        }
    }

    private void handleMove(String[] parts) {
        if (parts.length >= 4) {
            try {
                int row = Integer.parseInt(parts[1]);
                int col = Integer.parseInt(parts[2]);
                int stone = Integer.parseInt(parts[3]);
                if (callback != null) callback.onClientMove(row, col, stone);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void handleChat(String[] parts) {
        if (parts.length >= 2) {
            String msg = parts[1];
            if (callback != null) callback.onChatReceived(msg);
        }
    }

    private void checkBothReady() {
        if (hostReady && clientReady && !gameActive) {
            gameActive = true;
            sendMessage(GameProtocol.CMD_START + GameProtocol.SEP + "2"); // 客户端白棋=2
            log("双方准备完毕，游戏开始！");
        }
    }

    public void setHostReady(boolean ready) {
        this.hostReady = ready;
        checkBothReady();
    }

    public void sendMove(int row, int col, int stone) {
        sendMessage(GameProtocol.CMD_MOVE + GameProtocol.SEP + row + GameProtocol.SEP + col + GameProtocol.SEP + stone);
    }

    public void sendChat(String message) {
        sendMessage(GameProtocol.CMD_CHAT + GameProtocol.SEP + message);
    }

    public void sendRestart(int newHostStone) {
        this.hostStone = newHostStone;
        sendMessage(GameProtocol.CMD_RESTART + GameProtocol.SEP + (newHostStone == 1 ? 2 : 1));
        hostReady = false;
        clientReady = false;
        gameActive = false;
    }

    public void sendGameOver(int winner) {
        sendMessage(GameProtocol.CMD_GAMEOVER + GameProtocol.SEP + winner);
        gameActive = false;
    }

    private void sendMessage(String message) {
        if (writer != null && clientConnected) {
            writer.println(message);
            writer.flush();
        }
    }

    public boolean isClientConnected() {
        return clientConnected;
    }

    public int getPort() {
        return port;
    }

    public String getConnectionCode() {
        return connectionCode;
    }

    public String getLocalIP() {
        return localIP;
    }

    public int getHostStone() {
        return hostStone;
    }

    public void setHostStone(int stone) {
        this.hostStone = stone;
    }

    private void log(String msg) {
        if (callback != null) callback.onLog("[Server] " + msg);
    }

    public void stop() {
        running = false;
        clientConnected = false;
        try { if (reader != null) reader.close(); } catch (IOException ignored) {}
        try { if (writer != null) writer.close(); } catch (Exception ignored) {}
        try { if (clientSocket != null) clientSocket.close(); } catch (IOException ignored) {}
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        log("房间已关闭");
    }
}
