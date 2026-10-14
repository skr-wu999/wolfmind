package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.game.RestoreArchivedGameApplicationService;
import com.wolfmind.application.port.MetricsPort;
import com.wolfmind.application.port.ReplayArchivePort;
import com.wolfmind.application.port.SnapshotStorePort;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.ViewerContext;
import com.wolfmind.infrastructure.archive.SqliteReplayArchive;
import com.wolfmind.infrastructure.archive.TerminalGameArchiver;
import com.wolfmind.infrastructure.memory.InMemoryGameRepository;
import com.wolfmind.infrastructure.memory.InMemoryGameStateCache;
import com.wolfmind.infrastructure.memory.InMemoryGameViewQuery;
import com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository;
import com.wolfmind.testsupport.ArchivedMatchFixture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisArchiveRecoveryTest {
    private static final ArchivedMatchFixture FINISHED = ArchivedMatchFixture.create("g_restore_finished", true);
    private static final ArchivedMatchFixture ABORTED = ArchivedMatchFixture.create("g_restore_aborted", false);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    @TempDir Path directory;
    private SqliteArchiveRepository sqlite;
    private RedisEventStore store;
    private SqliteReplayArchive archive;

    @BeforeAll static void connect() { factory = RedisTestSupport.connect(); redis = factory == null ? null : new StringRedisTemplate(factory); }
    @AfterAll static void disconnect() { if (factory != null) factory.destroy(); }
    @BeforeEach void setup() {
        RedisTestSupport.flush(redis);
        store = new RedisEventStore(redis);
        sqlite = new SqliteArchiveRepository(jdbcUrl());
        archive = new SqliteReplayArchive(sqlite);
    }
    @AfterEach void close() { if (sqlite != null) sqlite.close(); }
    private String jdbcUrl() { return "jdbc:sqlite:" + directory.resolve("archive.db"); }

    private void archive(ArchivedMatchFixture fixture) {
        new TerminalGameArchiver(fixture.harness().viewQuery(), fixture.harness().eventStore(), sqlite,
                unused -> {}, fixture.harness().time()).sweep();
        assertTrue(archive.completedGame(fixture.gameId()).isPresent());
    }
    private InMemoryGameRepository repository() {
        return new InMemoryGameRepository(store, new RedisSnapshotStore(redis, store), new InMemoryGameStateCache(),
                50, MetricsPort.NOOP, new RestoreArchivedGameApplicationService(store, archive, store));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void actualRepositoryAndViewsRestoreOriginalTerminalState(boolean finished) {
        var fixture = finished ? FINISHED : ABORTED;
        archive(fixture);
        store.append(fixture.gameId(), 0, fixture.events());
        redis.delete(RedisKeys.events(fixture.gameId()));
        var snapshots = new RedisSnapshotStore(redis, store);
        snapshots.save(new SnapshotStorePort.Snapshot(fixture.gameId(), 1, "invalid-old-snapshot", "old"));
        AtomicInteger wakeups = new AtomicInteger(); store.onAppend(unused -> wakeups.incrementAndGet());
        var repo = repository();
        var query = new InMemoryGameViewQuery(store, repo, archive);
        assertEquals(fixture.state(), repo.load(fixture.gameId()).game().snapshot());
        assertEquals(sqlite.readRange(fixture.gameId(), 1, Integer.MAX_VALUE), store.read(fixture.gameId(), 1, Integer.MAX_VALUE));
        assertEquals(fixture.state(), repository().load(fixture.gameId()).game().snapshot(), "新进程可再次加载");
        assertNotNull(query.view(fixture.gameId(), ViewerContext.anonymous()));
        assertEquals(4, repo.loadAt(fixture.gameId(), 4).version());
        assertEquals(fixture.harness().viewQuery().replayPage(fixture.gameId(), ViewerContext.anonymous(), 1, 4, 4)
                        .events().stream().map(e -> e.eventId()).toList(),
                query.replayPage(fixture.gameId(), ViewerContext.anonymous(), 1, 4, 4)
                        .events().stream().map(e -> e.eventId()).toList());
        assertEquals(fixture.harness().viewQuery().viewAt(fixture.gameId(), ViewerContext.anonymous(), 4),
                query.viewAt(fixture.gameId(), ViewerContext.anonymous(), 4));
        assertEquals(0, wakeups.get());
        assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(RedisEventStore.AGENT_STREAMS, fixture.gameId())));
        assertFalse(redis.hasKey(RedisKeys.latestSnapshot(fixture.gameId())));
        assertTrue(store.ttlSecondsOf(fixture.gameId()) > RedisEventStore.GAME_TERMINAL_TTL_SECONDS - 60);
        assertTrue(redis.keys("wolfmind:archive-restore:*").isEmpty());
    }

    @Test void readAtAndViewAutomaticallyRestoreWhenTheyAreTheFirstRequest() {
        archive(FINISHED);
        var repo = repository();
        assertEquals(4, repo.loadAt(FINISHED.gameId(), 4).version());
        redis.delete(RedisKeys.events(FINISHED.gameId()));
        var query = new InMemoryGameViewQuery(store, repository(), archive);
        assertNotNull(query.view(FINISHED.gameId(), ViewerContext.anonymous()));
    }

    @Test void concurrentInstancesInstallOneCompleteStream() throws Exception {
        archive(FINISHED);
        var pool = Executors.newFixedThreadPool(6);
        var installed = new AtomicInteger();
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 6; i++) futures.add(pool.submit(() -> {
                try (var copy = new SqliteArchiveRepository(jdbcUrl())) {
                    var service = new RestoreArchivedGameApplicationService(store, new SqliteReplayArchive(copy),
                            (id, events) -> { boolean result = store.restoreAbsent(id, events); if (result) installed.incrementAndGet(); return result; });
                    return service.restoreMissing(FINISHED.gameId());
                }
            }));
            for (var future : futures) assertTrue(future.get(10, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        assertEquals(1, installed.get());
        assertEquals(FINISHED.state(), repository().load(FINISHED.gameId()).game().snapshot());
        assertEquals(FINISHED.events().size(), redis.opsForStream().size(RedisKeys.events(FINISHED.gameId())));
    }

    @Test void existingStreamIsNotOverwrittenEvenWhenRestoreWasAlreadyPrepared() {
        store.append(FINISHED.gameId(), 0, FINISHED.events().subList(0, 11));
        var before = store.read(FINISHED.gameId(), 1, Integer.MAX_VALUE);
        assertFalse(store.restoreAbsent(FINISHED.gameId(), FINISHED.events()));
        assertEquals(before, store.read(FINISHED.gameId(), 1, Integer.MAX_VALUE));
        assertEquals(-1L, store.ttlSecondsOf(FINISHED.gameId()));
    }

    @Test void interruptedInstallationLeavesNoPartialAuthoritativeStreamAndCanRetry() {
        archive(FINISHED);
        var failingRedis = spy(redis);
        doAnswer(invocation -> {
            RedisScript<?> script = invocation.getArgument(0);
            if (script.getScriptAsString().contains("INCOMPLETE_ARCHIVE_STAGE")) throw new IllegalStateException("interrupted before install");
            return invocation.callRealMethod();
        }).when(failingRedis).execute(any(RedisScript.class), anyList(), any(Object[].class));
        var failingStore = new RedisEventStore(failingRedis);
        assertThrows(IllegalStateException.class, () -> failingStore.restoreAbsent(FINISHED.gameId(), FINISHED.events()));
        assertFalse(store.exists(FINISHED.gameId()));
        assertTrue(redis.keys("wolfmind:archive-restore:*").isEmpty());
        assertEquals(FINISHED.state(), repository().load(FINISHED.gameId()).game().snapshot());
    }

    @Test void unfinishedArchiveAndMissingArchiveDoNotCreateGames() {
        sqlite.archive(FINISHED.events().subList(0, 5).stream()
                .map(e -> SqliteArchiveRepository.ArchivedEvent.of(e, 1L)).toList());
        assertThrows(GameException.class, () -> repository().load(FINISHED.gameId()));
        assertFalse(store.exists(FINISHED.gameId()));
        assertThrows(GameException.class, () -> repository().load("g_missing"));
    }

    @Test void corruptArchiveMarkerDoesNotPermitPartialRestore() {
        archive(FINISHED);
        var row = sqlite.game(FINISHED.gameId()).orElseThrow();
        sqlite.upsertGame(new SqliteArchiveRepository.GameRecord(row.gameId(), row.roomId(), row.status(), row.phase(), row.round(),
                row.version() + 1, row.playerCount(), row.roleCompositionJson(), row.winner(), row.finishReason(),
                row.startedAt(), row.finishedAt(), row.eventsArchived() + 1), 1L);
        assertThrows(GameException.class, () -> repository().load(FINISHED.gameId()));
        assertFalse(store.exists(FINISHED.gameId()));
    }

    @Test void startupMigratesOldUnarchivedTtlAndPreservesArchivedRetention() {
        archive(ABORTED);
        store.append(ABORTED.gameId(), 0, ABORTED.events());
        store.append(FINISHED.gameId(), 0, FINISHED.events().subList(0, 11));
        String id = FINISHED.gameId();
        var snapshots = new RedisSnapshotStore(redis, store);
        snapshots.save(new SnapshotStorePort.Snapshot(id, 5, "old", "old"));
        for (String key : List.of(RedisKeys.events(id), RedisKeys.snapshotIndex(id), RedisKeys.latestSnapshot(id), RedisKeys.snapshotAt(id, 5))) {
            redis.expire(key, Duration.ofDays(30));
        }
        store.reconcileRetention(archive);
        for (String key : List.of(RedisKeys.events(id), RedisKeys.snapshotIndex(id), RedisKeys.latestSnapshot(id), RedisKeys.snapshotAt(id, 5))) {
            assertEquals(-1L, redis.getExpire(key));
        }
        assertTrue(store.ttlSecondsOf(ABORTED.gameId()) <= RedisEventStore.GAME_TERMINAL_TTL_SECONDS);
    }

    @Test void appendCancelsOldSnapshotExpiryAndArchiveFailureKeepsData() {
        String id = FINISHED.gameId();
        store.append(id, 0, FINISHED.events().subList(0, 11));
        var snapshots = new RedisSnapshotStore(redis, store);
        snapshots.save(new SnapshotStorePort.Snapshot(id, 5, "old", "old"));
        for (String key : List.of(RedisKeys.events(id), RedisKeys.snapshotIndex(id), RedisKeys.latestSnapshot(id), RedisKeys.snapshotAt(id, 5))) {
            redis.expire(key, Duration.ofDays(30));
        }
        store.append(id, 11, FINISHED.events().subList(11, 12));
        assertEquals(-1L, redis.getExpire(RedisKeys.events(id)));
        assertEquals(-1L, redis.getExpire(RedisKeys.snapshotAt(id, 5)));
        var broken = SqliteArchiveRepository.inMemory(); broken.close();
        new TerminalGameArchiver(FINISHED.harness().viewQuery(), store, broken, store::shortenAfterArchive,
                FINISHED.harness().time()).sweep();
        assertEquals(-1L, redis.getExpire(RedisKeys.events(id)));
    }
}
