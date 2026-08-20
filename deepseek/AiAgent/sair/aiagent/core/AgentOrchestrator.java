package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.aiagent.model.ChatMessage;
import sair.aiagent.model.ToolDefinition;

/**
 * AgentOrchestrator —— 多智能体编排引擎（Harness 多智能体能力，opt-in）。
 *
 * <p>在单个 Agent 的 Function Calling 循环之上，增加「任务分解 + 多 Agent 协作」能力，
 * 支持三种编排模式：</p>
 * <ul>
 *   <li><b>PIPELINE</b>（流水线）—— 规划者把任务分解为有序子步骤，按顺序串行执行，
 *       每步产出作为下一步的上下文。</li>
 *   <li><b>FANOUT_FANIN</b>（扇出扇入）—— 规划者拆出可并行执行的独立子任务，
 *       并发执行后由聚合者综合为最终答案。</li>
 *   <li><b>EXPERT_POOL</b>（专家池）—— 路由器按任务类型选择专家角色，用角色专属
 *       系统提示词让单个专家 Agent 完成任务。</li>
 * </ul>
 *
 * <p>设计原则：<b>opt-in + 不改变默认链路</b>——{@link AgentExecutor} 默认仍走单 Agent
 * Function Calling；只有用户显式调用编排命令时才走本引擎，避免每次任务翻倍 API 开销。
 * 并发执行统一走 {@link ThreadManager} 的命名线程池。</p>
 */
public class AgentOrchestrator {

    /** 编排模式。 */
    public enum Mode {
        PIPELINE, FANOUT_FANIN, EXPERT_POOL
    }

    /** 规划者系统提示词 —— 只输出有序子步骤（每行一个，形如 "1. xxx"）。 */
    private static final String PLANNER_SYSTEM =
        "你是任务规划器。请把用户任务分解为清晰、可独立执行的子步骤，按执行顺序每行输出一个，"
      + "格式严格为「序号. 子步骤描述」，不要输出任何解释、标题或多余内容。\n"
      + "若任务本身已足够简单，只输出一个子步骤即可。\n"
      + "示例：\n1. 搜索相关资料\n2. 汇总为要点\n3. 输出最终结论";

    /** 扇出规划者提示词 —— 拆分为「彼此独立、可并行」的子任务。 */
    private static final String FANOUT_SYSTEM =
        "你是任务规划器。请把用户任务拆分为若干彼此独立、可并行执行的子任务，"
      + "每行输出一个，格式严格为「序号. 子任务描述」，不要输出任何解释或多余内容。\n"
      + "子任务之间不得有先后依赖，各自独立可完成。若无法拆分，只输出一个子任务。\n"
      + "示例：\n1. 查询 A 相关信息\n2. 查询 B 相关信息";

    /** 聚合者系统提示词 —— 综合多个子任务结果输出最终答案。 */
    private static final String SYNTHESIZER_SYSTEM =
        "你是结果聚合器。你会收到一个原始任务和若干子任务的执行结果，"
      + "请综合这些结果，输出一份条理清晰、完整准确的最终答案。"
      + "不要遗漏关键信息，也不要编造子任务结果中没有的内容。";

    /** 专家路由器提示词 —— 只输出专家角色名（一个词）。 */
    private static final String ROUTER_SYSTEM =
        "你是专家路由器。根据用户任务判断最合适的专家角色，只输出以下角色之一（不要其它内容）：\n"
      + "coder（编程/脚本/调试/代码）\n"
      + "researcher（检索/调研/实时信息/资料）\n"
      + "writer（写作/文案/润色/翻译）\n"
      + "general（其它通用任务）";

    /** 专家角色 → 角色专属系统提示词补充。 */
    private static final String EXPERT_CODER =
        "你是一位资深软件工程师，擅长编写、调试与解释代码。优先通过工具实际执行验证，输出可运行的代码。";
    private static final String EXPERT_RESEARCHER =
        "你是一位严谨的研究员，擅长检索与核实信息。优先通过 web/search 工具查询实时资料，对不确定信息标注来源。";
    private static final String EXPERT_WRITER =
        "你是一位专业写手，擅长文案创作、润色与翻译。语言流畅自然，贴合语境，重点突出。";
    private static final String EXPERT_GENERAL =
        "你是一位全能型助手，直接高效地完成用户任务。";

    /** 步骤行解析：形如 "1." / "1)" / "1、" / "1 " 开头。 */
    private static final Pattern STEP_LINE = Pattern.compile("^\\s*\\d+\\s*[.)、]?\\s*(.+)$");

    private final DeepSeekClient client;
    private final ToolDispatcher dispatcher;
    /** 审查者子 Agent（可空，非空时对最终回复做验证闭环）。 */
    private volatile CriticAgent critic;

    public AgentOrchestrator(DeepSeekClient client, ToolDispatcher dispatcher) {
        this.client = client;
        this.dispatcher = dispatcher;
    }

    /** 设置审查者子 Agent，启用最终回复验证闭环。 */
    public void setCritic(CriticAgent critic) {
        this.critic = critic;
    }

    /**
     * 编排入口。
     *
     * @param task           用户任务
     * @param mode           编排模式
     * @param baseSystemPrompt 子 Agent 的基础系统提示词（角色/能力/环境）
     * @param tools          工具定义列表
     * @param model          模型名
     * @param ctx            工具执行上下文
     * @param stopCheck      停止检查回调（可空）
     * @return 最终答复
     */
    public String orchestrate(String task, Mode mode, String baseSystemPrompt,
                              List<ToolDefinition> tools, String model, ToolContext ctx,
                              java.util.function.BooleanSupplier stopCheck) {
        if (task == null || task.trim().isEmpty()) return "";
        try {
            switch (mode == null ? Mode.PIPELINE : mode) {
                case PIPELINE:
                    return runPipeline(task, baseSystemPrompt, tools, model, ctx, stopCheck);
                case FANOUT_FANIN:
                    return runFanoutFanin(task, baseSystemPrompt, tools, model, ctx, stopCheck);
                case EXPERT_POOL:
                    return runExpertPool(task, baseSystemPrompt, tools, model, ctx, stopCheck);
                default:
                    return runPipeline(task, baseSystemPrompt, tools, model, ctx, stopCheck);
            }
        } catch (Exception e) {
            return "[多智能体编排失败] " + e.toString();
        }
    }

    // ==================== PIPELINE ====================

    private String runPipeline(String task, String base, List<ToolDefinition> tools,
                               String model, ToolContext ctx, java.util.function.BooleanSupplier stopCheck) {
        List<String> steps = planSteps(task, model, PLANNER_SYSTEM);
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (stopCheck != null && stopCheck.getAsBoolean()) return buildSoFar(context);
            String step = steps.get(i);
            String stepTask = step + (context.length() > 0 ? "\n\n前序步骤的成果参考：\n" + context : "");
            String result = runSubAgent(stepTask, base, tools, model, ctx, stopCheck);
            context.append("[步骤").append(i + 1).append("] ").append(step).append("\n")
                   .append(result).append("\n\n");
        }
        return context.toString().trim();
    }

    // ==================== FANOUT_FANIN ====================

    private String runFanoutFanin(String task, String base, List<ToolDefinition> tools,
                                  String model, ToolContext ctx, java.util.function.BooleanSupplier stopCheck) {
        List<String> subtasks = planSteps(task, model, FANOUT_SYSTEM);
        if (subtasks.size() <= 1) {
            // 无法拆分，直接单 Agent 执行
            return runSubAgent(task, base, tools, model, ctx, stopCheck);
        }
        // 并发执行独立子任务
        int n = Math.min(subtasks.size(), 4);
        ExecutorService pool = ThreadManager.getInstance().newNamedFixed("Orchestrator-Fanout", n);
        List<Future<String>> futures = new ArrayList<>();
        for (final String sub : subtasks) {
            futures.add(pool.submit(new Callable<String>() {
                @Override
                public String call() {
                    return runSubAgent(sub, base, tools, model, ctx, stopCheck);
                }
            }));
        }
        // 收集结果（保持子任务顺序）
        List<String> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                String r = futures.get(i).get(600, TimeUnit.SECONDS);
                results.add((r == null || r.trim().isEmpty()) ? "(子任务未产出结果)" : r);
            } catch (Exception e) {
                results.add("(子任务执行失败: " + e.toString() + ")");
            }
        }
        // 聚合
        return synthesize(task, subtasks, results, model);
    }

    // ==================== EXPERT_POOL ====================

    private String runExpertPool(String task, String base, List<ToolDefinition> tools,
                                 String model, ToolContext ctx, java.util.function.BooleanSupplier stopCheck) {
        String role = routeExpert(task, model);
        String expertSuffix = expertPrompt(role);
        String expertSystem = (base != null ? base : "") + "\n\n" + expertSuffix;
        String result = runSubAgent(task, expertSystem, tools, model, ctx, stopCheck);
        return result;
    }

    // ==================== 子 Agent 执行 ====================

    /** 用一个独立上下文窗口的 Function Calling 循环执行单个子任务。 */
    private String runSubAgent(String task, String stableSystem, List<ToolDefinition> tools,
                               String model, ToolContext ctx, java.util.function.BooleanSupplier stopCheck) {
        FunctionCallingBridge bridge = new FunctionCallingBridge(client);
        bridge.setCritic(critic);
        return bridge.runWithDispatcher(task, stableSystem, null, tools, model,
                dispatcher, ctx, stopCheck);
    }

    // ==================== 规划 / 路由 / 聚合 ====================

    /** 让 LLM 把任务拆解为子步骤。失败时回退为单步骤。 */
    private List<String> planSteps(String task, String model, String systemPrompt) {
        List<String> steps = new ArrayList<>();
        try {
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("system", systemPrompt));
            msgs.add(new ChatMessage("user", task));
            String resp = client.chatSync(msgs, model);
            steps = parseSteps(resp);
        } catch (Exception ignored) {
            // 规划失败回退
        }
        if (steps.isEmpty()) {
            steps.add(task);
        }
        return steps;
    }

    /** 解析步骤列表（每行 "数字. 内容"）。 */
    private static List<String> parseSteps(String resp) {
        List<String> steps = new ArrayList<>();
        if (resp == null) return steps;
        for (String line : resp.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            Matcher m = STEP_LINE.matcher(trimmed);
            if (m.find()) {
                steps.add(m.group(1).trim());
            }
        }
        return steps;
    }

    /** 让 LLM 选择专家角色。失败回退 general。 */
    private String routeExpert(String task, String model) {
        try {
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("system", ROUTER_SYSTEM));
            msgs.add(new ChatMessage("user", task));
            String resp = client.chatSync(msgs, model);
            if (resp != null) {
                String r = resp.trim().toLowerCase();
                if (r.contains("coder")) return "coder";
                if (r.contains("researcher") || r.contains("research")) return "researcher";
                if (r.contains("writer")) return "writer";
            }
        } catch (Exception ignored) {}
        return "general";
    }

    /** 获取角色专属系统提示词补充。 */
    private static String expertPrompt(String role) {
        if ("coder".equals(role)) return EXPERT_CODER;
        if ("researcher".equals(role)) return EXPERT_RESEARCHER;
        if ("writer".equals(role)) return EXPERT_WRITER;
        return EXPERT_GENERAL;
    }

    /** 让 LLM 综合多个子任务结果。失败时简单拼接。 */
    private String synthesize(String task, List<String> subtasks, List<String> results, String model) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < subtasks.size(); i++) {
            body.append("【子任务").append(i + 1).append("】").append(subtasks.get(i)).append("\n")
                .append(results.get(i)).append("\n\n");
        }
        try {
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("system", SYNTHESIZER_SYSTEM));
            msgs.add(new ChatMessage("user", "原始任务：\n" + task + "\n\n子任务结果：\n" + body));
            String resp = client.chatSync(msgs, model);
            if (resp != null && !resp.trim().isEmpty()) return resp.trim();
        } catch (Exception ignored) {}
        return body.toString().trim();
    }

    /** 流水线中途停止时返回已产出内容。 */
    private static String buildSoFar(StringBuilder context) {
        String s = context.toString().trim();
        return s.isEmpty() ? "(已停止)" : s + "\n\n(已停止)";
    }
}
