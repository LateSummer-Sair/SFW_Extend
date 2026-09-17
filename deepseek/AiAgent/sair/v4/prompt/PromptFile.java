package sair.v4.prompt;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Fs;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 单文件提示词（基板③：系统仅提供<b>初始提示词</b>的加载）。
 * <p>格式：一个 {@code # 标题} + 正文，外加若干 {@code ## [段名]} 分节。
 * 分节让"一份文件承载整套身份设定"，同时可按段取用（{@link #section(String)}）。</p>
 *
 * <p><b>预算只是报警线（B10）</b>：给了 {@link sair.v4.Conf} 时按 {@code identityMaxChars}
 * （默认 8000，可配）判一次"超没超"，超了只 warn 一行
 * （{@code [prompt] identity.md 已 4199/8000 字符}）—— <b>绝不截断、绝不丢内容</b>。
 * 旧文档里那句"identity.md 贴着 4000 字符硬上限"说的是<b>压缩口径</b>，代码里从来没有闸门。</p>
 */
public final class PromptFile {

    private final File file;
    private final Out out;
    /**
     * 预算来源（可空）：{@code null} = 这份提示词不设预算（例如子 Agent 的公共部分）。
     * <p>持 {@code Conf} 而不是持一个数字：{@code identityMaxChars} 是<b>每回合热读</b>的键，
     * 构造时抄一份就变成"改了要重启"了。</p>
     */
    private final sair.v4.Conf conf;

    private volatile long mtime = -1;
    private volatile String raw = "";
    private volatile String title = "";
    private volatile String body = "";
    private final Map<String, String> sections = new LinkedHashMap<String, String>();

    public PromptFile(File file, Out out) {
        this(file, out, null);
    }

    /** @param conf 预算来源（{@code null} = 不设预算，只按"加载了 N 字符"打一行 dim） */
    public PromptFile(File file, Out out, sair.v4.Conf conf) {
        this.file = file;
        this.out = out;
        this.conf = conf;
    }

    public File file() { return file; }

    public String raw() { return raw; }

    public String title() { return title; }

    /** 段名 → 段正文（不含标题行）。 */
    public synchronized String section(String name) {
        if (name == null) return null;
        return sections.get(name.trim());
    }

    public synchronized List<String> sectionNames() { return new ArrayList<String>(sections.keySet()); }

    public synchronized Map<String, String> sections() { return new LinkedHashMap<String, String>(sections); }

    /** 除分节之外的正文（标题之后、第一个分节之前的内容）。 */
    public String body() { return body; }

    public boolean exists() { return file != null && file.isFile(); }

    /**
     * mtime 变化或首次调用时真正读取；返回是否成功（文件不存在=空提示词，不算失败）。
     *
     * <p><b>整个方法同步</b>：多个会话道会同时调它（每一轮都渲染一次提示词），
     * 而 {@link #parse(String)} 是<b>就地</b>清空并重填 {@code sections} 的 ——
     * 两个线程同时走到 parse（首次加载，或主人刚改完 identity.md 的那一刻）会同时改同一个
     * LinkedHashMap，另一个线程此时复制 {@link #sections()} 还可能撞上 ConcurrentModificationException。
     * 加锁后：要么读到旧的一份，要么读到新的一份，不存在中间态。</p>
     */
    public synchronized boolean reload() {
        if (file == null) return false;
        if (!file.isFile()) {
            mtime = -1;
            raw = "";
            title = "";
            body = "";
            sections.clear();
            return true;
        }
        long m = file.lastModified();
        if (m == mtime) return true;
        String txt = Fs.read(file);
        if (txt == null) return false;
        parse(txt);
        mtime = m;
        if (out != null) {
            out.dim("[prompt] 已加载 " + file.getName() + "（" + raw.length() + " 字符，"
                    + sections.size() + " 个分节）");
        }
        warnOverBudget();
        return true;
    }

    /**
     * 超预算<b>只 warn 一行</b>（B10；主人原话「预算直接改大，再压缩就不对头」）。
     *
     * <p>预算 = {@link sair.v4.Conf#identityMaxChars()}（默认 8000，可配，每回合热读）。
     * 这里<b>没有</b>任何 {@code substring} —— 正文在 {@link #raw()} 里一字不少；
     * 超了只是"该考虑把这段挪去插件层了"的烟雾报警。</p>
     *
     * <p>只在<b>真的重新读了文件</b>时打（{@link #reload()} 的 mtime 短路在前面），
     * 所以每轮渲染提示词不会刷屏。</p>
     */
    private void warnOverBudget() {
        if (out == null || conf == null) return;
        int budget = conf.identityMaxChars();
        if (budget <= 0 || raw.length() <= budget) return;
        out.warn("[prompt] " + file.getName() + " 已 " + raw.length() + "/" + budget
                + " 字符（超预算，按不截断处理 —— 要调预算改配置键 identityMaxChars）");
    }

    private void parse(String txt) {
        raw = txt;
        title = "";
        body = "";
        sections.clear();
        String[] lines = txt.split("\n", -1);
        StringBuilder cur = null;      // null=正文
        String curName = null;
        StringBuilder head = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("## ")) {
                if (cur != null && curName != null) sections.put(curName, trimTail(cur.toString()));
                curName = strip(t.substring(3).trim());
                cur = new StringBuilder();
                continue;
            }
            if (t.startsWith("# ") && title.isEmpty() && head.length() == 0) {
                title = t.substring(2).trim();
                continue;
            }
            if (cur == null) head.append(line).append('\n');
            else cur.append(line).append('\n');
        }
        if (cur != null && curName != null) sections.put(curName, trimTail(cur.toString()));
        body = head.toString().trim();
    }

    private static String strip(String s) {
        String t = s.trim();
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1).trim();
        return t;
    }

    private static String trimTail(String s) {
        return s.replaceAll("\\s+$", "");
    }

    /** 供诊断：各段字符数。 */
    public String stats() {
        StringBuilder sb = new StringBuilder();
        sb.append("identity: ").append(Str.nz(title).isEmpty() ? "(无标题)" : title)
          .append("  正文=").append(body.length()).append(" 字符");
        for (Map.Entry<String, String> e : sections.entrySet()) {
            sb.append("  |  ").append(e.getKey()).append("=").append(e.getValue().length());
        }
        return sb.toString();
    }
}
