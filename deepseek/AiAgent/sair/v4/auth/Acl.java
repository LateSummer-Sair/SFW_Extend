package sair.v4.auth;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Jsonc;
import sair.v4.kit.Out;

/**
 * 技能管控表内核：<b>身份 × 键 → Ban / Run / Read / 空</b>（权限表 v6，三个域）。
 *
 * <p>权限文件按<b>归属</b>分散存放（同一个内核、同一套格式与判定语义）：</p>
 * <ul>
 *   <li><b>基板内置工具</b>（不属于任何技能：{@code napcat} / {@code console} / {@code exec} /
 *       {@code prompt} / {@code model} / {@code perm} / {@code store} / {@code tools} / {@code skill} /
 *       {@code ctx} / {@code config} / {@code agent} / {@code alarm} / {@code sendimage} …）的权限写在
 *       数据根下的 {@link #CORE_FILE_NAME}（{@code data\perms-core.jsonc}，与 {@code skills} 目录并列）；</li>
 *   <li><b>每个技能</b>可以在自己的目录里放一份 {@link #SKILL_FILE_NAME}
 *       （{@code data\skills\<技能名>\perms.jsonc}），<b>只写这个技能自己提供的工具</b>的 op；
 *       越界的条目（写了别的技能的、或基板工具的 op）<b>忽略</b>，启动日志里点名告警一行；</li>
 *   <li><b>默认拒绝</b>：技能目录里没有这份文件 = 该技能的全部 op 对 {@code ALLUSER} 一律不授权
 *       （未列到 = 未授权）；{@code MASTER} 与她本人（{@code SYSTEM}）恒全权，不受影响；</li>
 *   <li>旧的统一账本 {@link #FILE_NAME}（{@code skillctl.json}）仍然<b>照读</b>：当成 core 一并读进来
 *       （warn 一行建议 {@code ai/perm gen} 分家），<b>不删</b>，也不再写它。</li>
 * </ul>
 *
 * <h3>文件形状（version 6：域在外、侧在内、规则层中括号键）</h3>
 * <pre>
 * {
 *   "version": 6,
 *   "Skill": { "Run": { ["donation.add", "donation.list"]: ["ALLUSER"] },
 *              "Ban": { ["donation.reset"]: ["ALLUSER"] } },
 *   "DB":    { "Run": { ["pref.donation"]: ["ALLUSER"] }, "Read": {…}, "Ban": {…} },
 *   "File":  { "Run": {…}, "Read": {…}, "Ban": {…} }
 * }
 * </pre>
 * <p>读盘走 {@link Jsonc}（IO → 过滤器 → 解析器）：独占一行的 {@code //} 注释丢掉；键位置的
 * {@code [..]:} 配对后规范成<b>一个</b>字符串键（元素用 U+001F 连接），读取端按 U+001F 拆开；
 * 单元素 {@code ["a"]} 与裸键 {@code "a"} 等价（渲染只输出带括号的）。</p>
 *
 * <h3>优先级与默认值（规格 §2）</h3>
 * <ul>
 *   <li>同一个键上：<b>Ban 压过 Run 与 Read</b>（同键 Ban 赢），同一侧多处声明<b>取并集</b>；</li>
 *   <li>不同键之间：<b>更具体的键优先</b>（{@code pref.donation} 比 {@code pref} 具体；{@code File} 按
 *       最长路径优先，目录 = 以它为前缀的整棵子树）—— 具体那把没给这个身份任何决定时才落到更宽的键；</li>
 *   <li>未定义：{@code Skill} 不能用、{@code File} 不能碰、{@code DB} <b>能读不能改</b>
 *       （"能读"是唯一默认放开的地方 ⇒ {@link #readDefaultList()} 给启动汇总点名单）。</li>
 * </ul>
 *
 * <h3>为什么权限只剩这一条判定</h3>
 * <p>权限不再按"资源在哪"分配：资源（本机文件、运行目录、数据库、对外动作）对
 * {@code MASTER} 与她本人（{@code SYSTEM}）<b>完全可见、可改、可执行</b>；对 {@code ALLUSER}
 * 是一个黑盒 —— 想读、想写、想执行，唯一的路是技能（工具）这条手脚。所以判定只剩：
 * <b>这个身份的这个人，能不能用这个 op</b>（{@link #allow}）、<b>能不能改 / 读这类数据</b>
 * （{@link #dbDeny} / {@link #dbReadDeny}）、<b>能不能碰这个路径</b>（{@link #fileDeny}）。</p>
 *
 * <p><b>权限表只约束 {@code ALLUSER}</b>：把 {@code MASTER} / {@code SYSTEM} 写进表里一律
 * <b>忽略</b>（不报错、不生效）。</p>
 *
 * <p><b>绝不自己写盘</b>：只有主人敲授权命令（{@code ai/perm run|ban|db|file|revoke|gen}）才会落盘；
 * 装载、判定、控制台查看都不写。</p>
 */
public final class Acl {

    /**
     * 基板内置工具的权限文件（数据根下，与 skills 目录并列）：<b>不属于任何技能的 op</b> 都写在这里。
     */
    public static final String CORE_FILE_NAME = "perms-core.jsonc";
    /** 技能自己的权限文件（技能目录里）：<b>只写这个技能自己提供的工具</b>的 op。 */
    public static final String SKILL_FILE_NAME = "perms.jsonc";
    /**
     * 旧的统一账本文件名（数据根下）：<b>只读一次</b> —— 存在就当成 core 一并读进来（warn 一行建议分家），
     * 不写它、也不删它。
     */
    public static final String FILE_NAME = "skillctl.json";
    /** 旧账本文件名（v3 的资源 ACL）：只用来读一次做迁移，不再写。 */
    public static final String LEGACY_FILE_NAME = "perms.json";
    /** 账本格式版本（v6：域在外、侧在内、规则层中括号键）。 */
    public static final int VERSION = 6;
    /** 拒绝文案前缀（识别面 {@code Registry.isDenyText} 认它；<b>一字不改</b>）。 */
    public static final String DENY_PREFIX = "[权限阻断] ";
    /** 身份前缀：人。 */
    public static final String USER_PREFIX = "user:";
    /** 身份前缀：群。 */
    public static final String GROUP_PREFIX = "group:";
    /** 全体用户（ALLUSER 侧的最底层身份）。 */
    public static final String ALLUSER_KEY = "ALLUSER";
    /** 她自己的主体键（恒全权；写进表里会被忽略）。 */
    public static final String SYSTEM_KEY = "SYSTEM";
    /** 主人的主体键（恒全权；写进表里会被忽略）。 */
    public static final String MASTER_KEY = "MASTER";

    /**
     * 表顶那一句给主人看的用法说明（写进 JSON 的 {@code _readme} 字段；基板不读）。
     */
    public static final String README = "技能管控表（基板读这一份；以 // 开头的整行是注释，只给人看，读之前整行丢掉）。"
            + "三个块：Skill = 谁能用哪些 op；DB = 谁能改 / 谁能读哪类数据（改命中后还要看这一行是不是他自己的）；"
            + "File = 谁能碰哪些路径（目录写成末尾带斜杠 = 整棵子树，最长路径优先）。"
            + "规则层的键一律写成中括号数组：[\"a\", \"b\"] 与分别写 \"a\" 与 \"b\" 等价。"
            + "优先级：更具体的键优先，同一个键上 Ban 压过 Run 与 Read；没列到：Skill 不能用、DB 能读不能改、File 不能碰。"
            + "身份写法：user:<QQ> / group:<群号> / ALLUSER（裸 QQ 号 = user:<QQ>）；"
            + "主人（MASTER）与她本人（SYSTEM）恒全权，不受本表影响，不用写进表里";

    /** 行尾释义的分隔符（{@code #} 之后的内容基板一律不读）。 */
    private static final char NOTE_MARK = '#';

    /** 剥掉行尾释义，留下真正要给基板读的那一段。 */
    private static String cut(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int h = s.indexOf(NOTE_MARK);
        return h < 0 ? s : s.substring(0, h).trim();
    }

    /** 把一行与释义拼在一起（释义为空就原样；用空格对齐，读起来整齐）。 */
    private static String withNote(String line, String note) {
        if (note == null || note.trim().isEmpty()) return line;
        StringBuilder sb = new StringBuilder(line);
        while (sb.length() < 44) sb.append(' ');
        return sb.append("  ").append(NOTE_MARK).append(' ').append(note.trim()).toString();
    }

    // ==================== 条目 ====================

    /** 一条账本条目。{@link #raw()} 是原样的那一行，{@link #principals()} 是规范化后的身份。 */
    public static final class Entry {
        private final String raw;
        private final boolean ban;
        private final String op;
        private final List<String> principals;
        private final String note;

        Entry(String raw, boolean ban, String op, List<String> principals) {
            this.raw = raw;
            this.ban = ban;
            this.op = op;
            this.principals = Collections.unmodifiableList(new ArrayList<String>(principals));
            int h = raw == null ? -1 : raw.indexOf(NOTE_MARK);
            this.note = h < 0 ? "" : raw.substring(h + 1).trim();
        }

        /** 原样的一行（打印与回写都用它）。 */
        public String raw() { return raw; }
        /** true = {@code Ban} 黑名单，false = {@code Run} 白名单。 */
        public boolean ban() { return ban; }
        /** op 名（{@code 工具名} 或 {@code 工具名.动作名}）。 */
        public String op() { return op; }
        /** 规范化身份表（{@code user:…} / {@code group:…} / {@code ALLUSER}）；空表 = 未授权。 */
        public List<String> principals() { return principals; }

        /** 行尾的中文释义（给人看的；基板不读它）。 */
        public String note() { return note; }

        /** 一行的可读形态：{@code [Run] memory.remember（记一件事）← user:123}。 */
        public String text() {
            StringBuilder sb = new StringBuilder();
            sb.append(ban ? "[Ban] " : "[Run] ").append(op);
            if (!note.isEmpty()) sb.append("（").append(note).append("）");
            if (principals.isEmpty()) sb.append(" ← （空：未授权）");
            for (String p : principals) sb.append(" ← ").append(p);
            return sb.toString();
        }
    }

    /** 一条认不出来的条目（原样留着，好让主人改）。 */
    public static final class Reject {
        private final String raw;
        private final String why;

        Reject(String raw, String why) {
            this.raw = raw;
            this.why = why;
        }

        public String raw() { return raw; }
        public String why() { return why; }

        public String text() { return raw + "   ← " + why; }
    }

    /**
     * 条目字符串 → 条目。<b>认不出来一律 {@code null}（绝不猜）</b>。
     */
    public static Entry parse(String raw) {
        if (raw == null) return null;
        String s = cut(raw);
        int b = s.indexOf('[');
        int e = s.lastIndexOf(']');
        if (b <= 0 || e < b) return null;
        String head = s.substring(0, b).trim();
        boolean ban;
        if ("Ban".equalsIgnoreCase(head) || "禁".equals(head) || "黑".equals(head)) ban = true;
        else if ("Run".equalsIgnoreCase(head) || "放".equals(head) || "白".equals(head)) ban = false;
        else return null;
        String body = s.substring(b + 1, e).trim();
        if (body.isEmpty()) return null;
        JsonArray arr = J.arr("[" + body + "]");
        if (arr == null || arr.size() == 0) return null;
        String op = str(arr.get(0));
        if (op.isEmpty()) return null;
        List<String> ps = new ArrayList<String>();
        for (int i = 1; i < arr.size(); i++) {
            String p = principalKey(str(arr.get(i)));
            if (p == null) return null;
            if (!ps.contains(p)) ps.add(p);
        }
        return new Entry(raw == null ? s : raw.trim(), ban, op, ps);
    }

    /** 条目为什么非法；合法返回空串。与 {@link #parse(String)} 同一套判据。 */
    public static String reason(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "空行";
        String s = cut(raw);
        int b = s.indexOf('[');
        int e = s.lastIndexOf(']');
        if (b <= 0 || e < b) return "写法应是 Run[\"op\",\"身份\"…] 或 Ban[\"op\",\"身份\"…]";
        String head = s.substring(0, b).trim();
        if (!"Run".equalsIgnoreCase(head) && !"Ban".equalsIgnoreCase(head)
                && !"放".equals(head) && !"禁".equals(head) && !"白".equals(head) && !"黑".equals(head)) {
            return "开头只认 Run 或 Ban（" + head + " 不认识）";
        }
        String body = s.substring(b + 1, e).trim();
        if (body.isEmpty()) return "方括号里没有一个 op";
        JsonArray arr = J.arr("[" + body + "]");
        if (arr == null || arr.size() == 0) return "方括号里的写法不是 \"…\",\"…\" 这种字符串表";
        if (str(arr.get(0)).isEmpty()) return "第一个位置要写 op（工具名或 工具名.动作名）";
        for (int i = 1; i < arr.size(); i++) {
            String why = principalReason(str(arr.get(i)));
            if (!why.isEmpty()) return "第 " + (i + 1) + " 个身份：" + why;
        }
        return "";
    }

    /** 身份写法 → 规范串；认不出返回 {@code null}。 */
    public static String principalKey(String p) {
        if (p == null) return null;
        String s = p.trim();
        if (s.isEmpty()) return null;
        String up = s.toUpperCase(Locale.ROOT);
        if (MASTER_KEY.equals(up) || "ROOT".equals(up)) return null;
        if (SYSTEM_KEY.equals(up)) return null;
        if (ALLUSER_KEY.equals(up) || "全体".equals(s) || "所有人".equals(s) || "全部".equals(s)) return ALLUSER_KEY;
        String low = s.toLowerCase(Locale.ROOT);
        if (low.startsWith(USER_PREFIX)) return number(USER_PREFIX, s.substring(USER_PREFIX.length()));
        if (low.startsWith("qq:")) return number(USER_PREFIX, s.substring(3));
        if (low.startsWith("qq")) return number(USER_PREFIX, s.substring(2));
        if (low.startsWith(GROUP_PREFIX)) return number(GROUP_PREFIX, s.substring(GROUP_PREFIX.length()));
        if (low.startsWith("群号")) return number(GROUP_PREFIX, s.substring(2));
        if (low.startsWith("群")) return number(GROUP_PREFIX, s.substring(1));
        if (low.startsWith("用户")) return number(USER_PREFIX, s.substring(2));
        if (digits(s)) return USER_PREFIX + s;
        return null;
    }

    /** 身份写法为什么不能用；能用返回空串。 */
    public static String principalReason(String p) {
        if (p == null || p.trim().isEmpty()) return "空的身份写法";
        String s = p.trim();
        String up = s.toUpperCase(Locale.ROOT);
        if (MASTER_KEY.equals(up) || "ROOT".equals(up)) return "主人恒全权，不需要写进表里";
        if (SYSTEM_KEY.equals(up)) return "她自己恒全权，不需要写进表里";
        if (principalKey(s) != null) return "";
        return "只认 user:<QQ号> / group:<群号> / ALLUSER（或裸 QQ 号）";
    }

    private static String number(String prefix, String raw) {
        String d = raw == null ? "" : raw.trim();
        if (!digits(d)) return null;
        return prefix + d;
    }

    private static String str(JsonElement e) {
        if (e == null || e.isJsonNull()) return "";
        return e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static boolean digits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    private static long idOf(String key, String prefix) {
        try {
            return Long.parseLong(key.substring(prefix.length()));
        } catch (Throwable t) {
            return -1L;
        }
    }

    /** 一个键的覆盖关系：{@code spec(key, query) < 0} = 不覆盖；否则是具体度（越大越具体）。 */
    private interface Cover {
        int spec(String key, String query);
    }

    /**
     * op / 数据类别的覆盖：精确命中永远最具体；工具级 / 类级键覆盖它下面的子键
     * （眼下的 op 域走实例字段 {@link #OP_COVER}，它会多问一句"这个动作注册过没有"）。
     */
    private static final Cover COVER_DOT = new Cover() {
        @Override public int spec(String key, String query) {
            if (key.equals(query)) return key.length() + 1000;
            return query.startsWith(key + ".") ? key.length() : -1;
        }
    };

    /** 只认精确同键（问"整把工具"时先看有没有工具级键，不看动作级键）。 */
    private static final Cover COVER_EXACT = new Cover() {
        @Override public int spec(String key, String query) {
            return key.equals(query) ? 1000 : -1;
        }
    };

    /**
     * 路径的覆盖：目录（末尾 {@code /}）= 以它为前缀的整棵子树（含目录本身），文件 = 那一个；
     * 具体度 = 键的长度（越长越具体 ⇒ 子目录压过父目录）。
     */
    private static final Cover COVER_PATH = new Cover() {
        @Override public int spec(String key, String query) {
            String a = cmpPath(key);
            String b = cmpPath(query);
            if (a.endsWith("/")) {
                if (b.equals(a.substring(0, a.length() - 1)) || b.startsWith(a)) return a.length();
                return -1;
            }
            return b.equals(a) ? a.length() : -1;
        }
    };

    /** 域内一次判定的结果：最具体的那把覆盖键给出了什么。 */
    private static final class Decision {
        /** 谁都没给决定（各域按自己的默认值收尾）。 */
        static final int NONE = 0;
        /** 有一把 {@code Run} / {@code Read} 命中了调用者。 */
        static final int RUN = 1;
        /** 有一把 {@code Ban} 命中了调用者（同键 Ban 赢）。 */
        static final int BAN = 2;
        /**
         * 这把键在表里<b>出现过</b>、身份表却是空的（"列出来了但谁都不给"）—— 它同样是一条决定：
         * 不再回落到更宽的键，更不吃各域"未定义"的默认值（{@code DB} 的读默认是"能读"，
         * 最容易被空表吃成放行）。
         */
        static final int EMPTY = 3;

        int kind = NONE;
        String key = "";
        /** 调用者是"被指名"命中的（{@code user:} / {@code group:} 直接对上），不是靠 {@code ALLUSER}。 */
        boolean named = false;
    }

    /**
     * 一条 v6 规则：域（Skill / DB / File）+ 侧（Run / Read / Ban）+ 键 + 身份并集。
     * <p>记着它<b>读自哪份文件</b>：写回按文件重排，DB/File 的规则才不会在别人的文件里被抹掉。</p>
     */
    private static final class Rule {
        final String domain;
        final String side;
        final String key;
        final File file;
        final List<String> principals = new ArrayList<String>();

        Rule(String domain, String side, String key, File file) {
            this.domain = domain;
            this.side = side;
            this.key = key;
            this.file = file;
        }

        /** 一行的可读形态（控制台/汇总用）。 */
        String text() {
            StringBuilder sb = new StringBuilder();
            sb.append("[").append(domain).append(".").append(side).append("] ").append(key);
            if (principals.isEmpty()) sb.append(" ← （空：无授权）");
            for (String p : principals) sb.append(" ← ").append(p);
            return sb.toString();
        }
    }

    /**
     * 数据类别白名单（规格 §3 的"35 类表单"）：<b>认不出的键一律点名且不生效</b>（方向只许更严）。
     *
     * <p>分两部分：① 表级（表名就是键）14 个；② 表内子类 + {@code kv} 抽屉 21 个。</p>
     *
     * <p><b>"怎么认它"是显式映射，不在本类里推导</b>：类别名与代码里的字面值<b>不相等</b>
     * （{@code note.correct} ↔ {@code source='correction'}、{@code note.experience} ↔ {@code 'experience'}、
     * {@code memory.impression} ↔ {@code 'impression'}、{@code pref.donation} ↔ {@code pref.key} 以可配置前缀
     * 开头…）。那张对照表是规格 §3 的"签字依据"，由调用方（②波次）按它认；本类只管"这个名字认不认"。</p>
     *
     * <p><b>故意不在表里的三条</b>：{@code attach: } / {@code rounds: } / {@code chat_window: } ——
     * 它们是上下文<b>事实行</b>的前缀（{@code MediaAttach.PREFIX} / {@code Loop.ROUNDS_PREFIX} /
     * {@code ChatWindow.META_PREFIX}），不落任何库，所以写进 {@code DB} 块要判"认不出的键"。</p>
     */
    private static final LinkedHashSet<String> DB_KEYS = new LinkedHashSet<String>();
    static {
        String[] keys = {
            "dialog", "grouplog", "memory", "memory.impression", "note", "note.correct", "note.experience",
            "pref", "pref.donation", "emotion", "group_impression", "sticker", "favor", "favor_event",
            "alarm", "task", "skill_index", "sent",
            "kv.block", "kv.mark", "kv.pending", "kv.pick", "kv.modcfg", "kv.trigger", "kv.mediax",
            "kv.maintain", "kv.emotion", "kv.impression", "kv.experience", "kv.cand", "kv.sticker",
            "kv.note", "kv.talk", "kv.approve", "kv.mod"
        };
        for (int i = 0; i < keys.length; i++) DB_KEYS.add(keys[i]);
    }

    /**
     * <b>技能文件里 {@code DB} 键的归属</b>（规格 §2.5-10）：只有这几个数据类别属于技能，
     * 其余表级键与全部 {@code kv.*} 都归基板（只许写在 {@code perms-core.jsonc}）。
     * <p>技能文件里写了不属它的 DB 键 = 越界 ⇒ 忽略 + 点名（与 op 越界同一口径）。
     * 映射按规格 §3 的"代码出处"列定死，<b>不许由名字推导</b>（类别名与代码字面值不相等）。</p>
     */
    private static final Map<String, String> DB_OWNER = new LinkedHashMap<String, String>();
    static {
        DB_OWNER.put("memory", "记忆");
        DB_OWNER.put("memory.impression", "印象蒸馏");
        DB_OWNER.put("note", "知识笔记");
        DB_OWNER.put("note.correct", "修正记录");
        DB_OWNER.put("note.experience", "经验蒸馏");
        DB_OWNER.put("pref.donation", "捐赠");
        DB_OWNER.put("sticker", "表情包");
        DB_OWNER.put("emotion", "情绪");
        DB_OWNER.put("group_impression", "群印象");
    }

    /** 比对用的大小写口径：Windows 下路径大小写不敏感，Linux 下敏感（规格 §3）。 */
    private static final boolean WIN = File.separatorChar == '\\';

    // ==================== 实例 ====================

    /** 基板权限文件（core；单文件装载时就是被读的那一份）。 */
    private final File file;
    private final File dataRoot;
    private final Out out;
    /** Skill 域的条目（op：Run / Ban）。 */
    private final List<Entry> entries = new ArrayList<Entry>();
    /** DB / File 域的规则（v6 新形状；Skill 域仍走 {@link #entries}）。 */
    private final List<Rule> rules = new ArrayList<Rule>();
    /** 认不出的键（数据类别 / 路径 / 骨架层拼错的块与侧）：装载点名 + 一律不生效。 */
    private final List<String> unknowns = new ArrayList<String>();
    private final List<Reject> rejects = new ArrayList<Reject>();
    /** 本次是从旧账本（v3 资源 ACL）迁进来的条数；{@code -1} = 不是迁移来的。 */
    private int migrated = -1;
    /** 迁移时被忽略的旧资源类条目数（A/B/C/E）。 */
    private int legacySkipped = 0;

    // ---- 多源（core + 技能目录）装载 ----

    /** 技能根（{@code data\skills}）；单文件装载时为 null。 */
    private File skillsDir;
    /** 工具名 → 技能名（只含技能提供的工具）；{@code null} = 拿不到归属（只读 core）。 */
    private Map<String, String> owners;
    /** 是不是多源装载的实例（决定 {@link #reload()} 走哪条路）。 */
    private boolean merged;
    /** 本次读到的权限文件（按读序；core 在前）。 */
    private final List<File> sources = new ArrayList<File>();
    /** op → 它读自哪个文件（回写兜底与状态展示用）。 */
    private final Map<String, File> opFiles = new LinkedHashMap<String, File>();
    /** 文件绝对路径 → 这次从它读进来几条（来源汇总那一行用）。 */
    private final Map<String, Integer> sourceCounts = new LinkedHashMap<String, Integer>();
    /** 文件绝对路径 → 这次从它读进来几条 DB/File 规则（写回计划用）。 */
    private final Map<String, Integer> ruleCounts = new LinkedHashMap<String, Integer>();
    /** 这次读盘里 JSON 坏掉的文件（绝对路径）：重排时<b>不碰</b>它们，免得把主人的原文冲掉。 */
    private final List<String> brokenPaths = new ArrayList<String>();
    /** 最近一次 {@link #saveAll} 真正写了的文件。 */
    private final List<File> written = new ArrayList<File>();
    /** 最近一次 {@link #saveAll} 有决定、但没地方写的键（gen 的提示用）。 */
    private final List<String> unwritten = new ArrayList<String>();
    // 读盘时的当前上下文（单线程装载，读完即清）
    /** 正在读的这个文件属于哪个技能（null = core：全部接受）。 */
    private String readScopeSkill;
    /** 正在读的那个文件。 */
    private File readFile;
    /** 这个文件这次读进来几条 Skill 条目。 */
    private int readHits;
    /** 这个文件这次读进来几条 DB/File 规则。 */
    private int readRuleHits;
    /** 旧嵌套写法这次已经点过名的文件（一个文件只点一次，免得刷屏）。 */
    private File legacyWarned;
    /** Skill 域的规则视图（条目 → 规则），判定时与 DB/File 走同一段代码；条目一变就重建。 */
    private List<Rule> skillPool;
    private boolean skillPoolDirty = true;
    /** 本表里"被明确授予过"的动作级 op（Run 侧出现过的 {@code 工具.动作}）：工具级键的覆盖范围之一。 */
    private LinkedHashSet<String> grantOps = new LinkedHashSet<String>();
    /** 已注册的 op 清单（{@code Auth.ops()}，②波次/启动装配接进来）；{@code null} = 拿不到注册表。 */
    private LinkedHashSet<String> knownOps;

    /**
     * Skill 域的键覆盖（要看注册表清单，所以是实例字段而不是 static 常量）。
     * <p>规则见 {@link #opSpec(String, String)}。</p>
     */
    private final Cover OP_COVER = new Cover() {
        @Override public int spec(String key, String query) { return opSpec(key, query); }
    };

    private Acl(File file, File dataRoot, Out out) {
        this.file = file;
        this.dataRoot = dataRoot != null ? dataRoot : (file != null ? file.getParentFile() : null);
        this.out = out;
    }

    /**
     * 基板权限文件（数据根下的 {@link #CORE_FILE_NAME}，{@code data\perms-core.jsonc}）。
     * <p>"不属于任何技能"的 op（内置工具、{@code napcat.<动作>}）都写在这里。</p>
     */
    public static File fileOf(File dataRoot) {
        if (dataRoot == null) return null;
        return new File(dataRoot, CORE_FILE_NAME);
    }

    /** 旧的统一账本文件（数据根下的 {@link #FILE_NAME}）：只读一次做迁移，不写不删。 */
    public static File unifiedFileOf(File dataRoot) {
        if (dataRoot == null) return null;
        return new File(dataRoot, FILE_NAME);
    }

    /** 某个技能自己的权限文件（{@code data\skills\<技能名>\perms.jsonc}）。 */
    public static File skillFileOf(File skillsDir, String skill) {
        if (skillsDir == null || skill == null || skill.trim().isEmpty()) return null;
        return new File(new File(skillsDir, skill.trim()), SKILL_FILE_NAME);
    }

    /** 旧账本文件（v3 资源 ACL）：只用于一次性迁移。 */
    public static File legacyFileOf(File dataRoot) {
        if (dataRoot == null) return null;
        return new File(dataRoot, LEGACY_FILE_NAME);
    }

    /** 装载（读盘，只读一个文件）。{@code file} 为 null = 空账本（一切未授权）。 */
    public static Acl load(File file, Out out) {
        Acl a = new Acl(file, file == null ? null : file.getParentFile(), out);
        a.read();
        return a;
    }

    /**
     * <b>多源装载</b>：读 core（{@code perms-core.jsonc}）+ 每个技能目录里的 {@code perms.jsonc}，
     * 合并成一张表（判定语义与单文件时<b>一字不差</b>）。
     *
     * <ul>
     *   <li>{@code core} 里的条目<b>全部接受</b>（它是基板自己的地盘）；</li>
     *   <li>技能文件里的 <b>Skill 域</b>条目<b>只接受 {@code toolOwners} 里 owner 等于该目录名的 op</b>；
     *       越界的（写了别的技能的、或基板工具的 op）<b>忽略 + 一行 warn</b>；</li>
     *   <li>技能文件里的 DB / File 规则照收（那是它自己的数据与它自己碰的路径）；</li>
     *   <li>数据根下旧的 {@link #FILE_NAME}（{@code skillctl.json}）若存在，当成 core 一并读进来
     *       （warn 一行建议 {@code ai/perm gen} 分家），<b>不删</b>；</li>
     *   <li>技能目录里<b>没有</b> {@code perms.jsonc} = 该技能全部 op 一律不授权（未列到 = 未授权）；</li>
     *   <li>{@code toolOwners} 为 {@code null}（拿不到注册表的早期 / 桩）时<b>退回"只读 core"</b>：
     *       技能文件一律不读（没有归属就无从判断越界，宁可不读也不放宽）。</li>
     * </ul>
     *
     * @param core       基板权限文件（可为 null = 没有这一份）
     * @param skillsDir  技能根（可为 null = 没有技能）
     * @param toolOwners 工具名 → 技能名（只含技能提供的工具；可为 null）
     * @param out        告警落点（可为 null）
     */
    public static Acl loadMerged(File core, File skillsDir, Map<String, String> toolOwners, Out out) {
        Acl a = new Acl(core, core == null ? null : core.getParentFile(), out);
        a.merged = true;
        a.skillsDir = skillsDir;
        a.owners = toolOwners == null ? null : new LinkedHashMap<String, String>(toolOwners);
        a.readMerged();
        return a;
    }

    /** 空账本（等于"一个 op 都没授权"）。 */
    public static Acl empty(Out out) { return new Acl(null, null, out); }

    /**
     * 同 {@link #loadMerged(File, File, Map, Out)}，并顺手把<b>已注册的 op 清单</b>接进来
     * （{@code Auth.ops().all()}）——工具级键只覆盖已注册的动作（规格 §2.5-1）。
     * <p>②波次/启动装配用这个重载；4 参那个保持冻结签名不动。</p>
     */
    public static Acl loadMerged(File core, File skillsDir, Map<String, String> toolOwners,
                                 Collection<String> ops, Out out) {
        Acl a = new Acl(core, core == null ? null : core.getParentFile(), out);
        a.merged = true;
        a.skillsDir = skillsDir;
        a.owners = toolOwners == null ? null : new LinkedHashMap<String, String>(toolOwners);
        a.knownOps = buildOps(ops);          // 必须在读盘之前：越界判定是装载期做的
        a.readMerged();
        return a;
    }

    /**
     * 接上"已注册的 op 清单"（{@code Auth.ops().all()}）。
     * <p>装载完权限表之后调一次即可；清单变了会自己重读一次（技能文件里"写了没注册的动作"是
     * <b>装载期</b>就点名的，光改判定口径不够）。不调也能跑 —— 那时退回"本表里被授予过的动作"
     * 这条口径（见 {@link #opSpec(String, String)}）。</p>
     */
    public void knownOps(Collection<String> ops) {
        LinkedHashSet<String> s = buildOps(ops);
        if (s == null ? knownOps == null : s.equals(knownOps)) return;
        knownOps = s;
        reload();
    }

    private static LinkedHashSet<String> buildOps(Collection<String> ops) {
        if (ops == null) return null;
        LinkedHashSet<String> s = new LinkedHashSet<String>();
        for (String o : ops) {
            if (o == null) continue;
            String t = o.trim();
            if (!t.isEmpty()) s.add(t);
        }
        return s;
    }

    /** 重读（主人手改了文件之后调；多源装载的实例会把技能目录一起重读）。 */
    public boolean reload() {
        entries.clear();
        rules.clear();
        unknowns.clear();
        rejects.clear();
        migrated = -1;
        legacySkipped = 0;
        sources.clear();
        opFiles.clear();
        sourceCounts.clear();
        ruleCounts.clear();
        brokenPaths.clear();
        legacyWarned = null;
        skillPool = null;
        skillPoolDirty = true;
        if (merged) readMerged();
        else read();
        return true;
    }

    /** 单文件装载（{@link #load(File, Out)} 的那条路）：文件不在就只试一次 v3 旧账本迁移。 */
    private void read() {
        if (file == null) return;
        if (file.isFile()) {
            readInto(file, null);
            return;
        }
        readLegacy();
    }

    /**
     * 多源装载（{@link #loadMerged} 的那条路）：core（+ 旧统一表）在前，技能文件按目录名排序在后。
     * <p>技能目录里没有 {@code perms.jsonc} 的技能<b>什么都不读</b> —— 没列到 = 未授权。</p>
     */
    private void readMerged() {
        if (file != null && file.isFile()) readInto(file, null);
        // 旧统一表：当成 core 一并读进来（只读；不删、不写）
        File unified = unifiedFileOf(dataRoot);
        if (unified != null && unified.isFile() && !sameFile(unified, file)) {
            readInto(unified, null);
            warn("[acl] 读到旧的统一表 " + FILE_NAME + "：" + entries.size() + " 条已并入 core；"
                    + "建议敲 ai/perm gen 分家（旧文件不删，留着你自己处置）");
        }
        if (file == null || !file.isFile()) {
            // core 缺失：仍然给 v3 旧账本一次机会（它的口径是"翻译成新表"，不是本轮的技能文件）
            if (!(unified != null && unified.isFile())) readLegacy();
        }
        if (skillsDir == null || !skillsDir.isDirectory()) return;
        if (owners == null) {
            warn("[acl] 拿不到工具归属（注册表还没装配）—— 技能权限文件本次一律不读："
                    + "ALLUSER 按未授权处理（要读请等装配完成或敲 ai/perm reload）");
            return;
        }
        for (File d : Fs.dirs(skillsDir)) {
            File f = new File(d, SKILL_FILE_NAME);
            if (!f.isFile()) continue;                     // 没有这份文件 = 该技能全部 op 不授权
            readInto(f, d.getName());
        }
    }

    /**
     * 读一个权限文件（读盘管线：IO → {@link Jsonc} 过滤器 → Gson）。
     * {@code skill} = 这个文件属于哪个技能（{@code null} = core：Skill 域的条目全部接受）。
     *
     * <p>一份文件读不出东西时<b>按空处理</b>（它管的 op 一律未授权）+ warn 点名文件 ——
     * 读盘出任何事都绝不"当成没有限制"，只当"没有授权"。</p>
     */
    private void readInto(File f, String skill) {
        readScopeSkill = skill;
        readFile = f;
        readHits = 0;
        readRuleHits = 0;
        sources.add(f);
        try {
            Jsonc.Filtered fd = Jsonc.filter(f);
            if (!fd.problems.isEmpty()) {
                // 规则键里带了 U+001F（拆不干净）：过滤器已经点到行号，这一份按坏文件处理 ——
                // 规格 §1.4 "宁可拒，不猜"，绝不许静默丢键、更不许当成正常表读
                brokenPaths.add(f.getAbsolutePath());
                for (String p : fd.problems) warn("[acl] " + f.getName() + " " + p);
                warn("[acl] 权限文件里有拆不干净的规则键 —— 这一份按空处理（它管的 op 一律未授权）："
                        + f.getAbsolutePath());
                return;
            }
            JsonObject root = J.obj(fd.text);
            if (root == null) {
                brokenPaths.add(f.getAbsolutePath());
                warn("[acl] 权限文件不是合法 JSON —— 这一份按空处理（它管的 op 一律未授权）：" + f.getAbsolutePath());
                return;
            }
            int ver = J.i(root, "version", 0);
            if (ver == 5) {
                warn("[acl] 权限文件 version=5 是上一版格式 —— 按旧语义读（建议敲 ai/perm gen 重排）：" + f.getName());
            } else if (ver > VERSION) {
                warn("[acl] 权限文件 version=" + ver + " 比基板新（这一版是 " + VERSION
                        + "）—— 认得的字段照读，认不出的忽略：" + f.getName());
            } else if (ver != VERSION) {
                warn("[acl] 权限文件 version=" + ver + "（这一版是 " + VERSION + "）—— 按字段尽力读：" + f.getName());
            }
            if (hasKey(root, "Skill") || hasKey(root, "DB") || hasKey(root, "File")) {
                readV6(root, f);                            // 新形状：域在外、侧在内、规则层中括号键
                return;
            }
            if (hasKey(root, "Run") || hasKey(root, "Ban")) {
                warn("[acl] 这是旧格式（顶层 Run/Ban 套工具/动作）—— 按旧口径照读；建议敲 ai/perm gen 重排："
                        + f.getName());
                readLegacyBlocks(root);
                return;
            }
            JsonArray arr = J.list(root, "entries");
            if (arr == null) {
                if (ver >= 5) return;      // 空表（只有 version / _readme）= 一条都没有：未授权，不告警
                warn("[acl] 权限文件里既没有 Skill/DB/File 三块、也没有 entries 数组 —— 按空表处理（一切未授权）："
                        + f.getName());
                return;
            }
            for (int i = 0; i < arr.size(); i++) {
                String raw = str(arr.get(i));
                if (raw.isEmpty()) continue;                       // 空行 = 组间留白
                if (raw.trim().startsWith("#")) continue;          // 纯注释行（工具分组说明）：不判、不报错
                Entry e = parse(raw);
                if (e == null) reject(raw, reason(raw));
                else addEntry(e);
            }
        } catch (Throwable t) {
            // 装载出任何事都落到"这一份按空 + 点名文件"：读不出 == 没有授权，绝不放宽
            brokenPaths.add(f.getAbsolutePath());
            warn("[acl] 读权限文件时出错，这一份按空处理（它管的 op 一律未授权）：" + f.getAbsolutePath() + "（" + t + "）");
        } finally {
            readScopeSkill = null;
            readFile = null;
            sourceCounts.put(f.getAbsolutePath(), Integer.valueOf(readHits));
            ruleCounts.put(f.getAbsolutePath(), Integer.valueOf(readRuleHits));
        }
    }

    // ==================== v6：域 → 侧 → 规则 ====================

    /**
     * 新形状（v6）：顶层只认 {@code version} / {@code _readme} / {@code Skill} / {@code DB} / {@code File}。
     * <p><b>骨架层拼错要点名</b>：未知的块、未知的侧一律整块忽略（方向只许更严），并进
     * {@link #unknownKeys()} 让控制台能一眼看到拼错的字。</p>
     * <p>按条目遍历（不是 {@code J.sub}）：同一个域写了两遍也照单全收 —— 过滤器给重复键改过名，
     * 这里拿 {@link Jsonc#undup(String)} 还原，重复的那一份按并集读进来。</p>
     */
    private void readV6(JsonObject root, File f) {
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String k = Jsonc.undup(e.getKey());
            if (k == null) continue;
            k = k.trim();
            if ("version".equals(k) || "_readme".equals(k)) continue;
            if ("Skill".equals(k) || "DB".equals(k) || "File".equals(k)) {
                if ("File".equals(k) && readScopeSkill != null) {
                    // 路径属于部署：技能文件里写 File 块 = 越界，整块忽略 + 点名（规格 §2.5-10）
                    warnForeignRule(null, "路径属于部署，只许写在基板那份 " + CORE_FILE_NAME + " 里");
                    continue;
                }
                readDomain(asObj(e.getValue()), k, f);
                continue;
            }
            unknown("认不出的块「" + k + "」（只认 Skill / DB / File）", k);
        }
    }

    /** 一个域：侧（Run / Read / Ban）拼错要点名；侧的值不是对象 = 这一侧整体忽略。 */
    private void readDomain(JsonObject dom, String domain, File f) {
        if (dom == null) return;
        for (Map.Entry<String, JsonElement> e : dom.entrySet()) {
            String side = Jsonc.undup(e.getKey() == null ? "" : e.getKey()).trim();
            if (isSide(domain, side)) {
                readSide(side, e.getValue(), domain, f);
                continue;
            }
            unknown("「" + domain + "」里认不出的侧「" + side + "」（只认 " + sidesOf(domain) + "）", domain + "." + side);
        }
    }

    /** 这个域的合法侧：{@code Skill} 只有 Run/Ban；{@code DB} 与 {@code File} 有 Run/Read/Ban。 */
    private static boolean isSide(String domain, String side) {
        if ("Skill".equals(domain)) return "Run".equals(side) || "Ban".equals(side);
        return "Run".equals(side) || "Read".equals(side) || "Ban".equals(side);
    }

    private static String sidesOf(String domain) {
        return "Skill".equals(domain) ? "Run / Ban" : "Run / Read / Ban";
    }

    private void readSide(String side, JsonElement v, String domain, File f) {
        if (v == null || v.isJsonNull()) return;
        if (!v.isJsonObject()) {
            warn("[acl] 「" + domain + "." + side + "」不是对象（规则要写成 { 键: [身份] }）—— 这一侧忽略："
                    + f.getName());
            return;
        }
        for (Map.Entry<String, JsonElement> e : v.getAsJsonObject().entrySet()) {
            readRule(domain, side, e.getKey(), e.getValue(), f);
        }
    }

    /**
     * 读一条规则：键（过滤器已把键数组合成一个字符串键，这里按 U+001F 拆开）→ 域内校验 → 身份表。
     * <p>值不是身份数组时，只有 {@code Skill} 域还认"工具 → 动作"的旧嵌套（点名提示重排，规格 §4）。</p>
     */
    private void readRule(String domain, String side, String rawKey, JsonElement v, File f) {
        if (v == null || v.isJsonNull()) return;
        List<String> keys = Jsonc.splitKeys(rawKey == null ? "" : Jsonc.undup(rawKey));
        for (int i = 0; i < keys.size(); i++) {
            String raw0 = keys.get(i) == null ? "" : keys.get(i);
            String key = ("Skill".equals(domain)) ? stripGloss(raw0) : raw0.trim();
            if (key.isEmpty()) {
                unknown("认不出的规则键（空键）", "?");
                continue;
            }
            if (v.isJsonObject()) {
                if (!"Skill".equals(domain)) {
                    warn("[acl] 「" + domain + "." + side + "」的「" + key + "」值是个对象 —— " + domain
                            + " 域只认身份数组，这一条忽略：" + f.getName());
                    continue;
                }
                warnLegacyNested(f);
                for (Map.Entry<String, JsonElement> a : v.getAsJsonObject().entrySet()) {
                    String act = stripGloss(a.getKey() == null ? "" : a.getKey().trim());
                    String op = ("*".equals(act) || act.isEmpty()) ? key
                            : (act.indexOf('.') >= 0 ? act : key + "." + act);
                    List<String> aps = principalsOf(a.getValue());
                    if (aps.isEmpty() && !literalEmpty(a.getValue())) continue;   // 只写了自由身份 ⇒ 整行忽略（§0）
                    addParsed("Ban".equals(side), op, aps);
                }
                continue;
            }
            if (!v.isJsonArray() && !v.isJsonPrimitive()) {
                warn("[acl] 「" + domain + "." + side + "」的「" + key + "」值不是身份数组 —— 这一条忽略："
                        + f.getName());
                continue;
            }
            List<String> ps = principalsOf(v);
            if (ps.isEmpty() && !literalEmpty(v)) continue;      // 只写了自由身份 ⇒ 整行忽略（§0）
            if ("Skill".equals(domain)) {
                addParsed("Ban".equals(side), key, ps);
                continue;
            }
            addRule(domain, side, key, ps, f);
        }
    }

    /**
     * 收一条 DB / File 规则：<b>先过键空间</b>（认不出的一律点名且不生效），再过<b>归属</b>
     * （技能文件只能写自己的数据键，规格 §2.5-10），最后记下来。
     * <p>同一个键多处读进来就是多条 —— 判定时取并集（规格 §2"同一个键出现多处取并集"）。</p>
     */
    private void addRule(String domain, String side, String key, List<String> ps, File f) {
        String k = key;
        if ("DB".equals(domain)) {
            if (!DB_KEYS.contains(k)) {
                unknown("认不出的数据键「" + k + "」（不在数据类别表里）", k);
                return;
            }
            if (readScopeSkill != null) {
                // 技能文件里的 DB 键：只有属本技能的才收（否则就是给自己开通用的后门）
                String owner = DB_OWNER.get(k);
                if (!readScopeSkill.equals(owner)) {
                    warnForeignRule(k, owner == null
                            ? ("这类数据归基板（只许写在 " + CORE_FILE_NAME + "）")
                            : ("属技能「" + owner + "」"));
                    return;
                }
            }
        } else {
            if (readScopeSkill != null) {
                // File 块只许写在基板那份：路径属于部署，不属于某个技能（规格 §2.5-10）
                warnForeignRule(null, "路径属于部署，只许写在基板那份 " + CORE_FILE_NAME + " 里");
                return;
            }
            String why = pathProblem(k);
            String nk = why == null ? normPath(k) : "";
            if (why != null || nk.isEmpty()) {
                unknown("认不出的路径键「" + k + "」" + (why == null ? "" : "（" + why + "）"), k);
                return;
            }
            k = nk;
        }
        Rule r = new Rule(domain, side, k, f);
        r.principals.addAll(ps);
        rules.add(r);
        readRuleHits++;
    }

    /**
     * 技能文件里写了不属它的 {@code DB} 键 / {@code File} 块：忽略 + 点名（规格 §2.5-10）。
     *
     * @param key {@code null} = 这是 {@code File} 块（整块忽略）；否则是不属本技能的数据键
     * @param why 归属说明（属哪个技能 / 归基板）
     */
    private void warnForeignRule(String key, String why) {
        if (key == null) {
            warn("[acl] 越界忽略：技能「" + readScopeSkill + "」的 " + SKILL_FILE_NAME
                    + " 里写了 File 块（" + why + "）—— 整块不生效");
            return;
        }
        warn("[acl] 越界忽略：技能「" + readScopeSkill + "」的 " + SKILL_FILE_NAME
                + " 里写了不属它的 DB 键「" + key + "」（" + why + "）—— 这一条不生效");
    }

    /**
     * 旧形状的两个块（顶层 {@code Run} / {@code Ban}）。同一个块写了两遍也照单全收（按并集读）。
     */
    private void readLegacyBlocks(JsonObject root) {
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String k = Jsonc.undup(e.getKey());
            if ("Run".equals(k)) readBlock(asObj(e.getValue()), false);
            else if ("Ban".equals(k)) readBlock(asObj(e.getValue()), true);
        }
    }

    /** 这个对象里有没有这个键（按"重复键改名"还原后的原键比）。 */
    private static boolean hasKey(JsonObject o, String name) {
        if (o == null) return false;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (name.equals(Jsonc.undup(e.getKey()))) return true;
        }
        return false;
    }

    private static JsonObject asObj(JsonElement e) {
        return (e != null && e.isJsonObject()) ? e.getAsJsonObject() : null;
    }

    /**
     * 旧形状（v5 及更早）：{@code "Run": { "工具": { "动作": [身份] } }} 或 {@code "Run": { "工具": [身份] }}。
     * <p>整份照读（规格 §4：旧嵌套写法继续能读）—— 归属检查照旧生效（{@link #inScope}）：技能文件里
     * 写了别的技能的 op 仍然忽略 + 点名（规格 §3 第 1 条，v5 时代就是这个口径）。</p>
     */
    private void readBlock(JsonObject block, boolean ban) {
        if (block == null) return;
        for (Map.Entry<String, JsonElement> e : block.entrySet()) {
            String tool = stripGloss(Jsonc.undup(e.getKey()));
            if (tool.isEmpty()) continue;
            JsonElement v = e.getValue();
            if (v == null || v.isJsonNull()) continue;
            if (v.isJsonArray() || v.isJsonPrimitive()) {
                List<String> ps = principalsOf(v);
                if (ps.isEmpty() && !literalEmpty(v)) continue;  // 只写了自由身份 ⇒ 整行忽略（§0）
                addParsed(ban, tool, ps, false);
            } else if (v.isJsonObject()) {
                for (Map.Entry<String, JsonElement> a : v.getAsJsonObject().entrySet()) {
                    String act = stripGloss(Jsonc.undup(a.getKey()));
                    String op = (act.equals("*") || act.isEmpty()) ? tool
                            : (act.indexOf('.') >= 0 ? act : (tool + "." + act));
                    List<String> aps = principalsOf(a.getValue());
                    if (aps.isEmpty() && !literalEmpty(a.getValue())) continue;   // 同上
                    addParsed(ban, op, aps, false);
                }
            }
        }
    }

    /** 键名 → op 名（剥掉 {@code （中文释义）} 那一段；只对 op 键做，路径键不许剥）。 */
    private static String stripGloss(String key) {
        if (key == null) return "";
        String s = key.trim();
        int i = s.indexOf('（');
        if (i < 0) i = s.indexOf('(');
        if (i < 0) i = s.indexOf('#');
        return (i < 0 ? s : s.substring(0, i)).trim();
    }

    /**
     * 这条规则的值是不是<b>字面空表</b>（{@code []}）。
     *
     * <p>它必须与"写了身份、但全是按 §0 该被忽略的自由身份（{@code MASTER} / {@code SYSTEM}）"分开：
     * 前者是"列出来了但谁都不给"（{@link Decision#EMPTY}，<b>算一条决定</b>）；后者整行按 §0
     * <b>不生效</b> —— 等于没写：既不进判定，也<b>不占</b>"已声明"的位置。不分开的话，
     * {@code Ban["memory"]: ["MASTER","SYSTEM"]} 这种空壳会把 {@code memory} 从
     * {@link #readDefaultList()} 的披露里挤掉（庚抓到的就是这个）。</p>
     */
    private static boolean literalEmpty(JsonElement v) {
        return v != null && v.isJsonArray() && v.getAsJsonArray().size() == 0;
    }

    /**
     * 身份表 → 规范身份。
     * <p>{@code MASTER} / {@code SYSTEM}（以及 {@code ROOT}）<b>写进表里一律忽略</b>：
     * 不报错、不生效 —— 权限表只约束 {@code ALLUSER}，主人与她本人恒全权，不进判定。</p>
     */
    private List<String> principalsOf(JsonElement v) {
        List<String> ps = new ArrayList<String>();
        if (v == null || v.isJsonNull()) return ps;
        if (v.isJsonArray()) {
            JsonArray a = v.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                String raw = str(a.get(i));
                if (isFreeKey(raw)) continue;
                String p = principalKey(raw);
                if (p == null) {
                    reject(raw, "身份写法认不出（只认 user:<QQ> / group:<群号> / ALLUSER）");
                    continue;
                }
                if (!ps.contains(p)) ps.add(p);
            }
        } else if (v.isJsonPrimitive()) {
            String raw = str(v);
            if (!isFreeKey(raw)) {
                String p = principalKey(raw);
                if (p != null && !ps.contains(p)) ps.add(p);
            }
        }
        return ps;
    }

    /** 是不是"恒全权"的那两个主体键（写进表里一律忽略，不报错）。 */
    private static boolean isFreeKey(String raw) {
        if (raw == null) return false;
        String up = raw.trim().toUpperCase(Locale.ROOT);
        return MASTER_KEY.equals(up) || SYSTEM_KEY.equals(up) || "ROOT".equals(up);
    }

    /** 收一条 Skill 条目（顺手记它读自哪个文件、这个文件读进来几条）。 */
    private void addEntry(Entry e) {
        if (e == null) return;
        entries.add(e);
        skillPoolDirty = true;
        readHits++;
        if (readFile != null && !opFiles.containsKey(e.op())) opFiles.put(e.op(), readFile);
    }

    /**
     * Skill 域的规则视图（条目 → 规则）：判定与 DB/File 域走同一段"最具体键优先"的代码。
     * <p>懒建 + 脏标记（条目一变才重建）：{@code allow} 每轮会被问上百次，不能每次都重新拼一遍。</p>
     */
    private List<Rule> skillPool() {
        if (skillPool != null && !skillPoolDirty) return skillPool;
        List<Rule> l = new ArrayList<Rule>(entries.size());
        LinkedHashSet<String> grants = new LinkedHashSet<String>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            Rule r = new Rule("Skill", e.ban() ? "Ban" : "Run", e.op(), null);
            r.principals.addAll(e.principals());
            l.add(r);
            if (!e.ban() && e.op().indexOf('.') > 0) grants.add(e.op());
        }
        skillPool = l;
        grantOps = grants;
        skillPoolDirty = false;
        return l;
    }

    /**
     * Skill 域一把键对 query 的覆盖（{@code < 0} = 不覆盖；否则是具体度）。
     *
     * <ul>
     *   <li>精确同键 ⇒ 命中（最具体）——"该工具自身那一条"（{@code ["weather"]}）就是走这一支；</li>
     *   <li>工具级键（不带点）覆盖 {@code 工具.动作}，但<b>只覆盖"已知的动作"</b>：
     *       ① 接上了注册表清单（{@link #knownOps(Collection)}）⇒ 必须真的在清单里；
     *       ② 拿不到注册表 ⇒ 只认"本表 Skill 域里被授予过的动作"（Run 侧出现过的 {@code 工具.动作}）。
     *       两条之外<b>一律不覆盖</b>（规格 §2.5-1：工具级授权不许覆盖尚未存在/从未见过的动作，
     *       否则将来技能新增动作会被旧授权自动放行 —— 方向只能更严）。</li>
     * </ul>
     */
    private int opSpec(String key, String query) {
        if (key.equals(query)) return key.length() + 1000;
        if (!query.startsWith(key + ".")) return -1;
        if (key.indexOf('.') < 0) {
            boolean known = knownOps != null ? knownOps.contains(query) : grantOps.contains(query);
            if (!known) return -1;
        }
        return key.length();
    }

    /**
     * 收一条 v6 结构里读出来的 Skill 条目：<b>先判归属</b> —— 技能文件里写了不属于它的 op 就整条忽略，
     * 并在日志里点名一行（{@link #warnOutOfScope}）。
     */
    private void addParsed(boolean ban, String op, List<String> ps) { addParsed(ban, op, ps, true); }

    /**
     * @param registryGate 要不要同时过"这个动作在注册表里"这一道：<b>v6 新形状</b>要（规格 §3：
     *                     技能文件只能写它自己提供的 op）；<b>旧形状</b>不要 —— 那是 v5 兼容路，
     *                     老口径只有归属这一道（"旧嵌套写法继续能读"）。
     */
    private void addParsed(boolean ban, String op, List<String> ps, boolean registryGate) {
        String o = op == null ? "" : op.trim();
        if (o.isEmpty()) return;
        if (!inScope(o, registryGate)) {
            warnOutOfScope(o, registryGate);
            return;
        }
        addEntry(new Entry((ban ? "Ban[\"" : "Run[\"") + o + "\"]", ban, o, ps));
    }

    /**
     * 这个 op 现在这一遍读盘里给不给进来（技能文件：不属于它的 op 一律忽略）。
     *
     * <p><b>装载期只有一道关：归属</b> —— 这个 op 的工具得归当前这个技能（core 文件不过这一道）。
     * <b>不带点的工具级键（{@code ["weather"]} / {@code ["memory"]}）照样接受</b>：
     * "这个动作存不存在"是<b>判定阶段</b>的事（{@link #opSpec}：工具级键只覆盖已知动作），
     * 绝不能在装载期把整条工具级授权丢掉（v5 式技能文件会因此整条失效）。</p>
     *
     * <p>唯一例外：拿得到注册表清单时，技能文件里写了一个<b>点分</b>动作而清单里根本没有
     * （{@code weather.extra}）⇒ 那是一条写错的动作，按越界忽略 + 点名（规格 §3 第 1 条）。</p>
     */
    private boolean inScope(String op, boolean registryGate) {
        if (readScopeSkill == null) return true;                  // core：全部接受
        if (owners == null) return false;                         // 拿不到归属：技能文件一律不读
        String tool = toolOf(op);
        if (!readScopeSkill.equals(owners.get(tool))) return false;
        if (!registryGate) return true;                           // 旧形状：只判归属，不查注册表
        return knownOps == null || op.indexOf('.') < 0 || knownOps.contains(op);
    }

    /** 越界忽略的一行 warn（点出技能名与那条 op，并说清是哪一道拦的）。 */
    private void warnOutOfScope(String op, boolean registryGate) {
        String owner = owners == null ? null : owners.get(toolOf(op));
        String why;
        if (owner != null && !owner.equals(readScopeSkill)) why = "属技能「" + owner + "」";
        else if (registryGate && knownOps != null && op.indexOf('.') > 0 && !knownOps.contains(op)) {
            why = "注册表里没有这个动作（该工具没提供它）";
        } else if (owner == null) why = "不是任何技能提供的工具";
        else why = "属技能「" + owner + "」";
        warn("[acl] 越界忽略：技能「" + readScopeSkill + "」的 " + SKILL_FILE_NAME + " 里写了不属于它的 op「"
                + op + "」（" + why + "）—— 这一条不生效");
    }

    /** 旧嵌套只点一次名（一个文件里可能有很多条）。 */
    private void warnLegacyNested(File f) {
        if (f != null && sameFile(legacyWarned, f)) return;
        legacyWarned = f;
        warn("[acl] 读到旧嵌套写法（键 → 动作 → 身份）—— 这是旧格式，建议敲 ai/perm gen 重排："
                + (f == null ? "" : f.getName()));
    }

    /** 点名一个认不出的键 / 块（装载告警 + 进 {@link #unknownKeys()}），一律不生效。 */
    private void unknown(String why, String key) {
        warn("[acl] " + why + (readFile == null ? "" : (" —— 不生效：" + readFile.getName())));
        if (key != null && !key.isEmpty() && !unknowns.contains(key)) unknowns.add(key);
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Throwable t) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }

    /**
     * 一次性读旧账本（v3 资源 ACL）。只有 {@code T[…]} 那几条能翻译成新口径：
     * 位里有 {@code X} = {@code Run}，没有 = {@code Ban}（旧账本里的"一位都不给"就是显式拒绝）；
     * {@code A/B/C/E} 那几行按新语义<b>没有意义</b>（资源不再有位），忽略并计数，提醒主人。
     * 这里<b>只读不写</b>：要落成新文件，敲 {@code ai/perm gen}。
     *
     * <p>读盘同样走 {@link Jsonc}（旧账本里也不该有未过滤内容）：独占一行的 {@code //} / {@code #}
     * 注释行在喂 Gson 之前就丢掉；{@code entries} 数组里的 {@code "# 工具分组说明"} 那种<b>字符串</b>
     * 注释行不是"独占一行的注释"，会原样进数组，在本方法里按老口径跳过（不判、不报错）。</p>
     */
    private void readLegacy() {
        File legacy = legacyFileOf(dataRoot);
        if (legacy == null || !legacy.isFile()) return;
        JsonObject root = J.obj(Jsonc.read(legacy));
        if (root == null) return;
        JsonArray arr = J.list(root, "entries");
        if (arr == null) return;
        int ok = 0;
        for (int i = 0; i < arr.size(); i++) {
            String raw = str(arr.get(i));
            if (raw.isEmpty()) continue;
            if (raw.trim().startsWith("#")) continue;          // 数组里的纯注释行（工具分组说明）
            int b = raw.indexOf('[');
            int e = raw.lastIndexOf(']');
            if (b <= 0 || e < b) continue;
            String cls = raw.substring(0, b).trim().toUpperCase(Locale.ROOT);
            JsonArray f = J.arr("[" + raw.substring(b + 1, e).trim() + "]");
            if (f == null || f.size() < 3) continue;
            if (!"T".equals(cls)) {
                legacySkipped++;
                continue;
            }
            String principal = principalKey(str(f.get(0)));
            String tool = str(f.get(1)).trim();
            String bits = str(f.get(2)).trim().toUpperCase(Locale.ROOT);
            if (principal == null || tool.isEmpty()) continue;
            boolean ban = bits.indexOf('X') < 0;
            List<String> ps = new ArrayList<String>();
            ps.add(principal);
            String op = tool;
            Entry le = new Entry((ban ? "Ban" : "Run") + "[\"" + op + "\",\"" + principal + "\"]", ban, op, ps);
            entries.add(le);
            if (!opFiles.containsKey(op)) opFiles.put(op, legacy);
            ok++;
        }
        migrated = ok;
        skillPoolDirty = true;
        if (ok > 0 || legacySkipped > 0) {
            warn("[acl] 读到旧账本 " + LEGACY_FILE_NAME + "：" + ok + " 条技能授权已按新口径读入"
                    + (legacySkipped > 0 ? ("，" + legacySkipped + " 条资源类条目（A/B/C/E）已忽略 —— 资源不再有位") : "")
                    + "；要落成 " + CORE_FILE_NAME + " 就敲 ai/perm gen");
        }
    }

    private void reject(String raw, String why) {
        rejects.add(new Reject(raw, why));
        warn("[acl] 认不出的条目：" + raw + "   ← " + why);
    }

    private void warn(String s) { if (out != null) out.warn(s); }

    // ==================== 判定 ====================

    /**
     * <b>Skill 域</b>：这个身份能不能用这个 op（签名不变）。
     *
     * @return {@code null} = 放行；否则是可直接回给模型的拒绝原文（含 {@link #DENY_PREFIX}）
     */
    public String allow(Caller c, String op) {
        String o = op == null ? "" : op.trim();
        if (o.isEmpty()) return DENY_PREFIX + "没有给出 op 名 —— 按拒绝处理（fail-closed）";
        if (c == null) return null;                       // 内部 / 诊断：不筛
        if (isFree(c)) return null;                       // 主人与她本人：全权
        if (o.indexOf('.') < 0) return allowTool(c, o);   // 问"整把工具"：另有一套口径
        Decision d = decide(skillPool(), "Skill", o, c, "Run", OP_COVER);
        if (d.kind == Decision.BAN) return DENY_PREFIX + "「" + o + "」在技能管控表的黑名单里";
        if (d.kind == Decision.RUN) return null;
        if (d.kind == Decision.EMPTY) return DENY_PREFIX + "「" + o + "」没有授权给你（列出来了，但谁都没给）";
        return DENY_PREFIX + "「" + o + "」没有授权给你（未列到 = 未授权）";
    }

    /**
     * 问"这一整把工具能不能用"（op 名不带点）。
     *
     * <ol>
     *   <li>表里有<b>工具级键</b> ⇒ 按它判（{@code Ban} 拒 / {@code Run} 放行）；</li>
     *   <li>没有工具级键 ⇒ 它的动作级键里<b>只要有一个</b>放行就算能用 —— 与
     *       {@code Registry.visible} 的口径一致（只放开某个动作时，模型仍然看得见这把工具去调它）；</li>
     *   <li>一个都没有 ⇒ 未授权。</li>
     * </ol>
     */
    private String allowTool(Caller c, String tool) {
        List<Rule> pool = skillPool();
        Decision exact = decide(pool, "Skill", tool, c, "Run", COVER_EXACT);
        if (exact.kind == Decision.BAN) return DENY_PREFIX + "「" + tool + "」在技能管控表的黑名单里";
        if (exact.kind == Decision.RUN) return null;
        for (int i = 0; i < pool.size(); i++) {
            Rule r = pool.get(i);
            if ("Ban".equals(r.side) || !r.key.startsWith(tool + ".")) continue;
            if (hit(r.principals, c) > 0) return null;
        }
        return DENY_PREFIX + "「" + tool + "」没有授权给你（未列到 = 未授权）";
    }

    /** 同 {@link #allow(Caller, String)} 的布尔版。 */
    public boolean allowed(Caller c, String op) { return allow(c, op) == null; }

    /**
     * 这个身份在给定 op 清单里能用哪些（{@link #allow(Caller, String)} 的批量版）。
     * 给上下文事实块与控制台清单用。
     */
    public List<String> opsFor(Caller c, Collection<String> known) {
        List<String> l = new ArrayList<String>();
        if (known == null) return l;
        for (String op : known) if (allowed(c, op)) l.add(op);
        return l;
    }

    /**
     * <b>DB 域的写判定</b>：{@code null} = 可改；否则是可直接回给模型的拒绝原文。
     *
     * <p>命中 {@code Run} 之后<b>还要过"行归属"</b>：那一行的归属主体必须等于调用者；
     * 要让某个人改别人的行，必须把<b>他本人</b>指名（{@code user:<QQ>} / {@code group:<群号>}）
     * 写进 {@code Run} —— 靠 {@code ALLUSER} 命中的只动得了自己那一份。</p>
     *
     * @param dataKey  数据类别（规格 §3 的白名单；认不出 ⇒ 拒 + 提示"装载时已点名"）
     * @param rowOwner 这一行的归属主体（{@code user:<QQ>} / {@code group:<群号>}；{@code null}/空 = 无归属的共享行）
     */
    public String dbDeny(Caller c, String dataKey, String rowOwner) {
        String k = dataKey == null ? "" : dataKey.trim();
        if (k.isEmpty()) return DENY_PREFIX + "没有给出数据类别 —— 按拒绝处理（fail-closed）";
        if (c == null) return null;                       // 内部 / 诊断：不筛
        if (isFree(c)) return null;                       // 主人与她本人：全权
        Decision d = decide(rules, "DB", k, c, "Run", COVER_DOT);
        if (d.kind == Decision.RUN) {
            // 行归属：无归属行（rowOwner=null）= 共享行，只有被指名的身份动得了（规格 §2.5-5）
            if (rowOwned(c, rowOwner) || d.named) return null;
            return DENY_PREFIX + "这条记录不属于你（只能改你自己那一份）";
        }
        // 认不出的键（连宽键都不覆盖它）才有"不认识的权限键"这一说；被宽键覆盖的照宽键判
        if (!DB_KEYS.contains(k) && !coveredByKnownKey("DB", k)) {
            return DENY_PREFIX + "不认识的权限键「" + k + "」（装载时已点名）";
        }
        return DENY_PREFIX + "这类数据（" + k + "）你不能改 —— 只有主人与她本人能改";
    }

    /**
     * <b>DB 域的读判定</b>：{@code null} = 可读。
     * <p>{@code Ban > Read > 未定义}，而 {@code DB} 的"未定义"是<b>能读</b> —— 这是全表唯一默认放开的地方
     * （规格 §2）⇒ 装载时用 {@link #readDefaultList()} 点名单。认不出的键（也没有宽键覆盖）一律拒，
     * 不吃默认放开这一口。</p>
     */
    public String dbReadDeny(Caller c, String dataKey) {
        String k = dataKey == null ? "" : dataKey.trim();
        if (k.isEmpty()) return DENY_PREFIX + "没有给出数据类别 —— 按拒绝处理（fail-closed）";
        if (c == null) return null;
        if (isFree(c)) return null;
        Decision d = decide(rules, "DB", k, c, "Read", COVER_DOT);
        if (d.kind == Decision.BAN || d.kind == Decision.EMPTY) {
            // 显式空 Read 表（EMPTY）与 Ban 一样是"不能读"，绝不被"未定义 = 能读"的默认吃掉
            return DENY_PREFIX + "这类数据（" + k + "）你不能读";
        }
        if (!DB_KEYS.contains(k) && d.kind == Decision.NONE && !coveredByKnownKey("DB", k)) {
            return DENY_PREFIX + "不认识的权限键「" + k + "」（装载时已点名）";
        }
        return null;                                      // Read 命中 / 未定义：能读
    }

    /** 有没有"已知类别"的键覆盖这个查询键（宽键覆盖已知/下级子键；认不出的键自己不做决定）。 */
    private boolean coveredByKnownKey(String domain, String key) {
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (!domain.equals(r.domain)) continue;
            if (!DB_KEYS.contains(r.key)) continue;
            if (COVER_DOT.spec(r.key, key) >= 0) return true;
        }
        return false;
    }

    /**
     * <b>File 域的判定</b>：{@code null} = 可碰。
     *
     * @param path  要碰的路径（绝对路径最准；装载时与判定时用<b>同一套</b>归一化）
     * @param write {@code true} 看 {@code Run}（改/写/删/建），{@code false} 看 {@code Read}（读/找/下）
     */
    public String fileDeny(Caller c, String path, boolean write) {
        String raw = path == null ? "" : path.trim();
        if (c == null) return null;                       // 内部 / 诊断：不筛
        if (isFree(c)) return null;                       // 主人与她本人：全权
        String why = pathProblem(raw);
        String nk = why == null ? normPath(raw) : "";
        if (why != null || nk.isEmpty()) return DENY_PREFIX + "这个路径不在允许范围（" + raw + "）";
        Decision d = decide(rules, "File", nk, c, write ? "Run" : "Read", COVER_PATH);
        if (d.kind == Decision.RUN) return null;
        return DENY_PREFIX + "这个路径不在允许范围（" + nk + "）";
    }

    /**
     * 域内判定：把覆盖这个键的规则键<b>从最具体往最宽</b>过一遍，第一把给出决定的键说了算。
     *
     * <ul>
     *   <li><b>同一把键上</b>：{@code Ban} 先于 {@code Run} / {@code Read}（同键 Ban 赢），身份取并集；</li>
     *   <li><b>不同的键之间</b>：更具体的键优先（{@code pref.donation} 比 {@code pref} 具体；
     *       {@code File} 按最长路径）—— 具体那把<b>没给这个身份任何决定</b>时，才落到更宽的键；</li>
     *   <li>一把覆盖键都没有 ⇒ {@link Decision#NONE}（各域按自己的默认值收尾）。</li>
     * </ul>
     *
     * @param runSide {@code Run}（写：Skill/DB/File 都看它）或 {@code Read}（读：DB/File）
     * @param cover   键覆盖口径（op 域看注册表，DB 看点分子键，File 看最长路径）
     * <p><b>空身份表也是一条决定</b>：某把键在表里出现过、身份表却是空的（"列出来了但谁都不给"）
     * ⇒ {@link Decision#EMPTY}，不再往更宽的键落、也不吃该域"未定义"的默认值 —— DB 的读默认是
     * "能读"，不这样 {@code Read["sent"]: []} 会被吃成"能读"。</p>
     */
    private Decision decide(List<Rule> pool, String domain, String query, Caller c,
                            final String runSide, final Cover cover) {
        Decision d = new Decision();
        final ArrayList<String> keys = new ArrayList<String>();
        for (int i = 0; i < pool.size(); i++) {
            Rule r = pool.get(i);
            if (!domain.equals(r.domain)) continue;
            if (cover.spec(r.key, query) < 0) continue;
            if (!keys.contains(r.key)) keys.add(r.key);
        }
        if (keys.isEmpty()) return d;
        final String q = query;
        Collections.sort(keys, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                int sa = cover.spec(a, q);
                int sb = cover.spec(b, q);
                if (sa != sb) return sb - sa;                  // 具体度降序（长的在前）
                return a.compareTo(b);                         // 同具体度按键名：结果才是确定的
            }
        });
        for (int i = 0; i < keys.size(); i++) {
            String k = keys.get(i);
            boolean ban = false;
            boolean run = false;
            boolean named = false;
            boolean sideSeen = false;          // 这一侧在这把键上有没有规则
            boolean sideEmptyOnly = true;      // 有规则的话，是不是清一色空身份表
            for (int j = 0; j < pool.size(); j++) {
                Rule r = pool.get(j);
                if (!domain.equals(r.domain) || !r.key.equals(k)) continue;
                boolean isRunSide = r.side.equals(runSide);
                if (!"Ban".equals(r.side) && !isRunSide) continue;
                if (isRunSide) {
                    sideSeen = true;
                    if (!r.principals.isEmpty()) sideEmptyOnly = false;
                }
                int h = hit(r.principals, c);
                if (h == 0) continue;
                if ("Ban".equals(r.side)) {
                    ban = true;                                // 同键：Ban 压过 Run / Read
                } else {
                    run = true;
                    if (h == 2) named = true;
                }
            }
            if (ban) { d.kind = Decision.BAN; d.key = k; return d; }
            if (run) { d.kind = Decision.RUN; d.key = k; d.named = named; return d; }
            if (sideSeen && sideEmptyOnly) {
                // 显式写下的空身份表 = "列出来了但谁都不给" = 这一把键上的决定（不再往更宽的键落）
                d.kind = Decision.EMPTY;
                d.key = k;
                return d;
            }
        }
        return d;
    }

    /** 身份表命中：0 = 没命中；1 = 命中（含 {@code ALLUSER}）；2 = <b>指名</b>命中（user:/group: 直接对上）。 */
    private static int hit(List<String> ps, Caller c) {
        if (c == null) return 0;
        int r = 0;
        for (int i = 0; i < ps.size(); i++) {
            String p = ps.get(i);
            if (ALLUSER_KEY.equals(p)) { r = 1; continue; }
            if (p.startsWith(USER_PREFIX) && c.qq() > 0L && idOf(p, USER_PREFIX) == c.qq()) return 2;
            if (p.startsWith(GROUP_PREFIX) && c.groupId() > 0L && idOf(p, GROUP_PREFIX) == c.groupId()) return 2;
        }
        return r;
    }

    /** 主人（MASTER）与她本人（SYSTEM）恒全权：不进判定（规格 §0）。 */
    private static boolean isFree(Caller c) {
        if (c == null) return false;
        Caller.Kind k = c.kind();
        return k == Caller.Kind.MASTER || k == Caller.Kind.SYSTEM;
    }

    /**
     * 这一行是不是他自己的：归属主体必须等于调用者。
     * <p>{@code null}/空 = 无归属的共享行（{@code note} 这类）⇒ 不算他的，只有被指名的人动得了。</p>
     */
    private static boolean rowOwned(Caller c, String rowOwner) {
        if (c == null || rowOwner == null) return false;
        String raw = rowOwner.trim();
        if (raw.isEmpty()) return false;
        String p = principalKey(raw);
        if (p == null) p = raw;                            // 认不出的写法：按字面比，对不上就拒
        if (p.startsWith(USER_PREFIX)) return c.qq() > 0L && idOf(p, USER_PREFIX) == c.qq();
        if (p.startsWith(GROUP_PREFIX)) return c.groupId() > 0L && idOf(p, GROUP_PREFIX) == c.groupId();
        return false;
    }

    /**
     * 路径归一化（规格 §3）：反斜杠统一成正斜杠、去重复斜杠、解析 {@code .} 与 {@code ..}；
     * 末尾斜杠<b>保留</b>（= 目录，管整棵子树）。
     */
    private static String normPath(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replace('\\', '/');
        if (s.isEmpty()) return "";
        boolean abs = s.charAt(0) == '/';
        boolean dir = s.endsWith("/");
        String[] parts = s.split("/");
        ArrayList<String> keep = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!keep.isEmpty() && !"..".equals(keep.get(keep.size() - 1))) keep.remove(keep.size() - 1);
                else if (!abs) keep.add("..");
                continue;
            }
            keep.add(p);
        }
        StringBuilder sb = new StringBuilder();
        if (abs) sb.append('/');
        for (int i = 0; i < keep.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(keep.get(i));
        }
        if (sb.length() == 0) return dir ? "/" : "";
        if (dir && sb.charAt(sb.length() - 1) != '/') sb.append('/');
        return sb.toString();
    }

    /** 路径写法为什么不能用（能用返回 {@code null}）：只认具体路径，不认通配，也不认空。 */
    private static String pathProblem(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "空路径";
        if (raw.indexOf('*') >= 0 || raw.indexOf('?') >= 0) return "不认通配符（要整棵子树就把目录写成末尾带斜杠）";
        if (raw.indexOf('\u0000') >= 0) return "路径里有 NUL";
        return null;
    }

    /** 比对用的路径：Windows 下路径大小写不敏感，Linux 下敏感（规格 §3）。 */
    private static String cmpPath(String s) { return WIN ? s.toLowerCase(Locale.ROOT) : s; }

    // ==================== 查看 ====================

    /** 全部 Skill 条目（输出格式冻结：控制台与事实块都按它排）。 */
    public List<Entry> entries() { return new ArrayList<Entry>(entries); }

    /** 认不出来的条目。 */
    public List<Reject> rejects() { return new ArrayList<Reject>(rejects); }

    /** 本次是不是从旧账本迁进来的；{@code -1} = 不是。 */
    public int migratedCount() { return migrated; }

    /** 迁移时忽略掉的旧资源类条目数。 */
    public int legacySkipped() { return legacySkipped; }

    /** 这次从哪些权限文件读的（按读序；core 在前；没读到的文件不在这里）。 */
    public List<File> sources() { return new ArrayList<File>(sources); }

    /** 这次从某个文件读进来几条 Skill 条目（没读过 = 0）。 */
    public int entriesOf(File f) {
        if (f == null) return 0;
        Integer n = sourceCounts.get(f.getAbsolutePath());
        return n == null ? 0 : n.intValue();
    }

    /** 这次从某个文件读进来几条 DB/File 规则（没读过 = 0）。 */
    public int rulesOf(File f) {
        if (f == null) return 0;
        Integer n = ruleCounts.get(f.getAbsolutePath());
        return n == null ? 0 : n.intValue();
    }

    /**
     * 来源汇总那一行（控制台 {@code ai/perm} 表尾用）：{@code core 79 条；技能 22 份共 92 条}。
     * <p>按"这一份文件是不是技能目录里的"分开数；一份都没读到就说"没有任何权限文件"。
     * <b>输出格式冻结</b>（主人对权限的认知靠这一行）。</p>
     */
    public String sourceStat() {
        int coreN = 0;
        int coreFiles = 0;
        int skillN = 0;
        int skillFiles = 0;
        for (File f : sources) {
            int n = entriesOf(f);
            if (isSkillFile(f)) {
                skillFiles++;
                skillN += n;
            } else {
                coreFiles++;
                coreN += n;
            }
        }
        if (coreFiles == 0 && skillFiles == 0) return "权限文件：一个都没有（= 一切未授权）";
        StringBuilder sb = new StringBuilder("权限文件：");
        if (coreFiles > 0) sb.append("core ").append(coreN).append(" 条");
        else sb.append("core 没有（缺 ").append(CORE_FILE_NAME).append("）");
        sb.append("；技能 ").append(skillFiles).append(" 份共 ").append(skillN).append(" 条");
        return sb.toString();
    }

    /** 这个文件是不是技能目录里的权限文件（{@code <skillsDir>\<技能名>\perms.jsonc}）。 */
    public boolean isSkillFile(File f) {
        if (f == null || skillsDir == null) return false;
        File p = f.getParentFile();
        if (p == null || p.getParentFile() == null) return false;
        return sameFile(p.getParentFile(), skillsDir) && SKILL_FILE_NAME.equals(f.getName());
    }

    /** 这个 op 这次是从哪个文件读来的（不在任何文件里 = {@code null}）。 */
    public File fileOfOp(String op) {
        return op == null ? null : opFiles.get(op.trim());
    }

    /**
     * 这个 op 按<b>归属</b>应该待在哪个文件：技能提供的工具 → 该技能目录里的 {@link #SKILL_FILE_NAME}；
     * 其余（内置工具、{@code napcat.*}、没有归属的 op）→ 数据根下的 {@link #CORE_FILE_NAME}。
     * <p>只回答"该去哪"，不判文件在不在 —— 回写落点见 {@link #targetOf(String)}。</p>
     */
    public File ownerFileOf(String op) {
        String tool = toolOf(op);
        String owner = owners == null ? null : owners.get(tool);
        File sk = skillFileOf(skillsDir, owner);
        return sk != null ? sk : file;
    }

    /**
     * 这个 op 现在真要写回哪个文件（控制台回执与 {@link #saveAll} 用）：
     * 优先归属文件；{@code gen}（不新建文件）时若归属文件还不存在，退回它读来的那个文件（旧统一表除外），
     * 再退回 core。
     */
    public File targetOf(String op) { return targetOf(op, owners, true); }

    private File targetOf(String op, Map<String, String> own, boolean create) {
        String tool = toolOf(op);
        String owner = own == null ? null : own.get(tool);
        File want = skillFileOf(skillsDir, owner);
        if (want == null) want = file;
        if (create || (want != null && want.isFile())) return want;
        File from = fileOfOp(op);
        if (from != null && from.isFile() && !isUnified(from)) return from;
        if (file != null && file.isFile()) return file;
        return want;
    }

    /**
     * <b>任意一个键</b>现在真要写回哪个文件（控制台回执用）：op 走归属（{@link #targetOf(String)}）；
     * DB / File 规则用它读自的那份文件，读自旧统一表（只读）时退回 core。
     */
    public File targetOfKey(String key) {
        String k = key == null ? "" : key.trim();
        if (!k.isEmpty()) {
            String nk = normPath(k);
            for (Rule r : rules) {
                if (!r.key.equals(k) && !r.key.equals(nk)) continue;
                File f = r.file;
                if (f != null && !isUnified(f)) return f;
            }
        }
        return targetOf(k);
    }

    /** 是不是数据根下那份旧的统一表（{@link #FILE_NAME}）：只读不写，回写时不当落点。 */
    private boolean isUnified(File f) {
        File u = unifiedFileOf(dataRoot);
        return u != null && sameFile(f, u);
    }

    /** 最近一次 {@link #saveAll} 真正写了的文件（回执/摘要用；下次 saveAll 会清空重填）。 */
    public List<File> writtenFiles() { return new ArrayList<File>(written); }

    /** 最近一次 {@link #saveAll} 有决定、但没有任何权限文件可写的键（{@code gen} 的提示用）。 */
    public List<String> unwrittenOps() { return new ArrayList<String>(unwritten); }

    /**
     * 逐行清单（人能读的形态）。
     * <p><b>输出格式冻结</b>：只列 Skill 条目与认不出的条目（DB/File 的规则另有
     * {@link #dbClasses()} / {@link #fileKeys()} / {@link #summary()} 给控制台用），
     * 免得既有消费方（{@code Builtins.permShow}）看到的行数悄悄变多。</p>
     */
    public List<String> list() {
        List<String> l = new ArrayList<String>();
        for (Entry e : entries) l.add(e.text());
        for (Reject r : rejects) l.add("[?] " + r.text());
        return l;
    }

    /**
     * DB / File 域的规则逐行视图（域 → 侧 → 键 → 身份），给控制台按"域 → 侧 → 键"打印用。
     * <p>同一个"域 + 侧 + 键"多处声明在这里<b>先取并集再打印</b>（规格 §2 的并集语义），
     * 免得同一把键在控制台上出现两行、让人以为互相覆盖。Skill 域仍走 {@link #entries()}
     * （那一套的输出格式冻结）；这一份是<b>新增的口</b>，不动 {@link #list()} 的既有行数与顺序。</p>
     */
    public List<String> ruleList() {
        List<Rule> merged = new ArrayList<Rule>();
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            Rule hit = null;
            for (int j = 0; j < merged.size(); j++) {
                Rule m = merged.get(j);
                if (m.domain.equals(r.domain) && m.side.equals(r.side) && m.key.equals(r.key)) {
                    hit = m;
                    break;
                }
            }
            if (hit == null) {
                Rule m = new Rule(r.domain, r.side, r.key, r.file);
                m.principals.addAll(r.principals);
                merged.add(m);
            } else {
                for (int k = 0; k < r.principals.size(); k++) {
                    String p = r.principals.get(k);
                    if (!hit.principals.contains(p)) hit.principals.add(p);
                }
            }
        }
        List<String> l = new ArrayList<String>();
        for (int i = 0; i < merged.size(); i++) l.add(merged.get(i).text());
        return l;
    }

    /** op → 身份分组（给控制台按 op 展示用；输出格式冻结）。 */
    public Map<String, List<Entry>> byOp() {
        Map<String, List<Entry>> m = new LinkedHashMap<String, List<Entry>>();
        for (Entry e : entries) {
            List<Entry> l = m.get(e.op());
            if (l == null) {
                l = new ArrayList<Entry>();
                m.put(e.op(), l);
            }
            l.add(e);
        }
        return m;
    }

    /** 一行摘要（输出格式冻结：字段与顺序都不动）。 */
    public String stat() {
        int ban = 0;
        for (Entry e : entries) if (e.ban()) ban++;
        StringBuilder sb = new StringBuilder();
        sb.append(entries.size()).append(" 条（Run ").append(entries.size() - ban).append(" / Ban ").append(ban).append("）");
        sb.append("，覆盖 op ").append(byOp().size()).append(" 个");
        if (!rejects.isEmpty()) sb.append("，认不出 ").append(rejects.size()).append(" 条");
        if (migrated >= 0) sb.append("（读自旧账本 ").append(LEGACY_FILE_NAME).append("）");
        return sb.toString();
    }

    /** 已知数据键（规格 §3 的白名单；控制台 / 汇总用）。 */
    public List<String> dbClasses() { return new ArrayList<String>(DB_KEYS); }

    /** 已知路径键（表里出现过的那些，已归一化；控制台 / 汇总用）。 */
    public List<String> fileKeys() {
        List<String> l = new ArrayList<String>();
        for (Rule r : rules) {
            if (!"File".equals(r.domain)) continue;
            if (!l.contains(r.key)) l.add(r.key);
        }
        return l;
    }

    /** 认不出的键（装载时已点名，一律不生效）：数据键 / 路径键 / 骨架层拼错的块与侧。 */
    public List<String> unknownKeys() { return new ArrayList<String>(unknowns); }

    /**
     * 没有显式 {@code Read} 声明、按默认能读的类别（{@code DB} 是唯一默认放开的域）。
     *
     * <p>两条排除（规格 §2.5-6）：① <b>有</b>显式 {@code Read} 声明（宽键覆盖也算 —— 写了
     * {@code Read["pref"]}，{@code pref.donation} 就跟着 {@code pref} 那把键走）；② 在任何
     * {@code Ban} 里出现过（被禁的类别不该出现在"按默认能读"的清单里，否则汇总会误导主人）。</p>
     *
     * <p>"算不算一条声明"看的是<b>真正进了表的规则</b>：只写了 {@code MASTER} / {@code SYSTEM}
     * 的行按 §0 整行忽略（见 {@link #literalEmpty(JsonElement)}），<b>不占</b>"已声明"的位置 ——
     * 否则 {@code Ban["memory"]: ["MASTER","SYSTEM"]} 这种空壳会把 {@code memory} 从披露里挤掉，
     * 而它其实仍然按默认能读。</p>
     */
    public List<String> readDefaultList() {
        List<String> l = new ArrayList<String>();
        for (String k : DB_KEYS) {
            if (declared("Read", k)) continue;
            if (declared("Ban", k)) continue;
            l.add(k);
        }
        return l;
    }

    /**
     * DB 域这一侧有没有<b>真正生效的声明</b>覆盖它（只写了自由身份的行不算 —— 那种行按 §0
     * 整行忽略，见 {@link #literalEmpty(JsonElement)}）。
     */
    private boolean declared(String side, String key) {
        for (int i = 0; i < rules.size(); i++) {
            Rule r = rules.get(i);
            if (!"DB".equals(r.domain) || !side.equals(r.side)) continue;
            if (COVER_DOT.spec(r.key, key) >= 0) return true;
        }
        return false;
    }

    /**
     * 装载汇总那一行（规格 §4，控制台 / 启动用）：
     * {@code 权限：Skill N 条 / DB M 条 / File K 条；未声明 Read 的类别（按默认能读）：…；认不出的键：…}
     */
    public String summary() {
        StringBuilder sb = new StringBuilder("权限：Skill ").append(entries.size())
                .append(" 条 / DB ").append(countRules("DB")).append(" 条 / File ").append(countRules("File")).append(" 条");
        List<String> dflt = readDefaultList();
        sb.append("；未声明 Read 的类别：").append(dflt.isEmpty() ? "无" : join(dflt, "、"));
        sb.append("；认不出的键：").append(unknowns.isEmpty() ? "无" : join(unknowns, "、"));
        return sb.toString();
    }

    private int countRules(String domain) {
        int n = 0;
        for (Rule r : rules) if (domain.equals(r.domain)) n++;
        return n;
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    // ==================== 主人写表 ====================

    /** 授权（{@code ban=false}）或封禁（{@code ban=true}）某个身份用某个 op。 */
    public boolean grant(String op, String principal, boolean ban) { return grant(op, principal, ban, ""); }

    /** 同上，并把这行的中文释义一起写上（给人看；基板不读释义）。 */
    public boolean grant(String op, String principal, boolean ban, String note) {
        String o = op == null ? "" : op.trim();
        String p = principalKey(principal);
        if (o.isEmpty() || p == null) return false;
        revokeOp(o, p);                                // 同一个 op+身份只留最后写的那一条
        List<String> ps = new ArrayList<String>();
        ps.add(p);
        String raw = withNote((ban ? "Ban[\"" : "Run[\"") + o + "\",\"" + p + "\"]", note);
        entries.add(new Entry(raw, ban, o, ps));
        skillPoolDirty = true;
        return true;
    }

    /**
     * 给<b>任意一个键</b>加一条决定（②波次的 {@code ai/perm db|file run|ban|read} 用）：
     * 键是已知数据类别 ⇒ {@code DB} 域；看着像路径 ⇒ {@code File} 域；其余 ⇒ 当 op（走 {@link #grant}）。
     *
     * @param side {@code run} / {@code read} / {@code ban}（大小写不敏感；op 层没有 {@code read} 这一侧）
     */
    public boolean grantKey(String key, String principal, String side) {
        String k = key == null ? "" : key.trim();
        String p = principalKey(principal);
        String sd = side == null ? "" : side.trim().toLowerCase(Locale.ROOT);
        if (k.isEmpty() || p == null) return false;
        if (!"run".equals(sd) && !"read".equals(sd) && !"ban".equals(sd)) return false;
        String name = "run".equals(sd) ? "Run" : ("read".equals(sd) ? "Read" : "Ban");
        if (DB_KEYS.contains(k)) return addGrant("DB", name, k, p);
        if (looksLikePath(k) && pathProblem(k) == null && !normPath(k).isEmpty()) {
            return addGrant("File", name, normPath(k), p);
        }
        if ("Read".equals(name)) return false;         // op 层没有"读"这一侧
        return grant(k, p, "Ban".equals(name));
    }

    /** 看着像路径：带正/反斜杠（权限表的 File 键就是这么写的）。 */
    private static boolean looksLikePath(String k) { return k.indexOf('/') >= 0 || k.indexOf('\\') >= 0; }

    /** 给 DB / File 域加一条决定（同键同侧同身份不重复写；并集语义天然成立）。 */
    private boolean addGrant(String domain, String side, String key, String p) {
        for (Rule r : rules) {
            if (r.domain.equals(domain) && r.side.equals(side) && r.key.equals(key)) {
                if (!r.principals.contains(p)) r.principals.add(p);
                return true;
            }
        }
        Rule r = new Rule(domain, side, key, file);
        r.principals.add(p);
        rules.add(r);
        return true;
    }

    /**
     * 撤掉某个身份在某个键上的全部条目 / 规则。
     * <p>键会<b>自动判断属于哪一块</b>（规格 §6）：op、数据类别、路径 —— 一次把所有命中的地方都摘干净。</p>
     *
     * <p>两条路径口径一致：<b>摘空了 = 整条删</b>（不是留一条空壳）。{@code Skill} 的条目与
     * {@code DB}/{@code File} 的规则都按这个来 —— 主人翻文件时不该看到一堆 {@code []}，
     * 而且"没列到"与"列出来但空"在判定上都不给权限，留空壳只会让文件变脏。
     * （<b>手写</b>的空身份表不受影响：装载时照旧读成"列出来了但谁都不给 = 未授权"，只是 revoke 不制造它。）</p>
     */
    public boolean revoke(String key, String principal) {
        String o = key == null ? "" : key.trim();
        String p = principalKey(principal);
        if (o.isEmpty() || p == null) return false;
        boolean changed = revokeOp(o, p);
        String nk = normPath(o);
        for (int i = rules.size() - 1; i >= 0; i--) {
            Rule r = rules.get(i);
            if (!r.key.equals(o) && !r.key.equals(nk)) continue;
            if (!r.principals.contains(p)) continue;
            r.principals.remove(p);
            if (r.principals.isEmpty()) rules.remove(i);   // 摘空 = 整条删（与 revokeOp 同一口径）
            changed = true;
        }
        if (changed) skillPoolDirty = true;
        return changed;
    }

    /**
     * 撤掉某个身份在某个 op 上的全部 Skill 条目。
     *
     * <p>一条条目里若还写着别的身份，<b>只把这个身份摘掉、其余原样留着</b>（摘空了才整条删）；
     * 所以要撤某人不是"删那一行"，而是"把那个人从那一行里去掉"。</p>
     */
    private boolean revokeOp(String op, String principal) {
        String o = op == null ? "" : op.trim();
        String p = principalKey(principal);
        if (o.isEmpty() || p == null) return false;
        boolean changed = false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (!e.op().equals(o) || !e.principals().contains(p)) continue;
            entries.remove(i);
            List<String> rest = new ArrayList<String>();
            for (String q : e.principals()) if (!q.equals(p)) rest.add(q);
            if (!rest.isEmpty()) {
                StringBuilder sb = new StringBuilder(e.ban() ? "Ban[\"" : "Run[\"");
                sb.append(e.op()).append('"');
                for (String q : rest) sb.append(",\"").append(q).append('"');
                sb.append(']');
                entries.add(i, new Entry(withNote(sb.toString(), e.note()), e.ban(), e.op(), rest));
            }
            changed = true;
        }
        if (changed) skillPoolDirty = true;
        return changed;
    }

    /** 账本文件。 */
    public File file() { return file; }

    /** 数据根。 */
    public File dataRoot() { return dataRoot; }

    /** 回写成 JSON 文本（v6 新形状：域在外、侧在内、规则层中括号键）。 */
    public String renderJson() { return renderStructured(entries, rules, null, null); }

    /**
     * <b>渲染成 v6 新形状</b>：三个域（{@code Skill} / {@code DB} / {@code File}）在外，侧
     * （{@code Run} / {@code Read} / {@code Ban}）在内，规则层的键一律写成中括号数组；
     * 每个域/侧的注释写在内容的前一行（读盘时整行丢掉）。
     *
     * <pre>
     * "Skill": {
     *   // ── 谁能用（没列到 = 未授权）
     *   "Run": {
     *     // ── 记忆 memory（长期记忆）
     *     ["memory.recall", "memory.remember"]: ["ALLUSER"],
     *     ["weather"]: ["ALLUSER"]
     *   },
     *   "Ban": { }
     * }
     * </pre>
     *
     * <p><b>只写有决定的键</b>：没有条目的 op 不写（没列到 = 未授权，不写空占位 —— 本轮 gen 只在已有文件里
     * 重排，不生成完整清单）；身份表完全相同的规则合成一行键数组，这是重排不是发明
     * （{@code ["a","b"]: [X]} 读回来与分别写两条完全等价）。</p>
     *
     * <p>DB / File 的规则<b>保留显式写下的空身份表</b>：{@code Read["sent"]: []} 是一条真实的决定
     * （谁都别读），丢了它默认"能读"就会滑回来。</p>
     *
     * @param notes     op → 中文释义（可为 null；只进注释，不进键）
     * @param toolNotes 工具名 → "这把工具是干嘛的"（可为 null；只进注释）
     */
    public String renderAll(Collection<String> knownOps) { return renderAll(knownOps, null, null); }

    public String renderAll(Collection<String> knownOps, Map<String, String> notes) {
        return renderAll(knownOps, notes, null);
    }

    public String renderAll(Collection<String> knownOps, Map<String, String> notes, Map<String, String> toolNotes) {
        return renderStructured(entries, rules, notes, toolNotes);
    }

    /**
     * 渲染一批条目 + 一批规则（按文件写回时，一份文件只带它自己的东西）。
     * <p>这个入口的落点按 <b>core</b> 算（三个域都合法），行为与从前一字不差 —— 见下面那个重载。</p>
     */
    private String renderStructured(Collection<Entry> use, Collection<Rule> useRules,
                                    Map<String, String> notes, Map<String, String> toolNotes) {
        return renderStructured(use, useRules, notes, toolNotes, false);
    }

    /**
     * 同上，并说明<b>这份文件的落点是不是技能文件</b>（{@code skills\<技能名>\perms.jsonc}）。
     *
     * <p>写侧必须与读侧同口径（规格 §2.5-10）：技能文件里的 {@code File} 块<b>越界</b>
     * （路径属于部署，只许写在 {@code perms-core.jsonc}），所以技能文件<b>一个字符都不写 File 块</b>；
     * 技能文件里的 {@code DB} 块只在这个技能<b>确实带着自己的 DB 规则</b>时才写
     * （空骨架读者不认、纯噪音）。{@code perms-core.jsonc} 三个块照旧（DB / File 的合法落点，
     * 格式与注释一字不变）。</p>
     *
     * @param skillFile {@code true} = 落点是技能文件（不许 File 块；DB 块只在真有规则时写）
     */
    private String renderStructured(Collection<Entry> use, Collection<Rule> useRules,
                                    Map<String, String> notes, Map<String, String> toolNotes,
                                    boolean skillFile) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(VERSION).append(",\n");
        sb.append("  \"_readme\": ").append(J.json(README)).append(",\n");

        sb.append("\n  // ───────── 技能：谁能用哪些 op ─────────\n");
        sb.append("  \"Skill\": {\n");
        appendSide(sb, "Run", "谁能用（没列到 = 未授权）", skillLines("Run", use, notes, toolNotes), true);
        appendSide(sb, "Ban", "谁不能用（压过 Run）", skillLines("Ban", use, notes, toolNotes), false);

        // 逗号只看"后面还有没有块"：技能文件常常只写 Skill 这一个块（core 三个块照旧）
        boolean db = !skillFile || hasDomain(useRules, "DB");
        boolean fileBlock = !skillFile;
        sb.append("  }").append(db || fileBlock ? ",\n" : "\n");
        if (db) {
            sb.append("\n  // ───────── 数据（键 = 类别）：谁能改、谁能读、谁不能 ─────────\n");
            sb.append("  \"DB\": {\n");
            appendSide(sb, "Run", "谁能改（命中还要看这一行是不是他自己的）", ruleLines(useRules, "DB", "Run"), true);
            appendSide(sb, "Read", "谁能读（没列到 = 默认能读）", ruleLines(useRules, "DB", "Read"), true);
            appendSide(sb, "Ban", "谁不能改也不能读（压过 Run 与 Read）", ruleLines(useRules, "DB", "Ban"), false);
            sb.append("  }").append(fileBlock ? ",\n" : "\n");
        }
        if (fileBlock) {
            sb.append("\n  // ───────── 文件（键 = 路径；目录 = 整棵子树，最长路径优先） ─────────\n");
            sb.append("  \"File\": {\n");
            appendSide(sb, "Run", "谁能改", ruleLines(useRules, "File", "Run"), true);
            appendSide(sb, "Read", "谁能读（没列到 = 不能读）", ruleLines(useRules, "File", "Read"), true);
            appendSide(sb, "Ban", "谁不能碰（压过 Run 与 Read）", ruleLines(useRules, "File", "Ban"), false);
            sb.append("  }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    /** 这批规则里有没有这个域的（技能文件的 {@code DB} 块"确实带规则"才写）。 */
    private static boolean hasDomain(Collection<Rule> useRules, String domain) {
        if (useRules == null) return false;
        for (Rule r : useRules) if (r != null && domain.equals(r.domain)) return true;
        return false;
    }

    /**
     * 写一个侧：{@code lines} 里每条元素 = "若干注释行 + 一行规则"（注释写在规则的前一行），
     * 逗号只加在规则行尾。空侧写成 {@code "Ban": { }}。
     */
    private static void appendSide(StringBuilder sb, String side, String comment, List<String> lines, boolean more) {
        sb.append("    // ── ").append(comment).append("\n");
        sb.append("    \"").append(side).append("\": {");
        if (lines.isEmpty()) {
            sb.append("}");
            sb.append(more ? ",\n" : "\n");
            return;
        }
        sb.append("\n");
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            sb.append(i + 1 < lines.size() ? ",\n" : "\n");
        }
        sb.append("    }");
        sb.append(more ? ",\n" : "\n");
    }

    /**
     * Skill 域一个侧的行：<b>按工具分组</b>（注释一行点出这把工具是干嘛的），组内 op 排序；
     * 身份表完全相同的 op 合成一行键数组。空身份表的条目不写（Skill 没列到 = 未授权，写空只是噪声）。
     */
    private static List<String> skillLines(String side, Collection<Entry> use,
                                           Map<String, String> notes, Map<String, String> toolNotes) {
        boolean ban = "Ban".equals(side);
        TreeMap<String, TreeMap<String, List<String>>> byTool = new TreeMap<String, TreeMap<String, List<String>>>();
        if (use != null) {
            for (Entry e : use) {
                if (e.ban() != ban || e.principals().isEmpty()) continue;
                String tool = toolOf(e.op());
                TreeMap<String, List<String>> ops = byTool.get(tool);
                if (ops == null) {
                    ops = new TreeMap<String, List<String>>();
                    byTool.put(tool, ops);
                }
                List<String> ps = ops.get(e.op());
                if (ps == null) {
                    ps = new ArrayList<String>();
                    ops.put(e.op(), ps);
                }
                for (String p : e.principals()) if (!ps.contains(p)) ps.add(p);
            }
        }
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, TreeMap<String, List<String>>> g : byTool.entrySet()) {
            String tool = g.getKey();
            String tg = gloss(toolNotes == null ? null : toolNotes.get(tool), 20);
            String header = "      // ── " + (tg.isEmpty() ? tool : (tg + "（" + tool + "）")) + "\n";
            LinkedHashMap<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
            LinkedHashMap<String, List<String>> psOf = new LinkedHashMap<String, List<String>>();
            for (Map.Entry<String, List<String>> o : g.getValue().entrySet()) {
                String pk = principalsKey(o.getValue());
                List<String> ks = groups.get(pk);
                if (ks == null) {
                    ks = new ArrayList<String>();
                    groups.put(pk, ks);
                    psOf.put(pk, o.getValue());
                }
                ks.add(o.getKey());
            }
            boolean first = true;
            for (Map.Entry<String, List<String>> gr : groups.entrySet()) {
                StringBuilder head = new StringBuilder(first ? header : "");
                first = false;
                for (String op : gr.getValue()) {
                    // 释义写在内容的前一行（释义不变：来自注册表，不进键）
                    String ng = gloss(notes == null ? null : notes.get(op), 24);
                    if (!ng.isEmpty()) head.append("      // ").append(ng).append("\n");
                }
                lines.add(head.append(ruleLine(gr.getValue(), psOf.get(gr.getKey()))).toString());
            }
        }
        return lines;
    }

    /**
     * DB / File 域一个侧的行：键排序；身份表完全相同的键合成一行键数组。
     * <p>显式写下的空身份表照写（那是一条决定，不是空占位）。</p>
     */
    private static List<String> ruleLines(Collection<Rule> useRules, String domain, String side) {
        TreeMap<String, List<String>> byKey = new TreeMap<String, List<String>>();
        if (useRules != null) {
            for (Rule r : useRules) {
                if (!domain.equals(r.domain) || !side.equals(r.side)) continue;
                List<String> ps = byKey.get(r.key);
                if (ps == null) {
                    ps = new ArrayList<String>();
                    byKey.put(r.key, ps);
                }
                for (String p : r.principals) if (!ps.contains(p)) ps.add(p);
            }
        }
        LinkedHashMap<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        LinkedHashMap<String, List<String>> psOf = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, List<String>> e : byKey.entrySet()) {
            String pk = principalsKey(e.getValue());
            List<String> ks = groups.get(pk);
            if (ks == null) {
                ks = new ArrayList<String>();
                groups.put(pk, ks);
                psOf.put(pk, e.getValue());
            }
            ks.add(e.getKey());
        }
        List<String> lines = new ArrayList<String>();
        for (Map.Entry<String, List<String>> g : groups.entrySet()) {
            lines.add(ruleLine(g.getValue(), psOf.get(g.getKey())));
        }
        return lines;
    }

    /** 一行规则：{@code ["k1", "k2"]: ["ALLUSER", "user:1"]}（键一律带中括号 —— 渲染只输出带括号的）。 */
    private static String ruleLine(List<String> keys, List<String> ps) {
        StringBuilder sb = new StringBuilder("      [");
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(J.json(keys.get(i)));
        }
        sb.append("]: [");
        for (int i = 0; i < ps.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(J.json(ps.get(i)));
        }
        return sb.append(']').toString();
    }

    /** 身份表的分组键（只有"完全一样"才合成一行；U+0001 做分隔，不会与身份写法撞）。 */
    private static String principalsKey(List<String> ps) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ps.size(); i++) {
            if (i > 0) sb.append('\u0001');
            sb.append(ps.get(i));
        }
        return sb.toString();
    }

    /** 释义压短：一行里的括注不超过 {@code max} 字（表要一眼能扫）。 */
    private static String gloss(String s, int max) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        int cut = t.length();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '。' || c == '；' || c == '（' || c == '：' || c == '，') {
                cut = i;
                break;
            }
        }
        String head = t.substring(0, Math.min(cut, t.length())).trim();
        if (head.length() > max) head = head.substring(0, max);
        return head.replace('"', '\'').replace('\\', '/');
    }

    private static String toolOf(String op) {
        if (op == null) return "";
        String s = op.trim();
        int i = s.indexOf('.');
        return i < 0 ? s : s.substring(0, i);
    }

    /**
     * 落盘（原子写；<b>把全部条目写进 core 这一个文件</b>的兜底路径）。
     * <p>不知道归属时才用它 —— 正常的授权/封禁/撤回/重排都走 {@link #saveAll(Auth)}（按归属分文件）。</p>
     */
    public boolean save() { return save(renderJson()); }

    /** 用给定文本落到 core 文件。 */
    public boolean save(String text) {
        if (file == null || text == null) return false;
        boolean ok = atomicWrite(file, text);
        if (ok) reload();
        return ok;
    }

    /**
     * <b>按归属分文件写回</b>（授权 / 封禁 / 撤回之后都走它）：分组带注释、规则层一行一个中括号键。
     *
     * <p>每个 op 写回它<b>应该待的那个文件</b>：技能提供的工具 → 该技能目录里的
     * {@link #SKILL_FILE_NAME}（不存在就创建）；其余 → 数据根下的 {@link #CORE_FILE_NAME}。
     * DB / File 的规则写回它<b>读自的那份文件</b>（谁的声明回谁那儿，不在别人的文件里抹掉它）。
     * {@code File} 域规则实际只会读自 {@link #CORE_FILE_NAME}：技能文件里的 {@code File} 块
     * <b>装载期就被拒</b>（规格 §2.5-10，{@link #addRule} 里整块忽略 + 点名，走不到 {@code new Rule}），
     * 所以"读自技能文件的 File 规则"这种输入不存在。
     * 一个文件里只写它自己的东西；<b>只写有决定的键</b>（没列到 = 未授权，不写空占位）。</p>
     *
     * <p><b>产出必须能被读侧无警告地读回</b>：写到技能文件里的文本只带这个技能自己的
     * {@code Skill} 块（+ 它自己的 {@code DB} 规则），绝不带 {@code File} 块。
     * <b>主人手写进技能文件的 {@code File} 块</b>：装载时点名告警、整块不生效；它从来不是一条规则，
     * 没有任何地方会把它搬走 —— <b>下一次写盘重排这份文件时它被丢弃</b>（与"写侧不产出 File 块"同一条口径）。
     * 另外，某个文件渲染出来的文本与它盘上现有字节<b>完全相同时跳过这一次写</b>
     * （不碰 mtime、不碰字节）：一条规则落盘不再牵动全部权限文件的 mtime。</p>
     *
     * @param auth 权限面（拿它的 op 清单、注释与"工具 → 技能"的归属）；为 null 就只按现有条目重排
     */
    public boolean saveAll(Auth auth) { return saveAll(auth, true); }

    /**
     * 同 {@link #saveAll(Auth)}，但可指定"要不要为没有权限文件的 op 新建文件"。
     *
     * @param create {@code true} = 归属文件不存在就创建（{@code run|ban|revoke} 的口径）；
     *               {@code false} = 只在<b>已有</b>权限文件里重排/补注释，<b>不新建文件</b>（{@code gen} 的口径）
     */
    public boolean saveAll(Auth auth, boolean create) {
        written.clear();
        unwritten.clear();
        Map<String, String> own = auth == null ? null : auth.toolOwners();
        Map<String, String> notes = auth == null || auth.ops() == null ? null : auth.ops().notesMap();
        Map<String, String> toolNotes = auth == null || auth.ops() == null ? null : auth.ops().toolNotes();
        // 计划：文件 → 这个文件该写的条目 / 规则（一个文件只写它自己的东西）
        LinkedHashMap<File, List<Entry>> plan = new LinkedHashMap<File, List<Entry>>();
        LinkedHashMap<File, List<Rule>> rplan = new LinkedHashMap<File, List<Rule>>();
        // 已经存在的权限文件先按"重排"进计划：core 一律重排；技能文件只在<b>确实贡献过条目或规则</b>时重排
        // （说明它管着东西）。这样"撤掉最后一条"才会真的从文件里消失；而"整份文件全是越界条目"
        // 的那种（主人的手写内容，基板只忽略）原样留着，不静默抹掉。
        for (File f : sources) {
            if (f == null || !f.isFile() || isUnified(f)) continue;      // 旧的统一表只读，绝不写
            if (brokenPaths.contains(f.getAbsolutePath())) continue;     // 坏 JSON：不重排（不冲掉原文）
            if (f != file && entriesOf(f) <= 0 && rulesOf(f) <= 0) continue;
            if (!plan.containsKey(f)) plan.put(f, new ArrayList<Entry>());
            if (!rplan.containsKey(f)) rplan.put(f, new ArrayList<Rule>());
        }
        for (Entry e : entries) {
            if (e.principals().isEmpty()) continue;                 // 空占位不写：没列到就是未授权
            File f = targetOf(e.op(), own, create);
            if (f == null || (!create && !f.isFile())) {
                if (!unwritten.contains(e.op())) unwritten.add(e.op());
                continue;
            }
            List<Entry> l = plan.get(f);
            if (l == null) {
                l = new ArrayList<Entry>();
                plan.put(f, l);
            }
            l.add(e);
        }
        for (Rule r : rules) {
            // File 域规则只可能来自 core：技能文件里的 File 块<b>装载期就被拒</b>（规格 §2.5-10，
            // 见 addRule：整块忽略 + 点名，return 在 new Rule 之前），根本不会变成一条 Rule ——
            // 所以这里没有"读自技能文件的 File 规则"这种输入，也就没有"改落"这回事。
            File f = ruleTarget(r, create);
            if (f == null) {
                if (!unwritten.contains(r.key)) unwritten.add(r.key);
                continue;
            }
            List<Rule> l = rplan.get(f);
            if (l == null) {
                l = new ArrayList<Rule>();
                rplan.put(f, l);
            }
            l.add(r);
        }
        if (plan.isEmpty() && rplan.isEmpty()) return true;         // 没有权限文件可写：什么都不做
        boolean ok = true;
        LinkedHashSet<File> files = new LinkedHashSet<File>();
        files.addAll(plan.keySet());
        files.addAll(rplan.keySet());
        for (File f : files) {
            File dir = f.getParentFile();
            if (create && dir != null && !dir.isDirectory()) Fs.mkdirs(dir);
            List<Entry> es = plan.get(f);
            List<Rule> rs = rplan.get(f);
            if (es == null) es = new ArrayList<Entry>();
            if (rs == null) rs = new ArrayList<Rule>();
            // 落点决定形状：技能文件不许出现 File 块，DB 块只在这个技能带着自己的 DB 规则时才写
            // （写侧与读侧同口径，规格 §2.5-10）；core 三个块照旧。
            String text = renderStructured(es, rs, notes, toolNotes, isSkillFile(f));
            if (sameText(f, text)) continue;      // 与盘上完全相同：不碰 mtime、不碰字节
            if (!atomicWrite(f, text)) {
                ok = false;
                continue;
            }
            written.add(f);
        }
        reload();                  // 落盘后重读：内存与文件一致（多源装载的会把技能目录一起重读）
        return ok;
    }

    /**
     * 一条 DB/File 规则写回哪个文件：优先它读来的那份（旧的统一表只读、不当落点），再退回 core；
     * {@code create=false} 时只认已有文件（没有可写的就进 {@link #unwrittenOps()}）。
     */
    private File ruleTarget(Rule r, boolean create) {
        File f = r.file;
        if (f != null && f.isFile() && !isUnified(f)) return f;
        if (file != null && (create || file.isFile())) return file;
        return null;
    }

    private static boolean atomicWrite(File target, String text) {
        File tmp = new File(target.getAbsolutePath() + ".tmp");
        if (!Fs.write(tmp, text)) return false;
        if (move(tmp, target)) return true;
        boolean ok = Fs.write(target, text);
        tmp.delete();
        return ok;
    }

    /**
     * 盘上这份文件的内容与要写的文本是不是<b>字节完全相同</b>（完全相同就不写：不碰 mtime、不碰字节）。
     * <p>写法与 {@link Fs#write} 同编码（UTF-8、不带 BOM），所以"相同"就是"再写一遍也不会变"。
     * 文件不在、读不出来、编码对不上：一律返回 {@code false} = 照旧写 —— 宁可多写一次，不许漏写。</p>
     */
    private static boolean sameText(File f, String text) {
        if (f == null || text == null || !f.isFile()) return false;
        try {
            byte[] cur = Files.readAllBytes(f.toPath());
            byte[] want = text.getBytes(Fs.UTF8);
            if (cur.length != want.length) return false;
            for (int i = 0; i < cur.length; i++) if (cur[i] != want[i]) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean move(File from, File to) {
        try {
            Files.move(from.toPath(), to.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable ignore) {
        }
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Throwable ignore) {
        }
        if (to.exists() && !to.delete()) return false;
        return from.renameTo(to);
    }
}
