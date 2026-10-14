package com.wolfmind.application.game;

import com.wolfmind.application.port.ArchivedEventRestorePort;
import com.wolfmind.application.port.ReplayArchivePort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.infrastructure.memory.InMemoryEventStore;
import com.wolfmind.testsupport.ArchivedMatchFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestoreArchivedGameApplicationServiceTest {
    private static final ArchivedMatchFixture FIXTURE = ArchivedMatchFixture.create("g_archive_service", false);
    private final InMemoryEventStore store = new InMemoryEventStore();
    private final ReplayArchivePort archive = mock(ReplayArchivePort.class);
    private final ArchivedEventRestorePort writer = mock(ArchivedEventRestorePort.class);
    private final RestoreArchivedGameApplicationService service = new RestoreArchivedGameApplicationService(store, archive, writer);
    private ReplayArchivePort.ArchivedGame header;

    @BeforeEach void archive() {
        var state = FIXTURE.state();
        header = new ReplayArchivePort.ArchivedGame(FIXTURE.gameId(), state.status().name(), state.phase().name(),
                state.round(), state.version(), null, List.of());
        when(archive.game(FIXTURE.gameId())).thenReturn(Optional.of(header));
        when(archive.completedGame(FIXTURE.gameId())).thenReturn(Optional.of(header));
        when(archive.events(FIXTURE.gameId(), 1, Integer.MAX_VALUE, Integer.MAX_VALUE)).thenReturn(FIXTURE.events());
    }

    @Test void restoresOnceWithOriginalEvents() {
        when(writer.restoreAbsent(FIXTURE.gameId(), FIXTURE.events())).thenAnswer(invocation -> {
            store.append(FIXTURE.gameId(), 0, FIXTURE.events()); return true;
        });
        assertTrue(service.restoreMissing(FIXTURE.gameId()));
        assertTrue(service.restoreMissing(FIXTURE.gameId()));
        verify(writer, times(1)).restoreAbsent(FIXTURE.gameId(), FIXTURE.events());
        assertEquals(FIXTURE.events(), store.read(FIXTURE.gameId(), 1, Integer.MAX_VALUE));
    }

    @Test void noArchiveDoesNotCreateAStream() {
        when(archive.game("g_missing")).thenReturn(Optional.empty());
        assertFalse(service.restoreMissing("g_missing")); verifyNoInteractions(writer);
    }

    @Test void incompleteMarkerIsRejected() {
        when(archive.completedGame(FIXTURE.gameId())).thenReturn(Optional.empty());
        assertThrows(GameException.class, () -> service.restoreMissing(FIXTURE.gameId())); verifyNoInteractions(writer);
    }

    @Test void missingEventIsRejectedBeforeWrite() {
        when(archive.events(FIXTURE.gameId(), 1, Integer.MAX_VALUE, Integer.MAX_VALUE)).thenReturn(FIXTURE.events().subList(1, FIXTURE.events().size()));
        assertThrows(GameException.class, () -> service.restoreMissing(FIXTURE.gameId())); verifyNoInteractions(writer);
    }

    @Test void duplicatedVersionIsRejectedBeforeWrite() {
        var events = new ArrayList<>(FIXTURE.events()); events.set(4, events.get(3));
        when(archive.events(FIXTURE.gameId(), 1, Integer.MAX_VALUE, Integer.MAX_VALUE)).thenReturn(events);
        assertThrows(GameException.class, () -> service.restoreMissing(FIXTURE.gameId())); verifyNoInteractions(writer);
    }

    @Test void mismatchedFinalMetadataIsRejectedBeforeWrite() {
        var wrong = new ReplayArchivePort.ArchivedGame(header.gameId(), "FINISHED", header.phase(), header.round(),
                header.version(), "WOLF", List.of());
        when(archive.game(FIXTURE.gameId())).thenReturn(Optional.of(wrong));
        when(archive.completedGame(FIXTURE.gameId())).thenReturn(Optional.of(wrong));
        assertThrows(GameException.class, () -> service.restoreMissing(FIXTURE.gameId())); verifyNoInteractions(writer);
    }

    @Test void crossGameEventIsRejectedBeforeWrite() {
        var events = new ArrayList<>(FIXTURE.events()); var old = events.get(4);
        events.set(4, new EventEnvelope(old.eventId(), "g_other", old.version(), old.type(), old.schemaVersion(),
                old.occurredAt(), old.phase(), old.round(), old.actorId(), old.visibility(), old.causationId(), old.correlationId(), old.payload()));
        when(archive.events(FIXTURE.gameId(), 1, Integer.MAX_VALUE, Integer.MAX_VALUE)).thenReturn(events);
        assertThrows(GameException.class, () -> service.restoreMissing(FIXTURE.gameId())); verifyNoInteractions(writer);
    }

    @Test void failedInstallationCanBeRetriedWithoutAnAuthoritativePartialStream() {
        when(writer.restoreAbsent(FIXTURE.gameId(), FIXTURE.events())).thenThrow(new IllegalStateException("interrupted"))
                .thenAnswer(invocation -> { store.append(FIXTURE.gameId(), 0, FIXTURE.events()); return true; });
        assertThrows(IllegalStateException.class, () -> service.restoreMissing(FIXTURE.gameId()));
        assertFalse(store.exists(FIXTURE.gameId()));
        assertTrue(service.restoreMissing(FIXTURE.gameId()));
    }

    @Test void unknownFutureSchemaIsRejectedBeforeWrite() {
        var events=new ArrayList<>(FIXTURE.events());var old=events.get(4);
        events.set(4,new EventEnvelope(old.eventId(),old.gameId(),old.version(),old.type(),99,
                old.occurredAt(),old.phase(),old.round(),old.actorId(),old.visibility(),old.causationId(),old.correlationId(),old.payload()));
        when(archive.events(FIXTURE.gameId(),1,Integer.MAX_VALUE,Integer.MAX_VALUE)).thenReturn(events);
        assertThrows(GameException.class,()->service.restoreMissing(FIXTURE.gameId()));verifyNoInteractions(writer);
    }

    @Test void reusedEventIdAtAnotherVersionIsRejectedBeforeWrite() {
        var events=new ArrayList<>(FIXTURE.events());var old=events.get(4);
        events.set(4,new EventEnvelope(events.get(3).eventId(),old.gameId(),old.version(),old.type(),old.schemaVersion(),
                old.occurredAt(),old.phase(),old.round(),old.actorId(),old.visibility(),old.causationId(),old.correlationId(),old.payload()));
        when(archive.events(FIXTURE.gameId(),1,Integer.MAX_VALUE,Integer.MAX_VALUE)).thenReturn(events);
        assertThrows(GameException.class,()->service.restoreMissing(FIXTURE.gameId()));verifyNoInteractions(writer);
    }
}
