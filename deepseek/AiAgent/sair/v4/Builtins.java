package sair.v4;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Map;

import sair.v4.agent.Agent;
import sair.v4.ai.DeepSeek;
import sair.v4.ai.Msg;
import sair.v4.ai.Res;
import sair.v4.auth.Acl;
import sair.v4.auth.Auth;
import sair.v4.auth.Bits;
import sair.v4.auth.Caller;
import sair.v4.ctx.Turn;
import sair.v4.hot.DynCode;
import sair.v4.hot.Sk;
import sair.v4.hot.Skills;
import sair.v4.kit.Fs;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;
import sair.v4.prompt.Inject;
import sair.v4.prompt.Prompts;

import sair.v4.store.Store;
import sair.v4.tool.Registry;
import sair.v4.tool.Tool;

/**
 * 基板自带的工具面（全部是<b>能力形状</b>，没有业务形状）。
 *
 * <pre>
 *   store / store_write / store_admin     六库通用接口              （①）
 *   skill / skill_write                   技能库读写与重载          （②）
 *   exec                                  动态执行 Java / 系统命令  （②）
 *   prompt / prompt_write                 提示词加载与注入          （③）
 *   agent                                 多 Agent 调度与清单表     （④）
 *   ctx                                   上下文注入                （⑤）
 *   perm                                  权限账本（授权/撤销/查询/验算）（⑥）
 *   model                                 直连模型                  （⑦）
 *   napcat                                OneBot 动作               （⑧）
 *   console                               控制台输出                （⑨）
 *   alarm                                 定时唤醒                  （④）
 * </pre>
 *
 * 业务形状的能力（发图片、禁言、天气、搜索、审核、主动发言……）一律由 data/skills 提供。
 * 权限：没有"工具级档位"这回事（旧档位表 {@code PermTable} 已随 P9b-2 删除）——
 * 判据是资源 ACL：要碰资源那一刻判 {@code Res.*} 的位（{@code Host.need*}/{@code needRes}），
 * 所以这里每个工具都在实现里自己判它要碰的资源。
 */
public final class Builtins {

    private Builtins() {}

    /**
     * 图片直传闸门（甲方急令，T12-R3c）：{@code model} 工具的 {@code op=vision} 是<b>唯一</b>能把图
     * 直接塞进请求的旁路 —— 入站那条在 {@link sair.v4.ctx.CtxBuild#user(sair.v4.ctx.Turn, String)}
     * 里另有 mode 闸门（三道闸的第一道）。
     *
     * <p>判据<b>复用</b> {@link sair.v4.ctx.CtxBuild#visionMode(String)}（同一个算法、同一份配置、
     * 同一个 model 来源 {@code selfModel(t)}），所以"这条工具能不能直传"与"入站能不能挂图"<b>永远同源</b>；
     * <b>不在这里造第二套判定</b> —— 造了就会两处漂移，而这条闸门的全部意义就是"没有第二条路"。</p>
     *
     * <p><b>判不出来也拒绝（fail-closed）</b>：{@code boot.ctx()} 为 {@code null}（装配早期 / 降级）
     * 或任何异常 ⇒ 返回"判不出档位"的拒绝文案。<b>放行 = 漏洞复活</b>，所以这里既不许放行、也不许抛。</p>
     *
     * @return {@code null} = 放行（只有 {@code mode == native}）；非 {@code null} = 直接作为工具结果返回的拒绝文案
     */
    private static String imageDirectDeny(Boot boot, Turn t) {
        try {
            sair.v4.ctx.CtxBuild ctx = boot == null ? null : boot.ctx();
            if (ctx == null) {
                return "图片直传已被策略关闭（判不出档位：上下文装配未就绪）：请改用看图手（look）看这条消息里的图。";
            }
            String mode = ctx.visionMode(ctx.selfModel(t));
            if (sair.v4.ctx.CtxBuild.MODE_NATIVE.equals(mode)) return null;
            return "图片直传已被策略关闭（本轮 models.mode=" + mode + "，非 native）：请改用看图手（look）看这条消息里的图。";
        } catch (Throwable e) {
            return "图片直传已被策略关闭（判不出档位：" + e.getClass().getSimpleName()
                    + "）：请改用看图手（look）看这条消息里的图。";
        }
    }

    public static void register(final Boot boot) {
        final Conf conf = boot.conf();
        final Out out = boot.out();
        final Registry reg = boot.registry();
        final Store store = boot.store();
        // "有哪些库"问注册表：基板环境库（dialog/grouplog）+ 插件装载时声明的业务库。
        // 插件没装 = 少一个库（下面那句人话会把当前可用的列出来），不是异常。
        final sair.v4.store.Libs libs = boot.libs();
        final Skills skills = boot.skills();
        final Prompts prompts = boot.prompts();
        final Agent agent = boot.agent();
        final Auth auth = boot.auth();
        final Tick tick = boot.tick();
        final DynCode dyn = boot.dyn();

        // ---------------- ① 六库：读 ----------------
        reg.add(Tool.of("store")
                .desc("读写基板六库：lib 取 memory 长期记忆/note 知识笔记/dialog 对话历史/grouplog 群聊历史/sticker 表情包/pref 偏好设定。")
                .returns("查询结果（JSON 文本）")
                .params(schemaStoreRead(libs))
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String lib = J.s(args, "lib", "");
                        String op = J.s(args, "op", "list").toLowerCase();
                        sair.v4.store.LibSpec L = libs.get(lib);
                        if (L == null) return "未知的库：" + lib + "（可用：" + libNames(libs) + "）";
                        // ACL（C 类 R 位）：读库（list/get/search/count）要 R。主体 = 当轮触发者，拿不到按 SYSTEM。
                        String aclDeny = needRes(auth, conf, sair.v4.auth.Res.db(lib), 'R');
                        if (aclDeny != null) return aclDeny;
                        Caller me = t == null ? null : t.caller();
                        boolean master = me != null && me.master();
                        String deny = scopedDeny(lib, me, false);
                        if (deny != null) return deny;
                        JsonObject filter = master ? J.sub(args, "filter") : constrain(lib, J.sub(args, "filter"), me);
                        int limit = J.i(args, "limit", 20);
                        if ("get".equals(op)) {
                            JsonObject o = store.get(lib, J.l(args, "id", 0));
                            if (o == null) return "没有这一条";
                            if (!master && !inScope(lib, o, me)) return Acl.DENY_PREFIX + "这条记录不属于你的会话";
                            return J.json(o);
                        }
                        if ("count".equals(op)) {
                            return String.valueOf(store.count(lib, filter));
                        }
                        if ("search".equals(op)) {
                            List<JsonObject> hits = store.search(lib, J.s(args, "query", ""), limit);
                            if (!master) hits = keepInScope(lib, hits, me);
                            return J.json(hits);
                        }
                        return J.json(store.list(lib, filter, limit, J.s(args, "order", "ts desc")));
                    }
                }));

        // ---------------- ① 六库：写 ----------------
        reg.add(Tool.of("store_write")
                .desc("写基板六库（put/update/delete）：字段名按库定义给，写错会被忽略。")
                .returns("新 id / 影响行数")
                .params(schemaStoreWrite())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String lib = J.s(args, "lib", "");
                        String op = J.s(args, "op", "put").toLowerCase();
                        if (libs.get(lib) == null) return "未知的库：" + lib + "（可用：" + libNames(libs) + "）";
                        // ACL（C 类 W 位）：写库（put/update/delete）要 W。
                        String aclDeny = needRes(auth, conf, sair.v4.auth.Res.db(lib), 'W');
                        if (aclDeny != null) return aclDeny;
                        Caller me = t == null ? null : t.caller();
                        boolean master = me != null && me.master();
                        String deny = scopedDeny(lib, me, true);
                        if (deny != null) return deny;
                        // 归属硬判（与权限位无关）：C 类按类默认分配现在是 R（非主人只读），
                        // 非主人本来就没有 W；这条硬判是为"主人哪天按人放开 C 类的 W"留的
                        // —— 原始写口不能靠默认位兜底，必须自己判归属。
                        // ① note / kv：与"哪一行"无关，非主人一律拒（连存在性都不必问）；
                        String ownDeny = writeClosedDeny(lib, me);
                        if (ownDeny != null) return ownDeny;
                        if ("update".equals(op)) {
                            long id = J.l(args, "id", 0);
                            JsonObject old = store.get(lib, id);
                            if (old == null) return "更新失败（id 不存在？）";
                            if (!master && !inScope(lib, old, me)) return Acl.DENY_PREFIX + "这条记录不属于你的会话";
                            // ② memory 专属：非主人只动得了自己那一行（别人的行 / global 行一律拒）。
                            //    **只在 lib = memory 时生效** —— dialog/grouplog 的归属由上面的 inScope 与
                            //    constrainRow 管，拿 memory 的尺子去量它们会把"写自己的会话"误拒。
                            if ("memory".equals(lib)) {
                                ownDeny = writeMemoryDeny(old, me);
                                if (ownDeny != null) return ownDeny;
                            }
                            JsonObject patch = J.sub(args, "patch");
                            // 作用域越权硬判（在 constrainRow 的静默改写**之前**）：非主人显式写
                            // scope=global 一律拒，与技能层同口径（不再"技能拒、原始写口静默降级"）
                            String scopeDeny = writeScopeDeny(lib, patch, me);
                            if (scopeDeny != null) return scopeDeny;
                            if (!master) patch = constrainRow(lib, patch, me, null);
                            boolean ok = store.update(lib, id, patch);
                            return ok ? "已更新" : "更新失败";
                        }
                        if ("delete".equals(op)) {
                            long id = J.l(args, "id", 0);
                            JsonObject old = store.get(lib, id);
                            if (old == null) return "删除失败（id 不存在？）";
                            if (!master && !inScope(lib, old, me)) return Acl.DENY_PREFIX + "这条记录不属于你的会话";
                            // ② 同 update：memory 专属的行级归属判
                            if ("memory".equals(lib)) {
                                ownDeny = writeMemoryDeny(old, me);
                                if (ownDeny != null) return ownDeny;
                            }
                            boolean ok = store.delete(lib, id);
                            return ok ? "已删除" : "删除失败";
                        }
                        JsonObject row = J.sub(args, "row");
                        if (row == null) row = new JsonObject();
                        // 作用域越权硬判（在 constrainRow 的静默改写**之前**）：非主人显式写
                        // scope=global 一律拒（原来会被静默改写成 user，导致下面 writeMemoryDeny 的
                        // "global 只有主人能写"分支永远走不到）；MASTER/SYSTEM 不受影响
                        String scopeDeny = writeScopeDeny(lib, row, me);
                        if (scopeDeny != null) return scopeDeny;
                        if (!master) {
                            row = constrainRow(lib, row, me, null);
                            if (row == null) return Acl.DENY_PREFIX + "不能往 " + lib + " 库写非本会话的数据";
                        }
                        // ② 同 update/delete：memory 专属的行级归属判（其它库到这里已经由
                        //    scopedDeny + constrainRow 收口；note/kv 在上面就被 writeClosedDeny 拒了）
                        if ("memory".equals(lib)) {
                            ownDeny = writeMemoryDeny(row, me);
                            if (ownDeny != null) return ownDeny;
                        }
                        long id = store.put(lib, row);
                        return id > 0 ? ("已写入，id=" + id) : "写入失败";
                    }
                }));

        // ---------------- ① 六库：管理（仅主人） ----------------
        reg.add(Tool.of("store_admin")
                .desc("六库管理：stat 统计/maintain 维护清理/optimize 合并全文索引段/vacuum 回收空闲页/export 导出 JSONL/import 导入覆盖。")
                .returns("操作结果")
                .params(schemaStoreAdmin())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String op = J.s(args, "op", "stat").toLowerCase();
                        if ("stat".equals(op)) {
                            // ACL：store_admin 管理面（C 类）。stat 读 R；其余写 W。
                            String aclDeny = needRes(auth, conf, sair.v4.auth.Res.mem("admin"), 'R');
                            if (aclDeny != null) return aclDeny;
                            return J.json(store.stat());
                        }
                        if ("maintain".equals(op)) {
                            String aclDeny = needRes(auth, conf, sair.v4.auth.Res.mem("admin"), 'W');
                            if (aclDeny != null) return aclDeny;
                            // 整库维护会删掉<b>所有人</b>的超龄行（没有"逐行归属"可言）：非主人一律拒
                            Caller me = t == null ? null : t.caller();
                            String ownDeny = writeMemoryDeny(null, me);
                            if (ownDeny != null) return ownDeny;
                            // 三档保留期都从配置取默认（keepDays 只管记忆/笔记类）：
                            // 模型不能靠"不给参数"就拿到一个更激进的默认值把聊天记录扫了
                            return J.json(store.maintain(
                                    J.i(args, "keepDays", conf.keepDays()),
                                    J.i(args, "dialogKeepDays", conf.dialogKeepDays()),
                                    J.i(args, "grouplogKeepDays", conf.grouplogKeepDays()),
                                    J.i(args, "maxLow", conf.maxLowImportance())));
                        }
                        String lib = J.s(args, "lib", "");
                        String path = J.s(args, "path", "");
                        File f = Str.blank(path) ? new File(conf.filesDir(), lib + "-" + Str.stamp() + ".jsonl") : new File(path);
                        if ("optimize".equals(op)) {
                            String aclDeny = needRes(auth, conf, sair.v4.auth.Res.db(lib), 'W');
                            if (aclDeny != null) return aclDeny;
                            // 整库维护动作（合并 FTS 段）：非主人一律拒（不指望位表兜底 —— 主人可以给 C 类开 W）
                            String ownDeny = writeAdminDeny("维护", t == null ? null : t.caller());
                            if (ownDeny != null) return ownDeny;
                            // 批量删行之后合并 FTS 段（删除标记这时才真正丢掉；还要 VACUUM 才还盘）
                            return store.optimizeFts(lib)
                                    ? ("已合并 " + lib + " 的全文索引段") : "合并失败（库名不对 / 该库没有索引）";
                        }
                        if ("vacuum".equals(op)) {
                            String aclDeny = needRes(auth, conf, sair.v4.auth.Res.mem("admin"), 'W');
                            if (aclDeny != null) return aclDeny;
                            // 整库维护动作（回收空闲页）：非主人一律拒
                            String ownDeny = writeAdminDeny("维护", t == null ? null : t.caller());
                            if (ownDeny != null) return ownDeny;
                            store.db().exec("VACUUM");
                            return "VACUUM 已执行（回收空闲页；期间独占数据库）";
                        }
                        if ("export".equals(op)) {
                            String aclDeny = needRes(auth, conf, sair.v4.auth.Res.db(lib), 'W');
                            if (aclDeny != null) return aclDeny;
                            // 整库导出会把跨用户/跨群数据一次性拿走：非主人一律拒
                            // （这一段在 <b>写出文件之前</b>，被拒时一个字节都不会落盘）
                            String ownDeny = writeAdminDeny("导出", t == null ? null : t.caller());
                            if (ownDeny != null) return ownDeny;
                            return store.exportJson(lib, f) ? ("已导出 " + f.getAbsolutePath()) : "导出失败";
                        }
                        if ("import".equals(op)) {
                            String aclDeny = needRes(auth, conf, sair.v4.auth.Res.db(lib), 'W');
                            if (aclDeny != null) return aclDeny;
                            // 整库覆盖也是"任意行"的写口（且没有逐行归属可验）：与 store_write 同一把尺子
                            Caller me = t == null ? null : t.caller();
                            String ownDeny = writeClosedDeny(lib, me);
                            if (ownDeny == null && "memory".equals(lib)) ownDeny = writeMemoryDeny(null, me);
                            if (ownDeny != null) return ownDeny;
                            int n = store.importJson(lib, f);
                            return n >= 0 ? ("已导入 " + n + " 条") : "导入失败";
                        }
                        return "未知操作：" + op;
                    }
                }));

        // ---------------- ② 技能库：读 ----------------
        reg.add(Tool.of("skill")
                .desc("技能库（外挂层）：list 清单/read 说明书/validate 体检/search 按名找。技能决定你有哪些额外工具与钩子。")
                .returns("技能清单/说明书正文")
                .params(schemaSkillRead())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String op = J.s(args, "op", "list").toLowerCase();
                        if ("validate".equals(op)) {
                            List<String> p = skills.validate();
                            return p.isEmpty() ? "技能库无问题（共 " + skills.size() + " 个）" : Str.join(p, "\n");
                        }
                        if ("read".equals(op)) {
                            Sk sk = skills.get(J.s(args, "name", ""));
                            if (sk == null) return "没有这个技能";
                            String body = sk.doc == null ? "" : sk.doc;
                            return "技能 " + sk.name + "\n" + J.json(sk.toJson()) + "\n\n" + body;
                        }
                        JsonArray arr = new JsonArray();
                        for (Sk sk : skills.list()) {
                            if (Str.has(J.s(args, "name", "")) && !sk.name.contains(J.s(args, "name", ""))) continue;
                            arr.add(sk.toJson());
                        }
                        return J.json(arr);
                    }
                }));

        // ---------------- ② 技能库：写/重载（仅主人） ----------------
        reg.add(Tool.of("skill_write")
                .desc("技能库管理（仅主人）：add/update（写 md 与 java 后需 reload）/delete/reload 重扫/read_source；"
                        + "draft/drafts/promote 走草稿区（先草稿，没问题再 promote 进技能库）。")
                .returns("操作结果")
                .params(schemaSkillWrite())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String op = J.s(args, "op", "").toLowerCase();
                        String name = Str.safeName(J.s(args, "name", ""));
                        // ── 结构性依赖收口（P10a）：<b>这把工具自己先判一次落点</b> ──
                        // 权限注册表不再做工具级判定（见 tool/Registry.call 的注释），所以"谁能让它写技能库"
                        // 曾经只靠<b>调用点自觉</b>（例如 {@code 经验蒸馏.promote} 里那句
                        // {@code h.needPath(h.conf().skillsDir(), 'W')}）—— 任何新入口忘了判，就等于开了
                        // "写源码 + 热加载 = 代码执行"的后门。这里把判定收进工具本身：
                        // 动手（写盘 / 编译 / 热加载）之前，先对这次要落的路径判 W 位；
                        // 主体取法与其它 op 一致（{@code Ctx.caller()}，取不到就拒，绝不回落 SYSTEM）。
                        // 两个落点各自判：技能库目录（本工具的主落点）+ 草稿区（draft/promote 会写它）。
                        List<File> targets = new ArrayList<File>();
                        targets.add(conf.skillsDir());
                        if ("draft".equals(op) || "promote".equals(op)) targets.add(conf.draftsDir());
                        String selfDeny = skillWriteSelfDeny(auth, conf, out, targets);
                        if (selfDeny != null) return selfDeny;
                        if ("reload".equals(op)) {
                            skills.scan();
                            return "已重扫，当前 " + skills.size() + " 个技能（工具 " + skills.toolCount() + " 个）"
                                    + TOOL_NAME_RULE;
                        }
                        if ("drafts".equals(op)) return draftsJson(conf);
                        if (Str.blank(name)) return "需要 name" + TOOL_NAME_RULE;
                        File dir = new File(conf.skillsDir(), name);
                        if ("delete".equals(op)) {
                            Fs.deleteRec(dir);
                            skills.scan();
                            return "已删除技能 " + name;
                        }
                        if ("read_source".equals(op)) {
                            List<File> src = Fs.walk(dir, ".java", 2);
                            if (src.isEmpty()) return "该技能没有 java 源码";
                            StringBuilder sb = new StringBuilder();
                            for (File f : src) sb.append("// ").append(f.getName()).append("\n").append(Fs.read(f, "")).append("\n");
                            return sb.toString();
                        }
                        String md = J.s(args, "md", "");
                        String java = J.s(args, "java", "");
                        // ---- 草稿区：不扫描、不监听，草稿永远不进工具表 ----
                        if ("draft".equals(op)) {
                            if (Str.blank(md) && Str.blank(java)) return "需要 md 或 java 内容" + TOOL_NAME_RULE;
                            File d = new File(conf.draftsDir(), name);
                            String err = writeSkillFiles(d, name, md, java);
                            if (err != null) return err;
                            return "已写入草稿：" + name + "（还在 " + conf.draftsRel() + "/，没进工具表；"
                                    + "确认没问题后用 op=promote 搬进技能库）" + TOOL_NAME_RULE;
                        }
                        if ("promote".equals(op)) {
                            File d = new File(conf.draftsDir(), name);
                            if (!d.isDirectory()) return "没有这个草稿：" + name + "（用 op=drafts 看有哪些）";
                            if (dir.exists()) {
                                // 同名就是冲突：不按来源让步（没有"手写优先"这种东西）、不静默覆盖，直接说清并给出路
                                return "同名技能已存在，未覆盖：" + name + "（线上 " + conf.skillsRel() + "/" + name
                                        + "）。要么先 op=delete 删掉线上的，要么用 op=update 明确覆盖内容；草稿还原样留在 "
                                        + conf.draftsRel() + "/" + name + "。";
                            }
                            Fs.mkdirs(dir);
                            int n = 0;
                            String base = d.getAbsolutePath() + File.separator;
                            for (File f : Fs.walk(d, null, 3)) {
                                String abs = f.getAbsolutePath();
                                if (!abs.startsWith(base)) continue;
                                File to = new File(dir, abs.substring(base.length()));
                                Fs.mkdirs(to.getParentFile());
                                if (Fs.copy(f, to)) n++;
                            }
                            Fs.deleteRec(d);
                            skills.scan();
                            Sk sk = skills.get(name);
                            if (sk != null && sk.state == Sk.State.ERROR) {
                                return "已搬进技能库但加载失败：" + sk.error + TOOL_NAME_RULE;
                            }
                            return "已提升 " + name + "（" + n + " 个文件，" + conf.draftsRel() + " 里的草稿已删除），"
                                    + (sk != null && sk.providesTool() ? ("工具 " + sk.tool + " 已生效") : "已加载")
                                    + mappedNote(sk) + TOOL_NAME_RULE;
                        }
                        if (!"add".equals(op) && !"update".equals(op)) {
                            return "未知操作：" + op + "（可用 add/update/delete/reload/read_source/draft/drafts/promote）";
                        }
                        if (Str.blank(md) && Str.blank(java)) return "需要 md 或 java 内容" + TOOL_NAME_RULE;
                        Fs.mkdirs(dir);
                        String err = writeSkillFiles(dir, name, md, java);
                        if (err != null) return err;
                        skills.scan();
                        Sk sk = skills.get(name);
                        if (sk != null && sk.state == Sk.State.ERROR) return "已写入但加载失败：" + sk.error + TOOL_NAME_RULE;
                        return "已写入并加载：" + name + (sk != null && sk.providesTool() ? ("，工具 " + sk.tool) : "")
                                + mappedNote(sk) + TOOL_NAME_RULE;
                    }
                }));

        // ---------------- ② 动态执行（仅主人） ----------------
        reg.add(Tool.of("exec")
                .desc("动态执行（仅主人）：kind=java 编译运行 Java 源码；kind=cmd 执行系统命令。")
                .returns("执行输出")
                .params(schemaExec())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String kind = J.s(args, "kind", "java").toLowerCase();
                        if ("cmd".equals(kind) || "shell".equals(kind)) {
                            return dyn.runCmd(J.s(args, "cmd", ""), J.l(args, "timeout", 30000));
                        }
                        List<String> a = J.strings(args, "args");
                        return dyn.runJava(J.s(args, "code", ""), a.toArray(new String[0]));
                    }
                }));

        // ---------------- ③ 提示词：读 ----------------
        reg.add(Tool.of("prompt")
                .desc("提示词（基板只带一份初始身份提示词）：show 初始提示词/list 还有哪些提示词文件/read 读指定文件/sections 分节名。")
                .returns("提示词正文或清单")
                .params(schemaPromptRead())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String op = J.s(args, "op", "show").toLowerCase();
                        if ("list".equals(op)) return J.json(prompts.files());
                        if ("sections".equals(op)) return J.json(prompts.identity().sectionNames());
                        if ("read".equals(op)) {
                            String txt = prompts.read(J.s(args, "name", ""));
                            return txt == null ? "没有这份提示词" : txt;
                        }
                        return prompts.identity().raw();
                    }
                }));

        // ---------------- ③ 提示词：注入/写（仅主人） ----------------
        reg.add(Tool.of("prompt_write")
                .desc("提示词注入与写入（仅主人）：load 载入某份提示词并注入/inject 注入一段文本/write 新建或覆盖提示词文件/clear 移除注入/reload。"
                        + "slot 取 system 系统提示词/context 每轮上下文/tool 工具说明/arg 用户消息前。scope 省略=全局，否则是会话键。")
                .returns("操作结果")
                .params(schemaPromptWrite())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String op = J.s(args, "op", "inject").toLowerCase();
                        String slot = J.s(args, "slot", Inject.SYSTEM);
                        String scope = J.s(args, "scope", Inject.GLOBAL);
                        String key = J.s(args, "key", "manual");
                        if ("reload".equals(op)) {
                            prompts.reload();
                            return "已重载初始提示词（" + prompts.identity().raw().length() + " 字符）";
                        }
                        if ("clear".equals(op)) {
                            boolean ok = prompts.inject().remove(scope, slot, key);
                            return ok ? "已移除" : "没有这条注入";
                        }
                        if ("write".equals(op)) {
                            return prompts.write(J.s(args, "name", ""), J.s(args, "text", "")) ? "已写入" : "写入失败";
                        }
                        if ("load".equals(op)) {
                            String name = J.s(args, "name", "");
                            String txt = prompts.read(name);
                            if (txt == null) return "没有这份提示词：" + name;
                            prompts.inject().put(scope, slot, "file:" + name, txt, "prompt:" + name);
                            return "已把 " + name + " 注入到 " + slot + "（" + txt.length() + " 字符）";
                        }
                        String text = J.s(args, "text", "");
                        if (Str.blank(text)) return "需要 text";
                        prompts.inject().put(scope, slot, key, text, "manual");
                        return "已注入到 " + slot + "（" + text.length() + " 字符）";
                    }
                }));

        // ---------------- ④ Agent 调度 ----------------
        reg.add(Tool.of("agent")
                .desc("多 Agent：spawn 派子 Agent 干活（可指定它用哪些工具）/list 清单表/status 单个任务。"
                        + "子 Agent 结论自包含回传；async=true 立刻返回任务票。"
                        + "结论默认只回给你（用户看不到）；只有当这一单本来就是\"替机器人说一句话\"时才用 speak=true，"
                        + "那时它写成什么就发到当前会话；内部收尾（任务号/message_id/子 Agent 原文）不写进对外内容。"
                        + "★子 Agent 是最小单位：不能再派子 Agent；你可同时派多个，它们同级（都由你分配和建立）。")
                .returns("任务票/清单")
                .params(schemaAgent())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        // 闸②（D46★2）：子 Agent 是最小单位 —— 它在自己的回合里调 agent 工具，
                        // **所有 op 一律拒**（spawn/list/status/stop 全拒）。放在 ACL 判定之前：
                        // 这不是"你有没有权限"的问题，是"这个身份根本不许有这扇门"。
                        // 只靠闸①（派活处过滤工具表）不够：模型仍可能凭记忆喊这个名字，Registry 照旧会找到实现。
                        if (t != null && t.isSubagent()) {
                            return "[agent] 子 Agent 是最小单位：不能再派子 Agent（同级，由主 Agent 统一分配）";
                        }
                        Caller me = t == null ? null : t.caller();
                        boolean master = me != null && me.master();
                        String op = J.s(args, "op", "list").toLowerCase();
                        // ACL：agent = 一把工具，它的<b>入口</b>是 T 类资源。spawn/stop 执行 X；list/status 读 R。
                        // （T 类对 ALLUSER 默认一位都没有 ⇒ 非主人连 list 都被拦；要放开由主人写 T 类例外条目。）
                        String aclDeny = needRes(auth, conf, sair.v4.auth.Res.tool("agent"),
                                ("spawn".equals(op) || "stop".equals(op)) ? 'X' : 'R');
                        if (aclDeny != null) return aclDeny;
                        if ("spawn".equals(op)) {
                            String task = J.s(args, "task", "");
                            if (Str.blank(task)) return "需要 task";
                            List<String> tools = J.strings(args, "tools");
                            boolean async = J.b(args, "async", false);
                            // brief = 子 Agent 的变化部分提示词：由主 Agent（你）现场生成
                            String brief = J.s(args, "brief", "");
                            String model = J.s(args, "model", "");   // 视觉兜底：指定会看图的模型
                            // speak=true（只在 async=true 时有意义）= 这一单就是"替机器人说一句话"：
                            // 子 Agent 写成什么就由基板发到当前会话（它自己发过、或回 <silent> 时不重复发）。
                            // 默认 false = 内部材料，用户看不到；同步派发时结论只回给模型，由它自己决定怎么说。
                            boolean speak = J.b(args, "speak", false);
                            return J.json(agent.spawn(me, t == null ? null : t.sink(), task, tools, async, brief,
                                    model, speak));
                        }
                        if ("status".equals(op)) {
                            long id = J.l(args, "id", 0);
                            for (JsonObject o : agent.tasks(null, master ? null : (me == null ? "" : me.session()))) {
                                if (J.l(o, "id", 0) == id) return J.json(o);
                            }
                            return "没有这个任务（或它不是你的）";
                        }
                        if ("stop".equals(op)) {
                            // 旧档位判定 auth.check("agent.stop")（ROOT 档）已并入本工具顶部的 ACL 判定（stop → T 类 X 位）。
                            // 按会话停：主人停全部；非主人 / 不带 session 时只停自己那个会话的回合
                            // （多会话并行之后，"一个用户敲停"不能打断别人的回合）
                            String want = J.s(args, "session", "").trim();
                            boolean all = master && (want.isEmpty() || "*".equals(want));
                            int n = agent.stop(all ? null : (want.isEmpty() && me != null ? me.session() : want),
                                    master && all);
                            return "已请求中断 " + n + " 个在跑的回合"
                                    + (all ? "（主人：全部会话）" : "（只停目标会话）");
                        }
                        String status = J.s(args, "status", "").trim().toLowerCase();
                        if (Str.blank(status) || "all".equals(status)) status = null;
                        return J.json(agent.tasksJson(status, master ? null : (me == null ? "" : me.session())));
                    }
                }));

        // ---------------- ⑤ 上下文注入 ----------------
        reg.add(Tool.of("ctx")
                .desc("上下文注入：inject 按 slot 注入文本/list 当前注入项/clear 清空。"
                        + "slot 取 system/context/tool/arg；scope 省略=当前会话。")
                .returns("操作结果")
                .params(schemaCtx())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        Caller me = t == null ? null : t.caller();
                        boolean master = me != null && me.master();
                        String op = J.s(args, "op", "list").toLowerCase();
                        String scopeArg = J.s(args, "scope", "").trim();
                        String own = t == null ? Inject.GLOBAL : t.session();
                        String scope = own;
                        if (Str.has(scopeArg)) {
                            if (!master && !scopeArg.equals(own)) {
                                return Acl.DENY_PREFIX + "只能往自己的会话（" + own + "）注入";
                            }
                            if (master) scope = scopeArg;         // 主人可以指定任意作用域（含 *）
                        }
                        String slot = J.s(args, "slot", Inject.CONTEXT).trim();
                        if (!master && !Inject.CONTEXT.equals(slot)) {
                            return Acl.DENY_PREFIX + "只有主人能注入 " + slot + " 槽（普通会话只能注入 context）";
                        }
                        if ("inject".equals(op)) {
                            String text = J.s(args, "text", "");
                            if (Str.blank(text)) return "需要 text";
                            String key = J.s(args, "key", "ctx");
                            prompts.inject().put(scope, slot, key, text, "ctx");
                            return "已注入 " + slot + "（作用域 " + scope + "）";
                        }
                        if ("clear".equals(op)) {
                            if (!master && Str.has(scopeArg) && !scopeArg.equals(own)) {
                                return Acl.DENY_PREFIX + "只能清自己的注入";
                            }
                            prompts.inject().clearScope(master && Str.has(scopeArg) ? scopeArg : own);
                            return "已清空作用域 " + (master && Str.has(scopeArg) ? scopeArg : own) + " 的注入";
                        }
                        return J.json(prompts.inject().list(own));
                    }
                }));

        // ---------------- ⑥ perm：权限账本（授权 / 撤销 / 查询 / 验算；四个 op 全部仅主人） ----------------
        reg.add(Tool.of("perm")
                .desc(PERM_DESC)
                .returns("回执原文（条目 + 实际生效位 + 默认分配 + 是不是「例外：超出默认分配」）；"
                        + "acl 给清单或验算结果；whoami 给身份与五类有效位")
                .params(schemaPerm())
                // 示例写死在工具上 = 盖掉 tools-index.md 里那行老口径示例（它还在教 op=set/setlevel + 数字档位，
                // 留着会跟新口径打架）。tools-index.md 那一行由 S8 清理，清掉后这三行可以撤。
                .examples(java.util.Arrays.asList(PERM_EXAMPLES))
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        Caller c = t == null ? sair.v4.ctx.Ctx.caller() : t.caller();
                        return permTool(args, out, auth == null ? null : auth.acl(), c);
                    }
                }));

        // ---------------- ⑥b 自省：tools（找得到 / 看得懂 / 知道能不能用） ----------------
        //
        // 主人要的那条工作流：**知道该用什么工具 → 不知道就查 → 读说明书 → 验证行不行 → 行就做 /
        // 不行就直说 → 给结论**。前四步全靠这一把：清单（list）、按意图找（search）、
        // 完整说明书 + **以调用者现在的身份能不能用**（show）。
        // 边界（与别处一致）：只看 Registry.visible(c) 里的工具 —— 不给不该看的人暴露能力面；
        // 判定一律现算 auth.bits(c, Res.tool(名))，基板不缓存、不猜。
        reg.add(Tool.of("tools")
                .desc(TOOLS_DESC)
                .returns("工具清单 / 按意图匹配到的几把 / 一把工具的完整说明书（含参数表与"
                        + "「以你现在的身份能不能用」）")
                .params(schemaTools())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        Caller c = t == null ? sair.v4.ctx.Ctx.caller() : t.caller();
                        return toolsTool(args, out, auth, conf, reg, c);
                    }
                }));

        // ---------------- ⑥c 自省：config（"改哪儿"必须可查） ----------------
        //
        // 起因：改设置这件事以前只存在两种走法 —— 让她背 config.json 的键名，或者去撞控制台
        // 框架命令（真机事故就是这条：她连撞 5 次 console 被连续失败闸门掐停）。
        // 这一把把「键名 + 生效值 + 即时/需重启」一次给全；**写只有主人**。
        reg.add(Tool.of("config")
                .desc(CONFIG_DESC)
                .returns("配置清单（已知键 + 生效值 + 即时/需重启）/ 一个键的生效值与口径 / 改动回执")
                .params(schemaConfig())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        Caller c = t == null ? sair.v4.ctx.Ctx.caller() : t.caller();
                        return configTool(args, out, auth, conf, c);
                    }
                }));

        // ---------------- ⑦ 模型直连 ----------------
        reg.add(Tool.of("model")
                .desc("直连 DeepSeek：chat 问一句/vision 看图/balance 余额/models 模型清单/info 当前模型。"
                        + "边界：临时问一句、不要缓存时用我；带缓存与失败黑名单的专用看图工具是 vision。")
                .returns("模型回复文本或查询结果")
                .params(schemaModel())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String op = J.s(args, "op", "chat").toLowerCase();
                        // ACL：model 的入口是 <b>T 类</b>资源（工具已从 C 类拆出来）：chat/vision 出网 → X；
                        // balance/models/info 读信息 → R。T 类按类默认分配对 ALLUSER 是 NONE（一位都不给），
                        // 所以这条判定现在真能拦人；要按人放开就在 T 类上写例外条目。
                        boolean netOp = "chat".equals(op) || "vision".equals(op);
                        String aclDeny = needRes(auth, conf, sair.v4.auth.Res.tool("model"), netOp ? 'X' : 'R');
                        if (aclDeny != null) return aclDeny;
                        DeepSeek ai = boot.ai();
                        if ("info".equals(op)) {
                            JsonObject o = new JsonObject();
                            o.addProperty("configured", conf.model());
                            o.addProperty("resolved", conf.resolveModel());
                            o.addProperty("auto", conf.isAutoModel());
                            o.addProperty("url", conf.apiUrl());
                            o.addProperty("key_set", Str.has(conf.apiKey()));
                            return J.json(o);
                        }
                        if ("balance".equals(op)) {
                            JsonObject b = ai.balance();
                            return b == null ? "查询余额失败" : J.json(b);
                        }
                        if ("models".equals(op)) return J.json(ai.models());
                        JsonArray msgs = new JsonArray();
                        String sys = J.s(args, "system", "");
                        if (Str.has(sys)) msgs.add(Msg.system(sys));
                        List<String> images = J.strings(args, "images");
                        if ("vision".equals(op) && !images.isEmpty()) {
                            // ★图片直传收口（甲方急令，T12-R3c）：这里是**唯一**能把图直接塞进请求的旁路
                            // （入站那条已由 CtxBuild.user 的 mode 闸门挡着）。判据**复用同一个 visionMode**
                            // —— 与入站**同源**，不在这里造第二套判定。非 native（off/relay 皆然）一律**整条拒绝**：
                            // 不许静默、不许"挂了文本不挂图"的半挂中间态；判不出来（ctx 未就绪/异常）**也拒绝**。
                            String deny = imageDirectDeny(boot, t);
                            if (deny != null) return deny;
                            msgs.add(Msg.userWithImages(J.s(args, "text", ""), images));
                        } else {
                            msgs.add(Msg.user(J.s(args, "text", "")));
                        }
                        JsonObject opts = new JsonObject();
                        String m = J.s(args, "model", "");
                        if (Str.has(m)) opts.addProperty("model", m);
                        Res r = "vision".equals(op) ? ai.vision(msgs, opts) : ai.chat(msgs, null, opts);
                        return r.ok() ? Str.nz(r.content) : ("模型调用失败：" + r.error);
                    }
                }));

        // ---------------- ⑧ NapCat ----------------
        reg.add(Tool.of("napcat")
                .desc("NapCat（OneBot v11）：action=list 看动作目录；其余 action 直接调用，params 是动作参数对象。"
                        + "未连接时返回“Napcat 没有连接”，其它能力不受影响。")
                .returns("动作执行结果（JSON）")
                .params(schemaNapcat())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        // 工具路径<b>只走"装好闸门"的实例</b>：回落到未装闸门的 api 等于把 E 类判定整层删掉
                        // （Boot 里那个 api 是给"基板自己发回复/定时推送"用的）。
                        // 闸门没装配 = 整把工具 fail-closed（连 list 都不给），理由里点明原因。
                        String action = J.s(args, "action", "list");
                        if (!boot.napcatGuardReady()) {
                            // 产出面统一用 Acl.DENY_PREFIX（"[权限阻断] "）；旧串 Auth.DENY_PREFIX
                            // 只留给识别面兼容（D12 ★4 / D13 ⑤）—— 这条拒文以前根本没有 [权限阻断] 。
                            String why = Acl.DENY_PREFIX + "NapCat 闸门没有装配（guardedApi 缺失，或它没装上 Guard）"
                                    + "—— 拒绝执行 " + action;
                            if (out != null) out.err(why);
                            return why;
                        }
                        sair.v4.qq.Api api = boot.guardedNapcat();
                        if (api == null) {
                            String why = Acl.DENY_PREFIX + "NapCat 闸门实例取不到 —— 拒绝执行 " + action;
                            if (out != null) out.err(why);
                            return why;
                        }
                        if ("list".equals(action)) {
                            JsonObject cat = api.catalog();
                            JsonObject visible = new JsonObject();
                            // 目录可见性 = 这个主体对 napcat.<动作> 有没有 X 位：与下面那道闸门
                            // （Guard 的 allowRes(Res.platform(action),'X')）<b>同一判据</b>，不再问旧档位表
                            // —— 旧表只会多拦，会把"实际调不动"的动作名暴露给非主人。
                            Caller who = napcatSubject(conf);
                            if (auth == null) {
                                String why = "[tool] napcat 权限面没有装配（auth == null）—— 拒绝列出动作目录";
                                if (out != null) out.err(why);
                                return why;
                            }
                            for (Map.Entry<String, com.google.gson.JsonElement> e : cat.entrySet()) {
                                if (auth.allowRes(who, sair.v4.auth.Res.platform(e.getKey()), 'X') == null) {
                                    visible.add(e.getKey(), e.getValue());
                                }
                            }
                            // 事实说明（不是新动作）：本地文件怎么交给 NapCat —— 由基板统一改写，调用方只管传绝对路径
                            visible.add("_local_file", J.obj("desc", localFileFact(boot.relay()),
                                    "params", new JsonObject()));
                            return J.json(visible);
                        }
                        // 先判权限再看连接状态：越权者不该从"未连接"里读到自己有没有这个动作
                        // 主体取法与 Guard 完全同源（见 napcatSubject）
                        Caller who = napcatSubject(conf);
                        if (auth == null) {
                            String why = "[tool] napcat 权限面没有装配（auth == null）—— 拒绝执行 " + action;
                            if (out != null) out.err(why);
                            return why;
                        }
                        String deny = auth.allowRes(who, sair.v4.auth.Res.platform(action), 'X');
                        if (deny != null) return deny;
                        if (!api.available()) return sair.v4.qq.Api.NOT_CONNECTED;
                        return J.json(api.call(action, J.sub(args, "params")));
                    }
                }));

        // ---------------- ⑨ 控制台 ----------------
        // 控制台捕获器（流式旁路 + 游标读 + 框架命令桥）在这里挂上配置里的上限：
        // 这一步之后 conf 一定拿得到；幂等、装不上（无界面环境）就静默降级，不影响其余能力。
        sair.v4.term.SfwOut.installTap(conf);

        reg.add(Tool.of("console")
                .desc("SFW 控制台（仅主人）：read 读控制台最近输出（游标式）/run 执行框架命令（如 jj/at 1+/100）并回它的新输出"
                        + "/history 最近的框架命令/print 打印一行/clear 清屏/size 控制台规模。"
                        + "控制台 = 主人机位；read 与 run 都是对同一个终端说话。")
                .returns("操作结果：read/run 回一行事实 + 输出正文；size 回规模事实；history 回命令表")
                .params(schemaConsole())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        String op = J.s(args, "op", "print").toLowerCase();
                        // ACL：<b>整把工具都判 E 类</b>（D10「E 类含 SFW 命令交互」+ P10a 裁决）——
                        // 这把工具的契约就是"SFW 控制台（仅主人）"，而且 print/clear 是<b>往主人机位写</b>
                        // （非主人可借它在控制台里打出伪造的框架输出去骗主人）。统一用 X 而不是 R：
                        // SYSTEM 的 E 默认只有 X，用 R 会把她自己（自主读控制台）挡在外面。
                        String aclDeny = needRes(auth, conf, sair.v4.auth.Res.platform("sfw.run"), 'X');
                        if (aclDeny != null) return aclDeny;
                        // 每次用到时读配置（consoleTap* 改动即时生效：上限/开关/转发档位）
                        sair.v4.term.SfwOut.installTap(boot.conf());

                        if ("read".equals(op)) {
                            long since = J.l(args, "since_seq", 0L);
                            int max = J.i(args, "tail_chars", 0);
                            String filter = J.s(args, "filter", "");
                            return sair.v4.term.SfwOut.read(since, max, filter);
                        }
                        if ("run".equals(op)) {
                            String cmd = J.s(args, "cmd", "");
                            // B10：这一格原来回的是「需要 cmd」—— 既没有 [console] 前缀、也没有 ok= 标记，
                            // 于是连续失败闸门（Loop.failed）既认不出工具名、也读不到 ok=，
                            // "她反复空调 console run"永远不计数（真机上会被别的计数凑够 5 次才掐停）。
                            // 改成 console 这一族的同一形态（与 ConsoleTap.run("") 逐字相同）：
                            // 结构标记是协议字段，不是关键词表。
                            if (Str.blank(cmd)) return "[console] run ok=0 reason=need_cmd";
                            return sair.v4.term.SfwOut.run(cmd);
                        }
                        if ("history".equals(op)) {
                            return sair.v4.term.SfwOut.history(J.i(args, "limit", 20));
                        }
                        if ("size".equals(op)) {
                            return sair.v4.term.SfwOut.size();
                        }
                        if ("visible".equals(op)) {
                            // 全量读取（当前可见全文）：按需调用，务必给 tail_chars（框架这个 API 会整份拷贝）
                            int max = J.i(args, "tail_chars", 4000);
                            String txt = sair.v4.term.SfwOut.visibleText(max);
                            if (txt == null || txt.isEmpty()) {
                                return "[console] visible ok=0（读不到：无 SFW 控制台）";
                            }
                            // 首行必须带 ok=1（B8，2026-09-16）：console 这一族成败都带 [console] 前缀，
                            // 连续失败闸门（Loop.failed）靠这个结构标记区分 —— 少了它，每次成功的
                            // visible 都会被算成失败（真机上 5 次就掐停整轮）。
                            return "[console] visible ok=1 chars=" + txt.length() + "\n" + txt;
                        }
                        if ("clear".equals(op)) {
                            sair.v4.term.SfwOut con = boot.console();
                            if (con == null) return "控制台不可用（当前输出不是 SFW 控制台）";
                            con.clear();
                            // 说清缓冲语义：/clear 清的是控制台文档（与命令历史），捕获缓冲照旧留着最近输出
                            return "已清屏（捕获缓冲仍保留 " + sair.v4.term.ConsoleTap.lines() + " 条，可继续 read）";
                        }
                        if ("print".equals(op) || op.isEmpty()) {
                            out.print(J.s(args, "text", "") + "\n", Out.Tone.NORMAL);
                            return "已打印";
                        }
                        return "未知 op：" + op + "（可用：read/run/history/print/clear/size/visible）";
                    }
                }));

        // ---------------- ④ 到点的事（闹钟账本 / 职责台） ----------------
        reg.add(Tool.of("alarm")
                .desc("到点的事（**待办与闹钟是同一张账**）：add 记一件（写 at/in = 到点触发；不写时间 = 先记成待办）；"
                        + "list 看清单（主人看全部，别人只看自己委派的那些）；remove/clear 删；"
                        + "done/fail 收尾（办妥了 / 没办成）；defer 顺延或给待办排期（别人委派的不能拖）。"
                        + "**没有「放弃」这个动作**：只有两种结局 —— 办妥，或者如实说没办成。"
                        + "时间：at=\"YYYY-MM-DD HH:mm\"/\"HH:mm\"/\"+30m\"/\"明天 09:00\"/\"明早\"/\"今晚\"，或 in=分钟；"
                        + "周期：repeat=once/daily/weekly/every:分钟/cron:分 时 日 月 周。"
                        + "scope 取 console/group/private，target 是群号或 QQ 号。"
                        + "mine=true = 我自己定下的（承诺/提醒）；默认 false = 别人委派的工作（必办）。")
                .returns("闹钟清单/操作结果（清单里每条带 state：pending 待办 / running 到点已触发还没收尾 / done 办妥 / failed 没办成 / abandoned 已放弃）")
                .params(schemaAlarm())
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        Caller me = t == null ? null : t.caller();
                        boolean master = me != null && me.master();
                        String op = J.s(args, "op", "list").toLowerCase();
                        // 读：按调用者判 C 类 R（非主人默认有 R）。写见下一段：账本是**她自己的账**。
                        String aclDeny = needRes(auth, conf, sair.v4.auth.Res.mem("alarm"), 'R');
                        if (aclDeny != null) return aclDeny;
                        // 写（add/done/fail/defer/abandon）按 **SYSTEM** 判 W：别人委派的事要真能记进来、
                        // 到点办完要真能收尾，而普通用户对 C 类只有 R（D43）—— 所以闸门交给"她的判断 + 配额"，
                        // 不给用户开写位（这条是设计选择，见 notes 里的记录）。
                        if (!"list".equals(op) && auth != null) {
                            String wDeny = auth.allowRes(
                                    Caller.systemActor(conf == null ? 0L : conf.masterQQ()),
                                    sair.v4.auth.Res.mem("alarm"), 'W');
                            if (wDeny != null) return wDeny;
                        }
                        if ("remove".equals(op)) {
                            return tick.remove(J.l(args, "id", 0), me) ? "已删除" : "没有这个闹钟（或它不是你的）";
                        }
                        if ("clear".equals(op)) {
                            int n = tick.clear(me);
                            return master ? ("已清空 " + n + " 个闹钟") : ("已清空你自己的 " + n + " 个闹钟");
                        }
                        if ("done".equals(op) || "fail".equals(op)) {
                            long id = J.l(args, "id", 0);
                            boolean done = "done".equals(op);
                            boolean ok = tick.mark(id, done ? Tick.ST_DONE : Tick.ST_FAILED,
                                    J.s(args, "result", ""), J.s(args, "why", ""), me);
                            return ok ? ("#" + id + (done ? " 已记成办妥" : " 已记成没办成"))
                                      : ("没有这个闹钟（或它不是你的）：#" + id);
                        }
                        if ("defer".equals(op)) {
                            long id = J.l(args, "id", 0);
                            long when = parseWhen(J.s(args, "at", ""), J.i(args, "in", 0));
                            if (when <= 0) return "时间无法识别（用 at=\"HH:mm\"/\"+30m\"/\"明天 09:00\" 或 in=分钟）";
                            boolean ok = tick.defer(id, when, J.s(args, "why", ""), me);
                            return ok ? ("#" + id + " 已顺延到 " + stamp(when))
                                      : ("顺延不了 #" + id + "：别人委派的是工作不能拖，或者这条不是你的");
                        }
                        if ("add".equals(op)) {
                            String atArg = J.s(args, "at", "");
                            int inArg = J.i(args, "in", 0);
                            String repeatArg = J.s(args, "repeat", "once");
                            boolean gaveTime = !Str.blank(atArg) || inArg > 0;
                            long when = parseWhen(atArg, inArg);
                            boolean cronRepeat = repeatArg.trim().toLowerCase().startsWith("cron:");
                            if (cronRepeat && when <= 0L) {
                                Cron cc = Cron.parse(repeatArg.trim().substring(5).trim());
                                if (cc == null) return "cron 表达式认不出（用「分 时 日 月 周」，如 cron:0 9 * * 1-5）";
                                when = cc.next(System.currentTimeMillis(), 366 * 24 * 60);
                                if (when <= 0L) return "这个 cron 表达式在未来一年里都不触发，先改一下";
                            }
                            if (when <= 0L && gaveTime) {
                                return "时间无法识别（用 at=\"HH:mm\"/\"+30m\"/\"明天 09:00\"，或 in=分钟；"
                                        + "想先记成待办就别写时间）";
                            }
                            // 周期活第一次什么时候响：写了 repeat 却没写时间 ⇒ 从"下一次"算起
                            // （every:N = N 分钟后；daily/weekly = 一个周期后；cron = 下一个命中分钟）
                            if (when <= 0L && isPeriodic(repeatArg)) {
                                when = Tick.nextFire(System.currentTimeMillis(), repeatArg, System.currentTimeMillis());
                                if (when <= 0L) return "这个周期写法认不出（once/daily/weekly/every:分钟/cron:分 时 日 月 周）";
                            }
                            String task = J.s(args, "task", "");
                            if (Str.blank(task)) return "需要 task（这件事是什么）";
                            // 去重：同一个人、时间相近（都没定时 = 都是待办）⇒ 当成"同一件事说了两遍"
                            // （真机实测：两条消息合并进两个回合，她会把同一件事记两遍）
                            long qqArg = J.l(args, "qq", 0L);
                            long principalQq = qqArg > 0L ? qqArg : (me == null ? 0L : me.qq());
                            long nowMs = System.currentTimeMillis();
                            if (principalQq > 0L) {
                                for (JsonObject a : tick.alarms()) {
                                    if (J.i(a, "enabled", 1) != 1) continue;
                                    if (J.l(Tick.ownerOf(a), "qq", 0L) != principalQq) continue;
                                    long fa = J.l(a, "fire_at", 0L);
                                    // "同一件事"的判据：任务正文归一化后一样（或互相包含）就当成同一件
                                    boolean same = sameTask(J.s(a, "task", ""), task);
                                    boolean dup;
                                    if (when <= 0L) {
                                        dup = fa <= 0L && nowMs - J.l(a, "ts", 0L) <= 300000L;
                                    } else {
                                        dup = fa > 0L && Math.abs(fa - when) <= 120000L;
                                    }
                                    // 正文一样 ⇒ 不管有没有 again 都不重复记（真机实测：合并回合会让她照着
                                    // "要再来一条就带 again=true"的提示再记一遍同一件事）
                                    if (same) {
                                        return "同一件事已经记着了：#" + J.l(a, "id", 0L) + "（" + J.s(a, "task", "")
                                                + "）—— 不重复记";
                                    }
                                    if (dup && !J.b(args, "again", false)) {
                                        return "这条已经有了：#" + J.l(a, "id", 0L) + "（" + J.s(a, "task", "")
                                                + "）—— 不重复记。真要再排一条就带 again=true";
                                    }
                                }
                            }
                            // 配额：每条到点的事 = 一次完整轮，账本是她记的、但钱是主人花的
                            int pendingAll = 0;
                            int pendingMine = 0;
                            for (JsonObject a : tick.alarms()) {
                                if (J.i(a, "enabled", 1) != 1) continue;
                                String st = J.s(a, "state", "");
                                if (Tick.ST_DONE.equals(st) || Tick.ST_FAILED.equals(st)) continue;
                                pendingAll++;
                                if (me != null && J.l(Tick.ownerOf(a), "qq", 0L) == me.qq()) pendingMine++;
                            }
                            if (pendingAll >= ALARM_MAX_ALL) {
                                return "到点的事太多了（还有 " + pendingAll + " 条没办完）—— 先办掉或清掉一些再加";
                            }
                            if (me != null && me.qq() > 0L && pendingMine >= ALARM_MAX_ONE) {
                                return "你手上的事已经压了 " + pendingMine + " 条没办完，先清一清再加";
                            }
                            String scopeArg = J.s(args, "scope", "").trim().toLowerCase();
                            long target = J.l(args, "target", 0);
                            String scope;
                            if (master) {
                                scope = Str.blank(scopeArg) ? "console" : scopeArg;
                                if ("console".equals(scope)) target = 0;
                            } else {
                                // 非主人：只能给自己所在会话定时，不能建"以主人身份跑"的闹钟
                                if ("console".equals(scopeArg)) {
                                    return Acl.DENY_PREFIX + "只有主人能建控制台闹钟";
                                }
                                boolean inGroup = me != null && me.isGroup();
                                scope = inGroup ? "group" : "private";
                                target = inGroup ? me.groupId() : me.qq();
                            }
                            if (!"console".equals(scope) && target <= 0) return "需要 target（群号或 QQ 号）";
                            // 替谁记的：一轮里可能同时有好几个人的消息（同会话合并）⇒ 用 qq= 指明委托人；
                            // 不写 = 当轮说话的人。归属决定"谁能查、到点以谁的身份跑"。
                            boolean principalMaster = qqArg > 0L
                                    ? (conf != null && qqArg == conf.masterQQ())
                                    : master;
                            JsonObject row = tick.add(when, repeatArg, scope, target,
                                    task, J.s(args, "prompt", ""), me, J.b(args, "mine", false),
                                    qqArg, principalMaster);
                            return J.json(row);
                        }
                        // list：主人看全部（mine=true 时只看自己委派的），别人只看自己委派的
                        boolean onlyMine = J.b(args, "mine", false);
                        JsonArray arr = new JsonArray();
                        for (JsonObject a : tick.alarms(master ? null : me)) {
                            if (onlyMine) {
                                long oq = J.l(Tick.ownerOf(a), "qq", 0L);
                                if (me == null || oq != me.qq()) continue;
                            }
                            arr.add(a);
                        }
                        return J.json(arr);
                    }
                }));

        if (out != null) {
            out.dim("[builtins] 基板工具面 " + reg.size() + " 个（全部是能力形状；业务能力由技能提供）");
        }
    }

    // ==================== ⑥ perm：权限账本（授权 / 撤销 / 查询 / 验算） ====================
    //
    // 口径一句话：账本（数据根的 perms.json）是"主人的例外授权清单"，一条一行，写法固定：
    //     类["主体","范围","位"]
    // 判定 = 按类默认分配 + 例外突破默认；主体层级 User(3) > Group(2) > ALLUSER(1)，
    // SYSTEM 自成一路；同层级按行序，后面的覆盖前面的（范围的具体度不参与优先级）。
    // 这四个 op 一律 MASTER 专属（受保护第一层 mem:acl），非主人只回五个字。

    /** 非主人来要权限时唯一的那句话（不解释、不透露账本内容）。 */
    public static final String PERM_DENY = "这得主人定";

    /** 五个 op 的人话名（回执 / 报错 / 说明共用一份文案）。 */
    private static final String PERM_OPS =
            "grant 授权/覆盖 · revoke 撤销 · acl 看清单或验算（这三个只有主人）"
            + " · whoami 我是谁（谁都能问：只回自己五类有效位，不回账本正文）"
            + " · check 查某人在某类/某范围的位与例外（只读；谁都能查自己，查别人只有主人）";

    /**
     * {@code whoami} 是否对所有身份开放（主人 2026-09-15 口径：非主人问得到"自己的位与默认分配"，
     * 但<b>不回账本条目原文</b>）。
     *
     * <p>与 {@code notes/acl-decisions.md} D3 那句"非主人问权限 = 拒答"的差别只在这一格：
     * D3 说的是"问<b>别人</b>有什么权限"（那是 {@code acl} op，恒 MASTER 专属）；
     * 若裁定 whoami 也要 MASTER 专属，把这一个字面量改成 {@code false} 即可，逻辑不用动。</p>
     */
    private static final boolean PERM_WHOAMI_OPEN = true;

    /**
     * perm 工具的口径正文（<b>她自己读的就是这一段</b>：人话 → 账本条目的映射表 + 两条优先级规则）。
     *
     * <p><b>为什么放工具描述、不并进 {@code identity.md}</b>：① 身份提示词是<b>每轮常驻</b>的预算
     * （预算键 {@code identityMaxChars}，默认 8000，超了只 warn、不截断 —— 见 {@code prompt.PromptFile}），
     * 塞进来就是"每一轮都付这份钱"，而身份那点预算该留给她的根设定；② 这份口径只在"主人说改权限"这一刻有用，
     * 工具描述正是那一刻必然送进模型的上下文，而身份提示词是每轮常驻的预算；③ 它就是这把工具的契约
     * （授权口径本来就只跟 perm 有关），不用两头同步；④ 事实块 / 身份提示词的改写不归它管，不交叉。</p>
     *
     * <p>{@code public} 是给探针断言用的（{@code ProbeAclGrant} 直接核这段文本覆盖了哪些人话说法，
     * 免得口径悄悄被改窄）。注册处用 {@code .desc(PERM_DESC)} 把它挂到工具上。</p>
     */
    public static final String PERM_DESC =
            "权限账本（资源的 RWX 位；只有主人能改）：" + PERM_OPS + "。"
            + "别人来要权限（含「给我权限」）只回「这得主人定」，绝不写盘、别说账本有什么。\n"
            + "· 「这人有没有特例放行」用 op=check：principal 留空=查你自己，cls=A/B/C/E/T，"
            + "scope 留空=整类（给具体范围更准）；**只读**、一个字都不改；"
            + "查别人只有主人，非主人只能查自己。\n"
            + "账本一条一行：类[\"主体\",\"范围\",\"位\"]\n"
            + "· 五类：A 本机（所有本机文件 + 内存中的所有进程，含网络资源）"
            + "· B SFW（SFW 运行时目录，只有文件）"
            + "· C 数据（数据库全部内容 + dataDir 的 files 目录）"
            + "· E 外部交互（Napcat 输入输出 + 向 SFW 发命令及其输出）"
            + "· T 工具（只有入口受管控：R 看工具、W 改/注册、X 执行；执行不判位）。\n"
            + "· 主体：User<QQ号>（个人，跨群有效）· Group<群号>（只在该群会话生效）"
            + "· SYSTEM（你自己）· ALLUSER（全体用户，不含你和主人）。\n"
            + "· 范围：A/B 写路径（盘符 D: / 目录 / 文件）；C 写库名 / mem:xxx /"
            + " files 下某文件完整路径（C 类按名字相等命中，整类写空串）；E 写动作名；"
            + "T 写工具名（tool: 前缀可省）；空串 = 该类全部。\n"
            + "· 位：R/W/X 的 7 种组合（R、W、X、RW、RX、WX、RWX）；空串 = 一位都没有（显式拒绝，压过默认分配）。\n"
            + "· 没写进账本的走默认分配：A=不给、B=不给、C=R、E=不给、T=不给"
            + "（A 类 = 本机文件与进程（含网络资源），普通用户连读都不给，R/W/X 三位全收回；"
            + "B 类 = SFW 目录内的文件，一位都不给；"
            + "C 类 = 数据库全部内容 + files 目录，只给读；"
            + "E 类 = Napcat 输入输出与 SFW 命令交互，一位都不给；"
            + "T 类 = 工具入口（看 / 改·注册 / 执行），一位都不给 —— 非主人看不到也调不动任何工具）；"
            + "你自己（SYSTEM）是 A=R、B=RW、C=RWX、E=RWX、T=RWX。\n"
            + "· 受限配置文件与脚本（`.json` / `.ini` / `.conf` / `.properties` / `.yml` / `.yaml` / `.toml` /"
            + " `.bat` / `.cmd` / `.ir` / `.xml` / `.env` / `.key` / `.pem` / `.p12`，大小写不敏感；只算 SFW 内的 B 类）是**受保护资源**，"
            + "非主人一律读不到（例外也不开）—— 别把配置内容、脚本内容念给别人。\n"
            + "· 例外可以突破默认 —— 想给某人读本机文件（A 类）：A[\"User<QQ>\",\"\",\"R\"]（整类给读）；"
            + "只给某个范围：A[\"User<QQ>\",\"D:/share\",\"RWX\"]（范围外照样一点都碰不到）；"
            + "给全体用户：A[\"ALLUSER\",\"\",\"R\"]。默认收回了 A 类之后，按人放开就走这几条。\n"
            + "主人说什么 → 就调什么：\n"
            + "·「帮我设定123456权限位置A=RWX」→ op=grant principal=123456 cls=A bits=RWX（scope 留空 = 整个 A 类）\n"
            + "·「帮我设定123456权限位置C盘的XXX目录=RWX」→ op=grant principal=123456 cls=A scope=C:/XXX bits=RWX\n"
            + "·「帮我设定群123456权限位置D盘=R」→ op=grant principal=群123456 cls=A scope=D: bits=R\n"
            + "·「帮我设定123456，23456，45678的权限位置D:/share = R」→ principal=123456,23456,45678"
            + "（逗号 = 一主体一条，自动展开）cls=A scope=D:/share bits=R\n"
            + "·「帮我设定123456能用 agent 这个工具」→ op=grant principal=123456 cls=T scope=agent bits=X"
            + "（T 类只管入口三位；tool: 前缀可省）\n"
            + "·「帮我设定 A[\\\"User123456\\\",\\\"D:/share\\\",\\\"RWX\\\"]」→ 整条照抄进 principal"
            + "（其余参数不用给），原样落账\n"
            + "·「帮我取消123456的A权限」→ op=revoke principal=123456 cls=A（scope 留空 = 删其 A 类全部条目 → 回默认分配）\n"
            + "·「帮我取消123456在D:/share的权限」→ op=revoke principal=123456 scope=D:/share（只删命中这一条）\n"
            + "·「帮我禁止123456读D:/share」→ op=grant principal=123456 cls=A scope=D:/share bits="
            + "（空串！「禁止」＝写空位条目＝显式拒绝，跟「取消」不是一回事）\n"
            + "·「帮我看看123456现在有什么权限」→ op=acl principal=123456（先看，别改）\n"
            + "·「帮我列出所有授权」→ op=acl（不带参数）\n"
            + "四条规矩：① 回执里的「实际生效位」是基板算的，别自己算：例外条目直接生效 —— "
            + "A=RWX 就是真能写本机，不再被默认分配压回去；给 SYSTEM 写例外要在回执里说清「这是给她自己的例外，"
            + "会影响她的自主行为」；A 的 W/X、B 的 W、E 的 X 是高风险（接近主人的能力），基板会在控制台多打一行 warn。"
            + "② 层级优先：User<QQ>(3) > Group<群号>(2) > ALLUSER(1)，SYSTEM 自成一路（只管你自己）—— "
            + "更细的身份赢，User 条目哪怕写在 Group 条目前面也照样赢。"
            + "③ 同层级按行序：同层命中多条时，账本里靠后的覆盖靠前的；范围的具体度不参与优先级 —— "
            + "要「先全清、再单独放开」，就把放开那条写在后面。"
            + "④ 含糊就先复述一遍再问主人，别猜着写盘；位只认 RWX，主人说数字（老口径）就回一句「现在按 RWX 写」。";

    /** perm 的用法示例（盖掉 tools-index.md 那行老口径示例，见上面注册处的注释）。 */
    private static final String[] PERM_EXAMPLES = {
            "op=acl（列所有授权；加 principal=123456 看某人权限）",
            "op=grant principal=123456 cls=A scope=D:/share bits=RWX（主人说「帮我设定123456在D:/share读写执行」）",
            "op=revoke principal=123456 cls=A（主人说「帮我取消123456的A权限」）",
            "op=check cls=C scope=memory（只读：某范围有多少位、有无例外命中；principal 留空=自己）",
    };

    /** 类别的人话名（回执 / 说明用）。 */
    private static String clsName(char cls) {
        switch (Character.toUpperCase(cls)) {
            case 'A': return "A 本机（本机文件与进程，含网络资源）";
            case 'B': return "B SFW（SFW 运行时目录，仅文件）";
            case 'C': return "C 数据（数据库全部内容 + files 目录）";
            case 'E': return "E 外部交互（Napcat 与 SFW 命令交互）";
            case 'T': return "T 工具（只有入口受管控）";
            default: return cls + "（认不出的类别）";
        }
    }

    /** 类别写法 → 类字母；认不出返回 {@code 0}（只认 A/B/C/E/T，容忍 "A类" 这种写法）。 */
    private static char clsOf(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return 0;
        char c = Character.toUpperCase(s.charAt(0));
        if (c < 'A' || c > 'Z') return 0;
        String rest = s.substring(1).trim();
        if (!rest.isEmpty() && !"类".equals(rest)) return 0;
        return "ABCET".indexOf(c) >= 0 ? c : 0;
    }

    /** 类别认不出时的统一回话。 */
    private static String clsProblem(String raw) {
        return "类别只认 A 本机 / B SFW / C 数据 / E 外部交互 / T 工具（现在是 \"" + (raw == null ? "" : raw.trim()) + "\"）";
    }

    /** 位的人话写法（{@code NONE} 说成"一位都没有"，因为条目里写的是空串）。 */
    private static String bitsText(int mask) {
        return mask == Bits.NONE ? "NONE（一位都没有）" : Bits.format(mask);
    }

    /** 主体键属于哪条线：{@code SYSTEM} 走她自己的默认分配，其余（含 ALLUSER）走用户那条线。 */
    private static Caller.Kind kindOf(String principalKey) {
        return Acl.SYSTEM_KEY.equals(principalKey) ? Caller.Kind.SYSTEM : Caller.Kind.ALLUSER;
    }

    /** 某个身份在某类上的默认分配表（一句话，回执用）：{@code A=NONE B=NONE C=R E=NONE T=NONE}。 */
    private static String allocText(Caller.Kind kind) {
        StringBuilder sb = new StringBuilder();
        char[] cs = { 'A', 'B', 'C', 'E', 'T' };
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) sb.append(" ");
            sb.append(cs[i]).append("=").append(Bits.format(Acl.allocOf(kind, cs[i])));
        }
        return sb.toString();
    }

    /** 高风险例外：A 的 W/X、B 的 W、E 的 X（等于把接近主人的能力给出去了）。 */
    private static boolean risky(char cls, int mask) {
        switch (Character.toUpperCase(cls)) {
            case 'A': return (mask & (Bits.W | Bits.X)) != 0;
            case 'B': return (mask & Bits.W) != 0;
            case 'E': return (mask & Bits.X) != 0;
            default: return false;
        }
    }

    /**
     * 主体写法的宽松归一 —— <b>只做设计稿写明的几条，绝不瞎猜</b>：
     * {@code 群123456}/{@code 群号123456} → {@code Group123456}，裸数字 → {@code User<数字>}，
     * {@code 全体用户/所有人} → {@code ALLUSER}，{@code 你自己/她自己/系统} → {@code SYSTEM}
     * （后两条跟她读到的口径文本用的是同一批词）。认不出返回 {@code null}。
     */
    private static String principalKeyOf(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        if ("所有人".equals(s) || "全体用户".equals(s) || "全部用户".equals(s) || "所有用户".equals(s)) {
            return Acl.ALLUSER_KEY;
        }
        if ("你自己".equals(s) || "她自己".equals(s) || "系统".equals(s) || "系统自己".equals(s)) {
            return Acl.SYSTEM_KEY;
        }
        String low = s.toLowerCase(java.util.Locale.ROOT);
        if (s.startsWith("群号")) s = Acl.GROUP_PREFIX + s.substring(2).trim();
        else if (s.startsWith("群")) s = Acl.GROUP_PREFIX + s.substring(1).trim();
        else if (low.startsWith("qq号")) s = Acl.USER_PREFIX + s.substring(3).trim();
        else if (low.startsWith("qq")) s = Acl.USER_PREFIX + s.substring(2).trim();
        else if (s.startsWith("用户")) s = Acl.USER_PREFIX + s.substring(2).trim();
        return Acl.principalKey(s);
    }

    /** 多主体：逗号 / 顿号 / 分号 / 竖线分开（"123456，23456" = 两个主体，一个主体一条）。 */
    private static String[] splitPrincipals(String raw) {
        String[] parts = (raw == null ? "" : raw).split("[,，、;；|]");
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i] == null ? "" : parts[i].trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[out.size()]);
    }

    /** 看起来是不是"一整条账本条目"（主人给的写法：{@code 类["主体","范围","位"]}）。 */
    private static boolean isEntryText(String s) {
        String t = s == null ? "" : s.trim();
        return t.length() > 3 && t.indexOf('[') > 0 && t.endsWith("]");
    }

    /** 按（主体 + 类 + 规范化范围）在账本里定位那一条（就是 grant 用的定位键）。 */
    private static Acl.Entry find(Acl acl, String principalKey, char cls, String scope) {
        String sc = Acl.scopeOf(cls, scope);
        if (acl == null || principalKey == null || sc == null) return null;
        for (Acl.Entry e : acl.entries()) {
            if (e.cls() == cls && e.principal().equals(principalKey) && e.scope().equals(sc)) return e;
        }
        return null;
    }

    /** 主体键 → "就是这个人"的调用者（验算用；群主体落在那个群的假想会话里）。 */
    private static Caller callerOf(String principalKey) {
        if (principalKey == null) return null;
        if (Acl.SYSTEM_KEY.equals(principalKey)) return Caller.systemActor();
        if (Acl.ALLUSER_KEY.equals(principalKey)) return Caller.allUser(0L);
        if (principalKey.startsWith(Acl.GROUP_PREFIX)) {
            return Caller.allUser(0L).inGroup(idOf(principalKey, Acl.GROUP_PREFIX), "");
        }
        if (principalKey.startsWith(Acl.USER_PREFIX)) {
            return Caller.allUser(idOf(principalKey, Acl.USER_PREFIX));
        }
        return null;
    }

    /** {@code User123456} 里的数字（认不出按 0，认不出的主体本来也匹配不上任何真人）。 */
    private static long idOf(String key, String prefix) {
        try {
            return Long.parseLong(key.substring(prefix.length()).trim());
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    /**
     * 类 + 范围 → 一个资源描述（验算用；C 类认 {@code mem:}/{@code db:} 前缀，
     * T 类认 {@code tool:} 前缀，认不出返回 null）。
     */
    private static sair.v4.auth.Res resOf(char cls, String scope) {
        String s = scope == null ? "" : scope.trim();
        if (cls == 'A' || cls == 'B') return sair.v4.auth.Res.path(s);
        if (cls == 'C') {
            String low = s.toLowerCase(java.util.Locale.ROOT);
            // 别名 {@code db:memory} = {@code memory}：跟 {@code Acl.scopeOf} 的剥前缀口径对齐，
            // 否则同一个库两种写法在验算面会得到两种结果（{@code db:memory} 显示「✗ 范围不命中」）。
            if (low.startsWith("db:")) s = s.substring(3).trim();
            low = s.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("mem:")) return sair.v4.auth.Res.mem(s.substring(4));
            // 老习惯写 {@code cls=C scope=tool:xxx}：那是个 T 类资源（工具已从 C 类拆出），
            // 这里如实造出 T 类资源，让验算印出"按自动判定属于 T 类（不是 C 类）"，而不是静默算错。
            if (low.startsWith("tool:")) return sair.v4.auth.Res.tool(s.substring(5));
            // C 类里的「路径式」范围（{@code <数据根>/files/**} 归 C 类）要按<b>真资源</b>验算：
            // 否则验算拿"库名"去比路径，明明生效的授权会显示「✗ 范围不命中」。
            if (s.indexOf('/') >= 0 || s.indexOf('\\') >= 0) return sair.v4.auth.Res.path(s);
            return sair.v4.auth.Res.db(s);
        }
        if (cls == 'E') return sair.v4.auth.Res.platform(s);
        // T 类：工具入口。带不带 {@code tool:} 前缀都认（{@code Acl.scopeOf('T', …)} 的同一套归一）。
        if (cls == 'T') {
            String low = s.toLowerCase(java.util.Locale.ROOT);
            if (low.startsWith("tool:")) s = s.substring(5).trim();
            return sair.v4.auth.Res.tool(s);
        }
        return null;
    }

    /**
     * perm 工具的五个 op 的实现（<b>独立成静态方法</b>：探针可以直接调它，不用起一个 Boot）。
     *
     * <p><b>授权 / 撤销 / 查询（grant / revoke / acl）是 MASTER 专属</b>：开头一律判受保护第一层
     * {@code mem:acl} 的 <b>W 位</b>（{@link Acl#masterOnlyRes}：账本自己 + 授权动作，连她自己的
     * 自主行为也不给）—— 非主人只得到 {@link #PERM_DENY} 五个字，账本正文一个字都不回、
     * 连"装配没装配"都不漏。</p>
     *
     * <p><b>{@code whoami} 谁都能问</b>（它不是授权动作，也不回账本条目原文）：只回身份（kind）与
     * 五类有效位 + 每格的来源（例外 / 默认分配 / 受保护），见 {@link #permWhoami}。</p>
     *
     * <p><b>{@code check} 是只读查询，且不进上面那道 {@code mem:acl} 的门</b>：它不问账本正文，
     * 只问"这个主体在这一格上是什么"。所以它的权限边界单独写在自己的第一段里 ——
     * <b>查别人必须 MASTER，非主人只能查自己</b>，越界回 {@link Acl#DENY_PREFIX} 开头的拒绝原文。
     * 见 {@link #permCheck}。</p>
     *
     * @param acl 权限账本（{@code null} = 没装配）
     * @param c   调用者（{@code null} = 无主体，fail-closed）
     */
    public static String permTool(JsonObject args, Out out, Acl acl, Caller c) {
        String op = J.s(args, "op", "whoami").trim().toLowerCase();
        boolean master = c != null && c.master();
        if (acl == null) {
            // check / whoami 都只读、也都不回账本正文：默认分配是静态表，没账本也答得出
            if ("check".equals(op)) return permCheck(args, null, c);
            if (PERM_WHOAMI_OPEN && "whoami".equals(op)) return permWhoami(null, c);   // 没账本也答得出
            return master ? ("权限账本没装配（读不到数据根下的 " + Acl.FILE_NAME + "），改不了") : PERM_DENY;
        }
        // 只读查询：先于授权闸门处理（它自己的"查别人要 MASTER"边界在 permCheck 里）
        if ("check".equals(op)) return permCheck(args, acl, c);
        if ("whoami".equals(op) && (PERM_WHOAMI_OPEN || master)) return permWhoami(acl, c);
        // 受保护第一层（只有主人）：判定走 Acl 同一条 API，不另写一套；同时硬拦非 MASTER
        // （设计稿：授权 op 是"第一层受保护资源"，SYSTEM 也不给）。
        String deny = acl.allow(c, sair.v4.auth.Res.mem("acl"), 'W');
        if (!master || deny != null) {
            if (out != null && !master) {
                out.dim("[acl] 非主人调用授权 op（" + (c == null ? "无主体" : c.label()) + "），已拒");
            }
            return PERM_DENY;
        }
        if ("grant".equals(op)) return permGrant(args, out, acl, c);
        if ("revoke".equals(op)) return permRevoke(args, out, acl);
        if ("acl".equals(op)) return permAcl(args, acl);
        return "op 只认 " + PERM_OPS + "；旧的 get/list/set/reset/levels/setlevel 属于已废除的档位体系"
                + "（好感度不再插手权限），现在没有这些 op。";
    }

    /** 一条待授权的条目（参数给的三件套，或主人直接照抄的整条条目）。 */
    private static final class Spec {
        String principalKey;      // 规范主体键
        String principalRaw;      // 主体原文（报错回话用）
        char cls;
        String scope;             // 范围原文
        String bits;              // 位写法（"" = 显式拒绝）
        String literal;           // 整条条目的原文（不是照抄写法时为 null）
    }

    /**
     * {@code grant}：按（主体 + 类 + 规范化范围）<b>就地覆盖</b>已有条目（没有就追加到末尾），
     * 然后<b>显式落盘</b>，最后给回执。
     *
     * <p>回执必须写全三样：<b>原文条目</b> + <b>实际生效位</b> + <b>默认分配是多少</b>，
     * 并点明是不是「例外：超出默认分配」—— 免得主人以为 A 类写进去的 RWX 被默认分配压了回去
     * （A 类默认一位都不给，写 {@code A["User<QQ>","","R"]} 就是「超出默认分配 A=NONE，多给了 R」；
     * v2 起例外就是直接生效的，不再与默认分配取交集）。</p>
     */
    private static String permGrant(JsonObject args, Out out, Acl acl, Caller c) {
        String rawP = J.s(args, "principal", "").trim();
        if (Str.blank(rawP)) {
            return "用法：perm op=grant principal=User123456 cls=A scope=D:/share bits=RWX\n"
                    + "  principal＝主体（User<QQ号> / Group<群号> / SYSTEM / ALLUSER；裸数字 = User<QQ>；"
                    + "逗号分开 = 一个主体一条；也可以直接给一整条条目）\n"
                    + "  cls＝A/B/C/E/T　scope＝留空 = 该类全部（A/B 给路径、C 给库名或 mem:xxx 或 files 下的路径、"
                    + "E 给动作名、T 给工具名）\n"
                    + "  bits＝R/W/X 的组合（RW / RX / RWX …）；空串 = 一位都不给（显式拒绝）";
        }
        boolean hasBits = args != null && args.has("bits");
        String clsArg = J.s(args, "cls", "").trim();
        String scopeArg = J.s(args, "scope", "");
        String bitsArg = hasBits ? J.s(args, "bits", "") : null;

        List<Spec> specs = new ArrayList<Spec>();
        if (isEntryText(rawP)) {
            // 字面条目：主人自己给的写法，整条照抄（这是设计稿写明的用法）
            Acl.Entry lit = Acl.parse(rawP);
            if (lit == null) return "这条条目写法认不出：" + rawP + "\n  " + Acl.reason(rawP);
            Spec s = new Spec();
            s.principalKey = lit.principal();
            s.principalRaw = lit.principal();
            s.cls = lit.cls();
            s.scope = lit.scopeRaw();
            s.bits = lit.bits();
            s.literal = rawP;
            specs.add(s);
        } else {
            if (!hasBits) {
                return "要明确给 bits（R/W/X 的组合，如 RWX）；空串才是「一点都不给」（显式拒绝）。"
                        + "含糊就先复述一遍问主人，别猜着写盘。";
            }
            char cls = clsOf(clsArg);
            if (cls == 0) return clsProblem(clsArg);
            String[] tokens = splitPrincipals(rawP);
            for (int i = 0; i < tokens.length; i++) {
                Spec s = new Spec();
                s.principalRaw = tokens[i];
                s.principalKey = principalKeyOf(tokens[i]);
                s.cls = cls;
                s.scope = scopeArg;
                s.bits = bitsArg;
                specs.add(s);
            }
        }
        // 先全验一遍：一个主体写不进去，整批都不写（不留半截授权）
        for (int i = 0; i < specs.size(); i++) {
            String bad = specProblem(specs.get(i));
            if (bad != null) return bad;
        }
        if (acl.file() != null) acl.reload();          // 账本是人能手改的：写之前先重读，别盖掉手改的内容
        List<Acl.Entry> written = new ArrayList<Acl.Entry>();
        for (int i = 0; i < specs.size(); i++) {
            Spec s = specs.get(i);
            if (!acl.grant(s.principalKey, s.cls, s.scope, s.bits)) {
                return "写不进去：" + s.principalKey + " " + s.cls + " \"" + Str.nz(s.scope)
                        + "\"（主体 / 类 / 范围 / 位有一处认不出，账本没动）";
            }
            Acl.Entry e = find(acl, s.principalKey, s.cls, s.scope);
            if (e != null) written.add(e);
        }
        boolean saved = acl.save();
        StringBuilder sb = new StringBuilder();
        sb.append(written.size() == 1 ? "已设定：" : ("已设定 " + written.size() + " 条（一个主体一条）："));
        for (int i = 0; i < written.size(); i++) {
            Acl.Entry e = written.get(i);
            if (specs.size() > 1) sb.append("\n").append(i + 1).append(")");
            sb.append("\n").append(receiptOf(acl, e));
            if (out != null && risky(e.cls(), acl.effective(e))) {
                out.warn("[acl] 高风险例外：" + e.raw() + " —— " + clsName(e.cls()) + " 的 "
                        + Bits.format(acl.effective(e)) + " 等于把接近主人的能力给了 " + e.principal());
            }
        }
        if (specs.size() == 1 && specs.get(0).literal != null && written.size() == 1
                && !specs.get(0).literal.equals(written.get(0).raw())) {
            sb.append("\n  照抄的原文：").append(specs.get(0).literal)
              .append("（账本里按规范写法存成上面那条，含义一样）");
        }
        sb.append("\n").append(saved
                ? ("已写入 " + (acl.file() == null ? Acl.FILE_NAME : acl.file().getAbsolutePath()) + "（立即生效，不用重启）")
                : "注意：写盘失败，改动只在内存里（重启会丢）！");
        return sb.toString();
    }

    /** 一条 Spec 能不能写进账本；不能就返回人话理由（{@code null} = 能）。 */
    private static String specProblem(Spec s) {
        if (s.principalKey == null) {
            String why = Acl.principalReason(s.principalRaw);
            return "主体写法认不出：" + s.principalRaw + "\n  " + (Str.blank(why)
                    ? "主体只认 User<QQ号> / Group<群号> / SYSTEM / ALLUSER（裸数字 = User<QQ>；MASTER 不接受例外）" : why);
        }
        if (clsOf(String.valueOf(s.cls)) == 0) return clsProblem(String.valueOf(s.cls));
        if (Acl.scopeOf(s.cls, s.scope) == null) {
            return "范围写法认不出：" + Str.nz(s.scope) + "（A/B 给路径、C 给库名或 mem:xxx、" 
                    + "E 给动作名、T 给工具名；空串 = 该类全部）";
        }
        if (Acl.bitsOfField(s.bits) == Bits.INVALID) {
            return "位写法认不出：" + Str.nz(s.bits) + " —— 只认 " + Bits.hint()
                    + "，空串 = 一位都不给；主人说数字（老口径）就回一句「现在按 RWX 写」，别照数字落账";
        }
        return null;
    }

    /**
     * 一条条目的回执：原文 + 实际生效位 + 默认分配 + 是不是「例外：超出默认分配」。
     * <p>实际生效位一律走 {@link Acl#effective}，默认分配一律走 {@link Acl#allocOf} —— 不在这里另算一套。</p>
     */
    private static String receiptOf(Acl acl, Acl.Entry e) {
        int eff = acl.effective(e);
        Caller.Kind kind = kindOf(e.principal());
        int alloc = Acl.allocOf(kind, e.cls());
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(e.raw());
        sb.append("\n    实际生效位 ").append(bitsText(eff))
          .append("（例外条目直接生效，不再与默认分配取交集）");
        sb.append("\n    默认分配 ").append(e.cls()).append("=").append(bitsText(alloc))
          .append(kind == Caller.Kind.SYSTEM ? "（她自己那条线）" : "（一条例外都没命中时按类默认）");
        int over = eff & ~alloc;
        int less = alloc & ~eff;
        if (over != 0) {
            sb.append("\n    例外：超出默认分配 ").append(e.cls()).append("=").append(Bits.format(alloc))
              .append(" —— 多给了 ").append(Bits.format(over));
        } else if (less != 0) {
            sb.append("\n    例外：比默认分配更紧 —— 收掉了 ").append(Bits.format(less));
        } else {
            sb.append("\n    例外：与默认分配一致（没超出）");
        }
        if (e.system()) {
            sb.append("\n    注意：这是给她自己（SYSTEM）的例外，会影响她的自主行为。");
        }
        return sb.toString();
    }

    /**
     * {@code revoke}：删掉命中的条目（按主体 + 类 + 范围定位），显式落盘，回执说清"回到默认分配多少"。
     *
     * <p>两种口径：<b>范围留空</b> = 删掉他在这个类里的<b>全部</b>条目（"帮我取消123456的A权限"）；
     * <b>给了范围</b> = 只删命中那一条（"帮我取消123456在D:/share的权限"）。
     * <b>不给类也行</b>（主人那句话里常常没有类字母）：这时按范围在所有类里找命中项，
     * 但只要给了类就只动那个类。</p>
     */
    private static String permRevoke(JsonObject args, Out out, Acl acl) {
        String rawP = J.s(args, "principal", "").trim();
        if (Str.blank(rawP)) {
            return "用法：perm op=revoke principal=User123456 cls=A scope=D:/share\n"
                    + "  scope 留空 = 删掉他在这个类里的全部条目（回到默认分配）；给了 scope = 只删命中那一条；\n"
                    + "  cls 可以不给（这时必须有 scope：按范围在所有类里找命中项）。"
                    + "要「禁止」而不是「取消」，用 op=grant 写空位（bits=空串）。";
        }
        String clsArg = J.s(args, "cls", "").trim();
        String scopeArg = J.s(args, "scope", "");
        char cls = clsOf(clsArg);
        if (!Str.blank(clsArg) && cls == 0) return clsProblem(clsArg);
        if (cls == 0 && Str.blank(scopeArg)) {
            return "要撤销哪个类？cls=A/B/C/E/T（不给类就得给 scope —— 免得一句话把别的类的条目也删了）";
        }
        String[] tokens = splitPrincipals(rawP);
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < tokens.length; i++) {
            String p = principalKeyOf(tokens[i]);
            if (p == null) {
                String why = Acl.principalReason(tokens[i]);
                return "主体写法认不出：" + tokens[i] + "\n  " + (Str.blank(why)
                        ? "主体只认 User<QQ号> / Group<群号> / SYSTEM / ALLUSER（裸数字 = User<QQ>）" : why);
            }
            keys.add(p);
        }
        StringBuilder sb = new StringBuilder();
        int total = 0;
        List<String> missing = new ArrayList<String>();
        if (acl.file() != null) acl.reload();          // 同上：写之前先重读
        for (int i = 0; i < keys.size(); i++) {
            String p = keys.get(i);
            // 先照账本顺序把要删的条目挑出来（回执要写它们的原文），再逐条删
            List<Acl.Entry> victims = new ArrayList<Acl.Entry>();
            for (Acl.Entry e : acl.entries()) {
                if (!e.principal().equals(p)) continue;
                if (cls != 0 && e.cls() != cls) continue;
                if (cls == 0 || !Str.blank(scopeArg)) {
                    String want = Acl.scopeOf(e.cls(), scopeArg);
                    if (want == null || !e.scope().equals(want)) continue;
                }
                victims.add(e);
            }
            if (victims.isEmpty()) {
                missing.add(p + (Str.blank(scopeArg) ? "" : (" 在 " + scopeArg)));
                continue;
            }
            sb.append("\n已撤销 ").append(p).append(" 的 ").append(victims.size()).append(" 条条目：");
            for (int k = 0; k < victims.size(); k++) {
                Acl.Entry e = victims.get(k);
                if (!acl.revoke(p, e.cls(), e.scopeRaw())) continue;      // 按定位键删（同一条）
                total++;
                sb.append("\n  ").append(e.raw()).append("   [").append(clsName(e.cls())).append("]");
                sb.append("\n    回到默认分配 ").append(e.cls()).append("=")
                  .append(bitsText(Acl.allocOf(kindOf(p), e.cls())))
                  .append("（若还有别的条目命中这个资源 —— 群条目或 ALLUSER 底稿 —— 按层级优先算，"
                        + "用 op=acl principal=").append(p).append(" cls=").append(e.cls())
                  .append(" scope=… 验算）");
            }
        }
        boolean saved = acl.save();
        if (out != null) out.dim("[acl] revoke：" + total + " 条条目已从账本删除（" + acl.stat() + "）");
        StringBuilder head = new StringBuilder();
        head.append(total == 0 ? "没有命中的条目（账本没动）" : ("共删掉 " + total + " 条"));
        for (int i = 0; i < missing.size(); i++) {
            head.append("\n  没有命中：").append(missing.get(i))
                .append("（本来就是默认分配，没有例外条目）");
        }
        head.append("\n").append(saved ? "已写入账本（立即生效，不用重启）" : "注意：写盘失败，改动只在内存里！");
        return sb.append("\n").append(head).toString();
    }

    /**
     * {@code acl}：不带参数 = 列全部条目（<b>按文件顺序</b>，标出主体层级与"默认分配 / 例外"）；
     * 带 {@code principal}（+ 可选 {@code cls} / {@code scope}）= 看这个人 / 验算。
     *
     * <p>验算打印<b>按优先级顺序命中的条目链</b>（谁覆盖了谁）与<b>最终有效位</b>：
     * 有效位一律调 {@link Acl#bitsOf}，来源调 {@link Acl#source} —— 账本怎么判就怎么显示，不另算一套。</p>
     */
    private static String permAcl(JsonObject args, Acl acl) {
        String rawP = J.s(args, "principal", "").trim();
        String clsArg = J.s(args, "cls", "").trim();
        String scopeArg = J.s(args, "scope", "");
        if (Str.blank(rawP)) {
            StringBuilder sb = new StringBuilder();
            sb.append(acl.stat()).append("\n账本文件：")
              .append(acl.file() == null ? "(无)" : acl.file().getAbsolutePath());
            List<String> lines = acl.list();
            for (int i = 0; i < lines.size(); i++) sb.append("\n  ").append(lines.get(i));
            List<Acl.Reject> rej = acl.rejects();
            for (int i = 0; i < rej.size(); i++) sb.append("\n  跳过非法条目：").append(rej.get(i));
            return sb.toString();
        }
        String p = principalKeyOf(rawP);
        if (p == null) {
            String why = Acl.principalReason(rawP);
            return "主体写法认不出：" + rawP + "\n  " + (Str.blank(why)
                    ? "主体只认 User<QQ号> / Group<群号> / SYSTEM / ALLUSER（裸数字 = User<QQ>）" : why);
        }
        if (Str.blank(clsArg)) {                       // "帮我看看123456现在有什么权限"
            return principalReport(acl, p);
        }
        char cls = clsOf(clsArg);
        if (cls == 0) return clsProblem(clsArg);
        Caller who = callerOf(p);
        if (who == null) return "造不出这个主体的判定身份：" + p;
        sair.v4.auth.Res r = resOf(cls, scopeArg);
        boolean whole = Str.blank(scopeArg);            // 空范围 = 整个类
        boolean autoCls = r != null && r.cls() != cls;  // 类是被自动判定的（A/B 路径、B 类空范围）
        if (whole && autoCls) {
            // 整类视图算不出来（B 类没有"空范围"的资源工厂）：说清 + 给该主体在这个类的条目
            StringBuilder sb = new StringBuilder();
            sb.append("验算：").append(p).append(" · ").append(clsName(cls)).append(" · 范围＝整类（空串）");
            sb.append("\n  这一格算不出单一位：范围写空串的条目命中该类所有资源，"
                    + "而带具体范围的条目只命中它自己（含它下面）—— 「整个类」上没有单一有效位，得给具体资源。");
            sb.append("\n  给个范围我就能算：A/B 给路径（scope=D:/share）、C 给库名或 mem:xxx、"
                    + "E 给动作名、T 给工具名。");
            sb.append("\n").append(classEntries(acl, p, cls, who, null));
            sb.append("\n  默认分配：").append(cls).append("=").append(bitsText(Acl.allocOf(who.kind(), cls)))
              .append("（一条例外都没命中时）");
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("验算：").append(p).append(" · ").append(clsName(cls))
          .append(" · 范围=").append(whole ? "整类（空串）" : scopeArg.trim());
        sb.append("\n  资源：").append(r == null ? "(造不出来)" : r.toString()).append("  ← ");
        if (autoCls) {
            sb.append("按自动判定属于 ").append(clsName(r.cls())).append("（不是 ").append(cls)
              .append(" 类）—— 类别是自动判的，下面按 ").append(r.cls()).append(" 类算");
        } else {
            sb.append("类别与请求一致");
        }
        if (whole) {
            sb.append("\n  （范围＝整类：只有范围写空串的条目命中它，所以这格是「整类视图」）");
        }
        sb.append("\n").append(classEntries(acl, p, r.cls(), who, r));
        int eff = acl.bitsOf(who, r);
        sb.append("\n  最终有效位：").append(Bits.format(eff))
          .append(eff == Bits.NONE ? "（一位都没有）" : "")
          .append("（掩码 ").append(eff).append("）");
        sb.append("\n  位从哪来：").append(acl.source(who, r));
        sb.append("\n  默认分配：").append(r.cls()).append("=").append(bitsText(Acl.allocOf(who.kind(), r.cls())))
          .append("（一条例外都没命中时按类默认）");
        sb.append("\n  读 R ").append(Bits.has(eff, 'R') ? "有" : "没有")
          .append(" · 写 W ").append(Bits.has(eff, 'W') ? "有" : "没有")
          .append(" · 执行/对外 X ").append(Bits.has(eff, 'X') ? "有" : "没有");
        sb.append("\n  规则：层级优先 User(3) > Group(2) > ALLUSER(1)（SYSTEM 自成一路）；"
                + "同层级按行序，后面的覆盖前面的；范围的具体度不参与优先级。");
        return sb.toString();
    }

    /** 某主体在某类的命中链（{@code r == null} 时只列条目与层级）。 */
    private static String classEntries(Acl acl, String p, char cls, Caller who, sair.v4.auth.Res r) {
        List<Acl.Entry> es = new ArrayList<Acl.Entry>();
        for (Acl.Entry e : acl.entries()) if (e.cls() == cls) es.add(e);
        StringBuilder sb = new StringBuilder();
        if (r == null) {
            sb.append("  ").append(cls).append(" 类条目（按账本顺序从上往下，例外命中时生效位见每行）：");
        } else {
            sb.append("  ").append(cls).append(" 类命中链（按账本顺序从上往下；★ = 最终说了算的那条，"
                    + "它压过上面所有命中的）：");
        }
        if (es.isEmpty()) return sb.append("（这个类里一条条目都没有）").toString();
        Acl.Entry win = r == null ? null : acl.hit(who, r);
        for (int i = 0; i < es.size(); i++) {
            Acl.Entry e = es.get(i);
            sb.append("\n    ").append(i + 1).append(". ").append(e.raw())
              .append("   主体 ").append(e.tierName());
            if (r != null) {
                int t = e.tier(who);
                sb.append(e.hits(who, r) ? "  ✓命中" : ("  ✗ " + (t <= 0 ? "主体不命中" : "范围不命中")));
                if (e == win) sb.append("  ★ 最终生效");
            } else {
                sb.append("  例外命中时生效 ").append(Bits.format(acl.effective(e)));
            }
        }
        return sb.toString();
    }

    /**
     * {@code check}：<b>只读查询</b> —— 某主体在某个类 / 某个范围上的
     * 「<b>默认分配</b> + <b>有效位</b> + <b>命中的例外条目</b> + <b>是不是落在受保护资源上</b>」。
     *
     * <p><b>与 {@code acl} 的分工</b>：{@code acl} 是<b>主人翻账本</b>（清单 / 逐条验算），恒 MASTER 专属、
     * 走受保护第一层 {@code mem:acl} 的 {@code W} 位那道门；{@code check} 问的是
     * "这个主体在这一格上到底是什么"，所以对非主人开了一条<b>极窄</b>的口子：
     * <b>非主人只能查自己</b>。它<b>不进</b> {@code mem:acl} 那道门（那道门管的是"改账本 / 翻账本正文"），
     * 边界写在下面第 ② 步里。</p>
     *
     * <h3>权限边界（第 ② 步，越界就拒）</h3>
     * <ul>
     *   <li>{@code principal} 留空 = 查自己（{@link #selfKeyOf}：她自己 → {@code SYSTEM}，
     *       其余 → {@code User<QQ>}）；</li>
     *   <li><b>查别人必须 MASTER</b>；非主人写别人的主体键一律拒，回
     *       {@link Acl#DENY_PREFIX} 开头的拒绝原文（{@code [权限阻断]}，识别面认得出）——
     *       <b>不是</b> {@link #PERM_DENY} 那五个字：那五个字是"别人来要权限"的回话，
     *       这里回答的是"你越界查了别人的权限"；</li>
     *   <li>没有可用的自身身份（没有 QQ 号、也不是她自己）→ 拒（fail-closed）。</li>
     * </ul>
     *
     * <p><b>只读</b>：不 {@code reload()}、不 {@code grant}/{@code revoke}、不 {@code save()} ——
     * 显示与判定一律用<b>内存里那一份账本</b>（与 {@code Auth.allowRes} 判定时用的同一份，
     * 所以主人刚写完例外，这里当场就是对的）。<b>本 op 不提供任何写账本的能力</b>
     * （grant / revoke 保持原样，只有主人能用）。</p>
     *
     * <p><b>条目的两条口径</b>：命中条目的筛选一律用账本自己的 {@link Acl#rulesFor(Caller)}
     * （主体层级 &gt; 0）+ {@link Acl.Entry#scopeHits(sair.v4.auth.Res)}，位与来源一律用
     * {@link Acl#bitsOf} / {@link Acl#source} —— <b>不在这里另算一套判定</b>。
     * 范围留空（整类）时没有单一"有效位"可言：这时用该类的<b>整类样本资源</b>
     * （{@link #probeRes}，与 {@code whoami} 同一份口径）算出"范围写空串的条目才命中"的那一格，
     * 并在回执里点明它是整类视图。</p>
     *
     * @param acl 权限账本（{@code null} = 没装配：默认分配是静态表，照样答得出）
     * @param c   调用者（{@code null} = 无主体，fail-closed）
     */
    private static String permCheck(JsonObject args, Acl acl, Caller c) {
        String rawP = J.s(args, "principal", "").trim();
        String clsArg = J.s(args, "cls", "").trim();
        String scopeArg = J.s(args, "scope", "");
        boolean master = c != null && c.master();
        // ① 要查谁：留空 = 自己
        String selfKey = selfKeyOf(c);
        String pk;
        if (Str.blank(rawP)) {
            if (selfKey == null) {
                if (master) {          // 主人（本地控制台）可能没有 QQ 号：主人的位恒定，直接说清怎么查
                    return "你是主人（MASTER 恒全权，任何例外条目都不影响你）—— 要查谁就把 principal 写上"
                            + "（User<QQ号> / Group<群号> / SYSTEM / ALLUSER）。";
                }
                return Acl.DENY_PREFIX + "查不了：这一轮没有可用的主体身份（没有 QQ 号，也不是她自己）"
                        + " —— 非主人只能查自己";
            }
            pk = selfKey;
        } else {
            pk = principalKeyOf(rawP);
            if (pk == null) {
                String why = Acl.principalReason(rawP);
                return "主体写法认不出：" + rawP + "\n  " + (Str.blank(why)
                        ? "主体只认 User<QQ号> / Group<群号> / SYSTEM / ALLUSER（裸数字 = User<QQ>）；"
                          + "principal 留空 = 查你自己" : why);
            }
        }
        // ② 权限边界：查别人必须 MASTER；非主人只能查自己
        if (!master && !pk.equals(selfKey)) {
            return Acl.DENY_PREFIX + "查别人的权限要 MASTER（你查的是 " + pk + "）—— 非主人只能查自己";
        }
        // ③ 类别
        char cls = clsOf(clsArg);
        if (cls == 0) return clsProblem(clsArg);
        // ④ 判定身份 + 资源（整类 = 该类的整类样本，只有范围写空串的条目命中它）
        Caller who = callerOf(pk);
        if (who == null) return "造不出这个主体的判定身份：" + pk;
        boolean whole = Str.blank(scopeArg);
        sair.v4.auth.Res r = whole ? probeRes(acl, cls) : resOf(cls, scopeArg);
        char judged = r == null ? cls : r.cls();          // 类别是自动判的：以真资源的类为准（与 permAcl 同口径）

        StringBuilder sb = new StringBuilder();
        sb.append("check：").append(pk).append("（判定身份 ").append(who.kind().name()).append("）· ")
          .append(clsName(cls)).append(" · 范围=").append(whole ? "整类（空串）" : scopeArg.trim());
        sb.append("\n  资源：").append(r == null ? "(这个类在当前进程里没有可用的样本资源)" : r.toString());
        if (r != null && judged != cls) {
            sb.append("  ← 按自动判定属于 ").append(clsName(judged)).append("（不是 ").append(cls)
              .append(" 类），下面按 ").append(judged).append(" 类算");
        }
        sb.append("\n  默认分配：").append(judged).append("=").append(bitsText(Acl.allocOf(who.kind(), judged)))
          .append(who.kind() == Caller.Kind.MASTER ? "（MASTER 恒全权）" : "（一条例外都没命中时按类默认）");
        int eff = (acl == null || r == null) ? Acl.allocOf(who.kind(), judged) : acl.bitsOf(who, r);
        sb.append("\n  有效位：").append(Bits.format(eff))
          .append(eff == Bits.NONE ? "（一位都没有）" : "").append("（掩码 ").append(eff).append("）");
        sb.append("\n  位从哪来：").append(acl == null || r == null
                ? "账本没装配（按默认分配）" : acl.source(who, r));

        // 命中的例外条目：只看他名下的（主体层级 > 0），再看范围命中不命中
        List<Acl.Entry> hit = new ArrayList<Acl.Entry>();
        if (acl != null) {
            for (Acl.Entry e : acl.rulesFor(who)) {
                if (e.cls() != judged) continue;
                if (!whole && r != null && !e.scopeHits(r)) continue;
                hit.add(e);
            }
        }
        sb.append("\n  命中的例外条目：");
        if (hit.isEmpty()) sb.append("一条都没有（这个类里没有他名下的条目）");
        for (int i = 0; i < hit.size(); i++) {
            Acl.Entry e = hit.get(i);
            sb.append("\n    ").append(i + 1).append(". ").append(e.raw())
              .append("   [主体 ").append(e.tierName()).append("]")
              .append(" -> 例外命中时生效 ").append(Bits.format(acl.effective(e)));
        }
        // 受保护资源（两层：第一层只有主人，第二层非主人恒不给）
        String prot = "不是受保护资源";
        if (acl != null && r != null) {
            if (acl.masterOnlyRes(r)) {
                prot = "第一层受保护资源（账本自己 / 授权动作 / 账号凭据）—— 只有 MASTER 拿得到"
                        + "（连她自己的自主行为也不给）";
            } else if (acl.outsiderBlockedRes(r)) {
                prot = acl.secondLayerCoreRes(r)
                        ? "第二层受保护资源（她自己的身份与配置：prompts/** 等）—— 非主人一律读不到（例外也不开）"
                        : "第二层受保护资源（受限配置 / 脚本 / 密钥材料后缀，只算 SFW 内的 B 类）"
                          + "—— 非主人一律读不到（例外也不开）";
            }
        }
        sb.append("\n  受保护资源：").append(prot);
        if (whole) {
            sb.append("\n  （范围＝整类：整类样本只反映范围写空串的条目；要某一格的确切位请给 scope，"
                    + "例如 scope=D:/share / scope=memory / scope=perm / scope=send_group_msg）");
        }
        if (acl == null) sb.append("\n  （权限账本没装配：有效位就是默认分配，例外一条都没有）");
        sb.append("\n  规则：层级优先 User(3) > Group(2) > ALLUSER(1)（SYSTEM 自成一路）；"
                + "同层级按行序，后面的覆盖前面的；范围的具体度不参与优先级。");
        return sb.toString();
    }

    /**
     * {@code check} 里"查自己"的那个主体键：她自己（{@code Kind.SYSTEM}）→ {@code SYSTEM}；
     * 其余（主人 / 普通用户）→ {@code User<QQ>}；没有 QQ 号又没有 SYSTEM 标志 → {@code null}
     * （调用方按 fail-closed 处理）。
     */
    private static String selfKeyOf(Caller c) {
        if (c == null) return null;
        if (c.kind() == Caller.Kind.SYSTEM) return Acl.SYSTEM_KEY;
        return c.qq() > 0L ? (Acl.USER_PREFIX + c.qq()) : null;
    }

    /** "帮我看看123456现在有什么权限"：他的全部条目 + 每条的生效位 + 默认分配对比。 */
    private static String principalReport(Acl acl, String p) {
        Caller.Kind kind = kindOf(p);
        List<Acl.Entry> mine = acl.rulesOf(p);
        StringBuilder sb = new StringBuilder();
        sb.append(p).append(" 的例外条目（按账本顺序）：");
        if (mine.isEmpty()) sb.append("一条都没有 —— 一切按默认分配走");
        for (int i = 0; i < mine.size(); i++) {
            Acl.Entry e = mine.get(i);
            sb.append("\n  ").append(i + 1).append(". ").append(e.raw())
              .append("   [主体 ").append(e.tierName()).append("]")
              .append(" -> 例外命中时生效 ").append(Bits.format(acl.effective(e)))
              .append("（该类默认分配 ").append(e.cls()).append("=")
              .append(Bits.format(Acl.allocOf(kind, e.cls()))).append("）");
        }
        sb.append("\n  他/她的默认分配（一条例外都没命中时）：").append(allocText(kind));
        sb.append("\n  要看某个具体资源上到底是多少位：op=acl principal=").append(p).append(" cls=A scope=D:/share");
        sb.append("\n  （群条目只在该群会话里生效；要看群里的结果就把主体写成 Group<群号>）");
        return sb.toString();
    }

    /**
     * {@code whoami}：调用者的身份（{@code kind}）+ 五类有效位。<b>谁都能问</b> ——
     * 它只回"位"和"这一格的位从哪来（例外 / 默认分配 / 受保护）"，
     * <b>绝不回账本条目原文</b>（账本正文是主人的信息），也不对外人报内部路径。
     */
    private static String permWhoami(Acl acl, Caller c) {
        JsonObject me = new JsonObject();
        if (c == null) {
            me.addProperty("caller", "无主体（fail-closed：五类都拿不到）");
            return J.json(me);
        }
        boolean master = c.master();
        me.addProperty("kind", c.kind().name().toLowerCase(java.util.Locale.ROOT));   // master/system/alluser
        me.addProperty("entry", c.isConsole() ? "console" : "qq");
        me.addProperty("qq", c.qq());
        me.addProperty("session", c.session());
        me.addProperty("master", master);
        me.addProperty("system", c.system());
        // 好感度只决定热情度，不插手权限；主人与本地控制台不显示
        me.addProperty("favor", (master || c.isConsole()) ? -1 : (long) c.favor());
        if (c.isGroup()) {
            me.addProperty("group", c.groupId());
            me.addProperty("role", c.groupRole());
        }
        JsonObject alloc = new JsonObject();      // 默认分配（按身份取表）
        JsonObject bits = new JsonObject();       // 有效位（样本见 sample）
        JsonObject from = new JsonObject();       // 这一格的位从哪来（有例外 / 默认分配 / 受保护）
        JsonObject sample = new JsonObject();
        char[] cs = { 'A', 'B', 'C', 'E', 'T' };
        for (int i = 0; i < cs.length; i++) {
            char cls = cs[i];
            String k = String.valueOf(cls);
            int def = Acl.allocOf(c.kind(), cls);
            sair.v4.auth.Res r = probeRes(acl, cls);
            alloc.addProperty(k, Bits.format(def));
            bits.addProperty(k, Bits.format(acl == null || r == null ? def : acl.bitsOf(c, r)));
            from.addProperty(k, fromText(acl, c, r));
            sample.addProperty(k, probeName(cls, r, master));
        }
        me.add("alloc", alloc);
        me.add("bits", bits);
        me.add("from", from);
        me.add("sample", sample);
        me.addProperty("note", "alloc = 按身份取的五类默认分配；bits = 用 Acl 判定出来的有效位（样本见 sample："
                + "A/C/E/T 四格是「整类（范围=空串）」样本，只反映范围写空串的条目；B 那一格以数据根为样本），"
                + "from = 这一格的位从哪来。要看某个具体路径/库/工具/动作上到底是多少位，请主人用 op=acl 验算。");
        if (acl == null) me.addProperty("ledger", "权限账本没装配（bits 就是默认分配）");
        return J.json(me);
    }

    /** 五类有效位的样本资源（A/C/E/T 用"空范围"＝整类样本；B 用数据根，{@code Res} 没有空范围的 B 工厂）。 */
    private static sair.v4.auth.Res probeRes(Acl acl, char cls) {
        if (cls == 'A') return sair.v4.auth.Res.path("");
        if (cls == 'B') return acl != null && acl.dataRoot() != null ? sair.v4.auth.Res.path(acl.dataRoot()) : null;
        if (cls == 'C') return sair.v4.auth.Res.db("");
        if (cls == 'E') return sair.v4.auth.Res.platform("");
        if (cls == 'T') return sair.v4.auth.Res.tool("");
        return null;
    }

    /** 这一格的位从哪来（<b>只说来源，不抄账本条目原文</b>）。 */
    private static String fromText(Acl acl, Caller c, sair.v4.auth.Res r) {
        if (c != null && c.kind() == Caller.Kind.MASTER) return "MASTER 恒全权（任何条目都不影响主人）";
        if (acl == null || r == null) return "账本没装配（按默认分配）";
        if (acl.masterOnlyRes(r)) return "第一层受保护资源（只有主人拿得到）";
        if (c != null && c.kind() == Caller.Kind.ALLUSER && acl.outsiderBlockedRes(r)) {
            return "第二层受保护资源（外人恒不给）";
        }
        if (acl.hit(c, r) != null) return "例外条目（有例外命中这一格的样本）";
        return c != null && c.kind() == Caller.Kind.SYSTEM ? "她自己的默认分配" : "默认分配（没有例外命中）";
    }

    /** 样本资源的人话名（{@code whoami} 里跟着 bits 一起给，免得数字被误读；对外人不报内部路径）。 */
    private static String probeName(char cls, sair.v4.auth.Res r, boolean master) {
        if (r == null) return "(无样本)";
        if (cls == 'B') {
            return master ? ("数据根 " + r.target() + "（自动判定 " + r.cls() + " 类）")
                          : ("数据根（自动判定 " + r.cls() + " 类）");
        }
        return "整类（范围=空串）";
    }

    // ==================== ⑥b/⑥c 自省：tools（能力）/ config（配置） ====================
    //
    // 这两把是主人要的那条工作流的"机械化依据"：
    //   知道该用什么工具 → 不知道就查（tools list/search）→ 读说明书（tools show）
    //   → 验证行不行（show 里按 auth.bits 现算的那一格）→ 行就做 / 不行就直说 → 给结论。
    // 两条共同的边界：
    //   ① 能力面只列 Registry.visible(c) —— 不给不该看的人暴露工具面；
    //   ② 判定一律现算（auth.bits / auth.allowRes），基板不缓存、不猜、不编。

    /** {@code tools} 的契约正文（她自己读的就是这一段）。 */
    public static final String TOOLS_DESC =
            "能力自省（找工具 / 读说明书 / 验证行不行）：list 列**你现在能用**的工具（名 + 一句话 + 归属）；"
            + "search 按**意图**找（「改设置」「发图」「查余额」）；show <工具名> 读完整说明书："
            + "描述、返回、**参数表**（类型/必填/默认/示例）、归属、**★以你现在的身份能不能用（缺哪一位也说清）**、"
            + "只读还是有副作用。\n"
            + "想不起工具名、不确定 op 怎么填、想确认「我这身份调不调得动」—— 先问我，别硬试"
            + "（撞权限墙会被连续失败闸门掐停整轮）。\n"
            + "边界：只列你**现在看得见**的工具（工具面按 T 类的 X 位筛）；看不见的我不替你描述。";

    /** {@code config} 的契约正文。 */
    public static final String CONFIG_DESC =
            "基板配置自省（**改设置只有主人**）：list 看所有已知键在 **config.json 里**的值；get <键> 看一个键；"
            + "set <键> <值> 改一个键（**只写 config.json 这个文件**）。\n"
            + "★ 生效口径：改配置 = 只改文件，**跑着的基板一个键都不动**；要生效只有 "
            + "`ai/start`（没在跑）或 `ai/restart`（已经在跑）。文件值与已生效值不一样时，"
            + "list/get 标「待生效（要 ai/start）」。\n"
            + "可改：**轮次上限**（agentMaxRounds 主 / subagentMaxRounds 子）、**开关**"
            + "（napcatEnabled / relayEnabled / prefInject / extEnabled / logTools …）、"
            + "**家规类参数**（保留期 keepDays / dialogKeepDays / grouplogKeepDays、低重要度上限 maxLowImportance）、"
            + "**维护周期**（maintainEveryHours）、以及各类预算与条数上限（toolResultMax / ctxBudgetChars / "
            + "chatWindowSize / identityMaxChars …）。config.json 里没写、只靠默认值生效的键也在 list 里。\n"
            + "敏感键（apiKey / napcatToken / relayToken）**只回「已设置 / 长度」，绝不回值**。\n"
            + "非主人问设置、或让别人替你改：一律不答应（改配置 = 改基板边界，只有主人有权）—— "
            + "回「这得主人定」。";

    /** {@code tools} 的 op（报错文案与 schema 共用一份）。 */
    private static final String TOOLS_OPS = "list 工具清单 · search 按意图找 · show 读某一把的说明书";

    /** {@code config} 的 op。 */
    private static final String CONFIG_OPS = "list 全部已知键 · get 看一个键 · set 改一个键（只有主人）";

    /** {@code tools op=list} 里"一句话"的长度上限（描述是自由文本，索引/清单都压成一行）。 */
    private static final int TOOLS_LINE_MAX = 80;

    /** {@code tools op=search} 最多回几把（"最像的几把"，不是倒清单）。 */
    private static final int TOOLS_SEARCH_MAX = 5;

    /**
     * <b>{@code tools}</b>：找得到 / 看得懂 / 知道能不能用。
     *
     * @param auth 权限面（{@code null} = 没装配：那一格如实说"算不出"，<b>不编</b>）
     * @param c    调用者（{@code null} = 无主体）
     */
    public static String toolsTool(JsonObject args, Out out, Auth auth, Conf conf, Registry reg, Caller c) {
        if (reg == null) return "工具注册表没装配（拿不到任何工具）";
        String op = J.s(args, "op", "list").trim().toLowerCase();
        if ("list".equals(op)) return toolsList(reg, c, J.s(args, "keyword", ""));
        if ("search".equals(op)) return toolsSearch(reg, c, J.s(args, "query", ""));
        if ("show".equals(op)) return toolsShow(args, out, auth, conf, reg, c);
        return "op 只认 " + TOOLS_OPS + "。";
    }

    /** {@code tools op=list [关键词]}：**这个调用者现在能用的**工具（名字 + 一句话 + 归属）。 */
    private static String toolsList(Registry reg, Caller c, String keyword) {
        List<Tool> vis = reg.visible(c);
        String kw = Str.trim(keyword);
        StringBuilder sb = new StringBuilder();
        int hit = 0;
        for (Tool t : vis) {
            if (t == null) continue;
            if (!kw.isEmpty()) {
                String hay = (t.name() + " " + Str.nz(t.desc()) + " " + Str.nz(t.owner())).toLowerCase();
                if (hay.indexOf(kw.toLowerCase()) < 0) continue;
            }
            hit++;
            sb.append("\n· ").append(t.name()).append(" —— ").append(oneLineOrDash(t.desc()))
              .append("  [").append(ownerText(t)).append("]");
        }
        StringBuilder head = new StringBuilder();
        head.append("工具清单（").append(c == null ? "无主体（诊断视图：全量）" : callerText(c)).append("）");
        if (kw.isEmpty()) head.append("：").append(vis.size()).append(" 个");
        else head.append("：关键词「").append(kw).append("」命中 ").append(hit).append(" / ").append(vis.size());
        if (vis.isEmpty()) {
            return head + "\n（你现在一把都用不了：工具面按 **T 类的 X 位**筛 —— 没有这一位就连工具名都看不到。"
                    + "要放开只能由主人写例外条目，例如 T[\"User<QQ>\",\"tool:send\",\"X\"]）";
        }
        if (hit == 0) {
            return head + "\n（没有匹配的工具；不带关键词看全部：tools op=list）";
        }
        head.append("\n· 每把的完整说明书：tools op=show name=<工具名>（含参数表与「你这身份能不能用」）");
        return head.append(sb).toString();
    }

    /** {@code tools op=search <意图词>}：按意图找 —— 匹配工具名 / 描述 / 归属 / 自报的 op 取值 / 用法示例。 */
    private static String toolsSearch(Registry reg, Caller c, String query) {
        String q = Str.trim(query);
        if (q.isEmpty()) {
            return "search 要一个意图词（例如「改设置」「发图」「查余额」）；只看清单用 tools op=list。";
        }
        List<Tool> vis = reg.visible(c);
        List<Object[]> scored = new ArrayList<Object[]>();      // {Tool, score, 命中片段, 命中在哪}
        for (Tool t : vis) {
            if (t == null) continue;
            Object[] m = match(t, q);
            if (m == null) continue;
            scored.add(new Object[] { t, m[0], m[1], m[2] });
        }
        java.util.Collections.sort(scored, new java.util.Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                int x = ((Integer) b[1]).compareTo((Integer) a[1]);
                if (x != 0) return x;
                return ((Tool) a[0]).name().compareToIgnoreCase(((Tool) b[0]).name());
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append("按意图找「").append(q).append("」（匹配范围：工具名 / 描述 / 归属 / 自报的 op 取值 / 用法示例）");
        if (scored.isEmpty()) {
            sb.append("\n（一把都没匹配上）—— 换个说法再搜，或者 tools op=list 看全部；"
                    + "确实没有对应能力 = 你现在做不了这件事，如实说，别绕。");
            return sb.toString();
        }
        int n = Math.min(TOOLS_SEARCH_MAX, scored.size());
        sb.append("：最像的 ").append(n).append(" 把（共 ").append(vis.size()).append(" 把可见）");
        for (int i = 0; i < n; i++) {
            Object[] row = scored.get(i);
            Tool t = (Tool) row[0];
            sb.append("\n").append(i + 1).append(". ").append(t.name()).append(" —— ")
              .append(oneLineOrDash(t.desc()))
              .append("  [").append(ownerText(t)).append("]")
              .append("\n   命中：").append(row[3]).append("「").append(row[2]).append("」");
        }
        sb.append("\n要看完整说明书（参数表 + 能不能用）：tools op=show name=<工具名>");
        return sb.toString();
    }

    /** {@code tools op=show <工具名>}：完整说明书（含"以你现在的身份能不能用"）。 */
    private static String toolsShow(JsonObject args, Out out, Auth auth, Conf conf, Registry reg, Caller c) {
        String name = J.s(args, "name", "").trim();
        if (name.isEmpty()) return "show 要工具名（tools op=show name=send）；名字不准就先 tools op=search query=<意图词>。";
        Tool t = reg.get(name);
        if (t == null) {
            // 名字不存在：走注册表自己的候选机制（只在**可见**工具里找），与"喊错工具名"同一口径
            List<Tool> near = reg.nearestVisible(name, c);
            StringBuilder sb = new StringBuilder("没有名为 ").append(name).append(" 的工具。");
            if (near.isEmpty()) {
                sb.append("\n（你手上有什么：tools op=list）");
            } else {
                sb.append("最接近的是：");
                for (Tool x : near) sb.append("\n· ").append(x.name()).append(" —— ").append(oneLineOrDash(x.desc()));
            }
            return sb.toString();
        }
        boolean visible = containsTool(reg.visible(c), name);
        StringBuilder sb = new StringBuilder();
        sb.append("工具：").append(t.name()).append("\n");
        sb.append("归属：").append(ownerText(t)).append("\n");
        // ★ 能不能用 —— 一律现算（这就是"验证行不行"的机械化依据）
        sb.append(authLine(auth, c, t)).append("\n");
        // 上一次"你本人"调它被拒的原文（没有就不出现这一行 —— 绝不编）
        String deny = reg.lastDeny(c, name);
        if (Str.has(deny)) sb.append("上次调用被拒（你本人）：").append(deny).append("\n");
        if (!visible) {
            // 看不见 = 不描述（工具面按 T:X 筛，说明书也是能力面的一部分）
            sb.append("说明书：**不给** —— 你现在看不到这把工具（见上面那一格缺的位）。"
                    + "拿到位之后再来看；要看你手上有什么：tools op=list。");
            return sb.toString();
        }
        sb.append("描述：").append(Str.nz(t.desc()).trim().isEmpty() ? "(未标注)" : Str.oneLine(t.desc())).append("\n");
        sb.append("返回：").append(Str.nz(t.returns()).trim().isEmpty() ? "(未标注)" : Str.oneLine(t.returns())).append("\n");
        sb.append(sideEffectLine(t)).append("\n");
        sb.append(paramTable(t));
        List<String> ex = t.examples();
        if (ex != null && !ex.isEmpty()) {
            sb.append("用法示例：");
            for (int i = 0; i < ex.size(); i++) sb.append("\n  · ").append(Str.oneLine(ex.get(i)));
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 说明书里"★以你现在的身份能不能用"那一格 —— <b>现算</b>
     * （{@code auth.bits(c, Res.tool(名))}：工具是 T 类资源，入口要的是 {@code X} 位）。
     */
    private static String authLine(Auth auth, Caller c, Tool t) {
        if (auth == null) {
            return "★ 能不能用：算不出 —— 权限面没装配（auth == null）。基板不编一个结论给你。";
        }
        if (c == null) {
            return "★ 能不能用：不能用 —— 这一轮没有调用者身份（fail-closed：没有主体的调用一律拒）。";
        }
        sair.v4.auth.Res res = sair.v4.auth.Res.tool(t.name());
        int bits;
        try {
            bits = auth.bits(c, res);
        } catch (Throwable e) {
            return "★ 能不能用：算不出 —— 判定抛异常（" + e + "）；基板不编结论。";
        }
        String alloc = Bits.format(Acl.allocOf(c.kind(), 'T'));
        boolean ok = Bits.has(bits, 'X');
        StringBuilder sb = new StringBuilder();
        sb.append("★ 能不能用：").append(ok ? "**能用**" : "**不能用**");
        sb.append("（你 = ").append(callerText(c)).append("；工具入口要的是 T 类的 `X` 位）\n");
        sb.append("   T 类有效位：").append(Bits.format(bits));
        if (!ok) sb.append("  ← **缺 X 位**（没有它 = 这把工具连「交到手」都不给）");
        sb.append("（该类默认分配 T=").append(alloc).append("）\n");
        sb.append("   位从哪来：").append(fromText(auth.acl(), c, res));
        return sb.toString();
    }

    /**
     * 说明书里的"只读 / 有副作用"那一格。
     *
     * <p><b>判据只有"声明的动作名"这一条结构事实</b>（工具自报的 {@code op} 取值表）：
     * 全都落在读动作里 ⇒ 只读；出现写/动作名 ⇒ 有副作用；认不出 ⇒ {@code 未标注}。
     * 认不出时<b>不猜</b>（不拿名字/描述做联想），因为猜错比不答更坏 —— 这一格的用途是"要不要防着点"。</p>
     */
    private static String sideEffectLine(Tool t) {
        List<String> ops = declaredOps(t);
        if (ops.isEmpty()) {
            if (sair.v4.agent.Loop.speaks(t.name())) {
                return "只读/副作用：**有副作用**（对外发送类：框架自己的 speaks 表里有它）";
            }
            return "只读/副作用：未标注（这把工具没声明 op/action 取值表，看不出读还是写）";
        }
        List<String> read = new ArrayList<String>();
        List<String> act = new ArrayList<String>();
        List<String> unk = new ArrayList<String>();
        for (String op : ops) {
            String o = op.toLowerCase();
            if (READ_OPS.contains(o)) read.add(op);
            else if (ACTION_OPS.contains(o)) act.add(op);
            else unk.add(op);
        }
        StringBuilder sb = new StringBuilder("只读/副作用：");
        if (act.isEmpty() && unk.isEmpty()) {
            sb.append("**只读**（声明的动作全是读：").append(Str.join(ops, "/")).append("）");
        } else if (unk.isEmpty()) {
            sb.append("**有副作用**（声明的动作里有写/对外动作：").append(Str.join(act, "/"));
            if (!read.isEmpty()) sb.append("；只读的：").append(Str.join(read, "/"));
            sb.append("）");
        } else {
            sb.append("未标注（声明的动作里认不出读写的：").append(Str.join(unk, "/"));
            if (!act.isEmpty()) sb.append("；写/对外的：").append(Str.join(act, "/"));
            if (!read.isEmpty()) sb.append("；只读的：").append(Str.join(read, "/"));
            sb.append("）");
        }
        return sb.toString();
    }

    /** 声明面的"读"动作名（只用来回答上面那一格，不参与任何权限判定）。 */
    private static final List<String> READ_OPS = java.util.Arrays.asList(
            "list", "get", "search", "count", "stat", "read", "read_source", "show", "info", "status",
            "history", "sections", "validate", "balance", "models", "acl", "check", "whoami", "visible", "drafts");

    /** 声明面的"写 / 对外"动作名。 */
    private static final List<String> ACTION_OPS = java.util.Arrays.asList(
            "put", "update", "delete", "add", "set", "remove", "clear", "run", "grant", "revoke", "spawn", "stop",
            "inject", "load", "write", "import", "export", "optimize", "vacuum", "maintain", "reload", "promote",
            "draft", "chat", "vision", "print", "send", "collect", "remark", "recall", "see",
            "group", "private", "file", "fileto", "record", "forward", "relay");

    /** 工具自报的 op / action / kind 取值表（没有就空表）。 */
    private static List<String> declaredOps(Tool t) {
        List<String> out = new ArrayList<String>();
        JsonObject props = t == null ? null : J.sub(t.params(), "properties");
        if (props == null) return out;
        String[] keys = { "op", "action", "kind" };
        for (int i = 0; i < keys.length; i++) {
            JsonObject node = J.sub(props, keys[i]);
            JsonArray en = node == null ? null : J.list(node, "enum");
            if (en == null) continue;
            for (com.google.gson.JsonElement e : en) {
                if (e == null || e.isJsonNull()) continue;
                String v = e.getAsString();
                if (Str.has(v) && !out.contains(v)) out.add(v);
            }
        }
        return out;
    }

    /**
     * 参数表：参数名 / 类型 / 必填 / 默认值 / 示例（<b>示例只从两处取</b>：
     * schema 里的 {@code example} 键，或技能 md 折进描述里的 {@code 示例：xxx}；
     * 都没有就从工具的用法示例里抽 {@code 参数名=值}。抽不到就留空 —— 不编一个例子）。
     */
    private static String paramTable(Tool t) {
        JsonObject params = t.params();
        JsonObject props = params == null ? null : J.sub(params, "properties");
        if (props == null || props.size() == 0) {
            return "参数：无（这把工具不吃参数）\n";
        }
        List<String> req = new ArrayList<String>();
        JsonArray required = J.list(params, "required");
        if (required != null) {
            for (com.google.gson.JsonElement e : required) {
                if (e != null && !e.isJsonNull()) req.add(e.getAsString());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("参数（").append(props.size()).append(" 个；★ = 必填）：");
        for (Map.Entry<String, com.google.gson.JsonElement> e : props.entrySet()) {
            String name = e.getKey();
            JsonObject node = e.getValue() != null && e.getValue().isJsonObject()
                    ? e.getValue().getAsJsonObject() : new JsonObject();
            String desc = J.s(node, "description", "");
            String example = J.s(node, "example", "");
            int at = desc.lastIndexOf("示例：");
            if (Str.blank(example) && at >= 0) {
                example = desc.substring(at + 3).trim();
                desc = desc.substring(0, at).trim();
            }
            if (Str.blank(example)) example = exampleFrom(t.examples(), name);
            JsonArray en = J.list(node, "enum");
            StringBuilder line = new StringBuilder();
            line.append("\n  ").append(req.contains(name) ? "★" : " ").append(name)
                .append("  ").append(Str.nz(J.s(node, "type", "string")));
            if (req.contains(name)) line.append("  必填");
            if (node.has("default") && !node.get("default").isJsonNull()) {
                line.append("  默认 ").append(node.get("default").getAsString());
            }
            if (en != null && en.size() > 0) {
                List<String> vals = new ArrayList<String>();
                for (com.google.gson.JsonElement x : en) if (x != null && !x.isJsonNull()) vals.add(x.getAsString());
                String joined = Str.join(vals, "|");
                line.append("  取值 ").append(joined);
                // 描述就是把取值表抄一遍的（`op` 的常见写法）：不重复打印第二遍
                if (joined.replace('|', '/').equals(Str.trim(desc))) desc = "";
            }
            if (Str.has(desc)) line.append("  ").append(Str.cut(Str.oneLine(desc), 120));
            line.append("  示例：").append(Str.has(example) ? Str.oneLine(example) : "(未标注)");
            sb.append(line);
        }
        return sb.append("\n").toString();
    }

    /** 从工具的用法示例里抽 {@code <参数名>=<值>}（抽不到返回空串）。 */
    private static String exampleFrom(List<String> examples, String name) {
        if (examples == null || Str.blank(name)) return "";
        for (String s : examples) {
            String line = Str.oneLine(s);
            int at = line.indexOf(name + "=");
            if (at < 0) continue;
            String rest = line.substring(at + name.length() + 1).trim();
            int end = rest.length();
            char[] stop = { ' ', '，', '；', '）', ')', '、', ',', ';' };
            for (int i = 0; i < rest.length(); i++) {
                for (int j = 0; j < stop.length; j++) {
                    if (rest.charAt(i) == stop[j]) { end = i; break; }
                }
                if (end != rest.length()) break;
            }
            String v = rest.substring(0, end).trim();
            if (Str.has(v)) return v;
        }
        return "";
    }

    /** 一个工具在清单里的"一句话"（没有描述 = 留空，不编）。 */
    private static String oneLineOrDash(String desc) {
        String d = Str.oneLine(Str.nz(desc));
        return Str.blank(d) ? "" : Str.cut(d, TOOLS_LINE_MAX);
    }

    /** 归属的人话写法。 */
    private static String ownerText(Tool t) {
        return t.skill() ? ("技能：" + Str.nz(t.owner())) : "基板";
    }

    /** 调用者的人话写法（含主体种类）。 */
    private static String callerText(Caller c) {
        if (c == null) return "无主体";
        return c.label() + "，种类 " + c.kind().name();
    }

    private static boolean containsTool(List<Tool> list, String name) {
        if (list == null || name == null) return false;
        for (Tool t : list) if (t != null && name.equals(t.name())) return true;
        return false;
    }

    /**
     * {@code tools op=search} 的一把工具命中情况。
     *
     * <p><b>匹配是"拿你自己给的那几个字去比"</b>（不靠关键词表）：先看整串在不在，
     * 不在就把查询串拆成 2~4 字的片段，看最长的那个片段落在哪个字段里 ——
     * 所以「查余额」能靠「余额」命中 {@code model}，「发图」能靠「发图」命中 {@code sendimage}（归属「发图片」）。</p>
     *
     * @return {@code null} = 不命中；否则 {@code {得分, 命中片段, 命中在哪}}
     */
    private static Object[] match(Tool t, String q) {
        String[] fields = { "工具名", "描述", "返回", "归属", "op 取值", "用法示例" };
        String[] hay = new String[fields.length];
        hay[0] = t.name();
        hay[1] = Str.nz(t.desc());
        hay[2] = Str.nz(t.returns());
        hay[3] = t.skill() ? Str.nz(t.owner()) : "基板";
        hay[4] = Str.join(declaredOps(t), " ");
        List<String> ex = t.examples();
        hay[5] = ex == null ? "" : Str.join(ex, " ");
        int[] weight = { 5, 3, 2, 3, 2, 2 };
        int best = 0;
        String bestSlice = "";
        String bestWhere = "";
        for (int i = 0; i < hay.length; i++) {
            String piece = longestHit(q, hay[i]);
            if (piece.isEmpty()) continue;
            int sc = weight[i] * (piece.equals(q) ? 100 : piece.length());
            if (sc > best) {
                best = sc;
                bestSlice = piece;
                bestWhere = fields[i];
            }
        }
        if (best == 0) return null;
        return new Object[] { Integer.valueOf(best), bestSlice, bestWhere };
    }

    /** 查询串在 haystack 里命中的<b>最长片段</b>（整串命中直接返回整串；否则 4→2 字的片段）。 */
    private static String longestHit(String q, String hay) {
        String query = Str.trim(q);
        String text = Str.nz(hay);
        if (query.isEmpty() || text.isEmpty()) return "";
        if (text.indexOf(query) >= 0) return query;
        int max = Math.min(4, query.length() - 1);
        for (int len = max; len >= 2; len--) {
            for (int i = 0; i + len <= query.length(); i++) {
                String piece = query.substring(i, i + len);
                if (text.indexOf(piece) >= 0) return piece;
            }
        }
        // 单字查询（"图""钱"这类）：只有工具名/描述里真的有这个字才算，且权重最低
        if (query.length() == 1 && text.indexOf(query) >= 0) return query;
        return "";
    }

    /**
     * <b>{@code config}</b>：改哪儿必须可查（键名 + 生效值 + 即时/需重启）。
     *
     * @param auth 权限面（{@code null} = 没装配：{@code set} 一律拒）
     * @param c    调用者
     */
    public static String configTool(JsonObject args, Out out, Auth auth, Conf conf, Caller c) {
        if (conf == null) return "配置没装配（读不到数据根下的 config.json）";
        String op = J.s(args, "op", "list").trim().toLowerCase();
        if ("list".equals(op)) return configList(conf, c);
        if ("get".equals(op)) return configGet(conf, J.s(args, "key", ""));
        if ("set".equals(op)) return configSet(args, out, auth, conf, c);
        return "op 只认 " + CONFIG_OPS + "。";
    }

    /** {@code config op=list}：所有已知键 + **config.json 里**的值 + 待生效标记（<b>缺省生效的键也在里面</b>）。 */
    private static String configList(Conf conf, Caller c) {
        List<Conf.Key> keys = conf.fileKeys();
        List<Conf.Key> pending = new ArrayList<Conf.Key>();
        for (Conf.Key k : keys) if (k.pending()) pending.add(k);
        StringBuilder sb = new StringBuilder();
        sb.append("配置自省：已知键 ").append(keys.size()).append(" 个（其中待生效 ").append(pending.size())
          .append(" 个）");
        sb.append("\n值 = **config.json 里的值**（文件里没写的显示出厂默认）；"
                + "敏感键只回「已设置 / 长度」，绝不回值。");
        sb.append("\n★ 生效口径：改配置只改文件 —— 要让改动生效只有 `ai/start`（没在跑）或 "
                + "`ai/restart`（已经在跑）；下面标「待生效」的就是文件里已经改了、跑着的基板还没用的键。");
        sb.append("\n");
        for (int i = 0; i < keys.size(); i++) sb.append("\n  ").append(keyLine(keys.get(i)));
        sb.append("\n");
        // 绝对路径只给主人（非主人看到"配置文件在哪个盘"没有用处，属于内部路径）
        if (c != null && c.master()) sb.append("\n配置文件：").append(conf.file().getAbsolutePath());
        sb.append("\n只列**已知键**（有消费者读的键）：不在这张表里的名字写进去也没人读。"
                + "改一个键：config op=set key=<键> value=<值>（只有主人）。");
        return sb.toString();
    }

    /** 一行：{@code 键 = 值} + 待生效标记（敏感键打码）。 */
    private static String keyLine(Conf.Key k) {
        StringBuilder sb = new StringBuilder();
        sb.append(k.name()).append(" = ").append(maskValue(k));
        if (k.pending()) sb.append("   ← 待生效（要 ai/start；已经在跑则 ai/restart）");
        return sb.toString();
    }

    /** 敏感键只回"已设置 / 长度"（B10 硬口径：绝不回值）。 */
    private static String maskValue(Conf.Key k) {
        if (!k.sensitive()) return k.set() ? k.value() : "(未设置)";
        if (!k.set()) return "(未设置)";
        return "已设置（敏感键：不回值，共 " + k.length() + " 字符）";
    }

    /** {@code config op=get <键>}：**文件里**的值 + 与已生效值的差别（待生效就明说）。 */
    private static String configGet(Conf conf, String key) {
        String name = Str.trim(key);
        if (name.isEmpty()) {
            return "get 要一个键名（config op=get key=agentMaxRounds）；不知道有哪些键就先 config op=list。";
        }
        Conf.Key k = conf.fileKey(name);
        if (k == null) {
            // 不在已知键表里：如实说"没人读它"，并把配置里的原值报出来（敏感键同样不回值）
            String raw = conf.fileGet(name, null);
            StringBuilder sb = new StringBuilder();
            sb.append(name).append("：**不是已知键**（没有任何消费者读它 —— 写进去也不会有任何行为变化）。");
            if (raw == null) sb.append("\n  config.json 里也没有这个键。");
            else if (Conf.isSensitive(name)) sb.append("\n  config.json 里的值：已设置（敏感键：不回值，共 ")
                    .append(raw.length()).append(" 字符）");
            else sb.append("\n  config.json 里的原值：").append(raw);
            sb.append("\n  （已知键清单：config op=list）");
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        sb.append(k.name()).append(" = ").append(maskValue(k));
        sb.append("\n口径：**config.json 里的值**。改它只改文件 —— 要生效得 `ai/start`（没在跑）"
                + "或 `ai/restart`（已经在跑）。");
        if (k.pending()) {
            Conf.Key live = conf.knownKey(k.name());
            sb.append("\n★ 待生效：这个键**文件里已经改了**，但跑着的基板还是旧值（已生效：")
              .append(live == null ? "(未知)" : maskValue(live))
              .append("）—— 要 ai/restart（没在跑就是 ai/start）才按新值跑。");
        } else {
            sb.append("\n已生效：文件里的值与跑着的基板一致。");
        }
        sb.append("\n设置值：config op=set key=").append(k.name()).append(" value=<值>");
        if (Conf.isSensitive(k.name())) sb.append("（敏感键：回执也只回长度，不回值）");
        return sb.toString();
    }

    /**
     * {@code config op=set <键> <值>}：<b>只有主人</b>。
     *
     * <p>两道：① 资源位 —— {@code Res.tool("config")} 的 {@code W} 位（走 ACL 同一条 API，
     * 拒文与别处<b>一字不差同形</b>：{@code 缺 W 位 —— [权限阻断] …}）；
     * ② 显式 MASTER 复核 —— 改配置 = 改<b>基板边界</b>，连她自己的自主行为（SYSTEM，T=RWX 有 W）
     * 也不给（D43 第 11 条：边界定义权只有主人有）。</p>
     */
    private static String configSet(JsonObject args, Out out, Auth auth, Conf conf, Caller c) {
        String name = J.s(args, "key", "").trim();
        if (name.isEmpty()) {
            return "set 要键名与值：config op=set key=agentMaxRounds value=30（键名清单：config op=list）";
        }
        if (args == null || !args.has("value")) {
            return "set 要给 value（要清空就写 value=\"\"）：config op=set key=" + name + " value=<值>";
        }
        boolean master = c != null && c.master();
        // ① ACL 同一条 API（T 类的 W 位 = 改/注册工具的位）：非主人默认 T=NONE ⇒ 拒文与别处同形
        if (auth == null) {
            return Acl.DENY_PREFIX + "改配置需要权限面（auth == null：没装配）—— 拒绝 " + name;
        }
        String aclDeny = auth.allowRes(c, sair.v4.auth.Res.tool("config"), 'W');
        if (aclDeny != null) return aclDeny;
        // ② 只有主人（把"她自己"也挡在外面：边界定义权在她之外）
        if (!master) {
            if (out != null) out.dim("[config] 非主人调用 set（" + (c == null ? "无主体" : c.label()) + "），已拒");
            return Acl.DENY_PREFIX + "改配置只有主人能做（你 = " + callerText(c)
                    + "）—— 基板边界只有主人有权力改；要改请让主人来说。";
        }
        String raw = J.s(args, "value", "");
        Object val = parseValue(raw);
        Conf.Key known = conf.knownKey(name);
        // 新口径：**只写 config.json 这个文件** —— 内存里的生效值一个字段都不动。
        boolean saved = conf.fileSet(name, val);
        StringBuilder sb = new StringBuilder();
        if (known != null && known.sensitive()) {
            // 敏感键：回执也只回"已设置 + 长度"，绝不把值搬进结果
            sb.append("已写入 config.json：").append(name).append("（敏感键：不回值，共 ")
              .append(raw.length()).append(" 字符）");
        } else {
            sb.append("已写入 config.json：").append(name).append(" = ")
              .append(Str.blank(raw) ? "(空)" : raw);
        }
        sb.append(saved ? "" : "（**没落盘**：写 config.json 失败，看控制台那行告警）");
        sb.append("（未生效 —— 要 ai/start；已在运行则 ai/restart）");
        // 回读：读的是**文件**，并与跑着的基板比一比（不一样 = 待生效）
        Conf.Key after = conf.fileKey(name);
        if (after != null) {
            sb.append("\n回读（config.json）：").append(name).append(" = ").append(maskValue(after))
              .append(after.pending() ? "   ← 待生效（跑着的基板还是旧值，要 ai/restart）"
                                      : "   （已生效）");
        }
        if (known == null) {
            sb.append("\n注意：`").append(name).append("` 不在已知键表里（没有消费者读它）—— "
                    + "写进去了，但不会有任何行为变化；要用得先有代码读它。");
        }
        return sb.toString();
    }

    /** {@code set} 的值解析（与 {@code term.Cmd} 的 {@code config set} 同一套：整数/小数/布尔/字符串）。 */
    private static Object parseValue(String raw) {
        String v = raw == null ? "" : raw.trim();
        if (v.matches("-?\\d+")) {
            try {
                return Long.valueOf(Long.parseLong(v));
            } catch (Throwable ignore) {
                return v;
            }
        }
        if (v.matches("-?\\d+\\.\\d+")) {
            try {
                return Double.valueOf(Double.parseDouble(v));
            } catch (Throwable ignore) {
                return v;
            }
        }
        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) return Boolean.valueOf(v);
        return v;
    }

    // ==================== ACL 判定（C 数据 / T 工具 / A·B 路径 / E 动作） ====================

    /**
     * 内置工具侧的 ACL 判定主体：工具路径 = 当轮绑定的触发者（{@link sair.v4.ctx.Ctx#caller()}，
     * P1 已让工具路径绑触发者）。
     *
     * <p><b>拿不到触发者 = fail-closed</b>：直接返回 {@code null}，由 {@link Acl#allow} 对它回
     * "缺少调用者身份"拒绝 —— <b>绝不回落到 SYSTEM</b>。回落 SYSTEM（C:RWX）等于把"无主体 = 放行"的
     * 旧洞从 E 类挪到 C 类（工人 2026-09-15 已标出）。她自己的自主行为（钩子/定时/装配）不经过这里，
     * 那些路径 P1 已显式绑 {@link Caller#systemActor}。</p>
     */
    private static Caller aclCaller(Conf conf) {
        return sair.v4.ctx.Ctx.caller();   // 可为 null → Acl.allow 对 null 主体拒绝
    }

    /**
     * 资源判定（放行返回 null，否则返回拒绝原文）：{@code need} 取 'R' 读 / 'W' 写 / 'X' 执行。
     * 基板侧直调 {@link Auth#allowRes}（C 数据的库/内存、T 类的工具入口、A/B 的路径、E 的动作都在这里收口）。
     */
    private static String needRes(Auth auth, Conf conf, sair.v4.auth.Res res, char need) {
        return auth.allowRes(aclCaller(conf), res, need);
    }

    /**
     * <b>{@code skill_write} 的落点自判</b>（结构性依赖收口）：写盘 / 编译 / 热加载之前，
     * 对这次要落的每个路径判 {@code 'W'} 位；任何一个不通过就拒绝。
     *
     * <p><b>为什么必须由工具自己判</b>：权限注册表已不做工具级判定
     * （见 {@code Registry.call} 的注释："这里不再有工具级权限判定"），旧体系里"唯一拦人的地方"是
     * <b>调用点自觉</b>（{@code 经验蒸馏.promote} 里那句 {@code h.needPath(h.conf().skillsDir(), 'W')}）。
     * 那是结构性依赖：任何新入口（别的技能、子 Agent、模型直调 {@code registry.call}）忘了判，
     * 就等于开了"写源码 + 热加载 = 代码执行"的后门。这里把判定收进工具本身，调用点那句保留
     * （纵深防御两层都要有）。</p>
     *
     * <p><b>主体</b>：与 {@link #needRes} 一致 —— {@code Ctx.caller()}，<b>取不到就拒</b>
     * （fail-closed，绝不回落 SYSTEM）；判定用真账本（{@code auth}），不是"空账本放行"。</p>
     *
     * <p><b>为什么判目录而不是"目录 + 具体文件名"</b>：落点是"技能库"这件事的粒度就是目录；
     * 判得更细会让 {@code B["…/skills/某技能","RWX"]} 这类条目把"在技能库里新建任意技能"重新打开。</p>
     *
     * @return 放行返回 {@code null}；拒绝时返回账本原文（{@code [权限阻断] 需要 … 的 W 位（默认分配 …，当前有效位 …）}）
     */
    private static String skillWriteSelfDeny(Auth auth, Conf conf, Out out, List<File> targets) {
        for (int i = 0; targets != null && i < targets.size(); i++) {
            File f = targets.get(i);
            if (f == null) continue;
            String deny = needRes(auth, conf, sair.v4.auth.Res.path(f), 'W');
            if (deny != null) {
                if (out != null) {
                    out.err("[auth] skill_write 自判：落点 " + f.getAbsolutePath() + " 的 W 位不足 —— " + deny);
                }
                return deny;
            }
        }
        return null;
    }

    // ==================== 技能库：文件写入 / 草稿 ====================

    /** 工具名的硬规则（写进返回文案：起中文名也能跑 —— 会自动英文化注册，先说清省得反复踩）。 */
    private static final String TOOL_NAME_RULE =
            "（工具名建议用 ASCII ^[a-zA-Z0-9_-]{1,64}$；写了中文名会自动英文化注册，文件夹名与 md 名保持中文）";

    /** 声明名被英文化时的映射说明（没改写就返回空串）。 */
    private static String mappedNote(Sk sk) {
        if (sk == null || sk.toolRegistered == null) return "";
        return "，工具名「" + sk.toolDeclared + "」已自动英文化注册为 " + sk.toolRegistered;
    }

    /** 把 md / java 写进某个技能目录（add / update / draft 共用）；返回 null = 成功。 */
    private static String writeSkillFiles(File dir, String name, String md, String java) {
        Fs.mkdirs(dir);
        if (Str.has(md)) {
            File mdf = new File(dir, name + ".md");
            if (!Fs.write(mdf, md)) return "写入 md 失败";
        }
        if (Str.has(java)) {
            String cls = DynCode.classNameOf(java);
            File jf = new File(dir, (Str.has(cls) ? cls : "AirunSkill") + ".java");
            if (!Fs.write(jf, java)) return "写入 java 失败";
        }
        return null;
    }

    /** 草稿区清单（只有目录与文件事实，没有文案）。 */
    private static String draftsJson(Conf conf) {
        JsonArray arr = new JsonArray();
        File rootDir = conf.draftsDir();
        for (File d : Fs.dirs(rootDir)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", d.getName());
            List<File> fs = Fs.walk(d, null, 2);
            JsonArray names = new JsonArray();
            long bytes = 0;
            long latest = 0;
            for (File f : fs) {
                names.add(f.getName());
                bytes += f.length();
                latest = Math.max(latest, f.lastModified());
            }
            o.addProperty("files", names.size());
            o.add("file_names", names);
            o.addProperty("bytes", bytes);
            o.addProperty("mtime", latest);
            o.addProperty("exists_in_skills", new File(conf.skillsDir(), d.getName()).exists());
            arr.add(o);
        }
        JsonObject rootOut = new JsonObject();
        rootOut.addProperty("dir", rootDir.getAbsolutePath());
        rootOut.addProperty("count", arr.size());
        rootOut.add("drafts", arr);
        rootOut.addProperty("note", "草稿不参与扫描、不进工具表；promote 之后才会加载。" + TOOL_NAME_RULE);
        return J.json(rootOut);
    }

    // ==================== 库的会话隔离（非主人） ====================

    /** 非主人访问敏感库的规则；返回 null = 放行。 */
    private static String scopedDeny(String lib, Caller me, boolean write) {
        if (me == null) return Acl.DENY_PREFIX + "缺少调用者身份";
        if (me.master()) return null;
        if ("dialog".equals(lib)) return null;                 // 强制收敛到本会话（见 constrain/constrainRow）
        if ("grouplog".equals(lib)) {
            if (write) return Acl.DENY_PREFIX + "群聊历史只能由基板记录";
            if (!me.isGroup()) return Acl.DENY_PREFIX + "群聊历史只能在本群里查";
            return null;
        }
        if ("pref".equals(lib) && write) return Acl.DENY_PREFIX + "偏好设定只能由主人改";
        if ("sticker".equals(lib) && write) return Acl.DENY_PREFIX + "表情包库只能由主人改";
        return null;
    }

    /** 读过滤收敛：dialog 只看本会话、grouplog 只看本群。 */
    private static JsonObject constrain(String lib, JsonObject filter, Caller me) {
        JsonObject f = filter == null ? new JsonObject() : filter.deepCopy();
        if ("dialog".equals(lib)) f.addProperty("session", me.session());
        else if ("grouplog".equals(lib)) f.addProperty("group_id", me.groupId());
        return f;
    }

    /** 写行收敛：返回 null 表示这次写入不被允许。 */
    private static JsonObject constrainRow(String lib, JsonObject row, Caller me, String ignored) {
        JsonObject r = row == null ? new JsonObject() : row.deepCopy();
        if ("dialog".equals(lib)) {
            String s = J.s(r, "session", "");
            if (Str.has(s) && !s.equals(me.session())) return null;      // 不许伪造他人会话
            r.addProperty("session", me.session());
            return r;
        }
        if ("memory".equals(lib)) {
            String scope = J.s(r, "scope", "user");
            // 非主人显式写 scope=global 的情形**到不了这里**：调用点先过 writeScopeDeny（[权限阻断] 直接拒）。
            // 这一行留着当最后一道兜底（万一将来多出别的调用点）：宁可静默收敛到自己的 user 行，
            // 也绝不把一行记忆落进全局作用域。
            if ("global".equalsIgnoreCase(scope)) scope = "user";
            r.addProperty("scope", scope);
            if ("user".equals(scope)) r.addProperty("scope_id", String.valueOf(me.qq()));
            return r;
        }
        if ("note".equals(lib)) return r;
        return r;
    }

    /**
     * <b>写口的作用域越权硬判（非主人）：显式写 {@code scope=global} 一律"拒"</b>，
     * 不再由 {@link #constrainRow} 静默改写成 {@code user}。
     *
     * <p><b>为什么必须改</b>：同一个动作在两处口径不一致 —— 技能层（如 {@code memory} 技能）对
     * "非主人写 global"是<b>明拒</b>（{@code [权限阻断]}），而原始写口 {@code store_write} 走到
     * {@link #constrainRow} 时把 {@code global} <b>静默改写成 {@code user}</b>，
     * 于是 {@link #writeMemoryDeny} 的"global 只有主人能写"那条分支<b>永远走不到</b>。
     * 主人看到的行为就是"技能说不行、直接写却悄悄落盘成另一行"—— 统一成"拒"。
     *
     * <p>只认<b>显式</b>给的 {@code scope=global}（缺省不算，缺省本来就是 {@code user}）；
     * {@code MASTER} 与 {@code SYSTEM}（她自己的自主行为）照旧全权，行为不变。</p>
     *
     * @return 放行返回 {@code null}，否则返回 {@code [权限阻断]} 开头的拒绝原文
     */
    private static String writeScopeDeny(String lib, JsonObject row, Caller me) {
        if (row == null) return null;
        if (me == null) return Acl.DENY_PREFIX + "缺少调用者身份";
        if (me.master() || me.system()) return null;
        if (!"memory".equals(lib)) return null;      // pref/sticker 等库的写口另由 scopedDeny 整库封死
        String scope = Str.trim(J.s(row, "scope", ""));
        if ("global".equalsIgnoreCase(scope)) {
            return Acl.DENY_PREFIX + "非主人不能写 global 作用域的记忆（只能写自己的 user 或自己所在群的 group）";
        }
        return null;
    }

    /** 该行是否属于这个调用者的作用域。 */
    private static boolean inScope(String lib, JsonObject row, Caller me) {
        if (row == null || me == null) return false;
        if (me.master()) return true;
        if ("dialog".equals(lib)) return me.session().equals(J.s(row, "session", ""));
        if ("grouplog".equals(lib)) return J.l(row, "group_id", 0) == me.groupId();
        return true;
    }

    /**
     * <b>管理面写口硬判</b>：整库导出 / 整库维护（{@code store_admin} 的 {@code export} /
     * {@code optimize} / {@code vacuum}）<b>只有 MASTER 与 SYSTEM</b> 能做 —— 非主人一律拒。
     *
     * <p>理由：{@code export} 会把任意库<b>整表导出</b>（跨用户 / 跨群数据一次性拿走），
     * {@code optimize}/{@code vacuum} 是维护动作；C 类按类默认分配虽然只有 {@code R}
     * （{@code Acl.DEF_C}），但主人可以按人把 C 类的 {@code W} 开出去 —— 所以这一层不靠位表兜底，
     * 自己硬判"只有 MASTER 与 SYSTEM"。</p>
     *
     * <p>{@code stat} <b>不在其列</b>：它只暴露"有多少行"，不暴露行内容，非主人可读。</p>
     *
     * @param what 动作的人话名（{@code 导出} / {@code 维护}），进拒绝文案
     * @return 放行返回 {@code null}
     */
    private static String writeAdminDeny(String what, Caller me) {
        if (me == null) return Acl.DENY_PREFIX + "缺少调用者身份";
        if (me.master() || me.system()) return null;
        return Acl.DENY_PREFIX + "整库" + what + "是主人的事（只有 MASTER 与 SYSTEM 能做）";
    }

    /**
     * <b>写口的归属硬判（一）：按库封死的写</b> —— {@code note} 与 {@code kv} 对非主人<b>一律拒</b>，
     * 与"具体哪一行"无关（所以 {@code put}/{@code update}/{@code delete} 三处共用它，连"id 存不存在"都不必问）。
     *
     * <ul>
     *   <li>{@code note}：<b>全体共享</b>的知识库，行里没有归属列 —— 谁都能读，写只能主人来；</li>
     *   <li>{@code kv}：技能各有自己的写路径（例如定时任务直接调 {@code store.kvSet}），
     *       原始写口不对非主人开放。</li>
     * </ul>
     *
     * <p><b>为什么必须硬判、且不看权限位</b>：新 ACL 模型里 C 类按类默认分配是 {@code RWX}
     * （{@code Acl.DEF_C}）—— 非主人也拿得到 W。旧模型"C 默认只有 R"曾是"非主人写不进库"的
     * <b>隐性防线</b>，现在它没有了；而 {@code store_write} 是"任意库任意行"的原始写口，
     * 不判归属就等于绕过所有技能自己的键设计。{@code needDb('W')} 对所有人都放行，靠它挡不住。</p>
     *
     * @return 放行返回 {@code null}；MASTER 与 SYSTEM（她的自主行为）照旧全权
     */
    private static String writeClosedDeny(String lib, Caller me) {
        if (me == null) return Acl.DENY_PREFIX + "缺少调用者身份";
        if (me.master() || me.system()) return null;
        if ("note".equals(lib)) return Acl.DENY_PREFIX + "知识笔记是全体共享的，只有主人能改";
        if ("kv".equals(lib)) return Acl.DENY_PREFIX + "kv 是各技能自己的键空间，不能直接写";
        return null;
    }

    /**
     * <b>写口的归属硬判（二）：{@code memory} 的行级归属 + 整库级</b>（非主人）。
     *
     * <ul>
     *   <li>{@code row == null} = <b>没有具体行</b>的整库写（{@code store_admin import} 的整库覆盖、
     *       {@code maintain} 的超龄清理）—— 没法逐行验归属，非主人直接拒；</li>
     *   <li>具体行：{@code scope=user} 且 {@code scope_id == 自己 QQ}，或 {@code scope=group}
     *       且 {@code scope_id == 自己所在的群} —— 两者都不满足（含 {@code global}）一律拒。</li>
     * </ul>
     *
     * @return 放行返回 {@code null}；MASTER 与 SYSTEM 照旧全权；其它库（dialog/grouplog/pref/sticker）
     *          原样返回 {@code null}（走既有的 {@link #scopedDeny} + {@link #constrainRow}/{@link #inScope} 口径）
     */
    private static String writeMemoryDeny(JsonObject row, Caller me) {
        if (me == null) return Acl.DENY_PREFIX + "缺少调用者身份";
        if (me.master() || me.system()) return null;                 // MASTER 与她的自主行为照旧全权
        if (row == null) return Acl.DENY_PREFIX + "不能整库覆盖 / 整库维护记忆（只有主人能）";
        String scope = Str.trim(J.s(row, "scope", "user"));
        String id = Str.trim(J.s(row, "scope_id", ""));
        if ("user".equalsIgnoreCase(scope)) {
            if (String.valueOf(me.qq()).equals(id)) return null;
            return Acl.DENY_PREFIX + "这条记忆不属于你（scope=user, scope_id=" + id + "）";
        }
        if ("group".equalsIgnoreCase(scope)) {
            if (me.isGroup() && String.valueOf(me.groupId()).equals(id)) return null;
            return Acl.DENY_PREFIX + "这条记忆不属于你所在的群（scope=group, scope_id=" + id + "）";
        }
        return Acl.DENY_PREFIX + "不能改 scope=" + scope + " 的记忆（global 只有主人能写）";
    }

    private static List<JsonObject> keepInScope(String lib, List<JsonObject> rows, Caller me) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (rows == null) return out;
        for (JsonObject r : rows) if (inScope(lib, r, me)) out.add(r);
        return out;
    }

    // ==================== NapCat 动作权限 ====================

    /**
     * {@code napcat} 工具两处判定用的<b>主体</b>：与装在 {@code guardedApi} 上的那道 Guard
     * （{@code Boot} 里 {@code Api.Guard.deny}）<b>取法完全同源</b> —— 先问当轮绑定的主体
     * {@link sair.v4.ctx.Ctx#caller()}；取不到 = 她自己的自主行为（基板回复、定时推送、扩展点回调），
     * 按 {@link Caller#systemActor(long)} 处理。
     *
     * <p>旧实现是 {@code auth.allow/check("napcat.<动作>")}（走旧档位表）：权威闸门其实在 Guard 上
     * （{@code Api.call} 每次都先问它），旧表只能多拦、不能放行 —— 但两处语义不一致，
     * 而且目录可见性会把<b>实际调不动</b>的动作名暴露给非主人。现在两处一句话：{@code Res.platform + 'X'}。</p>
     */
    private static Caller napcatSubject(Conf conf) {
        Caller c = sair.v4.ctx.Ctx.caller();
        if (c == null) c = Caller.systemActor(conf == null ? 0L : conf.masterQQ());
        return c;
    }

    /**
     * {@code napcat} 工具 {@code action=list} 里的一条<b>事实说明</b>（不是动作）：
     * 本地文件会经基板文件外链中转变成 URL 交给 NapCat。
     */
    private static String localFileFact(sair.v4.qq.Relay r) {
        StringBuilder sb = new StringBuilder();
        sb.append("本地文件交给 NapCat 的唯一口径：把本地绝对路径直接填进 file（upload_group_file / upload_private_file），"
                + "或填进消息段的 data.file（image / record / video / file）与 CQ 串的 file=。"
                + "基板在 Api.call 里统一改写：中转在跑就换成 URL（NapCat 在别的机器上也取得到），没跑就换成 file:///（只有同机能取）。"
                + "不要自己拼 URL、不要自己起服务、不要把大文件塞进 base64。");
        if (r == null) {
            sb.append(" 当前：没有装配中转。");
        } else if (r.running()) {
            sb.append(" 当前：中转在跑（").append(r.urlShape()).append("，端口 ").append(r.port()).append("）。");
        } else {
            sb.append(" 当前：中转没在跑（relayEnabled=false 或没起来）——本地文件走 file:///，NapCat 必须与插件同机。");
        }
        return sb.toString();
    }

    // ==================== NapCat 动作出厂档位：已删除（P9b-1 停用 / P9b-2 删净） ====================
    //
    // 这里原来是 napcatLevel(action)：把每个 NapCat 动作映射到一个"出厂档位"（MASTER / AFFECTION:<N>，
    // 其中 set_group_* 走旧档位表的 GROUP_MASTER_AS 常量）。它<b>只有唯一一个活调用点</b> ——
    // Boot.permSync 给旧档位表补"缺行"。P9b-1 把 permSync 整块删掉之后，本函数在 src 里已无任何调用方
    // （平台动作的权威判据是 ACL 的 Res.platform(动作) X 位，装在 guardedApi 上的 Guard，与旧表无关），
    // 故一并删除；P9b-2 再把旧档位表本身（PermTable 整类与 GROUP_MASTER_AS）删除。判定面一个字没变。

    // ==================== 时间解析 ====================

    /** 支持 "+30m/+2h/+1d"、"HH:mm"、"YYYY-MM-DD HH:mm"、"yyyy/MM/dd HH:mm"，或 in 分钟。 */
    public static long parseWhen(String at, int inMinutes) {
        long now = System.currentTimeMillis();
        if (inMinutes > 0) return now + inMinutes * 60000L;
        if (Str.blank(at)) return 0;
        String s = at.trim();
        try {
            if (s.startsWith("+")) {
                String body = s.substring(1).trim().toLowerCase();
                char unit = body.charAt(body.length() - 1);
                long n = Long.parseLong(body.substring(0, body.length() - 1).trim());
                if (unit == 'm') return now + n * 60000L;
                if (unit == 'h') return now + n * 3600000L;
                if (unit == 'd') return now + n * 86400000L;
                if (unit == 's') return now + n * 1000L;
                return 0;
            }
            if (s.matches("^\\d+$")) return Long.parseLong(s);
            String[] patterns = {"yyyy-MM-dd HH:mm", "yyyy/MM/dd HH:mm", "yyyy-MM-dd HH:mm:ss"};
            for (String p : patterns) {
                try {
                    SimpleDateFormat f = new SimpleDateFormat(p);
                    f.setLenient(false);
                    Date d = f.parse(s);
                    if (d != null) return d.getTime();
                } catch (Exception ignored) {
                }
            }
            if (s.matches("^\\d{1,2}:\\d{2}$")) {
                String[] hm = s.split(":");
                Calendar c = Calendar.getInstance();
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                c.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis() <= now) c.add(Calendar.DAY_OF_MONTH, 1);
                return c.getTimeInMillis();
            }
            // 人话写法（"明天 09:00" / "明早" / "今晚" / "下周三 14:00"）：认出来就用它
            long spoken = parseSpoken(s);
            if (spoken > 0L) return spoken;
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 某日 00:00（毫秒）。 */
    private static long midnight(long ms, int addDays) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        c.add(Calendar.DAY_OF_MONTH, addDays);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** 周几：{@code 一..六} = 1..6、{@code 日/天/7} = 7；认不出返回 -1。 */
    private static int weekdayOf(String s) {
        if (Str.blank(s)) return -1;
        char c = s.charAt(0);
        if (c == '一') return 1;
        if (c == '二') return 2;
        if (c == '三') return 3;
        if (c == '四') return 4;
        if (c == '五') return 5;
        if (c == '六') return 6;
        if (c == '日' || c == '天' || c == '7') return 7;
        if (c >= '1' && c <= '7') return c - '0';
        return -1;
    }

    /**
     * 人话时间（闹钟输入用）：认日期词（今天/明天/明日/后天/大后天/今晚/明早、下周X/周X/星期X）、
     * 时段词（凌晨/早上/早晨/上午/中午/下午/傍晚/晚上/夜里）与时刻（{@code HH:mm} / {@code H点} / {@code H点半} / {@code H点M分}）。
     *
     * <p>口径：<b>日期词定"哪一天"、时刻定"几点"，两者都写就都听；只写日期用该词的默认点</b>
     * （明天/后天/大后天 = 09:00，明早 = 07:00，今晚 = 20:00，中午 = 12:00，下午 = 14:00，傍晚 = 18:00）；
     * 时段词会把 12 小时制的点挪到正确的半天（"晚上8点" = 20:00、"下午3点半" = 15:30）。
     * 算出来的时刻已经过去 ⇒ 顺到明天同一时刻（与老的 {@code HH:mm} 口径一致）。
     * 一个词都没认出来 ⇒ 返回 0，交给上面那几套老格式。</p>
     */
    private static long parseSpoken(String s) {
        String t = s.replace(" ", "").replace("\t", "").replace("，", "").replace(",", "");
        if (Str.blank(t)) return 0L;
        long nowMs = System.currentTimeMillis();
        long day = 0L;                 // 目标日 00:00（0 = 没说哪一天）
        Integer defHour = null;        // 只说了日期时的默认点
        boolean matched = false;
        String rest = t;
        // ① 相对日
        String[][] rel = {{"大后天", "3"}, {"后天", "2"}, {"明天", "1"}, {"明日", "1"}, {"今天", "0"}, {"今日", "0"}};
        for (String[] d : rel) {
            if (rest.startsWith(d[0])) {
                day = midnight(nowMs, Integer.parseInt(d[1]));
                defHour = Integer.valueOf(9);
                rest = rest.substring(d[0].length());
                matched = true;
                break;
            }
        }
        // ② 今晚 / 明早
        if (rest.startsWith("今晚")) {
            day = midnight(nowMs, 0);
            defHour = Integer.valueOf(20);
            rest = rest.substring(2);
            matched = true;
        } else if (rest.startsWith("明早") || rest.startsWith("明天早")) {
            int cut = rest.startsWith("明早") ? 2 : 3;
            day = midnight(nowMs, 1);
            defHour = Integer.valueOf(7);
            rest = rest.substring(cut);
            matched = true;
        }
        // ③ 下周X / 周X / 星期X
        if (rest.startsWith("下周") || rest.startsWith("周") || rest.startsWith("星期")) {
            int off = rest.startsWith("下周") ? 2 : (rest.startsWith("星期") ? 2 : 1);
            boolean nextWeek = rest.startsWith("下周");
            String tail = rest.substring(off);
            java.util.regex.Matcher wm = java.util.regex.Pattern.compile("^([一二三四五六日天1-7])").matcher(tail);
            if (wm.find()) {
                int wd = weekdayOf(wm.group(1));
                if (wd > 0) {
                    Calendar c = Calendar.getInstance();
                    int cur = c.get(Calendar.DAY_OF_WEEK);            // 1=周日 … 7=周六
                    int target = wd == 7 ? 1 : wd + 1;
                    int add = target - cur;
                    if (add < 0) add += 7;
                    if (nextWeek) add += 7;                           // "下周X" 落在下一周
                    if (add == 0) add = 7;                            // 就是今天（没写"今天"）⇒ 顺到下周
                    day = midnight(nowMs, add);
                    if (defHour == null) defHour = Integer.valueOf(9);
                    rest = tail.substring(wm.end());
                    matched = true;
                }
            }
        }
        // ④ 时段词（顺带记住"上午侧 / 下午晚上侧"，用来把 12 小时制的点挪到正确半天）
        String[] words = {"凌晨", "早上", "早晨", "上午", "中午", "下午", "傍晚", "晚上", "夜里"};
        int[] hours = {5, 8, 8, 9, 12, 14, 18, 20, 20};
        int period = 0;      // 0 没写 / 1 上午侧 / 2 下午与晚上侧
        for (int i = 0; i < words.length; i++) {
            if (rest.startsWith(words[i])) {
                defHour = Integer.valueOf(hours[i]);
                period = hours[i] >= 12 ? 2 : 1;
                rest = rest.substring(words[i].length());
                matched = true;
                break;
            }
        }
        // ⑤ 时刻
        Integer hour = null;
        int minute = 0;
        java.util.regex.Matcher cm = java.util.regex.Pattern
                .compile("^(\\d{1,2})(?:[:：](\\d{1,2})|点(半|(\\d{1,2})分?)?)").matcher(rest);
        if (cm.find()) {
            int h = Integer.parseInt(cm.group(1));
            int mi = 0;
            if (cm.group(2) != null) mi = Integer.parseInt(cm.group(2));
            else if ("半".equals(cm.group(3))) mi = 30;
            else if (cm.group(4) != null) mi = Integer.parseInt(cm.group(4));
            if (period == 2 && h < 12) h += 12;          // "晚上8点" = 20:00
            else if (period == 1 && h == 12) h = 0;      // "凌晨12点" = 00:00
            if (h >= 0 && h <= 23 && mi >= 0 && mi <= 59) {
                hour = Integer.valueOf(h);
                minute = mi;
                matched = true;
            }
        }
        if (!matched) return 0L;
        Calendar c = Calendar.getInstance();
        if (day > 0L) c.setTimeInMillis(day);
        if (hour != null) {
            c.set(Calendar.HOUR_OF_DAY, hour.intValue());
            c.set(Calendar.MINUTE, minute);
        } else if (defHour != null) {
            c.set(Calendar.HOUR_OF_DAY, defHour.intValue());
            c.set(Calendar.MINUTE, 0);
        } else {
            return 0L;
        }
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long ms = c.getTimeInMillis();
        if (ms <= nowMs) ms += 86400000L;      // 算出来已经过去 ⇒ 顺到明天同一时刻（与老的 HH:mm 口径一致）
        return ms;
    }

    /**
     * "可用：…" 那句子话里的库清单 —— 问注册表（基板环境库 + 插件声明的业务库），不写死六库。
     */
    private static String libNames(sair.v4.store.Libs libs) {
        return Str.join(libEnum(libs), "/");
    }

    /**
     * 工具面里的库清单：<b>顺序保持既有口径</b>（{@code memory/note/dialog/grouplog/sticker/pref}，
     * 外挂文案与快照都比过这个顺序），只列"当前真的注册了"的库；插件新声明的表按注册顺序附在后面。
     * <p>这样"插件没装"的库不会出现在 schema 的 enum 里 —— 模型看不到就不会去调它。</p>
     */
    private static List<String> libEnum(sair.v4.store.Libs libs) {
        List<String> out = new ArrayList<String>();
        if (libs == null) return out;
        for (String t : new String[] {"memory", "note", "dialog", "grouplog", "sticker", "pref"}) {
            if (libs.get(t) != null) out.add(t);
        }
        for (String t : libs.tables()) if (!out.contains(t)) out.add(t);
        return out;
    }

    // ==================== JSON Schema（给模型看的参数说明） ====================

    /**
     * 一个"取值表固定"的参数（op / kind / slot 这类）。
     * <p>把 {@code enum} 明确写进 schema（而不是只写在 description 的自然语言里）：模型看得见取值表，
     * 基板也就能据此做参数复核（枚举值越界时把合法取值附在结果后面）。
     * 取值表是结构信息、不是文案，所以它跟工具名一样属于工具契约本身。</p>
     */
    private static JsonObject oneOf(String desc, String def, String... values) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String v : values) a.add(v);
        JsonObject o = J.obj("type", "string", "description", desc, "enum", a);
        if (def != null) o.addProperty("default", def);
        return o;
    }

    private static JsonObject obj(String type, String desc) {
        return J.obj("type", type, "description", desc);
    }

    private static JsonObject schemaStoreRead(sair.v4.store.Libs libs) {
        com.google.gson.JsonArray libArr = new com.google.gson.JsonArray();
        for (String l : libEnum(libs)) libArr.add(l);
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "lib", J.obj("type", "string", "description", "库名", "enum", libArr),
                        "op", oneOf("list/get/search/count", "list", "list", "get", "search", "count"),
                        "id", obj("integer", "记录 id（get）"),
                        "query", obj("string", "检索词（search）"),
                        "filter", obj("object", "等值过滤，如 {\"group_id\":123}"),
                        "limit", obj("integer", "条数，默认 20"),
                        "order", obj("string", "排序，默认 ts desc")),
                "required", arr("lib"));
    }

    private static JsonObject schemaStoreWrite() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "lib", obj("string", "库名"),
                        "op", oneOf("put/update/delete", "put", "put", "update", "delete"),
                        "id", obj("integer", "记录 id（update/delete）"),
                        "row", obj("object", "整行数据（put）"),
                        "patch", obj("object", "要改的字段（update）")),
                "required", arr("lib"));
    }

    private static JsonObject schemaStoreAdmin() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("stat/maintain/optimize/vacuum/export/import", "stat", "stat", "maintain", "optimize", "vacuum", "export", "import"),
                        "lib", obj("string", "导出/导入/optimize 的库名"),
                        "path", obj("string", "文件路径（省略写到 files/）"),
                        "keepDays", obj("integer", "维护：记忆/笔记保留天数，默认配置 keepDays"),
                        "dialogKeepDays", obj("integer", "维护：对话历史保留天数，默认配置 dialogKeepDays"),
                        "grouplogKeepDays", obj("integer", "维护：群聊历史保留天数，默认配置 grouplogKeepDays"),
                        "maxLow", obj("integer", "维护：低重要度记忆上限，默认 500")));
    }

    private static JsonObject schemaSkillRead() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("list/read/validate", "list", "list", "read", "validate"),
                        "name", obj("string", "技能名（read；list 时做包含过滤）")));
    }

    private static JsonObject schemaSkillWrite() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("add/update/delete/reload/read_source/draft/drafts/promote", null,
                                "add", "update", "delete", "reload", "read_source", "draft", "drafts", "promote"),
                        "name", obj("string", "技能名（=文件夹名）"),
                        "md", obj("string", "技能 md 全文（front matter + 说明书）"),
                        "java", obj("string", "技能 Java 源码（可选）")),
                "required", arr("op"));
    }

    private static JsonObject schemaExec() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "kind", oneOf("java/cmd", "java", "java", "cmd"),
                        "code", obj("string", "Java 源码（含类名，最好有 public static Object run(String[])）"),
                        "args", J.obj("type", "array", "description", "传给 run/main 的参数", "items", J.obj("type", "string")),
                        "cmd", obj("string", "系统命令"),
                        "timeout", obj("integer", "超时毫秒，默认 30000")));
    }

    private static JsonObject schemaPromptRead() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("show/list/read/sections", "show", "show", "list", "read", "sections"),
                        "name", obj("string", "提示词文件名（read）")));
    }

    private static JsonObject schemaPromptWrite() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("load/inject/write/clear/reload", "inject", "load", "inject", "write", "clear", "reload"),
                        "slot", oneOf("system/context/tool/arg", "system", "system", "context", "tool", "arg"),
                        "scope", obj("string", "作用域：省略=全局，或会话键"),
                        "key", obj("string", "注入项名字（同 key 覆盖）"),
                        "name", obj("string", "提示词文件名（load/write）"),
                        "text", obj("string", "要注入或写入的文本")));
    }

    private static JsonObject schemaAgent() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("spawn/list/status/stop", "list", "spawn", "list", "status", "stop"),
                        "task", obj("string", "要子 Agent 做的事（spawn）"),
                        "model", obj("string", "子 Agent 的模型（可选）。你看不了图时（查 models.self_has_vision）必须派 model=models.vision 的子 Agent 去看图，用文字把看到的回给你"),
                        "brief", obj("string", "子 Agent 的角色/边界/输出格式——由你（主 Agent）现场生成；"
                                + "公共规则在外挂 prompts/subagent.md 里，不用重复"),
                        "tools", J.obj("type", "array", "description", "子 Agent 可用的工具名（省略=与主 Agent 相同）",
                                "items", J.obj("type", "string")),
                        "async", obj("boolean", "true=立刻返回任务票（结论回灌给你，不直接发给用户）"),
                        "speak", obj("boolean", "async=true 时有意义：true=这一单就是替机器人说一句话，子 Agent 写什么就由基板发到当前会话"
                                + "（它自己发过或回 <silent> 则不重复发）；默认 false=内部材料，用户看不到"),
                        "id", obj("integer", "任务 id（status）"),
                        "status", J.obj("type", "string", "description", "running/done/all")));
    }

    private static JsonObject schemaCtx() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("inject/list/clear", "list", "inject", "list", "clear"),
                        "slot", oneOf("system/context/tool/arg", "context", "system", "context", "tool", "arg"),
                        "key", obj("string", "注入项名字"),
                        "text", obj("string", "要注入的文本"),
                        "scope", obj("string", "作用域：省略=当前会话")));
    }

    /**
     * perm 的入参表（口径文本在 {@link #PERM_DESC}：参数说明里只讲"这个参数怎么填"）。
     * <p>取值表写进 {@code enum}：模型看得见合法取值，基板的参数复核也据此拦越界值。</p>
     * <p>{@code public} 是给探针断言用的（核 op 取值表与四个参数就是她看到的那一份契约）。</p>
     */
    public static JsonObject schemaPerm() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("grant 授权/覆盖 · revoke 撤销 · acl 看清单或验算（这三个只有主人）"
                                        + " · whoami 我是谁（谁都能问：只回自己五类有效位）"
                                        + " · check 查某人在某类/某范围的位与例外（只读；谁都能查自己，查别人只有主人）",
                                "whoami", "whoami", "grant", "revoke", "acl", "check"),
                        "principal", obj("string",
                                "主体。User<QQ号>（个人，跨群有效）/ Group<群号>（只在该群会话生效）/ SYSTEM（她自己）/ "
                                + "ALLUSER（全体用户）；裸数字 = User<QQ>；「群123456」也认；"
                                + "多个主体用逗号分开（一主体一条）；"
                                + "也可以直接给一整条条目（如 A[\"User123456\",\"D:/share\",\"RWX\"]），这时 cls/scope/bits 都不用给。"
                                + "op=check 时留空 = 查你自己"),
                        "cls", oneOf("类别：A 本机（本机文件与进程，含网络资源）· B SFW（SFW 运行时目录，仅文件）"
                                        + "· C 数据（数据库全部内容 + files 目录）"
                                        + "· E 外部交互（Napcat 输入输出 + 向 SFW 发命令）"
                                        + "· T 工具（只有入口受管控：R 看 / W 改·注册 / X 执行）",
                                null, "A", "B", "C", "E", "T"),
                        "scope", obj("string",
                                "范围：A/B 给路径（盘符 D: / 目录 / 文件）；C 给库名或 mem:xxx 或 files 下的路径；"
                                + "E 给动作名；T 给工具名（tool: 前缀可省）；留空 = 该类全部。"
                                + "revoke 留空 = 删掉他在该类全部条目；check 留空 = 只看整类那一格"),
                        "bits", obj("string",
                                "位：R/W/X 的 7 种组合（R、W、X、RW、RX、WX、RWX）；"
                                + "空串 = 一位都不给（显式拒绝，压过默认分配 —— 「禁止」走这个）")));
    }

    private static JsonObject schemaTools() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf(TOOLS_OPS, "list", "list", "search", "show"),
                        "keyword", obj("string", "list：按名/描述/归属过滤（留空 = 全部）"),
                        "query", obj("string", "search：你要的**意图**，如「改设置」「发图」「查余额」「发给某人」"),
                        "name", obj("string", "show：工具名（写不准就先 search）")));
    }

    /** {@code config} 的入参表（说明在 {@link #CONFIG_DESC}）。 */
    public static JsonObject schemaConfig() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf(CONFIG_OPS, "list", "list", "get", "set"),
                        "key", obj("string", "配置键名（get/set；不知道就 op=list 看全部已知键）"),
                        "value", obj("string", "新值（set）：整数/小数/true|false 按原类型写；清空写空串")));
    }

    private static JsonObject schemaModel() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("chat/vision/balance/models/info", "chat", "chat", "vision", "balance", "models", "info"),
                        "text", obj("string", "要问的话"),
                        "system", obj("string", "可选系统提示"),
                        "images", J.obj("type", "array", "description", "图片 URL 或 data:base64（vision）",
                                "items", J.obj("type", "string")),
                        "model", obj("string", "临时换模型（省略=配置模型）")));
    }

    private static JsonObject schemaNapcat() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "action", J.obj("type", "string", "description", "动作名；list=列目录", "default", "list"),
                        "params", obj("object", "动作参数对象（按目录里的参数名给）")));
    }

    private static JsonObject schemaConsole() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("read/run/history/print/clear/size/visible", "print",
                                "read", "run", "history", "print", "clear", "size", "visible"),
                        "text", obj("string", "要打印的文本（print）"),
                        "cmd", obj("string", "框架命令（run），如 jj/at 1+/100"),
                        "since_seq", obj("integer", "read：上次读到的游标（上次返回里的 seq_tail）；省略=取最近 tail_chars"),
                        "tail_chars", obj("integer", "read/visible：最多回多少字符（read 省略取配置 consoleTapReadChars）"),
                        "filter", obj("string", "read：只保留含该串的输出行"),
                        "limit", obj("integer", "history：回多少条，默认 20")));
    }

    /** 这一行是不是"周期活"的写法（once 不算）。 */
    private static boolean isPeriodic(String repeat) {
        String r = Str.nz(repeat).trim().toLowerCase();
        return r.startsWith("every:") || "daily".equals(r) || "weekly".equals(r) || r.startsWith("cron:");
    }

    /** 两段任务正文是不是"同一件事"（去掉空白与常见标点后比较，允许一方包含另一方）。 */
    private static boolean sameTask(String a, String b) {
        String x = normTask(a);
        String y = normTask(b);
        if (x.isEmpty() || y.isEmpty()) return false;
        return x.equals(y) || x.contains(y) || y.contains(x);
    }

    private static String normTask(String s) {
        return Str.nz(s).replaceAll("[\\s，。、,.;；:：!！?？\"'（）()\\[\\]【】]", "");
    }

    /** 到点的事：全场没办完的总量上限（每条到点的事 = 一次完整轮，这是花钱的阀门）。 */
    private static final int ALARM_MAX_ALL = 200;
    /** 到点的事：同一个人身上没办完的上限。 */
    private static final int ALARM_MAX_ONE = 5;

    /** 时间戳 → 给人看的一行（`yyyy-MM-dd HH:mm`）。 */
    private static String stamp(long ms) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(new java.util.Date(ms));
    }

    private static JsonObject schemaAlarm() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("add 新建 · list 看清单（主人看全部，别人只看自己委派的）· remove 删一条 · clear 清空自己能看到的 · "
                                        + "done 办妥收尾 · fail 没办成收尾 · defer 顺延或给待办排期",
                                "list", "add", "list", "remove", "clear", "done", "fail", "defer"),
                        "at", obj("string",
                                "时间：\"2026-09-14 09:00\" / \"09:00\" / \"+30m\" / 人话写法 \"明天 09:00\"、\"明早\"、\"今晚\"、\"下周三 14:00\"、\"后天中午\"。"
                                + "只给日期不给点：明天/后天/大后天=09:00，今晚=20:00，中午=12:00，下午=14:00。"
                                + "**不写 at/in = 先记成待办**（只记账、不到点触发，等 op=defer 排期或她顺手办）"),
                        "in", obj("integer", "多少分钟后"),
                        "repeat", J.obj("type", "string", "description",
                                "once/daily/weekly/every:分钟/cron:分 时 日 月 周（如 cron:0 9 * * 1-5 = 工作日九点）",
                                "default", "once"),
                        "scope", obj("string", "console/group/private"),
                        "target", obj("integer", "群号或 QQ 号"),
                        "task", obj("string", "到点让 AI 做的事（写清楚要做完什么）"),
                        "prompt", obj("string", "给这一轮的额外提示"),
                        "mine", J.obj("type", "boolean", "description",
                                "add：true = 我自己定下的（承诺/提醒，可顺延可放弃）；默认 false = 别人委派的工作（必办，不许放弃）。"
                                + "list：true = 只看我自己委派的那些（主人用）", "default", false),
                        "qq", obj("integer",
                                "add：这条是**替谁记的**（委托人 QQ）。一轮里可能同时有好几个人的消息，"
                                + "替谁记就填谁的 QQ；不填 = 当轮说话的人。归属决定谁能查、到点以谁的身份跑"),
                        "again", J.obj("type", "boolean", "description",
                                "add：同一个人两分钟内已经有一条没办完的，默认当成重复、不再建（回你既有的 id）；"
                                + "确实要再排一条才带 true", "default", false),
                        "id", obj("integer", "闹钟 id（remove/done/fail/defer/abandon 用）"),
                        "result", obj("string", "done：办成了什么（一句话）"),
                        "why", obj("string", "fail/defer/abandon：为什么没办成 / 为什么顺延或排期 / 为什么放弃")));
    }

    private static com.google.gson.JsonArray arr(String... items) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (String s : items) a.add(s);
        return a;
    }
}
