package sair.aiagent.onebot;

import sair.aiagent.core.SkillBank;

/**
 * NapCat（QQ 通道）专属技能库 —— <b>已整体迁移为单文件技能</b>。
 *
 * <p>
 * 原本在这里硬编码的 52 条 NapCat API 技能（发送消息 / 群管理 / 媒体 / 互动 / 文件 / 配置…），
 * 已全部转为项目工作目录 {@code skills/} 下的 .md 文件，每个文件在 front matter 里显式声明：
 * </p>
 * <ul>
 *   <li>{@code channels: execq,execs} —— 这批能力只在 QQ 通道存在（本地 console 没有 NapCat API）；</li>
 *   <li>{@code permission:} —— 对齐权限矩阵，例如群组禁言=GROUP_MASTER、发送文件=AFFECTION:300、
 *       拉黑用户=MASTER。</li>
 * </ul>
 *
 * <p>
 * 技能内容从此只有 .md 一个来源：改文案不需要改代码、不需要重新打包。本类保留为空壳，
 * 仅作为接线点存在（{@code AiAgentActivity} 仍会调用 {@link #initSkills(SkillBank)}）。
 * </p>
 */
public class NapCatSkillLib {

    /**
     * 启动入口 —— 不再注册任何技能（内容已迁到 {@code skills/*.md}）。
     * <p>旧库记录的统一下线由 {@code SystemSkillLib.retireSupersededBuiltins()} 处理：
     * 它对「所有 source=builtin 且已有同名 .md 技能」的记录生效，与技能原本来自哪个库无关。</p>
     */
    public static void initSkills(SkillBank bank) {
        // 迁移后无需再做任何事：技能由 ThirdPartySkillStore 从 data/skills/ 加载，
        // 旧的内置记录由 SystemSkillLib 的退役逻辑按「有无 .md 替身」自动下线。
    }
}
