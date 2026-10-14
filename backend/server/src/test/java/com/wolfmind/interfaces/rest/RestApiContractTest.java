package com.wolfmind.interfaces.rest;

import com.wolfmind.application.port.EventStorePort;
import com.wolfmind.application.port.GameRepositoryPort;
import com.wolfmind.domain.game.event.EventEnvelope;
import com.wolfmind.infrastructure.InfrastructureTestEvents;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REST 接口层的端到端契约测试（Spring Boot 3 内嵌 Tomcat，随机端口）。
 *
 * <p>为什么要起真实 HTTP 而不是直接调控制器方法：**JSON 字段形状本身就是契约**。
 * 领域投影是 record，序列化后的键名与"null 字段是否出现"由 Jackson 的行配置决定；
 * 只有走完一次真实的请求-响应，才能证明前端拿到的东西没变。
 *
 * <p>端口用随机端口，避免并行构建冲突。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestApiContractTest {

    private static final String GAME_ID = "g_rest_0001";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EventStorePort eventStore;

    @Autowired
    private GameRepositoryPort repository;

    @BeforeEach
    void seedGame() {
        if (eventStore.currentVersion(GAME_ID) == 0) {
            List<EventEnvelope> events = InfrastructureTestEvents.startEventStream(GAME_ID);
            repository.commit(GAME_ID, 0, events, GameRepositoryPort.SnapshotPolicy.INTERVAL);
        }
    }

    @Test
    @DisplayName("GET /api/v1/health 返回 200 与依赖状态，并带 X-Trace-Id")
    void healthIsAvailable() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/health", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("application/json", response.getHeaders().getContentType().getType()
                + "/" + response.getHeaders().getContentType().getSubtype());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        assertEquals("0.1.0", response.getBody().get("version"));
        assertTrue(response.getBody().containsKey("dependencies"));
    }

    @Test
    @DisplayName("GET /games/{id} 的 JSON 字段形状与 api-spec 5.2 一致（前端契约）")
    void gameViewShapeMatchesContract() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/games/" + GAME_ID, Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);

        // 顶层字段必须逐个存在：这些键名就是前端的读法。
        for (String field : List.of("gameId", "version", "status", "phase", "round", "turnIndex",
                "turnOrder", "currentTurn", "players", "votes", "deaths", "spectator", "winner")) {
            assertTrue(body.containsKey(field), "缺少契约字段 " + field + "；实际键=" + body.keySet());
        }
        assertEquals(GAME_ID, body.get("gameId"));
        assertEquals("RUNNING", body.get("status"));
        assertEquals(11, ((Number) body.get("version")).intValue());
        // 匿名读取者：没有座位，但 me 键必须存在且为 null（"不可见"与"字段缺失"是不同事实）。
        assertTrue(body.containsKey("me"), "me 键必须存在");
        assertEquals(null, body.get("me"), "匿名读取者没有座位");

        List<?> players = (List<?>) body.get("players");
        assertEquals(9, players.size(), "九人局必须有 9 个座位");
        Map<?, ?> first = (Map<?, ?>) players.get(0);
        for (String field : List.of("playerId", "seatNo", "alive", "ready", "controller", "role")) {
            assertTrue(first.containsKey(field), "participant 缺字段 " + field + "；实际键=" + first.keySet());
        }
        Map<?, ?> controller = (Map<?, ?>) first.get("controller");
        assertTrue(controller.containsKey("type") && controller.containsKey("refId"),
                "controller 必须是 {type, refId}；实际=" + controller);
    }

    @Test
    @DisplayName("匿名读取者看不到任何角色（隐藏信息隔离）")
    void anonymousViewerSeesNoRoles() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/games/" + GAME_ID, Map.class);
        List<?> players = (List<?>) response.getBody().get("players");
        for (Object item : players) {
            assertEquals(null, ((Map<?, ?>) item).get("role"),
                    "匿名读取者不得看到任何座位角色");
        }
    }

    @Test
    @DisplayName("POST /games/{id}/actions 未登录返回 401，且不产生事件")
    void submitActionRequiresAuthentication() {
        int before = eventStore.currentVersion(GAME_ID);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "idem_contract_401");

        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/games/" + GAME_ID + "/actions",
                HttpMethod.POST,
                new HttpEntity<>("{\"actionType\":\"VOTE\",\"params\":{\"targetSeatNo\":6},\"expectedVersion\":11}", headers),
                Map.class);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("AUTH_REQUIRED", response.getBody().get("code"));
        assertTrue(response.getBody().containsKey("traceId"));
        assertEquals(before, eventStore.currentVersion(GAME_ID), "被拒绝的写请求不得产生事件");
    }

    @Test
    @DisplayName("GET /games/{id}/replay 只返回该身份可见的事件，信封字段与 event-design 2 一致")
    void replayReturnsEventEnvelopes() {
        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/games/" + GAME_ID + "/replay?from=1&to=11", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(GAME_ID, body.get("gameId"));
        assertEquals(1, ((Number) body.get("from")).intValue());
        assertEquals(false, body.get("truncated"), "请求区间未超过流尾时不得标记截断");

        // 开局 11 条事件里只有 GAME_STARTED 与 PHASE_CHANGED 是 PUBLIC，9 条 ROLE_ASSIGNED 是 PRIVATE。
        // 匿名读取者因此只看到 2 条——这是隐藏信息隔离生效，不是丢数据。
        List<?> events = (List<?>) body.get("events");
        assertEquals(2, events.size(), "匿名读取者只应看到 PUBLIC 事件；实际类型="
                + events.stream().map(e -> ((Map<?, ?>) e).get("type")).toList());
        assertTrue(events.size() < 11, "PRIVATE 的 ROLE_ASSIGNED 不得下发给匿名读取者");

        Map<?, ?> first = (Map<?, ?>) events.get(0);
        for (String field : List.of("eventId", "gameId", "version", "type", "schemaVersion",
                "occurredAt", "phase", "round", "actorId", "visibility", "causationId",
                "correlationId", "payload")) {
            assertTrue(first.containsKey(field), "事件信封缺字段 " + field + "；实际键=" + first.keySet());
        }
        assertEquals("PUBLIC", first.get("visibility"));
    }

    @Test
    @DisplayName("GET /games 返回分页信封")
    void listGamesReturnsPagedEnvelope() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/games?page=1&pageSize=20", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        for (String field : List.of("items", "page", "pageSize", "total")) {
            assertTrue(body.containsKey(field), "分页响应缺字段 " + field);
        }
    }

    @Test
    @DisplayName("非法参数返回 400 VALIDATION_ERROR，而不是 500 或静默兜默认值")
    void invalidParameterIsRejected() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/games?page=abc", Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().get("code"));
    }

    @Test
    @DisplayName("回放区间倒置返回 400")
    void replayRejectsInvertedRange() {
        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/games/" + GAME_ID + "/replay?from=5&to=2", Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().get("code"));
    }

    @Test
    @DisplayName("POST /games/{id}/actions 缺少 Idempotency-Key 时返回 400（api-spec 1 要求所有 POST 带该头）")
    void submitActionRequiresIdempotencyKey() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/games/" + GAME_ID + "/actions",
                HttpMethod.POST,
                new HttpEntity<>("{\"actionType\":\"VOTE\",\"expectedVersion\":11}", headers),
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().get("code"));
    }

    @Test
    @DisplayName("请求体不是合法 JSON 时返回 400，而不是 500")
    void submitActionRejectsMalformedBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "idem_contract_bad_body");
        ResponseEntity<Map> response = rest.exchange(
                "/api/v1/games/" + GAME_ID + "/actions",
                HttpMethod.POST,
                new HttpEntity<>("not json", headers),
                Map.class);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().get("code"));
    }

    @Test
    @DisplayName("错误响应带可关联的 traceId，且响应头与响应体一致")
    void errorResponsesCarryTraceId() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/games?page=abc", Map.class);

        String header = response.getHeaders().getFirst("X-Trace-Id");
        assertNotNull(header, "任何响应都必须带 X-Trace-Id，否则失败无法与日志对上");
        assertEquals(header, response.getBody().get("traceId"), "响应头与响应体的 traceId 必须一致");
        assertTrue(header.startsWith("cor_"), "traceId 形态与事件 correlationId 同义；实际=" + header);
    }

    @Test
    @DisplayName("不存在的路由返回 404，而不是 500")
    void unknownRouteIsNotFound() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/nope", Map.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertTrue(response.getBody().containsKey("code"));
    }

    @Test
    @DisplayName("只读部署缺少应用服务时映射为 501，绝不假装成功")
    void notWiredServiceReturns501() {
        // 501 的语义是"路由存在但该部署没接上服务"，与 404（路由不存在）不同。
        // 直接驱动 advice 断言映射，避免为一个部署变体启动第二个 Spring 上下文。
        org.springframework.mock.web.MockHttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest();
        org.springframework.http.ResponseEntity<Errors.ApiError> response =
                new ApiExceptionHandler().onNotWired(new NotWiredException("测试：未接应用服务"), request);

        assertEquals(501, response.getStatusCode().value());
        assertEquals("NOT_IMPLEMENTED", response.getBody().code());
    }
}