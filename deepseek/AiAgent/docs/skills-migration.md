# 三方技能：一技能一文件夹（md 文档 + Java 源码）

> V4.0 起，三方技能的形态是 **`data/skills/` 下的「一个技能一个文件夹」**：
> 文件夹里既有 `<技能名>.md`（元数据 + 说明），也有 `*.java`（实现）。
> **代码只支持 Java**（js/nodejs/python 已移除）；md 里 `<java start>…<java end>`
> 式内嵌代码段**已彻底废弃**（加载期会红字点名提示迁移）。

## 一、目录长什么样

```
data/skills/
  ├─ 三方技能库管理.md          ← 纯文档技能：平铺在根目录，只有文档、没有代码
  ├─ 消息拆分发送.md            ← 纯文档技能（不注册为 FC 工具，只作为知识进索引）
  ├─ 时间查询/                  ← 工具技能：文件夹 = 一个技能
  │    ├─ 时间查询.md           ← front matter 元数据 + 自然语言说明
  │    └─ AirunSkill.java       ← Java 源码
  └─ 快速笔记搜索/
       ├─ 快速笔记搜索.md
       ├─ AirunSkill.java
       └─ NoteHelper.java       ← 可以有多个 .java，辅助类放同目录一起编译
```

- **纯文档技能**：`data/skills/<名>.md`
- **工具技能**：`data/skills/<名>/<名>.md` + `<名>/*.java`
- **文件夹名 = md 文件名 = front matter 的 `name`**，三者必须完全一致，否则拒绝加载并点名

把项目里 `skills/` 的内容整体拷进插件数据目录即可（`data/sair.aiagent.AiAgentActivity/skills/`），
目录被 `WatchService` 监听 —— **根目录和每个技能文件夹都在监听范围内**，
所以改 md、改 `.java`、加辅助类都会自动重载（防抖 500ms）。

## 二、`<技能名>.md` 的 front matter

```yaml
---
name: 时间查询                     # 必须 = 文件夹名 = 文件名
description: 查询当前日期和时间      # 一行描述，进工具索引
channels: execq,execs,console      # ★ 该能力可走的通道
permission: ANY                    # ★ 调用所需权限
tool: time                         # 可选，顶替同名内置 FC 工具（沿用原工具名）
entry: AirunSkill                  # 可选，多个类都有 airun 时指定入口类
returns: 当前时间字符串             # 可选，写进工具描述，告诉模型结果怎么用
context: true                      # 可选，airun 第二入参收到只读上下文
timeout: 20                        # 可选，单次调用超时秒数（5~300，默认 30）
state: per_call                    # 可选，per_call（默认，每次新实例）/ shared（复用实例）
airun:
  description: 查询指定城市天气       # 展示给模型的工具说明
  params:
    city:
      type: string                 # string/integer/number/boolean/array/object
      description: 城市名
      required: true
      default: 北京                 # 可选，模型没填时框架补齐
      enum: [晴, 雨, 雪]            # 可选，写进 JSON Schema 限定取值
      example: 上海                 # 可选，示例（拼进参数描述，模型填对率更高）
    tags:
      type: array
      items: string                # 数组元素类型（没有 items 的数组在 JSON Schema 里非法）
      items_description: 标签名     # 可选，元素说明
---
```

### channels —— 通道

| 值 | 含义 |
|---|---|
| `execq` | QQ 普通消息（受限工具集） |
| `execs` | QQ 主人用 `execs:` 唤出的全权限模式 |
| `console` | SFW 控制台（只有本地能力，没有 NapCat API） |

未声明 = 三个通道全可见；也可以写 `all` 或 `*`。

### permission —— 权限

| 值 | 含义 |
|---|---|
| `ANY` | 无门槛 |
| `AFFECTION:N` | 好感度 ≥N，或主人（未声明时的默认位是 `AFFECTION:300`） |
| `MEDIA` | 主人，或好感度 >600 |
| `GROUP_MASTER` | 群内主人 |
| `MASTER` / `ROOT` | 仅主人（ROOT = 无条件放行所有闸门） |

对**纯文档技能**来说 `permission` 是说明性的；对**工具技能**（有 `.java`）是**真正强制**的，
由 `checkPermission()` 在调用前拦截。

## 三、Java 代码怎么写

```java
import java.util.*;

public class AirunSkill {
    // 声明了 context: true → 框架按 airun(Map, Map) 调用
    public Map<String, Object> airun(Map<String, Object> args, Map<String, Object> ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("who", args.get("who"));
        out.put("channel", ctx == null ? "unknown" : ctx.get("channel"));
        return out;   // 返回 Map/List/数组会自动序列化成 JSON
    }
}
```

### 入口方法

- 入口方法固定叫 **`airun`**，类里没有它就永远不会被注册成 FC 工具（加载期红字点名）。
- 可选签名（按此顺序探测，只写一个就够）：

  ```
  airun(Map 入参, Map 上下文) → airun(Map, String) → airun(String, Map) → airun(String, String)
  → airun(Map) → airun(String) → airun()
  ```

- 写了双参 `airun` 就自动拿到上下文（不必再声明 `context: true`）。
- **多个类都有 `airun`** → 必须在 md 里用 `entry: <类名>` 指定入口（否则报错并要求指定）。
- 入口类需要 `public` 无参构造器（默认就有，除非你显式写了别的构造器）。
- 返回 `Map`/`List`/数组会自动序列化为 JSON；返回空字符串/null 视为「执行成功但无输出」。

### 多文件 / 辅助类

同一个技能文件夹里的**所有 `.java` 会一起编译**（编译产物只存在于内存，不落盘）：

- 顶层辅助类、静态内部类都能正常用（先前的「只保留最后一个 class 字节」实现会让它们静默失效）；
- 不同技能文件夹之间互不可见 —— 要复用代码就复制一份，或在 md 里说明；
- 文件名建议与类名一致（不一致只会提示，不影响加载；但 `public` 类必须与文件名同名，否则 javac 报错）。

### 上下文（只读投影）

`context: true`（或写了双参 airun）时，第二个参数收到：

| 字段 | 含义 |
|---|---|
| `channel` | `execq` / `execs` / `console` |
| `is_group` / `group_id` / `user_id` / `user_name` | 来源群与发送者 |
| `is_master` | 是否主人 |
| `affection` | 好感度 |
| `self_id` | Bot 自己的 QQ 号 |
| `skill_name` / `source_file` / `data_dir` | 当前技能名、md 路径、插件数据目录 |
| `now` / `call_id` | 毫秒时间戳 / 本次调用 ID |

### 能摸到什么（能力边界）

编译/运行 classpath **包含 ai.jar 自身**，所以技能能直接调插件类：

| 可达 | 说明 |
|---|---|
| `PersistenceManager.getInstance()` | 笔记 / 记忆 / 纠错 / 偏好 / 日程等 DB 类操作 |
| `SkillBank.getInstance()` | 技能检索、skillinfo |
| 任意 `sair.*` 类 | 只要不需要「请求上下文的可写句柄」 |
| 只读上下文（`context: true`） | 群号 / QQ 号 / 是否主人 / 好感度 / 通道 / 数据目录 |

| 不可达（必须留在 Java 内置工具里） | 原因 |
|---|---|
| `napcatApi`（以 Bot 身份发消息、群管、媒体上传） | 这是**可写句柄**：交给一个技能文件等于把「在任意群以 Bot 身份说话」的能力外包出去 |
| `unifiedMemory` / `pendingRequestPool` / `emotionManager` | 同上，可写状态句柄，刻意不注入 |

> 只给数据、不给句柄是**有意为之**。`SkillContext` 的类注释写明了这条边界。

## 四、加载期会点名的问题

`thirdskill validate`（execs 通道）会重扫目录 + 重新编译 + 逐条点名：

| 情况 | 提示 |
|---|---|
| 文件夹名 / md 名 / `name` 三者不一致 | ✗ 点名「必须叫 X.md」 |
| 文件夹里有 `.java` 但没有同名 md | ✗ 不注册（缺工具描述与参数声明） |
| 文件夹里有名字不对的 md | ✗ 直接点出「文件夹里的 md 叫 A，必须叫 B.md」 |
| 编译失败 / 没有 `airun` 方法 / 多个 airun 未声明 `entry:` | ✗ 记在注册表，模型调用时直接收到该错误（不再白烧一轮往返） |
| 遗留 `<java start>` 内嵌标签 | ✗ 该写法已废弃、不会被编译，并给出迁移方式 |
| 放了 `.js`/`.py`/`.nodejs` | ✗ 已不再支持，请改写为 `.java` |
| 放了 `.class`/`.jar` 等二进制 | ✗ **绝不会被加载**（技能目录不是 classpath） |

## 五、编译与重载

- md 一被加载就**编译并注册**（内容哈希比对：没变复用、变了新替旧、删了摘除），
  调用期不再动态编译。
- 改 md 的 `entry:` 也会触发重新判定（指纹覆盖 entry 声明）。
- 技能文件夹本身在监听范围内 → 改 `.java`、加辅助类、删文件都会自动重载。
- 技能里的 `println`/`print` 会被捕获并附在工具结果里（上限 4000 字符）；
  `airun` 抛异常会回显技能内栈帧（最多 5 帧）。

## 六、从旧写法迁移

| 旧形态 | 新形态 |
|---|---|
| `data/skills/时间查询.md`（内含 `<java start>…<java end>`） | `data/skills/时间查询/时间查询.md` + `data/skills/时间查询/AirunSkill.java` |
| 内嵌 `<js start>` / `<nodejs start>` / `<python start>` | 改写为 Java（或留在纯文档技能里当说明，不会被编译） |
| 纯文档 `.md`（无代码） | **不用动**，继续平铺在根目录 |

迁移三步：① 建同名文件夹 ② 把 md 移进去改成同名 ③ 把代码段内容存成 `AirunSkill.java`。
`thirdskill validate` 会把还没迁移的文件逐条列出来。

## 七、哪些工具的实现已经在技能里（V4.0 起）

**这 20 个工具的 Java 实现已经被删除**，只剩技能文件夹里的源码 —— 删掉文件夹，工具就消失：

| 工具名 | 技能文件夹 | 通道 | 依赖（都在 Java，技能只调用） |
|---|---|---|---|
| `readfile` / `readdir` / `findfile` | `文件读取/` `目录列举/` `文件查找/` | 三通道 | `FileUtils`、确认闸门、execq 路径规则 |
| `web` / `search` | `网页抓取/` `联网搜索/` | 三通道 | `WebFetcher`、`SearchTool`、`NetGuard`、确认闸门 |
| `download` | `文件下载/` | console,execs | `NetGuard`、`FormatUtil`、`MemoryManager`、确认闸门 |
| `note` / `searchnote` | `知识笔记/` `快速笔记搜索/` | 三通道 | `PersistenceManager` |
| `correct` / `setimageremark` | `纠错记录/` `图片注释/` | 三通道 | `PersistenceManager`、`ImageDownloader` |
| `weather` / `balance` | `天气查询/` `余额查询/` | 三通道 | `WeatherTool`、`DeepSeekClient` |
| `schedule` | `定时任务/` | console,execs | `CronScheduler` |
| `editprompt` / `settrigger` | `系统提示词/` `触发词设置/` | console,execs / 三通道 | `AiConfig`（`settrigger` 仍需主人） |
| `batchrename` / `batchconvert` | `批量改名/` `批量转格式/` | console,execs | 纯 JDK |
| `time` / `skillinfo` / `thirdskill` | `时间查询/` `技能说明书查询/` `三方技能库管理工具/` | 三通道 / console,execs | `SkillBank` |

### 安全边界：剥业务逻辑，不剥判定

| 留在 Java（技能只能调用） | 为什么 |
|---|---|
| `ConfirmationGate.confirm()` | 控制台"高危操作先问一句"的闸门。给技能一个 `setBypassConfirm(true)` 的能力等于取消确认；技能走 `confirm()`，与 `ai/yes`、execs 绕过共用同一份状态 |
| `NetGuard.isInternalHost()` | 内网地址拦截。`download` 每一跳重定向都要重新判一次（防 302 绕到内网）；这段代码若跟着技能走，改一行 `return false` 就能让 AI 打内网 |
| `HarnessConfig` 权限矩阵 | 内置工具名的权限**以矩阵为准**，技能声明的 `permission` 不能放宽它 |
| `FileUtils` / QQ 路径规则 | execq 通道"必须给明确路径"的语义在技能里复刻，规则本身仍在 Java |

### 部署：技能彻底外置（不进 jar）

技能文件**不打包进 jar**，请自行把本仓库 `skills/` 目录的内容放到插件数据目录：

```
项目 skills/                              →   C:\Sair\SairFrameWork\data\sair.aiagent.AiAgentActivity\skills\
  ├─ 三方技能库管理.md                                  ├─ 三方技能库管理.md
  └─ 时间查询/                                          └─ 时间查询/
       ├─ 时间查询.md                                          ├─ 时间查询.md
       └─ AirunSkill.java                                      └─ AirunSkill.java
```

目录被 `WatchService` 监听（含每个技能文件夹），**放下即热加载，不用重启**。

**没放会怎样**：20 个已剥离工具（`readfile` / `web` / `download` / `note` / `weather` …）的实现只在技能里，
缺技能就真的没有这些工具。插件启动时会明确点名缺哪些并给出拷贝指引 —— 不会静默消失：

```
[第三方技能] ⚠ 有 20 个工具的实现已剥离到技能，但 data/skills 里没有对应技能，这些工具当前不可用：
  readfile, readdir, findfile, web, search, download, note, searchnote, correct, balance, weather,
  schedule, editprompt, settrigger, batchrename, batchconvert, setimageremark, time, skillinfo, thirdskill
  处理：把项目 skills/ 目录的内容整体拷进 data/skills/
```

`thirdskill validate` 也会核对这张表，随时可查现状（只剩一半技能时，只报缺的那几个）。

### 自己动手剥离一个工具

1. 建 `data/skills/<工具说明名>/<同名>.md`，front matter 写 `tool: <原工具名>`、`channels`、`permission`，
   `airun.params` 里把参数名与原来<b>一字不差</b>地声明（模型看到的 schema 就来自这里）；
2. 把实现写成 `AirunSkill.java`（可以有多个类；入口方法叫 `airun`）；
3. 删掉 Java 侧的注册、case 分支与实现方法；
4. `thirdskill validate` 体检，再发一条消息试调用。

> 注意：如果根目录还留着同名的纯文档 `md`（老的工具说明书就是它），**文件夹版本优先、散文件会被忽略并点名** ——
> 建议把说明书内容并进文件夹里的 md（`天气查询`/`文件下载` 就是这么处理的）。

## 八、接口约定：统一的是「协议」，不是「参数」

> 这一节解释两个常见疑问：①每个技能的 `airun` 参数都不一样，框架怎么找到、怎么调用？
> ②20 个技能的入口类都叫 `AirunSkill`，为什么不冲突？

### 8.1 入口协议（框架侧，统一且固定）

框架**不认识"参数名"**，它只认这一套约定：

| 维度 | 约定 |
|---|---|
| 入口方法名 | 永远叫 `airun`（不叫 run、不叫 execute） |
| 签名形状 | 按固定顺序探测：`airun(Map,Map)` → `airun(Map,String)` → `airun(String,Map)` → `airun(String,String)` → `airun(Map)` → `airun(String)` → `airun()` |
| 怎么传参 | 模型的工具调用 → JSON → 按 md 声明归一化（`"3"`→`3`、单值→单元素数组、对象字符串→对象、`default` 补齐）→ **一个 Map**（`String` 签名则给原始 JSON 文本） |
| 怎么传上下文 | 只读投影（channel / user_id / group_id / is_master / affection / data_dir / now …）作为第 2 个参数；写了双参 `airun` 就自动给，或显式声明 `context: true` |
| 返回值 | `Map`/`List`/数组 → 自动序列化为 JSON；其它 → `toString()`；空 → 「执行成功但无输出」 |
| 入口类 | 有 `airun(…)` 的那个类；若一个技能里**多个类都有** `airun`，用 front matter `entry: <类名>` 指定 |
| 工具名 | 来自 md 的 `name:`（→ `tp_<规范化名>`）或 `tool:`（顶替同名内置工具） |

所以「每个 airun 参数都不一样」**不影响被找到**：名字由 `airun` 这一个约定固定，参数永远是"一个 Map"，
不存在"按参数名匹配方法"。实测（20 个发布技能逐个真实调用）：

```
调用成功 20/20；类名集合 = [AirunSkill]
distinct Class 对象 = 20   distinct ClassLoader = 20
   文件读取 → Class@1da2cb77 loader@3f6b0be5
   目录列举 → Class@5745ca0e loader@2de56eb2
   文件查找 → Class@295cf707 loader@748741cb
   网页抓取 → Class@70f02c32 loader@b4711e2
```

同一批技能里签名实际有 4 种形状（`airun(Map,Map)` / `airun(Map)` / `airun(String)` / `airun()`），
20/20 全部被正确找到并执行出各自的结果。

### 8.2 每个技能的参数为什么必须不一样

`airun.params` **不是 Java 方法签名**，而是**给模型看的 JSON Schema**（也就是 Function Calling 里
每个 tool 自带的 `parameters`）：

```yaml
readfile → path / offset / limit        weather → city        note → content
```

它同时驱动四件事：

1. **工具 schema** —— 模型看到的参数名、类型、必填与否；
2. **类型归一化** —— `integer` 拿到 `Long`、`array` 拿到 `List`、`"true"` → `true`；
3. **缺省补齐** —— 模型没填的 `default` 由框架补上；
4. **取值约束** —— `enum` 限定取值、`items`/`items_description` 描述数组元素。

如果把入参"统一"成一个 `args` 字符串，代价是：模型得自己拼 JSON（准确率明显下降）、
schema 级校验与默认值/枚举/示例全部丢失、日志与审计也没法按参数名定位。
这与 Function Calling 的通行做法（每个 tool 各自带 parameters）正好相反。

**结论**：协议统一（8.1），schema 逐工具（8.2）。这两件事不矛盾 ——
就像函数调用的"调用约定"统一，而每个函数的形参各不相同。

### 8.3 类名相同为什么不冲突：每个技能一个 ClassLoader

技能源码不是编译进插件的，而是**运行期按技能文件夹单独编译**：

```
技能文件夹/*.java  →  DynamicCodeEngine.compileAll()  →  Map<类名, 字节码>
                                                       ↓
                              MultiClassLoader（每个技能、每次编译一个）
                                                       ↓
                          defineClass → 该技能专属的 Class 对象
```

- **父加载器 = 插件加载器**：所以技能能直接调 `sair.aiagent.core.PersistenceManager` 这类插件类；
- **self-first（自身优先）**：类名在自己的字节码表里就先自己 `defineClass`，**不问父加载器**。
  这一条是必须的 —— 父优先时，插件 classpath 上只要出现同名类（例如构建脚本把示例源码也编译了），
  技能会**静默跑成别人的实现**（实测踩过：调 `readfile` 返回了天气演示的 JSON）；
- **每次编译换新 loader**：改 `.java` → 重新编译 → 新 loader + 新 Class（旧的一旦没人引用即可回收），
  所以热重载不会残留旧版本类。

三个推论：

| 推论 | 说明 |
|---|---|
| 同名类互不干扰 | 20 个技能都声明 `public class AirunSkill` 也能各跑各的（20 个 loader 各自定义一份） |
| 入口类不靠类名找 | 由「哪个类有 `airun`」判定（多个则要 `entry:`），所以类名可以随便叫、重复也无妨 |
| **跨技能不能互相引用** | 两个技能在各自命名空间里；要复用代码，抽到插件的 Java 类里（如 `util/NetGuard`、`util/FormatUtil`），或各留一份 |

### 8.4 构建时为什么还要排除 `skills/`

这两件事解决的是不同问题，不是二选一：

| 问题 | 解法 |
|---|---|
| **运行期**同名类冲突 | ClassLoader 隔离（8.3）—— 已实现 |
| **构建期**把技能源码当插件代码编译 | 排除。`_build_ps.ps1` 是"编译工作区下所有 `.java`"，不排除就会 `javac: 重复: AirunSkill` 直接编译失败，而且会把技能源码烧进插件 jar |

技能源码是**运行时资产**（要放到 `data/skills/`、由用户自行拷贝），不是插件本体的一部分 ——
所以构建排除与"技能不进 jar"是同一件事的两面。

### 8.5 什么时候需要 `entry:`

只有当**一个技能文件夹里有多个类都写了 `airun`** 时才需要：

```yaml
entry: ToolBox        # 指定入口类
```

单类技能（绝大多数）不用写；框架自动判定。类文件建议与类名一致
（`public` 类不同名会被 javac 拒绝，非 public 类不同名只会提示）。
