package com.wolfmind.application.game;
import com.wolfmind.domain.game.event.*;
import com.wolfmind.domain.game.model.*;
import java.util.List;

public final class CommittedEventReplay {
    private CommittedEventReplay() { }
    public static GameSnapshot state(String id, List<EventEnvelope> events, int version) {
        if (version < 1 || events.size() != version) throw GameException.Factory.replayOutOfOrder(version, events.size());
        if(events.getFirst().eventType()!=EventType.GAME_STARTED)
            throw new GameException(DomainErrorCode.REPLAY_OUT_OF_ORDER,"First event must be GAME_STARTED");
        Game game = Game.create(GameId.of(id));
        var ids=new java.util.HashSet<String>();
        for (int index = 0; index < events.size(); index++) {
            var event = events.get(index);
            if (!id.equals(event.gameId()) || event.version() != index + 1 || !ids.add(event.eventId()))
                throw GameException.Factory.replayOutOfOrder(index + 1, event.version());
            var decoded=EventCodec.decode(event);
            if(event.schemaVersion()>decoded.schemaVersion())
                throw new GameException(DomainErrorCode.REPLAY_UNKNOWN_EVENT_TYPE,"Unsupported event schemaVersion: "+event.type());
            game.replay(decoded);
        }
        return game.snapshot();
    }
}
