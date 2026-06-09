package sair.scq.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;

import sair.scq.protocol.SCMessage;
import sair.sys.SairCons;

/**
 * SCQ服务端核心 —— TCP服务端，负责管理客户端连接和消息路由。
 * 
 * <h3>架构</h3>
 * <ul>
 *   <li>ServerSocket 监听指定端口</li>
 *   <li>线程池处理每个客户端连接（每个连接对应一个ClientSession）</li>
 *   <li>提供UserManager和GroupManager的静态访问</li>
 *   <li>管理在线会话映射（UID → ClientSession）</li>
 * </ul>
 */
public class SCServerCore {

    /** 数据目录 */
    private final String dataDir;
    /** 线程池 */
    private ExecutorService executor;
    /** 服务端Socket */
    private ServerSocket serverSocket;
    /** 运行状态 */
    private volatile boolean running = false;
    /** 监听端口 */
    private int port;

    /** 用户管理器 */
    private UserManager userManager;
    /** 群组管理器 */
    private GroupManager groupManager;

    /** 在线会话映射：UID → ClientSession */
    private final ConcurrentHashMap<Long, ClientSession> sessionMap = new ConcurrentHashMap<Long, ClientSession>();

    public SCServerCore(String dataDir) {
        this.dataDir = dataDir;
        this.userManager = new UserManager(dataDir);
        this.groupManager = new GroupManager(dataDir, userManager);
    }

    // ==================== 服务器启停 ====================

    /**
     * 启动服务端。
     * @param port 监听端口
     * @return 是否启动成功
     */
    public synchronized boolean start(int port) {
        if (running) return false;
        this.port = port;

        try {
            serverSocket = new ServerSocket(port);
            executor = new ThreadPoolExecutor(
                20, 120,
                60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(360),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
            );

            running = true;

            // 启动接受连接线程
            new Thread(new Runnable() {
                public void run() {
                    SairCons.println("SCQ服务端已启动，监听端口: " + port);
                    while (running) {
                        try {
                            Socket socket = serverSocket.accept();
                            Thread.sleep(1);
                            ClientSession session = new ClientSession(SCServerCore.this, socket);
                            executor.execute(session);
                            SairCons.println("新客户端连接: " + socket.getInetAddress().getHostAddress());
                        } catch (IOException e) {
                            if (running) {
                                SairCons.println("接受连接异常: " + e.getMessage());
                            }
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
            }, "SCQ-Acceptor").start();

            return true;
        } catch (IOException e) {
            SairCons.println("启动服务端失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * 停止服务端。
     */
    public synchronized void stop() {
        running = false;

        // 断开所有客户端
        for (ClientSession session : sessionMap.values()) {
            try { session.disconnect(); } catch (Exception e) {}
        }
        sessionMap.clear();

        // 关闭线程池
        if (executor != null) {
            executor.shutdownNow();
        }

        // 关闭ServerSocket
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException e) {}
        }

        SairCons.println("SCQ服务端已停止");
    }

    /** 服务端是否在运行 */
    public boolean isRunning() { return running; }

    /** 获取监听端口 */
    public int getPort() { return port; }

    // ==================== 会话管理（参照nsc的socketList模式）====================

    /** 注册会话 */
    public void registerSession(long uid, ClientSession session) {
        sessionMap.put(uid, session);
    }

    /** 注销会话 */
    public void unregisterSession(long uid) {
        sessionMap.remove(uid);
    }

    /** 获取会话 */
    public ClientSession getSession(long uid) {
        return sessionMap.get(uid);
    }

    /** 获取所有会话 */
    public Collection<ClientSession> getAllSessions() {
        return sessionMap.values();
    }

    /**
     * 判断用户是否在线（参照nsc：直接检查socket状态）。
     * 不依赖UserInfo.isOnline布尔标志，而是检查sessionMap中是否有活跃的socket连接。
     */
    public boolean isUserOnline(long uid) {
        ClientSession session = sessionMap.get(uid);
        return session != null && isSessionActive(session);
    }

    /**
     * 判断会话的socket连接是否活跃（参照nsc：socket.isConnected() && !socket.isClosed()）。
     */
    public boolean isSessionActive(ClientSession session) {
        if (session == null) return false;
        Socket s = session.getSocket();
        return s != null && s.isConnected() && !s.isClosed();
    }

    /**
     * 获取所有在线用户UID列表（参照nsc的getSocketListSize）。
     */
    public List<Long> getOnlineUIDs() {
        List<Long> list = new ArrayList<Long>();
        for (Long uid : sessionMap.keySet()) {
            if (isUserOnline(uid)) {
                list.add(uid);
            }
        }
        return list;
    }

    /** 获取在线用户数 */
    public int getOnlineCount() {
        return getOnlineUIDs().size();
    }

    /**
     * 踢掉旧会话（同一用户新登录时，参照nsc setLinkedClientClose）。
     */
    public void kickOldSession(long uid, ClientSession newSession) {
        ClientSession oldSession = sessionMap.get(uid);
        if (oldSession != null && oldSession != newSession) {
            SairCons.println("踢掉旧连接: UID=" + uid);
            oldSession.disconnect();
        }
    }

    // ==================== 管理器访问 ====================

    public UserManager getUserManager() { return userManager; }
    public GroupManager getGroupManager() { return groupManager; }
    public String getDataDir() { return dataDir; }

    // ==================== 广播 ====================

    /** 广播消息给所有在线用户（参照nsc：检查socket实际连接状态） */
    public void broadcast(SCMessage msg) {
        for (ClientSession session : sessionMap.values()) {
            if (isSessionActive(session) && session.getUser() != null) {
                session.sendMessage(msg);
            }
        }
    }
}
