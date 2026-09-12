package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.SkillEntry;

/**
 * 技能蒸馏引擎 —— 从 Agent 执行轨迹中通过 LLM 提取可复用技能。
 * <p>
 * 对应 SkillRL 论文中的"经验蒸馏机制"：将冗余的交互轨迹转化为
 * 凝练、可落地的技能知识，存入 SkillBank。
 * </p>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li>Agent 执行完毕后，收集本轮轨迹（任务、操作、结果、成败）</li>
 *   <li>若轨迹质量足够（>= 3 个有效步骤），构建提取 prompt</li>
 *   <li>调用 DeepSeek API 请求结构化 JSON 响应（candidateSkills）</li>
 *   <li>解析 JSON，每个候选技能调用 SkillBank.addSkill()（自动去重合并）</li>
 * </ol>
 *
 * <h3>约束</h3>
 * <ul>
 *   <li>两次提取之间最小间隔 60 秒，防止频繁 API 调用</li>
 *   <li>仅在 exec/execs 模式触发（chat 模式不提取）</li>
 *   <li>异步执行，不阻塞主 Agent 流程</li>
 * </ul>
 */
public class SkillExtractor {

    private static final int MIN_ACTIONS = 2;
    private static final long MIN_INTERVAL_MS = 60_000; // 1 minute

    private final DeepSeekClient client;
    private final SkillBank skillBank;
    private final Gson gson = new Gson();
    private final AtomicLong lastExtractTime = new AtomicLong(0);

    public SkillExtractor(DeepSeekClient client, SkillBank skillBank) {
        this.client = client;
        this.skillBank = skillBank;
    }

    // ==================== 轨迹数据模型 ====================

    /** Single action step in an Agent trajectory */
    public static class ActionStep {
        public String type;
        public String content;
        public String result;
        public boolean success;
        public String reasoningContent;  // AI's chain-of-thought for this round

        public ActionStep(String type, String content, String result, boolean success) {
            this(type, content, result, success, "");
        }

        public ActionStep(String type, String content, String result, boolean success, String reasoningContent) {
            this.type = type;
            this.content = content != null ? trunc(content, 200) : "";
            this.result = result != null ? trunc(result, 300) : "";
            this.success = success;
            this.reasoningContent = reasoningContent != null ? trunc(reasoningContent, 500) : "";
        }
    }

    /** Full Agent execution trajectory */
    public static class AgentTrajectory {
        public String task;
        public String mode;   // "exec" or "execs"
        public boolean overallSuccess;
        public List<ActionStep> steps = new ArrayList<>();
        public String reasoningContent;  // accumulated chain-of-thought across rounds

        public AgentTrajectory(String task, String mode) {
            this.task = task;
            this.mode = mode;
        }

        public void addStep(String type, String content, String result, boolean success) {
            steps.add(new ActionStep(type, content, result, success));
        }

        public void addStep(String type, String content, String result, boolean success, String reasoningContent) {
            steps.add(new ActionStep(type, content, result, success, reasoningContent));
        }

        public boolean isWorthExtracting() {
            return steps.size() >= MIN_ACTIONS;
        }
    }

    // ==================== 核心提取 ====================

    /**
     * Extract skills from an Agent execution trajectory.
     * Called asynchronously after Agent execution completes.
     */
    public void extract(AgentTrajectory trajectory) {
        if (trajectory == null || !trajectory.isWorthExtracting()) return;

        // Rate limit (atomic compare-and-set)
        long now = System.currentTimeMillis();
        long prev = lastExtractTime.get();
        if (now - prev < MIN_INTERVAL_MS) return;
        if (!lastExtractTime.compareAndSet(prev, now)) return;  // CAS failed, another thread is extracting

        try {
            String prompt = buildExtractionPrompt(trajectory, null);
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("user", prompt));
            String response = client.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());

            if (response != null && !response.trim().isEmpty()) {
                List<CandidateSkill> candidates = parseResponse(response);
                if (candidates != null) {
                    int added = 0;
                    for (CandidateSkill cs : candidates) {
                        if (cs.name != null && !cs.name.trim().isEmpty()
                                && cs.content != null && !cs.content.trim().isEmpty()) {
                            String scope = (cs.scope != null) ? cs.scope : "task";
                            int id = skillBank.addSkill(cs.name, cs.category,
                                    cs.description, cs.content, "extracted", scope);
                            if (id > 0) added++;
                        }
                    }
                    if (added > 0) {
                        AiAgentActivity.debugLog("[SkillExtractor] extracted "
                                + added + " skills from trajectory ("
                                + trajectory.steps.size() + " steps)");
                    }
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[SkillExtractor] extraction failed: " + e.getMessage());
        }
    }

    /**
     * Immediate synchronous extraction — bypasses rate limit for explicit
     * user-triggered extraction via the skillextract tool.
     *
     * @param trajectory the current conversation trajectory
     * @param focusDescription what the user wants to distill (may be null)
     * @return number of skills extracted
     */
    public int extractImmediate(AgentTrajectory trajectory, String focusDescription) {
        if (trajectory == null || !trajectory.isWorthExtracting()) return 0;

        // Update lastExtractTime atomically
        lastExtractTime.set(System.currentTimeMillis());

        try {
            String prompt = buildExtractionPrompt(trajectory, focusDescription);
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("user", prompt));
            String response = client.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());

            if (response != null && !response.trim().isEmpty()) {
                List<CandidateSkill> candidates = parseResponse(response);
                if (candidates != null) {
                    int added = 0;
                    for (CandidateSkill cs : candidates) {
                        if (cs.name != null && !cs.name.trim().isEmpty()
                                && cs.content != null && !cs.content.trim().isEmpty()) {
                            String scope = (cs.scope != null) ? cs.scope : "task";
                            int id = skillBank.addSkill(cs.name, cs.category,
                                    cs.description, cs.content, "extracted", scope);
                            if (id > 0) added++;
                        }
                    }
                    if (added > 0) {
                        AiAgentActivity.debugLog("[SkillExtractor] immediate: extracted "
                                + added + " skills (focus: "
                                + (focusDescription != null ? focusDescription : "none") + ")");
                    }
                    return added;
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[SkillExtractor] immediate extraction failed: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Extract skills from recent journal entries (manual trigger).
     */
    public int extractFromJournal(PersistenceManager pm, int recentEntries) {
        if (pm == null) return 0;
        String journalCtx = pm.buildJournalContext(recentEntries);
        if (journalCtx == null || journalCtx.trim().isEmpty()) return 0;

        try {
            String prompt = "You are analyzing past agent activity logs to extract reusable skills.\n\n"
                    + "Recent activity log:\n" + journalCtx + "\n\n"
                    + "Identify any recurring patterns, learned preferences, or strategies "
                    + "that could be formalized as skills. Output as JSON array:\n"
                    + "[{\"name\":\"...\",\"category\":\"preference|strategy|lesson|rule\","
                    + "\"description\":\"...\",\"content\":\"...\",\"scope\":\"general|task\"}]\n"
                    + "Only include skills that are clearly supported by the logs. Output ONLY the JSON array.";

            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("user", prompt));
            String response = client.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());

            if (response != null && !response.trim().isEmpty()) {
                List<CandidateSkill> candidates = parseResponse(response);
                if (candidates != null) {
                    int added = 0;
                    for (CandidateSkill cs : candidates) {
                        if (cs.name != null && !cs.name.trim().isEmpty()
                                && cs.content != null && !cs.content.trim().isEmpty()) {
                            String scope = (cs.scope != null) ? cs.scope : "task";
                            int id = skillBank.addSkill(cs.name, cs.category,
                                    cs.description, cs.content, "extracted", scope);
                            if (id > 0) added++;
                        }
                    }
                    return added;
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[SkillExtractor] journal extraction failed: " + e.getMessage());
        }
        return 0;
    }

    /**
     * Extract skills from free-form conversation context (chat/QQ).
     * Unlike trajectory-based extraction, this analyzes natural dialogue
     * patterns, user preferences, and interaction styles.
     */
    public int extractConversationSkill(String conversationContext, String focusDescription) {
        if (conversationContext == null || conversationContext.trim().isEmpty()) return 0;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("You are analyzing a conversation to extract reusable patterns and skills.\n\n");
            if (focusDescription != null && !focusDescription.trim().isEmpty()) {
                sb.append("FOCUS: The user wants to distill: \"").append(focusDescription).append("\"\n\n");
            }
            sb.append("Conversation:\n");
            String ctx = conversationContext;
            if (ctx.length() > 5000) ctx = ctx.substring(0, 5000) + "\n...(truncated)";
            sb.append(ctx).append("\n\n");
            sb.append("Identify reusable skills/patterns. Output JSON array:\n");
            sb.append("[{\"name\":\"...\",\"category\":\"preference|strategy|lesson|rule|persona\",");
            sb.append("\"description\":\"...\",\"content\":\"...\",\"scope\":\"general|task|persona\"}]\n");
            sb.append("For style/preference patterns about specific people, use scope=\"persona\".\n");
            sb.append("Output ONLY the JSON array, no other text.");

            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("user", sb.toString()));
            String response = client.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());

            if (response != null && !response.trim().isEmpty()) {
                List<CandidateSkill> candidates = parseResponse(response);
                if (candidates != null) {
                    int added = 0;
                    for (CandidateSkill cs : candidates) {
                        if (cs.name != null && !cs.name.trim().isEmpty()
                                && cs.content != null && !cs.content.trim().isEmpty()) {
                            String scope = (cs.scope != null) ? cs.scope : "task";
                            int id = skillBank.addSkill(cs.name, cs.category,
                                    cs.description, cs.content, "extracted", scope);
                            if (id > 0) added++;
                        }
                    }
                    if (added > 0) {
                        AiAgentActivity.debugLog("[SkillExtractor] conversation: extracted "
                                + added + " skills");
                    }
                    return added;
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[SkillExtractor] conversation extraction failed: " + e.getMessage());
        }
        return 0;
    }

    // ==================== Prompt 构建 ====================

    private String buildExtractionPrompt(AgentTrajectory t, String focusDescription) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are analyzing an AI agent's execution trajectory to extract reusable skills.\n\n");

        // Focus guidance: user explicitly requested extraction of a specific pattern
        if (focusDescription != null && !focusDescription.trim().isEmpty()) {
            sb.append("=== EXTRACTION FOCUS ===\n");
            sb.append("The user explicitly requested distillation of: \"").append(focusDescription.trim()).append("\"\n");
            sb.append("Prioritize extracting skills related to this focus.\n\n");
        }

        sb.append("Task: ").append(t.task).append("\n");
        sb.append("Mode: ").append(t.mode).append("\n");
        sb.append("Overall: ").append(t.overallSuccess ? "SUCCESS" : "FAILED").append("\n");
        if (t.reasoningContent != null && !t.reasoningContent.isEmpty()) {
            sb.append("AI Reasoning: ").append(trunc(t.reasoningContent, 300)).append("\n");
        }
        sb.append("\n");

        // Differentiated distillation (SkillRL paper): separate success and failure steps
        java.util.List<ActionStep> successSteps = new java.util.ArrayList<>();
        java.util.List<ActionStep> failSteps = new java.util.ArrayList<>();
        for (ActionStep s : t.steps) {
            if (s.success) successSteps.add(s); else failSteps.add(s);
        }

        if (!successSteps.isEmpty()) {
            sb.append("=== SUCCESSFUL steps (extract decision patterns, key actions, reusable strategies) ===\n");
            for (int i = 0; i < successSteps.size(); i++) {
                ActionStep s = successSteps.get(i);
                sb.append(i + 1).append(". [").append(s.type).append("] OK\n");
                sb.append("   Content: ").append(s.content).append("\n");
                sb.append("   Result: ").append(s.result).append("\n");
                if (s.reasoningContent != null && !s.reasoningContent.isEmpty()) {
                    sb.append("   Reasoning: ").append(s.reasoningContent).append("\n");
                }
            }
        }

        if (!failSteps.isEmpty()) {
            sb.append("\n=== FAILED steps (synthesize anti-patterns, fault points, corrective rules) ===\n");
            for (int i = 0; i < failSteps.size(); i++) {
                ActionStep s = failSteps.get(i);
                sb.append(i + 1).append(". [").append(s.type).append("] FAIL\n");
                sb.append("   Content: ").append(s.content).append("\n");
                sb.append("   Result: ").append(s.result).append("\n");
            }
        }

        sb.append("\nBased on this trajectory, identify reusable skills. For EACH skill provide:\n");
        sb.append("- name: short identifier (max 40 chars)\n");
        sb.append("- category: preference / strategy / lesson / rule\n");
        sb.append("- description: one-sentence summary\n");
        sb.append("- content: the actual skill rule\n");
        sb.append("- scope: 'general' for cross-task principles, 'task' for task-specific, 'persona' for style/personality patterns\n\n");
        sb.append("CRITICAL RULES:\n");
        sb.append("1. Extract BOTH success patterns AND failure lessons\n");
        sb.append("2. Generalize beyond the specific task: can this apply to FUTURE tasks?\n");
        sb.append("3. If no reusable skill, output empty array [].\n\n");
        sb.append("Output as JSON array:\n");
        sb.append("[{\"name\":\"...\",\"category\":\"preference|strategy|lesson|rule\",");
        sb.append("\"description\":\"...\",\"content\":\"...\",\"scope\":\"general|task\"}]\n");
        sb.append("Output ONLY the JSON array, no other text.");

        return sb.toString();
    }

    // ==================== 响应解析 ====================

    /** LLM response candidate skill */
    @SuppressWarnings("unused")
    private static class CandidateSkill {
        String name;
        String category;
        String description;
        String content;
        String scope;
    }

    private List<CandidateSkill> parseResponse(String response) {
        try {
            // Extract JSON array from response (handle markdown code blocks)
            String json = response.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf("[");
                int end = json.lastIndexOf("]");
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            if (!json.startsWith("[")) return null;

            java.lang.reflect.Type listType =
                    new TypeToken<List<CandidateSkill>>() {}.getType();
            return gson.fromJson(json, listType);
        } catch (Exception e) {
            AiAgentActivity.debugLog("[SkillExtractor] JSON parse failed: " + e.getMessage());
            return null;
        }
    }

    // ==================== 工具 ====================

    private static String trunc(String s, int maxLen) {
        if (s == null || s.isEmpty()) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
