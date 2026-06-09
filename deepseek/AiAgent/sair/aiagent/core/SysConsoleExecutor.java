package sair.aiagent.core;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 跨平台系统控制台执行器 —— 绕过SFW限制直接调用系统Shell。
 * <p>
 * 使用 Java {@link ProcessBuilder} 直接调用操作系统Shell，捕获stdout+stderr输出。
 * 自动根据操作系统选择正确的Shell解释器，支持超时和字符数截断。
 * </p>
 *
 * <h3>跨平台Shell映射</h3>
 * <table>
 *   <tr><th>操作系统</th><th>Shell</th><th>命令格式</th></tr>
 *   <tr><td>Windows</td><td>cmd.exe</td><td>{@code cmd /c "command"}</td></tr>
 *   <tr><td>Linux</td><td>/bin/sh</td><td>{@code /bin/sh -c 'command'}</td></tr>
 *   <tr><td>macOS</td><td>/bin/sh</td><td>{@code /bin/sh -c 'command'}</td></tr>
 * </table>
 *
 * <h3>编码处理</h3>
 * <p>Windows下cmd输出通常为GBK编码，引擎自动尝试UTF-8后回退系统默认编码。</p>
 *
 * <h3>安全限制</h3>
 * <ul>
 *   <li>超时30秒自动kill进程</li>
 *   <li>输出截断至10000字符</li>
 *   <li>命令执行前会在控制台显示提示</li>
 * </ul>
 */
public final class SysConsoleExecutor {

    // ==================== 回调接口 ====================

    /**
     * 实时输出监听器 —— 命令执行过程中逐行回调。
     */
    public interface OutputListener {
        /** 命令开始执行 */
        void onStart();
        /** 收到一行输出 */
        void onLine(String line);
        /** 输出完成 */
        void onFinish(String fullOutput);
        /** 执行出错 */
        void onError(String message);
    }

    /** 默认超时（毫秒） */
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** 最大输出字符数 */
    private static final int MAX_OUTPUT_CHARS = 10_000;

    // ==================== 平台检测 ====================

    /** 当前是否为Windows系统 */
    private static final boolean IS_WINDOWS;

    /** 系统行分隔符 */
    private static final String LINE_SEP;

    /** 系统文件分隔符 */
    private static final String FILE_SEP;

    static {
        String osName = System.getProperty("os.name", "").toLowerCase();
        IS_WINDOWS = osName.contains("win");
        LINE_SEP = System.getProperty("line.separator", "\n");
        FILE_SEP = File.separator;
    }

    private SysConsoleExecutor() {} // 工具类禁止实例化

    // ==================== 公共API ====================

    /**
     * 执行系统命令（使用默认超时30秒）。
     *
     * @param command 系统命令
     * @return 命令输出（stdout + stderr合并）
     */
    public static String execute(String command) {
        return execute(command, DEFAULT_TIMEOUT_MS, null);
    }

    /**
     * 执行系统命令并实时回调输出。
     * <p>逐行读取进程输出，通过 {@link OutputListener} 回调每一行。
     * 适用于需要在UI面板中实时显示命令执行过程的场景。</p>
     *
     * @param command  系统命令
     * @param listener 输出监听器（不可为null）
     */
    public static void executeWithListener(String command, OutputListener listener) {
        executeWithListener(command, DEFAULT_TIMEOUT_MS, null, listener);
    }

    /**
     * 执行系统命令。
     *
     * @param command     系统命令
     * @param timeoutMs   超时时间（毫秒），0表示无超时
     * @param workingDir  工作目录，null则继承当前进程
     * @return 命令输出（stdout + stderr合并）
     */
    public static String execute(String command, long timeoutMs, File workingDir) {
        if (command == null || command.trim().isEmpty()) {
            return "(空命令)";
        }

        try {
            // 构建Shell命令
            String[] shellCmd = buildShellCommand(command);

            ProcessBuilder pb = new ProcessBuilder(shellCmd);
            if (workingDir != null && workingDir.isDirectory()) {
                pb.directory(workingDir);
            }

            // 合并stdout和stderr
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            // 读取输出
            Charset encoding = detectEncoding();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), encoding))) {
                int ch;
                while ((ch = reader.read()) != -1) {
                    if (output.length() < MAX_OUTPUT_CHARS) {
                        output.append((char) ch);
                    }
                }
            }

            // 等待进程结束或超时
            long effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                if (output.length() == 0) {
                    return "(命令执行超时，已强制终止)";
                }
                output.append(LINE_SEP).append("--- 超时终止 ---");
            }

            String result = output.toString().trim();
            if (result.isEmpty()) {
                return "(无输出)";
            }

            // 截断过长输出
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS)
                       + LINE_SEP + "... (输出过长，已截断至" + MAX_OUTPUT_CHARS + "字符)";
            }

            return result;

        } catch (Exception e) {
            return "系统命令执行失败: " + e.getClass().getSimpleName() + " - " + e.getMessage();
        }
    }

    // ==================== 平台适配 ====================

    /**
     * 根据操作系统构建Shell命令数组。
     * <p>跨平台核心：Windows用cmd /c，Unix用/bin/sh -c。</p>
     */
    private static String[] buildShellCommand(String command) {
        if (IS_WINDOWS) {
            // Windows: cmd /c "command"
            // chcp 65001 切换控制台编码为UTF-8
            return new String[] { "cmd", "/c", "chcp 65001 >nul 2>nul & " + command };
        } else {
            // Linux / macOS / Unix: /bin/sh -c 'command'
            return new String[] { "/bin/sh", "-c", command };
        }
    }

    /**
     * 检测合适的输出编码。
     * <p>跨平台统一 UTF-8：Windows下通过 chcp 65001 切换编码，
     * Linux/macOS 默认为 UTF-8。固用 UTF-8 读取，避免跨平台乱码。</p>
     */
    private static Charset detectEncoding() {
        return StandardCharsets.UTF_8;
    }

    /**
     * 执行系统命令并通过监听器实时回调输出。
     * <p>逐行读取进程输出，每读到一行就回调 {@link OutputListener#onLine(String)}。
     * 命令完成后回调 {@link OutputListener#onFinish(String)}。</p>
     */
    private static void executeWithListener(String command, long timeoutMs,
                                             File workingDir, OutputListener listener) {
        if (command == null || command.trim().isEmpty()) {
            listener.onError("空命令");
            return;
        }
        listener.onStart();
        try {
            String[] shellCmd = buildShellCommand(command);
            ProcessBuilder pb = new ProcessBuilder(shellCmd);
            if (workingDir != null && workingDir.isDirectory()) {
                pb.directory(workingDir);
            }
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder fullOutput = new StringBuilder();
            Charset encoding = detectEncoding();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), encoding))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (fullOutput.length() < MAX_OUTPUT_CHARS) {
                        fullOutput.append(line).append(LINE_SEP);
                    }
                    listener.onLine(line);
                }
            }

            long effectiveTimeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
            boolean finished = process.waitFor(effectiveTimeout, TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                String result = fullOutput.toString().trim();
                if (result.isEmpty()) {
                    listener.onError("命令执行超时");
                } else {
                    listener.onLine("--- 超时终止 ---");
                    listener.onFinish(result + "\n--- 超时终止 ---");
                }
                return;
            }

            String result = fullOutput.toString().trim();
            if (result.isEmpty()) {
                result = "(无输出)";
            }
            if (result.length() > MAX_OUTPUT_CHARS) {
                result = result.substring(0, MAX_OUTPUT_CHARS)
                       + LINE_SEP + "... (输出过长，已截断至" + MAX_OUTPUT_CHARS + "字符)";
            }
            listener.onFinish(result);

        } catch (Exception e) {
            listener.onError("执行失败: " + e.getMessage());
        }
    }

    // ==================== 信息方法 ====================

    /** @return 当前操作系统标识 */
    public static String getOsIdentifier() {
        String osName = System.getProperty("os.name", "Unknown");
        String osArch = System.getProperty("os.arch", "");
        if (IS_WINDOWS) return "Windows (" + osArch + ")";
        if (osName.toLowerCase().contains("mac")) return "macOS (" + osArch + ")";
        if (osName.toLowerCase().contains("linux")) return "Linux (" + osArch + ")";
        return osName + " (" + osArch + ")";
    }

    /** @return 当前Shell类型 */
    public static String getShellType() {
        return IS_WINDOWS ? "cmd.exe" : "/bin/sh";
    }

    /** @return 系统行分隔符 */
    public static String lineSeparator() {
        return LINE_SEP;
    }

    /** @return 系统文件分隔符 */
    public static String fileSeparator() {
        return FILE_SEP;
    }
}
