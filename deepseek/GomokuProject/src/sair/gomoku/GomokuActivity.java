package sair.gomoku;

import javax.swing.SwingUtilities;

import sair.gomoku.ai.GomokuAI;
import sair.gomoku.game.Board;
import sair.gomoku.online.GameClient;
import sair.gomoku.online.GameServer;
import sair.gomoku.ui.CreateRoomPanel;
import sair.gomoku.ui.DifficultySelectPanel;
import sair.gomoku.ui.GameBoardPanel;
import sair.gomoku.ui.GamePanel;
import sair.gomoku.ui.JoinRoomPanel;
import sair.gomoku.ui.ModeSelectPanel;
import sair.gomoku.ui.OnlineSelectPanel;
import sair.sys.gui.ConsFrame;
import sair.sys.SairCons;
import sair.user.Activity;

/**
 * 五子棋游戏主Activity
 *
 * 流程：
 *   模式选择 → (单人:选难度+颜色) / (双人:直接开局) / (联机:创建/加入房间) → 渲染棋盘对战
 */
public class GomokuActivity extends Activity {

    // === 各阶段面板 ===
    private ModeSelectPanel modePanel;
    private DifficultySelectPanel diffPanel;
    private OnlineSelectPanel onlinePanel;
    private CreateRoomPanel createRoomPanel;
    private JoinRoomPanel joinRoomPanel;
    private GameBoardPanel gameBoardPanel;

    // === 游戏核心 ===
    private GomokuAI ai;
    private GameServer server;
    private GameClient client;

    // === 联机状态 ===
    private int onlineRole = 0; // 0=无, 1=房主, 2=客户端
    private String storedIp = "";
    private int storedPort = 8063;
    private String storedCode = "";

    public GomokuActivity() {}

    @Override
    public Object main(String funcName, String args) {
        switch (funcName) {
            case "g":
            case "start":
                return showModeSelect();
            case "create":
                return cmdCreateRoom(args);
            case "connect":
                return cmdConnectRoom(args);
            case "stop":
                return stopGame();
            default:
                return false;
        }
    }

    /** /g create <端口号> — 直接创建房间跳过菜单 */
    private boolean cmdCreateRoom(String args) {
        int port = 8063; // 默认
        if (args != null && !args.trim().isEmpty()) {
            try { port = Integer.parseInt(args.trim()); }
            catch (NumberFormatException e) {
                SairCons.println("端口号格式错误，使用默认端口 8063");
            }
        }
        showCreateRoom(port);
        return true;
    }

    /** /g connect <IP> <端口> <连接码> — 直接连接房间跳过菜单 */
    private boolean cmdConnectRoom(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println("用法: /g connect <IP> <端口号> <连接码>");
            return false;
        }
        String[] parts = args.trim().split("\\s+");
        if (parts.length < 3) {
            SairCons.println("参数不足，用法: /g connect <IP> <端口号> <连接码>");
            return false;
        }
        String ip = parts[0];
        int port;
        try { port = Integer.parseInt(parts[1]); }
        catch (NumberFormatException e) {
            SairCons.println("端口号格式错误");
            return false;
        }
        String code = parts[2];
        stopOnline();
        doConnect(ip, port, code);
        return true;
    }

    // ============== 第一步：模式选择 ==============

    private boolean showModeSelect() {
        stopOnline();
        if (modePanel == null) {
            modePanel = new ModeSelectPanel();
            modePanel.setOnSingle(e -> showDifficultySelect());
            modePanel.setOnDual(e -> startDualGame());
            modePanel.setOnOnline(e -> showOnlineSelect());
        }
        renderPanel(modePanel);
        SairCons.println("五子棋 — 请选择模式");
        return true;
    }

    // ============== 单人模式：第二步选难度 ==============

    private void showDifficultySelect() {
        if (diffPanel == null) {
            diffPanel = new DifficultySelectPanel();
            diffPanel.setOnStart(e -> {
                int diff = diffPanel.getSelectedDifficulty();
                int color = diffPanel.getSelectedColor();
                startSingleGame(diff, color);
            });
            diffPanel.setOnBack(e -> showModeSelect());
        }
        renderPanel(diffPanel);
    }

    private void startSingleGame(int difficulty, int playerStone) {
        stopOnline();

        GameBoardPanel board = getBoardPanel();
        board.setOnlineMode(false);
        board.getGamePanel().resetGame();
        board.getGamePanel().setGameMode(GamePanel.MODE_SINGLE);
        board.getGamePanel().setPlayerStone(playerStone);

        ai = new GomokuAI(difficulty);
        board.getGamePanel().setAi(ai);

        String diffStr = difficulty == GomokuAI.EASY ? "初级" : (difficulty == GomokuAI.MEDIUM ? "中级" : "高级");
        board.setStatus("单人模式 - " + diffStr + " - 你是" + (playerStone == Board.BLACK ? "黑棋" : "白棋"));

        // 白棋时AI先手
        if (playerStone == Board.WHITE) {
            SwingUtilities.invokeLater(() -> {
                GamePanel gp = board.getGamePanel();
                int[] m = ai.getBestMove(gp.getBoard(), Board.BLACK);
                gp.getBoard().placeStone(m[0], m[1], Board.BLACK);
                gp.setCurrentStone(Board.WHITE);
                gp.setPlayerTurn(true);
                gp.repaint();
                board.setStatus("轮到你了（白棋）");
            });
        }

        renderPanel(board);
        SairCons.println("单人模式启动 - 难度:" + diffStr);
    }

    // ============== 双人本地模式 ==============

    private void startDualGame() {
        stopOnline();

        GameBoardPanel board = getBoardPanel();
        board.setOnlineMode(false);
        board.getGamePanel().resetGame();
        board.getGamePanel().setGameMode(GamePanel.MODE_DUAL_LOCAL);
        board.getGamePanel().setPlayerStone(Board.BLACK);
        board.getGamePanel().setPlayerTurn(true);
        ai = null;
        board.setStatus("双人模式 - 黑棋先手");

        renderPanel(board);
        SairCons.println("双人本地模式启动");
    }

    // ============== 联机：第二步选择创建/加入 ==============

    private void showOnlineSelect() {
        if (onlinePanel == null) {
            onlinePanel = new OnlineSelectPanel();
            onlinePanel.setOnCreate(e -> showCreateRoom(8063));
            onlinePanel.setOnJoin(e -> showJoinRoom());
            onlinePanel.setOnBack(e -> showModeSelect());
        }
        renderPanel(onlinePanel);
    }

    // ============== 创建房间 ==============

    private void showCreateRoom(int port) {
        createRoomPanel = new CreateRoomPanel();
        createRoomPanel.setOnBack(e -> {
            stopOnline();
            showOnlineSelect();
        });

        stopOnline();
        renderPanel(createRoomPanel);

        new Thread(() -> {
            try {
                server = new GameServer(port, new GameServer.ServerCallback() {
                    @Override
                    public void onClientConnected(String clientInfo) {
                        SwingUtilities.invokeLater(() -> {
                            createRoomPanel.setStatus("对手已连接！准备开始游戏...");
                            onlineRole = 1;
                            startOnlineHostGame();
                        });
                    }
                    @Override
                    public void onClientDisconnected() {
                        SwingUtilities.invokeLater(() -> {
                            if (gameBoardPanel != null) gameBoardPanel.setStatus("对手断开连接");
                        });
                    }
                    @Override
                    public void onClientReady(boolean ready) {
                        SwingUtilities.invokeLater(() -> {
                            if (gameBoardPanel != null) {
                                gameBoardPanel.getChatPanel().appendMessage("[系统] 对手" + (ready ? "已准备" : "取消准备"));
                                if (ready) {
                                    gameBoardPanel.getGamePanel().setPlayerTurn(true);
                                    gameBoardPanel.setStatus("游戏开始！你是黑棋，请落子");
                                }
                            }
                        });
                    }
                    @Override
                    public void onClientMove(int row, int col, int stone) {
                        SwingUtilities.invokeLater(() -> {
                            if (gameBoardPanel != null) {
                                gameBoardPanel.getGamePanel().placeRemoteStone(row, col, stone);
                                gameBoardPanel.getGamePanel().setPlayerTurn(true);
                                gameBoardPanel.setStatus("轮到你了");
                                if (gameBoardPanel.getGamePanel().isGameOver()) {
                                    int w = gameBoardPanel.getGamePanel().getBoard().checkWin(row, col) ? stone : 0;
                                    gameBoardPanel.setStatus(w == 0 ? "平局！" : (w == Board.BLACK ? "黑棋获胜！" : "白棋获胜！"));
                                    server.sendGameOver(w);
                                }
                            }
                        });
                    }
                    @Override
                    public void onChatReceived(String message) {
                        SwingUtilities.invokeLater(() -> {
                            if (gameBoardPanel != null) gameBoardPanel.getChatPanel().appendMessage("对手: " + message);
                        });
                    }
                    @Override
                    public void onLog(String log) {
                        SairCons.println(log);
                    }
                });

                SwingUtilities.invokeLater(() -> {
                    createRoomPanel.setRoomInfo(server.getLocalIP(), server.getPort(), server.getConnectionCode());
                    createRoomPanel.setStatus("等待玩家加入...（120秒超时）");
                });

                server.start();
            } catch (Exception e) {
                SairCons.println("创建房间失败: " + e.getMessage());
                showOnlineSelect();
            }
        }).start();
    }

    private void startOnlineHostGame() {
        GameBoardPanel board = getBoardPanel();
        board.setOnlineMode(true);
        board.getGamePanel().resetGame();
        board.getGamePanel().setGameMode(GamePanel.MODE_ONLINE);
        board.getGamePanel().setPlayerStone(Board.BLACK);
        board.getGamePanel().setPlayerTurn(false);
        board.setStatus("对手已连接！双方点击「准备」开始");

        board.getChatPanel().appendMessage("[系统] 房间已创建，对手已连接");
        board.getChatPanel().appendMessage("[系统] IP: " + server.getLocalIP() + " 端口: " + server.getPort() + " 码: " + server.getConnectionCode());

        renderPanel(board);
    }

    // ============== 加入房间 ==============

    private void showJoinRoom() {
        joinRoomPanel = new JoinRoomPanel();
        joinRoomPanel.setOnConnect(e -> {
                String ip = joinRoomPanel.getIP();
                int port = joinRoomPanel.getPort();
                String code = joinRoomPanel.getCode();

                if (port <= 0 || code.isEmpty()) {
                    joinRoomPanel.setStatus("请填写完整信息");
                    return;
                }
                joinRoomPanel.setStatus("正在连接...");
                doConnect(ip, port, code);
        });
        joinRoomPanel.setOnBack(e -> { stopOnline(); showOnlineSelect(); });

        stopOnline();
        joinRoomPanel.setStatus(" ");
        renderPanel(joinRoomPanel);
    }

    private void doConnect(String ip, int port, String code) {
        new Thread(() -> {
            client = new GameClient(ip, port, code, new GameClient.ClientCallback() {
                @Override
                public void onAccepted() {
                    // 连接验证通过，立即显示棋盘（默认白色，等待START分配颜色）
                    SwingUtilities.invokeLater(() -> {
                        onlineRole = 2;
                        GameBoardPanel board = getBoardPanel();
                        board.setOnlineMode(true);
                        board.getGamePanel().resetGame();
                        board.getGamePanel().setGameMode(GamePanel.MODE_ONLINE);
                        board.getGamePanel().setPlayerStone(Board.WHITE);
                        board.getGamePanel().setPlayerTurn(false);
                        board.setStatus("已连接！你是白棋，点击「准备」");
                        board.getChatPanel().appendMessage("[系统] 连接成功，等待房主分配颜色...");
                        renderPanel(board);
                    });
                }
                @Override
                public void onConnected(int myStone) {
                    // START命令收到，更新颜色并启用游戏
                    SwingUtilities.invokeLater(() -> {
                        if (gameBoardPanel != null) {
                            gameBoardPanel.getGamePanel().setPlayerStone(myStone);
                            gameBoardPanel.getGamePanel().setPlayerTurn(myStone == Board.BLACK);
                            gameBoardPanel.setStatus("游戏开始！你是" + (myStone == Board.BLACK ? "黑棋" : "白棋") + "，请落子");
                            gameBoardPanel.getChatPanel().appendMessage("[系统] 双方准备完毕，游戏开始！你是" + (myStone == Board.BLACK ? "黑棋（先手）" : "白棋（后手）"));
                        }
                    });
                }
                @Override
                public void onConnectionFailed(String reason) {
                    SwingUtilities.invokeLater(() -> {
                        if (joinRoomPanel != null) joinRoomPanel.setStatus("连接失败: " + reason);
                        SairCons.println("连接失败: " + reason);
                    });
                }
                @Override
                public void onDisconnected() {
                    SwingUtilities.invokeLater(() -> {
                        if (gameBoardPanel != null) {
                            gameBoardPanel.setStatus("与服务器断开连接");
                            gameBoardPanel.getChatPanel().appendMessage("[系统] 连接断开，尝试重连...");
                        }
                    });
                }
                @Override
                public void onOpponentMove(int row, int col, int stone) {
                    SwingUtilities.invokeLater(() -> {
                        if (gameBoardPanel != null) {
                            gameBoardPanel.getGamePanel().placeRemoteStone(row, col, stone);
                            gameBoardPanel.getGamePanel().setPlayerTurn(true);
                            gameBoardPanel.setStatus("轮到你了");
                            if (gameBoardPanel.getGamePanel().isGameOver()) {
                                int w = gameBoardPanel.getGamePanel().getBoard().checkWin(row, col) ? stone : 0;
                                gameBoardPanel.setStatus(w == 0 ? "平局！" : (w == Board.BLACK ? "黑棋获胜！" : "白棋获胜！"));
                            }
                        }
                    });
                }
                @Override
                public void onChatReceived(String message) {
                    SwingUtilities.invokeLater(() -> {
                        if (gameBoardPanel != null) gameBoardPanel.getChatPanel().appendMessage("对手: " + message);
                    });
                }
                @Override
                public void onRestart(int newStone) {
                    SwingUtilities.invokeLater(() -> {
                        if (gameBoardPanel != null) {
                            gameBoardPanel.getGamePanel().resetGame();
                            gameBoardPanel.getGamePanel().setPlayerStone(newStone);
                            gameBoardPanel.getGamePanel().setPlayerTurn(newStone == Board.BLACK);
                            gameBoardPanel.setStatus("重新开始！你是" + (newStone == Board.BLACK ? "黑棋" : "白棋") + "，点击「准备」");
                            gameBoardPanel.getChatPanel().appendMessage("[系统] 重新开始，你是" + (newStone == Board.BLACK ? "黑棋" : "白棋"));
                        }
                    });
                }
                @Override
                public void onGameOver(int winner) {
                    SwingUtilities.invokeLater(() -> {
                        if (gameBoardPanel != null) {
                            String msg = winner == 0 ? "平局！" : (winner == Board.BLACK ? "黑棋获胜！" : "白棋获胜！");
                            gameBoardPanel.setStatus(msg + " 点击「重来」继续");
                            gameBoardPanel.getChatPanel().appendMessage("[系统] " + msg);
                        }
                    });
                }
                @Override
                public void onLog(String log) { SairCons.println(log); }
            });
            client.connect();
        }).start();
    }

    // ============== 棋盘面板 ==============

    private GameBoardPanel getBoardPanel() {
        gameBoardPanel = new GameBoardPanel();

        gameBoardPanel.getGamePanel().setMoveListener((row, col) -> {
                GamePanel gp = gameBoardPanel.getGamePanel();
                if (onlineRole == 1 && server != null && server.isClientConnected()) {
                    int stone = gp.getPlayerStone();
                    server.sendMove(row, col, stone);
                    gp.setPlayerTurn(false);
                    gameBoardPanel.setStatus("等待对手落子...");
                } else if (onlineRole == 2 && client != null && client.isConnected()) {
                    int stone = gp.getPlayerStone();
                    client.sendMove(row, col, stone);
                    gp.setPlayerTurn(false);
                    gameBoardPanel.setStatus("等待对手落子...");
                } else if (gp.getGameMode() == GamePanel.MODE_DUAL_LOCAL) {
                    gameBoardPanel.setStatus(gp.getCurrentStone() == Board.BLACK ? "黑棋落子" : "白棋落子");
                } else if (gp.getGameMode() == GamePanel.MODE_SINGLE) {
                    if (!gp.isGameOver()) {
                        gameBoardPanel.setStatus("轮到你了");
                    }
                }

                if (gp.isGameOver()) {
                    int wr = gp.getWinRow(), wc = gp.getWinCol();
                    if (wr >= 0 && wc >= 0) {
                        int winner = gp.getBoard().getStone(wr, wc);
                        gameBoardPanel.setStatus(winner == Board.BLACK ? "黑棋获胜！" : "白棋获胜！");
                        if (onlineRole == 1 && server != null) server.sendGameOver(winner);
                    } else {
                        gameBoardPanel.setStatus("平局！");
                        if (onlineRole == 1 && server != null) server.sendGameOver(0);
                    }
                }
            });

            gameBoardPanel.setOnReady(e -> {
                if (onlineRole == 1 && server != null) {
                    server.setHostReady(true);
                    gameBoardPanel.setStatus("已准备，等待对手...");
                    gameBoardPanel.getChatPanel().appendMessage("[系统] 你已准备");
                    if (server.isClientConnected()) {
                        gameBoardPanel.getGamePanel().setPlayerTurn(true);
                        gameBoardPanel.setStatus("游戏开始！你是黑棋，请落子");
                    }
                } else if (onlineRole == 2 && client != null) {
                    client.sendReady(true);
                    gameBoardPanel.setStatus("已准备，等待房主...");
                    gameBoardPanel.getChatPanel().appendMessage("[系统] 你已准备");
                }
            });

            gameBoardPanel.setOnRestart(e -> {
                if (onlineRole == 1 && server != null) {
                    int newStone = server.getHostStone() == Board.BLACK ? Board.WHITE : Board.BLACK;
                    server.setHostStone(newStone);
                    server.sendRestart(newStone);
                    gameBoardPanel.getGamePanel().resetGame();
                    gameBoardPanel.getGamePanel().setPlayerStone(newStone);
                    gameBoardPanel.getGamePanel().setPlayerTurn(newStone == Board.BLACK);
                    gameBoardPanel.setStatus("重新开始！你是" + (newStone == Board.BLACK ? "黑棋" : "白棋") + "，点击「准备」");
                    gameBoardPanel.getChatPanel().appendMessage("[系统] 重新开始，你是" + (newStone == Board.BLACK ? "黑棋" : "白棋"));
                    server.setHostReady(false);
                } else {
                    gameBoardPanel.getGamePanel().resetGame();
                    if (ai != null) {
                        gameBoardPanel.getGamePanel().setAi(ai);
                        if (gameBoardPanel.getGamePanel().getPlayerStone() == Board.WHITE) {
                            gameBoardPanel.getGamePanel().setPlayerTurn(false);
                            SwingUtilities.invokeLater(() -> {
                                int[] m = ai.getBestMove(gameBoardPanel.getGamePanel().getBoard(), Board.BLACK);
                                gameBoardPanel.getGamePanel().getBoard().placeStone(m[0], m[1], Board.BLACK);
                                gameBoardPanel.getGamePanel().setCurrentStone(Board.WHITE);
                                gameBoardPanel.getGamePanel().setPlayerTurn(true);
                                gameBoardPanel.getGamePanel().repaint();
                            });
                        }
                    }
                    gameBoardPanel.setStatus("已重置");
                }
            });

            gameBoardPanel.setOnBack(e -> {
                stopOnline();
                showModeSelect();
            });

            gameBoardPanel.setChatListener(msg -> {
                if (onlineRole == 1 && server != null) server.sendChat(msg);
                else if (onlineRole == 2 && client != null) client.sendChat(msg);
                else gameBoardPanel.getChatPanel().appendMessage("[系统] 非联机模式不支持聊天");
            });

        return gameBoardPanel;
    }

    // ============== 工具方法 ==============

    private void renderPanel(javax.swing.JPanel panel) {
        SwingUtilities.invokeLater(() -> {
            ConsFrame.printComponent(panel);
        });
    }

    private boolean stopGame() {
        stopOnline();
        showModeSelect();
        SairCons.println("游戏已停止，返回模式选择");
        return true;
    }

    private void stopOnline() {
        if (server != null) { server.stop(); server = null; }
        if (client != null) { client.disconnect(); client = null; }
        onlineRole = 0;
    }

    @Override
    public String[] help() {
        return new String[]{
            "===== 五子棋游戏 =====",
            "/g start  - 打开模式选择界面",
            "/g stop   - 停止游戏返回主菜单",
            "===== 操作流程 =====",
            "单人：/g start → 点击「单人模式」→ 选难度+颜色 → 开始游戏",
            "双人：/g start → 点击「双人本地对弈」→ 开始游戏",
            "/g create [端口号]  - 快速创建房间（默认8063）",
            "/g connect <IP> <端口> <码> - 快速连接房间",
            "联机房主：/g create 8063  或  /g start → 「联机」→ 「创建房间」",
            "联机客户端：/g connect IP 端口 码  或  /g start → 「联机」→ 「加入房间」",
        };
    }

    @Override
    public void exit() { stopOnline(); }

    @Override
    protected String dataDir() { return "Gomoku"; }
}
