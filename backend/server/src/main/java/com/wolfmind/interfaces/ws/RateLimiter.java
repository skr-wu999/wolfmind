package com.wolfmind.interfaces.ws;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 每连接的客户端帧频率限制（websocket-protocol.md §9「发送速率限制」、§3.6）。
 *
 * <p>文档要求两级处置：**超限先回 {@code ERROR{code:"RATE_LIMITED"}}，
 * 持续超限才关闭 4429**。这个"先警告后关闭"的顺序不是细节——直接断开会让一个
 * 偶发突发（例如用户连点两下、或页面刚恢复时补发积压的 ACK）变成掉线重连，
 * 而重连本身又会带来一批帧，于是形成"越忙越断"的循环。
 *
 * <p><b>为什么它不依赖任何 WebSocket 类型</b>：与 {@link SubscriptionState} 同样的理由。
 * 限速的语义（窗口、阈值、警告与关闭的分界）是最容易算错的部分，
 * 而它完全可以离线验证——把窗口和阈值设小，用假时钟驱动就能覆盖所有边界。
 *
 * <p>窗口用**滑动**（记录每帧的到达时刻）而不是固定分桶：固定分桶允许在分桶边界上
 * 通过 2 倍流量（前一个桶的末尾加后一个桶的开头），而"限制"的意义正是防这个。
 */
final class RateLimiter {

    /** 默认窗口：1 秒。 */
    static final long DEFAULT_WINDOW_MILLIS = 1_000L;

    /**
     * 默认上限：每秒 20 帧。
     *
     * <p>定在 20 而不是更小：正常操作（提交行动、ACK、心跳、断线后的 RESUME）远低于它，
     * 而一个页面在恢复瞬间补发积压 ACK 时可能到十几帧——阈值太低会把正常行为判成滥用。
     */
    static final int DEFAULT_MAX_FRAMES = 20;

    /** 持续超限多久后关闭：3 个窗口。 */
    static final int VIOLATION_WINDOWS_BEFORE_CLOSE = 3;

    private final long windowMillis;
    private final int maxFrames;
    private final long closeAfterMillis;

    /** 窗口内每帧的到达时刻，队首最旧。 */
    private final Deque<Long> timestamps = new ArrayDeque<>();

    /** 首次超限的时刻；未超限时为 {@code null}。 */
    private Long violationSince;

    /** 最近一次超限的时刻；用于判断违规是否"持续"。 */
    private Long lastViolationAt;

    /** 是否曾经超限过（用于诊断与测试）。 */
    private boolean everExceeded;

    RateLimiter() {
        this(DEFAULT_WINDOW_MILLIS, DEFAULT_MAX_FRAMES);
    }

    RateLimiter(long windowMillis, int maxFrames) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("窗口必须为正数，实际为 " + windowMillis);
        }
        if (maxFrames <= 0) {
            throw new IllegalArgumentException("上限必须为正数，实际为 " + maxFrames);
        }
        this.windowMillis = windowMillis;
        this.maxFrames = maxFrames;
        this.closeAfterMillis = windowMillis * VIOLATION_WINDOWS_BEFORE_CLOSE;
    }

    /** 限速判定结果。 */
    enum Verdict {
        /** 放行。 */
        ALLOW,
        /** 超限：回 {@code ERROR{code:RATE_LIMITED}}，但不关闭。 */
        WARN,
        /** 持续超限：关闭 4429。 */
        CLOSE,
    }

    /**
     * 记一帧并给出处置。
     *
     * <p>超限的帧**不记入窗口**：否则持续滥用会把窗口填满，使"窗口内帧数"永远超限，
     * 于是客户端即使真的降低频率也要等一整段安静期才能恢复——
     * 而文档要求的是"降低帧发送频率后重连"，不是"静默一段时间后恢复"。
     */
    Verdict record(long now) {
        evictExpired(now);

        if (timestamps.size() >= maxFrames) {
            everExceeded = true;
            /*
             * "持续超限"的判据是**两次违规之间的间隔不超过 closeAfterMillis**。
             *
             * 我试过两种更复杂的写法，都被测试否掉了：
             * - "只要放行就重置计时"：窗口一过期就放行一帧，那一帧把计时清零，
             *   于是 4429 永远发不出去；
             * - "窗口清空才重置"：一个刚好卡在阈值上的客户端会让窗口反复清空，
             *   同样永远不关闭。
             *
             * 间隔判据同时满足两边：真正在滥用的客户端会不断产生新违规（间隔很短），
             * 而偶发突发的客户端不再违规，间隔自然拉开——它根本不会再进入这个分支，
             * 因此也不需要在别处"重置"什么。
             */
            boolean sustained = lastViolationAt != null && now - lastViolationAt <= closeAfterMillis;
            if (violationSince == null || !sustained) {
                violationSince = now;
            }
            lastViolationAt = now;
            return now - violationSince >= closeAfterMillis ? Verdict.CLOSE : Verdict.WARN;
        }

        timestamps.addLast(now);
        return Verdict.ALLOW;
    }

    /** 当前窗口内的帧数。 */
    int windowCount(long now) {
        evictExpired(now);
        return timestamps.size();
    }

    /** 生效窗口毫秒；供诊断与配置确认。 */
    long windowMillis() {
        return windowMillis;
    }

    /** 生效的窗口内上限；供诊断与配置确认。 */
    int maxFrames() {
        return maxFrames;
    }

    boolean everExceeded() {
        return everExceeded;
    }

    void reset() {
        timestamps.clear();
        violationSince = null;
    }

    private void evictExpired(long now) {
        while (!timestamps.isEmpty() && now - timestamps.peekFirst() >= windowMillis) {
            timestamps.removeFirst();
        }
    }
}
