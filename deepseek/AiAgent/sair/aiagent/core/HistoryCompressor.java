package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ChatMessage;

/**
 * History Compressor — LLM 驱动的对话历史压缩器。
 * <p>
 * 参照 AI Agent 记忆系统设计文章中的 HistoryCompressor：
 * <ul>
 *   <li>对话 ≤2 轮不压缩</li>
 *   <li>LLM 将旧历史压缩为摘要（保留核心需求和关键信息）</li>
 *   <li>保留最近 2 轮原始对话</li>
 *   <li>摘要存储到 app_state，跨会话复用</li>
 * </ul>
 * </p>
 */
public class HistoryCompressor {

    /** 最少对话轮数（少于此数不压缩） */
    private static final int MIN_ROUNDS_TO_COMPRESS = 2;
    /** 保留最近 N 轮对话不压缩 */
    private static final int KEEP_RECENT_ROUNDS = 2;
    /** 摘要最大字符数 */
    private static final int MAX_SUMMARY_CHARS = 500;
    /** 压缩触发 token 阈值（超过此 token 数触发压缩） */
    private static final int COMPRESS_TOKEN_THRESHOLD = 50_000;

    private final DeepSeekClient client;
    private String compressedSummary;

    public HistoryCompressor(DeepSeekClient client) {
        this.client = client;
    }

    /** 加载之前保存的压缩摘要 */
    public void loadSummary(String summary) {
        this.compressedSummary = summary;
    }

    /** 获取当前压缩摘要 */
    public String getSummary() {
        return compressedSummary;
    }

    /**
     * 判断是否需要压缩。
     * @param totalTokens 当前对话历史的总 token 估算
     * @return true 需要压缩
     */
    public boolean shouldCompress(int totalTokens) {
        return totalTokens > COMPRESS_TOKEN_THRESHOLD;
    }

    /**
     * 压缩对话历史，返回压缩后的上下文文本。
     * <p>
     * 压缩策略：
     * 1. 保留最近 KEEP_RECENT_ROUNDS 轮对话为原始格式
     * 2. 旧对话用 LLM 压缩为摘要
     * 3. 格式：[压缩摘要] + [最近 N 轮原始对话]
     * </p>
     *
     * @param messages 完整的对话消息列表（不含 system 消息）
     * @return 压缩后的上下文字符串
     */
    public synchronized String compress(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) return null;

        // 计算轮数（一对 user+assistant = 1 轮）
        int totalRounds = countRounds(messages);
        if (totalRounds <= MIN_ROUNDS_TO_COMPRESS) return null;

        // 分离旧历史（需压缩的部分）和最近对话
        int recentStart = Math.max(0, messages.size() - (KEEP_RECENT_ROUNDS * 2));
        List<ChatMessage> oldMessages = new ArrayList<>();
        List<ChatMessage> recentMessages = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (i < recentStart) {
                oldMessages.add(messages.get(i));
            } else {
                recentMessages.add(messages.get(i));
            }
        }

        if (oldMessages.isEmpty()) return null;

        // 构建压缩提示词
        String compressPrompt = buildCompressPrompt(oldMessages);
        if (compressPrompt == null || compressPrompt.isEmpty()) return null;

        // 调用 LLM 生成摘要
        String summary = callLLMForSummary(compressPrompt);
        if (summary == null || summary.trim().isEmpty()) return null;

        this.compressedSummary = summary.trim();

        // 构建压缩后的上下文：摘要 + 最近对话
        StringBuilder ctx = new StringBuilder();
        ctx.append("## Conversation Summary\n");
        ctx.append("The following is a summary of the earlier conversation:\n");
        ctx.append(summary.trim()).append("\n\n");

        if (!recentMessages.isEmpty()) {
            ctx.append("## Recent Messages\n");
            for (ChatMessage m : recentMessages) {
                String role = m.getRole();
                String content = m.getContent();
                if (content != null && content.length() > 300) {
                    content = content.substring(0, 300) + "...";
                }
                String label = "user".equals(role) ? "User" : "assistant".equals(role) ? "Assistant" : role;
                ctx.append(label).append(": ").append(content).append("\n");
            }
        }

        return ctx.toString();
    }

    /** 构建压缩提示词 */
    private String buildCompressPrompt(List<ChatMessage> oldMessages) {
        if (oldMessages.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("Please summarize the following conversation history.\n");
        sb.append("Keep it under ").append(MAX_SUMMARY_CHARS).append(" characters.\n");
        sb.append("Focus on:\n");
        sb.append("- Core topics and user needs discussed\n");
        sb.append("- Any decisions or preferences mentioned\n");
        sb.append("- Important facts or information exchanged\n");
        sb.append("Ignore greetings, small talk, and irrelevant details.\n\n");
        sb.append("--- Conversation to summarize ---\n");

        int chars = 0;
        for (ChatMessage m : oldMessages) {
            String role = m.getRole();
            String content = m.getContent();
            if (content == null) content = "";
            if (content.length() > 500) content = content.substring(0, 500) + "...";
            String line = role + ": " + content + "\n";
            if (chars + line.length() > 8000) {
                sb.append("... (truncated)\n");
                break;
            }
            sb.append(line);
            chars += line.length();
        }

        return sb.toString();
    }

    /** 调用 LLM 获取摘要（使用 execq/flash 模型以节省成本） */
    private String callLLMForSummary(String prompt) {
        try {
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("system",
                "You are a conversation summarizer. Output ONLY the summary text, no markdown formatting, no prefixes."));
            msgs.add(new ChatMessage("user", prompt));

            // 使用 execq（flash）模型（非流式，更便宜更快）
            String result = client.chatSync(msgs, sair.aiagent.core.AiConfig.getInstance().getExecqModel());
            if (result != null && !result.trim().isEmpty()) {
                // 清理输出
                result = result.trim();
                if (result.length() > MAX_SUMMARY_CHARS) {
                    result = result.substring(0, MAX_SUMMARY_CHARS);
                }
                AiAgentActivity.debugLog("[HistoryCompressor] summary: " +
                    result.substring(0, Math.min(80, result.length())) + "...");
                return result;
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[HistoryCompressor] LLM call failed: " + e.getMessage());
        }
        return null;
    }

    /** 统计对话轮数 */
    private int countRounds(List<ChatMessage> messages) {
        int rounds = 0;
        boolean hasUser = false;
        for (ChatMessage m : messages) {
            if ("user".equals(m.getRole())) {
                hasUser = true;
            } else if ("assistant".equals(m.getRole()) && hasUser) {
                rounds++;
                hasUser = false;
            }
        }
        if (hasUser) rounds++; // 最后一条用户消息也算一轮
        return rounds;
    }
}
