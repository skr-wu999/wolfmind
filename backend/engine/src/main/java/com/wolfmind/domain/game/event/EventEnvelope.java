package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GameException;
import com.wolfmind.domain.game.model.GameStatus;
import com.wolfmind.domain.game.model.Visibility;

import java.util.Map;

/**
 * 事件信封（docs/architecture/event-design.md §2）。
 *
 * <p>信封是"可持久化的事实"：{@code version} 是流内定位依据，{@link DomainEvent#payload()} 是
 * 事件专属数据，{@code visibility} 决定谁可以读。
 *
 * <p>完整性规则：字段缺失必须在写入前被拦截，不允许"默认值兜底"，否则重放会因字段歧义
 * 产生不同状态（event-design.md §2）。
 */
public record EventEnvelope(
        String eventId,
        String gameId,
        int version,
        String type,
        int schemaVersion,
        long occurredAt,
        String phase,
        int round,
        String actorId,
        Visibility visibility,
        String causationId,
        String correlationId,
        Map<String, Object> payload) {

    /** 当前信封结构版本。只有信封结构本身变更才递增（与事件 {@code schemaVersion} 相互独立）。 */
    public static final int ENVELOPE_VERSION = 1;

    public EventEnvelope {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId 不能为空");
        }
        if (gameId == null || gameId.isBlank()) {
            throw new IllegalArgumentException("gameId 不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version 必须 ≥ 1，实际为 " + version);
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type 不能为空");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion 必须 ≥ 1");
        }
        if (occurredAt < 0) {
            throw new IllegalArgumentException("occurredAt 不能为负");
        }
        if (phase == null || phase.isBlank()) {
            throw new IllegalArgumentException("phase 不能为空");
        }
        if (round < 1) {
            throw new IllegalArgumentException("round 必须 ≥ 1");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId 不能为空");
        }
        if (visibility == null) {
            throw new IllegalArgumentException("visibility 不能为空");
        }
        if (causationId == null || causationId.isBlank()) {
            throw new IllegalArgumentException("causationId 不能为空");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId 不能为空");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为空，事件专属数据必须显式给出");
        }
        payload = EventPayloads.seal(payload);
    }

    /** 由领域事件与流内版本构造信封；这是写入路径的唯一入口。 */
    public static EventEnvelope of(DomainEvent event, String gameId, int version) {
        return new EventEnvelope(
                event.eventId(),
                gameId,
                version,
                event.type().code(),
                event.schemaVersion(),
                event.occurredAt(),
                event.phase().name(),
                event.round(),
                event.actorId(),
                event.visibility(),
                event.causationId(),
                event.correlationId(),
                event.payload());
    }

    /** 事件类型是否已被契约目录收录；未知类型必须显式失败。 */
    public EventType eventType() {
        return EventType.fromCode(type);
    }

    /**
     * 该事件在赛后可回放：对局结束（{@code FINISHED} / {@code ABORTED}）后所有可见性都放开。
     */
    public boolean visibleAfterGame(GameStatus status) {
        return status == GameStatus.FINISHED || status == GameStatus.ABORTED;
    }

    /** 便于日志与断言：只含 type 与 version，不含 payload（避免日志成为泄漏渠道）。 */
    public String describe() {
        return type + "@" + version;
    }

    /** 供消费层做去重键（event-design.md §10：以 {@code eventId} 去重）。 */
    public String dedupKey() {
        return gameId + ":" + eventId;
    }

    /** 版本不连续时抛出：事件流不允许空洞（INV-11）。 */
    public void assertFollows(int expectedVersion) {
        if (version != expectedVersion) {
            throw GameException.Factory.replayOutOfOrder(expectedVersion, version);
        }
    }
}
