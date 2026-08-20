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
 * 技能包（符合 agentskills.io 标准、含 SKILL.md 的多文件目录/zip）由 {@link AgentSkillStore}
 * 管理，存放于 {@code data/skillpackages} 目录，与本类物理隔离。
 * </p>
 */
public class ThirdPartySkillStore {

    /** 三方技能数量上限 */
    public static final int MAX_SKILLS = 50;
    /** 单个 .md 文件大小上限（字节） */
    public static final long MAX_FILE_SIZE = 8 * 1024;

    private final Map<String, ThirdPartySkill> skills = new ConcurrentHashMap<>();

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
            watchThread = new Thread(this::watchLoop, "ThirdPartySkill-Watcher");
            watchThread.setDaemon(true);
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

    /** 重新同步监听：取消旧 key，注册 skillsDir 根目录。 */
    private void resyncWatch() {
        if (watchService == null || skillsDir == null) return;
        for (WatchKey k : watchKeyMap.keySet()) {
            k.cancel();
        }
        watchKeyMap.clear();
        try {
            WatchKey key = skillsDir.toPath().register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
            watchKeyMap.put(key, skillsDir.toPath());
        } catch (Exception e) {
            AiAgentActivity.debugLog("[ThirdPartySkill] 注册监听失败: " + e.toString());
        }
    }

    /** 全量重扫目录，重建内存镜像（仅识别根目录下的单文件 .md）。 */
    private synchronized void reloadAll() {
        if (skillsDir == null || !skillsDir.isDirectory()) return;
        File[] entries = skillsDir.listFiles();
        Map<String, ThirdPartySkill> fresh = new ConcurrentHashMap<>();
        if (entries != null) {
            for (File f : entries) {
                if (f.isFile() && !f.getName().startsWith(".")
                        && f.getName().toLowerCase().endsWith(".md")) {
                    ThirdPartySkill s = parseFile(f);
                    if (s != null && !s.getName().isEmpty()) {
                        if (fresh.size() >= MAX_SKILLS) {
                            AiAgentActivity.debugLog("[ThirdPartySkill] 三方技能数量已达上限 "
                                    + MAX_SKILLS + "，忽略: " + f.getName());
                            break;
                        }
                        fresh.put(s.getName(), s);
                    }
                }
            }
        }

        // diff：识别新增/移除的技能，输出日志（让控制台有加载反馈）
        Set<String> newNames = new TreeSet<>(fresh.keySet());
        newNames.removeAll(lastNames);
        Set<String> removedNames = new TreeSet<>(lastNames);
        removedNames.removeAll(fresh.keySet());

        skills.clear();
        skills.putAll(fresh);
        lastNames = new TreeSet<>(fresh.keySet());

        if (!newNames.isEmpty()) {
            AiAgentActivity.debugLog("[ThirdPartySkill] +" + newNames.size()
                    + " 个技能: " + String.join(", ", newNames));
        }
        if (!removedNames.isEmpty()) {
            AiAgentActivity.debugLog("[ThirdPartySkill] -" + removedNames.size()
                    + " 个技能: " + String.join(", ", removedNames));
        }

        checkCodeRuntimes();

        resyncWatch();
    }

    private ThirdPartySkill parseFile(File f) {
        if (f == null || !f.isFile()) return null;
        if (f.length() > MAX_FILE_SIZE) return null;
        try {
            String raw = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            return parseMarkdown(raw, f);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== Markdown 解析 / 构建 ====================

    /** 解析 .md 内容：支持 front matter（name / description / airun 签名），无 front matter 时用文件名兜底。 */
    static ThirdPartySkill parseMarkdown(String raw, File sourceFile) {
        String name = null;
        String description = "";
        String content = raw == null ? "" : raw;
        String airunDescription = null;
        List<ThirdPartySkill.AirunParam> airunParams = Collections.emptyList();

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
                AirunParseResult ar = parseAirun(fm.toString());
                airunDescription = ar.description;
                airunParams = ar.params;
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
        } else if (!name.trim().equals(fname)) {
            EdtUtils.println(FCM.Error_Color,
                    "[ThirdPartySkill] ✗ 文件名与 name 字段不一致，拒绝加载: " + sourceFile.getName()
                            + "（name=" + name.trim() + "）");
            return null;
        }

        if (description == null) description = "";
        if (description.trim().isEmpty()) {
            description = content.length() > 80 ? content.substring(0, 80) : content;
        }
        Map<String, String> codeBlocks = extractCodeBlocks(content);
        String bodyOnly = stripCodeBlocks(content);
        if (description.trim().isEmpty()) {
            description = bodyOnly.length() > 80 ? bodyOnly.substring(0, 80) : bodyOnly;
        }
        return new ThirdPartySkill(name, description, bodyOnly, sourceFile.getAbsolutePath(),
                codeBlocks, airunDescription, airunParams);
    }

    /** 支持的语言代码段标签。 */
    private static final String[] CODE_LANGS = {"nodejs", "js", "java", "python"};

    /**
     * 从 md 正文中提取四种代码段（&lt;nodejs start&gt;…&lt;nodejs end&gt; 等），
     * 返回 语言 → 代码 映射。同一语言多个代码段合并（换行分隔）。
     */
    static Map<String, String> extractCodeBlocks(String content) {
        Map<String, String> blocks = new LinkedHashMap<>();
        if (content == null || content.isEmpty()) return blocks;
        for (String lang : CODE_LANGS) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "<" + lang + "\\s+start>\\s*\\n?([\\s\\S]*?)\\s*<" + lang + "\\s+end>");
            java.util.regex.Matcher m = p.matcher(content);
            StringBuilder merged = null;
            while (m.find()) {
                String code = m.group(1).trim();
                if (!code.isEmpty()) {
                    if (merged == null) merged = new StringBuilder(code);
                    else merged.append("\n").append(code);
                }
            }
            if (merged != null) blocks.put(lang, merged.toString());
        }
        return blocks;
    }

    /** 从 md 正文中移除代码段（保留自然语言说明，代码段交由执行器按需调用）。 */
    static String stripCodeBlocks(String content) {
        if (content == null || content.isEmpty()) return content;
        for (String lang : CODE_LANGS) {
            content = content.replaceAll("<" + lang + "\\s+start>[\\s\\S]*?<" + lang + "\\s+end>", "");
        }
        return content;
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
        final List<ThirdPartySkill.AirunParam> params = new ArrayList<>();
    }

    /**
     * 解析 front matter 中的 airun 声明块（可选），提取函数描述与参数列表。
     * <pre>
     * airun:
     *   description: xxx
     *   params:
     *     arg1:
     *       type: string
     *       description: xxx
     *       required: true
     * </pre>
     */
    private static AirunParseResult parseAirun(String fm) {
        AirunParseResult r = new AirunParseResult();
        if (fm == null || fm.isEmpty()) return r;
        String[] lines = fm.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!"airun:".equals(lines[i].trim())) continue;
            int base = indentOf(lines[i]);
            for (int j = i + 1; j < lines.length; j++) {
                String l = lines[j];
                if (l.trim().isEmpty()) continue;
                int ind = indentOf(l);
                if (ind <= base) break; // 退出 airun 块
                String t = l.trim();
                if (t.startsWith("description:")) {
                    r.description = unquote(t.substring("description:".length()).trim());
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
                String type = "string", desc = "";
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
                    else if (pt.startsWith("required:")) required = "true".equalsIgnoreCase(pt.substring("required:".length()).trim());
                    j++;
                }
                out.add(new ThirdPartySkill.AirunParam(paramName, type, desc, required));
                i = j;
            } else {
                i++;
            }
        }
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

    // ==================== 代码段环境检测 ====================

    /**
     * 检测含 nodejs/python 代码段的三方技能所需运行时是否就绪。
     * node/python 不是 JVM 原生能力，缺失时红字统一告知用户自行安装。
     */
    private void checkCodeRuntimes() {
        Set<String> needNode = new LinkedHashSet<>();
        Set<String> needPython = new LinkedHashSet<>();
        for (ThirdPartySkill s : skills.values()) {
            Map<String, String> blocks = s.getCodeBlocks();
            if (blocks == null || blocks.isEmpty()) continue;
            if (blocks.containsKey("nodejs")) needNode.add(s.getName());
            if (blocks.containsKey("python")) needPython.add(s.getName());
        }
        if (needNode.isEmpty() && needPython.isEmpty()) return;

        Map<String, Boolean> status = new LinkedHashMap<>();
        if (!needNode.isEmpty()) status.put("node", probe("node --version"));
        if (!needPython.isEmpty()) status.put("python", probe("python --version"));

        if (status.equals(lastCodeEnvStatus)) return;
        lastCodeEnvStatus = new LinkedHashMap<>(status);

        StringBuilder missing = new StringBuilder();
        for (Map.Entry<String, Boolean> e : status.entrySet()) {
            if (e.getValue()) continue;
            String skills = "node".equals(e.getKey()) ? String.join(", ", needNode) : String.join(", ", needPython);
            if (missing.length() > 0) missing.append("\n");
            missing.append("  ✗ ").append(e.getKey()).append(" 未检测到 → 影响技能: ").append(skills);
        }
        if (missing.length() > 0) {
            EdtUtils.println(FCM.Error_Color,
                    "\n[ThirdPartySkill] ⚠ 三方技能含可执行代码段，但以下运行时未安装（请自行安装后重试）：\n" + missing + "\n");
        }
    }

    /** 探测命令是否可用（捕获退出码，3 秒超时）。 */
    private boolean probe(String command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command.split("\\s+"));
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[256];
                while (in.read(buf) != -1) { /* 吞掉输出避免阻塞 */ }
            }
            if (!p.waitFor(3, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
