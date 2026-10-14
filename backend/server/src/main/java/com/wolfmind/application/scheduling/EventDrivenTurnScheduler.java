package com.wolfmind.application.scheduling;

import com.wolfmind.application.agent.model.TurnSignal;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.game.TimeoutApplicationService;
import com.wolfmind.application.port.MetricsPort;
import com.wolfmind.application.port.TimePort;
import com.wolfmind.domain.game.model.GameStatus;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class EventDrivenTurnScheduler implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(EventDrivenTurnScheduler.class.getName());
    private final EventConsumptionPort events;
    private final TurnTaskStorePort tasks;
    private final TurnPlanner planner;
    private final AIControllerAdapter adapter;
    private final TimeoutApplicationService timeout;
    private final TimePort clock;
    private final MetricsPort metrics;
    private final String owner = UUID.randomUUID().toString();
    private final ThreadPoolExecutor agents;
    private final ThreadPoolExecutor maintenance;
    private final ScheduledThreadPoolExecutor timers;
    private final Set<String> enqueued = ConcurrentHashMap.newKeySet();
    private final Set<String> timed = ConcurrentHashMap.newKeySet();
    private final Map<String, ScheduledFuture<?>> wakeups = new ConcurrentHashMap<>();
    private final Map<String, Future<?>> running = new ConcurrentHashMap<>();
    private final AtomicBoolean active = new AtomicBoolean();
    private Thread consumer;

    public EventDrivenTurnScheduler(EventConsumptionPort events, TurnTaskStorePort tasks, TurnPlanner planner,
                                     AIControllerAdapter adapter, TimeoutApplicationService timeout, TimePort clock,
                                     MetricsPort metrics, int concurrency, int queueSize) {
        this.events = events; this.tasks = tasks; this.planner = planner; this.adapter = adapter;
        this.timeout = timeout; this.clock = clock; this.metrics = metrics;
        agents = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), daemonFactory("wolfmind-turn"), new ThreadPoolExecutor.AbortPolicy());
        maintenance = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueSize), daemonFactory("wolfmind-turn-timeout"), new ThreadPoolExecutor.AbortPolicy());
        timers = new ScheduledThreadPoolExecutor(2, daemonFactory("wolfmind-turn-deadline"));
        timers.setRemoveOnCancelPolicy(true);
    }
    private static ThreadFactory daemonFactory(String name) {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        return runnable -> { Thread t = new Thread(runnable, name+"-"+count.incrementAndGet()); t.setDaemon(true); return t; };
    }
    public void start() {
        start(true);
    }
    public void start(boolean consumeEvents) {
        if (!active.compareAndSet(false, true)) return;
        if (consumeEvents) {
            consumer = daemonFactory("wolfmind-agent-events").newThread(() -> {
                while (active.get()) try { consumeOnce(500); }
                catch (RuntimeException failure) {
                    if (!active.get()) break;
                    metrics.counter("wolfmind.scheduler.consume.failures", 1);
                    LOG.warning("Agent event scheduling failed; delivery unacknowledged: " + failure.getClass().getSimpleName());
                    try { Thread.sleep(250); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            });
            consumer.start();
        }
        timers.scheduleWithFixedDelay(() -> {
            if (!active.get()) return;
            try {
                metrics.counter("wolfmind.scheduler.lease.recovered", tasks.recover(clock.nowMillis()));
                metrics.gauge("wolfmind.scheduler.blocked",events.blockedCount());
                pumpTimeouts();
                // Recover task wakeups after restart; this reads only task indexes, never RUNNING game lists.
                pumpActions();
            } catch (RuntimeException failure) {
                if (active.get()) LOG.warning("Task index recovery failed: " + failure.getClass().getSimpleName());
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
    }

    public int consumeOnce(long blockMillis) {
        var deliveries = events.read(200, blockMillis);
        int count = 0;
        for (var delivery : deliveries) {
            try {
            TurnPlanner.Plan plan = planner.plan(delivery.gameId());
            Set<String> registered = new HashSet<>();
            tasks.tasks(delivery.gameId()).forEach(t -> registered.add(t.taskId()));
            metrics.counter("wolfmind.scheduler.tasks.duplicates", plan.tasks().stream().filter(t -> registered.contains(t.taskId())).count());
            tasks.reconcile(delivery.gameId(), plan.tasks());
            events.acknowledge(delivery);
            count += delivery.events().size();
            cancelSuperseded(delivery.gameId());
            for (var event : delivery.events()) if (event.type().equals("PLAYER_CONTROLLER_CHANGED")
                    && event.payload().get("seatNo") instanceof Number number) {
                var player = plan.snapshot().playerAt(com.wolfmind.domain.game.model.SeatNo.of(number.intValue()));
                if (player.controller().type() == com.wolfmind.domain.game.model.ControllerType.HUMAN)
                    adapter.onSeatTakenOverByHuman(delivery.gameId(), player.playerId().value());
            }
            if (plan.snapshot().status() != GameStatus.RUNNING) {
                if (delivery.events().getLast().version() >= plan.snapshot().version()) {
                    events.forget(delivery.gameId()); adapter.onGameFinished(delivery.gameId());
                    tasks.purge(delivery.gameId());
                }
            } else for (TurnTask task : plan.tasks()) scheduleWakeup(task);
            } catch (RuntimeException failure) {
                metrics.counter("wolfmind.scheduler.delivery.failures",1);
                if (failure instanceof com.wolfmind.domain.game.model.GameException gameFailure
                        && gameFailure.code().name().startsWith("REPLAY_")
                        || failure instanceof IllegalStateException && failure.getMessage() != null
                        && failure.getMessage().startsWith("Missing committed window"))
                    events.failed(delivery,failure.getClass().getSimpleName());
                LOG.warning("Event delivery retained: "+delivery.gameId()+" "+failure.getClass().getSimpleName());
            } finally {
                events.release(delivery);
            }
        }
        metrics.counter("wolfmind.scheduler.events", count);
        pumpTimeouts(); pumpActions();
        return count;
    }
    private void scheduleWakeup(TurnTask task) {
        if (task.readyAt() <= clock.nowMillis()) return;
        // Only one timer per deterministic task identity; repeated events cannot extend its deadline.
        if (!timed.add(task.taskId())) return;
        var future = timers.schedule(() -> {
            timed.remove(task.taskId());
            wakeups.remove(task.taskId());
            if (task.kind() == TurnTask.Kind.TIMEOUT) pumpTimeouts(); else pumpActions();
        }, Math.max(0, task.readyAt() - clock.nowMillis()), TimeUnit.MILLISECONDS);
        wakeups.put(task.taskId(), future);
    }
    private void cancelSuperseded(String gameId) {
        for (TurnTask task : tasks.tasks(gameId)) if (!task.active()) {
            if (task.status() == TurnTask.Status.CANCELLED || task.status() == TurnTask.Status.EXPIRED) {
                Future<?> future = running.remove(task.taskId());
                if (future != null) future.cancel(true);
            }
            ScheduledFuture<?> wakeup = wakeups.remove(task.taskId());
            if (wakeup != null) wakeup.cancel(false);
            timed.remove(task.taskId());
        }
    }
    public void pumpActions() {
        for (TurnTask task : tasks.due(clock.nowMillis(), false, 200)) {
            if (!enqueued.add(task.taskId())) continue;
            try {
                FutureTask<Void> future = new FutureTask<>(() -> { execute(task); return null; }) {
                    @Override protected void done() {
                        running.remove(task.taskId(), this); enqueued.remove(task.taskId());
                        agents.remove(this); maintenance.remove(this);
                    }
                };
                running.put(task.taskId(), future);
                (task.kind() == TurnTask.Kind.ACTION ? agents : maintenance).execute(future);
            } catch (RejectedExecutionException full) {
                enqueued.remove(task.taskId()); running.remove(task.taskId());
                metrics.counter("wolfmind.scheduler.queue.full", 1);
            }
        }
        metrics.gauge("wolfmind.scheduler.queue", agents.getQueue().size());
        metrics.gauge("wolfmind.scheduler.running", agents.getActiveCount());
    }
    public void pumpTimeouts() {
        for (TurnTask task : tasks.due(clock.nowMillis(), true, 200)) {
            if (!enqueued.add(task.taskId())) continue;
            try { maintenance.execute(() -> execute(task)); }
            catch (RejectedExecutionException full) { enqueued.remove(task.taskId()); break; }
        }
    }
    public void reconcileGame(String gameId) {
        var plan = planner.plan(gameId);
        tasks.reconcile(gameId, plan.tasks()); cancelSuperseded(gameId);
        plan.tasks().forEach(this::scheduleWakeup);
        pumpTimeouts(); pumpActions();
    }
    private void execute(TurnTask proposed) {
        TurnTask task = null;
        try {
            task = tasks.claim(proposed.taskId(), owner, clock.nowMillis(),
                    proposed.kind() == TurnTask.Kind.ACTION ? Math.max(1, proposed.deadline()-clock.nowMillis()) : 5_000).orElse(null);
            if (task == null) return;
            if (!planner.current(task)) {
                tasks.finish(task, TurnTask.Status.CANCELLED, List.of(), "WINDOW_CLOSED"); return;
            }
            if (task.kind() != TurnTask.Kind.ACTION) {
                if (task.kind() == TurnTask.Kind.TIMEOUT)
                    metrics.timing("wolfmind.scheduler.timeout.latency",Math.max(0,clock.nowMillis()-task.deadline()));
                timeout.execute(task);
                tasks.finish(task, TurnTask.Status.SUCCEEDED, List.of(), null); return;
            }
            if (clock.nowMillis() >= task.deadline()) {
                tasks.finish(task, TurnTask.Status.EXPIRED, List.of(), "DEADLINE"); return;
            }
            metrics.timing("wolfmind.scheduler.queue.latency", Math.max(0, clock.nowMillis()-task.readyAt()));
            var state = planner.plan(task.gameId()).snapshot();
            var result = adapter.dispatch(new TurnSignal(task.gameId(), task.playerId(), task.seatNo(), task.phase(),
                    state.turnIndex(), task.deadline(), task.dispatchVersion(), task.controllerType(), task.fence()), null);
            boolean accepted = result != null && result.submittedAction() != null;
            boolean busy = result != null && "AGENT_BUSY".equals(result.errorCode());
            tasks.finish(task, accepted ? TurnTask.Status.SUCCEEDED : busy ? TurnTask.Status.READY : TurnTask.Status.WAITING_DEADLINE,
                    accepted ? result.submittedEventId() : List.of(), result == null ? "NO_RESULT" : result.errorCode());
            metrics.counter("wolfmind.scheduler.tasks.completed", 1);
            if (accepted && planner.plan(task.gameId()).snapshot().status() != GameStatus.RUNNING) {
                tasks.reconcile(task.gameId(), List.of()); cancelSuperseded(task.gameId());
                adapter.onGameFinished(task.gameId()); tasks.purge(task.gameId());
            }
        } catch (RuntimeException failure) {
            if (task != null) {
                try { tasks.finish(task, task.kind() == TurnTask.Kind.ACTION
                        ? TurnTask.Status.WAITING_DEADLINE : TurnTask.Status.READY, List.of(), failure.getClass().getSimpleName()); }
                catch (RuntimeException persistenceFailure) {
                    metrics.counter("wolfmind.scheduler.result.failures", 1);
                }
            }
            if (active.get() && !Thread.currentThread().isInterrupted())
                LOG.warning("Turn task failed: " + proposed.taskId()+" "+failure.getClass().getSimpleName());
        } finally {
            enqueued.remove(proposed.taskId());
        }
    }
    @Override public void close() {
        active.set(false);
        if (consumer != null) consumer.interrupt();
        agents.shutdownNow(); maintenance.shutdownNow(); timers.shutdownNow(); events.close();
        try {
            if (consumer != null) consumer.join(1000);
            agents.awaitTermination(500,TimeUnit.MILLISECONDS);
            maintenance.awaitTermination(500,TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
