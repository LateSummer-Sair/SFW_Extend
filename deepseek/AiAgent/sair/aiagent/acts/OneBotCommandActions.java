package sair.aiagent.acts;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

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

    // ==================== 拟人化监听态开关 ====================

    public Object handleOneBotEnableListen() {
        act.getConfig().setListenStateEnabled(true);
        act.getConfig().save();
        if (act.getOneBotMessageHandler() != null) {
            act.getOneBotMessageHandler().enableListeningState();
            EdtUtils.println(ActivityActions.C_INFO, "拟人化监听态已启用");
        } else {
            EdtUtils.println(ActivityActions.C_INFO, "监听态配置已保存，下次启动OneBot时生效");
        }
        return true;
    }

    public Object handleOneBotDisableListen() {
        act.getConfig().setListenStateEnabled(false);
        act.getConfig().save();
        if (act.getOneBotMessageHandler() != null) {
            act.getOneBotMessageHandler().disableListeningState();
            EdtUtils.println(ActivityActions.C_INFO, "拟人化监听态已禁用");
        } else {
            EdtUtils.println(ActivityActions.C_INFO, "监听态配置已保存，下次启动OneBot时生效");
        }
        return true;
    }

    public Object handleOneBotAddGroup(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebotaddgroup [群号1 群号2 ...]（支持空格分隔多个群号）");
        String[] tokens = args.trim().split("\\s+");
        List<Long> added = new ArrayList<>();
        List<Long> existed = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            try {
                long groupId = Long.parseLong(token);
                if (act.getConfig().addMonitoredGroup(groupId)) {
                    added.add(groupId);
                    if (act.getOneBotMessageHandler() != null) {
                        act.getOneBotMessageHandler().addMonitoredGroup(groupId);
                    }
                } else {
                    existed.add(groupId);
                }
            } catch (NumberFormatException e) {
                invalid.add(token);
            }
        }
        act.getConfig().save();
        if (!added.isEmpty()) EdtUtils.println(ActivityActions.C_INFO, "已添加监听群: " + added);
        if (!existed.isEmpty()) EdtUtils.println(ActivityActions.C_INFO, "已在监听列表: " + existed);
        if (!invalid.isEmpty()) EdtUtils.println(ActivityActions.C_ERR, "无效群号(忽略): " + invalid);
        return true;
    }

    public Object handleOneBotRemoveGroup(String args) {
        if (ActivityActions.isEmpty(args)) return ActivityActions.err("用法: ai/onebotremovegroup [群号1 群号2 ...]（支持空格分隔多个群号）");
        String[] tokens = args.trim().split("\\s+");
        List<Long> removed = new ArrayList<>();
        List<String> invalid = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            try {
                long groupId = Long.parseLong(token);
                if (act.getConfig().removeMonitoredGroup(groupId)) {
                    removed.add(groupId);
                    if (act.getOneBotMessageHandler() != null) {
                        act.getOneBotMessageHandler().removeMonitoredGroup(groupId);
                    }
                }
            } catch (NumberFormatException e) {
                invalid.add(token);
            }
        }
        act.getConfig().save();
        if (!removed.isEmpty()) EdtUtils.println(ActivityActions.C_INFO, "已移除监听群: " + removed);
        if (!invalid.isEmpty()) EdtUtils.println(ActivityActions.C_ERR, "无效群号(忽略): " + invalid);
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

    // ==================== 好感度复位 ====================

    public Object handleResetAffection() {
        if (act.getOneBotMessageHandler() != null && act.getOneBotMessageHandler().getEmotionManager() != null) {
            act.getOneBotMessageHandler().getEmotionManager().resetAllAffections();
            EdtUtils.println(ActivityActions.C_INFO, "已重置所有用户的好感度");
        } else {
            EdtUtils.println(ActivityActions.C_ERR, "情绪管理器未就绪，无法重置好感度");
        }
        return true;
    }

    /** 重置全部捐赠记录（从数据库层面彻底清空，含自增 ID 归零）。 */
    public Object handleResetDonations() {
        if (act.getOneBotMessageHandler() != null && act.getOneBotMessageHandler().getEmotionManager() != null) {
            act.getOneBotMessageHandler().getEmotionManager().resetAllDonations();
            EdtUtils.println(ActivityActions.C_INFO, "已重置全部捐赠记录（数据库已彻底清空）");
        } else {
            EdtUtils.println(ActivityActions.C_ERR, "情绪管理器未就绪，无法重置捐赠记录");
        }
        return true;
    }
}
