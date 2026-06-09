package sair.player.audio;

import javax.sound.sampled.AudioFormat;

/**
 * Viper 风格综合音效处理器 —— 集成 4 个独立音频效果模块。
 * 
 * <h3>模块及处理顺序</h3>
 * <ol>
 *   <li><b>BassEnhancer（低音增强）</b> — 低通滤波提取低频 → 增强后叠加回原信号</li>
 *   <li><b>TrebleEnhancer（高音增强）</b> — 高通滤波提取高频 → 增强后叠加</li>
 *   <li><b>Exciter（谐波激励器）</b> — 使用 tanh() 软削波生成偶次谐波 → 混合回原信号</li>
 *   <li><b>Compressor（动态压缩器）</b> — 包络跟踪 + 阈值/压缩比控制动态范围</li>
 * </ol>
 * 
 * <h3>关键设计原则</h3>
 * <ul>
 *   <li>每个 {@code process()} 方法先用 {@code System.arraycopy} 复制输入数据，避免引用赋值</li>
 *   <li>处理完所有模块后应用输出增益（0.1~3.0），通过限幅防止削波</li>
 * </ul>
 * 
 * <h3>预设</h3>
 * 6 种预设场景：natural, bass_boost, vocal_clarity, dynamic, warm, bright
 * 每种预设开启/关闭不同模块的特定组合并设置强度参数。
 */
public class ViperEffectsProcessor {

	private AudioFormat format;
	/** 总开关：启用/禁用整个 Viper 流水线 */
	private boolean enabled = false;

	// ── 四个效果模块 ──

	private BassEnhancer bassEnhancer;
	private TrebleEnhancer trebleEnhancer;
	private Compressor compressor;
	private Exciter exciter;

	// ── 全局参数 ──

	/** 输出增益（0.1 ~ 3.0），所有模块处理完后应用 */
	private float outputGain = 1.0f;
	/** 自动增益补偿（暂未使用，预留接口） */
	private boolean autoGain = true;

	public ViperEffectsProcessor(AudioFormat format) {
		this.format = format;
		this.bassEnhancer = new BassEnhancer(format);
		this.trebleEnhancer = new TrebleEnhancer(format);
		this.compressor = new Compressor(format);
		this.exciter = new Exciter(format);
	}

	/**
	 * 主处理流水线：按固定顺序依次调用各模块。
	 * 
	 * <h4>关键：复制数据而非引用赋值</h4>
	 * 每次创建新的 {@code byte[]} 并用 {@code System.arraycopy} 复制输入数据。
	 * 这避免了 Java 的引用传递导致后续模块修改原始数组的问题。
	 */
	public byte[] process(byte[] audioData) {
		if (!enabled || audioData == null || audioData.length == 0) {
			return audioData;
		}

		// 始终创建新的输出数组，避免引用问题
		byte[] processed = new byte[audioData.length];
		System.arraycopy(audioData, 0, processed, 0, audioData.length);

		// ① 低音增强 — 增加低频力度
		if (bassEnhancer.isEnabled()) {
			processed = bassEnhancer.process(processed);
		}

		// ② 高音增强 — 增加高频清晰度
		if (trebleEnhancer.isEnabled()) {
			processed = trebleEnhancer.process(processed);
		}

		// ③ 激励器 — 生成谐波增加音色的温暖感和细节
		if (exciter.isEnabled()) {
			processed = exciter.process(processed);
		}

		// ④ 动态压缩 — 控制动态范围，使整体响度更均匀
		if (compressor.isEnabled()) {
			processed = compressor.process(processed);
		}

		// ⑤ 输出增益 — 最后调整整体音量
		if (outputGain != 1.0f) {
			processed = applyGain(processed, outputGain);
		}

		return processed;
	}

	/**
	 * 对 PCM 数据应用线性增益并限幅。
	 * 
	 * <p>逐样本处理：short → float → ×gain → clamp(-1,1) → short → byte[]</p>
	 */
	private byte[] applyGain(byte[] input, float gain) {
		byte[] output = new byte[input.length];
		System.arraycopy(input, 0, output, 0, input.length);

		int frameSize = format.getChannels() * 2; // 16-bit per sample

		for (int i = 0; i <= input.length - frameSize; i += frameSize) {
			for (int ch = 0; ch < format.getChannels(); ch++) {
				int offset = i + ch * 2;
				short sample = (short) ((input[offset + 1] << 8) | (input[offset] & 0xFF));
				float processed = (sample / 32768.0f) * gain;

				// 限幅
				processed = Math.max(-1.0f, Math.min(1.0f, processed));

				short outSample = (short) (processed * 32767.0f);
				output[offset]     = (byte) (outSample & 0xFF);
				output[offset + 1] = (byte) (outSample >> 8);
			}
		}
		return output;
	}

	// ==================== Getter / Setter ====================

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }

	public BassEnhancer getBassEnhancer()       { return bassEnhancer; }
	public TrebleEnhancer getTrebleEnhancer()   { return trebleEnhancer; }
	public Compressor getCompressor()           { return compressor; }
	public Exciter getExciter()                 { return exciter; }

	public float getOutputGain() { return outputGain; }
	public void setOutputGain(float gain) {
		this.outputGain = Math.max(0.1f, Math.min(3.0f, gain));
	}

	public boolean isAutoGain()       { return autoGain; }
	public void setAutoGain(boolean auto) { this.autoGain = auto; }

	/** 重置所有子模块状态 */
	public void reset() {
		bassEnhancer.reset();
		trebleEnhancer.reset();
		compressor.reset();
		exciter.reset();
	}

	/**
	 * 应用 Viper 预设（组合开关子模块并设置强度）。
	 */
	public void applyPreset(String preset) {
		switch (preset.toLowerCase()) {
			case "natural":         // 自然：全部关闭
				setEnabled(true);
				bassEnhancer.setEnabled(false); trebleEnhancer.setEnabled(false);
				compressor.setEnabled(false);   exciter.setEnabled(false);
				break;
			case "bass_boost":      // 低音增强：Bass + 轻度压缩
				setEnabled(true);
				bassEnhancer.setEnabled(true);  bassEnhancer.setStrength(0.7f);
				trebleEnhancer.setEnabled(false);
				compressor.setEnabled(true);    compressor.setRatio(2.0f);
				exciter.setEnabled(false);
				break;
			case "vocal_clarity":   // 人声清晰：Treble + 压缩 + 轻度激励
				setEnabled(true);
				bassEnhancer.setEnabled(false);
				trebleEnhancer.setEnabled(true); trebleEnhancer.setStrength(0.5f);
				compressor.setEnabled(true);     compressor.setRatio(1.5f);
				exciter.setEnabled(true);        exciter.setAmount(0.3f);
				break;
			case "dynamic":         // 动态：全部开启，中等强度
				setEnabled(true);
				bassEnhancer.setEnabled(true);   bassEnhancer.setStrength(0.5f);
				trebleEnhancer.setEnabled(true); trebleEnhancer.setStrength(0.4f);
				compressor.setEnabled(true);     compressor.setRatio(2.5f);
				exciter.setEnabled(true);        exciter.setAmount(0.5f);
				break;
			case "warm":            // 温暖：Bass + 压缩 + 轻度激励
				setEnabled(true);
				bassEnhancer.setEnabled(true);   bassEnhancer.setStrength(0.6f);
				trebleEnhancer.setEnabled(false);
				compressor.setEnabled(true);     compressor.setRatio(1.8f);
				exciter.setEnabled(true);        exciter.setAmount(0.2f);
				break;
			case "bright":          // 明亮：Treble + 激励，无压缩
				setEnabled(true);
				bassEnhancer.setEnabled(false);
				trebleEnhancer.setEnabled(true); trebleEnhancer.setStrength(0.7f);
				compressor.setEnabled(false);
				exciter.setEnabled(true);        exciter.setAmount(0.6f);
				break;
		}
	}

	// ==================== 内部效果类 ====================

	/**
	 * 低音增强器 —— 通过 IIR 低通滤波提取低频成分并叠加回原信号。
	 * 
	 * <h4>公式</h4>
	 * <pre>
	 *   lp = s × 0.3 + prev × 0.7           (一阶低通: α=0.3)
	 *   output = s + lp × strength × 0.5    (增强低频)
	 * </pre>
	 * 低通滤波衰减高频，仅保留低频（~100Hz 以下），然后叠加回原信号实现低音增强。
	 */
	public static class BassEnhancer {
		private AudioFormat format;
		private boolean enabled = false;
		/** 增强强度 (0.0 ~ 1.0) */
		private float strength = 0.5f;

		/** 各声道前一帧的低通输出（用于 IIR 反馈） */
		private float[] prevSamples;

		public BassEnhancer(AudioFormat format) {
			this.format = format;
			this.prevSamples = new float[format.getChannels()];
		}

		public byte[] process(byte[] input) {
			byte[] output = new byte[input.length];
			System.arraycopy(input, 0, output, 0, input.length);

			int frameSize = format.getChannels() * 2;

			for (int i = 0; i <= input.length - frameSize; i += frameSize) {
				for (int ch = 0; ch < format.getChannels(); ch++) {
					int offset = i + ch * 2;
					short sample = (short) ((input[offset + 1] << 8) | (input[offset] & 0xFF));
					float s = sample / 32768.0f;

					// 一阶 IIR 低通滤波：提取低频
					float lp = s * 0.3f + prevSamples[ch] * 0.7f;
					prevSamples[ch] = lp;

					// 低频增强并限幅
					float enhanced = s + lp * strength * 0.5f;
					enhanced = Math.max(-1.0f, Math.min(1.0f, enhanced));

					short out = (short) (enhanced * 32767.0f);
					output[offset]     = (byte) (out & 0xFF);
					output[offset + 1] = (byte) (out >> 8);
				}
			}
			return output;
		}

		public boolean isEnabled() { return enabled; }
		public void setEnabled(boolean e) { this.enabled = e; }
		public float getStrength() { return strength; }
		public void setStrength(float s) { this.strength = Math.max(0.0f, Math.min(1.0f, s)); }
		public void reset() { java.util.Arrays.fill(prevSamples, 0.0f); }
	}

	/**
	 * 高音增强器 —— 通过一阶差分高通滤波提取高频成分并叠加回原信号。
	 * 
	 * <h4>公式</h4>
	 * <pre>
	 *   hp = s - prev            (一阶差分 = 高通)
	 *   output = s + hp × strength × 0.3
	 * </pre>
	 */
	public static class TrebleEnhancer {
		private AudioFormat format;
		private boolean enabled = false;
		private float strength = 0.5f;

		private float[] prevSamples;

		public TrebleEnhancer(AudioFormat format) {
			this.format = format;
			this.prevSamples = new float[format.getChannels()];
		}

		public byte[] process(byte[] input) {
			byte[] output = new byte[input.length];
			System.arraycopy(input, 0, output, 0, input.length);

			int frameSize = format.getChannels() * 2;

			for (int i = 0; i <= input.length - frameSize; i += frameSize) {
				for (int ch = 0; ch < format.getChannels(); ch++) {
					int offset = i + ch * 2;
					short sample = (short) ((input[offset + 1] << 8) | (input[offset] & 0xFF));
					float s = sample / 32768.0f;

					// 一阶差分高通：hp[n] = s[n] - s[n-1]
					float hp = s - prevSamples[ch];
					prevSamples[ch] = s;

					// 高频增强
					float enhanced = s + hp * strength * 0.3f;
					enhanced = Math.max(-1.0f, Math.min(1.0f, enhanced));

					short out = (short) (enhanced * 32767.0f);
					output[offset]     = (byte) (out & 0xFF);
					output[offset + 1] = (byte) (out >> 8);
				}
			}
			return output;
		}

		public boolean isEnabled() { return enabled; }
		public void setEnabled(boolean e) { this.enabled = e; }
		public float getStrength() { return strength; }
		public void setStrength(float s) { this.strength = Math.max(0.0f, Math.min(1.0f, s)); }
		public void reset() { java.util.Arrays.fill(prevSamples, 0.0f); }
	}

	/**
	 * 动态压缩器 —— 使用包络跟踪的经典下行压缩器。
	 * 
	 * <h4>工作原理</h4>
	 * <ol>
	 *   <li>通过峰值包络跟踪（attack/release 系数）估算信号幅度</li>
	 *   <li>当包络超过阈值（dB）时，按压缩比（ratio）计算增益衰减</li>
	 *   <li>attack=0.01s 快速响应，release=0.1s 较慢释放</li>
	 * </ol>
	 * 
	 * <h4>包络跟踪</h4>
	 * <pre>
	 *   attackCoeff  = exp(-1/(attack × sampleRate))
	 *   releaseCoeff = exp(-1/(release × sampleRate))
	 *   当 |s| > envelope:  envelope = |s|·attack  + envelope·(1-attack)
	 *   当 |s| ≤ envelope:  envelope = |s|·release + envelope·(1-release)
	 * </pre>
	 */
	public static class Compressor {
		private AudioFormat format;
		private boolean enabled = false;
		/** 阈值 dB，超过此值开始压缩（默认 -20dB） */
		private float threshold = -20.0f;
		/** 压缩比，2.0 = 2:1 压缩比（输入高于阈值 2dB 时输出仅高 1dB） */
		private float ratio = 2.0f;
		/** 启动时间（秒），影响包络上升速度 */
		private float attack = 0.01f;
		/** 释放时间（秒），影响包络下降速度 */
		private float release = 0.1f;

		/** 当前包络值（跨帧持续跟踪） */
		private float envelope = 0.0f;

		public Compressor(AudioFormat format) {
			this.format = format;
		}

		public byte[] process(byte[] input) {
			byte[] output = new byte[input.length];
			System.arraycopy(input, 0, output, 0, input.length);

			int frameSize = format.getChannels() * 2;
			// 预计算包络系数（一次计算避免循环内重复）
			float attackCoeff  = (float) Math.exp(-1.0f / (attack * format.getSampleRate()));
			float releaseCoeff = (float) Math.exp(-1.0f / (release * format.getSampleRate()));

			for (int i = 0; i <= input.length - frameSize; i += frameSize) {
				for (int ch = 0; ch < format.getChannels(); ch++) {
					int offset = i + ch * 2;
					short sample = (short) ((input[offset + 1] << 8) | (input[offset] & 0xFF));
					float s = sample / 32768.0f;

					// 峰值包络跟踪
					float absS = Math.abs(s);
					envelope = absS > envelope
						? absS * attackCoeff + envelope * (1 - attackCoeff)     // 上升（attack）
						: absS * releaseCoeff + envelope * (1 - releaseCoeff);   // 下降（release）

					// 计算压缩增益
					float gain = 1.0f;
					float envDB = (float) (20.0 * Math.log10(Math.max(0.001f, envelope)));
					if (envDB > threshold) {
						// 压缩公式：超出部分 / ratio
						float excess = envDB - threshold;
						float reducedDB = threshold + excess / ratio;
						gain = (float) Math.pow(10.0, (reducedDB - envDB) / 20.0);
					}

					float compressed = s * gain;
					compressed = Math.max(-1.0f, Math.min(1.0f, compressed));

					short out = (short) (compressed * 32767.0f);
					output[offset]     = (byte) (out & 0xFF);
					output[offset + 1] = (byte) (out >> 8);
				}
			}
			return output;
		}

		public boolean isEnabled()  { return enabled; }
		public void setEnabled(boolean e) { this.enabled = e; }
		public float getRatio()     { return ratio; }
		public void setRatio(float r) { this.ratio = Math.max(1.0f, Math.min(10.0f, r)); }
		public void reset()         { envelope = 0.0f; }
	}

	/**
	 * 谐波激励器 —— 使用 tanh() 软削波函数生成偶次谐波。
	 * 
	 * <h4>原理</h4>
	 * <pre>
	 *   harmonic = tanh(s × 2.0) × 0.5     — 软削波生成谐波
	 *   output = s × (1 - 0.3×amount) + harmonic × 0.3×amount
	 * </pre>
	 * tanh() 是非线性函数，会引入偶次谐波（温暖、管味），
	 * 适量的谐波使声音更有"模拟味"和细节感。
	 */
	public static class Exciter {
		private AudioFormat format;
		private boolean enabled = false;
		/** 激励量 (0.0 ~ 1.0) */
		private float amount = 0.5f;

		public Exciter(AudioFormat format) {
			this.format = format;
		}

		public byte[] process(byte[] input) {
			byte[] output = new byte[input.length];
			System.arraycopy(input, 0, output, 0, input.length);

			int frameSize = format.getChannels() * 2;

			for (int i = 0; i <= input.length - frameSize; i += frameSize) {
				for (int ch = 0; ch < format.getChannels(); ch++) {
					int offset = i + ch * 2;
					short sample = (short) ((input[offset + 1] << 8) | (input[offset] & 0xFF));
					float s = sample / 32768.0f;

					// tanh() 软削波生成谐波（类似模拟磁带饱和）
					float harmonic = (float) Math.tanh(s * 2.0) * 0.5f;

					// 混合原始信号与谐波
					float excited = s * (1.0f - amount * 0.3f) + harmonic * amount * 0.3f;
					excited = Math.max(-1.0f, Math.min(1.0f, excited));

					short out = (short) (excited * 32767.0f);
					output[offset]     = (byte) (out & 0xFF);
					output[offset + 1] = (byte) (out >> 8);
				}
			}
			return output;
		}

		public boolean isEnabled()  { return enabled; }
		public void setEnabled(boolean e) { this.enabled = e; }
		public float getAmount()    { return amount; }
		public void setAmount(float a) { this.amount = Math.max(0.0f, Math.min(1.0f, a)); }
		public void reset() {}
	}
}
