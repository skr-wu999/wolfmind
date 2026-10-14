package com.wolfmind.application.game;

import com.wolfmind.application.fake.*;
import com.wolfmind.application.port.EventPublisherPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.scheduling.SpeechPacing;
import com.wolfmind.application.scheduling.TurnPlanner;
import com.wolfmind.application.scheduling.TurnTask;
import com.wolfmind.domain.game.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TimeoutApplicationServiceTest {
    private static final String ID = "g_timeout_service";
    private static final int STARTED_VERSION = 11;
    private FakeEventStore store;
    private FakeGameRepository repository;
    private FakeLock lock;
    private FakePublisher publisher;
    private FakeTime time;
    private TurnPlanner planner;
    private TimeoutApplicationService service;
    private Game game;

    @BeforeEach void setUp() {
        store = new FakeEventStore();
        repository = new FakeGameRepository(store);
        lock = new FakeLock();
        publisher = new FakePublisher();
        time = new FakeTime(1700);
        game = GameFixtures.registeredStartedGame(store, repository, ID);
        planner = new TurnPlanner(repository, store, SpeechPacing.NONE);
        service = new TimeoutApplicationService(repository, lock, publisher, time, planner);
    }

    private TurnTask planned(TurnTask.Kind kind) {
        return planner.plan(ID).tasks().stream().filter(t -> t.kind() == kind).findFirst().orElseThrow();
    }

    private TurnTask advanceTask() {
        repository.register(GameFixtures.restoredAtPhase(game, GamePhase.NIGHT_SETTLE));
        return planned(TurnTask.Kind.ADVANCE);
    }

    private TurnTask unplanned(String gameId) {
        return TurnTask.ready(gameId, TurnTask.Kind.ADVANCE, "", 0, "NIGHT_WOLF", 1, 999,
                0, "", "", STARTED_VERSION, time.nowMillis(), time.nowMillis() + 1000);
    }

    @Test void staleTaskProducesNoEventsAndReleasesTheLock() {
        service.execute(unplanned(ID));
        assertEquals(STARTED_VERSION, store.currentVersion(ID));
        assertEquals(0, repository.commitCount());
        assertTrue(publisher.groups().isEmpty());
        assertTrue(publisher.realtime().isEmpty());
        assertEquals(1, lock.unlockCalls());
        assertFalse(lock.isHeld(ID));
    }

    @Test void deadlineNotReachedProducesNoEvents() {
        service.execute(planned(TurnTask.Kind.TIMEOUT));
        assertEquals(STARTED_VERSION, store.currentVersion(ID));
        assertEquals(0, repository.commitCount());
        assertTrue(publisher.realtime().isEmpty());
        assertFalse(lock.isHeld(ID));
    }

    @Test void domainReturningNoAdvanceDoesNotCommitOrPublish() {
        var current = mock(TurnPlanner.class);
        var task = unplanned(ID);
        when(current.current(task)).thenReturn(true);
        new TimeoutApplicationService(repository, lock, publisher, time, current).execute(task);
        assertEquals(0, repository.commitCount());
        assertEquals(STARTED_VERSION, store.currentVersion(ID));
        assertTrue(publisher.groups().isEmpty());
        assertEquals(1, lock.unlockCalls());
    }

    @Test void domainAdvanceCommitsContinuousVersionsWithSystemTaskIdentity() {
        var task = advanceTask();
        service.execute(task);
        var committed = repository.lastCommit();
        assertFalse(committed.events().isEmpty());
        assertEquals(STARTED_VERSION, committed.expectedVersion());
        for (int index = 0; index < committed.events().size(); index++) {
            var event = committed.events().get(index);
            assertEquals(STARTED_VERSION + index + 1, event.version());
            assertEquals("SYSTEM", event.actorId());
            assertEquals("timeout:" + task.taskId() + ":" + STARTED_VERSION, event.correlationId());
        }
        assertEquals(STARTED_VERSION + committed.events().size(), store.currentVersion(ID));
        assertEquals(committed.events(), publisher.realtime());
        assertEquals(1, lock.unlockCalls());
        assertFalse(lock.isHeld(ID));
    }

    @Test void busyLockFailsBeforeLoadingOrCheckingTheTask() {
        lock.forceBusy(true);
        var error = assertThrows(GameException.class, () -> service.execute(unplanned(ID)));
        assertEquals(DomainErrorCode.GAME_BUSY, error.code());
        assertEquals(0, repository.loadCalls());
        assertEquals(0, repository.commitCount());
    }

    @Test void loadFailureReleasesTheLock() {
        var error = assertThrows(GameException.class, () -> service.execute(unplanned("g_missing")));
        assertEquals(DomainErrorCode.GAME_NOT_RUNNING, error.code());
        assertEquals(1, lock.unlockCalls());
        assertFalse(lock.isHeld("g_missing"));
    }

    @Test void commitFailureNeverPublishesAndStillReleasesTheLock() {
        var task = advanceTask();
        var failed = mock(GameRepositoryPort.class, org.mockito.AdditionalAnswers.delegatesTo(repository));
        doThrow(new IllegalStateException("store unavailable")).when(failed)
                .commit(eq(ID), eq(STARTED_VERSION), anyList(), eq(GameRepositoryPort.SnapshotPolicy.INTERVAL));
        assertThrows(IllegalStateException.class, () ->
                new TimeoutApplicationService(failed, lock, publisher, time, planner).execute(task));
        assertEquals(STARTED_VERSION, store.currentVersion(ID));
        assertTrue(publisher.groups().isEmpty());
        assertTrue(publisher.realtime().isEmpty());
        assertEquals(1, lock.unlockCalls());
        assertFalse(lock.isHeld(ID));
    }

    @Test void commitCompletesBeforeAnyPublication() {
        var task = advanceTask();
        var observedRepository = mock(GameRepositoryPort.class, org.mockito.AdditionalAnswers.delegatesTo(repository));
        var observedPublisher = mock(EventPublisherPort.class, org.mockito.AdditionalAnswers.delegatesTo(publisher));
        new TimeoutApplicationService(observedRepository, lock, observedPublisher, time, planner).execute(task);
        var order = inOrder(observedRepository, observedPublisher);
        order.verify(observedRepository).commit(eq(ID), eq(STARTED_VERSION), anyList(), eq(GameRepositoryPort.SnapshotPolicy.INTERVAL));
        order.verify(observedPublisher).publish(eq("cg-projection"), anyList());
        order.verify(observedPublisher).publish(eq("cg-agent"), anyList());
        order.verify(observedPublisher).publish(eq("cg-insight"), anyList());
        order.verify(observedPublisher).publishRealtime(anyList());
    }

    @Test void publicationFailureDoesNotEraseCommittedEventsOrKeepTheLock() {
        var task = advanceTask();
        publisher.failAll(true);
        assertDoesNotThrow(() -> service.execute(task));
        assertTrue(store.currentVersion(ID) > STARTED_VERSION);
        assertEquals(1, repository.commitCount());
        assertEquals(1, lock.unlockCalls());
        assertFalse(lock.isHeld(ID));
    }
}
