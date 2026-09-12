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

    private static final long MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024; // 100MB

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
            case "sys":      result = executeSys(content); break;
            case "evaljs":   result = executeEvalJs(content); break;
            case "eval":     result = executeEval(content); break;
            case "remember": result = executeRemember(content); break;
            case "superise": result = executeSurprise(content); break;
            case "stop":      result = executeStop(); break;
            case "sendimage": result = executeSendImage(content); break;
            case "sendrecord":result = executeSendRecord(content); break;
            case "sendfile":      result = executeSendFile(content); break;
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

    String executeSys(String command) {
        if (!gate.await("sys", "执行系统命令: " + command)) return "系统命令被拒绝。";
        EdtUtils.println(C_TOOL, "\n  > 系统命令: " + command);
        final SysConsolePanel panel = new SysConsolePanel(command);
        SwingUtilities.invokeLater(() -> ConsFrame.printComponent(panel));
        final StringBuilder result = new StringBuilder();
        final Object lock = new Object();
        ThreadManager.getInstance().newDaemonThread("AiAgent-AgentSys", () -> {
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
        }).start();
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

    String executeSurprise(String content) {
        if (content == null || content.trim().isEmpty()) return "彩蛋内容为空，跳过。";
        String text = content.trim();
        EdtUtils.println(new Color(255, 180, 220), "\n  [彩蛋] " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
        SwingUtilities.invokeLater(() -> {
            try { new SurpriseWindow(text).display(); } catch (Exception e) { EdtUtils.println(FCM.Error_Color, "  [彩蛋] 弹窗失败: " + e.toString()); }
        });
        return "彩蛋已弹出。";
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
        // 回传具体原因：避免模型看到裸 failed 后瞎猜原因、并反复重试同一张图
        String reason = stickerManager.getLastCollectReason();
        if (reason == null || reason.isEmpty()) reason = "收藏失败（原因未知）";
        return "[collectsticker] 收藏失败：" + reason;
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