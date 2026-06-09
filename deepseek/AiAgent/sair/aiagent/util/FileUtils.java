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

    private static final String[] IMAGE_EXTENSIONS = {
        ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".webp"
    };

    private FileUtils() {}

    /**
     * Read file content (auto-detect type).
     */
    public static String readFile(String path) {
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

        return readTextFile(file);
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
            if (text != null) return truncateIfNeeded(text);

            // Try system default charset (cross-platform: GBK on Win, UTF-8 on Unix)
            text = tryDecode(bytes, Charset.defaultCharset());
            if (text != null) return truncateIfNeeded(text);

            // Try GBK explicitly (works cross-platform as fallback)
            text = tryDecode(bytes, Charset.forName("GBK"));
            if (text != null) return truncateIfNeeded(text);

            // All failed: force UTF-8
            return truncateIfNeeded(new String(bytes, StandardCharsets.UTF_8));
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

    /** Truncate if too long */
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
