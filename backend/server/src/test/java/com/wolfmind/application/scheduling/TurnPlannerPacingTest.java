package com.wolfmind.application.scheduling;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.testsupport.ControlledTurnDriver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TurnPlannerPacingTest {
    private static final String ID = "g_pacing";

    private InfrastructureGameHarness discussionAfterOneSpeech() {
        var harness = new InfrastructureGameHarness(1_730_000_000_000L);
        var views = new AgentViewAdapter(harness.viewQuery());
        var trace = new InMemoryAgentTrace();
        var gateway = new ToolGatewayAdapter(views, harness.viewQuery(), harness.submitService(),
                harness.idempotency(), trace, harness.time());
        var runtime = new AgentRuntimeImpl(views, new WolfMindStrategy(gateway), gateway,
                new InMemoryMemoryPort(), trace, null, harness.time());
        var adapter = new AIControllerAdapter(runtime, views, gateway, harness.time(), AgentConfig.ruleBaseline("pacing"));
        var driver = new ControlledTurnDriver(harness.repository(), adapter, harness.time(), harness.publisher(),
                harness.eventStore(), harness.lock());
        harness.startNineAiGame(ID, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 20240501L);
        for (int step = 0; step < 200; step++) {
            if (harness.repository().load(ID).game().phase() == GamePhase.DAY_DISCUSS
                    && harness.eventStream(ID).stream().anyMatch(e -> e.type().equals("PLAYER_SPOKE")
                            && e.phase().equals("DAY_DISCUSS"))) return harness;
            driver.step(ID);
        }
        throw new AssertionError("Unable to reach discussion after first speech");
    }

    @Test void readingDelayChangesOnlyReadyTimeAndCannotExtendTheTurn() {
        var harness = discussionAfterOneSpeech();
        var pacing = new SpeechPacing(1000, 100, 20000, id -> true);
        var planner = new TurnPlanner(harness.repository(), harness.eventStore(), pacing);
        var speech = harness.eventStream(ID).stream().filter(e -> e.type().equals("PLAYER_SPOKE")).reduce((a,b) -> b).orElseThrow();
        var before = harness.repository().load(ID).game().snapshot();
        var action = planner.plan(ID).tasks().stream().filter(t -> t.kind() == TurnTask.Kind.ACTION).findFirst().orElseThrow();
        assertEquals(speech.occurredAt() + pacing.pauseFor((String) speech.payload().get("content")), action.readyAt());
        assertEquals(before.currentTurn().deadline(), action.deadline());
        harness.setClockTo(action.readyAt());
        assertEquals(action.taskId(), planner.plan(ID).tasks().stream().filter(t -> t.kind() == TurnTask.Kind.ACTION).findFirst().orElseThrow().taskId());
        assertEquals(before, harness.repository().load(ID).game().snapshot());
    }

    @Test void excludedGamesDoNotReceiveReadingDelay() {
        var harness = discussionAfterOneSpeech();
        var planner = new TurnPlanner(harness.repository(), harness.eventStore(), new SpeechPacing(1000, 100, 20000, id -> !id.equals(ID)));
        var action = planner.plan(ID).tasks().stream().filter(t -> t.kind() == TurnTask.Kind.ACTION).findFirst().orElseThrow();
        var turn = harness.eventStream(ID).stream().filter(e -> e.type().equals("TURN_GRANTED")).reduce((a,b) -> b).orElseThrow();
        assertEquals(turn.occurredAt(), action.readyAt());
    }

    @Test void readingDelayCountsUnicodeCharactersAndCapsLength() {
        var pacing = new SpeechPacing(1500, 120, 25000, id -> true);
        assertEquals(1500, pacing.pauseFor(null));
        assertEquals(1500, pacing.pauseFor(""));
        assertEquals(1980, pacing.pauseFor("我是好人"));
        assertEquals(1620, pacing.pauseFor("😀"));
        assertEquals(25000, pacing.pauseFor("字".repeat(500)));
        assertFalse(SpeechPacing.NONE.enabledFor(ID));
    }

    @Test void invalidReadingDelayFailsBeforeScheduling() {
        assertThrows(IllegalArgumentException.class, () -> new SpeechPacing(-1, 0, 0, id -> true));
        assertThrows(IllegalArgumentException.class, () -> new SpeechPacing(0, -1, 0, id -> true));
        assertThrows(IllegalArgumentException.class, () -> new SpeechPacing(0, 0, -1, id -> true));
        assertThrows(NullPointerException.class, () -> new SpeechPacing(0, 0, 0, null));
    }

    @Test void humanTimeoutAdvancesOnlyTheExpiredSpeechWindowAndCannotRunTwice() {
        var harness = discussionAfterOneSpeech();
        var loaded = harness.repository().load(ID);
        var game = loaded.game();
        int seat = game.snapshot().currentTurn().seatNo().value();
        var changes = game.changeController(SeatNo.of(seat), PlayerController.human("human"),
                new ActionContext("human", harness.now(), "SYSTEM", "human", "human", loaded.version(), 0));
        var envelopes = java.util.stream.IntStream.range(0, changes.size()).mapToObj(index ->
                com.wolfmind.domain.game.event.EventEnvelope.of(changes.get(index), ID, loaded.version() + index + 1)).toList();
        harness.repository().commit(ID, loaded.version(), envelopes,
                com.wolfmind.application.port.GameRepositoryPort.SnapshotPolicy.INTERVAL);
        var planner = new TurnPlanner(harness.repository(), harness.eventStore(), SpeechPacing.NONE);
        var plan = planner.plan(ID);
        assertTrue(plan.tasks().stream().noneMatch(t -> t.kind() == TurnTask.Kind.ACTION));
        var deadline = plan.tasks().stream().filter(t -> t.kind() == TurnTask.Kind.TIMEOUT).findFirst().orElseThrow();
        var service = new com.wolfmind.application.game.TimeoutApplicationService(harness.repository(), harness.lock(),
                harness.publisher(), harness.time(), planner);
        int before = harness.repository().load(ID).version();
        harness.setClockTo(deadline.deadline() - 1);
        service.execute(deadline);
        assertEquals(before, harness.repository().load(ID).version());
        harness.setClockTo(deadline.deadline());
        service.execute(deadline);
        var after = harness.repository().load(ID).game().snapshot();
        assertNotEquals(seat, after.currentTurn().seatNo().value());
        assertEquals(1, harness.eventStream(ID).stream().filter(e -> e.version() > before
                && e.type().equals("SPEECH_SKIPPED")).count());
        service.execute(deadline);
        assertEquals(after, harness.repository().load(ID).game().snapshot());
    }
}
