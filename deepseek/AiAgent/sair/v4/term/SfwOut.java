package sair.v4.term;

import java.awt.Color;

import sair.v4.Conf;
import sair.v4.kit.Out;
import sair.sys.SairCons;

/**
 * SFW 控制台输出（基板⑨）：把基板的语义色映射到框架控制台。
 * <p>这是基板的控制台门面：<b>写</b>（{@link #print}）与 <b>读/执行</b>
 * （{@link #read}／{@link #run}／{@link #history}／{@link #size}）都在这里，
 * 真正的机制在 {@link ConsoleTap}（流式捕获 + 游标读取 + 框架命令桥）。
 * 无界面环境（探针）用 {@code PlainOut} 顶替。</p>
 *
 * <p><b>构造即装捕获器</b>：{@code new SfwOut()} 幂等地挂上控制台捕获器
 * （{@link ConsoleTap#install(Conf)}，重复构造不会重复注册；装不上就静默降级）。
 * 基板装配到第⑮步（基板工具面）时再用 {@link #installTap(Conf)} 套上配置里的上限，
 * 基板停摆时由 {@code Th.shutdown()} 注销（{@link #uninstallTap()}）—— 框架的代理表里不留悬挂 runnable。</p>
 */
public final class SfwOut implements Out {

    private static final String TAG = "[AiAgentV4] ";

    public SfwOut() {
        // 幂等：任何一条装配/重启路径 new 出来的 SfwOut 都会确保捕获器挂着
        try {
            ConsoleTap.install(null);
        } catch (Throwable ignored) {
            // 无界面环境装不上：基板不能因为捕获器就起不来
        }
    }

    @Override
    public void print(String text, Tone tone) {
        if (text == null || text.isEmpty()) return;
        try {
            Color c = color(tone);
            // 一律交给框架的打印分发（insertPrinto）：
            //  · 我们的捕获器挂着 → 框架不再自己写，把这条交给代理 → 我们收进队列、在 EDT 上落盘（单写者）；
            //  · 没挂着（无界面探针 / 装了但失败）→ 走框架原路：有别的代理就交给它（探针的捕获代理靠这条拿到输出），
            //    一个代理都没有才由框架自己打印。
            // 千万别在这里绕过 insertPrinto 直接插文档：那会让别的打印代理（例如探针）一条都收不到。
            if (c == null) SairCons.print(text);
            else SairCons.print(c, text);
        } catch (Throwable ignored) {
            // 控制台不可用（无 GUI）时静默：基板不能因为输出挂了就停摆
        }
    }

    /** 带前缀的诊断输出。 */
    public void log(String text, Tone tone) {
        print(TAG + text + "\n", tone);
    }

    public void log(String text) { log(text, Tone.DIM); }

    public static Color color(Tone tone) {
        if (tone == null) return null;
        switch (tone) {
            case DIM: return Color.GRAY;
            case OK: return Color.GREEN;
            case WARN: return Color.ORANGE;
            case ERR: return Color.RED;
            case TITLE: return Color.CYAN;
            default: return null;
        }
    }

    /** 清屏（控制台的 /clear 走框架命令；捕获缓冲不受影响 —— 清完还读得到最近输出）。 */
    public void clear() {
        // 清屏是文档操作，同样只能在 EDT 上做（理由见 print）
        try {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) {
                SairCons.runner(false, "/clear");
            } else {
                javax.swing.SwingUtilities.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            SairCons.runner(false, "/clear");
                        } catch (Throwable ignored) {
                        }
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    // ==================== 读半边（基板⑨ 的原生控制台交互） ====================

    /** 装捕获器并套上配置上限（幂等；装配期调用）。返回是否处于"已挂上"。 */
    public static boolean installTap(Conf conf) {
        return ConsoleTap.install(conf);
    }

    /** 注销捕获器（幂等；{@code Th.shutdown()} = {@code Boot.stop()} 的收敛点调用）。 */
    public static void uninstallTap() {
        ConsoleTap.uninstall();
    }

    /** 捕获器挂上了吗。 */
    public static boolean tapInstalled() { return ConsoleTap.installed(); }

    /** SFW 控制台可用吗（无界面环境返回 false，不抛异常）。 */
    public static boolean available() { return ConsoleTap.available(); }

    /** 当前输出游标（把返回值当下次 {@code since_seq} 传回来，就只拿新增）。 */
    public static long cursor() { return ConsoleTap.cursor(); }

    /**
     * 游标式读控制台输出（事实行 + 正文）。
     *
     * @param sinceSeq 上次读到的 seq（{@code <=0} = 从缓冲里最旧的一条开始）
     * @param maxChars 本次最多回多少字符（{@code <=0} = 配置 {@code consoleTapReadChars}）
     * @param filter   非空时只保留含该串的输出行
     */
    public static String read(long sinceSeq, int maxChars, String filter) {
        return ConsoleTap.readWithFacts(sinceSeq, maxChars, filter);
    }

    /** 「当前可见全文」（{@link SairCons#getConsoleText()}，按需调用，务必给上限；见 ConsoleTap 的说明）。 */
    public static String visibleText(int maxChars) {
        return ConsoleTap.visibleText(maxChars);
    }

    /** 执行一条 SFW 框架命令，并回它打出来的新输出（{@link ConsoleTap#run(String)}）。 */
    public static String run(String cmd) {
        return ConsoleTap.run(cmd);
    }

    /** 框架命令历史最近 N 条（{@code SairCons.localRunnerHistory}）。 */
    public static String history(int n) {
        return ConsoleTap.history(n);
    }

    /** 控制台规模（{@code SairCons.getConsoleSize()}）+ 捕获缓冲事实。 */
    public static String size() {
        return ConsoleTap.size();
    }

    /** 捕获缓冲/开关的状态面。 */
    public static com.google.gson.JsonObject tapStatus() {
        return ConsoleTap.status();
    }
}
