package sair.aiagent.util;

/**
 * 展示用的小工具函数（技能与内置实现共用，避免每个技能各复制一份导致行为漂移）。
 *
 * <p>目前只有体积格式化与下载文件名推导 —— 这两段原先写在 {@code AgentActionHandler} 里，
 * 被 download / sendfile 等多处共用。技能剥离后如果各抄一份，"1.0 KB 还是 1.0KB" 这种
 * 细节就会各说各话，所以统一放这里。</p>
 */
public final class FormatUtil {

    private FormatUtil() {}

    /** 人类可读体积：B / KB / MB / GB（保留 1~2 位小数，与原实现一致）。 */
    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * 从 URL 推导一个安全的本地文件名。
     * <p>Windows 路径分隔符、{@code ..}、控制字符全部中和；取不到名字时用 URL 哈希兜底。</p>
     */
    public static String extractFileName(String url) {
        try {
            String path = new java.net.URI(url).getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty()) {
                    try {
                        name = java.net.URLDecoder.decode(name, "UTF-8");
                    } catch (Exception ignored) {
                        // 保持原始编码
                    }
                    name = name.replaceAll("[/\\\\]", "_").replaceAll("\\.\\.", "__").replaceAll("\\x00", "");
                    name = name.trim().replaceAll("^\\.+", "_").replaceAll("\\.+$", "_");
                    if (name.isEmpty()) name = "download";
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 落到哈希兜底
        }
        return "download_" + Math.abs(url.hashCode());
    }
}
