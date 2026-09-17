package sair.v4.ctx;

import sair.v4.auth.Caller;

/**
 * 线程内的"当前调用者"绑定。
 * <p>用途：技能与工具执行期间，基板需要知道"这一动作是以谁的身份发起的"——
 * 尤其是 NapCat 动作闸门（{@code qq.Api.Guard}）要按调用者复核权限。
 * 没有绑定时视为基板内部动作（回复、定时推送），不做按调用者的复核。</p>
 */
public final class Ctx {

    private Ctx() {}

    private static final ThreadLocal<Caller> CURRENT = new ThreadLocal<Caller>();

    public static void bind(Caller c) {
        if (c == null) CURRENT.remove();
        else CURRENT.set(c);
    }

    public static Caller caller() { return CURRENT.get(); }

    public static void clear() {
        CURRENT.remove();
    }

    /** 绑定并在结束后还原（异常也还原）。 */
    public static final class Scope implements AutoCloseable {
        private final Caller prev;

        private Scope(Caller c) {
            this.prev = CURRENT.get();
            bind(c);
        }

        @Override
        public void close() { bind(prev); }
    }

    public static Scope of(Caller c) { return new Scope(c); }
}
