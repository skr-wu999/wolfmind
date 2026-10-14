package com.wolfmind.application.game;

import com.wolfmind.application.port.*;
import com.wolfmind.application.scheduling.TurnPlanner;
import com.wolfmind.application.scheduling.TurnTask;
import com.wolfmind.domain.game.event.DomainEvent;
import com.wolfmind.domain.game.model.*;
import java.util.List;

public final class TimeoutApplicationService {
    private final GameRepositoryPort repository;
    private final GameLockPort lock;
    private final EventPublisherPort publisher;
    private final TimePort time;
    private final TurnPlanner planner;

    public TimeoutApplicationService(GameRepositoryPort repository, GameLockPort lock,
                                     EventPublisherPort publisher, TimePort time, TurnPlanner planner) {
        this.repository = repository; this.lock = lock; this.publisher = publisher; this.time = time; this.planner = planner;
    }

    public void execute(TurnTask task) {
        var token = lock.tryLock(task.gameId()).orElseThrow(() -> GameException.Factory.busy(GameId.of(task.gameId())));
        try {
            if (!planner.current(task)) return;
            Game game = repository.load(task.gameId()).game();
            if (task.kind() == TurnTask.Kind.ADVANCE) {
                commit(task, game, game.version(), game.advance(context(task, game.version())));
                return;
            }
            long now = time.nowMillis();
            if (now < task.deadline()) return;
            List<SkipAction> skips = game.timeoutSkips(now, true).skips();
            for (SkipAction skip : skips) {
                if (!planner.current(task)) break;
                game = repository.load(task.gameId()).game();
                int version = game.version();
                commit(task, game, version, game.handle(skip, context(task, version)));
            }
        } finally { lock.unlock(token); }
    }

    private ActionContext context(TurnTask task, int version) {
        String command = "timeout:" + task.taskId() + ":" + version;
        return new ActionContext(CommandIdentifiers.eventIdBase(task.gameId(), version + 1), time.nowMillis(),
                ActionContext.SYSTEM_ACTOR, command, command, version, 0);
    }
    private void commit(TurnTask task, Game game, int version, List<DomainEvent> events) {
        if (events.isEmpty()) return;
        var envelopes = EventEnvelopes.wrap(task.gameId(), version + 1, events);
        repository.commit(task.gameId(), version, envelopes, GameRepositoryPort.SnapshotPolicy.INTERVAL);
        EventPublication.publish(publisher, envelopes);
    }
}
