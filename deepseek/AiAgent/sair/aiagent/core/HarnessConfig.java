package sair.aiagent.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sair.aiagent.AiAgentActivity;

/**
 * HarnessConfig —— Harness 化升级的「确定性约束」配置中心。
 *
 * <p>把原本散落在 {@link ToolDispatcher} 中的硬编码权限校验（isMasterOnly /
 * isGroupMaster / isMediaAllowed / 好感度门槛）迁移为 <b>代码化配置</b>，由
 * {@code data/harness.json} 集中声明。{@link ToolDispatcher} 不再保留兜底硬编码，
 * 权限统一收敛到本类（单一权限来源，避免重复拦截文案消耗 token）。</p>
 *
 * <h3>配置文件结构（data/harness.json，可选，缺失时回退内置默认值）</h3>
 * <pre>{@code
 * {
 *   "permissionMatrix": {
 *     "sendfile": "MASTER",
 *     "ban": "GROUP_MASTER",
 *     "sendimage": "MEDIA",
 *     "setcard": "AFFECTION:200"
 *   },
 *   "forbiddenCodePatterns": ["processbuilder", "runtime.getruntime", "system.exit"],
 *   "maxResultLength": 12000,
 *   "requireNonEmptyTools": ["web", "search", "readfile"]
 * }
 * }</pre>
 *
 * <p>权限级别语义：{@code MASTER}=仅主人、{@code GROUP_MASTER}=仅群内主人、
 * {@code MEDIA}=主人或好感度&gt;600、{@code AFFECTION:N}=主人或好感度≥N、
 * {@code ANY}=无限制。支持 {@link #reloadIfChanged()} 热加载（mtime 变化检测）。</p>
 */
public class HarnessConfig {

    // ==================== 权限级别常量 ====================
    public static final String LEVEL_MASTER       = "MASTER";
    public static final String LEVEL_GROUP_MASTER = "GROUP_MASTER";
    public static final String LEVEL_MEDIA        = "MEDIA";
    public static final String LEVEL_ANY          = "ANY";
    public static final String AFFECTION_PREFIX   = "AFFECTION:";

    // ==================== 单例 ====================
    private static volatile HarnessConfig instance;

    private HarnessConfig() {}

    /** 获取单例（双重检查锁定）。 */
    public static HarnessConfig getInstance() {
        if (instance == null) {
            synchronized (HarnessConfig.class) {
                if (instance == null) instance = new HarnessConfig();
            }
        }
        return instance;
    }

    // ==================== 状态 ====================
    private volatile File configFile;
    private volatile long lastLoadedMtime = -1;

    // 工具 → 权限级别（默认值即当前硬编码语义）
    private final Map<String, String> permissionMatrix = new LinkedHashMap<>();
    // 高危代码模式（execq 通道 eval/evaljs/sys 的确定性拦截）
    private final List<String> forbiddenCodePatterns = new ArrayList<>();
    // 结果校验
    private volatile int maxResultLength = 12000;
    private final List<String> requireNonEmptyTools = new ArrayList<>();
    // 验证闭环开关（CriticAgent）：默认关闭（opt-in），开启后每次最终回复增加一次独立审查
    private volatile boolean criticEnabled = false;

    // ==================== 生命周期 ====================

    /** 初始化：设定配置文件路径并加载。幂等（仅首次生效）。 */
    public void init(String dataDir) {
        if (configFile != null) return;
        this.configFile = new File(dataDir, "harness.json");
        applyDefaults();
        load();
    }

    /** 应用内置默认确定性约束（配置文件缺失时的兜底）。 */
    private void applyDefaults() {
        // === 权限矩阵默认值（镜像当前硬编码语义） ===
        // 仅主人（MASTER）
        permissionMatrix.put("sendfile",      LEVEL_MASTER);
        permissionMatrix.put("block",         LEVEL_MASTER);
        permissionMatrix.put("unblock",       LEVEL_MASTER);
        permissionMatrix.put("delfriend",     LEVEL_MASTER);
        permissionMatrix.put("forwardmsg",    LEVEL_MASTER);
        permissionMatrix.put("sendgroupmsg",  LEVEL_MASTER);
        permissionMatrix.put("setaffection",  LEVEL_MASTER);
        permissionMatrix.put("recorddonation",LEVEL_MASTER);
        permissionMatrix.put("resetdonation", LEVEL_MASTER);
        permissionMatrix.put("setname",       LEVEL_MASTER);
        permissionMatrix.put("setsignature",  LEVEL_MASTER);
        permissionMatrix.put("settrigger",    LEVEL_MASTER);
        // 仅群内主人（GROUP_MASTER）
        permissionMatrix.put("ban",           LEVEL_GROUP_MASTER);
        permissionMatrix.put("kick",          LEVEL_GROUP_MASTER);
        permissionMatrix.put("muteall",       LEVEL_GROUP_MASTER);
        permissionMatrix.put("setadmin",      LEVEL_GROUP_MASTER);
        permissionMatrix.put("setgroupname",  LEVEL_GROUP_MASTER);
        permissionMatrix.put("leavegroup",    LEVEL_GROUP_MASTER);
        // 主人或好感度>600（MEDIA）
        permissionMatrix.put("sendimage",     LEVEL_MEDIA);
        permissionMatrix.put("sendrecord",    LEVEL_MEDIA);
        permissionMatrix.put("sendlike",      LEVEL_MEDIA);
        permissionMatrix.put("relay",         LEVEL_MEDIA);
        permissionMatrix.put("sendfileto",    LEVEL_MEDIA);
        // 好感度门槛（AFFECTION:N）
        permissionMatrix.put("setcard",          "AFFECTION:200");
        permissionMatrix.put("approvefriend",    "AFFECTION:100");
        permissionMatrix.put("acceptgroupinvite","AFFECTION:300");

        // === 高危代码模式（确定性拦截，仅 execq 非主人通道强制） ===
        forbiddenCodePatterns.clear();
        forbiddenCodePatterns.add("processbuilder");
        forbiddenCodePatterns.add("runtime.getruntime");
        forbiddenCodePatterns.add("runtime.exec");
        forbiddenCodePatterns.add("system.exit");
        forbiddenCodePatterns.add("class.forname");
        forbiddenCodePatterns.add("file.delete");
        forbiddenCodePatterns.add("files.delete");
        forbiddenCodePatterns.add(".exec(");
        forbiddenCodePatterns.add("reflect");

        // === 结果校验 ===
        maxResultLength = 12000;
        requireNonEmptyTools.clear();
        requireNonEmptyTools.add("web");
        requireNonEmptyTools.add("search");
        requireNonEmptyTools.add("readfile");

        // === 验证闭环（默认关闭，opt-in） ===
        criticEnabled = false;
    }

    /** 从配置文件加载，覆盖默认值。解析失败则静默回退默认值。 */
    public synchronized void load() {
        if (configFile == null || !configFile.exists()) return;
        try (FileInputStream fis = new FileInputStream(configFile)) {
            JsonElement root = JsonParser.parseReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
            if (root == null || !root.isJsonObject()) {
                AiAgentActivity.debugLog("[HarnessConfig] 配置文件不是 JSON 对象，使用默认约束: " + configFile.getAbsolutePath());
                return;
            }
            JsonObject obj = root.getAsJsonObject();

            // 权限矩阵
            JsonElement pm = obj.get("permissionMatrix");
            if (pm != null && pm.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : pm.getAsJsonObject().entrySet()) {
                    String level = e.getValue().isJsonPrimitive()
                            ? e.getValue().getAsString().trim().toUpperCase() : "";
                    if (!level.isEmpty()) {
                        permissionMatrix.put(e.getKey(), level);
                    }
                }
            }

            // 高危代码模式
            JsonElement fp = obj.get("forbiddenCodePatterns");
            if (fp != null && fp.isJsonArray()) {
                forbiddenCodePatterns.clear();
                for (JsonElement item : fp.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        String p = item.getAsString().trim().toLowerCase();
                        if (!p.isEmpty()) forbiddenCodePatterns.add(p);
                    }
                }
            }

            // 结果校验
            JsonElement ml = obj.get("maxResultLength");
            if (ml != null && ml.isJsonPrimitive()) {
                try { maxResultLength = ml.getAsInt(); } catch (Exception ignored) {}
                if (maxResultLength <= 0) maxResultLength = 12000;
            }
            JsonElement ce = obj.get("criticEnabled");
            if (ce != null && ce.isJsonPrimitive()) {
                try { criticEnabled = ce.getAsBoolean(); } catch (Exception ignored) {}
            }
            JsonElement rn = obj.get("requireNonEmptyTools");
            if (rn != null && rn.isJsonArray()) {
                requireNonEmptyTools.clear();
                for (JsonElement item : rn.getAsJsonArray()) {
                    if (item.isJsonPrimitive()) {
                        String t = item.getAsString().trim();
                        if (!t.isEmpty()) requireNonEmptyTools.add(t);
                    }
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[HarnessConfig] 配置解析失败，使用默认约束: " + e.toString());
        } finally {
            lastLoadedMtime = configFile.lastModified();
        }
    }

    /** 热加载：文件 mtime 变化时重新加载，返回是否发生重载。 */
    public boolean reloadIfChanged() {
        if (configFile == null || !configFile.exists()) return false;
        long mtime = configFile.lastModified();
        if (mtime != lastLoadedMtime) {
            load();
            return true;
        }
        return false;
    }

    // ==================== 权限校验（确定性第一道闸门） ====================

    /**
     * 校验工具调用权限。仅在 QQ 上下文（{@code napcatApi != null}）强制，
     * console 本地通道视为全信任直接放行。返回 null 表示放行，否则返回阻断信息。
     */
    public String checkPermission(String toolName, ToolContext ctx) {
        if (toolName == null) return null;
        if (ctx == null || ctx.napcatApi == null) return null; // 本地通道全信任
        String level = permissionMatrix.getOrDefault(toolName, LEVEL_ANY);
        if (LEVEL_ANY.equals(level)) return null;

        switch (level) {
            case LEVEL_MASTER:
                if (!ctx.isMaster) return "[harness] 权限阻断：" + toolName + " 仅主人可用";
                return null;
            case LEVEL_GROUP_MASTER:
                if (!(ctx.isMaster && ctx.qqMsg != null && ctx.qqMsg.isGroupMessage())) {
                    return "[harness] 权限阻断：" + toolName + " 仅群内主人可用";
                }
                return null;
            case LEVEL_MEDIA:
                if (!(ctx.isMaster || ctx.affection > 600)) {
                    return "[harness] 权限阻断：" + toolName + " 需主人或好感度>600";
                }
                return null;
            default:
                if (level.startsWith(AFFECTION_PREFIX)) {
                    int min;
                    try { min = Integer.parseInt(level.substring(AFFECTION_PREFIX.length()).trim()); }
                    catch (NumberFormatException e) { return null; }
                    if (!ctx.isMaster && ctx.affection < min) {
                        return "[harness] 权限阻断：" + toolName + " 需好感度≥" + min + "或主人";
                    }
                }
                return null;
        }
    }

    // ==================== 代码安全（确定性约束） ====================

    /**
     * 高危代码模式拦截：仅对 execq 非主人通道强制（安全边界）。
     * 命中任一禁用模式即阻断，防止 AI 通过动态注入绕过权限。
     */
    public String checkCodeSafety(String toolName, String argumentsJson, ToolContext ctx) {
        if (ctx == null || !ctx.isExecq() || ctx.isMaster) return null; // 主人/本地全信任
        if (!("eval".equals(toolName) || "evaljs".equals(toolName) || "sys".equals(toolName))) {
            return null;
        }
        String code = FunctionCallingBridge.extractArg(argumentsJson,
                "code".equals(toolName) || "evaljs".equals(toolName) ? "code" : "command");
        if (code == null || code.trim().isEmpty()) return null;
        String c = code.toLowerCase();
        for (String pattern : forbiddenCodePatterns) {
            if (c.contains(pattern)) {
                return "[harness] 代码安全阻断：" + toolName + " 含禁用模式 '" + pattern + "'";
            }
        }
        return null;
    }

    // ==================== 结果校验（后置钩子） ====================

    /** 校验/规整工具执行结果：空结果标注、超长截断。 */
    public String validateResult(String toolName, String result) {
        if (result == null) {
            return "[harness] 工具 " + toolName + " 返回空结果";
        }
        if (requireNonEmptyTools.contains(toolName) && result.trim().isEmpty()) {
            return "[harness] 工具 " + toolName + " 返回空内容";
        }
        int limit = getMaxResultLengthForTool(toolName);
        if (result.length() > limit) {
            return result.substring(0, limit)
                    + "\n...[harness] 结果过长已截断(" + result.length() + " → " + limit + ")";
        }
        return result;
    }

    /** 按工具类型收紧结果长度，避免 readfile/web/search 等大文本占用过多 token。 */
    public int getMaxResultLengthForTool(String toolName) {
        if ("readfile".equals(toolName) || "web".equals(toolName) || "search".equals(toolName)) {
            return Math.min(maxResultLength, 6000);
        }
        if ("readdir".equals(toolName) || "findfile".equals(toolName) || "searchglobal".equals(toolName)) {
            return Math.min(maxResultLength, 8000);
        }
        return maxResultLength;
    }

    // ==================== Getter（供外部查询/热加载/调试） ====================

    public String getPermissionLevel(String toolName) {
        return permissionMatrix.getOrDefault(toolName, LEVEL_ANY);
    }

    public Map<String, String> getPermissionMatrix() {
        return new LinkedHashMap<>(permissionMatrix);
    }

    public List<String> getForbiddenCodePatterns() {
        return new ArrayList<>(forbiddenCodePatterns);
    }

    public int getMaxResultLength() {
        return maxResultLength;
    }

    /** 验证闭环（CriticAgent）是否开启。默认 false，需 opt-in 开启。 */
    public boolean isCriticEnabled() {
        return criticEnabled;
    }

    /** 运行时切换验证闭环开关（供 /criticon /criticoff 命令调用，内存态不落盘）。 */
    public void setCriticEnabled(boolean enabled) {
        this.criticEnabled = enabled;
    }
}
