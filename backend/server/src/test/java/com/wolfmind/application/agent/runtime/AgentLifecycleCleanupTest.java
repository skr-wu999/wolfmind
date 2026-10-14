package com.wolfmind.application.agent.runtime;

import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.agent.fake.FakeAgentView;
import com.wolfmind.application.agent.fake.FakeToolGateway;
import com.wolfmind.application.agent.llm.LlmClient;
import com.wolfmind.application.agent.model.*;
import com.wolfmind.application.agent.speech.SpeechPreviewPort;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.agent.view.AgentViewQueryPort;
import com.wolfmind.application.port.MetricsPort;
import com.wolfmind.infrastructure.ai.WorkflowEngineSelection;
import com.wolfmind.infrastructure.archive.ArchivingAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.infrastructure.metrics.InProcessMetrics;
import com.wolfmind.infrastructure.sqlite.SqliteDecisionTraceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentLifecycleCleanupTest {
    private static final long NOW = 1_730_000_000_000L;

    private static AgentConfig modelConfig() {
        var base = AgentConfig.ruleBaseline("test");
        return new AgentConfig(base.agentConfigId(), base.name(), "fixed-model", base.temperature(),
                base.repairTemperature(), base.strategyProfile(), base.persona(), base.mode(),
                base.summarizeThreshold(), base.exposureAversion(), base.memoryRecallLimit(),
                base.candidateLimit(), base.promptTemplateId(), new TurnBudget(1, 6, 8000, 1500, 500));
    }

    private static TurnRequest request(int seat, AgentConfig config) {
        var view = FakeAgentView.of("VILLAGER", seat, List.of(3, 4, 5, 6, 7, 8, 9), "DAY_VOTE");
        return new TurnRequest("g_test", "p_" + seat, seat, "VILLAGER", "VILLAGE", "DAY_VOTE",
                1, 0, 10, NOW + 30_000, view, List.of("SubmitVoteTool"), config.budget(), config);
    }

    static final class BlockingModel implements LlmClient {
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1)};
        final AtomicInteger calls = new AtomicInteger();
        @Override public LlmResponse chat(LlmRequest request) {
            int call = calls.getAndIncrement();
            assertTrue(call < 2, "no retries should consume an extra model call");
            entered.countDown();
            try { assertTrue(release[call].await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
            return new LlmResponse.Success("", "fixed-model", 100, 50, 1,
                    List.of(new ToolCall("call-" + call, "SubmitVoteTool", "{\"targetId\":\"p_3\"}")), null);
        }
        @Override public boolean available() { return true; }
        @Override public String providerId() { return "fixed"; }
        @Override public InteractionMode interactionMode() { return InteractionMode.FUNCTION_CALLING; }
    }

    @ParameterizedTest @ValueSource(strings = {"SEQUENTIAL", "LANGGRAPH4J"})
    void terminalCleanupWaitsForAllAdmittedCallsAndArchivesTheirFinalTraces(String engine) throws Exception {
        try (var archive = SqliteDecisionTraceRepository.inMemory(); var pool = Executors.newFixedThreadPool(2)) {
            var model = new BlockingModel();
            var live = new InMemoryAgentTrace();
            var trace = new ArchivingAgentTrace(live, archive);
            var guard = new LlmCostGuard(1_000, () -> NOW);
            var gateway = new FakeToolGateway();
            var views = mock(AgentViewQueryPort.class);
            when(views.viewFor(anyString(), anyString())).thenAnswer(invocation ->
                    request(Integer.parseInt(invocation.<String>getArgument(1).substring(2)), modelConfig()).view());
            var runtime = new AgentRuntimeImpl(views, new WolfMindStrategy(gateway), gateway,
                    new InMemoryMemoryPort(), trace, model, () -> NOW, new BudgetGuard(() -> NOW),
                    new WorkflowEngineSelection(engine), SpeechPreviewPort.NONE, new InProcessMetrics(), guard);
            var config = modelConfig();
            for (int seat = 1; seat <= 2; seat++) runtime.createAgent(
                    new AgentRuntime.CreateAgentCommand("g_test", "p_" + seat, seat, config));
            guard.record("g_test", 100, 0);
            var first = pool.submit(() -> runtime.executeTurn(request(1, config)));
            var second = pool.submit(() -> runtime.executeTurn(request(2, config)));
            try {
                assertTrue(model.entered.await(3, TimeUnit.SECONDS));
                runtime.destroyGame("g_test");
                runtime.destroyGame("g_test");
                assertEquals(0, runtime.activeInstanceCount("g_test"));
                assertEquals(1, runtime.trackedGameCount());
                assertEquals(100, guard.tokensSpent("g_test"));
                assertThrows(IllegalStateException.class, () -> runtime.createAgent(
                        new AgentRuntime.CreateAgentCommand("g_test", "p_1", 1, config)));
                assertEquals("AGENT_NOT_REGISTERED", runtime.executeTurn(request(1, config)).errorCode());
                int before = archive.count("g_test");
                model.release[0].countDown();
                for (int i = 0; i < 300 && !first.isDone() && !second.isDone(); i++) Thread.sleep(5);
                assertTrue(first.isDone() || second.isDone());
                (first.isDone() ? first : second).get(1, TimeUnit.SECONDS);
                assertEquals(250, guard.tokensSpent("g_test"), "the other model call is still outstanding");
                assertEquals(1, runtime.trackedGameCount());
                assertTrue(archive.count("g_test") >= before);
                model.release[1].countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
                assertEquals(0, runtime.trackedGameCount());
                assertEquals(0, guard.tokensSpent("g_test"));
                assertEquals(0, live.totalCount());
                assertEquals(2, archive.load("g_test", null).stream()
                        .filter(event -> event.type() == AgentTracePort.TraceEventType.AGENT_TURN_FINISHED).count());
                int archived = archive.count("g_test");
                runtime.destroyGame("g_test");
                runtime.executeTurn(request(1, config));
                assertEquals(archived, archive.count("g_test"));
                assertEquals(0, live.totalCount());
                assertEquals(2, model.calls.get());
            } finally {
                model.release[0].countDown(); model.release[1].countDown();
            }
        }
    }

    @Test void humanTakeoverKeepsTheGameLedgerAndOtherSeats() {
        var trace = new InMemoryAgentTrace();
        var guard = new LlmCostGuard(100, () -> NOW);
        var runtime = new AgentRuntimeImpl(null, null, null, null, trace, null, () -> NOW,
                new BudgetGuard(() -> NOW), null, SpeechPreviewPort.NONE, MetricsPort.NOOP, guard);
        var config = AgentConfig.ruleBaseline("test");
        var first = runtime.createAgent(new AgentRuntime.CreateAgentCommand("g_test", "p_1", 1, config));
        runtime.createAgent(new AgentRuntime.CreateAgentCommand("g_test", "p_2", 2, config));
        guard.record("g_test", 150, 0);
        runtime.destroyAgent(first);
        runtime.destroyAgent(first);
        assertFalse(runtime.hasInstance("g_test", "p_1"));
        assertTrue(runtime.hasInstance("g_test", "p_2"));
        assertEquals(150, guard.tokensSpent("g_test"));
        assertEquals(1, guard.blockedGames());
        runtime.destroyGame("g_test");
        assertEquals(0, guard.blockedGames());
        assertEquals(0, runtime.trackedGameCount());
    }

    @Test void manyCompletedGamesReleaseLedgersAndKeepOnlyBoundedAnalysis() {
        var trace = new InMemoryAgentTrace(3, 3_600_000, () -> NOW, MetricsPort.NOOP);
        var guard = new LlmCostGuard(100, () -> NOW);
        var runtime = new AgentRuntimeImpl(null, null, null, null, trace, null, () -> NOW,
                new BudgetGuard(() -> NOW), null, SpeechPreviewPort.NONE, MetricsPort.NOOP, guard);
        for (int i = 0; i < 2_000; i++) {
            String game = "game-" + i;
            runtime.createAgent(new AgentRuntime.CreateAgentCommand(game, "p_1", 1, AgentConfig.ruleBaseline("test")));
            trace.record(new AgentTracePort.AgentTraceEvent(AgentTracePort.TraceEventType.AGENT_TURN_FINISHED,
                    game, "p_1", 10, "DAY_VOTE", 1, NOW, Map.of()));
            guard.record(game, i % 2 == 0 ? 50 : 150, 0);
            runtime.destroyGame(game);
            runtime.destroyGame(game);
        }
        assertEquals(0, runtime.trackedGameCount());
        assertEquals(0, guard.report().get("trackedGames"));
        assertEquals(0, guard.blockedGames());
        assertEquals(3, trace.totalCount());
    }

    @Test void finishingOneGameDoesNotResetAnotherGamesBudget() {
        var trace = new InMemoryAgentTrace();
        var guard = new LlmCostGuard(100, () -> NOW);
        var runtime = new AgentRuntimeImpl(null, null, null, null, trace, null, () -> NOW,
                new BudgetGuard(() -> NOW), null, SpeechPreviewPort.NONE, MetricsPort.NOOP, guard);
        for (String game : List.of("finished", "active")) {
            runtime.createAgent(new AgentRuntime.CreateAgentCommand(game, "p_1", 1, AgentConfig.ruleBaseline("test")));
            guard.record(game, 150, 0);
        }
        runtime.destroyGame("finished");
        assertEquals(0, guard.tokensSpent("finished"));
        assertEquals(150, guard.tokensSpent("active"));
        assertEquals(1, guard.blockedGames());
        assertTrue(runtime.hasInstance("active", "p_1"));
        assertEquals(1, runtime.trackedGameCount());
    }

    @Test void oldAiSignalCannotUndoHumanTakeover() {
        var views = mock(AgentViewQueryPort.class);
        var base = request(1, modelConfig()).view();
        var self = base.me();
        var human = new AgentView.Self(self.playerId(), self.seatNo(), self.role(), self.camp(), self.alive(),
                "HUMAN", self.skills());
        var view = new AgentView(base.gameId(), base.version(), base.status(), base.phase(), base.round(),
                base.turnIndex(), base.turnOrder(), base.currentTurn(), base.rules(), human, base.other(),
                base.votes(), base.deaths(), base.speeches(), base.privateEvents(), base.winner());
        when(views.viewFor("g_test", "p_1")).thenReturn(view);
        var runtime = mock(AgentRuntime.class);
        var adapter = new AIControllerAdapter(runtime, views, mock(com.wolfmind.application.agent.tool.ToolGatewayPort.class),
                () -> NOW, modelConfig());
        assertNull(adapter.dispatch(new TurnSignal("g_test", "p_1", 1, "DAY_VOTE", 0,
                NOW + 30_000, 10, "AI"), null));
        verify(runtime, never()).createAgent(any());
        verify(runtime, never()).executeTurn(any());
    }

    @ParameterizedTest @ValueSource(strings = {"FINISHED", "ABORTED"})
    void completedViewCannotLazilyRecreateAnAgent(String status) {
        var views = mock(AgentViewQueryPort.class);
        var base = request(1, modelConfig()).view();
        var terminal = new AgentView(base.gameId(), base.version(), status, base.phase(), base.round(),
                base.turnIndex(), base.turnOrder(), base.currentTurn(), base.rules(), base.me(), base.other(),
                base.votes(), base.deaths(), base.speeches(), base.privateEvents(), base.winner());
        when(views.viewFor("g_test", "p_1")).thenReturn(terminal);
        var runtime = mock(AgentRuntime.class);
        var adapter = new AIControllerAdapter(runtime, views, mock(com.wolfmind.application.agent.tool.ToolGatewayPort.class),
                () -> NOW, modelConfig());
        assertEquals("GAME_CLOSED", adapter.dispatch(new TurnSignal("g_test", "p_1", 1, "DAY_VOTE", 0,
                NOW + 30_000, 10, "AI"), null).errorCode());
        verify(runtime, never()).createAgent(any());
        verify(runtime, never()).executeTurn(any());
    }
}
