package sair.scq.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户信息模型 —— 存储用户的所有属性和状态。
 * 
 * <h3>角色定义</h3>
 * SUPER_ADMIN: 超级管理员（首个注册用户），可创建群组/定义管理员/自定义UID
 * ADMIN: 管理员，可管理群组（禁言/审核/踢人）
 * MEMBER: 普通成员
 */
public class UserInfo {

    /** 用户角色枚举 */
    public enum Role {
        SUPER_ADMIN, ADMIN, MEMBER;

        public static Role fromString(String s) {
            if (s == null) return MEMBER;
            try { return valueOf(s.toUpperCase()); } catch (Exception e) { return MEMBER; }
        }
    }

    /** 唯一ID（纯数字，超管可自定义） */
    private long uid;
    /** 用户名 */
    private String username;
    /** 密码哈希（SHA-256） */
    private String passwordHash;
    /** 用户身份 */
    private Role role = Role.MEMBER;
    /** 个性签名 */
    private String signature = "";
    /** 头像文件路径 */
    private String avatarPath = "";
    /** 登录IP地址 */
    private String ip = "";
    /** 好友UID列表 */
    private List<Long> contacts = new ArrayList<Long>();
    /** 加入的群组GID列表 */
    private List<Long> groups = new ArrayList<Long>();
    /** 是否在线 */
    private boolean isOnline = false;

    // ==================== Getter / Setter ====================

    public long getUid() { return uid; }
    public void setUid(long uid) { this.uid = uid; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public String getAvatarPath() { return avatarPath; }
    public void setAvatarPath(String avatarPath) { this.avatarPath = avatarPath; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public List<Long> getContacts() { return contacts; }
    public void setContacts(List<Long> contacts) { this.contacts = contacts; }

    public List<Long> getGroups() { return groups; }
    public void setGroups(List<Long> groups) { this.groups = groups; }

    public boolean isOnline() { return isOnline; }
    public void setOnline(boolean online) { isOnline = online; }

    // ==================== 便捷方法 ====================

    /** 添加好友 */
    public boolean addContact(long contactUID) {
        if (!contacts.contains(contactUID)) {
            contacts.add(contactUID);
            return true;
        }
        return false;
    }

    /** 移除好友 */
    public boolean removeContact(long contactUID) {
        return contacts.remove((Long) contactUID);
    }

    /** 加入群组 */
    public boolean joinGroup(long gid) {
        if (!groups.contains(gid)) {
            groups.add(gid);
            return true;
        }
        return false;
    }

    /** 退出群组 */
    public boolean leaveGroup(long gid) {
        return groups.remove((Long) gid);
    }

    /** 是否是管理员及以上 */
    public boolean isAdminOrAbove() {
        return role == Role.SUPER_ADMIN || role == Role.ADMIN;
    }

    /** 是否是超级管理员 */
    public boolean isSuperAdmin() {
        return role == Role.SUPER_ADMIN;
    }

    // ==================== JSON序列化(手动拼接) ====================

    public String toJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"uid\":").append(uid);
        sb.append(",\"username\":\"").append(escape(username)).append("\"");
        sb.append(",\"passwordHash\":\"").append(escape(passwordHash)).append("\"");
        sb.append(",\"role\":\"").append(role.name()).append("\"");
        sb.append(",\"signature\":\"").append(escape(signature)).append("\"");
        sb.append(",\"avatarPath\":\"").append(escape(avatarPath)).append("\"");
        sb.append(",\"ip\":\"").append(escape(ip)).append("\"");
        sb.append(",\"contacts\":").append(longListToJson(contacts));
        sb.append(",\"groups\":").append(longListToJson(groups));
        sb.append(",\"isOnline\":").append(isOnline);
        sb.append("}");
        return sb.toString();
    }

    public static UserInfo fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            UserInfo u = new UserInfo();
            u.uid = extractLong(json, "\"uid\":");
            u.username = extractString(json, "\"username\":\"");
            u.passwordHash = extractString(json, "\"passwordHash\":\"");
            u.role = Role.fromString(extractString(json, "\"role\":\""));
            u.signature = extractString(json, "\"signature\":\"");
            u.avatarPath = extractString(json, "\"avatarPath\":\"");
            u.ip = extractString(json, "\"ip\":\"");
            u.contacts = extractLongList(json, "\"contacts\":[");
            u.groups = extractLongList(json, "\"groups\":[");
            u.isOnline = "true".equals(extractValue(json, "\"isOnline\":", ","));
            // 最后一个字段后面可能是 } 
            int lastIdx = json.lastIndexOf("\"isOnline\":");
            if (lastIdx >= 0) {
                int valStart = json.indexOf(":", lastIdx) + 1;
                int valEnd = json.indexOf("}", valStart);
                if (valEnd < 0) valEnd = json.length();
                u.isOnline = "true".equals(json.substring(valStart, valEnd).trim());
            }
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 辅助方法 ====================

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String longListToJson(List<Long> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(list.get(i));
        }
        sb.append("]");
        return sb.toString();
    }

    private static long extractLong(String json, String key) {
        String v = extractValue(json, key, ",");
        if (v == null || v.isEmpty()) return 0;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return 0; }
    }

    private static String extractString(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { sb.append(c); escaped = false; }
            else if (c == '\\') escaped = true;
            else if (c == '"') return sb.toString();
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String extractValue(String json, String key, String endDelim) {
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = json.indexOf(endDelim, start);
        if (end < 0) {
            end = json.indexOf("}", start);
            if (end < 0) end = json.length();
        }
        return json.substring(start, end).trim();
    }

    private static List<Long> extractLongList(String json, String key) {
        List<Long> list = new ArrayList<Long>();
        int start = json.indexOf(key);
        if (start < 0) return list;
        start += key.length();
        int end = json.indexOf("]", start);
        if (end < 0) return list;
        String content = json.substring(start, end);
        if (content.isEmpty()) return list;
        String[] parts = content.split(",");
        for (String p : parts) {
            try { list.add(Long.parseLong(p.trim())); } catch (Exception e) {}
        }
        return list;
    }
}
