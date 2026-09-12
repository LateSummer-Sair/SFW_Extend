package sair.aiagent.core;

import java.util.Arrays;
import java.util.List;

import sair.aiagent.AiAgentActivity;
import sair.aiagent.model.SkillEntry;

/**
 * 系统技能库 —— <b>已整体迁移为单文件技能</b>。
 *
 * <p>
 * 原本在这里硬编码的 30 余条系统技能（工具说明、记忆架构、动态注入、捐赠记录…），
 * 已全部转为项目工作目录 {@code skills/} 下的 .md 文件，每个文件在 front matter 里
 * 显式声明 {@code channels}（可走哪些通道）与 {@code permission}（调用所需权限）。
 * 技能内容从此只有 .md 一个来源：改文案不需要改代码、不需要重新打包、不需要重启。
 * </p>
 *
 * <p>
 * 本类现在只做一件事：<b>把库里遗留的内置技能记录下线</b>。原因是内置技能一旦写进 SQLite
 * 就不会因为「代码里删掉注册」而消失 —— 它们仍会出现在工具索引与 skillinfo 里，与迁入的
 * .md 技能重复。
 * </p>
 */
public class SystemSkillLib {

    /**
     * <b>功能已整体移除</b>的内置技能名单（没有 .md 替身，必须无条件下线）。
     * <p>当前清单：多文件技能包（agentskills.io / data/skillpackages）自 V3.14 起整体移除。</p>
     */
    private static final List<String> RETIRED_BUILTINS = Arrays.asList(
            "agentskills.io 技能包使用");

    /** 把「功能已移除」的旧内置技能记录下线（幂等，每次启动执行）。 */
    public static void retireRemovedSkills(SkillBank bank) {
        if (bank == null || bank.getPersistenceManager() == null) return;
        for (String name : RETIRED_BUILTINS) {
            try {
                SkillEntry s = bank.getPersistenceManager().findSimilarSkill(name, "");
                if (s != null && s.isActive()) {
                    if (bank.getPersistenceManager().deprecateSkill(s.getId())) {
                        AiAgentActivity.debugLog("[SkillBank] 已下线退役内置技能 #" + s.getId()
                                + "（对应功能已移除）: " + name);
                    }
                }
            } catch (Exception e) {
                AiAgentActivity.debugLog("[SkillBank] 下线退役技能失败: " + name + " - " + e);
            }
        }
    }

    /**
     * 把「已被 .md 技能顶替」的内置技能从库里下线（幂等，每次启动执行）。
     * <p>
     * <b>安全性</b>：<b>只有对应 .md 技能真的存在时才下线</b>。也就是说「把 skills/ 里的 .md
     * 拷进 data/skills/」这一步没做时，旧的内置技能文档仍照常工作，不会出现「文档全没了」的空窗；
     * 拷贝过去之后旧记录自动退役，避免与 .md 重复。
     * </p>
     * <p>名单不写死：直接以「当前三方技能库里有没有同名技能」为准，后续增删自动跟随。</p>
     */
    public static void retireSupersededBuiltins(SkillBank bank) {
        if (bank == null || bank.getPersistenceManager() == null) return;
        PersistenceManager pm = bank.getPersistenceManager();
        ThirdPartySkillStore tp = bank.getThirdPartyStore();
        if (tp == null || tp.isEmpty()) return;   // 未安装 .md → 内置技能继续作为唯一来源
        int retired = 0;
        for (SkillEntry s : pm.listAllSkills()) {
            if (s == null || !"builtin".equalsIgnoreCase(s.getSource())) continue;
            if (tp.get(s.getName()) == null) continue;      // 没有 .md 替身 → 保留
            if (pm.deprecateSkill(s.getId())) {
                retired++;
                AiAgentActivity.debugLog("[SkillBank] 内置技能已迁出为单文件技能，旧记录下线 #"
                        + s.getId() + ": " + s.getName());
            }
        }
        if (retired > 0) {
            AiAgentActivity.debugLog("[SkillBank] 共下线 " + retired
                    + " 条已被 .md 技能顶替的内置记录（内容保留，可用 skillinfo 按 id 查回）");
        }
    }

    /** 启动入口：只做旧记录下线，不再注册任何技能（技能内容全在 skills/*.md）。 */
    public static void initSkills(SkillBank bank) {
        if (bank == null) return;
        retireRemovedSkills(bank);
        retireSupersededBuiltins(bank);
    }
}
