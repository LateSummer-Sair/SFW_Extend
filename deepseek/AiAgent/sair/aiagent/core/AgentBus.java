package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ToolDefinition;

/**
 * AgentBus —— 递归多智能体通信运行时（多模型协作核心）。
 *
 * <p>把「唤起另一个 Agent」做成 Function Calling 工具（{@code call_agent}/{@code ask_agent}），
 * 让主模型（main）、视觉模型（vision）、段落模型（passage）三个 Agent 在自己的 Function Calling
 * 循环中互相唤起、互相发问、互相传递数据，形成双向递归协作。</p>
 *
 * <p>关键机制：</p>
 * <ul>
 *   <li><b>注册表</b>：每个 Agent 用 {@link AgentDef} 描述（name/systemPrompt/model/toolsProvider），
 *       {@code register} 一次即可被其他 Agent 唤起。</li>
 *   <li><b>递归调度</b>：{@code invoke} 会为被唤起的 Agent 新建独立的 Function Calling 循环，
 *       其工具集额外注入 {@code call_agent}/{@code ask_agent}，使子 Agent 也能反向唤起其他 Agent。</li>
 *   <li><b>防死循环</b>：递归深度随 {@link ToolContext} 对象透传（跨线程工具执行也能正确累加），超过 {@link #MAX_DEPTH} 拒绝继续唤起。</li>
 * </ul>
 *
 * <p>拓展性：新增第 N 个 Agent（音频/视频/链接摘要等）只需定义 {@link AgentDef} 并 {@code register} 一次，
 * 所有现有 Agent 即可通过 {@code call_agent} 唤起它，无需改动调度核心。</p>
 */
public class AgentBus {

    /** 递归唤起最大深度，防止无限循环（A→B→A→B…）。 */
    public static final int MAX_DEPTH = 6;

    /** 子 Agent 单次工具调用预算（比主循环更紧，避免子 Agent 耗尽全局预算）。 */
    private static final int SUB_AGENT_MAX_TOOL_CALLS = 8;
    /** 子 Agent 单次执行超时（毫秒）。 */
    private static final long SUB_AGENT_TIMEOUT_MS = 300_000L;
    /** 子 Agent 结果回传前的最大字符数。 */
    private static final int SUB_AGENT_RESULT_MAX_CHARS = 1200;
    /** 子 Agent 结果缓存（agent|task → result），避免重复唤起重复消耗 token。 */
    private final Map<String, CacheEntry> resultCache = new ConcurrentHashMap<>();
    /** 结果缓存最大条数。 */
    private static final int RESULT_CACHE_MAX = 256;
    /** 结果缓存有效期（毫秒）。 */
    private static final long RESULT_CACHE_TTL_MS = 10 * 60 * 1000L;

    private static final class CacheEntry {
        final String value;
        final long ts;
        CacheEntry(String value, long ts) { this.value = value; this.ts = ts; }
    }

    /** 单个 Agent 的定义。 */
    public static final class AgentDef {
        public final String name;
        public final String systemPrompt;
        public final String model;
        public final Function<ToolContext, List<ToolDefinition>> toolsProvider;

        public AgentDef(String name, String systemPrompt, String model,
                        Function<ToolContext, List<ToolDefinition>> toolsProvider) {
            this.name = name;
            this.systemPrompt = systemPrompt;
            this.model = model;
            this.toolsProvider = toolsProvider;
        }
    }

    private final DeepSeekClient client;
    private final ToolDispatcher dispatcher;
    private final Map<String, AgentDef> agents = new ConcurrentHashMap<>();

    public AgentBus(DeepSeekClient client, ToolDispatcher dispatcher) {
        this.client = client;
        this.dispatcher = dispatcher;
    }

    /** 注册一个可被唤起的 Agent。 */
    public void register(AgentDef def) {
        if (def != null && def.name != null && !def.name.trim().isEmpty()) {
            agents.put(def.name.trim(), def);
        }
    }

    /**
     * 唤起指定 Agent 执行任务（递归入口）。
     *
     * @param name Agent 名（main/vision/passage 等）
     * @param task 交给该 Agent 的任务描述或问题
     * @param ctx  工具执行上下文（透传给子 Agent 的工具）
     * @return 该 Agent 的最终答复文本；失败或深度超限时返回错误文本
     */
    public String invoke(String name, String task, ToolContext ctx) {
        if (name == null || name.trim().isEmpty()) {
            return "[AgentBus] 未指定 Agent 名";
        }
        AgentDef def = agents.get(name.trim());
        if (def == null) {
            return "[AgentBus] 未知 Agent: " + name + "（可用：main/vision/passage）";
        }
        // 深度随 ctx 对象透传（工具在独立线程执行，ThreadLocal 无法跨线程传递，改用 ctx 字段确保深度正确累加）
        int d = (ctx != null) ? ctx.agentBusDepth : 0;
        if (d >= MAX_DEPTH) {
            return "[AgentBus] 递归唤起深度超限（已达 " + MAX_DEPTH + "），已停止继续唤起，请基于已有信息直接给出结论";
        }
        String cacheKey = def.name + "|" + (task == null ? "" : task);
        CacheEntry cachedEntry = resultCache.get(cacheKey);
        if (cachedEntry != null && System.currentTimeMillis() - cachedEntry.ts < RESULT_CACHE_TTL_MS) {
            AiAgentActivity.debugLog("[AgentBus] ← Agent[" + name.trim() + "] 缓存命中");
            return cachedEntry.value;
        }
        if (cachedEntry != null) resultCache.remove(cacheKey);
        if (ctx != null) ctx.agentBusDepth = d + 1;
        String taskBrief = (task == null ? "" : task);
        if (taskBrief.length() > 120) taskBrief = taskBrief.substring(0, 120) + "...";
        long invokeStart = System.currentTimeMillis();
        // SFW 控制台可观测性：输出 Agent 间互相唤起日志（谁唤起谁、任务、深度）
        AiAgentActivity.debugLog("[AgentBus] → 唤起 Agent[" + name.trim() + "] (深度" + (d + 1) + "/" + MAX_DEPTH + "): " + taskBrief);
        try {
            List<ToolDefinition> tools = def.toolsProvider != null ? def.toolsProvider.apply(ctx) : new ArrayList<ToolDefinition>();
            List<ToolDefinition> merged = new ArrayList<>(tools == null ? new ArrayList<ToolDefinition>() : tools);
            // 去重：toolsProvider 已包含 call_agent/ask_agent 时不再重复添加
            java.util.Set<String> names = new java.util.HashSet<>();
            for (ToolDefinition t : merged) {
                if (t != null) names.add(t.getName());
            }
            for (ToolDefinition ct : callAgentTools()) {
                if (!names.contains(ct.getName())) merged.add(ct);
            }
            FunctionCallingBridge bridge = new FunctionCallingBridge(client);
            bridge.setMaxToolCalls(SUB_AGENT_MAX_TOOL_CALLS);
            String result = invokeWithTimeout(bridge, task, def, merged, ctx);
            result = truncateResult(result);
            if (resultCache.size() >= RESULT_CACHE_MAX) {
                resultCache.clear();
            }
            resultCache.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
            String resultBrief = (result == null ? "(null)" : result);
            if (resultBrief.length() > 120) resultBrief = resultBrief.substring(0, 120) + "...";
            AiAgentActivity.debugLog("[AgentBus] ← Agent[" + name.trim() + "] 返回 (耗时" + (System.currentTimeMillis() - invokeStart) + "ms): " + resultBrief);
            return result;
        } catch (Exception e) {
            AiAgentActivity.debugLog("[AgentBus] ✗ Agent[" + name.trim() + "] 执行失败: " + e.toString());
            return "[AgentBus] 唤起 Agent \"" + name + "\" 执行失败: " + e.toString();
        } finally {
            if (ctx != null) ctx.agentBusDepth = d;
        }
    }

    /** 子 Agent 执行加超时保护。 */
    private String invokeWithTimeout(final FunctionCallingBridge bridge, final String task,
                                     final AgentDef def, final List<ToolDefinition> merged,
                                     final ToolContext ctx) {
        ExecutorService pool = ThreadManager.getInstance().newNamedCached("AgentBus");
        Future<String> f = pool.submit(new Callable<String>() {
            @Override
            public String call() throws Exception {
                return bridge.runWithDispatcher(task, def.systemPrompt, null, merged, def.model, dispatcher, ctx, null);
            }
        });
        try {
            return f.get(SUB_AGENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            f.cancel(true);
            return "[AgentBus] 子 Agent \"" + def.name + "\" 执行超时（>" + (SUB_AGENT_TIMEOUT_MS / 1000) + "s）";
        } catch (Exception e) {
            return "[AgentBus] 子 Agent \"" + def.name + "\" 执行失败: " + e.toString();
        }
    }

    /** 回传前截断，避免长结果放大父 Agent token。 */
    private static String truncateResult(String result) {
        if (result == null) return null;
        if (result.length() <= SUB_AGENT_RESULT_MAX_CHARS) return result;
        return result.substring(0, SUB_AGENT_RESULT_MAX_CHARS) + "\n…(子 Agent 结果过长已截断)";
    }

    /** 返回 Agent 间通信工具（call_agent / ask_agent），供子 Agent 工具集注入。 */
    public static List<ToolDefinition> callAgentTools() {
        List<ToolDefinition> tools = new ArrayList<>();
        tools.add(new ToolDefinition("call_agent",
                "唤起子 Agent 执行任务并返回结果。可用：main(主模型)/vision(看图)/passage(概括折叠消息)")
                .addString("agent", "main/vision/passage")
                .addString("task", "子任务描述"));
        tools.add(new ToolDefinition("ask_agent",
                "向子 Agent 请教问题。可用：main/vision/passage")
                .addString("agent", "main/vision/passage")
                .addString("question", "问题"));
        return tools;
    }
}
