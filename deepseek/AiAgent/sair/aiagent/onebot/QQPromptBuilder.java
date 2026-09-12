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
    private volatile long selfId; // Bot 自己的 QQ 号，存图候选排除自己的消息

    /**
     * 后台补数据专用小线程池（群信息 / 好友列表）。
     * <p>
     * {@code getGroupInfo} / {@code getFriendList} 是<b>阻塞</b>调用（要等 OneBot WebSocket 回包），
     * 以前直接丢进 execPool —— 而 execPool 只有 3 个 worker 且同时承载所有 AI 请求，
     * 一次卡住的 NapCat 调用就会吃掉 1/3 的 AI 处理能力。补数据不影响本次回复，
     * 因此拆到独立小池，彻底与 AI 主链路隔离。
     * </p>
     */
    private final ExecutorService bgFetchPool = new java.util.concurrent.ThreadPoolExecutor(
            1, 2, 60L, java.util.concurrent.TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<Runnable>(16),
            r -> { Thread t = new Thread(r, "QQ-PromptBgFetch"); t.setDaemon(true); return t; },
            new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());

    QQPromptBuilder(UnifiedQQMemoryManager unifiedMemory, NapCatApi napcatApi,
                    ExecutorService execPool) {
        this.unifiedMemory = unifiedMemory;
        this.napcatApi = napcatApi;
        this.execPool = execPool;
    }

    void setEmotionManager(EmotionStateManager em) { this.emotionManager = em; }
    void setAgentExecutor(AgentExecutor ae) { this.agentExecutor = ae; }
    void setSelfId(long selfId) { this.selfId = selfId; }

    String buildStableSystemPrompt() {
        String corePrompt = AiConfig.getInstance().getExecqPrompt();
        corePrompt = corePrompt.replace("{filepath}", AiConfig.getInstance().getFileDownloadPath());
        return corePrompt;
    }

    /**
     * 构建动态上下文（下沉到首条 user 消息）。
     * <p>
     * <b>KV 前缀缓存友好的分段构建</b>：整段内容被拆成「稳定部分 sb」与「易变部分 vol」，
     * 先写稳定部分再拼易变部分。DeepSeek 的前缀缓存按<b>完整前缀单元</b>匹配，
     * 只要前面字节不变就能命中（命中约 ¥0.02-0.04/M，未命中约 ¥1-2/M，差约 50 倍），
     * 所以「先放几条消息内不会变的内容、把每条消息都在变的说话者/历史压到后面」
     * 能把可缓存前缀从「技能索引」延长到「技能索引 + 群印象 + 群身份 + 用户画像 + 偏好 + 会话日志」。
     * </p>
     * <p>稳定段顺序（越靠前越稳定）：技能索引 → 群印象 → 群身份 → 群成员映射 → 结构化偏好 →
     * 用户画像 → 会话日志。易变段顺序：说话者/回复对象 → @列表 → 跨群列表 → 图片/折叠/引用提示 →
     * 群历史 → 检索到的记忆/笔记/纠错 → 情绪 → 惩罚记录 → 路由提示。</p>
     */
    String buildDynamicContext(QQMessage msg, List<String> punishmentRecords) {
        StringBuilder sb = new StringBuilder(8192);   // 稳定部分（可缓存前缀）
        StringBuilder vol = new StringBuilder(8192);  // 易变部分（逐消息变化）
        // 纯文本只提取一次，后续复用，避免每处调用重复执行 CQ 码正则剥离
        final String plainText = msg.getPlainText();

        // 稳定工具索引置于动态上下文首部：技能不变时逐字节稳定，作为 user 消息前缀命中 KV 缓存
        // 预算 2000 token(≈8000 字符)：足以装下全部内置工具/API 清单（84 条 ≈ 3.3K 字符）
        // 加若干条经验技能，成本约 +1.5K token/请求（相对 1M 上下文可忽略）。
        if (agentExecutor != null && agentExecutor.getSkillBank() != null) {
            boolean isExecsMsg = plainText != null && plainText.trim().startsWith("execs:");
            String skillChannel = isExecsMsg ? "execs" : "execq";
            String skillsCtx = agentExecutor.getSkillBank().buildStableIndex(skillChannel, 2000);
            if (skillsCtx != null) sb.append(skillsCtx).append("\n\n");
        }

        // 群印象（群级、极少变化）：紧跟在稳定技能索引之后，尽量延长可缓存前缀；
        // 同时让 AI 知道「这个群整体是什么氛围」（此前 group_impressions 表有 51 行却从未被注入）。
        if (msg.isGroupMessage()) {
            sair.aiagent.core.PersistenceManager gpm = sair.aiagent.core.PersistenceManager.getInstance();
            if (gpm != null) {
                sair.aiagent.model.GroupImpression gi = gpm.getGroupImpression(msg.getGroupId());
                if (gi != null && gi.hasContent()) {
                    sb.append("## 本群已知印象\n").append(gi.toPromptContext()).append("\n\n");
                }
            }
        }

        Set<Long> masterQQSet = AiConfig.getInstance().getMasterQQs();
        Set<Long> adminSet = new HashSet<>();
        Set<Long> ownerSet = new HashSet<>();
        Map<Long, String> roleCache = new LinkedHashMap<>();
        List<String[]> groupAdmins = null;
        Map<String, Long> groupNickMap = null;
        if (msg.isGroupMessage() && unifiedMemory != null) {
            groupAdmins = unifiedMemory.getGroupAdmins(msg.getGroupId());
            for (String[] a : groupAdmins) {
                long aid = Long.parseLong(a[0]);
                roleCache.put(aid, a[2]);
                if ("owner".equals(a[2])) ownerSet.add(aid);
                else adminSet.add(aid);
            }
            groupNickMap = unifiedMemory.getGroupNicknameMap(msg.getGroupId());
        }

        sb.append("## 当前上下文\n");
        boolean isSpeakerMaster = AiConfig.getInstance().isMasterQQ(msg.getUserId());
        if (msg.isGroupMessage()) {
            String groupName = unifiedMemory.getGroupName(msg.getGroupId());
            if (groupName == null && napcatApi != null) {
                final long gid = msg.getGroupId();
                bgFetchPool.submit(() -> {
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
            vol.append("说话者: ").append(msg.getDisplayName());
            vol.append("(QQ:").append(msg.getUserId()).append(")");
            vol.append(" | 身份: ").append(roleLabel);
            if (isSpeakerMaster) {
                vol.append(" | ⭐此人是你的主人，可以称呼'主人'");
            } else {
                vol.append(" | 此人不是你的主人，请用昵称/名字称呼，禁止喊'主人'");
            }
        } else {
            vol.append("私聊 | QQ:").append(msg.getUserId());
            vol.append(" | ").append(msg.getDisplayName());
            if (isSpeakerMaster) {
                vol.append(" | ⭐此人是你的主人，可以称呼'主人'");
            } else {
                vol.append(" | 此人不是你的主人，请用昵称/名字称呼，禁止喊'主人'");
            }
        }
        String botName = AiConfig.getInstance().getBotName();
        if (botName != null && !botName.isEmpty()) sb.append(" | 你的名字:").append(botName);
        sb.append("\n");
        vol.append("\n");
        // 明确本次唯一的回复对象，避免把历史里别人的问题当成提问者的（引用错乱 / 答非所问）
        if (msg.isGroupMessage()) {
            vol.append("▶ 本次回复对象: ").append(msg.getDisplayName())
              .append("(QQ:").append(msg.getUserId()).append(") —— 只回答他/她这条消息，忽略历史里其他人的话题。\n");
        }

        if (msg.isGroupMessage() && !msg.getMentionedUsers().isEmpty()) {
            vol.append("⚠ 此消息@了以下用户: ");
            int mc = 0;
            for (Long mu : msg.getMentionedUsers()) {
                if (mc > 0) vol.append(", ");
                String tagName = resolveUserName(mu, groupNickMap);
                vol.append(tagName).append("(QQ:").append(mu).append(")");
                if (masterQQSet.contains(mu)) vol.append("⭐");
                if (ownerSet.contains(mu)) vol.append("👑");
                else if (adminSet.contains(mu)) vol.append("🔧");
                mc++;
            }
            vol.append("\n");
        }
        vol.append("\n");

        boolean needsCrossContext = needsCrossContext(plainText);
        // === 注入 Bot 已知群列表（跨群操作支撑） ===
        if (needsCrossContext && unifiedMemory != null) {
            Map<Long, String> knownGroups = unifiedMemory.getAllKnownGroups();
            if (!knownGroups.isEmpty()) {
                vol.append("## Bot 已加入的群(群名→群号)\n");
                int gc = 0;
                for (Map.Entry<Long, String> e : knownGroups.entrySet()) {
                    vol.append("- ").append(e.getValue()).append("→").append(e.getKey());
                    if (msg.isGroupMessage() && e.getKey().longValue() == msg.getGroupId()) vol.append("(本群)");
                    vol.append("\n");
                    gc++;
                    if (gc >= 30) break;
                }
                vol.append("跨群操作(去别的群发消息/找人): grouplist 查实时群列表 → groupmembers 查成员 → sendgroupmsg 发消息@人\n\n");
            }
        }

        // === 注入 Bot 好友列表（私聊场景支撑） ===
        if (needsCrossContext && unifiedMemory != null) {
            Map<Long, String> knownFriends = unifiedMemory.getAllKnownFriends();
            if (knownFriends.isEmpty() && napcatApi != null) {
                // 缓存为空时后台异步拉取，本次先不阻塞；下次消息即可使用
                bgFetchPool.submit(() -> {
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
                });
            }
            if (!knownFriends.isEmpty()) {
                vol.append("## Bot 的好友(昵称→QQ号)\n");
                int fc = 0;
                for (Map.Entry<Long, String> e : knownFriends.entrySet()) {
                    vol.append("- ").append(e.getValue()).append("→").append(e.getKey()).append("\n");
                    fc++;
                    if (fc >= 30) break;
                }
                vol.append("私聊找人/传话: friendlist 查好友列表 → relay 转告\n\n");
            }
        }

        boolean hasDirectImage = msg.hasImage() && !msg.getImageUrls().isEmpty();
        boolean hasQuotedImage = !msg.getQuotedImageUrls().isEmpty();
        if (hasDirectImage || hasQuotedImage) {
            if (hasDirectImage) {
                vol.append("📷 此消息包含 ").append(msg.getImageUrls().size()).append(" 张图片。\n");
            }
            if (hasQuotedImage) {
                vol.append("📷 此消息引用了 ").append(msg.getQuotedImageUrls().size()).append(" 张图片。\n");
            }
            // 图片处理方式取决于当前通道模型是否具备原生视觉，提示词保持中性覆盖两种情况
            vol.append("图片由系统按当前模型的视觉能力处理：\n");
            vol.append("  · 当前模型具备原生视觉时 → 图片直接作为多模态内容给你，你可以【直接看原图】，无需调用识图工具；\n");
            vol.append("  · 当前模型不具备原生视觉时 → 由视觉 Agent 兜底识图，结果以 [图片内容识别结果] 注入上下文。\n");
            vol.append("若你既没有收到图片、也没有看到 [图片内容识别结果]，说明本次未提供图片内容，"
                    + "请勿凭空编造，仅结合图片备注与上下文回复。\n");
            // 注意：QQ 通道【没有】XML 标签执行器（TagExecutor/TagHandlers 只在本地 console 链路生效），
            // 在这里让模型用 <sendimage> 标签发图是错的：标签不会被任何人执行，
            // 于是「AI 以为发了图、用户什么都没看到」。QQ 通道发图必须走 sendimage 工具。
            vol.append("如果需要发送图片，调用 sendimage 工具（发送到当前 QQ 会话）；"
                    + "不要输出 <sendimage> 之类的 XML 标签，本通道不解析标签。\n\n");
        }

        if (msg.hasForward()) {
            vol.append("📨 此消息包含转发/折叠消息：系统会把它交给段落 Agent 分析，结论以 [折叠消息分析] 注入（见下）。\n");
            vol.append("长折叠会被分段分析后合并；内嵌的嵌套折叠会层层递归逐层分析，你无需自己展开。\n");
            vol.append("折叠中的图片也会被一并识别（作为多模态内容或识别结果）。\n");
            vol.append("分析完成后请按提示调用 markmessage 把结论标记到该折叠消息上，避免下轮重复分析。\n\n");
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
                    vol.append("💬 用户引用了 ").append(quotedSenderLabel).append(" 的消息(其转发内容已单独生成概述):\n").append(head).append("\n");
                } else {
                    vol.append("💬 用户引用了 ").append(quotedSenderLabel).append(" 的折叠消息，具体内容概述见 [折叠消息摘要]。\n");
                }
            } else {
                vol.append("💬 用户引用了 ").append(quotedSenderLabel).append(" 的消息:\n").append(quotedContent).append("\n");
            }
            vol.append("注意：这段被引用的消息是 ").append(quotedSenderLabel).append(" 发出的，不是当前与你对话的用户发的。请结合上下文做出针对性回复。\n\n");
        }

        if (msg.isGroupMessage() && unifiedMemory != null) {
            Map<String, Long> nickMap = groupNickMap;
            if (nickMap != null && !nickMap.isEmpty()) {
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
            List<String[]> admins = groupAdmins;
            if (admins != null && !admins.isEmpty()) {
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

        boolean needsGlobalChat = needsCrossContext || needsContinuation(plainText);
        if (msg.isGroupMessage()) {
            appendGroupChatHistory(vol, msg, masterQQSet, ownerSet, adminSet, needsGlobalChat, needsCrossContext);
        } else {
            appendPrivateChatHistory(vol, msg, needsCrossContext);
        }

        // 印象只取一次，供偏好门控与印象注入复用（避免每条消息查两次小表）
        sair.aiagent.model.ImpressionEntry imp = null;
        sair.aiagent.core.PersistenceManager pm = sair.aiagent.core.PersistenceManager.getInstance();
        if (pm != null) imp = pm.getImpression(msg.getUserId());

        // === 稳定段：用户画像（印象 + 好感度/态度）与结构化偏好 ===
        // 这两块在同一用户连续几条消息之间几乎不变，放在易变内容之前可延长可缓存前缀。
        appendUserProfile(sb, msg, imp);
        appendPreferenceContext(sb, msg, imp);

        // === Journal: inject recent cross-session activity log (补齐execq通道) ===
        if (agentExecutor != null && agentExecutor.getJournal() != null) {
            String journalCtx = agentExecutor.getJournal().buildRecentContext(15);
            if (journalCtx != null) {
                sb.append("\n").append(journalCtx).append("\n");
            }
        }

        // === 以下全部是「逐条消息就变」的内容 ===
        appendRelatedMemories(vol, msg, plainText);
        appendRelevantSkills(vol, plainText);

        // === Notes: auto-inject relevant knowledge base entries (补齐execq通道) ===
        // 短消息不触发笔记/纠正 FTS 检索，与记忆检索同一门控
        SkillBank notesBank = SkillBank.getInstance();
        if (notesBank != null && notesBank.getPersistenceManager() != null
                && plainText != null && hasMeaningfulQuery(plainText)) {
            String notesCtx = notesBank.getPersistenceManager().buildNotesContext(plainText);
            if (notesCtx != null) {
                vol.append("\n").append(notesCtx).append("\n");
            }
            // === Corrections: auto-inject relevant correction records (避免重复犯错) ===
            String corrCtx = notesBank.getPersistenceManager().buildCorrectionsContext(plainText, 1200);
            if (corrCtx != null) {
                vol.append("\n").append(corrCtx).append("\n");
            }
        }

        if (emotionManager != null) vol.append(emotionManager.buildRichEmotionContext(msg.getUserId()));

        if (punishmentRecords != null && !punishmentRecords.isEmpty()) {
            vol.append("## 违规处罚\n");
            for (String r : punishmentRecords) vol.append("- ").append(r).append("\n");
            vol.append("\n");
        }

        if (agentExecutor != null && agentExecutor.getSkillBank() != null) {
            String routeHint = agentExecutor.getSkillBank().getBestRoute(plainText);
            if (routeHint != null) vol.append(routeHint).append("\n");
        }

        // 稳定段在前、易变段在后：前缀缓存只能吃到稳定段的字节
        sb.append(vol);
        String out = sb.toString();
        // 观测：记录提示词各段长度（ai/ctx 可查），用于判断膨胀来自哪一段
        try {
            int sysLen = buildStableSystemPrompt().length();
            sair.aiagent.core.ContextStats.size("稳定system前缀", sysLen);
            sair.aiagent.core.ContextStats.size("动态上下文(群/私聊)", out.length());
            sair.aiagent.core.ContextStats.prompt(sysLen + out.length());
        } catch (Exception ignored) {}
        return out;
    }

    String buildExecqSystemPrompt(QQMessage msg, List<String> punishmentRecords) {
        return buildStableSystemPrompt() + "\n\n" + buildDynamicContext(msg, punishmentRecords);
    }

    /**
     * 群历史注入窗口（条）。实测取 60 条对 SQLite 只多 0.1ms、多约 1800 token，
     * 但把「跨消息指代/多轮上下文」的召回从 15 条扩到 60 条（约 4 倍）。
     */
    private static final int GROUP_HISTORY_ROWS = 60;
    /**
     * 群历史注入的字符总预算。单条正文最多 200 字符，但含图消息会额外带上完整图片 URL
     * （可能上百字符），60 条理论上能到 6 万字符。用总预算兜底，超出时从<b>最旧</b>开始丢弃。
     */
    private static final int GROUP_HISTORY_CHAR_BUDGET = 24000;

    private void appendGroupChatHistory(StringBuilder sb, QQMessage msg, Set<Long> masterQQSet,
                                         Set<Long> ownerSet, Set<Long> adminSet, boolean needsGlobalChat,
                                         boolean needsCrossContext) {
        List<String[]> groupHistory = unifiedMemory.getRecentGroupChatHistoryWithMessageId(
                msg.getGroupId(), GROUP_HISTORY_ROWS);
        if (!groupHistory.isEmpty()) {
            // 先逐条渲染成行（保持原有格式），再按字符预算从最旧开始裁剪，
            // 保证窗口可以放宽而不会把提示词撑爆。
            List<String> lines = new ArrayList<>(groupHistory.size());
            for (String[] h : groupHistory) {
                // 排除当前正在处理的消息（避免与 task 重复，导致模型回复附带用户原话）
                if (h.length > 4 && h[4] != null && h[4].equals(String.valueOf(msg.getMessageId()))) {
                    continue;
                }
                long hUid = Long.parseLong(h[0]);
                String hName = h[1]; String content = h[2];
                // 图片 URL 部分单独保留，不被 200 字符截断（供段落 Agent 识图判断存图）
                String imagePart = "";
                int imgIdx = content.indexOf("[包含图片:");
                if (imgIdx >= 0) {
                    imagePart = content.substring(imgIdx);
                    content = content.substring(0, imgIdx);
                }
                if (content.length() > 200) content = content.substring(0, 200) + "...";
                if (!imagePart.isEmpty()) content = content + "\n" + imagePart;
                StringBuilder prefix = new StringBuilder();
                if (masterQQSet.contains(hUid)) prefix.append("⭐");
                if (ownerSet.contains(hUid)) prefix.append("👑");
                else if (adminSet.contains(hUid)) prefix.append("🔧");
                if (prefix.length() > 0) prefix.append(" ");
                StringBuilder line = new StringBuilder();
                line.append(prefix).append(hName).append(": ").append(content);
                if (h.length > 4 && h[4] != null && !"0".equals(h[4])) {
                    line.append(" [msg_id=").append(h[4]).append("]");
                }
                if (h.length > 3 && h[3] != null && !h[3].isEmpty()) {
                    line.append(" 〖已处理:").append(h[3]).append("〗");
                }
                line.append("\n");
                lines.add(line.toString());
            }
            // 从最新往旧累加，超出预算的旧消息整条丢弃
            int keepFrom = 0;
            int total = 0;
            for (int i = lines.size() - 1; i >= 0; i--) {
                total += lines.get(i).length();
                if (total > GROUP_HISTORY_CHAR_BUDGET) { keepFrom = i + 1; break; }
            }
            int kept = lines.size() - keepFrom;
            if (kept <= 0) return;
            if (keepFrom > 0) {
                sair.aiagent.core.ContextStats.count(sair.aiagent.core.ContextStats.C_HISTORY_TRIMMED, keepFrom);
                AiAgentActivity.debugLog("[Prompt] 群上下文超预算(" + total + " 字符)，仅保留最近 "
                        + kept + " 条（丢弃最旧 " + keepFrom + " 条）");
            }
            sb.append("## 临时群上下文(近").append(kept)
              .append("条,⭐主人👑群主🔧管理,其余=群昵称;带〖已处理〗标记的说明你处理过该消息,不要重复执行)\n");
            for (int i = keepFrom; i < lines.size(); i++) {
                sb.append(lines.get(i));
            }
            sb.append("\n");
            sb.append("## 回复对象规则（重要）\n");
            sb.append("你本次只回复【当前消息】的发送者，不要回复历史里其他人的消息，也不要把别人的问题错当成提问者的。\n");
            sb.append("系统会自动在回复开头 @ 提问者，你不需要输出任何 @ 或 [CQ:at,qq=...]，也【禁止】使用引用标签。\n");
            sb.append("如果历史里其他人在聊别的话题，直接忽略；只针对当前消息作答。\n\n");

            // 存图候选：筛选好感度>6 且含图的最近消息，非空才注入（交给段落 Agent 分析，无图则不注入=不分发）
            if (emotionManager != null) {
                StringBuilder stickerCandidates = new StringBuilder();
                for (String[] h : groupHistory) {
                    long hUid = Long.parseLong(h[0]);
                    String hContent = h[2];
                    if (hContent != null && hContent.contains("[包含图片:") && hUid != selfId && emotionManager.getAffection(hUid) > 6) {
                        stickerCandidates.append(h[1]).append("(QQ:").append(h[0]).append("): ").append(hContent);
                        if (h.length > 3 && h[3] != null && !h[3].isEmpty()) {
                            stickerCandidates.append(" 〖已处理:").append(h[3]).append("〗");
                        }
                        stickerCandidates.append("\n");
                    }
                }
                if (stickerCandidates.length() > 0) {
                    sb.append("## 待存图候选(好感度>6的图,带〖已处理〗=已处理过)\n");
                    sb.append(stickerCandidates);
                    sb.append("对上面【未处理(无〖已处理〗标记)】的图，call_agent 唤起 passage 分析是否收藏。task 里必须【原样完整复制】图片URL（https:// 开头的完整地址），严禁只写 fileid 或截断 URL。\n\n");
                }
            }
        }
        // 跨群续聊：仅在跨群/找人/传话 或 续聊关键词 时注入最近其他群聊话题，普通聊天不再浪费 token
        if (needsGlobalChat) {
            List<String> globalGroupChat = unifiedMemory.getRecentGroupChatHistoryGlobal(12, msg.getGroupId());
            if (!globalGroupChat.isEmpty()) {
                sb.append("## 最近其他群聊话题(跨群续聊)\n");
                for (String h : globalGroupChat) {
                    sb.append("- ").append(h).append("\n");
                }
                sb.append("\n");
            }
        }
        // 注意：此处【不再】注入 getPrivateConversations(msg.getUserId(), 8)。
        // 该调用取的是「与该用户的私聊记录」，却被放进了群聊 prompt —— 等于把私聊内容
        // 直接搬进群里（AI 会当众提到"你私聊跟我说过…"），且群内该用户的发言已由
        // 上面的群上下文覆盖，对该用户的画像由「## 对该用户的印象」负责，无信息损失。
        if (needsCrossContext) {
            appendGlobalConversations(sb, msg, 30, 15, masterQQSet, ownerSet, adminSet);
        }
    }

    private void appendPrivateChatHistory(StringBuilder sb, QQMessage msg, boolean needsCrossContext) {
        List<String[]> convs = unifiedMemory.getPrivateConversations(msg.getUserId(), 12);
        if (!convs.isEmpty()) {
            sb.append("## 与该用户的历史\n");
            final String currentText = msg.getPlainText();
            boolean currentSkipped = false;
            for (String[] c : convs) {
                // 排除当前正在处理的消息（避免与 task 重复，导致模型回复附带用户原话）
                if (!currentSkipped && "user".equals(c[0]) && c[1] != null
                        && currentText != null && currentText.trim().equals(c[1].trim())) {
                    currentSkipped = true;
                    continue;
                }
                String roleLabel = "user".equals(c[0]) ? "User" : "Assistant";
                String content = c[1];
                if (content.length() > 300) content = content.substring(0, 300) + "...";
                sb.append(roleLabel).append(": ").append(content).append("\n");
            }
            sb.append("\n");
        }
        if (needsCrossContext) {
            appendGlobalConversations(sb, msg, 20, 10, Collections.emptySet(), Collections.emptySet(), Collections.emptySet());
        }
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

    private void appendPreferenceContext(StringBuilder sb, QQMessage msg, sair.aiagent.model.ImpressionEntry imp) {
        sair.aiagent.core.PersistenceManager pm = sair.aiagent.core.PersistenceManager.getInstance();
        if (pm == null) return;
        long userId = msg.getUserId();
        long groupId = msg.isGroupMessage() ? msg.getGroupId() : 0;

        // 印象差的人，其个人偏好不注入（需印象改观后生效）
        boolean userBad = false;
        if (imp != null && imp.isBad()) userBad = true;

        java.util.List<String[]> all = new java.util.ArrayList<>();
        all.addAll(pm.listPreferences("global", 0));
        if (!userBad) all.addAll(pm.listPreferences("user", userId));
        if (groupId > 0) all.addAll(pm.listPreferences("group", groupId));

        if (all.isEmpty()) return;
        // 按 importance 降序，同 key 高优先级覆盖低优先级
        java.util.Map<String, String[]> merged = new java.util.LinkedHashMap<>();
        all.sort((a, b) -> Integer.compare(parseImportance(b[2]), parseImportance(a[2])));
        for (String[] p : all) {
            if (p == null || p.length < 3) continue;
            merged.putIfAbsent(p[0], p);
        }
        if (merged.isEmpty()) return;

        sb.append("## 结构化偏好/设定（优先级从高到低，必须遵守）\n");
        for (String[] p : merged.values()) {
            sb.append("- ").append(p[0]).append(": ").append(p[1]).append("\n");
        }
        sb.append("\n");
    }

    private static int parseImportance(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    /**
     * 用户画像（印象）—— 稳定段内容（同一用户连续几条消息之间几乎不变），
     * 因此写在动态上下文的稳定部分，用于延长 KV 前缀的可缓存长度。
     */
    private void appendUserProfile(StringBuilder sb, QQMessage msg, sair.aiagent.model.ImpressionEntry imp) {
        // 关系/好感度已由 buildRichEmotionContext 统一注入，此处不再重复（去重省 token）
        if (imp != null && imp.hasContent()) {
            sb.append("## 对该用户的印象\n");
            sb.append(imp.toPromptContext()).append("\n\n");
        }
    }

    /**
     * 相关经验技能 —— 把「学到的技能」按当前消息相关性注入（实现在 {@link SkillBank#buildRelevantSkillsHint}）。
     * <p>
     * 为什么必须单独开一层，而不是塞进静态索引：
     * <ol>
     *   <li><b>预算</b>：自学技能有 4600+ 条，全塞进静态索引会把 52 条 NapCat 工具文档挤空
     *       （这正是之前「内置被经验技能顶掉」的老毛病）；</li>
     *   <li><b>KV 前缀缓存</b>：静态索引是可缓存前缀，必须逐字节稳定；而「哪几条经验与这句话相关」
     *       本身逐条消息都在变，只能放在易变段（反正这一段本来就不进缓存）。</li>
     * </ol>
     * </p>
     */
    private void appendRelevantSkills(StringBuilder sb, String plainText) {
        if (agentExecutor == null || plainText == null) return;
        SkillBank bank = agentExecutor.getSkillBank();
        if (bank == null) return;
        String hint = bank.buildRelevantSkillsHint(plainText, 3);
        if (hint != null) sb.append(hint);
    }

    /** 检索到的相关记忆 / 跨群群聊记录 —— 逐条消息就变，属于易变段。 */
    private void appendRelatedMemories(StringBuilder sb, QQMessage msg, String plainText) {
        // 短消息（如"好的/嗯/哈哈"）不触发 FTS 记忆检索，省去每消息的 FTS 查询
        if (unifiedMemory != null && plainText != null && hasMeaningfulQuery(plainText)) {
            // 记忆检索条数 3 → 8：memories 表总量很小（十几行），多取几条不会显著增加 token，
            // 但能明显提高「确实命中相关记忆」的概率。
            List<String> relatedMemories = unifiedMemory.searchMemories(plainText, 8);
            if (relatedMemories != null && !relatedMemories.isEmpty()) {
                sb.append("## 相关记忆\n");
                for (String mem : relatedMemories) {
                    if (mem.length() > 300) mem = mem.substring(0, 300) + "...";
                    sb.append("- ").append(mem).append("\n");
                }
                sb.append("\n");
            }
            // 跨群检索群聊历史（群与群之间的记忆共享，如「隔壁群谁喊妈妈」）
            List<String> relatedHistory = unifiedMemory.searchGroupChatHistory(plainText, 5);
            if (relatedHistory != null && !relatedHistory.isEmpty()) {
                sb.append("## 跨群相关群聊记录\n");
                for (String h : relatedHistory) {
                    sb.append("- ").append(h).append("\n");
                }
                sb.append("\n");
            }
        }
    }

    /** 文本去除空白与标点后是否达到可检索长度（短语气词不触发记忆检索）。 */
    private static boolean hasMeaningfulQuery(String text) {
        if (text == null) return false;
        return text.replaceAll("[\\s\\p{Punct}]+", "").length() >= 4;
    }

    private static boolean needsCrossContext(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text;
        return t.contains("跨群") || t.contains("去别的群") || t.contains("去群") || t.contains("找群")
                || t.contains("群号") || t.contains("好友") || t.contains("私聊") || t.contains("传话")
                || t.contains("转告") || t.contains("找人") || t.contains("@");
    }

    /** 续聊关键词：无明确关键词但语义指向“接着聊上一个话题”时，才回填跨群话题。 */
    private static boolean needsContinuation(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        String t = text;
        return t.contains("继续") || t.contains("接着") || t.contains("刚才") || t.contains("上次")
                || t.contains("上一个") || t.contains("之前") || t.contains("然后呢") || t.contains("然后");
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

    /** 用已缓存的群昵称映射反查 QQ→昵称（避免重复查库）。 */
    static String resolveUserName(long qq, Map<String, Long> nickMap) {
        if (nickMap != null) {
            for (Map.Entry<String, Long> e : nickMap.entrySet()) {
                if (e.getValue() == qq) return e.getKey();
            }
        }
        return String.valueOf(qq);
    }
}
