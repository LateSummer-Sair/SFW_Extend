package sair.aiagent.onebot;

import java.util.*;
import java.util.function.Consumer;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.core.AgentExecutor;
import sair.aiagent.model.StickerEntry;
import sair.aiagent.onebot.model.QQMessage;

/**
 * QQ消息与AI Agent之间的桥接层。
 * <p>负责消息路由（execq/execs）、AI回复发送、消息分段、任务描述构建。</p>
 */
class QQAgentBridge {

    /** execq 随机配表情包的概率（0.0~1.0），命中时才从库存随机挑一个追加 */
    private static final double STICKER_RANDOM_PROBABILITY = 0.4;

    /** 单条 QQ 消息最大长度（极端兜底上限，正常由 AI 用 <split> 语义分段，程序仅在超长时兜底切割）。 */
    private static final int MAX_MSG_LEN = 1000;

    private final AgentExecutor agentExecutor;
    private final UnifiedQQMemoryManager unifiedMemory;
    private volatile OneBotServer server;
    private volatile ExecsForwardHandler execsForwardHandler;
    private volatile long selfId;
    private volatile InternalAgents internalAgents;
    private volatile String dataDir;
    private volatile EmotionStateManager emotionManager;
    private volatile PendingRequestPool pendingRequestPool;

    QQAgentBridge(AgentExecutor agentExecutor, UnifiedQQMemoryManager unifiedMemory) {
        this.agentExecutor = agentExecutor;
        this.unifiedMemory = unifiedMemory;
    }

    void setServer(OneBotServer s) { this.server = s; }
    void setExecsForwardHandler(ExecsForwardHandler h) { this.execsForwardHandler = h; }
    void setSelfId(long id) { this.selfId = id; }
    void setDataDir(String dir) { this.dataDir = dir; }
    void setEmotionManager(EmotionStateManager em) { this.emotionManager = em; }
    void setPendingRequestPool(PendingRequestPool pool) { this.pendingRequestPool = pool; }

    String getBotLabel() {
        String name = sair.aiagent.core.AiConfig.getInstance().getBotName();
        return (name != null && !name.isEmpty()) ? name : "Bot";
    }

    // ==================== Agent 执行桥接 ====================

    /** 执行真正的execs链路（与SFW控制台一致）。返回立即回复文本。 */
    String executeRealExecs(QQMessage msg, String task, Consumer<String> responseSender) {
        if (agentExecutor == null) {
            return "[错误] AI引擎未就绪";
        }
        try {
            AiAgentActivity.qqLog("[QQMsg] 开始执行真实execs链路: " + task);
            Thread execThread = new Thread(() -> {
                String botLabel = getBotLabel();
                final String[] thinkingBlockHolder = new String[1];
                try {
                    AiAgentActivity.qqLog("[QQMsg-Execs] execs线程启动，开始执行任务");
                    agentExecutor.setQqExecsCallback((roundOutput) -> {
                        if (roundOutput != null && roundOutput.startsWith("[THINKING_BLOCK]")) {
                            thinkingBlockHolder[0] = roundOutput.substring("[THINKING_BLOCK]".length());
                        } else {
                            sendReply(msg, roundOutput);
                        }
                    });
                    agentExecutor.enterBypass();
                    try {
                        agentExecutor.executeFcLocal(task);
                    } finally {
                        agentExecutor.exitBypass();
                    }
                    agentExecutor.setQqExecsCallback(null);
                    if (thinkingBlockHolder[0] != null && !thinkingBlockHolder[0].isEmpty()) {
                        sendThinkingAsForward(msg, thinkingBlockHolder[0]);
                    }
                    AiAgentActivity.qqLog("[QQMsg-Execs] execs执行完成");
                } catch (Exception e) {
                    agentExecutor.setQqExecsCallback(null);
                    AiAgentActivity.qqLog("[QQMsg-Execs] execs执行异常: " + e.toString());
                    sendReply(msg, "\n[" + botLabel + "execs] ❌ 任务执行失败: " + e.getMessage() + "\n详细错误请查看SFW控制台");
                }
            }, "QQ-Execs-" + msg.getUserId());
            execThread.setDaemon(true);
            execThread.start();
            AiAgentActivity.qqLog("[QQMsg] execs任务已提交到后台线程");
            return "好的，马上处理~";
        } catch (Exception e) {
            AiAgentActivity.qqLog("[QQMsg] execs提交失败: " + e.toString());
            return "[错误] execs任务提交失败: " + e.getMessage();
        }
    }

    /** 执行 QQ execs: 链路：原生 Function Calling + 全权限（buildAllTools）+ pro 模型 + QQ 上下文。返回立即回复文本。 */
    String executeRealExecFc(QQMessage msg, String task, String stableSystem, String dynamicContext,
                             sair.aiagent.core.ToolContext toolCtx) {
        if (agentExecutor == null) {
            return "[错误] AI引擎未就绪";
        }
        try {
            AiAgentActivity.qqLog("[QQMsg] 开始执行execs链路（Function Calling 全权限+全技能）: " + task);
            Thread execThread = new Thread(() -> {
                String botLabel = getBotLabel();
                try {
                    AiAgentActivity.qqLog("[QQMsg-Execs] execs线程启动");
                    // executeFcExecs 内部已 enterBypass/exitBypass（免确认）
                    String result = agentExecutor.executeFcExecs(task, stableSystem, dynamicContext, toolCtx,
                            sair.aiagent.core.AiConfig.getInstance().getAgentModel());
                    if (result != null && !result.trim().isEmpty()) {
                        sendReply(msg, result);
                    } else {
                        sendReply(msg, "[execs] 任务执行完成（无文本输出）");
                    }
                    AiAgentActivity.qqLog("[QQMsg-Execs] execs执行完成");
                } catch (Exception e) {
                    AiAgentActivity.qqLog("[QQMsg-Execs] execs执行异常: " + e.toString());
                    sendReply(msg, "\n[" + botLabel + "execs] 任务执行失败: " + e.getMessage() + "\n详细错误请查看SFW控制台");
                }
            }, "QQ-Execs-" + msg.getUserId());
            execThread.setDaemon(true);
            execThread.start();
            AiAgentActivity.qqLog("[QQMsg] execs任务已提交到后台线程");
            return "好的，马上处理~";
        } catch (Exception e) {
            AiAgentActivity.qqLog("[QQMsg] execs提交失败: " + e.toString());
            return "[错误] execs任务提交失败: " + e.getMessage();
        }
    }

    void sendThinkingAsForward(QQMessage msg, String thinkingText) {
        if (execsForwardHandler != null) execsForwardHandler.sendThinkingAsForward(msg, thinkingText);
    }

    // ==================== 消息发送 ====================

    void sendReply(QQMessage msg, String text) {
        if (server == null || text == null || text.trim().isEmpty()) return;
        if (msg.isGroupMessage()) {
            server.sendGroupMsg(msg.getGroupId(), text);
        } else {
            server.sendPrivateMsg(msg.getUserId(), text);
        }
    }

    void sendMultipleReplies(QQMessage msg, List<String> texts) {
        if (texts == null || texts.isEmpty()) return;
        for (String text : texts) {
            if (text != null && !text.trim().isEmpty()) {
                sendReply(msg, text);
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    // ==================== 任务描述 ====================

    String buildTaskDescription(QQMessage msg) {
        StringBuilder sb = new StringBuilder();
        sb.append(msg.getDisplayName());
        sb.append("(QQ:").append(msg.getUserId()).append(")");
        sb.append(msg.isGroupMessage() ? " 群聊: " : " 私聊: ");
        sb.append(msg.getPlainText());
        if (msg.hasImage() && !msg.getImageUrls().isEmpty()) {
            // 图片内容由系统按需注入（引用图片或看图指令才识别），此处仅提示数量
            sb.append("\n📷 此消息包含 ").append(msg.getImageUrls().size()).append(" 张图片");
        }
        if (msg.hasForward()) {
            sb.append("\n📨 此消息包含转发/折叠消息，内容概述见 [折叠消息摘要]。");
        }
        if (msg.getQuotedMessageContent() != null && !msg.getQuotedMessageContent().isEmpty()) {
            String quotedSenderLabel = (msg.getQuotedSenderName() != null && !msg.getQuotedSenderName().isEmpty())
                    ? msg.getQuotedSenderName() + "(QQ:" + msg.getQuotedSenderQQ() + ")"
                    : "对方";
            String quotedContent = msg.getQuotedMessageContent();
            int foldIdx = quotedContent.indexOf("[折叠消息展开内容]");
            if (foldIdx >= 0) {
                String head = quotedContent.substring(0, foldIdx).trim();
                if (!head.isEmpty()) {
                    sb.append("\n💬 被引用的消息（由 ").append(quotedSenderLabel).append(" 发出，其转发内容已单独生成概述）: ").append(head);
                } else {
                    sb.append("\n💬 用户引用了 ").append(quotedSenderLabel).append(" 的折叠消息，内容概述见 [折叠消息摘要]。");
                }
            } else {
                sb.append("\n💬 被引用的消息（由 ").append(quotedSenderLabel).append(" 发出）: ").append(quotedContent);
            }
        }
        return sb.toString();
    }

    /** 纯@（无附带文字）时的任务描述：结合上下文自然回应，不回复「空消息」。 */
    private String buildBareMentionTask(QQMessage msg) {
        String name = msg.getDisplayName();
        String roleLabel = buildSpeakerRoleLabel(msg);
        String identityLine = name + "（QQ:" + msg.getUserId() + "，身份:" + roleLabel + "）";
        String[] recent = findRecentUserMessageWithMark(msg);
        if (recent != null && recent[0] != null && !recent[0].isEmpty()) {
            String content = recent[0];
            String mark = recent[1];
            if (mark != null && !mark.isEmpty()) {
                return identityLine + " 单独 @ 了你（没有附带文字）。\n"
                        + "他/她最近说的是：\"" + content + "\"\n"
                        + "这条消息你【之前已经处理过了】，处理结果是：\"" + mark + "\"\n"
                        + "请基于「已处理」这个事实自然地回应他/她（例如告诉他刚才已经帮他办过了），【绝对不要重复执行原消息里的任务】。\n"
                        + "注意：只有【⭐主人】才能称为'主人'，此人身份是" + roleLabel + "，若不是主人请用昵称称呼。";
            }
            return identityLine + " 单独 @ 了你（没有附带文字）。\n"
                    + "请结合他/她最近说的话自然地回应，不要提「空消息」「没内容」之类的话。\n"
                    + "他/她最近说的是：\"" + content + "\"\n"
                    + "注意：只有【⭐主人】才能称为'主人'，此人身份是" + roleLabel + "，若不是主人请用昵称称呼。";
        }
        return identityLine + " 单独 @ 了你（没有附带文字）。\n"
                + "请自然地回应他/她（例如打招呼、询问有什么事），不要提「空消息」。\n"
                + "注意：只有【⭐主人】才能称为'主人'，此人身份是" + roleLabel + "，若不是主人请用昵称称呼。";
    }

    /** 判断消息触发者的身份标签：⭐主人 / 👑群主 / 🔧管理员 / 普通成员 */
    private String buildSpeakerRoleLabel(QQMessage msg) {
        long uid = msg.getUserId();
        if (sair.aiagent.core.AiConfig.getInstance().isMasterQQ(uid)) return "⭐主人";
        if (msg.isGroupMessage() && unifiedMemory != null) {
            try {
                java.util.List<String[]> admins = unifiedMemory.getGroupAdmins(msg.getGroupId());
                for (String[] a : admins) {
                    if (a.length > 0 && String.valueOf(uid).equals(a[0])) {
                        if (a.length > 2 && "owner".equals(a[2])) return "👑群主";
                        return "🔧管理员";
                    }
                }
            } catch (Exception ignored) {}
        }
        return "普通成员";
    }

    /** 查找该用户最近一条非空消息及其 Mark 备注（返回 [内容, 备注]，备注可能为 null），找不到返回 null。 */
    private String[] findRecentUserMessageWithMark(QQMessage msg) {
        if (unifiedMemory == null) return null;
        try {
            if (msg.isGroupMessage()) {
                java.util.List<String[]> history = unifiedMemory.getRecentGroupChatHistoryWithMark(msg.getGroupId(), 30);
                if (history != null) {
                    String uid = String.valueOf(msg.getUserId());
                    for (int i = history.size() - 1; i >= 0; i--) {
                        String[] m = history.get(i);
                        if (m != null && m.length > 2 && uid.equals(m[0])) {
                            String content = m[2];
                            if (content != null && !content.trim().isEmpty()) {
                                String mark = (m.length > 3) ? m[3] : null;
                                return new String[]{ content.trim(), mark };
                            }
                        }
                    }
                }
            } else {
                java.util.List<String[]> conv = unifiedMemory.getPrivateConversations(msg.getUserId(), 30);
                if (conv != null) {
                    for (String[] m : conv) {
                        if (m != null && m.length > 1 && "user".equals(m[0])) {
                            String content = m[1];
                            if (content != null && !content.trim().isEmpty()) {
                                String mark = unifiedMemory.getConversationMark("private", msg.getUserId(), content.trim());
                                return new String[]{ content.trim(), mark };
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ==================== 消息分割 ====================

    List<String> splitIntoMessages(String text) {
        List<String> messages = new ArrayList<>();
        if (text == null || text.isEmpty()) return messages;
        String t = text.trim();
        if (t.length() <= MAX_MSG_LEN) {
            messages.add(t);
            return messages;
        }
        // 1. 先切分成「语义单元」：``` 代码块作为不可分割的原子单元，其余按空行段落切分
        List<String> units = splitSemanticUnits(t);
        // 2. 按 MAX_MSG_LEN 合并语义单元，尽量不在单元内部切割
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (unit.length() > MAX_MSG_LEN) {
                // 单元本身超长（如超长代码块/段落）：先 flush 当前缓冲，再单独切分
                if (current.length() > 0) {
                    messages.add(current.toString().trim());
                    current.setLength(0);
                }
                for (String piece : splitLongParagraph(unit)) {
                    messages.add(piece);
                }
                continue;
            }
            int mergedLen = current.length() + (current.length() > 0 ? 2 : 0) + unit.length();
            if (mergedLen > MAX_MSG_LEN && current.length() > 0) {
                messages.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(unit);
        }
        if (current.length() > 0) messages.add(current.toString().trim());
        return messages;
    }

    /** 将文本切分为语义单元：优先保护 ``` 代码块（含围栏整体），代码块之外按空行段落切分。 */
    private List<String> splitSemanticUnits(String t) {
        List<String> units = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int i = 0;
        int n = t.length();
        while (i < n) {
            int fence = t.indexOf("```", i);
            if (fence < 0) {
                plain.append(t.substring(i));
                break;
            }
            int close = t.indexOf("```", fence + 3);
            if (close < 0) {
                plain.append(t.substring(i));
                break;
            }
            // 代码块前的内容先按空行段落切分
            plain.append(t.substring(i, fence));
            appendParagraphs(units, plain.toString());
            plain.setLength(0);
            // 代码块整体作为独立原子单元（含三反引号围栏）
            String block = t.substring(fence, close + 3).trim();
            if (!block.isEmpty()) units.add(block);
            i = close + 3;
        }
        if (plain.length() > 0) {
            appendParagraphs(units, plain.toString());
        }
        return units;
    }

    private void appendParagraphs(List<String> units, String text) {
        for (String para : text.split("\\n\\n")) {
            String p = para.trim();
            if (!p.isEmpty()) units.add(p);
        }
    }

    /** 把超长段落切成 ≤MAX_MSG_LEN 的片段，优先在换行/句末标点处切。 */
    private List<String> splitLongParagraph(String para) {
        List<String> pieces = new ArrayList<>();
        int pos = 0;
        int n = para.length();
        while (pos < n) {
            int end = Math.min(pos + MAX_MSG_LEN, n);
            if (end < n) {
                int nl = para.lastIndexOf('\n', end - 1);
                if (nl > pos) {
                    end = nl;
                } else {
                    for (int i = end - 1; i > pos + MAX_MSG_LEN / 2; i--) {
                        char ch = para.charAt(i);
                        if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';') {
                            end = i + 1;
                            break;
                        }
                    }
                }
            }
            String piece = para.substring(pos, end).trim();
            if (!piece.isEmpty()) pieces.add(piece);
            pos = end;
        }
        return pieces;
    }

    // ==================== QQ Agent 主入口 ====================

    /**
     * 执行QQ通道的Agent处理。
     * 根据发送者身份决定使用execq（受限）还是execs（自由）模式。
     */
    void executeQqAgent(QQMessage msg, Object memoryManager,
                        String stableSystem, String dynamicContext, Consumer<String> responseSender) {
        if (agentExecutor == null) {
            sendReply(msg, "AI引擎未就绪，请稍后再试。");
            return;
        }

        String plainText = msg.getPlainText();
        if (plainText != null && plainText.contains("<stop>")) {
            AiAgentActivity.qqLog("[QQMsg] 检测到<stop>标签，停止Agent执行");
            agentExecutor.markStopped();
            sendReply(msg, "\u2705 Agent执行已停止。");
            return;
        }

        try {
            // 语音消息：若异步转文字尚未完成，直接告知用户，无需等待（异步转文字完成后会自动注入聊天记录）
            if (msg.hasRecord() && (msg.getVoiceText() == null || msg.getVoiceText().isEmpty())) {
                sendReply(msg, "收到语音消息，但语音转文字尚未完成，请稍后重试。转文字完成后会自动记录。");
                return;
            }
            String task = buildTaskDescription(msg);

            // 纯@（无附带文字）：分析上下文，不要回复「空消息」
            boolean bareMention = msg.isAtBot() && (plainText == null || plainText.trim().isEmpty());
            if (bareMention) {
                task = buildBareMentionTask(msg);
            }

            long senderQQ = msg.getUserId();
            boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ);
            // 语音消息：触发词在语音转文字内容中（voiceText）也应生效，兼容 execs: 前缀
            String voiceText = msg.getVoiceText();
            boolean voiceExecs = voiceText != null && voiceText.trim().startsWith("execs:");
            boolean useExecs = isMaster && ((plainText != null && plainText.startsWith("execs:")) || voiceExecs);
            AiAgentActivity.qqLog("[QQMsg] 发送者QQ: " + senderQQ + ", 是否主人: " + isMaster + ", 使用execs: " + useExecs);

            // 构造 QQ 工具执行上下文
            sair.aiagent.core.ToolContext toolCtx = new sair.aiagent.core.ToolContext("execq");
            toolCtx.qqMsg = msg;
            toolCtx.napcatApi = napcatApi;
            toolCtx.unifiedMemory = unifiedMemory;
            toolCtx.internalAgents = internalAgents;
            toolCtx.senderQQ = senderQQ;
            toolCtx.isMaster = isMaster;
            toolCtx.dataDir = dataDir;
            toolCtx.affection = emotionManager != null ? emotionManager.getAffection(senderQQ) : 0;
            toolCtx.pendingRequestPool = pendingRequestPool;
            toolCtx.emotionManager = emotionManager;

            String aiResponse;
            if (useExecs) {
                AiAgentActivity.qqLog("[QQMsg] 主人execs消息，执行完整execs链路（Function Calling 全工具）");
                sendReply(msg, "[execs] 权限提升：全权限 + 全技能...\n正在启动execs链路...");
                String actualTask;
                if (voiceExecs) {
                    actualTask = voiceText.trim().substring(6).trim();
                } else {
                    actualTask = plainText.substring(6).trim();
                }
                // execs 全权限：将 execq 提示词中的受限描述替换为全权限说明，避免 AI 误以为仍受 execq 限制
                String execsSystem = stableSystem != null ? stableSystem
                        .replace("execq 通道的 eval（动态注入）仅限多对象拆分搜索场景破例可用，evaljs（动态执行）始终禁用；其他场景无代码兜底",
                                 "execs 全权限链路：eval（动态注入）与 evaljs（动态执行）均可正常使用，cmd/sys 可执行任意命令（已免确认），其他场景有代码兜底")
                        : null;
                aiResponse = executeRealExecFc(msg, actualTask, execsSystem, dynamicContext, toolCtx);
            } else {
                AiAgentActivity.qqLog("[QQMsg] 使用execq模式（Function Calling 受限）");
                aiResponse = agentExecutor.executeFcExecq(task, stableSystem, dynamicContext, toolCtx,
                        sair.aiagent.core.AiConfig.getInstance().getExecqModel());
            }

            if (aiResponse != null && !aiResponse.trim().isEmpty()) {
                AiAgentActivity.qqLog("[QQMsg] AI返回文本: 长度=" + aiResponse.length()
                        + ", 前100字=" + (aiResponse.length() > 100 ? aiResponse.substring(0, 100) + "..." : aiResponse));
                if (msg.isGroupMessage()) {
                    unifiedMemory.addConversation("assistant", aiResponse, "group",
                        msg.getGroupId(), selfId, null);
                } else {
                    unifiedMemory.addConversation("assistant", aiResponse, "private",
                        msg.getUserId(), selfId, null);
                }

                String cleanResponse = aiResponse.replaceAll("<[^>]+>", "").trim();
                AiAgentActivity.qqLog("[QQMsg] cleanResponse=" + (cleanResponse.isEmpty() ? "空" : ("非空(长度" + cleanResponse.length() + ")"))
                        + ", server=" + (server != null) + ", 是否群聊=" + msg.isGroupMessage());
                if (!cleanResponse.isEmpty()) {
                    List<String> messages;
                    String[] splitParts = aiResponse.split("<split>");
                    if (splitParts.length > 1) {
                        messages = new ArrayList<>();
                        for (String part : splitParts) {
                            String cleaned = part.replaceAll("<[^>]+>", "").trim();
                            if (!cleaned.isEmpty()) messages.add(cleaned);
                        }
                    } else {
                        messages = splitIntoMessages(cleanResponse);
                    }

                    if (!useExecs && !messages.isEmpty() && agentExecutor != null && agentExecutor.getStickerManager() != null) {
                        try {
                            // execq 随机触发：按概率从库存随机挑一个表情包，作为单独一条图片消息追加（不与文字拼接）
                            if (Math.random() < STICKER_RANDOM_PROBABILITY) {
                                StickerEntry match = agentExecutor.getStickerManager().randomSticker();
                                if (match != null && match.getImageUrl() != null && !match.getImageUrl().isEmpty()) {
                                    // 图片单独作为一条消息发送，避免与文字混排导致突兀或发送失败
                                    messages.add("[CQ:image,file=" + match.getImageUrl() + "]");
                                    AiAgentActivity.qqLog("[QQMsg] Sticker #" + match.getId() + " (random) sent as separate msg");
                                }
                            }
                        } catch (Exception ex) {
                            AiAgentActivity.qqLog("[QQMsg] Sticker random fail: " + ex.toString());
                        }
                    }

                    // 群聊纯@回复加@前缀，明确指向@自己的那个人
                    if (bareMention && msg.isGroupMessage() && !messages.isEmpty()) {
                        messages.set(0, "[CQ:at,qq=" + msg.getUserId() + "] " + messages.get(0));
                    }
                    AiAgentActivity.qqLog("[QQMsg] 准备发送 " + messages.size() + " 条回复");
                    sendMultipleReplies(msg, messages);
                } else {
                    // 兜底：AI 只输出了标签没有纯文本，发送确认提示
                    AiAgentActivity.qqLog("[QQMsg] 仅标签无文本，发送兜底");
                    String brief = task.length() > 30 ? task.substring(0, 30) + "..." : task;
                    sendReply(msg, "\u2705 " + brief + " \u2014 \u5df2\u5904\u7406");
                }
            } else {
                AiAgentActivity.qqLog("[QQMsg] AI返回空文本，发送兜底提示");
                sendReply(msg, "抱歉，我暂时无法理解这条消息，请换一种说法或稍后再试。");
            }
        } catch (Exception e) {
            AiAgentActivity.qqLog("[QQMsg] Agent执行失败: " + e.toString());
            sendReply(msg, "处理失败: " + e.getMessage());
        }
    }

    // NapCatApi引用（由QQMessageHandler注入，用于群管回调）
    private volatile NapCatApi napcatApi;
    void setNapcatApi(NapCatApi api) { this.napcatApi = api; }
    void setInternalAgents(InternalAgents ia) { this.internalAgents = ia; }
}