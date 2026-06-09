package sair.scq.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 群组信息模型 —— 存储群组的属性和成员管理数据。
 * 
 * <h3>管理员规则</h3>
 * 每40人可设置1名管理员，少于40人只能有1名管理员。
 * MAX_ADMINS = max(1, memberCount / 40)
 */
public class GroupInfo {

    /** 群唯一ID */
    private long gid;
    /** 群组名称 */
    private String groupName;
    /** 创建者UID */
    private long creatorUID;
    /** 成员UID列表 */
    private List<Long> memberUIDs = new ArrayList<Long>();
    /** 管理员UID列表 */
    private List<Long> adminUIDs = new ArrayList<Long>();
    /** 被禁言成员UID列表 */
    private List<Long> mutedUIDs = new ArrayList<Long>();
    /** 入群申请中的UID列表 */
    private List<Long> joinRequests = new ArrayList<Long>();
    /** 创建时间 */
    private long createTime;

    public GroupInfo() {
        this.createTime = System.currentTimeMillis();
    }

    // ==================== Getter / Setter ====================

    public long getGid() { return gid; }
    public void setGid(long gid) { this.gid = gid; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public long getCreatorUID() { return creatorUID; }
    public void setCreatorUID(long creatorUID) { this.creatorUID = creatorUID; }

    public List<Long> getMemberUIDs() { return memberUIDs; }
    public void setMemberUIDs(List<Long> memberUIDs) { this.memberUIDs = memberUIDs; }

    public List<Long> getAdminUIDs() { return adminUIDs; }
    public void setAdminUIDs(List<Long> adminUIDs) { this.adminUIDs = adminUIDs; }

    public List<Long> getMutedUIDs() { return mutedUIDs; }
    public void setMutedUIDs(List<Long> mutedUIDs) { this.mutedUIDs = mutedUIDs; }

    public List<Long> getJoinRequests() { return joinRequests; }
    public void setJoinRequests(List<Long> joinRequests) { this.joinRequests = joinRequests; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    // ==================== 业务方法 ====================

    /** 获取允许的管理员最大数量(初始1名,每40人+1名) */
    public int getMaxAdmins() {
        return 1 + memberUIDs.size() / 40;
    }

    /** 是否可以添加更多管理员 */
    public boolean canAddAdmin() {
        return adminUIDs.size() < getMaxAdmins();
    }

    /** 添加成员 */
    public boolean addMember(long uid) {
        if (!memberUIDs.contains(uid)) {
            memberUIDs.add(uid);
            return true;
        }
        return false;
    }

    /** 移除成员（同时移除其管理员身份） */
    public boolean removeMember(long uid) {
        adminUIDs.remove((Long) uid);
        mutedUIDs.remove((Long) uid);
        return memberUIDs.remove((Long) uid);
    }

    /** 是否是成员 */
    public boolean isMember(long uid) {
        return memberUIDs.contains(uid);
    }

    /** 是否是管理员 */
    public boolean isAdmin(long uid) {
        return adminUIDs.contains(uid);
    }

    /** 设置管理员 */
    public boolean setAdmin(long uid) {
        if (!memberUIDs.contains(uid)) return false;
        if (adminUIDs.contains(uid)) return false;
        if (!canAddAdmin()) return false;
        adminUIDs.add(uid);
        return true;
    }

    /** 取消管理员 */
    public boolean removeAdmin(long uid) {
        return adminUIDs.remove((Long) uid);
    }

    /** 禁言 */
    public boolean mute(long uid) {
        if (!memberUIDs.contains(uid)) return false;
        if (mutedUIDs.contains(uid)) return false;
        mutedUIDs.add(uid);
        return true;
    }

    /** 取消禁言 */
    public boolean unmute(long uid) {
        return mutedUIDs.remove((Long) uid);
    }

    /** 是否被禁言 */
    public boolean isMuted(long uid) {
        return mutedUIDs.contains(uid);
    }

    /** 添加入群申请 */
    public boolean addJoinRequest(long uid) {
        if (memberUIDs.contains(uid)) return false;
        if (joinRequests.contains(uid)) return false;
        joinRequests.add(uid);
        return true;
    }

    /** 移除入群申请 */
    public boolean removeJoinRequest(long uid) {
        return joinRequests.remove((Long) uid);
    }

    // ==================== JSON 序列化 ====================

    public String toJson() {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"gid\":").append(gid);
        sb.append(",\"groupName\":\"").append(escape(groupName)).append("\"");
        sb.append(",\"creatorUID\":").append(creatorUID);
        sb.append(",\"memberUIDs\":").append(longListToJson(memberUIDs));
        sb.append(",\"adminUIDs\":").append(longListToJson(adminUIDs));
        sb.append(",\"mutedUIDs\":").append(longListToJson(mutedUIDs));
        sb.append(",\"joinRequests\":").append(longListToJson(joinRequests));
        sb.append(",\"createTime\":").append(createTime);
        sb.append("}");
        return sb.toString();
    }

    public static GroupInfo fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            GroupInfo g = new GroupInfo();
            g.gid = ecLong(json, "\"gid\":");
            g.groupName = ecStr(json, "\"groupName\":\"");
            g.creatorUID = ecLong(json, "\"creatorUID\":");
            g.memberUIDs = ecLongList(json, "\"memberUIDs\":[");
            g.adminUIDs = ecLongList(json, "\"adminUIDs\":[");
            g.mutedUIDs = ecLongList(json, "\"mutedUIDs\":[");
            g.joinRequests = ecLongList(json, "\"joinRequests\":[");
            g.createTime = ecLong(json, "\"createTime\":");
            return g;
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

    private static long ecLong(String json, String key) {
        String v = ecVal(json, key);
        if (v.isEmpty()) return 0;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return 0; }
    }

    private static String ecStr(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        StringBuilder sb = new StringBuilder();
        boolean esc = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (esc) { sb.append(c); esc = false; }
            else if (c == '\\') esc = true;
            else if (c == '"') return sb.toString();
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String ecVal(String json, String key) {
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = json.indexOf(",", start);
        if (end < 0) {
            end = json.indexOf("}", start);
            if (end < 0) end = json.length();
        }
        return json.substring(start, end).trim();
    }

    private static List<Long> ecLongList(String json, String key) {
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
