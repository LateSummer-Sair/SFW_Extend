package sair.v4.qq;

import sair.v4.kit.Str;

/**
 * <b>{@code [[mood:…]]} 自报标记族 —— 基板侧的唯一实现（这是协议，不是词表）</b>。
 *
 * <p>这个类<strong>不持有任何可配置的"词"</strong>：它认的是<b>形状</b>（{@code [[} / {@code [} +
 * {@code mood} + 半角冒号 + 标记名），而形状是<b>基板与模型之间的约定</b>（提示词每轮都在教她写
 * {@code [[mood:做不到]]} 这一行），不是可以随某个技能的开关生死的业务数据。所以它住在
 * {@code src} 里、不读任何 {@code prompt.md}。标记名（{@code 做不到} / {@code 为难} / {@code 违规} /
 * {@code 转述}）的含义仍归 {@code 情绪} 技能读 —— 基板只负责"这一行不许成为正文"。</p>
 *
 * <h3>为什么必须住基板（真机缺口，2026-09-22 审计 §5-1）</h3>
 * <p>这套剥离原先<b>只有</b>一个实现，住在<b>可选技能</b> {@code data\skills\情绪\MoodStage} 里。
 * 于是四种形态必现外泄：技能移走 / 编译失败 / {@code config.json: extEnabled=false}（整条 stage 管线不跑）/
 * 工具发送路（{@code send} 一类动作完全不过 stage）—— 群里会出现字面量 {@code [[mood:做不到]]}。
 * 基板原先 {@code grep '[[mood'} 零命中，一个兜底都没有。</p>
 *
 * <h3>口径：{@link #start} / {@link #tag} 逐字搬自 {@code MoodStage}；{@link #strip} <b>本轮改过语义</b></h3>
 * <p>{@link #start} / {@link #tag} 与 {@code data\skills\情绪\MoodStage.java} 里那一份<b>逐字相同</b>，
 * 一行语义都没改（识别面因此一个字节都没扩大）。{@link #strip} 在本轮
 * （2026-09-22「内部文字外泄防线批」）被改成<b>止语点</b>语义 —— 见下一条的「旧 → 新」。</p>
 * <ul>
 *   <li>{@link #start}：认 {@code [[mood:} / {@code [mood:}（中括号一个两个都行）、{@code MOOD}
 *       任意大小写；冒号必须<b>紧跟</b> {@code mood}。★ <b>2026-09-23「出站核心批」起多了归一化（③）</b>：
 *       <b>全角方括号</b> {@code ［］} 与 <b>全角冒号</b> {@code ：} 与半角同判，
 *       <b>零宽字符</b>（{@code U+200B/200C/200D/FEFF}）在形状里一律视为可忽略。
 *       ⇒ 旧 → 新（逐条，只更严，识别面只扩不缩）：
 *       <ul>
 *         <li><b>旧</b>：{@code ［［mood:x］］} / <code>[[mo&#8203;od:x]]</code> <b>不认</b> ⇒
 *             标记原样当正文发进群（字面量外泄）。</li>
 *         <li><b>新</b>：两者都认（返回的下标仍指<b>原文</b>里那个左方括号的位置，
 *             所以"保留标记之前的文字"这套坐标语义一个字没变）。</li>
 *         <li><b>不变的</b>：{@code [mood :x]}（{@code mood} 与冒号之间夹<b>普通空格</b>）照旧
 *             <b>不认</b> —— 空格不是零宽字符；{@code moodx} / {@code moo} 这类拼写也不认。</li>
 *       </ul></li>
 *   <li>{@link #tag}：冒号后到 {@code ]} / {@code ］} 之间那段，去掉收尾括号 / 半角空格 / 制表符 /
 *       全角空格 / 零宽字符（归一化后比较标记名，{@code hasRelay} 因此认
 *       {@code [[mood:转述&#8203;]]}）。</li>
 *   <li>{@link #strip}：<b>按行</b>处理。语义在 2026-09-22「内部文字外泄防线批」改成
 *       <b>{@code [[mood:…]]} 标记 = 止语点</b>（旧 → 新见下）：
 *       <ul>
 *         <li><b>旧（本轮之前）</b>：标记行<b>整行丢弃</b>、保留标记<b>之前</b>的文字，
 *             <b>但标记之后的其余行照旧保留</b> —— 逐行扫过去，不因标记而停下。
 *             夹在行中时丢的是"该行标记之后的文字"（<b>只丢这一行的尾巴</b>），下一行接着写。</li>
 *         <li><b>新（本轮起）</b>：标记所在行<b>连同它之后的全部正文一起丢弃</b>，
 *             只保留标记<b>之前</b>的文字。⇒ 两处差别：① 行中标记不再是"丢该行尾巴"，
 *             而是"丢标记之后的<b>全部</b>（含后面所有行）"；② 标记单独成行且后面还有正文时，
 *             旧口径会把后面的正文发出去，新口径一个字都不发。</li>
 *         <li>为什么：真机 2026-09-22 09:06 那一轮，她先写了该说的那句、<b>接着</b>写
 *             {@code [[mood:违规]]}、<b>再</b>接 2082 字的自述推理。她已经用这枚标记划出了
 *             "该说的到这儿为止"，而旧口径只把标记行当垃圾行剥掉、没有把它的<b>边界</b>用起来
 *             ⇒ 整段独白被切成 14 条发进真群。新口径下同一轮只剩她那句正确的回答（14 → 1）。
 *             实测：全库 67 条带标记的 {@code assistant} 行里 <b>66 条标记本就在最后一段</b>
 *             （其后只有空行）⇒ 历史误伤 0，唯一差别就是事故那 1 条。</li>
 *         <li>尾部连续空行压到一个；<b>标记不存在</b>时逐字返回原对象（{@code ==} 可判"一个字都没动"）；
 *             截断的残行（{@code [[mood:做不到} 没有 {@code ]}）同样按标记行处理（含其后的全部丢弃）。</li>
 *       </ul>
 *       <b>★ 已知副作用</b>（既有行为，本轮刻意不动）：{@code 这事我[[mood:做不到]]做不到。}
 *       出站只剩 {@code 这事我} —— 可能截句。是否改成"只丢标记、保留两侧文字"，由 GM 下一轮裁定。</li>
 * </ul>
 *
 * <h3>两处调用者（同一份判据，不写第二套）</h3>
 * <ol>
 *   <li>{@code term.Sinks.gate(part)}：<b>逐条</b>（分段之后）—— 技能关掉 / 卸掉 / 总闸关掉 /
 *       无 caller 落点时的那道兜底；</li>
 *   <li>{@code qq.Api} 的内容级闸（{@code call} / {@code callQuiet} / {@code sendText}）：
 *       <b>工具发送路</b>（{@code send} 一类动作不过 stage）。</li>
 * </ol>
 * <p>{@code 情绪\MoodStage.transform} 也改成调这里（它自己那份实现已删除）⇒
 * 全树只有这一处判据。注意：{@link #strip} <b>不</b>放在"分段之前"的整条口
 * （{@code Sinks.wholeGate}）—— 那会让 {@code MoodStage} 看不到 {@code [[mood:转述]]}
 * ⇒ 转述轮又会错误 @ 人（用户可见回归）。</p>
 *
 * <h3>{@code [[mood:转述]]} 的语义<b>不</b>在这里</h3>
 * <p>"写了转述 ⇒ 本轮一个字都不 @、不拆句"仍然归 {@code 情绪}（它要看情绪态与场合）。
 * 本类只提供识别（{@link #hasRelay}）与那枚只对同一次发送有效的短 TTL 标记
 * （{@link #relay} / {@link #takeRelay}，30 秒、进程内、取完即清）—— 那是<b>协议记号的生命周期</b>，
 * 与"标记长什么样"同属一处；怎么用（@ 不 @）仍是技能的决定。</p>
 */
public final class MoodMarks {

    private MoodMarks() {}

    /** 「转述」标记名（<b>归一化后</b>比较的那个词）。 */
    public static final String RELAY_TAG = "转述";

    /** 那枚短 TTL 标记的有效期（毫秒）：只服务"同一次发送"（逐条 transform 与整批 finish 在同一批里跑完）。 */
    private static final long RELAY_TTL_MS = 30000L;
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> RELAY =
            new java.util.concurrent.ConcurrentHashMap<String, Long>();

    /**
     * 她该写的<b>规范写法</b>（提示词与夹具引用它）—— 与 {@link #RELAY_TAG} 的关系是
     * "同一个标记的两种表示"；识别一律走 {@link #tag} 的归一化，不做字符串相等判断。
     */
    public static String mark() { return "[[mood:" + RELAY_TAG + "]]"; }

    // ================================================================ 形状（归一化匹配）

    /**
     * 归一化：这一行里第一个 {@code mood} 标记的<b>起始下标</b>；没有返回 {@code -1}。
     *
     * <p>认得：{@code [[mood:}} / {@code [mood:}（中括号一个两个都行）、{@code MOOD} 任意大小写；
     * <b>全角</b> {@code ［］} 与 <b>全角冒号</b> {@code ：} 与半角同判；形状里夹的<b>零宽字符</b>
     * （{@code U+200B/200C/200D/FEFF}）一律忽略。冒号必须紧跟 {@code mood}（冒号后的空格由
     * {@link #tag} 去掉；{@code mood} 与冒号之间的<b>普通空格不算</b> —— 那还是"没写标记"）。</p>
     *
     * <p><b>下标坐标</b>：返回值始终是<b>原文</b>里的下标（归一化只发生在比较时，不重建字符串）
     * ⇒ 调用方 {@code line.substring(0, mi)}（{@link #strip} 保留"标记之前文字"用的就是它）
     * 一个字都没变。</p>
     */
    public static int start(String line) {
        if (line == null) return -1;
        if (line.indexOf('[') < 0 && line.indexOf('\uFF3B') < 0) return -1;
        for (int i = 0; i < line.length(); i++) {
            if (br(line.charAt(i)) != '[') continue;
            int j = i + 1;
            j = skip(line, j);
            if (j < line.length() && br(line.charAt(j)) == '[') { j++; j = skip(line, j); }
            int after = word(line, j, "mood");
            if (after < 0) continue;
            after = skip(line, after);
            if (after >= line.length() || br(line.charAt(after)) != ':') continue;
            return i;
        }
        return -1;
    }

    /** 全角 {@code ［］：} ⇒ 半角（其余原样）；只用于<b>比较</b>，不改下标、不改正文。 */
    private static char br(char c) {
        if (c == '\uFF3B') return '[';
        if (c == '\uFF3D') return ']';
        if (c == '\uFF1A') return ':';
        return c;
    }

    /** 零宽字符（识别时可忽略，但**不删**它 —— 下标必须仍与原文对齐）。 */
    private static boolean zero(char c) {
        return c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF';
    }

    /** 跳过零宽字符。 */
    private static int skip(String line, int i) {
        while (i < line.length() && zero(line.charAt(i))) i++;
        return i;
    }

    /**
     * 从 {@code j} 起匹配 {@code w}（大小写不敏感，途中允许夹零宽字符）。
     *
     * @return 匹配结束后的下标；没匹配上返回 {@code -1}
     */
    private static int word(String line, int j, String w) {
        int k = j;
        for (int i = 0; i < w.length(); i++) {
            k = skip(line, k);
            if (k >= line.length()) return -1;
            if (Character.toLowerCase(line.charAt(k)) != Character.toLowerCase(w.charAt(i))) return -1;
            k++;
        }
        return k;
    }

    /** 这一段里有没有 {@code mood} 标记族（形状判据就是 {@link #start}）。 */
    public static boolean has(String text) {
        return text != null && start(text) >= 0;
    }

    /**
     * 归一化后的标记名（去掉收尾括号 / 半角空格 / 制表符 / 全角空格 / 零宽字符）：
     * {@code 转述} / {@code 做不到} / {@code 为难} / {@code 违规}；没有返回空串。
     *
     * <p>★ 2026-09-23「出站核心批」③ 起，冒号与收尾括号也走 {@link #br} 归一化
     * （{@code ：} / {@code ］} 与半角同判），标记名里的零宽字符按"可忽略"处理 ——
     * 于是 {@code hasRelay} 认得 {@code ［［mood:转述］］} 与 {@code [[mood:转述&#8203;]]}。
     * 返回语义不变：认不出标记就是空串（{@code ""}）。</p>
     */
    public static String tag(String line) {
        int i = start(line);
        if (i < 0) return "";
        int j = -1;
        for (int k = i; k < line.length(); k++) {
            if (br(line.charAt(k)) == ':') { j = k; break; }
        }
        if (j < 0) return "";
        int e = -1;
        for (int k = j + 1; k < line.length(); k++) {
            if (br(line.charAt(k)) == ']') { e = k; break; }
        }
        String tag = e < 0 ? line.substring(j + 1) : line.substring(j + 1, e);
        StringBuilder sb = new StringBuilder(tag.length());
        for (int k = 0; k < tag.length(); k++) {
            char c = tag.charAt(k);
            if (zero(c)) continue;                       // 零宽字符：可忽略
            if (br(c) == ']' || c == ' ' || c == '\t' || c == '\u3000') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    // ================================================================ 「被劈开的标记」（① 用的两条，2026-09-23 新增）

    /**
     * <b>「半截标记」</b>：这一行 {@link #start} 认出了标记开头，<b>却没有闭合的 {@code ]}</b>
     * （全角 {@code ］} 也算闭合）。返回那个开头的下标；不是这种形状返回 {@code -1}。
     *
     * <p>这是 {@code term.Sinks.gate} 用的那条判据（①(b)，工单口径逐字）：<b>某一段的末行</b>
     * 是这个形状 ⇒ 这一段落不发（理由 {@code rule=mood_mark residual=split}）。
     * 为什么必须 fail-closed：标记被 {@code <split>} 劈成两半时，两半各自都不满足
     * {@link #start} 的完整形状，于是"半个标记 + 残句"会当正文发进群（真机 2026-09-22 事故形状）。</p>
     *
     * <p>这是<b>新增</b>的公开方法（既有签名与语义一个字未动）；它只做识别，不做任何改写。</p>
     */
    public static int openMark(String line) {
        int i = start(line);
        if (i < 0) return -1;
        for (int k = i; k < line.length(); k++) if (br(line.charAt(k)) == ']') return -1;
        return i;
    }

    /**
     * <b>「被劈开的标记开头」</b>：文本的<b>结尾</b>是不是一个还没写完的 {@code [[mood:}} 开头
     * （{@code [}/{@code [[} + {@code mood} 的正前缀）。返回那个开头的下标；不是则 {@code -1}。
     *
     * <p>与 {@link #openMark} 的分工：{@code openMark} 管"写全了 {@code mood:} 但没收尾"，
     * 这一条管"连 {@code mood:} 都没写全"—— 真机碎片 {@code 正文甲[[mo} / {@code od:做不到]]}
     * 里，前半段就落在这里（{@link #start} 对它返回 {@code -1}）。<b>至少要写出 {@code mo}</b>
     * 才算（{@code [} / {@code [m} 单独收尾不算 —— 正常句子真的可能以方括号收尾）。</p>
     */
    public static int openTail(String text) {
        if (text == null || text.isEmpty()) return -1;
        int from = Math.max(0, text.length() - 12);      // 形状最长 7 字，留零宽字符的余量
        for (int i = from; i < text.length(); i++) {
            if (br(text.charAt(i)) != '[') continue;
            if (openShape(text, i) >= 0) return i;
        }
        return -1;
    }

    /** {@code text[i..]} 是不是标记开头的<b>正前缀</b>（至少写出 {@code mo}）：是 ⇒ 形状字符数，否则 -1。 */
    private static int openShape(String text, int i) {
        int k = i;
        int n = text.length();
        int matched = 0;
        if (k >= n || br(text.charAt(k)) != '[') return -1;
        k++; matched++;
        k = skip(text, k);
        if (k < n && br(text.charAt(k)) == '[') { k++; matched++; k = skip(text, k); }
        String w = "mood";
        int letters = 0;
        for (int j = 0; j < w.length(); j++) {
            k = skip(text, k);
            if (k >= n) break;
            if (Character.toLowerCase(text.charAt(k)) != w.charAt(j)) return -1;
            k++; letters++;
        }
        matched += letters;
        if (letters < 2) return -1;                     // 只认 `mo` 及以上
        if (letters == w.length()) {                    // 四个字母写全了：还可以再跟一个冒号
            k = skip(text, k);
            if (k < n) {
                if (br(text.charAt(k)) != ':') return -1;
                k++; matched++;
            }
        }
        return k >= n ? matched : -1;                   // 必须是"到此为止"的正前缀
    }

    /** 这一批文本里有没有「转述」自报标记（<b>归一化</b>：大小写 / 中括号数 / 空格都不敏感）。 */
    public static boolean hasRelay(String text) {
        if (text == null) return false;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (RELAY_TAG.equals(tag(lines[i]))) return true;
        }
        return false;
    }

    /** 这一条文本里认出来的标记（归一化；没有就是空串）。<b>只读</b>，不做任何记分。 */
    public static String tagOf(String text) {
        if (text == null) return "";
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String t = tag(lines[i]);
            if (Str.has(t)) return t;
        }
        return "";
    }

    /**
     * 剥掉 {@code [[mood:…]]} 那一行，<b>以及它之后的全部正文</b>（标记 = 止语点）。
     *
     * <p><b>旧 → 新（2026-09-22「内部文字外泄防线批」改的，是本类唯一的语义变更）</b>：
     * 旧口径只把标记行当"垃圾行"剥掉 —— 整行丢弃、保留标记之前的文字、<b>后面的行照旧全留</b>
     * （夹在行中时只丢该行标记之后的那半句）。新口径把标记当<b>边界</b>：标记所在行<b>连同它之后
     * 的全部正文</b>一起丢弃（夹在行中时，丢的是标记之后的<b>全部</b>，不再只是那一行的尾巴）。</p>
     *
     * <p>匹配走 {@link #start} 的<b>归一化</b>（大小写 / 中括号数 / 冒号后空格；★ 2026-09-23「出站核心批」③
     * 起另加<b>全角括号·全角冒号·零宽字符</b>）⇒ 识别面<b>只扩不缩</b>：今天照发的全角写法，从今往后会被剥。
     * 本方法的<b>语义</b>（保留标记之前的文字、标记及其之后的全部丢弃；没剥到时逐字返回原对象）一个字没动。</p>
     *
     * @return 剥完的文本；<b>没剥到时逐字返回原对象</b>（{@code ==} 可判"一个字都没动"）；
     *         剥到"什么都不剩"时返回空串（调用方据此决定"这一条不发"）
     */
    public static String strip(String text) {
        if (text == null || start(text) < 0) return text;
        StringBuilder out = new StringBuilder();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int mi = start(line);
            if (mi >= 0) {
                String keep = mi <= 0 ? "" : line.substring(0, mi).trim();
                if (Str.has(keep)) out.append(keep).append('\n');
                break;            // ★ 止语点：这一行连同它之后的**全部**正文一起丢弃
            }
            out.append(line).append('\n');
        }
        String s = out.toString();
        while (s.endsWith("\n\n")) s = s.substring(0, s.length() - 1);
        return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
    }

    // ================================================================ 「转述」短 TTL 标记

    /** 记一枚转述标记（只对<b>同一次发送</b>有效；顺手清掉过期的）。 */
    public static void relay(String session) {
        if (!Str.has(session)) return;
        long now = System.currentTimeMillis();
        try {
            for (java.util.Map.Entry<String, Long> e : RELAY.entrySet()) {
                Long v = e.getValue();
                if (v == null || now - v.longValue() > RELAY_TTL_MS) RELAY.remove(e.getKey());
            }
            RELAY.put(session, Long.valueOf(now));
        } catch (Throwable ignored) {
        }
    }

    /** 取一枚转述标记（<b>取完即清</b>）：这一批是不是"转述轮"。 */
    public static boolean takeRelay(String session) {
        if (!Str.has(session)) return false;
        try {
            Long v = RELAY.remove(session);
            return v != null && (System.currentTimeMillis() - v.longValue()) <= RELAY_TTL_MS;
        } catch (Throwable t) {
            return false;
        }
    }
}
