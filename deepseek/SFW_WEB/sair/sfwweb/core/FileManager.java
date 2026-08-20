package sair.sfwweb.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import sair.sys.tools.ToolPack;

/**
 * SFW Web 文件管理器
 * 管理 SFW.jar 所在目录及其子目录下的所有文件
 * 支持浏览、增删改、上传、预览、IR执行
 */
public class FileManager {

    private static FileManager instance;
    private final File sfwRoot;

    private FileManager() {
        // SFW 根目录，直接使用 ToolPack 提供的路径（与 Pathes 一致）
        sfwRoot = new File(ToolPack.getPath());
    }

    public static synchronized FileManager getInstance() {
        if (instance == null) instance = new FileManager();
        return instance;
    }

    /** 获取 SFW 根目录 */
    public String getSfwRootPath() {
        try { return sfwRoot.getCanonicalPath(); } catch (IOException e) { return sfwRoot.getAbsolutePath(); }
    }

    /** 规范化路径，防止路径遍历攻击 */
    private File resolvePath(String relativePath) throws IOException {
        String rootCanonical = sfwRoot.getCanonicalPath();
        File resolved = new File(sfwRoot, relativePath).getCanonicalFile();
        String resolvedCanonical = resolved.getCanonicalPath();
        // 必须在 SFW 根目录之下（使用 canonical 路径确保大小写/分隔符一致）
        if (!resolvedCanonical.startsWith(rootCanonical + File.separator)
                && !resolvedCanonical.equals(rootCanonical)) {
            throw new SecurityException("Access denied: path traversal detected");
        }
        return resolved;
    }

    /** 文件/目录信息 */
    public static class FileInfo {
        public String name;
        public String path;       // relative path from SFW root
        public boolean isDir;
        public long size;
        public long lastModified;
        public String extension;
        public boolean readable;
        public boolean writable;
        public boolean executable;
        // 用于前端图标/预览判断
        public String mimeType;
        public boolean isImage;
        public boolean isMedia;
        public boolean isAudio;
        public boolean isIr;
        public boolean isText;
        public boolean isCode;

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("path", path);
            m.put("isDir", isDir);
            m.put("size", size);
            m.put("lastModified", lastModified);
            m.put("extension", extension);
            m.put("readable", readable);
            m.put("writable", writable);
            m.put("executable", executable);
            m.put("mimeType", mimeType);
            m.put("isImage", isImage);
            m.put("isMedia", isMedia);
            m.put("isAudio", isAudio);
            m.put("isIr", isIr);
            m.put("isText", isText);
            m.put("isCode", isCode);
            return m;
        }
    }

    /** 列出目录内容 */
    public List<FileInfo> listDirectory(String relativePath) throws IOException {
        File dir = resolvePath(relativePath);
        if (!dir.exists()) throw new FileNotFoundException("Directory not found: " + relativePath);
        if (!dir.isDirectory()) throw new IllegalArgumentException("Not a directory: " + relativePath);

        File[] files = dir.listFiles();
        if (files == null) return Collections.emptyList();

        List<FileInfo> result = new ArrayList<>();
        // 目录在前，文件在后，按名称排序
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File f : files) {
            // 显示所有文件（包括 .env .gitignore 等隐藏配置文件在 classifyFile 中已被标记为文本/代码）
            result.add(toFileInfo(f));
        }
        return result;
    }

    /** 读取文件内容（文本），自动检测编码 */
    public String readFile(String relativePath) throws IOException {
        File file = resolvePath(relativePath);
        if (!file.exists()) throw new FileNotFoundException("File not found: " + relativePath);
        if (file.isDirectory()) throw new IllegalArgumentException("Cannot read directory: " + relativePath);

        // 限制文件大小：最大 10MB 文本文件
        if (file.length() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("File too large (>10MB): " + relativePath);
        }

        byte[] data = Files.readAllBytes(file.toPath());
        // 自动检测编码：BOM 或 前缀分析
        String encoding = detectEncoding(data);
        return new String(data, encoding);
    }

    /** 检测文件编码 */
    private static String detectEncoding(byte[] data) {
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            return "UTF-8"; // UTF-8 BOM
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return "UTF-16BE";
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return "UTF-16LE";
        }
        // 快速检查是否为有效 UTF-8（无BOM）
        if (isValidUtf8(data)) {
            return "UTF-8";
        }
        // 默认 GBK（中文 Windows 常见编码）
        return "GBK";
    }

    /** 简易 UTF-8 校验：检查多字节序列是否合法 */
    private static boolean isValidUtf8(byte[] data) {
        int i = 0;
        while (i < data.length) {
            int b = data[i] & 0xFF;
            if (b <= 0x7F) { i++; }
            else if (b >= 0xC2 && b <= 0xDF) {
                if (i + 1 >= data.length) return false;
                if ((data[i + 1] & 0xC0) != 0x80) return false;
                i += 2;
            } else if (b >= 0xE0 && b <= 0xEF) {
                if (i + 2 >= data.length) return false;
                if ((data[i + 1] & 0xC0) != 0x80) return false;
                if ((data[i + 2] & 0xC0) != 0x80) return false;
                i += 3;
            } else if (b >= 0xF0 && b <= 0xF4) {
                if (i + 3 >= data.length) return false;
                if ((data[i + 1] & 0xC0) != 0x80) return false;
                if ((data[i + 2] & 0xC0) != 0x80) return false;
                if ((data[i + 3] & 0xC0) != 0x80) return false;
                i += 4;
            } else {
                return false; // invalid UTF-8 lead byte
            }
        }
        return true;
    }

    /** 写入文件内容（原子写入 + 锁检查在同一操作中） */
    public void writeFile(String relativePath, String content, boolean createIfMissing) throws IOException {
        File file = resolvePath(relativePath);
        if (!createIfMissing && !file.exists()) throw new FileNotFoundException("File not found: " + relativePath);
        if (file.exists() && file.isDirectory()) throw new IllegalArgumentException("Cannot write to directory: " + relativePath);

        // 确保父目录存在
        File parent = file.getParentFile();
        if (!parent.exists()) parent.mkdirs();

        // 先写到临时文件，再原子 rename，同时检测锁
        File tmpFile = new File(file.getAbsolutePath() + ".sfw_tmp_" + System.currentTimeMillis());
        try (FileOutputStream fos = new FileOutputStream(tmpFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            osw.write(content);
            osw.flush();
            fos.getFD().sync(); // 确保刷到磁盘
        } catch (IOException e) {
            tmpFile.delete();
            if (e.getMessage() != null && (e.getMessage().contains("lock") || e.getMessage().contains("denied"))) {
                throw new IOException("文件正在被其他程序占用，无法写入: " + file.getName());
            }
            throw e;
        }

        // 原子替换：如果目标被锁定，renameTo 会失败
        if (file.exists()) {
            String locked = checkFileLocked(file);
            if (locked != null) { tmpFile.delete(); throw new IOException(locked); }
        }
        if (!tmpFile.renameTo(file)) {
            tmpFile.delete();
            throw new IOException("文件正在被其他程序占用，无法写入: " + file.getName());
        }
    }

    /** 删除文件或目录 */
    public void deleteFile(String relativePath) throws IOException {
        File file = resolvePath(relativePath);
        if (!file.exists()) throw new FileNotFoundException("Not found: " + relativePath);
        if (relativePath.isEmpty() || ".".equals(relativePath)) {
            throw new IllegalArgumentException("Cannot delete root directory");
        }

        String locked = checkFileLocked(file);
        if (locked != null) throw new IOException(locked);

        if (file.isDirectory()) {
            deleteRecursive(file);
        } else {
            if (!file.delete()) {
                throw new IOException("删除失败（文件可能被占用）: " + file.getName());
            }
        }
    }

    private void deleteRecursive(File dir) throws IOException {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                String locked = checkFileLocked(child);
                if (locked != null) throw new IOException(locked);
                if (child.isDirectory()) {
                    deleteRecursive(child);
                } else {
                    if (!child.delete()) {
                        throw new IOException("删除失败（文件可能被占用）: " + child.getName());
                    }
                }
            }
        }
        if (!dir.delete()) {
            throw new IOException("删除失败（目录可能被占用）: " + dir.getName());
        }
    }

    /** 创建文件或目录 */
    public void createFile(String relativePath, boolean isDir) throws IOException {
        File file = resolvePath(relativePath);
        if (file.exists()) throw new IOException("已存在: " + relativePath);

        File parent = file.getParentFile();
        if (!parent.exists()) parent.mkdirs();

        if (isDir) {
            if (!file.mkdirs()) throw new IOException("创建目录失败: " + relativePath);
        } else {
            if (!file.createNewFile()) throw new IOException("创建文件失败: " + relativePath);
        }
    }

    /** 移动/重命名文件或目录 */
    public void moveFile(String oldRelPath, String newRelPath) throws IOException {
        File oldFile = resolvePath(oldRelPath);
        File newFile = resolvePath(newRelPath);
        if (!oldFile.exists()) throw new FileNotFoundException("Not found: " + oldRelPath);
        if (newFile.exists()) throw new IOException("目标已存在: " + newRelPath);

        String locked = checkFileLocked(oldFile);
        if (locked != null) throw new IOException(locked);

        File newParent = newFile.getParentFile();
        if (!newParent.exists()) newParent.mkdirs();

        if (!oldFile.renameTo(newFile)) {
            throw new IOException("移动/重命名失败（文件可能被占用）: " + oldFile.getName());
        }
    }

    /** 上传文件（保存上传的字节流） */
    public void uploadFile(String dirPath, String fileName, InputStream inputStream) throws IOException {
        File dir = resolvePath(dirPath);
        if (!dir.exists()) dir.mkdirs();
        if (!dir.isDirectory()) throw new IllegalArgumentException("Not a directory: " + dirPath);

        File targetFile = new File(dir, fileName);
        // 规范化校验
        targetFile = resolvePath(dirPath + File.separator + fileName);

        if (targetFile.exists()) {
            String locked = checkFileLocked(targetFile);
            if (locked != null) throw new IOException(locked);
        }

        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = inputStream.read(buffer)) != -1) {
                fos.write(buffer, 0, n);
            }
            fos.flush();
        }
    }

    /** 获取文件的 MIME 类型和分类信息 */
    private FileInfo toFileInfo(File file) {
        FileInfo info = new FileInfo();
        info.name = file.getName();
        info.isDir = file.isDirectory();
        info.size = file.length();
        info.lastModified = file.lastModified();
        info.readable = file.canRead();
        info.writable = file.canWrite();
        info.executable = file.canExecute();

        // 相对路径
        String rootPath;
        try { rootPath = sfwRoot.getCanonicalPath(); } catch (IOException e) { rootPath = sfwRoot.getAbsolutePath(); }
        String filePath;
        try { filePath = file.getCanonicalPath(); } catch (IOException e) { filePath = file.getAbsolutePath(); }
        if (filePath.startsWith(rootPath)) {
            info.path = filePath.substring(rootPath.length()).replace('\\', '/');
            if (info.path.startsWith("/")) info.path = info.path.substring(1);
        } else {
            info.path = file.getName();
        }

        // 扩展名
        String name = file.getName().toLowerCase();
        int dot = name.lastIndexOf('.');
        info.extension = (dot >= 0 && dot < name.length() - 1) ? name.substring(dot + 1) : "";

        // 分类
        classifyFile(info);

        return info;
    }

    private void classifyFile(FileInfo info) {
        String ext = info.extension;

        // IR 脚本
        info.isIr = "ir".equals(ext);

        // 图片
        info.isImage = "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext)
                || "gif".equals(ext) || "bmp".equals(ext) || "svg".equals(ext)
                || "webp".equals(ext) || "ico".equals(ext) || "tiff".equals(ext)
                || "tif".equals(ext);

        // 音频
        info.isAudio = "mp3".equals(ext) || "wav".equals(ext) || "ogg".equals(ext)
                || "flac".equals(ext) || "aac".equals(ext) || "m4a".equals(ext)
                || "wma".equals(ext) || "opus".equals(ext);

        // 视频/媒体
        info.isMedia = "mp4".equals(ext) || "webm".equals(ext) || "mkv".equals(ext)
                || "avi".equals(ext) || "mov".equals(ext) || "wmv".equals(ext)
                || "flv".equals(ext) || "m4v".equals(ext);

        // MIME 类型
        info.mimeType = getMimeByExt(ext);

        if (info.isDir) {
            info.mimeType = "inode/directory";
            return;
        }

        // 代码文件（需要高亮）
        info.isCode = "js".equals(ext) || "java".equals(ext) || "cpp".equals(ext)
                || "c".equals(ext) || "h".equals(ext) || "py".equals(ext)
                || "css".equals(ext) || "html".equals(ext) || "xml".equals(ext)
                || "json".equals(ext) || "ts".equals(ext) || "jsx".equals(ext)
                || "tsx".equals(ext) || "vue".equals(ext) || "php".equals(ext)
                || "rb".equals(ext) || "go".equals(ext) || "rs".equals(ext)
                || "swift".equals(ext) || "kt".equals(ext) || "scala".equals(ext)
                || "sh".equals(ext) || "bat".equals(ext) || "ps1".equals(ext)
                || "sql".equals(ext) || "yaml".equals(ext) || "yml".equals(ext)
                || "cs".equals(ext) || "lua".equals(ext) || "r".equals(ext)
                || "md".equals(ext) || "ir".equals(ext);

        // 文本文件（可在线编辑）
        info.isText = info.isCode
                || "txt".equals(ext) || "log".equals(ext) || "cfg".equals(ext)
                || "ini".equals(ext) || "conf".equals(ext) || "properties".equals(ext)
                || "csv".equals(ext) || "tsv".equals(ext) || "tex".equals(ext)
                || "bib".equals(ext) || "toml".equals(ext) || "gitignore".equals(ext)
                || "env".equals(ext) || "editorconfig".equals(ext)
                || (ext.isEmpty() && !info.isDir);  // 无扩展名可能是文本
    }

    private static String getMimeByExt(String ext) {
        switch (ext) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "svg": return "image/svg+xml";
            case "webp": return "image/webp";
            case "ico": return "image/x-icon";
            case "mp3": return "audio/mpeg";
            case "wav": return "audio/wav";
            case "ogg": return "audio/ogg";
            case "flac": return "audio/flac";
            case "aac": return "audio/aac";
            case "mp4": return "video/mp4";
            case "webm": return "video/webm";
            case "mkv": return "video/x-matroska";
            case "avi": return "video/x-msvideo";
            case "mov": return "video/quicktime";
            case "pdf": return "application/pdf";
            case "zip": return "application/zip";
            case "jar": return "application/java-archive";
            case "ir": return "text/plain";
            case "html": case "htm": return "text/html";
            case "css": return "text/css";
            case "js": return "application/javascript";
            case "json": return "application/json";
            case "xml": return "application/xml";
            case "txt": case "log": case "cfg": case "ini": case "conf":
            case "properties": return "text/plain";
            default: return "application/octet-stream";
        }
    }

    /** 检测文件是否被占用 */
    private String checkFileLocked(File file) {
        if (!file.exists()) return null;
        if (file.isDirectory()) {
            // 目录不检查锁定
            return null;
        }
        // 尝试以写模式打开文件来检测锁定
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            // 能打开说明没有被独占锁定
            return null;
        } catch (IOException e) {
            return "文件正在被其他程序占用: " + file.getName();
        }
    }

    /** 预览文件——返回字节数组（用于图片/媒体文件直接提供） */
    public byte[] readFileBytes(String relativePath) throws IOException {
        File file = resolvePath(relativePath);
        if (!file.exists()) throw new FileNotFoundException("File not found: " + relativePath);
        if (file.isDirectory()) throw new IllegalArgumentException("Cannot preview directory: " + relativePath);
        // 预览文件限制 50MB
        if (file.length() > 50 * 1024 * 1024) {
            throw new IllegalArgumentException("File too large for preview (>50MB): " + relativePath);
        }
        return Files.readAllBytes(file.toPath());
    }

    /** 执行 IR 文件
     * 通过 SFW 的 /ir 命令执行，IR 文件路径相对于 SFW 根目录
     * 此方法不直接执行——调用方需获取命令字符串后通过 CommandHandler 执行 */
    public void executeIrFile(String relativePath) throws IOException {
        File file = resolvePath(relativePath);
        if (!file.exists()) throw new FileNotFoundException("IR file not found: " + relativePath);
        if (!file.getName().endsWith(".ir")) {
            throw new IllegalArgumentException("Not an IR file: " + relativePath);
        }
        // 此方法保留用于验证 IR 文件是否存在及合法性
        // 实际执行由调用方通过 buildIrCommand() + CommandHandler 完成
    }

    /** 从文件相对路径构建 IR 执行命令，使用 SFW 的相对路径规则（"." 开头自动替换为 SFW 根目录） */
    public String buildIrCommand(String relativePath) throws IOException {
        // 验证路径有效（含路径穿越安全检查）
        resolvePath(relativePath);
        String sfwPath = "./" + relativePath.replace('\\', '/');
        return "/ir \"" + sfwPath + "\"";
    }
}
