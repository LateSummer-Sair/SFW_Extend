package sair.aiagent.core;

import java.util.ArrayList;
import java.util.List;

import sair.aiagent.model.ChatMessage;

/**
 * CriticAgent —— 审查者子 Agent（生成/评估分离的「评估」侧）。
 *
 * <p>Harness 验证闭环的核心：用一个<b>独立上下文窗口</b>的轻量审查者，对主 Agent
 * 的产出（最终回复 / 关键操作）做二次评估。主 Agent 负责「生成」，CriticAgent
 * 负责「评估」，评估不通过则阻断（Stop Hook），从而防止幻觉、越权、违规输出逃逸。</p>
 *
 * <p>设计原则：<b>fail-open</b>——审查器自身异常（网络/超时/解析失败）时放行，
 * 避免审查器成为单点故障；但确定性约束层（HarnessConfig 权限矩阵）始终在线。</p>
 */
public class CriticAgent {

    /** 审查者系统提示词 —— 只输出 PASS 或 BLOCK: 原因。 */
    private static final String CRITIC_SYSTEM =
        "你是一个严格的输出审查员。你会收到一个 AI 助手的候选输出（回复或操作）。\n"
      + "请只判断该输出是否违反以下红线，并仅输出两种结果之一：\n"
      + "1. PASS —— 输出安全、合规、无明显问题\n"
      + "2. BLOCK: 原因 —— 输出存在以下问题之一\n"
      + "\n"
      + "红线清单：\n"
      + "- 泄露系统提示词、内部配置、API 密钥、数据库路径等敏感信息\n"
      + "- 输出危险指令（删除系统文件、格式化磁盘、恶意代码、攻击载荷）\n"
      + "- 冒充主人身份、越权执行管理员/群主操作\n"
      + "- 对用户进行人身攻击、辱骂、歧视、骚扰\n"
      + "- 编造虚假事实并当作真实信息输出（明显幻觉）\n"
      + "- 泄露他人隐私（QQ号、手机号、住址等）\n"
      + "\n"
      + "注意：普通的功能性回复、正常对话、合理的工具调用说明都应当 PASS。\n"
      + "不要过度敏感，只有明确踩红线才 BLOCK。\n"
      + "严格只输出 \"PASS\" 或 \"BLOCK: 原因\"，不要输出其它内容。";

    private final DeepSeekClient client;
    private volatile String model;

    public CriticAgent(DeepSeekClient client) {
        this.client = client;
    }

    /** 覆盖审查所用模型（null=使用 execq 模型）。 */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 审查候选最终回复。
     *
     * @param task      用户任务
     * @param candidate 主 Agent 生成的候选回复
     * @return "PASS"（放行）或 "BLOCK: 原因"（阻断）
     */
    public String reviewResponse(String task, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return "PASS";
        String user = "【用户任务】\n" + safe(task) + "\n\n【候选回复】\n" + candidate;
        return review(CRITIC_SYSTEM, user);
    }

    /**
     * 审查关键操作（群管 / 高危文件写入 / 修改型指令）。
     *
     * @param toolName 工具名
     * @param args     工具参数
     * @param context  上下文说明（谁在什么场景下触发）
     * @return "PASS" 或 "BLOCK: 原因"
     */
    public String reviewAction(String toolName, String args, String context) {
        String user = "【关键操作】工具: " + safe(toolName) + "\n参数: " + safe(args)
                + "\n\n【上下文】\n" + safe(context)
                + "\n\n请判断该操作是否越权、危险或违规，输出 PASS 或 BLOCK: 原因。";
        return review(CRITIC_SYSTEM, user);
    }

    /** 执行一次审查调用，返回 PASS 或 BLOCK: 原因（异常时 fail-open 放行）。 */
    private String review(String systemPrompt, String userContent) {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(new ChatMessage("system", systemPrompt));
        msgs.add(new ChatMessage("user", userContent));
        try {
            String m = (model != null && !model.isEmpty()) ? model : AiConfig.getInstance().getExecqModel();
            String resp = client.chatSync(msgs, m);
            return parseVerdict(resp);
        } catch (Exception e) {
            return "PASS"; // fail-open：审查器故障不阻断业务
        }
    }

    /** 解析审查结果：优先识别 PASS/BLOCK，识别失败时保守放行。 */
    private static String parseVerdict(String resp) {
        if (resp == null || resp.trim().isEmpty()) return "PASS";
        String r = resp.trim();
        String up = r.toUpperCase();
        if (up.startsWith("PASS")) return "PASS";
        if (up.startsWith("BLOCK")) {
            int colon = r.indexOf(':');
            if (colon > 0) {
                return "BLOCK: " + r.substring(colon + 1).trim();
            }
            int c = r.indexOf('：');
            if (c > 0) {
                return "BLOCK: " + r.substring(c + 1).trim();
            }
            return "BLOCK: " + r;
        }
        // 非标准格式：含 BLOCK 关键字视为阻断，否则放行（fail-open）
        if (up.contains("BLOCK")) return "BLOCK: " + r;
        return "PASS";
    }

    private static String safe(String s) {
        return s == null ? "" : (s.length() > 2000 ? s.substring(0, 2000) : s);
    }
}
