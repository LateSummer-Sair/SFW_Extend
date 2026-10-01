package sair.v4.term;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.Conf;
import sair.v4.kit.Fs;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * <b>出站红线（常开族）—— 基板侧的唯一判据 + 唯一词表读取器</b>。
 *
 * <p>这一族有两条走向（口径逐字搬自技能 {@code data\skills\出站红线\}，一个字都没改）：</p>
 * <ol>
 *   <li><b>不可替换类目</b>（{@code 禁词不替换}，今天 = 威胁 / 自伤）⇒ 命中即<b>整条不发</b>
 *       （{@link #veto}）；<b>不需要 caller、不需要场合</b> —— 定时任务 / 闹钟落点
 *       （{@code Sinks.TaskSink}，caller 恒为 null）也要拦得住；</li>
 *   <li><b>可替换类目</b>（配了 {@code 禁词替换-<类目>} 且不在"不替换"里，今天 = 隐私 / 机密键）
 *       ⇒ <b>就地换词放行</b>（{@link #replace}）。</li>
 * </ol>
 *
 * <h3>为什么必须住基板（2026-09-22 审计 §5-2）</h3>
 * <p>它原先<b>只有</b>一个实现，住在<b>可选技能</b> {@code 出站红线} 里。技能移走 / 编译失败 /
 * {@code extEnabled=false} / 工具发送路 —— 任一形态都会让<b>整族</b>消失，而它同时是<b>唯一</b>覆盖
 * "无 caller 落点"的安全闸（情绪那一层明确"没 caller 一律不否决"）。老探针里甚至写着
 * "摘掉红线 ⇒ 威胁在这里放行"，等于官方承认卸载即漏。</p>
 *
 * <h3>词表：外挂文件、热读、<b>绝不硬编码</b></h3>
 * <p>词表在 {@code data/prompts/redline.md}（键：{@code 禁词常开} / {@code 禁词不替换} /
 * {@code 禁词-<类目>} / {@code 禁词替换-<类目>} / {@code 禁词例外-<类目>}，格式与技能那份
 * {@code prompt.md} 逐字相同）。读法与缓存照 {@code tool.ToolIndex} 的既有套路：
 * <b>mtime + 字节数</b>双失效 —— 改完保存，下一条消息生效，不用重编译、不用重启。
 * <b>词表不许写进 .java</b>（改一个词要动代码 + 重编译 + 重启，等于把技能的病搬到基板）。</p>
 *
 * <h3>例外（{@code 禁词例外-<类目>}）：2026-09-27 甲方令（批 16）</h3>
 * <p>甲方 CEO 原话：「自伤类目歧义子串…单独判断例外」。判据是<b>子串包含</b>，所以正常句
 * 「想死你了，好久不见。」会命中 {@code 自伤/想死}，而 {@code 自伤} 在 {@code 禁词不替换} 里
 * ⇒ <b>整条静默</b>（与 2026-09-20 的 {@code 上门} 事故同一类）。<b>不许删词</b>（那会放开真自伤），
 * 也不许在代码里写死 ⇒ 放开词表里的一个键：{@code 上下文短语=被豁免的词}，逗号分隔。</p>
 * <p>语义：词 {@code w} 命中时，若该类目有某一对「左 = {@code w}」<b>且正文含左</b> ⇒
 * <b>只豁免这一次命中</b>（继续扫其余词）；否则照旧否决。例外**只放宽被点名的那一个词在点名的那种
 * 上下文里**；缺键 / 坏项 / 表读不到 = 没有例外 = 照旧拦。</p>
 *
 * <h3>失败面（如实写）</h3>
 * <p>表读不到 / 解析不出任何类目 ⇒ 这条闸<b>一个词都不判</b>，并在启动（或运行期状态变化）时打
 * <b>一行 err</b>。为什么不是 fail-closed（表没了就什么都不发）：那会让"词表文件被误删"变成
 * "她一句话都发不出去"，事故面比漏词大得多；判据是"静默失败比失败更贵" ⇒ 宁可吵，不许静默。
 * 这个残余风险（表是单点）在收工报告里明写，不粉饰。</p>
 *
 * <h3>装配（与 {@code Sinks.setExt} 同一个套路）</h3>
 * <p>装配期由 {@code Boot} 调 {@link #install(Conf, Out)} 注入配置（基板级只有一个，见 {@code Boot} 的装配），
 * {@code Boot.stop()} 调 {@link #uninstall()} 卸下。默认（谁都没注入过，含所有老探针）= 没有词表
 * ⇒ 一个词都不判 = 与加这一层之前逐字节一致。</p>
 */
public final class Redline {

    /** 词表文件名（相对 {@code prompts/} 目录）。 */
    public static final String FILE = "redline.md";

    private Redline() {}

    private static volatile Conf CONF;
    private static volatile Out OUT;
    private static volatile Table TABLE;
    /** 上次读表时的文件指纹（mtime + 字节数）：两样都没变才复用缓存。 */
    private static volatile long stamp = Long.MIN_VALUE;
    private static volatile long size = -1L;
    /** 上一次打过的状态提示（同一个状态不重复刷屏）。 */
    private static volatile String lastNote = "";

    /**
     * 一份读出来的词表（不可变快照）。{@code loaded=false} = 这份表没有可用内容
     * （文件不存在 / 读不出来 / 一个类目都没解析到）⇒ 判据一律空转。
     */
    public static final class Table {
        /** 读的是哪个文件（诊断用；可能为 null = 没有配置）。 */
        public final File file;
        public final boolean loaded;
        public final long mtime;
        public final long bytes;
        final List<String> cats;
        final Map<String, List<String>> words;
        final List<String> always;
        final List<String> never;
        final Map<String, String> repl;
        /**
         * 类目 ⇒ 例外对（{@code 禁词例外-<类目>}）。<b>不是词表</b>：它不进 {@link #wordCount()}、
         * 不进 {@link #catsFact()}、不算类目 ⇒ 启动事实行的 {@code 词=} 与类目词数不受它影响
         * （2026-09-27 甲方令：例外是"单独判断"，不是往词表里加/减词）。
         */
        final Map<String, List<Ex>> exempt;

        Table(File file, boolean loaded, long mtime, long bytes,
              List<String> cats, Map<String, List<String>> words,
              List<String> always, List<String> never, Map<String, String> repl,
              Map<String, List<Ex>> exempt) {
            this.file = file;
            this.loaded = loaded;
            this.mtime = mtime;
            this.bytes = bytes;
            this.cats = cats == null ? Collections.<String>emptyList() : cats;
            this.words = words == null ? Collections.<String, List<String>>emptyMap() : words;
            this.always = always == null ? Collections.<String>emptyList() : always;
            this.never = never == null ? Collections.<String>emptyList() : never;
            this.repl = repl == null ? Collections.<String, String>emptyMap() : repl;
            this.exempt = exempt == null ? Collections.<String, List<Ex>>emptyMap() : exempt;
        }

        /** 全部类目名（{@code 禁词-<类目>} 的键，按文件次序）。 */
        public List<String> categories() { return Collections.unmodifiableList(cats); }

        /** 常开族类目（{@code 禁词常开}）—— 本闸只判这几个。 */
        public List<String> alwaysCats() { return Collections.unmodifiableList(always); }

        /** "只否决、绝不替换"的类目（{@code 禁词不替换}）。 */
        public List<String> neverReplaceCats() { return Collections.unmodifiableList(never); }

        /** 某个类目的词表。 */
        public List<String> words(String cat) {
            List<String> w = words.get(cat);
            return w == null ? Collections.<String>emptyList() : Collections.unmodifiableList(w);
        }

        /** 某个类目的替换串（没配就是空串）。 */
        public String replacement(String cat) {
            String r = repl.get(cat);
            return r == null ? "" : r;
        }

        /**
         * 某个类目的例外对（诊断/探针用；原样 {@code 左=右}，按文件次序）——
         * 空表 = 这一类目<b>没有任何例外</b>（缺键 / 坏项 / 表读不到都是这个形状）。
         */
        public List<String> exemptions(String cat) {
            List<Ex> xs = exempt.get(cat);
            if (xs == null || xs.isEmpty()) return Collections.emptyList();
            List<String> out = new ArrayList<String>();
            for (int i = 0; i < xs.size(); i++) {
                Ex e = xs.get(i);
                if (e != null) out.add(e.left + "=" + e.right);
            }
            return Collections.unmodifiableList(out);
        }

        /** 本闸真正会判的类目（常开族 ∩ 有词表的键），按文件次序。 */
        public List<String> judged() {
            List<String> out = new ArrayList<String>();
            for (String c : always) if (!words(c).isEmpty() && !out.contains(c)) out.add(c);
            return out;
        }

        /** 判断口径下的词数（所有"会判的类目"的词数之和）—— 启动事实行用。 */
        public int wordCount() {
            int n = 0;
            for (String c : judged()) n += words(c).size();
            return n;
        }

        /** 类目清单事实：{@code 隐私(5) 威胁(10) …}。 */
        public String catsFact() {
            StringBuilder sb = new StringBuilder();
            for (String c : judged()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(c).append('(').append(words(c).size()).append(')');
            }
            return sb.length() == 0 ? "(无类目)" : sb.toString();
        }
    }

    /**
     * 一条例外对（{@code 左=右}）：词 {@code right} 在"正文含 {@code left}"时被豁免。
     * <p>两边都**非空**才可能被构造出来（见 {@link #addEx}）：{@code text.contains("")} 恒真，
     * 空的左边会变成"什么都豁免" ⇒ 那是最宽的放宽，绝不许出现。</p>
     */
    static final class Ex {
        final String left;
        final String right;
        Ex(String left, String right) { this.left = left; this.right = right; }
    }

    // ================================================================ 装配

    /**
     * 装配期注入配置并读一次表：<b>读到了就打一行事实，读不到就打一行 err</b>
     * （"静默失败比失败更贵" —— 这条闸看不见就等于没有）。
     *
     * @param conf 基板配置（{@code conf.promptsDir()} 下找 {@link #FILE}）；null = 卸下
     * @param out  日志面（可为 null：探针里不需要那行事实）
     */
    public static void install(Conf conf, Out out) {
        CONF = conf;
        OUT = out;
        stamp = Long.MIN_VALUE;                 // 强制重读
        size = -1L;
        lastNote = "";
        Table t = table();                     // 读不到 / 空表由 table() 自己打 err（状态变化才打）
        if (t.loaded && !t.judged().isEmpty() && out != null) {
            out.dim("[redline] 基板红线就位（" + t.catsFact() + " 词=" + t.wordCount()
                    + " 不替换=" + Str.join(t.neverReplaceCats(), ",")
                    + " 可替换=" + Str.join(replaceableCats(t), ",")
                    + " 表=" + (t.file == null ? "(无)" : t.file.getName())
                    + " mtime=" + t.mtime + " bytes=" + t.bytes + "）");
        }
    }

    /** 卸下（{@code Boot.stop()}；此后一个词都不判）。 */
    public static void uninstall() {
        CONF = null;
        OUT = null;
        TABLE = null;
        stamp = Long.MIN_VALUE;
        size = -1L;
        lastNote = "";
    }

    /** 表是不是读到了（诊断/探针用）。 */
    public static boolean ready() {
        return table().loaded;
    }

    /** 启动事实行（给日志/诊断用；不含正文）。 */
    public static String fact() {
        Table t = table();
        if (!t.loaded) return "未就位（词表读不到：" + (t.file == null ? "(无)" : t.file.getAbsolutePath()) + "）";
        return t.catsFact() + " 词=" + t.wordCount()
                + " 不替换=" + Str.join(t.neverReplaceCats(), ",")
                + " 表=" + (t.file == null ? "(无)" : t.file.getName())
                + " mtime=" + t.mtime + " bytes=" + t.bytes;
    }

    // ================================================================ 判据（唯二两个出口）

    /**
     * 这一条正文命中了哪个<b>该整条否决</b>的常开族类目（没有就 null）：只算"不替换"的那几个。
     * <p>判据是<b>子串包含</b>（与技能那一层逐字相同）。</p>
     * <p><b>2026-09-27 甲方令（批 16）</b>：命中后先问一句 {@code 禁词例外-<类目>} ——
     * 被点名的那个词 + 被点名的那种上下文 ⇒ 这一个词这一次跳过、<b>继续扫</b>（不是整类放行）；
     * 否则与加这一层之前逐字同形：命中即返回类目 ⇒ caller 整条不发。</p>
     */
    public static String veto(String text) {
        if (text == null || Str.blank(text)) return null;
        Table t = table();
        if (!t.loaded) return null;
        List<String> cats = t.judged();
        for (int c = 0; c < cats.size(); c++) {
            String cat = cats.get(c);
            if (replaceable(t, cat)) continue;          // 配了替换串的走 replace，不在这里否决
            List<String> ws = t.words(cat);
            for (int i = 0; i < ws.size(); i++) {
                String w = ws.get(i);
                if (!Str.has(w) || !text.contains(w)) continue;
                // 2026-09-27 甲方令（批 16）「自伤类目歧义子串…单独判断例外」：先判例外，再定否决。
                if (exempted(t, cat, w, text)) continue;   // 只豁免这一个词这一次；其余词照扫
                return cat;
            }
        }
        return null;
    }

    /**
     * 这个类目下、这个词、这一次命中该不该豁免 —— <b>例外的唯一入口</b>（只从 {@link #veto} 调）。
     * <p>成立条件（两条都要）：① 某一对的右边与 {@code w} <b>逐字相等</b>；② 正文含那一对的左边。
     * 因为只有 {@code veto} 会调它，而 {@code veto} 早就 {@code continue} 掉了可替换类目 ⇒
     * 例外天然<b>只对"非替换类目"生效</b>（给 {@code 隐私} 之类配例外 = 那一行空转，不报错）。</p>
     * <p>空表 / 空串一律 false：{@code text.contains("")} 恒真，若放进来就是"什么都豁免"。</p>
     */
    private static boolean exempted(Table t, String cat, String w, String text) {
        if (t == null || !Str.has(cat) || !Str.has(w) || text == null) return false;
        List<Ex> xs = t.exempt.get(cat);
        if (xs == null || xs.isEmpty()) return false;
        for (int i = 0; i < xs.size(); i++) {
            Ex e = xs.get(i);
            if (e == null || !Str.has(e.left) || !Str.has(e.right)) continue;
            if (w.equals(e.right) && text.contains(e.left)) return true;
        }
        return false;
    }

    /**
     * 可替换类目 ⇒ <b>就地换词</b>（命中词换成 {@code 禁词替换-<类目>}）；没命中就逐字返回原对象。
     * <p>顺序与技能那一层相同：按类目在文件里的次序、类目内按词表次序依次替换。</p>
     */
    public static String replace(String text) {
        if (text == null || Str.blank(text)) return text;
        Table t = table();
        if (!t.loaded) return text;
        String out = text;
        List<String> cats = t.judged();
        for (int c = 0; c < cats.size(); c++) {
            String cat = cats.get(c);
            if (!replaceable(t, cat)) continue;         // "绝不替换"的留给 veto
            String rep = t.replacement(cat);
            List<String> ws = t.words(cat);
            for (int i = 0; i < ws.size(); i++) {
                String w = ws.get(i);
                if (!Str.has(w) || !out.contains(w)) continue;
                out = out.replace(w, rep);
            }
        }
        return out;
    }

    /**
     * 这个类目走软替换吗 —— 口径与技能那一层<b>逐字相同</b>：
     * 配了 {@code 禁词替换-<类目>}（非空）<b>且</b>不在 {@code 禁词不替换} 里。
     * <p>为什么连"没配替换串的类目"都要算清：那种类目在既有实现里走<b>否决</b>
     * （{@code replaceable=false} ⇒ {@code banned} 判它），不是"两边都不管"。</p>
     */
    public static boolean replaceable(String cat) {
        return cat != null && replaceable(table(), cat);
    }

    private static boolean replaceable(Table t, String cat) {
        if (t == null || cat == null) return false;
        if (t.neverReplaceCats().contains(cat)) return false;
        return Str.has(t.replacement(cat));
    }

    /** 走软替换的类目清单（启动事实行用）。 */
    private static List<String> replaceableCats(Table t) {
        List<String> out = new ArrayList<String>();
        List<String> cats = t.judged();
        for (int i = 0; i < cats.size(); i++) {
            String c = cats.get(i);
            if (replaceable(t, c)) out.add(c);
        }
        return out;
    }

    /** 这个类目是不是"只否决、绝不替换"（诊断/探针用）。 */
    public static boolean neverReplace(String cat) {
        return cat != null && table().neverReplaceCats().contains(cat);
    }

    // ================================================================ 读表（mtime + 字节数热读）

    /** 现读（文件没变就复用缓存）——与 {@code ToolIndex} 同一套口径。 */
    public static Table table() {
        Conf conf = CONF;
        File f = fileOf(conf);
        long m = (f != null && f.isFile()) ? f.lastModified() : -1L;
        long n = (f != null && f.isFile()) ? f.length() : -1L;
        Table cur = TABLE;
        if (cur != null && m == stamp && n == size) return cur;
        Table fresh = read(f, m, n);
        TABLE = fresh;
        stamp = m;
        size = n;
        if (!fresh.loaded) {
            note("missing", "基板红线词表读不到：" + (f == null ? "(没有 prompts 目录)" : f.getAbsolutePath())
                    + " —— 这条闸当前**一个词都不判**（威胁/自伤/隐私/机密键全部放行）。");
        } else if (fresh.judged().isEmpty()) {
            note("empty", "基板红线词表里没有可判的类目（" + f.getAbsolutePath()
                    + "）：检查 `禁词常开` 与 `禁词-<类目>` 两组键是不是都在。");
        } else {
            note("ok", "");
        }
        return fresh;
    }

    private static File fileOf(Conf conf) {
        try {
            return conf == null ? null : new File(conf.promptsDir(), FILE);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 解析一份表：{@code #} 开头跳过；{@code 键: 值}（半角/全角冒号都认）；同名键后面的**追加**（清单键多行）。 */
    private static Table read(File f, long mtime, long bytes) {
        List<String> cats = new ArrayList<String>();
        Map<String, List<String>> words = new LinkedHashMap<String, List<String>>();
        Map<String, String> repl = new LinkedHashMap<String, String>();
        Map<String, List<Ex>> exempt = new LinkedHashMap<String, List<Ex>>();
        List<String> exBad = new ArrayList<String>();   // 被坏行沾过的类目（批 16：一律按"没有例外"）
        List<String> always = new ArrayList<String>();
        List<String> never = new ArrayList<String>();
        if (f == null || !f.isFile()) {
            return new Table(f, false, mtime, bytes, cats, words, always, never, repl, exempt);
        }
        String txt = null;
        try {
            txt = Fs.read(f);
        } catch (Throwable t) {
            txt = null;
        }
        if (txt == null) {
            return new Table(f, false, mtime, bytes, cats, words, always, never, repl, exempt);
        }
        for (String raw : txt.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw == null ? "" : raw.trim();
            if (!line.isEmpty() && line.charAt(0) == '\ufeff') line = line.substring(1).trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            int k = colon(line);
            if (k <= 0) continue;
            String key = line.substring(0, k).trim();
            String val = line.substring(k + 1).trim();
            if (key.isEmpty()) continue;
            if ("禁词常开".equals(key)) { addAll(always, split(val)); continue; }
            if ("禁词不替换".equals(key)) { addAll(never, split(val)); continue; }
            if (key.startsWith("禁词替换-") && key.length() > 5) {
                String cat = key.substring(5).trim();
                if (Str.has(cat) && !repl.containsKey(cat)) repl.put(cat, val);
                continue;
            }
            // ---- 2026-09-27 甲方令（批 16）：`禁词例外-<类目>`（歧义子串的单独判断例外）----
            // 语法：`上下文短语=被豁免的词`，**半角逗号**分隔各项（全角逗号 `，` 是左边的**内容**，
            // 例：`不活了，笑死=我不活了` = 一项）。前缀与上面三族互不重叠。
            // ★ 失败面（全部 ⇒ 这一类目**没有任何例外** ⇒ 与加这一层之前逐字同形，照旧拦）：
            //   键缺失 / 值空 / **任一项没有 `=`** / **任一项有多个 `=`** / 用全角 `＝` /
            //   左右任一侧为空 / 类目名空 / 表整份读不到（`loaded=false` 时 veto 直接 return null）。
            // ★ 为什么这一定是 fail-safe 方向：解析只往 `exempt` 里**加对**，判据只可能因为
            //   "找到一对"而 `continue` 跳过命中 —— 代码里没有任何"解析失败就放行"的分支，
            //   坏项越多 ⇒ 豁免越少 ⇒ 拦得越多。宁可"整类没有例外"，也绝不把"没写对"读成"放宽"。
            if (key.startsWith("禁词例外-") && key.length() > 5) {
                String cat = key.substring(5).trim();
                if (!Str.has(cat)) continue;
                addEx(exempt, exBad, cat, val);
                continue;
            }
            if (key.startsWith("禁词-") && key.length() > 3) {
                String cat = key.substring(3).trim();
                if (!Str.has(cat)) continue;
                if (!cats.contains(cat)) cats.add(cat);
                List<String> w = words.get(cat);
                if (w == null) { w = new ArrayList<String>(); words.put(cat, w); }
                addAll(w, split(val));
                continue;
            }
        }
        boolean loaded = !cats.isEmpty() || !always.isEmpty();
        return new Table(f, loaded, mtime, bytes, cats, words, always, never, repl, exempt);
    }

    /**
     * 解析一行例外清单：每一项必须是 {@code 左=右}（<b>恰一个</b>半角 {@code =}，左右都非空）。
     * <p><b>任一项坏 ⇒ 这一整行一项都不认</b>（不报错、不猜、不兜底）；并且这一类目一旦被坏行
     * 沾过（{@code bad}），后续同名的行也一律不认 —— <b>"这一类目没有任何例外"</b>，
     * 与"加这一层之前"逐字同形。理由：这一层是"放宽"，任何解析上的不确定都必须落到"不放宽"。
     * 右侧与词表里的词是<b>逐字相等</b>比对（见 {@link #exempted}），不是子串：例外只许点名一个词。</p>
     */
    private static void addEx(Map<String, List<Ex>> all, List<String> bad, String cat, String val) {
        List<Ex> tmp = parseEx(val);
        if (tmp == null) {                       // 坏行 ⇒ 清掉这一类目已有的一切例外，并记毒
            all.remove(cat);
            if (bad != null && !bad.contains(cat)) bad.add(cat);
            return;
        }
        if (bad != null && bad.contains(cat)) return;   // 被坏行沾过 ⇒ 之后的行也不认（可预测）
        List<Ex> cur = all.get(cat);
        if (cur == null) { cur = new ArrayList<Ex>(); all.put(cat, cur); }
        cur.addAll(tmp);
    }

    /** 解析一行例外清单：全部合法才返回那些对；**任何一项坏 ⇒ 返回 null**（= 这一类目没有例外）。 */
    private static List<Ex> parseEx(String val) {
        List<Ex> out = new ArrayList<Ex>();
        if (val == null) return null;
        String[] parts = val.split(",", -1);          // 只按半角逗号切：全角逗号是上下文短语的内容
        boolean any = false;
        for (int i = 0; i < parts.length; i++) {
            String p = Str.trim(parts[i]);
            if (!Str.has(p)) continue;                // 空项（尾逗号一类）不算坏项，也不算一项
            any = true;
            int eq = p.indexOf('=');
            if (eq <= 0) return null;                 // 缺 `=`，或左边为空
            if (eq != p.lastIndexOf('=')) return null; // 一个以上 `=`（含全角 `＝` 不在其列）：形态不明
            String left = Str.trim(p.substring(0, eq));
            String right = Str.trim(p.substring(eq + 1));
            if (!Str.has(left) || !Str.has(right)) return null;   // 左右任一侧为空
            out.add(new Ex(left, right));
        }
        if (!any) return null;                        // 值空 / 只有逗号 ⇒ 没有例外
        return out;
    }

    private static void addAll(List<String> to, List<String> from) {
        for (int i = 0; i < from.size(); i++) {
            String x = from.get(i);
            if (Str.has(x) && !to.contains(x)) to.add(x);
        }
    }

    /** 逗号清单（半角/全角逗号都认；去空项）。 */
    private static List<String> split(String v) {
        List<String> out = new ArrayList<String>();
        if (v == null) return out;
        String[] parts = v.split("[,，]");
        for (int i = 0; i < parts.length; i++) {
            String t = Str.trim(parts[i]);
            if (Str.has(t)) out.add(t);
        }
        return out;
    }

    /** 半角或全角冒号的下标（没有返回 -1）。 */
    private static int colon(String s) {
        int a = s.indexOf(':');
        int b = s.indexOf('\uFF1A');
        if (a < 0) return b;
        if (b < 0) return a;
        return Math.min(a, b);
    }

    /** 状态变化才打一行（同一个状态不重复刷屏）：这类失败必须留痕。 */
    private static void note(String state, String msg) {
        if (state.equals(lastNote)) return;
        lastNote = state;
        if (msg == null || msg.isEmpty()) return;
        err(msg);
    }

    private static void err(String msg) {
        Out o = OUT;
        try {
            if (o != null) o.err("[redline] " + Str.oneLine(msg));
        } catch (Throwable ignored) {
        }
    }
}
