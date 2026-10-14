package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.agent.model.AgentConfig;
import com.wolfmind.application.agent.runtime.AIControllerAdapter;
import com.wolfmind.application.agent.runtime.AgentRuntimeImpl;
import com.wolfmind.application.agent.strategy.WolfMindStrategy;
import com.wolfmind.application.port.GameLockPort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.application.port.IdempotencyPort;
import com.wolfmind.application.user.AuthApplicationService;
import com.wolfmind.application.user.PasswordHasher;
import com.wolfmind.application.user.SessionPort;
import com.wolfmind.domain.game.event.EventCodec;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.domain.game.model.DomainErrorCode;
import com.wolfmind.domain.game.model.Game;
import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameId;
import com.wolfmind.domain.game.model.GameRules;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.RoleComposition;
import com.wolfmind.domain.game.model.Visibility;
import com.wolfmind.infrastructure.agent.AgentViewAdapter;
import com.wolfmind.infrastructure.agent.ToolGatewayAdapter;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import com.wolfmind.infrastructure.memory.InMemoryAgentTrace;
import com.wolfmind.infrastructure.memory.InMemoryGameRepository;
import com.wolfmind.infrastructure.memory.InMemoryGameStateCache;
import com.wolfmind.infrastructure.memory.InMemoryMemoryPort;
import com.wolfmind.infrastructure.memory.InMemoryRoomIndex;
import com.wolfmind.infrastructure.sqlite.SqliteUserRepository;
import com.wolfmind.testsupport.ControlledTurnDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 存储适配器与内存实现的契约等价、并发仲裁与重启恢复（Phase 2 验收：重启后恢复出的状态等于重启前）。
 */
class RedisStorageIntegrationTest {

    private static final String GAME_ID = "g_redis_it";

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        factory = RedisTestSupport.connect();
        redis = factory == null ? null : new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @BeforeEach
    void flush() {
        RedisTestSupport.flush(redis);
    }

    /** 用共享应用层规划/超时用例在内存里打完整局，返回完整事件流与终态。 */
    private static PlayedGame playFullGame(long seed) {
        InfrastructureGameHarness harness = new InfrastructureGameHarness(1_730_000_000_000L);
        AgentViewAdapter views = new AgentViewAdapter(harness.viewQuery());
        InMemoryAgentTrace trace = new InMemoryAgentTrace();
        ToolGatewayAdapter gateway = new ToolGatewayAdapter(
                views, harness.viewQuery(), harness.submitService(), harness.idempotency(), trace, harness.time());
        AgentRuntimeImpl runtime = new AgentRuntimeImpl(
                views, new WolfMindStrategy(gateway), gateway, new InMemoryMemoryPort(), trace, null, harness.time());
        AIControllerAdapter adapter = new AIControllerAdapter(runtime, views, gateway, harness.time(),
                AgentConfig.ruleBaseline("redis-it"));
        ControlledTurnDriver driver = new ControlledTurnDriver(harness.repository(), adapter, harness.time(),
                harness.publisher(), harness.eventStore(), harness.lock());
        harness.startNineAiGame(GAME_ID, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, seed);
        for (int step = 0; step < 500
                && harness.repository().load(GAME_ID).game().snapshot().status() == GameStatus.RUNNING; step++) {
            harness.advanceClock(200L);
            driver.step(GAME_ID);
        }
        GameSnapshot finalState = harness.repository().load(GAME_ID).game().snapshot();
        assertEquals(GameStatus.FINISHED, finalState.status(), "夹具必须打完整局");
        return new PlayedGame(harness.eventStream(GAME_ID), finalState);
    }

    private record PlayedGame(List<EventEnvelope> stream, GameSnapshot finalState) {}

    @Test
    void roomTakeoverReplaysWithoutRegisteringRoomInGameConsumers() {
        RedisEventStore store = new RedisEventStore(redis);
        var repository = new com.wolfmind.infrastructure.memory.InMemoryRoomRepository(store);
        var harness = new InfrastructureGameHarness(1_730_000_000_000L);
        var rooms = new com.wolfmind.application.room.RoomApplicationService(repository,
                new InMemoryRoomIndex(), harness.lock(), harness.publisher(), harness.time());
        String id = rooms.create("owner", "exit", Map.of(), "room-create").roomId();
        rooms.ready(id, "owner", "ready");
        for (int seat = 2; seat <= 9; seat++) rooms.seatAi(id, "owner", seat, "ai-" + seat, "ai-" + seat);
        rooms.startGame(id, "owner", "g_exit", "start");
        rooms.leave(id, "owner", "exit");
        assertEquals("CLOSED", rooms.load(id).status().name());
        assertTrue(rooms.load(id).seatAt(com.wolfmind.domain.game.model.SeatNo.of(1)).isAi());
        String stream = "room:" + id;
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(RedisEventConsumption.directory("cg-broadcast"), stream)));
        for (String group : List.of("cg-agent", "cg-projection", "cg-insight")) {
            assertFalse(Boolean.TRUE.equals(redis.opsForSet().isMember(RedisEventConsumption.directory(group), stream)), group);
        }
    }

    private static void appendInChunks(RedisEventStore store, List<EventEnvelope> events, int chunk) {
        for (int from = 0; from < events.size(); from += chunk) {
            List<EventEnvelope> batch = events.subList(from, Math.min(from + chunk, events.size()));
            store.append(GAME_ID, from, batch);
        }
    }

    private static GameSnapshot replay(List<EventEnvelope> events) {
        Game game = Game.create(GameId.of(GAME_ID));
        for (EventEnvelope event : events) {
            game.replay(EventCodec.decode(event));
        }
        return game.snapshot();
    }

    @Test
    void fullMatchStreamRoundTripsFieldByFieldAndReplaysToSameState() {
        PlayedGame played = playFullGame(20240501L);
        RedisEventStore store = new RedisEventStore(redis);

        appendInChunks(store, played.stream(), 7);

        List<EventEnvelope> readBack = store.read(GAME_ID, 1, Integer.MAX_VALUE);
        assertEquals(played.stream().size(), readBack.size());
        for (int i = 0; i < readBack.size(); i++) {
            EventEnvelope written = narrowed(played.stream().get(i));
            EventEnvelope read = readBack.get(i);
            assertEquals(written, read, () -> "第 " + written.version() + " 条 " + written.type()
                    + " 读回不等：写入 payload=" + typed(written.payload())
                    + " 读回 payload=" + typed(read.payload()));
        }
        assertEquals(played.stream().size(), store.currentVersion(GAME_ID));
        assertTrue(store.exists(GAME_ID));
        assertEquals(List.of(GAME_ID), store.gameIds());
        assertEquals(played.finalState(), replay(readBack));

        List<EventEnvelope> window = store.read(GAME_ID, 5, 9);
        assertEquals(List.of(5, 6, 7, 8, 9), window.stream().map(EventEnvelope::version).toList());
        assertTrue(store.read(GAME_ID, 9, 5).isEmpty());

        long ttl = store.ttlSecondsOf(GAME_ID);
        assertEquals(-1L, ttl, "终局但未归档的事件流不能自动过期");
    }

    @Test
    void retentionShrinksToSevenDaysOnlyWhenArchiveReportsDone() {
        PlayedGame played = playFullGame(20240501L);
        RedisEventStore store = new RedisEventStore(redis);
        RedisSnapshotStore snapshots = new RedisSnapshotStore(redis, store);
        appendInChunks(store, played.stream(), 50);
        snapshots.save(new com.wolfmind.application.port.SnapshotStorePort.Snapshot(
                GAME_ID, played.stream().size(), "{}", "c"));
        assertEquals(-1L, redis.getExpire(RedisKeys.latestSnapshot(GAME_ID)));

        store.shortenAfterArchive(GAME_ID);
        store.shortenAfterArchive(GAME_ID);

        for (String key : List.of(RedisKeys.events(GAME_ID), RedisKeys.latestSnapshot(GAME_ID),
                RedisKeys.snapshotIndex(GAME_ID), RedisKeys.snapshotAt(GAME_ID, played.stream().size()))) {
            long ttl = redis.getExpire(key);
            assertTrue(ttl > RedisEventStore.GAME_TERMINAL_TTL_SECONDS - 60
                            && ttl <= RedisEventStore.GAME_TERMINAL_TTL_SECONDS,
                    key + " 归档后应为 7 天，实际 " + ttl);
        }
    }

    @Test
    void staleOrGappedAppendIsRejectedWithoutPartialWrite() {
        List<EventEnvelope> stream = playFullGame(7L).stream();
        RedisEventStore store = new RedisEventStore(redis);
        store.append(GAME_ID, 0, stream.subList(0, 11));

        GameException stale = assertThrows(GameException.class,
                () -> store.append(GAME_ID, 5, stream.subList(5, 8)));
        assertEquals(DomainErrorCode.GAME_VERSION_CONFLICT, stale.code());
        assertEquals(11, store.currentVersion(GAME_ID));

        List<EventEnvelope> gapped = List.of(stream.get(11), stream.get(13));
        assertThrows(GameException.class, () -> store.append(GAME_ID, 11, gapped));
        assertEquals(11, store.currentVersion(GAME_ID), "批内不连续必须整批不写");

        long ttl = store.ttlSecondsOf(GAME_ID);
        assertEquals(-1L, ttl, "未终局的事件流不能自动过期");
    }

    @Test
    void concurrentWritersWithSameExpectedVersionHaveExactlyOneWinner() throws Exception {
        List<EventEnvelope> stream = playFullGame(42L).stream();
        RedisEventStore store = new RedisEventStore(redis);
        store.append(GAME_ID, 0, stream.subList(0, 11));
        List<EventEnvelope> next = stream.subList(11, 14);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> writers = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                writers.add(() -> {
                    try {
                        store.append(GAME_ID, 11, next);
                        return true;
                    } catch (GameException conflict) {
                        assertEquals(DomainErrorCode.GAME_VERSION_CONFLICT, conflict.code());
                        return false;
                    }
                });
            }
            int winners = 0;
            for (Future<Boolean> result : pool.invokeAll(writers)) {
                if (result.get()) {
                    winners++;
                }
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(14, store.currentVersion(GAME_ID));
        assertEquals(next, store.read(GAME_ID, 12, 14));
    }

    @Test
    void restartRecoversSameStateFromSnapshotPlusTail() {
        PlayedGame played = playFullGame(20240501L);
        List<EventEnvelope> beforeFinish = played.stream().subList(0, played.stream().size() - 3);
        GameSnapshot expected = replay(beforeFinish);

        RedisEventStore storeBefore = new RedisEventStore(redis);
        InMemoryGameRepository before = new InMemoryGameRepository(
                storeBefore, new RedisSnapshotStore(redis, storeBefore), new InMemoryGameStateCache(), 10);
        for (int from = 0; from < beforeFinish.size(); from += 4) {
            List<EventEnvelope> batch = beforeFinish.subList(from, Math.min(from + 4, beforeFinish.size()));
            before.commit(GAME_ID, from, batch, GameRepositoryPort.SnapshotPolicy.INTERVAL);
        }

        RedisEventStore storeAfter = new RedisEventStore(redis);
        RedisSnapshotStore snapshotsAfter = new RedisSnapshotStore(redis, storeAfter);
        InMemoryGameRepository after = new InMemoryGameRepository(
                storeAfter, snapshotsAfter, new InMemoryGameStateCache(), 10);
        GameSnapshot recovered = after.load(GAME_ID).game().snapshot();

        assertEquals(expected, recovered, "重启后由快照+增量恢复的状态必须等于重启前");
        assertEquals(1, after.incrementalLoadCount(), "必须走快照+增量路径");
        assertEquals(0, after.checksumDegradationCount());
        List<Integer> retained = snapshotsAfter.retainedVersions(GAME_ID);
        assertEquals(RedisSnapshotStore.RETAINED_VERSIONS, retained.size(), "Redis 侧只保留最近 3 个快照");
        assertEquals(snapshotsAfter.latest(GAME_ID).orElseThrow().version(), retained.getLast());
    }

    @Test
    void corruptedSnapshotFallsBackToFullReplay() {
        PlayedGame played = playFullGame(7L);
        RedisEventStore store = new RedisEventStore(redis);
        appendInChunks(store, played.stream(), 20);
        RedisSnapshotStore snapshots = new RedisSnapshotStore(redis, store);
        snapshots.save(new com.wolfmind.application.port.SnapshotStorePort.Snapshot(
                GAME_ID, 11, "{\"broken\":true}", "deadbeef"));

        InMemoryGameRepository repository = new InMemoryGameRepository(
                store, snapshots, new InMemoryGameStateCache());
        assertEquals(played.finalState(), repository.load(GAME_ID).game().snapshot());
        assertEquals(1, repository.checksumDegradationCount());
        assertEquals(1, repository.fullReplayLoadCount());
    }

    @Test
    void lockIsExclusiveAndStaleOwnerCannotReleaseTakeover() throws InterruptedException {
        RedisLock lock = new RedisLock(redis, 150);
        GameLockPort.LockToken first = lock.tryLock(GAME_ID).orElseThrow();
        assertTrue(lock.tryLock(GAME_ID).isEmpty(), "持有期间第二个写者必须拿不到锁");

        Thread.sleep(300);
        GameLockPort.LockToken takeover = lock.tryLock(GAME_ID).orElseThrow();
        lock.unlock(first);
        assertTrue(lock.isLocked(GAME_ID), "过期持有者释放时不得删掉接管者的锁");

        lock.unlock(takeover);
        assertFalse(lock.isLocked(GAME_ID));
        assertFalse(redis.hasKey("game:" + GAME_ID + ":lock"));

        GameLockPort.LockToken roomLock = lock.tryLock("room:r_1").orElseThrow();
        assertTrue(redis.hasKey("room:r_1:lock"), "房间锁键为 room:{id}:lock");
        lock.unlock(roomLock);
        assertFalse(redis.hasKey("room:r_1:lock"));
    }

    @Test
    void idempotencyKeepsFirstResultAndExpires() {
        RedisIdempotencyPort port = new RedisIdempotencyPort(redis);
        IdempotencyPort.RecordedResult first = new IdempotencyPort.RecordedResult(
                GAME_ID, "key-1", 14, List.of("ev_1", "ev_2"), List.of("PLAYER_VOTED", "PHASE_CHANGED"), "digest");

        assertTrue(port.find(GAME_ID, "key-1").isEmpty());
        port.record(GAME_ID, "key-1", first);
        port.record(GAME_ID, "key-1", first);
        assertEquals(Optional.of(first), port.find(GAME_ID, "key-1"));

        IdempotencyPort.RecordedResult different = new IdempotencyPort.RecordedResult(
                GAME_ID, "key-1", 15, List.of("ev_3"), List.of("PLAYER_VOTED"), "other");
        assertThrows(IllegalStateException.class, () -> port.record(GAME_ID, "key-1", different));
        assertEquals(Optional.of(first), port.find(GAME_ID, "key-1"));

        Long ttl = redis.getExpire("idem:" + GAME_ID + ":key-1", TimeUnit.MILLISECONDS);
        assertNotNull(ttl);
        assertTrue(ttl > 0 && ttl <= RedisIdempotencyPort.DEFAULT_TTL_MILLIS);
    }

    @Test
    void sessionsVerifyRevokeAndExpireThroughKeyTtl() throws InterruptedException {
        RedisSessionStore sessions = new RedisSessionStore(redis);
        sessions.issue("u_1", "s1", "secret-1", 0L, 60_000L);
        sessions.issue("u_1", "s2", "secret-2", 0L, 60_000L);
        sessions.issue("u_2", "s3", "secret-3", 0L, 100L);

        assertEquals(Optional.of(new SessionPort.Verified("u_1", "s1", "u_1.s1.secret-1")),
                sessions.verify("u_1.s1.secret-1"));
        assertTrue(sessions.verify("u_1.s1.wrong").isEmpty());
        assertTrue(sessions.verify("not-a-token").isEmpty());
        assertNull(redis.opsForHash().get("user:u_1:session:s1", "secret"), "明文 secret 不得落库");

        assertTrue(sessions.revoke("u_1.s1.secret-1"));
        assertTrue(sessions.verify("u_1.s1.secret-1").isEmpty());
        assertEquals(1, sessions.revokeAll("u_1"));

        Thread.sleep(300);
        assertTrue(sessions.verify("u_2.s3.secret-3").isEmpty(), "过期会话由键 TTL 失效");
    }

    @Test
    void roomIndexAndGameCatalogOnlySeeTheirOwnStreams() {
        RedisEventStore store = new RedisEventStore(redis);
        store.append("room:r_1", 0, List.of(envelope("room:r_1", 1, "ROOM_CREATED")));
        store.append("user:u_1", 0, List.of(envelope("user:u_1", 1, "USER_REGISTERED")));
        store.append(GAME_ID, 0, List.of(envelope(GAME_ID, 1, "GAME_STARTED")));

        RedisRoomIndex rooms = new RedisRoomIndex(redis);
        assertEquals(List.of("r_1"), rooms.all());
        assertTrue(rooms.contains("r_1"));
        assertFalse(rooms.contains("r_2"));
        assertEquals(List.of(GAME_ID), store.gameIds());

        assertTrue(redis.hasKey("room:r_1:events"));
        assertTrue(redis.hasKey("user:u_1:events"));
        assertTrue(redis.hasKey("game:" + GAME_ID + ":events"));
        long roomTtl = store.ttlSecondsOf("room:r_1");
        assertTrue(roomTtl > RedisEventStore.ROOM_OPEN_TTL_SECONDS - 60
                && roomTtl <= RedisEventStore.ROOM_OPEN_TTL_SECONDS, "未关闭房间 TTL 应为 24 小时");
        assertTrue(store.ttlSecondsOf("user:u_1") > RedisEventStore.GAME_RUNNING_TTL_SECONDS);
    }

    @Test
    void accountsAndSessionsSurviveRestartAndOrphanUsernameCanBeReused(@TempDir Path dir) {
        String jdbc = "jdbc:sqlite:" + dir.resolve("accounts.db");
        RedisEventStore events = new RedisEventStore(redis);
        long[] clock = {1_730_000_000_000L};
        String token;
        try (SqliteUserRepository users = SqliteUserRepository.open(jdbc, events)) {
            AuthApplicationService auth = new AuthApplicationService(
                    users, new RedisSessionStore(redis), new PasswordHasher(1), () -> clock[0]);
            auth.register("alice", "password-1", "Alice");
            token = auth.login("alice", "password-1").sessionToken();
            assertTrue(users.credentialOf(users.findByUsername("alice").orElseThrow().userId())
                    .orElseThrow().algorithm().equals(PasswordHasher.ALGORITHM));
        }

        try (SqliteUserRepository users = SqliteUserRepository.open(jdbc, new RedisEventStore(redis))) {
            RedisSessionStore sessions = new RedisSessionStore(redis);
            AuthApplicationService auth = new AuthApplicationService(
                    users, sessions, new PasswordHasher(1), () -> clock[0]);
            assertTrue(sessions.verify(token).isPresent(), "重启后既有会话仍有效");
            assertNotNull(auth.login("alice", "password-1").sessionToken(), "重启后可用原口令登录");
            assertThrows(GameException.class, () -> auth.register("alice", "password-2", "Alice"));

            String userId = users.findByUsername("alice").orElseThrow().userId().value();
            redis.delete("user:" + userId + ":events");
            assertTrue(users.findByUsername("alice").isEmpty(), "事件流丢失后账号不可重建");
            auth.register("alice", "password-3", "Alice");
            assertNotNull(auth.login("alice", "password-3").sessionToken(), "孤儿行被替换后用户名可再次注册");
        }
    }

    /**
     * JSON 不保留整数宽度：{@code GAME_STARTED.rules} 里的 {@code Long} 截止时长读回为 {@code Integer}
     * （与 SQLite 归档同一规则）。比较前对写入侧做同样的收窄；语义等价由重放断言保证。
     */
    @SuppressWarnings("unchecked")
    private static EventEnvelope narrowed(EventEnvelope event) {
        return new EventEnvelope(event.eventId(), event.gameId(), event.version(), event.type(),
                event.schemaVersion(), event.occurredAt(), event.phase(), event.round(), event.actorId(),
                event.visibility(), event.causationId(), event.correlationId(),
                (Map<String, Object>) com.wolfmind.infrastructure.json.Json.narrowIntegers(event.payload()));
    }

    private static String typed(Object value) {
        if (value instanceof Map<?, ?> map) {
            StringBuilder text = new StringBuilder("{");
            map.forEach((key, item) -> text.append(key).append('=').append(typed(item)).append(", "));
            return text.append('}').toString();
        }
        if (value instanceof List<?> list) {
            return list.stream().map(RedisStorageIntegrationTest::typed).toList().toString();
        }
        return value == null ? "null" : value + ":" + value.getClass().getSimpleName();
    }

    private static EventEnvelope envelope(String partition, int version, String type) {
        return new EventEnvelope("ev_" + partition + "_" + version, partition, version, type, 1,
                1_730_000_000_000L, "GAME_INIT", 1, "SYSTEM", Visibility.PUBLIC, "cmd", "cor", Map.of());
    }
}
