package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;

import sair.aiagent.AiAgentActivity;
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

    /** 工具结果累积长度上限：批量读大文件时工具结果会持续累积，超过该值后压缩后续结果，避免请求体超长触发 API 400。 */
    public static final int MAX_ACCUMULATED_TOOL_RESULT_CHARS = 20000;

    /** 上下文溢出后，单个工具结果被压缩到的保留长度。 */
    public static final int TOOL_RESULT_TRIM_CHARS = 1000;

    // ==================== 折叠消息递归分析（长折叠 / 嵌套折叠）====================

    /** 嵌套折叠最大递归展开层数（层层递归交给段落 Agent）。 */
    private static final int FORWARD_MAX_DEPTH = 3;
    /**
     * 单层折叠渲染成文本的最大字符数。
     * <p>渲染只是本地字符串拼接（零 API 成本），因此上限设得很大：
     * 真正需要限制的是「分析次数」（见 {@link #FORWARD_MAX_PASSAGE_CALLS}）而不是渲染，
     * 否则超长折叠会在渲染阶段就被截断、尾巴静默丢失。实测 200 条长消息渲染约 2 万字，
     * 这里留足余量。</p>
     */
    private static final int FORWARD_RENDER_MAX_CHARS = 200000;
    /**
     * 分块阈值：单次交给段落 Agent 的正文超过该长度就分块分析（map-reduce）。
     * <p>取 6000 字（约 4000 token，模型 1M 上下文下毫无压力）：块数少一半，
     * 段落 Agent 调用次数随之减半；实测 200 条长消息（约 4.3 万字）分 8 块，
     * 落在调用预算内、无需降级。</p>
     */
    private static final int FORWARD_CHUNK_CHARS = 6000;
    /** 单条消息最多唤起段落 Agent 的次数（成本上限，防止深层嵌套 × 多分块无限放大）。 */
    private static final int FORWARD_MAX_PASSAGE_CALLS = 12;
    /** 注入主 Agent 上下文的折叠分析结论长度上限（防止超长结论挤爆主 Agent 上下文）。 */
    private static final int FORWARD_ANALYSIS_MAX_CHARS = 4000;
    /** 单次请求最多附带的图片数（折叠图片全识别，但设上限避免请求体过大）。 */
    private static final int MAX_ATTACH_IMAGES = 20;

    private final DeepSeekClient client;

    /** 看图指令关键词（用户明确要求看图片内容） */
    private static final String[] IMAGE_REQUEST_KEYWORDS = {
        "看图", "识图", "识别图片", "图片里", "图里", "这张图", "什么图",
        "图片上", "图上", "帮我看看图", "看看图", "图中", "图内", "图片内容"
    };

    /** 审查者子 Agent（可空；非空时对最终回复做验证闭环）。 */
    private volatile CriticAgent critic;

    /** 是否启用图片/折叠消息预处理（视觉兜底 Agent 自身关闭，避免递归识图）。 */
    private volatile boolean preprocessEnabled = true;

    /** 是否启用「折叠消息委派给段落 Agent」（段落 Agent 自身关闭，避免重复委派同一份折叠消息）。 */
    private volatile boolean forwardDelegationEnabled = true;

    /** 本次执行使用的工具分发器（用于取 AgentBus 唤起段落/视觉 Agent）。 */
    private volatile ToolDispatcher dispatcherRef;

    public FunctionCallingBridge(DeepSeekClient client) {
        this.client = client;
    }

    /** 设置审查者子 Agent，启用最终回复验证闭环。 */
    public void setCritic(CriticAgent critic) {
        this.critic = critic;
    }

    /** 关闭/开启图片与折叠消息预处理（视觉兜底 Agent 调用时关闭）。 */
    public void setPreprocessEnabled(boolean enabled) {
        this.preprocessEnabled = enabled;
    }

    /** 关闭/开启「折叠消息委派给段落 Agent」（段落 Agent 调用时关闭，防止重复委派造成递归）。 */
    public void setForwardDelegationEnabled(boolean enabled) {
        this.forwardDelegationEnabled = enabled;
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
        this.dispatcherRef = dispatcher;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", stableSystemPrompt));
        StringBuilder userContent = new StringBuilder();
        if (dynamicContext != null && !dynamicContext.isEmpty()) {
            userContent.append(dynamicContext).append("\n\n");
        }
        userContent.append(task);

        String effectiveModel = (model != null && !model.isEmpty())
                ? model
                : (ctx != null && ctx.model != null ? ctx.model : "");
        // 当前通道模型是否自己就能看图（决定图片走「原生多模态」还是「视觉 Agent 兜底」）
        final boolean nativeVision = AiConfig.hasNativeVision(effectiveModel);

        // === 预处理：折叠消息 + 图片（多 Agent 协作）===
        // 顺序很重要：先分析折叠消息（过程中会把折叠里的图片 URL 登记到消息上），
        // 再收集图片，这样折叠消息中的图片也能一并被识别（含各嵌套层）。
        String forwardAnalysis = null;
        String forwardMarkHint = null;
        if (preprocessEnabled && forwardDelegationEnabled) {
            ForwardTask fwd = prepareForwardTask(ctx);
            if (fwd != null) {
                String existing = lookupForwardMark(ctx, fwd);
                if (existing != null && !existing.isEmpty()) {
                    forwardAnalysis = existing;
                    AiAgentActivity.debugLog("[FC] 折叠消息已分析过，复用其结论，跳过段落 Agent：" + fwd.label);
                } else {
                    forwardAnalysis = analyzeForward(fwd, ctx);
                    if (forwardAnalysis != null && !forwardAnalysis.isEmpty()) {
                        // 由主 Agent 负责把结论写回该折叠消息（提示词中会明确要求它调用 markmessage）
                        forwardMarkHint = buildForwardMarkHint(fwd, forwardAnalysis);
                    }
                }
            }
        }

        // 图片：模型有原生视觉就直接随消息提交；无视觉（如 Pro）则由视觉 Agent 兜底识图。
        boolean needVision = preprocessEnabled && shouldRecognizeImage(ctx, task);
        VisionBatch visionBatch = preprocessEnabled ? collectVisionImages(ctx, needVision) : new VisionBatch();

        List<ToolDefinition> effectiveTools = tools;
        boolean hasPreprocess = !visionBatch.pendingImages.isEmpty() || !visionBatch.descriptions.isEmpty()
                || visionBatch.hasOversize || (forwardAnalysis != null && !forwardAnalysis.isEmpty())
                || (forwardMarkHint != null && !forwardMarkHint.isEmpty());
        if (hasPreprocess) {
            StringBuilder combined = new StringBuilder();
            if (visionBatch.hasOversize) {
                combined.append("[系统提示] 本次有图片超过3M大小限制，我（bot）不看，已跳过该图。\n\n");
            }
            // 图片归属提醒（引用他人图片时，明确告知 AI 图片的原发送者，避免归因到当前对话者）
            String ownerHint = buildImageOwnerHint(ctx);
            if (ownerHint != null && !ownerHint.isEmpty()) {
                combined.append(ownerHint).append("\n\n");
            }
            // 已缓存的图片描述（存图审查时已识别过，直接复用，零成本）
            for (String desc : visionBatch.descriptions) {
                combined.append("[图片内容识别结果]\n").append(desc).append("\n\n");
            }
            // 图片路径分流：有原生视觉 → 随消息附加原图；无原生视觉 → 视觉 Agent 兜底转文字注入
            boolean attachImages = false;
            if (!visionBatch.pendingImages.isEmpty()) {
                if (nativeVision) {
                    attachImages = true;
                    AiAgentActivity.debugLog("[FC] 原生多模态：模型 " + effectiveModel
                            + " 具备视觉，随消息提交 " + visionBatch.pendingImages.size() + " 张图片");
                } else {
                    AiAgentActivity.debugLog("[FC] 模型 " + effectiveModel
                            + " 无原生视觉，交由视觉 Agent 兜底识别 " + visionBatch.pendingImages.size() + " 张图片");
                    String desc = visionFallbackRecognize(visionBatch.pendingImages, ctx);
                    combined.append("[图片内容识别结果]\n")
                            .append(desc != null && !desc.isEmpty()
                                    ? desc
                                    : "（本次图片识别失败，视觉兜底不可用，请结合上下文回复，不要凭空编造图片内容）")
                            .append("\n\n");
                }
            }
            if (forwardAnalysis != null && !forwardAnalysis.isEmpty()) {
                // 结论注入主 Agent 上下文前做长度保护（完整结论已通过 mark 机制留档）
                String shown = forwardAnalysis;
                if (shown.length() > FORWARD_ANALYSIS_MAX_CHARS) {
                    shown = shown.substring(0, FORWARD_ANALYSIS_MAX_CHARS)
                          + "\n…（结论过长已截断，需要更多细节可用 ask_agent 向 passage 追问）";
                }
                combined.append("[折叠消息分析]\n").append(shown).append("\n\n");
            }
            if (forwardMarkHint != null && !forwardMarkHint.isEmpty()) {
                combined.append(forwardMarkHint).append("\n\n");
            }
            combined.append(userContent.toString());
            if (attachImages) {
                messages.add(ChatMessage.createMultimodal(combined.toString(), visionBatch.pendingImages));
            } else {
                messages.add(new ChatMessage("user", combined.toString()));
            }
            // 保持常规模型 + 工具，继续走 Function Calling 循环
        } else {
            messages.add(new ChatMessage("user", userContent.toString()));
        }

        int consecutiveFailures = 0;   // 连续失败次数（任何成功即归零）
        int fcRound = 0;               // 主模型调用轮次（诊断用）
        int criticRetries = 0;         // 审查者阻断后重新生成次数
        int totalToolCalls = 0;        // 工具调用总次数（尝试次数）
        int accumulatedToolResultChars = 0;  // 工具结果累积字符数（上下文溢出控制）
        boolean contextOverflowed = false;   // 工具结果累积是否已超阈值
        String lastFailure = null;     // 最后一次失败信息
        boolean fallbackHinted = false;          // 是否已注入动态注入兜底提示
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
                // 每轮记录 KV 前缀缓存命中情况：这是「成本」唯一的直接观测点
                // （命中约 ¥0.02-0.04/M，未命中约 ¥1-2/M，差 50 倍）。
                long[] u = client.getLastCallUsage();
                if (u != null && (u[0] > 0 || u[2] + u[3] > 0)) {
                    long cacheTotal = u[2] + u[3];
                    int hitPct = cacheTotal > 0 ? (int) (u[2] * 100 / cacheTotal) : 0;
                    sair.aiagent.AiAgentActivity.debugLog("[FC] #" + fcRound + " 用量: prompt=" + u[0]
                            + " completion=" + u[1]
                            + " cacheHit=" + u[2] + " cacheMiss=" + u[3] + " (命中率 " + hitPct + "%)");
                }
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
                // 不设工具调用次数上限：由 Agent 自行判断任务是否完成、是否需要继续调用工具。
                // 仅在「连续失败」与「显式停止」时终止，避免真正的死循环。
                List<String> toolResults = executeToolCalls(r.toolCalls, ctx, dispatcher);
                for (int ti = 0; ti < r.toolCalls.size(); ti++) {
                    ToolCall tc = r.toolCalls.get(ti);
                    String result = toolResults.get(ti);
                    boolean failed = isFailureResult(tc.getName(), result);
                    // 注意：不能按「连续调用同一工具」判空转——递归翻找文件会正常地连续调用 readdir/readfile。
                    // 无进展的识别由 isFailureResult 依据工具返回内容（失败/空结果）完成，见下方。
                    // 工具结果累积长度控制：批量读大文件时结果持续累积会撑爆请求体(API 400)，超阈值后压缩后续结果。
                    if (result != null) {
                        if (contextOverflowed) {
                            if (result.length() > TOOL_RESULT_TRIM_CHARS) {
                                ContextStats.count(ContextStats.C_TOOL_RESULT_TRIMMED);
                                result = result.substring(0, TOOL_RESULT_TRIM_CHARS)
                                        + "\n...[上下文过长，工具结果已压缩；请基于已有信息总结，避免继续读取大文件]";
                            }
                        } else {
                            accumulatedToolResultChars += result.length();
                            if (accumulatedToolResultChars > MAX_ACCUMULATED_TOOL_RESULT_CHARS) {
                                contextOverflowed = true;
                                ContextStats.count(ContextStats.C_CONTEXT_OVERFLOW);
                                if (result.length() > TOOL_RESULT_TRIM_CHARS) {
                                    ContextStats.count(ContextStats.C_TOOL_RESULT_TRIMMED);
                                    result = result.substring(0, TOOL_RESULT_TRIM_CHARS)
                                            + "\n...[上下文过长，工具结果已压缩；请基于已有信息总结，避免继续读取大文件]";
                                }
                            }
                        }
                    }
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
            // 打印完整堆栈，便于定位 IllegalArgumentException 等异常的确切来源
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            sair.aiagent.AiAgentActivity.debugLog("[FC] 主循环异常(完整堆栈):\n" + sw.toString());
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
            final PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return;
            // 通道标记：execs（QQ 全权限链路）必须与 execq 区分开，
            // 否则 harness_traces 里两条件混在一起，统计"哪条链路更差"就无从下手。
            String mode = (ctx == null || ctx.channel == null) ? "console"
                    : (ctx.execsMode ? "execs" : ctx.channel);
            final HarnessTrace trace = new HarnessTrace(HarnessTrace.newTaskId(), mode, task, toolCalls,
                    success, System.currentTimeMillis() - startTime, verdict, System.currentTimeMillis());
            // 异步落库：可观测性不应阻塞主链路
            ThreadManager.getInstance().newNamedSingle("TraceWriter").submit(() -> {
                try { pm.saveTrace(trace); } catch (Exception ignored) {}
            });
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
     * 判断是否需要把图片作为多模态输入提供给模型。
     * <p>触发条件：引用图片 / 用户明确「看图指令」/ <b>折叠消息中含图片</b>（折叠里的图一律识别）。</p>
     */
    private boolean shouldRecognizeImage(ToolContext ctx, String task) {
        if (ctx == null || ctx.qqMsg == null) return false;
        sair.aiagent.onebot.model.QQMessage qq = ctx.qqMsg;
        // 引用图片 = 要看
        if (!qq.getQuotedImageUrls().isEmpty()) return true;
        // 折叠消息里的图片：一律识别（含各嵌套层收集到的图片）
        if (!qq.getForwardImageUrls().isEmpty()) return true;
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
     * <p>若某张图已由存图审查（StickerManager.reviewSafe）缓存过内容描述，则直接复用描述文字
     * （无需再次附加图片，节约图片 token）。</p>
     *
     * @param ctx 工具执行上下文（可为 null，非 QQ 通道时返回空批次）
     * @param recognizeImages 是否需要识图（false 时仅复用已缓存的描述文字，不下载/不附加图片）
     * @return 视觉批次（含已缓存描述与待附加的图片）
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
        // 引用消息图片（成对 [url, file(md5)]，供 NapCat get_image 兜底下载；索引永远对齐）
        List<String[]> quotedPairs = qq.getQuotedImages();
        for (String[] pair : quotedPairs) {
            if (pair == null || pair.length < 2) continue;
            processVisionImage(pair[0], pair[1], recognizeImages, batch, napcat);
        }
        // 折叠消息里的图片（含各嵌套层；超过上限的部分记日志丢弃，避免请求体过大）
        List<String> foldUrls = qq.getForwardImageUrls();
        if (!foldUrls.isEmpty()) {
            int take = Math.min(foldUrls.size(), MAX_ATTACH_IMAGES);
            if (foldUrls.size() > take) {
                AiAgentActivity.debugLog("[FC] 折叠消息图片 " + foldUrls.size() + " 张，超出单次附加上限 "
                        + MAX_ATTACH_IMAGES + "，仅附加前 " + take + " 张");
            }
            for (int i = 0; i < take; i++) {
                processVisionImage(foldUrls.get(i), null, recognizeImages, batch, napcat);
            }
        }
        return batch;
    }

    /**
     * 处理单张图片：缓存复用 → 按需识图 → 下载/转换（引用图片带 file/md5 供 NapCat 兜底）。
     */
    private static void processVisionImage(String url, String file, boolean recognizeImages,
                                           VisionBatch batch, sair.aiagent.onebot.NapCatApi napcat) {
        // 仅有 file（md5）没有 URL 时也要处理：NapCat 的 get_image 可以直接按 md5 取图，
        // 旧实现在这里直接 return，导致「只给 md5 的图」被静默丢弃、AI 完全看不到。
        if ((url == null || url.isEmpty()) && (file == null || file.isEmpty())) return;
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

    // ==================== 折叠消息 → 段落 Agent ====================

    /** 折叠消息处理任务：要分析的正文 + 目标消息定位信息（供查/写 Mark 用）。 */
    private static final class ForwardTask {
        /** 折叠消息正文（合并转发的展开内容） */
        final String content;
        /** 归属描述（用于提示词与日志） */
        final String label;
        /** 该折叠消息的 OneBot message_id（>0 时可精确打标） */
        final long messageId;
        /** 折叠消息发送者 QQ（内容匹配兜底时要按发送者定位） */
        final long senderQQ;
        /** 折叠消息所在会话：group / private */
        final String sourceType;
        /** 会话 ID：群号 或 对方 QQ */
        final long sourceId;
        /** 是否来自「被引用的消息」 */
        final boolean quoted;
        /** 该折叠消息在记忆库中的存储正文（用于内容匹配兜底，可能与 content 不同） */
        final String storedContent;
        /** 折叠消息原始 JSON（结构化递归展开用；可为 null，此时退回按 content 文本分析） */
        final String raw;

        ForwardTask(String content, String label, long messageId, long senderQQ,
                    String sourceType, long sourceId, boolean quoted, String storedContent, String raw) {
            this.content = content;
            this.label = label;
            this.messageId = messageId;
            this.senderQQ = senderQQ;
            this.sourceType = sourceType;
            this.sourceId = sourceId;
            this.quoted = quoted;
            this.storedContent = storedContent;
            this.raw = raw;
        }
    }

    /**
     * 提取需要交给段落 Agent 分析的折叠消息（当前消息的折叠 / 被引用消息中的折叠展开内容）。
     * <p>不论是否引用，只要存在折叠内容就产出任务。</p>
     */
    private ForwardTask prepareForwardTask(ToolContext ctx) {
        if (ctx == null || ctx.qqMsg == null) return null;
        sair.aiagent.onebot.model.QQMessage qq = ctx.qqMsg;
        boolean group = qq.isGroupMessage();
        String srcType = group ? "group" : "private";
        long srcId = group ? qq.getGroupId() : qq.getUserId();

        // 1) 当前消息自带折叠内容
        if (qq.hasForward() && qq.getForwardContent() != null && !qq.getForwardContent().isEmpty()) {
            return new ForwardTask(qq.getForwardContent(),
                    "当前消息中的折叠消息", qq.getMessageId(), qq.getUserId(),
                    srcType, srcId, false, qq.getPlainText(), qq.getForwardRaw());
        }
        // 2) 被引用的消息是折叠消息（quotedMessageContent 已含展开内容）
        String quoted = qq.getQuotedMessageContent();
        if (quoted != null && quoted.contains("[折叠消息展开内容]")) {
            long senderQQ = (qq.getQuotedSenderQQ() > 0) ? qq.getQuotedSenderQQ() : qq.getUserId();
            String who = (qq.getQuotedSenderName() != null && !qq.getQuotedSenderName().isEmpty())
                    ? qq.getQuotedSenderName() + "(QQ:" + senderQQ + ")"
                    : "QQ:" + senderQQ;
            return new ForwardTask(quoted,
                    "被引用的、由 " + who + " 发出的折叠消息", qq.getReplyMessageId(), senderQQ,
                    srcType, srcId, true, quoted, qq.getForwardRaw());
        }
        return null;
    }

    /**
     * 查询该折叠消息是否已被分析过（最保险：先按 message_id 精确查，再按内容匹配兜底）。
     *
     * @return 已有的分析结论（mark），未分析过返回 null
     */
    private String lookupForwardMark(ToolContext ctx, ForwardTask fwd) {
        if (ctx == null || ctx.unifiedMemory == null || fwd == null) return null;
        sair.aiagent.onebot.UnifiedQQMemoryManager mem = ctx.unifiedMemory;
        try {
            // 1) 精确：按 message_id（最可靠，引用折叠消息也能命中）
            if (fwd.messageId > 0) {
                String m = "group".equals(fwd.sourceType)
                        ? mem.getGroupMessageMarkById(fwd.sourceId, fwd.messageId)
                        : mem.getConversationMarkById(fwd.sourceType, fwd.sourceId, fwd.messageId);
                if (m != null && !m.trim().isEmpty()) {
                    AiAgentActivity.debugLog("[FC] 折叠消息命中已分析标记（按 message_id=" + fwd.messageId + "）");
                    return m.trim();
                }
            }
            // 2) 兜底：按发送者 + 内容匹配（内容候选含原文与「折叠展开标记」之前的部分）
            for (String candidate : contentCandidates(fwd.storedContent)) {
                String m = "group".equals(fwd.sourceType)
                        ? mem.getGroupMessageMark(fwd.sourceId, fwd.senderQQ, candidate)
                        : mem.getConversationMark(fwd.sourceType, fwd.sourceId, candidate);
                if (m != null && !m.trim().isEmpty()) {
                    AiAgentActivity.debugLog("[FC] 折叠消息命中已分析标记（按内容匹配）");
                    return m.trim();
                }
            }
        } catch (Exception e) {
            AiAgentActivity.debugLog("[FC] 折叠消息已分析标记查询失败: " + e.toString());
        }
        return null;
    }

    /** 内容匹配候选：原文本身 + 「[折叠消息展开内容]」标记之前的头部（引用折叠时库里存的是头部）。 */
    private static List<String> contentCandidates(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        String c = content.trim();
        if (c.isEmpty()) return out;
        out.add(c);
        int idx = c.indexOf("[折叠消息展开内容]");
        if (idx > 0) {
            String head = c.substring(0, idx).trim();
            if (!head.isEmpty()) out.add(head);
        }
        return out;
    }

    /** 段落 Agent 调用预算（防止深层嵌套 × 多分块导致调用次数失控）。 */
    private static final class PassageBudget {
        int remaining;
        PassageBudget(int max) { this.remaining = Math.max(1, max); }
        boolean take() { if (remaining <= 0) return false; remaining--; return true; }
    }

    /**
     * 交给段落 Agent 分析折叠消息。
     * <p>处理顺序：
     * <ol>
     *   <li><b>结构化解析</b>折叠内容（保留图片真实 URL 与嵌套折叠 id）；</li>
     *   <li><b>嵌套折叠层层递归</b>：每一层内嵌折叠都单独唤起一个段落 Agent 分析，结论内联进上层正文；</li>
     *   <li><b>长正文分块</b>（map-reduce）：超过阈值就按消息边界切块，逐块分析后再由段落 Agent 合并成一份结论。</li>
     * </ol>
     * 段落 Agent 是可重复创建的：这里按「嵌套层数 + 分块数」多次唤起它，每次都是独立的 FC 循环。</p>
     *
     * @return 分析结论文本，失败返回 null
     */
    private String analyzeForward(ForwardTask fwd, ToolContext ctx) {
        if (fwd == null || fwd.content == null || fwd.content.trim().isEmpty()) return null;
        long start = System.currentTimeMillis();
        AiAgentActivity.qqLog("[FC] 折叠消息转交段落 Agent 分析 (" + fwd.label
                + ", 长度" + fwd.content.length() + ")");
        AgentBus bus = (dispatcherRef != null) ? dispatcherRef.getAgentBus() : null;
        if (bus == null) {
            AiAgentActivity.qqLog("[FC] AgentBus 不可用，无法交给段落 Agent 分析折叠消息");
            return null;
        }
        PassageBudget budget = new PassageBudget(FORWARD_MAX_PASSAGE_CALLS);
        try {
            // 1) 结构化解析（拿不到原始 JSON 时退化为按已展开文本分析）
            List<sair.aiagent.onebot.ForwardMessageExpander.ForwardMessage> struct =
                    (fwd.raw != null && !fwd.raw.isEmpty())
                            ? sair.aiagent.onebot.ForwardMessageExpander.parseForwardStructure(fwd.raw)
                            : new ArrayList<>();
            String content;
            int nestedCount = 0;
            if (!struct.isEmpty()) {
                List<String> imageUrls = new ArrayList<>();
                content = composeForwardText(struct, ctx, 0, budget, imageUrls, fwd);
                nestedCount = countNested(struct);
                // 折叠里的图片登记到消息上 → 后续视觉处理统一附加/兜底识别（全部识别）
                if (ctx != null && ctx.qqMsg != null) {
                    for (String u : imageUrls) ctx.qqMsg.addForwardImageUrl(u);
                }
                AiAgentActivity.qqLog("[FC] 折叠消息结构化展开: 消息数=" + struct.size()
                        + ", 嵌套折叠=" + nestedCount + ", 图片=" + imageUrls.size()
                        + ", 正文长度=" + (content != null ? content.length() : 0));
            } else {
                content = fwd.content;
            }
            if (content == null || content.trim().isEmpty()) return null;

            // 2) 长正文分块分析 + 合并
            String analysis = analyzeTextByChunks(content, ctx, budget, fwd.label);
            AiAgentActivity.qqLog("[FC] 段落 Agent 分析完成 (嵌套=" + nestedCount
                    + ", 耗时" + (System.currentTimeMillis() - start) + "ms, 剩余预算=" + budget.remaining + ")");
            return analysis;
        } catch (Exception e) {
            AiAgentActivity.qqLog("[FC] 段落 Agent 分析折叠消息失败: " + e.toString());
            return null;
        }
    }

    /** 统计结构中的嵌套折叠节点数（含内嵌 content 可解析出的层级）。 */
    private static int countNested(List<sair.aiagent.onebot.ForwardMessageExpander.ForwardMessage> msgs) {
        int n = 0;
        if (msgs == null) return 0;
        for (sair.aiagent.onebot.ForwardMessageExpander.ForwardMessage m : msgs) {
            if (m == null) continue;
            for (sair.aiagent.onebot.ForwardMessageExpander.ForwardItem it : m.items) {
                if (it.isNested()) n++;
            }
        }
        return n;
    }

    /**
     * 递归渲染折叠结构为文本，<b>每一层内嵌折叠都单独交给段落 Agent 分析</b>并把结论内联。
     *
     * @param imageUrls 输出参数：收集到的全部图片 URL（含各嵌套层）
     */
    private String composeForwardText(List<sair.aiagent.onebot.ForwardMessageExpander.ForwardMessage> msgs,
                                      ToolContext ctx, int depth, PassageBudget budget,
                                      List<String> imageUrls, ForwardTask fwd) {
        StringBuilder sb = new StringBuilder();
        appendForwardLevel(msgs, ctx, depth, budget, sb, "", imageUrls, fwd);
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private void appendForwardLevel(List<sair.aiagent.onebot.ForwardMessageExpander.ForwardMessage> msgs,
                                    ToolContext ctx, int depth, PassageBudget budget,
                                    StringBuilder sb, String indent,
                                    List<String> imageUrls, ForwardTask fwd) {
        if (msgs == null) return;
        for (sair.aiagent.onebot.ForwardMessageExpander.ForwardMessage m : msgs) {
            if (m == null) continue;
            if (sb.length() >= FORWARD_RENDER_MAX_CHARS) return;
            String name = (m.senderName != null && !m.senderName.isEmpty()) ? m.senderName : "未知";
            // 普通内容项（图片收集 URL 供识别；嵌套项留给下面的递归分支处理）
            StringBuilder line = new StringBuilder();
            for (sair.aiagent.onebot.ForwardMessageExpander.ForwardItem it : m.items) {
                if (it.isNested()) continue;
                if (it.isImage()) {
                    if (line.length() > 0) line.append(" ");
                    if (it.imageUrl != null && !it.imageUrl.isEmpty()) {
                        line.append("[图片:").append(it.imageUrl).append("]");
                        if (imageUrls != null && !imageUrls.contains(it.imageUrl)) imageUrls.add(it.imageUrl);
                        // 立即登记到消息上：这样**后续每一次**段落 Agent 唤起（含嵌套层）都能在
                        // 自己的预处理里拿到这些图片（有原生视觉就直接看，无视觉则走视觉兜底）
                        if (ctx != null && ctx.qqMsg != null) ctx.qqMsg.addForwardImageUrl(it.imageUrl);
                    } else {
                        line.append("[图片]");
                    }
                } else if (sair.aiagent.onebot.ForwardMessageExpander.ForwardItem.TYPE_TEXT.equals(it.type)) {
                    if (it.text != null && !it.text.isEmpty()) {
                        if (line.length() > 0) line.append(" ");
                        line.append(it.text);
                    }
                } else if (sair.aiagent.onebot.ForwardMessageExpander.ForwardItem.TYPE_FACE.equals(it.type)) {
                    if (line.length() > 0) line.append(" ");
                    line.append("[表情]");
                } else if (sair.aiagent.onebot.ForwardMessageExpander.ForwardItem.TYPE_AT.equals(it.type)) {
                    if (line.length() > 0) line.append(" ");
                    line.append("@").append(it.text != null ? it.text : "");
                }
            }
            if (line.length() > 0) {
                sb.append(indent).append(name).append(": ").append(line).append("\n");
            }
            // 嵌套折叠：层层递归
            for (sair.aiagent.onebot.ForwardMessageExpander.ForwardItem it : m.items) {
                if (!it.isNested()) continue;
                if (sb.length() >= FORWARD_RENDER_MAX_CHARS) return;
                if (depth >= FORWARD_MAX_DEPTH) {
                    sb.append(indent).append("  └[内嵌折叠消息：已达最大展开层数 ")
                      .append(FORWARD_MAX_DEPTH).append("，未继续展开]\n");
                    continue;
                }
                List<sair.aiagent.onebot.ForwardMessageExpander.ForwardMessage> inner = null;
                if (it.nestedRaw != null && !it.nestedRaw.isEmpty()) {
                    inner = sair.aiagent.onebot.ForwardMessageExpander.parseForwardStructure(it.nestedRaw);
                }
                if ((inner == null || inner.isEmpty()) && it.nestedId != null && !it.nestedId.isEmpty()
                        && ctx != null && ctx.napcatApi != null) {
                    try {
                        String nestedJson = ctx.napcatApi.getForwardMsg(it.nestedId);
                        if (nestedJson != null && !nestedJson.isEmpty()) {
                            inner = sair.aiagent.onebot.ForwardMessageExpander.parseForwardStructure(nestedJson);
                        }
                    } catch (Exception e) {
                        AiAgentActivity.debugLog("[FC] 嵌套折叠消息获取失败 id=" + it.nestedId + ": " + e.toString());
                    }
                }
                if (inner == null || inner.isEmpty()) {
                    sb.append(indent).append("  └[内嵌折叠消息：内容获取失败]\n");
                    continue;
                }
                // 先把这一层嵌套渲染出来
                StringBuilder innerSb = new StringBuilder();
                appendForwardLevel(inner, ctx, depth + 1, budget, innerSb, indent + "    ", imageUrls, fwd);
                String innerText = innerSb.toString().trim();
                sb.append(indent).append("  ┌[内嵌折叠消息 第").append(depth + 2).append("层]\n");
                // 该层单独唤起段落 Agent 分析（段落 Agent 可重复创建）
                if (budget.take() && !innerText.isEmpty()) {
                    String nestedAnalysis = passageAnalyze(
                            "以下是一条【内嵌折叠消息】的全部内容，请分析它：\n" + innerText,
                            ctx, "内嵌折叠消息");
                    if (nestedAnalysis != null && !nestedAnalysis.isEmpty()) {
                        sb.append(indent).append("    [内嵌折叠消息分析结论]\n")
                          .append(indentBlock(nestedAnalysis, indent + "    ")).append("\n");
                    } else {
                        sb.append(indentBlock(innerText, indent + "    ")).append("\n");
                    }
                } else {
                    if (!innerText.isEmpty()) sb.append(indentBlock(innerText, indent + "    ")).append("\n");
                }
                sb.append(indent).append("  └[内嵌折叠消息结束]\n");
            }
        }
    }

    /** 给多行文本统一加缩进。 */
    private static String indentBlock(String text, String indent) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            sb.append(indent).append(line).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 长正文分块分析（map-reduce）：按行切块 → 逐块交给段落 Agent → 再由段落 Agent 合并成一份结论。
     */
    private String analyzeTextByChunks(String content, ToolContext ctx, PassageBudget budget, String label) {
        if (content == null || content.trim().isEmpty()) return null;
        if (content.length() <= FORWARD_CHUNK_CHARS) {
            if (!budget.take()) return content;   // 预算耗尽：直接把正文交回（至少不丢信息）
            return passageAnalyze(buildForwardAnalyzeTask(content, label), ctx, label);
        }
        List<String> chunks = splitByLines(content, FORWARD_CHUNK_CHARS);
        AiAgentActivity.qqLog("[FC] 折叠正文超长(" + content.length() + "字)，分 " + chunks.size() + " 块分析");
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            if (budget.take()) {
                String r = passageAnalyze(buildForwardAnalyzeTask(chunk,
                        label + " 第" + (i + 1) + "/" + chunks.size() + " 段"), ctx, label);
                parts.add(r != null && !r.isEmpty() ? r : chunk);
            } else {
                parts.add(chunk);
            }
        }
        if (parts.size() <= 1) return parts.isEmpty() ? null : parts.get(0);
        // 合并：再交给段落 Agent 汇总成一份完整结论
        StringBuilder merged = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            merged.append("【第").append(i + 1).append("段分析】\n").append(parts.get(i)).append("\n\n");
        }
        if (!budget.take()) return merged.toString().trim();
        String finalR = passageAnalyze(
                "下面是同一条折叠消息被分段分析后的结果，请合并成一份完整、不重复的结论：\n" + merged,
                ctx, label + " 汇总");
        return (finalR != null && !finalR.isEmpty()) ? finalR : merged.toString().trim();
    }

    /** 折叠消息分析任务的统一提示词。 */
    private static String buildForwardAnalyzeTask(String content, String label) {
        return "请分析下面这段折叠消息（合并转发的多条消息，" + label + "）：\n"
             + "1. 对话主题与背景\n"
             + "2. 各方发言的核心观点 / 关键信息（注明是谁说的）\n"
             + "3. 与当前对话可能相关的重点\n"
             + "4. 其中的图片：结合图片内容一并说明（你能直接看到图片就直接描述；"
             + "看不到时用 vision 工具按 [图片:URL] 里的地址识图）\n\n"
             + "折叠消息内容：\n" + content;
    }

    /** 唤起段落 Agent 执行一次分析（每次都是一次独立的 FC 循环 = 可重复创建的段落 Agent）。 */
    private String passageAnalyze(String task, ToolContext ctx, String label) {
        AgentBus bus = (dispatcherRef != null) ? dispatcherRef.getAgentBus() : null;
        if (bus == null) return null;
        try {
            String result = bus.invoke("passage", task, ctx);
            if (result == null || result.trim().isEmpty()) return null;
            if (result.startsWith("[AgentBus]")) {
                AiAgentActivity.qqLog("[FC] 段落 Agent(" + label + ") 返回异常: " + result);
                return null;
            }
            return result.trim();
        } catch (Exception e) {
            AiAgentActivity.qqLog("[FC] 段落 Agent(" + label + ") 执行失败: " + e.toString());
            return null;
        }
    }

    /** 按行切分成不超过 maxChars 的块（尽量在消息边界断开）。 */
    private static List<String> splitByLines(String content, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : content.split("\n")) {
            if (cur.length() > 0 && cur.length() + line.length() + 1 > maxChars) {
                chunks.add(cur.toString());
                cur.setLength(0);
            }
            // 单行本身就超长：硬切
            if (line.length() > maxChars) {
                if (cur.length() > 0) { chunks.add(cur.toString()); cur.setLength(0); }
                for (int i = 0; i < line.length(); i += maxChars) {
                    chunks.add(line.substring(i, Math.min(line.length(), i + maxChars)));
                }
                continue;
            }
            if (cur.length() > 0) cur.append("\n");
            cur.append(line);
        }
        if (cur.length() > 0) chunks.add(cur.toString());
        return chunks;
    }

    /**
     * 生成「请主 Agent 标记该折叠消息已分析」的指令块。
     * <p>按用户设计，标记由主 Agent 自己通过 markmessage 工具写入；
     * 这里给出精确参数，保证标记落到正确的消息上（引用折叠用 target=quoted）。</p>
     */
    private String buildForwardMarkHint(ForwardTask fwd, String analysis) {
        String brief = analysis.length() > 120 ? analysis.substring(0, 120) + "…" : analysis;
        StringBuilder sb = new StringBuilder();
        sb.append("[待主 Agent 标记] 上面这段折叠消息（").append(fwd.label).append("）已由段落 Agent 分析完毕。\n");
        sb.append("请调用 markmessage 把结论标记到该折叠消息上，以便下一轮不再重复分析：\n");
        sb.append("  action=set, mark=\"已分析: ").append(brief.replace("\"", "「")).append("\"\n");
        if (fwd.quoted) {
            sb.append("  该折叠消息来自【被引用的消息】，请加 target=quoted");
        } else {
            sb.append("  该折叠消息就是【当前消息】，target 用默认值（current）即可");
        }
        if (fwd.messageId > 0) {
            sb.append("，并带上 message_id=").append(fwd.messageId).append("（精确打标）");
        }
        sb.append("。\n");
        sb.append("（标记完成后正常作答即可；若你已经在本轮标记过，不要重复标记。）");
        return sb.toString();
    }

    // ==================== 视觉兜底（Pro 等无视觉模型） ====================

    /**
     * 视觉兜底识图：当前模型不具备原生视觉时（如 Pro），由视觉 Agent 识图后返回文字描述。
     * <p>AgentBus 不可用或视觉 Agent 未产出有效结果时，直接调用视觉能力模型（写死的 Flash）兜底。</p>
     */
    private String visionFallbackRecognize(List<String> images, ToolContext ctx) {
        if (images == null || images.isEmpty()) return null;
        long start = System.currentTimeMillis();
        AgentBus bus = (dispatcherRef != null) ? dispatcherRef.getAgentBus() : null;
        if (bus != null) {
            try {
                StringBuilder urls = new StringBuilder();
                for (int i = 0; i < images.size(); i++) {
                    urls.append(i + 1).append(": ").append(images.get(i)).append("\n");
                }
                String task = "请识别以下 " + images.size() + " 张图片，并用文字完整转写图中所有文字、"
                        + "描述图片结构与关键信息（主体、场景、关键数据等）。\n"
                        + "只做客观识别与转写，不要分析、不要下结论。\n"
                        + "对每张图片都必须调用 vision 工具（url 原样传入下面的完整地址/base64）：\n" + urls;
                String r = bus.invoke("vision", task, ctx);
                if (r != null && !r.trim().isEmpty() && !r.startsWith("[AgentBus]")) {
                    AiAgentActivity.qqLog("[FC] 视觉 Agent 兜底识图完成 (耗时"
                            + (System.currentTimeMillis() - start) + "ms)");
                    return r.trim();
                }
                AiAgentActivity.qqLog("[FC] 视觉 Agent 兜底未产出有效结果，回退直接调用视觉模型");
            } catch (Exception e) {
                AiAgentActivity.qqLog("[FC] 视觉 Agent 兜底失败，回退直接调用视觉模型: " + e.toString());
            }
        }
        // 兜底：直接调用具备视觉能力的模型（写死 Flash）
        try {
            String prompt = "请仔细识别图片，完整转写图中所有文字内容，并描述图片结构、关键信息。"
                    + "只做客观识别与转写，不要分析、不要下结论。";
            String result = client.chatVision(images, prompt);
            if (result != null && !result.trim().isEmpty()) return result.trim();
        } catch (Exception e) {
            AiAgentActivity.qqLog("[FC] 视觉模型兜底识图失败: " + e.toString());
        }
        return null;
    }

    private static boolean allParallelSafe(List<ToolCall> toolCalls, boolean local) {
        if (toolCalls == null || toolCalls.isEmpty()) return false;
        for (ToolCall tc : toolCalls) {
            if (tc == null) return false;
            if (ToolDispatcher.isStatefulTool(tc.getName())) return false;
            if (!local && !ToolDispatcher.isReadonlyTool(tc.getName())) return false;
        }
        return true;
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
        boolean parallel = (n > 1) && allParallelSafe(toolCalls, ctx == null || !ctx.isExecq());
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
                ThreadManager.getInstance().newNamedFixed("FC-ToolExec", 8);
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
        long timeoutMs = ToolDispatcher.timeoutForTool(tc.getName());
        try {
            return f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            f.cancel(true);
            return "[工具执行超时] " + tc.getName() + " 执行超过 " + (timeoutMs / 1000) + "s，已中断";
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
        // 其他工具：判定范围限定在「首行」，并且只在结果较短时按词命中。
        // 旧实现把「失败/错误/异常/未找到/缺少/超时/not found/is empty」等泛化词在**前 80 字符**内
        // 做 contains，于是一份完全正常的 readfile 结果（正文首行是「# 错误处理指南」）或 readdir
        // 列表（含「错误日志.txt」）都会被判成失败：连续失败计数被推高，
        // 达到 MAX_CONSECUTIVE_FAILURES 后任务被"终止保护"提前掐断，而工具其实都成功了。
        // 现在：长结果（>200 字符＝确实有内容返回）直接判成功；短结果才看首行措辞。
        if (r.length() > 200) return false;
        int nl = r.indexOf('\n');
        String head = nl > 0 ? r.substring(0, nl) : r;
        if (head.length() > 80) head = head.substring(0, 80);
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
