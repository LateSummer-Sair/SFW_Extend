package sair.aiagent.core;

/**
 * 工具执行上下文 —— 承载工具执行所需的通道标识与 QQ 上下文。
 * <p>
 * Function Calling 工具执行时，本地通道（console）与 QQ 通道（execq）
 * 需要不同的上下文信息。本地通道仅需 channel 标识；QQ 通道还需
 * NapCatApi、QQMessage、UnifiedQQMemoryManager、InternalAgents 等，
 * 供群管/媒体类工具（ban/kick/sendimage/relay 等）执行时使用。
 * </p>
 */
public class ToolContext {

    /** 通道标识：console（本地）或 execq（QQ） */
    public String channel = "console";

    // === QQ 通道上下文（console 通道为 null） ===
    public sair.aiagent.onebot.model.QQMessage qqMsg;
    public sair.aiagent.onebot.NapCatApi napcatApi;
    public sair.aiagent.onebot.UnifiedQQMemoryManager unifiedMemory;
    public sair.aiagent.onebot.InternalAgents internalAgents;

    /** 发送者 QQ 号 */
    public long senderQQ;
    /** 发送者好感度 */
    public int affection;
    /** 发送者是否为主人 */
    public boolean isMaster;
    /** 数据目录（用于媒体渲染/文件落盘） */
    public String dataDir;
    /** 待处理请求池（好友申请/群邀请，供 pendingrequests/approvefriend 等工具使用） */
    public sair.aiagent.onebot.PendingRequestPool pendingRequestPool;
    /** 情绪状态管理器（供 recorddonation 等好感度/捐赠类工具使用） */
    public sair.aiagent.onebot.EmotionStateManager emotionManager;

    // === 上下文快照（供 alarm 工具持久化，到点重建执行上下文用） ===
    /** 稳定 system 前缀（角色/能力清单，缓存命中点） */
    public String stableSystemPrompt;
    /** 动态上下文（历史/记忆/情绪等，下沉 user 消息） */
    public String dynamicContext;
    /** 当前使用的模型名 */
    public String model;
    /** 是否为 execs 全权限链路（QQ 通道 execs: 关键字），用于区分 execq 受限与 execs 全权限 */
    public boolean execsMode = false;

    public ToolContext() {
        this("console");
    }

    public ToolContext(String channel) {
        this.channel = (channel != null && !channel.isEmpty()) ? channel : "console";
    }

    /** 是否为 QQ 通道 */
    public boolean isExecq() {
        return "execq".equals(channel);
    }
}
