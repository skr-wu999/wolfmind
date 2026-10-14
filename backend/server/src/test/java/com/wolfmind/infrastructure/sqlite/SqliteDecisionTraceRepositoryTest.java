package com.wolfmind.infrastructure.sqlite;

import com.wolfmind.application.agent.trace.AgentTracePort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策痕迹归档的验收（database-schema.md §3.6、api-spec.md §7）。
 *
 * <p>它守两条底线：<b>重复投递不翻倍</b>（分析页的 token 与工具次数会因此失真），
 * 以及<b>只有 {@code AGENT_} 事件能进来</b>（一旦接受领域事件，
 * {@code agent_memory} 就变成对局事件的第二份副本，而 §8.3 说这是刻意隔离）。
 */
class SqliteDecisionTraceRepositoryTest {

    private SqliteDecisionTraceRepository repository;

    @BeforeEach
    void openRepository() {
        repository = SqliteDecisionTraceRepository.inMemory();
    }

    @AfterEach
    void closeRepository() {
        if (repository != null) {
            repository.close();
        }
    }

    private static AgentTracePort.AgentTraceEvent event(
            AgentTracePort.TraceEventType type, String playerId, int version, Map<String, Object> payload) {
        return new AgentTracePort.AgentTraceEvent(
                type, "g_1", playerId, version, "NIGHT_WOLF", 1, 1_730_000_000_000L + version, payload);
    }

    @Test
    @DisplayName("归档后可以按对局与座位读回，且 payload 逐字段相等")
    void archiveRoundTripsTheStructuredPayload() {
        AgentTracePort.AgentTraceEvent source = event(
                AgentTracePort.TraceEventType.AGENT_LLM_INVOKED, "p_3", 12,
                Map.of("model", "deepseek-flash", "promptTokens", 1200, "completionTokens", 300, "latencyMs", 4200));

        assertTrue(repository.archive(source));
        List<AgentTracePort.AgentTraceEvent> loaded = repository.load("g_1", "p_3");

        assertEquals(1, loaded.size());
        AgentTracePort.AgentTraceEvent restored = loaded.get(0);
        assertEquals(source.type(), restored.type());
        assertEquals(source.gameId(), restored.gameId());
        assertEquals(source.playerId(), restored.playerId());
        assertEquals(source.version(), restored.version());
        assertEquals(source.phase(), restored.phase());
        assertEquals(source.round(), restored.round());
        assertEquals(source.occurredAt(), restored.occurredAt());
        assertEquals(Map.of("model", "deepseek-flash", "promptTokens", 1200,
                        "completionTokens", 300, "latencyMs", 4200),
                restored.payload(), "JSON 往返后 payload 必须与写入时逐字段相等");
    }

    @Test
    @DisplayName("同一事件重复归档只留一行（ux_agent_memory_trace 是幂等保证）")
    void repeatedArchiveIsIdempotent() {
        AgentTracePort.AgentTraceEvent source = event(
                AgentTracePort.TraceEventType.AGENT_TURN_FINISHED, "p_1", 41,
                Map.of("chosen", "VOTE@5:0.82", "submitted", true, "fallbackUsed", false));

        assertTrue(repository.archive(source), "首次写入应新增一行");
        assertFalse(repository.archive(source), "重复投递必须被唯一索引忽略");
        assertFalse(repository.archive(source));

        assertEquals(1, repository.count("g_1"));
        assertEquals(1, repository.load("g_1", "p_1").size());
    }

    @Test
    @DisplayName("内容不同的两条轨迹不会被幂等键吞掉")
    void distinctTracesKeepDistinctRows() {
        assertTrue(repository.archive(event(AgentTracePort.TraceEventType.AGENT_TOOL_CALLED, "p_2", 20,
                Map.of("tool", "QueryGameStateTool", "decision", "ALLOWED"))));
        assertTrue(repository.archive(event(AgentTracePort.TraceEventType.AGENT_TOOL_CALLED, "p_2", 21,
                Map.of("tool", "SubmitVoteTool", "decision", "ALLOWED"))));

        assertEquals(2, repository.count("g_1"));
    }

    @Test
    @DisplayName("按座位过滤；playerId 为 null 时返回整局")
    void loadFiltersBySeat() {
        repository.archive(event(AgentTracePort.TraceEventType.AGENT_TURN_STARTED, "p_1", 3, Map.of()));
        repository.archive(event(AgentTracePort.TraceEventType.AGENT_TURN_STARTED, "p_2", 4, Map.of()));

        assertEquals(1, repository.load("g_1", "p_1").size());
        assertEquals(2, repository.load("g_1", null).size());
        assertEquals(0, repository.load("g_1", "p_9").size());
    }

    @Test
    @DisplayName("并发归档不丢行：投票阶段多个座位在不同线程同时写同一连接")
    void concurrentArchiveKeepsEveryTrace() throws Exception {
        int threads = 8;
        int perThread = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                String playerId = "p_" + (t + 1);
                results.add(pool.submit(() -> {
                    start.await();
                    int written = 0;
                    for (int i = 0; i < perThread; i++) {
                        if (repository.archive(event(AgentTracePort.TraceEventType.AGENT_STRATEGY_DECIDED,
                                playerId, i, Map.of("chosen", "VOTE@" + i)))) {
                            written++;
                        }
                    }
                    return written;
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> result : results) {
                total += result.get(30, TimeUnit.SECONDS);
            }
            assertEquals(threads * perThread, total, "每次归档都应报告新增一行");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads * perThread, repository.count("g_1"));
        assertEquals(perThread, repository.load("g_1", "p_1").size());
    }

    @Test
    @DisplayName("预留列恒为 NULL（database-schema.md §6.6 / OP-D10）")
    void reservedColumnsStayNull() {
        repository.archive(event(AgentTracePort.TraceEventType.AGENT_CONTEXT_BUILT, "p_1", 5,
                Map.of("candidateCount", 3)));
        assertEquals(0, repository.reservedColumnsUsed());
    }

    @Test
    @DisplayName("轨迹类型目录的封闭性：全部带 AGENT_ 前缀")
    void everyTraceTypeIsPrefixedSoDomainEventsCannotEnter() {
        // 归档器只接受 AGENT_ 前缀（database-schema.md §8.3：轨迹与 game_events 的无交集是刻意隔离）。
        // 由于 TraceEventType 是封闭枚举，这一点只能通过"目录本身的前缀"来守住：
        // 一旦有人往目录里加了一个领域事件名，这条断言会立刻失败。
        for (AgentTracePort.TraceEventType type : AgentTracePort.TraceEventType.values()) {
            assertTrue(type.name().startsWith("AGENT_"),
                    "轨迹事件类型必须带 AGENT_ 前缀，否则它会被归档成 agent_memory 里的领域事件副本：" + type);
        }
    }
}
