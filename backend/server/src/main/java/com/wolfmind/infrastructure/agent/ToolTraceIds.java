package com.wolfmind.infrastructure.agent;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trace/关联 id 生成（tool-gateway.md §5 {@code traceId}、§7 审计）。
 *
 * <p>为什么需要它：{@code traceId} 必须与提交事件的 {@code correlationId} <b>一致</b>，
 * 否则"从审计记录追到对局事件"这条路断掉，AI 分析页就没法把一次工具调用与它产生的事件对上。
 * 因此网关为每次调用生成一个 id，同时写进审计与 {@code SubmitActionCommand.correlationId}。
 *
 * <p>格式 {@code cor_<base36 序号>_<身份指纹>}：序号保证同进程内唯一（用原子计数器而不是随机数，
 * 测试与日志可比对），指纹让同一座位同一工具的多次调用在日志里可读。
 */
public final class ToolTraceIds {

    private final AtomicLong sequence = new AtomicLong();

    public String next(String gameId, String playerId, String toolName) {
        long ordinal = sequence.incrementAndGet();
        int fingerprint = Objects.hash(gameId, playerId, toolName) & 0x7fffffff;
        return "cor_" + Long.toString(ordinal, 36) + "_" + Integer.toHexString(fingerprint);
    }

    /** 已生成的 id 数，供测试断言"每次调用都新建一个 id"。 */
    public long issued() {
        return sequence.get();
    }
}
