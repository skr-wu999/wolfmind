package com.wolfmind.infrastructure.memory;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.scheduling.EventConsumptionPort;
import java.util.*;

public final class InMemoryEventConsumption implements EventConsumptionPort {
    private final EventStorePort store;
    private final GameIdCatalog catalog;
    private final Map<String, Integer> acked = new HashMap<>();
    private final Set<String> forgotten = new HashSet<>();
    private final AutoCloseable subscription;
    public InMemoryEventConsumption(EventStorePort store, GameIdCatalog catalog) {
        this.store = store; this.catalog = catalog;
        subscription = store.onAppend(id -> { synchronized (this) { notifyAll(); } });
    }
    @Override public synchronized List<Delivery> read(int limit, long blockMillis) {
        List<Delivery> result = new ArrayList<>();
        for (String id : catalog.gameIds()) {
            if (forgotten.contains(id)) continue;
            int from = acked.getOrDefault(id, 0) + 1;
            var events = store.read(id, from, from + limit - 1);
            if (!events.isEmpty()) result.add(new Delivery(id, events));
        }
        if (result.isEmpty() && blockMillis > 0) {
            try { wait(blockMillis); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return read(limit, 0);
        }
        return result;
    }
    @Override public synchronized void acknowledge(Delivery delivery) {
        if (!delivery.events().isEmpty()) acked.merge(delivery.gameId(), delivery.events().getLast().version(), Math::max);
    }
    @Override public synchronized void forget(String gameId) { forgotten.add(gameId); }
    @Override public void close() { try { subscription.close(); } catch (Exception ignored) { } }
}
