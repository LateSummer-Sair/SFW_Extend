package sair.aiagent.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 对话消息模型 —— 一条对话记录。
 * <p>
 * 封装 OpenAI 兼容 API 的 messages 数组中单个元素的数据结构，
 * 包含角色(role)和内容(content)两个核心字段。
 * </p>
 *
 * <h3>角色说明</h3>
 * <ul>
 *   <li><b>system</b>  —— 系统提示词，定义AI行为边界</li>
 *   <li><b>user</b>    —— 用户消息</li>
 *   <li><b>assistant</b> —— AI回复</li>
 * </ul>
 *
 * <h3>多模态支持（v2.3）</h3>
 * <p>当消息包含图片时，使用 contentParts 字段存储 OpenAI Vision API 格式的内容数组：
 * [{type:"text", text:"..."}, {type:"image_url", image_url: {url:"..."}}]。</p>
 *
 * @see <a href="https://api-docs.deepseek.com/">DeepSeek API文档</a>
 */
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 2L;

    /** 消息角色：system / user / assistant */
    private final String role;

    /** 消息正文内容 */
    private final String content;

    /** 多模态内容部件列表（null=纯文本消息，非null=多模态消息） */
    private final List<Map<String, Object>> contentParts;

    /** 无参构造器，用于 JSON 反序列化 */
    private ChatMessage() {
        this.role = "user";
        this.content = "";
        this.contentParts = null;
    }

    /**
     * 构造一条纯文本对话消息。
     *
     * @param role    消息角色，不可为null
     * @param content 消息内容，不可为null
     */
    public ChatMessage(String role, String content) {
        this.role = (role != null) ? role : "user";
        this.content = (content != null) ? content : "";
        this.contentParts = null;
    }

    /**
     * 构造一条多模态对话消息（含图片）。
     * <p>contentParts 直接映射到 API 请求的 content 数组。</p>
     *
     * @param role         消息角色
     * @param content      纯文本内容（用于 estimateTokens 等兼容方法）
     * @param contentParts 多模态内容数组
     */
    public ChatMessage(String role, String content, List<Map<String, Object>> contentParts) {
        this.role = (role != null) ? role : "user";
        this.content = (content != null) ? content : "";
        this.contentParts = (contentParts != null && !contentParts.isEmpty()) ?
                Collections.unmodifiableList(new ArrayList<>(contentParts)) : null;
    }

    /**
     * 工厂方法：创建带图片的多模态用户消息。
     * <p>构建 Vision API content 数组：[text_part, image_part1, image_part2, ...]。</p>
     *
     * @param text      文本内容
     * @param imageUrls 图片 URL 列表（http/https URL 或 base64 data URI）
     * @return 多模态 ChatMessage
     */
    public static ChatMessage createMultimodal(String text, List<String> imageUrls) {
        List<Map<String, Object>> parts = new ArrayList<>();

        // 文本部分
        if (text != null && !text.isEmpty()) {
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", text);
            parts.add(textPart);
        }

        // 图片部分
        if (imageUrls != null) {
            for (String url : imageUrls) {
                if (url != null && !url.isEmpty()) {
                    Map<String, Object> imageUrlObj = new LinkedHashMap<>();
                    imageUrlObj.put("url", url);

                    Map<String, Object> imagePart = new LinkedHashMap<>();
                    imagePart.put("type", "image_url");
                    imagePart.put("image_url", imageUrlObj);

                    parts.add(imagePart);
                }
            }
        }

        return new ChatMessage("user", text, parts);
    }

    /** @return 消息角色 */
    public String getRole() {
        return role;
    }

    /** @return 消息内容 */
    public String getContent() {
        return content;
    }

    /** @return 是否为多模态消息（包含图片等非文本内容） */
    public boolean hasImages() {
        return contentParts != null && !contentParts.isEmpty();
    }

    /** @return 多模态内容部件列表（纯文本消息返回 null） */
    public List<Map<String, Object>> getContentParts() {
        return contentParts;
    }

    /**
     * 估算本条消息的 token 数量。
     * <p>
     * 简单估算策略：中文字符约0.6 token/字，英文约0.25 token/字。
     * 多模态消息中的图片按 85 token/张 估算（符合 OpenAI Vision 计费标准）。
     * 这不是精确计算，但足以用于对话历史的容量控制。
     * </p>
     *
     * @return 估算的 token 数
     */
    public int estimateTokens() {
        int tokens = 0;
        String textContent = content;
        if (textContent != null && !textContent.isEmpty()) {
            int chineseChars = 0;
            int otherChars = 0;
            for (char c : textContent.toCharArray()) {
                if (isCJK(c)) {
                    chineseChars++;
                } else {
                    otherChars++;
                }
            }
            tokens = (int) (chineseChars * 0.6 + otherChars * 0.25);
        }
        // 图片 token 估算：85 token/张
        if (contentParts != null) {
            for (Map<String, Object> part : contentParts) {
                if ("image_url".equals(part.get("type"))) {
                    tokens += 85;
                }
            }
        }
        return tokens;
    }

    /** 判断字符是否属于 CJK（中日韩统一表意文字）及日文假名区块 */
    private static boolean isCJK(char c) {
        // 使用 Unicode 码点范围判断，兼容 JDK 8
        // CJK Radicals Supplement: U+2E80–U+2EFF
        if (c >= 0x2E80 && c <= 0x2EFF) return true;
        // Kangxi Radicals: U+2F00–U+2FDF
        if (c >= 0x2F00 && c <= 0x2FDF) return true;
        // CJK Symbols and Punctuation: U+3000–U+303F
        if (c >= 0x3000 && c <= 0x303F) return true;
        // Hiragana: U+3040–U+309F
        if (c >= 0x3040 && c <= 0x309F) return true;
        // Katakana: U+30A0–U+30FF
        if (c >= 0x30A0 && c <= 0x30FF) return true;
        // CJK Strokes / Enclosed CJK: U+31C0–U+32FF
        if (c >= 0x31C0 && c <= 0x32FF) return true;
        // CJK Compatibility: U+3300–U+33FF
        if (c >= 0x3300 && c <= 0x33FF) return true;
        // CJK Extension A: U+3400–U+4DBF
        if (c >= 0x3400 && c <= 0x4DBF) return true;
        // CJK Unified Ideographs: U+4E00–U+9FFF
        if (c >= 0x4E00 && c <= 0x9FFF) return true;
        // CJK Compatibility Ideographs: U+F900–U+FAFF
        if (c >= 0xF900 && c <= 0xFAFF) return true;
        // Halfwidth Katakana: U+FF65–U+FF9F
        if (c >= 0xFF65 && c <= 0xFF9F) return true;
        // Fullwidth forms: U+FF00–U+FFEF
        if (c >= 0xFF00 && c <= 0xFFEF) return true;
        return false;
    }

    @Override
    public String toString() {
        String summary = "[" + role + "] " + (content != null && content.length() > 80 ? content.substring(0, 80) + "..." : content);
        if (contentParts != null && !contentParts.isEmpty()) {
            int imgCount = 0;
            for (Map<String, Object> p : contentParts) {
                if ("image_url".equals(p.get("type"))) imgCount++;
            }
            summary += " [+ " + imgCount + " image(s)]";
        }
        return summary;
    }
}
