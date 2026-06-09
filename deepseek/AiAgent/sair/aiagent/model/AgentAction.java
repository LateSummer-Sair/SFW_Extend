package sair.aiagent.model;

/**
 * Agent操作模型 —— 描述AI在Agent模式下决定执行的一个操作。
 * <p>
 * AI通过XML标签告诉SFW框架要做什么，框架解析后生成此对象，
 * 然后根据 {@code type} 字段路由到对应的执行逻辑。
 * </p>
 *
 * <h3>操作类型</h3>
 * <ul>
 *   <li><b>cmd</b>     —— 执行SFW命令，如 "pl/start 5"</li>
 *   <li><b>read</b>    —— 读取文件内容</li>
 *   <li><b>readdir</b> —— 列出目录结构</li>
 *   <li><b>done</b>    —— 任务完成，终止Agent循环</li>
 * </ul>
 */
public class AgentAction {

    /** 操作类型标签 */
    private final String type;

    /** 操作的具体内容（命令文本 / 文件路径 / 完成信息） */
    private final String content;

    /**
     * 构造一个Agent操作。
     *
     * @param type    操作类型 (cmd / read / readdir / done)
     * @param content 操作内容
     */
    public AgentAction(String type, String content) {
        this.type = type;
        this.content = content;
    }

    /** @return 操作类型 */
    public String getType() {
        return type;
    }

    /** @return 操作内容 */
    public String getContent() {
        return content;
    }
}
