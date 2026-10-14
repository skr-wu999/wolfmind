package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GamePhase;

/**
 * 事件信封的公共部分（event-design.md §2 的必填字段）。
 *
 * <p>每个具体事件记录都携带一个 {@code EventMeta}，从而无法"忘记"写入某个信封字段：
 * 信封字段的缺失必须在写入前被拦截，不允许默认值兜底，否则重放会因字段歧义产生不同状态。
 *
 * @param eventId       事件唯一标识，全局唯一且可排序
 * @param version       该事件在 {@code gameId} 内的聚合版本，从 1 开始严格递增
 * @param occurredAt    毫秒时间戳，由 {@code ActionContext} 注入
 * @param phase         事件发生时的阶段
 * @param round         事件发生时的轮次
 * @param actorId       触发者：userId / playerId / SYSTEM
 * @param causationId   直接触发本次事件的命令 id 或上游 eventId
 * @param correlationId 一次完整用例的关联 id
 */
public record EventMeta(
        String eventId,
        int version,
        long occurredAt,
        GamePhase phase,
        int round,
        String actorId,
        String causationId,
        String correlationId) {

    public EventMeta {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version 必须 ≥ 1，实际为 " + version);
        }
        if (occurredAt < 0) {
            throw new IllegalArgumentException("occurredAt 不能为负");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase 不能为空");
        }
        if (round < 1) {
            throw new IllegalArgumentException("round 必须 ≥ 1，实际为 " + round);
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId 不能为空");
        }
        if (causationId == null || causationId.isBlank()) {
            throw new IllegalArgumentException("causationId 不能为空");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId 不能为空");
        }
    }
}
