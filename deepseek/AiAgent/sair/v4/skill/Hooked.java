package sair.v4.skill;

import com.google.gson.JsonObject;

/**
 * 钩子技能接口：技能可以挂在基板的事件上。
 * <p>基板只提供<b>事件</b>，怎么处理完全在技能里（钩子在=能力在；技能删掉能力就彻底消失）。</p>
 *
 * <h3>钩子名</h3>
 * <pre>
 *   on_timer    基板唯一 tick（周期见 conf.tickMs），payload: {"tick":n,"ts":..}
 *   on_message  一条入站 QQ 消息，payload: 事件 JSON（见 qq.Ev）
 *   on_request  一条好友申请/群邀请，payload: 事件 JSON
 *   on_notice   一条通知事件（戳一戳/撤回/进群/禁言…），payload: 事件 JSON（见 qq.Ev）
 *               —— 只读留痕：这条路上钩子**不能**发言、也起不了一轮（见 hot.Skills.ON_NOTICE）
 * </pre>
 */
public interface Hooked {

    void on(String hook, JsonObject payload, Host h);
}
