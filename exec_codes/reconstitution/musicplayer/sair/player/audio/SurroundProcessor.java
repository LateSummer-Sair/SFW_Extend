package sair.player.audio;

import javax.sound.sampled.AudioFormat;

/**
 * 专业环绕声处理器 —— 支持 4 种环绕模式。
 * 
 * <h3>环绕模式</h3>
 * <table border="1">
 *   <tr><th>NONE</th><td>无环绕，透传（仅立体声不做处理）</td></tr>
 *   <tr><th>STEREO_EXPAND</th><td>立体声扩展：通过 M/S 分解增强侧面信号（L-R）实现加宽</td></tr>
 *   <tr><th>VIRTUAL_SURROUND</th><td>虚拟环绕：利用延迟线（哈斯效应）和声道交叉混合模拟环绕</td></tr>
 *   <tr><th>DIFFERENTIAL</th><td>差分环绕：提取左右声道差异信号增强，附加简单高通滤波去除低频 mono</td></tr>
 *   <tr><th>WIDE_STEREO</th><td>宽立体声：M/S 分解 + 频率相关的动态宽度 + 柔和限幅</td></tr>
 * </table>
 * 
 * <h3>核心参数</h3>
 * <ul>
 *   <li><b>expandWidth</b> (1.0~3.0)：扩展宽度，值越大声场越宽</li>
 *   <li><b>surroundMix</b> (0.0~1.0)：环绕混合度，控制延迟/差分信号与原声的比例</li>
 *   <li><b>delayMs</b> (1~50ms)：虚拟环绕延迟时间（哈斯效应关键参数）</li>
 *   <li><b>diffusion</b> (0.0~1.0)：扩散度，控制交叉声道混合程度</li>
 * </ul>
 * 
 * <h3>哈斯效应（Haas Effect）</h3>
 * 当两个相同的声音到达耳朵的时间差在 1~30ms 之间时，人耳感知到的不是两个声音，
 * 而是声音来自两个方向之间的某个位置。VIRTUAL_SURROUND 模式利用此原理，
 * 在左声道混入少量延迟的右声道信号，反之亦然。
 * 
 * <p>仅处理立体声（双声道），单声道数据透传。</p>
 */
public class SurroundProcessor {

	/** 环绕模式枚举 */
	public enum SurroundMode {
		NONE,             // 无环绕
		STEREO_EXPAND,    // 立体声扩展（M/S 增强 side）
		VIRTUAL_SURROUND, // 虚拟环绕（延迟 + 交叉混合）
		DIFFERENTIAL,     // 差分环绕（差异信号增强）
		WIDE_STEREO       // 宽立体声（动态宽度 + 柔和限幅）
	}

	private AudioFormat format;
	private SurroundMode mode = SurroundMode.NONE;

	// ── 环绕参数 ──

	/** 扩展宽度 (1.0 ~ 3.0)，默认 1.5 */
	private float expandWidth = 1.5f;
	/** 环绕混合度 (0.0 ~ 1.0)，默认 0.3 */
	private float surroundMix = 0.3f;
	/** 延迟时间 (ms)，默认 20ms — 哈斯效应有效范围 1-30ms */
	private float delayMs = 20.0f;
	/** 扩散度 (0.0 ~ 1.0)，控制交叉混合强度，默认 0.5 */
	private float diffusion = 0.5f;

	// ── 延迟线缓冲区（用于实现哈斯效应） ──

	/** 延迟线：左右声道各一条环形缓冲区 */
	private float[][] delayLines;
	/** 延迟线写入位置（环形索引） */
	private int[] delayIndices;
	/** 延迟样本数 = delayMs / 1000 × sampleRate */
	private int delaySamples;

	// ── 历史样本（用于差分处理的高通滤波） ──

	/** 左声道前两个样本 */
	private float[] prevLeft;
	/** 右声道前两个样本 */
	private float[] prevRight;

	public SurroundProcessor(AudioFormat format) {
		this.format = format;
		initializeDelayLines();
	}

	/** 初始化延迟线缓冲区：计算延迟样本数并分配环形缓冲 */
	private void initializeDelayLines() {
		if (format.getChannels() != 2) {
			return; // 仅支持立体声
		}

		// 延迟样本数 = 延迟时间(ms) / 1000 × 采样率
		delaySamples = (int) (delayMs * format.getSampleRate() / 1000.0);
		delaySamples = Math.max(1, Math.min(delaySamples, 2048)); // 限幅 1~2048

		// 环形缓冲区：左右声道各一条
		delayLines = new float[2][delaySamples];
		delayIndices = new int[2];

		// 历史样本：各声道存储 2 个历史值
		prevLeft = new float[2];
		prevRight = new float[2];
	}

	/**
	 * 处理立体声音频数据 —— 根据当前模式分发到具体算法。
	 * <p>NONE 模式或单声道数据直接透传。</p>
	 */
	public byte[] process(byte[] audioData) {
		if (audioData == null || audioData.length == 0 || mode == SurroundMode.NONE) {
			return audioData;
		}

		if (format.getChannels() != 2) {
			return audioData; // 仅处理立体声
		}

		byte[] processed = new byte[audioData.length];

		switch (mode) {
			case STEREO_EXPAND:    processStereoExpand(audioData, processed);    break;
			case VIRTUAL_SURROUND: processVirtualSurround(audioData, processed); break;
			case DIFFERENTIAL:     processDifferentialSurround(audioData, processed); break;
			case WIDE_STEREO:      processWideStereo(audioData, processed);      break;
			default: System.arraycopy(audioData, 0, processed, 0, audioData.length); break;
		}

		return processed;
	}

	/**
	 * 立体声扩展算法 —— 通过 M/S（Mid/Side）分解增强声场宽度。
	 * 
	 * <h4>原理</h4>
	 * <pre>
	 *   mid  = (L + R) / 2    — 中间信号（单声道分量）
	 *   side = (L - R) / 2    — 侧面信号（立体声差异）
	 *   L' = mid + side × expandWidth
	 *   R' = mid - side × expandWidth
	 * </pre>
	 * 放大 side 信号使声道分离度增加，产生更宽的声场感。
	 */
	private void processStereoExpand(byte[] input, byte[] output) {
		int frameSize = 4; // 16-bit stereo = 4 bytes

		for (int i = 0; i <= input.length - frameSize; i += frameSize) {
			// 读取左右声道 16-bit 样本
			short left  = (short) ((input[i + 1] << 8) | (input[i] & 0xFF));
			short right = (short) ((input[i + 3] << 8) | (input[i + 2] & 0xFF));

			float l = left / 32768.0f;
			float r = right / 32768.0f;

			// M/S 分解
			float mid  = (l + r) * 0.5f;
			float side = (l - r) * 0.5f;

			// 扩展侧面信号（核心：放大声道差异）
			side *= expandWidth;

			// 转回 L/R
			float outL = mid + side;
			float outR = mid - side;

			// 硬限幅
			outL = Math.max(-1.0f, Math.min(1.0f, outL));
			outR = Math.max(-1.0f, Math.min(1.0f, outR));

			short outLeft  = (short) (outL * 32767.0f);
			short outRight = (short) (outR * 32767.0f);

			output[i]     = (byte) (outLeft & 0xFF);
			output[i + 1] = (byte) (outLeft >> 8);
			output[i + 2] = (byte) (outRight & 0xFF);
			output[i + 3] = (byte) (outRight >> 8);
		}
	}

	/**
	 * 虚拟环绕声算法 —— 利用延迟线和声道交叉混合模拟环绕感。
	 * 
	 * <h4>哈斯效应实现</h4>
	 * <ol>
	 *   <li>将左右信号分别写入环形延迟线缓冲</li>
	 *   <li>读取 delaySamples 之前的延迟信号</li>
	 *   <li>将延迟后的对侧信号按 surroundMix × diffusion 混入本侧</li>
	 * </ol>
	 * 
	 * <pre>
	 *   L' = L × (1-mix) + R_delayed × mix × diffusion
	 *   R' = R × (1-mix) + L_delayed × mix × diffusion
	 * </pre>
	 */
	private void processVirtualSurround(byte[] input, byte[] output) {
		int frameSize = 4;

		for (int i = 0; i <= input.length - frameSize; i += frameSize) {
			short left  = (short) ((input[i + 1] << 8) | (input[i] & 0xFF));
			short right = (short) ((input[i + 3] << 8) | (input[i + 2] & 0xFF));

			float l = left / 32768.0f;
			float r = right / 32768.0f;

			// 应用延迟线获取延迟后的信号
			float delayedL = applyDelay(l, 0);
			float delayedR = applyDelay(r, 1);

			// 交叉混合原始与延迟信号
			float outL = l * (1.0f - surroundMix) + delayedR * surroundMix * diffusion;
			float outR = r * (1.0f - surroundMix) + delayedL * surroundMix * diffusion;

			outL = Math.max(-1.0f, Math.min(1.0f, outL));
			outR = Math.max(-1.0f, Math.min(1.0f, outR));

			short outLeft  = (short) (outL * 32767.0f);
			short outRight = (short) (outR * 32767.0f);

			output[i]     = (byte) (outLeft & 0xFF);
			output[i + 1] = (byte) (outLeft >> 8);
			output[i + 2] = (byte) (outRight & 0xFF);
			output[i + 3] = (byte) (outRight >> 8);
		}
	}

	/**
	 * 差分环绕算法 —— 提取并增强左右声道的差异信号。
	 * 
	 * <h4>两步处理</h4>
	 * <ol>
	 *   <li>计算差异信号 diff = L - R，并增强</li>
	 *   <li>通过差分的历史值实现简单高通滤波（去除低频 mono 成分）</li>
	 * </ol>
	 */
	private void processDifferentialSurround(byte[] input, byte[] output) {
		int frameSize = 4;

		for (int i = 0; i <= input.length - frameSize; i += frameSize) {
			short left  = (short) ((input[i + 1] << 8) | (input[i] & 0xFF));
			short right = (short) ((input[i + 3] << 8) | (input[i + 2] & 0xFF));

			float l = left / 32768.0f;
			float r = right / 32768.0f;

			// 计算差分和共同信号
			float diff   = l - r;
			float common = (l + r) * 0.5f;

			// 高通滤波差分信号：去除低频 mono 成分，只增强空间分量
			float hpDiff = diff - (prevLeft[0] + prevRight[0]) * 0.5f;
			float outL = common + hpDiff * surroundMix * 0.7f;
			float outR = common - hpDiff * surroundMix * 0.7f;

			// 更新历史存储
			prevLeft[0]  = prevLeft[1];   prevLeft[1]  = l;
			prevRight[0] = prevRight[1];  prevRight[1] = r;

			outL = Math.max(-1.0f, Math.min(1.0f, outL));
			outR = Math.max(-1.0f, Math.min(1.0f, outR));

			short outLeft  = (short) (outL * 32767.0f);
			short outRight = (short) (outR * 32767.0f);

			output[i]     = (byte) (outLeft & 0xFF);
			output[i + 1] = (byte) (outLeft >> 8);
			output[i + 2] = (byte) (outRight & 0xFF);
			output[i + 3] = (byte) (outRight >> 8);
		}
	}

	/**
	 * 宽立体声算法 —— M/S 分解 + 信号幅度相关的动态宽度控制 + 柔和限幅。
	 * 
	 * <p>与 STEREO_EXPAND 的区别：当 mid 幅度较大时自动减少宽度扩展，
	 * 避免大信号时的削波失真；使用 {@link #softClip(float)} 代替硬限幅。</p>
	 */
	private void processWideStereo(byte[] input, byte[] output) {
		int frameSize = 4;

		for (int i = 0; i <= input.length - frameSize; i += frameSize) {
			short left  = (short) ((input[i + 1] << 8) | (input[i] & 0xFF));
			short right = (short) ((input[i + 3] << 8) | (input[i + 2] & 0xFF));

			float l = left / 32768.0f;
			float r = right / 32768.0f;

			// M/S 分解
			float mid  = (l + r) * 0.5f;
			float side = (l - r) * 0.5f;

			// 动态宽度：大信号时减少扩展，避免失真
			float width = expandWidth;
			if (Math.abs(mid) > 0.7f) {
				width *= 0.8f; // 信号较大时收缩宽度
			}

			side *= width;

			float outL = mid + side;
			float outR = mid - side;

			// 柔和限幅（避免 hard clip 带来的谐波失真）
			outL = softClip(outL);
			outR = softClip(outR);

			short outLeft  = (short) (outL * 32767.0f);
			short outRight = (short) (outR * 32767.0f);

			output[i]     = (byte) (outLeft & 0xFF);
			output[i + 1] = (byte) (outLeft >> 8);
			output[i + 2] = (byte) (outRight & 0xFF);
			output[i + 3] = (byte) (outRight >> 8);
		}
	}

	/**
	 * 环形延迟线 —— 将样本写入缓冲区并在 delaySamples 后读取。
	 * 
	 * <h4>环形缓冲原理</h4>
	 * <pre>
	 *   写入: buffer[writeIndex] = input;  writeIndex = (writeIndex + 1) % len
	 *   读取: readIndex = (writeIndex - delaySamples + len) % len
	 *         output = buffer[readIndex]
	 * </pre>
	 * 
	 * @param input   输入样本
	 * @param channel 声道索引（0=左, 1=右）
	 * @return delaySamples 之前的样本
	 */
	private float applyDelay(float input, int channel) {
		if (delaySamples <= 0) return input;

		// 写入当前样本到环形缓冲
		delayLines[channel][delayIndices[channel]] = input;

		// 计算延迟后的读取位置（环形回绕）
		int readIndex = (delayIndices[channel] - delaySamples + delayLines[channel].length) % delayLines[channel].length;
		float delayed = delayLines[channel][readIndex];

		// 推进写入指针
		delayIndices[channel] = (delayIndices[channel] + 1) % delayLines[channel].length;

		return delayed;
	}

	/**
	 * 柔和限幅器（Soft Clipper） —— 用指数衰减代替硬削波。
	 * 
	 * <h4>为什么用柔和限幅？</h4>
	 * 硬限幅（if > 1.0 → 1.0）会产生奇数谐波失真（类似方波），
	 * 柔和限幅用连续函数逼近饱和特性，减少可听失真。
	 * 
	 * <pre>
	 *   当 sample > 1.0:  output = 1.0 - exp(-(sample - 1.0) × 2)
	 *   当 sample < -1.0: output = -1.0 + exp((sample + 1.0) × 2)
	 * </pre>
	 */
	private float softClip(float sample) {
		if (sample > 1.0f) {
			return 1.0f - (float) Math.exp(-(sample - 1.0f) * 2.0);
		} else if (sample < -1.0f) {
			return -1.0f + (float) Math.exp((sample + 1.0f) * 2.0);
		}
		return sample;
	}

	// ==================== Getter / Setter ====================

	public SurroundMode getMode() { return mode; }
	/** 设置环绕模式并重置状态（切换模式时清空延迟线等状态） */
	public void setMode(SurroundMode mode) {
		this.mode = mode;
		reset();
	}

	public float getExpandWidth() { return expandWidth; }
	public void setExpandWidth(float width) {
		this.expandWidth = Math.max(1.0f, Math.min(3.0f, width));
	}

	public float getSurroundMix() { return surroundMix; }
	public void setSurroundMix(float mix) {
		this.surroundMix = Math.max(0.0f, Math.min(1.0f, mix));
	}

	public float getDelayMs() { return delayMs; }
	/** 设置延迟时间并重新初始化延迟线缓冲区（缓冲大小取决于延迟时间） */
	public void setDelayMs(float delay) {
		this.delayMs = Math.max(1.0f, Math.min(50.0f, delay));
		initializeDelayLines();
	}

	public float getDiffusion() { return diffusion; }
	public void setDiffusion(float diff) {
		this.diffusion = Math.max(0.0f, Math.min(1.0f, diff));
	}

	/** 重置所有内部状态（延迟线缓冲 + 历史样本） */
	public void reset() {
		if (delayLines != null) {
			for (int i = 0; i < delayLines.length; i++) {
				java.util.Arrays.fill(delayLines[i], 0.0f);
			}
		}
		if (delayIndices != null) {
			java.util.Arrays.fill(delayIndices, 0);
		}
		if (prevLeft != null) {
			java.util.Arrays.fill(prevLeft, 0.0f);
		}
		if (prevRight != null) {
			java.util.Arrays.fill(prevRight, 0.0f);
		}
	}
}
