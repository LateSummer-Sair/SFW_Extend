package sair.v4.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import sair.v4.kit.Out;

/**
 * {@link Libs} 的默认实现：<b>一张表只能有一个主人</b>。
 *
 * <p>读多写少：注册只在装配期与插件装载期发生（单线程），运行期每一轮都在读 ——
 * 所以读路径走不可变快照（{@link #all()} 不加锁），写路径上锁并重建快照。</p>
 *
 * <h3>冲突怎么办</h3>
 * <p>表名或中文别名被占用时 {@link #register} 返回 {@code false}，<b>不覆盖</b>：
 * 两张表抢一个名字一定是 bug（列定义会互相打架，数据会被另一套字段解释），
 * 宁可让后来者失败也不能悄悄换掉先来者。调用方（插件装载流程）据此把该插件标成失败并打一行日志。</p>
 *
 * <h3>唯一的例外：同一个主人重新声明自己那张表</h3>
 * <p>插件重扫（{@code skill reload} / 改了 .java 再扫）一定会再声明一次同一个表名 ——
 * 那不是"抢名字"，是<b>更新</b>：原地替换成新声明（列/别名/索引按新声明走，注册顺序不变），
 * 返回 {@code true}。否则每次重扫插件都会被自己上一次的声明判成冲突而整只失败。
 * 别人（不同 owner）来抢同一个名字仍然一律失败。</p>
 */
public final class LibRegistry implements Libs {

    private final Out out;
    /** 注册顺序（环境表在前）。 */
    private final List<LibSpec> order = new ArrayList<LibSpec>();
    /** 键（表名 + 全部别名，一律小写）→ 声明。 */
    private final Map<String, LibSpec> byKey = new LinkedHashMap<String, LibSpec>();
    /** 读路径快照（volatile 换引用，不加锁读）。 */
    private volatile List<LibSpec> snapshot = Collections.emptyList();

    public LibRegistry(Out out) {
        this.out = out;
    }

    @Override
    public synchronized boolean register(LibSpec spec) {
        if (spec == null || spec.table == null || spec.table.trim().isEmpty()) return false;
        String t = spec.table.trim().toLowerCase();
        LibSpec old = byKey.get(t);
        if (old != null && !sameOwner(old, spec)) return false;      // 别人抢同一个表名 = 失败
        // 别名不能撞"别的表"（自己那张表的老别名不算撞车：那正是重声明）
        for (String a : spec.alias) {
            if (a == null || a.trim().isEmpty()) continue;
            LibSpec other = byKey.get(a.trim().toLowerCase());
            if (other != null && other != old) return false;
        }
        if (old != null) {                                          // 同一主人重新声明 = 原地更新
            byKey.remove(t);
            for (String a : old.alias) {
                if (a == null || a.trim().isEmpty()) continue;
                byKey.remove(a.trim().toLowerCase());
            }
            int i = order.indexOf(old);
            if (i >= 0) order.set(i, spec); else order.add(spec);
        } else {
            order.add(spec);
        }
        put(t, spec);
        return true;
    }

    /** 新声明和已有声明是同一个主人吗（基板自己的库 owner 为空串，不算"主人可认领"）。 */
    private static boolean sameOwner(LibSpec old, LibSpec spec) {
        return old != null && spec != null
                && !spec.owner.isEmpty() && spec.owner.equals(old.owner);
    }

    /** 落键 + 换读快照（键 = 表名 + 全部别名，一律小写）。 */
    private void put(String tableKey, LibSpec spec) {
        byKey.put(tableKey, spec);
        for (String a : spec.alias) {
            if (a == null || a.trim().isEmpty()) continue;
            byKey.put(a.trim().toLowerCase(), spec);
        }
        snapshot = Collections.unmodifiableList(new ArrayList<LibSpec>(order));
    }

    /** 批量注册（基板自己的环境表用）；返回成功条数。 */
    public synchronized int registerAll(List<LibSpec> specs) {
        int n = 0;
        for (LibSpec s : specs) if (register(s)) n++;
        return n;
    }

    @Override
    public LibSpec get(String tableOrAlias) {
        if (tableOrAlias == null) return null;
        String k = tableOrAlias.trim().toLowerCase();
        if (k.isEmpty()) return null;
        return byKey.get(k);
    }

    @Override
    public List<LibSpec> all() { return snapshot; }

    @Override
    public List<String> tables() {
        List<String> out = new ArrayList<String>();
        for (LibSpec s : snapshot) out.add(s.table);
        return out;
    }

    @Override
    public List<LibSpec> env() {
        List<LibSpec> out = new ArrayList<LibSpec>();
        for (LibSpec s : snapshot) if (s.owner.isEmpty()) out.add(s);
        return out;
    }

    @Override
    public int size() { return snapshot.size(); }

    /** 冲突诊断（谁被谁占了）：注册失败时打一行，别让人对着"注册不上"猜。 */
    public void warnConflict(LibSpec spec) {
        if (out == null || spec == null) return;
        LibSpec old = get(spec.table);
        out.warn("[store] 库名冲突：'" + spec.table + "' 已被 "
                + (old == null ? "?" : (old.owner.isEmpty() ? "基板" : old.owner + " 插件")) + " 占用，"
                + (spec.owner.isEmpty() ? "基板" : spec.owner + " 插件") + " 这次声明被拒");
    }
}
