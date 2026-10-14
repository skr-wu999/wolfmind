package com.wolfmind.infrastructure.redis;

import com.wolfmind.application.scheduling.*;
import com.wolfmind.domain.game.model.*;
import com.wolfmind.infrastructure.agent.wiring.InfrastructureGameHarness;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RedisTurnSchedulingTest {
    private static LettuceConnectionFactory connection;
    private static StringRedisTemplate redis;
    @BeforeAll static void connect() { connection = RedisTestSupport.connect(); redis = connection == null ? null : new StringRedisTemplate(connection); }
    @AfterAll static void close() { if (connection != null) connection.destroy(); }
    @BeforeEach void clear() { RedisTestSupport.flush(redis); }
    private TurnTask task() {
        return TurnTask.ready("g_redis_task", TurnTask.Kind.ACTION, "p_1", 1, "NIGHT_WOLF", 1, 11, 0,
                "p_1", "AI", 15, 100, 1000);
    }
    @Test void claimBudgetAndRegistrationAreIdempotentAcrossStoreInstances() {
        var store = new RedisTurnTaskStore(redis); var task = task(); store.reconcile(task.gameId(), List.of(task));
        var claimed = store.claim(task.taskId(), "first", 100, 500).orElseThrow();
        assertTrue(store.reserve(claimed.fence(), "MODEL", 1, 101));
        var restarted = new RedisTurnTaskStore(redis);
        restarted.reconcile(task.gameId(), List.of(task));
        assertTrue(restarted.claim(task.taskId(), "second", 101, 500).isEmpty());
        assertFalse(restarted.reserve(claimed.fence(), "MODEL", 99, 101));
        assertEquals(15, restarted.tasks(task.gameId()).getFirst().dispatchVersion());
        assertTrue(restarted.finish(claimed, TurnTask.Status.SUCCEEDED, List.of("event-16"), null));
        assertFalse(restarted.valid(claimed.fence(), 101));
        assertEquals(List.of("event-16"), restarted.tasks(task.gameId()).getFirst().resultEventIds());
    }
    @Test void cancelledLeaseCannotWriteOrFinishAndEmptyPlanRoundTrips() {
        var store = new RedisTurnTaskStore(redis); var task = task(); store.reconcile(task.gameId(), List.of(task));
        var claimed = store.claim(task.taskId(), "first", 100, 500).orElseThrow();
        store.reconcile(task.gameId(), List.of());
        assertFalse(store.valid(claimed.fence(), 101));
        assertFalse(store.finish(claimed, TurnTask.Status.SUCCEEDED, List.of(), null));
        assertTrue(store.due(1000, false, 10).isEmpty());
        assertEquals(TurnTask.Status.CANCELLED, store.tasks(task.gameId()).getFirst().status());
    }
    @Test void recoveryNeverReexecutesUncertainModelAndRetriesTimeout() {
        var store = new RedisTurnTaskStore(redis); var task = task();
        var timeout = TurnTask.ready(task.gameId(), TurnTask.Kind.TIMEOUT, "", 0, "NIGHT_WOLF", 1, 11, 0,
                "", "", 15, 1000, 1000);
        store.reconcile(task.gameId(), List.of(task, timeout));
        var claimed = store.claim(task.taskId(), "old", 100, 100).orElseThrow();
        assertEquals(1, new RedisTurnTaskStore(redis).recover(201));
        assertFalse(store.valid(claimed.fence(), 201)); assertTrue(store.due(201, false, 10).isEmpty());
        var first = store.claim(timeout.taskId(), "old", 1000, 50).orElseThrow();
        assertEquals(1, store.recover(1051));
        var second = store.claim(timeout.taskId(), "new", 1051, 50).orElseThrow();
        assertTrue(second.leaseToken() > first.leaseToken());
        assertFalse(store.finish(first, TurnTask.Status.SUCCEEDED, List.of(), null));
    }
    @Test void eventsCommittedWithoutPublisherAreConsumedAndPendingSurvivesRestart() {
        var source = new InfrastructureGameHarness(1000);
        String id = "g_redis_event";
        source.startNineAiGame(id, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 7);
        var store = new RedisEventStore(redis); store.append(id, 0, source.eventStream(id));
        assertTrue(Boolean.TRUE.equals(redis.opsForSet().isMember(RedisEventStore.AGENT_STREAMS, id)));
        EventConsumptionPort.Delivery delivered;
        try (var reader = new RedisEventConsumption(redis, store, "cg-agent", null, 0)) {
            delivered = reader.read(200, 0).getFirst();
            assertEquals(store.read(id, 1, Integer.MAX_VALUE), delivered.events());
        }
        try (var reader = new RedisEventConsumption(redis, store, "cg-agent", null, 0)) {
            var recovered = reader.read(200, 0).getFirst();
            assertEquals(delivered, recovered);
            reader.acknowledge(recovered);
            assertTrue(reader.read(200, 0).isEmpty());
            assertEquals(0, redis.opsForStream().pending(RedisKeys.events(id), RedisEventConsumption.GROUP).getTotalPendingMessages());
        }
    }
    @Test void redisEventChainCompletesNineSeatsWithoutGameScan() throws Exception {
        String id = "g_redis_complete";
        var clock = new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        com.wolfmind.application.port.TimePort time = clock::get;
        var events = new RedisEventStore(redis);
        var repository = new com.wolfmind.infrastructure.memory.InMemoryGameRepository(events,
                new RedisSnapshotStore(redis, events), new com.wolfmind.infrastructure.memory.InMemoryGameStateCache());
        var lock = new RedisLock(redis); var idem = new RedisIdempotencyPort(redis);
        var publisher = new com.wolfmind.infrastructure.memory.InMemoryEventPublisher();
        var store = new RedisTurnTaskStore(redis);
        var planner = new TurnPlanner(repository, events, SpeechPacing.NONE);
        var submit = new com.wolfmind.application.game.SubmitActionApplicationService(
                new com.wolfmind.application.game.ActionCodec(), idem, lock, repository, publisher, time);
        submit.bindTaskGuard(new TaskSubmissionGuard(store, planner));
        var query = new com.wolfmind.infrastructure.memory.InMemoryGameViewQuery(events, repository);
        var views = new com.wolfmind.infrastructure.agent.AgentViewAdapter(query);
        var trace = new com.wolfmind.infrastructure.memory.InMemoryAgentTrace();
        var gateway = new com.wolfmind.infrastructure.agent.ToolGatewayAdapter(views, query, submit, idem, trace, time);
        gateway.bindTaskStore(store);
        var runtime = new com.wolfmind.application.agent.runtime.AgentRuntimeImpl(views,
                new com.wolfmind.application.agent.strategy.WolfMindStrategy(gateway), gateway,
                new com.wolfmind.infrastructure.memory.InMemoryMemoryPort(), trace, null, time);
        runtime.bindTaskStore(store);
        var adapter = new com.wolfmind.application.agent.runtime.AIControllerAdapter(runtime, views, gateway, time,
                com.wolfmind.application.agent.model.AgentConfig.ruleBaseline("redis-event"));
        var source = new InfrastructureGameHarness(clock.get());
        source.startNineAiGame(id, RoleComposition.STANDARD_NINE, GameRules.DEFAULT, 42);
        events.append(id, 0, source.eventStream(id));
        try (var scheduler = new EventDrivenTurnScheduler(new RedisEventConsumption(redis, events), store, planner,
                adapter, new com.wolfmind.application.game.TimeoutApplicationService(repository, lock, publisher, time, planner),
                time, com.wolfmind.application.port.MetricsPort.NOOP, 8, 64)) {
            scheduler.start();
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (repository.load(id).game().snapshot().status()!=GameStatus.FINISHED && System.nanoTime()<end) Thread.sleep(20);
            assertEquals(GameStatus.FINISHED, repository.load(id).game().snapshot().status());
            Game replay=Game.create(GameId.of(id));
            events.read(id,1,Integer.MAX_VALUE).forEach(e -> replay.replay(com.wolfmind.domain.game.event.EventCodec.decode(e)));
            assertEquals(repository.load(id).game().snapshot(), replay.snapshot());
        }
    }
    @Test void poisonEventIsVisibleInDlqAndDoesNotBlockOtherGames() {
        String bad="g_bad", good="g_good";
        redis.opsForStream().add(org.springframework.data.redis.connection.stream.MapRecord.create(RedisKeys.events(bad),
                Map.of("type","MALFORMED")));
        var source=new InfrastructureGameHarness(1000);
        source.startNineAiGame(good,RoleComposition.STANDARD_NINE,GameRules.DEFAULT,7);
        var store=new RedisEventStore(redis); store.append(good,0,source.eventStream(good));
        try (var reader=new RedisEventConsumption(redis,store)) {
            var delivered=reader.read(200,0);
            assertEquals(1,delivered.size()); assertEquals(good,delivered.getFirst().gameId());
            reader.acknowledge(delivered.getFirst());
            reader.read(200,0); reader.read(200,0);
            assertEquals(1,reader.blockedCount());
            assertEquals(1,redis.opsForStream().size(RedisKeys.events(bad)+":dlq"));
            assertEquals(1,redis.opsForStream().pending(RedisKeys.events(bad),RedisEventConsumption.GROUP).getTotalPendingMessages());
            assertTrue(reader.read(200,0).isEmpty());
        }
    }
}
