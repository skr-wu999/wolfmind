package com.wolfmind.interfaces.ws;

import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.ws.WebSocketEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.MessageHandler;
import jakarta.websocket.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 等待房订阅在真实连接上的契约（websocket-protocol.md §5.1 ROOM_SUBSCRIBE、§5.2 ROOM_SNAPSHOT）。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session",
                "wolfmind.test.context=ws-room"
        })
class RoomSubscriptionLiveTest {

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate rest;
    @Autowired private WebSocketGateway gateway;

    private final List<Session> openSessions = new ArrayList<>();

    @Test
    void presenceCountsAnAccountAcrossTabsAndDoesNotCountAnotherWatcher() throws Exception {
        String owner=registerAndLogin();
        Map<?,?> created=post("/api/v1/rooms",Map.of("name","presence"),owner).getBody();
        String roomId=(String)created.get("roomId"), userId=(String)created.get("ownerId");
        Client first=connect(owner), second=connect(owner), watcher=connect(registerAndLogin());
        for (Client client : List.of(first,second,watcher)) {
            client.send("ROOM_SUBSCRIBE",Map.of("roomId",roomId));
            assertNotNull(client.awaitSnapshot(roomId,room->true));
        }
        assertTrue(gateway.present(roomId,null,userId));
        first.session.close();
        assertTrue(gateway.present(roomId,null,userId),"another authenticated tab still holds presence");
        second.session.close();
        for (int attempt=0;attempt<50 && gateway.present(roomId,null,userId);attempt++) Thread.sleep(20);
        assertFalse(gateway.present(roomId,null,userId),"another account watching cannot keep this member online");
    }

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

    @Test
    @DisplayName("订阅后立即收到房间快照；房间每次变化再推一帧；开局后快照带 RUNNING 与 gameId")
    void snapshotsFollowRoomChangesUntilGameStarts() throws Exception {
        String owner = registerAndLogin();
        String roomId = (String) post("/api/v1/rooms", Map.of("name", "推送房", "config", Map.of()), owner)
                .getBody().get("roomId");

        Client watcher = connect(registerAndLogin());
        watcher.send("ROOM_SUBSCRIBE", Map.of("roomId", roomId));
        Map<String, Object> first = watcher.awaitSnapshot(roomId, room -> true);
        assertNotNull(first, "订阅后应立即收到 ROOM_SNAPSHOT；实际 " + watcher.types());
        Map<String, Object> rest0 = rest.getForEntity("/api/v1/rooms/" + roomId, Map.class).getBody();
        assertEquals(rest0.keySet(), room(first).keySet(), "快照里的房间与 GET /rooms/{roomId} 同形");
        assertEquals("WAITING", room(first).get("status"));
        assertEquals(1L, ((Number) room(first).get("occupied")).longValue());

        post("/api/v1/rooms/" + roomId + "/seats/2/ai", Map.of("agentConfigId", "rule-baseline"), owner);
        Map<String, Object> afterAi = watcher.awaitSnapshot(roomId,
                room -> ((Number) room.get("occupied")).intValue() == 2);
        assertNotNull(afterAi, "房主添加 AI 后应推送新快照；实际 " + watcher.types());
        assertTrue(version(afterAi) > version(first), "版本应递增");

        post("/api/v1/rooms/" + roomId + "/ready", Map.of(), owner);
        ResponseEntity<Map> started = post("/api/v1/rooms/" + roomId + "/games", Map.of("autoFillAi", true), owner);
        assertEquals(201, started.getStatusCode().value(), "开局失败：" + started.getBody());
        String gameId = (String) started.getBody().get("gameId");

        Map<String, Object> running = watcher.awaitSnapshot(roomId, room -> "RUNNING".equals(room.get("status")));
        assertNotNull(running, "开局后应推送 RUNNING 快照；实际 " + watcher.types());
        assertEquals(gameId, room(running).get("gameId"), "非房主据此进入对局");
    }

    @Test
    @DisplayName("不存在的房间回 ROOM_NOT_FOUND；未认证回 AUTH_REQUIRED；取消订阅后不再推送")
    void rejectsAndUnsubscribes() throws Exception {
        Client anonymous = connect(null);
        anonymous.send("ROOM_SUBSCRIBE", Map.of("roomId", "r_any"));
        assertEquals("AUTH_REQUIRED", anonymous.awaitErrorCode());

        String owner = registerAndLogin();
        String roomId = (String) post("/api/v1/rooms", Map.of("name", "退订房", "config", Map.of()), owner)
                .getBody().get("roomId");
        Client watcher = connect(registerAndLogin());
        watcher.send("ROOM_SUBSCRIBE", Map.of("roomId", "r_does_not_exist"));
        assertEquals("ROOM_NOT_FOUND", watcher.awaitErrorCode());

        watcher.send("ROOM_SUBSCRIBE", Map.of("roomId", roomId));
        assertNotNull(watcher.awaitSnapshot(roomId, room -> true));
        watcher.send("ROOM_UNSUBSCRIBE", Map.of("roomId", roomId));
        watcher.send("PING", Map.of());
        assertNotNull(watcher.await(frame -> "PONG".equals(frame.get("type"))), "退订帧已被处理");
        int before = watcher.snapshotCount();

        post("/api/v1/rooms/" + roomId + "/seats/2/ai", Map.of("agentConfigId", "rule-baseline"), owner);
        watcher.send("PING", Map.of());
        Thread.sleep(300);
        assertEquals(before, watcher.snapshotCount(), "退订后不应再收到该房间的快照；实际 " + watcher.types());
    }

    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> room(Map<String, Object> frame) {
        return (Map<String, Object>) ((Map<String, Object>) frame.get("payload")).get("room");
    }

    @SuppressWarnings("unchecked")
    private static int version(Map<String, Object> frame) {
        return ((Number) ((Map<String, Object>) frame.get("payload")).get("version")).intValue();
    }

    private ResponseEntity<Map> post(String path, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("Idempotency-Key", "idem_" + UUID.randomUUID());
        ResponseEntity<Map> response = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        assertTrue(response.getStatusCode().is2xxSuccessful(), path + " 失败：" + response.getBody());
        return response;
    }

    private String registerAndLogin() {
        String username = "wr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        rest.postForEntity("/api/v1/auth/register",
                Map.of("username", username, "password", "correct-horse"), Map.class);
        ResponseEntity<Map> loggedIn = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", username, "password", "correct-horse"), Map.class);
        return (String) loggedIn.getBody().get("sessionToken");
    }

    private Client connect(String token) throws Exception {
        int port = ((org.springframework.boot.web.context.WebServerApplicationContext) applicationContext)
                .getWebServer().getPort();
        Client client = new Client();
        Session session = ContainerProvider.getWebSocketContainer().connectToServer(client,
                ClientEndpointConfig.Builder.create().build(),
                URI.create("ws://localhost:" + port + WebSocketEndpoint.PATH));
        openSessions.add(session);
        client.session = session;
        if (token != null) {
            client.send("AUTH", Map.of("token", token));
            assertNotNull(client.await(frame -> "AUTH_OK".equals(frame.get("type"))), "AUTH 未成功");
        }
        return client;
    }

    private static final class Client extends Endpoint {

        final List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();
        Session session;
        private int sent;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text ->
                    frames.add(Json.readObject(text)));
        }

        void send(String type, Map<String, Object> payload) throws Exception {
            session.getBasicRemote().sendText(Json.write(Map.of("id", "c_" + (++sent), "type", type, "payload", payload)));
        }

        Map<String, Object> await(Predicate<Map<String, Object>> match) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                for (Map<String, Object> frame : frames) {
                    if (match.test(frame)) {
                        return frame;
                    }
                }
                Thread.sleep(20);
            }
            return null;
        }

        Map<String, Object> awaitSnapshot(String roomId, Predicate<Map<String, Object>> roomMatch)
                throws InterruptedException {
            return await(frame -> "ROOM_SNAPSHOT".equals(frame.get("type"))
                    && roomId.equals(((Map<?, ?>) frame.get("payload")).get("roomId"))
                    && roomMatch.test(room(frame)));
        }

        String awaitErrorCode() throws InterruptedException {
            Map<String, Object> error = await(frame -> "ERROR".equals(frame.get("type")));
            assertNotNull(error, "应收到 ERROR；实际 " + types());
            assertFalse(types().contains("ROOM_SNAPSHOT"), "被拒的订阅不得有快照");
            frames.remove(error);
            return String.valueOf(((Map<?, ?>) error.get("payload")).get("code"));
        }

        int snapshotCount() {
            return (int) frames.stream().filter(frame -> "ROOM_SNAPSHOT".equals(frame.get("type"))).count();
        }

        List<String> types() {
            return frames.stream().map(frame -> String.valueOf(frame.get("type"))).toList();
        }
    }
}
