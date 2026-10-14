package com.wolfmind.application.events;

import com.wolfmind.application.game.CommittedEventReplay;
import com.wolfmind.application.port.*;
import com.wolfmind.application.scheduling.EventConsumptionPort;
import com.wolfmind.domain.game.model.GameStatus;

public final class GameInsightService {
    private final EventStorePort source;
    private final MatchArchivePort archive;
    private final InsightPort insight;
    private final TimePort clock;
    public GameInsightService(EventStorePort source, MatchArchivePort archive, InsightPort insight, TimePort clock) {
        this.source = source; this.archive = archive; this.insight = insight; this.clock = clock;
    }
    public boolean process(EventConsumptionPort.Delivery delivery) {
        int version = delivery.events().getLast().version();
        var events = source.exists(delivery.gameId()) ? source.read(delivery.gameId(), 1, version)
                : archive.read(delivery.gameId(), version);
        var state = CommittedEventReplay.state(delivery.gameId(), events, version);
        if (state.status() != GameStatus.FINISHED && state.status() != GameStatus.ABORTED) return false;
        if (!archive.completedThrough(delivery.gameId(), version)) throw new IllegalStateException("Waiting for verified archive");
        insight.record(state, events, clock.nowMillis());
        return true;
    }
}
