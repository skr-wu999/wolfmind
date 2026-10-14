package com.wolfmind.testsupport;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;

import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

public record ArchivedMatchFixture(InfrastructureGameHarness harness, String gameId,
                                    List<EventEnvelope> events, GameSnapshot state) {
    public static ArchivedMatchFixture create(String gameId, boolean finished) {
        var h = new InfrastructureGameHarness(1_730_000_000_000L);
        h.startNineAiGame(gameId, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 42L);
        if (finished) {
            var views = new AgentViewAdapter(h.viewQuery());
            var trace = new InMemoryAgentTrace();
            var gateway = new ToolGatewayAdapter(views, h.viewQuery(), h.submitService(), h.idempotency(), trace, h.time());
            var runtime = new AgentRuntimeImpl(views, new WolfMindStrategy(gateway), gateway,
                    new InMemoryMemoryPort(), trace, null, h.time());
            var driver = new ControlledTurnDriver(h.repository(), new AIControllerAdapter(runtime, views, gateway,
                    h.time(), AgentConfig.ruleBaseline("archive-fixture")), h.time(), h.publisher(), h.eventStore(), h.lock());
            for (int step = 0; step < 2000 && h.repository().load(gameId).game().status() == GameStatus.RUNNING; step++) {
                h.advanceClock(200L); driver.step(gameId);
            }
            assertEquals(GameStatus.FINISHED, h.repository().load(gameId).game().status());
        } else {
            var loaded = h.repository().load(gameId);
            var ctx = new ActionContext("abort-" + gameId, h.time().nowMillis(), "SYSTEM", "abort", "abort",
                    loaded.version(), 0L);
            var events = loaded.game().abort("archive-test", ctx).stream().map(e -> EventEnvelope.of(e, gameId, e.version())).toList();
            h.repository().commit(gameId, loaded.version(), events, GameRepositoryPort.SnapshotPolicy.FORCE);
        }
        return new ArchivedMatchFixture(h, gameId, h.eventStream(gameId), h.repository().load(gameId).game().snapshot());
    }
}
