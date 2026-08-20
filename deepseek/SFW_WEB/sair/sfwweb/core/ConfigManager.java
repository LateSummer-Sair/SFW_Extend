package sair.sfwweb.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SFW Web 配置管理器
 * HTTPS：默认关闭。用户提供 PEM 证书(.crt/.pem)和私钥(.key)文件即可启用。
 */
public class ConfigManager {

    public static final int DEFAULT_PORT = 8080;
    public static final boolean DEFAULT_HTTPS_ENABLED = false;

    public static final String DEFAULT_BG_COLOR = "#0C0C0C";
    public static final String DEFAULT_FONT_COLOR = "#00FF41";
    public static final String DEFAULT_BORDER_COLOR = "#1A1A2E";
    public static final String DEFAULT_ACCENT_COLOR = "#E94560";
    public static final String DEFAULT_FONT_FAMILY = "Consolas, 'Courier New', monospace";
    public static final int DEFAULT_FONT_SIZE = 14;

    private final Properties props;
    private File configFile;
    private String dataDir;

    private static final ConfigManager instance = new ConfigManager();

    public static ConfigManager getInstance() {
        return instance;
    }

    private volatile boolean savePending = false;
    private final ScheduledExecutorService saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SFW_WEB_ConfigSaver");
        t.setDaemon(true);
        return t;
    });

    private ConfigManager() {
        props = new Properties();
        setDefaults();
    }

    /** 延迟保存：500ms 内多次调用只写一次磁盘 */
    private void saveDeferred() {
        if (savePending) return;
        savePending = true;
        saveExecutor.schedule(() -> {
            savePending = false;
            save();
        }, 500, TimeUnit.MILLISECONDS);
    }

    private void setDefaults() {
        props.setProperty("port", String.valueOf(DEFAULT_PORT));
        props.setProperty("https.enabled", String.valueOf(DEFAULT_HTTPS_ENABLED));
        props.setProperty("cert.file", "");
        props.setProperty("key.file", "");
        props.setProperty("web.root", "");
        props.setProperty("password.hash", "");
        props.setProperty("password.salt", "");
        props.setProperty("theme.bg_color", DEFAULT_BG_COLOR);
        props.setProperty("theme.font_color", DEFAULT_FONT_COLOR);
        props.setProperty("theme.border_color", DEFAULT_BORDER_COLOR);
        props.setProperty("theme.accent_color", DEFAULT_ACCENT_COLOR);
        props.setProperty("theme.font_family", DEFAULT_FONT_FAMILY);
        props.setProperty("theme.font_size", String.valueOf(DEFAULT_FONT_SIZE));
        props.setProperty("theme.background_opacity", "1.0");
        props.setProperty("session.timeout_minutes", "30");
        props.setProperty("max_output_lines", "5000");
    }

    public void init(String dataDir) {
        this.dataDir = dataDir;
        this.configFile = new File(dataDir, "sfwweb.properties");
        if (configFile.exists()) {
            load();
        } else {
            save();
        }
    }

    private void load() {
        try (FileInputStream fis = new FileInputStream(configFile);
             InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
            props.load(isr);
        } catch (IOException e) {
            System.err.println("[SFW_WEB] Failed to load config: " + e.getMessage());
            setDefaults();
        }
    }

    public void save() {
        if (configFile == null) return;
        try (FileOutputStream fos = new FileOutputStream(configFile);
             OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
            props.store(osw, "SFW Web Plugin Configuration");
        } catch (IOException e) {
            System.err.println("[SFW_WEB] Failed to save config: " + e.getMessage());
        }
    }

    // ==================== Getters ====================

    public int getPort() {
        return Integer.parseInt(props.getProperty("port", String.valueOf(DEFAULT_PORT)));
    }

    public boolean isHttpsEnabled() {
        return Boolean.parseBoolean(props.getProperty("https.enabled", String.valueOf(DEFAULT_HTTPS_ENABLED)));
    }

    /** PEM 证书文件路径（为空则表示未配置） */
    public String getCertFile() {
        return props.getProperty("cert.file", "");
    }

    /** PEM 私钥文件路径 */
    public String getKeyFile() {
        return props.getProperty("key.file", "");
    }

    public String getWebRoot() {
        String root = props.getProperty("web.root", "");
        if (root == null || root.trim().isEmpty()) {
            return null;
        }
        if (!root.endsWith(File.separator)) {
            root += File.separator;
        }
        return root;
    }

    /** HTTPS 是否实际可用（证书和私钥文件均已配置且存在） */
    public boolean isHttpsReady() {
        String cert = getCertFile();
        if (cert == null || cert.trim().isEmpty()) return false;
        if (!new File(cert).exists() || !new File(cert).isFile()) return false;
        String key = getKeyFile();
        if (key == null || key.trim().isEmpty()) return false;
        return new File(key).exists() && new File(key).isFile();
    }

    public String getPasswordHash() { return props.getProperty("password.hash", ""); }
    public String getPasswordSalt() { return props.getProperty("password.salt", ""); }
    public String getBgColor() { return props.getProperty("theme.bg_color", DEFAULT_BG_COLOR); }
    public String getFontColor() { return props.getProperty("theme.font_color", DEFAULT_FONT_COLOR); }
    public String getBorderColor() { return props.getProperty("theme.border_color", DEFAULT_BORDER_COLOR); }
    public String getAccentColor() { return props.getProperty("theme.accent_color", DEFAULT_ACCENT_COLOR); }
    public String getFontFamily() { return props.getProperty("theme.font_family", DEFAULT_FONT_FAMILY); }
    public int getFontSize() { return Integer.parseInt(props.getProperty("theme.font_size", String.valueOf(DEFAULT_FONT_SIZE))); }
    public double getBackgroundOpacity() { return Double.parseDouble(props.getProperty("theme.background_opacity", "1.0")); }
    public int getSessionTimeoutMinutes() { return Integer.parseInt(props.getProperty("session.timeout_minutes", "30")); }
    public int getMaxOutputLines() { return Integer.parseInt(props.getProperty("max_output_lines", "5000")); }

    public boolean isPasswordSet() {
        String hash = getPasswordHash();
        return hash != null && !hash.isEmpty();
    }

    public String getDataDir() { return dataDir; }

    public Properties getAllProperties() {
        return (Properties) props.clone();
    }

    // ==================== Setters ====================

    public void setPort(int port) { props.setProperty("port", String.valueOf(port)); saveDeferred(); }
    public void setHttpsEnabled(boolean enabled) { props.setProperty("https.enabled", String.valueOf(enabled)); saveDeferred(); }
    public void setCertFile(String path) { props.setProperty("cert.file", path != null ? path : ""); saveDeferred(); }
    public void setKeyFile(String path) { props.setProperty("key.file", path != null ? path : ""); saveDeferred(); }
    public void setWebRoot(String root) { props.setProperty("web.root", root != null ? root : ""); saveDeferred(); }
    public void setPasswordHash(String hash) { props.setProperty("password.hash", hash); saveDeferred(); }
    public void setPasswordSalt(String salt) { props.setProperty("password.salt", salt); saveDeferred(); }
    public void setBgColor(String color) { props.setProperty("theme.bg_color", color); saveDeferred(); }
    public void setFontColor(String color) { props.setProperty("theme.font_color", color); saveDeferred(); }
    public void setBorderColor(String color) { props.setProperty("theme.border_color", color); saveDeferred(); }
    public void setAccentColor(String color) { props.setProperty("theme.accent_color", color); saveDeferred(); }
    public void setFontFamily(String family) { props.setProperty("theme.font_family", family); saveDeferred(); }
    public void setFontSize(int size) { props.setProperty("theme.font_size", String.valueOf(size)); saveDeferred(); }
    public void setBackgroundOpacity(double opacity) { props.setProperty("theme.background_opacity", String.valueOf(opacity)); saveDeferred(); }
    public void setSessionTimeoutMinutes(int minutes) { props.setProperty("session.timeout_minutes", String.valueOf(minutes)); saveDeferred(); }
    public void setMaxOutputLines(int lines) { props.setProperty("max_output_lines", String.valueOf(lines)); saveDeferred(); }
}
