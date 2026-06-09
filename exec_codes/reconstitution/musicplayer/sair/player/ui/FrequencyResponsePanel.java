package sair.player.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

import javax.swing.JPanel;

import sair.FCM;
import sair.player.audio.EQProcessor;
import sair.sys.gui.ConsFrame;

/**
 * 频率响应曲线图 —— 实时显示 15 段 EQ 的合成频率响应。
 * 
 * <h4>坐标系</h4>
 * <pre>
 *   Y 轴（增益 dB）：-24 dB（顶部）→ 0 dB（中部）→ +24 dB（底部）
 *   X 轴（频率 Hz）：20 Hz（左侧）→ 20,000 Hz（右侧），对数刻度
 * </pre>
 * 
 * <h4>绘制内容（绘制顺序）</h4>
 * <ol>
 *   <li><b>网格线</b>（{@link #drawGrid}）：dB 水平线 + 频率垂直线 + 中轴线</li>
 *   <li><b>频率响应曲线</b>（{@link #drawFrequencyResponse}）：
 *       逐像素计算合成幅度 → 绿色实线 + 半透明填充</li>
 *   <li><b>中心频点标记</b>（{@link #drawCenterFrequencies}）：
 *       15 个黄色圆点 + 垂直线，标注 EQ 控制点位置</li>
 * </ol>
 * 
 * <h4>坐标变换</h4>
 * <table border="1">
 *   <tr><th>方向</th><th>公式</th><th>说明</th></tr>
 *   <tr><td>频率 → X</td><td>{@code x = (log10(freq) - log10(20)) / (log10(20000) - log10(20)) * width}</td><td>对数映射</td></tr>
 *   <tr><td>X → 频率</td><td>{@code freq = 10^(logMin + x/width * (logMax - logMin))}</td><td>逆映射</td></tr>
 *   <tr><td>dB → Y</td><td>{@code y = (1 - (dB + 24) / 48) * height}</td><td>-24 dB=顶部，+24 dB=底部</td></tr>
 * </table>
 * 
 * <h4>频率响应计算</h4>
 * 对每个像素点的频率，遍历 15 个 EQ 频段，累加每个频段的峰值滤波器响应：
 * <pre>
 *   totalMagnitude = Σ peakFilterResponse(freq, fc[i], Q=1.414, gain[i])
 * </pre>
 * 峰值滤波器传递函数简化版（仅幅度）：
 * <pre>
 *   w = f / fc,  A = sqrt(gain)
 *   magnitude = (w² + w*A/Q + 1) / (w² + w/(A*Q) + 1)
 *   response_dB = 20 * log10(magnitude)
 * </pre>
 * 
 * <h4>性能说明</h4>
 * 对每个像素点都执行 15 次 floating-point 计算。在 600×200 的渲染区域，
 * 600 像素 × 15 频段 = 9,000 次 peakFilterResponse 调用。
 * 得益于现代 CPU 的浮点性能，这对重绘来说是可接受的。
 * 仅在滑块变化触发 repaint 时重新计算，不会持续消耗资源。
 * 
 * @author SairFramework
 */
public class FrequencyResponsePanel extends JPanel {

	private static final long serialVersionUID = 1L;
	/** EQ 处理器引用 —— 用于读取中心频率和增益值 */
	private EQProcessor eqProcessor;

	/**
	 * 构造 —— 设置绘图区域尺寸和背景色。
	 * 
	 * <p>注意：背景设为不透明（{@code setOpaque(true)}）黑色，因为此面板需要
	 * 清晰的纯黑背景来对比彩色曲线。</p>
	 * 
	 * @param eq EQ 处理器
	 */
	public FrequencyResponsePanel(EQProcessor eq) {
		this.eqProcessor = eq;
		setPreferredSize(new Dimension(600, 200));  // 固定画布大小
		setBackground(Color.BLACK);
		setOpaque(true);  // 不透明 — 纯黑背景让曲线更清晰
	}

	/**
	 * 更新 EQ 处理器引用（切换歌曲时调用）。
	 * 
	 * <p>切换歌曲后会创建新的 {@link EQProcessor}，需要更新此处引用
	 * 以读取正确的新处理器增益值。</p>
	 * 
	 * @param eq 新的 EQ 处理器
	 */
	public void updateEQProcessor(EQProcessor eq) {
		this.eqProcessor = eq;
		repaint(); // 用新处理器的增益重新绘制曲线
	}

	/**
	 * 绘制频率响应曲线图（主入口）。
	 * 
	 * <h4>渲染质量设置</h4>
	 * <ul>
	 *   <li>{@code VALUE_ANTIALIAS_ON}：曲线平滑抗锯齿</li>
	 *   <li>{@code VALUE_RENDER_QUALITY}：高质量渲染（牺牲速度换质量）</li>
	 * </ul>
	 * 由于此面板不是高频刷新的动画，可以安全使用高质量渲染。
	 */
	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		Graphics2D g2d = (Graphics2D) g;

		// 抗锯齿 — 曲线边缘平滑
		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		// 高质量渲染 — 更好的颜色插值
		g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

		int width = getWidth();
		int height = getHeight();

		// 绘制顺序：网格 → 曲线 → 频点标记（后绘制的在上面）
		drawGrid(g2d, width, height);
		drawFrequencyResponse(g2d, width, height);
		drawCenterFrequencies(g2d, width, height);
	}

	/**
	 * 绘制网格线 —— 水平 dB 线 + 垂直频率线 + 0 dB 中轴线。
	 * 
	 * <h4>水平线</h4>
	 * 从 -18 dB 到 +18 dB，步长 6 dB。每条线旁标注 dB 值。
	 * 使用深灰色（40,40,40）保持低视觉权重，文字用 FCM 副色。
	 * 
	 * <h4>垂直线</h4>
	 * 标记关键频率：20, 50, 100, 200, 500, 1k, 2k, 5k, 10k, 20k Hz。
	 * 标注格式：≥1000 Hz 用 kHz（如 "5k"），&lt;1000 Hz 用数字（如 "200"）。
	 * 
	 * <h4>0 dB 中轴线</h4>
	 * 使用稍亮的灰色（100,100,100）和稍粗的线宽（1.5f）突出显示。
	 * 
	 * @param g2d   图形上下文
	 * @param width  画布宽度
	 * @param height 画布高度
	 */
	private void drawGrid(Graphics2D g2d, int width, int height) {
		g2d.setColor(new Color(40, 40, 40));  // 深灰网格线
		g2d.setStroke(new java.awt.BasicStroke(1.0f));

		// --- 水平 dB 线：-18 ~ +18 dB，步长 6 ---
		for (int db = -18; db <= 18; db += 6) {
			int y = dBToY(db, height);
			g2d.draw(new Line2D.Double(0, y, width, y));

			// dB 标注
			g2d.setColor(FCM.EXECTION_help_Color);
			g2d.setFont(ConsFrame.font.deriveFont(9f));
			g2d.drawString(db + "dB", 5, y - 2);  // y-2 使文字在线上方
			g2d.setColor(new Color(40, 40, 40));   // 恢复网格线颜色
		}

		// --- 垂直频率线：对数分布的 10 个关键频率 ---
		float[] freqMarks = { 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000 };
		for (float freq : freqMarks) {
			int x = freqToX(freq, width);
			g2d.draw(new Line2D.Double(x, 0, x, height));

			// 频率标注：≥1000 Hz 用 "k" 缩写
			String label;
			if (freq >= 1000) {
				label = (int) (freq / 1000) + "k";
			} else {
				label = String.valueOf(freq);
			}
			g2d.setColor(FCM.EXECTION_help_Color);
			g2d.setFont(ConsFrame.font.deriveFont(9f));
			g2d.drawString(label, x - 5, height - 5);  // 底部标注
			g2d.setColor(new Color(40, 40, 40));  // 恢复
		}

		// --- 0 dB 中轴线（稍亮、稍粗）---
		g2d.setColor(new Color(100, 100, 100));
		g2d.setStroke(new java.awt.BasicStroke(1.5f));
		int y0 = dBToY(0, height);
		g2d.draw(new Line2D.Double(0, y0, width, y0));
	}

	/**
	 * 绘制合成频率响应曲线 —— 绿色实线 + 半透明绿色填充区域。
	 * 
	 * <h4>计算流程</h4>
	 * <ol>
	 *   <li>逐像素遍历 X 轴（0 ~ width）</li>
	 *   <li>将 X 坐标转为频率值（对数映射）</li>
	 *   <li>调用 {@link #calculateMagnitudeAtFreq(float)} 计算该频率的 dB 值</li>
	 *   <li>将 dB 值转为 Y 坐标</li>
	 *   <li>用 {@link Path2D} 累积坐标点</li>
	 * </ol>
	 * 
	 * <h4>视觉效果</h4>
	 * <ul>
	 *   <li>曲线：亮绿色（0,255,100），线宽 2.5f</li>
	 *   <li>填充：半透明绿色（alpha=30），形成"包裹"效果</li>
	 *   <li>填充区域：曲线下方到画布底部</li>
	 * </ul>
	 * 
	 * @param g2d    图形上下文
	 * @param width  画布宽度
	 * @param height 画布高度
	 */
	private void drawFrequencyResponse(Graphics2D g2d, int width, int height) {
		Path2D path = new Path2D.Float();

		// 从 X=0 到 X=width，逐像素计算曲线
		boolean first = true;
		for (int x = 0; x < width; x++) {
			float freq = xToFreq(x, width);                        // X → 频率
			float magnitude = calculateMagnitudeAtFreq(freq);      // 频率 → dB
			int y = dBToY(magnitude, height);                      // dB → Y

			if (first) {
				path.moveTo(x, y);  // 路径起点
				first = false;
			} else {
				path.lineTo(x, y);  // 连线
			}
		}

		// 绘制绿色曲线
		g2d.setColor(new Color(0, 255, 100));
		g2d.setStroke(new java.awt.BasicStroke(2.5f));
		g2d.draw(path);

		// 填充曲线下方区域（半透明绿色）
		g2d.setColor(new Color(0, 255, 100, 30));
		path.lineTo(width, height);  // 右下角
		path.lineTo(0, height);      // 左下角
		path.closePath();            // 闭合回起点
		g2d.fill(path);              // 填充封闭区域
	}

	/**
	 * 绘制 15 个中心频点的标记 —— 黄色圆点和垂直线。
	 * 
	 * <h4>绘制内容</h4>
	 * <ul>
	 *   <li>黄色圆点（直径 8px）：标注每个频段的中心频率和当前增益</li>
	 *   <li>浅黄色垂直线：从圆点到 0 dB 线，直观显示增益偏离参考值</li>
	 * </ul>
	 * 
	 * <h4>颜色编码</h4>
	 * <ul>
	 *   <li>圆点 + 实线：金黄色（255,200,0），线宽 2.0f</li>
	 *   <li>垂直线：半透明黄色（255,200,0,100），线宽 1.0f</li>
	 * </ul>
	 * 
	 * @param g2d    图形上下文
	 * @param width  画布宽度
	 * @param height 画布高度
	 */
	private void drawCenterFrequencies(Graphics2D g2d, int width, int height) {
		float[] frequencies = eqProcessor.getCenterFrequencies();  // 15 个中心频率
		float[] gains = eqProcessor.getAllBandGains();             // 15 个线性增益

		g2d.setColor(new Color(255, 200, 0));  // 金黄色
		g2d.setStroke(new java.awt.BasicStroke(2.0f));

		for (int i = 0; i < frequencies.length; i++) {
			int x = freqToX(frequencies[i], width);
			// 线性增益 → dB
			float gainDB = (float) (20.0 * Math.log10(Math.max(0.001f, gains[i])));
			int y = dBToY(gainDB, height);

			// 圆点标记（直径 8px）
			g2d.fillOval(x - 4, y - 4, 8, 8);

			// 垂直线：从圆点到 0 dB 线
			g2d.setColor(new Color(255, 200, 0, 100));  // 半透明
			g2d.setStroke(new java.awt.BasicStroke(1.0f));
			g2d.draw(new Line2D.Double(x, y, x, dBToY(0, height)));
			// 恢复颜色和线宽，供下个圆点绘制
			g2d.setColor(new Color(255, 200, 0));
			g2d.setStroke(new java.awt.BasicStroke(2.0f));
		}
	}

	/**
	 * 计算指定频率处的合成幅度（dB）。
	 * 
	 * <h4>计算模型</h4>
	 * 采用<b>简化 dB 叠加</b>模型：将 15 个频段在该频率的峰值滤波器响应
	 * （dB 值）直接累加。这是近似的，因为 dB 域叠加 ≠ 线性域叠加，
	 * 但视觉效果上与真实的滤波器级联足够接近，用于可视化足够。
	 * 
	 * <h4>频段参数</h4>
	 * Q = 1.414（相当于带宽约 1 倍频程），适合 1/3 倍频程间隔的 15 段均衡器。
	 * 
	 * @param freq 目标频率（Hz）
	 * @return 合成 dB 值
	 */
	private float calculateMagnitudeAtFreq(float freq) {
		float totalMagnitude = 0.0f;

		float[] frequencies = eqProcessor.getCenterFrequencies();
		float[] gains = eqProcessor.getAllBandGains();

		// 累加每个频段在当前频率上的响应
		for (int i = 0; i < frequencies.length; i++) {
			float fc = frequencies[i];    // 该频段中心频率
			float gain = gains[i];        // 该频段线性增益

			// Q = 1.414 = sqrt(2)，标准峰值滤波器的临界阻尼点
			float Q = 1.414f;
			float response = peakFilterResponse(freq, fc, Q, gain);
			totalMagnitude += response;   // dB 域累加
		}

		return totalMagnitude;
	}

	/**
	 * 峰值（PEAKING）滤波器的幅度响应。
	 * 
	 * <h4>传递函数（幅度部分）</h4>
	 * <pre>
	 *   w = f / fc                  — 归一化频率
	 *   A = sqrt(gain)              — 增益的平方根（因为峰值滤波器在中心频率处幅度 = A² = gain）
	 *   
	 *   H(w) = (w² + w·A/Q + 1) / (w² + w/(A·Q) + 1)
	 *   
	 *   response_dB = 20 * log10(|H(w)|)
	 * </pre>
	 * 
	 * <h4>A 的计算</h4>
	 * 对于峰值滤波器，{@code A = sqrt(gain)} 使得在中心频率（w=1）处：
	 * {@code |H(1)| = A = sqrt(gain)}，即 dB = 20*log10(sqrt(gain)) = 10*log10(gain)。
	 * 这与 EQ 滑块的 dB 值对应（10*log10(gain) = dB）。
	 * 
	 * <p>{@code Math.max(0.0001f, magnitude)} 防止 log10(0) = -∞ 溢出。</p>
	 * 
	 * @param freq 目标频率（Hz）
	 * @param fc   中心频率（Hz）
	 * @param Q    品质因数（带宽控制）
	 * @param gain 线性增益（1.0 = 0 dB）
	 * @return dB 幅度响应值
	 */
	private float peakFilterResponse(float freq, float fc, float Q, float gain) {
		float w = freq / fc;                           // 归一化频率
		float w2 = w * w;                              // 归一化频率平方
		float A = (float) Math.sqrt(Math.max(0.001f, gain));  // 幅度因子

		// 峰值滤波器传递函数（幅度）
		float numerator = w2 + (w / Q) * A + 1;        // 分子：w² + w·A/Q + 1
		float denominator = w2 + (w / Q) / A + 1;      // 分母：w² + w/(A·Q) + 1

		float magnitude = numerator / denominator;     // |H(w)|
		return (float) (20.0 * Math.log10(Math.max(0.0001f, magnitude)));
	}

	/**
	 * 频率（Hz）→ X 坐标（对数映射）。
	 * 
	 * <h4>对数映射</h4>
	 * 人耳对频率的感知是对数的（每个倍频程感知相同），因此 X 轴使用对数刻度。
	 * 20 Hz → X=0（左边缘），20,000 Hz → X=width（右边缘）。
	 * 
	 * @param freq  频率（Hz）
	 * @param width 画布宽度
	 * @return X 坐标
	 */
	private int freqToX(float freq, int width) {
		float logFreq = (float) Math.log10(freq);
		float logMin = (float) Math.log10(20.0);       // 最低频率 20 Hz
		float logMax = (float) Math.log10(20000.0);    // 最高频率 20 kHz

		// 线性映射对数频率到像素坐标
		return (int) ((logFreq - logMin) / (logMax - logMin) * width);
	}

	/**
	 * X 坐标 → 频率（Hz）（对数逆映射）。
	 * 
	 * <h4>用途</h4>
	 * 在绘制频率响应曲线时，对每个 X 像素计算对应的频率，
	 * 然后计算该频率处的合成幅度，得到 Y 坐标。
	 * 
	 * @param x     X 像素坐标
	 * @param width 画布宽度
	 * @return 频率（Hz）
	 */
	private float xToFreq(int x, int width) {
		float ratio = (float) x / width;               // 0.0 ~ 1.0
		float logMin = (float) Math.log10(20.0);
		float logMax = (float) Math.log10(20000.0);

		float logFreq = logMin + ratio * (logMax - logMin);  // 对数域线性插值
		return (float) Math.pow(10.0, logFreq);               // 10^logFreq = freq
	}

	/**
	 * dB 值 → Y 坐标。
	 * 
	 * <h4>映射关系</h4>
	 * <ul>
	 *   <li>dB = -24 → Y = 0（顶部）</li>
	 *   <li>dB = 0   → Y = height/2（中部）</li>
	 *   <li>dB = +24 → Y = height（底部）</li>
	 * </ul>
	 * 
	 * <h4>公式</h4>
	 * {@code ratio = (db - (-24)) / (24 - (-24)) = (db + 24) / 48}
	 * {@code y = (1 - ratio) * height}（反转，因为屏幕 Y 轴向下）
	 * 
	 * <p>ratio 钳位到 [0, 1] 防止超出画布范围。</p>
	 * 
	 * @param db     dB 值（-24 ~ +24 范围内效果最佳）
	 * @param height 画布高度
	 * @return Y 坐标
	 */
	private int dBToY(float db, int height) {
		float minDB = -24.0f;
		float maxDB = 24.0f;

		// (db - minDB) / range → 归一化到 [0, 1]
		float ratio = (db - minDB) / (maxDB - minDB);
		ratio = Math.max(0.0f, Math.min(1.0f, ratio));  // 钳位防止越界

		// 反转：ratio=0 在顶部，ratio=1 在底部
		return (int) ((1.0f - ratio) * height);
	}

	/**
	 * 触发重绘 —— 供外部（如 {@link EQPanel}）在 EQ 参数变化后刷新曲线。
	 */
	public void refresh() {
		repaint();
	}
}
