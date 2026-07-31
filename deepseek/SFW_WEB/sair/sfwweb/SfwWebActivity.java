package sair.sfwweb;

import sair.FCM;
import sair.Pathes;
import sair.sys.SairCons;
import sair.user.Activity;
import sair.sfwweb.core.*;

/**
 * SFW Web v1.0 — Web 远程控制台插件
 * 默认 HTTP，用户提供 PEM 证书+私钥后可通过 setcert/setkey + togglehttps 启用 HTTPS。
 */
public class SfwWebActivity extends Activity {

    private ConfigManager config;
    private PasswordManager passwordManager;
    private SfwConsoleCapture consoleCapture;
    private CommandHandler commandHandler;
    private WebServer webServer;

    private volatile boolean initialized = false;

    public SfwWebActivity() {
        config = ConfigManager.getInstance();
        passwordManager = new PasswordManager(config);
        consoleCapture = new SfwConsoleCapture();
        commandHandler = new CommandHandler(consoleCapture);
        webServer = new WebServer(config, passwordManager, commandHandler, consoleCapture);
    }

    // ==================== Activity 生命周期 ====================

    @Override
    public Object main(String funcName, String args) {
        if (!initialized) {
            String dataDir = getDataDir();
            config.init(dataDir);
            consoleCapture.init(config.getMaxOutputLines());

            initialized = true;

            String webRoot = config.getWebRoot();
            SairCons.println(FCM.EXECTION_help_Color,
                "[SFW_WEB] Web root: " + (webRoot != null ? webRoot : "(JAR classpath)"));
            SairCons.println(FCM.EXECTION_help_Color,
                "[SFW_WEB] Plugin initialized. DataDir: " + dataDir);

            if (webServer.start()) {
                boolean useHttps = config.isHttpsEnabled() && config.isHttpsReady();
                String protocol = useHttps ? "https" : "http";
                SairCons.println(FCM.EXECTION_help_Color,
                    "[SFW_WEB] Server started at " + protocol + "://localhost:" + config.getPort());

                if (useHttps) {
                    SairCons.println(FCM.EXECTION_help_Color,
                        "[SFW_WEB] HTTPS mode.");
                } else {
                    SairCons.println(FCM.EXECTION_help_Color,
                        "[SFW_WEB] HTTP mode: listening on all interfaces, port: " + config.getPort());
                }

                if (!passwordManager.isPasswordSet()) {
                    SairCons.println(FCM.EXECTION_help_Color,
                        "[SFW_WEB] First run! Open the web page to set your password.");
                }
            } else {
                SairCons.println(FCM.Error_Color,
                    "[SFW_WEB] ERROR: Failed to start web server!");
            }
        }

        return routeCommand(funcName, args);
    }

    // ==================== 命令路由 ====================

    private Object routeCommand(String funcName, String args) {
        if (funcName == null) return help();

        switch (funcName.toLowerCase()) {
            case "start":          return cmdStart();
            case "stop":           return cmdStop();
            case "restart":        return cmdRestart();
            case "status":         return cmdStatus();
            case "setport":        return cmdSetPort(args);
            case "setpassword":    return cmdSetPassword(args);
            case "resetpassword":  return cmdResetPassword(args);
            case "setcert":        return cmdSetCert(args);
            case "setkey":         return cmdSetKey(args);
            case "setwebroot":     return cmdSetWebRoot(args);
            case "settheme":       return cmdSetTheme(args);
            case "setfontcolor":   return cmdSetFontColor(args);
            case "setbgcolor":     return cmdSetBgColor(args);
            case "setbordercolor": return cmdSetBorderColor(args);
            case "setaccentcolor": return cmdSetAccentColor(args);
            case "setfontsize":    return cmdSetFontSize(args);
            case "setfontfamily":  return cmdSetFontFamily(args);
            case "setopacity":     return cmdSetOpacity(args);
            case "togglehttps":    return cmdToggleHttps(args);
            case "showconfig":     return cmdShowConfig();
            case "setmaxlines":    return cmdSetMaxLines(args);
            case "clearlog":       return cmdClearLog();
            default:               return false;
        }
    }

    // ==================== 服务器控制 ====================

    private Object cmdStart() {
        if (webServer.start()) {
            boolean useHttps = config.isHttpsEnabled() && config.isHttpsReady();
            String protocol = useHttps ? "https" : "http";
            SairCons.println(FCM.EXECTION_help_Color,
                "[SFW_WEB] Server started at " + protocol + "://localhost:" + config.getPort());
            return true;
        }
        SairCons.println(FCM.Error_Color, "[SFW_WEB] Failed to start server!");
        return false;
    }

    private Object cmdStop() {
        webServer.stop();
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Server stopped");
        return true;
    }

    private Object cmdRestart() {
        webServer.stop();
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        return cmdStart();
    }

    private Object cmdStatus() {
        boolean useHttps = config.isHttpsEnabled() && config.isHttpsReady();
        String protocol = useHttps ? "https" : "http";
        SairCons.println(FCM.EXECTION_help_Color, Pathes.printSplit);
        SairCons.println(FCM.EXECTION_help_Color, "SFW Web Plugin Status:");
        SairCons.println(FCM.EXECTION_help_Color, "  Server: " + protocol + "://localhost:" + config.getPort());
        SairCons.println(FCM.EXECTION_help_Color, "  Web Root: " + config.getWebRoot());
        SairCons.println(FCM.EXECTION_help_Color, "  Cert: " + (config.isHttpsReady() ? config.getCertFile() : "(not set)"));
        SairCons.println(FCM.EXECTION_help_Color, "  Key:  " + (config.isHttpsReady() ? config.getKeyFile() : "(not set)"));
        SairCons.println(FCM.EXECTION_help_Color, "  Password Set: " + (passwordManager.isPasswordSet() ? "Yes" : "No"));
        SairCons.println(FCM.EXECTION_help_Color, "  Console Lines: " + consoleCapture.getOutputLines().size());
        SairCons.println(FCM.EXECTION_help_Color, "  Command History: " + commandHandler.getHistory().size());
        SairCons.println(FCM.EXECTION_help_Color, Pathes.printSplit);
        return true;
    }

    private Object cmdSetPort(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current port: " + config.getPort());
            return true;
        }
        try {
            config.setPort(Integer.parseInt(args.trim()));
            SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Port set to " + args.trim() + ". Restart to apply.");
        } catch (NumberFormatException e) {
            SairCons.println(FCM.Error_Color, "[SFW_WEB] Invalid port number!");
        }
        return true;
    }

    // ==================== 密码管理 ====================

    private Object cmdSetPassword(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.Error_Color, "[SFW_WEB] Usage: setpassword <new_password>");
            return true;
        }
        if (passwordManager.setPassword(args.trim()))
            SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Password updated.");
        else
            SairCons.println(FCM.Error_Color, "[SFW_WEB] Password must be at least 6 characters!");
        return true;
    }

    private Object cmdResetPassword(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.Error_Color, "[SFW_WEB] Usage: resetpassword <old> <new>");
            return true;
        }
        String[] parts = args.trim().split("\\s+", 2);
        if (parts.length < 2) {
            SairCons.println(FCM.Error_Color, "[SFW_WEB] Usage: resetpassword <old> <new>");
            return true;
        }
        if (passwordManager.resetPassword(parts[0], parts[1]))
            SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Password reset.");
        else
            SairCons.println(FCM.Error_Color, "[SFW_WEB] Old password incorrect or new too short!");
        return true;
    }

    // ==================== HTTPS / 证书 ====================

    private Object cmdSetCert(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current cert: " +
                (config.getCertFile().isEmpty() ? "(not set)" : config.getCertFile()));
            SairCons.println(FCM.EXECTION_help_Color, "Usage: setcert <path/to/cert.pem>");
            return true;
        }
        config.setCertFile(args.trim());
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Cert path set. Also set key with setkey.");
        return true;
    }

    private Object cmdSetKey(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.Error_Color, "[SFW_WEB] Usage: setkey <path/to/key.pem>");
            return true;
        }
        config.setKeyFile(args.trim());
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Key path set.");
        return true;
    }

    private Object cmdSetWebRoot(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current web root: " + config.getWebRoot());
            SairCons.println(FCM.EXECTION_help_Color, "Usage: setwebroot <directory>");
            return true;
        }
        config.setWebRoot(args.trim());
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Web root set to " + args.trim() + ". Restart to apply.");
        return true;
    }

    private Object cmdToggleHttps(String args) {
        boolean current = config.isHttpsEnabled();
        if (args != null && !args.trim().isEmpty()) {
            boolean enable = "true".equalsIgnoreCase(args.trim()) || "on".equalsIgnoreCase(args.trim())
                          || "1".equals(args.trim()) || "enable".equalsIgnoreCase(args.trim());
            config.setHttpsEnabled(enable);
        } else {
            config.setHttpsEnabled(!current);
        }
        if (config.isHttpsEnabled() && !config.isHttpsReady()) {
            SairCons.println(FCM.Error_Color,
                "[SFW_WEB] WARNING: HTTPS enabled but cert/key not configured! Use setcert and setkey first.");
        }
        SairCons.println(FCM.EXECTION_help_Color,
            "[SFW_WEB] HTTPS " + (config.isHttpsEnabled() ? "enabled" : "disabled") + ". Restarting server...");
        webServer.restart();
        return true;
    }

    // ==================== 主题定制 ====================

    private Object cmdSetTheme(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Usage: settheme <bg> <font> <border> <accent>");
            SairCons.println(FCM.EXECTION_help_Color, "Example: settheme #0C0C0C #00FF41 #1A1A2E #E94560");
            return true;
        }
        String[] p = args.trim().split("\\s+");
        if (p.length >= 1) config.setBgColor(p[0]);
        if (p.length >= 2) config.setFontColor(p[1]);
        if (p.length >= 3) config.setBorderColor(p[2]);
        if (p.length >= 4) config.setAccentColor(p[3]);
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Theme updated. Refresh web page.");
        return true;
    }

    private Object cmdSetFontColor(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current: " + config.getFontColor()); return true; }
        config.setFontColor(args.trim());
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Font color -> " + args.trim());
        return true;
    }
    private Object cmdSetBgColor(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current: " + config.getBgColor()); return true; }
        config.setBgColor(args.trim());
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] BG color -> " + args.trim());
        return true;
    }
    private Object cmdSetBorderColor(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current: " + config.getBorderColor()); return true; }
        config.setBorderColor(args.trim());
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Border color -> " + args.trim());
        return true;
    }
    private Object cmdSetAccentColor(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current: " + config.getAccentColor()); return true; }
        config.setAccentColor(args.trim());
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Accent color -> " + args.trim());
        return true;
    }
    private Object cmdSetFontSize(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current: " + config.getFontSize()); return true; }
        try { config.setFontSize(Integer.parseInt(args.trim())); SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Font size -> " + args.trim()); }
        catch (NumberFormatException e) { SairCons.println(FCM.Error_Color, "[SFW_WEB] Invalid size!"); }
        return true;
    }
    private Object cmdSetFontFamily(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current: " + config.getFontFamily()); return true; }
        config.setFontFamily(args.trim());
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Font family -> " + args.trim());
        return true;
    }
    private Object cmdSetOpacity(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current: " + config.getBackgroundOpacity()); return true; }
        try { config.setBackgroundOpacity(Math.max(0, Math.min(1, Double.parseDouble(args.trim()))));
              SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Opacity -> " + config.getBackgroundOpacity()); }
        catch (NumberFormatException e) { SairCons.println(FCM.Error_Color, "[SFW_WEB] Invalid opacity (0.0-1.0)!"); }
        return true;
    }

    // ==================== 其他 ====================

    private Object cmdShowConfig() {
        SairCons.println(FCM.EXECTION_help_Color, Pathes.printSplit);
        SairCons.println(FCM.EXECTION_help_Color, "SFW Web Configuration:");
        SairCons.println(FCM.EXECTION_help_Color, "  Port: " + config.getPort());
        SairCons.println(FCM.EXECTION_help_Color, "  HTTPS: " + config.isHttpsEnabled() + (config.isHttpsReady() ? " (ready)" : " (no cert/key)"));
        SairCons.println(FCM.EXECTION_help_Color, "  Cert: " + (config.getCertFile().isEmpty() ? "(not set)" : config.getCertFile()));
        SairCons.println(FCM.EXECTION_help_Color, "  Key:  " + (config.getKeyFile().isEmpty() ? "(not set)" : config.getKeyFile()));
        SairCons.println(FCM.EXECTION_help_Color, "  Web Root: " + config.getWebRoot());
        SairCons.println(FCM.EXECTION_help_Color, "  Password Set: " + passwordManager.isPasswordSet());
        SairCons.println(FCM.EXECTION_help_Color, "  BG Color: " + config.getBgColor());
        SairCons.println(FCM.EXECTION_help_Color, "  Font Color: " + config.getFontColor());
        SairCons.println(FCM.EXECTION_help_Color, "  Border Color: " + config.getBorderColor());
        SairCons.println(FCM.EXECTION_help_Color, "  Accent Color: " + config.getAccentColor());
        SairCons.println(FCM.EXECTION_help_Color, "  Font Size: " + config.getFontSize());
        SairCons.println(FCM.EXECTION_help_Color, "  Font Family: " + config.getFontFamily());
        SairCons.println(FCM.EXECTION_help_Color, "  Opacity: " + config.getBackgroundOpacity());
        SairCons.println(FCM.EXECTION_help_Color, "  Session Timeout: " + config.getSessionTimeoutMinutes() + " min");
        SairCons.println(FCM.EXECTION_help_Color, "  Max Output Lines: " + config.getMaxOutputLines());
        SairCons.println(FCM.EXECTION_help_Color, Pathes.printSplit);
        return true;
    }

    private Object cmdSetMaxLines(String args) {
        if (args == null || args.trim().isEmpty()) {
            SairCons.println(FCM.EXECTION_help_Color, "Current: " + config.getMaxOutputLines()); return true; }
        try { config.setMaxOutputLines(Integer.parseInt(args.trim()));
              SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Max lines -> " + args.trim()); }
        catch (NumberFormatException e) { SairCons.println(FCM.Error_Color, "[SFW_WEB] Invalid number!"); }
        return true;
    }

    private Object cmdClearLog() {
        consoleCapture.clear();
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Console log cleared.");
        return true;
    }

    // ==================== help / exit ====================

    @Override
    public String[] help() {
        String n = getName();
        return new String[] {
            Pathes.printSplit,
            "SFW Web v1.0 — Web 远程控制台 | 默认 HTTP | 密码认证 | SSE 实时 | PEM 证书",
            "HTTPS 需提供 PEM 证书+私钥，启用后自动关闭 HTTP 并重启服务。",
            Pathes.printSplit,
            "服务器:",
            "\t" + n + "/start                    启动服务器",
            "\t" + n + "/stop                     停止服务器",
            "\t" + n + "/restart                  重启服务器",
            "\t" + n + "/status                   查看状态",
            "\t" + n + "/setport [端口]           设置监听端口（默认8080，需重启）",
            Pathes.printSplit,
            "HTTPS:",
            "\t" + n + "/setcert [路径]           设置 PEM 证书文件路径",
            "\t" + n + "/setkey [路径]            设置 PEM 私钥文件路径",
            "\t" + n + "/togglehttps [on|off]     启停 HTTPS（自动重启）",
            "\t" + n + "/setwebroot [目录]        设置前端文件根目录（需重启）",
            Pathes.printSplit,
            "密码:",
            "\t" + n + "/setpassword [密码]       设置登录密码（>=6位）",
            "\t" + n + "/resetpassword [旧] [新]  重置密码",
            Pathes.printSplit,
            "主题:",
            "\t" + n + "/settheme [背景] [字] [框] [强调] 一键配色",
            "\t" + n + "/setfontcolor [色]        文字颜色",
            "\t" + n + "/setbgcolor [色]          背景颜色",
            "\t" + n + "/setbordercolor [色]      边框颜色",
            "\t" + n + "/setaccentcolor [色]      强调色",
            "\t" + n + "/setfontsize [大小]       字体大小",
            "\t" + n + "/setfontfamily [字体]     字体族",
            "\t" + n + "/setopacity [0-1]         背景透明度",
            Pathes.printSplit,
            "其他:",
            "\t" + n + "/showconfig               显示完整配置",
            "\t" + n + "/setmaxlines [条数]       最大输出行数",
            "\t" + n + "/clearlog                 清空 Web 控制台",
            Pathes.printSplit,
        };
    }

    @Override
    public void exit() {
        webServer.stop();
        consoleCapture.shutdown();
        config.save();
        SairCons.println(FCM.EXECTION_help_Color, "[SFW_WEB] Plugin shutdown complete");
    }

    @Override
    protected String dataDir() {
        return "sair.sfwweb.SfwWebActivity";
    }
}
