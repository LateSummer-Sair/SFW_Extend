package sair.aiagent.onebot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.core.AgentExecutor;
import sair.aiagent.onebot.model.QQMessage;

/**
 * Execs 链路执行与合并转发处理器。
 * 从 QQMessageHandler 中提取，负责 execs 模式的异步执行和思考过程的合并转发发送。
 */
public class ExecsForwardHandler {

    private final AgentExecutor agentExecutor;
    private final NapCatApi napcatApi;
    private final OneBotServer server;
    private final long selfId;
    private final String botLabel;

    public ExecsForwardHandler(AgentExecutor agentExecutor, NapCatApi napcatApi,
                                OneBotServer server, long selfId, String botLabel) {
        this.agentExecutor = agentExecutor;
        this.napcatApi = napcatApi;
        this.server = server;
        this.selfId = selfId;
        this.botLabel = botLabel;
    }

    /**
     * 执行真正的execs链路（与SFW控制台execs完全一样）。
     * 实时推送每一轮的思考和执行结果给主人。
     */
    public String executeRealExecs(QQMessage msg, String task, Consumer<String> responseSender) {
        if (agentExecutor == null) {
            return "[错误] AI引擎未就绪";
        }

        try {
            AiAgentActivity.debugLog("[QQMsg] 开始执行真实execs链路: " + task);

            Thread execThread = new Thread(() -> {
                final String[] thinkingBlockHolder = new String[1];
                try {
                    AiAgentActivity.debugLog("[QQMsg-Execs] execs线程启动，开始执行任务");

                    agentExecutor.setQqExecsCallback((roundOutput) -> {
                        if (roundOutput != null && roundOutput.startsWith("[THINKING_BLOCK]")) {
                            thinkingBlockHolder[0] = roundOutput.substring("[THINKING_BLOCK]".length());
                        } else {
                            sendReply(msg, roundOutput);
                        }
                    });

                    agentExecutor.execute(task);
                    agentExecutor.setQqExecsCallback(null);

                    if (thinkingBlockHolder[0] != null && !thinkingBlockHolder[0].isEmpty()) {
                        sendThinkingAsForward(msg, thinkingBlockHolder[0]);
                    }

                    AiAgentActivity.debugLog("[QQMsg-Execs] execs执行完成");

                } catch (Exception e) {
                    agentExecutor.setQqExecsCallback(null);
                    AiAgentActivity.debugLog("[QQMsg-Execs] execs执行异常: " + e.toString());
                    sendReply(msg, "\n[" + botLabel + "execs] 任务执行失败: " + e.getMessage() + "\n详细错误请查看SFW控制台");
                }
            }, "QQ-Execs-" + msg.getUserId());

            execThread.setDaemon(true);
            execThread.start();

            AiAgentActivity.debugLog("[QQMsg] execs任务已提交到后台线程");
            return "好的，马上处理~";

        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] execs提交失败: " + e.toString());
            return "[错误] execs任务提交失败: " + e.getMessage();
        }
    }

    /**
     * 将execs思考过程以QQ原生合并转发消息卡片发送。
     * 按 [第N轮思考] 标识拆分为多个转发节点。
     */
    public void sendThinkingAsForward(QQMessage msg, String thinkingText) {
        if (napcatApi == null) {
            sendReply(msg, thinkingText);
            AiAgentActivity.debugLog("[QQMsg] NapCatApi未就绪，思考过程以纯文本发送");
            return;
        }

        try {
            String[] rounds = thinkingText.split("(?=\\[第\\d+轮思考\\])");

            String botName = botLabel;
            String botUin = String.valueOf(selfId);

            List<Map<String, Object>> nodes = new ArrayList<>();

            for (String round : rounds) {
                String trimmed = round.trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.length() > 3500) {
                    trimmed = trimmed.substring(0, 3500) + "\n...(内容过长已截断)";
                }

                Map<String, Object> node = new HashMap<>();
                Map<String, Object> data = new HashMap<>();
                data.put("uin", botUin);
                data.put("name", botName);

                List<Map<String, Object>> content = new ArrayList<>();
                Map<String, Object> textSeg = new HashMap<>();
                textSeg.put("type", "text");
                Map<String, Object> textData = new HashMap<>();
                textData.put("text", trimmed);
                textSeg.put("data", textData);
                content.add(textSeg);

                data.put("content", content);
                node.put("type", "node");
                node.put("data", data);
                nodes.add(node);
            }

            if (nodes.isEmpty()) {
                AiAgentActivity.debugLog("[QQMsg] 思考内容为空，跳过合并转发");
                return;
            }

            AiAgentActivity.debugLog("[QQMsg] 发送合并转发消息: " + nodes.size() + " 个节点");

            if (msg.isGroupMessage()) {
                napcatApi.sendGroupForwardMsg(msg.getGroupId(), nodes);
            } else {
                napcatApi.sendPrivateForwardMsg(msg.getUserId(), nodes);
            }

        } catch (Exception e) {
            AiAgentActivity.debugLog("[QQMsg] 合并转发发送失败: " + e.toString());
            sendReply(msg, thinkingText);
        }
    }

    private void sendReply(QQMessage msg, String text) {
        if (server == null || text == null || text.trim().isEmpty()) return;
        if (msg.isGroupMessage()) {
            server.sendGroupMsg(msg.getGroupId(), text);
        } else {
            server.sendPrivateMsg(msg.getUserId(), text);
        }
    }
}
