package com.wolfmind.infrastructure.redis;

import com.wolfmind.WolfMindApplication;
import com.wolfmind.application.agent.AgentRuntime;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.domain.game.model.GameSnapshot;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.testsupport.ControlledTurnDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redis 模式的进程重启验收：同一份 Redis 与 SQLite 上先后启动两个应用上下文，
 * 第二次启动后对局、房间、账号与会话都可恢复，且恢复后的对局能由补建的 Agent 打完。
 */
class RedisRestartAcceptanceTest {

    private static final String PASSWORD = "correct-horse";

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @TempDir
    Path sqliteDir;

    private ConfigurableApplicationContext running;

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
    void prepare() {
        RedisTestSupport.flush(redis);
        System.setProperty("wolfmind.agent.force-rule", "true");
    }

    @AfterEach
    void cleanUp() {
        if (running != null) {
            running.close();
        }
        if (redis != null) {
            System.clearProperty("wolfmind.agent.force-rule");
            RedisTestSupport.flush(redis);
        }
    }

    private ConfigurableApplicationContext boot() {
        running = new SpringApplicationBuilder(WolfMindApplication.class)
                .properties(
                        "wolfmind.storage=redis",
                        "spring.data.redis.host=" + RedisTestSupport.host(),
                        "spring.data.redis.port=" + RedisTestSupport.port(),
                        "spring.data.redis.database=" + RedisTestSupport.DATABASE,
                        "wolfmind.sqlite.path=" + sqliteDir.resolve("wolfmind.db"),
                        "server.port=0",
                        "wolfmind.game.driver.enabled=false",
                        "wolfmind.room.lifecycle.enabled=false",
                        "wolfmind.ws.heartbeat.enabled=false",
                        "wolfmind.auth.pbkdf2-iterations=1",
                        "wolfmind.auth.viewer-resolver=session")
                .run();
        return running;
    }

    private void shutDown() {
        running.close();
        running = null;
    }

    private static TestRestTemplate rest(ConfigurableApplicationContext context) {
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        return new TestRestTemplate(new RestTemplateBuilder().rootUri("http://localhost:" + port + "/api/v1"));
    }

    @Test
    void realRedisConfigurationRestoresExpiredArchiveThroughTheGameApi() {
        var fixture = com.wolfmind.testsupport.ArchivedMatchFixture.create("g_archive_http", true);
        try (var archive = new com.wolfmind.infrastructure.sqlite.SqliteArchiveRepository(
                "jdbc:sqlite:" + sqliteDir.resolve("wolfmind.db"))) {
            new com.wolfmind.infrastructure.archive.TerminalGameArchiver(fixture.harness().viewQuery(),
                    fixture.harness().eventStore(), archive, unused -> {}, fixture.harness().time()).sweep();
        }
        assertFalse(redis.hasKey(RedisKeys.events(fixture.gameId())));
        var context = boot();
        var response = rest(context).getForEntity("/games/" + fixture.gameId(), Map.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(fixture.state(), context.getBean(com.wolfmind.application.port.GameRepositoryPort.class)
                .load(fixture.gameId()).game().snapshot());
        assertEquals(fixture.events().size(), new RedisEventStore(redis).currentVersion(fixture.gameId()));
        var metrics = context.getBean(com.wolfmind.application.port.MetricsPort.class).snapshot();
        assertEquals(0L, metrics.counters().getOrDefault(com.wolfmind.application.port.MetricsPort.Names.LLM_CALLS, 0L));
    }

    private static HttpHeaders headers(String bearer, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (bearer != null) {
            headers.setBearerAuth(bearer);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private static Map<?, ?> call(TestRestTemplate rest, HttpMethod method, String path, Object body,
                                  String bearer, String idempotencyKey, HttpStatus expected) {
        ResponseEntity<Map> response = rest.exchange(path, method,
                new HttpEntity<>(body, headers(bearer, idempotencyKey)), Map.class);
        assertEquals(expected, response.getStatusCode(), () -> method + " " + path + " -> " + response.getBody());
        return response.getBody();
    }

    private static GameSnapshot snapshotOf(ConfigurableApplicationContext context, String gameId) {
        return context.getBean(GameRepositoryPort.class).load(gameId).game().snapshot();
    }

    private static ControlledTurnDriver driverFor(ConfigurableApplicationContext context) {
        return new ControlledTurnDriver(context.getBean(GameRepositoryPort.class),
                context.getBean(com.wolfmind.application.agent.runtime.AIControllerAdapter.class),
                context.getBean(com.wolfmind.application.port.TimePort.class),
                context.getBean(com.wolfmind.application.port.EventPublisherPort.class),
                context.getBean(com.wolfmind.application.port.EventStorePort.class),
                context.getBean(com.wolfmind.application.port.GameLockPort.class));
    }

    @Test
    void gameRoomAccountAndSessionSurviveRestartAndTheGameFinishes() {
        String username = "restart_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        ConfigurableApplicationContext first = boot();
        TestRestTemplate rest = rest(first);
        String userId = (String) call(rest, HttpMethod.POST, "/auth/register",
                Map.of("username", username, "password", PASSWORD), null, null, HttpStatus.CREATED).get("userId");
        String token = (String) call(rest, HttpMethod.POST, "/auth/login",
                Map.of("username", username, "password", PASSWORD), null, null, HttpStatus.OK).get("sessionToken");
        String roomId = (String) call(rest, HttpMethod.POST, "/rooms", Map.of("name", "重启验收"),
                token, "create", HttpStatus.CREATED).get("roomId");
        call(rest, HttpMethod.POST, "/rooms/" + roomId + "/ready", null, token, "ready", HttpStatus.OK);
        Map<?, ?> started = call(rest, HttpMethod.POST, "/rooms/" + roomId + "/games",
                Map.of("autoFillAi", true), token, "start", HttpStatus.CREATED);
        String gameId = (String) started.get("gameId");
        assertEquals(8, ((Number) started.get("agentCount")).intValue());
        // Storage recovery uses a deterministic AI for the owner's turns; the room membership stays active.
        var repository = first.getBean(GameRepositoryPort.class);
        var loaded = repository.load(gameId);
        var controllerEvents = loaded.game().changeController(com.wolfmind.domain.game.model.SeatNo.of(1),
                new com.wolfmind.domain.game.model.PlayerController(com.wolfmind.domain.game.model.ControllerType.AI, "p_1"),
                com.wolfmind.domain.game.model.ActionContext.of("test-ai", System.currentTimeMillis(),
                        "SYSTEM", "test-ai", "test-ai", loaded.version()));
        repository.commit(gameId, loaded.version(), com.wolfmind.application.game.EventEnvelopes.wrap(
                gameId, loaded.version() + 1, controllerEvents), GameRepositoryPort.SnapshotPolicy.FORCE);

        ControlledTurnDriver driver = driverFor(first);
        for (int step = 0; step < 12; step++) {
            driver.step(gameId);
        }
        GameSnapshot beforeRestart = snapshotOf(first, gameId);
        assertEquals(GameStatus.RUNNING, beforeRestart.status(), "重启前对局必须仍在进行，否则测不到恢复");
        assertTrue(beforeRestart.version() > 11, "推进后版本应高于开局事件数，实际 " + beforeRestart.version());
        shutDown();

        ConfigurableApplicationContext second = boot();
        rest = rest(second);
        assertEquals(beforeRestart, snapshotOf(second, gameId), "重启后恢复的对局状态必须等于重启前");

        Map<?, ?> me = call(rest, HttpMethod.GET, "/auth/me", null, token, null, HttpStatus.OK);
        assertEquals(userId, me.get("userId"), "重启前签发的会话令牌必须仍然有效");
        call(rest, HttpMethod.POST, "/auth/login",
                Map.of("username", username, "password", PASSWORD), null, null, HttpStatus.OK);

        Map<?, ?> room = call(rest, HttpMethod.GET, "/rooms/" + roomId, null, token, null, HttpStatus.OK);
        assertEquals(gameId, room.get("gameId"));
        List<?> rooms = (List<?>) call(rest, HttpMethod.GET, "/rooms?status=RUNNING", null, token, null,
                HttpStatus.OK).get("items");
        assertTrue(rooms.stream().anyMatch(item -> roomId.equals(((Map<?, ?>) item).get("roomId"))),
                "房间列表必须在重启后仍包含该房间：" + rooms);

        assertEquals(9, second.getBean(AgentRuntime.class).activeInstanceCount(gameId),
                "重启恢复必须为运行中对局补建全部 9 个 AI Agent");

        ControlledTurnDriver resumed = driverFor(second);
        for (int step = 0; step < 600 && snapshotOf(second, gameId).status() == GameStatus.RUNNING; step++) {
            resumed.step(gameId);
        }
        GameSnapshot finalState = snapshotOf(second, gameId);
        assertEquals(GameStatus.FINISHED, finalState.status(), "恢复后的对局必须能打完");
        assertNotNull(finalState.winner());
        long closeDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        var queries = second.getBean(com.wolfmind.application.room.GetRoomQueryService.class);
        while (!"CLOSED".equals(queries.versioned(roomId).view().status()) && System.nanoTime() < closeDeadline) {
            java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(20));
        }
        assertEquals("CLOSED", queries.versioned(roomId).view().status(), () -> "cg-projection must close the room after normal settlement: "
                + second.getBean(com.wolfmind.application.port.MetricsPort.class).snapshot());
        assertEquals(0, queries.count(null));
    }
}
