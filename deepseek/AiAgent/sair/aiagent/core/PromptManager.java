package sair.aiagent.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import sair.aiagent.AiAgentActivity;

/**
 * 提示词管理器 —— <b>提示词全部外置在 md 文件里</b>，类内不再保留任何提示词正文。
 *
 * <h3>三份文件（都在插件数据目录）</h3>
 * <ul>
 *   <li>{@code systemPrompt.md} —— 聊天/本地通道的底座提示词（规则 + 角色设定）</li>
 *   <li>{@code execqPrompt.md}  —— QQ 通道的提示词（规则 + 角色设定）</li>
 *   <li>{@code agentPrompt.md}  —— execs/本地 Agent 追加段（含 {@code {os}/{shell}/{jdk}/{plugins}} 占位符）</li>
 * </ul>
 *
 * <h3>三条规则</h3>
 * <ol>
 *   <li><b>文件就是提示词</b>：取到的内容 = 文件内容去掉标记行。类里没有任何兜底正文，
 *       文件不存在/读不出来 → 该提示词就是<b>空字符串</b>（不报警、不阻塞）。</li>
 *   <li><b>改文件即热重载</b>：按 mtime+size 比对（500ms 节流），无需重启或重编译。</li>
 *   <li><b>标记行只用于分界</b>：{@code <!-- AiAgent:base -->} / {@code <!-- AiAgent:persona -->}
 *       加载时会被剥离，不会进入提示词；它们让 {@code ai/setprompt} 能只改「角色设定」段。</li>
 * </ol>
 */
public class PromptManager {

    // ==================== 单例 ====================

    private static volatile PromptManager instance;

    /** 分界标记（加载时剥离，不进入提示词）。 */
    static final String MARK_BASE = "<!-- AiAgent:base -->";
    static final String MARK_PERSONA = "<!-- AiAgent:persona -->";

    /** 「角色设定」段的引导语（仅在 setprompt 新建该段时写入）。 */
    private static final String GLUE =
            "## 角色设定\n以下定义你的名字、身份、性格与说话习惯，请以这些设定为准。\n\n";

    public static PromptManager getInstance() {
        if (instance == null) {
            synchronized (PromptManager.class) {
                if (instance == null) instance = new PromptManager();
            }
        }
        return instance;
    }

    private PromptManager() {}

    // ==================== 状态 ====================

    /** 当前生效的三段提示词（= 对应文件内容去掉标记行；文件缺失时为空串）。 */
    private volatile String systemPrompt = "";
    private volatile String execqPrompt = "";
    private volatile String agentStaticPrompt = "";

    private volatile File systemPromptFile;
    private volatile File execqPromptFile;
    private volatile File agentPromptFile;

    private volatile long sysMtime = -1, sysLen = -1;
    private volatile long eqMtime = -1, eqLen = -1;
    private volatile long agMtime = -1, agLen = -1;
    private volatile long lastHotCheck = 0;

    // ==================== 初始化 / 热重载 ====================

    /** 初始化：读三份 md（不存在就是空提示词，不报警）。 */
    public void init(File dataDir) {
        if (dataDir == null) return;
        this.systemPromptFile = locate(dataDir, "systemPrompt.md");
        this.execqPromptFile = locate(dataDir, "execqPrompt.md");
        this.agentPromptFile = locate(dataDir, "agentPrompt.md");
        reload(systemPromptFile, 0);
        reload(execqPromptFile, 1);
        reload(agentPromptFile, 2);
    }

    /**
     * 找提示词文件：<b>优先 {@code data/prompts/<名字>.md}</b>（把提示词单独放一个子目录更清爽），
     * 找不到再看数据目录根下的同名文件（兼容旧布局）。
     *
     * <p>两处都存在时以 {@code prompts/} 为准，并在日志里点一句 —— 避免"我改了根目录那份怎么不生效"。</p>
     */
    private static File locate(File dataDir, String name) {
        File nested = new File(new File(dataDir, "prompts"), name);
        File root = new File(dataDir, name);
        if (nested.isFile()) {
            if (root.isFile()) {
                log("检测到两处都有 " + name + "，本次使用 prompts/ 下的那份（数据目录根下的已忽略）");
            }
            return nested;
        }
        return root;
    }

    /** 热重载检查（节流 500ms）。 */
    private void hotReloadIfChanged() {
        long now = System.currentTimeMillis();
        if (now - lastHotCheck < 500) return;
        lastHotCheck = now;
        reloadIfChanged(systemPromptFile, 0, sysMtime, sysLen);
        reloadIfChanged(execqPromptFile, 1, eqMtime, eqLen);
        reloadIfChanged(agentPromptFile, 2, agMtime, agLen);
    }

    private void reloadIfChanged(File f, int which, long mtime, long len) {
        if (f == null || !f.isFile()) return;
        long m = f.lastModified(), l = f.length();
        if (m == mtime && l == len) return;
        reload(f, which);
    }

    /** 读盘并生效（which: 0=system, 1=execq, 2=agent）。 */
    private void reload(File f, int which) {
        String raw = readText(f);
        String text = (raw == null) ? "" : stripMarks(raw);
        long m = (f != null) ? f.lastModified() : -1L;
        long l = (f != null) ? f.length() : -1L;
        switch (which) {
            case 0: systemPrompt = text; sysMtime = m; sysLen = l; break;
            case 1: execqPrompt = text; eqMtime = m; eqLen = l; break;
            default: agentStaticPrompt = text; agMtime = m; agLen = l; break;
        }
    }

    /** 摘掉标记行：其余内容<b>一个字节都不动</b>（含换行风格）。 */
    static String stripMarks(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int nl = text.indexOf('\n', i);
            String line = (nl < 0) ? text.substring(i) : text.substring(i, nl + 1);
            String bare = line.replace("\r", "").replace("\n", "").trim();
            if (!bare.equals(MARK_BASE) && !bare.equals(MARK_PERSONA)) out.append(line);
            if (nl < 0) break;
            i = nl + 1;
        }
        return out.toString();
    }

    private static String readText(File f) {
        try {
            if (f == null || !f.isFile()) return null;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private void writeText(File f, String text) {
        if (f == null || text == null) return;
        try {
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private static void log(String msg) {
        try { AiAgentActivity.debugLog("[提示词] " + msg); } catch (Throwable ignored) {}
    }

    // ==================== Getters ====================

    /** 聊天/本地通道的底座提示词（= systemPrompt.md 内容，缺文件为空串）。 */
    public String getSystemPrompt() {
        hotReloadIfChanged();
        return systemPrompt;
    }

    /** QQ 通道提示词（= execqPrompt.md 内容，缺文件为空串）。 */
    public String getExecqPrompt() {
        hotReloadIfChanged();
        return execqPrompt;
    }

    /** execs/本地 Agent 追加段模板（= agentPrompt.md 内容，含 {os}/{shell}/{jdk}/{plugins} 占位符）。 */
    public String getAgentStaticPrompt() {
        hotReloadIfChanged();
        return agentStaticPrompt;
    }

    /** 角色设定段（标记之后的内容）；没有该段返回空串。 */
    public String getSystemPromptExtra() { return extractPersona(systemPrompt, MARK_PERSONA); }

    /** execq 角色设定段；没有该段返回空串。 */
    public String getExecqPromptExtra() { return extractPersona(execqPrompt, MARK_PERSONA); }

    // ==================== Setters（只动「角色设定」段，不碰上面的规则） ====================

    /** 设置聊天通道的角色设定（写入 systemPrompt.md 的 persona 段）。 */
    public void setSystemPrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) return;
        replacePersona(systemPromptFile, prompt.trim(), 0);
    }

    /** 追加/覆盖聊天通道角色设定（同 setSystemPrompt）。 */
    public void appendSystemPrompt(String extra) { setSystemPrompt(extra); }

    /** 设置 QQ 通道的角色设定（写入 execqPrompt.md 的 persona 段）。 */
    public void setExecqPrompt(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) return;
        replacePersona(execqPromptFile, prompt.trim(), 1);
    }

    /** 追加/覆盖 QQ 通道角色设定（同 setExecqPrompt）。 */
    public void appendExecqPrompt(String extra) { setExecqPrompt(extra); }

    /**
     * 替换「角色设定」段：
     * 有 {@code <!-- AiAgent:persona -->} 标记 → 只换标记之后的内容（上面的规则原样保留）；
     * 没有标记 → 在文件末尾补一个带标记的角色设定段（已有内容不会被丢）。
     */
    private void replacePersona(File f, String persona, int which) {
        String raw = readText(f);
        if (raw == null) raw = "";
        String text;
        int i = raw.indexOf(MARK_PERSONA);
        if (i >= 0) {
            int nl = raw.indexOf('\n', i + MARK_PERSONA.length());
            String head = (nl < 0) ? (raw + "\n") : raw.substring(0, nl + 1);
            text = head + "\n" + GLUE + persona;
        } else if (raw.trim().isEmpty()) {
            text = persona;
        } else {
            String sep = raw.endsWith("\n") ? "" : "\n";
            text = raw + sep + MARK_PERSONA + "\n\n" + GLUE + persona;
        }
        writeText(f, text);
        reload(f, which);
        log((which == 0 ? "systemPrompt.md" : "execqPrompt.md") + " 角色设定已更新（规则部分保持不变）");
    }

    /** 从文本里取出 persona 段（标记之后、去掉引导语）。 */
    private static String extractPersona(String composed, String mark) {
        if (composed == null || composed.isEmpty()) return "";
        int i = composed.indexOf(GLUE);
        return (i < 0) ? "" : composed.substring(i + GLUE.length()).trim();
    }
}
