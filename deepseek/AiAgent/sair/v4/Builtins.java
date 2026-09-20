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
import sair.v4.auth.Caller;
import sair.v4.auth.Favor;
import sair.v4.auth.OpList;
import sair.v4.auth.Ops;
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
 *   perm                                  好感度 + 技能管控表        （⑥）
 *   tools / config                        能力自省 / 配置自省        （⑥）
 *   model                                 直连模型                  （⑦）
 *   napcat                                OneBot 动作               （⑧）
 *   console                               控制台输出                （⑨）
 *   alarm                                 定时唤醒                  （④）
 * </pre>
 *
 * 业务形状的能力（发图片、禁言、天气、搜索、审核、主动发言……）一律由 data/skills 提供。
 * 权限：<b>只有一条判定 —— 身份 × op</b>（见 {@code sair.v4.auth.Acl}）。资源不再有位：
 * 本机文件、数据库、对外动作对 {@code MASTER} 与她本人（{@code SYSTEM}）完全可见可改可执行，
 * 对 {@code ALLUSER} 是黑盒 —— 唯一的路就是技能（工具）。所以这里<b>每个动作分支各判自己那个 op</b>：
 * <pre>
 *   String deny = needOp(auth, conf, "store.get");
 *   if (deny != null) return deny;
 * </pre>
 * 判完 op 之后那几道<b>行归属 / 会话作用域 / 配额</b>判断照旧保留（它们是数据正确性，不是权限位）；
 * 内置工具的全部 op 由 {@link #declareOps(OpList)} 在装配期登记（清单 = 技能管控表的骨架）。
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
        final Favor favor = boot.favor();

        // 内置工具的全部 op 在这里登记（技能管控表的骨架：ai/perm gen 按这份清单列空 Run 行）。
        // 这就是装配期调用点；Boot 侧若要在别处补登记，直接调 Builtins.declareOps(auth.ops())。
        declareOps(auth == null ? null : auth.ops());

        // 罢工硬干活闸（情绪 v2 · SPEC §4）的读句柄：只读 kv 的 mood:<场合> 行，异常吞掉、绝不抛。
        // 同一个 store = 情绪插件 h.store() 用的那一份（Boot 的 store 字段），所以判据真源同源、只有一处。
        STRIKE_DB = store;
        STRIKE_OUT = out;

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
                        Caller me = t == null ? null : t.caller();
                        boolean master = me != null && me.master();
                        String deny = scopedDeny(lib, me, false);
                        if (deny != null) return deny;
                        // v6 DB 域（读）：get / count / search / list 四个分支（含认不出的 op 走的默认分支）
                        // 共用这一道 —— 类别键按被查的 lib 映射（规格 §3 白名单）。老守卫（scopedDeny /
                        // constrain / inScope / keepInScope）原样留在后面，两条口径都跑：这一道答"这类数据
                        // 你能不能读"，老守卫答"这一行是不是你的会话 / 群"。
                        String readDeny = dbReadDenyOf(auth, dbKeyOf(lib), me);
                        if (readDeny != null) return readDeny;
                        JsonObject filter = master ? J.sub(args, "filter") : constrain(lib, J.sub(args, "filter"), me);
                        int limit = J.i(args, "limit", 20);
                        if ("get".equals(op)) {
                            // 动作级 op：取一条
                            String aclDeny = needOp(auth, conf, "store.get");
                            if (aclDeny != null) return aclDeny;
                            JsonObject o = store.get(lib, J.l(args, "id", 0));
                            if (o == null) return "没有这一条";
                            if (!master && !inScope(lib, o, me)) return Acl.DENY_PREFIX + "这条记录不属于你的会话";
                            // v6 补的读口行归属（丙核出的缺口）：memory 只读「本人 / 本群 / global」。
                            // 老 inScope 对 memory 一律 true ⇒ 只靠它的话，"读口授权给 ALLUSER"就等于
                            // 能读任意 scope_id 的记忆行。这里与记忆技能内部的 visible 同口径；
                            // MASTER / SYSTEM 恒全权（规格 §0）⇒ 用 isFree 而不是 master。
                            if (!isFree(me) && !readVisible(lib, o, me)) return Acl.DENY_PREFIX + "这条记忆不在你的作用域里（只能读本人 / 本群 / global）";
                            return J.json(o);
                        }
                        if ("count".equals(op)) {
                            // 动作级 op：数行数
                            String aclDeny = needOp(auth, conf, "store.count");
                            if (aclDeny != null) return aclDeny;
                            // v6 读口行归属：memory 不能"整库一把数"（那会数出别人的行数）——
                            // 按可见的三条作用域分别数（本人 / 本群 / global），与 keepReadScope 同口径。
                            if (!isFree(me) && "memory".equals(lib)) return String.valueOf(memoryReadCount(store, lib, filter, me));
                            return String.valueOf(store.count(lib, filter));
                        }
                        if ("search".equals(op)) {
                            // 动作级 op：检索
                            String aclDeny = needOp(auth, conf, "store.search");
                            if (aclDeny != null) return aclDeny;
                            List<JsonObject> hits = store.search(lib, J.s(args, "query", ""), limit);
                            if (!master) {
                                hits = keepInScope(lib, hits, me);       // 老守卫：dialog / grouplog 的会话归属
                                if (!isFree(me)) hits = keepReadScope(lib, hits, me);   // v6：memory 的本人 / 本群 / global
                            }
                            return J.json(hits);
                        }
                        // 默认分支（list，以及认不出的 op 当 list 处理）：动作级 op
                        String aclDeny = needOp(auth, conf, "store.list");
                        if (aclDeny != null) return aclDeny;
                        List<JsonObject> rows = store.list(lib, filter, limit, J.s(args, "order", "ts desc"));
                        // v6 读口行归属：list 原来只靠 constrain 的等值过滤（它只碰 dialog / grouplog），
                        // memory 一行都不过滤 ⇒ 这里按行再过一次可见面（MASTER / SYSTEM 不筛）。
                        if (!isFree(me)) rows = keepReadScope(lib, rows, me);
                        return J.json(rows);
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
                        Caller me = t == null ? null : t.caller();
                        boolean master = me != null && me.master();
                        String deny = scopedDeny(lib, me, true);
                        if (deny != null) return deny;
                        // 归属硬判（与 op 判定无关，是数据正确性）：op 判定只管"这个人能不能用这个动作"，
                        // 挡不住"他用自己的权限去改别人的行" —— 原始写口必须自己判归属。
                        // ① note / kv：与"哪一行"无关，非主人一律拒（连存在性都不必问）；
                        String ownDeny = writeClosedDeny(lib, me);
                        if (ownDeny != null) return ownDeny;
                        if ("update".equals(op)) {
                            // 动作级 op：改一行
                            String aclDeny = needOp(auth, conf, "store_write.update");
                            if (aclDeny != null) return aclDeny;
                            long id = J.l(args, "id", 0);
                            JsonObject old = store.get(lib, id);
                            if (old == null) return "更新失败（id 不存在？）";
                            // v6 DB 域（写）：先过"这类数据你能不能改 + 这一行是不是他的"（dbDeny 的第三参
                            // rowOwner），再走下面的老守卫（inScope / writeMemoryDeny / writeScopeDeny /
                            // constrainRow 一条都不删）—— 顺序按规格 §7：dbDeny 包一层，旧守卫降级为兜底。
                            String dbDeny = dbDenyOf(auth, dbKeyOf(lib), rowOwnerOf(lib, old), me);
                            if (dbDeny != null) return dbDeny;
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
                            // 动作级 op：删一行
                            String aclDeny = needOp(auth, conf, "store_write.delete");
                            if (aclDeny != null) return aclDeny;
                            long id = J.l(args, "id", 0);
                            JsonObject old = store.get(lib, id);
                            if (old == null) return "删除失败（id 不存在？）";
                            // v6 DB 域（写）：同 update —— 先 dbDeny（行归属按类别显式映射，规格 §7-4），
                            // 再走老守卫 inScope / writeMemoryDeny。
                            String dbDeny = dbDenyOf(auth, dbKeyOf(lib), rowOwnerOf(lib, old), me);
                            if (dbDeny != null) return dbDeny;
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
                        // 动作级 op：写一行（默认分支；认不出的 op 也按 put 处理）
                        String aclDeny = needOp(auth, conf, "store_write.put");
                        if (aclDeny != null) return aclDeny;
                        // v6 DB 域（写）：这一行还没落库，归属按送进来的 row 现算（rowOwner 形态
                        // user:<QQ> / group:<群号> / null=无归属的共享行）—— 无归属行只有被指名的身份动得了。
                        String dbDeny = dbDenyOf(auth, dbKeyOf(lib), rowOwnerOf(lib, row), me);
                        if (dbDeny != null) return dbDeny;
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
                            // 动作级 op：六库统计（只暴露"有多少行"，不暴露行内容）
                            String aclDeny = needOp(auth, conf, "store_admin.stat");
                            if (aclDeny != null) return aclDeny;
                            return J.json(store.stat());
                        }
                        if ("maintain".equals(op)) {
                            // 动作级 op：整库维护清理
                            String aclDeny = needOp(auth, conf, "store_admin.maintain");
                            if (aclDeny != null) return aclDeny;
                            // 整库维护会删掉<b>所有人</b>的超龄行（没有"逐行归属"可言）：非主人一律拒
                            Caller me = t == null ? null : t.caller();
                            // v6 DB 域：整库动作没有单一行归属，只能把它<b>覆盖到的每一类</b>都过一遍写判定
                            // （rowOwner=null ⇒ 只有被指名的身份动得了）。规格 §7-2：writeMemoryDeny(null)
                            // 这道整库闸因 rowOwner 只有行级而保留，dbDeny 是加在它前面的一层，不是替代。
                            String dbDeny = dbWholeDeny(auth, MAINTAIN_KEYS, me);
                            if (dbDeny != null) return dbDeny;
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
                            // 动作级 op：合并全文索引段
                            String aclDeny = needOp(auth, conf, "store_admin.optimize");
                            if (aclDeny != null) return aclDeny;
                            // 整库维护动作：非主人一律拒（op 判定之外再加一道 —— 别指望账本不写这一行）
                            String dbDeny = dbDenyOf(auth, dbKeyOf(lib), null, t == null ? null : t.caller());
                            if (dbDeny != null) return dbDeny;
                            String ownDeny = writeAdminDeny("维护", t == null ? null : t.caller());
                            if (ownDeny != null) return ownDeny;
                            // 批量删行之后合并 FTS 段（删除标记这时才真正丢掉；还要 VACUUM 才还盘）
                            return store.optimizeFts(lib)
                                    ? ("已合并 " + lib + " 的全文索引段") : "合并失败（库名不对 / 该库没有索引）";
                        }
                        if ("vacuum".equals(op)) {
                            // 动作级 op：回收空闲页
                            String aclDeny = needOp(auth, conf, "store_admin.vacuum");
                            if (aclDeny != null) return aclDeny;
                            // 整库维护动作（回收空闲页）：非主人一律拒
                            // v6 DB 域：VACUUM 动的是整个数据库文件 ⇒ 覆盖全部业务类别，逐类过写判定。
                            String dbDeny = dbWholeDeny(auth, ADMIN_LIB_KEYS, t == null ? null : t.caller());
                            if (dbDeny != null) return dbDeny;
                            String ownDeny = writeAdminDeny("维护", t == null ? null : t.caller());
                            if (ownDeny != null) return ownDeny;
                            store.db().exec("VACUUM");
                            return "VACUUM 已执行（回收空闲页；期间独占数据库）";
                        }
                        if ("export".equals(op)) {
                            // 动作级 op：整库导出
                            String aclDeny = needOp(auth, conf, "store_admin.export");
                            if (aclDeny != null) return aclDeny;
                            // 整库导出会把跨用户/跨群数据一次性拿走：非主人一律拒
                            // （这一段在 <b>写出文件之前</b>，被拒时一个字节都不会落盘）
                            // v6 DB 域：整库导出 = 无归属行的读走写侧口径（rowOwner=null ⇒ 只有被指名的身份）。
                            String dbDeny = dbDenyOf(auth, dbKeyOf(lib), null, t == null ? null : t.caller());
                            if (dbDeny != null) return dbDeny;
                            // v6 File 域：导出要<b>写出</b>本机文件（path 来自工具参数）⇒ write=true 看 Run。
                            String fileDeny = fileDenyOf(auth, f.getPath(), true, t == null ? null : t.caller());
                            if (fileDeny != null) return fileDeny;
                            String ownDeny = writeAdminDeny("导出", t == null ? null : t.caller());
                            if (ownDeny != null) return ownDeny;
                            return store.exportJson(lib, f) ? ("已导出 " + f.getAbsolutePath()) : "导出失败";
                        }
                        if ("import".equals(op)) {
                            // 动作级 op：整库导入覆盖
                            String aclDeny = needOp(auth, conf, "store_admin.import");
                            if (aclDeny != null) return aclDeny;
                            // 整库覆盖也是"任意行"的写口（且没有逐行归属可验）：与 store_write 同一把尺子
                            Caller me = t == null ? null : t.caller();
                            // v6 DB 域：整库覆盖（rowOwner=null）；v6 File 域：要<b>读入</b> path 指的本机文件
                            // （path 来自工具参数）⇒ write=false 看 Read。两道都在既有守卫之前。
                            String dbDeny = dbDenyOf(auth, dbKeyOf(lib), null, me);
                            if (dbDeny != null) return dbDeny;
                            String fileDeny = fileDenyOf(auth, f.getPath(), false, me);
                            if (fileDeny != null) return fileDeny;
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
                            // 动作级 op：技能库体检
                            String aclDeny = needOp(auth, conf, "skill.validate");
                            if (aclDeny != null) return aclDeny;
                            List<String> p = skills.validate();
                            return p.isEmpty() ? "技能库无问题（共 " + skills.size() + " 个）" : Str.join(p, "\n");
                        }
                        if ("read".equals(op)) {
                            // 动作级 op：读一份说明书
                            String aclDeny = needOp(auth, conf, "skill.read");
                            if (aclDeny != null) return aclDeny;
                            Sk sk = skills.get(J.s(args, "name", ""));
                            if (sk == null) return "没有这个技能";
                            String body = sk.doc == null ? "" : sk.doc;
                            return "技能 " + sk.name + "\n" + J.json(sk.toJson()) + "\n\n" + body;
                        }
                        // 默认分支（list，含认不出的 op）：动作级 op
                        String aclDeny = needOp(auth, conf, "skill.list");
                        if (aclDeny != null) return aclDeny;
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
                        // ── 后门收口 ──
                        // 这把工具是"写源码 + 热加载 = 代码执行"的形状，所以<b>工具自己</b>在动手之前
                        // 对这次的动作判 op（{@code skill_write.add} 等）：工具级 op 放行不代表某个动作放行，
                        // 判定必须落在<b>每个动作分支的第一行</b>（见下面各分支）。
                        if ("reload".equals(op)) {
                            // 动作级 op：重扫技能库
                            String aclDeny = needOp(auth, conf, "skill_write.reload");
                            if (aclDeny != null) return aclDeny;
                            skills.scan();
                            return "已重扫，当前 " + skills.size() + " 个技能（工具 " + skills.toolCount() + " 个）"
                                    + TOOL_NAME_RULE;
                        }
                        if ("drafts".equals(op)) {
                            // 动作级 op：列草稿
                            String aclDeny = needOp(auth, conf, "skill_write.drafts");
                            if (aclDeny != null) return aclDeny;
                            return draftsJson(conf);
                        }
                        if (Str.blank(name)) return "需要 name" + TOOL_NAME_RULE;
                        File dir = new File(conf.skillsDir(), name);
                        if ("delete".equals(op)) {
                            // 动作级 op：删技能
                            String aclDeny = needOp(auth, conf, "skill_write.delete");
                            if (aclDeny != null) return aclDeny;
                            Fs.deleteRec(dir);
                            skills.scan();
                            return "已删除技能 " + name;
                        }
                        if ("read_source".equals(op)) {
                            // 动作级 op：读技能源码
                            String aclDeny = needOp(auth, conf, "skill_write.read_source");
                            if (aclDeny != null) return aclDeny;
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
                            // 动作级 op：写草稿
                            String aclDeny = needOp(auth, conf, "skill_write.draft");
                            if (aclDeny != null) return aclDeny;
                            if (Str.blank(md) && Str.blank(java)) return "需要 md 或 java 内容" + TOOL_NAME_RULE;
                            File d = new File(conf.draftsDir(), name);
                            String err = writeSkillFiles(d, name, md, java);
                            if (err != null) return err;
                            return "已写入草稿：" + name + "（还在 " + conf.draftsRel() + "/，没进工具表；"
                                    + "确认没问题后用 op=promote 搬进技能库）" + TOOL_NAME_RULE;
                        }
                        if ("promote".equals(op)) {
                            // 动作级 op：草稿搬进技能库
                            String aclDeny = needOp(auth, conf, "skill_write.promote");
                            if (aclDeny != null) return aclDeny;
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
                            int skipped = 0;
                            String base = d.getAbsolutePath() + File.separator;
                            for (File f : Fs.walk(d, null, 3)) {
                                String abs = f.getAbsolutePath();
                                if (!abs.startsWith(base)) continue;
                                // ★ 护栏：权限文件（perms.jsonc）不能经技能写入的路带进技能目录 ——
                                //   否则一次 promote 就能给这个技能自己开口子。它在草稿里就跳过。
                                if (isPermFile(f.getName())) {
                                    skipped++;
                                    continue;
                                }
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
                                    + (skipped > 0 ? ("；已跳过 " + skipped + " 个受保护文件（" + Acl.SKILL_FILE_NAME
                                            + "：权限文件只能由主人用控制台 ai/perm 写）") : "")
                                    + mappedNote(sk) + TOOL_NAME_RULE;
                        }
                        if ("add".equals(op)) {
                            // 动作级 op：新增技能
                            String aclDeny = needOp(auth, conf, "skill_write.add");
                            if (aclDeny != null) return aclDeny;
                        } else if ("update".equals(op)) {
                            // 动作级 op：覆盖技能内容
                            String aclDeny = needOp(auth, conf, "skill_write.update");
                            if (aclDeny != null) return aclDeny;
                        } else {
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
                            // 动作级 op：执行系统命令
                            String aclDeny = needOp(auth, conf, "exec.cmd");
                            if (aclDeny != null) return aclDeny;
                            return dyn.runCmd(J.s(args, "cmd", ""), J.l(args, "timeout", 30000));
                        }
                        // 动作级 op：编译并运行一段 Java（默认分支）
                        String aclDeny = needOp(auth, conf, "exec.java");
                        if (aclDeny != null) return aclDeny;
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
                        if ("list".equals(op)) {
                            // 动作级 op：列提示词文件
                            String aclDeny = needOp(auth, conf, "prompt.list");
                            if (aclDeny != null) return aclDeny;
                            return J.json(prompts.files());
                        }
                        if ("sections".equals(op)) {
                            // 动作级 op：初始提示词的分节名
                            String aclDeny = needOp(auth, conf, "prompt.sections");
                            if (aclDeny != null) return aclDeny;
                            return J.json(prompts.identity().sectionNames());
                        }
                        if ("read".equals(op)) {
                            // 动作级 op：读一份提示词
                            String aclDeny = needOp(auth, conf, "prompt.read");
                            if (aclDeny != null) return aclDeny;
                            String txt = prompts.read(J.s(args, "name", ""));
                            return txt == null ? "没有这份提示词" : txt;
                        }
                        // 默认分支（show，含认不出的 op）：动作级 op
                        String aclDeny = needOp(auth, conf, "prompt.show");
                        if (aclDeny != null) return aclDeny;
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
                            // 动作级 op：重载初始提示词
                            String aclDeny = needOp(auth, conf, "prompt_write.reload");
                            if (aclDeny != null) return aclDeny;
                            prompts.reload();
                            return "已重载初始提示词（" + prompts.identity().raw().length() + " 字符）";
                        }
                        if ("clear".equals(op)) {
                            // 动作级 op：移除注入
                            String aclDeny = needOp(auth, conf, "prompt_write.clear");
                            if (aclDeny != null) return aclDeny;
                            boolean ok = prompts.inject().remove(scope, slot, key);
                            return ok ? "已移除" : "没有这条注入";
                        }
                        if ("write".equals(op)) {
                            // 动作级 op：新建 / 覆盖提示词文件
                            String aclDeny = needOp(auth, conf, "prompt_write.write");
                            if (aclDeny != null) return aclDeny;
                            return prompts.write(J.s(args, "name", ""), J.s(args, "text", "")) ? "已写入" : "写入失败";
                        }
                        if ("load".equals(op)) {
                            // 动作级 op：载入一份提示词并注入
                            String aclDeny = needOp(auth, conf, "prompt_write.load");
                            if (aclDeny != null) return aclDeny;
                            String name = J.s(args, "name", "");
                            String txt = prompts.read(name);
                            if (txt == null) return "没有这份提示词：" + name;
                            prompts.inject().put(scope, slot, "file:" + name, txt, "prompt:" + name);
                            return "已把 " + name + " 注入到 " + slot + "（" + txt.length() + " 字符）";
                        }
                        // 默认分支（inject，含认不出的 op）：动作级 op
                        String aclDeny = needOp(auth, conf, "prompt_write.inject");
                        if (aclDeny != null) return aclDeny;
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
                        // **所有 op 一律拒**（spawn/list/status/stop 全拒）。放在 op 判定之前：
                        // 这不是"你有没有权限"的问题，是"这个身份根本不许有这扇门"。
                        // 只靠闸①（派活处过滤工具表）不够：模型仍可能凭记忆喊这个名字，Registry 照旧会找到实现。
                        if (t != null && t.isSubagent()) {
                            return "[agent] 子 Agent 是最小单位：不能再派子 Agent（同级，由主 Agent 统一分配）";
                        }
                        Caller me = t == null ? null : t.caller();
                        boolean master = me != null && me.master();
                        String op = J.s(args, "op", "list").toLowerCase();
                        if ("spawn".equals(op)) {
                            // 动作级 op：派一个子 Agent
                            String aclDeny = needOp(auth, conf, "agent.spawn");
                            if (aclDeny != null) return aclDeny;
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
                            // 动作级 op：看单个任务
                            String aclDeny = needOp(auth, conf, "agent.status");
                            if (aclDeny != null) return aclDeny;
                            long id = J.l(args, "id", 0);
                            for (JsonObject o : agent.tasks(null, master ? null : (me == null ? "" : me.session()))) {
                                if (J.l(o, "id", 0) == id) return J.json(o);
                            }
                            return "没有这个任务（或它不是你的）";
                        }
                        if ("stop".equals(op)) {
                            // 动作级 op：中断在跑的回合
                            String aclDeny = needOp(auth, conf, "agent.stop");
                            if (aclDeny != null) return aclDeny;
                            // 按会话停：主人停全部；非主人 / 不带 session 时只停自己那个会话的回合
                            // （多会话并行之后，"一个用户敲停"不能打断别人的回合）
                            String want = J.s(args, "session", "").trim();
                            boolean all = master && (want.isEmpty() || "*".equals(want));
                            int n = agent.stop(all ? null : (want.isEmpty() && me != null ? me.session() : want),
                                    master && all);
                            return "已请求中断 " + n + " 个在跑的回合"
                                    + (all ? "（主人：全部会话）" : "（只停目标会话）");
                        }
                        // 默认分支（list，含认不出的 op）：动作级 op
                        String aclDeny = needOp(auth, conf, "agent.list");
                        if (aclDeny != null) return aclDeny;
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
                            // 动作级 op：往槽位注入文本
                            String aclDeny = needOp(auth, conf, "ctx.inject");
                            if (aclDeny != null) return aclDeny;
                            String text = J.s(args, "text", "");
                            if (Str.blank(text)) return "需要 text";
                            String key = J.s(args, "key", "ctx");
                            prompts.inject().put(scope, slot, key, text, "ctx");
                            return "已注入 " + slot + "（作用域 " + scope + "）";
                        }
                        if ("clear".equals(op)) {
                            // 动作级 op：清空注入
                            String aclDeny = needOp(auth, conf, "ctx.clear");
                            if (aclDeny != null) return aclDeny;
                            if (!master && Str.has(scopeArg) && !scopeArg.equals(own)) {
                                return Acl.DENY_PREFIX + "只能清自己的注入";
                            }
                            prompts.inject().clearScope(master && Str.has(scopeArg) ? scopeArg : own);
                            return "已清空作用域 " + (master && Str.has(scopeArg) ? scopeArg : own) + " 的注入";
                        }
                        // 默认分支（list，含认不出的 op）：动作级 op
                        String aclDeny = needOp(auth, conf, "ctx.list");
                        if (aclDeny != null) return aclDeny;
                        return J.json(prompts.inject().list(own));
                    }
                }));

        // ---------------- ⑥ perm：好感度（关系值） + 权限文件（授权/封禁/撤回/重排） ----------------
        reg.add(Tool.of("perm")
                .desc(PERM_DESC)
                .returns("好感度：身份与数值（whoami/get/list/set/reset）；权限：条目原文或回执（show/gen/run/ban/revoke）")
                .params(schemaPerm())
                .examples(java.util.Arrays.asList(PERM_EXAMPLES))
                .handler(new Tool.Handler() {
                    @Override
                    public Object call(JsonObject args, Turn t) {
                        Caller c = t == null ? sair.v4.ctx.Ctx.caller() : t.caller();
                        return permTool(args, out, auth, favor, c);
                    }
                }));

        // ---------------- ⑥b 自省：tools（找得到 / 看得懂 / 知道能不能用） ----------------
        //
        // 主人要的那条工作流：**知道该用什么工具 → 不知道就查 → 读说明书 → 验证行不行 → 行就做 /
        // 不行就直说 → 给结论**。前四步全靠这一把：清单（list）、按意图找（search）、
        // 完整说明书 + **以调用者现在的身份能不能用**（show）。
        // 边界（与别处一致）：只看 Registry.visible(c) 里的工具 —— 不给不该看的人暴露能力面；
        // 判定一律现算（auth.ops().of(名) + auth.allowed(c, op)），基板不缓存、不猜。
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
                        DeepSeek ai = boot.ai();
                        if ("info".equals(op)) {
                            // 动作级 op：看当前模型信息
                            String aclDeny = needOp(auth, conf, "model.info");
                            if (aclDeny != null) return aclDeny;
                            JsonObject o = new JsonObject();
                            o.addProperty("configured", conf.model());
                            o.addProperty("resolved", conf.resolveModel());
                            o.addProperty("auto", conf.isAutoModel());
                            o.addProperty("url", conf.apiUrl());
                            o.addProperty("key_set", Str.has(conf.apiKey()));
                            return J.json(o);
                        }
                        if ("balance".equals(op)) {
                            // 动作级 op：查余额
                            String aclDeny = needOp(auth, conf, "model.balance");
                            if (aclDeny != null) return aclDeny;
                            JsonObject b = ai.balance();
                            return b == null ? "查询余额失败" : J.json(b);
                        }
                        if ("models".equals(op)) {
                            // 动作级 op：列模型清单
                            String aclDeny = needOp(auth, conf, "model.models");
                            if (aclDeny != null) return aclDeny;
                            return J.json(ai.models());
                        }
                        JsonArray msgs = new JsonArray();
                        String sys = J.s(args, "system", "");
                        if (Str.has(sys)) msgs.add(Msg.system(sys));
                        if ("vision".equals(op)) {
                            // 动作级 op：看图（出网，走多模态分支）
                            String aclDeny = needOp(auth, conf, "model.vision");
                            if (aclDeny != null) return aclDeny;
                            List<String> images = J.strings(args, "images");
                            if (!images.isEmpty()) {
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
                            return modelReply(ai, true, msgs, args);
                        }
                        // 默认分支（chat，含认不出的 op）：动作级 op
                        String aclDeny = needOp(auth, conf, "model.chat");
                        if (aclDeny != null) return aclDeny;
                        msgs.add(Msg.user(J.s(args, "text", "")));
                        return modelReply(ai, false, msgs, args);
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
                        // 工具路径<b>只走"装好闸门"的实例</b>：回落到未装闸门的 api 等于把 op 判定整层删掉
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
                            // 目录不是"一个动作"：它的每一行按 napcat.<动作> 各判一次，
                            // 只把放行的动作名交出去（判不动的动作名不进目录 —— 不给越权者暴露能力面）。
                            JsonObject cat = api.catalog();
                            JsonObject visible = new JsonObject();
                            Caller who = napcatSubject(conf);
                            if (auth == null) {
                                String why = "[tool] napcat 权限面没有装配（auth == null）—— 拒绝列出动作目录";
                                if (out != null) out.err(why);
                                return why;
                            }
                            for (Map.Entry<String, com.google.gson.JsonElement> e : cat.entrySet()) {
                                // 走同一口判定（needOpAs）：罢工态下目录里一个动作都不放行（SPEC §4）。
                                if (needOpAs(auth, who, "napcat." + e.getKey()) == null) {
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
                        // 动作级 op：动作名就是代码里那个 action 字符串（napcat.<动作名>）
                        // 判定走同一口 needOpAs（不再直呼 auth.allow）—— 罢工闸因此覆盖到 NapCat 一族。
                        String deny = needOpAs(auth, who, "napcat." + action);
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
                        // 每次用到时读配置（consoleTap* 改动即时生效：上限/开关/转发档位）
                        sair.v4.term.SfwOut.installTap(boot.conf());

                        if ("read".equals(op)) {
                            // 动作级 op：读控制台输出
                            String aclDeny = needOp(auth, conf, "console.read");
                            if (aclDeny != null) return aclDeny;
                            long since = J.l(args, "since_seq", 0L);
                            int max = J.i(args, "tail_chars", 0);
                            String filter = J.s(args, "filter", "");
                            return sair.v4.term.SfwOut.read(since, max, filter);
                        }
                        if ("run".equals(op)) {
                            // 动作级 op：跑一条框架命令（与 Host.sfwRun 同一档事）
                            String aclDeny = needOp(auth, conf, "console.run");
                            if (aclDeny != null) return aclDeny;
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
                            // 动作级 op：看最近的框架命令
                            String aclDeny = needOp(auth, conf, "console.history");
                            if (aclDeny != null) return aclDeny;
                            return sair.v4.term.SfwOut.history(J.i(args, "limit", 20));
                        }
                        if ("size".equals(op)) {
                            // 动作级 op：看控制台规模
                            String aclDeny = needOp(auth, conf, "console.size");
                            if (aclDeny != null) return aclDeny;
                            return sair.v4.term.SfwOut.size();
                        }
                        if ("visible".equals(op)) {
                            // 动作级 op：全量读取当前可见全文
                            String aclDeny = needOp(auth, conf, "console.visible");
                            if (aclDeny != null) return aclDeny;
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
                            // 动作级 op：清屏
                            String aclDeny = needOp(auth, conf, "console.clear");
                            if (aclDeny != null) return aclDeny;
                            sair.v4.term.SfwOut con = boot.console();
                            if (con == null) return "控制台不可用（当前输出不是 SFW 控制台）";
                            con.clear();
                            // 说清缓冲语义：/clear 清的是控制台文档（与命令历史），捕获缓冲照旧留着最近输出
                            return "已清屏（捕获缓冲仍保留 " + sair.v4.term.ConsoleTap.lines() + " 条，可继续 read）";
                        }
                        if ("print".equals(op) || op.isEmpty()) {
                            // 动作级 op：往控制台打一行（默认分支，含认不出的 op）
                            String aclDeny = needOp(auth, conf, "console.print");
                            if (aclDeny != null) return aclDeny;
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
                        if ("remove".equals(op)) {
                            // 动作级 op：删一条
                            String aclDeny = needOp(auth, conf, "alarm.remove");
                            if (aclDeny != null) return aclDeny;
                            return tick.remove(J.l(args, "id", 0), me) ? "已删除" : "没有这个闹钟（或它不是你的）";
                        }
                        if ("clear".equals(op)) {
                            // 动作级 op：清空自己能看到的
                            String aclDeny = needOp(auth, conf, "alarm.clear");
                            if (aclDeny != null) return aclDeny;
                            int n = tick.clear(me);
                            return master ? ("已清空 " + n + " 个闹钟") : ("已清空你自己的 " + n + " 个闹钟");
                        }
                        if ("done".equals(op)) {
                            // 动作级 op：办妥收尾
                            String aclDeny = needOp(auth, conf, "alarm.done");
                            if (aclDeny != null) return aclDeny;
                            long id = J.l(args, "id", 0);
                            boolean ok = tick.mark(id, Tick.ST_DONE, J.s(args, "result", ""), J.s(args, "why", ""), me);
                            return ok ? ("#" + id + " 已记成办妥") : ("没有这个闹钟（或它不是你的）：#" + id);
                        }
                        if ("fail".equals(op)) {
                            // 动作级 op：没办成收尾
                            String aclDeny = needOp(auth, conf, "alarm.fail");
                            if (aclDeny != null) return aclDeny;
                            long id = J.l(args, "id", 0);
                            boolean ok = tick.mark(id, Tick.ST_FAILED, J.s(args, "result", ""), J.s(args, "why", ""), me);
                            return ok ? ("#" + id + " 已记成没办成") : ("没有这个闹钟（或它不是你的）：#" + id);
                        }
                        if ("defer".equals(op)) {
                            // 动作级 op：顺延或给待办排期
                            String aclDeny = needOp(auth, conf, "alarm.defer");
                            if (aclDeny != null) return aclDeny;
                            long id = J.l(args, "id", 0);
                            long when = parseWhen(J.s(args, "at", ""), J.i(args, "in", 0));
                            if (when <= 0) return "时间无法识别（用 at=\"HH:mm\"/\"+30m\"/\"明天 09:00\" 或 in=分钟）";
                            boolean ok = tick.defer(id, when, J.s(args, "why", ""), me);
                            return ok ? ("#" + id + " 已顺延到 " + stamp(when))
                                      : ("顺延不了 #" + id + "：别人委派的是工作不能拖，或者这条不是你的");
                        }
                        if ("add".equals(op)) {
                            // 动作级 op：记一件（写 at/in = 到点触发；不写时间 = 先记成待办）
                            String aclDeny = needOp(auth, conf, "alarm.add");
                            if (aclDeny != null) return aclDeny;
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
                        // 默认分支（list，含认不出的 op）：动作级 op
                        // 读：主人看全部（mine=true 时只看自己委派的），别人只看自己委派的
                        String aclDeny = needOp(auth, conf, "alarm.list");
                        if (aclDeny != null) return aclDeny;
                        boolean onlyMine = J.b(args, "mine", false);
                        JsonArray arr = new JsonArray();
                        // 主人走**无参**那条（= 全部）；给 alarms(Caller) 传 null 是"没有主体" ⇒ 空（口径见 Tick.alarms）。
                        List<JsonObject> visible = master ? tick.alarms() : tick.alarms(me);
                        for (JsonObject a : visible) {
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

    /**
     * 内置工具的全部 op（= 技能管控表的骨架：{@code ai/perm gen} 按这份清单把每个 op 列成一行空 Run）。
     *
     * <p><b>由装配期调用</b>：{@code Builtins.register(boot)} 里已经用 {@code boot.auth().ops()} 调过一次
     * （见 register 开头那句 declareOps）；Boot 侧若还有别的装配入口要补登记，直接
     * {@code Builtins.declareOps(auth.ops())} —— 本方法只登记，不做任何判定、不碰盘。</p>
     *
     * <p>NapCat 的 op 从 {@link sair.v4.qq.Api#actionNames()}（动作目录）生成：目录里加一个动作，
     * 这里就多一个 {@code napcat.<动作名>}，两边不会漂。</p>
     */
    public static void declareOps(OpList ops) {
        if (ops == null) return;
        // ① 六库：读 / 写 / 管理
        ops.add("store", "list", "列库里的行");
        ops.add("store", "get", "取一条");
        ops.add("store", "search", "检索");
        ops.add("store", "count", "数行数");
        ops.add("store_write", "put", "写一行");
        ops.add("store_write", "update", "改一行");
        ops.add("store_write", "delete", "删一行");
        ops.add("store_admin", "stat", "六库统计");
        ops.add("store_admin", "maintain", "维护清理");
        ops.add("store_admin", "optimize", "合并全文索引段");
        ops.add("store_admin", "vacuum", "回收空闲页");
        ops.add("store_admin", "export", "导出 JSONL");
        ops.add("store_admin", "import", "导入覆盖");
        // ② 技能库 / 动态执行
        ops.add("skill", "list", "技能清单");
        ops.add("skill", "read", "读技能说明书");
        ops.add("skill", "validate", "技能库体检");
        ops.add("skill_write", "add", "新增技能");
        ops.add("skill_write", "update", "覆盖技能内容");
        ops.add("skill_write", "delete", "删技能");
        ops.add("skill_write", "reload", "重扫技能库");
        ops.add("skill_write", "read_source", "读技能 java 源码");
        ops.add("skill_write", "draft", "写草稿");
        ops.add("skill_write", "drafts", "列草稿");
        ops.add("skill_write", "promote", "草稿搬进技能库");
        ops.add("exec", "java", "编译并运行一段 Java");
        ops.add("exec", "cmd", "执行系统命令");
        // ③ 提示词
        ops.add("prompt", "show", "看初始身份提示词");
        ops.add("prompt", "list", "列提示词文件");
        ops.add("prompt", "read", "读一份提示词");
        ops.add("prompt", "sections", "初始提示词的分节名");
        ops.add("prompt_write", "load", "载入一份提示词并注入");
        ops.add("prompt_write", "inject", "注入一段文本");
        ops.add("prompt_write", "write", "新建或覆盖提示词文件");
        ops.add("prompt_write", "clear", "移除注入");
        ops.add("prompt_write", "reload", "重载初始提示词");
        // ④ Agent 调度 / ⑤ 上下文注入
        ops.add("agent", "spawn", "派一个子 Agent");
        ops.add("agent", "list", "任务清单表");
        ops.add("agent", "status", "单个任务状态");
        ops.add("agent", "stop", "中断在跑的回合");
        ops.add("ctx", "inject", "往槽位注入文本");
        ops.add("ctx", "list", "列当前注入项");
        ops.add("ctx", "clear", "清空注入");
        // ⑥ perm：好感度面 + 权限文件面
        ops.add("perm", "whoami", "我是谁（含我的好感度）");
        ops.add("perm", "get", "查一个人的好感度");
        ops.add("perm", "list", "好感度榜单");
        ops.add("perm", "set", "直接定好感度");
        ops.add("perm", "reset", "清空好感度");
        ops.add("perm", "show", "看合并后的权限表");
        ops.add("perm", "gen", "在已有权限文件里重排（不新建文件）");
        ops.add("perm", "run", "授权（Run = 可用）");
        ops.add("perm", "ban", "封禁（Ban = 不可用）");
        ops.add("perm", "revoke", "撤回条目");
        // ⑥b/⑥c 自省
        ops.add("tools", "list", "列你能用的工具");
        ops.add("tools", "search", "按意图找工具");
        ops.add("tools", "show", "读一把工具的说明书");
        ops.add("config", "list", "列已知配置键");
        ops.add("config", "get", "看一个键");
        ops.add("config", "set", "改一个键");
        // ⑦ 模型直连
        ops.add("model", "chat", "问一句");
        ops.add("model", "vision", "看图");
        ops.add("model", "balance", "查余额");
        ops.add("model", "models", "模型清单");
        ops.add("model", "info", "当前模型信息");
        // ⑧ NapCat：每个动作一个 op（动作目录是唯一来源）
        List<String> acts = sair.v4.qq.Api.actionNames();
        for (int i = 0; i < acts.size(); i++) {
            ops.add("napcat", acts.get(i), "NapCat 动作");
        }
        // ⑨ 控制台 / ④ 到点的事
        ops.add("console", "read", "读控制台最近的输出");
        ops.add("console", "run", "跑一条框架命令");
        ops.add("console", "history", "最近的框架命令");
        ops.add("console", "print", "往控制台打一行");
        ops.add("console", "clear", "清屏");
        ops.add("console", "size", "控制台规模");
        ops.add("console", "visible", "当前可见全文");
        ops.add("alarm", "add", "记一件到点的事");
        ops.add("alarm", "list", "闹钟清单");
        ops.add("alarm", "remove", "删一条");
        ops.add("alarm", "clear", "清空自己的");
        ops.add("alarm", "done", "办妥收尾");
        ops.add("alarm", "fail", "没办成收尾");
        ops.add("alarm", "defer", "顺延或排期");
    }

    /**
     * 模型直连（chat / vision）的收口：临时换模型 + 把结果规整成字符串。
     * <p>两个动作共用它，但<b>op 判定各自在自己的分支里判</b>（见 model 工具的实现）。</p>
     */
    private static String modelReply(DeepSeek ai, boolean vision, JsonArray msgs, JsonObject args) {
        JsonObject opts = new JsonObject();
        String m = J.s(args, "model", "");
        if (Str.has(m)) opts.addProperty("model", m);
        Res r = vision ? ai.vision(msgs, opts) : ai.chat(msgs, null, opts);
        return r.ok() ? Str.nz(r.content) : ("模型调用失败：" + r.error);
    }

    // ==================== ⑥ perm：好感度（关系值） + 权限文件（授权 / 撤销 / 重排） ====================
    //
    // 一把工具两个面，op 名固定（与控制台命令面的名字不同，这一处是故意的）：
    //   好感度面：whoami（我是谁 + 我的好感度）· get（查一个人）· list（榜单）· set（直接定值）· reset（清空）
    //   权限面：show（看合并后的表）· gen（在已有权限文件里重排）· run（授权）· ban（封禁）· revoke（撤回）
    //
    // 口径一句话：好感度只是"关系值"，<b>不判任何权限</b>；谁能用哪个 op 由按归属分散的权限文件
    // （数据根 perms-core.jsonc + 每个技能自己的 skills\<技能名>\perms.jsonc）说了算 ——
    // Run 白名单 = 可用 / Ban 黑名单 = 不可用 / 空 = 未授权 = 不可用，优先级 Ban > Run > 空；
    // MASTER 与她本人（SYSTEM）恒全权，不受表影响。
    //
    // 权限面那五个另有一层<b>业务硬判</b>：权限文件是主人的信息，show/run/ban/gen/revoke 只有主人能真正调通
    // —— 连她自己的自主行为（SYSTEM，op 判定恒放行）也不给。

    /** 非主人来改账本时唯一的那句话（不解释、不透露账本内容）。 */
    public static final String PERM_DENY = "这得主人定";

    /** 十个 op 的人话名（回执 / 报错 / 说明共用一份文案）。 */
    private static final String PERM_OPS =
            "whoami 我是谁（含我的好感度）· get 查一个人 · list 好感度榜单 · set 直接定值 · reset 清空"
            + " · show 看权限表 · gen 重排已有权限文件 · run 授权 · ban 封禁 · revoke 撤回";

    /**
     * perm 工具的口径正文（<b>她自己读的就是这一段</b>：人话 → 表里条目的映射 + 两条优先级规则）。
     *
     * <p><b>为什么放工具描述、不并进 {@code identity.md}</b>：身份提示词是<b>每轮常驻</b>的预算，
     * 而这份口径只在"主人说改权限"这一刻有用，工具描述正是那一刻必然送进模型的上下文。</p>
     *
     * <p>{@code public} 是给探针断言用的（核这段文本覆盖了哪些人话说法，免得口径悄悄被改窄）。</p>
     */
    public static final String PERM_DESC =
            "一、好感度（关系值，**不判任何权限** —— 只是她跟这个人处得怎么样）："
            + "whoami 我是谁 · get 查一个人 · list 榜单 · set 直接定值 · reset 清空。\n"
            + "二、权限（**权限的唯一口径**：身份 × op → Ban / Run / 空；权限文件按归属分散 —— 基板工具写"
            + "数据根下 " + Acl.CORE_FILE_NAME + "，每个技能写自己的 skills\\<技能名>\\" + Acl.SKILL_FILE_NAME
            + "，技能文件里写了不属于它的 op 会被忽略，技能没有那份文件 = 它的全部 op 不授权）："
            + "show 看合并后的表 · gen 只在已有权限文件里重排补注释（不新建文件）· run 授权（= 可用）· ban 封禁 · revoke 撤回。\n"
            + "· 表里一条一行：Run[\"op\",\"身份\"…] = 可用 · Ban[\"op\",\"身份\"…] = 不可用。"
            + "op = 工具名 或 工具名.动作名（写工具名 = 覆盖它的全部动作，如 memory 盖住 memory.remember）。\n"
            + "· 身份：user:<QQ号>（个人，跨群有效）· group:<群号>（只在该群会话生效）· ALLUSER（全体用户）；"
            + "裸数字 = user:<QQ>；「群123456」也认。**MASTER 与她本人（SYSTEM）恒全权，写进表里会被拒收**。\n"
            + "· 三态：Ban 黑名单 = 不可用；Run 白名单 = 可用；**空**（写了 Run 却一个身份都没给，或者表里根本没这一行）"
            + "= 未授权 = 不可用。优先级 Ban > Run > 空：同一个 op 上，该身份只要命中任何一条 Ban 就拒。\n"
            + "· 归属：不给谁开就是默认（技能目录里没有 " + Acl.SKILL_FILE_NAME + " 的技能，它的全部 op 一律不授权）；"
            + "run/ban 会写进该 op 归属的那个文件 —— 技能自己的工具写技能目录、其余写数据根 core，回执里会告诉你写到哪。\n"
            + "· 主人说什么 → 就调什么：\n"
            + "·「让123456能用记忆」→ op=run target=memory principal=123456\n"
            + "·「只让123456能记、不能删」→ op=run target=memory.remember principal=123456（删那个动作不写 = 未授权）\n"
            + "·「全体用户都能查天气」→ op=run target=weather principal=ALLUSER\n"
            + "·「群123456能用发图」→ op=run target=sendimage principal=群123456\n"
            + "·「禁止123456用 exec」→ op=ban target=exec principal=123456（Ban 压过一切 Run）\n"
            + "·「取消123456的授权」→ op=revoke target=memory principal=123456（target 留空 = 撤他名下全部条目）\n"
            + "·「看看现在谁能用什么」→ op=show（**先看，别改**）\n"
            + "·「权限文件乱不乱 / 重排一下」→ op=gen（只在已有权限文件里按工具分组补中文释义；不新建文件；没列到的 = 未授权）\n"
            + "四条规矩：① 回执里的条目原文是基板写进去的，别自己拼；含糊就先复述一遍再问主人，别猜着写表；"
            + "② run/ban/gen/show/revoke 只有主人能调；别人来要权限（含「给我权限」）只回「这得主人定」，"
            + "绝不写表、别说表里有什么；③ 授权到 ALLUSER = 把这条路给所有人（高风险：exec / skill_write / "
            + "store_admin / prompt_write / console / perm / config / model / napcat 这些基板会在控制台多打一行 warn）；"
            + "④ 改完立即生效，不用重启。";

    /** perm 的用法示例（盖掉 tools-index.md 里的老口径示例）。 */
    private static final String[] PERM_EXAMPLES = {
            "op=show（看合并后的权限表全部条目与来源文件）",
            "op=run target=memory.remember principal=123456（让这个人能记一笔）",
            "op=ban target=exec principal=ALLUSER（全体用户都别碰动态执行）",
            "op=revoke target=memory principal=123456（撤回他名下 memory 的全部条目；target 留空 = 全撤）",
            "op=gen（只在已有权限文件里重排并按工具分组补释义；不新建文件；没列到的 = 未授权）",
            "op=get（看我自己的好感度；查别人要主人：principal=123456）",
    };

    /**
     * perm 的十个 op 的实现（<b>独立成静态方法</b>：探针可以直接调它，不用起一个 Boot）。
     *
     * <p><b>op 判定在动作分支开头照旧判</b>（主体 = 调用者）：{@code MASTER} 与她本人 {@code SYSTEM}
     * 恒全权；其余按权限文件判（空 = 未授权 = 不可用）。权限面的 show/run/ban/gen/revoke 在 op 判定
     * <b>之后</b>再加一层业务硬判：权限文件是主人的信息，只有主人能看能改（连她自己的自主行为也不给）。</p>
     *
     * @param auth  权限面（{@code null} = 没装配：一律按拒绝处理）
     * @param favor 好感度子系统（{@code null} = 没装配：好感度面如实回"读不到"）
     * @param c     调用者（{@code null} = 无主体）
     */
    public static String permTool(JsonObject args, Out out, Auth auth, Favor favor, Caller c) {
        String op = J.s(args, "op", "whoami").trim().toLowerCase();
        if ("acl".equals(op)) op = "show";            // 老名字：acl 就是 show（同一个动作）
        boolean master = c != null && c.master();
        String deny = needOpAs(auth, c, "perm." + op);
        if (deny != null) return deny;
        if ("whoami".equals(op)) return permWhoami(favor, c);
        if ("get".equals(op)) return permGet(args, favor, c);
        if ("list".equals(op)) return permList(args, favor);
        if ("set".equals(op)) return permSet(args, favor, c);
        if ("reset".equals(op)) return permReset(favor);
        // 权限面：账本是主人的信息 —— 看与改都只有主人
        if ("show".equals(op)) {
            if (!master) return permOwnerOnly(out, c);
            return permShow(auth);
        }
        if ("gen".equals(op)) {
            if (!master) return permOwnerOnly(out, c);
            return permGen(out, auth);
        }
        if ("run".equals(op)) {
            if (!master) return permOwnerOnly(out, c);
            return permGrant(args, out, auth, false);
        }
        if ("ban".equals(op)) {
            if (!master) return permOwnerOnly(out, c);
            return permGrant(args, out, auth, true);
        }
        if ("revoke".equals(op)) {
            if (!master) return permOwnerOnly(out, c);
            return permRevoke(args, out, auth);
        }
        return "op 只认 " + PERM_OPS + "。旧的 check/levels/level/setlevel 已经没有了（资源不再有位、"
                + "好感度不再插手权限）：要看自己手上有什么用 tools op=show，要看表用 perm op=show。";
    }

    /** 非主人调权限面时的那五个字（控制台留一行）。 */
    private static String permOwnerOnly(Out out, Caller c) {
        if (out != null) out.dim("[acl] 非主人调用技能管控表（" + (c == null ? "无主体" : c.label()) + "），已拒");
        return PERM_DENY;
    }

    // ---------------- 好感度面 ----------------

    /** {@code whoami}：我是谁 + 我的好感度（判不判得到由 op 判定说了算）。 */
    private static String permWhoami(Favor favor, Caller c) {
        JsonObject me = new JsonObject();
        if (c == null) {
            me.addProperty("caller", "无主体（没有可判定的身份）");
            return J.json(me);
        }
        boolean master = c.master();
        me.addProperty("kind", c.kind().name().toLowerCase(java.util.Locale.ROOT));   // master/system/alluser
        me.addProperty("entry", c.isConsole() ? "console" : "qq");
        me.addProperty("qq", c.qq());
        me.addProperty("session", c.session());
        me.addProperty("master", master);
        me.addProperty("system", c.system());
        if (c.isGroup()) {
            me.addProperty("group", c.groupId());
            me.addProperty("role", c.groupRole());
        }
        // 好感度只是"关系值"，不判任何权限；主人与本地控制台不适用（-1）
        if (master || c.isConsole() || favor == null) {
            me.addProperty("favor", -1);
        } else {
            double v = c.favor();
            me.addProperty("favor", (long) v);
            me.addProperty("level", favor.levelName(v));
        }
        me.addProperty("note", "好感度只是关系值，不判任何权限；你能用哪些工具/动作由技能管控表"
                + "（身份 × op：Run = 可用 / Ban = 不可用 / 空 = 未授权）决定 —— "
                + "看自己手上有什么用 tools op=list。");
        return J.json(me);
    }

    /** {@code get}：查一个人的好感度（principal 留空 = 自己；查别人只有主人与她自己的自主行为）。 */
    private static String permGet(JsonObject args, Favor favor, Caller c) {
        if (favor == null) return "好感度子系统没装配（读不到 favor 表）";
        String rawP = J.s(args, "principal", "").trim();
        long qq;
        if (Str.blank(rawP)) {
            if (c == null || c.qq() <= 0L) {
                return Acl.DENY_PREFIX + "查不了：这一轮没有可用的 QQ 身份（要查别人就把 principal 写成 QQ 号）";
            }
            qq = c.qq();
        } else {
            Long id = qqOf(rawP);
            if (id == null) {
                return "主体写法认不出：" + rawP + "（好感度只认 QQ 号：裸数字 / user:<QQ>；留空 = 你自己）";
            }
            qq = id.longValue();
        }
        boolean mine = c != null && c.qq() == qq;
        boolean owner = c != null && (c.master() || c.system());
        if (!mine && !owner) {
            return Acl.DENY_PREFIX + "查别人的好感度要主人 —— 你自己的用 perm op=get（不带 principal）";
        }
        JsonObject o = favor.snapshot(qq);
        JsonArray arr = new JsonArray();
        for (JsonObject e : favor.events(qq, 5)) arr.add(e);
        o.add("recent", arr);
        return J.json(o);
    }

    /** QQ 号写法 → 号码（认不出返回 {@code null}）：只认裸数字 / {@code user:<QQ>} / {@code qq:<QQ>} / {@code 用户<QQ>}。 */
    private static Long qqOf(String raw) {
        String k = Acl.principalKey(raw);
        if (k == null || !k.startsWith(Acl.USER_PREFIX)) return null;
        try {
            return Long.valueOf(Long.parseLong(k.substring(Acl.USER_PREFIX.length()).trim()));
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** {@code list}：好感度榜单（按数值降序）。 */
    private static String permList(JsonObject args, Favor favor) {
        if (favor == null) return "好感度子系统没装配（读不到 favor 表）";
        int limit = J.i(args, "limit", 20);
        if (limit <= 0) limit = 20;
        List<JsonObject> top = favor.top(limit);
        StringBuilder sb = new StringBuilder("好感度榜单（按数值降序，前 " + top.size() + " 名）：");
        if (top.isEmpty()) return sb.append("（一个人都还没有）").toString();
        for (int i = 0; i < top.size(); i++) {
            JsonObject o = top.get(i);
            sb.append("\n  ").append(i + 1).append(". ").append(J.l(o, "qq", 0L))
              .append("  ").append((long) J.d(o, "value", 0D))
              .append("  ").append(Str.nz(J.s(o, "level", "")));
            String note = Str.oneLine(Str.nz(J.s(o, "note", "")));
            if (Str.has(note)) sb.append("  ← ").append(Str.cut(note, 40));
        }
        sb.append("\n（好感度只是关系值，不判任何权限）");
        return sb.toString();
    }

    /** {@code set}：直接定值（主人手动调关系值；不受单次加减区间限制，每一次都写一行流水）。 */
    private static String permSet(JsonObject args, Favor favor, Caller c) {
        if (favor == null) return "好感度子系统没装配（读不到 favor 表）";
        String rawP = J.s(args, "principal", "").trim();
        long qq;
        if (Str.blank(rawP)) {
            if (c == null || c.qq() <= 0L) return "要给 QQ 号：perm op=set principal=123456 value=100";
            qq = c.qq();
        } else {
            Long id = qqOf(rawP);
            if (id == null) return "主体写法认不出：" + rawP + "（只认 QQ 号：裸数字 / user:<QQ>）";
            qq = id.longValue();
        }
        if (args == null || !args.has("value")) {
            return "要给 value（0 是合法值）：perm op=set principal=" + qq + " value=100";
        }
        double v = J.d(args, "value", -1D);
        if (v < 0D) return "好感度不能是负数（要给 0 就写 value=0）";
        Favor.Change ch = favor.setValue(qq, v, J.s(args, "note", "主人直改"), c == null ? "perm" : c.label());
        StringBuilder sb = new StringBuilder();
        sb.append("已把 ").append(qq).append(" 的好感度定为 ").append((long) ch.after)
          .append("（").append(favor.levelName(ch.after)).append("）");
        if (ch.before != ch.after) {
            sb.append("；原来 ").append((long) ch.before).append("（").append(favor.levelName(ch.before)).append("）");
        }
        sb.append("\n（好感度只是关系值，不判任何权限；这次改动已经写了一行 favor_event 流水）");
        return sb.toString();
    }

    /** {@code reset}：清空好感度（favor 表清干净，流水表保留）。 */
    private static String permReset(Favor favor) {
        if (favor == null) return "好感度子系统没装配（读不到 favor 表）";
        int n = favor.resetAll();
        return "已清空 " + n + " 条好感度记录（所有人回到初识；favor_event 流水表原样保留）";
    }

    // ---------------- 权限面：权限文件（core + 每个技能自己的 perms.jsonc，合并视图） ----------------

    /** 权限文件的人读路径（数据根下用相对路径；根外给全路径）。 */
    private static String permPath(Acl acl, File f) {
        if (f == null) return "(无)";
        try {
            File root = acl == null ? null : acl.dataRoot();
            if (root != null) {
                String r = root.getAbsolutePath();
                String p = f.getAbsolutePath();
                if (p.startsWith(r + File.separator)) {
                    return root.getName() + File.separator + p.substring(r.length() + 1);
                }
            }
        } catch (Throwable ignored) {
        }
        return f.getAbsolutePath();
    }

    /** 回执里"写到哪个文件"：按<b>归属</b>算出来的那个落点；没有具体 op 就把这次写了的文件都列出来。 */
    private static String permWrote(Acl acl, String op) {
        if (acl == null) return "(无)";
        if (op != null && !op.trim().isEmpty()) {
            File tf = acl.targetOf(op);
            if (tf != null) return permPath(acl, tf);
        }
        List<File> ws = acl.writtenFiles();
        if (ws.isEmpty()) return permPath(acl, acl.file());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ws.size(); i++) sb.append(i == 0 ? "" : "、").append(permPath(acl, ws.get(i)));
        return sb.toString();
    }

    /** {@code show}：看合并后的权限表（条目原文 + 统计 + 来源汇总 + 还没进表的 op）。主人专属。 */
    private static String permShow(Auth auth) {
        if (auth == null) return "权限面没装配（读不到权限文件）";
        Acl acl = auth.acl();
        if (acl == null) return "权限面没装配（读不到权限文件）";
        StringBuilder sb = new StringBuilder();
        sb.append(acl.stat());
        sb.append("\n").append(acl.sourceStat());
        for (File f : acl.sources()) sb.append("\n  ").append(permPath(acl, f)).append("  ").append(acl.entriesOf(f)).append(" 条");
        sb.append("\n口径：Run[\"op\",\"身份\"…] = 可用 · Ban[…] = 不可用 · 空 = 未授权 = 不可用；"
                + "优先级 Ban > Run > 空；MASTER 与她本人（SYSTEM）不受表影响。");
        sb.append("\n权限文件按归属分散：基板工具写数据根 " + Acl.CORE_FILE_NAME + "；每个技能写自己的 "
                + "skills\\<技能名>\\" + Acl.SKILL_FILE_NAME + "（只写它自己提供的工具，越界条目忽略；"
                + "技能没有那份文件 = 它的全部 op 不授权）。");
        List<String> lines = acl.list();
        if (lines.isEmpty()) {
            sb.append("\n（表是空的：一个 op 都没授权 —— 没有权限文件就是默认；要开口：op=run target=<op> principal=<身份>）");
        }
        for (int i = 0; i < lines.size(); i++) sb.append("\n  ").append(lines.get(i));
        List<Acl.Reject> rej = acl.rejects();
        for (int i = 0; i < rej.size(); i++) {
            Acl.Reject r = rej.get(i);
            sb.append("\n  认不出的条目：").append(r.text());
        }
        List<String> missing = missingOps(acl, auth.ops());
        sb.append("\n\n清单里的 op 共 ").append(auth.allOps().size()).append(" 个，其中还没进表 ")
          .append(missing.size()).append(" 个");
        if (!missing.isEmpty()) {
            int show = Math.min(12, missing.size());
            for (int i = 0; i < show; i++) sb.append(i == 0 ? "：" : "、").append(missing.get(i));
            if (missing.size() > show) sb.append(" …");
            sb.append("（= 未授权；要开就 op=run）");
        }
        return sb.toString();
    }

    /** 清单里有、但表里一行都没提的 op（工具级那一行也算覆盖了它的全部动作）。 */
    private static List<String> missingOps(Acl acl, Ops ops) {
        List<String> out = new ArrayList<String>();
        if (acl == null || ops == null) return out;
        java.util.Set<String> covered = new java.util.HashSet<String>();
        for (Acl.Entry e : acl.entries()) covered.add(e.op());
        for (String op : ops.all()) {
            if (covered.contains(op)) continue;
            if (covered.contains(Ops.toolOf(op))) continue;
            out.add(op);
        }
        return out;
    }

    /**
     * {@code gen}：<b>只在已有权限文件里重排 / 补注释</b>（不新建文件）。主人专属。
     *
     * <p>没有权限文件 = 一切未授权，这正是默认 —— gen 不再生成"完整清单"。</p>
     */
    private static String permGen(Out out, Auth auth) {
        if (auth == null) return "权限面没装配（拿不到 op 清单）";
        Acl acl = auth.acl();
        if (acl == null) return "权限面没装配（读不到权限文件）";
        acl.reload();                                  // 权限文件是主人能手改的：写之前先重读，别盖掉手改的内容
        int before = acl.entries().size();
        boolean saved = acl.saveAll(auth, false);      // false = 只在已有文件里重排，不新建文件
        int after = acl.entries().size();
        if (out != null) out.dim("[acl] gen：" + before + " → " + after + " 条（" + acl.stat() + "）");
        StringBuilder sb = new StringBuilder();
        List<File> wrote = acl.writtenFiles();
        if (!saved) {
            sb.append("**没落盘**（权限文件写盘失败）");
        } else if (wrote.isEmpty()) {
            // 同控制台：wrote 为空也可能是"已有文件逐字节相同、sameText 跳过"，不是"文件不存在"。
            int onDisk = 0;
            for (File f : acl.sources()) if (f != null && f.isFile()) onDisk++;
            if (onDisk == 0) {
                sb.append("没有任何权限文件可重排（").append(Acl.CORE_FILE_NAME).append(" 与技能目录里的 ")
                  .append(Acl.SKILL_FILE_NAME).append(" 都不存在）—— gen 不新建文件；没有文件 = 一切未授权");
            } else {
                sb.append("已有 ").append(onDisk)
                  .append(" 份权限文件，本次没有需要重排的内容 —— gen 不新建文件");
            }
        } else {
            sb.append("已重排 ").append(wrote.size()).append(" 份权限文件：");
            for (int i = 0; i < wrote.size(); i++) sb.append(i == 0 ? "" : "、").append(permPath(acl, wrote.get(i)));
        }
        sb.append("\n表里现在 ").append(after).append(" 条，覆盖 op ").append(acl.byOp().size()).append(" 个");
        List<String> miss = acl.unwrittenOps();
        if (!miss.isEmpty()) {
            int show = Math.min(12, miss.size());
            sb.append("\n这些 op 还没有权限文件（= 不授权）：");
            for (int i = 0; i < show; i++) sb.append(i == 0 ? "" : "、").append(miss.get(i));
            if (miss.size() > show) sb.append(" …");
        }
        sb.append("\n要开口就给某个 op 加一条：perm op=run target=<op> principal=<身份>"
                + "（写进该 op 归属的那个文件，不存在就创建）");
        return sb.toString();
    }

    /**
     * {@code run} / {@code ban}：给一个身份在某个 op 上写 {@code Run}（可用）或 {@code Ban}（不可用）。主人专属。
     *
     * <p>同一个（op + 身份）只留最后写的那一条（{@link Acl#grant} 的口径）；表是人能手改的，
     * 所以写之前先重读一次。</p>
     */
    private static String permGrant(JsonObject args, Out out, Auth auth, boolean ban) {
        Acl acl = auth == null ? null : auth.acl();
        if (acl == null) return "权限面没装配（读不到权限文件），改不了";
        String target = J.s(args, "target", "").trim();
        String rawP = J.s(args, "principal", "").trim();
        String verb = ban ? "ban" : "run";
        if (target.isEmpty() || rawP.isEmpty()) {
            return "用法：perm op=" + verb + " target=<op> principal=<身份>\n"
                    + "  target＝op（工具名，或 工具名.动作名；写工具名 = 覆盖它的全部动作，如 memory / memory.remember）\n"
                    + "  principal＝身份（user:<QQ号> / group:<群号> / ALLUSER；裸数字 = user:<QQ>；"
                    + "「群123456」也认；逗号分开 = 一个身份一条）\n"
                    + "  MASTER 与她本人（SYSTEM）恒全权，写不进表里。";
        }
        if (!saneOp(target)) return "op 写法认不出：" + target + "（只认 工具名 或 工具名.动作名）";
        String[] tokens = splitPrincipals(rawP);
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < tokens.length; i++) {
            String k = Acl.principalKey(tokens[i]);
            if (k == null) {
                String why = Acl.principalReason(tokens[i]);
                return "身份写法认不出：" + tokens[i] + "\n  " + (Str.blank(why)
                        ? "身份只认 user:<QQ号> / group:<群号> / ALLUSER（裸数字 = user:<QQ>）" : why);
            }
            keys.add(k);
        }
        boolean known = auth.ops().known(target);
        acl.reload();                                  // 同上：写之前先重读
        StringBuilder sb = new StringBuilder();
        sb.append(ban ? "已封禁：" : "已授权：");
        for (int i = 0; i < keys.size(); i++) {
            if (!acl.grant(target, keys.get(i), ban, auth.ops().noteOf(target))) {
                return "写不进去：" + keys.get(i) + " " + target + "（身份或 op 有一处认不出，表没动）";
            }
            if (keys.size() > 1) sb.append("\n").append(i + 1).append(")");
            sb.append("\n  ").append(ban ? "Ban[\"" : "Run[\"").append(target).append("\",\"")
              .append(keys.get(i)).append("\"]");
            if (out != null && !ban && Acl.ALLUSER_KEY.equals(keys.get(i)) && riskyOp(target)) {
                out.warn("[acl] 高风险授权：Run[\"" + target + "\",\"ALLUSER\"] —— 这等于把这条路给所有人");
            }
        }
        boolean saved = acl.saveAll(auth);
        sb.append("\n").append(saved
                ? ("已写入 " + permWrote(acl, target) + "（立即生效，不用重启）")
                : "注意：写盘失败，改动只在内存里（重启会丢）！");
        if (!known) {
            sb.append("\n注意：`").append(target).append("` 不在当前 op 清单里（没有技能声明它）—— "
                    + "工具名的键只覆盖装载时已注册的动作；把完整的 `工具名.动作名` 精确键写进基板那份文件"
                    + " = 给一个将来才注册的动作预先授权（精确键命中时不查注册清单）。");
        }
        return sb.toString();
    }

    /**
     * {@code revoke}：撤回。给 {@code target} = 只撤这个 op 上的条目；{@code target} 留空 = 撤该身份名下的全部条目。
     * 主人专属。
     *
     * <p>注意（{@link Acl#revoke} 的口径）：一条条目带多个身份时，撤其中一个身份会把<b>整条</b>删掉 ——
     * 要只撤一个人，就别在更宽的身份（{@code ALLUSER}）上跟他写同一条。</p>
     */
    private static String permRevoke(JsonObject args, Out out, Auth auth) {
        Acl acl = auth == null ? null : auth.acl();
        if (acl == null) return "权限面没装配（读不到权限文件），改不了";
        String target = J.s(args, "target", "").trim();
        String rawP = J.s(args, "principal", "").trim();
        if (rawP.isEmpty()) {
            return "用法：perm op=revoke principal=<身份> [target=<op>]\n"
                    + "  target 给了 = 只撤这个 op 上的条目；target 留空 = 撤该身份名下的全部条目。";
        }
        if (!target.isEmpty() && !saneOp(target)) return "op 写法认不出：" + target + "（只认 工具名 或 工具名.动作名）";
        String[] tokens = splitPrincipals(rawP);
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < tokens.length; i++) {
            String k = Acl.principalKey(tokens[i]);
            if (k == null) {
                String why = Acl.principalReason(tokens[i]);
                return "身份写法认不出：" + tokens[i] + "\n  " + (Str.blank(why)
                        ? "身份只认 user:<QQ号> / group:<群号> / ALLUSER（裸数字 = user:<QQ>）" : why);
            }
            keys.add(k);
        }
        acl.reload();                                  // 同上：写之前先重读
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (int i = 0; i < keys.size(); i++) {
            String p = keys.get(i);
            // 先照表里的顺序把要撤的条目挑出来（回执要写它们的原文），再逐条撤
            List<Acl.Entry> victims = new ArrayList<Acl.Entry>();
            for (Acl.Entry e : acl.entries()) {
                if (!e.principals().contains(p)) continue;
                if (!target.isEmpty() && !e.op().equals(target)) continue;
                victims.add(e);
            }
            if (victims.isEmpty()) {
                sb.append("\n没有命中的条目：").append(p)
                  .append(target.isEmpty() ? "（他名下本来就没有条目）" : (" 在 " + target + " 上"));
                continue;
            }
            List<String> ops = new ArrayList<String>();
            for (int k = 0; k < victims.size(); k++) {
                if (!ops.contains(victims.get(k).op())) ops.add(victims.get(k).op());
            }
            for (int k = 0; k < ops.size(); k++) acl.revoke(ops.get(k), p);
            total += victims.size();
            sb.append("\n已撤回 ").append(p).append(" 的 ").append(victims.size()).append(" 条条目：");
            for (int k = 0; k < victims.size(); k++) sb.append("\n  ").append(victims.get(k).raw());
        }
        boolean saved = acl.saveAll(auth);
        if (out != null) out.dim("[acl] revoke：" + total + " 条条目已从表里删除（" + acl.stat() + "）");
        StringBuilder head = new StringBuilder(total == 0 ? "没有命中的条目（表没动）" : ("共撤掉 " + total + " 条"));
        head.append("\n").append(saved
                ? ("已写入 " + permWrote(acl, target) + "（立即生效，不用重启）")
                : "注意：写盘失败，改动只在内存里（重启会丢）！");
        head.append("\n撤掉之后就是「空」= 未授权 = 不可用；要放行别的身份另写一条 op=run。");
        return sb.append("\n").append(head).toString();
    }

    /** 多身份：逗号 / 顿号 / 分号 / 竖线分开（"123456，23456" = 两个身份，一个身份一条）。 */
    private static String[] splitPrincipals(String raw) {
        String[] parts = (raw == null ? "" : raw).split("[,，、;；|]");
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < parts.length; i++) {
            String t = parts[i] == null ? "" : parts[i].trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.toArray(new String[out.size()]);
    }

    /** op 写法检查：{@code 工具名} 或 {@code 工具名.动作名}（两段都非空、没有空白、只有一个点）。 */
    private static boolean saneOp(String op) {
        String s = Str.trim(op);
        if (s.isEmpty() || s.indexOf(' ') >= 0 || s.indexOf('\t') >= 0) return false;
        int i = s.indexOf('.');
        if (i < 0) return true;
        return i > 0 && i < s.length() - 1 && s.indexOf('.', i + 1) < 0;
    }

    /** 授权到 ALLUSER 的"高风险 op"（只是控制台多打一行的提醒，不参与任何判定）。 */
    private static boolean riskyOp(String op) {
        String tool = Ops.toolOf(op);
        return "exec".equals(tool) || "skill_write".equals(tool) || "store_admin".equals(tool)
                || "prompt_write".equals(tool) || "console".equals(tool) || "perm".equals(tool)
                || "config".equals(tool) || "model".equals(tool) || "napcat".equals(tool);
    }

    // ==================== ⑥b/⑥c 自省：tools（能力）/ config（配置） ====================
    //
    // 这两把是主人要的那条工作流的"机械化依据"：
    //   知道该用什么工具 → 不知道就查（tools list/search）→ 读说明书（tools show）
    //   → 验证行不行（show 里按 op 现算的那一格）→ 行就做 / 不行就直说 → 给结论。
    // 两条共同的边界：
    //   ① 能力面只列 Registry.visible(c) —— 不给不该看的人暴露工具面；
    //   ② 判定一律现算（auth.ops().of(名) + auth.allowed(c, op)），基板不缓存、不猜、不编。

    /** {@code tools} 的契约正文（她自己读的就是这一段）。 */
    public static final String TOOLS_DESC =
            "能力自省（找工具 / 读说明书 / 验证行不行）：list 列**你现在能用**的工具（名 + 一句话 + 归属）；"
            + "search 按**意图**找（「改设置」「发图」「查余额」）；show <工具名> 读完整说明书："
            + "描述、返回、**参数表**（类型/必填/默认/示例）、归属、**★以你现在的身份能不能用（哪个 op 能用也说清）**、"
            + "只读还是有副作用。\n"
            + "想不起工具名、不确定 op 怎么填、想确认「我这身份调不调得动」—— 先问我，别硬试"
            + "（撞权限墙会被连续失败闸门掐停整轮）。\n"
            + "边界：只列你**现在看得见**的工具（工具面按技能管控表里的 op 筛）；看不见的我不替你描述。";

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
        if ("list".equals(op)) {
            // 动作级 op：列工具清单
            String deny = needOpAs(auth, c, "tools.list");
            if (deny != null) return deny;
            return toolsList(reg, c, J.s(args, "keyword", ""));
        }
        if ("search".equals(op)) {
            // 动作级 op：按意图找
            String deny = needOpAs(auth, c, "tools.search");
            if (deny != null) return deny;
            return toolsSearch(reg, c, J.s(args, "query", ""));
        }
        if ("show".equals(op)) {
            // 动作级 op：读某一把的说明书
            String deny = needOpAs(auth, c, "tools.show");
            if (deny != null) return deny;
            return toolsShow(args, out, auth, conf, reg, c);
        }
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
            return head + "\n（你现在一把都用不了：工具面按技能管控表里的 **op** 筛 —— "
                    + "没有 Run 命中你，连工具名都看不到。要放开只能由主人写授权："
                    + "perm op=run target=<工具名> principal=<你>）";
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
            // 看不见 = 不描述（工具面按 op 筛，说明书也是能力面的一部分）
            sb.append("说明书：**不给** —— 你现在看不到这把工具（见上面那一格：它的 op 一个都没放行给你）。"
                    + "授权之后再来看；要看你手上有什么：tools op=list。");
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
     * 说明书里"★以你现在的身份能不能用"那一格 —— <b>现算</b>：
     * 把这一把声明过的 op 挨个判一遍（{@code auth.allowed(c, op)}，判据只有"身份 × op"这一条），
     * 只要有一个 op 放行就是"能用"，并把每个 op 的可用 / 不可用逐个列出来。
     */
    private static String authLine(Auth auth, Caller c, Tool t) {
        if (auth == null) {
            return "★ 能不能用：算不出 —— 权限面没装配（auth == null）。基板不编一个结论给你。";
        }
        if (c == null) {
            return "★ 能不能用：不能用 —— 这一轮没有调用者身份（没有主体 = 没有可判定的身份）。";
        }
        List<String> ops;
        try {
            ops = auth.ops().of(t.name());
        } catch (Throwable e) {
            return "★ 能不能用：算不出 —— 取 op 清单抛异常（" + e + "）；基板不编结论。";
        }
        List<String> yes = new ArrayList<String>();
        List<String> no = new ArrayList<String>();
        for (int i = 0; i < ops.size(); i++) {
            String op = ops.get(i);
            boolean ok;
            try {
                ok = auth.allowed(c, op);
            } catch (Throwable e) {
                return "★ 能不能用：算不出 —— 判定 " + op + " 抛异常（" + e + "）；基板不编结论。";
            }
            if (ok) yes.add(op);
            else no.add(op);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("★ 能不能用：").append(yes.isEmpty() ? "**不能用**" : "**能用**");
        sb.append("（你 = ").append(callerText(c)).append("；判据 = 身份 × op，这一把共 ")
          .append(ops.size()).append(" 个 op）\n");
        if (!yes.isEmpty()) sb.append("   可用的 op：").append(Str.join(yes, "、")).append("\n");
        if (!no.isEmpty()) sb.append("   不可用的 op：").append(Str.join(no, "、")).append("\n");
        sb.append("   （可用 = 技能管控表里有 Run 命中你；不可用 = Ban 命中，或者没授权 —— 空 = 未授权 = 不可用）");
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
            "put", "update", "delete", "add", "set", "reset", "remove", "clear", "run", "ban", "grant", "revoke",
            "spawn", "stop", "gen",
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
     * @param auth 权限面（{@code null} = 没装配：一律拒）
     * @param c    调用者
     */
    public static String configTool(JsonObject args, Out out, Auth auth, Conf conf, Caller c) {
        if (conf == null) return "配置没装配（读不到数据根下的 config.json）";
        String op = J.s(args, "op", "list").trim().toLowerCase();
        if ("list".equals(op)) {
            // 动作级 op：列已知键
            String deny = needOpAs(auth, c, "config.list");
            if (deny != null) return deny;
            return configList(conf, c);
        }
        if ("get".equals(op)) {
            // 动作级 op：看一个键
            String deny = needOpAs(auth, c, "config.get");
            if (deny != null) return deny;
            return configGet(conf, J.s(args, "key", ""));
        }
        if ("set".equals(op)) {
            // 动作级 op：改一个键（下面还有一层"只有主人"的业务硬判）
            String deny = needOpAs(auth, c, "config.set");
            if (deny != null) return deny;
            return configSet(args, out, conf, c);
        }
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
     * <p>两道：① {@code config.set} 的 <b>op 判定</b>（在 {@link #configTool} 的分支开头判，
     * 主体 = 调用者）；② 显式 MASTER 复核 —— 改配置 = 改<b>基板边界</b>，连她自己的自主行为
     * （SYSTEM，op 判定恒放行）也不给（D43 第 11 条：边界定义权只有主人有）。</p>
     */
    private static String configSet(JsonObject args, Out out, Conf conf, Caller c) {
        String name = J.s(args, "key", "").trim();
        if (name.isEmpty()) {
            return "set 要键名与值：config op=set key=agentMaxRounds value=30（键名清单：config op=list）";
        }
        if (args == null || !args.has("value")) {
            return "set 要给 value（要清空就写 value=\"\"）：config op=set key=" + name + " value=<值>";
        }
        boolean master = c != null && c.master();
        // 只有主人（把"她自己"也挡在外面：边界定义权在她之外）
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

    // ==================== 罢工硬干活闸（情绪 v2 · SPEC §4） ====================

    /**
     * 罢工回执的<b>首行</b>（SPEC §4.1 的契约串，<b>逐字</b>；探针按字面断言）。
     *
     * <p>只有这一行 —— 拒的时候<b>不调用任何下游</b>（不发消息、不写库、不联网），只回执。</p>
     */
    public static final String STRIKE_LINE = "[罢工中] 我正忙着，这事等会儿再说。";

    /** 罢工闸的白名单：{@code perm} 一族（主人改权限是系统级动作，罢工不挡）。 */
    private static final String STRIKE_WHITELIST_TOOL = "perm";

    /** 情绪事实行的键前缀（与情绪插件的 {@code EmotionMood.KV} 逐字同源）。 */
    private static final String STRIKE_MOOD_KV = "mood:";

    /** 罢工档位码（与情绪插件的 {@code EmotionMood.S_STRIKE} 逐字同源）。 */
    private static final String STRIKE_STATE = "strike";

    /** 装配期注入的读库句柄（判据真源 = {@code kv} 的 {@code mood:<场合>} 行）；没注入 = fail-open。 */
    private static volatile Store STRIKE_DB;

    /** 装配期注入的 warn 句柄（fail-open 时只记一行 warn）。 */
    private static volatile Out STRIKE_OUT;

    /**
     * <b>罢工硬干活闸</b>（SPEC §4）：op 执行前读一眼<b>本场合</b>情绪 —— {@code kv} 的
     * {@code mood:<场合>} 那一行（群 = {@code mood:group:<群号>}、私聊 = {@code mood:private:<QQ>}）。
     *
     * <p><b>只读、异常一律吞掉、绝不抛</b>。{@code state == strike} ⇒ 除白名单（{@code perm} 一族）外
     * 一律拒，回执 = {@link #STRIKE_LINE}（只有这一行；不调用任何下游）。除此之外<b>行为逐字节不变</b>：
     * 非 strike 态返回 {@code null}，调用点照旧走 {@code auth.allow(...)}。</p>
     *
     * <p><b>fail-open</b>（SPEC §4.4）：取不到场合 / 读库异常 ⇒ <b>不拦</b>，只记一行 warn ——
     * 罢工是可用性功能，内部错误不许把全部 op 打死；但"能读到 strike"时必须拦。
     * 判据真源只有 {@code kv} 的 {@code mood:<场合>} 行这一处：不读 {@code emotion} 表、不造第二套状态。</p>
     *
     * <p>坦白三处口径（复核用）：① 白名单按<b>工具名那一段</b>比（{@code perm} / {@code perm.*} 都放行，
     * {@code permx.*} 不放行）；② 调用者取不到（{@code c == null}，Ctx 没绑）时判不出场合 ⇒ fail-open；
     * ③ <b>不豁免 SYSTEM</b> —— 她自己的钩子 / 定时任务发的 op 在罢工态下同样被拒（SPEC 的白名单只有
     * {@code perm}），要豁免是另一条裁定。</p>
     *
     * <p>调用点共三处（第三轮又加了一处：NapCat 动作出口 {@code Boot} 的 {@code Api.Guard} ——
     * {@code h.napcat()} 直连原来绕过了这道闸）：{@link #needOpAs}（内置 op）、
     * {@code sair.v4.skill.Host#need}（技能 op）、{@code sair.v4.tool.Registry#mayEnterDeny}（工具入口）。
     * 出口那一路用 {@link #strikeDenyScene}（场合从动作参数现算）。</p>
     *
     * @param c  判定主体（工具路径 = 当轮触发者，见 {@code needOp} 的口径）
     * @param op op 名（{@code 工具名} 或 {@code 工具名.动作名}）
     * @return {@code null} = 放行；非 {@code null} = 罢工态下的回执（直接作为工具结果返回）
     */
    public static String strikeDeny(Caller c, String op) {
        try {
            // 白名单：perm 一族（工具名那一段就是 perm）。判在最先 —— 主人改权限不能被罢工挡住。
            if (STRIKE_WHITELIST_TOOL.equals(Ops.toolOf(op))) return null;
            Store db = STRIKE_DB;
            if (db == null) {
                strikeWarn("读不到库句柄（闸门未装配）", "", op);
                return null;
            }
            if (c == null) {
                strikeWarn("取不到场合（当轮没有调用者）", "", op);
                return null;
            }
            return strikeOf(db, strikeScopeOf(c.groupId(), c.qq()), op);
        } catch (Throwable t) {
            // 绝不抛：任何异常都落回"不拦 + 一行 warn"
            strikeWarn("读情绪异常（" + t.getClass().getSimpleName() + "）", "", op);
            return null;
        }
    }

    /**
     * <b>说话动作豁免清单</b>（第四轮 P0）：<b>罢工 = 不干活，但要继续骂人</b> —— 把"话"送出去的那几个
     * 动作在罢工期间<b>照常放行</b>。
     *
     * <p>为什么必须豁免：{@code send_group_msg} 也走 NapCat 动作出口，若不豁免，她一生气就连自己的
     * 回复都发不出去 ⇒ 整群静默最长 10 分钟，「极怒 → 罢工 → <b>专心骂人</b>」当场作废（真机 P0：
     * {@code [warn] [qq] 发送失败（[罢工中] …）：本条改打控制台}）。</p>
     *
     * <p><b>单一真源</b>：判据只有 {@link #strikeSpeakAction(String)} 一处、它只认这一行串；
     * 探针按字面断言这一串。只含"把文字/消息送出去"的动作 —— 群管、审批、群公告
     * （{@code _send_group_notice}，GM 明确留在拦的一侧）、上传、点赞、戳一戳一概不在内，照旧拦。</p>
     */
    public static final String STRIKE_SPEAK_ACTIONS =
            "send_msg,send_group_msg,send_private_msg,send_group_forward_msg,send_private_forward_msg";

    /** 这个动作是不是"把话送出去"（{@link #STRIKE_SPEAK_ACTIONS} 是唯一判据；别在别处再写一份清单）。 */
    public static boolean strikeSpeakAction(String action) {
        String a = action == null ? "" : action.trim();
        return !a.isEmpty() && ("," + STRIKE_SPEAK_ACTIONS + ",").contains("," + a + ",");
    }

    /**
     * <b>NapCat 动作出口的完整罢工判定</b>（{@code Boot} 的 {@code Api.Guard} 唯一调用点）：
     * ① 说话动作 ⇒ 放行（{@link #STRIKE_SPEAK_ACTIONS}，第四轮 P0 —— 罢工也要能骂人）；
     * ② 其余 ⇒ {@link #strikeDenyScene}（按场合判；判据真源仍是 {@code kv} 的 {@code mood:<场合>}）。
     *
     * <p>这一层<b>不</b>碰 ACL —— 调用点已经"ACL 先判"过了（ACL 拒文优先的裁定不破）。</p>
     */
    public static String strikeDenyAction(String action, String sceneId, String what) {
        if (strikeSpeakAction(action)) return null;
        return strikeDenyScene(sceneId, what);
    }

    /**
     * <b>按"显式场合串"判罢工闸</b>（NapCat 动作出口用：场合由 {@code Boot} 的 {@code Api.Guard}
     * 从动作 {@code params} / 会话键现算，见 {@code napcatScene}）。
     *
     * <p>与 {@link #strikeDeny} 同一处判据、同一句措辞（{@link #STRIKE_LINE}）、同一份真源
     * （{@code kv} 的 {@code mood:<场合>}），只是场合不来自 {@link Caller}。为什么动作出口也要判：
     * {@code h.napcat()} 是<b>直连</b>（不经工具面）—— 群审核 / 申请审批那两个钩子就是直连发禁言、
     * 警告、审批，原来整段绕过罢工闸。</p>
     *
     * <p>白名单不在这条路上：动作不是 op，{@code perm} 一族本来也不走 NapCat。取不到场合（空串）⇒
     * 放行 + 一行 warn（fail-open，不许因为取不到场合把动作全打死）。</p>
     *
     * @param sceneId 场合串（{@code group:<群号>} / {@code private:<QQ>}）；空 = 判不出 ⇒ 放行
     * @param what    诊断用标签（一般是 {@code napcat.<动作名>}，只进 warn 行，不改回执）
     * @return {@code null} = 放行；非 {@code null} = 罢工回执
     */
    public static String strikeDenyScene(String sceneId, String what) {
        try {
            if (Str.blank(sceneId)) {
                strikeWarn("取不到场合（动作参数与会话都没有 group/user）", "", what);
                return null;
            }
            Store db = STRIKE_DB;
            if (db == null) {
                strikeWarn("读不到库句柄（闸门未装配）", "", what);
                return null;
            }
            return strikeOf(db, sceneId, what);
        } catch (Throwable t) {
            strikeWarn("读情绪异常（" + t.getClass().getSimpleName() + "）", "", what);
            return null;
        }
    }

    /** 判据主体（{@link #strikeDeny} 与 {@link #strikeDenyScene} 共用）：读 {@code mood:<场合>}，strike ⇒ 回执。 */
    private static String strikeOf(Store db, String scene, String what) {
        String row = db.kv(STRIKE_MOOD_KV + scene, null);
        if (Str.blank(row)) {                              // 没有这一行 = 本场合没在罢工
            STRIKE_LAST_WARN = "";
            return null;
        }
        JsonObject mood = J.obj(row);
        if (mood == null) {
            strikeWarn("mood 行读不出 JSON（" + STRIKE_MOOD_KV + scene + "）", scene, what);
            return null;
        }
        STRIKE_LAST_WARN = "";
        if (!STRIKE_STATE.equals(J.s(mood, "state", ""))) return null;   // 非 strike：行为逐字节不变
        return STRIKE_LINE;
    }

    /**
     * 场合键：群聊 {@code group:<群号>}、私聊 {@code private:<QQ>}。
     *
     * <p>与情绪插件的 {@code EmotionMood.scopeOf(groupId, qq)} <b>同一个表达式</b>：技能类在运行期才编译，
     * 基板引用不到它，只能同源照抄 —— 改动那一边时这一行必须跟着改（口径写在 SPEC §1/§4）。</p>
     */
    private static String strikeScopeOf(long groupId, long qq) {
        return groupId > 0L ? ("group:" + groupId) : ("private:" + qq);
    }

    /**
     * 上一次 fail-open warn 的「原因｜场合」键。
     * <p>为什么需要它：入口闸（{@code Registry.mayEnterDeny}）与内置体闸（{@link #needOpAs}）会为
     * <b>同一次 op 调用</b>各求值一次 —— 不折一下，一次 fail-open 会刷两行；SPEC §4.4 要的是<b>一行</b>。
     * 同一场合、同一原因的连续 fail-open 只记第一行（读成功一次或换了原因/场合就重置）。</p>
     */
    private static volatile String STRIKE_LAST_WARN = "";

    /** fail-open 的那一行 warn（warn 自己也不许抛 —— fail-open 是这一层的全部意义）。 */
    private static void strikeWarn(String why, String scene, String op) {
        try {
            String key = why + "|" + (scene == null ? "" : scene);
            if (key.equals(STRIKE_LAST_WARN)) return;
            STRIKE_LAST_WARN = key;
            Out o = STRIKE_OUT;
            if (o != null) o.warn("[罢工闸] " + why + " —— 按不拦处理：" + op);
        } catch (Throwable ignored) {
            // 连 warn 都失败：仍然不拦
        }
    }

    // ==================== op 判定（身份 × op → Ban / Run / 空） ====================

    /**
     * 内置工具侧的判定主体：工具路径 = 当轮绑定的触发者（{@link sair.v4.ctx.Ctx#caller()}，
     * P1 已让工具路径绑触发者）。
     *
     * <p>她自己的自主行为（钩子 / 定时 / 扩展点）不经过这里，那些路径 P1 已显式绑
     * {@link Caller#systemActor} —— 所以"钩子里那一句判定"照旧写，判定主体是 SYSTEM，
     * 恒放行（口径统一：读代码的人不需要分辨这是谁触发的）。</p>
     */
    private static Caller aclCaller(Conf conf) {
        return sair.v4.ctx.Ctx.caller();
    }

    /**
     * <b>op 判定</b>（放行返回 {@code null}，否则返回可直接回给模型的拒绝原文）：
     * 主体 = 当轮触发者，判据 = {@link Auth#allow(Caller, String)}（身份 × op）。
     *
     * <p>{@code op} = {@code 工具名} 或 {@code 工具名.动作名}（清单见 {@link Ops}，内置工具那份由
     * {@link #declareOps(OpList)} 登记）。<b>每个动作分支各判自己那个 op</b> —— 技能管控表就是按这些
     * 名字放行的，"允许他取一条"与"允许他删一行"能在表里分开写。</p>
     *
     * <p>{@code MASTER} 与她本人（{@code SYSTEM}）恒全权（{@code Acl} 内部短路）；{@code ALLUSER}
     * 按表判：{@code Ban} = 不可用 / {@code Run} = 可用 / <b>空</b> = 未授权 = 不可用。权限面没装配
     * （{@code auth == null}）时按拒绝处理（fail-closed）—— 不假装有权限面。</p>
     *
     * <p><b>罢工硬干活闸在权限判定之后</b>（GM 裁定，SPEC §4）：① 权限面先判 —— 账本没放行就把它的
     * 拒文原样返回（"权限阻断"那句优先，不许被情绪盖掉）；② 放行了才看情绪，{@code strike} 态下除
     * {@code perm} 一族一律拒，回执见 {@link #STRIKE_LINE}。非 strike 态这一层放行，返回值与改前
     * <b>逐字节相同</b>。</p>
     */
    private static String needOp(Auth auth, Conf conf, String op) {
        return needOpAs(auth, aclCaller(conf), op);
    }

    /**
     * 同 {@link #needOp}，但<b>主体直接给</b>（调用点手上就有 {@link Caller} 时用：
     * perm / tools / config 这几个静态入口，判定主体就是它们收到的那一个）。
     */
    private static String needOpAs(Auth auth, Caller c, String op) {
        if (auth == null) return Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + op;
        // ① 权限面先判：ACL 的拒文优先（权限模型不许被情绪改）。
        String deny = auth.allow(c, op);
        if (deny != null) return deny;
        // ② 放行了才是罢工硬干活闸：唯一插入点 = 所有内置 op 判定都走的这一口，在任何副作用之前。
        return strikeDeny(c, op);
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

    /**
     * <b>护栏</b>：这个文件名是不是权限文件（{@link Acl#SKILL_FILE_NAME} = {@code perms.jsonc}）。
     *
     * <p>技能不许自己写它 —— 权限文件按归属分散之后，技能目录里的 {@code perms.jsonc} 就是"这个技能
     * 自己提供的工具给谁用"的授权书；让技能的写入/覆盖/新增/草稿提升碰到它，等于技能能给自己开口子。
     * 它只能由主人用控制台 {@code ai/perm} 或直接手改文件来写。</p>
     */
    private static boolean isPermFile(String fileName) {
        return fileName != null && Acl.SKILL_FILE_NAME.equalsIgnoreCase(fileName.trim());
    }

    /** 拒绝写权限文件时那一句可读原因（写在返回值里，调用方直接回给模型）。 */
    private static String permFileDeny(String where) {
        return "拒绝写入 " + Acl.SKILL_FILE_NAME + "：" + where
                + " —— 权限文件（谁能用这个技能）只能由主人用控制台 ai/perm 写，技能不能给自己开口子。";
    }

    /** 把 md / java 写进某个技能目录（add / update / draft 共用）；返回 null = 成功。 */
    private static String writeSkillFiles(File dir, String name, String md, String java) {
        Fs.mkdirs(dir);
        if (Str.has(md)) {
            File mdf = new File(dir, name + ".md");
            if (isPermFile(mdf.getName())) return permFileDeny(mdf.getName());
            if (!Fs.write(mdf, md)) return "写入 md 失败";
        }
        if (Str.has(java)) {
            String cls = DynCode.classNameOf(java);
            File jf = new File(dir, (Str.has(cls) ? cls : "AirunSkill") + ".java");
            if (isPermFile(jf.getName())) return permFileDeny(jf.getName());
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
     * {@code optimize}/{@code vacuum} 是维护动作。op 判定只管"这个人能不能用这个动作"，
     * 挡不住"主人哪天把 {@code store_admin.export} 放给了某个人" —— 所以这一层不靠 op 兜底，
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
     * <p><b>为什么必须硬判</b>：op 判定只回答"这个人能不能用这个动作"，不回答"他能不能动这一行"
     * —— 而 {@code store_write} 是"任意库任意行"的原始写口，不判归属就等于绕过所有技能自己的键设计。</p>
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

    // ==================== v6 DB 域：执行点接入（通用库口） ====================

    /**
     * 整库动作覆盖到的 DB 类别（{@code store_admin} 的 {@code optimize}/{@code vacuum}/
     * {@code export}/{@code import} 用）：整库动作<b>没有单一行归属</b>，所以把它覆盖到的每一类
     * 都过一遍写判定 —— 任一类的 {@code Run} 不成立就拒。{@code rowOwner=null}（无归属的共享行）
     * 按规格 §2.5-5 只有被指名的身份动得了。
     */
    private static final String[] ADMIN_LIB_KEYS = {
        "memory", "note", "dialog", "grouplog", "sticker", "pref", "emotion", "group_impression"
    };

    /** {@code maintain}（整库维护清理）实际会删到的三类超龄行。 */
    private static final String[] MAINTAIN_KEYS = {"memory", "dialog", "grouplog"};

    /**
     * <b>通用库口的 lib → DB 域类别键</b>映射（规格 §3 白名单）。
     *
     * <p>通用口拿到的 {@code lib} 是 {@code Libs} 注册表里的<b>表名</b>，{@code DB} 域的键就是那份
     * 数据类别表；这八类两者<b>恰好同名</b>（{@code dialog}/{@code grouplog} 是基板环境表，
     * 其余六类由插件声明）。不同名的那一族是 {@code kv.*}（13 个逻辑子类共用一张 {@code kv} 表），
     * 它没有注册成 {@code LibSpec} ⇒ 通用口在 {@code libs.get(lib) == null} 那一句就把它拦成
     * "未知的库"了。</p>
     *
     * <p><b>显式白名单，不是字符串推导</b>：认不出的 lib 原样交给 {@link Acl#dbDeny} /
     * {@link Acl#dbReadDeny}，由它们判"不认识的权限键" ⇒ 拒（方向只许更严，绝不放宽）。</p>
     *
     * <p>{@code public}：控制台那条 {@code ai/store import}（{@code term.Cmd}）要判同一个"整库覆盖"，
     * 库名 → 数据键必须与工具面<b>同一份</b>映射，不许另起一套。</p>
     */
    public static String dbKeyOf(String lib) {
        String l = lib == null ? "" : lib.trim();
        if ("dialog".equals(l)) return "dialog";
        if ("grouplog".equals(l)) return "grouplog";
        if ("memory".equals(l)) return "memory";
        if ("note".equals(l)) return "note";
        if ("sticker".equals(l)) return "sticker";
        if ("pref".equals(l)) return "pref";
        if ("emotion".equals(l)) return "emotion";
        if ("group_impression".equals(l)) return "group_impression";
        return l;
    }

    /**
     * <b>这一行归谁</b>（{@link Acl#dbDeny} 的第三参）：规范形态 {@code user:<QQ>} /
     * {@code group:<群号>}；{@code null} = 无归属的共享行（规格 §2.5-5：只有被指名的身份动得了）。
     *
     * <p><b>必须按类别各自映射，不许拿字符串直接比</b>（规格 §7-4）：{@link #inScope} 对
     * {@code dialog} 比的是 {@code me.session()}（{@code qq:<QQ>} / {@code group:<群号>} /
     * {@code console}），而 {@code rowOwner} 的规范形态是 {@code user:<QQ>} ⇒
     * <b>{@code qq: ≠ user:}</b>，直接比会把 dialog 的行归属全判错。映射表（列名与取值都来自
     * 各自的 {@code LibSpec} 与技能实现）：</p>
     * <ul>
     *   <li>{@code dialog}：行 {@code session}（{@code qq:<QQ>} / {@code user:<QQ>}）→ {@code user:<QQ>}；
     *       {@code group:<群号>} → {@code group:<群号>}；{@code console} 一类 → {@code null}；</li>
     *   <li>{@code grouplog} / {@code group_impression}：行 {@code group_id} → {@code group:<群号>}；</li>
     *   <li>{@code memory} / {@code pref}：行 {@code scope}（{@code user}/{@code group}）+ {@code scope_id}
     *       → {@code user:<scope_id>} / {@code group:<scope_id>}；{@code global} → {@code null}；</li>
     *   <li>{@code emotion}：行 {@code group_id} / {@code qq} → {@code group:<群号>} / {@code user:<QQ>}；</li>
     *   <li>{@code sticker}：行 {@code extra.added_by} → {@code user:<QQ>}（没有这个字段的老行 = 无归属）；</li>
     *   <li>{@code note} 与认不出的库：{@code null}（全体共享行）。</li>
     * </ul>
     */
    private static String rowOwnerOf(String lib, JsonObject row) {
        if (row == null) return null;
        String l = lib == null ? "" : lib.trim();
        if ("dialog".equals(l)) return ownerOfSession(J.s(row, "session", ""));
        if ("grouplog".equals(l) || "group_impression".equals(l)) return ownerOfGroupId(J.l(row, "group_id", 0L));
        if ("memory".equals(l) || "pref".equals(l)) {
            return ownerOfScope(Str.trim(J.s(row, "scope", "")), Str.trim(J.s(row, "scope_id", "")));
        }
        if ("emotion".equals(l)) {
            long g = J.l(row, "group_id", 0L);
            return g > 0L ? ownerOfGroupId(g) : ownerOfUser(J.l(row, "qq", 0L));
        }
        if ("sticker".equals(l)) return ownerOfUser(J.l(J.sub(row, "extra"), "added_by", 0L));
        return null;
    }

    /** 会话键形态 → rowOwner：{@code qq:<QQ>}（以及 Api 台账那种 {@code user:<QQ>}）→ {@code user:<QQ>}。 */
    private static String ownerOfSession(String session) {
        String s = Str.trim(session);
        if (s.startsWith("qq:")) return ownerOfUser(numOf(s.substring(3)));
        if (s.startsWith("user:")) return ownerOfUser(numOf(s.substring(5)));
        if (s.startsWith("group:")) return ownerOfGroupId(numOf(s.substring(6)));
        return null;                                   // console 一类：没有归属主体
    }

    /** {@code scope} + {@code scope_id} → rowOwner（{@code global} / 认不出的作用域 = 无归属）。 */
    private static String ownerOfScope(String scope, String id) {
        if ("user".equalsIgnoreCase(scope)) return ownerOfUser(numOf(id));
        if ("group".equalsIgnoreCase(scope)) return ownerOfGroupId(numOf(id));
        return null;
    }

    private static String ownerOfUser(long qq) { return qq > 0L ? ("user:" + qq) : null; }

    private static String ownerOfGroupId(long gid) { return gid > 0L ? ("group:" + gid) : null; }

    /** 号（{@code scope_id} / {@code session} 里那段是文本列）；认不出返回 0（= 无归属）。 */
    private static long numOf(String s) {
        String t = Str.trim(s);
        if (t.isEmpty()) return 0L;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * DB 域<b>写</b>判定的一层壳：{@code auth} 没装配 / 判定异常一律按拒（fail-closed，与
     * {@link #needOpAs} 同口径 —— 绝不让异常变成放行）。放行返回 {@code null}；否则是
     * {@link Acl#dbDeny} 的拒绝原文（自带 {@code [权限阻断] } 前缀，调用方原样回给模型，不再拼前缀）。
     */
    private static String dbDenyOf(Auth auth, String dataKey, String rowOwner, Caller me) {
        if (auth == null) return Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + dataKey;
        try {
            Acl acl = auth.acl();
            if (acl == null) return Acl.DENY_PREFIX + "权限面没有装配（acl == null），按拒绝处理：" + dataKey;
            return acl.dbDeny(me, dataKey, rowOwner);
        } catch (Throwable t) {
            return Acl.DENY_PREFIX + "数据判定异常，按拒绝处理：" + dataKey + "（" + t + "）";
        }
    }

    /** DB 域<b>读</b>判定的一层壳（同 {@link #dbDenyOf} 的 fail-closed 口径）。 */
    private static String dbReadDenyOf(Auth auth, String dataKey, Caller me) {
        if (auth == null) return Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + dataKey;
        try {
            Acl acl = auth.acl();
            if (acl == null) return Acl.DENY_PREFIX + "权限面没有装配（acl == null），按拒绝处理：" + dataKey;
            return acl.dbReadDeny(me, dataKey);
        } catch (Throwable t) {
            return Acl.DENY_PREFIX + "数据判定异常，按拒绝处理：" + dataKey + "（" + t + "）";
        }
    }

    /**
     * <b>File 域路径判定</b>的一层壳：{@code write=true} 看 {@code Run}（写 / 改 / 删 / 建），
     * {@code false} 看 {@code Read}（读 / 找 / 下载）。同 fail-closed 口径。
     */
    private static String fileDenyOf(Auth auth, String path, boolean write, Caller me) {
        if (auth == null) return Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + path;
        try {
            Acl acl = auth.acl();
            if (acl == null) return Acl.DENY_PREFIX + "权限面没有装配（acl == null），按拒绝处理：" + path;
            return acl.fileDeny(me, path, write);
        } catch (Throwable t) {
            return Acl.DENY_PREFIX + "路径判定异常，按拒绝处理：" + path + "（" + t + "）";
        }
    }

    /** 整库动作：把它覆盖到的每一类都过一遍写判定（任一拒 ⇒ 拒）。 */
    private static String dbWholeDeny(Auth auth, String[] keys, Caller me) {
        for (int i = 0; i < keys.length; i++) {
            String d = dbDenyOf(auth, keys[i], null, me);
            if (d != null) return d;
        }
        return null;
    }

    // ==================== v6 读口行归属（补丙核出的 memory 缺口） ====================

    /**
     * {@code memory} 读口的行归属（非主人）：<b>本人 / 本群 / global</b> 三类可见。
     *
     * <p>与记忆技能内部的 {@code MemorySkill.visible} <b>同口径</b>：{@code global} 全可见、
     * {@code user} 比 {@code scope_id == 自己 QQ}、{@code group} 比 {@code scope_id == 自己所在群}；
     * 认不出的作用域一律不可见（不猜）。</p>
     */
    private static boolean memoryVisible(JsonObject row, Caller me) {
        if (row == null || me == null) return false;
        String scope = Str.trim(J.s(row, "scope", ""));
        String id = Str.trim(J.s(row, "scope_id", ""));
        if ("global".equalsIgnoreCase(scope)) return true;
        if ("user".equalsIgnoreCase(scope)) return String.valueOf(me.qq()).equals(id);
        if ("group".equalsIgnoreCase(scope)) return me.isGroup() && String.valueOf(me.groupId()).equals(id);
        return false;
    }

    /**
     * <b>主人（MASTER）与她本人（SYSTEM）恒全权</b>（规格 §0：不进判定、不受任何块影响）。
     *
     * <p>v6 新接的读口行归属用这个判据，而<b>不是</b> {@code me.master()}：老守卫里
     * {@link #inScope} 只豁免 {@code MASTER}，那不是 v6 的口径（记忆技能内部的 {@code master(h)}
     * 同样是"主人"一个含义，但规格 §0 明写她本人在内）。新代码按规格走，老守卫原样不动。</p>
     */
    private static boolean isFree(Caller c) {
        return c != null && (c.master() || c.system());
    }

    /** 读口行归属总入口：memory 走 {@link #memoryVisible}，其它库沿用既有 {@link #inScope} 口径。 */
    private static boolean readVisible(String lib, JsonObject row, Caller me) {
        if (!"memory".equals(lib)) return true;
        return memoryVisible(row, me);
    }

    /** 读口逐行过滤（与 {@link #keepInScope} 并列的那一层：memory 的作用域可见面）。 */
    private static List<JsonObject> keepReadScope(String lib, List<JsonObject> rows, Caller me) {
        List<JsonObject> out = new ArrayList<JsonObject>();
        if (rows == null) return out;
        for (JsonObject r : rows) if (readVisible(lib, r, me)) out.add(r);
        return out;
    }

    /**
     * {@code memory} 的非主人条数：不能"整库一把数"（那会数出别人的行数），按可见的三条作用域
     * 分别数（本人的 {@code user} / 本群的 {@code group} / 全体的 {@code global}）。
     * 调用方 filter 里的 {@code scope}/{@code scope_id} 一律被这三片覆盖 —— 结果只会比"他能看见的
     * 面"更窄，不会更宽。
     */
    private static long memoryReadCount(Store store, String lib, JsonObject filter, Caller me) {
        long n = 0L;
        n += store.count(lib, scopeFilter(filter, "user", String.valueOf(me.qq())));
        if (me.isGroup()) n += store.count(lib, scopeFilter(filter, "group", String.valueOf(me.groupId())));
        n += store.count(lib, scopeFilter(filter, "global", ""));
        return n;
    }

    /** 复制一份过滤器并把作用域钉死到给定那一层（不改调用方手里的对象）。 */
    private static JsonObject scopeFilter(JsonObject f, String scope, String scopeId) {
        JsonObject o = f == null ? new JsonObject() : f.deepCopy();
        o.addProperty("scope", scope);
        o.addProperty("scope_id", scopeId);
        return o;
    }

    // ==================== NapCat 动作权限 ====================

    /**
     * {@code napcat} 工具两处判定用的<b>主体</b>：与装在 {@code guardedApi} 上的那道 Guard
     * （{@code Boot} 里 {@code Api.Guard.deny}）<b>取法完全同源</b> —— 先问当轮绑定的主体
     * {@link sair.v4.ctx.Ctx#caller()}；取不到 = 她自己的自主行为（基板回复、定时推送、扩展点回调），
     * 按 {@link Caller#systemActor(long)} 处理。
     *
     * <p>口径：{@code napcat.<动作名>} 是每个动作自己的 op（清单由 {@link #declareOps(OpList)} 按动作目录登记）。
     * 装在 {@code guardedApi} 上的那道 Guard（{@code Boot} 里 {@code Api.Guard.deny}）是同一条判定的
     * 第二处出口 —— 两处取主体必须同源，否则"看得到"与"调得动"会对不上。</p>
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
    // 这里原来是 napcatLevel(action)：把每个 NapCat 动作映射到一个"出厂档位"（MASTER / AFFECTION:<N>）。
    // 权限面整个换成 op 判定之后，每个动作的判据就是 {@code napcat.<动作名>} 这个 op
    // （装在 guardedApi 上的 Guard 与本文件的工具实现各自判它），与旧档位表无关；per-action 的
    // 出厂值改由技能管控表说了算（空 = 未授权），所以这里不再保留任何出厂映射。

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
     * <p>{@code public} 是给探针断言用的（核 op 取值表与这几个参数就是她看到的那一份契约）。</p>
     */
    public static JsonObject schemaPerm() {
        return J.obj(
                "type", "object",
                "properties", J.obj(
                        "op", oneOf("好感度面：whoami 我是谁 · get 查一个人 · list 榜单 · set 直接定值 · reset 清空"
                                        + "；权限面：show 看合并后的表 · gen 只在已有权限文件里重排 · run 授权 · ban 封禁 · revoke 撤回"
                                        + "（show/gen/run/ban/revoke 只有主人能调）",
                                "whoami", "whoami", "get", "list", "set", "reset", "show", "gen", "run", "ban", "revoke"),
                        "principal", obj("string",
                                "身份 / QQ 号。好感度面（get/set）：QQ 号（裸数字或 user:<QQ>），留空 = 你自己。"
                                + "权限面（run/ban/revoke）：user:<QQ号>（个人，跨群有效）/ group:<群号>（只在该群会话生效）/ "
                                + "ALLUSER（全体用户）；裸数字 = user:<QQ>；「群123456」也认；逗号分开 = 一个身份一条。"
                                + "MASTER 与她本人（SYSTEM）恒全权，写不进表里。"),
                        "target", obj("string",
                                "要授权 / 封禁 / 撤回的 **op**：工具名（覆盖它的全部动作，如 memory）"
                                + "或 工具名.动作名（只管那一个，如 memory.remember）。"
                                + "写回该 op 归属的那个权限文件（技能自己的工具写技能目录，其余写数据根 core；回执里会说明）。"
                                + "op=revoke 留空 = 撤该身份名下的全部条目。"),
                        "value", obj("number", "op=set：好感度的新数值（0 是合法值；不受单次加减区间限制）"),
                        "note", obj("string", "op=set：为什么改（写进 favor_event 流水）"),
                        "limit", obj("integer", "op=list：榜单回多少名，默认 20")));
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
