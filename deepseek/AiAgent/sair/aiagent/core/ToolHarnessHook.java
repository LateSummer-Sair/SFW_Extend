package sair.aiagent.core;

/**
 * ToolHarnessHook —— 面向工具执行的标准 Harness 钩子实现。
 *
 * <p>串联 {@link HarnessConfig} 的确定性约束，作为 {@link ToolDispatcher} 的第一道闸门：
 * <ol>
 *   <li>前置：热加载检测 → 权限矩阵校验 → 高危代码模式拦截。</li>
 *   <li>后置：结果空值标注 / 超长截断 / 副作用检查。</li>
 * </ol>
 * 作为 {@link ToolDispatcher} 唯一的权限闸门（原硬编码校验已合并至 {@link HarnessConfig}）。</p>
 */
public class ToolHarnessHook implements HarnessHook {

    private final HarnessConfig config;

    public ToolHarnessHook(HarnessConfig config) {
        this.config = (config != null) ? config : HarnessConfig.getInstance();
    }

    @Override
    public String preExecute(String toolName, String argumentsJson, ToolContext ctx) {
        // 热加载：文件变化即时生效（仅首次命中时多一次磁盘读）
        config.reloadIfChanged();

        // 1. 权限矩阵校验（确定性阻断，AI 绕不过）
        String permErr = config.checkPermission(toolName, ctx);
        if (permErr != null) return permErr;

        // 2. 高危代码模式拦截（execq 非主人动态注入安全边界）
        String codeErr = config.checkCodeSafety(toolName, argumentsJson, ctx);
        if (codeErr != null) return codeErr;

        return null; // 放行
    }

    @Override
    public String postExecute(String toolName, String argumentsJson, ToolContext ctx, String result) {
        // eval/evaljs 编译/执行失败确定性检测：明确标注失败信号，供连续失败计数与 AI 感知
        if (("eval".equals(toolName) || "evaljs".equals(toolName)) && result != null) {
            String annotated = annotateCodeFailure(result);
            if (annotated != null) return annotated;
        }
        // 结果校验 + 长度规整
        return config.validateResult(toolName, result);
    }

    /** 检测动态代码执行/编译失败信号，命中则加前缀标注（否则返回 null）。 */
    private static String annotateCodeFailure(String result) {
        String r = result.trim();
        boolean fail = r.startsWith("编译失败") || r.startsWith("编译器不可用")
                || r.contains("编译错误") || r.contains("Compilation failed")
                || (r.startsWith("类 [") && r.contains("加载失败"))
                || r.startsWith("创建实例失败") || r.startsWith("执行失败")
                || r.startsWith("Java 编译器不可用");
        return fail ? "[代码执行失败] " + result : null;
    }
}
