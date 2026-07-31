package sair.sfwweb.core;

import sair.sys.SairCons;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SFW 命令处理器
 * 处理来自 Web 端的命令请求，转发给 SFW 执行
 * 支持命令历史记录
 */
public class CommandHandler {

    private static final int MAX_HISTORY = 500;

    private final List<String> commandHistory = Collections.synchronizedList(new ArrayList<String>());
    private final SfwConsoleCapture consoleCapture;
    private final AtomicBoolean isExecuting = new AtomicBoolean(false);

    public CommandHandler(SfwConsoleCapture consoleCapture) {
        this.consoleCapture = consoleCapture;
    }

    /**
     * 执行 SFW 命令
     * @param command SFW 命令字符串
     * @return 执行结果描述
     */
    public CommandResult execute(String command) {
        if (command == null || command.trim().isEmpty()) {
            return new CommandResult(false, "Empty command");
        }

        command = command.trim();
        addHistory(command);

        if (isExecuting.get()) {
            return new CommandResult(false, "Another command is already executing");
        }

        isExecuting.set(true);
        try {
            // 通过 SFW 的 runner 执行命令
            Object result = SairCons.runner(true, command);

            return new CommandResult(true, result != null ? result.toString() : "OK");
        } catch (Exception e) {
            return new CommandResult(false, "Execution error: " + e.getMessage());
        } finally {
            isExecuting.set(false);
        }
    }

    /**
     * 获取命令历史
     */
    public List<String> getHistory() {
        synchronized (commandHistory) {
            return new ArrayList<String>(commandHistory);
        }
    }

    /**
     * 获取最近的命令历史
     */
    public List<String> getRecentHistory(int count) {
        List<String> all = getHistory();
        int from = Math.max(0, all.size() - count);
        return all.subList(from, all.size());
    }

    /**
     * 添加命令到历史
     */
    private void addHistory(String command) {
        synchronized (commandHistory) {
            commandHistory.add(command);
            while (commandHistory.size() > MAX_HISTORY) {
                commandHistory.remove(0);
            }
        }
    }

    /**
     * 清空命令历史
     */
    public void clearHistory() {
        synchronized (commandHistory) {
            commandHistory.clear();
        }
    }

    /**
     * 是否正在执行命令
     */
    public boolean isExecuting() {
        return isExecuting.get();
    }

    /**
     * 获取控制台捕获器
     */
    public SfwConsoleCapture getConsoleCapture() {
        return consoleCapture;
    }

    /**
     * 命令执行结果
     */
    public static class CommandResult {
        public final boolean success;
        public final String message;

        public CommandResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
