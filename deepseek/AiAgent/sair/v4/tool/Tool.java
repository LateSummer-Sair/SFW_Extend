package sair.v4.tool;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import sair.v4.ctx.Turn;

/**
 * 一个工具（模型可见的能力）。基板工具与技能工具在这里统一：<b>只有一套注册表</b>。
 * <p>没有"通道"属性，<b>也没有"工具级权限档位"</b>（旧档位体系已随 P9b 整批删除）：工具是 <b>T 类资源</b>，
 * 它的位说了算 —— {@link Registry#visible} 按 {@code T:X} 筛"<b>交不交到手</b>"，
 * {@link Registry#call} 再按 {@code T:X} 拦"<b>点不点得动</b>"（两道都过才执行）。
 * 工具<b>内部执行过程一律不判位</b>（主人的口径：只管入口），而"能不能碰某个资源"由要碰资源那一刻的
 * ACL 判定说了算（{@code Host.need*} → {@code Acl.allow}）。工具自己声明的 {@code op} 同样不参与权限判定。</p>
 */
public final class Tool {

    /** 工具实现（返回 String / JsonObject / Map / List / null）。 */
    public interface Handler {
        Object call(JsonObject args, Turn t);
    }

    private final String name;
    private final String desc;
    private final String returns;
    private final String owner;
    private final boolean skill;
    private final JsonObject params;
    private final List<String> examples;
    private final Handler handler;

    private Tool(Builder b) {
        this.name = b.name;
        this.desc = b.desc;
        this.returns = b.returns;
        this.owner = b.owner;
        this.skill = b.skill;
        this.params = b.params == null ? defaultParams() : b.params;
        this.examples = b.examples == null ? new ArrayList<String>() : b.examples;
        this.handler = b.handler;
    }

    public String name() { return name; }

    public String desc() { return desc; }

    public String returns() { return returns; }

    /** 归属：{@code builtin} 或技能名。 */
    public String owner() { return owner; }

    public boolean skill() { return skill; }

    public JsonObject params() { return params; }

    /** 用法示例（来自技能 md 的 {@code airun.examples}）：只给"这句话该走哪个 op"这种一句话例子。 */
    public List<String> examples() { return Collections.unmodifiableList(examples); }

    public Handler handler() { return handler; }

    /** 去掉实现的副本：给技能的只读工具视图用（技能不能借它绕过权限复核）。 */
    public Tool withoutHandler() {
        Builder b = new Builder(name).desc(desc).returns(returns)
                .owner(owner).skill(skill).params(params).examples(examples).handler(null);
        return b.build();
    }

    /**
     * 补上用法示例的副本（示例一律外挂在 md：技能写自己的 front matter，内置工具写在
     * {@code prompts/tools-index.md}）。注册期由 {@link Registry#add} 用它给没写示例的工具补上，
     * 这样"给内置工具加示例"也不需要往 Java 里写文案。
     */
    public Tool withExamples(List<String> ex) {
        if (ex == null || ex.isEmpty()) return this;
        Builder b = new Builder(name).desc(desc).returns(returns)
                .owner(owner).skill(skill).params(params).examples(ex).handler(handler);
        return b.build();
    }

    /** OpenAI/DeepSeek function schema。 */
    public JsonObject schema() {
        JsonObject fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("description", fullDesc());
        fn.add("parameters", params);
        JsonObject root = new JsonObject();
        root.addProperty("type", "function");
        root.add("function", fn);
        return root;
    }

    /**
     * 工具说明 = 描述 + 用法示例 + 返回说明（"返回什么"能显著提高模型的调用正确率）。
     * <p>示例在这里统一拼进描述（技能来自 {@code airun.examples}，内置工具来自
     * {@code prompts/tools-index.md}）—— 只在这一处拼，避免两头各拼一遍变成重复文案。</p>
     */
    public String fullDesc() {
        StringBuilder sb = new StringBuilder();
        sb.append(desc == null ? "" : desc);
        if (!examples.isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(EXAMPLE_PREFIX);
            for (int i = 0; i < examples.size(); i++) {
                if (i > 0) sb.append("；");
                sb.append(examples.get(i));
            }
        }
        if (returns != null && !returns.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(" ");
            sb.append("返回：").append(returns.trim());
        }
        return sb.toString();
    }

    /** 示例前缀（与 {@code SkMd} 给参数级示例用的「示例：」保持同一套写法）。 */
    public static final String EXAMPLE_PREFIX = "例：";

    private static JsonObject defaultParams() {
        JsonObject p = new JsonObject();
        p.addProperty("type", "object");
        p.add("properties", new JsonObject());
        return p;
    }

    public static Builder of(String name) { return new Builder(name); }

    /** 构造器。 */
    public static final class Builder {
        private final String name;
        private String desc = "";
        private String returns = "";
        private String owner = "builtin";
        private boolean skill = false;
        private JsonObject params = null;
        private List<String> examples = null;
        private Handler handler;

        private Builder(String name) { this.name = name; }

        public Builder desc(String v) { this.desc = v; return this; }

        public Builder returns(String v) { this.returns = v; return this; }

        public Builder owner(String v) { this.owner = v; return this; }

        public Builder skill(boolean v) { this.skill = v; return this; }

        public Builder params(JsonObject v) { this.params = v; return this; }

        public Builder examples(List<String> v) {
            this.examples = v == null ? null : new ArrayList<String>(v);
            return this;
        }

        public Builder handler(Handler h) { this.handler = h; return this; }

        public Tool build() { return new Tool(this); }
    }
}
