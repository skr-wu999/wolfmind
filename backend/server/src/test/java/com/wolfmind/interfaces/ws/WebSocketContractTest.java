package com.wolfmind.interfaces.ws;

import com.wolfmind.infrastructure.json.Json;
import com.wolfmind.infrastructure.ws.WebSocketEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WebSocket 端点的真实连接契约测试（websocket-protocol.md §3、§4、§6）。
 *
 * <p>协议语义另有 23 条纯单元测试（{@code SubscriptionStateTest}），这里只验证那些
 * **只有真实连接才能证明**的事：端点确实注册在 {@code /ws}、握手能完成、JSON 帧能双向收发、
 * {@code AUTH} 之后拿到 {@code AUTH_OK}、坏帧回 {@code ERROR} 而不断开连接、无效令牌触发关闭。
 * 两者互补而非重复：后者验证 seq 规则，前者验证"能不能连上"。
 *
 * <p><b>{@code wolfmind.test.context=ws} 这个属性不是装饰</b>：Spring 按属性集合缓存上下文，
 * 而本类的属性与 {@code AuthContractTest} 完全一致时两者会**共享同一个上下文**（进而共享
 * 同一个内存会话存储）。单跑通过、全量跑失败的现象就是这么来的——会话存储被另一个测试类
 * 的用例写满了。加一个独有的属性值让上下文彻底隔离，避免测试之间通过共享状态互相影响。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session",
                "wolfmind.test.context=ws"
        })
class WebSocketContractTest {

    /**
     * 服务端随机端口。
     *
     * <p>直接从运行中的 {@code WebServer} 取，而不是依赖
     * {@code @LocalServerPort}（包路径在 Spring Boot 版本间变过）或
     * {@code @Value("${local.server.port}")}（实测在该上下文里没解析出来，端口为 0）。
     * 从 WebServer 取是唯一不依赖任何注入约定的方式。
     */
    private int port() {
        return ((org.springframework.boot.web.context.WebServerApplicationContext) applicationContext)
                .getWebServer().getPort();
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Autowired
    private TestRestTemplate rest;

    private final List<Session> openSessions = new ArrayList<>();

    @AfterEach
    void closeSessions() {
        for (Session session : openSessions) {
            try {
                session.close();
            } catch (Exception ignored) {
                // 测试清理失败不影响断言结果。
            }
        }
        openSessions.clear();
    }

    /** 收集服务端帧的客户端端点。 */
    private static final class Collector extends Endpoint {

        final List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile CloseReason closeReason;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text ->
                    frames.add(Json.readObject(text)));
        }

        @Override
        public void onClose(Session session, CloseReason reason) {
            closeReason = reason;
            closed.countDown();
        }

        /** 等待某个类型的帧出现；超时返回 null。 */
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

        boolean awaitClose(long timeoutMillis) {
            try {
                return closed.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        List<String> types() {
            return frames.stream().map(frame -> String.valueOf(frame.get("type"))).toList();
        }

        private static void sleep() {
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private Collector connect() throws Exception {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        Collector collector = new Collector();
        Session session = container.connectToServer(
                collector,
                ClientEndpointConfig.Builder.create().build(),
                URI.create("ws://localhost:" + port() + WebSocketEndpoint.PATH));
        openSessions.add(session);
        return collector;
    }

    private static void send(Session session, String json) throws Exception {
        session.getBasicRemote().sendText(json);
    }

    private Session lastSession() {
        return openSessions.get(openSessions.size() - 1);
    }

    /** 注册一个账号并登录，返回会话令牌。 */
    private String registerAndLogin() {
        String username = "ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<Map> registered = rest.postForEntity("/api/v1/auth/register",
                Map.of("username", username, "password", "correct-horse"), Map.class);
        assertEquals(HttpStatus.CREATED.value(), registered.getStatusCode().value(), "注册失败：" + registered.getBody());
        ResponseEntity<Map> loggedIn = rest.postForEntity("/api/v1/auth/login",
                Map.of("username", username, "password", "correct-horse"), Map.class);
        assertEquals(HttpStatus.OK.value(), loggedIn.getStatusCode().value(), "登录失败：" + loggedIn.getBody());
        return (String) loggedIn.getBody().get("sessionToken");
    }

    @Test
    @DisplayName("端点注册在 /ws：握手成功并收到对 AUTH 的 AUTH_OK")
    void authHandshakeOverRealConnection() throws Exception {
        Collector collector = connect();
        String token = registerAndLogin();

        send(lastSession(), Json.write(Map.of(
                "id", "f_1", "type", "AUTH", "payload", Map.of("token", token))));

        Map<String, Object> authOk = collector.await("AUTH_OK", 5_000);
        assertNotNull(authOk, "5 秒内未收到 AUTH_OK，实际收到：" + collector.frames);
        // 信封字段齐全（§4.1）
        for (String field : List.of("v", "id", "type", "ts", "seq", "payload")) {
            assertTrue(authOk.containsKey(field), "AUTH_OK 缺字段 " + field);
        }
        // JSON 数字统一解析为 Long，因此比数值而不是比装箱类型。
        assertEquals(1L, ((Number) authOk.get("v")).longValue(), "协议版本为 1");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) authOk.get("payload");
        assertNotNull(payload.get("userId"));
        // 三个协商参数必须由服务端下发，客户端不能自己假设。
        // 注意 JSON 数字统一解析为 Long，因此比数值而不是比装箱类型——
        // 用 assertEquals(int, Object) 会在值相同的情况下失败（踩过两次）。
        assertEquals((long) SubscriptionState.HEARTBEAT_INTERVAL_MILLIS,
                ((Number) payload.get("heartbeatIntervalMs")).longValue());
        assertEquals((long) SubscriptionState.IDLE_TIMEOUT_MILLIS,
                ((Number) payload.get("idleTimeoutMs")).longValue());
        assertEquals((long) SubscriptionState.MAX_PENDING_FRAMES,
                ((Number) payload.get("maxPendingFrames")).longValue());
        // 令牌绝不回显。
        assertFalse(authOk.toString().contains(token), "服务端帧里绝不能出现会话令牌");
    }

    @Test
    @DisplayName("PING 收到 PONG（心跳在真实连接上可用）")
    void pingPongOverRealConnection() throws Exception {
        Collector collector = connect();
        send(lastSession(), Json.write(Map.of(
                "id", "f_auth", "type", "AUTH", "payload", Map.of("token", registerAndLogin()))));
        assertNotNull(collector.await("AUTH_OK", 5_000), "AUTH 未成功");

        send(lastSession(), Json.write(Map.of("id", "f_ping", "type", "PING", "payload", Map.of())));

        Map<String, Object> pong = collector.await("PONG", 5_000);
        assertNotNull(pong, "5 秒内未收到 PONG，实际收到：" + collector.types());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) pong.get("payload");
        assertNotNull(payload.get("serverSeq"));
        assertNotNull(payload.get("serverTime"));
    }

    @Test
    @DisplayName("坏 JSON / 缺字段 / 未知类型都回 ERROR 帧，且连接保持可用")
    void malformedFramesGetErrorWithoutClosing() throws Exception {
        Collector collector = connect();
        send(lastSession(), Json.write(Map.of(
                "id", "f_auth", "type", "AUTH", "payload", Map.of("token", registerAndLogin()))));
        assertNotNull(collector.await("AUTH_OK", 5_000), "AUTH 未成功");

        // 三种形态错误：非 JSON、缺 type、目录外的类型。
        send(lastSession(), "{ this is not json");
        send(lastSession(), Json.write(Map.of("id", "f_2")));
        send(lastSession(), Json.write(Map.of("id", "f_3", "type", "TELEPORT", "payload", Map.of())));

        // 三次错误都应得到 ERROR，而不是连接被断开。
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && collector.frames.stream().filter(f -> "ERROR".equals(f.get("type"))).count() < 3) {
            Thread.sleep(20);
        }
        long errorCount = collector.frames.stream().filter(f -> "ERROR".equals(f.get("type"))).count();
        assertEquals(3, errorCount, "每一次坏帧都应有独立的 ERROR，实际帧序列：" + collector.types());

        // 连接仍然可用：再发一个 PING 应收到 PONG。
        // 这是"坏帧不断开连接"的关键证据——断开会让客户端的重连逻辑反复触发。
        send(lastSession(), Json.write(Map.of("id", "f_4", "type", "PING", "payload", Map.of())));
        assertNotNull(collector.await("PONG", 5_000), "坏帧之后连接必须仍然可用");
    }

    @Test
    @DisplayName("无效令牌：回 ERROR 并用 4401 关闭连接（客户端据此清会话而非重连）")
    void invalidTokenClosesWithUnauthenticated() throws Exception {
        Collector collector = connect();

        send(lastSession(), Json.write(Map.of(
                "id", "f_auth", "type", "AUTH", "payload", Map.of("token", "u_1.bogus.secret"))));

        Map<String, Object> error = collector.await("ERROR", 5_000);
        assertNotNull(error, "无效令牌应回 ERROR，实际收到：" + collector.types());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) error.get("payload");
        assertEquals("AUTH_REQUIRED", payload.get("code"));

        assertTrue(collector.awaitClose(5_000), "无效令牌之后连接必须被关闭");
        assertNotNull(collector.closeReason);
        assertEquals(4401, collector.closeReason.getCloseCode().getCode(),
                "关闭码必须是 4401（未认证），客户端据此清会话而不是重连");
    }

    @Test
    @DisplayName("未认证就订阅：回 AUTH_REQUIRED，且不建立订阅")
    void subscribeBeforeAuthIsRejectedOverRealConnection() throws Exception {
        Collector collector = connect();

        send(lastSession(), Json.write(Map.of(
                "id", "f_sub", "type", "SUBSCRIBE",
                "payload", Map.of("gameId", "g_ws_probe", "lastSeq", 0))));

        Map<String, Object> error = collector.await("ERROR", 5_000);
        assertNotNull(error, "未认证订阅应回 ERROR，实际收到：" + collector.types());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) error.get("payload");
        assertEquals("AUTH_REQUIRED", payload.get("code"));
        // 关键：不得出现 SUBSCRIBED——否则客户端会以为自己订阅成功了。
        assertFalse(collector.types().contains("SUBSCRIBED"),
                "未认证不得建立订阅，实际帧序列：" + collector.types());
    }

    @Test
    @DisplayName("SUBSCRIBE 不存在的对局：仍回 SUBSCRIBED 且 to=0，不断开连接")
    void subscribeToUnknownGameStillAcknowledges() throws Exception {
        Collector collector = connect();
        send(lastSession(), Json.write(Map.of(
                "id", "f_auth", "type", "AUTH", "payload", Map.of("token", registerAndLogin()))));
        assertNotNull(collector.await("AUTH_OK", 5_000), "AUTH 未成功");

        send(lastSession(), Json.write(Map.of(
                "id", "f_sub", "type", "SUBSCRIBE",
                "payload", Map.of("gameId", "g_does_not_exist", "lastSeq", 0))));

        Map<String, Object> subscribed = collector.await("SUBSCRIBED", 5_000);
        assertNotNull(subscribed, "订阅一个不存在的对局也应得到 SUBSCRIBED，实际："
                + collector.types());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) subscribed.get("payload");
        assertEquals("g_does_not_exist", payload.get("gameId"));
        assertEquals(0L, ((Number) payload.get("to")).longValue(), "不存在的对局流尾为 0");
        // 未截断：请求 0 而服务端持有 0，没有缺口。
        assertEquals(false, payload.get("truncated"));
        // scope 由服务端判定，请求里没有观战参数。
        assertEquals("SPECTATOR", payload.get("scope"));
    }

    @Test
    @DisplayName("RESUME 契约：resumed=true，from=lastSeq+1")
    void resumeAcknowledgesGapStart() throws Exception {
        Collector collector = connect();
        send(lastSession(), Json.write(Map.of(
                "id", "f_auth", "type", "AUTH", "payload", Map.of("token", registerAndLogin()))));
        assertNotNull(collector.await("AUTH_OK", 5_000), "AUTH 未成功");

        send(lastSession(), Json.write(Map.of(
                "id", "f_resume", "type", "RESUME",
                "payload", Map.of("gameId", "g_resume_probe", "lastSeq", 7))));

        Map<String, Object> subscribed = collector.await("SUBSCRIBED", 5_000);
        assertNotNull(subscribed, "RESUME 应得到 SUBSCRIBED，实际：" + collector.types());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) subscribed.get("payload");
        assertEquals(true, payload.get("resumed"), "RESUME 必须标记 resumed=true");
        // from = lastSeq + 1 = 8：客户端据此知道补发从第 8 版开始。
        assertEquals(8L, ((Number) payload.get("from")).longValue());
    }

    @Test
    @DisplayName("两个连接互不影响：各自独立认证与订阅")
    void connectionsAreIndependent() throws Exception {
        Collector first = connect();
        send(lastSession(), Json.write(Map.of(
                "id", "f_auth", "type", "AUTH", "payload", Map.of("token", registerAndLogin()))));
        assertNotNull(first.await("AUTH_OK", 5_000), "第一个连接认证失败");

        // 第二个连接：在它上面发一个坏帧，不应影响第一个连接。
        Collector second = connect();
        send(lastSession(), "{ broken");

        Map<String, Object> error = second.await("ERROR", 5_000);
        assertNotNull(error, "第二个连接应收到自己的 ERROR");
        assertFalse(first.types().contains("ERROR"),
                "第二个连接的坏帧不得影响第一个连接，第一个连接实际收到：" + first.types());

        // 第一个连接仍然可用。
        send(openSessions.get(0), Json.write(
                Map.of("id", "f_ping", "type", "PING", "payload", Map.of())));
        assertNotNull(first.await("PONG", 5_000), "第一个连接必须仍然可用");
    }
}
