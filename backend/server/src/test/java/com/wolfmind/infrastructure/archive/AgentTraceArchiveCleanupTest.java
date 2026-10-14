package com.wolfmind.infrastructure.archive;

import com.wolfmind.application.agent.trace.AgentTracePort;
import com.wolfmind.application.port.DecisionTraceArchivePort;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.metrics.InProcessMetrics;
import com.wolfmind.infrastructure.sqlite.SqliteDecisionTraceRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AgentTraceArchiveCleanupTest {
    private static AgentTracePort.AgentTraceEvent event(String game, int version, String text) {
        return new AgentTracePort.AgentTraceEvent(AgentTracePort.TraceEventType.AGENT_TURN_FINISHED,
                game, "p_1", version, "DAY_VOTE", 1, 1_000,
                Map.of("promptTokens", 100L, "completionTokens", 50, "summary", text));
    }

    private static DecisionTraceArchivePort unreliable(SqliteDecisionTraceRepository repository, AtomicBoolean failed) {
        return new DecisionTraceArchivePort() {
            public boolean archive(AgentTracePort.AgentTraceEvent event) {
                if (failed.get()) throw new IllegalStateException("SQLite unavailable");
                return repository.archive(event);
            }
            public List<AgentTracePort.AgentTraceEvent> load(String game, String player) {
                if (failed.get()) throw new IllegalStateException("SQLite unavailable");
                return repository.load(game, player);
            }
            public int count(String game) { return repository.count(game); }
        };
    }

    @Test void cleanupPreservesArchivedAnalysisAndDoesNotDuplicateLongPayloads() {
        try (var repository = SqliteDecisionTraceRepository.inMemory()) {
            var live = new InMemoryAgentTrace();
            var trace = new ArchivingAgentTrace(live, repository);
            trace.record(event("game", 10, "公开发言".repeat(100)));
            trace.finishGame("game");
            trace.finishGame("game");
            trace.maintain();
            assertEquals(0, live.totalCount());
            assertEquals(1, repository.count("game"));
            assertEquals(1, trace.traces("game", "p_1").size());
            assertEquals("公开发言".repeat(100), trace.traces("game", "p_1").getFirst().payload().get("summary"));
        }
    }

    @Test void transientFailureKeepsTracesAndMaintenanceRetriesOnlyMissingRows() {
        try (var repository = SqliteDecisionTraceRepository.inMemory()) {
            var failed = new AtomicBoolean();
            var metrics = new InProcessMetrics();
            var live = new InMemoryAgentTrace();
            var trace = new ArchivingAgentTrace(live, unreliable(repository, failed), ignored -> {}, metrics);
            trace.record(event("game", 10, "already archived"));
            failed.set(true);
            assertDoesNotThrow(() -> trace.record(event("game", 11, "retry me")));
            assertDoesNotThrow(() -> trace.finishGame("game"));
            assertEquals(2, live.totalCount());
            assertEquals(1, repository.count("game"));
            assertEquals(1L, metrics.snapshot().gauges().get("wolfmind.agent.trace.archive.pending-games"));
            failed.set(false);
            trace.maintain();
            trace.maintain();
            assertEquals(0, live.totalCount());
            assertEquals(2, repository.count("game"));
            assertEquals(2, trace.traces("game", "p_1").size());
            assertEquals(0L, metrics.snapshot().gauges().get("wolfmind.agent.trace.archive.pending-games"));
        }
    }

    @Test void failedInsightNotificationIsRetriedWithoutWritingAnotherTrace() {
        try (var repository = SqliteDecisionTraceRepository.inMemory()) {
            var failed = new AtomicBoolean(true);
            var notified = new AtomicLong();
            var live = new InMemoryAgentTrace();
            var trace = new ArchivingAgentTrace(live, repository, ignored -> {
                if (failed.get()) throw new IllegalStateException("Redis unavailable");
                notified.incrementAndGet();
            });
            trace.record(event("game", 10, "final"));
            trace.finishGame("game");
            assertEquals(1, live.totalCount());
            failed.set(false);
            trace.maintain();
            assertEquals(1, notified.get());
            assertEquals(0, live.totalCount());
            assertEquals(1, repository.count("game"));
        }
    }

    @Test void prolongedOutageRemainsBoundedAndReportsDroppedPendingGames() {
        try (var repository = SqliteDecisionTraceRepository.inMemory()) {
            var now = new AtomicLong(1_000);
            var metrics = new InProcessMetrics();
            var live = new InMemoryAgentTrace(2, 100, now::get, metrics);
            var trace = new ArchivingAgentTrace(live, unreliable(repository, new AtomicBoolean(true)), ignored -> {}, metrics);
            for (int i = 0; i < 3; i++) {
                trace.record(event("game-" + i, 10, "pending"));
                trace.finishGame("game-" + i);
            }
            assertEquals(2, live.totalCount());
            assertEquals(1L, metrics.snapshot().counters().get("wolfmind.agent.trace.archive.dropped-games"));
            now.addAndGet(100);
            trace.maintain();
            assertEquals(0, live.totalCount());
            assertEquals(3L, metrics.snapshot().counters().get("wolfmind.agent.trace.archive.dropped-games"));
            assertEquals(0L, metrics.snapshot().gauges().get("wolfmind.agent.trace.archive.pending-games"));
        }
    }
}
