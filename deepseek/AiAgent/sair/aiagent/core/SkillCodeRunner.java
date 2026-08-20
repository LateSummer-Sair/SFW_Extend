package sair.aiagent.core;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import sair.aiagent.model.ThirdPartySkill;

/**
 * 三方技能代码段执行器 —— 执行单文件 .md 技能中内嵌的 java/js/nodejs/python 代码段。
 *
 * <h3>统一协议</h3>
 * <ul>
 *   <li>固定入口函数：{@code airun}（强制返回值，返回空则视为错误）。</li>
 *   <li>入参：一个 JSON 字符串（{@code argsJson}），各语言以 JSON 解析后传给 airun。</li>
 *   <li>出参：airun 返回值，统一转为字符串返回给 AI。</li>
 * </ul>
 *
 * <h3>语言实现</h3>
 * <ul>
 *   <li><b>java</b>：JVM 原生 —— 用 {@link DynamicCodeEngine#compileAndCache} 编译并缓存实例，
 *       反射调用 {@code airun(String)} 或 {@code airun()}。</li>
 *   <li><b>js</b>：JVM 原生 —— Nashorn 引擎执行，{@code airun(JSON.parse(args))}。</li>
 *   <li><b>nodejs</b>：系统命令行 {@code node}（需预装）。</li>
 *   <li><b>python</b>：系统命令行 {@code python}（需预装）。</li>
 * </ul>
 */
public class SkillCodeRunner {

    private static final long EXEC_TIMEOUT_SECONDS = 30;

    private final DynamicCodeEngine codeEngine;
    private final File tmpDir;

    /** java 代码段缓存：技能名 → (源码 + 编译实例)。源码变化时自动重新编译。 */
    private final Map<String, CachedJava> javaCache = new ConcurrentHashMap<>();

    public SkillCodeRunner(DynamicCodeEngine codeEngine, File tmpDir) {
        this.codeEngine = codeEngine;
        this.tmpDir = tmpDir != null ? tmpDir
                : new File(System.getProperty("java.io.tmpdir"), "aiagent_skills");
    }

    /** 缓存项：记录源码，便于检测内容变化后失效重编。 */
    private static final class CachedJava {
        final String code;
        final Object instance;
        CachedJava(String code, Object instance) { this.code = code; this.instance = instance; }
    }

    /**
     * 调用三方技能的 airun 入口函数。
     *
     * @param skill    三方技能（须含代码段）
     * @param argsJson 传给 airun 的 JSON 字符串（如 {@code "hello"} 或 {@code {"city":"北京"}}）
     * @return airun 的返回值字符串，或错误信息
     */
    public String invoke(ThirdPartySkill skill, String argsJson) {
        if (skill == null) return "[callskill] 未找到技能";
        Map<String, String> blocks = skill.getCodeBlocks();
        if (blocks == null || blocks.isEmpty()) {
            return "[callskill] 该技能不含可执行代码段（需在 md 中用 <java/js/nodejs/python start>…end> 声明）";
        }
        if (argsJson == null || argsJson.trim().isEmpty()) argsJson = "null";  // 空参规范化为 JSON null，避免 JSON.parse("") 报错

        // 语言优先级：java > js > nodejs > python（原生优先，外部运行时靠后）
        String javaCode = blocks.get("java");
        if (javaCode != null && !javaCode.isEmpty()) return invokeJava(skill.getName(), javaCode, argsJson);
        String jsCode = blocks.get("js");
        if (jsCode != null && !jsCode.isEmpty()) return invokeJs(jsCode, argsJson);
        String nodeCode = blocks.get("nodejs");
        if (nodeCode != null && !nodeCode.isEmpty()) return invokeExternal("node", ".js", nodeCode, argsJson, "nodejs");
        String pyCode = blocks.get("python");
        if (pyCode != null && !pyCode.isEmpty()) return invokeExternal("python", ".py", pyCode, argsJson, "python");
        return "[callskill] 无可用代码段";
    }

    // ==================== Java ====================

    private String invokeJava(String key, String code, String argsJson) {
        CachedJava cached = javaCache.get(key);
        if (cached == null || !cached.code.equals(code)) {
            if (codeEngine == null) return "[callskill] Java 编译引擎未初始化";
            Object instance = codeEngine.compileAndCache(code, key);
            if (instance == null) {
                return "[callskill] Java 编译失败: " + codeEngine.getLastCompilerMessage();
            }
            cached = new CachedJava(code, instance);
            javaCache.put(key, cached);
        }
        Object instance = cached.instance;
        try {
            Method m = instance.getClass().getMethod("airun", String.class);
            return normalizeJava(m.invoke(instance, argsJson));
        } catch (NoSuchMethodException e) {
            try {
                Method m = instance.getClass().getMethod("airun");
                return normalizeJava(m.invoke(instance));
            } catch (NoSuchMethodException e2) {
                return "[callskill] 类 [" + instance.getClass().getSimpleName()
                        + "] 未定义 public Object airun(String) 或 public Object airun() 方法";
            } catch (InvocationTargetException e2) {
                return "[callskill] airun 执行异常: " + unwrap(e2);
            } catch (Exception e2) {
                return "[callskill] airun 调用异常: " + e2;
            }
        } catch (InvocationTargetException e) {
            return "[callskill] airun 执行异常: " + unwrap(e);
        } catch (Exception e) {
            return "[callskill] airun 调用异常: " + e;
        }
    }

    private String normalizeJava(Object result) {
        if (result == null) return emptyResult();
        String s = result.toString();
        if (s == null || s.trim().isEmpty()) return emptyResult();
        return s;
    }

    // ==================== JS ====================

    private String invokeJs(String code, String argsJson) {
        if (codeEngine == null || !codeEngine.isJsAvailable()) {
            return "[callskill] JS 引擎不可用（当前 JVM 未找到 JavaScript 脚本引擎）";
        }
        String script = code + "\nJSON.stringify(airun(JSON.parse(" + jsStringLiteral(argsJson) + ")));";
        String result = codeEngine.evalJS(script);
        if (result == null || result.trim().isEmpty() || "undefined".equals(result.trim())) {
            return emptyResult();
        }
        return result;
    }

    // ==================== nodejs / python ====================

    private String invokeExternal(String cmd, String ext, String code, String argsJson, String lang) {
        String script;
        if ("nodejs".equals(lang)) {
            script = code + "\nconsole.log(JSON.stringify(airun(JSON.parse(process.argv[1]))));";
        } else {
            script = code + "\nimport sys, json\nprint(json.dumps(airun(json.loads(sys.argv[1])), ensure_ascii=False))";
        }
        File scriptFile = null;
        File outFile = null;
        try {
            if (!tmpDir.exists()) tmpDir.mkdirs();
            scriptFile = File.createTempFile("skill_airun_", ext, tmpDir);
            Files.write(scriptFile.toPath(), script.getBytes(StandardCharsets.UTF_8));
            outFile = File.createTempFile("skill_out_", ".txt", tmpDir);

            ProcessBuilder pb = new ProcessBuilder(cmd, scriptFile.getAbsolutePath(), argsJson);
            pb.redirectErrorStream(true);
            pb.redirectOutput(outFile);
            Process p = pb.start();
            if (!p.waitFor(EXEC_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "[callskill] " + cmd + " 执行超时（" + EXEC_TIMEOUT_SECONDS + " 秒）";
            }
            String out = new String(Files.readAllBytes(outFile.toPath()), StandardCharsets.UTF_8);
            int exit = p.exitValue();
            if (exit != 0) {
                return "[callskill] " + cmd + " 执行失败（退出码 " + exit + "）: "
                        + (out != null ? out.trim() : "");
            }
            if (out == null || out.trim().isEmpty()) return emptyResult();
            return out.trim();
        } catch (java.io.IOException e) {
            return "[callskill] " + cmd + " 未安装或不可执行，请先安装 " + cmd + " 运行时（或配置 PATH）";
        } catch (Exception e) {
            return "[callskill] 执行失败: " + e;
        } finally {
            if (scriptFile != null) { try { scriptFile.delete(); } catch (Exception ignored) {} }
            if (outFile != null) { try { outFile.delete(); } catch (Exception ignored) {} }
        }
    }

    // ==================== 工具 ====================

    private String emptyResult() {
        return "[callskill] airun 返回值为空（强制返回值：airun 必须返回有效结果）";
    }

    private static Throwable unwrap(InvocationTargetException e) {
        Throwable cause = e.getCause();
        return cause != null ? cause : e;
    }

    /** 将字符串转为 JS 字符串字面量（加引号并转义），供拼入脚本时使用。 */
    private static String jsStringLiteral(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:   sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
