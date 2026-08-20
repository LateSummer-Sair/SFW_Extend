package sair.aiagent.core;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.AgentAction;
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

    private final AgentActionHandler actionHandler;
    /** 三方技能代码段执行器（懒创建，仅 execs/本地通道使用）。 */
    private volatile SkillCodeRunner skillCodeRunner;
    /** Harness 生命周期钩子（确定性约束层），按注册顺序执行。 */
    private final List<HarnessHook> hooks = new ArrayList<>();

    public ToolDispatcher(AgentActionHandler actionHandler) {
        this.actionHandler = actionHandler;
    }

    public AgentActionHandler getActionHandler() {
        return actionHandler;
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

    /** 本地通道（console/execs）全量工具注册。 */
    public static List<ToolDefinition> buildAllTools() {
        List<ToolDefinition> tools = new ArrayList<>();

        tools.add(new ToolDefinition("cmd", "执行 SFW 插件命令（格式：组件名/函数名 参数，空格分隔）。不确定组件时先用 cmd /help 或 cmd 组件名/help 查询可用命令")
                .addString("command", "插件命令"));

        tools.add(new ToolDefinition("readfile", "读取指定文件的文本内容")
                .addString("path", "文件路径"));

        tools.add(new ToolDefinition("readdir", "列出指定目录的文件与子目录结构")
                .addOptionalString("path", "目录路径，留空则列出当前目录"));

        tools.add(new ToolDefinition("sys", "执行系统命令（shell）")
                .addString("command", "系统命令"));

        tools.add(new ToolDefinition("evaljs", "动态执行一段 JavaScript 代码（Nashorn 引擎，脚本解释执行，非 Java 动态注入），适合快速脚本、HTTP 请求、数据处理。Java 类用全限定名（如 java.net.URL）")
                .addString("code", "要执行的 JS 代码"));

        tools.add(new ToolDefinition("eval", "编译并执行一段 Java 代码（动态注入，终极兜底）。当其它工具失败时直接写代码完成任务，可用 SFW 内部 API（Libraries/SairCons/Activity）或 JDK 标准类")
                .addString("code", "要执行的 Java 代码"));

        tools.add(new ToolDefinition("web", "抓取指定 URL 的网页或 API 接口内容，自动识别 JSON 接口并原样返回结构化数据，适合查询实时信息、网页内容。URL 必须是完整 http/https 地址")
                .addString("url", "要抓取的完整 URL（http/https）"));

        tools.add(new ToolDefinition("search", "在必应搜索网页并返回标题、链接、摘要（适合查实时信息、公开资料、新闻）")
                .addString("query", "搜索关键词"));

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

        tools.add(new ToolDefinition("note", "知识库操作，命令格式：add title|content|tags | search query | list | get id | delete id | update id title|content|tags")
                .addString("content", "完整子命令字符串"));

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

        // 三方技能（含代码段）动态注册为 Function Calling 工具（tp_ 前缀），execs/本地始终可用
        addThirdPartyToolTools(tools);

        return tools;
    }

    /** QQ 通道（execq）受限工具注册。 */
    public static List<ToolDefinition> buildExecqTools() {
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

        tools.add(new ToolDefinition("readfile", "读取指定文件的文本内容")
                .addString("path", "文件路径"));

        tools.add(new ToolDefinition("weather", "查询指定城市的实时天气")
                .addString("city", "城市名"));

        tools.add(new ToolDefinition("time", "查询当前日期和时间"));

        tools.add(new ToolDefinition("remember", "记录一条跨会话的持久化记忆")
                .addString("content", "要记住的内容"));

        tools.add(new ToolDefinition("correct", "记录一条纠正信息（当用户指出你的错误、或你发现自己犯错时，记录纠正内容避免重复犯错）")
                .addString("topic", "纠正主题/关键词（归类检索用）")
                .addString("content", "纠正内容")
                .addOptionalString("viewpoint", "观点标签（同一主题多观点时区分用，可留空）"));

        tools.add(new ToolDefinition("note", "知识库操作，命令格式：add title|content|tags | search query | list | get id | delete id | update id title|content|tags")
                .addString("content", "完整子命令字符串"));

        tools.add(new ToolDefinition("searchnote", "在知识库中检索相关笔记")
                .addString("query", "检索关键词"));

        tools.add(new ToolDefinition("balance", "查询 DeepSeek 账户余额"));

        tools.add(new ToolDefinition("sendsticker", "发送表情包（按上下文匹配库存）")
                .addOptionalString("context", "表情包匹配上下文，留空则返回库存清单"));

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

        // 三方技能代码段执行能力（callskill + tp_ 动态工具）：默认放权给用户，用户可通过配置关闭
        if (AiConfig.getInstance().isThirdPartyCodeExecq()) {
            tools.add(new ToolDefinition("callskill", "调用三方技能（data/skills/*.md）内嵌代码段的 airun 入口函数。args 为 JSON（字符串用引号包裹、对象用 {}）")
                    .addString("skill", "三方技能名")
                    .addString("args", "传给 airun 的参数（JSON 字符串）"));
            addThirdPartyToolTools(tools);
        }

        return tools;
    }

    /** QQ execs 全权限工具集 = 本地全量工具 + QQ 专属工具（按名去重）。 */
    public static List<ToolDefinition> buildExecsTools() {
        List<ToolDefinition> tools = new ArrayList<>(buildAllTools());
        Set<String> names = new HashSet<>();
        for (ToolDefinition t : tools) names.add(t.getName());
        for (ToolDefinition t : buildExecqTools()) {
            if (!names.contains(t.getName())) {
                tools.add(t);
            }
        }
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
        for (ThirdPartySkill tp : store.getAll()) {
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
        HarnessHook[] snapshot;
        synchronized (hooks) {
            snapshot = hooks.toArray(new HarnessHook[0]);
        }
        for (HarnessHook hook : snapshot) {
            try {
                String blocked = hook.preExecute(toolName, argumentsJson, ctx);
                if (blocked != null) return blocked;
            } catch (Exception ignored) {
                // 钩子异常不影响工具执行（护栏自身故障不应阻断业务）
            }
        }
        String result;
        try {
            result = executeInternal(toolName, argumentsJson, ctx);
        } catch (Exception e) {
            result = "[工具执行异常] " + toolName + ": " + e.toString();
        }
        for (HarnessHook hook : snapshot) {
            try {
                result = hook.postExecute(toolName, argumentsJson, ctx, result);
            } catch (Exception ignored) {
                // 后置钩子异常保留原结果
            }
        }
        return result;
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
            case "readfile":     return act("readfile", arg(argumentsJson, "path"));
            case "readdir":      return act("readdir", arg(argumentsJson, "path"));
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
            case "remember":     return act("remember", arg(argumentsJson, "content"));
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
            case "setname":      return executeSetName(arg(argumentsJson, "name"), ctx);
            case "setsignature": return executeSetSignature(arg(argumentsJson, "signature"), ctx);
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
            case "callskill":        return executeCallSkill(argumentsJson);
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
            default:
                if (toolName != null && toolName.startsWith("tp_")) {
                    return executeThirdPartyTool(toolName.substring(3), argumentsJson);
                }
                return "[工具] 未知工具: " + toolName;
        }
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
    private String executeCallSkill(String json) {
        String skillName = arg(json, "skill");
        if (skillName == null || skillName.trim().isEmpty()) return "[callskill] 缺少 skill 参数（技能名）";
        String argsJson = arg(json, "args");
        ThirdPartySkillStore store = SkillBank.getInstance().getThirdPartyStore();
        if (store == null) return "[callskill] 三方技能库未初始化";
        ThirdPartySkill skill = store.get(skillName.trim());
        if (skill == null) return "[callskill] 未找到三方技能: " + skillName.trim();
        return invokeSkill(skill, argsJson);
    }

    /** 执行动态注册的三方技能工具（tp_ 前缀），组装入参后调用 airun 入口。 */
    private String executeThirdPartyTool(String skillName, String argumentsJson) {
        ThirdPartySkillStore store = SkillBank.getInstance().getThirdPartyStore();
        if (store == null) return "[callskill] 三方技能库未初始化";
        ThirdPartySkill skill = store.get(skillName);
        if (skill == null) return "[callskill] 未找到三方技能: " + skillName;
        String argsJson = argumentsJson;
        // 未声明 airun 参数时，工具只有一个 args 字段，取出其原始 JSON 作为 airun 入参
        if (!skill.hasAirunSchema()) {
            String raw = arg(argumentsJson, "args");
            if (raw != null && !raw.trim().isEmpty()) argsJson = raw;
        }
        return invokeSkill(skill, argsJson);
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
        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) return;
        String q = query.trim();
        try {
            int id = pm.addNote("[联网搜索] " + q, truncateNote(result.trim()), q);
            if (id >= 0) {
                AiAgentActivity.debugLog("[AutoNote] 搜索成功已沉淀笔记 #" + id + ": " + q);
            }
        } catch (Exception ignored) {
            // 沉淀失败不影响主流程
        }
    }

    /**
     * 抓取成功时自动沉淀到知识库（notes），作为下次同类问题的探索资料。
     * 仅「抓到有效正文」才沉淀；失败（HTTP 错误/被拒绝/空正文）不沉淀。
     */
    private static void autoStoreWebResult(String url, String result) {
        if (url == null || url.trim().isEmpty()) return;
        if (!isWebSuccess(result)) return;
        PersistenceManager pm = PersistenceManager.getInstance();
        if (pm == null) return;
        String u = url.trim();
        try {
            int id = pm.addNote("[网页抓取] " + u, truncateNote(result.trim()), u);
            if (id >= 0) {
                AiAgentActivity.debugLog("[AutoNote] 抓取成功已沉淀笔记 #" + id + ": " + u);
            }
        } catch (Exception ignored) {
            // 沉淀失败不影响主流程
        }
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
            AiConfig.getInstance().setBotName(trimmed);
            AiConfig.getInstance().save();
            return "[setname] QQ昵称已设置: " + trimmed + " " + resp;
        }
        AiConfig.getInstance().setBotName(trimmed);
        AiConfig.getInstance().save();
        return "[setname] 名字已设置为: " + trimmed;
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

    // ==================== QQ 媒体发送（execq 通道真实发送） ====================

    /** 发送图片到当前 QQ 会话（文字渲染 / 本地路径 / URL）。 */
    private String executeSendImageQq(String content, ToolContext ctx) {
        if (ctx.napcatApi == null || ctx.qqMsg == null) return "[sendimage] 缺少 QQ 上下文";
        if (content == null || content.trim().isEmpty()) return "[sendimage] 内容为空";
        String imageContent = content.trim();
        try {
            java.io.File imgFile = new java.io.File(imageContent);
            if (imgFile.exists() && imgFile.isFile()) {
                sendImageToQq(ctx, imageContent);
                return "[sendimage] 已发送图片";
            } else if (imageContent.startsWith("http://") || imageContent.startsWith("https://")) {
                sendImageToQq(ctx, imageContent);
                return "[sendimage] 已发送图片";
            } else {
                String dataDir = ctx.dataDir;
                if (dataDir == null || dataDir.isEmpty()) return "[sendimage] 缺少 dataDir，无法渲染文字为图片";
                java.io.File outputDir = new java.io.File(dataDir, "rendered");
                String fileName = "img_" + System.currentTimeMillis() + ".png";
                java.io.File outputFile = new java.io.File(outputDir, fileName);
                sair.aiagent.util.ImageRenderer.renderTextToImage(imageContent, outputFile);
                sendImageToQq(ctx, outputFile.getAbsolutePath());
                return "[sendimage] 图片已渲染并发送";
            }
        } catch (Exception e) {
            return "[sendimage] 发送失败: " + e.toString();
        }
    }

    private void sendImageToQq(ToolContext ctx, String fileOrUrl) {
        if (ctx.qqMsg.isGroupMessage()) {
            ctx.napcatApi.sendGroupImage(ctx.qqMsg.getGroupId(), fileOrUrl);
        } else {
            ctx.napcatApi.sendPrivateImage(ctx.qqMsg.getUserId(), fileOrUrl);
        }
    }

    /** 发送语音到当前 QQ 会话。 */
    private String executeSendRecordQq(String path, ToolContext ctx) {
        if (ctx.napcatApi == null || ctx.qqMsg == null) return "[sendrecord] 缺少 QQ 上下文";
        if (path == null || path.trim().isEmpty()) return "[sendrecord] 语音文件路径为空";
        String recordPath = path.trim();
        try {
            if (ctx.qqMsg.isGroupMessage()) {
                ctx.napcatApi.sendGroupRecord(ctx.qqMsg.getGroupId(), recordPath);
            } else {
                ctx.napcatApi.sendPrivateRecord(ctx.qqMsg.getUserId(), recordPath);
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
