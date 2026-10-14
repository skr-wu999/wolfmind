package com.wolfmind.application.port;

import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameSnapshot;
import java.util.List;

public interface MatchArchivePort {
    List<EventEnvelope> read(String gameId, int toVersion);
    void append(List<EventEnvelope> events, long now);
    boolean completedThrough(String gameId, int version);
    void complete(String gameId, List<EventEnvelope> events, GameSnapshot state, long now);
}
