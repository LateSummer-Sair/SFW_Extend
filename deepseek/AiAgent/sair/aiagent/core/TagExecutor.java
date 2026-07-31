package sair.aiagent.core;

import java.awt.Color;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.swing.SwingUtilities;

import sair.FCM;
import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.AgentAction;
import sair.aiagent.model.StickerEntry;
import sair.aiagent.ui.SysConsolePanel;
import sair.aiagent.util.EdtUtils;
import sair.aiagent.util.FileUtils;
import sair.sys.SairCons;
import sair.sys.gui.ConsFrame;
import sair.user.Activity;

/**
 * 标签执行器 —— 从 {@link AgentExecutor} 提取的所有 &lt;xxx&gt; 标签执行方法。
 * <p>负责解析 Agent 响应中的 XML 标签并执行对应的操作（cmd/sys/web/eval/download/remember 等）。</p>
 */
public class TagExecutor {

    private static final Pattern TAG_PATTERN =
            Pattern.compile("<(cmd|readfile|readdir|sys|evaljs|eval|web|remember|download|superise|editprompt|stop|sendimage|sendrecord|sendfile|schedule|note|searchnote|batchrename|batchconvert)>(.*?)</\\1>", Pattern.DOTALL);

    /** execq 受限标签白名单 */
    static final Pattern EXECQ_TAG_PATTERN =
            Pattern.compile("<(cmd|web|readdir|setname|stop|sendsticker|collectsticker|readfile|schedule|note|searchnote)>(.*?)</\\1>", Pattern.DOTALL);

    /** execq 群管标签检测（用于触发实时回调） */
    static final Pattern GROUP_TAG_PATTERN =
            Pattern.compile("<(ban|kick|muteall|setadmin|setcard|setgroupname|leavegroup|block|unblock|delfriend)>", Pattern.CASE_INSENSITIVE);

    private final ConfirmationGate gate;
    private final DynamicCodeEngine codeEngine;
    private final Activity selfActivity;
    private volatile MemoryManager memoryManager;
    private volatile EmotionManager emotionManager;
    private volatile StickerManager stickerManager;
    private volatile JournalManager journal;
    private volatile java.util.function.Consumer<String> qqExecsCallback;
    private volatile CronScheduler cronScheduler;

    public TagExecutor(ConfirmationGate gate, DynamicCodeEngine codeEngine, Activity selfActivity) {
        this.gate = gate;
        this.codeEngine = codeEngine;
        this.selfActivity = selfActivity;
    }

    public void setMemoryManager(MemoryManager memoryManager) { this.memoryManager = memoryManager; }
    public void setEmotionManager(EmotionManager emotionManager) { this.emotionManager = emotionManager; }
    public void setStickerManager(StickerManager stickerManager) { this.stickerManager = stickerManager; }
    public void setJournal(JournalManager journal) { this.journal = journal; }
    public void setQqExecsCallback(java.util.function.Consumer<String> callback) { this.qqExecsCallback = callback; }
    public void setCronScheduler(CronScheduler cronScheduler) { this.cronScheduler = cronScheduler; }

    // ==================== 静态解析方法 ====================

    static List<AgentAction> parseActions(String response) {
        List<AgentAction> actions = new ArrayList<>();
        Matcher m = TAG_PATTERN.matcher(response);
        while (m.find()) {
            actions.add(new AgentAction(m.group(1), m.group(2).trim()));
        }
        return actions;
    }

    static List<AgentAction> parseExecqActions(String response) {
        List<AgentAction> actions = new ArrayList<>();
        Matcher m = EXECQ_TAG_PATTERN.matcher(response);
        while (m.find()) {
            actions.add(new AgentAction(m.group(1), m.group(2).trim()));
        }
        return actions;
    }

    // ==================== 标签分发 ====================

    String executeAction(AgentAction action) {
        String type = action.getType();
        String content = action.getContent();
        String result;
        switch (type) {
            case "cmd":      result = executeCmd(content); break;
            case "readfile": result = executeReadFile(content); break;
            case "readdir":  result = executeReadDir(content); break;
            case "sys":      result = executeSys(content); break;
            case "evaljs":   result = executeEvalJs(content); break;
            case "eval":     result = executeEval(content); break;
            case "web":      result = executeWeb(content); break;
            case "remember": result = executeRemember(content); break;
            case "download": result = executeDownload(content); break;
            case "superise": result = executeSurprise(content); break;
            case "editprompt": result = executeEditPrompt(content); break;
            case "stop":      result = executeStop(); break;
            case "sendimage": result = executeSendImage(content); break;
            case "sendrecord":result = executeSendRecord(content); break;
            case "sendfile":      result = executeSendFile(content); break;
            case "schedule":      result = executeSchedule(content); break;
            case "note":         result = executeNote(content); break;
            case "searchnote":   result = executeSearchNote(content); break;
            case "batchrename":  result = executeBatchRename(content); break;
            case "batchconvert": result = executeBatchConvert(content); break;
            default:         result = "未知操作: " + type; break;
        }
        if (journal != null) {
            journal.addAgentAction(type, content, result);
        }
        return result;
    }

    // ==================== execq 标签 ====================

    String executeQqAction(AgentAction action) {
        switch (action.getType()) {
            case "cmd":     return executeCmdExecq(action.getContent());
            case "web":     return executeWeb(action.getContent());
            case "readdir": return executeReadDir(action.getContent());
            case "readfile":return executeReadFileExecq(action.getContent());
            case "setname": return executeSetName(action.getContent());
            case "stop":    return executeStop();
            case "sendsticker":   return executeSendSticker(action.getContent());
            case "collectsticker":return executeCollectSticker(action.getContent());
            case "schedule":      return executeSchedule(action.getContent());
            case "note":         return executeNote(action.getContent());
            case "searchnote":   return executeSearchNote(action.getContent());
            default:        return "QQ execq通道不支持: " + action.getType();
        }
    }

    private String executeCmdExecq(String command) {
        String pluginName = extractPluginName(command);
        if (pluginName == null) {
            return "execq通道 <cmd> 格式错误，应为 pluginName/funcName args，收到: " + command;
        }
        java.util.Set<String> cmdWhitelist = sair.aiagent.core.AiConfig.getInstance().getExecqCmdWhitelist();
        if (cmdWhitelist == null || !cmdWhitelist.contains(pluginName)) {
            return "execq通道 <cmd> 被拒绝：插件 [" + pluginName + "] 不在白名单中。"
                 + "当前白名单: " + (cmdWhitelist == null ? "(无)" : cmdWhitelist.toString());
        }
        return executeCmd(command);
    }

    /** execq 通道的 readfile 执行 */
    private String executeReadFileExecq(String path) {
        String content = FileUtils.readFile(path.trim());
        return "文件 [" + path + "] 内容:\n" + content;
    }

    /** 设置AI机器人名字 */
    private String executeSetName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "[setname] 错误：名字不能为空";
        }
        String trimmedName = name.trim();
        sair.aiagent.core.AiConfig.getInstance().setBotName(trimmedName);
        sair.aiagent.core.AiConfig.getInstance().save();
        return "[setname] 名字已设置为: " + trimmedName;
    }

    // ==================== 通用标签 ====================

    /** note knowledge base */
    private String executeNote(String content) {
        if (content == null || content.trim().isEmpty()) 
            return "[note] usage: <note add title|content|tags> | search query | list | get id | delete id | update id title|content|tags";
        String c = content.trim();
        if (c.startsWith("add ")) {
            String rest = c.substring(4).trim();
            String[] parts = rest.split("\\|", 3);
            if (parts.length < 2) return "[note] add needs title|content format";
            String title = parts[0].trim();
            String body = parts[1].trim();
            String tags = parts.length > 2 ? parts[2].trim() : "";
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return "[note] PersistenceManager not available";
            int id = pm.addNote(title, body, tags);
            return id > 0 ? "[note] saved #" + id + ": " + title : "[note] save failed";
        } else if (c.startsWith("search ")) {
            String query = c.substring(7).trim();
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return "[note] PersistenceManager not available";
            java.util.List<String[]> results = pm.searchNotes(query, 10);
            if (results.isEmpty()) return "[note] no results for: " + query;
            StringBuilder sb = new StringBuilder("[note] search results (" + results.size() + "):");
            for (String[] r : results) {
                sb.append("\n  #").append(r[0]).append(" ").append(r[1]);
                if (r[2] != null && !r[2].isEmpty()) sb.append(" - ").append(r[2]);
            }
            return sb.toString();
        } else if (c.startsWith("list")) {
            PersistenceManager pm = PersistenceManager.getInstance();
            if (pm == null) return "[note] PersistenceManager not available";
            java.util.List<String[]> notes = pm.listNotes(20);
            if (notes.isEmpty()) return "[note] no notes yet";
            StringBuilder sb = new StringBuilder("[note] recent notes (" + notes.size() + "):");
            for (String[] n : notes) {
                sb.append("\n  #").append(n[0]).append(" ").append(n[1]);
                if (n[3] != null && !n[3].isEmpty()) sb.append(" [").append(n[3]).append("]");
            }
            return sb.toString();
        } else if (c.startsWith("get ")) {
            try {
                int id = Integer.parseInt(c.substring(4).trim());
                PersistenceManager pm = PersistenceManager.getInstance();
                if (pm == null) return "[note] PersistenceManager not available";
                String[] note = pm.getNote(id);
                if (note == null) return "[note] #" + id + " not found";
                return "[note] #" + id + " " + note[0] + "\n" + note[1] 
                    + (note[2] != null && !note[2].isEmpty() ? "\nTags: " + note[2] : "");
            } catch (NumberFormatException e) { return "[note] get needs numeric id"; }
        } else if (c.startsWith("delete ")) {
            try {
                int id = Integer.parseInt(c.substring(7).trim());
                PersistenceManager pm = PersistenceManager.getInstance();
                if (pm == null) return "[note] PersistenceManager not available";
                return pm.removeNote(id) ? "[note] #" + id + " deleted" : "[note] #" + id + " not found";
            } catch (NumberFormatException e) { return "[note] delete needs numeric id"; }
        } else if (c.startsWith("update ")) {
            String rest = c.substring(7).trim();
            String[] parts2 = rest.split(" ", 2);
            try {
                int id = Integer.parseInt(parts2[0]);
                String updateArgs = parts2.length > 1 ? parts2[1].trim() : "";
                String[] fields = updateArgs.split("\\|", 3);
                if (fields.length < 2) return "[note] update needs: id title|content|tags";
                PersistenceManager pm = PersistenceManager.getInstance();
                if (pm == null) return "[note] PersistenceManager not available";
                String title = fields[0].trim();
                String body = fields[1].trim();
                String tags = fields.length > 2 ? fields[2].trim() : "";
                return pm.updateNote(id, title, body, tags) 
                    ? "[note] #" + id + " updated" : "[note] #" + id + " not found";
            } catch (NumberFormatException e) { return "[note] update needs: numeric_id title|content|tags"; }
        }
        return "[note] unknown command. try: add/search/list/get/delete/update";
    }

    /** search note alias */
    private String executeSearchNote(String content) {
        return executeNote("search " + (content != null ? content.trim() : ""));
    }

    private String executeCmd(String command) {
        if (!gate.await("cmd", "执行 SFW命令: " + command)) {
            return "SFW命令被拒绝。";
        }
        EdtUtils.println(new Color(255, 200, 100), "\n  > 执行 SFW命令: " + command);
        final String[] output = new String[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    try {
                        String before = SairCons.getConsoleText();
                        SairCons.runner(false, command);
                        String after = SairCons.getConsoleText();
                        output[0] = extractDiff(before, after);
                    } catch (Exception e) {
                        output[0] = "ERROR: " + e.getMessage();
                    }
                }
            });
        } catch (InvocationTargetException e) {
            return "命令错误: " + e.getCause().getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "命令中断。";
        }
        String result = output[0];
        if (result == null || result.isEmpty() || result.equals("(无输出)")) {
            return "命令 [" + command + "] 已执行（无文本输出）";
        }
        return "命令 [" + command + "] 结果:\n" + result;
    }

    private String executeReadFile(String path) {
        if (!gate.await("readfile", "读取文件: " + path)) {
            return "读取文件被拒绝。";
        }
        EdtUtils.println(new Color(255, 200, 100), "\n  > 读取: " + path);
        String content = FileUtils.readFile(path);
        return "文件 [" + path + "]:\n" + content;
    }

    private String executeReadDir(String path) {
        if (!gate.await("readdir", "列出目录: " + path)) {
            return "列出目录被拒绝。";
        }
        EdtUtils.println(new Color(255, 200, 100), "\n  > 列出目录: " + path);
        String content = FileUtils.readDir(path);
        return "目录 [" + path + "]:\n" + content;
    }

    private String executeSys(String command) {
        if (!gate.await("sys", "执行系统命令: " + command)) {
            return "系统命令被拒绝。";
        }
        EdtUtils.println(new Color(255, 200, 100), "\n  > 系统命令: " + command);

        final SysConsolePanel panel = new SysConsolePanel(command);
        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                ConsFrame.printComponent(panel);
            }
        });

        final StringBuilder result = new StringBuilder();
        final Object lock = new Object();

        new Thread(new Runnable() {
            public void run() {
                try {
                    SysConsoleExecutor.executeWithListener(command,
                            new SysConsoleExecutor.OutputListener() {
                        @Override
                        public void onStart() {}
                        @Override
                        public void onLine(String line) {
                            panel.appendLine(line);
                        }
                        @Override
                        public void onFinish(String fullOutput) {
                            panel.setFinished();
                            synchronized (lock) {
                                result.append(fullOutput);
                                lock.notify();
                            }
                        }
                        @Override
                        public void onError(String message) {
                            panel.setError(message);
                            synchronized (lock) {
                                result.append("ERROR: ").append(message);
                                lock.notify();
                            }
                        }
                    });
                } finally {
                    synchronized (lock) {
                        if (result.length() == 0) {
                            result.append("ERROR: 命令执行线程异常退出");
                        }
                        lock.notify();
                    }
                }
            }
        }, "AiAgent-AgentSys").start();

        synchronized (lock) {
            try {
                lock.wait(35_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "系统命令中断。";
            }
        }

        String output = result.toString();
        if (output.startsWith("ERROR: ")) {
            return "系统命令 [" + command + "] 错误: " + output.substring(7);
        }
        return "系统命令 [" + command + "] 结果:\n" + output;
    }

    private String executeEvalJs(String code) {
        if (!gate.await("evaljs", "执行 JavaScript: " + (code.length() > 80 ? code.substring(0, 80) + "..." : code))) {
            return "JS 执行被拒绝。";
        }
        EdtUtils.println(new Color(100, 200, 255), "\n  [JS执行] " + (code.length() > 60 ? code.substring(0, 60) + "..." : code));
        if (codeEngine == null) return "JS引擎未初始化。";
        String result = codeEngine.evalJS(code);
        return "========== JS 执行结果 ==========\n" + result;
    }

    private String executeEval(String code) {
        String display = code.length() > 60 ? code.substring(0, 60) + "..." : code;
        if (!gate.await("eval", "编译并执行 Java 代码: " + display)) {
            return "Java 编译执行被拒绝。";
        }
        EdtUtils.println(new Color(100, 255, 200), "\n  [Java编译] " + display);
        if (codeEngine == null) return "代码引擎未初始化。";
        if (!codeEngine.isCompilerAvailable()) return "Java编译器不可用：当前JRE环境，需JDK。请在运行SFW的JDK环境下使用。";
        String objName = "obj_" + System.currentTimeMillis() % 100000;
        String result = codeEngine.compileAndInstantiate(code, objName);
        String compileInfo = codeEngine.getLastCompilerMessage();
        if (result.startsWith("编译失败") || result.startsWith("无法从源码中提取类名")) {
            return "========== 动态注入结果 ==========\n【编译】" + result;
        }
        return "========== 动态注入结果 ==========\n"
                + "【编译】" + (compileInfo != null && !compileInfo.isEmpty() ? compileInfo : "编译通过")
                + "\n【执行】" + result;
    }

    private String executeWeb(String raw) {
        raw = raw.trim();
        String url = raw;
        String cssSelector = null;
        String regexFilter = null;

        // parse regex="..." attribute
        java.util.regex.Matcher rm = java.util.regex.Pattern.compile("regex=\"([^\"]*)\"").matcher(raw);
        if (rm.find()) {
            regexFilter = rm.group(1);
            url = raw.substring(0, rm.start()).trim();
        }

        // parse url|selector format
        int pipe = url.indexOf('|');
        if (pipe > 0) {
            cssSelector = url.substring(pipe + 1).trim();
            url = url.substring(0, pipe).trim();
        }

        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (isInternalHost(host)) {
                EdtUtils.println(FCM.Error_Color, "\n  [Web] 拒绝内网地址: " + host);
                return "Web GET [" + url + "] 被拒绝: 禁止访问内网地址 (" + host + ")";
            }
        } catch (Exception e) {
            return "Web GET [" + url + "] 错误: URL格式无效 - " + e.getMessage();
        }
        if (!gate.await("web", "联网获取: " + url)) {
            return "Web 请求被拒绝。";
        }
        EdtUtils.println(new Color(100, 200, 255), "\n  [Web] GET " + url);
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; AiAgent-SFW/1.4)");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                java.io.InputStream is = conn.getInputStream();
                java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
                String body = s.hasNext() ? s.next() : "";
                s.close();
                is.close();
                if (body.length() > 4000) {
                    body = body.substring(0, 4000) + "\n\n...(已截断，" +
                           (body.length() - 4000) + " 字符省略)";
                }
                // apply selector or regex filter
                String result = body;
                if (cssSelector != null && !cssSelector.isEmpty()) {
                    result = extractBySelector(body, cssSelector);
                } else if (regexFilter != null && !regexFilter.isEmpty()) {
                    result = extractByRegex(body, regexFilter);
                }
                return "Web GET [" + url + "] (HTTP " + code + "):\n" + result;
            } else {
                return "Web GET [" + url + "] 失败: HTTP " + code;
            }
        } catch (Exception e) {
            return "Web GET [" + url + "] 错误: " + e.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private String executeRemember(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "记忆内容为空，未记录。";
        }
        if (memoryManager == null) {
            return "记忆管理器未初始化，无法记录。";
        }
        sair.aiagent.model.MemoryEntry entry = memoryManager.add(content.trim());
        if (entry != null) {
            EdtUtils.println(new Color(255, 220, 130), "\n  [记忆] 已记录 [" + entry.getId() + "]: " + entry.getContent());
            return "记忆已记录 [" + entry.getId() + "]: " + entry.getContent();
        }
        return "记忆记录失败。";
    }

    private String executeDownload(String url) {
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "下载地址无效，必须以 http:// 或 https:// 开头: " + url;
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String host = uri.getHost();
            if (isInternalHost(host)) {
                EdtUtils.println(FCM.Error_Color, "\n  [下载] 拒绝内网地址: " + host);
                return "下载 [" + url + "] 被拒绝: 禁止访问内网地址 (" + host + ")";
            }
        } catch (Exception e) {
            return "下载 [" + url + "] 错误: URL格式无效 - " + e.getMessage();
        }

        if (!gate.await("download", "下载文件: " + url)) {
            return "下载被拒绝。";
        }

        String fileName = extractFileName(url);
        String dataDir = selfActivity.getDataDir();
        File downloadDir = new File(dataDir, "downloads");
        downloadDir.mkdirs();
        File targetFile = new File(downloadDir, fileName);

        if (targetFile.exists()) {
            String base = fileName;
            String ext = "";
            int dotIdx = base.lastIndexOf('.');
            if (dotIdx > 0) {
                ext = base.substring(dotIdx);
                base = base.substring(0, dotIdx);
            }
            for (int i = 1; i <= 99; i++) {
                targetFile = new File(downloadDir, base + "_" + i + ext);
                if (!targetFile.exists()) {
                    fileName = base + "_" + i + ext;
                    break;
                }
            }
        }

        EdtUtils.println(new Color(120, 200, 255), "\n  [下载] " + url + " -> " + fileName);

        java.net.HttpURLConnection conn = null;
        java.io.BufferedInputStream bis = null;
        java.io.FileOutputStream fos = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (compatible; AiAgent-SFW/1.4)");
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 400) {
                long totalSize = conn.getContentLengthLong();
                bis = new java.io.BufferedInputStream(conn.getInputStream());
                fos = new java.io.FileOutputStream(targetFile);
                byte[] buf = new byte[8192];
                long downloaded = 0;
                int n;
                while ((n = bis.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    downloaded += n;
                }
                fos.flush();
                bis.close();
                fos.close();
                conn.disconnect();

                String sizeStr = formatSize(downloaded);
                String relPath = "downloads/" + fileName;
                String absPath = targetFile.getAbsolutePath();

                if (memoryManager != null) {
                    memoryManager.add("下载文件: " + relPath + " | 来源: " + url + " | 大小: " + sizeStr);
                }

                return "下载完成: " + relPath + " (" + sizeStr + ")\n"
                     + "绝对路径: " + absPath + "\n"
                     + "已记录到记忆，下次可直接使用相对路径 downloads/" + fileName;
            } else {
                conn.disconnect();
                return "下载 [" + url + "] 失败: HTTP " + code;
            }
        } catch (java.io.FileNotFoundException e) {
            return "下载 [" + url + "] 失败: 文件不存在 (404)";
        } catch (Exception e) {
            return "下载 [" + url + "] 错误: " + e.toString();
        } finally {
            try { if (bis != null) bis.close(); } catch (Exception ignored) {}
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    /** cron schedule management */
    private String executeSchedule(String content) {
        if (cronScheduler == null) return "[schedule] CronScheduler not initialized";
        if (content == null || content.trim().isEmpty()) 
            return "[schedule] usage: <schedule add \"*/30\" \"cmd\"> | list | remove id | enable id | disable id";
        String c = content.trim();
        String[] parts = c.split("\\s+", 2);
        String subCmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1].trim() : "";
        switch (subCmd) {
            case "add": {
                String cronExpr = extractQuoted(args, 0);
                String command = extractQuoted(args, 1);
                String desc = extractQuoted(args, 2);
                if (cronExpr.isEmpty() || command.isEmpty()) 
                    return "[schedule] add needs cron_expr and command in quotes";
                return cronScheduler.addTask(cronExpr, command, desc);
            }
            case "list": return cronScheduler.listTasks();
            case "remove":
                try { return cronScheduler.removeTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] remove needs task ID"; }
            case "enable":
                try { return cronScheduler.enableTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] enable needs task ID"; }
            case "disable":
                try { return cronScheduler.disableTask(Integer.parseInt(args)); }
                catch (NumberFormatException e) { return "[schedule] disable needs task ID"; }
            default: return "[schedule] unknown: " + subCmd + ". try: add/list/remove/enable/disable";
        }
    }

    private static String extractQuoted(String args, int index) {
        int count = 0, i = 0;
        while (i < args.length() && count <= index) {
            if (args.charAt(i) == '"') {
                int end = args.indexOf('"', i + 1);
                if (end < 0) break;
                if (count == index) return args.substring(i + 1, end);
                i = end + 1; count++;
            } else { i++; }
        }
        return "";
    }

    // ==================== batch file operations ====================

    private String executeBatchRename(String content) {
        if (content == null || content.trim().isEmpty()) 
            return "[batchrename] usage: <batchrename dir=/path pattern=regex replacement=text> or with preview prefix";
        String c = content.trim();
        String dir = extractParam(c, "dir");
        String pattern = extractParam(c, "pattern");
        String replacement = extractParam(c, "replacement");
        boolean preview = c.startsWith("preview") || c.contains("preview ");

        if (dir.isEmpty() || pattern.isEmpty()) 
            return "[batchrename] needs dir and pattern params";

        java.io.File d = new java.io.File(dir);
        if (!d.exists() || !d.isDirectory()) return "[batchrename] dir not found: " + dir;
        
        java.io.File[] files = d.listFiles();
        if (files == null || files.length == 0) return "[batchrename] no files in: " + dir;
        
        java.util.regex.Pattern pat;
        try { pat = java.util.regex.Pattern.compile(pattern); } 
        catch (Exception e) { return "[batchrename] invalid regex: " + pattern; }
        
        StringBuilder sb = new StringBuilder("[batchrename] ");
        if (preview) sb.append("PREVIEW:\n"); else sb.append((replacement.isEmpty() ? "matches" : "rename") + ":\n");
        int count = 0;
        for (java.io.File f : files) {
            if (!f.isFile()) continue;
            String name = f.getName();
            java.util.regex.Matcher m = pat.matcher(name);
            if (m.find()) {
                String newName = replacement.isEmpty() ? name : m.replaceAll(replacement);
                sb.append("  ").append(name).append(" -> ").append(newName).append("\n");
                if (!preview && !replacement.isEmpty()) {
                    java.io.File dest = new java.io.File(d, newName);
                    if (dest.exists()) { sb.append("    SKIP: already exists\n"); continue; }
                    if (f.renameTo(dest)) count++;
                } else if (preview) { count++; }
            }
        }
        sb.append("  Total: ").append(count).append(" files");
        return sb.toString();
    }

    private String executeBatchConvert(String content) {
        if (content == null || content.trim().isEmpty()) 
            return "[batchconvert] usage: <batchconvert dir=/path from=EXT to=EXT>";
        String c = content.trim();
        String dir = extractParam(c, "dir");
        String from = extractParam(c, "from");
        String to = extractParam(c, "to");
        if (dir.isEmpty() || from.isEmpty() || to.isEmpty()) 
            return "[batchconvert] needs dir, from, to params";
        
        java.io.File d = new java.io.File(dir);
        if (!d.exists() || !d.isDirectory()) return "[batchconvert] dir not found: " + dir;
        
        java.io.File[] files = d.listFiles();
        if (files == null || files.length == 0) return "[batchconvert] no files in: " + dir;
        
        StringBuilder sb = new StringBuilder("[batchconvert]");
        int count = 0;
        for (java.io.File f : files) {
            String name = f.getName().toLowerCase();
            if (!f.isFile() || !name.endsWith("." + from.toLowerCase())) continue;
            String base = f.getName().substring(0, f.getName().length() - from.length() - 1);
            java.io.File dest = new java.io.File(d, base + "." + to);
            try {
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f);
                if (img == null) { 
                    sb.append("\n  FAIL: ").append(f.getName()).append(" (not readable)"); 
                    continue; 
                }
                if (!javax.imageio.ImageIO.write(img, to, dest)) {
                    sb.append("\n  FAIL: ").append(f.getName()).append(" (no writer for ").append(to).append(")");
                    continue;
                }
                sb.append("\n  ").append(f.getName()).append(" -> ").append(dest.getName());
                count++;
            } catch (Exception e) {
                sb.append("\n  ERROR: ").append(f.getName()).append(" - ").append(e.getMessage());
            }
        }
        sb.append("\n  Converted: ").append(count).append(" files");
        return sb.toString();
    }

    /** extract key="value" param from string */
    private static String extractParam(String s, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            key + "=\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE
        ).matcher(s);
        return m.find() ? m.group(1) : "";
    }


    // ==================== Web 抓取辅助方法 ====================

    /** 通过CSS选择器从HTML提取内容 */
    private static String extractBySelector(String html, String selector) {
        if (html == null || selector == null) return html;
        StringBuilder result = new StringBuilder();
        String lower = selector.toLowerCase().trim();
        if (lower.startsWith(".")) {
            String cls = lower.substring(1);
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<[^>]*class=\"[^\"]*" + java.util.regex.Pattern.quote(cls) + "[^\"]*\"[^>]*>(.*?)</[^>]+>",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                if (result.length() > 0) result.append("\n");
                result.append(stripHtml(m.group(1)));
            }
        } else if (lower.startsWith("#")) {
            String id = lower.substring(1);
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<[^>]*id=\"" + java.util.regex.Pattern.quote(id) + "\"[^>]*>(.*?)</[^>]+>",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                if (result.length() > 0) result.append("\n");
                result.append(stripHtml(m.group(1)));
            }
        } else {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "<" + java.util.regex.Pattern.quote(lower) + "[^>]*>(.*?)</" + java.util.regex.Pattern.quote(lower) + ">",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                if (result.length() > 0) result.append("\n");
                result.append(stripHtml(m.group(1)));
            }
        }
        return result.length() > 0 ? result.toString() : "(no matches for: " + selector + ")";
    }

    /** 通过正则从HTML提取内容 */
    private static String extractByRegex(String html, String regex) {
        if (html == null || regex == null) return html;
        StringBuilder result = new StringBuilder();
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher m = p.matcher(html);
            while (m.find()) {
                if (result.length() > 0) result.append("\n");
                result.append(m.group());
                if (result.length() > 3000) { result.append("\n...(truncated)"); break; }
            }
        } catch (Exception e) { return "Regex error: " + e.getMessage(); }
        return result.length() > 0 ? result.toString() : "(no matches for regex)";
    }

    /** 去除HTML标签，保留纯文本 */
    private static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
    }


    // ==================== 彩蛋/超自然 ====================

    private String executeSurprise(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "彩蛋内容为空，跳过。";
        }
        String text = content.trim();
        EdtUtils.println(new Color(255, 180, 220), "\n  [彩蛋] " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                try {
                    new sair.aiagent.ui.SurpriseWindow(text).display();
                } catch (Exception e) {
                    EdtUtils.println(FCM.Error_Color, "  [彩蛋] 弹窗失败: " + e.toString());
                }
            }
        });
        return "彩蛋已弹出。";
    }

    // ==================== 提示词修改 ====================

    private String executeEditPrompt(String newPrompt) {
        if (newPrompt == null || newPrompt.trim().isEmpty()) {
            return "提示词内容为空，未修改。";
        }
        String prompt = newPrompt.trim();
        if (prompt.length() < 30) {
            return "提示词太短（" + prompt.length() + " 字符），需要至少 30 字符。请保持性格连续性，变化不要太大。";
        }

        EdtUtils.println(new Color(200, 180, 255), "\n  [修改提示词] 长度: " + prompt.length() + " 字符");

        try {
            AiConfig.getInstance().setSystemPrompt(prompt);
            AiConfig.getInstance().save();
            return "系统提示词已更新（" + prompt.length() + " 字符）。新个性已生效。\n提示：变化不要太大，保持性格连续性。";
        } catch (Exception e) {
            return "提示词更新失败: " + e.getMessage();
        }
    }

    // ==================== 媒体标签 ====================

    private String executeSendImage(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "[sendimage] 内容为空，无法渲染";
        }
        if (sair.aiagent.util.ImageRenderer.isHeadless()) {
            return "[sendimage] 当前环境无图形界面，无法渲染图片。内容: " + content;
        }
        
        String text = content.trim();
        EdtUtils.println(new Color(150, 200, 255), "\n  [渲染图片] " + (text.length() > 40 ? text.substring(0, 40) + "..." : text));
        
        try {
            String dataDir = selfActivity.getDataDir();
            File outputDir = new File(dataDir, "rendered");
            String fileName = "img_" + System.currentTimeMillis() + ".png";
            File outputFile = new File(outputDir, fileName);
            
            sair.aiagent.util.ImageRenderer.renderTextToImage(text, outputFile);
            
            String absPath = outputFile.getAbsolutePath();
            if (qqExecsCallback != null) {
                qqExecsCallback.accept("[IMAGE]" + absPath);
            }
            return "[sendimage] 图片已渲染: " + absPath;
        } catch (Exception e) {
            return "[sendimage] 渲染失败: " + e.getMessage();
        }
    }

    private String executeSendRecord(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "[sendrecord] 语音文件路径为空";
        }
        String path = content.trim();
        EdtUtils.println(new Color(200, 180, 255), "\n  [发送语音] " + path);
        
        if (qqExecsCallback != null) {
            qqExecsCallback.accept("[RECORD]" + path);
        }
        return "[sendrecord] 语音消息路径已传递: " + path;
    }

    private String executeSendFile(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "[sendfile] 文件路径为空";
        }
        String filePath = content.trim();
        
        File file = new File(filePath);
        if (!file.exists()) {
            return "[sendfile] 文件不存在: " + filePath;
        }
        if (!file.isFile()) {
            return "[sendfile] 路径不是文件: " + filePath;
        }
        
        EdtUtils.println(new Color(255, 200, 150), "\n  [发送文件] " + filePath + " (" + formatSize(file.length()) + ")");
        
        if (qqExecsCallback != null) {
            qqExecsCallback.accept("[FILE]" + filePath + "|" + file.getName());
        }
        return "[sendfile] 文件路径已传递: " + filePath + " (" + formatSize(file.length()) + ")";
    }

    // ==================== 表情包标签 ====================

    private String executeSendSticker(String context) {
        if (stickerManager == null) return "[sendsticker] StickerManager not initialized";
        if (context == null || context.trim().isEmpty()) {
            return "[sendsticker] context empty, cannot match sticker";
        }
        EdtUtils.println(new Color(255, 200, 150), "\n  [Sticker] matching: "
                + (context.length() > 40 ? context.substring(0, 40) + "..." : context));

        StickerEntry match = stickerManager.findBestMatch(context.trim());
        if (match == null) {
            return "[sendsticker] no matching sticker for context";
        }
        if (qqExecsCallback != null) {
            String img = match.getImageUrl();
            if (img != null && !img.isEmpty()) {
                qqExecsCallback.accept("[STICKER]" + img + "|" + match.getId());
            }
        }
        return "[sendsticker] sticker #" + match.getId() + " sent";
    }

    private String executeCollectSticker(String content) {
        if (stickerManager == null) return "[collectsticker] StickerManager not initialized";
        if (content == null || content.trim().isEmpty()) {
            return "[collectsticker] need imageUrl";
        }
        String imageUrl;
        String ctx;
        int pipeIdx = content.indexOf("|");
        if (pipeIdx > 0) {
            imageUrl = content.substring(0, pipeIdx).trim();
            ctx = content.substring(pipeIdx + 1).trim();
        } else {
            imageUrl = content.trim();
            ctx = "";
        }
        StickerEntry entry = stickerManager.collect(imageUrl, ctx);
        if (entry != null) return "[collectsticker] collected #" + entry.getId();
        return "[collectsticker] failed";
    }

    /** Called by QQMessageHandler when image appears in chat */
    public void collectStickerFromQQ(String imageUrl, String context) {
        if (stickerManager == null) return;
        stickerManager.collect(imageUrl, context);
    }

    // ==================== 停止 ====================

    private String executeStop() {
        EdtUtils.println(FCM.Error_Color, "\n  [停止] Agent执行已被中断");
        // AgentExecutor.markStopped() 由 AgentExecutor 自己处理
        return "[STOP] Agent执行已被中断。";
    }

    // ==================== 静态工具方法 ====================

    /** 从 SFW 命令字符串提取插件名，格式: pluginName/funcName args */
    static String extractPluginName(String command) {
        if (command == null) return null;
        String trimmed = command.trim();
        int slashIdx = trimmed.indexOf('/');
        if (slashIdx <= 0) return null;
        return trimmed.substring(0, slashIdx);
    }

    /** 从 URL 提取文件名，过滤路径穿越字符 */
    static String extractFileName(String url) {
        try {
            String path = new java.net.URI(url).getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String name = path.substring(path.lastIndexOf('/') + 1);
                if (!name.isEmpty()) {
                    try {
                        name = java.net.URLDecoder.decode(name, "UTF-8");
                    } catch (Exception ignored) {}
                    name = name.replaceAll("[/\\\\]", "_")
                               .replaceAll("\\.\\.", "__")
                               .replaceAll("\\x00", "");
                    name = name.trim().replaceAll("^\\.+", "_").replaceAll("\\.+$", "_");
                    if (name.isEmpty()) name = "download";
                    return name;
                }
            }
        } catch (Exception ignored) {}
        return "download_" + Math.abs(url.hashCode());
    }

    /** 格式化文件大小 */
    static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** SSRF 防护：检查域名和实际解析 IP 是否为内网地址 */
    static boolean isInternalHost(String host) {
        if (host == null || host.isEmpty()) return true;
        String lower = host.toLowerCase();
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("0.0.0.0")) return true;
        if (lower.startsWith("10.") || lower.startsWith("192.168.")) return true;
        if (lower.startsWith("172.")) {
            try {
                int second = Integer.parseInt(lower.substring(4, lower.indexOf('.', 4)));
                if (second >= 16 && second <= 31) return true;
            } catch (Exception ignored) {}
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (ip == null) return false;
            if (ip.equals("127.0.0.1") || ip.equals("0.0.0.0") || ip.startsWith("10.") || ip.startsWith("192.168.")) return true;
            if (ip.startsWith("172.")) {
                int dotIdx = ip.indexOf('.', 4);
                if (dotIdx > 0) {
                    int second = Integer.parseInt(ip.substring(4, dotIdx));
                    if (second >= 16 && second <= 31) return true;
                }
            }
            if (ip.startsWith("169.254.")) return true;
            if (ip.equals("::1") || ip.startsWith("fe80:") || ip.startsWith("fc") || ip.startsWith("fd")) return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** 提取 before→after 的增量输出 */
    static String extractDiff(String before, String after) {
        if (before == null || after == null) return after;
        if (before.equals(after)) return "(无变化)";
        String diff = after;
        if (after.startsWith(before) && after.length() > before.length()) {
            diff = after.substring(before.length()).trim();
            if (diff.isEmpty()) diff = after;
        }
        if (diff.length() > 3000) {
            diff = diff.substring(0, 3000) + "\n…(输出过长，已截断至 3000 字符)";
        }
        return diff.isEmpty() ? after : diff;
    }
}
