package sair.aiagent.core;

import java.util.ArrayList;
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
    private AgentSkillStore agentSkillStore;

    /** LRU tag-detail cache: tagName -> skillDetail, max 20 entries */
    private final Map<String, String> tagDetailCache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 20;
        }
    };

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
        return pm.addSkill(name, category, description, content, source, scope);
    }

    /**
     * 添加内置技能（SystemSkillLib/NapCatSkillLib 调用）。
     * 计算 SHA-256 哈希：相同跳过；旧数据无 hash 时以代码为权威源直接覆写，不走 LLM。
     */
    public int addBuiltinSkill(String name, String category, String description,
                               String content, String source, String scope) {
        if (pm == null) return -1;
        String newHash = SkillEntry.computeContentHash(content);
        SkillEntry existing = pm.findSimilarSkill(name, description);
        if (existing != null) {
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
        return pm.addSkill(name, category, description, content, source, scope);
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
            String merged = llmClient.chatSync(msgs, "deepseek-v4-flash");
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
            String rewritten = llmClient.chatSync(msgs, "deepseek-v4-flash");
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
            String improved = llmClient.chatSync(msgs, "deepseek-v4-flash");
            if (improved != null && !improved.trim().isEmpty()
                    && !improved.trim().equals(skill.getContent())) {
                String validatePrompt = "Compare two versions of a skill and decide if the NEW version "
                        + "is genuinely better than the OLD version.\n\n"
                        + "OLD (v" + skill.getVersion() + "):\n" + skill.getContent() + "\n\n"
                        + "NEW (v" + (skill.getVersion() + 1) + "):\n" + improved.trim() + "\n\n"
                        + "Reply ONLY with 'YES' if the new version is better, otherwise 'NO'.";
                List<sair.aiagent.model.ChatMessage> vmsgs = new ArrayList<>();
                vmsgs.add(new sair.aiagent.model.ChatMessage("user", validatePrompt));
                String validation = llmClient.chatSync(vmsgs, "deepseek-v4-flash");
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
     * 构建「稳定」工具索引（Tier0 三方技能 + Tier1 内置技能），不含任务相关路由提示。
     * <p>输出在技能不变时逐字节稳定，适合放进 system 前缀或动态上下文首部，命中 KV 前缀缓存。</p>
     */
    public String buildStableIndex(String channel, int maxTokens) {
        if (pm == null) return null;
        int budget = (maxTokens > 0 ? maxTokens : 800) * 4;
        int used = 0;
        StringBuilder sb = new StringBuilder();

        // Tier 0: 三方技能 + agentskills.io 技能包（三方优先，所有通道可见）
        List<ThirdPartySkill> tpAll = getAllThirdParty();
        if (!tpAll.isEmpty()) {
            sb.append("## 三方技能（优先于内置同名技能）\n");
            tpAll.sort(Comparator.comparing(ThirdPartySkill::getName));
            for (ThirdPartySkill tp : tpAll) {
                String line = compactThirdPartyLine(tp);
                if (line == null || line.isEmpty()) continue;
                if (used + line.length() > budget) break;
                sb.append(line);
                used += line.length();
            }
        }

        // Tier 1: 技能索引，每条一行。channel: execq=仅NapCat, console=排除NapCat, execs=全部
        List<SkillEntry> allActive = listAll();
        if (!allActive.isEmpty()) {
            sb.append("## 可用工具索引（不熟悉某技能/工具用法时，先调用 skillinfo 工具查详情）\n");
            for (SkillEntry s : allActive) {
                if (!s.isWorthInjecting()) continue;
                if (!scopeVisible(channel, s.getScope())) continue;
                if (isThirdPartyShadowed(s.getName())) continue;  // 三方同名优先，跳过内置
                String line = compactLine(s);
                if (line == null || line.isEmpty()) continue;
                if (used + line.length() > budget) break;
                sb.append(line);
                used += line.length();
            }
        }

        if (sb.length() < 20) return null;
        return sb.toString();
    }

    /** 判断某内置技能名是否被同名三方技能覆盖（三方优先，含单文件与技能包）。 */
    private boolean isThirdPartyShadowed(String name) {
        if (name == null) return false;
        if (thirdPartyStore != null && thirdPartyStore.get(name) != null) return true;
        return agentSkillStore != null && agentSkillStore.get(name) != null;
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
        // 含代码段时追加函数签名，让 AI 一眼看到可调用方式
        if (tp.hasCodeBlocks()) {
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

    /** 判断某 scope 技能在当前 channel 下是否可见。 */
    private static boolean scopeVisible(String channel, String scope) {
        if ("execq".equals(channel)) {
            return "execq".equals(scope);  // execq 普通消息：仅 NapCat 技能
        }
        if ("console".equals(channel)) {
            return !"execq".equals(scope);  // 本地 console：排除 NapCat
        }
        return true;  // execs 或其它：全部可见
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

        // 1) 按技能名精准匹配
        for (SkillEntry s : listAll()) {
            if (s.isActive() && s.getName().equalsIgnoreCase(tag)) {
                String result = "## " + s.getName() + "\n" + s.getContent();
                tagDetailCache.put(tag, result);
                return result;
            }
        }

        // 2) 按内容中含工具名匹配（如内容包含 `weather`）
        String tagPattern = "`" + tag + "`";
        for (SkillEntry s : listAll()) {
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

    /** 构建三方技能详情：单文件技能返回正文；技能包临时读取 SKILL.md 正文 + 附属文档。 */
    private String buildThirdPartyDetail(ThirdPartySkill tp) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(tp.getName()).append("（三方技能");
        if (tp.isPackage()) sb.append("·技能包");
        sb.append("）\n");
        if (tp.isPackage()) {
            if (agentSkillStore != null) {
                String main = agentSkillStore.readMainContent(tp);
                if (main != null && !main.isEmpty()) {
                    sb.append(main).append("\n");
                }
                if (!tp.getAttachmentPaths().isEmpty()) {
                    sb.append("\n### 技能包附属文档\n");
                    for (String rel : tp.getAttachmentPaths().keySet()) {
                        String content = agentSkillStore.readAttachment(tp, rel);
                        sb.append("\n--- [").append(rel).append("] ---\n").append(content).append("\n");
                    }
                }
            }
        } else {
            sb.append(tp.getContent());
        }
        return sb.toString();
    }

    /** 合并单文件三方技能与 agentskills.io 技能包。 */
    private List<ThirdPartySkill> getAllThirdParty() {
        List<ThirdPartySkill> all = new ArrayList<>();
        if (thirdPartyStore != null) all.addAll(thirdPartyStore.getAll());
        if (agentSkillStore != null) all.addAll(agentSkillStore.getAll());
        return all;
    }

    /** 是否存在三方技能（单文件或技能包）。 */
    private boolean hasAnyThirdParty() {
        return (thirdPartyStore != null && !thirdPartyStore.isEmpty())
                || (agentSkillStore != null && !agentSkillStore.isEmpty());
    }

    /** 在单文件三方技能与技能包中查找指定技能（名称精准优先，再内容匹配）。 */
    private ThirdPartySkill findThirdParty(String tag) {
        if (thirdPartyStore != null) {
            ThirdPartySkill tp = thirdPartyStore.get(tag);
            if (tp != null) return tp;
        }
        if (agentSkillStore != null) {
            ThirdPartySkill tp = agentSkillStore.get(tag);
            if (tp != null) return tp;
        }
        String tagPattern = "`" + tag + "`";
        if (thirdPartyStore != null) {
            for (ThirdPartySkill s : thirdPartyStore.getAll()) {
                if (s.getName().equalsIgnoreCase(tag)
                        || (s.getContent() != null && s.getContent().contains(tagPattern))) {
                    return s;
                }
            }
        }
        if (agentSkillStore != null) {
            for (ThirdPartySkill s : agentSkillStore.getAll()) {
                if (s.getName().equalsIgnoreCase(tag)) {
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
    }

    /** 获取三方技能库存储（供 ToolDispatcher 等访问）。 */
    public ThirdPartySkillStore getThirdPartyStore() {
        return thirdPartyStore;
    }

    /** 设置 agentskills.io 技能包存储（由 AiAgentActivity 注入） */
    public void setAgentSkillStore(AgentSkillStore store) {
        this.agentSkillStore = store;
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
