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
    /**
     * ROOT —— <b>主人 = 系统 root</b>：任何工具、任何通道、任何门槛（好感度 / 群内限制 /
     * 群里与否 / 代码安全黑名单）一律无条件放行。
     * <p>这是「主人」权限的正式定义：主人不是「比其他人高一级的角色」，而是像 Linux root 一样
     * 不受闸门约束。矩阵里写 {@code MASTER} 与 {@code ROOT} 等价（后者语义更清楚，推荐新配置使用）。
     * 放行仍然<b>留审计日志</b>（root 操作也要可追溯），且只在 QQ 通道记录，避免本地通道刷屏。</p>
     */
    public static final String LEVEL_ROOT         = "ROOT";
    /** 主人专属级别（{@link #LEVEL_ROOT} 的向后兼容别名）。 */
    public static final String LEVEL_MASTER       = "MASTER";
    public static final String LEVEL_GROUP_MASTER = "GROUP_MASTER";
    public static final String LEVEL_MEDIA        = "MEDIA";
    public static final String LEVEL_ANY          = "ANY";
    public static final String AFFECTION_PREFIX   = "AFFECTION:";
    /**
     * 三方技能（{@code tp_*}）动态工具的默认权限位：好感度 ≥300（与发文件/发语音同级）。
     * <p>这些工具会执行 .md 里内嵌的 Java/JS/node/python 代码，以前不在权限矩阵里 →
     * 落到 {@code LEVEL_ANY}，等于任何群成员都能让宿主执行任意代码。单个技能可用
     * front matter 的 {@code airun.permission} 覆盖。</p>
     */
    public static final String LEVEL_TP_DEFAULT   = AFFECTION_PREFIX + "300";

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
        permissionMatrix.put("sendlike",      LEVEL_MEDIA);
        permissionMatrix.put("relay",         LEVEL_MEDIA);
        // 好感度门槛（AFFECTION:N）
        permissionMatrix.put("setcard",          "AFFECTION:200");
        permissionMatrix.put("approvefriend",    "AFFECTION:100");
        permissionMatrix.put("acceptgroupinvite","AFFECTION:300");
        permissionMatrix.put("sendfile",         "AFFECTION:300");
        permissionMatrix.put("sendfileto",       "AFFECTION:300");
        permissionMatrix.put("sendrecord",       "AFFECTION:300");

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
     * 校验工具调用权限。返回 null 表示放行，否则返回阻断信息。
     * <p>判定顺序：</p>
     * <ol>
     *   <li><b>ROOT</b>：调用者是主人 → 无条件放行（所有闸门短路，等价 Linux root）；</li>
     *   <li>本地 console 通道（{@code napcatApi == null}）→ 全信任放行；</li>
     *   <li>否则按权限位判定：ROOT/MASTER=仅主人、GROUP_MASTER=群内主人、
     *       MEDIA=主人或好感度&gt;600、AFFECTION:N=主人或好感度≥N、ANY=不限；</li>
     *   <li>{@code tp_*} 动态工具未在矩阵里 → 默认 {@link #LEVEL_TP_DEFAULT}（好感度≥300），
     *       可由技能 front matter 的 {@code airun.permission} 覆盖。</li>
     * </ol>
     */
    public String checkPermission(String toolName, ToolContext ctx) {
        return checkPermission(toolName, ctx, null);
    }

    /**
     * 校验工具调用权限（可指定权限位覆盖，供 {@code tp_*} 动态工具使用）。
     *
     * @param declaredLevel 技能自己在 front matter 里声明的权限位；为空则查矩阵/默认值
     */
    public String checkPermission(String toolName, ToolContext ctx, String declaredLevel) {
        if (toolName == null) return null;
        // ROOT：主人不受任何闸门约束（Linux root 语义）。仍然留审计。
        if (ctx != null && ctx.isMaster) {
            if (ctx.isExecq()) {
                AiAgentActivity.debugLog("[ROOT] 主人调用 " + toolName + " —— 权限闸门全部放行（ROOT 语义）");
            }
            return null;
        }
        if (ctx == null || ctx.napcatApi == null) return null; // 本地通道全信任
        // ★ 内置工具名（权限矩阵里有明确条目）以矩阵为准，技能声明的 permission 不能放宽它 ——
        //   否则「加一个 tool: settrigger 的 .md 并写 permission: ANY」就能绕掉仅主人的门禁。
        //   技能自己新造的 tp_* 名字才由它自己声明权限位。
        String level;
        if (permissionMatrix.containsKey(toolName)) {
            level = permissionMatrix.get(toolName);
            if (declaredLevel != null && !declaredLevel.trim().isEmpty()
                    && !level.equals(declaredLevel.trim())) {
                AiAgentActivity.debugLog("[harness] " + toolName + " 的权限以矩阵为准（"
                        + level + "），忽略技能声明的 " + declaredLevel.trim());
            }
        } else {
            level = (declaredLevel != null && !declaredLevel.trim().isEmpty())
                    ? declaredLevel.trim()
                    : defaultLevelFor(toolName);
        }
        if (LEVEL_ANY.equals(level)) return null;

        switch (level) {
            case LEVEL_ROOT:
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

    /** 未在矩阵中登记的工具的默认权限位（三方技能默认要好感度，其他工具仍为不限制）。 */
    private static String defaultLevelFor(String toolName) {
        if (toolName != null && toolName.startsWith("tp_")) return LEVEL_TP_DEFAULT;
        return LEVEL_ANY;
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
            ContextStats.count(ContextStats.C_RESULT_TRUNCATED);
            return result.substring(0, limit)
                    + "\n...[harness] 结果过长已截断(" + result.length() + " → " + limit + ")";
        }
        return result;
    }

    /** 按工具类型收紧结果长度，避免 readfile/web/search 等大文本占用过多 token。 */
    public int getMaxResultLengthForTool(String toolName) {
        if ("readfile".equals(toolName)) {
            // readfile 读文件允许更大（代码/文档文件常见几万字符），超大文件配合 offset/limit 分块读取
            return Math.min(maxResultLength, 20000);
        }
        if ("web".equals(toolName) || "search".equals(toolName)) {
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
