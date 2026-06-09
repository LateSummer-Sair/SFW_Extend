package sair.aiagent.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 配置管理器 —— 单例模式。
 * API Key 以 AES-128 加密存储，防止明文泄露。
 */
public class AiConfig {

    // ==================== 默认值 ====================

    /** DeepSeek 默认 API 地址 */
    public static final String DEFAULT_API_URL = "https://api.deepseek.com";

    /** 默认模型 */
    public static final String DEFAULT_MODEL = "deepseek-chat";

    /** 默认系统提示词 */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是一个运行在 SairFrameWork(SFW) 中的 AI 智能助手。"
          + "你可以帮助用户完成任务，分析数据，操作框架。"
          + "请用中文回复。";

    // ==================== 单例 ====================

    private static volatile AiConfig instance;

    /** 私有构造，防止外部实例化 */
    private AiConfig() {}

    /**
     * 获取单例实例（双重检查锁定，线程安全）。
     *
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
    private String systemPrompt = DEFAULT_SYSTEM_PROMPT;

    /** 配置文件路径（init后设置） */
    private File configFile;

    // ==================== 生命周期 ====================

    /**
     * 初始化：加载已有配置。
     *
     * @param dataDir 插件数据目录路径
     */
    public void init(String dataDir) {
        this.configFile = new File(dataDir, "config.properties");
        load();
    }

    // ==================== 持久化 ====================

    /**
     * 从配置文件加载配置项。
     * <p>如果文件不存在或读取失败，保留默认值。</p>
     */
    public void load() {
        if (configFile == null || !configFile.exists()) return;
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(configFile)) {
            p.load(new InputStreamReader(fis, StandardCharsets.UTF_8));
            apiKey = decrypt(p.getProperty("apiKey", ""));
            apiUrl       = p.getProperty("apiUrl", apiUrl);
            model        = p.getProperty("model", model);
            String sp    = p.getProperty("systemPrompt", "");
            if (!sp.isEmpty()) {
                systemPrompt = sp;
            }
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
            p.setProperty("systemPrompt", systemPrompt);
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
    public String getSystemPrompt()    { return systemPrompt; }

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

    public void setSystemPrompt(String prompt) {
        this.systemPrompt = (prompt != null && !prompt.trim().isEmpty())
                ? prompt.trim() : DEFAULT_SYSTEM_PROMPT;
    }

    /** @return API Key 是否已设置 */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isEmpty();
    }

    // ==================== 工具方法 ====================

    /**
     * 生成脱敏后的 API Key 用于显示。
     * <pre>
     * "sk-1234567890abcdef" → "sk-1****cdef"
     * </pre>
     *
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
