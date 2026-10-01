package sair.v4.qq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型输出里的「控制标记」清理（V3 {@code util/MarkerTags} 的白名单口径）。
 *
 * <p>约定用 {@code <quote>} / {@code <split>} / {@code <at>} / {@code <reply>} 这类标签传递结构化控制信息；
 * 这些标记必须在<b>发送前</b>剥掉，否则用户看到的是一串裸标签，写进聊天记忆里也会白白烧 token。</p>
 *
 * <h3>为什么不直接 {@code replaceAll("<[^>]+>", "")}</h3>
 * <p>那是"通配剥离一切尖括号"，会连带吞掉回复里完全正常的 <code>&lt;div class="x"&gt;</code>、
 * {@code List<String>}、{@code x < 0 > y} —— 而且被吞掉的那段还会当成"干净文本"写进记忆，
 * 一次清理既改坏回复又污染记忆。所以这里<b>只剥白名单里的标签</b>。</p>
 *
 * <p>V4 的工具都是 function calling，不靠标签执行动作；这份表里的动作标签（{@code <sendimage>} 等）
 * 只是安全网：模型偶尔会照着老习惯把标签写进回复里。</p>
 *
 * <h3>★ 批 15 / 2026-09-26：收尾要看引号（老口径把带 {@code >} 的属性值截断了）</h3>
 * <p>老收尾是 {@code [^>]*>} —— <b>碰到的第一个 {@code >} 就当标签结束</b>。属性值里出现 {@code >}
 * 时（例 {@code <at qq="1" name="a>b"/>}）它只吃掉 {@code <at qq="1" name="a>}，把残渣
 * {@code b"/>} 原样发进会话；成对写法更糟：{@code <quote id="a>b">原文</quote>} 只剩 {@code b">原文}。
 * 而 {@code >} 在属性值里是合法字符（{@code name="a>b"} 很自然），不该被当成收尾。</p>
 * <p>新口径 = <b>两条支路，结构化那条优先</b>（白名单标签名<b>一个字没改</b>）：</p>
 * <ol>
 *   <li><b>① 结构完整</b>：{@code <名 属性…>}，属性只认 {@code 名=值} 这一种写法
 *       （值可写成 {@code "…"} / {@code '…'} / 裸值）；<b>引号里的 {@code >} 不算收尾</b> ——
 *       标签一直吃到<b>第一个不在引号里的 {@code >}</b>；</li>
 *   <li><b>② 老口径兜底</b>：①认不出来（半截引号、裸标签、模型乱写）就逐字退回老行为 ——
 *       <b>老行为一个字节都不丢</b>。</li>
 * </ol>
 * <p><b>为什么这样不会多剥别人的字</b>：①的属性文法里不存在"裸 {@code >}"（属性名是 ASCII 标识符，
 * 裸值不含引号与 {@code >}，带引号的值必须闭合），所以①的终点<b>只可能</b>在
 * "引号里那个 {@code >}"上比②更靠后；其余输入上①②终点逐字相同 ⇒ 产物逐字相同。
 * 换言之：<b>新旧只在"属性值里含 {@code >}"这一件事上有差别</b>。属性名限定 ASCII
 * （{@code qq}/{@code id}/{@code file}…），中文正文里的 {@code 也许 a=b} 因此不会被误当属性。</p>
 *
 * <h3>★ 2026-09-28「协议标记漏出」批：这张表就是全仓唯一的那份名单（本轮补齐三族）</h3>
 * <p><b>真机事故</b>（甲方 2026-09-28 报「又在那个群出现了标签输出」，写者逐行核过
 * {@code C:\Sair\SairFrameWork\data\AiAgent-V4\v4.db}）：{@code sent id=3413}（2026-09-28 15:17:22
 * 群 {@code 543986616}，{@code message_id=1804072462}）正文里带着 {@code <sticker op="send"/>}，
 * 后面还有基板自己那条 {@code [提及 qq=2762731756]}；同一张台账里另有
 * {@code id=2361 <memory op="remember" scope="group" …>}、
 * {@code id=2866 <at qq="2312678168"给3 的 10 的 14 次方…}（<b>属性形状开了头、到结尾都没有
 * {@code >}</b>，老口径①②都匹配不到 ⇒ 原样落群，还把后面的正文"看起来"吞进了属性）。</p>
 *
 * <p><b>根因不是"名字少写了一个"，是这份唯一的名单从 V3 起就没跟着 V4 更新</b>：表里 30 个名字
 * 全是 V3 时代的动作标签，而 V4 的她<b>把工具名当标签写</b>（{@code sticker} / {@code memory} 都是
 * V4 的<b>工具名</b>，全仓没有任何一处教过也不是任何技能实现的形状 ⇒ 她是从工具清单里学的）。
 * 所以只补几个字面量 = 下次加一个新工具又会漏。三族一起落：</p>
 * <ol>
 *   <li><b>族 A · 协议标记（角度）</b>：{@code <ident …>} / {@code <ident …/>} / {@code </ident>} /
 *       {@code <ident>…</ident>}，{@code ident = [A-Za-z][A-Za-z0-9_.:-]*}。
 *       <b>处置：把标记本身剪掉、剩下的句子照发</b>（与 {@code <split>} 这类分段标记同一处置）；
 *       @see #strip</li>
 *   <li><b>族 B · 半截/畸形标记（fail-closed）</b>：正文里有"名单里的名字 + 属性形状
 *       （{@code =} 出现了）但到这个 {@code <} 之后<b>再没有 {@code >}</b>"⇒ <b>整条不发</b>。
 *       理由与「自我记账头残缺 ⇒ 整条不发」同一条纪律：不猜截断点，也绝不把"半个标签 + 残句"
 *       当正文发出去。@see #halfFact</li>
 *   <li><b>族 C · 基板方括号标注</b>：{@code [提及 …]} / {@code [引用 …]} / {@code [图片 …]} /
 *       {@code [表情 …]} / {@code [语音 …]} / {@code [视频 …]} / {@code [文件 …]} / {@code [音乐 …]}
 *       / {@code [内联音频 …]} / {@code [折叠转发 …]} / {@code [转发节点 …]} / {@code [卡片 …]} /
 *       {@code [未知段 …]} —— 这些是 {@link MediaRender} 渲染给别人看时用的<b>基板标签</b>
 *       （线上载荷里<b>没有</b>这些字面量：NapCat 回显证明真载荷是 {@code at} 段，见
 *       {@code grouplog #456827} 与 {@code message_sent} 回显），但她读得到自己的历史 ⇒ 会照着写。
 *       与族 A 同一处置。@see #strip
 *       <b>刻意不剥</b>：{@code [来源·模型] }（{@link MediaRender#MACHINE_FMT}）—— 那是基板
 *       有意加给"机器写的正文"的来源戳，剥了会破坏"哪句话是机器写的"这条判据；
 *       {@code [我发的 msg_id=N]} 也不在这张表里（它有自己的 {@link SelfEcho} 一族）。</li>
 * </ol>
 *
 * <p><b>运行期的名字从哪来（防漂移的机制，不是第二份词表）</b>：{@link #addName} 由
 * {@code tool.Registry.add} 在<b>唯一的注册漏斗</b>上喂进来（内置工具 + 技能工具都走它）
 * ⇒ <b>新加一个工具，它的标签名自动进入剥离集合</b>，不需要人来维护表。
 * 名单里<b>不</b>并入两类名字：① 已经由 {@code qq.ToolMarkup} 拥有的工具调用结构词
 * （{@code invoke}/{@code tools}/… —— 并进来会把"整条不发"降级成"剥掉照发"，那是<b>放松</b>
 * 既有判据）；② {@code <silent>} 一族（它归 {@code agent.Agent.silent}/{@code stripSilent}，
 * 且"夹在句子中间照发"是既有钉死的行为）。两条都在 {@link #addName} 里判。
 * 名单是<b>并集快照</b>：静态 30 个 V3 名字一个字没改，运行期名字追加在后面。
 */
public final class MarkerTags {

    private MarkerTags() {
    }

    /**
     * 控制标记白名单（标签名精确匹配；允许带属性，如 {@code <split/>}、{@code <at qq="1">}）。
     * <p><b>批 15 / 本轮都一个字没加、没删</b>（30 个名字，与 V3 逐字相同）：<br>
     * {@code quote|split|at|reply|favor|br|sendimage|sendrecord|sendfile|schedule|note|searchnote|stop|editprompt|cmd|sys|eval|evaljs|download|balance|weather|skillextract|batchrename|batchconvert|readfile|readdir|findfile|web|search|remember}</p>
     * <p>V4 的名字由 {@link #addName} 在运行期并进来（见类注释"运行期的名字从哪来"）。</p>
     */
    private static final String MARKER_NAMES =
            "quote|split|at|reply|favor"
            + "|br|sendimage|sendrecord|sendfile|schedule|note|searchnote|stop|editprompt"
            + "|cmd|sys|eval|evaljs|download|balance|weather|skillextract"
            + "|batchrename|batchconvert|readfile|readdir|findfile|web|search|remember";

    /**
     * <b>V4 侧补进来的静态名字</b>（V3 那 30 个一个字没改，这一份是<b>追加</b>段的静态部分）。
     *
     * <p>只有 {@code text} 一个，依据两条（都不是"凭想象加"）：</p>
     * <ol>
     *   <li>真机 {@code grouplog #387202}（2026-09-18，群 {@code 121873503}）她的正文里有
     *       {@code …我照办。</text> < DSML parameter name="at" string="true">1284688456}
     *       —— 模型侧的成对包装标签；</li>
     *   <li><b>它不在 {@code qq.ToolMarkup} 的覆盖里</b>（实测：{@code ToolMarkup.rule("你好</text>")}
     *       返回 {@code null} —— 结构词表是 {@code invoke}/{@code parameter}/{@code tools} 一族，
     *       不含 {@code text}）⇒ 只有"DSML 外壳 + </text>"那一整条才被 ToolMarkup 拦下，
     *       <b>光秃秃一个 {@code </text>}</b> 会原样落群。这一枚是白名单自己该补的漏名，
     *       <b>不是</b>把 ToolMarkup 那一族抄过来（{@code invoke}/{@code parameter}/{@code tools}
     *       仍然一律不进这张表 —— 见 {@link #addName} 第 3 条例外）。</li>
     * </ol>
     */
    private static final String V4_MARKER_NAMES = "text";

    /**
     * ① 结构完整的标签尾部：{@code 属性*} 然后 {@code /?>}。
     * <p>属性名限定 ASCII 标识符；带引号的值允许含 {@code >}，但<b>必须闭合</b>
     * （半截引号在①②里都活不下来 ⇒ 退回②的老行为）。</p>
     */
    private static final String TAG_TAIL_STRUCTURED =
            "(?:\\s+[A-Za-z_][A-Za-z0-9_.:-]*\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>\"']+))*\\s*/?>";

    /** ② 老口径兜底（批 15 之前逐字）：碰到的第一个 {@code >} 就是终点。 */
    private static final String TAG_TAIL_LAX = "[^>]*>";

    /**
     * <b>族 C · 基板方括号标注的词头</b>（唯一产法是 {@link MediaRender}；{@link QuoteCache}
     * 的 {@code LABEL_HEADS} 是同一份"消费侧识别表"，这里只做剥除，不生成任何标签）。
     *
     * <p>词头之后<b>必须紧跟</b>空白或 {@code ]} —— 否则 {@code [图片说明]} 这种正常中文会被误吃
     * （判据与 {@code [图片]}/{@code [图片 cat.png]} 两种真实形状一致）。</p>
     */
    private static final String[] BRACKET_HEADS = {
        "[提及", "[引用", "[图片", "[表情", "[语音", "[视频", "[文件", "[音乐",
        "[内联音频", "[折叠转发", "[转发节点", "[卡片", "[未知段",
    };

    /** 标记名形状（与 {@code <ident …>} 的 ident 同一条文法）。 */
    private static final Pattern NAME_SHAPE = Pattern.compile("^[A-Za-z][A-Za-z0-9_.:-]*$");

    /** 运行期并入的标记名（唯一写者 {@code tool.Registry.add}；键是<b>归一化小写</b>）。 */
    private static final Set<String> EXTRA = ConcurrentHashMap.newKeySet();

    /** 当前判据里的全部名字（静态 + 运行期并入；小写、稳定排序）。与 {@link #MARKER_TAG_PATTERN} 同时换。 */
    private static volatile List<String> NAMES = build(new ArrayList<String>());

    /** 控制标记：白名单名 + （结构化优先 / 老口径兜底）。 */
    private static volatile Pattern MARKER_TAG_PATTERN = buildPattern(NAMES);

    /** 分段之前的剥离用（同上，但 {@link #KEEP_FOR_STAGE} 那几枚留在名单外）。 */
    private static volatile Pattern KEEP_TAG_PATTERN = buildKeepPattern(NAMES);

    // ---------------------------------------------------------------- 名单（运行期）

    /**
     * <b>把一个运行期的名字并进剥离集合</b>（调用方只有一处：{@code tool.Registry.add} ——
     * 内置工具与技能工具都走那个唯一漏斗，所以"新加一个工具"自动带上它的标签名）。
     *
     * <p><b>三类名字不收</b>（每一条都是"收进来会放松既有判据"）：</p>
     * <ol>
     *   <li>不合标记名文法的（空 / 中文名 / 带空格 —— 技能的中文工具名会被
     *       {@code hot.ToolNames} 转写成 {@code tp_<hash>}，进来的就是那个合法名）；</li>
     *   <li>已经在静态 30 名里的（重复并入会让正则变长，语义不变）；</li>
     *   <li><b>{@code qq.ToolMarkup} 拥有的结构词</b>（{@code invoke} / {@code parameter} /
     *       {@code tools} 一族）：那些名字的纪律是"<b>整条不发</b>"，若在这里先被剥掉，
     *       它们就再也到不了 {@code ToolMarkup} 那道闸 ⇒ <b>放松</b>。判据直接问
     *       {@link ToolMarkup#has}（唯一实现），不写第二份结构词表。</li>
     * </ol>
     * <p>另外 {@code silent} 一族刻意<b>不</b>收：它归 {@code agent.Agent.silent} /
     * {@code stripSilent}（批 19 刚做完整条/贴边/接缝），且"夹在句子中间照发"是既有钉死的行为
     * （{@code ProbeAgentBoundary} ⑤c、{@code ProbeRedline} N6-a 都逐字钉着）。</p>
     *
     * @return {@code true} = 这个名字现在会被剥离（新并入或本来就在）
     */
    public static synchronized boolean addName(String name) {
        String n = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        if (!NAME_SHAPE.matcher(n).matches()) return false;
        if ("silent".equals(n) || "不语".equals(n) || "不发言".equals(n)) return false;
        if (NAMES.contains(n)) return true;
        if (ToolMarkup.has("<" + n + ">") || ToolMarkup.has("</" + n + ">")) return false;
        EXTRA.add(n);
        NAMES = build(new ArrayList<String>(EXTRA));
        MARKER_TAG_PATTERN = buildPattern(NAMES);
        KEEP_TAG_PATTERN = buildKeepPattern(NAMES);
        return true;
    }

    /**
     * 当前判据里的全部标记名（静态 30 个 + 运行期并入的，小写、稳定排序）。
     * <p><b>只读</b>：给探针做"防漂移交叉校验"用 —— 把运行期注册的每个工具名喂给
     * {@link #has(String)}，谁加了工具却没进名单，门禁当场变红。</p>
     */
    public static List<String> names() {
        return NAMES;
    }

    /**
     * 静态 30 名（<b>V3 原顺序、一个字不改</b>）+ V4 静态追加名 + 运行期追加名（去重后按名排序）。
     *
     * <p>静态段保持原顺序的意义：没有运行期名字时正则里那一截与批 15 <b>逐字相同</b>
     * （本轮唯一的静态追加是 {@code |text}），追加段排在最后，并入与否只影响它自己。</p>
     */
    private static List<String> build(List<String> extra) {
        List<String> l = new ArrayList<String>(Arrays.asList(MARKER_NAMES.split("\\|")));
        for (String n : V4_MARKER_NAMES.split("\\|")) {
            if (!n.isEmpty() && !l.contains(n)) l.add(n);
        }
        List<String> add = new ArrayList<String>();
        for (int i = 0; i < extra.size(); i++) {
            String n = extra.get(i);
            if (n != null && !n.isEmpty() && !l.contains(n) && !add.contains(n)) add.add(n);
        }
        Collections.sort(add);
        l.addAll(add);
        return Collections.unmodifiableList(l);
    }

    /**
     * 名单 → 正则。名单里的静态段与批 15 的 30 名逐字同序，再加 {@code |text} 与运行期名字。
     */
    private static Pattern buildPattern(List<String> names) {
        StringBuilder alt = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (alt.length() > 0) alt.append('|');
            alt.append(names.get(i).replace(".", "\\."));       // 只有这一个正则元字符可能出现
        }
        return Pattern.compile(
                "</?(?:" + alt + ")\\b(?:"
                + TAG_TAIL_STRUCTURED + "|" + TAG_TAIL_LAX + ")",
                Pattern.CASE_INSENSITIVE);
    }

    /** 同 {@link #buildPattern}，但把 {@link #KEEP_FOR_STAGE} 那几枚排除（分段之前那一支用）。 */
    private static Pattern buildKeepPattern(List<String> names) {
        java.util.List<String> keep = Arrays.asList(KEEP_FOR_STAGE.split("\\|"));
        StringBuilder alt = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String n = names.get(i);
            if (keep.contains(n)) continue;
            if (alt.length() > 0) alt.append('|');
            alt.append(n.replace(".", "\\."));
        }
        return Pattern.compile(
                "</?(?:" + alt + ")\\b(?:"
                + TAG_TAIL_STRUCTURED + "|" + TAG_TAIL_LAX + ")",
                Pattern.CASE_INSENSITIVE);
    }

    // ---------------------------------------------------------------- 剥离

    /**
     * 剥离控制标记 + 基板方括号标注（只有 trim，不做别的改动；null → 空串）。
     *
     * <p><b>族 A（角度协议标记）</b>：命中的标签整段删掉，句子照留；<b>族 C（基板方括号标注）</b>：
     * 命中的 {@code [词头 …]} 整段删掉，句子照留。两族都不命中就<b>逐字节不变</b>
     * （返回 {@code text.trim()} —— 与加这两族之前逐字相同）。</p>
     *
     * <p><b>不含族 B</b>：半截标记要"整条不发"，那是闸的决定、不是剥离器的产出，
     * 判据见 {@link #halfFact(String)}，落点见 {@code term.Sinks} 与 {@code qq.Api} 两处闸。</p>
     */
    public static String strip(String text) {
        if (text == null) return "";
        // 快路：一个尖括号、一个方括号都没有 ⇒ 不可能命中（绝大多数消息走这条）
        if (text.indexOf('<') < 0 && text.indexOf('[') < 0) return text.trim();
        return stripCore(text).trim();
    }

    /**
     * <b>分段之后还有"主"的标记名</b>：这些名字<b>不许</b>在分段之前被剥掉 ——
     * 出站 stage 管线与好感度执行器都跑在<b>分段之后</b>，它们正等着读这些标记。
     *
     * <p>逐条给依据（不是猜的）：</p>
     * <ul>
     *   <li>{@code split} —— 它<b>就是</b>分段自己的尺子（{@code qq.Seg.byMarker}）；</li>
     *   <li>{@code at} / {@code quote} / {@code reply} —— 归技能 {@code 点名引用\AtPickStage}
     *       （出站 stage，order=20）：{@code <at qq="123"/>} ⇒ {@code [CQ:at,qq=123]}、
     *       {@code <quote id="456"/>} ⇒ {@code [CQ:reply,id=456]}。segment 之前剥掉它们 =
     *       <b>把 @ 与引用悄悄弄丢</b>（写者实测：{@code ProbeAtQuoteMarkers} 18 条当场变红，
     *       控制台那行 {@code [ext] stage=点名引用:AtPickStage chars=0}）；</li>
     *   <li>{@code favor} —— 归基板 {@link FavorTags#apply}（{@code QqSink} 里同样在分段之后执行）。</li>
     * </ul>
     * <p>残余（如实）：这几枚的名字因此仍可能被"长度硬切"劈开 —— 它们都很短
     * （{@code <at qq="1234567890"/>} 22 字符），且旧行为本来就是这样；本轮不扩大口径。</p>
     */
    private static final String KEEP_FOR_STAGE = "split|at|quote|reply|favor";

    /**
     * <b>与 {@link #strip} 同一判据，但把 {@link #KEEP_FOR_STAGE} 那几枚留下来</b>：
     * 用来做"<b>分段之前</b>先把协议标记剥掉"。
     *
     * <p><b>为什么需要它</b>：分段的尺子是"空行段落 → 长度上限"，<b>长度是硬切</b>的
     * —— 一枚写在末尾的长协议标记会被正好切在里面。真机 {@code sent id=2361}
     * （{@code dialog id=5214}，182 字正文 + 群聊上限 180）就是形状：
     * 前半片 {@code …<memory … tags="群规,陈欣欣,}、后半片 {@code @" />}
     * —— 两片各自都<b>不再满足标记形状</b> ⇒ 逐条那道闸一个都认不出，后半片原样落群。
     * 所以"剥"必须发生在<b>分段之前</b>；而 stage 还要用的那几枚（见 {@link #KEEP_FOR_STAGE}）
     * 必须活到分段之后。</p>
     *
     * <p><b>不变量</b>：一个字都没剥掉时返回<b>原对象</b>（{@code ==} 同一个引用）
     * ⇒ {@code term.Sinks.wholeGate} 那条"零改动 = 零字节差"的契约照旧。</p>
     */
    public static String stripKeepSplit(String text) {
        if (text == null) return "";
        if (text.indexOf('<') < 0 && text.indexOf('[') < 0) return text;
        String s = text;
        if (s.indexOf('<') >= 0) s = KEEP_TAG_PATTERN.matcher(s).replaceAll("");
        s = stripBrackets(s);
        return s.equals(text) ? text : s;
    }

    /** 剥离本体（不 trim；{@code strip} 与 {@code stripKeepSplit} 共用这一支）。 */
    private static String stripCore(String text) {
        String s = text;
        if (s.indexOf('<') >= 0) s = MARKER_TAG_PATTERN.matcher(s).replaceAll("");
        s = stripBrackets(s);
        return s;
    }

    /** 含有<b>角度</b>控制标记吗（不想改文本、只想判断时用；语义与加族 C 之前逐字相同）。 */
    public static boolean has(String text) {
        if (text == null || text.indexOf('<') < 0) return false;
        return MARKER_TAG_PATTERN.matcher(text).find();
    }

    /**
     * <b>族 B 的判据本体</b>：正文里有没有"名单里的名字 + 属性形状开了头、到结尾都没有收尾
     * {@code >}"（真机 {@code sent id=2866} 的 {@code <at qq="2312678168"给3 的 10 的 14 次方…}）。
     *
     * <p>为什么必须 fail-closed：老口径①（结构完整）认不出、②（{@code [^>]*>}）也匹配不到
     * ⇒ 那一串会<b>原样落群</b>，还把后面的正文"看起来"吞进了属性。不猜截断点、也不做"正则删字符后照发"
     * —— 纪律与「自我记账头残缺 ⇒ 整条不发」逐字同条。</p>
     *
     * <p><b>只对名单里的名字判</b>（{@code <div class="x"} 这种普通标签一个字都不动）；
     * 判据落在<b>已经过 {@link #strip} 的文本</b>上也成立：能剥的早剥了，剩下的才是残形。</p>
     *
     * @return 命中的结构事实（给日志用，<b>不含正文</b>）；{@code null} = 没有半截标记
     */
    public static String halfFact(String text) {
        if (text == null || text.indexOf('<') < 0) return null;
        int n = text.length();
        for (int i = text.indexOf('<'); i >= 0; i = text.indexOf('<', i + 1)) {
            int j = i + 1;
            if (j < n && text.charAt(j) == '/') j++;
            int s = j;
            while (j < n && isNameChar(text.charAt(j))) j++;
            if (j == s) continue;                                  // "<" 后面不是名字（"a < b" / "< 5"）
            if (!isNameStart(text.charAt(s))) continue;
            String name = text.substring(s, j).toLowerCase(java.util.Locale.ROOT);
            if (!NAMES.contains(name)) continue;                    // 只对名单里的名字判
            if (j >= n || !isSpace(text.charAt(j))) continue;       // 名字之后必须紧接空白（"<atq" 不是）
            if (text.indexOf('>', i) >= 0) continue;                // 有收尾 ⇒ 不是半截
            if (text.indexOf('=', j) < 0) continue;                 // 没有属性形状 ⇒ 不是"属性开了头"
            return "rule=proto_tag residual=half name=" + name + " chars=" + n;
        }
        return null;
    }

    /**
     * 一行结构事实：命中的标记名（去重、稳定排序）+ 字符数（<b>不含正文</b>），
     * 给"改写绝不静默"那条纪律用。没命中返回 {@code null}。
     *
     * <p>列的名字从<b>原文</b>取（不是从剥完的文本取），所以事实行讲的是"她写了什么形状"。</p>
     */
    public static String fact(String before, String after) {
        if (before == null || before.isEmpty()) return null;
        List<String> hit = new ArrayList<String>();
        if (before.indexOf('<') >= 0) {
            Matcher m = MARKER_TAG_PATTERN.matcher(before);
            while (m.find()) {
                String t = m.group();
                int a = 0;
                if (t.startsWith("</")) a = 2;
                else if (t.startsWith("<")) a = 1;
                int b = a;
                while (b < t.length() && isNameChar(t.charAt(b))) b++;
                if (b > a) {
                    String nm = t.substring(a, b).toLowerCase(java.util.Locale.ROOT);
                    if (!hit.contains(nm)) hit.add(nm);
                }
            }
        }
        if (before.indexOf('[') >= 0) {
            for (int i = 0; i < BRACKET_HEADS.length; i++) {
                if (before.indexOf(BRACKET_HEADS[i]) >= 0 && !hit.contains(BRACKET_HEADS[i])) {
                    hit.add(BRACKET_HEADS[i]);
                }
            }
        }
        if (hit.isEmpty()) return null;
        Collections.sort(hit);
        return "rule=proto_tag name=" + join(hit, ",") + " chars=" + before.length()
                + "→" + (after == null ? 0 : after.length());
    }

    /** 一行结构事实（自己剥一遍）。 */
    public static String fact(String text) {
        return fact(text, strip(text));
    }

    // ---------------------------------------------------------------- 族 C 的实现

    /**
     * 剥掉方括号标注（{@code [提及 qq=1]} 这类）—— 命中的整段删掉，其余逐字保留。
     * <p><b>不闭合的</b>（有词头却没有 {@code ]}）<b>一律不动</b>：与"不猜截断点"同一条纪律
     * （猜错就会把整句正常正文吃掉），如实记在残余里。</p>
     */
    private static String stripBrackets(String s) {
        if (s == null || s.indexOf('[') < 0) return s;
        StringBuilder sb = null;
        int i = 0;
        while (i < s.length()) {
            int h = bracketHead(s, i);
            if (h < 0) break;
            int close = s.indexOf(']', h);
            if (close < 0) break;                       // 没闭合：不猜
            if (sb == null) sb = new StringBuilder(s.length());
            sb.append(s, i, h);
            i = close + 1;
        }
        if (sb == null) return s;                       // 一个字都没动 ⇒ 同一个对象
        sb.append(s, i, s.length());
        return sb.toString();
    }

    /** 从 {@code from} 起找第一个"基板方括号标注"的词头下标；没有返回 {@code -1}。 */
    private static int bracketHead(String s, int from) {
        for (int i = Math.max(0, from); i < s.length(); i++) {
            if (s.charAt(i) != '[') continue;
            for (int k = 0; k < BRACKET_HEADS.length; k++) {
                String h = BRACKET_HEADS[k];
                if (!s.startsWith(h, i)) continue;
                int j = i + h.length();
                // 词头之后必须紧跟空白或 ']' —— 否则 [图片说明] 这种正常中文会被误吃
                char c = j < s.length() ? s.charAt(j) : ']';
                if (c == ']' || c == ' ' || c == '\u3000' || c == '\t') return i;
            }
        }
        return -1;
    }

    private static boolean isNameStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == ':' || c == '-';
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\u3000' || c == '\n' || c == '\r';
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        return sb.toString();
    }
}
