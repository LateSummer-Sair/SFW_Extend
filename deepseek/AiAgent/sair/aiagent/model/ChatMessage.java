package sair.aiagent.model;

import java.io.Serializable;

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
 * @see <a href="https://api-docs.deepseek.com/">DeepSeek API文档</a>
 */
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 消息角色：system / user / assistant */
    private final String role;

    /** 消息正文内容 */
    private final String content;

    /** 无参构造器，用于 JSON 反序列化 */
    private ChatMessage() {
        this.role = "user";
        this.content = "";
    }

    /**
     * 构造一条对话消息。
     *
     * @param role    消息角色，不可为null
     * @param content 消息内容，不可为null
     */
    public ChatMessage(String role, String content) {
        this.role = (role != null) ? role : "user";
        this.content = (content != null) ? content : "";
    }

    /** @return 消息角色 */
    public String getRole() {
        return role;
    }

    /** @return 消息内容 */
    public String getContent() {
        return content;
    }

    /**
     * 估算本条消息的 token 数量。
     * <p>
     * 简单估算策略：中文字符约0.6 token/字，英文约0.25 token/字。
     * 这不是精确计算，但足以用于对话历史的容量控制。
     * </p>
     *
     * @return 估算的 token 数
     */
    public int estimateTokens() {
        if (content == null || content.isEmpty()) return 0;
        int chineseChars = 0;
        int otherChars = 0;
        for (char c : content.toCharArray()) {
            if (isCJK(c)) {
                chineseChars++;
            } else {
                otherChars++;
            }
        }
        return (int) (chineseChars * 0.6 + otherChars * 0.25);
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
        return "[" + role + "] " + (content.length() > 80 ? content.substring(0, 80) + "..." : content);
    }
}
