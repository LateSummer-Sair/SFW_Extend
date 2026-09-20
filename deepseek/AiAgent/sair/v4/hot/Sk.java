package sair.v4.hot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一个技能（插件层的最小单位）：一个文件夹 + 同名 md（front matter 声明 + 正文说明书）
 * + 零到多个 .java 源文件（运行期编译）。
 */
public final class Sk {

    /** 状态。 */
    public enum State { LOADED, MD_ONLY, ERROR }

    public String name;
    public String description = "";
    /** 提供给模型的工具名（空 = 本技能不提供工具，只做钩子/文档）。这是<b>注册名</b>，一定合 API 规范。 */
    public String tool = "";
    /** md 里原样声明的名字（可能是中文）；与 {@link #tool} 不同时才有值。 */
    public String toolDeclared = "";
    /** 自动英文化后的注册名；声明名本来就合法时为 null。 */
    public String toolRegistered = null;
    /**
     * 旧"权限档位"字段：<b>恒为空串，没有任何语义</b> —— 旧档位体系（技能 md 的 {@code permission:}、
     * 权限注册表）已随 P9b 整批删除，权限改由技能管控表（数据根 {@code skillctl.json}）按
     * <b>身份 × op</b> 判（{@code h.need("工具名.动作名")}）。
     *
     * <p>保留它只是为了<b>不动可观测面</b>：{@link #toJson()} 的 {@code permission} 键与
     * {@code skill_index.permission} 列照旧（{@code Store} 落库时读它），技能列表的 JSON 形状因此不变。
     * 扫描时由基板固定清空（{@code Skills} 里 {@code sk.level = ""}）。</p>
     *
     * <p><b>不要给它填任何值</b>：现在的权限判据只有技能管控表，给这里填字符串不会生效，
     * 只会让下一个人以为档位还活着。</p>
     */
    public String level = "";
    /** 注入位（system/context/tool/arg），配合技能文件夹里的 prompt.md 使用。 */
    public String inject = "";
    /** 实际注入过的槽位（重载/删除时按它清理，避免旧槽残留）。 */
    public String injectedSlot = null;
    public List<String> hooks = new ArrayList<String>();
    /** airun 元信息：description / returns / params。 */
    public JsonObject airun = new JsonObject();
    /** 用法示例（{@code airun.examples}）：一句话"用户这么说 → 走哪个 op"，写进工具描述。 */
    public List<String> examples = new ArrayList<String>();

    public File dir;
    public File md;
    public File promptFile;
    public List<File> sources = new ArrayList<File>();
    public String doc = "";

    public State state = State.MD_ONLY;
    public String error = "";
    public String hash = "";
    public String entryClass = null;

    /** 编译产物（类名 → 字节码），供独立 ClassLoader 定义。 */
    public Map<String, byte[]> classes = null;
    /** 加载期建好、运行期复用（每次调用新建类加载器会白烧元空间）。 */
    public ClassLoader loader = null;
    public Class<?> cls = null;
    /** shared 状态时的复用实例。 */
    public volatile Object instance = null;
    public boolean shared = false;

    public boolean hasCode() { return classes != null && !classes.isEmpty(); }

    public boolean providesTool() { return tool != null && !tool.trim().isEmpty() && hasCode(); }

    public boolean hasHook(String hook) {
        return hook != null && hooks.contains(hook);
    }

    /**
     * 工具描述 = {@code airun.description}（空则退回顶层 description）。
     * <p>用法示例**不在这里拼** —— 统一由 {@link sair.v4.tool.Tool#fullDesc()} 拼一份
     * （技能与内置工具走同一处），否则技能会拼两遍、内置工具又一遍都拼不上。</p>
     */
    public String toolDesc() {
        String d = airun != null ? sair.v4.kit.J.s(airun, "description", "") : "";
        if (sair.v4.kit.Str.blank(d)) d = description;
        return d;
    }

    public String toolReturns() {
        return airun != null ? sair.v4.kit.J.s(airun, "returns", "") : "";
    }

    public JsonObject schema() {
        JsonObject params = airun == null ? null : sair.v4.kit.J.sub(airun, "params");
        return SkMd.schema(params);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.addProperty("tool", tool);
        if (toolRegistered != null) {
            o.addProperty("tool_declared", toolDeclared);
            o.addProperty("tool_note", "md 里声明的名字不合 API 规范，已自动英文化注册为 " + tool);
        }
        o.addProperty("permission", level);
        o.addProperty("inject", inject);
        JsonArray h = new JsonArray();
        for (String x : hooks) h.add(x);
        o.add("hooks", h);
        o.addProperty("state", state.name());
        o.addProperty("files", sources.size());
        o.addProperty("dir", dir == null ? "" : dir.getAbsolutePath());
        if (!error.isEmpty()) o.addProperty("error", error);
        return o;
    }
}
