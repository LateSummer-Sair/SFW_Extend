package sair.scq.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sair.scq.model.GroupInfo;
import sair.scq.model.UserInfo;
import sair.sys.SairCons;

/**
 * 群组管理器 —— 负责群组的创建、加入、退出、管理操作和数据持久化。
 * 
 * <h3>管理员规则</h3>
 * 每40人可设置1名管理员，少于40人只能有1名管理员。
 * 
 * <h3>持久化</h3>
 * 数据存储在 data/groups.json（所有群组信息JSON数组）。
 */
public class GroupManager {

    /** 数据文件相对路径 */
    private static final String DATA_FILE = "data" + File.separator + "groups.json";

    /** 群组映射表：GID → GroupInfo */
    private final Map<Long, GroupInfo> groupMap = new HashMap<Long, GroupInfo>();
    /** 下一个自增GID */
    private long nextGID = 100L;
    /** 数据目录 */
    private final String dataDir;
    /** 用户管理器引用（用于验证操作权限） */
    private final UserManager userManager;

    public GroupManager(String dataDir, UserManager userManager) {
        this.dataDir = dataDir;
        this.userManager = userManager;
        load();
    }

    // ==================== 群组创建 ====================

    /**
     * 创建群组（仅超级管理员可用）。
     * @param creatorUID 创建者UID
     * @param groupName 群组名称
     * @return 成功返回GroupInfo，失败返回null
     */
    public synchronized GroupInfo createGroup(long creatorUID, String groupName) {
        UserInfo creator = userManager.getUser(creatorUID);
        if (creator == null || !creator.isSuperAdmin()) return null;
        if (groupName == null || groupName.trim().isEmpty()) return null;
        groupName = groupName.trim();

        GroupInfo group = new GroupInfo();
        group.setGid(nextGID++);
        group.setGroupName(groupName);
        group.setCreatorUID(creatorUID);
        group.getMemberUIDs().add(creatorUID);

        groupMap.put(group.getGid(), group);
        userManager.joinGroup(creatorUID, group.getGid());
        save();

        SairCons.println("群组创建成功: " + groupName + " [GID:" + group.getGid() + "]");
        return group;
    }

    // ==================== 群组查询 ====================

    /** 根据GID获取群组 */
    public GroupInfo getGroup(long gid) {
        return groupMap.get(gid);
    }

    /** 获取所有群组 */
    public List<GroupInfo> getAllGroups() {
        return new ArrayList<GroupInfo>(groupMap.values());
    }

    /** 获取群组的待审核入群申请列表 */
    public List<Long> getPendingRequests(long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return new ArrayList<Long>();
        return new ArrayList<Long>(group.getJoinRequests());
    }

    /** 根据名称查找群组 */
    public GroupInfo getGroupByName(String name) {
        for (GroupInfo g : groupMap.values()) {
            if (g.getGroupName().equals(name)) return g;
        }
        return null;
    }

    /** 群组是否存在 */
    public boolean groupExists(long gid) {
        return groupMap.containsKey(gid);
    }

    // ==================== 成员操作 ====================

    /**
     * 申请加入群组。
     * 将用户添加到入群申请列表，等待管理员审核。
     */
    public synchronized boolean requestJoin(long uid, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;
        UserInfo user = userManager.getUser(uid);
        if (user == null) return false;
        if (group.isMember(uid)) return false;
        return group.addJoinRequest(uid);
    }

    /**
     * 管理员审核通过入群申请。
     * @param adminUID 操作的管理员UID
     * @param targetUID 待加入的UID
     * @param gid 群组GID
     * @return 是否成功
     */
    public synchronized boolean approveJoin(long adminUID, long targetUID, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;
        if (!group.isAdmin(adminUID) && !group.isMember(adminUID)) return false;
        // 只有管理员才能审核
        if (!group.isAdmin(adminUID) && group.getCreatorUID() != adminUID) return false;

        if (!group.getJoinRequests().contains(targetUID)) return false;
        group.removeJoinRequest(targetUID);
        boolean result = group.addMember(targetUID);
        if (result) {
            userManager.joinGroup(targetUID, gid);
            save();
        }
        return result;
    }

    /**
     * 拒绝入群申请。
     */
    public synchronized boolean rejectJoin(long adminUID, long targetUID, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;
        if (!group.isAdmin(adminUID) && group.getCreatorUID() != adminUID) return false;
        return group.removeJoinRequest(targetUID);
    }

    /**
     * 退出群组。
     */
    public synchronized boolean leaveGroup(long uid, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;
        if (!group.isMember(uid)) return false;

        boolean result = group.removeMember(uid);
        if (result) {
            userManager.leaveGroup(uid, gid);
            // 如果群组无人，删除群组
            if (group.getMemberUIDs().isEmpty()) {
                groupMap.remove(gid);
            }
            save();
        }
        return result;
    }

    /**
     * 踢出成员（管理员操作）。
     */
    public synchronized boolean kickMember(long adminUID, long targetUID, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;
        if (!group.isAdmin(adminUID) && group.getCreatorUID() != adminUID) return false;
        if (targetUID == group.getCreatorUID()) return false; // 不能踢创建者
        if (!group.isMember(targetUID)) return false;

        boolean result = group.removeMember(targetUID);
        if (result) {
            userManager.leaveGroup(targetUID, gid);
            if (group.getMemberUIDs().isEmpty()) {
                groupMap.remove(gid);
            }
            save();
        }
        return result;
    }

    // ==================== 管理员操作 ====================

    /**
     * 设置管理员（仅超级管理员可用）。
     */
    public synchronized boolean setAdmin(long superAdminUID, long targetUID, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;

        UserInfo operator = userManager.getUser(superAdminUID);
        if (operator == null || !operator.isSuperAdmin()) return false;

        return group.setAdmin(targetUID);
    }

    /**
     * 取消管理员（仅超级管理员可用）。
     */
    public synchronized boolean removeAdmin(long superAdminUID, long targetUID, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;

        UserInfo operator = userManager.getUser(superAdminUID);
        if (operator == null || !operator.isSuperAdmin()) return false;

        return group.removeAdmin(targetUID);
    }

    // ==================== 禁言操作 ====================

    /**
     * 禁言成员（管理员操作）。
     */
    public synchronized boolean muteMember(long adminUID, long targetUID, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;
        if (!group.isAdmin(adminUID) && group.getCreatorUID() != adminUID) return false;
        if (targetUID == group.getCreatorUID()) return false; // 不能禁言创建者
        return group.mute(targetUID);
    }

    /**
     * 取消禁言（管理员操作）。
     */
    public synchronized boolean unmuteMember(long adminUID, long targetUID, long gid) {
        GroupInfo group = groupMap.get(gid);
        if (group == null) return false;
        if (!group.isAdmin(adminUID) && group.getCreatorUID() != adminUID) return false;
        return group.unmute(targetUID);
    }

    // ==================== 持久化 ====================

    private void load() {
        String filePath = dataDir + DATA_FILE;
        File file = new File(filePath);
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            return;
        }
        try {
            String content = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
            if (content == null || content.trim().isEmpty() || "[]".equals(content.trim())) return;

            content = content.trim();
            if (content.startsWith("[")) content = content.substring(1);
            if (content.endsWith("]")) content = content.substring(0, content.length() - 1);

            List<String> items = splitJsonArray(content);
            for (String item : items) {
                GroupInfo g = GroupInfo.fromJson(item.trim());
                if (g != null) {
                    groupMap.put(g.getGid(), g);
                    if (g.getGid() >= nextGID) {
                        nextGID = g.getGid() + 1;
                    }
                }
            }
        } catch (IOException e) {
            SairCons.println("加载群组数据失败: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            File dir = new File(dataDir + "data");
            if (!dir.exists()) dir.mkdirs();

            StringBuilder sb = new StringBuilder(4096);
            sb.append("[");
            boolean first = true;
            for (GroupInfo g : groupMap.values()) {
                if (!first) sb.append(",");
                sb.append(g.toJson());
                first = false;
            }
            sb.append("]");

            Files.write(Paths.get(dataDir + DATA_FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SairCons.println("保存群组数据失败: " + e.getMessage());
        }
    }

    /** JSON数组分割（按顶层大括号匹配） */
    private static List<String> splitJsonArray(String content) {
        List<String> result = new ArrayList<String>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    result.add(content.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return result;
    }
}
