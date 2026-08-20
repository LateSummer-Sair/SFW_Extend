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

    /** 单条 QQ 消息最大长度（超过则切割后逐条发送，条数不设上限）。 */
    private static final int MAX_MSG_LEN = 100;

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
            sb.append("\n📷 此消息包含 ").append(msg.getImageUrls().size()).append(" 张图片");
            for (int i = 0; i < msg.getImageUrls().size(); i++) {
                sb.append("\n  图片URL: ").append(msg.getImageUrls().get(i));
            }
        }
        if (msg.hasForward() && msg.getForwardContent() != null && !msg.getForwardContent().isEmpty()) {
            sb.append("\n" + "📨" + " 转发/折叠消息内容:\n").append(msg.getForwardContent());
        }
        if (msg.getQuotedMessageContent() != null && !msg.getQuotedMessageContent().isEmpty()) {
            sb.append("\n💬 被引用的消息: ").append(msg.getQuotedMessageContent());
        }
        return sb.toString();
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
        StringBuilder current = new StringBuilder();
        for (String para : t.split("\\n\\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (p.length() > MAX_MSG_LEN) {
                // 段落超长：先 flush 当前缓冲，再把段落切成多条
                if (current.length() > 0) {
                    messages.add(current.toString().trim());
                    current.setLength(0);
                }
                for (String piece : splitLongParagraph(p)) {
                    messages.add(piece);
                }
                continue;
            }
            int mergedLen = current.length() + (current.length() > 0 ? 2 : 0) + p.length();
            if (mergedLen > MAX_MSG_LEN && current.length() > 0) {
                messages.add(current.toString().trim());
                current.setLength(0);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(p);
        }
        if (current.length() > 0) messages.add(current.toString().trim());
        return messages;
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
            String task = buildTaskDescription(msg);

            // 本地图片识别（二维码 + OCR）：为节省在线 OCR 成本，仅对「引用消息图片」做识别（OCR 回归引用才使用）。
            // 直接发送的图片不再自动识别，AI 可根据任务描述中的图片 URL 引导用户「引用该图片」后识别。
            java.util.List<String> allImages = new java.util.ArrayList<>();
            allImages.addAll(msg.getQuotedImageUrls());
            boolean hasAnyImage = !msg.getQuotedImageUrls().isEmpty();
            if (hasAnyImage) {
                StringBuilder recog = new StringBuilder();
                recog.append("\n\n【图片本地识别备注】\n以下为每张图片的二维码与文字识别结果（先识别二维码，再识别文字），请以此备注为理解图片的唯一依据，直接根据备注内容回答；备注中标记「无」表示该项未识别到：");
                int idx = 0;
                for (String imgUrl : allImages) {
                    idx++;
                    try {
                        byte[] imgBytes = ImageDownloader.downloadImage(imgUrl);
                        String remark = ImageRecognizer.buildRemark(imgBytes);
                        recog.append("\n图片").append(idx).append(": ").append(remark);
                        // 附带 MD5，供 AI 用 setimageremark 工具精确修改该图注释（与图片强绑定）
                        if (imgBytes != null && imgBytes.length > 0) {
                            recog.append(" 【MD5:").append(ImageRecognizer.md5(imgBytes)).append("】");
                        }
                    } catch (Exception e) {
                        recog.append("\n图片").append(idx).append(": 二维码: 无 | OCR文字: 无（识别失败）");
                        AiAgentActivity.qqLog("[QQMsg] 图片识别失败: " + e.toString());
                    }
                }
                task = task + recog.toString();
            }

            long senderQQ = msg.getUserId();
            boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ);
            boolean useExecs = isMaster && plainText != null && plainText.startsWith("execs:");
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
                String actualTask = plainText.substring(6).trim();
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
                if (msg.isGroupMessage()) {
                    unifiedMemory.addConversation("assistant", aiResponse, "group",
                        msg.getGroupId(), selfId, null);
                } else {
                    unifiedMemory.addConversation("assistant", aiResponse, "private",
                        msg.getUserId(), selfId, null);
                }

                String cleanResponse = aiResponse.replaceAll("<[^>]+>", "").trim();
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

                    sendMultipleReplies(msg, messages);
                } else {
                    // 兜底：AI 只输出了标签没有纯文本，发送确认提示
                    AiAgentActivity.qqLog("[QQMsg] 仅标签无文本，发送兜底");
                    String brief = task.length() > 30 ? task.substring(0, 30) + "..." : task;
                    sendReply(msg, "\u2705 " + brief + " \u2014 \u5df2\u5904\u7406");
                }
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