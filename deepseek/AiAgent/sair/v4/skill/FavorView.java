package sair.v4.skill;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * 技能的好感度视图（只读）。
 * <p>好感度就是权限门禁：技能可以<b>读</b>它来决定行为（比如熟人优先），
 * 但<b>不能改</b>它 —— 改好感度只有主人能在控制台或通过 {@code perm} 工具做。</p>
 */
public interface FavorView {

    double of(long qq);

    String levelName(double value);

    JsonObject snapshot(long qq);

    List<JsonObject> top(int limit);
}
