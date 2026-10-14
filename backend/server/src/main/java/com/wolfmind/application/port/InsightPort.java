package com.wolfmind.application.port;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.event.EventEnvelope;
import java.util.List;

public interface InsightPort {
    void record(GameSnapshot state, List<EventEnvelope> events, long now);
}
