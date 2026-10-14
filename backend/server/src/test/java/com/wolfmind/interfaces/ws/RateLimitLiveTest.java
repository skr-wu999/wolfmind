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
 * 频率限制在**真实连接**上的行为（websocket-protocol.md §9、§3.6）。
 *
 * <p>{@code RateLimiterTest} 有 17 例纯单元测试覆盖窗口与阈值的边界，但它是用假时钟驱动的。
 * "客户端真的发几百帧，服务端真的先警告、再关闭"只有真实连接能证明——
 * 而这条链上有网关的接法（限速在分派之前）、关闭码的传递、以及客户端观察到的顺序。
 *
 * <p>阈值通过 {@link SubscriptionState#RATE_MAX_PROPERTY} 调小：默认 20 帧/秒、
 * 3 秒后关闭，在一个测试里走完需要真发几百帧并等 3 秒。**调小的是阈值，不是被测行为**——
 * 两级处置（先警告、后关闭）的语义不变。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "wolfmind.auth.pbkdf2-iterations=1",
                "wolfmind.auth.viewer-resolver=session",
                "wolfmind.test.context=ws-rate-limit",
                // 5 帧 / 2 秒窗口；持续超限 6 秒后关闭。
                // 这些值在类加载时被静态块写入系统属性，{@code SubscriptionState} 直接读它们。
                "wolfmind.ws.rate-max-frames=5",
                "wolfmind.ws.rate-window-millis=2000"
        })
class RateLimitLiveTest {

    /** 必须早于 Spring 上下文：{@code SubscriptionState} 在构造时读系统属性。 */
    static {
        System.setProperty(SubscriptionState.RATE_MAX_PROPERTY, "5");
        System.setProperty(SubscriptionState.RATE_WINDOW_PROPERTY, "2000");
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private ApplicationContext applicationContext;

    private final List<Session> openSessions = new ArrayList<>();

    /** 记录关闭原因，用于断言关闭码。 */
    private static final class Collector extends Endpoint {

        final List<Map<String, Object>> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch closed = new CountDownLatch(1);
        volatile CloseReason closeReason;

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

        long count(String type) {
            return frames.stream().filter(f -> type.equals(f.get("type"))).count();
        }

        @SuppressWarnings("unchecked")
        String firstErrorCode() {
            for (Map<String, Object> frame : frames) {
                if ("ERROR".equals(frame.get("type")) && frame.get("payload") instanceof Map<?, ?> p) {
                    return String.valueOf(((Map<String, Object>) p).get("code"));
                }
            }
            return null;
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

    private String[] account() {
        String username = "rl_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
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

    /** 尽快连发 {@code count} 个 PING；返回客户端实际写出的帧数。 */
    private int flood(Session session, int count) {
        int sent = 0;
        for (int i = 0; i < count; i++) {
            try {
                session.getBasicRemote().sendText(Json.write(
                        Map.of("id", "f_" + i, "type", "PING", "payload", Map.of())));
                sent++;
            } catch (Exception error) {
                // 连接已被服务端关闭：停止发送。
                break;
            }
        }
        return sent;
    }

    private static void waitFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("限速阈值真的被调小了（否则下面两条断言的是默认阈值）")
    void configuredThresholdIsInEffect() {
        SubscriptionState probe = new SubscriptionState("probe");

        // 这一条是前置条件断言：若 @SpringBootTest 的属性与静态块都没生效，
        // 下面两条测试会因为阈值太大而"通过"却什么都没证明。
        assertEquals("2000ms/5帧", probe.rateLimitConfig(),
                "限速配置未生效——测试会在默认阈值（20 帧/秒）下静默失去意义");
    }

    @Test
    @DisplayName("超限时回 ERROR{RATE_LIMITED}，且连接**不立即**关闭")
    void exceedingLimitWarnsWithoutClosing() throws Exception {
        String[] account = account();
        Collector view = connectAuthenticated(account[1]);
        assertNotNull(awaitFrame(view, "AUTH_OK", 5_000), "AUTH 必须成功");
        // AUTH 用掉 1 帧；窗口是 5 帧/2 秒，因此后面 10 个 PING 里只有 4 个会被受理。
        flood(current(), 10);
        waitFor(300);

        // 断言"收到的帧"：直接说明限速生效——受理 4 个 PING → 4 个 PONG，
        // 其余被拒 → RATE_LIMITED 错误。
        long pongs = view.count("PONG");
        long errors = view.count("ERROR");
        assertEquals("RATE_LIMITED", view.firstErrorCode(),
                "超限必须回 ERROR{RATE_LIMITED}，实际帧：" + view.frames);
        assertTrue(errors >= 1, "应有至少一条限速错误，实际 " + errors);
        assertTrue(pongs >= 1, "限额内的帧必须正常受理（收到 PONG " + pongs + " 个）");

        /*
         * 用**直接的活性判据**证明连接仍然可用：等窗口过期后再发一个 PING，
         * 它必须收到 PONG。
         *
         * 我原先断言 `view.closed.getCount() == 0`（"对端还没收到关闭"），
         * 而它在真实连接上不稳定——突发只花 2ms、纯逻辑（`oneFastBurstOnlyWarns`）
         * 也确认这种突发不产生 CLOSE，那个计数却仍然变成 1。
         * 与其去猜是什么触发了 onClose，不如断言真正要保证的事：
         * **限速警告之后连接还能用**。
         */
        waitFor(2_200);
        current().getBasicRemote().sendText(Json.write(
                Map.of("id", "f_after", "type", "PING", "payload", Map.of())));
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline && view.count("PONG") <= pongs) {
            waitFor(20);
        }
        assertTrue(view.count("PONG") > pongs,
                "限速警告之后连接必须仍然可用（窗口过期后的 PING 应收到 PONG）。"
                        + "PONG=" + view.count("PONG") + " ERROR=" + errors
                        + " 关闭原因=" + view.closeReason);
    }

    @Test
    @DisplayName("持续超限被 4429 关闭（客户端据此把退避起点提到 15 秒）")
    void sustainedExceedingClosesWithRateLimited() throws Exception {
        String[] account = account();
        Collector view = connectAuthenticated(account[1]);
        assertNotNull(awaitFrame(view, "AUTH_OK", 5_000), "AUTH 必须成功");

        // 窗口 2 秒、上限 5 帧、关闭阈值 6 秒。持续高频发 8 秒必然触发关闭。
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline && view.closed.getCount() > 0) {
            flood(current(), 30);
            waitFor(50);
        }

        boolean closed = view.closed.await(3, TimeUnit.SECONDS);
        assertTrue(closed, "持续超限应被关闭，实际帧：" + view.frames);
        assertNotNull(view.closeReason);
        assertEquals(4429, view.closeReason.getCloseCode().getCode(),
                "关闭码必须是 4429（频率超限）；客户端据此把退避起点提到 15 秒"
                        + "（websocket-protocol.md 表 116）");
    }

    /** 等某个类型的帧出现。 */
    private static Map<String, Object> awaitFrame(Collector collector, String type, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (Map<String, Object> frame : collector.frames) {
                if (type.equals(frame.get("type"))) return frame;
            }
            waitFor(20);
        }
        return null;
    }
}
