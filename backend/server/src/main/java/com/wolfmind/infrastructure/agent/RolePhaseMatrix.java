package com.wolfmind.infrastructure.agent;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 权限矩阵（tool-gateway.md §3 + §2.2 的技能矩阵）：<b>本类是全工程唯一的一份矩阵数据</b>。
 *
 * <p>"只有一份"是硬要求：{@code availableTools}（进 prompt 的白名单）、{@code allowedActionTypes}
 * （策略层候选生成）、G5（阶段门）、G7（角色门）、G8（次数门）、G9/G10（目标与资源门）
 * 全部由下面这张 {@link Grant} 列表派生。任何"顺手再写一个 if"都会让提示词与网关各说一套，
 * 那是 tool-gateway.md §7 约束 1 与 strategy-layer.md §7 约束 1 明确禁止的。
 *
 * <p>阶段与角色用字符串表达：{@code application.agent} 与 Agent 侧基础设施不能引用
 * {@code domain.game.model}（DEP-7、C-5），而 {@code GamePhase} / {@code Role} 的
 * {@code name()} 就是这套字符串。这份镜像由 {@code RolePhaseMatrixTest} 与领域枚举交叉校验：
 * 阶段名必须都是 {@code GamePhase} 的取值，"需要回合窗口"的阶段必须与
 * {@code GamePhase.grantsTurn()} 完全一致，因此它不是第二份真相。
 *
 * <p>线程安全：{@code GRANTS} 与派生结构都是不可变集合，所有查询方法都是纯查表、无副作用，
 * 可被策略层与 Runtime 并发调用。
 */
public final class RolePhaseMatrix {

    /** 任意阶段（查询类工具不受阶段限制）。 */
    public static final String ANY_PHASE = "*";

    /** 工具作用域：被授权角色（标准九人局的五个角色；观战者不在其中）。 */
    public static final Set<String> ROLES = Set.of("WEREWOLF", "SEER", "WITCH", "HUNTER", "VILLAGER");

    /** 接受玩家行动的阶段（与 {@code GamePhase.acceptsActions()} 一致，不含结算与终态）。 */
    public static final Set<String> ACTION_PHASES = Set.of(
            "NIGHT_WOLF", "NIGHT_SEER", "NIGHT_WITCH", "LAST_WORDS", "DAY_DISCUSS", "DAY_VOTE",
            "HUNTER_SHOOT");

    /** 需要回合窗口的阶段（与 {@code GamePhase.grantsTurn()} 一致）。 */
    public static final Set<String> TURN_PHASES = Set.of("LAST_WORDS", "DAY_DISCUSS", "HUNTER_SHOOT");

    /** 技能次数配额。 */
    public enum Quota {
        /** 无次数限制（提交次数由回合窗口与领域的 V6 保证）。 */
        NONE,
        /** 每夜 1 次。 */
        PER_NIGHT,
        /** 全局 1 次。 */
        PER_GAME
    }

    /** 目标约束（tool-gateway.md §2.2 的"目标约束"列、§4 G9）。 */
    public enum TargetRule {
        /** 无目标。 */
        NONE,
        /** 存活、非自身。 */
        ALIVE_OTHER,
        /** 存活、非自身、非队友（狼刀 INV-6）。 */
        ALIVE_NON_TEAMMATE,
        /** 女巫解药：存活、非自身（{@code witchSelfSave=true} 时允许自救）。 */
        WITCH_SAVE
    }

    /**
     * 一条授权：把"工具 × 角色 × 阶段"与"领域动作类型、技能名、次数、目标约束、是否需要回合"绑在一起。
     *
     * @param tool        工具名；{@link ToolCatalog#SKIP_CARRIER} 表示"兜底动作，可由任意已注册工具承载"
     * @param actionType  领域动作类型（api-spec.md §5.3）；查询类工具为 {@code null}
     * @param skill       §2.2 的技能名（仅 {@code UseSkillTool}）；其余为 {@code null}
     * @param roles       允许的角色
     * @param phases      允许的阶段；{@link #ANY_PHASE} 表示任意阶段
     * @param requiresTurn 是否需要 {@code currentTurn} 属于本人（G6）
     * @param quota       次数配额（G8）
     * @param target      目标约束（G9）
     */
    public record Grant(
            String tool,
            String actionType,
            String skill,
            Set<String> roles,
            Set<String> phases,
            boolean requiresTurn,
            Quota quota,
            TargetRule target) {

        public Grant {
            roles = Set.copyOf(roles);
            phases = Set.copyOf(phases);
        }

        boolean coversPhase(String phase) {
            return phases.contains(ANY_PHASE) || phases.contains(phase);
        }

        boolean readOnly() {
            return actionType == null;
        }
    }

    private static final List<Grant> GRANTS = build();

    private RolePhaseMatrix() {}

    private static List<Grant> build() {
        List<Grant> grants = new ArrayList<>();

        // §3 第 1、2 行：查询类工具对所有角色、所有阶段开放（对局不在 RUNNING 由 G4 拒绝）。
        grants.add(new Grant(ToolCatalog.QUERY_GAME_STATE, null, null, ROLES, Set.of(ANY_PHASE),
                false, Quota.NONE, TargetRule.NONE));
        grants.add(new Grant(ToolCatalog.QUERY_HISTORY, null, null, ROLES, Set.of(ANY_PHASE),
                false, Quota.NONE, TargetRule.NONE));

        // §3 第 3 行：发言只在讨论与遗言阶段，且必须轮到自己。
        grants.add(new Grant(ToolCatalog.SPEAK, "SPEAK", null, ROLES,
                Set.of("DAY_DISCUSS", "LAST_WORDS"), true, Quota.NONE, TargetRule.NONE));

        // §3 第 4 行：投票在 DAY_VOTE，同时提交、无回合窗口。
        grants.add(new Grant(ToolCatalog.VOTE, "VOTE", null, ROLES,
                Set.of("DAY_VOTE"), false, Quota.NONE, TargetRule.ALIVE_OTHER));

        // §3 第 5 行：狼刀只有狼人在 NIGHT_WOLF，目标不得是队友（INV-6）。
        grants.add(new Grant(ToolCatalog.KILL, "WOLF_KILL", null, Set.of("WEREWOLF"),
                Set.of("NIGHT_WOLF"), false, Quota.NONE, TargetRule.ALIVE_NON_TEAMMATE));

        // §2.2：UseSkillTool 的七个技能。HUNTER_SHOOT 阶段发放回合窗口（domain-model.md §4），
        // 因此 requiresTurn=true；女巫与预言家的夜间阶段是同时提交，无回合窗口。
        grants.add(new Grant(ToolCatalog.SKILL, "SEER_INSPECT", "SEER_INSPECT", Set.of("SEER"),
                Set.of("NIGHT_SEER"), false, Quota.PER_NIGHT, TargetRule.ALIVE_OTHER));
        grants.add(new Grant(ToolCatalog.SKILL, "SEER_SKIP", "SEER_SKIP", Set.of("SEER"),
                Set.of("NIGHT_SEER"), false, Quota.PER_NIGHT, TargetRule.NONE));
        grants.add(new Grant(ToolCatalog.SKILL, "WITCH_SAVE", "WITCH_SAVE", Set.of("WITCH"),
                Set.of("NIGHT_WITCH"), false, Quota.PER_GAME, TargetRule.WITCH_SAVE));
        grants.add(new Grant(ToolCatalog.SKILL, "WITCH_POISON", "WITCH_POISON", Set.of("WITCH"),
                Set.of("NIGHT_WITCH"), false, Quota.PER_GAME, TargetRule.ALIVE_OTHER));
        grants.add(new Grant(ToolCatalog.SKILL, "WITCH_PASS", "WITCH_PASS", Set.of("WITCH"),
                Set.of("NIGHT_WITCH"), false, Quota.PER_NIGHT, TargetRule.NONE));
        grants.add(new Grant(ToolCatalog.SKILL, "HUNTER_SHOOT", "HUNTER_SHOOT", Set.of("HUNTER"),
                Set.of("HUNTER_SHOOT"), true, Quota.PER_GAME, TargetRule.ALIVE_OTHER));
        grants.add(new Grant(ToolCatalog.SKILL, "HUNTER_GIVE_UP", "HUNTER_GIVE_UP", Set.of("HUNTER"),
                Set.of("HUNTER_SHOOT"), true, Quota.PER_GAME, TargetRule.NONE));

        // 兜底 SKIP（agent-runtime.md §10）：任何"本阶段还要提交点什么"的角色都能跳过，
        // 与承载它的工具解耦（Runtime 固定用 SpeakTool 提交 SKIP），因此 tool 用载体通配符。
        grants.add(new Grant(ToolCatalog.SKIP_CARRIER, "SKIP", null, ROLES, ACTION_PHASES,
                false, Quota.NONE, TargetRule.NONE));

        return List.copyOf(grants);
    }

    /** 全量授权（只读视图），供契约测试逐格核对。 */
    public static List<Grant> grants() {
        return GRANTS;
    }

    /** 本回合该角色该阶段可用的工具白名单（进 prompt，同时是 G5 的判据）。 */
    public static List<String> toolsFor(String role, String phase) {
        Set<String> tools = new LinkedHashSet<>();
        for (Grant grant : GRANTS) {
            // 兜底动作的载体通配符不是工具名，不进白名单
            if (grant.coversPhase(phase) && grant.roles().contains(role)
                    && !ToolCatalog.SKIP_CARRIER.equals(grant.tool())) {
                tools.add(grant.tool());
            }
        }
        return List.copyOf(tools);
    }

    /**
     * 该角色该阶段允许提交的动作类型集合。
     *
     * <p><b>只返回主名</b>（api-spec.md §5.3 的 {@code SPEAK} / {@code VOTE} / {@code WOLF_KILL} /
     * {@code SEER_INSPECT} / {@code WITCH_SAVE} / {@code WITCH_POISON} / {@code WITCH_PASS} /
     * {@code HUNTER_SHOOT} / {@code SKIP}）：这是 {@code ActionProposal.actionType} 的词汇表，
     * 策略层的候选生成与 OutputValidator 共用它。tool-gateway.md §2.2 的技能名
     * （{@code SEER_SKIP} / {@code HUNTER_GIVE_UP}）属于工具层内部细节：网关仍接受它们入站
     * （{@link ToolCatalog#canonicalActionType} 桥接），但不会出现在候选集里，
     * 避免同一件事在候选集与提案里各有一种写法。
     *
     * <p>纯查表、无副作用：策略层的候选生成与 OutputValidator 共用它，
     * 因此它必须可以在多线程下随意调用。
     */
    public static List<String> actionTypesFor(String role, String phase) {
        Set<String> types = new LinkedHashSet<>();
        for (Grant grant : GRANTS) {
            if (grant.readOnly() || !grant.coversPhase(phase) || !grant.roles().contains(role)) {
                continue;
            }
            types.add(ToolCatalog.canonicalActionType(grant.actionType()));
        }
        return List.copyOf(types);
    }

    /** 该角色该阶段该动作的全部授权（一个动作可能有多个技能变体，例如女巫的三种用药）。 */
    public static List<Grant> grantsFor(String role, String phase, String actionType) {
        List<Grant> matches = new ArrayList<>();
        for (Grant grant : GRANTS) {
            if (actionType != null && actionType.equals(grant.actionType())
                    && grant.coversPhase(phase) && grant.roles().contains(role)) {
                matches.add(grant);
            }
        }
        return List.copyOf(matches);
    }

    /**
     * G5：工具名在该角色该阶段是否被授权。
     *
     * <p>兜底动作 {@code SKIP} 与承载它的工具解耦（Runtime 固定用 {@code SpeakTool} 提交它，
     * 但夜里没有 SpeakTool 授权），因此只有"声明的动作确实是 SKIP"时才允许任意已注册工具承载；
     * 否则 {@code SpeakTool + SPEAK} 在夜里会绕过 G5，把阶段门挪到 G7 才生效。
     */
    public static boolean toolAllowed(String role, String phase, String tool, String actionType) {
        for (Grant grant : GRANTS) {
            if (!grant.coversPhase(phase) || !grant.roles().contains(role)) {
                continue;
            }
            if (tool != null && tool.equals(grant.tool())) {
                return true;
            }
            if (ToolCatalog.SKIP_CARRIER.equals(grant.tool())
                    && "SKIP".equals(actionType)
                    && ToolCatalog.isRegistered(tool)) {
                return true;
            }
        }
        return false;
    }

    /** G7：动作类型在该角色该阶段是否被授权。 */
    public static boolean actionAllowed(String role, String phase, String actionType) {
        return !grantsFor(role, phase, actionType).isEmpty();
    }

    /** 该阶段是否接受任何行动（{@code GamePhase.acceptsActions()} 的 Agent 侧镜像）。 */
    public static boolean acceptsActions(String phase) {
        return ACTION_PHASES.contains(phase);
    }

    /** 该阶段是否需要回合窗口（G6）。 */
    public static boolean requiresTurnWindow(String phase) {
        return TURN_PHASES.contains(phase);
    }

    /** §2.2 的技能授权；未知技能名返回空。 */
    public static List<Grant> grantsForSkill(String skill) {
        List<Grant> matches = new ArrayList<>();
        for (Grant grant : GRANTS) {
            if (grant.skill() != null && grant.skill().equals(skill)) {
                matches.add(grant);
            }
        }
        return List.copyOf(matches);
    }

    /** 动作类型是否属于"角色能力"动作（狼刀、查验、用药、开枪）；发言/投票/跳过为通用动作。 */
    public static boolean isCapabilityAction(String actionType) {
        for (Grant grant : GRANTS) {
            if (actionType != null && actionType.equals(grant.actionType()) && grant.skill() != null) {
                return true;
            }
        }
        return false;
    }
}
