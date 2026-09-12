package sair.aiagent.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能运行时上下文投影 —— 注入给 {@code airun} 的「框架侧参数」。
 *
 * <h3>为什么需要它</h3>
 * <p>
 * 内嵌代码的编译/运行 classpath 包含 ai.jar 自身，所以技能能直接调 {@code PersistenceManager}
 * 这类单例；但<b>拿不到请求上下文</b>（谁在哪个群问了这句话、他是不是主人、好感度多少）。
 * 结果就是「依赖上下文的工具没法剥离到 .md」—— NapCat 群管/媒体、跨群、消息 Mark 都卡在这里。
 * 本类把 {@link ToolContext} 里<b>该给技能看的部分</b>投影成一份纯数据，所有语言都能直接用
 * （Java 拿 Map、JS 拿对象、Python 拿 dict）。
 * </p>
 *
 * <h3>只给数据，不给句柄</h3>
 * <p>
 * 刻意<b>不注入</b> {@code napcatApi} / {@code unifiedMemory} 这类可写句柄：那等于把「以 Bot 身份
 * 在任意群发消息」的能力交给一个 .md 文件。技能需要写操作时，应当调用内置工具已有的能力面
 * （或后续单独设计受控的写接口），而不是直接握句柄。
 * </p>
 *
 * <h3>启用方式</h3>
 * <p>技能在 front matter 声明 {@code context: true}；未声明时 {@code airun} 只收到模型填的参数
 * （与旧协议完全一致，零回归）。</p>
 */
public final class SkillContext {

    private SkillContext() {}

    /** 投影字段名（同时是 JS 对象属性名 / Python dict 键名）。 */
    public static final String K_CHANNEL    = "channel";
    public static final String K_IS_GROUP   = "is_group";
    public static final String K_GROUP_ID   = "group_id";
    public static final String K_USER_ID    = "user_id";
    public static final String K_USER_NAME  = "user_name";
    public static final String K_IS_MASTER  = "is_master";
    public static final String K_AFFECTION  = "affection";
    public static final String K_SELF_ID    = "self_id";
    public static final String K_SKILL_NAME = "skill_name";
    public static final String K_SOURCE_FILE= "source_file";
    public static final String K_DATA_DIR   = "data_dir";
    public static final String K_NOW        = "now";
    public static final String K_CALL_ID    = "call_id";

    /**
     * 构建投影。
     *
     * @param ctx        请求上下文（可为 null —— 控制台/后台调用时）
     * @param skillName  当前技能名
     * @param sourceFile 技能 .md 绝对路径
     * @param dataDir    插件数据目录（可能为 null）
     */
    public static Map<String, Object> build(ToolContext ctx, String skillName,
                                            String sourceFile, String dataDir) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean isGroup = ctx != null && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage();
        m.put(K_CHANNEL, ctx != null ? (ctx.execsMode ? "execs" : ctx.channel) : "console");
        m.put(K_IS_GROUP, isGroup);
        m.put(K_GROUP_ID, isGroup ? ctx.qqMsg.getGroupId() : 0L);
        m.put(K_USER_ID, ctx != null ? ctx.senderQQ : 0L);
        m.put(K_USER_NAME, isGroup ? ctx.qqMsg.getDisplayName() : "");
        m.put(K_IS_MASTER, ctx != null && ctx.isMaster);
        m.put(K_AFFECTION, ctx != null ? ctx.affection : 0);
        // Bot 自己的 QQ 号：来自配置（AiConfig.onebotSelfId），控制台/未设置时为 0
        long selfId = 0L;
        try {
            selfId = AiConfig.getInstance().getOnebotSelfId();
        } catch (Exception ignored) {}
        m.put(K_SELF_ID, selfId);
        m.put(K_SKILL_NAME, skillName == null ? "" : skillName);
        m.put(K_SOURCE_FILE, sourceFile == null ? "" : sourceFile);
        m.put(K_DATA_DIR, dataDir == null ? "" : dataDir);
        m.put(K_NOW, System.currentTimeMillis());
        m.put(K_CALL_ID, Long.toHexString(System.nanoTime()));
        return m;
    }
}
