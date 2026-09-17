package sair.v4.schedule;

/**
 * 最简单的一种回合任务：不可合并、有会话键与优先级、可以回一句话。
 *
 * <p>用于"合并没有意义"的后台回合：闹钟到点唤起、异步子 Agent 收尾回灌。
 * 它们跟用户消息放在同一套分道里排队（这样模型调用仍然不并发），但不会被并进别的回合。</p>
 */
public final class SimpleJob implements Job {

    /** 回话落点（可为 null = 没有落点）。 */
    public interface Notice {
        void say(String text);
    }

    /** 把基板输出落点（{@code ctx.Sink}）适配成"回一句话"；null 进 null 出。 */
    public static Notice sink(final sair.v4.ctx.Sink s) {
        if (s == null) return null;
        return new Notice() {
            @Override
            public void say(String text) {
                s.say(text);
            }
        };
    }

    private final String session;
    private final boolean high;
    private final Runnable body;
    private final Notice notice;

    public SimpleJob(String session, boolean high, Runnable body, Notice notice) {
        this.session = session == null ? "" : session;
        this.high = high;
        this.body = body;
        this.notice = notice;
    }

    @Override
    public String session() {
        return session;
    }

    @Override
    public boolean high() {
        return high;
    }

    @Override
    public int merge(Job later) {
        return -1;
    }

    @Override
    public boolean notice(String text) {
        if (notice == null || text == null) return false;
        try {
            notice.say(text);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void run() {
        body.run();
    }
}
