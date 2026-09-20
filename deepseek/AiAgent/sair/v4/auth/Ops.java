package sair.v4.auth;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全部 op 的清单：技能装载时声明（{@link OpList}）、内置工具注册时声明。
 *
 * <p>它是<b>管控表的骨架</b>：{@code ai/perm gen} 按这份清单把每个 op 列成一行空的
 * {@code Run["op"]}（空 = 未授权），主人再往要开口的那几行补身份。清单只增不减地跟着技能走 ——
 * 卸载一个技能，它的 op 从清单里消失，账本里那几行留着也无害（认不出的 op 不会命中任何调用）。</p>
 *
 * <p>线程口径：装载期单线程写、运行期多线程读，方法都是 {@code synchronized}。</p>
 */
public final class Ops implements OpList {

    /** 工具名 + 动作名 → op 名（动作空 = 就是工具名）。 */
    public static String op(String tool, String action) {
        String t = tool == null ? "" : tool.trim();
        String a = action == null ? "" : action.trim();
        if (a.isEmpty()) return t;
        return t.isEmpty() ? a : (t + "." + a);
    }

    /** op 名 → 工具名（{@code memory.remember} → {@code memory}）。 */
    public static String toolOf(String op) {
        if (op == null) return "";
        String s = op.trim();
        int i = s.indexOf('.');
        return i < 0 ? s : s.substring(0, i);
    }

    /** op 名 → 动作名（单动作返回空串）。 */
    public static String actionOf(String op) {
        if (op == null) return "";
        String s = op.trim();
        int i = s.indexOf('.');
        return i < 0 ? "" : s.substring(i + 1);
    }

    /** 工具名 → 动作名 → 说明。工具声明过就按声明走，没声明过就当"单动作 = 工具名"。 */
    private final Map<String, LinkedHashMap<String, String>> byTool =
            new LinkedHashMap<String, LinkedHashMap<String, String>>();

    /** 声明一个 op（重复声明同一个 op 只保留第一条的说明）。 */
    @Override
    public synchronized void add(String tool, String action, String note) {
        declare(tool, action, note);
    }

    /** 声明一个 op（重复声明同一个 op 只保留第一条的说明）。 */
    public synchronized void declare(String tool, String action, String note) {
        String t = tool == null ? "" : tool.trim();
        if (t.isEmpty()) return;
        String a = action == null ? "" : action.trim();
        LinkedHashMap<String, String> m = byTool.get(t);
        if (m == null) {
            m = new LinkedHashMap<String, String>();
            byTool.put(t, m);
        }
        if (!m.containsKey(a)) m.put(a, note == null ? "" : note.trim());
    }

    /** 全部 op 名（字典序）。 */
    public synchronized List<String> all() {
        List<String> l = new ArrayList<String>();
        for (Map.Entry<String, LinkedHashMap<String, String>> e : byTool.entrySet()) {
            for (String a : e.getValue().keySet()) l.add(op(e.getKey(), a));
        }
        Collections.sort(l);
        return l;
    }

    /** 全部工具名（字典序）。 */
    public synchronized List<String> tools() {
        List<String> l = new ArrayList<String>(byTool.keySet());
        Collections.sort(l);
        return l;
    }

    /**
     * 一个工具声明的全部 op；<b>没声明过就退化成工具名本身</b>
     * （老的 {@code airun} 式技能不需要为了能被管控而改写）。
     */
    public synchronized List<String> of(String tool) {
        String t = tool == null ? "" : tool.trim();
        List<String> l = new ArrayList<String>();
        if (t.isEmpty()) return l;
        LinkedHashMap<String, String> m = byTool.get(t);
        if (m == null || m.isEmpty()) {
            l.add(t);
            return l;
        }
        for (String a : m.keySet()) l.add(op(t, a));
        return l;
    }

    /** op 的一句话说明（没声明过返回空串）。 */
    public synchronized String noteOf(String op) {
        String t = toolOf(op);
        LinkedHashMap<String, String> m = byTool.get(t);
        if (m == null) return "";
        String a = actionOf(op);
        String n = m.get(a);
        return n == null ? "" : n;
    }

    /** 全部 op → 中文释义（写表时给每一行带上，人一眼就能看懂）。 */
    public synchronized Map<String, String> notesMap() {
        Map<String, String> m = new LinkedHashMap<String, String>();
        for (String op : all()) m.put(op, noteOf(op));
        return m;
    }

    /** 工具名 → "这把工具是干什么的"（写进表里的分组注释）。 */
    private final Map<String, String> toolNotes = new LinkedHashMap<String, String>();

    /** 登记一把工具的说明（重复登记只保留第一条）。 */
    public synchronized void declareTool(String tool, String note) {
        String t = tool == null ? "" : tool.trim();
        if (t.isEmpty()) return;
        String n = note == null ? "" : note.trim();
        if (n.isEmpty()) return;
        if (!toolNotes.containsKey(t)) toolNotes.put(t, n);
    }

    @Override
    public void tool(String tool, String note) { declareTool(tool, note); }

    /** 工具名 → 说明（没有就空串）。 */
    public synchronized String toolNoteOf(String tool) {
        String n = toolNotes.get(tool == null ? "" : tool.trim());
        return n == null ? "" : n;
    }

    /** 全部工具说明的快照。 */
    public synchronized Map<String, String> toolNotes() {
        return new LinkedHashMap<String, String>(toolNotes);
    }

    /** 这个 op 是不是清单里的（清单外的 op 不会命中任何账本条目，一律未授权）。 */
    public synchronized boolean known(String op) {
        if (op == null) return false;
        String t = toolOf(op);
        LinkedHashMap<String, String> m = byTool.get(t);
        if (m == null) return false;
        String a = actionOf(op);
        if (m.containsKey(a)) return true;
        // 调用点写的是工具名（技能级判定），而清单里是动作级 —— 也算认识
        return a.isEmpty() && !m.isEmpty();
    }
}
