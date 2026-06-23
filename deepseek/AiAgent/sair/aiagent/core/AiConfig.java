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
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";

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

    /** 主动查看功能是否启用 */
    private boolean proactiveCheckEnabled = false;

    /** 监听的群号列表（逗号分隔） */
    private final Set<Long> monitoredGroups = new LinkedHashSet<>();

    /** AI机器人名字（用于检测群聊中提到名字时触发回复） */
    private String botName = "";

    /** execq <cmd> 插件白名单（逗号分隔持久化），为空则不限制 */
    private final Set<String> execqCmdWhitelist = new LinkedHashSet<>();

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
            // execq 插件白名单
            String whitelist = p.getProperty("execqCmdWhitelist", "");
            if (!whitelist.isEmpty()) {
                execqCmdWhitelist.clear();
                for (String item : whitelist.split(",")) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) {
                        execqCmdWhitelist.add(trimmed);
                    }
                }
            }
            // 主动查看配置
            proactiveCheckEnabled = "true".equalsIgnoreCase(p.getProperty("proactiveCheckEnabled", "false"));
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
            // AI机器人名字
            botName = p.getProperty("botName", "");
        } catch (Exception ignored) {
            // 读取失败则使用默认值
        }
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
            p.setProperty("execqCmdWhitelist", String.join(",", execqCmdWhitelist));
            // 主动查看配置
            p.setProperty("proactiveCheckEnabled", String.valueOf(proactiveCheckEnabled));
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
            // AI机器人名字
            p.setProperty("botName", botName);
            try (FileOutputStream fos = new FileOutputStream(configFile)) {
                p.store(new OutputStreamWriter(fos, StandardCharsets.UTF_8),
                        "AiAgent Configuration");
            }
        } catch (Exception ignored) {
            // 保存失败静默
        }
    }

    // ==================== Getters & Setters ====================

    public String getApiKey()          { return apiKey; }
    public String getApiUrl()          { return apiUrl; }
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
    
    /** 获取主人QQ号列表 */
    public Set<Long> getMasterQQs()             { return Collections.unmodifiableSet(masterQQs); }
    
    /** 添加主人QQ号 */
    public void addMasterQQ(long qq)            { masterQQs.add(qq); }
    
    /** 移除主人QQ号 */
    public void removeMasterQQ(long qq)         { masterQQs.remove(qq); }
    
    /** 检查是否是主人 */
    public boolean isMasterQQ(long qq)          { return masterQQs.contains(qq); }
    
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

    // === execq 插件白名单 ===

    /** 获取 execq <cmd> 插件白名单（不可变视图） */
    public Set<String> getExecqCmdWhitelist() { return Collections.unmodifiableSet(execqCmdWhitelist); }

    /** 向白名单添加插件名 */
    public boolean addExecqCmdPlugin(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) return false;
        return execqCmdWhitelist.add(pluginName.trim());
    }

    /** 从白名单移除插件名 */
    public boolean removeExecqCmdPlugin(String pluginName) {
        if (pluginName == null || pluginName.trim().isEmpty()) return false;
        return execqCmdWhitelist.remove(pluginName.trim());
    }

    /** 检查插件名是否在白名单中 */
    public boolean isExecqCmdPluginAllowed(String pluginName) {
        if (execqCmdWhitelist.isEmpty()) return false;
        return execqCmdWhitelist.contains(pluginName);
    }

    /** @return API Key 是否已设置 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    // === 主动查看配置 ===

    public boolean isProactiveCheckEnabled() { return proactiveCheckEnabled; }
    public void setProactiveCheckEnabled(boolean v) { this.proactiveCheckEnabled = v; }

    public Set<Long> getMonitoredGroups() { return Collections.unmodifiableSet(monitoredGroups); }
    public boolean addMonitoredGroup(long groupId) { return monitoredGroups.add(groupId); }
    public boolean removeMonitoredGroup(long groupId) { return monitoredGroups.remove(groupId); }

    // === AI机器人名字 ===

    public String getBotName() { return botName != null ? botName.trim() : ""; }
    public void setBotName(String name) { this.botName = (name != null) ? name.trim() : ""; }

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

    /** AES 密钥（固定种子，防止重启后密钥变化导致无法解密） */
    private static final String AES_KEY = "AiAgent@SFW2024!";

    private static byte[] getAesKey() {
        byte[] raw = AES_KEY.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[16];
        System.arraycopy(raw, 0, key, 0, Math.min(raw.length, 16));
        return key;
    }

    private static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(getAesKey(), "AES"));
            return Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { return ""; }
    }

    private static String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(getAesKey(), "AES"));
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }
}
