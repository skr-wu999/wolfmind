package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.infrastructure.metrics.InProcessMetrics;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AgentTraceRetentionTest {
    private final AtomicLong now = new AtomicLong(1_000);
    private final InMemoryAgentTrace trace = new InMemoryAgentTrace(2, 100, now::get, new InProcessMetrics());

    static AgentTracePort.AgentTraceEvent event(String gameId) {
        return new AgentTracePort.AgentTraceEvent(AgentTracePort.TraceEventType.AGENT_TURN_FINISHED,
                gameId, "p_1", 10, "DAY_VOTE", 1, 1_000, Map.of("promptTokens", 100));
    }

    @Test void completedGamesHaveACountLimitWithoutEvictingActiveGames() {
        trace.record(event("active"));
        for (int i = 0; i < 30; i++) {
            String gameId = "finished-" + i;
            trace.record(event(gameId));
            trace.finishGame(gameId);
        }
        assertEquals(2, trace.finishedGameIds().size());
        assertEquals(3, trace.totalCount());
        assertEquals(1, trace.traces("active", "p_1").size());
        assertTrue(trace.traces("finished-0", "p_1").isEmpty());
        assertEquals(1, trace.traces("finished-29", "p_1").size());
    }

    @Test void repeatedCompletionAndLateRecordsDoNotExtendRetention() {
        trace.record(event("finished"));
        trace.finishGame("finished");
        now.addAndGet(99);
        trace.finishGame("finished");
        trace.record(event("finished"));
        assertEquals(1, trace.totalCount());
        now.incrementAndGet();
        trace.maintain();
        assertEquals(0, trace.totalCount());
        assertTrue(trace.finishedGameIds().isEmpty());
    }

    @Test void maintenanceExpiresFinishedTracesEvenWhenNobodyReadsThem() {
        trace.record(event("active"));
        trace.record(event("finished"));
        trace.finishGame("finished");
        now.addAndGet(100);
        trace.maintain();
        assertEquals(1, trace.totalCount());
        assertEquals("active", trace.all().getFirst().gameId());
    }
}
