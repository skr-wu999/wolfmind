package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 只读工具（tool-gateway.md §2.1、§8）：scope 隔离、不存在 {@code GOD}、
 * 历史查询的可见性过滤与上限、调用前后事件流长度不变。
 */
class ToolGatewayReadTest {

    private GatewayHarness harness;

    @BeforeEach
    void setUp() {
        harness = new GatewayHarness();
    }

    @Test
    @DisplayName("SELF：本座位的角色、技能与私有事实可见")
    void selfScopeShowsOwnIdentityAndFacts() {
        harness.driveTo(GamePhase.NIGHT_WITCH);
        int seer = harness.seatOfRole(Role.SEER);

        AgentView scoped = scopedState(harness.gateway(), harness.session(seer), "SELF");

        assertEquals("SEER", scoped.me().role(), "自己的角色必须可见");
        assertNotNull(scoped.me().skills(), "SELF 必须带技能状态");
        assertTrue(scoped.privateEvents().stream()
                        .anyMatch(fact -> fact.is(AgentView.PrivateFact.FactKind.SEER_INSPECT)),
                "预言家必须在 SELF 里看到自己的查验结果");
    }

    @Test
    @DisplayName("PUBLIC：不含任何角色、技能与私有事实")
    void publicScopeHidesPrivateData() {
        harness.driveTo(GamePhase.NIGHT_WITCH);
        int seer = harness.seatOfRole(Role.SEER);

        AgentView scoped = scopedState(harness.gateway(), harness.session(seer), "PUBLIC");

        assertNull(scoped.me().role(), "PUBLIC 不包含自己的角色");
        assertNull(scoped.me().skills(), "PUBLIC 不包含技能状态");
        assertTrue(scoped.privateEvents().isEmpty(), "PUBLIC 不含私有事实");
        assertTrue(scoped.other().stream().allMatch(seat -> seat.role() == null),
                "PUBLIC 里其他座位的角色必须为空");
        assertNotNull(scoped.phase(), "公开信息（阶段、轮次）仍然可用");
    }

    @Test
    @DisplayName("MY_CAMP：狼人看到队友阵营信息，村民看不到")
    void campScopeShowsTeammatesOnlyToWolves() {
        harness.driveTo(GamePhase.NIGHT_SEER);
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int villager = harness.seatOfRole(Role.VILLAGER);
        List<Integer> wolves = harness.state().players().stream()
                .filter(player -> player.role() == Role.WEREWOLF)
                .map(player -> player.seatNo().value())
                .toList();
        int teammate = wolves.stream().filter(seat -> seat != wolf).findFirst().orElseThrow();

        AgentView wolfScope = scopedState(harness.gateway(), harness.session(wolf), "MY_CAMP");
        assertTrue(wolfScope.privateEvents().stream()
                        .anyMatch(fact -> fact.is(AgentView.PrivateFact.FactKind.WOLF_TEAMMATE)
                                && fact.targetSeat() == teammate),
                "狼人必须在 MY_CAMP 里看到队友");
        assertEquals("WEREWOLF", wolfScope.seat(teammate).orElseThrow().role());

        AgentView villagerScope = scopedState(harness.gateway(), harness.session(villager), "MY_CAMP");
        assertTrue(villagerScope.privateEvents().stream()
                        .noneMatch(fact -> fact.is(AgentView.PrivateFact.FactKind.WOLF_TEAMMATE)),
                "村民不得看到任何狼队友事实");
        assertTrue(villagerScope.other().stream().allMatch(seat -> seat.role() == null),
                "村民的 MY_CAMP 不含任何其他座位角色");
    }

    @Test
    @DisplayName("不存在 GOD scope：必须被 G2 拒绝")
    void godScopeDoesNotExist() {
        int seat = harness.seatOfRole(Role.VILLAGER);
        ToolGatewayPort.ToolResult result = harness.gateway().queryGameState(harness.session(seat), "GOD");

        ToolGatewayPort.ToolResult.Denied denied = ToolGatewayWriteTest.assertDenied(result);
        assertEquals(DenialCodes.TOOL_ARGS_INVALID, denied.code());
        assertTrue(denied.message().contains("GOD"));
    }

    @Test
    @DisplayName("只读工具不产生事件：调用前后事件流长度不变")
    void readToolsHaveNoSideEffects() {
        int before = harness.eventCount();
        int seat = harness.seatOfRole(Role.WEREWOLF);

        ToolGatewayWriteTest.assertOk(harness.gateway().queryGameState(harness.session(seat), "SELF"));
        ToolGatewayWriteTest.assertOk(harness.gateway().queryGameState(harness.session(seat), "PUBLIC"));
        ToolGatewayWriteTest.assertOk(harness.gateway().queryGameState(harness.session(seat), "MY_CAMP"));
        ToolGatewayWriteTest.assertOk(harness.gateway().queryHistory(harness.session(seat), null, null, 50));

        assertEquals(before, harness.eventCount(), "只读工具不得改变对局事件流（C-4）");
    }

    @Test
    @DisplayName("QueryHistory：按可见性过滤（狼人见 WOLF 事件，村民见不到）")
    void historyIsFilteredByVisibility() {
        harness.driveTo(GamePhase.NIGHT_SEER);
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int villager = harness.seatOfRole(Role.VILLAGER);

        List<Map<String, Object>> wolfEvents =
                history(harness.gateway().queryHistory(harness.session(wolf), null, null, 100));
        List<Map<String, Object>> villagerEvents =
                history(harness.gateway().queryHistory(harness.session(villager), null, null, 100));

        assertTrue(types(wolfEvents).contains("WOLF_KILL_NOMINATED"), "狼人必须能看到狼刀提名（WOLF）");
        assertFalse(types(villagerEvents).contains("WOLF_KILL_NOMINATED"), "村民不得看到 WOLF 事件");
        assertFalse(types(villagerEvents).contains("PLAYER_KILLED"), "村民不得看到 JUDGE 事件");
        assertTrue(types(villagerEvents).contains("PHASE_CHANGED"), "公开事件对所有人可见");
        assertTrue(types(villagerEvents).contains("ROLE_ASSIGNED"), "本人 PRIVATE 事件可见");
        assertEquals(1, villagerEvents.stream()
                        .filter(event -> "ROLE_ASSIGNED".equals(event.get("type")))
                        .count(),
                "村民只能拿到自己的 ROLE_ASSIGNED");
    }

    @Test
    @DisplayName("QueryHistory：区间与 limit 都生效，limit 超上限被 G2 拒绝")
    void historyWindowAndLimit() {
        harness.driveTo(GamePhase.NIGHT_WITCH);
        int seat = harness.seatOfRole(Role.WEREWOLF);

        List<Map<String, Object>> windowed = history(
                harness.gateway().queryHistory(harness.session(seat), 1, 5, 100));
        assertFalse(windowed.isEmpty());
        assertTrue(windowed.stream().allMatch(event -> {
            int version = (Integer) event.get("version");
            return version >= 1 && version <= 5;
        }), "返回的事件必须落在请求区间内");

        List<Map<String, Object>> limited =
                history(harness.gateway().queryHistory(harness.session(seat), null, null, 3));
        assertTrue(limited.size() <= 3, "limit 必须生效，实际 " + limited.size());

        ToolGatewayPort.ToolResult.Denied denied = ToolGatewayWriteTest.assertDenied(
                harness.gateway().queryHistory(harness.session(seat), null, null, 101));
        assertEquals(DenialCodes.TOOL_ARGS_INVALID, denied.code());
        ToolGatewayWriteTest.assertDenied(harness.gateway().queryHistory(harness.session(seat), null, null, 0));
    }

    @Test
    @DisplayName("只读工具也受身份与目录约束：幽灵座位与未注册工具都拒绝")
    void readToolsStillCheckIdentity() {
        int seat = harness.seatOfRole(Role.VILLAGER);
        ToolGatewayWriteTest.assertOk(harness.gateway().queryGameState(harness.session(seat), "SELF"));

        com.wolfmind.application.agent.model.ToolSession ghost = new com.wolfmind.application.agent.model.ToolSession(
                GatewayHarness.GAME_ID, "p_99", 9, "VILLAGER", "DAY_DISCUSS", harness.version(),
                harness.time.nowMillis() + 10_000L, 6);
        ToolGatewayPort.ToolResult.Denied denied = ToolGatewayWriteTest.assertDenied(
                harness.gateway().queryGameState(ghost, "SELF"));
        assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, denied.code(),
                "会话身份不是本局座位时必须拒绝");

        ToolGatewayPort.ToolResult.Denied unknown = ToolGatewayWriteTest.assertDenied(
                harness.gateway().submit(harness.session(seat), "ShellTool", ActionProposal.skip("TEST")));
        assertEquals(DenialCodes.TOOL_UNKNOWN, unknown.code(), "目录之外没有工具（§13：不提供写事件/SQL 工具）");
    }

    @Test
    @DisplayName("发言内容只从本人的可见事件里取，不能被别的座位读到")
    void speechesAreScopedToVisibleEvents() {
        harness.driveTo(GamePhase.DAY_VOTE);
        int seat = harness.seatOfRole(Role.VILLAGER);

        AgentView view = scopedState(harness.gateway(), harness.session(seat), "SELF");
        assertTrue(view.speeches().stream().anyMatch(speech -> "驱动用发言".equals(speech.content())),
                "公开发言对参与者可见");
        assertFalse(view.speeches().stream().anyMatch(speech -> speech.phase() == null),
                "发言必须带上阶段");
    }

    @Test
    @DisplayName("只读调用同样消耗回合预算：第 7 次查询被 BUDGET_EXCEEDED 拒绝（§2.1、§11）")
    void readCallsConsumeTheTurnBudget() {
        ToolGatewayAdapter gateway = harness.gateway();
        int seat = harness.seatOfRole(Role.WEREWOLF);
        var session = harness.session(seat);
        int before = harness.eventCount();

        for (int call = 1; call <= ToolGatewayConfig.DEFAULT.maxToolCallsPerTurn(); call++) {
            ToolGatewayWriteTest.assertOk(gateway.queryGameState(session, "PUBLIC"));
        }
        ToolGatewayPort.ToolResult.Denied denied =
                ToolGatewayWriteTest.assertDenied(gateway.queryGameState(session, "PUBLIC"));

        assertEquals(DenialCodes.BUDGET_EXCEEDED, denied.code());
        assertEquals(before, harness.eventCount(), "预算拒绝不产生事件");
    }

    // ---------------------------------------------------------------- 辅助

    private static AgentView scopedState(ToolGatewayAdapter gateway, com.wolfmind.application.agent.model.ToolSession session, String scope) {
        return outcome(gateway.queryGameState(session, scope)).view();
    }

    private static ToolGatewayPort.QueryOutcome outcome(ToolGatewayPort.ToolResult result) {
        ToolGatewayPort.ToolResult.Ok ok = ToolGatewayWriteTest.assertOk(result);
        Object value = ok.payload().get(QueryGameStateTool.KEY_QUERY);
        assertNotNull(value, "只读结果必须带 QueryOutcome");
        return (ToolGatewayPort.QueryOutcome) value;
    }

    private static List<Map<String, Object>> history(ToolGatewayPort.ToolResult result) {
        return outcome(result).events();
    }

    private static java.util.Set<String> types(List<Map<String, Object>> events) {
        return events.stream().map(event -> String.valueOf(event.get("type")))
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
