package sair.player.acts;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line.Info;
import javax.sound.sampled.SourceDataLine;

import sair.player.audio.EQProcessor;
import sair.player.audio.VolumeController;
import sair.player.audio.SurroundProcessor;
import sair.player.audio.SoundstageExpander;
import sair.player.audio.ViperEffectsProcessor;

/**
 * 音频播放引擎 —— 实现从 MP3 文件到扬声器的完整数据流。
 * 
 * <h3>工作流程（全量内存加载方案）</h3>
 * <ol>
 *   <li><b>解码阶段</b>：通过 {@code AudioSystem.getAudioInputStream(pcmFormat, baseStream)} 
 *       将 MP3 转码为 CD 品质 PCM（44100Hz / 16-bit / 立体声）。</li>
 *   <li><b>内存加载</b>：{@link #putsMemory(AudioInputStream)} 将整个 PCM 流一次性读入
 *       {@code byte[] bais} 数组（~20-80MB/首）。</li>
 *   <li><b>播放循环</b>：{@link #Start()} 方法中，将 {@code bais} 包装为 {@link ByteArrayInputStream}，
 *       每次读 4096 字节 → 经过 DSP 处理器链 → 写入 {@link SourceDataLine} 输出到扬声器。</li>
 *   <li><b>进度跳转</b>：{@link #setPlayPosition(float)} 设置标记 → 播放循环内通过
 *       {@code bbais.reset() + skip(nowBytePosition)} 实现瞬时重定位。</li>
 * </ol>
 * 
 * <h3>DSP 处理器链（按顺序执行）</h3>
 * <pre>
 *   EQ 均衡器 → 环绕声 → 声场扩展 → Viper 综合音效 → 音量控制
 * </pre>
 * 所有处理器由 {@link PlayerActions} 统一创建并注入，跨歌曲共享同一实例。
 * 
 * <h3>线程模型</h3>
 * 实现了 {@link Runnable} 接口，播放运行在独立线程中。
 * 通过 {@code wait()/notify()} 实现暂停/继续同步。
 * 
 * @see #Start() 播放主循环
 * @see #processAudio(byte[], int) DSP 处理器链
 * @see #setPlayPosition(float) 进度跳转
 */
public class SairMP3Player implements Runnable {

	/**
	 * PCM 字节序标记。
	 * {@code true} = 大端序（Big-Endian），{@code false} = 小端序（Little-Endian）。
	 * 由 JVM 所在平台的 {@link ByteOrder#nativeOrder()} 决定。
	 */
	private static final boolean codingFlag = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;

	/**
	 * 全量内存加载：将编码后的音频流一次性读入 byte[] 数组。
	 * 
	 * <h4>为什么用全量加载？</h4>
	 * 相比流式播放方案，全量加载的优势：
	 * <ul>
	 *   <li>进度跳转瞬时完成（reset + skip 即可），无需重新解码 MP3 帧</li>
	 *   <li>DSP 处理器链逻辑简单，不依赖帧边界</li>
	 *   <li>线程安全简单，无文件 I/O 竞争</li>
	 * </ul>
	 * 代价是内存占用（~20-80MB/首），但现代设备可接受。
	 * 
	 * @param bis 已解码为 PCM 的 AudioInputStream
	 * @return PCM 原始字节数组（16-bit 立体声交织，little-endian）
	 */
	private static byte[] putsMemory(AudioInputStream bis) {
		// 预分配 8MB 缓冲区，减少 ArrayList 的扩容次数
		ByteArrayOutputStream byos = new ByteArrayOutputStream(8 * 1024 * 1024);
		int len;
		byte[] buffer = new byte[8 * 1024]; // 8KB 读缓冲
		try {
			while (true) {
				len = bis.read(buffer);
				if (len < 0)       // 读到流末尾
					break;
				byos.write(buffer, 0, len); // 追加到内存缓冲区
			}
			return byos.toByteArray(); // 一次性输出完整数组
		} catch (IOException e) {
			// 读取失败返回 null
		} finally {
			// 三层清理：流 → 缓冲区 → GC
			try { if (bis != null) bis.close(); bis = null; } catch (Exception e) {}
			try { byos.close(); byos = null; } catch (Exception e) {}
			System.gc(); // 提示 JVM 回收临时缓冲区
		}
		return null;
	}

	// ==================== PCM 音频参数 ====================

	/** PCM 音频格式：CD 品质（44100Hz, 16-bit, 立体声） */
	private AudioFormat pcmFormat = null;
	/** 扬声器输出线：与声卡交互的底层管道 */
	private SourceDataLine line = null;
	/** 全量 PCM 数据缓存：解码后的完整音频数据 */
	private byte[] bais = null;

	// ==================== 播放状态 ====================

	/**
	 * 播放状态标志：
	 * <ul>
	 *   <li>{@code isContinue=false, isBreak=false} → 暂停中（wait 状态）</li>
	 *   <li>{@code isContinue=true,  isBreak=false} → 播放中</li>
	 *   <li>{@code isBreak=true}                   → 已停止</li>
	 * </ul>
	 */
	private boolean isContinue = true, isBreak = false, isSetPos = false;
	/** 当前播放进度（0.0 ~ 1.0），用于 UI 进度条 */
	private float nowPosition = 0;
	/** 当前已播放的 PCM 字节数，用于跳转定位 */
	private int nowBytePosition = 0;
	/** 音频总时长（秒），由 publicInit 根据 PCM 字节数反算 */
	private int maxTime = 0;

	// ==================== DSP 处理器引用（由 PlayerActions 注入） ====================

	private EQProcessor eqProcessor = null;
	private VolumeController volumeController = null;
	private SurroundProcessor surroundProcessor = null;
	private SoundstageExpander soundstageExpander = null;
	private ViperEffectsProcessor viperProcessor = null;

	public SairMP3Player() {}

	/**
	 * 从文件构造播放器实例 —— 立即解码并内存加载。
	 * @param file MP3 音频文件
	 * @throws Exception 文件解码失败
	 */
	public SairMP3Player(File file) throws Exception {
		this.reSetFile(file);
	}

	/**
	 * 从输入流构造播放器实例。
	 * @param input 音频输入流（如 JAR 内资源）
	 * @throws Exception 解码失败
	 */
	public SairMP3Player(InputStream input) throws Exception {
		this.reSetStream(input);
	}

	// ==================== 处理器注入 ====================

	public void setEQProcessor(EQProcessor eq)              { this.eqProcessor = eq; }
	public EQProcessor getEQProcessor()                     { return eqProcessor; }
	public void setVolumeController(VolumeController vc)    { this.volumeController = vc; }
	public VolumeController getVolumeController()           { return volumeController; }
	public void setSurroundProcessor(SurroundProcessor sp)  { this.surroundProcessor = sp; }
	public SurroundProcessor getSurroundProcessor()         { return surroundProcessor; }
	public void setSoundstageExpander(SoundstageExpander se){ this.soundstageExpander = se; }
	public SoundstageExpander getSoundstageExpander()       { return soundstageExpander; }
	public void setViperProcessor(ViperEffectsProcessor vp) { this.viperProcessor = vp; }
	public ViperEffectsProcessor getViperProcessor()        { return viperProcessor; }

	/** @return 当前 PCM 音频格式 */
	public AudioFormat getAudioFormat()                     { return pcmFormat; }

	// ==================== 文件初始化 ====================

	/**
	 * 从 InputStream 重新加载音频文件。
	 * 仅在当前无加载数据时有效（防重入保护）。
	 * @return true = 加载成功，false = 已有数据跳过
	 */
	public boolean reSetStream(InputStream input) throws Exception {
		if (this.bais != null)
			return false;
		else {
			this.publicInit(AudioSystem.getAudioInputStream(input));
			return true;
		}
	}

	/**
	 * 从 File 重新加载音频文件。
	 * 仅在当前无加载数据时有效（防重入保护）。
	 * @return true = 加载成功，false = 已有数据跳过
	 */
	public boolean reSetFile(File file) throws Exception {
		if (this.bais != null)
			return false;
		else {
			this.publicInit(AudioSystem.getAudioInputStream(file));
			return true;
		}
	}

	/**
	 * 公共初始化方法：MP3 解码 → PCM 转码 → 内存加载 → 打开音频线。
	 * 
	 * <h4>处理流程</h4>
	 * <ol>
	 *   <li>读取源格式（MP3 的 sampleRate/channels）</li>
	 *   <li>构造目标 PCM 格式：PCM_SIGNED / 16-bit / 立体声 / little-endian</li>
	 *   <li>通过 {@code AudioSystem.getAudioInputStream(pcmFormat, baseStream)} 转码</li>
	 *   <li>{@code putsMemory()} 全量读入 {@code bais}</li>
	 *   <li>打开 SourceDataLine（Java 音频输出管道）</li>
	 *   <li>计算总时长：{@code bais.length / (sampleRate * 2字节 * 声道数)}</li>
	 * </ol>
	 * 
	 * <p>注意：EQ/Viper/Surround/Volume 等处理器不在此时创建，
	 * 而是由 PlayerActions 统一创建后通过 setter 注入，确保跨歌曲共享实例。</p>
	 */
	private void publicInit(AudioInputStream baseStream) throws Exception {
		AudioFormat baseFormat = baseStream.getFormat();
		Encoding pcmSigFlag = AudioFormat.Encoding.PCM_SIGNED; // 有符号PCM
		float rate = baseFormat.getSampleRate();               // 采样率（如 44100）
		int chann = baseFormat.getChannels();                  // 声道数（1=单声道，2=立体声）
		int fsize = chann * 2;                                 // 帧大小 = 声道 × 2字节(16bit)
		int bitsSize = 16;                                     // 位深16bit
		// 构造目标 PCM 格式
		AudioFormat pcmFormat = new AudioFormat(pcmSigFlag, rate, bitsSize, chann, fsize, rate, codingFlag);
		// 请求 Java 音频引擎将源格式转码为目标 PCM 格式
		AudioInputStream resultStream = AudioSystem.getAudioInputStream(pcmFormat, baseStream);
		this.pcmFormat = pcmFormat;
		this.bais = putsMemory(resultStream); // 全量内存加载

		// 获取音频输出线（SourceDataLine）：与声卡的管道
		int lineSigFlag = AudioSystem.NOT_SPECIFIED; // 使用默认缓冲区大小
		Info lineinfo = new DataLine.Info(SourceDataLine.class, pcmFormat, lineSigFlag);
		this.line = (SourceDataLine) AudioSystem.getLine(lineinfo);

		// 计算总时长（秒）：总字节 / (采样率 × 2字节/样本 × 声道数)，加 0.999 向上取整
		float timeResult = (this.bais.length / (rate * 2f * chann)) + 0.999f;
		this.maxTime = (int) timeResult;
	}

	// ==================== 状态查询 ====================

	/** @return 是否正在播放（或暂停中未停止） */
	public boolean isPlaying() { return this.isContinue || !this.isBreak; }
	/** @return 是否暂停中 */
	public boolean isPause()   { return !this.isContinue; }
	/** @return 是否已停止 */
	public boolean isStop()    { return this.isBreak; }
	/** @return 是否处于可继续的暂停状态（暂停且未停止） */
	public boolean canContinue(){ return (!this.isContinue) && (!this.isBreak); }

	// ==================== 播放控制 ====================

	/**
	 * 停止播放。
	 * <p>设置 {@code isBreak = true} 后，如果正处于暂停（wait），
	 * 需要先 Continue 唤醒线程才能让它检测到 isBreak 并退出循环。</p>
	 */
	public boolean Stop() throws IOException {
		if (this.bais == null) return false;
		this.isBreak = true;
		if (!this.isContinue)
			this.Continue(); // 唤醒被 wait() 阻塞的播放线程，使其检测到 isBreak
		return true;
	}

	/** 暂停播放：设置 isContinue = false，播放循环检测后进入 wait() 阻塞 */
	public void Pause() {
		if (this.bais == null) return;
		this.isContinue = false;
	}

	/**
	 * 继续播放：设置 isContinue = true，通过 notify() 唤醒 wait() 中的播放线程。
	 * <p>{@code synchronized(this)} 确保与播放线程的 wait() 在同一监视器上同步。</p>
	 */
	public void Continue() {
		if (this.bais == null || this.line == null) return;
		this.isContinue = true;
		synchronized (this) {
			this.notify(); // 唤醒播放线程
		}
	}

	/**
	 * 播放主循环 —— 将 PCM 数据从内存持续推送到声卡。
	 * 
	 * <h4>循环逻辑</h4>
	 * <ol>
	 *   <li>将 {@code bais} 包装为 {@link ByteArrayInputStream}</li>
	 *   <li>打开 SourceDataLine 并开始</li>
	 *   <li>每次读 4096 字节 → {@link #processAudio(byte[], int)} 经 DSP 链处理 → 写入声卡</li>
	 *   <li>更新 {@code nowPosition}（进度 0.0~1.0）和 {@code nowBytePosition}</li>
	 *   <li>检查暂停信号 → {@code wait()} 阻塞直到被 {@code notify()} 唤醒</li>
	 *   <li>检查停止信号 → 退出循环</li>
	 *   <li>检查跳转信号 → {@code reset() + skip()} 重新定位到目标位置</li>
	 * </ol>
	 * 
	 * @throws Exception 播放过程中发生 IO 异常
	 */
	public void Start() throws Exception {
		if (this.bais == null || this.pcmFormat == null)
			return;
		int len = -1;
		byte[] buffer;
		ByteArrayInputStream bbais = null;
		try {
			// 每次播放创建新的 ByteArrayInputStream（支持 reset）
			bbais = new ByteArrayInputStream(this.bais);
			this.line.open(this.pcmFormat); // 打开声卡管道
			this.line.start();              // 开始向声卡推送数据
			buffer = new byte[4096];        // 4KB 读缓冲区
			while (true) {
				// ── 跳转处理：reset + skip 到目标字节位置 ──
				if (this.isSetPos) {
					bbais.reset();
					bbais.skip(this.nowBytePosition);
					this.isSetPos = false;
				}
				len = bbais.read(buffer);
				if (len < 0)     // 读到末尾（文件正常结束）
					break;

				// ── DSP 处理器链：EQ → 环绕声 → 声场 → Viper → 音量 ──
				byte[] processedBuffer = processAudio(buffer, len);

				// ── 更新进度 ──
				this.nowBytePosition += len;
				this.nowPosition = (float) this.nowBytePosition / (float) this.bais.length;
				// ── 写入声卡输出 ──
				this.line.write(processedBuffer, 0, processedBuffer.length);

				// ── 暂停检测 ──
				if (!this.isContinue)
					synchronized (this) {
						try {
							this.wait(); // 阻塞直到被 Continue() 的 notify() 唤醒
						} catch (InterruptedException e) {
							break;
						}
					}
				// ── 停止检测 ──
				if (this.isBreak)
					break;
			}
		} catch (Exception e) {
			throw new IOException(e.getMessage());
		} finally {
			// 清理：关闭音频线、关闭流、释放内存
			closeLine();
			if (bbais != null)
				bbais.close();
			clearBytes_File();
			buffer = null;
			bbais = null;
		}
	}

	/**
	 * DSP 音频处理器链：按固定顺序依次处理音频数据。
	 * 
	 * <h4>处理顺序及原因</h4>
	 * <ol>
	 *   <li><b>EQ 均衡器</b> — 频响修正，必须先执行以保证后续效果基于正确频谱</li>
	 *   <li><b>环绕声</b> — 空间定位，在频域修正之后声场处理之前</li>
	 *   <li><b>声场扩展</b> — 空间感增强</li>
	 *   <li><b>Viper 综合音效</b> — 音色润色（动态压缩/谐波增强等）</li>
	 *   <li><b>音量控制</b> — 最后应用，确保所有效果器输出都经过音量缩放</li>
	 * </ol>
	 * 
	 * @param input  原始 PCM 字节数组
	 * @param length 有效数据长度（buffer 可能大于实际读取量）
	 * @return 经过所有处理器链处理后的 PCM 字节数组
	 */
	private byte[] processAudio(byte[] input, int length) {
		byte[] processed = input;

		// 如果最后一帧读取不足 4096 字节，裁剪到实际长度
		if (length < input.length) {
			byte[] trimmed = new byte[length];
			System.arraycopy(input, 0, trimmed, 0, length);
			processed = trimmed;
		}

		// 1. EQ 均衡器（频段增益调整）
		if (eqProcessor != null) {
			processed = eqProcessor.process(processed);
		}

		// 2. 环绕声处理（仅在非 NONE 模式下生效）
		if (surroundProcessor != null && surroundProcessor.getMode() != SurroundProcessor.SurroundMode.NONE) {
			processed = surroundProcessor.process(processed);
		}

		// 3. 声场扩展（仅在启用时生效）
		if (soundstageExpander != null && soundstageExpander.isEnabled()) {
			processed = soundstageExpander.process(processed);
		}

		// 4. Viper 综合音效（仅在启用时生效）
		if (viperProcessor != null && viperProcessor.isEnabled()) {
			processed = viperProcessor.process(processed);
		}

		// 5. 音量控制（最后应用，缩放所有效果器输出的总增益）
		if (volumeController != null) {
			processed = volumeController.process(processed);
		}

		return processed;
	}

	// ==================== 资源清理 ====================

	/** 释放 PCM 内存缓存（置 null 等待 GC 回收） */
	private void clearBytes_File() {
		this.bais = null;
	}

	/**
	 * 关闭音频输出线。
	 * <p>步骤：drain（排空缓冲区）→ stop → close → 置 null。</p>
	 */
	private void closeLine() {
		if (this.line != null) {
			this.line.drain();  // 等待缓冲区所有数据输出完毕
			this.line.stop();
			this.line.close();
			this.line = null;
		}
	}

	/** Runnable 入口：在线程中执行 Start() */
	@Override
	public void run() {
		try {
			this.Start();
		} catch (Exception e) {}
	}

	// ==================== 进度相关 ====================

	/** @return PCM 数据总字节数 */
	public int getMaxBytesSize() {
		return (this.bais != null) ? this.bais.length : 0;
	}

	/** @return 当前播放到的字节偏移量（用于进度条） */
	public int getPlayLimit() {
		return (this.bais != null) ? (int) (this.bais.length * this.nowPosition) : 0;
	}

	/** @return 当前播放进度（0.0 ~ 1.0） */
	public float getPlayPosition() {
		return this.nowPosition;
	}

	/**
	 * 设置播放进度（跳转到指定位置）。
	 * 
	 * <h4>实现机制</h4>
	 * <ol>
	 *   <li>暂停播放（Pause）</li>
	 *   <li>计算目标字节位置：{@code target × 总字节数}</li>
	 *   <li>字节对齐修正：16-bit 立体声每样本 4 字节，位置必须 4 字节对齐。
	 *       通过 {@code nowPos % 2 > 0 → nowPos -= 3} 向下对齐到 4 的倍数。</li>
	 *   <li>设置 {@code isSetPos = true}，播放循环检测后执行 reset + skip</li>
	 *   <li>继续播放（Continue）</li>
	 * </ol>
	 * 
	 * @param target 目标进度（0.0 ~ 1.0）
	 */
	public void setPlayPosition(float target) {
		this.Pause();
		int nowPos = (int) (this.getMaxBytesSize() * target);
		// 字节对齐：16-bit 立体声每样本4字节，确保位置在样本边界
		if (nowPos % 2 > 0)
			nowPos -= 3;
		if (nowPos < 0)
			nowPos = 0;
		this.isSetPos = true;
		this.nowBytePosition = nowPos;
		this.Continue();
	}

	/** @return 音频总时长（秒） */
	public int getMaxTime() {
		return this.maxTime;
	}

}
