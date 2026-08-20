package sair.aiagent.model;

import java.io.Serializable;

/**
 * Function Calling 工具调用结果 —— 对应 API 响应 message.tool_calls 数组项。
 * <p>当 AI 决定调用某个工具时返回，包含工具名和 JSON 序列化的参数。</p>
 */
public class ToolCall implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String id;
    private final String name;
    private final String arguments;

    public ToolCall(String id, String name, String arguments) {
        this.id = (id != null) ? id : "";
        this.name = (name != null) ? name : "";
        this.arguments = (arguments != null) ? arguments : "{}";
    }

    /** 工具调用唯一 ID（回传 tool 消息时需要） */
    public String getId() { return id; }

    /** 工具函数名 */
    public String getName() { return name; }

    /** JSON 序列化的参数字符串 */
    public String getArguments() { return arguments; }

    @Override
    public String toString() {
        return "ToolCall{" + name + "(" + arguments + ")}";
    }
}
