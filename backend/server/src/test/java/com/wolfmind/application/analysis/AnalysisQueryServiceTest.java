package com.wolfmind.application.analysis;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.view.GameViewSnapshot;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.testsupport.ControlledTurnDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 分析查询用例的验收（api-spec.md §7、AGENTS.md §11 Phase 5 的"回放准确性 / AI 分析"）。
 *
 * <p>整局复用应用层规划/超时用例与真实工具网关，由同步测试夹具 {@code ControlledTurnDriver} 驱动，
 * 因此断言的对象是真实事件流与真实决策轨迹，而不是手工构造的样本。
 */
class AnalysisQueryServiceTest {

    private static final String GAME_ID = "g_analysis";
    private static final long START_MILLIS = 1_730_000_000_000L;

    private static final class Fixture {
        final InfrastructureGameHarness harness = new InfrastructureGameHarness(START_MILLIS);
        final AgentViewAdapter views = new AgentViewAdapter(harness.viewQuery());
        final InMemoryAgentTrace trace = new InMemoryAgentTrace();
        final ToolGatewayAdapter gateway = new ToolGatewayAdapter(
                views, harness.viewQuery(), harness.submitService(), harness.idempotency(), trace, harness.time());
        final AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                views, new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(), trace, null, harness.time());
        final ControlledTurnDriver driver = new ControlledTurnDriver(harness.repository(),
                new AIControllerAdapter(runtime, views, gateway, harness.time(),
                        AgentConfig.ruleBaseline("analysis-test")),
                harness.time(), harness.publisher(), harness.eventStore(), harness.lock());
        final AnalysisQueryService analysis = new AnalysisQueryService(harness.viewQuery(), trace);

        Fixture(long seed) {
            harness.startNineAiGame(GAME_ID, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, seed);
        }

        /** 推进到终局；规则基线不调用任何模型，因此这一步是确定性的。 */
        void playToEnd() {
            for (int step = 0; step < 600
                    && harness.repository().load(GAME_ID).game().status() == GameStatus.RUNNING; step++) {
                harness.advanceClock(200L);
                driver.step(GAME_ID);
            }
        }
    }

    @Test
    @DisplayName("终局后概览给出角色、每人统计与关键事件，且每一项都能追到事件流")
    void summaryMatchesTheCommittedEventStream() {
        Fixture fixture = new Fixture(20240501L);
        fixture.playToEnd();
        assertEquals(GameStatus.FINISHED, fixture.harness.repository().load(GAME_ID).game().status());

        AnalysisSummary summary = fixture.analysis.summary(GAME_ID, null);

        assertEquals("FINISHED", summary.status());
        assertEquals("FULL", summary.disclosure(), "终局必须完整披露：这是 2026-10-12 批准的政策");
        assertEquals(GAME_ID, summary.gameId());
        assertEquals(9, summary.players().size());
        assertTrue(summary.rounds() >= 1);
        assertNotNull(summary.winner(), "终局必须有胜方");

        // 角色只在披露后出现；这是"分析页看到的身份来自服务端"的直接证据
        assertTrue(summary.players().stream().allMatch(stat -> stat.role() != null),
                "终局后 9 个座位都应给出角色");

        // 发言数与事件流逐条对齐
        List<String> speeches = fixture.harness.eventTypes(GAME_ID).stream()
                .filter("PLAYER_SPOKE"::equals)
                .toList();
        int totalSpeeches = summary.players().stream().mapToInt(AgentStat::speechCount).sum();
        assertEquals(speeches.size(), totalSpeeches, "发言总数必须等于 PLAYER_SPOKE 的条数");

        // 关键事件列表只含公开事件类型
        assertFalse(summary.keyEvents().isEmpty());
        assertTrue(summary.keyEvents().stream().allMatch(event ->
                        event.summary() != null && !event.summary().isBlank()),
                "每条关键事件都要有可读说明");
        assertTrue(summary.keyEvents().stream().noneMatch(event ->
                        "ROLE_ASSIGNED".equals(event.type()) || "WOLF_KILL_SELECTED".equals(event.type())),
                "私有与狼队事件不得进入公开的关键事件列表");
        assertTrue(summary.provenance().contains("事件"),
                "来源说明必须写清楚数字从哪来，否则页面上的数字会被当成事实引用");
    }

    @Test
    @DisplayName("投票命中只在角色披露后给出，且与阵营一致")
    void voteAccuracyIsDerivedFromDisclosedCamps() {
        Fixture fixture = new Fixture(7L);
        fixture.playToEnd();
        AnalysisSummary summary = fixture.analysis.summary(GAME_ID, null);

        GameViewSnapshot view = fixture.harness.viewQuery().view(GAME_ID, null);
        Map<Integer, String> campBySeat = new java.util.LinkedHashMap<>();
        view.players().forEach(player -> campBySeat.put(player.seatNo(),
                player.camp() == null ? null : player.camp().name()));

        for (AgentStat stat : summary.players()) {
            if (stat.voteCount() == 0) {
                assertNull(stat.voteHitCount(), "没投过票的座位不该给出命中数");
                continue;
            }
            assertNotNull(stat.voteHitCount());
            long expected = stat.voteTargetSeats().stream()
                    .filter(seat -> "WOLF".equals(campBySeat.get(seat)))
                    .count();
            assertEquals(expected, stat.voteHitCount().longValue(),
                    "座位 " + stat.seatNo() + " 的命中数必须可重算");
            assertEquals(stat.voteHitCount() / (double) stat.voteCount(), stat.voteAccuracy(), 0.0001);
        }
    }

    @Test
    @DisplayName("决策轨迹给出候选集、选择与 token，且不含完整提示词")
    void agentTracesExposeObservableFactsOnly() {
        Fixture fixture = new Fixture(42L);
        fixture.playToEnd();

        AgentAnalysis analysis = fixture.analysis.agentAnalysis(GAME_ID, null, "p_1");

        assertEquals(GAME_ID, analysis.gameId());
        assertEquals(1, analysis.seatNo());
        assertEquals("FULL", analysis.disclosure());
        assertNotNull(analysis.role(), "终局后座位角色必须可得");
        assertFalse(analysis.traces().isEmpty(), "规则基线座位同样会留下轨迹（回合开始/结束至少各一条）");

        assertTrue(analysis.traces().stream().anyMatch(trace -> "FINISHED".equals(trace.kind())));
        assertTrue(analysis.traces().stream().anyMatch(trace ->
                        "TURN_STARTED".equals(trace.kind())),
                "轨迹必须包含回合起点，否则无法把决策放回时间轴");
        assertTrue(analysis.traces().stream().allMatch(trace -> trace.version() >= 1));
        assertTrue(analysis.traces().stream().noneMatch(trace -> "LLM".equals(trace.kind())),
                "规则基线不调用模型，因此不应有 LLM 轨迹");

        // 版本升序：分析页按时间轴渲染，乱序会让"决策顺序"读起来是错的
        for (int i = 1; i < analysis.traces().size(); i++) {
            assertTrue(analysis.traces().get(i).version() >= analysis.traces().get(i - 1).version());
        }
        assertTrue(analysis.note().contains("不包含模型私有推理"),
                "接口必须明说它给的不是思维过程（AGENTS.md §8）");
    }

    @Test
    @DisplayName("复读拦截在赛后给出被比对的座位、相似度与被拦的稿子；泄密拦截不给原稿")
    void echoRejectionShowsMatchAndDraftAfterGame() {
        Fixture fixture = new Fixture(42L);
        fixture.playToEnd();
        recordRejection(fixture, Map.of("stage", "ECHO_CHECK", "seatNo", 3, "round", 1,
                "similarity", 0.8123, "draft", "首稿内容"));
        recordRejection(fixture, Map.of("stage", "ECHO_CHECK_REWRITE", "seatNo", 2, "round", 1,
                "similarity", 0.9, "draft", "改写稿内容", "kept", "ORIGINAL"));
        recordRejection(fixture, Map.of("stage", "LEAK_CHECK", "kind", "PRIVATE_FACT", "draft", "不该出现"));

        List<AgentAnalysis.DecisionTraceView> rejected = fixture.analysis.agentAnalysis(GAME_ID, null, "p_2")
                .traces().stream()
                .filter(trace -> "REJECTED".equals(trace.kind()))
                .toList();

        assertEquals(3, rejected.size());
        assertEquals("与 3 号第 1 轮发言相似度 0.81", rejected.get(0).reason());
        assertEquals("首稿内容", rejected.get(0).draft());
        assertEquals("与自己第 1 轮发言相似度 0.90，提交了首稿", rejected.get(1).reason());
        assertEquals("改写稿内容", rejected.get(1).draft());
        assertEquals("PRIVATE_FACT", rejected.get(2).reason());
        assertNull(rejected.get(2).draft(), "泄密拦截的原稿可能含隐藏信息，任何时候都不返回");
    }

    private static void recordRejection(Fixture fixture, Map<String, Object> payload) {
        fixture.trace.record(new com.wolfmind.application.agent.trace.AgentTracePort.AgentTraceEvent(
                com.wolfmind.application.agent.trace.AgentTracePort.TraceEventType.AGENT_OUTPUT_REJECTED,
                GAME_ID, "p_2", 10_000, "DAY_DISCUSS", 1, START_MILLIS, payload));
    }

    @Test
    @DisplayName("进行中的对局只给公开事实，且不返回任何决策轨迹")
    void runningGameIsPublicOnly() {
        Fixture fixture = new Fixture(20240501L);
        // 只推进一步：此时对局仍在运行
        fixture.harness.advanceClock(200L);
        fixture.driver.step(GAME_ID);
        assertEquals(GameStatus.RUNNING, fixture.harness.repository().load(GAME_ID).game().status());

        AnalysisSummary summary = fixture.analysis.summary(GAME_ID, null);
        assertEquals("PUBLIC", summary.disclosure());
        assertTrue(summary.players().stream().allMatch(stat -> stat.role() == null),
                "进行中不得披露任何角色");
        assertTrue(summary.players().stream().allMatch(stat -> stat.voteHitCount() == null),
                "角色未披露时命中率算不出来，必须是 null 而不是 0");
        assertTrue(summary.provenance().contains("结束后"));

        AgentAnalysis analysis = fixture.analysis.agentAnalysis(GAME_ID, null, "p_1");
        assertEquals("PUBLIC", analysis.disclosure());
        assertTrue(analysis.traces().isEmpty(), "轨迹包含候选集与工具调用，属于最强的事实披露");
        assertTrue(analysis.note().contains("结束后"));
    }

    @Test
    @DisplayName("不存在的座位按地址错误处理，而不是给出一份空轨迹")
    void unknownSeatIsRejected() {
        Fixture fixture = new Fixture(20240501L);
        assertThrows(AnalysisQueryService.UnknownSeatException.class,
                () -> fixture.analysis.agentAnalysis(GAME_ID, null, "p_99"));
    }
}
