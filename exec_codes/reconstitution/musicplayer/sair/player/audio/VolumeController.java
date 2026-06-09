package sair.player.audio;

/**
 * 音量控制器 —— 提供主音量、总音量、静音三重控制。
 * 
 * <h3>音量模型</h3>
 * <pre>
 *   实际音量 = muted ? 0.0 : (volume × masterVolume)
 * </pre>
 * <ul>
 *   <li><b>volume</b> — 用户可调音量（0.0 ~ 1.0），对应百分比 0%~100%</li>
 *   <li><b>masterVolume</b> — 总音量限制（0.0 ~ 1.0），通常为 1.0</li>
 *   <li><b>muted</b> — 静音标志，静音时保存恢复音量</li>
 * </ul>
 * 
 * <h3>DSP 处理</h3>
 * 对每个 16-bit PCM 样本乘以实际音量因子。
 * 当 actualVolume == 1.0 时直接透传，避免不必要的数组拷贝。
 * 
 * <h3>在处理器链中的位置</h3>
 * 作为 DSP 链的最后一步，确保所有效果器的输出都经过音量缩放。
 */
public class VolumeController {

	/** 用户音量（0.0 ~ 1.0），默认 100% */
	private float volume = 1.0f;
	/** 总音量限制（0.0 ~ 1.0），默认 100% */
	private float masterVolume = 1.0f;
	/** 静音状态 */
	private boolean muted = false;
	/** 静音前的音量值（用于取消静音时恢复） */
	private float lastVolume = 1.0f;

	public VolumeController() {}

	/**
	 * 处理音频数据：对每个 16-bit 样本乘以实际音量。
	 * 
	 * <p>优化：当 actualVolume >= 1.0（无需衰减）或 == 0.0（完全静音）
	 * 且 volume 为 1.0 以上时直接透传，减少数组分配。</p>
	 * 
	 * <p>注意：假设 little-endian 字节序（与项目 PCM 格式一致）。</p>
	 */
	public byte[] process(byte[] audioData) {
		if (audioData == null || audioData.length == 0) {
			return audioData;
		}

		// 计算实际音量（静音时为 0）
		float actualVolume = muted ? 0.0f : (volume * masterVolume);

		// 无需处理时透传
		if (actualVolume >= 1.0f || actualVolume <= 0.0f) {
			return audioData;
		}

		byte[] processed = new byte[audioData.length];

		// 逐样本处理：每 2 字节 = 1 个 16-bit 样本
		for (int i = 0; i < audioData.length - 1; i += 2) {
			// little-endian: 低字节在前
			short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));

			// 乘以音量因子
			short processedSample = (short) (sample * actualVolume);

			// 写回 little-endian
			processed[i]     = (byte) (processedSample & 0xFF);      // 低字节
			processed[i + 1] = (byte) (processedSample >> 8);        // 高字节
		}

		return processed;
	}

	// ==================== 音量控制 ====================

	/** 设置音量（0.0 ~ 1.0），自动限幅 */
	public void setVolume(float volume) {
		if (volume < 0.0f) volume = 0.0f;
		if (volume > 1.0f) volume = 1.0f;
		this.volume = volume;
	}

	public float getVolume() { return volume; }

	/** 设置总音量（0.0 ~ 1.0） */
	public void setMasterVolume(float masterVolume) {
		if (masterVolume < 0.0f) masterVolume = 0.0f;
		if (masterVolume > 1.0f) masterVolume = 1.0f;
		this.masterVolume = masterVolume;
	}

	public float getMasterVolume() { return masterVolume; }

	// ==================== 静音 ====================

	/** 静音（保存当前音量以便恢复） */
	public void mute() {
		if (!muted) {
			lastVolume = volume; // 保存恢复用音量
			muted = true;
		}
	}

	/** 取消静音（恢复到静音前的音量） */
	public void unmute() {
		if (muted) {
			volume = lastVolume; // 恢复音量
			muted = false;
		}
	}

	/** 切换静音状态 */
	public void toggleMute() {
		if (muted) unmute(); else mute();
	}

	public boolean isMuted() { return muted; }

	// ==================== 百分比接口 ====================

	/** @return 音量百分比（0~100） */
	public int getVolumePercent() {
		return (int) (volume * 100);
	}

	/** 设置音量百分比（0~100） */
	public void setVolumePercent(int percent) {
		setVolume(percent / 100.0f);
	}
}
