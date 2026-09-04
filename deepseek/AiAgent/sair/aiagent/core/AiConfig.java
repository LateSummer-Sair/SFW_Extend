package sair.aiagent.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.crypto.Cipher;
import java.security.SecureRandom;
import javax.crypto.spec.SecretKeySpec;

/**
 * 配置管理器 —— 单例模式。
 * API Key 以 AES-128 加密存储，防止明文泄露。
 * <p>提示词由 PromptManager 集中管理，通过 config.properties 持久化。</p>
 */
public class AiConfig {

    // ==================== 默认值 ====================

    /** DeepSeek 默认 API 地址 */
    public static final String DEFAULT_API_URL = "https://api.deepseek.com";

    /** 默认模型 */
    public static final String DEFAULT_MODEL = "auto";

    // 提示词默认值由 PromptManager 硬编码提供，config.properties 可覆盖

    // ==================== 单例 ====================

    private static volatile AiConfig instance;

    /** 私有构造，防止外部实例化 */
    private AiConfig() {}

    /**
     * 获取单例实例（双重检查锁定，线程安全）。
     * @return 配置管理器单例
     */
    public static AiConfig getInstance() {
        if (instance == null) {
            synchronized (AiConfig.class) {
                if (instance == null) {
                    instance = new AiConfig();
                }
            }
        }
        return instance;
    }

    // ==================== 配置字段 ====================

    private String apiKey    = "";
    private String apiUrl    = DEFAULT_API_URL;
    private String model     = DEFAULT_MODEL;

    // === OneBot QQ 配置 ===
    private int onebotPort = 5800;
    private String onebotToken = "";
    private boolean onebotEnabled = false;
    private long onebotSelfId = 0;
    
    /** 主人QQ号列表（具备execs权限） */
    private Set<Long> masterQQs = new LinkedHashSet<>();

    // === Redis 缓存配置（旁路缓存，未运行则静默降级，不影响主流程） ===
    private boolean redisEnabled = true;
    private String redisHost = "127.0.0.1";
    private int redisPort = 6379;
    private int redisDb = 1;                  // 与 YunzaiBot(db 0) 隔离
    private String redisPrefix = "aiagent:";
    private String redisPassword = "";

    // === DeepSeek API 高级参数 ===
    private String reasoningEffort = "high";  // 默认启用深度思考, low/high/max=启用, none/空=关闭
    private double temperature = -1;        // -1=不设置, 0~2.0
    private double topP = -1;               // -1=不设置
    private String stopSequences = "";      // 逗号分隔的停止词
    private String responseFormat = "";     // "json_object" 或空
    private int maxOutputTokens = 0;        // 0=使用默认值
    private String deepSeekUserId = "";     // 用于缓存隔离/内容安全
    private double frequencyPenalty = -1;    // -1=不设置, -2.0~2.0
    private double presencePenalty = -1;     // -1=不设置, -2.0~2.0
    /** Function Calling strict 模式（Beta）：切 /beta base_url + 严格 JSON Schema（additionalProperties:false） */
    private boolean strictMode = false;

    /** 主动查看功能是否启用 */
    private boolean proactiveCheckEnabled = false;

    /** 拟人化监听态是否启用（默认关闭；需手动开启，关闭时回到 execPool 并发处理消息） */
    private boolean listenStateEnabled = false;

    /** 三方技能代码段在 execq 通道的执行权限。默认 true=放权（所有通道可用，用户导入即视为刚需）；false=仅 execs/本地可用。 */
    private boolean thirdPartyCodeExecq = true;

    /** 监听的群号列表（逗号分隔） */
    private final Set<Long> monitoredGroups = new LinkedHashSet<>();

    /** 消息触发词列表（多个用 ; 分隔，用于群聊中提到任一触发词时触发回复；第一个作为主名字展示） */
    private String botName = "";
    /** 触发词拆分结果缓存（botName 变更时失效），避免每条消息重复 split。 */
    private volatile List<String> cachedTriggerWords;

    /** QQ 通道文件访问根目录（分号分隔；留空=不限制）。 */
    private String qqFileAccessRoots = "";

    /** 文件下载目录（接收主人发送的文件存储位置） */
    private String fileDownloadPath = "";

    /** 文件中转服务对外访问地址（供跨机器 NapCat 下载；空=自动探测局域网 IP） */
    private String fileServerHost = "";

    /** 文件中转服务端口（默认 2671） */
    private int fileServerPort = 2671;

    /** 配置文件路径（init后设置） */
    private File configFile;

    // ==================== 生命周期 ====================

    /**
     * 初始化：加载已有配置。
     * @param dataDir 插件数据目录路径
     */
    public void init(String dataDir) {
        this.configFile = new File(dataDir, "config.properties");
        load();
    }

    // ==================== 持久化 ====================

    /**
     * 从配置文件加载配置项。
     */
    public void load() {
        if (configFile == null || !configFile.exists()) return;
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            p.load(new InputStreamReader(fis, StandardCharsets.UTF_8));
            apiKey = decrypt(p.getProperty("apiKey", ""));
            apiUrl       = p.getProperty("apiUrl", apiUrl);
            model        = p.getProperty("model", model);
            // 提示词从 config.properties 追加到 PromptManager（不替换默认值）
            String sp = p.getProperty("systemPrompt", "");
            if (!sp.isEmpty()) {
                PromptManager.getInstance().appendSystemPrompt(sp);
            }
            String eqp = p.getProperty("execqPrompt", "");
            if (!eqp.isEmpty()) {
                PromptManager.getInstance().appendExecqPrompt(eqp);
            }
            // OneBot 配置
            onebotEnabled = "true".equalsIgnoreCase(p.getProperty("onebotEnabled", "false"));
            try { onebotPort = Integer.parseInt(p.getProperty("onebotPort", "5800")); } catch (NumberFormatException ignored) {}
            onebotToken = p.getProperty("onebotToken", "");
            try { onebotSelfId = Long.parseLong(p.getProperty("onebotSelfId", "0")); } catch (NumberFormatException ignored) {}
            // 主动查看配置
            proactiveCheckEnabled = "true".equalsIgnoreCase(p.getProperty("proactiveCheckEnabled", "false"));
            // 拟人化监听态（默认关闭，需手动开启）
            listenStateEnabled = "true".equalsIgnoreCase(p.getProperty("listenStateEnabled", "false"));
            // 三方技能代码段 execq 权限（默认放权 true，仅显式 false 才关闭）
            thirdPartyCodeExecq = !"false".equalsIgnoreCase(p.getProperty("thirdPartyCodeExecq", "true"));
            String groupsStr = p.getProperty("monitoredGroups", "");
            if (!groupsStr.isEmpty()) {
                monitoredGroups.clear();
                for (String item : groupsStr.split(",")) {
                    try {
                        monitoredGroups.add(Long.parseLong(item.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            // 主人QQ号列表（具备execs权限）
            String mastersStr = p.getProperty("masterQQs", "");
            if (!mastersStr.isEmpty()) {
                masterQQs.clear();
                for (String item : mastersStr.split(",")) {
                    try {
                        masterQQs.add(Long.parseLong(item.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            // Redis 缓存配置
            redisEnabled = "true".equalsIgnoreCase(p.getProperty("redisEnabled", "true"));
            redisHost = p.getProperty("redisHost", "127.0.0.1");
            try { redisPort = Integer.parseInt(p.getProperty("redisPort", "6379")); } catch (NumberFormatException ignored) {}
            try { redisDb = Integer.parseInt(p.getProperty("redisDb", "1")); } catch (NumberFormatException ignored) {}
            redisPrefix = p.getProperty("redisPrefix", "aiagent:");
            redisPassword = p.getProperty("redisPassword", "");
            // AI机器人名字
            botName = p.getProperty("botName", "");
            cachedTriggerWords = null;
            // QQ 通道文件访问根目录（分号分隔，留空=不限制）
            qqFileAccessRoots = p.getProperty("qqFileAccessRoots", "");
            // 文件下载目录
            fileDownloadPath = p.getProperty("fileDownloadPath", "");
            // 文件中转服务对外访问地址（空=自动探测局域网 IP）
            fileServerHost = p.getProperty("fileServerHost", "");
            try { fileServerPort = Integer.parseInt(p.getProperty("fileServerPort", "2671")); } catch (NumberFormatException ignored) {}
            // DeepSeek 高级参数
            reasoningEffort = p.getProperty("reasoningEffort", "high");
            try { temperature = Double.parseDouble(p.getProperty("temperature", "-1")); } catch (NumberFormatException ignored) {}
            try { topP = Double.parseDouble(p.getProperty("topP", "-1")); } catch (NumberFormatException ignored) {}
            stopSequences = p.getProperty("stopSequences", "");
            responseFormat = p.getProperty("responseFormat", "");
            try { maxOutputTokens = Integer.parseInt(p.getProperty("maxOutputTokens", "0")); } catch (NumberFormatException ignored) {}
            deepSeekUserId = p.getProperty("deepSeekUserId", "");
            try { frequencyPenalty = Double.parseDouble(p.getProperty("frequencyPenalty", "-1")); } catch (NumberFormatException ignored) {}
            try { presencePenalty = Double.parseDouble(p.getProperty("presencePenalty", "-1")); } catch (NumberFormatException ignored) {}
            strictMode = "true".equalsIgnoreCase(p.getProperty("strictMode", "false"));
        } catch (Exception e) {
            // 读取失败则使用默认值，并记录原因便于排查
            try {
                sair.aiagent.AiAgentActivity.debugLog("[AiConfig] 配置读取失败，使用默认值: " + e.toString());
            } catch (Exception ignored) {}
        }
        validateAndLog();
    }

    /** 加载后校验关键配置，非法值回退默认并输出日志。 */
    private void validateAndLog() {
        List<String> warnings = new ArrayList<>();
        if (apiUrl == null || apiUrl.trim().isEmpty()
                || (!apiUrl.startsWith("http://") && !apiUrl.startsWith("https://"))) {
            warnings.add("apiUrl 非法，回退默认");
            apiUrl = DEFAULT_API_URL;
        }
        if (model == null || model.trim().isEmpty()) {
            warnings.add("model 为空，回退 auto");
            model = DEFAULT_MODEL;
        }
        if (onebotPort < 1 || onebotPort > 65535) {
            warnings.add("onebotPort 非法，回退 5800");
            onebotPort = 5800;
        }
        if (onebotSelfId < 0) {
            warnings.add("onebotSelfId 非法，回退 0");
            onebotSelfId = 0;
        }
        if (redisPort < 1 || redisPort > 65535) {
            warnings.add("redisPort 非法，回退 6379");
            redisPort = 6379;
        }
        if (redisDb < 0 || redisDb > 15) {
            warnings.add("redisDb 非法，回退 1");
            redisDb = 1;
        }
        if (fileServerPort < 1 || fileServerPort > 65535) {
            warnings.add("fileServerPort 非法，回退 2671");
            fileServerPort = 2671;
        }
        if (temperature != -1 && (temperature < 0 || temperature > 2.0)) {
            warnings.add("temperature 越界，回退 -1");
            temperature = -1;
        }
        if (topP != -1 && (topP < 0 || topP > 1.0)) {
            warnings.add("topP 越界，回退 -1");
            topP = -1;
        }
        if (maxOutputTokens < 0) {
            warnings.add("maxOutputTokens 非法，回退 0");
            maxOutputTokens = 0;
        }
        if (frequencyPenalty != -1 && (frequencyPenalty < -2.0 || frequencyPenalty > 2.0)) {
            warnings.add("frequencyPenalty 越界，回退 -1");
            frequencyPenalty = -1;
        }
        if (presencePenalty != -1 && (presencePenalty < -2.0 || presencePenalty > 2.0)) {
            warnings.add("presencePenalty 越界，回退 -1");
            presencePenalty = -1;
        }
        try {
            if (warnings.isEmpty()) {
                sair.aiagent.AiAgentActivity.debugLog("[AiConfig] 配置校验通过: " + configFile.getAbsolutePath());
            } else {
                sair.aiagent.AiAgentActivity.debugLog("[AiConfig] 配置校验: " + String.join("; ", warnings));
            }
        } catch (Exception ignored) {}
    }

    /**
     * 将当前配置持久化到文件。
     */
    public void save() {
        if (configFile == null) return;
        try {
            configFile.getParentFile().mkdirs();
            Properties p = new Properties();
            p.setProperty("apiKey", encrypt(apiKey));
            p.setProperty("apiUrl",       apiUrl);
            p.setProperty("model",        model);
            p.setProperty("systemPrompt", PromptManager.getInstance().getSystemPromptExtra());
            // OneBot 配置
            p.setProperty("onebotEnabled", String.valueOf(onebotEnabled));
            p.setProperty("onebotPort",    String.valueOf(onebotPort));
            p.setProperty("onebotToken",   onebotToken);
            p.setProperty("onebotSelfId",  String.valueOf(onebotSelfId));
            p.setProperty("execqPrompt",   PromptManager.getInstance().getExecqPromptExtra());
            // 主动查看配置
            p.setProperty("proactiveCheckEnabled", String.valueOf(proactiveCheckEnabled));
            p.setProperty("listenStateEnabled", String.valueOf(listenStateEnabled));
            p.setProperty("thirdPartyCodeExecq", String.valueOf(thirdPartyCodeExecq));
            List<String> groupList = new ArrayList<>();
            for (Long g : monitoredGroups) {
                groupList.add(String.valueOf(g));
            }
            p.setProperty("monitoredGroups", String.join(",", groupList));
            // 主人QQ号列表（具备execs权限）
            List<String> masterList = new ArrayList<>();
            for (Long qq : masterQQs) {
                masterList.add(String.valueOf(qq));
            }
            p.setProperty("masterQQs", String.join(",", masterList));
            // Redis 缓存配置
            p.setProperty("redisEnabled", String.valueOf(redisEnabled));
            p.setProperty("redisHost", redisHost);
            p.setProperty("redisPort", String.valueOf(redisPort));
            p.setProperty("redisDb", String.valueOf(redisDb));
            p.setProperty("redisPrefix", redisPrefix);
            p.setProperty("redisPassword", redisPassword);
            // AI机器人名字
            p.setProperty("botName", botName);
            p.setProperty("qqFileAccessRoots", qqFileAccessRoots);
            p.setProperty("fileDownloadPath", fileDownloadPath);
            p.setProperty("fileServerHost", fileServerHost);
            p.setProperty("fileServerPort", String.valueOf(fileServerPort));
            // DeepSeek 高级参数
            p.setProperty("reasoningEffort", reasoningEffort);
            p.setProperty("temperature", String.valueOf(temperature));
            p.setProperty("topP", String.valueOf(topP));
            p.setProperty("stopSequences", stopSequences);
            p.setProperty("responseFormat", responseFormat);
            p.setProperty("maxOutputTokens", String.valueOf(maxOutputTokens));
            p.setProperty("deepSeekUserId", deepSeekUserId);
            p.setProperty("frequencyPenalty", String.valueOf(frequencyPenalty));
            p.setProperty("presencePenalty", String.valueOf(presencePenalty));
            p.setProperty("strictMode", String.valueOf(strictMode));
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                p.store(new OutputStreamWriter(fos, StandardCharsets.UTF_8),
                        "AiAgent Configuration");
            }
        } catch (Exception e) {
            // 保存失败记录日志，防止静默丢配置
            System.err.println("[AiConfig] 保存配置失败: " + e.toString());
            try {
                sair.aiagent.AiAgentActivity.debugLog("[AiConfig] save() FAILED: " + e.toString());
            } catch (Exception ignored) {}
        }
    }

    // ==================== Getters & Setters ====================

    public String getApiKey()          { return apiKey; }
    public String getApiUrl()          { return apiUrl; }

    /**
     * 获取实际请求用的 base_url。
     * <p>strict 模式（Beta）需切换到 {@code https://api.deepseek.com/beta}，
     * 否则服务端会对 strict JSON Schema 校验失败。</p>
     */
    public String getEffectiveApiUrl() {
        if (strictMode) {
            String base = apiUrl;
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            if (!base.endsWith("/beta")) {
                return base + "/beta";
            }
            return base;
        }
        return apiUrl;
    }
    public String getModel()           { return model; }

    /** 获取系统提示词（从 PromptManager） */
    public String getSystemPrompt()    {
        return PromptManager.getInstance().getSystemPrompt();
    }

    public void setApiKey(String apiKey) {
        this.apiKey = (apiKey != null) ? apiKey.trim() : "";
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = (apiUrl != null && !apiUrl.trim().isEmpty())
                ? apiUrl.trim() : DEFAULT_API_URL;
    }

    public void setModel(String model) {
        this.model = (model != null && !model.trim().isEmpty())
                ? model.trim() : DEFAULT_MODEL;
    }

    /** 设置系统提示词 */
    public void setSystemPrompt(String prompt) {
        if (prompt != null && !prompt.trim().isEmpty()) {
            PromptManager.getInstance().setSystemPrompt(prompt);
        }
    }

    // === OneBot Getters/Setters ===

    public int getOnebotPort()           { return onebotPort; }
    public void setOnebotPort(int port)  { this.onebotPort = port > 0 ? port : 5800; }

    public String getOnebotToken()             { return onebotToken; }
    public void setOnebotToken(String token)   { this.onebotToken = (token != null) ? token.trim() : ""; }

    public boolean isOnebotEnabled()            { return onebotEnabled; }
    public void setOnebotEnabled(boolean v)     { this.onebotEnabled = v; }

    public long getOnebotSelfId()               { return onebotSelfId; }
    public void setOnebotSelfId(long id)        { this.onebotSelfId = id; }
    
    /** 获取文件下载目录（优先返回配置值，否则根据系统自动选择），保证以分隔符结尾 */
    public String getFileDownloadPath() {
        String path;
        if (fileDownloadPath != null && !fileDownloadPath.isEmpty()) {
            path = fileDownloadPath;
        } else {
            // 自动检测系统默认下载目录
            String os = System.getProperty("os.name", "").toLowerCase();
            String home = System.getProperty("user.home", ".");
            path = os.contains("win") ? home + "\\Downloads" : home + "/Downloads";
        }
        // 确保以分隔符结尾，方便路径拼接
        if (!path.endsWith("/") && !path.endsWith("\\")) {
            path += java.io.File.separator;
        }
        return path;
    }
    
    /** 设置文件下载目录 */
    public void setFileDownloadPath(String path) { this.fileDownloadPath = (path != null) ? path.trim() : ""; }

    /** QQ 通道文件访问根目录（分号分隔；留空=不限制）。 */
    public String getQqFileAccessRoots() { return qqFileAccessRoots; }
    public void setQqFileAccessRoots(String roots) { this.qqFileAccessRoots = (roots != null) ? roots.trim() : ""; }

    /** 判断 QQ 通道是否允许访问指定路径；未配置根目录时默认放行。 */
    public boolean isQqFileAccessAllowed(String path) {
        if (qqFileAccessRoots == null || qqFileAccessRoots.trim().isEmpty()) return true;
        if (path == null || path.trim().isEmpty()) return false;
        java.io.File target = new java.io.File(path.trim());
        String abs;
        try { abs = target.getCanonicalPath(); } catch (Exception e) { abs = target.getAbsolutePath(); }
        for (String root : qqFileAccessRoots.split(";")) {
            String r = root.trim();
            if (r.isEmpty()) continue;
            try {
                String canonRoot = new java.io.File(r).getCanonicalPath();
                if (abs.equals(canonRoot) || abs.startsWith(canonRoot + java.io.File.separator)) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** 获取文件中转服务对外访问地址（空=自动探测局域网 IP） */
    public String getFileServerHost() { return fileServerHost; }
    /** 设置文件中转服务对外访问地址 */
    public void setFileServerHost(String host) { this.fileServerHost = (host != null) ? host.trim() : ""; }

    /** 获取文件中转服务端口 */
    public int getFileServerPort() { return fileServerPort; }
    /** 设置文件中转服务端口（范围 1~65535，非法回退默认 2671） */
    public void setFileServerPort(int port) { this.fileServerPort = (port > 0 && port <= 65535) ? port : 2671; }

    // ==================== 模型智能路由 (v2.4) ====================
    
    /** 默认 execq/chat 模型（轻量快速） */
    private static final String DEFAULT_EXECQ_MODEL = "deepseek-v4-flash";
    /** 默认 agent 模型（深度推理） */
    private static final String DEFAULT_AGENT_MODEL = "deepseek-v4-pro";
    /** 默认 vision 模型（多模态图像理解，替代已移除的在线 OCR） */
    private static final String DEFAULT_VISION_MODEL = "deepseek-v4-flash-vision-exp";
    /** auto 模式标记 */
    private static final String AUTO_MODEL = "auto";
    
    /**
     * 获取 execq/Chat 通道应使用的模型。
     * <p>model=auto 时返回 flash（轻量快速），否则严格遵守配置值。</p>
     */
    public String getExecqModel() {
        if (AUTO_MODEL.equalsIgnoreCase(model)) {
            logModelRoute("Chat/Execq", DEFAULT_EXECQ_MODEL);
            return DEFAULT_EXECQ_MODEL;
        }
        logModelRoute("Chat/Execq", model);
        return model;
    }
    
    /**
     * 获取 Chat（本地对话）通道应使用的模型。
     * <p>model=auto 时返回 flash（轻量快速），否则严格遵守配置值。</p>
     */
    public String getChatModel() {
        if (AUTO_MODEL.equalsIgnoreCase(model)) {
            logModelRoute("Chat（本地）", DEFAULT_EXECQ_MODEL);
            return DEFAULT_EXECQ_MODEL;
        }
        logModelRoute("Chat（本地）", model);
        return model;
    }

    /**
     * 获取 Agent(exec/execs) 通道应使用的模型。
     * <p>model=auto 时返回 pro（深度推理），否则严格遵守配置值。</p>
     */
    public String getAgentModel() {
        if (AUTO_MODEL.equalsIgnoreCase(model)) {
            logModelRoute("Agent", DEFAULT_AGENT_MODEL);
            return DEFAULT_AGENT_MODEL;
        }
        logModelRoute("Agent", model);
        return model;
    }

    /**
     * 获取 Vision（多模态图像理解）模型名。
     * <p>按需识图：仅当用户「引用图片」或明确「看图指令」时才调用该模型分析图像内容。
     * 识别结果由 {@code FunctionCallingBridge} 以 [图片内容识别结果] 注入主模型上下文。</p>
     */
    public String getVisionModel() {
        return DEFAULT_VISION_MODEL;
    }

    /** 模型路由日志（仅在 model=auto 时输出提示） */
    private static void logModelRoute(String channel, String resolved) {
        if (AUTO_MODEL.equalsIgnoreCase(getInstance().model)) {
            String msg = "[模型路由] auto模式: " + channel + "通道使用 " + resolved;
            System.out.println(msg);
            try { sair.aiagent.AiAgentActivity.debugLog(msg); } catch (Exception ignored) {}
        }
    }
    
    /** 获取主人QQ号列表 */
    public Set<Long> getMasterQQs()             { return Collections.unmodifiableSet(masterQQs); }
    
    /** 添加主人QQ号 */
    public void addMasterQQ(long qq)            { masterQQs.add(qq); }
    
    /** 移除主人QQ号 */
    public void removeMasterQQ(long qq)         { masterQQs.remove(qq); }
    
    /** 检查是否是主人 */
    public boolean isMasterQQ(long qq)          { return masterQQs.contains(qq); }

    // === Redis Getters/Setters ===

    public boolean isRedisEnabled()                { return redisEnabled; }
    public void setRedisEnabled(boolean v)         { this.redisEnabled = v; }

    public String getRedisHost()                   { return redisHost; }
    public void setRedisHost(String v)             { this.redisHost = (v != null && !v.trim().isEmpty()) ? v.trim() : "127.0.0.1"; }

    public int getRedisPort()                      { return redisPort; }
    public void setRedisPort(int v)                { this.redisPort = v > 0 ? v : 6379; }

    public int getRedisDb()                        { return redisDb; }
    public void setRedisDb(int v)                  { this.redisDb = (v >= 0 && v <= 15) ? v : 1; }

    public String getRedisPrefix()                 { return redisPrefix; }
    public void setRedisPrefix(String v)           { this.redisPrefix = (v != null) ? v : "aiagent:"; }

    public String getRedisPassword()               { return redisPassword; }
    public void setRedisPassword(String v)         { this.redisPassword = (v != null) ? v.trim() : ""; }
    
    /** 设置主人QQ号列表（从配置文件加载） */
    public void setMasterQQs(Set<Long> qqList)  { 
        this.masterQQs.clear();
        if (qqList != null) {
            this.masterQQs.addAll(qqList);
        }
    }

    /** 获取 execq QQ通道提示词 */
    public String getExecqPrompt()                {
        return PromptManager.getInstance().getExecqPrompt();
    }

    /** 设置 execq 提示词 */
    public void setExecqPrompt(String prompt)     {
        if (prompt != null && !prompt.trim().isEmpty()) {
            PromptManager.getInstance().setExecqPrompt(prompt);
        }
    }

    /** @return API Key 是否已设置 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    // === 主动查看配置 ===

    public boolean isProactiveCheckEnabled() { return proactiveCheckEnabled; }
    public void setProactiveCheckEnabled(boolean v) { this.proactiveCheckEnabled = v; }

    public boolean isListenStateEnabled() { return listenStateEnabled; }
    public void setListenStateEnabled(boolean v) { this.listenStateEnabled = v; }

    public boolean isThirdPartyCodeExecq() { return thirdPartyCodeExecq; }
    public void setThirdPartyCodeExecq(boolean v) { this.thirdPartyCodeExecq = v; }

    public Set<Long> getMonitoredGroups() { return Collections.unmodifiableSet(monitoredGroups); }
    public boolean addMonitoredGroup(long groupId) { return monitoredGroups.add(groupId); }
    public boolean removeMonitoredGroup(long groupId) { return monitoredGroups.remove(groupId); }

    // === AI机器人名字 / 消息触发词 ===

    /** 获取主名字（第一个触发词），用于展示（转发卡片/提示词等）；无则返回空串 */
    public String getBotName() {
        List<String> words = getTriggerWords();
        return words.isEmpty() ? "" : words.get(0);
    }

    /** 获取原始触发词存储串（多个用 ; 分隔，用于配置展示） */
    public String getBotNameRaw() {
        return botName != null ? botName.trim() : "";
    }

    /** 获取所有触发词（按 ; 拆分，去空白去空项；结果缓存，botName 变更时失效） */
    public List<String> getTriggerWords() {
        List<String> cached = cachedTriggerWords;
        if (cached != null) return cached;
        List<String> words = new ArrayList<>();
        if (botName != null && !botName.trim().isEmpty()) {
            for (String w : botName.split(";")) {
                String t = w.trim();
                if (!t.isEmpty()) words.add(t);
            }
        }
        cachedTriggerWords = words;
        return words;
    }

    /** 判断文本是否命中任一触发词（用于群聊中@之外的触发检测） */
    public boolean matchesTrigger(String text) {
        if (text == null || text.isEmpty()) return false;
        List<String> words = getTriggerWords();
        if (words.isEmpty()) return false;
        for (String w : words) {
            if (text.contains(w)) return true;
        }
        return false;
    }

    /** 设置触发词（多个用 ; 分隔，自动去空白、去空项、去重后重新拼接存储） */
    public void setBotName(String name) {
        if (name == null) { this.botName = ""; cachedTriggerWords = null; return; }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String w : name.split(";")) {
            String t = w.trim();
            if (!t.isEmpty()) seen.add(t);
        }
        this.botName = String.join(";", seen);
        cachedTriggerWords = null;
    }

    // === DeepSeek API 高级参数 Getters/Setters ===

    /** 获取思考模式强度（空=不启用, low/high/xhigh/max） */
    public String getReasoningEffort() { return reasoningEffort; }
    public void setReasoningEffort(String v) { this.reasoningEffort = (v != null) ? v.trim() : ""; }

    /** 获取 temperature（-1=不设置） */
    public double getTemperature() { return temperature; }
    public void setTemperature(double v) { this.temperature = v; }

    /** 获取 top_p（-1=不设置） */
    public double getTopP() { return topP; }
    public void setTopP(double v) { this.topP = v; }

    /** 获取停止序列（逗号分隔） */
    public String getStopSequences() { return stopSequences; }
    public void setStopSequences(String v) { this.stopSequences = (v != null) ? v.trim() : ""; }

    /** 获取响应格式（"json_object" 或空） */
    public String getResponseFormat() { return responseFormat; }
    public void setResponseFormat(String v) { this.responseFormat = (v != null) ? v.trim() : ""; }

    /** 获取最大输出 token 数（0=使用默认值） */
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int v) { this.maxOutputTokens = Math.max(0, v); }

    /** 获取 DeepSeek user_id（用于缓存隔离和内容安全） */
    public String getDeepSeekUserId() { return deepSeekUserId; }
    public void setDeepSeekUserId(String v) { this.deepSeekUserId = (v != null) ? v.trim() : ""; }

    public double getFrequencyPenalty() { return frequencyPenalty; }
    public void setFrequencyPenalty(double v) { this.frequencyPenalty = Math.max(-2.0, Math.min(2.0, v)); }
    public double getPresencePenalty() { return presencePenalty; }
    public void setPresencePenalty(double v) { this.presencePenalty = Math.max(-2.0, Math.min(2.0, v)); }

    /** Function Calling strict 模式（Beta）开关 */
    public boolean isStrictMode() { return strictMode; }
    public void setStrictMode(boolean v) { this.strictMode = v; }

    // ==================== 工具方法 ====================

    /**
     * 生成脱敏后的 API Key 用于显示。
     * <pre>"sk-1234567890abcdef" -> "sk-1****cdef"</pre>
     * @return 脱敏后的 Key，未设置返回 "(未设置)"
     */
    public String getMaskedKey() {
        if (apiKey == null || apiKey.isEmpty()) return "(未设置)";
        if (apiKey.length() <= 8) return "****";
        return apiKey.substring(0, 4) + "****"
             + apiKey.substring(apiKey.length() - 4);
    }

    // ==================== AES 加密 ====================

    /**
     * AES 密钥派生种子 —— 基于多因子组合，防止仅靠反编译获取密钥。
     * <p>种子来源：系统属性 + 机器名 + 用户目录，三者组合后取 hash 作为密钥基础。
     * 重启后所有因子不变，密钥稳定。</p>
     */
    /** GCM IV length (12 bytes recommended by NIST) */
    private static final int GCM_IV_LEN = 12;
    /** GCM tag length in bits */
    private static final int GCM_TAG_LEN = 128;

    private static final byte[] AES_KEY = deriveAesKey();

    private static byte[] deriveAesKey() {
        try {
            java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
            sha256.update(System.getProperty("java.vm.name", "").getBytes(StandardCharsets.UTF_8));
            sha256.update(System.getProperty("os.arch", "").getBytes(StandardCharsets.UTF_8));
            sha256.update(System.getProperty("user.name", "").getBytes(StandardCharsets.UTF_8));
            try { sha256.update(java.net.InetAddress.getLocalHost().getHostName().getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
            sha256.update(System.getProperty("user.dir", "").getBytes(StandardCharsets.UTF_8));
            sha256.update("AiAgent@SFW2024!".getBytes(StandardCharsets.UTF_8));
            byte[] fullHash = sha256.digest();
            // 取前16字节作为 AES-128 密钥
            byte[] key = new byte[16];
            System.arraycopy(fullHash, 0, key, 0, 16);
            return key;
        } catch (Exception e) {
            // 回退：绝不会发生，SHA-256 是所有 JVM 的必备算法
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static byte[] getAesKey() {
        return AES_KEY;
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = new byte[GCM_IV_LEN];
            SECURE_RANDOM.nextBytes(iv);
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LEN, iv);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(getAesKey(), "AES"), spec);
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            // IV(12) + ciphertext
            byte[] combined = new byte[GCM_IV_LEN + ct.length];
            System.arraycopy(iv, 0, combined, 0, GCM_IV_LEN);
            System.arraycopy(ct, 0, combined, GCM_IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) { return ""; }
    }

    private static String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return "";
        try {
            byte[] combined = Base64.getDecoder().decode(encrypted);
            if (combined.length < GCM_IV_LEN + 16) {
                // Too short for GCM → try legacy ECB fallback
                return decryptLegacyEcb(encrypted);
            }
            byte[] iv = new byte[GCM_IV_LEN];
            byte[] ct = new byte[combined.length - GCM_IV_LEN];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LEN);
            System.arraycopy(combined, GCM_IV_LEN, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            javax.crypto.spec.GCMParameterSpec spec = new javax.crypto.spec.GCMParameterSpec(GCM_TAG_LEN, iv);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(getAesKey(), "AES"), spec);
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // GCM failed → try legacy ECB for old configs
            return decryptLegacyEcb(encrypted);
        }
    }

    /** Legacy AES/ECB decrypt for old config.properties compatibility */
    private static String decryptLegacyEcb(String encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(getAesKey(), "AES"));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
}
