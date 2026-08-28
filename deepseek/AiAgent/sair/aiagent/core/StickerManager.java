package sair.aiagent.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import sair.aiagent.AiAgentActivity;
import java.io.*;
import java.net.*;
import sair.aiagent.model.StickerEntry;
import sair.aiagent.model.ChatMessage;
import sair.aiagent.onebot.ImageDownloader;
import sair.aiagent.onebot.NapCatApi;
import java.util.Collections;

/**
 * 表情包管理器 —— 收集、匹配、清理。
 * <p>
 * QQ Bot 自动从群聊中收集表情包图片，记录产生语境。
 * 当 AI 回复时，根据当前对话语境匹配最合适的表情包，
 * 若无合适匹配则不发送。
 * </p>
 *
 * <h3>工作流程</h3>
 * <ol>
 *   <li><b>收集</b>：QQ 消息中含有图片时，调用 {@link #collect} 收集</li>
 *   <li><b>匹配</b>：AI 准备回复时，调用 {@link #findBestMatch} 检索</li>
 *   <li><b>清理</b>：超过上限时自动淘汰最旧的</li>
 * </ol>
 */
public class StickerManager {

    /** 最多保留表情包数量 */
    private static final int MAX_STICKERS = 15;

    /** 表情包有效期（3天），超期自动清除 */
    private static final long STICKER_TTL_MS = 3 * 24 * 3600_000L;

    /** 存图冷却时间（2分钟）：冷却中拒绝存图，减少视觉模型调用 */
    private static final long COLLECT_COOLDOWN_MS = 2 * 60 * 1000L;

    /** 语境匹配最低得分阈值（0.0~1.0），低于此分不发送 */
    private static final double MATCH_THRESHOLD = 0.4;

    private PersistenceManager pm;
    private String dataDir;
    private final java.util.Random random = new java.util.Random();
    /** 视觉审查用 DeepSeekClient（注入，用于违规内容鉴定） */
    private volatile DeepSeekClient client;
    /** NapCat API（注入，用于腾讯内网图片下载转 base64 的兜底） */
    private volatile NapCatApi napcatApi;
    /** 自动清理守护线程 */
    private volatile Thread cleanupThread;
    /** 上次存图触发时间戳（2分钟冷却） */
    private volatile long lastCollectTime = 0;
    /** 图片视觉分析内容描述缓存（原始URL → 内容描述），供识图复用，避免同一张图重复调视觉模型 */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> VISION_DESC_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 视觉描述缓存最大条目数 */
    private static final int VISION_CACHE_MAX = 100;
    /** 视觉审查完整结果缓存（原始URL → 四段判定结果），同一张图只调一次视觉模型 */
    private static final java.util.concurrent.ConcurrentHashMap<String, ReviewResult> REVIEW_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 视觉审查结果缓存最大条目数 */
    private static final int REVIEW_CACHE_MAX = 300;

    public void setPersistenceManager(PersistenceManager pm) {
        this.pm = pm;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    /** 注入视觉审查用的 DeepSeekClient */
    public void setDeepSeekClient(DeepSeekClient client) { this.client = client; }

    /** 注入 NapCat API（用于腾讯内网图片下载转 base64 的兜底） */
    public void setNapcatApi(NapCatApi api) { this.napcatApi = api; }

    // ==================== 收集 ====================

    /**
     * 从本地文件路径收集表情包（QQ图片已下载到本地后调用）。
     */
    public synchronized StickerEntry collectFromLocal(String localPath, String context) {
        if (pm == null || localPath == null || localPath.isEmpty()) return null;
        String keywords = StickerEntry.extractKeywords(context);
        StickerEntry entry = pm.addSticker(localPath, localPath, context, keywords, "");
        if (entry != null) {
            AiAgentActivity.debugLog("[Sticker] collected local #" + entry.getId());
        }
        cleanup();
        return entry;
    }

    /**
     * 收集一个表情包。
     * <p>从 QQ 消息中提取到的图片，连同当前语境一起存储。
     * 超过上限时自动淘汰最旧的。</p>
     *
     * @param imageUrl QQ 消息中的图片 URL（CQ 码中的 url 字段）
     * @param context  产生该表情时的周围对话文本
     * @return 收集到的 StickerEntry，失败返回 null
     */
    public synchronized StickerEntry collect(String imageUrl, String context) {
        if (pm == null || imageUrl == null || imageUrl.trim().isEmpty()) return null;

        // 0. 冷却检查（2分钟）：冷却中拒绝存图，且不调用视觉模型
        long now = System.currentTimeMillis();
        if (now - lastCollectTime < COLLECT_COOLDOWN_MS) {
            AiAgentActivity.debugLog("[Sticker] collect skipped (cooldown)");
            return null;
        }
        // 1. 先清理过期表情包（3天TTL），腾出空位
        cleanupExpired();

        // 2. 达到上限则不存储、也不调视觉模型审查（减少视觉模型调用）
        if (pm.stickerCount() >= MAX_STICKERS) {
            AiAgentActivity.debugLog("[Sticker] collect skipped (full " + MAX_STICKERS + "/" + MAX_STICKERS + ", waiting for expired cleanup)");
            return null;
        }

        // 3. 下载图片到本地
        String[] dl = downloadToLocalWithRemark(imageUrl);
        if (dl == null) {
            AiAgentActivity.debugLog("[Sticker] collect skipped (download failed): " + imageUrl);
            return null;
        }
        String localPath = dl[0];

        // 4. 视觉模型审查违规内容（政治敏感/成人等），违规则不存储
        if (!reviewSafe(imageUrl)) {
            AiAgentActivity.debugLog("[Sticker] collect skipped (violating content or review failed): " + imageUrl);
            deleteLocalFile(localPath);
            return null;
        }

        // 5. 存储（匹配 key 优先用视觉模型得到的图片内容描述）
        String keywords = buildMatchKey(context, imageUrl);
        String remark = dl[1] != null ? dl[1] : "";
        StickerEntry entry = pm.addSticker(imageUrl, localPath, context, keywords, remark);
        if (entry != null) {
            AiAgentActivity.debugLog("[Sticker] collected #" + entry.getId()
                    + " keywords=" + (keywords.length() > 40 ? keywords.substring(0, 40) + "..." : keywords));
        }

        return entry;
    }

    // ==================== 匹配 ====================

    /**
     * 根据当前语境查找最匹配的表情包。
     * <p>算法：提取当前语境关键词 → 逐条计算与存储表情包的关键词重叠度 →
     * 按得分排序 → 超过阈值则返回最优，否则返回 null。</p>
     *
     * @param currentContext 当前对话语境文本
     * @return 最匹配的 StickerEntry，无合适匹配返回 null
     */
    public synchronized StickerEntry findBestMatch(String currentContext) {
        if (pm == null || currentContext == null || currentContext.trim().isEmpty()) return null;

        List<StickerEntry> all = pm.listAllStickers();
        if (all.isEmpty()) return null;

        StickerEntry best = null;
        double bestScore = 0;

        for (StickerEntry s : all) {
            String target = s.getKeywords();
            if (target == null || target.isEmpty()) continue;

            // 匹配得分：当前语境 vs 表情包的视觉内容描述（短查询字符命中率 / 长查询 bigram Dice）
            double score = matchScore(currentContext, target);

            // 近期收集的表情包加权（1 天内 +10%）
            long age = System.currentTimeMillis() - s.getTimestamp();
            if (age < 24 * 3600_000L) score *= 1.10;

            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }

        if (best != null && bestScore >= MATCH_THRESHOLD) {
            // 记录使用
            pm.incrementStickerUsage(best.getId());
            best.incrementUsage();
            AiAgentActivity.debugLog("[Sticker] matched #" + best.getId()
                    + " score=" + String.format("%.2f", bestScore)
                    + " desc=" + (best.getKeywords().length() > 40
                        ? best.getKeywords().substring(0, 40) + "..." : best.getKeywords()));
            return best;
        }

        AiAgentActivity.debugLog("[Sticker] no match (bestScore="
                + String.format("%.2f", bestScore) + " < " + MATCH_THRESHOLD + ")");
        return null;
    }

    // ==================== 匹配算法 ====================

    /** 构建匹配 key：优先用视觉模型得到的图片内容描述，缺失时回退语境关键词。 */
    private String buildMatchKey(String context, String imageUrl) {
        String visionDesc = getCachedDescription(imageUrl);
        if (visionDesc != null && !visionDesc.trim().isEmpty()) {
            return visionDesc.trim();
        }
        return StickerEntry.extractKeywords(context);
    }

    /** 归一化匹配文本：仅保留中文/英文/数字，转小写。 */
    private static String normalizeMatch(String s) {
        if (s == null) return "";
        return s.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9]", "").toLowerCase();
    }

    /** 计算 query 与 target 的匹配得分（0~1）。短查询用字符命中率，长查询用字符 bigram Dice 系数。 */
    private static double matchScore(String query, String target) {
        String q = normalizeMatch(query);
        String t = normalizeMatch(target);
        if (q.isEmpty() || t.isEmpty()) return 0;

        // 短查询（≤2 字符）：字符命中率，如「猫」命中「一只猫在招手」
        if (q.length() <= 2) {
            int hit = 0;
            for (int i = 0; i < q.length(); i++) {
                if (t.indexOf(q.charAt(i)) >= 0) hit++;
            }
            return hit / (double) q.length();
        }

        // 长查询：字符 bigram Dice 系数
        java.util.Set<String> qb = charBigrams(q);
        java.util.Set<String> tb = charBigrams(t);
        if (qb.isEmpty() || tb.isEmpty()) return 0;
        int inter = 0;
        for (String g : qb) if (tb.contains(g)) inter++;
        return 2.0 * inter / (qb.size() + tb.size());
    }

    /** 提取字符 bigram 集合（长度 1 时退化为单字符）。 */
    private static java.util.Set<String> charBigrams(String s) {
        java.util.Set<String> set = new java.util.HashSet<>();
        if (s.length() == 1) { set.add(s); return set; }
        for (int i = 0; i < s.length() - 1; i++) {
            set.add(s.substring(i, i + 2));
        }
        return set;
    }

    /**
     * 随机返回一条库存表情包（用于 execq 随机触发，不依赖语义匹配）。
     * <p>从所有拥有有效图片的表情包中随机挑一条，并记录使用次数。</p>
     *
     * @return 随机选中的 StickerEntry，库存为空或无有效图片时返回 null
     */
    public synchronized StickerEntry randomSticker() {
        if (pm == null) return null;
        List<StickerEntry> all = pm.listAllStickers();
        if (all.isEmpty()) return null;

        List<StickerEntry> valid = new ArrayList<>();
        for (StickerEntry s : all) {
            if (s.getImageUrl() != null && !s.getImageUrl().isEmpty()) valid.add(s);
        }
        if (valid.isEmpty()) return null;

        StickerEntry picked = valid.get(random.nextInt(valid.size()));
        pm.incrementStickerUsage(picked.getId());
        picked.incrementUsage();
        AiAgentActivity.debugLog("[Sticker] random picked #" + picked.getId());
        return picked;
    }

    // ==================== 清单 ====================

    /**
     * 构建表情包库存清单文本，供 AI 通过系统提示词了解有哪些表情包可用。
     * <p>返回格式：每行 "[#id] 关键词 | 使用N次"</p>
     */
    public synchronized String buildInventoryForAi() {
        if (pm == null) return "";
        List<StickerEntry> all = pm.listAllStickers();
        if (all.isEmpty()) return "(empty - no stickers collected yet)";

        StringBuilder sb = new StringBuilder();
        sb.append("Available stickers (").append(all.size()).append(" total):\n");
        for (StickerEntry s : all) {
            String kw = s.getKeywords();
            if (kw == null || kw.isEmpty()) continue;
            if (kw.length() > 50) kw = kw.substring(0, 50) + "...";
            sb.append("  [#").append(s.getId()).append("] ")
              .append(kw)
              .append(" | used:").append(s.getUsageCount()).append("x\n");
        }
        return sb.toString();
    }

    /**
     * 获取表情包数量。
     */
    public synchronized int count() {
        if (pm == null) return 0;
        return pm.stickerCount();
    }


    /**
     * Download sticker image to local storage, compute remark, and return {localPath, remark}.
     * Used so the sticker CQ code always references a valid local file, and the image's
     * persistent remark (keyed by MD5) follows the image into long-term storage.
     */
    private String[] downloadToLocalWithRemark(String imageUrl) {
        if (dataDir == null || imageUrl == null || imageUrl.isEmpty()) return null;
        try {
            File stickerDir = new File(dataDir, "stickers");
            stickerDir.mkdirs();
            String name = "sticker_" + Math.abs(imageUrl.hashCode());
            // Try to keep extension
            int qi = imageUrl.indexOf('?');
            String cleanUrl = qi > 0 ? imageUrl.substring(0, qi) : imageUrl;
            int dot = cleanUrl.lastIndexOf('.');
            if (dot > 0 && dot < cleanUrl.length() - 1) {
                String ext = cleanUrl.substring(dot);
                if (ext.length() <= 5) name += ext;
            }
            File outFile = new File(stickerDir, name + ".png");
            if (outFile.exists() && outFile.length() > 0) {
                return new String[]{outFile.getAbsolutePath(), ""};
            }

            URL url = new URL(imageUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            InputStream is = conn.getInputStream();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                byte[] buf = new byte[4096]; int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            } finally {
                try { is.close(); } catch (Exception ignored) {}
                conn.disconnect();
            }

            byte[] bytes = baos.toByteArray();

            // 计算图片 MD5 并查持久化注释（注释随图进入长期存储）
            String remark = "";
            try {
                String md5 = sair.aiagent.onebot.ImageRecognizer.md5(bytes);
                String persisted = pm != null ? pm.getImageRemark(md5) : null;
                if (persisted != null && !persisted.isEmpty()) remark = persisted;
            } catch (Exception ignored) {}

            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(bytes);
            }
            return new String[]{outFile.getAbsolutePath(), remark};
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Sticker] download failed: " + e.getMessage());
            return null;
        }
    }


    // ==================== 清理 ====================

    /**
     * 清理超出上限的表情包，保留最近 MAX_STICKERS 条。
     */
    public synchronized void cleanup() {
        if (pm == null) return;
        pm.trimStickers(MAX_STICKERS);
    }

    /** 视觉审查提示词：一次性完成「表情数量 + 图中文字 + 内容描述 + 安全判定」，供识图复用（减少视觉模型调用） */
    private static final String REVIEW_PROMPT =
            "你是图片内容审查员，请一次性完成四项任务：\n"
            + "1. 统计图片中的人脸/面部表情数量（真人、卡通、动物拟人等均可）。回答「无」「一张」或「多张」。\n"
            + "2. 只转写图中出现的文字（不要描述图片，只写图中文字；若无文字则写「无」）。\n"
            + "3. 客观描述图片内容（图片类型、主体、场景等）。\n"
            + "4. 安全判定：判断是否含违规内容（政治敏感、成人色情、暴力血腥、游戏截图等）。\n\n"
            + "请严格按以下四行格式回答：\n"
            + "表情数量：<无 / 一张 / 多张>\n"
            + "图中文字：<图中文字，无则写「无」>\n"
            + "内容描述：<图片内容描述>\n"
            + "安全判定：<违规 或 正常>";

    /** 从文本中提取 startKey 之后、endKey 之前的片段（跳过全角/半角冒号与空格）。 */
    private static String extractBetween(String text, String startKey, String endKey) {
        if (text == null) return null;
        int s = text.indexOf(startKey);
        if (s < 0) return null;
        s += startKey.length();
        while (s < text.length() && (text.charAt(s) == '：' || text.charAt(s) == ':' || text.charAt(s) == ' ')) s++;
        int e = text.indexOf(endKey, s);
        String seg = e > s ? text.substring(s, e) : text.substring(s);
        seg = seg.trim();
        return seg.isEmpty() ? null : seg;
    }

    /** 从文本中提取 key 之后的片段（跳过全角/半角冒号与空格）。 */
    private static String extractAfter(String text, String key) {
        if (text == null) return null;
        int i = text.indexOf(key);
        if (i < 0) return null;
        i += key.length();
        while (i < text.length() && (text.charAt(i) == '：' || text.charAt(i) == ':' || text.charAt(i) == ' ')) i++;
        String seg = text.substring(i).trim();
        return seg.isEmpty() ? null : seg;
    }

    /** 统计有效字符数（去掉空白与常见标点，中文/英文/数字各算 1 字符）。 */
    private static int countChars(String s) {
        if (s == null) return 0;
        return s.replaceAll("[\\s，。！？、；：\"'“”‘’（）()【】\\[\\]{}<>]", "").length();
    }

    /** 视觉审查四段结果（表情数量/图中文字/内容描述/安全判定），缓存复用避免重复调视觉模型。 */
    private static final class ReviewResult {
        final String face;
        final String text;
        final String description;
        final String verdict;
        /** 完整原始回复，verdict 缺失时回退判定违规 */
        final String full;
        ReviewResult(String face, String text, String description, String verdict, String full) {
            this.face = face;
            this.text = text;
            this.description = description;
            this.verdict = verdict;
            this.full = full;
        }
    }

    /** 缓存图片内容描述（供识图复用，减少视觉模型重复调用）。 */
    private static void cacheDescription(String imageUrl, String description) {
        if (imageUrl == null || imageUrl.isEmpty() || description == null || description.isEmpty()) return;
        if (VISION_DESC_CACHE.size() >= VISION_CACHE_MAX) {
            synchronized (VISION_DESC_CACHE) {
                if (VISION_DESC_CACHE.size() >= VISION_CACHE_MAX) {
                    java.util.Iterator<String> it = VISION_DESC_CACHE.keySet().iterator();
                    int remove = VISION_DESC_CACHE.size() / 2;
                    for (int i = 0; i < remove && it.hasNext(); i++) { it.next(); it.remove(); }
                }
            }
        }
        VISION_DESC_CACHE.put(imageUrl, description);
    }

    /** 获取缓存的图片内容描述（供识图复用）。 */
    public static String getCachedDescription(String imageUrl) {
        return imageUrl == null ? null : VISION_DESC_CACHE.get(imageUrl);
    }

    /** 缓存视觉审查完整结果（同一张图只调一次视觉模型）。 */
    private static void cacheReview(String imageUrl, ReviewResult rr) {
        if (imageUrl == null || imageUrl.isEmpty() || rr == null) return;
        if (REVIEW_CACHE.size() >= REVIEW_CACHE_MAX) {
            synchronized (REVIEW_CACHE) {
                if (REVIEW_CACHE.size() >= REVIEW_CACHE_MAX) {
                    java.util.Iterator<String> it = REVIEW_CACHE.keySet().iterator();
                    int remove = REVIEW_CACHE.size() / 2;
                    for (int i = 0; i < remove && it.hasNext(); i++) { it.next(); it.remove(); }
                }
            }
        }
        REVIEW_CACHE.put(imageUrl, rr);
    }

    /**
     * 调用视觉模型审查图片是否含违规内容。
     * @return true=安全可存储，false=违规或审查失败（不存储）
     */
    private boolean reviewSafe(String imageUrl) {
        if (client == null) return true; // 未注入审查能力则放行
        // 触发视觉审查：更新冷却时间（视觉模型调用或缓存命中后都进入冷却，减少调用频率）
        lastCollectTime = System.currentTimeMillis();
        try {
            // 0. 缓存短路：同一张图只调一次视觉模型，命中则复用判定结果
            ReviewResult cached = REVIEW_CACHE.get(imageUrl);
            if (cached != null) {
                AiAgentActivity.debugLog("[Sticker] review: cache hit -> " + imageUrl);
                return evaluateReview(cached, imageUrl);
            }

            String visionImage = ImageDownloader.resolveImageForVision(imageUrl, napcatApi);
            if (ImageDownloader.isOversizeResult(visionImage)) {
                AiAgentActivity.debugLog("[Sticker] review: image oversize 3MB, reject -> " + imageUrl);
                return false;
            }
            if (visionImage == null) {
                AiAgentActivity.debugLog("[Sticker] review: image resolve failed, treat as unsafe");
                return false;
            }
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(ChatMessage.createMultimodal(REVIEW_PROMPT, Collections.singletonList(visionImage)));
            String result = client.chatSync(msgs, AiConfig.getInstance().getVisionModel());
            if (result == null || result.trim().isEmpty()) {
                AiAgentActivity.debugLog("[Sticker] review: empty result, treat as unsafe");
                return false;
            }
            String r = result.trim();

            // 解析四段：表情数量 / 图中文字 / 内容描述 / 安全判定
            String face = extractBetween(r, "表情数量", "图中文字");
            String text = extractBetween(r, "图中文字", "内容描述");
            String description = extractBetween(r, "内容描述", "安全判定");
            String verdict = extractAfter(r, "安全判定");

            ReviewResult rr = new ReviewResult(face, text, description, verdict, r);
            cacheReview(imageUrl, rr);
            return evaluateReview(rr, imageUrl);
        } catch (Exception e) {
            AiAgentActivity.debugLog("[Sticker] review failed: " + e.getMessage());
            return false;
        }
    }

    /** 依据视觉审查四段结果判定是否可存储：仅一张脸 + 文字≤15字 + 无违规。 */
    private boolean evaluateReview(ReviewResult rr, String imageUrl) {
        String face = rr.face;
        String text = rr.text;
        String description = rr.description;
        String verdict = rr.verdict;

        // 缓存内容描述（无论存图与否都缓存，供 FunctionCallingBridge 识图复用）
        if (description != null && !description.isEmpty()) {
            cacheDescription(imageUrl, description);
        }

        // 1. 仅一张脸部表情才存（无 / 多张都拒绝）
        if (face == null || !face.contains("一张")) {
            AiAgentActivity.debugLog("[Sticker] review: not exactly one face -> skip");
            return false;
        }

        // 2. 文字不超过 15 字才存
        if (text != null && !text.trim().isEmpty() && !"无".equals(text.trim())) {
            if (countChars(text) > 15) {
                AiAgentActivity.debugLog("[Sticker] review: too much text -> skip");
                return false;
            }
        }

        // 3. 合法性判定（含游戏截图排除）；verdict 缺失时回退用完整回复判定
        boolean violating;
        if (verdict != null && !verdict.isEmpty()) {
            violating = verdict.contains("违规") || verdict.contains("色情") || verdict.contains("成人")
                    || verdict.contains("敏感") || verdict.contains("政治") || verdict.contains("暴力")
                    || verdict.contains("游戏");
        } else {
            violating = rr.full != null && (rr.full.contains("违规") || rr.full.contains("色情")
                    || rr.full.contains("成人") || rr.full.contains("敏感") || rr.full.contains("政治")
                    || rr.full.contains("暴力") || rr.full.contains("游戏"));
        }

        AiAgentActivity.debugLog("[Sticker] review: " + (violating ? "VIOLATING -> skip" : "SAFE -> store"));
        return !violating;
    }

    /**
     * 清理过期表情包（超过 STICKER_TTL_MS=3天），返回清理数量。
     */
    public synchronized int cleanupExpired() {
        if (pm == null) return 0;
        List<StickerEntry> all = pm.listAllStickers();
        long now = System.currentTimeMillis();
        int removed = 0;
        for (StickerEntry s : all) {
            if (now - s.getTimestamp() > STICKER_TTL_MS) {
                pm.removeSticker(s.getId());
                deleteLocalFile(s.getFilePath());
                removed++;
                AiAgentActivity.debugLog("[Sticker] expired removed #" + s.getId());
            }
        }
        return removed;
    }

    /** 删除本地表情包文件（忽略异常） */
    private void deleteLocalFile(String path) {
        if (path == null || path.isEmpty()) return;
        try {
            File f = new File(path);
            if (f.exists() && f.isFile()) f.delete();
        } catch (Exception ignored) {}
    }

    /**
     * 启动自动清理守护线程：每小时清理一次过期表情包。
     * 全自动，无需人工干预（除非用 ai/clearstickers 命令手动清空）。
     */
    public synchronized void startAutoCleanup() {
        if (cleanupThread != null) return;
        cleanupThread = sair.aiagent.core.ThreadManager.getInstance().newDaemonThread("StickerCleanup", new Runnable() {
            public void run() {
                while (true) {
                    try { Thread.sleep(60 * 60 * 1000L); } catch (InterruptedException e) { break; }
                    try {
                        int removed = cleanupExpired();
                        if (removed > 0) AiAgentActivity.debugLog("[Sticker] auto cleanup removed " + removed + " expired");
                    } catch (Exception ignored) {}
                }
            }
        });
        cleanupThread.start();
    }

    /**
     * 清空所有表情包（数据库记录 + 本地图片文件）。
     */
    public synchronized void clearAll() {
        if (pm == null) return;
        // 删除本地文件
        if (dataDir != null) {
            try {
                File dir = new File(dataDir, "stickers");
                if (dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile()) f.delete();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        // 删除数据库记录
        List<StickerEntry> all = pm.listAllStickers();
        for (StickerEntry s : all) {
            pm.removeSticker(s.getId());
        }
    }
}
