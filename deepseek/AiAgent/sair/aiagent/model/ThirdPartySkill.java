package sair.aiagent.model;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 三方技能条目模型 —— 来自 {@code data/skills} 目录下的 <b>技能文件夹</b>。
 *
 * <h3>形态（V4.0 起）</h3>
 * <pre>
 * data/skills/
 *   ├─ 纯文档技能.md                 ← 只有文档（无代码），平铺在根目录
 *   └─ 时间查询/                     ← 带代码的工具技能 = 一个独立文件夹
 *        ├─ 时间查询.md              ← front matter 元数据 + 自然语言说明
 *        └─ AirunSkill.java          ← Java 源码（可多个：辅助类放同目录即可）
 * </pre>
 *
 * <p>md 与文件夹<b>同名</b>（都等于 front matter 的 {@code name}）；代码只支持 Java，
 * 入口类里必须有 {@code airun} 方法。md 里的 {@code <java start>} 内嵌代码段自 V4.0 起
 * <b>已彻底废弃</b>（加载期点名提示迁移），多文件技能包（zip / SKILL.md 约定）仍然不支持。</p>
 */
public class ThirdPartySkill implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String name;
    private final String description;
    private final String content;                 // 完整正文（front matter 剥离后的 body）
    private final String contentHash;
    private final String sourceFile;              // 源 .md 的绝对路径
    /** 技能文件夹的绝对路径（纯文档技能为 null）。 */
    private final String sourceDir;
    /** 文件夹里的 Java 源文件名（已排序，相对文件名如 {@code AirunSkill.java}）；纯文档技能为空。 */
    private final List<String> javaSources;
    /** front matter {@code entry:} 声明的入口类名；空=按「哪个类有 airun 方法」自动判定。 */
    private final String entryClass;
    /** 全部 Java 源码拼接后的文本（加载期做 airun 快速预检用，避免读盘）。 */
    private final String javaText;
    private final String airunDescription;              // airun 函数描述（front matter 声明，可为 null）
    private final List<AirunParam> airunParams;         // airun 参数列表（front matter 声明，可为空）
    /** 声明的最低调用权限（harness 权限级别字符串，如 {@code AFFECTION:300} / {@code MASTER}）；空=用默认。 */
    private final String declaredPermission;
    /** 声明的适用通道（逗号分隔，如 {@code execq,execs}）；空=全通道。 */
    private final String declaredChannels;
    /**
     * 该技能<b>顶替的内置 FC 工具名</b>（front matter {@code tool:} 声明）。
     * <p>用于「把硬编码工具剥离成 .md」：声明后该技能就以这个名字注册为 FC 工具，
     * 并且内置的同名 ToolDefinition 会被移除（否则 tools 数组里会同时出现两个同名函数）。
     * 未声明时工具名按 {@code tp_<规范化技能名>} 生成。</p>
     */
    private final String declaredToolName;

    /** 是否需要框架注入运行时上下文（front matter {@code context: true}）→ airun 收到第二个参数。 */
    private final boolean needsContext;
    /** 该技能的执行超时（秒）；≤0 表示用默认 30s。 */
    private final int timeoutSeconds;
    /**
     * 实例状态模式：{@code false}=每次调用新建实例（默认，安全）；
     * {@code true}=复用同一个实例（有状态技能，需自行保证线程安全）。
     */
    private final boolean stateShared;
    /** 返回值类型提示（{@code string}/{@code json}），仅用于给模型描述，不改变实际返回。 */
    private final String returnType;

    /**
     * airun 函数签名参数（front matter 中 airun.params 声明的单个参数）。
     * <p>{@code type} 支持 string/number/integer/boolean/array/object，缺省默认 string；
     * {@code type: array} 时可用 {@code items} 声明元素类型（缺省 string）。</p>
     */
    public static class AirunParam {
        public final String name;
        public final String type;
        public final String description;
        public final boolean required;
        /** 数组元素类型（仅 type=array 时有意义）。 */
        public final String items;
        /** 数组元素描述（可空）。 */
        public final String itemsDescription;
        /** 默认值（JSON 字面量文本，如 {@code 3} / {@code "北京"} / {@code [1,2]}）；空=无默认值。 */
        public final String defaultValue;
        /** 枚举候选值（可空）；非空时工具 schema 会带上 enum。 */
        public final List<String> enumValues;
        /** 给模型看的示例（可空，会附在参数描述后）。 */
        public final String example;

        public AirunParam(String name, String type, String description, boolean required) {
            this(name, type, description, required, null, null);
        }

        public AirunParam(String name, String type, String description, boolean required,
                          String items, String itemsDescription) {
            this(name, type, description, required, items, itemsDescription, null, null, null);
        }

        public AirunParam(String name, String type, String description, boolean required,
                          String items, String itemsDescription, String defaultValue,
                          List<String> enumValues, String example) {
            this.name = (name != null) ? name.trim() : "";
            this.type = normalizeType(type);
            this.description = (description != null) ? description.trim() : "";
            this.required = required;
            this.items = (items == null || items.trim().isEmpty()) ? "string" : items.trim().toLowerCase();
            this.itemsDescription = (itemsDescription != null) ? itemsDescription.trim() : "";
            this.defaultValue = (defaultValue == null || defaultValue.trim().isEmpty()) ? null : defaultValue.trim();
            this.enumValues = (enumValues == null || enumValues.isEmpty())
                    ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<>(enumValues));
            this.example = (example == null || example.trim().isEmpty()) ? null : example.trim();
        }

        /** 归一化参数类型（只接受 JSON Schema 认识的类型，其余回退 string）。 */
        private static String normalizeType(String t) {
            if (t == null || t.trim().isEmpty()) return "string";
            String v = t.trim().toLowerCase();
            switch (v) {
                case "string": case "number": case "integer": case "boolean": case "array": case "object":
                    return v;
                case "int": case "long":      return "integer";
                case "float": case "double":  return "number";
                case "bool":                  return "boolean";
                case "list":                  return "array";
                default:                      return "string";
            }
        }

        public boolean isArray() { return "array".equals(type); }
    }

    /** 纯文档技能构造器（无代码文件夹）。 */
    public ThirdPartySkill(String name, String description, String content, String sourceFile) {
        this(name, description, content, sourceFile, null, Collections.<String>emptyList(), null, null,
                null, null, null, null, null, null, false, 0, false, null);
    }

    /** 技能构造器（代码文件夹 + 入口类声明）。 */
    public ThirdPartySkill(String name, String description, String content, String sourceFile,
                           String sourceDir, List<String> javaSources, String entryClass,
                           List<String> ignored, Map<String, String> javaFileTexts) {
        this(name, description, content, sourceFile, sourceDir, javaSources, entryClass, ignored,
                javaFileTexts, null, null, null, null, null, false, 0, false, null);
    }

    /** 技能构造器（完整元数据）。 */
    public ThirdPartySkill(String name, String description, String content, String sourceFile,
                           String sourceDir, List<String> javaSources, String entryClass,
                           List<String> ignored, Map<String, String> javaFileTexts,
                           String airunDescription, List<AirunParam> airunParams,
                           String declaredPermission, String declaredChannels, String declaredToolName,
                           boolean needsContext, int timeoutSeconds, boolean stateShared,
                           String returnType) {
        this.name = (name != null) ? name.trim() : "";
        this.description = (description != null) ? description.trim() : "";
        this.content = (content != null) ? content.trim() : "";
        this.sourceFile = sourceFile;
        this.sourceDir = (sourceDir == null || sourceDir.trim().isEmpty()) ? null : sourceDir;
        if (javaSources == null || javaSources.isEmpty()) {
            this.javaSources = Collections.emptyList();
        } else {
            List<String> copy = new ArrayList<>(javaSources);
            Collections.sort(copy);   // 排序：保证编译顺序与日志字节稳定
            this.javaSources = Collections.unmodifiableList(copy);
        }
        this.entryClass = blankToNull(entryClass);
        StringBuilder jt = new StringBuilder();
        if (javaFileTexts != null) {
            for (String t : javaFileTexts.values()) {
                if (t == null) continue;
                if (jt.length() > 0) jt.append('\n');
                jt.append(t);
            }
        }
        this.javaText = jt.toString();
        this.airunDescription = (airunDescription == null || airunDescription.trim().isEmpty())
                ? null : airunDescription.trim();
        if (airunParams == null || airunParams.isEmpty()) {
            this.airunParams = Collections.emptyList();
        } else {
            this.airunParams = Collections.unmodifiableList(new ArrayList<>(airunParams));
        }
        this.declaredPermission = blankToNull(declaredPermission);
        this.declaredChannels = normalizeChannels(declaredChannels);
        this.declaredToolName = blankToNull(declaredToolName);
        this.needsContext = needsContext;
        this.timeoutSeconds = Math.max(0, timeoutSeconds);
        this.stateShared = stateShared;
        this.returnType = blankToNull(returnType);
        this.contentHash = computeContentHash(this.content);
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    /** 归一化通道声明：小写、去空格、去重、只保留已知通道名（非法值忽略）。 */
    private static String normalizeChannels(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "";
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String part : raw.split("[,，\\s]+")) {
            String c = part.trim().toLowerCase();
            if (c.isEmpty()) continue;
            if ("execq".equals(c) || "execs".equals(c) || "console".equals(c)) out.add(c);
            else if ("all".equals(c) || "*".equals(c)) { out.add("execq"); out.add("execs"); out.add("console"); }
        }
        return String.join(",", out);
    }

    public String getName()        { return name; }
    public String getDescription() { return description; }
    public String getContent()     { return content; }
    public String getContentHash() { return contentHash; }
    public String getSourceFile()  { return sourceFile; }

    /** 技能文件夹绝对路径（纯文档技能为 null）。 */
    public String getSourceDir()   { return sourceDir; }

    /** 文件夹里的 Java 源文件名（已排序；纯文档技能为空列表）。 */
    public List<String> getJavaSources() { return javaSources; }

    /** front matter {@code entry:} 声明的入口类名；未声明返回 null（按 airun 方法自动判定）。 */
    public String getEntryClass()  { return entryClass; }

    /** 全部 Java 源码拼接文本（加载期已读入；供文本级安全检查与 airun 预检复用，避免重复读盘）。 */
    public String getJavaText()    { return javaText == null ? "" : javaText; }

    /** airun 函数描述（front matter 声明，未声明则为 null）。 */
    public String getAirunDescription() { return airunDescription; }

    /** airun 参数列表（front matter 声明，未声明则为空列表）。 */
    public List<AirunParam> getAirunParams() { return airunParams; }

    /** 是否声明了 airun 函数签名（含至少一个参数）。 */
    public boolean hasAirunSchema() { return !airunParams.isEmpty(); }

    /** 是否带 Java 源码（= 工具技能，会被编译注册）。 */
    public boolean hasJavaSource() { return !javaSources.isEmpty(); }

    /**
     * 源码里是否出现过 {@code airun} 这个词（<b>子串预检，宁松勿紧</b>）。
     * <p>「入口函数连名字都没写对」的技能不该注册成 FC 工具（否则 AI 拿到一个必然失败的工具、
     * 白烧一轮往返）。真正权威的判定在编译期：编译通过后用反射确认类里确有 {@code airun} 方法。</p>
     */
    public boolean mentionsAirun() {
        return javaText != null && javaText.contains("airun");
    }

    /** 声明的最低调用权限（harness 级别字符串）；未声明返回 null。 */
    public String getDeclaredPermission() { return declaredPermission; }

    /** 声明的适用通道（逗号分隔）；空 = 全通道。 */
    public String getDeclaredChannels()   { return declaredChannels; }

    /** 该技能是否适用于指定通道（未声明 = 全通道）。 */
    public boolean appliesToChannel(String channel) {
        if (channel == null || channel.isEmpty()) return true;
        if (declaredChannels == null || declaredChannels.isEmpty()) return true;
        return ("," + declaredChannels + ",").contains("," + channel.toLowerCase() + ",");
    }

    /** 顶替的内置 FC 工具名；未声明返回 null。 */
    public String getDeclaredToolName()   { return declaredToolName; }

    /**
     * 该技能注册为 FC 工具时使用的名字。
     * <p>声明了 {@code tool:} 就用它（用于顶替硬编码工具，保持工具名不变），
     * 否则用 {@code tp_<规范化技能名>}。合法性由调用方（ToolDispatcher）最终校验。</p>
     */
    public String providedToolName() {
        if (declaredToolName != null && !declaredToolName.isEmpty()) return declaredToolName;
        return sair.aiagent.util.ToolNames.TP_PREFIX + sair.aiagent.util.ToolNames.sanitize(name);
    }

    /** 是否顶替了一个内置 FC 工具（即该工具的实现已从 Java 剥离到本技能文件夹）。 */
    public boolean replacesBuiltinTool() {
        return declaredToolName != null && !declaredToolName.isEmpty()
                && hasJavaSource() && mentionsAirun();
    }

    /** 是否需要框架注入运行时上下文（airun 会收到第二个参数 {@code ctx}）。 */
    public boolean needsContext()   { return needsContext; }

    /** 声明的执行超时（秒）；≤0 表示用默认值。 */
    public int getTimeoutSeconds()  { return timeoutSeconds; }

    /** 是否复用同一个实例（有状态技能）。默认 false = 每次调用新实例，避免并发污染。 */
    public boolean isStateShared()  { return stateShared; }

    /** 返回值类型提示（string/json）；未声明返回 null。 */
    public String getReturnType()   { return returnType; }

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
        return "[ThirdPartySkill] " + name + " (" + sourceFile + ")";
    }
}
