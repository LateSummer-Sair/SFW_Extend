package sair.aiagent.onebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待处理请求池 —— 保存好友申请与群邀请，交由 AI/主人通过 FC 工具决策。
 * <p>
 * 好友申请/群邀请到达后不再自动同意或拒绝，而是登记到此池中，
 * AI 可用 pendingrequests 工具查看，用 approvefriend/rejectfriend/
 * acceptgroupinvite/rejectgroupinvite 工具处理（以 OneBot 的 flag 为键）。
 * </p>
 */
public class PendingRequestPool {

    /** 待处理请求条目 */
    public static class PendingRequest {
        /** 请求类型：friend（好友申请） / group（群邀请） */
        public final String type;
        /** OneBot 请求标识（调用 set_friend_add_request / set_group_add_request 用） */
        public final String flag;
        /** 申请人 / 邀请者 QQ 号 */
        public final long userId;
        /** 群号（群邀请有效，好友申请为 0） */
        public final long groupId;
        /** 验证消息（可能为空） */
        public final String comment;
        /** 到达时间戳 */
        public final long timestamp;

        public PendingRequest(String type, String flag, long userId, long groupId, String comment) {
            this.type = type;
            this.flag = flag;
            this.userId = userId;
            this.groupId = groupId;
            this.comment = comment;
            this.timestamp = System.currentTimeMillis();
        }
    }

    /** flag -> 请求 */
    private final Map<String, PendingRequest> requests = new ConcurrentHashMap<>();

    /** 登记一个待处理请求 */
    public void add(PendingRequest req) {
        if (req == null || req.flag == null || req.flag.isEmpty()) return;
        requests.put(req.flag, req);
    }

    /** 按 flag 获取请求 */
    public PendingRequest get(String flag) {
        return flag == null ? null : requests.get(flag);
    }

    /** 按 flag 移除请求（处理完成后调用） */
    public PendingRequest remove(String flag) {
        return flag == null ? null : requests.remove(flag);
    }

    /** 列出所有待处理请求 */
    public List<PendingRequest> list() {
        return new ArrayList<>(requests.values());
    }

    /** 待处理请求数量 */
    public int size() {
        return requests.size();
    }
}
