package com.wolfmind.infrastructure.archive;

import com.wolfmind.application.port.MatchArchivePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository;
import com.wolfmind.infrastructure.json.Json;
import java.util.List;

public final class SqliteMatchArchive implements MatchArchivePort {
    private final SqliteArchiveRepository repository;
    public SqliteMatchArchive(SqliteArchiveRepository repository) { this.repository = repository; }
    @Override public List<EventEnvelope> read(String id, int to) { return repository.readRange(id, 1, to); }
    @Override public void append(List<EventEnvelope> events, long now) {
        repository.archive(events.stream().map(e -> SqliteArchiveRepository.ArchivedEvent.of(e, now)).toList());
        var stored = repository.readRange(events.getFirst().gameId(), events.getFirst().version(), events.getLast().version());
        var expected = events.stream().map(SqliteMatchArchive::canonical).toList();
        if (!stored.equals(expected)) throw new com.wolfmind.domain.game.model.GameException(
                com.wolfmind.domain.game.model.DomainErrorCode.REPLAY_OUT_OF_ORDER,"Archived event content conflict");
    }

    @SuppressWarnings("unchecked")
    private static EventEnvelope canonical(EventEnvelope event) {
        return new EventEnvelope(event.eventId(),event.gameId(),event.version(),event.type(),event.schemaVersion(),
                event.occurredAt(),event.phase(),event.round(),event.actorId(),event.visibility(),event.causationId(),event.correlationId(),
                (java.util.Map<String,Object>)Json.narrowIntegers(Json.read(Json.write(event.payload()))));
    }
    @Override public boolean completedThrough(String id, int version) {
        return repository.game(id).filter(row -> row.archivedThrough(version)).isPresent();
    }
    @Override public void complete(String id, List<EventEnvelope> events, GameSnapshot state, long now) {
        synchronized (repository) {
            if (repository.archivedCount(id) != events.size() || !repository.versionGaps(id).isEmpty())
                throw new IllegalStateException("Archive verification failed: " + id);
            var first = events.getFirst(); var last = events.getLast();
            repository.upsertGame(new SqliteArchiveRepository.GameRecord(id,
                    String.valueOf(first.payload().getOrDefault("roomId", "")), state.status().name(), state.phase().name(),
                    state.round(), state.version(), state.players().size(), Json.write(first.payload().getOrDefault("roleComposition", java.util.Map.of())),
                    state.winner() == null ? null : state.winner().name(),
                    String.valueOf(last.payload().getOrDefault("reason", "")), first.occurredAt(), last.occurredAt(), events.size()), now);
        }
    }
}
