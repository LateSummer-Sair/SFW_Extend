package sair.aiagent.core;

/**
 * 系统通用技能库——注入所有通道共享的内置技能。
 * scope=general 的技能对所有通道可见。
 *
 * <p>注意：本系统已切换到 Function Calling 运行模式，
 * 技能内容描述「工具调用」而非「XML 标签」。
 * 工具名用反引号包裹（如 `weather`），供 SkillBank 按工具名检索详情。</p>
 */
public class SystemSkillLib {

    public static void initSkills(SkillBank bank) {
        if (bank == null) return;

        // === 工具总览 ===
        bank.addBuiltinSkill("工具调用系统", "core", "通过 Function Calling 调用系统工具",
            "本系统通过 Function Calling 机制调用工具，你无需输出任何 XML 标签。\n\n" +
            "## 本地通道（execs）可用工具\n" +
            "- `cmd`: 执行命令/调用插件（格式: pluginName/funcName args）\n" +
            "- `sys`: 执行系统命令\n" +
            "- `evaljs`: 执行 JavaScript 代码（Nashorn 引擎）\n" +
            "- `eval`: 动态编译执行 Java 代码\n" +
            "- `web`: 获取网页内容\n" +
            "- `readfile`: 读取文件\n" +
            "- `readdir`: 列出目录\n" +
            "- `download`: 下载文件\n" +
            "- `remember`: 记录持久化记忆\n" +
            "- `sendimage`: 渲染文字为图片/发送图片\n" +
            "- `sendrecord`: 发送语音消息\n" +
            "- `sendfile`: 发送文件\n" +
            "- `editprompt`: 修改系统提示词\n" +
            "- `superise`: 弹出彩蛋窗口\n" +
            "- `schedule`: 创建定时任务\n" +
            "- `note`: 知识库操作\n" +
            "- `searchnote`: 搜索知识库\n" +
            "- `batchrename`: 批量重命名\n" +
            "- `batchconvert`: 批量图片格式转换\n" +
            "- `balance`: 查询 API 余额\n" +
            "- `weather`: 查询天气\n" +
            "- `skillextract`: 从对话轨迹中蒸馏技能\n" +
            "- `stop`: 停止当前任务\n\n" +
            "## execq 通道额外工具\n" +
            "- `setname`: 设置机器人名字\n" +
            "- `setsignature`: 设置个性签名\n" +
            "- `sendsticker`: 发送表情包\n" +
            "- `collectsticker`: 收藏表情包\n" +
            "- `poke`: 戳一戳\n" +
            "- 群管: `ban` `kick` `muteall` `setadmin` `setcard` `setgroupname` `leavegroup`\n" +
            "- 好友管理: `block` `unblock` `delfriend`\n" +
            "- 互动: `sendlike` `sendfileto` `relay` `forwardmsg`\n\n" +
            "## 工具权限\n" +
            "- 本地通道: execq 工具不可用\n" +
            "- QQ execq 通道: 仅白名单工具可用",
            "builtin", "general");

        // === 动态代码执行 ===
        bank.addBuiltinSkill("动态代码执行", "core", "区分「动态注入 eval」与「动态执行 evaljs」的本质区别",
            "本系统有两种代码执行方式，本质完全不同，务必区分：\n\n" +
            "## eval = 动态注入（Java 代码）\n" +
            "- 将 Java 源码编译后加载进 JVM 执行，是真正「注入」一个类\n" +
            "- 能力最强，可调用 SFW 内部 API、反射访问运行时任意对象，是最终兜底手段\n\n" +
            "## evaljs = 动态执行（JavaScript 脚本）\n" +
            "- 在 Nashorn 引擎中解释执行 JS 脚本，只是「执行」一段脚本，非注入\n" +
            "- 适合快速脚本、HTTP 请求、数据处理，能力弱于 eval\n\n" +
            "## 铁律\n" +
            "- 兜底必须用 eval（动态注入），绝不能用 evaljs 替代\n" +
            "- 最长执行时间: 30 秒，输出截断: 10000 字符\n" +
            "- execq 通道: `eval` 破例可用于「多对象拆分搜索」场景，其他场景仍禁用；`evaljs` 始终不可用",
            "builtin", "general");

        // === 天气 ===
        bank.addBuiltinSkill("天气查询", "tool", "查询指定城市的实时天气信息",
            "调用 `weather` 工具查询天气，参数 city 为城市名。\n\n" +
            "## 参数\n" +
            "- city: 城市名称（支持中文和英文）\n\n" +
            "## 示例\n" +
            "- `weather` 北京\n" +
            "- `weather` Tokyo\n\n" +
            "## 返回信息\n" +
            "- 当前温度、体感温度\n" +
            "- 天气状况（晴/多云/雨等）\n" +
            "- 湿度、风速、气压\n" +
            "- 未来几小时预报\n\n" +
            "## 限制\n" +
            "- 免费 API 有请求频率限制\n" +
            "- 城市名不存在时返回错误",
            "builtin", "general");

        // === 网页操作 ===
        bank.addBuiltinSkill("时效性信息查询", "tool", "查询日期、新闻、事件等需要实时验证的信息",
            "时效性信息查询已统一并入「多对象拆分搜索」技能，普通整句搜索已废弃禁用。\n\n" +
            "## 核心规则\n" +
            "- 需要日期时间时，调用 time 工具查询，不要凭记忆猜测\n" +
            "- 当用户询问超出你知识范围的时效性信息（如新闻、热点事件）时，按「多对象拆分搜索」四步法：拆对象→判语境→逐对象搜索→合并分析\n" +
            "- 绝对禁止幻觉编造！不知道就是不知道，去搜索！\n\n" +
            "## 注意\n" +
            "- SFW 框架没有 date 命令，不要再尝试 `cmd` date！\n" +
            "- 搜索后提取关键信息用中文简洁回复",
            "builtin", "general");

        bank.addBuiltinSkill("网页内容获取", "tool", "获取指定 URL 的网页/接口内容",
            "本技能仅用于「用户发来 URL/网址时获取该指定页面内容」，不是搜索方式；联网搜索请走「多对象拆分搜索」技能。\n\n" +
            "调用 `web` 工具获取网页或 API 接口内容，参数 url 为完整地址。\n\n" +
            "## 参数\n" +
            "- url: 完整的 HTTP/HTTPS 地址\n\n" +
            "## 特性\n" +
            "- 自动跟随 HTTP 3xx 重定向 + 识别 meta refresh 跳转（最多 5 次）\n" +
            "- 自动识别编码（UTF-8/GBK，乱码自动纠正）\n" +
            "- 自动解压 gzip/deflate 响应\n" +
            "- 自动提取正文（剔除脚本/样式/标签，纯文本返回）\n" +
            "- 自动识别 JSON 接口响应并原样返回结构化数据\n" +
            "- 网络异常自动重试 2 次\n" +
            "- 超时: 连接 10 秒, 读取 20 秒\n\n" +
            "## 安全\n" +
            "- 内网地址自动拒绝（SSRF 防护）\n" +
            "- 返回内容截断至合理长度\n\n" +
            "## 局限\n" +
            "- 无法执行 JS：纯前端渲染的动态网页（SPA）抓不到正文，此时应找 API 接口，或用 eval（动态注入）写代码请求\n" +
            "- 需要登录/cookie 的页面无法访问\n\n" +
            "## 示例\n" +
            "- `web` https://api.example.com/data\n" +
            "- `web` https://zh.wikipedia.org/wiki/Java",
            "builtin", "general");

        // === 多对象拆分搜索（唯一搜索方式，破例允许动态注入） ===
        bank.addBuiltinSkill("多对象拆分搜索", "tool", "唯一联网搜索方式：拆对象逐搜再合并（破例允许动态注入）",
            "本技能是【唯一】的联网搜索方式，永久替换普通 web 搜索，普通整句搜索已废弃禁用。\n\n" +
            "所有需要联网搜索/查证的问题（对比、选择、时效信息、热点、多个名词实体等）一律按以下四步法：\n\n" +
            "## 四步法\n" +
            "1. 拆对象：把问题拆成多个独立对象（人物/物品/概念等名词实体）\n" +
            "2. 判语境：识别问题整体指向的领域/主题（如游戏名、作品名、历史事件等）\n" +
            "3. 逐对象搜索：每个对象单独搜一次，搜索词 = 对象名 + 语境限定词\n" +
            "4. 合并分析：把各对象搜索结果放在一起综合对比，得出结论\n\n" +
            "## 搜索引擎（重要）\n" +
            "- 【最优先使用必应 Bing】，其次才是其他搜索引擎\n" +
            "- 搜索地址: https://www.bing.com/search?q=搜索词\n\n" +
            "## 破例允许动态注入（重要！）\n" +
            "本技能【破例允许】使用 eval 动态注入写代码执行搜索：\n" +
            "- 可用 Java 代码（HttpURLConnection 等）并发抓取多个对象的搜索结果并解析合并\n" +
            "- 这是全项目唯一允许主动使用动态注入的场景；其他技能/场景仍只把 eval 当兜底\n" +
            "- 所有通道（本地 execs / QQ execq / QQ execs:）均破例允许用 eval 做多对象拆分搜索\n\n" +
            "## 示例\n" +
            "用户问：「丝柯克用什么武器好？风鹰剑还是雾切之回光？」\n" +
            "语境 = 原神；拆成 3 个对象分别搜（优先必应）：\n" +
            "- `web` https://www.bing.com/search?q=原神 丝柯克\n" +
            "- `web` https://www.bing.com/search?q=原神 风鹰剑\n" +
            "- `web` https://www.bing.com/search?q=原神 雾切之回光\n" +
            "搜完后综合三者信息分析，给出结论\n\n" +
            "## 注意\n" +
            "- 搜索词一定要带上语境限定词，避免搜到无关内容\n" +
            "- 只输出最终结论，不要输出拆解和搜索过程",
            "builtin", "general");

        // === 文件操作 ===
        bank.addBuiltinSkill("文件读写", "tool", "读取和列出文件目录",
            "`readfile` 工具读取文本文件内容，参数 path 为文件路径。\n" +
            "`readdir` 工具列出目录中的文件和子目录，参数 path 为目录路径。\n\n" +
            "## 示例\n" +
            "- `readfile` /data/config.txt\n" +
            "- `readdir` /home/user/documents\n\n" +
            "## 递归翻找文件时的状态判断（重要）\n" +
            "当需要递归查找文件时，每步操作后都要判断工作状态：\n" +
            "- 找到目标 → 工作完成，立即给出结果并停止\n" +
            "- 目录为空 / 文件不存在 / 路径不存在 → 此路不通，换下一路径，不要反复重试同一路径\n" +
            "- 已检查过的路径要记住，不要重复进入同一个目录\n" +
            "- 连续多次（约 8 次以上）都是死路/空结果 → 判定为找不到，主动告知用户「未找到」并停止，不要无限翻找\n" +
            "- 每次 readdir/readfile 返回有效内容（非空目录、非不存在）都算有进展，可以继续\n\n" +
            "## 注意\n" +
            "- 大文件可能被截断\n" +
            "- 二进制文件（如图片）仅返回元信息",
            "builtin", "general");

        // === 记忆系统 ===
        bank.addBuiltinSkill("持久化记忆", "memory", "将重要信息保存到持久化记忆中，理解四层记忆架构",
            "## 四层记忆系统架构\n" +
            "本系统采用分层记忆设计，每层自动注入到提示词中:\n\n" +
            "### 1. 工作记忆（自动）- 当前对话上下文\n" +
            "- 系统自动注入最近的对话历史（滑动窗口）\n" +
            "- 无需任何操作，AI 直接从 system prompt 读取\n\n" +
            "### 2. 短期记忆（自动）- 近期交互记录\n" +
            "- JournalManager: 最近 15-25 条操作日志自动注入\n" +
            "- 包含最近的工具执行结果（操作类型+内容+结果）\n" +
            "- 跨会话持久化（SQLite），重启不丢失\n\n" +
            "### 3. 长期记忆（自动+手动）- 跨会话持久化记忆\n" +
            "- MemoryManager: FTS5 全文搜索，根据用户消息自动检索相关记忆注入\n" +
            "- 调用 `remember` 工具手动写入持久记忆\n" +
            "- QQ 记忆系统:\n" +
            "  * UnifiedQQMemoryManager (统一记忆库 unified_memory.db): execq 通道主记忆库，所有 QQ 共享\n" +
            "  * AI 应了解: `remember` 在 execq 通道写入 UnifiedQQMemoryManager，在本地通道写入 aiagent.db\n" +
            "  * 搜索时系统自动检索对应的记忆库，无需手动区分\n\n" +
            "### 4. 持久记忆（手动查询）- 知识库\n" +
            "- Notes 知识库: 使用 `note` 工具 CRUD，使用 `searchnote` 工具 FTS5 搜索\n" +
            "- 系统会自动将相关笔记注入提示词（Knowledge Base Notes 段）\n" +
            "- AI 应主动使用 `searchnote` 查询不熟悉的领域知识\n\n" +
            "## 知识获取优先级（铁律）\n" +
            "遇到不会/不懂/不确定的事，必须按以下顺序层层递进查找，不要跳过:\n" +
            "1. 短期记忆上下文（已自动注入）\n" +
            "2. 长期记忆上下文（已自动注入）\n" +
            "3. 知识库（`searchnote` 查询）\n" +
            "4. 联网搜索（`web`）\n\n" +
            "- 先看上下文中已有的记忆/知识，没有再 `searchnote` 查知识库，仍没有才 `web` 联网搜索\n" +
            "- 联网搜索到可靠内容、任务成功完成后: 用 `remember` 记入长期记忆、用 `note` 记入知识库，下次直接复用\n\n" +
            "## 示例\n" +
            "- `remember` 用户喜欢简约风格的 UI 设计\n" +
            "- `remember` 项目使用 PostgreSQL 数据库，端口 5432\n" +
            "- `searchnote` 数据库配置 (查询知识库)",
            "builtin", "general");

        // === 知识库 ===
        bank.addBuiltinSkill("知识库管理", "knowledge", "管理可搜索的持久化知识条目",
            "`note` 工具支持完整的 CRUD 操作:\n\n" +
            "## 添加\n" +
            "`note` add title|content|tags\n\n" +
            "## 搜索\n" +
            "`note` search query\n\n" +
            "## 列表\n" +
            "`note` list\n\n" +
            "## 获取\n" +
            "`note` get id\n\n" +
            "## 删除\n" +
            "`note` delete id\n\n" +
            "## 更新\n" +
            "`note` update id title|content|tags",
            "builtin", "general");

        // === 定时任务 ===
        bank.addBuiltinSkill("定时任务调度", "automation", "创建和管理 cron 定时任务",
            "调用 `schedule` 工具创建定时任务，命令格式: add \"cron表达式\" \"command\"。\n\n" +
            "## Cron 格式\n" +
            "分 时 日 月 周 command\n\n" +
            "## 示例\n" +
            "- `schedule` add \"0 8 * * *\" \"remind morning meeting\" (每天 8 点)\n" +
            "- `schedule` add \"*/30 * * * *\" \"check status\" (每 30 分钟)\n" +
            "- `schedule` add \"0 0 1 * *\" \"monthly report\" (每月 1 号)\n\n" +
            "## 注意\n" +
            "- 精确到分钟级别\n" +
            "- 任务命令在本地 Shell 执行",
            "builtin", "general");

        // === 系统闹钟 ===
        bank.addBuiltinSkill("系统闹钟", "automation", "到点唤醒AI执行任务的闹钟（区别 schedule 只执行 SFW 命令）",
            "调用 `alarm` 工具创建系统闹钟，到点后会实实在在唤醒 AI 走 Agent 链路执行任务。\n\n" +
            "## 子命令\n" +
            "- `alarm` add schedule|task —— 创建闹钟\n" +
            "- `alarm` list —— 列出所有闹钟\n" +
            "- `alarm` remove id —— 移除闹钟（删除快照）\n" +
            "- `alarm` cancel id —— 取消重复闹钟（等价 remove）\n" +
            "- `alarm` append id|备注内容 —— 追加备注到快照（可无限追加直到任务移除）\n\n" +
            "## schedule 格式（AI 负责把自然语言翻译成此格式）\n" +
            "- once:yyyy-MM-dd HH:mm —— 一次性（到点触发即视为完成，自动移除）\n" +
            "- daily:HH:mm —— 每天固定时刻\n" +
            "- weekly:D,HH:mm —— 每周固定星期几，D=1周一~7周日\n\n" +
            "## 语义分析规则\n" +
            "- \"每天\"/\"每日\" → daily:HH:mm\n" +
            "- \"每周一/周二...\"/\"每星期几\" → weekly:D,HH:mm\n" +
            "- \"明天\"/\"下周三\"/\"某月某日几点\" 等一次性 → once:yyyy-MM-dd HH:mm\n" +
            "- 只给相对时间无明确日期时，按最近一次该时刻计算 once 日期\n\n" +
            "## 权限分级\n" +
            "- execq 普通用户 → REMIND（到点仅发送提醒，不执行任务）\n" +
            "- execq 主人 → EXECQ（到点走受限 Agent 链路执行）\n" +
            "- execs / 本地控制台 → EXECS（到点走全权限 Agent 链路执行）\n\n" +
            "## 注意\n" +
            "- task 描述要清晰完整，因为到点时 AI 会以快照上下文重新执行它\n" +
            "- 创建闹钟时会自动保存当时的上下文快照，到点按快照重建上下文",
            "builtin", "general");

        // === 余额查询 ===
        bank.addBuiltinSkill("API余额查询", "admin", "查询 DeepSeek API 账户余额",
            "调用 `balance` 工具查询当前 API 账户余额。\n\n" +
            "## 返回信息\n" +
            "- 账户余额和货币单位\n" +
            "- 已使用额度\n" +
            "- 赠送额度（如有）\n\n" +
            "## 频率\n" +
            "- 建议不超过每 10 分钟一次",
            "builtin", "general");

        // === 批量操作 ===
        bank.addBuiltinSkill("批量文件操作", "tool", "批量重命名和格式转换",
            "批量重命名: `batchrename` 工具（参数 dir/pattern/replacement）\n" +
            "批量转换: `batchconvert` 工具（参数 dir/from/to）\n\n" +
            "## 重命名示例\n" +
            "- dir=/photos pattern=(IMG_\\d+)\\.jpg replacement=$1_edited.jpg\n" +
            "- dir=/downloads pattern=preview replacement=\n\n" +
            "## 转换示例\n" +
            "- dir=/images from=png to=jpg\n\n" +
            "## 注意\n" +
            "- 使用 preview 前缀可预览不实际执行\n" +
            "- 转换仅支持常见图片格式",
            "builtin", "general");

        // === SFW命令执行 ===
        bank.addBuiltinSkill("SFW命令执行", "tool", "通过 cmd 工具执行 SFW 插件命令或查询帮助",
            "调用 `cmd` 工具执行 SFW 框架命令，参数 command 格式为 pluginName/funcName args。\n\n" +
            "## 格式\n" +
            "- 插件名/函数名 参数: 执行插件函数并返回控制台输出\n\n" +
            "## 示例\n" +
            "- `cmd` FileManager/delete temp.txt\n" +
            "- `cmd` NetworkUtils/ping 8.8.8.8\n\n" +
            "## 查询帮助（execq 通道）\n" +
            "- `cmd` /help: 查看 SFW 框架自身帮助 + 可用组件列表\n" +
            "- `cmd` 组件名/help: 查看指定组件的帮助（如 `cmd` ai/help 查看 ai 组件帮助）\n\n" +
            "## 确认规则\n" +
            "- 需确认: ai/yes 通过 | ai/no 拒绝 | 60s 超时自动拒\n" +
            "- execs 模式绕过所有确认\n\n" +
            "## 限制\n" +
            "- execq 通道: cmd 仅允许 help 查询（/help 或 组件名/help），其他命令一律禁止\n" +
            "- 本地通道: 无限制",
            "builtin", "general");

        // === 系统Shell ===
        bank.addBuiltinSkill("系统Shell执行", "tool", "通过 sys 工具执行系统 Shell 命令",
            "调用 `sys` 工具执行系统 Shell 命令，参数 command 为命令内容。\n\n" +
            "## 特性\n" +
            "- 实时捕获 stdout 和 stderr 输出\n" +
            "- 最长等待 35 秒后超时\n" +
            "- 根据操作系统自动选择 Shell\n\n" +
            "## 示例\n" +
            "- `sys` dir C:\\Users (Windows)\n" +
            "- `sys` ls -la /home (Linux)\n" +
            "- `sys` pip install requests\n\n" +
            "## 确认规则\n" +
            "- 需确认: 高危操作会弹出确认对话框\n" +
            "- execs 模式: 绕过确认\n\n" +
            "## 安全\n" +
            "- execq 通道: `sys` 工具不可用",
            "builtin", "general");

        // === Java动态注入 ===
        bank.addBuiltinSkill("Java动态注入", "tool", "通过 eval 工具一次性编译执行 Java 代码（动态注入，万能兜底）",
            "调用 `eval` 工具编译并执行一段 Java 代码，参数 code 为源码内容。\n\n" +
            "## 核心约定（必须遵守）\n" +
            "- 源码可以无 package 声明（有 package 也能正确处理）\n" +
            "- 类中必须定义 `public Object run()` 方法，而不是 main 方法\n" +
            "- 执行结果由 `run()` 的返回值给出（返回 null 则显示 null）\n" +
            "- 全程一次性：编译→加载→实例化→调用 run()→丢弃，不保留任何状态\n\n" +
            "## 万能兜底\n" +
            "- eval 是任何技能的最终兜底选项：常规工具连续失败 5 次后，系统会自动引导你用 eval 编写代码直接完成任务\n" +
            "- 免手动编译，可调用 SFW 框架内部任何 API，也能反射得到当前运行时全部对象\n\n" +
            "## 可访问的 SFW 内部 API\n" +
            "- `sair.sys.Libraries.activities`: 组件注册表 Map<String, Activity>，遍历可获取所有已加载组件及名称\n" +
            "- `sair.sys.Libraries.mods` / `sair.sys.Libraries.exections`: 模块与执行器注册表\n" +
            "- `sair.sys.SairCons.runner(boolean isMark, String cmd)`: 执行 SFW 命令，cmd 格式 `组件名/功能名 参数`，返回 Object 结果\n" +
            "- `sair.sys.SairCons.toActiRun(Activity, funcName, args)`: 直接调用组件的 main 方法\n" +
            "- `sair.user.Activity.main(String funcName, String args)`: 组件功能入口（返回 Object）\n" +
            "- `sair.user.Activity.help()`: 返回组件帮助信息 String[]\n" +
            "- `sair.user.Activity.getName()` / `getDataDir()`: 组件名与数据目录\n" +
            "- `sair.LoaderManager.loader`: SairLoader 加载器\n\n" +
            "## 完整示例\n" +
            "- `eval` class Hello { public Object run() { return \"hello \" + (1+1); } }\n" +
            "- `eval` class Sum { public Object run() { int s=0; for(int i=1;i<=100;i++) s+=i; return s; } }\n" +
            "- `eval` class ListActs { public Object run() { return sair.sys.Libraries.activities.keySet().toString(); } }\n" +
            "- `eval` class RunCmd { public Object run() { return sair.sys.SairCons.runner(false, \"组件名/功能名 参数\"); } }\n\n" +
            "## 编译环境\n" +
            "- 使用 JDK 的 JavaCompiler 内存编译，无需落盘\n" +
            "- 编译 classpath 自动包含 ai.jar、SFW.jar 与 SFW 依赖 jars\n" +
            "- 源码按 UTF-8 编码编译\n\n" +
            "## 确认规则\n" +
            "- 需确认（代码注入风险）\n" +
            "- execs 模式: 绕过确认\n\n" +
            "## 限制\n" +
            "- 最长执行时间 30 秒\n" +
            "- 输出结果截断 10000 字符\n" +
            "- 缺少无参 run() 方法会返回编译/执行错误提示\n" +
            "- execq 通道: `eval` 仅限「多对象拆分搜索」破例可用，其他场景仍禁用",
            "builtin", "general");

        // === JavaScript执行 ===
        bank.addBuiltinSkill("JavaScript执行", "tool", "通过 evaljs 工具动态执行 JavaScript 代码（Nashorn 引擎，非动态注入）",
            "调用 `evaljs` 工具在 Nashorn 引擎中动态执行 JavaScript（脚本解释执行，非 Java 动态注入），参数 code 为代码内容。\n\n" +
            "## 可用 Java 类\n" +
            "- java.lang.* (基础类型)\n" +
            "- java.util.* (集合/工具)\n" +
            "- java.math.* / java.text.* (数值/日期)\n" +
            "- java.net.* (HTTP 请求)\n" +
            "- java.io.* / java.nio.file.* (文件操作)\n" +
            "- sair.* (SFW 框架内部 API：Libraries/SairCons 等，可反射访问运行时对象)\n\n" +
            "## 示例\n" +
            "- `evaljs` var x = 1 + 2; x * 10;\n" +
            "- `evaljs` new java.net.URL('https://api.example.com').getText()\n\n" +
            "## 确认规则\n" +
            "- 需确认（代码注入风险）\n" +
            "- execs 模式: 绕过确认\n\n" +
            "## 限制\n" +
            "- Nashorn 引擎，不支持 ES6+ 语法\n" +
            "- 本质是「动态执行」JS 脚本，不能替代 eval 的「动态注入」Java 代码作为兜底\n" +
            "- execq 通道: `evaljs` 不可用（动态执行已禁止）",
            "builtin", "general");

        // === 文件下载 ===
        bank.addBuiltinSkill("文件下载", "tool", "通过 download 工具下载网络文件",
            "调用 `download` 工具下载文件到本地，参数 url 为下载地址。\n\n" +
            "## 行为\n" +
            "- 文件保存到 dataDir/downloads/ 目录\n" +
            "- 重名文件自动添加序号后缀\n" +
            "- 下载路径自动记录到持久化记忆\n\n" +
            "## 示例\n" +
            "- `download` https://example.com/data.zip\n" +
            "- `download` https://cdn.example.com/image.png\n\n" +
            "## 确认规则\n" +
            "- 需确认\n" +
            "- execs 模式: 绕过确认\n\n" +
            "## 安全\n" +
            "- 内网地址自动拒绝（SSRF 防护）\n" +
            "- 仅允许 HTTP/HTTPS 协议",
            "builtin", "general");

        // === 彩蛋窗口 ===
        bank.addBuiltinSkill("彩蛋窗口", "interact", "通过 superise 工具弹出惊喜窗口",
            "调用 `superise` 工具弹出彩蛋窗口，参数 content 为彩蛋文本。\n\n" +
            "## 用途\n" +
            "- 在任务完成时给用户惊喜\n" +
            "- 以可视化方式传达表情/心情\n" +
            "- 为枯燥任务增添趣味性\n\n" +
            "## 示例\n" +
            "- `superise` 任务完成！\n" +
            "- `superise` 发现了一个彩蛋！\n\n" +
            "## 确认规则\n" +
            "- 无需确认\n\n" +
            "## 注意\n" +
            "- 仅在真正惊喜/特殊时刻使用，勿频繁滥用\n" +
            "- execq 通道: `superise` 不可用",
            "builtin", "general");

        // === 图片发送 ===
        bank.addBuiltinSkill("图片生成与发送", "media", "通过 sendimage 工具渲染文字为图片或发送已有图片",
            "调用 `sendimage` 工具发送图片，参数 content 为内容。\n\n" +
            "## 用法\n" +
            "- 文字内容: 将文字渲染为图片\n" +
            "- 本地路径: 发送已有图片文件\n" +
            "- URL: 下载网络图片后发送\n\n" +
            "## 示例\n" +
            "- `sendimage` 今日天气晴好\n" +
            "- `sendimage` /data/screenshots/result.png\n" +
            "- `sendimage` https://example.com/photo.jpg\n\n" +
            "## 确认规则\n" +
            "- 无需确认\n\n" +
            "## 注意\n" +
            "- execq 通道: sendimage 通过 Function Calling 工具直接发送到 QQ 会话",
            "builtin", "general");

        // === 语音发送 ===
        bank.addBuiltinSkill("语音发送", "media", "通过 sendrecord 工具发送语音消息",
            "调用 `sendrecord` 工具发送语音，参数 path 为语音文件路径或 URL。\n\n" +
            "## 格式\n" +
            "- 本地路径: /path/to/audio.mp3\n" +
            "- URL: https://example.com/audio.mp3\n\n" +
            "## 示例\n" +
            "- `sendrecord` /data/voice/greeting.wav\n" +
            "- `sendrecord` https://tts.example.com/speech.mp3\n\n" +
            "## 确认规则\n" +
            "- 无需确认\n\n" +
            "## 支持格式\n" +
            "- mp3, wav, amr, ogg 等常见音频格式",
            "builtin", "general");

        // === 文件发送 ===
        bank.addBuiltinSkill("文件发送", "media", "通过 sendfile 工具发送本地文件",
            "调用 `sendfile` 工具发送本地文件，参数 path 为文件路径。\n\n" +
            "## 示例\n" +
            "- `sendfile` /data/reports/monthly.pdf\n" +
            "- `sendfile` /downloads/archive.zip\n\n" +
            "## 确认规则\n" +
            "- 无需确认\n\n" +
            "## 权限\n" +
            "- 本地通道: 无限制\n" +
            "- execq 通道: 仅主人可用",
            "builtin", "general");

        // === 提示词编辑 ===
        bank.addBuiltinSkill("提示词编辑", "admin", "通过 editprompt 工具动态修改系统提示词",
            "调用 `editprompt` 工具修改 AI 的系统提示词，参数 content 为新的提示词。\n\n" +
            "## 规则\n" +
            "- 新提示词长度 >= 30 字符\n" +
            "- 修改后立即生效，自动持久化\n" +
            "- 可通过 resetToDefaults() 恢复默认\n\n" +
            "## 示例\n" +
            "- `editprompt` 你是一个专业的 Python 开发助手\n\n" +
            "## 确认规则\n" +
            "- 无需确认\n\n" +
            "## 注意\n" +
            "- 修改提示词会影响 AI 后续所有行为",
            "builtin", "general");

        // === 停止执行 ===
        bank.addBuiltinSkill("停止Agent执行", "control", "通过 stop 工具立即停止当前 Agent 循环",
            "调用 `stop` 工具立即停止 Agent 的当前执行循环。\n\n" +
            "## 效果\n" +
            "- Agent 立即退出当前任务循环\n" +
            "- 已完成的操作不会被回滚\n\n" +
            "## 确认规则\n" +
            "- 无需确认，直接执行\n\n" +
            "## 使用场景\n" +
            "- 任务目标已达成，无需继续\n" +
            "- 用户要求停止\n" +
            "- 检测到死循环或无意义重复",
            "builtin", "general");

        // === 快速笔记搜索 ===
        bank.addBuiltinSkill("快速笔记搜索", "knowledge", "通过 searchnote 工具快速搜索知识库",
            "调用 `searchnote` 工具快速搜索笔记，参数 query 为检索关键词。\n\n" +
            "## 等价于\n" +
            "- `searchnote` keyword = `note` search keyword\n\n" +
            "## 示例\n" +
            "- `searchnote` 数据库配置\n" +
            "- `searchnote` 部署步骤\n\n" +
            "## 返回\n" +
            "- 匹配的笔记标题和摘要列表\n" +
            "- 支持全文搜索（FTS5）",
            "builtin", "general");

        // === 技能蒸馏 ===
        bank.addBuiltinSkill("技能蒸馏触发", "skills", "通过 skillextract 工具从对话轨迹中蒸馏技能",
            "调用 `skillextract` 工具触发技能蒸馏，参数 focus 为蒸馏重点（可选）。\n\n" +
            "## 触发方式\n" +
            "- 显式: 用户说请蒸馏/记住这个方法时自动调用\n" +
            "- 隐式: 每轮 Agent 执行完毕自动分析轨迹\n\n" +
            "## 工作流程\n" +
            "1. 扫描当前对话的操作轨迹\n" +
            "2. 通过 LLM 分析提取可复用模式\n" +
            "3. 去重后存入 SkillBank 技能库\n\n" +
            "## 参数\n" +
            "- focus: 可选，指定蒸馏重点\n\n" +
            "## 示例\n" +
            "- `skillextract` 蒸馏本次任务的模式\n" +
            "- `skillextract` 文件批量处理\n\n" +
            "## 限制\n" +
            "- 至少需要 2 个有效操作步骤\n" +
            "- 60 秒内不重复触发（显式调用除外）",
            "builtin", "general");

        // === 三方技能库管理 ===
        bank.addBuiltinSkill("三方技能库管理", "admin", "通过 thirdskill 工具增删三方技能库（仅 execs / QQ execs 可用）",
            "三方技能库是独立于内置技能库的物理隔离技能仓库，位于 data/skills/ 目录，每个技能一个 .md 文件。\n\n" +
            "## 核心规则\n" +
            "- 三方技能库与内置技能库完全隔离，同名技能三方优先覆盖内置\n" +
            "- 所有通道（本地 / execq / QQ execs）都可用三方技能\n" +
            "- 只有 execs 和 QQ execs 能调用 `thirdskill` 工具管理三方库\n\n" +
            "## 子命令\n" +
            "- `thirdskill` list: 列出当前所有三方技能\n" +
            "- `thirdskill` add 技能名|描述|内容: 新增一个三方技能（写为 .md 文件并热加载）\n" +
            "- `thirdskill` delete 技能名: 删除一个三方技能（删除对应 .md 文件）\n\n" +
            "## 修改技能（无 edit 子命令）\n" +
            "- 不提供「改」操作。需要修改技能时，直接编辑 data/skills/ 下对应 .md 文件即可\n" +
            "- 系统监听 skills 目录：新增文件自动加载、删除文件自动移除、文件变更自动重载\n\n" +
            "## 导入三方技能的方式（无需工具）\n" +
            "- 把写好的 .md 文件放进 data/skills/ 目录，系统自动监听加载\n" +
            "- 文件格式：首部 front matter 声明 name 和 description，正文为技能内容；文件名必须与 name 同名\n\n" +
            "## 示例\n" +
            "- `thirdskill` list\n" +
            "- `thirdskill` add 代码审查|审查代码质量|审查用户提供的代码，指出潜在缺陷和改进建议\n" +
            "- `thirdskill` delete 代码审查\n\n" +
            "## 安全约束\n" +
            "- 技能名不能包含路径分隔符、两个点、冒号、星号、问号、尖括号、竖线等特殊字符\n" +
            "- 单个技能内容上限 8KB\n" +
            "- 文件名必须与 front matter 的 name 字段完全一致，否则拒绝加载\n" +
            "- 三方技能数量越多，注入上下文的 token 消耗越大，会相应影响 API 余额\n" +
            "- 删除操作会直接删除对应 .md 文件，不可恢复\n\n" +
            "## 代码段（可执行能力）\n" +
            "- 单文件 .md 正文可内嵌代码段：<java start>…<java end>、<js start>…<js end>、<nodejs start>…<nodejs end>、<python start>…<python end>\n" +
            "- 代码段会被提取并脱离正文（正文保留自然语言说明），通过 Function Calling 工具执行\n" +
            "- 固定入口函数 airun（强制返回值，返回空视为错误）；入参为 JSON 对象，出参转字符串返回给 AI\n" +
            "- java 代码段用 JVM 动态编译并缓存实例，反射调用 public Object airun(String) 或 public Object airun()\n" +
            "- js 代码段用 Nashorn 引擎执行 airun(JSON.parse(args))\n" +
            "- nodejs/python 代码段走系统命令行（node/python 需预装，缺失时启动红字提示）\n" +
            "## airun 函数签名（推荐声明，让 AI 高效调用）\n" +
            "- 在 front matter 声明 airun 的描述与参数，系统会动态注册成具名工具 tp_技能名，AI 直接调用\n" +
            "- 声明格式：\n" +
            "  airun:\n" +
            "    description: 计算数学表达式并返回结果\n" +
            "    params:\n" +
            "      expression:\n" +
            "        type: string\n" +
            "        description: 数学表达式\n" +
            "        required: true\n" +
            "- 未声明 airun 签名时，工具退化为单个 args（JSON 字符串）参数，建议补全签名提升准确率\n" +
            "- 调用方式：直接调用 tp_技能名（推荐）；或 callskill 工具（skill=技能名, args=JSON）兜底\n" +
            "- 默认所有通道可用；用户可通过 ai/setthirdpartycode off 收紧为仅 execs/本地",
            "builtin", "general");

        // === agentskills.io 技能包（data/skillpackages 目录）===
        bank.addBuiltinSkill("agentskills.io 技能包使用", "general", "使用 data/skillpackages/ 目录下的 agentskills.io 标准技能包",
            "agentskills.io 技能包是符合标准的多文件技能包，位于 data/skillpackages/ 目录，每个技能包一个目录（含 SKILL.md + references/*.md + 可选 scripts/）。\n\n" +
            "## 加载机制\n" +
            "- 技能包目录放入 data/skillpackages/ 即自动加载（含 zip 自动解压导入）\n" +
            "- SKILL.md 首部 front matter 声明 name/description，正文是完整用法说明\n" +
            "- references/*.md 是补充资料，用 skillinfo 工具查询技能详情时一并读取\n\n" +
            "## 使用方式（关键）\n" +
            "- 技能包的 SKILL.md / references 里写的命令行（如 curl、jq、python 等）属于 shell 命令\n" +
            "- 执行这些命令行时，用 `sys` 工具（执行系统命令）直接运行即可，无需专门工具\n" +
            "- 系统已内置 curl；其它外部程序（如 jq 等）若缺失，启动时会红字提示\n\n" +
            "## 依赖提示\n" +
            "- 技能包所需的外部程序（curl/jq/python 等）会在启动时自动探测\n" +
            "- 缺失的依赖会以红字统一提示用户安装，缺什么装什么",
            "builtin", "general");

        // === 捐赠记录 ===
        bank.addBuiltinSkill("捐赠记录", "admin", "捐赠管理三接口：recorddonation 记录捐赠、resetdonation 重置某人名单、donationlist 查排行榜",
            "捐赠名单管理有三种指令，对应三个 FC 工具：\n\n" +
            "## 1. 记录捐赠（recorddonation，仅主人）\n" +
            "- 主人说「XXX 捐赠了 XXX 元」时，调用 `recorddonation` 追加一条此人的捐赠记录并加分\n" +
            "- `amount`（必填）：捐赠金额（元），1 元 = 1 好感度\n" +
            "- `donor`（可选）：捐赠者 QQ 号或昵称；留空则视为主人自己捐赠\n" +
            "- `note`（可选）：备注说明\n\n" +
            "## 2. 重置捐赠名单（resetdonation，仅主人）\n" +
            "- 主人说「重置/清空 XXX 的捐赠名单」时，调用 `resetdonation` 移除此人全部捐赠记录\n" +
            "- `target`（必填）：目标用户 QQ 号或昵称\n\n" +
            "## 3. 查询捐赠排行榜（donationlist，任何人）\n" +
            "- 任何人询问「捐赠名单 / 谁捐得最多 / 我捐了多少」时，调用 `donationlist`\n" +
            "- 返回：全部人员捐赠总额 Top10 + 触发者自己的捐赠总额\n\n" +
            "## 示例\n" +
            "- 「张三捐了 100 块」→ recorddonation amount=100 donor=张三\n" +
            "- 「我捐了 50」→ recorddonation amount=50（donor 留空）\n" +
            "- 「清空张三的捐赠」→ resetdonation target=张三\n" +
            "- 「捐赠排行榜」→ donationlist",
            "builtin", "general");
    }
}
