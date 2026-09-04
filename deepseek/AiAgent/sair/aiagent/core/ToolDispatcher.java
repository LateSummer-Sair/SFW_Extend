package sair.aiagent.core;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.AgentAction;
import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.ThirdPartySkill;
import sair.aiagent.model.ToolDefinition;
import sair.sys.Libraries;
import sair.sys.gui.ConsFrame;
import sair.user.Activity;

/**
 * 工具分发器 —— 统一 Function Calling 工具注册表与执行入口。
 * <p>
 * 将原本散落在 {@link AgentActionHandler}、{@link TagExecutor} 中的
 * 执行逻辑，统一收敛为「工具名 → ToolDefinition + 执行」的单一入口。
 * 底层复用 {@link AgentActionHandler} 的工具实现，避免重复代码。
 * </p>
 */
public class ToolDispatcher {

    /** 可安全并行执行的只读工具（本地与 QQ 通用）。 */
    private static final java.util.Set<String> READONLY_TOOLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "readfile", "readdir", "findfile", "web", "search", "weather", "time", "vision",
            "searchnote", "searchglobal", "skillinfo", "balance", "grouplist", "groupmembers",
            "friendlist", "pendingrequests", "getselfinfo", "donationlist", "queryaffection",
            "affectionrank"));

    /** 判断工具是否只读、可安全并行。 */
    public static boolean isReadonlyTool(String toolName) {
        return toolName != null && READONLY_TOOLS.contains(toolName);
    }

    /** 有副作用/有状态、必须串行执行的工具。 */
    private static final java.util.Set<String> STATEFUL_TOOLS = new java.util.HashSet<>(java.util.Arrays.asList(
            "cmd", "sys", "evaljs", "eval", "download", "batchrename", "batchconvert",
            "remember", "correct", "note", "schedule", "sendimage", "sendrecord", "sendfile",
            "editprompt", "superise", "stop", "skillextract", "thirdskill", "callskill",
            "alarm", "markmessage", "setimageremark", "setname", "setsignature", "settrigger",
            "sendsticker", "collectsticker", "clearsticker", "ban", "kick", "muteall",
            "setadmin", "setcard", "setgroupname", "leavegroup", "block", "unblock", "delfriend",
            "poke", "sendlike", "sendfileto", "relay", "forwardmsg", "sendgroupmsg",
            "approvefriend", "rejectfriend", "acceptgroupinvite", "rejectgroupinvite",
            "recorddonation", "resetdonation", "setaffection", "call_agent", "ask_agent"));

    /** 判断工具是否有副作用、必须串行。 */
    public static boolean isStatefulTool(String toolName) {
        return toolName != null && STATEFUL_TOOLS.contains(toolName);
    }

    /** 按工具类型返回执行超时（毫秒）。 */
    public static long timeoutForTool(String toolName) {
        if (toolName == null) return 120_000L;
        switch (toolName) {
            case "time": case "balance": case "skillinfo": case "getpreference":
            case "markmessage": case "getselfinfo": case "pendingrequests":
            case "friendlist": case "grouplist": case "groupmembers":
            case "queryaffection": case "affectionrank": case "donationlist":
                return 15_000L;
            case "web": case "search": case "weather": case "readfile":
            case "readdir": case "findfile": case "searchnote": case "searchglobal":
            case "vision": case "sendsticker": case "collectsticker":
                return 60_000L;
            case "download": case "eval": case "evaljs": case "sys": case "cmd":
            case "batchrename": case "batchconvert": case "sendimage": case "sendrecord":
            case "sendfile": case "sendfileto": case "relay": case "forwardmsg":
            case "sendgroupmsg": case "call_agent": case "ask_agent":
                return 180_000L;
            default:
                return 120_000L;
        }
    }

    private final AgentActionHandler actionHandler;

    /** 工具集缓存（三方技能变更时失效）。 */
    private static volatile List<ToolDefinition> cachedAllTools;
    private static volatile List<ToolDefinition> cachedExecqTools;
    private static volatile List<ToolDefinition> cachedExecsTools;
    private static volatile boolean cachedStrict = false;
    private static volatile boolean cachedThirdPartyFlag = false;

    /** 三方技能增删后调用，使工具集缓存失效。 */
    public static void invalidateToolSetCache() {
        cachedAllTools = null;
        cachedExecqTools = null;
        cachedExecsTools = null;
    }
    /** 三方技能代码段执行器（懒创建，仅 execs/本地通道使用）。 */
    private volatile SkillCodeRunner skillCodeRunner;
    /** Harness 生命周期钩子（确定性约束层），按注册顺序执行。 */
    private final List<HarnessHook> hooks = new ArrayList<>();
    /** Agent 总线（递归多智能体通信；null=未启用 call_agent/ask_agent）。 */
    private volatile AgentBus agentBus;

    public ToolDispatcher(AgentActionHandler actionHandler) {
        this.actionHandler = actionHandler;
    }

    public AgentActionHandler getActionHandler() {
        return actionHandler;
    }

    /** 注入 Agent 总线，启用 call_agent/ask_agent 递归唤起。 */
    public void setAgentBus(AgentBus bus) {
        this.agentBus = bus;
    }

    /**
     * 注册 Harness 生命周期钩子（确定性约束层）。
     * 多个钩子按注册顺序依次执行；preExecute 任一返回非 null 即阻断执行。
     */
    public void registerHook(HarnessHook hook) {
        if (hook != null) {
            synchronized (hooks) {
                hooks.add(hook);
            }
        }
    }

    // ==================== 工具注册 ====================

    /**
     * strict 模式（Beta）下，统一为所有工具应用 strict 约束
     * （{@code additionalProperties:false} + 严格 JSON Schema，optional 属性转 anyOf null）。
     * <p>仅在 {@link AiConfig#isStrictMode()} 开启时生效。</p>
     */
    private static void applyStrictIfEnabled(List<ToolDefinition> tools) {
        if (AiConfig.getInstance().isStrictMode()) {
            for (ToolDefinition t : tools) {
                if (t != null) t.setStrict(true);
            }
        }
    }

    /** 本地通道（console/execs）全量工具注册。 */
    public static List<ToolDefinition> buildAllTools() {
        boolean strict = AiConfig.getInstance().isStrictMode();
        if (cachedAllTools != null && cachedStrict == strict) {
            return cachedAllTools;
        }
        List<ToolDefinition> tools = new ArrayList<>();

        tools.add(new ToolDefinition("cmd", "执行SFW插件命令（组件名/函数名 参数）。不确定先 cmd /help 或 cmd 组件名/help")
                .addString("command", "插件命令"));

        tools.add(new ToolDefinition("readfile", "读取指定文件的文本内容")
                .addString("path", "文件路径"));

        tools.add(new ToolDefinition("readdir", "列出指定目录的文件与子目录结构")
                .addOptionalString("path", "目录路径，留空则列出当前目录"));

        tools.add(new ToolDefinition("findfile", "递归查找指定目录下文件名匹配关键词的文件/目录（快速定位，避免逐层 readdir 探索）")
                .addString("path", "起始目录路径")
                .addOptionalString("keyword", "文件名关键词（子串匹配，忽略大小写，留空=返回全部）"));

        tools.add(new ToolDefinition("sys", "执行系统命令（shell）")
                .addString("command", "系统命令"));

        tools.add(new ToolDefinition("evaljs", "动态执行 JavaScript 代码（Nashorn 引擎，非 Java 动态注入），适合脚本/HTTP/数据处理，Java 类用全限定名")
                .addString("code", "要执行的 JS 代码"));

        tools.add(new ToolDefinition("eval", "编译并执行 Java 代码（动态注入，终极兜底），可用 SFW 内部 API（Libraries/SairCons/Activity）或 JDK 标准类")
                .addString("code", "要执行的 Java 代码"));

        tools.add(new ToolDefinition("web", "抓取网页/API内容，自动识别JSON返回。URL需完整http/https")
                .addString("url", "完整URL"));

        tools.add(new ToolDefinition("search", "必应搜索，返回标题/链接/摘要")
                .addString("query", "关键词"));

        tools.add(new ToolDefinition("remember", "记录一条跨会话的持久化记忆")
                .addString("content", "要记住的内容"));

        tools.add(new ToolDefinition("correct", "记录一条纠正信息（当用户指出你的错误、或你发现自己犯错时，记录纠正内容避免重复犯错）")
                .addString("topic", "纠正主题/关键词（归类检索用）")
                .addString("content", "纠正内容")
                .addOptionalString("viewpoint", "观点标签（同一主题多观点时区分用，可留空）"));

        tools.add(new ToolDefinition("download", "下载文件到 dataDir/downloads/ 目录")
                .addString("url", "下载地址（http/https）"));

        tools.add(new ToolDefinition("superise", "弹出一个彩蛋/惊喜窗口")
                .addString("content", "彩蛋文本内容"));

        tools.add(new ToolDefinition("editprompt", "修改系统提示词（持久化到配置）")
                .addString("content", "新的系统提示词（至少 30 字符）"));

        tools.add(new ToolDefinition("stop", "立即停止当前 Agent 执行循环"));

        tools.add(new ToolDefinition("sendimage", "将文字渲染为图片并保存到 dataDir/rendered/")
                .addString("content", "要渲染成图片的文字内容"));

        tools.add(new ToolDefinition("sendrecord", "发送语音消息（传递语音文件路径）")
                .addString("path", "语音文件路径"));

        tools.add(new ToolDefinition("sendfile", "发送文件（传递文件路径）")
                .addString("path", "文件路径"));

        tools.add(new ToolDefinition("schedule", "创建/管理定时任务，命令格式：add \"cron\" \"command\" | list | remove id | enable id | disable id")
                .addString("content", "完整子命令字符串"));

        tools.add(new ToolDefinition("note", "知识库：add title|content|tags / search q / list / get id / delete id / update id title|content|tags")
                .addString("content", "子命令"));

        tools.add(new ToolDefinition("searchnote", "在知识库中检索相关笔记")
                .addString("query", "检索关键词"));

        tools.add(new ToolDefinition("batchrename", "批量重命名文件（dir=/path pattern=regex replacement=text）")
                .addString("dir", "目标目录")
                .addString("pattern", "匹配的正则")
                .addString("replacement", "替换文本"));

        tools.add(new ToolDefinition("batchconvert", "批量图片格式转换（dir=/path from=EXT to=EXT）")
                .addString("dir", "目标目录")
                .addString("from", "源格式扩展名")
                .addString("to", "目标格式扩展名"));

        tools.add(new ToolDefinition("balance", "查询 DeepSeek 账户余额"));

        tools.add(new ToolDefinition("skillextract", "从执行轨迹中蒸馏提取技能")
                .addOptionalString("focus", "本次提取关注的主题（可为空）"));

        tools.add(new ToolDefinition("weather", "查询指定城市的实时天气")
                .addString("city", "城市名"));

        tools.add(new ToolDefinition("time", "查询当前日期和时间"));

        tools.add(new ToolDefinition("vision", "视觉分析图片：调用视觉模型分析并返回图片特征（类型/主体/文字/色调/二维码/违规内容等），适用于看图理解与内容鉴定")
                .addString("url", "图片 URL（http/https）或 file_id"));

        tools.add(new ToolDefinition("skillinfo", "查询某个技能/工具的完整使用说明书（不熟悉某技能或工具用法时，先调用本工具查详情）")
                .addString("name", "技能名或工具名（如 weather、eval、sendimage 等）"));

        tools.add(new ToolDefinition("setimageremark", "修改/设置某张图片的注释（AI 根据上下文或用户指正更新图片备注，注释与图片 MD5 强绑定并持久化，图片进入长期存储时注释随图保留）")
                .addOptionalString("image_url", "图片 URL（http/https），与 image_md5 二选一")
                .addOptionalString("image_md5", "图片 MD5（若已知，优先用此精确绑定，无需下载）")
                .addString("remark", "新的图片注释内容"));

        tools.add(new ToolDefinition("thirdskill", "管理三方技能库（仅 execs / QQ execs 可用）。命令格式：list | add 技能名|描述|内容 | delete 技能名")
                .addString("content", "完整子命令字符串，如 list、add 技能名|描述|技能内容、delete 技能名"));

        tools.add(new ToolDefinition("callskill", "调用三方技能（data/skills/*.md）内嵌代码段的 airun 入口函数。仅 execs/本地通道可用。args 为 JSON（字符串用引号包裹、对象用 {}，如 {\"city\":\"北京\"} 或 \"你好\"）")
                .addString("skill", "三方技能名")
                .addString("args", "传给 airun 的参数（JSON 字符串）"));

        tools.add(new ToolDefinition("alarm", "系统闹钟（到点唤醒AI执行任务）。命令格式：add schedule|task（schedule=once:yyyy-MM-dd HH:mm / daily:HH:mm / weekly:D,HH:mm，D=1周一~7周日） | list | remove id | cancel id | append id|备注内容")
                .addString("content", "完整子命令字符串"));

        tools.add(new ToolDefinition("searchglobal", "全局记忆检索：跨群/跨用户检索 Bot 的对话历史、群聊记录和长期记忆（用于回答「某用户/某群之前聊了什么」）。涉及隐私的内容由你判断是否透露，隐私则跳过不说")
                .addString("query", "检索关键词（话题/人名/群名等）"));

        tools.add(new ToolDefinition("markmessage", "给消息打 Mark 备注（AI 自用内部标记，用户看不到）。action=set 标记某条消息的处理结果（message 可留空=当前消息，mark 必填）；action=get 查询某条消息是否已处理（message 必填）；action=list 列出最近带备注的消息")
                .addString("action", "set/get/list")
                .addOptionalString("message", "要标记或查询的消息内容（set 留空=当前消息；get 必填）")
                .addOptionalString("mark", "备注内容（set 时必填，如「已记录XXX捐赠10元」）"));

        tools.add(new ToolDefinition("setpreference", "设置一条结构化偏好/设定（如找文件优先在哪找、谁能读哪个文件、发消息用什么语气）。scope=user/group/global，key=偏好名，value=偏好内容")
                .addString("scope", "user/group/global")
                .addString("key", "偏好名（如 file_search_priority、reply_tone）")
                .addString("value", "偏好内容"));

        tools.add(new ToolDefinition("getpreference", "查询结构化偏好。scope=user/group/global，key 留空则列出该 scope 下全部偏好")
                .addString("scope", "user/group/global")
                .addOptionalString("key", "偏好名，留空列出全部"));

        // 三方技能（含代码段）动态注册为 Function Calling 工具（tp_ 前缀），execs/本地始终可用
        addThirdPartyToolTools(tools);

        // 多智能体递归唤起（call_agent/ask_agent）
        tools.addAll(AgentBus.callAgentTools());

        applyStrictIfEnabled(tools);
        cachedAllTools = tools;
        cachedStrict = strict;
        return tools;
    }

    /** QQ 通道（execq）受限工具注册。 */
    public static List<ToolDefinition> buildExecqTools() {
        boolean strict = AiConfig.getInstance().isStrictMode();
        boolean thirdParty = AiConfig.getInstance().isThirdPartyCodeExecq();
        if (cachedExecqTools != null && cachedStrict == strict && cachedThirdPartyFlag == thirdParty) {
            return cachedExecqTools;
        }
        List<ToolDefinition> tools = new ArrayList<>();

        tools.add(new ToolDefinition("cmd", "执行 SFW 命令（execq 通道仅允许 help，其他命令一律禁止）")
                .addString("command", "命令（execq 仅允许 help）"));

        tools.add(new ToolDefinition("eval", "编译并执行一段 Java 代码（动态注入）。execq 通道【仅限多对象拆分搜索场景破例使用】：用 HttpURLConnection 并发抓取多个对象的搜索结果并解析合并；其他场景禁止使用")
                .addString("code", "要执行的 Java 代码（仅限搜索抓取）"));

        tools.add(new ToolDefinition("web", "抓取指定 URL 的网页或 API 接口内容（自动识别 JSON 并原样返回）")
                .addString("url", "要抓取的完整 URL（http/https）"));

        tools.add(new ToolDefinition("search", "在必应搜索网页并返回标题、链接、摘要（适合查实时信息、公开资料、新闻）")
                .addString("query", "搜索关键词"));

        tools.add(new ToolDefinition("readdir", "列出指定目录的文件与子目录结构")
                .addOptionalString("path", "目录路径，留空则列出当前目录"));

        tools.add(new ToolDefinition("findfile", "递归查找指定目录下文件名匹配关键词的文件/目录（快速定位，避免逐层 readdir 探索）")
                .addString("path", "起始目录路径")
                .addOptionalString("keyword", "文件名关键词（子串匹配，忽略大小写，留空=返回全部）"));

        tools.add(new ToolDefinition("readfile", "读取指定文件的文本内容")
                .addString("path", "文件路径"));

        tools.add(new ToolDefinition("weather", "查询指定城市的实时天气")
                .addString("city", "城市名"));

        tools.add(new ToolDefinition("time", "查询当前日期和时间"));

        tools.add(new ToolDefinition("vision", "视觉分析图片：调用视觉模型分析并返回图片特征（类型/主体/文字/色调/二维码/违规内容等），适用于看图理解与内容鉴定")
                .addString("url", "图片 URL（http/https）或 file_id"));

        tools.add(new ToolDefinition("remember", "记录一条跨会话的持久化记忆")
                .addString("content", "要记住的内容"));

        tools.add(new ToolDefinition("correct", "记录一条纠正信息（当用户指出你的错误、或你发现自己犯错时，记录纠正内容避免重复犯错）")
                .addString("topic", "纠正主题/关键词（归类检索用）")
                .addString("content", "纠正内容")
                .addOptionalString("viewpoint", "观点标签（同一主题多观点时区分用，可留空）"));

        tools.add(new ToolDefinition("note", "知识库：add title|content|tags / search q / list / get id / delete id / update id title|content|tags")
                .addString("content", "子命令"));

        tools.add(new ToolDefinition("searchnote", "在知识库中检索相关笔记")
                .addString("query", "检索关键词"));

        tools.add(new ToolDefinition("balance", "查询 DeepSeek 账户余额"));

        tools.add(new ToolDefinition("sendsticker", "发送表情包（按图片内容描述匹配库存）")
                .addOptionalString("context", "简短表情/情绪关键词（如「猫」「开心」「哭」），留空则返回库存清单"));

        tools.add(new ToolDefinition("collectsticker", "收藏表情包（imageUrl|context）")
                .addString("content", "imageUrl|context 格式"));

        tools.add(new ToolDefinition("setimageremark", "修改/设置某张图片的注释（根据上下文或用户指正更新图片备注，与图片 MD5 强绑定并持久化）")
                .addOptionalString("image_url", "图片 URL（http/https），与 image_md5 二选一")
                .addOptionalString("image_md5", "图片 MD5（若已知，优先用此精确绑定，无需下载）")
                .addString("remark", "新的图片注释内容"));

        tools.add(new ToolDefinition("clearsticker", "清空表情包图片库（删数据库记录+本地文件）"));

        // === QQ 群管工具（仅主人） ===
        tools.add(new ToolDefinition("ban", "禁言群成员（仅主人）")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null)
                .addProperty("duration", "integer", "禁言秒数（默认180）", false, null));

        tools.add(new ToolDefinition("kick", "移出群成员（仅主人）")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null)
                .addProperty("block", "boolean", "是否同时拉黑（默认false）", false, null));

        tools.add(new ToolDefinition("muteall", "全员禁言开关（仅主人）")
                .addProperty("enable", "boolean", "true=开启 false=关闭", true, null));

        tools.add(new ToolDefinition("setadmin", "设置/取消群管理员（仅主人）")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null)
                .addProperty("enable", "boolean", "true=设为管理员 false=取消", true, null));

        tools.add(new ToolDefinition("setcard", "设置群成员名片（仅主人）")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null)
                .addString("card", "名片内容"));

        tools.add(new ToolDefinition("setgroupname", "设置群名称（仅主人）")
                .addString("name", "新的群名称"));

        tools.add(new ToolDefinition("setname", "修改机器人自己的 QQ 昵称")
                .addString("name", "新的 QQ 昵称"));

        tools.add(new ToolDefinition("setsignature", "修改机器人自己的 QQ 个性签名")
                .addString("signature", "新的个性签名内容"));

        tools.add(new ToolDefinition("settrigger", "设置/修改 Bot 的消息触发词（仅主人）。多个触发词用 ; 分隔（如 小助手;助手;小助），群聊中提到任一触发词即触发回复。主人说「把触发词改成XX」「新增/删除触发词」「查看触发词」时调用")
                .addOptionalString("words", "触发词列表，多个用 ; 分隔；留空或填 list/查看 则查询当前触发词"));

        tools.add(new ToolDefinition("leavegroup", "退出群聊（仅主人）")
                .addProperty("dismiss", "boolean", "是否解散群（默认false）", false, null));

        tools.add(new ToolDefinition("block", "拉黑用户（仅主人）")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null));

        tools.add(new ToolDefinition("unblock", "取消拉黑用户（仅主人）")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null));

        tools.add(new ToolDefinition("delfriend", "删除好友（仅主人）")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null));

        // === QQ 媒体/互动工具 ===
        tools.add(new ToolDefinition("poke", "戳一戳群成员")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null));

        tools.add(new ToolDefinition("sendlike", "给用户点赞")
                .addProperty("user_id", "integer", "目标用户 QQ 号", true, null)
                .addProperty("times", "integer", "点赞次数（默认10）", false, null));

        tools.add(new ToolDefinition("sendfileto", "发送文件给指定联系人")
                .addString("target", "目标 QQ 号或昵称")
                .addString("path", "文件路径"));

        tools.add(new ToolDefinition("sendimage", "发送图片到当前 QQ 会话（文字/本地路径/URL）")
                .addString("content", "图片内容：文字、本地图片路径或 http/https URL"));

        tools.add(new ToolDefinition("sendrecord", "发送语音消息到当前 QQ 会话")
                .addString("path", "语音文件路径或 URL"));

        tools.add(new ToolDefinition("sendfile", "发送文件到当前 QQ 会话（仅主人）")
                .addString("path", "文件路径"));

        tools.add(new ToolDefinition("relay", "转告消息给指定联系人")
                .addString("target", "目标 QQ 号或昵称")
                .addString("message", "转告内容"));

        tools.add(new ToolDefinition("forwardmsg", "转发消息（仅主人）")
                .addProperty("message_id", "integer", "要转发的消息 ID", true, null)
                .addString("target", "目标 QQ 号/群号/群名/昵称"));

        // === 跨群操作工具 ===
        tools.add(new ToolDefinition("grouplist", "查询 Bot 加入的所有群（群名 + 群号）。跨群操作（去别的群发消息/找人）前，先查它确认目标群号")
                .addOptionalString("keyword", "可选：按关键词过滤群名（如群名关键词）"));

        tools.add(new ToolDefinition("groupmembers", "查询指定群的成员列表（昵称 + QQ 号）。跨群找某人（如去别的群@某人）时，先查它拿到目标 QQ 号")
                .addProperty("group_id", "integer", "目标群号", true, null));

        tools.add(new ToolDefinition("sendgroupmsg", "向指定群发送消息（可@群成员）。跨群打招呼/发消息时用它：message 里用 [CQ:at,qq=QQ号] 前缀实现@。仅主人可用")
                .addProperty("group_id", "integer", "目标群号", true, null)
                .addString("message", "要发送的消息内容（可含 [CQ:at,qq=xxx] 前缀@人）"));

        tools.add(new ToolDefinition("friendlist", "查询 Bot 的好友列表（昵称/备注 + QQ 号）。私聊找某人（如私聊传话）时，先查它拿到目标 QQ 号")
                .addOptionalString("keyword", "可选：按关键词过滤好友昵称/备注"));

        tools.add(new ToolDefinition("skillinfo", "查询某个技能/工具的完整使用说明书（不熟悉某技能或工具用法时，先调用本工具查详情）")
                .addString("name", "技能名或工具名"));

        // === 好友申请 / 群邀请决策工具（AI 处理待处理请求池） ===
        tools.add(new ToolDefinition("pendingrequests", "列出待处理的好友申请与群邀请（等待 AI/主人决策）"));

        tools.add(new ToolDefinition("approvefriend", "同意一个好友申请（需主人或好感度≥100）")
                .addString("flag", "请求 flag（从 pendingrequests 获取）"));

        tools.add(new ToolDefinition("rejectfriend", "拒绝一个好友申请")
                .addString("flag", "请求 flag（从 pendingrequests 获取）"));

        tools.add(new ToolDefinition("acceptgroupinvite", "同意一个群邀请（需主人或好感度≥300）")
                .addString("flag", "请求 flag（从 pendingrequests 获取）"));

        tools.add(new ToolDefinition("rejectgroupinvite", "拒绝一个群邀请")
                .addString("flag", "请求 flag（从 pendingrequests 获取）"));

        tools.add(new ToolDefinition("getselfinfo", "查看机器人自己的 QQ 昵称与个性签名等资料"));

        tools.add(new ToolDefinition("recorddonation", "记录一笔捐赠并加分（仅主人可用，1元=1好感度）。主人告知「XXX 捐赠了 XXX 元」时调用")
                .addString("amount", "捐赠金额（元，数字）")
                .addOptionalString("donor", "捐赠者 QQ 号或昵称（留空则视为主人自己捐赠）")
                .addOptionalString("note", "备注说明"));

        tools.add(new ToolDefinition("donationlist", "查询捐赠排行榜（任何人可查）。返回全部人员捐赠总额 Top10 + 你（触发者）自己的捐赠总额"));

        tools.add(new ToolDefinition("resetdonation", "重置某人的捐赠名单（移除此人全部捐赠记录，仅主人可用）")
                .addString("target", "目标用户 QQ 号或昵称"));

        // === 好感度查询/调整工具 ===
        tools.add(new ToolDefinition("queryaffection", "查询用户好感度。可查单个用户（按QQ号）或某个群成员的好感度列表（可随机抽取N个）。好感度分级：<100陌生人、100认识、200普通朋友、300可入群、400挚友、800挚爱、1000灵魂伴侣")
                .addProperty("user_id", "integer", "要查询的QQ号（与 group_id 二选一）", false, null)
                .addProperty("group_id", "integer", "要查询的群号（不填则默认当前群）", false, null)
                .addProperty("count", "integer", "随机抽取N个人展示（不填则全部）", false, null));

        tools.add(new ToolDefinition("setaffection", "直接设置某个用户的好感度到指定值（仅主人可用）。主人说「把XX的好感度调到XX」时调用")
                .addString("target", "目标用户：QQ号（纯数字）或昵称")
                .addProperty("affection", "integer", "目标好感度值（-100到1000）", true, null)
                .addProperty("group_id", "integer", "按昵称定位时所在的群号（不填则默认当前群）", false, null));

        tools.add(new ToolDefinition("affectionrank", "查询好感度排行Top10。默认查当前群成员好感度排名；scope=global 查全局排行（仅主人可用）")
                .addOptionalString("scope", "排行范围：group（本群，默认）或 global（全局，仅主人）")
                .addProperty("group_id", "integer", "要查的群号（不填则默认当前群）", false, null));

        tools.add(new ToolDefinition("alarm", "系统闹钟（到点唤醒AI执行任务，普通用户为提醒，主人为 AI 任务）。命令格式：add schedule|task（schedule=once:yyyy-MM-dd HH:mm / daily:HH:mm / weekly:D,HH:mm） | list | remove id | cancel id | append id|备注内容")
                .addString("content", "完整子命令字符串"));

        tools.add(new ToolDefinition("searchglobal", "全局记忆检索：跨群/跨用户检索 Bot 的对话历史、群聊记录和长期记忆（用于回答「某用户/某群之前聊了什么」）。涉及隐私的内容由你判断是否透露，隐私则跳过不说")
                .addString("query", "检索关键词（话题/人名/群名等）"));

        tools.add(new ToolDefinition("markmessage", "给消息打 Mark 备注（AI 自用内部标记，用户看不到）。action=set 标记某条消息的处理结果（message 可留空=当前消息，mark 必填）；action=get 查询某条消息是否已处理（message 必填）；action=list 列出最近带备注的消息")
                .addString("action", "set/get/list")
                .addOptionalString("message", "要标记或查询的消息内容（set 留空=当前消息；get 必填）")
                .addOptionalString("mark", "备注内容（set 时必填，如「已记录XXX捐赠10元」）"));

        tools.add(new ToolDefinition("setpreference", "设置一条结构化偏好/设定。scope=user/group/global，key=偏好名，value=偏好内容")
                .addString("scope", "user/group/global")
                .addString("key", "偏好名")
                .addString("value", "偏好内容"));

        tools.add(new ToolDefinition("getpreference", "查询结构化偏好。scope=user/group/global，key 留空则列出该 scope 下全部偏好")
                .addString("scope", "user/group/global")
                .addOptionalString("key", "偏好名，留空列出全部"));

        // 三方技能代码段执行能力（callskill + tp_ 动态工具）：默认放权给用户，用户可通过配置关闭
        if (AiConfig.getInstance().isThirdPartyCodeExecq()) {
            tools.add(new ToolDefinition("callskill", "调用三方技能（data/skills/*.md）内嵌代码段的 airun 入口函数。args 为 JSON（字符串用引号包裹、对象用 {}）")
                    .addString("skill", "三方技能名")
                    .addString("args", "传给 airun 的参数（JSON 字符串）"));
            addThirdPartyToolTools(tools);
        }

        // 多智能体递归唤起（call_agent/ask_agent）
        tools.addAll(AgentBus.callAgentTools());

        applyStrictIfEnabled(tools);
        cachedExecqTools = tools;
        cachedThirdPartyFlag = thirdParty;
        return tools;
    }

    /** QQ execs 全权限工具集 = 本地全量工具 + QQ 专属工具（按名去重）；结果缓存，三方技能变更时失效。 */
    public static List<ToolDefinition> buildExecsTools() {
        if (cachedExecsTools != null) return cachedExecsTools;
        List<ToolDefinition> tools = new ArrayList<>(buildAllTools());
        Set<String> names = new HashSet<>();
        for (ToolDefinition t : tools) names.add(t.getName());
        for (ToolDefinition t : buildExecqTools()) {
            if (!names.contains(t.getName())) {
                tools.add(t);
            }
        }
        cachedExecsTools = tools;
        return tools;
    }

    /**
     * 将含代码段的三方技能动态注册为 Function Calling 工具（tp_ 前缀）。
     * 参数来自 front matter 的 airun 声明；未声明时退化为单个 args（JSON 字符串）。
     */
    private static void addThirdPartyToolTools(List<ToolDefinition> tools) {
        SkillBank bank = SkillBank.getInstance();
        ThirdPartySkillStore store = bank == null ? null : bank.getThirdPartyStore();
        if (store == null) return;
        // 按技能名排序，保证工具注册顺序字节稳定（DeepSeek 前缀缓存要求 tools 数组字节稳定，ConcurrentHashMap 迭代顺序不稳定会破坏缓存）
        java.util.List<ThirdPartySkill> sorted = new java.util.ArrayList<>(store.getAll());
        sorted.sort(java.util.Comparator.comparing(ThirdPartySkill::getName));
        for (ThirdPartySkill tp : sorted) {
            if (tp == null || !tp.hasCodeBlocks()) continue; // 仅注册含可执行代码段的技能
            String toolName = "tp_" + tp.getName();
            String desc = tp.getAirunDescription();
            if (desc == null || desc.trim().isEmpty()) desc = tp.getDescription();
            if (desc == null || desc.trim().isEmpty()) desc = "调用三方技能 " + tp.getName();
            ToolDefinition td = new ToolDefinition(toolName, desc);
            List<ThirdPartySkill.AirunParam> params = tp.getAirunParams();
            if (params != null && !params.isEmpty()) {
                for (ThirdPartySkill.AirunParam p : params) {
                    if (p == null || p.name.isEmpty()) continue;
                    td.addProperty(p.name, p.type, p.description, p.required, null);
                }
            } else {
                td.addString("args", "传给 airun 的 JSON 参数（字符串或对象，如 \"hello\" 或 {\"city\":\"北京\"}）");
            }
            tools.add(td);
        }
    }

    // ==================== 执行 ====================

    /**
     * 统一执行工具调用（含 Harness 生命周期钩子）。
     * <p>前置钩子可确定性阻断（权限/高危代码），后置钩子校验结果。</p>
     *
     * @param toolName      工具名
     * @param argumentsJson 工具参数 JSON 字符串
     * @param ctx           执行上下文（console 或 execq）
     * @return 工具执行结果字符串
     */
    public String execute(String toolName, String argumentsJson, ToolContext ctx) {
        // 工具调用追踪日志：记录通道 + 工具名 + 参数概要（覆盖 console/execq/execs 全通道）
        String channel = (ctx != null) ? (ctx.execsMode ? "execs" : ctx.channel) : "console";
        long start = System.currentTimeMillis();
        AiAgentActivity.debugLog("[Tool] [" + channel + "] 调用 " + toolName + briefArgs(argumentsJson));
        auditToolAccess(toolName, argumentsJson, ctx);
        String fileBlock = checkQqFileAccess(toolName, argumentsJson, ctx);
        if (fileBlock != null) return fileBlock;

        HarnessHook[] snapshot;
        synchronized (hooks) {
            snapshot = hooks.toArray(new HarnessHook[0]);
        }
        for (HarnessHook hook : snapshot) {
            try {
                String blocked = hook.preExecute(toolName, argumentsJson, ctx);
                if (blocked != null) {
                    AiAgentActivity.debugLog("[Tool] [" + channel + "] " + toolName + " 被权限/安全阻断: " + briefText(blocked));
                    recordToolTrace(toolName, channel, argumentsJson, blocked, false,
                            System.currentTimeMillis() - start, "blocked");
                    return blocked;
                }
            } catch (Exception ignored) {
                // 钩子异常不影响工具执行（护栏自身故障不应阻断业务）
            }
        }
        String result;
        boolean success = true;
        String outcome = "ok";
        try {
            result = executeInternal(toolName, argumentsJson, ctx);
        } catch (Exception e) {
            result = "[工具执行异常] " + toolName + ": " + e.toString();
            success = false;
            outcome = "error";
        }
        for (HarnessHook hook : snapshot) {
            try {
                result = hook.postExecute(toolName, argumentsJson, ctx, result);
            } catch (Exception ignored) {
                // 后置钩子异常保留原结果
            }
        }
        if (isTraceFailureResult(result)) {
            success = false;
            outcome = result != null && result.startsWith("[harness]") ? "blocked" : "error";
        }
        long duration = System.currentTimeMillis() - start;
        AiAgentActivity.debugLog("[Tool] [" + channel + "] " + toolName + " 完成(" + duration + "ms) → " + briefText(result));
        recordToolTrace(toolName, channel, argumentsJson, result, success, duration, outcome);
        return result;
    }

    /** QQ 通道文件访问策略：配置了根目录时，仅允许根目录内路径。 */
    private static String checkQqFileAccess(String toolName, String argumentsJson, ToolContext ctx) {
        if (ctx == null || !ctx.isExecq()) return null;
        if (!"readfile".equals(toolName) && !"readdir".equals(toolName) && !"findfile".equals(toolName)) return null;
        String path = arg(argumentsJson, "path");
        if (path == null || path.trim().isEmpty()) return null; // readdir/findfile 空路径按现有逻辑处理
        if (!AiConfig.getInstance().isQqFileAccessAllowed(path.trim())) {
            return "[harness] 文件访问阻断：" + toolName + " 路径不在允许的根目录内";
        }
        return null;
    }

    /** 对高风险/文件访问工具做审计日志（QQ 文件访问、本地高危执行）。 */
    private static void auditToolAccess(String toolName, String argumentsJson, ToolContext ctx) {
        if (toolName == null) return;
        boolean qq = ctx != null && ctx.isExecq();
        if (("readfile".equals(toolName) || "readdir".equals(toolName) || "findfile".equals(toolName)) && qq) {
            AiAgentActivity.debugLog("[Audit] QQ文件访问 tool=" + toolName
                    + " user=" + (ctx.senderQQ > 0 ? ctx.senderQQ : 0)
                    + " args=" + briefArgs(argumentsJson));
        }
        if (("cmd".equals(toolName) || "sys".equals(toolName) || "eval".equals(toolName) || "evaljs".equals(toolName))
                && ctx != null && !ctx.isExecq()) {
            AiAgentActivity.debugLog("[Audit] 本地高危执行 tool=" + toolName + " args=" + briefArgs(argumentsJson));
        }
    }

    /** 判断工具结果是否应标记为失败，用于遥测准确性。 */
    private static boolean isTraceFailureResult(String result) {
        if (result == null) return false;
        String r = result.trim();
        return r.startsWith("[harness]") || r.startsWith("[代码执行失败]")
                || r.startsWith("[工具执行异常]") || r.startsWith("[工具] 未知工具");
    }

    /** 轻量工具调用遥测：独立于 HarnessTrace，失败静默忽略。 */
    private static void recordToolTrace(String toolName, String channel, String argumentsJson,
                                        String result, boolean success, long durationMs, String outcome) {
        try {
            final PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return;
            final ToolCallTrace trace = new ToolCallTrace(
                    ToolCallTrace.newTraceId(),
                    toolName,
                    channel,
                    briefArgs(argumentsJson),
                    briefText(result),
                    success,
                    durationMs,
                    outcome,
                    System.currentTimeMillis());
            // 异步落库：遥测不阻塞工具执行主链路
            ThreadManager.getInstance().newNamedSingle("TraceWriter").submit(() -> {
                try { pm.saveToolTrace(trace); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {
            // 遥测异常不影响工具执行
        }
    }

    /** 参数概要：截断到 160 字符，避免超长内容刷屏。 */
    private static String briefArgs(String args) {
        if (args == null || args.trim().isEmpty()) return "";
        String t = args.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.length() > 160) t = t.substring(0, 160) + "...";
        return " | " + t;
    }

    /** 结果概要：截断到 160 字符，避免超长结果刷屏。 */
    private static String briefText(String s) {
        if (s == null) return "(null)";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        if (t.isEmpty()) return "(空)";
        if (t.length() > 160) t = t.substring(0, 160) + "...";
        return t;
    }

    /** QQ 通道文件路径处理：空路径优先落到插件数据目录，显式路径保持全局可访问。 */
    private static String[] resolveQqPath(ToolContext ctx, String path, boolean allowEmptyAsDataDir) {
        if (ctx == null || !ctx.isExecq() || ctx.execsMode) {
            return new String[]{path, null};
        }
        String p = (path == null) ? "" : path.trim();
        if (p.isEmpty()) {
            if (!allowEmptyAsDataDir) {
                return new String[]{null, "[readfile] 无权限：execq 通道必须提供明确文件路径"};
            }
            p = ctx.dataDir;
        }
        if (p == null || p.isEmpty()) {
            return new String[]{null, "[readfile] 无权限：execq 通道缺少数据目录"};
        }
        return new String[]{p, null};
    }

    /** 工具实际执行（不含钩子），保留原有全部逻辑不变。 */
    private String executeInternal(String toolName, String argumentsJson, ToolContext ctx) {
        switch (toolName) {
            case "cmd": {
                String cmdArg = arg(argumentsJson, "command");
                if (ctx.isExecq() && !ctx.execsMode) {
                    String helpResult = execqHelpQuery(cmdArg);
                    if (helpResult == null) {
                        return "[cmd] 无权限：execq 通道仅允许 help 查询（格式：组件名/help 或 /help），其他命令一律禁止执行";
                    }
                    return helpResult;
                }
                return act("cmd", cmdArg);
            }
            case "readfile": {
                String path = arg(argumentsJson, "path");
                String[] resolved = resolveQqPath(ctx, path, false);
                if (resolved[1] != null) return resolved[1];
                return act("readfile", resolved[0]);
            }
            case "readdir": {
                String path = arg(argumentsJson, "path");
                String[] resolved = resolveQqPath(ctx, path, true);
                if (resolved[1] != null) return resolved[1];
                return act("readdir", resolved[0]);
            }
            case "findfile": {
                String fpath = arg(argumentsJson, "path");
                String fkw = arg(argumentsJson, "keyword");
                String[] resolved = resolveQqPath(ctx, fpath, true);
                if (resolved[1] != null) return resolved[1];
                String fcontent = (fkw == null || fkw.trim().isEmpty()) ? resolved[0] : (resolved[0] + "|" + fkw.trim());
                return act("findfile", fcontent);
            }
            case "sys":          return act("sys", arg(argumentsJson, "command"));
            case "evaljs": {
                // execq 通道已彻底禁用动态执行（evaljs），仅本地 execs / QQ execs: 可用
                if (ctx.isExecq() && !ctx.execsMode) {
                    return "[evaljs] 无权限：execq 通道已禁用动态执行";
                }
                String jsCode = arg(argumentsJson, "code");
                return act("evaljs", jsCode);
            }
            case "eval": {
                if (ctx.isExecq() && !ctx.execsMode) {
                    String evalCode = arg(argumentsJson, "code");
                    if (!isSearchEvalCode(evalCode)) {
                        return "[eval] 无权限：execq 通道的 eval 仅限多对象拆分搜索的联网抓取，其他场景禁用";
                    }
                }
                return act("eval", arg(argumentsJson, "code"));
            }
            case "web": {
                String url = arg(argumentsJson, "url");
                String result = act("web", url);
                autoStoreWebResult(url, result);
                return result;
            }
            case "search": {
                String query = arg(argumentsJson, "query");
                String result = act("search", query);
                autoStoreSearchResult(query, result);
                return result;
            }
            case "remember":     return executeRemember(arg(argumentsJson, "content"), ctx);
            case "correct":      return executeCorrect(argumentsJson, ctx);
            case "download":     return act("download", arg(argumentsJson, "url"));
            case "superise":     return act("superise", arg(argumentsJson, "content"));
            case "editprompt":   return act("editprompt", arg(argumentsJson, "content"));
            case "stop":         return act("stop", "");
            case "sendimage":    return ctx.isExecq() ? executeSendImageQq(arg(argumentsJson, "content"), ctx) : act("sendimage", arg(argumentsJson, "content"));
            case "sendrecord":   return ctx.isExecq() ? executeSendRecordQq(arg(argumentsJson, "path"), ctx) : act("sendrecord", arg(argumentsJson, "path"));
            case "sendfile":     return ctx.isExecq() ? executeSendFileQq(arg(argumentsJson, "path"), ctx) : act("sendfile", arg(argumentsJson, "path"));
            case "schedule":     return act("schedule", arg(argumentsJson, "content"));
            case "note":         return act("note", arg(argumentsJson, "content"));
            case "searchnote":   return act("searchnote", arg(argumentsJson, "query"));
            case "batchrename":  return act("batchrename", batchRenameContent(argumentsJson));
            case "batchconvert": return act("batchconvert", batchConvertContent(argumentsJson));
            case "balance":      return act("balance", "");
            case "skillextract": return act("skillextract", arg(argumentsJson, "focus"));
            case "weather":      return act("weather", arg(argumentsJson, "city"));
            case "time":         return executeTime();
            case "vision":       return executeVision(argumentsJson, ctx);
            case "setname":      return executeSetName(arg(argumentsJson, "name"), ctx);
            case "setsignature": return executeSetSignature(arg(argumentsJson, "signature"), ctx);
            case "settrigger":   return executeSetTrigger(arg(argumentsJson, "words"), ctx);
            case "sendsticker":  return actionHandler.executeSendSticker(arg(argumentsJson, "context"));
            case "collectsticker": return actionHandler.executeCollectSticker(arg(argumentsJson, "content"));
            case "clearsticker":  return actionHandler.executeClearSticker();
            // === QQ 群管/媒体工具 ===
            case "ban":          return executeBan(argumentsJson, ctx);
            case "kick":         return executeKick(argumentsJson, ctx);
            case "muteall":      return executeMuteAll(argumentsJson, ctx);
            case "setadmin":     return executeSetAdmin(argumentsJson, ctx);
            case "setcard":      return executeSetCard(argumentsJson, ctx);
            case "setgroupname": return executeSetGroupName(argumentsJson, ctx);
            case "leavegroup":   return executeLeaveGroup(argumentsJson, ctx);
            case "block":        return executeBlock(argumentsJson, ctx);
            case "unblock":      return executeUnblock(argumentsJson, ctx);
            case "delfriend":    return executeDelfriend(argumentsJson, ctx);
            case "poke":         return executePoke(argumentsJson, ctx);
            case "sendlike":     return executeSendLike(argumentsJson, ctx);
            case "sendfileto":   return executeSendFileTo(argumentsJson, ctx);
            case "relay":        return executeRelay(argumentsJson, ctx);
            case "forwardmsg":   return executeForwardMsg(argumentsJson, ctx);
            case "grouplist":    return executeGroupList(argumentsJson, ctx);
            case "groupmembers": return executeGroupMembers(argumentsJson, ctx);
            case "sendgroupmsg": return executeSendGroupMsg(argumentsJson, ctx);
            case "friendlist":    return executeFriendList(argumentsJson, ctx);
            case "skillinfo":       return executeSkillInfo(arg(argumentsJson, "name"));
            case "setimageremark":   return executeSetImageRemark(argumentsJson);
            case "thirdskill":       return executeThirdSkill(arg(argumentsJson, "content"));
            case "callskill":        return executeCallSkill(argumentsJson, ctx);
            case "pendingrequests":    return executePendingRequests(ctx);
            case "approvefriend":      return executeApproveFriend(arg(argumentsJson, "flag"), ctx);
            case "rejectfriend":       return executeRejectFriend(arg(argumentsJson, "flag"), ctx);
            case "acceptgroupinvite":  return executeAcceptGroupInvite(arg(argumentsJson, "flag"), ctx);
            case "rejectgroupinvite":  return executeRejectGroupInvite(arg(argumentsJson, "flag"), ctx);
            case "getselfinfo":        return executeGetSelfInfo(ctx);
            case "recorddonation":     return executeRecordDonation(argumentsJson, ctx);
            case "donationlist":       return executeDonationList(argumentsJson, ctx);
            case "resetdonation":      return executeResetDonation(argumentsJson, ctx);
            case "queryaffection":     return executeQueryAffection(argumentsJson, ctx);
            case "setaffection":       return executeSetAffection(argumentsJson, ctx);
            case "affectionrank":      return executeAffectionRank(argumentsJson, ctx);
            case "alarm":             return executeAlarm(argumentsJson, ctx);
            case "searchglobal":      return executeSearchGlobal(argumentsJson, ctx);
            case "markmessage":       return executeMarkMessage(argumentsJson, ctx);
            case "setpreference":     return executeSetPreference(argumentsJson, ctx);
            case "getpreference":     return executeGetPreference(argumentsJson, ctx);
            case "call_agent":        return invokeAgent(arg(argumentsJson, "agent"), arg(argumentsJson, "task"), ctx);
            case "ask_agent":         return invokeAgent(arg(argumentsJson, "agent"), arg(argumentsJson, "question"), ctx);
            default:
                if (toolName != null && toolName.startsWith("tp_")) {
                    return executeThirdPartyTool(toolName.substring(3), argumentsJson, ctx);
                }
                return "[工具] 未知工具: " + toolName;
        }
    }

    /** 唤起指定 Agent（call_agent/ask_agent 工具），通过 AgentBus 递归调度。 */
    private String invokeAgent(String agent, String task, ToolContext ctx) {
        if (agentBus == null) {
            return "[AgentBus] 未初始化（call_agent/ask_agent 不可用）";
        }
        return agentBus.invoke(agent, task, ctx);
    }

    /** 记录持久化记忆（remember 工具）：execq 通道写入统一记忆库，本地通道写入本地记忆库。 */
    private String executeRemember(String content, ToolContext ctx) {
        if (content == null || content.trim().isEmpty()) return "[remember] 记忆内容为空，未记录。";
        String c = content.trim();
        // QQ 通道（execq）：写入统一记忆库（unified_memory.db，跨会话跨用户持久化）
        if (ctx != null && ctx.isExecq()) {
            sair.aiagent.onebot.UnifiedQQMemoryManager mem = ctx.unifiedMemory;
            if (mem == null) return "[remember] 统一记忆库不可用，无法记录。";
            mem.addMemory(c);
            return "[remember] 已记录到统一记忆库: " + c;
        }
        // 本地通道（console）：写入本地记忆库（aiagent.db）
        return act("remember", c);
    }

    /** 记录纠正信息（correct 工具）：写入 corrections 表，避免 AI 重复犯错。 */
    private String executeCorrect(String json, ToolContext ctx) {
        String topic = arg(json, "topic");
        String content = arg(json, "content");
        String viewpoint = arg(json, "viewpoint");
        if (content == null || content.trim().isEmpty()) {
            return "[correct] 错误：content（纠正内容）不能为空";
        }
        if (topic == null || topic.trim().isEmpty()) {
            topic = "general";
        }
        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) {
            return "[correct] 错误：持久化层未初始化";
        }
        long qq = (ctx != null && ctx.senderQQ > 0) ? ctx.senderQQ : 0;
        int id = pm.addCorrection(topic.trim(), content.trim(),
                viewpoint != null ? viewpoint.trim() : "", "ai", qq);
        return id >= 0
            ? "[correct] 已记录纠正 #" + id + "（主题: " + topic + "）"
            : "[correct] 记录失败";
    }

    /** 查询当前日期时间。 */
    private static String executeTime() {
        return "当前时间: " + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss EEEE")
                .format(new java.util.Date());
    }

    /** vision 工具的视觉分析提示词：要求视觉模型返回图片的详细特征描述。 */
    private static final String VISION_PROMPT =
            "请仔细分析这张图片，并输出详细的结构化描述，包括：\n"
            + "1. 图片类型（照片/表情包/截图/海报/二维码/漫画等）\n"
            + "2. 主要对象、人物、场景\n"
            + "3. 画面中的文字内容（如有）\n"
            + "4. 整体色调、风格、情绪氛围\n"
            + "5. 是否含二维码/条形码（如有请尝试描述其用途）\n"
            + "6. 是否有政治敏感、色情、成人、暴力等违规内容（有则明确标注「违规」）\n"
            + "请用中文简洁描述，尽量详尽但不要臆测。";

    /** 视觉分析（vision 工具）：下载图片转 Base64 后调用视觉模型，返回图片详细特征描述。 */
    private String executeVision(String argumentsJson, ToolContext ctx) {
        String url = arg(argumentsJson, "url");
        if (url == null || url.trim().isEmpty()) {
            return "[vision] 请提供 url 参数（图片 URL http/https 或 file_id）";
        }
        DeepSeekClient client = actionHandler.getClient();
        if (client == null) {
            return "[vision] 视觉模型客户端不可用";
        }
        sair.aiagent.onebot.NapCatApi napcat = ctx != null ? ctx.napcatApi : null;
        try {
            String visionImage = sair.aiagent.onebot.ImageDownloader.resolveImageForVision(url.trim(), napcat);
            if (sair.aiagent.onebot.ImageDownloader.isOversizeResult(visionImage)) {
                return "[vision] 图片超过3M大小限制，我（bot）不看！";
            }
            if (visionImage == null) {
                return "[vision] 图片下载或转换失败，无法分析（可能图片已过期或不可访问）";
            }
            String cacheKey = sair.aiagent.onebot.ImageRecognizer.visionCacheKey(visionImage);
            String cached = sair.aiagent.onebot.ImageRecognizer.getCachedVisionResult(cacheKey);
            if (cached != null) {
                return "[vision] 图片分析结果:\n" + cached;
            }
            String result = client.chatVision(java.util.Collections.singletonList(visionImage), VISION_PROMPT);
            if (result == null || result.trim().isEmpty()) {
                return "[vision] 视觉模型未返回有效结果";
            }
            sair.aiagent.onebot.ImageRecognizer.putCachedVisionResult(cacheKey, result);
            return "[vision] 图片分析结果:\n" + result.trim();
        } catch (Exception e) {
            return "[vision] 视觉分析失败: " + e.toString();
        }
    }

    /** 全局记忆检索（searchglobal 工具）：综合群聊历史、对话历史、AI 长期记忆三源检索。 */
    private String executeSearchGlobal(String argumentsJson, ToolContext ctx) {
        String query = arg(argumentsJson, "query");
        if (query == null || query.trim().isEmpty()) {
            return "[searchglobal] 请提供 query（检索关键词）";
        }
        sair.aiagent.onebot.UnifiedQQMemoryManager mem = ctx != null ? ctx.unifiedMemory : null;
        if (mem == null) {
            return "[searchglobal] 统一记忆库不可用（仅 QQ 通道支持全局记忆检索）";
        }
        String q = query.trim();
        StringBuilder sb = new StringBuilder("[searchglobal] 全局记忆检索结果（query: ").append(q).append("）");
        int hitCount = 0;

        // 1. 群聊历史（跨群，含所有群消息）
        java.util.List<String> groupHits = mem.searchGroupChatHistory(q, 5);
        if (groupHits != null && !groupHits.isEmpty()) {
            sb.append("\n【群聊记录】");
            for (String h : groupHits) { sb.append("\n- ").append(h); hitCount++; }
        }

        // 2. 对话历史（私聊 + 群聊，跨用户）
        java.util.List<String> convHits = mem.searchConversations(q, 5);
        if (convHits != null && !convHits.isEmpty()) {
            sb.append("\n【对话历史】");
            for (String h : convHits) { sb.append("\n- ").append(h); hitCount++; }
        }

        // 3. AI 长期记忆
        java.util.List<String> memHits = mem.searchMemories(q, 3);
        if (memHits != null && !memHits.isEmpty()) {
            sb.append("\n【长期记忆】");
            for (String h : memHits) { sb.append("\n- ").append(h); hitCount++; }
        }

        if (hitCount == 0) {
            return "[searchglobal] 未找到与「" + q + "」相关的记录";
        }
        return sb.toString();
    }

    /** 消息 Mark 备注（markmessage 工具）：AI 自用，标记已处理消息，避免重复处理。 */
    private String executeMarkMessage(String argumentsJson, ToolContext ctx) {
        String action = arg(argumentsJson, "action");
        String message = arg(argumentsJson, "message");
        String mark = arg(argumentsJson, "mark");
        sair.aiagent.onebot.UnifiedQQMemoryManager mem = ctx != null ? ctx.unifiedMemory : null;
        if (mem == null) {
            return "[markmessage] 统一记忆库不可用（仅 QQ 通道支持消息备注）";
        }
        String srcType = "private";
        long srcId = 0;
        long senderQQ = 0;
        if (ctx != null && ctx.qqMsg != null) {
            senderQQ = ctx.qqMsg.getUserId();
            if (ctx.qqMsg.isGroupMessage()) {
                srcType = "group";
                srcId = ctx.qqMsg.getGroupId();
            } else {
                srcId = ctx.qqMsg.getUserId();
            }
        }
        if (action == null) action = "";
        action = action.trim();

        if ("list".equals(action)) {
            if ("group".equals(srcType)) {
                java.util.List<String[]> list = mem.getRecentGroupChatHistoryWithMark(srcId, 30);
                StringBuilder sb = new StringBuilder("[markmessage] 最近带 Mark 备注的消息:\n");
                int c = 0;
                for (String[] m : list) {
                    if (m != null && m.length > 3 && m[3] != null && !m[3].isEmpty()) {
                        String content = m[2] != null ? m[2] : "";
                        if (content.length() > 60) content = content.substring(0, 60) + "...";
                        sb.append("- \"").append(content).append("\" → ").append(m[3]).append("\n");
                        c++;
                    }
                }
                return c > 0 ? sb.toString() : "[markmessage] 最近没有带 Mark 备注的消息";
            }
            return "[markmessage] list 仅群聊场景支持";
        }

        if ("get".equals(action)) {
            if (message == null || message.trim().isEmpty()) {
                return "[markmessage] get 需要 message 参数（要查询的消息内容）";
            }
            String existing = "group".equals(srcType)
                    ? mem.getGroupMessageMark(srcId, senderQQ, message.trim())
                    : mem.getConversationMark(srcType, srcId, message.trim());
            return existing != null && !existing.isEmpty()
                    ? "[markmessage] 该消息已有备注: " + existing
                    : "[markmessage] 该消息暂无备注（说明尚未处理或无需处理）";
        }

        if ("set".equals(action)) {
            if (mark == null || mark.trim().isEmpty()) {
                return "[markmessage] set 需要 mark 参数（备注内容）";
            }
            String target = (message != null && !message.trim().isEmpty())
                    ? message.trim()
                    : (ctx != null && ctx.qqMsg != null ? ctx.qqMsg.getPlainText() : null);
            if (target == null || target.trim().isEmpty()) {
                return "[markmessage] 无法确定要标记的消息（请提供 message 参数）";
            }
            if ("group".equals(srcType)) {
                mem.setGroupMessageMark(srcId, senderQQ, target, mark.trim());
            } else {
                mem.setConversationMark(srcType, srcId, target, mark.trim());
            }
            String brief = target.length() > 40 ? target.substring(0, 40) + "..." : target;
            return "[markmessage] 已标记消息 \"" + brief + "\" → " + mark.trim();
        }

        return "[markmessage] 用法: action=set（打备注，mark 必填）/ get（查备注，message 必填）/ list（列出最近带备注消息）";
    }

    /** 设置结构化偏好（setpreference 工具）。 */
    private String executeSetPreference(String argumentsJson, ToolContext ctx) {
        String scope = arg(argumentsJson, "scope");
        String key = arg(argumentsJson, "key");
        String value = arg(argumentsJson, "value");
        if (scope == null || scope.trim().isEmpty()) scope = "user";
        scope = scope.trim().toLowerCase();
        if (!"user".equals(scope) && !"group".equals(scope) && !"global".equals(scope)) {
            return "[setpreference] scope 必须是 user/group/global";
        }
        if (key == null || key.trim().isEmpty()) return "[setpreference] 缺少 key";
        if (value == null || value.trim().isEmpty()) return "[setpreference] 缺少 value";

        long targetId = 0;
        boolean isMaster = ctx == null || ctx.isMaster;
        int affection = 0;
        if (ctx != null) {
            if ("group".equals(scope) && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage()) {
                targetId = ctx.qqMsg.getGroupId();
            } else if ("user".equals(scope)) {
                targetId = ctx.senderQQ > 0 ? ctx.senderQQ : 0;
            }
            if (ctx.emotionManager != null && targetId > 0) {
                affection = ctx.emotionManager.getAffection(targetId);
            }
        }

        // 印象差的人不接受任何设定（主人除外）
        if (!isMaster && "user".equals(scope) && targetId > 0) {
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm != null) {
                sair.aiagent.model.ImpressionEntry imp = pm.getImpression(targetId);
                if (imp != null && imp.isBad()) {
                    return "[setpreference] 该用户当前印象较差，暂不接受偏好设定，待印象改观后再试";
                }
            }
        }

        // 优先级：主人最高；其余按好感度映射 importance
        int importance;
        if (isMaster) {
            importance = 10;
        } else if (affection >= 800) {
            importance = 8;
        } else if (affection >= 400) {
            importance = 6;
        } else if (affection >= 200) {
            importance = 4;
        } else {
            importance = 2;
        }

        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) return "[setpreference] 持久化层未初始化";
        pm.setPreference(scope, targetId, key.trim(), value.trim(), importance);
        return "[setpreference] 已设置 " + scope + "/" + targetId + " 的偏好 [" + key.trim() + "]（importance=" + importance + "）";
    }

    /** 查询结构化偏好（getpreference 工具）。 */
    private String executeGetPreference(String argumentsJson, ToolContext ctx) {
        String scope = arg(argumentsJson, "scope");
        String key = arg(argumentsJson, "key");
        if (scope == null || scope.trim().isEmpty()) scope = "user";
        scope = scope.trim().toLowerCase();
        if (!"user".equals(scope) && !"group".equals(scope) && !"global".equals(scope)) {
            return "[getpreference] scope 必须是 user/group/global";
        }
        long targetId = 0;
        if (ctx != null) {
            if ("group".equals(scope) && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage()) {
                targetId = ctx.qqMsg.getGroupId();
            } else if ("user".equals(scope)) {
                targetId = ctx.senderQQ > 0 ? ctx.senderQQ : 0;
            }
        }
        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) return "[getpreference] 持久化层未初始化";
        if (key != null && !key.trim().isEmpty()) {
            String v = pm.getPreference(scope, targetId, key.trim());
            return v != null ? "[getpreference] " + key.trim() + " = " + v
                    : "[getpreference] 未找到 " + scope + "/" + targetId + " 的偏好 [" + key.trim() + "]";
        }
        java.util.List<String[]> list = pm.listPreferences(scope, targetId);
        if (list.isEmpty()) return "[getpreference] " + scope + "/" + targetId + " 暂无偏好";
        StringBuilder sb = new StringBuilder("[getpreference] ").append(scope).append("/").append(targetId).append(" 的偏好:\n");
        for (String[] p : list) {
            sb.append("- ").append(p[0]).append(" = ").append(p[1]).append(" (importance=").append(p[2]).append(")\n");
        }
        return sb.toString().trim();
    }

    /** 查询技能/工具的完整说明书（skillinfo 工具）。 */
    private String executeSkillInfo(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "[skillinfo] 用法: 传 name 参数指定技能名或工具名";
        }
        String detail = SkillBank.getInstance().getTagDetail(name.trim());
        if (detail == null) {
            return "[skillinfo] 未找到技能或工具: " + name + "（可尝试其它名称）";
        }
        return detail;
    }

    /** 列出待处理的好友申请与群邀请（pendingrequests 工具）。 */
    private String executePendingRequests(ToolContext ctx) {
        if (ctx == null || ctx.pendingRequestPool == null) {
            return "[pendingrequests] 待处理请求池不可用（仅QQ通道）";
        }
        java.util.List<sair.aiagent.onebot.PendingRequestPool.PendingRequest> list = ctx.pendingRequestPool.list();
        if (list.isEmpty()) {
            return "[pendingrequests] 当前没有待处理的好友申请或群邀请";
        }
        StringBuilder sb = new StringBuilder("[pendingrequests] 待处理请求:\n");
        for (sair.aiagent.onebot.PendingRequestPool.PendingRequest r : list) {
            String typeLabel = "friend".equals(r.type) ? "好友申请" : "群邀请";
            sb.append("- ").append(typeLabel)
              .append(" | flag=").append(r.flag)
              .append(" | 申请人QQ=").append(r.userId);
            if (r.groupId > 0) sb.append(" | 群号=").append(r.groupId);
            if (r.comment != null && !r.comment.isEmpty()) sb.append(" | 验证消息=").append(r.comment);
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 同意好友申请（需主人或好感度≥100）。 */
    private String executeApproveFriend(String flag, ToolContext ctx) {
        if (ctx == null || ctx.pendingRequestPool == null || ctx.napcatApi == null) {
            return "[approvefriend] 仅QQ通道可用";
        }
        if (flag == null || flag.trim().isEmpty()) {
            return "[approvefriend] 错误：请提供 flag";
        }
        sair.aiagent.onebot.PendingRequestPool.PendingRequest r = ctx.pendingRequestPool.get(flag.trim());
        if (r == null || !"friend".equals(r.type)) {
            return "[approvefriend] 未找到对应好友申请（请用 pendingrequests 查看）";
        }
        String resp = ctx.napcatApi.handleFriendRequest(flag.trim(), true, null);
        ctx.pendingRequestPool.remove(flag.trim());
        return "[approvefriend] 已同意好友申请: QQ=" + r.userId + " " + resp;
    }

    /** 拒绝好友申请。 */
    private String executeRejectFriend(String flag, ToolContext ctx) {
        if (ctx == null || ctx.pendingRequestPool == null || ctx.napcatApi == null) {
            return "[rejectfriend] 仅QQ通道可用";
        }
        if (flag == null || flag.trim().isEmpty()) {
            return "[rejectfriend] 错误：请提供 flag";
        }
        sair.aiagent.onebot.PendingRequestPool.PendingRequest r = ctx.pendingRequestPool.get(flag.trim());
        if (r == null || !"friend".equals(r.type)) {
            return "[rejectfriend] 未找到对应好友申请";
        }
        String resp = ctx.napcatApi.handleFriendRequest(flag.trim(), false, "拒绝");
        ctx.pendingRequestPool.remove(flag.trim());
        return "[rejectfriend] 已拒绝好友申请: QQ=" + r.userId + " " + resp;
    }

    /** 同意群邀请（需主人或好感度≥300）。 */
    private String executeAcceptGroupInvite(String flag, ToolContext ctx) {
        if (ctx == null || ctx.pendingRequestPool == null || ctx.napcatApi == null) {
            return "[acceptgroupinvite] 仅QQ通道可用";
        }
        if (flag == null || flag.trim().isEmpty()) {
            return "[acceptgroupinvite] 错误：请提供 flag";
        }
        sair.aiagent.onebot.PendingRequestPool.PendingRequest r = ctx.pendingRequestPool.get(flag.trim());
        if (r == null || !"group".equals(r.type)) {
            return "[acceptgroupinvite] 未找到对应群邀请（请用 pendingrequests 查看）";
        }
        String resp = ctx.napcatApi.handleGroupInvite(flag.trim(), true, null);
        ctx.pendingRequestPool.remove(flag.trim());
        return "[acceptgroupinvite] 已同意群邀请: 群=" + r.groupId + " " + resp;
    }

    /** 拒绝群邀请。 */
    private String executeRejectGroupInvite(String flag, ToolContext ctx) {
        if (ctx == null || ctx.pendingRequestPool == null || ctx.napcatApi == null) {
            return "[rejectgroupinvite] 仅QQ通道可用";
        }
        if (flag == null || flag.trim().isEmpty()) {
            return "[rejectgroupinvite] 错误：请提供 flag";
        }
        sair.aiagent.onebot.PendingRequestPool.PendingRequest r = ctx.pendingRequestPool.get(flag.trim());
        if (r == null || !"group".equals(r.type)) {
            return "[rejectgroupinvite] 未找到对应群邀请";
        }
        String resp = ctx.napcatApi.handleGroupInvite(flag.trim(), false, "拒绝");
        ctx.pendingRequestPool.remove(flag.trim());
        return "[rejectgroupinvite] 已拒绝群邀请: 群=" + r.groupId + " " + resp;
    }

    /** 查看机器人自己的 QQ 资料（getselfinfo 工具）。 */
    private String executeGetSelfInfo(ToolContext ctx) {
        if (ctx == null || ctx.napcatApi == null) {
            return "[getselfinfo] 仅QQ通道可用";
        }
        String info = ctx.napcatApi.getLoginInfo();
        return "[getselfinfo] " + (info != null && !info.isEmpty() ? info : "获取失败");
    }

    /** 记录捐赠（仅主人可用，1元=1好感度）。 */
    private String executeRecordDonation(String argumentsJson, ToolContext ctx) {
        if (ctx == null) {
            return "[recorddonation] 仅QQ通道可用";
        }
        if (ctx.emotionManager == null) {
            return "[recorddonation] 情绪管理器不可用";
        }
        String amountStr = arg(argumentsJson, "amount");
        String donor = arg(argumentsJson, "donor");
        String note = arg(argumentsJson, "note");
        int amount;
        try {
            amount = (int) Math.round(Double.parseDouble(amountStr == null ? "" : amountStr.trim()));
        } catch (Exception e) {
            return "[recorddonation] 错误：amount 必须是数字（元）";
        }
        if (amount <= 0) {
            return "[recorddonation] 错误：捐赠金额必须大于0";
        }
        long targetUserId;
        String userName;
        if (donor == null || donor.trim().isEmpty()) {
            // 未指定捐赠者 → 主人自己
            targetUserId = ctx.senderQQ;
            userName = null;
        } else if (donor.trim().matches("\\d+")) {
            // 纯数字 → QQ 号
            targetUserId = Long.parseLong(donor.trim());
            userName = null;
        } else {
            // 昵称 → 定位 QQ；定位失败报错
            long found = resolveUserByNickname(donor.trim(), -1, ctx);
            if (found <= 0) {
                return "[recorddonation] 未找到捐赠者「" + donor.trim() + "」对应的 QQ，请提供 QQ 号";
            }
            targetUserId = found;
            userName = donor.trim();
        }
        ctx.emotionManager.onDonation(targetUserId, userName, amount, note);
        int cur = ctx.emotionManager.getAffection(targetUserId);
        String label = (userName != null && !userName.isEmpty()) ? userName : ("QQ" + targetUserId);
        return "[recorddonation] 已记录捐赠：" + label + " 捐赠 " + amount + " 元，好感度 +" + amount + "（当前 " + cur + "）";
    }

    /** 查询捐赠排行榜（任何人可查）：Top10 + 触发者自身金额。 */
    private String executeDonationList(String json, ToolContext ctx) {
        if (ctx == null || ctx.emotionManager == null) {
            return "[donationlist] 情绪管理器不可用（仅QQ通道）";
        }
        java.util.List<String[]> top = ctx.emotionManager.getDonationTop(10);
        long me = ctx.senderQQ > 0 ? ctx.senderQQ : 0;
        int myTotal = me > 0 ? ctx.emotionManager.getTotalDonation(me) : 0;

        StringBuilder sb = new StringBuilder("[donationlist] 捐赠排行榜 Top").append(top.size()).append(":\n");
        int rank = 1;
        for (String[] r : top) {
            long uid = Long.parseLong(r[0]);
            String name = (r[1] != null && !r[1].isEmpty()) ? r[1] : (uid > 0 ? ("QQ" + uid) : "未知");
            int total = Integer.parseInt(r[2]);
            sb.append(rank++).append(". ").append(name).append(" 捐赠 ").append(total).append(" 元\n");
        }
        if (top.isEmpty()) {
            sb.append("（暂无捐赠记录）\n");
        }
        sb.append("\n[donationlist] 你的捐赠总额：").append(myTotal).append(" 元");
        return sb.toString();
    }

    /** 重置某人的捐赠名单（仅主人）。 */
    private String executeResetDonation(String json, ToolContext ctx) {
        if (ctx == null || ctx.emotionManager == null) {
            return "[resetdonation] 情绪管理器不可用（仅QQ通道）";
        }
        String target = arg(json, "target");
        if (target == null || target.trim().isEmpty()) {
            return "[resetdonation] 错误：请提供 target（目标用户 QQ 号或昵称）";
        }
        target = target.trim();
        long targetQQ;
        if (target.matches("\\d+")) {
            targetQQ = Long.parseLong(target);
        } else {
            targetQQ = resolveUserByNickname(target, -1, ctx);
            if (targetQQ <= 0) {
                return "[resetdonation] 未找到昵称「" + target + "」对应的用户，请改用 QQ 号";
            }
        }
        int deleted = ctx.emotionManager.resetDonation(targetQQ);
        if (deleted <= 0) {
            return "[resetdonation] 未找到 QQ" + targetQQ + " 的捐赠记录";
        }
        return "[resetdonation] 已重置 QQ" + targetQQ + " 的捐赠名单（移除 " + deleted + " 条记录）";
    }

    /** 查询好感度（queryaffection 工具）。 */
    private String executeQueryAffection(String json, ToolContext ctx) {
        if (ctx == null || ctx.emotionManager == null) {
            return "[queryaffection] 情绪管理器不可用";
        }
        long userId = extractLong(json, "user_id", -1);
        long groupId = extractLong(json, "group_id", -1);
        long count = extractLong(json, "count", -1);

        // 1. 指定单个用户
        if (userId > 0) {
            int aff = ctx.emotionManager.getAffection(userId);
            String attitude = ctx.emotionManager.getUserAttitudeDescription(userId);
            return "[queryaffection] QQ" + userId + " 好感度: " + aff + "/1000，关系: " + attitude;
        }

        // 2. 群成员好感度列表
        if (groupId <= 0 && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage()) {
            groupId = ctx.qqMsg.getGroupId();
        }
        if (groupId > 0 && ctx.napcatApi != null) {
            try {
                String resp = ctx.napcatApi.getGroupMemberList(groupId);
                String dataArr = sair.aiagent.onebot.util.JsonUtil.extractArray(resp, "data");
                if (dataArr == null || dataArr.trim().isEmpty()) {
                    return "[queryaffection] 群成员列表查询失败";
                }
                List<String[]> members = new ArrayList<>();
                for (String m : sair.aiagent.onebot.util.JsonUtil.splitJsonArray(dataArr)) {
                    String uid = sair.aiagent.onebot.util.JsonUtil.extractString(m, "user_id");
                    if (uid == null || uid.isEmpty()) continue;
                    String nick = sair.aiagent.onebot.util.JsonUtil.extractString(m, "nickname");
                    String card = sair.aiagent.onebot.util.JsonUtil.extractString(m, "card");
                    String name = (card != null && !card.isEmpty()) ? card : (nick != null && !nick.isEmpty() ? nick : "(未知)");
                    members.add(new String[]{uid, name});
                }
                if (members.isEmpty()) return "[queryaffection] 群成员列表为空";
                if (count > 0 && count < members.size()) {
                    java.util.Collections.shuffle(members);
                    members = members.subList(0, (int) count);
                }
                StringBuilder sb = new StringBuilder("[queryaffection] 群 ").append(groupId).append(" 成员好感度:\n");
                for (String[] mem : members) {
                    long uid = Long.parseLong(mem[0]);
                    int aff = ctx.emotionManager.getAffection(uid);
                    String attitude = ctx.emotionManager.getUserAttitudeDescription(uid);
                    sb.append("- ").append(mem[1]).append("(QQ:").append(uid).append(") 好感度: ").append(aff)
                      .append("/1000，").append(attitude).append("\n");
                }
                return sb.toString();
            } catch (Exception e) {
                return "[queryaffection] 查询失败: " + e.toString();
            }
        }

        // 3. 全部已知好感度
        Map<Long, Integer> all = ctx.emotionManager.getAllAffections();
        if (all.isEmpty()) return "[queryaffection] 暂无任何用户的好感度记录";
        StringBuilder sb = new StringBuilder("[queryaffection] 全部用户好感度(" + all.size() + "人):\n");
        for (Map.Entry<Long, Integer> e : all.entrySet()) {
            sb.append("- QQ:").append(e.getKey()).append(" 好感度: ").append(e.getValue()).append("/1000\n");
        }
        return sb.toString();
    }

    /** 设置好感度（setaffection 工具，仅主人）。 */
    private String executeSetAffection(String json, ToolContext ctx) {
        if (ctx.emotionManager == null) return "[setaffection] 情绪管理器不可用";
        String target = arg(json, "target");
        if (target == null || target.trim().isEmpty()) return "[setaffection] 缺少 target 参数";
        target = target.trim();
        long affection = extractLong(json, "affection", Long.MIN_VALUE);
        if (affection == Long.MIN_VALUE) return "[setaffection] 缺少 affection 参数";
        long groupId = extractLong(json, "group_id", -1);

        long targetQQ = -1;
        if (target.matches("\\d+")) {
            targetQQ = Long.parseLong(target);
        } else {
            targetQQ = resolveUserByNickname(target, groupId, ctx);
            if (targetQQ <= 0) {
                return "[setaffection] 未找到昵称「" + target + "」对应的用户，请改用QQ号";
            }
        }

        ctx.emotionManager.setAffection(targetQQ, (int) affection);
        int cur = ctx.emotionManager.getAffection(targetQQ);
        String attitude = ctx.emotionManager.getUserAttitudeDescription(targetQQ);
        return "[setaffection] 已将 QQ" + targetQQ + " 的好感度设置为 " + cur + "/1000，关系: " + attitude;
    }

    /** 查询好感度排行（affectionrank 工具）：本群默认Top10，全局仅主人。 */
    private String executeAffectionRank(String json, ToolContext ctx) {
        if (ctx == null || ctx.emotionManager == null) {
            return "[affectionrank] 情绪管理器不可用";
        }
        String scope = arg(json, "scope");
        boolean global = scope != null && scope.trim().equalsIgnoreCase("global");
        if (global) {
            if (!isMasterOnly(ctx)) return "[affectionrank] 无权限：全局好感度排行仅主人可查询";
            List<long[]> ranking = ctx.emotionManager.getGlobalAffectionRanking(10);
            return formatRanking("全局", ranking);
        }
        long groupId = extractLong(json, "group_id", -1);
        if (groupId <= 0 && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage()) {
            groupId = ctx.qqMsg.getGroupId();
        }
        if (groupId <= 0) return "[affectionrank] 缺少群号（私聊请显式指定 group_id 参数）";
        List<long[]> ranking = ctx.emotionManager.getGroupAffectionRanking(groupId, 10);
        return formatRanking("群 " + groupId, ranking);
    }

    /** 格式化好感度排行输出。 */
    private String formatRanking(String scope, List<long[]> ranking) {
        if (ranking == null || ranking.isEmpty()) {
            return "[affectionrank] " + scope + " 暂无好感度排行数据";
        }
        StringBuilder sb = new StringBuilder("[affectionrank] ").append(scope).append(" 好感度Top").append(ranking.size()).append(":\n");
        int i = 1;
        for (long[] r : ranking) {
            sb.append(i++).append(". QQ:").append(r[0]).append(" 好感度:").append(r[1]).append("/1000\n");
        }
        return sb.toString();
    }

    /**
     * 系统闹钟（alarm 工具）—— 子命令模式。
     * <p>到点唤醒 AI 走 Agent 链路执行任务（区别于 schedule 只执行 SFW 命令）。</p>
     * <ul>
     *   <li>{@code add schedule|task} —— 创建闹钟（含上下文快照）。schedule=once:yyyy-MM-dd HH:mm / daily:HH:mm / weekly:D,HH:mm</li>
     *   <li>{@code list} —— 列出闹钟</li>
     *   <li>{@code remove id} —— 移除闹钟（删除快照）</li>
     *   <li>{@code cancel id} —— 取消重复闹钟（等价 remove）</li>
     *   <li>{@code append id|内容} —— 追加备注到快照</li>
     * </ul>
     */
    private String executeAlarm(String argumentsJson, ToolContext ctx) {
        String content = arg(argumentsJson, "content");
        if (content == null || content.trim().isEmpty()) {
            return "[alarm] 用法: add schedule|task | list | remove id | cancel id | append id|备注内容";
        }
        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) {
            return "[alarm] 错误：持久化层未初始化";
        }
        String cmd = content.trim();
        String lower = cmd.toLowerCase();

        if (lower.equals("list")) {
            List<sair.aiagent.model.AlarmEntry> alarms = pm.listAlarms();
            if (alarms.isEmpty()) return "[alarm] 暂无闹钟";
            StringBuilder sb = new StringBuilder("[alarm] 闹钟列表:\n");
            for (sair.aiagent.model.AlarmEntry a : alarms) {
                sb.append(a.toString()).append("\n");
            }
            return sb.toString().trim();
        }

        if (lower.startsWith("remove ")) {
            return removeAlarmById(pm, lower.substring(7).trim());
        }
        if (lower.startsWith("cancel ")) {
            return removeAlarmById(pm, lower.substring(7).trim());
        }
        if (lower.startsWith("append ")) {
            return appendAlarmNote(pm, lower.substring(7).trim());
        }
        if (lower.startsWith("add ")) {
            return addAlarm(pm, lower.substring(4).trim(), ctx);
        }
        return "[alarm] 未知子命令: " + cmd + "（支持 add / list / remove / cancel / append）";
    }

    private String removeAlarmById(PersistenceManager pm, String idStr) {
        try {
            int id = Integer.parseInt(idStr.trim());
            return pm.removeAlarm(id)
                    ? "[alarm] 闹钟 #" + id + " 已移除"
                    : "[alarm] 闹钟 #" + id + " 不存在";
        } catch (NumberFormatException e) {
            return "[alarm] 错误：id 必须是数字";
        }
    }

    private String appendAlarmNote(PersistenceManager pm, String argStr) {
        int sep = argStr.indexOf('|');
        if (sep <= 0) return "[alarm] append 格式错误: append id|备注内容";
        String idStr = argStr.substring(0, sep).trim();
        String noteContent = argStr.substring(sep + 1).trim();
        if (noteContent.isEmpty()) return "[alarm] append 备注内容不能为空";
        int id;
        try {
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return "[alarm] 错误：id 必须是数字";
        }
        sair.aiagent.model.AlarmEntry alarm = pm.getAlarm(id);
        if (alarm == null) return "[alarm] 闹钟 #" + id + " 不存在";

        // 追加备注到 notes JSON 数组
        java.util.List<Object> notes = new java.util.ArrayList<>();
        String existing = alarm.getNotes();
        if (existing != null && !existing.trim().isEmpty()) {
            try {
                com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(existing).getAsJsonArray();
                for (com.google.gson.JsonElement e : arr) notes.add(e);
            } catch (Exception ignored) {}
        }
        com.google.gson.JsonObject note = new com.google.gson.JsonObject();
        note.addProperty("ts", System.currentTimeMillis());
        note.addProperty("content", noteContent);
        notes.add(note);
        String newNotes = new com.google.gson.Gson().toJson(notes);
        pm.appendAlarmNote(id, newNotes);
        return "[alarm] 已追加备注到闹钟 #" + id + ": " + noteContent;
    }

    /** 创建闹钟：确定 scope，构建上下文快照，写入数据库。 */
    private String addAlarm(PersistenceManager pm, String argStr, ToolContext ctx) {
        int sep = argStr.indexOf('|');
        if (sep <= 0) return "[alarm] add 格式错误: add schedule|task";
        String schedule = argStr.substring(0, sep).trim();
        String task = argStr.substring(sep + 1).trim();
        if (schedule.isEmpty()) return "[alarm] 错误：schedule 不能为空";
        if (task.isEmpty()) return "[alarm] 错误：task 不能为空";

        // 校验 schedule 格式
        long triggerCheck = AlarmScheduler.computeNextTrigger(
                new sair.aiagent.model.AlarmEntry(0, "REMIND", schedule, task, null, null,
                        "console", 0, false, 0, false, false, true, 0, 0),
                System.currentTimeMillis());
        if (triggerCheck < 0) {
            return "[alarm] 无法解析 schedule: " + schedule + "\n支持格式: once:yyyy-MM-dd HH:mm | daily:HH:mm | weekly:D,HH:mm（D=1周一~7周日）";
        }

        // 语义判断重复性：once=一次性，daily/weekly=重复
        boolean repeat = schedule.startsWith("daily:") || schedule.startsWith("weekly:");

        // scope 权限分级：console→EXECS；execq 普通用户→REMIND；execq 主人→EXECQ；execs 全权限→EXECS
        String scope;
        boolean isQq = ctx != null && ctx.isExecq();
        if (!isQq) {
            scope = "EXECS";
        } else if (ctx.execsMode) {
            scope = "EXECS";
        } else if (ctx.isMaster) {
            scope = "EXECQ";
        } else {
            scope = "REMIND";
        }

        // 上下文快照 {stableSystem, dynamicContext, model}
        com.google.gson.JsonObject snapshot = new com.google.gson.JsonObject();
        snapshot.addProperty("stableSystem", ctx != null && ctx.stableSystemPrompt != null ? ctx.stableSystemPrompt : "");
        snapshot.addProperty("dynamicContext", ctx != null && ctx.dynamicContext != null ? ctx.dynamicContext : "");
        snapshot.addProperty("model", ctx != null && ctx.model != null ? ctx.model : "");
        String snapshotJson = new com.google.gson.Gson().toJson(snapshot);

        String channel = isQq ? "execq" : "console";
        long senderQq = ctx != null ? ctx.senderQQ : 0;
        boolean isGroup = ctx != null && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage();
        long groupId = isGroup ? ctx.qqMsg.getGroupId() : 0;
        boolean isMaster = ctx != null && ctx.isMaster;

        int id = pm.addAlarm(scope, schedule, task, snapshotJson, "[]", channel,
                senderQq, isGroup, groupId, isMaster, repeat);
        if (id < 0) return "[alarm] 创建失败";

        String scopeLabel = "REMIND".equals(scope) ? "提醒" : ("EXECQ".equals(scope) ? "AI任务(execq)" : "AI任务(execs)");
        String typeLabel = repeat ? "重复" : "一次性";
        return "[alarm] 闹钟 #" + id + " 已创建（" + typeLabel + "，" + scopeLabel + "）: " + schedule + " | " + task;
    }

    /** 按昵称定位 QQ 号（先查群昵称映射，再查跨群个人昵称映射）。 */
    private long resolveUserByNickname(String nickname, long groupId, ToolContext ctx) {
        if (ctx == null || ctx.unifiedMemory == null) return -1;
        long gid = groupId;
        if (gid <= 0 && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage()) {
            gid = ctx.qqMsg.getGroupId();
        }
        if (gid > 0) {
            Map<String, Long> nickMap = ctx.unifiedMemory.getGroupNicknameMap(gid);
            if (nickMap != null) {
                Long qq = nickMap.get(nickname);
                if (qq != null) return qq;
            }
        }
        Map<String, Long> personalMap = ctx.unifiedMemory.getPersonalNicknameMap();
        if (personalMap != null) {
            Long qq = personalMap.get(nickname);
            if (qq != null) return qq;
        }
        return -1;
    }

    /** 修改/设置图片注释（setimageremark 工具）：按 MD5 强绑定写入持久化。 */
    private String executeSetImageRemark(String json) {
        String remark = FunctionCallingBridge.extractArg(json, "remark");
        if (remark == null || remark.trim().isEmpty()) {
            return "[setimageremark] 错误：remark 不能为空";
        }
        String md5 = FunctionCallingBridge.extractArg(json, "image_md5");
        if (md5 == null || md5.trim().isEmpty()) {
            String imageUrl = FunctionCallingBridge.extractArg(json, "image_url");
            if (imageUrl == null || imageUrl.trim().isEmpty()) {
                return "[setimageremark] 错误：需提供 image_url 或 image_md5 之一";
            }
            try {
                byte[] bytes = sair.aiagent.onebot.ImageDownloader.downloadImage(imageUrl.trim());
                if (bytes == null || bytes.length == 0) {
                    return "[setimageremark] 错误：图片下载失败，无法计算 MD5";
                }
                md5 = sair.aiagent.onebot.ImageRecognizer.md5(bytes);
            } catch (Exception e) {
                return "[setimageremark] 错误：图片下载异常: " + e.toString();
            }
        }
        md5 = md5.trim().toLowerCase();
        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) {
            return "[setimageremark] 错误：持久化层未初始化";
        }
        pm.setImageRemark(md5, remark.trim(), "ai");
        return "[setimageremark] 已更新图片注释（MD5: " + md5 + "）";
    }

    /** 调用三方技能代码段 airun 入口（callskill 工具）。是否在 execq/QQ 通道可用由 setthirdpartycode 配置决定。 */
    private String executeCallSkill(String json, ToolContext ctx) {
        String skillName = arg(json, "skill");
        if (skillName == null || skillName.trim().isEmpty()) return "[callskill] 缺少 skill 参数（技能名）";
        String argsJson = arg(json, "args");
        ThirdPartySkillStore store = SkillBank.getInstance().getThirdPartyStore();
        if (store == null) return "[callskill] 三方技能库未初始化";
        ThirdPartySkill skill = store.get(skillName.trim());
        if (skill == null) return "[callskill] 未找到三方技能: " + skillName.trim();
        String blocked = checkThirdPartySkillExecqSafety(skill, ctx);
        if (blocked != null) return blocked;
        return invokeSkill(skill, argsJson);
    }

    /** 执行动态注册的三方技能工具（tp_ 前缀），组装入参后调用 airun 入口。 */
    private String executeThirdPartyTool(String skillName, String argumentsJson, ToolContext ctx) {
        ThirdPartySkillStore store = SkillBank.getInstance().getThirdPartyStore();
        if (store == null) return "[callskill] 三方技能库未初始化";
        ThirdPartySkill skill = store.get(skillName);
        if (skill == null) return "[callskill] 未找到三方技能: " + skillName;
        String blocked = checkThirdPartySkillExecqSafety(skill, ctx);
        if (blocked != null) return blocked;
        String argsJson = argumentsJson;
        // 未声明 airun 参数时，工具只有一个 args 字段，取出其原始 JSON 作为 airun 入参
        if (!skill.hasAirunSchema()) {
            String raw = arg(argumentsJson, "args");
            if (raw != null && !raw.trim().isEmpty()) argsJson = raw;
        }
        return invokeSkill(skill, argsJson);
    }

    private static String checkThirdPartySkillExecqSafety(ThirdPartySkill skill, ToolContext ctx) {
        if (ctx == null || !ctx.isExecq() || ctx.execsMode || ctx.isMaster) return null;
        if (skill == null || skill.getCodeBlocks() == null || skill.getCodeBlocks().isEmpty()) return null;
        StringBuilder code = new StringBuilder();
        for (String block : skill.getCodeBlocks().values()) {
            if (block != null) code.append(block).append('\n');
        }
        String c = code.toString().toLowerCase();
        for (String pattern : HarnessConfig.getInstance().getForbiddenCodePatterns()) {
            if (c.contains(pattern)) {
                return "[harness] 代码安全阻断：三方技能 " + skill.getName() + " 含禁用模式 '" + pattern + "'";
            }
        }
        return null;
    }

    /** 实际调用三方技能 airun 入口（懒创建 SkillCodeRunner）。 */
    private String invokeSkill(ThirdPartySkill skill, String argsJson) {
        if (skillCodeRunner == null) {
            synchronized (this) {
                if (skillCodeRunner == null) {
                    String dataDir = actionHandler.getSelfActivity() != null
                            ? actionHandler.getSelfActivity().getDataDir() : null;
                    File tmp = dataDir != null ? new File(dataDir, "tmp") : null;
                    skillCodeRunner = new SkillCodeRunner(actionHandler.getCodeEngine(), tmp);
                }
            }
        }
        return skillCodeRunner.invoke(skill, argsJson);
    }

    /** 管理三方技能库（thirdskill 工具）：list / add / delete。 */
    private String executeThirdSkill(String content) {
        ThirdPartySkillStore store = SkillBank.getInstance().getThirdPartyStore();
        if (store == null) return "[thirdskill] 三方技能库未初始化";
        if (content == null || content.trim().isEmpty()) return "[thirdskill] 用法: list | add 技能名|描述|内容 | delete 技能名";
        String cmd = content.trim();
        if ("list".equalsIgnoreCase(cmd)) return store.list();
        if (cmd.toLowerCase().startsWith("delete ")) return store.delete(cmd.substring(7).trim());
        if (cmd.toLowerCase().startsWith("add ")) {
            String args = cmd.substring(4).trim();
            String[] parts = args.split("\\|", 3);
            if (parts.length < 2) return "[thirdskill] add 格式错误: add 技能名|描述|内容";
            return store.add(parts[0].trim(), parts[1].trim(), parts.length > 2 ? parts[2].trim() : "");
        }
        return "[thirdskill] 未知子命令: " + cmd + "（支持 list / add / delete）";
    }

    /** 将工具调用委托给 AgentActionHandler 执行（保持与 XML 标签执行逻辑一致）。 */
    private String act(String type, String content) {
        return actionHandler.executeAction(new AgentAction(type, content));
    }

    private static String arg(String argumentsJson, String key) {
        return FunctionCallingBridge.extractArg(argumentsJson, key);
    }

    // ==================== 联网成功自动沉淀 ====================

    /**
     * 搜索成功时自动沉淀到知识库（notes），作为下次同类问题的探索资料。
     * 仅「拿到预期结果」才沉淀；失败（超时/被反爬/空结果）不沉淀。
     */
    private static void autoStoreSearchResult(String query, String result) {
        if (query == null || query.trim().isEmpty()) return;
        if (!isSearchSuccess(result)) return;
        final String q = query.trim();
        final String note = truncateNote(result.trim());
        ThreadManager.getInstance().newNamedCached("AutoNote").submit(() -> {
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return;
            try {
                int id = pm.addNote("[联网搜索] " + q, note, q);
                if (id >= 0) {
                    AiAgentActivity.debugLog("[AutoNote] 搜索成功已沉淀笔记 #" + id + ": " + q);
                }
            } catch (Exception ignored) {
                // 沉淀失败不影响主流程
            }
        });
    }

    /**
     * 抓取成功时自动沉淀到知识库（notes），作为下次同类问题的探索资料。
     * 仅「抓到有效正文」才沉淀；失败（HTTP 错误/被拒绝/空正文）不沉淀。
     */
    private static void autoStoreWebResult(String url, String result) {
        if (url == null || url.trim().isEmpty()) return;
        if (!isWebSuccess(result)) return;
        final String u = url.trim();
        final String note = truncateNote(result.trim());
        ThreadManager.getInstance().newNamedCached("AutoNote").submit(() -> {
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return;
            try {
                int id = pm.addNote("[网页抓取] " + u, note, u);
                if (id >= 0) {
                    AiAgentActivity.debugLog("[AutoNote] 抓取成功已沉淀笔记 #" + id + ": " + u);
                }
            } catch (Exception ignored) {
                // 沉淀失败不影响主流程
            }
        });
    }

    /** 判断搜索结果是否成功：SearchTool 成功以 [搜索] 开头，失败一律以 [search] 开头。 */
    private static boolean isSearchSuccess(String result) {
        if (result == null) return false;
        String r = result.trim();
        if (r.isEmpty()) return false;
        return !r.startsWith("[search]");
    }

    /** 判断抓取结果是否成功：抓到有效正文才算成功，失败/被拒绝/空正文不算。 */
    private static boolean isWebSuccess(String result) {
        if (result == null) return false;
        String r = result.trim();
        if (r.isEmpty()) return false;
        if (!r.startsWith("Web GET")) return false;
        if (r.contains("失败:") || r.contains("错误:") || r.contains("被拒绝")) return false;
        int httpIdx = r.indexOf("(HTTP ");
        if (httpIdx > 0) {
            int colon = r.indexOf(':', httpIdx);
            String body = colon > 0 ? r.substring(colon + 1).trim() : "";
            if (body.length() < 30) return false;
        }
        return true;
    }

    /** 截断过长的笔记正文，避免单条笔记膨胀。 */
    private static String truncateNote(String content) {
        if (content == null) return "";
        if (content.length() <= 3000) return content;
        return content.substring(0, 3000) + "\n...(笔记过长已截断)";
    }

    private static String batchRenameContent(String argumentsJson) {
        return "dir=" + arg(argumentsJson, "dir")
                + " pattern=" + arg(argumentsJson, "pattern")
                + " replacement=" + arg(argumentsJson, "replacement");
    }

    private static String batchConvertContent(String argumentsJson) {
        return "dir=" + arg(argumentsJson, "dir")
                + " from=" + arg(argumentsJson, "from")
                + " to=" + arg(argumentsJson, "to");
    }

    private String executeSetName(String name, ToolContext ctx) {
        if (name == null || name.trim().isEmpty()) {
            return "[setname] 错误：名字不能为空";
        }
        String trimmed = name.trim();
        if (ctx != null && ctx.isExecq() && ctx.napcatApi != null) {
            String resp = ctx.napcatApi.setQQProfile(trimmed, null);
            return "[setname] QQ昵称已设置: " + trimmed + " " + resp;
        }
        return "[setname] 仅QQ通道可修改QQ昵称；修改消息触发词请用 settrigger 工具";
    }

    private String executeSetSignature(String signature, ToolContext ctx) {
        if (signature == null || signature.trim().isEmpty()) {
            return "[setsignature] 错误：个性签名不能为空";
        }
        if (ctx == null || !ctx.isExecq() || ctx.napcatApi == null) {
            return "[setsignature] 仅QQ通道可用";
        }
        String resp = ctx.napcatApi.setSelfLongnick(signature.trim());
        return "[setsignature] 个性签名已设置: " + signature.trim() + " " + resp;
    }

    private String executeSetTrigger(String words, ToolContext ctx) {
        // 仅主人可修改触发词
        if (ctx == null || !ctx.isMaster) {
            return "[settrigger] 无权限：仅主人可修改触发词";
        }
        AiConfig cfg = AiConfig.getInstance();
        if (words == null || words.trim().isEmpty()
                || "list".equalsIgnoreCase(words.trim()) || "查看".equals(words.trim())) {
            java.util.List<String> current = cfg.getTriggerWords();
            if (current.isEmpty()) return "[settrigger] 当前未设置触发词（@机器人 始终有效）";
            return "[settrigger] 当前触发词: " + String.join("; ", current);
        }
        cfg.setBotName(words); // setBotName 内部按 ; 拆分去重
        cfg.save();
        java.util.List<String> list = cfg.getTriggerWords();
        return "[settrigger] 触发词已设置: " + String.join("; ", list);
    }

    // ==================== QQ 媒体发送（execq 通道真实发送） ====================

    /** 发送图片到当前 QQ 会话（文字渲染 / 本地路径 / URL）。 */
    private String executeSendImageQq(String content, ToolContext ctx) {
        if (ctx.napcatApi == null || ctx.qqMsg == null) return "[sendimage] 缺少 QQ 上下文";
        if (content == null || content.trim().isEmpty()) return "[sendimage] 内容为空";
        String imageContent = content.trim();
        try {
            java.io.File imgFile = new java.io.File(imageContent);
            if (imgFile.exists() && imgFile.isFile()) {
                return checkSendResult("sendimage", sendImageToQq(ctx, imageContent), "已发送图片");
            } else if (imageContent.startsWith("http://") || imageContent.startsWith("https://")) {
                return checkSendResult("sendimage", sendImageToQq(ctx, imageContent), "已发送图片");
            } else {
                String dataDir = ctx.dataDir;
                if (dataDir == null || dataDir.isEmpty()) return "[sendimage] 缺少 dataDir，无法渲染文字为图片";
                java.io.File outputDir = new java.io.File(dataDir, "rendered");
                String fileName = "img_" + System.currentTimeMillis() + ".png";
                java.io.File outputFile = new java.io.File(outputDir, fileName);
                sair.aiagent.util.ImageRenderer.renderTextToImage(imageContent, outputFile);
                return checkSendResult("sendimage", sendImageToQq(ctx, outputFile.getAbsolutePath()), "图片已渲染并发送");
            }
        } catch (Exception e) {
            return "[sendimage] 发送失败: " + e.toString();
        }
    }

    /** 发送图片，返回 NapCat API 原始响应（成功 JSON / 失败 JSON / 超时 null）。 */
    private String sendImageToQq(ToolContext ctx, String fileOrUrl) {
        String param = resolveMediaParam(fileOrUrl);
        AiAgentActivity.debugLog("[sendimage] file参数=" + abbreviate(param, 200));
        if (ctx.qqMsg.isGroupMessage()) {
            return ctx.napcatApi.sendGroupImage(ctx.qqMsg.getGroupId(), param);
        } else {
            return ctx.napcatApi.sendPrivateImage(ctx.qqMsg.getUserId(), param);
        }
    }

    /** 发送语音到当前 QQ 会话（本地路径自动走文件中转服务，供跨机器 NapCat 下载）。 */
    private String executeSendRecordQq(String path, ToolContext ctx) {
        if (ctx.napcatApi == null || ctx.qqMsg == null) return "[sendrecord] 缺少 QQ 上下文";
        if (path == null || path.trim().isEmpty()) return "[sendrecord] 语音文件路径为空";
        String recordPath = path.trim();
        try {
            String fileParam = resolveMediaParam(recordPath);
            if (ctx.qqMsg.isGroupMessage()) {
                ctx.napcatApi.sendGroupRecord(ctx.qqMsg.getGroupId(), fileParam);
            } else {
                ctx.napcatApi.sendPrivateRecord(ctx.qqMsg.getUserId(), fileParam);
            }
            return "[sendrecord] 已发送语音";
        } catch (Exception e) {
            return "[sendrecord] 发送失败: " + e.toString();
        }
    }

    /** 发送文件到当前 QQ 会话（仅主人）。优先走文件中转服务（HTTP URL），服务不可用回退本地路径。 */
    private String executeSendFileQq(String path, ToolContext ctx) {
        if (ctx.napcatApi == null || ctx.qqMsg == null) return "[sendfile] 缺少 QQ 上下文";
        if (path == null || path.trim().isEmpty()) return "[sendfile] 文件路径为空";
        String filePath = path.trim();
        java.io.File f = new java.io.File(filePath);
        if (!f.exists() || !f.isFile()) return "[sendfile] 文件不存在: " + filePath;
        String canonicalPath;
        try { canonicalPath = f.getCanonicalPath(); } catch (Exception ex) { canonicalPath = f.getAbsolutePath(); }
        String transferUrl = toTransferUrl(f);
        String fileParam = transferUrl != null ? transferUrl : canonicalPath;
        if (ctx.qqMsg.isGroupMessage()) {
            ctx.napcatApi.sendGroupFile(ctx.qqMsg.getGroupId(), fileParam, f.getName());
        } else {
            ctx.napcatApi.sendPrivateFile(ctx.qqMsg.getUserId(), fileParam, f.getName());
        }
        return "[sendfile] 已发送文件: " + f.getName() + (transferUrl != null ? "（经文件中转服务）" : "");
    }

    // ==================== QQ 群管/媒体工具执行 ====================

    /** 用户管理权限校验：仅主人 */
    private boolean isMasterOnly(ToolContext ctx) {
        return ctx != null && ctx.isMaster && ctx.napcatApi != null;
    }

    private boolean isMasterQQ(long uid) {
        java.util.Set<Long> masters = AiConfig.getInstance().getMasterQQs();
        return masters != null && masters.contains(uid);
    }

    private static long extractLong(String json, String key, long def) {
        String s = FunctionCallingBridge.extractArg(json, key);
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean extractBool(String json, String key, boolean def) {
        String s = FunctionCallingBridge.extractArg(json, key);
        if (s == null || s.isEmpty()) return def;
        return "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim()) || "on".equalsIgnoreCase(s.trim());
    }

    private String executeBan(String json, ToolContext ctx) {
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[ban] 缺少 user_id 参数";
        if (isMasterQQ(uid)) return "[ban] 不能对主人操作";
        int duration = (int) extractLong(json, "duration", 180);
        if (duration < 0) duration = 0;
        if (duration > 2592000) duration = 2592000;
        ctx.napcatApi.muteGroupMember(ctx.qqMsg.getGroupId(), uid, duration);
        return "[ban] 已禁言 " + uid + " " + duration + " 秒";
    }

    private String executeKick(String json, ToolContext ctx) {
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[kick] 缺少 user_id 参数";
        if (isMasterQQ(uid)) return "[kick] 不能对主人操作";
        boolean block = extractBool(json, "block", false);
        ctx.napcatApi.kickGroupMember(ctx.qqMsg.getGroupId(), uid, block);
        return "[kick] 已移出 " + uid + (block ? "（并拉黑）" : "");
    }

    private String executeMuteAll(String json, ToolContext ctx) {
        boolean enable = extractBool(json, "enable", false);
        ctx.napcatApi.muteAll(ctx.qqMsg.getGroupId(), enable);
        return "[muteall] 全员禁言已" + (enable ? "开启" : "关闭");
    }

    private String executeSetAdmin(String json, ToolContext ctx) {
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[setadmin] 缺少 user_id 参数";
        boolean enable = extractBool(json, "enable", false);
        ctx.napcatApi.setGroupAdmin(ctx.qqMsg.getGroupId(), uid, enable);
        return "[setadmin] " + uid + (enable ? " 已设为管理员" : " 已取消管理员");
    }

    private String executeSetCard(String json, ToolContext ctx) {
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[setcard] 缺少 user_id 参数";
        String card = FunctionCallingBridge.extractArg(json, "card");
        ctx.napcatApi.setGroupCard(ctx.qqMsg.getGroupId(), uid, card);
        return "[setcard] 已设置 " + uid + " 的名片";
    }

    private String executeSetGroupName(String json, ToolContext ctx) {
        String name = FunctionCallingBridge.extractArg(json, "name");
        ctx.napcatApi.setGroupName(ctx.qqMsg.getGroupId(), name);
        return "[setgroupname] 群名已设置为: " + name;
    }

    private String executeLeaveGroup(String json, ToolContext ctx) {
        boolean dismiss = extractBool(json, "dismiss", false);
        ctx.napcatApi.leaveGroup(ctx.qqMsg.getGroupId(), dismiss);
        return "[leavegroup] 已退群" + (dismiss ? "（并解散）" : "");
    }

    private String executeBlock(String json, ToolContext ctx) {
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[block] 缺少 user_id 参数";
        if (isMasterQQ(uid)) return "[block] 不能对主人操作";
        if (ctx.internalAgents != null) {
            ctx.internalAgents.blockUser(uid, "AI判定违规拉黑", true);
        }
        return "[block] 已拉黑 " + uid;
    }

    private String executeUnblock(String json, ToolContext ctx) {
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[unblock] 缺少 user_id 参数";
        if (ctx.internalAgents != null) {
            ctx.internalAgents.unblockUser(uid);
        }
        return "[unblock] 已取消拉黑 " + uid;
    }

    private String executeDelfriend(String json, ToolContext ctx) {
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[delfriend] 缺少 user_id 参数";
        ctx.napcatApi.deleteFriend(uid);
        return "[delfriend] 已删除好友 " + uid;
    }

    private String executePoke(String json, ToolContext ctx) {
        if (ctx == null || ctx.napcatApi == null || ctx.qqMsg == null) return "[poke] 缺少 QQ 上下文";
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[poke] 缺少 user_id 参数";
        if (ctx.qqMsg.isGroupMessage() && ctx.qqMsg.getGroupId() > 0) {
            ctx.napcatApi.sendGroupPoke(ctx.qqMsg.getGroupId(), uid);
        }
        return "[poke] 已戳一戳 " + uid;
    }

    private String executeSendLike(String json, ToolContext ctx) {
        long uid = extractLong(json, "user_id", -1);
        if (uid < 0) return "[sendlike] 缺少 user_id 参数";
        int times = (int) extractLong(json, "times", 10);
        ctx.napcatApi.sendLike(uid, times);
        return "[sendlike] 已点赞 " + uid + " " + times + " 次";
    }

    private String executeSendFileTo(String json, ToolContext ctx) {
        if (ctx == null || ctx.napcatApi == null || ctx.qqMsg == null) return "[sendfileto] 缺少 QQ 上下文";
        String target = FunctionCallingBridge.extractArg(json, "target");
        String path = FunctionCallingBridge.extractArg(json, "path");
        java.io.File f = new java.io.File(path);
        if (!f.exists() || !f.isFile()) return "[sendfileto] 文件不存在: " + path;
        Long targetQQ = findContact(target, ctx);
        if (targetQQ == null) return "[sendfileto] 未找到目标联系人: " + target;
        String transferUrl = toTransferUrl(f);
        String fileParam = transferUrl != null ? transferUrl : f.getAbsolutePath();
        ctx.napcatApi.sendPrivateFile(targetQQ, fileParam, f.getName());
        return "[sendfileto] 已发送文件给 " + targetQQ + (transferUrl != null ? "（经文件中转服务）" : "");
    }

    /** 将本地文件注册到文件中转服务，返回 HTTP URL；服务未启动时返回 null（回退本地路径）。 */
    private static String toTransferUrl(java.io.File f) {
        try {
            sair.aiagent.onebot.FileServer fs = sair.aiagent.onebot.FileServer.getInstance();
            if (fs.isRunning()) {
                return fs.register(f);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 本地文件路径 → 文件中转服务 URL（跨机器 NapCat 可下载）。
     * <p>中转服务不可用或 host 为 loopback（跨机器不可达）时，回退 base64 编码，
     * 让 NapCat 直接解码，彻底摆脱对「NapCat 能访问 Windows 本机文件」的依赖。
     * URL / file:// / base64:// 原样透传。</p>
     */
    public static String resolveMediaParam(String fileOrUrl) {
        if (fileOrUrl == null) return fileOrUrl;
        String p = fileOrUrl.trim();
        if (p.startsWith("http://") || p.startsWith("https://")
                || p.startsWith("file://") || p.startsWith("base64://")) {
            return p;
        }
        java.io.File f = new java.io.File(p);
        if (f.exists() && f.isFile()) {
            // 1. 优先文件中转服务（HTTP URL），但 host 必须是跨机器可访问的非 loopback 地址
            String transferUrl = toTransferUrl(f);
            if (transferUrl != null && !isLoopbackUrl(transferUrl)) {
                AiAgentActivity.debugLog("[Media] 本地文件经文件中转: " + transferUrl);
                return transferUrl;
            }
            if (transferUrl != null) {
                AiAgentActivity.debugLog("[Media] 文件中转 host 为 loopback（跨机器不可达），改用 base64: " + transferUrl);
            }
            // 2. 中转不可用/不可达：本地文件转 base64，NapCat 直接解码，无需访问本机文件
            String b64 = toBase64Param(f);
            if (b64 != null) {
                AiAgentActivity.debugLog("[Media] 本地文件转 base64: " + f.getName() + " (" + f.length() + " bytes)");
                return b64;
            }
            AiAgentActivity.debugLog("[Media] 本地文件过大无法 base64，回退原始路径（仅本机 NapCat 可用）: " + p);
        }
        return p;
    }

    /** 判断 URL host 是否为 loopback/任意地址（跨机器 NapCat 无法访问）。 */
    private static boolean isLoopbackUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("://127.") || u.contains("://localhost")
                || u.contains("://[::1]") || u.contains("://0.0.0.0");
    }

    /** 本地文件 → base64:// 参数（NapCat 直接解码）。超过 8MB 或读取失败返回 null。 */
    private static String toBase64Param(java.io.File f) {
        try {
            long len = f.length();
            if (len <= 0 || len > 8 * 1024 * 1024) return null;
            byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
            return "base64://" + java.util.Base64.getEncoder().encodeToString(data);
        } catch (Exception e) {
            return null;
        }
    }

    /** 构建可直接嵌入消息的 [CQ:image,file=...] 码（本地文件自动走中转/base64，并对 CQ 特殊字符转义）。 */
    public static String buildImageCq(String fileOrUrl) {
        String param = resolveMediaParam(fileOrUrl);
        if (param == null || param.isEmpty()) return null;
        return "[CQ:image,file=" + sair.aiagent.onebot.NapCatApi.escapeCQ(param) + "]";
    }

    /** 截断超长字符串用于日志打印，避免刷屏。 */
    private static String abbreviate(String s, int max) {
        if (s == null) return "null";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...(共" + s.length() + "字符)";
    }

    /** 解析 NapCat API 响应：成功返回 okMsg，失败/超时返回可读错误。 */
    private static String checkSendResult(String tool, String resp, String okMsg) {
        if (resp == null || resp.isEmpty()) {
            return "[" + tool + "] 发送超时或失败（NapCat 无响应，请检查文件是否可被 NapCat 访问）";
        }
        try {
            String status = sair.aiagent.onebot.util.JsonUtil.extractString(resp, "status");
            long retcode = sair.aiagent.onebot.util.JsonUtil.extractLong(resp, "retcode");
            if ("failed".equalsIgnoreCase(status) || retcode != 0) {
                String msg = sair.aiagent.onebot.util.JsonUtil.extractString(resp, "message");
                String wording = sair.aiagent.onebot.util.JsonUtil.extractString(resp, "wording");
                return "[" + tool + "] 发送失败: " + (msg != null && !msg.isEmpty() ? msg
                        : wording != null && !wording.isEmpty() ? wording : ("retcode=" + retcode));
            }
        } catch (Exception ignored) {}
        return "[" + tool + "] " + okMsg;
    }

    /**
     * execq 通道 eval 破例仅限「多对象拆分搜索」：检查代码是否只做联网抓取，
     * 禁止文件系统操作、进程执行、反射、类加载等危险操作。
     */
    private static boolean isSearchEvalCode(String code) {
        if (code == null || code.trim().isEmpty()) return false;
        String c = code.toLowerCase();
        boolean hasHttp = c.contains("httpurlconnection") || c.contains("openconnection")
                || c.contains("inputstream") || c.contains("bufferedreader")
                || c.contains("http://") || c.contains("https://") || c.contains("java.net.url");
        boolean dangerous = c.contains("processbuilder") || c.contains("runtime.getruntime")
                || c.contains("fileoutputstream") || c.contains("filewriter") || c.contains("files.")
                || c.contains("class.forname") || c.contains(".exec(") || c.contains("system.exit")
                || c.contains("file.delete") || c.contains("filereader") || c.contains("reflection");
        return hasHttp && !dangerous;
    }

    /**
     * execq 通道 cmd 的 help 查询。
     * <p>语法：{@code /help} 查 SFW 自身帮助；{@code 组件名/help} 查组件帮助。
     * 返回 null 表示不是合法的 help 查询（应拒绝）。</p>
     */
    private String execqHelpQuery(String cmdArg) {
        if (cmdArg == null) return null;
        String trimmed = cmdArg.trim();
        if (trimmed.isEmpty()) return null;
        String t = trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
        if ("help".equalsIgnoreCase(t)) {
            return buildSfwHelpWithComponents();
        }
        if (t.toLowerCase().endsWith("/help")) {
            String compName = t.substring(0, t.length() - "/help".length()).trim();
            if (compName.isEmpty()) return buildSfwHelpWithComponents();
            return buildComponentHelp(compName);
        }
        return null;
    }

    /** SFW 自身帮助 + 组件列表。 */
    private String buildSfwHelpWithComponents() {
        StringBuilder sb = new StringBuilder();
        sb.append("[help] SFW 框架帮助:\n");
        appendHelpLines(sb, ConsFrame.fa.help());
        sb.append("\n[help] 可用组件列表:\n");
        synchronized (Libraries.activities) {
            for (String name : Libraries.activities.keySet()) {
                sb.append("- ").append(name).append("\n");
            }
        }
        sb.append("\n（查询某组件帮助：cmd 组件名/help）");
        return sb.toString();
    }

    /** 组件帮助。 */
    private String buildComponentHelp(String compName) {
        Activity act;
        synchronized (Libraries.activities) {
            act = Libraries.activities.get(compName);
        }
        if (act == null) {
            return "[help] 未找到组件: " + compName + "（可 /help 查看组件列表）";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[help] 组件 ").append(compName).append(" 的帮助:\n");
        appendHelpLines(sb, act.help());
        return sb.toString();
    }

    private void appendHelpLines(StringBuilder sb, String[] lines) {
        if (lines == null) {
            sb.append("(该组件未提供帮助信息)\n");
            return;
        }
        for (String line : lines) {
            if (line != null) sb.append(line).append("\n");
        }
    }

    private String executeRelay(String json, ToolContext ctx) {
        String target = FunctionCallingBridge.extractArg(json, "target");
        String message = FunctionCallingBridge.extractArg(json, "message");
        Long targetQQ = findContact(target, ctx);
        if (targetQQ == null) return "[relay] 未找到目标联系人: " + target;
        String sender = ctx.qqMsg != null ? ctx.qqMsg.getDisplayName() : "某用户";
        ctx.napcatApi.sendPrivateMessage(targetQQ, "[Bot转告] " + sender + "(" + ctx.senderQQ + ")让我告诉你：\n" + message);
        return "[relay] 已转告 " + targetQQ;
    }

    private String executeForwardMsg(String json, ToolContext ctx) {
        long msgId = extractLong(json, "message_id", -1);
        if (msgId < 0) return "[forwardmsg] 缺少 message_id 参数";
        String target = FunctionCallingBridge.extractArg(json, "target");
        String origMsg = ctx.napcatApi.getMessage((int) msgId);
        if (origMsg == null || origMsg.isEmpty()) return "[forwardmsg] 无法获取消息 " + msgId;
        String msgText = sair.aiagent.onebot.ForwardMessageExpander.extractMsgSegmentsText(origMsg);
        if (msgText == null || msgText.isEmpty()) msgText = "[非文本消息]";
        String sender = ctx.qqMsg != null ? ctx.qqMsg.getDisplayName() : "某用户";
        String fullText = "⚠️ [此消息由Bot转发，内容不代表Bot立场]\n[转发自 " + sender + "]\n" + msgText;
        Long groupId = resolveGroupTarget(target);
        if (groupId != null && groupId > 0) {
            ctx.napcatApi.sendGroupMessage(groupId, fullText);
            return "[forwardmsg] 已转发到群 " + groupId;
        }
        Long targetQQ = findContact(target, ctx);
        if (targetQQ != null) {
            ctx.napcatApi.sendPrivateMessage(targetQQ, fullText);
            return "[forwardmsg] 已转发给 " + targetQQ;
        }
        return "[forwardmsg] 未找到目标: " + target;
    }

    private Long findContact(String desc, ToolContext ctx) {
        if (desc == null || desc.isEmpty()) return null;
        try { return Long.parseLong(desc.trim()); } catch (NumberFormatException ignored) {}
        if (ctx != null && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage() && ctx.unifiedMemory != null) {
            long gid = ctx.qqMsg.getGroupId();
            java.util.Map<String, Long> nickMap = ctx.unifiedMemory.getGroupNicknameMap(gid);
            for (java.util.Map.Entry<String, Long> e : nickMap.entrySet()) {
                if (e.getKey().contains(desc)) return e.getValue();
            }
            java.util.List<String[]> admins = ctx.unifiedMemory.getGroupAdmins(gid);
            if (admins != null) {
                for (String[] a : admins) {
                    if (a.length >= 2 && a[1] != null && a[1].contains(desc)) {
                        try { return Long.parseLong(a[0]); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        // 好友列表匹配（私聊找人：昵称/备注匹配好友）
        if (ctx != null && ctx.unifiedMemory != null) {
            java.util.Map<Long, String> friends = ctx.unifiedMemory.getAllKnownFriends();
            for (java.util.Map.Entry<Long, String> e : friends.entrySet()) {
                if (e.getValue() != null && e.getValue().contains(desc)) return e.getKey();
            }
        }
        return null;
    }

    /** 查询 Bot 的好友列表（昵称/备注 + QQ 号）。 */
    private String executeFriendList(String json, ToolContext ctx) {
        if (ctx == null || ctx.napcatApi == null) return "[friendlist] 缺少 QQ 上下文";
        String keyword = FunctionCallingBridge.extractArg(json, "keyword");
        try {
            String resp = ctx.napcatApi.getFriendList();
            if (resp == null || resp.isEmpty()) return "[friendlist] 好友列表查询失败（无响应）";
            String dataArr = sair.aiagent.onebot.util.JsonUtil.extractArray(resp, "data");
            if (dataArr == null || dataArr.trim().isEmpty()) return "[friendlist] 未获取到好友数据";
            StringBuilder sb = new StringBuilder("[friendlist] Bot 的好友:\n");
            int n = 0;
            for (String f : sair.aiagent.onebot.util.JsonUtil.splitJsonArray(dataArr)) {
                String uid = sair.aiagent.onebot.util.JsonUtil.extractString(f, "user_id");
                if (uid == null || uid.isEmpty()) continue;
                String nick = sair.aiagent.onebot.util.JsonUtil.extractString(f, "nickname");
                String remark = sair.aiagent.onebot.util.JsonUtil.extractString(f, "remark");
                String name = (remark != null && !remark.isEmpty()) ? remark : (nick != null && !nick.isEmpty() ? nick : "(未知)");
                if (keyword != null && !keyword.trim().isEmpty() && !name.contains(keyword.trim())) continue;
                sb.append("- ").append(name).append(" QQ:").append(uid).append("\n");
                n++;
                if (n >= 200) { sb.append("...(好友过多，已截断)\n"); break; }
            }
            if (n == 0) return "[friendlist] 好友列表为空" + (keyword != null && !keyword.trim().isEmpty() ? "（关键词 " + keyword.trim() + " 无匹配）" : "");
            return sb.toString();
        } catch (Exception e) {
            return "[friendlist] 查询失败: " + e.toString();
        }
    }

    /** 查询 Bot 加入的所有群（群名 + 群号）。 */
    private String executeGroupList(String json, ToolContext ctx) {
        if (ctx == null || ctx.napcatApi == null) return "[grouplist] 缺少 QQ 上下文";
        String keyword = FunctionCallingBridge.extractArg(json, "keyword");
        try {
            String resp = ctx.napcatApi.getGroupList();
            if (resp == null || resp.isEmpty()) return "[grouplist] 群列表查询失败（无响应）";
            String dataArr = sair.aiagent.onebot.util.JsonUtil.extractArray(resp, "data");
            if (dataArr == null || dataArr.trim().isEmpty()) return "[grouplist] 未获取到群列表数据";
            StringBuilder sb = new StringBuilder("[grouplist] Bot 加入的群:\n");
            int n = 0;
            for (String g : sair.aiagent.onebot.util.JsonUtil.splitJsonArray(dataArr)) {
                String gname = sair.aiagent.onebot.util.JsonUtil.extractString(g, "group_name");
                String gid = sair.aiagent.onebot.util.JsonUtil.extractString(g, "group_id");
                if (gid == null || gid.isEmpty()) continue;
                if (gname == null || gname.isEmpty()) gname = "(未命名)";
                if (keyword != null && !keyword.trim().isEmpty() && !gname.contains(keyword.trim())) continue;
                sb.append("- ").append(gname).append(" 群号:").append(gid).append("\n");
                n++;
            }
            if (n == 0) return "[grouplist] 群列表为空" + (keyword != null && !keyword.trim().isEmpty() ? "（关键词 " + keyword.trim() + " 无匹配）" : "");
            return sb.toString();
        } catch (Exception e) {
            return "[grouplist] 查询失败: " + e.toString();
        }
    }

    /** 查询指定群的成员列表（昵称 + QQ 号）。 */
    private String executeGroupMembers(String json, ToolContext ctx) {
        if (ctx == null || ctx.napcatApi == null) return "[groupmembers] 缺少 QQ 上下文";
        long gid = extractLong(json, "group_id", -1);
        if (gid <= 0) return "[groupmembers] 缺少 group_id 参数";
        try {
            String resp = ctx.napcatApi.getGroupMemberList(gid);
            if (resp == null || resp.isEmpty()) return "[groupmembers] 群成员列表查询失败（无响应）";
            String dataArr = sair.aiagent.onebot.util.JsonUtil.extractArray(resp, "data");
            if (dataArr == null || dataArr.trim().isEmpty()) return "[groupmembers] 未获取到成员数据";
            StringBuilder sb = new StringBuilder("[groupmembers] 群 ").append(gid).append(" 成员:\n");
            int n = 0;
            for (String m : sair.aiagent.onebot.util.JsonUtil.splitJsonArray(dataArr)) {
                String uid = sair.aiagent.onebot.util.JsonUtil.extractString(m, "user_id");
                if (uid == null || uid.isEmpty()) continue;
                String nick = sair.aiagent.onebot.util.JsonUtil.extractString(m, "nickname");
                String card = sair.aiagent.onebot.util.JsonUtil.extractString(m, "card");
                String name = (card != null && !card.isEmpty()) ? card : (nick != null && !nick.isEmpty() ? nick : "(未知)");
                sb.append("- ").append(name).append(" QQ:").append(uid).append("\n");
                n++;
                if (n >= 200) { sb.append("...(成员过多，已截断)\n"); break; }
            }
            if (n == 0) return "[groupmembers] 成员列表为空";
            return sb.toString();
        } catch (Exception e) {
            return "[groupmembers] 查询失败: " + e.toString();
        }
    }

    /** 向指定群发送消息（可@群成员），仅主人可用。 */
    private String executeSendGroupMsg(String json, ToolContext ctx) {
        long gid = extractLong(json, "group_id", -1);
        if (gid <= 0) return "[sendgroupmsg] 缺少 group_id 参数";
        String message = FunctionCallingBridge.extractArg(json, "message");
        if (message == null || message.trim().isEmpty()) return "[sendgroupmsg] 缺少 message 参数";
        ctx.napcatApi.sendGroupMessage(gid, message);
        return "[sendgroupmsg] 已发送到群 " + gid + ": " + message;
    }

    private Long resolveGroupTarget(String desc) {
        if (desc == null || desc.isEmpty()) return null;
        try {
            long num = Long.parseLong(desc.trim());
            if (num >= 100000) return num;
        } catch (NumberFormatException ignored) {}
        return null;
    }
}
