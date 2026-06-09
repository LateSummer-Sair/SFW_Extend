package sair.player.audio;

import javax.sound.sampled.AudioFormat;

/**
 * 双二阶滤波器（Biquad IIR Filter）—— 基于 RBJ Audio-EQ-Cookbook 公式。
 * 
 * <h3>传递函数</h3>
 * <pre>
 *             b0 + b1·z⁻¹ + b2·z⁻²
 *   H(z) = ──────────────────────────
 *             1 + a1·z⁻¹ + a2·z⁻²
 * </pre>
 * 
 * <h3>时域差分方程</h3>
 * <pre>
 *   y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] - a1·y[n-1] - a2·y[n-2]
 * </pre>
 * 
 * <h3>支持的滤波器类型</h3>
 * <ul>
 *   <li><b>PEAKING</b> — 峰值滤波器（EQ 频段使用，带增益参数）</li>
 *   <li><b>LOWPASS</b> / <b>HIGHPASS</b> — 低通/高通</li>
 *   <li><b>LOWSHELF</b> / <b>HIGHSHELF</b> — 低频/高频搁架</li>
 *   <li><b>NOTCH</b> — 陷波滤波器</li>
 *   <li><b>BANDPASS</b> — 带通滤波器</li>
 * </ul>
 * 
 * <h3>系数计算公式来源</h3>
 * 参考 Robert Bristow-Johnson 的 "Audio EQ Cookbook"，
 * 使用双线性变换从模拟域 s 平面映射到数字域 z 平面。
 * 
 * <h3>状态变量</h3>
 * 每个滤波器实例维护 4 个历史状态：
 * <ul>
 *   <li>{@code x1, x2} — 输入延迟线（x[n-1], x[n-2]）</li>
 *   <li>{@code y1, y2} — 输出延迟线（y[n-1], y[n-2]）</li>
 * </ul>
 * 
 * @see EQProcessor 均衡器（使用 15 个 PEAKING 类型的 BiquadFilter）
 */
public class BiquadFilter {

	/** 滤波器类型枚举 */
	public enum FilterType {
		PEAKING, LOWPASS, HIGHPASS, LOWSHELF, HIGHSHELF, NOTCH, BANDPASS
	}

	// ==================== 滤波器参数 ====================

	private FilterType type;
	/** 采样率（Hz），如 44100 */
	private float sampleRate;
	/** 中心/截止频率（Hz） */
	private float frequency;
	/** 品质因数 Q（带宽 = f₀/Q），默认 1.414（Butterworth 响应） */
	private float Q;
	/** 增益（线性值），1.0 = 0dB，即无增益；仅 PEAKING/SHELF 类型使用 */
	private float gain;

	// ==================== 传递函数系数 ====================

	/** 前馈系数：b0, b1, b2 */
	private float b0, b1, b2;
	/** 反馈系数：a1, a2（a0 始终归一化为 1.0） */
	private float a1, a2;

	// ==================== 状态变量（延迟线） ====================

	/** 输入历史：x1 = x[n-1], x2 = x[n-2] */
	private float x1, x2;
	/** 输出历史：y1 = y[n-1], y2 = y[n-2] */
	private float y1, y2;

	/**
	 * 构造双二阶滤波器并计算系数。
	 * 
	 * @param format    PCM 音频格式（提供采样率）
	 * @param type      滤波器类型
	 * @param frequency 中心/截止频率（Hz）
	 * @param Q         品质因数
	 * @param gain      线性增益值（1.0 = 0dB）
	 */
	public BiquadFilter(AudioFormat format, FilterType type, float frequency, float Q, float gain) {
		this.sampleRate = format.getSampleRate();
		this.type = type;
		this.frequency = frequency;
		this.Q = Q;
		this.gain = gain;
		calculateCoefficients();
		reset();
	}

	/**
	 * 根据当前参数计算传递函数系数（b0, b1, b2, a1, a2）。
	 * 
	 * <h4>核心中间变量</h4>
	 * <ul>
	 *   <li>{@code w0 = 2π·f/fₛ} — 归一化角频率（弧度/样本）</li>
	 *   <li>{@code alpha = sin(w0)/(2Q)} — 带宽参数，控制滤波器的锐度</li>
	 *   <li>{@code A = √(gain)} — 线性增益的平方根，用于 dB 到线性转换</li>
	 * </ul>
	 */
	private void calculateCoefficients() {
		float w0 = (float) (2.0 * Math.PI * frequency / sampleRate);
		float cosW = (float) Math.cos(w0);
		float sinW = (float) Math.sin(w0);
		float alpha = sinW / (2.0f * Q);
		// A = 10^(dB_gain/40)，因为峰值滤波器增益在 dB 域是对称的
		float A = (float) Math.sqrt(Math.max(0.001f, gain));

		switch (type) {
		case PEAKING:    calculatePeaking(w0, cosW, sinW, alpha, A);   break;
		case LOWPASS:    calculateLowPass(w0, cosW, sinW, alpha);     break;
		case HIGHPASS:   calculateHighPass(w0, cosW, sinW, alpha);    break;
		case LOWSHELF:   calculateLowShelf(w0, cosW, sinW, alpha, A); break;
		case HIGHSHELF:  calculateHighShelf(w0, cosW, sinW, alpha, A); break;
		case NOTCH:      calculateNotch(w0, cosW, sinW, alpha);       break;
		case BANDPASS:   calculateBandPass(w0, cosW, sinW, alpha);    break;
		}
	}

	// ======== RBJ 公式实现 ========

	/**
	 * PEAKING（峰值）滤波器系数。
	 * <p>在中心频率处增益为 {@code gain}（线性值），远离中心频率时增益回归 0dB（1.0）。
	 * 这是 EQ 均衡器各频段使用的滤波器类型。</p>
	 */
	private void calculatePeaking(float w0, float cosW, float sinW, float alpha, float A) {
		b0 = 1.0f + alpha * A;
		b1 = -2.0f * cosW;
		b2 = 1.0f - alpha * A;
		float a0 = 1.0f + alpha / A;
		a1 = -2.0f * cosW;
		a2 = 1.0f - alpha / A;
		normalize(a0); // 将 a0 归一化为 1.0
	}

	/** LOWPASS（低通）滤波器：通过低频，衰减高频 */
	private void calculateLowPass(float w0, float cosW, float sinW, float alpha) {
		b0 = (1.0f - cosW) / 2.0f;
		b1 = 1.0f - cosW;
		b2 = (1.0f - cosW) / 2.0f;
		float a0 = 1.0f + alpha;
		a1 = -2.0f * cosW;
		a2 = 1.0f - alpha;
		normalize(a0);
	}

	/** HIGHPASS（高通）滤波器：通过高频，衰减低频 */
	private void calculateHighPass(float w0, float cosW, float sinW, float alpha) {
		b0 = (1.0f + cosW) / 2.0f;
		b1 = -(1.0f + cosW);
		b2 = (1.0f + cosW) / 2.0f;
		float a0 = 1.0f + alpha;
		a1 = -2.0f * cosW;
		a2 = 1.0f - alpha;
		normalize(a0);
	}

	/** LOWSHELF（低频搁架）：在截止频率以下提升或衰减固定量 */
	private void calculateLowShelf(float w0, float cosW, float sinW, float alpha, float A) {
		float sqrtA = (float) Math.sqrt(A);
		b0 = A * ((A + 1.0f) - (A - 1.0f) * cosW + 2.0f * sqrtA * alpha);
		b1 = 2.0f * A * ((A - 1.0f) - (A + 1.0f) * cosW);
		b2 = A * ((A + 1.0f) - (A - 1.0f) * cosW - 2.0f * sqrtA * alpha);
		float a0 = (A + 1.0f) + (A - 1.0f) * cosW + 2.0f * sqrtA * alpha;
		a1 = -2.0f * ((A - 1.0f) + (A + 1.0f) * cosW);
		a2 = (A + 1.0f) + (A - 1.0f) * cosW - 2.0f * sqrtA * alpha;
		normalize(a0);
	}

	/** HIGHSHELF（高频搁架）：在截止频率以上提升或衰减固定量 */
	private void calculateHighShelf(float w0, float cosW, float sinW, float alpha, float A) {
		float sqrtA = (float) Math.sqrt(A);
		b0 = A * ((A + 1.0f) + (A - 1.0f) * cosW + 2.0f * sqrtA * alpha);
		b1 = -2.0f * A * ((A - 1.0f) + (A + 1.0f) * cosW);
		b2 = A * ((A + 1.0f) + (A - 1.0f) * cosW - 2.0f * sqrtA * alpha);
		float a0 = (A + 1.0f) - (A - 1.0f) * cosW + 2.0f * sqrtA * alpha;
		a1 = 2.0f * ((A - 1.0f) - (A + 1.0f) * cosW);
		a2 = (A + 1.0f) - (A - 1.0f) * cosW - 2.0f * sqrtA * alpha;
		normalize(a0);
	}

	/** NOTCH（陷波）滤波器：在中心频率处产生极窄的衰减 */
	private void calculateNotch(float w0, float cosW, float sinW, float alpha) {
		b0 = 1.0f;
		b1 = -2.0f * cosW;
		b2 = 1.0f;
		float a0 = 1.0f + alpha;
		a1 = -2.0f * cosW;
		a2 = 1.0f - alpha;
		normalize(a0);
	}

	/** BANDPASS（带通）滤波器：仅通过中心频率附近的频带 */
	private void calculateBandPass(float w0, float cosW, float sinW, float alpha) {
		b0 = alpha;
		b1 = 0.0f;
		b2 = -alpha;
		float a0 = 1.0f + alpha;
		a1 = -2.0f * cosW;
		a2 = 1.0f - alpha;
		normalize(a0);
	}

	/**
	 * 归一化：将所有系数除以 a0，使传递函数分母常数项为 1。
	 * <p>这确保了差分方程中 y[n] 的系数为 1，简化计算。</p>
	 */
	private void normalize(float a0) {
		if (a0 != 0) {
			b0 /= a0; b1 /= a0; b2 /= a0;
			a1 /= a0; a2 /= a0;
		}
	}

	/**
	 * 处理单个样本点（标量 IIR 滤波）。
	 * 
	 * <h4>差分方程</h4>
	 * <pre>
	 *   output = b0·input + b1·x1 + b2·x2 - a1·y1 - a2·y2
	 * </pre>
	 * 处理完后更新延迟线状态（x2←x1, x1←input, y2←y1, y1←output）。
	 * 
	 * @param input 当前输入样本（归一化浮点数，-1.0 ~ 1.0）
	 * @return 滤波后的输出样本
	 */
	public float process(float input) {
		float output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;

		// 更新延迟线
		x2 = x1;   x1 = input;  // 输入移位
		y2 = y1;   y1 = output; // 输出移位

		return output;
	}

	/** 重置所有延迟线状态为零（避免上一首歌的状态影响下一首） */
	public void reset() {
		x1 = x2 = y1 = y2 = 0.0f;
	}

	/**
	 * 更新滤波器参数并重新计算系数。
	 * <p>当 EQ 频段增益改变时调用此方法重新计算传递函数系数并重置状态。</p>
	 */
	public void updateParameters(float frequency, float Q, float gain) {
		this.frequency = frequency;
		this.Q = Q;
		this.gain = gain;
		calculateCoefficients();
		reset(); // 参数改变后必须重置状态，避免瞬态噪声
	}

	/**
	 * 计算指定频率处的幅度响应（dB）。
	 * <p>用于 EQ 面板的频谱曲线绘制。</p>
	 * @param freq 查询频率（Hz）
	 * @return 该频率处的增益（dB），如 +6.0 或 -3.0
	 */
	public float getMagnitudeResponse(float freq) {
		// 代入 z = e^(jω)，其中 ω = 2π·f/fₛ
		float w = (float) (2.0 * Math.PI * freq / sampleRate);
		float cosW = (float) Math.cos(w);
		float sinW = (float) Math.sin(w);
		float cos2W = (float) Math.cos(2.0 * w);
		float sin2W = (float) Math.sin(2.0 * w);

		// 分子的复数形式：b0 + b1·e^(-jω) + b2·e^(-j2ω)
		float numReal = b0 + b1 * cosW + b2 * cos2W;
		float numImag = b1 * sinW + b2 * sin2W;
		// 分母的复数形式：1 + a1·e^(-jω) + a2·e^(-j2ω)
		float denReal = 1.0f + a1 * cosW + a2 * cos2W;
		float denImag = a1 * sinW + a2 * sin2W;

		// |H(e^(jω))| = |分子| / |分母|
		float numMag = (float) Math.sqrt(numReal * numReal + numImag * numImag);
		float denMag = (float) Math.sqrt(denReal * denReal + denImag * denImag);
		if (denMag == 0) return 0;

		float magnitude = numMag / denMag;
		return (float) (20.0 * Math.log10(Math.max(0.0001f, magnitude)));
	}

	public FilterType getType()          { return type; }
	public float getFrequency()          { return frequency; }
	public float getQ()                  { return Q; }
	public float getGain()               { return gain; }
}
