package sair.v4.qq;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import sair.v4.Conf;
import sair.v4.kit.J;
import sair.v4.kit.Out;
import sair.v4.kit.Str;

/**
 * OneBot v11 动作目录 + 类型化封装（设计基线能力⑧的"动作面"）。
 *
 * <p>统一返回口径：所有方法都返回同一个 <b>JsonObject 形状</b>：</p>
 * <ul>
 *   <li>成功：NapCat 原始响应对象（{@code status}/{@code retcode}/{@code data}/{@code echo}）；</li>
 *   <li>未连接：{@code {"error":"Napcat 没有连接"}}（NapCat 未连接时基板的统一话术）；</li>
 *   <li>其它失败：{@code {"error":"…"}} —— 无响应/超时、响应不是 JSON、以及
 *       {@code status=failed} 或 {@code retcode≠0}（此时原字段仍在，只是补一个 error 便于统一判错）。</li>
 * </ul>
 * <p>判错统一用 {@link #ok(JsonObject)} / {@link #error(JsonObject)}。</p>
 *
 * <p>参数名一律按 OneBot v11（{@code group_id}/{@code user_id}/{@code message}/{@code duration}…）。
 * 图片/语音用消息段数组（{@code [{"type":"image","data":{"file":".."}}]}），文件上传用
 * {@code upload_group_file}/{@code upload_private_file}。本地绝对路径的写法由 {@link Relay} 统一裁定：
 * <b>中转在跑 → 外链 URL（NapCat 在别的机器上也取得到）；没跑 → {@code file:///C:/x}（同机口径）</b>。
 * 调用方不需要知道 NapCat 在哪台机器上。</p>
 *
 * <h3>强制层（唯一漏斗，技能绕不过去）</h3>
 * <p>{@link #call(String, JsonObject)} 是所有动作的<b>唯一出口</b>，它在把参数交给 NapCat 之前
 * 统一改写一遍"要发出去的本地文件"（{@link #rewrite(String, JsonObject)}）：</p>
 * <ul>
 *   <li>{@code upload_group_file} / {@code upload_private_file} 的 {@code file}；</li>
 *   <li>{@code send_group_msg} / {@code send_private_msg} / {@code send_msg} 的 {@code message} ——
 *       消息段数组里的 {@code data.file}、以及 CQ 串里的 {@code file=}。</li>
 * </ul>
 * <p>只要值长得像本地路径（{@code C:\x}、{@code C:/x}、{@code file:///C:/x}），就换成中转 URL；
 * 中转没开（或路径不是普通文件）则保留原值，并 warn 一句"跨机 NapCat 读不到本地路径"。
 * 因此<b>技能只要把本地绝对路径交给 {@code h.napcat()} 就是合规的</b>，不需要自己拼 URL、也不需要知道中转。</p>
 *
 * <p><b>在这一跳上过两道判定（v6）</b>：① {@code napcat.<动作名>} 的 op（{@code Skill} 域 ——
 * "他能不能做这个动作"）；② {@link #pathDeny}（{@code File} 域 —— "这个路径本身在不在允许范围"，
 * 把本机文件交出去 = <b>读文件</b> ⇒ 看 {@code Read}）。两道都在<b>产出 URL 之前</b>：被拒时不产出
 * 任何写法（连 {@code file:///} 回退都不给），拒绝原文由 {@link #call} 作为整条动作的结果返回 ——
 * 绝不"先转成 URL 再拒"（那等于已经泄露）。</p>
 */
public final class Api {

    /** NapCat 未连接时的统一返回（设计基线⑧）。 */
    public static final String NOT_CONNECTED = "Napcat 没有连接";

    /**
     * 带"文件"参数的参数名（任何动作都管）：主形态是 {@code file}，另有 NapCat 扩展用 {@code image}
     * 与复数形态 {@code images}（{@code send_qzone_msg} 的图片数组键）。
     *
     * <p><b>{@code images} 是 N2/D4 加的</b>（K2 实测：{@code send_qzone_msg} 的图片 URL 数组不在
     * 本表里 ⇒ 每个 URL 都会被内容级闸当"正文"判 —— 命中不可替换类目就整条拦下、命中可替换类目
     * 就<b>静默把 URL 改坏</b>）。为什么它属于"文件"而不是"正文"：<b>这个键的值是地址</b>
     * （URL / 本地路径 / base64），不是她要说的那句话；{@link #rewrite} 顺手把单串形态也纳入
     * 文件改写与 {@code File} 域路径判定（数组形态 {@code localPathOf} 认不出 ⇒ 原样透传，
     * 与改动前逐字节相同）。</p>
     */
    private static final String[] FILE_KEYS = {"file", "image", "images"};
    /**
     * <b>协议不透明标识的键名</b>（内容级闸的键级例外，2026-09-23 复核者对抗用例之后新增）。
     * <p>这些键的值是 NapCat 下发的<b>标识 / 令牌</b>，基板只负责<b>原样回传</b> —— 对它们换词/剥标记 =
     * 把请求改坏（真令牌形状 {@code dd-token-9876} 会被换成 {@code dd-这类信息-9876}，申请就发不出去了）。</p>
     *
     * <p><b>N2/D4 扩充（K2 实测到的真风险：静默改坏标识）</b>：内容级闸判的是"任意非 {@code get_*}
     * 动作 × 任意字符串叶子"，本表原来只有 {@code flag} ⇒ 下面这七个键的<b>字符串</b>值也会进闸：
     * 命中不可替换类目就<b>整条拦下</b>（K2 实测 {@code form=leaf:tid}），命中可替换类目就
     * <b>静默换词、把标识改坏</b>（与 {@code flag} 当初那个缺陷同一类，正是给 {@code flag} 开例外的理由）。</p>
     * <p><b>逐键说明"为什么它是标识不是内容"</b>：</p>
     * <ul>
     *   <li>{@code tid}：QQ 空间动态的<b>动态 id</b>（{@code delete_qzone_msg} 靠它定位要删哪一条）——
     *       删的是"那条动态"，说的不是话；</li>
     *   <li>{@code message_id}：消息 id（{@code delete_msg} / 群待办 {@code complete_group_todo} 靠它定位），
     *       是 NapCat 发号的<b>唯一键</b>；</li>
     *   <li>{@code message_seq}：会话内消息序号（拉历史 / 群待办的游标），同样是发号；</li>
     *   <li>{@code album_id}：群相册 id（{@code cancel_group_album_media_like} 的目标相册）——
     *       相册的编号，改一个字符就likes到别的相册/直接失败；</li>
     *   <li>{@code batch_id}：相册媒体批次 id（同上，点赞/取消点赞的批目标）；</li>
     *   <li>{@code lloc}：群相册媒体的内部定位串（{@code lloc} 是 NapCat 的下发值，基板不解释）；</li>
     *   <li>{@code src_id}：来源/转发类动作的来源会话 id（NapCat 的下发标识）。</li>
     * </ul>
     * <p><b>判据没有放松</b>：例外<b>只按这七个键名 + {@code flag}</b> 生效，
     * 且只对<b>该键的名字</b>生效 —— 同样的词放进 {@code message} / {@code reason} / {@code note} /
     * {@code card} / {@code content}（正文键）<b>照旧过闸</b>（拦下或换词）。反向证明见交付报告 ④。</p>
     */
    private static final String[] PROTOCOL_KEYS = {"flag", "tid", "message_id", "message_seq",
            "album_id", "batch_id", "lloc", "src_id"};
    /**
     * <b>载荷键</b>（内容级闸的键级例外，<b>但不进改写层</b>）——N2/D5 用。
     *
     * <p>{@code chunk_data}（{@code upload_file_stream} 的 base64 分块）是<b>文件字节本身</b>，
     * 不是"她要说出去的话"；而它<b>又进不了 {@link #FILE_KEYS}</b>：那个表同时喂
     * {@link #rewrite}，而 {@code rewriteLocalFile} 会把"以 {@code /} 开头"的值当本地路径
     * 换成中转 URL —— base64 完全可能以 {@code /} 开头（首字节 0xFC..0xFF）⇒ 那样会<b>把分块
     * 静默改坏</b>。所以它单列一张表：<b>只跳过内容级闸，一个字节都不改写</b>。</p>
     *
     * <p>★ 为什么必须有这条：base64 字母表含全部小写字母 ⇒ 分块里出现 {@code token} 这种
     * 机密键词是常态；不豁免，闸会把它换成 {@code 这类信息} ⇒ 上传的文件被静默改坏
     * （与 D4 那七个标识键同一类缺陷，实测见交付报告 ④ 的 D5 前置断言）。</p>
     */
    private static final String[] PAYLOAD_KEYS = {"chunk_data"};
    /** 消息类动作（{@code params.message}：消息段数组或 CQ 串）。 */
    private static final String[] SEND_ACTIONS = {"send_group_msg", "send_private_msg", "send_msg"};

    // ---------------- N1：语音转写 / 图片直链刷新（动作名与配置键都在这一处） ----------------

    /** 语音转文字的动作名（NapCat <b>4.18.2+</b> 的「消息扩展」；依据 {@code tmp/v6-real/upstream/D5.md} 4.18.2）。 */
    public static final String A_PTT_TEXT = "fetch_ptt_text";
    /** rkey 的动作名（NapCat 的 PacketBackend 扩展；依据 {@code D4.md} 扩展动作表 + "图片 URL 约 2 小时过期"一段）。 */
    public static final String A_NC_RKEY = "nc_get_rkey";
    /** 图片取回的两个既有动作（刷新链的第 ②/③ 步；依据 {@code D4.md} 的"旧文档"一段）。 */
    public static final String A_GET_IMAGE = "get_image";
    public static final String A_GET_FILE = "get_file";
    /** 语音转写结构事实行的前缀（{@code ptt: {"records":1,"text":1,…}}）。 */
    public static final String PTT_FACT_PREFIX = "ptt: ";
    /**
     * 语音转写开关的配置键（默认<b>开</b>）。
     * <p>理由：① 一条语音只多一次只读调用（{@code fetch_ptt_text}），而语音在群里是低频；
     * ② 关掉它等于让模型继续"听不懂"她说的话 —— 代价比一次往返大得多；
     * ③ 失败一律吞成"没有转写"，退回旧标签，不会因为开关开着而丢消息。
     * 关掉的方式（{@code data\config.json} 里写 {@code "qqPttText": false}）在 REPORT 里写明。</p>
     */
    public static final String CFG_PTT_TEXT = "qqPttText";

    /**
     * <b>首取失败后最多再取几次</b>的配置键（默认 {@value #PTT_RETRY_DEFAULT}）。
     *
     * <p><b>为什么需要重试（真机证据，2026-09-24）</b>：同一条语音
     * （{@code message_id=1887763003} / {@code a7f75e720e4d22c88fd472b20d9dbc2a.amr}）
     * 到达时首取 {@code fetch_ptt_text} 返失败（{@code [qq] ptt: {"records":1,"text":0,"none":1,…}}），
     * <b>两分钟后同一条再取就成</b>（{@code {"status":"ok","retcode":0,"data":{"text":"你好啊，晚上好。"}}}）。
     * 另有"同一 {@code message_id} 首取成 / 首取败"的成对样本 ⇒ 不是端点不可用，而是<b>语音刚落库时
     * NapCat 那边的媒体（silk/amr）还没准备好</b>：等一会儿再问就有。所以修复在<b>重试</b>，
     * 不在技能侧做别的。</p>
     *
     * <p>口径：{@code <=0} ⇒ 不重试（与加重试之前逐字节相同：一次动作、成败都收手）；
     * 超过 {@value #PTT_RETRY_MAX} 的值被<b>夹住</b>（配置写多大都不会把入站路堵住）。
     * 间隔见 {@link #CFG_PTT_RETRY_MS}。这个键<b>不改</b> {@link #CFG_PTT_TEXT} 的语义
     * （那个键仍然只管"要不要转写"）。</p>
     */
    public static final String CFG_PTT_RETRY = "qqPttRetry";
    /**
     * <b>各次重试前等多少毫秒</b>的配置键（逗号分隔；第 i 次重试用第 i 个值，值不够就复用最后一个）。
     *
     * <p>出厂默认 {@value #PTT_RETRY_MS_DEFAULT}——这一组是**按真机实测**挑的（2026-09-25，甲方私聊两条语音）：
     * ① 02:58 那条：语音到达那一刻（02:58:43.909）自动首取 = 失败，**2.99 秒之后**（02:58:46.903）
     * 同一个 message_id 再取 = 成功（{@code data.text = "你好。"}）；② 02:15 那条：首取失败、
     * **两分钟后**再取成功。⇒ 转写窗口的**下界约 3 秒**（②只是"≤114s 够"，不是上界）。
     * 默认按 1s / 2s / 3s 三档递进，于是**首取之后的第 2 次动作 ≈1.0s、第 3 次 ≈3.0s、第 4 次 ≈6.0s**，
     * 而单次失败本身只要 210–330 ms。旧值 {@code "1200,2000"}（第 3 次动作 ≈3.2s）正好卡在实测边界上，
     * 故上修为现默认。</p>
     *
     * <p>三道封顶（写多大的数都不会变成无界阻塞）：单次等待 ≤ {@value #PTT_RETRY_WAIT_MAX_MS} ms；
     * <b>全部重试等待加起来 ≤ {@value #PTT_RETRY_BUDGET_MAX_MS} ms</b>（睡满预算之后剩下的重试
     * <b>不再等</b>，立刻发）；<b>重试次数</b> ≤ {@value #PTT_RETRY_MAX}（⇒ 动作最多 {@value #PTT_RETRY_MAX} + 1 次）。
     * 空串/整串畸形 ⇒ 回落默认序列；单个畸形段按 0（= 那一次不等）。</p>
     */
    public static final String CFG_PTT_RETRY_MS = "qqPttRetryMs";
    /** {@link #CFG_PTT_RETRY} 的出厂默认：首取失败后最多<b>再</b>取 3 次（合计最多 4 次动作）。 */
    public static final int PTT_RETRY_DEFAULT = 3;
    /** {@link #CFG_PTT_RETRY_MS} 的出厂默认：重试前等 1000 / 2000 / 3000 ms（第 2/3/4 次动作 ≈1.0s / 3.0s / 6.0s）。 */
    public static final String PTT_RETRY_MS_DEFAULT = "1000,2000,3000";
    /** 重试次数的<b>硬上限</b>（配置写多大都不会超过它）。 */
    private static final int PTT_RETRY_MAX = 5;
    /** <b>单次</b>重试前等待的硬上限（毫秒）。 */
    private static final long PTT_RETRY_WAIT_MAX_MS = 8000L;
    /** <b>全部重试等待加起来</b>的硬上限（毫秒）。 */
    private static final long PTT_RETRY_BUDGET_MAX_MS = 15000L;

    /**
     * <b>转写失败标记的键名</b>（写进那条 {@code record} 段的 fact）。
     *
     * <p>只在这一种情况下写：{@code records>0 && got==0} <b>且我们真的发过动作</b>
     * （{@link #CFG_PTT_TEXT} 开着、{@code message_id>0}）⇒ 值是 {@link #PTT_STATE_FAIL}。</p>
     *
     * <p><b>它解决的是"她说错话"</b>：转写取不到时标签仍是既有的 {@code [语音 12秒]}（见下），
     * 而那行 {@code media: [{…,"ptt_state":"fail"}]} 事实会进她这一轮的事实块 ⇒ 她能看到
     * "这条语音<b>有</b>转写的路、只是这次没取到"，而不是对外宣称"我这边没有听语音的路子"。</p>
     *
     * <p><b>为什么不改标签</b>：{@code MediaRender} 的 {@code [语音 12秒]} 是 N1 批钉死的
     * 逐字节契约（"取不到 = 没发生"，A1=A5 逐字节相等）—— 转写失败<b>不是</b>一种新的降级形态。
     * 标记因此只加在事实里，渲染链一个字节都不动。</p>
     *
     * <p><b>为什么在 fact 里而不是让渲染器读</b>：{@code ptt_state} 只有本类写
     * （{@code MediaRender} 一个键都不读它），所以它是"这一步的记账"，不是渲染口径。</p>
     */
    public static final String PTT_STATE_KEY = "ptt_state";
    /** {@link #PTT_STATE_KEY} 的值：这一条语音段<b>没拿到文本</b>（转写失败，或当时 NapCat 没连上 —— 成功则不会写这个键）。 */
    public static final String PTT_STATE_FAIL = "fail";

    private final Link link;
    private final Conf conf;

    /** 文件外链中转（可空：没装配时本地路径一律走 file:/// 回退）。 */
    private volatile Relay relay;

    /** 控制台日志去向（可空；只用于"本地文件没经中转"这类告警）。 */
    private volatile Out out;

    /**
     * 动作闸门（由基板装配时注入：技能/模型发起的动作要过权限复核）。
     * <p>两个动作出口都过它：{@link #call(String, JsonObject)}（第 197-205 行）与
     * {@link #callQuiet(String, JsonObject)}（2026-09-23「下沉批」起）—— 同一条判空式子、
     * 同一句异常兜底，拒绝原文都原样作为 {@code error} 返回。</p>
     */
    public interface Guard {
        /** 返回非空 = 拒绝（该文案直接作为 error 返回）；null = 放行。 */
        String deny(String action, JsonObject params);
    }

    private volatile Guard guard;

    /**
     * <b>出站消息台账注入口</b>（F5a）：{@link #call} 成功发出一条消息时，把这次拿到的
     * {@code data.message_id} 交给基板记下来 —— 撤回只能按 {@code message_id} 定位，
     * 而这个 id 以前是用完就丢的。
     *
     * <p><b>为什么是函数式接口而不是直接注入 {@code Store}</b>：① 与 {@link Guard} 同一先例；
     * ② {@code qq} 这层不需要知道"台账存在 SQL 里"（不引入 {@code qq → store} 的包依赖）；
     * ③ 探针可以直接装一个假 tap 断言"记了什么"，不必碰库。</p>
     *
     * <p><b>它是 fire-and-forget 的</b>：返回 {@code void}，由 {@link #call} 在 try/catch 里调 ——
     * 台账记录因此<b>不可能</b>改变、吞掉或拖住动作本身的返回值。</p>
     */
    public interface SentTap {
        /**
         * @param session   会话键（{@code group:<群号>} / {@code user:<QQ>}；推不出时为空串）
         * @param action    真实动作名（{@code send_group_msg} / {@code send_private_msg} / {@code send_msg}）
         * @param messageId 本次真发出去的消息 id（{@code > 0}）
         * @param preview   出站正文的纯文本预览（前 ~60 字；抽不出为空串）
         * @param params    本次发送的<b>完整参数</b>（{@link #call} 里 {@code rewrite} <b>之后</b>的副本，
         *                  因此本地路径已经换成了中转 URL）—— P0-1 起交给台账侧，
         *                  好让她自己发的那条也能渲染成一行标记行进她自己的记账。
         *                  <p>{@code rewrite} 之后的副本是<b>故意</b>的：记账要记的是"真发出去的那个东西"，
         *                  不是"她本来想发的本地路径"。唯一例外见 {@code Relay}（换 URL 只改这一个键）。</p>
         */
        void sent(String session, String action, long messageId, String preview, JsonObject params);
    }

    private volatile SentTap sentTap;

    /**
     * 权限面（技能管控表）：改写层在把本地文件交出去之前，按<b>这个动作的 op</b> 判一次
     * （op 名 = {@code napcat.<动作名>}，与 {@code Boot} 的 NapCat 闸门同一判据），
     * 再过<b>这个路径本身</b>的 {@code File} 域判定（{@link #pathDeny}）。
     */
    private volatile sair.v4.auth.Auth auth;

    public Api(Link link, Conf conf) {
        this.link = link;
        this.conf = conf;
    }

    /** 装闸门；{@code null} 表示不检查（基板自身的回复/定时推送走不装闸门的实例）。 */
    public void setGuard(Guard g) { this.guard = g; }

    public Guard guard() { return guard; }

    /**
     * 装权限面（{@code Boot} 装配时注入）；改写层据此判"这个 NapCat 动作的 op"<b>与路径本身</b>
     * （{@link #pathDeny}）。装配顺序是 {@code setRelay} 在前、{@code setAuth} 在后，所以两个入口
     * 都往 {@link Relay} 推一次 —— 中转自己那道路径判定（{@link Relay#urlOf} 一族）与这里同源。
     */
    public void setAuth(sair.v4.auth.Auth a) {
        this.auth = a;
        Relay r = this.relay;
        if (r != null) r.setAuth(a);
    }

    /** 装文件外链中转（{@code Boot} 装配时注入），并把当前权限面一起推给它。 */
    public void setRelay(Relay r) {
        this.relay = r;
        if (r != null) r.setAuth(this.auth);
    }

    public void setOut(Out o) { this.out = o; }

    /** 装出站消息台账（{@code Boot} 装配时注入）；{@code null} = 不记台账（发送行为一字不变）。 */
    public void setSentTap(SentTap t) { this.sentTap = t; }

    public SentTap sentTap() { return sentTap; }

    public Relay relay() { return relay; }

    public Link link() { return link; }

    public Conf conf() { return conf; }

    /** 连接可用性（= link.connected()）。 */
    public boolean available() { return link != null && link.connected(); }

    // ---------------- 调用入口 ----------------

    /**
     * 调一个动作并返回解析后的响应对象。
     *
     * <p><b>FIX-ECHO 补-3（独立复核 REFUTED 之后的下沉）</b>：发送类动作（{@link #SEND_ACTIONS}）
     * 的参数正文里出现自我记账头 ⇒ <b>整条拒发</b>（fail-closed，任一形态、任一位置含中段）。
     * 这一道是"动作出口"的兜底：{@link #sendText} 只覆盖三个类型化的文本发送方法，而
     * <b>这条 public 的 {@code call(...)} 是技能与模型绕不过去的那一跳</b> —— 模型可调的
     * {@code napcat} 工具（{@code Builtins} 的 {@code api.call(action, params)}）与技能的
     * {@code h.napcat()} 都从这里发。判据复用 {@link SelfEcho#headAt}（没有第二套匹配）；
     * <b>这里只拦不剥</b>（剥头语义只在 {@code term.Sinks} 一处）。位置在 {@code available()}
     * <b>之前</b>：拦不拦由正文决定，不取决于 NapCat 当前连没连上。</p>
     *
     * @param action OneBot v11 动作名，见 {@link #catalog()}
     * @param params 动作参数，可为 null（按空对象处理）
     */
    public JsonObject call(String action, JsonObject params) {
        if (action == null || action.trim().isEmpty()) return error("动作名为空");
        // 强制层：所有本地文件都在这里被换成中转 URL（技能与模型都绕不过这一层）。
        // 一个动作碰多个资源也不再逐资源判位（资源不再有位）：把本地文件交出去这件事，
        // 判的就是<b>这个动作自己的 op</b>（{@code napcat.<动作名>}）—— 改写层先判一次，
        // 闸门（Guard）再判一次，两处同一判据。
        // 改写层对每个"要交出去的本地文件"按这个 op 判一次，拒绝就把拒绝原文作为整条动作的结果返回，绝不静默跳过。
        Deny fileDeny = new Deny();
        JsonObject p = rewrite(action, params == null ? new JsonObject() : params, fileDeny);
        if (fileDeny.text != null) return error(fileDeny.text);
        Guard g = guard;
        if (g != null) {
            String deny;
            try {
                deny = g.deny(action, p);
            } catch (Throwable t) {
                deny = "[auth] 动作闸门异常：" + t;
            }
            if (deny != null && !deny.trim().isEmpty()) return error(deny);
        }
        // ★ FIX-ECHO 补-3/补-6：参数里出现自我记账头 ⇒ 拒发。判据是**按内容**（任意字符串叶子、
        //   任意动作名；表外动作也兜得住），深度 64 + 字符预算 200000，超限同样 fail-closed。
        String headFact = selfHeadFact(action, p);
        if (headFact != null) return selfHeadDeny("动作出口 call(" + Str.trim(action) + ")", headFact);
        // ★ SILENT-FIX：发送类动作的**整条正文**就是协议暗号 ⇒ 整条拒发（与 term.Sinks 那道闸同源）。
        //   ★ 2026-09-28「贴边暗号」批：整条不相等但**贴着首/尾**的带括号暗号 ⇒ 这里就地剥掉
        //     （判据只有 Agent.stripSilent 一处），剥完的正文继续走下面的内容级闸。
        String silentFact = silentFact(action, p);
        if (silentFact != null) return silentDeny("动作出口 call(" + Str.trim(action) + ")", silentFact);
        // ★ 基板提炼批（2026-09-22）：工具发送路的**内容级闸** —— [[mood:…]] 标记族剥离（剥完为空 ⇒ 拦下）
        //   与出站红线（不可替换 ⇒ 拦下；可替换 ⇒ 就地换词）。判据与词表同源（qq.MoodMarks / term.Redline），
        //   与 term.Sinks.gate 那一层是**同一套**，这里不写第二套。位置同样在 available() **之前**。
        //   ★ 2026-09-28「协议标记漏出」批：这一支里还加了族 A/族 B/族 C（见 contentJudge 的注释）。
        String protoBefore = bodyOf(p);
        String contentFact = contentFact(action, p);
        if (contentFact != null) return contentDeny("动作出口 call(" + Str.trim(action) + ")", contentFact);
        // ★ 2026-09-23「出站核心批」②：'任意动作 × 任意字符串叶子'的那一支（同源判据、同一套落法；
        //   三条例外见 contentFactAny）。位置上紧跟上面那一支：发送类动作的 message 仍由上面那一支
        //   出结论（错文串因此逐字不变），这一支负责其余动作与其余字段。
        String anyFact = contentFactAny(action, p);
        if (anyFact != null) return contentDeny("动作出口 call(" + Str.trim(action) + ")", anyFact);
        // ★ 2026-09-28：剥了协议标记就留一行痕（改写绝不静默；判据同源、不写第二套）
        protoWarn("动作出口 call(" + Str.trim(action) + ")", protoBefore);
        if (!available()) return error(NOT_CONNECTED);
        String text = link.call(action, p);
        if (text == null) return error("Napcat 没有响应（超时或连接已断开）: " + action);
        JsonObject o = J.obj(text);
        if (o == null) return error("Napcat 响应不是 JSON: " + brief(text));
        if (J.get(o, "error") == null) {
            long code = J.l(o, "retcode", 0L);
            String status = J.s(o, "status", "");
            if (code != 0 || "failed".equalsIgnoreCase(status)) {
                String msg = J.s(o, "message", "").trim();
                if (msg.isEmpty()) msg = J.s(o, "wording", "").trim();
                if (msg.isEmpty()) msg = "retcode=" + code;
                o.addProperty("error", msg);
            }
        }
        // 台账（可选）：真发出去的消息留一条底账（F5a）。它排在 return 之前的最后一步，
        // 只读入参、不改 o、不返回值 —— 记录失败绝不影响这条动作的结果（见 recordSent）。
        recordSent(action, p, o);
        return o;
    }

    /** 响应是否成功（没有 error 字段）。 */
    public static boolean ok(JsonObject resp) {
        return resp != null && J.get(resp, "error") == null;
    }

    /**
     * <b>静默调用</b>（M4：引用解析专用）：与 {@link #call} 的判定口径<b>逐条相同</b>
     * （未连接 / 非 JSON / retcode≠0 / status=failed 都补 {@code error}），
     * 但底层走 {@link Link#call(String, JsonObject, boolean) 静默重载} ⇒
     * <b>响应原文不进控制台</b>（{@code get_msg} 的响应前 200 字含被引消息正文）。
     *
     * <p><b>★ 2026-09-23「ACL/罢工闸下沉批」（老账 · 潜伏缺陷）</b>：它<b>也过闸门</b>了 ——
     * 与 {@link #call} 用的是<b>同一个</b> {@link Guard} 字段、同一条判空式子
     * （{@code deny != null && !deny.trim().isEmpty()}）、同一句异常兜底措辞，
     * 拒绝原文照旧原样作为 {@code {"error":"…"}} 返回。<b>判据不写第二套</b>：闸门体只有一处实现
     * —— {@code Boot} 装配的那个 {@code Api.Guard}（ACL 腿 = {@code Auth.allow(主体, "napcat.<动作名>")}，
     * 罢工腿 = {@code Builtins.strikeDenyAction}，<b>ACL 先判、罢工后判</b>）。</p>
     *
     * <p><b>为什么改口（旧口径错在哪）</b>：旧注释说"这是基板自己的读动作 ⇒ 不过 ACL 闸门"——
     * 那对<b>当前唯一的树内调用者</b>成立：{@link #getMsgQuiet(long)} 只被 {@code qq.QuoteCache} 用，
     * 而 {@code Boot} 把<b>不带闸门的</b> {@code Boot.api()} 传给了它（{@code Boot} 第 777 行），
     * 那个实例 {@code guard == null} ⇒ 补闸后<b>行为逐字节不变</b>。但 {@code callQuiet} 是
     * <b>{@code public}</b> 的，且 {@code Host.napcat()} = {@code Boot.guardedNapcat()} 给的是
     * <b>带闸门</b>那份 —— 拿着 {@code h.napcat()} 的技能直接调它，就绕过了权限与罢工。
     * 它与 {@link #call} 同属"工具发送路"，却有闸门而它没有 ⇒ 这是潜伏缺陷，不是"基板自己的读动作"。</p>
     *
     * <p><b>仍然不走文件改写层</b>（{@link #rewrite} / {@link #needOp} / {@link #pathDeny}）：① 它的既有
     * 契约是<b>不 deepCopy 参数</b>（改的是调用方自己那个对象 —— 两个探针正是靠这一点观测"就地剥标记 /
     * 就地换词"），而改写层第一句就是 {@code deepCopy}，接上去等于把这个契约换掉；
     * ② 它实际用的动作（{@code get_msg}）参数里没有任何本地文件（只有 {@code message_id}）。
     * <b>边界如实记</b>：将来若有调用者拿它发<b>带本地文件参数</b>的动作，{@link #needOp} /
     * {@link #pathDeny} 那两道<b>不会</b>生效 —— 那种用法要走 {@link #call} 或另开裁定。</p>
     *
     * <p><b>旧文（逐字留档；口径已被上一段取代）</b>：<i>"刻意<b>不做</b>两件事：① 不过 ACL 闸门 ——
     * 这是<b>基板自己的读动作</b>（同"落库"这一类的自主行为；带闸门那份是给技能/模型发起动作用的，
     * {@code Boot.api()} 的注释把这条口径写死了）；② 不走文件改写层 —— {@code get_msg}
     * 的参数里没有任何本地文件（只有 {@code message_id}）。"</i></p>
     *
     * <p><b>FIX-ECHO 补-6（复核证伪②）</b>：它<b>必须</b>过自我记账头那道闸 —— 这条 public 方法
     * 直连 {@link Link#call}，树内虽然只被 {@link #getMsg(long)} 用，但**拿着 {@code h.napcat()} 的
     * 技能能调**；复核实测它当时既无判据闸也无 ACL 闸（带头参数一路走到 {@code Napcat 没有连接}）。
     * 现在它与 {@link #call} 走<b>同一个拒绝函数</b>（同一处措辞、同一个 {@link SelfEcho#headAt}、
     * 同一套深度/预算闸），仍然<b>只拦不剥</b>。</p>
     */
    public JsonObject callQuiet(String action, JsonObject params) {
        if (action == null || action.trim().isEmpty()) return error("动作名为空");
        JsonObject p = params == null ? new JsonObject() : params;
        // ★ ACL / 罢工闸（2026-09-23「下沉批」）：与 call 的第 197-205 行**逐字同一条式子**。
        //   位置也与 call 同序：排在自我记账头 / SILENT / 内容级闸之前 ⇒ "ACL 拒文优先"这条
        //   裁定在两个出口上给出同一个答案（两处同时命中时报的是 ACL 那条）。
        //   闸门体只有一处实现（Boot 装配的 Api.Guard），这里一个字都不新写判据。
        Guard g = guard;
        if (g != null) {
            String deny;
            try {
                deny = g.deny(action, p);
            } catch (Throwable t) {
                deny = "[auth] 动作闸门异常：" + t;
            }
            if (deny != null && !deny.trim().isEmpty()) return error(deny);
        }
        String headFact = selfHeadFact(action, p);
        if (headFact != null) return selfHeadDeny("静默出口 callQuiet(" + Str.trim(action) + ")", headFact);
        // ★ SILENT-FIX：同一条整条判定（口径与 call 逐字相同）—— 这条 public 方法直连 Link，
        //   拿着 h.napcat() 的技能同样绕不过去（send 类动作的正文就是"要说出去的那句话"）。
        //   ★ 2026-09-28「贴边暗号」批：同一条"贴边 ⇒ 就地剥"（与 call 逐字相同）；注意本方法
        //     的既有契约是**不 deepCopy 参数**，所以这里剥的是**调用方自己那个对象**（与它下面
        //     那条 contentFact 的"就地剥标记 / 就地换词"是同一个契约）。
        String silentFact = silentFact(action, p);
        if (silentFact != null) return silentDeny("静默出口 callQuiet(" + Str.trim(action) + ")", silentFact);
        // ★ 基板提炼批：同一条内容级闸（口径与 call 逐字相同）。
        String protoBefore = bodyOf(p);
        String contentFact = contentFact(action, p);
        if (contentFact != null) return contentDeny("静默出口 callQuiet(" + Str.trim(action) + ")", contentFact);
        // ★ 2026-09-23「出站核心批」②：同一条 widened 闸（口径与 call 逐字相同）。
        String anyFact = contentFactAny(action, p);
        if (anyFact != null) return contentDeny("静默出口 callQuiet(" + Str.trim(action) + ")", anyFact);
        // ★ 2026-09-28：剥了协议标记就留一行痕（与 call 逐字同一条）
        protoWarn("静默出口 callQuiet(" + Str.trim(action) + ")", protoBefore);
        if (!available()) return error(NOT_CONNECTED);
        String text = link.call(action, p, true);
        if (text == null) return error("Napcat 没有响应（超时或连接已断开）: " + action);
        JsonObject o = J.obj(text);
        if (o == null) return error("Napcat 响应不是 JSON: " + brief(text));
        if (J.get(o, "error") == null) {
            long code = J.l(o, "retcode", 0L);
            String status = J.s(o, "status", "");
            if (code != 0 || "failed".equalsIgnoreCase(status)) {
                String msg = J.s(o, "message", "").trim();
                if (msg.isEmpty()) msg = J.s(o, "wording", "").trim();
                if (msg.isEmpty()) msg = "retcode=" + code;
                o.addProperty("error", msg);
            }
        }
        return o;
    }

    /**
     * 静默取一条消息（M4 的 {@code get_msg} 路）。
     *
     * <p>与 {@link #getMsg(long)} 的差别只有一个：<b>响应原文不进控制台</b>。
     * 契约（成功 / {@code data.message=[]} 空正文 / {@code retcode 1200} 已撤回）逐条相同。</p>
     */
    public JsonObject getMsgQuiet(long messageId) {
        return callQuiet("get_msg", J.obj("message_id", messageId));
    }

    // ---------------- N1：语音转写（入站渲染链的前置一步） ----------------

    /**
     * <b>入站渲染链的前置一步</b>：给 {@code facts} 里的语音段补上转写文本
     * （键 {@link MediaRender#PTT_KEY}），供 {@link MediaRender#render(Ev, JsonArray)} 渲染成
     * {@code [语音 12秒：转写文本]}。
     *
     * <h3>为什么单独一步、而不是让渲染器自己去调</h3>
     * <p>{@code MediaRender} 的契约是"纯函数：无网络、无时钟、不读配置"（那个契约是"同一条消息
     * 只有一种渲染"的结构性保证）—— 它<b>不许</b>发动作。所以调用点只能是入站渲染链自己：
     * 拿到 {@code mediaFacts} 之后、调 {@code MediaRender.render} <b>之前</b>走这一步。</p>
     *
     * <h3>纪律（逐条）</h3>
     * <ol>
     *   <li><b>绝不影响消息本身</b>：任何失败（未连接 / 超时 / 版本 &lt; 4.18.2 / retcode≠0 /
     *       响应畸形 / 抛异常）都吞掉 ⇒ 转写为空 ⇒ 标签逐字节退回旧形状 {@code [语音 12秒]}；</li>
     *   <li><b>静默</b>：走 {@link #callQuiet}，响应原文不进控制台（转写是<b>别人的话</b>，
     *       控制台不是聊天记录的回声墙）；本方法自己<b>不打任何日志</b>，只返回一行结构事实
     *       （只有规模：几个语音段、有没有转写成功），由调用方按自己的口径打；</li>
     *   <li><b>一条消息最多一次调用</b>（<b>★ W6 起：失败会按预算重试</b>）：同一条消息里的 N 个语音段
     *       共用同一次 {@code fetch_ptt_text}（它按 {@code message_id} 取），拿到什么就写进每个语音段的 fact。
     *       <b>重试（2026-09-24 W6 批）</b>：首取失败 ⇒ 按 {@link #CFG_PTT_RETRY}（默认
     *       {@value #PTT_RETRY_DEFAULT} 次）/ {@link #CFG_PTT_RETRY_MS}（默认 {@value #PTT_RETRY_MS_DEFAULT}）
     *       短退避再取，取得就立刻停；<b>最坏额外等待 ≈6 s</b>（真机实测：转写窗口约 3 s），且三道硬顶（次数 ≤ {@value #PTT_RETRY_MAX}、
     *       单次等待 ≤ {@value #PTT_RETRY_WAIT_MAX_MS} ms、全部等待合计 ≤ {@value #PTT_RETRY_BUDGET_MAX_MS} ms）
     *       ⇒ <b>绝无无界阻塞</b>；未连接时第一跳就收手、不重试。失败照旧一律吞掉（不抛、不阻断渲染）。
     *       理由与真机证据见 {@link #CFG_PTT_RETRY}（同一条语音首取失败、两分钟后同一条再取成功）；</li>
     *   <li><b>这一步说了算</b>：处理一个语音段时<b>先删掉</b> fact 里已有的
     *       {@link MediaRender#PTT_KEY} 与 {@link #PTT_STATE_KEY} 再写这一次的结果（取不到 ⇒
     *       转写键删了不写、只写失败标记）。
     *       理由：{@code QqGateway.mediaFacts} 会把段 {@code data} 里的标量字段<b>原样抄进 fact</b>，
     *       所以"段自己带了一个 {@code ptt_text}"是会出现在 fact 里的；只有让这一步成为唯一权威，
     *       那句话才一定是<b>我们这次真问出来的</b>。代价如实记：同一条消息调两次 = 多发一次动作
     *       （调用点在入站渲染链只有一处，刻意不做内存幂等标记 —— 那会引入"谁先写的"第二个真相）；</li>
     *   <li><b>失败要在事实里留痕</b>（★ W6 新增）：真发过动作却没取到 ⇒ 那条 record 段的 fact 多一个
     *       {@code "ptt_state":"fail"}（{@link #PTT_STATE_KEY}）—— <b>标签一个字节都不改</b>
     *       （{@link MediaRender} 的 {@code [语音 12秒]} 是 N1 批钉死的逐字节契约），
     *       但这一行事实会进她的事实块 ⇒ 她不会把"这次没取到"说成"我没有听语音的路子"。
     *       关掉开关 / 没有 {@code message_id} 两种情况<b>不写</b>这个键（那不是失败，{@code why} 已说明）；</li>
     *   <li><b>可关</b>：{@link #CFG_PTT_TEXT}（默认开）为 false ⇒ 一次动作都不发，
     *       事实行里 {@code "enabled":false}。</li>
     * </ol>
     *
     * @param ev    入站事件（{@code null} ⇒ 空串，不发动作）
     * @param facts {@code QqGateway.mediaFacts(ev)} 的输出（<b>就地</b>补键；{@code null} ⇒ 空串）
     * @return 一行结构事实。既有四个键（{@code records}/{@code text}/{@code none}/{@code enabled}）
     *         与 {@code why} <b>一个不删、顺序与值都不动</b>；<b>新增字段 {@code tries}</b>
     *         （这条消息一共发了几次 {@code fetch_ptt_text}）<b>只在真的重试过（{@code tries ≥ 2}）时出现</b>
     *         ⇒ 首取就成 / 关开关 / 没有 msg_id 三种情况下这一行与加重试之前<b>逐字节相同</b>。
     *         例：{@code ptt: {"records":1,"text":1,"none":0,"enabled":true,"tries":2}}；
     *         这条消息<b>没有</b>语音段 ⇒ 空串（不打行）
     */
    public String pttEnrich(Ev ev, JsonArray facts) {
        if (ev == null || facts == null) return "";
        try {
            int records = 0;
            int got = 0;
            long msgId = ev.messageId();
            boolean on = conf == null || conf.getBool(CFG_PTT_TEXT, true);
            String text = "";
            boolean tried = false;
            int tries = 0;                                           // 这条消息真发了几次 fetch_ptt_text
            for (int i = 0; i < facts.size(); i++) {
                JsonElement e = facts.get(i);
                if (e == null || !e.isJsonObject()) continue;
                JsonObject f = e.getAsJsonObject();
                if (!"record".equals(Str.lower(Str.trim(J.s(f, "type", ""))))) continue;
                records++;
                f.remove(MediaRender.PTT_KEY);                       // 这一步说了算（先删旧的，见 javadoc）
                f.remove(PTT_STATE_KEY);                             // 同一个权威口径：失败标记也只由这一步写
                if (!on || msgId <= 0L) continue;
                if (!tried) {                                        // 一条消息只发一次动作（失败才按预算重试）
                    tried = true;
                    int[] n = new int[1];
                    text = pttTextQuiet(msgId, n);
                    tries = n[0];
                }
                if (Str.has(text)) {
                    f.addProperty(MediaRender.PTT_KEY, Str.cut(text, MediaRender.PTT_TEXT_CHARS));
                    got++;
                } else {
                    // 真发过动作但这一次没取到 ⇒ 留一个失败标记（**不改标签**，只进事实；见 PTT_STATE_KEY）。
                    f.addProperty(PTT_STATE_KEY, PTT_STATE_FAIL);
                }
            }
            if (records == 0) return "";
            JsonObject o = new JsonObject();
            o.addProperty("records", records);
            o.addProperty("text", got);
            o.addProperty("none", records - got);
            o.addProperty("enabled", on);
            // ★ 结构事实行新增字段（既有四个键一个不删、顺序与值都不动）：
            //   只在**真的重试过**（tries ≥ 2）时才出现 ⇒ 首取就成 / 关开关 / 没有 msg_id 三种情况下
            //   这一行与加重试之前**逐字节相同**（既有夹具是整行相等断言）。含义 = 这条消息一共发了几次
            //   `fetch_ptt_text`（1 = 首取，2 = 首取失败后第 1 次重试就成，3 = 首取 + 两次重试都用掉了）。
            if (tries > 1) o.addProperty("tries", tries);
            if (!on) o.addProperty("why", "off");
            else if (msgId <= 0L) o.addProperty("why", "no_msg_id");
            return PTT_FACT_PREFIX + J.json(o);
        } catch (Throwable ignored) {
            return "";            // 纯附属步骤：它自己绝不抛（调用方也不必为它兜异常）
        }
    }

    /**
     * {@code fetch_ptt_text}（静默、吞错）：拿到转写文本，<b>任何失败 ⇒ 空串</b>。
     *
     * <p>版本要求：NapCat <b>4.18.2+</b>（更早的实现没有这个动作 ⇒ 响应 retcode≠0 ⇒ 空串 ⇒ 旧标签）。
     * 参数 {@code message_id}（上游快照写的是 {@code anyOf[number|string]}）。</p>
     *
     * <p><b>★ 首取失败会按预算重试</b>（2026-09-24 W6 批；真机证据与理由见 {@link #CFG_PTT_RETRY}）：</p>
     * <ul>
     *   <li>重试次数上限 {@link #CFG_PTT_RETRY}（默认 {@value #PTT_RETRY_DEFAULT} = 首取之后再取 3 次
     *       ⇒ <b>合计最多 4 次动作</b>），硬顶 {@value #PTT_RETRY_MAX}（⇒ 动作最多 {@value #PTT_RETRY_MAX} + 1 次）；</li>
     *   <li>间隔 {@link #CFG_PTT_RETRY_MS}（默认 {@value #PTT_RETRY_MS_DEFAULT}），单次 ≤ {@value #PTT_RETRY_WAIT_MAX_MS} ms、
     *       <b>全部等待加起来 ≤ {@value #PTT_RETRY_BUDGET_MAX_MS} ms</b>（睡满预算后剩下的重试不再等）；</li>
     *   <li><b>取得文本立刻停</b>（后续重试一个都不发）；只有"空串"才算失败；</li>
     *   <li><b>未连接不重试</b>：{@code available()==false} 时第一跳就收手 —— 三秒内不会连上，
     *       白等只会占着入站事件线程（这一条也让"NapCat 根本没连"的既有夹具保持逐字节与零延迟）；</li>
     *   <li><b>失败仍然一律吞掉</b>：不抛、不返回 error 文案、绝不阻断渲染 —— 签名与"空串"契约不变；
     *       {@link InterruptedException} 只恢复中断位并收手（不把异常漏出去）。</li>
     * </ul>
     *
     * @param messageId 消息 id（{@code <=0} ⇒ 空串、<b>一次动作都不发</b>）
     * @return 转写文本；任何失败（含重试用尽）⇒ 空串
     */
    public String pttTextQuiet(long messageId) {
        return pttTextQuiet(messageId, null);
    }

    /**
     * {@link #pttTextQuiet(long)} 的<b>带计数</b>重载（内部用）：{@code tries[0]} 回填
     * <b>这次一共发了几次动作</b>（含首取；任何提前收手都如实回填）。
     *
     * <p>刻意 <b>private</b>：公开面（{@link #pttTextQuiet(long)}）的签名与返回值一个字节都不动，
     * "试了几次"只经由结构事实行的 {@code tries} 字段对外可见。</p>
     *
     * @param tries 计数器（可为 {@code null} = 不关心）
     */
    private String pttTextQuiet(long messageId, int[] tries) {
        if (tries != null) tries[0] = 0;
        if (messageId <= 0L) return "";
        int retry = pttRetryCount();
        long[] waits = pttRetryWaits(retry);
        int n = 0;
        for (int k = 0; ; k++) {
            n++;
            String text;
            try {
                text = pttText(callQuiet(A_PTT_TEXT, J.obj("message_id", messageId)));
            } catch (Throwable ignored) {
                text = "";
            }
            if (Str.has(text)) {
                if (tries != null) tries[0] = n;
                return text;                                  // 取得就立刻停（不再重试）
            }
            if (k >= retry) break;                            // 次数用尽
            if (!available()) break;                          // 未连接：再等也不会连上，立刻收手
            long w = waits[k];
            if (w <= 0L) continue;                            // 间隔配成 0 ⇒ 这一次不等
            try {
                Thread.sleep(w);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (tries != null) tries[0] = n;
        return "";
    }

    /** 重试次数上限（配置 {@link #CFG_PTT_RETRY}）：{@code <0} ⇒ 0；{@code >} {@value #PTT_RETRY_MAX} ⇒ 夹住。 */
    private int pttRetryCount() {
        int v;
        try {
            v = conf == null ? PTT_RETRY_DEFAULT : conf.getInt(CFG_PTT_RETRY, PTT_RETRY_DEFAULT);
        } catch (Throwable t) {
            v = PTT_RETRY_DEFAULT;
        }
        if (v < 0) v = 0;
        if (v > PTT_RETRY_MAX) v = PTT_RETRY_MAX;
        return v;
    }

    /**
     * 每次重试前的等待序列（毫秒；纯计算，读了配置也只读 {@link #CFG_PTT_RETRY_MS}）。
     *
     * <p>逐次夹三道：单次 ≤ {@value #PTT_RETRY_WAIT_MAX_MS}；<b>累计 ≤ {@value #PTT_RETRY_BUDGET_MAX_MS}</b>
     * （预算睡满之后剩下的项自动变 0 = 不再等）；序列比次数上限短 ⇒ 复用最后一个值。</p>
     */
    private long[] pttRetryWaits(int count) {
        if (count <= 0) return new long[0];
        String spec;
        try {
            spec = conf == null ? PTT_RETRY_MS_DEFAULT : conf.get(CFG_PTT_RETRY_MS, PTT_RETRY_MS_DEFAULT);
        } catch (Throwable t) {
            spec = PTT_RETRY_MS_DEFAULT;
        }
        long[] vals = pttWaitsOf(spec);
        long[] out = new long[count];
        long left = PTT_RETRY_BUDGET_MAX_MS;
        for (int i = 0; i < count; i++) {
            long w = vals.length == 0 ? 0L : vals[Math.min(i, vals.length - 1)];
            if (w < 0L) w = 0L;
            if (w > PTT_RETRY_WAIT_MAX_MS) w = PTT_RETRY_WAIT_MAX_MS;
            if (w > left) w = left;
            left -= w;
            out[i] = w;
        }
        return out;
    }

    /**
     * {@code "1200,2000"} → {@code {1200,2000}}（<b>纯函数</b>）。
     * <p>空串/全空白 ⇒ 默认序列 {@value #PTT_RETRY_MS_DEFAULT}；单个段畸形（非数字）⇒ 那一段按 0
     * （= 那一次不等），<b>绝不抛</b>。</p>
     */
    private static long[] pttWaitsOf(String spec) {
        String s = Str.trim(spec);
        if (s.isEmpty()) s = PTT_RETRY_MS_DEFAULT;
        String[] parts = s.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            long v = 0L;
            try {
                v = Long.parseLong(Str.trim(parts[i]));
            } catch (Throwable ignored) {
                v = 0L;
            }
            out[i] = v;
        }
        return out;
    }

    /**
     * {@code fetch_ptt_text} 的响应 → 转写文本（<b>纯函数，唯一取值口径</b>）。
     *
     * <p>上游 OpenAPI 快照<b>没有</b>给这个端点的 response schema（{@code D5.md} 4.18.2 原文：
     * "该端点在该快照中无 description 文本"）⇒ 这里按"候选键 + 字符串 data"取，
     * <b>取不到就空串</b>（= 退回旧标签），绝不猜、绝不把 error 文案当转写：</p>
     * <ol>
     *   <li>{@code data.text} / {@code data.ptt_text} / {@code data.result}（三个候选键）；</li>
     *   <li>{@code data} 本身是字符串 ⇒ 就是它；</li>
     *   <li>顶层 {@code text}。</li>
     * </ol>
     * <p>刻意 <b>不</b>认 {@code message}/{@code wording}：那两个键在失败响应里是错误文案。</p>
     */
    public static String pttText(JsonObject resp) {
        if (resp == null || !ok(resp)) return "";
        JsonObject d = J.sub(resp, "data");
        if (d != null) {
            for (String k : new String[] {"text", "ptt_text", "result"}) {
                String v = J.s(d, k, "");
                if (Str.has(v)) return Str.trim(v);
            }
        }
        JsonElement e = J.get(resp, "data");
        if (e != null && e.isJsonPrimitive()) {
            try {
                String v = e.getAsString();
                if (Str.has(v)) return Str.trim(v);
            } catch (Throwable ignored) {
            }
        }
        String top = Str.trim(J.s(resp, "text", ""));
        return Str.has(top) ? top : "";
    }

    // ---------------- N1：图片直链刷新（rkey 扩展接进既有取回链） ----------------

    /**
     * <b>刷新一条可能已过期的图片直链</b>。
     *
     * <h3>上游事实（这是本方法存在的全部理由）</h3>
     * <p>{@code D4.md} 原文："图片的链接具有约 <b>2 小时</b>的过期时间，当过期后会提示
     * {@code url expired}。此时可以调用 {@code nc_get_rkey} 获取新 rkey 替换 rkey 使用，
     * 或者通过 {@code get_image}、{@code get_file}、{@code get_msg} 刷新获取新的 URL。"</p>
     *
     * <h3>顺序（前一步拿不到就退下一步；全拿不到 ⇒ 空串）</h3>
     * <ol>
     *   <li>{@code nc_get_rkey}：把 URL 里的 {@code rkey=} 换掉（<b>同一个 URL 形状，不新造资源</b>，
     *       也不冒充别的接口）；</li>
     *   <li>{@code get_image}（{@code file} 值）；</li>
     *   <li>{@code get_file}（{@code file} 值）；</li>
     *   <li>{@code get_msg}（{@code message_id}）—— 与 {@link #getMsgQuiet(long)} 同一条既有取回路。</li>
     * </ol>
     * <p>只收 {@code http(s)} / {@code data:image/} 形态的地址：{@code get_image}/{@code get_file}
     * 在<b>跨机</b> NapCat 上给的是<b>它那台机器</b>的本地路径（{@code qq.Inbound} 类注释记的就是这个坑），
     * 那不是"刷新出来的直链"，按"取不回"处理、继续走下一步。</p>
     *
     * <h3>降级口径（写死在注释里，调用方按它决定说什么）</h3>
     * <ul>
     *   <li>返回空串 ⇒ <b>保留原 URL</b>，不报错、不丢消息、不编地址（本方法本身不抛异常）；</li>
     *   <li><b>已撤回</b>的消息：上游原文"已撤回的消息无法被再次获取或恢复"⇒ {@code get_msg} 给
     *       retcode 1200 ⇒ 空串（这一条无论走哪一步都取不回）；</li>
     *   <li><b>超过约 5000 条</b>的消息/文件：上游原文"每条消息的 ID 都是唯一的，且在大约 5000 条消息后
     *       会因 LRU 策略而过期并被清理"⇒ 同样取不回（{@code D3.md} NapCat 资源与消息 ID 设计说明）；</li>
     *   <li>rkey 是<b>令牌</b>：本方法<b>不</b>打印、<b>不</b>落库、<b>不</b>进事实行（只返回 URL）。</li>
     * </ul>
     *
     * @param url       现有直链（可空 —— 那就只能靠 file/message_id 取一条新的）
     * @param file      NapCat 的 {@code file} 值（可空）
     * @param messageId 这条消息的 id（{@code <=0} = 不知道 ⇒ 跳过第 ④ 步）
     * @return 新地址；取不回 ⇒ 空串
     */
    public String refreshImageUrlQuiet(String url, String file, long messageId) {
        try {
            String u = refreshByRkey(url);
            if (Str.has(u)) return u;
            String f = Str.trim(file);
            if (Str.has(f)) {
                u = firstHttpUrl(callQuiet(A_GET_IMAGE, J.obj("file", f)));
                if (Str.has(u)) return u;
                u = firstHttpUrl(callQuiet(A_GET_FILE, J.obj("file", f)));
                if (Str.has(u)) return u;
            }
            if (messageId > 0L) {
                u = firstHttpUrl(callQuiet("get_msg", J.obj("message_id", messageId)));
                if (Str.has(u)) return u;
            }
        } catch (Throwable ignored) {
            // 刷新是附属步骤：任何异常都吞掉 ⇒ 空串 ⇒ 调用方保留原 URL
        }
        return "";
    }

    /** 第 ① 步：用 {@code nc_get_rkey} 换掉 URL 里的 rkey（没变/换不成 ⇒ 空串）。 */
    private String refreshByRkey(String url) {
        String u = Str.trim(url);
        if (u.indexOf("rkey=") < 0) return "";
        return replaceRkey(u, rkeyQuiet());
    }

    /** {@code nc_get_rkey}（静默、吞错）：拿新 rkey，失败 ⇒ 空串。<b>返回值是令牌，调用方不许打日志。</b> */
    public String rkeyQuiet() {
        try {
            return rkey(callQuiet(A_NC_RKEY, new JsonObject()));
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * {@code nc_get_rkey} 的响应 → rkey 字符串（<b>纯函数，唯一取值口径</b>）。
     * <p>候选：{@code data.rkey} / {@code data.r_key} / {@code data} 是字符串 / 顶层 {@code rkey}；
     * 都取不到 ⇒ 空串。</p>
     */
    public static String rkey(JsonObject resp) {
        if (resp == null || !ok(resp)) return "";
        JsonObject d = J.sub(resp, "data");
        if (d != null) {
            String v = Str.trim(J.s(d, "rkey", ""));
            if (!v.isEmpty()) return v;
            v = Str.trim(J.s(d, "r_key", ""));
            if (!v.isEmpty()) return v;
        }
        JsonElement e = J.get(resp, "data");
        if (e != null && e.isJsonPrimitive()) {
            try {
                String v = Str.trim(e.getAsString());
                if (!v.isEmpty()) return v;
            } catch (Throwable ignored) {
            }
        }
        return Str.trim(J.s(resp, "rkey", ""));
    }

    /**
     * 把一个直链里的 {@code rkey=…} 换成新 rkey（<b>纯函数</b>）。
     *
     * <p>认的是查询串里第一个 {@code rkey=} 参数，换到下一个 {@code &} 之前；其余字节（appid/fileid/
     * domain 等）一个都不动 —— 这就是上游说的"获取新 rkey 替换 rkey 使用"。</p>
     *
     * @return 换好的 URL；URL 里没有 {@code rkey=} / rkey 为空 / 换完<b>与原来相同</b> ⇒ 空串
     *         （"没变化"不算刷新成功，调用方应当继续退下一步）
     */
    public static String replaceRkey(String url, String rkey) {
        String u = Str.trim(url);
        String r = Str.trim(rkey);
        if (u.isEmpty() || r.isEmpty()) return "";
        int i = u.indexOf("rkey=");
        if (i < 0) return "";
        int j = u.indexOf('&', i);
        String head = u.substring(0, i + 5);
        String cur = j < 0 ? u.substring(i + 5) : u.substring(i + 5, j);
        String tail = j < 0 ? "" : u.substring(j);
        if (cur.equals(r)) return "";
        return head + r + tail;
    }

    /**
     * 响应里第一个 {@code http(s)} / {@code data:image/} 形态的字符串（<b>纯函数</b>）。
     * <p>刻意<b>不猜键名</b>（{@code get_image}/{@code get_file}/{@code get_msg} 三者的响应形状
     * 不一样，而且会随版本变）：扫深度 ≤ {@value #URL_SCAN_DEPTH}、节点数 ≤ {@value #URL_SCAN_NODES}
     * 的 JSON，取第一个像直链的字符串；扫不到 ⇒ 空串。</p>
     */
    private static String firstHttpUrl(JsonObject resp) {
        if (resp == null || !ok(resp)) return "";
        String u = firstHttpUrl(J.get(resp, "data"), 0, new int[] {URL_SCAN_NODES});
        return u == null ? "" : u;
    }

    private static String firstHttpUrl(JsonElement e, int depth, int[] budget) {
        if (e == null || e.isJsonNull() || depth > URL_SCAN_DEPTH || budget[0] <= 0) return null;
        budget[0]--;
        if (e.isJsonPrimitive()) {
            try {
                String s = e.getAsString();
                String l = Str.lower(Str.trim(s));
                boolean okUrl = l.startsWith("http://") || l.startsWith("https://") || l.startsWith("data:image/");
                return okUrl ? Str.trim(s) : null;
            } catch (Throwable t) {
                return null;
            }
        }
        if (e.isJsonObject()) {
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                String r = firstHttpUrl(en.getValue(), depth + 1, budget);
                if (r != null) return r;
            }
            return null;
        }
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                String r = firstHttpUrl(x, depth + 1, budget);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------------- N2/D5：Stream API（NapCat v4.8.115+；大文件 / 跨设备） ----------------
    //
    // 上游事实（tmp/v6-real/upstream/D4.md「Stream API（推荐方案）」一节）：
    //   ① v4.8.115+ 起 NapCat 引入 Stream API，定位是「大文件传输」（>100MB，避免内存溢出）
    //      与「跨设备部署」（NapCat 与 QQ 不在同一台机器）；
    //   ② 三类：Normal（clean_stream_temp_file）/ Download（test_download_stream、
    //      download_file_stream）/ Upload（upload_file_stream）；
    //   ③ 「Upload/Download 组 API 的 action name 必须以 stream 结尾」；
    //   ④ 报文用 type = stream / response / error（源码里还有 reset）区分流状态，
    //      顶层另有 stream = "stream-action" 标记这是流式接口。
    //
    // 载荷契约（源码级，比文档页更细；两个文件抓的是 GitHub main 上的
    // packages/napcat-onebot/action/stream/{UploadFileStream,DownloadFileStream,BaseDownloadStream,StreamBasic}.ts）：
    //   upload_file_stream  : { stream_id(必填), chunk_data(base64,可选), chunk_index, total_chunks,
    //                          file_size, expected_sha256, is_complete, filename, reset, verify_only,
    //                          file_retention(默认 5 分钟) }
    //                          → 分片回 {type:stream,status:chunk_received,received_chunks,total_chunks}；
    //                            收完回 {type:response,status:file_complete,file_path,file_size,sha256}
    //   download_file_stream: { file|file_id, chunk_size(默认 64KB) }
    //                          → 先 {type:stream,data_type:file_info,file_name,file_size,chunk_size}，
    //                            再 N 个 {type:stream,data_type:file_chunk,index,data(base64),size,progress}，
    //                            最后 {type:response,data_type:file_complete,total_chunks,total_bytes}
    //
    // ★ 与 relay / upload_group_file 的分工（这是本节的要点，写在这里免得以后再猜）：
    //   · **relay（既有，同机也行）**：把"本机的文件"用一个 HTTP URL 交给 NapCat 去取 ——
    //     适合"文件在基板这台机器、且两台机器网络互通"的中小文件；它的代价是开一个能读本机
    //     任意文件的服务（relayEnabled 默认 false，就是这个原因）。
    //   · **Stream（本单接的）**：把字节**推/拉到 NapCat 那台机器**再处理 ——
    //     跨设备时不需要任何入站服务（NapCat 主动连的是反向 WS，一直是通的），
    //     而且超过 100MB 的文件不会整块进内存（本层按 chunk 读、按 chunk 发）。
    //   · **upload_group_file（既有）**：把文件"发到群里"的那一步 —— 它的 file 参数是
    //     NapCat 那台机器上的路径或 URL。于是 stream 上传与它天然是两段：
    //       stream 上传 = 把字节送上 NapCat 那台机器（拿到它那边的 file_path）
    //       upload_group_file = 用那个路径把文件发进群
    //     本单把这两段串起来（{@link #streamUploadGroupFileQuiet}），失败回退到既有 relay 路。
    //
    // ★ 一条如实的边界（不掩盖）：{@link #streamUploadGroupFileQuiet} 第二步要把**NapCat 那台
    //   机器上的路径**交给 upload_group_file，而这一步会过基板既有的改写层与 File 域判定
    //   （{@code rewriteLocalFile} 把任何形如 {@code C:\x} / {@code /x} 的值当"本机文件"）——
    //   跨机时那个路径在本机并不存在。所以：relay 在跑 ⇒ 值会被改写成中转 URL（取不到）；
    //   路径不在本机允许范围内 ⇒ 被 File 域拒（fail-closed）。两种情况下本方法都**回退到既有
    //   relay 路**（结果正确、只是白做一次上传）。要让它一步到位，需要给改写层加一个
    //   "这个路径在 NapCat 那台机器上"的显式豁免 —— 那是**放宽一道既有闸**，不在本单权限内，
    //   如实交底在报告 ⑦，等鱼总裁定。

    /** Stream 上传动作名（v4.8.115+；必须以 {@code stream} 结尾）。 */
    public static final String A_STREAM_UPLOAD = "upload_file_stream";
    /** Stream 下载动作名（v4.8.115+）。 */
    public static final String A_STREAM_DOWNLOAD = "download_file_stream";
    /** 分块大小的配置键（字节；默认 64 KiB = NapCat 自己的默认）。 */
    public static final String CFG_STREAM_CHUNK = "streamChunkBytes";
    /** 流式传输总时限的配置键（毫秒；默认 10 分钟 = NapCat 自己的 stream 超时）。 */
    public static final String CFG_STREAM_TIMEOUT = "streamTimeoutMs";
    /** 分块大小的出厂默认（字节，与 NapCat 的 {@code chunk_size} 默认一致）。 */
    public static final int STREAM_CHUNK_DEFAULT = 64 * 1024;
    /** 流式传输总时限的出厂默认（毫秒，与 NapCat 自己的 10 分钟一致）。 */
    public static final long STREAM_TIMEOUT_DEFAULT = 10L * 60L * 1000L;
    /** NapCat 那台机器上临时文件的保留时间（毫秒，默认 5 分钟 = NapCat 自己的默认）。 */
    public static final long STREAM_RETENTION_DEFAULT = 5L * 60L * 1000L;
    /** 下载回流的内存上限（字节；超过就中止，宁可失败也不把内存吃光）。 */
    public static final long STREAM_DOWNLOAD_MAX = 256L * 1024 * 1024;
    /** 同时在收的下载流槽位数（超过就丢最旧的一条，防只进不出）。 */
    private static final int STREAM_SLOTS = 4;

    /** 一次流式传输的总时限（毫秒；{@code <=0} 用默认）。 */
    private long streamTimeoutMs() {
        try {
            long v = conf == null ? STREAM_TIMEOUT_DEFAULT : conf.getInt(CFG_STREAM_TIMEOUT, (int) STREAM_TIMEOUT_DEFAULT);
            return v > 0L ? v : STREAM_TIMEOUT_DEFAULT;
        } catch (Throwable t) {
            return STREAM_TIMEOUT_DEFAULT;
        }
    }

    /** 分块大小（字节；{@code <=0} 用默认，且夹到 1 KiB..4 MiB）。 */
    private int streamChunkBytes() {
        int v;
        try {
            v = conf == null ? STREAM_CHUNK_DEFAULT : conf.getInt(CFG_STREAM_CHUNK, STREAM_CHUNK_DEFAULT);
        } catch (Throwable t) {
            v = STREAM_CHUNK_DEFAULT;
        }
        if (v <= 0) v = STREAM_CHUNK_DEFAULT;
        if (v < 1024) v = 1024;
        if (v > 4 * 1024 * 1024) v = 4 * 1024 * 1024;
        return v;
    }

    /**
     * <b>流式上传一个本机文件到 NapCat 那台机器</b>（{@code upload_file_stream}，v4.8.115+）。
     *
     * <p>分块（{@link #streamChunkBytes()}，默认 64 KiB）逐块 base64 发上去，块序 {@code chunk_index}
     * 从 0 连续；最后发一次 {@code is_complete=true} 让 NapCat 合并并校验。</p>
     *
     * <p><b>为什么必须分块</b>：上游给的定位就是"大于 100MB 的文件，避免内存溢出" ——
     * 本层按块读文件（不把整个文件读进内存），也不会把整个文件的 base64 一次塞进一帧。</p>
     *
     * @param file 本机文件路径（不存在/读不了 ⇒ 空串）
     * @param name 显示文件名（空 ⇒ 取 file 的文件名）
     * @return NapCat 那台机器上的 {@code file_path}（成功）；<b>任何失败 ⇒ 空串</b>
     *         （未连接 / 动作不被支持（&lt; 4.8.115）/ 超限 / retcode≠0 / 响应畸形 / 超时）
     */
    public String streamUploadQuiet(String file, String name) {
        if (!available()) return "";
        java.io.File f = new java.io.File(Str.trim(file));
        if (!f.isFile() || !f.canRead()) return "";
        long size = f.length();
        int chunk = streamChunkBytes();
        int total = (int) ((size + chunk - 1) / chunk);
        if (total <= 0) total = 1;
        String nm = fileName(file, name);
        String sid = "v4-" + System.currentTimeMillis() + "-" + (STREAM_SEQ.incrementAndGet());
        long deadline = System.currentTimeMillis() + streamTimeoutMs();
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[chunk];
            for (int i = 0; i < total; i++) {
                if (System.currentTimeMillis() > deadline) return "";       // 总时限到 ⇒ 放弃（不重试）
                int n = 0;
                while (n < chunk) {
                    int r = in.read(buf, n, chunk - n);
                    if (r < 0) break;
                    n += r;
                }
                if (n <= 0) return "";
                byte[] part = (n == chunk) ? buf : java.util.Arrays.copyOf(buf, n);
                String b64 = java.util.Base64.getEncoder().encodeToString(part);
                JsonObject p = J.obj("stream_id", sid, "chunk_data", b64, "chunk_index", i,
                        "total_chunks", total, "file_size", size, "filename", nm,
                        "file_retention", STREAM_RETENTION_DEFAULT);
                JsonObject resp = callQuiet(A_STREAM_UPLOAD, p);
                if (!ok(resp) || !isStreamPacket(resp, "chunk_received", "file_created")) return "";
            }
            JsonObject done = callQuiet(A_STREAM_UPLOAD, J.obj("stream_id", sid, "is_complete", Boolean.TRUE,
                    "total_chunks", total, "file_size", size, "filename", nm,
                    "file_retention", STREAM_RETENTION_DEFAULT));
            if (!ok(done)) return "";
            JsonObject d = J.sub(done, "data");
            return d == null ? "" : Str.trim(J.s(d, "file_path", ""));
        } catch (Throwable ignored) {
            return "";                       // 附属能力：任何异常都吞成"没传成"，绝不抛给调用方
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }

    /** 流式序号（{@code stream_id} 用；进程内唯一即可）。 */
    private static final java.util.concurrent.atomic.AtomicLong STREAM_SEQ =
            new java.util.concurrent.atomic.AtomicLong(1L);

    /**
     * 这个响应是不是我们要的那一档流式包（{@code data.type}/{@code data.status} 两处任一命中即可）。
     * <p>两个字段名来自两个动作的源码：upload 用 {@code status}、download 用 {@code data_type}/{@code type}。</p>
     */
    private static boolean isStreamPacket(JsonObject resp, String a, String b) {
        JsonObject d = J.sub(resp, "data");
        if (d == null) return false;
        String t = J.s(d, "type", "");
        String s = J.s(d, "status", "");
        String dt = J.s(d, "data_type", "");
        return a.equals(t) || a.equals(s) || a.equals(dt) || b.equals(t) || b.equals(s) || b.equals(dt);
    }

    // ---- 下载：NapCat 会把**同一个 echo** 的多条包推回来，而 Link 只认第一条 ----
    //
    // 这是本节的第二个硬事实：`Link` 的口径是"一个 echo 一个响应"（pending.remove(echo) 之后
    // 后续同 echo 的帧按无人认领忽略），而 Stream 下载恰好是"一请求、多响应帧"。
    // `Link.setRawSink` 是既有的**帧级**钩子（全树只有声明、没有任何调用者，grep 可核），
    // 于是本层用它把同一 echo 的后续帧收起来 —— **不改 Link 一个字节**（Link 不在本单可改范围）。

    /** 一个下载流的收集器：按 {@code chunk_index} 落位，收满或收到 response 即完成。 */
    private static final class StreamSink implements Link.RawSink {
        final String echo;
        final java.util.TreeMap<Integer, byte[]> chunks = new java.util.TreeMap<Integer, byte[]>();
        volatile boolean seen;                 // 认出过至少一个流式包（否则这就不是 Stream 响应）
        volatile boolean done;
        volatile String error = "";
        volatile long bytes;
        volatile long expect = -1L;

        StreamSink(String echo) { this.echo = echo; }

        @Override public void onFrame(String text) {
            try {
                onObject(J.obj(text));
            } catch (Throwable ignored) {
                // 单帧坏了不影响整条流（真坏了会走超时那一条）
            }
        }

        void onObject(JsonObject o) {
            if (o == null) return;
            if (!echo.equals(J.s(o, "echo", ""))) return;
            JsonObject d = J.sub(o, "data");
            if (d == null) return;
            String t = Str.lower(Str.trim(J.s(d, "type", "")));
            String dt = Str.lower(Str.trim(J.s(d, "data_type", "")));
            if ("error".equals(t) || "reset".equals(t)) {
                error = Str.nz(J.s(o, "message", "stream error"));
                seen = true;
                done = true;
                return;
            }
            if ("file_info".equals(dt)) {
                expect = J.l(d, "file_size", -1L);
                seen = true;
                return;
            }
            if ("stream".equals(t) && "file_chunk".equals(dt)) {
                String b64 = J.s(d, "data", "");
                if (b64.isEmpty()) return;
                byte[] b = java.util.Base64.getDecoder().decode(b64);
                bytes += b.length;
                seen = true;
                if (bytes > STREAM_DOWNLOAD_MAX) {
                    error = "下载流超过内存上限";
                    done = true;
                    return;
                }
                synchronized (chunks) { chunks.put((int) J.l(d, "index", chunks.size()), b); }
                return;
            }
            if ("response".equals(t) || "file_complete".equals(dt)) {
                seen = true;
                done = true;
            }
        }
    }

    /** 在收的下载流（echo → 收集器；超过 {@link #STREAM_SLOTS} 丢最旧）。 */
    private static final java.util.LinkedHashMap<String, StreamSink> STREAM_INBOX =
            new java.util.LinkedHashMap<String, StreamSink>(8, 0.75f, false) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, StreamSink> e) {
                    return size() > STREAM_SLOTS;
                }
            };
    /** 帧级钩子是不是已经装好（只装一次）。 */
    private static volatile boolean sinkReady;

    /**
     * 装一次帧级钩子（幂等；只在第一次真下载时装）。
     *
     * <p><b>为什么钩子要"按 echo 自己建槽位"</b>：{@code Link.call} 在收到<b>第一帧</b>时就把
     * future 完成、把 {@code pending} 里的 echo 摘掉了；后续同 echo 的块帧到得比调用方拿到
     * 首帧还早是完全可能的 ⇒ 钩子必须能<b>先收着</b>，调用方之后按 echo 取（而不是反过来）。</p>
     *
     * <p><b>为什么必须先看一小段文本再解析</b>：钩子在读循环里被每条帧调用（包括所有 QQ 事件），
     * 全量 JSON 解析会给读循环加一份无谓开销 ⇒ 先用最便宜的子串把"明显不是流式包"的帧挡掉。</p>
     */
    private void ensureStreamSink() {
        if (sinkReady) return;
        synchronized (Api.class) {
            if (sinkReady) return;
            Link l = link;
            if (l == null) return;
            l.setRawSink(new Link.RawSink() {
                @Override public void onFrame(String text) {
                    try {
                        if (text == null) return;
                        if (text.indexOf("file_chunk") < 0 && text.indexOf("file_info") < 0
                                && text.indexOf("stream-action") < 0) return;
                        JsonObject o = J.obj(text);
                        if (o == null) return;
                        String echo = Str.trim(J.s(o, "echo", ""));
                        if (echo.isEmpty()) return;
                        StreamSink s;
                        synchronized (STREAM_INBOX) {
                            s = STREAM_INBOX.get(echo);
                            if (s == null) {
                                s = new StreamSink(echo);
                                STREAM_INBOX.put(echo, s);
                            }
                        }
                        s.onObject(o);
                    } catch (Throwable ignored) {
                    }
                }
            });
            sinkReady = true;
        }
    }

    /**
     * <b>流式下载一个文件到本机</b>（{@code download_file_stream}，v4.8.115+）。
     *
     * <p>NapCat 会按块把它那边的文件推回来（{@code data_type=file_chunk}，每块 base64），
     * 本层按 {@code index} 落位、收完写进 {@code outPath}。跨设备时这是唯一"拿得到字节"的路
     * （{@code get_file}/{@code get_image} 给的是<b>它那台机器</b>的本地路径）。</p>
     *
     * @param file     NapCat 侧的 file 值（路径 / URL / file:// / 带 msgId+elementId 的标识）
     * @param fileId   NapCat 侧的 file_id（{@code file} 为空时用它）
     * @param outPath  本机落点（父目录会建）
     * @return 落好的本机路径（成功）；<b>任何失败 ⇒ 空串</b>
     */
    public String streamDownloadQuiet(String file, String fileId, String outPath) {
        if (!available()) return "";
        String out = Str.trim(outPath);
        if (out.isEmpty()) return "";
        long deadline = System.currentTimeMillis() + streamTimeoutMs();
        try {
            ensureStreamSink();
            JsonObject p = new JsonObject();
            if (Str.has(Str.trim(file))) p.addProperty("file", Str.trim(file));
            if (Str.has(Str.trim(fileId))) p.addProperty("file_id", Str.trim(fileId));
            if (!p.has("file") && !p.has("file_id")) return "";
            p.addProperty("chunk_size", streamChunkBytes());
            JsonObject first = callQuiet(A_STREAM_DOWNLOAD, p);
            if (!ok(first)) return "";
            String echo = Str.trim(J.s(first, "echo", ""));
            if (echo.isEmpty()) return "";
            StreamSink s;
            boolean seeded;
            synchronized (STREAM_INBOX) {
                s = STREAM_INBOX.get(echo);          // 钩子可能已经先收着了（第一帧就是它喂的）
                seeded = s != null;
                if (s == null) {
                    s = new StreamSink(echo);
                    STREAM_INBOX.put(echo, s);
                }
            }
            final StreamSink sink = s;
            try {
                if (!seeded) sink.onObject(first);   // 钩子没收过 ⇒ 自己把首包喂一遍（否则会重复计一次）
                if (!sink.seen) return "";           // 首包就不是流式包 ⇒ 这不是 Stream 响应（回退）
                while (!sink.done && System.currentTimeMillis() < deadline) {
                    Thread.sleep(5L);
                }
            } finally {
                synchronized (STREAM_INBOX) { STREAM_INBOX.remove(echo); }
            }
            if (sink.error != null && !sink.error.isEmpty()) return "";
            if (!sink.done || sink.chunks.isEmpty()) return "";
            if (sink.expect > 0L && sink.bytes != sink.expect) return "";   // 字节数对不上 ⇒ 当没下成
            java.io.File f = new java.io.File(out);
            java.io.File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            java.io.FileOutputStream os = null;
            try {
                os = new java.io.FileOutputStream(f);
                synchronized (sink.chunks) {
                    for (byte[] b : sink.chunks.values()) os.write(b);
                }
                os.flush();
            } finally {
                if (os != null) try { os.close(); } catch (Throwable ignored) {}
            }
            return f.getPath();
        } catch (Throwable ignored) {
            return "";                       // 附属能力：任何异常都吞成"没下成"，绝不抛给调用方
        }
    }

    /**
     * <b>把本机文件发进群：先用 Stream 上传，失败回退到既有的 relay / {@code upload_group_file} 路。</b>
     *
     * <p>顺序：① {@link #streamUploadQuiet}（分块上传到 NapCat 那台机器）⇒ 拿到它那边的
     * {@code file_path}；② 用那个路径调 {@code upload_group_file}；③ ①或②任一不成
     * ⇒ 回退到 {@link #sendGroupFile}（既有的 relay / {@code file:///} 那条路，行为一个字节不变）。</p>
     *
     * @return 最后一次动作的响应（回退时就是既有那一步的响应）；<b>绝不抛异常</b>
     */
    public JsonObject streamUploadGroupFileQuiet(long groupId, String file, String name) {
        if (groupId <= 0L) return error("群号不合法");
        try {
            String remote = streamUploadQuiet(file, name);
            if (Str.has(remote)) {
                JsonObject r = call("upload_group_file",
                        J.obj("group_id", groupId, "file", remote, "name", fileName(file, name)));
                if (ok(r)) return r;
            }
        } catch (Throwable ignored) {
            // 走下面的回退
        }
        return sendGroupFile(groupId, file, name);            // 既有 relay / file:/// 路（行为不变）
    }

    /** {@link #firstHttpUrl(JsonElement, int, int[])} 的深度上限。 */
    private static final int URL_SCAN_DEPTH = 8;
    /** 同上：节点数上限（防巨大响应把扫描变成遍历炸弹）。 */
    private static final int URL_SCAN_NODES = 512;

    // ---------------- N2/D3：图片直链刷新链的**调用点**（在入站媒体事实上，见方法 javadoc） ----------------

    /**
     * 入站图片直链"还算新鲜"的窗口（毫秒，90 分钟 &lt; 上游的约 2 小时）。
     * <p>刻意<b>不做成配置键</b>：它是"要不要多打一次只读动作"的判据，不是用户可调行为。</p>
     */
    public static final long URL_FRESH_MS = 90L * 60L * 1000L;

    /** 一条入站消息最多真刷几张图（防"打锤子"：宁可有图旧着，也不许多发动作）。 */
    public static final int URL_REFRESH_PER_MSG = 1;
    /** 限流：每 60 秒最多几次真刷新（口径与阈值照 {@code qq.QuoteCache.RATE_PER_MIN = 6}）。 */
    public static final int URL_REFRESH_PER_MIN = 6;
    /** 一条消息的刷新预算（毫秒）：超过它就不再发起新的刷新动作。 */
    public static final long URL_REFRESH_BUDGET_MS = 1500L;
    /** 同一目标的抑制窗口（毫秒）：窗口内重复出现不再发动作（<b>失败的也记</b>，防同一条坏标识反复打）。 */
    public static final long URL_REFRESH_SUPPRESS_MS = 60000L;
    /** 刷新结果内存缓存的容量上限（LRU 淘汰；"只进不出"是不允许的）。 */
    public static final int URL_CACHE_MAX = 256;
    /** 直链刷新的总开关（键 {@code qqMediaUrlRefresh}，默认开；关掉 ⇒ 一个动作都不发，事实行 {@code enabled:false}）。 */
    public static final String CFG_MEDIA_URL_REFRESH = "qqMediaUrlRefresh";

    /**
     * 一条内存缓存项：刷出来的新直链 + 那一刻的时间戳。
     * <p><b>只在内存</b>：值是直链（<b>含 rkey 令牌</b>）⇒ 绝不落库、绝不落日志
     * （那正是"不许把令牌写进日志/库"这一条）。{@code url} 为空串 = 那次没刷成。</p>
     */
    private static final class UrlMemo {
        final String url;
        final long ts;
        UrlMemo(String url, long ts) { this.url = url; this.ts = ts; }
    }

    /**
     * 刷新结果的内存缓存：<b>LRU + 容量上限</b>（{@link #URL_CACHE_MAX}）、<b>TTL</b>
     * （{@link #URL_FRESH_MS}）。键是<b>文件标识</b>（{@code file}/{@code file_id}；取不到才退直链），
     * 所以复用的是"同一张图问第二次"这件事，不会跨图串台。
     */
    private static final java.util.LinkedHashMap<String, UrlMemo> URL_MEMO =
            new java.util.LinkedHashMap<String, UrlMemo>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, UrlMemo> eldest) {
                    return size() > URL_CACHE_MAX;
                }
            };
    /** 限流窗口（与 {@code QuoteCache} 同一套做法：60 秒滑窗 + 计数）。 */
    private static final Object URL_RATE_LOCK = new Object();
    private static long urlWindowStart;
    private static int urlWindowCount;

    /** 缓存查一次（含 TTL 与抑制窗口；返回 null = 可以真发动作）。 */
    private static UrlMemo memoGet(String key, long now) {
        synchronized (URL_MEMO) {
            UrlMemo m = URL_MEMO.get(key);
            if (m == null) return null;
            if (now - m.ts > URL_FRESH_MS) { URL_MEMO.remove(key); return null; }   // 过期即删（读路径自愈）
            if (Str.has(m.url)) return m;                                          // 有结果 ⇒ 复用（hit）
            return (now - m.ts <= URL_REFRESH_SUPPRESS_MS) ? m : null;             // 刚失败过 ⇒ 抑制；过窗口 ⇒ 可再试
        }
    }

    private static void memoPut(String key, String url, long now) {
        if (key == null || key.isEmpty()) return;
        synchronized (URL_MEMO) { URL_MEMO.put(key, new UrlMemo(url == null ? "" : url, now)); }
    }

    /** 60 秒滑窗限流（{@link #URL_REFRESH_PER_MIN}）：放行返回 true 并计数。 */
    private static boolean rateAllow(long now) {
        synchronized (URL_RATE_LOCK) {
            if (now - urlWindowStart > 60000L) { urlWindowStart = now; urlWindowCount = 0; }
            if (urlWindowCount >= URL_REFRESH_PER_MIN) return false;
            urlWindowCount++;
            return true;
        }
    }

    /**
     * <b>把入站媒体事实里"可能已过期"的图片直链刷一次</b>（{@link #refreshImageUrlQuiet} 的调用点）。
     *
     * <h3>为什么调用点在这里（而不是 {@code QuoteCache} / {@code MediaAttach.scan}）</h3>
     * <ol>
     *   <li><b>{@code QuoteCache} 被排除（实测的理由，不是偏好）</b>：那条链取回的是"被引消息"，
     *       它的图只经 {@code MediaRender.render} 变成标签 —— 而 {@code [图片 <file>]} 用的是
     *       {@code file}，<b>不是 {@code url}</b>（{@code MediaRender.label()} 的 image 分支）；
     *       那条链的产物（{@code text}/{@code render}）会进 kv 与 {@code grouplog.extra}。
     *       在那里刷新 = ① 刷出来的地址<b>没人用</b>；② 想让它有用就得把 URL 写进记录 ⇒
     *       等于<b>把 rkey（令牌）落库</b>，正是本单明令禁止的那件事。所以它既无效又有害。</li>
     *   <li><b>{@code MediaAttach.scan} 被排除</b>：它是本工程里唯一"真正需要 URL"的地方
     *       （{@code CtxBuild.user} → {@code Msg.userWithImages}），但它是一个<b>纯函数</b>
     *       （类注释：「无模型、无网络、无时钟、不读配置、不读库」），且唯一调用点在
     *       {@code ctx.CtxBuild}（<b>不归本单</b>）。要在那里拿 {@code Api} 只能塞一个全局钩子，
     *       还会把网络动作放到<b>回合线程</b>上（违反 M4 "回合线程 0 次网络"的硬契约）。</li>
     *   <li>⇒ 落在<b>入站媒体事实链</b>（{@code QqGateway.handleMessage} 里 {@code mediaFacts} 之后）：
     *       这就是那条 URL 的下游消费者（{@code media:} 事实串 + 挂图那一跳）的源头；
     *       <b>事实串只在本轮内存里</b>（{@code grouplog} 存的是 render、{@code dialog} 存的是正文），
     *       所以刷出来的地址<b>不落库、不落日志</b>。</li>
     * </ol>
     *
     * <h3>"只在需要拿 URL 时调"的两道判据（两条都满足才发动作）</h3>
     * <ol>
     *   <li>事实是 {@code type=image} 且 {@code url} 里<b>真的有 {@code rkey=}</b>：
     *       没有 URL（表情/本地路径）、或 URL 里没有 rkey ⇒ <b>一个动作都不发</b>
     *       （第 ②/③/④ 步都要 file/message_id，对"本来就能用的地址"发动作是纯开销）；</li>
     *   <li><b>有理由怀疑它旧</b>：二者之一 ——
     *       ① 这个 URL <b>不在本条事件自己的段里</b>（{@code Ev.mediaUrls("image")} 里找不到它）
     *          ⇒ 它是 {@code QqGateway.mediaFacts} 从入站登记簿 {@code Inbound} 里兜出来的
     *          <b>更早一次登记</b>的地址，天然可能是旧的；
     *       ② 这条消息本身<b>迟到</b>（{@code now - ev.time() > }{@link #URL_FRESH_MS}：
     *          重连补投 / 重放 / 慢链路）⇒ 它的 rkey 可能已经过期。
     *       刚收到的正常图片两条都不满足 ⇒ <b>一次动作都不发</b>（这才是"只在需要时调"）。</li>
     * </ol>
     *
     * <h3>怎么不变成"打 NapCat 的锤子"（四条硬约束，逐条落在代码里）</h3>
     * <ol>
     *   <li><b>按需 + 有上限的缓存</b>：{@link #URL_MEMO}（LRU，容量 {@link #URL_CACHE_MAX}=256，
     *       TTL {@link #URL_FRESH_MS}）按 {@code file}/{@code file_id} 复用刚刷出来的直链
     *       （命中记 {@code hit}，不再发动作）；过期即删（读路径自愈），不会只进不出。</li>
     *   <li><b>限流 + 去重</b>：① 60 秒滑窗最多 {@link #URL_REFRESH_PER_MIN}=6 次真刷新
     *       （阈值与做法照 {@code QuoteCache.RATE_PER_MIN}）；② 同一目标的抑制窗口
     *       {@link #URL_REFRESH_SUPPRESS_MS}=60 秒（<b>失败的也记</b>，防一条坏标识反复打）；
     *       ③ 一条消息最多 {@link #URL_REFRESH_PER_MSG}=1 张。被挡下的都记 {@code throttled}/{@code dup}。</li>
     *   <li><b>失败只吞不抛、不阻断</b>：本方法整体在 {@code try/catch} 里，任何异常 ⇒ 空串；
     *       单个图刷不出来 ⇒ <b>原 URL 一个字节都不改</b>（{@code kept}）；渲染/回答照旧完成。
     *       返回的那行<b>只有计数</b>（{@code images/stale/hit/refreshed/kept/dup/throttled/enabled}），
     *       一个 URL/rkey 字符都没有。</li>
     *   <li><b>不在回复主路径上长时间阻塞</b>：它是<b>入站事件线程</b>上的同步前置一步
     *       （不进回合线程、不进 {@code Loop} 的请求装配），并且有
     *       {@link #URL_REFRESH_BUDGET_MS}=1500ms 的预算闸（超过预算就不再发起新的刷新动作）。
     *       <b>如实交底</b>：单次 {@code Link.call} 的往返上限仍是 {@code napcatTimeoutMs}
     *       （默认 10000ms，Link 的既有配置，本层改不了），所以最坏阻塞 = 1 次往返
     *       （{@link #URL_REFRESH_PER_MSG}=1）；要真正异步必须改 {@code Link}（不在本单可改范围）。</li>
     * </ol>
     *
     * <h3>降级口径（写死在注释里，调用方按它决定说什么）</h3>
     * <ul>
     *   <li>刷新失败（{@link #refreshImageUrlQuiet} 返回空串）⇒ <b>原 URL 一个字节都不改</b>；</li>
     *   <li><b>约 2h 过期</b>：这就是本方法存在的理由（{@code D4.md} 第 860 行）；</li>
     *   <li><b>已撤回</b>（上游："已撤回的消息无法被再次获取或恢复"）⇒ {@code get_msg} retcode 1200
     *       ⇒ 取不回 ⇒ 保留原地址；</li>
     *   <li><b>超过约 5000 条</b>（上游："大约 5000 条消息后会因 LRU 策略而过期并被清理"）
     *       ⇒ 同样取不回 ⇒ 保留原地址；</li>
     *   <li><b>未连接</b>（{@link #available()} 为假）：一个动作都不发（记 {@code throttled}，
     *       原地址保留）。</li>
     * </ul>
     *
     * @param ev    入站事件（{@code null} ⇒ 空串）
     * @param facts {@code QqGateway.mediaFacts(ev)} 的输出（<b>就地</b>改 {@code url}；{@code null} ⇒ 空串）
     * @return 一行结构事实（形如 {@code media_url: {"images":2,"stale":1,"hit":0,"refreshed":1,"kept":0,
     *         "dup":0,"throttled":0,"enabled":true}}）；没有可疑的图 ⇒ 空串（不打行）
     */
    public String refreshFactsUrlsQuiet(Ev ev, JsonArray facts) {
        if (ev == null || facts == null) return "";
        try {
            boolean on = conf == null || conf.getBool(CFG_MEDIA_URL_REFRESH, true);
            List<String> own = ev.mediaUrls("image");                 // 本条事件自己段里的地址
            long now = System.currentTimeMillis();
            long age = 0L;
            long t = ev.time();
            if (t > 0L) age = Math.max(0L, now - t * 1000L);
            boolean late = age > URL_FRESH_MS;
            long deadline = now + URL_REFRESH_BUDGET_MS;
            int images = 0;
            int stale = 0;
            int hit = 0;
            int refreshed = 0;
            int dup = 0;
            int throttled = 0;
            int used = 0;
            for (int i = 0; i < facts.size(); i++) {
                JsonElement e = facts.get(i);
                if (e == null || !e.isJsonObject()) continue;
                JsonObject f = e.getAsJsonObject();
                if (!"image".equals(Str.lower(Str.trim(J.s(f, "type", ""))))) continue;
                images++;
                String url = Str.trim(J.s(f, "url", ""));
                if (!Str.has(url) || url.indexOf("rkey=") < 0) continue;   // 判据①
                if (!late && own.contains(url)) continue;                  // 判据②：刚到的、自己的
                stale++;
                if (!on) continue;                                        // 开关关：只记数，一个动作都不发
                if (used >= URL_REFRESH_PER_MSG) { throttled++; continue; }
                if (!available()) { throttled++; continue; }               // 未连接：不发动作
                String file = J.s(f, "file", J.s(f, "file_id", ""));
                String key = Str.has(Str.trim(file)) ? Str.trim(file) : url;
                UrlMemo m = memoGet(key, System.currentTimeMillis());
                if (m != null) {
                    if (Str.has(m.url)) { f.addProperty("url", m.url); hit++; }
                    else dup++;
                    continue;
                }
                if (System.currentTimeMillis() >= deadline) { throttled++; continue; }
                if (!rateAllow(System.currentTimeMillis())) { throttled++; continue; }
                used++;
                String fresh = refreshImageUrlQuiet(url, file, ev.messageId());
                memoPut(key, fresh, System.currentTimeMillis());        // 失败也记（空串 = 抑制窗口）
                if (Str.has(fresh)) {
                    f.addProperty("url", fresh);
                    refreshed++;
                }
            }
            if (stale == 0) return "";
            int kept = stale - refreshed - hit - dup - throttled;           // 账必须平（负数 = 逻辑错了）
            if (kept < 0) kept = 0;
            JsonObject o = new JsonObject();
            o.addProperty("images", images);
            o.addProperty("stale", stale);
            o.addProperty("hit", hit);
            o.addProperty("refreshed", refreshed);
            o.addProperty("kept", kept);
            o.addProperty("dup", dup);
            o.addProperty("throttled", throttled);
            o.addProperty("enabled", on);
            if (!on) o.addProperty("why", "off");
            return "media_url: " + J.json(o);
        } catch (Throwable ignored) {
            return "";            // 纯附属步骤：它自己绝不抛（调用方也不必为它兜异常）
        }
    }

    /** 响应的错误串；成功返回 ""。 */
    public static String error(JsonObject resp) {
        return resp == null ? "" : J.s(resp, "error", "");
    }

    private static JsonObject error(String msg) {
        return J.obj("error", msg);
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\r', ' ').replace('\n', ' ');
        return t.length() <= 120 ? t : t.substring(0, 120) + "…";
    }

    // ---------------- 出站消息台账（F5a：为"按范围批量撤回"留的 message_id 底账） ----------------

    /**
     * 台账预览的字数上限（供她/主人辨认"这条是哪句"；仍<b>不存全文</b>）。
     *
     * <p><b>★ 2026-09-28「协议标记漏出」批：60 → 300。</b>理由（真机实测，不是估的）：
     * 60 字那档把台账变成了<b>不能当证据的东西</b> —— 2026-09-28 定案时写者量到
     * {@code select max(length(preview)) from sent} = <b>61</b>（60 字 + 那个 {@code …}），
     * 于是事故那几条的正文全被截在关键位置之前：{@code sent id=3413} 只留下
     * {@code "…这翻脸比翻书还快～ <sticker o…"}（标记名刚露三个字母就断了），
     * {@code id=2361} 停在 {@code <memory op="remember" sco…}，{@code id=2866} 停在
     * {@code <at qq="2312678168"给3 的 10 的} —— <b>三起事故都得回头翻 {@code grouplog} 才认得出形状</b>。
     * 300 字覆盖本仓实测的正文主体（同批最长泄漏正文 297 字），代价是每条多存 ≤240 字
     * （台账是 SQLite 行，量级无关紧要），而"能不能当证据"是这次定案的瓶颈。</p>
     */
    private static final int PREVIEW_CHARS = 300;

    /**
     * 记一条台账（<b>可选、fire-and-forget</b>）：三个条件全满足才记 ——
     * ① 动作是 {@link #SEND_ACTIONS} 之一；② 响应成功（{@link #ok}：没有 error ⇒ status=ok 且 retcode=0）；
     * ③ {@code data.message_id > 0}（没有 id 就没什么可撤的）。
     *
     * <p><b>绝不影响发送</b>：本方法在 {@link #call} 里排在 {@code return} 之前的最后一步，
     * 只读入参、不改响应对象、不返回值；整个体包在 try/catch 里，异常只在控制台打一行 dim。
     * 台账写失败 = 少一条"可撤的消息"，而<b>不是</b>"这条消息没发出去"。</p>
     */
    private void recordSent(String action, JsonObject p, JsonObject o) {
        SentTap tap = sentTap;
        if (tap == null || !isSendAction(action)) return;
        try {
            if (!ok(o)) return;                                   // failed / retcode≠0：没真发出去
            JsonObject data = J.sub(o, "data");
            long mid = data == null ? 0L : J.l(data, "message_id", 0L);
            if (mid <= 0L) return;
            tap.sent(sessionOf(p), action, mid, previewOf(p), p);
        } catch (Throwable t) {
            Out sink = out;
            if (sink != null) sink.dim("[napcat] 出站消息台账没记上（不影响发送）：" + t);
        }
    }

    private static boolean isSendAction(String action) {
        if (action == null) return false;
        for (int i = 0; i < SEND_ACTIONS.length; i++) if (SEND_ACTIONS[i].equals(action)) return true;
        return false;
    }

    /** 会话键：{@code group:<群号>} / {@code user:<QQ>}；两个都没给就空串（台账里仍可按时间找）。 */
    private static String sessionOf(JsonObject p) {
        if (p == null) return "";
        long g = J.l(p, "group_id", 0L);
        if (g > 0L) return "group:" + g;
        long u = J.l(p, "user_id", 0L);
        if (u > 0L) return "user:" + u;
        return "";
    }

    /**
     * 出站正文 → 纯文本预览。两种形态都认：<b>CQ 串</b>（{@code [CQ:image,file=..]你好}）与
     * <b>消息段数组</b>（{@code [{"type":"text","data":{"text":"你好"}}]}）；非文本段折成占位符，
     * 抽不出任何正文就返回空串（<b>不猜、不兜底</b>）。
     */
    private static String previewOf(JsonObject p) {
        return previewCut(bodyOf(p));
    }

    /**
     * 动作正文的<b>原文拼接</b>（不截断、不压空白，供"整条就是暗号"那种<b>相等</b>判定用）：
     * 与 {@link #previewOf} 同一套读取规则（字符串形态走 {@link #stripCq}、段数组只拼
     * {@code type=text} 段的 {@code data.text}、非文本段折占位符），只差最后那道
     * {@link #PREVIEW_CHARS} 字预览
     * —— 预览是给人看的，判定不能看被截断/压白之后的残形。
     */
    private static String bodyOf(JsonObject p) {
        if (p == null) return "";
        JsonElement m = p.get("message");
        if (m == null || m.isJsonNull()) return "";
        if (m.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : m.getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject seg = e.getAsJsonObject();
                String type = J.s(seg, "type", "");
                JsonObject d = J.sub(seg, "data");
                if ("text".equals(type)) sb.append(d == null ? "" : J.s(d, "text", ""));
                else sb.append(segMark(type));
            }
            return sb.toString();
        }
        if (m.isJsonPrimitive()) return stripCq(m.getAsString());
        return "";
    }

    /** 非文本段的占位符（认不出的段名也方括号包一下，别把信息丢了）。 */
    private static String segMark(String type) {
        if ("image".equals(type)) return "[图片]";
        if ("face".equals(type)) return "[表情]";
        if ("at".equals(type)) return "[@]";
        if ("record".equals(type)) return "[语音]";
        if ("video".equals(type)) return "[视频]";
        if ("file".equals(type)) return "[文件]";
        if ("reply".equals(type)) return "[回复]";
        return Str.blank(type) ? "" : ("[" + type + "]");
    }

    /** CQ 串 → 纯文本（去掉 {@code [CQ:..]} 码，非文本码折成占位符；码没闭合就到此为止，不往里猜）。 */
    private static String stripCq(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf("[CQ:", i);
            if (a < 0) {
                sb.append(s.substring(i));
                break;
            }
            sb.append(s, i, a);
            int b = s.indexOf(']', a);
            if (b < 0) break;
            String code = s.substring(a + 4, b);
            int eq = code.indexOf(',');
            sb.append(segMark(eq < 0 ? code : code.substring(0, eq)));
            i = b + 1;
        }
        return unescape(sb.toString());
    }

    /** CQ 实体反转义（只做最常见几个；预览是给人看的，不做完整 HTML 解码）。 */
    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&#91;", "[").replace("&#93;", "]").replace("&#44;", ",");
    }

    /** 压平空白 + 截到 {@link #PREVIEW_CHARS} 字（不切断代理对，避免半个字符落库）。 */
    private static String previewCut(String s) {
        String t = Str.nz(s).replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        StringBuilder sb = new StringBuilder();
        boolean sp = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == ' ') {
                if (!sp && sb.length() > 0) sb.append(' ');
                sp = true;
            } else {
                sb.append(c);
                sp = false;
            }
        }
        String u = sb.toString().trim();
        if (u.length() <= PREVIEW_CHARS) return u;
        int cut = PREVIEW_CHARS;
        if (Character.isHighSurrogate(u.charAt(cut - 1))) cut--;
        return u.substring(0, cut) + "…";
    }

    // ---------------- 强制层：本地文件一律经中转变成本地可取的写法 ----------------

    /**
     * 判定的<b>主体</b>：与 {@link #needOp} 完全同源 —— 先问当轮绑定的 {@code Ctx.caller()}；
     * 取不到 = 她自己的自主行为（基板回复、定时推送、扩展点回调）⇒ {@code Caller.systemActor(masterQQ)}。
     */
    private sair.v4.auth.Caller subject() {
        sair.v4.auth.Caller c = sair.v4.ctx.Ctx.caller();
        if (c == null) c = sair.v4.auth.Caller.systemActor(conf == null ? 0L : conf.masterQQ());
        return c;
    }

    /**
     * 判定"当前主体能不能做这个 NapCat 动作"（改写层用的 op 判定）。
     *
     * <p>op 名 = {@code napcat.<动作名>}；主体 = {@link #subject()}。
     * 放行返回 {@code null}；拒绝返回可直接回给调用方的原文（绝不静默）。
     * 权限面没装配时按拒绝处理（fail-closed，与 NapCat 闸门同一口径）。</p>
     */
    private String needOp(String op) {
        sair.v4.auth.Auth a = auth;
        if (a == null) {
            // 装配失败：fail-closed，绝不静默放权
            return sair.v4.auth.Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + op;
        }
        return a.allow(subject(), op);
    }

    /**
     * <b>v6 File 域的路径判定</b>（规格 §7 点名的那条"完全没有路径判定"的漏斗）。
     *
     * <p>与 {@link #needOp} 是<b>两道判定，别混成一道</b>：那一道答"他能不能做这个动作"
     * （{@code napcat.<动作名>}），这一道答"这个路径本身在不在允许范围"（{@code File} 域的
     * {@code Run}/{@code Read} + 最长路径优先）。把本机文件交出去 = <b>读文件</b> ⇒
     * {@code write=false}（看 {@code Read}）；写类落点（{@code store_admin.export} 那种）用
     * {@code write=true}（看 {@code Run}）。</p>
     *
     * <p>放行返回 {@code null}；拒绝返回 {@code Acl} 的拒绝原文（自带 {@code [权限阻断] } 前缀）。
     * 权限面没装配 / 判定异常一律按拒绝处理（fail-closed，与 {@link #needOp} 同口径）。</p>
     */
    private String pathDeny(String path, boolean write) {
        sair.v4.auth.Auth a = auth;
        if (a == null) {
            return sair.v4.auth.Acl.DENY_PREFIX + "权限面没有装配（auth == null），按拒绝处理：" + path;
        }
        try {
            sair.v4.auth.Acl acl = a.acl();
            if (acl == null) {
                return sair.v4.auth.Acl.DENY_PREFIX + "权限面没有装配（acl == null），按拒绝处理：" + path;
            }
            return acl.fileDeny(subject(), path, write);
        } catch (Throwable t) {
            return sair.v4.auth.Acl.DENY_PREFIX + "路径判定异常，按拒绝处理：" + path + "（" + t + "）";
        }
    }

    /** 改写层判出的拒绝（局部传递；{@link #call} 把它作为整条动作的结果返回）。 */
    private static final class Deny {
        String text;
    }

    /**
     * 改写这条动作里的本地文件参数（唯一漏斗）。
     *
     * <p>覆盖三类形态（其余参数一律不动）：</p>
     * <ol>
     *   <li>{@code send_group_msg} / {@code send_private_msg} / {@code send_msg} 的 {@code message} ——
     *       消息段数组里的 {@code data.file}（image / record / video / file…）与 CQ 串里的 {@code file=}；</li>
     *   <li><b>任何动作</b>的 {@code file} 参数（上传类 {@code upload_*_file} 的主形态，
     *       也顺手管住别的动作里同名的文件参数）；</li>
     *   <li>{@code image} 参数（NapCat 扩展 {@code _send_group_notice} 的公告图片、{@code ocr_image} 等）。</li>
     * </ol>
     * <p>值不像本地路径（http(s) / base64:// / fileid / 相对路径）就原样留着。参数是<b>副本</b>，
     * 改它不影响调用方手里的对象。</p>
     */
    private JsonObject rewrite(String action, JsonObject params, Deny deny) {
        JsonObject p = params == null ? new JsonObject() : params.deepCopy();
        String a = Str.lower(Str.trim(action));
        if (a.isEmpty()) return p;
        for (String s : SEND_ACTIONS) {
            if (s.equals(a)) {
                rewriteMessage(a, p, deny);
                break;
            }
        }
        for (String k : FILE_KEYS) rewriteKey(a, p, k, deny);
        return p;
    }

    /** 顶层的一个文件形态参数（{@code file} / {@code image}）：像本地路径就换成中转 URL。 */    private void rewriteKey(String action, JsonObject params, String key, Deny deny) {
        String v = J.s(params, key, "");
        if (!Str.has(v)) return;
        String nv = rewriteLocalFile(action, v.trim(), deny);
        if (!nv.equals(v)) params.addProperty(key, nv);
    }

    /** {@code params.message}：消息段数组里的 {@code data.file} 与 CQ 串里的 {@code file=} 都改写。 */
    private void rewriteMessage(String action, JsonObject params, Deny deny) {
        JsonElement el = J.get(params, "message");
        if (el == null) return;
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                if (e == null || !e.isJsonObject()) continue;
                JsonObject data = J.sub(e.getAsJsonObject(), "data");
                if (data == null) continue;
                String f = J.s(data, "file", "");
                if (!Str.has(f)) continue;
                String nv = rewriteLocalFile(action, f.trim(), deny);
                if (!nv.equals(f)) data.addProperty("file", nv);
            }
            return;
        }
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
            String s = el.getAsString();
            String nv = rewriteCq(action, s, deny);
            if (!nv.equals(s)) params.addProperty("message", nv);
        }
    }

    /** CQ 串：逐个 {@code [CQ:…]} 块改写 {@code file=} 的值，块外的文字原样保留。 */
    private String rewriteCq(String action, String s, Deny deny) {
        if (Str.blank(s) || s.indexOf("[CQ:") < 0) return s;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (true) {
            int p = s.indexOf("[CQ:", i);
            if (p < 0) {
                out.append(s.substring(i));
                break;
            }
            int q = s.indexOf(']', p);
            if (q < 0) {
                out.append(s.substring(i));
                break;
            }
            out.append(s, i, p);
            out.append(rewriteCqBlock(action, s.substring(p, q + 1), deny));
            i = q + 1;
        }
        return out.toString();
    }

    /** 一个 {@code [CQ:type,file=…]} 块：只动 {@code file=} 的值（到下一个 {@code ,} 或 {@code ]} 为止）。 */
    private String rewriteCqBlock(String action, String block, Deny deny) {
        StringBuilder sb = new StringBuilder(block);
        int from = 0;
        while (true) {
            int p = sb.indexOf("file=", from);
            if (p < 0) break;
            int e = p + 5;
            while (e < sb.length() && sb.charAt(e) != ',' && sb.charAt(e) != ']') e++;
            String v = sb.substring(p + 5, e);
            String nv = rewriteLocalFile(action, v, deny);
            if (nv.equals(v)) {
                from = e;
            } else {
                sb.replace(p + 5, e, nv);
                from = p + 5 + nv.length();
            }
        }
        return sb.toString();
    }

    /**
     * 一个 file 值：像本地路径就换成中转 URL；中转没开保留原值并告警。
     * <p>不像本地路径的值（http(s) 链接、{@code base64://}、NapCat fileid、相对路径）一律不动。</p>
     */
    private String rewriteLocalFile(String action, String value, Deny deny) {
        String path = localPathOf(value);
        if (path == null) return value;
        // 把本地路径改写成可被 NapCat 取到的写法之前，先判"当前主体能不能做这个动作"（op = napcat.<动作名>）。
        // 拒绝 → 不产出 URL，把拒绝原文交给 call() 作为动作结果返回（绝不静默跳过）。
        String pd = needOp("napcat." + action);
        if (pd != null) {
            if (deny.text == null) deny.text = pd;
            return value;
        }
        // v6 File 域：这条路径<b>本身</b>在不在允许范围（与上面那道 op 判定是两件事）。
        // 先判后转：被拒时连 file:/// 回退都不产出，一个字节都不外发。
        String fd = pathDeny(path, false);
        if (fd != null) {
            if (deny.text == null) deny.text = fd;
            return value;
        }
        Relay r = relay;
        if (r == null || !r.running()) {
            warn("[relay] " + action + " 要发的本地文件没经中转（relayEnabled=false 或中转没起来）："
                    + Str.cut(value, 160) + " —— 跨机 NapCat 读不到本地路径（同机部署不受影响）");
            return value;
        }
        String u = r.urlFor(path);
        if (u == null) return value;      // 形态不合法（不该发生）：原样交给 NapCat
        return u;
    }

    /**
     * 本地路径形态判定：{@code C:\x}、{@code C:/x}、{@code /x}、{@code file:///C:/x}。
     * 其余（URL / base64 / fileid / 相对路径）返回 null = 不是本地文件，不改写。
     */
    private static String localPathOf(String value) {
        String s = Str.trim(value);
        if (s.isEmpty()) return null;
        String low = s.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://") || low.startsWith("base64://")) return null;
        if (low.startsWith("file:")) {
            String rest = s.substring(5);
            while (rest.startsWith("//")) rest = rest.substring(2);
            if (rest.length() > 2 && rest.startsWith("/") && rest.charAt(2) == ':') rest = rest.substring(1);
            if (rest.length() >= 3 && rest.charAt(1) == ':' && (rest.charAt(2) == '/' || rest.charAt(2) == '\\')) {
                return rest;
            }
            return rest.startsWith("/") ? rest : null;      // file://主机名/… 一类不管
        }
        if (s.length() >= 3 && s.charAt(1) == ':' && (s.charAt(2) == '\\' || s.charAt(2) == '/')) return s;
        if (s.startsWith("/") || s.startsWith("\\")) return s;
        return null;
    }

    private void warn(String msg) {
        try {
            if (out != null) out.warn(msg);
        } catch (Throwable ignored) {
        }
    }

    /**
     * <b>★ 2026-09-28「协议标记漏出」批：一行"剥了哪些协议标记"的痕</b>
     * （{@code rule=proto_tag name=… chars=A→B}，<b>只报名字与字符数，不含正文</b>）。
     *
     * <p>为什么是"比对"而不是"在剥离处打日志"：剥的判据只有 {@link MarkerTags#strip} 一处，
     * 而剥是<b>就地改写</b>（{@code contentJudge} / {@code contentFact} 都在改写那个参数对象）；
     * 这一支拿"改写之前的正文"再剥一遍、比一次，因此<b>不需要给判据类加任何回调</b>，
     * 也不会漏掉"同一动作里好几个叶子各剥了一枚"的情形（报的是命中名字的并集）。</p>
     */
    private void protoWarn(String where, String body) {
        try {
            if (body == null || body.isEmpty()) return;
            if (body.indexOf('<') < 0 && body.indexOf('[') < 0) return;
            String cut = MarkerTags.strip(body);
            if (cut.equals(body)) return;                       // 没剥掉任何东西 ⇒ 一个字都不打
            String f = MarkerTags.fact(body, cut);
            warn("[qq] 出站剥协议标记：" + (f == null ? "rule=proto_tag chars=" + body.length() : f)
                    + "（" + where + "）");
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 消息 ----------------

    public JsonObject sendGroupMsg(long groupId, String message) {
        return sendText(true, groupId, message);
    }

    public JsonObject sendPrivateMsg(long userId, String message) {
        return sendText(false, userId, message);
    }

    /**
     * <b>发文本的唯一出口</b>：四条口径在这里一次做掉（真机事故 2026-09-18/19、2026-09-20）——
     * ① 她写的标记先换成 CQ 码（{@code <at qq=…/>} ⇒ 真 @、{@code <quote id=…/>} ⇒ 真引用），
     *    再剥其余控制标记：**既不原样漏标记，也不把引用悄悄丢掉**；
     * ② <b>工具调用标记</b>（DSML 一族，{@link ToolMarkup}）命中 ⇒ <b>整条不发</b>，
     *    返回失败让调用方如实知道（半剥的残留比整条不发更糟）；
     * ③ <b>FIX-ECHO 补-2：自我记账头</b>（{@link SelfEcho}，任一形态、<b>任一位置</b>，含中段）
     *    命中 ⇒ <b>整条不发</b>（fail-closed）。这是"类型化文本发送"这道出口：正常回复走
     *    {@code term.Sinks} 那条管线（那里会<b>剥头再发</b>），而<b>绕过 Sinks 的直连通道</b>——
     *    控制台 {@code qq send}（{@code term.Cmd} 直接调 {@link #sendGroupMsg}/{@link #sendPrivateMsg}）
     *    与拿着 {@code h.napcat()} 自己发消息的技能/工具 —— 从这里或从
     *    {@link #call(String, JsonObject)}（补-3，动作出口）过去。两处<b>共用同一套判据</b>
     *    （{@link SelfEcho#headAt}）与<b>同一处措辞</b>（{@link #selfHeadDeny}），不是两套规则；
     *    这里<b>不做剥头</b>（剥头语义只在 {@code Sinks} 一处，免得同一条正文有两个"该发什么"的答案）。
     * ④ 纯文字口径（{@link PlainText}，主人裁的"输出严禁 Markdown"）。
     *
     * <p><b>为什么必须在这一层</b>：正常回复走 {@code term.Sinks} 那条管线，闸都在；
     * 而 {@code send} 这类工具是直接调 {@link Api} 的 —— 事故里漏出去的正是这条。</p>
     */
    /**
     * <b>会被真人读到的文本</b>—— 这张清单<b>只是文档，不是判据</b>（FIX-ECHO 补-6 之后：闸按<b>内容</b>判，
     * 与动作名无关；见 {@link #selfHeadFact}）。留在这里是因为它仍然是"哪些动作的参数会显示给别人看"
     * 的唯一一份人读清单，供日后加减动作时对照：
     *
     * <p>收录标准两条都要满足：① 参数是<b>她写的自由文本</b>（不是 id / 开关 / 枚举）；
     * ② 它会<b>显示给别人</b>（不是只她自己看得见）。</p>
     * <ul>
     *   <li>{@code send_group_msg} / {@code send_private_msg} / {@code send_msg} —— {@code message}：消息正文；</li>
     *   <li>{@code send_group_forward_msg} / {@code send_private_forward_msg} —— {@code messages}：
     *       合并转发节点数组（每个 node 的 {@code data.content} 就是别人逐条读到的正文）；</li>
     *   <li>{@code _send_group_notice} —— {@code content}：群公告正文，全群读到；</li>
     *   <li>{@code set_group_card} —— {@code card}：群名片，群成员都能看到；</li>
     *   <li>{@code set_group_name} —— {@code group_name}：群名，全群可见；</li>
     *   <li>{@code set_group_add_request} —— {@code reason}：拒绝加群/邀请的理由，申请人读到；</li>
     *   <li>{@code set_qq_profile} —— {@code nickname} / {@code personal_note}；{@code set_self_longnick} —— {@code longNick}：昵称与个性签名。</li>
     * </ul>
     *
     * <p><b>不属于这张清单</b>（没有"她写的、显示给别人看的自由文本"）：查询类（{@code get_*}）、
     * 点赞/表情回应/打卡/戳一戳、已读、撤回与删除、群管的开关与 id、上传文件的 {@code file}/{@code name}、
     * {@code set_friend_add_request.remark}（备注只她自己看得见）。
     * <b>但它们照旧会被扫</b>：补-6 的判据是"任意字符串叶子里出现自我记账头就拒" ——
     * 那个形状是<b>内部记账形状</b>，出现在任何出站参数里都没有正当理由（复核实测的
     * {@code set_group_special_title} / {@code send_forward_msg} / {@code _set_model_show}
     * 这类<b>表外动作</b>正是靠这条兜住的）。</p>
     */

    /**
     * 拒发一条"正文里带自我记账头"的动作/文本（<b>四个出口共用一处措辞</b>，见补-3 与
     * {@link #sendText} / {@link #call(String, JsonObject)} / {@link #callQuiet(String, JsonObject)}）：
     * warn 只写结构事实（判据名 / 位置 / 字符数 / 哪个出口；超出扫描预算或深度时写预算事实），
     * <b>不含正文</b>；回执给调用方一句实话（它据此知道这条没发出去）。
     */
    private JsonObject selfHeadDeny(String where, String fact) {
        warn("[qq] 出站拦截：正文出现自我记账头（" + fact + "） → 整条丢弃（" + where + "）");
        return error("这条正文里带自我记账头（" + fact + "），按纪律整条拦下、没有发出去");
    }

    /**
     * <b>SILENT-FIX（真机事故 2026-09-21 21:37）+ 贴边暗号剥离（真机事故 2026-09-28 01:20）</b>：
     * 发送类动作的<b>整条正文</b>就是协议暗号（{@code <silent>} 一类"这一轮不出声"的记号）
     * ⇒ <b>整条拒发</b>；<b>贴着正文首/尾</b>的带括号暗号 ⇒ <b>就地剥掉那个 token，再拿剥完的正文
     * 走后面几道闸</b>（{@code contentFact} / {@code contentFactAny} / {@code available()}）。
     * 真机发出去的那两条（群 {@code 543986616}、{@code sent id=1597/1598}、
     * {@code message_id=1901253020/1490762514}）正文<b>就是</b> {@code <silent>}：负一层的清洗只住在
     * 一个<b>可选技能</b>（{@code 拟人化} 的套话正则 {@code ^\s*<silent>\s*$}）里，技能一关，
     * 基板就把暗号当正文发出去了。</p>
     *
     * <p>判据是<b>相等</b>不是包含（口径与 {@code term.Sinks.gate} 那条逐字相同，判据同源）：
     * 整条正文就是暗号 ⇒ 拒；暗号夹在正常句子里（"她回了一句 {@code <silent>} 试试"）⇒ 放行，
     * 那时它是正文的一部分。判据<b>一律复用</b> {@link sair.v4.agent.Agent#silent(String)}
     * （{@code <silent>} / {@code [silent]} / {@code silent} 与 {@code 不语} 一组全在那一处，
     * 这里不写第二套词表）—— 与自我记账头那条闸同一个形状：判据在别处、这里只做决定。</p>
     *
     * <p>为什么落在"发送类动作 + 正文"上：{@code <silent>} 的语义只在"这句话要不要说出去"这一层
     * 成立，别的动作里没有这句话（{@code set_group_card} 的花名、{@code get_msg} 的查询串都不该
     * 被这条判据碰到）。所以口径是 {@link #SEND_ACTIONS} × 正文（{@code message}：字符串形态 /
     * 段数组拼起来的那串，见 {@link #bodyOf}）。位置在 {@code available()} <b>之前</b>：
     * 拦不拦由正文决定，不取决于 NapCat 当前连没连上。</p>
     *
     * <p><b>★ 2026-09-28 起"只拦不剥"这句作废（改成"整条拦 / 贴边剥"）</b>，理由是又漏了两次
     * （{@code grouplog #452484}：正文 {@code "…我照最新那条走。 <silent>"} 真发进群
     * {@code 543986616}，{@code sent_id=3280}；{@code #421563}：正文以 {@code "<silent> "} 开头，
     * 真发进群 {@code 70559059}，{@code sent_id=2594}）—— 那两条<b>整条不相等</b>，旧的相等判据
     * 放行，字面量就进了群。剥的判据仍然<b>只有一处</b>
     * （{@link sair.v4.agent.Agent#stripSilent(String)}：只剥首/尾、只剥带括号形态、
     * 裸词 {@code 不发言} 一族一律不动 —— 反例是真机 {@code #403457} 的正常中文
     * "她发不发言我哪知道呀"）：</p>
     * <ul>
     *   <li><b>字符串形态</b>：就地改 {@code message}（改的是 {@code call} 里那份 deepCopy 过的
     *       参数对象；{@code callQuiet} 本就不复制参数，它改的就是调用方自己那个对象 ——
     *       与 {@code contentFact} 的既有契约一致）；</li>
     *   <li><b>段数组形态</b>：判在**可见正文**（{@link #textJoin}，非 text 段没有文字）上，只改
     *       <b>第一个 / 最后一个非空 text 段</b>里的那个 token，改完用 {@link #textJoin} 验回
     *       "拼起来 = 整条剥"；对不上（token 跨两个 text 段那种）⇒ {@code residual=split}
     *       <b>整条拒发</b>（宁可不发，不发半句）；</li>
     *   <li>剥完什么都不剩 ⇒ 同样<b>整条拒发</b>（不会发出空条）；</li>
     *   <li>没命中 ⇒ 一个字节都不改（{@code stripSilent} 没命中时返回原对象，老行为逐字节不变）。</li>
     * </ul>
     *
     * <p>空正文不算暗号（{@code Agent.silent(null/空/空白)} 也是 true，这里先排除），
     * 所以"没给正文"的老行为一个字不变。</p>
     *
     * <p><b>非 static</b>（旧版是 static）：剥掉东西时要照"改写绝不静默"那条纪律留一行痕
     * （只报判据名 / 形态 / 字符数，<b>不含正文</b>）。</p>
     *
     * @return 命中的结构事实（给日志用，<b>不含正文</b>：判据名 + 形态 + 字符数 / 段数）；
     *         {@code null} = 放行（可能已就地剥掉贴边的暗号）
     */
    private String silentFact(String action, JsonObject params) {
        if (params == null) return null;
        if (!isSendAction(Str.trim(action))) return null;
        JsonElement m = params.get("message");
        if (m == null || m.isJsonNull()) return null;
        String form;
        if (m.isJsonArray()) form = "segments";
        else if (m.isJsonPrimitive()) form = "string";
        else return null;                                   // 认不出的形态不猜（不 fail-open：它没正文）
        String body = bodyOf(params);
        if (body.trim().isEmpty()) return null;             // 空正文不是暗号（silent 对空串也为 true）
        if (sair.v4.agent.Agent.silent(body)) {
            return "rule=silent_token form=" + form + " chars=" + body.length()
                    + ("segments".equals(form) ? " parts=" + m.getAsJsonArray().size() : "");
        }
        // ★ 2026-09-28「贴边暗号」批：相等拦不住的首/尾形态 ⇒ 就地剥掉，再往下走后面几道闸。
        if ("string".equals(form)) {
            String raw = m.getAsString();
            if (raw == null || raw.isEmpty()) return null;
            String cut = sair.v4.agent.Agent.stripSilent(raw);
            if (cut == raw) return null;                    // 没命中 ⇒ 原对象（一个字都不改）
            if (cut.trim().isEmpty()) {
                return "rule=silent_token edge=strip form=string remain=0 chars=" + raw.length();
            }
            params.addProperty("message", cut);
            warn("[qq] 出站剥暗号：发送类动作的正文首/尾贴着协议暗号（<silent> 一族）→ 剥掉那个 token 再发"
                    + "（rule=silent_token edge=strip form=string chars=" + raw.length() + "）");
            return null;
        }
        JsonArray arr = m.getAsJsonArray();
        // ★ 段数组形态判在**可见正文**（{@link #textJoin}：只有 text 段有字，非 text 段没有字）上，
        //   而不是上面那条整条相等判据用的 {@link #bodyOf}（后者把图片/表情折成 {@code [图片]} 占位符）。
        //   为什么：占位符会挡住"贴边"的形状 —— {@code [{"type":"image"…}, {"type":"text","data":{"text":"<silent>"}}]}
        //   的 bodyOf 是 {@code [图片]<silent>}（首字符是 {@code [}），整条相等与"贴边"都认不出，
        //   而用户真看到的正是那个字面量 ⇒ 这一支按可见正文判，才拦得住。
        String join = textJoin(arr);
        if (join.length() == 0) return null;                // 一个 text 段都没有 = 没有正文可判
        String cut = sair.v4.agent.Agent.stripSilent(join);
        if (cut == join) return null;                       // 没命中 ⇒ 原对象（一个字都不改）
        if (cut.trim().isEmpty()) {
            return "rule=silent_token edge=strip form=segments remain=0 chars=" + join.length();
        }
        stripSilentEdgeLeaves(arr);
        if (!cut.equals(textJoin(arr))) {
            // 逐段剥完拼起来 ≠ 整条剥（token 跨两个 text 段、或段内首尾空白被 trim 掉之后拼不回整条）
            // ⇒ fail-closed：宁可不发，不发半句。
            return "rule=silent_token edge=strip form=segments residual=split chars=" + join.length();
        }
        warn("[qq] 出站剥暗号：发送类动作的正文首/尾贴着协议暗号（<silent> 一族）→ 剥掉那个 token 再发"
                + "（rule=silent_token edge=strip form=segments chars=" + join.length() + "）");
        return null;
    }

    /** 拒发一条"整条正文就是协议暗号"的动作（{@link #silentFact}）：措辞只在这一处成型。 */
    private JsonObject silentDeny(String where, String fact) {
        warn("[qq] 出站拦截：整条正文就是协议暗号（" + fact + "） → 整条丢弃（" + where + "）");
        return error("这条正文整条就是协议暗号（" + fact + "），按纪律整条拦下、没有发出去");
    }

    // ================================================================ 内容级闸：基板事实块 + [[mood:…]] 标记族 + 出站红线

    /**
     * <b>基板提炼批（2026-09-22）· 工具发送路的内容级闸</b>（审计 §5-1 的 E 形态缺口）。
     *
     * <p>为什么必须是这一层：{@code Sinks.stage} 只在 {@code QqSink} / {@code TaskSink} 里被调
     * —— {@code 消息发送\SendSkill}、{@code 群管理}、{@code 表情包}、{@code 发图片}、
     * {@code 申请审批} 走 {@code h.napcat()} → {@link #call}，<b>完全不过 stage</b>。
     * 于是她一旦把 {@code [[mood:…]]} 或威胁词写进 {@code send} 的正文，字面量原样进群。
     * 老探针 {@code ProbeEmotion} 把这条钉成"已知缺口"（A26）—— 本轮把它变成"已覆盖"。</p>
     *
     * <p><b>2026-09-22「戳一戳批」P3 补的一类</b>：同一层再接进 G1
     * 「基板事实块写法」跳闸（{@link InternalFacts}）—— 这一类此前只有回复路
     * （{@code term.Sinks.wholeGate}）拦得住，工具路漏。判据同源、阈值同源（≥2 行），
     * 命中即整条拦下（理由 {@code rule=internal_fact}）。</p>
     *
     * <p><b>判据同源，不写第二套</b>：标记族 = {@link MoodMarks}（{@code term.Sinks.gate} 用的是同一个），
     * 红线 = {@link sair.v4.term.Redline}（词表 {@code prompts/redline.md} 热读）。</p>
     *
     * <p><b>这一支的口径保持"发送类动作 × 正文"不变</b>（与 {@link #silentFact} 同一口径：
     * {@link #SEND_ACTIONS} × {@code message}）—— 它的错文串（{@code form=string} / {@code form=segments}）
     * 被既有探针逐字钉着（{@code ProbeLeak} 的 N3-a..N3-h），所以一个字都不动。</p>
     *
     * <p><b>★ 2026-09-23「出站核心批」② 起，'任意动作的任意字符串叶子'由另一支补齐</b>：
     * {@link #contentFactAny}（在 {@link #call}/{@link #callQuiet} 里紧跟这一支之后跑）。
     * 早先"不做任意叶子"的理由（换词会改技能写出去的字节、既有断言网格要 0 差异）由那一支
     * 用<b>逐叶子判 + 就地改写</b>正面处理，并逐字段登记在收工报告里。</p>
     *
     * @return 命中的结构事实（给日志用，<b>不含正文</b>：判据名 + 形态 + 字符数）；{@code null} = 放行
     */
    private static String contentFact(String action, JsonObject params) {
        if (params == null) return null;
        if (!isSendAction(Str.trim(action))) return null;
        JsonElement m = params.get("message");
        if (m == null || m.isJsonNull()) return null;
        if (m.isJsonPrimitive()) {
            if (!m.getAsJsonPrimitive().isString()) return null;      // 认不出的形态不猜（它没正文）
            String body = m.getAsString();
            if (body == null || body.isEmpty()) return null;
            Body b = contentJudge(body, "string");
            if (b.text == null) return b.why;
            if (!b.text.equals(body)) params.addProperty("message", b.text);     // 就地改写（剥标记 / 换词）
            return null;
        }
        if (!m.isJsonArray()) return null;
        JsonArray arr = m.getAsJsonArray();
        String join0 = textJoin(arr);
        if (join0.length() == 0) return null;                         // 没有文本段 = 没有正文可判
        // ---- ⓪ 基板事实块写法（G1；2026-09-22「戳一戳批」P3 起）——按**拼起来的整条**判：
        //    与字符串形态（走 contentJudge）和 Sinks.wholeGate 是同一个判据类、同一条"至少两行"阈值。
        //    放在标记族之前，理由与 contentJudge 里那句相同（内部文字的判断不该被先剥标记掩盖）。
        String fam = InternalFacts.fact(join0);
        if (fam != null) return fam;
        // ---- ⓪-b 协议标记族（族 B 半截 ⇒ 拦下；族 A/族 C ⇒ 逐段落笔、逐字验回）
        //    位置与 L1/G1 一致：排在标记族之前（"这段是内部/协议文字"的判断不该被先剥标记掩盖）。
        String protoHalf = MarkerTags.halfFact(join0);
        if (protoHalf != null) return protoHalf + " form=segments";
        String mks = MarkerTags.strip(join0);
        if (!mks.equals(join0)) {
            if (mks.trim().isEmpty()) {
                return "rule=proto_tag form=segments chars=" + join0.length() + " remain=0";
            }
            stripMarkLeaves(arr);
            if (!mks.equals(textJoin(arr))) {
                // 逐段剥完拼起来与"整条剥"不一致（标记正好被劈在两段之间，例如
                // ["<sti","cker/>"]）⇒ fail-closed。宁可丢这一条，也不许把"半个标记 + 残句"发出去。
                return "rule=proto_tag form=segments residual=split chars=" + join0.length();
            }
        }
        // ---- ① 标记族：先按**拼起来的整条**判（与字符串形态同一判据），再逐段落笔、逐字验回
        String ms = MoodMarks.strip(join0);
        if (!ms.equals(join0)) {
            if (ms.trim().isEmpty()) {
                return "rule=mood_mark form=segments chars=" + join0.length() + " remain=0";
            }
            stripLeaves(arr);
            if (!ms.equals(textJoin(arr))) {
                // 逐段剥完拼起来与"整条剥"的结果不一致（标记正好被劈在两段之间）⇒ fail-closed。
                // 宁可丢这一条，也不许把"半个标记 + 残句"发给用户。
                return "rule=mood_mark form=segments residual=split chars=" + join0.length();
            }
        }
        // ---- ② 出站红线：同样先按整条判（可替换类目必须"逐段换完拼起来 = 整条换"才算干净）
        String join1 = textJoin(arr);
        String cat = sair.v4.term.Redline.veto(join1);
        if (cat != null) {
            return "rule=redline form=segments cat=" + cat + " chars=" + join1.length();
        }
        String rep = sair.v4.term.Redline.replace(join1);
        if (!rep.equals(join1)) {
            replaceLeaves(arr);
            if (!rep.equals(textJoin(arr))) {
                return "rule=redline form=segments residual=split chars=" + join1.length();
            }
        }
        return null;
    }

    // ================================================================ 内容级闸（②：任意动作 × 任意字符串叶子）

    /**
     * <b>② 任意动作 × 任意字符串叶子的内容级闸</b>（2026-09-23「出站核心批」②，审计 §5-1 的
     * "只覆盖 3 个发送动作 × {@code message}"缺口）。
     *
     * <p><b>为什么必须补</b>：{@link #contentFact} 只判 {@link #SEND_ACTIONS} × {@code message} ⇒
     * {@code _send_group_notice.content}（群公告，全群读到）、{@code set_group_card.card}（群名片）、
     * {@code set_group_name.group_name}（群名）、{@code set_group_add_request.reason}（拒绝理由，
     * 申请人读到）、{@code set_friend_add_request.remark}、{@code set_qq_profile.nickname} /
     * {@code personal_note}、{@code set_self_longnick.longNick}、合并转发节点的 {@code content}
     * 全部不判：她把 {@code [[mood:…]]} / 威胁词 / 事实行写进这些字段，字面量原样出站。</p>
     *
     * <p><b>纪律照 {@link #selfHeadFact}（自我记账头那条路）一套</b>：任意动作、任意字符串叶子
     * （递归：对象 / 数组 / 字符串），深度上限 {@value #HEAD_SCAN_DEPTH}、字符预算
     * {@value #HEAD_SCAN_CHARS}，<b>任一超限都拒发</b>（fail-closed：看不懂的载荷宁可整条不发）。
     * 判据<b>同源不写第二套</b>：内部字面量 = {@link InternalFacts}、标记族 = {@link MoodMarks}、
     * 红线 = {@link sair.v4.term.Redline} —— 都走同一条 {@link #contentJudge}（与
     * {@code term.Sinks.gate} 同一套：事实行 → 标记族 → 红线）。落法也是同一套：
     * 不可替换类目（威胁/自伤）⇒ 拦下；可替换类目（隐私/机密键）⇒ 就地换词；
     * 标记族 ⇒ 剥离、剥完为空 ⇒ 拦下；事实行 ⇒ 拦下（{@code rule=internal_fact}）。
     * <b>只改字符串叶子</b>：非字符串叶子（数字/布尔）、键名、动作名、结构一律不碰。</p>
     *
     * <p><b>四条例外（逐条给理由，都是"不是她要说出去的话"）</b>：</p>
     * <ol>
     *   <li><b>查询类动作</b>（{@code get_*} / {@code _get_*}，见 {@link #isReadAction}）：
     *       参数是查询条件不是正文（{@link #catalog()} 的人读清单也把查询类排除在外），
     *       换词/剥标记只会把请求改坏。
     *       <p><b>★ 如实说</b>：这条例外同时让既有绿断言 {@code ProbeLeak} N3-i 继续绿，但 N3-i 的
     *       原话是"<b>非发送类动作一律</b>不在内容级闸里（口径 = SEND_ACTIONS × message）" ——
     *       那条旧口径已被本支<b>推翻</b>（{@code set_group_card.card} / {@code _send_group_notice.content}
     *       这些<b>非发送、非查询</b>的动作现在<b>要</b>过闸）⇒ N3-i 应当按新口径重指，
     *       <b>不是</b>靠这条例外躲开；逐条交底见 {@code tmp\v6-real\w1-outbound\REPORT.md} 第 4 节。</p></li>
     *   <li><b>{@code file} / {@code image} / {@code images} 键</b>（{@link #FILE_KEYS}）：
     *       值是路径 / URL / base64（{@code images} 是 {@code send_qzone_msg} 的图片 URL 数组），
     *       不是她的话；换词会把路径/URL 改坏（中转没开时本地路径原样留着），而且那是体量最大的载荷。</li>
     *   <li><b>{@code type} 键</b>（{@link #STRUCT_KEYS}）：段类型名，不是正文（与 {@link #foldHead} 同一个例外）。</li>
     *   <li><b>协议标识 / 令牌键</b>（{@link #PROTOCOL_KEYS}）：{@code flag}（申请/审批令牌）与
     *       N2/D4 补进来的七个标识键（{@code tid} / {@code message_id} / {@code message_seq} /
     *       {@code album_id} / {@code batch_id} / {@code lloc} / {@code src_id}）—— 它们是
     *       NapCat 下发的<b>发号</b>，基板只负责原样回传。★ <b>这是复核者用对抗用例证出来的本批新引入缺陷</b>
     *       （{@code flag} 那一例）：② 之前的 {@code Api} 没有本支（{@code javap} 核过），基线只判
     *       {@code SEND_ACTIONS × message} ⇒ 真令牌形状 {@code "dd-token-9876"}（字面含机密键词
     *       {@code token}）会被<b>静默换词</b>成 {@code "dd-这类信息-9876"} ⇒ <b>那条申请带着改坏的
     *       令牌发出去</b>，功能静默损坏。⇒ 改写协议标识 = 静默功能损坏，与"她要说出去的话"无关，
     *       故列为键级例外；<b>键级的，只认键名，值里的红线词放进正文键照旧判</b>。</li>
     * </ol>
     *
     * @return 命中的结构事实（给日志用，<b>不含正文</b>：判据名 + 叶子键名 + 字符数）；{@code null} = 放行
     */
    private static String contentFactAny(String action, JsonObject params) {
        if (params == null) return null;
        if (isReadAction(Str.trim(action))) return null;              // 例外①：查询类动作
        int[] budget = new int[] { HEAD_SCAN_CHARS };
        List<String> orig = new ArrayList<String>();                  // 进闸前的正文（拼"整条视图"用）
        List<String> post = new ArrayList<String>();                  // 判完（剥标记 / 换词）之后的正文
        List<String> keys = new ArrayList<String>();                  // 与上面一一对应：叶子所在的键名
        String f = scanContent(params, 0, budget, "", orig, post, keys);
        if (f != null) return f;
        return foldContent(orig, post, keys);
    }

    /** 查询类动作（{@code get_*} / {@code _get_*}）：参数不是"要说出去的话"（见 {@link #contentFactAny} 例外①）。 */
    private static boolean isReadAction(String action) {
        if (action == null || action.isEmpty()) return false;
        String a = action.charAt(0) == '_' ? action.substring(1) : action;
        return a.startsWith("get_");
    }

    /**
     * ② 的判据本体：<b>逐字符串叶子判 + 就地改写</b>（递归 对象 / 数组；深度与字符预算双闸，
     * 超限 fail-closed）。写回落在<b>叶子自己的容器</b>上（对象按键、数组按下标）—— 非字符串叶子
     * 与结构一律不碰。
     *
     * <p>计费口径与 {@link #scanHead} 一致：每个被判的叶子按"长度 + 1"计，<b>扣减之后</b>再判超限
     * （否则"最后一段正好把预算用光"会静默放行）。被例外②③跳过的键（{@code file}/{@code image}/{@code type}）
     * <b>不计费也不判</b> —— 它们不是她的文本，本闸不因此新拒任何载荷。</p>
     *
     * @return 命中的结构事实（非 null ⇒ 整条动作拦下）；{@code null} = 放行（改写已就地落笔）
     */
    private static String scanContent(JsonElement el, int depth, int[] budget, String key,
                                      List<String> orig, List<String> post, List<String> keys) {
        if (el == null || el.isJsonNull()) return null;
        if (depth > HEAD_SCAN_DEPTH) {
            return "rule=content depth=exceeded limit=" + HEAD_SCAN_DEPTH;
        }
        if (budget[0] <= 0) {
            return "rule=content budget=exceeded limit=" + HEAD_SCAN_CHARS;
        }
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            for (int i = 0; i < a.size(); i++) {
                JsonElement e = a.get(i);
                if (e == null || e.isJsonNull()) continue;
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    if (skipLeafKey(key)) continue;
                    String t = e.getAsString();
                    budget[0] -= (t.length() + 1);
                    if (budget[0] <= 0) return "rule=content budget=exceeded limit=" + HEAD_SCAN_CHARS;
                    Body b = contentJudge(t, "leaf:" + (key == null ? "" : key));
                    if (b.text == null) return b.why;
                    orig.add(t);
                    post.add(b.text);
                    keys.add(key == null ? "" : key);
                    if (!b.text.equals(t)) a.set(i, new com.google.gson.JsonPrimitive(b.text));
                    continue;
                }
                String f = scanContent(e, depth + 1, budget, key, orig, post, keys);
                if (f != null) return f;
            }
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            List<String> ks = new ArrayList<String>(o.keySet());
            for (int i = 0; i < ks.size(); i++) {
                String k = ks.get(i);
                JsonElement e = o.get(k);
                if (e == null || e.isJsonNull()) continue;
                if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                    if (skipLeafKey(k)) continue;
                    String t = e.getAsString();
                    budget[0] -= (t.length() + 1);
                    if (budget[0] <= 0) return "rule=content budget=exceeded limit=" + HEAD_SCAN_CHARS;
                    Body b = contentJudge(t, "leaf:" + k);
                    if (b.text == null) return b.why;
                    orig.add(t);
                    post.add(b.text);
                    keys.add(k);
                    if (!b.text.equals(t)) o.addProperty(k, b.text);
                    continue;
                }
                String f = scanContent(e, depth + 1, budget, k, orig, post, keys);
                if (f != null) return f;
            }
        }
        return null;
    }

    /**
     * ② 的<b>渲染视图</b>那一半（与 {@link #foldHead} 同一个动机、同一条纪律）：判据不能只看单个叶子
     * —— 基板/ NapCat 会把多个叶子拼成一条用户读到的文本，于是"标记劈成两半"或"红线词跨两个字段"
     * 的写法每个叶子都干净。两件事在这里做：
     * <ol>
     *   <li><b>标记族</b>：整条视图剥完为空 ⇒ 拦下；整条视图剥完与"逐叶剥完拼起来"不一致
     *       （标记正好劈在两个叶子之间）⇒ fail-closed 拦下（与 {@link #contentFact} 段数组那条
     *       {@code residual=split} 同口径）。</li>
     *   <li><b>事实行</b>（同一条"至少两行"阈值）与<b>红线</b>：整条视图上再判一次；
     *       可替换类目"整条换得到、逐叶换不到" ⇒ fail-closed 拦下。</li>
     * </ol>
     * <p>预算：叶子在 {@link #scanContent} 里已按"长度 + 1"计过 ⇒ 拼出来的串同样受
     * {@value #HEAD_SCAN_CHARS} 约束，不会因为多拼一遍就放行。</p>
     */
    private static String foldContent(List<String> orig, List<String> post, List<String> keys) {
        if (orig == null || orig.isEmpty()) return null;
        StringBuilder oa = new StringBuilder();
        StringBuilder pa = new StringBuilder();
        StringBuilder sa = new StringBuilder();
        for (int i = 0; i < orig.size(); i++) {
            String t = orig.get(i) == null ? "" : orig.get(i);
            oa.append(t);
            pa.append(post.get(i) == null ? "" : post.get(i));
            sa.append(MoodMarks.strip(t));                 // 逐叶"只剥标记"的结果（供标记族那一半比对）
        }
        String o = oa.toString();
        if (o.isEmpty()) return null;
        String p = pa.toString();
        String ms = MoodMarks.strip(o);                    // 整条视图剥一遍
        if (!ms.equals(o)) {
            if (ms.trim().isEmpty()) {
                return "rule=mood_mark form=leaves remain=0 chars=" + o.length();
            }
            if (!ms.equals(sa.toString())) {
                return "rule=mood_mark form=leaves residual=split chars=" + o.length();
            }
        }
        String fam = InternalFacts.fact(o);                // 事实行：判的是"她写出来的那段原文"
        if (fam != null) return fam;
        String cat = sair.v4.term.Redline.veto(ms);
        if (cat != null) {
            return "rule=redline form=leaves cat=" + cat + " chars=" + ms.length();
        }
        String rep = sair.v4.term.Redline.replace(ms);
        if (!rep.equals(ms) && !rep.equals(p)) {
            return "rule=redline form=leaves residual=split chars=" + ms.length();
        }
        return null;
    }

    /**
     * ② 不判的字符串叶子（四条例外里的键级部分，见 {@link #contentFactAny}）：
     * {@code type}（段类型名）、{@code file}/{@code image}/{@code images}（路径 / URL / base64；{@code images}
     * 是 {@code send_qzone_msg} 的图片数组键）、<b>协议标识与令牌键</b>
     * （{@code flag} + N2/D4 的 {@code tid} / {@code message_id} / {@code message_seq} /
     * {@code album_id} / {@code batch_id} / {@code lloc} / {@code src_id}：NapCat 下发的发号，
     * 改写 = 静默功能损坏）、<b>载荷键</b>（{@link #PAYLOAD_KEYS} 的 {@code chunk_data}：
     * {@code upload_file_stream} 的 base64 分块 = 文件字节本身；只跳闸、不改写）。
     *
     * <p>键级例外是<b>按名字</b>生效的，与动作无关 ⇒ 探针用同一串
     * {@code "dd-token-9876"} 分别放进 {@code flag} 与 {@code message}/{@code reason}/{@code content}
     * 做正反对照（前者逐字不变、后者照旧处理）。</p>
     */
    private static boolean skipLeafKey(String key) {
        if (key == null || key.isEmpty()) return false;
        if (STRUCT_KEYS.contains(key)) return true;
        for (int i = 0; i < FILE_KEYS.length; i++) if (FILE_KEYS[i].equals(key)) return true;
        for (int i = 0; i < PROTOCOL_KEYS.length; i++) if (PROTOCOL_KEYS[i].equals(key)) return true;
        for (int i = 0; i < PAYLOAD_KEYS.length; i++) if (PAYLOAD_KEYS[i].equals(key)) return true;
        return false;
    }

    /** 把段数组里所有 {@code type=text} 段的正文按遍历顺序拼起来（与 {@link #bodyOf} 同一套规则，只是不带占位符）。 */
    private static String textJoin(JsonArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.size(); i++) {
            String t = textLeaf(arr.get(i));
            if (t != null) sb.append(t);
        }
        return sb.toString();
    }

    /**
     * <b>逐文本段落一笔"剥协议标记"（族 A 角度标记 + 族 C 基板方括号标注，就地改写）</b>
     * —— {@link #contentFact} 段数组那一支用，与 {@link #stripLeaves}（{@code [[mood:…]]} 那一族）
     * 同一个形状、同一个"落笔之后由调用方验回"的纪律。
     *
     * <p>只动 {@code type=text} 段的 {@code data.text}（非 text 段里没有"她写的正文"）；
     * 判据只有 {@link MarkerTags#strip} 一处。</p>
     */
    private static void stripMarkLeaves(JsonArray arr) {
        for (int i = 0; i < arr.size(); i++) {
            JsonElement seg = arr.get(i);
            String t = textLeaf(seg);
            if (t == null || t.isEmpty()) continue;
            String s = MarkerTags.strip(t);
            if (!s.equals(t)) setTextLeaf(seg, s);
        }
    }

    /** 逐文本段落一笔"剥标记"（就地改写）。 */
    private static void stripLeaves(JsonArray arr) {
        for (int i = 0; i < arr.size(); i++) {
            JsonElement seg = arr.get(i);
            String t = textLeaf(seg);
            if (t == null || t.isEmpty()) continue;
            String s = MoodMarks.strip(t);
            if (!s.equals(t)) setTextLeaf(seg, s);
        }
    }

    /**
     * <b>段数组形态的"贴边暗号"剥离（{@link #silentFact} 用，就地改写）</b>：只动<b>第一个 / 最后一个
     * 非空 text 段</b> —— 贴边的 token 只可能落在这两个段里（整条拼起来的头/尾坐标），中间的 text 段
     * 一律不碰（那正是"夹在句子里照发"那一条，见 {@code Agent.stripSilent}）。
     *
     * <p>逐轮剥（能循环剥到不再变），判据只有 {@code Agent.stripSilent} 一处。落笔之后由调用方
     * 用 {@link #textJoin} 验回"拼起来 = 整条剥"：对不上就是 token 跨了两个 text 段，调用方按
     * {@code residual=split} 整条拒发。</p>
     */
    private static void stripSilentEdgeLeaves(JsonArray arr) {
        if (arr == null) return;
        for (int round = 0; round < 8; round++) {
            int first = -1;
            int last = -1;
            for (int i = 0; i < arr.size(); i++) {
                String t = textLeaf(arr.get(i));
                if (t == null || t.isEmpty()) continue;
                if (first < 0) first = i;
                last = i;
            }
            if (first < 0) return;
            boolean changed = false;
            String ft = textLeaf(arr.get(first));
            String fs = sair.v4.agent.Agent.stripSilent(ft);
            if (fs != ft) { setTextLeaf(arr.get(first), fs); changed = true; }
            if (last != first) {
                String lt = textLeaf(arr.get(last));
                String ls = lt == null ? null : sair.v4.agent.Agent.stripSilent(lt);
                if (ls != null && ls != lt) { setTextLeaf(arr.get(last), ls); changed = true; }
            }
            if (!changed) return;
        }
    }

    /** 逐文本段落一笔"红线换词"（就地改写）。 */
    private static void replaceLeaves(JsonArray arr) {
        for (int i = 0; i < arr.size(); i++) {
            JsonElement seg = arr.get(i);
            String t = textLeaf(seg);
            if (t == null || t.isEmpty()) continue;
            String s = sair.v4.term.Redline.replace(t);
            if (!s.equals(t)) setTextLeaf(seg, s);
        }
    }

    /**
     * 一段正文的结论：{@code text == null} ⇒ 拦下（{@code why} 是结构事实）；否则 {@code text} = 该发出去的正文
     * （可能被剥掉标记行、被换过词）。
     */
    private static final class Body {
        final String text;
        final String why;
        private Body(String text, String why) { this.text = text; this.why = why; }
        static Body keep(String text) { return new Body(text, null); }
        static Body deny(String why) { return new Body(null, why); }
    }

    /**
     * <b>唯一的内容级判据</b>（{@code call} / {@code callQuiet} / {@code sendText} 共用）：
     * 先判「基板事实块写法」整条跳闸（G1），再剥 {@code [[mood:…]]} 标记族（剥完什么都不剩 ⇒ 拦），
     * 最后判出站红线（不可替换 ⇒ 拦；可替换 ⇒ 换词）。
     *
     * <p>与 {@code term.Sinks.gate} 的顺序一致（标记族 → 红线），粒度也一致（<b>一段</b>正文：
     * 标记行整行丢、命中词就地换），这样"回复路"与"工具路"对同一句话的结果相同。</p>
     *
     * <p><b>2026-09-22「戳一戳批」P3 起，这道闸多了一条判据</b>：正文写成「基板事实块的写法」
     * （{@code 键:} 紧跟结构化值 / {@code {"type":"…} 形态；全角、大小写、空白都归一化）且
     * <b>至少两行</b>各自成形状 ⇒ <b>整条拦下</b>，理由带 {@code rule=internal_fact}。
     * 判据只有 {@link InternalFacts} <b>一处</b>（与 {@code term.Sinks.wholeGate} 用的同一个类、
     * 同一个 {@link InternalFacts#fact(String)}，<b>不</b>写第二套）；位置也与那边一致 ——
     * 排在标记族<b>之前</b>（它是"这段是内部文字"的判断，不该被先剥标记掩盖）。</p>
     *
     * <p><b>为什么补这一条</b>：G1 此前只挂在 {@code Sinks.wholeGate} ⇒ 只有"回复路"
     * （{@code QqSink}/{@code TaskSink}）拦得住；她走 {@code send} 一类<b>工具</b>把事实行形状的正文
     * 发出去时（{@code h.napcat()} → {@link #call}）这道闸不生效 —— 与"红线/标记两处漏斗同源"的
     * 既有口径不一致。本批把同源判据接进工具路，两条路的结论从此一致。</p>
     */
    private static Body contentJudge(String body, String form) {
        if (body == null) return Body.keep(null);
        String fam = InternalFacts.fact(body);
        if (fam != null) return Body.deny(fam);
        // ★ 2026-09-28「协议标记漏出」批 · 族 B：**半截/畸形协议标记** ⇒ 整条拦下
        //   （真机 sent id=2866 的 `<at qq="2312678168"给3 的 10 的 14 次方…`：属性形状开了头、
        //   到结尾都没有 `>` ⇒ 老口径两条支路都匹配不到、原样落群）。判据只有
        //   qq.MarkerTags.halfFact 一处，与 term.Sinks 那两处闸同源（不写第二套）。
        String protoHalf = MarkerTags.halfFact(body);
        if (protoHalf != null) return Body.deny(protoHalf + " form=" + form);
        String cur = body;
        // ★ 族 A（角度协议标记）+ 族 C（基板方括号标注）：**剪掉标记、剩下的句子照发**
        //   （真机 sent id=3413 的 `<sticker op="send"/>`、id=2361 的 `<memory op="remember" …/>`、
        //   以及她自己可能照写的 `[提及 qq=…]`）；剥完什么都不剩 ⇒ 整条拦下。
        //   判据只有 qq.MarkerTags.strip 一处（名字 = 静态 30 名 ∪ 运行期工具名，见 Registry.add）。
        String mk = MarkerTags.strip(cur);
        if (!mk.equals(cur)) {
            if (mk.trim().isEmpty()) {
                return Body.deny("rule=proto_tag form=" + form + " chars=" + cur.length() + " remain=0");
            }
            cur = mk;
        }
        String ms = MoodMarks.strip(cur);
        if (!ms.equals(cur)) {
            if (ms.trim().isEmpty()) {
                return Body.deny("rule=mood_mark form=" + form + " chars=" + cur.length() + " remain=0");
            }
            cur = ms;
        }
        String cat = sair.v4.term.Redline.veto(cur);
        if (cat != null) {
            return Body.deny("rule=redline form=" + form + " cat=" + cat + " chars=" + cur.length());
        }
        String rep = sair.v4.term.Redline.replace(cur);
        return new Body(rep, null);
    }

    /** 一个消息段里的文本（只认 {@code type=text} 的 {@code data.text}；别的段没有"她写的正文"）。 */
    private static String textLeaf(JsonElement seg) {
        try {
            if (seg == null || !seg.isJsonObject()) return null;
            JsonObject o = seg.getAsJsonObject();
            JsonElement ty = o.get("type");
            if (ty == null || ty.isJsonNull() || !ty.isJsonPrimitive()) return null;
            if (!"text".equals(ty.getAsString())) return null;
            JsonElement d = o.get("data");
            if (d == null || !d.isJsonObject()) return null;
            JsonElement t = d.getAsJsonObject().get("text");
            if (t == null || t.isJsonNull() || !t.isJsonPrimitive()) return null;
            return t.getAsString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 就地改写一个文本段的正文（形态不对就什么都不做 —— 判据已经拦下了，这里只负责落笔）。 */
    private static void setTextLeaf(JsonElement seg, String text) {
        try {
            seg.getAsJsonObject().getAsJsonObject("data").addProperty("text", text);
        } catch (Throwable ignored) {
        }
    }

    /** 拒发一条"内容级闸命中"的动作（{@link #contentFact}）：措辞只在这一处成型。 */
    private JsonObject contentDeny(String where, String fact) {
        warn("[qq] 出站拦截：正文命中出站内容闸（" + fact + "） → 整条丢弃（" + where + "）");
        return error("这条正文命中出站内容闸（" + fact + "），按纪律整条拦下、没有发出去");
    }

    /**
     * <b>FIX-ECHO 补-6（复核证伪①之后的口径）：按内容判，不按动作名判。</b>
     *
     * <p>为什么不能再按动作名判：动作名是<b>开放字符串</b>（{@link #call} 不校验它在不在
     * {@link #catalog()} 里），而 NapCat 的动作空间比本类枚举的大 —— 复核实测
     * {@code set_group_special_title}（群头衔，全群可见）、{@code send_forward_msg}
     * （合并转发的第三个动作名）、{@code _set_model_show} 带头时**全部放行**。
     * ⇒ 判据改成：<b>对任意动作的 {@code params} 里任意字符串叶子扫自我记账头</b>
     * （递归：对象 / 数组 / 字符串；{@code message}、{@code data.text}、节点 {@code content}、
     * 甚至查询参数一律照扫）。判据仍然是 {@link SelfEcho#headAt}：fail-closed、
     * <b>只拦不剥</b>、整条动作丢弃、措辞仍只由 {@link #selfHeadDeny} 一处成型。</p>
     *
     * <p><b>资源边界（都不 fail-open）</b>：递归深度上限 {@value #HEAD_SCAN_DEPTH}
     * （复核实测"第 6 层包装 ≈ JSON 深度 13"起旧的 4 层会漏）；扫描字符预算
     * {@value #HEAD_SCAN_CHARS}。任一超限都<b>拒发</b>并留一行预算事实 ——
     * 看不懂的载荷宁可整条不发，绝不"因为太重就放行"。</p>
     *
     * @return 命中的结构事实（给日志用，<b>不含正文</b>：判据名 + 位置 + 字符数）；{@code null} = 放行
     */
    private static String selfHeadFact(String action, JsonObject params) {
        if (params == null) return null;
        int[] budget = new int[] { HEAD_SCAN_CHARS };
        java.util.List<String> leaves = new java.util.ArrayList<String>();
        java.util.List<String> keys = new java.util.ArrayList<String>();     // 与 leaves 一一对应：最近的键名
        // 第一遍：逐字符串叶子（补-6）——叶子与键名同时收下，供第二遍"渲染形态"用
        String f = scanHead(params, 0, budget, "", leaves, keys);
        if (f != null) return f;
        // 第二遍：把叶子拼回"渲染形态"再判（补-7，堵"拆分逃逸"）
        return foldHead(leaves, keys);
    }

    /**
     * 唯一的**结构性判别键**：它的值是 {@code text}/{@code node}/{@code image} 这种<b>类型名</b>，
     * 永远不是她要说的正文 —— 拼"渲染形态"时跳过它的叶子（否则每段里的 {@code type} 会把
     * 相邻的两半隔开，拼出来的不是用户真正看到的那一串）。
     * <p>注意：<b>只有这一处例外，而且只是"拼的时候跳过"</b> —— 逐叶子那遍（补-6）一个叶子都不漏，
     * 判据也不看动作名。</p>
     */
    private static final java.util.HashSet<String> STRUCT_KEYS =
            new java.util.HashSet<String>(java.util.Arrays.asList("type"));

    /**
     * <b>FIX-ECHO 补-7（第二轮复核证伪：拆分逃逸）</b>：判据不能只看单个叶子 —— 基板会把段数组
     * <b>拼成一条消息</b>（{@link #previewOf} 逐段 {@code append}、而它正是写进 {@code sent} 台账那份；
     * {@code SelfEcho.textOf} → {@code Ev.plainText()} 同一条规则：只拼 {@code type=text} 段的
     * {@code data.text}）。于是把头从<b>前缀内部</b>切开、两半各放一个叶子时（叶 1 = {@code "["}，
     * 叶 2 = {@code "我发的 msg_id=1] 你好呀"}），逐叶子扫描**两半都不含** {@code [我发的 msg_id=}
     * ⇒ 会整条放行，而用户收到的却是拼好的完整带头正文。
     *
     * <p>两遍拼法，都喂同一个 {@link SelfEcho#headAt}、都 fail-closed：</p>
     * <ol>
     *   <li><b>A：按遍历顺序把所有叶子拼起来</b>（跳过 {@link #STRUCT_KEYS} 那些类型名叶子），
     *       <b>原样拼</b>与<b>空格拼</b>各判一次 —— 覆盖"两半分到同一段数组的两个 text 段"与
     *       "两半分到两个不同参数"两种摆法；</li>
     *   <li><b>B：同名键跨容器再拼一次</b>（把所有 {@code key=data.text} 的叶子按遍历顺序拼起来）——
     *       覆盖"两半分到两个不同转发节点的 content 里"这种隔着结构键的摆法。</li>
     * </ol>
     * <p>事实里标明是哪一种拼法（{@code fold=plain} / {@code fold=space} / {@code fold=key:&lt;键名&gt;}）。
     * <b>键名本身不扫</b>（复核举不出哪个动作会把键名渲染给人看 —— 如实留作残留）。
     * 预算：叶子在第一遍已按"长度 + 1（给分隔符留一位）"计入 ⇒ 拼出来的串同样受
     * {@value #HEAD_SCAN_CHARS} 约束（超预算在第一遍就拒了），不会因为拼法多就放行。</p>
     */
    private static String foldHead(java.util.List<String> leaves, java.util.List<String> keys) {
        if (leaves == null || leaves.size() < 2) return null;     // 单叶子：第一遍已经覆盖

        // A：所有叶子（跳过类型名）按遍历顺序 —— 原样拼 / 空格拼
        StringBuilder plain = new StringBuilder();
        StringBuilder spaced = new StringBuilder();
        for (int i = 0; i < leaves.size(); i++) {
            String k = i < keys.size() ? keys.get(i) : "";
            if (STRUCT_KEYS.contains(k)) continue;
            if (spaced.length() > 0) spaced.append(' ');
            plain.append(leaves.get(i));
            spaced.append(leaves.get(i));
        }
        String hit = foldFact(plain, "fold=plain");
        if (hit != null) return hit;
        hit = foldFact(spaced, "fold=space");
        if (hit != null) return hit;

        // B：同名键跨容器（data.text 分在多个节点/参数里）
        java.util.LinkedHashMap<String, StringBuilder> byKey =
                new java.util.LinkedHashMap<String, StringBuilder>();
        for (int i = 0; i < leaves.size(); i++) {
            String k = i < keys.size() ? keys.get(i) : "";
            if (k.isEmpty() || STRUCT_KEYS.contains(k)) continue;
            StringBuilder sb = byKey.get(k);
            if (sb == null) { sb = new StringBuilder(); byKey.put(k, sb); }
            sb.append(leaves.get(i));
        }
        for (java.util.Map.Entry<String, StringBuilder> e : byKey.entrySet()) {
            hit = foldFact(e.getValue(), "fold=key:" + e.getKey());
            if (hit != null) return hit;
        }
        return null;
    }

    /** 拼出来的串喂 {@link SelfEcho#headAt}（超长串不再单独计费：叶子那遍已经计过）。 */
    private static String foldFact(StringBuilder sb, String how) {
        if (sb == null || sb.length() == 0) return null;
        int at = SelfEcho.headAt(sb.toString());
        if (at < 0) return null;
        return "rule=self_head at=" + at + " chars=" + sb.length() + " " + how;
    }

    /** 扫描预算（一次动作最多看这么多字符；超出 ⇒ fail-closed）。 */
    private static final int HEAD_SCAN_CHARS = 200000;
    /** 递归深度上限（超出 ⇒ fail-closed）。 */
    private static final int HEAD_SCAN_DEPTH = 64;

    /**
     * 递归扫一个 JSON 值里的所有字符串叶子（对象 / 数组 / 字符串；深度与字符预算双闸）。
     *
     * <p>键名不扫（键名由动作契约决定，不是她写的文本）；只有字符串<b>值</b>算正文，
     * 命中时把"最近的键名"写进事实（{@code key=data.text} 这种路径末段）好定位。
     * 超预算/超深度返回一条"预算事实"（非 null ⇒ 调用方拒发）；<b>预算是在扣减之后判的</b> ——
     * 否则"最后一段正好把预算用光"就会静默放行（探针 `(20d)` 钉的就是这个）。
     * 每个叶子都按"长度 + 1"计费（那 1 位留给第二遍可能插入的分隔符），并<b>同时收进 {@code leaves}</b>，
     * 供 {@link #foldHead} 拼出渲染形态（补-7）。</p>
     */
    private static String scanHead(JsonElement el, int depth, int[] budget, String key,
                                   java.util.List<String> leaves, java.util.List<String> keys) {
        if (el == null || el.isJsonNull()) return null;
        if (depth > HEAD_SCAN_DEPTH) {
            return "rule=self_head depth=exceeded limit=" + HEAD_SCAN_DEPTH;
        }
        if (budget[0] <= 0) {
            return "rule=self_head budget=exceeded limit=" + HEAD_SCAN_CHARS;
        }
        if (el.isJsonPrimitive()) {
            if (!el.getAsJsonPrimitive().isString()) return null;
            String t = el.getAsString();
            budget[0] -= (t.length() + 1);
            if (budget[0] <= 0) {
                return "rule=self_head budget=exceeded limit=" + HEAD_SCAN_CHARS;
            }
            leaves.add(t);                                  // 供第二遍"渲染形态"用（补-7）
            keys.add(key == null ? "" : key);
            int at = SelfEcho.headAt(t);
            if (at >= 0) {
                return "rule=self_head at=" + at + " chars=" + t.length()
                        + (key == null || key.isEmpty() ? "" : " key=" + key);
            }
            return null;
        }
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                String f = scanHead(e, depth + 1, budget, key, leaves, keys);
                if (f != null) return f;
            }
            return null;
        }
        if (el.isJsonObject()) {
            for (java.util.Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey();
                String f = scanHead(e.getValue(), depth + 1, budget, k, leaves, keys);
                if (f != null) return f;
            }
        }
        return null;
    }

    private JsonObject sendText(boolean group, long id, String message) {
        // ★ 2026-09-28「协议标记漏出」批：剥了协议标记就留一行痕（ChatMarkers.clean 里那次剥离
        //   与这一支读的是同一份判据 MarkerTags.strip；这里只负责"报出来"）。
        protoWarn("文本出口 sendText", message);
        String clean = ChatMarkers.clean(message);
        String leak = ToolMarkup.rule(clean);
        if (leak != null) {
            warn("[qq] 拦下一条工具调用标记（" + ToolMarkup.fact(clean) + "）：整条不发");
            return error("这条正文里带工具调用标记（rule=" + leak + "），按纪律整条拦下、没有发出去");
        }
        // ★ FIX-ECHO 补-2：自我记账头（任一形态：方括号 / san 中和出来的圆括号变体）——
        //   这里只拦不剥：正文里任何位置出现它都整条不发，点明判据名与位置/字符数（无正文）。
        int head = SelfEcho.headAt(clean);
        if (head >= 0) {
            return selfHeadDeny("文本出口 sendText",
                    "rule=self_head at=" + head + " chars=" + clean.length());
        }
        // ★ 基板提炼批（2026-09-22）：内容级闸（同源判据，见 contentJudge）——文本出口这一道
        //   与 call / callQuiet 那两道是同一套；剥到什么都不剩 / 命中不可替换类目 ⇒ 拦下，
        //   可替换类目 ⇒ 换词后照发。排在"空正文"那句之前：整条就是标记的那一条要报出
        //   rule=mood_mark（那是真原因），不是"这条正文是空的"。
        Body jb = contentJudge(clean, "string");
        if (jb.text == null) return contentDeny("文本出口 sendText", jb.why);
        clean = jb.text;
        if (clean.isEmpty()) return error("这条正文是空的（剥掉控制标记之后没有内容）");
        return group
                ? call("send_group_msg", J.obj("group_id", id, "message", clean))
                : call("send_private_msg", J.obj("user_id", id, "message", clean));
    }

    public JsonObject sendMsg(boolean group, long id, String message) {
        return group ? sendGroupMsg(id, message) : sendPrivateMsg(id, message);
    }

    public JsonObject sendGroupImage(long groupId, String file) {
        return call("send_group_msg", J.obj("group_id", groupId, "message", seg("send_group_msg", "image", file)));
    }

    public JsonObject sendPrivateImage(long userId, String file) {
        return call("send_private_msg", J.obj("user_id", userId, "message", seg("send_private_msg", "image", file)));
    }

    public JsonObject sendGroupRecord(long groupId, String file) {
        return call("send_group_msg", J.obj("group_id", groupId, "message", seg("send_group_msg", "record", file)));
    }

    public JsonObject sendPrivateRecord(long userId, String file) {
        return call("send_private_msg", J.obj("user_id", userId, "message", seg("send_private_msg", "record", file)));
    }

    public JsonObject sendGroupFile(long groupId, String file, String name) {
        return call("upload_group_file",
                J.obj("group_id", groupId, "file", fileParam("upload_group_file", file), "name", fileName(file, name)));
    }

    public JsonObject sendPrivateFile(long userId, String file, String name) {
        return call("upload_private_file",
                J.obj("user_id", userId, "file", fileParam("upload_private_file", file), "name", fileName(file, name)));
    }

    // ---------------- 查询 ----------------

    public JsonObject getGroupList() { return call("get_group_list", new JsonObject()); }

    public JsonObject getGroupMemberList(long groupId) {
        return call("get_group_member_list", J.obj("group_id", groupId));
    }

    public JsonObject getGroupInfo(long groupId) {
        return call("get_group_info", J.obj("group_id", groupId));
    }

    public JsonObject getFriendList() { return call("get_friend_list", new JsonObject()); }

    public JsonObject getLoginInfo() { return call("get_login_info", new JsonObject()); }

    public JsonObject getStrangerInfo(long userId) {
        return call("get_stranger_info", J.obj("user_id", userId));
    }

    public JsonObject getGroupMemberInfo(long groupId, long userId) {
        return call("get_group_member_info", J.obj("group_id", groupId, "user_id", userId));
    }

    public JsonObject getMsg(long messageId) {
        return call("get_msg", J.obj("message_id", messageId));
    }

    /** 合并转发内容：{@code message_id} 与 {@code id} 同时给，兼容不同实现取值习惯。 */
    public JsonObject getForwardMsg(String id) {
        return call("get_forward_msg", J.obj("message_id", id, "id", id));
    }

    // ---------------- 群管 ----------------

    public JsonObject setGroupBan(long groupId, long userId, int durationSec) {
        return call("set_group_ban", J.obj("group_id", groupId, "user_id", userId, "duration", durationSec));
    }

    public JsonObject setGroupWholeBan(long groupId, boolean enable) {
        return call("set_group_whole_ban", J.obj("group_id", groupId, "enable", enable));
    }

    public JsonObject setGroupAdmin(long groupId, long userId, boolean enable) {
        return call("set_group_admin", J.obj("group_id", groupId, "user_id", userId, "enable", enable));
    }

    public JsonObject setGroupCard(long groupId, long userId, String card) {
        return call("set_group_card", J.obj("group_id", groupId, "user_id", userId, "card", card == null ? "" : card));
    }

    public JsonObject setGroupName(long groupId, String name) {
        return call("set_group_name", J.obj("group_id", groupId, "group_name", name));
    }

    public JsonObject setGroupLeave(long groupId, boolean dismiss) {
        return call("set_group_leave", J.obj("group_id", groupId, "is_dismiss", dismiss));
    }

    public JsonObject setGroupKick(long groupId, long userId, boolean rejectAdd) {
        return call("set_group_kick", J.obj("group_id", groupId, "user_id", userId, "reject_add_request", rejectAdd));
    }

    // ---------------- 请求 / 好友 ----------------

    public JsonObject setFriendAddRequest(String flag, boolean approve, String remark) {
        return call("set_friend_add_request",
                J.obj("flag", flag, "approve", approve, "remark", remark == null ? "" : remark));
    }

    public JsonObject setGroupAddRequest(String flag, String subType, boolean approve, String reason) {
        return call("set_group_add_request",
                J.obj("flag", flag, "sub_type", subType, "approve", approve, "reason", reason == null ? "" : reason));
    }

    public JsonObject deleteFriend(long userId) {
        return call("delete_friend", J.obj("user_id", userId));
    }

    // ---------------- 消息管理 / 互动 ----------------

    public JsonObject deleteMsg(long messageId) {
        return call("delete_msg", J.obj("message_id", messageId));
    }

    public JsonObject markMsgRead(long messageId) {
        return call("mark_msg_as_read", J.obj("message_id", messageId));
    }

    public JsonObject sendLike(long userId, int times) {
        return call("send_like", J.obj("user_id", userId, "times", times > 0 ? times : 10));
    }

    // ---------------- 系统 ----------------

    public JsonObject getStatus() { return call("get_status", new JsonObject()); }

    public JsonObject getVersionInfo() { return call("get_version_info", new JsonObject()); }

    public JsonObject canSendImage() { return call("can_send_image", new JsonObject()); }

    public JsonObject canSendRecord() { return call("can_send_record", new JsonObject()); }

    // ---------------- 动作目录 ----------------

    /**
     * 动作目录：{@code { "<动作名>": {"desc":"…","params":{"<参数名>":"…"}}, … }}。
     * <p>给模型看的"能力形状"清单；{@code napcat} 工具据此让模型选动作、填参数。</p>
     */
    public JsonObject catalog() {
        JsonObject c = new JsonObject();

        // 消息
        c.add("send_group_msg", act("发送群消息（message 可用 CQ 码字符串或消息段数组）",
                J.obj("group_id", "群号", "message", "消息内容", "auto_escape", "是否不解析 CQ 码（可选）")));
        c.add("send_private_msg", act("发送私聊消息（message 可用 CQ 码字符串或消息段数组）",
                J.obj("user_id", "QQ 号", "message", "消息内容", "auto_escape", "是否不解析 CQ 码（可选）")));
        c.add("send_group_forward_msg", act("发送群合并转发（messages 为 node 数组）",
                J.obj("group_id", "群号", "messages", "转发节点数组 [{type:node,data:{uin,name,content}}]")));
        c.add("send_private_forward_msg", act("发送私聊合并转发（messages 为 node 数组）",
                J.obj("user_id", "QQ 号", "messages", "转发节点数组")));
        c.add("delete_msg", act("撤回消息", J.obj("message_id", "消息 ID")));
        c.add("mark_msg_as_read", act("标记消息已读", J.obj("message_id", "消息 ID")));
        c.add("get_msg", act("获取消息详情", J.obj("message_id", "消息 ID")));
        c.add("get_forward_msg", act("获取合并转发内容", J.obj("message_id", "转发消息 ID", "id", "同上（兼容字段）")));
        c.add("get_group_msg_history", act("拉取群聊历史消息",
                J.obj("group_id", "群号", "message_seq", "起始消息序号（可选）", "count", "条数")));
        c.add("get_friend_msg_history", act("拉取私聊历史消息",
                J.obj("user_id", "QQ 号", "message_seq", "起始消息序号（可选）", "count", "条数")));
        c.add("set_msg_emoji_like", act("给消息贴表情回应",
                J.obj("message_id", "消息 ID", "emoji_id", "表情 ID", "set", "true=贴上 false=取消")));

        // 媒体
        c.add("get_image", act("获取图片文件（返回本地路径）", J.obj("file", "图片 file 值")));
        c.add("get_record", act("获取语音文件",
                J.obj("file", "语音 file 值", "out_format", "输出格式 mp3/amr/wav/flac（可选）")));
        c.add("can_send_image", act("能否发图（账号风控状态）", J.obj()));
        c.add("can_send_record", act("能否发语音（账号风控状态）", J.obj()));
        c.add("ocr_image", act("图片文字识别", J.obj("image", "图片 file 值")));

        // 文件
        c.add("upload_group_file", act("上传群文件（本地绝对路径交给基板：中转开着→外链 URL，没开→file:/// URI）",
                J.obj("group_id", "群号", "file", "本地路径或 HTTP(S) URL", "name", "显示文件名", "folder", "群文件夹 ID（可选）")));
        c.add("upload_private_file", act("上传私聊文件（本地绝对路径交给基板：中转开着→外链 URL，没开→file:/// URI）",
                J.obj("user_id", "QQ 号", "file", "本地路径或 HTTP(S) URL", "name", "显示文件名")));

        // 查询
        c.add("get_group_list", act("获取群列表", J.obj()));
        c.add("get_group_info", act("获取群信息", J.obj("group_id", "群号", "no_cache", "是否不用缓存（可选）")));
        c.add("get_group_member_list", act("获取群成员列表", J.obj("group_id", "群号")));
        c.add("get_group_member_info", act("获取群成员信息",
                J.obj("group_id", "群号", "user_id", "QQ 号", "no_cache", "是否不用缓存（可选）")));
        c.add("get_friend_list", act("获取好友列表", J.obj()));
        c.add("get_stranger_info", act("获取陌生人信息", J.obj("user_id", "QQ 号", "no_cache", "是否不用缓存（可选）")));
        c.add("get_login_info", act("获取登录号信息", J.obj()));
        c.add("get_recent_contact", act("获取最近联系人", J.obj("count", "条数")));

        // 群管
        c.add("set_group_ban", act("群禁言（duration 秒，0=解除；duration=0 且 user_id=0 无意义）",
                J.obj("group_id", "群号", "user_id", "QQ 号", "duration", "禁言秒数，0 表示解除")));
        c.add("set_group_whole_ban", act("全员禁言开关", J.obj("group_id", "群号", "enable", "true=开启")));
        c.add("set_group_admin", act("设置/取消群管理", J.obj("group_id", "群号", "user_id", "QQ 号", "enable", "true=设置")));
        c.add("set_group_card", act("设置群名片（空串=清空）", J.obj("group_id", "群号", "user_id", "QQ 号", "card", "群名片")));
        c.add("set_group_name", act("设置群名", J.obj("group_id", "群号", "group_name", "新群名")));
        c.add("set_group_leave", act("退群（is_dismiss=true 且是群主时解散）", J.obj("group_id", "群号", "is_dismiss", "是否解散")));
        c.add("set_group_kick", act("踢人", J.obj("group_id", "群号", "user_id", "QQ 号", "reject_add_request", "是否拒绝再加群")));
        c.add("set_group_sign", act("群打卡", J.obj("group_id", "群号")));
        c.add("get_group_at_all_remain", act("查询 @全体成员 剩余次数", J.obj("group_id", "群号")));
        c.add("group_poke", act("群内戳一戳", J.obj("group_id", "群号", "user_id", "QQ 号")));
        c.add("_get_group_notice", act("获取群公告（NapCat 扩展）", J.obj("group_id", "群号")));
        c.add("_send_group_notice", act("发布群公告（NapCat 扩展）",
                J.obj("group_id", "群号", "content", "公告内容", "image", "公告图片（可选）")));

        // 请求 / 好友
        c.add("set_friend_add_request", act("处理加好友请求",
                J.obj("flag", "请求 flag", "approve", "true=同意", "remark", "备注")));
        c.add("set_group_add_request", act("处理加群/邀请请求",
                J.obj("flag", "请求 flag", "sub_type", "add=加群 invite=邀请", "approve", "true=同意", "reason", "拒绝理由")));
        c.add("delete_friend", act("删除好友", J.obj("user_id", "QQ 号")));
        c.add("send_like", act("给好友点赞", J.obj("user_id", "QQ 号", "times", "次数，默认 10")));
        c.add("friend_poke", act("私聊戳一戳", J.obj("user_id", "QQ 号")));

        // 账号 / 系统
        c.add("set_qq_profile", act("修改账号资料", J.obj("nickname", "昵称", "personal_note", "个性签名")));
        c.add("set_self_longnick", act("修改个性签名（NapCat 扩展）", J.obj("longNick", "签名内容")));
        c.add("get_status", act("获取运行状态（online/good）", J.obj()));
        c.add("get_version_info", act("获取实现版本信息", J.obj()));
        c.add("get_cookies", act("获取登录态 Cookies", J.obj("domain", "域名，如 qun.qq.com")));

        // ---- N2/D6：自定义表情四件（NapCat 4.18.5+ 的「系统扩展」；K1 实测 known(napcat.add_custom_face)=false）----
        // 依据：tmp/v6-real/upstream/D5.md「4.18.5：新增 5 个端点」一节（逐个字段照抄 PayloadSchema）。
        // 下表里没有标「（可选）」的参数 = 上游 required 里的那一档。
        c.add("fetch_custom_face_detail", act("获取自定义表情详情/列表（NapCat 4.18.5+）",
                J.obj("count", "获取数量（必填；上游默认 48；anyOf[number|string]）")));
        c.add("add_custom_face", act("添加自定义表情（NapCat 4.18.5+；file 必填 = 本地表情文件路径）",
                J.obj("file", "本地表情文件路径（必填）",
                        "emoji_id", "表情 ID（可选；未提供时传空字符串）",
                        "package_id", "表情包 ID（可选；未提供时传 0）",
                        "file_name", "文件名（可选；未提供时从 file 路径取 basename）",
                        "file_size", "文件大小（可选；未提供时读本地文件）",
                        "md5", "文件 MD5（可选；未提供时读本地文件计算）",
                        "is_mark_face", "是否商城表情（可选，boolean）",
                        "is_origin", "是否原图（可选，boolean）")));
        c.add("delete_custom_face", act("删除自定义表情（NapCat 4.18.5+；上游无必填项，四个键至少给一个）",
                J.obj("res_id", "资源 ID（可选；anyOf[string|array]）",
                        "id", "表情 ID（可选；anyOf[string|array]）",
                        "ids", "ID 数组（可选，array）",
                        "md5", "表情 MD5（可选；anyOf[string|array]）")));
        c.add("set_custom_face_desc", act("修改自定义表情描述（NapCat 4.18.5+；四个参数都必填）",
                J.obj("emoji_id", "表情 ID（必填）",
                        "res_id", "资源 ID（必填）",
                        "md5", "表情 MD5（必填）",
                        "desc", "新的表情描述（必填）")));

        // ---- N2/D6：群管理边缘（NapCat 4.18.0 ~ 4.18.26；K2 已实测 known(...)=false）----
        // 依据：tmp/v6-real/upstream/D5.md 各版本页（4.18.0 / 4.18.4 / 4.18.5 / 4.18.14 / 4.18.26）逐条字段。
        c.add("complete_group_todo", act("完成群待办（NapCat 4.18.0+；把指定消息对应的待办标记为已完成）",
                J.obj("group_id", "群号（必填）",
                        "message_id", "消息 ID（可选）",
                        "message_seq", "消息 Seq（可选）")));
        c.add("cancel_group_todo", act("取消群待办（NapCat 4.18.0+）",
                J.obj("group_id", "群号（必填）",
                        "message_id", "消息 ID（可选）",
                        "message_seq", "消息 Seq（可选）")));
        c.add("get_group_signed_list", act("获取群组今日打卡列表（NapCat 4.18.5+，只读）",
                J.obj("group_id", "群号（必填）")));
        c.add("set_group_member_invite_policy", act("设置群成员邀请策略（NapCat 4.18.14+）",
                J.obj("group_id", "群号（必填）",
                        "policy", "邀请策略（必填）：disabled=禁止 / require_approval=需要管理员审核 / "
                                + "no_approval=无需审核 / no_approval_under_100=群成员少于 100 人时无需审核")));
        c.add("set_group_member_permissions", act("设置群成员功能权限（NapCat 4.18.14+；未传的项目保持不变）",
                J.obj("group_id", "群号（必填）",
                        "allow_member_upload_album", "允许成员上传群相册（可选，boolean）",
                        "allow_member_temporary_session", "允许成员发起临时会话（可选，boolean）",
                        "allow_member_create_group", "允许成员发起新的群聊（可选，boolean）")));
        c.add("set_group_new_member_history_visibility", act("设置新成员历史消息可见性（NapCat 4.18.14+）",
                J.obj("group_id", "群号（必填）",
                        "visible", "新成员默认可见最近聊天记录（必填，boolean）")));
        c.add("get_group_share_link", act("获取群分享/加群链接（NapCat 4.18.26+，只读）",
                J.obj("group_id", "群号（必填）",
                        "need_short_url", "是否返回短链（可选，默认 true；false 返回完整 universal-share 链接）",
                        "src_id", "分享来源 ID（可选，默认 73）",
                        "additional_param", "附加参数（可选，默认空串）")));
        c.add("cancel_group_album_media_like", act("取消点赞群相册媒体（NapCat 4.18.4+）",
                J.obj("group_id", "群号（必填）",
                        "album_id", "相册 ID（必填）",
                        "batch_id", "上传操作批次 ID（必填）",
                        "lloc", "媒体 ID（可选；若针对整个上传操作则不填）")));
        c.add("send_qzone_msg", act("发表 QQ 空间说说（NapCat 4.18.14+；对外公开发声 —— 基板出站内容闸照判）",
                J.obj("content", "说说正文（必填）",
                        "images", "图片数组（可选；元素支持 file:// http(s):// base64://，NapCat 自动上传）",
                        "ugc_right", "查看权限（可选，默认 1）：1 所有人可见 / 4 好友可见 / 16 部分好友可见 / "
                                + "64 仅自己可见 / 128 部分好友不可见",
                        "target_uins", "权限作用的 QQ 号数组（可选；ugc_right=16/128 时用）")));
        c.add("delete_qzone_msg", act("删除 QQ 空间说说（NapCat 4.18.14+）",
                J.obj("tid", "说说 tid（必填；来自 send_qzone_msg 的返回）")));

        // ---- N2/D2：语音转写（NapCat 4.18.2+；依据 D5.md 4.18.2 一节）----
        // 为什么进目录：模型/技能想自己转写时得能点得到它。**默认权限**：不给 ALLUSER
        // （未登记 = 未授权 ⇒ 只有主人/SYSTEM 能动），基板自主路径走不带闸门的 boot.api() 不受影响。
        c.add("fetch_ptt_text", act("获取语音转文字（NapCat 4.18.2+）",
                J.obj("message_id", "消息 ID（必填；anyOf[number|string]）")));
        return c;
    }

    private static JsonObject act(String desc, JsonObject params) {
        return J.obj("desc", desc, "params", params == null ? new JsonObject() : params);
    }

    // ---------------- 参数工具 ----------------

    /** 单段消息：{@code [{"type":type,"data":{"file":file}}]}（图片/语音的本地路径经中转载定）。 */
    private JsonArray seg(String action, String type, String file) {
        JsonArray a = new JsonArray();
        a.add(J.obj("type", type, "data", J.obj("file", mediaParam(action, file))));
        return a;
    }

    /**
     * 上传类动作的 file 参数：URL / base64:// / 已是 file: 的原样透传；
     * 本地绝对路径交给 {@link Relay}（中转在跑 → 外链 URL；没跑 → NapCat 认的 {@code file:///C:/x}）；
     * 相对路径与 fileid 原样交给 NapCat。
     *
     * @param action 这个文件要跟着走的 NapCat 动作名（判定用 op = {@code napcat.<action>}）
     */
    private String fileParam(String action, String file) {
        String p = file == null ? "" : file.trim();
        if (p.isEmpty()) return p;
        // 这是"把本机文件交出去"的一跳 —— 先判"能不能做这个动作"再决定要不要转成 URL/file:///。
        // 拒绝时不产出 URL（返回原值）；真正的拒绝由 call() 里的改写层再判一次并作为动作结果返回。
        String path = localPathOf(p);
        if (path != null && needOp("napcat." + action) != null) return p;
        // v6 File 域：路径本身的判定（与 op 判定同源同序；拒绝时不产出 URL，真正的拒绝文案由
        // call() 的改写层给 —— 这里只保证"被拒的路径不会被偷偷转成外链"）。
        if (path != null && pathDeny(path, false) != null) return p;
        Relay r = relay;
        if (r == null) return legacyFileParam(p);
        return r.uploadParam(p);
    }

    /**
     * 图片 / 语音段的 file 参数：中转在跑就换成外链；没跑时<b>原样透传</b>
     * （NapCat 的 image / record 段本来就认同机本地路径，不加 {@code file:///} 是既有口径）。
     *
     * @param action 这个文件要跟着走的 NapCat 动作名（判定用 op = {@code napcat.<action>}）
     */
    private String mediaParam(String action, String file) {
        String p = file == null ? "" : file.trim();
        if (p.isEmpty()) return p;
        // 同上 —— 先判"能不能做这个动作"再决定要不要换外链。
        String path = localPathOf(p);
        if (path != null && needOp("napcat." + action) != null) return p;
        // v6 File 域：路径本身的判定（同上，拒绝时不产出外链）。
        if (path != null && pathDeny(path, false) != null) return p;
        Relay r = relay;
        return r == null ? p : r.mediaParam(p);
    }

    /** 没装配中转时的同机口径（V3 现场验证过）：本地绝对路径 → {@code file:///C:/x}。 */
    private static String legacyFileParam(String p) {
        String low = p.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")
                || low.startsWith("file:") || low.startsWith("base64://")) return p;
        boolean abs = p.startsWith("/") || (p.length() >= 2 && p.charAt(1) == ':');
        if (!abs) return p;
        String fwd = p.replace('\\', '/');
        return fwd.startsWith("/") ? "file://" + fwd : "file:///" + fwd;
    }

    /** 没给显示名时用文件名兜底。 */
    private static String fileName(String file, String name) {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        String s = file == null ? "" : file.trim();
        int i = s.indexOf('?');
        if (i >= 0) s = s.substring(0, i);
        i = s.indexOf('#');
        if (i >= 0) s = s.substring(0, i);
        s = s.replace('\\', '/');
        i = s.lastIndexOf('/');
        String base = i >= 0 ? s.substring(i + 1) : s;
        return base.trim().isEmpty() ? "file" : base.trim();
    }

    /** 目录里所有动作名（控制台/工具清单用）。 */
    public static List<String> actionNames() {
        List<String> out = new ArrayList<String>();
        for (String k : catalogAll().keySet()) out.add(k);
        return out;
    }

    /**
     * 动作目录（静态版）：装配期登记 {@code napcat.<动作>} 的 op 清单用
     * （{@code Boot.declareNapcatOps()}）。它<b>不读任何实例状态</b> —— 目录是常量表。
     */
    public static JsonObject catalogAll() { return new Api(null, null).catalog(); }
}
