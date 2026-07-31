package sair.aiagent.onebot;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.onebot.model.QQMessage;

/**
 * QQ 消息响应中的标签处理器。
 * 从 QQMessageHandler 中提取，负责处理 AI 回复中的群管标签、媒体发送标签、好友管理标签等。
 */
public class QqActionProcessor {

    private final NapCatApi napcatApi;
    private final InternalAgents internalAgents;
    private final String dataDir;
    private final UnifiedQQMemoryManager unifiedMemory;

    public QqActionProcessor(NapCatApi napcatApi, InternalAgents internalAgents,
                              String dataDir, UnifiedQQMemoryManager unifiedMemory) {
        this.napcatApi = napcatApi;
        this.internalAgents = internalAgents;
        this.dataDir = dataDir;
        this.unifiedMemory = unifiedMemory;
    }

    /**
     * 发送前处理AI响应中的群管标签和媒体标签。
     * 这是OneBot层的自有能力，不污染AgentExecutor主线。
     */
    public void processQqActions(String aiResponse, QQMessage msg, long senderQQ) {
        long groupId = msg.isGroupMessage() ? msg.getGroupId() : 0;

        boolean isSenderMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ);
        java.util.Set<Long> masterQQs = sair.aiagent.core.AiConfig.getInstance().getMasterQQs();
        if (!isSenderMaster) {
            AiAgentActivity.debugLog("[QQMsg] 非主人(senderQQ=" + senderQQ + ")尝试触发群管操作，已拒绝");
            return;
        }

        // 群管标签
        if (msg.isGroupMessage() && napcatApi != null) {
            processBanTag(aiResponse, groupId, masterQQs);
            processKickTag(aiResponse, groupId, masterQQs);
            processMuteAllTag(aiResponse, groupId);
            processSetAdminTag(aiResponse, groupId);
            processSetCardTag(aiResponse, groupId);
            processSetGroupNameTag(aiResponse, groupId);
            processLeaveGroupTag(aiResponse, groupId);
        }

        // 用户管理标签
        if (napcatApi != null) {
            processBlockTag(aiResponse);
            processUnblockTag(aiResponse);
            processDelfriendTag(aiResponse);
        }

        // 媒体发送标签
        if (napcatApi != null) {
            processSendImageTag(aiResponse, msg);
            processSendRecordTag(aiResponse, msg);
            processRelayTag(aiResponse, msg, senderQQ);
            processForwardMsgTag(aiResponse, msg, senderQQ);
            processSendLikeTag(aiResponse);
            processReadFileTag(aiResponse);
            processSendFileToTag(aiResponse, msg, senderQQ);
            processSendFileTag(aiResponse, msg, senderQQ);
        }
    }

    private void processBanTag(String response, long groupId, java.util.Set<Long> masterQQs) {
        Matcher m = Pattern.compile("<ban>\\s*(\\d+)(?:\\s+(\\d+))?\\s*</ban>").matcher(response);
        while (m.find()) {
            try {
                long targetUserId = Long.parseLong(m.group(1));
                int duration = 180;
                if (m.group(2) != null) {
                    duration = Integer.parseInt(m.group(2));
                    if (duration < 0) duration = 0;
                    if (duration > 2592000) duration = 2592000;
                }
                if (masterQQs.contains(targetUserId)) continue;
                napcatApi.muteGroupMember(groupId, targetUserId, duration);
            } catch (NumberFormatException e) { }
        }
    }

    private void processKickTag(String response, long groupId, java.util.Set<Long> masterQQs) {
        Matcher m = Pattern.compile("<kick>\\s*(\\d+)(?:\\s+(block))?\\s*</kick>", Pattern.CASE_INSENSITIVE).matcher(response);
        while (m.find()) {
            try {
                long targetUserId = Long.parseLong(m.group(1));
                boolean block = m.group(2) != null;
                if (masterQQs.contains(targetUserId)) continue;
                napcatApi.kickGroupMember(groupId, targetUserId, block);
            } catch (NumberFormatException e) { }
        }
    }

    private void processMuteAllTag(String response, long groupId) {
        Matcher m = Pattern.compile("<muteall>\\s*(on|off|true|false|1|0)\\s*</muteall>", Pattern.CASE_INSENSITIVE).matcher(response);
        while (m.find()) {
            String val = m.group(1).toLowerCase();
            boolean enable = "on".equals(val) || "true".equals(val) || "1".equals(val);
            napcatApi.muteAll(groupId, enable);
        }
    }

    private void processSetAdminTag(String response, long groupId) {
        Matcher m = Pattern.compile("<setadmin>\\s*(\\d+)\\s+(on|off|true|false|1|0)\\s*</setadmin>", Pattern.CASE_INSENSITIVE).matcher(response);
        while (m.find()) {
            try {
                long userId = Long.parseLong(m.group(1));
                String val = m.group(2).toLowerCase();
                boolean enable = "on".equals(val) || "true".equals(val) || "1".equals(val);
                napcatApi.setGroupAdmin(groupId, userId, enable);
            } catch (NumberFormatException e) { }
        }
    }

    private void processSetCardTag(String response, long groupId) {
        Matcher m = Pattern.compile("<setcard>\\s*(\\d+)\\s+(.+?)\\s*</setcard>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            try {
                long userId = Long.parseLong(m.group(1));
                String card = m.group(2).trim();
                napcatApi.setGroupCard(groupId, userId, card);
            } catch (NumberFormatException e) { }
        }
    }

    private void processSetGroupNameTag(String response, long groupId) {
        Matcher m = Pattern.compile("<setgroupname>\\s*(.+?)\\s*</setgroupname>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            String name = m.group(1).trim();
            napcatApi.setGroupName(groupId, name);
        }
    }

    private void processLeaveGroupTag(String response, long groupId) {
        Matcher m = Pattern.compile("<leavegroup>\\s*(dismiss)?\\s*</leavegroup>", Pattern.CASE_INSENSITIVE).matcher(response);
        while (m.find()) {
            boolean isDismiss = m.group(1) != null;
            napcatApi.leaveGroup(groupId, isDismiss);
        }
    }

    private void processBlockTag(String response) {
        Matcher m = Pattern.compile("<block>\\s*(\\d+)\\s*</block>").matcher(response);
        while (m.find()) {
            try {
                long userId = Long.parseLong(m.group(1));
                if (internalAgents != null) {
                    internalAgents.blockUser(userId, "AI判定违规拉黑", true);
                }
            } catch (NumberFormatException e) { }
        }
    }

    private void processUnblockTag(String response) {
        Matcher m = Pattern.compile("<unblock>\\s*(\\d+)\\s*</unblock>").matcher(response);
        while (m.find()) {
            try {
                long userId = Long.parseLong(m.group(1));
                if (internalAgents != null) {
                    internalAgents.unblockUser(userId);
                }
            } catch (NumberFormatException e) { }
        }
    }

    private void processDelfriendTag(String response) {
        if (napcatApi == null) return;
        Matcher m = Pattern.compile("<delfriend>\\s*(\\d+)\\s*</delfriend>").matcher(response);
        while (m.find()) {
            try {
                long userId = Long.parseLong(m.group(1));
                napcatApi.deleteFriend(userId);
            } catch (NumberFormatException e) { }
        }
    }

    private void processSendImageTag(String response, QQMessage msg) {
        Matcher m = Pattern.compile("<sendimage>\\s*(.+?)\\s*</sendimage>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            String imageContent = m.group(1).trim();
            File imgFile = new File(imageContent);
            if (imgFile.exists() && imgFile.isFile()) {
                if (msg.isGroupMessage()) {
                    napcatApi.sendGroupImage(msg.getGroupId(), imageContent);
                } else {
                    napcatApi.sendPrivateImage(msg.getUserId(), imageContent);
                }
            } else if (imageContent.startsWith("http://") || imageContent.startsWith("https://")) {
                if (msg.isGroupMessage()) {
                    napcatApi.sendGroupImage(msg.getGroupId(), imageContent);
                } else {
                    napcatApi.sendPrivateImage(msg.getUserId(), imageContent);
                }
            } else {
                try {
                    File outputDir = new File(dataDir, "rendered");
                    String fileName = "img_" + System.currentTimeMillis() + ".png";
                    File outputFile = new File(outputDir, fileName);
                    sair.aiagent.util.ImageRenderer.renderTextToImage(imageContent, outputFile);
                    String absPath = outputFile.getAbsolutePath();
                    if (msg.isGroupMessage()) {
                        napcatApi.sendGroupImage(msg.getGroupId(), absPath);
                    } else {
                        napcatApi.sendPrivateImage(msg.getUserId(), absPath);
                    }
                } catch (Exception e) {
                    AiAgentActivity.debugLog("[QQMsg] <sendimage>渲染失败: " + e.toString());
                }
            }
        }
    }

    private void processSendRecordTag(String response, QQMessage msg) {
        Matcher m = Pattern.compile("<sendrecord>\\s*(.+?)\\s*</sendrecord>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            String recordPath = m.group(1).trim();
            if (msg.isGroupMessage()) {
                napcatApi.sendGroupRecord(msg.getGroupId(), recordPath);
            } else {
                napcatApi.sendPrivateRecord(msg.getUserId(), recordPath);
            }
        }
    }

    private void processRelayTag(String response, QQMessage msg, long senderQQ) {
        Matcher m = Pattern.compile("<relay>\\s*(.+?)\\s*</relay>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            String relayContent = m.group(1).trim();
            int sepIdx = relayContent.indexOf("|||");
            if (sepIdx < 0) continue;
            String targetDesc = relayContent.substring(0, sepIdx).trim();
            String relayMsg = relayContent.substring(sepIdx + 3).trim();
            if (relayMsg.isEmpty()) continue;

            Long targetQQ = findContactByDescription(targetDesc, msg);
            if (targetQQ != null) {
                napcatApi.sendPrivateMessage(targetQQ, "[Bot转告] " + msg.getDisplayName() + "(" + senderQQ + ")让我告诉你：\n" + relayMsg);
            }
        }
    }

    private void processForwardMsgTag(String response, QQMessage msg, long senderQQ) {
        Matcher m = Pattern.compile("<forwardmsg>\\s*(\\d+)\\s*\\|\\|\\|\\s*(.+?)\\s*</forwardmsg>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            try {
                long fwdMsgId = Long.parseLong(m.group(1));
                String targetDesc = m.group(2).trim();
                boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ);
                if (!isMaster) continue;

                String origMsg = napcatApi.getMessage((int) fwdMsgId);
                if (origMsg != null && !origMsg.isEmpty()) {
                    String msgText = ForwardMessageExpander.extractMsgSegmentsText(origMsg);
                    if (msgText == null || msgText.isEmpty()) msgText = "[非文本消息]";
                    Long targetQQ = findContactByDescription(targetDesc, msg);
                    if (targetQQ != null) {
                        napcatApi.sendPrivateMessage(targetQQ, "[转发自 " + msg.getDisplayName() + "]\n" + msgText);
                    }
                }
            } catch (NumberFormatException e) { }
        }
    }

    private void processSendLikeTag(String response) {
        Matcher m = Pattern.compile("<sendlike>\\s*(\\d+)(?:\\s+(\\d+))?\\s*</sendlike>").matcher(response);
        while (m.find()) {
            try {
                long likeTarget = Long.parseLong(m.group(1));
                int times = 10;
                if (m.group(2) != null) times = Integer.parseInt(m.group(2));
                napcatApi.sendLike(likeTarget, times);
            } catch (NumberFormatException e) { }
        }
    }

    private void processReadFileTag(String response) {
        Matcher m = Pattern.compile("<readfile>\\s*(.+?)\\s*</readfile>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            String filePath = m.group(1).trim();
            try {
                File f = new File(filePath);
                if (!f.exists()) continue;
                sair.aiagent.util.FileUtils.readFile(filePath);
                AiAgentActivity.debugLog("[QQMsg] <readfile>读取: " + filePath + " (" + f.length() + " bytes)");
            } catch (Exception e) {
                AiAgentActivity.debugLog("[QQMsg] <readfile>失败: " + e.getMessage());
            }
        }
    }

    private void processSendFileToTag(String response, QQMessage msg, long senderQQ) {
        Matcher m = Pattern.compile("<sendfileto>\\s*(.+?)\\s*\\|\\|\\|\\s*(.+?)\\s*</sendfileto>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            String targetDesc = m.group(1).trim();
            String filePath = m.group(2).trim();
            if (!sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ)) continue;

            File f = new File(filePath);
            if (!f.exists() || !f.isFile()) continue;

            String canonicalPath;
            try { canonicalPath = f.getCanonicalPath(); } catch (Exception ex) { canonicalPath = f.getAbsolutePath(); }

            Long targetQQ = findContactByDescription(targetDesc, msg);
            if (targetQQ != null) {
                napcatApi.sendPrivateFile(targetQQ, canonicalPath, f.getName());
            }
        }
    }

    private void processSendFileTag(String response, QQMessage msg, long senderQQ) {
        Matcher m = Pattern.compile("<sendfile>\\s*(.+?)\\s*</sendfile>", Pattern.DOTALL).matcher(response);
        while (m.find()) {
            String filePath = m.group(1).trim();
            boolean isMaster = sair.aiagent.core.AiConfig.getInstance().isMasterQQ(senderQQ);
            if (!isMaster) continue;

            File f = new File(filePath);
            if (!f.exists() || !f.isFile()) continue;

            String canonicalPath;
            try { canonicalPath = f.getCanonicalPath(); } catch (Exception ex) { canonicalPath = f.getAbsolutePath(); }

            if (msg.isGroupMessage()) {
                napcatApi.sendGroupFile(msg.getGroupId(), canonicalPath, f.getName());
            } else {
                napcatApi.sendPrivateFile(msg.getUserId(), canonicalPath, f.getName());
            }
        }
    }

    /**
     * 根据描述在数据库中搜索匹配的联系人QQ号。
     * 搜索范围：群昵称映射 > 群成员列表 > 好友列表。
     */
    private Long findContactByDescription(String desc, QQMessage msg) {
        if (desc == null || desc.isEmpty()) return null;
        try { return Long.parseLong(desc.trim()); } catch (NumberFormatException ignored) {}

        if (msg.isGroupMessage() && unifiedMemory != null) {
            java.util.Map<String, Long> nickMap = unifiedMemory.getGroupNicknameMap(msg.getGroupId());
            for (java.util.Map.Entry<String, Long> e : nickMap.entrySet()) {
                if (e.getKey().contains(desc)) return e.getValue();
            }
            java.util.List<String[]> admins = unifiedMemory.getGroupAdmins(msg.getGroupId());
            if (admins != null) {
                for (String[] a : admins) {
                    if (a.length >= 2 && a[1] != null && a[1].contains(desc)) {
                        try { return Long.parseLong(a[0]); } catch (NumberFormatException ignored) {}
                    }
                }
            }
        }
        return null;
    }
}
