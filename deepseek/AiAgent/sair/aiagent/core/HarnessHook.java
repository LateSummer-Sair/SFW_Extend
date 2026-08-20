package sair.aiagent.core;

/**
 * Harness 生命周期钩子 —— 工具执行的「生成/评估分离」拦截点。
 *
 * <p>注册到 {@link ToolDispatcher} 后，在每次工具调用前后回调，实现：
 * <ul>
 *   <li><b>前置钩子</b>（preExecute）：参数 Schema 校验、权限二次校验、越权/高危代码确定性阻断。</li>
 *   <li><b>后置钩子</b>（postExecute）：结果验证、副作用检查、长度规整、链路埋点。</li>
 * </ul>
 * 拦截点运行在模型生成与工具落地之间，构成「确定性约束层」——即使模型幻觉绕过提示词，
 * 也无法绕过本层校验。可注册多个钩子，按注册顺序依次执行。</p>
 */
public interface HarnessHook {

    /**
     * 前置钩子：在工具实际执行前调用。
     *
     * @param toolName      工具名
     * @param argumentsJson 工具参数 JSON 字符串
     * @param ctx           执行上下文（console / execq）
     * @return 返回非 null 字符串表示<b>阻断</b>，该字符串直接作为工具结果返回（不再执行工具）；
     *         返回 null 表示放行，继续执行。
     */
    String preExecute(String toolName, String argumentsJson, ToolContext ctx);

    /**
     * 后置钩子：在工具执行完成后调用，可校验/改写结果。
     *
     * @param toolName      工具名
     * @param argumentsJson 工具参数 JSON 字符串
     * @param ctx           执行上下文
     * @param result        工具原始执行结果
     * @return 最终返回给模型的结果（可原样返回，也可校验后改写/标注）。
     */
    String postExecute(String toolName, String argumentsJson, ToolContext ctx, String result);
}
