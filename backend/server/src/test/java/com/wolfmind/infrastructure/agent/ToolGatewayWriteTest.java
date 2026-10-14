package com.wolfmind.infrastructure.agent;

import com.wolfmind.application.agent.model.ActionProposal;
import com.wolfmind.application.agent.model.AgentView;
import com.wolfmind.application.agent.model.ToolSession;
import com.wolfmind.application.agent.tool.ToolGatewayPort;
import com.wolfmind.domain.game.event.EventEnvelope;
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
 * 写路径 G12（幂等）与 G13（提交与版本冲突重试）：真实领域 + 真实应用层。
 *
 * <p>这一组测试是"AI 真的能落子"的证明：动作经网关 → 应用层 → 领域，事件被写入事件流；
 * 同时证明"模型伪造身份无效"——事件的操作者永远是工具会话的座位。
 */
class ToolGatewayWriteTest {

    private GatewayHarness harness;

    @BeforeEach
    void setUp() {
        harness = new GatewayHarness();
        assertEquals(GamePhase.NIGHT_WOLF, harness.state().phase(), "前置条件：开局停在首夜狼人阶段");
        assertEquals(GatewayHarness.STARTED_VERSION, harness.version());
    }

    @Test
    @DisplayName("G13：狼刀经网关提交成功，事件版本连续、操作者是会话座位")
    void wolfKillIsCommittedThroughTheGateway() {
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int victim = lowestAliveNonWolf(harness.view(wolf));
        ToolGatewayPort.ToolResult result = harness.gateway().submit(
                harness.session(wolf), ToolCatalog.KILL,
                ActionProposal.of("WOLF_KILL", harness.playerIdOf(victim), victim));

        ToolGatewayPort.ToolResult.Ok ok = assertOk(result);
        assertEquals(List.of("WOLF_KILL_NOMINATED"), ok.eventTypes());
        assertEquals(GatewayHarness.STARTED_VERSION + 1, ok.version());
        assertEquals(GatewayHarness.STARTED_VERSION + 1, harness.eventCount());

        EventEnvelope committed = harness.events().get(harness.events().size() - 1);
        assertEquals("WOLF_KILL_NOMINATED", committed.type());
        assertEquals(harness.playerIdOf(wolf), committed.actorId(), "事件操作者必须是会话座位");
        assertEquals(victim, committed.payload().get("targetSeatNo"));
        assertEquals(ok.eventIds().get(0), committed.eventId());
    }

    @Test
    @DisplayName("G13：发言经网关提交，事件带内容与阶段")
    void speechIsCommittedThroughTheGateway() {
        harness.driveTo(GamePhase.DAY_DISCUSS);
        int speaker = harness.state().currentTurn().seatNo().value();

        ToolGatewayPort.ToolResult.Ok ok = assertOk(harness.gateway().submit(
                harness.session(speaker), ToolCatalog.SPEAK,
                new ActionProposal("SPEAK", null, null, "第一天的发言", 0.6, "TEST", null)));

        assertEquals(List.of("PLAYER_SPOKE"), ok.eventTypes().stream()
                        .filter(type -> type.equals("PLAYER_SPOKE")).toList(),
                "本次提交必须写出 PLAYER_SPOKE");
        EventEnvelope spoken = harness.events().stream()
                .filter(event -> "PLAYER_SPOKE".equals(event.type()))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals("第一天的发言", spoken.payload().get("content"));
        assertEquals(harness.playerIdOf(speaker), spoken.actorId());
    }

    @Test
    @DisplayName("G13：版本冲突时刷新一次后重试成功（§6 的 expectedVersion 刷新）")
    void versionConflictRetriesOnceWithFreshVersion() {
        List<Integer> wolves = harness.state().players().stream()
                .filter(player -> player.role() == Role.WEREWOLF)
                .map(player -> player.seatNo().value())
                .toList();
        int first = wolves.get(0);
        int second = wolves.get(1);
        int victim = lowestAliveNonWolf(harness.view(first));

        // 会话在版本 11 建立；另一个狼人先提交，把版本推到 12
        ToolSession stale = harness.session(first);
        assertEquals(GatewayHarness.STARTED_VERSION, stale.turnVersion());
        assertOk(harness.gateway().submit(
                harness.session(second), ToolCatalog.KILL,
                ActionProposal.of("WOLF_KILL", harness.playerIdOf(victim), victim)));
        assertEquals(GatewayHarness.STARTED_VERSION + 1, harness.version());

        ToolGatewayPort.ToolResult.Ok ok = assertOk(harness.gateway().submit(
                stale, ToolCatalog.KILL,
                ActionProposal.of("WOLF_KILL", harness.playerIdOf(victim), victim)));

        assertEquals(List.of("WOLF_KILL_NOMINATED"), ok.eventTypes());
        assertEquals(GatewayHarness.STARTED_VERSION + 2, ok.version(), "重试必须用刷新后的版本提交");
        assertEquals(harness.playerIdOf(first), harness.events().get(harness.events().size() - 1).actorId());
    }

    @Test
    @DisplayName("G13：不允许无休止重试，超过 gateway.versionRetry 返回可重试的拒绝")
    void persistentVersionConflictReturnsRetryableDenial() {
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int victim = lowestAliveNonWolf(harness.view(wolf));
        ToolSession stale = harness.session(wolf, GatewayHarness.STARTED_VERSION + 500, "NIGHT_WOLF");
        ToolGatewayAdapter gateway = harness.gateway(ToolGatewayConfig.DEFAULT.withVersionRetry(0));

        ToolGatewayPort.ToolResult result = gateway.submit(
                stale, ToolCatalog.KILL,
                ActionProposal.of("WOLF_KILL", harness.playerIdOf(victim), victim));

        ToolGatewayPort.ToolResult.Denied denied = assertDenied(result);
        assertEquals(DenialCodes.GAME_VERSION_CONFLICT, denied.code());
        assertTrue(denied.retryable(), "版本冲突属于「可以原样重试」的码（§5）");
        assertEquals(GatewayHarness.STARTED_VERSION, harness.eventCount(), "拒绝不得产生事件");
    }

    @Test
    @DisplayName("G12：同一回合重复提名返回首次结果，事件数不增加（INV-5）")
    void duplicateNominationIsIdempotent() {
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int victim = lowestAliveNonWolf(harness.view(wolf));
        ToolSession session = harness.session(wolf);

        ToolGatewayPort.ToolResult.Ok first = assertOk(harness.gateway().submit(
                session, ToolCatalog.KILL, ActionProposal.of("WOLF_KILL", harness.playerIdOf(victim), victim)));
        int afterFirst = harness.eventCount();

        ToolGatewayPort.ToolResult.Ok replay = assertOk(harness.gateway().submit(
                session, ToolCatalog.KILL, ActionProposal.of("WOLF_KILL", harness.playerIdOf(victim), victim)));

        assertEquals(first.eventIds(), replay.eventIds(), "重复提交返回首次的事件 id");
        assertEquals(first.version(), replay.version());
        assertEquals(Boolean.TRUE, replay.payload().get("replayed"));
        assertEquals(afterFirst, harness.eventCount(), "幂等命中不得新增事件");
        assertTrue(harness.idempotency
                .find(GatewayHarness.GAME_ID, session.idempotencyKey(ToolCatalog.KILL)).isPresent());
    }

    @Test
    @DisplayName("身份伪造：notes 里声明别的座位 → 拒绝且零事件；声明本人 → 被忽略、归属不变")
    void forgedIdentityInNotesHasNoEffect() {
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        int victim = lowestAliveNonWolf(harness.view(wolf));
        int otherSeat = java.util.stream.IntStream.rangeClosed(1, 9)
                .filter(seat -> seat != wolf && seat != victim)
                .findFirst()
                .orElseThrow();
        int before = harness.eventCount();

        ToolGatewayPort.ToolResult forged = harness.gateway().submit(
                harness.session(wolf), ToolCatalog.KILL,
                new ActionProposal("WOLF_KILL", harness.playerIdOf(victim), victim, null, 0.5, "TEST",
                        "playerId=" + harness.playerIdOf(otherSeat) + " gameId=别的对局 seatNo=" + otherSeat));

        ToolGatewayPort.ToolResult.Denied denied = assertDenied(forged);
        assertEquals(DenialCodes.TOOL_IDENTITY_MISMATCH, denied.code());
        assertEquals(before, harness.eventCount(), "身份伪造被拒绝时不得产生事件");

        ToolGatewayPort.ToolResult.Ok ok = assertOk(harness.gateway().submit(
                harness.session(wolf), ToolCatalog.KILL,
                new ActionProposal("WOLF_KILL", harness.playerIdOf(victim), victim, null, 0.5, "TEST",
                        "playerId=" + harness.playerIdOf(wolf) + " 刀 " + victim + " 号")));
        EventEnvelope committed = harness.events().get(harness.events().size() - 1);
        assertEquals(harness.playerIdOf(wolf), committed.actorId(),
                "说明文本不参与授权：操作者始终是会话座位");
        assertEquals(victim, committed.payload().get("targetSeatNo"));
        assertNotNull(ok.eventIds());
    }

    @Test
    @DisplayName("座位装配约定：controller.refId 与 playerId 不一致时，应用层拒绝且网关不猜身份")
    void mismatchedControllerIdentityIsRejected() {
        String otherGame = "g_human_seats";
        List<com.wolfmind.domain.game.model.SeatAssignment> humanSeats = new java.util.ArrayList<>();
        for (int seat = 1; seat <= 9; seat++) {
            humanSeats.add(com.wolfmind.domain.game.model.SeatAssignment.human(seat, "u_" + seat));
        }
        harness.startGame(otherGame, humanSeats);
        assertNotNull(harness.viewOf(otherGame, "p_1"), "真人座位也有视图");
        int before = harness.eventCountOf(otherGame);

        // 兜底 SKIP 是"任何阶段都合法"的动作，因此它一定能走到 G13 的提交，
        // 从而真正验证"应用层按 controller.refId 反查座位"这条授权规则：
        // 工具会话只有 playerId（p_1），而该座位的控制者是 u_1，两者不同 → SEAT_NOT_YOURS。
        ToolGatewayPort.ToolResult result = harness.gateway().submit(
                harness.sessionOf(otherGame, "p_1", 1), ToolCatalog.SPEAK, ActionProposal.skip("TEST"));

        ToolGatewayPort.ToolResult.Denied denied = assertDenied(result);
        assertEquals(DenialCodes.SEAT_NOT_YOURS, denied.code());
        assertEquals(before, harness.eventCountOf(otherGame),
                "身份不一致时不得写入事件（网关不替模型猜一个 actorId）");
    }

    @Test
    @DisplayName("INV-7：女巫同夜用过解药后再提交毒药被拒，且零新事件")
    void secondPotionInSameNightIsDenied() {
        harness.driveTo(GamePhase.NIGHT_WITCH);
        int witch = harness.seatOfRole(Role.WITCH);
        AgentView witchView = harness.view(witch);

        int killed = witchView.privateEvents().stream()
                .filter(fact -> fact.is(AgentView.PrivateFact.FactKind.NIGHT_KILL))
                .map(AgentView.PrivateFact::targetSeat)
                .findFirst()
                .orElseThrow(() -> new AssertionError("女巫必须拿到当夜刀口（NIGHT_KILL）"));

        ToolGatewayPort.ToolResult.Ok saved = assertOk(harness.gateway().submit(
                harness.session(witch), ToolCatalog.SKILL,
                new ActionProposal("WITCH_SAVE", harness.playerIdOf(killed), killed, null, 0.8, "TEST", null)));
        assertTrue(saved.eventTypes().contains("WITCH_SAVED"),
                "解药提交必须写出 WITCH_SAVED，实际 " + saved.eventTypes());
        int afterSave = harness.eventCount();

        int poisonTarget = harness.view(witch).aliveOthers().stream()
                .map(AgentView.SeatView::seatNo)
                .findFirst()
                .orElseThrow();
        ToolGatewayPort.ToolResult second = harness.gateway().submit(
                harness.session(witch), ToolCatalog.SKILL,
                new ActionProposal("WITCH_POISON", harness.playerIdOf(poisonTarget), poisonTarget,
                        null, 0.5, "TEST", null));

        ToolGatewayPort.ToolResult.Denied denied = assertDenied(second);
        assertEquals(DenialCodes.ACTION_NOT_ALLOWED, denied.code());
        assertFalse(denied.retryable(), "次数/阶段类拒绝不可原样重试");
        assertEquals(afterSave, harness.eventCount(), "INV-7：第二个药不得产生事件");
    }

    @Test
    @DisplayName("狼人不能刀队友（INV-6）：网关拦在领域之前，事件流不变")
    void wolfCannotKillTeammate() {
        List<Integer> wolves = harness.state().players().stream()
                .filter(player -> player.role() == Role.WEREWOLF)
                .map(player -> player.seatNo().value())
                .toList();
        int wolf = wolves.get(0);
        int teammate = wolves.get(1);
        int before = harness.eventCount();

        ToolGatewayPort.ToolResult result = harness.gateway().submit(
                harness.session(wolf), ToolCatalog.KILL,
                ActionProposal.of("WOLF_KILL", harness.playerIdOf(teammate), teammate));

        ToolGatewayPort.ToolResult.Denied denied = assertDenied(result);
        assertEquals(DenialCodes.ACTION_INVALID_TARGET, denied.code());
        assertEquals(before, harness.eventCount());
    }

    @Test
    @DisplayName("兜底 SKIP：夜里经 SpeakTool 提交也能落成空刀提名（agent-runtime.md §10）")
    void fallbackSkipWorksAtNight() {
        int wolf = harness.seatOfRole(Role.WEREWOLF);
        ToolGatewayPort.ToolResult.Ok ok = assertOk(harness.gateway().submit(
                harness.session(wolf), ToolCatalog.SPEAK, ActionProposal.skip("FALLBACK")));

        assertEquals(List.of("WOLF_KILL_NOMINATED"), ok.eventTypes(),
                "NIGHT_WOLF 的 SKIP 由领域映射为空刀提名");
        assertEquals(Boolean.TRUE, harness.events().get(harness.events().size() - 1)
                .payload().get("emptyKill"));
    }

    @Test
    @DisplayName("重放攻击：上一回合的会话在新阶段失效，不产生第二条行动事件（§8）")
    void staleSessionFromPreviousPhaseIsRejected() {
        harness.driveTo(GamePhase.DAY_DISCUSS);
        int speaker = harness.state().currentTurn().seatNo().value();
        ToolSession captured = harness.session(speaker);
        assertOk(harness.gateway().submit(
                captured, ToolCatalog.SPEAK,
                new ActionProposal("SPEAK", null, null, "第一轮发言", 0.6, "TEST", null)));

        // 把阶段推进到投票，再用"上一回合的会话"重放同一次意图
        harness.driveTo(GamePhase.DAY_VOTE);
        int versionBefore = harness.version();
        int eventsBefore = harness.eventCount();

        ToolGatewayPort.ToolResult replay = harness.gateway().submit(
                captured, ToolCatalog.SPEAK,
                new ActionProposal("SPEAK", null, null, "第一轮发言", 0.6, "TEST", null));

        ToolGatewayPort.ToolResult.Denied denied = assertDenied(replay);
        assertTrue(DenialCodes.all().contains(denied.code()), "拒绝码必须在登记表内：" + denied.code());
        assertFalse(denied.retryable(), "过期回合的调用不可原样重试");
        assertEquals(eventsBefore, harness.eventCount(), "重放不得新增事件");
        assertEquals(versionBefore, harness.version(), "重放不得推进版本");
    }

    static ToolGatewayPort.ToolResult.Ok assertOk(ToolGatewayPort.ToolResult result) {
        assertTrue(result instanceof ToolGatewayPort.ToolResult.Ok,
                "期望成功，实际 " + result.describe());
        return (ToolGatewayPort.ToolResult.Ok) result;
    }

    static ToolGatewayPort.ToolResult.Denied assertDenied(ToolGatewayPort.ToolResult result) {
        assertTrue(result instanceof ToolGatewayPort.ToolResult.Denied,
                "期望结构化拒绝，实际 " + result.describe());
        return (ToolGatewayPort.ToolResult.Denied) result;
    }

    private static int lowestAliveNonWolf(AgentView view) {
        return view.aliveOthers().stream()
                .filter(seat -> seat.role() == null || !"WEREWOLF".equals(seat.role()))
                .map(AgentView.SeatView::seatNo)
                .min(Integer::compareTo)
                .orElseThrow();
    }
}
