package sair.aiagent.core;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import sair.FCM;
import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ThirdPartySkill;
import sair.aiagent.util.EdtUtils;

/**
 * 三方技能库 —— 基于 {@code data/skills} 目录单文件 .md 的内存镜像 + 文件监听。
 * <p>
 * 与内置技能库（SQLite）物理隔离：技能以单文件 .md 形式存放，增删改直接针对文件系统。
 * WatchService 监听目录变化，新增→加载、删除→移除、修改→重载（防抖合并）。
 * </p>
 * <p>
 * 技能形态只有一种：<b>单文件 .md</b>。多文件技能包（agentskills.io 标准、含 SKILL.md 的
 * 目录/zip，存放于 {@code data/skillpackages}）自 V3.14 起已整体移除，本类不再与任何
 * 技能包存储协作。
 * </p>
 */
public class ThirdPartySkillStore {

    /** 三方技能数量上限（内置工具文档已整体迁入 .md，单文件数量会到 80+，故放宽到 500）。 */
    public static final int MAX_SKILLS = 500;

    /**
     * 实现已<b>完全剥离</b>到技能的 FC 工具名（Java 侧没有实现，只有技能提供）。
     *
     * <p>这些工具能不能用完全取决于 {@code data/skills/} 里有没有对应技能。技能是<b>外置资产</b>
     * （不随 jar 发布、自行拷贝），所以启动时会核对这张表：缺哪个就明确点名，
     * 而不是让工具静默消失 —— 静默消失会让模型以为能调、用户以为坏了，两边都查不出原因。</p>
     */
    public static final String[] PEELED_TOOL_NAMES = {
            "readfile", "readdir", "findfile", "web", "search", "download",
            "note", "searchnote", "correct", "balance", "weather", "schedule",
            "editprompt", "settrigger", "batchrename", "batchconvert", "setimageremark",
            "time", "skillinfo", "thirdskill"
    };

    /** 核对「已剥离工具」是否都由技能提供；返回缺失的工具名（空 = 全部就位）。 */
    public List<String> findMissingPeeledTools() {
        List<String> missing = new ArrayList<>();
        for (String tool : PEELED_TOOL_NAMES) {
            if (getByProvidedToolName(tool) == null) missing.add(tool);
        }
        return missing;
    }

    /**
     * 启动期提示：缺技能时把「缺哪些、去哪拿、放哪里」讲清楚。
     *
     * @return 需要打印的提示文本；一切正常时返回 null
     */
    public String describeMissingPeeledTools() {
        List<String> missing = findMissingPeeledTools();
        if (missing.isEmpty()) return null;
        return "[第三方技能] ⚠ 有 " + missing.size()
                + " 个工具的实现已剥离到技能，但 data/skills 里没有对应技能，这些工具当前不可用：\n  "
                + String.join(", ", missing)
                + "\n  处理：把项目 skills/ 目录的内容整体拷进 data/skills/"
                + "（纯文档技能 = <名>.md；工具技能 = <名>/ 文件夹，内含 <名>.md 与 *.java）"
                + "\n  查看现状：thirdskill validate；目录被监听，拷进去即热加载，无需重启";
    }

    /** 单个 .md 文件大小上限（字节）。三方技能是「按需注入」的文档，8KB 对正常说明书过小（易被静默拒绝），放宽到 64KB。 */
    public static final long MAX_FILE_SIZE = 64 * 1024;

    private final Map<String, ThirdPartySkill> skills = new ConcurrentHashMap<>();

    /** 规范化工具名 → 技能名（注册与反查共用同一张表，保证一致）。 */
    private final Map<String, String> toolNames = new ConcurrentHashMap<>();
    /** 同一张表的反向视图。 */
    private final Map<String, String> toolNameToSkill = new ConcurrentHashMap<>();

    private volatile File skillsDir;
    private volatile WatchService watchService;
    private volatile Thread watchThread;
    private volatile boolean running = false;

    /** 已注册监听的 WatchKey → 目录 映射（单文件技能仅注册根目录）。 */
    private final Map<WatchKey, Path> watchKeyMap = new ConcurrentHashMap<>();
    /** 上一次重扫后的技能名集合（用于 diff 输出加载/移除日志）。 */
    private volatile Set<String> lastNames = new LinkedHashSet<>();
    /** 上一次环境检测状态（node/python 是否就绪），无变化则不重复提示。 */
    private volatile Map<String, Boolean> lastCodeEnvStatus = new LinkedHashMap<>();

    /** 初始化：确保目录存在 → 全量扫描 → 启动文件监听。 */
    public synchronized void init(File dataDir) {
        File dir = new File(dataDir, "skills");
        if (!dir.exists()) dir.mkdirs();
        this.skillsDir = dir;
        startWatch();   // 先启动监听服务与守护线程
        reloadAll();    // 全量扫描 + 注册根目录监听
        AiAgentActivity.debugLog("[ThirdPartySkill] 三方技能库已初始化: " + dir.getAbsolutePath()
                + " (" + skills.size() + " 个技能)");
    }

    /** 关闭：停止监听线程并释放 WatchService。 */
    public synchronized void shutdown() {
        running = false;
        if (watchThread != null) {
            watchThread.interrupt();
            watchThread = null;
        }
        watchKeyMap.clear();
        if (watchService != null) {
            try { watchService.close(); } catch (Exception ignored) {}
            watchService = null;
        }
    }

    // ==================== 查询 ====================

    public ThirdPartySkill get(String name) {
        return name == null ? null : skills.get(name);
    }

    /**
     * 按「规范化后的工具名」反查技能。
     * <p>工具名是 {@link #sanitizeToolName(String)} 的结果，与技能原名可能不同（中文名会被转写并
     * 追加哈希后缀），所以 AI 回传 {@code tp_<安全名>} 时必须能反查到原技能。
     * 注册表在 {@link #reloadAll()} 里一次构建，保证「注册时用的名字」与「反查时用的名字」一致。</p>
     */
    public ThirdPartySkill getBySanitizedToolName(String toolName) {
        if (toolName == null || toolName.isEmpty()) return null;
        ThirdPartySkill exact = skills.get(toolName);
        if (exact != null) return exact;
        String name = toolNameToSkill.get(toolName);
        return name == null ? null : skills.get(name);
    }

    /**
     * 该技能实际注册的 FC 工具名（不含 {@code tp_} 前缀）。
     * <p>声明了 {@code tool:}（顶替内置工具）时直接用声明名，不走规范化 —— 保持工具名不变
     * 才能让「实现从 Java 剥离到 .md」对模型与既有提示词完全透明。</p>
     */
    public String getRegisteredToolName(ThirdPartySkill s) {
        if (s == null) return null;
        if (s.getDeclaredToolName() != null && !s.getDeclaredToolName().isEmpty()) {
            return s.getDeclaredToolName();
        }
        String n = toolNameToSkill.get(sanitizeToolName(s.getName()));
        if (n != null && n.equals(s.getName())) return sanitizeToolName(s.getName());
        // 名字已被占用而加了后缀的情况：从注册表反查
        for (Map.Entry<String, String> e : toolNameToSkill.entrySet()) {
            if (e.getValue().equals(s.getName())) return e.getKey();
        }
        return sanitizeToolName(s.getName());
    }

    /** 按「技能提供的 FC 工具名」反查技能（含 {@code tool:} 声明的顶替名）。 */
    public ThirdPartySkill getByProvidedToolName(String toolName) {
        if (toolName == null || toolName.isEmpty()) return null;
        for (ThirdPartySkill s : skills.values()) {
            String declared = s.getDeclaredToolName();
            if (declared != null && declared.equals(toolName)) return s;
        }
        return null;
    }

    /** 构建「工具名 → 技能名」注册表，保证全局唯一（名字撞车时按名字序追加数字后缀）。 */
    private void buildToolNameRegistry() {
        Map<String, String> fresh = new ConcurrentHashMap<>();
        Set<String> used = new LinkedHashSet<>();
        List<String> names = new ArrayList<>(skills.keySet());
        java.util.Collections.sort(names);
        for (String name : names) {
            String base = sanitizeToolName(name);
            String candidate = base;
            int n = 2;
            while (used.contains(candidate)) {
                String suffix = "-" + n++;
                candidate = (base.length() + suffix.length() > 61)
                        ? base.substring(0, 61 - suffix.length()) + suffix
                        : base + suffix;
            }
            used.add(candidate);
            fresh.put(candidate, name);
        }
        toolNames.clear();
        toolNames.putAll(fresh);
        toolNameToSkill.clear();
        toolNameToSkill.putAll(fresh);
    }

    public Collection<ThirdPartySkill> getAll() {
        return skills.values();
    }

    public boolean isEmpty() {
        return skills.isEmpty();
    }

    public int size() {
        return skills.size();
    }

    /** 列出所有三方技能（名称 + 描述）。 */
    public String list() {
        if (skills.isEmpty()) return "三方技能库为空（data/skills 目录下无 .md 文件）";
        List<ThirdPartySkill> sorted = new ArrayList<>(skills.values());
        sorted.sort(Comparator.comparing(ThirdPartySkill::getName));
        StringBuilder sb = new StringBuilder("三方技能库（").append(sorted.size()).append(" 个）:\n");
        for (ThirdPartySkill s : sorted) {
            sb.append("- ").append(s.getName()).append(": ").append(s.getDescription()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 增删（管理，仅 execs / QQ execs 调用） ====================

    /** 新增三方技能：写入 .md 文件 + 更新内存。 */
    public synchronized String add(String name, String description, String content) {
        if (skillsDir == null) return "[thirdskill] 三方技能库未初始化";
        String safeName = sanitizeName(name);
        if (safeName == null) return "[thirdskill] 技能名不合法（禁止 / \\ .. : * ? \" < > | 等字符）";
        if (skills.size() >= MAX_SKILLS && !skills.containsKey(safeName)) {
            return "[thirdskill] 三方技能数量已达上限 " + MAX_SKILLS;
        }
        if (content == null || content.trim().isEmpty()) return "[thirdskill] 技能内容不能为空";
        // 按最终写入文件的字节数校验（与 parseFile 的 f.length() 一致，避免中文等多字节内容超限后被重扫丢弃）
        byte[] bytes = buildMarkdown(safeName, description, content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_SIZE) return "[thirdskill] 技能内容过大（上限 8KB）";
        File f = new File(skillsDir, safeName + ".md");
        try {
            Files.write(f.toPath(), bytes);
        } catch (Exception e) {
            return "[thirdskill] 写入失败: " + e.toString();
        }
        skills.put(safeName, new ThirdPartySkill(safeName, description, content, f.getAbsolutePath()));
        ToolDispatcher.invalidateToolSetCache();
        return "[thirdskill] 已新增三方技能: " + safeName;
    }

    /** 删除三方技能：删除 .md 文件 + 移除内存。 */
    public synchronized String delete(String name) {
        if (skillsDir == null) return "[thirdskill] 三方技能库未初始化";
        if (name == null || name.trim().isEmpty()) return "[thirdskill] 缺少技能名";
        ThirdPartySkill s = skills.get(name.trim());
        if (s == null) return "[thirdskill] 未找到三方技能: " + name;
        try {
            File f = new File(s.getSourceFile());
            if (f.exists()) f.delete();
            skills.remove(s.getName());
            ToolDispatcher.invalidateToolSetCache();
            return "[thirdskill] 已删除三方技能: " + s.getName();
        } catch (Exception e) {
            return "[thirdskill] 删除失败: " + e.toString();
        }
    }

    // ==================== 文件监听 ====================

    private void startWatch() {
        if (skillsDir == null) return;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            running = true;
            watchThread = ThreadManager.getInstance().newDaemonThread("ThirdPartySkill-Watcher", this::watchLoop);
            watchThread.start();
        } catch (Exception e) {
            AiAgentActivity.debugLog("[ThirdPartySkill] 文件监听启动失败: " + e.toString());
        }
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;
                // 丢弃已被 resyncWatch 取消的无效 key
                if (!key.isValid()) {
                    key.pollEvents();
                    continue;
                }
                key.pollEvents();
                key.reset();
                // 防抖：等待 500ms 让编辑器连续保存事件合并，再 drain 剩余事件
                Thread.sleep(500);
                while ((key = watchService.poll()) != null) {
                    key.pollEvents();
                    key.reset();
                }
                reloadAll();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                AiAgentActivity.debugLog("[ThirdPartySkill] 监听异常: " + e.toString());
            }
        }
    }

    /**
     * 重新同步监听：注册 skillsDir 根目录<b>以及每个技能文件夹</b>。
     * <p>WatchService 不递归 —— 技能文件夹里的 {@code .java} 改动只有在监听该子目录时才会触发重载，
     * 不注册的话「改了 Java 源码但技能还在跑旧版本」。新增/删除文件夹会在下一轮全量扫描后重新同步。</p>
     */
    private void resyncWatch() {
        if (watchService == null || skillsDir == null) return;
        for (WatchKey k : watchKeyMap.keySet()) {
            k.cancel();
        }
        watchKeyMap.clear();
        registerWatch(skillsDir);
        for (File d : listDirs(skillsDir)) {
            registerWatch(d);
        }
    }

    /** 注册单个目录（失败只记日志，不影响其它目录）。 */
    private void registerWatch(File dir) {
        try {
            WatchKey key = dir.toPath().register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            watchKeyMap.put(key, dir.toPath());
        } catch (Exception e) {
            AiAgentActivity.debugLog("[ThirdPartySkill] 注册监听失败 " + dir.getName() + ": " + e);
        }
    }

    /** 运行时注册表（加载期编译 airun 用；由 AiAgentActivity 注入）。 */
    private volatile Object codeRunner;

    /** 注入代码执行器；注入后立即把所有已加载技能的代码注册进运行时。 */
    public void setCodeRunner(sair.aiagent.core.SkillCodeRunner runner) {
        this.codeRunner = runner;
        registerAllSkillCode();
    }

    /**
     * 把所有含代码段的技能注册进运行时（加载期编译 java 段）。
     * <p>这是「md 加载时就注册掉 airun」的落点：此后调用不再动态编译；
     * 编译不过的技能在加载期就红字点名，并把失败原因记在注册表里，
     * 调用时直接回错误而不是白烧一轮模型往返。</p>
     *
     * @return 注册失败的技能数
     */
    public int registerAllSkillCode() {
        sair.aiagent.core.SkillCodeRunner runner = (sair.aiagent.core.SkillCodeRunner) codeRunner;
        if (runner == null) return 0;
        // 收尾：删掉的技能要从编译注册表里摘掉，否则「已删除的技能」还能被 callskill 调到
        runner.prune(new java.util.HashSet<>(skills.keySet()));
        int failed = 0;
        for (ThirdPartySkill s : skills.values()) {
            if (s == null || !s.hasJavaSource()) continue;
            String err = runner.register(s);
            if (err != null) {
                failed++;
                EdtUtils.println(FCM.Error_Color, "[thirdskill] ✗ " + s.getName()
                        + " 代码不可用（已标记，模型调用时会直接收到该错误）: " + err);
            }
        }
        return failed;
    }

    /**
     * 手动体检：重扫目录 + 重新注册代码，再逐条列出问题。
     * <p>等价于「热重载 + 点名」：确认 md 写得对不对、airun 编译不编译得过、
     * 通道/权限有没有声明、工具名会不会被改名。加载期的告警只在启动时闪一次，
     * 这个子命令让作者改完 md 立刻能自查（不用重启客户端）。</p>
     *
     * @return 人类可读的体检报告
     */
    public synchronized String validate() {
        reloadAll();
        sair.aiagent.core.SkillCodeRunner runner = (sair.aiagent.core.SkillCodeRunner) codeRunner;
        List<ThirdPartySkill> all = new ArrayList<>(skills.values());
        java.util.Collections.sort(all, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        StringBuilder sb = new StringBuilder();
        java.util.List<String> errors = new ArrayList<>();
        java.util.List<String> notes = new ArrayList<>();
        int docOnly = 0, runnable = 0, broken = 0;
        for (ThirdPartySkill s : all) {
            String file = s.getSourceFile() == null ? s.getName() : new File(s.getSourceFile()).getName();
            if (!s.hasJavaSource()) {
                docOnly++;
                continue;   // 纯文档技能：合法形态，只是不注册为工具
            }
            String reg = (runner == null) ? null : runner.describeRegistrationProblem(s.getName());
            if (reg != null) {
                broken++;
                errors.add("✗ " + file + " → 代码不可用: " + singleLine(reg));
                continue;
            }
            if (!s.mentionsAirun()) {
                broken++;
                errors.add("✗ " + file + " → .java 里没有 airun 方法（入口函数必须叫 airun）");
                continue;
            }
            runnable++;
            // 声明了 tool: 的技能用内置工具名注册，规范化名根本没被用到 → 只对 tp_* 技能提示
            if (!s.replacesBuiltinTool() && !sanitizeToolName(s.getName()).equals(s.getName())) {
                notes.add("⚠ " + file + " → 技能名含非法工具名字符，注册名规范化为 "
                        + sair.aiagent.util.ToolNames.TP_PREFIX + sanitizeToolName(s.getName()));
            }
            if (s.getDeclaredPermission() == null) {
                notes.add("• " + file + " → 未声明 permission，按默认 "
                        + sair.aiagent.core.HarnessConfig.LEVEL_TP_DEFAULT + " 放行");
            }
            if (s.getDeclaredChannels() == null || s.getDeclaredChannels().isEmpty()) {
                notes.add("• " + file + " → 未声明 channels，三个通道都可见");
            }
            if (s.replacesBuiltinTool()) {
                notes.add("• " + file + " → 顶替内置工具 " + s.getDeclaredToolName()
                        + "（同名内置实现已从工具列表剔除）");
            }
        }
        // 目录级结构性问题（配对失败 / 遗留内嵌标签 / 退役语言 / 二进制产物 / 缺 md）
        for (Map.Entry<String, List<String>> e : new java.util.TreeMap<>(loadProblems).entrySet()) {
            for (String p : e.getValue()) {
                errors.add("✗ " + e.getKey() + " → " + p);
            }
        }
        sb.append("三方技能体检: 共 ").append(all.size()).append(" 个（可执行 ").append(runnable)
          .append(" / 纯文档 ").append(docOnly).append(" / 有问题 ").append(broken)
          .append("），错误 ").append(errors.size())
          .append(" 条，提示 ").append(notes.size()).append(" 条");
        if (runner != null) sb.append("；").append(runner.statusSummary());
        // 代码一栏：列出每个工具技能的文件夹、java 文件数与入口类
        StringBuilder code = new StringBuilder();
        for (ThirdPartySkill s : all) {
            if (!s.hasJavaSource()) continue;
            String dirName = (s.getSourceDir() == null) ? s.getName()
                    : new File(s.getSourceDir()).getName();
            code.append("\n  · ").append(dirName).append("/ → ").append(s.getJavaSources().size())
                .append(" 个 .java ").append(s.getJavaSources())
                .append("，入口类 ").append(s.getEntryClass() == null ? "（自动判定）" : s.getEntryClass());
        }
        if (code.length() > 0) sb.append("\n工具技能文件夹：").append(code);
        if (errors.isEmpty() && notes.isEmpty()) {
            sb.append("\n全部技能正常，无需处理");
            return sb.toString();
        }
        final int MAX_LINES = 40;
        int printed = 0;
        for (String e : errors) {
            if (printed++ >= MAX_LINES) { sb.append("\n…（错误过多，已截断）"); break; }
            sb.append("\n").append(e);
        }
        for (String n : notes) {
            if (printed++ >= MAX_LINES) { sb.append("\n…（提示过多，已截断）"); break; }
            sb.append("\n").append(n);
        }
        return sb.toString();
    }

    /**
     * 全量重扫目录，重建内存镜像。
     * <p>识别两种形态（V4.0 起）：</p>
     * <ul>
     *   <li>根目录平铺的 {@code <名>.md} → <b>纯文档技能</b>（无代码）；</li>
     *   <li>子目录 {@code <名>/} 里的 {@code <名>.md} + {@code *.java} → <b>工具技能</b>
     *       （md 与文件夹必须同名，代码只支持 Java）。</li>
     * </ul>
     */
    private synchronized void reloadAll() {
        if (skillsDir == null || !skillsDir.isDirectory()) return;
        loadProblems.clear();   // 每轮全量扫描都重新收集结构性问题
        Map<String, ThirdPartySkill> fresh = new ConcurrentHashMap<>();
        List<File> entries = new ArrayList<>();
        collectSkillFiles(skillsDir, entries, loadProblems);
        java.util.Collections.sort(entries, (a, b) -> a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath()));
        for (File f : entries) {
            // 超限/解析失败都必须留日志：旧实现对超大文件直接静默跳过，
            // 用户放进来的技能「凭空消失」且毫无提示。
            if (f.length() > MAX_FILE_SIZE) {
                String p = "文件超过 " + (MAX_FILE_SIZE / 1024) + "KB 上限（实际 "
                        + (f.length() / 1024) + "KB），已跳过";
                loadProblems.put(f.getName(), java.util.Collections.singletonList(p));
                EdtUtils.println(FCM.Error_Color, "[ThirdPartySkill] ✗ " + f.getName() + " → " + p);
                continue;
            }
            ThirdPartySkill s = parseFile(f);
            if (s == null) continue;   // parseFile 内部已给红字/日志
            if (s.getName().isEmpty()) continue;
            ThirdPartySkill prev = fresh.get(s.getName());
            if (prev != null) {
                // 同名冲突（常见于把旧版放进子目录做备份）：先进先得并明确告警，
                // 否则「谁生效」取决于文件系统顺序，不可复现。
                EdtUtils.println(FCM.Error_Color, "[ThirdPartySkill] ✗ 技能名重复，忽略后者: "
                        + s.getName() + "（已有 " + prev.getSourceFile() + "，本次 " + s.getSourceFile() + "）");
                continue;
            }
            if (fresh.size() >= MAX_SKILLS) {
                AiAgentActivity.debugLog("[ThirdPartySkill] 三方技能数量已达上限 "
                        + MAX_SKILLS + "，忽略: " + f.getName());
                continue;
            }
            fresh.put(s.getName(), s);
        }
        // 有代码文件夹但没有同名 md → 无法注册（没有工具描述/参数声明），点名
        for (File d : listDirs(skillsDir)) {
            File md = new File(d, d.getName() + ".md");
            if (md.isFile()) continue;
            List<File> javaFiles = listJava(d);
            // 文件夹里若有「名字不对的 md」，直接点出来 —— 不然作者只能看到「没有同名 md」，猜不到差在哪
            StringBuilder wrongMd = new StringBuilder();
            File[] inner = d.listFiles();
            if (inner != null) {
                for (File x : inner) {
                    if (x.isFile() && x.getName().toLowerCase().endsWith(".md")
                            && !x.getName().equalsIgnoreCase(d.getName() + ".md")) {
                        if (wrongMd.length() > 0) wrongMd.append("、");
                        wrongMd.append(x.getName());
                    }
                }
            }
            String p;
            if (wrongMd.length() > 0) {
                p = "文件夹里的 md 叫「" + wrongMd + "」，必须叫「" + d.getName() + ".md」"
                  + "（文件夹名 = md 文件名 = front matter 的 name，三者必须一致）→ 不加载";
            } else if (javaFiles.isEmpty()) {
                p = "文件夹里没有 " + d.getName() + ".md（纯文档技能请把 .md 平铺在 skills 根目录；"
                  + "工具技能请在文件夹里同时放 <技能名>.md 与 .java）";
            } else {
                p = "有 " + javaFiles.size() + " 个 .java 但没有同名 " + d.getName()
                  + ".md → 不注册（缺少工具描述与 airun 参数声明）";
            }
            loadProblems.put(d.getName() + "/", java.util.Collections.singletonList(p));
            EdtUtils.println(FCM.Error_Color, "[ThirdPartySkill] ✗ " + d.getName() + "/ → " + p);
        }

        // diff：识别新增/移除的技能，输出日志（让控制台有加载反馈）
        Set<String> newNames = new TreeSet<>(fresh.keySet());
        newNames.removeAll(lastNames);
        Set<String> removedNames = new TreeSet<>(lastNames);
        removedNames.removeAll(fresh.keySet());

        skills.clear();
        skills.putAll(fresh);
        lastNames = new TreeSet<>(fresh.keySet());
        buildToolNameRegistry();
        ToolDispatcher.invalidateToolSetCache();
        // 加载期注册：编译 airun 并登记到运行时（改了内容的技能在此新替旧）
        registerAllSkillCode();

        if (!newNames.isEmpty()) {
            AiAgentActivity.debugLog("[ThirdPartySkill] +" + newNames.size()
                    + " 个技能: " + String.join(", ", newNames));
        }
        if (!removedNames.isEmpty()) {
            AiAgentActivity.debugLog("[ThirdPartySkill] -" + removedNames.size()
                    + " 个技能: " + String.join(", ", removedNames));
        }

        resyncWatch();
    }

    private ThirdPartySkill parseFile(File f) {
        if (f == null || !f.isFile()) return null;
        if (f.length() > MAX_FILE_SIZE) return null;
        try {
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<String> problems = new ArrayList<>();
            ThirdPartySkill s = parseMarkdown(raw, f, skillsDir, problems);
            if (!problems.isEmpty()) {
                loadProblems.put(f.getName(), problems);
                for (String p : problems) {
                    EdtUtils.println(FCM.Error_Color, "[ThirdPartySkill] ✗ " + f.getName() + " → " + p);
                }
            }
            if (s != null) {
                warnOnLoadIssues(s);
            }
            return s;
        } catch (Exception e) {
            EdtUtils.println(FCM.Error_Color, "[ThirdPartySkill] ✗ 读取失败: " + f.getName() + " - " + e);
            return null;
        }
    }

    /** 最近一次全量扫描中每个文件的代码块结构性问题（供 thirdskill validate 复述）。 */
    private final Map<String, List<String>> loadProblems = new ConcurrentHashMap<>();

    /**
     * 加载期给出可诊断的提示（不阻断加载，只把「静默失效」变成「看得见的问题」）。
     */
    private void warnOnLoadIssues(ThirdPartySkill s) {
        // 1) 有 Java 源码但连 airun 这个词都没有 → 永远无法执行，不注册为工具
        String noEntry = javaSourceWithoutAirun(s);
        if (noEntry != null) {
            EdtUtils.println(FCM.Error_Color, "[ThirdPartySkill] ✗ " + noEntry);
        }
        // 2) 工具名规范化提示 —— 只对「真的会注册成 FC 工具」的技能提示；
        //    纯文档技能永远不会成为工具，不需要合法工具名（以前会给几十个文档技能刷一屏红字）。
        String notice = toolNameNotice(s);
        if (notice != null) {
            EdtUtils.println(FCM.EXECTION_pathInfo_Color, "[ThirdPartySkill] " + notice);
        }
    }

    /** 有 Java 但没有 airun 入口 → 返回告警文本；否则 null。 */
    static String javaSourceWithoutAirun(ThirdPartySkill s) {
        if (s == null || !s.hasJavaSource() || s.mentionsAirun()) return null;
        return s.getName() + " 的 .java 里没有 airun 方法，不会注册为 FC 工具"
                + "（避免给 AI 一个必然失败的工具）";
    }

    /**
     * 工具名规范化提示；<b>只有会注册成 FC 工具的技能才需要</b>，否则返回 null。
     *
     * <p>触发条件：有 Java 源码 + 有 airun 入口 + 未声明 {@code tool:}（声明了就用内置工具名，
     * 跟技能名无关）。纯文档技能、纯知识技能一律不提示。</p>
     */
    static String toolNameNotice(ThirdPartySkill s) {
        if (s == null) return null;
        if (!s.hasJavaSource() || !s.mentionsAirun()) return null;   // 不会成为工具
        if (s.replacesBuiltinTool()) return null;                    // 用 tool: 的名字
        String safe = sanitizeToolName(s.getName());
        if (safe.equals(s.getName())) return null;                   // 本来就是合法工具名
        return s.getName() + " 会注册为工具名 tp_" + safe
                + "（技能名含非 ASCII 字符，官方要求 function.name 匹配 ^[a-zA-Z0-9_-]+$，"
                + "已自动规范化以避免整个请求 400；想要可读的工具名可在 front matter 加 tool: <英文名>）";
    }

    /**
     * 收集技能 md：根目录的 .md + 一层子目录里的 &lt;目录名&gt;.md（文件夹形态）。
     *
     * <p><b>同名冲突的裁决</b>：若同时存在散文件 {@code <名>.md} 和文件夹 {@code <名>/<名>.md}
     * （把内置工具剥离成技能时很容易撞上 —— 老的工具说明书就是那个散文件），
     * <b>文件夹版本优先</b>（它含实现，信息更全），散文件忽略并点名。
     * 早先靠路径排序决定谁生效，会让"能用的工具"被一个纯文档散文件悄悄盖掉。</p>
     */
    private static void collectSkillFiles(File root, List<File> out, Map<String, List<String>> problems) {
        File[] files = root.listFiles();
        if (files == null) return;
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        // 先收集文件夹名（文件夹 = 工具技能）
        java.util.Set<String> folderNames = new java.util.HashSet<>();
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) {
                if (new File(f, f.getName() + ".md").isFile()) folderNames.add(f.getName());
            }
        }
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".")) continue;                       // 隐藏文件/目录
            if (f.isFile()) {
                if (!n.toLowerCase().endsWith(".md")) continue;
                String base = n.substring(0, n.length() - 3);
                if (folderNames.contains(base)) {
                    // 文件夹优先：散文件忽略（否则同名技能先进先得，把工具版本挤掉）
                    problems.put(n, java.util.Collections.singletonList(
                            "与文件夹 " + base + "/ 同名，已忽略本散文件（文件夹版本优先）"));
                    continue;
                }
                out.add(f);                                        // 纯文档技能
            } else if (f.isDirectory()) {
                // 文件夹形态：只认 <目录名>.md（同名 = 配对成功）
                File inner = new File(f, n + ".md");
                if (inner.isFile()) out.add(inner);
            }
        }
    }

    /** 列出 skills 根目录下的一层子目录（跳过隐藏目录）。 */
    private static List<File> listDirs(File root) {
        List<File> out = new ArrayList<>();
        File[] files = root.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isDirectory() && !f.getName().startsWith(".")) out.add(f);
        }
        return out;
    }

    /** 列出技能文件夹里的 .java（已排序）。 */
    private static List<File> listJava(File dir) {
        List<File> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.getName().toLowerCase().endsWith(".java") && !f.getName().startsWith(".")) {
                out.add(f);
            }
        }
        java.util.Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** 曾经支持、现在已被移除的语言源码扩展名（加载期点名，避免作者以为还在生效）。 */
    private static final String[] RETIRED_EXT = {".js", ".nodejs", ".py", ".python", ".mjs", ".cjs"};
    /** 绝不加载的二进制产物（安全：防止有人往技能目录里丢编译好的类）。 */
    private static final String[] FORBIDDEN_EXT = {".class", ".jar", ".zip", ".dll", ".so", ".exe"};

    /**
     * 把技能名规范化为合法工具名（委托 {@link sair.aiagent.util.ToolNames}）。
     * <p>官方要求 {@code function.name} 匹配 {@code ^[a-zA-Z0-9_-]+$}，违规会导致<b>整条请求 400</b>；
     * 纯中文名转写后为空，必须追加原名哈希才能保证唯一。细节见 ToolNames 的类注释。</p>
     */
    static String sanitizeToolName(String name) {
        return sair.aiagent.util.ToolNames.sanitize(name);
    }
    /** 6 位稳定哈希（String.hashCode 的规范是固定的，重启后不变）。 */
    private static String shortHash(String s) {
        int h = (s == null) ? 0 : s.hashCode();
        return String.format("%06x", h & 0xFFFFFF);
    }

    // ==================== Markdown 解析 / 构建 ====================

    /** 解析 .md 内容：支持 front matter（name / description / airun 签名），无 front matter 时用文件名兜底。 */
    static ThirdPartySkill parseMarkdown(String raw, File sourceFile) {
        return parseMarkdown(raw, sourceFile, null);
    }

    /** 解析 .md 内容（根目录平铺形态，等价于「无技能文件夹」）。 */
    static ThirdPartySkill parseMarkdown(String raw, File sourceFile, List<String> problemsOut) {
        return parseMarkdown(raw, sourceFile, sourceFile == null ? null : sourceFile.getParentFile(),
                problemsOut);
    }

    /**
     * 解析技能 md（V4.0 文件夹模型）。
     *
     * @param raw          md 全文
     * @param sourceFile   本 md 文件
     * @param skillsRoot   skills 根目录（用于判断「平铺文档技能」还是「文件夹工具技能」；可 null）
     * @param problemsOut  结构性问题回传（配对失败、遗留内嵌标签、退役语言、二进制产物…）
     * @return 解析成功返回技能；配对校验失败返回 null
     */
    static ThirdPartySkill parseMarkdown(String raw, File sourceFile, File skillsRoot,
                                         List<String> problemsOut) {
        String name = null;
        String description = "";
        String content = raw == null ? "" : raw;
        String airunDescription = null;
        List<ThirdPartySkill.AirunParam> airunParams = Collections.emptyList();
        String declaredPermission = null;
        String declaredChannels = null;
        String declaredToolName = null;
        boolean fmContext = false;
        int fmTimeout = 0;
        boolean fmStateShared = false;
        String fmReturnType = null;

        String[] lines = content.split("\n", -1);
        if (lines.length > 0 && "---".equals(lines[0].trim())) {
            StringBuilder fm = new StringBuilder();
            int i = 1;
            boolean closed = false;
            while (i < lines.length) {
                if ("---".equals(lines[i].trim())) { closed = true; break; }
                fm.append(lines[i]).append("\n");
                i++;
            }
            if (closed) {
                name = extractFmField(fm.toString(), "name");
                description = extractFmField(fm.toString(), "description");
                // 顶层元数据：通道 / 权限 / 顶替的工具名（airun.permission 作为权限的等价写法）
                declaredChannels = extractFmField(fm.toString(), "channels");
                declaredToolName = extractFmField(fm.toString(), "tool");
                declaredPermission = extractFmField(fm.toString(), "permission");
                fmContext = parseBool(extractFmField(fm.toString(), "context"));
                fmTimeout = parseIntField(extractFmField(fm.toString(), "timeout"), 0);
                fmStateShared = "shared".equalsIgnoreCase(
                        String.valueOf(extractFmField(fm.toString(), "state")).trim());
                fmReturnType = extractFmField(fm.toString(), "returns");
                AirunParseResult ar = parseAirun(fm.toString());
                airunDescription = ar.description;
                airunParams = ar.params;
                if (declaredPermission == null) declaredPermission = ar.permission;
                if (declaredChannels == null) declaredChannels = ar.channels;
                StringBuilder body = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) {
                    if (body.length() > 0) body.append("\n");
                    body.append(lines[j]);
                }
                content = body.toString();
            }
        }

        // 文件名（去掉 .md 后缀）
        String fname = sourceFile.getName();
        int dot = fname.lastIndexOf('.');
        if (dot > 0) fname = fname.substring(0, dot);

        // 硬性校验：front matter 声明了 name 时，必须与文件名完全一致（否则拒绝加载）
        if (name == null || name.trim().isEmpty()) {
            name = fname; // 无 name 字段：用文件名兜底（兼容旧文件）
        } else if (!name.trim().equals(fname)) {            EdtUtils.println(FCM.Error_Color,
                    "[ThirdPartySkill] ✗ 文件名与 name 字段不一致，拒绝加载: " + sourceFile.getName()
                            + "（name=" + name.trim() + "）");
            return null;
        }

        if (description == null) description = "";
        if (description.trim().isEmpty()) {
            description = content.length() > 80 ? content.substring(0, 80) : content;
        }
        String bodyOnly = content;

        // ==================== 技能文件夹（V4.0 模型）====================
        // 文件夹形态：skills/<名>/<名>.md + *.java（代码只支持 Java）
        String sourceDir = null;
        List<String> javaNames = Collections.emptyList();
        Map<String, String> javaTexts = new LinkedHashMap<>();
        boolean inFolder = skillsRoot != null && sourceFile.getParentFile() != null
                && !sourceFile.getParentFile().equals(skillsRoot);
        if (inFolder) {
            File dir = sourceFile.getParentFile();
            String dirName = dir.getName();
            sourceDir = dir.getAbsolutePath();
            // 配对硬校验一：文件夹名 = md 文件名 = name 字段
            if (!dirName.equals(fname)) {
                if (problemsOut != null) {
                    problemsOut.add("文件夹名与 md 文件名不一致（" + dirName + " / "
                            + sourceFile.getName() + "）→ 不加载");
                }
                return null;
            }
            if (!dirName.equals(name)) {
                if (problemsOut != null) {
                    problemsOut.add("文件夹名与 front matter 的 name 不一致（"
                            + dirName + " / " + name + "）→ 不加载");
                }
                return null;
            }
            // 收集 Java 源码
            List<String> names = new ArrayList<>();
            for (File jf : listJava(dir)) {
                if (jf.length() > MAX_FILE_SIZE) {
                    if (problemsOut != null) {
                        problemsOut.add(jf.getName() + " 超过 " + (MAX_FILE_SIZE / 1024)
                                + "KB，已忽略");
                    }
                    continue;
                }
                try {
                    String src = new String(Files.readAllBytes(jf.toPath()), StandardCharsets.UTF_8);
                    String cls = classDeclaredIn(src);
                    String expect = jf.getName().substring(0, jf.getName().length() - 5);
                    if (cls != null && !cls.equals(expect) && problemsOut != null) {
                        problemsOut.add("⚠ " + jf.getName() + " 里声明的类是 " + cls
                                + "（文件名与类名不一致；public 类会导致编译失败，建议改名为 "
                                + cls + ".java）");
                    }
                    names.add(jf.getName());
                    javaTexts.put(jf.getName(), src);
                } catch (Exception e) {
                    if (problemsOut != null) problemsOut.add(jf.getName() + " 读取失败: " + e);
                }
            }
            javaNames = names;
            // 曾经支持、现在已移除的语言 / 二进制产物 → 点名
            File[] all = dir.listFiles();
            if (all != null && problemsOut != null) {
                for (File x : all) {
                    if (!x.isFile() || x.getName().startsWith(".")) continue;
                    String ln = x.getName().toLowerCase();
                    if (ln.equals(sourceFile.getName().toLowerCase()) || ln.endsWith(".java")) continue;
                    if (endsWithAny(ln, FORBIDDEN_EXT)) {
                        problemsOut.add("✗ " + x.getName() + " 是编译产物/二进制，绝不会被加载"
                                + "（只编译 .java 源码）");
                    } else if (endsWithAny(ln, RETIRED_EXT)) {
                        problemsOut.add("✗ " + x.getName() + " 已不再支持"
                                + "（V4.0 起三方技能只支持 Java，请改写为 .java）");
                    }
                }
            }
        } else {
            // 根目录的平铺 .md：纯文档技能。若正文里还留着内嵌代码段 → 点名提示迁移
            if (problemsOut != null && containsInlineCodeTag(bodyOnly)) {
                problemsOut.add("正文里还有内嵌代码段标签（<java start>…<java end>），"
                        + "该写法已废弃、不会被编译。迁移方式：新建同名文件夹 "
                        + name + "/，把本文件移进去改为 " + name + ".md，"
                        + "代码另存为 " + name + "/AirunSkill.java");
            }
        }
        if (javaNames.isEmpty() && sourceDir != null) {
            // 文件夹里没有 .java：等同于纯文档技能（合法，只是不注册为工具）
            sourceDir = sourceDir;
        }
        if (description.trim().isEmpty()) {
            description = bodyOnly.length() > 80 ? bodyOnly.substring(0, 80) : bodyOnly;
        }
        String entry = extractEntryField(raw);
        return new ThirdPartySkill(name, description, bodyOnly, sourceFile.getAbsolutePath(),
                sourceDir, javaNames, entry, null, javaTexts,
                airunDescription, airunParams, declaredPermission,
                declaredChannels, declaredToolName,
                fmContext, fmTimeout, fmStateShared, fmReturnType);
    }

    /** front matter 顶层 {@code entry:} 声明的入口类名（可空）。 */
    private static String extractEntryField(String raw) {
        if (raw == null || !raw.startsWith("---")) return null;
        int end = raw.indexOf("\n---", 3);
        String fm = (end > 0) ? raw.substring(0, end) : raw;
        return extractFmField(fm, "entry");
    }

    /** 从源码里取第一个类声明名（仅用于「文件名 vs 类名」提示，不参与加载）。 */
    private static String classDeclaredIn(String src) {
        if (src == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+)*class\\s+(\\w+)")
                .matcher(src);
        return m.find() ? m.group(1) : null;
    }

    private static boolean endsWithAny(String s, String[] exts) {
        for (String e : exts) if (s.endsWith(e)) return true;
        return false;
    }

    /** 正文里是否还有独占一行的内嵌代码段标签（已废弃写法，仅用于迁移提示）。 */
    private static boolean containsInlineCodeTag(String body) {
        if (body == null || body.isEmpty()) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?m)^[ \\t]{0,3}<\\s*(?:java|js|nodejs|python)(?:\\s+start)?\\s*>[ \\t]*$")
                .matcher(body);
        return m.find();
    }

    /** 构建 .md 文件内容（front matter + 正文）。name/description 转单行，避免换行破坏 front matter。 */
    static String buildMarkdown(String name, String description, String content) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(singleLine(name)).append("\n");
        sb.append("description: ").append(singleLine(description)).append("\n");
        sb.append("---\n");
        sb.append(content == null ? "" : content);
        return sb.toString();
    }

    private static String singleLine(String s) {
        if (s == null) return "";
        return s.trim().replace('\r', ' ').replace('\n', ' ');
    }

    private static String extractFmField(String fm, String key) {
        for (String line : fm.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + ":")) {
                String v = t.substring(key.length() + 1).trim();
                // 剥离 YAML 引号（技能包 description 常见用引号包裹，如 description: "..."）
                if (v.length() >= 2) {
                    char first = v.charAt(0), last = v.charAt(v.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        v = v.substring(1, v.length() - 1);
                    }
                }
                return v;
            }
        }
        return null;
    }

    /** airun 声明块解析结果。 */
    private static final class AirunParseResult {
        String description;
        String permission;
        String channels;
        final List<ThirdPartySkill.AirunParam> params = new ArrayList<>();
    }

    /**
     * 解析 front matter 中的 airun 声明块（可选），提取函数描述、权限与参数列表。
     * <pre>
     * airun:
     *   description: xxx
     *   permission: AFFECTION:300      # 可选，覆盖默认权限位（默认 AFFECTION:300）
     *   params:
     *     arg1:
     *       type: string               # string/integer/number/boolean/array/object
     *       description: xxx
     *       required: true             # true/yes/1 均可
     *     tags:
     *       type: array
     *       items: string              # 数组元素类型
     * </pre>
     */
    private static AirunParseResult parseAirun(String fm) {
        AirunParseResult r = new AirunParseResult();
        if (fm == null || fm.isEmpty()) return r;
        String[] lines = fm.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!"airun:".equals(lines[i].trim())) continue;
            final int base = indentOf(lines[i]);
            // 只认「airun 的直接子级」上的键。
            // 旧实现匹配任意深度的 description:，于是参数块里的 description 会反过来覆盖
            // airun 自己的描述 —— 实测 description 最终变成了最后一个参数的描述（如「预报天数」），
            // 而它正是要展示给模型的工具说明。子级缩进取「第一条比 airun 更深的非空行」。
            int childIndent = -1;
            for (int k = i + 1; k < lines.length; k++) {
                if (lines[k].trim().isEmpty()) continue;
                int ind = indentOf(lines[k]);
                if (ind <= base) break;
                childIndent = ind;
                break;
            }
            if (childIndent < 0) break;
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j];
                if (l.trim().isEmpty()) continue;
                int ind = indentOf(l);
                if (ind <= base) break;      // 退出 airun 块
                if (ind != childIndent) continue;   // 跳过参数块内部的键
                String t = l.trim();
                if (t.startsWith("description:")) {
                    r.description = unquote(t.substring("description:".length()).trim());
                } else if (t.startsWith("permission:")) {
                    r.permission = unquote(t.substring("permission:".length()).trim());
                } else if (t.startsWith("channels:")) {
                    r.channels = unquote(t.substring("channels:".length()).trim());
                } else if ("params:".equals(t)) {
                    parseParams(lines, j + 1, ind, r.params);
                }
            }
            break; // 只处理第一个 airun 声明
        }
        return r;
    }

    /** 解析 params 缩进块内的参数列表。 */
    private static void parseParams(String[] lines, int start, int paramsIndent,
                                    List<ThirdPartySkill.AirunParam> out) {
        int i = start;
        while (i < lines.length) {
            String l = lines[i];
            if (l.trim().isEmpty()) { i++; continue; }
            int ind = indentOf(l);
            if (ind <= paramsIndent) break; // 退出 params 块
            String t = l.trim();
            if (t.endsWith(":") && t.length() > 1) {
                String paramName = t.substring(0, t.length() - 1).trim();
                String type = "string", desc = "", items = null, itemsDesc = null;
                String defVal = null, example = null;
                java.util.List<String> enums = new java.util.ArrayList<>();
                boolean required = false;
                int j = i + 1;
                while (j < lines.length) {
                    String pl = lines[j];
                    if (pl.trim().isEmpty()) { j++; continue; }
                    int pind = indentOf(pl);
                    if (pind <= ind) break; // 退出该参数属性块
                    String pt = pl.trim();
                    if (pt.startsWith("type:")) type = pt.substring("type:".length()).trim();
                    else if (pt.startsWith("description:")) desc = unquote(pt.substring("description:".length()).trim());
                    else if (pt.startsWith("items:")) items = pt.substring("items:".length()).trim();
                    else if (pt.startsWith("items_description:")) itemsDesc = unquote(pt.substring("items_description:".length()).trim());
                    else if (pt.startsWith("required:")) required = parseBool(pt.substring("required:".length()).trim());
                    else if (pt.startsWith("default:")) defVal = pt.substring("default:".length()).trim();
                    else if (pt.startsWith("example:")) example = unquote(pt.substring("example:".length()).trim());
                    else if (pt.startsWith("enum:")) {
                        // 支持 enum: a,b,c 与 enum: [a, b, c] 两种写法
                        String raw = pt.substring("enum:".length()).trim();
                        if (raw.startsWith("[")) raw = raw.replaceAll("^\\[|\\]$", "");
                        for (String e : raw.split("[,，]")) {
                            String v = unquote(e.trim());
                            if (!v.isEmpty()) enums.add(v);
                        }
                    }
                    j++;
                }
                out.add(new ThirdPartySkill.AirunParam(paramName, type, desc, required,
                        items, itemsDesc, defVal, enums, example));
                i = j;
            } else {
                i++;
            }
        }
    }

    /** 宽松整数解析（失败返回 default）。 */
    private static int parseIntField(String v, int def) {
        if (v == null) return def;
        try { return Integer.parseInt(unquote(v).trim()); } catch (Exception e) { return def; }
    }

    /** 宽松布尔解析：true/yes/y/1/on 均视为真（旧实现只认 "true"，required: yes 会被静默当成 false）。 */
    private static boolean parseBool(String v) {
        if (v == null) return false;
        String t = unquote(v).toLowerCase();
        return "true".equals(t) || "yes".equals(t) || "y".equals(t) || "1".equals(t) || "on".equals(t);
    }

    /** 计算行首缩进空格数（空格或制表符）。 */
    private static int indentOf(String line) {
        if (line == null) return 0;
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) n++;
        return n;
    }

    /** 剥离字符串首尾引号。 */
    private static String unquote(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.length() >= 2) {
            char first = v.charAt(0), last = v.charAt(v.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }

    /** 文件名安全校验：禁止路径分隔符（防穿越）与文件系统非法字符；`..` 因分隔符已禁无需单列。 */
    private static String sanitizeName(String name) {
        if (name == null) return null;
        String n = name.trim();
        if (n.isEmpty()) return null;
        if (n.contains("/") || n.contains("\\") || n.contains(":")
                || n.contains("*") || n.contains("?") || n.contains("\"")
                || n.contains("<") || n.contains(">") || n.contains("|")) {
            return null;
        }
        return n;
    }

}
