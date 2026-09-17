package sair.v4.qq;

/**
 * 「发一条文本」的最小接口：<b>落点只依赖它</b>，不直接依赖 {@link Api}。
 *
 * <p>为什么要这一层：分段发送的规矩（拆几条、每条多长、停多久）属于"发消息"这件事本身，
 * 而真实发送要连 NapCat。有了这个接口，探针可以塞一个"只记录、不联网"的实现，
 * 把"一条长回复到底发成了几条"钉死在断言里；生产路径用 {@link #of(Api)} 包一层即可。</p>
 */
public interface Sender {

    /**
     * 发一条文本。
     *
     * @param group  true = 群，false = 私聊
     * @param target 群号或 QQ 号
     * @param text   正文
     * @return 成功返回 {@code null}；失败返回原因（简短、给人看）
     */
    String send(boolean group, long target, String text);

    /** 真实实现：走 NapCat 动作（{@code send_group_msg} / {@code send_private_msg}）。 */
    static Sender of(final Api api) {
        return new Sender() {
            @Override
            public String send(boolean group, long target, String text) {
                if (api == null) return "没有 NapCat 接口";
                com.google.gson.JsonObject r = group ? api.sendGroupMsg(target, text)
                        : api.sendPrivateMsg(target, text);
                return Api.ok(r) ? null : Api.error(r);
            }
        };
    }
}
