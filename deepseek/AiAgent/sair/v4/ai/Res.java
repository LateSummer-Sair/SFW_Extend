package sair.v4.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import sair.v4.kit.J;
import sair.v4.kit.Str;

/**
 * 一次模型调用的结果（字段全 public，便于各模块直接读）。
 *
 * <p>约定：<b>失败绝不抛异常</b> —— {@link #error} 非空即代表失败，{@link #ok()} 为 false。
 * 成功路径下 {@link #content}/{@link #reasoning} 至少有一个可能为空串，但绝不为 null。</p>
 */
public final class Res {

    /** 正文（流式时为所有 content 分片拼接的结果）。 */
    public String content = "";
    /** 思维链（deepseek-reasoner 的 reasoning_content，流式时为分片拼接结果）。 */
    public String reasoning = "";
    /** 工具调用；无调用时为 null。元素形如
     *  {@code {"id":"call_x","type":"function","function":{"name":"..","arguments":"<JSON 字符串>"}}}。 */
    public JsonArray toolCalls;
    /** 结束原因：stop / length / tool_calls / content_filter / insufficient_system_resource。 */
    public String finishReason = "";
    /** 原样带上的 usage（流式可能为 null）。 */
    public JsonObject usage;
    /** 本次调用耗时（毫秒，含流式全程）。 */
    public long ms;
    /** 失败原因；为空表示成功。 */
    public String error = "";
    /** 服务端实际使用的模型名。 */
    public String modelUsed = "";

    /** 是否成功（error 为空）。 */
    public boolean ok() {
        return error == null || error.isEmpty();
    }

    /** 是否有工具调用。 */
    public boolean hasTools() {
        return toolCalls != null && toolCalls.size() > 0;
    }

    /** 正文的 null 安全读取。 */
    public String text() {
        return content == null ? "" : content;
    }

    /** 造一个失败结果。 */
    public static Res fail(String message) {
        Res r = new Res();
        r.error = (message == null || message.isEmpty()) ? "未知错误" : message;
        return r;
    }

    /**
     * 一行"模型调用事件"（控制台默认只留这一类行里的模型侧事实）。
     *
     * <p>只给主人要的四件事：<b>走的哪个模型 / 缓存命中多少 / 用时多少 / 这次结果规模</b>。</p>
     */
    public String callEvent() {
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(ok() ? "ok" : "FAIL");
            sb.append(" model=").append(Str.blank(modelUsed) ? "?" : modelUsed);
            if (!ok()) {
                sb.append(" error=").append(Str.cut(Str.oneLine(error), 160));
                sb.append(" ms=").append(ms);
                return sb.toString();
            }
            long hit = J.l(usage, "prompt_cache_hit_tokens", -1L);
            long miss = J.l(usage, "prompt_cache_miss_tokens", -1L);
            if (hit < 0L) {
                // 新版用量结构：prompt_tokens_details.cached_tokens
                com.google.gson.JsonObject det = usage == null ? null : J.sub(usage, "prompt_tokens_details");
                hit = det == null ? -1L : J.l(det, "cached_tokens", -1L);
            }
            if (hit >= 0L) {
                long total = hit + (miss < 0L ? 0L : miss);
                sb.append(" cache=").append(total > 0L ? (hit * 100L / total) + "%" : "?")
                  .append("(hit=").append(hit);
                if (miss >= 0L) sb.append(" miss=").append(miss);
                sb.append(")");
            }
            sb.append(" ms=").append(ms);
            if (hasTools()) sb.append(" tools=").append(toolCalls.size());
            sb.append(" in=").append(J.l(usage, "prompt_tokens", 0L))
              .append(" out=").append(J.l(usage, "completion_tokens", 0L))
              .append(" total=").append(J.l(usage, "total_tokens", 0L));
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    /** 单行摘要（日志/控制台用）；任何字段异常都不会抛出。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        try {
            if (!ok()) {
                return "FAIL " + Str.cut(Str.oneLine(error), 200);
            }
            sb.append("ok");
            if (Str.has(modelUsed)) sb.append(" model=").append(modelUsed);
            if (Str.has(finishReason)) sb.append(" finish=").append(finishReason);
            sb.append(" ms=").append(ms);
            sb.append(" content=").append(content == null ? 0 : content.length()).append("c");
            if (Str.has(reasoning)) sb.append(" reasoning=").append(reasoning.length()).append("c");
            if (hasTools()) sb.append(" tools=").append(toolCalls.size());
            if (usage != null) sb.append(" tokens=").append(J.l(usage, "total_tokens", 0L));
            if (Str.has(content)) sb.append(" «").append(Str.cut(Str.oneLine(content), 60)).append("»");
        } catch (Throwable ignored) {
            // 摘要永远不能成为故障点
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "Res{" + summary() + "}";
    }
}
