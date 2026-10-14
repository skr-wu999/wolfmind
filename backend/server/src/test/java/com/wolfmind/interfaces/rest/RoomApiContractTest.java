package com.wolfmind.interfaces.rest;

import com.wolfmind.domain.game.model.ViewerContext;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 房间接口的端到端契约测试（api-spec.md §4）。
 *
 * <p>它覆盖**鉴权之后**的分支，这需要一个已登录身份。这里通过替换
 * {@link ViewerResolverPort} Bean 来提供，而不是伪造一个会话存储——
 * 这正是把身份解析做成端口的目的：控制器与"谁在调用"解耦。
 *
 * <p>测试本身也顺带证明了一件事：认证上线时只需替换一个 Bean，控制器不用改。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "wolfmind.auth.viewer-resolver=none")
class RoomApiContractTest {

    @Test
    void gameExitRequiresOwnSeatAndAbortsOnlyAfterLastHumanLeaves() {
        CURRENT_ACTOR.set("exit_owner");
        String room = (String) createRoom("退出验收", "exit-create").get("roomId");
        CURRENT_ACTOR.set("exit_alice");
        assertEquals(HttpStatus.OK, post("/api/v1/rooms/" + room + "/join", "{}", "exit-join").getStatusCode());
        post("/api/v1/rooms/" + room + "/ready", "{}", "exit-ready-alice");
        CURRENT_ACTOR.set("exit_owner");
        post("/api/v1/rooms/" + room + "/ready", "{}", "exit-ready-owner");
        String game = (String) post("/api/v1/rooms/" + room + "/games", "{\"autoFillAi\":true}", "exit-start").getBody().get("gameId");
        String path = "/api/v1/games/" + game + "/leave";
        CURRENT_ACTOR.set(null);
        assertEquals(HttpStatus.UNAUTHORIZED, post(path, "{}", "exit-anonymous").getStatusCode());
        CURRENT_ACTOR.set("exit_stranger");
        assertEquals(HttpStatus.FORBIDDEN, post(path, "{}", "exit-stranger").getStatusCode());
        CURRENT_ACTOR.set("exit_owner");
        assertEquals(HttpStatus.BAD_REQUEST, post(path, "{}", null).getStatusCode());
        var first = post(path, "{}", "exit-owner");
        assertEquals(HttpStatus.OK, first.getStatusCode());
        assertEquals("exit_alice", first.getBody().get("ownerId"));
        assertEquals("RUNNING", first.getBody().get("status"));
        assertEquals(HttpStatus.OK, post(path, "{}", "exit-owner").getStatusCode());
        CURRENT_ACTOR.set("exit_alice");
        var last = post(path, "{}", "exit-alice");
        assertEquals(HttpStatus.OK, last.getStatusCode());
        assertEquals("CLOSED", last.getBody().get("status"));
        var state = rest.getForEntity("/api/v1/games/" + game, Map.class).getBody();
        assertEquals("ABORTED", state.get("status"));
        assertEquals("NO_HUMANS", state.get("finishReason"));
        assertEquals(null, state.get("winner"));
        CURRENT_ACTOR.set("u_owner");
    }

    /** 当前测试假定的调用者；每个用例自己设置。 */
    private static final AtomicReference<String> CURRENT_ACTOR = new AtomicReference<>("u_owner");

    @TestConfiguration
    static class StubAuthentication {

        @Bean
        @Primary
        ViewerResolverPort testViewerResolver() {
            return new ViewerResolverPort() {
                @Override
                public ViewerContext resolve(HttpServletRequest request) {
                    String actor = CURRENT_ACTOR.get();
                    if (actor == null) {
                        return ViewerContext.anonymous();
                    }
                    // participant(...) 需要 myPlayerIds；房间接口只读 viewerUserId，
                    // 因此这里给一个能表达"已登录"的最小参与者上下文。
                    return ViewerContext.participant(actor, java.util.Set.of(), java.util.Set.of(), false);
                }
            };
        }
    }

    @Autowired
    private TestRestTemplate rest;

    private static HttpHeaders headers(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private ResponseEntity<Map> post(String path, String body, String idempotencyKey) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(idempotencyKey)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createRoom(String name, String idempotencyKey) {
        ResponseEntity<Map> response = post("/api/v1/rooms",
                "{\"name\":\"" + name + "\",\"config\":{\"playerCount\":9}}", idempotencyKey);
        assertEquals(HttpStatus.CREATED, response.getStatusCode(), "建房失败：" + response.getBody());
        return response.getBody();
    }

    @Test
    void customRoomRulesReachTheRealGameApi() {
        CURRENT_ACTOR.set("u_owner");
        var created = post("/api/v1/rooms", """
                {"name":"custom rules","config":{"rules":{"voteDeadlineMs":15000,
                "speechMaxLength":123,"abstainAllowed":false,"witchSelfSave":false,"winCondition":"WOLVES_OUTNUMBER"}}}
                """, "custom-rules-create");
        assertEquals(HttpStatus.CREATED, created.getStatusCode());
        String roomId = (String) created.getBody().get("roomId");
        var effective = (Map<?, ?>) created.getBody().get("rules");
        assertEquals("WOLVES_OUTNUMBER", effective.get("winCondition"));
        assertEquals(false, effective.get("witchSelfSave"));
        assertEquals(HttpStatus.OK, post("/api/v1/rooms/" + roomId + "/ready", "{}", "custom-rules-ready").getStatusCode());
        var started = post("/api/v1/rooms/" + roomId + "/games", "{\"autoFillAi\":true}", "custom-rules-start");
        assertEquals(HttpStatus.CREATED, started.getStatusCode());
        String gameId = (String) started.getBody().get("gameId");
        var view = rest.getForEntity("/api/v1/games/" + gameId, Map.class);
        assertEquals(HttpStatus.OK, view.getStatusCode());
        var rules = (Map<?, ?>) view.getBody().get("rules");
        assertEquals(15_000, ((Number) rules.get("voteDeadlineMs")).intValue());
        assertEquals(123, rules.get("speechMaxLength"));
        assertEquals(false, rules.get("abstainAllowed"));
        assertEquals(false, rules.get("witchSelfSave"));
        assertEquals(60_000, ((Number) rules.get("turnDeadlineMs")).intValue());
    }

    @Test
    void invalidRoomRulesReturn400InsteadOfBeingSilentlyIgnored() {
        CURRENT_ACTOR.set("u_owner");
        List<String> invalid = List.of("{\"voteDeadlineMs\":0}", "{\"abstainAllowed\":\"false\"}",
                "{\"unknownRule\":true}", "null", "\"DEFAULT\"");
        for (int i = 0; i < invalid.size(); i++) {
            var response = post("/api/v1/rooms", "{\"name\":\"invalid rules\",\"config\":{\"rules\":"
                    + invalid.get(i) + "}}", "invalid-rules-" + i);
            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
            assertEquals("RULES_INVALID", response.getBody().get("code"));
        }
    }

    @Test
    @DisplayName("POST /rooms 建房：房主自动占 1 号座，响应含 9 个座位")
    void createRoomSeatsOwner() {
        CURRENT_ACTOR.set("u_owner");
        Map<String, Object> room = createRoom("契约测试房", "idem_room_create_1");

        assertNotNull(room.get("roomId"));
        assertEquals("WAITING", room.get("status"));
        assertEquals("u_owner", room.get("ownerId"));
        assertEquals(1, ((Number) room.get("occupied")).intValue());
        assertEquals(9, ((Number) room.get("capacity")).intValue());
        assertEquals(1, ((Number) room.get("ownerSeatNo")).intValue(), "房主坐在 1 号座");

        List<?> seats = (List<?>) room.get("seats");
        assertEquals(9, seats.size(), "必须返回 9 个座位（含空座），客户端据此渲染空位");
        Map<?, ?> first = (Map<?, ?>) seats.get(0);
        assertEquals(1, ((Number) first.get("seatNo")).intValue());
        assertEquals("u_owner", first.get("playerId"));
        Map<?, ?> controller = (Map<?, ?>) first.get("controller");
        assertEquals("HUMAN", controller.get("type"));
        assertEquals("u_owner", controller.get("refId"));
    }

    @Test
    @DisplayName("POST /rooms 缺少 Idempotency-Key 返回 400，且不建房")
    void createRoomRequiresIdempotencyKey() {
        CURRENT_ACTOR.set("u_owner");
        ResponseEntity<Map> response = post("/api/v1/rooms", "{\"name\":\"无键房\"}", null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("VALIDATION_ERROR", response.getBody().get("code"));
    }

    @Test
    @DisplayName("未登录访问房间写接口返回 401 AUTH_REQUIRED")
    void anonymousCannotCreateRoom() {
        CURRENT_ACTOR.set(null);
        try {
            ResponseEntity<Map> response = post("/api/v1/rooms", "{\"name\":\"匿名房\"}", "idem_anon");
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertEquals("AUTH_REQUIRED", response.getBody().get("code"));
        } finally {
            CURRENT_ACTOR.set("u_owner");
        }
    }

    @Test
    @DisplayName("GET /rooms/{id} 返回刚建的房间，座位与控制者形状与契约一致")
    void getRoomReturnsSeats() {
        CURRENT_ACTOR.set("u_owner");
        String roomId = (String) createRoom("详情房", "idem_room_get_1").get("roomId");

        ResponseEntity<Map> response = rest.getForEntity("/api/v1/rooms/" + roomId, Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        assertEquals(roomId, body.get("roomId"));
        assertEquals("WAITING", body.get("status"));
        // 建房后房主占 1 号座但尚未准备，因此 readyCount 为 0。
        // 注意"占座"与"就绪"是两件事：把房主自动视为已准备会让 INV-R2 的校验形同虚设。
        assertEquals(0, ((Number) body.get("readyCount")).intValue());
        assertTrue(body.containsKey("gameId"), "未开局时 gameId 键必须存在（值为 null）");
        assertEquals(null, body.get("gameId"));
    }

    @Test
    @DisplayName("GET /rooms/{id} 房间不存在返回 404 ROOM_NOT_FOUND")
    void getMissingRoomReturns404() {
        ResponseEntity<Map> response = rest.getForEntity("/api/v1/rooms/r_nope", Map.class);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("ROOM_NOT_FOUND", response.getBody().get("code"));
    }

    @Test
    @DisplayName("GET /rooms 返回分页信封，且 total 是全量条数")
    void listRoomsReturnsPagedEnvelope() {
        CURRENT_ACTOR.set("u_owner");
        createRoom("列表房", "idem_room_list_1");

        ResponseEntity<Map> response = rest.getForEntity(
                "/api/v1/rooms?status=WAITING&page=1&pageSize=5", Map.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        Map<String, Object> body = response.getBody();
        for (String field : List.of("items", "page", "pageSize", "total")) {
            assertTrue(body.containsKey(field), "分页响应缺字段 " + field);
        }
        assertTrue(((Number) body.get("total")).intValue() >= 1, "total 必须是全量条数");
        List<?> items = (List<?>) body.get("items");
        assertFalse(items.isEmpty());
        Map<?, ?> item = (Map<?, ?>) items.get(0);
        // 列表项刻意不含 9 个座位明细：大厅列表不该把每个房间的座位都拖下来。
        assertFalse(item.containsKey("seats"), "列表项不应包含座位明细");
        assertTrue(item.containsKey("roomId") && item.containsKey("occupied"));
    }

    @Test
    @DisplayName("完整流程：入座 → 准备 → AI 补位 → 开局，房间转 RUNNING")
    void fullRoomFlowReachesRunning() {
        CURRENT_ACTOR.set("u_owner");
        String roomId = (String) createRoom("流程房", "idem_flow_create").get("roomId");

        // 房主准备
        ResponseEntity<Map> ready = post("/api/v1/rooms/" + roomId + "/ready", "{}", "idem_flow_ready");
        assertEquals(HttpStatus.OK, ready.getStatusCode(), "准备失败：" + ready.getBody());
        assertEquals(1, ((Number) ready.getBody().get("readyCount")).intValue());

        // 8 个 AI 座位补齐（AI 入座即就绪）
        for (int seatNo = 2; seatNo <= 9; seatNo++) {
            ResponseEntity<Map> ai = post(
                    "/api/v1/rooms/" + roomId + "/seats/" + seatNo + "/ai",
                    "{\"agentConfigId\":\"rule-baseline\"}",
                    "idem_flow_ai_" + seatNo);
            assertEquals(HttpStatus.OK, ai.getStatusCode(), "AI 补位失败 seat=" + seatNo + "：" + ai.getBody());
            Map<?, ?> controller = (Map<?, ?>) ai.getBody().get("controller");
            assertEquals("AI", controller.get("type"));
        }

        // 开局：此时应满足 INV-R2（坐满且全部就绪）
        ResponseEntity<Map> started = post(
                "/api/v1/rooms/" + roomId + "/games", "{\"autoFillAi\":false}", "idem_flow_start");
        assertEquals(HttpStatus.CREATED, started.getStatusCode(), "开局失败：" + started.getBody());
        assertEquals("RUNNING", started.getBody().get("status"));

        // 关键断言：开局必须真的产出一个可玩的对局，而不是只把房间状态改成 RUNNING。
        String gameId = (String) started.getBody().get("gameId");
        assertNotNull(gameId, "开局响应必须带 gameId");
        assertFalse(gameId.isBlank());
        assertEquals(11, ((Number) started.getBody().get("version")).intValue(),
                "开局产生 11 条事件（GAME_STARTED + 9×ROLE_ASSIGNED + PHASE_CHANGED）");
        assertEquals("NIGHT_WOLF", started.getBody().get("phase"));

        // 用这个 gameId 真的能读到对局视图。
        ResponseEntity<Map> gameView = rest.getForEntity("/api/v1/games/" + gameId, Map.class);
        assertEquals(HttpStatus.OK, gameView.getStatusCode(),
                "开局给出的 gameId 必须能打开对局，否则 9 个玩家会卡在一个打不开的房间里");
        assertEquals(gameId, gameView.getBody().get("gameId"));
        assertEquals("RUNNING", gameView.getBody().get("status"));
        List<?> players = (List<?>) gameView.getBody().get("players");
        assertEquals(9, players.size(), "对局必须有 9 个座位");

        // 房间视图此时也应绑定同一个 gameId
        ResponseEntity<Map> roomAfter = rest.getForEntity("/api/v1/rooms/" + roomId, Map.class);
        assertEquals("RUNNING", roomAfter.getBody().get("status"));
        assertEquals(gameId, roomAfter.getBody().get("gameId"), "房间必须记住真实的 gameId");

        // 开局后座位冻结：再入座应被拒
        ResponseEntity<Map> frozen = post(
                "/api/v1/rooms/" + roomId + "/join", "{}", "idem_flow_join_after");
        assertEquals(HttpStatus.CONFLICT, frozen.getStatusCode(), "开局后不得再接受入座");
        assertEquals("GAME_ALREADY_STARTED", frozen.getBody().get("code"));

        // 重复开局幂等：返回同一局，不再发牌
        ResponseEntity<Map> again = post(
                "/api/v1/rooms/" + roomId + "/games", "{\"autoFillAi\":false}", "idem_flow_start_2");
        assertEquals(HttpStatus.CREATED, again.getStatusCode());
        assertEquals(gameId, again.getBody().get("gameId"), "重复开局必须返回既有对局");
    }

    @Test
    @DisplayName("开局会为 AI 座位实例化 Agent（agentCount 与实际一致）")
    void startGameInstantiatesAgents() {
        CURRENT_ACTOR.set("u_owner");
        String roomId = (String) createRoom("AI 补位房", "idem_agents_create").get("roomId");

        // 全部用 autoFillAi：房主之外的 8 个座位补成 AI（AI 入座即就绪）
        post("/api/v1/rooms/" + roomId + "/ready", "{}", "idem_agents_ready");

        ResponseEntity<Map> started = post(
                "/api/v1/rooms/" + roomId + "/games", "{\"autoFillAi\":true}", "idem_agents_start");

        assertEquals(HttpStatus.CREATED, started.getStatusCode(), "开局失败：" + started.getBody());
        assertEquals(8, ((Number) started.getBody().get("agentCount")).intValue(),
                "8 个 AI 座位都应有在岗 Agent；实际=" + started.getBody().get("agentCount"));
    }

    @Test
    @DisplayName("未全部就绪时开局返回 409 NOT_READY")
    void startGameRequiresAllReady() {
        CURRENT_ACTOR.set("u_owner");
        String roomId = (String) createRoom("未就绪房", "idem_notready_create").get("roomId");

        ResponseEntity<Map> started = post(
                "/api/v1/rooms/" + roomId + "/games", "{\"autoFillAi\":false}", "idem_notready_start");

        assertEquals(HttpStatus.CONFLICT, started.getStatusCode(), "实际响应体=" + started.getBody());
        assertEquals("NOT_READY", started.getBody().get("code"), "实际响应体=" + started.getBody());
    }

    @Test
    @DisplayName("AI 补位幂等：同一座位重复补位不报错，座位数不变")
    void seatAiIsIdempotent() {
        CURRENT_ACTOR.set("u_owner");
        String roomId = (String) createRoom("幂等房", "idem_ai_idem_create").get("roomId");

        post("/api/v1/rooms/" + roomId + "/seats/2/ai", "{\"agentConfigId\":\"agent_a\"}", "idem_ai_1");
        ResponseEntity<Map> again = post(
                "/api/v1/rooms/" + roomId + "/seats/2/ai",
                "{\"agentConfigId\":\"agent_a\"}", "idem_ai_2");

        assertEquals(HttpStatus.OK, again.getStatusCode(), "重复补位同一 AI 应幂等");
        Map<?, ?> room = (Map<?, ?>) again.getBody().get("room");
        assertEquals(2, ((Number) room.get("occupied")).intValue(), "房主 + 1 个 AI");
    }

    @Test
    @DisplayName("非房主不能操作 AI 座位（403 ACTION_NOT_ALLOWED）")
    void nonOwnerCannotSeatAi() {
        CURRENT_ACTOR.set("u_owner");
        String roomId = (String) createRoom("权限房", "idem_perm_create").get("roomId");

        CURRENT_ACTOR.set("u_other");
        ResponseEntity<Map> response = post(
                "/api/v1/rooms/" + roomId + "/seats/2/ai",
                "{\"agentConfigId\":\"agent_b\"}", "idem_perm_ai");
        CURRENT_ACTOR.set("u_owner");

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("ACTION_NOT_ALLOWED", response.getBody().get("code"));
    }
}
