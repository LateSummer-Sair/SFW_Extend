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

    private final DeepSeekClient client;

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
        messages.add(new ChatMessage("user", userContent.toString()));

        int consecutiveFailures = 0;   // 连续失败次数（任何成功即归零）
        int criticRetries = 0;         // 审查者阻断后重新生成次数
        int totalToolCalls = 0;        // 工具调用总次数（尝试次数）
        String lastFailure = null;     // 最后一次失败信息
        boolean fallbackHinted = false;          // 是否已注入动态注入兜底提示
        boolean hasEval = hasTool(tools, "eval");       // 通道是否有 eval（动态注入：Java 编译执行，终极兜底）
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
                DeepSeekClient.ToolCallResult r = client.chatSyncWithTools(messages, tools, "auto", model);
                if (!r.hasToolCalls()) {
                    String content = (r.content != null && !r.content.trim().isEmpty())
                            ? r.content : "(AI 未返回文本)";
                    // Harness 验证闭环：审查者评估最终回复，不通过则阻断并重新生成
                    if (critic != null && criticRetries < MAX_CRITIC_RETRIES) {
                        String verdict = critic.reviewResponse(task, content);
                        traceVerdict = verdict;
                        if (verdict.startsWith("BLOCK")) {
                            criticRetries++;
                            messages.add(new ChatMessage("assistant", content));
                            messages.add(new ChatMessage("user", buildCriticCorrection(verdict)));
                            continue;
                        }
                    }
                    return content;
                }
                messages.add(ChatMessage.createAssistantWithToolCalls(r.content, r.toolCalls));
                totalToolCalls += r.toolCalls.size();
                List<String> toolResults = executeToolCalls(r.toolCalls, ctx, dispatcher);
                for (int ti = 0; ti < r.toolCalls.size(); ti++) {
                    ToolCall tc = r.toolCalls.get(ti);
                    String result = toolResults.get(ti);
                    boolean failed = isFailureResult(result);
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
     * <p>仅识别高置信的失败信号，避免将「文件内容中偶然出现的 error/异常」误判为失败。</p>
     */
    private static boolean isFailureResult(String result) {
        if (result == null) return true;
        String r = result.trim();
        if (r.isEmpty()) return false;
        if (r.startsWith("[工具执行异常]")) return true;
        // Web 抓取失败（HTTP 错误码 / 网络错误 / 被拒绝）—— 仅检查冒号前的头部，避免正文「失败」字样误判
        if (r.startsWith("Web GET")) {
            // 失败模式：Web GET [url] 失败: HTTP xxx / 错误: xxx（英文冒号精确匹配）
            if (r.contains("失败:") || r.contains("错误:") || r.contains("被拒绝")) return true;
            // 成功但正文为空/过短 → 视为无进展
            int httpIdx = r.indexOf("(HTTP ");
            if (httpIdx > 0) {
                int colon = r.indexOf(':', httpIdx);
                String body = colon > 0 ? r.substring(colon + 1).trim() : "";
                if (body.length() < 30) return true;
            }
        }
        // 高置信失败关键词（成功结果几乎不会包含）
        if (r.contains("无权限") || r.contains("权限不足") || r.contains("被拒绝")
                || r.contains("仅主人可用") || r.contains("未知工具") || r.contains("未知操作")
                || r.contains("文件不存在") || r.contains("目录不存在") || r.contains("发送失败")
                || r.contains("执行失败") || r.contains("任务执行失败") || r.contains("未就绪")
                || r.contains("expired") || r.contains("缺少") || r.contains("为空")
                || r.contains("命令错误") || r.contains("命令中断") || r.contains("未找到")
                || r.contains("没有找到") || r.contains("无结果") || r.contains("无匹配")
                || r.contains("未获取") || r.contains("未解析") || r.contains("反爬")
                || r.contains("无有效") || r.contains("超时") || r.contains("无搜索结果")) {
            return true;
        }
        // 「失败/未初始化/不可用」较宽泛，仅当出现在结果头部（前 40 字符）才判为失败，避免误伤文件正文
        String head40 = r.length() > 40 ? r.substring(0, 40) : r;
        if (head40.contains("失败") || head40.contains("未初始化") || head40.contains("不可用")) {
            return true;
        }
        // 英文失败/无进展信号（FileUtils 等返回，多为开头，仅检查头部避免误伤文件正文）
        String head60 = r.length() > 60 ? r.substring(0, 60) : r;
        if (head60.contains("not found") || head60.contains("is empty")
                || head60.contains("not a directory") || head60.contains("is a directory")
                || head60.contains("cannot read") || head60.contains("too large")
                || head60.contains("not initialized") || head60.contains("not available")) {
            return true;
        }
        // 「错误/异常」较宽泛，仅当出现在结果开头 30 字符内才判为失败，避免误伤文件内容
        String head = r.length() > 30 ? r.substring(0, 30) : r;
        return head.contains("错误") || head.contains("异常");
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
