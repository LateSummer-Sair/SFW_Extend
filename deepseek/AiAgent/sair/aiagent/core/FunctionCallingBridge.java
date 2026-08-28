package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;

import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.ToolCall;
import sair.aiagent.model.ToolDefinition;

/**
 * Function Calling Bridge —— DeepSeek 原生 Function Calling 引擎。
 * 将项目的标签工具映射为 ToolDefinition（JSON Schema），驱动多轮 tool_calls 循环。
 */
public class FunctionCallingBridge {

    /** 常规工具执行连续失败达到该次数后，切换动态注入（eval，Java 编译执行）兜底。 */
    public static final int FALLBACK_THRESHOLD = 5;

    /** 工具执行连续失败（抛异常或返回错误）达到该次数时终止循环。 */
    public static final int MAX_CONSECUTIVE_FAILURES = 10;

    /** 验证闭环：审查者评估不通过时允许重新生成的最大次数（防死循环）。 */
    public static final int MAX_CRITIC_RETRIES = 2;

    /** 工具调用总次数硬上限：防止 AI 陷入「工具全部成功但一直探索/翻找」的无限循环，永不输出最终回复。 */
    public static final int MAX_TOTAL_TOOL_CALLS = 60;

    /** 接近硬上限时，注入一次「尽快总结收尾」提示的触发阈值。 */
    public static final int WARN_TOOL_CALLS = 45;

    private final DeepSeekClient client;

    /** 看图指令关键词（用户明确要求看图片内容） */
    private static final String[] IMAGE_REQUEST_KEYWORDS = {
        "看图", "识图", "识别图片", "图片里", "图里", "这张图", "什么图",
        "图片上", "图上", "帮我看看图", "看看图", "图中", "图内", "图片内容"
    };

    /** 审查者子 Agent（可空；非空时对最终回复做验证闭环）。 */
    private volatile CriticAgent critic;

    public FunctionCallingBridge(DeepSeekClient client) {
        this.client = client;
    }

    /** 设置审查者子 Agent，启用最终回复验证闭环。 */
    public void setCritic(CriticAgent critic) {
        this.critic = critic;
    }

    /**
     * 使用 {@link ToolDispatcher} 执行 Function Calling 多轮循环。
     * <p>支持工具执行上下文（ToolContext）、
     * 连续失败计数（防止空转/死循环）以及停止检查回调。</p>
     *
     * @param task         用户任务
     * @param systemPrompt 系统提示词
     * @param tools        工具定义列表
     * @param model        模型名
     * @param dispatcher   工具分发器
     * @param ctx          工具执行上下文
     * @param stopCheck    停止检查回调（返回 true 则中断循环），可为 null
     */
    public String runWithDispatcher(String task, String systemPrompt, List<ToolDefinition> tools,
                                    String model, ToolDispatcher dispatcher, ToolContext ctx,
                                    java.util.function.BooleanSupplier stopCheck) {
        return runWithDispatcher(task, systemPrompt, null, tools, model, dispatcher, ctx, stopCheck);
    }

    /**
     * 使用 {@link ToolDispatcher} 执行 Function Calling 多轮循环（缓存优化版）。
     * <p>将稳定的 system 前缀与动态上下文分离：稳定前缀单独作 system 消息（缓存前缀命中点），
     * 动态上下文下沉到首条 user 消息，避免动态内容污染 system 前缀导致 Prompt Cache 全部 miss。</p>
     */
    public String runWithDispatcher(String task, String stableSystemPrompt, String dynamicContext,
                                    List<ToolDefinition> tools, String model, ToolDispatcher dispatcher,
                                    ToolContext ctx, java.util.function.BooleanSupplier stopCheck) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", stableSystemPrompt));
        StringBuilder userContent = new StringBuilder();
        if (dynamicContext != null && !dynamicContext.isEmpty()) {
            userContent.append(dynamicContext).append("\n\n");
        }
        userContent.append(task);

        // === 预处理：按需识图 + 折叠消息段落摘要（多模型协作）===
        // 1. 视觉 Agent：仅在「引用图片」或用户明确「看图指令」时调用，避免无条件识图导致卡死。
        boolean needVision = shouldRecognizeImage(ctx, task);
        VisionBatch visionBatch = collectVisionImages(ctx, needVision);
        // 2. 段落 Agent：折叠消息（当前消息或引用消息）先用主模型生成概述，再交主模型总结。
        String forwardSummary = null;
        String forwardText = extractForwardForSummary(ctx);
        if (forwardText != null) {
            forwardSummary = summarizeForwardMessage(forwardText);
        }

        String effectiveModel = (model != null) ? model : "";
        List<ToolDefinition> effectiveTools = tools;
        boolean hasPreprocess = !visionBatch.pendingImages.isEmpty() || !visionBatch.descriptions.isEmpty()
                || visionBatch.hasOversize || (forwardSummary != null && !forwardSummary.isEmpty());
        if (hasPreprocess) {
            // 识图：仅对未缓存的图调 vision 模型，已由存图审查缓存的直接复用（节约 token）
            String imageUnderstanding = recognizeImages(userContent.toString(), visionBatch.pendingImages, ctx);
            StringBuilder combined = new StringBuilder();
            if (visionBatch.hasOversize) {
                combined.append("[系统提示] 本次有图片超过3M大小限制，我（bot）不看，已跳过该图。\n\n");
            }
            // 图片归属提醒（引用他人图片时，明确告知 AI 图片的原发送者，避免归因到当前对话者）
            String ownerHint = buildImageOwnerHint(ctx);
            if (ownerHint != null && !ownerHint.isEmpty()) {
                combined.append(ownerHint).append("\n\n");
            }
            for (String desc : visionBatch.descriptions) {
                combined.append("[图片内容识别结果]\n").append(desc).append("\n\n");
            }
            if (imageUnderstanding != null && !imageUnderstanding.isEmpty()) {
                combined.append("[图片内容识别结果]\n").append(imageUnderstanding).append("\n\n");
            } else if (!visionBatch.pendingImages.isEmpty()) {
                // 视觉模型不可用/识别失败：明确告知主模型，避免主模型因「未出现识别结果」而不知如何回复
                combined.append("[图片内容识别结果]\n")
                        .append("（本次图片识别失败，视觉模型暂不可用，请结合上下文回复，不要凭空编造图片内容）")
                        .append("\n\n");
            }
            if (forwardSummary != null && !forwardSummary.isEmpty()) {
                combined.append("[折叠消息摘要]\n").append(forwardSummary).append("\n\n");
            }
            combined.append(userContent.toString());
            messages.add(new ChatMessage("user", combined.toString()));
            // 保持常规模型 + 工具，继续走 Function Calling 循环
        } else {
            messages.add(new ChatMessage("user", userContent.toString()));
        }

        int consecutiveFailures = 0;   // 连续失败次数（任何成功即归零）
        int fcRound = 0;               // 主模型调用轮次（诊断用）
        int criticRetries = 0;         // 审查者阻断后重新生成次数
        int totalToolCalls = 0;        // 工具调用总次数（尝试次数）
        String lastFailure = null;     // 最后一次失败信息
        boolean fallbackHinted = false;          // 是否已注入动态注入兜底提示
        boolean wrapUpHinted = false;            // 是否已注入「尽快总结收尾」提示
        boolean hasEval = hasTool(effectiveTools, "eval");       // 通道是否有 eval（动态注入：Java 编译执行，终极兜底）
        long traceStart = System.currentTimeMillis();   // Harness 可观测性：开始时间
        boolean traceSuccess = true;                    // Harness 可观测性：是否成功
        String traceVerdict = "";                       // Harness 可观测性：审查最终判定
        try {
            for (;;) {  // 不设轮次上限：仅靠自然完成 / 显式停止 / 连续失败(含空转)终止
                if (stopCheck != null && stopCheck.getAsBoolean()) {
                    traceSuccess = false;
                    return "(已停止)";
                }
                // 连续失败 5 次后，注入一次「改用动态注入（eval）兜底」提示（仅提示，不重置计数）
                // 仅当通道有 eval（动态注入）时才兜底；execq 通道 eval 仅限多对象拆分搜索破例可用
                if (!fallbackHinted && consecutiveFailures >= FALLBACK_THRESHOLD && hasEval) {
                    fallbackHinted = true;
                    messages.add(new ChatMessage("user", buildFallbackPrompt()));
                }
                fcRound++;
                long fcCallStart = System.currentTimeMillis();
                sair.aiagent.AiAgentActivity.debugLog("[FC] 主模型调用 #" + fcRound + " (累计工具调用=" + totalToolCalls + ", messages=" + messages.size() + ")");
                DeepSeekClient.ToolCallResult r = client.chatSyncWithTools(messages, effectiveTools, "auto", effectiveModel);
                sair.aiagent.AiAgentActivity.debugLog("[FC] 主模型返回 #" + fcRound + " (耗时" + (System.currentTimeMillis() - fcCallStart) + "ms, "
                        + (r.hasToolCalls() ? "toolCalls=" + r.toolCalls.size() : "content长度=" + (r.content != null ? r.content.length() : 0)) + ")");
                if (!r.hasToolCalls()) {
                    String content = (r.content != null && !r.content.trim().isEmpty())
                            ? r.content : "(AI 未返回文本)";
                    // Harness 验证闭环：审查者评估最终回复，不通过则阻断并重新生成
                    if (critic != null && criticRetries < MAX_CRITIC_RETRIES) {
                        String verdict = critic.reviewResponse(task, content);
                        traceVerdict = verdict;
                        if (verdict.startsWith("BLOCK")) {
                            criticRetries++;
                            // 携带 tools 参数的请求后续轮次必须回传 reasoning_content，否则思考模式下 API 返回 400
                            messages.add(new ChatMessage("assistant", content, r.reasoningContent));
                            messages.add(new ChatMessage("user", buildCriticCorrection(verdict)));
                            continue;
                        }
                    }
                    return content;
                }
                // 携带 tools 参数的请求后续轮次必须完整回传 reasoning_content，否则思考模式下 API 返回 400
                messages.add(ChatMessage.createAssistantWithToolCalls(r.content, r.reasoningContent, r.toolCalls));
                totalToolCalls += r.toolCalls.size();
                // 接近硬上限：注入一次收尾提示，引导 AI 基于已有信息尽快输出最终结果
                if (!wrapUpHinted && totalToolCalls >= WARN_TOOL_CALLS) {
                    wrapUpHinted = true;
                    messages.add(new ChatMessage("user", buildWrapUpPrompt(totalToolCalls)));
                }
                // 硬上限：强制终止，返回已执行进度说明，避免永久不回复
                if (totalToolCalls >= MAX_TOTAL_TOOL_CALLS) {
                    traceSuccess = false;
                    return buildToolCallLimitMessage(totalToolCalls);
                }
                List<String> toolResults = executeToolCalls(r.toolCalls, ctx, dispatcher);
                for (int ti = 0; ti < r.toolCalls.size(); ti++) {
                    ToolCall tc = r.toolCalls.get(ti);
                    String result = toolResults.get(ti);
                    boolean failed = isFailureResult(tc.getName(), result);
                    // 注意：不能按「连续调用同一工具」判空转——递归翻找文件会正常地连续调用 readdir/readfile。
                    // 无进展的识别由 isFailureResult 依据工具返回内容（失败/空结果）完成，见下方。
                    messages.add(ChatMessage.createToolResult(tc.getId(), tc.getName(), result));
                    if (failed) {
                        consecutiveFailures++;
                        lastFailure = result;
                    } else {
                        consecutiveFailures = 0;
                        fallbackHinted = false;  // 成功后允许下次连续失败再次提示兜底
                    }
                    // 连续失败达到 MAX_CONSECUTIVE_FAILURES 次 → 异常终止
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        traceSuccess = false;
                        return buildFailureStopMessage(totalToolCalls, consecutiveFailures, lastFailure);
                    }
                }
            }
        } catch (Exception e) {
            traceSuccess = false;
            return "[Function Calling 调用失败] " + e.toString();
        } finally {
            recordTrace(task, ctx, totalToolCalls, traceSuccess, traceStart, traceVerdict);
        }
    }

    /**
     * 记录单次 Function Calling 执行轨迹到 Harness 可观测性存储（{@code harness_traces} 表）。
     * 失败静默忽略——可观测性不应阻断业务。
     */
    private void recordTrace(String task, ToolContext ctx, int toolCalls, boolean success,
                             long startTime, String verdict) {
        try {
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return;
            String mode = (ctx != null && ctx.channel != null) ? ctx.channel : "console";
            HarnessTrace trace = new HarnessTrace(HarnessTrace.newTaskId(), mode, task, toolCalls,
                    success, System.currentTimeMillis() - startTime, verdict, System.currentTimeMillis());
            pm.saveTrace(trace);
        } catch (Exception ignored) {
            // 轨迹落库失败不影响业务
        }
    }

    /** 视觉识别批次：缓存命中的描述 + 待 vision 模型识别的图片。 */
    private static final class VisionBatch {
        final List<String> descriptions = new ArrayList<>();
        final List<String> pendingImages = new ArrayList<>();
        boolean hasOversize = false;
    }

    /**
     * 判断是否需要调用视觉模型识图：引用图片 或 用户明确「看图指令」。
     * <p>非触发时图片仅作占位，不下载、不转 base64、不调视觉模型，避免含图消息卡死。</p>
     */
    private boolean shouldRecognizeImage(ToolContext ctx, String task) {
        if (ctx == null || ctx.qqMsg == null) return false;
        sair.aiagent.onebot.model.QQMessage qq = ctx.qqMsg;
        // 引用图片 = 要看
        if (!qq.getQuotedImageUrls().isEmpty()) return true;
        String text = (task != null ? task : "") + " " + (qq.getPlainText() != null ? qq.getPlainText() : "");
        for (String kw : IMAGE_REQUEST_KEYWORDS) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 收集 QQ 通道消息中的图片 URL，并转换为 Vision API 可用格式（base64 data URI / 可访问 URL）。
     * <p>合并「当前消息图片」与「引用消息图片」，由 {@code ImageDownloader.resolveImageForVision}
     * 统一处理腾讯内网图片的 base64 转换与 NapCat get_image 兜底。</p>
     * <p>若某张图已由存图审查（StickerManager.reviewSafe）缓存过内容描述，则直接复用描述，
     * 不再重复调用视觉模型（节约 token）。</p>
     *
     * @param ctx 工具执行上下文（可为 null，非 QQ 通道时返回空批次）
     * @param recognizeImages 是否需要调用视觉模型识图（false 时仅复用 mark 备注，不调模型）
     * @return 视觉识别批次（含缓存描述与待识别图片）
     */
    private static VisionBatch collectVisionImages(ToolContext ctx, boolean recognizeImages) {
        VisionBatch batch = new VisionBatch();
        if (ctx == null || ctx.qqMsg == null) return batch;

        sair.aiagent.onebot.model.QQMessage qq = ctx.qqMsg;
        sair.aiagent.onebot.NapCatApi napcat = ctx.napcatApi;

        // 当前消息图片（无 file/md5 关联）
        if (qq.hasImage()) {
            for (String url : qq.getImageUrls()) {
                processVisionImage(url, null, recognizeImages, batch, napcat);
            }
        }
        // 引用消息图片（带 file/md5，供 NapCat get_image 兜底下载）
        List<String> quotedUrls = qq.getQuotedImageUrls();
        List<String> quotedFiles = qq.getQuotedImageFiles();
        for (int i = 0; i < quotedUrls.size(); i++) {
            String url = quotedUrls.get(i);
            String file = (i < quotedFiles.size()) ? quotedFiles.get(i) : null;
            processVisionImage(url, file, recognizeImages, batch, napcat);
        }
        return batch;
    }

    /**
     * 处理单张图片：缓存复用 → 按需识图 → 下载/转换（引用图片带 file/md5 供 NapCat 兜底）。
     */
    private static void processVisionImage(String url, String file, boolean recognizeImages,
                                           VisionBatch batch, sair.aiagent.onebot.NapCatApi napcat) {
        if (url == null || url.isEmpty()) return;
        // 优先复用存图审查时缓存的内容描述（mark 备注，同一张图只调一次视觉模型）
        String cached = StickerManager.getCachedDescription(url);
        if (cached != null && !cached.isEmpty()) {
            batch.descriptions.add(cached);
            return;
        }
        // 未要求识图且无备注：跳过该图，不下载、不转 base64、不调视觉模型
        if (!recognizeImages) return;
        String resolved = sair.aiagent.onebot.ImageDownloader.resolveImageForVision(url, file, napcat);
        if (sair.aiagent.onebot.ImageDownloader.isOversizeResult(resolved)) {
            batch.hasOversize = true;
            return;
        }
        if (resolved != null && !resolved.isEmpty()) {
            batch.pendingImages.add(resolved);
        }
    }

    /**
     * 构建图片归属提醒：当引用了他人的图片时，明确告知 AI 图片的原发送者，
     * 防止 AI 把图片的拍摄者/内容归因到当前对话者（如把「主人」当成图片发送者）。
     *
     * @param ctx 工具执行上下文
     * @return 归属提醒文本，无归属信息时返回 null
     */
    private String buildImageOwnerHint(ToolContext ctx) {
        if (ctx == null || ctx.qqMsg == null) return null;
        String sender = ctx.qqMsg.getQuotedSenderName();
        long senderQQ = ctx.qqMsg.getQuotedSenderQQ();
        long currentUser = ctx.qqMsg.getUserId();
        if (sender == null || sender.isEmpty() || senderQQ <= 0 || senderQQ == currentUser) {
            return null;
        }
        return "⚠️ 图片归属提醒：本次消息涉及的图片，原发送者是【" + sender + "(QQ:" + senderQQ
                + ")】，不是当前与你对话的这个人。当前对话者只是引用了这张图片来询问内容，"
                + "请不要把图片的拍摄者、拍摄地点或内容归因到当前对话者身上。";
    }

    /**
     * 用 vision 模型纯识别图片内容，返回客观文字描述（不启用 Function Calling）。
     * <p>识别失败或结果为空时返回 null，调用方跳过图片理解继续正常流程。</p>
     *
     * @param text        原始用户文本（含动态上下文与任务，作为识别背景）
     * @param visionImages Vision API 可用图片 URL 列表（非空）
     * @return 图片文字描述，失败时返回 null
     */
    private String recognizeImages(String text, List<String> visionImages, ToolContext ctx) {
        if (visionImages == null || visionImages.isEmpty()) return null;
        long visionStart = System.currentTimeMillis();
        sair.aiagent.AiAgentActivity.qqLog("[FC] 视觉模型识别图片开始: " + visionImages.size() + " 张");
        try {
            String prompt = "请仔细识别这张图片，完整转写图中所有文字内容，并描述图片结构、关键信息"
                    + "（如邮件标题、发件人、收件人、正文、日期、关键数据等）。"
                    + "只做客观识别与转写，不要分析、不要下结论。";
            // 图片归属说明：引用别人的图片时，明确告知 AI 这张图是谁发的
            if (ctx != null && ctx.qqMsg != null) {
                String sender = ctx.qqMsg.getQuotedSenderName();
                long senderQQ = ctx.qqMsg.getQuotedSenderQQ();
                long currentUser = ctx.qqMsg.getUserId();
                if (sender != null && !sender.isEmpty() && senderQQ > 0 && senderQQ != currentUser) {
                    prompt = "注意：这张（些）图片是被引用的消息里的，它是【" + sender + "(QQ:" + senderQQ
                            + ")】发的，不是当前与你对话的人发的。\n\n" + prompt;
                }
            }
            prompt += "\n\n用户消息背景：\n" + (text != null ? text : "");
            List<ChatMessage> visionMsgs = new ArrayList<>();
            visionMsgs.add(ChatMessage.createMultimodal(prompt, visionImages));
            String result = client.chatSync(visionMsgs, AiConfig.getInstance().getVisionModel());
            if (result != null && !result.trim().isEmpty()) {
                sair.aiagent.AiAgentActivity.qqLog("[FC] 视觉模型识别完成 (耗时" + (System.currentTimeMillis() - visionStart) + "ms)");
                return result.trim();
            }
        } catch (Exception e) {
            sair.aiagent.AiAgentActivity.qqLog("[FC] 图片识别失败: " + e.toString());
        }
        return null;
    }

    /**
     * 段落 Agent：用主模型概括折叠消息（合并转发的多条消息），返回概述文本，失败返回 null。
     */
    private String summarizeForwardMessage(String forwardContent) {
        if (forwardContent == null || forwardContent.trim().isEmpty()) return null;
        long sumStart = System.currentTimeMillis();
        sair.aiagent.AiAgentActivity.qqLog("[FC] 段落模型概括折叠消息开始 (长度" + forwardContent.length() + ")");
        try {
            String prompt = "请阅读以下折叠消息（合并转发的多条消息），用简洁的语言概括：\n"
                    + "1. 这段对话讨论的主题与背景\n"
                    + "2. 各方发言的核心观点/关键信息\n"
                    + "3. 与当前对话可能相关的重点\n\n"
                    + "折叠消息内容：\n" + forwardContent;
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("user", prompt));
            String result = client.chatSync(msgs, AiConfig.getInstance().getExecqModel());
            sair.aiagent.AiAgentActivity.qqLog("[FC] 段落模型概括完成 (耗时" + (System.currentTimeMillis() - sumStart) + "ms)");
            return (result != null && !result.trim().isEmpty()) ? result.trim() : null;
        } catch (Exception e) {
            sair.aiagent.AiAgentActivity.qqLog("[FC] 折叠消息摘要失败: " + e.toString());
            return null;
        }
    }

    /** 提取需要摘要的折叠消息文本：当前消息折叠内容 或 引用消息中的折叠展开内容。 */
    private String extractForwardForSummary(ToolContext ctx) {
        if (ctx == null || ctx.qqMsg == null) return null;
        sair.aiagent.onebot.model.QQMessage qq = ctx.qqMsg;
        if (qq.hasForward() && qq.getForwardContent() != null && !qq.getForwardContent().isEmpty()) {
            return qq.getForwardContent();
        }
        String quoted = qq.getQuotedMessageContent();
        if (quoted != null && quoted.contains("[折叠消息展开内容]")) {
            return quoted;  // 引用折叠消息时，quotedMessageContent 已含展开内容
        }
        return null;
    }

    /**
     * 执行本轮 tool_calls：多个工具且本地通道时并发执行（独立工具并行），
     * QQ 通道或单工具时串行执行（保证 QQ 状态安全与结果顺序稳定）。
     */
    private List<String> executeToolCalls(List<ToolCall> toolCalls, ToolContext ctx, ToolDispatcher dispatcher) {
        int n = toolCalls.size();
        List<String> results = new ArrayList<>(n);
        for (int i = 0; i < n; i++) results.add(null);
        if (n == 0) return results;
        boolean parallel = (n > 1) && (ctx == null || !ctx.isExecq());
        if (!parallel) {
            for (int i = 0; i < n; i++) {
                results.set(i, executeOne(toolCalls.get(i), ctx, dispatcher));
            }
            return results;
        }
        java.util.concurrent.ExecutorService pool =
                ThreadManager.getInstance().newNamedFixed("FC-Tools", Math.min(n, 8));
        List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
        for (final ToolCall tc : toolCalls) {
            futures.add(pool.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return executeOne(tc, ctx, dispatcher);
                }
            }));
        }
        for (int i = 0; i < n; i++) {
            try {
                results.set(i, futures.get(i).get(600, java.util.concurrent.TimeUnit.SECONDS));
            } catch (Exception e) {
                results.set(i, "[工具执行异常] " + toolCalls.get(i).getName() + ": " + e.toString());
            }
        }
        return results;
    }

    /** 执行单个工具，异常统一转为失败结果；单工具 120s 超时保护，防止卡死线程池。 */
    private String executeOne(ToolCall tc, ToolContext ctx, ToolDispatcher dispatcher) {
        java.util.concurrent.ExecutorService pool =
                ThreadManager.getInstance().newNamedCached("FC-ToolExec");
        java.util.concurrent.Future<String> f = pool.submit(new java.util.concurrent.Callable<String>() {
            @Override
            public String call() {
                try {
                    return dispatcher.execute(tc.getName(), tc.getArguments(), ctx);
                } catch (Exception e) {
                    return "[工具执行异常] " + tc.getName() + ": " + e.toString();
                }
            }
        });
        try {
            return f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            return "[工具执行超时] " + tc.getName() + " 执行超过 120s，已中断";
        } catch (Exception e) {
            return "[工具执行异常] " + tc.getName() + ": " + e.toString();
        }
    }

    /**
     * 判断工具执行结果是否属于「失败」——用于连续失败计数与终止保护。
     * <p>按工具类型精确分流：search/web 依据其固定成功/失败前缀判断；其余工具仅检查结果头部，
     * 避免把「搜索结果摘要 / 网页正文 / 文件内容」中偶然出现的 失败/未找到/无结果 等词误判为失败。</p>
     */
    private static boolean isFailureResult(String toolName, String result) {
        if (result == null) return true;
        String r = result.trim();
        if (r.isEmpty()) return false;
        // 工具执行异常/超时、权限阻断、代码安全阻断、空结果 —— 统一失败信号
        if (r.startsWith("[工具执行异常]") || r.startsWith("[工具执行超时]")
                || r.startsWith("[harness]")) {
            return true;
        }
        // search 工具：成功以「[搜索]」开头，失败以「[search]」（小写）开头，精确分流避免摘要误判
        if ("search".equals(toolName)) {
            return r.startsWith("[search]");
        }
        // web 工具：成功以「Web GET [url] (HTTP xxx」开头，失败为「Web GET [url] 失败:/错误:/被拒绝」
        if ("web".equals(toolName)) {
            if (!r.startsWith("Web GET")) return true;
            int nl = r.indexOf('\n');
            String statusLine = nl > 0 ? r.substring(0, nl) : r;
            if (statusLine.contains("失败:") || statusLine.contains("错误:") || statusLine.contains("被拒绝")) return true;
            int httpIdx = r.indexOf("(HTTP ");
            if (httpIdx > 0) {
                int colon = r.indexOf(':', httpIdx);
                String body = colon > 0 ? r.substring(colon + 1).trim() : "";
                if (body.length() < 30) return true;
            }
            return false;
        }
        // 其他工具：失败/无进展信号仅检查结果头部（前 80 字符），避免把正文中的 失败/未找到/无结果 等词误判
        String head = r.length() > 80 ? r.substring(0, 80) : r;
        if (head.contains("无权限") || head.contains("权限不足") || head.contains("被拒绝")
                || head.contains("仅主人可用") || head.contains("未知工具") || head.contains("未知操作")
                || head.contains("文件不存在") || head.contains("目录不存在") || head.contains("发送失败")
                || head.contains("执行失败") || head.contains("任务执行失败") || head.contains("未就绪")
                || head.contains("缺少") || head.contains("命令错误") || head.contains("命令中断")
                || head.contains("未找到") || head.contains("没有找到") || head.contains("无结果")
                || head.contains("无匹配") || head.contains("未获取") || head.contains("未解析")
                || head.contains("反爬") || head.contains("无有效") || head.contains("超时")
                || head.contains("无搜索结果") || head.contains("失败") || head.contains("未初始化")
                || head.contains("不可用") || head.contains("错误") || head.contains("异常")
                || head.contains("expired") || head.contains("not found") || head.contains("is empty")
                || head.contains("not a directory") || head.contains("is a directory")
                || head.contains("cannot read") || head.contains("too large")
                || head.contains("not initialized") || head.contains("not available")) {
            return true;
        }
        return false;
    }

    /** 构造「工具调用达到硬上限」终止时的告知信息。 */
    private static String buildToolCallLimitMessage(int totalToolCalls) {
        return "⚠ 本次任务已连续执行 " + totalToolCalls + " 次工具调用，达到安全上限，为避免无限循环已自动停止。\n"
             + "已获取的部分结果可能尚未整理完成。如需继续，请补充更明确的目标，或拆分为更小的子任务后重试。";
    }

    /** 构造「接近硬上限」的收尾引导提示。 */
    private static String buildWrapUpPrompt(int usedToolCalls) {
        return "⚠ 注意：当前任务已执行 " + usedToolCalls + " 次工具调用，接近安全上限 " + MAX_TOTAL_TOOL_CALLS + "。\n"
             + "请不要再调用新工具，立即基于已获取的信息总结并输出最终结果。";
    }

    /** 构造连续失败终止时的告知信息。 */
    private static String buildFailureStopMessage(int totalToolCalls, int consecutiveFailures,
                                                  String lastFailure) {
        StringBuilder sb = new StringBuilder();
        sb.append("⚠ 检测到连续失败 ").append(consecutiveFailures).append(" 次，已自动终止操作。\n");
        sb.append("共尝试工具调用 ").append(totalToolCalls).append(" 次。\n")
          .append("最后遇到的问题：\n")
          .append(lastFailure != null && !lastFailure.trim().isEmpty() ? lastFailure : "(无详细信息)");
        return sb.toString();
    }

    /** 判断工具列表中是否存在指定名称的工具。 */
    private static boolean hasTool(List<ToolDefinition> tools, String name) {
        if (tools == null || name == null) return false;
        for (ToolDefinition t : tools) {
            if (t != null && name.equals(t.getName())) return true;
        }
        return false;
    }

    /** 构造动态注入兜底的引导提示（让 AI 改用 eval 编写 Java 代码直接完成任务）。 */
    private static String buildFallbackPrompt() {
        return "⚠ 检测到前面的常规尝试已连续失败 " + FALLBACK_THRESHOLD + " 次。\n"
             + "请改用「动态注入」方式兜底：调用 `eval` 工具，编写一段 Java 代码（编译执行）直接完成任务，"
             + "不要再重复之前失败的操作。\n"
             + "注意：eval 是「动态注入」（Java 源码编译后加载执行），与 evaljs 的「动态执行」（JS 脚本解释执行）有本质区别，兜底必须用 eval。";
    }

    /** 构造审查阻断后的纠正提示（引导 AI 重新生成合规回复）。 */
    private static String buildCriticCorrection(String verdict) {
        String reason = verdict;
        if (reason.startsWith("BLOCK:")) {
            reason = reason.substring("BLOCK:".length()).trim();
        } else if (reason.startsWith("BLOCK：")) {
            reason = reason.substring("BLOCK：".length()).trim();
        }
        return "⚠ 你的上一轮回复未通过安全审查，请重新生成。\n"
             + "被拦截原因：" + reason + "\n"
             + "请针对上述问题修改你的回复，确保不违反安全红线后再输出。";
    }

    public static String extractArg(String argumentsJson, String key) {
        if (argumentsJson == null || argumentsJson.isEmpty()) return "";
        try {
            com.google.gson.JsonObject obj =
                    com.google.gson.JsonParser.parseString(argumentsJson).getAsJsonObject();
            if (obj.has(key) && !obj.get(key).isJsonNull()) {
                return obj.get(key).getAsString();
            }
            return "";
        } catch (Exception e) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
                    .matcher(argumentsJson);
            return m.find() ? m.group(1).replace("\\\"", "\"").replace("\\n", "\n") : "";
        }
    }
}
