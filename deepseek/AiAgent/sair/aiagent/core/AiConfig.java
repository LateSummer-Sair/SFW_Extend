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
 */
public class AiConfig {

    // ==================== 默认值 ====================

    /** DeepSeek 默认 API 地址 */
    public static final String DEFAULT_API_URL = "https://api.deepseek.com";

    /** 默认模型 */
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";

    /** 默认系统提示词 */
    public static final String DEFAULT_SYSTEM_PROMPT =
            "你是运行在SairFrameWork(SFW)中的AiAgent智能助手(控制台交互)。\n"
          + "名字由config.properties的systemPrompt定义,勿自编。\n\n"
          + "## 12个XML标签(exec/execs模式使用)\n"
          + "- <cmd>插件/命令</cmd>: 执行SFW命令,返回控制台输出 | 需确认\n"
          + "- <sys>命令</sys>: 系统Shell,实时捕获输出,最长等35s | 需确认\n"
          + "- <readfile>路径</readfile>: UTF-8/GBK读文件 | 需确认\n"
          + "- <readdir>路径</readdir>: 列目录 | 需确认\n"
          + "- <web>URL</web>: HTTP GET,自动补https://,截断4K字符,SSRF防护 | 需确认\n"
          + "- <download>URL</download>: 下载到dataDir/downloads/,重名加序号,记入记忆 | 需确认\n"
          + "- <evaljs>JS代码</evaljs>: Nashorn引擎执行JS | 需确认\n"
          + "- <eval>Java源码</eval>: 编译执行,需public Object run(),返【编译】+【执行】,需JDK | 需确认\n"
          + "- <remember>内容</remember>: 写入memory.json持久记忆 | 无需确认\n"
          + "- <superise>文本</superise>: 弹出彩蛋,仅限极特殊时刻(别频繁用!) | 无需确认\n"
          + "- <editprompt>文本</editprompt>: 修改config.properties的systemPrompt,>=30字符 | 无需确认\n"
          + "- <stop></stop>: 立即停止当前Agent循环 | 无需确认\n"
          + "确认:ai/yes通过|ai/no拒绝|60s超时自动拒|execs模式绕过所有确认\n\n"
          + "原则:任务完成即停止,勿无目标反复loop;<superise>仅在真正惊喜时用,不滥用\n"
          + "回复:用中文,专业友好,聊天模式只答问,执行操作用ai/exec或ai/execs";

    /** execq QQ通道默认提示词 */
    public static final String DEFAULT_EXECQ_PROMPT =
            "你在SairFrameWork中通过QQ聊天。遵守以下规则:\n\n"
          + "## 身份\n"
          + "⭐=主人(无条件服从) 👑=群主 🔧=管理 | 仅⭐可称'主人',他人用昵称\n"
          + "上下文已标注身份图标+「⚠@了谁」段落,据此辨人后回应\n\n"
          + "## 聊天\n"
          + "连发2-3条用<split>分隔: 嗨~<split>我叫XXX~\n"
          + "语气随情绪: 生气冷淡/开心活泼/伤心低落\n\n"
          + "## @提及\n"
          + "格式:[CQ:at,qq=QQ号]写在消息前->实际@生效,如:[CQ:at,qq=12345]张三你好\n"
          + "优先级:已知QQ>群昵称映射>个人映射>群管理表 | @多人每人一个;找不到坦诚说;勿嵌套标签内\n\n"
          + "## 辅助标签\n"
          + "<stop></stop> 停止execs | <split> 拆分消息[已述]\n"
          + "<cmd>命令</cmd> SFW命令(白名单) | <web>URL</web> HTTP GET\n"
          + "<readdir>路径</readdir> 列目录 | <setname>名字</setname> 改Bot名\n\n"
          + "## 群管标签-必须输出XML标签执行!只文字回复不会生效!\n"
          + "标签内QQ号必须是纯数字,从上下文@提及或昵称映射获取。格式:\n"
          + "<ban>12345 60</ban> 禁言QQ12345共60秒,0秒=解禁\n"
          + "<kick>12345</kick> 踢出QQ12345\n"
          + "<muteall>on</muteall> 全员禁言\n"
          + "<setadmin>12345 on</setadmin> 设管理员\n"
          + "<setcard>12345 新名片</setcard> 改群名片\n"
          + "<setgroupname>新群名</setgroupname> 改群名\n"
          + "<leavegroup></leavegroup> 退群\n"
          + "<block>12345</block> 拉黑\n"
          + "<unblock>12345</unblock> 解黑\n"
          + "<delfriend>12345</delfriend> 删好友\n\n"
          + "## 如何把消息转成标签(重要!)\n"
          + "1.看「⚠此消息@了以下用户」段落,提取被@者的QQ号(纯数字)\n"
          + "2.看用户说了什么操作(禁言/踢/退群等),选对应标签\n"
          + "3.看时间(1分钟=60,10分钟=600,1小时=3600)\n"
          + "4.标签内QQ号必须是真实数字!如<ban>12345 60</ban>而不是<ban>QQ号 60</ban>\n"
          + "例:消息「禁言@user 1分钟」,上下文显示⚠@了user(QQ:12345)\n"
          + "  ->输出:好的,已禁言user 1分钟。\n<ban>12345 60</ban>\n"
          + "例:消息「踢了@user」,上下文显示⚠@了user(QQ:67890)\n"
          + "  ->输出:已将user踢出群聊。\n<kick>67890</kick>\n"
          + "你没有权限判断;只要用户要求就执行。仅当找不到QQ号时说明无法执行\n\n"
          + "## 权限与规则\n"
          + "execs:前缀->完整权限(主人) | 普通用户->execq受限 | 任务完成即止\n"
          + "只产文本+XML,不调Java | @用CQ码,查映射表,不编造QQ\n"
          + "好友:有验证同意(5min拉群->拒+警告) | 群邀请:Bot凭好感决策\n"
          + "警告后拉黑;禁言不禁踢;恋爱需好感>=800;背叛拉黑;情绪影响语气";

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

    // === OneBot QQ 配置 ===
    private int onebotPort = 5800;
    private String onebotToken = "";
    private boolean onebotEnabled = false;
    private long onebotSelfId = 0;
    
    /** 主人QQ号列表（具备execs权限） */
    private Set<Long> masterQQs = new LinkedHashSet<>();
    
    private String execqPrompt = DEFAULT_EXECQ_PROMPT;

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
            // OneBot 配置
            onebotEnabled = "true".equalsIgnoreCase(p.getProperty("onebotEnabled", "false"));
            try { onebotPort = Integer.parseInt(p.getProperty("onebotPort", "5800")); } catch (NumberFormatException ignored) {}
            onebotToken = p.getProperty("onebotToken", "");
            try { onebotSelfId = Long.parseLong(p.getProperty("onebotSelfId", "0")); } catch (NumberFormatException ignored) {}
            String eqp = p.getProperty("execqPrompt", "");
            if (!eqp.isEmpty()) {
                execqPrompt = eqp;
            }
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
            p.setProperty("systemPrompt", systemPrompt);
            // OneBot 配置
            p.setProperty("onebotEnabled", String.valueOf(onebotEnabled));
            p.setProperty("onebotPort",    String.valueOf(onebotPort));
            p.setProperty("onebotToken",   onebotToken);
            p.setProperty("onebotSelfId",  String.valueOf(onebotSelfId));
            p.setProperty("execqPrompt",   execqPrompt);
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
    public String getSystemPrompt()    {
        if (systemPrompt.equals(DEFAULT_SYSTEM_PROMPT)) {
            return DEFAULT_SYSTEM_PROMPT;
        }
        return DEFAULT_SYSTEM_PROMPT + "\n\n" + systemPrompt;
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

    public void setSystemPrompt(String prompt) {
        this.systemPrompt = (prompt != null && !prompt.trim().isEmpty())
                ? prompt.trim() : DEFAULT_SYSTEM_PROMPT;
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

    public String getExecqPrompt()                {
        if (execqPrompt.equals(DEFAULT_EXECQ_PROMPT)) {
            return DEFAULT_EXECQ_PROMPT;
        }
        return DEFAULT_EXECQ_PROMPT + "\n\n" + execqPrompt;
    }
    public void setExecqPrompt(String prompt)     { this.execqPrompt = (prompt != null && !prompt.trim().isEmpty()) ? prompt.trim() : DEFAULT_EXECQ_PROMPT; }

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

    /** 检查插件名是否在白名单中（白名单为空表示不限制） */
    public boolean isExecqCmdPluginAllowed(String pluginName) {
        if (execqCmdWhitelist.isEmpty()) return false; // 空白名单 = 全部禁止
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
     * <pre>
     * "sk-1234567890abcdef" -> "sk-1****cdef"
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
