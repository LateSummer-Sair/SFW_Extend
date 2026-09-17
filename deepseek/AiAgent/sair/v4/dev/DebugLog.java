package sair.v4.dev;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import sair.v4.Conf;
import sair.v4.kit.Fs;
import sair.v4.kit.Str;

/**
 * 调试口的<b>落盘</b>半边（基板⑨ 的旁路，只由 {@link DebugPort} 安装）。
 *
 * <h3>为什么要有它</h3>
 * <p>内存里的两路捕获都是有界环形缓冲（控制台 {@code ConsoleTap} 默认 500 条 / 64KB，
 * 对话 {@link DebugPort} 默认 2000 条 / 256KB）—— 它们回答的是"最近发生了什么"，
 * <b>回答不了"从起实例到现在一共发生了什么"</b>。所以调试输出口要能"一次取全部输出"，
 * 就必须把每一行同时写进文件，再由 {@code dump} / {@code talkdump} 从文件里读。</p>
 *
 * <h3>落点与滚动策略（写清楚，不含糊）</h3>
 * <ul>
 *   <li><b>落点</b>：{@code <数据根>\logs\}，两个文件 ——
 *       {@code console-<yyyyMMdd>.log}（控制台捕获的每一行）与
 *       {@code talk-<yyyyMMdd>.log}（对话/聊天框的每一条：user / stream / say / say-dup /
 *       notice-tool / notice-turn / end）；</li>
 *   <li><b>按天</b>：文件名带日期，跨零点自动换到当天的文件；</li>
 *   <li><b>按大小滚动</b>：单个片段超过 {@value #ROTATE_BYTES} 字节（8MB）就<b>封存</b> ——
 *       改名为 {@code <kind>-<yyyyMMdd>-<HHmmssSSS>.log}，然后开一个新的当天片段；</li>
 *   <li><b>只增不删</b>：本类<b>从不删除任何日志文件</b>（连最旧的分片也保留，只是改名归档）。
 *       所以长期运行 {@code logs\} 会持续变大 —— 要清理请人工删（本类是调试面，不做清理策略）；</li>
 *   <li><b>读取顺序</b>（{@code dump}/{@code talkdump} 用）：先按日期升序，同一天里先按"封存时刻"
 *       升序，当前片段排在同一天的最后 —— 也就是与写入顺序完全一致。</li>
 * </ul>
 *
 * <h3>行格式（一行一条记录；记录的正文里可以有真的换行）</h3>
 * <pre>
 * console:  #&lt;seq&gt; &lt;yyyy-MM-dd HH:mm:ss.SSS&gt; [&lt;tone&gt;] &lt;thread&gt; | &lt;text&gt;
 * talk:     #&lt;seq&gt; &lt;yyyy-MM-dd HH:mm:ss.SSS&gt; [&lt;kind&gt;] &lt;text&gt;
 * </pre>
 * <p>记录正文原样保留（<b>不</b>把换行转义掉，读回来与当时打出来的一致）。因此"这一行是不是一条新记录"
 * 的判据是<b>形状 + 单调</b>两条同时成立：行首形如 {@code #<数字> <yyyy-MM-dd> }，且那个数字
 * <b>严格大于</b>上一条记录的 seq。正文里偶然出现同形状的行不会被误判。</p>
 *
 * <h3>游标的连续性（重启不重置）</h3>
 * <p>seq 不只在本次进程内自增：{@link #install} 会先把该日志<b>最新那个片段</b>的最大 seq 读出来，
 * 从它的下一位继续。这样同一天里重启/换实例之后，游标仍然单调 —— 否则 {@code dump 0} 会把两次
 * 运行的 seq 混在一起，{@code since_seq} 也就没法续取了。</p>
 *
 * <h3>边界</h3>
 * <p>{@link #install} 之前一切写入都是空操作（{@link #enabled()} 为 false）—— 所以探针、
 * 无界面进程、以及"调试口没开"的基板，一个字节都不会写盘。所有方法 {@code catch (Throwable)}：
 * 落盘是观测面，<b>绝不能</b>影响打印本身。</p>
 */
public final class DebugLog {

    private DebugLog() { }

    /** 日志目录名（数据根下）。 */
    public static final String DIR_NAME = "logs";

    /** 控制台日志的类别名。 */
    public static final String CONSOLE = "console";

    /** 对话日志的类别名。 */
    public static final String TALK = "talk";

    /** 单片段滚动阈值：8MB。 */
    public static final long ROTATE_BYTES = 8L * 1024L * 1024L;

    /** 单次 {@code dump}/{@code talkdump} 的响应字符上限：4MB（超了给续取游标）。 */
    public static final int MAX_DUMP_CHARS = 4 * 1024 * 1024;

    // ==================== 状态 ====================

    private static final Object LOCK = new Object();

    /** 装上了吗（{@link #install} 成功且未 {@link #stop()}）。 */
    private static volatile boolean on = false;

    private static File dir;
    private static Writer wConsole;
    private static Writer wTalk;
    private static File fConsole;
    private static File fTalk;
    private static long seqConsole = 0L;
    private static long seqTalk = 0L;
    private static long rowsConsole = 0L;
    private static long rowsTalk = 0L;
    private static String note = "";

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd");
    private static final SimpleDateFormat SEAL = new SimpleDateFormat("HHmmssSSS");
    private static final SimpleDateFormat STAMP = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

    private static final java.util.regex.Pattern CUR_P =
            java.util.regex.Pattern.compile("^(console|talk)-(\\d{8})\\.log$");
    /** 封存分片：{@code <kind>-<yyyyMMdd>-<HHmmssSSS>.log}；同一毫秒撞名时带 {@code _n} 后缀（照旧认）。 */
    private static final java.util.regex.Pattern SEALED_P =
            java.util.regex.Pattern.compile("^(console|talk)-(\\d{8})-(\\d{9})(_\\d+)?\\.log$");

    // ==================== 生命周期 ====================

    /**
     * 装上落盘（幂等：重复调用先关旧的）。由<b>壳层</b>调（{@link #ensure(File)} ←
     * {@code DebugShell.attach} ← {@code V4Activity}）。
     *
     * <p><b>它与调试口已经解耦</b>（主人 2026-09-16 追加裁定）：落盘是"日志"这个产品能力
     * （日志只增不删是工程规矩），不是调试口的一部分 —— 所以正常启动（没敲过 {@code ai/debug on}）
     * 也照写日志，{@code ai/debug off} 也不停它、更不删任何文件。只有壳卸载
     * （{@link #stop()}）才关句柄。</p>
     *
     * @param root 数据根（{@code Boot.root()}）；null/取不到时退回当前目录
     * @param conf 配置（诊断用；当前不读键）
     * @return true = 可以落盘了
     */
    public static boolean install(File root, Conf conf) {
        synchronized (LOCK) {
            closeLocked();
            on = false;
            note = "";
            try {
                File r = root == null ? new File(".") : root;
                dir = new File(r, DIR_NAME);
                if (!dir.isDirectory() && !Fs.mkdirs(dir)) {
                    note = "建不了日志目录：" + dir.getAbsolutePath();
                    dir = null;
                    return false;
                }
                seqConsole = lastSeqOf(CONSOLE);
                seqTalk = lastSeqOf(TALK);
                rowsConsole = 0L;
                rowsTalk = 0L;
                on = true;
                return true;
            } catch (Throwable t) {
                note = String.valueOf(t);
                on = false;
                return false;
            }
        }
    }

    /** 关掉落盘（幂等；只有<b>壳卸载</b>走这里 —— {@code DebugShell.detach()} / {@code V4Activity.exit()}）。<b>只关句柄，不删任何文件。</b> */
    public static void stop() {
        synchronized (LOCK) {
            closeLocked();
            on = false;
        }
    }

    /**
     * <b>壳层</b>入口：确保落盘已装上（幂等 —— 已经装着、而且是同一个数据根，就什么都不做）。
     *
     * <p>谁调它：壳 {@code V4Activity}（{@code DebugShell.attach}）。<b>不</b>由
     * {@code ai/debug on} 调 —— 落盘与那两个口已经解耦（主人 2026-09-16 追加裁定）：
     * 口开着关着日志都写；{@code ai/debug off} 只停口、不停落盘、不删文件。</p>
     *
     * <p>幂等很要紧：{@code ai/start} / {@code ai/restart} 每次都会走一遍
     * （数据根可能在新壳上重算）。重复 {@link #install} 会重开文件句柄并重读一遍
     * "最新片段的最大 seq"（日志只增不删，不该被多余地重开）。</p>
     *
     * @param root 数据根；null = 当前目录
     * @return true = 可以落盘了
     */
    public static boolean ensure(File root) {
        File r = root == null ? new File(".") : root;
        File d = new File(r, DIR_NAME);
        synchronized (LOCK) {
            if (on && dir != null && dir.equals(d)) return true;
        }
        return install(r, null);
    }

    /** 现在会落盘吗。 */
    public static boolean enabled() { return on; }

    /** 日志目录（没装 = null）。 */
    public static File dir() { return dir; }

    /** 装不上时的原因（诊断；空串 = 没问题）。 */
    public static String note() { return note; }

    private static void closeLocked() {
        closeQ(wConsole);
        closeQ(wTalk);
        wConsole = null;
        wTalk = null;
        fConsole = null;
        fTalk = null;
    }

    private static void closeQ(java.io.Closeable w) {
        if (w == null) return;
        try { w.close(); } catch (Throwable ignored) { }
    }

    // ==================== 写入 ====================

    /**
     * 记一条控制台输出（{@code ConsoleTap.accept} 每次调用都会到这里）。
     *
     * <p>与环形缓冲的开关<b>无关</b>：{@code consoleTapEnabled=false} 只是"不记内存里那条 500 行的窗口"，
     * 落盘是调试输出口自己的容量，照记（否则"一次取全部输出"会被一个无关开关悄悄关掉）。</p>
     */
    public static void console(long ms, String tone, String thread, String text) {
        if (!on) return;
        try {
            long s;
            synchronized (LOCK) {
                if (!on) return;
                s = ++seqConsole;
                rowsConsole++;
                writeRowLocked(CONSOLE, s, ms, tone, thread, text);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 记一条对话（{@code DebugPort.acceptTalk} 每次调用都会到这里）。 */
    public static void talk(long ms, String kind, String text) {
        if (!on) return;
        try {
            synchronized (LOCK) {
                if (!on) return;
                long s = ++seqTalk;
                rowsTalk++;
                writeRowLocked(TALK, s, ms, kind, null, text);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeRowLocked(String kind, long seq, long ms, String tag, String thread, String text) {
        try {
            File f = new File(dir, kind + "-" + DAY.format(new Date(ms)) + ".log");
            boolean console = CONSOLE.equals(kind);
            File cur = console ? fConsole : fTalk;
            Writer w = console ? wConsole : wTalk;
            if (cur == null || !cur.equals(f)) {
                closeQ(w);
                if (console) { wConsole = null; fConsole = f; } else { wTalk = null; fTalk = f; }
                w = open(f);
                if (w == null) return;
                if (console) wConsole = w; else wTalk = w;
                cur = f;
            }
            if (cur.length() >= ROTATE_BYTES) {
                // 封存旧片段（改名归档，绝不删除），再开一个新的当天片段
                closeQ(w);
                seal(cur, kind);
                w = open(cur);
                if (w == null) return;
                if (console) { wConsole = w; fConsole = cur; } else { wTalk = w; fTalk = cur; }
            }
            StringBuilder sb = new StringBuilder(128);
            sb.append('#').append(seq).append(' ').append(STAMP.format(new Date(ms)));
            if (!Str.blank(tag)) sb.append(" [").append(tag).append(']');
            if (!Str.blank(thread)) sb.append(' ').append(thread);
            sb.append(" | ");
            String body = text == null ? "" : text;
            sb.append(body);
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            w.write(sb.toString());
            w.flush();
        } catch (Throwable t) {
            note = String.valueOf(t);
        }
    }

    private static Writer open(File f) {
        try {
            return new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
        } catch (Throwable t) {
            note = "打不开日志文件：" + f.getAbsolutePath() + " → " + t;
            return null;
        }
    }

    /** 封存：{@code x.log} → {@code x-<HHmmssSSS>.log}（不删除；重名时加序号后缀）。 */
    private static void seal(File f, String kind) {
        try {
            String ts = SEAL.format(new Date(System.currentTimeMillis()));
            File to = new File(dir, kind + "-" + DAY.format(new Date()) + "-" + ts + ".log");
            int n = 1;
            while (to.exists() && n < 1000) {
                to = new File(dir, kind + "-" + DAY.format(new Date()) + "-" + ts + "_" + n + ".log");
                n++;
            }
            if (!f.renameTo(to)) {
                // 改不动名（被占/权限）：退回"截断续写"，但要留一行可读原因
                note = "封存日志失败（改名不成功）：" + f.getName() + " → " + to.getName();
            }
        } catch (Throwable t) {
            note = String.valueOf(t);
        }
    }

    // ==================== 读取 ====================

    /**
     * 一次性读全部（从落盘读，不受内存窗口限制）。
     *
     * @param talk    true = 对话日志，false = 控制台日志
     * @param sinceSeq 只回 seq 严格大于它的记录（{@code <=0} = 从盘上最早的一条开始）
     * @return 事实行 + 正文 + 尾事实行（含 {@code next_seq}；截断时给"还有 N 字符…续取"那一句）
     */
    public static String dump(boolean talk, long sinceSeq) {
        String kind = talk ? TALK : CONSOLE;
        File d = dir;
        List<File> files = d == null ? new ArrayList<File>() : orderFiles(d, kind);
        if (files.isEmpty()) {
            // 还没有落盘文件（调试口刚起来 / 这段时间一行输出都没有）：也要给与正常路径**同形**的
            // 头尾事实行 —— 客户端只认 next_seq=，不能让它在这里读到另一种形状。
            return "[" + kind + "dump] dir=" + (d == null ? "-" : d.getAbsolutePath())
                    + " files=0 seq_from=- seq_to=- rows=0 chars=0 cap=" + MAX_DUMP_CHARS
                    + " since_seq=" + sinceSeq
                    + "\n（还没有落盘文件：调试输出口刚起来，或者这段时间一行输出都没有）"
                    + "\n[" + kind + "dump] next_seq=" + sinceSeq + " more_chars=0 truncated=0";
        }
        StringBuilder body = new StringBuilder(8192);
        int used = 0;
        boolean cut = false;
        long remaining = 0L;
        long rows = 0L;
        long firstSeq = -1L;
        long lastSeq = -1L;
        long cur = -1L;
        for (File f : files) {
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 16);
                String line;
                while ((line = r.readLine()) != null) {
                    long s = rowSeq(line, cur);
                    boolean isRow = s > 0;
                    if (isRow) cur = s;
                    if (cur <= sinceSeq) continue;
                    int len = line.length() + 1;
                    if (cut) { remaining += len; continue; }
                    if (used + len > MAX_DUMP_CHARS) {
                        if (used == 0 && isRow) {
                            // 单条记录就超上限：也要回一点，否则游标永远不动（死循环）
                            body.append(line, 0, Math.min(line.length(), MAX_DUMP_CHARS)).append('\n');
                            used += Math.min(len, MAX_DUMP_CHARS + 1);
                            rows++;
                            if (firstSeq < 0) firstSeq = cur;
                            lastSeq = cur;
                        }
                        cut = true;
                        remaining += len;
                        continue;
                    }
                    body.append(line).append('\n');
                    used += len;
                    if (isRow) {
                        rows++;
                        if (firstSeq < 0) firstSeq = cur;
                        lastSeq = cur;
                    }
                }
            } catch (Throwable t) {
                body.append("…(读不动 ").append(f.getName()).append("：")
                        .append(Str.oneLine(String.valueOf(t))).append(")\n");
            } finally {
                closeQ(r);
            }
        }
        StringBuilder sb = new StringBuilder(body.length() + 512);
        sb.append('[').append(kind).append("dump] dir=").append(d.getAbsolutePath())
          .append(" files=").append(files.size())
          .append(" seq_from=").append(firstSeq < 0 ? "-" : String.valueOf(firstSeq))
          .append(" seq_to=").append(lastSeq < 0 ? "-" : String.valueOf(lastSeq))
          .append(" rows=").append(rows)
          .append(" chars=").append(used)
          .append(" cap=").append(MAX_DUMP_CHARS)
          .append(" since_seq=").append(sinceSeq)
          .append('\n');
        sb.append(body);
        long next = lastSeq < 0 ? sinceSeq : lastSeq;
        if (cut) {
            sb.append("…(还有 ").append(remaining).append(" 字符，用 since_seq=").append(next).append(" 续取)\n");
        }
        sb.append('[').append(kind).append("dump] next_seq=").append(next)
          .append(" more_chars=").append(remaining)
          .append(" truncated=").append(cut ? 1 : 0);
        return sb.toString();
    }

    /**
     * 一行是不是"记录开头"：形状必须是 {@code #<数字> <yyyy-MM-dd> …}，且那个数字严格大于 {@code cur}。
     * 返回 seq；不是记录开头返回 -1（= 上一条记录的正文续行）。
     */
    private static long rowSeq(String line, long cur) {
        if (line == null || line.length() < 13) return -1L;
        if (line.charAt(0) != '#') return -1L;
        long v = 0L;
        int i = 1;
        int d = 0;
        while (i < line.length() && d < 18) {
            char c = line.charAt(i);
            if (c < '0' || c > '9') break;
            v = v * 10L + (c - '0');
            i++;
            d++;
        }
        if (d == 0 || i >= line.length() || line.charAt(i) != ' ') return -1L;
        if (!datePrefix(line, i + 1)) return -1L;
        if (v <= cur) return -1L;
        return v;
    }

    /** {@code s.charAt(from)} 起是不是 {@code yyyy-MM-dd HH} 形状。 */
    private static boolean datePrefix(String s, int from) {
        if (from + 13 > s.length()) return false;
        for (int i = 0; i < 10; i++) {
            char c = s.charAt(from + i);
            if (i == 4 || i == 7) {
                if (c != '-') return false;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        if (s.charAt(from + 10) != ' ') return false;
        char h1 = s.charAt(from + 11);
        char h2 = s.charAt(from + 12);
        return h1 >= '0' && h1 <= '9' && h2 >= '0' && h2 <= '9';
    }

    /** 该类别在盘上的全部文件，按写入顺序（日期升序 → 封存时刻升序 → 当前片段最后）。 */
    private static List<File> orderFiles(File d, String kind) {
        List<File> out = new ArrayList<File>();
        try {
            File[] fs = d.listFiles();
            if (fs == null) return out;
            Map<String, File> byKey = new TreeMap<String, File>();
            for (File f : fs) {
                if (f == null || !f.isFile()) continue;
                String n = f.getName();
                if (!n.startsWith(kind + "-")) continue;
                java.util.regex.Matcher cur = CUR_P.matcher(n);
                if (cur.matches() && kind.equals(cur.group(1))) {
                    byKey.put(cur.group(2) + "-z", f);          // 当前片段：同日排最后
                    continue;
                }
                java.util.regex.Matcher se = SEALED_P.matcher(n);
                if (se.matches() && kind.equals(se.group(1))) {
                    byKey.put(se.group(2) + "-" + se.group(3) + (se.group(4) == null ? "" : se.group(4)), f);
                }
            }
            out.addAll(byKey.values());
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 该类别最新片段里的最大 seq（没有则 0）—— 让 seq 跨重启继续，游标不会退回去。 */
    private static long lastSeqOf(String kind) {
        long max = 0L;
        try {
            File d = dir;
            if (d == null) return 0L;
            List<File> fs = orderFiles(d, kind);
            if (fs.isEmpty()) return 0L;
            File last = fs.get(fs.size() - 1);
            BufferedReader r = null;
            try {
                r = new BufferedReader(new InputStreamReader(new FileInputStream(last), "UTF-8"), 1 << 16);
                String line;
                long cur = -1L;
                while ((line = r.readLine()) != null) {
                    long s = rowSeq(line, cur);
                    if (s > 0) { cur = s; if (s > max) max = s; }
                }
            } finally {
                closeQ(r);
            }
        } catch (Throwable ignored) {
        }
        return max;
    }

    /** 诊断事实行（{@code status} 里带一行）。 */
    public static String facts() {
        StringBuilder sb = new StringBuilder(160);
        sb.append("[logs] on=").append(on ? 1 : 0)
          .append(" dir=").append(dir == null ? "-" : dir.getAbsolutePath())
          .append(" console=").append(fConsole == null ? "-" : fConsole.getName())
          .append(" console_seq=").append(seqConsole)
          .append(" console_rows=").append(rowsConsole)
          .append(" talk=").append(fTalk == null ? "-" : fTalk.getName())
          .append(" talk_seq=").append(seqTalk)
          .append(" talk_rows=").append(rowsTalk)
          .append(" rotate_bytes=").append(ROTATE_BYTES);
        if (!note.isEmpty()) sb.append(" note=").append(Str.oneLine(note));
        return sb.toString();
    }
}
