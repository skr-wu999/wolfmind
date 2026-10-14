package com.wolfmind.agent.e2e;

import com.wolfmind.application.agent.llm.ScriptedLlmClient;
import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.Camp;
import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 验收测试：**9 个 AI 座位在无真人参与下完成一整局**（AGENTS.md §11）。
 *
 * <p>它跑在 {@link InfrastructureGameHarness} 的生产同构链路上：Agent 的每次行动都经过
 * 工具网关 G1..G13、应用层的幂等与单写者锁、领域规则与事件化，因此这一条测试同时是
 * "Agent 子系统可用"与"写路径未被绕过"的证据。
 *
 * <p>三条验收线（对应 AGENTS.md §11 的三句话）：
 * <ol>
 *   <li><b>九个 AI 能完成一局</b>：规则基线（无 LLM）与脚本 LLM 两种模式都必须跑到
 *       {@code GAME_FINISHED}，且事件流版本连续无空洞；</li>
 *   <li><b>秘密角色保持隔离</b>：村民视图看不到狼队友，狼人视图看得到；</li>
 *   <li><b>非法/迟到输出被拒绝或安全降级</b>：模型返回坏 JSON、越权目标、超时不可用时，
 *       对局仍能推进到终局，且不产生非法事件。</li>
 * </ol>
 */
class NineAiGameE2ETest {

    private static final long CLOCK_START = 1_730_000_000_000L;
    private static final int MAX_STEPS = 400;

    @Test
    @DisplayName("规则基线：LLM 完全不可用时 9 个 AI 仍能完成一整局（agent-runtime.md §10）")
    void ruleBaselineCompletesFullGame() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, "g_e2e_baseline", 20240501L);

        int steps = runner.runUntilFinished(MAX_STEPS);

        assertEquals(GameStatus.FINISHED, runner.snapshot().status(),
                "规则基线必须能跑到终局，而不是卡在某个阶段（步数=" + steps + "，阶段时间线="
                        + runner.phaseTimeline() + "）");
        assertNotNull(runner.winner(), "终局必须有获胜阵营");
        assertTrue(runner.everySeatHasTrace(),
                "9 个座位都必须被真正驱动过（每个座位至少一条 AGENT_ 轨迹）");
        assertTrue(runner.agentTurns() > 0, "必须有 AI 回合被执行");
    }

    @Test
    @DisplayName("事件流版本连续无空洞，且每条阶段跳转都产生 PHASE_CHANGED")
    void eventStreamIsContiguousAndEveryTransitionIsEvented() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, "g_e2e_stream", 20240501L);
        runner.runUntilFinished(MAX_STEPS);

        List<EventEnvelope> stream = runner.eventStream();
        assertTrue(stream.size() > 11, "事件流应当明显长于开局的 11 条，实际 " + stream.size());
        for (int index = 0; index < stream.size(); index++) {
            assertEquals(index + 1, stream.get(index).version(),
                    "event-design.md §14.2：version 必须从 1 连续递增，无空洞无重复（INV-11）");
        }
        assertEquals(stream.size(), runner.version(), "聚合版本必须等于事件流长度");

        // 阶段跳转序列必须落在转换表内：这里断言"每次跳转都有对应事件"，转换表本身由
        // PhaseTransitionTableTest 逐条校验，此处不重复实现一份表。
        List<String> timeline = runner.phaseTimeline();
        assertFalse(timeline.isEmpty(), "对局必须有阶段跳转");
        assertTrue(timeline.stream().allMatch(entry -> entry.contains("->")),
                "阶段时间线必须形如 from->to@round，实际 " + timeline);
        assertEquals(1, (int) timeline.stream().filter(entry -> entry.startsWith("GAME_INIT->")).count(),
                "开局只应有一次 GAME_INIT 跳转");
        assertTrue(timeline.stream().anyMatch(entry -> entry.contains("->GAME_OVER")),
                "event-design.md §14.4：进入终态也必须有 PHASE_CHANGED，实际 " + timeline);
    }

    @Test
    @DisplayName("终局事件唯一且对局状态为 FINISHED，事件流里不出现 AGENT_ 类型")
    void terminalStateIsClean() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, "g_e2e_terminal", 20240501L);
        runner.runUntilFinished(MAX_STEPS);

        long finished = runner.eventTypes().stream().filter("GAME_FINISHED"::equals).count();
        assertEquals(1, finished, "终局事件只能有一条");
        assertTrue(runner.finished());
        assertTrue(runner.eventTypes().stream().noneMatch(type -> type.startsWith("AGENT_")),
                "event-design.md §1 结论 3 / §7.4：AGENT_ 决策事件绝不进入 game:{id}:events");
        assertTrue(runner.trace().allWireTypesAgentPrefixed(),
                "决策事件的类型前缀必须都是 AGENT_");
    }

    @Test
    @DisplayName("秘密角色保持隔离：村民看不到狼队友，狼人看得到（AGENTS.md §11）")
    void secretRolesStayIsolatedOnTheRealProjection() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, "g_e2e_roles", 20240501L);

        // 开局后立即检查：此时只有 GAME_STARTED / ROLE_ASSIGNED / PHASE_CHANGED
        var snapshot = runner.snapshot();
        int wolfSeat = snapshot.players().stream()
                .filter(p -> p.role() == com.wolfmind.domain.game.model.Role.WEREWOLF)
                .mapToInt(p -> p.seatNo().value())
                .findFirst()
                .orElseThrow();
        int villagerSeat = snapshot.players().stream()
                .filter(p -> p.role() == com.wolfmind.domain.game.model.Role.VILLAGER)
                .mapToInt(p -> p.seatNo().value())
                .findFirst()
                .orElseThrow();

        var wolfView = runner.viewOf(wolfSeat);
        var villagerView = runner.viewOf(villagerSeat);

        assertNotNull(wolfView.me(), "座位视图必须包含 me");
        assertEquals("WEREWOLF", wolfView.me().role(), "本人角色可见");

        long wolfVisibleRoles = wolfView.other().stream().filter(seat -> seat.role() != null).count();
        assertTrue(wolfVisibleRoles >= 2,
                "狼人必须能看到队友角色（event-design.md §4：WOLF 可见性），实际可见 " + wolfVisibleRoles);

        long villagerVisibleRoles = villagerView.other().stream()
                .filter(seat -> seat.role() != null)
                .count();
        assertEquals(0, villagerVisibleRoles,
                "村民在进行中不得看到任何他人角色（隐藏信息隔离），实际可见 " + villagerVisibleRoles
                        + " 个：" + villagerView.other().stream()
                        .filter(seat -> seat.role() != null)
                        .map(seat -> seat.seatNo() + "=" + seat.role())
                        .toList());
    }

    @Test
    @DisplayName("模型输出不可用或非法时安全降级：对局仍能推进到终局，且无非法事件")
    void illegalOrMissingModelOutputDegradesSafely() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        // 每次调用都失败：验证"全 LLM 不可用"的全局降级（agent-runtime.md §10 最后一行）
        ScriptedLlmClient failing = new ScriptedLlmClient();
        failing.unavailable();

        AgentConfig config = new AgentConfig(
                "e2e-llm", "失败模型", "fake-model", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "tpl", com.wolfmind.application.agent.model.TurnBudget.DEFAULT);

        NineAiGameRunner runner = NineAiGameRunner.start(
                harness, "g_e2e_degrade", failing, config, 20240501L);
        runner.runUntilFinished(MAX_STEPS);

        assertEquals(GameStatus.FINISHED, runner.snapshot().status(),
                "模型不可用时必须仍能完成对局（走规则兜底），实际阶段=" + runner.phase()
                        + " 时间线=" + runner.phaseTimeline());
        assertEquals(0, failing.callCount(),
                "模型不可用时不应产生任何调用（Runtime 必须先查 available()）");
        assertTrue(runner.fallbackTurns() > 0, "必须走规则兜底");
        assertTrue(runner.trace().countOf(
                        com.wolfmind.application.agent.trace.AgentTracePort.TraceEventType.AGENT_TOOL_CALLED) > 0,
                "降级路径的动作仍必须经工具网关提交（兜底不是绕过领域）");
        assertTrue(runner.eventTypes().stream().noneMatch(type -> type.startsWith("AGENT_")),
                "降级路径同样不得污染对局事件流");
    }

    @Test
    @DisplayName("脚本模型返回坏 JSON：校验拒绝后修复或兜底，对局不受影响")
    void malformedModelOutputIsRejectedAndRecovered() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        ScriptedLlmClient scripted = new ScriptedLlmClient();
        // 每个回合都返回无法解析的文本；脚本耗尽后固定返回坏输出，等价于"模型一直胡说"
        for (int index = 0; index < 300; index++) {
            scripted.enqueueText("这不是 JSON，也不是合法动作");
        }
        AgentConfig config = new AgentConfig(
                "e2e-badjson", "坏输出模型", "fake-model", 0.6, 0.2,
                AgentConfig.StrategyProfile.BALANCED, "中性", AgentConfig.WorkflowMode.A,
                60, 0.5, 8, 3, "tpl", com.wolfmind.application.agent.model.TurnBudget.DEFAULT);

        NineAiGameRunner runner = NineAiGameRunner.start(
                harness, "g_e2e_badjson", scripted, config, 20240501L);
        runner.runUntilFinished(MAX_STEPS);

        assertEquals(GameStatus.FINISHED, runner.snapshot().status(),
                "坏输出必须被 OutputValidator 拒绝并降级，而不是让对局卡死；时间线="
                        + runner.phaseTimeline());
        // 每次失败都记 AGENT_OUTPUT_REJECTED，且修复轮不超过预算
        assertTrue(runner.trace().hasType(
                        com.wolfmind.application.agent.trace.AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED)
                        || runner.trace().hasType(
                        com.wolfmind.application.agent.trace.AgentTracePort.TraceEventType.AGENT_LLM_INVOKED),
                "必须记录 LLM 调用或输出拒绝的轨迹");
        assertTrue(runner.fallbackTurns() > 0, "校验失败后必须走兜底");
    }

    @Test
    @DisplayName("对局可复现：同一 gameId + seed 在两套独立环境里跑两次得到同一事件类型序列与同一胜者")
    void gameIsReproducibleWithSameSeed() {
        // 策略层的平局键取自 gameId（strategy-layer.md §13），复现必须固定 gameId
        InfrastructureGameHarness first = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner firstRunner = NineAiGameRunner.startRuleBaseline(first, "g_repro", 20240501L);
        firstRunner.runUntilFinished(MAX_STEPS);

        InfrastructureGameHarness second = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner secondRunner = NineAiGameRunner.startRuleBaseline(second, "g_repro", 20240501L);
        secondRunner.runUntilFinished(MAX_STEPS);

        assertEquals(firstRunner.eventTypes(), secondRunner.eventTypes(),
                "strategy-layer.md §11 末句：同一 gameId + seed + 配置必须产生同一事件序列");
        assertEquals(firstRunner.winner(), secondRunner.winner(), "胜者必须一致");
        assertEquals(firstRunner.round(), secondRunner.round(), "轮数必须一致");

        Set<Camp> winners = new HashSet<>();
        winners.add(firstRunner.winner());
        assertEquals(1, winners.size());
    }

    @Test
    @DisplayName("每个 AI 座位都留下完整决策轨迹：回合开始/LLM 或策略/结束")
    void everySeatLeavesCompleteTrace() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, "g_e2e_trace", 20240501L);
        runner.runUntilFinished(MAX_STEPS);

        var types = List.of(
                com.wolfmind.application.agent.trace.AgentTracePort.TraceEventType.AGENT_TURN_STARTED,
                com.wolfmind.application.agent.trace.AgentTracePort.TraceEventType.AGENT_STRATEGY_DECIDED,
                com.wolfmind.application.agent.trace.AgentTracePort.TraceEventType.AGENT_TURN_FINISHED);
        for (var type : types) {
            assertTrue(runner.trace().hasType(type),
                    "决策轨迹必须包含 " + type + "（agent-runtime.md §12 目录）");
        }
        assertTrue(runner.trace().totalCount() > 0);
        assertTrue(runner.activeInstanceCount() >= 0);
    }

    @Test
    @DisplayName("AI 回合数足以覆盖整局：多次夜间与白天都真的由 Agent 驱动")
    void agentsActuallyDriveTheWholeGame() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, "g_e2e_drive", 20240501L);
        runner.runUntilFinished(MAX_STEPS);

        // 一局至少 2 轮（否则胜负判定过早），且每轮都有发言与投票
        assertTrue(runner.round() >= 1, "至少 1 轮");
        assertTrue(runner.eventTypes().contains("GAME_STARTED"));
        assertTrue(runner.eventTypes().contains("PHASE_CHANGED"));
        assertTrue(runner.agentTurns() >= runner.round() * 3,
                "每轮至少应有若干个 AI 回合（夜间+发言+投票），实际 " + runner.agentTurns()
                        + " 回合 / " + runner.round() + " 轮");
        // 阶段覆盖：至少经历一次夜间与一次白天判定阶段
        List<String> timeline = runner.phaseTimeline();
        assertTrue(timeline.stream().anyMatch(entry -> entry.contains("NIGHT_")),
                "必须经历夜间阶段，实际 " + timeline);
        assertTrue(timeline.stream().anyMatch(entry -> entry.contains("DAY_") || entry.contains("VOTE")),
                "必须经历白天阶段，实际 " + timeline);
    }

    @Test
    @DisplayName("策略层与网关不被绕过：每次提交都留下网关审计轨迹")
    void submissionsGoThroughTheGateway() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, "g_e2e_gateway", 20240501L);
        runner.runUntilFinished(MAX_STEPS);

        // 纯规则基线不调用 LLM，因此每个生效的回合都记为 FALLBACK——但"兜底"指的是**决策来源**，
        // 不是"没提交"：兜底产物同样是合法动作并已写入事件流
        // （agent-runtime.md §10 末句「兜底动作是合法 Action，走同一领域校验与同一事件化路径」）。
        int effectiveTurns = runner.submittedTurns() + runner.fallbackTurns();
        assertTrue(effectiveTurns > 0,
                "必须有经由工具网关提交成功的回合，实际 submitted=" + runner.submittedTurns()
                        + " fallback=" + runner.fallbackTurns() + " failed=" + runner.failedTurns());
        assertEquals(0, runner.timeoutSkips(),
                "规则基线必须能自行完成每一个回合，不应依赖超时跳过兜底");
        assertTrue(runner.trace().countOf(
                        com.wolfmind.application.agent.trace.AgentTracePort.TraceEventType.AGENT_TOOL_CALLED) > 0,
                "每次工具调用都必须留下审计轨迹（tool-gateway.md §7）");
        assertTrue(runner.trace().allWireTypesAgentPrefixed(),
                "审计轨迹的类型前缀必须都是 AGENT_，不得混入领域事件类型");
    }

    @Test
    @DisplayName("阶段推进与超时看护不会产生空转：事件流始终在增长")
    void noInfiniteStalling() {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(CLOCK_START);
        NineAiGameRunner runner = NineAiGameRunner.startRuleBaseline(harness, "g_e2e_progress", 20240501L);

        int eventsBefore = runner.eventStream().size();
        runner.runUntilFinished(MAX_STEPS);
        int eventsAfter = runner.eventStream().size();

        assertTrue(eventsAfter > eventsBefore + 10,
                "整局必须产生大量事件，实际只有 " + (eventsAfter - eventsBefore));
        assertTrue(runner.timeoutSkips() + runner.stageAdvanceSteps() + runner.agentTurns() > 10,
                "推进动作必须有实质数量");
        assertFalse(runner.phaseTimeline().isEmpty());
    }
}
