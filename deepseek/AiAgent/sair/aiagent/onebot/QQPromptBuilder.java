package sair.aiagent.onebot;

import java.util.*;
import java.util.concurrent.ExecutorService;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.core.AiConfig;
import sair.aiagent.core.AgentExecutor;
import sair.aiagent.core.SkillBank;
import sair.aiagent.onebot.model.QQMessage;

class QQPromptBuilder {

    private final UnifiedQQMemoryManager unifiedMemory;
    private final NapCatApi napcatApi;
    private final ExecutorService execPool;
    private volatile EmotionStateManager emotionManager;
    private volatile AgentExecutor agentExecutor;

    QQPromptBuilder(UnifiedQQMemoryManager unifiedMemory, NapCatApi napcatApi,
                    ExecutorService execPool) {
        this.unifiedMemory = unifiedMemory;
        this.napcatApi = napcatApi;
        this.execPool = execPool;
    }

    void setEmotionManager(EmotionStateManager em) { this.emotionManager = em; }
    void setAgentExecutor(AgentExecutor ae) { this.agentExecutor = ae; }

    String buildStableSystemPrompt() {
        String corePrompt = AiConfig.getInstance().getExecqPrompt();
        corePrompt = corePrompt.replace("{filepath}", AiConfig.getInstance().getFileDownloadPath());
        return corePrompt;
    }

    String buildDynamicContext(QQMessage msg, List<String> punishmentRecords) {
        StringBuilder sb = new StringBuilder(8192);

        Set<Long> masterQQSet = AiConfig.getInstance().getMasterQQs();
        Set<Long> adminSet = new HashSet<>();
        Set<Long> ownerSet = new HashSet<>();
        Map<Long, String> roleCache = new LinkedHashMap<>();
        if (msg.isGroupMessage() && unifiedMemory != null) {
            List<String[]> admins = unifiedMemory.getGroupAdmins(msg.getGroupId());
            for (String[] a : admins) {
                long aid = Long.parseLong(a[0]);
                roleCache.put(aid, a[2]);
                if ("owner".equals(a[2])) ownerSet.add(aid);
                else adminSet.add(aid);
            }
        }

        sb.append("## 当前上下文\n");
        boolean isSpeakerMaster = AiConfig.getInstance().isMasterQQ(msg.getUserId());
        if (msg.isGroupMessage()) {
            String groupName = unifiedMemory.getGroupName(msg.getGroupId());
            if (groupName == null && napcatApi != null) {
                final long gid = msg.getGroupId();
                execPool.submit(() -> {
                    try {
                        String info = napcatApi.getGroupInfo(gid, false);
                        String name = sair.aiagent.onebot.util.JsonUtil.extractString(info, "group_name");
                        if (name != null && !name.isEmpty()) unifiedMemory.setGroupName(gid, name);
                    } catch (Exception ignored) {}
                });
            }
            String senderRole = (msg.getSender() != null && msg.getSender().role != null)
                ? msg.getSender().role : "member";
            String roleLabel = "owner".equals(senderRole) ? "群主" :
                               "admin".equals(senderRole) ? "管理员" : "成员";
            sb.append("群: ").append(groupName != null ? groupName + "(" + msg.getGroupId() + ")" : msg.getGroupId());
            sb.append(" | 说话者: ").append(msg.getDisplayName());
            sb.append("(QQ:").append(msg.getUserId()).append(")");
            sb.append(" | 身份: ").append(roleLabel);
            if (isSpeakerMaster) {
                sb.append(" | ⭐此人是你的主人，可以称呼'主人'");
            } else {
                sb.append(" | 此人不是你的主人，请用昵称/名字称呼，禁止喊'主人'");
            }
        } else {
            sb.append("私聊 | QQ:").append(msg.getUserId());
            sb.append(" | ").append(msg.getDisplayName());
            if (isSpeakerMaster) {
                sb.append(" | ⭐此人是你的主人，可以称呼'主人'");
            } else {
                sb.append(" | 此人不是你的主人，请用昵称/名字称呼，禁止喊'主人'");
            }
        }
        String botName = AiConfig.getInstance().getBotName();
        if (botName != null && !botName.isEmpty()) sb.append(" | 你的名字:").append(botName);
        sb.append("\n");

        if (msg.isGroupMessage() && !msg.getMentionedUsers().isEmpty()) {
            sb.append("⚠ 此消息@了以下用户: ");
            int mc = 0;
            for (Long mu : msg.getMentionedUsers()) {
                if (mc > 0) sb.append(", ");
                String tagName = resolveUserName(mu, msg.getGroupId(), unifiedMemory);
                sb.append(tagName).append("(QQ:").append(mu).append(")");
                if (masterQQSet.contains(mu)) sb.append("⭐");
                if (ownerSet.contains(mu)) sb.append("👑");
                else if (adminSet.contains(mu)) sb.append("🔧");
                mc++;
            }
            sb.append("\n");
        }
        sb.append("\n");

        // === 注入 Bot 已知群列表（跨群操作支撑） ===
        if (unifiedMemory != null) {
            Map<Long, String> knownGroups = unifiedMemory.getAllKnownGroups();
            if (!knownGroups.isEmpty()) {
                sb.append("## Bot 已加入的群(群名→群号)\n");
                int gc = 0;
                for (Map.Entry<Long, String> e : knownGroups.entrySet()) {
                    sb.append("- ").append(e.getValue()).append("→").append(e.getKey());
                    if (msg.isGroupMessage() && e.getKey().longValue() == msg.getGroupId()) sb.append("(本群)");
                    sb.append("\n");
                    gc++;
                    if (gc >= 30) break;
                }
                sb.append("跨群操作(去别的群发消息/找人): grouplist 查实时群列表 → groupmembers 查成员 → sendgroupmsg 发消息@人\n\n");
            }
        }

        // === 注入 Bot 好友列表（私聊场景支撑） ===
        if (unifiedMemory != null) {
            Map<Long, String> knownFriends = unifiedMemory.getAllKnownFriends();
            if (knownFriends.isEmpty() && napcatApi != null) {
                // 缓存为空时实时拉取并缓存好友昵称
                try {
                    String resp = napcatApi.getFriendList();
                    String dataArr = sair.aiagent.onebot.util.JsonUtil.extractArray(resp, "data");
                    if (dataArr != null && !dataArr.trim().isEmpty()) {
                        for (String f : sair.aiagent.onebot.util.JsonUtil.splitJsonArray(dataArr)) {
                            String uid = sair.aiagent.onebot.util.JsonUtil.extractString(f, "user_id");
                            String nick = sair.aiagent.onebot.util.JsonUtil.extractString(f, "nickname");
                            String remark = sair.aiagent.onebot.util.JsonUtil.extractString(f, "remark");
                            if (uid != null && !uid.isEmpty()) {
                                String name = (remark != null && !remark.isEmpty()) ? remark : (nick != null ? nick : "");
                                if (!name.isEmpty()) {
                                    try { unifiedMemory.setFriendName(Long.parseLong(uid), name); } catch (NumberFormatException ignored) {}
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
                knownFriends = unifiedMemory.getAllKnownFriends();
            }
            if (!knownFriends.isEmpty()) {
                sb.append("## Bot 的好友(昵称→QQ号)\n");
                int fc = 0;
                for (Map.Entry<Long, String> e : knownFriends.entrySet()) {
                    sb.append("- ").append(e.getValue()).append("→").append(e.getKey()).append("\n");
                    fc++;
                    if (fc >= 30) break;
                }
                sb.append("私聊找人/传话: friendlist 查好友列表 → relay 转告\n\n");
            }
        }

        boolean hasDirectImage = msg.hasImage() && !msg.getImageUrls().isEmpty();
        boolean hasQuotedImage = !msg.getQuotedImageUrls().isEmpty();
        if (hasDirectImage || hasQuotedImage) {
            if (hasDirectImage) {
                sb.append("📷 此消息包含 ").append(msg.getImageUrls().size()).append(" 张图片。\n");
            }
            if (hasQuotedImage) {
                sb.append("📷 此消息引用了 ").append(msg.getQuotedImageUrls().size()).append(" 张图片。\n");
            }
            sb.append("图片内容识别结果由系统按需注入（引用图片或用户明确要求「看图」时才会调用视觉模型识别）。\n");
            sb.append("若上下文未出现 [图片内容识别结果]，说明本次未识别图片，请勿凭空编造图片内容，仅结合图片备注与上下文回复。\n");
            sb.append("如果需要发送图片，使用 <sendimage>描述</sendimage> 标签。\n\n");
        }

        if (msg.hasForward()) {
            sb.append("📨 此消息包含转发/折叠消息，其内容概述由系统单独生成后注入（见 [折叠消息摘要]）。\n");
            sb.append("若上下文未出现 [折叠消息摘要]，说明本次未能获取折叠内容，请告知用户暂时无法查看折叠消息的具体内容。\n\n");
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
                    sb.append("💬 用户引用了 ").append(quotedSenderLabel).append(" 的消息(其转发内容已单独生成概述):\n").append(head).append("\n");
                } else {
                    sb.append("💬 用户引用了 ").append(quotedSenderLabel).append(" 的折叠消息，具体内容概述见 [折叠消息摘要]。\n");
                }
            } else {
                sb.append("💬 用户引用了 ").append(quotedSenderLabel).append(" 的消息:\n").append(quotedContent).append("\n");
            }
            sb.append("注意：这段被引用的消息是 ").append(quotedSenderLabel).append(" 发出的，不是当前与你对话的用户发的。请结合上下文做出针对性回复。\n\n");
        }

        if (msg.isGroupMessage() && unifiedMemory != null) {
            Map<String, Long> nickMap = unifiedMemory.getGroupNicknameMap(msg.getGroupId());
            if (!nickMap.isEmpty()) {
                sb.append("## 群昵称→QQ映射(⭐主人👑群主🔧管理,无图标=普通成员群昵称)\n");
                int nc = 0;
                for (Map.Entry<String, Long> e : nickMap.entrySet()) {
                    if (nc >= 20) break;
                    sb.append(e.getKey()).append("→").append(e.getValue());
                    if (masterQQSet.contains(e.getValue())) sb.append("⭐");
                    if (ownerSet.contains(e.getValue())) sb.append("👑");
                    else if (adminSet.contains(e.getValue())) sb.append("🔧");
                    sb.append(" "); nc++;
                }
                sb.append("\n");
            }
            List<String[]> admins = unifiedMemory.getGroupAdmins(msg.getGroupId());
            if (!admins.isEmpty()) {
                sb.append("## 群管理员/群主\n");
                StringBuilder ownerLine = new StringBuilder();
                StringBuilder adminLine = new StringBuilder();
                for (String[] a : admins) {
                    long aid = Long.parseLong(a[0]);
                    String label = a[1] + "(QQ:" + a[0] + ")";
                    if (masterQQSet.contains(aid)) label += "⭐";
                    if ("owner".equals(a[2])) {
                        if (ownerLine.length() > 0) ownerLine.append(", ");
                        ownerLine.append(label);
                    } else {
                        if (adminLine.length() > 0) adminLine.append(", ");
                        adminLine.append(label);
                    }
                }
                if (ownerLine.length() > 0) sb.append("群主: ").append(ownerLine).append("\n");
                if (adminLine.length() > 0) sb.append("管理员: ").append(adminLine).append("\n");
                sb.append("若@管理员或@群主，从上表选。多管理随机@1-2个即可\n\n");
            }
        }

        if (unifiedMemory != null) {
            Map<String, Long> personalMap = unifiedMemory.getPersonalNicknameMap();
            if (!personalMap.isEmpty()) {
                sb.append("## 个人昵称→QQ映射(跨群,⭐主人👑群主🔧管理,其余=昵称)\n");
                int pc = 0;
                for (Map.Entry<String, Long> e : personalMap.entrySet()) {
                    if (pc >= 15) break;
                    sb.append(e.getKey()).append("→").append(e.getValue());
                    if (masterQQSet.contains(e.getValue())) sb.append("⭐");
                    if (ownerSet.contains(e.getValue())) sb.append("👑");
                    else if (adminSet.contains(e.getValue())) sb.append("🔧");
                    sb.append(" "); pc++;
                }
                sb.append("\n");
            }
        }

        if (msg.isGroupMessage()) {
            appendGroupChatHistory(sb, msg, masterQQSet, ownerSet, adminSet);
        } else {
            appendPrivateChatHistory(sb, msg);
        }

        appendImpressionAndMemories(sb, msg);

        // === Journal: inject recent cross-session activity log (补齐execq通道) ===
        if (agentExecutor != null && agentExecutor.getJournal() != null) {
            String journalCtx = agentExecutor.getJournal().buildRecentContext(15);
            if (journalCtx != null) {
                sb.append("\n").append(journalCtx).append("\n");
            }
        }

        // === Notes: auto-inject relevant knowledge base entries (补齐execq通道) ===
        SkillBank notesBank = SkillBank.getInstance();
        if (notesBank != null && notesBank.getPersistenceManager() != null && msg.getPlainText() != null) {
            String notesCtx = notesBank.getPersistenceManager().buildNotesContext(msg.getPlainText());
            if (notesCtx != null) {
                sb.append("\n").append(notesCtx).append("\n");
            }
            // === Corrections: auto-inject relevant correction records (避免重复犯错) ===
            String corrCtx = notesBank.getPersistenceManager().buildCorrectionsContext(msg.getPlainText(), 1200);
            if (corrCtx != null) {
                sb.append("\n").append(corrCtx).append("\n");
            }
        }

        if (emotionManager != null) sb.append(emotionManager.buildRichEmotionContext(msg.getUserId()));

        if (punishmentRecords != null && !punishmentRecords.isEmpty()) {
            sb.append("## 违规处罚\n");
            for (String r : punishmentRecords) sb.append("- ").append(r).append("\n");
            sb.append("\n");
        }

        if (agentExecutor != null && agentExecutor.getSkillBank() != null) {
            boolean isExecsMsg = msg.getPlainText() != null && msg.getPlainText().trim().startsWith("execs:");
            String skillChannel = isExecsMsg ? "execs" : "execq";
            String skillsCtx = agentExecutor.getSkillBank().buildCompactIndex(skillChannel, 800);
            if (skillsCtx != null) sb.append("\n").append(skillsCtx).append("\n");
            String routeHint = agentExecutor.getSkillBank().getBestRoute(msg.getPlainText());
            if (routeHint != null) sb.append(routeHint).append("\n");
        }

        return sb.toString();
    }

    String buildExecqSystemPrompt(QQMessage msg, List<String> punishmentRecords) {
        return buildStableSystemPrompt() + "\n\n" + buildDynamicContext(msg, punishmentRecords);
    }

    private void appendGroupChatHistory(StringBuilder sb, QQMessage msg, Set<Long> masterQQSet,
                                         Set<Long> ownerSet, Set<Long> adminSet) {
        List<String[]> groupHistory = unifiedMemory.getRecentGroupChatHistoryWithMark(msg.getGroupId(), 25);
        if (!groupHistory.isEmpty()) {
            sb.append("## 临时群上下文(近25条,⭐主人👑群主🔧管理,其余=群昵称;带〖已处理〗标记的说明你处理过该消息,不要重复执行)\n");
            for (String[] h : groupHistory) {
                long hUid = Long.parseLong(h[0]);
                String hName = h[1]; String content = h[2];
                if (content.length() > 200) content = content.substring(0, 200) + "...";
                StringBuilder prefix = new StringBuilder();
                if (masterQQSet.contains(hUid)) prefix.append("⭐");
                if (ownerSet.contains(hUid)) prefix.append("👑");
                else if (adminSet.contains(hUid)) prefix.append("🔧");
                if (prefix.length() > 0) prefix.append(" ");
                sb.append(prefix).append(hName).append(": ").append(content);
                if (h.length > 3 && h[3] != null && !h[3].isEmpty()) {
                    sb.append(" 〖已处理:").append(h[3]).append("〗");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }
        // 跨群续聊：注入最近其他群聊的话题（时间维度兜底，解决"继续上一个群的话题"这类无关键词元引用）
        List<String> globalGroupChat = unifiedMemory.getRecentGroupChatHistoryGlobal(12, msg.getGroupId());
        if (!globalGroupChat.isEmpty()) {
            sb.append("## 最近其他群聊话题(跨群续聊)\n");
            for (String h : globalGroupChat) {
                sb.append("- ").append(h).append("\n");
            }
            sb.append("\n");
        }
        List<String[]> personalConvs = unifiedMemory.getPrivateConversations(msg.getUserId(), 15);
        if (!personalConvs.isEmpty()) {
            sb.append("## 与该用户的历史\n");
            for (String[] c : personalConvs) {
                String roleLabel = "user".equals(c[0]) ? msg.getDisplayName() : "You";
                String content = c[1];
                if (content.length() > 300) content = content.substring(0, 300) + "...";
                sb.append(roleLabel).append(": ").append(content).append("\n");
            }
            sb.append("\n");
        }
        appendGlobalConversations(sb, msg, 30, 15, masterQQSet, ownerSet, adminSet);
    }

    private void appendPrivateChatHistory(StringBuilder sb, QQMessage msg) {
        List<String[]> convs = unifiedMemory.getPrivateConversations(msg.getUserId(), 25);
        if (!convs.isEmpty()) {
            sb.append("## 与该用户的历史\n");
            for (String[] c : convs) {
                String roleLabel = "user".equals(c[0]) ? "User" : "Assistant";
                String content = c[1];
                if (content.length() > 300) content = content.substring(0, 300) + "...";
                sb.append(roleLabel).append(": ").append(content).append("\n");
            }
            sb.append("\n");
        }
        appendGlobalConversations(sb, msg, 20, 10, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
    }

    private void appendGlobalConversations(StringBuilder sb, QQMessage msg, int fetch, int show,
                                            Set<Long> masterQQSet, Set<Long> ownerSet, Set<Long> adminSet) {
        List<String[]> globalConvs = unifiedMemory.getGlobalRecentConversations(fetch);
        if (globalConvs.isEmpty()) return;
        sb.append("## 全局最近活跃\n");
        int count = 0;
        for (String[] c : globalConvs) {
            if (count >= show) break;
            String sourceType = c[2]; String sourceId = c[3];
            String senderName = c[5] != null ? c[5] : "?";
            String senderIdStr = c[4]; String content = c[1];
            if (content.length() > 150) content = content.substring(0, 150) + "...";
            StringBuilder prefix = new StringBuilder();
            if (senderIdStr != null) {
                try {
                    long sid = Long.parseLong(senderIdStr);
                    if (masterQQSet.contains(sid)) prefix.append("⭐");
                    if (ownerSet.contains(sid)) prefix.append("👑");
                    else if (adminSet.contains(sid)) prefix.append("🔧");
                } catch (NumberFormatException ignored) {}
            }
            if (prefix.length() > 0) prefix.append(" ");
            String ctx = "group".equals(sourceType) ?
                (sourceId.equals(String.valueOf(msg.getGroupId())) ? "[本群]" : "[群"+sourceId+"]") : "[私]";
            sb.append(ctx).append(prefix).append(senderName).append(": ").append(content).append("\n");
            count++;
        }
        sb.append("\n");
    }

    private void appendImpressionAndMemories(StringBuilder sb, QQMessage msg) {
        SkillBank bank = SkillBank.getInstance();
        if (bank != null && bank.getPersistenceManager() != null) {
            sair.aiagent.model.ImpressionEntry imp = bank.getPersistenceManager().getImpression(msg.getUserId());
            if (imp != null && imp.hasContent()) {
                sb.append("## 对该用户的印象\n");
                sb.append(imp.toPromptContext()).append("\n\n");
            }
        }
        if (unifiedMemory != null && msg.getPlainText() != null) {
            List<String> relatedMemories = unifiedMemory.searchMemories(msg.getPlainText(), 3);
            if (relatedMemories != null && !relatedMemories.isEmpty()) {
                sb.append("## 相关记忆\n");
                for (String mem : relatedMemories) {
                    if (mem.length() > 300) mem = mem.substring(0, 300) + "...";
                    sb.append("- ").append(mem).append("\n");
                }
                sb.append("\n");
            }
            // 跨群检索群聊历史（群与群之间的记忆共享，如「隔壁群谁喊妈妈」）
            List<String> relatedHistory = unifiedMemory.searchGroupChatHistory(msg.getPlainText(), 5);
            if (relatedHistory != null && !relatedHistory.isEmpty()) {
                sb.append("## 跨群相关群聊记录\n");
                for (String h : relatedHistory) {
                    sb.append("- ").append(h).append("\n");
                }
                sb.append("\n");
            }
        }
    }

    static String resolveUserName(long qq, long groupId, UnifiedQQMemoryManager mem) {
        if (mem != null) {
            Map<String, Long> nickMap = mem.getGroupNicknameMap(groupId);
            if (nickMap != null) {
                for (Map.Entry<String, Long> e : nickMap.entrySet()) {
                    if (e.getValue() == qq) return e.getKey();
                }
            }
        }
        return String.valueOf(qq);
    }
}
