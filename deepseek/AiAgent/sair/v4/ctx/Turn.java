package sair.v4.ctx;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import sair.v4.auth.Caller;
import sair.v4.kit.J;

/**
 * 一轮调用的上下文（基板⑤）：身份 + 会话 + 消息流 + 本轮可变状态。
 * <p>消息数组就是最终发给模型的 {@code messages}；工具闭环的每一步都往这里追加，
 * 因此"上下文注入"可以在任意一轮、任意时刻发生（段落来源见 ctx 包与 {@code Inject}）。</p>
 */
public final class Turn {

    private final Caller caller;
    private final String session;
    private final JsonArray messages = new JsonArray();
    private final JsonObject state = new JsonObject();
    private final Sink sink;
    private final long startedAt = System.currentTimeMillis();

    private volatile boolean stopped = false;
    private volatile String lastText = "";
    private int rounds = 0;
    /** 内部回合（子 Agent / 后台收尾）：正文一律不外发；见 {@link #say(String)}。 */
    private volatile boolean internal = false;
    /** 本回合有没有"真的对外说过话"（调用了发送类工具）：用来避免"再说一遍"。 */
    private volatile boolean spoke = false;

    public Turn(Caller caller, Sink sink) {
        this.caller = caller;
        this.session = caller == null ? "console" : caller.session();
        this.sink = sink;
    }

    public Caller caller() { return caller; }

    public String session() { return session; }

    public Sink sink() { return sink; }

    public JsonArray messages() { return messages; }

    public JsonObject state() { return state; }

    public long startedAt() { return startedAt; }

    public long elapsedMs() { return System.currentTimeMillis() - startedAt; }

    public int rounds() { return rounds; }

    public void round() { rounds++; }

    public String lastText() { return lastText; }

    public void lastText(String t) { lastText = t == null ? "" : t; }

    public boolean stopped() { return stopped; }

    public void stop() { stopped = true; }

    // ---------- 消息追加 ----------

    public void add(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content == null ? "" : content);
        messages.add(m);
    }

    public void addSystem(String text) { add("system", text); }

    public void addUser(String text) { add("user", text); }

    public void addAssistant(String text) { add("assistant", text); }

    public void addMessage(JsonObject raw) { if (raw != null) messages.add(raw); }

    public void addTool(String toolCallId, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", "tool");
        m.addProperty("tool_call_id", toolCallId == null ? "" : toolCallId);
        m.addProperty("content", content == null ? "" : content);
        messages.add(m);
    }

    // ---------- 状态 ----------

    public void put(String k, Object v) { J.put(state, k, v); }

    public String get(String k, String def) { return J.s(state, k, def); }

    /**
     * 子 Agent 身份标记（{@code Agent.execSub} 派活时由<b>基板自己</b>写：{@code put("subagent", label)}）。
     * <p>空串 = 这是主 Agent 的回合。</p>
     */
    public String subagent() { return get("subagent", ""); }

    /**
     * 这一轮是不是<b>子 Agent</b> —— 判据只有这一条：{@code Turn} 上有没有基板自己写的
     * {@code subagent} 标记（<b>不靠技能/提示词自称</b>）。
     * <p>两个消费者：{@code Loop.run} 选轮次上限的键（主 {@code agentMaxRounds} /
     * 子 {@code subagentMaxRounds}）、{@code Builtins} 的 {@code agent} 工具禁止嵌套。</p>
     */
    public boolean isSubagent() {
        String s = subagent();
        return s != null && s.trim().length() > 0;
    }

    /** 输出到当前会话。 */
    public void say(String text) {
        if (text == null || text.isEmpty()) return;
        // 内部回合（子 Agent / 后台收尾）不对外说话：这类回合的正文是"Agent 之间的交流"，
        // 只进控制台（say 的调用方看不到落点，所以这里统一拦，别让任何一条路漏给用户）。
        if (internal || sink == null) return;
        sink.say(text);
    }

    /** 过程信息（只进控制台）。 */
    public void notice(String text) {
        if (sink != null) sink.notice(text);
    }

    // ---------- 内外部边界 ----------

    /** 本回合是不是<b>内部回合</b>（子 Agent / 后台任务收尾）——内部回合的正文一律不外发。 */
    public boolean internal() { return internal; }

    /** 标记为内部回合。 */
    public void markInternal() { this.internal = true; }

    /** 本回合有没有真的对外说过话（调用过发送类工具）。 */
    public boolean spoke() { return spoke; }

    /** 记下"这个回合对外说过话"（由 {@code Loop} 在发送类工具成功后调用）。 */
    public void markSpoke() { this.spoke = true; }
}
