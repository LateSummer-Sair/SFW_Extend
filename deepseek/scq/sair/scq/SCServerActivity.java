package sair.scq;

import sair.Pathes;
import sair.scq.acts.ServerActions;
import sair.scq.server.SCServerCore;
import sair.scq.ui.ServerUI;
import sair.user.Activity;

/**
 * SCQ服务端Activity —— 提供SCQ即时聊天服务端的控制台命令入口。
 * 
 * <h3>命令命名空间</h3>
 * 注册名为 "scq"，命令格式为 scq/命令 参数。
 * 服务端命令通过 Activity 主入口路由到 ServerActions。
 * 
 * <h3>支持的命令</h3>
 * <ul>
 *   <li>start [port] - 启动服务端</li>
 *   <li>stop - 停止服务端</li>
 *   <li>status - 查看服务端状态</li>
 *   <li>listUsers - 列出在线用户</li>
 *   <li>listAllUsers - 列出所有注册用户</li>
 *   <li>listGroups - 列出所有群组</li>
 *   <li>showUI - 显示管理面板</li>
 *   <li>setRole - 设置用户角色</li>
 * </ul>
 */
public class SCServerActivity extends Activity {

    private SCServerCore serverCore;
    private ServerActions serverActions;
    private ServerUI serverUI;

    @Override
    public Object main(String funcName, String args) {
        // 延迟初始化
        initIfNeeded();

        switch (funcName) {
            case "start":
                return serverActions.start(args);
            case "stop":
                return serverActions.stop();
            case "status":
                return serverActions.status();
            case "listUsers":
                return serverActions.listUsers();
            case "listAllUsers":
                return serverActions.listAllUsers();
            case "listGroups":
                return serverActions.listGroups();
            case "showUI":
                return serverActions.showUI();
            case "setRole":
                return serverActions.setRole(args);
            default:
                return false;
        }
    }

    private void initIfNeeded() {
        if (serverCore == null) {
            serverCore = new SCServerCore(this.getDataDir());
            serverActions = new ServerActions(serverCore);
            serverUI = new ServerUI(serverActions);
            serverActions.setServerUI(serverUI);
        }
    }

    @Override
    public String[] help() {
        String name = this.getName();
        return new String[] {
            Pathes.printSplit,
            "SCQ 即时聊天 - 服务端",
            "Version: 1.0",
            Pathes.printSplit,
            "服务管理:",
            "\t" + name + "/start [port] 启动服务端（默认端口9000）",
            "\t" + name + "/stop 停止服务端",
            "\t" + name + "/status 查看服务端状态",
            "用户管理:",
            "\t" + name + "/listUsers 列出在线用户",
            "\t" + name + "/listAllUsers 列出所有注册用户",
            "\t" + name + "/setRole <UID> <SUPER_ADMIN|ADMIN|MEMBER> 设置用户角色",
            "群组管理:",
            "\t" + name + "/listGroups 列出所有群组",
            "界面:",
            "\t" + name + "/showUI 显示服务端管理面板",
            Pathes.printSplit,
        };
    }

    @Override
    public void exit() {
        if (serverActions != null) {
            serverActions.exit();
        }
    }
}
