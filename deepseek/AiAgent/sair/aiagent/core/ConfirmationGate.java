package sair.aiagent.core;

import java.awt.Color;
import sair.aiagent.util.EdtUtils;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


import sair.FCM;

/**
 * 高危操作确认门控 —— 统一的用户确认机制。
 * <p>
 * 对反射、系统命令、动态代码注入等高危操作，Agent 执行前挂起等待用户
 * 输入 {@code ai/yes} 或 {@code ai/no}，60 秒超时自动拒绝。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 *   if (!gate.await("sys", "执行系统命令: rm -rf /", 60_000)) {
 *       return "操作被拒绝。";
 *   }
 *   // 执行高危操作...
 * }</pre>
 */
public class ConfirmationGate {

    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    private static final Color C_WARN  = new Color(255, 140, 0);
    private static final Color C_DESC  = new Color(255, 200, 60);
    private static final Color C_EXEC  = new Color(100, 255, 100);

    /** 确认结果：null=等待中，true=允许，false=拒绝 */
    private volatile Boolean result;
    private CountDownLatch latch;
    private volatile String pendingType;
    private volatile String pendingDesc;

    /** 绕过确认模式：true 时 await() 直接返回 true，不阻塞等待用户 */
    private volatile boolean bypassConfirm = false;

    /**
     * 阻塞等待用户确认。
     *
     * @param type        操作类型标签（如 "sys", "evaljava"）
     * @param description 操作描述（展示给用户）
     * @param timeoutMs   超时毫秒数
     * @return true=用户确认，false=拒绝或超时
     */
    public boolean await(String type, String description, long timeoutMs) {
        // 绕过确认模式：直接允许
        if (bypassConfirm) {
            EdtUtils.println(new Color(180, 180, 180), "  [自动允许] " + type + ": " + description);
            return true;
        }

        // 显示确认提示（一次 invokeAndWait 原子打印，避免流式字符穿插打断分隔符）
        EdtUtils.printlnLines(
            FCM.split_Color, "\n══════════════════════════════════════",
            C_WARN, "⚠ AI 请求高危操作 [" + type + "] ——",
            C_DESC, description,
            C_WARN, "⚠ 是否允许？输入 ai/yes 确认 / ai/no 拒绝 (" + (timeoutMs / 1000) + "秒超时自动拒绝)",
            FCM.split_Color, "══════════════════════════════════════\n"
        );

        // ★ 仅在同步块内设置状态，完成后释放监视器再等待
        synchronized (this) {
            latch = new CountDownLatch(1);
            result = null;
            pendingType = type;
            pendingDesc = description;
        }

        try {
            // ★ latch.await() 在同步块外，不持有 this 监视器 → confirm() 可正常进入
            boolean confirmed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            synchronized (this) {
                if (!confirmed || result == null || !result) {
                    pendingType = null;
                    pendingDesc = null;
                    latch = null;
                    result = null;
                    return false;
                }
                pendingType = null;
                pendingDesc = null;
                latch = null;
                result = null;
            }
        } catch (InterruptedException e) {
            synchronized (this) {
                pendingType = null;
                pendingDesc = null;
                latch = null;
                result = null;
            }
            Thread.currentThread().interrupt();
            return false;
        }

        EdtUtils.println(C_EXEC, "▶ " + type + " 执行中...");
        return true;
    }

    /**
     * 使用默认超时（60秒）等待确认。
     */
    public boolean await(String type, String description) {
        return await(type, description, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 设置是否绕过确认（execs 模式）。
     * @param bypass true=自动允许所有高危操作
     */
    public void setBypassConfirm(boolean bypass) {
        this.bypassConfirm = bypass;
    }

    /** @return 当前是否处于绕过确认模式 */
    public boolean isBypassConfirm() {
        return bypassConfirm;
    }

    /**
     * 用户确认回调（由 ai/yes 或 ai/no 触发）。
     */
    public synchronized void confirm(boolean allowed) {
        if (latch != null && result == null) {
            result = allowed;
            latch.countDown();
        }
    }

    /** @return 是否有待确认的操作 */
    public synchronized boolean isAwaiting() {
        return latch != null && latch.getCount() > 0;
    }

    /** @return 待确认操作的类型标签 */
    public synchronized String getPendingType() {
        return pendingType;
    }

    /** @return 待确认操作的描述 */
    public synchronized String getPendingDesc() {
        return pendingDesc;
    }
}
