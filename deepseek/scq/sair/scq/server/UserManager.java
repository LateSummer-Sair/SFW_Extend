package sair.scq.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sair.scq.model.UserInfo;
import sair.scq.model.UserInfo.Role;
import sair.sys.SairCons;

/**
 * 用户管理器 —— 负责用户注册、登录认证、信息更新和数据持久化。
 * 
 * <h3>UID规则</h3>
 * 普通用户自增UID从1000开始，超级管理员可自定义UID。
 * 首个注册用户自动成为超级管理员。
 * 
 * <h3>持久化</h3>
 * 数据存储在 data/users.json（ 所有用户信息JSON数组）。
 */
public class UserManager {

    /** 自增UID起始值 */
    private static final long UID_START = 1000L;
    /** 数据文件相对路径 */
    private static final String DATA_FILE = "data" + File.separator + "users.json";

    /** 用户映射表：UID → UserInfo */
    private final Map<Long, UserInfo> userMap = new HashMap<Long, UserInfo>();
    /** 用户名映射表：username → UserInfo */
    private final Map<String, UserInfo> userByName = new HashMap<String, UserInfo>();
    /** 下一个自增UID */
    private long nextUID = UID_START;
    /** 数据目录 */
    private final String dataDir;
    /** 是否已存在用户（用于判断首个用户） */
    private boolean hasExistingUsers = false;

    public UserManager(String dataDir) {
        this.dataDir = dataDir;
        load();
    }

    // ==================== 用户认证 ====================

    /**
     * 用户注册。
     * @param username 用户名
     * @param password 明文密码
     * @param customUID 自定义UID（仅超级管理员可用，0表示自动生成）
     * @param requesterUID 请求者UID（0表示新用户自助注册）
     * @return 成功返回UserInfo，失败返回null
     */
    public synchronized UserInfo register(String username, String password, long customUID, long requesterUID) {
        if (username == null || username.trim().isEmpty()) return null;
        if (password == null || password.isEmpty()) return null;
        username = username.trim();

        // 检查用户名唯一性
        if (userByName.containsKey(username)) return null;

        UserInfo user = new UserInfo();
        user.setUsername(username);
        user.setPasswordHash(sha256(password));

        // 确定UID
        if (customUID > 0) {
            // 自定义UID：检查是否有权限和是否冲突
            UserInfo requester = userMap.get(requesterUID);
            if (requester == null || !requester.isSuperAdmin()) return null;
            if (userMap.containsKey(customUID)) return null;
            user.setUid(customUID);
        } else {
            user.setUid(nextUID++);
        }

        // 首个用户自动成为超级管理员
        if (!hasExistingUsers && userMap.isEmpty()) {
            user.setRole(Role.SUPER_ADMIN);
        } else {
            user.setRole(Role.MEMBER);
        }

        userMap.put(user.getUid(), user);
        userByName.put(user.getUsername(), user);
        save();
        hasExistingUsers = true;

        SairCons.println("用户注册成功: " + username + " [UID:" + user.getUid() + "]");
        return user;
    }

    /**
     * 用户登录验证。
     * @return 验证成功返回UserInfo并更新IP和在线状态，失败返回null
     */
    public synchronized UserInfo login(String username, String password, String ip) {
        if (username == null || password == null) return null;
        UserInfo user = userByName.get(username.trim());
        if (user == null) return null;

        String hash = sha256(password);
        if (!hash.equals(user.getPasswordHash())) return null;

        user.setIp(ip != null ? ip : "");
        // 在线状态由SCServerCore的sessionMap管理，不设置布尔标志（参照nsc）
        return user;
    }

    /**
     * 用户登出（参照nsc：仅清空IP，实际在线状态由SCServerCore.unregisterSession管理）。
     */
    public synchronized void logout(long uid) {
        UserInfo user = userMap.get(uid);
        if (user != null) {
            user.setIp("");
        }
    }

    // ==================== 用户查询 ====================

    /** 根据UID获取用户 */
    public UserInfo getUser(long uid) {
        return userMap.get(uid);
    }

    /** 根据用户名获取用户 */
    public UserInfo getUserByName(String username) {
        return userByName.get(username);
    }

    /** 获取所有用户 */
    public List<UserInfo> getAllUsers() {
        return new ArrayList<UserInfo>(userMap.values());
    }

    /**
     * 获取在线用户（参照nsc：根据SCServerCore提供的在线UID集合过滤）。
     * 不再依赖UserInfo.isOnline布尔标志。
     * @param onlineUIDs SCServerCore.getOnlineUIDs()返回的在线UID集合
     */
    public List<UserInfo> getOnlineUsers(java.util.Collection<Long> onlineUIDs) {
        List<UserInfo> list = new ArrayList<UserInfo>();
        for (Long uid : onlineUIDs) {
            UserInfo u = userMap.get(uid);
            if (u != null) list.add(u);
        }
        return list;
    }

    /** 用户是否存在 */
    public boolean userExists(long uid) {
        return userMap.containsKey(uid);
    }

    // ==================== 用户信息更新 ====================

    /** 更新用户信息(签名/头像/密码) */
    public synchronized boolean updateProfile(long uid, String signature, String avatarPath, String newPassword) {
        UserInfo user = userMap.get(uid);
        if (user == null) return false;
        if (signature != null) user.setSignature(signature);
        if (avatarPath != null) user.setAvatarPath(avatarPath);
        if (newPassword != null && !newPassword.isEmpty()) {
            user.setPasswordHash(sha256(newPassword));
        }
        save();
        return true;
    }

    /** 设置用户角色 */
    public synchronized boolean setRole(long uid, Role role) {
        UserInfo user = userMap.get(uid);
        if (user == null) return false;
        user.setRole(role);
        save();
        return true;
    }

    // ==================== 通讯录操作 ====================

    /** 添加好友 */
    public synchronized boolean addContact(long uid, long contactUID) {
        UserInfo user = userMap.get(uid);
        if (user == null || !userMap.containsKey(contactUID)) return false;
        boolean result = user.addContact(contactUID);
        if (result) save();
        return result;
    }

    /** 加入群组 */
    public synchronized boolean joinGroup(long uid, long gid) {
        UserInfo user = userMap.get(uid);
        if (user == null) return false;
        boolean result = user.joinGroup(gid);
        if (result) save();
        return result;
    }

    /** 退出群组 */
    public synchronized boolean leaveGroup(long uid, long gid) {
        UserInfo user = userMap.get(uid);
        if (user == null) return false;
        boolean result = user.leaveGroup(gid);
        if (result) save();
        return result;
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

            // 简单JSON数组解析: [json1,json2,...]
            content = content.trim();
            if (content.startsWith("[")) content = content.substring(1);
            if (content.endsWith("]")) content = content.substring(0, content.length() - 1);

            List<String> items = splitJsonArray(content);
            for (String item : items) {
                UserInfo u = UserInfo.fromJson(item.trim());
                if (u != null) {
                    userMap.put(u.getUid(), u);
                    userByName.put(u.getUsername(), u);
                    if (u.getUid() >= nextUID) {
                        nextUID = u.getUid() + 1;
                    }
                }
            }
            hasExistingUsers = !userMap.isEmpty();
        } catch (IOException e) {
            SairCons.println("加载用户数据失败: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            File dir = new File(dataDir + "data");
            if (!dir.exists()) dir.mkdirs();

            StringBuilder sb = new StringBuilder(4096);
            sb.append("[");
            boolean first = true;
            for (UserInfo u : userMap.values()) {
                if (!first) sb.append(",");
                sb.append(u.toJson());
                first = false;
            }
            sb.append("]");

            Files.write(Paths.get(dataDir + DATA_FILE), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            SairCons.println("保存用户数据失败: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return input; // fallback, should never happen
        }
    }

    /**
     * 简单分割JSON数组中的各个对象字符串。
     * 按顶层大括号匹配来分割。
     */
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
