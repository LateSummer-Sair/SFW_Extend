package sair.v4.qq;

import java.util.regex.Pattern;

/**
 * 模型输出里的「控制标记」清理（V3 {@code util/MarkerTags} 的白名单口径）。
 *
 * <p>约定用 {@code <quote>} / {@code <split>} / {@code <at>} / {@code <reply>} 这类标签传递结构化控制信息；
 * 这些标记必须在<b>发送前</b>剥掉，否则用户看到的是一串裸标签，写进聊天记忆里也会白白烧 token。</p>
 *
 * <h3>为什么不直接 {@code replaceAll("<[^>]+>", "")}</h3>
 * <p>那是"通配剥离一切尖括号"，会连带吞掉回复里完全正常的 <code>&lt;div class="x"&gt;</code>、
 * {@code List<String>}、{@code x < 0 > y} —— 而且被吞掉的那段还会当成"干净文本"写进记忆，
 * 一次清理既改坏回复又污染记忆。所以这里<b>只剥白名单里的标签</b>。</p>
 *
 * <p>V4 的工具都是 function calling，不靠标签执行动作；这份表里的动作标签（{@code <sendimage>} 等）
 * 只是安全网：模型偶尔会照着老习惯把标签写进回复里。</p>
 */
public final class MarkerTags {

    private MarkerTags() {
    }

    /** 控制标记白名单（标签名精确匹配；允许带属性，如 {@code <split/>}、{@code <at qq="1">}）。 */
    private static final Pattern MARKER_TAG_PATTERN = Pattern.compile(
            "</?(?:quote|split|at|reply|favor"
            + "|br|sendimage|sendrecord|sendfile|schedule|note|searchnote|stop|editprompt"
            + "|cmd|sys|eval|evaljs|download|balance|weather|skillextract"
            + "|batchrename|batchconvert|readfile|readdir|findfile|web|search|remember)\\b[^>]*>",
            Pattern.CASE_INSENSITIVE);

    /** 剥离控制标记（只有 trim，不做别的改动；null → 空串）。 */
    public static String strip(String text) {
        if (text == null) return "";
        if (text.indexOf('<') < 0) return text.trim();
        return MARKER_TAG_PATTERN.matcher(text).replaceAll("").trim();
    }

    /** 含有控制标记吗（不想改文本、只想判断时用）。 */
    public static boolean has(String text) {
        return text != null && text.indexOf('<') >= 0 && MARKER_TAG_PATTERN.matcher(text).find();
    }
}
