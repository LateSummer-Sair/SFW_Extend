package sair.scq.protocol;

/**
 * SCQ消息类型枚举 —— 定义服务端与客户端之间的所有通信消息类型。
 */
public enum MessageType {
    /** 注册 */
    REGISTER,
    /** 登录 */
    LOGIN,
    /** 登出 */
    LOGOUT,
    /** 在线用户列表 */
    USER_LIST,
    /** 更新用户信息(头像/签名/密码) */
    USER_UPDATE,
    /** 私聊消息 */
    PRIVATE_MSG,
    /** 群聊消息 */
    GROUP_MSG,
    /** 创建群组 */
    GROUP_CREATE,
    /** 加入群组 */
    GROUP_JOIN,
    /** 退出群组 */
    GROUP_LEAVE,
    /** 申请入群 */
    JOIN_REQUEST,
    /** 批准入群申请 */
    APPROVE_JOIN,
    /** 拒绝入群申请 */
    REJECT_JOIN,
    /** 踢出成员 */
    GROUP_KICK,
    /** 禁言成员 */
    GROUP_MUTE,
    /** 取消禁言 */
    GROUP_UNMUTE,
    /** 设置管理员 */
    GROUP_SET_ADMIN,
    /** SFW命令中继 */
    COMMAND,
    /** 文件传输请求 */
    FILE_TRANSFER,
    /** 添加好友/群组(by唯一ID) */
    CONTACT_ADD,
    /** 获取通讯录 */
    CONTACT_LIST,
    /** 在线状态变更 */
    STATUS,
    /** 群组列表 */
    GROUP_LIST,
    /** 群组详情 */
    GROUP_INFO,
    /** 好友添加通知（对方已将你添加为好友） */
    CONTACT_ADDED,
    /** 错误响应 */
    ERROR,
    /** 成功响应 */
    OK;

    /**
     * 根据名称字符串查找枚举值，不区分大小写。
     */
    public static MessageType fromString(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
