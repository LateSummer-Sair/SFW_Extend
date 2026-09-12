package sair.aiagent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.SkillEntry;
import sair.aiagent.model.ThirdPartySkill;

/**
 * 技能库核心引擎 —— Skills 自进化机制的中枢。
 * <p>
 * 提供技能的全生命周期管理：添加（含去重合并）、检索、
 * 上下文构建、使用追踪、进化触发。
 * 底层存储委托给 {@link PersistenceManager} 的 SQLite skills 表。
 * </p>
 *
 * <h3>通道技能范围</h3>
 * <ul>
 *   <li>execq (QQ通道): 所有技能 (general + execq + task + persona)</li>
 *   <li>console (本地通道): general + task + persona（排除 execq 即 NapCat API）</li>
 * </ul>
 */
public class SkillBank {

    private static volatile SkillBank instance;
    private PersistenceManager pm;
    private DeepSeekClient llmClient;
    private RouteCache routeCache;
    private ThirdPartySkillStore thirdPartyStore;

    /** LRU tag-detail cache: tagName -> skillDetail, max 20 entries */
    private final Map<String, String> tagDetailCache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 20;
        }
    };

    /** 索引字符串缓存：cacheKey → 完整索引文本，最多 4 个（execq/execs/console 各不同预算）。 */
    private static final int INDEX_CACHE_MAX = 4;
    private final Map<String, String> indexCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(8, 0.75f, true) {
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > INDEX_CACHE_MAX;
                }
            });

    private SkillBank() {}

    public static SkillBank getInstance() {
        if (instance == null) {
            synchronized (SkillBank.class) {
                if (instance == null) {
                    instance = new SkillBank();
                }
            }
        }
        return instance;
    }

    public void init(PersistenceManager pm, DeepSeekClient llmClient) {
        this.pm = pm;
        this.llmClient = llmClient;
    }

    // ==================== 添加（含去重合并） ====================

    /**
     * 提取端近似重复抑制阈值。
     * <p>
     * 根因：{@code findSimilarSkill} 只按 <b>name 精确匹配</b>，而 LLM 每次都给改写句生成
     * 新名字（{@code quote_exact_product_model_in_search} vs
     * {@code quote_exact_model_number_in_search}），于是每条改写都成了一条新记录 ——
     * 实测技能库以 <b>440 条/天</b>增长、总量已达 4,601 条，其中大量是同一条经验的不同说法
     * （「403 别重试、换搜索引擎」有几十种表达）。
     * </p>
     * <p>取 0.85 = 只拦近乎复述的；被拦下的<b>不静默丢弃</b>，而是在命中的那条上累加
     * {@code dup_hit_count}（反复被独立学到 = 这条经验很重要）。</p>
     */
    private static final double EXTRACT_DUP_RATIO = 0.85;

    /** 手动去重命令使用的默认阈值（可被命令参数覆盖）。 */
    public static final double DEDUP_DEFAULT_RATIO = 0.85;

    public int addSkill(String name, String category, String description,
                        String content, String source) {
        return addSkill(name, category, description, content, source, "task");
    }

    public int addSkill(String name, String category, String description,
                        String content, String source, String scope) {
        if (pm == null) return -1;
        SkillEntry existing = pm.findSimilarSkill(name, description);
        if (existing != null) {
            return mergeSkill(existing, new SkillEntry(name, category, description, content, source, scope));
        }
        // 近似重复抑制：名称不同但在说同一件事的改写不再入库
        SkillEntry near = findNearDuplicate(name, description, EXTRACT_DUP_RATIO);
        if (near != null) {
            pm.incrementSkillDupHit(near.getId());
            AiAgentActivity.debugLog("[SkillBank] 近似重复已抑制（并入 #" + near.getId()
                    + " dupHit=" + (near.getDupHitCount() + 1) + "）: " + name);
            return near.getId();
        }
        return pm.addSkill(name, category, description, content, source, scope);
    }

    /**
     * 添加内置技能（SystemSkillLib/NapCatSkillLib 调用）。
     * 计算 SHA-256 哈希：相同跳过；旧数据无 hash 时以代码为权威源直接覆写，不走 LLM。
     * <p>适用通道由 {@link #channelsForBuiltin} 按工具可用性推导并回填（见该方法说明）。</p>
     */
    public int addBuiltinSkill(String name, String category, String description,
                               String content, String source, String scope) {
        if (pm == null) return -1;
        String newHash = SkillEntry.computeContentHash(content);
        final String channels = channelsForBuiltin(name, scope);
        SkillEntry existing = pm.findSimilarSkill(name, description);
        if (existing != null) {
            // 通道归属与内容分开处理：老库这列是空的，即使内容没变也必须回填一次，
            // 否则旧数据永远走 scope 兜底规则（QQ 通道就还是看不见通用工具文档）。
            if (!channels.equals(existing.getChannels())) {
                pm.setSkillChannels(existing.getId(), channels);
                AiAgentActivity.debugLog("[SkillBank] builtin #" + existing.getId()
                        + " channels '" + existing.getChannels() + "' -> '" + channels + "': " + name);
            }
            String oldHash = existing.getContentHash();
            if (oldHash != null && oldHash.equals(newHash)) {
                AiAgentActivity.debugLog("[SkillBank] skip builtin #" + existing.getId()
                        + " (hash match): " + name);
                return existing.getId();
            }
            // 无旧hash(DDL刚升级)或内容变化 → 代码为权威源，直接覆写，不走LLM
            boolean ok = pm.updateSkill(existing.getId(), existing.getName(),
                    description, content, existing.getVersion() + 1);
            AiAgentActivity.debugLog("[SkillBank] update builtin #" + existing.getId()
                    + " v" + existing.getVersion() + "->" + (existing.getVersion() + 1)
                    + " (hash=" + newHash.substring(0, Math.min(8, newHash.length())) + "..." + "): " + name);
            return ok ? existing.getId() : -1;
        }
        int id = pm.addSkill(name, category, description, content, source, scope);
        if (id > 0) pm.setSkillChannels(id, channels);
        return id;
    }

    public int mergeSkill(SkillEntry existing, SkillEntry candidate) {
        if (pm == null) return -1;
        String mergedContent;
        if (llmClient != null) {
            mergedContent = llmMergeContent(existing, candidate);
        } else {
            mergedContent = existing.getContent();
            if (!mergedContent.contains(candidate.getContent())) {
                mergedContent = mergedContent + "\n" + candidate.getContent();
            }
        }
        int newVersion = existing.getVersion() + 1;
        boolean ok = pm.updateSkill(existing.getId(), existing.getName(),
                candidate.getDescription(), mergedContent, newVersion);
        // updateSkill 内部已通过 computeContentHash 更新 content_hash
        if (ok) {
            existing.setContentHash(SkillEntry.computeContentHash(mergedContent));
        }
        if (ok) {
            AiAgentActivity.debugLog("[SkillBank] merged skill #" + existing.getId()
                    + " v" + existing.getVersion() + "->v" + newVersion
                    + ": " + existing.getName());
        }
        return ok ? existing.getId() : -1;
    }

    private String llmMergeContent(SkillEntry existing, SkillEntry candidate) {
        try {
            String prompt = "You are merging two versions of a learned skill. "
                    + "Preserve the core identity of the original, integrate "
                    + "new constraints from the candidate. Output ONLY the merged "
                    + "skill content text, no explanation.\n\n"
                    + "Original (v" + existing.getVersion() + "):\n"
                    + existing.getContent() + "\n\n"
                    + "Candidate update:\n" + candidate.getContent() + "\n\n"
                    + "Merged skill:";
            List<sair.aiagent.model.ChatMessage> msgs = new ArrayList<>();
            msgs.add(new sair.aiagent.model.ChatMessage("user", prompt));
            String merged = llmClient.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());
            if (merged != null && !merged.trim().isEmpty()) {
                return merged.trim();
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[SkillBank] LLM merge failed: " + e.getMessage());
        }
        return existing.getContent() + "\n" + candidate.getContent();
    }

    // ==================== 检索 ====================

    public List<SkillEntry> search(String query, int limit) {
        if (pm == null) return new ArrayList<>();
        return pm.searchSkills(query, limit > 0 ? limit : 5);
    }

    public List<SkillEntry> listAll() {
        if (pm == null) return new ArrayList<>();
        return pm.listAllSkills();
    }

    public SkillEntry getSkill(int id) {
        if (pm == null) return null;
        return pm.getSkill(id);
    }

    // ==================== 上下文构建 ====================

    /**
     * 分层技能上下文构建: general全量 + 通道专属 + task语义Top-K。
     *
     * @param task     当前任务描述，用于语义检索
     * @param maxTokens 最大token预算（0=默认800）
     * @param channel  "execq"=全部技能; "console"=排除NapCat(execq)技能
     */
    public String buildContext(String task, int maxTokens, String channel) {
        if (pm == null) return null;
        boolean isExecq = "execq".equals(channel);
        String retrievalQuery = rewriteQuery(task);

        StringBuilder sb = new StringBuilder();
        int budget = maxTokens > 0 ? maxTokens * 4 : 3200;
        int used = 0;

        // Tier 1: General skills (always, all channels)
        List<SkillEntry> generalSkills = pm.getGeneralSkills();
        if (!generalSkills.isEmpty()) {
            sb.append("## General Skills (always apply)\n");
            for (SkillEntry s : generalSkills) {
                if (!s.isWorthInjecting()) continue;
                String line = "- **" + s.getName() + "** (v" + s.getVersion() + "): " + s.getContent() + "\n";
                if (used + line.length() > budget / 2) break;
                sb.append(line);
                used += line.length();
            }
        }

        // Tier 1b: Execq channel — NapCat API skills
        if (isExecq) {
            List<SkillEntry> execqSkills = pm.getSkillsByScope("execq");
            if (!execqSkills.isEmpty()) {
                sb.append("## QQ Channel Skills (NapCat API)\n");
                for (SkillEntry s : execqSkills) {
                    if (!s.isWorthInjecting()) continue;
                    String line = "- **" + s.getName() + "** (v" + s.getVersion() + "): " + s.getContent() + "\n";
                    if (used + line.length() > budget / 2) break;
                    sb.append(line);
                    used += line.length();
                }
            }
        }

        // Tier 2: Task-specific skills (top-K semantic). Console excludes execq.
        List<SkillEntry> taskSkills = pm.searchSkills(retrievalQuery, 5);
        if (!taskSkills.isEmpty()) {
            sb.append("## Task-Specific Skills\n");
            for (SkillEntry s : taskSkills) {
                if (!s.isWorthInjecting()) continue;
                if ("general".equals(s.getScope())) continue; // already in Tier1
                if (!isExecq && "execq".equals(s.getScope())) continue; // NapCat not for console
                String line = "- **" + s.getName() + "** (v" + s.getVersion()
                        + ", " + s.successRate() + "%): " + s.getContent() + "\n";
                if (used + line.length() > budget) break;
                sb.append(line);
                used += line.length();
            }
        }

        // Tier 3: Persona skills
        List<SkillEntry> personaSkills = pm.getPersonaSkills();
        if (!personaSkills.isEmpty()) {
            sb.append("## Persona Style Reference (auxiliary — blend, don't override)\n");
            sb.append("The following style preferences are distilled from user interactions. ");
            sb.append("Blend them with your base personality (custom prompt), ");
            sb.append("use half from persona + half from your default style.\n");
            for (SkillEntry s : personaSkills) {
                if (!s.isWorthInjecting()) continue;
                String line = "- [人物风格: " + s.getName() + "] " + s.getContent() + "\n";
                if (used + line.length() > budget) break;
                sb.append(line);
                used += line.length();
            }
        }

        if (sb.length() < 20) return null;
        return sb.toString();
    }

    private String rewriteQuery(String task) {
        if (llmClient == null || task == null || task.length() < 20) return task;
        try {
            String prompt = "Rewrite the following task description into a concise, "
                    + "standalone search query (max 50 chars) for retrieving relevant skills. "
                    + "Focus on the core intent, remove noise:\n\n" + task + "\n\nQuery:";
            List<sair.aiagent.model.ChatMessage> msgs = new ArrayList<>();
            msgs.add(new sair.aiagent.model.ChatMessage("user", prompt));
            String rewritten = llmClient.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());
            if (rewritten != null && !rewritten.trim().isEmpty() && rewritten.trim().length() <= 100) {
                return rewritten.trim();
            }
        } catch (Exception e) { /* fallback */ }
        return task;
    }

    public String buildContext(String task) {
        return buildContext(task, 800, "console");
    }

    public String buildContext(String task, String channel) {
        return buildContext(task, 800, channel);
    }

    // ==================== 使用追踪 ====================

    /**
     * 记录一次「技能被查阅」（skillinfo 命中并返回了内容）。
     * <p>
     * 这是技能库目前唯一可观测的「这条经验被真正用上了」的信号：原来的
     * {@link #recordSuccess}/{@link #recordFailure} 从来没有任何调用方，
     * 导致 4601 条自学经验的使用计数全是 0 —— 既看不出哪些经验有价值，
     * 也让依赖 failure_count 的 {@link #evolve()} 永远找不到候选。
     * </p>
     * <p>按名称匹配（技能名或工具名优先，其次按内容包含该名称匹配），
     * 匹配不到就静默忽略（例如查的是内置工具名而非技能）。</p>
     */
    public void recordSkillLookup(String tag) {
        if (pm == null || tag == null || tag.trim().isEmpty()) return;
        final String t = tag.trim();
        try {
            for (SkillEntry s : listAll()) {
                if (s == null || !s.isActive()) continue;
                if (t.equalsIgnoreCase(s.getName())) {
                    recordSuccess(s.getId());
                    return;
                }
            }
            String pattern = "`" + t + "`";
            for (SkillEntry s : listAll()) {
                if (s == null || !s.isActive()) continue;
                if (s.getContent() != null && s.getContent().contains(pattern)) {
                    recordSuccess(s.getId());
                    return;
                }
            }
        } catch (Exception ignored) {
            // 使用回写失败不影响 skillinfo 的返回
        }
    }

    public void recordSuccess(int skillId) {
        if (pm != null) pm.incrementSkillUsage(skillId, true);
    }

    public void recordFailure(int skillId) {
        if (pm != null) pm.incrementSkillUsage(skillId, false);
    }

    // ==================== 进化 ====================

    public int evolve() {
        if (pm == null || llmClient == null) return 0;
        List<SkillEntry> candidates = pm.getSkillsForEvolution(3, 5);
        if (candidates.isEmpty()) return 0;
        int evolved = 0;
        for (SkillEntry skill : candidates) {
            try {
                SkillEntry improved = llmEvolveSkill(skill);
                if (improved != null) {
                    int newVersion = skill.getVersion() + 1;
                    pm.updateSkill(skill.getId(), skill.getName(),
                            skill.getDescription(), improved.getContent(), newVersion);
                    evolved++;
                    AiAgentActivity.debugLog("[SkillBank] evolved skill #" + skill.getId()
                            + " v" + skill.getVersion() + "->v" + newVersion
                            + ": " + skill.getName());
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[SkillBank] evolve failed for #"
                        + skill.getId() + ": " + e.getMessage());
            }
        }
        return evolved;
    }

    private SkillEntry llmEvolveSkill(SkillEntry skill) {
        try {
            String prompt = "You are improving a learned skill that has a high failure rate.\n\n"
                    + "Skill: " + skill.getName() + " [" + skill.getCategory() + "]\n"
                    + "Current version: v" + skill.getVersion() + "\n"
                    + "Success: " + skill.getSuccessCount() + " / Failure: " + skill.getFailureCount() + "\n"
                    + "Current content:\n" + skill.getContent() + "\n\n"
                    + "Conservative editing rules:\n"
                    + "- Only change what needs fixing, preserve everything else\n"
                    + "- If the skill content already contains correct information but the agent ignored it, do NOT change it\n"
                    + "- Tighten existing content rather than expanding\n"
                    + "Analyze why this skill might be failing and generate an IMPROVED version. "
                    + "Output ONLY the improved skill content, nothing else.";
            List<sair.aiagent.model.ChatMessage> msgs = new ArrayList<>();
            msgs.add(new sair.aiagent.model.ChatMessage("user", prompt));
            String improved = llmClient.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());
            if (improved != null && !improved.trim().isEmpty()
                    && !improved.trim().equals(skill.getContent())) {
                String validatePrompt = "Compare two versions of a skill and decide if the NEW version "
                        + "is genuinely better than the OLD version.\n\n"
                        + "OLD (v" + skill.getVersion() + "):\n" + skill.getContent() + "\n\n"
                        + "NEW (v" + (skill.getVersion() + 1) + "):\n" + improved.trim() + "\n\n"
                        + "Reply ONLY with 'YES' if the new version is better, otherwise 'NO'.";
                List<sair.aiagent.model.ChatMessage> vmsgs = new ArrayList<>();
                vmsgs.add(new sair.aiagent.model.ChatMessage("user", validatePrompt));
                String validation = llmClient.chatSync(vmsgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());
                if (validation != null && validation.trim().toUpperCase().startsWith("YES")) {
                    SkillEntry evolved = new SkillEntry(
                            skill.getName(), skill.getCategory(),
                            skill.getDescription(), improved.trim(), "evolved");
                    evolved.setParentSkillId(skill.getId());
                    evolved.setVersion(skill.getVersion() + 1);
                    return evolved;
                } else {
                    AiAgentActivity.debugLog("[SkillBank] validation rejected evolution for #" + skill.getId());
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[SkillBank] LLM evolve failed: " + e.getMessage());
        }
        return null;
    }

    // ==================== 紧凑索引（替代全量 content 注入） ====================

    /**
     * 构建紧凑工具索引，替代原来的全量技能内容注入。
     * 每条技能只输出技能名+一行简短描述（~80字符），大幅降低 token 消耗。
     * AI 根据索引调用工具后，再由 {@link #getTagDetail} 按需注入详情。
     */
    public String buildCompactIndex(String channel, int maxTokens) {
        StringBuilder sb = new StringBuilder();
        String stable = buildStableIndex(channel, maxTokens);
        if (stable != null) sb.append(stable);
        // Tier 2: Route hint from cache（任务相关，非稳定前缀，不进入 buildStableIndex）
        if (routeCache != null) {
            String routeHint = routeCache.getRouteHint(null);  // context-free hint
            if (routeHint != null) sb.append(routeHint);
        }
        if (sb.length() < 20) return null;
        return sb.toString();
    }

    /**
     * 构建「稳定」工具索引（Tier0 三方技能 + Tier1 内置/经验技能），不含任务相关路由提示。
     * <p>输出在技能不变时逐字节稳定，适合放进 system 前缀或动态上下文首部，命中 KV 前缀缓存。</p>
     * <p>
     * 两条关键修正：
     * <ol>
     *   <li><b>排序</b>：技能列表由 {@link PersistenceManager} 按「内置优先、其次新建优先」返回，
     *       内置工具/API 清单不会再被海量经验技能挤出预算（旧实现 {@code ORDER BY id DESC} 会把
     *       id 很小的内置技能排到最后，在 execs/console 通道下被整体截断）。</li>
     *   <li><b>过滤</b>：索引是「能力目录」，不再用成功率门槛 {@code isWorthInjecting()} 过滤。
     *       成功率低就永久不出现在目录里 → 永远不会被重新使用 → 成功率永远是低（自锁死循环）。
     *       质量门槛只应作用于正文注入（buildContext），不应作用于目录本身。</li>
     * </ol>
     * 整串结果按 (channel, 预算, 技能表版本, 三方技能文本) 缓存，热路径不再重复格式化。
     */
    public String buildStableIndex(String channel, int maxTokens) {
        if (pm == null) return null;
        int budget = (maxTokens > 0 ? maxTokens : 800) * 4;

        // 三方技能数量极少（通常 0），先构建其文本并参与缓存键：
        // 三方技能任何增删改名都会让整串索引缓存自动失效。
        String tpSection = buildThirdPartySection(channel, budget);
        final String cacheKey = channel + "|" + budget + "|" + pm.skillsVersion()
                + "|" + tpSection.length() + "|" + tpSection.hashCode();
        String hit = indexCache.get(cacheKey);
        if (hit != null) return hit;

        int used = 0;
        StringBuilder sb = new StringBuilder(4096);
        if (!tpSection.isEmpty()) {
            sb.append(tpSection);
            used += tpSection.length();
        }

        // Tier 1: 技能索引，每条一行。channel: execq=仅NapCat, console=排除NapCat, execs=全部
        List<SkillEntry> allActive = listAll();
        boolean headerWritten = false;
        for (SkillEntry s : allActive) {
            if (!s.isActive()) continue;
            if (!isVisibleInChannel(channel, s)) continue;
            if (isThirdPartyShadowed(s.getName())) continue;  // 三方同名优先，跳过内置
            String line = compactLine(s);
            if (line == null || line.isEmpty()) continue;
            if (used + line.length() > budget) continue;  // 单行超限只跳过该行，不放弃后面所有技能
            if (!headerWritten) {
                String header = "## 可用工具索引（不熟悉某技能/工具用法时，先调用 skillinfo 工具查详情）\n";
                sb.append(header);
                used += header.length();
                headerWritten = true;
            }
            sb.append(line);
            used += line.length();
        }

        if (sb.length() < 20) return null;
        String result = sb.toString();
        indexCache.put(cacheKey, result);
        return result;
    }

    /** Tier 0：三方技能（单文件 .md，优先于内置同名技能；按 md 里的 channels 声明过滤通道）。 */
    private String buildThirdPartySection(String channel, int budget) {
        List<ThirdPartySkill> tpAll = getAllThirdParty();
        if (tpAll.isEmpty()) return "";
        List<ThirdPartySkill> sorted = new ArrayList<>(tpAll);
        sorted.sort(Comparator.comparing(ThirdPartySkill::getName));
        StringBuilder sb = new StringBuilder(512);
        int used = 0;
        for (ThirdPartySkill tp : sorted) {
            if (tp != null && !tp.appliesToChannel(channel)) continue;   // md 声明的通道过滤
            String line = compactThirdPartyLine(tp);
            if (line == null || line.isEmpty()) continue;
            if (used + line.length() > budget) continue;
            if (used == 0) {
                String header = "## 三方技能（优先于内置同名技能）\n";
                sb.append(header);
                used += header.length();
            }
            sb.append(line);
            used += line.length();
        }
        return sb.toString();
    }

    /** 判断某内置技能名是否被同名三方技能覆盖（三方优先）。 */
    private boolean isThirdPartyShadowed(String name) {
        return name != null && thirdPartyStore != null && thirdPartyStore.get(name) != null;
    }

    /** 将三方技能格式化为紧凑索引行（优先用 description 一句话描述，为空才取 content 前3行）。 */
    private static String compactThirdPartyLine(ThirdPartySkill tp) {
        String desc = tp.getDescription();
        if (desc == null || desc.trim().isEmpty()) {
            String content = tp.getContent();
            if (content != null && !content.isEmpty()) {
                String[] lines = content.split("\\n");
                StringBuilder summary = new StringBuilder();
                for (int i = 0; i < Math.min(3, lines.length); i++) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    if (summary.length() > 0) summary.append(" ");
                    summary.append(line);
                }
                desc = summary.toString();
            }
        }
        if (desc == null) desc = "";
        if (desc.length() > 120) desc = desc.substring(0, 117) + "...";
        // 含 Java 源码（工具技能）时追加函数签名，让 AI 一眼看到可调用方式
        if (tp.hasJavaSource()) {
            StringBuilder sig = new StringBuilder();
            List<ThirdPartySkill.AirunParam> params = tp.getAirunParams();
            if (params != null && !params.isEmpty()) {
                sig.append("airun(");
                for (int i = 0; i < params.size(); i++) {
                    ThirdPartySkill.AirunParam p = params.get(i);
                    if (i > 0) sig.append(", ");
                    sig.append(p.name).append(": ").append(p.type);
                }
                sig.append(")");
            } else {
                sig.append("airun(JSON 参数)");
            }
            desc = desc + " | 工具 tp_" + tp.getName() + " " + sig;
        }
        return "- " + tp.getName() + ": " + desc + "\n";
    }

    /** 判断某 scope 技能在旧规则下是否可见（内置技能尚未回填 channels 时的兜底）。 */
    private static boolean scopeVisible(String channel, String scope) {
        if ("execq".equals(channel)) {
            return "execq".equals(scope);  // execq 普通消息：仅 NapCat 技能
        }
        if ("console".equals(channel)) {
            return !"execq".equals(scope);  // 本地 console：排除 NapCat
        }
        return true;  // execs 或其它：全部可见
    }

    /**
     * <b>通道可见性的唯一权威判定</b>（静态索引与动态经验层都必须走这里）。
     * <p>
     * 拆轴后的规则：
     * <ol>
     *   <li>声明了 {@code channels} → 只按声明判定（内置工具说明书走这条）；</li>
     *   <li>未声明（空值，含<b>全部「学到的技能」</b>）→ <b>全通道可见</b>。
     *       学到的经验是 AI 自己的知识，本来就不该有通道边界；
     *       工具本身还有权限门，隐藏经验不会更安全，只会让它重复踩坑。</li>
     *   <li>老数据尚未回填 channels 时，退回旧的 scope 规则兜底。</li>
     * </ol>
     * </p>
     */
    public static boolean isVisibleInChannel(String channel, SkillEntry s) {
        if (s == null || !s.isActive()) return false;
        if (s.getChannels() != null && !s.getChannels().isEmpty()) {
            return s.appliesToChannel(channel);
        }
        return scopeVisible(channel, s.getScope());
    }

    /**
     * 内置技能的适用通道：NapCat 系（scope=execq）→ QQ 系；其余 → 全通道。
     *
     * <p><b>这里曾经有一张「不在 execq 工具集里」的 14 个名字的人工清单</b>（sys/evaljs/download/
     * schedule/XML标签系统…），V4.0 已删除，原因是它已经彻底失效：</p>
     * <ul>
     *   <li>它只在 {@link #addBuiltinSkill} 注册内置文档时用来推导 channels 列；</li>
     *   <li>而内置文档自 V3.14 起不再由代码注册（{@code SystemSkillLib}/{@code NapCatSkillLib}
     *       只剩「旧记录下线」，技能正文全在 data/skills/*.md）—— {@code addBuiltinSkill} 全工程零调用；</li>
     *   <li>库里现存的内置行 channels 列是空的，走 {@link #scopeVisible} 兜底，那张清单从未作用到它们身上。</li>
     * </ul>
     * <p>以后真需要按工具可用性给某条内置文档限通道，直接在技能 md 的 front matter 里写
     * {@code channels:} —— 那才是现在唯一的通道归属来源。</p>
     */
    static String channelsForBuiltin(String name, String scope) {
        if ("execq".equals(scope)) return "execq,execs";
        return "execq,execs,console";
    }

    /** 将一条技能格式化为紧凑索引行（优先用 description 一句话描述，为空才取 content 前3行） */
    private String compactLine(SkillEntry s) {
        String desc = s.getDescription();
        if (desc == null || desc.trim().isEmpty()) {
            // 回退：description 为空时取 content 前3行作为摘要
            String content = s.getContent();
            if (content == null) return null;
            String[] lines = content.split("\\n");
            StringBuilder summary = new StringBuilder();
            for (int i = 0; i < Math.min(3, lines.length); i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (summary.length() > 0) summary.append(" ");
                summary.append(line);
            }
            desc = summary.toString();
        } else {
            desc = desc.trim();
        }
        if (desc.isEmpty()) return null;
        if (desc.length() > 120) desc = desc.substring(0, 117) + "...";
        return "- " + s.getName() + ": " + desc + "\n";
    }

    /**
     * 根据工具名查找完整技能说明书，用于按需注入详情。
     * 查找顺序: 1) 技能名精准匹配  2) 内容中含工具名  3) FTS5 全文搜索
     */
    public String getTagDetail(String tagName) {
        if (pm == null || tagName == null || tagName.trim().isEmpty()) return null;
        String tag = tagName.trim();

        boolean hasThirdParty = hasAnyThirdParty();

        // 0) LRU cache hit（三方技能库为空时才走缓存；三方优先可能覆盖同名内置，需实时查询）
        if (!hasThirdParty) {
            String cached = tagDetailCache.get(tag);
            if (cached != null) return cached;
        }

        // 0.5) 三方技能优先（同名/含工具名优先命中；不写缓存，删除/修改即时生效）
        if (hasThirdParty) {
            ThirdPartySkill tp = findThirdParty(tag);
            if (tp != null) {
                return buildThirdPartyDetail(tp);
            }
        }

        // 1) 按技能名精准匹配  2) 按内容中含工具名匹配（一次取快照，避免重复全表查询）
        List<SkillEntry> snapshot = listAll();
        for (SkillEntry s : snapshot) {
            if (s.isActive() && s.getName().equalsIgnoreCase(tag)) {
                String result = "## " + s.getName() + "\n" + s.getContent();
                tagDetailCache.put(tag, result);
                return result;
            }
        }

        String tagPattern = "`" + tag + "`";
        for (SkillEntry s : snapshot) {
            if (s.isActive() && s.getContent() != null && s.getContent().contains(tagPattern)) {
                String result = "## " + s.getName() + "\n" + s.getContent();
                tagDetailCache.put(tag, result);
                return result;
            }
        }

        // 3) FTS5 全文搜索兜底
        List<SkillEntry> matches = search(tag, 3);
        if (!matches.isEmpty()) {
            SkillEntry best = matches.get(0);
            String result = "## " + best.getName() + "\n" + best.getContent();
            tagDetailCache.put(tag, result);
            return result;
        }

        return null;
    }

    /** 构建三方技能详情：正文 + 通道/权限元数据头（让模型知道这个能力走哪些通道、要什么权限）。 */
    private String buildThirdPartyDetail(ThirdPartySkill tp) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(tp.getName()).append("（三方技能）\n");
        String ch = tp.getDeclaredChannels();
        sb.append("- 通道: ").append((ch == null || ch.isEmpty())
                ? "execq,execs,console（未声明 = 全通道）" : ch).append("\n");
        if (tp.getDeclaredPermission() != null) {
            sb.append("- 权限: ").append(tp.getDeclaredPermission()).append("\n");
        }
        if (tp.getDeclaredToolName() != null) {
            sb.append("- 提供的 FC 工具: ").append(tp.getDeclaredToolName()).append("\n");
        }
        sb.append("\n").append(tp.getContent());
        return sb.toString();
    }

    /** 全部三方技能（单文件 .md）。 */
    private List<ThirdPartySkill> getAllThirdParty() {
        List<ThirdPartySkill> all = new ArrayList<>();
        if (thirdPartyStore != null) all.addAll(thirdPartyStore.getAll());
        return all;
    }

    /** 是否存在三方技能。 */
    private boolean hasAnyThirdParty() {
        return thirdPartyStore != null && !thirdPartyStore.isEmpty();
    }

    /** 在三方技能中查找指定技能（原名 / 规范化工具名 / tp_ 前缀 / 内容匹配）。 */
    private ThirdPartySkill findThirdParty(String tag) {
        if (tag == null || tag.isEmpty()) return null;
        // AI 可能原样回传工具名（tp_xxx），也可能给规范化后的安全名或原名，三种都要能命中
        String bare = tag.startsWith("tp_") ? tag.substring(3) : tag;
        if (thirdPartyStore != null) {
            ThirdPartySkill tp = thirdPartyStore.get(bare);
            if (tp == null) tp = thirdPartyStore.getBySanitizedToolName(bare);
            if (tp != null) return tp;
        }
        String tagPattern = "`" + bare + "`";
        if (thirdPartyStore != null) {
            for (ThirdPartySkill s : thirdPartyStore.getAll()) {
                if (s.getName().equalsIgnoreCase(bare)
                        || (s.getContent() != null && s.getContent().contains(tagPattern))) {
                    return s;
                }
            }
        }
        return null;
    }

    /** 从 RouteCache 获取当前任务的最短路径提示 */
    public String getBestRoute(String task) {
        if (routeCache == null || task == null) return null;
        return routeCache.getRouteHint(task);
    }

    /** 设置 RouteCache（由 AiAgentActivity 注入） */
    public void setRouteCache(RouteCache cache) {
        this.routeCache = cache;
    }

    /** 设置三方技能库存储（由 AiAgentActivity 注入） */
    public void setThirdPartyStore(ThirdPartySkillStore store) {
        this.thirdPartyStore = store;
        // 工具数组是按通道缓存的；换了技能库就必须让缓存失效，
        // 否则「先建过工具列表、后接上技能库」会一直看到旧列表（剥离后的工具直接消失）。
        ToolDispatcher.invalidateToolSetCache();
    }

    /** 获取三方技能库存储（供 ToolDispatcher 等访问）。 */
    public ThirdPartySkillStore getThirdPartyStore() {
        return thirdPartyStore;
    }

    /** 从 RouteCache 获取降级路由提示（排除所有已试路径） */
    public String getFallbackRoute(String task, java.util.List<String> triedTags) {
        if (routeCache == null || task == null) return null;
        return routeCache.getFallbackRouteHint(task, triedTags);
    }

    /** 探索模式提示 — 所有已知路径失败，忽略权重自由探索 */
    public String getExploreContext() {
        return "\n## ⚠ 探索模式 — 已知路径全部失败\n"
                + "所有缓存的执行路径均已尝试且失败。\n"
                + "请忽略权重和路由提示，从工具索引位置重新开始。\n"
                + "自由组合工具、尝试新的执行方式、创造性地解决问题。\n"
                + "不要重复之前失败的工具序列!\n\n"
                + "\n**重要**: 如果你尝试了新的方法后仍然无法完成任务，\n"
                + "请直接向用户汇报已尝试的方法和失败原因，\n"
                + "并请求用户提供帮助或换一种方式描述需求。\n";
    }

    // ==================== 相关经验（动态层） ====================

    /** 动态经验层条数。 */
    private static final int RELEVANT_SKILL_ROWS = 3;
    /** 动态经验层每行的描述上限。 */
    private static final int RELEVANT_SKILL_DESC_CHARS = 90;
    /**
     * 近似重复阈值：新候选与已入选条目的项集合重叠度达到此值即视为「同一件事的另一种说法」，跳过。
     * <p>
     * 实测必要性：技能库里 4,601 条彼此相似度 98% 都 <0.5（不是简单复制），
     * 但<b>同一次查询的 Top-3 经常是同一件事的三种改写</b>
     * （实测「D盘share里的音乐文件在哪」的三条两两相似度 0.67 / 1.00 / 0.56），
     * 等于把 3 个名额全浪费掉。取 0.6 可拦住这种情况，同时保留真正互补的经验。
     * </p>
     */
    private static final double RELEVANT_SKILL_DUP_RATIO = 0.6;

    /** 文本指纹（中文 3-gram + 英数词，见 {@link sair.aiagent.util.TextFingerprint}）。 */
    private static java.util.Set<String> fingerprint(String text) {
        return sair.aiagent.util.TextFingerprint.trigram(text);
    }

    /** 两个指纹的包含度（交集 / 较小集合）。 */
    private static double overlapRatio(java.util.Set<String> a, java.util.Set<String> b) {
        return sair.aiagent.util.TextFingerprint.minOverlap(a, b);
    }

    /**
     * 相关经验提示（QQ 通道与控制台通道共用）—— 按 query 检索「学到过的经验」。
     * <p>
     * 自学技能已有 4600+ 条，不可能全塞进静态索引（会把工具文档挤空，也破坏 KV 前缀缓存），
     * 但「自生成技能」本身就是各通道的默认能力之一，看不见等于没有。
     * 所以按当前消息检索 Top-N，<b>只给名称 + 一句话描述</b>：
     * 它是目录不是正文，AI 需要细节时自己调 skillinfo 展开，因此注入面很小。
     * 另外做一次近似去重，避免几个名额被同一件事的多种说法占满。
     * </p>
     *
     * @return 供 prompt 直接拼接的文本块；无命中/不可检索时返回 null
     */
    public String buildRelevantSkillsHint(String query, int rows) {
        if (pm == null || query == null) return null;
        // 与记忆/笔记检索同一门控：短消息（好的/嗯/哈哈）不触发
        if (query.replaceAll("[\\s\\p{Punct}]+", "").length() < 4) return null;
        int n = rows > 0 ? rows : RELEVANT_SKILL_ROWS;
        try {
            // 多取几倍候选：去重会淘汰一部分，靠后的候补顶上
            List<SkillEntry> hits = pm.searchSkills(query, n * 3);
            if (hits == null || hits.isEmpty()) return null;
            StringBuilder lines = new StringBuilder();
            java.util.Set<String> seenName = new java.util.HashSet<>();
            List<java.util.Set<String>> picked = new java.util.ArrayList<>();
            int shown = 0;
            for (SkillEntry s : hits) {
                if (s == null || !s.isActive()) continue;
                // 只给「学到的经验」：内置技能是工具说明书，由静态索引按通道统一管理，
                // 这里再列一遍纯属重复。学到的经验不分通道（channels 为空 = 全通道）。
                if ("builtin".equalsIgnoreCase(s.getSource())) continue;
                String name = s.getName();
                if (name == null || name.isEmpty() || !seenName.add(name)) continue;
                String desc = s.getDescription();
                if (desc == null || desc.trim().isEmpty()) continue;
                // 近似去重：与已入选条目说的是同一件事就跳过
                java.util.Set<String> fp = fingerprint(name + " " + desc);
                boolean dup = false;
                for (java.util.Set<String> prev : picked) {
                    if (overlapRatio(fp, prev) >= RELEVANT_SKILL_DUP_RATIO) { dup = true; break; }
                }
                if (dup) continue;
                picked.add(fp);
                desc = desc.trim().replace('\n', ' ');
                if (desc.length() > RELEVANT_SKILL_DESC_CHARS) {
                    desc = desc.substring(0, RELEVANT_SKILL_DESC_CHARS) + "...";
                }
                lines.append("- ").append(name).append(": ").append(desc).append("\n");
                if (++shown >= n) break;
            }
            if (shown == 0) return null;
            return "## 相关经验技能（过往互动中总结出的经验，可参考；需要完整说明时用 skillinfo 查）\n"
                    + lines + "\n";
        } catch (Exception e) {
            AiAgentActivity.debugLog("[SkillBank] 相关经验检索失败: " + e.toString());
            return null;
        }
    }

    // ==================== 手动去重（ai/dedup） ====================

    /** 去重计划：一个重复组。 */
    public static final class DupGroup {
        /** 保留的代表条目。 */
        public final SkillEntry keeper;
        /** 被并入代表条目的重复条目。 */
        public final List<SkillEntry> duplicates = new ArrayList<>();
        DupGroup(SkillEntry keeper) { this.keeper = keeper; }
    }

    /**
     * 找近似重复的已有经验：先用 FTS 取候选，再按文本指纹重叠度确认。
     *
     * @return 重叠度 ≥ ratio 的最相似条目；没有则 null
     */
    SkillEntry findNearDuplicate(String name, String description, double ratio) {
        if (pm == null) return null;
        String probe = ((name == null ? "" : name) + " " + (description == null ? "" : description)).trim();
        if (probe.length() < 4) return null;
        List<SkillEntry> cands;
        try {
            cands = pm.searchSkills(probe, 5);
        } catch (Exception e) {
            return null;
        }
        if (cands == null || cands.isEmpty()) return null;
        java.util.Set<String> mine = fingerprint(name + " " + description);
        SkillEntry best = null;
        double bestRatio = 0;
        for (SkillEntry c : cands) {
            if (c == null || !c.isActive()) continue;
            if ("builtin".equalsIgnoreCase(c.getSource())) continue;   // 不与内置工具说明书合并
            double r = overlapRatio(mine, fingerprint(c.getName() + " " + c.getDescription()));
            if (r > bestRatio) { bestRatio = r; best = c; }
        }
        return (best != null && bestRatio >= ratio) ? best : null;
    }

    /**
     * 扫描技能库，找出近似重复组（只读，不改库）。
     * <p>
     * 算法：给每条经验算文本指纹（中文 3-gram + 英数词），用「倒排索引 + 稀有词分桶」避免
     * O(N²) 两两比较 —— 只在共享稀有词（出现次数 ≤ {@code bucketMax}）的条目之间比较。
     * 4,601 条实测秒级；涨到 10 万条也只是线性增长。
     * </p>
     *
     * @param ratio     判定重复的重叠度阈值
     * @param maxGroups 最多返回多少组（防止输出爆炸）
     */
    public List<DupGroup> planDedup(double ratio, int maxGroups) {
        List<DupGroup> groups = new ArrayList<>();
        if (pm == null) return groups;
        double th = ratio > 0 ? ratio : DEDUP_DEFAULT_RATIO;
        List<SkillEntry> all = listAll();
        // 只处理「学到的经验」：内置工具说明书是人工维护的，不参与自动合并
        List<SkillEntry> learned = new ArrayList<>();
        for (SkillEntry s : all) {
            if (s != null && s.isActive() && !"builtin".equalsIgnoreCase(s.getSource())) learned.add(s);
        }
        if (learned.size() < 2) return groups;

        List<java.util.Set<String>> fps = new ArrayList<>(learned.size());
        Map<String, List<Integer>> inverted = new java.util.HashMap<>();
        for (int i = 0; i < learned.size(); i++) {
            SkillEntry s = learned.get(i);
            java.util.Set<String> fp = fingerprint(s.getName() + " " + s.getDescription());
            fps.add(fp);
            for (String t : fp) {
                inverted.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
            }
        }
        final int bucketMax = 60;   // 只在这些「稀有词」的分桶内部比较
        boolean[] merged = new boolean[learned.size()];
        for (int i = 0; i < learned.size(); i++) {
            if (merged[i]) continue;
            DupGroup g = null;
            java.util.Set<Integer> cands = new java.util.LinkedHashSet<>();
            for (String t : fps.get(i)) {
                List<Integer> bucket = inverted.get(t);
                if (bucket == null || bucket.size() > bucketMax) continue;
                cands.addAll(bucket);
            }
            for (int j : cands) {
                if (j <= i || merged[j]) continue;
                if (overlapRatio(fps.get(i), fps.get(j)) < th) continue;
                if (g == null) g = new DupGroup(learned.get(i));
                g.duplicates.add(learned.get(j));
                merged[j] = true;
            }
            if (g != null) {
                groups.add(g);
                if (groups.size() >= maxGroups) break;
            }
        }
        return groups;
    }

    /**
     * 执行去重：把每组里的重复条目标记为 {@code merged} 并指向代表条目
     * （复用既有的 {@link PersistenceManager#markSkillMerged} —— 它本来就设计了这个字段，
     * 只是从来没有被调用过）。
     *
     * @return 实际合并掉的条数
     */
    public int applyDedup(List<DupGroup> groups) {
        if (pm == null || groups == null || groups.isEmpty()) return 0;
        int n = 0;
        for (DupGroup g : groups) {
            for (SkillEntry dup : g.duplicates) {
                if (pm.markSkillMerged(dup.getId(), g.keeper.getId())) n++;
            }
        }
        AiAgentActivity.debugLog("[SkillBank] 手动去重完成: 合并 " + n + " 条，涉及 " + groups.size() + " 组");
        return n;
    }

    /** 技能库统计（供 ai/dedup 输出）。 */
    public String dedupStats() {
        if (pm == null) return "(技能系统未初始化)";
        int total = 0, learned = 0, builtin = 0, dupHit = 0, used = 0;
        for (SkillEntry s : listAll()) {
            if (s == null) continue;
            total++;
            if ("builtin".equalsIgnoreCase(s.getSource())) builtin++;
            else {
                learned++;
                if (s.getDupHitCount() > 0) dupHit++;
                if (s.getSuccessCount() + s.getFailureCount() > 0) used++;
            }
        }
        return "经验技能 " + learned + " 条（内置 " + builtin + " 条，合计 " + total + "）"
                + " | 曾被抑制过重复 " + dupHit + " 条 | 有使用记录 " + used + " 条";
    }

    /** 通用去重组（技能/笔记共用；id+显示名）。 */
    public static final class DupItem {
        public final int id;
        public final String label;
        public DupItem(int id, String label) { this.id = id; this.label = label == null ? "" : label; }
    }

    /** 通用去重计划。 */
    public static final class DupPlan {
        public final String corpus;
        public final int total;
        public final List<DupGroup2> groups = new ArrayList<>();
        DupPlan(String corpus, int total) { this.corpus = corpus; this.total = total; }
        public int duplicateCount() {
            int n = 0;
            for (DupGroup2 g : groups) n += g.duplicates.size();
            return n;
        }
    }

    /** 通用去重组。 */
    public static final class DupGroup2 {
        public final DupItem keeper;
        public final List<DupItem> duplicates = new ArrayList<>();
        DupGroup2(DupItem keeper) { this.keeper = keeper; }
    }

    /**
     * 扫描<b>知识库笔记</b>的近似重复组（只读）。
     * <p>
     * 与技能用同一套「倒排索引 + 稀有词分桶」算法，但指纹与度量不同，原因是数据形状不同：
     * 笔记的<b>标题</b>才是身份（「这条笔记问的是什么」），正文是模板化的搜索结果堆
     * （成百上千条共享 {@code [搜索] "X" 的结果：… 百度百科 …} 这套模板，
     * 同主题实体词又高度重合），把正文算进指纹会把「同主题不同问题」判成重复 ——
     * 实测会把「原神 诺艾尔 满配 DPS」并进「原神 桑多涅 配队」。
     * 所以这里用<b>标题指纹（中文 2-gram + 英数词）+ 对称 Jaccard</b>。
     * </p>
     */
    public DupPlan planNoteDedup(double ratio, int maxGroups) {
        DupPlan plan = new DupPlan("notes", 0);
        if (pm == null) return plan;
        double th = ratio > 0 ? ratio : DEDUP_DEFAULT_RATIO;
        List<String[]> rows = pm.listNotesForDedup();
        plan = new DupPlan("notes", rows.size());
        if (rows.size() < 2) return plan;

        List<java.util.Set<String>> fps = new ArrayList<>(rows.size());
        Map<String, List<Integer>> inverted = new java.util.HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            // 指纹比较「问题本身」：剥掉 [联网搜索]/[网页抓取] 前缀，否则固定前缀会稀释相似度
            java.util.Set<String> fp = sair.aiagent.util.TextFingerprint.bigram(
                    sair.aiagent.util.TextFingerprint.normalizeNoteTitle(rows.get(i)[1]));
            fps.add(fp);
            for (String t : fp) inverted.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }
        final int bucketMax = 60;
        boolean[] merged = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            if (merged[i]) continue;
            java.util.Set<Integer> cands = new java.util.LinkedHashSet<>();
            for (String t : fps.get(i)) {
                List<Integer> bucket = inverted.get(t);
                if (bucket == null || bucket.size() > bucketMax) continue;
                cands.addAll(bucket);
            }
            DupGroup2 g = null;
            for (int j : cands) {
                if (j <= i || merged[j]) continue;
                if (sair.aiagent.util.TextFingerprint.jaccard(fps.get(i), fps.get(j)) < th) continue;
                if (g == null) g = new DupGroup2(new DupItem(
                        Integer.parseInt(rows.get(i)[0]), rows.get(i)[1]));
                g.duplicates.add(new DupItem(Integer.parseInt(rows.get(j)[0]), rows.get(j)[1]));
                merged[j] = true;
            }
            if (g != null) {
                plan.groups.add(g);
                if (plan.groups.size() >= maxGroups) break;
            }
        }
        return plan;
    }

    /** 执行笔记去重：把重复笔记标记 merged_into（内容保留，检索/注入不再返回）。 */
    public int applyNoteDedup(DupPlan plan) {
        if (pm == null || plan == null || plan.groups.isEmpty()) return 0;
        int n = 0;
        for (DupGroup2 g : plan.groups) {
            for (DupItem dup : g.duplicates) {
                if (pm.markNoteMerged(dup.id, g.keeper.id)) n++;
            }
        }
        AiAgentActivity.debugLog("[SkillBank] 笔记去重完成: 合并 " + n + " 条，涉及 " + plan.groups.size() + " 组");
        return n;
    }

    // ==================== 管理 ====================

    public boolean delete(int skillId) {
        if (pm == null) return false;
        return pm.removeSkill(skillId);
    }

    public boolean deprecate(int skillId) {
        if (pm == null) return false;
        return pm.deprecateSkill(skillId);
    }

    public boolean markMerged(int skillId, int mergedIntoId) {
        if (pm == null) return false;
        return pm.markSkillMerged(skillId, mergedIntoId);
    }

    public PersistenceManager getPersistenceManager() {
        return pm;
    }

    // ==================== 格式化 ====================

    public String formatSkillList() {
        List<SkillEntry> all = listAll();
        if (all.isEmpty()) return "(no skills)";
        StringBuilder sb = new StringBuilder();
        sb.append("Total: ").append(all.size()).append(" skills:\n");
        for (SkillEntry s : all) {
            sb.append("[").append(s.getId()).append("] ");
            sb.append(s.getName());
            sb.append(" v").append(s.getVersion());
            sb.append(" [").append(s.getCategory()).append("]");
            sb.append(" ").append(s.successRate()).append("%");
            sb.append(" (").append(s.getStatus()).append(")");
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public String formatSkillDetail(SkillEntry s) {
        if (s == null) return "(skill not found)";
        return "=== Skill #" + s.getId() + " ===\n"
                + "Name:    " + s.getName() + "\n"
                + "Version: " + s.getVersion() + "\n"
                + "Category:" + s.getCategory() + "\n"
                + "Status:  " + s.getStatus() + "\n"
                + "Source:  " + s.getSource() + "\n"
                + "Success: " + s.getSuccessCount() + " / Failure: " + s.getFailureCount()
                + " (" + s.successRate() + "%)\n"
                + "Parent:  " + (s.getParentSkillId() > 0 ? "#" + s.getParentSkillId() : "(original)") + "\n"
                + "Desc:    " + s.getDescription() + "\n"
                + "--- Content ---\n"
                + s.getContent();
    }

    // ==================== Export ====================

    /** 系统内置技能（source=builtin）是否为受保护技能，不允许导出。 */
    private boolean isBuiltin(SkillEntry s) {
        return s != null && "builtin".equalsIgnoreCase(s.getSource());
    }

    public String exportSkill(int id) {
        SkillEntry s = getSkill(id);
        if (s == null) return null;
        if (isBuiltin(s)) {
            return "系统技能 #" + id + "（" + s.getName() + "）不允许导出";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Skill: ").append(s.getName()).append("\n\n");
        sb.append("- ID: ").append(s.getId()).append("\n");
        sb.append("- Category: ").append(s.getCategory()).append("\n");
        sb.append("- Scope: ").append(s.getScope()).append("\n");
        sb.append("- Version: v").append(s.getVersion()).append("\n");
        sb.append("- Success Rate: ").append(s.successRate()).append("%\n");
        sb.append("- Status: ").append(s.getStatus()).append("\n\n");
        sb.append("## Content\n\n").append(s.getContent()).append("\n");
        return sb.toString();
    }

    public String exportAllSkills() {
        List<SkillEntry> all = listAll();
        if (all.isEmpty()) return "(no skills)";
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (SkillEntry s : all) {
            if (isBuiltin(s)) continue; // 系统内置技能不可导出
            sb.append("## ").append(s.getName()).append(" (v").append(s.getVersion()).append(")\n\n");
            sb.append(s.getContent()).append("\n\n---\n\n");
            count++;
        }
        if (count == 0) return "(系统技能库不允许导出)";
        return "# Skills Export\n\nTotal: " + count + " skills\n\n---\n\n" + sb.toString().trim();
    }

}
