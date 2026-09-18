package sair.v4.term;

import com.google.gson.JsonObject;

import sair.v4.auth.Caller;
import sair.v4.ctx.Sink;
import sair.v4.kit.Out;
import sair.v4.qq.Api;

/** 输出落点：本地控制台 / QQ（群、私聊）/ 定时任务。 */
public final class Sinks {

    private Sinks() {}

    /**
     * 出站 stage 管线（基板⑦；装配期由 {@code Boot} 注入）。
     *
     * <p><b>为什么是静态注入口</b>：{@link QqSink} / {@link TaskSink} 在网关、定时器、工具、
     * 探针等<b>十几处</b>被构造，而扩展点注册表是<b>基板级</b>的（同一时刻只有一个，
     * 见 {@code Boot} 的装配）。为了传一个引用去改所有构造点，只会让每个调用方都多背一个
     * "跟它无关"的参数。静态的话默认是 null —— 谁都没注入过（包括所有老探针）= 零 stage =
     * 与加这一层之前逐字节一致。</p>
     */
    private static volatile sair.v4.ext.ExtRegistry EXT;

    /** 注入/卸下出站扩展点（装配期注入；{@code Boot.stop()} 卸下，避免停完还在改正文）。 */
    public static void setExt(sair.v4.ext.ExtRegistry e) { EXT = e; }

    /**
     * 跑一遍出站 stage 管线（<b>分段之后、发送之前</b>）。
     *
     * <p>零 stage 时原样返回同一个 List 对象 —— 调用方据此做到"行为逐字节不变"。</p>
     *
     * @param seg 分段口径：追加消息（stage 的 {@code extra}）与正文共用同一把尺子
     */
    private static java.util.List<String> stage(Caller caller, java.util.List<String> parts,
                                                boolean group, long target, boolean echo, Segmenter seg) {
        sair.v4.ext.ExtRegistry e = EXT;
        if (e == null || !e.onStages()) return parts;
        final Segmenter s = seg;
        final boolean g = group;
        sair.v4.ext.ExtRegistry.Splitter sp = new sair.v4.ext.ExtRegistry.Splitter() {
            @Override
            public java.util.List<String> split(String text) {
                return s == null ? sair.v4.qq.Seg.messages(text, 1200) : s.plan(text, g);
            }
        };
        try {
            sair.v4.ext.Outbound m = new sair.v4.ext.Outbound(caller, "", group, target, echo);
            return e.runStages(m, parts, sp).parts;
        } catch (Throwable t) {
            // 注册表自己炸了：这一条按原样发。外挂改不动的东西，不能因为它坏了就丢话。
            return parts;
        }
    }

    /**
     * <b>出站最后一道闸</b>：这一条正文里是不是混进了模型的<b>工具调用标记</b>。
     *
     * <p>真机事故（2026-09-16 22:12，测试群 121873503）：模型把工具调用标记当正文吐了出来
     * （{@code delta.content} 里是 DSML 标签、{@code tool_calls} 是空的），
     * {@code agent.Loop} 因此判定"这一轮没有工具调用 = 最终回答"，那段标签被切成 3 条发进了真群。
     * 判据是<b>结构</b>（标签形状 + 竖线/DSML/结构词），不是关键词表 —— 见
     * {@link sair.v4.qq.ToolMarkup}。</p>
     *
     * <p><b>命中就整条不发</b>，绝不"正则删字符后照发"：半剥的残留（半个标签、半句参数）
     * 比一条都不发更像乱码，而且会把残缺内容同时写进聊天记忆。要拦的那一条丢了，
     * 其余条照发（各条独立判）。</p>
     *
     * @return {@code null} = 放行；非 null = 命中的结构事实（给日志用，<b>不含正文</b>）
     */
    private static String block(String part) {
        return sair.v4.qq.ToolMarkup.has(part) ? sair.v4.qq.ToolMarkup.fact(part) : null;
    }

    /** 记一行"拦下了"（结构事实，无正文 —— 与 {@code [qq] msg … chars=} 同口径）。 */
    private static void blocked(Out out, String where, String fact) {
        if (out != null) out.warn(where + " 出站拦截：正文命中工具调用标记（" + fact + "）→ 整条丢弃");
    }

    /**
     * <b>什么都不做的落点</b>（一个字都不发、也不打控制台）。
     *
     * <p>唯一的用途：给"<b>只能记账、不许说话</b>"的钩子当落点 —— 现在只有一条这样的路：
     * {@code QqGateway} 的 {@code on_notice} 派发（主人裁定「通知接入…<b>不必当即触发响应</b>」，
     * 见 {@code notes\workorders\N1-notices-ingest.md} 与 {@code tmp\n1\REPORT.md}）。
     * 那条路上钩子拿到的 {@code Turn.sink()} 必须是它：{@code h.say(...)} 能调、但<b>发不出去</b>。</p>
     *
     * <p>为什么不是"给钩子传 {@code null} 的 {@link sair.v4.ctx.Turn}"：{@code Boot.host(sk,c,null)}
     * 的 {@code Host.say} 在 {@code t == null} 时会<b>退回控制台落点</b>（那是为 on_timer 那条路设计的），
     * 于是"钩子说什么都发不出去"这句话就不成立了。传一个静默落点才是构造性的保证：
     * 这条路上没有任何一条通路能到用户（QQ 或控制台）。</p>
     */
    public static final class SilentSink implements Sink {
        public static final SilentSink I = new SilentSink();
        @Override public void say(String text) { }
        @Override public void stream(String delta) { }
        @Override public void notice(String text) { }
    }

    /** 本地控制台落点（≡ 主人交互）。 */
    public static final class ConsoleSink implements Sink {
        private final Out out;
        private volatile boolean streaming = false;
        /** 流式时扣住的尾巴：`**` `#` `|` 这类标记可能被劈成两批，扣住几个字符等下一批一起清。 */
        private String hold = "";
        public ConsoleSink(Out out) { this.out = out; }

        @Override
        public void say(String text) {
            if (streaming) {
                if (!hold.isEmpty()) {
                    out.print(sair.v4.qq.PlainText.cleanChunk(hold), Out.Tone.NORMAL);
                    hold = "";
                }
                out.print("\n", Out.Tone.NORMAL);   // 流式已打完内容，只补一个换行
                streaming = false;
                return;
            }
            // 纯文字口径（主人裁 2026-09-18：控制台输出也严禁 Markdown）
            out.print(sair.v4.qq.PlainText.clean(text == null ? "" : text) + "\n", Out.Tone.NORMAL);
        }

        @Override
        public void stream(String delta) {
            if (delta == null || delta.isEmpty()) return;
            streaming = true;
            String s = hold + delta;
            hold = "";
            int n = s.length();
            int cut = n;
            while (cut > 0 && cut > n - 3) {
                char c = s.charAt(cut - 1);
                if (c == '*' || c == '_' || c == '`' || c == '#' || c == '|' || c == '>' || c == '~') cut--;
                else break;
            }
            if (cut < n) {
                hold = s.substring(cut);
                s = s.substring(0, cut);
            }
            String cleaned = sair.v4.qq.PlainText.cleanChunk(s);
            if (!cleaned.isEmpty()) out.print(cleaned, Out.Tone.NORMAL);
        }

        @Override
        public void notice(String text) { out.dim(text); }
    }

    /**
     * QQ 落点：回复发到原会话。NapCat 未连接时降级到控制台并明确提示，
     * 但不影响其余能力（其它工具照常可用）。
     *
     * <p><b>不回显回复正文</b>：回复是"发出去"的动作，控制台只打一行结构事实
     * （{@code [qq→] sent … chars=…}）—— 默认口径是"只打逻辑怎么走"，
     * 正文只在 {@code logVerbose=true} 时打（构造参数 {@code echo} 由网关按该配置传入）。</p>
     *
     * <p><b>分段发送</b>：一条太长的回复拆成几条发（见 {@link sair.v4.qq.Seg}）——
     * 模型自己写 {@code <split>} 就按它切；没写就按"空行段落 → 长度上限"切，群聊还按
     * {@code replyGroupMaxChars} 再压短一档；条与条之间按 {@code replySplitDelayMs}(+抖动) 停一下，
     * 读起来才像人在连着说话。上限/停顿都能在 config.json 里调（见 {@link Segmenter}）。</p>
     */
    public static final class QqSink implements Sink {
        private final Api api;
        private final sair.v4.qq.Sender sender;
        private final Caller caller;
        private final Out out;
        private final boolean echo;
        /** 分段口径（上限、停顿）；为 null 时按出厂默认（不分段的后备路径）。 */
        private final Segmenter seg;
        /** 好感度子系统（她自己的加减标记在这里执行）；为 null = 本路径不管好感度。 */
        private final sair.v4.auth.Favor favor;

        public QqSink(Api api, Caller caller, Out out, boolean echo) {
            this(api, caller, out, echo, null);
        }

        public QqSink(Api api, Caller caller, Out out, boolean echo, Segmenter seg) {
            this(api, caller, out, echo, seg, null);
        }

        public QqSink(Api api, Caller caller, Out out, boolean echo, Segmenter seg, sair.v4.auth.Favor favor) {
            this(api, sair.v4.qq.Sender.of(api), caller, out, echo, seg, favor);
        }

        /** 直接给"发送器"（探针用记录器；生产走上面的 {@link Api} 构造）。 */
        public QqSink(sair.v4.qq.Sender sender, Caller caller, Out out, boolean echo, Segmenter seg) {
            this(null, sender, caller, out, echo, seg, null);
        }

        private QqSink(Api api, sair.v4.qq.Sender sender, Caller caller, Out out, boolean echo, Segmenter seg,
                       sair.v4.auth.Favor favor) {
            this.api = api;
            this.sender = sender == null ? sair.v4.qq.Sender.of(null) : sender;
            this.caller = caller;
            this.out = out;
            this.echo = echo;
            this.seg = seg;
            this.favor = favor;
        }

        @Override
        public void say(String text) {
            if (text == null) return;
            String raw = text.trim();
            if (raw.isEmpty()) return;
            // 注意顺序：**先分段再剥标记** —— <split> 是分段标记，剥早了就没法按它切了
            if (api != null && !api.available()) {
                out.warn("[qq] " + Api.NOT_CONNECTED + "：回复只打进控制台");
                String clean = sair.v4.qq.MarkerTags.strip(sair.v4.qq.PlainText.clean(raw));
                String blk = block(clean);                       // 工具调用标记：降级也不打出来
                if (blk != null) { blocked(out, "[qq]", blk); return; }
                out.print(clean + "\n", Out.Tone.NORMAL);   // 投递降级：不打出来就丢了
                return;
            }
            if (caller == null || (caller.groupId() <= 0 && caller.qq() <= 0)) {
                out.warn("[qq] 没有可用的会话落点（caller 缺群号/QQ），回复只打进控制台");
                String clean = sair.v4.qq.MarkerTags.strip(sair.v4.qq.PlainText.clean(raw));
                String blk = block(clean);
                if (blk != null) { blocked(out, "[qq]", blk); return; }
                out.print(clean + "\n", Out.Tone.NORMAL);
                return;
            }
            java.util.List<String> parts = seg == null
                    ? sair.v4.qq.Seg.messages(raw, 1200)
                    : seg.plan(raw, caller.isGroup());
            // 她自己的好感度加减标记：**在出站 stage 之前**就执行掉。
            // 为什么放在 stage 前面：stages 会否决/洗空某一批（实测「情绪」的禁词否决会把整批打掉），
            // 而好感度是"她的判断"，不是"这句话的一部分"——话被拦下来，账照样要记；
            // 反过来，标记在 stage 之前就消失，别的 stage 看到的正文是干净的。
            if (favor != null) {
                java.util.List<String> cleaned = sair.v4.qq.FavorTags.apply(parts, caller, favor, out);
                if (cleaned != null) parts = cleaned;
            }
            // 出站管线：分段与发送之间（零 stage = 原样，见 stage()）
            parts = stage(caller, parts, caller.isGroup(),
                    caller.isGroup() ? caller.groupId() : caller.qq(), echo, seg);
            int blockedParts = 0;                       // 本批被"工具调用标记"闸门拦下的条数
            for (int i = 0; i < parts.size(); i++) {
                // 纯文字口径（主人裁 2026-09-18：严禁 Markdown）：先清洗格式符号、再剥控制标记
                String part = sair.v4.qq.MarkerTags.strip(sair.v4.qq.PlainText.clean(parts.get(i)));
                if (part.isEmpty()) continue;
                // ★ 最后一道闸：这一条是不是模型的工具调用标记（真机事故 2026-09-16 22:12）。
                //   命中就丢这一条（其余条照发），日志只写结构事实。见 block(...) 的说明。
                String blk = block(part);
                if (blk != null) { blocked(out, "[qq]", blk); blockedParts++; continue; }
                if (echo) out.dim("[qq→] " + part);           // 只有 logVerbose 才打正文
                // 条间停顿：拟人化（最后一条之后不停）
                if (i > 0 && seg != null) seg.pause();
                String err = sender.send(caller.isGroup(),
                        caller.isGroup() ? caller.groupId() : caller.qq(), part);
                if (err != null) {
                    out.warn("[qq] 发送失败（" + err + "）：本条改打控制台");
                    out.print(part + "\n", Out.Tone.NORMAL);
                }
            }
            // 结构事实：发了几条、各多长（上限生效了没有，看这一行）
            if (out != null && parts.size() > 1) out.dim("[qq→] sent " + sair.v4.qq.Seg.fact(parts)
                    + " group=" + caller.isGroup());
            // 整批级别的拦截事实（只有真拦到了才打；没拦到 = 与加这道闸之前逐字节一致）
            if (blockedParts > 0 && out != null) {
                out.warn("[qq] 本批 " + parts.size() + " 条里拦下 " + blockedParts
                        + " 条（工具调用标记）；一个字的正文都没有发出去的那几条见上面的拦截行");
            }
        }

        @Override
        public void stream(String delta) {
            // QQ 不流式：等 final 一次发出，避免刷屏
        }

        @Override
        public void notice(String text) { out.dim(text); }
    }

    /** 定时/后台任务的落点：有目标就发 QQ，否则打控制台。 */
    public static final class TaskSink implements Sink {
        private final Api api;
        private final sair.v4.qq.Sender sender;
        private final Out out;
        private final boolean group;
        private final long target;
        private final boolean echo;
        /** 分段口径（与回复同一条：长结果也得分条发）。 */
        private final Segmenter seg;

        public TaskSink(Api api, Out out, boolean group, long target) {
            this(api, out, group, target, false);
        }

        /** {@code echo=true} 时把发出的正文也打进控制台（{@code logVerbose} 口径；默认只打结构事实）。 */
        public TaskSink(Api api, Out out, boolean group, long target, boolean echo) {
            this(api, out, group, target, echo, null);
        }

        public TaskSink(Api api, Out out, boolean group, long target, boolean echo, Segmenter seg) {
            this.api = api;
            this.sender = sair.v4.qq.Sender.of(api);
            this.out = out;
            this.group = group;
            this.target = target;
            this.echo = echo;
            this.seg = seg;
        }

        /** 直接给"发送器"（探针用；生产走 {@link Api} 构造）。 */
        public TaskSink(sair.v4.qq.Sender sender, Out out, boolean group, long target, boolean echo, Segmenter seg) {
            this.api = null;
            this.sender = sender == null ? sair.v4.qq.Sender.of(null) : sender;
            this.out = out;
            this.group = group;
            this.target = target;
            this.echo = echo;
            this.seg = seg;
        }

        /**
         * <b>投递通路</b>：目标会话键（{@code group:<群号>} / {@code qq:<QQ>}）。
         * <p>给 {@code Agent.ask} 用：正文会被直接投到这里的回合，纪律与普通回复不同（只写对用户说的话，
         * 不写回执腔）。号 ≤ 0（没目标、降级打控制台）时返回 {@code null} —— 那时正文没有落点。</p>
         */
        @Override
        public String deliverTo() {
            if (target <= 0L) return null;
            return (group ? "group:" : "qq:") + target;
        }

        @Override
        public void say(String text) {
            if (text == null) return;
            String raw = text.trim();
            if (raw.isEmpty()) return;
            String body = sair.v4.qq.MarkerTags.strip(sair.v4.qq.PlainText.clean(raw));
            if (api != null && !api.available() && target > 0) {
                if (out != null) out.warn("[task] " + Api.NOT_CONNECTED + "：结果只打进控制台");
                String blk = block(body);          // 工具调用标记：降级也不打出来
                if (blk != null) { blocked(out, "[task]", blk); return; }
                out.print(body + "\n", Out.Tone.NORMAL);       // 投递降级：不打出来这条就丢了
                return;
            }
            if (target > 0) {
                java.util.List<String> parts = seg == null
                        ? sair.v4.qq.Seg.messages(raw, 1200)
                        : seg.plan(raw, group);
                // 出站管线：分段与发送之间（零 stage = 原样）。定时任务没有调用者，caller 传 null。
                parts = stage(null, parts, group, target, echo, seg);
                String firstErr = null;
                int blockedParts = 0;
                for (int i = 0; i < parts.size(); i++) {
                    String part = sair.v4.qq.MarkerTags.strip(sair.v4.qq.PlainText.clean(parts.get(i)));
                    if (part.isEmpty()) continue;
                    // ★ 与 QqSink 同一道闸（同一条出站纪律：工具调用标记永不外发）
                    String blk = block(part);
                    if (blk != null) { blocked(out, "[task]", blk); blockedParts++; continue; }
                    if (i > 0 && seg != null) seg.pause();
                    String err = sender.send(group, target, part);
                    if (err != null && firstErr == null) firstErr = err;
                }
                // 结构事实：发给谁、几条、各多长；正文只在 echo（logVerbose）时出现
                if (out != null) {
                    out.dim("[task→] sent scope=" + (group ? "group" : "private") + " target=" + target
                            + " chars=" + body.length() + " " + sair.v4.qq.Seg.fact(parts)
                            + (blockedParts > 0 ? " blocked=" + blockedParts : "")
                            + (firstErr == null ? "" : " fail=" + firstErr)
                            + (echo ? " text=" + body : ""));
                }
                return;
            }
            if (out != null) {
                String blk = block(body);
                if (blk != null) { blocked(out, "[task]", blk); return; }
                out.print(body + "\n", Out.Tone.NORMAL);
            }
        }

        @Override
        public void stream(String delta) { }

        @Override
        public void notice(String text) {
            if (out != null) out.dim(text);
        }
    }

    /**
     * 长文本分段（QQ 单条消息有长度上限；优先在换行/句号处切，避免把句子劈开）。
     */
    public static java.util.List<String> split(String text, int max) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (text == null || text.trim().isEmpty()) return out;
        String rest = text;
        int limit = Math.max(16, max);
        while (rest.length() > limit) {
            int cut = -1;
            for (int i = Math.min(limit, rest.length() - 1); i > limit / 2; i--) {
                char c = rest.charAt(i);
                if (c == '\n' || c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?') {
                    cut = i + 1;
                    break;
                }
            }
            if (cut <= 0 || cut > limit) cut = limit;
            String head = rest.substring(0, cut).trim();
            if (!head.isEmpty()) out.add(head);
            rest = rest.substring(cut);
        }
        String tail = rest.trim();
        if (!tail.isEmpty()) out.add(tail);
        return out;
    }
}
