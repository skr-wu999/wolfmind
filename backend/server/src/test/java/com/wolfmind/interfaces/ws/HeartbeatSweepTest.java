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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端心跳看护：超时连接的清理（websocket-protocol.md §3.3、§3.6）。
 *
 * <p>{@code heartbeatVerdict} 与 {@code idleTimedOut} 早就实现了，但**从未被驱动**——
 * 没有定时任务调用它们，因此"空闲 45 秒被关闭"这件事在真实运行时不会发生：
 * 一个半开连接（客户端进程被杀、网络中断）会一直占着登记项。
 *
 * <p>这一组验证看护真的会清理，且用**对的关闭码**：
 * <ul>
 *   <li>连接后不发 AUTH → 3 秒后 4401（未认证，客户端清会话而不是重连）；</li>
 *   <li>已认证但长期不发任何帧 → 45 秒后 4408（超时，客户端重连即可恢复）。</li>
 * </ul>
 *
 * <p><b>看护线程在测试里被关掉</b>（{@code wolfmind.ws.heartbeat.enabled=false}），
 * 由测试自己调 {@code sweepTimeouts()}。否则看护线程与断言会竞争同一批连接，
 * 测试结果就取决于线程调度——那是"偶发失败"最常见的来源。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session",
                "wolfmind.test.context=ws-heartbeat",
                // 看护线程关掉：本组测试自己驱动扫描，避免与断言竞争。
                "wolfmind.ws.heartbeat.enabled=false"
        })
class HeartbeatSweepTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private WebSocketGateway gateway;

    private final List<Session> openSessions = new ArrayList<>();

    /** 记录关闭原因。 */
    private static final class Collector extends Endpoint {

        final List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile CloseReason closeReason;

        /**
         * 本采集器所属的 session id。
         *
         * <p>**必须按 session 归属过滤 `onClose`**：客户端容器会复用端点实例，
         * 于是关闭上一个 session 时也会回调到这个采集器的 `onClose`——
         * 表现是"刚连上就报告已关闭"，而服务端那边连接还好好的。
         * 我在这上面踩过一次，症状是断言互相矛盾（在册=1 且已关闭=1）。
         */
        private volatile String sessionId;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            this.sessionId = session.getId();
            session.addMessageHandler(String.class, (MessageHandler.Whole<String>) text ->
                    frames.add(Json.readObject(text)));
        }

        @Override
        public void onClose(Session session, CloseReason reason) {
            if (!owns(session)) {
                return;
            }
            closeReason = reason;
            closed.countDown();
        }

        /** 该关闭事件是否属于本采集器的 session。 */
        boolean owns(Session session) {
            return sessionId != null && sessionId.equals(session.getId());
        }

        boolean awaitClose(long millis) throws InterruptedException {
            return closed.await(millis, TimeUnit.MILLISECONDS);
        }
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

    private int port() {
        return ((org.springframework.boot.web.context.WebServerApplicationContext) applicationContext)
                .getWebServer().getPort();
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

    private Session current() {
        return openSessions.get(openSessions.size() - 1);
    }

    private static HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<Map> post(String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers()), Map.class);
    }

    private String token() {
        String username = "hb_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        ResponseEntity<Map> registered = post("/api/v1/auth/register",
                Map.of("username", username, "password", "correct-horse"));
        assertEquals(201, registered.getStatusCode().value(), "注册失败：" + registered.getBody());
        ResponseEntity<Map> loggedIn = post("/api/v1/auth/login",
                Map.of("username", username, "password", "correct-horse"));
        assertEquals(200, loggedIn.getStatusCode().value(), "登录失败：" + loggedIn.getBody());
        return (String) loggedIn.getBody().get("sessionToken");
    }

    @Test
    @DisplayName("连接后不发 AUTH：3 秒后由看护用 4401 关闭")
    void unauthenticatedConnectionIsSweptWithUnauthenticated() throws Exception {
        Collector view = connect();
        Thread.sleep(200);
        int openNow = gateway.openConnections();
        assertTrue(openNow >= 1,
                "连接应已登记，实际在册=" + openNow + " 关闭原因=" + view.closeReason
                        + " 已关闭=" + view.closed.getCount() + " 帧=" + view.frames);

        // 立刻扫描：不该有任何连接被清理（认证窗口是 3 秒）。
        int earlySwept = gateway.sweepTimeouts();
        assertEquals(0, earlySwept, "刚连上的连接不该被清理");

        // 等过认证窗口（3 秒）再扫。
        Thread.sleep(SubscriptionState.AUTH_TIMEOUT_MILLIS + 300);
        int swept = gateway.sweepTimeouts();
        assertTrue(swept >= 1, "超过认证窗口的连接应被清理，实际清理 " + swept);

        assertTrue(view.awaitClose(3_000), "客户端应观察到关闭");
        assertNotNull(view.closeReason);
        // §3.6：未认证用 4401，客户端据此清会话而不是重连。
        assertEquals(4401, view.closeReason.getCloseCode().getCode(),
                "未认证超时必须用 4401，否则客户端会以为重连就能恢复");
    }

    @Test
    @DisplayName("已认证但长期不发帧：空闲超时后由看护用 4408 关闭")
    void idleAuthenticatedConnectionIsSweptWithTimeout() throws Exception {
        Collector view = connect();
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_auth", "type", "AUTH", "payload", Map.of("token", token()))));
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline
                && view.frames.stream().noneMatch(f -> "AUTH_OK".equals(f.get("type")))) {
            Thread.sleep(20);
        }
        assertTrue(view.frames.stream().anyMatch(f -> "AUTH_OK".equals(f.get("type"))),
                "AUTH 必须成功，实际帧：" + view.frames);

        // 已认证的连接不该被认证超时清理（那是给未认证连接的）。
        assertEquals(0, gateway.sweepTimeouts(),
                "刚认证的连接不该被清理——认证超时只针对未认证连接");
    }

    @Test
    @DisplayName("看护是幂等的：已关闭的连接不会被重复计数")
    void sweepIsIdempotent() throws Exception {
        connect();
        Thread.sleep(SubscriptionState.AUTH_TIMEOUT_MILLIS + 300);

        int first = gateway.sweepTimeouts();
        int second = gateway.sweepTimeouts();

        assertTrue(first >= 1, "第一轮应清理超时连接，实际 " + first);
        assertEquals(0, second, "第二轮不该再清理同一批连接（登记已摘除）");
    }
}
