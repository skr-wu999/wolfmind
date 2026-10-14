package com.wolfmind.domain.game.event;

import com.wolfmind.domain.game.model.GamePhase;
import com.wolfmind.domain.game.model.PlayerId;
import com.wolfmind.domain.game.model.Visibility;

import java.util.Map;

/**
 * 领域事件契约。
 *
 * <p>{@code type} 是契约，Java 类名是实现细节（event-design.md §6）；字段名与统一语言一致
 * （ddd-design.md §1.3）。事件一旦写入不可修改、不可删除，只允许追加（INV-14）。
 *
 * <p>{@code payload()} 是 {@code type} 的确定性序列化形式：map 的键顺序稳定、值只用
 * String / Integer / Long / Boolean / List / Map，便于 Stream 字段与 SQLite 归档共用同一份编码。
 *
 * <p>每个具体事件记录都必须携带 {@link EventMeta}，信封字段通过默认方法转发；这样"忘记写某个
 * 信封字段"在编译期就不可能发生（event-design.md §2 的完整性规则）。
 */
public interface DomainEvent {

    /** 事件信封的公共部分。 */
    EventMeta meta();

    /** 事件类型字符串（UPPER_SNAKE），{@link EventType} 目录是它的唯一定义处。 */
    EventType type();

    /** 该 {@code type} 的载荷版本：新增必填字段时 +1，并在 upcaster 中为旧版本补默认值。 */
    int schemaVersion();

    /**
     * 可见性（event-design.md §4）。
     *
     * <p>可见性是事件的属性而不是推送层的临时判断：WebSocket 推送、AI 工具、回放接口共用同一份判定。
     */
    Visibility visibility();

    /** {@code PRIVATE} 事件的目标玩家；非 {@code PRIVATE} 事件返回 {@code null}。 */
    PlayerId targetPlayerId();

    /** 事件的载荷字段，键名与 event-design.md §7 的 payload 列逐字一致。 */
    Map<String, Object> payload();

    default GamePhase phase() {
        return meta().phase();
    }

    default int round() {
        return meta().round();
    }

    default String actorId() {
        return meta().actorId();
    }

    default String eventId() {
        return meta().eventId();
    }

    default String causationId() {
        return meta().causationId();
    }

    default String correlationId() {
        return meta().correlationId();
    }

    default long occurredAt() {
        return meta().occurredAt();
    }

    default int version() {
        return meta().version();
    }
}
