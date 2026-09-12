package sair.aiagent.util;

import java.util.regex.Pattern;

/**
 * 模型输出中的「控制标记」清理工具。
 * <p>
 * 系统约定用 {@code <quote>} / {@code <split>} / {@code <at>} / {@code <reply>} 这类标签
 * 传递结构化控制信息，这些标记必须在发送前剥离，否则会原样出现在用户看到的回复里，
 * 也会污染写进聊天记忆的文本。
 * </p>
 *
 * <h3>为什么不能写 {@code replaceAll("&lt;[^&gt;]+&gt;", "")}</h3>
 * <p>
 * 那是「通配剥离一切尖括号内容」，会连带吞掉 AI 回复里完全正常的：
 * </p>
 * <ul>
 *   <li>HTML/XML 片段：{@code <div class="x">}、{@code <br>}</li>
 *   <li>泛型与集合：{@code List<String>}、{@code Map<K, V>}</li>
 *   <li>比较表达式：{@code x < 0 > y}、{@code <3}</li>
 * </ul>
 * <p>
 * 更严重的是：被吞掉的那段文本还会被当成「已清理的干净文本」写入聊天记忆，
 * 于是回复被改坏的同时，记忆也被永久污染。因此这里只剥离白名单标记。
 * </p>
 */
public final class MarkerTags {

    private MarkerTags() {}

    /**
     * 需要剥离的控制标记（标签名白名单）。
     * <p>
     * 前四个是 QQ 通道真实使用的约定标记；其余是<b>本地 console 链路</b>的动作标签
     * （由 {@code TagExecutor}/{@code AgentActionHandler} 执行）。QQ 通道没有标签执行器，
     * 但模型偶尔会照着历史习惯或本地链路的知识把标签写进 QQ 回复里 —— 一旦漏出去，
     * 用户看到的是一串裸标签。这里作为安全网统一剥掉。
     * </p>
     */
    private static final Pattern MARKER_TAG_PATTERN = Pattern.compile(
            "</?(?:quote|split|at|reply"
            + "|br|sendimage|sendrecord|sendfile|schedule|note|searchnote|stop|editprompt|superise"
            + "|cmd|sys|eval|evaljs|download|balance|weather|skillextract"
            + "|batchrename|batchconvert|readfile|readdir|findfile|web|search|remember)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);

    /** 剥离控制标记（不做 trim 以外的其它改动；null → 空串）。 */
    public static String strip(String text) {
        if (text == null) return "";
        if (text.indexOf('<') < 0) return text.trim();
        return MARKER_TAG_PATTERN.matcher(text).replaceAll("").trim();
    }
}
