package sair.v4.prompt;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import sair.v4.Conf;
import sair.v4.kit.Fs;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * 提示词门面（基板③）。
 * <p><b>基板只带一份初始提示词</b>：{@code prompts/identity.md}（单文件）。
 * 其余提示词属于插件层 —— 可以放在 {@code prompts/} 下的其它文件里，
 * 由 AI 通过 {@code prompt} 工具在运行期加载并注入到指定位置（{@link Inject}）。</p>
 * <p>基板自身不写死任何提示词正文：文件不存在就是空提示词，不报警、不兜底。</p>
 */
public final class Prompts {

    private final Conf conf;
    private final Out out;
    private final PromptFile identity;
    private final PromptFile subagent;
    private final Inject inject = new Inject();

    public Prompts(Conf conf, Out out) {
        this.conf = conf;
        this.out = out;
        this.identity = new PromptFile(conf.identityFile(), out, conf);
        this.subagent = new PromptFile(conf.subagentFile(), out);
        reload();
    }

    public PromptFile identity() { return identity; }

    /**
     * 子 Agent 的<b>公共部分</b>提示词（外挂文件 {@code prompts/subagent.md}）。
     * <p>基板不带任何正文：文件不存在 = 这段为空。变化部分（这个子 Agent 的角色、
     * 边界、输出格式）由<b>主 Agent 在派发时生成</b>，见 {@code Agent.spawn(..., brief)}。</p>
     */
    public PromptFile subagent() { return subagent; }

    public Inject inject() { return inject; }

    public boolean reload() {
        Fs.mkdirs(conf.promptsDir());
        subagent.reload();
        return identity.reload();
    }

    /** prompts/ 目录下的提示词文件名（不含初始身份文件）。 */
    public List<String> files() {
        List<String> outList = new ArrayList<String>();
        File dir = conf.promptsDir();
        for (File f : Fs.walk(dir, ".md", 2)) {
            String rel = f.getAbsolutePath().substring(dir.getAbsolutePath().length() + 1).replace('\\', '/');
            if (rel.equals(conf.identityRel().replace("prompts/", ""))) continue;
            outList.add(rel);
        }
        return outList;
    }

    /** 读取一份提示词（"identity" 或 prompts/ 下的相对路径/文件名）。路径被限制在 prompts/ 目录内的 .md。 */
    public String read(String name) {
        if (Str.blank(name)) return null;
        String n = name.trim();
        if ("identity".equalsIgnoreCase(n) || "初始".equals(n) || n.equals(conf.identityRel())) {
            identity.reload();
            return identity.raw();
        }
        File f = resolveInside(conf.promptsDir(), n);
        if (f != null && f.isFile()) return Fs.read(f);
        for (File c : Fs.walk(conf.promptsDir(), ".md", 2)) {
            if (c.getName().equalsIgnoreCase(n) || Fs.baseName(c).equalsIgnoreCase(n)) return Fs.read(c);
        }
        return null;
    }

    /**
     * 把相对名解析成提示词目录内的真实文件；越界（`..`、绝对路径、符号链接逃逸）或非 .md 一律返回 null。
     * <p>这是硬边界：曾经可以 {@code prompt read name=../config.json} 读到 apiKey。</p>
     */
    public static File resolveInside(File dir, String name) {
        if (dir == null || Str.blank(name)) return null;
        try {
            String n = name.trim().replace('\\', '/');
            if (n.startsWith("/") || n.matches("^[A-Za-z]:.*")) return null;          // 绝对路径
            File root = dir.getCanonicalFile();
            File f = new File(root, n).getCanonicalFile();
            // 第二道：解析最深已存在的祖先（getCanonicalPath 不解析 Windows 目录联接 junction）
            File real;
            try {
                File probe = f;
                while (probe != null && !probe.exists()) probe = probe.getParentFile();
                real = probe == null ? f : new File(probe.getCanonicalFile(), f.getAbsolutePath().substring(probe.getAbsolutePath().length()));
            } catch (Exception e) {
                return null;
            }
            if (!inside(root, real)) return null;
            f = real;
            String rp = root.getAbsolutePath();
            String fp = f.getAbsolutePath();
            if (!fp.equals(rp) && !fp.startsWith(rp + File.separator)) return null;   // 逃出目录
            if (!f.getName().toLowerCase().endsWith(".md")) return null;             // 只认 md
            return f;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean inside(File root, File f) {
        try {
            String rp = root.getCanonicalPath();
            String fp = f.getCanonicalPath();
            return fp.equals(rp) || fp.startsWith(rp + File.separator);
        } catch (Exception e) {
            return false;
        }
    }

    /** 写一份插件层提示词文件（同样限定在 prompts/ 目录内的 .md）。 */
    public boolean write(String name, String text) {
        if (Str.blank(name)) return false;
        String n = Str.safeName(name.trim());
        if (!n.toLowerCase().endsWith(".md")) n = n + ".md";
        File f = resolveInside(conf.promptsDir(), n);
        if (f == null) return false;
        return Fs.write(f, text);
    }

    /**
     * 基础系统提示词 = 初始身份（标题 + 正文 + 所有分节）+ {@code system} 槽位注入。
     * 没有任何兜底文案：文件缺失即该部分为空。
     */
    public String system(String scope) {
        return render(identity, scope);
    }

    /** 子 Agent 公共部分（+ {@code system} 槽位注入）；文件缺失即空。 */
    public String subagentSystem(String scope) {
        return render(subagent, scope);
    }

    /** 把一份提示词文件渲染成文本（标题 + 正文 + 分节 + system 槽位注入）。 */
    private String render(PromptFile f, String scope) {
        f.reload();
        StringBuilder sb = new StringBuilder();
        String t = f.title();
        if (Str.has(t)) sb.append("# ").append(t).append("\n\n");
        String body = f.body();
        if (Str.has(body)) sb.append(body).append("\n");
        for (java.util.Map.Entry<String, String> e : f.sections().entrySet()) {
            sb.append("\n## ").append(e.getKey()).append("\n").append(e.getValue()).append("\n");
        }
        String inj = inject.render(scope, Inject.SYSTEM);
        if (Str.has(inj)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(inj).append("\n");
        }
        return sb.toString().trim();
    }

    /** 动态上下文注入文本（{@code context} 槽位）。 */
    public String context(String scope) {
        return inject.render(scope, Inject.CONTEXT);
    }
}
