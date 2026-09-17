package sair.v4.hot;

import com.google.gson.JsonObject;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import sair.v4.Conf;
import sair.v4.auth.Caller;
import sair.v4.ctx.Turn;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.prompt.Inject;
import sair.v4.skill.Args;
import sair.v4.skill.Host;
import sair.v4.skill.Hooked;
import sair.v4.skill.Skill;
import sair.v4.tool.Registry;
import sair.v4.tool.Tool;

/**
 * 技能库（基板②：显式加载/卸载；<b>没有目录监听</b>）。
 * <p>md 解析 → .java 运行期编译 → 注册为工具 → 事件钩子派发。加载时机只有两个：启动时 scan 与显式重载。
 * 技能是<b>纯外置资产</b>：基板不核对"应该有哪些技能"，技能目录整体删掉也能照常启动，
 * 只是工具少一些、钩子没人接。</p>
 */
public final class Skills {

    /** 钩子名。 */
    public static final String ON_TIMER = "on_timer";
    public static final String ON_MESSAGE = "on_message";
    public static final String ON_REQUEST = "on_request";
    /**
     * 通知类事件的钩子（{@code post_type=notice}；N1）。
     *
     * <p><b>它只能记账，不能说话、不能起一轮</b>：这条路上的通知已经按主人裁定「作为特殊消息记录」
     * 落库留痕，而"<b>不必当即触发响应</b>"是那条裁定的另一半 —— 所以 {@code QqGateway} 派它时
     * 不读 {@code _handled}/{@code _reply}、不进地址门、不给模型，落点是一个什么都不发的
     * {@code term.Sinks.SilentSink}（见那里的注释：为什么不传 {@code null} 的 Turn）。</p>
     *
     * <p>为什么要有它：外挂面（技能/扩展）此前收不到任何通知 —— {@code on_timer}/{@code on_message}/
     * {@code on_request} 三条都盖不到 {@code notice}，于是"有人反复戳它超过 3 次"这类完全建立在
     * 通知之上的策略在外挂层<b>无从实现</b>。</p>
     */
    public static final String ON_NOTICE = "on_notice";


    /** 宿主工厂：把一次调用绑到具体技能与具体会话上。 */
    public interface Hosts {
        Host hostFor(Sk sk, Caller c, Turn t);
    }

    /**
     * 编译进度回调（可为空）：每扫完一个技能目录回调一次。
     * <p>技能扫描是最慢的一步（编译 20+ 个技能），所以要能看见"编到第几个、编的是谁、花了多久"；
     * 回调在<b>扫描线程</b>上被调用，回调里不要做重活。</p>
     */
    public interface CompileListener {
        void done(int i, int total, String name, long ms);
    }

    private final Conf conf;
    private final Out out;
    private final Registry registry;
    private final Hosts hosts;
    private final Inject inject;

    private final Map<String, Sk> skills = new ConcurrentHashMap<String, Sk>();
    private final AtomicLong ticks = new AtomicLong();
    /** 编译进度回调（装配期由 {@code Boot} 装上；正常情况下为空）。 */
    private volatile CompileListener compileListener;
    /** 整轮扫完的收口回调（基板用它同步权限注册表：每个技能工具都要有一行）。 */
    private volatile Runnable scanListener;
    /** 是否正在扫（{@code skill reload} 据此拒绝"排到扫描锁后面"，免得把控制台卡住）。 */
    private volatile boolean scanning = false;
    /**
     * 插件扩展点注册表（基板⑦；装配期由 {@code Boot} 注入）。
     * <p>装载时按接口自动识别、整体注册；卸载/重扫/装载失败时整体注销（见 {@link #registerExt}）。</p>
     */
    private volatile sair.v4.ext.ExtRegistry ext;
    /**
     * 库注册表（基板⑧；装配期由 {@code Boot} 注入 —— 与基板自己注册环境库用的是<b>同一个</b>对象）。
     * <p>装载时插件用 {@link Skill#declare(sair.v4.store.Libs)} 在这里登记自己的表；没注入 = 插件声明不了库
     * （工具与钩子照常，只是用它自己那张表的工具会报"没有这个库"）。</p>
     */
    private volatile sair.v4.store.Libs libs;
    /**
     * "库落盘"回调（装配期由 {@code Boot} 注入）：新声明了表的插件装载完之后，把缺的表补出来。
     * <p>启动那一次由 {@code Boot} 统一建表；这个回调管的是<b>运行期重扫</b>（{@code skill reload} /
     * 现场写出来的新插件）—— 否则新插件的表要等重启才存在。</p>
     */
    private volatile Runnable libSync;

    public Skills(Conf conf, Out out, Registry registry, Inject inject, Hosts hosts) {
        this.conf = conf;
        this.out = out;
        this.registry = registry;
        this.inject = inject;
        this.hosts = hosts;
    }

    /** 注入插件扩展点注册表（装配期调一次；没注入 = 插件挂不了扩展点，工具与钩子照常）。 */
    public void setExt(sair.v4.ext.ExtRegistry e) { this.ext = e; }

    /** 注入库注册表（装配期调一次；没注入 = 插件声明不了自己的库）。 */
    public void setLibs(sair.v4.store.Libs l) { this.libs = l; }

    /** 注入"库落盘"回调（装配期调一次；没注入 = 运行期新声明的表要等重启才建）。 */
    public void setLibSync(Runnable r) { this.libSync = r; }

    // ==================== 扫描与加载 ====================

    /** 全量扫描：新增/变更的技能重新编译并注册，删除的技能摘掉工具与钩子。 */
    public synchronized void scan() {
        File root = conf.skillsDir();
        Fs.mkdirs(root);
        scanning = true;
        try {
            scan0(root);
        } finally {
            scanning = false;
        }
    }

    /** 扫描本体（已经声明"正在扫"）。 */
    private void scan0(File root) {
        if (out != null) out.dim("[skills] 扫描 " + root.getAbsolutePath());
        List<File> dirs = Fs.dirs(root);
        List<String> seen = new ArrayList<String>();
        int i = 0;
        final int total = dirs.size();
        for (File d : dirs) {
            String name = d.getName();
            seen.add(name);
            long t0 = System.currentTimeMillis();
            try {
                loadOne(d, name);
            } catch (Throwable t) {
                Sk sk = skills.get(name);
                if (sk == null) {
                    sk = new Sk();
                    sk.name = name;
                    sk.dir = d;
                    skills.put(name, sk);
                }
                sk.state = Sk.State.ERROR;
                sk.error = String.valueOf(t);
                if (out != null) out.err("[skills] " + name + " 加载异常: " + t);
            }
            i++;
            CompileListener cl = compileListener;
            if (cl != null) {
                try {
                    cl.done(i, total, name, System.currentTimeMillis() - t0);
                } catch (Throwable ignored) {
                    // 进度回调炸了不影响扫描
                }
            }
        }
        // 删除已不存在的技能
        for (String name : new ArrayList<String>(skills.keySet())) {
            if (seen.contains(name)) continue;
            Sk sk = skills.remove(name);
            if (sk != null) {
                registry.removeByOwner(name);
                unregisterExt(name);                 // 插件没了，它挂的扩展点一起摘（工具+钩子+扩展点全摘）
                clearSkillInject(name, sk);
                if (out != null) out.dim("[skills] 已移除 " + name);
            }
        }
        if (out != null) {
            out.dim("[skills] 当前技能 " + skills.size() + " 个（提供工具 " + toolCount() + " 个，"
                    + "钩子 " + hookCount() + " 个）");
        }
        // 收口回调：权限注册表要跟着技能增减同步（新工具补一行、消失的工具留着行不动）
        Runnable sl = scanListener;
        if (sl != null) {
            try {
                sl.run();
            } catch (Throwable t) {
                if (out != null) out.warn("[skills] 扫描收口回调异常（不影响技能本身）：" + t);
            }
        }
    }

    /** 装上/卸下编译进度回调（装配期用；传 null 卸下）。 */
    public void setCompileListener(CompileListener l) { this.compileListener = l; }

    /**
     * 挂一个"扫完就回调"的钩子（基板用它同步权限注册表：新技能/新工具要有一行）。
     * <p>与 {@link #setCompileListener} 分开：那个是逐技能的进度回话，这个是整轮扫完的收口。</p>
     */
    public void setScanListener(Runnable r) { this.scanListener = r; }

    /** 是否正在全量扫描（编译中）。 */
    public boolean scanning() { return scanning; }

    private void loadOne(File dir, String name) throws Exception {
        File md = new File(dir, name + ".md");
        Sk existing = skills.get(name);
        String hash = hashOf(dir, md);
        if (existing != null && hash.equals(existing.hash) && existing.state != Sk.State.ERROR) {
            return;     // 未变更
        }
        // 先整体摘掉这个插件上一次挂的扩展点：下面任何一条"装载失败就早退"的路径都不会留下半套扩展点。
        // 未变更的那条路（上面）不摘 —— 没变就不该有"摘了又挂"的空窗。
        unregisterExt(name);
        Sk sk = new Sk();
        sk.name = name;
        sk.dir = dir;
        sk.hash = hash;
        if (!md.isFile()) {
            sk.state = Sk.State.ERROR;
            sk.error = "缺少同名文档 " + name + ".md";
            skills.put(name, sk);
            registry.removeByOwner(name);
            if (out != null) out.warn("[skills] " + name + " 缺少同名 md，已跳过");
            return;
        }
        sk.md = md;
        String text = Fs.read(md, "");
        SkMd.Parsed p = SkMd.parse(text);
        sk.doc = p.body;
        JsonObject fm = p.front == null ? new JsonObject() : p.front;
        String declaredName = J.s(fm, "name", "").trim();
        if (Str.has(declaredName) && !declaredName.equals(name)) {
            sk.state = Sk.State.ERROR;
            sk.error = "front matter name(" + declaredName + ") 与文件夹名(" + name + ") 不一致";
            skills.put(name, sk);
            registry.removeByOwner(name);
            if (out != null) out.warn("[skills] " + sk.error);
            return;
        }
        sk.description = J.s(fm, "description", "");
        sk.tool = J.s(fm, "tool", "");
        // P9b-1：这里原来还有一段"skill md 里写了 permission: 就提示旧档位已不生效"的告警 —— 已删除。
        // 旧档位体系整个退休（权限只由 ACL 账本按资源判定），再提"档位"只会误导人；{@code sk.level}
        // 字段**保留**（可观测面：Sk.toJson().permission + skill_index.permission），这里固定清空。
        sk.level = "";
        sk.inject = J.s(fm, "inject", "");
        sk.hooks = hooksOf(fm);
        JsonObject airun = J.sub(fm, "airun");
        sk.airun = airun == null ? new JsonObject() : airun;
        sk.shared = "shared".equalsIgnoreCase(J.s(airun, "state", "per_call"));
        sk.examples = examplesOf(airun);

        // 工具名必须合 API 规范（^[a-zA-Z0-9_-]{1,64}$）：非法名让服务端拒的是**整条请求** 400。
        // 策略不是判错，而是**自动英文化注册**（ToolNames.derived：tp_ + 可读 ASCII 段 + 8 位稳定哈希），
        // **文件夹名与 md 名保持中文不变**；声明名 → 注册名的映射记在 sk.toolDeclared → sk.tool，
        // 启动日志与 skill/skill_write 的返回里都可见（让模型知道"中文技能名 → 注册的工具名"）。
        if (Str.has(sk.tool)) {
            sk.toolDeclared = sk.tool.trim();
            // ① 先按**声明名**查保留名：基板 15 个工具名不许技能顶替（命名空间保护，不是优先级）。
            //    这一步必须发生在"改名前" —— 否则声明的 `exec` 会被撞车后缀改成 `exec-2` 混过去。
            Tool reserved = registry.get(sk.toolDeclared);
            if (reserved != null && !reserved.skill()) {
                sk.state = Sk.State.ERROR;
                sk.error = "工具名 " + sk.toolDeclared + " 与基板工具重名（基板工具名是保留名，请改名）";
                skills.put(name, sk);
                registry.removeByOwner(name);
                if (out != null) out.err("[skills] " + name + " " + sk.error);
                return;
            }
            // ② 再算注册名：只跟**别的**工具避让（自己上一次注册的名字不算撞车 —— 重扫时它还没被摘掉）
            List<String> taken = new ArrayList<String>();
            for (String n : registry.names()) {
                Tool t = registry.get(n);
                if (t != null && name.equals(t.owner())) continue;
                taken.add(n);
            }
            for (Sk other : skills.values()) {
                // 注意：这里比的是**技能名**而不是对象。loadOne 每次都 new 一个 Sk，
                // 而 skills 表里还是上一次的实例 —— 用 `other == sk` 判自己会漏掉，
                // 于是"自己上一次的注册名"被当成撞车，重扫之后工具名变成 experience-2。
                if (other == null || name.equals(other.name) || Str.blank(other.tool)) continue;
                taken.add(other.tool);
            }
            String reg = ToolNames.register(sk.toolDeclared, taken);
            if (!reg.equals(sk.toolDeclared)) {
                sk.toolRegistered = reg;
                if (out != null) {
                    out.dim("[skills] " + name + " 的工具名「" + sk.toolDeclared + "」不合 API 规范 → 注册为 "
                            + reg + "（文件夹名与 md 名仍保持中文）");
                }
            }
            sk.tool = reg;
        }

        // 源码
        List<File> sources = Fs.walk(dir, ".java", 2);
        sk.sources = sources;
        File prompt = new File(dir, "prompt.md");
        if (prompt.isFile()) sk.promptFile = prompt;

        // 编译
        if (!sources.isEmpty()) {
            SkCompile.Result r = SkCompile.compile(sources);
            if (!r.ok()) {
                sk.state = Sk.State.ERROR;
                sk.error = "编译失败: " + Str.join(r.errors, " | ");
                skills.put(name, sk);
                registry.removeByOwner(name);
                if (out != null) out.err("[skills] " + name + " " + sk.error);
                return;
            }
            sk.classes = r.classes;
            String entry = J.s(fm, "entry", "").trim();
            sk.entryClass = pickEntry(sk, entry);
            if (sk.entryClass == null) {
                sk.state = Sk.State.ERROR;
                sk.error = "找不到入口类（需要一个实现 Skill 的类，或一个含 airun 方法的类）";
                skills.put(name, sk);
                registry.removeByOwner(name);
                if (out != null) out.err("[skills] " + name + " " + sk.error);
                return;
            }
            // 类加载器与入口类在加载期建好，运行期复用
            sk.loader = new SkClassLoader(sk.classes, Skills.class.getClassLoader());
            sk.cls = Class.forName(sk.entryClass, true, sk.loader);
            // 库声明（表结构由插件声明、基板只给注册表）：实例化入口类之后、注册工具之前。
            // 放在这里的原因：表必须早于用它的工具存在。失败只让该插件不可用（warn 一行，不拖垮装配）。
            if (!declareLibs(name, sk)) return;
            sk.state = Sk.State.LOADED;
        } else {
            sk.state = Sk.State.MD_ONLY;
        }

        skills.put(name, sk);

        // 注册工具（权限不在这里声明：没有"工具级档位"这回事 —— 判据是资源 ACL，
        // 要碰资源那一刻判 Res.* 的位；旧档位表的键规则「中文文件夹名.工具名」已随 PermTable 删除）
        registry.removeByOwner(name);
        // 插件扩展点（基板⑦）：注册库声明 → 注册扩展点 → 注册工具 → 挂事件钩子 → 装注入片段（装载顺序的最后一步）
        registerExt(name, sk);
        if (sk.providesTool()) {
            registry.add(Tool.of(sk.tool)
                    .desc(sk.toolDesc())
                    .returns(sk.toolReturns())
                    .owner(name)
                    .skill(true)
                    .params(sk.schema())
                    .examples(sk.examples)
                    .handler(new Tool.Handler() {
                        @Override
                        public Object call(JsonObject args, Turn t) {
                            return invoke(sk, args, t);
                        }
                    }));
        }
        // 提示词片段（插件层内容，声明了 inject 就注入到该槽位的全局作用域）
        if (inject != null) {
            clearSkillInject(name, sk);
            if (sk.promptFile != null && Str.has(sk.inject)) {
                String ptxt = Fs.read(sk.promptFile, "");
                if (Str.has(ptxt)) {
                    String slot = sk.inject.trim();
                    inject.put(Inject.GLOBAL, slot, "skill:" + name, ptxt, "skill:" + name);
                    sk.injectedSlot = slot;
                }
            }
        }
        if (out != null) {
            out.dim("[skills] " + name + " → " + sk.state + (sk.providesTool() ? " 工具=" + sk.tool : "")
                    + (sk.hooks.isEmpty() ? "" : " 钩子=" + Str.join(sk.hooks, ",")));
        }
    }

    /**
     * 装载期库声明（基板⑧；调用点见 {@link #loadOne}）。
     *
     * <p><b>为什么用"实现 {@link Skill} 接口 + {@code declare(Libs)}"而不是 md 里再加一个 {@code lib:} 段</b>：
     * 表结构是一棵"表名 + 每列类型/默认值 + FTS 列 + LIKE 列 + 索引名 + 中文别名"的结构，
     * {@code SkMd} 那种极简 YAML 解析器表达不了；而这一段写错是<b>静默不生效</b>
     * （表建不出来、工具却已经注册好了）。用代码写，编译期就能查。md 只留给人看的说明书。</p>
     *
     * <p>对<b>该插件编译出来的每个顶层类</b>都问一次（谁实现了 {@link Skill#declare} 谁来声明）：
     * 纯行为插件（只挂 {@code ContextProvider} / {@code OutboundStage} / {@code Hooked}，不提供工具）
     * 常常<b>没有</b>"工具入口类"，而它的表仍然要有人声明 —— 只看入口类的话，
     * 这类插件就只能硬塞一个 {@code implements Skill} 的壳类才能建表，那是给人添堵。
     * 同一个插件声明两次同一张表是**幂等**的（同 owner 重声明 = 更新），重扫不会出问题。</p>
     *
     * <p>没有实现 {@link Skill} 的类直接跳过（{@code airun} 反射回退那条路 = 没有声明面）。</p>
     *
     * <p>重复注册（表名/别名被<b>别的</b> owner 占）等于声明失败 —— 由 {@link Guarded} 记下来，这里把该插件标失败。</p>
     *
     * @return true = 继续装载；false = 该插件已标失败（调用方直接 return，不注册它的工具）
     */
    private boolean declareLibs(String name, Sk sk) {
        final sair.v4.store.Libs reg = libs;
        if (reg == null || sk == null || sk.classes == null || sk.loader == null) return true;
        final Guarded g = new Guarded(reg);
        List<String> names = new ArrayList<String>(sk.classes.keySet());
        Collections.sort(names);                                     // 顺序确定 = 每次装出来的结果逐字节一样
        boolean any = false;
        for (String cn : names) {
            if (cn.contains("$")) continue;
            final boolean isEntry = sk.cls != null && cn.equals(sk.cls.getName());
            try {
                Class<?> k = Class.forName(cn, false, sk.loader);
                if (!Skill.class.isAssignableFrom(k)) continue;
                ((Skill) k.newInstance()).declare(g);
                any = true;
            } catch (Throwable t) {
                if (!isEntry) {
                    // 非入口类只是"顺带也实现了 Skill"：它实例化不了/声明炸了，不该拖垮整个插件
                    // （入口类才是这个插件的身份，它的声明失败才等于插件不可用）。
                    if (out != null) out.warn("[skills] " + name + " 的 " + cn
                            + " 不用于声明库（已跳过）：" + (t.getCause() == null ? t : t.getCause()));
                    continue;
                }
                Throwable cause = t.getCause() == null ? t : t.getCause();
                sk.state = Sk.State.ERROR;
                sk.error = "库声明失败: " + cause;
                skills.put(name, sk);
                registry.removeByOwner(name);
                if (out != null) out.warn("[skills] " + name + " " + sk.error + "（该插件本次不可用，其余照常）");
                return false;
            }
        }
        if (!any) return true;                                       // 这个插件没有库声明面
        if (g.conflict) {
            sk.state = Sk.State.ERROR;
            sk.error = "库声明失败: 表名或别名已被别的库占用（见上一行 [store] 库名冲突）";
            skills.put(name, sk);
            registry.removeByOwner(name);
            if (out != null) out.warn("[skills] " + name + " " + sk.error + "（该插件本次不可用，其余照常）");
            return false;
        }
        Runnable sync = libSync;
        if (sync != null && g.needSync) {
            try {
                sync.run();     // 新声明的表/新加的列立刻落盘（运行期重扫时也成立）
            } catch (Throwable t) {
                if (out != null) out.warn("[store] 库声明后建表失败（不影响插件装载）：" + t);
            }
        }
        return true;
    }

    /**
     * 递给插件的注册表视图：<b>只加一件本事</b> —— 把"注册被拒"记下来，
     * 让装载流程能把该插件标失败（{@link sair.v4.store.Libs#register} 的返回值是给插件看的，
     * 插件完全可以不看；这里替它看）。
     *
     * <p>顺带记一下"这次声明是不是新东西"（新表 / 列数变了）：只有新东西才值得跑一次库落盘，
     * 否则 26 个插件重扫会把建表语句跑 26 遍（全是幂等空转，只是白刷日志）。</p>
     */
    private final class Guarded implements sair.v4.store.Libs {

        private final sair.v4.store.Libs real;
        private boolean conflict;
        /** 这次声明里有"表还不存在"或"列数变了" → 需要跑一次建表/补列。 */
        private boolean needSync;

        Guarded(sair.v4.store.Libs real) { this.real = real; }

        @Override
        public boolean register(sair.v4.store.LibSpec spec) {
            sair.v4.store.LibSpec before = real.get(spec == null ? null : spec.table);
            boolean ok = real.register(spec);
            if (!ok) {
                conflict = true;
                if (real instanceof sair.v4.store.LibRegistry) {
                    ((sair.v4.store.LibRegistry) real).warnConflict(spec);
                } else if (out != null) {
                    out.warn("[store] 库名冲突：'" + (spec == null ? "?" : spec.table) + "' 已被占用，这次声明被拒");
                }
            } else if (before == null || before.cols.size() != spec.cols.size()) {
                needSync = true;
            }
            return ok;
        }

        @Override
        public sair.v4.store.LibSpec get(String tableOrAlias) { return real.get(tableOrAlias); }

        @Override
        public List<sair.v4.store.LibSpec> all() { return real.all(); }

        @Override
        public List<String> tables() { return real.tables(); }

        @Override
        public List<sair.v4.store.LibSpec> env() { return real.env(); }

        @Override
        public int size() { return real.size(); }
    }

    /**
     * 按接口<b>自动识别</b>并注册一个插件的扩展点（基板⑦；调用点见 {@link #loadOne}）。
     *
     * <p><b>为什么用"自动识别"而不是 md 里再加一个 {@code ext:} 段</b>：插件作者已经用
     * {@code implements ContextProvider} 声明过一次意图了，让他再在 front matter 里抄一遍接口名，
     * 只会多出一个"抄错了就静默不生效"的坑，而且那段还得跟类名一起维护。{@code hooks:} 需要显式声明，
     * 是因为方法名（{@code on_message} 等）本来就不带类型信息；接口实现带了，不用再编一遍。</p>
     *
     * <p>扫的是这个插件编译出来的<b>每个顶层类</b>，不是只看入口类：工具与扩展点常常是两件事，
     * 把 provider 写在另一个类里是最自然的写法；只看入口类会逼作者把两个身份塞进同一个类。
     * 跳过 {@code $} 内部类（匿名/内部类不该被当成插件身份）。实例化失败只 warn 一行 —— 插件本身照常可用。</p>
     *
     * <p>顺序：先把这个插件上一次挂的整体摘掉（重扫幂等），再按<b>类名升序</b>挂这一次的
     * （顺序确定 = 同一份插件每次装出来的 order 序列逐字节一样）。</p>
     */
    private void registerExt(String name, Sk sk) {
        sair.v4.ext.ExtRegistry e = ext;
        if (e == null) return;
        unregisterExt(name);
        if (sk == null || !sk.hasCode() || sk.classes == null || sk.loader == null) return;
        List<String> names = new ArrayList<String>(sk.classes.keySet());
        Collections.sort(names);
        int n = 0;
        for (String cn : names) {
            if (cn.contains("$")) continue;
            try {
                Class<?> k = Class.forName(cn, false, sk.loader);
                if (!sair.v4.ext.ExtRegistry.isExtClass(k)) continue;
                n += e.register(name, k.newInstance());
            } catch (Throwable t) {
                if (out != null) out.warn("[ext] " + name + " 的 " + cn + " 不能用作扩展点（已跳过）：" + t);
            }
        }
        if (n > 0 && out != null) out.dim("[ext] " + name + " 挂了 " + n + " 条扩展点");
    }

    /** 摘掉一个插件挂的全部扩展点（卸载 / 重扫 / 装载失败都走它）。 */
    private void unregisterExt(String name) {
        sair.v4.ext.ExtRegistry e = ext;
        if (e == null) return;
        try {
            e.unregister(name);
        } catch (Throwable t) {
            if (out != null) out.warn("[ext] " + name + " 的扩展点注销失败（不影响技能本身）：" + t);
        }
    }

    /**
     * 用法示例：{@code airun.examples} 支持 YAML 数组（{@code examples: [a, b]}）与逗号分隔字符串。
     * <p>写法是"用户这么说 → 走哪个 op"，一行一条；示例正文一律写在 md 里，基板只读取与拼接。</p>
     */
    public static List<String> examplesOf(JsonObject airun) {
        List<String> out = new ArrayList<String>();
        if (airun == null) return out;
        com.google.gson.JsonElement e = airun.get("examples");
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                if (x == null || !x.isJsonPrimitive()) continue;
                String s = x.getAsString().trim();
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
        for (String s : e.getAsString().split("[,\\n]")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** 钩子声明：既支持 YAML 数组（hooks: [on_timer, on_message]），也支持逗号/空格分隔的字符串。 */    public static List<String> hooksOf(JsonObject fm) {
        List<String> out = new ArrayList<String>();
        if (fm == null) return out;
        com.google.gson.JsonElement e = fm.get("hooks");
        if (e == null || e.isJsonNull()) return out;
        if (e.isJsonArray()) {
            for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                if (x != null && x.isJsonPrimitive()) {
                    String s = x.getAsString().trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
            return out;
        }
        for (String s : e.getAsString().split("[,\\s]+")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 清掉某技能留下的提示词注入：既清它这次声明的槽位，也清历史可能写过的四个槽
     * （技能改过 inject 值或直接删掉时，旧槽不会永久残留）。
     */
    private void clearSkillInject(String name, Sk sk) {
        if (inject == null) return;
        String key = "skill:" + name;
        String[] slots = {Inject.SYSTEM, Inject.CONTEXT, Inject.TOOL, Inject.ARG};
        for (String s : slots) inject.remove(Inject.GLOBAL, s, key);
        if (sk != null) sk.injectedSlot = null;
    }

    private String pickEntry(Sk sk, String declared) {
        ClassLoader cl = new SkClassLoader(sk.classes, Skills.class.getClassLoader());
        List<String> names = new ArrayList<String>(sk.classes.keySet());
        Collections.sort(names);
        if (Str.has(declared)) {
            for (String n : names) if (n.equals(declared) || n.endsWith("." + declared)) return n;
        }
        // 1) 实现 Skill 接口的类
        for (String n : names) {
            if (n.contains("$")) continue;
            try {
                Class<?> c = Class.forName(n, false, cl);
                if (Skill.class.isAssignableFrom(c)) return n;
            } catch (Throwable ignored) {
            }
        }
        // 2) 含 airun 方法的类
        for (String n : names) {
            if (n.contains("$")) continue;
            try {
                Class<?> c = Class.forName(n, false, cl);
                for (Method m : c.getMethods()) if ("airun".equals(m.getName())) return n;
            } catch (Throwable ignored) {
            }
        }
        // 3) 第一个顶层类
        for (String n : names) if (!n.contains("$")) return n;
        return null;
    }

    // ==================== 调用 ====================

    /** 按技能名调用（供工具回调与其它技能使用）。 */
    public Object invokeByName(String name, JsonObject args, Turn t) {
        Sk sk = skills.get(name);
        // B10：这 6 条原来一律用复数前缀 [skills] —— 而连续失败闸门（Loop.failed）按
        // 「首行是不是 [<工具名>]」判自报失败，[skills] 既不是工具名、也不是框架自己的
        // [tool]/[auth] 前缀 ⇒ 这些**真失败**一次都不计数。这里给它们补上结构标记：
        //   · 手里有 sk ⇒ 用**真工具名**（注册表里的名字），并显式写 ok=0；
        //   · 拿不到工具名（技能都不存在）⇒ 用框架那条与名字无关的结构前缀 [tool] + ok=0
        //     （Loop.failed 对 [tool] 开头的正文一律算失败）。判据仍是结构标记，不是关键词表。
        if (sk == null) return "[tool] ok=0 没有技能 " + name;
        return invoke(sk, args, t);
    }

    /** 一个技能对外暴露的工具名（没声明工具名就用技能名：与 {@code Args} 的取法同一处口径）。 */
    private static String toolNameOf(Sk sk) {
        if (sk == null) return "";
        return Str.blank(sk.tool) ? Str.nz(sk.name) : sk.tool;
    }

    public Object invoke(Sk sk, JsonObject args, Turn t) {
        if (sk == null) return "[tool] ok=0 技能不存在";
        String tool = toolNameOf(sk);
        if (sk.state == Sk.State.ERROR) return "[" + tool + "] ok=0 " + sk.name + " 不可用: " + sk.error;
        if (!sk.hasCode()) return "[" + tool + "] ok=0 " + sk.name + " 没有可执行代码（纯文档技能）";
        Caller c = t == null ? null : t.caller();
        Host h = hosts == null ? null : hosts.hostFor(sk, c, t);
        Args a = new Args(Str.blank(sk.tool) ? sk.name : sk.tool, args);
        // 绑定当前调用者：技能发起的 NapCat 动作要按调用者复核权限
        sair.v4.ctx.Ctx.Scope scope = sair.v4.ctx.Ctx.of(c);
        try {
            Class<?> cls = sk.cls != null ? sk.cls : Class.forName(sk.entryClass, true, new SkClassLoader(sk.classes, Skills.class.getClassLoader()));
            if (sk.shared) {
                // shared 实例只创建一次（并发调用时不能各建一个）
                Object inst;
                synchronized (sk) {
                    if (sk.instance == null) sk.instance = cls.newInstance();
                    inst = sk.instance;
                }
                // 关键：shared 实例是**整个进程内唯一**的可变对象。只保证"只建一次"是不够的 ——
                // 两条会话道（以及定时钩子 / 异步子 Agent）会同时进来改同一份字段，
                // 于是"读-改-写"彼此覆盖。这里的口径是 **per-skill 串行化**：
                // 同一个 shared 技能同一时刻只跑一个调用；不同技能、非 shared 技能完全不受影响。
                // （同一线程重入没问题：synchronized 可重入，技能自调自己不会自锁。）
                synchronized (inst) {
                    return callOne(inst, a, h);
                }
            }
            return callOne(cls.newInstance(), a, h);
        } catch (Throwable e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            StringBuilder sb = new StringBuilder("[" + tool + "] ok=0 " + sk.name + " 执行异常: " + cause);
            StackTraceElement[] st = cause.getStackTrace();
            for (int i = 0; i < Math.min(5, st.length); i++) sb.append("\n  at ").append(st[i]);
            return sb.toString();
        } finally {
            scope.close();
        }
    }

    /** 真正把一次调用交给实例（{@link Skill} 接口或 {@code airun} 反射回退）。 */
    private Object callOne(Object inst, Args a, Host h) throws Exception {
        if (inst instanceof Skill) return ((Skill) inst).call(a, h);
        return reflectAirun(inst, a, h);
    }

    /**
     * 反射回退：按"形参能否接受实参"匹配，依次尝试
     * {@code airun(Map,Map) → airun(Map,Args) → airun(Args,Host) → airun(String,Map) → airun(String,String)
     * → airun(Map) → airun(String) → airun()}
     * （早先用 {@code getMethod(实际类型)} 精确匹配，LinkedHashMap 永远匹配不上 Map 形参 → 契约形同虚设）。
     */
    private Object reflectAirun(Object inst, Args a, Host h) throws Exception {
        Map<String, Object> args = a.map();
        Map<String, Object> ctx = contextMap(a, h);
        Object[][] candidates = {
                {args, ctx},
                {args, J.json(ctx)},
                {a, h},
                {J.json(args), ctx},
                {J.json(args), J.json(ctx)},
                {args},
                {J.json(args)},
                {a},
                {}
        };
        Class<?> c = inst.getClass();
        for (Object[] params : candidates) {
            Method m = findAirun(c, params);
            if (m == null) continue;
            try {
                m.setAccessible(true);
                return m.invoke(inst, params);
            } catch (Throwable e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                // B10：原来是复数前缀 [skills]（≠ 工具名）⇒ 这条真失败不被连续失败闸门计数。
                // 工具名从 Args 拿（它就是调用点用的那个注册名），拿不到就退框架的 [tool] 前缀。
                String tn = (a == null || Str.blank(a.tool())) ? "tool" : a.tool();
                return "[" + tn + "] ok=0 airun 调用失败: " + cause;
            }
        }
        StringBuilder sb = new StringBuilder("入口类没有可用的 airun 方法（可接受的实参：Map/JSON/Args + 上下文）。可用签名：");
        boolean any = false;
        for (Method m : c.getMethods()) {
            if (!"airun".equals(m.getName())) continue;
            any = true;
            sb.append("\n  airun(");
            Class<?>[] pt = m.getParameterTypes();
            for (int i = 0; i < pt.length; i++) sb.append(i > 0 ? ", " : "").append(pt[i].getSimpleName());
            sb.append(")");
        }
        if (!any) sb.append("（该类根本没有 airun 方法）");
        throw new NoSuchMethodException(sb.toString());
    }

    /** 找一个形参能接受这组实参的 airun。 */
    private static Method findAirun(Class<?> c, Object[] params) {
        for (Method m : c.getMethods()) {
            if (!"airun".equals(m.getName())) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != params.length) continue;
            boolean ok = true;
            for (int i = 0; i < pt.length; i++) {
                if (params[i] == null) {
                    if (pt[i].isPrimitive()) { ok = false; break; }
                    continue;
                }
                if (!pt[i].isAssignableFrom(params[i].getClass())) { ok = false; break; }
            }
            if (ok) return m;
        }
        return null;
    }

    private Map<String, Object> contextMap(Args a, Host h) {
        Map<String, Object> ctx = new LinkedHashMap<String, Object>();
        if (h == null) return ctx;
        Caller c = h.caller();
        ctx.put("session", h.session() == null ? "" : h.session());
        ctx.put("scope", h.scopeKey() == null ? "" : h.scopeKey());
        if (c != null) {
            ctx.put("entry", c.isConsole() ? "console" : "qq");
            ctx.put("qq", c.qq());
            ctx.put("group_id", c.groupId());
            ctx.put("is_master", c.master());
            ctx.put("master", c.master());
            ctx.put("favor", c.favor());
            ctx.put("name", c.name());
            ctx.put("role", c.groupRole());
        }
        ctx.put("tool", a.tool());
        ctx.put("napcat", h.napcatConnected());
        ctx.put("data_dir", h.conf() == null ? "" : h.conf().root().getAbsolutePath());
        return ctx;
    }

    /** 没有具体会话的钩子（如 on_timer）用的落点 + 回合（由基板装配注入）。 */
    private volatile sair.v4.ctx.Sink systemSink;

    public void setSystemSink(sair.v4.ctx.Sink s) { this.systemSink = s; }

    /**
     * 钩子派发（基板把事件交给技能；没人接就什么都不发生）。
     *
     * <p><b>主体 = SYSTEM（她自己的自主行为），不是主人</b>：A={@code R}、B={@code RW}、C={@code RWX}、
     * E={@code X} —— 记库、发她自己的推送都行，但 A 类没有 {@code W/X}，所以 {@code exec}
     * （判 {@code Res.path(execShell())} 的 {@code X}）与写本机文件都被拒。
     *
     * <p>旧口径是 {@code Caller.system(...)}（那是 {@code master=true} 的老工厂，语义"以主人身份运行"）
     * —— 等于"定时任务 = 主人权限"，与 {@link #hookActor()} 那条 P1 已改的口径不一致（这里曾漏改一处）。
     * 现在两处同源：主体一律 {@link Caller#systemActor(long)}。</p>
     *
     * <p>到点真正派出去的子 Agent <b>不继承这里的钩子主体</b>：技能用 {@code Host.spawnAs(...)}
     * 显式指定"任务创建者"当主体（能力继承：延迟执行不得比创建者权限更高）。</p>
     */
    public void dispatch(String hook, JsonObject payload) {
        // 定时类钩子的主体 = 她自己的自主行为（拿不到触发者，也没有触发者）：这样技能能用
        // h.call(...) 调受权限管的工具，同时不把 A 类的 W/X 白送出去
        Caller c = Caller.systemActor(conf == null ? 0L : conf.masterQQ());
        dispatch(hook, payload, c, new Turn(c, systemSink));
    }

    /**
     * 钩子派发：把事件上下文（调用者 + 会话）一并交给技能，
     * 这样钩子技能也能 {@code h.say(...)}（往原会话说话）、{@code h.inject(...)}（注入上下文）、
     * {@code h.spawn(...)}（派子 Agent）。
     *
     * <p><b>线程口径</b>：钩子必须<b>同步</b>跑完（{@code payload._handled} 决定基板还要不要自己回），
     * 所以这里不是异步化，而是把"在谁身上来就在谁身上跑"换成<b>有界执行池</b>
     * （{@link #setHookPool}，配置 {@code hookWorkers}/{@code hookQueueMax}）：
     * 慢钩子不再让线程数跟着消息数涨；池满时 caller-runs（不丢、不自锁）。
     * 池未装配（{@code workers<=0}）= 旧行为：调用线程直接跑。</p>
     */
    public void dispatch(String hook, JsonObject payload, Caller caller, Turn turn) {
        if (Str.blank(hook)) return;
        List<Sk> list = withHook(hook);
        for (Sk sk : list) {
            if (sk.state == Sk.State.ERROR || !sk.hasCode()) continue;
            final String hk = hook;
            final Sk target = sk;
            final JsonObject pl = payload;
            final Caller c = caller;
            final Turn t = turn;
            sair.v4.schedule.Pool p = hookPool;
            if (p == null) {
                invokeHook(hk, target, pl, c, t);
                continue;
            }
            p.submitAndWait(new Runnable() {
                @Override
                public void run() {
                    invokeHook(hk, target, pl, c, t);
                }
            });
        }
    }

    /** 钩子技能执行池；null / {@code enabled()==false} = 调用线程直接跑（旧行为）。 */
    private volatile sair.v4.schedule.Pool hookPool;

    public void setHookPool(sair.v4.schedule.Pool p) { this.hookPool = p; }

    /**
     * 钩子路径的<b>权限判定主体</b>：她自己的自主行为（SYSTEM），不是那个发消息的人。
     *
     * <p>口径见 {@code notes/acl-wiring-packages.md} §P1-a：钩子里的动作（自动禁言、自动审批、自动清理）
     * 是"她自己的自动策略"，按触发者判会让"陌生人的默认 {@code R}"把它当场拒掉；绑 {@code null}
     * 又会让闸门走"无主体 = 基板内部动作 = 放行"的老洞。所以这里绑 {@link Caller#systemActor(long)}。</p>
     *
     * <p>注意：钩子里 {@code Host.caller()} <b>仍然是真实发送者</b>（给她做上下文用）——
     * 那是"谁在说话"，与"判定用谁的位"是两件事。</p>
     */
    private Caller hookActor() {
        return Caller.systemActor(conf == null ? 0L : conf.masterQQ());
    }

    /** 真正调用一个技能的一个钩子（异常一律吞掉记日志，绝不影响基板）。 */
    private void invokeHook(String hook, Sk sk, JsonObject payload, Caller caller, Turn turn) {
        try {
            Host h = hosts == null ? null : hosts.hostFor(sk, caller, turn);
            Class<?> cls = sk.cls != null ? sk.cls : Class.forName(sk.entryClass, true, new SkClassLoader(sk.classes, Skills.class.getClassLoader()));
            Object inst = cls.newInstance();
            boolean handled = false;
            if (inst instanceof Hooked) {
                // 钩子里的动作以"机器人自己"的身份执行（审核要禁言的是发言人，不是"发言人有权禁言自己"）；
                // 触发者身份仍然通过 h.caller() 提供给技能做策略判断。
                // 权限判定主体（Ctx）= SYSTEM：钩子是"她自己的自动策略"。绑 null 会让闸门把每个动作都当
                // "基板内部动作"放行（旧 Boot:424 的洞），而按触发者判又会让"陌生人的默认 R"当场拒掉
                // 自动禁言 —— 见 notes/acl-wiring-packages.md §P1-a。
                sair.v4.ctx.Ctx.Scope sc = sair.v4.ctx.Ctx.of(hookActor());
                try {
                    ((Hooked) inst).on(hook, payload, h);
                } finally {
                    sc.close();
                }
                handled = true;
            } else {
                for (Method m : cls.getMethods()) {
                    if (!m.getName().equals(hook)) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    Method call = null;
                    Object[] argv = null;
                    if (pt.length == 2 && pt[0].isAssignableFrom(JsonObject.class)
                            && pt[1].isAssignableFrom(Host.class)) {
                        call = m;
                        argv = new Object[] {payload, h};
                    } else if (pt.length == 1 && pt[0].isAssignableFrom(JsonObject.class)) {
                        call = m;
                        argv = new Object[] {payload};
                    } else if (pt.length == 0) {
                        call = m;
                        argv = new Object[0];
                    }
                    if (call != null) {
                        // 老式（非 Hooked）钩子分支：判定主体同样是 SYSTEM（与上面那条一个字不差）
                        sair.v4.ctx.Ctx.Scope sc = sair.v4.ctx.Ctx.of(hookActor());
                        try {
                            call.invoke(inst, argv);
                        } finally {
                            sc.close();
                        }
                        handled = true;
                    }
                    break;
                }
            }
            if (!handled && out != null) {
                out.err("[skills] " + sk.name + " 声明了钩子 " + hook + "，但入口类里没有匹配的方法"
                        + "（需要 Hooked.on(String,JsonObject,Host)，或 " + hook + "(JsonObject[,Host])）");
            }
        } catch (Throwable e) {
            if (out != null) out.err("[skills] 钩子 " + hook + " 在 " + sk.name + " 上失败: "
                    + (e.getCause() == null ? e : e.getCause()));
        }
    }

    // ==================== 查询/诊断 ====================

    public List<Sk> list() {
        List<Sk> l = new ArrayList<Sk>(skills.values());
        Collections.sort(l, new java.util.Comparator<Sk>() {
            @Override
            public int compare(Sk a, Sk b) { return a.name.compareToIgnoreCase(b.name); }
        });
        return l;
    }

    public Sk get(String name) { return name == null ? null : skills.get(name); }

    public int size() { return skills.size(); }

    public int toolCount() {
        int n = 0;
        for (Sk s : skills.values()) if (s.providesTool()) n++;
        return n;
    }

    public int hookCount() {
        int n = 0;
        for (Sk s : skills.values()) if (!s.hooks.isEmpty()) n++;
        return n;
    }

    public List<Sk> withHook(String hook) {
        List<Sk> l = new ArrayList<Sk>();
        for (Sk s : skills.values()) if (s.hasHook(hook)) l.add(s);
        return l;
    }

    /** 技能库体检。 */
    public List<String> validate() {
        List<String> problems = new ArrayList<String>();
        for (Sk s : list()) {
            if (s.state == Sk.State.ERROR) problems.add(s.name + ": " + s.error);
            else if (s.sources.isEmpty() && Str.has(s.tool)) problems.add(s.name + ": 声明了 tool(" + s.tool + ") 但没有 .java 代码");
        }
        return problems;
    }

    public long ticks() { return ticks.get(); }

    public void tick() { ticks.incrementAndGet(); }

    // ==================== 加载时机 ====================

    /*
     * 这里以前有一个 2 秒轮询的技能目录监听（mtime 变了就整库重扫）。已经整条删掉：
     *  - 它带来的是"后台线程随时可能重编译技能"的噪音与不确定性（改一半的 md / 编译到一半的 java
     *    都会被扫进去），排查问题时最烦的就是这种"和我无关的时点"；
     *  - 真正的加载时机只有两个：**启动时扫一次**（Boot.init → Skills.scan）与**显式重载**
     *    （skill_write op=reload、控制台 skill reload、promote 之后自动 scan）。
     * 所以：改了技能文件（md / java / prompt.md）必须显式重载，否则要等重启。
     */

    private static String hashOf(File dir, File md) {
        StringBuilder sb = new StringBuilder();
        sb.append(md.isFile() ? md.lastModified() : 0).append(':').append(Fs.size(md)).append('|');
        for (File f : Fs.walk(dir, ".java", 2)) sb.append(f.getName()).append(f.lastModified()).append(f.length()).append(';');
        File prompt = new File(dir, "prompt.md");
        if (prompt.isFile()) sb.append("p").append(prompt.lastModified()).append(prompt.length());
        return Fs.md5(sb.toString().getBytes(Fs.UTF8));
    }
}
