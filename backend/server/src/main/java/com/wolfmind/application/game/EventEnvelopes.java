package com.wolfmind.application.game;

import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.event.EventEnvelope;

import java.util.ArrayList;
import java.util.List;

/**
 * 领域事件 → 事件信封的唯一转换点（event-design.md §2）。
 *
 * <p>版本由应用层裁决：一次命令产生的全部事件必须连续占用 {@code expectedVersion + 1} 起的版本号，
 * 不允许空洞（INV-11）。领域在自己那一侧也按相同规则推进版本，两者必须一致——
 * 信封版本是持久化与重放的定位依据，因此这里是权威值。
 */
public final class EventEnvelopes {

    private EventEnvelopes() {}

    /**
     * 按顺序封装事件。
     *
     * @param firstVersion 第一条事件的聚合版本，通常为 {@code expectedVersion + 1}
     */
    public static List<EventEnvelope> wrap(String gameId, int firstVersion, List<DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        List<EventEnvelope> envelopes = new ArrayList<>(events.size());
        int version = firstVersion;
        for (DomainEvent event : events) {
            envelopes.add(EventEnvelope.of(event, gameId, version));
            version++;
        }
        return List.copyOf(envelopes);
    }
}
