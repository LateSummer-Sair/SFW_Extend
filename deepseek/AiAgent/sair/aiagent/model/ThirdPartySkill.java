package sair.aiagent.model;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三方技能条目模型 —— 来自 {@code data/skills} 目录下的 .md 文件或技能包目录。
 * <p>
 * 与内置技能库（{@link SkillEntry}，存 SQLite）物理隔离，完全由文件系统驱动：
 * 增删改直接对应 .md 文件的创建/删除/编辑，内存中仅保留解析后的镜像。
 * </p>
 * <p>
 * 支持两种形态：
 * <ul>
 *   <li><b>单文件技能</b>：一个 .md 文件，{@code content} 为完整正文（加载进内存）。</li>
 *   <li><b>技能包</b>：目录内存在 {@code SKILL.md} 入口。仅注册元信息（name/description），
 *       正文与附属文档不加载进内存，使用时由 {@code ThirdPartySkillStore} 按需临时读取；
 *       {@link #getRequiredRuntimes()} 提供辅助脚本所需运行时清单。</li>
 * </ul>
 * </p>
 */
public class ThirdPartySkill implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;
    private final String content;                 // 单文件技能：完整正文；技能包：空串
    private final String contentHash;
    private final String sourceFile;              // 单文件 .md / 技能包 SKILL.md 绝对路径
    private final String packageDir;              // 技能包根目录（单文件为 null）
    private final Map<String, String> attachmentPaths;  // 技能包 .md：相对路径 → 绝对路径
    private final List<String> requiredRuntimes;        // 技能包所需运行时清单
    private final Map<String, String> codeBlocks;       // 单文件技能：语言(java/js/nodejs/python)→代码段
    private final String airunDescription;              // airun 函数描述（front matter 声明，可为 null）
    private final List<AirunParam> airunParams;         // airun 参数列表（front matter 声明，可为空）

    /**
     * airun 函数签名参数（front matter 中 airun.params 声明的单个参数）。
     * <p>{@code type} 支持 string/number/integer/boolean，缺省默认 string。</p>
     */
    public static class AirunParam {
        public final String name;
        public final String type;
        public final String description;
        public final boolean required;
        public AirunParam(String name, String type, String description, boolean required) {
            this.name = (name != null) ? name.trim() : "";
            this.type = (type == null || type.trim().isEmpty()) ? "string" : type.trim().toLowerCase();
            this.description = (description != null) ? description.trim() : "";
            this.required = required;
        }
    }

    /** 单文件技能构造器（兼容旧调用，无代码段）。 */
    public ThirdPartySkill(String name, String description, String content, String sourceFile) {
        this(name, description, content, sourceFile, Collections.<String, String>emptyMap());
    }

    /** 单文件技能构造器（含代码段，无 airun 签名声明）。 */
    public ThirdPartySkill(String name, String description, String content, String sourceFile,
                           Map<String, String> codeBlocks) {
        this(name, description, content, sourceFile, codeBlocks, null, null);
    }

    /** 单文件技能构造器（含代码段 + airun 函数签名声明）。 */
    public ThirdPartySkill(String name, String description, String content, String sourceFile,
                           Map<String, String> codeBlocks, String airunDescription,
                           List<AirunParam> airunParams) {
        this.name = (name != null) ? name.trim() : "";
        this.description = (description != null) ? description.trim() : "";
        this.content = (content != null) ? content.trim() : "";
        this.sourceFile = sourceFile;
        this.packageDir = null;
        this.attachmentPaths = Collections.emptyMap();
        this.requiredRuntimes = Collections.emptyList();
        if (codeBlocks == null || codeBlocks.isEmpty()) {
            this.codeBlocks = Collections.emptyMap();
        } else {
            this.codeBlocks = Collections.unmodifiableMap(new LinkedHashMap<>(codeBlocks));
        }
        this.airunDescription = (airunDescription == null || airunDescription.trim().isEmpty())
                ? null : airunDescription.trim();
        if (airunParams == null || airunParams.isEmpty()) {
            this.airunParams = Collections.emptyList();
        } else {
            this.airunParams = Collections.unmodifiableList(new ArrayList<>(airunParams));
        }
        this.contentHash = computeContentHash(this.content);
    }

    /** 技能包构造器：仅注册元信息，不加载正文 / 附属文档内容。 */
    public ThirdPartySkill(String name, String description, String sourceFile, String packageDir,
                           Map<String, String> attachmentPaths, List<String> requiredRuntimes) {
        this.name = (name != null) ? name.trim() : "";
        this.description = (description != null) ? description.trim() : "";
        this.content = "";
        this.sourceFile = sourceFile;
        this.packageDir = packageDir;
        if (attachmentPaths == null || attachmentPaths.isEmpty()) {
            this.attachmentPaths = Collections.emptyMap();
        } else {
            this.attachmentPaths = Collections.unmodifiableMap(new LinkedHashMap<>(attachmentPaths));
        }
        if (requiredRuntimes == null || requiredRuntimes.isEmpty()) {
            this.requiredRuntimes = Collections.emptyList();
        } else {
            this.requiredRuntimes = Collections.unmodifiableList(new ArrayList<>(requiredRuntimes));
        }
        this.codeBlocks = Collections.emptyMap();
        this.airunDescription = null;
        this.airunParams = Collections.emptyList();
        this.contentHash = "";
    }

    public String getName()        { return name; }
    public String getDescription() { return description; }
    public String getContent()     { return content; }
    public String getContentHash() { return contentHash; }
    public String getSourceFile()  { return sourceFile; }
    public String getPackageDir()  { return packageDir; }
    public Map<String, String> getAttachmentPaths() { return attachmentPaths; }
    public List<String> getRequiredRuntimes() { return requiredRuntimes; }
    public Map<String, String> getCodeBlocks() { return codeBlocks; }

    /** airun 函数描述（front matter 声明，未声明则为 null）。 */
    public String getAirunDescription() { return airunDescription; }

    /** airun 参数列表（front matter 声明，未声明则为空列表）。 */
    public List<AirunParam> getAirunParams() { return airunParams; }

    /** 是否声明了 airun 函数签名（含至少一个参数）。 */
    public boolean hasAirunSchema() { return !airunParams.isEmpty(); }

    /** 是否包含可执行代码段。 */
    public boolean hasCodeBlocks() { return !codeBlocks.isEmpty(); }

    /** 是否为技能包（目录内含 SKILL.md + 其他文件）。 */
    public boolean isPackage() { return packageDir != null; }

    /** 固定 scope，标识为三方技能（所有通道可见）。 */
    public String getScope() { return "third_party"; }

    /** 计算 content 的 SHA-256 哈希，用于变化检测。 */
    public static String computeContentHash(String content) {
        if (content == null || content.isEmpty()) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(content.hashCode());
        }
    }

    @Override
    public String toString() {
        return "[ThirdPartySkill] " + name + (isPackage() ? " [技能包]" : "") + " (" + sourceFile + ")";
    }
}
