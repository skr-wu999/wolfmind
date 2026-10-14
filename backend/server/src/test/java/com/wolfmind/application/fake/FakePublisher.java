package com.wolfmind.application.fake;

import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 假事件发布者：按消费组记录已发布事件，并支持注入失败。
 *
 * <p>"发布失败不影响状态一致性"只有靠注入失败才能验证，因此失败开关是它的主要用途。
 */
public final class FakePublisher implements EventPublisherPort {

    private final Map<String, List<EventEnvelope>> published = new LinkedHashMap<>();
    private final List<EventEnvelope> realtime = new ArrayList<>();
    private boolean failAll;

    @Override
    public void publish(String consumerGroup, List<EventEnvelope> events) {
        if (failAll) {
            throw new IllegalStateException("模拟发布失败：" + consumerGroup);
        }
        published.computeIfAbsent(consumerGroup, key -> new ArrayList<>()).addAll(events);
    }

    @Override
    public void publishRealtime(List<EventEnvelope> events) {
        if (failAll) {
            throw new IllegalStateException("模拟实时推送失败");
        }
        realtime.addAll(events);
    }

    public void failAll(boolean value) {
        this.failAll = value;
    }

    public List<EventEnvelope> publishedTo(String consumerGroup) {
        return published.getOrDefault(consumerGroup, List.of());
    }

    public Set<String> groups() {
        return Set.copyOf(published.keySet());
    }

    public List<EventEnvelope> realtime() {
        return List.copyOf(realtime);
    }
}
