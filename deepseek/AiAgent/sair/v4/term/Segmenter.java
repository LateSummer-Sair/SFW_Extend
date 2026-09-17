package sair.v4.term;

import java.util.List;

import sair.v4.Conf;
import sair.v4.qq.Seg;

/**
 * 分段发送的口径（<b>配置驱动</b>）：一条回复拆几条、每条多长、条与条之间停多久。
 *
 * <p>四个键（都能在 config.json 里改，改完即时生效）：</p>
 * <ul>
 *   <li>{@code replySplit}（默认 true）：总开关。关掉 = 一条发完（只有极端超长才硬切）。</li>
 *   <li>{@code replyMaxChars}（默认 1000）：单条上限；超过就按"空行段落"拆（V3 的 {@code MAX_MSG_LEN}）。</li>
 *   <li>{@code replyGroupMaxChars}（默认 180）：<b>群聊</b>再压短一档（V3 的"群聊短句优先"）；
 *       {@code 0} = 群聊不加这一层。</li>
 *   <li>{@code replySplitDelayMs} + {@code replySplitJitterMs}（默认 800 + 最多 1200）：
 *       条与条之间的停顿；两个都设 0 = 立刻连发。</li>
 * </ul>
 *
 * <p>显式标记优先：模型自己写了 {@code <split>} 就只按标记拆（它分好的段最贴合语气），
 * 不再按长度兜底切。</p>
 */
public final class Segmenter {

    private final Conf conf;

    public Segmenter(Conf conf) {
        this.conf = conf;
    }

    /** 出厂默认：单条 1000 字、群聊压到 180、条间停 800(+抖动)。 */
    public static final int DEF_MAX_CHARS = 1000;
    public static final int DEF_GROUP_MAX_CHARS = 180;
    public static final int DEF_DELAY_MS = 800;
    public static final int DEF_JITTER_MS = 1200;

    /** 是否开了分段。 */
    public boolean on() {
        try {
            return conf == null || conf.getBool("replySplit", true);
        } catch (Throwable t) {
            return true;
        }
    }

    /** 单条上限（<=0 = 不限制）。 */
    public int maxChars() {
        try {
            return conf == null ? DEF_MAX_CHARS : conf.getInt("replyMaxChars", DEF_MAX_CHARS);
        } catch (Throwable t) {
            return DEF_MAX_CHARS;
        }
    }

    /** 群聊上限（0 = 不压短）。 */
    public int groupMaxChars() {
        try {
            return conf == null ? DEF_GROUP_MAX_CHARS : conf.getInt("replyGroupMaxChars", DEF_GROUP_MAX_CHARS);
        } catch (Throwable t) {
            return DEF_GROUP_MAX_CHARS;
        }
    }

    /** 条间停顿基准（毫秒）。 */
    public int delayMs() {
        try {
            return conf == null ? DEF_DELAY_MS : conf.getInt("replySplitDelayMs", DEF_DELAY_MS);
        } catch (Throwable t) {
            return DEF_DELAY_MS;
        }
    }

    /** 条间停顿随机抖动的上限（毫秒；让节奏不像机器）。 */
    public int jitterMs() {
        try {
            return conf == null ? DEF_JITTER_MS : conf.getInt("replySplitJitterMs", DEF_JITTER_MS);
        } catch (Throwable t) {
            return DEF_JITTER_MS;
        }
    }

    /**
     * 拆一条回复（入口就这一个：关掉分段时退回"按上限硬切"，避免真有超长文本时被 QQ 拒收）。
     */
    public List<String> plan(String text, boolean group) {
        int max = maxChars();
        if (!on()) {
            int hard = max > 0 ? Math.max(max, 2000) : 0;      // 关掉分段：只在极端超长时才切
            return Seg.messages(text, hard);
        }
        return Seg.plan(text, max, group ? groupMaxChars() : 0, group);
    }

    /** 发下一条之前的停顿（{@code 0} = 不等）。 */
    public void pause() {
        int base = delayMs();
        int jit = Math.max(0, jitterMs());
        long ms = base <= 0 ? 0L : (base + (jit > 0 ? (long) (Math.random() * jit) : 0L));
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
