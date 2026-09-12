package sair.aiagent.util;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * File reading utility - static methods collection.
 * Provides file/directory reading, encoding auto-detection (cross-platform),
 * image metadata extraction.
 *
 * <h3>Supported types</h3>
 * <ul>
 *   <li>Text files - UTF-8 / GBK / system-default auto-detection (10MB max)</li>
 *   <li>Image files - png / jpg / jpeg / gif / bmp / webp (metadata only)</li>
 *   <li>Directory - recursive listing, sorted by type then name</li>
 * </ul>
 */
public final class FileUtils {

    private static final long MAX_TEXT_SIZE = 10 * 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 50_000;
    private static final int MAX_FIND_RESULTS = 200;
    private static final int MAX_FIND_DEPTH = 20;
    /** 多线程遍历线程数：IO 密集，略高于 CPU 核心但上限 8，避免磁盘争抢 */
    private static final int FIND_THREADS = Math.min(8, Math.max(2, Runtime.getRuntime().availableProcessors() * 2));

    private static final String[] IMAGE_EXTENSIONS = {
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"
    };

    private FileUtils() {}

    /**
     * Read file content (auto-detect type).
     */
    public static String readFile(String path) {
        return readFile(path, 0, -1);
    }

    /**
     * Read file content with optional chunking (offset/limit) for large text files.
     * <p>
     * 重要：offset/limit 必须作用在<b>完整解码文本</b>上。旧实现先把内容截断到
     * {@link #MAX_OUTPUT_CHARS}(50000) 再切片，导致任何超过 5 万字符的文件：
     * {@code offset=60000} 会被 clamp 到「截断后缀」内部，{@code start >= end} 后
     * 返回一段截断提示文字甚至空串——AI 以为文件读完了/内容为空（而且工具返回被判为成功），
     * 于是<b>永远无法读取第 5 万字符之后的内容</b>。
     * </p>
     * @param offset 起始字符偏移（0=从头读）
     * @param limit  最多读取的字符数（-1 或 0=读全部）
     */
    public static String readFile(String path, int offset, int limit) {
        if (path == null || path.trim().isEmpty()) {
            return "Path is empty.";
        }

        File file = new File(path);
        if (!file.exists())  return "File not found: " + path;
        if (file.isDirectory()) return "Path is a directory: " + path
                + "\nTip: use readdir command to list directory.";

        if (isImageFile(path)) {
            return readImageInfo(file);
        }

        String content = readTextFile(file);
        if (content == null || content.startsWith("File too large") || content.startsWith("Read file error")) {
            return content;
        }
        int total = content.length();
        if (offset > 0 || limit > 0) {
            int start = Math.min(Math.max(offset, 0), total);
            int end = (limit > 0) ? Math.min(start + limit, total) : total;
            if (start >= end) {
                return "[已到文件末尾] 请求范围 offset=" + offset + ", limit=" + limit
                        + "，但文件总长度仅 " + total + " 字符。";
            }
            String slice = content.substring(start, end);
            // 单次返回上限：超出时给出「下次 offset」，让分段读取可以真正接上
            if (slice.length() > MAX_OUTPUT_CHARS) {
                int nextOffset = start + MAX_OUTPUT_CHARS;
                return slice.substring(0, MAX_OUTPUT_CHARS)
                     + "\n\n... (本次返回截断于 " + MAX_OUTPUT_CHARS + " 字符；文件总长 " + total
                     + " 字符，续读请用 offset=" + nextOffset + ")";
            }
            if (end < total) {
                return slice + "\n\n... (已读到 " + end + "/" + total
                     + " 字符，续读请用 offset=" + end + ")";
            }
            return slice;
        }
        // 未指定 offset/limit：整文件返回，超限时明确告知总长度与续读方式
        if (total > MAX_OUTPUT_CHARS) {
            return content.substring(0, MAX_OUTPUT_CHARS)
                 + "\n\n... (truncated to " + MAX_OUTPUT_CHARS + " chars; 文件总长 " + total
                 + " 字符，续读请用 offset=" + MAX_OUTPUT_CHARS + ")";
        }
        return content;
    }

    /**
     * List directory contents.
     */
    public static String readDir(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "Path is empty.";
        }

        File dir = new File(path);
        if (!dir.exists())       return "Directory not found: " + path;
        if (!dir.isDirectory())  return "Path is not a directory: " + path;

        File[] files = dir.listFiles();
        if (files == null) return "Cannot read directory: " + path;

        // Sort: directories first, then by name
        Arrays.sort(files, new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        StringBuilder sb = new StringBuilder();
        sb.append("Directory: ").append(dir.getAbsolutePath()).append("\n");
        sb.append("------------------------------\n");

        int fileCount = 0, dirCount = 0;
        for (File f : files) {
            if (f.isDirectory()) {
                sb.append("  [DIR]  ").append(f.getName())
                  .append(File.separator).append("\n");
                dirCount++;
            } else {
                sb.append("  [FILE] ").append(f.getName())
                  .append("  (").append(formatSize(f.length())).append(")\n");
                fileCount++;
            }
        }
        if (files.length == 0) {
            sb.append("  (empty)\n");
        }
        sb.append("------------------------------\n");
        sb.append("Total: ").append(dirCount).append(" dirs, ")
          .append(fileCount).append(" files\n");
        return sb.toString();
    }

    /**
     * 递归查找目录下文件名匹配关键词的文件/目录（快速定位，避免逐层 readdir）。
     * <p>多线程并行遍历不同目录分支（IO 密集场景下并行读盘提速），关键词为子串匹配（忽略大小写），
     * 空则返回全部；最多返回 {@link #MAX_FIND_RESULTS} 条，深度上限 {@link #MAX_FIND_DEPTH}。</p>
     */
    public static String findFiles(String dirPath, String keyword) {
        if (dirPath == null || dirPath.trim().isEmpty()) {
            return "Path is empty.";
        }
        File dir = new File(dirPath);
        if (!dir.exists()) return "Directory not found: " + dirPath;
        if (!dir.isDirectory()) return "Path is not a directory: " + dirPath;

        final String kw = (keyword == null) ? "" : keyword.trim().toLowerCase();
        final java.util.List<File> matched = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(FIND_THREADS);
        java.util.concurrent.Phaser phaser = new java.util.concurrent.Phaser(1);
        try {
            walkConcurrent(pool, phaser, dir, kw, matched, 0);
            phaser.arriveAndAwaitAdvance();
        } catch (Exception ignored) {
            // 遍历异常不阻断已收集结果
        } finally {
            pool.shutdown();
        }

        // 快照 + 按路径排序，保证输出稳定
        java.util.List<File> snapshot;
        synchronized (matched) {
            snapshot = new java.util.ArrayList<>(matched);
        }
        snapshot.sort(new java.util.Comparator<File>() {
            public int compare(File a, File b) {
                return a.getAbsolutePath().compareToIgnoreCase(b.getAbsolutePath());
            }
        });
        int total = snapshot.size();
        java.util.List<File> shown = total > MAX_FIND_RESULTS ? snapshot.subList(0, MAX_FIND_RESULTS) : snapshot;

        StringBuilder sb = new StringBuilder();
        sb.append("Find in: ").append(dir.getAbsolutePath()).append("\n");
        sb.append("Keyword: ").append(kw.isEmpty() ? "(all)" : kw).append("\n");
        sb.append("------------------------------\n");
        if (shown.isEmpty()) {
            sb.append("  (no match)\n");
        } else {
            for (File f : shown) {
                sb.append("  ").append(f.getAbsolutePath());
                if (f.isFile()) sb.append("  (").append(formatSize(f.length())).append(")");
                sb.append("\n");
            }
            if (total > MAX_FIND_RESULTS) {
                sb.append("  ... (truncated, more results)\n");
            }
        }
        sb.append("------------------------------\n");
        sb.append("Total: ").append(total).append(" matches\n");
        return truncateIfNeeded(sb.toString());
    }

    private static void walkConcurrent(java.util.concurrent.ExecutorService pool,
                                       java.util.concurrent.Phaser phaser,
                                       File dir, String kw, java.util.List<File> matched, int depth) {
        if (depth > MAX_FIND_DEPTH || matched.size() >= MAX_FIND_RESULTS) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (matched.size() >= MAX_FIND_RESULTS) return;
            if (kw.isEmpty() || f.getName().toLowerCase().contains(kw)) {
                matched.add(f);
            }
            if (f.isDirectory()) {
                phaser.register();
                pool.submit(() -> {
                    try {
                        walkConcurrent(pool, phaser, f, kw, matched, depth + 1);
                    } finally {
                        phaser.arriveAndDeregister();
                    }
                });
            }
        }
    }

    // === Private implementation ===

    private static boolean isImageFile(String path) {
        String lower = path.toLowerCase();
        for (String ext : IMAGE_EXTENSIONS) {
            if (lower.endsWith(ext)) return true;
        }
        return false;
    }

    /**
     * Read text file with cross-platform encoding detection.
     * <p>返回<b>完整</b>解码文本，<b>不截断</b>：截断统一由
     * {@link #readFile(String, int, int)} 负责，它必须拿到完整文本，
     * offset/limit 才能定位到第 5 万字符之后的内容。</p>
     * Strategy: UTF-8 -> system default -> GBK -> UTF-8 (force).
     * On Windows, system default is typically GBK/CP936. On Linux/Mac it's UTF-8.
     */
    private static String readTextFile(File file) {
        long fileSize = file.length();
        if (fileSize > MAX_TEXT_SIZE) {
            return "File too large (" + (fileSize / 1024 / 1024)
                    + "MB), max is 10MB.";
        }
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(file.getAbsolutePath()));

            // Try UTF-8 first
            String text = tryDecode(bytes, StandardCharsets.UTF_8);
            if (text != null) return text;

            // Try system default charset (cross-platform: GBK on Win, UTF-8 on Unix)
            text = tryDecode(bytes, Charset.defaultCharset());
            if (text != null) return text;

            // Try GBK explicitly (works cross-platform as fallback)
            text = tryDecode(bytes, Charset.forName("GBK"));
            if (text != null) return text;

            // All failed: force UTF-8
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Read file error: " + e.getMessage();
        }
    }

    /**
     * Try to decode bytes with given charset. Returns null if too many
     * replacement characters (indicates wrong encoding).
     */
    private static String tryDecode(byte[] bytes, Charset charset) {
        try {
            String text = new String(bytes, charset);
            // 全文检测替换字符比例（避免纯ASCII前段误判）
            int replacementCount = 0;
            int len = text.length();
            for (int i = 0; i < len; i++) {
                if (text.charAt(i) == '\uFFFD') replacementCount++;
            }
            if (len > 0 && (double) replacementCount / len > 0.05) {
                return null;
            }
            return text;
        } catch (Exception e) {
            return null;
        }
    }

    /** Read image metadata */
    private static String readImageInfo(File file) {
        try {
            BufferedImage img = ImageIO.read(file);
            if (img == null) return "Cannot parse image: " + file.getAbsolutePath();
            long fileSize = file.length();
            return "[Image Info]\n"
                 + "  Path:     " + file.getAbsolutePath() + "\n"
                 + "  Size:     " + img.getWidth() + "x" + img.getHeight() + " px\n"
                 + "  FileSize: " + formatSize(fileSize) + "\n"
                 + "  Tip: Use Vision-capable model to analyze this image.";
        } catch (Exception e) {
            return "Read image error: " + e.getMessage();
        }
    }

    /** Truncate if too long（仅用于未显式指定 offset/limit 的整文件读取路径） */
    private static String truncateIfNeeded(String content) {
        if (content.length() > MAX_OUTPUT_CHARS) {
            return content.substring(0, MAX_OUTPUT_CHARS)
                 + "\n\n... (truncated to " + MAX_OUTPUT_CHARS + " chars)";
        }
        return content;
    }

    /** Format file size (cross-platform: no locale-dependent formatting) */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.1f GB", mb / 1024.0);
    }
}
