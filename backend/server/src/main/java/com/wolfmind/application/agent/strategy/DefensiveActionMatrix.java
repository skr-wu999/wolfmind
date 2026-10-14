package com.wolfmind.application.agent.strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置的动作白名单矩阵：**只为防御**，不是第二份权限矩阵（tool-gateway.md §3）。
 *
 * <p>生产装配一律注入 {@code ToolGatewayPort}，{@link WolfMindStrategy} 优先调用端口的
 * {@code allowedActionTypes(role, phase)}，本类只在端口缺席（例如纯离线单测、装配漏注入）时兜底。
 * 之所以不把这段删掉：策略层在没有网关的离线场景下仍要能产出候选集，否则"纯规则基线"
 * 与单元测试都得拖一个假网关进来。
 *
 * <p>它的内容按 tool-gateway.md §3 的权限矩阵逐格翻译（工具 → 动作类型、阶段 → 动作阶段），
 * 权当"端口没接上时的最小可用集合"。**权限矩阵的改动必须落在端口实现里**，
 * 本类不同步修改只会影响离线兜底，不会影响生产行为——这正是它保持私有包可见的原因。
 */
final class DefensiveActionMatrix {

    private DefensiveActionMatrix() {}

    /** 该角色该阶段允许的动作类型（无动作时返回空列表）。 */
    static List<String> actionTypes(String role, String phase) {
        String normalizedRole = StrategyCodes.normalize(role);
        String normalizedPhase = StrategyCodes.normalize(phase);
        List<String> actions = new ArrayList<>();
        // 任何活着的玩家都能发言与投票；SKIP 在任何需提交的阶段都合法（api-spec.md §5.3）
        if (StrategyCodes.DAY_DISCUSS.equals(normalizedPhase) || StrategyCodes.LAST_WORDS.equals(normalizedPhase)) {
            actions.add(StrategyCodes.SPEAK);
        }
        if (StrategyCodes.DAY_VOTE.equals(normalizedPhase)) {
            actions.add(StrategyCodes.VOTE);
        }
        switch (normalizedRole) {
            case StrategyCodes.WEREWOLF -> {
                if (StrategyCodes.NIGHT_WOLF.equals(normalizedPhase)) {
                    actions.add(StrategyCodes.WOLF_KILL);
                }
            }
            case StrategyCodes.SEER -> {
                if (StrategyCodes.NIGHT_SEER.equals(normalizedPhase)) {
                    actions.add(StrategyCodes.SEER_INSPECT);
                }
            }
            case StrategyCodes.WITCH -> {
                if (StrategyCodes.NIGHT_WITCH.equals(normalizedPhase)) {
                    actions.add(StrategyCodes.WITCH_SAVE);
                    actions.add(StrategyCodes.WITCH_POISON);
                    actions.add(StrategyCodes.WITCH_PASS);
                }
            }
            case StrategyCodes.HUNTER -> {
                if (StrategyCodes.HUNTER_SHOOT_PHASE.equals(normalizedPhase)) {
                    actions.add(StrategyCodes.HUNTER_SHOOT);
                }
            }
            default -> {
                // 未知角色没有专属动作
            }
        }
        if (StrategyCodes.SUBMIT_PHASES.contains(normalizedPhase)) {
            actions.add(StrategyCodes.SKIP);
        }
        return List.copyOf(actions);
    }

    /** 该角色该阶段可用的工具名（进 prompt 的 availableTools，与 §3 矩阵同源）。 */
    static List<String> toolNames(String role, String phase) {
        String normalizedRole = StrategyCodes.normalize(role);
        String normalizedPhase = StrategyCodes.normalize(phase);
        List<String> tools = new ArrayList<>();
        tools.add("QueryGameStateTool");
        tools.add("QueryHistoryTool");
        if (StrategyCodes.DAY_DISCUSS.equals(normalizedPhase) || StrategyCodes.LAST_WORDS.equals(normalizedPhase)) {
            tools.add("SpeakTool");
        }
        if (StrategyCodes.DAY_VOTE.equals(normalizedPhase)) {
            tools.add("SubmitVoteTool");
        }
        if (StrategyCodes.WEREWOLF.equals(normalizedRole) && StrategyCodes.NIGHT_WOLF.equals(normalizedPhase)) {
            tools.add("KillPlayerTool");
        }
        if (StrategyCodes.NIGHT_SEER.equals(normalizedPhase) && StrategyCodes.SEER.equals(normalizedRole)) {
            tools.add("UseSkillTool");
        }
        if (StrategyCodes.NIGHT_WITCH.equals(normalizedPhase) && StrategyCodes.WITCH.equals(normalizedRole)) {
            tools.add("UseSkillTool");
        }
        if (StrategyCodes.HUNTER_SHOOT_PHASE.equals(normalizedPhase) && StrategyCodes.HUNTER.equals(normalizedRole)) {
            tools.add("UseSkillTool");
        }
        return List.copyOf(tools);
    }
}
