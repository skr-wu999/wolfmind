package com.wolfmind.application.scheduling;

import com.wolfmind.domain.game.event.EventEnvelope;
import java.util.List;

public interface EventConsumptionPort extends AutoCloseable {
    record Delivery(String gameId, List<EventEnvelope> events, String receipt) {
        public Delivery(String gameId, List<EventEnvelope> events) { this(gameId, events, null); }
        public Delivery { events = List.copyOf(events); }
    }
    List<Delivery> read(int limit, long blockMillis);
    void acknowledge(Delivery delivery);
    void forget(String gameId);
    default void failed(Delivery delivery, String errorCode) { }
    default long blockedCount() { return 0; }
    default long pendingCount() { return 0; }
    default void release(Delivery delivery) { }
    @Override default void close() { }
}
