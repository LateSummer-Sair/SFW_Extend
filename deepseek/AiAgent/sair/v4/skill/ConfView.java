package sair.v4.skill;

import com.google.gson.JsonObject;

import java.io.File;

/**
 * 技能的配置视图（只读）。
 * <p>技能需要知道"模型是谁、主人在哪、数据目录在哪"，但<b>不能改</b>基板配置 ——
 * 改配置的入口只有控制台的 {@code config} 命令与配置文件本身。</p>
 */
public interface ConfView {

    File root();

    File file();

    File path(String rel);

    String get(String key, String def);

    int getInt(String key, int def);

    long getLong(String key, long def);

    double getDouble(String key, double def);

    boolean getBool(String key, boolean def);

    /** 配置快照（只读副本，改它不影响基板）。 */
    JsonObject snapshot();

    // ---- 常用项 ----
    String apiUrl();

    String model();

    String autoModel();

    /** 会看图的模型名（主模型没有视觉能力时，派子 Agent 用它兜底）。 */
    String visionModel();

    /**
     * 识图<b>兜底</b>用的模型名 —— <b>复数键</b> {@code visionModels}，与上面单数键 {@code visionModel}
     * 是<b>两个不同的键</b>，别把这两个方法当成同一个。
     * <p>默认值来自 {@code Conf} 的同一个访问器（缺键 = {@code deepseek-flash}）⇒ 技能侧不必再写
     * "缺键就传空串"的那种形式差异，取值与 {@code CtxBuild.selfHasVision} 同源。</p>
     * <p><b>只增不改</b>：单数键 {@link #visionModel()} 的语义一个字没动。</p>
     */
    String visionModels();

    String resolveModel();

    boolean isAutoModel();

    double temperature();

    int maxTokens();

    boolean napcatEnabled();

    int napcatPort();

    long selfId();

    long masterQQ();

    boolean logQq();

    int agentMaxRounds();

    /** <b>子 Agent</b>一轮的轮次上限（键 {@code subagentMaxRounds}，出厂默认 40）。 */
    int subagentMaxRounds();

    int subAgentMax();

    int toolResultMax();

    long tickMs();

    String identityRel();

    String skillsRel();

    // P9b-2 删除：permDefaultBuiltin() / permDefaultSkill()（旧档位表的缺行默认档，随 PermTable 一起删净）

    // ---- 路径 ----
    File skillsDir();

    File promptsDir();

    File identityFile();

    File filesDir();

    File cacheDir();

    File tmpDir();

    File dbFile();
}
