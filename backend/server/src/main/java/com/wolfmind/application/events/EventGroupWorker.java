package com.wolfmind.application.events;

import com.wolfmind.application.port.MetricsPort;
import com.wolfmind.application.scheduling.EventConsumptionPort;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/** 每组独立线程；处理成功才确认，暂时故障保留待处理消息。 */
public final class EventGroupWorker implements AutoCloseable {
    private final String group;
    private final EventConsumptionPort source;
    private final Predicate<EventConsumptionPort.Delivery> handler;
    private final MetricsPort metrics;
    private final AtomicBoolean active = new AtomicBoolean();
    private Thread thread;

    public EventGroupWorker(String group, EventConsumptionPort source,
                            Predicate<EventConsumptionPort.Delivery> handler, MetricsPort metrics) {
        this.group = group; this.source = source; this.handler = handler; this.metrics = metrics;
    }

    public int consumeOnce(long blockMillis) {
        int processed = 0;
        for (var delivery : source.read(200, blockMillis)) {
            try {
                boolean settled = handler.test(delivery);
                source.acknowledge(delivery);
                if (settled) source.forget(delivery.gameId());
                processed += delivery.events().size();
            } catch (RuntimeException failure) {
                metrics.counter("wolfmind.consumer." + group + ".failures", 1);
                if (failure instanceof com.wolfmind.domain.game.model.GameException
                        || failure instanceof IllegalArgumentException) source.failed(delivery, "INVALID_EVENT");
            } finally {
                source.release(delivery);
            }
        }
        metrics.gauge("wolfmind.consumer." + group + ".blocked", source.blockedCount());
        metrics.gauge("wolfmind.consumer." + group + ".pending", source.pendingCount());
        return processed;
    }

    public void start() {
        if (!active.compareAndSet(false, true)) return;
        thread = new Thread(() -> {
            while (active.get()) {
                try { consumeOnce(250); }
                catch (RuntimeException failure) { metrics.counter("wolfmind.consumer." + group + ".read.failures", 1); }
                try { Thread.sleep(100); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }, "wolfmind-" + group);
        thread.setDaemon(true); thread.start();
    }

    @Override public void close() {
        active.set(false);
        if (thread != null) {
            thread.interrupt();
            try { thread.join(2000L); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        source.close();
    }
}
