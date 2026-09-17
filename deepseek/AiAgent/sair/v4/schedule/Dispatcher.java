package sair.v4.schedule;

import com.google.gson.JsonObject;

/**
 * 回合提交口（基板调度面）：QQ 入站、闹钟、异步子 Agent 都走这里，
 * 因此"排队在哪、满了怎么办"只有一份实现（{@link Lanes}）。
 *
 * <p>{@link sair.v4.agent.Agent} 只依赖这一个接口，因此探针/未来替换调度模型不用改 Agent。</p>
 */
public interface Dispatcher {

    /** 提交一个回合任务（{@code session} 为空 = 归入无名道）。 */
    void submit(String session, boolean high, Job job);

    /** 当前排队中的回合数（不含正在跑的）。 */
    int depth();

    /** 是否已经关闭（关闭后提交一律不执行）。 */
    boolean isShutdown();

    /** 运行期计数（进 {@code status}）。 */
    JsonObject stat();
}
