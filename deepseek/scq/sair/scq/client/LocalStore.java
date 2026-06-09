package sair.scq.client;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import sair.scq.model.ChatRecord;
import sair.sys.SairCons;

/**
 * 聊天记录本地存储 —— 加密存储客户端聊天记录。
 * 
 * <h3>存储策略</h3>
 * <ul>
 *   <li>按会话(UID/群组ID)分文件存储</li>
 *   <li>每条记录 ChatRecord → JSON → AES加密 → 写入文件</li>
 *   <li>每个会话文件格式：[记录1的密文]\n[记录2的密文]\n...</li>
 *   <li>文件路径: data/chatlogs/{ownUID}/{peerUID}.dat</li>
 * </ul>
 * 
 * <h3>加密</h3>
 * 使用AES-256加密，密钥由用户密码派生。
 */
public class LocalStore {

    /** 聊天记录目录 */
    private static final String CHATLOG_DIR = "data" + File.separator + "chatlogs";

    /** 当前用户UID */
    private long ownUID;
    /** AES密钥 */
    private byte[] keyBytes;
    /** 数据根目录 */
    private final String dataDir;

    /** 内存中的聊天记录缓存：peerUID → List<ChatRecord> */
    private final Map<Long, List<ChatRecord>> chatCache = new HashMap<Long, List<ChatRecord>>();

    public LocalStore(String dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * 初始化存储：设置当前用户和加密密钥。
     * @param ownUID 当前用户UID
     * @param password 用户密码（用于派生加密密钥）
     */
    public void init(long ownUID, String password) {
        this.ownUID = ownUID;
        this.keyBytes = CryptoUtils.deriveKey(password);
        chatCache.clear();
    }

    /**
     * 保存一条聊天记录到本地（加密）。
     * @param record 聊天记录
     */
    public synchronized void saveRecord(ChatRecord record) {
        if (ownUID == 0 || keyBytes == null) return;

        long peerUID = record.isGroup() ? record.getToUID() : 
                       (record.getFromUID() == ownUID ? record.getToUID() : record.getFromUID());

        // 添加到内存缓存
        List<ChatRecord> records = chatCache.get(peerUID);
        if (records == null) {
            records = new ArrayList<ChatRecord>();
            chatCache.put(peerUID, records);
        }
        records.add(record);

        // 加密并写入文件
        String filePath = getFilePath(peerUID);
        try {
            File file = new File(filePath);
            file.getParentFile().mkdirs();

            String json = record.toJson();
            String encrypted = CryptoUtils.encrypt(json, keyBytes);

            // 追加写入
            StringBuilder sb = new StringBuilder();
            if (file.exists()) {
                String existing = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
                sb.append(existing);
                if (!existing.endsWith("\n")) sb.append("\n");
            }
            sb.append(encrypted).append("\n");

            Files.write(Paths.get(filePath), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            SairCons.println("保存聊天记录失败: " + e.getMessage());
        }
    }

    /**
     * 加载指定会话的聊天记录。
     * @param peerUID 对方UID或群组GID
     * @return 聊天记录列表
     */
    public synchronized List<ChatRecord> loadRecords(long peerUID) {
        // 先检查缓存
        List<ChatRecord> cached = chatCache.get(peerUID);
        if (cached != null && !cached.isEmpty()) {
            return new ArrayList<ChatRecord>(cached);
        }

        List<ChatRecord> records = new ArrayList<ChatRecord>();
        String filePath = getFilePath(peerUID);
        File file = new File(filePath);

        if (!file.exists()) return records;

        try {
            String content = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);
            String[] lines = content.split("\n");
            for (String line : lines) {
                if (line.trim().isEmpty()) continue;
                String decrypted = CryptoUtils.decrypt(line.trim(), keyBytes);
                if (decrypted != null && !decrypted.isEmpty()) {
                    ChatRecord record = ChatRecord.fromJson(decrypted);
                    if (record != null) {
                        records.add(record);
                    }
                }
            }

            // 更新缓存
            chatCache.put(peerUID, records);
        } catch (IOException e) {
            SairCons.println("加载聊天记录失败: " + e.getMessage());
        }

        return records;
    }

    /**
     * 清除指定会话的缓存（重新加载时从文件刷新）。
     */
    public void clearCache(long peerUID) {
        chatCache.remove(peerUID);
    }

    /**
     * 清除所有缓存。
     */
    public void clearAllCache() {
        chatCache.clear();
    }

    /**
     * 重置存储（切换用户时）。
     */
    public void reset() {
        ownUID = 0;
        keyBytes = null;
        chatCache.clear();
    }

    /** 获取会话文件路径 */
    private String getFilePath(long peerUID) {
        return dataDir + CHATLOG_DIR + File.separator + ownUID + File.separator + peerUID + ".dat";
    }
}
