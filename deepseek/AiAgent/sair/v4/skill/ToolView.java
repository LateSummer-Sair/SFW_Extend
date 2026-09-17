package sair.v4.skill;

import com.google.gson.JsonObject;

import java.util.List;

import sair.v4.auth.Caller;
import sair.v4.ctx.Turn;
import sair.v4.tool.Tool;

/**
 * 技能的工具视图（只读 + 受控调用）。
 * <p>技能可以看有哪些工具、可以调别的工具（会走权限复核），但<b>不能注册/删除工具</b> ——
 * 工具表只由基板与技能库装载流程维护。</p>
 */
public interface ToolView {

    /** 工具的<b>只读快照</b>（不含实现）：技能不能借它绕过 {@link #call} 的权限复核。 */
    Tool getView(String name);

    List<Tool> visible(Caller c);

    List<String> visibleNames(Caller c);

    int size();

    String describe(Caller c);

    /** 调用工具（含权限复核）。 */
    String call(String name, JsonObject args, Turn t);
}
