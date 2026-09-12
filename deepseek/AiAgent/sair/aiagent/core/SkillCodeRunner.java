package sair.aiagent.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.ThirdPartySkill;

/**
 * 三方技能代码执行器 —— 编译并执行技能文件夹里的 Java 源码（V4.0 起只支持 Java）。
 *
 * <h3>注册模型</h3>
 * <p>
 * 旧实现是「调用时才发现没编译 → 动态编译 → 缓存实例」。现在改为
 * <b>加载 .md 时就把 airun 注册进运行时</b>：
 * </p>
 * <ul>
 *   <li>{@link #register(ThirdPartySkill)} 在技能加载/重载时调用，<b>立即编译</b> java 段
 *       （js 做语法预检，node/python 交给运行期），把编译产物按技能名登记到运行时注册表；</li>
 *   <li>编译错误在<b>加载期</b>就暴露（红字 + 索引标记），不再等模型踩上去才发现；</li>
 *   <li>调用时直接取注册表里的产物，<b>不再动态编译</b>，首次调用没有编译延迟；</li>
 *   <li>改了 .md → {@link #register} 收到新代码哈希 → 重新编译并<b>新替旧</b>
 *       （旧 ClassLoader/实例失去引用后被 GC 回收）；</li>
 *   <li>技能被删除 → {@link #prune(Set)} 摘掉陈旧条目。</li>
 * </ul>
 * <p>
 * 注册表只缓存「已加载的产物」，不落盘：进程重启后由加载流程重新注册，语义与旧版一致。
 * </p>
 *
 * <h3>统一协议</h3>
 * <ul>
 *   <li>入口函数固定 {@code airun}。</li>
 *   <li>入参：按 front matter 声明的类型归一化后的 JSON（可选注入框架上下文，见下）。</li>
 *   <li>出参：集合/数组/Map 序列化为 JSON；空值视为「执行成功但无输出」。</li>
 *   <li>
 *     声明 {@code context: true} 的技能，{@code airun} 会额外收到一份只读上下文投影
 *     （{@link SkillContext}），签名按 <b>参数个数多的优先</b>尝试：
 *     <pre>
 *     airun(Map args, Map ctx)   /  airun(Map args, String ctxJson)
 *     airun(String argsJson, Map ctx) / airun(String argsJson, String ctxJson)
 *     </pre>
 *     未声明 {@code context} 时只传一个参数，与旧协议完全一致（零回归）。
 *   </li>
 * </ul>
 *
 * <h3>宿主保护</h3>
 * <ul>
 *   <li>每次执行开<b>独立守护线程</b>（不进共享池），卡死只泄漏它自己；</li>
 *   <li>{@link #slots} 信号量限制同时存活的执行数，名额耗尽时明确拒绝并提示重启；</li>
 *   <li>超时可由技能声明（{@code timeout:} 秒，限幅 5~300），默认 30s；</li>
 *   <li>node/python 输出流式读取、超限即杀。</li>
 * </ul>
 */
public class SkillCodeRunner {

    /** 默认执行超时（秒）。 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 30;
    /** 技能可声明的超时上下限（秒）。 */
    private static final long MIN_TIMEOUT_SECONDS = 5;
    private static final long MAX_TIMEOUT_SECONDS = 300;
    /** 同时存活的技能执行线程上限（卡死线程会占用名额，用满即拒绝新调用）。 */
    private static final int MAX_CONCURRENT = 4;
    /** 单次调用捕获的 stdout 上限（字符），防止刷屏。 */
    private static final int MAX_CAPTURED_STDOUT = 4000;
    /** 异常栈里最多回显几个技能内帧。 */
    private static final int MAX_SKILL_FRAMES = 5;

    private final DynamicCodeEngine codeEngine;
    private final File tmpDir;
    /** 插件数据目录（投影给技能的 ctx.data_dir）。 */
    private volatile String dataDir;

    private final Semaphore slots = new Semaphore(MAX_CONCURRENT);

    /** 运行时注册表：技能名 → 已编译产物（加载期登记，调用期只读）。 */
    private final Map<String, Compiled> registry = new ConcurrentHashMap<>();

    /** 线程级 stdout 捕获：全局只装一次 tee，写入按当前线程路由，并发安全。 */
    private static final ThreadLocal<ByteArrayOutputStream> OUT_CAPTURE = new ThreadLocal<>();
    private static volatile boolean teeInstalled = false;

    /**
     * 共享 Gson：数字按「整→Long / 带小数→Double」解析。
     * <p>Gson 默认策略把所有数字都解析成 Double，于是声明 {@code type: integer} 的参数
     * 到技能手里是 {@code 3.0}，技能原样回显给模型也是 {@code 3.0}
     * （实测：days=3.0、affection=777.0）。加上这个策略后 integer → Long。</p>
     */
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder()
            .setObjectToNumberStrategy(com.google.gson.ToNumberPolicy.LONG_OR_DOUBLE)
            .create();

    public SkillCodeRunner(DynamicCodeEngine codeEngine, File tmpDir) {
        this.codeEngine = codeEngine;
        this.tmpDir = tmpDir != null ? tmpDir
                : new File(System.getProperty("java.io.tmpdir"), "aiagent_skills");
        this.dataDir = (tmpDir != null && tmpDir.getParentFile() != null)
                ? tmpDir.getParentFile().getAbsolutePath() : null;
        installStdoutTee();
    }

    /** 已注册（且编译成功）的技能名快照。 */
    public Set<String> registeredNames() {
        return registry.keySet();
    }

    /** 注册表统计（供 ai/status 观测）。 */
    public String statusSummary() {
        int ok = 0, bad = 0;
        long calls = 0;
        for (Compiled c : registry.values()) {
            if (c.isUsable()) ok++; else bad++;
            calls += c.invocations;
        }
        return "已注册技能=" + registry.size() + "（可用 " + ok + " / 不可用 " + bad + "）"
                + ", 累计调用=" + calls;
    }

    // ==================== 加载期注册 ====================

    /** 单个技能的已编译产物。 */
    private static final class Compiled {
        final String codeHash;
        final String sourceFile;
        /** 入口类（含 airun 方法、被实例化的那个）。 */
        volatile Class<?> entryClass;
        /** shared 模式下复用的实例（per_call 模式为 null）。 */
        volatile Object instance;
        /** 本次编译出的类数量（含辅助类/内部类），仅用于日志。 */
        volatile int classCount;
        /** 非 null 表示加载期编译/校验失败，调用时直接回这个错误，不再重试。 */
        volatile String error;
        volatile long compiledAt;
        volatile long invocations;
        volatile long totalMillis;

        Compiled(String codeHash, String sourceFile) {
            this.codeHash = codeHash;
            this.sourceFile = sourceFile;
        }

        boolean isUsable() { return error == null; }
    }

    /** 读取技能文件夹里的全部 .java（文件名 → 源码）。 */
    private static Map<String, String> readSources(ThirdPartySkill skill) {
        Map<String, String> out = new LinkedHashMap<>();
        File dir = (skill.getSourceDir() == null) ? null : new File(skill.getSourceDir());
        if (dir == null || !dir.isDirectory()) return out;
        for (String n : skill.getJavaSources()) {
            File f = new File(dir, n);
            try {
                out.put(n, new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
                // 读不到就当没有这个文件（加载期另有问题点名）
            }
        }
        return out;
    }

    private static String joinSources(Map<String, String> sources) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            sb.append("// ").append(e.getKey()).append('\n').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String names(List<Class<?>> classes) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> c : classes) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(c.getSimpleName());
        }
        return sb.toString();
    }

    /** 源码指纹是否与注册时一致（含文件集合、每个文件内容、以及 md 声明的 entry:）。 */
    private static boolean sameCode(Compiled c, ThirdPartySkill skill) {
        if (c == null || c.entryClass == null) return false;
        Map<String, String> sources = readSources(skill);
        if (sources.isEmpty()) return false;
        return hashOf(skill, sources).equals(c.codeHash);
    }

    /** 注册指纹 = entry 声明 + 全部源码。改 entry: 也要重新编译判定，否则会一直用旧的失败结果。 */
    private static String hashOf(ThirdPartySkill skill, Map<String, String> sources) {
        String entry = (skill.getEntryClass() == null) ? "" : skill.getEntryClass();
        return ThirdPartySkill.computeContentHash(entry + "\u0000" + joinSources(sources));
    }

    /**
     * 加载期注册：把技能文件夹里的 .java 一起编译、挑出入口类，登记到运行时。
     * <p>同名比对源码哈希（未变化直接复用），变化则重新编译并<b>新替旧</b>。</p>
     *
     * @return null 表示注册成功；否则返回失败原因（供加载期红字提示）
     */
    public String register(ThirdPartySkill skill) {
        if (skill == null || !skill.hasJavaSource()) return null;
        String name = skill.getName();
        Map<String, String> sources = readSources(skill);
        if (sources.isEmpty()) return null;
        // 指纹要覆盖「影响注册结果的一切」：源码 + md 里声明的 entry:。
        // 只哈希源码的话，改了 entry: 会被当成「没变化」而继续用旧的失败结果（实测踩过）。
        String hash = hashOf(skill, sources);

        Compiled old = registry.get(name);
        if (old != null && hash.equals(old.codeHash) && old.sourceFile != null
                && old.sourceFile.equals(skill.getSourceFile())) {
            return old.error;   // 内容与来源都没变 → 复用（含此前已确定的失败结果）
        }

        Compiled c = new Compiled(hashOf(skill, sources), skill.getSourceFile());
        String err = null;
        if (codeEngine == null) {
            err = "Java 编译引擎未初始化";
        } else {
            DynamicCodeEngine.CompiledUnit unit = codeEngine.compileAll(sources, name);
            if (unit == null) {
                err = "Java 编译失败: " + codeEngine.getLastCompilerMessage();
            } else {
                // 入口类：front matter `entry:` 优先，否则取「唯一有 airun 方法的类」
                Class<?> entry = null;
                List<Class<?>> withAirun = new ArrayList<>();
                for (Class<?> cls : unit.getClasses().values()) {
                    if (cls.getName().contains("$")) continue;      // 内部类不作为入口候选
                    if (hasAnyAirun(cls)) withAirun.add(cls);
                }
                String declared = skill.getEntryClass();
                if (declared != null) {
                    entry = unit.find(declared);
                    if (entry == null) {
                        err = "md 里声明的 entry: " + declared + " 在编译产物里不存在（实际类: "
                            + unit.getClasses().keySet() + "）";
                    } else if (!hasAnyAirun(entry)) {
                        err = "entry: " + declared + " 里没有 airun 方法";
                    }
                } else if (withAirun.size() == 1) {
                    entry = withAirun.get(0);
                } else if (withAirun.isEmpty()) {
                    err = "编译产物里没有任何带 airun 方法的类（类: " + unit.getClasses().keySet() + "）";
                } else {
                    err = "有多个类都带 airun 方法 " + names(withAirun)
                        + "，请在 md 的 front matter 里用 entry: <类名> 指定入口";
                }
                if (err == null && entry != null) {
                    try {
                        Object inst = entry.getConstructor().newInstance();
                        c.entryClass = entry;
                        // 无状态技能（默认 per_call）不长期持有实例；声明 state: shared 才复用
                        c.instance = skill.isStateShared() ? inst : null;
                        c.classCount = unit.getClasses().size();
                    } catch (NoSuchMethodException e) {
                        err = "入口类 [" + entry.getSimpleName() + "] 缺少 public 无参构造器";
                    } catch (Exception e) {
                        err = "入口类实例化失败: " + describeThrowable(e);
                    }
                }
            }
        }
        c.error = err;
        c.compiledAt = System.currentTimeMillis();
        registry.put(name, c);

        if (err != null) {
            AiAgentActivity.debugLog("[thirdskill] ✗ 注册失败 " + name + " (java): " + err);
        } else if (old != null) {
            AiAgentActivity.debugLog("[thirdskill] 已用新版本替换注册 #" + name + " (java"
                    + ")，旧编译产物释放");
        } else {
            AiAgentActivity.debugLog("[thirdskill] 已注册 " + name + " (java)"
                    + (c.entryClass != null ? " → " + c.entryClass.getSimpleName()
                       + "（" + c.classCount + " 个类）" : ""));
        }
        return err;
    }

    /** 批量注册（加载/重载后调用），返回失败数量。 */
    public int registerAll(java.util.Collection<ThirdPartySkill> skills) {
        int failed = 0;
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ThirdPartySkill s : skills) {
            if (s == null || !s.hasJavaSource()) continue;
            names.add(s.getName());
            if (register(s) != null) failed++;
        }
        prune(names);   // 已删除的技能从注册表摘掉
        return failed;
    }

    /** 摘掉已不存在的技能的编译产物（新替旧/删除的收尾）。 */
    public void prune(Set<String> liveNames) {
        registry.keySet().removeIf(n -> !liveNames.contains(n));
    }

    /** 加载期红字提示：把「有代码但编译不过」的技能在控制台点名。 */
    public String describeRegistrationProblem(String skillName) {
        Compiled c = registry.get(skillName);
        return (c == null) ? null : c.error;
    }


    // ==================== 调用期 ====================

    /**
     * 调用三方技能的 airun 入口函数。
     *
     * @param skill    三方技能（须含代码段）
     * @param argsJson 模型填的参数（已按声明类型归一化）
     * @param ctx      请求上下文（可为 null；技能声明 context:true 时才会传给 airun）
     * @return airun 的返回值字符串，或错误信息
     */
    public String invoke(ThirdPartySkill skill, String argsJson, ToolContext ctx) {
        if (skill == null) return "[callskill] 未找到技能";
        if (!skill.hasJavaSource()) {
            return "[callskill] 该技能不含 Java 源码（工具技能需要独立文件夹："
                 + "skills/<技能名>/<技能名>.md + *.java）";
        }

        // 注册表缺失（例如技能刚加入还没重扫）→ 立即补注册，保持「自愈」语义
        Compiled c = registry.get(skill.getName());
        if (c == null || !sameCode(c, skill)) {
            register(skill);
            c = registry.get(skill.getName());
        }
        if (c != null && c.error != null) {
            return "[callskill] " + skill.getName() + " 不可用（加载期校验失败）: " + c.error;
        }

        if (argsJson == null || argsJson.trim().isEmpty()) argsJson = "null";
        final String normalized = normalizeArgs(skill, argsJson);
        // 上下文 JSON：声明了 context:true 一定要给；写了双参 airun 也直接给
        // （该签名本身就是「我要上下文」的声明，少一个坑）。
        final boolean javaTwoArg = c != null && c.entryClass != null && hasTwoArgAirun(c.entryClass);
        final String ctxJson = (skill.needsContext() || javaTwoArg) ? ctxToJson(ctx, skill) : null;
        final long timeoutMs = resolveTimeoutMs(skill);
        final ThirdPartySkill fSkill = skill;
        final Compiled fCompiled = c;

        if (!slots.tryAcquire()) {
            return "[callskill] 已有 " + MAX_CONCURRENT + " 个技能执行线程在运行或卡死未退出，"
                 + "已暂停新的技能执行以保护宿主（请检查技能代码是否有死循环；重启插件可恢复）";
        }
        final AtomicReference<String> out = new AtomicReference<>();
        final long t0 = System.currentTimeMillis();
        Thread t = new Thread(() -> {
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            OUT_CAPTURE.set(captured);
            try {
                String r = invokeJava(fSkill, fCompiled, normalized, ctxJson);
                String log = new String(captured.toByteArray(), StandardCharsets.UTF_8);
                if (log != null && !log.trim().isEmpty()) {
                    if (log.length() > MAX_CAPTURED_STDOUT) {
                        log = log.substring(0, MAX_CAPTURED_STDOUT) + "...(输出过长已截断)";
                    }
                    r = (r == null ? "" : r) + "\n[stdout] " + log.trim();
                }
                out.set(r);
            } catch (Throwable e) {
                out.set("[callskill] 代码执行失败: " + describeThrowable(e));
            } finally {
                OUT_CAPTURE.remove();
                if (fCompiled != null) {
                    fCompiled.invocations++;
                    fCompiled.totalMillis += (System.currentTimeMillis() - t0);
                }
                slots.release();   // 只有线程真正退出才归还名额
            }
        }, "SkillRun-" + skill.getName());
        t.setDaemon(true);
        t.start();
        try {
            t.join(timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            t.interrupt();   // 尽力而为：响应中断的代码会退出并归还名额，死循环则继续占用
            AiAgentActivity.debugLog("[callskill] " + skill.getName() + " 执行超时（"
                    + (timeoutMs / 1000) + " 秒），线程仍在运行");
            return "[callskill] 代码执行超时（" + (timeoutMs / 1000) + " 秒）";
        }
        String r = out.get();
        return r != null ? r : "[callskill] 代码未返回结果";
    }


    /** 技能声明的超时（秒 → 毫秒，限幅）。 */
    private static long resolveTimeoutMs(ThirdPartySkill skill) {
        int sec = skill.getTimeoutSeconds();
        long s = (sec > 0) ? sec : DEFAULT_TIMEOUT_SECONDS;
        s = Math.max(MIN_TIMEOUT_SECONDS, Math.min(MAX_TIMEOUT_SECONDS, s));
        return s * 1000L;
    }

    /** 构建注入给 airun 的上下文 JSON。 */
    private String ctxToJson(ToolContext ctx, ThirdPartySkill skill) {
        try {
            Map<String, Object> m = SkillContext.build(ctx, skill.getName(), skill.getSourceFile(), dataDir);
            return GSON.toJson(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    // ==================== 入参归一化（按声明类型转换） ====================

    /**
     * 按 airun 声明的参数类型归一化入参 JSON。
     * <p>让声明的 {@code type} 真正生效（{@code "3"} → 3、{@code "true"} → true、
     * 单值 → 单元素数组、对象字符串 → 对象）；未声明参数时原样返回。</p>
     */
    static String normalizeArgs(ThirdPartySkill skill, String argsJson) {
        if (skill == null || !skill.hasAirunSchema()) return argsJson;
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(argsJson);
            if (root == null || !root.isJsonObject()) {
                java.util.List<ThirdPartySkill.AirunParam> ps = skill.getAirunParams();
                if (ps.size() == 1 && root != null) {
                    com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                    o.add(ps.get(0).name, coerce(root, ps.get(0)));
                    return o.toString();
                }
                return argsJson;
            }
            com.google.gson.JsonObject src = root.getAsJsonObject();
            com.google.gson.JsonObject dst = new com.google.gson.JsonObject();
            for (ThirdPartySkill.AirunParam p : skill.getAirunParams()) {
                com.google.gson.JsonElement v = src.get(p.name);
                if (v == null || v.isJsonNull()) {
                    if (p.defaultValue != null) {
                        // 声明了默认值：模型没给就补上（可选参数不必让代码自己判空）
                        dst.add(p.name, com.google.gson.JsonParser.parseString(p.defaultValue));
                    } else if (p.required) {
                        AiAgentActivity.debugLog("[callskill] 缺少必填参数 " + p.name
                                + "（技能 " + skill.getName() + "）");
                    }
                    continue;
                }
                dst.add(p.name, coerce(v, p));
            }
            // 保留未声明的额外字段（作者可能自己解析更多字段）
            for (Map.Entry<String, com.google.gson.JsonElement> e : src.entrySet()) {
                if (!dst.has(e.getKey())) dst.add(e.getKey(), e.getValue());
            }
            return dst.toString();
        } catch (Exception e) {
            return argsJson;   // 解析失败就用原串，绝不因为归一化把调用打死
        }
    }

    /** 把单个 JSON 值按声明的参数类型转换。 */
    private static com.google.gson.JsonElement coerce(com.google.gson.JsonElement v,
                                                       ThirdPartySkill.AirunParam p) {
        try {
            switch (p.type) {
                case "integer": {
                    if (v.isJsonPrimitive()) {
                        com.google.gson.JsonPrimitive pr = v.getAsJsonPrimitive();
                        if (pr.isNumber()) return new com.google.gson.JsonPrimitive(pr.getAsLong());
                        String s = pr.getAsString().trim();
                        if (!s.isEmpty()) return new com.google.gson.JsonPrimitive((long) Double.parseDouble(s));
                    }
                    return v;
                }
                case "number": {
                    if (v.isJsonPrimitive()) {
                        com.google.gson.JsonPrimitive pr = v.getAsJsonPrimitive();
                        if (pr.isNumber()) return new com.google.gson.JsonPrimitive(pr.getAsDouble());
                        String s = pr.getAsString().trim();
                        if (!s.isEmpty()) return new com.google.gson.JsonPrimitive(Double.parseDouble(s));
                    }
                    return v;
                }
                case "boolean": {
                    if (v.isJsonPrimitive()) {
                        com.google.gson.JsonPrimitive pr = v.getAsJsonPrimitive();
                        if (pr.isBoolean()) return v;
                        String s = pr.getAsString().trim().toLowerCase();
                        if ("true".equals(s) || "yes".equals(s) || "1".equals(s)) {
                            return new com.google.gson.JsonPrimitive(true);
                        }
                        if ("false".equals(s) || "no".equals(s) || "0".equals(s)) {
                            return new com.google.gson.JsonPrimitive(false);
                        }
                    }
                    return v;
                }
                case "array": {
                    if (v.isJsonArray()) {
                        com.google.gson.JsonArray in = v.getAsJsonArray();
                        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
                        ThirdPartySkill.AirunParam itemType =
                                new ThirdPartySkill.AirunParam("_", p.items, "", false);
                        for (com.google.gson.JsonElement e : in) out.add(coerce(e, itemType));
                        return out;
                    }
                    com.google.gson.JsonArray single = new com.google.gson.JsonArray();
                    single.add(v);
                    return single;
                }
                case "object": {
                    if (v.isJsonObject()) return v;
                    if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                        try {
                            com.google.gson.JsonElement parsed =
                                    com.google.gson.JsonParser.parseString(v.getAsString());
                            if (parsed != null && parsed.isJsonObject()) return parsed;
                        } catch (Exception ignored) {}
                    }
                    return v;
                }
                case "string":
                default: {
                    if (v.isJsonPrimitive()) {
                        com.google.gson.JsonPrimitive pr = v.getAsJsonPrimitive();
                        if (pr.isString()) return v;
                        return new com.google.gson.JsonPrimitive(pr.getAsString());
                    }
                    return v;
                }
            }
        } catch (Exception e) {
            return v;
        }
    }

    // ==================== Java ====================

    private String invokeJava(ThirdPartySkill skill, Compiled c, String argsJson, String ctxJson) {
        if (c == null || c.entryClass == null) return "[callskill] Java 代码未注册";
        Object instance;
        try {
            // 默认 per_call：每次调用新实例，避免成员变量被并发请求互相污染
            instance = skill.isStateShared() && c.instance != null
                    ? c.instance
                    : c.entryClass.getConstructor().newInstance();
        } catch (Exception e) {
            return "[callskill] 实例化失败: " + describeThrowable(e);
        }
        boolean needCtx = skill.needsContext() || hasTwoArgAirun(c.entryClass);
        // 需要上下文的签名：参数多的优先（Map+Map → Map+String → String+Map → String+String）
        if (needCtx) {
            String r = tryInvoke2(instance, c.entryClass, argsJson, ctxJson);
            if (r != null) return r;
        }
        String r = tryInvoke1(instance, c.entryClass, argsJson);
        if (r != null) return r;
        return "[callskill] 类 [" + c.entryClass.getSimpleName() + "] 不含可执行 airun 方法"
             + "（支持 airun(Map) / airun(String) / airun()，"
             + "以及带上下文双入参的 airun(Map,Map) / airun(Map,String)"
             + " / airun(String,Map) / airun(String,String)）";
    }

    /** 类里是否存在双入参 airun（四种组合任一），用于「作者写了双参就自动给上下文」。 */
    private static boolean hasTwoArgAirun(Class<?> clazz) {
        if (clazz == null) return false;
        Class<?>[][] sigs = {
                {Map.class, Map.class}, {Map.class, String.class},
                {String.class, Map.class}, {String.class, String.class},
        };
        for (Class<?>[] sig : sigs) {
            try {
                clazz.getMethod("airun", sig[0], sig[1]);
                return true;
            } catch (NoSuchMethodException ignored) {
                // 试下一个签名
            }
        }
        return false;
    }

    /** 类里是否存在任意一种 airun 签名（单入参 / 无参 / 双入参）。 */
    private static boolean hasAnyAirun(Class<?> clazz) {
        if (clazz == null) return false;
        if (hasTwoArgAirun(clazz)) return true;
        try {
            clazz.getMethod("airun", Map.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            // 继续试
        }
        try {
            clazz.getMethod("airun", String.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            // 继续试
        }
        try {
            clazz.getMethod("airun");
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    /** 尝试 airun(Map,Map) → airun(Map,String) → airun(String,Map) → airun(String,String)。 */
    private String tryInvoke2(Object instance, Class<?> clazz, String argsJson, String ctxJson) {
        Object[][] variants = {
                {Map.class, Map.class},
                {Map.class, String.class},
                {String.class, Map.class},
                {String.class, String.class},
        };
        for (Object[] sig : variants) {
            Class<?> a0 = (Class<?>) sig[0], a1 = (Class<?>) sig[1];
            try {
                Method m = clazz.getMethod("airun", a0, a1);
                Object v0 = (a0 == Map.class) ? toMap(argsJson) : argsJson;
                Object v1 = (a1 == Map.class) ? toMap(ctxJson) : ctxJson;
                return normalizeJava(m.invoke(instance, v0, v1));
            } catch (NoSuchMethodException ignored) {
                // 试下一个签名
            } catch (InvocationTargetException e) {
                return "[callskill] airun 执行异常: " + describeThrowable(e);
            } catch (Exception e) {
                return "[callskill] airun 调用异常: " + describeThrowable(e);
            }
        }
        return null;
    }

    /** 尝试 airun(Map) → airun(String) → airun()。 */
    private String tryInvoke1(Object instance, Class<?> clazz, String argsJson) {
        try {
            Method m = clazz.getMethod("airun", Map.class);
            return normalizeJava(m.invoke(instance, toMap(argsJson)));
        } catch (NoSuchMethodException ignored) {
        } catch (InvocationTargetException e) {
            return "[callskill] airun 执行异常: " + describeThrowable(e);
        } catch (Exception e) {
            return "[callskill] airun 调用异常: " + describeThrowable(e);
        }
        try {
            Method m = clazz.getMethod("airun", String.class);
            return normalizeJava(m.invoke(instance, argsJson));
        } catch (NoSuchMethodException ignored) {
        } catch (InvocationTargetException e) {
            return "[callskill] airun 执行异常: " + describeThrowable(e);
        } catch (Exception e) {
            return "[callskill] airun 调用异常: " + describeThrowable(e);
        }
        try {
            Method m = clazz.getMethod("airun");
            return normalizeJava(m.invoke(instance));
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (InvocationTargetException e) {
            return "[callskill] airun 执行异常: " + describeThrowable(e);
        } catch (Exception e) {
            return "[callskill] airun 调用异常: " + describeThrowable(e);
        }
    }

    /** 把归一化后的 JSON 转成 Map（供 airun(Map) 使用）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(String argsJson) {
        if (argsJson == null) return null;
        try {
            Object o = GSON.fromJson(argsJson, Map.class);
            return (o instanceof Map) ? (Map<String, Object>) o : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizeJava(Object result) {
        if (result == null) return emptyResult();
        String s;
        if (result instanceof Map || result instanceof java.util.Collection || result.getClass().isArray()) {
            try {
                s = GSON.toJson(result);
            } catch (Exception e) {
                s = String.valueOf(result);
            }
        } else if (result instanceof CharSequence || result instanceof Number
                || result instanceof Boolean || result instanceof Character) {
            s = result.toString();
        } else {
            try {
                String json = GSON.toJson(result);
                s = (json != null && json.length() > 2) ? json : result.toString();
            } catch (Exception e) {
                s = result.toString();
            }
        }
        if (s == null || s.trim().isEmpty()) return emptyResult();
        return s;
    }

    // ==================== 诊断 ====================

    /**
     * 异常描述：附上<b>技能自身类的栈帧</b>。
     * <p>旧实现只回 {@code e.toString()}（如 {@code java.lang.NullPointerException: xxx}），
     * 没有行号，作者根本没法定位。现在把技能类里的前几帧带上。</p>
     */
    static String describeThrowable(Throwable e) {
        if (e == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getName());
        if (e.getMessage() != null) sb.append(": ").append(e.getMessage());
        StackTraceElement[] st = e.getStackTrace();
        if (st != null) {
            int shown = 0;
            for (StackTraceElement f : st) {
                String cn = f.getClassName();
                if (cn == null) continue;
                // 只回显技能自身的帧（排除 JDK 与插件框架）
                if (cn.startsWith("java.") || cn.startsWith("javax.") || cn.startsWith("sun.")
                        || cn.startsWith("jdk.") || cn.startsWith("com.google.")) {
                    continue;
                }
                sb.append("\n    at ").append(f);
                if (++shown >= MAX_SKILL_FRAMES) break;
            }
            if (shown == 0 && st.length > 0) sb.append("\n    at ").append(st[0]);
        }
        Throwable cause = e.getCause();
        if (cause != null && cause != e) sb.append("\n  caused by: ").append(describeThrowable(cause));
        return sb.toString();
    }

    /**
     * 安装 stdout tee：全局只装一次，写入按<b>当前线程</b>路由到捕获缓冲（无缓冲则原样透传）。
     * <p>这样 Java 技能里的 {@code System.out.println} 既能照常进控制台，又能作为调试输出
     * 回显在工具结果里；并发调用不会互相串流。</p>
     */
    private static synchronized void installStdoutTee() {
        if (teeInstalled) return;
        final PrintStream original = System.out;
        PrintStream tee = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
                ByteArrayOutputStream cap = OUT_CAPTURE.get();
                if (cap != null) cap.write(b);
                original.write(b);
            }
            @Override
            public void write(byte[] b, int off, int len) {
                ByteArrayOutputStream cap = OUT_CAPTURE.get();
                if (cap != null) cap.write(b, off, len);
                original.write(b, off, len);
            }
            @Override
            public void flush() {
                original.flush();
            }
        }, true);
        System.setOut(tee);
        teeInstalled = true;
        AiAgentActivity.debugLog("[thirdskill] 已安装 stdout 捕获（技能内的 println 会回显在工具结果里）");
    }

    /** 空返回值的语义：<b>执行成功但没有输出</b>，不是错误。 */
    private String emptyResult() {
        return "[callskill] 执行成功（airun 未返回内容）";
    }

}
