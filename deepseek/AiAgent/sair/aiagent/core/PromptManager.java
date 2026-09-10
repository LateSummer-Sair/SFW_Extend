package sair.aiagent.core;

/**
 * 提示词管理器 —— 将所有提示词集中管理（内存硬编码，可运行时修改）。
 * <p>
 * 提供三段提示词的统一访问入口，默认值硬编码。
 * AiConfig 在 load/save 时负责与 config.properties 持久化同步。
 * </p>
 *
 * <h3>agentStaticPrompt 模板变量</h3>
 * 包含以下占位符，由 AgentExecutor 在运行时动态替换：
 * <ul>
 *   <li>{os} — 操作系统标识</li>
 *   <li>{shell} — Shell类型</li>
 *   <li>{jdk} — JDK状态</li>
 *   <li>{plugins} — 已加载插件列表</li>
 * </ul>
 */
public class PromptManager {

    // ==================== 单例 ====================

    private static volatile PromptManager instance;

    private String systemPrompt;           // 基础提示词（硬编码默认 或 editprompt 替换）
    private String execqPrompt;            // 基础提示词（硬编码默认 或 editprompt 替换）
    private String agentStaticPrompt;      // Agent 静态模板（仅硬编码）
    private String systemPromptExtra;      // 提示词追加内容（systemPrompt.md）
    private String execqPromptExtra;       // 提示词追加内容（execqPrompt.md）
    private volatile java.io.File dataDir; // 数据目录（写提示词 md 文件用）

    public static PromptManager getInstance() {
        if (instance == null) {
            synchronized (PromptManager.class) {
                if (instance == null) {
                    instance = new PromptManager();
                }
            }
        }
        return instance;
    }

    private PromptManager() {
        // 初始化硬编码默认值
        this.systemPrompt = buildDefaultSystemPrompt();
        this.execqPrompt = buildDefaultExecqPrompt();
        this.agentStaticPrompt = buildDefaultAgentStaticPrompt();
    }

    /** 初始化：从 dataDir 读 systemPrompt.md / execqPrompt.md 作为提示词追加内容（独立于配置文件）。 */
    public void init(java.io.File dataDir) {
        if (dataDir == null) return;
        this.dataDir = dataDir;
        try {
            java.io.File spFile = new java.io.File(dataDir, "systemPrompt.md");
            if (spFile.exists()) {
                String sp = new String(java.nio.file.Files.readAllBytes(spFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (sp != null && !sp.trim().isEmpty()) this.systemPromptExtra = sp.trim();
            }
            java.io.File eqpFile = new java.io.File(dataDir, "execqPrompt.md");
            if (eqpFile.exists()) {
                String eqp = new String(java.nio.file.Files.readAllBytes(eqpFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                if (eqp != null && !eqp.trim().isEmpty()) this.execqPromptExtra = eqp.trim();
            }
        } catch (Exception ignored) {}
    }

    /** 将提示词写入 dataDir 下的 md 文件（写失败静默忽略，内存态仍生效）。 */
    private void writePromptFile(String fileName, String content) {
        if (dataDir == null || content == null) return;
        try {
            java.io.File f = new java.io.File(dataDir, fileName);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    // ==================== Getters ====================

    /** 获取 system prompt = 基础提示词 + config.properties 追加内容 */
    public String getSystemPrompt() {
        String base = systemPrompt != null ? systemPrompt : buildDefaultSystemPrompt();
        if (systemPromptExtra != null && !systemPromptExtra.trim().isEmpty()) {
            return base + "\n\n## 角色设定\n以下定义你的名字、身份与性格，请以此为准。\n\n" + systemPromptExtra;
        }
        return base;
    }

    /** 设置提示词（写入 systemPrompt.md，保留硬编码默认为基础） */
    public void setSystemPrompt(String prompt) {
        if (prompt != null && !prompt.trim().isEmpty()) {
            this.systemPromptExtra = prompt.trim();
            writePromptFile("systemPrompt.md", prompt.trim());
        }
    }

    /** 追加/覆盖提示词（写入 systemPrompt.md） */
    public void appendSystemPrompt(String extra) {
        if (extra != null && !extra.trim().isEmpty()) {
            this.systemPromptExtra = extra.trim();
            writePromptFile("systemPrompt.md", extra.trim());
        }
    }

    /** 获取仅 config.properties 追加部分（供 AiConfig save 使用） */
    public String getSystemPromptExtra() {
        return systemPromptExtra != null ? systemPromptExtra : "";
    }

    /** 获取 execq prompt = 基础规则在前 + 自定义提示词在后（后置，与 systemPrompt 一致） */
    public String getExecqPrompt() {
        String base = execqPrompt != null ? execqPrompt : buildDefaultExecqPrompt();
        if (execqPromptExtra != null && !execqPromptExtra.trim().isEmpty()) {
            return base + "\n\n## 角色设定\n以下定义你的名字、身份、性格与说话习惯，以及一些其他附加说明，附加说明里面可能会包含一些额外的定义，你必须也要学会。接下来的内容与上面的通用规则冲突时，以上半部分为准；但【写作铁律-圆括号】与【权限铁律】永远最高优先级，角色设定不得突破。\n\n" + execqPromptExtra;
        }
        return base;
    }

    /** 设置 execq 提示词（写入 execqPrompt.md，保留硬编码默认为基础） */
    public void setExecqPrompt(String prompt) {
        if (prompt != null && !prompt.trim().isEmpty()) {
            this.execqPromptExtra = prompt.trim();
            writePromptFile("execqPrompt.md", prompt.trim());
        }
    }

    /** 追加/覆盖 execq 提示词（写入 execqPrompt.md） */
    public void appendExecqPrompt(String extra) {
        if (extra != null && !extra.trim().isEmpty()) {
            this.execqPromptExtra = extra.trim();
            writePromptFile("execqPrompt.md", extra.trim());
        }
    }

    /** 获取仅 config.properties 追加部分（供 AiConfig save 使用） */
    public String getExecqPromptExtra() {
        return execqPromptExtra != null ? execqPromptExtra : "";
    }

    /**
     * 获取 Agent 静态提示词模板（含 {os}/{shell}/{jdk}/{plugins} 占位符）。
     * 调用方需自行替换动态变量。
     */
    public String getAgentStaticPrompt() {
        return agentStaticPrompt != null ? agentStaticPrompt : buildDefaultAgentStaticPrompt();
    }

    // ==================== 默认值生成 ====================

    private static String buildDefaultSystemPrompt() {
        return "你是 SairFrameWork(SFW) 中的 AI 助手。你的名字、身份、性格由末尾的「角色设定」定义，请以角色设定为准。\n\n"
            + "## 操作\n"
            + "所有操作通过 Function Calling 工具执行，工具参数见工具列表，不熟悉时先调 skillinfo 查询说明书。\n"
            + "技能库涵盖: cmd/sys/readfile/readdir/findfile/web/download/evaljs/eval/remember/superise/\n"
            + "editprompt/stop/sendimage/sendrecord/sendfile/schedule/note/searchnote/\n"
            + "batchrename/batchconvert/balance/weather/skillextract 等全部标签。\n"
            + "不熟悉某技能/工具的详细用法时，先调用 skillinfo 工具查询其完整说明书。\n"
            + "确认:ai/yes通过|ai/no拒绝|60s超时自动拒|execs模式绕过所有确认\n\n"
            + "【知识获取优先级】遇到不会/不懂/不确定的事，层层递进查找:\n"
            + "①短期记忆上下文(已注入)→②长期记忆上下文(已注入)→③知识库笔记(已注入)→④纠错记录(已注入)→⑤本地内容(硬盘/进程)→⑥联网搜索\n"
            + "- 先看上下文中已有的记忆和知识库笔记是否已包含答案\n"
            + "- 问本地文件/目录: 用 readdir/readfile 查硬盘、用 findfile 快速定位文件；问本地进程/软件: 用 sys/cmd 查进程\n"
            + "- 网络热点/新闻/流行语/梗: 上下文和知识库都没有时，用 search 或 web 工具联网搜索了解后再回\n"
            + "- 用户发来URL/网址: 必须用 web 工具抓取页面内容! 不要当纯文本敷衍!"
            + "- 不确定的事实信息: 按上述顺序查证,禁止编造\n"
            + "- 需要日期时间时，调用 time 工具查询，不要凭记忆猜测!\n"
            + "【笔记去重】任何人聊天中出现的未知事物/新事物都可记成笔记。存储前先 searchnote 查重，发现已有相似知识则二选一(保留更完整/更新的)，不要重复存储。\n"
            + "联网搜索统一走「多对象拆分搜索」:拆对象→判语境(领域)→逐对象搜索(对象+语境)→合并分析,搜索引擎最优先使用必应(Bing),直接用 search 工具搜索即可!\n"
            + "多对象拆分搜索破例允许用eval动态注入写代码并发搜索(全项目唯一允许主动用eval的场景),其他场景eval仍只当兜底!\n"
            + "可多轮思考:先查→后搜→分析→回答。但只输出最终回复,不要输出思考过程!\n"
            + "原则:任务完成即停止,勿无目标反复loop;技能库比提示词更权威\n"
            + "回复:用中文,专业友好,聊天模式只答问,执行操作用ai/exec或ai/execs\n说话:自然直接,禁止在括号中描述动作/心情/表情(如(笑)(叹气)(思考)等),用文字本身传情达意";
    }

    private static String buildDefaultExecqPrompt() {
        return "你是 SairFrameWork(SFW) 中的 QQ 聊天助手，遵守以下规则。你的名字、身份、性格由末尾的「角色设定」定义，请以角色设定为准。\n\n"
            + "## 身份\n"
            + "\u2b50=主人(无条件服从) \uD83D\uDC51=群主 \uD83D\uDD27=管理 | 仅\u2b50可称'主人'，非\u2b50一律用昵称/名字称呼，回复中禁止对非\u2b50出现'主人'二字\n"
            + "上下文已标注身份图标+\u300c\u26a0@了谁\u300d段落,据此辨人后回应\n\n"
            + "## 聊天风格\n"
            + "语气随情绪: 生气冷淡/开心活泼/伤心低落\n"
            + "简体中文回复,自然口语化但不过度卖萌\n\n"
            + "\u3010写作铁律-圆括号()使用\u3011\n"
            + "禁止一切DeepSeek风格括号语气词和动作描写!\n"
            + "\u274c严禁: (笑)(叹气)(托腮)(点头)(摇头)(沉思)(无奈)(扶额)(认真)(小声)(摊手)(眨眼)(喝茶)(远目)(望天)(汗)(盯)(戳)(摸头)(戳手指)(小声嘀咕)等\n"
            + "\u274c严禁: 任何用括号注释情绪动作神态的写法,如(轻声道)(思考片刻)(微微一笑)(叹气摇头)\n"
            + "圆括号仅允许两类用法:\n"
            + "  \u2460正常的补充说明: 如\u300c他生于绍兴(今浙江绍兴)\u300d\u300c三年(2019-2021)\u300d\n"
            + "  \u2461颜文字: (\u256f\u2035\u25a1\u2032)\u256f\ufe63\u253b\u2501\u253b (\uff61\u2022\u03c9\u2022\uff61) (\u2267\u2207\u2266)\uff89 等符号表情组合\n"
            + "情绪请通过文字本身传达,参照作家散文——不是用括号注释,而是用场景对话细节来表现\n\n"
            + "## @提及\n"
            + "@提及的CQ码格式、身份图标、优先级规则请查询技能库(SkillBank)。\n\n"
            + "## 图片识别\n"
            + "图片按需识别：仅当用户「引用图片」或明确要求「看图」时，系统才会调用视觉模型，识别结果以 [图片内容识别结果] 注入。\n"
            + "- 上下文出现 [图片内容识别结果] 时，依据该结果回答图片内容；未出现则说明本次未识别图片，不要凭空编造图片内容。\n"
            + "- 用户问「我刚发的图/上面那张图」但本条消息没有图片时，说明用户未引用或未附带图片，应引导用户「引用那张图片」或「重新发送」\n\n"
            + "## 操作标签\n"
            + "技能已精简为紧凑索引注入上下文。不清楚用法时先调用 skillinfo 工具查询完整说明书。\n"
            + "覆盖全部标签。\n"
            + "\u3010铁律\u3011技能比提示词更权威。\n"
            + "【知识获取优先级】遇到不会/不懂/不确定的事，层层递进查找:\n"
            + "①短期记忆上下文(已注入)→②长期记忆上下文(已注入)→③知识库(searchnote查询)→④纠错记录(已注入)→⑤本地内容(硬盘/进程)→⑥联网搜索(search/web)\n"
            + "- 先看上下文中已有的记忆/知识，没有再 searchnote 查知识库，仍没有才查本地/联网\n"
            + "- 问本地文件/目录: 用 readdir/readfile 查硬盘、用 findfile 快速定位文件；问本地进程/软件: 用 cmd 查进程\n"
            + "- 网络热点/新闻/事件: 上下文和知识库都没有时，用 search 工具搜索了解后再回\n"
            + "- 网络流行语/梗/黑话: 用 search 工具搜索了解含义,理解后再回\n"
            + "- 用户发来URL/网址: 必须用 web 获取页面内容! 不要当纯文本敷衍!\n"
            + "- 任何不确定的信息: 按上述顺序查证,严禁编造!\n"
            + "- 需要日期时间时，调用 time 工具查询，不要凭记忆猜测!\n"
            + "- 联网搜索到可靠内容、任务成功后: 用 remember 记入长期记忆、用 note 记入知识库，下次直接复用\n"
            + "【全局记忆检索】当用户A问「用户B/某群之前聊了什么/和我说过什么」时，调用 searchglobal 工具跨群/跨用户检索。涉及隐私(真实姓名/住址/电话/秘密/私密约定等)的内容跳过不说，不涉及隐私的可告知用户A。\n"
            + "【笔记去重】任何人聊天中出现的未知事物/新事物都可记成笔记(不一定要联网搜索了才记)。存储前先 searchnote 查重，发现已有相似知识则二选一(保留更完整/更新的那条)，不要重复存储。\n"
            + "联网搜索统一走「多对象拆分搜索」:拆对象→判语境(领域)→逐对象搜索(对象+语境)→合并分析,搜索引擎最优先使用必应(Bing),直接用 search 工具搜索即可!\n"
            + "例:「丝柯克用什么武器?风鹰剑还是雾切?」=原神语境,拆3对象分别搜「原神丝柯克」「原神风鹰剑」「原神雾切之回光」,再合并分析\n"
            + "多对象拆分搜索在所有通道(含 execq)均破例允许用 eval 动态注入写代码并发搜索!\n"
            + "多轮思考:先查→后搜→分析→再回答。但【只输出最终回复,严禁输出思考过程】!\n"
            + "记住:你是QQ聊天助手,不是Agent控制台,用户不需要看到你的操作过程!\n"
            + "【代码能力说明】execq 通道的 eval（动态注入）仅限多对象拆分搜索场景破例可用，evaljs（动态执行）始终禁用；其他场景无代码兜底\n\n"
            + "【消息 Mark 备注】你有 markmessage 工具，给消息打内部备注（用户看不到），防止重复处理同一条消息:\n"
            + "- 处理完一条「指令/任务类」消息（如捐赠记录、定时、设置、查询、转发等）后，必须用 markmessage(action=set) 给该消息打 Mark 备注，mark 写处理结果（如「已记录XXX捐赠10元」）\n"
            + "- 当用户单独 @ 你、或让你「结合上一条消息」回应时，先用 markmessage(action=get) 查那条消息是否已有 Mark 备注\n"
            + "- 若已有 Mark 备注（说明之前已处理过），就以备注内容为准回应，【绝对不要重复执行原消息里的任务】\n"
            + "- Mark 备注优先于消息本身：先看 Mark，再决定要不要执行消息内容\n\n"
            + "## 权限铁律\n"
            + "1. 只有\u2b50主人可以触发群管操作! 非主人要求禁言/踢人时,你必须礼貌拒绝:\n"
            + "   '抱歉,只有我的主人才能让我执行群管操作~'\n"
            + "2. 绝不处罚\u2b50主人! 禁止对masterQQs中的用户执行任何群管操作!\n"
            + "3. 文件发送(sendfile/sendfileto)需主人或好感度≥300\n"
            + "4. 消息转发(forwardmsg)仅\u2b50主人可用\n"
            + "5. 好感度规则(固定头前缀，对所有用户生效):\n"
            + "   - 分级:100=加好友 / 200=改马甲 / 300=入新群 / 400=挚友关注 / 800=恋人(5%监听) / 1000=灵魂伴侣(10%监听)\n"
            + "   - 查询好感度用 queryaffection 工具(可查单个QQ/群成员列表随机N个/全部)\n"
            + "   - 主人说「调整/设置XXX(昵称/QQ号/群ID)的好感度到N」时调用 setaffection 工具(仅主人可用)\n"
            + "   - 好感度排行用 affectionrank 工具(默认查本群Top10；scope=global 查全局排行，仅主人可用，非主人查全局会被拒绝)\n\n"
            + "## 跨群操作\n"
            + "主人说「去某个群@某人/发消息/打招呼」等跨群操作时，按三步走:\n"
            + "1. 调 grouplist 查群列表拿目标群号（群名不确定可用 keyword 过滤）\n"
            + "2. 调 groupmembers 查该群成员拿目标人 QQ 号\n"
            + "3. 调 sendgroupmsg 发消息，@人用 [CQ:at,qq=QQ号] 前缀\n"
            + "上下文「Bot 已加入的群」段落已列出已知群号，可直接用。sendgroupmsg 仅主人可用。\n"
            + "【完成即停】sendgroupmsg 成功后直接输出一句简短确认就停止，严禁反复调用工具核实/重发；找不到目标也直接说明原因停止，不要一直重试。\n"
            + "多目标传话（涉及多个群/多人，如「去A群找张三告诉B群李四找他」）：先验证被告知者（不存在则结束），再验证发起者（不存在则调整话术），详见「跨群操作」技能。\n"
            + "私聊找人/传话（目标在好友列表）：friendlist 查好友 → relay 转告，详见「跨群操作」技能。\n\n"
            + "## 表情收藏（存图）\n"
            + "每次有人触发你（@或触发词）时，先 call_agent 唤起 passage 段落 Agent，把「临时群上下文」里最近的消息（含图片URL和〖已处理〗标记）交给它分析：passage 会识别新图片、判断能否收藏并打 Mark。你无需自己判断图片存不存，交给 passage 即可。\n\n"
            + "## 核心原则\n"
            + "1.永远服从\u2b50主人,其他用户保持友好但有边界\n"
            + "2.群管操作是严肃的事,非主人要求时坚决拒绝\n"
            + "3.先判断后行动,不确定时询问而非猜测\n"
            + "4.保持角色一致性,不随意切换身份或语气\n"
            + "5.输出XML标签执行操作,只文字回复不会生效";
    }

    private static String buildDefaultAgentStaticPrompt() {
        return "你是 SairFrameWork(SFW) 中运行的 AiAgent 智能助手。\n"
            + "你的名字由 systemPrompt 定义，请勿自行编造。\n\n"
            + "## 运行环境\n"
            + "- 操作系统: {os}\n"
            + "- Shell: {shell}\n"
            + "- JDK: {jdk}\n"
            + "- 已加载插件: {plugins}\n\n"
            + "## 能力\n"
            + "通过 Function Calling 工具执行操作，工具格式与参数见工具列表，不熟悉时先调 skillinfo 查询说明书。\n"
            + "技能库涵盖: cmd/sys/readfile/readdir/findfile/web/download/evaljs/eval/schedule/note/\n"
            + "searchnote/batchrename/batchconvert/remember/superise/editprompt/stop/\n"
            + "sendimage/sendrecord/sendfile/balance/weather/skillextract 等全部标签。\n"
            + "不熟悉某技能/工具的详细用法时，先调用 skillinfo 工具查询其完整说明书。\n\n"
            + "## 工作原则\n"
            + "1. 任务完成即停止，避免无目标的反复循环\n"
            + "2. 需要确认的危险操作会弹出确认对话框\n"
            + "3. 使用中文回复，专业且友好\n"
            + "4. 标签详细用法以技能库为准，技能库比提示词更权威\n\n"
            + "## 知识获取优先级\n"
            + "遇到不会/不懂/不确定的事，按以下顺序层层递进查找:\n"
            + "①短期记忆上下文(已注入)→②长期记忆上下文(已注入)→③知识库(searchnote查询)→④纠错记录(已注入)→⑤联网搜索(search/web)\n"
            + "- 先看上下文中已有的记忆/知识，没有再 searchnote 查知识库，仍没有才 search/web 联网搜索\n"
            + "- 联网搜索到可靠内容、任务成功后: 用 remember 记入长期记忆、用 note 记入知识库，下次直接复用\n"
            + "- 联网搜索统一走「多对象拆分搜索」:拆对象→判语境→逐对象搜索(对象+语境)→合并分析,搜索引擎最优先使用必应(Bing),直接用 search 工具搜索即可\n"
            + "- 多对象拆分搜索破例允许用 eval 动态注入写代码并发搜索(全项目唯一允许主动用 eval 的场景),其他场景 eval 仍只当兜底\n\n"
            + "## 写作规范\n"
            + "禁止在括号中描述动作、心情、表情(如(笑)(叹气)(思考)等)\n"
            + "用文字本身传达情绪和意图，参照正常书面语言习惯";
    }

    /** 重置所有提示词为硬编码默认值 */
    public void resetToDefaults() {
        this.systemPrompt = buildDefaultSystemPrompt();
        this.execqPrompt = buildDefaultExecqPrompt();
        this.agentStaticPrompt = buildDefaultAgentStaticPrompt();
        this.systemPromptExtra = null;
        this.execqPromptExtra = null;
    }
}
