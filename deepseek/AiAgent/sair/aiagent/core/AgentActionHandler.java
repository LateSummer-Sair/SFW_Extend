package sair.aiagent.core;

import java.awt.Color;
import java.io.File;
import java.lang.reflect.InvocationTargetException;

import javax.swing.SwingUtilities;

import sair.FCM;
import sair.aiagent.model.AgentAction;
import sair.aiagent.model.StickerEntry;
import sair.aiagent.util.EdtUtils;
import sair.aiagent.util.FileUtils;
import sair.aiagent.util.WeatherTool;
import sair.aiagent.util.WebFetcher;
import sair.aiagent.util.SearchTool;
import sair.aiagent.ui.SurpriseWindow;
import sair.aiagent.ui.SysConsolePanel;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.user.Activity;

public class AgentActionHandler {

    private static final Color C_TOOL = new Color(255, 200, 100);

    private final ConfirmationGate gate;
    private final DeepSeekClient client;
    private final DynamicCodeEngine codeEngine;
    private final TagExecutor tagExecutor;
    private final Activity selfActivity;

    private volatile JournalManager journal;
    private volatile StickerManager stickerManager;
    private volatile EmotionManager emotionManager;
    private volatile MemoryManager memoryManager;
    private volatile java.util.function.Consumer<String> qqExecsCallback;
    private volatile AgentExecutor parent;

    AgentActionHandler(ConfirmationGate gate, DeepSeekClient client,
                       DynamicCodeEngine codeEngine, TagExecutor tagExecutor,
                       Activity selfActivity) {
        this.gate = gate;
        this.client = client;
        this.codeEngine = codeEngine;
        this.tagExecutor = tagExecutor;
        this.selfActivity = selfActivity;
    }

    void setParent(AgentExecutor parent) { this.parent = parent; }
    void setJournal(JournalManager journal) { this.journal = journal; }
    void setStickerManager(StickerManager sm) { this.stickerManager = sm; }
    void setEmotionManager(EmotionManager em) { this.emotionManager = em; }
    void setMemoryManager(MemoryManager mm) { this.memoryManager = mm; }
    void setQqExecsCallback(java.util.function.Consumer<String> cb) { this.qqExecsCallback = cb; }

    /** 暴露动态代码引擎（供 ToolDispatcher 创建 SkillCodeRunner）。 */
    DynamicCodeEngine getCodeEngine() { return codeEngine; }

    /** 暴露自身 Activity（供 ToolDispatcher 获取数据目录）。 */
    Activity getSelfActivity() { return selfActivity; }

    /** 暴露 DeepSeekClient（供 ToolDispatcher 的 vision 工具调用视觉模型）。 */
    public DeepSeekClient getClient() { return client; }

    String executeAction(AgentAction action) {
        String type = action.getType();
        String content = action.getContent();
        String result;
        switch (type) {
            case "cmd":      result = executeCmd(content); break;
            case "readfile": result = executeReadFile(content); break;
            case "readdir":  result = executeReadDir(content); break;
            case "findfile": result = executeFindFile(content); break;
            case "sys":      result = executeSys(content); break;
            case "evaljs":   result = executeEvalJs(content); break;
            case "eval":     result = executeEval(content); break;
            case "web":      result = executeWeb(content); break;
            case "search":   result = executeSearch(content); break;
            case "remember": result = executeRemember(content); break;
            case "download": result = executeDownload(content); break;
            case "superise": result = executeSurprise(content); break;
            case "editprompt": result = executeEditPrompt(content); break;
            case "stop":      result = executeStop(); break;
            case "sendimage": result = executeSendImage(content); break;
            case "sendrecord":result = executeSendRecord(content); break;
            case "sendfile":      result = executeSendFile(content); break;
            case "schedule":      result = tagExecutor.executeSchedule(content); break;
            case "note":         result = tagExecutor.executeNote(content); break;
            case "searchnote":   result = tagExecutor.executeSearchNote(content); break;
            case "batchrename":  result = tagExecutor.executeBatchRename(content); break;
            case "batchconvert": result = tagExecutor.executeBatchConvert(content); break;
            case "balance":     result = executeBalance(); break;
            case "weather":     result = executeWeather(content); break;
            case "skillextract": result = parent != null ? parent.executeSkillExtract(content) : "no parent"; break;
            default:         result = "未知操作: " + type; break;
        }
        if (journal != null) {
            journal.addAgentAction(type, content, result);
        }
        return result;
    }

    String executeCmd(String command) {
        if (!gate.await("cmd", "执行 SFW命令: " + command)) return "SFW命令被拒绝。";
        EdtUtils.println(C_TOOL, "\n  > 执行 SFW命令: " + command);
        final String[] output = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    String before = SairCons.getConsoleText();
                    SairCons.runner(false, command);
                    String after = SairCons.getConsoleText();
                    output[0] = extractDiff(before, after);
                } catch (Exception e) { output[0] = "ERROR: " + e.getMessage(); }
            });
        } catch (InvocationTargetException e) { return "命令错误: " + e.getCause().getMessage();
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return "命令中断。"; }
        String result = output[0];
        if (result == null || result.isEmpty() || result.equals("(无输出)")) return "命令 [" + command + "] 已执行（无文本输出）";
        return "命令 [" + command + "] 结果:\n" + result;
    }

    String executeReadFile(String path) {
        if (!gate.await("readfile", "读取文件: " + path)) return "读取文件被拒绝。";
        EdtUtils.println(C_TOOL, "\n  > 读取: " + path);
        return "文件 [" + path + "]:\n" + FileUtils.readFile(path);
    }

    String executeReadDir(String path) {
        if (!gate.await("readdir", "列出目录: " + path)) return "列出目录被拒绝。";
        EdtUtils.println(C_TOOL, "\n  > 列出目录: " + path);
        return "目录 [" + path + "]:\n" + FileUtils.readDir(path);
    }

    String executeFindFile(String content) {
        // content 格式：path 或 path|keyword
        String path = content;
        String keyword = "";
        if (content != null) {
            int bar = content.indexOf('|');
            if (bar >= 0) {
                path = content.substring(0, bar).trim();
                keyword = content.substring(bar + 1).trim();
            }
        }
        if (!gate.await("findfile", "查找文件: " + path)) return "查找文件被拒绝。";
        EdtUtils.println(C_TOOL, "\n  > 查找文件: " + path + (keyword.isEmpty() ? "" : " (关键词:" + keyword + ")"));
        return FileUtils.findFiles(path, keyword);
    }

    String executeSys(String command) {
        if (!gate.await("sys", "执行系统命令: " + command)) return "系统命令被拒绝。";
        EdtUtils.println(C_TOOL, "\n  > 系统命令: " + command);
        final SysConsolePanel panel = new SysConsolePanel(command);
        SwingUtilities.invokeLater(() -> ConsFrame.printComponent(panel));
        final StringBuilder result = new StringBuilder();
        final Object lock = new Object();
        new Thread(() -> {
            try {
                SysConsoleExecutor.executeWithListener(command, new SysConsoleExecutor.OutputListener() {
                    public void onStart() {}
                    public void onLine(String line) { panel.appendLine(line); }
                    public void onFinish(String fullOutput) {
                        panel.setFinished();
                        synchronized (lock) { result.append(fullOutput); lock.notify(); }
                    }
                    public void onError(String message) {
                        panel.setError(message);
                        synchronized (lock) { result.append("ERROR: ").append(message); lock.notify(); }
                    }
                });
            } finally {
                synchronized (lock) { if (result.length() == 0) result.append("ERROR: 命令执行线程异常退出"); lock.notify(); }
            }
        }, "AiAgent-AgentSys").start();
        synchronized (lock) {
            try { lock.wait(35_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return "系统命令中断。"; }
        }
        String output = result.toString();
        if (output.startsWith("ERROR: ")) return "系统命令 [" + command + "] 错误: " + output.substring(7);
        return "系统命令 [" + command + "] 结果:\n" + output;
    }

    public String executeEvalJs(String code) {
        if (!gate.await("evaljs", "执行 JavaScript: " + (code.length() > 80 ? code.substring(0, 80) + "..." : code))) return "JS 执行被拒绝。";
        EdtUtils.println(new Color(100, 200, 255), "\n  [JS执行] " + (code.length() > 60 ? code.substring(0, 60) + "..." : code));
        if (codeEngine == null) return "JS引擎未初始化。";
        return "========== JS 执行结果 ==========\n" + codeEngine.evalJS(code);
    }

    String executeEval(String code) {
        String display = code.length() > 60 ? code.substring(0, 60) + "..." : code;
        if (!gate.await("eval", "编译并执行 Java 代码: " + display)) return "Java 编译执行被拒绝。";
        EdtUtils.println(new Color(100, 255, 200), "\n  [Java编译] " + display);
        if (codeEngine == null) return "代码引擎未初始化。";
        if (!codeEngine.isCompilerAvailable()) return "Java编译器不可用：当前JRE环境，需JDK。";
        String objName = "obj_" + System.currentTimeMillis() % 100000;
        String result = codeEngine.compileAndInstantiate(code, objName);
        String compileInfo = codeEngine.getLastCompilerMessage();
        if (result.startsWith("编译失败") || result.startsWith("无法从源码中提取类名")) return "========== 动态注入结果 ==========\n【编译】" + result;
        return "========== 动态注入结果 ==========\n【编译】" + (compileInfo != null && !compileInfo.isEmpty() ? compileInfo : "编译通过") + "\n【执行】" + result;
    }

    public String executeWeb(String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        try {
            java.net.URI uri = new java.net.URI(url);
            if (isInternalHost(uri.getHost())) {
                EdtUtils.println(FCM.Error_Color, "\n  [Web] 拒绝内网地址: " + uri.getHost());
                return "Web GET [" + url + "] 被拒绝: 禁止访问内网地址 (" + uri.getHost() + ")";
            }
        } catch (Exception e) { return "Web GET [" + url + "] 错误: URL格式无效 - " + e.getMessage(); }
        if (!gate.await("web", "联网获取: " + url)) return "Web 请求被拒绝。";
        EdtUtils.println(new Color(100, 200, 255), "\n  [Web] GET " + url);

        WebFetcher.FetchResult r = WebFetcher.fetch(url);
        if (r.success) {
            String text = (r.text == null || r.text.trim().isEmpty()) ? "(空正文)" : r.text;
            return "Web GET [" + r.finalUrl + "] (HTTP " + r.status + ", 编码 " + r.charset + "):\n" + text;
        }
        return "Web GET [" + r.finalUrl + "] 失败: " + (r.error != null ? r.error : "HTTP " + r.status);
    }

    String executeSearch(String query) {
        if (query == null || query.trim().isEmpty()) return "[search] 请提供搜索关键词";
        if (!gate.await("search", "联网搜索: " + query)) return "搜索被拒绝。";
        EdtUtils.println(new Color(100, 200, 255), "\n  [搜索] " + query);
        return SearchTool.search(query);
    }

    String executeRemember(String content) {
        if (content == null || content.trim().isEmpty()) return "记忆内容为空，未记录。";
        if (memoryManager == null) return "记忆管理器未初始化，无法记录。";
        sair.aiagent.model.MemoryEntry entry = memoryManager.add(content.trim());
        if (entry != null) {
            EdtUtils.println(new Color(255, 220, 130), "\n  [记忆] 已记录 [" + entry.getId() + "]: " + entry.getContent());
            return "记忆已记录 [" + entry.getId() + "]: " + entry.getContent();
        }
        return "记忆记录失败。";
    }

    String executeDownload(String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return "下载地址无效: " + url;
        try {
            java.net.URI uri = new java.net.URI(url);
            if (isInternalHost(uri.getHost())) {
                EdtUtils.println(FCM.Error_Color, "\n  [下载] 拒绝内网地址: " + uri.getHost());
                return "下载 [" + url + "] 被拒绝: 禁止访问内网地址 (" + uri.getHost() + ")";
            }
        } catch (Exception e) { return "下载 [" + url + "] 错误: URL格式无效 - " + e.getMessage(); }
        if (!gate.await("download", "下载文件: " + url)) return "下载被拒绝。";
        String fileName = extractFileName(url);
        String dataDir = selfActivity.getDataDir();
        File downloadDir = new File(dataDir, "downloads"); downloadDir.mkdirs();
        File targetFile = new File(downloadDir, fileName);
        if (targetFile.exists()) {
            String base = fileName, ext = "";
            int dotIdx = base.lastIndexOf('.');
            if (dotIdx > 0) { ext = base.substring(dotIdx); base = base.substring(0, dotIdx); }
            for (int i = 1; i <= 99; i++) {
                targetFile = new File(downloadDir, base + "_" + i + ext);
                if (!targetFile.exists()) { fileName = base + "_" + i + ext; break; }
            }
        }
        EdtUtils.println(new Color(120, 200, 255), "\n  [下载] " + url + " -> " + fileName);
        java.net.HttpURLConnection conn = null;
        java.io.BufferedInputStream bis = null;
        java.io.FileOutputStream fos = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET"); conn.setConnectTimeout(15_000); conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; AiAgent-SFW/1.4)");
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                bis = new java.io.BufferedInputStream(conn.getInputStream());
                fos = new java.io.FileOutputStream(targetFile);
                byte[] buf = new byte[8192]; long downloaded = 0; int n;
                while ((n = bis.read(buf)) != -1) { fos.write(buf, 0, n); downloaded += n; }
                fos.flush(); bis.close(); fos.close(); conn.disconnect();
                String sizeStr = formatSize(downloaded);
                String relPath = "downloads/" + fileName;
                if (memoryManager != null) memoryManager.add("下载文件: " + relPath + " | 来源: " + url + " | 大小: " + sizeStr);
                return "下载完成: " + relPath + " (" + sizeStr + ")\n绝对路径: " + targetFile.getAbsolutePath();
            }
            conn.disconnect(); return "下载 [" + url + "] 失败: HTTP " + code;
        } catch (java.io.FileNotFoundException e) { return "下载 [" + url + "] 失败: 文件不存在 (404)";
        } catch (Exception e) { return "下载 [" + url + "] 错误: " + e.toString();
        } finally {
            try { if (bis != null) bis.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    String executeSurprise(String content) {
        if (content == null || content.trim().isEmpty()) return "彩蛋内容为空，跳过。";
        String text = content.trim();
        EdtUtils.println(new Color(255, 180, 220), "\n  [彩蛋] " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
        SwingUtilities.invokeLater(() -> {
            try { new SurpriseWindow(text).display(); } catch (Exception e) { EdtUtils.println(FCM.Error_Color, "  [彩蛋] 弹窗失败: " + e.toString()); }
        });
        return "彩蛋已弹出。";
    }

    String executeEditPrompt(String newPrompt) {
        if (newPrompt == null || newPrompt.trim().isEmpty()) return "提示词内容为空，未修改。";
        String prompt = newPrompt.trim();
        if (prompt.length() < 30) return "提示词太短（" + prompt.length() + " 字符），需要至少 30 字符。";
        EdtUtils.println(new Color(200, 180, 255), "\n  [修改提示词] 长度: " + prompt.length() + " 字符");
        try { AiConfig.getInstance().setSystemPrompt(prompt); AiConfig.getInstance().save();
            return "系统提示词已更新（" + prompt.length() + " 字符）。新个性已生效。";
        } catch (Exception e) { return "提示词更新失败: " + e.getMessage(); }
    }

    String executeSendImage(String content) {
        if (content == null || content.trim().isEmpty()) return "[sendimage] 内容为空，无法渲染";
        if (sair.aiagent.util.ImageRenderer.isHeadless()) return "[sendimage] 当前环境无图形界面，无法渲染图片。";
        String text = content.trim();
        EdtUtils.println(new Color(150, 200, 255), "\n  [渲染图片] " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
        try {
            String dataDir = selfActivity.getDataDir();
            File outputDir = new File(dataDir, "rendered");
            String fileName = "img_" + System.currentTimeMillis() + ".png";
            File outputFile = new File(outputDir, fileName);
            sair.aiagent.util.ImageRenderer.renderTextToImage(text, outputFile);
            String absPath = outputFile.getAbsolutePath();
            if (qqExecsCallback != null) qqExecsCallback.accept("[IMAGE]" + absPath);
            return "[sendimage] 图片已渲染: " + absPath;
        } catch (Exception e) { return "[sendimage] 渲染失败: " + e.getMessage(); }
    }

    String executeSendRecord(String content) {
        if (content == null || content.trim().isEmpty()) return "[sendrecord] 语音文件路径为空";
        String path = content.trim();
        EdtUtils.println(new Color(200, 180, 255), "\n  [发送语音] " + path);
        if (qqExecsCallback != null) qqExecsCallback.accept("[RECORD]" + path);
        return "[sendrecord] 语音消息路径已传递: " + path;
    }

    String executeSendFile(String content) {
        if (content == null || content.trim().isEmpty()) return "[sendfile] 文件路径为空";
        String filePath = content.trim();
        File file = new File(filePath);
        if (!file.exists()) return "[sendfile] 文件不存在: " + filePath;
        if (!file.isFile()) return "[sendfile] 路径不是文件: " + filePath;
        EdtUtils.println(new Color(255, 200, 150), "\n  [发送文件] " + filePath + " (" + formatSize(file.length()) + ")");
        if (qqExecsCallback != null) qqExecsCallback.accept("[FILE]" + filePath + "|" + file.getName());
        return "[sendfile] 文件路径已传递: " + filePath + " (" + formatSize(file.length()) + ")";
    }

    String executeSendSticker(String context) {
        if (stickerManager == null) return "[sendsticker] StickerManager not initialized";
        if (context == null || context.trim().isEmpty()) return stickerManager.buildInventoryForAi();
        EdtUtils.println(new Color(255, 200, 150), "\n  [Sticker] matching: " + (context.length() > 40 ? context.substring(0, 40) + "..." : context));
        StickerEntry match = stickerManager.findBestMatch(context.trim());
        if (match == null) return "[sendsticker] no matching sticker for context";
        if (qqExecsCallback != null) {
            String img = match.getImageUrl();
            if (img != null && !img.isEmpty()) qqExecsCallback.accept("[STICKER]" + img + "|" + match.getId());
        }
        return "[sendsticker] sticker #" + match.getId() + " sent";
    }

    String executeCollectSticker(String content) {
        if (stickerManager == null) return "[collectsticker] StickerManager not initialized";
        if (content == null || content.trim().isEmpty()) return "[collectsticker] need imageUrl";
        String imageUrl, ctx;
        int pipeIdx = content.indexOf("|");
        if (pipeIdx > 0) { imageUrl = content.substring(0, pipeIdx).trim(); ctx = content.substring(pipeIdx + 1).trim(); }
        else { imageUrl = content.trim(); ctx = ""; }
        StickerEntry entry = stickerManager.collect(imageUrl, ctx);
        if (entry != null) return "[collectsticker] collected #" + entry.getId();
        return "[collectsticker] failed";
    }

    String executeClearSticker() {
        if (stickerManager == null) return "[clearsticker] StickerManager not initialized";
        stickerManager.clearAll();
        return "[clearsticker] 表情包图片库已清空";
    }

    String executeStop() {
        EdtUtils.println(FCM.Error_Color, "\n  [停止] Agent执行已被中断");
        if (parent != null) parent.markStopped();
        return "[STOP] Agent执行已被中断。";
    }

    public String executeWeather(String city) {
        if (city == null || city.trim().isEmpty()) return WeatherTool.queryWeather("");
        return WeatherTool.queryWeather(city.trim());
    }

    String executeBalance() {
        if (client == null) return "[balance] DeepSeekClient未初始化";
        try { return client.queryBalance(); } catch (Exception e) { return "[balance] 查询失败: " + e.toString(); }
    }

    static String extractFileName(String url) {
        try {
            String path = new java.net.URI(url).getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty()) {
                    try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) {}
                    name = name.replaceAll("[/\\\\]", "_").replaceAll("\\.\\.", "__").replaceAll("\\x00", "");
                    name = name.trim().replaceAll("^\\.+", "_").replaceAll("\\.+$", "_");
                    if (name.isEmpty()) name = "download";
                    return name;
                }
            }
        } catch (Exception ignored) {}
        return "download_" + Math.abs(url.hashCode());
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    static boolean isInternalHost(String host) {
        if (host == null || host.isEmpty()) return true;
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("0.0.0.0")) return true;
        if (lower.startsWith("10.") || lower.startsWith("192.168.")) return true;
        if (lower.startsWith("172.")) {
            try { int second = Integer.parseInt(lower.substring(4, lower.indexOf('.', 4))); if (second >= 16 && second <= 31) return true; } catch (Exception ignored) {}
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (ip == null) return false;
            if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0") || ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
            if (ip.startsWith("172.")) { int di = ip.indexOf('.', 4); if (di > 0) { int s = Integer.parseInt(ip.substring(4, di)); if (s >= 16 && s <= 31) return true; } }
            if (ip.startsWith("169.254.")) return true;
            if (ip.equals("::1") || ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    static String extractDiff(String before, String after) {
        if (before == null || after == null) return after;
        if (before.equals(after)) return "(无变化)";
        String diff = after;
        if (after.startsWith(before) && after.length() > before.length()) { diff = after.substring(before.length()).trim(); if (diff.isEmpty()) diff = after; }
        if (diff.length() > 3000) diff = diff.substring(0, 3000) + "\n…(输出过长，已截断至 3000 字符)";
        return diff.isEmpty() ? after : diff;
    }
}