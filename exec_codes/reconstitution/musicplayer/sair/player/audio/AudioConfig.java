package sair.player.audio;

import java.io.File;
import java.io.FileWriter;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

import sair.sys.SairCons;

/**
 * 音效配置持久化管理器 —— 将 EQ、音量、环绕声、声场、Viper 参数保存到/从磁盘加载。
 * 
 * <h3>存储格式</h3>
 * 纯文本 key=value 格式，每行一个配置项，支持 {@code #} 开头注释行：
 * <pre>
 *   volume=0.800000
 *   master_volume=1.000000
 *   muted=false
 *   eq_band_0=1.000000
 *   eq_band_1=1.200000
 *   ...
 *   surround_mode=NONE
 *   soundstage_enabled=false
 *   viper_enabled=true
 *   viper_bass_strength=0.700000
 *   ...
 * </pre>
 * 
 * <h3>设计决策</h3>
 * <ul>
 *   <li><b>不使用 Java Properties</b>：Properties 不支持浮点精度的保留，且 key 数量多时序列化顺序不可控</li>
 *   <li><b>手动 FileWriter/BufferedReader</b>：逐行写入和解析，简单可控</li>
 *   <li><b>showMessages 参数</b>：静默保存（切歌时）vs 显式保存（手动按钮），避免控制台刷屏</li>
 *   <li><b>标签式 if-else 而非嵌套</b>：每个配置 key 独立判断，避免之前 else-if 链导致的 Viper 配置丢失问题</li>
 * </ul>
 * 
 * @see PlayerActions#saveAudioConfig() 停止时自动保存
 * @see PlayerActions#saveAudioConfigManual() 手动保存按钮
 */
public class AudioConfig {

	/** 配置文件名：保存在 Activity 数据目录下 */
	private static final String CONFIG_FILE_NAME = "audio_config.cfg";
	/** EQ 频段 key 前缀：eq_band_0, eq_band_1, ... */
	private static final String EQ_PREFIX = "eq_band_";
	private static final String VOLUME_KEY = "volume";
	private static final String MASTER_VOLUME_KEY = "master_volume";
	private static final String MUTED_KEY = "muted";

	// ── 环绕声配置键 ──
	private static final String SURROUND_MODE_KEY = "surround_mode";
	private static final String SURROUND_WIDTH_KEY = "surround_width";
	private static final String SURROUND_MIX_KEY = "surround_mix";

	// ── 声场扩展配置键 ──
	private static final String SOUNDSTAGE_ENABLED_KEY = "soundstage_enabled";
	private static final String SOUNDSTAGE_WIDTH_KEY = "soundstage_width";
	private static final String SOUNDSTAGE_DEPTH_KEY = "soundstage_depth";
	private static final String SOUNDSTAGE_HEIGHT_KEY = "soundstage_height";
	private static final String SOUNDSTAGE_CENTER_KEY = "soundstage_center";

	// ── Viper 音效配置键 ──
	private static final String VIPER_ENABLED_KEY = "viper_enabled";
	private static final String VIPER_BASS_ENABLED_KEY = "viper_bass_enabled";
	private static final String VIPER_BASS_STRENGTH_KEY = "viper_bass_strength";
	private static final String VIPER_TREBLE_ENABLED_KEY = "viper_treble_enabled";
	private static final String VIPER_TREBLE_STRENGTH_KEY = "viper_treble_strength";
	private static final String VIPER_COMPRESSOR_ENABLED_KEY = "viper_compressor_enabled";
	private static final String VIPER_COMPRESSOR_RATIO_KEY = "viper_compressor_ratio";
	private static final String VIPER_EXCITER_ENABLED_KEY = "viper_exciter_enabled";
	private static final String VIPER_EXCITER_AMOUNT_KEY = "viper_exciter_amount";

	/** 配置文件所在目录 */
	private String configDir;

	public AudioConfig(String dataDir) {
		this.configDir = dataDir;
	}

	// ==================== 便捷重载方法 ====================

	public void saveConfig(EQProcessor eq, VolumeController vc) {
		saveConfig(eq, vc, null, null, null, true);
	}

	public void saveConfig(EQProcessor eq, VolumeController vc, boolean showMessages) {
		saveConfig(eq, vc, null, null, null, showMessages);
	}

	public void saveConfig(EQProcessor eq, VolumeController vc,
	                      sair.player.audio.SurroundProcessor surround,
	                      sair.player.audio.SoundstageExpander soundstage,
	                      sair.player.audio.ViperEffectsProcessor viper) {
		saveConfig(eq, vc, surround, soundstage, viper, true);
	}

	/**
	 * 保存所有音效配置到磁盘。
	 * 
	 * <h4>写入顺序</h4>
	 * <ol>
	 *   <li>音量：volume, master_volume, muted</li>
	 *   <li>EQ：eq_band_0 ~ eq_band_14（15个频段）</li>
	 *   <li>环绕声：surround_mode, surround_width, surround_mix（仅在处理器非空时写入）</li>
	 *   <li>声场扩展：soundstage_enabled, width, depth, height, center</li>
	 *   <li>Viper 音效：viper_enabled 及 4 个子模块（Bass/Treble/Compressor/Exciter）</li>
	 * </ol>
	 * 
	 * <p>写入方式为覆盖（非追加），每次完全重写配置文件。</p>
	 * 
	 * @param showMessages 是否在控制台显示保存提示
	 */
	public void saveConfig(EQProcessor eq, VolumeController vc,
	                      sair.player.audio.SurroundProcessor surround,
	                      sair.player.audio.SoundstageExpander soundstage,
	                      sair.player.audio.ViperEffectsProcessor viper,
	                      boolean showMessages) {
		File configFile = new File(configDir + CONFIG_FILE_NAME);

		try (FileWriter writer = new FileWriter(configFile)) {

			// ── 音量 ──
			writer.write(VOLUME_KEY + "=" + vc.getVolume() + "\n");
			writer.write(MASTER_VOLUME_KEY + "=" + vc.getMasterVolume() + "\n");
			writer.write(MUTED_KEY + "=" + vc.isMuted() + "\n");

			// ── EQ：15段频段增益 ──
			float[] eqGains = eq.getAllBandGains();
			for (int i = 0; i < eqGains.length; i++) {
				writer.write(EQ_PREFIX + i + "=" + eqGains[i] + "\n");
			}

			// ── 环绕声（仅在非空时写入，加载时对应检查非空） ──
			if (surround != null) {
				writer.write(SURROUND_MODE_KEY + "=" + surround.getMode().name() + "\n");
				writer.write(SURROUND_WIDTH_KEY + "=" + surround.getExpandWidth() + "\n");
				writer.write(SURROUND_MIX_KEY + "=" + surround.getSurroundMix() + "\n");
			}

			// ── 声场扩展 ──
			if (soundstage != null) {
				writer.write(SOUNDSTAGE_ENABLED_KEY + "=" + soundstage.isEnabled() + "\n");
				writer.write(SOUNDSTAGE_WIDTH_KEY + "=" + soundstage.getWidth() + "\n");
				writer.write(SOUNDSTAGE_DEPTH_KEY + "=" + soundstage.getDepth() + "\n");
				writer.write(SOUNDSTAGE_HEIGHT_KEY + "=" + soundstage.getHeight() + "\n");
				writer.write(SOUNDSTAGE_CENTER_KEY + "=" + soundstage.getCenterImage() + "\n");
			}

			// ── Viper 音效：总开关 + 4个子模块 ──
			if (viper != null) {
				writer.write(VIPER_ENABLED_KEY + "=" + viper.isEnabled() + "\n");

				// Bass Enhancer
				writer.write(VIPER_BASS_ENABLED_KEY + "=" + viper.getBassEnhancer().isEnabled() + "\n");
				writer.write(VIPER_BASS_STRENGTH_KEY + "=" + viper.getBassEnhancer().getStrength() + "\n");

				// Treble Enhancer
				writer.write(VIPER_TREBLE_ENABLED_KEY + "=" + viper.getTrebleEnhancer().isEnabled() + "\n");
				writer.write(VIPER_TREBLE_STRENGTH_KEY + "=" + viper.getTrebleEnhancer().getStrength() + "\n");

				// Compressor
				writer.write(VIPER_COMPRESSOR_ENABLED_KEY + "=" + viper.getCompressor().isEnabled() + "\n");
				writer.write(VIPER_COMPRESSOR_RATIO_KEY + "=" + viper.getCompressor().getRatio() + "\n");

				// Exciter
				writer.write(VIPER_EXCITER_ENABLED_KEY + "=" + viper.getExciter().isEnabled() + "\n");
				writer.write(VIPER_EXCITER_AMOUNT_KEY + "=" + viper.getExciter().getAmount() + "\n");
			}

			if (showMessages) {
				SairCons.println("音效配置已保存");
			}
		} catch (IOException e) {
			if (showMessages) {
				SairCons.println("保存音效配置失败: " + e.getMessage());
			}
		}
	}

	// ==================== 加载重载方法 ====================

	public void loadConfig(EQProcessor eq, VolumeController vc) {
		loadConfig(eq, vc, null, null, null, true);
	}

	public void loadConfig(EQProcessor eq, VolumeController vc, boolean showMessages) {
		loadConfig(eq, vc, null, null, null, showMessages);
	}

	public void loadConfig(EQProcessor eq, VolumeController vc,
	                      sair.player.audio.SurroundProcessor surround,
	                      sair.player.audio.SoundstageExpander soundstage,
	                      sair.player.audio.ViperEffectsProcessor viper) {
		loadConfig(eq, vc, surround, soundstage, viper, true);
	}

	/**
	 * 从磁盘加载音效配置并应用到处理器。
	 * 
	 * <h4>解析逻辑</h4>
	 * <ol>
	 *   <li>跳过空行和 {@code #} 开头的注释行</li>
	 *   <li>按 {@code =} 分割每行为 key 和 value</li>
	 *   <li>关键设计：使用 <b>独立 if-else if 标签</b> 而非嵌套 else-if，
	 *       每个配置 key 独立判断并校验对应处理器非空。
	 *       这避免了之前嵌套 else-if 导致 Viper 配置永不加载的 bug。</li>
	 * </ol>
	 * 
	 * @param showMessages 是否显示加载提示
	 */
	public void loadConfig(EQProcessor eq, VolumeController vc,
	                      sair.player.audio.SurroundProcessor surround,
	                      sair.player.audio.SoundstageExpander soundstage,
	                      sair.player.audio.ViperEffectsProcessor viper,
	                      boolean showMessages) {
		File configFile = new File(configDir + CONFIG_FILE_NAME);

		if (!configFile.exists()) {
			if (showMessages) {
				SairCons.println("未找到音效配置文件，使用默认配置");
			}
			return;
		}

		try (BufferedReader reader = new BufferedReader(new FileReader(configFile))) {
			String line;

			while ((line = reader.readLine()) != null) {
				line = line.trim();
				// 跳过空行和注释行
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				String[] parts = line.split("=");
				if (parts.length != 2) {
					continue; // 格式异常行跳过
				}

				String key = parts[0].trim();
				String value = parts[1].trim();

				// ── 音量配置 ──
				if (key.equals(VOLUME_KEY)) {
					vc.setVolume(Float.parseFloat(value));
				} else if (key.equals(MASTER_VOLUME_KEY)) {
					vc.setMasterVolume(Float.parseFloat(value));
				} else if (key.equals(MUTED_KEY)) {
					if (Boolean.parseBoolean(value)) {
						vc.mute(); // 仅在 mute=true 时调用，避免 toggle 副作用
					}
				}
				// ── EQ 频段配置（key 格式：eq_band_0 ~ eq_band_14） ──
				else if (key.startsWith(EQ_PREFIX)) {
					int bandIndex = Integer.parseInt(key.substring(EQ_PREFIX.length()));
					float gain = Float.parseFloat(value);
					eq.setBandGain(bandIndex, gain);
				}
				// ── 环绕声配置（每个 key 独立判断且验证处理器非空） ──
				else if (key.equals(SURROUND_MODE_KEY) && surround != null) {
					try {
						sair.player.audio.SurroundProcessor.SurroundMode mode =
							sair.player.audio.SurroundProcessor.SurroundMode.valueOf(value);
						surround.setMode(mode);
					} catch (IllegalArgumentException e) {
						// 忽略无效的模式值（配置文件损坏/手动修改所致）
					}
				} else if (key.equals(SURROUND_WIDTH_KEY) && surround != null) {
					surround.setExpandWidth(Float.parseFloat(value));
				} else if (key.equals(SURROUND_MIX_KEY) && surround != null) {
					surround.setSurroundMix(Float.parseFloat(value));
				}
				// ── 声场扩展配置 ──
				else if (key.equals(SOUNDSTAGE_ENABLED_KEY) && soundstage != null) {
					soundstage.setEnabled(Boolean.parseBoolean(value));
				} else if (key.equals(SOUNDSTAGE_WIDTH_KEY) && soundstage != null) {
					soundstage.setWidth(Float.parseFloat(value));
				} else if (key.equals(SOUNDSTAGE_DEPTH_KEY) && soundstage != null) {
					soundstage.setDepth(Float.parseFloat(value));
				} else if (key.equals(SOUNDSTAGE_HEIGHT_KEY) && soundstage != null) {
					soundstage.setHeight(Float.parseFloat(value));
				} else if (key.equals(SOUNDSTAGE_CENTER_KEY) && soundstage != null) {
					soundstage.setCenterImage(Float.parseFloat(value));
				}
				// ── Viper 音效配置 ──
				else if (key.equals(VIPER_ENABLED_KEY) && viper != null) {
					viper.setEnabled(Boolean.parseBoolean(value));
				} else if (key.equals(VIPER_BASS_ENABLED_KEY) && viper != null) {
					viper.getBassEnhancer().setEnabled(Boolean.parseBoolean(value));
				} else if (key.equals(VIPER_BASS_STRENGTH_KEY) && viper != null) {
					viper.getBassEnhancer().setStrength(Float.parseFloat(value));
				} else if (key.equals(VIPER_TREBLE_ENABLED_KEY) && viper != null) {
					viper.getTrebleEnhancer().setEnabled(Boolean.parseBoolean(value));
				} else if (key.equals(VIPER_TREBLE_STRENGTH_KEY) && viper != null) {
					viper.getTrebleEnhancer().setStrength(Float.parseFloat(value));
				} else if (key.equals(VIPER_COMPRESSOR_ENABLED_KEY) && viper != null) {
					viper.getCompressor().setEnabled(Boolean.parseBoolean(value));
				} else if (key.equals(VIPER_COMPRESSOR_RATIO_KEY) && viper != null) {
					viper.getCompressor().setRatio(Float.parseFloat(value));
				} else if (key.equals(VIPER_EXCITER_ENABLED_KEY) && viper != null) {
					viper.getExciter().setEnabled(Boolean.parseBoolean(value));
				} else if (key.equals(VIPER_EXCITER_AMOUNT_KEY) && viper != null) {
					viper.getExciter().setAmount(Float.parseFloat(value));
				}
			}

			if (showMessages) {
				SairCons.println("音效配置已加载");
			}
		} catch (Exception e) {
			if (showMessages) {
				SairCons.println("加载音效配置失败: " + e.getMessage());
			}
		}
	}

	/** 删除配置文件（重置为默认） */
	public void deleteConfig() {
		File configFile = new File(configDir + CONFIG_FILE_NAME);
		if (configFile.exists()) {
			configFile.delete();
			SairCons.println("音效配置已删除");
		}
	}
}
