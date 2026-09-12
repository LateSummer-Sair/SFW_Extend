package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.ImpressionEntry;

/**
 * 人格印象蒸馏引擎 —— 从 QQ 对话历史中通过 LLM 提取对某人的多维度印象。
 * <p>
 * 类似 SkillExtractor 的技能蒸馏机制，ImpressionExtractor 将冗余的对话
 * 历史转化为凝练的 5 维度人格印象（情绪/爱好/说话风格/虚实态度/图片习惯），
 * 存入 impressions 表供后续对话注入上下文。
 * </p>
 *
 * <h3>5 个维度</h3>
 * <ol>
 *   <li><b>emotionStability</b> — 情绪稳定性：冷静、易怒、爱哭、阴阳怪气等</li>
 *   <li><b>interests</b>       — 兴趣爱好：喜欢什么事物/游戏/话题/词汇</li>
 *   <li><b>speakingStyle</b>   — 说话风格：话痨/简洁/喜欢发长文/喜欢用表情</li>
 *   <li><b>honesty</b>         — 务实 vs 吹牛：求真务实或爱夸大其词</li>
 *   <li><b>imageHabit</b>      — 图片偏好：是否喜欢发图片、频率如何</li>
 * </ol>
 *
 * <h3>触发条件</h3>
 * <ul>
 *   <li>累计消息 >= 10 条 且 距上次蒸馏 > 1 小时</li>
 *   <li>最多每次蒸馏 3 个用户，防止 API 滥用</li>
 *   <li>异步执行，不阻塞主流程</li>
 * </ul>
 */
public class ImpressionExtractor {

    private static final int MIN_MESSAGES = 10;
    private static final long MIN_INTERVAL_MS = 3_600_000; // 1 hour
    private static final int MAX_BATCH = 3;

    private final DeepSeekClient client;
    private final PersistenceManager pm;
    private final Gson gson = new Gson();
    private volatile long lastRunTime = 0;

    public ImpressionExtractor(DeepSeekClient client, PersistenceManager pm) {
        this.client = client;
        this.pm = pm;
    }

    // ==================== 触发入口 ====================

    /**
     * Try to distill impressions for users who have accumulated enough messages.
     * Called periodically (e.g., every 30 minutes from a scheduled thread).
     *
     * @param memoryManager optional UnifiedQQMemoryManager for conversation history
     * @return number of users successfully distilled
     */
    public int distillIfNeeded(sair.aiagent.onebot.UnifiedQQMemoryManager memoryManager) {
        long now = System.currentTimeMillis();
        if (now - lastRunTime < MIN_INTERVAL_MS) return 0;
        lastRunTime = now;

        if (pm == null) return 0;

        List<Long> users = pm.getUsersForImpressionDistillation(MIN_MESSAGES, MIN_INTERVAL_MS);
        if (users.isEmpty()) return 0;

        int count = 0;
        for (int i = 0; i < Math.min(users.size(), MAX_BATCH); i++) {
            long qq = users.get(i);
            try {
                // Build conversation context for this user
                String convCtx = buildConversationContext(qq, memoryManager);
                if (convCtx == null || convCtx.isEmpty()) continue;

                // Get existing impression for merging
                ImpressionEntry existing = pm.getImpression(qq);

                // Call LLM for distillation
                ImpressionEntry distilled = distill(qq, convCtx, existing);
                if (distilled != null) {
                    pm.upsertImpression(distilled);
                    count++;
                    AiAgentActivity.debugLog("[ImpressionExtractor] distilled QQ=" + qq
                            + " (" + distilled.getMessageCount() + " msgs)");
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[ImpressionExtractor] QQ=" + qq + " failed: " + e.getMessage());
            }
        }

        if (count > 0) {
            AiAgentActivity.debugLog("[ImpressionExtractor] batch done: " + count + " users");
        }
        return count;
    }

    /**
     * Manually distill impression for a specific user.
     */
    public ImpressionEntry distillUser(long qq,
            sair.aiagent.onebot.UnifiedQQMemoryManager memoryManager) {
        String convCtx = buildConversationContext(qq, memoryManager);
        if (convCtx == null || convCtx.isEmpty()) return null;

        ImpressionEntry existing = pm.getImpression(qq);
        return distill(qq, convCtx, existing);
    }

    // ==================== 对话上下文构建 ====================

    private String buildConversationContext(long qq,
            sair.aiagent.onebot.UnifiedQQMemoryManager mem) {
        if (mem == null) return null;
        StringBuilder sb = new StringBuilder();

        List<String[]> convs = mem.getPrivateConversations(qq, 30);
        if (!convs.isEmpty()) {
            for (String[] c : convs) {
                String roleLabel = "user".equals(c[0]) ? "User" : "Bot";
                String content = c[1];
                if (content != null && content.length() > 500) content = content.substring(0, 500) + "...";
                sb.append(roleLabel).append(": ").append(content).append("\n");
            }
        }

        List<String[]> groupMsgs = mem.getUserGroupMessages(qq, 30);
        if (!groupMsgs.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append("=== 群聊发言 ===\n");
            for (String[] g : groupMsgs) {
                String nickname = (g[0] != null && !g[0].isEmpty()) ? g[0] : "用户";
                String content = g[1];
                if (content != null && content.length() > 500) content = content.substring(0, 500) + "...";
                sb.append(nickname).append(": ").append(content).append("\n");
            }
        }

        if (sb.length() == 0) return null;
        return sb.toString();
    }

    // ==================== LLM 蒸馏 ====================

    private ImpressionEntry distill(long qq, String conversationContext,
            ImpressionEntry existing) {
        try {
            String prompt = buildDistillPrompt(qq, conversationContext, existing);
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("user", prompt));
            String response = client.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());

            if (response == null || response.trim().isEmpty()) return null;

            DistilledImpression di = parseResponse(response);
            if (di == null) return null;

            // Merge with existing or create new
            ImpressionEntry result;
            if (existing != null) {
                result = existing;
                // Merge: only overwrite non-empty fields from LLM distillation
                if (!isEmpty(di.emotionStability)) result.setEmotionStability(di.emotionStability);
                if (!isEmpty(di.interests)) result.setInterests(di.interests);
                if (!isEmpty(di.speakingStyle)) result.setSpeakingStyle(di.speakingStyle);
                if (!isEmpty(di.honesty)) result.setHonesty(di.honesty);
                if (!isEmpty(di.imageHabit)) result.setImageHabit(di.imageHabit);
                if (!isEmpty(di.nickname)) result.setNickname(di.nickname);
                if (di.impressionLevel != null) result.setImpressionLevel(di.impressionLevel);
                result.setUpdatedAt(System.currentTimeMillis());
            } else {
                result = new ImpressionEntry(qq);
                result.setNickname(di.nickname != null ? di.nickname : "");
                result.setEmotionStability(di.emotionStability != null ? di.emotionStability : "");
                result.setInterests(di.interests != null ? di.interests : "");
                result.setSpeakingStyle(di.speakingStyle != null ? di.speakingStyle : "");
                result.setHonesty(di.honesty != null ? di.honesty : "");
                result.setImageHabit(di.imageHabit != null ? di.imageHabit : "");
                result.setImpressionLevel(di.impressionLevel != null ? di.impressionLevel : 0);
                result.setUpdatedAt(System.currentTimeMillis());
            }

            return result;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[ImpressionExtractor] distill failed: " + e.getMessage());
            return null;
        }
    }

    // ==================== Prompt 构建 ====================

    private String buildDistillPrompt(long qq, String convCtx, ImpressionEntry existing) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是人格分析专家。请分析以下QQ用户的对话历史，提炼出对该用户的5维度人格印象。\n\n");
        sb.append("用户QQ: ").append(qq).append("\n\n");

        // Truncate context if too long
        String ctx = convCtx;
        if (ctx.length() > 6000) ctx = ctx.substring(0, 6000) + "\n...(truncated)";

        sb.append("=== 对话历史 ===\n");
        sb.append(ctx);
        sb.append("\n\n");

        if (existing != null && existing.hasContent()) {
            sb.append("=== 现有印象（在此基础上更新） ===\n");
            sb.append(existing.toPromptContext());
            sb.append("\n\n");
        }

        sb.append("=== 分析维度 ===\n");
        sb.append("请从以下5个维度分析该用户，每维度用一句话概括（用中文）：\n\n");
        sb.append("1. emotionStability 情绪稳定性：是否情绪化？冷静/易怒/爱哭/阴阳怪气？\n");
        sb.append("2. interests 兴趣爱好：喜欢什么事物、游戏、话题？常说什么词汇？\n");
        sb.append("3. speakingStyle 说话风格：话多还是话少？发长文还是短句？语气特点？\n");
        sb.append("4. honesty 虚实态度：务实真诚还是爱吹牛夸大？\n");
        sb.append("5. imageHabit 图片习惯：喜欢发图片吗？发图频率如何？\n");
        sb.append("6. impressionLevel 印象好坏：-100~100 整数，正数=印象好，负数=印象差，0=中性。\n\n");

        sb.append("=== 重要规则 ===\n");
        sb.append("- 基于对话历史客观分析，不要凭空猜测\n");
        sb.append("- 如果某维度信息不足，对应字段留空字符串\n");
        sb.append("- 如果现有印象已准确，保持原样不要改动\n");
        sb.append("- 只输出 JSON，不要其他文字\n\n");

        sb.append("输出格式（JSON）：\n");
        sb.append("{\"nickname\":\"昵称\",\"emotionStability\":\"...\",\"interests\":\"...\",");
        sb.append("\"speakingStyle\":\"...\",\"honesty\":\"...\",\"imageHabit\":\"...\",\"impressionLevel\":0}\n");
        sb.append("Output ONLY the JSON object, no other text.");

        return sb.toString();
    }

    // ==================== 响应解析 ====================

    @SuppressWarnings("unused")
    private static class DistilledImpression {
        String nickname;
        String emotionStability;
        String interests;
        String speakingStyle;
        String honesty;
        String imageHabit;
        Integer impressionLevel;
    }

    private DistilledImpression parseResponse(String response) {
        try {
            String json = response.trim();
            // Handle markdown code blocks
            if (json.startsWith("```")) {
                int start = json.indexOf("{");
                int end = json.lastIndexOf("}");
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            if (!json.startsWith("{")) return null;

            java.lang.reflect.Type type =
                    new TypeToken<DistilledImpression>() {}.getType();
            return gson.fromJson(json, type);
        } catch (Exception e) {
            AiAgentActivity.debugLog("[ImpressionExtractor] parse failed: " + e.getMessage());
            return null;
        }
    }

    // ==================== 工具 ====================

    private static boolean isEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Get last run timestamp (for scheduling) */
    public long getLastRunTime() { return lastRunTime; }
}
