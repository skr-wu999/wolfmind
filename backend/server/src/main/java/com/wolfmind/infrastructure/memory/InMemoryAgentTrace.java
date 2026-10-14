package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.agent.trace.AgentTracePort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import com.wolfmind.application.port.MetricsPort;

/**
 * 内存轨迹实现：Phase 3 的最小可用形态，SQLite {@code agent_memory} 的同形替换点。
 *
 * <p>两条硬约束（agent-runtime.md §12、event-design.md §1 结论 3）：
 * <ol>
 *   <li><b>类型前缀必须是 {@code AGENT_}</b>，且永不写入 {@code game:{id}:events}——
 *       本类只存内存，没有任何通往对局事件流的路径，这一点由结构保证而不是靠约定；</li>
 *   <li><b>不存完整 prompt 与私有 payload</b>：写入前对 payload 做长度收敛，
 *       避免日志/轨迹成为隐藏信息泄漏渠道。</li>
 * </ol>
 */
public final class InMemoryAgentTrace implements AgentTracePort {

    /** 单个 payload 值的长度上限：轨迹是分析用的摘要，不是原文仓库。 */
    private static final int VALUE_MAX_LENGTH = 200;

    /** 每局每座位的轨迹条数上限，防止长局无界增长。 */
    private static final int PER_SEAT_LIMIT = 2_000;

    private final Map<String, List<AgentTraceEvent>> byKey = new LinkedHashMap<>();
    private final Map<String, Long> finished = new LinkedHashMap<>();
    private final int finishedLimit;
    private final long retentionMillis;
    private final LongSupplier clock;
    private final MetricsPort metrics;
    private Consumer<String> onEviction = ignored -> {};

    public InMemoryAgentTrace() {
        this(20, 3_600_000L, System::currentTimeMillis, MetricsPort.NOOP);
    }

    public InMemoryAgentTrace(int finishedLimit, long retentionMillis, LongSupplier clock, MetricsPort metrics) {
        if (finishedLimit < 1 || retentionMillis < 1) throw new IllegalArgumentException("Invalid trace retention");
        this.finishedLimit = finishedLimit;
        this.retentionMillis = retentionMillis;
        this.clock = clock;
        this.metrics = metrics;
    }

    public synchronized void onEviction(Consumer<String> listener) { onEviction = listener; }

    @Override
    public synchronized void record(AgentTraceEvent event) {
        maintain();
        if (finished.containsKey(event.gameId())) return;
        AgentTraceEvent sanitized = sanitize(event);
        byKey.compute(event.gameId() + ":" + event.playerId(), (key, existing) -> {
            List<AgentTraceEvent> list = existing == null ? new ArrayList<>() : new ArrayList<>(existing);
            list.add(sanitized);
            if (list.size() > PER_SEAT_LIMIT) {
                list = new ArrayList<>(list.subList(list.size() - PER_SEAT_LIMIT, list.size()));
            }
            return List.copyOf(list);
        });
        updateMetrics();
    }

    @Override
    public synchronized List<AgentTraceEvent> traces(String gameId, String playerId) {
        maintain();
        if (playerId == null) {
            String prefix = gameId + ":";
            return byKey.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
                    .flatMap(entry -> entry.getValue().stream()).toList();
        }
        return byKey.getOrDefault(gameId + ":" + playerId, List.of());
    }

    /** 全部轨迹，供测试断言"没有任何领域事件类型"与"类型前缀都是 AGENT_"。 */
    public synchronized List<AgentTraceEvent> all() {
        maintain();
        List<AgentTraceEvent> all = new ArrayList<>();
        byKey.values().forEach(all::addAll);
        return List.copyOf(all);
    }

    public int totalCount() {
        return all().size();
    }

    public long countOf(TraceEventType type) {
        return all().stream().filter(event -> event.type() == type).count();
    }

    public boolean hasType(TraceEventType type) {
        return all().stream().anyMatch(event -> event.type() == type);
    }

    /**
     * 全部轨迹的类型前缀是否都是 {@code AGENT_}。
     *
     * <p>它是"决策事件不进入对局事件流"（event-design.md §1 结论 3）的机器校验：
     * 只要有一个类型不带 {@code AGENT_} 前缀，就说明有领域事件被误记成决策事件。
     */
    public boolean allWireTypesAgentPrefixed() {
        return all().stream().allMatch(event -> event.wireType().startsWith("AGENT_"));
    }

    /** 清空某局全部轨迹（对局结束归档后调用）。 */
    public synchronized void clearGame(String gameId) {
        String prefix = gameId + ":";
        byKey.keySet().removeIf(key -> key.startsWith(prefix));
        finished.remove(gameId);
        updateMetrics();
    }

    @Override
    public synchronized void finishGame(String gameId) {
        finished.putIfAbsent(gameId, clock.getAsLong());
        maintain();
    }

    public synchronized List<String> finishedGameIds() {
        maintain();
        return List.copyOf(finished.keySet());
    }

    @Override
    public synchronized void maintain() {
        long now = clock.getAsLong();
        List<String> expired = finished.entrySet().stream()
                .filter(entry -> now - entry.getValue() >= retentionMillis).map(Map.Entry::getKey).toList();
        expired.forEach(this::evict);
        while (finished.size() > finishedLimit) evict(finished.keySet().iterator().next());
        updateMetrics();
    }

    private void evict(String gameId) {
        clearGame(gameId);
        metrics.counter("wolfmind.agent.trace.evicted-games", 1);
        onEviction.accept(gameId);
    }

    private void updateMetrics() {
        metrics.gauge("wolfmind.agent.trace.live-events", byKey.values().stream().mapToLong(List::size).sum());
        metrics.gauge("wolfmind.agent.trace.finished-games", finished.size());
    }

    /** 收敛 payload：长字符串截断，并记录被截断的事实而不是静默丢失。 */
    public static AgentTraceEvent sanitize(AgentTraceEvent event) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        event.payload().forEach((key, value) -> {
            if (value instanceof String text && text.length() > VALUE_MAX_LENGTH) {
                payload.put(key, text.substring(0, VALUE_MAX_LENGTH) + "…(truncated)");
            } else {
                payload.put(key, value);
            }
        });
        return new AgentTraceEvent(
                event.type(), event.gameId(), event.playerId(), event.version(),
                event.phase(), event.round(), event.occurredAt(), payload);
    }
}
