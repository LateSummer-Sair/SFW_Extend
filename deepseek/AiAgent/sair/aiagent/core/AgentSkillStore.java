package sair.aiagent.core;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import sair.FCM;
import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ThirdPartySkill;
import sair.aiagent.util.EdtUtils;

/**
 * agentskills.io 技能包存储 —— 基于 {@code data/skillpackages} 目录的技能包内存镜像 + 文件监听。
 * <p>
 * 与单文件三方技能（{@code data/skills} 目录，见 {@link ThirdPartySkillStore}）物理隔离：
 * 每个技能包是一个含 {@code SKILL.md} 的目录，遵守 agentskills.io 标准。
 * </p>
 * <ul>
 *   <li>目录技能包：新增目录→注册、删除目录→移除、修改→重载。</li>
 *   <li>zip 技能包：扫描时自动解压 → 移动到 {@code data/skillpackages/<name>/} → 删除原 zip。</li>
 *   <li>渐进式披露：注册时仅提取 front matter 元信息（name/description），正文与附属文档按需懒加载。</li>
 *   <li>环境依赖审查：注册后探测所需运行时/程序在当前系统是否可用，缺失的提示用户配置。</li>
 * </ul>
 */
public class AgentSkillStore {

    /** 技能包数量上限 */
    public static final int MAX_PACKAGES = 50;
    /** 环境探测结果缓存有效期（毫秒），超时后重新探测，避免用户装好运行时后长期不刷新 */
    private static final long RUNTIME_CHECK_TTL_MS = 60_000L;
    /** 技能包目录名 */
    private static final String PACKAGES_DIR = "skillpackages";
    /** zip 解压临时目录前缀（隐藏目录，避免被当作技能入口）。 */
    private static final String TMP_PREFIX = ".tmp_";

    /** 脚本扩展名 → 所需运行时（技能包 scripts/ 下辅助脚本检测用）。 */
    private static final Map<String, String> SCRIPT_RUNTIME = new HashMap<>();
    static {
        SCRIPT_RUNTIME.put("py", "Python");
        SCRIPT_RUNTIME.put("js", "Node.js");
        SCRIPT_RUNTIME.put("mjs", "Node.js");
        SCRIPT_RUNTIME.put("cjs", "Node.js");
        SCRIPT_RUNTIME.put("ts", "Node.js + TypeScript");
        SCRIPT_RUNTIME.put("sh", "Bash (Linux/macOS)");
        SCRIPT_RUNTIME.put("bash", "Bash (Linux/macOS)");
        SCRIPT_RUNTIME.put("bat", "CMD (Windows)");
        SCRIPT_RUNTIME.put("cmd", "CMD (Windows)");
        SCRIPT_RUNTIME.put("ps1", "PowerShell");
        SCRIPT_RUNTIME.put("rb", "Ruby");
        SCRIPT_RUNTIME.put("go", "Go");
        SCRIPT_RUNTIME.put("java", "Java (JDK)");
        SCRIPT_RUNTIME.put("rs", "Rust");
        SCRIPT_RUNTIME.put("php", "PHP");
        SCRIPT_RUNTIME.put("pl", "Perl");
        SCRIPT_RUNTIME.put("lua", "Lua");
        SCRIPT_RUNTIME.put("r", "R");
    }

    /** 运行时名称 → 探测命令（环境可用性审查用）。 */
    private static final Map<String, String> RUNTIME_CHECK_CMD = new HashMap<>();
    static {
        RUNTIME_CHECK_CMD.put("Python", "python --version");
        RUNTIME_CHECK_CMD.put("Node.js", "node --version");
        RUNTIME_CHECK_CMD.put("Node.js + TypeScript", "node --version");
        RUNTIME_CHECK_CMD.put("Bash (Linux/macOS)", "bash --version");
        RUNTIME_CHECK_CMD.put("CMD (Windows)", "cmd /c ver");
        RUNTIME_CHECK_CMD.put("PowerShell", "powershell -NoProfile -Command exit");
        RUNTIME_CHECK_CMD.put("Ruby", "ruby --version");
        RUNTIME_CHECK_CMD.put("Go", "go version");
        RUNTIME_CHECK_CMD.put("Java (JDK)", "java -version");
        RUNTIME_CHECK_CMD.put("Rust", "rustc --version");
        RUNTIME_CHECK_CMD.put("PHP", "php --version");
        RUNTIME_CHECK_CMD.put("Perl", "perl --version");
        RUNTIME_CHECK_CMD.put("Lua", "lua -v");
        RUNTIME_CHECK_CMD.put("R", "R --version");
    }

    /** compatibility 字段中常见的程序名 → 探测命令。 */
    private static final Map<String, String> PROGRAM_CHECK_CMD = new HashMap<>();
    static {
        PROGRAM_CHECK_CMD.put("git", "git --version");
        PROGRAM_CHECK_CMD.put("docker", "docker --version");
        PROGRAM_CHECK_CMD.put("jq", "jq --version");
        PROGRAM_CHECK_CMD.put("python3", "python3 --version");
        PROGRAM_CHECK_CMD.put("python", "python --version");
        PROGRAM_CHECK_CMD.put("node", "node --version");
        PROGRAM_CHECK_CMD.put("npm", "npm --version");
        PROGRAM_CHECK_CMD.put("curl", "curl --version");
        PROGRAM_CHECK_CMD.put("wget", "wget --version");
    }

    private final Map<String, ThirdPartySkill> skills = new ConcurrentHashMap<>();
    /** 技能名 → compatibility 字段文本（agentskills.io 可选字段，环境要求说明）。 */
    private final Map<String, String> compatMap = new ConcurrentHashMap<>();

    private volatile File skillsDir;
    private volatile WatchService watchService;
    private volatile Thread watchThread;
    private volatile boolean running = false;

    /** 上次已输出的运行时清单（变化检测用，避免监听重扫时重复刷屏）。 */
    private volatile List<String> lastLoggedRuntimes;
    /** 已注册监听的 WatchKey → 目录 映射（递归监听用，弥补 WatchService 非递归局限）。 */
    private final Map<WatchKey, Path> watchKeyMap = new ConcurrentHashMap<>();
    /** 上一次重扫后的技能名集合（用于 diff 输出加载/移除日志）。 */
    private volatile Set<String> lastNames = new LinkedHashSet<>();
    /** 环境探测结果缓存（带 TTL 过期，超时后重新探测）：运行时/程序名 → 结果。 */
    private final Map<String, CacheEntry> runtimeCheckCache = new ConcurrentHashMap<>();
    /** 上一次环境审查结果（变化检测用，避免重复刷屏）。 */
    private volatile Map<String, Boolean> lastEnvStatus = new LinkedHashMap<>();

    /** 初始化：确保目录存在 → 全量扫描 → 启动文件监听。 */
    public synchronized void init(File dataDir) {
        File dir = new File(dataDir, PACKAGES_DIR);
        if (!dir.exists()) dir.mkdirs();
        this.skillsDir = dir;
        startWatch();   // 先启动监听服务与守护线程
        reloadAll();    // 全量扫描 + zip 导入 + 递归注册子目录监听
        AiAgentActivity.debugLog("[AgentSkill] agentskills.io 技能包库已初始化: " + dir.getAbsolutePath()
                + " (" + skills.size() + " 个技能包)");
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

    /** 列出所有技能包（名称 + 描述 + 环境要求）。 */
    public String list() {
        if (skills.isEmpty()) return "技能包库为空（data/skillpackages 目录下无符合 agentskills.io 标准的技能包）";
        List<ThirdPartySkill> sorted = new ArrayList<>(skills.values());
        sorted.sort(Comparator.comparing(ThirdPartySkill::getName));
        StringBuilder sb = new StringBuilder("agentskills.io 技能包（").append(sorted.size()).append(" 个）:\n");
        for (ThirdPartySkill s : sorted) {
            sb.append("- ").append(s.getName()).append(": ").append(s.getDescription());
            String compat = compatMap.get(s.getName());
            if (compat != null && !compat.isEmpty()) {
                sb.append("  [环境要求: ").append(compat).append("]");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 文件监听 ====================

    private void startWatch() {
        if (skillsDir == null) return;
        try {
            watchService = FileSystems.getDefault().newWatchService();
            running = true;
            watchThread = new Thread(this::watchLoop, "AgentSkill-Watcher");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (Exception e) {
            AiAgentActivity.debugLog("[AgentSkill] 文件监听启动失败: " + e.toString());
        }
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) continue;
                if (!key.isValid()) {
                    key.pollEvents();
                    continue;
                }
                key.pollEvents();
                key.reset();
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
                AiAgentActivity.debugLog("[AgentSkill] 监听异常: " + e.toString());
            }
        }
    }

    /** 重新同步子目录监听：取消旧 key，递归注册 skillsDir 及其所有子目录。 */
    private void resyncWatch() {
        if (watchService == null || skillsDir == null) return;
        for (WatchKey k : watchKeyMap.keySet()) {
            k.cancel();
        }
        watchKeyMap.clear();
        try {
            registerRecursive(skillsDir.toPath());
        } catch (Exception e) {
            AiAgentActivity.debugLog("[AgentSkill] 递归注册监听失败: " + e.toString());
        }
    }

    /** 递归注册目录（含所有子目录）到 WatchService，弥补其非递归监听的局限。 */
    private void registerRecursive(Path dir) throws java.io.IOException {
        WatchKey key = dir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY);
        watchKeyMap.put(key, dir);
        File[] subs = dir.toFile().listFiles();
        if (subs != null) {
            for (File f : subs) {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    registerRecursive(f.toPath());
                }
            }
        }
    }

    /** 全量重扫目录：先导入 zip，再注册目录技能包，重建内存镜像。 */
    private synchronized void reloadAll() {
        if (skillsDir == null || !skillsDir.isDirectory()) return;

        // 第一遍：处理 zip 技能包（解压→移动→删除 zip）
        File[] entries = skillsDir.listFiles();
        if (entries != null) {
            for (File f : entries) {
                if (f.isFile() && !f.getName().startsWith(".")
                        && f.getName().toLowerCase().endsWith(".zip")) {
                    importZip(f);
                }
            }
        }

        // 第二遍：扫描目录技能包
        entries = skillsDir.listFiles();
        Map<String, ThirdPartySkill> fresh = new ConcurrentHashMap<>();
        if (entries != null) {
            for (File f : entries) {
                if (f.isDirectory() && !f.getName().startsWith(".")) {
                    ThirdPartySkill s = loadPackage(f);
                    if (s != null && !s.getName().isEmpty()) {
                        if (fresh.size() >= MAX_PACKAGES) {
                            AiAgentActivity.debugLog("[AgentSkill] 技能包数量已达上限 " + MAX_PACKAGES + "，忽略: " + f.getName());
                            break;
                        }
                        fresh.put(s.getName(), s);
                    }
                }
            }
        }

        // diff：识别新增/移除的技能包
        Set<String> newNames = new TreeSet<>(fresh.keySet());
        newNames.removeAll(lastNames);
        Set<String> removedNames = new TreeSet<>(lastNames);
        removedNames.removeAll(fresh.keySet());

        skills.clear();
        skills.putAll(fresh);
        // 清理已移除技能包的 compatibility 记录
        compatMap.keySet().retainAll(fresh.keySet());
        lastNames = new TreeSet<>(fresh.keySet());

        if (!newNames.isEmpty()) {
            AiAgentActivity.debugLog("[AgentSkill] +" + newNames.size()
                    + " 个技能包: " + String.join(", ", newNames));
        }
        if (!removedNames.isEmpty()) {
            AiAgentActivity.debugLog("[AgentSkill] -" + removedNames.size()
                    + " 个技能包: " + String.join(", ", removedNames));
        }

        // 加载/注册完成后统一输出运行时清单 + 环境依赖审查
        logRequiredRuntimes();
        checkEnvironment();
        // 重新同步子目录监听
        resyncWatch();
    }

    // ==================== zip 导入 ====================

    /**
     * 导入 zip 技能包：解压到临时目录 → 定位 SKILL.md → 移动到 {@code skillpackages/<name>/} → 删除 zip。
     */
    private void importZip(File zipFile) {
        String baseName = zipFile.getName();
        int dot = baseName.lastIndexOf('.');
        if (dot > 0) baseName = baseName.substring(0, dot);

        File tmpDir = new File(skillsDir, TMP_PREFIX + baseName);
        deleteRecursively(tmpDir);
        if (!extractZip(zipFile, tmpDir)) return;

        File pkgRoot = locatePackageRoot(tmpDir);
        if (pkgRoot == null) {
            AiAgentActivity.debugLog("[AgentSkill] zip 技能包缺少 SKILL.md: " + zipFile.getName());
            deleteRecursively(tmpDir);
            return;
        }

        // 用 front matter 的 name 作为目标目录名（符合规范时），否则用原目录名
        String name = null;
        Meta meta = parseMeta(new File(pkgRoot, "SKILL.md"));
        if (meta != null && isValidAgentSkillName(meta.name)) {
            name = meta.name;
        }
        if (name == null) {
            name = sanitizePackageName(pkgRoot.getName());
        }

        File destDir = new File(skillsDir, name);
        deleteRecursively(destDir);

        boolean moved;
        if (pkgRoot.getAbsoluteFile().equals(tmpDir.getAbsoluteFile())) {
            // SKILL.md 在 zip 根：直接移动整个临时目录
            moved = tmpDir.renameTo(destDir);
            if (!moved) moved = moveDir(tmpDir, destDir);
            if (!moved) deleteRecursively(tmpDir);
        } else {
            moved = pkgRoot.renameTo(destDir);
            if (!moved) moved = moveDir(pkgRoot, destDir);
            deleteRecursively(tmpDir);
        }
        if (!moved) {
            AiAgentActivity.debugLog("[AgentSkill] zip 技能包移动失败: " + zipFile.getName());
            return;
        }

        zipFile.delete();
        AiAgentActivity.debugLog("[AgentSkill] 已导入 zip 技能包: " + zipFile.getName() + " → " + name);
    }

    /** 在解压目录中定位真正的技能包根（SKILL.md 可能在根，也可能被一层目录包裹）。 */
    private File locatePackageRoot(File dir) {
        if (new File(dir, "SKILL.md").isFile()) return dir;
        File[] subs = dir.listFiles();
        if (subs != null) {
            for (File sub : subs) {
                if (sub.isDirectory() && new File(sub, "SKILL.md").isFile()) {
                    return sub;
                }
            }
        }
        return null;
    }

    /** 解压 zip 到目标目录，带 zip-slip 路径穿越防护。 */
    private boolean extractZip(File zipFile, File destDir) {
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(zipFile)))) {
            String destRoot = destDir.getCanonicalPath();
            byte[] buf = new byte[8192];
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zin.closeEntry();
                    continue;
                }
                File out = new File(destDir, entry.getName());
                String outPath = out.getCanonicalPath();
                if (!outPath.equals(destRoot) && !outPath.startsWith(destRoot + File.separator)) {
                    zin.closeEntry();  // 拒绝路径穿越
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                try (OutputStream os = new FileOutputStream(out)) {
                    int n;
                    while ((n = zin.read(buf)) != -1) {
                        os.write(buf, 0, n);
                    }
                }
                zin.closeEntry();
            }
            return true;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[AgentSkill] zip 解压失败 " + zipFile.getName() + ": " + e.toString());
            return false;
        }
    }

    /** 递归删除文件或目录。 */
    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] subs = f.listFiles();
            if (subs != null) {
                for (File sub : subs) deleteRecursively(sub);
            }
        }
        f.delete();
    }

    /** 递归移动文件或目录（renameTo 失败时的兜底）。 */
    private boolean moveDir(File src, File dest) {
        if (src == null || !src.exists()) return false;
        if (src.isDirectory()) {
            if (!dest.exists()) dest.mkdirs();
            File[] files = src.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!moveDir(f, new File(dest, f.getName()))) return false;
                }
            }
            return src.delete();
        }
        try {
            Files.copy(src.toPath(), dest.toPath());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== agentskills.io 标准解析 ====================

    /** front matter 解析结果。 */
    private static class Meta {
        String name;
        String description;
        String compatibility;
    }

    /** 环境探测结果缓存条目（带过期时间）。 */
    private static class CacheEntry {
        final boolean available;
        final long timestamp;
        CacheEntry(boolean available) {
            this.available = available;
            this.timestamp = System.currentTimeMillis();
        }
        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > RUNTIME_CHECK_TTL_MS;
        }
    }

    /** 校验 agentskills.io name 规范：1-64 字符，小写字母数字连字符，不首尾连字符、不连续连字符。 */
    private static boolean isValidAgentSkillName(String name) {
        if (name == null || name.isEmpty() || name.length() > 64) return false;
        return name.matches("[a-z0-9]+(-[a-z0-9]+)*");
    }

    /** 将目录名规范化为 agentskills.io name：转小写、非法字符转连字符、合并连续连字符、去首尾连字符、截断 64。 */
    private static String sanitizePackageName(String dirName) {
        if (dirName == null) return "";
        String lower = dirName.toLowerCase();
        StringBuilder sb = new StringBuilder();
        for (char c : lower.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        if (sb.length() == 0) return dirName;  // 全部为非法字符时退回原目录名（仅作内存 key）
        if (sb.length() > 64) {
            sb.setLength(64);
            while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
                sb.setLength(sb.length() - 1);
            }
        }
        return sb.toString();
    }

    /**
     * 注册技能包：目录内存在 SKILL.md 才视为技能包。
     * 仅提取 front matter 元信息（name/description/compatibility）+ 扫描文件结构，
     * 不加载正文与附属文档内容到内存；使用时按需临时读取。
     */
    private ThirdPartySkill loadPackage(File dir) {
        File skillMd = new File(dir, "SKILL.md");
        if (!skillMd.isFile()) return null;  // 无 SKILL.md → 非技能包，忽略

        Meta meta = parseMeta(skillMd);
        if (meta == null) return null;

        // name 兜底：front matter name 不符合 agentskills.io 规范时用目录名
        String pkgName = meta.name;
        if (!isValidAgentSkillName(pkgName)) {
            if (pkgName != null && !pkgName.trim().isEmpty()) {
                AiAgentActivity.debugLog("[AgentSkill] 技能包 name 不符合 agentskills.io 规范，用目录名兜底: "
                        + pkgName + " → " + dir.getName());
            }
            pkgName = sanitizePackageName(dir.getName());
        } else if (!pkgName.equals(dir.getName())) {
            AiAgentActivity.debugLog("[AgentSkill] 技能包 name 与目录名不一致: " + pkgName
                    + " (目录 " + dir.getName() + ")，以 front matter name 为准");
        }

        String description = meta.description != null ? meta.description.trim() : "";
        if (description.length() > 1024) description = description.substring(0, 1024);
        if (description.isEmpty()) {
            description = "(无描述)";
        }

        Map<String, String> mdPaths = new LinkedHashMap<>();
        Set<String> runtimes = new LinkedHashSet<>();
        scanPackage(dir, dir, mdPaths, runtimes);

        if (meta.compatibility != null && !meta.compatibility.trim().isEmpty()) {
            compatMap.put(pkgName, meta.compatibility.trim());
        }

        List<String> runtimeList = new ArrayList<>(runtimes);
        return new ThirdPartySkill(pkgName, description, skillMd.getAbsolutePath(),
                dir.getAbsolutePath(), mdPaths, runtimeList);
    }

    /**
     * 递归扫描技能包目录：收集 .md 文件相对路径 → 绝对路径；检测脚本文件扩展名 → 所需运行时。
     * 不读取文件内容。
     */
    private void scanPackage(File root, File dir, Map<String, String> mdPaths, Set<String> runtimes) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                if (f.getName().startsWith(".")) continue;  // 跳过 .git/.idea 等隐藏目录
                scanPackage(root, f, mdPaths, runtimes);
            } else if (f.isFile()) {
                String name = f.getName();
                String lower = name.toLowerCase();
                String rel = root.toURI().relativize(f.toURI()).getPath().replace('\\', '/');
                if (lower.endsWith(".md")) {
                    if (!"skill.md".equals(lower)) {
                        mdPaths.put(rel, f.getAbsolutePath());
                    }
                } else {
                    int dot = name.lastIndexOf('.');
                    if (dot > 0) {
                        String runtime = SCRIPT_RUNTIME.get(name.substring(dot + 1).toLowerCase());
                        if (runtime != null) runtimes.add(runtime);
                    }
                }
            }
        }
    }

    /** 临时读取技能包 SKILL.md 正文（懒加载，使用时调用，剥离 front matter）。 */
    public String readMainContent(ThirdPartySkill s) {
        if (s == null || s.getSourceFile() == null) return "";
        File f = new File(s.getSourceFile());
        String raw = readFileContent(f);
        if (raw.isEmpty()) return "";
        return stripFrontMatter(raw);
    }

    /** 临时读取技能包附属文档内容（懒加载，使用时调用）。 */
    public String readAttachment(ThirdPartySkill s, String relPath) {
        if (s == null || relPath == null) return "";
        String abs = s.getAttachmentPaths().get(relPath);
        if (abs == null) return "";
        return readFileContent(new File(abs));
    }

    private String readFileContent(File f) {
        if (f == null || !f.isFile()) return "";
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** 仅解析 SKILL.md 的 front matter（最多读前 4KB），不加载正文。 */
    private Meta parseMeta(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            byte[] head = new byte[4096];
            int n;
            try (InputStream in = Files.newInputStream(f.toPath())) {
                n = in.read(head);
            }
            if (n <= 0) return null;
            return parseFrontMatter(new String(head, 0, n, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 front matter 的 name/description/compatibility 字段。 */
    private Meta parseFrontMatter(String raw) {
        Meta meta = new Meta();
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !"---".equals(lines[0].trim())) return meta;
        StringBuilder fm = new StringBuilder();
        int i = 1;
        boolean closed = false;
        while (i < lines.length) {
            if ("---".equals(lines[i].trim())) { closed = true; break; }
            fm.append(lines[i]).append("\n");
            i++;
        }
        if (!closed) return meta;
        meta.name = extractFmField(fm.toString(), "name");
        meta.description = extractFmField(fm.toString(), "description");
        meta.compatibility = extractFmField(fm.toString(), "compatibility");
        return meta;
    }

    /** 提取 front matter 单行字段值，剥离 YAML 首尾引号。 */
    private static String extractFmField(String fm, String key) {
        for (String line : fm.split("\n")) {
            String t = line.trim();
            if (t.startsWith(key + ":")) {
                String v = t.substring(key.length() + 1).trim();
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

    /** 剥离 SKILL.md 的 front matter，返回正文。 */
    private String stripFrontMatter(String raw) {
        String[] lines = raw.split("\n", -1);
        if (lines.length > 0 && "---".equals(lines[0].trim())) {
            int i = 1;
            while (i < lines.length) {
                if ("---".equals(lines[i].trim())) break;
                i++;
            }
            if (i < lines.length) {
                StringBuilder body = new StringBuilder();
                for (int j = i + 1; j < lines.length; j++) {
                    if (body.length() > 0) body.append("\n");
                    body.append(lines[j]);
                }
                return body.toString();
            }
        }
        return raw;
    }

    // ==================== 运行时清单 + 环境依赖审查 ====================

    /** 收集所有技能包所需的运行时（去重，保持首次出现顺序）。 */
    private List<String> collectAllRequiredRuntimes() {
        Set<String> runtimes = new LinkedHashSet<>();
        for (ThirdPartySkill s : skills.values()) {
            if (s.isPackage() && !s.getRequiredRuntimes().isEmpty()) {
                runtimes.addAll(s.getRequiredRuntimes());
            }
        }
        return new ArrayList<>(runtimes);
    }

    /** 从 compatibility 字段文本中提取已知程序名（用于环境探测）。 */
    private List<String> extractProgramsFromCompatibility() {
        Set<String> progs = new LinkedHashSet<>();
        for (String compat : compatMap.values()) {
            String lower = compat.toLowerCase();
            for (String prog : PROGRAM_CHECK_CMD.keySet()) {
                if (lower.contains(prog)) {
                    progs.add(prog);
                }
            }
        }
        return new ArrayList<>(progs);
    }

    /**
     * 加载/注册完成后统一输出运行时清单提醒（含 compatibility 环境要求）。
     * 仅在清单变化时输出，避免监听重扫时重复刷屏。
     */
    private void logRequiredRuntimes() {
        List<String> runtimes = collectAllRequiredRuntimes();
        if (runtimes.equals(lastLoggedRuntimes)) return;  // 清单无变化则不重复提示
        if (runtimes.isEmpty()) return;
        lastLoggedRuntimes = runtimes;

        StringBuilder sb = new StringBuilder();
        sb.append("[AgentSkill] ⚠ 以下技能包包含辅助脚本，使用前需先安装对应运行时：\n");
        for (ThirdPartySkill s : skills.values()) {
            if (s.isPackage() && !s.getRequiredRuntimes().isEmpty()) {
                sb.append("  - ").append(s.getName()).append(" → ")
                  .append(String.join(", ", s.getRequiredRuntimes())).append("\n");
            }
        }
        sb.append("[AgentSkill] 运行时清单（去重）：").append(String.join(", ", runtimes));
        AiAgentActivity.debugLog(sb.toString());
    }

    /**
     * 环境依赖审查：探测所需运行时/程序在当前系统是否可用。
     * 异步执行（独立线程），区分已就绪/缺失，缺失的提示用户配置环境。
     */
    private void checkEnvironment() {
        List<String> runtimes = collectAllRequiredRuntimes();
        List<String> progs = extractProgramsFromCompatibility();
        progs.addAll(extractProgramsFromContent());  // 扫描技能包 md 内容中提到的程序名（如 curl/jq/python）
        if (runtimes.isEmpty() && progs.isEmpty()) return;

        final List<String> allChecks = new ArrayList<>(runtimes);
        allChecks.addAll(progs);

        Thread t = new Thread(() -> {
            Map<String, Boolean> status = new LinkedHashMap<>();
            for (String item : allChecks) {
                status.put(item, checkItem(item));
            }
            if (status.equals(lastEnvStatus)) return;  // 无变化则不重复提示
            lastEnvStatus = new LinkedHashMap<>(status);

            // 缺失依赖统一红字告知（所有技能包加载完成后集中输出）
            StringBuilder missing = new StringBuilder();
            for (Map.Entry<String, Boolean> e : status.entrySet()) {
                if (!e.getValue()) {
                    if (missing.length() > 0) missing.append("\n");
                    missing.append("  ✗ ").append(e.getKey()).append(" 未检测到，请安装对应运行时后重试");
                }
            }
            if (missing.length() > 0) {
                EdtUtils.println(FCM.Error_Color,
                        "\n[AgentSkill] ⚠ 技能包环境依赖缺失，以下运行时/程序未安装：\n" + missing + "\n");
            }
            for (Map.Entry<String, Boolean> e : status.entrySet()) {
                if (e.getValue()) {
                    AiAgentActivity.debugLog("[AgentSkill] ✓ " + e.getKey() + " 已就绪");
                }
            }
        }, "AgentSkill-EnvCheck");
        t.setDaemon(true);
        t.start();
    }

    /** 从技能包 SKILL.md 正文与 references 内容中提取已知程序名（用于环境探测）。 */
    private List<String> extractProgramsFromContent() {
        Set<String> progs = new LinkedHashSet<>();
        for (ThirdPartySkill s : skills.values()) {
            if (!s.isPackage()) continue;
            String main = readMainContent(s);
            if (main != null) scanProgramNames(main, progs);
            for (String rel : s.getAttachmentPaths().keySet()) {
                String content = readAttachment(s, rel);
                if (content != null) scanProgramNames(content, progs);
            }
        }
        return new ArrayList<>(progs);
    }

    /** 扫描文本中出现的已知程序名（curl/jq/python 等）。 */
    private void scanProgramNames(String text, Set<String> out) {
        if (text == null || text.isEmpty()) return;
        String lower = text.toLowerCase();
        for (String prog : PROGRAM_CHECK_CMD.keySet()) {
            if (lower.contains(prog)) out.add(prog);
        }
    }

    /** 探测单个运行时/程序是否可用（带缓存）。 */
    private boolean checkItem(String item) {
        CacheEntry cached = runtimeCheckCache.get(item);
        if (cached != null && !cached.isExpired()) return cached.available;
        String cmd = RUNTIME_CHECK_CMD.get(item);
        if (cmd == null) cmd = PROGRAM_CHECK_CMD.get(item);
        boolean ok = (cmd == null) || probe(cmd);  // 无法探测的默认视为可用
        runtimeCheckCache.put(item, new CacheEntry(ok));
        return ok;
    }

    /** 执行探测命令，捕获退出码（0=可用），3 秒超时。 */
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
