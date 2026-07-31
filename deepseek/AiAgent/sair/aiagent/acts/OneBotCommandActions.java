package sair.aiagent.acts;

import java.awt.Color;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.util.EdtUtils;

/**
 * OneBot QQ 命令处理 —— 从 {@link ActivityActions} 提取的所有 OneBot QQ 相关命令。
 * <p>负责处理 execq 通道、OneBot 连接管理、白名单管理、主动查看配置等。</p>
 */
public class OneBotCommandActions {

    private final AiAgentActivity act;

    public OneBotCommandActions(AiAgentActivity act) {
        this.act = act;
    }

    // ==================== execq QQ通道 ====================

    /** execq —— QQ通道Agent模式（受限标签，自动允许） */
    public Object handleExecq(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/execq [QQ消息内容]\n注意：execq通常由QQ消息自动触发，此命令仅用于测试。");
        if (!ActivityActions.checkKey(act)) return false;

        final String task = args.trim();
        EdtUtils.println(ActivityActions.C_INFO, "[execq测试] " + task);
        act.getGate().setBypassConfirm(true);
        // 回退到exec模式
        return new ActivityActions(act).handleExec(args);
    }

    // ==================== OneBot 连接管理 ====================

    public Object handleOneBotConnect() {
        sair.aiagent.onebot.OneBotServer server = act.getOneBotServer();
        if (server == null) {
            EdtUtils.println(ActivityActions.C_ERR, "OneBot服务未初始化。");
            return false;
        }
        if (server.isRunning()) {
            EdtUtils.println(ActivityActions.C_INFO, "OneBot服务已在运行中，端口: " + server.getPort());
            return true;
        }
        server.setPort(act.getConfig().getOnebotPort());
        server.setAccessToken(act.getConfig().getOnebotToken());
        if (server.start()) {
            act.getConfig().setOnebotEnabled(true);
            act.getConfig().save();
            EdtUtils.println(new Color(100, 255, 100), "OneBot服务已启动，监听端口: " + server.getPort());
            EdtUtils.println(ActivityActions.C_INFO, "请在OneBot实现端配置反向WebSocket连接到: ws://127.0.0.1:" + server.getPort() + "/");
        } else {
            EdtUtils.println(ActivityActions.C_ERR, "OneBot服务启动失败，端口 " + server.getPort() + " 可能被占用。");
        }
        return true;
    }

    public Object handleOneBotDisconnect() {
        sair.aiagent.onebot.OneBotServer server = act.getOneBotServer();
        if (server == null) {
            EdtUtils.println(ActivityActions.C_ERR, "OneBot服务未初始化。");
            return false;
        }
        server.stop();
        act.getConfig().setOnebotEnabled(false);
        act.getConfig().save();
        EdtUtils.println(ActivityActions.C_INFO, "OneBot服务已停止。");
        return true;
    }

    public Object handleOneBotStatus() {
        sair.aiagent.onebot.OneBotServer server = act.getOneBotServer();
        if (server == null) {
            EdtUtils.println(ActivityActions.C_ERR, "OneBot服务未初始化。");
            return false;
        }
        EdtUtils.println(ActivityActions.C_INFO, "== OneBot QQ 状态 ==");
        EdtUtils.println(ActivityActions.C_INFO, "运行状态: " + (server.isRunning() ? "运行中" : "已停止"));
        EdtUtils.println(ActivityActions.C_INFO, "监听端口: " + server.getPort());
        EdtUtils.println(ActivityActions.C_INFO, "连接数  : " + server.getConnectionCount());
        EdtUtils.println(ActivityActions.C_INFO, "Token   : " + (act.getConfig().getOnebotToken().isEmpty() ? "(未设置)" : "已设置"));
        EdtUtils.println(ActivityActions.C_INFO, "机器人QQ: " + (act.getConfig().getOnebotSelfId() > 0 ? String.valueOf(act.getConfig().getOnebotSelfId()) : "(未设置)"));
        return true;
    }

    // ==================== OneBot 配置 ====================

    public Object handleOneBotSetPort(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebot/setport [端口号]");
        try {
            int port = Integer.parseInt(args.trim());
            act.getConfig().setOnebotPort(port);
            act.getConfig().save();
            EdtUtils.println(ActivityActions.C_INFO, "OneBot端口 -> " + port);
        } catch (NumberFormatException e) {
            return ActivityActions.err("端口号必须是数字。");
        }
        return true;
    }

    public Object handleOneBotSetToken(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebot/settoken [Token]");
        act.getConfig().setOnebotToken(args.trim());
        act.getConfig().save();
        EdtUtils.println(ActivityActions.C_INFO, "OneBot Token已设置。");
        return true;
    }

    public Object handleOneBotSetSelfId(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebot/setselfid [QQ号]");
        try {
            long qq = Long.parseLong(args.trim());
            act.getConfig().setOnebotSelfId(qq);
            act.getConfig().save();
            if (act.getOneBotMessageHandler() != null) {
                act.getOneBotMessageHandler().setSelfId(qq);
            }
            EdtUtils.println(ActivityActions.C_INFO, "机器人QQ号 -> " + qq);
        } catch (NumberFormatException e) {
            return ActivityActions.err("QQ号必须是数字。");
        }
        return true;
    }

    public Object handleOneBotSetPrompt(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebot/setprompt [提示词]");
        act.getConfig().setExecqPrompt(args);
        act.getConfig().save();
        EdtUtils.println(ActivityActions.C_INFO, "execq提示词已更新 (长度: " + act.getConfig().getExecqPrompt().length() + ")");
        return true;
    }

    public Object handleOneBotShowPrompt() {
        ActivityActions.print(new Color(100, 255, 180), "[execq 系统提示词]");
        EdtUtils.println(ActivityActions.C_INFO, "\n" + act.getConfig().getExecqPrompt());
        return true;
    }

    // ==================== execq 插件白名单管理 ====================

    public Object handleOneBotWhitelist() {
        java.util.Set<String> wl = act.getConfig().getExecqCmdWhitelist();
        ActivityActions.print(new Color(100, 255, 180), "[execq 插件白名单]");
        if (wl.isEmpty()) {
            EdtUtils.println(ActivityActions.C_INFO, "\n(空 — 所有 <cmd> 命令均被拒绝。使用 ai/onebot/whitelist/add 添加插件)");
        } else {
            EdtUtils.println(ActivityActions.C_INFO, "\n允许的插件: " + wl);
            EdtUtils.println(ActivityActions.C_INFO, "配置文件: config.properties → execqCmdWhitelist");
        }
        return true;
    }

    public Object handleOneBotWhitelistAdd(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebot/whitelist/add [插件名]");
        String pluginName = args.trim();
        if (act.getConfig().addExecqCmdPlugin(pluginName)) {
            act.getConfig().save();
            act.getAgent().setCmdWhitelist(act.getConfig().getExecqCmdWhitelist());
            EdtUtils.println(ActivityActions.C_INFO, "execq白名单已添加: [" + pluginName + "]，当前白名单: " + act.getConfig().getExecqCmdWhitelist());
        } else {
            EdtUtils.println(ActivityActions.C_INFO, "插件 [" + pluginName + "] 已在白名单中，当前: " + act.getConfig().getExecqCmdWhitelist());
        }
        return true;
    }

    public Object handleOneBotWhitelistRemove(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebotwhitelistremove [插件名]");
        String pluginName = args.trim();
        if (act.getConfig().removeExecqCmdPlugin(pluginName)) {
            act.getConfig().save();
            act.getAgent().setCmdWhitelist(act.getConfig().getExecqCmdWhitelist());
            EdtUtils.println(ActivityActions.C_INFO, "execq白名单已移除: [" + pluginName + "]，当前白名单: " + act.getConfig().getExecqCmdWhitelist());
        } else {
            EdtUtils.println(ActivityActions.C_INFO, "插件 [" + pluginName + "] 不在白名单中，当前: " + act.getConfig().getExecqCmdWhitelist());
        }
        return true;
    }

    // ==================== 主动查看配置命令 ====================

    public Object handleOneBotEnableProactive() {
        act.getConfig().setProactiveCheckEnabled(true);
        act.getConfig().save();
        if (act.getOneBotMessageHandler() != null) {
            act.getOneBotMessageHandler().enableProactiveCheck();
            EdtUtils.println(ActivityActions.C_INFO, "主动查看功能已启用（每5分钟检查一次）");
        } else {
            EdtUtils.println(ActivityActions.C_INFO, "主动查看配置已保存，下次启动OneBot时生效");
        }
        return true;
    }

    public Object handleOneBotDisableProactive() {
        act.getConfig().setProactiveCheckEnabled(false);
        act.getConfig().save();
        if (act.getOneBotMessageHandler() != null) {
            act.getOneBotMessageHandler().disableProactiveCheck();
        }
        EdtUtils.println(ActivityActions.C_INFO, "主动查看功能已禁用");
        return true;
    }

    public Object handleOneBotAddGroup(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebotaddgroup [群号]");
        try {
            long groupId = Long.parseLong(args.trim());
            if (act.getConfig().addMonitoredGroup(groupId)) {
                act.getConfig().save();
                if (act.getOneBotMessageHandler() != null) {
                    act.getOneBotMessageHandler().addMonitoredGroup(groupId);
                }
                EdtUtils.println(ActivityActions.C_INFO, "已添加监听群: " + groupId);
            } else {
                EdtUtils.println(ActivityActions.C_INFO, "群号已在监听列表中: " + groupId);
            }
        } catch (NumberFormatException e) {
            return ActivityActions.err("群号必须是数字。");
        }
        return true;
    }

    public Object handleOneBotRemoveGroup(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebotremovegroup [群号]");
        try {
            long groupId = Long.parseLong(args.trim());
            if (act.getConfig().removeMonitoredGroup(groupId)) {
                act.getConfig().save();
                if (act.getOneBotMessageHandler() != null) {
                    act.getOneBotMessageHandler().removeMonitoredGroup(groupId);
                }
                EdtUtils.println(ActivityActions.C_INFO, "已移除监听群: " + groupId);
            } else {
                EdtUtils.println(ActivityActions.C_INFO, "群号不在监听列表中: " + groupId);
            }
        } catch (NumberFormatException e) {
            return ActivityActions.err("群号必须是数字。");
        }
        return true;
    }

    public Object handleOneBotListGroups() {
        java.util.Set<Long> groups = act.getConfig().getMonitoredGroups();
        if (groups.isEmpty()) {
            EdtUtils.println(ActivityActions.C_INFO, "当前没有监听的群");
        } else {
            EdtUtils.println(ActivityActions.C_INFO, "监听的群列表 (共" + groups.size() + "个):");
            for (Long g : groups) {
                EdtUtils.println(ActivityActions.C_INFO, "  - " + g);
            }
        }
        EdtUtils.println(ActivityActions.C_INFO, "主动查看状态: " + (act.getConfig().isProactiveCheckEnabled() ? "已启用" : "已禁用"));
        return true;
    }
}
