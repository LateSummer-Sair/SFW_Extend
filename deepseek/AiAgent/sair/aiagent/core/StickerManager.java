package sair.aiagent.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.StickerEntry;

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
    private static final int MAX_STICKERS = 20;

    /** 语境匹配最低得分阈值（0.0~1.0），低于此分不发送 */
    private static final double MATCH_THRESHOLD = 0.15;

    /** 最低关键词重叠数（绝对数），低于此数即使比例高也不发送 */
    private static final int MIN_OVERLAP = 1;

    private PersistenceManager pm;
    private String dataDir;

    public void setPersistenceManager(PersistenceManager pm) {
        this.pm = pm;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    // ==================== 收集 ====================

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

        // 提取关键词
        String keywords = StickerEntry.extractKeywords(context);

        // 保存到数据库
        StickerEntry entry = pm.addSticker(imageUrl, null, context, keywords);
        if (entry != null) {
            AiAgentActivity.debugLog("[Sticker] collected #" + entry.getId()
                    + " keywords=" + (keywords.length() > 40 ? keywords.substring(0, 40) + "..." : keywords));
        }

        // 超过上限则清理
        cleanup();

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

        // 提取当前语境关键词
        String currentKw = StickerEntry.extractKeywords(currentContext);
        if (currentKw.isEmpty()) return null;
        String[] currentWords = currentKw.split("\\s+");

        // 计算每条表情包的关键词重叠得分
        StickerEntry best = null;
        double bestScore = 0;

        for (StickerEntry s : all) {
            String skw = s.getKeywords();
            if (skw == null || skw.isEmpty()) continue;

            String[] stickerWords = skw.split("\\s+");
            int overlap = 0;
            for (String cw : currentWords) {
                for (String sw : stickerWords) {
                    if (cw.equals(sw)) { overlap++; break; }
                }
            }

            if (overlap < MIN_OVERLAP) continue;

            // 得分 = 重叠词数 / 表情包关键词总数（Jaccard-like）
            double score = (double) overlap / Math.max(stickerWords.length, 1);

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
                    + " keywords=" + (best.getKeywords().length() > 40
                        ? best.getKeywords().substring(0, 40) + "..." : best.getKeywords()));
            return best;
        }

        AiAgentActivity.debugLog("[Sticker] no match (bestScore="
                + String.format("%.2f", bestScore) + " < " + MATCH_THRESHOLD + ")");
        return null;
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

    // ==================== 清理 ====================

    /**
     * 清理超出上限的表情包，保留最近 MAX_STICKERS 条。
     */
    public synchronized void cleanup() {
        if (pm == null) return;
        pm.trimStickers(MAX_STICKERS);
    }

    /**
     * 清空所有表情包。
     */
    public synchronized void clearAll() {
        if (pm == null) return;
        // 逐条删除（简单实现）
        List<StickerEntry> all = pm.listAllStickers();
        for (StickerEntry s : all) {
            pm.removeSticker(s.getId());
        }
    }
}
