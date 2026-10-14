package com.wolfmind.infrastructure.agent;

import com.wolfmind.domain.game.model.GamePhase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限矩阵（tool-gateway.md §3 + §2.2）：逐格覆盖 + 与领域枚举交叉校验 + 纯查表可并发。
 *
 * <p>"只有一份矩阵"这条纪律的可测形式就是本类：{@code availableTools} 的每个格子与
 * 期望集合逐一比对，任何"顺手加个 if"都会让某一格与文档不符而被这里打回。
 */
class RolePhaseMatrixTest {

    private static final List<String> ALL_ROLES =
            List.of("WEREWOLF", "SEER", "WITCH", "HUNTER", "VILLAGER");

    private static final List<String> READ_TOOLS =
            List.of(ToolCatalog.QUERY_GAME_STATE, ToolCatalog.QUERY_HISTORY);

    @Test
    @DisplayName("§3 逐格：查询类工具对所有角色、所有阶段开放")
    void queryToolsAreOpenToEveryRoleAndPhase() {
        for (String role : ALL_ROLES) {
            for (GamePhase phase : GamePhase.values()) {
                List<String> tools = RolePhaseMatrix.toolsFor(role, phase.name());
                assertTrue(tools.containsAll(READ_TOOLS),
                        role + " 在 " + phase + " 应可用查询类工具，实际 " + tools);
            }
        }
    }

    @Test
    @DisplayName("§3 逐格：发言只在讨论与遗言阶段，投票只在投票阶段")
    void speakAndVotePhases() {
        for (String role : ALL_ROLES) {
            assertEquals(List.of(ToolCatalog.SPEAK),
                    without(RolePhaseMatrix.toolsFor(role, "DAY_DISCUSS"), READ_TOOLS),
                    role + " 在 DAY_DISCUSS 只应多出发言工具");
            assertEquals(List.of(ToolCatalog.SPEAK),
                    without(RolePhaseMatrix.toolsFor(role, "LAST_WORDS"), READ_TOOLS),
                    role + " 在 LAST_WORDS 只应多出发言工具");
            assertEquals(List.of(ToolCatalog.VOTE),
                    without(RolePhaseMatrix.toolsFor(role, "DAY_VOTE"), READ_TOOLS),
                    role + " 在 DAY_VOTE 只应多出投票工具");
            for (GamePhase phase : List.of(GamePhase.NIGHT_WOLF, GamePhase.NIGHT_SEER, GamePhase.NIGHT_WITCH,
                    GamePhase.HUNTER_SHOOT, GamePhase.NIGHT_SETTLE, GamePhase.DAY_ANNOUNCE,
                    GamePhase.VOTE_SETTLE, GamePhase.GAME_OVER, GamePhase.GAME_INIT)) {
                assertFalse(RolePhaseMatrix.toolsFor(role, phase.name()).contains(ToolCatalog.SPEAK),
                        role + " 在 " + phase + " 不应能发言");
                assertFalse(RolePhaseMatrix.toolsFor(role, phase.name()).contains(ToolCatalog.VOTE),
                        role + " 在 " + phase + " 不应能投票");
            }
        }
    }

    @Test
    @DisplayName("§3 逐格：狼刀只有狼人、且只在 NIGHT_WOLF")
    void killToolIsWolfOnly() {
        assertEquals(List.of(ToolCatalog.KILL),
                without(RolePhaseMatrix.toolsFor("WEREWOLF", "NIGHT_WOLF"), READ_TOOLS));
        for (String role : List.of("SEER", "WITCH", "HUNTER", "VILLAGER")) {
            for (GamePhase phase : GamePhase.values()) {
                assertFalse(RolePhaseMatrix.toolsFor(role, phase.name()).contains(ToolCatalog.KILL),
                        role + " 不应能使用狼刀工具（阶段 " + phase + "）");
            }
        }
        assertFalse(RolePhaseMatrix.toolsFor("WEREWOLF", "DAY_DISCUSS").contains(ToolCatalog.KILL),
                "狼人在白天不能使用狼刀工具");
    }

    @Test
    @DisplayName("§3 逐格：UseSkillTool 只对预言家/女巫/猎人在各自阶段开放")
    void skillToolFollowsRolePhaseMatrix() {
        assertEquals(List.of(ToolCatalog.SKILL),
                without(RolePhaseMatrix.toolsFor("SEER", "NIGHT_SEER"), READ_TOOLS));
        assertEquals(List.of(ToolCatalog.SKILL),
                without(RolePhaseMatrix.toolsFor("WITCH", "NIGHT_WITCH"), READ_TOOLS));
        assertEquals(List.of(ToolCatalog.SKILL),
                without(RolePhaseMatrix.toolsFor("HUNTER", "HUNTER_SHOOT"), READ_TOOLS));

        assertFalse(RolePhaseMatrix.toolsFor("SEER", "NIGHT_WITCH").contains(ToolCatalog.SKILL));
        assertFalse(RolePhaseMatrix.toolsFor("WITCH", "NIGHT_SEER").contains(ToolCatalog.SKILL));
        assertFalse(RolePhaseMatrix.toolsFor("HUNTER", "DAY_DISCUSS").contains(ToolCatalog.SKILL));
        for (GamePhase phase : GamePhase.values()) {
            assertFalse(RolePhaseMatrix.toolsFor("VILLAGER", phase.name()).contains(ToolCatalog.SKILL),
                    "村民永远没有技能工具（阶段 " + phase + "）");
        }
    }

    @Test
    @DisplayName("§2.2 动作集合：每个角色在各自阶段的可提交动作逐项一致（只返回主名）")
    void allowedActionTypesPerRoleAndPhase() {
        assertEquals(List.of("WOLF_KILL", "SKIP"),
                RolePhaseMatrix.actionTypesFor("WEREWOLF", "NIGHT_WOLF"));
        assertEquals(List.of("SEER_INSPECT", "SKIP"),
                RolePhaseMatrix.actionTypesFor("SEER", "NIGHT_SEER"));
        assertEquals(List.of("WITCH_SAVE", "WITCH_POISON", "WITCH_PASS", "SKIP"),
                RolePhaseMatrix.actionTypesFor("WITCH", "NIGHT_WITCH"));
        assertEquals(List.of("HUNTER_SHOOT", "SKIP"),
                RolePhaseMatrix.actionTypesFor("HUNTER", "HUNTER_SHOOT"));
        for (String role : ALL_ROLES) {
            assertEquals(List.of("SPEAK", "SKIP"), RolePhaseMatrix.actionTypesFor(role, "DAY_DISCUSS"));
            assertEquals(List.of("SPEAK", "SKIP"), RolePhaseMatrix.actionTypesFor(role, "LAST_WORDS"));
            assertEquals(List.of("VOTE", "SKIP"), RolePhaseMatrix.actionTypesFor(role, "DAY_VOTE"));
        }
    }

    @Test
    @DisplayName("动作词汇表封闭：allowedActionTypes 只出现主名，§2.2 技能名只作为入站别名被接受")
    void actionVocabularyIsCanonical() {
        List<String> canonical = List.of(
                "SPEAK", "VOTE", "WOLF_KILL", "SEER_INSPECT", "WITCH_SAVE", "WITCH_POISON",
                "WITCH_PASS", "HUNTER_SHOOT", "SKIP");
        Set<String> aliases = Set.of("SEER_SKIP", "HUNTER_GIVE_UP", "HUNTER_SKIP");

        for (String role : ALL_ROLES) {
            for (GamePhase phase : GamePhase.values()) {
                for (String action : RolePhaseMatrix.actionTypesFor(role, phase.name())) {
                    assertTrue(canonical.contains(action),
                            role + "@" + phase + " 的候选动作必须是主名，实际 " + action);
                    assertFalse(aliases.contains(action),
                            role + "@" + phase + " 不得把工具层别名 " + action + " 放进候选集");
                }
            }
        }
        // 别名仍然能被网关接受（入站桥接），只是不作为候选公布
        assertEquals("SKIP", ToolCatalog.canonicalActionType("SEER_SKIP"));
        assertEquals("HUNTER_SHOOT", ToolCatalog.canonicalActionType("HUNTER_GIVE_UP"));
        assertEquals("HUNTER_SHOOT", ToolCatalog.canonicalActionType("HUNTER_SKIP"));
        assertEquals("VOTE", ToolCatalog.canonicalActionType("VOTE"));
        assertEquals("SKIP", ToolCatalog.effectiveActionType("SEER_SKIP", false));
        assertEquals("HUNTER_SHOOT", ToolCatalog.effectiveActionType("HUNTER_GIVE_UP", false));
    }

    @Test
    @DisplayName("结算与终态阶段不接受任何动作；观战者没有任何工具")
    void settledPhasesAndSpectatorsHaveNothing() {
        for (String phase : List.of(
                "NIGHT_SETTLE", "DAY_ANNOUNCE", "VOTE_SETTLE", "GAME_OVER", "GAME_INIT")) {
            for (String role : ALL_ROLES) {
                assertEquals(List.of(), RolePhaseMatrix.actionTypesFor(role, phase),
                        role + " 在 " + phase + " 不应有可提交动作");
            }
        }
        for (String role : List.of("SPECTATOR", "UNKNOWN", "")) {
            assertEquals(List.of(), RolePhaseMatrix.toolsFor(role, "DAY_DISCUSS"),
                    "未登记角色不应有任何工具：" + role);
            assertEquals(List.of(), RolePhaseMatrix.actionTypesFor(role, "DAY_VOTE"),
                    "未登记角色不应有任何动作：" + role);
        }
    }

    @Test
    @DisplayName("兜底 SKIP：任意角色在可行动阶段都能提交，非行动阶段不能")
    void skipIsUniversalOnlyInsideActionPhases() {
        for (GamePhase phase : GamePhase.values()) {
            for (String role : ALL_ROLES) {
                boolean expected = phase.acceptsActions();
                assertEquals(expected, RolePhaseMatrix.actionTypesFor(role, phase.name()).contains("SKIP"),
                        role + " 在 " + phase + " 的 SKIP 可用性应与领域一致");
            }
        }
        assertEquals("SKIP", ToolCatalog.canonicalActionType("SEER_SKIP"),
                "预言家的跳过在候选集里就是 SKIP（动作词汇表只有主名）");
    }

    @Test
    @DisplayName("矩阵里的阶段名与回合窗口必须与领域枚举一致（不是第二份真相）")
    void matrixMirrorsDomainPhaseSemantics() {
        List<String> phases = new ArrayList<>();
        for (RolePhaseMatrix.Grant grant : RolePhaseMatrix.grants()) {
            for (String phase : grant.phases()) {
                if (!RolePhaseMatrix.ANY_PHASE.equals(phase) && !phases.contains(phase)) {
                    phases.add(phase);
                }
            }
        }
        assertFalse(phases.isEmpty(), "矩阵必须声明具体阶段");
        for (String phase : phases) {
            GamePhase domain = GamePhase.valueOf(phase);
            assertEquals(domain.acceptsActions(), RolePhaseMatrix.acceptsActions(phase),
                    phase + " 的 acceptsActions 与领域不一致");
            assertEquals(domain.grantsTurn(), RolePhaseMatrix.requiresTurnWindow(phase),
                    phase + " 的回合窗口语义与领域不一致");
        }
        assertEquals(Set.copyOf(phases), Set.copyOf(RolePhaseMatrix.ACTION_PHASES),
                "ACTION_PHASES 必须与矩阵里出现的阶段一致");
    }

    @Test
    @DisplayName("纯查表：并发调用结果一致、无异常、无副作用")
    void lookupsArePureAndConcurrencySafe() throws Exception {
        List<String> before = RolePhaseMatrix.toolsFor("WITCH", "NIGHT_WITCH");
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                tasks.add(() -> {
                    for (int round = 0; round < 200; round++) {
                        if (!RolePhaseMatrix.toolsFor("WITCH", "NIGHT_WITCH").equals(before)) {
                            return false;
                        }
                        if (!RolePhaseMatrix.actionTypesFor("SEER", "NIGHT_SEER")
                                .equals(List.of("SEER_INSPECT", "SKIP"))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            for (Future<Boolean> future : pool.invokeAll(tasks)) {
                assertTrue(future.get(5, TimeUnit.SECONDS), "并发查表结果必须稳定");
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(before, RolePhaseMatrix.toolsFor("WITCH", "NIGHT_WITCH"),
                "查表不得修改矩阵：它是策略层与网关共用的只读数据");
    }

    private static List<String> without(List<String> tools, List<String> excluded) {
        return tools.stream().filter(tool -> !excluded.contains(tool)).toList();
    }
}
