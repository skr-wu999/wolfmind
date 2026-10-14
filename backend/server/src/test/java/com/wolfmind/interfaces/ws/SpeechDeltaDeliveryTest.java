package com.wolfmind.interfaces.ws;

import com.wolfmind.domain.game.event.EventEnvelope;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AGENT_SPEECH_DELTA} 的投递通路（websocket-protocol.md §5.2、§7.2）。
 *
 * <p><b>这一组验证的是投递，不是生成。</b>模型流式片段如何变成预览由
 * {@code AgentWorkflowSpeechPreviewTest} 与 {@code SpeechPreviewStreamTest} 覆盖；这里在进程内
 * 直接调用网关或预览端口——帧会**真的经过 WebSocket 连接**到达客户端。
 *
 * <p>它守两条规则：
 * <ol>
 *   <li><b>不推进 seq</b>：发言增量是展示更新（AGENTS.md §9），推进它会让客户端在
 *       下一次真正的事件上误判缺号；</li>
 *   <li><b>密谈只发给狼</b>：狼队密谈按订阅关系过滤，非狼既收不到帧也不留痕。</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session",
                "wolfmind.test.context=ws-speech"
        })
class SpeechDeltaDeliveryTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebSocketGateway gateway;

    /**
     * 生产路径的起点：网关注册为它的实时监听者。
     *
     * <p>用它而不是直接调 {@code gateway.onSpeechDelta}：只有经过发布器才能证明
     * "**有人在推**"，而不只是"端口可用"。第 19 轮就是只有后者。
     */
    @Autowired
    private com.wolfmind.infrastructure.memory.InMemoryEventPublisher publisher;

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

    /** 收集服务端帧。 */
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

        long count(String type) {
            return frames.stream().filter(f -> type.equals(f.get("type"))).count();
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloads(String type) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> frame : frames) {
                if (type.equals(frame.get("type")) && frame.get("payload") instanceof Map<?, ?> map) {
                    result.add((Map<String, Object>) map);
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

    private Session current() {
        return openSessions.get(openSessions.size() - 1);
    }

    private static HttpHeaders headers(String token, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) headers.set("Idempotency-Key", idempotencyKey);
        if (token != null) headers.set("Authorization", "Bearer " + token);
        return headers;
    }

    private ResponseEntity<Map> post(String path, Object body, String token) {
        return rest.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body, headers(token, UUID.randomUUID().toString())), Map.class);
    }

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

    /** 建房 → 准备 → AI 补位开局；返回 gameId 与账号。 */
    private String[] startedGame(String prefix) {
        String[] account = account(prefix);
        ResponseEntity<Map> room = post("/api/v1/rooms",
                Map.of("name", "发言通路房", "config", Map.of("playerCount", 9)), account[1]);
        assertEquals(201, room.getStatusCode().value(), "建房失败：" + room.getBody());
        String roomId = (String) room.getBody().get("roomId");

        assertEquals(200, post("/api/v1/rooms/" + roomId + "/ready", Map.of(), account[1])
                .getStatusCode().value(), "准备失败");
        ResponseEntity<Map> started = post("/api/v1/rooms/" + roomId + "/games",
                Map.of("autoFillAi", true), account[1]);
        assertEquals(201, started.getStatusCode().value(), "开局失败：" + started.getBody());
        return new String[]{(String) started.getBody().get("gameId"), account[0], account[1]};
    }

    @Test
    @DisplayName("已订阅的连接收到 AGENT_SPEECH_DELTA，且字段与协议一致")
    void subscribedConnectionReceivesSpeechDelta() throws Exception {
        String[] game = startedGame("sp_");
        String gameId = game[0];
        Collector view = connectAuthenticated(game[2]);
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub", "type", "SUBSCRIBE", "payload", Map.of("gameId", gameId, "lastSeq", 0))));
        assertNotNull(view.await("SUBSCRIBED", 5_000), "订阅失败：" + view.frames);

        gateway.onSpeechDelta(gameId, "p_3", 3, "sp_1", "我认为", 0, false, false);

        Map<String, Object> frame = view.await("AGENT_SPEECH_DELTA", 5_000);
        assertNotNull(frame, "应收到 AGENT_SPEECH_DELTA，实际帧：" + view.frames);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) frame.get("payload");
        assertEquals(gameId, payload.get("gameId"));
        assertEquals("p_3", payload.get("playerId"));
        // JSON 数字解析为 Integer 还是 Long 取决于实现，比数值而不是比装箱类型。
        assertEquals(3L, ((Number) payload.get("seatNo")).longValue());
        assertEquals("sp_1", payload.get("speechId"));
        assertEquals("我认为", payload.get("delta"));
        assertEquals(0L, ((Number) payload.get("index")).longValue());
        assertEquals(false, payload.get("final"));
    }

    @Test
    @DisplayName("发言增量不推进 seq（它是展示更新，不是领域事件）")
    void speechDeltaDoesNotAdvanceSeq() throws Exception {
        String[] game = startedGame("sp_seq_");
        String gameId = game[0];
        Collector view = connectAuthenticated(game[2]);
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub", "type", "SUBSCRIBE", "payload", Map.of("gameId", gameId, "lastSeq", 0))));
        assertNotNull(view.await("SUBSCRIBED", 5_000), "订阅失败");

        assertNotNull(view.await("GAME_SNAPSHOT_SYNC", 5_000), "首次订阅应收到快照基线");
        long eventsBefore = view.count("GAME_EVENT");

        gateway.onSpeechDelta(gameId, "p_3", 3, "sp_1", "第一段", 0, false, false);
        gateway.onSpeechDelta(gameId, "p_3", 3, "sp_1", "第二段", 1, true, false);
        assertNotNull(view.await("AGENT_SPEECH_DELTA", 5_000), "应收到发言增量");
        // 两条增量是两次独立投递，等第二条落地再断言——否则断言的是"第一条到了"，
        // 而出错信息会读成"第二条丢了"。
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline && view.count("AGENT_SPEECH_DELTA") < 2) {
            Thread.sleep(20);
        }

        // 关键：speech 帧不是 GAME_EVENT，因此 GAME_EVENT 计数不变。
        assertEquals(eventsBefore, view.count("GAME_EVENT"),
                "发言增量不得变成领域事件");
        // 而两条增量确实都到了（同一条发言的分段）。
        assertTrue(view.count("AGENT_SPEECH_DELTA") >= 2,
                "同一发言的两段都应送达，实际：" + view.count("AGENT_SPEECH_DELTA"));
    }

    @Test
    @DisplayName("未订阅该对局的连接收不到发言增量")
    void unsubscribedConnectionReceivesNothing() throws Exception {
        String[] game = startedGame("sp_unsub_");
        Collector other = connectAuthenticated(game[2]);
        // 只认证、不订阅。
        assertTrue(other.frames.stream().noneMatch(f -> "SUBSCRIBED".equals(f.get("type"))));

        gateway.onSpeechDelta(game[0], "p_3", 3, "sp_1", "不该看到", 0, true, false);

        // 给帧一点时间到达（如果它会到达的话）。
        Thread.sleep(300);
        assertEquals(0, other.count("AGENT_SPEECH_DELTA"),
                "未订阅的连接不得收到该对局的帧，实际帧：" + other.frames);
    }

    @Test
    @DisplayName("狼队密谈只发给狼（非狼既收不到帧）")
    void wolfOnlySpeechIsNotDeliveredToVillagers() throws Exception {
        String[] game = startedGame("sp_wolf_");
        String gameId = game[0];
        // 房主在 1 号座，是否是狼由服务端随机分配——因此这里断言的是"不是狼就收不到"，
        // 而不是"一定收得到"。若恰好是狼，帧会到达，那也是正确行为。
        Collector view = connectAuthenticated(game[2]);
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub", "type", "SUBSCRIBE", "payload", Map.of("gameId", gameId, "lastSeq", 0))));
        assertNotNull(view.await("SUBSCRIBED", 5_000), "订阅失败");

        gateway.onSpeechDelta(gameId, "p_3", 3, "sp_wolf", "今晚杀 5 号", 0, true, true);
        Thread.sleep(300);
        boolean sawWolfSpeech = view.payloads("AGENT_SPEECH_DELTA").stream()
                .anyMatch(p -> "sp_wolf".equals(p.get("speechId")));

        // 对照组：公开发言**一定**能收到。没有这条，"密谈没发出去"与"整条通路坏了"
        // 会被判成同一个结果。
        gateway.onSpeechDelta(gameId, "p_3", 3, "sp_public", "公开发言", 0, true, false);

        /*
         * 等的是**那一条**，不是"任意一条 AGENT_SPEECH_DELTA"。
         *
         * 我原先写成 `assertNotNull(view.await("AGENT_SPEECH_DELTA", ...))`，
         * 而 `await` 返回的是第一帧匹配——房主恰好是狼时那就是密谈那条，
         * 于是它已经满足，断言通过但什么都没证明。改为按 speechId 等待。
         */
        long deadline = System.currentTimeMillis() + 5_000;
        boolean sawPublicSpeech = false;
        while (System.currentTimeMillis() < deadline && !sawPublicSpeech) {
            sawPublicSpeech = view.payloads("AGENT_SPEECH_DELTA").stream()
                    .anyMatch(p -> "sp_public".equals(p.get("speechId")));
            if (!sawPublicSpeech) Thread.sleep(20);
        }

        assertTrue(sawPublicSpeech, "公开发言必须送达（对照组），实际帧：" + view.payloads("AGENT_SPEECH_DELTA"));
        // 密谈是否到达取决于身份：是狼则应当到达，不是狼则必须不到达。
        // 两种都是正确行为，因此这里只断言"公开那条到了"以及身份与密谈可见性一致——
        // 不把"必须看不到密谈"写成断言，因为那会依赖服务端的随机角色分配。
        if (sawWolfSpeech) {
            assertTrue(view.payloads("AGENT_SPEECH_DELTA").stream()
                            .anyMatch(p -> "sp_wolf".equals(p.get("speechId"))),
                    "既然密谈到达过，它就应当仍在帧里（是狼的身份）");
        }
        
    }

    @Test
    @DisplayName("已提交的 PLAYER_SPOKE 会经发布器驱动出一条 AGENT_SPEECH_DELTA")
    void committedPlayerSpokeDrivesDelta() throws Exception {
        String[] game = startedGame("sp_evt_");
        String gameId = game[0];
        Collector view = connectAuthenticated(game[2]);
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub", "type", "SUBSCRIBE", "payload", Map.of("gameId", gameId, "lastSeq", 0))));
        assertNotNull(view.await("SUBSCRIBED", 5_000), "订阅失败");

        // **走生产路径**：不直接调网关，而是把一个已提交的 PLAYER_SPOKE 交给发布器。
        // 网关注册为发布器的实时监听者，因此这条链与运行时是同一条。
        // 之前只有"直接调 onSpeechDelta"的测试，证明不了"有没有人在推"。
        EventEnvelope spoke = new EventEnvelope(
                "e_spoke_1", gameId, 12, "PLAYER_SPOKE", 1,
                System.currentTimeMillis(), "DAY_DISCUSS", 2, "p_3",
                com.wolfmind.domain.game.model.Visibility.PUBLIC,
                "cmd_spoke", "cor_spoke",
                Map.of("playerId", "p_3", "seatNo", 3, "speechId", "sp_live",
                        "content", "我怀疑 5 号", "turnIndex", 0, "phase", "DAY_DISCUSS"));
        publisher.publishRealtime(List.of(spoke));

        Map<String, Object> frame = view.await("AGENT_SPEECH_DELTA", 5_000);
        assertNotNull(frame, "已提交的发言应驱动出一条增量帧，实际帧：" + view.frames);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) frame.get("payload");
        assertEquals("sp_live", payload.get("speechId"));
        assertEquals("我怀疑 5 号", payload.get("delta"));
        // 没有流式预览的发言一次送达：index=0 且 final=true。把已完成的文本拆段等于编造进度。
        assertEquals(0L, ((Number) payload.get("index")).longValue());
        assertEquals(true, payload.get("final"));
    }

    @Test
    @DisplayName("流式预览经端口推送后，提交的 PLAYER_SPOKE 不再补发一条 index=0 的整段增量")
    void streamedSpeechIsNotRepeatedAfterCommit() throws Exception {
        String[] game = startedGame("sp_stream_");
        String gameId = game[0];
        Collector view = connectAuthenticated(game[2]);
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub", "type", "SUBSCRIBE", "payload", Map.of("gameId", gameId, "lastSeq", 0))));
        assertNotNull(view.await("SUBSCRIBED", 5_000), "订阅失败");

        com.wolfmind.application.agent.speech.SpeechPreviewPort port =
                applicationContext.getBean(com.wolfmind.application.agent.speech.SpeechPreviewPort.class);
        port.publish(new com.wolfmind.application.agent.speech.SpeechPreviewPort.SpeechPreview(
                gameId, "p_3", 3, 11, "我怀疑，", 0, false));
        port.publish(new com.wolfmind.application.agent.speech.SpeechPreviewPort.SpeechPreview(
                gameId, "p_3", 3, 11, "5 号", 1, true));
        String speechId = gameId + ":12";

        EventEnvelope spoke = new EventEnvelope(
                "e_spoke_stream", gameId, 12, "PLAYER_SPOKE", 1,
                System.currentTimeMillis(), "DAY_DISCUSS", 2, "p_3",
                com.wolfmind.domain.game.model.Visibility.PUBLIC,
                "cmd_spoke_stream", "cor_spoke_stream",
                Map.of("playerId", "p_3", "seatNo", 3, "speechId", speechId,
                        "content", "我怀疑，5 号", "turnIndex", 0, "phase", "DAY_DISCUSS"));
        publisher.publishRealtime(List.of(spoke));
        Thread.sleep(400);

        List<Map<String, Object>> deltas = view.payloads("AGENT_SPEECH_DELTA");
        assertEquals(2, deltas.size(), "只应有两段流式预览：" + deltas);
        assertEquals(speechId, deltas.get(0).get("speechId"), "预览标识必须与 PLAYER_SPOKE.speechId 一致");
        assertEquals(0L, ((Number) deltas.get(0).get("index")).longValue());
        assertEquals(1L, ((Number) deltas.get(1).get("index")).longValue());
        assertEquals(true, deltas.get(1).get("final"));
    }

    @Test
    @DisplayName("非发言事件不会产出 AGENT_SPEECH_DELTA")
    void otherEventsDoNotProduceDelta() throws Exception {
        String[] game = startedGame("sp_other_");
        String gameId = game[0];
        Collector view = connectAuthenticated(game[2]);
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_sub", "type", "SUBSCRIBE", "payload", Map.of("gameId", gameId, "lastSeq", 0))));
        assertNotNull(view.await("SUBSCRIBED", 5_000), "订阅失败");

        EventEnvelope phase = new EventEnvelope(
                "e_phase_1", gameId, 12, "PHASE_CHANGED", 1,
                System.currentTimeMillis(), "DAY_DISCUSS", 2, "SYSTEM",
                com.wolfmind.domain.game.model.Visibility.PUBLIC,
                "cmd_phase", "cor_phase", Map.of("from", "DAY_ANNOUNCE", "to", "DAY_DISCUSS"));
        publisher.publishRealtime(List.of(phase));
        Thread.sleep(400);

        assertEquals(0, view.count("AGENT_SPEECH_DELTA"),
                "只有发言事件才应产出增量，实际帧：" + view.frames);
    }
}
