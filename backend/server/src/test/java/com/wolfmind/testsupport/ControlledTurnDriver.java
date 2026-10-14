package com.wolfmind.testsupport;

import com.wolfmind.application.agent.model.TurnSignal;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.game.TimeoutApplicationService;
import com.wolfmind.application.port.*;
import com.wolfmind.application.scheduling.SpeechPacing;
import com.wolfmind.application.scheduling.TurnPlanner;
import com.wolfmind.application.scheduling.TurnTask;

/** Synchronous fixture for replay/archive tests; concurrency and leases use EventDrivenTurnScheduler tests. */
public final class ControlledTurnDriver {
    private final GameRepositoryPort repository;
    private final AIControllerAdapter adapter;
    private final TimePort clock;
    private final TurnPlanner planner;
    private final TimeoutApplicationService timeout;

    public ControlledTurnDriver(GameRepositoryPort repository, AIControllerAdapter adapter, TimePort clock,
                                EventPublisherPort publisher, EventStorePort events, GameLockPort lock) {
        this(repository, adapter, clock, publisher, events, lock, SpeechPacing.NONE);
    }

    public ControlledTurnDriver(GameRepositoryPort repository, AIControllerAdapter adapter, TimePort clock,
                                EventPublisherPort publisher, EventStorePort events, GameLockPort lock, SpeechPacing pacing) {
        this.repository = repository;
        this.adapter = adapter;
        this.clock = clock;
        planner = new TurnPlanner(repository, events, pacing);
        timeout = new TimeoutApplicationService(repository, lock, publisher, clock, planner);
    }

    public boolean step(String gameId) {
        var plan = planner.plan(gameId);
        int before = plan.snapshot().version();
        var expired = plan.tasks().stream().filter(t -> t.kind() == TurnTask.Kind.TIMEOUT
                && t.readyAt() <= clock.nowMillis()).findFirst();
        if (expired.isPresent()) timeout.execute(expired.get());
        else {
            var ready = plan.tasks().stream().filter(t -> t.kind() != TurnTask.Kind.TIMEOUT
                    && t.readyAt() <= clock.nowMillis() && t.deadline() > clock.nowMillis()).findFirst();
            if (ready.isEmpty()) return false;
            var task = ready.get();
            if (task.kind() == TurnTask.Kind.ADVANCE) timeout.execute(task);
            else adapter.dispatch(new TurnSignal(gameId, task.playerId(), task.seatNo(), task.phase(),
                    plan.snapshot().turnIndex(), task.deadline(), task.dispatchVersion(), task.controllerType()), null);
        }
        return repository.load(gameId).version() > before;
    }
}
