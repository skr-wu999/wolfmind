package com.wolfmind.interfaces.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端帧频率限制（websocket-protocol.md §9、§3.6）。
 *
 * <p>文档要求两级处置：**超限先回 {@code ERROR{code:"RATE_LIMITED"}}，持续超限才关闭 4429**。
 * 这个顺序不是细节——直接断开会让偶发突发（连点两下、页面恢复时补发积压 ACK）
 * 变成掉线重连，而重连本身又带来一批帧，形成"越忙越断"的循环。
 *
 * <p>这些断言能离线跑，是因为限速被刻意做成不依赖 WebSocket 类型的纯组件：
 * 窗口与阈值都是构造参数，用假时钟就能覆盖所有边界。
 */
class RateLimiterTest {

    private static final long T0 = 1_000_000L;

    @Test
    @DisplayName("窗口内未超限时一律放行")
    void allowsUpToLimit() {
        RateLimiter limiter = new RateLimiter(1_000L, 3);

        assertEquals(RateLimiter.Verdict.ALLOW, limiter.record(T0));
        assertEquals(RateLimiter.Verdict.ALLOW, limiter.record(T0 + 1));
        assertEquals(RateLimiter.Verdict.ALLOW, limiter.record(T0 + 2));
        assertEquals(3, limiter.windowCount(T0 + 2));
        assertFalse(limiter.everExceeded());
    }

    @Test
    @DisplayName("超出上限先给 WARN，不立刻关闭")
    void warnsBeforeClosing() {
        RateLimiter limiter = new RateLimiter(1_000L, 2);
        limiter.record(T0);
        limiter.record(T0 + 1);

        // 第 3 帧超限：警告而不是断开。
        assertEquals(RateLimiter.Verdict.WARN, limiter.record(T0 + 2));
        assertTrue(limiter.everExceeded());
    }

    @Test
    @DisplayName("持续超限 3 个窗口后关闭")
    void closesAfterSustainedViolation() {
        RateLimiter limiter = new RateLimiter(1_000L, 1);
        limiter.record(T0);

        assertEquals(RateLimiter.Verdict.WARN, limiter.record(T0 + 10), "首次超限应只警告");

        // 持续超限（每 100ms 一次，远密于窗口）：从首次超限起算满 3 个窗口后关闭。
        // 循环上界取 5 秒而不是 3 秒——恰好等于 closeAfterMillis 的循环会因为
        // 少走一格而永远看不到 CLOSE，而失败信息会读成"实现不关闭"。
        RateLimiter.Verdict last = RateLimiter.Verdict.WARN;
        for (long at = T0 + 20; at <= T0 + 5_000; at += 100) {
            last = limiter.record(at);
            if (last == RateLimiter.Verdict.CLOSE) break;
        }
        assertEquals(RateLimiter.Verdict.CLOSE, last,
                "持续超限应最终关闭，而不是永远只警告");
    }

    @Test
    @DisplayName("违规间隔拉长后重新从警告起算（偶发突发不累积成关闭）")
    void spacedViolationsStartOver() {
        RateLimiter limiter = new RateLimiter(1_000L, 1);
        limiter.record(T0);
        assertEquals(RateLimiter.Verdict.WARN, limiter.record(T0 + 10));

        // 安静 10 个窗口之后再来一次超限：间隔远大于 closeAfterMillis，
        // 因此应重新从警告起算，而不是"因为很久以前超限过"直接关闭。
        long later = T0 + 10 + 10_000;
        limiter.record(later);
        assertEquals(RateLimiter.Verdict.WARN, limiter.record(later + 10),
                "间隔拉长后应重新警告而不是关闭");
    }

    @Test
    @DisplayName("超限的帧不记入窗口（否则降频后也要等一整段安静期才能恢复）")
    void rejectedFramesDoNotFillWindow() {
        RateLimiter limiter = new RateLimiter(1_000L, 2);
        limiter.record(T0);
        limiter.record(T0 + 1);

        // 连发 50 个超限帧。
        for (int i = 0; i < 50; i++) {
            limiter.record(T0 + 2 + i);
        }
        // 窗口里仍然只有那 2 个被接受的帧。
        assertEquals(2, limiter.windowCount(T0 + 60),
                "被拒的帧不得占窗口，否则客户端降低频率后仍要长时间等待");
    }

    @Test
    @DisplayName("滑动窗口：固定分桶会在边界上放过 2 倍流量")
    void slidingWindowClosesBoundaryHole() {
        RateLimiter limiter = new RateLimiter(1_000L, 2);
        // 桶边界前用掉 2 帧。
        limiter.record(999L);
        limiter.record(999L);
        // 边界后用，仍是同一个滑动窗口内 → 应被拒。
        assertEquals(RateLimiter.Verdict.WARN, limiter.record(1_001L),
                "滑动窗口必须跨过固定分桶的边界，否则限制形同虚设");
    }

    @Test
    @DisplayName("窗口过期后帧被清出")
    void evictsExpiredFrames() {
        RateLimiter limiter = new RateLimiter(1_000L, 5);
        limiter.record(T0);
        limiter.record(T0 + 100);
        assertEquals(2, limiter.windowCount(T0 + 100));

        assertEquals(1, limiter.windowCount(T0 + 1_050), "第一帧应已过期");
        assertEquals(0, limiter.windowCount(T0 + 1_200), "两帧都应已过期");
    }

    @ParameterizedTest
    @CsvSource({"0, 5", "1000, 0", "-1, 5", "1000, -1"})
    @DisplayName("窗口与上限必须为正数（非法参数在构造期就失败）")
    void rejectsInvalidParameters(long window, int max) {
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(window, max));
    }

    @Test
    @DisplayName("一次快速突发（1+10 帧）只警告，不关闭")
    void oneFastBurstOnlyWarns() {
        // 这正是契约测试 RateLimitLiveTest 的真实场景：AUTH 1 帧 + 连发 10 个 PING。
        // 纯逻辑确认它不产生 CLOSE——若这里有 CLOSE，那"首次超限不得关闭"在真实连接上
        // 失败就说明是**别的东西**在关闭连接。
        RateLimiter limiter = new RateLimiter(2_000L, 5);

        // 一次性突发：所有帧都在同一毫秒。
        int warns = 0;
        int allows = 0;
        boolean sawClose = false;
        for (int i = 0; i < 11; i++) {
            switch (limiter.record(T0)) {
                case ALLOW -> allows++;
                case WARN -> warns++;
                case CLOSE -> sawClose = true;
            }
        }

        assertEquals(5, allows, "窗口 5 帧应全部受理");
        assertEquals(6, warns, "其余 6 帧应只警告");
        assertFalse(sawClose, "一次快速突发**不得**触发关闭");
    }

    @Test
    @DisplayName("默认参数：1 秒 20 帧、3 个窗口后关闭")
    void defaultParameters() {
        RateLimiter limiter = new RateLimiter();

        // 20 帧放行，第 21 帧警告。
        for (int i = 0; i < 20; i++) {
            assertEquals(RateLimiter.Verdict.ALLOW, limiter.record(T0 + i),
                    "第 " + (i + 1) + " 帧应放行");
        }
        assertEquals(RateLimiter.Verdict.WARN, limiter.record(T0 + 20));
    }

    @Test
    @DisplayName("reset 清空窗口与违规计时")
    void resetClears() {
        RateLimiter limiter = new RateLimiter(1_000L, 1);
        limiter.record(T0);
        limiter.record(T0 + 1);

        limiter.reset();

        assertEquals(0, limiter.windowCount(T0 + 2));
        assertEquals(RateLimiter.Verdict.ALLOW, limiter.record(T0 + 2));
    }
}

/**
 * 限速在协议状态机里的接法（§9：超限回 {@code ERROR{code:RATE_LIMITED}}，持续超限关 4429）。
 */
class SubscriptionStateRateLimitTest {

    private static final long NOW = 1_730_000_000_000L;

    private static SubscriptionState connected() {
        // **显式给阈值**，不依赖系统属性：RateLimitLiveTest 的静态块会设置全局属性，
        // 而测试执行顺序不确定——依赖属性会让这个类的结果随别的类而变（本轮就被污染过）。
        SubscriptionState state = new SubscriptionState("conn_rl", 1_000L, 20);
        state.onConnected(NOW);
        return state;
    }

    @Test
    @DisplayName("未超限时不返回错误帧")
    void allowsUnderLimit() {
        SubscriptionState state = connected();

        assertNull(state.checkRateLimit(NOW), "正常频率不应产生错误帧");
        assertFalse(state.rateLimitExceeded());
    }

    @Test
    @DisplayName("首次超限返回 ERROR{RATE_LIMITED}，但没有标记关闭")
    void warnsOnFirstViolation() {
        SubscriptionState state = connected();
        // 默认上限 20 帧/秒：先正常发满。
        for (int i = 0; i < 20; i++) {
            assertNull(state.checkRateLimit(NOW + i));
        }

        ServerFrame frame = state.checkRateLimit(NOW + 20);

        assertNotNull(frame, "超限应回错误帧");
        assertEquals("ERROR", frame.type());
        assertEquals("RATE_LIMITED", frame.payload().get("code"));
        assertFalse(state.rateLimitExceeded(), "首次超限**不得**标记关闭");
        assertEquals(SubscriptionState.Phase.CONNECTED, state.phase(),
                "警告之后连接必须仍然可用");
    }

    @Test
    @DisplayName("持续超限后标记关闭（网关据此发 4429）")
    void marksCloseAfterSustainedViolation() {
        SubscriptionState state = connected();
        for (int i = 0; i < 20; i++) {
            state.checkRateLimit(NOW + i);
        }

        /*
         * 持续超限：必须**真的**超过上限。
         *
         * 我第一版用 200ms 的间隔——那是每秒 5 帧，远低于 20 帧/秒的上限，
         * 因此第一个突发窗口过期后客户端就不再违规了，永远关不掉。
         * 而失败信息读成「实现不关闭」，实际是**测试没有在滥用**。
         * 现在用 20ms 间隔（每秒 50 帧）真正持续超限。
         */
        boolean closed = false;
        for (long at = NOW + 20; at <= NOW + 8_000; at += 20) {
            state.checkRateLimit(at);
            if (state.rateLimitExceeded()) {
                closed = true;
                break;
            }
        }

        assertTrue(closed, "持续超限应最终标记关闭。窗口内帧数=" + state.rateWindowCount(NOW + 6_000)
                + " phase=" + state.phase());
        assertEquals(SubscriptionState.Phase.CLOSED, state.phase());
    }

    @Test
    @DisplayName("已关闭的连接不再处理限速（避免对死连接继续计数）")
    void closedConnectionSkipsRateLimit() {
        SubscriptionState state = connected();
        state.onAuth("bad", token -> java.util.Optional.empty(), NOW);
        assertEquals(SubscriptionState.Phase.CLOSED, state.phase());

        assertNull(state.checkRateLimit(NOW + 1));
    }
}
