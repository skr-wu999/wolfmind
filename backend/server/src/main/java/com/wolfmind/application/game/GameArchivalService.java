package com.wolfmind.application.game;

import com.wolfmind.application.port.*;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.*;
import java.util.List;
import java.util.function.Consumer;

/** 事件消费和定时补漏复用同一份归档核验及保留期规则。 */
public final class GameArchivalService {
    public record Result(GameSnapshot state, boolean newCompletion) { }
    private final EventStorePort source;
    private final MatchArchivePort archive;
    private final GameStateCachePort cache;
    private final Consumer<String> afterArchived;
    private final TimePort clock;
    public GameArchivalService(EventStorePort source, MatchArchivePort archive, GameStateCachePort cache,
                                Consumer<String> afterArchived, TimePort clock) {
        this.source = source; this.archive = archive; this.cache = cache; this.afterArchived = afterArchived; this.clock = clock;
    }
    public Result archiveThrough(String gameId, int version) {
        List<EventEnvelope> events = source.exists(gameId) ? source.read(gameId, 1, version) : archive.read(gameId, version);
        GameSnapshot state = CommittedEventReplay.state(gameId, events, version);
        boolean terminal = state.status() == GameStatus.FINISHED || state.status() == GameStatus.ABORTED;
        boolean complete = archive.completedThrough(gameId, version);
        archive.append(events, clock.nowMillis());
        cache.put(state);
        if (terminal) {
            archive.complete(gameId, events, state, clock.nowMillis());
            afterArchived.accept(gameId);
        }
        return new Result(state, terminal && !complete);
    }
}
