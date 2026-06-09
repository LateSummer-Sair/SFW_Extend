package sair.player.audio;

import javax.sound.sampled.AudioFormat;

/**
 * 声场扩展处理器 (Soundstage Expander) —— 模拟更宽广的声场空间感。
 * 
 * <h3>核心原理</h3>
 * 通过 M/S 分解 → 宽带/深度/高度三维调整 → 简化 HRTF 空间化 → 输出，
 * 类似 Viper4Android 的场深控制。
 * 
 * <h3>三维参数</h3>
 * <table border="1">
 *   <tr><th>width</th><td>0.5~3.0</td><td>水平宽度，控制 side 信号放大倍数</td></tr>
 *   <tr><th>depth</th><td>0.0~2.0</td><td>深度/混响，混入延迟+衰减的 mid 信号</td></tr>
 *   <tr><th>height</th><td>0.5~2.0</td><td>高度感，通过高频增强（高架滤波器）模拟</td></tr>
 *   <tr><th>centerImage</th><td>0.0~2.0</td><td>中置图像强度，控制 mid 信号的放大倍数</td></tr>
 * </table>
 * 
 * <h3>信号处理流程</h3>
 * <ol>
 *   <li>M/S 分解（L/R → Mid/Side）</li>
 *   <li>宽度扩展（放大 Side 信号）</li>
 *   <li>中置图像控制（缩放 Mid 信号）</li>
 *   <li>深度/混响（混响处理的 Mid 信号混入原 Mid）</li>
 *   <li>HRTF 空间化（用 FIR 滤波器模拟头部传递函数）</li>
 *   <li>高度感（差分高通，增强高频）</li>
 * </ol>
 * 
 * <p>仅处理立体声（双声道），单声道数据透传。</p>
 */
public class SoundstageExpander {

	private AudioFormat format;
	private boolean enabled = false;

	// ── 声场参数 ──

	private float width = 1.0f;        // 宽度扩展 (0.5 ~ 3.0)
	private float depth = 1.0f;        // 深度/混响 (0.0 ~ 2.0)
	private float height = 1.0f;       // 高度感 (0.5 ~ 2.0)
	private float centerImage = 1.0f;  // 中置图像强度 (0.0 ~ 2.0)

	// ── 混响缓冲 ──

	/** 环形混响缓冲区：存储 100ms 的历史信号 */
	private float[] reverbBuffer;
	/** 混响缓冲区写入指针 */
	private int reverbIndex;
	/** 混响反馈系数 (0.0 ~ 0.9)，值越大混响持续时间越长 */
	private float reverbFeedback = 0.3f;
	/** 混响阻尼系数 (0.0 ~ 1.0)，值越大高频衰减越快 */
	private float reverbDamping = 0.5f;

	// ── HRTF 简化模型 ──

	/** 左耳 HRTF 系数（简化 FIR） */
	private float[] hrtfLeft;
	/** 右耳 HRTF 系数（简化 FIR） */
	private float[] hrtfRight;

	public SoundstageExpander(AudioFormat format) {
		this.format = format;
		initializeReverb();
		initializeHRTF();
	}

	/** 初始化混响缓冲区：100ms @ sampleRate */
	private void initializeReverb() {
		int bufferSize = (int) (format.getSampleRate() * 0.1); // 100ms
		reverbBuffer = new float[Math.max(bufferSize, 1024)];
		reverbIndex = 0;
	}

	/** 初始化简化 HRTF 系数 */
	private void initializeHRTF() {
		// 三阶简化的头部相关传递函数
		hrtfLeft  = new float[] { 1.0f, -0.3f, 0.1f };
		hrtfRight = new float[] { 1.0f, -0.3f, 0.1f };
	}

	/**
	 * 处理音频数据。
	 * <p>仅在 enabled=true 且为立体声时处理，否则透传。</p>
	 */
	public byte[] process(byte[] audioData) {
		if (!enabled || audioData == null || audioData.length == 0) {
			return audioData;
		}
		if (format.getChannels() != 2) {
			return audioData;
		}
		byte[] processed = new byte[audioData.length];
		processSoundstage(audioData, processed);
		return processed;
	}

	/**
	 * 声场扩展主处理 —— 对每帧立体声样本执行完整流水线。
	 */
	private void processSoundstage(byte[] input, byte[] output) {
		int frameSize = 4;
		// 输入/输出历史（用于 HRTF 和高度处理的 FIR 滤波）
		float[] prevInputL  = {0, 0};  float[] prevInputR  = {0, 0};
		float[] prevOutputL = {0, 0};  float[] prevOutputR = {0, 0};

		for (int i = 0; i <= input.length - frameSize; i += frameSize) {
			short left  = (short) ((input[i + 1] << 8) | (input[i] & 0xFF));
			short right = (short) ((input[i + 3] << 8) | (input[i + 2] & 0xFF));

			float l = left / 32768.0f;
			float r = right / 32768.0f;

			// ① M/S 分解
			float mid  = (l + r) * 0.5f;
			float side = (l - r) * 0.5f;

			// ② 宽度扩展（放大立体声差异）
			side *= width;

			// ③ 中置图像控制（缩放单声道分量）
			mid *= centerImage;

			// ④ 深度处理（混响混入 Mid）
			float reverbed = applyReverb(mid);
			mid = mid * (1.0f - depth * 0.3f) + reverbed * depth * 0.3f;

			// ⑤ HRTF 空间化
			float hrtfL = applyHRTF(mid, side, true,  prevInputL,  prevOutputL);
			float hrtfR = applyHRTF(mid, side, false, prevInputR,  prevOutputR);

			// ⑥ 高度感效果
			float outL = applyHeightEffect(hrtfL, prevOutputL);
			float outR = applyHeightEffect(hrtfR, prevOutputR);

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
	 * 应用简单混响 —— 环形缓冲区 + 延迟读取 + 反馈阻尼。
	 * <p>每次读取 20ms 前的样本，乘以 feedback 后返回。</p>
	 */
	private float applyReverb(float input) {
		if (reverbBuffer.length == 0) return input;

		// 写入当前样本到环形缓冲
		reverbBuffer[reverbIndex] = input;

		// 读取 20ms 前的样本（模拟早期反射）
		int delaySamples = (int) (format.getSampleRate() * 0.02); // 20ms
		int readIndex = (reverbIndex - delaySamples + reverbBuffer.length) % reverbBuffer.length;
		float reverbed = reverbBuffer[readIndex] * reverbFeedback;

		// 阻尼（低通滤波）：衰减高频
		reverbed *= (1.0f - reverbDamping * 0.5f);

		// 推进写入指针
		reverbIndex = (reverbIndex + 1) % reverbBuffer.length;

		return reverbed;
	}

	/**
	 * 应用简化 HRTF 处理 —— 简化的三阶 FIR 滤波器。
	 * 
	 * <h4>公式</h4>
	 * <pre>
	 *   对于左耳:  output = coeffs[0]·mid - coeffs[1]·side + coeffs[2]·prevIn[0]
	 *   对于右耳:  output = coeffs[0]·mid + coeffs[1]·side + coeffs[2]·prevIn[0]
	 * </pre>
	 * 侧边信号对左右耳的相位相反，模拟 ITD（耳间时间差）。
	 */
	private float applyHRTF(float mid, float side, boolean isLeft,
	                       float[] prevIn, float[] prevOut) {
		float[] coeffs = isLeft ? hrtfLeft : hrtfRight;

		// 简化 FIR 滤波
		float output = coeffs[0] * mid +
		              (isLeft ? -coeffs[1] : coeffs[1]) * side + // 相位差模拟
		              coeffs[2] * prevIn[0];

		// 更新输入历史
		prevIn[0] = prevIn[1];
		prevIn[1] = mid;

		return output;
	}

	/**
	 * 应用高度感效果 —— 差分高通增强。
	 * 
	 * <h4>原理</h4>
	 * 人耳感知声源高度的线索主要在高频段，
	 * 通过提取信号的高频差分分量并放大来实现高度感。
	 * 
	 * <pre>
	 *   highFreq = input - prevOut[0]           (一阶微分 = 高通)
	 *   output = input + highFreq × (height - 1) × 0.3
	 * </pre>
	 * 当 height > 1.0 时增强高频 → 声源感更高。
	 */
	private float applyHeightEffect(float input, float[] prevOut) {
		float highFreq = input - prevOut[0];
		prevOut[0] = prevOut[1];
		prevOut[1] = input;

		return input + highFreq * (height - 1.0f) * 0.3f;
	}

	// ==================== Getter / Setter ====================

	public boolean isEnabled() { return enabled; }
	public void setEnabled(boolean enabled) { this.enabled = enabled; }

	public float getWidth()  { return width; }
	public void setWidth(float w)  { this.width  = Math.max(0.5f, Math.min(3.0f, w)); }

	public float getDepth()  { return depth; }
	public void setDepth(float d)  { this.depth  = Math.max(0.0f, Math.min(2.0f, d)); }

	public float getHeight() { return height; }
	public void setHeight(float h) { this.height = Math.max(0.5f, Math.min(2.0f, h)); }

	public float getCenterImage() { return centerImage; }
	public void setCenterImage(float c) { this.centerImage = Math.max(0.0f, Math.min(2.0f, c)); }

	public float getReverbFeedback() { return reverbFeedback; }
	public void setReverbFeedback(float fb) {
		this.reverbFeedback = Math.max(0.0f, Math.min(0.9f, fb));
	}

	public float getReverbDamping() { return reverbDamping; }
	public void setReverbDamping(float d) {
		this.reverbDamping = Math.max(0.0f, Math.min(1.0f, d));
	}

	/** 重置混响缓冲区 */
	public void reset() {
		if (reverbBuffer != null) {
			java.util.Arrays.fill(reverbBuffer, 0.0f);
		}
		reverbIndex = 0;
	}

	/**
	 * 应用预设。
	 * <table border="1">
	 *   <tr><th>small_room</th><td>小房间（窄+浅+中置突出）</td></tr>
	 *   <tr><th>large_hall</th><td>大厅（宽+深+混响丰富）</td></tr>
	 *   <tr><th>concert</th><td>音乐会（极宽+深+高）</td></tr>
	 *   <tr><th>studio</th><td>录音室（窄+浅+中置最强）</td></tr>
	 *   <tr><th>wide</th><td>超宽（极宽+中等深度）</td></tr>
	 * </table>
	 */
	public void applyPreset(String preset) {
		switch (preset.toLowerCase()) {
			case "small_room":
				setWidth(1.2f);  setDepth(0.5f);  setHeight(1.0f);
				setCenterImage(1.2f); setReverbFeedback(0.2f); break;
			case "large_hall":
				setWidth(1.8f);  setDepth(1.5f);  setHeight(1.3f);
				setCenterImage(0.8f); setReverbFeedback(0.5f); break;
			case "concert":
				setWidth(2.2f);  setDepth(1.8f);  setHeight(1.5f);
				setCenterImage(0.7f); setReverbFeedback(0.6f); break;
			case "studio":
				setWidth(1.0f);  setDepth(0.2f);  setHeight(1.0f);
				setCenterImage(1.5f); setReverbFeedback(0.1f); break;
			case "wide":
				setWidth(2.5f);  setDepth(0.8f);  setHeight(1.2f);
				setCenterImage(0.9f); setReverbFeedback(0.3f); break;
		}
	}
}
