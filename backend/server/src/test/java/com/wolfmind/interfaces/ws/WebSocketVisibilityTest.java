package com.wolfmind.interfaces.ws;

import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.ws.WebSocketEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可见性过滤在**真实 WebSocket 连接**上的验证。
 *
 * <p>这是隐藏信息保护的最后一道，也是前几轮一直缺的那道：{@code SubscriptionStateTest} 验证
 * 的是协议语义（seq、补发、缺号），而它刻意不做过滤；过滤是否真的生效，
 * 只有让两个身份订阅同一对局、比对各自收到的帧才能证明。
 *
 * <p>断言的核心是一条：**同一场对局里，两个真人各自只收到自己的角色事件。**
 * 如果哪天有人把过滤放宽——比如为了"方便"而在扇出时算一次可见性再广播——这条会立刻失败，
 * 而它的失败方式正是"村民看到了狼人身份"。
 *
 * <p>它同时验证前几轮修掉的那个缺陷：网关过去给每个订阅者都返回空座位集合，
 * 后果是**连自己的角色都看不到**。那时的注释把它写成"安全的一侧"，
 * 但那是把"接错了"说成了"保守"。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session",
                "wolfmind.test.context=ws-visibility"
        })
class WebSocketVisibilityTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext applicationContext;

    private final List<Session> openSessions = new ArrayList<>();

    @AfterEach
    void closeSessions() {
        for (Session session : openSessions) {
            try {
                session.close();
            } catch (Exception ignored) {
                // 清理失败不影响断言。
            }
        }
        openSessions.clear();
    }

    private int port() {
        return ((org.springframework.boot.web.context.WebServerApplicationContext) applicationContext)
                .getWebServer().getPort();
    }

    /** 收集帧并支持按类型等待。 */
    private static final class Collector extends Endpoint {

        final List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text ->
                    frames.add(Json.readObject(text)));
        }

        Map<String, Object> await(String type, long timeoutMillis) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                for (Map<String, Object> frame : frames) {
                    if (type.equals(frame.get("type"))) {
                        return frame;
                    }
                }
                sleep();
            }
            return null;
        }

        /** 等待某类帧达到指定条数；返回实际条数。 */
        int awaitCount(String type, int expected, long timeoutMillis) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (count(type) >= expected) {
                    return count(type);
                }
                sleep();
            }
            return count(type);
        }

        int count(String type) {
            return (int) frames.stream().filter(f -> type.equals(f.get("type"))).count();
        }

        /** 某类帧的 payload 列表。 */
        List<Map<String, Object>> payloads(String type) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> frame : frames) {
                if (type.equals(frame.get("type")) && frame.get("payload") instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    result.add(typed);
                }
            }
            return result;
        }

        private static void sleep() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 建立连接并完成 AUTH；返回已认证的收集器。 */
    private Collector connectAuthenticated(String token) throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        Collector collector = new Collector();
        Session session = container.connectToServer(
                collector,
                ClientEndpointConfig.Builder.create().build(),
                URI.create("ws://localhost:" + port() + WebSocketEndpoint.PATH));
        openSessions.add(session);
        session.getBasicRemote().sendText(Json.write(
                Map.of("id", "f_auth", "type", "AUTH", "payload", Map.of("token", token))));
        assertNotNull(collector.await("AUTH_OK", 5_000), "AUTH 必须成功");
        return collector;
    }

    /** 当前连接的会话。 */
    private Session current() {
        return openSessions.get(openSessions.size() - 1);
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        if (token != null) {
            headers.set("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private ResponseEntity<Map> post(String path, Object body, String token) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, jsonHeaders(token)), Map.class);
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(path, HttpMethod.GET,
                new HttpEntity<>(null, jsonHeaders(token)), Map.class);
    }

    /** 注册并登录，返回 [userId, sessionToken]。 */
    private String[] account(String prefix) {
        String username = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<Map> registered = post("/api/v1/auth/register",
                Map.of("username", username, "password", "correct-horse"), null);
        assertEquals(201, registered.getStatusCode().value(), "注册失败：" + registered.getBody());
        ResponseEntity<Map> loggedIn = post("/api/v1/auth/login",
                Map.of("username", username, "password", "correct-horse"), null);
        assertEquals(200, loggedIn.getStatusCode().value(), "登录失败：" + loggedIn.getBody());
        return new String[]{
                (String) loggedIn.getBody().get("userId"),
                (String) loggedIn.getBody().get("sessionToken")
        };
    }

    /** 建一个房主 + 另一位真人的房间，AI 补齐其余座位并开局；返回 gameId 与两位玩家的账号。 */
    private String[] startGameWithTwoHumans() {
        String[] owner = account("vis_owner_");
        String[] guest = account("vis_guest_");

        ResponseEntity<Map> room = post("/api/v1/rooms",
                Map.of("name", "可见性验证房", "config", Map.of("playerCount", 9)), owner[1]);
        assertEquals(201, room.getStatusCode().value(), "建房失败：" + room.getBody());
        String roomId = (String) room.getBody().get("roomId");

        ResponseEntity<Map> joined = post("/api/v1/rooms/" + roomId + "/join", Map.of(), guest[1]);
        assertEquals(200, joined.getStatusCode().value(), "入座失败：" + joined.getBody());

        // 两位真人都必须准备：autoFillAi 只把**空座**补成 AI，不会把已入座的真人设为就绪。
        // 漏掉这一步会得到 NOT_READY（INV-R2），而不是"开局成功"——这是设计如此：
        // 让真人被自动置为就绪会让 INV-R2 形同虚设。
        assertEquals(200, post("/api/v1/rooms/" + roomId + "/ready", Map.of(), owner[1])
                .getStatusCode().value(), "房主准备失败");
        assertEquals(200, post("/api/v1/rooms/" + roomId + "/ready", Map.of(), guest[1])
                .getStatusCode().value(), "客人准备失败");

        ResponseEntity<Map> started = post("/api/v1/rooms/" + roomId + "/games",
                Map.of("autoFillAi", true), owner[1]);
        assertEquals(201, started.getStatusCode().value(), "开局失败：" + started.getBody());
        return new String[]{(String) started.getBody().get("gameId"), owner[0], guest[0],
                owner[1], guest[1]};
    }

    @Test
    @DisplayName("两个真人从有效游标补发：各自只收到自己的角色，绝不收到对方的")
    void eachSubscriberSeesOnlyItsOwnRole() throws Exception {
        String[] game = startGameWithTwoHumans();
        String gameId = game[0];
        String ownerUserId = game[1];
        String guestUserId = game[2];

        Collector ownerView = connectAuthenticated(game[3]);
        Collector guestView = connectAuthenticated(game[4]);

        current();
        openSessions.get(openSessions.size() - 2).getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub_owner", "type", "SUBSCRIBE",
                        "payload", Map.of("gameId", gameId, "lastSeq", 1))));
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub_guest", "type", "SUBSCRIBE",
                        "payload", Map.of("gameId", gameId, "lastSeq", 1))));

        assertNotNull(ownerView.await("SUBSCRIBED", 5_000), "房主订阅失败：" + ownerView.frames);
        assertNotNull(guestView.await("SUBSCRIBED", 5_000), "客人订阅失败：" + guestView.frames);

        // 开局产生 9 条 ROLE_ASSIGNED，全部是 PRIVATE 且各自只指向一个座位。
        ownerView.awaitCount("GAME_EVENT", 1, 5_000);
        guestView.awaitCount("GAME_EVENT", 1, 5_000);

        List<String> ownerSeen = rolePlayerIds(ownerView);
        List<String> guestSeen = rolePlayerIds(guestView);

        // 每人恰好看到一条 ROLE_ASSIGNED——这是过滤生效的最强证据：
        // 若过滤失效会看到 9 条，若接错（比如把座位集合留空）会看到 0 条。
        assertEquals(1, ownerSeen.size(),
                "房主应恰好看到自己的角色事件，实际=" + ownerSeen + "，全部帧=" + ownerView.frames);
        assertEquals(1, guestSeen.size(),
                "客人应恰好看到自己的角色事件，实际=" + guestSeen + "，全部帧=" + guestView.frames);

        // 各自看到的那条必须指向自己的座位，且两人的不一样。
        // 注意 playerId 是**座位号派生**的（p_1..p_9），不是 userId——这一点很关键：
        // 断言"包含 userId"会永远失败，而失败原因看起来像权限问题，实际是断言写错了。
        assertTrue(ownerSeen.get(0).contains("seatNo=1") || ownerSeen.get(0).contains("seatNo=2"),
                "房主看到的事件必须指向自己的座位：" + ownerSeen.get(0));
        assertFalse(ownerSeen.get(0).equals(guestSeen.get(0)),
                "两个真人不可能拿到同一条角色事件");

        // 交叉断言：任何一方都不得看到**对方那条**角色事件。
        // 比的是整条 payload 而不是 userId 子串——playerId 与 userId 不是同一个命名空间，
        // 用子串比对会得到假阳性。
        assertFalse(ownerView.payloads("GAME_EVENT").stream()
                        .anyMatch(p -> "ROLE_ASSIGNED".equals(p.get("type"))
                                && String.valueOf(p.get("payload")).equals(guestSeen.get(0))),
                "房主收到了客人的角色事件——村民能看到别人的角色");
        assertFalse(guestView.payloads("GAME_EVENT").stream()
                        .anyMatch(p -> "ROLE_ASSIGNED".equals(p.get("type"))
                                && String.valueOf(p.get("payload")).equals(ownerSeen.get(0))),
                "客人收到了房主的角色事件");
    }

    @Test
    @DisplayName("匿名订阅者看不到任何私有事件（连自己都没有，因为不是参与者）")
    void anonymousSubscriberSeesNoPrivateEvents() throws Exception {
        String[] game = startGameWithTwoHumans();
        String gameId = game[0];
        String ownerUserId = game[1];

        // 不 AUTH 就无法订阅（协议要求），因此用一个已认证但非参与者的账号。
        String[] outsider = account("vis_outsider_");
        Collector view = connectAuthenticated(outsider[1]);
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub", "type", "SUBSCRIBE",
                        "payload", Map.of("gameId", gameId, "lastSeq", 0))));
        assertNotNull(view.await("SUBSCRIBED", 5_000), "订阅失败：" + view.frames);
        view.awaitCount("GAME_EVENT", 1, 3_000);

        // 非参与者不是任何座位，因此看不到任何 ROLE_ASSIGNED。
        assertEquals(0, rolePlayerIds(view).size(),
                "非参与者不得看到任何角色事件，实际帧=" + view.frames);
        // 只断言"没有角色事件"：PUBLIC 事件（如 PHASE_CHANGED）本来就该发给所有人，
        // 而它们的内容里可能提到座位号——用"帧里不出现某字符串"做断言会得到假阳性。
    }

    /** 从收到的 GAME_EVENT 里挑出 ROLE_ASSIGNED，返回其 payload 的字符串形式。 */
    private static List<String> rolePlayerIds(Collector collector) {
        List<String> result = new ArrayList<>();
        for (Map<String, Object> payload : collector.payloads("GAME_EVENT")) {
            if (!"ROLE_ASSIGNED".equals(payload.get("type"))) {
                continue;
            }
            Object inner = payload.get("payload");
            result.add(String.valueOf(inner));
        }
        return result;
    }

    @Test
    void initialSubscriptionUsesAnAuthorizedSnapshotWithoutHistoricalEvents() throws Exception {
        String[] game = startGameWithTwoHumans();
        String[] outsider = account("snapshot_outside_");
        for (String token : List.of(game[3], game[4], outsider[1])) {
            Collector collector = connectAuthenticated(token);
            current().getBasicRemote().sendText(Json.write(Map.of("id", "initial", "type", "SUBSCRIBE",
                    "payload", Map.of("gameId", game[0], "lastSeq", 0))));
            var ack = collector.await("SUBSCRIBED", 5000);
            var snapshot = collector.await("GAME_SNAPSHOT_SYNC", 5000);
            assertNotNull(ack);
            assertNotNull(snapshot);
            @SuppressWarnings("unchecked")
            Map<String, Object> acknowledgement = (Map<String, Object>) ack.get("payload");
            assertEquals(token.equals(outsider[1]) ? "SPECTATOR" : "PLAYER", acknowledgement.get("scope"));
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) snapshot.get("payload");
            assertEquals(payload.get("replayedTo"), snapshot.get("seq"));
            Map<String, Object> restView = get("/api/v1/games/" + game[0], token).getBody();
            assertEquals(Json.write(restView.get("players")), Json.write(payload.get("players")));
            assertEquals(Json.write(restView.get("me")), Json.write(payload.get("me")));
            if (token.equals(outsider[1])) {
                assertEquals(null, restView.get("me"));
                assertEquals(List.of(), restView.get("permissions"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> players = (List<Map<String, Object>>) payload.get("players");
                assertTrue(players.stream().allMatch(player -> player.get("role") == null && player.get("camp") == null));
            }
            int baselineIndex = collector.frames.indexOf(snapshot);
            assertFalse(collector.frames.subList(0, baselineIndex).stream()
                    .anyMatch(frame -> "GAME_EVENT".equals(frame.get("type"))), "首次快照之前不补发历史");
            long baseline = ((Number) snapshot.get("seq")).longValue();
            assertTrue(collector.frames.stream().filter(frame -> "GAME_EVENT".equals(frame.get("type")))
                    .allMatch(frame -> ((Number) frame.get("seq")).longValue() > baseline));
        }
    }

    @Test
    void truncatedResumeReturnsOnlyTheAuthenticatedView() throws Exception {
        String[] game = startGameWithTwoHumans();
        Collector collector = connectAuthenticated(game[3]);
        current().getBasicRemote().sendText(Json.write(Map.of("id", "future-cursor", "type", "RESUME",
                "payload", Map.of("gameId", game[0], "lastSeq", 999999))));
        assertNotNull(collector.await("SYNC_REQUIRED", 5000), "实际帧：" + collector.frames);
        var snapshot = collector.await("GAME_SNAPSHOT_SYNC", 5000);
        assertNotNull(snapshot, "无法补发时必须交付授权快照");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) snapshot.get("payload");
        assertEquals(payload.get("replayedTo"), snapshot.get("seq"));
        assertEquals("FULL_REPLAY", payload.get("source"));
        @SuppressWarnings("unchecked")
        Map<String, Object> me = (Map<String, Object>) payload.get("me");
        assertEquals(1L, ((Number) me.get("seatNo")).longValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) payload.get("players");
        var rest = get("/api/v1/games/" + game[0], game[3]).getBody();
        assertEquals(Json.write(rest.get("players")), Json.write(players), "快照必须复用 REST 的同一可见性和序列化");
    }

    /** 视图细节：确认 GET /games 返回的 permissions[] 与 players 形状仍可用（同一次开局）。 */
    @Test
    @DisplayName("开局产出的对局能被 REST 读到，且 permissions[] 已登记")
    void startedGameIsReadableOverRest() {
        String[] game = startGameWithTwoHumans();
        String gameId = game[0];

        ResponseEntity<Map> view = get("/api/v1/games/" + gameId, game[3]);
        assertEquals(200, view.getStatusCode().value(), "读取对局失败：" + view.getBody());
        Map<String, Object> body = view.getBody();
        assertEquals(gameId, body.get("gameId"));
        assertEquals("RUNNING", body.get("status"));
        List<?> players = (List<?>) body.get("players");
        assertEquals(9, players.size());
        // permissions[] 是服务端投影字段（api-spec.md §5.2），必须存在。
        assertTrue(body.containsKey("permissions"), "对局视图必须含 permissions[]");
        assertNotNull(body.get("permissions"));
    }

}
