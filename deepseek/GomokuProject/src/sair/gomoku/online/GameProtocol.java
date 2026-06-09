package sair.gomoku.online;

/**
 * 联机通信协议常量
 */
public class GameProtocol {

    // 消息分隔符
    public static final String SEP = "|";

    // 协议命令
    public static final String CMD_MOVE = "MOVE";         // 落子: MOVE|row|col|stone
    public static final String CMD_JOIN = "JOIN";         // 加入房间: JOIN|code
    public static final String CMD_ACCEPT = "ACCEPT";     // 接受加入: ACCEPT
    public static final String CMD_REJECT = "REJECT";     // 拒绝: REJECT|reason
    public static final String CMD_CHAT = "CHAT";         // 聊天: CHAT|message
    public static final String CMD_READY = "READY";       // 准备: READY
    public static final String CMD_UNREADY = "UNREADY";   // 取消准备
    public static final String CMD_START = "START";       // 开始游戏: START|yourStone
    public static final String CMD_RESTART = "RESTART";   // 重新开始: RESTART|stone（轮换后颜色）
    public static final String CMD_GAMEOVER = "GAMEOVER"; // 游戏结束: GAMEOVER|winner
    public static final String CMD_PING = "PING";         // 心跳
    public static final String CMD_PONG = "PONG";         // 心跳响应
    public static final String CMD_DISCONNECT = "DISCONNECT"; // 断开
    public static final String CMD_INFO = "INFO";         // 服务器信息: INFO|ip|port|code
    public static final String CMD_RECONNECT = "RECONNECT"; // 重连

    // 响应
    public static final String RSP_OK = "OK";
    public static final String RSP_ERR = "ERR";
}
