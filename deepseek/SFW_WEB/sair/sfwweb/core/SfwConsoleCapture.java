package sair.sfwweb.core;

import sair.sys.SairCons;
import sair.user.PrintRunnable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SFW 控制台输出捕获器
 * 通过 PrintRunnable 拦截 SFW 控制台的所有输出，
 * 将输出内容（含颜色信息）缓存供 Web 端实时展示
 */
public class SfwConsoleCapture {

    private static final String PR_ID = "SFW_WEB_ConsoleCapture";

    /** 输出行存储 */
    private final List<ConsoleLine> outputLines = new CopyOnWriteArrayList<ConsoleLine>();

    /** SSE 订阅者列表 */
    private final List<SseSubscriber> subscribers = new CopyOnWriteArrayList<SseSubscriber>();

    /** 是否已注册到 SFW */
    private boolean registered = false;

    private int maxLines = 5000;

    /** SSE 订阅者接口 */
    public interface SseSubscriber {
        void onNewLine(ConsoleLine line);
        void onConsoleCleared();
    }

    /** 控制台输出行 */
    public static class ConsoleLine {
        public final String text;
        public final String colorHex; // 颜色十六进制，如 "#00FF41"
        public final long timestamp;

        public ConsoleLine(String text, String colorHex) {
            this.text = text;
            this.colorHex = colorHex;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /**
     * 初始化并注册到 SFW 控制台
     */
    public void init(int maxLines) {
        this.maxLines = maxLines;
        if (!registered) {
            SairCons.addPrintRunnable(PR_ID, new PrintRunnable() {
                @Override
                public void run(Integer index, Color c, String info) {
                    handleConsoleOutput(index, c, info);
                }
            });
            registered = true;
        }
    }

    /**
     * 处理 SFW 控制台输出
     */
    private void handleConsoleOutput(Integer index, Color c, String info) {
        if (info == null) return;

        String colorHex = colorToHex(c);
        ConsoleLine line = new ConsoleLine(info, colorHex);

        outputLines.add(line);

        // 限制最大输出行数
        while (outputLines.size() > maxLines) {
            outputLines.remove(0);
        }

        // 通知所有 SSE 订阅者
        for (SseSubscriber sub : subscribers) {
            try {
                sub.onNewLine(line);
            } catch (Exception ignored) {}
        }
    }

    /**
     * 添加 SSE 订阅者
     */
    public void addSubscriber(SseSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    /**
     * 移除 SSE 订阅者
     */
    public void removeSubscriber(SseSubscriber subscriber) {
        subscribers.remove(subscriber);
    }

    /**
     * 获取所有输出行快照
     */
    public List<ConsoleLine> getOutputLines() {
        return new ArrayList<ConsoleLine>(outputLines);
    }

    /**
     * 获取最近的输出行
     */
    public List<ConsoleLine> getRecentLines(int count) {
        List<ConsoleLine> all = getOutputLines();
        int from = Math.max(0, all.size() - count);
        return all.subList(from, all.size());
    }

    /**
     * 获取完整控制台文本（用于初始加载）
     */
    public String getFullText() {
        StringBuilder sb = new StringBuilder();
        for (ConsoleLine line : outputLines) {
            sb.append(line.text);
        }
        return sb.toString();
    }

    /**
     * 清空控制台输出
     */
    public void clear() {
        outputLines.clear();
        for (SseSubscriber sub : subscribers) {
            try {
                sub.onConsoleCleared();
            } catch (Exception ignored) {}
        }
    }

    /**
     * Color 转十六进制字符串
     */
    private String colorToHex(Color c) {
        if (c == null) {
            // 使用默认前景色
            return ConfigManager.DEFAULT_FONT_COLOR;
        }
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /**
     * 注销捕获器
     */
    public void shutdown() {
        SairCons.removePrintRunnable(PR_ID);
        registered = false;
        subscribers.clear();
        outputLines.clear();
    }
}
