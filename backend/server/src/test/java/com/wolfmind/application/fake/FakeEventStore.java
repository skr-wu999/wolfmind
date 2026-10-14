package com.wolfmind.application.fake;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 假事件存储：只实现 {@link EventStorePort} 的契约语义，不涉及 Redis。
 *
 * <p>它保留真实实现最关键的一条保证：显式版本即条目 ID，追加必须从 {@code expectedVersion + 1} 起连续，
 * 任一条冲突整批不写。因此应用层的版本编排（尤其"一次命令多条事件连续写入"）在这里能被真实验证。
 */
public final class FakeEventStore implements EventStorePort {

    private final Map<String, List<EventEnvelope>> streams = new LinkedHashMap<>();

    @Override
    public void append(String gameId, int expectedVersion, List<EventEnvelope> events) {
        if (events == null || events.isEmpty()) {
            throw new IllegalArgumentException("events 不能为空（INV-11）");
        }
        List<EventEnvelope> stream = new ArrayList<>(streams.getOrDefault(gameId, List.of()));
        int current = currentVersionOf(stream);
        if (current != expectedVersion) {
            throw GameException.Factory.versionConflict(expectedVersion, current);
        }
        // 先整体校验再写入：不允许出现半个批次，否则事件流会有版本空洞。
        int expected = expectedVersion + 1;
        for (EventEnvelope envelope : events) {
            if (envelope.version() != expected) {
                throw GameException.Factory.replayOutOfOrder(expected, envelope.version());
            }
            expected++;
        }
        stream.addAll(events);
        streams.put(gameId, List.copyOf(stream));
    }

    @Override
    public List<EventEnvelope> read(String gameId, int fromVersion, int toVersion) {
        return streams.getOrDefault(gameId, List.of()).stream()
                .filter(envelope -> envelope.version() >= fromVersion && envelope.version() <= toVersion)
                .sorted((left, right) -> Integer.compare(left.version(), right.version()))
                .toList();
    }

    @Override
    public int currentVersion(String gameId) {
        return currentVersionOf(streams.getOrDefault(gameId, List.of()));
    }

    @Override
    public boolean exists(String gameId) {
        return streams.containsKey(gameId);
    }

    /** 测试专用：制造不连续的流，用于验证恢复路径拒绝"带病恢复"。 */
    public void putUnchecked(String gameId, EventEnvelope... envelopes) {
        List<EventEnvelope> stream = new ArrayList<>(streams.getOrDefault(gameId, List.of()));
        stream.addAll(List.of(envelopes));
        streams.put(gameId, List.copyOf(stream));
    }

    /** 测试断言用：某个版本的事件。 */
    public EventEnvelope at(String gameId, int version) {
        return streams.getOrDefault(gameId, List.of()).stream()
                .filter(envelope -> envelope.version() == version)
                .findFirst()
                .orElseThrow(() -> new AssertionError("事件流中没有版本 " + version));
    }

    private static int currentVersionOf(List<EventEnvelope> stream) {
        if (stream.isEmpty()) {
            return 0;
        }
        return stream.stream().mapToInt(EventEnvelope::version).max().orElse(0);
    }
}
