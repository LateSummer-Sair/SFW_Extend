package sair.player.audio;

import javax.sound.sampled.AudioFormat;

/**
 * 专业 15 段参数均衡器 —— 基于级联双二阶 IIR 滤波器。
 * 
 * <h3>频段配置</h3>
 * 15 个 ISO 标准 1/3 倍频程频段，覆盖 20Hz ~ 20kHz 人耳可听范围：
 * <table border="1">
 *   <tr><th>频段</th><td>0</td><td>1</td><td>2</td><td>3</td><td>4</td><td>5</td><td>6</td><td>7</td>
 *       <td>8</td><td>9</td><td>10</td><td>11</td><td>12</td><td>13</td><td>14</td></tr>
 *   <tr><th>Hz</th><td>20</td><td>35</td><td>60</td><td>100</td><td>180</td><td>320</td>
 *       <td>600</td><td>1.2k</td><td>2.4k</td><td>4.8k</td><td>7.5k</td><td>10k</td><td>13k</td><td>16k</td><td>20k</td></tr>
 * </table>
 * 
 * <h3>滤波器类型</h3>
 * 使用 {@link BiquadFilter.FilterType#PEAKING} 峰值滤波器，每个频段独立控制中心频率的增益。
 * Q = 1.414（Butterworth 响应），带宽 = f₀/Q。
 * 
 * <h3>增益范围</h3>
 * 线性增益 0.125 ~ 8.0，对应 dB 范围约 -18dB ~ +18dB。
 * 
 * <h3>处理流程</h3>
 * <ol>
 *   <li>从字节数组解析 16-bit PCM 样本 → 归一化浮点（-1.0 ~ 1.0）</li>
 *   <li>每个样本依次通过 15 个 PEAKING 滤波器（仅增益≠1.0 的频段参与计算）</li>
 *   <li>硬限幅（hard clip）到 (-1.0, 1.0)</li>
 *   <li>转回 16-bit PCM 字节输出</li>
 * </ol>
 * 
 * @see BiquadFilter 滤波器核心实现
 */
public class EQProcessor {

	/** 频段总数：15 */
	public static final int BAND_COUNT = 15;

	/** 各频段当前增益（线性值） */
	private float[] bandGains;
	/** 各频段中心频率（Hz） */
	private float[] centerFrequencies;
	/** PCM 音频格式（提供采样率等参数） */
	private AudioFormat format;
	/** 15 个双二阶滤波器实例 */
	private BiquadFilter[] filters;
	/** 各频段 Q 值（默认 1.414） */
	private float[] qValues;

	/**
	 * 构造 15 段均衡器。
	 * <p>初始化全部频段为 0dB（增益=1.0），Q=1.414。</p>
	 */
	public EQProcessor(AudioFormat format) {
		this.format = format;
		this.bandGains = new float[BAND_COUNT];
		// ISO 1/3 倍频程标准频率点
		this.centerFrequencies = new float[] {
			20f, 35f, 60f, 100f, 180f, 320f, 600f, 1200f,
			2400f, 4800f, 7500f, 10000f, 13000f, 16000f, 20000f
		};

		// 初始化为 0dB（1.0 线性增益 = 无效果）
		for (int i = 0; i < BAND_COUNT; i++) {
			bandGains[i] = 1.0f;
		}

		filters = new BiquadFilter[BAND_COUNT];
		qValues = new float[BAND_COUNT];

		// 创建 15 个 PEAKING 滤波器，Q = 1.414（Butterworth 响应）
		for (int i = 0; i < BAND_COUNT; i++) {
			qValues[i] = 1.414f;
			filters[i] = new BiquadFilter(format, BiquadFilter.FilterType.PEAKING,
				centerFrequencies[i], qValues[i], bandGains[i]);
		}
	}

	/**
	 * 处理音频数据（EQ 均衡）。
	 * <p>仅处理 16-bit PCM，对于其他位深直接透传。</p>
	 */
	public byte[] process(byte[] audioData) {
		if (audioData == null || audioData.length == 0) {
			return audioData;
		}

		boolean isStereo = format.getChannels() == 2;
		int sampleSize = format.getSampleSizeInBits();
		boolean isBigEndian = format.isBigEndian();

		byte[] processed = new byte[audioData.length];

		if (sampleSize == 16) {
			process16Bit(audioData, processed, isStereo, isBigEndian);
		} else {
			// 非 16-bit 数据直接透传（当前项目仅使用 16-bit）
			System.arraycopy(audioData, 0, processed, 0, audioData.length);
		}

		return processed;
	}

	/**
	 * 16-bit PCM 处理。
	 * 
	 * <h4>数据解析</h4>
	 * <ul>
	 *   <li>立体声帧 = 4 字节（L低+ L高 + R低+ R高），小端序</li>
	 *   <li>单声道帧 = 2 字节</li>
	 *   <li>short → float：除以 32768（非对称缩放，正负范围一致）</li>
	 *   <li>float → short：乘以 32767 后限幅</li>
	 * </ul>
	 */
	private void process16Bit(byte[] input, byte[] output, boolean isStereo, boolean isBigEndian) {
		int bytesPerSample = 2;
		int frameSize = isStereo ? 4 : 2; // 每帧字节数

		for (int i = 0; i <= input.length - frameSize; i += frameSize) {
			for (int ch = 0; ch < (isStereo ? 2 : 1); ch++) {
				int offset = i + (ch * bytesPerSample);

				// 从字节数组重建 short 样本值
				short sample = isBigEndian
					? (short) ((input[offset] << 8) | (input[offset + 1] & 0xFF))
					: (short) ((input[offset + 1] << 8) | (input[offset] & 0xFF));

				// 归一化到 [-1.0, 1.0]
				float sampleFloat = sample / 32768.0f;

				// 应用 15 段 EQ
				float processedSample = applyEQ(sampleFloat);

				// 转回 short（含硬限幅）
				short processedShort = (short) (processedSample * 32767.0f);
				if (processedShort > 32767)  processedShort = 32767;
				if (processedShort < -32768) processedShort = -32768;

				// 写回字节数组
				if (isBigEndian) {
					output[offset]     = (byte) (processedShort >> 8);
					output[offset + 1] = (byte) (processedShort & 0xFF);
				} else {
					output[offset]     = (byte) (processedShort & 0xFF);
					output[offset + 1] = (byte) (processedShort >> 8);
				}
			}
		}
	}

	/**
	 * 应用全部 15 段 EQ 到单个样本。
	 * 
	 * <h4>优化：跳过 0dB 频段</h4>
	 * {@code if (bandGains[i] != 1.0f)} 跳过无需处理的频段，
	 * 避免无效的滤波器计算（当大部分频段为 0dB 时显著提升性能）。
	 * 
	 * @param sample 归一化浮点样本
	 * @return 均衡化后的样本（已限幅）
	 */
	private float applyEQ(float sample) {
		float output = sample;

		for (int i = 0; i < BAND_COUNT; i++) {
			if (bandGains[i] != 1.0f) { // 仅处理非 0dB 频段
				output = filters[i].process(output);
			}
		}

		// 硬限幅（hard clip）：防止级联滤波器导致溢出
		if (output > 1.0f)  output = 1.0f;
		if (output < -1.0f) output = -1.0f;

		return output;
	}

	// ==================== 频段增益控制 ====================

	/**
	 * 设置单个频段增益并更新滤波器系数。
	 * @param band 频段编号（0~14）
	 * @param gain 线性增益值（1.0 = 0dB）
	 */
	public void setBandGain(int band, float gain) {
		if (band >= 0 && band < BAND_COUNT) {
			bandGains[band] = gain;
			filters[band].updateParameters(centerFrequencies[band], qValues[band], gain);
		}
	}

	/** @return 指定频段增益（线性值） */
	public float getBandGain(int band) {
		return (band >= 0 && band < BAND_COUNT) ? bandGains[band] : 1.0f;
	}

	/** 重置所有频段到 0dB */
	public void resetEQ() {
		for (int i = 0; i < BAND_COUNT; i++) {
			bandGains[i] = 1.0f;
			filters[i].reset(); // 清除滤波器状态（延迟线）
			filters[i].updateParameters(centerFrequencies[i], qValues[i], 1.0f);
		}
	}

	/** @return 所有频段增益的克隆副本（防止外部直接修改内部数组） */
	public float[] getAllBandGains() {
		return bandGains.clone();
	}

	/** 批量设置所有频段增益（如加载预设） */
	public void setAllBandGains(float[] gains) {
		if (gains != null && gains.length == BAND_COUNT) {
			System.arraycopy(gains, 0, bandGains, 0, BAND_COUNT);
			for (int i = 0; i < BAND_COUNT; i++) {
				filters[i].updateParameters(centerFrequencies[i], qValues[i], gains[i]);
			}
		}
	}

	// ==================== Q 值控制 ====================

	/** 设置频段的 Q 值（影响带宽） */
	public void setBandQ(int band, float q) {
		if (band >= 0 && band < BAND_COUNT && q > 0) {
			qValues[band] = q;
			filters[band].updateParameters(centerFrequencies[band], q, bandGains[band]);
		}
	}

	public float getBandQ(int band) {
		return (band >= 0 && band < BAND_COUNT) ? qValues[band] : 1.414f;
	}

	// ==================== UI 标签 ====================

	/** @return 频段标签数组（如 "20Hz", "1.2kHz", "20kHz"） */
	public String[] getBandLabels() {
		String[] labels = new String[BAND_COUNT];
		for (int i = 0; i < BAND_COUNT; i++) {
			if (centerFrequencies[i] >= 1000) {
				labels[i] = (int) (centerFrequencies[i] / 1000) + "kHz";
			} else {
				labels[i] = (int) centerFrequencies[i] + "Hz";
			}
		}
		return labels;
	}

	public float[] getCenterFrequencies() {
		return centerFrequencies.clone();
	}

	// ==================== EQ 预设 ====================

	/**
	 * 应用 EQ 预设（覆盖所有频段）。
	 * 
	 * <h4>预设列表</h4>
	 * <table border="1">
	 *   <tr><th>预设名</th><th>效果描述</th></tr>
	 *   <tr><td>flat</td><td>平坦（所有频段 0dB）</td></tr>
	 *   <tr><td>bass_boost</td><td>低音增强（低频 +3~6dB）</td></tr>
	 *   <tr><td>treble_boost</td><td>高音增强（高频 +2~7dB）</td></tr>
	 *   <tr><td>vocal</td><td>人声增强（中频提升）</td></tr>
	 *   <tr><td>rock</td><td>摇滚（V 形曲线）</td></tr>
	 *   <tr><td>pop</td><td>流行（微 V 形）</td></tr>
	 *   <tr><td>jazz</td><td>爵士（柔和平滑）</td></tr>
	 *   <tr><td>classical</td><td>古典（略偏高音）</td></tr>
	 *   <tr><td>bass_reduce</td><td>低音衰减</td></tr>
	 * </table>
	 */
	public void applyPreset(String presetName) {
		float[] gains = new float[BAND_COUNT];

		switch (presetName.toLowerCase()) {
		case "flat":
			for (int i = 0; i < BAND_COUNT; i++) gains[i] = 1.0f;
			break;
		case "bass_boost":
			gains = new float[] { 2.0f, 1.8f, 1.6f, 1.4f, 1.2f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f };
			break;
		case "treble_boost":
			gains = new float[] { 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 2.2f };
			break;
		case "vocal":
			gains = new float[] { 0.8f, 0.9f, 1.0f, 1.2f, 1.5f, 1.8f, 2.0f, 1.8f, 1.5f, 1.2f, 1.0f, 0.9f, 0.8f, 0.8f, 0.7f };
			break;
		case "rock":
			gains = new float[] { 1.8f, 1.6f, 1.3f, 1.0f, 0.9f, 1.0f, 1.2f, 1.4f, 1.6f, 1.8f, 2.0f, 1.8f, 1.6f, 1.4f, 1.2f };
			break;
		case "pop":
			gains = new float[] { 1.2f, 1.3f, 1.2f, 1.0f, 0.9f, 1.0f, 1.3f, 1.5f, 1.4f, 1.2f, 1.0f, 0.9f, 0.9f, 1.0f, 1.1f };
			break;
		case "jazz":
			gains = new float[] { 1.3f, 1.2f, 1.1f, 1.0f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.3f, 1.2f, 1.1f, 1.0f, 1.0f, 1.1f };
			break;
		case "classical":
			gains = new float[] { 1.1f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.1f, 1.2f, 1.3f };
			break;
		case "bass_reduce":
			gains = new float[] { 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f };
			break;
		default:
			for (int i = 0; i < BAND_COUNT; i++) gains[i] = 1.0f;
			break;
		}

		setAllBandGains(gains);
	}

	public String[] getPresetNames() {
		return new String[] { "Flat", "Bass Boost", "Treble Boost", "Vocal", "Rock", "Pop", "Jazz", "Classical", "Bass Reduce" };
	}
}
